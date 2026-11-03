package net.gdragflip.chat;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.gdragflip.analysis.GdragAnalysis;
import net.gdragflip.analysis.GdragListing;
import net.gdragflip.analysis.GdragOpportunity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 把分析结果渲染成聊天消息（价格、排名、可点击的查看拍卖按钮）。 */
public final class ChatReport {
    private static final String PREFIX = "[金龙] ";

    private ChatReport() {
    }

    public static Component info(String text, ChatFormatting color) {
        return Component.literal(PREFIX).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(color));
    }

    public static void send(FabricClientCommandSource source, GdragAnalysis analysis, int limit,
                            Map<String, Integer> candyUsed) {
        source.sendFeedback(info("扫描 %d 条挂单，其中 %d 条 Lvl100+（Lvl200 %d 条）".formatted(
                analysis.scanned(), analysis.eligibleCount(), analysis.level200Count()), ChatFormatting.GRAY));

        if (analysis.eligibleCount() == 0) {
            source.sendFeedback(info("没有找到 Lvl100 以上的传奇金龙 BIN 挂单", ChatFormatting.RED));
            return;
        }

        if (!analysis.hasPricing()) {
            source.sendFeedback(info("无法计算每级均价：缺少 Lvl200 挂单或价格倒挂", ChatFormatting.RED));
            GdragListing base = analysis.base();
            if (base != null) {
                source.sendFeedback(info("当前 Lvl100+ 最低价：Lvl%d · %s".formatted(
                        base.level(), coins(base.price())), ChatFormatting.YELLOW));
            }
            return;
        }

        source.sendFeedback(info("基准：Lvl%d 最低 %s ｜ Lvl200 最低 %s ｜ 每级 %s".formatted(
                analysis.base().level(),
                coins(analysis.base().price()),
                coins(analysis.target200().price()),
                coins(Math.round(analysis.avgPerLevel()))), ChatFormatting.GRAY));

        List<GdragOpportunity> below = analysis.belowExpected();
        if (below.isEmpty()) {
            int fallback = Math.min(3, analysis.ranked().size());
            source.sendFeedback(info("没有低于理论价的挂单；下面是最接近的 %d 条：".formatted(fallback),
                    ChatFormatting.YELLOW));
            print(source, analysis.ranked(), fallback, candyUsed);
        } else {
            source.sendFeedback(info("低于理论价 %d 条（按利润降序）：".formatted(below.size()),
                    ChatFormatting.GREEN));
            print(source, below, Math.min(limit, below.size()), candyUsed);
        }
    }

    /** 与 send 展示的条目保持一致，供命令层提前查询 pet candy 状态。 */
    public static List<GdragOpportunity> selectForDisplay(GdragAnalysis analysis, int limit) {
        if (!analysis.hasPricing() || analysis.ranked().isEmpty()) {
            return List.of();
        }
        List<GdragOpportunity> below = analysis.belowExpected();
        if (!below.isEmpty()) {
            return below.subList(0, Math.min(limit, below.size()));
        }
        return analysis.ranked().subList(0, Math.min(3, analysis.ranked().size()));
    }

    private static void print(FabricClientCommandSource source, List<GdragOpportunity> opportunities,
                              int count, Map<String, Integer> candyUsed) {
        for (int i = 0; i < count; i++) {
            source.sendFeedback(line(i + 1, opportunities.get(i), candyUsed));
        }
    }

    private static Component line(int rank, GdragOpportunity opportunity, Map<String, Integer> candyUsed) {
        GdragListing listing = opportunity.listing();
        ChatFormatting profitColor = opportunity.profit() > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY;

        MutableComponent line = Component.literal(" #" + rank + "  ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal("Lvl " + listing.level()).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("  " + coins(listing.price())).withStyle(ChatFormatting.WHITE))
                .append(Component.literal("  理论 " + coins(opportunity.expectedPrice())).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("  利润 " + signedCoins(opportunity.profit())
                        + " (" + signedPercent(opportunity.roi()) + ")").withStyle(profitColor));

        String remaining = remaining(listing.endEpochMillis());
        if (!remaining.isEmpty()) {
            line.append(Component.literal("  剩 " + remaining).withStyle(ChatFormatting.DARK_GRAY));
        }

        Integer candy = candyUsed.get(listing.uuid());
        if (candy != null && candy > 0) {
            line.append(Component.literal("  " + candy + "/10 pet candy used").withStyle(ChatFormatting.YELLOW));
        }

        line.append(auctionButton(listing.uuid()))
                .append(webButton(listing.uuid()))
                .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(tooltip(listing))));

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

    private static Component tooltip(GdragListing listing) {
        return Component.literal(listing.itemName()).withStyle(ChatFormatting.GOLD)
                .append(Component.literal("\n卖家: " + (listing.playerName().isEmpty() ? "未知" : listing.playerName()))
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n价格: " + String.format(Locale.ROOT, "%,d", listing.price()) + " coins")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\nUUID: " + listing.uuid()).withStyle(ChatFormatting.DARK_GRAY));
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
