package net.gdragflip.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.gdragflip.GdragFlipMod;
import net.gdragflip.analysis.GdragAnalyzer;
import net.gdragflip.analysis.GdragCustom;
import net.gdragflip.analysis.PetXpCurve;
import net.gdragflip.auto.AutoScanner;
import net.gdragflip.chat.ChatReport;
import net.gdragflip.cofl.ExpCache;
import net.gdragflip.config.GdragConfig;
import net.gdragflip.leveling.ExpShareTracker;
import net.gdragflip.leveling.LevelingAssistant;
import net.gdragflip.leveling.LevelingSession;
import net.gdragflip.leveling.PetWidgetReader;
import net.gdragflip.scan.ScanService;
import net.gdragflip.ui.HudEditorScreen;
import net.gdragflip.ui.LevelingSettingsScreen;
import net.gdragflip.util.PriceParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * 命令一览（详见 {@code /gdrag help}）：
 * <pre>
 * /gdrag [条数]                  扫描并显示前 N 条（默认 10，最多 50）
 * /gdrag calc &lt;等级&gt; &lt;价格&gt;     评估假设挂单，例如 /gdrag calc 125 660m
 * /gdrag refresh                 跳过 15 秒缓存强制重扫
 * /gdrag auto [on|off]           自动扫描开关 / 状态
 * /gdrag auto interval &lt;秒&gt;     设置间隔（低于 20 秒需二次确认）
 * /gdrag cache clear|info        经验缓存
 * /gdrag reload                  重新读取配置文件
 * /gdrag help                    帮助
 * </pre>
 */
public final class GdragCommand {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private GdragCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
        dispatcher.register(build("gdrag"));
        dispatcher.register(build("gdragflip"));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> build(String name) {
        return ClientCommands.literal(name)
                .executes(context -> scan(context, DEFAULT_LIMIT, null, false))

                .then(ClientCommands.literal("help").executes(GdragCommand::help))
                .then(ClientCommands.literal("refresh")
                        .executes(context -> scan(context, DEFAULT_LIMIT, null, true)))
                .then(ClientCommands.literal("reload").executes(GdragCommand::reload))

                .then(ClientCommands.literal("hud")
                        .executes(GdragCommand::toggleHud)
                        .then(ClientCommands.literal("move").executes(GdragCommand::openHudEditor))
                        .then(ClientCommands.literal("pos")
                                .then(ClientCommands.argument("x", IntegerArgumentType.integer(0, 10000))
                                        .then(ClientCommands.argument("y", IntegerArgumentType.integer(0, 10000))
                                                .executes(GdragCommand::setHudPos)))))
                .then(ClientCommands.literal("settings").executes(GdragCommand::openSettings))
                .then(ClientCommands.literal("session")
                        .executes(GdragCommand::sessionInfo)
                        .then(ClientCommands.literal("start").executes(GdragCommand::sessionStart))
                        .then(ClientCommands.literal("reset").executes(GdragCommand::sessionReset)))
                .then(ClientCommands.literal("debug")
                        .then(ClientCommands.literal("tab").executes(GdragCommand::debugTab))
                        .then(ClientCommands.literal("state").executes(GdragCommand::debugState))
                        .then(ClientCommands.literal("share").executes(GdragCommand::debugShare))
                        .then(ClientCommands.literal("on").executes(context -> setDebug(context, true)))
                        .then(ClientCommands.literal("off").executes(context -> setDebug(context, false))))

                .then(ClientCommands.literal("cache")
                        .then(ClientCommands.literal("clear").executes(GdragCommand::clearCache))
                        .then(ClientCommands.literal("info").executes(GdragCommand::cacheInfo)))

                .then(ClientCommands.literal("auto")
                        .executes(GdragCommand::autoStatus)
                        .then(ClientCommands.literal("on").executes(context -> setAuto(context, true)))
                        .then(ClientCommands.literal("off").executes(context -> setAuto(context, false)))
                        .then(ClientCommands.literal("interval")
                                .then(ClientCommands.argument("seconds",
                                                IntegerArgumentType.integer(GdragConfig.MIN_INTERVAL_SECONDS, 3600))
                                        .executes(context -> setInterval(context, false))
                                        .then(ClientCommands.literal("confirm")
                                                .executes(context -> setInterval(context, true))))))

                .then(ClientCommands.literal("calc")
                        .then(ClientCommands.argument("level",
                                        IntegerArgumentType.integer(PetXpCurve.MIN_LEVEL, PetXpCurve.MAX_LEVEL))
                                .then(ClientCommands.argument("price", StringArgumentType.word())
                                        .executes(context -> calculate(context,
                                                IntegerArgumentType.getInteger(context, "level"),
                                                StringArgumentType.getString(context, "price"))))))

                // 保留旧写法：/gdrag 20 = 条数；/gdrag 125 660m = 等级 + 价格
                .then(ClientCommands.argument("limitOrLevel",
                                IntegerArgumentType.integer(1, PetXpCurve.MAX_LEVEL))
                        .executes(context -> {
                            int value = IntegerArgumentType.getInteger(context, "limitOrLevel");
                            if (value <= MAX_LIMIT) {
                                return scan(context, value, null, false);
                            }
                            context.getSource().sendFeedback(ChatReport.info(
                                    "用法：/gdrag <条数> 或 /gdrag calc <等级> <价格>（/gdrag help 查看全部）",
                                    ChatFormatting.RED));
                            return 0;
                        })
                        .then(ClientCommands.argument("price", StringArgumentType.word())
                                .executes(context -> calculate(context,
                                        IntegerArgumentType.getInteger(context, "limitOrLevel"),
                                        StringArgumentType.getString(context, "price")))));
    }

    private static int calculate(CommandContext<FabricClientCommandSource> context, int level, String priceText) {
        Long price = PriceParser.parse(priceText);
        if (price == null) {
            context.getSource().sendFeedback(ChatReport.info(
                    "价格格式无效，示例：660m、1.2b、660000000", ChatFormatting.RED));
            return 0;
        }
        return scan(context, DEFAULT_LIMIT, new CustomRequest(level, price), false);
    }

    private static int scan(CommandContext<FabricClientCommandSource> context, int limit, CustomRequest custom,
                            boolean force) {
        FabricClientCommandSource source = context.getSource();

        if (ScanService.cached() == null) {
            source.sendFeedback(ChatReport.info(ScanService.isScanning()
                    ? "正在查询中，请稍候…"
                    : "正在拉取挂单… 首次扫描需逐条获取经验，约 25~40 秒", ChatFormatting.GRAY));
        }

        ScanService.request(force).whenComplete((analysis, error) ->
                Minecraft.getInstance().execute(() -> {
                    if (error != null) {
                        error(source, rootMessage(error));
                        return;
                    }
                    GdragCustom customEntry = custom == null
                            ? null
                            : GdragAnalyzer.evaluate(analysis, custom.level(), custom.price());
                    ChatReport.send(source, analysis, limit, customEntry);
                }));
        return 1;
    }

    private static int help(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(ChatReport.info("用法：", ChatFormatting.GOLD));
        line(source, "/gdrag [条数]", "扫描并显示前 N 条（默认 10，最多 50）");
        line(source, "/gdrag calc <等级> <价格>", "评估假设挂单，例如 /gdrag calc 125 660m");
        line(source, "/gdrag refresh", "跳过 15 秒缓存强制重扫");
        line(source, "/gdrag auto [on|off]", "自动扫描开关；不带参数查看状态");
        line(source, "/gdrag auto interval <秒>", "设置间隔（默认 60，最低 5，低于 20 需 confirm）");
        line(source, "/gdrag cache clear|info", "清空 / 查看经验缓存");
        line(source, "/gdrag reload", "重新读取 config/gdragflip.json");
        line(source, "/gdrag hud", "开关升级助手 HUD");
        line(source, "/gdrag hud move", "进入拖动模式调整 HUD 位置");
        line(source, "/gdrag hud pos <x> <y>", "直接用坐标设置 HUD 位置");
        line(source, "/gdrag session", "查看会话状态（绑定宠物/累计/速率）");
        line(source, "/gdrag session start", "开始新会话：装备宠物后打开 /pets 完成绑定");
        line(source, "/gdrag session reset", "清零并解除绑定");
        line(source, "/gdrag settings", "打开升级助手设置界面（含拖动 HUD）");
        line(source, "/gdrag debug tab", "打印解析到的 tab 行（排查 Widget）");
        line(source, "/gdrag debug state", "打印会话/绑定/Widget/单价状态");
        line(source, "/gdrag debug on|off", "开关详细日志（写入 latest.log）");
        line(source, "/gdrag help", "显示本帮助");
        source.sendFeedback(ChatReport.info(
                "定价坐标是累计经验（等级 + 溢出进度）：A = 最低价 100+，B = 最低价 Lvl200（按刚好 200 级计）",
                ChatFormatting.DARK_GRAY));
        return 1;
    }

    private static void line(FabricClientCommandSource source, String command, String description) {
        source.sendFeedback(ChatReport.info("  " + command + "  —  " + description, ChatFormatting.YELLOW));
    }

    private static int autoStatus(CommandContext<FabricClientCommandSource> context) {
        GdragConfig config = GdragConfig.get();
        context.getSource().sendFeedback(ChatReport.info(
                "自动扫描：%s ｜ 间隔 %d 秒 ｜ 提醒阈值 利润 ≥ %s 且 ROI ≥ %.1f%% ｜ 经验缓存 %d 条".formatted(
                        config.autoScan ? "开启" : "关闭",
                        config.intervalSeconds,
                        ChatReport.coins(config.minProfit),
                        config.minRoiPercent,
                        ExpCache.size()), ChatFormatting.GRAY));
        return 1;
    }

    private static int setAuto(CommandContext<FabricClientCommandSource> context, boolean enabled) {
        GdragConfig config = GdragConfig.get();
        config.autoScan = enabled;
        GdragConfig.save();
        if (enabled) {
            AutoScanner.resetTimer();
        }
        context.getSource().sendFeedback(ChatReport.info(
                "自动扫描已" + (enabled ? "开启" : "关闭") + "（间隔 " + config.intervalSeconds + " 秒）",
                ChatFormatting.GREEN));
        return 1;
    }

    private static int setInterval(CommandContext<FabricClientCommandSource> context, boolean confirmed) {
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        FabricClientCommandSource source = context.getSource();
        GdragConfig config = GdragConfig.get();

        if (!confirmed && seconds < GdragConfig.SAFE_INTERVAL_SECONDS) {
            source.sendFeedback(ChatReport.info(
                    "%d 秒低于安全阈值 %d 秒（Cofl 免费额度 30 请求/10 秒、100 请求/分钟，累计 429 会封 IP）。".formatted(
                            seconds, GdragConfig.SAFE_INTERVAL_SECONDS), ChatFormatting.YELLOW));
            source.sendFeedback(ChatReport.info(
                    "确认请执行：/gdrag auto interval " + seconds + " confirm", ChatFormatting.YELLOW));
            return 0;
        }

        config.intervalSeconds = seconds;
        GdragConfig.save();
        if (config.autoScan) {
            AutoScanner.resetTimer();
        }
        source.sendFeedback(ChatReport.info("扫描间隔已设为 " + seconds + " 秒", ChatFormatting.GREEN));
        return 1;
    }

    private static int clearCache(CommandContext<FabricClientCommandSource> context) {
        ExpCache.clear();
        AutoScanner.clearNotified();
        context.getSource().sendFeedback(ChatReport.info("经验缓存已清空，下次扫描会重新获取", ChatFormatting.GREEN));
        return 1;
    }

    private static int cacheInfo(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(ChatReport.info(
                "经验缓存 %d 条 ｜ 文件 %s".formatted(ExpCache.size(), GdragConfig.file().getParent().resolve("gdragflip-exp-cache.json")),
                ChatFormatting.GRAY));
        return 1;
    }

    private static int reload(CommandContext<FabricClientCommandSource> context) {
        GdragConfig.load();
        GdragConfig config = GdragConfig.get();
        context.getSource().sendFeedback(ChatReport.info(
                "配置已重新读取：自动扫描 %s，间隔 %d 秒".formatted(
                        config.autoScan ? "开启" : "关闭", config.intervalSeconds), ChatFormatting.GREEN));
        return 1;
    }

    private static int toggleHud(CommandContext<FabricClientCommandSource> context) {
        GdragConfig config = GdragConfig.get();
        config.levelingHud = !config.levelingHud;
        GdragConfig.save();
        context.getSource().sendFeedback(ChatReport.info(
                "升级助手 HUD " + (config.levelingHud ? "已开启" : "已关闭") + "（位置/分享参数用 /gdrag settings 调整）",
                ChatFormatting.GREEN));
        return 1;
    }

    private static int openSettings(CommandContext<FabricClientCommandSource> context) {
        LevelingAssistant.openNextTick(new LevelingSettingsScreen());
        return 1;
    }

    private static int openHudEditor(CommandContext<FabricClientCommandSource> context) {
        GdragConfig config = GdragConfig.get();
        if (!config.levelingHud) {
            config.levelingHud = true;
            GdragConfig.save();
        }
        LevelingAssistant.openNextTick(new HudEditorScreen());
        return 1;
    }

    private static int setHudPos(CommandContext<FabricClientCommandSource> context) {
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        GdragConfig config = GdragConfig.get();
        config.hudX = x;
        config.hudY = y;
        config.levelingHud = true;
        GdragConfig.save();
        context.getSource().sendFeedback(ChatReport.info(
                "HUD 位置已设为 (%d, %d)".formatted(x, y), ChatFormatting.GREEN));
        return 1;
    }

    private static int sessionInfo(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        long xp = LevelingSession.totalXp();
        double pricePerExp = ScanService.lastResult() == null || !ScanService.lastResult().hasPricing()
                ? 0.0
                : ScanService.lastResult().pricePerExp();

        String since = java.time.Instant.ofEpochMilli(LevelingSession.startedAt())
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        if (!LevelingSession.bound()) {
            source.sendFeedback(ChatReport.info(LevelingSession.pendingBind()
                    ? "等待绑定：请装备要升级的宠物并打开一次 /pets 菜单"
                    : "会话未开始：先执行 /gdrag session start", ChatFormatting.YELLOW));
        } else {
            source.sendFeedback(ChatReport.info("绑定宠物：%s%s".formatted(
                    LevelingSession.boundPetName(),
                    LevelingSession.mismatch() ? " ｜ ⚠ 当前宠物不符（已暂停）" : " ｜ ✓"),
                    LevelingSession.mismatch() ? ChatFormatting.RED : ChatFormatting.GREEN));
        }

        source.sendFeedback(ChatReport.info("自 %s 起累计：%s XP ｜ %s XP/h ｜ 已装备金龙 %s".formatted(
                since,
                ChatReport.coins(xp),
                ChatReport.coins(Math.round(LevelingSession.xpPerHour())),
                LevelingSession.goldenDragonEquipped() ? "是" : "否"), ChatFormatting.GRAY));

        if (pricePerExp > 0) {
            source.sendFeedback(ChatReport.info("折合收益：%s coins（基准 %s / 1M XP）".formatted(
                    ChatReport.coins(Math.round(xp * pricePerExp)),
                    ChatReport.coins(Math.round(pricePerExp * 1_000_000.0))), ChatFormatting.GREEN));
        } else {
            source.sendFeedback(ChatReport.info("行情未就绪：先执行 /gdrag 或开启自动扫描", ChatFormatting.YELLOW));
        }

        long dungeonMinutes = LevelingSession.dungeonMillis() / 60_000L;
        source.sendFeedback(ChatReport.info("地牢计时：%d 分钟 ｜ 估算系数 %.2f%s".formatted(
                dungeonMinutes,
                LevelingSession.estimateFactor(),
                LevelingSession.usingEstimate() ? "（当前为估算）" : ""), ChatFormatting.GRAY));
        source.sendFeedback(ChatReport.info("当前宠物：%s".formatted(
                LevelingSession.currentPetKnown() ? LevelingSession.currentPetName() : "未知（尚未收到召唤消息）"),
                ChatFormatting.GRAY));

        if (!LevelingSession.widgetDetected()
                && !LevelingSession.estimatedWithin(120_000L)
                && LevelingSession.dungeonMillis() == 0L) {
            source.sendFeedback(ChatReport.info("未检测到宠物 Widget：请在 /tab 开启 Pet Widget", ChatFormatting.RED));
        }

        if (!ExpShareTracker.pets().isEmpty()) {
            source.sendFeedback(ChatReport.info("Exp Share 槽位：" + ExpShareTracker.pets().stream()
                    .map(pet -> pet.name() + " Lvl " + pet.level())
                    .reduce((a, b) -> a + "、" + b).orElse(""), ChatFormatting.AQUA));
        }
        return 1;
    }

    private static int debugTab(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        List<String> lines = PetWidgetReader.debugLines(Minecraft.getInstance());
        source.sendFeedback(ChatReport.info("Tab 共 " + lines.size() + " 行（只列非空行，最多 120 条）：", ChatFormatting.GRAY));
        int shown = 0;
        for (String line : lines) {
            int separator = line.indexOf('|');
            if (separator < 0 || line.substring(separator + 1).trim().isEmpty()) {
                continue;
            }
            if (shown++ >= 120) {
                break;
            }
            source.sendFeedback(Component.literal(line).withStyle(ChatFormatting.DARK_GRAY));
        }

        PetWidgetReader.Snapshot snapshot = PetWidgetReader.read(Minecraft.getInstance());
        source.sendFeedback(ChatReport.info("解析结果：found=%s golden=%s level=%d exp=%d".formatted(
                snapshot.found(), snapshot.goldenDragon(), snapshot.level(), snapshot.totalExp()),
                snapshot.found() ? ChatFormatting.GREEN : ChatFormatting.RED));
        return 1;
    }

    private static int debugState(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        PetWidgetReader.Snapshot snapshot = LevelingAssistant.snapshot();
        String bound = LevelingSession.bound()
                ? LevelingSession.boundPetName() + (LevelingSession.boundConfident() ? "（精确绑定）" : "（名字绑定）")
                : "未绑定";
        long estimateAge = LevelingSession.estimateAgeMillis();

        source.sendFeedback(ChatReport.info("会话：%s ｜ 当前宠物 %s ｜ %s".formatted(
                bound,
                LevelingSession.currentPetName().isEmpty() ? "未知" : LevelingSession.currentPetName(),
                LevelingSession.mismatch() ? "⚠ 不符（已暂停）" : "✓ 匹配"), ChatFormatting.GRAY));

        source.sendFeedback(ChatReport.info("累计 %s XP ｜ 活跃 %d 分钟 ｜ 地牢 %d 分钟 ｜ 系数 %.3f ｜ 估算 %s".formatted(
                ChatReport.coins(LevelingSession.totalXp()),
                LevelingSession.activeMillis() / 60_000L,
                LevelingSession.dungeonMillis() / 60_000L,
                LevelingSession.estimateFactor(),
                estimateAge < 0 ? "从未" : (estimateAge / 1000) + " 秒前"), ChatFormatting.GRAY));
        source.sendFeedback(ChatReport.info("待校准样本：估算 %s XP / %d 次 ｜ 精确 %s XP".formatted(
                ChatReport.coins(LevelingSession.rawSinceAnchor()),
                LevelingSession.estimateEvents(),
                ChatReport.coins(LevelingSession.preciseSinceAnchor())), ChatFormatting.DARK_GRAY));
        String calNote = LevelingSession.lastCalibrationNote();
        if (!calNote.isEmpty()) {
            source.sendFeedback(ChatReport.info("LastCal: " + calNote, ChatFormatting.DARK_GRAY));
        }

        source.sendFeedback(ChatReport.info("Widget：found=%s pet='%s' level=%d exp=%s ｜ Widget 检测=%s".formatted(
                snapshot.found(), snapshot.petName(), snapshot.level(),
                ChatReport.coins(snapshot.totalExp()),
                LevelingSession.widgetDetected() ? "是" : "否"), ChatFormatting.GRAY));

        source.sendFeedback(ChatReport.info("Dungeon: %s%s".formatted(
                net.gdragflip.leveling.DungeonDetector.inDungeon() ? "yes" : "no",
                net.gdragflip.leveling.DungeonDetector.inDungeon()
                        ? " (" + net.gdragflip.leveling.DungeonDetector.floor() + ")"
                        : ""), ChatFormatting.GRAY));

        double base = GdragConfig.get().dungeonPetXpMultiplier;
        double factor = LevelingSession.estimateFactor();
        source.sendFeedback(ChatReport.info("Eff: 1 Combat XP -> %.2f pet XP (base %.2f * f%.3f)".formatted(
                base * factor, base, factor), ChatFormatting.GRAY));

        double price = ScanService.lastResult() != null && ScanService.lastResult().hasPricing()
                ? ScanService.lastResult().pricePerExp() : 0.0;
        source.sendFeedback(ChatReport.info("单价：%s coins/XP ｜ 详细日志：%s".formatted(
                price > 0 ? String.format("%.4f", price) : "行情未就绪",
                GdragConfig.get().debugVerbose ? "开" : "关"), ChatFormatting.GRAY));
        return 1;
    }

    /** 把 Exp Sharing 界面 18~40 号槽位的内容写进日志，用于校准槽位坐标。 */
    private static int debugShare(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft client = Minecraft.getInstance();
        if (!(client.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen)
                || !screen.getTitle().getString().contains("Exp Sharing")) {
            source.sendFeedback(ChatReport.info("请先打开 /pets → Exp Sharing 界面再执行", ChatFormatting.RED));
            return 0;
        }

        net.minecraft.world.inventory.AbstractContainerMenu menu = screen.getMenu();
        GdragFlipMod.LOGGER.info("[share] ---- Exp Sharing 槽位转储 (size={}) ----", menu.slots.size());
        for (int slot = 18; slot < menu.slots.size() && slot <= 44; slot++) {
            net.minecraft.world.item.ItemStack stack = menu.getSlot(slot).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String name = stack.getHoverName().getString().replaceAll("§.", "");
            String lore = net.gdragflip.leveling.PetNbt.loreText(stack)
                    .replaceAll("§.", "").replace("\n", " / ").trim();
            GdragFlipMod.LOGGER.info("[share] slot={} name='{}' lore='{}'", slot, name, lore);
        }
        source.sendFeedback(ChatReport.info("已写入 latest.log，搜索 [share]", ChatFormatting.GREEN));
        return 1;
    }

    private static int setDebug(CommandContext<FabricClientCommandSource> context, boolean enabled) {
        GdragConfig config = GdragConfig.get();
        config.debugVerbose = enabled;
        GdragConfig.save();
        context.getSource().sendFeedback(ChatReport.info(
                "调试日志已" + (enabled ? "开启（每次采样/估算都会写 latest.log）" : "关闭"),
                ChatFormatting.GREEN));
        return 1;
    }

    private static int sessionStart(CommandContext<FabricClientCommandSource> context) {
        LevelingSession.requestStart();
        context.getSource().sendFeedback(ChatReport.info(
                "开始新会话：请装备要升级的宠物，然后打开一次 /pets 菜单完成 UUID 绑定", ChatFormatting.YELLOW));
        context.getSource().sendFeedback(ChatReport.info(
                "地牢外开 /pets = 校准；地牢内开 /pets = 仅显示真实值 15 秒（不改累计、不改系数）",
                ChatFormatting.GRAY));
        context.getSource().sendFeedback(ChatReport.info(
                "开 /pets 时顺手打开 Exp Sharing，HUD 的分享槽才会更新", ChatFormatting.DARK_GRAY));
        return 1;
    }

    private static int sessionReset(CommandContext<FabricClientCommandSource> context) {
        LevelingSession.reset();
        context.getSource().sendFeedback(ChatReport.info("本次会话统计已重置", ChatFormatting.GREEN));
        return 1;
    }

    private static void error(FabricClientCommandSource source, String message) {
        source.sendFeedback(ChatReport.info("查询失败：" + message, ChatFormatting.RED));
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private record CustomRequest(int level, long price) {
    }
}
