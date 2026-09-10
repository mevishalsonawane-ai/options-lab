<#
    Nightly chain harvest. Registered with Windows Task Scheduler to run after
    the 15:30 IST close.

    WHY THE TIMING IS THE WHOLE POINT. manifest.record refuses a same_day claim
    unless collected_on == session, and a backfilled partition holds only the
    contracts still listed when it ran - every already-expired front chain is
    missing from it. Upstox drops a contract from its master the moment it
    settles and recycles the token, and no free source serves expired
    contracts. So a session not collected ON ITS OWN DAY is gone permanently.
    That is not a gap that can be repaired later; it is the reason this exists.

    No credentials: the Upstox historical-candle endpoint is unauthenticated.
#>
param(
    [string]$Repo = "C:\Users\mevis\Downloads\files\options-lab",
    [int]$Expiries = 3,
    [switch]$Force          # collect even on a weekend, for a manual test run
)

$ErrorActionPreference = "Stop"
$stamp = Get-Date -Format "yyyy-MM-dd"
$logDir = Join-Path $Repo "logs"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
$log = Join-Path $logDir "harvest_$stamp.log"

function Write-Log($msg) {
    $line = "$(Get-Date -Format 'HH:mm:ss')  $msg"
    Write-Output $line
    Add-Content -Path $log -Value $line -Encoding utf8
}

$day = (Get-Date).DayOfWeek
if (-not $Force -and ($day -eq "Saturday" -or $day -eq "Sunday")) {
    Write-Log "$day - NSE closed, nothing to collect"
    exit 0
}

Write-Log "harvest start (expiries=$Expiries)"
Push-Location $Repo
try {
    $env:PYTHONPATH = $Repo
    $out = & python -m options_lab.harvest.cli --underlying NIFTY BANKNIFTY `
        --expiries $Expiries --days 1 --indices 2>&1
    $out | ForEach-Object { Write-Log $_ }
    if ($LASTEXITCODE -ne 0) {
        Write-Log "harvester exited $LASTEXITCODE"
        exit $LASTEXITCODE
    }

    # Heartbeat: the freshest same_day session per underlying, so staleness is
    # visible without opening a parquet.
    $probe = @'
from datetime import date
from pathlib import Path
from options_lab.harvest import manifest
for u in ("NIFTY", "BANKNIFTY"):
    m = manifest.read(Path("options_lab/data"), u)
    same = m[m.scope == "same_day"] if len(m) else m
    if len(same):
        last = max(same.session)
        print(f"{u}: {len(same)} same_day sessions, latest {last}, "
              f"{(date.today() - last).days}d old")
    else:
        print(f"{u}: NO same_day sessions yet")
'@
    $probe | & python - 2>&1 | ForEach-Object { Write-Log $_ }
    Write-Log "harvest done"
}
finally {
    Pop-Location
}
