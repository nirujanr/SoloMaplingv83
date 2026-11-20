# Downloads every picture the dashboard uses from maplestory.io (GMS v83) into data/icons so the
# page works offline:  data/icons/item/<id>.png, mob/<id>.gif, npc/<id>.png, skill/<id>.png,
# minimap/<mapId>.png. Full "Scene" map pictures are big and stay online-only.
#
# Run after the build-*.ps1 scripts (it reads their JSON to know which IDs exist):
#   powershell -ExecutionPolicy Bypass -File fm-dashboard\fetch-icons.ps1
#   ... -SkipMinimaps      do not download minimaps (~half of the total size)
#   ... -Retry             try again the IDs that were missing last time
#
# Resumable: files already downloaded are skipped, so re-running only fetches what's new.

#   ... -Scenes            also save the full "Scene" map pictures as JPEG (~2,600 maps, ~700 MB,
#                          ~10-15 min; only 4 at a time because maplestory.io renders each one)

param([switch]$SkipMinimaps, [switch]$Retry, [switch]$Scenes, [int]$Parallel = 8)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$data  = Join-Path $PSScriptRoot 'data'
$icons = Join-Path $data 'icons'
$api   = 'https://maplestory.io/api/GMS/83'

function Read-Json([string]$name) {
    $p = Join-Path $data $name
    if (-not (Test-Path $p)) { Write-Warning "$name not found - run the build scripts first"; return $null }
    Get-Content $p -Raw -Encoding UTF8 | ConvertFrom-Json
}

# --- which IDs do we need?
$jobs = @()   # list of @{ kind; id; url; file }
$items = Read-Json 'items.json'
if ($items) { foreach ($it in $items.items) { $jobs += @{ kind = 'item'; url = "$api/item/$($it.id)/icon"; file = "item\$($it.id).png" } } }
$mm = Read-Json 'mobmaps.json'
if ($mm) {
    foreach ($p in $mm.mobs.PSObject.Properties) { $jobs += @{ kind = 'mob'; url = "$api/mob/$($p.Name)/render/stand"; file = "mob\$($p.Name).gif" } }
    $npcIds = @{}
    foreach ($p in $mm.npcNames.PSObject.Properties) { $npcIds[$p.Name] = 1 }
    if ($items -and $items.shopNpcs) { foreach ($p in $items.shopNpcs.PSObject.Properties) { $npcIds[$p.Name] = 1 } }
    $q = Read-Json 'quests.json'
    if ($q) { foreach ($p in $q.npcs.PSObject.Properties) { $npcIds[$p.Name] = 1 } }
    foreach ($id in $npcIds.Keys) { $jobs += @{ kind = 'npc'; url = "$api/npc/$id/icon"; file = "npc\$id.png" } }
    if (-not $SkipMinimaps) {
        $mapsWithMini = Get-ChildItem (Join-Path $data 'maps') -Filter '*.json' | Where-Object {
            (Get-Content $_.FullName -TotalCount 1) -notmatch '^\{"mini":null' }
        foreach ($f in $mapsWithMini) {
            $id = $f.BaseName
            $j = Get-Content $f.FullName -Raw
            if ($j -match '"imageMap":(\d+)') { continue }   # linked maps use their target's minimap
            $jobs += @{ kind = 'minimap'; url = "$api/map/$id/minimap"; file = "minimap\$id.png" }
        }
    }
}

foreach ($k in 'item', 'mob', 'npc', 'skill', 'minimap') { New-Item -ItemType Directory -Force (Join-Path $icons $k) | Out-Null }
$missingFile = Join-Path $icons 'missing.txt'
$missing = @{}
if ((Test-Path $missingFile) -and -not $Retry) { Get-Content $missingFile | ForEach-Object { $missing[$_] = 1 } }

$todo = @($jobs | Where-Object { -not $missing.ContainsKey($_.file) -and -not (Test-Path (Join-Path $icons $_.file)) })
Write-Host ("{0} pictures known, {1} to download" -f $jobs.Count, $todo.Count)

# --- download, $Parallel at a time
# .NET Framework allows only 2 connections per host by default, which serializes the downloads
[System.Net.ServicePointManager]::DefaultConnectionLimit = [Math]::Max(16, $Parallel * 2)
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(60)
$client.DefaultRequestHeaders.UserAgent.ParseAdd('SoloMapling-dashboard/1.0')
$ok = 0; $fail = 0; $done = 0
for ($i = 0; $i -lt $todo.Count; $i += $Parallel) {
    $batch = $todo[$i..([Math]::Min($i + $Parallel, $todo.Count) - 1)]
    $tasks = foreach ($j in $batch) { $client.GetAsync($j.url) }
    try { [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$tasks) } catch { }
    for ($k = 0; $k -lt $batch.Count; $k++) {
        $t = $tasks[$k]; $j = $batch[$k]
        if ($t.Status -eq 'RanToCompletion' -and $t.Result.IsSuccessStatusCode -and $t.Result.Content.Headers.ContentType.MediaType -like 'image/*') {
            $bytes = $t.Result.Content.ReadAsByteArrayAsync().Result
            [System.IO.File]::WriteAllBytes((Join-Path $icons $j.file), $bytes)
            $ok++
        } else {
            # 404 = maplestory.io has no picture (custom/unused item): remember it so we don't ask again
            if ($t.Status -eq 'RanToCompletion' -and [int]$t.Result.StatusCode -eq 404) { $missing[$j.file] = 1 }
            $fail++
        }
        if ($t.Status -eq 'RanToCompletion') { $t.Result.Dispose() }
    }
    $done += $batch.Count
    if ($done % 800 -lt $Parallel -or $done -eq $todo.Count) {
        Write-Host ("  {0}/{1}  saved {2}, missing {3}" -f $done, $todo.Count, $ok, $fail)
        Set-Content $missingFile ($missing.Keys | Sort-Object) -Encoding UTF8
    }
}

# --- skill icons come in one JSON "skillbook" per job, as base64
$sk = Read-Json 'skills.json'
if ($sk) {
    foreach ($job in $sk.jobs) {
        if (-not $job.skills.Count) { continue }
        $need = @($job.skills | Where-Object { -not (Test-Path (Join-Path $icons "skill\$($_.id).png")) })
        if (-not $need.Count) { continue }
        # maplestory.io errors on a few v83 skillbooks (e.g. Hermit); v95 has the same icons for some of them
        $book = $null
        foreach ($ver in 83, 95) {
            $resp = $client.GetAsync("https://maplestory.io/api/GMS/$ver/job/$($job.id)/skillbook").Result
            if ($resp.IsSuccessStatusCode) { $book = $resp.Content.ReadAsStringAsync().Result | ConvertFrom-Json; break }
        }
        if (-not $book) { Write-Warning "No skill icons available for job $($job.id) (maplestory.io error)"; $fail++; continue }
        $wanted = @{}; foreach ($s in $job.skills) { $wanted[[int]$s.id] = 1 }
        foreach ($s in $book.skills) {
            if ($s.icon -and $wanted.ContainsKey([int]$s.id)) { [System.IO.File]::WriteAllBytes((Join-Path $icons "skill\$($s.id).png"), [Convert]::FromBase64String($s.icon)); $ok++ }
        }
    }
}
Set-Content $missingFile ($missing.Keys | Sort-Object) -Encoding UTF8

# --- full map pictures ("Scene"), saved as JPEG to keep them ~2.5x smaller than the PNGs
if ($Scenes) {
    Add-Type -AssemblyName System.Drawing
    $sceneDir = Join-Path $icons 'scene'
    New-Item -ItemType Directory -Force $sceneDir | Out-Null
    $jpeg = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object MimeType -eq 'image/jpeg'
    $ep = New-Object System.Drawing.Imaging.EncoderParameters 1
    $ep.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality), 82L
    $sceneIds = @(Get-ChildItem (Join-Path $data 'maps') -Filter '*.json' |
        Where-Object { (Get-Content $_.FullName -Raw) -notmatch '"imageMap"' } | ForEach-Object { $_.BaseName })
    $sceneTodo = @($sceneIds | Where-Object { -not $missing.ContainsKey("scene\$_.jpg") -and -not (Test-Path (Join-Path $sceneDir "$_.jpg")) })
    Write-Host ("Scenes: {0} maps, {1} to download" -f $sceneIds.Count, $sceneTodo.Count)
    # renders can be slow: own client with a longer timeout (a used HttpClient can't change it)
    $sceneClient = New-Object System.Net.Http.HttpClient
    $sceneClient.Timeout = [TimeSpan]::FromSeconds(180)
    $sceneClient.DefaultRequestHeaders.UserAgent.ParseAdd('SoloMapling-dashboard/1.0')
    $sOk = 0; $sFail = 0; $n = 4
    for ($i = 0; $i -lt $sceneTodo.Count; $i += $n) {
        $batch = $sceneTodo[$i..([Math]::Min($i + $n, $sceneTodo.Count) - 1)]
        $tasks = foreach ($id in $batch) { $sceneClient.GetAsync("$api/map/$id/render") }
        try { [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$tasks) } catch { }
        for ($k = 0; $k -lt $batch.Count; $k++) {
            $t = $tasks[$k]; $id = $batch[$k]
            if ($t.Status -eq 'RanToCompletion' -and $t.Result.IsSuccessStatusCode) {
                try {
                    $ms = New-Object System.IO.MemoryStream (, $t.Result.Content.ReadAsByteArrayAsync().Result)
                    $img = [System.Drawing.Image]::FromStream($ms)
                    $img.Save((Join-Path $sceneDir "$id.jpg"), $jpeg, $ep)
                    $img.Dispose(); $ms.Dispose(); $sOk++
                } catch { $sFail++ }
            } else {
                if ($t.Status -eq 'RanToCompletion' -and [int]$t.Result.StatusCode -in 404, 500) { $missing["scene\$id.jpg"] = 1 }
                $sFail++
            }
            if ($t.Status -eq 'RanToCompletion') { $t.Result.Dispose() }
        }
        if (($i / $n) % 50 -eq 0 -or $i + $n -ge $sceneTodo.Count) {
            Write-Host ("  scenes {0}/{1}  saved {2}, failed {3}" -f [Math]::Min($i + $n, $sceneTodo.Count), $sceneTodo.Count, $sOk, $sFail)
            Set-Content $missingFile ($missing.Keys | Sort-Object) -Encoding UTF8
        }
    }
    $sceneClient.Dispose()
    $ok += $sOk; $fail += $sFail
    Set-Content $missingFile ($missing.Keys | Sort-Object) -Encoding UTF8
}
$client.Dispose()

$size = (Get-ChildItem $icons -Recurse -File | Measure-Object Length -Sum).Sum / 1MB
Write-Host ("Done: saved {0} new pictures, {1} not available. data\icons is now {2:N0} MB." -f $ok, $fail, $size)
