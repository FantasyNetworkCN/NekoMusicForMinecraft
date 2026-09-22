package xin.neko.fantasynetwork.player;

import net.minecraft.text.Text;
import org.jflac.sound.spi.FlacAudioFileReader;
import org.jflac.sound.spi.FlacFormatConversionProvider;
import xin.neko.fantasynetwork.Main;
import xin.neko.fantasynetwork.api.NekoApi;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.InputStream;

/**
 * 真正的音频播放器：从 {@code /api/music/file/{id}} 边下边播。
 *
 * <p>解码用的是 JDK 自带的 Java Sound——曲库里的音频是 WAV（上传件）或 FLAC（无损音源），
 * 前者 Java Sound 直接认，后者交给打进 mod 的 jFLAC 解成 PCM。播放全程跑在一条守护线程上，
 * 游戏主线程只读状态（进度直接问音频线要，不是自己数的），所以放歌不影响 tick。
 *
 * <p>暂停是「停止喂数据」而不是停声卡：声卡缓冲区里那点数据放完就静音，最多差一两百毫秒，
 * 好处是任何时刻都不会有线程卡在阻塞写里。
 */
public final class NekoPlayer {

    /** 播放器状态，面板与 HUD 都靠它决定显示什么。 */
    public enum Status {
        IDLE, LOADING, PLAYING, PAUSED, ERROR
    }

    private static final NekoPlayer INSTANCE = new NekoPlayer();

    private static final String ERROR_DEVICE_KEY = "player." + Main.MOD_ID + ".error.device";
    private static final String ERROR_FORMAT_KEY = "player." + Main.MOD_ID + ".error.format";

    private static final int STREAM_BUFFER = 1 << 16;
    private static final int READ_BUFFER = 1 << 13;
    /** 声卡缓冲区：小一点暂停才跟手，又不能小到容易断音。 */
    private static final int LINE_BUFFER = 1 << 15;
    private static final float FALLBACK_SAMPLE_RATE = 44100.0F;
    private static final float DEFAULT_VOLUME = 0.75F;
    private static final float VOLUME_STEP = 0.05F;
    private static final long PAUSE_POLL_MILLIS = 10L;

    private final Object lineLock = new Object();

    private volatile Track track;
    private volatile Status status = Status.IDLE;
    private volatile String errorMessage = "";
    private volatile boolean paused;
    private volatile float volume = DEFAULT_VOLUME;
    /** 每次切歌 / 停止都自增，播放线程靠它判断自己是不是已经过期。 */
    private volatile int generation;

    private SourceDataLine line;
    private AudioFormat lineFormat;
    /** 播完之后声卡位置会清零，进度条靠它留在结尾。 */
    private long finishedPositionMillis;
    private volatile InputStream source;
    private Thread worker;

    private NekoPlayer() {
    }

    public static NekoPlayer getInstance() {
        return INSTANCE;
    }

    public Track getTrack() {
        return track;
    }

    public Status getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean hasTrack() {
        return track != null;
    }

    public boolean isPlaying() {
        return status == Status.PLAYING;
    }

    public boolean isPaused() {
        return status == Status.PAUSED;
    }

    /** 当前曲目是不是还在播 / 暂停着（加载中也算）。 */
    public boolean isActive() {
        return status == Status.LOADING || status == Status.PLAYING || status == Status.PAUSED;
    }

    public float getVolume() {
        return volume;
    }

    /** 从头开始播放一首曲子；正在放的那首会被顶掉。 */
    public void play(Track newTrack) {
        if (newTrack == null) {
            return;
        }
        stopInternal(true);
        track = newTrack;
        errorMessage = "";
        paused = false;
        status = Status.LOADING;

        int gen = ++generation;
        Thread thread = new Thread(() -> run(gen, newTrack), "nekomusic-player");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    public void pause() {
        if (status == Status.PLAYING) {
            paused = true;
            status = Status.PAUSED;
        }
    }

    public void resume() {
        if (status == Status.PAUSED) {
            paused = false;
            status = Status.PLAYING;
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
        stopInternal(false);
    }

    public void setVolume(float value) {
        volume = Math.max(0.0F, Math.min(1.0F, value));
        applyVolume();
    }

    public void adjustVolume(float delta) {
        setVolume(volume + delta);
    }

    public void volumeUp() {
        adjustVolume(VOLUME_STEP);
    }

    public void volumeDown() {
        adjustVolume(-VOLUME_STEP);
    }

    /** 已播放的毫秒数，直接取声卡位置；暂停时它自己会停住。 */
    public long getPositionMillis() {
        SourceDataLine current;
        AudioFormat format;
        synchronized (lineLock) {
            current = line;
            format = lineFormat;
        }
        if (current == null || format == null || format.getSampleRate() <= 0.0F) {
            return finishedPositionMillis;
        }
        long millis = (long) (current.getLongFramePosition() * 1000.0 / format.getSampleRate());
        Track currentTrack = track;
        if (currentTrack != null) {
            millis = Math.min(millis, currentTrack.durationMillis());
        }
        return Math.max(0L, millis);
    }

    /** 播放进度，0.0 ~ 1.0。 */
    public float getProgress() {
        Track currentTrack = track;
        if (currentTrack == null) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, getPositionMillis() / (float) currentTrack.durationMillis()));
    }

    private void stopInternal(boolean keepTrack) {
        generation++;
        paused = false;
        closeQuietly(source);
        source = null;
        Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
        }
        // 音频线交给播放线程自己关（它可能正阻塞在 write 里），这边只把状态清干净
        synchronized (lineLock) {
            line = null;
            lineFormat = null;
        }
        finishedPositionMillis = 0L;
        if (!keepTrack) {
            track = null;
            errorMessage = "";
            status = Status.IDLE;
        }
    }

    private void run(int gen, Track playingTrack) {
        SourceDataLine localLine = null;
        AudioInputStream audio = null;
        InputStream stream = null;
        try {
            stream = NekoApi.openStream("/api/music/file/" + playingTrack.id()).join();
            source = stream;
            audio = openAudioStream(new BufferedInputStream(stream, STREAM_BUFFER));

            localLine = tryOpenLine(audio.getFormat());
            if (localLine == null) {
                // 声卡不一定吃得下 96kHz 之类的规格，退回 44.1kHz 再试一次
                AudioFormat source = audio.getFormat();
                AudioFormat fallback = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, FALLBACK_SAMPLE_RATE, 16,
                        source.getChannels(), source.getChannels() * 2, FALLBACK_SAMPLE_RATE, false);
                audio = AudioSystem.getAudioInputStream(fallback, audio);
                localLine = tryOpenLine(audio.getFormat());
            }
            if (localLine == null) {
                throw new IllegalStateException(Text.translatable(ERROR_DEVICE_KEY).getString());
            }

            localLine.open(audio.getFormat(), LINE_BUFFER);
            applyVolume(localLine, volume);
            localLine.start();

            synchronized (lineLock) {
                line = localLine;
                lineFormat = audio.getFormat();
            }
            paused = false;
            status = Status.PLAYING;

            byte[] buffer = new byte[READ_BUFFER];
            while (generation == gen) {
                if (paused) {
                    sleepQuietly(PAUSE_POLL_MILLIS);
                    continue;
                }
                int read = audio.read(buffer);
                if (read < 0) {
                    break;
                }
                localLine.write(buffer, 0, read);
            }
            if (generation == gen) {
                // 把声卡里剩下的那点数据放完，听感和进度条都不会被砍断
                localLine.drain();
            }
        } catch (Throwable error) {
            if (generation == gen) {
                errorMessage = NekoApi.errorMessage(error);
                status = Status.ERROR;
                Main.LOGGER.warn("Neko 云音乐播放失败：{}", errorMessage, error);
            }
        } finally {
            // 先摘掉引用再关线，免得主线程正好读到一条已经关掉的音频线
            if (generation == gen && status != Status.ERROR) {
                finishedPositionMillis = playingTrack.durationMillis();
            }
            synchronized (lineLock) {
                if (line == localLine) {
                    line = null;
                    lineFormat = null;
                }
            }
            closeQuietly(localLine);
            closeQuietly(audio);
            closeQuietly(stream);
            if (source == stream) {
                source = null;
            }
            if (generation == gen) {
                if (status != Status.ERROR) {
                    status = Status.IDLE;
                }
                paused = false;
            }
        }
    }

    /** 按魔数挑解码器：FLAC 走 jFLAC，其余（WAV/AIFF 等）交给 Java Sound，最后统一成 16bit PCM。 */
    private static AudioInputStream openAudioStream(BufferedInputStream stream) throws Exception {
        stream.mark(8);
        byte[] magic = stream.readNBytes(4);
        stream.reset();
        boolean flac = magic.length == 4 && magic[0] == 'f' && magic[1] == 'L'
                && magic[2] == 'a' && magic[3] == 'C';

        AudioInputStream decoded = flac
                ? new FlacAudioFileReader().getAudioInputStream(stream)
                : AudioSystem.getAudioInputStream(stream);
        AudioFormat format = decoded.getFormat();

        if (AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()) && format.getSampleSizeInBits() == 16) {
            return decoded;
        }
        int bits = format.getSampleSizeInBits();
        if (flac && bits > 0 && bits % 8 == 0 && bits != 16) {
            // jFLAC 的转换器只做位深不变的 FLAC→PCM，先解出原始位深，再让 JDK 压成 16bit
            AudioFormat raw = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, format.getSampleRate(), bits,
                    format.getChannels(), format.getChannels() * (bits / 8), format.getSampleRate(), false);
            decoded = new FlacFormatConversionProvider().getAudioInputStream(raw, decoded);
        }
        AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, format.getSampleRate(), 16,
                format.getChannels(), format.getChannels() * 2, format.getSampleRate(), false);
        try {
            return AudioSystem.getAudioInputStream(target, decoded);
        } catch (IllegalArgumentException e) {
            // 转不了就按原格式播，能出声总比直接报错强
            Main.LOGGER.warn("无法把 {} 转成 16bit PCM，按原格式播放", format, e);
            throw new IllegalStateException(Text.translatable(ERROR_FORMAT_KEY, format.toString()).getString(), e);
        }
    }

    private static SourceDataLine tryOpenLine(AudioFormat format) {
        try {
            return AudioSystem.getSourceDataLine(format);
        } catch (LineUnavailableException | IllegalArgumentException e) {
            return null;
        }
    }

    private void applyVolume() {
        SourceDataLine current;
        synchronized (lineLock) {
            current = line;
        }
        if (current != null) {
            applyVolume(current, volume);
        }
    }

    private static void applyVolume(SourceDataLine line, float volume) {
        if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
        float decibels = (float) (20.0 * Math.log10(Math.max(0.0001F, volume)));
        gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), decibels)));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 收尾而已，关不掉也没别的办法
        }
    }
}
