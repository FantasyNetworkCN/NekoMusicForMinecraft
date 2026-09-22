package xin.neko.fantasynetwork.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import xin.neko.fantasynetwork.api.NekoApi;
import xin.neko.fantasynetwork.util.QrCodeRenderer;
import xin.neko.fantasynetwork.util.QrMatrix;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 扫码登录状态机：创建会话 → SSE 订阅状态 → 确认后写入 {@link AuthManager}。
 *
 * <p>流程与 PC 端一致：
 * <ol>
 *   <li>{@code POST /api/user/qrlogin/create} 拿 sessionId 与二维码内容；</li>
 *   <li>{@code GET /api/user/qrlogin/status?sessionId=...} 建立 SSE 长连接，等待 pending / scanned / confirmed；</li>
 *   <li>收到 confirmed 帧里的 token 与 user 即完成登录。</li>
 * </ol>
 *
 * <p>所有网络动作都在后台线程完成，状态变化通过 {@link Listener} 通知（可能在任意线程回调，
 * 需要在主线程改界面的监听方自己切线程）。
 */
public final class QrLoginService {

    public enum State {
        /** 没有进行中的会话。 */
        IDLE,
        /** 正在向后端申请二维码。 */
        CREATING,
        /** 二维码已展示，等待手机扫码。 */
        PENDING,
        /** 手机已扫码，等待确认。 */
        SCANNED,
        /** 手机已确认，登录完成。 */
        CONFIRMED,
        /** 手机侧拒绝登录。 */
        CANCELED,
        /** 会话过期（二维码需要刷新）。 */
        EXPIRED,
        /** 创建 / 订阅失败。 */
        ERROR
    }

    public interface Listener {
        void onSessionChanged(Session session);
    }

    /** 一次扫码会话的可读状态快照，字段全部 volatile，界面可直接轮询。 */
    public static final class Session {

        private volatile State state = State.IDLE;
        private volatile String sessionId = "";
        private volatile String qrContent = "";
        private volatile QrMatrix matrix;
        private volatile long expiresAtMillis;
        private volatile String errorMessage = "";

        private Session() {
        }

        public State state() {
            return state;
        }

        public String sessionId() {
            return sessionId;
        }

        /** 二维码内容，形如 {@code nekomusic://qrlogin?sid=xxx}。 */
        public String qrContent() {
            return qrContent;
        }

        public QrMatrix matrix() {
            return matrix;
        }

        /** 二维码剩余有效秒数，非 PENDING 状态返回 0。 */
        public long remainingSeconds() {
            if (state != State.PENDING && state != State.SCANNED) {
                return 0L;
            }
            long remaining = expiresAtMillis - System.currentTimeMillis();
            return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
        }

        public String errorMessage() {
            return errorMessage;
        }

        public boolean terminal() {
            return state == State.CONFIRMED || state == State.CANCELED
                    || state == State.EXPIRED || state == State.ERROR;
        }

        private void reset() {
            state = State.CREATING;
            sessionId = "";
            qrContent = "";
            matrix = null;
            expiresAtMillis = 0L;
            errorMessage = "";
        }

        private void begin(String sessionId, String qrContent, QrMatrix matrix, int expiresInSeconds) {
            this.sessionId = sessionId;
            this.qrContent = qrContent;
            this.matrix = matrix;
            this.expiresAtMillis = System.currentTimeMillis() + Math.max(1, expiresInSeconds) * 1000L;
            this.state = State.PENDING;
            this.errorMessage = "";
        }

        private void fail(String message) {
            state = State.ERROR;
            errorMessage = message == null ? "" : message;
        }
    }

    private static final QrLoginService INSTANCE = new QrLoginService();
    private static final int DEFAULT_EXPIRES_SECONDS = 180;

    private final Session session = new Session();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /** 会话代次：新建 / 取消后自增，让旧的在途回调与 SSE 线程自动失效。 */
    private final AtomicInteger generation = new AtomicInteger();

    /** SSE 是长连接，单独用一个禁用重定向的 HTTP/1.1 客户端，避免 HTTP/2 长流被中间层切断。 */
    private final HttpClient sseClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private volatile Thread watchThread;
    private volatile InputStream watchStream;

    private QrLoginService() {
    }

    public static QrLoginService getInstance() {
        return INSTANCE;
    }

    public Session session() {
        return session;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** 开始一次全新的扫码会话（会自动作废上一次）。 */
    public Session start() {
        final int gen = generation.incrementAndGet();
        stopWatch();
        session.reset();
        publish();

        NekoApi.post("/api/user/qrlogin/create", null, new JsonObject()).whenComplete((response, error) -> {
            if (!isCurrent(gen)) {
                return;
            }
            if (error != null) {
                fail(gen, NekoApi.errorMessage(error));
                return;
            }
            if (!response.success()) {
                fail(gen, response.message().isBlank() ? "创建扫码会话失败" : response.message());
                return;
            }

            JsonObject data = response.data();
            String sessionId = string(data, "sessionId");
            String qrContent = string(data, "qrContent");
            if (sessionId.isBlank() || qrContent.isBlank()) {
                fail(gen, "服务端未返回二维码内容");
                return;
            }

            int expiresIn = data.has("expiresIn") && data.get("expiresIn").isJsonPrimitive()
                    ? Math.max(1, data.get("expiresIn").getAsInt())
                    : DEFAULT_EXPIRES_SECONDS;

            QrMatrix matrix;
            try {
                matrix = QrCodeRenderer.encode(qrContent);
            } catch (RuntimeException e) {
                fail(gen, "二维码生成失败：" + e.getMessage());
                return;
            }

            if (!isCurrent(gen)) {
                return;
            }
            session.begin(sessionId, qrContent, matrix, expiresIn);
            publish();
            startWatch(gen, sessionId);
        });
        return session;
    }

    /** 作废当前会话并断开 SSE 连接。 */
    public void cancel() {
        generation.incrementAndGet();
        stopWatch();
        session.reset();
        session.state = State.IDLE;
        publish();
    }

    private void startWatch(int gen, String sessionId) {
        Thread thread = new Thread(() -> watch(gen, sessionId), "nekomusic-qrlogin");
        thread.setDaemon(true);
        watchThread = thread;
        thread.start();
    }

    private void watch(int gen, String sessionId) {
        String encoded = URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(NekoApi.BASE_URL + "/api/user/qrlogin/status?sessionId=" + encoded))
                .header("Accept", "text/event-stream")
                .header("User-Agent", "NekoMusicForMinecraft")
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response = sseClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            InputStream stream = response.body();
            watchStream = stream;
            if (response.statusCode() != 200) {
                String body = readAll(stream);
                fail(gen, describeHttpFailure(response.statusCode(), body));
                return;
            }
            consume(gen, stream);
        } catch (IOException e) {
            // 主动断开连接时也会走到这里，只有会话仍有效才算真失败
            if (isCurrent(gen) && !session.terminal()) {
                fail(gen, "扫码连接中断：" + NekoApi.errorMessage(e));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            watchStream = null;
        }
    }

    private void consume(int gen, InputStream stream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        String eventName = null;
        StringBuilder data = new StringBuilder();
        String line;

        while (isCurrent(gen) && (line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (eventName != null || data.length() > 0) {
                    handleEvent(gen, eventName == null ? "message" : eventName, data.toString());
                }
                eventName = null;
                data.setLength(0);
                continue;
            }
            if (line.charAt(0) == ':') {
                continue; // 心跳 / 注释帧
            }
            if (line.startsWith("event:")) {
                eventName = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line.substring(5).trim());
            }
        }

        if (isCurrent(gen) && !session.terminal()) {
            // 服务端没给终态就断流了，多半是网络问题，让界面提示刷新
            fail(gen, "扫码连接已断开，请刷新二维码重试");
        }
    }

    private void handleEvent(int gen, String eventName, String payload) {
        if (!"status".equals(eventName) || payload.isBlank() || !isCurrent(gen)) {
            return;
        }
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(payload);
            if (!element.isJsonObject()) {
                return;
            }
            json = element.getAsJsonObject();
        } catch (JsonParseException e) {
            return;
        }

        switch (string(json, "status")) {
            case "pending" -> update(gen, State.PENDING);
            case "scanned" -> update(gen, State.SCANNED);
            case "confirmed" -> {
                String token = string(json, "token");
                if (token.isBlank()) {
                    fail(gen, "服务端未返回登录令牌");
                    return;
                }
                JsonObject user = json.has("user") && json.get("user").isJsonObject()
                        ? json.getAsJsonObject("user")
                        : null;
                AuthManager.getInstance().setSession(token, user);
                update(gen, State.CONFIRMED);
            }
            case "canceled" -> update(gen, State.CANCELED);
            case "expired" -> update(gen, State.EXPIRED);
            default -> {
                // 未知状态忽略
            }
        }
    }

    private void update(int gen, State state) {
        if (!isCurrent(gen)) {
            return;
        }
        session.state = state;
        publish();
    }

    private void fail(int gen, String message) {
        if (!isCurrent(gen)) {
            return;
        }
        session.fail(message);
        publish();
    }

    private boolean isCurrent(int gen) {
        return gen == generation.get();
    }

    private void stopWatch() {
        InputStream stream = watchStream;
        watchStream = null;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // 关流失败无所谓，线程会随代次失效自然退出
            }
        }
        Thread thread = watchThread;
        watchThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void publish() {
        for (Listener listener : listeners) {
            listener.onSessionChanged(session);
        }
    }

    private static String describeHttpFailure(int statusCode, String body) {
        if (body != null && !body.isBlank()) {
            try {
                JsonElement element = JsonParser.parseString(body);
                if (element.isJsonObject()) {
                    String message = new xin.neko.fantasynetwork.api.ApiResponse(statusCode, element.getAsJsonObject()).message();
                    if (!message.isBlank()) {
                        return message;
                    }
                }
            } catch (JsonParseException ignored) {
                // 用状态码兜底
            }
        }
        return "扫码会话不可用（HTTP " + statusCode + "）";
    }

    private static String readAll(InputStream stream) {
        try (InputStream in = stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String string(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }
}
