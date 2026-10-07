## obuy group gb: trend-indicator and mean-reversion option-buying strategies (catalog TI-01..10, MR-01..06)

Written 7 Oct 2026. Code: `research/obuy/strategies/gb_trend.py`, `gb_meanrev.py`, `gb_ind.py` (indicators), and
`research/obuy/gb_group.py` (runs the group in chunks and computes the group-level controls). To reproduce, run
`flock <scratch>/obuy.lock python3 -I research/obuy/gb_group.py --name gb_all --pool 5`. It takes about 60 minutes.

### Verdict

**None of the 16 strategies passes the gates. All 16 lost money out of sample.** I tested 1,072 variants, using real
option minute prices, the app's fills and charges, 1 lot, and expiry days skipped. Every strategy's walk-forward net
(parameters picked only on earlier years, test years 2022-2026) is negative after costs. No strategy was positive in
more than 3 of its 5 test years.

The overfitting controls agree:
- **White's Reality Check and Hansen SPA:** p = 1.00 over all 1,072 variants. No variant beats not trading.
- **Random-baseline test:** 23 variants beat a random entry with the same exits at raw p < 0.05. None survives
  Benjamini-Hochberg (smallest q = 0.077).
- **Deflated Sharpe:** about 0 for every strategy's in-sample best variant.

**Best by walk-forward: MR-04 Camarilla R3/S3 fade.** Walk-forward net Rs -16,289 over 1,417 trades (PF 0.98, 2 of 5
years positive). It is also the only strategy whose signals carry information:
- Its entries beat random entries with the same exits: walk-forward p = 0.013 raw, but 0.11 after BH and 0.20 after
  Holm across the 16 strategies.
- Its in-sample best variant (5-min, R3/S3, target the pivot, ATM) made Rs +67,141 over 6 years with p < 0.001
  against random. BH q is 0.077, so it misses at 5%.

The edge over random entries is real-looking but smaller than costs plus the decay of a bought option. As an
option-buying rule it does not pay. Treat it as a candidate signal only, for example for a different execution, and
not as a strategy.

Everything popular in the trend family loses steadily out of sample: Supertrend, EMA crosses, the confluence scripts,
ADX, the TWAP pullback and Heikin-Ashi. The losses run from Rs -45k to -78k a year at 1 lot. The trades are
indistinguishable from random entries with the same exits: the cost of buying premium on every signal is simply paid.

**Per strategy, one line each.** IS = in-sample best variant (selection-biased). WF = walk-forward. p = beats the random
baseline on the WF trades (raw / BH across the 16). DD = WF max drawdown at 1 lot.

| catalog | strategy | variants | IS best | WF | p raw / BH | years + | DD |
|---|---|---|---|---|---|---|---|
| MR-04 | Camarilla R3/S3 fade | 72 | Rs +67,141 | Rs -16,289 | 0.013 / 0.108 | 2/5 | -79,472 |
| TI-06 | RSI 50 regime (NIFTY) | 90 | Rs -6,499 | Rs -45,088 | 0.410 / 0.728 | 2/5 | -67,617 |
| MR-01 | 5-EMA alert candle | 72 | Rs -65,164 | Rs -53,650 | 0.005 / 0.088 | 2/5 | -155,531 |
| TI-04 | EMA9 x TWAP, 1-ITM (NIFTY) | 36 | Rs -33,236 | Rs -73,557 | 0.058 / 0.229 | 1/5 | -85,696 |
| MR-03 | RSI 30/70 reversal | 48 | Rs +28,960 | Rs -82,004 | 0.373 / 0.728 | 2/5 | -153,827 |
| TI-02 | Supertrend + EMA filter | 64 | Rs +4,742 | Rs -89,468 | 0.039 / 0.208 | 0/5 | -123,960 |
| MR-02 | Bollinger alert candle | 96 | Rs +58,175 | Rs -90,790 | 0.154 / 0.410 | 3/5 | -293,226 |
| MR-05 | VWAP(TWAP) stretch fade | 96 | Rs -83,369 | Rs -91,856 | 0.984 / 0.994 | 1/5 | -99,127 |
| MR-06 | Opening-range fade (BANKNIFTY) | 48 | Rs -64,743 | Rs -96,578 | 0.071 / 0.229 | 1/4 | -141,950 |
| TI-07 | RSI 60/40 range shift | 64 | Rs -6,254 | Rs -154,916 | 0.852 / 0.994 | 1/5 | -208,314 |
| TI-08 | Heikin-Ashi wickless | 48 | Rs -144,917 | Rs -170,762 | 0.994 / 0.994 | 2/5 | -238,329 |
| TI-01 | Supertrend flip | 90 | Rs -65,579 | Rs -208,881 | 0.224 / 0.512 | 0/5 | -306,620 |
| TI-05 | VWAP(TWAP) pullback | 48 | Rs -161,289 | Rs -209,044 | 0.500 / 0.728 | 0/5 | -211,190 |
| TI-10 | ST + EMA + VWAP + RSI confluence | 56 | Rs -56,583 | Rs -290,461 | 0.833 / 0.994 | 1/5 | -322,346 |
| TI-09 | ADX/DMI | 48 | Rs -101,626 | Rs -345,311 | 0.990 / 0.994 | 0/5 | -372,895 |
| TI-03 | EMA fast/slow cross | 96 | Rs +122,151 | Rs -369,856 | 0.458 / 0.728 | 1/5 | -429,309 |

Some strategies look positive in-sample and still lose in the walk-forward:
- **TI-03:** Rs +122k in-sample against Rs -370k walk-forward. Its walk-forward picked a different EMA pair or
  timeframe almost every year.
- **MR-02:** Rs +58k in-sample against Rs -91k walk-forward.

This is the selection bias the controls exist to catch.

**Strategies run: all 16 catalog entries in these two families. None was skipped.** Some catalog parts could not be
tested as written:
- TI-07's positional (multi-day) version.
- MR-05's "wide CPR" range filter. ADX < 20 is used instead.
- TI-10's daily-loss / consecutive-loss risk module. A fixed cap of 3 trades a day is used instead.

**Per-variant results (scratchpad):**
- Every variant, with group-wide BH / Holm:
  `/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/obuy_cache/runs/gb_all/variants.csv`
- In the same folder: `families.csv` (gates), `wf_years.csv`, `mc.csv` and `oos_trades.csv.gz` (the walk-forward
  trades).
- Each chunk's own run: `runs/gb_all_c0` .. `gb_all_c6` (REPORT.md, families.csv, variants.csv, chunk.pkl). The chunk
  trades.csv.gz files were deleted to save disk.

### Tables (group-wide; Rs, 1 lot, after costs)

Group: 1,072 variants, 1,528 trading days (2020-08-03 .. 2026-10-05). White RC p = 1.000, Hansen SPA p = 1.000. 23 variants at raw random-baseline p < 0.05, none at BH q < 0.05 (smallest 0.077). Promoted: none.

#### One line per strategy

| catalog | strategy | variants | in-sample best net (trades, years +, max DD) | walk-forward net (trades) | WF years + | WF max DD | beats random p (raw / BH / Holm, 16 strategies) | G1 G2 G3 G4 | promoted |
|---|---|---|---|---|---|---|---|---|---|
| MR-04 | gb_mr04_camarilla | 72 | +67,141 (1498, 3/7, -61,377) | -16,289 (1417) | 2/5 | -79,472 | 0.013 / 0.108 / 0.202 | n n n Y | no |
| TI-06 | gb_ti06_rsi50 | 90 | -6,499 (1196, 3/7, -86,124) | -45,088 (787) | 2/5 | -67,617 | 0.410 / 0.728 / 1.000 | n n n Y | no |
| MR-01 | gb_mr01_5ema | 72 | -65,164 (3012, 3/7, -120,403) | -53,650 (2645) | 2/5 | -155,531 | 0.005 / 0.088 / 0.088 | n n n n | no |
| TI-04 | gb_ti04_ema9_twap | 36 | -33,236 (1286, 3/7, -64,378) | -73,557 (1359) | 1/5 | -85,696 | 0.058 / 0.229 / 0.754 | n n n Y | no |
| MR-03 | gb_mr03_rsi_reversal | 48 | +28,960 (1810, 4/7, -153,827) | -82,004 (1272) | 2/5 | -153,827 | 0.373 / 0.728 / 1.000 | n n n n | no |
| TI-02 | gb_ti02_st_ema | 64 | +4,742 (1125, 3/7, -63,363) | -89,468 (1679) | 0/5 | -123,960 | 0.039 / 0.208 / 0.546 | n n n n | no |
| MR-02 | gb_mr02_bb_alert | 96 | +58,175 (1022, 6/7, -147,320) | -90,790 (2136) | 3/5 | -293,226 | 0.154 / 0.410 / 1.000 | n n Y n | no |
| MR-05 | gb_mr05_vwap_stretch | 96 | -83,369 (495, 0/7, -88,738) | -91,856 (713) | 1/5 | -99,127 | 0.984 / 0.994 / 1.000 | n n n Y | no |
| MR-06 | gb_mr06_or_fade | 48 | -64,743 (1027, 2/6, -123,721) | -96,578 (819) | 1/4 | -141,950 | 0.071 / 0.229 / 0.858 | n n n n | no |
| TI-07 | gb_ti07_rsi_shift | 64 | -6,254 (599, 4/7, -112,904) | -154,916 (647) | 1/5 | -208,314 | 0.852 / 0.994 / 1.000 | n n n n | no |
| TI-08 | gb_ti08_heikin_ashi | 48 | -144,917 (2385, 4/7, -238,329) | -170,762 (2030) | 2/5 | -238,329 | 0.994 / 0.994 / 1.000 | n n n n | no |
| TI-01 | gb_ti01_supertrend | 90 | -65,579 (948, 0/7, -85,273) | -208,881 (2496) | 0/5 | -306,620 | 0.224 / 0.512 / 1.000 | n n n n | no |
| TI-05 | gb_ti05_vwap_pullback | 48 | -161,289 (1823, 0/7, -161,289) | -209,044 (1427) | 0/5 | -211,190 | 0.500 / 0.728 / 1.000 | n n n n | no |
| TI-10 | gb_ti10_confluence | 56 | -56,583 (3824, 3/7, -270,346) | -290,461 (2078) | 1/5 | -322,346 | 0.833 / 0.994 / 1.000 | n n n n | no |
| TI-09 | gb_ti09_adx | 48 | -101,626 (2473, 3/7, -261,423) | -345,311 (2929) | 0/5 | -372,895 | 0.990 / 0.994 / 1.000 | n n n n | no |
| TI-03 | gb_ti03_ema_cross | 96 | +122,151 (1979, 6/7, -111,213) | -369,856 (4526) | 1/5 | -429,309 | 0.458 / 0.728 / 1.000 | n n n n | no |

#### Walk-forward detail

| strategy | WF per year | PF | win | Sharpe | worst month | MC P(profit 1y) 1 lot | MC P(DD>=50%) 1 lot | DSR of IS best (N = all) | PBO |
|---|---|---|---|---|---|---|---|---|---|
| gb_mr04_camarilla | -3,438 | 0.98 | 53% | -0.08 | -36,553 | 46% | 0% | 0.00 | 33% |
| gb_ti06_rsi50 | -9,508 | 0.91 | 47% | -0.42 | -11,686 | 33% | 0% | 0.00 | 31% |
| gb_mr01_5ema | -11,314 | 0.98 | 33% | -0.15 | -51,314 | 43% | 0% | 0.00 | 0% |
| gb_ti04_ema9_twap | -15,512 | 0.91 | 43% | -0.64 | -19,086 | 26% | 0% | 0.00 | 0% |
| gb_mr03_rsi_reversal | -17,293 | 0.95 | 38% | -0.25 | -45,985 | 37% | 0% | 0.00 | 21% |
| gb_ti02_st_ema | -18,867 | 0.93 | 36% | -0.51 | -24,800 | 29% | 0% | 0.00 | 45% |
| gb_mr02_bb_alert | -19,146 | 0.95 | 32% | -0.23 | -68,238 | 36% | 0% | 0.00 | 21% |
| gb_mr05_vwap_stretch | -19,371 | 0.64 | 27% | -1.68 | -10,762 | 5% | 0% | 0.00 | 3% |
| gb_mr06_or_fade | -25,782 | 0.91 | 39% | -0.49 | -37,782 | 30% | 0% | 0.00 | 1% |
| gb_ti07_rsi_shift | -32,669 | 0.78 | 34% | -0.87 | -17,083 | 14% | 0% | 0.00 | 50% |
| gb_ti08_heikin_ashi | -36,011 | 0.86 | 31% | -0.79 | -58,013 | 17% | 0% | 0.00 | 0% |
| gb_ti01_supertrend | -44,050 | 0.89 | 36% | -0.62 | -38,002 | 20% | 0% | 0.00 | 56% |
| gb_ti05_vwap_pullback | -44,084 | 0.73 | 31% | -1.77 | -25,875 | 3% | 0% | 0.00 | 19% |
| gb_ti10_confluence | -61,254 | 0.89 | 38% | -0.73 | -60,323 | 18% | 0% | 0.00 | 45% |
| gb_ti09_adx | -72,821 | 0.82 | 38% | -1.14 | -63,037 | 5% | 0% | 0.00 | 31% |
| gb_ti03_ema_cross | -77,997 | 0.89 | 30% | -0.73 | -63,707 | 14% | 1% | 0.00 | 24% |

#### Walk-forward net by test year (Rs, 1 lot)

| strategy | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|
| gb_mr04_camarilla | +36,293 | -14,605 | -15,119 | +14,296 | -37,155 |
| gb_ti06_rsi50 | -20,065 | +5,648 | -30,744 | -11,251 | +11,323 |
| gb_mr01_5ema | +5,614 | -50,779 | +27,590 | -27,279 | -8,797 |
| gb_ti04_ema9_twap | -28,143 | -16,808 | +6,336 | -8,921 | -26,022 |
| gb_mr03_rsi_reversal | -26,349 | -23,590 | +47,032 | -111,868 | +32,772 |
| gb_ti02_st_ema | -18,117 | -13,293 | -8,934 | -26,158 | -22,965 |
| gb_mr02_bb_alert | +62,864 | +9,185 | -21,267 | -209,250 | +67,678 |
| gb_mr05_vwap_stretch | +474 | -4,237 | -20,755 | -54,184 | -13,154 |
| gb_mr06_or_fade | - | -14,756 | -51,585 | +4,179 | -34,416 |
| gb_ti07_rsi_shift | -58,811 | -26,919 | -30,148 | -51,807 | +12,770 |
| gb_ti08_heikin_ashi | -14,606 | +39,320 | +20,378 | -124,307 | -91,547 |
| gb_ti01_supertrend | -13,229 | -42,686 | -112,703 | -8,936 | -31,327 |
| gb_ti05_vwap_pullback | -58,197 | -53,264 | -16,901 | -38,548 | -42,134 |
| gb_ti10_confluence | -126,438 | -784 | +7,505 | -166,721 | -4,023 |
| gb_ti09_adx | -156,060 | -28,766 | -26,547 | -30,344 | -103,594 |
| gb_ti03_ema_cross | -226,824 | -52,185 | -74,120 | +16,833 | -33,560 |

#### Monte Carlo of the walk-forward trades, 1-year paths on Rs 5 lakh

| strategy | sizing | trades/yr | P(profit) | P(DD>=20%) | P(DD>=50%) | P(ruin) | 5th pct | median | 95th pct |
|---|---|---|---|---|---|---|---|---|---|
| gb_mr04_camarilla | 1 lot | 299 | 46% | 0% | 0% | 0% | -60,151 | -3,805 | +53,325 |
| gb_mr04_camarilla | 1% risk | 299 | 28% | 5% | 0% | 0% | -86,839 | -25,894 | +48,980 |
| gb_mr04_camarilla | 2% risk | 299 | 26% | 63% | 0% | 0% | -172,252 | -58,283 | +110,446 |
| gb_ti06_rsi50 | 1 lot | 166 | 33% | 0% | 0% | 0% | -46,553 | -10,121 | +28,244 |
| gb_ti06_rsi50 | 1% risk | 166 | 21% | 15% | 0% | 0% | -108,110 | -38,262 | +45,415 |
| gb_ti06_rsi50 | 2% risk | 166 | 20% | 80% | 2% | 0% | -206,751 | -83,237 | +100,133 |
| gb_mr01_5ema | 1 lot | 558 | 43% | 24% | 0% | 0% | -118,575 | -12,083 | +99,603 |
| gb_mr01_5ema | 1% risk | 558 | 63% | 0% | 0% | 0% | -19,542 | +5,532 | +37,811 |
| gb_mr01_5ema | 2% risk | 558 | 57% | 2% | 0% | 0% | -64,319 | +8,688 | +109,869 |
| gb_ti04_ema9_twap | 1 lot | 287 | 26% | 0% | 0% | 0% | -53,637 | -15,289 | +23,383 |
| gb_ti04_ema9_twap | 1% risk | 287 | 34% | 6% | 0% | 0% | -84,806 | -19,021 | +63,109 |
| gb_ti04_ema9_twap | 2% risk | 287 | 31% | 66% | 0% | 0% | -174,374 | -48,018 | +134,237 |
| gb_mr03_rsi_reversal | 1 lot | 268 | 37% | 15% | 0% | 0% | -107,962 | -17,480 | +76,259 |
| gb_mr03_rsi_reversal | 1% risk | 268 | 57% | 0% | 0% | 0% | -29,779 | +4,619 | +57,999 |
| gb_mr03_rsi_reversal | 2% risk | 268 | 49% | 7% | 0% | 0% | -82,711 | -1,214 | +124,762 |
| gb_ti02_st_ema | 1 lot | 354 | 29% | 2% | 0% | 0% | -74,018 | -19,210 | +35,366 |
| gb_ti02_st_ema | 1% risk | 354 | 26% | 67% | 0% | 0% | -173,722 | -57,572 | +100,856 |
| gb_ti02_st_ema | 2% risk | 354 | 21% | 99% | 39% | 0% | -306,495 | -138,108 | +205,334 |
| gb_mr02_bb_alert | 1 lot | 450 | 36% | 16% | 0% | 0% | -107,739 | -19,742 | +74,051 |
| gb_mr02_bb_alert | 1% risk | 450 | 67% | 0% | 0% | 0% | -24,377 | +9,055 | +48,161 |
| gb_mr02_bb_alert | 2% risk | 450 | 51% | 8% | 0% | 0% | -86,965 | +1,189 | +107,442 |
| gb_mr05_vwap_stretch | 1 lot | 150 | 5% | 0% | 0% | 0% | -37,047 | -19,444 | -532 |
| gb_mr05_vwap_stretch | 1% risk | 150 | 22% | 0% | 0% | 0% | -14,613 | -4,889 | +5,482 |
| gb_mr05_vwap_stretch | 2% risk | 150 | 14% | 0% | 0% | 0% | -40,546 | -17,178 | +9,037 |
| gb_mr06_or_fade | 1 lot | 219 | 30% | 16% | 0% | 0% | -109,569 | -25,520 | +60,483 |
| gb_mr06_or_fade | 1% risk | 219 | 9% | 39% | 0% | 0% | -133,669 | -64,796 | +17,895 |
| gb_mr06_or_fade | 2% risk | 219 | 9% | 95% | 12% | 0% | -256,091 | -139,240 | +40,995 |
| gb_ti07_rsi_shift | 1 lot | 136 | 14% | 2% | 0% | 0% | -81,556 | -33,133 | +18,373 |
| gb_ti07_rsi_shift | 1% risk | 136 | 21% | 7% | 0% | 0% | -97,870 | -37,994 | +45,283 |
| gb_ti07_rsi_shift | 2% risk | 136 | 17% | 76% | 0% | 0% | -193,696 | -88,671 | +85,136 |
| gb_ti08_heikin_ashi | 1 lot | 428 | 17% | 8% | 0% | 0% | -96,789 | -37,328 | +28,782 |
| gb_ti08_heikin_ashi | 1% risk | 428 | 55% | 0% | 0% | 0% | -20,249 | +1,781 | +31,378 |
| gb_ti08_heikin_ashi | 2% risk | 428 | 46% | 0% | 0% | 0% | -59,767 | -4,352 | +76,036 |
| gb_ti01_supertrend | 1 lot | 526 | 20% | 26% | 0% | 0% | -127,717 | -45,516 | +43,542 |
| gb_ti01_supertrend | 1% risk | 526 | 52% | 0% | 0% | 0% | -50,943 | +2,058 | +66,979 |
| gb_ti01_supertrend | 2% risk | 526 | 34% | 45% | 0% | 0% | -136,417 | -32,021 | +128,675 |
| gb_ti05_vwap_pullback | 1 lot | 301 | 3% | 1% | 0% | 0% | -81,032 | -43,971 | -5,796 |
| gb_ti05_vwap_pullback | 1% risk | 301 | 5% | 0% | 0% | 0% | -52,784 | -27,294 | +427 |
| gb_ti05_vwap_pullback | 2% risk | 301 | 3% | 27% | 0% | 0% | -122,369 | -69,923 | -10,674 |
| gb_ti10_confluence | 1 lot | 438 | 18% | 51% | 0% | 0% | -171,468 | -62,257 | +48,753 |
| gb_ti10_confluence | 1% risk | 438 | 53% | 0% | 0% | 0% | -33,127 | +1,905 | +53,198 |
| gb_ti10_confluence | 2% risk | 438 | 41% | 17% | 0% | 0% | -101,473 | -14,804 | +118,861 |
| gb_ti09_adx | 1 lot | 618 | 5% | 40% | 0% | 0% | -144,027 | -73,102 | -656 |
| gb_ti09_adx | 1% risk | 618 | 42% | 0% | 0% | 0% | -29,734 | -3,979 | +29,186 |
| gb_ti09_adx | 2% risk | 618 | 27% | 6% | 0% | 0% | -87,684 | -28,254 | +55,438 |
| gb_ti03_ema_cross | 1 lot | 954 | 14% | 64% | 1% | 0% | -195,428 | -79,657 | +40,232 |
| gb_ti03_ema_cross | 1% risk | 954 | 58% | 0% | 0% | 0% | -33,703 | +5,007 | +53,029 |
| gb_ti03_ema_cross | 2% risk | 954 | 34% | 31% | 0% | 0% | -125,945 | -26,929 | +91,600 |

#### In-sample best variant of each strategy (selection-biased)

| strategy | variant | net | per year | PF | Sharpe | p random raw | BH q (all variants) |
|---|---|---|---|---|---|---|---|
| gb_ti03_ema_cross | s45.r0.x0 ATM/near sq_off=915 {"tf": 15, "fast": 9, "slow": 50, "filt": null, "stop": "swing"} | +122,151 | +19,825 | 1.06 | 0.25 | 0.397 | 1.000 |
| gb_mr04_camarilla | s0.r0.x0 ATM/near sq_off=915 {"tf": 5, "level": 3, "tgt": "P"} | +67,141 | +10,911 | 1.07 | 0.29 | 0.000 | 0.077 |
| gb_mr02_bb_alert | s42.r1.x0 ITM1/near sq_off=915 {"tf": 15, "k": 2.0, "rr": 3.0, "trig": "close", "combined": false} | +58,175 | +9,442 | 1.05 | 0.16 | 0.006 | 0.497 |
| gb_mr03_rsi_reversal | s4.r0.x5 ATM/near default {"tf": 15, "n": 9, "low": 30} | +28,960 | +4,700 | 1.01 | 0.06 | 0.002 | 0.223 |
| gb_ti02_st_ema | s0.r1.x1 ITM1/near stop_pts=40,tgt_pts=80 {"tf": 5, "ef": 20, "es": 50, "mult": 3.0} | +4,742 | +770 | 1.01 | 0.03 | 0.007 | 0.502 |
| gb_ti07_rsi_shift | s30.r0.x1 ATM/near stop_pct=0.3,sq_off=915 {"tf": 60, "version": "pullback", "hi": 55, "lo": 45, "stop": "swing", "tgt": "2R"} | -6,254 | -1,015 | 0.99 | -0.03 | 1.000 | 1.000 |
| gb_ti06_rsi50 | s8.r0.x2 ATM/near stop_pct=0.25,tgt_pct=0.5,trail_pct=0.15,trail_arm=0.15,sq_off=915 {"tf": 5, "n": 9, "thr": 60} | -6,499 | -1,055 | 0.99 | -0.04 | 1.000 | 1.000 |
| gb_ti04_ema9_twap | s2.r0.x11 ITM1/near stop_pct=0.2,tgt_pct=0.2,sq_off=915 {"tf": 15} | -33,236 | -5,394 | 0.96 | -0.25 | 1.000 | 1.000 |
| gb_ti10_confluence | s4.r0.x0 ATM/near sq_off=915 {"tf": 5, "conds": ["st", "ema", "twap"], "rsi_thr": 55, "tgt": "2R"} | -56,583 | -9,184 | 0.99 | -0.11 | 1.000 | 1.000 |
| gb_mr06_or_fade | s0.r1.x4 ITM1/near stop_pct=0.3,tgt_pct=0.6 {"or_min": 15, "tf": 1} | -64,743 | -12,593 | 0.96 | -0.24 | 1.000 | 1.000 |
| gb_mr01_5ema | s29.r1.x0 ITM1/near sq_off=915 {"tf_s": 15, "tf_l": 15, "n": 5, "rr": 4.0, "trig": "close"} | -65,164 | -10,576 | 0.98 | -0.15 | 1.000 | 1.000 |
| gb_ti01_supertrend | s21.r0.x1 ATM/near stop_pct=0.3,pine_trail=True {"tf": 15, "n": 7, "mult": 3.0, "adx_min": 20} | -65,579 | -10,644 | 0.82 | -0.56 | 1.000 | 1.000 |
| gb_mr05_vwap_stretch | s23.r1.x1 ITM1/near stop_pct=0.3,sq_off=915 {"tf": 15, "k": 2.5, "filt": "adx20", "tgt": "dynamic"} | -83,369 | -13,531 | 0.61 | -1.46 | 1.000 | 1.000 |
| gb_ti09_adx | s44.r0.x0 ATM/near sq_off=915 {"tf": 15, "thr": 30, "n": 14, "stop": "swing", "adx_exit": true} | -101,626 | -16,494 | 0.94 | -0.27 | 1.000 | 1.000 |
| gb_ti08_heikin_ashi | s42.r0.x0 ATM/near sq_off=915 {"tf": 15, "nc": 3, "tol": 0.0, "ema_n": 50, "exit_mode": "red"} | -144,917 | -23,521 | 0.90 | -0.54 | 1.000 | 1.000 |
| gb_ti05_vwap_pullback | s9.r0.x1 ATM/near stop_pct=0.3,pine_trail=True,sq_off=915 {"dist": 0.001, "conf": "engulf", "chop": 2, "tgt": "2R"} | -161,289 | -26,178 | 0.72 | -1.62 | 1.000 | 1.000 |

#### Variants with raw random-baseline p < 0.05

23 of 1072 variants; smallest BH q 0.077
- gb_mr04_camarilla|s0.r0.x0: net +67,141, 1498 trades, p 0.000, q 0.077
- gb_mr04_camarilla|s1.r0.x0: net +63,271, 1789 trades, p 0.000, q 0.077
- gb_mr04_camarilla|s0.r1.x1: net +28,341, 1499 trades, p 0.000, q 0.077
- gb_mr04_camarilla|s0.r1.x0: net +64,688, 1499 trades, p 0.000, q 0.077
- gb_mr04_camarilla|s4.r1.x0: net +18,655, 2086 trades, p 0.000, q 0.077
- gb_mr04_camarilla|s4.r1.x1: net +20,363, 2086 trades, p 0.000, q 0.077
- gb_mr04_camarilla|s4.r0.x0: net +7,459, 2086 trades, p 0.000, q 0.077
- gb_mr04_camarilla|s0.r0.x1: net +29,578, 1498 trades, p 0.001, q 0.119
- gb_mr04_camarilla|s1.r0.x1: net +54,680, 1789 trades, p 0.001, q 0.119
- gb_mr04_camarilla|s6.r1.x0: net +769, 1294 trades, p 0.002, q 0.223
- gb_mr04_camarilla|s1.r1.x1: net +30,948, 1787 trades, p 0.002, q 0.223
- gb_mr03_rsi_reversal|s4.r0.x5: net +28,960, 1810 trades, p 0.002, q 0.223
- gb_mr04_camarilla|s1.r1.x0: net +38,092, 1787 trades, p 0.003, q 0.247
- gb_mr02_bb_alert|s42.r1.x0: net +58,175, 1022 trades, p 0.006, q 0.497
- gb_ti03_ema_cross|s46.r1.x0: net +105,044, 1743 trades, p 0.007, q 0.500

Picks: 
- gb_mr04_camarilla: 2022:s1.r1.x1;2023:s0.r1.x1;2024:s0.r1.x1;2025:s0.r1.x0;2026:s0.r1.x0
- gb_ti06_rsi50: 2022:s16.r0.x2;2023:s13.r0.x2;2024:s13.r0.x2;2025:s13.r0.x2;2026:s8.r0.x2
- gb_mr01_5ema: 2022:s35.r1.x0;2023:s25.r1.x0;2024:s29.r1.x0;2025:s29.r1.x0;2026:s29.r1.x0
- gb_ti04_ema9_twap: 2022:s0.r0.x9;2023:s2.r0.x11;2024:s2.r0.x11;2025:s2.r0.x11;2026:s2.r0.x11
- gb_mr03_rsi_reversal: 2022:s3.r0.x5;2023:s4.r0.x5;2024:s4.r0.x5;2025:s4.r0.x5;2026:s6.r0.x5
- gb_ti02_st_ema: 2022:s2.r1.x1;2023:s2.r1.x1;2024:s2.r1.x1;2025:s2.r1.x1;2026:s7.r1.x1
- gb_mr02_bb_alert: 2022:s27.r0.x0;2023:s31.r0.x0;2024:s31.r0.x0;2025:s34.r0.x0;2026:s42.r0.x0
- gb_mr05_vwap_stretch: 2022:s10.r0.x0;2023:s11.r0.x0;2024:s11.r0.x0;2025:s11.r0.x0;2026:s23.r0.x1
- gb_mr06_or_fade: 2023:s1.r1.x1;2024:s0.r1.x4;2025:s0.r1.x4;2026:s0.r1.x4
- gb_ti07_rsi_shift: 2022:s18.r0.x1;2023:s31.r0.x0;2024:s31.r0.x0;2025:s30.r0.x1;2026:s30.r0.x1
- gb_ti08_heikin_ashi: 2022:s42.r0.x0;2023:s42.r0.x0;2024:s42.r0.x0;2025:s42.r0.x0;2026:s42.r0.x0
- gb_ti01_supertrend: 2022:s14.r0.x0;2023:s14.r0.x0;2024:s29.r0.x0;2025:s21.r0.x1;2026:s23.r0.x1
- gb_ti05_vwap_pullback: 2022:s10.r0.x0;2023:s10.r0.x0;2024:s10.r0.x1;2025:s10.r0.x0;2026:s9.r0.x1
- gb_ti10_confluence: 2022:s14.r0.x0;2023:s24.r0.x0;2024:s25.r0.x0;2025:s25.r0.x0;2026:s4.r0.x0
- gb_ti09_adx: 2022:s35.r0.x0;2023:s46.r0.x0;2024:s44.r0.x0;2025:s44.r0.x0;2026:s44.r0.x0
- gb_ti03_ema_cross: 2022:s2.r1.x0;2023:s30.r1.x0;2024:s34.r1.x0;2025:s44.r1.x0;2026:s45.r1.x0

### How the rules were implemented (the most common reading where the catalog is ambiguous)

Common to all strategies:
- **Candles:** tf-minute candles anchored at 09:15. Indicators run continuously across days, as on a chart.
- **Signals and fills:** a signal is a candle CLOSE. The option (nearest weekly, else monthly) is bought at the next
  minute's open. For the "break" triggers of MR-01 / MR-02 / TI-09 the signal is the 1-minute bar that trades through
  the level, and the fill is at the next minute's open. This approximates a stop-entry order.
- **Entry window:** new entries until 14:30. Square-off at 15:10 or 15:15, as the catalog states.
- **Position rules:** one position at a time per index. Strategies that "exit and reverse" use separate CE and PE
  books, so the reversal entry happens at the same minute as the exit.
- **Expiry days:** skipped (the arms' rule; the next-week weekly contract is not in the data).
- **Fills, charges and lots:** the app's fills and charges, with the lot size in force on each date.
- **VWAP is a TWAP.** The index has no volume. "VWAP" is the running mean of the 1-minute typical price since 09:15,
  and the "VWAP bands" use its running standard deviation. This affects TI-04, TI-05, TI-10, MR-05 and the TWAP
  filter / stop options of TI-03 and TI-09.
- **Index stops** trigger when the 1-minute low or high touches the level. They are decided at the minute close and
  filled at the next open. "Close below" stops are therefore slightly tighter here.
- **"Opposite signal" exits** (flip, cross, red HA candle, RSI level) close the position at the open after that
  candle's close.

Strategy by strategy (the full grids are in the modules):

| id | rules as tested | grid (variants) |
|---|---|---|
| TI-01 | Supertrend flip on the candle close; the first candle of the day is skipped; out at the opposite flip (= the close-based ST-line stop). All 4 indices. | tf 3/5/15 x (ATR, mult) (7,3) (10,3) (14,3) (10,2) (10,1.5) x ADX(14)>20 on/off x 3 exits (flip only; -30% + % trail; -25%/+50%). 90 |
| TI-02 | ST flip and EMA alignment; out at the opposite flip. NIFTY, BANKNIFTY. | tf 5/15 x EMA 20/50, 9/21 x mult 3/2 x ATM/ITM1 x 4 exits (flip; -40/+80 pts; -30/+60%; -20/+40%). 64 |
| TI-03 | EMA fast/slow cross; out at the opposite cross; optionally a 5-candle swing stop and 2R target. All 4. | tf 3/5/15 x (5,20) (9,21) (13,34) (9,50) x TWAP-side filter on/off x stop cross/swing x ATM/ITM1. 96 |
| TI-04 | EMA9 crosses the TWAP; 1-ITM; premium target; out and reverse at the opposite cross. NIFTY. | tf 3/5/15 x target 5/8/12/20% x SL none/10/20%. 36 |
| TI-05 | 6 closes above a rising TWAP; a pullback candle's low within dist of the TWAP; a green (or bullish-engulfing) candle closes back above; stop at the lower of the swing low and the TWAP. NIFTY, BANKNIFTY. | dist 0.1/0.2% x green/engulf x chop filter (<= 2 TWAP crosses in the last hour) off/on x target 2R/3R/prior-day high x 2 exits. 48 |
| TI-06 | RSI crosses 50 (or 55/45, 60/40); the day's first signal only; -25%/+50%; EOD. NIFTY ATM. | tf 3/5/15 x RSI 9/14 x threshold 50/55/60 x 5 exits (-25/+50 plain, + Pine trail, + 15% trail; -20/+40; -30/+60). 90 |
| TI-07 | Breakout: RSI(14) crosses 60 (40). Pullback: bull regime, RSI dips to 40-50 and turns up. Stop: RSI back below 40, or swing. Target: 2R or RSI>80. Intraday only. All 4. | tf 15/60 x version x (60,40)/(55,45) x stop x target x 2 exits. 64 |
| TI-08 | The n-th consecutive green HA candle with no lower wick; stop at the HA low; out at the first red (or doji-like) HA candle. NIFTY, BANKNIFTY. | tf 5/15 x n 1/2/3 x wick tolerance 0/10% x EMA50 filter x exit mode. 48 |
| TI-09 | ADX > thr and rising, DI aligned; entry when the next candle breaks the setup high/low; swing stop 2R or TWAP stop 1R; optional exit when ADX turns down. All 4. | tf 5/15 x thr 20/25/30 x DI length 10/14 x stop x ADX exit. 48 |
| TI-10 | All chosen conditions turn true together (ST(10,3) green, EMA9>21, close > TWAP, RSI > 55/60); stop at the ST line; out at the ST flip; target 2R or none. NIFTY, BANKNIFTY. | tf 5/15 x 7 condition subsets / RSI levels x target x 2 exits. 56 |
| MR-01 | PoS 5-EMA. PE: the alert is a candle fully above the EMA, replaced by later alerts; entry when its low breaks. CE: alert fully below the EMA on the long timeframe. Stop at the alert's other extreme; target RR. NIFTY, BANKNIFTY. | tf pairs (5 short / 15 long), (5,5), (15,15) x EMA 5/9 x RR 2/3/4 x break/close trigger x ATM/ITM1. 72 |
| MR-02 | PoS Bollinger alert: a candle fully outside BB(20,k); entry on the break of its inner extreme. All 4. | tf 5/15 x k 1.5/2 x RR 2/3/4 x trigger x EMA5 combined on/off x ATM/ITM1. 96 |
| MR-03 | RSI back above 30 -> CE, back below 70 -> PE; premium SL/TP. NIFTY, BANKNIFTY. | tf 5/15 x RSI 9/14 x 30/70 or 20/80 x 6 exits (-40/+80 pts repo, -20/+40 pts, 3 % pairs, none). 48 |
| MR-04 | A candle trades at/above R3 (R2) and closes back below it -> PE (S mirror). Only on days opening inside S3-R3, candles from 09:30, the first per side per day. Stop at the next level out; index target P / previous close / R1. All 4. | tf 5/15 x level 3/2 x target x ATM/ITM1 x 3 exits (levels only; -30%; -30% + Pine trail). 72 |
| MR-05 | The first close beyond TWAP +- k SD -> fade toward the TWAP; stop 1 SD further out (fixed at entry); target the TWAP level at entry, or a close back across the moving TWAP; entries from 09:45. All 4. | tf 5/15 x k 1.5/2/2.5 x ADX<20 filter x target mode x ATM/ITM1 x 2 exits. 96 |
| MR-06 | After the opening range, price pokes outside and a candle closes back inside -> buy toward the other side; the first per side per day; premium SL/TP. BANKNIFTY (per the catalog). | OR 15/30 min x confirm on 1/5-min close x ATM/ITM1 x 6 exits (-40/+30, -40/+60, -20/+40 pts, -30/+30%, -30/+60%, -40/+60 + ladder). 48 |

### Caveats

- **The walk-forward test years are 2022-2026** (2020-21 only train; BANKNIFTY / FINNIFTY start in Aug 2021, SENSEX
  in May 2023). MR-06 is BANKNIFTY only, so its first test year is 2023.
- **Fills are modelled; there is no bid/ask.** Real fills on cheap options would be worse, which only strengthens the
  negative verdict.
- **The group ran in 7 chunks** (one data pass each) to keep memory bounded. The per-chunk REPORT.md corrections cover
  only their own chunk. The group-wide numbers above recompute BH / Holm, SPA / RC and DSR (N = 1,072) over all
  variants. PBO is per strategy.
- **The random baseline used 3 alternatives per signal** (the framework's floor when there are millions of
  candidates). Variants that lost money in full sample are not tested and get p = 1.
- **Monte Carlo risk sizing uses whole lots.** At 1% risk of Rs 5 lakh many trades size to 0 lots and are skipped.
  This is why some 1%-risk medians are slightly positive while the 1-lot medians are negative. It is not an edge.
- **Not tested:** VWAP with real volume (index futures volume is not usable historically), MIDCPNIFTY (no data), the
  next-week contract on expiry days, and multi-day holding.
