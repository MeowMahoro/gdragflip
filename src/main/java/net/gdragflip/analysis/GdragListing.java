package net.gdragflip.analysis;

/**
 * 一条可参与计算的金龙挂单（Lvl ≥ 100、LEGENDARY、BIN）。
 *
 * @param uuid          拍卖 UUID
 * @param level         宠物等级（从 itemName 的 [Lvl N] 解析）
 * @param exp           累计宠物经验（来自详情接口；取不到时为该等级起点经验）
 * @param expKnown      exp 是否为真实值（false = 详情查询失败，按等级起点估算）
 * @param candyUsed     已使用宠物糖果数量，-1 表示未知
 * @param price         一口价
 * @param egg           true = 未破壳（1~99 级），正常不会出现在结果里
 * @param itemName      原始物品名
 * @param playerName    卖家
 * @param endEpochMillis 结束时间，-1 表示未知
 */
public record GdragListing(
        String uuid,
        int level,
        long exp,
        boolean expKnown,
        int candyUsed,
        long price,
        boolean egg,
        String itemName,
        String playerName,
        long endEpochMillis
) {
}
