package xin.neko.fantasynetwork.ui;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import xin.neko.fantasynetwork.Main;
import xin.neko.fantasynetwork.player.NekoPlayer;
import xin.neko.fantasynetwork.player.Track;

/**
 * 悬浮在游戏界面上的播放器面板，默认锚定在左下角。
 *
 * <p>所有坐标都取自 GUI 缩放后的坐标系（{@link net.minecraft.client.util.Window#getScaledWidth()}），
 * 因此窗口尺寸/分辨率变化时面板会跟着走：窗口很小时自动切换紧凑布局，再小就直接不画。
 */
public final class PlayerHud implements HudElement {

    private static final Identifier ELEMENT_ID = Identifier.of(Main.MOD_ID, "player");
    private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of(Main.MOD_ID, "main"));

    /** 距离屏幕左/下边缘的间距。 */
    private static final int MARGIN = 4;
    /** 面板理想宽度，窗口放不下时会收缩到这个值以内。 */
    private static final int PREFERRED_WIDTH = 176;
    private static final int COMPACT_WIDTH = 132;
    private static final int MIN_PANEL_WIDTH = 96;
    private static final int PADDING = 4;
    /** 左侧那条彩色竖条。 */
    private static final int ACCENT_WIDTH = 2;
    private static final int BAR_HEIGHT = 3;

    /** 视口小于这个尺寸就完全隐藏，避免在小窗口里糊成一团。 */
    private static final int MIN_SCREEN_WIDTH = 160;
    private static final int MIN_SCREEN_HEIGHT = 120;
    /** 视口小于这个尺寸就切到紧凑布局（省掉歌手名和时间）。 */
    private static final int COMPACT_SCREEN_WIDTH = 280;
    private static final int COMPACT_SCREEN_HEIGHT = 200;

    private static final int COLOR_BACKGROUND = 0xC0121216;
    private static final int COLOR_BORDER = 0x40FFFFFF;
    private static final int COLOR_ACCENT = 0xFF9B7BFF;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_SUBTITLE = 0xFFB4B4C0;
    private static final int COLOR_BAR_TRACK = 0x40FFFFFF;

    private static KeyBinding toggleKey;
    private static KeyBinding playPauseKey;
    private static boolean visible = true;

    private PlayerHud() {
    }

    public static void init() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key." + Main.MOD_ID + ".toggle_hud",
                InputUtil.Type.KEYSYM,
                InputUtil.GLFW_KEY_N,
                CATEGORY));
        playPauseKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key." + Main.MOD_ID + ".play_pause",
                InputUtil.Type.KEYSYM,
                InputUtil.GLFW_KEY_M,
                CATEGORY));

        HudElementRegistry.addLast(ELEMENT_ID, new PlayerHud());
        ClientTickEvents.END_CLIENT_TICK.register(PlayerHud::onEndClientTick);
    }

    private static void onEndClientTick(MinecraftClient client) {
        while (toggleKey.wasPressed()) {
            visible = !visible;
        }
        while (playPauseKey.wasPressed()) {
            NekoPlayer player = NekoPlayer.getInstance();
            if (player.hasTrack()) {
                player.toggle();
            } else {
                // 真实音源接入前，先播一首占位曲目方便验证界面
                player.play(Track.DEMO);
            }
        }
        NekoPlayer.getInstance().tick(50L);
    }

    @Override
    public void render(DrawContext context, RenderTickCounter tickCounter) {
        if (!visible) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) {
            return;
        }

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        if (screenWidth < MIN_SCREEN_WIDTH || screenHeight < MIN_SCREEN_HEIGHT) {
            return;
        }

        boolean compact = screenWidth < COMPACT_SCREEN_WIDTH || screenHeight < COMPACT_SCREEN_HEIGHT;
        int panelWidth = Math.min(compact ? COMPACT_WIDTH : PREFERRED_WIDTH, screenWidth - MARGIN * 2);
        if (panelWidth < MIN_PANEL_WIDTH) {
            return;
        }

        TextRenderer font = client.textRenderer;
        NekoPlayer player = NekoPlayer.getInstance();
        Track track = player.getTrack();

        boolean showArtist = !compact && track != null && track.hasArtist();
        boolean showTimes = !compact;

        int textWidth = panelWidth - PADDING * 2 - ACCENT_WIDTH;
        int rowHeight = font.fontHeight + 1;
        int panelHeight = PADDING * 2 + rowHeight
                + (showArtist ? rowHeight : 0)
                + BAR_HEIGHT
                + (showTimes ? rowHeight : 0);

        int x = MARGIN;
        int y = screenHeight - MARGIN - panelHeight;

        drawPanel(context, x, y, panelWidth, panelHeight);

        int contentX = x + PADDING + ACCENT_WIDTH;
        int cursorY = y + PADDING;

        String title = track != null
                ? track.title()
                : Text.translatable("hud." + Main.MOD_ID + ".idle").getString();
        context.drawText(font, font.trimToWidth(title, textWidth), contentX, cursorY, COLOR_TITLE, true);
        cursorY += rowHeight;

        if (showArtist) {
            context.drawText(font, font.trimToWidth(track.artist(), textWidth), contentX, cursorY, COLOR_SUBTITLE, true);
            cursorY += rowHeight;
        }

        context.fill(contentX, cursorY, contentX + textWidth, cursorY + BAR_HEIGHT, COLOR_BAR_TRACK);
        int filled = Math.round(textWidth * Math.max(0.0F, Math.min(1.0F, player.getProgress())));
        if (filled > 0) {
            context.fill(contentX, cursorY, contentX + filled, cursorY + BAR_HEIGHT, COLOR_ACCENT);
        }
        cursorY += BAR_HEIGHT;

        if (showTimes) {
            String elapsed = formatTime(track == null ? 0L : player.getPositionMillis());
            String total = formatTime(track == null ? 0L : track.durationMillis());
            context.drawText(font, elapsed, contentX, cursorY, COLOR_SUBTITLE, true);
            context.drawText(font, total, contentX + textWidth - font.getWidth(total), cursorY, COLOR_SUBTITLE, true);
        }
    }

    private static void drawPanel(DrawContext context, int x, int y, int width, int height) {
        context.fill(x, y, x + width, y + height, COLOR_BACKGROUND);
        context.fill(x, y, x + width, y + 1, COLOR_BORDER);
        context.fill(x, y + height - 1, x + width, y + height, COLOR_BORDER);
        context.fill(x + width - 1, y, x + width, y + height, COLOR_BORDER);
        context.fill(x, y, x + ACCENT_WIDTH, y + height, COLOR_ACCENT);
    }

    private static String formatTime(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        return String.format("%02d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }
}
