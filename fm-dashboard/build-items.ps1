# Builds fm-dashboard/data/items.json for the "Items" tab: every item in the game with its
# name, description, category, stats/requirements (equips) and effects (potions, scrolls, ...),
# plus which NPC shops sell it. Reads the WZ XML in ../wz; NPC shops come from the database
# (skipped with a warning if the solomapling-db container isn't running).
#
#   powershell -ExecutionPolicy Bypass -File fm-dashboard\build-items.ps1

$ErrorActionPreference = 'Stop'
$root   = Split-Path -Parent $PSScriptRoot
$wz     = Join-Path $root 'wz'
$outDir = Join-Path $PSScriptRoot 'data'
New-Item -ItemType Directory -Force $outDir | Out-Null

function J([string]$s) {
    if ($null -eq $s) { return '""' }
    $sb = New-Object System.Text.StringBuilder ($s.Length + 2)
    [void]$sb.Append('"')
    foreach ($ch in $s.ToCharArray()) {
        switch ($ch) {
            '"' { [void]$sb.Append('\"') } '\' { [void]$sb.Append('\\') } "`n" { [void]$sb.Append('\n') }
            default { if ([int]$ch -lt 32) { [void]$sb.Append(' ') } else { [void]$sb.Append($ch) } }
        }
    }
    [void]$sb.Append('"')
    $sb.ToString()
}
function Clean([string]$s) {
    if (-not $s) { return '' }
    $s = $s.Replace('\r\n', "`n").Replace('\n', "`n").Replace('\r', '')
    ($s -creplace '#[a-zA-Z](\d+[:#])?', '' -replace '#', '' -replace '[ ]{2,}', ' ').Trim()
}
function Num([string]$v) {   # "0,3" (some WZ doubles use a comma) -> 0.3
    $v = $v.Replace(',', '.')
    $d = 0.0
    if ([double]::TryParse($v, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$d)) { $d } else { $null }
}
function Obj($pairs) {   # ordered hashtable -> JSON object (numbers stay numbers)
    $parts = foreach ($k in $pairs.Keys) {
        $v = $pairs[$k]
        if ($v -is [string]) { "$(J $k):$(J $v)" } else { "$(J $k):$(([double]$v).ToString([System.Globalization.CultureInfo]::InvariantCulture))" }
    }
    '{' + (@($parts) -join ',') + '}'
}

# ---------------------------------------------------------------- names + descriptions
Write-Host 'Reading names...'
$names = @{}; $descs = @{}
foreach ($f in 'Eqp', 'Consume', 'Ins', 'Etc', 'Cash', 'Pet') {
    $doc = New-Object System.Xml.XmlDocument
    $doc.Load((Join-Path $wz "String.wz\$f.img.xml"))
    foreach ($n in $doc.SelectNodes("//imgdir[string[@name='name']]")) {
        $id = 0
        if (-not [int]::TryParse($n.GetAttribute('name'), [ref]$id)) { continue }
        $names[$id] = $n.SelectSingleNode("string[@name='name']").GetAttribute('value')
        $d = $n.SelectSingleNode("string[@name='desc']")
        if ($d) { $descs[$id] = Clean $d.GetAttribute('value') }
    }
}

$skipKeys = 'islot', 'vslot', 'sfx', 'walk', 'stand', 'afterImage', 'icon', 'iconRaw', 'z', 'delay', 'icon2', 'iconD',
            'iconRaw2', 'iconRawD', 'chatBalloon', 'nameTag', 'sample', 'path', 'bgmPath', 'soundPath', 'effect', 'origin'
$lineRx = [regex]'^    <(int|short|long|string|double|float) name="([^"]+)" value="([^"]*)"'

$items = New-Object System.Collections.Generic.List[string]
function Add-Item([int]$id, [string]$cat, $info, $spec) {
    $name = if ($names.ContainsKey($id)) { $names[$id] } else { return }   # unnamed = unused/dev item
    $s = '{"id":' + $id + ',"n":' + (J $name) + ',"c":' + (J $cat)
    if ($descs.ContainsKey($id) -and $descs[$id]) { $s += ',"d":' + (J $descs[$id]) }
    if ($info.Count) { $s += ',"i":' + (Obj $info) }
    if ($spec -and $spec.Count) { $s += ',"s":' + (Obj $spec) }
    $items.Add($s + '}')
}

# ---------------------------------------------------------------- equips (Character.wz, one file per item)
Write-Host 'Reading equips...'
$equipDirs = 'Accessory', 'Cap', 'Cape', 'Coat', 'Glove', 'Longcoat', 'Pants', 'Ring', 'Shield', 'Shoes', 'Weapon', 'PetEquip', 'TamingMob', 'Face', 'Hair'
foreach ($dir in $equipDirs) {
    $path = Join-Path $wz "Character.wz\$dir"
    if (-not (Test-Path $path)) { continue }
    $cat = if ($dir -in 'Face', 'Hair') { 'Look' } else { 'Equip' }
    if ($dir -in 'Face', 'Hair') { continue }   # faces/hairs aren't items
    foreach ($f in Get-ChildItem $path -Filter '*.img.xml') {
        $id = 0
        if (-not [int]::TryParse(($f.Name -replace '\.img\.xml$', ''), [ref]$id)) { continue }
        if (-not $names.ContainsKey($id)) { continue }
        $info = [ordered]@{}
        $inInfo = $false
        foreach ($line in [System.IO.File]::ReadLines($f.FullName)) {
            if (-not $inInfo) { if ($line -eq '  <imgdir name="info">') { $inInfo = $true }; continue }
            if ($line -eq '  </imgdir>') { break }
            $m = $lineRx.Match($line)
            if (-not $m.Success) { continue }
            $k = $m.Groups[2].Value
            if ($k -in $skipKeys) { continue }
            if ($m.Groups[1].Value -eq 'string') { $info[$k] = $m.Groups[3].Value; continue }
            $v = Num $m.Groups[3].Value
            if ($null -ne $v -and $v -ne 0) { $info[$k] = $v }
        }
        Add-Item $id $(if ($info['cash'] -eq 1) { 'Cosmetic' } else { 'Equip' }) $info $null
    }
}

# ---------------------------------------------------------------- Use / Setup / Etc / Cash / Pet (Item.wz)
Write-Host 'Reading use, setup, etc, cash and pet items...'
function Children($node) {
    $h = [ordered]@{}
    if ($null -eq $node) { return $h }
    foreach ($c in $node.ChildNodes) {
        if ($c.LocalName -notin 'int', 'short', 'long', 'string', 'double', 'float') { continue }
        $k = $c.GetAttribute('name')
        if ($k -in $skipKeys) { continue }
        if ($c.LocalName -eq 'string') { $h[$k] = $c.GetAttribute('value'); continue }
        $v = Num $c.GetAttribute('value')
        if ($null -ne $v -and $v -ne 0) { $h[$k] = $v }
    }
    $h
}
foreach ($pair in @(@('Consume', 'Use'), @('Install', 'Setup'), @('Etc', 'Etc'), @('Cash', 'Cash'))) {
    foreach ($f in Get-ChildItem (Join-Path $wz "Item.wz\$($pair[0])") -Filter '*.img.xml') {
        $doc = New-Object System.Xml.XmlDocument
        $doc.Load($f.FullName)
        foreach ($it in $doc.DocumentElement.SelectNodes('imgdir')) {
            $id = 0
            if (-not [int]::TryParse($it.GetAttribute('name'), [ref]$id)) { continue }
            $info = Children $it.SelectSingleNode("imgdir[@name='info']")
            $spec = Children $it.SelectSingleNode("imgdir[@name='spec']")
            # some cures are flags inside spec (poison/seal/darkness/weakness/curse = 1)
            Add-Item $id $pair[1] $info $spec
        }
    }
}
foreach ($f in Get-ChildItem (Join-Path $wz 'Item.wz\Pet') -Filter '*.img.xml') {
    $id = 0
    if (-not [int]::TryParse(($f.Name -replace '\.img\.xml$', ''), [ref]$id)) { continue }
    $doc = New-Object System.Xml.XmlDocument
    $doc.Load($f.FullName)
    Add-Item $id 'Pet' (Children $doc.DocumentElement.SelectSingleNode("imgdir[@name='info']")) $null
}

# ---------------------------------------------------------------- NPC shops (database)
$shopJson = '[]'; $shopNpcs = @{}
try {
    $rows = docker exec solomapling-db mysql -uroot cosmic -N -B -e 'SELECT s.npcid, i.itemid, i.price FROM shopitems i JOIN shops s ON s.shopid = i.shopid WHERE i.price > 0'
    if ($LASTEXITCODE -ne 0) { throw 'query failed' }
    $shopJson = '[' + ((@($rows) | Where-Object { $_ } | ForEach-Object { $c = $_.Split("`t"); $shopNpcs[[int]$c[0]] = $true; "[$($c[0]),$($c[1]),$($c[2])]" }) -join ',') + ']'
} catch { Write-Warning "NPC shops skipped (is the solomapling-db container running?): $_" }
$npcNames = @{}
$doc = New-Object System.Xml.XmlDocument; $doc.Load((Join-Path $wz 'String.wz\Npc.img.xml'))
foreach ($n in $doc.SelectNodes("//imgdir[string[@name='name']]")) { $npcNames[[int]$n.GetAttribute('name')] = $n.SelectSingleNode("string[@name='name']").GetAttribute('value') }
$npcJson = '{' + ((@($shopNpcs.Keys) | ForEach-Object { """$_"":$(J $(if ($npcNames.ContainsKey($_)) { $npcNames[$_] } else { "NPC $_" }))" }) -join ',') + '}'

$json = '{"generatedAt":' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + ',"items":[' + ($items -join ',') + '],"shops":' + $shopJson + ',"shopNpcs":' + $npcJson + '}'
[System.IO.File]::WriteAllText((Join-Path $outDir 'items.json'), $json, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Wrote items.json: $($items.Count) items, $($shopNpcs.Count) shop NPCs"
