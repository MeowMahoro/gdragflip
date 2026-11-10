package net.gdragflip.leveling;

import com.google.gson.JsonObject;
import net.gdragflip.GdragFlipMod;
import net.gdragflip.analysis.PetXpCurve;
import net.gdragflip.chat.ChatReport;
import net.gdragflip.config.GdragConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 读取 {@code /pets} 菜单。
 *
 * <p>两种用途：</p>
 * <ol>
 *   <li><b>绑定</b>：{@code /gdrag session start} 后打开菜单，取 petInfo.active 那只金龙并记录 UUID；</li>
 *   <li><b>校准</b>：绑定后每次打开菜单，只按 <b>UUID</b> 在列表里找同一只宠物（与是否出战无关），
 *       找不到就提示翻页。</li>
 * </ol>
 */
public final class PetMenuReader {
    private static final String MENU_TITLE = "Pets";
    private static final String GOLDEN_DRAGON = "GOLDEN_DRAGON";
    private static int cooldown;
    private static long lastReportAt;
    private static String lastReport = "";
    private static long lastShareHintAt;

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
        List<Pet> pets = new ArrayList<>();
        Pet activeGolden = null;
        Pet activeOther = null;

        for (int slot = 0; slot < menu.slots.size(); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            JsonObject info = PetNbt.petInfo(stack);
            if (info == null) {
                continue;
            }
            long exp = PetNbt.exp(info);
            if (exp <= 0) {
                continue;
            }
            Pet pet = new Pet(PetNbt.id(info), PetNbt.name(stack), exp);
            pets.add(pet);
            boolean golden = GOLDEN_DRAGON.equalsIgnoreCase(PetNbt.type(info));
            boolean active = isActive(info);

            if (GdragConfig.get().debugVerbose) {
                GdragFlipMod.LOGGER.info("[petmenu] slot={} name='{}' type={} active={} id={} exp={}",
                        slot, pet.name(), PetNbt.type(info), active, pet.id(), exp);
            }

            if (active) {
                if (golden && activeGolden == null) {
                    activeGolden = pet;
                } else if (!golden && activeOther == null) {
                    activeOther = pet;
                }
            }
        }

        // 1) 等待绑定：只认 active 的金龙
        if (LevelingSession.pendingBind()) {
            if (activeGolden == null) {
                report(client, "未找到出战金龙（当前："
                        + (activeOther != null ? activeOther.name() : "无") + "），请召唤后重开 /pets",
                        ChatFormatting.RED);
                return;
            }
            LevelingSession.onActivePetRead(activeGolden.id(), activeGolden.name(), activeGolden.exp(), true);
            report(client, "已绑定：" + activeGolden.name() + "（" + shortId(activeGolden.id()) + "）",
                    ChatFormatting.GREEN);
            remindExpShare(client);
            return;
        }

        // 2) 未开始会话
        if (!LevelingSession.bound()) {
            report(client, "未开始会话：/gdrag session start", ChatFormatting.YELLOW);
            return;
        }

        // 3) 已绑定：按 UUID 找同一只（与是否出战无关）
        String boundId = LevelingSession.boundPetId();
        Pet match = null;
        for (Pet pet : pets) {
            if (!boundId.isEmpty() && boundId.equals(pet.id())) {
                match = pet;
                break;
            }
        }

        if (match == null) {
            report(client, "本页未找到绑定宠物 " + shortId(boundId) + "，请翻到下一页再看",
                    ChatFormatting.YELLOW);
            return;
        }

        String result = LevelingSession.readBoundPet(match.exp());
        if ("pseudo".equals(result)) {
            report(client, "地牢内：真实 " + PetXpCurve.levelLabel(match.exp()) + "（仅显示 15 秒）",
                    ChatFormatting.AQUA);
        } else {
            report(client, "校准：" + LevelingSession.lastCalibrationNote(), ChatFormatting.GREEN);
        }
        remindExpShare(client);
    }

    /** 每次读菜单都给一次结果（同一条消息 5 秒内不重复）。 */
    private static void report(Minecraft client, String message, ChatFormatting color) {
        if (client.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (message.equals(lastReport) && now - lastReportAt < 5_000L) {
            return;
        }
        lastReportAt = now;
        lastReport = message;
        client.player.sendSystemMessage(ChatReport.info(message, color));
    }

    /** 打开 /pets 但没顺手打开 Exp Sharing 时提醒一次（HUD 分享槽不会更新）。 */
    private static void remindExpShare(Minecraft client) {
        if (client.player == null || !LevelingSession.bound()) {
            return;
        }
        long since = ExpShareTracker.millisSinceRead();
        if (since >= 0 && since < 120_000L) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastShareHintAt < 120_000L) {
            return;
        }
        lastShareHintAt = now;
        client.player.sendSystemMessage(ChatReport.info(
                "Exp Share 未更新：请同时打开 /pets ➜ Exp Sharing", ChatFormatting.YELLOW));
    }

    private static String shortId(String id) {
        if (id == null || id.isEmpty()) {
            return "?";
        }
        return id.substring(0, Math.min(8, id.length())) + "…";
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

    private record Pet(String id, String name, long exp) {
    }
}
