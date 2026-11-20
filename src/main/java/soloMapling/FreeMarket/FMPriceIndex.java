package soloMapling.FreeMarket;

import client.inventory.Item;
import net.server.Server;
import net.server.channel.Channel;
import server.maps.HiredMerchant;
import server.maps.MapManager;
import server.maps.MapleMap;
import server.maps.PlayerShop;
import server.maps.PlayerShopItem;
import soloMapling.itemPool.ItemUtilities;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live Free Market supply: for every item currently listed in any FM room/channel, the cheapest
 * per-unit price and how many listings there are. Rebuilt by FMListingExporter every 30s (the same
 * scan that feeds the FM dashboard). Scrolled equips are skipped - their stats differ, so a listing
 * price says nothing about another copy. Shops owned by ignored owners (the Pawn Broker's own resale
 * shop) are skipped so its prices never feed back into its own offers.
 */
public final class FMPriceIndex {

    public record Supply(int itemId, int lowestUnitPrice, int listings) {}

    private static final int FM_FIRST_ROOM = 910000001;
    private static final int FM_LAST_ROOM = 910000022;

    private static volatile Map<Integer, Supply> index = Map.of();
    private static volatile long builtAt = 0;
    private static final Set<Integer> ignoredOwners = ConcurrentHashMap.newKeySet();

    private FMPriceIndex() {}

    /** Shops owned by these character ids are left out of the index. */
    public static void ignoreOwner(int ownerId) {
        ignoredOwners.add(ownerId);
    }

    public static void unignoreOwner(int ownerId) {
        ignoredOwners.remove(ownerId);
    }

    /** Current FM supply for an item, or null if nobody lists it (or the index isn't built yet). */
    public static Supply get(int itemId) {
        return index.get(itemId);
    }

    /** Every item currently listed (read-only snapshot of the last rebuild). */
    public static Map<Integer, Supply> all() {
        return index;
    }

    public static long getBuiltAt() {
        return builtAt;
    }

    public static void rebuild() {
        Map<Integer, Supply> next = new HashMap<>();
        for (Channel ch : Server.getInstance().getAllChannels()) {
            MapManager mm = ch.getMapFactory();
            for (int mapId = FM_FIRST_ROOM; mapId <= FM_LAST_ROOM; mapId++) {
                if (!mm.isMapLoaded(mapId)) continue;
                MapleMap map = mm.getMap(mapId);
                for (HiredMerchant hm : map.getAllHiredMerchants()) {
                    if (hm.isOpen() && !ignoredOwners.contains(hm.getOwnerId())) {
                        addAll(next, hm.getItems());
                    }
                }
                for (PlayerShop ps : map.getAllPlayerShops()) {
                    if (ps.isOpen() && ps.getOwner() != null && !ignoredOwners.contains(ps.getOwner().getId())) {
                        addAll(next, ps.getItems());
                    }
                }
            }
        }
        index = next;
        builtAt = System.currentTimeMillis();
    }

    private static void addAll(Map<Integer, Supply> into, List<PlayerShopItem> items) {
        if (items == null) return;
        for (PlayerShopItem psi : items) {
            Item item = psi.getItem();
            if (item == null || psi.getBundles() <= 0 || psi.getPrice() <= 0) continue;
            if (ItemUtilities.isEquip(item) && !ItemUtilities.isCleanEquip(item)) continue;
            int perBundle = Math.max(1, item.getQuantity());
            int unit = Math.max(1, psi.getPrice() / perBundle);
            into.merge(item.getItemId(), new Supply(item.getItemId(), unit, 1),
                    (a, b) -> new Supply(a.itemId(), Math.min(a.lowestUnitPrice(), b.lowestUnitPrice()),
                            a.listings() + b.listings()));
        }
    }
}
