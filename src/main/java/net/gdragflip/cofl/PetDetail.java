package net.gdragflip.cofl;

/**
 * 拍卖详情里与宠物成长相关字段。
 *
 * @param exp       累计宠物经验（{@code flatNbt.exp}）
 * @param candyUsed 已使用的宠物糖果数量，未知为 -1
 */
public record PetDetail(long exp, int candyUsed) {
}
