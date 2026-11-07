package net.gdragflip.leveling;

import com.google.gson.JsonObject;
import net.gdragflip.GdragFlipMod;
import net.gdragflip.chat.ChatReport;
import net.gdragflip.config.GdragConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 打开 {@code /pets} 菜单时读取「当前出战」金龙的 UUID 与真实经验。
 *
 * <p>两个用途：</p>
 * <ol>
 *   <li>会话开始时绑定宠物 UUID（{@code /gdrag session start} 后打开菜单即完成绑定）；</li>
 *   <li>之后每次打开菜单都用真实经验校准地牢内的估算值。</li>
 * </ol>
 */
public final class PetMenuReader {
    private static final String MENU_TITLE = "Pets";
    private static int cooldown;
    private static long lastMismatchAt;

    private PetMenuReader() {
    }

    public static void tick(Minecraft client) {
        if (cooldown-- > 0) {
            return;
        }
        if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (!screen.getTitle().getString().contains(MENU_TITLE)) {
            return;
        }
        cooldown = 20;

        AbstractContainerMenu menu = screen.getMenu();
        Pet found = null;
        Pet fallback = null;

        for (int slot = 0; slot < menu.slots.size(); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            JsonObject info = PetNbt.petInfo(stack);
            if (info == null || !"GOLDEN_DRAGON".equalsIgnoreCase(PetNbt.type(info))) {
                continue;
            }
            long exp = PetNbt.exp(info);
            if (exp <= 0) {
                continue;
            }
            Pet pet = new Pet(petId(info), PetNbt.name(stack), exp);
            if (GdragConfig.get().debugVerbose || LevelingSession.pendingBind()) {
                GdragFlipMod.LOGGER.info("[petmenu] slot={} name='{}' active={} id={} exp={}",
                        slot, pet.name(), isActive(info), pet.id(), exp);
            }
            if (isActive(info)) {
                found = pet;
                break;
            }
            if (fallback == null) {
                fallback = pet;
            }
        }

        Pet target = found != null ? found : fallback;
        if (target == null) {
            return;
        }

        String result = LevelingSession.onActivePetRead(
                target.id(), target.name(), target.exp(), found != null);
        if (client.player == null) {
            return;
        }
        switch (result) {
            case "bound" -> {
                client.player.sendSystemMessage(ChatReport.info(
                        "会话已开始，已绑定：" + target.name(), ChatFormatting.GREEN));
                client.player.sendSystemMessage(ChatReport.info(
                        "之后请保持这只宠物出战；切换别的宠物会暂停统计", ChatFormatting.GRAY));
            }
            case "bound-weak" -> {
                client.player.sendSystemMessage(ChatReport.info(
                        "已绑定：" + target.name() + "（未识别到 active 标记，仅按名字校验）",
                        ChatFormatting.YELLOW));
            }
            case "mismatch" -> {
                long now = System.currentTimeMillis();
                if (now - lastMismatchAt > 10_000L) {
                    lastMismatchAt = now;
                    client.player.sendSystemMessage(ChatReport.info(
                            "当前宠物与绑定的 " + LevelingSession.boundPetName() + " 不一致，已暂停统计",
                            ChatFormatting.RED));
                }
            }
            default -> {
                // calibrated / ignored：静默
            }
        }
    }

    private static boolean isActive(JsonObject info) {
        if (!info.has("active")) {
            return false;
        }
        try {
            return info.get("active").getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** petInfo 的稳定身份：优先 uniqueId，其次 uuid。 */
    private static String petId(JsonObject info) {
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

    private record Pet(String id, String name, long exp) {
    }
}
