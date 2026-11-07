package net.gdragflip.leveling;

import net.gdragflip.GdragFlipMod;
import net.gdragflip.analysis.PetXpCurve;
import net.gdragflip.config.GdragConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.PlayerTeam;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 Tab 列表里的「宠物 Widget」。
 *
 * <p>需要玩家在 {@code /tab} 里开启 Pet Widget，Hypixel 才会把当前宠物显示在 tab 上，
 * 形如：</p>
 * <pre>
 *  [Lvl 123] Golden Dragon
 *  33,915/179.7k XP (18.9%)
 * </pre>
 *
 * <p>排序方式与 Skysoft 的 tab 解析保持一致：按 {@code tabListOrder} 降序，
 * 再按 spectator / 队伍名 / 玩家名排序（Hypixel 用这些字段编码行顺序）。</p>
 */
public final class PetWidgetReader {
    private static final String TRACKED_PET = "Golden Dragon";
    private static volatile long lastDiagAt;
    /** tab 条目上限（Hypixel 的 tab 常有 100+ 行，不能截断太早）。 */
    private static final int MAX_LINES = 200;

    /** \u2726 是宠物皮肤标记 ✦，用转义写死避免源码编码问题。 */
    private static final Pattern NAME_PATTERN =
            Pattern.compile("\\[Lvl ([\\d,]+)]\\s*(?:\\[\\d+\u2726]\\s*)?([A-Za-z0-9 '_-]+?)(?: \u2726)?$");
    /** 经验行：兼容 "7,544/87,900"、"1,698,900.4/1.9M XP (90%)"、任意空白字符。 */
    private static final Pattern PROGRESS_PATTERN =
            Pattern.compile("^([\\d,.]+[kKmM]?)\\s*/\\s*([\\d,.]+[kKmM]?)\\s*(?:XP)?\\s*(?:\\(([\\d.]+)%\\))?$");
    private static final Pattern OVERFLOW_PATTERN = Pattern.compile("^\\+\\s*([\\d,.]+[kKmM]?)\\s*XP$");

    /** 宽松兜底：行里带 "Lvl N" / "a/b" / "+N XP" 即可。 */
    private static final Pattern LEVEL_PATTERN = Pattern.compile("Lvl\\s*([\\d,]+)");
    private static final Pattern PROGRESS_LOOSE_PATTERN =
            Pattern.compile("([\\d,.]+[kKmM]?)\\s*/\\s*([\\d,.]+[kKmM]?)\\s*XP");
    private static final Pattern OVERFLOW_LOOSE_PATTERN = Pattern.compile("\\+\\s*([\\d,.]+[kKmM]?)\\s*XP");

    private PetWidgetReader() {
    }

    public record Snapshot(
            boolean found,
            boolean goldenDragon,
            int level,
            long totalExp,
            long overflowExp,
            String petName
    ) {
        public static final Snapshot MISSING = new Snapshot(false, false, 0, 0L, 0L, "");
    }

    /**
     * 从 tab 列表读取当前宠物状态；未找到（或未开启 Widget）时返回 {@link Snapshot#MISSING}。
     */
    public static Snapshot read(Minecraft client) {
        List<String> lines = lines(client);
        if (lines.isEmpty()) {
            return Snapshot.MISSING;
        }

        // 兜底：只要 tab 上有「任何宠物」Widget，就说明不在无 Widget 的环境（例如地牢），
        // 用来让地牢计时正确暂停；金龙的精确数据仍优先返回。
        Snapshot otherPet = null;

        for (int i = 0; i < lines.size(); i++) {
            String nameLine = lines.get(i).trim();
            Matcher strict = NAME_PATTERN.matcher(nameLine);
            Integer level = null;
            String petName;
            if (strict.find()) {
                level = parseInt(strict.group(1), 0);
                petName = strict.group(2).trim();
            } else {
                Matcher loose = LEVEL_PATTERN.matcher(nameLine);
                if (!loose.find()) {
                    continue;
                }
                level = parseInt(loose.group(1), 0);
                petName = nameLine.replaceAll("\\[Lvl [\\d,]+]", "").replace("\u2726", "").trim();
            }
            if (level == null || level <= 0) {
                continue;
            }

            boolean golden = petName.toLowerCase(Locale.ROOT).contains(TRACKED_PET.toLowerCase(Locale.ROOT));
            Xp xp = findXp(lines, i);

            if (golden) {
                if (xp != null) {
                    return toSnapshot(level, petName, xp, true);
                }
                logNeighbors(lines, i, nameLine);
                continue;
            }

            if (otherPet == null) {
                otherPet = new Snapshot(true, false, level, PetXpCurve.expAtLevel(level), 0L, petName);
            }
        }

        return otherPet != null ? otherPet : Snapshot.MISSING;
    }

    /** 名字行附近找经验行：优先 "a/b" 进度，其次 "+N XP" 满级溢出，最后看上一行。 */
    private static Xp findXp(List<String> lines, int index) {
        Xp progress = null;
        Xp overflow = null;
        for (int offset = 1; offset <= 3; offset++) {
            Xp xp = xpAt(lines, index + offset);
            if (xp == null) {
                continue;
            }
            if (!xp.maxLevel() && progress == null) {
                progress = xp;
            } else if (xp.maxLevel() && overflow == null) {
                overflow = xp;
            }
        }
        if (progress != null) {
            return progress;
        }
        if (overflow != null) {
            return overflow;
        }
        return xpAt(lines, index - 1);
    }

    private static void logNeighbors(List<String> lines, int index, String nameLine) {
        long now = System.currentTimeMillis();
        if (now - lastDiagAt < 5_000L) {
            return;
        }
        lastDiagAt = now;
        GdragFlipMod.LOGGER.info("[petwidget] 名字行 #{} '{}' 相邻行不匹配，附近内容：", index, describe(nameLine));
        for (int offset = -1; offset <= 3; offset++) {
            if (offset == 0) {
                continue;
            }
            int target = index + offset;
            if (target < 0 || target >= lines.size()) {
                continue;
            }
            String text = lines.get(target);
            Xp xp = parseXp(text.trim());
            GdragFlipMod.LOGGER.info("[petwidget]   {} '{}' -> {}", offset, describe(text),
                    xp == null ? "null" : "xp(current=" + xp.current() + ",max=" + xp.maxLevel() + ")");
        }
    }

    /** 调试用：把不可见字符转成可见标记。 */
    private static String describe(String text) {
        StringBuilder builder = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\u00A0' -> builder.append("<NBSP>");
                case '\u2007' -> builder.append("<FIGSP>");
                case '\u202F' -> builder.append("<NNBSP>");
                case '\u200B' -> builder.append("<ZWSP>");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        builder.append("<CTRL:").append((int) c).append('>');
                    } else if (c > 0x7E) {
                        builder.append(String.format("<U+%04X>", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.toString();
    }

    private static Snapshot toSnapshot(int level, String petName, Xp xp, boolean golden) {
        Snapshot snapshot;
        if (xp.maxLevel()) {
            long total = PetXpCurve.CAP + xp.overflow();
            snapshot = new Snapshot(true, golden, PetXpCurve.MAX_LEVEL, total, xp.overflow(), petName);
        } else {
            long total = PetXpCurve.expAtLevel(level) + xp.current();
            snapshot = new Snapshot(true, golden, level, total, total - PetXpCurve.expAtLevel(level), petName);
        }
        if (GdragConfig.get().debugVerbose) {
            GdragFlipMod.LOGGER.info("[petwidget] 精确读取：pet='{}' level={} exp={}", petName, level,
                    snapshot.totalExp());
        }
        return snapshot;
    }

    private static Xp xpAt(List<String> lines, int index) {
        if (index < 0 || index >= lines.size()) {
            return null;
        }
        return parseXp(lines.get(index).trim());
    }

    /**
     * 按 tab 显示顺序取出所有可见行文本（已去颜色码）。
     */
    public static List<String> lines(Minecraft client) {
        if (client.getConnection() == null) {
            return List.of();
        }

        List<Entry> entries = new ArrayList<>();
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            PlayerTeam team = info.getTeam();
            if (display == null) {
                display = Component.literal(info.getProfile().name());
            }
            if (team != null) {
                display = PlayerTeam.formatNameForTeam(team, display);
            }
            entries.add(new Entry(
                    info.getTabListOrder(),
                    info.getGameMode() == net.minecraft.world.level.GameType.SPECTATOR,
                    team == null ? "" : team.getName(),
                    info.getProfile().name(),
                    stripColors(display.getString())));
        }

        entries.sort(Comparator.comparingInt(Entry::order).reversed()
                .thenComparing(Entry::spectator)
                .thenComparing(Entry::teamName)
                .thenComparing(Entry::profileName, String.CASE_INSENSITIVE_ORDER));

        List<String> lines = new ArrayList<>(Math.min(entries.size(), MAX_LINES));
        for (int i = 0; i < entries.size() && i < MAX_LINES; i++) {
            lines.add(entries.get(i).text());
        }
        return lines;
    }

    /** 调试用：带序号，并标记疑似宠物名字行 / 经验行。 */
    public static List<String> debugLines(Minecraft client) {
        List<String> lines = lines(client);
        List<String> result = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i);
            String marker = "";
            if (text.toLowerCase(Locale.ROOT).contains(TRACKED_PET.toLowerCase(Locale.ROOT))) {
                marker = "   <== 金龙名字行";
            } else if (parseXp(text.trim()) != null) {
                marker = "   <== 经验行?";
            }
            result.add(i + "| " + text + marker);
        }
        return result;
    }

    /** 从名字行里取等级：优先严格模式，失败则宽松找 "Lvl N"。 */
    private static Integer extractLevel(String text) {
        Matcher strict = NAME_PATTERN.matcher(text);
        if (strict.find()) {
            int level = parseInt(strict.group(1), 0);
            if (level > 0) {
                return level;
            }
        }
        Matcher loose = LEVEL_PATTERN.matcher(text);
        if (loose.find()) {
            int level = parseInt(loose.group(1), 0);
            if (level > 0) {
                return level;
            }
        }
        return null;
    }

    /** 宽松解析经验行：允许行内还有其他前后缀文本。 */
    private static Xp parseXp(String rawText) {
        String text = normalizeSpaces(
                Normalizer.normalize(rawText, Normalizer.Form.NFKC)).trim();
        if (text.equals("MAX LEVEL") || text.equals("MAX LEVEL!")) {
            return new Xp(0L, true, 0L);
        }

        // 注意：matches() 之后不能再对同一个 Matcher 调 find()（会从匹配末尾继续找，必然失败）。
        Matcher progress = PROGRESS_PATTERN.matcher(text);
        if (progress.matches()) {
            return new Xp(parseNumber(progress.group(1)), false, 0L);
        }
        Matcher looseProgress = PROGRESS_LOOSE_PATTERN.matcher(text);
        if (looseProgress.find()) {
            return new Xp(parseNumber(looseProgress.group(1)), false, 0L);
        }

        if (text.contains("MAX LEVEL")) {
            return new Xp(0L, true, 0L);
        }

        Matcher overflow = OVERFLOW_PATTERN.matcher(text);
        if (overflow.matches()) {
            return new Xp(0L, true, parseNumber(overflow.group(1)));
        }
        Matcher looseOverflow = OVERFLOW_LOOSE_PATTERN.matcher(text);
        if (looseOverflow.find()) {
            return new Xp(0L, true, parseNumber(looseOverflow.group(1)));
        }
        return null;
    }

    /** 解析 "179.7k" / "1.4M" / "33,915" 这类数值。 */
    private static long parseNumber(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0L;
        }
        String value = raw.replace(",", "").trim();
        double multiplier = 1.0;
        char last = value.charAt(value.length() - 1);
        if (last == 'k' || last == 'K') {
            multiplier = 1_000.0;
            value = value.substring(0, value.length() - 1);
        } else if (last == 'm' || last == 'M') {
            multiplier = 1_000_000.0;
            value = value.substring(0, value.length() - 1);
        }
        try {
            return Math.round(Double.parseDouble(value) * multiplier);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String stripColors(String text) {
        // NFKC 会把全角字符、各种 Unicode 空白（U+2000~U+200A、U+00A0 等）归一成 ASCII。
        String normalized = Normalizer.normalize(text.replaceAll("§.", ""), Normalizer.Form.NFKC);
        return normalizeSpaces(normalized);
    }

    /** Hypixel 偶尔用不换行空格做对齐，统一成普通空格，避免正则匹配失败。 */
    private static String normalizeSpaces(String text) {
        return text.replace('\u00A0', ' ')
                .replace('\u2007', ' ')
                .replace('\u202F', ' ')
                .replace('\u200B', ' ')
                .replace('\u3000', ' ');
    }

    private record Entry(int order, boolean spectator, String teamName, String profileName, String text) {
    }

    private record Xp(long current, boolean maxLevel, long overflow) {
    }
}
