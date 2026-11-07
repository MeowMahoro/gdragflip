package net.gdragflip.auto;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.gdragflip.analysis.GdragAnalysis;
import net.gdragflip.analysis.GdragOpportunity;
import net.gdragflip.chat.ChatReport;
import net.gdragflip.config.GdragConfig;
import net.gdragflip.scan.ScanService;
import net.minecraft.client.Minecraft;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台自动扫描：按配置的秒级间隔触发，命中阈值后在聊天栏提醒（同一条拍卖只提醒一次）。
 *
 * <p>扫描本身在后台线程，提醒切回客户端线程；同一时刻不会重叠扫描
 * （重复触发会被 {@link ScanService} 合并）。</p>
 */
public final class AutoScanner {
    private static final int MAX_ALERTS_PER_SCAN = 3;
    private static final Set<String> NOTIFIED = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean PENDING = new AtomicBoolean(false);

    private static volatile long lastScanAt = System.currentTimeMillis();

    private AutoScanner() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoScanner::tick);
    }

    /** 开启自动扫描或改小间隔后调用，让下一 tick 立刻开始一轮。 */
    public static void resetTimer() {
        lastScanAt = 0L;
    }

    public static void clearNotified() {
        NOTIFIED.clear();
    }

    private static void tick(Minecraft client) {
        GdragConfig config = GdragConfig.get();
        if (!config.autoScan || client.player == null) {
            return;
        }

        long intervalMillis = Math.max(GdragConfig.MIN_INTERVAL_SECONDS, config.intervalSeconds) * 1000L;
        if (System.currentTimeMillis() - lastScanAt < intervalMillis) {
            return;
        }
        if (!PENDING.compareAndSet(false, true)) {
            return;
        }

        lastScanAt = System.currentTimeMillis();
        ScanService.request(false).whenComplete((analysis, error) -> {
            PENDING.set(false);
            if (error != null || analysis == null) {
                return;
            }
            client.execute(() -> notifyOpportunities(client, analysis, config));
        });
    }

    private static void notifyOpportunities(Minecraft client, GdragAnalysis analysis, GdragConfig config) {
        if (!analysis.hasPricing()) {
            return;
        }
        int sent = 0;
        for (GdragOpportunity opportunity : analysis.belowExpected()) {
            if (sent >= MAX_ALERTS_PER_SCAN) {
                break;
            }
            if (opportunity.profit() < config.minProfit) {
                continue;
            }
            if (opportunity.roi() * 100.0 < config.minRoiPercent) {
                continue;
            }
            if (!NOTIFIED.add(opportunity.listing().uuid())) {
                continue;
            }
            ChatReport.notify(client, opportunity);
            sent++;
        }
    }
}
