package net.gdragflip.leveling;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * 升级助手的采样调度：每 5 tick 读一次 tab 宠物 Widget，并顺带读取 Exp Sharing 菜单。
 */
public final class LevelingAssistant {
    private static final int SAMPLE_INTERVAL_TICKS = 5;
    private static final int SAVE_INTERVAL_TICKS = 1200;

    private static volatile PetWidgetReader.Snapshot snapshot = PetWidgetReader.Snapshot.MISSING;
    private static int ticks;

    private LevelingAssistant() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(LevelingAssistant::tick);
    }

    private static void tick(Minecraft client) {
        if (client.player == null) {
            LevelingSession.onWorldLeave();
            snapshot = PetWidgetReader.Snapshot.MISSING;
            return;
        }

        ExpShareTracker.tick(client);

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
