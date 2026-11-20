package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import net.server.world.Party;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQBot;
import soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQConstants;
import soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQOrchestrator;
import soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQSoloParty;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static soloMapling.server.SoloMaplingUtilities.getMapleMapById;

/**
 * Kerning PQ party bots.
 *
 *   !kpq spawn [n]   - spawn n (default 3) level 21-30 KPQ bots here and add them to your party
 *   !kpq party       - add every unpartied KPQ bot to your party
 *   !kpq list        - each bot's level, job, map and progress
 *   !kpq killall     - remove every KPQ bot
 *
 * In game (map or party chat), as party leader: "drop pass", "drop coupons", "come",
 * "wrong" (try the next rope/platform/barrel combination), "status".
 */
public class KPQCommands extends Command {
    {
        setDescription("Kerning PQ party bots. Try: !kpq help");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        String sub = params.length == 0 ? "help" : params[0].toLowerCase();
        ExecutorServiceManager.getExecutorService().execute(() -> {
            try {
                switch (sub) {
                    case "spawn" -> spawn(player, params.length > 1 ? parseInt(params[1], 3) : 3);
                    case "party" -> player.yellowMessage("Added " + joinAll(player) + " KPQ bot(s) to your party.");
                    case "list" -> list(player);
                    case "killall" -> player.yellowMessage("Removed " + killAll() + " KPQ bot(s).");
                    default -> help(player);
                }
            } catch (Exception e) {
                player.yellowMessage("!kpq failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private static void help(Character p) {
        p.yellowMessage("---- KPQ bots ----");
        p.yellowMessage("!kpq spawn [n]  - spawn n (default 3) lv 21-30 bots here + join your party");
        p.yellowMessage("!kpq party      - add unpartied KPQ bots to your party");
        p.yellowMessage("!kpq list       - bot status");
        p.yellowMessage("!kpq killall    - remove all KPQ bots");
        p.yellowMessage("Chat as leader: drop pass / drop coupons / come / wrong / status");
        p.yellowMessage("Start in Kerning City, then talk to Lakelis as party leader.");
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void spawn(Character player, int n) {
        if (player.getMapId() != KPQConstants.KERNING_CITY) {
            player.yellowMessage("Note: KPQ starts from Kerning City (" + KPQConstants.KERNING_CITY
                    + "); bots must be there with you when you talk to Lakelis.");
        }
        int partySize = player.getParty() != null ? player.getParty().getMembers().size() : 1;
        int room = 4 - partySize; // KPQ allows 3-4 players
        if (n > room) {
            player.yellowMessage("KPQ allows 4 players max; spawning " + Math.max(room, 0) + ".");
            n = room;
        }
        if (n <= 0) return;

        int spawned = 0;
        for (int i = 0; i < n; i++) {
            int baseClass = ThreadLocalRandom.current().nextInt(1, 5); // warrior/mage/bowman/thief
            int botId = BotGeneration.createBot(player.getPosition(), getMapleMapById(player.getMapId()),
                    baseClass, KPQConstants.MIN_LEVEL, KPQConstants.MAX_LEVEL);
            Character bot = waitForBot(botId);
            if (bot == null) continue;
            KPQBot kb = new KPQBot(bot);
            CharacterStorage.addActiveBot(bot.getId(), kb);
            BotTypeManager.manuallyStartBot(bot);
            spawned++;
        }
        player.yellowMessage("Spawned " + spawned + " KPQ bot(s).");
        BotHelpers.blockingSleep(1500);
        player.yellowMessage("Added " + joinAll(player) + " to your party. Talk to Lakelis when ready.");
    }

    private static Character waitForBot(int botId) {
        for (int i = 0; i < 30; i++) {
            Character chr = BotHelpers.getCharFromChannelStorage(botId);
            if (chr != null) return chr;
            BotHelpers.blockingSleep(100);
        }
        return null;
    }

    private static int joinAll(Character player) {
        if (player.getParty() == null && !Party.createParty(player, false)) {
            player.yellowMessage("Couldn't create a party.");
            return 0;
        }
        Party party = player.getParty();
        if (party.getLeaderId() != player.getId()) {
            player.yellowMessage("You need to be the party leader.");
            return 0;
        }
        int joined = 0;
        for (KPQBot b : snapshot()) {
            Character bot = b.getChr();
            if (bot.getParty() != null) continue;
            if (party.getMembers().size() >= 4) break;
            if (Party.joinParty(bot, party.getId(), true)) joined++;
        }
        return joined;
    }

    private static void list(Character player) {
        List<KPQBot> bots = snapshot();
        player.yellowMessage("---- KPQ bots (" + bots.size() + ") ----");
        for (KPQBot b : bots) player.yellowMessage(b.debugLine());
    }

    private static int killAll() {
        int n = 0;
        for (KPQBot b : snapshot()) {
            if (KPQSoloParty.removeBot(b)) n++;
        }
        return n;
    }

    private static List<KPQBot> snapshot() {
        return new ArrayList<>(KPQOrchestrator.getInstance().getBots());
    }
}
