#!/bin/bash
# NN-CRUDE: Yahoo chart API (cookie+crumb session, as h27). Raw JSON -> parse_yahoo.py (python -I) -> parquet; raw deleted.
R=/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/nn_crude/raw_yahoo
rm -rf $R; mkdir -p $R/d $R/h; cd $R
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
curl -sS -m 20 -A "$UA" -c cj -o /dev/null https://fc.yahoo.com/
curl -sS -m 20 -A "$UA" -b cj -c cj -o /dev/null https://query2.finance.yahoo.com/v1/test/getcrumb
P1=946684800; P2=$(date +%s)
q() { python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
for s in CL=F BZ=F INR=X DX-Y.NYB ^INDIAVIX ^VIX NG=F GC=F ^GSPC ^TNX USO; do
  f=$(echo "$s" | tr -c 'A-Za-z0-9\n' '_')
  curl -sS -m 60 -A "$UA" -b cj -o d/$f.json -w "1d $s %{http_code} %{size_download}\n" "https://query2.finance.yahoo.com/v8/finance/chart/$(q $s)?period1=$P1&period2=$P2&interval=1d&events=history"
  sleep 1.5
done
for s in CL=F BZ=F INR=X DX-Y.NYB ES=F; do
  f=$(echo "$s" | tr -c 'A-Za-z0-9\n' '_')
  curl -sS -m 60 -A "$UA" -b cj -o h/$f.json -w "1h $s %{http_code} %{size_download}\n" "https://query2.finance.yahoo.com/v8/finance/chart/$(q $s)?range=730d&interval=1h"
  sleep 1.5
  curl -sS -m 60 -A "$UA" -b cj -o h/${f}_5m.json -w "5m $s %{http_code} %{size_download}\n" "https://query2.finance.yahoo.com/v8/finance/chart/$(q $s)?range=60d&interval=5m"
  sleep 1.5
done
