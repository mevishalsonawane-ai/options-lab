## Option buying, catalog group "ga": opening range, time of day, levels (obuy backtest)

Written 7 Oct 2026. Code: `research/obuy/strategies/ga_opening.py`, `ga_timeofday.py`, `ga_levels.py` (helpers in
`ga_common.py`), plus an extension of `orb.py` (new keyword options; the defaults reproduce the old signals exactly,
checked row for row). Run: `python3 -I research/obuy/run.py run ga_or01_orb15 ... ga_lv06_oiwall --name ga_all`
(17 strategies, **1,329 variants**, 10 random alternatives per signal, B = 2,000).
Data: NIFTY Aug 2020 to Oct 2026, BANKNIFTY / FINNIFTY Aug 2021 to Oct 2026, SENSEX May 2023 to Oct 2026. Fills and
charges are the app's, at 1 lot, with the lot size in force on each date. Walk-forward tests 2022 to 2026.

Outputs (scratchpad, not committed): `<scratchpad>/obuy_cache/runs/ga_all/` holds `REPORT.md`, `families.csv`,
**`variants.csv` (per-variant results)**, `trades.csv.gz` and `ga_post.json` (per-year walk-forward and Monte Carlo).
The robustness re-run is in `<scratchpad>/obuy_cache/runs/ga_lv05_check/`.
`<scratchpad>` = `/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad`.

### Verdict

**None of the 17 catalog strategies is a usable option-buying edge.**

One strategy, LV-05 (the Donchian N-day channel, traded the next day), formally passes all four gates in the main run.
It does not survive a fairer test, so I do not recommend it:

- **Fairer random baseline.** The main baseline enters at random minutes across the day. LV-05's next-day trades all
  enter at 09:16 and are held all day. Rerun with a baseline that enters at the same minute (only the side is a coin
  flip), its p rises from 0.0005 to 0.017. Holm-corrected across the 17 families, that is about 0.29, so gate 2 fails.
  (The rerun's own table shows Holm 0.034 only because that run had 2 families.)
- **Edge fading.** The walk-forward made Rs +95.7k in 2022 and +33.7k in 2023, then -14.3k in 2024, +19.5k in 2025
  and -48.8k in 2026. That is positive in 3 of 5 years: the minimum to pass, with the last year the worst.
- **Mostly bull-market longs.** The profit is mainly NIFTY and BANKNIFTY long trades from 2020 to 2023.
- **Overfitting checks.** DSR is 0.07 and PBO is 31% (56% in the next-day-only rerun). Across all 1,329 variants,
  White's Reality Check p = 0.94 and Hansen SPA p = 0.76.
- **Swing strategy cut to one day.** The catalog's LV-05 is positional. Here it is only the first-day leg.

Treat it as a lead for a proper multi-day test, not as something to trade.

The rest, in brief:

- **Best by walk-forward:** LV-05 at Rs +85.8k (282 trades, PF 1.23, max DD -93.3k), then OR-08 gap fill at
  Rs +8.4k, which is noise (p = 0.25).
- **Every other family lost money out of sample.**
- **The popular opening-range breakouts lose the most:**
  - ORB15: Rs -551k walk-forward. Its best variant over the full sample lost Rs -338k.
  - First 5-min candle: Rs -326k.
  - Box 9: Rs -366k.
  - Late ORB: Rs -261k.
  - None beats random entries with the same exits. This confirms the repo's earlier ORB result.
- **Time-of-day ideas** (2:50 candle, Gao first-half-hour momentum, last-30-minute OTM "jackpot") are all negative in
  4 or 5 of the 5 test years.
- **The jackpot buy** has raw p = 0.03 against random. That only means its direction rule picks the side better than a
  coin flip. It still loses Rs 125k, because OTM theta in the last 30 minutes costs more than the direction call earns.

One line per strategy. "In-sample best" is the full-sample best variant, which is selection-biased. Columns: net,
trades, years positive / walk-forward net / random-baseline p raw (BH) / walk-forward years positive / walk-forward
max DD.

| id | strategy | in-sample best | walk-forward | p raw (BH) | WF years + | WF max DD |
|---|---|---|---|---|---|---|
| OR-01 | 15-min ORB | -337.9k, 1,966 tr, 1/7 | -551.1k | 0.61 (0.90) | 1/5 | -625.8k |
| OR-02 | first 5-min candle | -337.8k, 2,559 tr, 0/7 | -325.7k | 0.64 (0.90) | 0/5 | -392.5k |
| OR-03 | late / wide ORB | -214.3k, 1,773 tr, 3/7 | -260.7k | 0.93 (1.00) | 1/5 | -325.8k |
| OR-04 | Bank Nifty Box 9 | -196.1k, 2,247 tr, 2/7 | -366.2k | 0.98 (1.00) | 1/5 | -383.2k |
| OR-05 | open = low / high | +25.4k, 207 tr, 4/7 | -83.4k | 0.58 (0.90) | 2/5 | -113.2k |
| OR-06 | 09:20 premium momentum | -60.3k, 2,717 tr, 4/7 | -282.3k | 0.88 (1.00) | 1/5 | -323.3k |
| OR-07 | gap and go | +84.9k, 483 tr, 4/7 | -29.7k | 0.012 (0.11) | 2/5 | -96.6k |
| OR-08 | gap fill | +46.8k, 572 tr, 5/7 | +8.4k | 0.25 (0.85) | 3/5 | -58.2k |
| TD-01 | 2:50 pm candle break | -57.3k, 550 tr, 0/7 | -82.1k | 1.00 (1.00) | 0/5 | -84.3k |
| TD-02 | first half-hour predicts last | -11.4k, 1,003 tr, 5/7 | -113.2k | 0.58 (0.90) | 1/5 | -141.2k |
| TD-03 | last-30-min OTM jackpot | -90.5k, 2,250 tr, 2/7 | -124.9k | 0.033 (0.19) | 1/5 | -173.4k |
| LV-01 | PDH / PDL break and retest | -12.9k, 1,960 tr, 3/7 | -129.7k | 0.49 (0.90) | 1/5 | -142.5k |
| LV-02 | narrow CPR | -2.6k, 648 tr, 3/7 | -62.3k | 0.18 (0.75) | 1/5 | -97.1k |
| LV-03 | Camarilla H4 / L4 | -44.3k, 2,716 tr, 3/7 | -144.8k | 0.38 (0.90) | 1/5 | -262.8k |
| LV-04 | NR7 / inside day (intraday leg) | +31.5k, 329 tr, 5/7 | -28.1k | 0.59 (0.90) | 1/5 | -58.7k |
| LV-05 | Donchian N-day (first-day leg) | +181.1k, 323 tr, 5/7 | **+85.8k** | 0.0005 (0.008); time-matched 0.017 | 3/5 | -93.3k |
| LV-06 | OI wall break | +15.5k, 172 tr, 4/7 | -79.8k | 0.93 (1.00) | 1/5 | -96.8k |

Every strategy was run. None was skipped. Two swing strategies (LV-04 and LV-05) are tested only as their intraday or
first-day legs, because the engine squares off every position the same day.

### Families: walk-forward, gates and overfitting checks (1 lot, after costs)

| strategy | variants | WF trades | WF net | per year | PF | win | p raw / Holm / BH | MC P(profit 1y) | best variant net | DSR | PBO | promoted |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ga_lv05_donchian | 96 | 282 | +85,754 | +18,084 | 1.23 | 49% | 0.000 / 0.008 / 0.008 | 72% | +181,146 | 0.07 | 31% | yes (see verdict) |
| ga_or08_gapfill | 72 | 476 | +8,426 | +1,777 | 1.02 | 56% | 0.251 / 1.000 / 0.853 | 53% | +46,769 | 0.01 | 34% | no |
| ga_lv04_nr7 | 72 | 168 | -28,141 | -5,955 | 0.88 | 42% | 0.586 / 1.000 / 0.899 | 38% | +31,521 | 0.00 | 76% | no |
| ga_or07_gapgo | 96 | 870 | -29,686 | -6,260 | 0.97 | 25% | 0.012 / 0.200 / 0.106 | 43% | +84,916 | 0.00 | 65% | no |
| ga_lv02_cpr | 72 | 552 | -62,250 | -13,127 | 0.89 | 29% | 0.177 / 1.000 / 0.754 | 33% | -2,645 | 0.00 | 46% | no |
| ga_lv06_oiwall | 96 | 527 | -79,775 | -16,823 | 0.78 | 18% | 0.931 / 1.000 / 0.997 | 21% | +15,474 | 0.00 | 55% | no |
| ga_td01_c250 | 60 | 607 | -82,108 | -17,315 | 0.69 | 41% | 0.997 / 1.000 / 0.997 | 6% | -57,331 | 0.00 | 12% | no |
| ga_or05_ohol | 54 | 610 | -83,396 | -17,602 | 0.82 | 36% | 0.583 / 1.000 / 0.899 | 21% | +25,446 | 0.00 | 8% | no |
| ga_td02_gao | 96 | 971 | -113,150 | -23,862 | 0.79 | 43% | 0.579 / 1.000 / 0.899 | 12% | -11,437 | 0.00 | 66% | no |
| ga_td03_jackpot | 36 | 1,917 | -124,865 | -26,332 | 0.84 | 40% | 0.033 / 0.502 / 0.190 | 12% | -90,464 | 0.00 | 15% | no |
| ga_lv01_pdhl | 64 | 1,672 | -129,689 | -27,349 | 0.84 | 46% | 0.492 / 1.000 / 0.899 | 15% | -12,862 | 0.00 | 38% | no |
| ga_lv03_camarilla | 72 | 2,468 | -144,763 | -30,528 | 0.95 | 38% | 0.384 / 1.000 / 0.899 | 34% | -44,340 | 0.00 | 5% | no |
| ga_or03_late | 80 | 1,511 | -260,661 | -54,969 | 0.85 | 40% | 0.931 / 1.000 / 0.997 | 14% | -214,250 | 0.00 | 55% | no |
| ga_or06_premmom | 72 | 2,475 | -282,312 | -59,485 | 0.83 | 43% | 0.883 / 1.000 / 0.997 | 9% | -60,266 | 0.00 | 78% | no |
| ga_or02_first5 | 96 | 2,244 | -325,718 | -68,689 | 0.88 | 42% | 0.635 / 1.000 / 0.899 | 15% | -337,808 | 0.00 | 45% | no |
| ga_or04_box9 | 96 | 1,916 | -366,230 | -77,232 | 0.84 | 30% | 0.983 / 1.000 / 0.997 | 10% | -196,143 | 0.00 | 17% | no |
| ga_or01_orb15 | 99 | 2,760 | -551,113 | -116,221 | 0.83 | 38% | 0.610 / 1.000 / 0.899 | 6% | -337,898 | 0.00 | 94% | no |

Variant level: White's Reality Check p = 0.944 and Hansen SPA p = 0.764 over all 1,329 variants. The only variants with
BH q < 0.05 against the random baseline are LV-05 next-day variants (q = 0.022), and none survives Holm (all >= 0.66).

**LV-05 robustness rerun** (`ga_lv05_check`). The grid is split by mode, with 20 random alternatives per signal:

| part | variants | WF net | p raw | baseline used | promoted in that 2-family run |
|---|---|---|---|---|---|
| next day (enter 09:16) | 48 | +85,754 | **0.017** | same minute (09:15 signal), coin-flip side | yes (Holm over 2 families = 0.034; over 17 it would be about 0.29) |
| intraday channel break | 48 | -52,052 | 0.624 | random minute 09:15 to 15:09 | no |

The walk-forward always picked N = 7 days, VIX below its 60-day median, monthly ITM1, held to 15:15 (with or without a
-30% stop).

Its trades by index, side and year (Rs) show that the profit is mainly 2020 to 2023 longs:

| s6|r1|x0 (N=7, VIX filter, next day, ITM1 monthly, EOD) | NIFTY long | NIFTY short | BANKNIFTY long | BANKNIFTY short |
|---|---|---|---|---|
| 2020-23 | +95.6k | +28.6k | +49.9k | +41.0k |
| 2024-26 | -14.8k | -0.6k | +6.6k | -25.3k |

### Walk-forward net by test year (Rs, 1 lot)

| strategy | 2022 | 2023 | 2024 | 2025 | 2026 (to Oct) |
|---|---|---|---|---|---|
| ga_lv05_donchian | +95,678 | +33,702 | -14,341 | +19,548 | -48,834 |
| ga_or08_gapfill | +10,238 | -8,617 | +6,668 | -21,743 | +21,880 |
| ga_lv04_nr7 | -13,666 | +14,552 | -22,793 | -4,018 | -2,216 |
| ga_or07_gapgo | -17,793 | +11,553 | -36,034 | +41,931 | -29,344 |
| ga_lv02_cpr | -41,730 | -8,643 | -12,271 | -31,595 | +31,989 |
| ga_lv06_oiwall | -10,814 | -20,986 | +6,164 | -39,219 | -14,919 |
| ga_td01_c250 | -9,136 | -13,591 | -30,246 | -7,117 | -22,018 |
| ga_or05_ohol | -19,114 | -73,985 | +8,199 | +17,314 | -15,811 |
| ga_td02_gao | -67,918 | -10,590 | -39,305 | -2,960 | +7,624 |
| ga_td03_jackpot | -53,125 | -30,723 | -61,781 | -3,639 | +24,403 |
| ga_lv01_pdhl | -62,401 | -868 | +9,514 | -18,711 | -57,224 |
| ga_lv03_camarilla | -8,920 | +45,685 | -52,869 | -101,750 | -26,909 |
| ga_or03_late | -28,528 | +8,235 | -47,367 | -123,115 | -69,885 |
| ga_or06_premmom | +44 | -119,496 | -3,150 | -56,929 | -102,781 |
| ga_or02_first5 | -14,560 | -40,240 | -94,767 | -72,442 | -103,710 |
| ga_or04_box9 | -89,656 | -60,127 | +48,793 | -246,478 | -18,760 |
| ga_or01_orb15 | -17,608 | -197,426 | -12,643 | -329,270 | +5,835 |

### Monte Carlo of the walk-forward trades on Rs 5 lakh

The walk-forward trades are resampled into 1-year paths, 10,000 of them. Under risk sizing, a trade without a premium
stop risks its whole premium, so it often gets 0 lots and is skipped.

| strategy | sizing | P(profit 1y) | median 1y P&L | 5th pct | 95th pct | P(DD>=20%) | P(DD>=50%) |
|---|---|---|---|---|---|---|---|
| ga_lv05_donchian | 1 lot | 72% | +17,149 | -30,448 | +68,005 | 0% | 0% |
| ga_lv05_donchian | 1% risk | 61% | +5,513 | -19,774 | +47,982 | 0% | 0% |
| ga_lv05_donchian | 2% risk | 50% | +64 | -53,205 | +96,896 | 0% | 0% |
| ga_or08_gapfill | 1 lot | 53% | +1,290 | -31,162 | +34,409 | 0% | 0% |
| ga_or08_gapfill | 1% risk | 41% | -5,470 | -45,125 | +41,780 | 0% | 0% |
| ga_or08_gapfill | 2% risk | 41% | -11,968 | -96,098 | +102,536 | 12% | 0% |
| ga_lv04_nr7 | 1 lot | 38% | -6,211 | -37,619 | +28,375 | 0% | 0% |
| ga_lv04_nr7 | 1% risk | 67% | +909 | -1,537 | +4,695 | 0% | 0% |
| ga_lv04_nr7 | 2% risk | 49% | -199 | -12,438 | +15,720 | 0% | 0% |
| ga_or07_gapgo | 1 lot | 43% | -8,024 | -72,395 | +67,984 | 2% | 0% |
| ga_or07_gapgo | 1% risk | 71% | +7,049 | -10,645 | +31,510 | 0% | 0% |
| ga_or07_gapgo | 2% risk | 62% | +11,591 | -40,764 | +83,634 | 0% | 0% |
| ga_lv02_cpr | 1 lot | 33% | -14,194 | -64,415 | +43,423 | 0% | 0% |
| ga_lv02_cpr | 1% risk | 21% | -38,776 | -101,137 | +46,958 | 12% | 0% |
| ga_lv02_cpr | 2% risk | 19% | -90,270 | -201,517 | +102,857 | 81% | 1% |
| ga_lv06_oiwall | 1 lot | 21% | -17,758 | -49,882 | +20,967 | 0% | 0% |
| ga_lv06_oiwall | 1% risk | 42% | -5,525 | -32,555 | +45,763 | 0% | 0% |
| ga_lv06_oiwall | 2% risk | 37% | -17,360 | -76,049 | +94,991 | 1% | 0% |
| ga_td01_c250 | 1 lot | 6% | -17,549 | -35,207 | +774 | 0% | 0% |
| ga_td01_c250 | 1% risk | 6% | -6,357 | -12,962 | +289 | 0% | 0% |
| ga_td01_c250 | 2% risk | 4% | -19,932 | -37,452 | -1,126 | 0% | 0% |
| ga_or05_ohol | 1 lot | 21% | -18,086 | -53,097 | +18,974 | 0% | 0% |
| ga_or05_ohol | 1% risk | 12% | -12,676 | -29,566 | +5,744 | 0% | 0% |
| ga_or05_ohol | 2% risk | 10% | -34,722 | -74,928 | +11,430 | 0% | 0% |
| ga_td02_gao | 1 lot | 12% | -23,734 | -57,684 | +10,598 | 0% | 0% |
| ga_td02_gao | 1% risk | 11% | -36,552 | -78,911 | +13,193 | 1% | 0% |
| ga_td02_gao | 2% risk | 10% | -78,433 | -155,250 | +23,159 | 55% | 0% |
| ga_td03_jackpot | 1 lot | 12% | -26,687 | -62,353 | +10,523 | 0% | 0% |
| ga_td03_jackpot | 1% risk | 1% | -128,653 | -198,263 | -37,088 | 87% | 0% |
| ga_td03_jackpot | 2% risk | 1% | -239,448 | -329,146 | -78,829 | 100% | 59% |
| ga_lv01_pdhl | 1 lot | 15% | -26,962 | -72,108 | +17,551 | 1% | 0% |
| ga_lv01_pdhl | 1% risk | 48% | -209 | -7,011 | +7,382 | 0% | 0% |
| ga_lv01_pdhl | 2% risk | 43% | -2,574 | -27,796 | +24,155 | 0% | 0% |
| ga_lv03_camarilla | 1 lot | 34% | -32,112 | -159,810 | +102,683 | 45% | 0% |
| ga_lv03_camarilla | 1% risk | 79% | +18,222 | -18,011 | +62,344 | 0% | 0% |
| ga_lv03_camarilla | 2% risk | 65% | +25,889 | -72,981 | +160,678 | 7% | 0% |
| ga_or03_late | 1 lot | 14% | -54,516 | -133,457 | +27,597 | 31% | 0% |
| ga_or03_late | 1% risk | 23% | -52,176 | -139,668 | +74,108 | 44% | 0% |
| ga_or03_late | 2% risk | 17% | -127,356 | -264,949 | +135,057 | 97% | 18% |
| ga_or06_premmom | 1 lot | 9% | -59,757 | -132,614 | +13,836 | 30% | 0% |
| ga_or06_premmom | 1% risk | 25% | -59,230 | -174,248 | +106,147 | 68% | 0% |
| ga_or06_premmom | 2% risk | 18% | -158,820 | -314,091 | +187,704 | 100% | 45% |
| ga_or02_first5 | 1 lot | 15% | -69,048 | -174,768 | +42,720 | 53% | 0% |
| ga_or02_first5 | 1% risk | 49% | -370 | -27,580 | +40,825 | 0% | 0% |
| ga_or02_first5 | 2% risk | 27% | -30,822 | -100,602 | +66,082 | 13% | 0% |
| ga_or04_box9 | 1 lot | 10% | -76,787 | -173,509 | +23,985 | 56% | 0% |
| ga_or04_box9 | 1% risk | 51% | +671 | -34,969 | +52,667 | 0% | 0% |
| ga_or04_box9 | 2% risk | 33% | -25,933 | -103,830 | +88,287 | 17% | 0% |
| ga_or01_orb15 | 1 lot | 6% | -116,351 | -230,661 | +3,433 | 79% | 4% |
| ga_or01_orb15 | 1% risk | 22% | -21,261 | -62,589 | +31,115 | 0% | 0% |
| ga_or01_orb15 | 2% risk | 18% | -58,948 | -147,672 | +64,932 | 54% | 0% |

The Camarilla, ORB, Box 9 and first-5-min results look better under 1% risk than at 1 lot. That is because index-stop
trades without a premium stop risk their whole premium under this sizing, so few lots are traded. It does not show an
edge.

### What was tested (grids fixed before any result)

All strategies share these settings:
- Expiry days are skipped. The near series is used: weekly where Dhan has it, otherwise monthly.
- Time exit 15:15, except TD-02 and TD-03, which exit at 15:25.
- The fill is the option's next-minute open.
- One position at a time per index.

Most grids change one factor at a time around the catalog's "most common" reading, to stay at 100 variants or fewer.

| id | grid | variants |
|---|---|---|
| OR-01 | base: 15-min range, 5-min close, stop at the other side, cutoff 13:30. Changed one at a time: range 5 / 30 / 60, bar 1 / 15, touch trigger, 20th-80th percentile width band, both directions, cutoff 11:00 / 12:00 (11 settings). x ATM / ITM1 / OTM1 x target 1 / 2 / 3R | 99 |
| OR-02 | 5-min range; 1- or 5-min close x 0.1-0.6% range filter on/off x gap-direction filter x previous-day high/low target on/off x ATM / ITM1 x 1 / 2 / 3R | 96 |
| OR-03 | range 30 / 60 / 120 min, entry from range end or from 11:15, 1-min close cross, stop at the other side, target 0.2 / 0.3 / 0.5% or none, x ATM / ITM1 x (levels only, or a -30% premium stop) | 80 |
| OR-04 | band 0.05 / 0.09 / 0.15 / 0.25%, buffer 0 / 0.02%, stop on 5-min close or touch, target none / 1R / 2R, x ATM / ITM1 | 96 |
| OR-05 | check 09:20 / 09:25 / 09:30, tolerance 0 / 0.03 / 0.05%, previous-close filter, stop at the day's extreme, target 1R / 2R / EOD, ATM | 54 |
| OR-06 | t0 09:16 / 09:20 / 09:30, x 5 / 10 / 15 / 20% (1-min close), ATM strike frozen at t0, exits: SL 20 / 30% x TP 40 / 100%, plus SL with the Pine trail | 72 |
| OR-07 | gap 0.3 / 0.5 / 0.8 / 1.2%, range 5 / 15 min, break in the gap's direction on the right side of the TWAP, stop at the range or the tighter TWAP, x ATM / ITM1 x (EOD, Pine trail, 25% trail) | 96 |
| OR-08 | gap band 0.3-0.8 / 0.2-0.8 / 0.3-1.2%, touch or 5-min close of the first 15-min candle against the gap, target the previous close or a 50% fill, x ATM / ITM1 x (levels, -30%, trail) | 72 |
| TD-01 | NIFTY, candle 14:45 or 14:50, PE mirror on/off, x ITM1 / ITM2 / ATM x index SL/TP 15/20, 10/20, 20/30, 30/60 or none | 60 |
| TD-02 | r1 including or excluding the gap, decide 14:45 / 15:00, threshold 0 / 0.25 / 0.5%, VIX above its 60-day median on/off, x ATM / ITM1 x (no stop, -30%) | 96 |
| TD-03 | trend vs open / previous close / TWAP at 14:45, OTM1 / OTM2, target 2x, stop none / 30 / 50%, out 15:15 / 15:25 | 36 |
| LV-01 | retest yes/no, 5-min close or touch, opening bias on/off, x ATM / ITM1 x 1 / 2 / 3R, and 3R + Pine trail | 64 |
| LV-02 | CPR narrower than 0.1 / 0.2 / 0.3%, 5- or 15-min close, target R1 / R2 / none, x ATM / ITM1 x (levels, -30%) | 72 |
| LV-03 | H4 / L4 or H3 / L3, target 0 / 1 / 2 x the level gap, stop on close or touch, x ATM / ITM1 x (levels, -30%, trail) | 72 |
| LV-04 | NR4 / NR7, inside day yes/no, touch or 15-min close, target 1 / 2 x range or none, x ITM1 near / ITM1 monthly / ATM near | 72 |
| LV-05 | N 5 / 7 / 10 / 20, VIX below its 60-day median on/off, next-day or intraday mode, x monthly ATM / ITM1 x (EOD, -30%, trail) | 96 |
| LV-06 | OI lookback 15 / 30 min, ATM ±5 / 10 strikes, OI drop >0 / 5 / 10%, target next wall or none, x ATM / ITM1 x (levels, -30%) | 96 |

### Deviations and caveats

- **No index volume.** "VWAP" is time-weighted (the running mean of the 1-minute typical price). This affects OR-07
  and TD-03. The optional futures-volume and ADX filters were not tested.
- **Partial exits are not modelled.** These include half at 1R and half at 2R (OR-04, LV-01), 2/3 at the fill (OR-08),
  and stop to breakeven at 1R. The whole position exits at a single target. The premium ladder and Pine trail stand in
  for "move to breakeven".
- **Intraday only.** LV-04 (hold 1-3 days) and LV-05 (positional, roll before expiry) are tested only as their
  intraday or first-day leg. There is no next-weekly contract, so the "next weekly" (at least 5 DTE) instrument is
  replaced by the nearest monthly. A multi-day test of LV-05 would need an engine change.
- **Stops are on index levels.** Close-confirmed stops (OR-04, LV-03, LV-06) are applied as an exit at the open after
  the 5-minute close that breaches the level. Event-day filters ("skip before scheduled events") are not applied.
- **TD-01 uses index points** (15 / 20) on NIFTY spot, not futures as in the source.
- **The random baseline window is per strategy.** For strategies that always enter at one clock time (LV-05 next-day
  mode, TD-02, TD-03), a window wider than the real entry times flatters the strategy. This is why LV-05 was rerun with
  a time-matched baseline. TD-02 and TD-03 already use their decision minute(s).
- **Rolling ATM±10 data.** OR-06 and LV-06 read option prices and OI from this window. The OI "wall" is therefore the
  biggest OI within ±5 or ±10 strikes, forward-filled while a strike is outside the window.
- **Old ORB grids unchanged.** The `orb.py` extension leaves `orb15` and `orb_grid` unchanged: their signals are
  identical row for row.
