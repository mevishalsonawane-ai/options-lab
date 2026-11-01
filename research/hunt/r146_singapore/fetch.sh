#!/bin/bash
# R146: download Singapore STI (daily 2015+, hourly last ~730 sessions) and S&P 500 daily from Yahoo's chart API
# (H27's cookie + crumb session). Raw JSON is untrusted: it is parsed only by r146.py under python -I.
#   bash research/hunt/r146_singapore/fetch.sh /tmp/r146raw
set -e
R=${1:-/tmp/r146raw}; mkdir -p "$R"; cd "$R"
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
curl -sS -m 20 -A "$UA" -c cj -o /dev/null https://fc.yahoo.com/ || true
curl -sS -m 20 -A "$UA" -b cj -c cj -o /dev/null https://query2.finance.yahoo.com/v1/test/getcrumb
Q=https://query2.finance.yahoo.com/v8/finance/chart
curl -sS -m 40 -A "$UA" -b cj -o sti_1d.json "$Q/%5ESTI?period1=1420070400&period2=$(date +%s)&interval=1d&events=history"
sleep 2
curl -sS -m 40 -A "$UA" -b cj -o sti_1h.json "$Q/%5ESTI?range=730d&interval=1h"
sleep 2
curl -sS -m 40 -A "$UA" -b cj -o gspc_1d.json "$Q/%5EGSPC?period1=1420070400&period2=$(date +%s)&interval=1d&events=history"
ls -l "$R"
