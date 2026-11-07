package net.gdragflip.ui;

import net.gdragflip.config.GdragConfig;
import net.gdragflip.hud.LevelingHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * HUD 位置编辑：直接拖动 HUD 本体，松手即保存坐标。
 *
 * <p>/gdrag settings → 调整 HUD 位置。</p>
 */
public final class HudEditorScreen extends Screen {
    private boolean dragging;
    private double grabOffsetX;
    private double grabOffsetY;

    public HudEditorScreen() {
        super(Component.literal("拖动 HUD 调整位置（ESC 保存退出）"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        GdragConfig config = GdragConfig.get();
        graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.literal("按住 HUD 拖动；松手自动保存"),
                this.width / 2, 20, 0xFFAAAAAA);

        LevelingHud.render(graphics, this.minecraft, config.hudX, config.hudY);

        if (dragging) {
            int w = LevelingHud.width(this.minecraft);
            int h = LevelingHud.height(this.minecraft);
            graphics.outline(config.hudX - 2, config.hudY - 2, w + 4, h + 4, 0x80FFFF00);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            GdragConfig config = GdragConfig.get();
            int w = LevelingHud.width(this.minecraft);
            int h = LevelingHud.height(this.minecraft);
            if (event.x() >= config.hudX && event.x() <= config.hudX + w
                    && event.y() >= config.hudY && event.y() <= config.hudY + h) {
                dragging = true;
                grabOffsetX = event.x() - config.hudX;
                grabOffsetY = event.y() - config.hudY;
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging) {
            GdragConfig config = GdragConfig.get();
            int w = LevelingHud.width(this.minecraft);
            int h = LevelingHud.height(this.minecraft);
            config.hudX = clamp((int) Math.round(event.x() - grabOffsetX), 0, Math.max(0, this.width - w));
            config.hudY = clamp((int) Math.round(event.y() - grabOffsetY), 0, Math.max(0, this.height - h));
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            GdragConfig.save();
            return true;
        }
        return super.mouseReleased(event);
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
