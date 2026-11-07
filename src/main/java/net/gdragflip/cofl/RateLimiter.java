package net.gdragflip.cofl;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 本地限速器，对应 Cofl 免费额度：30 请求/10 秒、100 请求/分钟。
 *
 * <p>留出余量（28/10s、92/60s），避免因为时钟漂移或并发请求撞上 429；
 * 官方说明累计 500 次 429 会封 IP，所以宁可慢一点。</p>
 */
public final class RateLimiter {
    private static final int MAX_PER_10S = 28;
    private static final int MAX_PER_60S = 92;

    private static final Deque<Long> HISTORY = new ArrayDeque<>();

    private RateLimiter() {
    }

    /**
     * 申请 {@code permits} 个请求额度，额度不足时阻塞等待。
     */
    public static void acquire(int permits) throws InterruptedException {
        if (permits <= 0) {
            return;
        }
        synchronized (HISTORY) {
            int remaining = permits;
            while (remaining > 0) {
                long now = System.currentTimeMillis();
                prune(now);

                int last10s = countSince(now - 10_000L);
                if (last10s >= MAX_PER_10S) {
                    Thread.sleep(150L);
                    continue;
                }
                if (HISTORY.size() >= MAX_PER_60S) {
                    Thread.sleep(500L);
                    continue;
                }
                HISTORY.addLast(now);
                remaining--;
            }
        }
    }

    private static void prune(long now) {
        while (!HISTORY.isEmpty() && now - HISTORY.peekFirst() > 60_000L) {
            HISTORY.pollFirst();
        }
    }

    private static int countSince(long threshold) {
        int count = 0;
        var iterator = HISTORY.descendingIterator();
        while (iterator.hasNext()) {
            if (iterator.next() >= threshold) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }
}
