package net.gdragflip.leveling;

import net.gdragflip.analysis.PetXpCurve;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.PlayerTeam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * 这是客户端唯一能「实时」拿到宠物等级与经验进度的来源（菜单 NBT 只在打开界面时才有）。</p>
 */
public final class PetWidgetReader {
    private static final String TRACKED_PET = "Golden Dragon";

    private static final Pattern NAME_PATTERN =
            Pattern.compile("\\[Lvl ([\\d,]+)]\\s*(?:\\[\\d+✦]\\s*)?([A-Za-z0-9 '_-]+?)(?: ✦)?$");
    private static final Pattern PROGRESS_PATTERN =
            Pattern.compile("^([\\d,.]+[kKmM]?)/([\\d,.]+[kKmM]?) XP(?: \\(([\\d.]+)%\\))?$");
    private static final Pattern OVERFLOW_PATTERN = Pattern.compile("^\\+([\\d,.]+[kKmM]?) XP$");

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
        if (client.getConnection() == null) {
            return Snapshot.MISSING;
        }

        List<Line> lines = new ArrayList<>();
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                PlayerTeam team = info.getTeam();
                Component name = Component.literal(info.getProfile().name());
                display = team == null ? name : PlayerTeam.formatNameForTeam(team, name);
            }
            lines.add(new Line(info.getTabListOrder(), stripColors(display.getString())));
        }
        lines.sort(Comparator.comparingInt(Line::order));

        for (int i = 0; i < lines.size(); i++) {
            Matcher nameMatcher = NAME_PATTERN.matcher(lines.get(i).text().trim());
            if (!nameMatcher.find()) {
                continue;
            }
            String petName = nameMatcher.group(2).trim();
            if (!TRACKED_PET.equalsIgnoreCase(petName)) {
                continue;
            }
            int level = parseInt(nameMatcher.group(1), 0);
            if (level <= 0) {
                continue;
            }

            // 经验行紧跟在名字行之后（最多隔一行，例如中间夹着宠物道具）。
            for (int j = i + 1; j <= Math.min(i + 2, lines.size() - 1); j++) {
                Xp xp = parseXp(lines.get(j).text().trim());
                if (xp == null) {
                    continue;
                }
                if (xp.maxLevel()) {
                    long total = PetXpCurve.CAP + xp.overflow();
                    return new Snapshot(true, true, PetXpCurve.MAX_LEVEL, total, xp.overflow(), petName);
                }
                long total = PetXpCurve.expAtLevel(level) + xp.current();
                return new Snapshot(true, true, level, total, total - PetXpCurve.expAtLevel(level), petName);
            }
        }
        return Snapshot.MISSING;
    }

    private static Xp parseXp(String text) {
        if (text.equals("MAX LEVEL") || text.equals("MAX LEVEL!")) {
            return new Xp(0L, true, 0L);
        }
        Matcher progress = PROGRESS_PATTERN.matcher(text);
        if (progress.matches()) {
            return new Xp(parseNumber(progress.group(1)), false, 0L);
        }
        Matcher overflow = OVERFLOW_PATTERN.matcher(text);
        if (overflow.matches()) {
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

    private record Line(int order, String text) {
    }

    private record Xp(long current, boolean maxLevel, long overflow) {
    }
}
