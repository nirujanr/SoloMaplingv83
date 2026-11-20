# Free Market Dashboard

Searchable list of everything currently for sale in the Free Market (bot and player shops,
all FM rooms and channels). Refreshes every 30 seconds.

## How it works
The game server (`soloMapling/server/FMListingExporter.java`) writes all open FM listings to
`fm-dashboard/data/listings.json` every 30 seconds. This container is just nginx serving
`index.html`, which reads that file.

## Run
From the project root (rebuilds the server too, since the exporter is new Java code):

    docker compose up -d --build

Then open http://localhost:8080

Dashboard only (after changing index.html):

    docker compose up -d --build fm-dashboard

## Drops tab
Searchable monster drop table (mob/item name or ID), with the real chance after the server
drop rate from `config.yaml` (`drop_rate`, `boss_drop_rate` for bosses) and the cheapest
current FM price for each item. Data comes from `data/drops.json`, built by:

    powershell -ExecutionPolicy Bypass -File fm-dashboard\build-drops.ps1

It needs only the db container running. Re-run it after editing `drop_data` or the drop
rates, then reload the page. Click a monster or item name to filter by it; "who drops it"
on the Free Market tab jumps to the drops for that item.

## Jobs, Skills and Quests tabs
Built from the WZ files only (no database), into `data/skills.json` and `data/quests.json`:

    powershell -ExecutionPolicy Bypass -File fm-dashboard\build-gamedata.ps1

- **Jobs**: advancement tree per class with the level for each advancement; click a job for
  its skills, or its quest count for quests restricted to that job.
- **Skills**: every skill per job with description, max level, required skills and what each
  level does (slider, or "Show every level"). Search covers all jobs. Icons load per job from
  maplestory.io, so the first view of a job can take a few seconds.
- **Quests**: search by quest, NPC, item, monster or ID; filter by your level, job and reward
  type. Shows start/end NPC, what to collect or hunt, rewards ("one at random" items are
  dashed) and the quest story. Untranslated (Korean) event quests are hidden by default.

Item, monster and NPC pictures are loaded from maplestory.io (GMS v83 data) and need internet.

## Items tab
Item dictionary built by `build-items.ps1` into `data/items.json`: every named item from the WZ files
(equips from Character.wz, use/setup/etc/cash/pets from Item.wz) with names and descriptions from
String.wz, plus NPC shop prices from the database (`shops`/`shopitems`).

- List: search by name or ID; filter by category, equip slot, job and required level; sort by
  level, name or NPC sell price. Shows key stats (or the effect for use items) and the FM price.
  NX cosmetics are hidden unless you pick the "Cosmetic" category.
- Item page (like MeowDB): requirements (level, STR/DEX/INT/LUK, job), stats, attack speed,
  upgrade slots, NPC sell price, flags (untradeable, one-of-a-kind, quest item...), the effect in
  plain words (potions, buffs, cures, return scrolls, scroll success/stats, throwing stars...),
  description, current FM listings, monsters that drop it with the best map to farm, NPC shops
  that sell it (and where the NPC stands), quests that reward or need it, other items for the
  same slot, and every scroll that works on it.

Clicking an item name anywhere on the dashboard opens its item page.

## Mobs and Maps tabs
Two tabs that share one data file; each keeps its own search and filters.
Built by `build-mobmaps.ps1` from the `life` lists in Map.wz, monster stats in Mob.wz, and the
area-boss scripts (`scripts/event/AreaBoss*.js`, e.g. Mano every ~3h) into `data/mobmaps.json`.

- **Monsters**: level, HP, EXP (after the server EXP rate). Click one for its full stats, every
  map it spawns on (and how many), all its drops with chances and FM prices, and quests that hunt it.
- **Maps**: search by map name or by a monster in it. Set a level range to rank maps as training
  spots (most monsters in your range first). A map page lists its monsters and NPCs; click an NPC
  for the quests it starts.
- Monsters that aren't on any map (Zakum, Horntail, PQ mobs...) are spawned by events or summons.
- **Map picture** (on a map's page): the minimap with every spawn point drawn on it, coloured per
  monster (timed respawns are bigger with a white ring), NPCs (green, click for their quests) and
  portals (blue, click to open the destination map; purple = warp inside the map). Toggles for
  each layer and floor lines/ropes, and a legend to show one monster at a time. "Scene" shows the
  full map picture instead. Layouts come from `data/maps/<id>.json` (written by
  `build-mobmaps.ps1`); the minimap and scene pictures load from maplestory.io.

Clicking an item anywhere shows the monsters that drop it; clicking a monster opens its page.

## Leveling tab
Built by `build-leveling.ps1` into `data/leveling.json` from the server's EXP table
(`src/main/java/constants/game/ExpTable.java`) and the rates/switches in `config.yaml`.

- EXP calculator: your level, EXP % and target level -> EXP needed; pick a monster and solo/party
  size to get EXP per kill and kills needed (same formula as `server/life/Monster.java`).
- Best monsters for your level: regular monsters within 5 levels that spawn on a map, ranked by
  EXP per HP, with kills to your target and the best map.
- Rule cards (rates, EXP sharing, AP/SP/HP/MP per level, death penalty, switched-off features),
  generated from the config so they stay correct, and the full EXP table with your level highlighted.

## Server rates
`build-mobmaps.ps1` also writes `data/rates.json` (EXP, meso, drop, boss drop and quest rates of
the first world in `config.yaml`), shown at the top of every tab. Drop chances, meso drops and
monster EXP on the page include these rates.

## Offline pictures
`fetch-icons.ps1` downloads every item, monster, NPC and skill icon plus every minimap from
maplestory.io into `data/icons` (resumable; already-saved files are skipped). The page uses these
local copies first and only goes online for anything missing. Run it after the build scripts so it
knows every ID; re-run it after adding items or maps. `-SkipMinimaps` leaves out the minimaps;
`-Retry` asks again for pictures that were missing last time. `-Scenes` also saves the full
"Scene" map pictures as JPEG into `data/icons/scene` (~2,600 maps, ~700 MB, ~10-15 minutes the
first time); without it the Scene view loads them from maplestory.io.

    powershell -ExecutionPolicy Bypass -File fm-dashboard\fetch-icons.ps1
    powershell -ExecutionPolicy Bypass -File fm-dashboard\fetch-icons.ps1 -Scenes

## Rebuild everything

    powershell -ExecutionPolicy Bypass -File fm-dashboard\build-all.ps1

## Notes
- Listings appear about 10s after the server is online, and bot shops only exist after the
  first player has logged in (and relogged), the same as in-game.
- If the "Updated" time turns red, the file hasn't changed for 2+ minutes - the game server
  is probably stopped.
- Anyone who can reach port 8080 can view it (read-only). With Tailscale, friends can open
  http://<your-tailscale-ip>:8080
