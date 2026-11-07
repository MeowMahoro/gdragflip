package net.gdragflip.scan;

import net.gdragflip.analysis.GdragAnalysis;
import net.gdragflip.analysis.GdragAnalyzer;
import net.gdragflip.analysis.GdragListing;
import net.gdragflip.cofl.CoflAuction;
import net.gdragflip.cofl.CoflClient;
import net.gdragflip.cofl.ExpCache;
import net.gdragflip.cofl.PetDetail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 扫描流程：拉列表 → 给未缓存的挂单补经验 → 分析。
 *
 * <p>命令与自动扫描共用同一份结果，15 秒内的重复请求直接复用，
 * 同一时刻只允许一个扫描在跑（并发调用返回同一个 future）。</p>
 */
public final class ScanService {
    private static final long CACHE_MILLIS = 15_000L;
    private static final Object LOCK = new Object();

    private static volatile GdragAnalysis last;
    private static volatile long lastAt;
    private static volatile CompletableFuture<GdragAnalysis> inFlight;

    private ScanService() {
    }

    /** 15 秒内的扫描结果，过期或没有则返回 null。 */
    public static GdragAnalysis cached() {
        GdragAnalysis snapshot = last;
        if (snapshot != null && System.currentTimeMillis() - lastAt <= CACHE_MILLIS) {
            return snapshot;
        }
        return null;
    }

    /** 最近一次扫描结果（不看新鲜度，供 HUD 取价格基准）。 */
    public static GdragAnalysis lastResult() {
        return last;
    }

    public static boolean hasResult() {
        return last != null;
    }

    public static boolean isScanning() {
        return inFlight != null;
    }

    public static CompletableFuture<GdragAnalysis> request(boolean force) {
        if (!force) {
            GdragAnalysis snapshot = cached();
            if (snapshot != null) {
                return CompletableFuture.completedFuture(snapshot);
            }
        }

        synchronized (LOCK) {
            CompletableFuture<GdragAnalysis> current = inFlight;
            if (current != null) {
                return current;
            }
            CompletableFuture<GdragAnalysis> future = CompletableFuture.supplyAsync(ScanService::run);
            inFlight = future;
            future.whenComplete((analysis, error) -> inFlight = null);
            return future;
        }
    }

    private static GdragAnalysis run() {
        try {
            List<CoflAuction> raw = CoflClient.fetchGoldenDragons();
            List<GdragListing> prepared = GdragAnalyzer.prepare(raw);

            List<String> missing = new ArrayList<>();
            for (GdragListing listing : prepared) {
                if (!ExpCache.has(listing.uuid())) {
                    missing.add(listing.uuid());
                }
            }
            if (!missing.isEmpty()) {
                Map<String, PetDetail> details = CoflClient.fetchPetDetailsBatch(missing);
                if (!details.isEmpty()) {
                    ExpCache.putAll(details);
                    ExpCache.save();
                }
            }

            GdragAnalysis analysis = GdragAnalyzer.analyze(raw);
            last = analysis;
            lastAt = System.currentTimeMillis();
            return analysis;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }
}
