package net.gdragflip.leveling;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 升级助手调度：地牢判定、地牢计时、菜单读取、出本提示。
 *
 * <p>数据来源只有两类：</p>
 * <ol>
 *   <li><b>估算</b>：地牢内由 {@link SkillXpWatcher} 解析 action bar 技能经验；</li>
 *   <li><b>真实值</b>：{@link PetMenuReader}（/pets）与 {@link ExpShareTracker}（Exp Sharing）。</li>
 * </ol>
 *
 * <p>不再读取 Tab 的 Pet Widget：它在副本里不显示，且与 /pets 的真实值重复。</p>
 */
public final class LevelingAssistant {
    private static final int SAVE_INTERVAL_TICKS = 1200;

    private static volatile Screen pendingScreen;
    private static int ticks;
    private static long lastTickAt = System.currentTimeMillis();
    private static boolean wasInDungeon;
    private static long lastCalibrationPromptAt;

    private LevelingAssistant() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(LevelingAssistant::tick);
        ClientReceiveMessageEvents.GAME.register(SkillXpWatcher::onGameMessage);
    }

    /**
     * 延迟到下一 tick 再打开界面：直接在命令执行时 setScreen 会被关闭聊天界面的流程顶掉
     * （表现就是界面闪一下就回到游戏）。
     */
    public static void openNextTick(Screen screen) {
        pendingScreen = screen;
    }

    private static void tick(Minecraft client) {
        Screen screen = pendingScreen;
        if (screen != null) {
            pendingScreen = null;
            client.setScreenAndShow(screen);
        }

        if (client.gui.screen() instanceof net.gdragflip.ui.HudEditorScreen editor) {
            editor.poll(client);
        }

        DungeonDetector.tick(client);

        if (client.player == null) {
            LevelingSession.onWorldLeave();
            return;
        }

        long now = System.currentTimeMillis();
        long delta = Math.max(0L, now - lastTickAt);
        lastTickAt = now;

        ExpShareTracker.tick(client);
        PetMenuReader.tick(client);

        // 地牢计时：在本内、宠物匹配、且仍在持续获得经验时才算「在打」。
        if (LevelingSession.inDungeon() && !LevelingSession.mismatch()
                && LevelingSession.estimatedWithin(120_000L)) {
            LevelingSession.addDungeonMillis(delta);
        }

        ticks++;
        // 每分钟落盘一次，避免异常退出丢数据（累计值本身不会自动清零）。
        if (ticks % SAVE_INTERVAL_TICKS == 0) {
            LevelingSession.saveIfDirty();
        }

        // 出地牢后提示去 /pets 校准一次。
        boolean inDungeon = LevelingSession.inDungeon();
        if (wasInDungeon && !inDungeon && LevelingSession.bound()) {
            if (now - lastCalibrationPromptAt > 60_000L) {
                lastCalibrationPromptAt = now;
                client.player.sendSystemMessage(net.gdragflip.chat.ChatReport.info(
                        "已出地牢 · 打开 /pets 校准", net.minecraft.ChatFormatting.YELLOW));
            }
        }
        wasInDungeon = inDungeon;
    }
}
