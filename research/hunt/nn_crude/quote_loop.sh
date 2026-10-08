#!/bin/bash
# Futures depth snapshots every 10 min during the MCX session while the token is valid.
while true; do
  now=$(date -u +%s); exp=$(date -u -d "2026-10-09 04:50" +%s); [ $now -ge $exp ] && break
  h=$(TZ=Asia/Kolkata date +%H%M)
  if [ "$h" -ge 0902 ] && [ "$h" -le 2328 ]; then (cd /tmp && python3 /home/user/options-lab/research/hunt/nn_crude/quote_snap.py >/dev/null 2>&1); fi
  sleep 600
done
