package xin.neko.fantasynetwork.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 搜索接口 {@code POST /api/music/search} 的薄封装，指令与界面共用同一份解析逻辑。
 *
 * <p>结果回调统一丢回调用方给的 {@code callbackExecutor}（界面上就是游戏主线程），
 * 因此界面侧可以直接改状态。
 */
public final class MusicSearch {

    /**
     * 搜索结果里的最小信息集。
     *
     * <p>后端不在搜索结果里给封面 / 直链：需要时用 {@code /api/music/cover/{id}} 与
     * {@code /api/music/file/{id}}。
     */
    public record Track(long id, String title, String artist, int durationSeconds) {

        /** {@code m:ss} 形式的时长。 */
        public String durationLabel() {
            int safe = Math.max(0, durationSeconds);
            return String.format("%d:%02d", safe / 60, safe % 60);
        }
    }

    /** 搜索成功时的结果；{@code message} 是后端附带的提示（没有结果时往往是原因）。 */
    public record Result(List<Track> tracks, String message) {
    }

    private MusicSearch() {
    }

    public static void search(String query, Executor callbackExecutor,
                              Consumer<Result> onSuccess, Consumer<String> onError) {
        JsonObject body = new JsonObject();
        body.addProperty("query", query);

        NekoApi.post("/api/music/search", null, body).whenComplete((response, error) ->
                callbackExecutor.execute(() -> {
                    if (error != null) {
                        onError.accept(NekoApi.errorMessage(error));
                        return;
                    }
                    onSuccess.accept(parse(response));
                }));
    }

    private static Result parse(ApiResponse response) {
        JsonArray array = response.array("results");
        List<Track> tracks = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject music = element.getAsJsonObject();
            tracks.add(new Track(
                    longValue(music, "id"),
                    string(music, "title"),
                    string(music, "artist"),
                    intValue(music, "duration")));
        }
        return new Result(List.copyOf(tracks), response.message());
    }

    private static String string(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static int intValue(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return 0;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long longValue(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return 0L;
        }
        try {
            return element.getAsLong();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
