package soloMapling.FreeMarket;

import server.ItemInformationProvider;
import soloMapling.itemPool.ItemDatabase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guesses a value for an item nobody has priced, from the items most like it:
 *  - equips: same equip type (item id / 10000, e.g. 1332xxx = daggers) and the closest required
 *    level (within 10, else within 20);
 *  - everything else: the nearest item ids in the same family (id / 100, e.g. 20400xx helmet
 *    scrolls, else id / 1000).
 * Each similar item's value is its cheapest FM listing if any, else its price-database value.
 * The guess is the median of the 5 closest.
 */
public final class PriceEstimator {

    public record Estimate(int value, String closestName, int samples, String kind) {}

    private static final int MAX_SAMPLES = 5;
    private static final Map<Integer, Integer> reqLevelCache = new ConcurrentHashMap<>();

    private PriceEstimator() {}

    public static Estimate estimate(int itemId) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Map<Integer, FMPriceIndex.Supply> listed = FMPriceIndex.all();
        ItemDatabase db = ItemDatabase.getInstance();

        Set<Integer> known = new HashSet<>(db.getAllItemIds());
        known.addAll(listed.keySet());
        known.remove(itemId);

        boolean equip = itemId / 1_000_000 == 1;
        List<int[]> candidates = new ArrayList<>(); // {id, distance}
        if (equip) {
            int type = itemId / 10_000;
            int myLv = reqLevel(ii, itemId);
            for (int maxGap : new int[]{10, 20}) {
                candidates.clear();
                for (int id : known) {
                    if (id / 10_000 != type || ii.isCash(id)) continue;
                    int gap = Math.abs(reqLevel(ii, id) - myLv);
                    if (gap <= maxGap) candidates.add(new int[]{id, gap});
                }
                if (!candidates.isEmpty()) break;
            }
        } else {
            for (int family : new int[]{100, 1000}) {
                candidates.clear();
                for (int id : known) {
                    if (id / family != itemId / family || ii.isCash(id)) continue;
                    candidates.add(new int[]{id, Math.abs(id - itemId)});
                }
                if (!candidates.isEmpty()) break;
            }
        }
        if (candidates.isEmpty()) return null;

        candidates.sort(Comparator.comparingInt(a -> a[1]));
        List<Integer> values = new ArrayList<>();
        String closest = null;
        for (int[] c : candidates) {
            int v = valueOf(c[0], listed, db);
            if (v <= 0) continue;
            if (closest == null) closest = ii.getName(c[0]);
            values.add(v);
            if (values.size() >= MAX_SAMPLES) break;
        }
        if (values.isEmpty()) return null;
        values.sort(Integer::compare);
        int median = values.get(values.size() / 2);
        String kind = equip ? equipKind(itemId) : "items like it";
        return new Estimate(median, closest != null ? closest : "a similar item", values.size(), kind);
    }

    private static int valueOf(int id, Map<Integer, FMPriceIndex.Supply> listed, ItemDatabase db) {
        FMPriceIndex.Supply s = listed.get(id);
        if (s != null) return s.lowestUnitPrice();
        Integer p = db.getItemPrice(id);
        return p != null ? p : 0;
    }

    private static int reqLevel(ItemInformationProvider ii, int id) {
        return reqLevelCache.computeIfAbsent(id, k -> {
            try {
                Integer lv = ii.getEquipLevelReq(k);
                return lv != null ? lv : 0;
            } catch (Exception e) {
                return 0;
            }
        });
    }

    private static String equipKind(int itemId) {
        return switch (itemId / 10_000) {
            case 100 -> "hats";
            case 101, 102, 103 -> "accessories";
            case 104 -> "tops";
            case 105 -> "overalls";
            case 106 -> "bottoms";
            case 107 -> "shoes";
            case 108 -> "gloves";
            case 109 -> "shields";
            case 110 -> "capes";
            case 111, 112 -> "rings and pendants";
            case 130 -> "one-handed swords";
            case 131 -> "one-handed axes";
            case 132 -> "one-handed maces";
            case 133 -> "daggers";
            case 137 -> "wands";
            case 138 -> "staves";
            case 140 -> "two-handed swords";
            case 141 -> "two-handed axes";
            case 142 -> "two-handed maces";
            case 143 -> "spears";
            case 144 -> "polearms";
            case 145 -> "bows";
            case 146 -> "crossbows";
            case 147 -> "claws";
            case 148 -> "knucklers";
            case 149 -> "guns";
            default -> "similar gear";
        };
    }
}
