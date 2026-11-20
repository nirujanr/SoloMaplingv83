# Builds fm-dashboard/data/drops.json for the "Drops" tab of the FM dashboard.
#
# Reads the drop tables from the solomapling-db container, and mob/item/quest names,
# mob levels and boss flags from the extracted WZ XML in ../wz. Read-only on the DB.
#
# Run from anywhere (the db container must be running):
#   powershell -ExecutionPolicy Bypass -File fm-dashboard\build-drops.ps1
# Re-run it after you edit drop_data / drop_data_global, then refresh the page.

$ErrorActionPreference = 'Stop'
$root    = Split-Path -Parent $PSScriptRoot
$wz      = Join-Path $root 'wz'
$outDir  = Join-Path $PSScriptRoot 'data'
$outFile = Join-Path $outDir 'drops.json'
$dbContainer = 'solomapling-db'

function Esc([string]$s) {
    if ($null -eq $s) { return '' }
    $s.Replace('\', '\\').Replace('"', '\"').Replace("`r", ' ').Replace("`n", ' ').Replace("`t", ' ')
}

function Query([string]$sql) {
    $out = docker exec $dbContainer mysql -uroot cosmic -N -B -e $sql
    if ($LASTEXITCODE -ne 0) { throw "MySQL query failed. Is the '$dbContainer' container running?" }
    @($out | Where-Object { $_ })
}

# --- names from String.wz -------------------------------------------------------------
function Read-Names([string]$file) {
    $map = @{}
    $path = Join-Path $wz "String.wz\$file"
    if (-not (Test-Path $path)) { Write-Warning "Missing $path"; return $map }
    $xml = New-Object System.Xml.XmlDocument
    $xml.Load($path)
    foreach ($node in $xml.SelectNodes("//imgdir[string[@name='name']]")) {
        $id = 0
        if ([int]::TryParse($node.GetAttribute('name'), [ref]$id)) {
            $map[$id] = $node.SelectSingleNode("string[@name='name']").GetAttribute('value')
        }
    }
    $map
}

Write-Host 'Reading names from String.wz...'
$itemNames = @{}
foreach ($f in 'Eqp.img.xml', 'Consume.img.xml', 'Ins.img.xml', 'Etc.img.xml', 'Cash.img.xml', 'Pet.img.xml') {
    $n = Read-Names $f
    foreach ($k in $n.Keys) { $itemNames[$k] = $n[$k] }
}
$mobNames = Read-Names 'Mob.img.xml'

$questNames = @{}
$questInfo = Join-Path $wz 'Quest.wz\QuestInfo.img.xml'
if (Test-Path $questInfo) {
    $xml = New-Object System.Xml.XmlDocument
    $xml.Load($questInfo)
    foreach ($node in $xml.DocumentElement.SelectNodes("imgdir[string[@name='name']]")) {
        $questNames[[int]$node.GetAttribute('name')] = $node.SelectSingleNode("string[@name='name']").GetAttribute('value')
    }
}

# --- rates from config.yaml -----------------------------------------------------------
$cfg = Get-Content (Join-Path $root 'config.yaml') -Raw
$dropRate = if ($cfg -match '(?m)^\s*drop_rate:\s*(\d+)') { [int]$Matches[1] } else { 1 }
$bossRate = if ($cfg -match '(?m)^\s*boss_drop_rate:\s*(\d+)') { [int]$Matches[1] } else { $dropRate }

# --- drops from MySQL -----------------------------------------------------------------
Write-Host 'Querying drop_data...'
$rows   = Query 'SELECT dropperid, itemid, minimum_quantity, maximum_quantity, chance, questid FROM drop_data'
$global = Query 'SELECT continent, itemid, minimum_quantity, maximum_quantity, chance, questid, comments FROM drop_data_global'

# --- mob level / boss flag from Mob.wz ------------------------------------------------
$mobIds = @{}
foreach ($r in $rows) { $mobIds[[int]($r.Split("`t")[0])] = $true }
$mobInfo = @{}
foreach ($id in $mobIds.Keys) {
    $path = Join-Path $wz ('Mob.wz\{0:D7}.img.xml' -f $id)
    $lv = 0; $boss = 0
    if (Test-Path $path) {
        # level/boss live near the top of the file, in the "info" block
        $head = (Get-Content $path -TotalCount 60) -join "`n"
        if ($head -match 'name="level" value="(\d+)"') { $lv = [int]$Matches[1] }
        if ($head -match 'name="boss" value="1"') { $boss = 1 }
    }
    $mobInfo[$id] = @($lv, $boss)
}

# --- write JSON -----------------------------------------------------------------------
$usedItems = @{}; $usedQuests = @{}
$sb = New-Object System.Text.StringBuilder (2MB)
[void]$sb.Append('{"generatedAt":').Append([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
[void]$sb.Append(',"rates":{"drop":').Append($dropRate).Append(',"boss":').Append($bossRate).Append('}')

# drops: [mobId, itemId, min, max, chance(/1,000,000), questId]
[void]$sb.Append(',"drops":[')
$first = $true
foreach ($r in $rows) {
    $c = $r.Split("`t")
    if (-not $first) { [void]$sb.Append(',') }; $first = $false
    [void]$sb.Append('[').Append(($c[0..5] -join ',')).Append(']')
    $usedItems[[int]$c[1]] = $true
    if ([int]$c[5] -gt 0) { $usedQuests[[int]$c[5]] = $true }
}
[void]$sb.Append(']')

# global: [continent, itemId, min, max, chance, questId, comment]
[void]$sb.Append(',"global":[')
$first = $true
foreach ($r in $global) {
    $c = $r.Split("`t")
    if (-not $first) { [void]$sb.Append(',') }; $first = $false
    [void]$sb.Append('[').Append(($c[0..5] -join ',')).Append(',"').Append((Esc $c[6])).Append('"]')
    $usedItems[[int]$c[1]] = $true
}
[void]$sb.Append(']')

# mobs: {id: [name, level, boss]}
[void]$sb.Append(',"mobs":{')
$first = $true
foreach ($id in $mobIds.Keys) {
    $name = if ($mobNames.ContainsKey($id)) { $mobNames[$id] } else { "Mob $id" }
    if (-not $first) { [void]$sb.Append(',') }; $first = $false
    [void]$sb.Append('"').Append($id).Append('":["').Append((Esc $name)).Append('",')
    [void]$sb.Append($mobInfo[$id][0]).Append(',').Append($mobInfo[$id][1]).Append(']')
}
[void]$sb.Append('}')

# items: {id: name}
[void]$sb.Append(',"items":{')
$first = $true
foreach ($id in $usedItems.Keys) {
    if ($id -eq 0) { continue }
    $name = if ($itemNames.ContainsKey($id)) { $itemNames[$id] } else { "Item $id" }
    if (-not $first) { [void]$sb.Append(',') }; $first = $false
    [void]$sb.Append('"').Append($id).Append('":"').Append((Esc $name)).Append('"')
}
[void]$sb.Append('}')

# quests: {id: name}
[void]$sb.Append(',"quests":{')
$first = $true
foreach ($id in $usedQuests.Keys) {
    $name = if ($questNames.ContainsKey($id)) { $questNames[$id] } else { "Quest $id" }
    if (-not $first) { [void]$sb.Append(',') }; $first = $false
    [void]$sb.Append('"').Append($id).Append('":"').Append((Esc $name)).Append('"')
}
[void]$sb.Append('}}')

New-Item -ItemType Directory -Force $outDir | Out-Null
[System.IO.File]::WriteAllText($outFile, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
Write-Host ("Wrote {0}: {1} drops from {2} mobs, {3} global, rates x{4} (boss x{5})" -f `
    $outFile, $rows.Count, $mobIds.Count, $global.Count, $dropRate, $bossRate)
