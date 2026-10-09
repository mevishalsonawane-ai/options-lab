# HUNT R5: Boss's uploaded guide, "Best Gann Sq9 + Sq144 + Fib + Harmonic Strategy (Magic#)", tested exactly as written

Written 9 Oct 2026. The source is `Gann_Fib_Harmonic_MagicNumber_Complete_Guide.md` (section 6 has the Pine script and
section 8 the recommended settings).

The frame is the same as R4:
- option BUYING only, Rs 1,00,000, FIXED 1 lot (NIFTY 65, BANKNIFTY 30). The script's "8% of equity" sizing is replaced
  by 1 lot, with no compounding;
- 1-ITM strike of the nearest expiry;
- exits on the option's 1-minute wicks;
- costs are the app's charges plus the h24 half-spread of 0.16% a side, with no stacked ±5 bps (X3 #2);
- random-entry twins, BH correction and a White Reality Check;
- the locked year (1 Oct 2025 - 6 Oct 2026), opened once after the picks were frozen.

Files:
- Plan, written before any result: `research/hunt/r5/PREREG.md`.
- Code: `research/hunt/r5/`:
  - `pine5.py`: a bar-by-bar replica of the script;
  - `design.py`: design and the one-time holdout;
  - `tables.py`.
- Results: `research/hunt/r5/results/` (`design_*.csv`, `holdout_*.csv`, `frozen.json`, `holdout_opened.txt`).
- Trade files and logs: `scratchpad/hunt/r5/` (4 MB).

## Verdict (plain English)

**No. For an option buyer with Rs 1 lakh and 1 lot, this strategy does not make money after costs. Mostly it does not
trade at all, and when it does trade, the script itself exits almost every trade on the bar it entered.**

1. **As written (Square-of-144 time filter ON), it almost never trades.**
   - **0 trades in 5 years on 15-min and 30-min charts**, NIFTY and BANKNIFTY.
   - With the default settings on 1-hour charts: 1 trade on NIFTY and 2 on BANKNIFTY (Aug 2020 - Sep 2025).
   - None of them was an intraday option trade that made money.
   - The reason is a code bug, explained in (e) below.
2. **With the time filter OFF (the "pragmatic" version), it still trades about once or twice a year**, and the trades
   are broken by construction:
   - The design period has **77 distinct trades** over every timeframe and setting.
   - **66 of the 77 (86%) are stopped out at the close of the entry bar itself.**
   - 69 of the 77 are Butterflies or Crabs. In those patterns the point D lies beyond X, so the script's stop
     ("beyond X") sits on the wrong side of the entry price.
3. **As option buys, the pooled design results are:**
   - script exits (NAT): **-Rs 87 a trade** (61 distinct trades, 33% winners);
   - the -25% / +50% premium exit: +Rs 253 a trade on 58 trades. That figure comes entirely from 3 lucky trades
     (median -Rs 175; **-Rs 59 a trade without the top 3**).
   - The best variant made **+Rs 8 a day**. No variant reached 30 trades (the most was 16).
   - Smallest BH q = **1.00**; best White Reality Check p = **0.67**.
4. **Locked year (1 Oct 2025 - 6 Oct 2026, opened once): zero option trades** in every variant.
   - Only one index signal appeared in the whole year: BANKNIFTY 30m, pivot 8, at 15:14. It was stopped out at once
     (-54 points), and 15:14 is too late for an intraday option buy anyway.
5. **Rs 5,000 a day is not reachable.** At about one trade a year per chart, nothing is.
6. **The guide's description does not match its code** (details below):
   - Square-of-9 and Fibonacci are only drawn on the chart. They never decide a trade.
   - The "Magic Number" is a label.
   - The Square-of-144 "time window" counts from the newest pivot, which makes it nearly impossible to satisfy.
   - The pivot list does not alternate highs and lows, so most "harmonic patterns" it finds are not real XABCD shapes.

**For the app:** the script **does not compile in IraAlgo's Pine engine**. It fails with `Unknown function
'array.new_line'` at guide lines 209 and 243. With those visual-only blocks removed it compiles and runs, and **the
app engine reproduces my Python replica's trade list bar for bar** (same entry bars, same counts). So the
replication is confirmed by an independent implementation. Recommendation: **do not run it, even on paper.**

## Code review: what the script actually does vs what the guide says

| guide claims | what the code does |
|---|---|
| "Buy: harmonic PRZ **+ price near a Gann Square-of-9 level or a key Fibonacci level** + preferably a Sq144 time window" | **(a) Square-of-9 and Fibonacci are never used in `buyCond`/`sellCond`.** They only create `line.new` drawings, and only when `showVisuals` is on. `useSq9` and `useFib` switch drawings on and off, nothing else; with `showVisuals = false` they do nothing at all. The only inputs to a trade are the harmonic ratios, the Sq144 window and the cooldown. The alert text "Harmonic + Gann Sq9/Sq144 + Fib confluence" is therefore false. The "Fib" lines are drawn from the last two pivots, i.e. the C->D leg that ends at D itself. The 1.272 / 1.618 lines are always drawn below the low (`hi - rng*lvl`), whatever the direction. |
| "Magic Number is used for identification" | **(b) Correct, and it is all it does.** It is text in order IDs, comments, labels and alerts, and it never affects a trade. `{{magicNumber}}` in `alertcondition` is not a valid TradingView placeholder, so it is printed literally. |
| "5-point XABCD patterns" | **(c) The pivot list does not alternate.** `f_add` pushes any confirmed pivot high or low that is more than minSwing x ATR away from the **last pushed price**, whatever its type. Sequences like H-H-L-H-L are common, and **73% of the trades (56 of 77) came from non-alternating X,A,B,C,D.** All ratios use `abs()`, so direction is never checked. A "bullish Gartley" can have D below X, and "isBull" only means "the last pivot was a low". When a high and a low confirm on the same bar, both are pushed (high first). |
| PRZ entry at D | **(d) Entries are late, and the block keeps firing.** `ta.pivothigh/low(…,10,10)` confirms a pivot 10 bars after it happens: 2.5 hours late on 15m, about 1.5 sessions late on 1h. The entry is a market order at the **close of the confirming bar** (`process_orders_on_close = true`). This is not repaint, but the median entry is 10 bars after D. The harmonic block runs on **every** bar while those 5 pivots stay the newest, so after the cooldown (16 bars) **the same stale pattern signals again**: 1.4 signals per pattern on average, at lag 26, 42, … bars. A same-direction re-signal while in a position does not add (pyramiding 0) but **moves the stop and target** (`strategy.exit` is re-issued). |
| "Square of 144 time window" filter | **(e) It counts bars since the LAST list pivot, which is D itself.** On the bar where D is added, barsSince = pivotRight = 10. The window needs \|barsSince - {36,48,72,96,108,144}\| <= 3, i.e. 33-39 bars at the earliest. **So the filter is NEVER true when a pattern completes.** It can only fire 33+ bars after D (8 hours later on 15m, about 5 sessions later on 1h), and only if no new pivot was pushed in between. As written, the strategy almost never trades: 0 trades on 15m/30m in 5 years. Every as-written trade entered 33-41 bars after D. |
| SL beyond X + 0.25 ATR; TP1 1.8R, TP2 3.0R | **(f)** The stop is X -/+ 0.25 ATR(now). The target is TP1 = **D** ± 1.8 x \|D - SL\|, measured from D, not from the late entry, so the real R:R is not 1.8. **TP2 is computed but never used.** For **Butterfly and Crab (D beyond X) the stop is on the wrong side of the entry**, so the trade is closed at once. 66 of 77 trades were: in my replica at the entry bar's close (process_orders_on_close), in the app engine at the next bar's open. Cooldown = `bar_index - lastSigBar > 15`. The script holds positions **overnight** (13% of trades) and has no session filter: it can enter at the 15:15 stub bar. Sizing is 8% of equity (replaced here by 1 lot). The 0.05% commission and 1-tick slippage are kept in (A). |

Other details:
- Patterns as coded:
  - Gartley: B 0.618 ± 0.045, D 0.786 ± 0.045.
  - Bat: B 0.382-0.50, D 0.886 ± 0.045.
  - Butterfly: B 0.786 ± 0.045, D 1.27-1.618.
  - Crab: B 0.382-0.618, D 1.618 ± 0.06.
  - All four need C/AB 0.382-0.886.
  - In 5 years of NIFTY 15m pivots (1,800 pushed), only **5** five-pivot sets matched any pattern, all Butterflies.
    Gartley and Bat almost never match.
- Pattern mix of the 77 design trades: Butterfly 42, Crab 27, Gartley 7, Bat 1. Exits: 66 instant stops, 9 normal
  stops, **2 targets**.

## How it was tested

- **Replica (`pine5.py`):**
  - TradingView NSE bars anchored at 09:15 (15m / 30m / 1h, with the 15:15 stub bar), with bar_index continuous
    across days;
  - Wilder ATR(14);
  - `ta.pivothigh/low`: strict on the left, >= on the right (checked against a naive loop);
  - `f_add` exactly as coded, list of 15;
  - the Sq144 window, ratios, tolerance 0.045 and cooldown;
  - the broker emulator: market fill at close; stop and limit from the next bar with gap-at-open fills and the
    open -> nearer extreme -> far extreme path; re-issued exits; reversals.
- **Cross-check:** the app's own Kotlin Pine engine (`android/engine`, run through a temporary JVM unit test that has
  since been deleted) ran the same script on the same bars. It gives identical trade lists (counts 0/8, 1/4, 0/4, 2/3
  for NIFTY/BANKNIFTY 15m/1h, Sq144 on/off).
- **Configurations (pre-registered):**
  - AS_WRITTEN: Sq144 on;
  - PRAG: Sq144 off;
  - CONFL: PRAG plus D within 0.1% of a Square-of-9 level (√C ± {0.25, 0.5, 1, 1.5, 2})² of the previous pivot, or of a
    0.382-0.786 Fibonacci retracement of the B->C leg. This is what the guide claims and the code doesn't do.
- **Settings:**
  - 15m/30m: pivot {8, 10} x minSwing {0.9, 1.1} x cooldown {12, 15}, plus the default (10, 1.0, 15);
  - 1h: pivot {10, 12} x {1.0, 1.3} x {15, 20}, which already contains the default.
  - That is 156 strategy runs and 312 option variants. 142 of the option variants had at least one design trade.
- **(A) Index points as the script trades** (overnight allowed, 0.05% a side + 1 tick).
- **(B) Option buys:**
  - entries the script makes at bar closes between 09:20 and 14:45; long -> 1-ITM CE, short -> 1-ITM PE, bought at the
    next minute's open.
  - NAT: out when the index 1-minute high/low touches the script's SL/TP1, or when the script reverses, at the next
    minute's open; else 15:10.
  - OPT: -25% / +50% on the option wicks.
  - Overnight holding is impossible for these intraday buys, and entries after 14:45 are dropped.
- **Design** = before 1 Oct 2025: NIFTY 1,276 sessions, BANKNIFTY 996. **Holdout** frozen at 12:55 9 Oct 2026, with 40
  confirmatory picks (18 primary + 22 design-best), then opened once.

## Results: design (Aug 2020 / Aug 2021 - Sep 2025)

Primary setting = the script default (pivot 10, minSwing 1.0, cooldown 15), inside the guide's range for all three
timeframes. p_rand and BH q are 1.00 by the pre-registered rule: under 30 trades gives p = 1, and no variant had more
than 16 trades.

| index | TF | config | (A) trades | (A) win% | (A) pts/trade net | (A) worst DD pts | (A) instant stop-outs | (B) NAT trades | NAT win% | NAT Rs/trade | NAT Rs/day | NAT worst DD Rs | NAT p_rand | NAT BH q | (B) OPT trades | OPT Rs/trade | OPT Rs/day |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| NIFTY | 15m | AS_WRITTEN | 0 | - | - | - | - | 0 | - | - | 0 | - | - | - | 0 | - | 0 |
| NIFTY | 15m | PRAG | 8 | 0 | -23.9 | 191 | 100% | 7 | 43 | -28 | -0.2 | 567 | 1.00 | 1.00 | 7 | 557 | 3.1 |
| NIFTY | 15m | CONFL | 6 | 0 | -24.0 | 144 | 100% | 5 | 60 | 74 | 0.3 | 140 | 1.00 | 1.00 | 5 | 119 | 0.5 |
| NIFTY | 30m | AS_WRITTEN | 0 | - | - | - | - | 0 | - | - | 0 | - | - | - | 0 | - | 0 |
| NIFTY | 30m | PRAG | 1 | 0 | -18.7 | 19 | 100% | 1 | 0 | -186 | -0.1 | 186 | 1.00 | 1.00 | 1 | -999 | -0.8 |
| NIFTY | 30m | CONFL | 0 | - | - | - | - | 0 | - | - | 0 | - | - | - | 0 | - | 0 |
| NIFTY | 1h | AS_WRITTEN | 1 | 0 | -113.1 | 113 | 0% | 0 | - | - | 0 | - | - | - | 0 | - | 0 |
| NIFTY | 1h | PRAG | 4 | 0 | -27.1 | 108 | 75% | 3 | 0 | -294 | -0.7 | 881 | 1.00 | 1.00 | 3 | 1,304 | 3.1 |
| NIFTY | 1h | CONFL | 3 | 0 | -28.3 | 85 | 67% | 2 | 0 | -426 | -0.7 | 852 | 1.00 | 1.00 | 2 | 618 | 1.0 |
| BANKNIFTY | 15m | AS_WRITTEN | 0 | - | - | - | - | 0 | - | - | 0 | - | - | - | 0 | - | 0 |
| BANKNIFTY | 15m | PRAG | 4 | 25 | 46.0 | 128 | 75% | 3 | 0 | -231 | -0.7 | 693 | 1.00 | 1.00 | 3 | -1,064 | -3.2 |
| BANKNIFTY | 15m | CONFL | 3 | 33 | 73.4 | 91 | 67% | 3 | 0 | -231 | -0.7 | 693 | 1.00 | 1.00 | 3 | -1,064 | -3.2 |
| BANKNIFTY | 30m | AS_WRITTEN | 0 | - | - | - | - | 0 | - | - | 0 | - | - | - | 0 | - | 0 |
| BANKNIFTY | 30m | PRAG | 0 | - | - | - | - | 0 | - | - | 0 | - | - | - | 0 | - | 0 |
| BANKNIFTY | 30m | CONFL | 0 | - | - | - | - | 0 | - | - | 0 | - | - | - | 0 | - | 0 |
| BANKNIFTY | 1h | AS_WRITTEN | 2 | 0 | -171.6 | 343 | 50% | 2 | 0 | -772 | -1.6 | 1,545 | 1.00 | 1.00 | 2 | -2,356 | -4.7 |
| BANKNIFTY | 1h | PRAG | 3 | 0 | -73.2 | 220 | 67% | 3 | 33 | 714 | 2.2 | 1,463 | 1.00 | 1.00 | 3 | 346 | 1.0 |
| BANKNIFTY | 1h | CONFL | 0 | - | - | - | - | 0 | - | - | 0 | - | - | - | 0 | - | 0 |

All settings (8-9 per row), design:

| index | TF | config | (A) trades, summed over settings | (A) pts/trade | (B) NAT trades | NAT Rs/day min..max | (B) OPT trades | OPT Rs/day min..max | best NAT t | min BH q |
|---|---|---|---|---|---|---|---|---|---|---|
| NIFTY | 15m | AS_WRITTEN | 8 | -11.3 | 6 | -0.2 .. -0.0 | 4 | 0.6 .. 0.6 | - | 1.00 |
| NIFTY | 15m | PRAG | 100 | 10.9 | 87 | -1.6 .. 0.4 | 83 | -2.4 .. 8.4 | 1.66 | 1.00 |
| NIFTY | 15m | CONFL | 50 | -43.5 | 43 | -0.2 .. 0.9 | 41 | -4.8 .. 5.1 | 1.74 | 1.00 |
| NIFTY | 30m | AS_WRITTEN | 0 | - | 0 | - | 0 | - | - | - |
| NIFTY | 30m | PRAG | 17 | -157.6 | 17 | -0.3 .. -0.1 | 17 | -1.0 .. -0.8 | -1.36 | 1.00 |
| NIFTY | 30m | CONFL | 0 | - | 0 | - | 0 | - | - | - |
| NIFTY | 1h | AS_WRITTEN | 8 | -113.1 | 0 | - | 0 | - | - | - |
| NIFTY | 1h | PRAG | 31 | -31.3 | 19 | -1.8 .. 0.4 | 19 | 1.4 .. 3.1 | 1.02 | 1.00 |
| NIFTY | 1h | CONFL | 23 | -33.9 | 15 | -1.8 .. 0.4 | 15 | -0.7 .. 2.6 | 0.16 | 1.00 |
| BANKNIFTY | 15m | AS_WRITTEN | 0 | - | 0 | - | 0 | - | - | - |
| BANKNIFTY | 15m | PRAG | 36 | -14.6 | 22 | -0.7 .. 0.5 | 22 | -3.2 .. 6.9 | 0.82 | 1.00 |
| BANKNIFTY | 15m | CONFL | 21 | 5.3 | 16 | -0.7 .. -0.0 | 16 | -3.2 .. -1.0 | -0.67 | 1.00 |
| BANKNIFTY | 30m | AS_WRITTEN | 0 | - | 0 | - | 0 | - | - | - |
| BANKNIFTY | 30m | PRAG | 8 | -180.1 | 6 | -0.0 .. 0.5 | 6 | -0.2 .. 1.1 | - | 1.00 |
| BANKNIFTY | 30m | CONFL | 0 | - | 0 | - | 0 | - | - | - |
| BANKNIFTY | 1h | AS_WRITTEN | 6 | -130.0 | 6 | -1.6 .. -0.1 | 6 | -4.7 .. -3.7 | - | 1.00 |
| BANKNIFTY | 1h | PRAG | 16 | -54.1 | 12 | -1.7 .. 2.2 | 12 | 1.0 .. 5.8 | 0.91 | 1.00 |
| BANKNIFTY | 1h | CONFL | 0 | - | 0 | - | 0 | - | - | - |

How to read these tables:
- Different settings mostly pick the **same few trades**, so the counts above overlap heavily.
- Distinct trades, pooled over everything:
  - (A) index: 77 trades, -16 points gross and **-44 points net** a trade.
  - (B) NAT: 61 trades, **-Rs 87 a trade**, t -1.0.
  - (B) OPT: 58 trades, +Rs 253 mean, but median -Rs 175. The top 3 trades (+Rs 5,500 to 6,600) carry it, and it is
    **-Rs 59 without them**.
- Rs/day is tiny either way because the strategy trades about once a year per chart.
- Rs 1 lakh check:
  - the premium for 1 lot is at most Rs 18.6k (median Rs 8.4k);
  - the worst single trade was -Rs 4,110;
  - the worst drawdown of any variant was Rs 10.1k (an OPT variant).
  - Affordable, but pointless.

## Results: locked holdout (1 Oct 2025 - 6 Oct 2026, opened once)

- **All 312 option variants, including the 40 frozen confirmatory picks: 0 trades.**
- Index level (A): a single trade in the whole year, in BANKNIFTY 30m with the pivot-8 settings (PRAG and CONFL, 4
  settings). It was a Butterfly-type signal at 15:14, stopped at once: **-54 points**. It is outside the option window.
- Every other index × timeframe × config × setting had 0 trades in the holdout year.
- Nothing can PASS. The pre-registered gate needs design BH q < 0.10 (best: 1.00) AND a positive holdout.

## Notes and limits

- My replica fills the instant stop-outs at the entry bar's close. The app engine fills them at the next bar's open.
  Same trades, a gap-sized difference of -20 to -133 points per instant stop in the app's run. TradingView's exact
  fill for an already-crossed stop under `process_orders_on_close` may follow either convention. It does not change
  anything above.
- The data is Dhan minute data, not TradingView's own feed, but the app engine's identical trade list shows the logic
  is reproduced.
- BANKNIFTY uses a 30-unit lot, as in R4.
- If Boss wants to try this idea seriously, the code would need real fixes. **These are NOT tested here:**
  - alternate the pivots;
  - check the direction of each leg;
  - put the stop beyond D for Butterfly / Crab;
  - start the Sq144 count from the pattern's X or from D's actual bar, not the newest pivot;
  - actually use the Square-of-9 / Fibonacci levels.
  R4 already tested properly built harmonics (Gartley, Bat, Butterfly, Crab and 6 others on 3-60 minute bars with
  correct stops). They failed too: rare trades, no edge over random entries, and holdout picks between -Rs 160 and
  +Rs 59 a day on 4-30 trades. So fixing the code is not expected to help.
- Deviation from PREREG: the plan said 162 runs (9 settings per timeframe). The 1h grid already contains the default
  setting, so there are 8 unique settings on 1h: 156 runs and 312 option variants. This was fixed before anything was
  frozen.
