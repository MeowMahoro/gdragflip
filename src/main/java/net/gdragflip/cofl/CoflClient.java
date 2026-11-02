package net.gdragflip.cofl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Cofl SkyApi 只读客户端。
 *
 * <p>使用 {@code /api/auctions/active/filter} 而不是 {@code /tag/{tag}/active/bin}：
 * 后者只返回最低的 10 条，而金龙的低价挂单基本全是 Lvl 1~9 的蛋，
 * 会导致所有 100 级以上的挂单完全看不到。</p>
 *
 * <p>filter 端点支持 page/pageSize（上限 100）+ tags 过滤，可拿到全部在挂金龙。</p>
 */
public final class CoflClient {
    /**
     * 与官方 SkyCofl mod 一致：主域名在 Cloudflare 后面，被拦时回退到官方自己的镜像域名。
     * （官方 CoflCore 的 QueryServerCommands 也是同样的多端点回退顺序。）
     */
    private static final String[] BASE_URLS = {
            "https://sky.coflnet.com/api/",
            "https://sky-commands.coflnet.com/api/",
            "https://sky-mod.coflnet.com/api/"
    };

    /** 最近一次请求成功的域名，后续请求优先使用，避免每次都先吃一次 403。 */
    private static volatile String preferredBase = BASE_URLS[0];
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 GdragFlip/1.0";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private CoflClient() {
    }

    /**
     * 拉取当前在挂的全部 PET_GOLDEN_DRAGON 拍卖。
     *
     * @throws IOException          HTTP 失败、限流或响应结构异常
     * @throws InterruptedException 线程被中断
     */
    public static List<CoflAuction> fetchGoldenDragons() throws IOException, InterruptedException {
        List<CoflAuction> auctions = new ArrayList<>();
        int totalCount = Integer.MAX_VALUE;

        for (int page = 0; page < MAX_PAGES && auctions.size() < totalCount; page++) {
            String path = "auctions/active/filter?tags=PET_GOLDEN_DRAGON&pageSize=" + PAGE_SIZE + "&page=" + page;
            JsonObject root = getJson(path);

            totalCount = root.has("totalCount") ? root.get("totalCount").getAsInt() : 0;
            JsonArray items = root.getAsJsonArray("items");
            if (items == null || items.isEmpty()) {
                break;
            }

            for (JsonElement element : items) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                auctions.add(new CoflAuction(
                        string(item, "uuid"),
                        string(item, "itemName"),
                        string(item, "tier"),
                        bool(item, "bin"),
                        number(item, "startingBid"),
                        string(item, "playerName"),
                        parseEnd(string(item, "end"))
                ));
            }
        }

        return auctions;
    }

    private static JsonObject getJson(String path) throws IOException, InterruptedException {
        IOException lastError = null;
        for (String base : orderedBases()) {
            try {
                JsonObject root = getJson(base, path);
                preferredBase = base;
                return root;
            } catch (IOException e) {
                lastError = e;
                System.err.println("[gdragflip] Cofl endpoint failed: " + base + " -> " + e.getMessage());
            }
        }
        throw new IOException("Cofl 所有域名均不可用：" + lastError.getMessage(), lastError);
    }

    private static List<String> orderedBases() {
        List<String> bases = new ArrayList<>(BASE_URLS.length);
        bases.add(preferredBase);
        for (String base : BASE_URLS) {
            if (!base.equals(preferredBase)) {
                bases.add(base);
            }
        }
        return bases;
    }

    private static JsonObject getJson(String base, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "https://sky.coflnet.com/")
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            throw new IOException("Cofl 请求被限流（429），请稍后再试");
        }
        if (response.statusCode() != 200) {
            // 写入游戏日志便于诊断（403 多为 Cloudflare 对代理/VPN 出口的拦截）。
            System.err.println("[gdragflip] Cofl HTTP " + response.statusCode()
                    + " body=" + shorten(response.body(), 300));
            if (response.statusCode() == 403) {
                throw new IOException("HTTP 403（Cloudflare 拦截）");
            }
            if (response.statusCode() == 429) {
                throw new IOException("Cofl 请求被限流（429），请稍后再试");
            }
            throw new IOException("Cofl 返回 HTTP " + response.statusCode());
        }

        JsonObject root = GSON.fromJson(response.body(), JsonObject.class);
        if (root == null) {
            throw new IOException("Cofl 返回内容无法解析");
        }
        return root;
    }

    private static String shorten(String value, int max) {
        if (value == null) {
            return "";
        }
        String flat = value.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static long number(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return 0L;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && !element.isJsonNull() && element.getAsBoolean();
    }

    private static long parseEnd(String value) {
        if (value == null || value.isEmpty()) {
            return -1L;
        }
        // filter 端点返回带毫秒与 Z 的 ISO-8601（如 2026-11-16T18:24:17.64Z），
        // active/bin 端点则是不带时区的 LocalDateTime，两种都兼容。
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli();
            } catch (RuntimeException e) {
                return -1L;
            }
        }
    }
}
