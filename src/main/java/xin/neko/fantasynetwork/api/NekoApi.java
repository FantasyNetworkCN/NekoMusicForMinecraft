package xin.neko.fantasynetwork.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Neko歌姬计划 HTTP 客户端。
 *
 * <p>只提供最基础的 JSON 请求能力，所有方法都在 {@link HttpClient} 的线程池上异步执行，
 * 不会阻塞游戏主线程；失败（网络异常、响应不是 JSON）以 {@link ApiException} 结束 future，
 * 业务失败（{@code success:false}）则作为普通 {@link ApiResponse} 返回，由调用方决定怎么提示。
 */
public final class NekoApi {

    /** 线上基础地址，与 PC 端 / Android 端保持一致。 */
    public static final String BASE_URL = "https://music.cnmsb.xin";

    private static final Gson GSON = new Gson();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private NekoApi() {
    }

    public static CompletableFuture<ApiResponse> get(String path, String token) {
        return send("GET", path, token, null);
    }

    public static CompletableFuture<ApiResponse> post(String path, String token, JsonObject body) {
        return send("POST", path, token, body == null ? new JsonObject() : body);
    }

    /**
     * 打开一个二进制资源（音频 / 封面）的响应体，返回的流是「边下边读」的：
     * 调用方读完必须关掉，否则这条 HTTP 连接不会释放。流只给一个线程读。
     */
    public static CompletableFuture<InputStream> openStream(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "NekoMusicForMinecraft")
                .GET()
                .build();

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .handle((response, error) -> {
                    if (error != null) {
                        throw new CompletionException(transportError(error));
                    }
                    int status = response.statusCode();
                    if (status != 200) {
                        closeQuietly(response.body());
                        throw new CompletionException(new ApiException(status, "资源下载失败（HTTP " + status + "）"));
                    }
                    return response.body();
                });
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // 关了就行，关不掉也没别的办法
        }
    }

    private static CompletableFuture<ApiResponse> send(String method, String path, String token, JsonObject body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "NekoMusicForMinecraft");

        if (token != null && !token.isBlank()) {
            // 后端同时兼容 Authorization: <token> 与 Authorization: Bearer <token>
            builder.header("Authorization", token);
        }

        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json; charset=UTF-8");
            builder.method(method, HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        }

        return CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, error) -> {
                    if (error != null) {
                        throw new CompletionException(transportError(error));
                    }
                    return parse(response);
                });
    }

    /** 解开 {@link CompletableFuture} 包装的 {@link CompletionException}，拿到真正的异常。 */
    public static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** 把异常转成可以直接展示给玩家的一行文案。 */
    public static String errorMessage(Throwable error) {
        Throwable cause = unwrap(error);
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static ApiResponse parse(HttpResponse<String> response) {
        String body = response.body();
        if (body != null && !body.isBlank()) {
            try {
                JsonElement element = JsonParser.parseString(body);
                if (element.isJsonObject()) {
                    return new ApiResponse(response.statusCode(), element.getAsJsonObject());
                }
            } catch (JsonParseException ignored) {
                // 落到下面按不可解析响应处理
            }
        }
        throw new ApiException(response.statusCode(), "服务器返回了无法解析的响应（HTTP " + response.statusCode() + "）");
    }

    private static ApiException transportError(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return new ApiException("连接 Neko 云音乐失败：" + cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : " " + cause.getMessage()), cause);
    }
}
