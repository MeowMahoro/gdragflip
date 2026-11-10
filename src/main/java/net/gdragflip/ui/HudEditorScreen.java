package net.gdragflip.ui;

import net.gdragflip.config.GdragConfig;
import net.gdragflip.hud.LevelingHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * HUD 位置编辑：按住 HUD 拖动，松手自动保存。
 *
 * <p>鼠标状态用 {@code MouseHandler} 轮询（{@link #tick()}），不依赖 Screen 的点击事件 ——
 * 26.3 里事件分发行为有变化，轮询更稳。</p>
 */
public final class HudEditorScreen extends Screen {
    private static long lastLogAt;

    private boolean dragging;
    private boolean wasDown;
    private double grabOffsetX;
    private double grabOffsetY;

    public HudEditorScreen() {
        super(Component.literal("拖动 HUD 调整位置（ESC 保存退出）"));
    }

    @Override
    public void tick() {
        // 兜底：Screen#tick 在部分版本不保证被调用，真正的轮询由 LevelingAssistant 每 tick 驱动。
        Minecraft client = this.minecraft;
        if (client != null) {
            poll(client);
        }
    }

    /** 由客户端 tick 调用，直接读取鼠标状态（不依赖 Screen 的事件分发）。 */
    public void poll(Minecraft client) {
        double mouseX = client.mouseHandler.getScaledXPos(client.getWindow());
        double mouseY = client.mouseHandler.getScaledYPos(client.getWindow());
        boolean down = client.mouseHandler.isLeftPressed();
        GdragConfig config = GdragConfig.get();

        int width = LevelingHud.width(client);
        int height = LevelingHud.height(client);

        if (down && !wasDown) {
            // 按下瞬间：点在 HUD 范围内就开始拖。
            if (inside(mouseX, mouseY, config, width, height)) {
                dragging = true;
                grabOffsetX = mouseX - config.hudX;
                grabOffsetY = mouseY - config.hudY;
            }
        } else if (down && dragging) {
            config.hudX = clamp((int) Math.round(mouseX - grabOffsetX), 0, Math.max(0, this.width - width));
            config.hudY = clamp((int) Math.round(mouseY - grabOffsetY), 0, Math.max(0, this.height - height));
        } else if (!down && dragging) {
            dragging = false;
            GdragConfig.save();
        }

        wasDown = down;

        if (GdragConfig.get().debugVerbose) {
            long now = System.currentTimeMillis();
            if (now - lastLogAt > 1_000L) {
                lastLogAt = now;
                net.gdragflip.GdragFlipMod.LOGGER.info(
                        "[hudedit] mouse=({}, {}) down={} dragging={} box=({}, {}) {}x{}",
                        Math.round(mouseX), Math.round(mouseY), down, dragging,
                        config.hudX, config.hudY, width, height);
            }
        }
    }

    private static boolean inside(double mouseX, double mouseY, GdragConfig config, int width, int height) {
        return mouseX >= config.hudX && mouseX <= config.hudX + width
                && mouseY >= config.hudY && mouseY <= config.hudY + height;
    }

    /** 方向键微调（拖动失效时的后备手段）。 */
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        // GLFW_MOD_SHIFT = 0x1；用事件自带修饰键，跨版本通用（26.2/26.3 的 InputConstants 签名不同）。
        int step = (event.modifiers() & 0x1) != 0 ? 10 : 1;
        GdragConfig config = GdragConfig.get();
        switch (event.key()) {
            case com.mojang.blaze3d.platform.InputConstants.KEY_LEFT -> config.hudX = Math.max(0, config.hudX - step);
            case com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT -> config.hudX += step;
            case com.mojang.blaze3d.platform.InputConstants.KEY_UP -> config.hudY = Math.max(0, config.hudY - step);
            case com.mojang.blaze3d.platform.InputConstants.KEY_DOWN -> config.hudY += step;
            default -> {
                return super.keyPressed(event);
            }
        }
        GdragConfig.save();
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        GdragConfig config = GdragConfig.get();
        graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.literal("按住 HUD 拖动；方向键微调（Shift 加速）"),
                this.width / 2, 20, 0xFFAAAAAA);

        LevelingHud.render(graphics, this.minecraft, config.hudX, config.hudY);

        if (dragging) {
            int w = LevelingHud.width(this.minecraft);
            int h = LevelingHud.height(this.minecraft);
            graphics.outline(config.hudX - 2, config.hudY - 2, w + 4, h + 4, 0x80FFFF00);
        }
    }

    @Override
    public void onClose() {
        GdragConfig.save();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
