package xin.neko.fantasynetwork.ui;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import xin.neko.fantasynetwork.Main;

/**
 * 打开音乐面板的快捷键（默认 K）。
 *
 * <p>面板本身用鼠标操作，指令只是备选入口——正常玩的时候按 K 就够了。
 */
public final class NekoMusicUi {

    private static KeyBinding openKey;

    private NekoMusicUi() {
    }

    public static void register() {
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key." + Main.MOD_ID + ".open_ui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                PlayerHud.CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(NekoMusicUi::onEndClientTick);
    }

    /** 打开面板；已经有别的界面开着（比如聊天栏）就不抢，免得把玩家正在做的事顶掉。 */
    public static void open() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen == null) {
            client.setScreen(new NekoMusicScreen());
        }
    }

    private static void onEndClientTick(MinecraftClient client) {
        while (openKey.wasPressed()) {
            open();
        }
    }
}
