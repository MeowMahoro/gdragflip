package net.gdragflip.leveling;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/** 从物品 NBT 里取 Hypixel 的 petInfo（兼容直接挂在根上或 ExtraAttributes 下）。 */
public final class PetNbt {
    private static final Gson GSON = new Gson();

    private PetNbt() {
    }

    public static JsonObject petInfo(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
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
            return null;
        }
    }

    public static long exp(JsonObject info) {
        if (info == null || !info.has("exp")) {
            return -1L;
        }
        try {
            return (long) info.get("exp").getAsDouble();
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    /** 物品显示名（去掉颜色码与 [Lvl N] 前缀），用于展示绑定的是哪只宠物。 */
    public static String name(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        String text = stack.getHoverName().getString().replaceAll("§.", "");
        return text.replaceAll("\\[Lvl [\\d,]+]\\s*", "").replace("\u2726", "").trim();
    }

    public static String type(JsonObject info) {
        if (info == null || !info.has("type")) {
            return "";
        }
        return info.get("type").getAsString();
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
