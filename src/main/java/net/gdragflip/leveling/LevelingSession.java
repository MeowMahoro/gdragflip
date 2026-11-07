package net.gdragflip.leveling;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.gdragflip.GdragFlipMod;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 宠物经验累计（只统计金龙）。
 *
 * <p>每次 tab 宠物 Widget 刷新时采样一次累计经验，与上次采样求差并累加。
 * 差值异常（换宠物、数据回退、跨世界）时只重新对基准，不计入。</p>
 *
 * <p><b>不会自动重置</b>：换世界、退出游戏、重启客户端都会保留累计值，
 * 只有 {@code /gdrag session reset}（或设置界面的重置按钮）才会清零。</p>
 */
public final class LevelingSession {
    /** 单次采样允许的最大增量，超过视为异常（换宠物/重登）。 */
    private static final long MAX_DELTA = 200_000_000L;
    /** 两次采样间隔超过该值就不计入活跃时长（切屏/AFK/断线重连）。 */
    private static final long MAX_SAMPLE_GAP_MILLIS = 10_000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static long startedAt = System.currentTimeMillis();
    private static long totalXp;
    /** 真正装备金龙、且在采样中的累计时长，用于计算 XP/h（避免把离线时间算进去）。 */
    private static long activeMillis;
    private static long lastSampleAt = -1L;
    private static long lastTotalExp = -1L;
    private static boolean goldenDragonEquipped;
    private static boolean widgetDetected;
    private static boolean dirty;
    private static Path file;

    private LevelingSession() {
    }

    /** 会话数据的落盘格式。 */
    private static final class Stored {
        long totalXp;
        long startedAt;
        long activeMillis;
    }

    public static synchronized void load() {
        file = FabricLoader.getInstance().getConfigDir().resolve("gdragflip-session.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Stored stored = GSON.fromJson(reader, Stored.class);
            if (stored != null) {
                totalXp = Math.max(0L, stored.totalXp);
                activeMillis = Math.max(0L, stored.activeMillis);
                if (stored.startedAt > 0) {
                    startedAt = stored.startedAt;
                }
            }
            GdragFlipMod.LOGGER.info("会话统计已恢复：{} XP", totalXp);
        } catch (Exception e) {
            GdragFlipMod.LOGGER.warn("会话统计读取失败，从零开始", e);
        }
    }

    public static synchronized void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Stored stored = new Stored();
            stored.totalXp = totalXp;
            stored.startedAt = startedAt;
            stored.activeMillis = activeMillis;
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(stored, writer);
            }
            dirty = false;
        } catch (Exception e) {
            GdragFlipMod.LOGGER.warn("会话统计写入失败", e);
        }
    }

    /** 有变化才写盘，供定时器调用。 */
    public static synchronized void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public static synchronized void sample(PetWidgetReader.Snapshot snapshot) {
        widgetDetected = snapshot.found();
        if (!snapshot.found() || !snapshot.goldenDragon()) {
            goldenDragonEquipped = false;
            lastTotalExp = -1L;
            return;
        }

        goldenDragonEquipped = true;
        long now = System.currentTimeMillis();
        if (lastSampleAt > 0) {
            long gap = now - lastSampleAt;
            if (gap > 0 && gap <= MAX_SAMPLE_GAP_MILLIS) {
                activeMillis += gap;
                dirty = true;
            }
        }
        lastSampleAt = now;

        long total = snapshot.totalExp();
        if (lastTotalExp < 0) {
            lastTotalExp = total;
            return;
        }

        long delta = total - lastTotalExp;
        if (delta > 0 && delta <= MAX_DELTA) {
            totalXp += delta;
            dirty = true;
        }
        lastTotalExp = total;
    }

    /** 仅由命令 / 设置界面调用。 */
    public static synchronized void reset() {
        totalXp = 0L;
        activeMillis = 0L;
        startedAt = System.currentTimeMillis();
        lastTotalExp = -1L;
        lastSampleAt = -1L;
        dirty = true;
        save();
    }

    /** 离开世界时清掉基准（避免用旧经验算差值），累计值保留。 */
    public static synchronized void onWorldLeave() {
        lastTotalExp = -1L;
        lastSampleAt = -1L;
        goldenDragonEquipped = false;
        widgetDetected = false;
        saveIfDirty();
    }

    public static synchronized long totalXp() {
        return totalXp;
    }

    public static synchronized long elapsedMillis() {
        return Math.max(1L, System.currentTimeMillis() - startedAt);
    }

    /** 装备金龙期间的每小时平均经验（不含离线/挂机时间）。 */
    public static synchronized double xpPerHour() {
        return totalXp / (Math.max(1L, activeMillis) / 3_600_000.0);
    }

    public static synchronized long activeMillis() {
        return activeMillis;
    }

    public static synchronized boolean goldenDragonEquipped() {
        return goldenDragonEquipped;
    }

    public static synchronized boolean widgetDetected() {
        return widgetDetected;
    }

    public static synchronized long startedAt() {
        return startedAt;
    }
}
