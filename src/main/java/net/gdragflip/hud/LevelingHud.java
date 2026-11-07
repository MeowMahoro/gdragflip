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
        int cursor = y;
        for (Component line : lines) {
            graphics.text(client.font, line, x, cursor, 0xFFFFFFFF);
            cursor += client.font.lineHeight + LINE_GAP;
        }
        return cursor - y;
    }

    public static List<Component> buildLines(GdragConfig config) {
        List<Component> lines = new ArrayList<>();
        PetWidgetReader.Snapshot snapshot = LevelingAssistant.snapshot();
        long sessionXp = LevelingSession.totalXp();
        double pricePerExp = pricePerExp();

        lines.add(Component.literal("金龙升级助手").withStyle(ChatFormatting.GOLD));

        if (!snapshot.found()) {
            lines.add(Component.literal("未检测到宠物 Widget").withStyle(ChatFormatting.RED));
            lines.add(Component.literal("请在 /tab 开启 Pet Widget").withStyle(ChatFormatting.GRAY));
        } else if (!snapshot.goldenDragon()) {
            lines.add(Component.literal("请装备金龙宠物").withStyle(ChatFormatting.RED));
        } else {
            lines.add(Component.literal(PetXpCurve.levelLabel(snapshot.totalExp()))
                    .withStyle(ChatFormatting.YELLOW));
        }

        lines.add(Component.literal("累计 " + signed(sessionXp) + " XP").withStyle(ChatFormatting.WHITE));

        if (pricePerExp > 0) {
            long coins = Math.round(sessionXp * pricePerExp);
            lines.add(Component.literal("≈ " + signed(coins) + " coins").withStyle(ChatFormatting.GREEN));
            lines.add(Component.literal("基准 " + formatPrice(pricePerExp) + " ｜ "
                    + ChatReport.coins(Math.round(LevelingSession.xpPerHour())) + " XP/h")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            lines.add(Component.literal("行情未就绪（/gdrag 或开启自动扫描）").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal(ChatReport.coins(Math.round(LevelingSession.xpPerHour())) + " XP/h")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        if (config.expShareEnabled && sessionXp > 0) {
            List<ExpShareTracker.SharePet> sharePets = ExpShareTracker.pets();
            int slots = Math.max(1, config.expShareSlots);
            long perPet = Math.round(sessionXp * config.expShareFactorPercent / 100.0);
            long total = perPet * slots;
            String label = "Exp Share ×" + slots + " ≈ " + signed(total) + " XP";
            if (pricePerExp > 0) {
                label += " ｜ " + signed(Math.round(total * pricePerExp)) + " coins";
            }
            lines.add(Component.literal(label).withStyle(ChatFormatting.AQUA));

            for (int i = 0; i < sharePets.size() && i < slots; i++) {
                ExpShareTracker.SharePet pet = sharePets.get(i);
                lines.add(Component.literal("  " + pet.name() + " Lvl " + pet.level()
                        + "  ≈ " + signed(perPet) + " XP").withStyle(ChatFormatting.DARK_AQUA));
            }
        }

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

    /** 每经验单价很小，用「百万经验」为单位显示更好读。 */
    private static String formatPrice(double pricePerExp) {
        return ChatReport.coins(Math.round(pricePerExp * 1_000_000.0)) + "/1M XP";
    }
}
