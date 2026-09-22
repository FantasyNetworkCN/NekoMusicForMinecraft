package xin.neko.fantasynetwork.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 后端统一响应信封 {@code {"success":bool,"message":string,...}}。
 *
 * <p>不同接口把业务数据放在 {@code data} 或顶层（例如搜索接口的 {@code results}），
 * 所以这里只做通用解析，由调用方按接口文档取字段。
 */
public record ApiResponse(int statusCode, JsonObject body) {

    public boolean success() {
        JsonElement element = body.get("success");
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }

    /** 错误 / 提示文案，按 {@code message} → {@code msg} → {@code error} 依次取。 */
    public String message() {
        for (String key : new String[]{"message", "msg", "error"}) {
            JsonElement element = body.get(key);
            if (element != null && element.isJsonPrimitive()) {
                return element.getAsString();
            }
        }
        return "";
    }

    /** {@code data} 对象字段；不存在时返回空对象，便于直接 get。 */
    public JsonObject data() {
        JsonElement element = body.get("data");
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    /** 顶层数组字段（如搜索接口的 {@code results}）；不存在或为 null 时返回空数组。 */
    public JsonArray array(String key) {
        JsonElement element = body.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }
}
