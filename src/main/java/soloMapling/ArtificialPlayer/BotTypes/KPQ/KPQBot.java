package soloMapling.ArtificialPlayer.BotTypes.KPQ;

import client.Character;
import client.inventory.Item;
import scripting.event.EventInstanceManager;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindBrain;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotLogic;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementPacketConstructor;
import constants.game.CharacterStance;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyLogic;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQOrchestrator.Command;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static soloMapling.BotLogger.log;

/**
 * Kerning Party Quest party-member bot. The human is the party leader; bots are members.
 * <p>
 * Driven by {@link KPQOrchestrator}'s 500ms tick ({@link #fastTick()}), perception-first:
 * every tick the bot looks at where its leader is, which stage map it is on and what the
 * event instance says, and does the one thing that stage needs:
 * <ul>
 *   <li>Leader on another KPQ map -> follow-warp next to the leader.</li>
 *   <li>Stage 1 -> walk to Cloto, get a question, hunt Ligators until it holds exactly the
 *       answer in coupons (extras are dropped), trade them for a pass, then hold the pass
 *       until the leader says "drop pass".</li>
 *   <li>Stages 2-4 -> stand on the rope / platform / barrel the orchestrator assigns;
 *       a "wrong" from Cloto moves everyone to the next combination.</li>
 *   <li>Stage 5 -> hunt the bosses, keep the passes they drop, hand them over on "drop pass".</li>
 *   <li>Bonus stage -> just hunt.</li>
 * </ul>
 * Bot pickups are cosmetic in this framework (nothing lands in a bot's inventory), so the
 * bot keeps its own coupon / pass counts and spawns real items for the leader when asked.
 */
public class KPQBot extends BotSM {

    private final KPQOrchestrator orchestrator = KPQOrchestrator.getInstance();
    private final GrindBrain grind = new GrindBrain(msg -> { });
    private final Random rng = new Random();

    // set while an async warp / hand-over runs; the fast tick skips the bot meanwhile
    private volatile boolean busy = false;
    private MapleMap lastMap;
    private long idleSince = 0; // when this bot lost its party/leader (0 = has one)

    // ---- per-run state ----
    private volatile int question = -1;   // index into QUESTION_ANSWERS, -1 = not asked yet
    private volatile int coupons = 0;     // coupons "in the bag"
    private volatile boolean hasPass = false; // stage 1 pass
    private volatile boolean stage1Done = false; // pass handed to the leader: never ask Cloto again
    private volatile MapItem couponClaim;          // a coupon stack the leader handed us (orchestrator-assigned)
    private long couponClaimAt;
    private volatile int passes = 0;      // stage 5 boss passes
    private long lastReminderAt = 0;
    private int lastReportedCoupons = -1;

    // ---- hunting ----
    private boolean grinding = false;
    private long lastHopAt = 0;

    // ---- walking ----
    private Point walkTarget;
    private long walkStartedAt;

    // ---- puzzle (stages 2-4) ----
    private int seenVersion = Integer.MIN_VALUE;
    private int mySpot = -1;
    private int myStage = -1;
    private long spotMoveAt;
    private boolean spotSnapped;

    public KPQBot(Character character) {
        super(character);
        botType = "KPQBot";
        dialoguePath = "SocialBotDialogue.yaml";
        orchestrator.registerBot(this);
    }

    private void klog(String msg) {
        log("[KPQBot " + getChr().getName() + "] " + msg);
    }

    // =========================================================================
    // Macro tick (2-6s, BotSM wheel): party invites only. All real work is in fastTick.
    // =========================================================================

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) return;
        if (getChr().getParty() == null && BotPartyLogic.checkPartyQueue(getChr())) {
            SocialCommands.BotSpeak(getChr(), pick("hi!", "ty for the inv", "kpq let's go", "hey"));
        }
    }

    @Override
    public void processMessages() {
        // Leader chat is routed directly by KPQOrchestrator.onPlayerChat.
    }

    @Override
    public void displayCommands(Character chr) {
        SocialCommands.displayPlayerChatCommands(chr,
                List.of("drop pass", "drop coupons", "come", "wrong", "status"));
    }

    @Override
    public synchronized void stopScheduledTask() {
        stopHunt();
        orchestrator.unregisterBot(this);
        BotAttackDriver.clearBot(getChr().getId());
        GCMovement.disable(getChr());
        super.stopScheduledTask();
    }

    // =========================================================================
    // Fast tick (500ms, orchestrator)
    // =========================================================================

    void fastTick() {
        if (!getRunning() || busy) return;
        Character chr = getChr();
        if (chr.getMap() == null) return;

        Character leader = KPQOrchestrator.leaderOf(chr);
        if (chr.getParty() == null || leader == null || leader.getMap() == null) {
            stopHunt();
            if (KPQConstants.isInsideMap(chr.getMapId()) || chr.getMapId() == KPQConstants.EXIT_MAP) {
                warpTo(townMap(), new Point(-100, 150), "lost my party");
                return;
            }
            // Wait in Kerning City for the next Solo party; give up after 10 idle minutes.
            long now = System.currentTimeMillis();
            if (idleSince == 0) {
                idleSince = now;
            } else if (now - idleSince > KPQConstants.IDLE_DESPAWN_MS) {
                busy = true;
                klog("idle " + (KPQConstants.IDLE_DESPAWN_MS / 60000) + " min with no party - removing");
                ExecutorServiceManager.runAsync(() -> KPQSoloParty.removeBot(this));
            }
            return;
        }
        idleSince = 0;

        // Follow the leader between KPQ maps (instances are separate MapleMap objects, so compare maps).
        if (KPQConstants.isKpqZone(leader.getMapId()) && leader.getMap() != chr.getMap()) {
            warpTo(leader.getMap(), leader.getPosition(), "following leader to " + leader.getMapId());
            return;
        }
        // Leader walked out of the KPQ area while we're stuck inside an instance: go home.
        if (KPQConstants.isInsideMap(chr.getMapId()) && !KPQConstants.isKpqZone(leader.getMapId())) {
            warpTo(townMap(), new Point(-100, 150), "leader left the KPQ area");
            return;
        }

        if (chr.getMap() != lastMap) {
            onEnterMap(lastMap, chr.getMap());
            lastMap = chr.getMap();
        }

        int stage = KPQConstants.stageOf(chr.getMapId());
        switch (stage) {
            case 1 -> stage1(leader);
            case 2, 3, 4 -> puzzle(leader, stage);
            case 5 -> stage5(leader);
            case 6 -> bonus(leader);
            default -> {
                stopHunt();
                if (KPQConstants.isKpqZone(chr.getMapId())) idleNear(leader);
            }
        }
    }

    private void onEnterMap(MapleMap from, MapleMap to) {
        int toId = to.getId();
        boolean fromInside = from != null && KPQConstants.isInsideMap(from.getId());
        stopHunt();
        GCMovement.setRestHold(getChr(), false);
        walkTarget = null;
        seenVersion = Integer.MIN_VALUE;
        mySpot = -1;
        myStage = -1;
        // A fresh run (entering stage 1 from outside) or going home wipes per-run state.
        if ((toId == KPQConstants.STAGE_1 && !fromInside) || !KPQConstants.isInsideMap(toId)) {
            resetRun();
        }
        klog("entered map " + toId);
    }

    private void resetRun() {
        question = -1;
        coupons = 0;
        hasPass = false;
        stage1Done = false;
        passes = 0;
        lastReminderAt = 0;
        lastReportedCoupons = -1;
    }

    // =========================================================================
    // Stage 1: question -> coupons -> pass
    // =========================================================================

    private void stage1(Character leader) {
        Character chr = getChr();
        EventInstanceManager eim = chr.getEventInstance();
        if (eim != null && eim.getProperty("1stageclear") != null) {
            stopHunt();
            idleNear(leader);
            return;
        }

        Point cloto = KPQConstants.clotoPos(1);
        if (hasPass || stage1Done) {
            // Done with Cloto: wait at the bottom next to her (don't trail the leader up the map),
            // and keep reminding the leader until the pass is handed over.
            stopHunt();
            stayNear(cloto);
            long now = System.currentTimeMillis();
            if (hasPass && now - lastReminderAt > KPQConstants.PASS_REMINDER_MS) {
                lastReminderAt = now;
                say(pick("Leader, I have my pass! Say 'drop pass'",
                        "pass ready - say 'drop pass' and I'll give it to you",
                        "still holding my pass for you, leader",
                        "got my pass, waiting by Cloto"));
            }
            return;
        }

        if (question < 0) {
            stopHunt();
            if (walkTo(cloto)) {
                question = rng.nextInt(KPQConstants.QUESTION_ANSWERS.length);
                if (eim != null) eim.gridInsert(chr, question + 1);
                int need = KPQConstants.QUESTION_ANSWERS[question];
                say("Cloto asked me the " + KPQConstants.QUESTION_TOPICS[question] + " -> " + need
                        + " coupons. Hunting!");
                klog("question " + question + " answer " + need);
            }
            return;
        }

        int need = KPQConstants.QUESTION_ANSWERS[question];
        if (couponClaim != null) {
            fetchCouponClaim(leader, need);
            return;
        }
        coupons += pickUpFromMobs(KPQConstants.COUPON);
        if (coupons != lastReportedCoupons && coupons > 0 && coupons < need) {
            lastReportedCoupons = coupons;
            SocialCommands.BotChatbubble(chr, "Coupons " + coupons + "/" + need);
        }

        if (coupons < need) {
            hunt();
            return;
        }

        stopHunt();
        if (coupons > need) {
            int extra = coupons - need;
            coupons = need;
            orchestrator.registerLeftover(leader, dropFor(leader, KPQConstants.COUPON, extra, chr.getPosition()));
            say("Got " + extra + " too many, " + extra + " coupon" + (extra == 1 ? "" : "s") + " left over");
        }
        if (walkTo(cloto)) {
            coupons = 0;
            hasPass = true;
            lastReminderAt = System.currentTimeMillis();
            if (eim != null) eim.gridInsert(chr, 0);
            say("Right answer, got my pass! Say 'drop pass' when you want it.");
            klog("stage 1 pass earned");
        }
    }

    // ---- coupon hand-off (leader drops coupons + says "d5") ----

    /** Coupons this bot still needs for its stage 1 answer; 0 if it isn't collecting. */
    int couponsStillNeeded() {
        if (busy || hasPass || stage1Done || question < 0 || getChr().getMapId() != KPQConstants.STAGE_1) return 0;
        return Math.max(0, KPQConstants.QUESTION_ANSWERS[question] - coupons);
    }

    MapItem getCouponClaim() {
        MapItem c = couponClaim;
        if (c != null && c.isPickedUp()) {
            couponClaim = null; // someone else (the leader?) took it
            return null;
        }
        return c;
    }

    void assignCouponClaim(MapItem stack, int qty) {
        couponClaim = stack;
        couponClaimAt = System.currentTimeMillis();
        say(pick("Got it, I'll grab them", "on it", "ty! grabbing them", "mine!")
                + " (need " + couponsStillNeeded() + ")");
        klog("claimed coupon stack x" + qty + " @" + stack.getPosition());
    }

    private void fetchCouponClaim(Character leader, int need) {
        Character chr = getChr();
        MapItem stack = couponClaim;
        if (stack == null || stack.isPickedUp() || stack.getPosition() == null) {
            couponClaim = null;
            return;
        }
        stopHunt();
        Point sp = stack.getPosition();
        Point pos = chr.getPosition();
        boolean close = Math.abs(pos.x - sp.x) <= 70 && Math.abs(pos.y - sp.y) <= 90;
        if (!close) {
            if (System.currentTimeMillis() - couponClaimAt > KPQConstants.COUPON_FETCH_TIMEOUT_MS) {
                GCMovement.stop(chr);
                GCMovement.teleportTo(chr, sp.x, sp.y);
            } else if (!GCMovement.isMoving(chr)) {
                GCMovement.move(chr, sp.x, sp.y);
            }
            return;
        }

        int took;
        stack.lockItem();
        try {
            if (stack.isPickedUp()) {
                couponClaim = null;
                return;
            }
            took = KPQOrchestrator.stackQty(stack);
            DropCommands.botLootSingleDrop(chr, stack);
        } finally {
            stack.unlockItem();
        }
        couponClaim = null;
        coupons += took;
        lastReportedCoupons = coupons;

        if (coupons > need) {
            int extra = coupons - need;
            coupons = need;
            MapItem left = dropFor(leader, KPQConstants.COUPON, extra, chr.getPosition());
            orchestrator.registerLeftover(leader, left);
            say("Took " + (took - extra) + ", " + extra + " coupon" + (extra == 1 ? "" : "s") + " left over");
        } else {
            say("Took " + took + " - " + coupons + "/" + need + (coupons == need ? ", that's all I need!" : ""));
        }
        klog("took coupon stack x" + took + ", now " + coupons + "/" + need);
    }

    // =========================================================================
    // Stages 2-4: stand on the assigned rope / platform / barrel
    // =========================================================================

    private void puzzle(Character leader, int stage) {
        Character chr = getChr();
        EventInstanceManager eim = chr.getEventInstance();
        if (eim == null) {
            idleNear(leader);
            return;
        }
        if (eim.getProperty(stage + "stageclear") != null) {
            if (mySpot >= 0) {
                mySpot = -1;
                GCMovement.setRestHold(chr, false);
                GCMovement.dismountRope(chr, 0);
                say(pick("nice!", "yay", "gogo", "next stage!"));
            }
            idleNear(leader);
            return;
        }

        int version = orchestrator.puzzleVersion(eim, stage);
        if (version != seenVersion || stage != myStage) {
            seenVersion = version;
            myStage = stage;
            mySpot = orchestrator.assignedSpot(this, eim, stage);
            spotMoveAt = System.currentTimeMillis();
            spotSnapped = false;
            GCMovement.setRestHold(chr, false);
            if (GCMovement.isClimbing(chr)) GCMovement.dismountRope(chr, 0);
            if (!GCMovement.isEnabled(chr)) GCMovement.enable(chr); // a rope hang drops to the floor here
            Point walk = walkPoint(stage, mySpot);
            if (walk != null) GCMovement.move(chr, walk.x, walk.y);
            klog("puzzle stage " + stage + " v" + version + " -> spot " + mySpot + " walk " + walk);
            return;
        }

        Point target = spotTarget(stage, mySpot);
        if (target == null) return;

        if (mySpot < 0) {
            // Stay well clear of every answer area so we never spoil a combination.
            if (insideAnyArea(stage, chr.getPosition())) GCMovement.move(chr, target.x, target.y);
            return;
        }

        Rectangle area = KPQOrchestrator.area(stage, mySpot);
        if (spotSnapped || (area != null && area.contains(chr.getPosition()))) {
            return; // in place - hold still until the next combination
        }

        long waited = System.currentTimeMillis() - spotMoveAt;
        boolean underRope = stage == 2 && Math.abs(chr.getPosition().x - target.x) <= 30;
        long limit = stage == 2 ? KPQConstants.ROPE_WALK_TIMEOUT_MS : KPQConstants.SPOT_SNAP_TIMEOUT_MS;
        if (underRope || waited > limit) {
            // Ropes: the walker can climb but can't stop mid-rope, so walk to the rope's foot and
            // grab on at the answer height. Platforms/barrels: settle onto a 5px-tall area directly.
            spotSnapped = true;
            snapTo(target, stage == 2);
            klog("on spot " + mySpot + " @" + target + (underRope ? " (grabbed rope)" : " (snapped)"));
        } else if (!GCMovement.isMoving(chr)) {
            Point walk = walkPoint(stage, mySpot);
            if (walk != null) GCMovement.move(chr, walk.x, walk.y);
        }
    }

    /** Where to walk for a spot: the foot of a rope (stage 2), else the spot itself. */
    private Point walkPoint(int stage, int spot) {
        Point target = spotTarget(stage, spot);
        Rectangle r = KPQOrchestrator.area(stage, spot);
        if (stage != 2 || r == null || target == null) return target;
        Point foot = GCMovement.groundPointBelow(getChr().getMap(), target.x, r.y + r.height - 5);
        return foot != null ? foot : target;
    }

    /** Orchestrator readiness check. */
    boolean isOnAssignedSpot() {
        Rectangle area = KPQOrchestrator.area(myStage, mySpot);
        return area != null && area.contains(getChr().getPosition());
    }

    private Point spotTarget(int stage, int spot) {
        if (spot < 0) return KPQConstants.puzzleWaitPos(stage);
        Rectangle r = KPQOrchestrator.area(stage, spot);
        if (r == null) return null;
        int cx = r.x + r.width / 2;
        if (stage == 2) {
            return new Point(cx, r.y + r.height / 2); // mid-rope
        }
        // platform / barrel: the foothold surface under the area's centre
        Point ground = GCMovement.groundPointBelow(getChr().getMap(), cx, r.y - 20);
        if (ground != null && r.contains(ground)) return ground;
        return new Point(cx, r.y + r.height / 2);
    }

    private boolean insideAnyArea(int stage, Point p) {
        Rectangle[] areas = KPQConstants.puzzleAreas(stage);
        if (areas == null) return false;
        for (Rectangle r : areas) if (r.contains(p)) return true;
        return false;
    }

    private void snapTo(Point p, boolean onRope) {
        Character chr = getChr();
        GCMovement.disable(chr); // the physics driver would otherwise drop us off a rope
        chr.setPosition(new Point(p));
        if (onRope) {
            // hang in the rope pose (stance 16) so viewers see the bot on the rope, not floating
            MovementCommands.BotMove(MovementPacketConstructor.createStillStancePacket(
                    (short) p.x, (short) p.y, (short) 0, (byte) CharacterStance.ROPE_STANCE), chr);
            chr.setPosition(new Point(p));
            return;
        }
        MovementCommands.BotIdleStandingUpdateForced(chr);
    }

    // =========================================================================
    // Stage 5 + bonus
    // =========================================================================

    private void stage5(Character leader) {
        Character chr = getChr();
        EventInstanceManager eim = chr.getEventInstance();
        passes += pickUpFromMobs(KPQConstants.PASS);
        if (eim != null && eim.getProperty("5stageclear") != null) {
            stopHunt();
            idleNear(leader);
            return;
        }
        if (aliveMonsters().isEmpty()) {
            stopHunt();
            idleNear(leader);
            long now = System.currentTimeMillis();
            if (passes > 0 && now - lastReminderAt > 40_000) {
                lastReminderAt = now;
                say("All bosses down. I have " + passes + " pass" + (passes == 1 ? "" : "es") + " - say 'drop pass'");
            }
            return;
        }
        hunt();
    }

    /** Bonus stage: the boxes are the leader's - stay out of the way and don't attack. */
    private void bonus(Character leader) {
        stopHunt();
        idleNear(leader);
    }

    // =========================================================================
    // Leader commands
    // =========================================================================

    void onLeaderCommand(Command cmd, Character leader) {
        Character chr = getChr();
        if (leader == null || leader.getMap() != chr.getMap()) {
            if (cmd == Command.STATUS) say(statusLine());
            return;
        }
        switch (cmd) {
            case DROP_PASS -> {
                int n = (hasPass ? 1 : 0) + passes;
                if (n == 0) {
                    say(stage1Done && KPQConstants.stageOf(chr.getMapId()) == 1
                            ? "I already gave you my pass"
                            : pick("no pass yet", "don't have one yet, still working on it", "0 passes sorry"));
                    return;
                }
                busy = true;
                try {
                    // Stage 1: stay at the bottom by Cloto - the pass appears at the leader's feet.
                    if (KPQConstants.stageOf(chr.getMapId()) != 1) approach(leader);
                    if (hasPass) stage1Done = true;
                    hasPass = false;
                    passes = 0;
                    dropFor(leader, KPQConstants.PASS, n, leader.getPosition());
                    say(n == 1 ? "here's my pass" : "here, " + n + " passes");
                    klog("dropped " + n + " pass(es) for leader");
                } finally {
                    busy = false;
                }
            }
            case DROP_COUPONS -> {
                if (coupons <= 0) {
                    say(pick("no coupons on me", "0 coupons"));
                    return;
                }
                busy = true;
                try {
                    approach(leader);
                    int n = coupons;
                    coupons = 0;
                    lastReportedCoupons = -1;
                    dropFor(leader, KPQConstants.COUPON, n, leader.getPosition());
                    say("dropped " + n + " coupon" + (n == 1 ? "" : "s"));
                } finally {
                    busy = false;
                }
            }
            case COME -> {
                stopHunt();
                Point p = leader.getPosition();
                GCMovement.move(chr, p.x + (rng.nextBoolean() ? 40 : -40), p.y);
                say(pick("omw", "coming", "k"));
            }
            case STATUS -> say(statusLine());
            default -> { }
        }
    }

    private String statusLine() {
        int stage = KPQConstants.stageOf(getChr().getMapId());
        if (stage == 1) {
            if (hasPass) return "I have my pass";
            if (question < 0) return "going to ask Cloto";
            return "coupons " + coupons + "/" + KPQConstants.QUESTION_ANSWERS[question];
        }
        if (stage >= 2 && stage <= 4) {
            return mySpot >= 0 ? "on " + KPQConstants.puzzleNoun(stage) + " " + (mySpot + 1) : "waiting by Cloto";
        }
        if (stage == 5) return passes + " pass" + (passes == 1 ? "" : "es");
        return "ready";
    }

    // Walk up to the leader before handing something over (blocking, max ~5s).
    private void approach(Character leader) {
        Character chr = getChr();
        Point lp = leader.getPosition();
        if (chr.getPosition().distance(lp) < 120) return;
        stopHunt();
        GCMovement.move(chr, lp.x + (rng.nextBoolean() ? 50 : -50), lp.y);
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until && chr.getPosition().distance(lp) > 120) {
            if (!BotHelpers.blockingSleep(200)) break;
        }
    }

    // =========================================================================
    // Building blocks
    // =========================================================================

    /** Spawn a real item stack that only the leader can pick up. */
    private MapItem dropFor(Character leader, int itemId, int qty, Point at) {
        if (qty <= 0) return null;
        Character chr = getChr();
        Item item = BotLogic.generateCleanItemWithQty(itemId, qty);
        MapItem drop = chr.getMap().spawnItemDropNoExpire(chr, leader, item, new Point(at), false, false);
        if (drop != null) drop.setPermanentOwner(true);
        return drop;
    }

    /** Pick up mob-dropped stacks of itemId we're entitled to near our feet; returns the count gained. */
    private int pickUpFromMobs(int itemId) {
        Character chr = getChr();
        MapleMap map = chr.getMap();
        int range = KPQConstants.LOOT_RANGE_PX;
        long now = System.currentTimeMillis();
        int gained = 0;
        List<MapObject> items = map.getMapObjectsInRange(chr.getPosition(), (double) range * range,
                List.of(MapObjectType.ITEM));
        for (MapObject o : items) {
            if (!(o instanceof MapItem mi)) continue;
            if (mi.getItemId() != itemId || !(mi.getDropper() instanceof Monster)) continue;
            if (now - mi.getDropTime() < KPQConstants.LOOT_SETTLE_MS) continue;
            if (!DropCommands.botCanLoot(chr, mi)) continue;
            mi.lockItem();
            try {
                if (mi.isPickedUp()) continue;
                int qty = mi.getItem() != null ? Math.max(1, mi.getItem().getQuantity()) : 1;
                DropCommands.botLootSingleDrop(chr, mi);
                gained += qty;
            } finally {
                mi.unlockItem();
            }
        }
        return gained;
    }

    private void hunt() {
        Character chr = getChr();
        if (!grinding) {
            grinding = true;
            GCMovement.setRestHold(chr, false);
            GCMovement.setGrinding(chr, true);
            // ROAM seeks mobs across ledges; the map's natural CAMP/PATROL style waits for mobs to
            // wander over, which is far too slow for 22 scattered Ligators on a tall map.
            grind.forceStyle(soloMapling.ArtificialPlayer.BotGrindSystem.GrindStyle.ROAM);
            grind.start(chr);
            lastHopAt = System.currentTimeMillis();
        }
        grind.tick(chr);

        // Tall KPQ maps: if nothing has died for a while, hop next to a live mob.
        long now = System.currentTimeMillis();
        if (grind.msSinceProgress() > KPQConstants.HUNT_STUCK_MS && now - lastHopAt > KPQConstants.HUNT_HOP_GAP_MS) {
            List<Monster> mobs = aliveMonsters();
            if (!mobs.isEmpty()) {
                lastHopAt = now;
                Monster m = mobs.get(rng.nextInt(mobs.size()));
                Point p = m.getPosition();
                GCMovement.stop(chr);
                GCMovement.teleportTo(chr, p.x + (rng.nextBoolean() ? 60 : -60), p.y);
                grind.resetupAfterTeleport(chr);
                klog("hunt stuck, hopped next to " + m.getName() + " @" + p);
            }
        }
    }

    private void stopHunt() {
        if (!grinding) return;
        grinding = false;
        Character chr = getChr();
        grind.release(chr);
        GCMovement.setGrinding(chr, false);
        GCMovement.stop(chr);
    }

    private List<Monster> aliveMonsters() {
        List<Monster> out = new ArrayList<>();
        for (Monster m : getChr().getMap().getAllMonsters()) {
            if (m.isAlive()) out.add(m);
        }
        return out;
    }

    /** Walk toward p; true once close enough (or after a timeout, when we "talk from afar"). */
    private boolean walkTo(Point p) {
        Character chr = getChr();
        Point pos = chr.getPosition();
        if (Math.abs(pos.x - p.x) <= KPQConstants.NPC_TALK_RANGE_PX && Math.abs(pos.y - p.y) <= 150) {
            walkTarget = null;
            return true;
        }
        long now = System.currentTimeMillis();
        if (walkTarget == null || !walkTarget.equals(p)) {
            walkTarget = new Point(p);
            walkStartedAt = now;
            GCMovement.move(chr, p.x + rng.nextInt(80) - 40, p.y);
            return false;
        }
        if (now - walkStartedAt > KPQConstants.NPC_WALK_TIMEOUT_MS) {
            GCMovement.stop(chr);
            GCMovement.teleportTo(chr, p.x - 60, p.y);
            walkTarget = null;
            return true;
        }
        if (!GCMovement.isMoving(chr)) GCMovement.move(chr, p.x + rng.nextInt(80) - 40, p.y);
        return false;
    }

    /** Hang around a fixed spot (e.g. the bottom floor by Cloto). */
    private void stayNear(Point spot) {
        Character chr = getChr();
        Point pos = chr.getPosition();
        if ((Math.abs(pos.x - spot.x) > 160 || Math.abs(pos.y - spot.y) > 100) && !GCMovement.isMoving(chr)) {
            GCMovement.move(chr, spot.x - 60 - rng.nextInt(140), spot.y);
        }
    }

    private void idleNear(Character leader) {
        Character chr = getChr();
        Point lp = leader.getPosition();
        Point pos = chr.getPosition();
        if (Math.abs(pos.x - lp.x) > KPQConstants.FOLLOW_SLACK_PX || Math.abs(pos.y - lp.y) > 160) {
            if (!GCMovement.isMoving(chr)) {
                int off = (rng.nextBoolean() ? 1 : -1) * (50 + rng.nextInt(90));
                GCMovement.move(chr, lp.x + off, lp.y);
            }
        }
    }

    /** Async warp (spawn choreography blocks for a few seconds). */
    private void warpTo(MapleMap target, Point near, String why) {
        if (target == null) return;
        busy = true;
        stopHunt();
        Point pos = new Point(near.x + rng.nextInt(120) - 60, near.y);
        ExecutorServiceManager.runAsync(() -> {
            try {
                BotHelpers.blockingSleep(150 + rng.nextInt(350)); // short human-ish beat, then appear
                Character chr = getChr();
                GCMovement.disable(chr);
                MapleMap old = chr.getMap();
                if (old != null && old != target) old.removePlayer(chr);
                // Place directly instead of warpBotToLocation: its arrival choreography blocks for
                // 2.5-6s, and the bot sat frozen (busy) through it after every stage change.
                if (old == target) target.removePlayer(chr);
                chr.setMap(target);
                chr.setPosition(pos);
                chr.setStance(5);
                target.addPlayer(chr);
                try {
                    if (chr.getMPC() != null) chr.getMPC().setMapId(target.getId());
                    chr.silentPartyUpdate(); // keep the party's map id right for the next KPQ entry check
                } catch (Exception ignored) {
                }
                klog("warped to " + target.getId() + " (" + why + ")");
            } catch (Exception e) {
                klog("warp failed: " + e.getMessage());
                e.printStackTrace();
            } finally {
                busy = false;
            }
        });
    }

    private MapleMap townMap() {
        return getChr().getClient().getChannelServer().getMapFactory().getMap(KPQConstants.KERNING_CITY);
    }

    private void say(String msg) {
        SocialCommands.BotSpeak(getChr(), msg);
    }

    private String pick(String... options) {
        return options[rng.nextInt(options.length)];
    }

    // ---- debug accessors (for !kpq list) ----
    public String debugLine() {
        Character chr = getChr();
        return chr.getName() + " cid=" + chr.getId() + " lv" + chr.getLevel() + " " + chr.getJob().name()
                + " map=" + chr.getMapId() + " " + statusLine()
                + (grinding ? " [hunting]" : "") + (busy ? " [busy]" : "");
    }
}
