# Builds fm-dashboard/data/skills.json and data/quests.json for the Jobs, Skills and Quests
# tabs of the dashboard. Reads only the extracted WZ XML in ../wz (no database needed).
#
#   powershell -ExecutionPolicy Bypass -File fm-dashboard\build-gamedata.ps1
#
# Re-run it after changing the WZ files, then reload the page.

$ErrorActionPreference = 'Stop'
$root   = Split-Path -Parent $PSScriptRoot
$wz     = Join-Path $root 'wz'
$outDir = Join-Path $PSScriptRoot 'data'
New-Item -ItemType Directory -Force $outDir | Out-Null

function Load-Xml([string]$rel) {
    $doc = New-Object System.Xml.XmlDocument
    $doc.Load((Join-Path $wz $rel))
    $doc
}
function J([string]$s) {   # JSON string literal
    if ($null -eq $s) { return '""' }
    $sb = New-Object System.Text.StringBuilder ($s.Length + 2)
    [void]$sb.Append('"')
    foreach ($ch in $s.ToCharArray()) {
        switch ($ch) {
            '"'  { [void]$sb.Append('\"') }
            '\'  { [void]$sb.Append('\\') }
            "`n" { [void]$sb.Append('\n') }
            "`r" { }
            "`t" { [void]$sb.Append(' ') }
            default { if ([int]$ch -lt 32) { [void]$sb.Append(' ') } else { [void]$sb.Append($ch) } }
        }
    }
    [void]$sb.Append('"')
    $sb.ToString()
}
function Val($node, [string]$name) {   # value of a child <int|string|short name=...>
    if ($null -eq $node) { return $null }
    $n = $node.SelectSingleNode("*[@name='$name']")
    if ($n) { $n.GetAttribute('value') } else { $null }
}
function Names([string]$rel, [string]$field = 'name') {
    $map = @{}
    foreach ($node in (Load-Xml $rel).SelectNodes("//imgdir[string[@name='$field']]")) {
        $id = 0
        if ([int]::TryParse($node.GetAttribute('name'), [ref]$id)) {
            $map[$id] = $node.SelectSingleNode("string[@name='$field']").GetAttribute('value')
        }
    }
    $map
}

Write-Host 'Reading names...'
$itemNames = @{}
foreach ($f in 'Eqp', 'Consume', 'Ins', 'Etc', 'Cash', 'Pet') {
    $n = Names "String.wz\$f.img.xml"
    foreach ($k in $n.Keys) { $itemNames[$k] = $n[$k] }
}
$mobNames = Names 'String.wz\Mob.img.xml'
$npcNames = Names 'String.wz\Npc.img.xml'
$mapNames = Names 'String.wz\Map.img.xml' 'mapName'

# Turns MapleStory text codes into plain text, in passes:
#  1. name codes with an ID: #t4000018# item, #o100100# mob, #p/#@ NPC, #m map, #q skill; others (#i icon, #c count...) dropped
#  2. #h# (player name) -> "you"; #Wprob#-style labels dropped
#  3. color/style codes (#b #k #r #e #n ...) and any stray '#'
$idCodeRx = [regex]'#([a-zA-Z@])(\d+)(?::[^#]*)?#'
$idEval = [System.Text.RegularExpressions.MatchEvaluator] {
    param($m)
    $id = [int]$m.Groups[2].Value
    $map = switch -CaseSensitive ($m.Groups[1].Value) {
        't' { $itemNames } 'z' { $itemNames } 'v' { $itemNames }
        'o' { $mobNames } 'p' { $npcNames } '@' { $npcNames } 'm' { $mapNames }
        default { $null }
    }
    if ($m.Groups[1].Value -ceq 'q' -and $skillText.ContainsKey($id)) { return $skillText[$id].name }
    if ($map -and $map.ContainsKey($id)) { return $map[$id] }
    ''
}
function Clean([string]$s) {
    if (-not $s) { return '' }
    $s = $s.Replace('\r\n', "`n").Replace('\n', "`n").Replace('\r', '')
    $s = $idCodeRx.Replace($s, $idEval)
    $s = $s -replace '#h ?\d*#', 'you' -replace '#W\w*#', ''
    $s = $s -creplace '#[a-zA-Z]', '' -replace '#', ''
    ($s -replace '[ ]{2,}', ' ' -replace "\n{3,}", "`n`n").Trim()
}

# ======================================================================== skills / jobs
Write-Host 'Reading skills...'
$skillStr = Load-Xml 'String.wz\Skill.img.xml'
$jobNames = @{}; $skillText = @{}
foreach ($node in $skillStr.DocumentElement.SelectNodes('imgdir')) {
    $id = [int]$node.GetAttribute('name')
    $book = Val $node 'bookName'
    if ($book) { $jobNames[$id] = $book; continue }
    $h = @{}
    foreach ($s in $node.SelectNodes("string[starts-with(@name,'h')]")) {
        $lv = 0
        if ([int]::TryParse($s.GetAttribute('name').Substring(1), [ref]$lv)) { $h[$lv] = Clean $s.GetAttribute('value') }
    }
    $skillText[$id] = @{ name = (Val $node 'name'); desc = (Val $node 'desc'); h = $h }
}

$skip = 'name', 'hs', 'action', 'icon', 'iconMouseOver', 'iconDisabled', 'effect', 'hit', 'ball', 'lt', 'rb', 'special', 'affected', 'prepare', 'keydown', 'keydownend', 'mob', 'summon'
function Level-Fallback($lvNode) {   # "MP -25; damage 100; ..." when String.wz has no hN line
    $parts = @()
    foreach ($c in $lvNode.ChildNodes) {
        if ($c.LocalName -notin 'int', 'short', 'string') { continue }
        $k = $c.GetAttribute('name')
        if ($k -in $skip) { continue }
        $v = $c.GetAttribute('value')
        switch ($k) {
            'mpCon' { $parts += "MP -$v" } 'hpCon' { $parts += "HP -$v" }
            'damage' { $parts += "Damage $v%" } 'time' { $parts += "$v sec" }
            'prop' { $parts += "$v% chance" } 'mobCount' { $parts += "$v monsters" }
            'attackCount' { $parts += "$v hits" } 'cooltime' { $parts += "Cooldown $v sec" }
            default { $parts += "$k $v" }
        }
    }
    $parts -join '; '
}

function Job-Tier([int]$id) {
    if ($id -lt 1000) {
        if ($id -eq 0) { return 0 }
        if ($id % 100 -eq 0) { return 1 }
        if ($id % 10 -eq 0) { return 2 }
        return 2 + ($id % 10)
    }
    if ($id % 1000 -eq 0) { return 0 }
    if ($id % 100 -eq 0) { return 1 }
    if ($id % 10 -eq 0) { return 2 }
    2 + ($id % 10)
}
$tierLevel = @{ 0 = 1; 1 = 10; 2 = 30; 3 = 70; 4 = 120 }
# String.wz only has skill-book titles ("Crusader's Guide"), so v83 job names are listed here.
$jobTitle = @{
    0 = 'Beginner'
    100 = 'Warrior'; 110 = 'Fighter'; 111 = 'Crusader'; 112 = 'Hero'; 120 = 'Page'; 121 = 'White Knight'; 122 = 'Paladin'
    130 = 'Spearman'; 131 = 'Dragon Knight'; 132 = 'Dark Knight'
    200 = 'Magician'; 210 = 'Wizard (Fire, Poison)'; 211 = 'Mage (Fire, Poison)'; 212 = 'Arch Mage (Fire, Poison)'
    220 = 'Wizard (Ice, Lightning)'; 221 = 'Mage (Ice, Lightning)'; 222 = 'Arch Mage (Ice, Lightning)'
    230 = 'Cleric'; 231 = 'Priest'; 232 = 'Bishop'
    300 = 'Bowman'; 310 = 'Hunter'; 311 = 'Ranger'; 312 = 'Bowmaster'; 320 = 'Crossbowman'; 321 = 'Sniper'; 322 = 'Marksman'
    400 = 'Thief'; 410 = 'Assassin'; 411 = 'Hermit'; 412 = 'Night Lord'; 420 = 'Bandit'; 421 = 'Chief Bandit'; 422 = 'Shadower'
    500 = 'Pirate'; 510 = 'Brawler'; 511 = 'Marauder'; 512 = 'Buccaneer'; 520 = 'Gunslinger'; 521 = 'Outlaw'; 522 = 'Corsair'
    800 = 'Manager'; 900 = 'GM'; 910 = 'Super GM'
    1000 = 'Noblesse'; 2000 = 'Legend'
}
foreach ($pair in @(@(1100, 'Dawn Warrior'), @(1200, 'Blaze Wizard'), @(1300, 'Wind Archer'), @(1400, 'Night Walker'), @(1500, 'Thunder Breaker'), @(2100, 'Aran'))) {
    $base = $pair[0]
    $jobTitle[$base] = "$($pair[1]) (1st)"; $jobTitle[$base + 10] = "$($pair[1]) (2nd)"
    $jobTitle[$base + 11] = "$($pair[1]) (3rd)"; $jobTitle[$base + 12] = "$($pair[1]) (4th)"
}

$sb = New-Object System.Text.StringBuilder (4MB)
[void]$sb.Append('{"jobs":[')
$firstJob = $true; $skillCount = 0
$jobFiles = Get-ChildItem (Join-Path $wz 'Skill.wz') -Filter '*.img.xml' | Where-Object { $_.Name -match '^\d+\.img\.xml$' } |
    Sort-Object { [int]($_.Name -replace '\.img\.xml$', '') }
foreach ($file in $jobFiles) {
    $jobId = [int]($file.Name -replace '\.img\.xml$', '')
    $tier = Job-Tier $jobId
    $req = $tierLevel[$tier]
    if ($jobId -eq 200) { $req = 8 }   # Magician advances at 8
    $jname = if ($jobTitle.ContainsKey($jobId)) { $jobTitle[$jobId] } elseif ($jobNames.ContainsKey($jobId)) { $jobNames[$jobId] } else { "Job $jobId" }
    $book = if ($jobNames.ContainsKey($jobId)) { $jobNames[$jobId] } else { '' }

    $doc = Load-Xml "Skill.wz\$($file.Name)"
    $skillsNode = $doc.SelectSingleNode("/imgdir/imgdir[@name='skill']")
    if (-not $firstJob) { [void]$sb.Append(',') }; $firstJob = $false
    [void]$sb.Append('{"id":').Append($jobId).Append(',"name":').Append((J $jname)).Append(',"book":').Append((J $book))
    [void]$sb.Append(',"tier":').Append($tier).Append(',"level":').Append($req).Append(',"skills":[')
    $firstSkill = $true
    if ($skillsNode) {
        foreach ($sk in $skillsNode.SelectNodes('imgdir')) {
            $sid = [int]$sk.GetAttribute('name')
            $txt = $skillText[$sid]
            $levels = @($sk.SelectNodes("imgdir[@name='level']/imgdir") | Sort-Object { [int]$_.GetAttribute('name') })
            $max = $levels.Count
            $name = if ($txt -and $txt.name) { $txt.name } else { "Skill $sid" }
            $desc = if ($txt) { $txt.desc } else { '' }
            $master = $null
            if ($desc -match '\[Master Level\s*:\s*(\d+)\]') { $master = [int]$Matches[1] }
            $desc = Clean ($desc -replace '\[Master Level\s*:\s*\d+\]', '')

            if (-not $firstSkill) { [void]$sb.Append(',') }; $firstSkill = $false
            [void]$sb.Append('{"id":').Append($sid).Append(',"name":').Append((J $name))
            [void]$sb.Append(',"desc":').Append((J $desc)).Append(',"max":').Append($max)
            if ($master) { [void]$sb.Append(',"master":').Append($master) }
            if ((Val $sk 'invisible') -eq '1') { [void]$sb.Append(',"hidden":1') }
            $reqNode = $sk.SelectSingleNode("imgdir[@name='req']")
            if ($reqNode -and $reqNode.ChildNodes.Count) {
                [void]$sb.Append(',"req":[')
                $fr = $true
                foreach ($r in $reqNode.ChildNodes) {
                    if (-not $fr) { [void]$sb.Append(',') }; $fr = $false
                    [void]$sb.Append('[').Append($r.GetAttribute('name')).Append(',').Append($r.GetAttribute('value')).Append(']')
                }
                [void]$sb.Append(']')
            }
            [void]$sb.Append(',"lv":[')
            for ($i = 0; $i -lt $max; $i++) {
                $lvNo = [int]$levels[$i].GetAttribute('name')
                $line = if ($txt -and $txt.h.ContainsKey($lvNo)) { $txt.h[$lvNo] } else { Level-Fallback $levels[$i] }
                if ($i) { [void]$sb.Append(',') }
                [void]$sb.Append((J $line))
            }
            [void]$sb.Append(']}')
            $skillCount++
        }
    }
    [void]$sb.Append(']}')
}
[void]$sb.Append('],"generatedAt":').Append([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()).Append('}')
[System.IO.File]::WriteAllText((Join-Path $outDir 'skills.json'), $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Wrote skills.json: $($jobFiles.Count) jobs, $skillCount skills"

# ======================================================================== quests
Write-Host 'Reading quests...'
$info  = Load-Xml 'Quest.wz\QuestInfo.img.xml'
$check = Load-Xml 'Quest.wz\Check.img.xml'
$act   = Load-Xml 'Quest.wz\Act.img.xml'
$checkById = @{}; foreach ($n in $check.DocumentElement.SelectNodes('imgdir')) { $checkById[$n.GetAttribute('name')] = $n }
$actById   = @{}; foreach ($n in $act.DocumentElement.SelectNodes('imgdir'))   { $actById[$n.GetAttribute('name')] = $n }

$usedItems = @{}; $usedMobs = @{}; $usedNpcs = @{}
function Pairs($parent, [string]$listName, [string]$a, [string]$b, $used) {
    $out = @()
    if ($null -eq $parent) { return $out }
    foreach ($e in $parent.SelectNodes("imgdir[@name='$listName']/imgdir")) {
        $x = Val $e $a; $y = Val $e $b
        if ($null -eq $x) { continue }
        if ($null -eq $y) { $y = 0 }
        if ($used) { $used[[int]$x] = $true }
        $out += ,@([int]$x, [int]$y)
    }
    , $out   # the comma stops PowerShell unrolling a single pair into two numbers
}
function Arr($pairs) { '[' + (($pairs | ForEach-Object { '[' + ($_ -join ',') + ']' }) -join ',') + ']' }

$sb = New-Object System.Text.StringBuilder (8MB)
[void]$sb.Append('{"quests":[')
$first = $true; $qCount = 0
foreach ($q in $info.DocumentElement.SelectNodes('imgdir')) {
    $qid = $q.GetAttribute('name')
    $name = Val $q 'name'
    if (-not $name) { continue }
    $c = $checkById[$qid]; $a = $actById[$qid]
    $c0 = if ($c) { $c.SelectSingleNode("imgdir[@name='0']") } else { $null }
    $c1 = if ($c) { $c.SelectSingleNode("imgdir[@name='1']") } else { $null }
    $a1 = if ($a) { $a.SelectSingleNode("imgdir[@name='1']") } else { $null }

    if (-not $first) { [void]$sb.Append(',') }; $first = $false
    $qCount++
    [void]$sb.Append('{"id":').Append($qid).Append(',"name":').Append((J (Clean $name)))
    $parent = Val $q 'parent'; if ($parent) { [void]$sb.Append(',"parent":').Append((J (Clean $parent))) }
    $area = Val $q 'area'; if ($area) { [void]$sb.Append(',"area":').Append($area) }

    $sum = Clean (Val $q 'summary'); if ($sum) { [void]$sb.Append(',"summary":').Append((J $sum)) }
    $d0 = Clean (Val $q '0'); if ($d0) { [void]$sb.Append(',"desc":').Append((J $d0)) }
    $d2 = Clean (Val $q '2'); if ($d2) { [void]$sb.Append(',"done":').Append((J $d2)) }

    $lvmin = Val $c0 'lvmin'; if ($lvmin) { [void]$sb.Append(',"lvmin":').Append($lvmin) }
    $lvmax = Val $c0 'lvmax'; if ($lvmax) { [void]$sb.Append(',"lvmax":').Append($lvmax) }
    $sn = Val $c0 'npc'; if ($sn) { [void]$sb.Append(',"startNpc":').Append($sn); $usedNpcs[[int]$sn] = $true }
    $en = Val $c1 'npc'; if ($en) { [void]$sb.Append(',"endNpc":').Append($en); $usedNpcs[[int]$en] = $true }
    if ($c0) {
        $jobs = @($c0.SelectNodes("imgdir[@name='job']/*") | ForEach-Object { $_.GetAttribute('value') })
        if ($jobs.Count) { [void]$sb.Append(',"jobs":[').Append(($jobs -join ',')).Append(']') }
        $pre = Pairs $c0 'quest' 'id' 'state' $null
        if ($pre.Count) { [void]$sb.Append(',"pre":').Append((Arr $pre)) }
    }
    $needItems = Pairs $c1 'item' 'id' 'count' $usedItems
    if ($needItems.Count) { [void]$sb.Append(',"items":').Append((Arr $needItems)) }
    $needMobs = Pairs $c1 'mob' 'id' 'count' $usedMobs
    if ($needMobs.Count) { [void]$sb.Append(',"mobs":').Append((Arr $needMobs)) }

    if ($a1) {
        $exp = Val $a1 'exp'; if ($exp) { [void]$sb.Append(',"exp":').Append($exp) }
        $money = Val $a1 'money'; if ($money) { [void]$sb.Append(',"meso":').Append($money) }
        $fame = Val $a1 'pop'; if ($fame) { [void]$sb.Append(',"fame":').Append($fame) }
        # reward items: [id, count, prop]; prop > 0 means "one of these at random"
        $rw = @()
        foreach ($e in $a1.SelectNodes("imgdir[@name='item']/imgdir")) {
            $iid = Val $e 'id'; $cnt = Val $e 'count'
            if ($null -eq $iid -or $null -eq $cnt -or [int]$cnt -le 0) { continue }
            $prop = Val $e 'prop'; if ($null -eq $prop) { $prop = 0 }
            $usedItems[[int]$iid] = $true
            $rw += ,@([int]$iid, [int]$cnt, [int]$prop)
        }
        if ($rw.Count) { [void]$sb.Append(',"reward":').Append((Arr $rw)) }
        $sk = @($a1.SelectNodes("imgdir[@name='skill']/imgdir") | ForEach-Object { Val $_ 'id' } | Where-Object { $_ })
        if ($sk.Count) { [void]$sb.Append(',"skills":[').Append(($sk -join ',')).Append(']') }
    }
    [void]$sb.Append('}')
}
[void]$sb.Append(']')
foreach ($pair in @(@('items', $usedItems, $itemNames), @('mobs', $usedMobs, $mobNames), @('npcs', $usedNpcs, $npcNames))) {
    [void]$sb.Append(',"').Append($pair[0]).Append('":{')
    $f = $true
    foreach ($id in $pair[1].Keys) {
        $nm = if ($pair[2].ContainsKey($id)) { $pair[2][$id] } else { "#$id" }
        if (-not $f) { [void]$sb.Append(',') }; $f = $false
        [void]$sb.Append('"').Append($id).Append('":').Append((J $nm))
    }
    [void]$sb.Append('}')
}
[void]$sb.Append(',"generatedAt":').Append([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()).Append('}')
[System.IO.File]::WriteAllText((Join-Path $outDir 'quests.json'), $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Wrote quests.json: $qCount quests"
