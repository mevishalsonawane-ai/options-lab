#!/bin/bash
# M2: Yahoo daily (cookie+crumb session, as h27) from 2000 for long proxies. Raw JSON is untrusted -> parse_yahoo.py (python -I).
R=/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/m2/raw/yahoo
rm -rf $R; mkdir -p $R; cd $R
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
curl -sS -m 20 -A "$UA" -c cj -o /dev/null https://fc.yahoo.com/
curl -sS -m 20 -A "$UA" -b cj -c cj -o /dev/null https://query2.finance.yahoo.com/v1/test/getcrumb
P1=946684800; P2=$(date +%s)
q() { python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
for s in CL=F BZ=F NG=F GC=F SI=F HG=F INR=X; do
  f=$(echo "$s" | tr -c 'A-Za-z0-9\n' '_')
  curl -sS -m 60 -A "$UA" -b cj -o $f.json -w "1d $s %{http_code} %{size_download}\n" "https://query2.finance.yahoo.com/v8/finance/chart/$(q $s)?period1=$P1&period2=$P2&interval=1d&events=history"
  sleep 1.5
done
