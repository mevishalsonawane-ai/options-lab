#!/bin/bash
# h27: download free daily (and 1h, last 730d) history from Yahoo's chart API (cookie+crumb session).
# Raw JSON is untrusted; parsed later by build.py under python -I.
R=/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/h27/raw
mkdir -p $R/yahoo $R/yahoo1h; cd $R
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
curl -sS -m 20 -A "$UA" -c cj -o /dev/null https://fc.yahoo.com/
curl -sS -m 20 -A "$UA" -b cj -c cj -o /dev/null https://query2.finance.yahoo.com/v1/test/getcrumb
P1=1420070400; P2=$(date +%s)
for s in ^GSPC ^NDX ^DJI ES=F NQ=F ^N225 ^HSI ^KS11 ^AXJO ^TWII 000001.SS ^STI CL=F BZ=F INR=X DX-Y.NYB ^TNX GC=F HDB IBN INFY WIT INDA EPI ^VIX ^NSEI ^NSEBANK ^INDIAVIX ^BSESN; do
  f=$(echo "$s" | tr -c 'A-Za-z0-9\n' '_')
  curl -sS -m 40 -A "$UA" -b cj -o yahoo/$f.json -w "$s %{http_code} %{size_download}\n" "https://query2.finance.yahoo.com/v8/finance/chart/$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$s")?period1=$P1&period2=$P2&interval=1d&events=history"
  sleep 1.5
done
for s in ES=F NQ=F ^N225 ^HSI ^KS11 ^NSEI; do
  f=$(echo "$s" | tr -c 'A-Za-z0-9\n' '_')
  curl -sS -m 40 -A "$UA" -b cj -o yahoo1h/$f.json -w "1h $s %{http_code} %{size_download}\n" "https://query2.finance.yahoo.com/v8/finance/chart/$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$s")?range=730d&interval=1h"
  sleep 1.5
done
