package xin.neko.fantasynetwork.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import xin.neko.fantasynetwork.auth.AuthManager;
import xin.neko.fantasynetwork.auth.QrLoginService;
import xin.neko.fantasynetwork.util.QrMatrix;

/**
 * 扫码登录界面：把二维码按点阵直接画在白色面板上（不需要贴图资源），
 * 同时展示会话状态与剩余有效期。
 *
 * <p>界面只是 {@link QrLoginService} 的一个观察者：关闭界面不会中断会话，手机端确认后
 * 依然会完成登录（{@code QrLoginNotifier} 会在聊天栏提示结果）；重新打开界面会接着显示同一个会话。
 */
public class QrLoginScreen extends Screen {

    /** 二维码四周留白的模块数（静默区），4 个模块是扫码器普遍要求的最小值。 */
    private static final int QUIET_ZONE = 4;
    private static final int MIN_SCALE = 2;
    private static final int MAX_SCALE = 12;

    private static final int COLOR_PANEL = 0xFFFFFFFF;
    private static final int COLOR_QR_DARK = 0xFF101018;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_HINT = 0xFFB4B4C0;
    private static final int COLOR_SUCCESS = 0xFF8BFFB0;
    private static final int COLOR_ERROR = 0xFFFF8080;
    private static final int COLOR_SUCCESS_OVERLAY = 0x6600D060;

    private static final long CONFIRMED_CLOSE_DELAY_MS = 1200L;
    private static final long AUTO_REFRESH_DELAY_MS = 1800L;

    private final QrLoginService service = QrLoginService.getInstance();

    /** 下面两个计时器只由渲染线程（{@link #tick()}）读写。 */
    private long confirmedAtMillis = -1L;
    private long refreshAtMillis = -1L;

    public QrLoginScreen() {
        super(Text.translatable("screen.nekomusicforminecraft.qr_login.title"));
    }

    @Override
    protected void init() {
        QrLoginService.State state = service.session().state();
        if (state == QrLoginService.State.IDLE || service.session().terminal()) {
            service.start();
        }

        int buttonWidth = Math.min(180, Math.max(100, width - 24));
        int buttonY = height - 28;
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.nekomusicforminecraft.qr_login.refresh"),
                        button -> service.start())
                .dimensions(width / 2 - buttonWidth / 2, buttonY, buttonWidth, 20)
                .build());
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void tick() {
        QrLoginService.Session session = service.session();
        long now = System.currentTimeMillis();

        switch (session.state()) {
            case CONFIRMED -> {
                if (confirmedAtMillis < 0L) {
                    confirmedAtMillis = now;
                } else if (now - confirmedAtMillis >= CONFIRMED_CLOSE_DELAY_MS && client != null) {
                    close();
                }
            }
            case CANCELED, EXPIRED -> {
                if (refreshAtMillis < 0L) {
                    refreshAtMillis = now;
                } else if (now - refreshAtMillis >= AUTO_REFRESH_DELAY_MS) {
                    service.start();
                }
            }
            case PENDING, SCANNED -> {
                // 服务端没推 expired 时（例如断线后又恢复），本地兜底刷新
                if (session.remainingSeconds() <= 0L) {
                    service.start();
                }
            }
            default -> {
                // IDLE / CREATING / ERROR 由按钮或命令重新发起
            }
        }
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        switch (input.key()) {
            case GLFW.GLFW_KEY_R -> {
                service.start();
                return true;
            }
            default -> {
                // 交给父类处理 ESC 等按键
            }
        }
        return super.keyPressed(input);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        QrLoginService.Session session = service.session();
        QrMatrix matrix = session.matrix();

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, COLOR_TITLE);

        int rowHeight = textRenderer.fontHeight + 2;
        int reserved = 12 + rowHeight + 12 + 10 + rowHeight * 2 + 34;
        int maxQrPixels = Math.max(40, Math.min(width - 40, height - reserved));
        int modules = matrix == null ? 0 : matrix.size() + QUIET_ZONE * 2;
        int scale = modules == 0 ? MIN_SCALE
                : Math.max(MIN_SCALE, Math.min(MAX_SCALE, maxQrPixels / modules));
        int qrPixels = modules * scale;
        int panelSize = qrPixels + 8;
        int panelX = (width - panelSize) / 2;
        int panelY = 12 + rowHeight + 10;

        context.fill(panelX, panelY, panelX + panelSize, panelY + panelSize, COLOR_PANEL);
        if (matrix != null) {
            drawMatrix(context, matrix, panelX + 4, panelY + 4, scale);
            if (session.state() == QrLoginService.State.CONFIRMED) {
                context.fill(panelX, panelY, panelX + panelSize, panelY + panelSize, COLOR_SUCCESS_OVERLAY);
            }
        }

        int statusY = panelY + panelSize + 10;
        renderStatus(context, session, statusY);

        String keys = Text.translatable("screen.nekomusicforminecraft.qr_login.keys").getString();
        context.drawCenteredTextWithShadow(textRenderer, keys, width / 2, height - 44, COLOR_HINT);
    }

    private void renderStatus(DrawContext context, QrLoginService.Session session, int y) {
        Text text;
        int color = COLOR_HINT;

        switch (session.state()) {
            case IDLE -> text = Text.translatable("screen.nekomusicforminecraft.qr_login.status.idle");
            case CREATING -> text = Text.translatable("screen.nekomusicforminecraft.qr_login.status.creating");
            case PENDING -> text = Text.translatable("screen.nekomusicforminecraft.qr_login.status.pending",
                    session.remainingSeconds());
            case SCANNED -> text = Text.translatable("screen.nekomusicforminecraft.qr_login.status.scanned");
            case CONFIRMED -> {
                text = Text.translatable("screen.nekomusicforminecraft.qr_login.status.confirmed",
                        AuthManager.getInstance().displayName());
                color = COLOR_SUCCESS;
            }
            case CANCELED -> text = Text.translatable("screen.nekomusicforminecraft.qr_login.status.canceled");
            case EXPIRED -> text = Text.translatable("screen.nekomusicforminecraft.qr_login.status.expired");
            default -> {
                text = Text.translatable("screen.nekomusicforminecraft.qr_login.status.error", session.errorMessage());
                color = COLOR_ERROR;
            }
        }

        context.drawCenteredTextWithShadow(textRenderer, text, width / 2, y, color);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("screen.nekomusicforminecraft.qr_login.hint"),
                width / 2, y + textRenderer.fontHeight + 4, COLOR_HINT);
    }

    private void drawMatrix(DrawContext context, QrMatrix matrix, int x, int y, int scale) {
        for (int row = 0; row < matrix.size(); row++) {
            int column = 0;
            while (column < matrix.size()) {
                if (!matrix.isDark(column, row)) {
                    column++;
                    continue;
                }
                int start = column;
                while (column < matrix.size() && matrix.isDark(column, row)) {
                    column++;
                }
                // 同一行相邻的深色模块合并成一次填充，减少绘制调用
                int left = x + (start + QUIET_ZONE) * scale;
                int top = y + (row + QUIET_ZONE) * scale;
                context.fill(left, top, left + (column - start) * scale, top + scale, COLOR_QR_DARK);
            }
        }
    }

}
