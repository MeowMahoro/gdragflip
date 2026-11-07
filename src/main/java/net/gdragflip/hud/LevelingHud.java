package net.gdragflip.hud;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.gdragflip.analysis.GdragAnalysis;
import net.gdragflip.analysis.PetXpCurve;
import net.gdragflip.chat.ChatReport;
import net.gdragflip.config.GdragConfig;
import net.gdragflip.leveling.ExpShareTracker;
import net.gdragflip.leveling.LevelingAssistant;
import net.gdragflip.leveling.LevelingSession;
import net.gdragflip.leveling.PetWidgetReader;
import net.gdragflip.scan.ScanService;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 「升级助手」HUD：显示本次会话金龙获得的经验、折合金币，以及 Exp Share 槽位的估算收益。
 *
 * <p>价格基准直接取拍卖扫描的「每经验单价」，auto 扫描会持续刷新它。</p>
 */
public final class LevelingHud implements HudElement {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("gdragflip", "leveling_hud");
    private static final int LINE_GAP = 1;

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        GdragConfig config = GdragConfig.get();
        if (!config.levelingHud) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        render(graphics, client, config.hudX, config.hudY);
    }

    /** 在指定坐标绘制 HUD，返回占用高度。HUD 本体与位置编辑界面共用。 */
    public static int render(GuiGraphicsExtractor graphics, Minecraft client, int x, int y) {
        List<Component> lines = buildLines(GdragConfig.get());
        if (lines.isEmpty()) {
            return 0;
        }

        int cursor = y;
        for (Component line : lines) {
            graphics.text(client.font, line, x, cursor, 0xFFFFFFFF);
            cursor += client.font.lineHeight + LINE_GAP;
        }
        return cursor - y;
    }

    private static String formatRate(double rate) {
        if (rate <= 0) {
            return "";
        }
        return rate == Math.floor(rate)
                ? (int) rate + "%"
                : String.format(java.util.Locale.ROOT, "%.1f%%", rate);
    }

    public static List<Component> buildLines(GdragConfig config) {
        List<Component> lines = new ArrayList<>();
        PetWidgetReader.Snapshot snapshot = LevelingAssistant.snapshot();
        long sessionXp = LevelingSession.totalXp();
        double pricePerExp = pricePerExp();

        lines.add(Component.literal("金龙升级助手").withStyle(ChatFormatting.GOLD));

        boolean accurateNow = snapshot.found() && snapshot.goldenDragon();
        // 120 秒窗口：两次击杀之间短暂空闲仍显示「估算」，避免状态闪烁；
        // 精确数据可用时立即不计为估算。
        boolean estimating = !accurateNow && LevelingSession.estimatedWithin(120_000L);

        if (accurateNow) {
            // 精确数据时等级由「主宠」段展示，这里不重复。
        } else if (LevelingSession.mismatch()) {
            // 不符优先于「估算」：暂停状态下不该再显示估算来源。
            String current = LevelingSession.currentPetName();
            lines.add(Component.literal("⚠ 当前宠物 " + (current.isEmpty() ? "未知" : current)
                    + "，与绑定 " + LevelingSession.boundPetName() + " 不符（已暂停）")
                    .withStyle(ChatFormatting.RED));
        } else if (estimating) {
            // 地牢内：来自 action bar 技能经验换算，不再提示 Widget。
            lines.add(Component.literal("估算 · 地牢技能经验").withStyle(ChatFormatting.AQUA));
        } else if (snapshot.found()) {
            lines.add(Component.literal("请装备金龙宠物").withStyle(ChatFormatting.RED));
        } else {
            lines.add(Component.literal("等待经验数据…").withStyle(ChatFormatting.GRAY));
            if (LevelingSession.dungeonMillis() == 0L) {
                lines.add(Component.literal("（/tab 开启 Pet Widget 可获得精确数据）")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }

        if (!LevelingSession.bound()) {
            lines.add(Component.literal("未开始会话（/gdrag session start）").withStyle(ChatFormatting.GRAY));
        }

        // 速率分母：地牢内计时优先；没有地牢记录时用装备活跃时长。
        boolean dungeonBased = LevelingSession.dungeonMillis() >= 60_000L;
        long baseMillis = dungeonBased ? LevelingSession.dungeonMillis() : LevelingSession.activeMillis();
        double hours = Math.max(1.0 / 3600.0, baseMillis / 3_600_000.0);
        boolean rateReady = baseMillis >= 60_000L;

        boolean shareEnabled = config.expShareEnabled;
        boolean shareKnown = shareEnabled && ExpShareTracker.hasData();
        List<ExpShareTracker.SharePet> sharePets = shareEnabled ? ExpShareTracker.pets() : List.of();
        int slots = shareEnabled ? (shareKnown ? Math.max(1, ExpShareTracker.observedSlots()) : 0) : 0;

        // 优先用每个槽位 lore 里写的真实比例；读不到时才退回配置系数。
        long shareTotalXp = 0L;
        List<Long> perPetXp = new ArrayList<>();
        if (shareEnabled) {
            if (!sharePets.isEmpty()) {
                for (ExpShareTracker.SharePet pet : sharePets) {
                    double rate = pet.ratePercent() > 0 ? pet.ratePercent() : config.expShareFactorPercent;
                    long xp = Math.round(sessionXp * rate / 100.0);
                    perPetXp.add(xp);
                    shareTotalXp += xp;
                }
            } else {
                long xp = Math.round(sessionXp * config.expShareFactorPercent / 100.0);
                shareTotalXp = xp * Math.max(1, config.expShareSlots);
            }
        }

        // 不确定来源：地牢内主宠是估算；分享槽在没读过 Exp Sharing 界面时也不确定。
        String mainMark = estimating ? "≈ " : "";
        String shareMark = (estimating || !shareKnown) ? "≈ " : "";
        boolean totalUncertain = shareEnabled ? (estimating || !shareKnown) : estimating;
        String totalMark = totalUncertain ? "≈ " : "";

        // ── 主宠 ──────────────────────────────
        lines.add(Component.literal("主宠：").withStyle(ChatFormatting.GREEN));
        if (accurateNow) {
            lines.add(Component.literal("  " + PetXpCurve.levelLabel(snapshot.totalExp()))
                    .withStyle(ChatFormatting.WHITE));
        }
        lines.add(Component.literal("  " + mainMark + "累计 " + signed(sessionXp) + " XP"
                + (pricePerExp > 0 ? " ｜ " + signed(Math.round(sessionXp * pricePerExp)) + " coins" : ""))
                .withStyle(ChatFormatting.WHITE));

        // ── 分享槽 ────────────────────────────
        if (shareEnabled) {
            lines.add(Component.literal("分享槽：").withStyle(ChatFormatting.YELLOW));
            String slotText = "  Slot x " + (shareKnown ? slots : "?");
            if (shareKnown) {
                double totalRate = 0;
                for (ExpShareTracker.SharePet pet : sharePets) {
                    totalRate += pet.ratePercent() > 0 ? pet.ratePercent() : config.expShareFactorPercent;
                }
                if (totalRate > 0) {
                    slotText += " ｜ " + formatRate(totalRate);
                }
            } else {
                slotText += "（打开 /pets → Exp Sharing 读取）";
            }
            lines.add(Component.literal(slotText).withStyle(ChatFormatting.GRAY));

            {
                lines.add(Component.literal("  " + shareMark + signed(shareTotalXp) + " XP"
                        + (pricePerExp > 0 ? " ｜ " + signed(Math.round(shareTotalXp * pricePerExp)) + " coins" : ""))
                        .withStyle(ChatFormatting.WHITE));
                for (int i = 0; i < sharePets.size(); i++) {
                    ExpShareTracker.SharePet pet = sharePets.get(i);
                    double rate = pet.ratePercent() > 0 ? pet.ratePercent() : config.expShareFactorPercent;
                    String levelPart = pet.level() > 0 ? " Lvl " + pet.level() : "";
                    String ratePart = pet.ratePercent() > 0 ? "  " + formatRate(rate) : "";
                    lines.add(Component.literal("    " + pet.name() + levelPart + ratePart
                            + "  " + shareMark + signed(perPetXp.get(i)) + " XP")
                            .withStyle(ChatFormatting.GRAY));
                }
            }
        }

        // ── 合计 ──────────────────────────────
        long totalXp = sessionXp + shareTotalXp;
        lines.add(Component.literal("合计：").withStyle(ChatFormatting.RED));
        StringBuilder total = new StringBuilder("  ").append(totalMark).append(signed(totalXp)).append(" XP");
        if (pricePerExp > 0) {
            total.append(" ｜ ").append(signed(Math.round(totalXp * pricePerExp))).append(" coins");
            if (rateReady) {
                total.append(" ｜ ").append(signed(Math.round(totalXp * pricePerExp / hours))).append("/h");
            }
        } else if (rateReady) {
            total.append(" ｜ ").append(ChatReport.coins(Math.round(totalXp / hours))).append(" XP/h");
        }
        lines.add(Component.literal(total.toString()).withStyle(ChatFormatting.WHITE));

        // 次要信息（时间基准 + 单价）
        String footer = (dungeonBased ? "地牢 " : "累计 ") + duration(baseMillis);
        footer += pricePerExp > 0
                ? " ｜ 每 1M XP ≈ " + ChatReport.coins(Math.round(pricePerExp * 1_000_000.0)) + " coins"
                : " ｜ 行情未就绪";
        lines.add(Component.literal(footer).withStyle(ChatFormatting.GRAY));

        return lines;
    }

    /** HUD 的像素尺寸，供拖动编辑界面做命中检测。 */
    public static int width(Minecraft client) {
        int max = 0;
        for (Component line : buildLines(GdragConfig.get())) {
            max = Math.max(max, client.font.width(line));
        }
        return max;
    }

    public static int height(Minecraft client) {
        return buildLines(GdragConfig.get()).size() * (client.font.lineHeight + LINE_GAP);
    }

    /** 当前拍卖扫描的每经验单价；没有扫描结果时为 0。 */
    private static double pricePerExp() {
        GdragAnalysis analysis = ScanService.lastResult();
        return analysis != null && analysis.hasPricing() ? analysis.pricePerExp() : 0.0;
    }

    private static String signed(long value) {
        return (value > 0 ? "+" : "") + ChatReport.coins(value);
    }

    private static String duration(long millis) {
        long minutes = Math.max(0L, millis / 60_000L);
        if (minutes < 60) {
            return minutes + "m";
        }
        return (minutes / 60) + "h" + (minutes % 60) + "m";
    }
}
