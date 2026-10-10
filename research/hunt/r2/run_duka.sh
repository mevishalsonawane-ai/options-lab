#!/bin/sh
# R2: silver-only Dukascopy fetch (design window full, holdout every 3rd weekday), then build parquet.
F=/home/user/options-lab/research/hunt/r2/fetch_duka.py
for i in 1 2 3; do python3 -P $F 2025-08-01 2025-09-30 XAGUSD; done
python3 -P $F build XAGUSD; echo DESIGNDONE
for i in 1 2; do STEP=3 python3 -P $F 2025-10-01 2026-10-06 XAGUSD; done
python3 -P $F build XAGUSD; echo ALLDONE
