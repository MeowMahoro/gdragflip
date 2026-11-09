package net.gdragflip.leveling;

import net.gdragflip.GdragFlipMod;
import net.gdragflip.config.GdragConfig;
import net.minecraft.network.chat.Component;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 地牢内的兜底经验来源：解析 action bar 里出现的技能经验
 * （形如 {@code +1,234 Combat (45.6%)}），按倍率换算成宠物经验。
 *
 * <p>地牢里 Hypixel 不显示 Pet Widget，拿不到真实宠物经验，只能用这个估算；
 * 倍率默认 1.5（金龙是 Combat 宠物，吃 Combat 技能经验）。</p>
 *
 * <p>同时监听系统聊天里的 {@code You summoned your <pet>!}：地牢内死亡后 autopet
 * 可能切到 Spirit 等宠物，此时必须停止把经验算到金龙头上。</p>
 */
public final class SkillXpWatcher {
    /** "+1,234 Combat (45.6%)" 或 "+1,234 Combat (1,234/5,678)"。 */
    private static final Pattern ACTION_BAR_PATTERN = Pattern.compile(
            "\\+([\\d,.]+[kKmMbB]?)\\s+(Combat|Mining|Farming|Fishing|Foraging|Enchanting|Alchemy|Taming)\\s*\\(");

    private static final Pattern SUMMON_PATTERN = Pattern.compile("You summoned your (.+?)!");
    /** 升级消息可反证「当前正在练这只宠物」，同时可当免费校准锚点。 */
    private static final Pattern LEVEL_UP_PATTERN = Pattern.compile("Your (.+?) leveled up to level (\\d+)!");

    private static volatile long lastEstimateAt;

    private SkillXpWatcher() {
    }

    /** Fabric 消息回调：overlay=true 表示 action bar，false 表示普通系统聊天。 */
    public static void onGameMessage(Component message, boolean overlay) {
        String text = clean(message.getString());

        if (!overlay) {
            Matcher summon = SUMMON_PATTERN.matcher(text);
            if (summon.find()) {
                String pet = summon.group(1).trim();
                LevelingSession.onPetSummoned(pet);
                GdragFlipMod.LOGGER.debug("[estimate] 当前宠物切换为 {}", pet);
                return;
            }
            Matcher levelUp = LEVEL_UP_PATTERN.matcher(text);
            if (levelUp.find()) {
                // 升级消息只有名字、没有 UUID：分享槽里的同名宠物升级也会触发，
                // 无法可靠区分是哪一只，因此**不用于身份判定、也不作为校准锚点**。
                if (GdragConfig.get().debugVerbose) {
                    GdragFlipMod.LOGGER.info("[ignore] 升级消息（不采信）：{} L{}", levelUp.group(1), levelUp.group(2));
                }
            }
            return;
        }

        GdragConfig config = GdragConfig.get();
        if (!config.dungeonEstimateEnabled) {
            return;
        }
        // 未开始会话 / 宠物与绑定不符时不计。
        if (!LevelingSession.bound() || LevelingSession.mismatch()) {
            return;
        }
        // Pet Widget 正常时用真实数据，不叠加估算。
        if (LevelingSession.widgetDetected()) {
            return;
        }
        Matcher matcher = ACTION_BAR_PATTERN.matcher(text);
        if (!matcher.find()) {
            return;
        }

        long skillXp = parseAmount(matcher.group(1));
        if (skillXp <= 0) {
            return;
        }

        long petXp = Math.round(skillXp * config.dungeonPetXpMultiplier);
        if (petXp <= 0) {
            return;
        }
        LevelingSession.addEstimated(petXp);
        lastEstimateAt = System.currentTimeMillis();
        if (config.debugVerbose) {
            GdragFlipMod.LOGGER.info("[estimate] 技能 +{} → 宠物 +{}（系数 {}，累计 {}）",
                    skillXp, petXp, String.format("%.2f", LevelingSession.estimateFactor()),
                    LevelingSession.totalXp());
        }
    }

    /** 最近 30 秒内是否有估算数据进入。 */
    public static boolean recentlyEstimated() {
        return System.currentTimeMillis() - lastEstimateAt < 30_000L;
    }

    private static String clean(String raw) {
        return Normalizer.normalize(raw.replaceAll("§.", ""), Normalizer.Form.NFKC).trim();
    }

    private static long parseAmount(String raw) {
        String value = raw.replace(",", "").trim();
        double multiplier = 1.0;
        char last = value.charAt(value.length() - 1);
        if (last == 'k' || last == 'K') {
            multiplier = 1_000.0;
            value = value.substring(0, value.length() - 1);
        } else if (last == 'm' || last == 'M') {
            multiplier = 1_000_000.0;
            value = value.substring(0, value.length() - 1);
        } else if (last == 'b' || last == 'B') {
            multiplier = 1_000_000_000.0;
            value = value.substring(0, value.length() - 1);
        }
        try {
            return Math.round(Double.parseDouble(value) * multiplier);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
