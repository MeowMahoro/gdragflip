package net.gdragflip.config;

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
 * 配置（config/gdragflip.json）。
 *
 * <p>自动扫描间隔以<b>秒</b>为单位；低于 {@link #SAFE_INTERVAL_SECONDS} 时命令层要求二次确认，
 * 因为 Cofl 免费额度只有 30 请求/10 秒、100 请求/分钟，且累计 429 会被封 IP。</p>
 */
public final class GdragConfig {
    /** 允许的最小间隔（再低没有意义，一次扫描本身的请求就够多）。 */
    public static final int MIN_INTERVAL_SECONDS = 5;
    /** 安全阈值：低于该值必须二次确认。 */
    public static final int SAFE_INTERVAL_SECONDS = 20;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();

    private static Path file;
    private static volatile GdragConfig instance;

    /** 是否开启后台自动扫描。 */
    public boolean autoScan = false;
    /** 扫描间隔（秒）。 */
    public int intervalSeconds = 60;
    /** 只有当利润不低于该值时才提醒。 */
    public long minProfit = 5_000_000L;
    /** 只有当利润率不低于该百分比时才提醒。 */
    public double minRoiPercent = 5.0;

    /** 是否显示「升级助手」HUD。 */
    public boolean levelingHud = false;
    /** HUD 左上角坐标。 */
    public int hudX = 4;
    public int hudY = 4;
    /** 是否统计 Exp Share 槽位。 */
    public boolean expShareEnabled = true;
    /** 分享系数（%）：默认 12% 基础分享率 × 同技能宠物 1.5 倍 = 18%。 */
    public double expShareFactorPercent = 18.0;
    /** 分享槽位数量（1 或 3，取决于 Diana 的 Sharing is Caring）。 */
    public int expShareSlots = 1;

    public static GdragConfig get() {
        GdragConfig current = instance;
        if (current != null) {
            return current;
        }
        synchronized (LOCK) {
            if (instance == null) {
                load();
            }
            return instance;
        }
    }

    public static Path file() {
        if (file == null) {
            file = FabricLoader.getInstance().getConfigDir().resolve("gdragflip.json");
        }
        return file;
    }

    public static synchronized void load() {
        GdragConfig loaded = null;
        Path path = file();
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                loaded = GSON.fromJson(reader, GdragConfig.class);
            } catch (Exception e) {
                GdragFlipMod.LOGGER.warn("配置读取失败，使用默认值", e);
            }
        }
        if (loaded == null) {
            loaded = new GdragConfig();
        }
        loaded.normalize();
        instance = loaded;
        if (!Files.isRegularFile(path)) {
            save();
        }
    }

    public static synchronized void save() {
        GdragConfig current = instance;
        if (current == null) {
            return;
        }
        current.normalize();
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(current, writer);
            }
        } catch (Exception e) {
            GdragFlipMod.LOGGER.warn("配置写入失败", e);
        }
    }

    private void normalize() {
        intervalSeconds = Math.max(MIN_INTERVAL_SECONDS, intervalSeconds);
        minProfit = Math.max(0L, minProfit);
        minRoiPercent = Math.max(0.0, minRoiPercent);
        hudX = Math.max(0, hudX);
        hudY = Math.max(0, hudY);
        expShareFactorPercent = Math.max(0.0, Math.min(500.0, expShareFactorPercent));
        expShareSlots = Math.max(1, Math.min(3, expShareSlots));
    }
}
