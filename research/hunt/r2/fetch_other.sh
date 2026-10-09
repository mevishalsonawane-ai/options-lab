#!/bin/bash
# r2: FRED daily series, EIA weekly stocks, CFTC disaggregated COT (yearly zips). Untrusted raw files.
R=/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/r2/raw
mkdir -p $R/fred $R/eia $R/cot; cd $R
for id in DFII10 DGS10 DTWEXBGS DEXINUS DCOILWTICO DHHNGSP T10YIE; do
  curl -sS -m 60 -o fred/$id.csv -w "fred $id %{http_code} %{size_download}\n" "https://fred.stlouisfed.org/graph/fredgraph.csv?id=$id"; sleep 1; done
curl -sS -m 60 -L -o eia/WCESTUS1w.xls -w "eia crude %{http_code} %{size_download}\n" "https://www.eia.gov/dnav/pet/hist_xls/WCESTUS1w.xls"
curl -sS -m 60 -L -o eia/NW2_EPG0_SWO_R48_BCFw.xls -w "eia ng %{http_code} %{size_download}\n" "https://www.eia.gov/dnav/ng/hist_xls/NW2_EPG0_SWO_R48_BCFw.xls"
for y in $(seq 2012 2026); do
  curl -sS -m 90 -o cot/d$y.zip -w "cot $y %{http_code} %{size_download}\n" "https://www.cftc.gov/files/dea/history/fut_disagg_txt_$y.zip"; sleep 1; done
