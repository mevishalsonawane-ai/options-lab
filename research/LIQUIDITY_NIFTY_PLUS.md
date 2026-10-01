# Liquidity 15+5 on NIFTY: enhancements, validated out-of-sample (research/liquidity_nifty_plus.py)

Year A: 2024-04-01 .. 2025-04-03 (250 days). Year B: 2025-04-04 .. 2026-04-13 (249 days). NIFTY lot 75, real option minute prices, 0.5 slippage a side, Rs 40 a round trip. BUYING options only (ATM CE on an up-break, ATM PE on a down-break unless noted). Net Rs is per lot per year, 15-min and 5-min books together unless `tfs` says otherwise. Max DD on the daily equity curve.

**Variants evaluated: 47** (1 baseline, 29 single-factor changes, 11 combinations built on Year A, 8 built on Year B; plus the no-premium-stop legacy row for reference).

## Verdict (read this first)

- **Baseline does not work on NIFTY.** Liquidity 15+5 as the arm runs it (15% premium stop) loses **-Rs 66,085** (Year A,
  488 trades, t -1.24) and **-Rs 28,299** (Year B, 469 trades, t -0.81) per lot. The index only moves +0.2 / +1.1 pts a
  trade our way; a NIFTY ATM option cannot pay 1 pt of slippage plus Rs 40 on that. (LIQUIDITY_INDICES.md's -44k / -22k
  used a Feb-to-Feb split and no premium stop; without the premium stop this Apr-to-Apr split gives -54k / -25k.)
- **Consistent but insufficient improvements** (better than baseline in BOTH years, still negative): a profit lock
  (once the premium is +30%, trail a stop 15% below its high: -29k / -16k), one trade per day per book (-25k / -16k),
  skipping days when the traded option expires the next day (-40k / -18k), the VWAP-side filter (-49k / -20k), dropping
  the failed-break stop (-55k / -22k). None turns the 15+5 books positive in either year.
- **Chosen on Year A -> tested on Year B:** `10-minute chart only, first-hour-range filter, profit lock 30%/15%, skip
  1-DTE` = +Rs 36,979 on A (55 trades, t 1.88) and **+Rs 6,191 on B** (33 trades, 0.13/day, t 0.78, 7/12 green
  months, max DD -4,063). It formally meets the brief's bar (positive both years, beats baseline in the held-out year).
- **Chosen on Year B -> tested on Year A:** `30-minute only, no failed-break stop, VWAP side, profit lock, skip 1-DTE`
  = +18,534 on B but **-8,106 on A**. Fails.
- **Recommendation: none with confidence.** The A-chosen setting passes the letter of the test, but: (1) its held-out
  result is +Rs 6k from 33 trades with t 0.78 - indistinguishable from zero, and the 3 best trades in Year B earn
  +8.5k, more than the whole year; (2) the reverse direction fails; (3) it is a knife edge - the same filter stack on
  the neighbouring charts gives, Year A / Year B: 5-min +27k / **-23k** (t -2.36), 15-min +27k / +0.7k, 30-min
  -6k / +9k, and on the arm's 15+5 books **+54k / -22k**; (4) the first-hour filter alone on 15+5 was -0.3k / -29k
  (t -2.16 in B). The honest answer is that no reasoned change reliably makes Liquidity 15+5 pay on NIFTY options;
  the edge on the index (a point or two a trade) is too small for option buying at NIFTY's point scale.
- If the lead still wants a NIFTY arm on paper, the only defensible candidate is the A-chosen setting, paper-traded,
  at about 1 trade every 5-8 days (0.22 / 0.13 a day) (settings below).

### Exact settings of the A-chosen candidate (for a paper arm only)

- Chart: **10-minute** candles only (one book, one position). Levels as the baseline: Liquidity Swings lookback 20
  (full range), Liquidity Pools 2 contacts / 5 bars apart / 10 confirmation bars; source "both" (a pool broken by a
  close where an active swing zone of the same side overlaps).
- Entry: next minute's open after the breaking 10-min close, **only from 10:15 to 14:30**, and only if the day's
  first-hour range (09:15-10:14 high minus low) is **at least the median of the previous 20 days' first-hour ranges**.
  Skip the day if the option being bought (nearest expiry after today) **expires the next calendar day (DTE <= 1)**.
  BUY the ATM CE on an up-break, ATM PE on a down-break (strike step 50). Lot 75.
- Exits (first of): 15% premium stop; **profit lock - once the premium has traded 30% above the price paid, a stop
  at max(price paid, premium high x 0.85)**; index touches the next active liquidity level in the trade's direction;
  a 10-min close back through the broken level (failed break); a new liquidity level forms on the trade's side;
  15:10.

## Robustness diagnostics (4 extra runs, not used for selection)

The A-chosen filter stack (first-hour filter, profit lock 30%/15%, skip 1-DTE) on other charts:

| chart | Year A net (trades, t) | Year B net (trades, t) |
|---|---|---|
| 10-min (chosen) | +36,979 (55, 1.88) | +6,191 (33, 0.78) |
| 5-min | +27,054 (103, 0.98) | -22,718 (72, -2.36) |
| 15-min | +26,696 (42, 1.18) | +658 (25, 0.07) |
| 30-min | -5,577 (27, -0.36) | +9,470 (19, 1.05) |
| 15+5 together (the arm) | +53,750 (145, 1.51) | -22,059 (97, -1.68) |

Chosen setting's Year B trades: 15 CE +Rs 7,935, 18 PE -Rs 1,744; exits next liquidity 19, failed break 7, premium
stop 4, profit lock 2, 15:10 1.

**Total runs: 47 distinct variants in the search + 1 legacy reference row + 4 diagnostics = 52** (above the ~40 aim
because the greedy combination step adds one run per helpful factor; several of those combinations were no-ops, e.g.
the 10:15 entry window and the VWAP filter add nothing once the first-hour filter already forces entries after 10:15).

### Caveats

- One Apr-to-Apr split, two years; trade counts after filtering are small (33-55 a year). Weekly NIFTY expiries moved
  weekday during the period; "skip 1-DTE" uses the actual expiry dates in the data.
- Fills are minute OHLC: a stop that triggers on a minute low fills at the stop or that minute's close if lower; the
  profit-lock high is updated after the stop test inside each minute (conservative ordering).
- The EMA filter uses a 5-minute 50 EMA versus its value 30 minutes earlier; a 9 EMA slope was tried first and
  filtered nothing (a breakout close almost always lifts it), so it was replaced.
- Code: research/liquidity_nifty_plus.py (a copy of liquidity_break.simulate extended; the original files are untouched).

## 1. Baseline reproduced

### Year A

| variant | trades | win | index pts/trade | Rs/trade | t | net Rs / lot / yr | green months | max DD |
|---|---|---|---|---|---|---|---|---|
| baseline | 488 (2.0/day) | 38% | +0.2 | -135 | -1.24 | **-66,085** | 2/13 | -98,865 |
| ps=None | 488 (2.0/day) | 41% | +0.2 | -110 | -0.95 | **-53,761** | 4/13 | -100,785 |

### Year B

| variant | trades | win | index pts/trade | Rs/trade | t | net Rs / lot / yr | green months | max DD |
|---|---|---|---|---|---|---|---|---|
| baseline | 469 (1.9/day) | 41% | +1.1 | -60 | -0.81 | **-28,299** | 3/13 | -54,515 |
| ps=None | 469 (1.9/day) | 43% | +1.4 | -53 | -0.65 | **-25,023** | 5/13 | -48,878 |

- Year A baseline by book: 5-min 365 trades Rs -41,662 (+0.2 pts); 15-min 123 trades Rs -24,423 (+0.4 pts); exits next liquidity 48%, failed break 25%, premium stop 22%, 15:10 2%, new liquidity 2%
- Year B baseline by book: 5-min 351 trades Rs -23,360 (+1.4 pts); 15-min 118 trades Rs -4,938 (+0.4 pts); exits next liquidity 53%, failed break 26%, premium stop 16%, new liquidity 3%, 15:10 3%

## 2. Single-factor changes, both years

Each row changes one thing from the baseline. Shown for transparency - picking the best row of the Year B column here would be in-sample; the out-of-sample test is section 3.

| variant | A trades | A Rs/trade | A t | A net | B trades | B Rs/trade | B t | B net |
|---|---|---|---|---|---|---|---|---|
| baseline | 488 | -135 | -1.24 | -66,085 | 469 | -60 | -0.81 | -28,299 |
| tfs=(15,) | 123 | -199 | -0.80 | -24,423 | 118 | -42 | -0.18 | -4,938 |
| tfs=(5,) | 365 | -114 | -0.95 | -41,662 | 351 | -67 | -1.12 | -23,360 |
| tfs=(10,) | 191 | -61 | -0.41 | -11,681 | 144 | -104 | -0.81 | -14,921 |
| tfs=(30,) | 54 | -555 | -1.37 | -29,954 | 50 | +134 | 0.46 | +6,718 |
| tfs=(15, 30) | 177 | -307 | -1.45 | -54,378 | 168 | +11 | 0.06 | +1,780 |
| tfs=(10, 30) | 245 | -170 | -1.15 | -41,635 | 194 | -42 | -0.35 | -8,203 |
| tfs=(3, 15) | 642 | -126 | -1.91 | -81,051 | 628 | -80 | -1.40 | -50,034 |
| sw=10 | 736 | -134 | -1.74 | -98,455 | 706 | -75 | -1.32 | -52,916 |
| sw=15 | 592 | -141 | -1.52 | -83,343 | 559 | -81 | -1.24 | -45,486 |
| sw=30 | 355 | -101 | -0.80 | -36,004 | 349 | -96 | -1.39 | -33,569 |
| pc=5 | 529 | -121 | -1.19 | -64,007 | 513 | -79 | -1.15 | -40,675 |
| pc=15 | 463 | -143 | -1.27 | -66,327 | 451 | -72 | -0.96 | -32,598 |
| ps=None | 488 | -110 | -0.95 | -53,761 | 469 | -53 | -0.65 | -25,023 |
| ps=0.1 | 488 | -101 | -1.00 | -49,524 | 469 | -58 | -0.83 | -27,208 |
| ps=0.2 | 488 | -153 | -1.37 | -74,907 | 469 | -61 | -0.78 | -28,752 |
| ps=0.25 | 488 | -143 | -1.24 | -69,932 | 469 | -73 | -0.91 | -34,365 |
| stop=False | 488 | -113 | -1.01 | -55,228 | 469 | -46 | -0.56 | -21,549 |
| win=(5, 225) | 368 | -151 | -1.08 | -55,614 | 352 | -57 | -0.62 | -20,019 |
| win=(60, 315) | 334 | -152 | -1.27 | -50,891 | 272 | -146 | -2.19 | -39,621 |
| trend=vwap | 466 | -104 | -0.92 | -48,689 | 423 | -48 | -0.61 | -20,378 |
| trend=ema | 465 | -180 | -1.82 | -83,468 | 447 | -56 | -0.73 | -25,229 |
| vol=True | 171 | -2 | -0.01 | -302 | 117 | -245 | -2.16 | -28,608 |
| itm=1 | 488 | -149 | -1.31 | -72,805 | 469 | -62 | -0.78 | -29,259 |
| lock=(0.2, None) | 488 | -64 | -0.62 | -31,113 | 469 | -46 | -0.63 | -21,493 |
| lock=(0.3, 0.15) | 488 | -59 | -0.64 | -28,917 | 469 | -34 | -0.57 | -16,029 |
| skip=expday | 404 | -132 | -1.03 | -53,226 | 363 | -97 | -1.12 | -35,261 |
| skip=dte1 | 402 | -99 | -0.75 | -39,628 | 379 | -48 | -0.55 | -18,179 |
| maxday=1 | 264 | -94 | -0.59 | -24,736 | 273 | -59 | -0.52 | -16,170 |
| maxday=2 | 399 | -111 | -0.96 | -44,424 | 401 | -51 | -0.61 | -20,508 |

## 3a. Chosen on Year A, tested on Year B

Factors that beat the baseline on Year A (best level each), combined greedily in this order: vol, tfs, maxday, lock, sw, skip, trend, ps, win, stop, pc.

Combinations tried (selection year / held-out year):

| combination | sel net | sel t | held-out net | held-out t |
|---|---|---|---|---|
| vol=True | -302 | -0.01 | -28,608 | -2.16 |
| tfs=(10,), vol=True | +17,917 | 0.71 | +5,583 | 0.70 |
| tfs=(10,), vol=True, maxday=1 | +16,009 | 1.27 | +10,381 | 1.47 |
| tfs=(10,), vol=True, lock=(0.3, 0.15) | +36,228 | 1.76 | +11,522 | 1.30 |
| tfs=(10,), sw=30, vol=True, lock=(0.3, 0.15) | +31,786 | 1.61 | +5,927 | 0.71 |
| tfs=(10,), vol=True, lock=(0.3, 0.15), skip=dte1 | +36,979 | 1.88 | +6,191 | 0.78 |
| tfs=(10,), trend=vwap, vol=True, lock=(0.3, 0.15), skip=dte1 | +36,979 | 1.88 | +6,191 | 0.78 |
| tfs=(10,), ps=0.1, vol=True, lock=(0.3, 0.15), skip=dte1 | +16,952 | 0.85 | +3,255 | 0.45 |
| tfs=(10,), win=(60, 315), vol=True, lock=(0.3, 0.15), skip=dte1 | +36,979 | 1.88 | +6,191 | 0.78 |
| tfs=(10,), stop=False, vol=True, lock=(0.3, 0.15), skip=dte1 | +30,053 | 1.44 | +8,151 | 1.00 |
| tfs=(10,), pc=5, vol=True, lock=(0.3, 0.15), skip=dte1 | +31,234 | 1.57 | +1,745 | 0.22 |

**Best on Year A: `tfs=(10,), vol=True, lock=(0.3, 0.15), skip=dte1`**

| variant | trades | win | index pts/trade | Rs/trade | t | net Rs / lot / yr | green months | max DD |
|---|---|---|---|---|---|---|---|---|
| Year A (selection) - chosen | 55 (0.2/day) | 55% | +17.8 | +672 | 1.88 | **+36,979** | 8/12 | -6,920 |
| Year A (selection) - baseline | 488 (2.0/day) | 38% | +0.2 | -135 | -1.24 | **-66,085** | 2/13 | -98,865 |
| Year B (held out) - chosen | 33 (0.1/day) | 48% | +12.2 | +188 | 0.78 | **+6,191** | 7/12 | -4,063 |
| Year B (held out) - baseline | 469 (1.9/day) | 41% | +1.1 | -60 | -0.81 | **-28,299** | 3/13 | -54,515 |

Positive in both years: True; beats baseline in held-out year: True -> **RECOMMENDABLE**

## 3b. Chosen on Year B, tested on Year A

Factors that beat the baseline on Year B (best level each), combined greedily in this order: tfs, lock, maxday, skip, win, trend, stop, ps.

Combinations tried (selection year / held-out year):

| combination | sel net | sel t | held-out net | held-out t |
|---|---|---|---|---|
| tfs=(30,) | +6,718 | 0.46 | -29,954 | -1.37 |
| tfs=(30,), lock=(0.3, 0.15) | +10,710 | 0.79 | -12,288 | -0.72 |
| tfs=(30,), lock=(0.3, 0.15), maxday=1 | +5,609 | 0.45 | -17,581 | -1.18 |
| tfs=(30,), lock=(0.3, 0.15), skip=dte1 | +12,496 | 1.03 | -9,138 | -0.54 |
| tfs=(30,), win=(5, 225), lock=(0.3, 0.15), skip=dte1 | +8,026 | 0.80 | -7,558 | -0.47 |
| tfs=(30,), trend=vwap, lock=(0.3, 0.15), skip=dte1 | +14,079 | 1.26 | -7,194 | -0.43 |
| tfs=(30,), stop=False, trend=vwap, lock=(0.3, 0.15), skip=dte1 | +18,534 | 1.56 | -8,106 | -0.48 |
| tfs=(30,), ps=None, stop=False, trend=vwap, lock=(0.3, 0.15), skip=dte1 | +4,284 | 0.24 | +4,128 | 0.17 |

**Best on Year B: `tfs=(30,), stop=False, trend=vwap, lock=(0.3, 0.15), skip=dte1`**

| variant | trades | win | index pts/trade | Rs/trade | t | net Rs / lot / yr | green months | max DD |
|---|---|---|---|---|---|---|---|---|
| Year B (selection) - chosen | 39 (0.2/day) | 64% | +12.9 | +475 | 1.56 | **+18,534** | 8/12 | -4,050 |
| Year B (selection) - baseline | 469 (1.9/day) | 41% | +1.1 | -60 | -0.81 | **-28,299** | 3/13 | -54,515 |
| Year A (held out) - chosen | 47 (0.2/day) | 49% | +4.4 | -172 | -0.48 | **-8,106** | 5/13 | -11,813 |
| Year A (held out) - baseline | 488 (2.0/day) | 38% | +0.2 | -135 | -1.24 | **-66,085** | 2/13 | -98,865 |

Positive in both years: False; beats baseline in held-out year: True -> **not recommended**

