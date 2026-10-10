#!/bin/bash
# Option-chain snapshots across the MCX session until the Dhan token expires (2026-10-09 04:57 UTC).
F=/home/user/options-lab/research/hunt/nn_crude/fetch_dhan.py
while true; do
  now=$(date -u +%s); exp=$(date -u -d "2026-10-09 04:50" +%s)
  [ $now -ge $exp ] && break
  h=$(TZ=Asia/Kolkata date +%H%M)
  if [ "$h" -ge 0905 ] && [ "$h" -le 2325 ]; then (cd /tmp && python3 $F chain >/dev/null 2>&1); fi
  sleep 1800
done
