#!/bin/bash
# h33: download public news sitemaps (ET monthly news sitemaps, Moneycontrol monthly post sitemaps), 2020-06 .. 2026-10.
# Polite: one request every 3 s, plain UA naming the purpose, no cookies. Raw files are kept gzipped.
# Usage: bash research/hunt/h33/fetch.sh <raw_dir>
set -u
R=$1; UA="Mozilla/5.0 (compatible; research script; options-lab h33)"
cd "$R" || exit 1
get(){ # url out.gz
  [ -s "$2" ] && return 0
  code=$(curl -sS -m 120 -A "$UA" -o tmp.xml -w "%{http_code}" "$1"); echo "$(date +%T) $code $1"
  if [ "$code" = 200 ]; then gzip -c tmp.xml > "$2"; fi; rm -f tmp.xml; sleep 3; }
while read -r u; do b=$(basename "$u" .xml); get "$u" "et_$b.xml.gz"; done < et_list.txt
for y in 2020 2021 2022 2023 2024 2025 2026; do for m in 01 02 03 04 05 06 07 08 09 10 11 12; do
  [ "$y$m" \< "202006" ] && continue; [ "$y$m" \> "202610" ] && continue
  get "https://www.moneycontrol.com/news/sitemap/sitemap-post-$y-$m.xml" "mc_$y-$m.xml.gz"; done; done
echo DONE
