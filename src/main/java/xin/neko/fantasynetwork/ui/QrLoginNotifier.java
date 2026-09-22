package xin.neko.fantasynetwork.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import xin.neko.fantasynetwork.auth.AuthManager;
import xin.neko.fantasynetwork.auth.QrLoginService;

/**
 * 把扫码登录的终态结果提示到聊天栏。
 *
 * <p>单独做成监听器是为了「关掉二维码界面后手机才确认」这种情形也能看到结果——
 * 登录本身由 {@link QrLoginService} 完成，与界面是否打开无关。
 */
public final class QrLoginNotifier implements QrLoginService.Listener {

    private QrLoginNotifier() {
    }

    public static void register() {
        QrLoginService.getInstance().addListener(new QrLoginNotifier());
    }

    @Override
    public void onSessionChanged(QrLoginService.Session session) {
        Text message = switch (session.state()) {
            case CONFIRMED -> Text.translatable("chat.nekomusicforminecraft.login.confirmed",
                    AuthManager.getInstance().displayName());
            case CANCELED -> Text.translatable("chat.nekomusicforminecraft.login.canceled");
            case ERROR -> Text.translatable("chat.nekomusicforminecraft.login.error", session.errorMessage());
            default -> null;
        };
        if (message == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(message, false);
            }
        });
    }
}
