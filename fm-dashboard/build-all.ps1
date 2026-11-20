# Rebuilds every data file the dashboard reads (except listings.json, which the game server
# writes itself). The drops step needs the solomapling-db container running.
#
#   powershell -ExecutionPolicy Bypass -File fm-dashboard\build-all.ps1

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'build-drops.ps1')      # drops.json      (database)
& (Join-Path $PSScriptRoot 'build-gamedata.ps1')   # skills.json, quests.json (WZ)
& (Join-Path $PSScriptRoot 'build-mobmaps.ps1')    # mobmaps.json, rates.json, maps\*.json (WZ, config.yaml)
& (Join-Path $PSScriptRoot 'build-items.ps1')      # items.json (WZ + NPC shops from the database)
& (Join-Path $PSScriptRoot 'build-leveling.ps1')   # leveling.json (ExpTable.java + config.yaml)
& (Join-Path $PSScriptRoot 'fetch-icons.ps1') -Scenes   # data\icons incl. scene pictures (only downloads ones not saved yet; needs internet)
Write-Host 'All dashboard data rebuilt. Reload the page.'
