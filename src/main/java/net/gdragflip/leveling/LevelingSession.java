package net.gdragflip.leveling;

/**
 * 本次会话的宠物经验统计（只统计金龙）。
 *
 * <p>每次 tab 宠物 Widget 刷新时采样一次累计经验，与上次采样求差并累加。
 * 差值异常（换宠物、数据回退、跨世界）时只重新对基准，不计入。</p>
 */
public final class LevelingSession {
    /** 单次采样允许的最大增量，超过视为异常（换宠物/重登）。 */
    private static final long MAX_DELTA = 200_000_000L;

    private static long startedAt = System.currentTimeMillis();
    private static long totalXp;
    private static long lastTotalExp = -1L;
    private static boolean goldenDragonEquipped;
    private static boolean widgetDetected;

    private LevelingSession() {
    }

    public static synchronized void sample(PetWidgetReader.Snapshot snapshot) {
        widgetDetected = snapshot.found();
        if (!snapshot.found() || !snapshot.goldenDragon()) {
            goldenDragonEquipped = false;
            lastTotalExp = -1L;
            return;
        }

        goldenDragonEquipped = true;
        long total = snapshot.totalExp();
        if (lastTotalExp < 0) {
            lastTotalExp = total;
            return;
        }

        long delta = total - lastTotalExp;
        if (delta > 0 && delta <= MAX_DELTA) {
            totalXp += delta;
        }
        lastTotalExp = total;
    }

    public static synchronized void reset() {
        totalXp = 0L;
        startedAt = System.currentTimeMillis();
        lastTotalExp = -1L;
    }

    /** 离开世界时清掉基准，但保留本次会话累计（重连后继续累加）。 */
    public static synchronized void onWorldLeave() {
        lastTotalExp = -1L;
        goldenDragonEquipped = false;
        widgetDetected = false;
    }

    public static synchronized long totalXp() {
        return totalXp;
    }

    public static synchronized long elapsedMillis() {
        return Math.max(1L, System.currentTimeMillis() - startedAt);
    }

    /** 本会话平均每小时经验。 */
    public static synchronized double xpPerHour() {
        return totalXp / (elapsedMillis() / 3_600_000.0);
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
