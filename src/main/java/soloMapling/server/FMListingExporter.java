package soloMapling.server;

import client.inventory.Equip;
import client.inventory.Item;
import net.server.Server;
import net.server.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.maps.HiredMerchant;
import server.maps.MapManager;
import server.maps.MapleMap;
import server.maps.PlayerShop;
import server.maps.PlayerShopItem;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.FreeMarket.FMShopInfoManager;
import soloMapling.FreeMarket.HiredMerchantArtificial;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Writes every Free Market listing (bot and real-player shops, all channels) to
 * fmdata/listings.json every 30 seconds, for the fm-dashboard container to read.
 * Read-only: it never changes a shop. Started from Server.init() once the server is online.
 */
public class FMListingExporter {

    private static final Logger log = LoggerFactory.getLogger(FMListingExporter.class);

    private static final Path OUT_DIR = Path.of("fmdata");
    private static final Path OUT_FILE = OUT_DIR.resolve("listings.json");
    private static final int FM_FIRST_ROOM = 910000001;
    private static final int FM_LAST_ROOM = 910000022;
    private static final long PERIOD_SECONDS = 30;

    private static volatile boolean started = false;

    public static synchronized void start() {
        if (started) return;
        started = true;
        ExecutorServiceManager.scheduleAtFixedRate(FMListingExporter::exportSafely,
                10, PERIOD_SECONDS, TimeUnit.SECONDS);
        log.info("[FMListingExporter] Writing Free Market listings to {} every {}s",
                OUT_FILE.toAbsolutePath(), PERIOD_SECONDS);
    }

    private static void exportSafely() {
        try {
            soloMapling.FreeMarket.FMPriceIndex.rebuild(); // live supply for the Pawn Broker
        } catch (Throwable t) {
            log.warn("[FMListingExporter] Price index rebuild failed", t);
        }
        try {
            export();
        } catch (Throwable t) {
            // never let an exception cancel the scheduled task
            log.warn("[FMListingExporter] Export failed", t);
        }
    }

    private static void export() throws IOException {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append("{\"generatedAt\":").append(System.currentTimeMillis()).append(",\"listings\":[");
        boolean first = true;
        int shopCount = 0;

        for (Channel ch : Server.getInstance().getAllChannels()) {
            MapManager mm = ch.getMapFactory();
            for (int mapId = FM_FIRST_ROOM; mapId <= FM_LAST_ROOM; mapId++) {
                if (!mm.isMapLoaded(mapId)) continue; // don't force-load empty rooms
                MapleMap map = mm.getMap(mapId);

                for (HiredMerchant hm : map.getAllHiredMerchants()) {
                    if (!hm.isOpen()) continue;
                    boolean bot = hm instanceof HiredMerchantArtificial;
                    String tier = bot ? ((HiredMerchantArtificial) hm).getTier() : null;
                    first = appendShop(sb, first, ii, ch, mapId, "merchant", bot, tier,
                            hm.getOwner(), hm.getDescription(), hm.getItems());
                    shopCount++;
                }
                for (PlayerShop ps : map.getAllPlayerShops()) {
                    if (!ps.isOpen()) continue;
                    boolean bot = ps.getOwner() != null && BotHelpers.isBot(ps.getOwner());
                    String owner = ps.getOwner() != null ? ps.getOwner().getName() : "?";
                    first = appendShop(sb, first, ii, ch, mapId, "playershop", bot, null,
                            owner, ps.getDescription(), ps.getItems());
                    shopCount++;
                }
            }
        }
        sb.append("],\"shopCount\":").append(shopCount).append('}');

        Files.createDirectories(OUT_DIR);
        Path tmp = OUT_DIR.resolve("listings.json.tmp");
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, OUT_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicNotSupported) {
            Files.move(tmp, OUT_FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean appendShop(StringBuilder sb, boolean first, ItemInformationProvider ii,
                                      Channel ch, int mapId, String shopType, boolean bot, String tier,
                                      String owner, String desc, List<PlayerShopItem> items) {
        if (items == null) return first;
        for (PlayerShopItem psi : items) {
            Item item = psi.getItem();
            if (item == null || psi.getBundles() <= 0) continue;
            int itemId = item.getItemId();

            if (!first) sb.append(',');
            first = false;

            sb.append('{');
            num(sb, "itemId", itemId);
            str(sb, "name", nameOf(ii, itemId));
            num(sb, "price", psi.getPrice());
            num(sb, "bundles", psi.getBundles());
            num(sb, "perBundle", item.getQuantity());
            num(sb, "world", ch.getWorld());
            num(sb, "channel", ch.getId());
            num(sb, "room", mapId - 910000000);
            str(sb, "region", FMShopInfoManager.getRegionByMapId(mapId));
            str(sb, "shopType", shopType);
            sb.append("\"bot\":").append(bot).append(',');
            if (tier != null) str(sb, "tier", tier);
            str(sb, "owner", owner);
            str(sb, "shopTitle", desc);

            if (item instanceof Equip eq) {
                Integer req = safeReqLevel(ii, itemId);
                if (req != null) num(sb, "reqLevel", req);
                sb.append("\"stats\":{");
                int before = sb.length();
                stat(sb, "STR", eq.getStr());
                stat(sb, "DEX", eq.getDex());
                stat(sb, "INT", eq.getInt());
                stat(sb, "LUK", eq.getLuk());
                stat(sb, "HP", eq.getHp());
                stat(sb, "MP", eq.getMp());
                stat(sb, "ATT", eq.getWatk());
                stat(sb, "M.ATT", eq.getMatk());
                stat(sb, "DEF", eq.getWdef());
                stat(sb, "M.DEF", eq.getMdef());
                stat(sb, "ACC", eq.getAcc());
                stat(sb, "AVOID", eq.getAvoid());
                stat(sb, "SPEED", eq.getSpeed());
                stat(sb, "JUMP", eq.getJump());
                if (sb.length() > before) sb.setLength(sb.length() - 1); // trailing comma
                sb.append("},");
                num(sb, "slots", eq.getUpgradeSlots());
                num(sb, "upgrades", eq.getLevel());
            }
            if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
            sb.append('}');
        }
        return first;
    }

    private static String nameOf(ItemInformationProvider ii, int itemId) {
        try {
            String n = ii.getName(itemId);
            return n != null ? n : ("Item " + itemId);
        } catch (Exception e) {
            return "Item " + itemId;
        }
    }

    private static Integer safeReqLevel(ItemInformationProvider ii, int itemId) {
        try {
            return ii.getEquipLevelReq(itemId);
        } catch (Exception e) {
            return null;
        }
    }

    private static void stat(StringBuilder sb, String key, int value) {
        if (value != 0) {
            sb.append('"').append(key).append("\":").append(value).append(',');
        }
    }

    private static void num(StringBuilder sb, String key, long value) {
        sb.append('"').append(key).append("\":").append(value).append(',');
    }

    private static void str(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":\"");
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
        }
        sb.append("\",");
    }
}
