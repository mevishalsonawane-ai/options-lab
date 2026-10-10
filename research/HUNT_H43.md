# HUNT h43: the outside "bake-off" report's 5 BANKNIFTY strategies and its tuned CHAMPION, tested on all our data

Code: `research/hunt/h43/` (pre-registration `PREREG.md`, written before any history P&L; amendments 1-2 and Part D
were added before the P&L they cover). Logs and CSVs: `scratchpad/hunt/h43/`. Option BUYING only, 1 lot.

## Verdict

**All 5 strategies and the CHAMPION are 25-day luck. None is a real edge.**

- On our full history before the holdout (Aug 2021 - Sep 2025, 845 trading days), **all 29 versions lose money after
  real costs**. Not one beats random entries taken at the same time of day. BH q = 1.00 for all. White RC p = 1.00,
  Hansen SPA p = 1.00.
- Even before any costs (gross), the headline versions make between Rs -131 and Rs +7 a day. The edge they need to
  pay costs (about Rs 100-150 a trade) is not there.
- In the locked holdout (Oct 2025 - Oct 2026) the CHAMPION loses Rs -83 a day net. EMA 9/21 5-min (the report's
  "medium trust" #1) made Rs +109 a day net in the holdout. But it lost in 4 of the 5 earlier years (Rs -102 a day),
  so a good year after four bad ones is what luck looks like. It is not a pre-registered pass.
- **Rs 5,000 a day with Rs 1 lakh: NO.** The best holdout result (+Rs 109/day) would need about 46 BANKNIFTY lots;
  Rs 1 lakh carries 5 (an ATM monthly lot costs about Rs 20,000 now).
- **Part D, "enhance it to the best": no enhancement survives.** I searched 1,807,740 versions (5 indices, 4 strike
  choices, 11 entry filters, 11 exits, 3 trade caps, wider parameter grids). The best in-sample versions make Rs +81 to
  +401 a day. Chosen honestly (nested walk-forward, each year picked only on earlier years), they **lose in all 16
  test years**: Rs -104 to -225 a day. That is worse than the plain rules. White RC p = 1.00 and SPA p = 1.00. In the
  holdout, 3 of the 4 enhanced versions lose. The fourth (+228/day) gets all its profit from 5 trades.

| # | strategy (report's headline) | report on its 25 days | ours, same window, their costs | pre-holdout net Rs/day (ours) | years positive | beats random? | holdout net Rs/day | verdict |
|---|---|---|---|---|---|---|---|---|
| 1 | EMA9/21 + ADX>20, 5-min, 30/60, 40 min | +7,656 | +1,311 | -102 | 1 of 5 | no (p 0.26) | +109 | luck |
| 2 | Bollinger(20,2) 1-min, 30/60, 15 min | +10,852 | -3,585 | -261 | 1 of 5 | no (p 0.32) | +13 | luck |
| 3 | ORB 15-min, 5-min close, 30/60, 40 min | +4,573 | +6,510 | -241 | 0 of 5 | no (p 0.93) | -138 | luck |
| 4 | Afternoon PE, ADX 15-25, 5-min, 30 min | +3,638 | +6,677 | -205 | 1 of 5 | no (p 0.62) | -190 | luck |
| 5 | ORB 30-min, 5-min close, 30/60, 40 min | +912 | +4,034 | -167 | 1 of 5 | no (p 0.62) | -47 | luck |
| C | CHAMPION EMA8/21 + ADX>15, 25/50 + lock, 60 min | +13,809 | +3,915 | -258 (nearest) / -218 (monthly) | 1 of 5 | no (p 0.62 / 0.25) | -83 | luck |

"Their costs" = Rs 95 per lot round trip, fills at the printed prices. "Ours" = app fills, app charges and the real
half-spread (0.16% of premium each side). Rs/day is per trading day at 1 lot of 30.

## How it was tested

- **Data:** our Dhan BANKNIFTY spot minutes and nearest-expiry ATM option minutes (weekly until Nov 2024, monthly
  after). The CHAMPION was also run on the nearest MONTHLY, as the document says.
- **Lot:** today's 30 for every year, so every year is in today's rupees.
- **Entry:** signal on the spot bar's close, buy the ATM option at the next minute's open.
- **Exits:** the premium stop and target are checked on the option's own **1-minute HIGH and LOW**. If both are hit in
  the same minute, the stop is taken first. The "close-based" variant decides stop / target on 1-minute closes and
  fills at the next open. Time stops count from the entry. Square-off 15:15. Expiry days skipped.
- **One trade at a time**, max trades a day as the report says.
- **Engine:** the validated `research/obuy` engine. For history, an outcome table of every minute, side and exit;
  the table lookups match direct engine runs trade for trade on the report window (all 21 checked variants equal to
  the rupee).
- **Holdout** locked from 2025-10-01; our data ends 2026-10-05.

## Part A: can we reproduce the report on its own 25 days?

**Partly. The trade counts match. The rupees do not.**

Our window is 2026-08-31 to 2026-10-05: 24 sessions, 23 tradable (expiry 29 Sep skipped). The report also had
6 and 7 October, which we do not have (our option data stops at 12:12 on 6 Oct).

| strategy | report trades | our trades | report net | ours, their costs, HIGH/LOW | ours, their costs, close-based | ours, our costs, HIGH/LOW | gross |
|---|---|---|---|---|---|---|---|
| EMA9/21 + ADX>20, 5-min | 22 | 21 | +7,656 | +1,311 | +1,311 | -128 | +2,934 |
| EMA9/21, 15-min (4 bars = 60 min) | 8 | 10 | +6,662 | +7,632 | +7,819 | +7,001 | +8,418 |
| Bollinger 1-min | 46 | 46 | +10,852 | -3,585 | -3,365 | -7,132 | -82 |
| Bollinger 5-min (6 bars = 30 min) | n/a | 45 | -222 | +10,301 | +10,499 | +6,879 | +13,733 |
| Bollinger 15-min (3 bars = 45 min) | n/a | 27 | -16,760 | -8,910 | -8,722 | -10,606 | -6,797 |
| ORB 15, 5-min | 22 | 22 | +4,573 | +6,510 | +6,510 | +4,811 | +8,181 |
| ORB 15, 15-min (4 bars) | n/a | 22 | -6,372 | +586 | +586 | -1,092 | +2,261 |
| Afternoon PE, 5-min | 32 | 26 | +3,638 | +6,677 | +6,677 | +5,570 | +8,791 |
| Afternoon PE, 15-min (3 bars) | n/a | 13 | -5,710 | +4,281 | +4,281 | +3,688 | +5,331 |
| ORB 30, 5-min, 40-min stop | 21 | 21 | +912 | +4,034 | +4,034 | +2,405 | +5,629 |
| ORB 30, 5-min, NO time stop | 21 | 21 | +912 | -16,957 | -17,851 | -18,501 | -15,351 |
| ORB 30, 1-min (20 bars) | n/a | 23 | -6,631 | +5,267 | +5,267 | +3,564 | +7,026 |
| CHAMPION (nearest = monthly here) | 33 | 32 | +13,809 | +3,915 | +6,064 | +1,567 | +6,382 |

What this says:
- **Counts match** (21 vs 22, 46 vs 46, 22 vs 22, 21 vs 21, 32 vs 33). So the signal rules are understood the same
  way. The indicators must run continuously across days: resetting them each morning gives 40 EMA trades, not 22.
- **The rupees swing by thousands on small differences.** Two missing days, a few minutes' difference in the
  fill, or the order of stop and target inside a 5-minute option bar move a 25-day total by Rs 5,000-15,000.
  That fragility is itself the main finding.
- **The report's timeframe "robustness" does not reproduce.** It says Bollinger works only on 1-min and ORB 15 only
  on 5-min. On our data Bollinger 5-min is the winner and Bollinger 1-min the loser; ORB 15-min on 15-min bars is
  flat, not -6,372. Which timeframe "wins" in 25 days is noise.
- **ORB 30 must have had a time stop.** With no time stop it loses Rs -17k; with the 8-bar (40-min) stop the parameter
  document lists, it makes +4k. I tested both.
- **CHAMPION:** our first half made +10.7k and the second half lost -6.8k (their costs). The document says the
  opposite (tuned half +4.2k, "confirmed" half +9.2k). Its "out-of-sample confirmation" does not survive a careful
  re-run of the same 25 days.
- Close-based exits change little except for the CHAMPION (+2.1k better on closes): its profit lock and 25% stop sit
  close to the price and get hit by 1-minute wicks.
- **Our costs** cost about Rs 160 per trade more than Rs 95 (app charges at today's 0.15% STT plus the real spread).
  Over 20-45 trades that is Rs 3,000-7,000 of the window's profit.

## Part B: every year we have (Aug 2021 - Sep 2025), rules fixed

Rs/day per trading day (845 days), 1 lot of 30. "random" = mean net per trade of entries at the same minute, same
side, random day of the same year, identical exits (2,000 draws). p = chance random does at least as well.

| version | trades | gross/day | their costs/day | our net/day | net/trade | random net/trade | p vs random | 2021 | 2022 | 2023 | 2024 | 2025 (to Sep) | max DD | green months |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| EMA 5m, 40 min | 819 | +7 | -74 | -102 | -105 | -145 | 0.26 | +4,510 | -6,048 | -32,122 | -34,585 | -17,691 | -115k | 36% |
| EMA 15m, 40 min | 332 | +23 | -10 | -21 | -54 | -144 | 0.18 | +4,299 | -1,930 | -21,352 | -26,810 | +27,703 | -67k | 46% |
| EMA 15m, 60 min | 326 | -18 | -50 | -61 | -158 | -151 | 0.51 | +14,942 | -16,050 | -30,484 | -42,408 | +22,449 | -97k | 40% |
| EMA 1m, 40 min | 1,647 | -202 | -365 | -420 | -215 | -153 | 0.91 | -18,078 | -73,246 | -60,115 | -100,101 | -103,270 | -368k | 26% |
| BB 1m, 15 min | 1,690 | -35 | -201 | -261 | -130 | -148 | 0.32 | +9,874 | -46,283 | -69,533 | -57,649 | -56,900 | -247k | 38% |
| BB 5m, 30 min | 1,668 | -38 | -203 | -259 | -131 | -140 | 0.41 | -47,899 | -78,522 | -75,612 | -62,070 | +45,113 | -266k | 34% |
| BB 15m, 45 min | 1,130 | -52 | -163 | -201 | -150 | -148 | 0.52 | -9,557 | -41,223 | +6,485 | -128,354 | +2,909 | -234k | 40% |
| ORB15 5m, 40 min | 827 | -131 | -212 | -241 | -246 | -144 | 0.93 | -10,503 | -33,218 | -43,782 | -71,126 | -44,790 | -212k | 30% |
| ORB15 15m, 60 min | 810 | -120 | -200 | -227 | -237 | -164 | 0.82 | +2,770 | -2,447 | -65,480 | -90,561 | -36,027 | -212k | 32% |
| PE 5m, 30 min | 1,027 | -80 | -184 | -205 | -168 | -153 | 0.62 | -11,132 | -55,890 | -67,386 | +5,354 | -43,916 | -181k | 30% |
| PE 15m, 45 min | 446 | -91 | -137 | -144 | -273 | -198 | 0.83 | -5,343 | -35,998 | -42,984 | -13,005 | -24,405 | -124k | 30% |
| ORB30 5m, 40 min | 805 | -60 | -139 | -167 | -175 | -155 | 0.62 | +7,748 | -21,971 | -22,732 | -42,471 | -61,270 | -165k | 34% |
| ORB30 5m, no time stop | 805 | -351 | -431 | -457 | -480 | -228 | 0.98 | -28,529 | -63,680 | -55,772 | -100,694 | -137,469 | -390k | 24% |
| ORB30 1m, 20 min | 814 | -4 | -84 | -112 | -116 | -142 | 0.29 | +7,005 | -16,298 | +3,087 | -28,907 | -59,285 | -112k | 52% |
| CHAMPION, nearest | 1,264 | -92 | -217 | -258 | -172 | -157 | 0.62 | +391 | -45,130 | -54,796 | -83,666 | -34,393 | -237k | 26% |
| CHAMPION, monthly | 1,247 | -3 | -118 | -218 | -148 | -185 | 0.25 | +5,632 | -33,678 | -69,803 | -51,915 | -34,393 | -227k | 34% |

(2021 is Aug-Dec only. The other 13 tested versions - other timeframes and the 120% target - are all worse or
similar; full table in `partB_pre.csv`.)

- **29 of 29 versions lose money net.** Even at the report's own Rs 95 cost, all 29 lose.
- **Gross, before any cost, they are about zero or negative.** The best gross is Rs +23 a day. A trade costs about
  Rs 100-150. There is nothing to pay it with.
- **None beats random entries at the same time of day.** Smallest p = 0.12 (PE 1-min). BH over the report's 41
  combinations: every q = 1.00. White RC p = 1.00, Hansen SPA p = 1.00 over the 29 versions.
- The EMA versions look least bad because they trade least (the cost is per trade), not because they predict.
- **Monthly vs nearest** (CHAMPION): the monthly option's slower decay helps gross (Rs -3 vs -92 a day), but the
  higher premium costs more spread, so net is about the same (-218 vs -258). Both lose in 4 of 5 years.

## Part B: the locked holdout (1 Oct 2025 - 5 Oct 2026), run once

236 tradable days. In this period BANKNIFTY has only monthly options, so nearest = monthly.

| version | trades | gross/day | their costs/day | our net/day | excl. the report's window | p vs random | max DD | worst month | green months |
|---|---|---|---|---|---|---|---|---|---|
| EMA 5m, 40 min | 231 | +271 | +198 | **+109** | +122 | 0.006 | -25k | -13.8k | 69% |
| EMA 15m, 40 min | 105 | +91 | +58 | +14 | -4 | 0.10 | -23k | -9.8k | 46% |
| EMA 15m, 60 min | 104 | +92 | +60 | +16 | -15 | 0.13 | -29k | -14.3k | 54% |
| BB 1m, 15 min | 472 | +339 | +190 | +13 | +48 | 0.02 | -29k | -15.8k | 46% |
| BB 5m, 30 min | 466 | +407 | +260 | +88 | +65 | 0.01 | -42k | -18.6k | 54% |
| ORB15 5m, 40 min | 227 | +19 | -53 | -138 | -175 | 0.24 | -54k | -15.1k | 31% |
| PE 5m, 30 min | 285 | -19 | -113 | -190 | -237 | 0.17 | -55k | -17.7k | 31% |
| ORB30 5m, 40 min | 220 | +105 | +36 | -47 | -63 | 0.08 | -29k | -7.7k | 46% |
| CHAMPION (monthly = nearest) | 336 | +146 | +39 | **-83** | -99 | 0.11 | -61k | -23.9k | 54% |

- The holdout is kinder than 2022-2025 for the EMA and Bollinger versions, and the effect is not only the report's
  25 days: excluding them, EMA 5m still makes +122 a day.
- But nothing passes the pre-registered rule (positive before the holdout, more than half the years positive, BH
  q < 0.05 vs random, then positive in the holdout). Every version fails the first three before the holdout is even
  looked at. In the holdout the best q (BH over 41) is 0.12.
- A strategy that lost Rs 102 a day for four years and then makes Rs 109 a day for one year is a regime change or luck,
  not an edge you can plan on. It also lost 2025 Jan-Sep (Rs -17.7k), which was already the monthly-only regime.

## Part C: plain verdict per strategy

At 1 lot of 30, our costs. Lots for Rs 5,000/day = 5,000 / holdout net per day. Lots Rs 1 lakh carries = 1,00,000 /
median premium per lot (about Rs 20,000 now; about Rs 9,800 for the weekly ATM before Nov 2024).

| strategy | verdict | pre-holdout Rs/day | holdout Rs/day | holdout max DD | green months (pre / holdout) | lots for Rs 5,000/day | lots Rs 1 lakh carries |
|---|---|---|---|---|---|---|---|
| 1 EMA9/21 + ADX 5-min | **25-day luck** (lost 4 of 5 years; good holdout year) | -102 | +109 | -25k | 36% / 69% | 46 | 5 |
| 2 Bollinger 1-min | **luck** | -261 | +13 | -29k | 38% / 46% | 383 | 5 |
| 3 ORB 15 | **luck** | -241 | -138 | -54k | 30% / 31% | never | 5 |
| 4 Afternoon PE | **luck** (the "bleed month" regime) | -205 | -190 | -55k | 30% / 31% | never | 6 |
| 5 ORB 30 (40-min stop) | **luck / no edge** | -167 | -47 | -29k | 34% / 46% | never | 5 |
| CHAMPION | **luck, and over-fitted to the 25 days** | -258 / -218 | -83 | -61k | 26% / 54% | never | 5 |

Rs 5,000/day at Rs 1 lakh: **NO** for every one of them.

## Part D: the enhancement search ("enhance the search and the strategy to the best")

Declared in `PREREG.md` before any part-D P&L.

**What was searched, per family:**
- **EMA cross:** EMA pairs 5/13, 5/21, 8/21, 9/21, 9/34 and 13/34; ADX threshold none, 15, 20, 25 or 30; candles of
  3, 5, 10 or 15 minutes. That is 120 sets, and it includes the CHAMPION's 8/21 and ADX>15.
- **Bollinger:** length 14, 20 or 30; width 1.5, 2 or 2.5; candles of 1, 3, 5, 10 or 15 minutes. 45 sets.
- **ORB:** opening range of 15, 30, 45 or 60 minutes; confirmation candle of 1, 3, 5, 10 or 15 minutes. 20 sets. This
  covers both #3 (ORB 15) and #5 (ORB 30).
- **Afternoon PE:** start at 13:00, 13:30, 14:00 or 14:30; ADX band 10-20, 15-25, 20-30 or none; candles of 3, 5, 10
  or 15 minutes. 64 sets.
- **11 entry filters:** none; VIX low / high; calm / wide day so far; beyond the prior-day high or low; inside the
  prior-day range; higher-timeframe trend agrees; option-OI build-up agrees; morning only; afternoon only.
- **4 strike choices:** ATM, 1-ITM and 1-OTM on the nearest expiry, plus ATM on the nearest monthly.
- **11 exits:** 30/60 with a time stop of 15, 30, 40 or 60 minutes, or none; points +15/-10, +20/-15 and +30/-20;
  the profit-lock ladder; a trailing stop; and the CHAMPION's exit.
- **Max trades a day:** 1, 2 or 4.
- **All 5 indices:** NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX.

**How it was run:**
- Every exit is checked on the option's 1-minute HIGH and LOW.
- Costs are the app's charges plus the real spread.
- The total is **1,807,740 versions**. Every one is counted in White RC and SPA.

**Overall:**
- Before the holdout, only 3.6-10.5% of versions in a family make money at all. The median version loses Rs 30-78 a
  day.
- **White RC p = 1.00 and Hansen SPA p = 1.00** over all 1.8 million. The best of them is what the best of 1.8 million
  coin flips looks like.

### Nested walk-forward: each test year uses the version that did best on the years before it

Rs/day is per trading day of the calendar (NIFTY's), 1 lot of that index.

| family | 2022 pick → result | 2023 | 2024 | 2025 (Jan-Sep) | walk-forward total | walk-forward Rs/day |
|---|---|---|---|---|---|---|
| EMA cross (incl. CHAMPION) | NIFTY 1-ITM, EMA 13/34 5m, afternoon, hold to 15:15 → -77/day | FINNIFTY ATM 13/34 + ADX>15, OI filter → -95 | same → -174 | MIDCP 1-ITM 5/21, wide day → -226 | -1,27,436 | **-138** |
| Bollinger | NIFTY 1-ITM BB(14,2) 3m, afternoon, 4/day → -53 | FINNIFTY 1-ITM BB(30,2) 5m, wide day → -38 | same → -543 | NIFTY 1-ITM BB(14,2) 3m → -275 | -2,08,269 | **-225** |
| ORB (15 and 30) | NIFTY monthly ATM, ORB 45, 15m, morning → -82 | FINNIFTY ATM ORB 30, 3m, VIX high → -10 | same → -358 | MIDCP 1-ITM ORB 60, 3m → -119 | -1,33,668 | **-144** |
| Afternoon PE | NIFTY 1-ITM, 13:00, ADX 15-25, 10m, trend filter → -53 | FINNIFTY 1-ITM, 13:30, 5m, VIX high → -61 | same → -130 | FINNIFTY 1-ITM, 13:30, ADX 20-30 → -192 | -95,932 | **-104** |

- **16 of 16 out-of-sample test years lose money.**
- Every family's walk-forward does **worse** than the plain rule did. For example, plain EMA 9/21 lost Rs -102 a
  day; the "enhanced" walk-forward lost Rs -138 a day.
- Almost every pick holds to 15:15 (the "S30EOD" exit). Each pick is the one that rode a few big trending days in its
  training years. Then it pays theta the next year.

### The enhanced version of each family (best before the holdout), and its one holdout run

The enhanced version is the family's best before the holdout, with at least 30 trades. Rs/day is per trading day of
that index. Lots are today's: MIDCP 120, FINNIFTY 60.

| family | enhanced version | pre-holdout net Rs/day (gross) | its years | holdout net Rs/day (gross) | holdout excl. report window | holdout max DD | holdout green months | lots for Rs 5,000/day | lots Rs 1 lakh carries |
|---|---|---|---|---|---|---|---|---|---|
| EMA | MIDCP 1-ITM, EMA 5/21 + ADX>15, 5m, "wide day" filter, 30/60 to 15:15, 1/day | +246 (+344) | 2023 -8k, **2024 +183k**, 2025 -27k | **-82** (+90) | -208 | -111k | 38% | never | 4 |
| Bollinger | MIDCP monthly ATM, BB(20,2.5) 3m, OI filter, 30/60 to 15:15, 1/day | +401 (+499) | 2023 +33k, 2024 +22k, **2025 +186k** | **+228** (+413) | +224 | -79k | 62% | 22 | 4 |
| ORB | MIDCP monthly ATM, ORB 15, 3m close, OI filter, 30/60 to 15:15, 1/day | +251 (+318) | 2023 +13k, 2024 -20k, **2025 +159k** | **-289** (-152) | -371 | -109k | 46% | never | 4 |
| Afternoon PE | FINNIFTY 1-ITM, from 13:30, ADX 20-30, 5m, VIX high, 30/60 to 15:15, 4/day | +81 (+147) | **2022 +92k**, 2023 -3k, 2024 +14k, 2025 -36k | **-140** (-26) | -154 | -37k | 23% | never | 4 |

How much of the "enhancement" is real?
- **In-sample:** the enhanced versions make Rs +81 to +401 a day. Each makes most of its money in ONE year (bold).
- **Out of sample, chosen honestly (walk-forward):** Rs -104 to -225 a day.
- **Holdout:** 3 of 4 lose. The in-sample gain is about 100% fitting.
- The one holdout winner (Bollinger on MIDCP) made +Rs 54k on 214 trades. Of that, +Rs 89k came from its top 5
  trades; the other 209 trades lost Rs -34k. Its t-stat is 0.6.
  - h24 showed that such MIDCP runaway winners depend on getting filled at a tight spread.
  - One positive out of 4 holdout tries is what chance gives.
  - It needs 22 MIDCP lots for Rs 5,000/day. Rs 1 lakh buys 4.
- The search ranks versions by rupees, so it favours the indices with the biggest rupees per lot (MIDCP 120,
  FINNIFTY 60). That makes the picks swing harder both ways. It does not make them predict better.

## Files

- `research/hunt/h43/`:
  - `PREREG.md`
  - `sigs.py`: the report's signals
  - `partA.py` and `partA_diag.py`: the report window and the reproduction checks
  - `table.py`: BANKNIFTY ATM outcome tables
  - `partB.py`: history, random baseline, BH/SPA and holdout
  - `partD.py` and `partD_report.py`: the enhancement search, nested walk-forward and the enhanced holdout
- `scratchpad/hunt/h43/`:
  - `partA.csv` and `partA_trades.csv.gz`
  - `partA_diag.csv`
  - `partB_pre.csv` and `partB_hold.csv`, with their `_daily` and `_spa` files
  - `D/final.json`: walk-forward picks and SPA
  - `D/partD_enhanced.csv`
  - `D/detail_trades.csv.gz`
  - `D/res_*.npz`: per-version per-year results
  - `D/hold_*.npz`: sealed holdout sums
