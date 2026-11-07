package net.gdragflip.leveling;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.gdragflip.GdragFlipMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取 Exp Sharing 菜单里的分享槽宠物。
 *
 * <p>与 Skysoft 的做法一致：打开 {@code /pets → Exp Sharing} 时读取槽位 30/31/32 的物品 NBT，
 * 没有实时接口。关闭界面后沿用上次读取结果。</p>
 */
public final class ExpShareTracker {
    private static final Pattern LEVEL_PATTERN = Pattern.compile("\\[Lvl ([\\d,]+)]");
    private static final Gson GSON = new Gson();
    private static final int[] SLOTS = {30, 31, 32};
    private static final String MENU_TITLE = "Exp Sharing";

    private static volatile List<SharePet> pets = List.of();
    private static int cooldown;

    private ExpShareTracker() {
    }

    /**
     * @param name  宠物名（已去掉 [Lvl N] 前缀）
     * @param level 物品名里显示的等级
     * @param exp   物品 NBT 里的累计经验（未知为 -1）
     * @param type  宠物内部类型（如 GOLDEN_DRAGON），未知为空串
     */
    public record SharePet(String name, int level, long exp, String type) {
    }

    public static List<SharePet> pets() {
        return pets;
    }

    public static void clear() {
        pets = List.of();
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
        for (int slot : SLOTS) {
            if (slot >= menu.slots.size()) {
                continue;
            }
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String hoverName = stack.getHoverName().getString();
            if (hoverName.contains("No pet")) {
                continue;
            }
            JsonObject info = petInfo(stack);
            long exp = -1L;
            String type = "";
            if (info != null) {
                if (info.has("exp")) {
                    exp = (long) info.get("exp").getAsDouble();
                }
                if (info.has("type")) {
                    type = info.get("type").getAsString();
                }
            }
            read.add(new SharePet(cleanName(hoverName), levelOf(hoverName), exp, type));
        }
        pets = List.copyOf(read);
    }

    private static String cleanName(String hoverName) {
        return hoverName.replaceAll("\\[Lvl [\\d,]+]\\s*", "").replace("✦", "").trim();
    }

    private static int levelOf(String hoverName) {
        Matcher matcher = LEVEL_PATTERN.matcher(hoverName);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static JsonObject petInfo(ItemStack stack) {
        CompoundTag tag = stack.get(DataComponents.CUSTOM_DATA) == null
                ? null
                : stack.get(DataComponents.CUSTOM_DATA).copyTag();
        if (tag == null) {
            return null;
        }

        String json = readString(tag, "petInfo");
        if (json.isEmpty()) {
            Optional<CompoundTag> extra = tag.getCompound("ExtraAttributes");
            if (extra.isPresent()) {
                json = readString(extra.get(), "petInfo");
            }
        }
        if (json.isEmpty()) {
            return null;
        }
        try {
            return GSON.fromJson(json, JsonObject.class);
        } catch (RuntimeException e) {
            GdragFlipMod.LOGGER.debug("petInfo 解析失败", e);
            return null;
        }
    }

    private static String readString(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return "";
        }
        try {
            return tag.getString(key).orElse("");
        } catch (RuntimeException e) {
            return "";
        }
    }
}
