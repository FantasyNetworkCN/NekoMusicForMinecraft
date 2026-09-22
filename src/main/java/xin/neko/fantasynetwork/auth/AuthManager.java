package xin.neko.fantasynetwork.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import xin.neko.fantasynetwork.Main;
import xin.neko.fantasynetwork.api.ApiException;
import xin.neko.fantasynetwork.api.NekoApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 登录状态（Token + 用户资料）的唯一持有者。
 *
 * <p>只把 Token 与最近一次拿到的用户资料落在 {@code config/nekomusicforminecraft.json} 里，
 * 每次启动再用 {@code GET /api/user/info} 拉一次最新资料，避免本地缓存过期——与 PC 端策略一致。
 */
public final class AuthManager {

    /** 登录状态变化（登录 / 退出 / 资料刷新）时的回调。 */
    public interface Listener {
        void onAuthChanged(NekoUser user);
    }

    private static final AuthManager INSTANCE = new AuthManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path configFile;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile String token;
    private volatile NekoUser user;

    private AuthManager() {
        this.configFile = FabricLoader.getInstance().getConfigDir().resolve(Main.MOD_ID + ".json");
    }

    public static AuthManager getInstance() {
        return INSTANCE;
    }

    public boolean isLoggedIn() {
        return token != null && !token.isBlank();
    }

    public String token() {
        return token;
    }

    /** 可能为 null：只有 Token、还没拉到资料时。 */
    public NekoUser user() {
        return user;
    }

    /** 展示用昵称，资料还没到时不显示空字符串。 */
    public String displayName() {
        NekoUser current = user;
        return current == null ? "-" : current.displayName();
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void load() {
        token = null;
        user = null;
        if (!Files.isRegularFile(configFile)) {
            return;
        }
        try {
            JsonElement element = JsonParser.parseString(Files.readString(configFile, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) {
                return;
            }
            JsonObject json = element.getAsJsonObject();
            JsonElement tokenElement = json.get("token");
            if (tokenElement != null && tokenElement.isJsonPrimitive()) {
                String saved = tokenElement.getAsString();
                token = saved.isBlank() ? null : saved;
            }
            JsonElement userElement = json.get("user");
            if (userElement != null && userElement.isJsonObject()) {
                user = NekoUser.fromJson(userElement.getAsJsonObject());
            }
        } catch (IOException | JsonParseException e) {
            Main.LOGGER.warn("读取登录配置失败：{}", e.getMessage());
        }
    }

    /** 扫码确认后写入 Token 与用户资料。 */
    public void setSession(String newToken, JsonObject userJson) {
        if (newToken == null || newToken.isBlank()) {
            return;
        }
        token = newToken;
        user = userJson == null ? null : NekoUser.fromJson(userJson);
        save();
        notifyListeners();
    }

    public void logout() {
        token = null;
        user = null;
        save();
        notifyListeners();
    }

    /** 拉取最新用户资料；Token 失效（401）时会顺带清掉本地登录状态。 */
    public CompletableFuture<NekoUser> fetchUserInfo() {
        String currentToken = token;
        if (currentToken == null) {
            return CompletableFuture.failedFuture(new ApiException(401, "请先登录"));
        }
        return NekoApi.get("/api/user/info", currentToken).thenApply(response -> {
            if (response.statusCode() == 401 || !response.success()) {
                if (response.statusCode() == 401) {
                    logout();
                }
                String message = response.message();
                throw new ApiException(response.statusCode(), message.isBlank() ? "登录状态已失效" : message);
            }
            JsonObject data = response.data();
            JsonObject userJson = data.has("user") && data.get("user").isJsonObject()
                    ? data.getAsJsonObject("user")
                    : data;
            NekoUser fetched = NekoUser.fromJson(userJson);
            user = fetched;
            save();
            notifyListeners();
            return fetched;
        });
    }

    /** 启动时后台静默刷新，失败只记日志。 */
    public void refreshInBackground() {
        if (!isLoggedIn()) {
            return;
        }
        fetchUserInfo().whenComplete((fetched, error) -> {
            if (error != null) {
                Main.LOGGER.warn("刷新用户信息失败：{}", NekoApi.errorMessage(error));
            }
        });
    }

    private void save() {
        JsonObject json = new JsonObject();
        String currentToken = token;
        if (currentToken != null) {
            json.addProperty("token", currentToken);
            NekoUser currentUser = user;
            if (currentUser != null) {
                json.add("user", currentUser.toJson());
            }
        }
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Main.LOGGER.warn("保存登录配置失败：{}", e.getMessage());
        }
    }

    private void notifyListeners() {
        for (Listener listener : listeners) {
            listener.onAuthChanged(user);
        }
    }
}
