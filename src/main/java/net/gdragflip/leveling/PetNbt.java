package net.gdragflip.leveling;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.gdragflip.analysis.PetXpCurve;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从物品 NBT 里取 Hypixel 的 petInfo（兼容直接挂在根上或 ExtraAttributes 下）。 */
public final class PetNbt {
    private static final Gson GSON = new Gson();
    private static final Pattern LEVEL_PATTERN = Pattern.compile("Lvl\\s*([\\d,]+)");

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

    /** 物品 lore 的纯文本（换行拼接）。 */
    public static String loreText(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Component line : lore.lines()) {
            builder.append(line.getString()).append('\n');
        }
        return builder.toString();
    }

    /** 宠物等级：优先物品名 [Lvl N]，其次 lore，最后用金龙经验反算。 */
    public static int level(ItemStack stack, JsonObject info) {
        int fromName = levelFromText(stack.getHoverName().getString());
        if (fromName > 0) {
            return fromName;
        }
        int fromLore = levelFromText(loreText(stack));
        if (fromLore > 0) {
            return fromLore;
        }
        if (info != null && "GOLDEN_DRAGON".equalsIgnoreCase(type(info))) {
            long exp = exp(info);
            if (exp > 0) {
                return PetXpCurve.levelOf(exp);
            }
        }
        return 0;
    }

    private static int levelFromText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Matcher matcher = LEVEL_PATTERN.matcher(text);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
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

    /** 宠物 UUID：优先 uniqueId，其次 uuid；都没有返回空串。 */
    public static String id(JsonObject info) {
        if (info == null) {
            return "";
        }
        for (String key : new String[]{"uniqueId", "uuid"}) {
            if (info.has(key)) {
                try {
                    String value = info.get(key).getAsString();
                    if (!value.isEmpty()) {
                        return value;
                    }
                } catch (RuntimeException ignored) {
                    // 继续尝试下一个字段
                }
            }
        }
        return "";
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
