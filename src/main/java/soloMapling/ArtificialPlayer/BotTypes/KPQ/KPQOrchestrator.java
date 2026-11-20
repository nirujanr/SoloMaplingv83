package soloMapling.ArtificialPlayer.BotTypes.KPQ;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import scripting.event.EventInstanceManager;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.server.ExecutorServiceManager;

import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static soloMapling.BotLogger.log;

/**
 * Coordinator for KPQ bots (blackboard pattern, like OPQOrchestrator).
 * <p>
 * Owns what no single bot can decide alone:
 * - the fast 500ms tick that drives every registered {@link KPQBot}
 * - event-instance registration (Cloto counts passes as party size - 1, and the
 *   stage 2-4 check walks eim.getPlayers(), so bots MUST be registered)
 * - the stage 2-4 puzzle: which combination the party is trying, who stands where,
 *   and moving on to the next combination after Cloto's "wrong" effect
 * - routing the leader's chat ("drop pass", "drop coupons", "come", "wrong", "status")
 */
public final class KPQOrchestrator {

    private static final KPQOrchestrator INSTANCE = new KPQOrchestrator();

    public static KPQOrchestrator getInstance() {
        return INSTANCE;
    }

    private final List<KPQBot> bots = new CopyOnWriteArrayList<>();
    private final Map<String, Puzzle> puzzles = new ConcurrentHashMap<>();
    private ScheduledFuture<?> fastTick;
    private ScheduledFuture<?> orchTick;

    private KPQOrchestrator() {}

    private static void klog(String msg) {
        log("[KPQOrchestrator] " + msg);
    }

    // =========================================================================
    // Registration / ticking
    // =========================================================================

    public synchronized void registerBot(KPQBot bot) {
        if (bot != null && !bots.contains(bot)) {
            bots.add(bot);
            klog("Registered " + bot.getChr().getName());
        }
        ensureTicking();
    }

    public synchronized void unregisterBot(KPQBot bot) {
        bots.remove(bot);
        if (bots.isEmpty()) {
            if (fastTick != null) fastTick.cancel(false);
            if (orchTick != null) orchTick.cancel(false);
            fastTick = null;
            orchTick = null;
            puzzles.clear();
        }
    }

    public List<KPQBot> getBots() {
        return Collections.unmodifiableList(bots);
    }

    private void ensureTicking() {
        if (fastTick == null || fastTick.isDone()) {
            fastTick = ExecutorServiceManager.getScheduledExecutorService().scheduleWithFixedDelay(() -> {
                for (KPQBot bot : bots) {
                    try {
                        bot.fastTick();
                    } catch (Exception e) {
                        e.printStackTrace(); // one bot's error never stops the others
                    }
                }
            }, KPQConstants.FAST_TICK_MS, KPQConstants.FAST_TICK_MS, TimeUnit.MILLISECONDS);
        }
        if (orchTick == null || orchTick.isDone()) {
            orchTick = ExecutorServiceManager.getScheduledExecutorService().scheduleWithFixedDelay(() -> {
                try {
                    tick();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, KPQConstants.ORCH_TICK_MS, KPQConstants.ORCH_TICK_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void tick() {
        // 1) Make sure every partied bot inside a KPQ instance is registered with it.
        for (KPQBot bot : bots) {
            Character chr = bot.getChr();
            Character leader = leaderOf(chr);
            if (leader == null) continue;
            EventInstanceManager eim = leader.getEventInstance();
            if (eim == null || !KPQConstants.isInsideMap(leader.getMapId())) continue;
            if (chr.getEventInstance() != eim) {
                eim.registerPlayer(chr, false);
                klog("Registered " + chr.getName() + " into event instance " + eim.getName());
            }
        }

        // 2) Puzzle announcements: once every assigned bot stands on its spot, call the leader.
        for (Puzzle p : puzzles.values()) {
            if (p.eim.isEventCleared() || p.eim.getProperty(p.stage + "stageclear") != null) continue;
            if (p.readyAnnounced) continue;
            List<KPQBot> team = puzzleTeam(p);
            if (team.isEmpty()) continue;
            boolean allOn = true;
            for (int i = 0; i < team.size() && i < 3; i++) {
                if (!team.get(i).isOnAssignedSpot()) {
                    allOn = false;
                    break;
                }
            }
            if (allOn) {
                p.readyAnnounced = true;
                String msg = leaderSlots(p, team).isEmpty()
                        ? "We're in position! Leader, click Cloto to check."
                        : "In position! Leader, get on " + p.noun() + " " + slotList(leaderSlots(p, team))
                        + " and click Cloto.";
                say(team.get(0), msg);
            }
        }

        // 3) Stage 1 coupon hand-off.
        for (CouponWatch w : couponWatches.values()) {
            try {
                tickCouponWatch(w);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // =========================================================================
    // Stage 1 coupon hand-off: the leader drops coupons, says "d5" (or "5"), and the bots
    // that still need coupons pick the stacks up, keep what they need and pass the rest on.
    // =========================================================================

    private static final class CouponWatch {
        final Character leader;
        volatile long until;
        final long startedAt = System.currentTimeMillis();
        boolean sawStack = false;
        boolean warnedNone = false;
        final Set<MapItem> leftovers = ConcurrentHashMap.newKeySet();  // stacks bots passed on
        final Set<MapItem> spareAnnounced = ConcurrentHashMap.newKeySet();

        CouponWatch(Character leader) {
            this.leader = leader;
        }
    }

    private final Map<Integer, CouponWatch> couponWatches = new ConcurrentHashMap<>();

    private void startCouponWatch(Character leader) {
        CouponWatch w = couponWatches.compute(leader.getId(), (id, old) ->
                (old != null && old.leader == leader) ? old : new CouponWatch(leader));
        w.until = System.currentTimeMillis() + KPQConstants.COUPON_WATCH_MS;
        w.warnedNone = false;

        List<KPQBot> needy = needyBots(leader);
        if (needy.isEmpty()) {
            KPQBot any = anyBotNear(leader);
            if (any != null) ExecutorServiceManager.runAsync(() ->
                    say(any, pick("we're all set, nobody needs coupons", "all good, no one needs coupons")));
        }
    }

    /** Called by a bot that kept what it needed and dropped the rest. */
    void registerLeftover(Character leader, MapItem stack) {
        if (leader == null || stack == null) return;
        CouponWatch w = couponWatches.computeIfAbsent(leader.getId(), id -> new CouponWatch(leader));
        w.leftovers.add(stack);
        w.sawStack = true;
        w.until = Math.max(w.until, System.currentTimeMillis() + KPQConstants.COUPON_WATCH_MS);
    }

    private void tickCouponWatch(CouponWatch w) {
        Character leader = w.leader;
        long now = System.currentTimeMillis();
        w.leftovers.removeIf(MapItem::isPickedUp);
        if (leader.getMap() == null || leader.getMapId() != KPQConstants.STAGE_1
                || (now > w.until && w.leftovers.isEmpty())) {
            couponWatches.remove(leader.getId(), w);
            return;
        }

        List<KPQBot> stage1Bots = new ArrayList<>();
        for (KPQBot b : bots) {
            if (b.getChr().getMap() == leader.getMap()) stage1Bots.add(b);
        }
        if (stage1Bots.isEmpty()) return;

        // Stacks already being fetched.
        Set<MapItem> claimed = new HashSet<>();
        for (KPQBot b : stage1Bots) {
            MapItem c = b.getCouponClaim();
            if (c != null) claimed.add(c);
        }

        // Free stacks: the leader's own coupon drops (while the watch is open) + passed-on leftovers.
        List<MapItem> free = new ArrayList<>();
        for (MapObject o : leader.getMap().getMapObjectsInRange(leader.getPosition(), Double.POSITIVE_INFINITY,
                List.of(MapObjectType.ITEM))) {
            if (!(o instanceof MapItem mi) || mi.isPickedUp() || mi.getItemId() != KPQConstants.COUPON) continue;
            if (claimed.contains(mi)) continue;
            boolean leaderDrop = now <= w.until && mi.isPlayerDrop()
                    && mi.getDropper() instanceof Character d && d.getId() == leader.getId();
            if (leaderDrop || w.leftovers.contains(mi)) free.add(mi);
        }
        if (!free.isEmpty()) w.sawStack = true;
        free.sort(Comparator.comparingInt((MapItem mi) -> stackQty(mi)).reversed());

        List<KPQBot> needy = needyBots(leader);
        for (MapItem stack : free) {
            if (needy.isEmpty()) break;
            KPQBot taker = needy.remove(0); // needs the most
            taker.assignCouponClaim(stack, stackQty(stack));
        }

        if (needy.isEmpty() && claimed.isEmpty()) {
            for (MapItem stack : free) {
                if (w.spareAnnounced.add(stack)) {
                    int q = stackQty(stack);
                    KPQBot speaker = stage1Bots.get(0);
                    say(speaker, "All set! " + q + " spare coupon" + (q == 1 ? "" : "s") + " on the floor.");
                }
            }
        }

        if (!w.sawStack && !w.warnedNone && now - w.startedAt > 5_000 && !needyBots(leader).isEmpty()) {
            w.warnedNone = true;
            say(stage1Bots.get(0), "I don't see your coupons on the floor yet - drop them and say the number again");
        }
    }

    /** Stage 1 bots on the leader's map that still need coupons and aren't fetching, most-needed first. */
    private List<KPQBot> needyBots(Character leader) {
        List<KPQBot> out = new ArrayList<>();
        for (KPQBot b : bots) {
            if (b.getChr().getMap() == leader.getMap() && b.couponsStillNeeded() > 0
                    && b.getCouponClaim() == null) {
                out.add(b);
            }
        }
        out.sort(Comparator.comparingInt(KPQBot::couponsStillNeeded).reversed());
        return out;
    }

    private KPQBot anyBotNear(Character leader) {
        for (KPQBot b : bots) {
            if (b.getChr().getMap() == leader.getMap()) return b;
        }
        return null;
    }

    static int stackQty(MapItem mi) {
        return mi.getItem() != null ? Math.max(1, mi.getItem().getQuantity()) : 1;
    }

    private static String pick(String... options) {
        return options[ThreadLocalRandom.current().nextInt(options.length)];
    }

    // =========================================================================
    // Party / leader helpers
    // =========================================================================

    /** The live leader Character of this bot's party, or null. */
    public static Character leaderOf(Character chr) {
        if (chr == null) return null;
        Party party = chr.getParty();
        if (party == null) return null;
        PartyCharacter lpc = party.getLeader();
        if (lpc == null) return null;
        Character leader = lpc.getPlayer();
        if (leader == null || leader.getId() == chr.getId()) return null;
        return leader;
    }

    // =========================================================================
    // Stages 2-4 puzzle
    // =========================================================================

    private static final class Puzzle {
        final EventInstanceManager eim;
        final int stage;
        final List<int[]> order;
        volatile int idx = 0;
        volatile int version = 0;
        volatile boolean readyAnnounced = false;

        Puzzle(EventInstanceManager eim, int stage) {
            this.eim = eim;
            this.stage = stage;
            this.order = KPQConstants.threeOf(KPQConstants.puzzleAreas(stage).length);
            Collections.shuffle(order); // try combinations in a random order, like real players
        }

        int[] current() {
            return order.get(idx % order.size());
        }

        String noun() {
            return KPQConstants.puzzleNoun(stage) + (current().length > 1 ? "s" : "");
        }
    }

    private static String key(EventInstanceManager eim, int stage) {
        return eim.getName() + "#" + stage;
    }

    private Puzzle puzzle(EventInstanceManager eim, int stage) {
        if (eim == null || KPQConstants.puzzleAreas(stage) == null) return null;
        // An instance name is reused by the next run ("Kerning0"), so drop stale entries.
        String k = key(eim, stage);
        Puzzle p = puzzles.get(k);
        if (p != null && p.eim != eim) {
            puzzles.remove(k);
            p = null;
        }
        if (p == null) {
            p = new Puzzle(eim, stage);
            puzzles.put(k, p);
            final Puzzle fp = p;
            ExecutorServiceManager.runAsync(() -> announceAttempt(fp, false));
        }
        return p;
    }

    /** Bots on the puzzle map for this instance, stable order (by character id). */
    private List<KPQBot> puzzleTeam(Puzzle p) {
        int mapId = KPQConstants.STAGE_1 + p.stage - 1;
        List<KPQBot> team = new ArrayList<>();
        for (KPQBot b : bots) {
            Character c = b.getChr();
            if (c.getMapId() == mapId && c.getEventInstance() == p.eim) team.add(b);
        }
        team.sort(Comparator.comparingInt(b -> b.getChr().getId()));
        return team;
    }

    private List<Integer> leaderSlots(Puzzle p, List<KPQBot> team) {
        int[] combo = p.current();
        List<Integer> out = new ArrayList<>();
        for (int i = team.size(); i < combo.length; i++) out.add(combo[i]);
        return out;
    }

    /** Area index this bot should stand on, or -1 to stay off every area. */
    public int assignedSpot(KPQBot bot, EventInstanceManager eim, int stage) {
        Puzzle p = puzzle(eim, stage);
        if (p == null) return -1;
        List<KPQBot> team = puzzleTeam(p);
        int i = team.indexOf(bot);
        int[] combo = p.current();
        return (i >= 0 && i < combo.length) ? combo[i] : -1;
    }

    /** Changes every time the party moves on to a new combination. */
    public int puzzleVersion(EventInstanceManager eim, int stage) {
        Puzzle p = puzzle(eim, stage);
        return p == null ? -1 : p.version;
    }

    /** Hook from EventInstanceManager.showWrongEffect — Cloto said "wrong". */
    public static void onWrongEffect(EventInstanceManager eim, int mapId) {
        if (eim == null || INSTANCE.bots.isEmpty()) return;
        int stage = KPQConstants.stageOf(mapId);
        if (KPQConstants.puzzleAreas(stage) == null) return;
        Puzzle p = INSTANCE.puzzles.get(key(eim, stage));
        if (p == null || p.eim != eim) return;
        if (!p.readyAnnounced) {
            // Checked before everyone was on their spot: this combination wasn't really tested, keep it.
            List<KPQBot> team = INSTANCE.puzzleTeam(p);
            if (!team.isEmpty()) {
                ExecutorServiceManager.runAsync(() -> say(team.get(0),
                        "Wait, we're not in position yet! I'll say when we're ready."));
            }
            return;
        }
        INSTANCE.advance(p, "Cloto says wrong");
    }

    private void advance(Puzzle p, String why) {
        p.idx++;
        p.version++;
        p.readyAnnounced = false;
        klog("Puzzle stage " + p.stage + " -> attempt " + (p.idx + 1) + "/" + p.order.size() + " (" + why + ")");
        ExecutorServiceManager.runAsync(() -> announceAttempt(p, true));
    }

    private void announceAttempt(Puzzle p, boolean afterWrong) {
        BotHelpers.blockingSleep(afterWrong ? 400 : 800);
        List<KPQBot> team = puzzleTeam(p);
        if (team.isEmpty()) return;
        String prefix = afterWrong ? "Not that one. " : "";
        String msg = prefix + "Trying " + p.noun() + " " + slotList(toList(p.current()))
                + " (" + (p.idx % p.order.size() + 1) + "/" + p.order.size() + ")";
        say(team.get(0), msg);
        List<Integer> mine = leaderSlots(p, team);
        if (!mine.isEmpty()) {
            say(team.get(0), "Leader, you take " + KPQConstants.puzzleNoun(p.stage) + " " + slotList(mine) + "!");
        }
    }

    private static List<Integer> toList(int[] a) {
        List<Integer> l = new ArrayList<>();
        for (int x : a) l.add(x);
        return l;
    }

    // Areas are numbered left -> right for humans, regardless of the script's index order.
    private static String slotList(List<Integer> slots) {
        StringBuilder sb = new StringBuilder();
        for (int s : slots) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s + 1);
        }
        return sb.toString();
    }

    public static Rectangle area(int stage, int spot) {
        Rectangle[] areas = KPQConstants.puzzleAreas(stage);
        return (areas == null || spot < 0 || spot >= areas.length) ? null : areas[spot];
    }

    // =========================================================================
    // Leader chat
    // =========================================================================

    public enum Command { DROP_PASS, DROP_COUPONS, COME, WRONG, STATUS, LEADER_COUPONS }

    /** Hook from the general + party chat handlers. Cheap no-op while no KPQ bots exist. */
    public static void onPlayerChat(Character sender, String text) {
        if (sender == null || text == null || INSTANCE.bots.isEmpty() || BotHelpers.isBot(sender)) return;
        Command cmd = parse(text);
        if (cmd == null || sender.getParty() == null) return;
        int partyId = sender.getParty().getId();

        List<KPQBot> mine = new ArrayList<>();
        for (KPQBot b : INSTANCE.bots) {
            Party p = b.getChr().getParty();
            if (p != null && p.getId() == partyId) mine.add(b);
        }
        if (mine.isEmpty()) return;

        if (cmd == Command.LEADER_COUPONS) {
            // Only meaningful in stage 1; a bare number anywhere else is just chat.
            if (sender.getMapId() == KPQConstants.STAGE_1) INSTANCE.startCouponWatch(sender);
            return;
        }
        if (cmd == Command.WRONG) {
            EventInstanceManager eim = sender.getEventInstance();
            int stage = KPQConstants.stageOf(sender.getMapId());
            Puzzle p = (eim != null) ? INSTANCE.puzzles.get(key(eim, stage)) : null;
            if (p != null && p.eim == eim) INSTANCE.advance(p, "leader said wrong");
            return;
        }
        for (KPQBot b : mine) {
            ExecutorServiceManager.runAsync(() -> {
                // stagger replies so the party doesn't answer in one frame
                BotHelpers.blockingSleep(100 + ThreadLocalRandom.current().nextInt(300));
                b.onLeaderCommand(cmd, sender);
            });
        }
    }

    static Command parse(String raw) {
        String t = raw.toLowerCase(Locale.ROOT).trim();
        // Leader handing coupons to the bots: "d5", "d 5", "5", "dropped 5 coupons",
        // "i have extra coupons", "coupons at the bottom". Checked before "drop coupons"
        // (which asks the bots to drop THEIR coupons).
        if (t.matches("d\\s*\\d{1,3}") || t.matches("\\d{1,3}")) return Command.LEADER_COUPONS;
        if ((t.contains("coupon") || t.contains("ticket"))
                && (t.contains("dropped") || t.contains("extra") || t.contains("bottom") || t.contains("i have")
                || t.contains("for you") || t.contains("here are") || t.contains("take"))) {
            return Command.LEADER_COUPONS;
        }
        boolean drop = t.contains("drop") || t.contains("give") || t.contains("gimme") || t.contains("hand");
        if (t.contains("coupon") && (drop || t.equals("coupons"))) return Command.DROP_COUPONS;
        if (t.contains("pass") && (drop || t.equals("pass") || t.equals("passes") || t.startsWith("pass "))) {
            return Command.DROP_PASS;
        }
        if (t.equals("come") || t.startsWith("come ") || t.contains("come here") || t.equals("follow")
                || t.contains("follow me") || t.equals("here")) return Command.COME;
        if (t.equals("wrong") || t.contains("try again") || t.contains("next combo") || t.equals("next")) {
            return Command.WRONG;
        }
        if (t.equals("status") || t.equals("report") || t.contains("how many")) return Command.STATUS;
        return null;
    }

    static void say(KPQBot bot, String msg) {
        SocialCommands.BotSpeak(bot.getChr(), msg);
    }
}
