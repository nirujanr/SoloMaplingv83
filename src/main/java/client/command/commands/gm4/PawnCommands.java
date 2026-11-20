package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotTypes.PawnBrokerBot;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;

/**
 * Pawn Broker bot (buys your scrolls/equips at live-FM prices, resells them in its own shop).
 *
 *   @pawn spawn    - spawn a Pawn Broker where you stand (use inside an FM room)
 *   @pawn list     - brokers, where they are, mesos spent today
 *   @pawn remove   - remove every Pawn Broker (and close their shops)
 */
public class PawnCommands extends Command {
    {
        setDescription("Pawn Broker bot. Try: @pawn help");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        String sub = params.length == 0 ? "help" : params[0].toLowerCase();
        ExecutorServiceManager.getExecutorService().execute(() -> {
            try {
                switch (sub) {
                    case "spawn" -> spawn(player, params.length > 1 && params[1].equalsIgnoreCase("greedy"));
                    case "list" -> list(player);
                    case "remove", "killall" -> player.yellowMessage("Removed " + removeAll() + " Pawn Broker(s).");
                    default -> help(player);
                }
            } catch (Exception e) {
                player.yellowMessage("@pawn failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private static void help(Character p) {
        p.yellowMessage("---- Pawn Broker ----");
        p.yellowMessage("@pawn spawn   - (re)open the Pawn Broker at the FM Entrance, ch 1 (auto-opens at server start)");
        p.yellowMessage("@pawn spawn greedy - open the Greedy Broker now (normally appears when the Pawn Broker's shop is full)");
        p.yellowMessage("@pawn list    - brokers, shop slots used, reset timers");
        p.yellowMessage("@pawn remove  - remove all Pawn Brokers and their shops");
        p.yellowMessage("Open a trade with it, add items, bargain in trade chat (\"4.5m\", \"90%\", \"more\"), lock to sell.");
    }

    private static void spawn(Character player, boolean greedy) {
        String err = PawnBrokerBot.spawnAtPost(greedy);
        if (err != null) {
            player.yellowMessage(err);
            return;
        }
        player.yellowMessage((greedy ? "Greedy Broker" : "Pawn Broker") + " is open at the FM Entrance (channel 1).");
    }

    private static void list(Character player) {
        player.yellowMessage("---- Brokers (" + PawnBrokerBot.getBrokers().size() + ") - shops in ch 2, FM Room 1 ----");
        for (PawnBrokerBot b : PawnBrokerBot.getBrokers()) {
            player.yellowMessage(b.statusLine());
        }
    }

    private static int removeAll() {
        int n = 0;
        for (PawnBrokerBot b : new ArrayList<>(PawnBrokerBot.getBrokers())) {
            Character bot = b.getChr();
            try {
                b.shutdown();
                BotTypeManager.manuallyStopBot(bot);
                BotGeneration.removeBotFromServer(bot);
                n++;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return n;
    }
}
