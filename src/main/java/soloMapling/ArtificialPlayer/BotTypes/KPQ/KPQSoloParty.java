package soloMapling.ArtificialPlayer.BotTypes.KPQ;

import client.Character;
import net.server.world.Party;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands;
import soloMapling.ArtificialPlayer.BotTypeManager;

import java.util.concurrent.ThreadLocalRandom;

/**
 * "Solo party" for Kerning PQ: fills the player's party up to 4 with KPQ bots so a
 * solo player can start the PQ straight from Lakelis. Reuses idle unpartied KPQ bots
 * already standing in Kerning City before spawning new ones, so repeated runs don't
 * pile bots up. Called from scripts/npc/9020000.js and !kpq spawn.
 */
public final class KPQSoloParty {

    public static final int PARTY_SIZE = 4; // KPQ allows 3-4 players

    private KPQSoloParty() {}

    /** Fill the player's party with KPQ bots. Returns null on success, else a message for the player. */
    public static String fillParty(Character player) {
        return fillParty(player, PARTY_SIZE);
    }

    public static synchronized String fillParty(Character player, int targetSize) {
        if (player.getLevel() < KPQConstants.MIN_LEVEL) { // no upper cap for the player (SoloMapling)
            return "You need to be at least level " + KPQConstants.MIN_LEVEL + " to attempt this party quest.";
        }
        if (player.getParty() == null && !Party.createParty(player, false)) {
            return "I couldn't make a party for you. Please try again.";
        }
        Party party = player.getParty();
        if (party.getLeaderId() != player.getId()) {
            return "Only the party leader can bring a solo party.";
        }
        targetSize = Math.min(targetSize, PARTY_SIZE);

        // 1) Reuse idle KPQ bots on this map that aren't in a party.
        MapleMap map = player.getMap();
        for (KPQBot b : KPQOrchestrator.getInstance().getBots()) {
            if (party.getMembers().size() >= targetSize) break;
            Character bot = b.getChr();
            if (bot.getParty() == null && bot.getMap() == map && levelOk(bot)) {
                Party.joinParty(bot, party.getId(), true);
            }
        }

        // 2) Spawn the rest next to the player.
        while (party.getMembers().size() < targetSize) {
            Character bot = spawnBot(player);
            if (bot == null) return "The other adventurers couldn't make it. Please try again.";
            if (!Party.joinParty(bot, party.getId(), true)) {
                return "Your party is full.";
            }
        }
        return null;
    }

    private static boolean levelOk(Character c) {
        return c.getLevel() >= KPQConstants.MIN_LEVEL && c.getLevel() <= KPQConstants.MAX_LEVEL;
    }

    /** Spawn one level 21-30 KPQ bot (random class) at the player's position and start it. */
    public static Character spawnBot(Character player) {
        int baseClass = ThreadLocalRandom.current().nextInt(1, 5); // warrior/mage/bowman/thief
        int botId = BotGeneration.createBot(player.getPosition(), player.getMap(),
                baseClass, KPQConstants.MIN_LEVEL, KPQConstants.MAX_LEVEL);
        Character bot = null;
        for (int i = 0; i < 30 && bot == null; i++) {
            bot = BotHelpers.getCharFromChannelStorage(botId);
            if (bot == null) BotHelpers.blockingSleep(100);
        }
        if (bot == null) return null;
        KPQBot kb = new KPQBot(bot);
        CharacterStorage.addActiveBot(bot.getId(), kb);
        BotTypeManager.manuallyStartBot(bot);
        return bot;
    }

    /** Take a KPQ bot off the server: leave party, stop, unregister, remove. */
    public static synchronized boolean removeBot(KPQBot b) {
        Character bot = b.getChr();
        try {
            if (bot.getParty() != null) BotPartyCommands.botLeaveParty(bot);
            BotTypeManager.manuallyStopBot(bot);
            KPQOrchestrator.getInstance().unregisterBot(b);
            BotGeneration.removeBotFromServer(bot);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
