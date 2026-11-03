package net.gdragflip.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.gdragflip.GdragFlipMod;
import net.gdragflip.analysis.GdragAnalysis;
import net.gdragflip.analysis.GdragAnalyzer;
import net.gdragflip.analysis.GdragOpportunity;
import net.gdragflip.chat.ChatReport;
import net.gdragflip.cofl.CoflAuction;
import net.gdragflip.cofl.CoflClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** /gdrag [limit] —— 查询并分析当前金龙挂单。 */
public final class GdragCommand {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;
    private static final long CACHE_MILLIS = 15_000L;

    private static final AtomicBoolean FETCHING = new AtomicBoolean(false);
    private static volatile GdragAnalysis lastAnalysis;
    private static volatile long lastAnalysisAt;

    /** 宠物糖果状态对同一条拍卖不会变，按 UUID 缓存，避免重复查详情。 */
    private static final Map<String, Integer> CANDY_CACHE = new ConcurrentHashMap<>();
    /** 详情接口是逐条请求，限制单次最多查多少条，避免长等待和触发限流。 */
    private static final int MAX_CANDY_LOOKUPS = 10;

    private GdragCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
        dispatcher.register(build("gdrag"));
        dispatcher.register(build("gdragflip"));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> build(String name) {
        return ClientCommands.literal(name)
                .executes(context -> execute(context, DEFAULT_LIMIT))
                .then(ClientCommands.argument("limit", IntegerArgumentType.integer(1, MAX_LIMIT))
                        .executes(context -> execute(context, IntegerArgumentType.getInteger(context, "limit"))));
    }

    private static int execute(CommandContext<FabricClientCommandSource> context, int limit) {
        FabricClientCommandSource source = context.getSource();
        long now = System.currentTimeMillis();

        GdragAnalysis cached = lastAnalysis;
        if (cached != null && now - lastAnalysisAt <= CACHE_MILLIS) {
            CompletableFuture.runAsync(() -> sendWithCandy(source, cached, limit));
            return 1;
        }

        if (!FETCHING.compareAndSet(false, true)) {
            source.sendFeedback(ChatReport.info("正在查询中，请稍候…", ChatFormatting.YELLOW));
            return 0;
        }

        source.sendFeedback(ChatReport.info("正在从 Cofl 拉取金龙挂单…", ChatFormatting.GRAY));

        CompletableFuture.runAsync(() -> {
            try {
                List<CoflAuction> auctions = CoflClient.fetchGoldenDragons();
                GdragAnalysis analysis = GdragAnalyzer.analyze(auctions);
                lastAnalysis = analysis;
                lastAnalysisAt = System.currentTimeMillis();
                sendWithCandy(source, analysis, limit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error(source, "查询被中断");
            } catch (Exception e) {
                GdragFlipMod.LOGGER.warn("Golden Dragon auction lookup failed", e);
                error(source, e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                FETCHING.set(false);
            }
        });

        return 1;
    }

    /**
     * 对将要展示的条目查询 pet candy 使用次数，再回主线程输出。
     * 未使用过（或查询失败）的条目不携带标记。
     */
    private static void sendWithCandy(FabricClientCommandSource source, GdragAnalysis analysis, int limit) {
        Map<String, Integer> candyUsed = new HashMap<>();
        int lookups = 0;

        for (GdragOpportunity opportunity : ChatReport.selectForDisplay(analysis, limit)) {
            if (lookups++ >= MAX_CANDY_LOOKUPS) {
                break;
            }

            String uuid = opportunity.listing().uuid();
            Integer cached = CANDY_CACHE.get(uuid);
            if (cached == null) {
                int used = CoflClient.fetchCandyUsed(uuid);
                if (used >= 0) {
                    CANDY_CACHE.put(uuid, used);
                    cached = used;
                }
            }

            if (cached != null && cached > 0) {
                candyUsed.put(uuid, cached);
            }
        }

        Minecraft.getInstance().execute(() -> ChatReport.send(source, analysis, limit, candyUsed));
    }

    private static void error(FabricClientCommandSource source, String message) {
        Minecraft.getInstance().execute(() ->
                source.sendFeedback(ChatReport.info("查询失败：" + message, ChatFormatting.RED)));
    }
}
