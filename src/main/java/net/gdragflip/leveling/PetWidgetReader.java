package net.gdragflip.leveling;

import net.gdragflip.analysis.PetXpCurve;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.PlayerTeam;

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
    private static final int MAX_LINES = 80;

    /** \u2726 是宠物皮肤标记 ✦，用转义写死避免源码编码问题。 */
    private static final Pattern NAME_PATTERN =
            Pattern.compile("\\[Lvl ([\\d,]+)]\\s*(?:\\[\\d+\u2726]\\s*)?([A-Za-z0-9 '_-]+?)(?: \u2726)?$");
    private static final Pattern PROGRESS_PATTERN =
            Pattern.compile("^([\\d,.]+[kKmM]?)/([\\d,.]+[kKmM]?) XP(?: \\(([\\d.]+)%\\))?$");
    private static final Pattern OVERFLOW_PATTERN = Pattern.compile("^\\+([\\d,.]+[kKmM]?) XP$");

    /** 宽松兜底：行里带 "Lvl N" / "a/b XP" / "+N XP" 即可。 */
    private static final Pattern LEVEL_PATTERN = Pattern.compile("Lvl\\s*([\\d,]+)");
    private static final Pattern PROGRESS_LOOSE_PATTERN =
            Pattern.compile("([\\d,.]+[kKmM]?)\\s*/\\s*([\\d,.]+[kKmM]?)\\s*XP");
    private static final Pattern OVERFLOW_LOOSE_PATTERN = Pattern.compile("\\+([\\d,.]+[kKmM]?)\\s*XP");

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

        for (int i = 0; i < lines.size(); i++) {
            String nameLine = lines.get(i).trim();
            // 宽松匹配：只要这一行提到金龙并且带等级即可（避免格式细节差异导致整块失效）。
            if (!nameLine.toLowerCase(Locale.ROOT).contains(TRACKED_PET.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Integer level = extractLevel(nameLine);
            if (level == null || level <= 0) {
                continue;
            }
            String petName = TRACKED_PET;

            // 经验行紧跟在名字行之后（可能夹着宠物道具行），也兼容上一行。
            for (int offset = 1; offset <= 3; offset++) {
                Xp xp = xpAt(lines, i + offset);
                if (xp == null) {
                    continue;
                }
                return toSnapshot(level, petName, xp);
            }
            Xp above = xpAt(lines, i - 1);
            if (above != null) {
                return toSnapshot(level, petName, above);
            }
        }
        return Snapshot.MISSING;
    }

    private static Snapshot toSnapshot(int level, String petName, Xp xp) {
        if (xp.maxLevel()) {
            long total = PetXpCurve.CAP + xp.overflow();
            return new Snapshot(true, true, PetXpCurve.MAX_LEVEL, total, xp.overflow(), petName);
        }
        long total = PetXpCurve.expAtLevel(level) + xp.current();
        return new Snapshot(true, true, level, total, total - PetXpCurve.expAtLevel(level), petName);
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

    /** 调试用：带序号与排序值的原始行。 */
    public static List<String> debugLines(Minecraft client) {
        List<String> lines = lines(client);
        List<String> result = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            result.add(i + "| " + lines.get(i));
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
    private static Xp parseXp(String text) {
        if (text.equals("MAX LEVEL") || text.equals("MAX LEVEL!")) {
            return new Xp(0L, true, 0L);
        }

        Matcher progress = PROGRESS_PATTERN.matcher(text);
        if (!progress.matches()) {
            progress = PROGRESS_LOOSE_PATTERN.matcher(text);
        }
        if (progress.find()) {
            return new Xp(parseNumber(progress.group(1)), false, 0L);
        }

        if (text.contains("MAX LEVEL")) {
            return new Xp(0L, true, 0L);
        }

        Matcher overflow = OVERFLOW_PATTERN.matcher(text);
        if (!overflow.matches()) {
            overflow = OVERFLOW_LOOSE_PATTERN.matcher(text);
        }
        if (overflow.find()) {
            return new Xp(0L, true, parseNumber(overflow.group(1)));
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
        return text.replaceAll("§.", "");
    }

    private record Entry(int order, boolean spectator, String teamName, String profileName, String text) {
    }

    private record Xp(long current, boolean maxLevel, long overflow) {
    }
}
