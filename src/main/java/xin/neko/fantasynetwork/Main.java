package xin.neko.fantasynetwork;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.neko.fantasynetwork.auth.AuthManager;
import xin.neko.fantasynetwork.command.NekoMusicCommand;
import xin.neko.fantasynetwork.ui.NekoMusicUi;
import xin.neko.fantasynetwork.ui.PlayerHud;
import xin.neko.fantasynetwork.ui.QrLoginNotifier;

public class Main implements ModInitializer {
    public static final String MOD_ID = "nekomusicforminecraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // 播放器界面、扫码登录、指令都是纯客户端的，专用服务器上不需要也不会加载这些类
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            PlayerHud.init();
            NekoMusicUi.register();
            QrLoginNotifier.register();
            NekoMusicCommand.register();

            AuthManager.getInstance().load();
            AuthManager.getInstance().refreshInBackground();
        }
    }
}
