package net.gdragflip.leveling;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取 Exp Sharing 菜单里的分享槽。
 *
 * <p>槽位有三格（30/31/32），但只有 Diana 的 Sharing is Caring 才会把 #2/#3 解锁；
 * 未生效的槽位物品 lore 会写 {@code DISABLED}，这类槽位直接忽略、也不计入槽位数。</p>
 *
 * <p>生效槽位的 lore 会写明真实分享比例，例如
 * {@code This pet is currently gaining 27% of your equipped pet's Exp}，
 * 直接读这个百分比比用配置猜测准确。</p>
 */
public final class ExpShareTracker {
    private static final Pattern RATE_PATTERN = Pattern.compile("([\\d.]+)\\s*%\\s+of");
    private static final int[] SLOTS = {30, 31, 32};
    private static final String MENU_TITLE = "Exp Sharing";

    private static volatile List<SharePet> pets = List.of();
    /** 已开启（非 DISABLED）的槽位数量；0 = 还没读过界面。 */
    private static volatile int observedSlots;
    private static int cooldown;

    private ExpShareTracker() {
    }

    /**
     * @param name        宠物名（已去掉 [Lvl N] 前缀）
     * @param level       宠物等级（名字/lore/经验反算）
     * @param exp         物品 NBT 里的累计经验，未知为 -1
     * @param type        宠物内部类型（如 GOLDEN_DRAGON），未知为空串
     * @param ratePercent 该槽位的分享比例（%），未知为 -1
     */
    public record SharePet(String name, int level, long exp, String type, double ratePercent) {
    }

    public static List<SharePet> pets() {
        return pets;
    }

    /** 已开启的槽位数量；0 表示还没打开过 Exp Sharing 界面。 */
    public static int observedSlots() {
        return observedSlots;
    }

    public static boolean hasData() {
        return observedSlots > 0;
    }

    public static void clear() {
        pets = List.of();
        observedSlots = 0;
    }

    public static void tick(Minecraft client) {
        if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (!screen.getTitle().getString().trim().equals(MENU_TITLE)) {
            return;
        }
        if (cooldown-- > 0) {
            return;
        }
        cooldown = 20;

        AbstractContainerMenu menu = screen.getMenu();
        List<SharePet> read = new ArrayList<>();
        int enabled = 0;

        for (int slot : SLOTS) {
            if (slot >= menu.slots.size()) {
                continue;
            }
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack.isEmpty()) {
                continue;
            }

            String lore = PetNbt.loreText(stack).toLowerCase(Locale.ROOT);
            // DISABLED（Diana 未触发）与 Locked 的槽位都不展示。
            if (lore.contains("disabled") || lore.contains("locked")) {
                continue;
            }
            enabled++;

            String hoverName = stack.getHoverName().getString().replaceAll("§.", "");
            if (hoverName.contains("No pet")) {
                continue;
            }

            JsonObject info = PetNbt.petInfo(stack);
            long exp = info == null ? -1L : PetNbt.exp(info);
            String type = info == null ? "" : PetNbt.type(info);
            int level = PetNbt.level(stack, info);
            double rate = parseRate(lore);

            read.add(new SharePet(PetNbt.name(stack), level, exp, type, rate));
        }

        pets = List.copyOf(read);
        observedSlots = enabled;
    }

    /** 从 lore "… ganing 27% of your equipped pet's Exp" 里取分享比例。 */
    private static double parseRate(String lore) {
        Matcher matcher = RATE_PATTERN.matcher(lore);
        if (!matcher.find()) {
            return -1.0;
        }
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return -1.0;
        }
    }
}
