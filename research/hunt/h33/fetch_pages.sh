#!/bin/bash
# h33: fetch article pages listed in a TSV (name<TAB>url) politely (SLEEP s apart, default 3), keep ONLY the
# publication-time strings (ET publishedDate, Moneycontrol datePublished) in <out.tsv>; the page itself is deleted
# (disk). Names already in <out.tsv> are skipped.
set -u
L=$1; O=$2; UA="Mozilla/5.0 (compatible; research script; options-lab h33)"; T=$(mktemp); touch "$O"
while IFS=$'\t' read -r n u; do
  grep -q "^$n	" "$O" && continue
  code=$(curl -sSL -m 40 -A "$UA" -o "$T" -w "%{http_code}" "$u")
  p=$(grep -oE "publishedDate'?\"? *: *['\"][0-9T:+-]{19}|\"datePublished\" *: *\"[0-9T:+-]{19}" "$T" | head -1 | grep -oE "[0-9]{4}-[0-9-]{5}T[0-9:]{8}")
  printf "%s\t%s\t%s\n" "$n" "$code" "$p" >> "$O"; rm -f "$T"; sleep "${SLEEP:-3}"
done < "$L"
echo DONE
