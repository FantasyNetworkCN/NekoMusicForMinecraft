package xin.neko.fantasynetwork.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import xin.neko.fantasynetwork.Main;
import xin.neko.fantasynetwork.api.MusicSearch;
import xin.neko.fantasynetwork.api.NekoApi;
import xin.neko.fantasynetwork.auth.AuthManager;
import xin.neko.fantasynetwork.ui.NekoMusicScreen;

/**
 * 客户端指令 {@code /nekomusic}，所有子指令都在本地执行（不需要服务器装这个 mod）。
 *
 * <p>面板本身按 K 就能开、鼠标就能用，这里的指令只是备选入口：习惯敲指令的玩家可以少点几下鼠标。
 * 网络请求一律异步：先回一句「正在处理」，拿到结果再回到主线程补一条消息。
 */
public final class NekoMusicCommand {

    private static final String PREFIX = "command." + Main.MOD_ID + ".";
    /** 搜索指令在聊天栏最多列几条结果。 */
    private static final int SEARCH_LIMIT = 5;

    /** 请求打开面板：聊天界面关闭自己之前不能直接切界面，所以只置个标记，下一 tick 再开。 */
    private static boolean openRequested;

    private NekoMusicCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("nekomusic")
                        .executes(context -> {
                            sendHelp(context.getSource());
                            return 1;
                        })
                        .then(ClientCommandManager.literal("login").executes(context -> {
                            openPanel(context.getSource());
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("logout").executes(context -> {
                            logout(context.getSource());
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("status").executes(context -> {
                            showStatus(context.getSource());
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("search")
                                .then(ClientCommandManager.argument("keyword", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            search(context.getSource(), StringArgumentType.getString(context, "keyword"));
                                            return 1;
                                        })))));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (openRequested) {
                openRequested = false;
                client.setScreen(new NekoMusicScreen());
            }
        });
    }

    private static void sendHelp(FabricClientCommandSource source) {
        source.sendFeedback(Text.translatable(PREFIX + "help.header"));
        source.sendFeedback(Text.translatable(PREFIX + "help.login"));
        source.sendFeedback(Text.translatable(PREFIX + "help.logout"));
        source.sendFeedback(Text.translatable(PREFIX + "help.status"));
        source.sendFeedback(Text.translatable(PREFIX + "help.search"));
    }

    private static void openPanel(FabricClientCommandSource source) {
        AuthManager auth = AuthManager.getInstance();
        if (auth.isLoggedIn()) {
            source.sendFeedback(Text.translatable(PREFIX + "login.already", auth.displayName()));
        }
        // ChatScreen 执行完提交的命令后还会把界面置空（setScreen(null)），
        // 这里立刻切界面会被它覆盖掉，所以交给下一 tick 的钩子来开。
        openRequested = true;
        source.sendFeedback(Text.translatable(PREFIX + "login.opening"));
    }

    private static void logout(FabricClientCommandSource source) {
        AuthManager auth = AuthManager.getInstance();
        if (!auth.isLoggedIn()) {
            source.sendFeedback(Text.translatable(PREFIX + "logout.not_logged_in"));
            return;
        }
        auth.logout();
        source.sendFeedback(Text.translatable(PREFIX + "logout.done"));
    }

    private static void showStatus(FabricClientCommandSource source) {
        AuthManager auth = AuthManager.getInstance();
        if (!auth.isLoggedIn()) {
            source.sendFeedback(Text.translatable(PREFIX + "status.not_logged_in"));
            return;
        }
        source.sendFeedback(Text.translatable(PREFIX + "status.loading"));

        MinecraftClient client = source.getClient();
        auth.fetchUserInfo().whenComplete((user, error) -> client.execute(() -> {
            if (error != null) {
                source.sendError(Text.translatable(PREFIX + "status.failed", NekoApi.errorMessage(error)));
                return;
            }
            source.sendFeedback(Text.translatable(PREFIX + "status.logged_in",
                    user.displayName(),
                    user.id(),
                    Text.translatable(PREFIX + (user.vip() ? "status.vip" : "status.normal"))));
        }));
    }

    private static void search(FabricClientCommandSource source, String keyword) {
        String query = keyword == null ? "" : keyword.trim();
        if (query.isEmpty()) {
            source.sendError(Text.translatable(PREFIX + "search.blank"));
            return;
        }

        source.sendFeedback(Text.translatable(PREFIX + "search.searching", query));

        MinecraftClient client = source.getClient();
        MusicSearch.search(query, client::execute,
                result -> {
                    if (result.tracks().isEmpty()) {
                        String message = result.message();
                        source.sendFeedback(message.isBlank()
                                ? Text.translatable(PREFIX + "search.empty", query)
                                : Text.translatable(PREFIX + "search.empty_with_message", query, message));
                        return;
                    }

                    source.sendFeedback(Text.translatable(PREFIX + "search.header", query).formatted(Formatting.AQUA));
                    for (MusicSearch.Track track : result.tracks().stream().limit(SEARCH_LIMIT).toList()) {
                        MutableText line = Text.literal("#" + track.id() + " ").formatted(Formatting.DARK_GRAY)
                                .append(Text.literal(track.title()).formatted(Formatting.WHITE))
                                .append(Text.literal(" - " + track.artist()).formatted(Formatting.GRAY))
                                .append(Text.literal(" " + track.durationLabel()).formatted(Formatting.DARK_GRAY));
                        source.sendFeedback(line);
                    }
                },
                message -> source.sendError(Text.translatable(PREFIX + "search.failed", message)));
    }
}
