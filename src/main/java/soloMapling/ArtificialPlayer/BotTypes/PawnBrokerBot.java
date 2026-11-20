package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import client.inventory.Item;
import server.ItemInformationProvider;
import server.Trade;
import server.maps.HiredMerchant;
import server.maps.PlayerShopItem;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeCommands;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeQueue;
import soloMapling.FreeMarket.FMPriceIndex;
import soloMapling.FreeMarket.HiredMerchantArtificial;
import soloMapling.FreeMarket.ShopOfferSystem.OfferParser;
import soloMapling.itemPool.ItemUtilities;
import soloMapling.server.ExecutorServiceManager;
import tools.PacketCreator;

import java.awt.Point;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static soloMapling.BotLogger.log;
import static soloMapling.FreeMarket.FMEconomyManager.formatPriceToShorthand;
import static soloMapling.itemPool.ItemInformationProviderUtilities.getItemName;

/**
 * Pawn Broker: open a trade, show it items, it appraises them against the live FM (lowest listed
 * price and how many are listed) and offers mesos. Bargain in trade chat ("4.5m", "90%", "more");
 * lock the trade and it confirms if the mesos match the deal. Everything it buys goes into its own
 * hired merchant, priced just under the cheapest FM listing.
 *
 * Trade flow runs on a shared 500ms ticker (not the 2-6s macro tick) so it feels responsive.
 * Bots never actually receive traded items (Trade skips bot inventories), so the offered items are
 * snapshotted (copied) right before the bot confirms and moved into the shop on success.
 */
public class PawnBrokerBot extends BotSM {

    // ---- tuning ---------------------------------------------------------------------------------
    public static final int MIN_VALUE = 50_000;            // per stack, by reference price
    public static final long DAILY_BUDGET = 200_000_000L;  // mesos the broker may pay out per day
    public static final int MAX_ROUNDS = 3;                // bargaining rounds before it stops moving
    private static final double SILLY_ASK = 1.2;           // asks above 120% of reference: walk away
    private static final int TALK_RANGE_PX = 400;
    private static final int SHOP_ITEM_ID = 5030000;       // Hired Merchant (shop skin)
    private static final long ACCEPT_DELAY_MS = 1200;      // beat before accepting a trade invite

    // Fixed post: FM Entrance, on the ground right under Scrooge (NPC 9030100 stands at x=288).
    public static final int ENTRANCE_MAP = 910000000;
    public static final Point POST = new Point(288, 34);
    // Resale shop: channel 2, FM Room 1, on the ground near the entrance portal (out00 at x=790).
    private static final int SHOP_CHANNEL = 2;
    private static final int SHOP_MAP = 910000001;
    private static final Point SHOP_POST = new Point(650, 34);
    private static final long AUTO_SPAWN_DELAY_MS = 240_000; // after the bot environment waves

    // Shop capacity: a hired merchant holds 16 items. Once full the broker stops buying until its
    // reset (fair: 24h, greedy: 48h, or a server restart), and the fair one calls in the Greedy one.
    public static final int SHOP_CAPACITY = 16;
    private static final long FAIR_RESET_MS = 24L * 60 * 60 * 1000;
    private static final long GREEDY_RESET_MS = 48L * 60 * 60 * 1000;

    // Greedy broker: stands beside the fair one, shop beside the fair shop, pays far less.
    private static final Point GREEDY_POST = new Point(200, 34);
    private static final Point GREEDY_SHOP_POST = new Point(520, 34);

    // Cosmetic rings both brokers wear (MapleBowl name label + chat bubble).
    private static final int LABEL_RING = 1112115;
    private static final int QUOTE_RING = 1112808;

    // ---- shared registry / ticker ---------------------------------------------------------------
    private static final List<PawnBrokerBot> brokers = new CopyOnWriteArrayList<>();
    private static volatile boolean tickerStarted = false;

    // ---- per-bot state --------------------------------------------------------------------------
    private final ConcurrentLinkedQueue<String> tradeChat = new ConcurrentLinkedQueue<>();
    private Session session;
    private HiredMerchant shop;
    private long spentToday = 0;
    private LocalDate budgetDay = LocalDate.now();
    private long lastIdleLine = 0;
    private long inviteSeenAt = 0;
    private final boolean greedy;
    private int itemsStocked = 0;     // items put in the current shop (sold or not)
    private long fullSince = 0;       // 0 = still buying
    private long lastFullNotice = 0;

    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3})\\s*%");

    /** One appraised stack. */
    private record Line(Item item, String name, int qty, int reference, int open, int cap, String why) {}

    /** One open trade. */
    private static final class Session {
        final Character partner;
        String signature = "";
        List<Line> lines = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        int reference, offer, cap, rounds;
        boolean walked, confirmed, nagged;
        List<Item> snapshot;
        int paid;

        Session(Character partner) {
            this.partner = partner;
        }
    }

    public PawnBrokerBot(Character chr, boolean greedy) {
        super(chr);
        this.greedy = greedy;
        dialoguePath = "MerchantBotDialogue.yaml";
        botType = greedy ? "GreedyBroker" : "PawnBroker";
        FMPriceIndex.ignoreOwner(chr.getId());
        brokers.add(this);
        startTicker();
    }

    public static List<PawnBrokerBot> getBrokers() {
        return brokers;
    }

    public boolean isGreedy() {
        return greedy;
    }

    private String title() {
        return greedy ? "Greedy Broker" : "Pawn Broker";
    }

    /** One-line status for @pawn list. */
    public String statusLine() {
        String st;
        if (fullSince > 0) {
            long left = (greedy ? GREEDY_RESET_MS : FAIR_RESET_MS) - (System.currentTimeMillis() - fullSince);
            st = "FULL - buying again in " + Math.max(0, left / 3_600_000) + "h";
        } else {
            st = "buying, shop " + itemsStocked + "/" + SHOP_CAPACITY;
        }
        return title() + " " + getChr().getName() + " - " + st + ", paid today " + fmt(spentToday);
    }

    private static PawnBrokerBot find(boolean greedy) {
        for (PawnBrokerBot b : brokers) if (b.greedy == greedy) return b;
        return null;
    }

    /** Server start: put the broker at its post once the bot environment has finished spawning. */
    public static void scheduleAutoSpawn() {
        ExecutorServiceManager.runAsync(() -> {
            BotHelpers.blockingSleep(AUTO_SPAWN_DELAY_MS);
            try {
                String err = spawnAtPost();
                log("[PawnBroker] auto-spawn: " + (err == null ? "open at FM Entrance" : err));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * Spawn the broker at its post (FM Entrance, channel 1, under Scrooge). One broker only:
     * returns an error string if one already exists or spawning failed, else null.
     */
    public static String spawnAtPost() {
        return spawnAtPost(false);
    }

    public static synchronized String spawnAtPost(boolean greedy) {
        String what = greedy ? "Greedy Broker" : "Pawn Broker";
        PawnBrokerBot existing = find(greedy);
        if (existing != null) return "The " + what + " is already open (" + existing.getChr().getName() + ").";
        net.server.channel.Channel ch = net.server.Server.getInstance().getChannel(0, 1);
        if (ch == null) return "Channel 1 isn't up yet.";
        server.maps.MapleMap map = ch.getMapFactory().getMap(ENTRANCE_MAP);
        int baseClass = ThreadLocalRandom.current().nextInt(1, 5);
        int botId = soloMapling.ArtificialPlayer.BotGeneration.createBot(new Point(greedy ? GREEDY_POST : POST),
                map, baseClass, 70, 120);
        Character bot = null;
        for (int i = 0; i < 30 && bot == null; i++) {
            bot = BotHelpers.getCharFromChannelStorage(botId);
            if (bot == null) BotHelpers.blockingSleep(100);
        }
        if (bot == null) return "Couldn't spawn the " + what + ", try again.";
        try {
            soloMapling.ArtificialPlayer.BotCustomization.EquipItem(bot, LABEL_RING, (short) -112);
            soloMapling.ArtificialPlayer.BotCustomization.EquipItem(bot, QUOTE_RING, (short) -113);
        } catch (Exception e) {
            e.printStackTrace(); // cosmetic only - never block the spawn
        }
        PawnBrokerBot pb = new PawnBrokerBot(bot, greedy);
        soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.addActiveBot(bot.getId(), pb);
        soloMapling.ArtificialPlayer.BotTypeManager.manuallyStartBot(bot);
        if (FMPriceIndex.getBuiltAt() == 0) FMPriceIndex.rebuild();
        System.out.println("[PawnBroker] " + what + " " + bot.getName() + " opened at the FM Entrance (ch 1)");
        return null;
    }

    /** Called before the bot is removed from the server. */
    public void shutdown() {
        brokers.remove(this);
        FMPriceIndex.unignoreOwner(getChr().getId());
        closeShop();
    }

    private static synchronized void startTicker() {
        if (tickerStarted) return;
        tickerStarted = true;
        ExecutorServiceManager.scheduleAtFixedRate(() -> {
            for (PawnBrokerBot b : brokers) {
                try {
                    b.fastTick();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 1000, 500, TimeUnit.MILLISECONDS);
    }

    // =============================================================================================
    // Macro tick: idle flavour only (trades run on the fast ticker)
    // =============================================================================================

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused() || getState() == BotState.TRADING || session != null) return;
        long now = System.currentTimeMillis();
        if (now - lastIdleLine > 60_000 && ThreadLocalRandom.current().nextInt(4) == 0) {
            lastIdleLine = now;
            String[] lines;
            if (fullSince > 0) {
                lines = new String[]{"My shop is full - I'm not buying right now. Sorry!"};
            } else if (greedy) {
                lines = new String[]{
                        "Buying EVERYTHING. Junk, potions, gear - I'll take it all. Cheap.",
                        "Greedy Broker here. Low prices, but I never say no.",
                        "Can't sell it anywhere else? Trade me, I'll make you an offer.",
                };
            } else {
                lines = new String[]{
                        "Buying scrolls & equips - open a trade and show me!",
                        "Pawn Broker here. Fair FM prices, instant mesos.",
                        "Got scrolls or gear you don't need? Trade me.",
                        "I price off the live FM. Show me what you've got!"
                };
            }
            SocialCommands.BotSpeak(getChr(), lines[ThreadLocalRandom.current().nextInt(lines.length)]);
        }
    }

    @Override
    protected void updateTradeSM() {
        // no-op: the pawn trade runs on fastTick(); BotSM still handles the TRADING -> RUNNING exit
    }

    // =============================================================================================
    // Fast tick (500ms)
    // =============================================================================================

    private void fastTick() {
        if (!getRunning()) return;
        Character bot = getChr();
        Trade trade = bot.getTrade();
        checkReset();
        if (trade == null) {
            session = null;
            tradeChat.clear();
            inviteSeenAt = 0;
            return;
        }
        Trade partnerTrade0 = trade.getPartner();
        if (partnerTrade0 != null && !trade.isFullTrade()) {
            // Pending invite. Players only - other bots get turned down.
            Character inviter = partnerTrade0.getChr();
            if (inviter != null && BotHelpers.isBot(inviter)) {
                BotTradeQueue.getInstance().removeTradeRequest(bot);
                BotTradeCommands.declineTradeInvite(bot);
                return;
            }
            if (fullSince > 0) { // shop full: not buying, turn the invite down and say why
                BotTradeQueue.getInstance().removeTradeRequest(bot);
                BotTradeCommands.declineTradeInvite(bot);
                long now = System.currentTimeMillis();
                if (now - lastFullNotice > 5_000) {
                    lastFullNotice = now;
                    PawnBrokerBot other = find(!greedy);
                    String tip = (!greedy && other != null && other.fullSince == 0)
                            ? " Try the Greedy Broker next to me." : "";
                    SocialCommands.BotSpeak(bot, "Sorry, my shop is full - I'm not buying right now." + tip);
                }
                return;
            }
            // Accept after a human beat: answering instantly races the client still opening its
            // trade window, and the broker then never appears in it.
            long now = System.currentTimeMillis();
            if (inviteSeenAt == 0) inviteSeenAt = now;
            if (now - inviteSeenAt >= ACCEPT_DELAY_MS) {
                inviteSeenAt = 0;
                checkForTrades();
            }
            return;
        }
        Trade partnerTrade = trade.getPartner();
        if (partnerTrade == null || partnerTrade.getChr() == null) return; // invite still pending
        if (session == null || session.partner != partnerTrade.getChr()) {
            session = new Session(partnerTrade.getChr());
            Trade.TradeResultCallback cb = r -> onTradeSuccess();
            trade.setTradeResultCallback(cb);
            chat(greedy ? "Put anything in the window - I buy it all. Don't expect much, though."
                    : "Hi! Put the items you want to sell in the window. I'll price them off the live FM.");
            int room = SHOP_CAPACITY - itemsStocked;
            if (room < 5) chat("Heads up: I only have room for " + room + " more item" + (room == 1 ? "" : "s") + ".");
        }
        Session s = session;
        if (s.confirmed) return;

        rollBudgetDay();
        List<Item> items = BotTradeCommands.getPartnersItems(bot);
        if (items == null) return;

        String sig = signature(items);
        if (!sig.equals(s.signature)) {
            s.signature = sig;
            appraise(s, items);
            return; // give the player a beat to read before reacting to anything else
        }

        boolean partnerLocked = BotTradeCommands.isPartnerLocked(bot);
        String msg;
        while ((msg = tradeChat.poll()) != null) {
            if (!partnerLocked) bargain(s, msg);
        }

        if (partnerLocked) {
            if (s.lines.isEmpty() || !s.rejected.isEmpty() || s.offer <= 0) {
                if (!s.nagged) {
                    s.nagged = true;
                    chat(s.lines.isEmpty() ? "There's nothing here I can buy."
                            : "Take out the items I don't buy first, then lock again.");
                }
                return;
            }
            int room = SHOP_CAPACITY - itemsStocked;
            if (items.size() > room) {
                if (!s.nagged) {
                    s.nagged = true;
                    chat("My shop only has room for " + room + " more item" + (room == 1 ? "" : "s")
                            + ". Take some out, then lock again.");
                }
                return;
            }
            if (spentToday + s.offer > DAILY_BUDGET) {
                if (!s.nagged) {
                    s.nagged = true;
                    chat("Sorry, I'm out of mesos for today. Come back tomorrow!");
                }
                return;
            }
            // Snapshot exactly what's in the window, then confirm.
            s.snapshot = new ArrayList<>();
            for (Item it : items) s.snapshot.add(it.copy());
            s.paid = s.offer;
            s.confirmed = true;
            chat("Pleasure doing business!");
            BotTradeCommands.confirmTrade(bot);
        }
    }

    // =============================================================================================
    // Appraisal
    // =============================================================================================

    private void appraise(Session s, List<Item> items) {
        s.lines.clear();
        s.rejected.clear();
        s.rounds = 0;
        s.walked = false;
        s.nagged = false;
        if (items.isEmpty()) {
            s.reference = s.offer = s.cap = 0;
            setOffer(0);
            return;
        }
        for (Item it : items) {
            Object r = appraiseItem(it);
            if (r instanceof Line l) s.lines.add(l);
            else s.rejected.add((String) r);
        }
        s.reference = 0;
        s.offer = 0;
        s.cap = 0;
        for (Line l : s.lines) {
            s.reference += l.reference();
            s.offer += l.open();
            s.cap += l.cap();
        }
        for (String r : s.rejected) chat(r);
        for (Line l : s.lines) chat(l.why());
        if (!s.lines.isEmpty()) {
            if (s.lines.size() > 1) chat("Total: " + fmt(s.offer) + " for everything I can take.");
            chat("Lock the trade to accept, or tell me your price.");
        }
        setOffer(s.offer);
    }

    /** Returns a Line, or a String explaining why the item is refused. */
    private Object appraiseItem(Item it) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        int id = it.getItemId();
        String name = getItemName(id);
        if (name == null) name = "that item";
        int qty = Math.max(1, it.getQuantity());

        if (ii.isQuestItem(id)) return "I can't buy " + name + " - it's a quest item.";
        if (ii.isCash(id)) return "I don't deal in cash items (" + name + ").";
        if (ii.isUntradeableRestricted(id) || ii.isAccountRestricted(id)) return name + " can't be traded.";
        if (!greedy && (id / 10000 == 200 || id / 10000 == 202)) return "I don't buy potions (" + name + ").";

        boolean scrolledEquip = ItemUtilities.isEquip(it) && !ItemUtilities.isCleanEquip(it);
        FMPriceIndex.Supply supply = scrolledEquip ? null : FMPriceIndex.get(id);
        Integer market = ItemUtilities.getItemMarketValue(it);

        int unitRef;
        double openPct, capPct;
        String basis;
        soloMapling.FreeMarket.PriceEstimator.Estimate guess;
        if (supply != null) {
            unitRef = supply.lowestUnitPrice();
            int n = supply.listings();
            if (n >= 10) { openPct = 0.80; capPct = 0.90; }
            else if (n >= 3) { openPct = 0.85; capPct = 0.95; }
            else { openPct = 0.90; capPct = 1.00; }
            basis = n + " in the FM, cheapest " + fmt(unitRef) + (qty > 1 ? " each" : "");
        } else if (market != null && market > 0) {
            unitRef = market;
            openPct = 0.80;
            capPct = 0.90;
            basis = (scrolledEquip ? "scrolled, worth about " : "none in the FM, worth about ") + fmt(unitRef)
                    + (qty > 1 ? " each" : "");
        } else if ((guess = soloMapling.FreeMarket.PriceEstimator.estimate(id)) != null) {
            // Nobody prices it: guess from the closest similar items, and pay a bit less for the risk.
            unitRef = guess.value();
            openPct = 0.70;
            capPct = 0.80;
            basis = "never seen one priced, but " + guess.kind() + " like " + guess.closestName()
                    + " go for about " + fmt(unitRef) + (qty > 1 ? " each" : "");
        } else {
            return "I have no idea what " + name + " is worth, sorry.";
        }
        if (greedy) {
            // Greedy: 50% of a known price, 40% of a guessed one, and barely moves when haggled.
            boolean guessed = supply == null && (market == null || market <= 0);
            openPct = guessed ? 0.40 : 0.50;
            capPct = guessed ? 0.45 : 0.55;
        }

        long ref = (long) unitRef * qty;
        if (!greedy && ref < MIN_VALUE) return name + " isn't worth enough for me (under " + fmt(MIN_VALUE) + ").";
        int reference = (int) Math.min(ref, Integer.MAX_VALUE);
        int open = (int) (reference * openPct);
        int cap = (int) (reference * capPct);
        String label = qty > 1 ? qty + "x " + name : name;
        return new Line(it, name, qty, reference, open, cap, label + ": " + basis + ". I'll pay " + fmt(open) + ".");
    }

    // =============================================================================================
    // Bargaining
    // =============================================================================================

    private void bargain(Session s, String msg) {
        if (s.lines.isEmpty()) return;
        String low = msg.toLowerCase(Locale.ROOT);
        long ask = -1;
        Matcher pm = PERCENT.matcher(low);
        if (pm.find()) {
            ask = (long) s.reference * Integer.parseInt(pm.group(1)) / 100;
        } else {
            long p = OfferParser.extractPrice(low);
            if (p > 0) ask = p;
        }
        boolean wantsMore = ask < 0 && (low.contains("more") || low.contains("higher") || low.contains("come on")
                || low.contains("lowball") || low.contains("better"));
        if (ask < 0 && !wantsMore) return; // small talk

        if (s.walked) {
            chat("My offer stands at " + fmt(s.offer) + ". Lock to accept or cancel.");
            return;
        }
        if (ask >= 0 && ask <= s.offer) {
            chat("Even better for me - but my offer stands at " + fmt(s.offer) + ".");
            return;
        }
        if (ask >= 0 && ask <= s.cap) {
            s.offer = (int) ask;
            setOffer(s.offer);
            chat("Deal! " + fmt(s.offer) + ". Lock the trade.");
            return;
        }
        if (ask > (long) (s.reference * SILLY_ASK) || s.rounds >= MAX_ROUNDS) {
            s.walked = true;
            chat(ask > (long) (s.reference * SILLY_ASK)
                    ? "Not worth it to me - I can buy it cheaper in the FM. My offer stays " + fmt(s.offer) + "."
                    : "That's as far as I go. " + fmt(s.offer) + ", lock to accept.");
            return;
        }
        s.rounds++;
        int target = ask >= 0 ? (int) Math.min(ask, s.cap) : s.cap;
        int step = wantsMore ? (target - s.offer) / 3 : (target - s.offer) / 2;
        s.offer = Math.min(s.cap, s.offer + Math.max(step, 1));
        setOffer(s.offer);
        String[] lines = {"Hmm... I can do " + fmt(s.offer) + ".", "Meet me at " + fmt(s.offer) + "?",
                "Best I can do right now is " + fmt(s.offer) + "."};
        chat(s.offer >= s.cap ? "Final offer: " + fmt(s.offer) + "."
                : lines[ThreadLocalRandom.current().nextInt(lines.length)]);
    }

    // =============================================================================================
    // Trade completion -> resale shop
    // =============================================================================================

    private void onTradeSuccess() {
        Session s = session;
        if (s == null || s.snapshot == null) return;
        spentToday += s.paid;
        log("[PawnBroker] " + getChr().getName() + " bought " + s.snapshot.size() + " item(s) from "
                + s.partner.getName() + " for " + s.paid + " (spent today " + spentToday + ")");
        List<Item> bought = s.snapshot;
        itemsStocked += bought.size();
        ExecutorServiceManager.runAsync(() -> {
            for (Item it : bought) stockShop(it);
        });
        SocialCommands.BotSpeak(getChr(), "Thanks " + s.partner.getName() + "! My "
                + (greedy ? "Greedy Shop" : "shop") + " is in channel 2, FM Room 1.");
        if (itemsStocked >= SHOP_CAPACITY) onShopFull();
    }

    /** Shop hit 16 items: stop buying until the reset; the fair broker calls in the greedy one. */
    private void onShopFull() {
        fullSince = System.currentTimeMillis();
        long hours = (greedy ? GREEDY_RESET_MS : FAIR_RESET_MS) / 3_600_000;
        System.out.println("[PawnBroker] " + title() + " shop full - not buying for " + hours + "h");
        if (!greedy && find(true) == null) {
            SocialCommands.BotSpeak(getChr(), "My shop is full! My greedy friend will take over from here...");
            ExecutorServiceManager.runAsync(() -> {
                String err = spawnAtPost(true);
                if (err != null) System.out.println("[PawnBroker] greedy spawn: " + err);
            });
        } else {
            SocialCommands.BotSpeak(getChr(), "My shop is full - I'm done buying for now.");
        }
    }

    /** After 24h (fair) / 48h (greedy) a full broker clears its shop and starts buying again. */
    private void checkReset() {
        if (fullSince == 0) return;
        long wait = greedy ? GREEDY_RESET_MS : FAIR_RESET_MS;
        if (System.currentTimeMillis() - fullSince < wait) return;
        closeShop();
        itemsStocked = 0;
        fullSince = 0;
        System.out.println("[PawnBroker] " + title() + " reset - buying again");
        SocialCommands.BotSpeak(getChr(), "Fresh shop, fresh mesos - I'm buying again!");
    }

    private synchronized void stockShop(Item item) {
        if (shop == null || !shop.isOpen()) openShop();
        if (shop == null) return;
        int qty = Math.max(1, item.getQuantity());
        FMPriceIndex.Supply supply = (ItemUtilities.isEquip(item) && !ItemUtilities.isCleanEquip(item))
                ? null : FMPriceIndex.get(item.getItemId());
        int unit;
        if (supply != null) {
            unit = Math.max(1, (int) (supply.lowestUnitPrice() * 0.99)); // just under the cheapest listing
        } else {
            Integer market = ItemUtilities.getItemMarketValue(item);
            if (market == null || market <= 0) {
                soloMapling.FreeMarket.PriceEstimator.Estimate g = soloMapling.FreeMarket.PriceEstimator.estimate(item.getItemId());
                if (g == null) return;
                market = g.value();
            }
            unit = (int) (market * (0.80 + ThreadLocalRandom.current().nextDouble() * 0.20));
        }
        Item copy = item.copy();
        long price = (long) unit * qty;
        shop.addItem(new PlayerShopItem(copy, (short) 1, (int) Math.min(price, Integer.MAX_VALUE)));
    }

    /**
     * The resale shop lives in its own FM room (channel 2, FM Room 1 - empty, since the bot shops
     * are all on channel 1) instead of next to the broker: shops aren't allowed in the FM Entrance.
     * HiredMerchant takes its map/channel from the owner, so those two fields are re-pointed.
     */
    private void openShop() {
        Character bot = getChr();
        net.server.channel.Channel ch = net.server.Server.getInstance().getChannel(0, SHOP_CHANNEL);
        if (ch == null) return;
        server.maps.MapleMap shopMap = ch.getMapFactory().getMap(SHOP_MAP);
        HiredMerchantArtificial m = new HiredMerchantArtificial(bot,
                greedy ? "Greedy Shop" : "Pawn Broker - Bargains!", SHOP_ITEM_ID, bot.getId(), bot.getName());
        try {
            java.lang.reflect.Field mapField = HiredMerchant.class.getDeclaredField("map");
            mapField.setAccessible(true);
            mapField.set(m, shopMap);
            java.lang.reflect.Field chField = HiredMerchant.class.getDeclaredField("channel");
            chField.setAccessible(true);
            chField.setInt(m, SHOP_CHANNEL);
        } catch (ReflectiveOperationException e) {
            log("[PawnBroker] couldn't move shop to ch" + SHOP_CHANNEL + ": " + e);
            return;
        }
        m.setPosition(new Point(greedy ? GREEDY_SHOP_POST : SHOP_POST));
        bot.getWorldServer().registerHiredMerchant(m);
        ch.addHiredMerchant(bot.getId(), m);
        m.setOpen(true);
        shopMap.addMapObject(m);
        shopMap.broadcastMessage(PacketCreator.spawnHiredMerchantBox(m));
        shop = m;
        System.out.println("[PawnBroker] " + title() + " shop opened in channel " + SHOP_CHANNEL + ", FM Room 1");
    }

    private synchronized void closeShop() {
        if (shop == null) return;
        try {
            shop.setOpen(false);
            shop.getMap().removeMapObject(shop);
            shop.getMap().broadcastMessage(PacketCreator.removeHiredMerchantBox(shop.getOwnerId()));
        } catch (Exception e) {
            e.printStackTrace();
        }
        shop = null;
    }

    // =============================================================================================
    // Chat hooks
    // =============================================================================================

    /** Trade-window chat from a player whose partner is a bot. */
    public static void onTradeChat(Character sender, String text) {
        Trade t = sender.getTrade();
        if (t == null || t.getPartner() == null) return;
        Character other = t.getPartner().getChr();
        if (other == null || !BotHelpers.isBot(other)) return;
        for (PawnBrokerBot b : brokers) {
            if (b.getChr() == other) {
                b.tradeChat.add(text);
                return;
            }
        }
    }

    /** Map chat: answer "selling / pawn / price check" when a broker is nearby. */
    public static void onPlayerChat(Character sender, String text) {
        if (sender == null || BotHelpers.isBot(sender) || brokers.isEmpty()) return;
        String low = text.toLowerCase(Locale.ROOT);
        if (!(low.contains("pawn") || low.contains("selling") || low.contains("price check")
                || low.contains("buying anything") || low.matches(".*\\bpc\\b.*"))) return;
        // Every broker in range answers: a full one says it's done buying (and points to the other
        // broker if that one is still buying); one that's buying invites a trade.
        long delay = 400 + ThreadLocalRandom.current().nextInt(600);
        for (PawnBrokerBot b : brokers) {
            Character bot = b.getChr();
            if (bot.getMap() != sender.getMap() || b.session != null
                    || bot.getPosition().distance(sender.getPosition()) > TALK_RANGE_PX) continue;
            String line;
            if (b.fullSince > 0) {
                PawnBrokerBot other = find(!b.greedy);
                line = (other != null && other.fullSince == 0)
                        ? "Sorry " + sender.getName() + ", my shop is full - I'm done buying for now. Ask the "
                          + other.title() + " beside me!"
                        : "Sorry " + sender.getName() + ", my shop is full - I'm done buying for now.";
            } else {
                line = "Open a trade with me and show me what you've got, " + sender.getName() + "!";
            }
            long d = delay;
            delay += 900; // the second broker answers a beat later
            ExecutorServiceManager.runAsync(() -> {
                BotHelpers.blockingSleep(d);
                SocialCommands.BotSpeak(bot, line);
            });
        }
    }

    // =============================================================================================
    // Helpers
    // =============================================================================================

    private void chat(String msg) {
        BotTradeCommands.writeTradeChat(getChr(), msg);
    }

    private void setOffer(int amount) {
        BotTradeCommands.setMeso(getChr(), Math.max(0, amount));
    }

    private void rollBudgetDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(budgetDay)) {
            budgetDay = today;
            spentToday = 0;
        }
    }

    private static String signature(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            sb.append(it.getPosition()).append(':').append(it.getItemId()).append('x').append(it.getQuantity()).append(';');
        }
        return sb.toString();
    }

    private static String fmt(long mesos) {
        return formatPriceToShorthand((int) Math.min(mesos, Integer.MAX_VALUE));
    }
}
