package net.gdragflip.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.gdragflip.analysis.GdragAnalyzer;
import net.gdragflip.analysis.GdragCustom;
import net.gdragflip.analysis.PetXpCurve;
import net.gdragflip.auto.AutoScanner;
import net.gdragflip.chat.ChatReport;
import net.gdragflip.cofl.ExpCache;
import net.gdragflip.config.GdragConfig;
import net.gdragflip.scan.ScanService;
import net.gdragflip.util.PriceParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;

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
