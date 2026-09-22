package xin.neko.fantasynetwork.api;

/** 调用 Neko歌姬计划接口失败时抛出。 */
public class ApiException extends RuntimeException {

    /** HTTP 状态码，网络层失败（未拿到响应）时为 -1。 */
    private final int statusCode;

    public ApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public int statusCode() {
        return statusCode;
    }

    /** Token 失效 / 未登录。 */
    public boolean unauthorized() {
        return statusCode == 401 || statusCode == 403;
    }
}
