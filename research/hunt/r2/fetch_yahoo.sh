#!/bin/bash
# r2: Yahoo chart API (cookie+crumb session) for US/global series. Raw JSON is untrusted; parsed by parse_yahoo.py under python -I.
R=/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/r2/raw
mkdir -p $R/d $R/h $R/m5 $R/m1; cd $R
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
curl -sS -m 20 -A "$UA" -c cj -o /dev/null https://fc.yahoo.com/
curl -sS -m 20 -A "$UA" -b cj -c cj -o /dev/null https://query2.finance.yahoo.com/v1/test/getcrumb
q(){ python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
SYMS="CL=F BZ=F NG=F GC=F SI=F HG=F DX-Y.NYB ^TNX ^FVX ES=F NQ=F ^GSPC INR=X ^VIX GLD"
P2=$(date +%s)
for s in $SYMS; do f=$(echo "$s" | tr -c 'A-Za-z0-9\n' '_')
  curl -sS -m 40 -A "$UA" -b cj -o d/$f.json -w "1d $s %{http_code} %{size_download}\n" "https://query2.finance.yahoo.com/v8/finance/chart/$(q $s)?period1=946684800&period2=$P2&interval=1d&events=history"; sleep 1.2
  curl -sS -m 40 -A "$UA" -b cj -o h/$f.json -w "1h $s %{http_code} %{size_download}\n" "https://query2.finance.yahoo.com/v8/finance/chart/$(q $s)?range=730d&interval=1h"; sleep 1.2
  curl -sS -m 40 -A "$UA" -b cj -o m5/$f.json -w "5m $s %{http_code} %{size_download}\n" "https://query2.finance.yahoo.com/v8/finance/chart/$(q $s)?range=60d&interval=5m"; sleep 1.2
  curl -sS -m 40 -A "$UA" -b cj -o m1/$f.json -w "1m $s %{http_code} %{size_download}\n" "https://query2.finance.yahoo.com/v8/finance/chart/$(q $s)?range=7d&interval=1m"; sleep 1.2
done
