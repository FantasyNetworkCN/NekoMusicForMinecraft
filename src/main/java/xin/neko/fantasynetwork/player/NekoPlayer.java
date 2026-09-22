package xin.neko.fantasynetwork.player;

/**
 * 播放器的全局状态。真正的音频解码与网络播放接进来之后，只需要在这类里更新
 * {@code track} / {@code positionMillis}，界面侧不用改。
 */
public final class NekoPlayer {

    private static final NekoPlayer INSTANCE = new NekoPlayer();

    private Track track;
    private long positionMillis;
    private boolean playing;

    private NekoPlayer() {
    }

    public static NekoPlayer getInstance() {
        return INSTANCE;
    }

    public boolean hasTrack() {
        return track != null;
    }

    public Track getTrack() {
        return track;
    }

    public long getPositionMillis() {
        return positionMillis;
    }

    public boolean isPlaying() {
        return playing && track != null;
    }

    public void play(Track newTrack) {
        if (newTrack == null) {
            return;
        }
        if (!newTrack.equals(track)) {
            track = newTrack;
            positionMillis = 0L;
        }
        playing = true;
    }

    public void pause() {
        playing = false;
    }

    public void resume() {
        if (track != null) {
            playing = true;
        }
    }

    public void toggle() {
        if (isPlaying()) {
            pause();
        } else {
            resume();
        }
    }

    public void stop() {
        track = null;
        positionMillis = 0L;
        playing = false;
    }

    public void seek(long millis) {
        positionMillis = clamp(millis);
    }

    /** 客户端每个 tick 调用一次，推进播放进度。 */
    public void tick(long deltaMillis) {
        if (!isPlaying()) {
            return;
        }
        positionMillis += Math.max(0L, deltaMillis);
        if (positionMillis >= track.durationMillis()) {
            positionMillis = track.durationMillis();
            playing = false;
        }
    }

    /** 播放进度，0.0 ~ 1.0。 */
    public float getProgress() {
        if (track == null) {
            return 0.0F;
        }
        return (float) (Math.min(positionMillis, track.durationMillis()) / (double) track.durationMillis());
    }

    private long clamp(long millis) {
        if (track == null) {
            return 0L;
        }
        return Math.max(0L, Math.min(millis, track.durationMillis()));
    }
}
