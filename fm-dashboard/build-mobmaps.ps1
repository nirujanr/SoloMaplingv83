# Builds fm-dashboard/data/mobmaps.json for the "Mobs & Maps" tab: every monster with its
# stats, every map that has monsters or NPCs, and which monsters/NPCs spawn on which map.
# Reads only the extracted WZ XML in ../wz (Map.wz life lists, Mob.wz info, String.wz names).
#
#   powershell -ExecutionPolicy Bypass -File fm-dashboard\build-mobmaps.ps1
#
# Re-run it after changing map or mob WZ files, then reload the page.

$ErrorActionPreference = 'Stop'
$root   = Split-Path -Parent $PSScriptRoot
$wz     = Join-Path $root 'wz'
$outDir = Join-Path $PSScriptRoot 'data'
New-Item -ItemType Directory -Force $outDir | Out-Null

function J([string]$s) {
    if ($null -eq $s) { return '""' }
    '"' + $s.Replace('\', '\\').Replace('"', '\"').Replace("`r", '').Replace("`n", ' ').Replace("`t", ' ') + '"'
}
function Load-Xml([string]$rel) { $d = New-Object System.Xml.XmlDocument; $d.Load((Join-Path $wz $rel)); $d }

# ---------------------------------------------------------------- names
Write-Host 'Reading names...'
$mobNames = @{}
foreach ($n in (Load-Xml 'String.wz\Mob.img.xml').SelectNodes("//imgdir[string[@name='name']]")) {
    $mobNames[[int]$n.GetAttribute('name')] = $n.SelectSingleNode("string[@name='name']").GetAttribute('value')
}
$npcNames = @{}
foreach ($n in (Load-Xml 'String.wz\Npc.img.xml').SelectNodes("//imgdir[string[@name='name']]")) {
    $npcNames[[int]$n.GetAttribute('name')] = $n.SelectSingleNode("string[@name='name']").GetAttribute('value')
}
# map id -> street, name, region (top-level group in String.wz/Map.img)
$mapInfo = @{}
foreach ($region in (Load-Xml 'String.wz\Map.img.xml').DocumentElement.SelectNodes('imgdir')) {
    $rk = $region.GetAttribute('name')
    foreach ($m in $region.SelectNodes('imgdir')) {
        $street = $m.SelectSingleNode("string[@name='streetName']"); $name = $m.SelectSingleNode("string[@name='mapName']")
        $mapInfo[[int]$m.GetAttribute('name')] = @(
            $(if ($street) { $street.GetAttribute('value') } else { '' }),
            $(if ($name) { $name.GetAttribute('value') } else { '' }),
            $rk)
    }
}

# ---------------------------------------------------------------- maps
Write-Host 'Scanning Map.wz (this takes a little while)...'
$lifeRx = [regex]'<imgdir name="\d+">([\s\S]*?)</imgdir>'   # one life entry; fields come in any order
$typeRx = [regex]'name="type" value="([mn])"'
$idRx = [regex]'<string name="id" value="(\d+)"'
$mobTimeRx = [regex]'name="mobTime" value="(-?\d+)"'
$linkRx = [regex]'<string name="link" value="(\d+)"/>'
$townRx = [regex]'<int name="town" value="1"/>'

# ---- per-map layout for the map picture: data/maps/<id>.json
# {"mini":[canvasW,canvasH,worldW,worldH,centerX,centerY,mag] | null, "vr":[l,t,r,b] | null,
#  "fh":[x1,y1,x2,y2, ...], "ropes":[[x,y1,y2,isLadder]], "portals":[[x,y,type,toMap,"name"]],
#  "life":[["m"|"n", id, x, y, mobTime]]}
function Section([string]$text, [string]$name) {
    $s = $text.IndexOf("`n  <imgdir name=""$name"">")
    if ($s -lt 0) { return '' }
    $e = $text.IndexOf("`n  </imgdir>", $s + 5)
    if ($e -lt 0) { $e = $text.Length }
    $text.Substring($s, $e - $s)
}
$entryRx = [regex]'<imgdir name="[^"]+">([^<]*(?:<(?!/?imgdir)[^<]*)*)</imgdir>'   # innermost imgdir blocks
function IntOf([string]$body, [string]$key) {
    $m = [regex]::Match($body, "name=""$key"" value=""(-?[\d.]+)""")
    if ($m.Success) { [int][double]$m.Groups[1].Value } else { $null }
}
function StrOf([string]$body, [string]$key) {
    $m = [regex]::Match($body, "<string name=""$key"" value=""([^""]*)""")
    if ($m.Success) { $m.Groups[1].Value } else { '' }
}
function Map-Detail([string]$text) {
    $parts = @()
    $mm = Section $text 'miniMap'
    $cw = $null
    if ($mm) {
        $cv = [regex]::Match($mm, '<canvas name="canvas" width="(\d+)" height="(\d+)"')
        if ($cv.Success) {
            $vals = @($cv.Groups[1].Value, $cv.Groups[2].Value) + @('width', 'height', 'centerX', 'centerY', 'mag' | ForEach-Object { IntOf $mm $_ })
            if ($vals -notcontains $null) { $cw = '[' + ($vals -join ',') + ']' }
        }
    }
    $parts += '"mini":' + $(if ($cw) { $cw } else { 'null' })
    $vr = @('VRLeft', 'VRTop', 'VRRight', 'VRBottom' | ForEach-Object { IntOf $text.Substring(0, [Math]::Min(3000, $text.Length)) $_ })
    $parts += '"vr":' + $(if ($vr -notcontains $null) { '[' + ($vr -join ',') + ']' } else { 'null' })

    $fh = New-Object System.Collections.Generic.List[string]
    foreach ($m in $entryRx.Matches((Section $text 'foothold'))) {
        $b = $m.Groups[1].Value
        $x1 = IntOf $b 'x1'; $y1 = IntOf $b 'y1'; $x2 = IntOf $b 'x2'; $y2 = IntOf $b 'y2'
        if ($null -ne $x1 -and $null -ne $y2) { $fh.Add("$x1,$y1,$x2,$y2") }
    }
    $parts += '"fh":[' + ($fh -join ',') + ']'

    $ropes = foreach ($m in $entryRx.Matches((Section $text 'ladderRope'))) {
        $b = $m.Groups[1].Value
        "[$(IntOf $b 'x'),$(IntOf $b 'y1'),$(IntOf $b 'y2'),$(if ((IntOf $b 'l') -eq 1) { 1 } else { 0 })]"
    }
    $parts += '"ropes":[' + (@($ropes) -join ',') + ']'

    $portals = foreach ($m in $entryRx.Matches((Section $text 'portal'))) {
        $b = $m.Groups[1].Value
        $x = IntOf $b 'x'; $y = IntOf $b 'y'
        if ($null -eq $x) { continue }
        $tm = IntOf $b 'tm'; if ($null -eq $tm) { $tm = 999999999 }
        "[$x,$y,$(IntOf $b 'pt'),$tm,$(J (StrOf $b 'pn'))]"
    }
    $parts += '"portals":[' + (@($portals) -join ',') + ']'

    $lifeOut = foreach ($m in $entryRx.Matches((Section $text 'life'))) {
        $b = $m.Groups[1].Value
        $type = StrOf $b 'type'; $id = StrOf $b 'id'
        if ($type -notin 'm', 'n' -or -not $id) { continue }
        $x = IntOf $b 'x'; $y = IntOf $b 'cy'; if ($null -eq $y) { $y = IntOf $b 'y' }
        $t = IntOf $b 'mobTime'; if ($null -eq $t) { $t = 0 }
        "[""$type"",$([int]$id),$x,$y,$t]"
    }
    $parts += '"life":[' + (@($lifeOut) -join ',') + ']'
    '{' + ($parts -join ',') + '}'
}
$details = @{}   # mapId -> json

$life = @{}    # mapId -> @{ mobs = @{mobId = @(count, mobTime)}; npcs = @{npcId = 1} }
$links = @{}   # mapId -> linked mapId
$towns = @{}
$files = Get-ChildItem (Join-Path $wz 'Map.wz\Map') -Recurse -Filter '*.img.xml' | Where-Object { $_.Name -match '^\d+\.img\.xml$' }
foreach ($f in $files) {
    $mapId = [int]($f.Name -replace '\.img\.xml$', '')
    $text = [System.IO.File]::ReadAllText($f.FullName)
    $infoEnd = $text.IndexOf('</imgdir>')
    $info = if ($infoEnd -gt 0) { $text.Substring(0, $infoEnd) } else { '' }
    $lm = $linkRx.Match($info)
    if ($lm.Success) { $links[$mapId] = [int]$lm.Groups[1].Value }
    if ($townRx.IsMatch($info)) { $towns[$mapId] = 1 }
    if (-not $lm.Success) { $details[$mapId] = Map-Detail $text }

    $start = $text.IndexOf('<imgdir name="life">')
    if ($start -lt 0) { continue }
    $end = $text.IndexOf("`n  </imgdir>", $start)
    if ($end -lt 0) { $end = $text.Length }
    $section = $text.Substring($start, $end - $start)
    $entry = @{ mobs = @{}; npcs = @{} }
    foreach ($m in $lifeRx.Matches($section)) {
        $body = $m.Groups[1].Value
        $tm = $typeRx.Match($body); $im = $idRx.Match($body)
        if (-not $tm.Success -or -not $im.Success) { continue }
        $id = [int]$im.Groups[1].Value
        if ($tm.Groups[1].Value -eq 'n') { $entry.npcs[$id] = 1; continue }
        $mt = $mobTimeRx.Match($body)
        $mobTime = if ($mt.Success) { [int]$mt.Groups[1].Value } else { 0 }
        if ($entry.mobs.ContainsKey($id)) {
            $entry.mobs[$id][0]++
            if ($mobTime -gt $entry.mobs[$id][1]) { $entry.mobs[$id][1] = $mobTime }
        } else { $entry.mobs[$id] = @(1, $mobTime) }
    }
    if ($entry.mobs.Count -or $entry.npcs.Count) { $life[$mapId] = $entry }
}
# area bosses (Mano, Mushmom, ...) are spawned every few hours by scripts/event/AreaBoss*.js, not by
# the map file. Take the map and mob IDs from each script; a script with several maps picks one at random.
$areaBoss = @()   # [mapId, mobId]
foreach ($s in Get-ChildItem (Join-Path $root 'scripts\event') -Filter 'AreaBoss*.js' -ErrorAction SilentlyContinue) {
    $js = [System.IO.File]::ReadAllText($s.FullName) -replace '(?m)//.*$', ''
    $maps = @([regex]::Matches($js, 'getMap\((\d{6,9})\)|[Mm]ap[Ii]d\w*\s*=\s*\[?([\d,\s]{6,})') | ForEach-Object {
        ($_.Groups[1].Value + ',' + $_.Groups[2].Value) -split '[,\s]+' } | Where-Object { $_ -match '^\d{6,9}$' } | Select-Object -Unique)
    $mobs = @([regex]::Matches($js, 'getMonster\((\d{6,7})\)|[Mm]ob[Ii]d\w*\s*=\s*\[?([\d,\s]{6,})') | ForEach-Object {
        ($_.Groups[1].Value + ',' + $_.Groups[2].Value) -split '[,\s]+' } | Where-Object { $_ -match '^\d{6,7}$' } | Select-Object -Unique)
    foreach ($mp in $maps) { foreach ($mb in $mobs) { $areaBoss += ,@([int]$mp, [int]$mb) } }
}
foreach ($p in $areaBoss) {
    if (-not $life.ContainsKey($p[0])) { $life[$p[0]] = @{ mobs = @{}; npcs = @{} } }
    $life[$p[0]].mobs[$p[1]] = @(1, -3)   # mobTime -3 = area boss from a script (respawns every ~3h)
}

# linked maps reuse the linked map's life
foreach ($k in @($links.Keys)) {
    if (-not $life.ContainsKey($k) -and $life.ContainsKey($links[$k])) { $life[$k] = $life[$links[$k]] }
}

# ---------------------------------------------------------------- mobs
Write-Host 'Reading monster stats...'
$statNames = 'level', 'maxHP', 'maxMP', 'exp', 'PADamage', 'PDDamage', 'MADamage', 'MDDamage', 'acc', 'eva'
$mobStats = @{}
$usedMobs = @{}
foreach ($e in $life.Values) { foreach ($id in $e.mobs.Keys) { $usedMobs[$id] = $true } }
foreach ($id in $mobNames.Keys) { $usedMobs[$id] = $true }
foreach ($id in $usedMobs.Keys) {
    $path = Join-Path $wz ('Mob.wz\{0:D7}.img.xml' -f $id)
    if (-not (Test-Path $path)) { continue }
    $head = (Get-Content $path -TotalCount 50) -join "`n"
    $vals = foreach ($s in $statNames) { if ($head -match "name=""$s"" value=""(-?\d+)""") { [long]$Matches[1] } else { 0 } }
    $boss = if ($head -match 'name="boss" value="1"') { 1 } else { 0 }
    $undead = if ($head -match 'name="undead" value="1"') { 1 } else { 0 }
    $elem = if ($head -match 'name="elemAttr" value="([^"]*)"') { $Matches[1] } else { '' }
    $mobStats[$id] = @{ v = $vals; boss = $boss; undead = $undead; elem = $elem }
}

# ---------------------------------------------------------------- write
$sb = New-Object System.Text.StringBuilder (4MB)
[void]$sb.Append('{"generatedAt":').Append([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())

# mobs: {id: [name, level, hp, mp, exp, PAD, PDD, MAD, MDD, acc, eva, boss, undead, elemAttr]}
[void]$sb.Append(',"mobs":{')
$first = $true
foreach ($id in $mobStats.Keys) {
    $st = $mobStats[$id]
    $name = if ($mobNames.ContainsKey($id)) { $mobNames[$id] } else { "Mob $id" }
    if (-not $first) { [void]$sb.Append(',') }; $first = $false
    [void]$sb.Append('"').Append($id).Append('":[').Append((J $name)).Append(',').Append(($st.v -join ','))
    [void]$sb.Append(',').Append($st.boss).Append(',').Append($st.undead).Append(',').Append((J $st.elem)).Append(']')
}
[void]$sb.Append('}')

# maps: {id: [street, name, region, town, linkedTo]}
[void]$sb.Append(',"maps":{')
$first = $true
foreach ($id in $life.Keys) {
    $mi = if ($mapInfo.ContainsKey($id)) { $mapInfo[$id] } else { @('', "Map $id", '') }
    if (-not $first) { [void]$sb.Append(',') }; $first = $false
    [void]$sb.Append('"').Append($id).Append('":[').Append((J $mi[0])).Append(',').Append((J $mi[1])).Append(',').Append((J $mi[2]))
    [void]$sb.Append(',').Append($(if ($towns.ContainsKey($id)) { 1 } else { 0 })).Append(',').Append($(if ($links.ContainsKey($id)) { $links[$id] } else { 0 })).Append(']')
}
[void]$sb.Append('}')

# spawns: [mapId, mobId, count, mobTime(sec; >0 = timed/boss spawn)]
[void]$sb.Append(',"spawns":[')
$first = $true; $spawnCount = 0; $usedNpcs = @{}
foreach ($id in $life.Keys) {
    foreach ($mob in $life[$id].mobs.Keys) {
        $c = $life[$id].mobs[$mob]
        if (-not $first) { [void]$sb.Append(',') }; $first = $false
        [void]$sb.Append('[').Append($id).Append(',').Append($mob).Append(',').Append($c[0]).Append(',').Append($c[1]).Append(']')
        $spawnCount++
    }
}
[void]$sb.Append(']')

# npcs: [mapId, npcId]
[void]$sb.Append(',"npcs":[')
$first = $true
foreach ($id in $life.Keys) {
    foreach ($npc in $life[$id].npcs.Keys) {
        if (-not $first) { [void]$sb.Append(',') }; $first = $false
        [void]$sb.Append('[').Append($id).Append(',').Append($npc).Append(']')
        $usedNpcs[$npc] = $true
    }
}
[void]$sb.Append('],"npcNames":{')
$first = $true
foreach ($id in $usedNpcs.Keys) {
    $nm = if ($npcNames.ContainsKey($id)) { $npcNames[$id] } else { "NPC $id" }
    if (-not $first) { [void]$sb.Append(',') }; $first = $false
    [void]$sb.Append('"').Append($id).Append('":').Append((J $nm))
}
[void]$sb.Append('}}')

[System.IO.File]::WriteAllText((Join-Path $outDir 'mobmaps.json'), $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))

# per-map layout files (linked maps reuse their target's layout)
$mapsDir = Join-Path $outDir 'maps'
if (Test-Path $mapsDir) { Get-ChildItem $mapsDir -Filter '*.json' | Remove-Item }
New-Item -ItemType Directory -Force $mapsDir | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
$written = 0
foreach ($id in $life.Keys) {
    $src = if ($links.ContainsKey($id)) { $links[$id] } else { $id }
    if (-not $details.ContainsKey($src)) { continue }
    $json = $details[$src]
    if ($src -ne $id) { $json = $json.Substring(0, $json.Length - 1) + ",""imageMap"":$src}" }   # picture comes from the linked map
    [System.IO.File]::WriteAllText((Join-Path $mapsDir "$id.json"), $json, $utf8)
    $written++
}
Write-Host "Wrote $written map layouts to data\maps"

# ---------------------------------------------------------------- server rates (first world in config.yaml)
$cfg = Get-Content (Join-Path $root 'config.yaml') -Raw
$rateJson = @()
foreach ($r in 'exp_rate', 'meso_rate', 'drop_rate', 'boss_drop_rate', 'quest_rate') {
    if ($cfg -match "(?m)^\s*$r\s*:\s*([\d.]+)") { $rateJson += """$($r -replace '_rate$', '')"":$($Matches[1])" }
}
$world = if ($cfg -match '(?m)^#World Name: \(0 "([^"]+)"') { $Matches[1] } else { 'World 0' }
$rateJson += """world"":$(J $world)"
[System.IO.File]::WriteAllText((Join-Path $outDir 'rates.json'), '{' + ($rateJson -join ',') + '}', (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Wrote rates.json: {$($rateJson -join ', ')}"
Write-Host "Wrote mobmaps.json: $($mobStats.Count) monsters, $($life.Count) maps, $spawnCount spawn entries, $($usedNpcs.Count) NPCs"
