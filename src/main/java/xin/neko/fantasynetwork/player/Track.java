package xin.neko.fantasynetwork.player;

import java.util.Objects;

/**
 * 一首可播放的曲目，对应后端 {@code GET /api/music/file/{id}} 能取到的音源。
 *
 * @param id             Neko 云音乐里的曲目 id，拼接音频 / 封面地址时用
 * @param title          曲名
 * @param artist         歌手，未知时可以传 null
 * @param durationMillis 总时长，毫秒
 */
public record Track(long id, String title, String artist, long durationMillis) {

    public Track {
        if (id <= 0L) {
            throw new IllegalArgumentException("id must be positive");
        }
        Objects.requireNonNull(title, "title");
        artist = artist == null ? "" : artist;
        if (durationMillis <= 0L) {
            throw new IllegalArgumentException("durationMillis must be positive");
        }
    }

    /** 搜索结果里的时长只有秒、且可能缺失，这里兜一个下限免得整条记录构造失败。 */
    public static Track of(long id, String title, String artist, int durationSeconds) {
        return new Track(id, title, artist, Math.max(1, durationSeconds) * 1000L);
    }

    public boolean hasArtist() {
        return !artist.isBlank();
    }

    public String durationLabel() {
        return formatTime(durationMillis);
    }

    /** {@code m:ss} 形式的时长。 */
    public static String formatTime(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        return String.format("%d:%02d", seconds / 60L, seconds % 60L);
    }
}
