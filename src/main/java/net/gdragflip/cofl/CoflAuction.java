package net.gdragflip.cofl;

/**
 * 一条 Cofl 活跃拍卖（/api/auctions/active/filter 的 items 元素）。
 *
 * @param uuid          拍卖 UUID，用于 /viewauction 与 sky.coflnet.com 链接
 * @param itemName      物品名，宠物形如 "[Lvl 131] Golden Dragon"
 * @param tier          稀有度，金龙只取 LEGENDARY
 * @param bin           是否一口价
 * @param price         一口价（startingBid，BIN 时即售价）
 * @param playerName    卖家
 * @param endEpochMillis 结束时间（解析失败为 -1）
 */
public record CoflAuction(
        String uuid,
        String itemName,
        String tier,
        boolean bin,
        long price,
        String playerName,
        long endEpochMillis
) {
}
