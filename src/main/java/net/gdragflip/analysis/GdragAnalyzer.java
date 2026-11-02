package net.gdragflip.analysis;

import net.gdragflip.cofl.CoflAuction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 金龙翻新（买 100+ 自养到 200 再卖）的定价分析。
 *
 * <p>算法（与需求一致）：</p>
 * <ol>
 *   <li>A = 所有 Lvl ≥ 100 传奇金龙 BIN 中价格最低的一条（记录其等级与价格）；</li>
 *   <li>B = 所有 Lvl 200 中价格最低的一条；</li>
 *   <li>每级均价 = (B - A) / (200 - A.level)；</li>
 *   <li>任意挂单理论价 = A.price + (level - A.level) × 每级均价；</li>
 *   <li>理论价 - 实际价 = 利润（正数即"低于平均价"，等价于线性近似下养到 200 的毛利）。</li>
 * </ol>
 */
public final class GdragAnalyzer {
    public static final int MIN_LEVEL = 100;
    public static final int MAX_LEVEL = 200;

    private static final Pattern LEVEL_PATTERN = Pattern.compile("^\\[Lvl (\\d+)]\\s*(.*)$");

    private GdragAnalyzer() {
    }

    public static GdragAnalysis analyze(List<CoflAuction> auctions) {
        List<GdragListing> listings = new ArrayList<>();
        for (CoflAuction auction : auctions) {
            GdragListing listing = toListing(auction);
            if (listing != null) {
                listings.add(listing);
            }
        }

        int level200Count = (int) listings.stream().filter(listing -> listing.level() == MAX_LEVEL).count();
        if (listings.isEmpty()) {
            return new GdragAnalysis(auctions.size(), 0, 0, null, null, 0.0, List.of());
        }

        // 同价时优先高等级（更划算）。
        Comparator<GdragListing> cheapest = Comparator.comparingLong(GdragListing::price)
                .thenComparing(Comparator.comparingInt(GdragListing::level).reversed());

        GdragListing base = listings.stream().min(cheapest).orElseThrow();
        GdragListing target200 = listings.stream()
                .filter(listing -> listing.level() == MAX_LEVEL)
                .min(cheapest)
                .orElse(null);

        double avgPerLevel = 0.0;
        if (target200 != null && target200.level() > base.level()) {
            avgPerLevel = (target200.price() - base.price()) / (double) (target200.level() - base.level());
        }

        List<GdragOpportunity> ranked = List.of();
        if (target200 != null && Double.isFinite(avgPerLevel) && avgPerLevel > 0) {
            final double avg = avgPerLevel;
            final long basePrice = base.price();
            final int baseLevel = base.level();

            ranked = listings.stream()
                    .map(listing -> {
                        long expected = Math.round(basePrice + (listing.level() - baseLevel) * avg);
                        long profit = expected - listing.price();
                        double roi = listing.price() > 0 ? profit / (double) listing.price() : 0.0;
                        return new GdragOpportunity(listing, expected, profit, roi);
                    })
                    .sorted(Comparator.comparingLong(GdragOpportunity::profit).reversed()
                            .thenComparing(Comparator.comparingDouble(GdragOpportunity::roi).reversed()))
                    .toList();
        }

        return new GdragAnalysis(auctions.size(), listings.size(), level200Count,
                base, target200, avgPerLevel, ranked);
    }

    private static GdragListing toListing(CoflAuction auction) {
        if (!auction.bin() || auction.price() <= 0) {
            return null;
        }
        if (!"LEGENDARY".equalsIgnoreCase(auction.tier())) {
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
        if (level < MIN_LEVEL) {
            return null;
        }

        boolean egg = matcher.group(2).toLowerCase(Locale.ROOT).contains("egg");
        return new GdragListing(auction.uuid(), level, auction.price(), egg,
                itemName, auction.playerName(), auction.endEpochMillis());
    }
}
