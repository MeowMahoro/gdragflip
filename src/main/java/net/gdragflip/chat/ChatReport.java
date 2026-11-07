package net.gdragflip.chat;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.gdragflip.analysis.GdragAnalysis;
import net.gdragflip.analysis.GdragCustom;
import net.gdragflip.analysis.GdragListing;
import net.gdragflip.analysis.GdragOpportunity;
import net.gdragflip.analysis.PetXpCurve;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/** 把分析结果渲染成聊天消息（等级+溢出经验、利润、可点击的查看拍卖按钮）。 */
public final class ChatReport {
    private static final String PREFIX = "[金龙] ";

    private ChatReport() {
    }

    public static Component info(String text, ChatFormatting color) {
        return Component.literal(PREFIX).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(color));
    }

    public static void send(FabricClientCommandSource source, GdragAnalysis analysis, int limit, GdragCustom custom) {
        source.sendFeedback(info("扫描 %d 条挂单，其中 %d 条 Lvl100+（Lvl200 %d 条）".formatted(
                analysis.scanned(), analysis.eligibleCount(), analysis.level200Count()), ChatFormatting.GRAY));

        if (analysis.eligibleCount() == 0) {
            source.sendFeedback(info("没有找到 Lvl100 以上的传奇金龙 BIN 挂单", ChatFormatting.RED));
            return;
        }

        if (!analysis.hasPricing()) {
            source.sendFeedback(info("无法计算每经验单价：缺少 Lvl200 挂单或价格倒挂", ChatFormatting.RED));
            GdragListing base = analysis.base();
            if (base != null) {
                source.sendFeedback(info("当前 Lvl100+ 最低价：%s · %s（exp %s）".formatted(
                        levelLabel(base), coins(base.price()), compact(base.exp())), ChatFormatting.YELLOW));
            }
            return;
        }

        GdragListing base = analysis.base();
        source.sendFeedback(info("基准：A %s %s（exp %s） ｜ B Lvl200 %s ｜ 每 1M XP ≈ %s".formatted(
                levelLabel(base), coins(base.price()), compact(base.exp()),
                coins(analysis.target200().price()),
                coins(Math.round(analysis.pricePerExp() * 1_000_000.0))), ChatFormatting.GRAY));

        List<GdragOpportunity> below = analysis.belowExpected();
        if (below.isEmpty()) {
            int fallback = Math.min(3, analysis.ranked().size());
            source.sendFeedback(info("没有低于理论价的挂单；下面是最接近的 %d 条：".formatted(fallback),
                    ChatFormatting.YELLOW));
            print(source, analysis.ranked(), fallback);
        } else {
            source.sendFeedback(info("低于理论价 %d 条（按利润降序）：".formatted(below.size()),
                    ChatFormatting.GREEN));
            print(source, below, Math.min(limit, below.size()));
        }

        if (custom != null) {
            source.sendFeedback(customLine(custom, analysis.ranked().size()));
        }
    }

    /** 自动扫描命中：玩家聊天栏单行提醒。 */
    public static void notify(Minecraft client, GdragOpportunity opportunity) {
        if (client.player == null) {
            return;
        }
        GdragListing listing = opportunity.listing();
        MutableComponent line = Component.literal(PREFIX).withStyle(ChatFormatting.GOLD)
                .append(Component.literal("新机会 ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(levelLabel(listing)).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("  " + coins(listing.price())).withStyle(ChatFormatting.WHITE))
                .append(Component.literal("  " + signedCoins(opportunity.profit())).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" (" + signedPercent(opportunity.roi()) + ")").withStyle(ChatFormatting.GRAY))
                .append(auctionButton(listing.uuid()))
                .append(webButton(listing.uuid()))
                .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(tooltip(opportunity))));
        client.player.sendSystemMessage(line);
    }

    /** 用户输入的假设挂单：蓝色单行，给出综合排名。 */
    private static Component customLine(GdragCustom custom, int total) {
        MutableComponent line = Component.literal("[自定义] ").withStyle(ChatFormatting.BLUE)
                .append(Component.literal("#" + custom.rank() + "/" + total).withStyle(ChatFormatting.BLUE))
                .append(Component.literal("  Lvl " + custom.level()).withStyle(ChatFormatting.BLUE))
                .append(Component.literal("  " + coins(custom.price())).withStyle(ChatFormatting.BLUE))
                .append(Component.literal("  " + signedCoins(custom.profit())).withStyle(ChatFormatting.BLUE));

        return line.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(
                Component.literal("该等级起点经验: " + String.format(Locale.ROOT, "%,d", custom.exp())
                                + " XP")
                        .append(Component.literal("\n理论价: " + String.format(Locale.ROOT, "%,d", custom.expectedPrice())
                                + " coins"))
                        .append(Component.literal("\n利润率: " + signedPercent(custom.roi()))
                                .withStyle(ChatFormatting.GRAY)))));
    }

    private static void print(FabricClientCommandSource source, List<GdragOpportunity> opportunities, int count) {
        for (int i = 0; i < count; i++) {
            source.sendFeedback(line(i + 1, opportunities.get(i)));
        }
    }

    private static Component line(int rank, GdragOpportunity opportunity) {
        GdragListing listing = opportunity.listing();
        ChatFormatting profitColor = opportunity.profit() > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY;

        MutableComponent line = Component.literal(" #" + rank + "  ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(levelLabel(listing)).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("  " + coins(listing.price())).withStyle(ChatFormatting.WHITE))
                .append(Component.literal("  理论 " + coins(opportunity.expectedPrice())).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("  " + signedCoins(opportunity.profit())).withStyle(profitColor))
                .append(Component.literal(" (" + signedPercent(opportunity.roi()) + ")").withStyle(ChatFormatting.GRAY));

        if (listing.candyUsed() > 0) {
            line.append(Component.literal("  (" + listing.candyUsed() + "/10)").withStyle(ChatFormatting.YELLOW));
        }

        line.append(auctionButton(listing.uuid()))
                .append(webButton(listing.uuid()))
                .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(tooltip(opportunity))));

        return line;
    }

    private static Component auctionButton(String uuid) {
        return Component.literal(" [拍卖]").withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.RunCommand("/viewauction " + uuid))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("运行 /viewauction " + uuid))));
    }

    private static Component webButton(String uuid) {
        return Component.literal(" [Cofl]").withStyle(style -> style
                .withColor(ChatFormatting.BLUE)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create("https://sky.coflnet.com/auction/" + uuid)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("打开 sky.coflnet.com 拍卖页"))));
    }

    private static Component tooltip(GdragOpportunity opportunity) {
        GdragListing listing = opportunity.listing();
        long levelStart = PetXpCurve.expAtLevel(listing.level());
        long overflow = Math.max(0L, listing.exp() - levelStart);

        MutableComponent tooltip = Component.literal(listing.itemName()).withStyle(ChatFormatting.GOLD)
                .append(Component.literal("\n经验: " + String.format(Locale.ROOT, "%,d", listing.exp())
                        + " XP" + (listing.expKnown() ? "" : "（估算）")).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n等级进度: " + levelLabel(listing)
                        + "（本级溢出 " + compact(overflow) + " XP）").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n卖家: " + (listing.playerName().isEmpty() ? "未知" : listing.playerName()))
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n价格: " + String.format(Locale.ROOT, "%,d", listing.price()) + " coins")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n理论价: " + String.format(Locale.ROOT, "%,d", opportunity.expectedPrice())
                        + " coins").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n利润: " + signedCoins(opportunity.profit())
                        + " (" + signedPercent(opportunity.roi()) + ")").withStyle(ChatFormatting.GRAY));

        String remaining = remaining(listing.endEpochMillis());
        if (!remaining.isEmpty()) {
            tooltip.append(Component.literal("\n剩余: " + remaining).withStyle(ChatFormatting.GRAY));
        }
        if (listing.candyUsed() > 0) {
            tooltip.append(Component.literal("\nPet Candy: " + listing.candyUsed() + "/10").withStyle(ChatFormatting.YELLOW));
        }

        tooltip.append(Component.literal("\nUUID: " + listing.uuid()).withStyle(ChatFormatting.DARK_GRAY));
        return tooltip;
    }

    /** 等级 + 溢出进度；经验未知时退回物品名里的等级。 */
    private static String levelLabel(GdragListing listing) {
        return listing.expKnown() ? PetXpCurve.levelLabel(listing.exp()) : "Lvl " + listing.level();
    }

    public static String coins(long value) {
        long abs = Math.abs(value);
        if (abs >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.2fB", value / 1_000_000_000.0);
        }
        if (abs >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        }
        if (abs >= 1_000L) {
            return String.format(Locale.ROOT, "%.1fK", value / 1_000.0);
        }
        return Long.toString(value);
    }

    /** 经验值用的紧凑格式（与金币同样的量级后缀）。 */
    private static String compact(long value) {
        return coins(value);
    }

    private static String signedCoins(long value) {
        return (value > 0 ? "+" : "") + coins(value);
    }

    private static String signedPercent(double ratio) {
        return String.format(Locale.ROOT, "%+.1f%%", ratio * 100.0);
    }

    private static String remaining(long endEpochMillis) {
        if (endEpochMillis <= 0) {
            return "";
        }
        long millis = endEpochMillis - System.currentTimeMillis();
        if (millis <= 0) {
            return "已结束";
        }
        long minutes = millis / 60_000L;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h" + (minutes % 60) + "m";
        }
        return (hours / 24) + "d" + (hours % 24) + "h";
    }
}
