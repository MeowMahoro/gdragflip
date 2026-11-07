package net.gdragflip.leveling;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.gdragflip.GdragFlipMod;
import net.gdragflip.analysis.PetXpCurve;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

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
    private static long lastEstimateAt;
    /** 地牢内累计时长（进本开始计时，离开/长时间无经验暂停）。 */
    private static long dungeonMillis;
    /** 真实经验校准点：最近一次从 /pets 菜单读到的经验与当时的会话累计。 */
    private static long lastRealExp = -1L;
    private static long lastRealAt;
    private static long totalAtLastReal;
    /** 最近一次召唤的宠物名（来自聊天消息）。 */
    private static String currentPetName = "";
    /** 换宠后下一次采样只对基准、不计增量。 */
    private static boolean pendingRebaseline;
    /** 上次校准对应的宠物 UUID，用于检测「换了一只金龙」。 */
    private static String lastRealPetId = "";
    /** 本次会话绑定的宠物（UUID + 名字）。空表示还没开始会话。 */
    private static String boundPetId = "";
    private static String boundPetName = "";
    /** 名字的归一化核心（去掉 ⭐/✦ 等符号，小写），用于名字比对。 */
    private static String boundPetCore = "";
    /** true = 绑定的是菜单里 active 那只（UUID 可信）；false = 兜底绑定，不做 UUID 比对。 */
    private static boolean boundConfident = true;
    /** 已请求开始会话、等待玩家装备宠物并打开 /pets 完成绑定。 */
    private static boolean pendingBind;
    /** 当前宠物与会话绑定的宠物不一致。 */
    private static boolean mismatch;
    /** 估算值的修正系数（真实增量 / 未修正的估算增量）。 */
    private static double estimateFactor = 1.0;
    /** 自上次校准点以来「未乘系数」的估算经验，用于一次解出系数。 */
    private static long rawSinceAnchor;
    /** 校准最少需要的样本量（经验）与时间间隔，样本太小时不更新系数。 */
    private static final long MIN_CALIBRATION_XP = 50_000L;
    private static final long MIN_CALIBRATION_MILLIS = 60_000L;
    private static Path file;

    private LevelingSession() {
    }

    /** 会话数据的落盘格式。 */
    private static final class Stored {
        long totalXp;
        long startedAt;
        long activeMillis;
        long dungeonMillis;
        long lastRealExp;
        long totalAtLastReal;
        double estimateFactor;
        String boundPetId;
        String boundPetName;
        String boundPetCore;
        boolean boundConfident;
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
                dungeonMillis = Math.max(0L, stored.dungeonMillis);
                lastRealExp = stored.lastRealExp;
                totalAtLastReal = stored.totalAtLastReal;
                boundPetId = stored.boundPetId == null ? "" : stored.boundPetId;
                boundPetName = stored.boundPetName == null ? "" : stored.boundPetName;
                boundPetCore = stored.boundPetCore == null || stored.boundPetCore.isEmpty()
                        ? coreName(boundPetName)
                        : stored.boundPetCore;
                boundConfident = stored.boundConfident;
                if (stored.estimateFactor > 0.0) {
                    estimateFactor = clampFactor(stored.estimateFactor);
                }
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
            stored.dungeonMillis = dungeonMillis;
            stored.lastRealExp = lastRealExp;
            stored.totalAtLastReal = totalAtLastReal;
            stored.estimateFactor = estimateFactor;
            stored.boundPetId = boundPetId;
            stored.boundPetName = boundPetName;
            stored.boundPetCore = boundPetCore;
            stored.boundConfident = boundConfident;
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
        if (snapshot.found()) {
            // 精确数据可用：立刻撤销「估算」标记，不必等 120 秒窗口过期。
            lastEstimateAt = 0L;
            // 必须先观测再判 mismatch，否则 mismatch 一旦置位就没有恢复路径。
            onPetObserved(snapshot.petName());
        }

        // 未开始会话（没绑定宠物）时不统计。
        if (boundPetId.isEmpty()) {
            goldenDragonEquipped = false;
            lastTotalExp = -1L;
            return;
        }
        // 当前宠物与绑定不符：暂停累计，等换回来再重新对基准。
        if (mismatch) {
            goldenDragonEquipped = false;
            lastTotalExp = -1L;
            return;
        }

        if (!snapshot.found() || !snapshot.goldenDragon()) {
            goldenDragonEquipped = false;
            lastTotalExp = -1L;
            return;
        }

        goldenDragonEquipped = true;

        if (pendingRebaseline) {
            // 刚换过宠物：只重设基准，不计增量。
            pendingRebaseline = false;
            lastTotalExp = snapshot.totalExp();
            lastSampleAt = System.currentTimeMillis();
            return;
        }

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
    /** 清零统计并解除绑定（下次需要重新 /gdrag session start）。 */
    public static synchronized void reset() {
        clearCounters();
        boundPetId = "";
        boundPetName = "";
        boundPetCore = "";
        boundConfident = true;
        pendingBind = false;
        mismatch = false;
        dirty = true;
        save();
    }

    private static synchronized void clearCounters() {
        totalXp = 0L;
        activeMillis = 0L;
        dungeonMillis = 0L;
        startedAt = System.currentTimeMillis();
        lastTotalExp = -1L;
        lastSampleAt = -1L;
        lastEstimateAt = 0L;
        lastRealExp = -1L;
        lastRealAt = 0L;
        totalAtLastReal = 0L;
        rawSinceAnchor = 0L;
        lastRealPetId = "";
        pendingRebaseline = false;
        estimateFactor = 1.0;
        dirty = true;
    }

    /** 离开世界时清掉基准（避免用旧经验算差值），累计值保留。 */
    public static synchronized void onWorldLeave() {
        lastTotalExp = -1L;
        lastSampleAt = -1L;
        goldenDragonEquipped = false;
        widgetDetected = false;
        saveIfDirty();
    }

    /**
     * 地牢内没有 Pet Widget 时，由 action bar 技能经验换算出的估算增量。
     * 判重逻辑在 {@link SkillXpWatcher}。
     */
    public static synchronized void addEstimated(long xp) {
        if (xp <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastEstimateAt > 0) {
            long gap = now - lastEstimateAt;
            if (gap > 0 && gap <= 15_000L) {
                activeMillis += gap;
            }
        }
        lastEstimateAt = now;
        rawSinceAnchor += xp;
        totalXp += Math.max(1L, Math.round(xp * estimateFactor));
        dirty = true;
    }

    /** 最近 30 秒内是否在用估算数据。 */
    public static synchronized boolean usingEstimate() {
        return estimatedWithin(30_000L);
    }

    public static synchronized boolean estimatedWithin(long millis) {
        return lastEstimateAt > 0 && System.currentTimeMillis() - lastEstimateAt < millis;
    }

    /**
     * 记录「You summoned your X!」里的当前宠物名。
     *
     * <p>地牢内看不到 Pet Widget，只能靠这条聊天消息判断现在练的是不是金龙
     * （死亡后 autopet 切 Spirit 之类的情况）。</p>
     */
    public static synchronized void onPetSummoned(String name) {
        if (name == null) {
            return;
        }
        // 换宠后下一帧的经验值不能和上一只做差（否则两只金龙的等级差会被当成"刚获得的经验"）。
        pendingRebaseline = true;
        onPetObserved(name);
    }

    /**
     * 观测到当前出战的宠物（来源：召唤消息 / 升级消息 / Pet Widget / /pets 菜单）。
     * 只要名字与绑定宠物不同就标记不匹配，不特判某一只宠物。
     */
    public static synchronized void onPetObserved(String name) {
        if (name == null) {
            return;
        }
        String cleaned = name.replace("\u2726", "").trim();
        if (cleaned.isEmpty()) {
            return;
        }
        currentPetName = cleaned;

        // 每次都重新判定，不能因为名字没变就早退（否则 mismatch 一旦置位就永远卡住）。
        if (boundPetId.isEmpty()) {
            return;
        }
        String boundCore = boundPetCore.isEmpty() ? coreName(boundPetName) : boundPetCore;
        if (boundCore.isEmpty()) {
            return;
        }
        boolean nowMismatch = !namesMatch(coreName(cleaned), boundCore);
        if (nowMismatch != mismatch) {
            mismatch = nowMismatch;
            if (mismatch) {
                // 切到别的宠物：丢掉基准，切回来时重新对基准。
                lastTotalExp = -1L;
                pendingRebaseline = true;
            }
        }
    }

    /**
     * 名字归一化：去掉 ⭐/✦/颜色码等符号与多余空白，只保留字母数字，便于比较。
     * 例："⭐ Golden Dragon" 与 "Golden Dragon ✦" 都归一为 "golden dragon"。
     */
    /** 双向包含比较：兼容「Golden Dragon」vs「Golden Dragon Egg」这类前后缀差异。 */
    private static boolean namesMatch(String observed, String boundCore) {
        return observed.contains(boundCore) || boundCore.contains(observed);
    }

    private static String coreName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        String text = name.replaceAll("§.", "").toLowerCase(Locale.ROOT);
        text = text.replaceAll("[^\\p{L}\\p{N}]+", " ");
        return text.trim().replaceAll("\\s+", " ");
    }

    public static synchronized String currentPetName() {
        return currentPetName;
    }

    public static synchronized boolean currentPetKnown() {
        return !currentPetName.isEmpty();
    }

    public static synchronized boolean currentPetIsGoldenDragon() {
        return currentPetName.toLowerCase(Locale.ROOT).contains("golden dragon");
    }

    /** 请求开始新会话：等玩家装备宠物并打开 /pets 时完成 uuid 绑定。 */
    public static synchronized void requestStart() {
        pendingBind = true;
        mismatch = false;
        boundPetId = "";
        boundPetName = "";
        boundPetCore = "";
        boundConfident = true;
        // 「开始新会话」= 立即清零，避免绑定前 HUD 还显示上一次的数据。
        clearCounters();
        save();
    }

    public static synchronized boolean pendingBind() {
        return pendingBind;
    }

    public static synchronized String boundPetName() {
        return boundPetName;
    }

    public static synchronized boolean bound() {
        return !boundPetId.isEmpty();
    }

    public static synchronized String boundPetId() {
        return boundPetId;
    }

    public static synchronized boolean boundConfident() {
        return boundConfident;
    }

    /** 距离最近一次估算数据过了多久（毫秒）；没估算过返回 -1。 */
    public static synchronized long estimateAgeMillis() {
        return lastEstimateAt <= 0 ? -1L : System.currentTimeMillis() - lastEstimateAt;
    }

    public static synchronized boolean mismatch() {
        return mismatch;
    }

    /**
     * /pets 菜单里读到「当前出战」的宠物。
     *
     * @return {@code bound} 完成绑定 / {@code mismatch} 与绑定不符 / {@code calibrated} 正常校准 / {@code ignored} 未开始会话
     */
    public static synchronized String onActivePetRead(String petId, String petName, long exp, boolean confident) {
        String id = petId == null ? "" : petId;
        String name = petName == null ? "" : petName;

        if (pendingBind) {
            pendingBind = false;
            clearCounters();
            boundPetId = id;
            boundPetName = name;
            boundPetCore = coreName(name);
            boundConfident = confident && !id.isEmpty();
            startedAt = System.currentTimeMillis();
            lastRealExp = exp;
            totalAtLastReal = 0L;
            lastRealPetId = id;
            mismatch = false;
            dirty = true;
            save();
            return boundConfident ? "bound" : "bound-weak";
        }

        if (boundPetId.isEmpty()) {
            return "ignored";
        }
        // UUID 变化但名字一致：只当作「同一只宠物的身份刷新」，重新锚定校准，不暂停统计。
        if (boundConfident && !id.isEmpty() && !id.equals(boundPetId)) {
            GdragFlipMod.LOGGER.info("[petmenu] UUID 变化（{} -> {}），重新锚定校准", boundPetId, id);
            boundPetId = id;
            lastRealPetId = id;
            lastRealExp = exp;
            totalAtLastReal = totalXp;
            dirty = true;
        }

        // 名字不一致才算真的换宠（核心名比较，忽略 ⭐/✦ 等符号）。
        String observed = coreName(name);
        if (!boundPetCore.isEmpty() && !observed.isEmpty() && !namesMatch(observed, boundPetCore)) {
            mismatch = true;
            lastTotalExp = -1L;
            dirty = true;
            return "mismatch";
        }

        mismatch = false;
        onRealExpRead(exp, id);
        return "calibrated";
    }

    /** 地牢内计时：只在「无 Pet Widget 且仍在持续获得经验」时累加。 */
    public static synchronized void addDungeonMillis(long millis) {
        if (millis > 0) {
            dungeonMillis += Math.min(millis, 1_000L);
            dirty = true;
        }
    }

    public static synchronized long dungeonMillis() {
        return dungeonMillis;
    }

    public static synchronized double estimateFactor() {
        return estimateFactor;
    }

    /**
     * 用 /pets 菜单读到的真实经验校准：把「上次校准点之后」的估算增量替换成真实增量，
     * 并按比值平滑更新估算系数（下次估算自动带上偏差修正）。
     */
    public static synchronized void onRealExpRead(long realExp) {
        onRealExpRead(realExp, "");
    }

    /**
     * @param petId 宠物 UUID（petInfo 的 uniqueId/uuid），用于识别是否换了一只金龙；
     *              为空表示不做身份校验
     */
    public static synchronized void onRealExpRead(long realExp, String petId) {
        if (realExp <= 0) {
            return;
        }
        String id = petId == null ? "" : petId;
        boolean samePet = id.isEmpty() || lastRealPetId.isEmpty() || id.equals(lastRealPetId);
        if (!samePet) {
            GdragFlipMod.LOGGER.info("校准对象切换（{} -> {}），本次只重设锚点", lastRealPetId, id);
            lastRealExp = realExp;
            lastRealPetId = id;
            totalAtLastReal = totalXp;
            return;
        }
        long now = System.currentTimeMillis();
        long sinceLastRead = lastRealAt > 0 ? now - lastRealAt : Long.MAX_VALUE;
        if (lastRealExp > 0) {
            long realDelta = realExp - lastRealExp;
            long estimateDelta = totalXp - totalAtLastReal;
            if (realDelta > 0 && realDelta <= MAX_DELTA) {
                totalXp = totalAtLastReal + realDelta;
                dirty = true;

                // 直接解出系数：真实增量 / 未修正的估算增量（一次即可收敛）。
                boolean enoughSample = rawSinceAnchor >= MIN_CALIBRATION_XP
                        && sinceLastRead >= MIN_CALIBRATION_MILLIS;
                if (enoughSample) {
                    double ideal = realDelta / (double) rawSinceAnchor;
                    if (ideal > 0.2 && ideal < 5.0) {
                        estimateFactor = clampFactor(ideal);
                        GdragFlipMod.LOGGER.info("校准收敛：真实 +{}，未修正估算 +{} → 系数 {}",
                                realDelta, rawSinceAnchor, String.format("%.3f", estimateFactor));
                    }
                } else {
                    GdragFlipMod.LOGGER.info("经验已校准（样本不足，暂不更新系数）：真实 +{}，估算 +{}",
                            realDelta, estimateDelta);
                }
                rawSinceAnchor = 0L;
            } else if (realDelta == 0 && estimateDelta > 0 && sinceLastRead >= 60_000L) {
                // 一分钟以上真实经验没动但估算涨了：说明估算在空转，抹掉这段虚增。
                totalXp = totalAtLastReal;
                rawSinceAnchor = 0L;
                dirty = true;
                GdragFlipMod.LOGGER.info("经验校准：真实未增长，撤销估算 +{}", estimateDelta);
            }
        }
        lastRealExp = realExp;
        lastRealAt = now;
        lastRealPetId = id;
        totalAtLastReal = totalXp;
    }

    /**
     * 用「升级消息」作为免费锚点：{@code Your Golden Dragon leveled up to level N!}
     * 表示此刻累计经验 ≈ 该等级起点经验，不需要打开菜单也能校准。
     */
    public static synchronized void onLevelUpAnchor(int level) {
        if (level <= 1) {
            return;
        }
        long exp = PetXpCurve.expAtLevel(level);
        if (exp > 0) {
            onRealExpRead(exp, lastRealPetId);
        }
    }

    /** 自上次校准点以来未修正的估算经验（调试用）。 */
    public static synchronized long rawSinceAnchor() {
        return rawSinceAnchor;
    }

    /** 名字是否与绑定的宠物一致（用于升级消息锚点）。 */
    public static synchronized boolean matchesBound(String name) {
        if (boundPetId.isEmpty()) {
            return false;
        }
        String boundCore = boundPetCore.isEmpty() ? coreName(boundPetName) : boundPetCore;
        String observed = coreName(name);
        return !boundCore.isEmpty() && !observed.isEmpty() && namesMatch(observed, boundCore);
    }

    private static double clampFactor(double value) {
        return Math.max(0.2, Math.min(5.0, value));
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
