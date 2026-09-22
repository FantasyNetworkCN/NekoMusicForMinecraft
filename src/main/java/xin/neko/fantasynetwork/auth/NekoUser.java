package xin.neko.fantasynetwork.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * 当前登录用户，字段与登录接口 / 用户信息接口返回的 {@code user} 对象一致。
 *
 * @param vip 是否 VIP（后端字段名为 {@code isVip}）
 */
public record NekoUser(long id, String nickname, String email, String createdAt, boolean vip) {

    public static NekoUser fromJson(JsonObject json) {
        return new NekoUser(
                longValue(json, "id"),
                stringValue(json, "nickname"),
                stringValue(json, "email"),
                stringValue(json, "createdAt"),
                booleanValue(json, "isVip"));
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("nickname", nickname);
        json.addProperty("email", email);
        json.addProperty("createdAt", createdAt);
        json.addProperty("isVip", vip);
        return json;
    }

    /** 界面上展示用的名字，昵称为空时退化成 ID。 */
    public String displayName() {
        return nickname == null || nickname.isBlank() ? "ID " + id : nickname;
    }

    private static String stringValue(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static long longValue(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return 0L;
        }
        try {
            return element.getAsLong();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean booleanValue(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isBoolean() && primitive.getAsBoolean();
    }
}
