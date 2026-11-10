package net.gdragflip.leveling;

import net.minecraft.client.Minecraft;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * 地牢判定：与 NoammAddons 一致，只看**侧边栏计分板**文本。
 *
 * <p>Hypixel 进入地牢后侧边栏会出现 {@code The Catacombs (F4)} / {@code (M7)} 这样的行；
 * 排队提示里也会出现同名文本，所以必须排除包含 {@code Queue} 的行。</p>
 *
 * <p>退出判定：侧边栏里不再有该行（换世界、结算、回大厅）即视为出本。</p>
 */
public final class DungeonDetector {
    private static final String MARKER = "The Catacombs (";
    private static final int POLL_TICKS = 5;

    private static volatile boolean inDungeon;
    private static volatile String floor = "";
    private static int cooldown;

    private DungeonDetector() {
    }

    public static boolean inDungeon() {
        return inDungeon;
    }

    /** 楼层字符串，如 {@code F4} / {@code M7}；不在本内为空串。 */
    public static String floor() {
        return floor;
    }

    public static void reset() {
        inDungeon = false;
        floor = "";
    }

    public static void tick(Minecraft client) {
        if (cooldown-- > 0) {
            return;
        }
        cooldown = POLL_TICKS;

        if (client.level == null) {
            reset();
            return;
        }

        Scoreboard scoreboard = client.level.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) {
            reset();
            return;
        }

        String detected = null;
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
            String line = lineOf(scoreboard, entry);
            int index = line.indexOf(MARKER);
            if (index < 0 || line.contains("Queue")) {
                continue;
            }
            int end = line.indexOf(')', index);
            detected = end > index ? line.substring(index + MARKER.length(), end) : "";
            break;
        }

        if (detected != null) {
            inDungeon = true;
            floor = detected;
        } else {
            reset();
        }
    }

    /** 侧边栏一行的完整文本（队伍 prefix + 行内容 + suffix，去掉颜色码）。 */
    private static String lineOf(Scoreboard scoreboard, PlayerScoreEntry entry) {
        String text = entry.ownerName().getString();
        PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
        if (team != null) {
            text = team.getPlayerPrefix().getString() + text + team.getPlayerSuffix().getString();
        }
        return text.replaceAll("§.", "");
    }
}
