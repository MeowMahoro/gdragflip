package net.gdragflip.leveling;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 升级助手的采样调度：每 5 tick 读一次 tab 宠物 Widget，并顺带读取 Exp Sharing 菜单。
 *
 * <p>地牢内 Hypixel 不显示 Pet Widget，此时由 {@link SkillXpWatcher} 从 action bar 估算。</p>
 */
public final class LevelingAssistant {
    private static final int SAMPLE_INTERVAL_TICKS = 5;
    private static final int SAVE_INTERVAL_TICKS = 1200;

    private static volatile PetWidgetReader.Snapshot snapshot = PetWidgetReader.Snapshot.MISSING;
    private static volatile Screen pendingScreen;
    private static int ticks;
    private static long lastTickAt = System.currentTimeMillis();

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
            client.gui.setScreen(screen);
        }

        if (client.player == null) {
            LevelingSession.onWorldLeave();
            snapshot = PetWidgetReader.Snapshot.MISSING;
            return;
        }

        long now = System.currentTimeMillis();
        long delta = Math.max(0L, now - lastTickAt);
        lastTickAt = now;

        ExpShareTracker.tick(client);
        PetMenuReader.tick(client);

        // 地牢计时：没有 Pet Widget、但仍在持续获得经验时才算「在打」。
        if (!snapshot.found() && LevelingSession.estimatedWithin(120_000L)) {
            LevelingSession.addDungeonMillis(delta);
        }

        ticks++;
        // 每分钟落盘一次，避免异常退出丢数据（累计值本身不会自动清零）。
        if (ticks % SAVE_INTERVAL_TICKS == 0) {
            LevelingSession.saveIfDirty();
        }
        if (ticks % SAMPLE_INTERVAL_TICKS != 0) {
            return;
        }
        PetWidgetReader.Snapshot read = PetWidgetReader.read(client);
        snapshot = read;
        LevelingSession.sample(read);
    }

    public static PetWidgetReader.Snapshot snapshot() {
        return snapshot;
    }
}
