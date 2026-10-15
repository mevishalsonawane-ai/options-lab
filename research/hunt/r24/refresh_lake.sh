#!/usr/bin/env bash
# R24: refresh the what-if table, the brain's what-if labels and Steps 1-2 on the full Dhan lake once it is complete
# (incl. opt_<UND>_MONTH). One command:   bash research/hunt/r24/refresh_lake.sh
# Needs rclone (remote "gdrive"), the brain's Python deps (research/brain/requirements.txt) + numba, scikit-learn.
# Step 3 is NOT re-run: R24's holdout has been read (results/step3_holdout.json). New rules need a new pre-registration
# and a fresh holdout. Results are rewritten in research/hunt/r24/results (commit them with a dated note).
set -euo pipefail
cd "$(dirname "$0")/../../.."
PY=${PY:-python3}
LAKE=${IRAALGO_LAKE:-${IRAALGO_CACHE:-$HOME/.cache/iraalgo}/lake}
mkdir -p "$LAKE"
rclone copy gdrive:IraAlgo-data-lake/options_rolling_1m "$LAKE" --include "opt_{NIFTY,BANKNIFTY}_*" --checksum
rclone copy gdrive:IraAlgo-data-lake/index_1m "$LAKE" --include "index_1m_{NIFTY,BANKNIFTY,INDIAVIX}_*" --checksum
rclone copy gdrive:IraAlgo-data-lake/fut_1m_live_index "$LAKE" --checksum
rclone copy gdrive:IraAlgo-data-lake/CATALOG.json "$LAKE"
[ -f research/data/CATALOG.json ] || cp "$LAKE/CATALOG.json" research/data/CATALOG.json   # the lake adapter's switch
for U in NIFTY BANKNIFTY; do
  $PY -m research.brain.cube build --und $U --source lake --from 2021-01-01
  $PY -m research.brain.features build --und $U
  $PY -m research.brain.guard --und $U --days 4
done
$PY research/hunt/r24/whatif.py build --series WEEK,MONTH
$PY -m research.brain.whatif_labels build
$PY research/hunt/r24/study.py step1
$PY research/hunt/r24/study.py step2
$PY research/hunt/r24/report.py
$PY research/hunt/r24/whatif.py upload
