package net.gdragflip.analysis;

import net.gdragflip.GdragFlipMod;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 金龙（LEGENDARY）升级经验曲线：等级 ↔ 累计经验 的换算。
 *
 * <p>数据来自打包在 jar 内的 {@code gdragflip/pet_xp.csv}（200 级）。
 * 1–100 级为传奇宠物标准曲线，101–200 级每级固定 1,886,700 XP，
 * 累计 100 级 = 25,353,230、200 级 = 214,023,230。</p>
 *
 * <p>本类是整个定价模型的坐标系：内部一律用「累计经验」表示宠物的养成进度，
 * 等级只是展示用的换算结果。</p>
 */
public final class PetXpCurve {
    public static final int MIN_LEVEL = 100;
    public static final int MAX_LEVEL = 200;

    private static final long[] CUMULATIVE = new long[MAX_LEVEL + 1];
    private static final int[] PER_LEVEL = new int[MAX_LEVEL + 1];

    /** 200 级所需累计经验（封顶值）。 */
    public static final long CAP;

    static {
        long cumulative = 0L;
        int loaded = 0;
        try (InputStream stream = PetXpCurve.class.getResourceAsStream("/gdragflip/pet_xp.csv")) {
            if (stream == null) {
                throw new IllegalStateException("找不到资源 gdragflip/pet_xp.csv");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("level,")) {
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length < 2) {
                    continue;
                }
                int level = Integer.parseInt(parts[0].trim());
                int xp = Integer.parseInt(parts[1].trim());
                if (level < 1 || level > MAX_LEVEL) {
                    continue;
                }
                if (level > 1) {
                    cumulative += xp;
                }
                CUMULATIVE[level] = cumulative;
                PER_LEVEL[level] = xp;
                loaded++;
            }
        } catch (Exception e) {
            GdragFlipMod.LOGGER.error("宠物经验曲线加载失败，等级换算将不可用", e);
        }
        CAP = CUMULATIVE[MAX_LEVEL];
        if (loaded != MAX_LEVEL) {
            GdragFlipMod.LOGGER.error("宠物经验曲线不完整：期望 {} 级，实际 {}", MAX_LEVEL, loaded);
        } else {
            GdragFlipMod.LOGGER.info("宠物经验曲线已加载：{} 级，满级累计 {} XP", MAX_LEVEL, CAP);
        }
    }

    private PetXpCurve() {
    }

    /** 升到该等级所需的累计经验（等级 1 = 0）。 */
    public static long cumulative(int level) {
        if (level <= 1) {
            return 0L;
        }
        return CUMULATIVE[Math.min(level, MAX_LEVEL)];
    }

    /** 当前等级的起点经验（等价于 {@link #cumulative(int)}）。 */
    public static long expAtLevel(int level) {
        return cumulative(level);
    }

    /** 累计经验对应的等级（越界时夹在 1..200）。 */
    public static int levelOf(long exp) {
        if (exp <= 0) {
            return 1;
        }
        if (exp >= CAP) {
            return MAX_LEVEL;
        }
        int low = 1;
        int high = MAX_LEVEL;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (CUMULATIVE[mid] <= exp) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    /** 当前等级内多出来的经验（等级起点之后的部分）。 */
    public static long overflow(long exp) {
        return Math.max(0L, exp - cumulative(levelOf(exp)));
    }

    /** 当前等级内的进度 0..1；满级返回 0。 */
    public static double progress(long exp) {
        int level = levelOf(exp);
        if (level >= MAX_LEVEL) {
            return 0.0;
        }
        long start = CUMULATIVE[level];
        long span = CUMULATIVE[level + 1] - start;
        if (span <= 0) {
            return 0.0;
        }
        return Math.min(1.0, Math.max(0.0, (exp - start) / (double) span));
    }

    /** 升到下一级还需多少经验（满级为 0）。 */
    public static long toNextLevel(long exp) {
        int level = levelOf(exp);
        if (level >= MAX_LEVEL) {
            return 0L;
        }
        return CUMULATIVE[level + 1] - exp;
    }

    /** 定价用经验：200 级溢出不计，一律封顶。 */
    public static long capped(long exp) {
        return Math.min(exp, CAP);
    }

    /** 展示用等级文本，例如 {@code Lvl 123 +47%}。 */
    public static String levelLabel(long exp) {
        int level = levelOf(exp);
        if (level >= MAX_LEVEL) {
            return "Lvl " + MAX_LEVEL;
        }
        int percent = (int) Math.round(progress(exp) * 100.0);
        return percent <= 0 ? "Lvl " + level : "Lvl " + level + " +" + percent + "%";
    }
}
