package net.gdragflip.analysis;

/**
 * 用户输入的假设挂单（等级 + 价格）在当前市场中的评估结果。
 *
 * @param level         宠物等级
 * @param price         假设价格
 * @param expectedPrice 按当前每级均价推算的理论价
 * @param profit        理论价 - 假设价格
 * @param roi           profit / price
 * @param rank          在全部 Lvl100+ 挂单中的利润排名（1 起）
 */
public record GdragCustom(
        int level,
        long price,
        long expectedPrice,
        long profit,
        double roi,
        int rank
) {
}
