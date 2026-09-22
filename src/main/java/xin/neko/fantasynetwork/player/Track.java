package xin.neko.fantasynetwork.player;

import java.util.Objects;

/**
 * 一首可播放的曲目。接入真实音源后，这里可以再挂上歌曲 id、封面地址等字段。
 *
 * @param title          曲名
 * @param artist         歌手，未知时可以传 null
 * @param durationMillis 总时长，毫秒
 */
public record Track(String title, String artist, long durationMillis) {

    /** 接入真实音源前的占位曲目，仅用于验证 HUD 渲染。 */
    public static final Track DEMO = new Track("Neko 云音乐", "FantasyNetwork", 215_000L);

    public Track {
        Objects.requireNonNull(title, "title");
        artist = artist == null ? "" : artist;
        if (durationMillis <= 0L) {
            throw new IllegalArgumentException("durationMillis must be positive");
        }
    }

    public boolean hasArtist() {
        return !artist.isBlank();
    }
}
