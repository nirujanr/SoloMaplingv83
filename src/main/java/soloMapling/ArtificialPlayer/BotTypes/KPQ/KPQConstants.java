package soloMapling.ArtificialPlayer.BotTypes.KPQ;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Map / item / NPC constants for the Kerning Party Quest bots.
 * <p>
 * Sources: scripts/event/KerningPQ.js, scripts/npc/9020001.js (Cloto) and
 * wz/Map.wz/Map/Map1/1030008xx.img.xml. The stage areas below MUST match the
 * Rectangles in 9020001.js — that script is the judge of "standing on it".
 */
public final class KPQConstants {

    private KPQConstants() {}

    // ---- Maps ---------------------------------------------------------------
    public static final int KERNING_CITY = 103000000; // recruit map (Lakelis)
    public static final int STAGE_1      = 103000800; // coupons (Ligators)
    public static final int STAGE_2      = 103000801; // ropes
    public static final int STAGE_3      = 103000802; // platforms
    public static final int STAGE_4      = 103000803; // barrels
    public static final int STAGE_5      = 103000804; // bosses -> 10 passes
    public static final int BONUS        = 103000805; // bonus hunting stage
    public static final int EXIT_MAP     = 103000890; // "on the way out"

    /** A KPQ bot with no party/leader for this long is removed from the server. */
    public static final long IDLE_DESPAWN_MS = 10 * 60_000L;

    public static boolean isInsideMap(int mapId) {
        return mapId >= STAGE_1 && mapId <= BONUS;
    }

    /** Maps where KPQ bots stick to their leader. Outside of these they idle in place. */
    public static boolean isKpqZone(int mapId) {
        return mapId == KERNING_CITY || mapId == EXIT_MAP || isInsideMap(mapId);
    }

    public static int stageOf(int mapId) {
        return isInsideMap(mapId) ? mapId - STAGE_1 + 1 : -1; // 1..6 (6 = bonus)
    }

    // ---- NPCs / mobs / items ------------------------------------------------
    public static final int CLOTO    = 9020001;
    public static final int LIGATOR  = 9300001;
    public static final int COUPON   = 4001007;
    public static final int PASS     = 4001008;
    public static final int STAGE_5_PASSES_NEEDED = 10;

    public static final int MIN_LEVEL = 21;
    public static final int MAX_LEVEL = 30;

    /** Cloto's spot per stage (index = stage 1..5), from the map XML. */
    public static Point clotoPos(int stage) {
        return switch (stage) {
            case 1 -> new Point(592, 106);
            case 2 -> new Point(-348, 90);
            case 3 -> new Point(1186, -75);
            case 4 -> new Point(1147, -75);
            case 5 -> new Point(305, -2715);
            default -> null;
        };
    }

    // ---- Stage 1 questions --------------------------------------------------
    // Same order as stage1Questions / stage1Answers in 9020001.js.
    public static final String[] QUESTION_TOPICS = {
            "lvl for 1st job warrior",
            "STR for 1st job warrior",
            "INT for 1st job magician",
            "DEX for 1st job bowman",
            "DEX for 1st job thief",
            "lvl for 2nd job",
            "lvl for 1st job magician"
    };
    public static final int[] QUESTION_ANSWERS = {10, 35, 20, 25, 25, 30, 8};

    // ---- Stages 2-4: answer areas (copied from 9020001.js) ---------------------
    public static final Rectangle[] STAGE_2_ROPES = {
            new Rectangle(-755, -132, 4, 218), new Rectangle(-721, -340, 4, 166),
            new Rectangle(-586, -326, 4, 150), new Rectangle(-483, -181, 4, 222)
    };
    public static final Rectangle[] STAGE_3_PLATFORMS = {
            new Rectangle(608, -180, 140, 50), new Rectangle(791, -117, 140, 45),
            new Rectangle(958, -180, 140, 50), new Rectangle(876, -238, 140, 45),
            new Rectangle(702, -238, 140, 45)
    };
    public static final Rectangle[] STAGE_4_BARRELS = {
            new Rectangle(910, -236, 35, 5), new Rectangle(877, -184, 35, 5),
            new Rectangle(946, -184, 35, 5), new Rectangle(845, -132, 35, 5),
            new Rectangle(910, -132, 35, 5), new Rectangle(981, -132, 35, 5)
    };

    /** Answer areas for a puzzle stage (2-4), else null. */
    public static Rectangle[] puzzleAreas(int stage) {
        return switch (stage) {
            case 2 -> STAGE_2_ROPES;
            case 3 -> STAGE_3_PLATFORMS;
            case 4 -> STAGE_4_BARRELS;
            default -> null;
        };
    }

    public static String puzzleNoun(int stage) {
        return switch (stage) {
            case 2 -> "rope";
            case 3 -> "platform";
            case 4 -> "barrel";
            default -> "spot";
        };
    }

    /** Where idle bots wait on a puzzle stage: next to Cloto, clear of every answer area. */
    public static Point puzzleWaitPos(int stage) {
        return switch (stage) {
            case 2 -> new Point(-300, 90);
            case 3 -> new Point(1120, -75);
            case 4 -> new Point(1090, -75);
            default -> null;
        };
    }

    /**
     * Every "exactly 3 of N" combination, in the same lexicographic order Cloto's
     * script lists them (stage2Combos / stage3Combos / stage4Combos are all C(N,3)).
     */
    public static List<int[]> threeOf(int n) {
        List<int[]> out = new ArrayList<>();
        for (int a = 0; a < n; a++)
            for (int b = a + 1; b < n; b++)
                for (int c = b + 1; c < n; c++)
                    out.add(new int[]{a, b, c});
        return out;
    }

    // ---- Tuning -------------------------------------------------------------
    public static final long FAST_TICK_MS          = 250;    // bot brain + combat cadence (same as TrainingBot's combat ticker)
    public static final long ORCH_TICK_MS          = 500;    // orchestrator cadence
    public static final int  LOOT_RANGE_PX         = 160;    // coupon / pass pickup radius
    public static final long LOOT_SETTLE_MS        = 500;    // let the drop land before grabbing
    public static final int  NPC_TALK_RANGE_PX     = 180;
    public static final long NPC_WALK_TIMEOUT_MS   = 22_000; // give up walking and hop next to Cloto
    public static final long SPOT_SNAP_TIMEOUT_MS  = 6_000;  // platform/barrel: not on the spot by then -> snap
    public static final long ROPE_WALK_TIMEOUT_MS  = 4_000;  // rope: grab on even if not at the foot yet
    public static final long HUNT_STUCK_MS         = 10_000; // no kill progress -> hop next to a mob
    public static final long HUNT_HOP_GAP_MS       = 8_000;  // min time between hops
    public static final int  FOLLOW_SLACK_PX       = 220;    // idle bots stay this close to the leader
    public static final long PASS_REMINDER_MS      = 20_000; // "I have my pass" nag interval (stage 1)
    public static final long COUPON_WATCH_MS       = 60_000; // after "d5", leader coupon drops count for this long
    public static final long COUPON_FETCH_TIMEOUT_MS = 9_000; // can't walk to a handed-off stack -> hop to it
}
