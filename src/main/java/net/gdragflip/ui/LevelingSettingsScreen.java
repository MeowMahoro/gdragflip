package net.gdragflip.ui;

import net.gdragflip.chat.ChatReport;
import net.gdragflip.config.GdragConfig;
import net.gdragflip.leveling.ExpShareTracker;
import net.gdragflip.leveling.LevelingSession;
import net.gdragflip.scan.ScanService;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 升级助手设置界面（原版 Screen 自绘，不引入 MoulConfig / Cloth）。
 *
 * <p>/gdrag settings 打开。</p>
 */
public final class LevelingSettingsScreen extends Screen {
    private static final int WIDTH = 220;
    private static final int HEIGHT = 20;

    public LevelingSettingsScreen() {
        super(Component.literal("金龙升级助手 设置"));
    }

    @Override
    protected void init() {
        GdragConfig config = GdragConfig.get();
        int left = this.width / 2 - WIDTH / 2;
        int y = 40;

        addRenderableWidget(Button.builder(hudLabel(config), button -> {
            GdragConfig current = GdragConfig.get();
            current.levelingHud = !current.levelingHud;
            GdragConfig.save();
            button.setMessage(hudLabel(current));
        }).bounds(left, y, WIDTH, HEIGHT).build());

        y += 24;
        addRenderableWidget(Button.builder(shareLabel(config), button -> {
            GdragConfig current = GdragConfig.get();
            current.expShareEnabled = !current.expShareEnabled;
            GdragConfig.save();
            button.setMessage(shareLabel(current));
        }).bounds(left, y, WIDTH, HEIGHT).build());

        y += 24;
        addRenderableWidget(Button.builder(factorLabel(config), button -> {
            GdragConfig current = GdragConfig.get();
            current.expShareFactorPercent = current.expShareFactorPercent >= 48.0
                    ? 0.0
                    : current.expShareFactorPercent + 6.0;
            GdragConfig.save();
            button.setMessage(factorLabel(current));
        }).bounds(left, y, WIDTH, HEIGHT).build());

        y += 24;
        addRenderableWidget(Button.builder(slotsLabel(config), button -> {
            GdragConfig current = GdragConfig.get();
            current.expShareSlots = current.expShareSlots >= 3 ? 1 : current.expShareSlots + 1;
            GdragConfig.save();
            button.setMessage(slotsLabel(current));
        }).bounds(left, y, WIDTH, HEIGHT).build());

        y += 24;
        addRenderableWidget(Button.builder(Component.literal("调整 HUD 位置（拖动）"), button ->
                        this.minecraft.gui.setScreen(new HudEditorScreen()))
                .bounds(left, y, WIDTH, HEIGHT).build());

        y += 32;
        addRenderableWidget(Button.builder(Component.literal("重置累计并解除绑定"), button ->
                        LevelingSession.reset())
                .bounds(left, y, WIDTH, HEIGHT).build());

        y += 24;
        addRenderableWidget(Button.builder(Component.literal("重新扫描行情"), button ->
                        ScanService.request(true))
                .bounds(left, y, WIDTH, HEIGHT).build());

        y += 24;
        addRenderableWidget(Button.builder(Component.literal("完成"), button -> onClose())
                .bounds(left, y, WIDTH, HEIGHT).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);

        String session = "本次 " + ChatReport.coins(LevelingSession.totalXp()) + " XP ｜ "
                + ChatReport.coins(Math.round(LevelingSession.xpPerHour())) + " XP/h";
        graphics.centeredText(this.font, Component.literal(session), this.width / 2, this.height - 44, 0xFFAAAAAA);

        String share = ExpShareTracker.pets().isEmpty()
                ? "未读取到 Exp Sharing 槽位（打开 /pets → Exp Sharing 一次即可）"
                : "已读取 " + ExpShareTracker.pets().size() + " 个分享槽宠物";
        graphics.centeredText(this.font, Component.literal(share), this.width / 2, this.height - 30, 0xFFAAAAAA);
    }

    private static Component hudLabel(GdragConfig config) {
        return Component.literal("HUD: " + (config.levelingHud ? "开启" : "关闭"));
    }

    private static Component shareLabel(GdragConfig config) {
        return Component.literal("Exp Share 统计: " + (config.expShareEnabled ? "开启" : "关闭"));
    }

    private static Component factorLabel(GdragConfig config) {
        return Component.literal("分享系数: " + String.format("%.0f%%", config.expShareFactorPercent));
    }

    private static Component slotsLabel(GdragConfig config) {
        return Component.literal("分享槽位: " + config.expShareSlots);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
