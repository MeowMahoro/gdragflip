package net.gdragflip.analysis;

/**
 * 单个挂单相对"按等级线性推算价"的偏离。
 *
 * @param listing       挂单
 * @param expectedPrice 理论价 = 基准价 + (等级 - 基准等级) × 每级均价
 * @param profit        理论价 - 实际价，正数表示低于理论价（线性近似下等于养到 200 的毛利）
 * @param roi           profit / 实际价
 */
public record GdragOpportunity(
        GdragListing listing,
        long expectedPrice,
        long profit,
        double roi
) {
}
