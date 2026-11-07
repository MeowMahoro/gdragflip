package net.gdragflip.analysis;

import net.gdragflip.cofl.CoflAuction;
import net.gdragflip.cofl.ExpCache;
import net.gdragflip.cofl.PetDetail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 金龙翻新（买 100+ 自养到满级再卖）的定价分析。
 *
 * <p>定价坐标是<b>累计经验</b>而不是等级：等级相同但溢出经验更多的挂单，实际进度更高，</p>
 * <p>理论价也更高。算法：</p>
 * <ol>
 *   <li>A = 所有 Lvl ≥ 100 传奇金龙 BIN 中价格最低的一条（记录其经验 expA）；</li>
 *   <li>B = 所有 Lvl 200 中价格最低的一条，<b>按刚好 200 级处理</b>（经验取满级 214,023,230，忽略溢出）；</li>
 *   <li>每经验单价 pricePerExp = (B价 - A价) / (满级经验 - expA)；</li>
 *   <li>任意挂单理论价 = A价 + (该单经验 - expA) × pricePerExp；</li>
 *   <li>理论价 - 实际价 = 利润（等于"买下养到 200 再按 B 价卖出"的毛利）。</li>
 * </ol>
 */
public final class GdragAnalyzer {
    private static final Pattern LEVEL_PATTERN = Pattern.compile("^\\[Lvl (\\d+)]\\s*(.*)$");
    private static final String LEGENDARY = "LEGENDARY";

    private GdragAnalyzer() {
    }

    /**
     * 把 Cofl 挂单转换成可参与计算的条目（过滤 BIN / 稀有度 / 等级，并用缓存补经验）。
     */
    public static List<GdragListing> prepare(List<CoflAuction> auctions) {
        List<GdragListing> listings = new ArrayList<>();
        for (CoflAuction auction : auctions) {
            GdragListing listing = toListing(auction);
            if (listing != null) {
                listings.add(listing);
            }
        }
        return listings;
    }

    public static GdragAnalysis analyze(List<CoflAuction> auctions) {
        List<GdragListing> listings = prepare(auctions);
        int level200Count = (int) listings.stream()
                .filter(listing -> listing.level() == PetXpCurve.MAX_LEVEL)
                .count();
        if (listings.isEmpty()) {
            return new GdragAnalysis(auctions.size(), 0, 0, null, null, 0.0, List.of());
        }

        // 同价时优先高等级（更划算）。
        Comparator<GdragListing> cheapest = Comparator.comparingLong(GdragListing::price)
                .thenComparing(Comparator.comparingInt(GdragListing::level).reversed());

        GdragListing base = listings.stream().min(cheapest).orElseThrow();
        GdragListing target200 = listings.stream()
                .filter(listing -> listing.level() == PetXpCurve.MAX_LEVEL)
                .min(cheapest)
                .orElse(null);

        long baseExp = PetXpCurve.capped(base.exp());
        double pricePerExp = 0.0;
        // B 一律按刚好 200 级（满级经验）处理，200 级溢出不计入收益。
        if (target200 != null && PetXpCurve.CAP - baseExp > 0) {
            pricePerExp = (target200.price() - base.price()) / (double) (PetXpCurve.CAP - baseExp);
        }

        List<GdragOpportunity> ranked = List.of();
        if (target200 != null && Double.isFinite(pricePerExp) && pricePerExp > 0) {
            final double unit = pricePerExp;
            final long anchorPrice = base.price();
            final long anchorExp = baseExp;

            ranked = listings.stream()
                    .map(listing -> {
                        long expected = expectedPrice(anchorPrice, anchorExp, unit, listing.exp());
                        long profit = expected - listing.price();
                        double roi = listing.price() > 0 ? profit / (double) listing.price() : 0.0;
                        return new GdragOpportunity(listing, expected, profit, roi);
                    })
                    .sorted(Comparator.comparingLong(GdragOpportunity::profit).reversed()
                            .thenComparing(Comparator.comparingDouble(GdragOpportunity::roi).reversed()))
                    .toList();
        }

        return new GdragAnalysis(auctions.size(), listings.size(), level200Count,
                base, target200, pricePerExp, ranked);
    }

    /**
     * 评估一个假设挂单（等级 + 价格）在当前市场中的表现与排名。
     * 等级按该级起点经验计算（不提供溢出经验输入）。数据不足时返回 null。
     */
    public static GdragCustom evaluate(GdragAnalysis analysis, int level, long price) {
        if (analysis == null || !analysis.hasPricing() || price <= 0) {
            return null;
        }

        int clampedLevel = Math.max(PetXpCurve.MIN_LEVEL, Math.min(PetXpCurve.MAX_LEVEL, level));
        long exp = PetXpCurve.expAtLevel(clampedLevel);
        long expected = expectedPrice(analysis, exp);
        long profit = expected - price;
        double roi = profit / (double) price;

        int rank = 1;
        for (GdragOpportunity opportunity : analysis.ranked()) {
            if (opportunity.profit() > profit) {
                rank++;
            }
        }
        return new GdragCustom(clampedLevel, exp, price, expected, profit, roi, rank);
    }

    /** 按当前扫描的每经验单价推算某个经验值对应的理论价。 */
    public static long expectedPrice(GdragAnalysis analysis, long exp) {
        return expectedPrice(analysis.base().price(), PetXpCurve.capped(analysis.base().exp()),
                analysis.pricePerExp(), exp);
    }

    private static long expectedPrice(long anchorPrice, long anchorExp, double pricePerExp, long exp) {
        return Math.round(anchorPrice + (PetXpCurve.capped(exp) - anchorExp) * pricePerExp);
    }

    private static GdragListing toListing(CoflAuction auction) {
        if (!auction.bin() || auction.price() <= 0) {
            return null;
        }
        if (!LEGENDARY.equalsIgnoreCase(auction.tier())) {
            return null;
        }

        String itemName = auction.itemName() == null ? "" : auction.itemName().trim();
        Matcher matcher = LEVEL_PATTERN.matcher(itemName);
        if (!matcher.matches()) {
            return null;
        }

        int level;
        try {
            level = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (level < PetXpCurve.MIN_LEVEL) {
            return null;
        }

        long levelStart = PetXpCurve.expAtLevel(level);
        PetDetail detail = ExpCache.get(auction.uuid());
        boolean expKnown = detail != null;
        long exp = levelStart;
        int candyUsed = -1;
        if (detail != null) {
            // 经验与等级来自同一份数据，理论上不会矛盾；异常时以等级起点兜底。
            exp = Math.max(detail.exp(), levelStart);
            candyUsed = detail.candyUsed();
        }

        boolean egg = matcher.group(2).toLowerCase(Locale.ROOT).contains("egg");
        return new GdragListing(auction.uuid(), level, exp, expKnown, candyUsed,
                auction.price(), egg, itemName, auction.playerName(), auction.endEpochMillis());
    }
}
