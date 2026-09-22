package xin.neko.fantasynetwork.ui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import xin.neko.fantasynetwork.Main;
import xin.neko.fantasynetwork.api.MusicSearch;
import xin.neko.fantasynetwork.auth.AuthManager;
import xin.neko.fantasynetwork.auth.NekoUser;
import xin.neko.fantasynetwork.auth.QrLoginService;
import xin.neko.fantasynetwork.util.QrMatrix;

import java.util.List;

/**
 * Neko云音乐主面板：半透明悬浮在游戏画面上，游戏继续跑（{@link #shouldPause()} 返回 false），
 * 除搜索框外没有原版控件，观感上更接近 HUD 而不是「另一个界面」。
 *
 * <p>左列是账号区：未登录直接把二维码画在白卡片上，扫完自动换成账号信息，全程不用敲指令；
 * 右列是搜索框 + 结果列表（回车或点「搜索」都行）。鼠标是主要操作方式，键盘只剩 ESC 关闭。
 */
public class NekoMusicScreen extends Screen {

    private static final String LANG = "screen." + Main.MOD_ID + ".panel.";
    private static final String QR_LANG = "screen." + Main.MOD_ID + ".qr_login.";

    private static final int PADDING = 10;
    private static final int HEADER_HEIGHT = 20;
    private static final int GAP = 10;
    private static final int BUTTON_HEIGHT = 18;
    private static final int ROW_HEIGHT = 20;
    private static final int MAX_ROWS = 6;
    private static final int MIN_QR_SIZE = 52;
    private static final int MAX_QR_SIZE = 96;
    /** 二维码下面预留给状态文案的两行高度。 */
    private static final int STATUS_HEIGHT = 22;
    /** 右列再窄就别显示了，不然搜索框会缩成一个点。 */
    private static final int SEARCH_MIN_WIDTH = 150;
    private static final int SEARCH_BUTTON_WIDTH = 46;
    private static final int MAX_PANEL_WIDTH = 330;
    private static final int MAX_PANEL_HEIGHT = 200;
    private static final int MIN_PANEL_WIDTH = 150;
    private static final int MIN_PANEL_HEIGHT = 130;
    /** 二维码四周留白的模块数（静默区），扫码器普遍要求至少 4 个。 */
    private static final int QUIET_ZONE = 4;
    private static final long AUTO_REFRESH_DELAY_MS = 1800L;

    private static final int COLOR_DIM = 0x66000000;
    private static final int COLOR_PANEL_TOP = 0xE81A1A24;
    private static final int COLOR_PANEL_BOTTOM = 0xE80B0B10;
    private static final int COLOR_BORDER = 0x33FFFFFF;
    private static final int COLOR_BORDER_HOVER = 0x66FFFFFF;
    private static final int COLOR_ACCENT = 0xFF9B7BFF;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFFE8E8F0;
    private static final int COLOR_SUBTITLE = 0xFF9A9AA8;
    private static final int COLOR_SEPARATOR = 0x22FFFFFF;
    private static final int COLOR_ROW_ALT = 0x0CFFFFFF;
    private static final int COLOR_ROW_HOVER = 0x2E9B7BFF;
    private static final int COLOR_SHADOW = 0x4C000000;
    private static final int COLOR_SHADOW_SOFT = 0x24000000;
    private static final int COLOR_FIELD = 0x59000000;
    private static final int COLOR_CARD = 0x26FFFFFF;
    private static final int COLOR_BUTTON = 0x2EFFFFFF;
    private static final int COLOR_BUTTON_HOVER = 0x4AFFFFFF;
    private static final int COLOR_BUTTON_PRIMARY = 0x409B7BFF;
    private static final int COLOR_BUTTON_PRIMARY_HOVER = 0x709B7BFF;
    private static final int COLOR_QR_BG = 0xFFFFFFFF;
    private static final int COLOR_QR_DARK = 0xFF101018;
    private static final int COLOR_SUCCESS = 0xFF8BFFB0;
    private static final int COLOR_SUCCESS_OVERLAY = 0x6600D060;
    private static final int COLOR_ERROR = 0xFFFF8080;

    private final AuthManager auth = AuthManager.getInstance();
    private final QrLoginService login = QrLoginService.getInstance();

    private TextFieldWidget searchField;
    private List<MusicSearch.Track> results = List.of();
    private String searchHint = "";
    private int searchHintColor = COLOR_SUBTITLE;
    private boolean searching;

    /** 面板打开时是不是已登录：变了就要重排一次（按钮、内容都不一样）。 */
    private boolean layoutLoggedIn;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentBottom;
    private int qrX;
    private int qrY;
    private int qrSize;
    private int statusY;
    private HitBox accountBox;
    private boolean showSearch;
    private int searchX;
    private int searchY;
    private int searchFieldWidth;
    private int searchButtonX;
    private int resultsX;
    private int resultsY;
    private int resultsWidth;
    private int resultRows;
    private long refreshAtMillis = -1L;

    public NekoMusicScreen() {
        super(Text.translatable(LANG + "title"));
    }

    @Override
    protected void init() {
        layoutLoggedIn = auth.isLoggedIn();
        layout();

        searchHint = Text.translatable(LANG + "search.hint").getString();
        searchHintColor = COLOR_SUBTITLE;

        if (showSearch) {
            searchField = new TextFieldWidget(textRenderer, searchX, searchY, searchFieldWidth, BUTTON_HEIGHT,
                    Text.translatable(LANG + "search.placeholder"));
            searchField.setPlaceholder(Text.translatable(LANG + "search.placeholder"));
            searchField.setMaxLength(64);
            addDrawableChild(searchField);
            setInitialFocus(searchField);
        } else {
            searchField = null;
        }

        // 未登录就把二维码准备好；已经登录 / 正在等扫码的不重开，避免把有效二维码顶掉
        QrLoginService.State state = login.session().state();
        if (!layoutLoggedIn && (state == QrLoginService.State.IDLE || login.session().terminal())) {
            login.start();
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void tick() {
        if (layoutLoggedIn != auth.isLoggedIn()) {
            // 扫码完成 / 退出登录都会走到这里，重排一次让面板换成另一套内容
            clearAndInit();
            return;
        }

        QrLoginService.Session session = login.session();
        long now = System.currentTimeMillis();
        switch (session.state()) {
            case CANCELED, EXPIRED -> {
                // 停一下再刷新，别把失败状态一闪而过
                if (refreshAtMillis < 0L) {
                    refreshAtMillis = now;
                } else if (now - refreshAtMillis >= AUTO_REFRESH_DELAY_MS) {
                    refreshAtMillis = -1L;
                    login.start();
                }
            }
            case PENDING, SCANNED -> {
                refreshAtMillis = -1L;
                // 服务端没推 expired 时（比如断线后重连）本地兜底刷新
                if (session.remainingSeconds() <= 0L) {
                    login.start();
                }
            }
            default -> refreshAtMillis = -1L;
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        // 不铺原版背景：游戏画面透出来，只压暗一点保证面板上的文字看得清
        context.fill(0, 0, width, height, COLOR_DIM);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        drawPanel(context);
        drawHeader(context);
        if (layoutLoggedIn) {
            drawAccountCard(context);
        } else {
            drawQrCard(context);
        }
        drawAccountButton(context, mouseX, mouseY);

        if (showSearch) {
            // 输入框垫一层半透明黑，原版控件自带的底色在深色面板上不够看
            context.fill(searchX - 3, searchY - 3, searchX + searchFieldWidth + 3, searchY + BUTTON_HEIGHT + 3,
                    COLOR_FIELD);
            // 输入框是唯一的原版控件，交给父类画（面板背景在它下面）
            super.render(context, mouseX, mouseY, deltaTicks);
            drawFieldFrame(context);
            drawResults(context, mouseX, mouseY);
            drawButton(context, searchButtonX, searchY, SEARCH_BUTTON_WIDTH, BUTTON_HEIGHT,
                    Text.translatable(LANG + "search.button"), true, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) {
            return true;
        }

        double mouseX = click.x();
        double mouseY = click.y();
        if (accountBox != null && accountBox.contains(mouseX, mouseY)) {
            if (layoutLoggedIn) {
                auth.logout();
                clearAndInit();
            } else {
                login.start();
            }
            return true;
        }
        if (showSearch && within(mouseX, mouseY, searchButtonX, searchY, SEARCH_BUTTON_WIDTH, BUTTON_HEIGHT)) {
            search();
            return true;
        }
        // 点到面板外面就当是关掉，符合悬浮窗的习惯；二维码本身在后台照样有效，再按 K 就回来了
        if (!within(mouseX, mouseY, panelX, panelY, panelWidth, panelHeight)) {
            close();
            return true;
        }
        return false;
    }

    /** 搜索框的描边：聚焦时换成 Neko 紫，一眼能看出输入法打到哪儿了。 */
    private void drawFieldFrame(DrawContext context) {
        boolean focused = searchField != null && searchField.isFocused();
        int border = focused ? COLOR_ACCENT : COLOR_BORDER;
        context.fill(searchX - 1, searchY - 1, searchX + searchFieldWidth + 1, searchY, border);
        context.fill(searchX - 1, searchY + BUTTON_HEIGHT, searchX + searchFieldWidth + 1, searchY + BUTTON_HEIGHT + 1,
                border);
        context.fill(searchX - 1, searchY, searchX, searchY + BUTTON_HEIGHT, border);
        context.fill(searchX + searchFieldWidth, searchY, searchX + searchFieldWidth + 1, searchY + BUTTON_HEIGHT,
                border);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (searchField != null && searchField.isFocused()
                && (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            search();
            return true;
        }
        return super.keyPressed(input);
    }

    /** 按可用空间把面板与各块内容的位置算出来；{@link #init()} 与窗口缩放时都会调用。 */
    private void layout() {
        panelWidth = clamp(width - 12, MIN_PANEL_WIDTH, MAX_PANEL_WIDTH);
        panelHeight = clamp(height - 12, MIN_PANEL_HEIGHT, MAX_PANEL_HEIGHT);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;

        int contentTop = panelY + PADDING + HEADER_HEIGHT + 8;
        contentBottom = panelY + panelHeight - PADDING;
        int available = Math.max(40, contentBottom - contentTop);

        int qrMaxByWidth = panelWidth - PADDING * 2 - GAP - SEARCH_MIN_WIDTH;
        int qrMaxByHeight = available - STATUS_HEIGHT - GAP - BUTTON_HEIGHT - GAP;
        qrSize = clamp(Math.min(qrMaxByWidth, qrMaxByHeight), MIN_QR_SIZE, MAX_QR_SIZE);

        qrX = panelX + PADDING;
        qrY = contentTop;
        statusY = qrY + qrSize + 6;
        accountBox = new HitBox(qrX, contentBottom - BUTTON_HEIGHT, qrSize, BUTTON_HEIGHT);

        searchX = qrX + qrSize + GAP;
        searchY = contentTop;
        int rightWidth = panelX + panelWidth - PADDING - searchX;
        showSearch = rightWidth >= SEARCH_MIN_WIDTH;
        if (showSearch) {
            searchFieldWidth = rightWidth - SEARCH_BUTTON_WIDTH - 6;
            searchButtonX = searchX + rightWidth - SEARCH_BUTTON_WIDTH;
            resultsX = searchX;
            resultsY = searchY + BUTTON_HEIGHT + 8;
            resultsWidth = rightWidth;
            resultRows = Math.min(MAX_ROWS, Math.max(0, (contentBottom - resultsY) / ROW_HEIGHT));
        } else {
            searchFieldWidth = 0;
            searchButtonX = 0;
            resultsX = searchX;
            resultsY = contentTop;
            resultsWidth = 0;
            resultRows = 0;
        }
    }

    private void drawPanel(DrawContext context) {
        int left = panelX;
        int right = panelX + panelWidth;
        int top = panelY;
        int bottom = panelY + panelHeight;

        // 投影：向右下偏几像素，看起来是浮在游戏画面上的
        context.fill(left + 2, top + 3, right + 2, bottom + 3, COLOR_SHADOW);
        context.fill(left + 1, top + 2, right + 1, bottom + 2, COLOR_SHADOW_SOFT);

        // 圆角面板：主体内缩两像素，四角再补上小方块，凑出圆角的效果
        context.fillGradient(left + 2, top, right - 2, bottom, COLOR_PANEL_TOP, COLOR_PANEL_BOTTOM);
        context.fillGradient(left, top + 2, left + 2, bottom - 2, COLOR_PANEL_TOP, COLOR_PANEL_BOTTOM);
        context.fillGradient(right - 2, top + 2, right, bottom - 2, COLOR_PANEL_TOP, COLOR_PANEL_BOTTOM);
        context.fill(left + 1, top + 1, left + 2, top + 2, COLOR_PANEL_TOP);
        context.fill(right - 2, top + 1, right - 1, top + 2, COLOR_PANEL_TOP);
        context.fill(left + 1, bottom - 1, left + 2, bottom, COLOR_PANEL_BOTTOM);
        context.fill(right - 2, bottom - 1, right - 1, bottom, COLOR_PANEL_BOTTOM);

        context.fill(left + 2, top, right - 2, top + 1, COLOR_BORDER);
        context.fill(left + 2, bottom - 1, right - 2, bottom, COLOR_BORDER);
        context.fill(left, top + 2, left + 1, bottom - 2, COLOR_BORDER);
        context.fill(right - 1, top + 2, right, bottom - 2, COLOR_BORDER);
        context.fill(left + 1, top + 1, left + 2, top + 2, COLOR_BORDER);
        context.fill(right - 2, top + 1, right - 1, top + 2, COLOR_BORDER);
        context.fill(left + 1, bottom - 1, left + 2, bottom, COLOR_BORDER);
        context.fill(right - 2, bottom - 1, right - 1, bottom, COLOR_BORDER);

        // 左侧那条 Neko 紫竖条，和左下角播放器 HUD 用同一套视觉语言
        context.fill(left + 1, top + 3, left + 3, bottom - 3, COLOR_ACCENT);
    }

    private void drawHeader(DrawContext context) {
        int textY = panelY + PADDING + 4;
        context.drawText(textRenderer, Text.translatable(LANG + "title"), panelX + PADDING + 6, textY, COLOR_TITLE, true);

        String close = Text.translatable(LANG + "close").getString();
        context.drawText(textRenderer, close, panelX + panelWidth - PADDING - textRenderer.getWidth(close), textY,
                COLOR_SUBTITLE, true);

        int lineY = panelY + PADDING + HEADER_HEIGHT - 2;
        context.fill(panelX + PADDING, lineY, panelX + panelWidth - PADDING, lineY + 1, COLOR_SEPARATOR);
    }

    private void drawQrCard(DrawContext context) {
        QrLoginService.Session session = login.session();
        QrMatrix matrix = session.matrix();

        context.fill(qrX, qrY, qrX + qrSize, qrY + qrSize, COLOR_QR_BG);
        if (matrix != null) {
            drawMatrix(context, matrix);
            if (session.state() == QrLoginService.State.CONFIRMED) {
                context.fill(qrX, qrY, qrX + qrSize, qrY + qrSize, COLOR_SUCCESS_OVERLAY);
            }
        }

        List<OrderedText> lines = textRenderer.wrapLines(loginStatusText(session), qrSize);
        int y = statusY;
        int color = loginStatusColor(session);
        for (int i = 0; i < lines.size() && i < 2; i++) {
            context.drawCenteredTextWithShadow(textRenderer, lines.get(i), qrX + qrSize / 2, y, color);
            y += textRenderer.fontHeight + 1;
        }
    }

    private Text loginStatusText(QrLoginService.Session session) {
        return switch (session.state()) {
            case IDLE -> Text.translatable(QR_LANG + "status.idle");
            case CREATING -> Text.translatable(QR_LANG + "status.creating");
            case PENDING -> Text.translatable(QR_LANG + "status.pending", session.remainingSeconds());
            case SCANNED -> Text.translatable(QR_LANG + "status.scanned");
            case CONFIRMED -> Text.translatable(QR_LANG + "status.confirmed", auth.displayName());
            case CANCELED -> Text.translatable(QR_LANG + "status.canceled");
            case EXPIRED -> Text.translatable(QR_LANG + "status.expired");
            default -> Text.translatable(QR_LANG + "status.error", session.errorMessage());
        };
    }

    private int loginStatusColor(QrLoginService.Session session) {
        return switch (session.state()) {
            case CONFIRMED -> COLOR_SUCCESS;
            case ERROR -> COLOR_ERROR;
            default -> COLOR_SUBTITLE;
        };
    }

    private void drawAccountCard(DrawContext context) {
        context.fill(qrX, qrY, qrX + qrSize, qrY + qrSize, COLOR_CARD);
        context.fill(qrX, qrY, qrX + qrSize, qrY + 1, COLOR_BORDER);
        context.fill(qrX, qrY + qrSize - 1, qrX + qrSize, qrY + qrSize, COLOR_BORDER);
        context.fill(qrX, qrY, qrX + 1, qrY + qrSize, COLOR_BORDER);
        context.fill(qrX + qrSize - 1, qrY, qrX + qrSize, qrY + qrSize, COLOR_BORDER);

        NekoUser user = auth.user();
        int centerX = qrX + qrSize / 2;
        int y = qrY + Math.max(4, (qrSize - 44) / 2);

        context.drawCenteredTextWithShadow(textRenderer, Text.translatable(LANG + "account"), centerX, y, COLOR_ACCENT);
        y += textRenderer.fontHeight + 5;
        context.drawCenteredTextWithShadow(textRenderer,
                textRenderer.trimToWidth(auth.displayName(), qrSize - 8), centerX, y, COLOR_TITLE);
        y += textRenderer.fontHeight + 5;

        Text rank = Text.translatable("command." + Main.MOD_ID + "."
                + (user != null && user.vip() ? "status.vip" : "status.normal"));
        String idLine = Text.translatable(LANG + "account.id", user == null ? "-" : user.id(), rank.getString())
                .getString();
        context.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(idLine, qrSize - 8),
                centerX, y, COLOR_SUBTITLE);
    }

    private void drawAccountButton(DrawContext context, int mouseX, int mouseY) {
        if (accountBox == null) {
            return;
        }
        drawButton(context, accountBox.x(), accountBox.y(), accountBox.width(), accountBox.height(),
                Text.translatable(LANG + (layoutLoggedIn ? "logout" : "refresh")), false, mouseX, mouseY);
    }

    private void drawResults(DrawContext context, int mouseX, int mouseY) {
        if (results.isEmpty()) {
            context.drawText(textRenderer, textRenderer.trimToWidth(searchHint, resultsWidth),
                    resultsX, resultsY + 2, searchHintColor, false);
            return;
        }

        int rows = Math.min(resultRows, results.size());
        for (int index = 0; index < rows; index++) {
            MusicSearch.Track track = results.get(index);
            int rowY = resultsY + index * ROW_HEIGHT;
            if (index % 2 == 1) {
                context.fill(resultsX, rowY, resultsX + resultsWidth, rowY + ROW_HEIGHT, COLOR_ROW_ALT);
            }
            if (within(mouseX, mouseY, resultsX, rowY, resultsWidth, ROW_HEIGHT)) {
                context.fill(resultsX, rowY, resultsX + resultsWidth, rowY + ROW_HEIGHT, COLOR_ROW_HOVER);
                // 悬停行左侧再点一条紫条，和面板的视觉语言一致
                context.fill(resultsX, rowY, resultsX + 2, rowY + ROW_HEIGHT, COLOR_ACCENT);
            }

            String duration = track.durationLabel();
            int durationWidth = textRenderer.getWidth(duration);
            String title = "#" + track.id() + "  " + track.title();
            context.drawText(textRenderer, textRenderer.trimToWidth(title, resultsWidth - durationWidth - 10),
                    resultsX + 2, rowY + 2, COLOR_TEXT, false);
            context.drawText(textRenderer, duration, resultsX + resultsWidth - durationWidth - 2, rowY + 2,
                    COLOR_SUBTITLE, false);
            if (!track.artist().isBlank()) {
                context.drawText(textRenderer, textRenderer.trimToWidth(track.artist(), resultsWidth - 4),
                        resultsX + 2, rowY + 11, COLOR_SUBTITLE, false);
            }
        }
    }

    private void drawButton(DrawContext context, int x, int y, int buttonWidth, int buttonHeight,
                            Text label, boolean primary, int mouseX, int mouseY) {
        boolean hovered = within(mouseX, mouseY, x, y, buttonWidth, buttonHeight);
        int background = primary
                ? (hovered ? COLOR_BUTTON_PRIMARY_HOVER : COLOR_BUTTON_PRIMARY)
                : (hovered ? COLOR_BUTTON_HOVER : COLOR_BUTTON);
        context.fill(x, y, x + buttonWidth, y + buttonHeight, background);

        int border = hovered ? COLOR_BORDER_HOVER : COLOR_BORDER;
        context.fill(x, y, x + buttonWidth, y + 1, border);
        context.fill(x, y + buttonHeight - 1, x + buttonWidth, y + buttonHeight, border);
        context.fill(x, y, x + 1, y + buttonHeight, border);
        context.fill(x + buttonWidth - 1, y, x + buttonWidth, y + buttonHeight, border);

        context.drawCenteredTextWithShadow(textRenderer, label, x + buttonWidth / 2,
                y + (buttonHeight - textRenderer.fontHeight) / 2, primary ? COLOR_TITLE : COLOR_TEXT);
    }

    private void drawMatrix(DrawContext context, QrMatrix matrix) {
        int modules = matrix.size() + QUIET_ZONE * 2;
        int inner = Math.max(1, qrSize - 8);
        int scale = Math.max(1, inner / modules);
        int drawn = scale * modules;
        int originX = qrX + (qrSize - drawn) / 2;
        int originY = qrY + (qrSize - drawn) / 2;

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
                int left = originX + (start + QUIET_ZONE) * scale;
                int top = originY + (row + QUIET_ZONE) * scale;
                context.fill(left, top, left + (column - start) * scale, top + scale, COLOR_QR_DARK);
            }
        }
    }

    private void search() {
        if (searchField == null) {
            return;
        }
        String query = searchField.getText().trim();
        if (query.isEmpty()) {
            searchHint = Text.translatable(LANG + "search.blank").getString();
            searchHintColor = COLOR_ERROR;
            return;
        }

        searching = true;
        searchHint = Text.translatable(LANG + "search.loading").getString();
        searchHintColor = COLOR_SUBTITLE;
        results = List.of();

        MusicSearch.search(query, client::execute,
                result -> {
                    searching = false;
                    results = result.tracks();
                    if (results.isEmpty()) {
                        searchHint = result.message().isBlank()
                                ? Text.translatable(LANG + "search.empty", query).getString()
                                : Text.translatable(LANG + "search.empty_with_message", query, result.message())
                                        .getString();
                        searchHintColor = COLOR_SUBTITLE;
                    }
                },
                message -> {
                    searching = false;
                    results = List.of();
                    searchHint = Text.translatable(LANG + "search.failed", message).getString();
                    searchHintColor = COLOR_ERROR;
                });
    }

    private static boolean within(double mouseX, double mouseY, int x, int y, int boxWidth, int boxHeight) {
        return mouseX >= x && mouseX < x + boxWidth && mouseY >= y && mouseY < y + boxHeight;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 面板里一个小按钮的命中区域。 */
    private record HitBox(int x, int y, int width, int height) {

        boolean contains(double mouseX, double mouseY) {
            return within(mouseX, mouseY, x, y, width, height);
        }
    }
}
