package net.gdragflip.cofl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.gdragflip.GdragFlipMod;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拍卖 UUID → 宠物经验/糖果 的永久缓存。
 *
 * <p>同一条拍卖的 {@code exp} 与 {@code candyUsed} 不会变化（经验只增不减，糖果不会减少，
 * 而拍卖一旦上架就固定了 NBT），因此查到一次即可永久复用。这样每次扫描只需要为新出现的
 * 挂单发详情请求，稳态下几乎不消耗额度。</p>
 *
 * <p>落盘位置：{@code config/gdragflip-exp-cache.json}。</p>
 */
public final class ExpCache {
    private static final int PRUNE_THRESHOLD = 20_000;
    private static final Type TYPE = new TypeToken<Map<String, PetDetail>>() {
    }.getType();
    private static final Gson GSON = new GsonBuilder().create();

    private static final Map<String, PetDetail> CACHE = new ConcurrentHashMap<>();
    private static Path file;

    private ExpCache() {
    }

    public static synchronized void load() {
        file = FabricLoader.getInstance().getConfigDir().resolve("gdragflip-exp-cache.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, PetDetail> loaded = GSON.fromJson(reader, TYPE);
            if (loaded != null) {
                CACHE.putAll(loaded);
            }
            GdragFlipMod.LOGGER.info("经验缓存已加载：{} 条", CACHE.size());
        } catch (Exception e) {
            GdragFlipMod.LOGGER.warn("经验缓存读取失败，将重新获取", e);
        }
    }

    public static boolean has(String uuid) {
        return uuid != null && CACHE.containsKey(uuid);
    }

    public static PetDetail get(String uuid) {
        return uuid == null ? null : CACHE.get(uuid);
    }

    public static void putAll(Map<String, PetDetail> details) {
        if (details != null && !details.isEmpty()) {
            CACHE.putAll(details);
        }
    }

    public static int size() {
        return CACHE.size();
    }

    public static synchronized void clear() {
        CACHE.clear();
        save();
    }

    public static synchronized void save() {
        if (file == null) {
            return;
        }
        if (CACHE.size() > PRUNE_THRESHOLD) {
            // 拍卖 UUID 只增不减，超过上限时直接清空重来（重新采集一次即可）。
            CACHE.clear();
        }
        try {
            Files.createDirectories(file.getParent());
            Map<String, PetDetail> snapshot = new HashMap<>(CACHE);
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(snapshot, TYPE, writer);
            }
        } catch (Exception e) {
            GdragFlipMod.LOGGER.warn("经验缓存写入失败", e);
        }
    }
}
