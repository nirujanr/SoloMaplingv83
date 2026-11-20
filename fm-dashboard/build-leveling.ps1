# Builds fm-dashboard/data/leveling.json for the "Leveling" tab: the EXP table from the server
# source (constants/game/ExpTable.java), the world rates and the leveling-related switches from
# config.yaml. Re-run after changing either, then reload the page.
#
#   powershell -ExecutionPolicy Bypass -File fm-dashboard\build-leveling.ps1

$ErrorActionPreference = 'Stop'
$root   = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $PSScriptRoot 'data'
New-Item -ItemType Directory -Force $outDir | Out-Null

$src = Get-Content (Join-Path $root 'src\main\java\constants\game\ExpTable.java') -Raw
$m = [regex]::Match($src, 'int\[\]\s+exp\s*=\s*\{([^}]*)\}')
if (-not $m.Success) { throw 'Could not find the exp table in ExpTable.java' }
$exp = ($m.Groups[1].Value -split ',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }

$cfg = Get-Content (Join-Path $root 'config.yaml') -Raw
function CfgVal([string]$key, $default) {
    $mm = [regex]::Match($cfg, "(?m)^\s*$key\s*:\s*([^\s#]+)")
    if ($mm.Success) { $mm.Groups[1].Value } else { $default }
}
$rates = 'exp_rate', 'meso_rate', 'drop_rate', 'boss_drop_rate', 'quest_rate' | ForEach-Object { """$($_ -replace '_rate$', '')"":$(CfgVal $_ 1)" }
$flagKeys = 'USE_QUEST_RATE', 'USE_ENFORCE_NOVICE_EXPRATE', 'USE_ADD_RATES_BY_LEVEL', 'USE_STACK_COUPON_RATES', 'USE_ENFORCE_MOB_LEVEL_RANGE',
            'USE_FULL_HOLY_SYMBOL', 'USE_AUTOASSIGN_STARTERS_AP', 'USE_RANDOMIZE_HPMP_GAIN', 'USE_ENFORCE_JOB_LEVEL_RANGE',
            'USE_ADD_SLOTS_BY_LEVEL', 'USE_PERFECT_PITCH', 'USE_SERVER_AUTOASSIGNER'
$flags = $flagKeys | ForEach-Object { """$_"":$((CfgVal $_ 'false').ToLower())" }
$nums = 'PARTY_BONUS_EXP_RATE', 'EXP_SPLIT_MVP_MOD', 'EXP_SPLIT_COMMON_MOD', 'EXP_SPLIT_LEVEL_INTERVAL', 'EXP_SPLIT_LEECH_INTERVAL',
        'PQ_BONUS_EXP_RATE', 'RESPAWN_INTERVAL' | ForEach-Object { """$_"":$(CfgVal $_ 0)" }

$json = '{"generatedAt":' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() +
        ',"exp":[' + ($exp -join ',') + ']' +
        ',"rates":{' + ($rates -join ',') + '}' +
        ',"flags":{' + ($flags -join ',') + '}' +
        ',"numbers":{' + ($nums -join ',') + '}}'
[System.IO.File]::WriteAllText((Join-Path $outDir 'leveling.json'), $json, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Wrote leveling.json: $($exp.Count) levels, rates {$($rates -join ', ')}"
