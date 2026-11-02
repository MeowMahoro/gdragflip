package net.gdragflip.analysis;

/**
 * 一条可参与计算的金龙挂单（Lvl ≥ 100、LEGENDARY、BIN）。
 *
 * @param uuid          拍卖 UUID
 * @param level         宠物等级（从 itemName 的 [Lvl N] 解析）
 * @param price         一口价
 * @param egg           true = 未破壳（1~99 级），正常不会出现在结果里
 * @param itemName      原始物品名
 * @param playerName    卖家
 * @param endEpochMillis 结束时间，-1 表示未知
 */
public record GdragListing(
        String uuid,
        int level,
        long price,
        boolean egg,
        String itemName,
        String playerName,
        long endEpochMillis
) {
}
