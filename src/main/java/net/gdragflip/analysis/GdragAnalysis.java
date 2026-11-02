package net.gdragflip.analysis;

import java.util.List;

/**
 * 一次扫描的完整结果。
 *
 * @param scanned        Cofl 返回的金龙挂单总数
 * @param eligibleCount  其中 Lvl ≥ 100 的 LEGENDARY BIN 数量
 * @param level200Count  Lvl 200 的挂单数量
 * @param base           100+ 中最低价挂单（计算基准 A）
 * @param target200      200 级最低价挂单（计算基准 B）
 * @param avgPerLevel    (B - A) / (200 - A.level)
 * @param ranked         全部 100+ 挂单按 profit 降序（含负值）
 */
public record GdragAnalysis(
        int scanned,
        int eligibleCount,
        int level200Count,
        GdragListing base,
        GdragListing target200,
        double avgPerLevel,
        List<GdragOpportunity> ranked
) {
    /** 两个基准齐全且价差方向合理时才能计算理论价。 */
    public boolean hasPricing() {
        return base != null && target200 != null
                && Double.isFinite(avgPerLevel) && avgPerLevel > 0;
    }

    /** 低于理论价的挂单，按利润降序。 */
    public List<GdragOpportunity> belowExpected() {
        return ranked.stream().filter(opportunity -> opportunity.profit() > 0).toList();
    }
}
