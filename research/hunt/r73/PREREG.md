# R73 pre-registration: a trend filter, or a wait of 1-minute candles, before Donchian buys the option

> **Note, 16 Oct 2026 (dated, added when renumbered):** Renumbered from R72 (that number went to the cross-index
> study). The first run of this exact plan computed the final period once, but its session was stopped and its results
> were lost before anyone recorded them; this rerun uses the same pre-registered plan, and its final read is therefore
> the second computation of the same frozen rules. (Original: commit ad55e171, `research/hunt/r72/PREREG.md` on branch
> `claude/r72-donchian-trend-wait`. Only "R72"/"r72" was changed to "R73"/"r73"; the random seed 72 is a frozen plan
> parameter and is kept as written.)

Written 16 Oct 2026, **before any R73 result was computed** (only the R51 clean stores were built). Research only (no
`android/**`, no `.github/**`); whether anything changes on paper is Boss's call.

Boss, 16 Oct: *"Can we add trend find for Donchian, or wait for 5 candles before buying any options, which will help us
get direction; it could help us with less loss."* Asked which candles: *"1 min 5 candles"*.

Context: the R51 Donchian 20 paper arm is at 9 closed trades, -Rs 5,073 (it is judged at 40 trades). Those trades are
after 13 Oct and not in the data; they are not part of this test.

## 1. Baseline: Donchian exactly as on paper today

- **Signal (source: the INDEX)**: the index's 5-minute bars (09:15-anchored, continuous across sessions); a close above
  the highest high of the previous 20 bars buys the call, a close below the lowest low of the previous 20 bars buys the
  put (`hunt/r44/ind.py donchian`, `hunt/r51/fam_r44.py signal_list`).
- **Option**: 1-ITM of the nearest weekly expiry, strike from the index at the last minute before the entry
  (`engine.strike_for(..., er - 1, 1)`); entry at the next minute's open (fills within 2 minutes, else none);
  entries 09:20 - 15:10; one position a side per index, at most 2 entries a side a day; never on the index's expiry day;
  NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX; 1 lot (`hunt/r64/r64arms.py donchian_trades`).
- **Exits (two sets, every variant runs under both)**:
  - **P = paper today**: stop -15% on the premium, the A4 30/60/100 ladder (breakeven + charges at +30%, +30% at +60%,
    +60% at +100%), **L25** (stop to +5% once +25%), no fixed target, out 30 minutes after the fill, square-off 15:15
    (`hunt/r66/r66cfg.py exit_spec(OWN["Donchian"], "L25")`). This is the primary set.
  - **Q = without L25**: the same with A4 only (`exit_spec(..., "A4")`).
- Engine, charges and slippage: R64's engine on R51's Amendment A clean stores (dated charges, R17 liquidity fills,
  next-minute fills, stops on the minute low with gap fills at the worse open, locks from the next minute).

## 2. Variants (18 entry rules x 2 exit sets = 36 tests)

Every filter reads only the index, and only candles that have **closed** by the moment of entry.

### A. Trend filter (take only breakouts in the trend's direction), decided at the signal bar's close

| id | rule (call side; the put side is the mirror) |
|---|---|
| T1 | 5-min close above its EMA 20 (5-min closes) |
| T2 | 5-min close above its EMA 50 |
| T3 | 15-min EMA 20 rising: the last fully closed 15-minute bar's EMA 20 above the one before (bars 09:15-anchored) |
| T4 | 1-hour EMA 20 rising: the same on the last fully closed 60-minute bar (09:15-anchored) |
| T5 | ADX 14 (5-min, Wilder) above 20 (strength only; the breakout gives the direction) |
| T6 | ADX 14 above 25 |
| T7 | Supertrend (10, 3) on 5-min bars is up |
| T8 | close above the day's average typical price so far ((H+L+C)/3 of the 1-minute candles from 09:15 to the signal bar's close). The index has no volume, so this is a time-weighted stand-in for VWAP; labelled so. |

### B. Confirmation wait (Boss's idea), on 1-minute index candles

After the 5-minute signal bar closes, wait N one-minute candles (N = **5**, Boss's number; 2 and 3 for comparison), then
enter at the open of the next minute (row 5(b+1)+N instead of 5(b+1)), with the strike re-chosen at the minute before,
as the app would. Level L = the channel edge that was broken (call: the highest high of the previous 20 bars).

| id | enter only if (call side; the put side is the mirror) |
|---|---|
| W{N}H | all N one-minute closes stay above L (the break holds) |
| W{N}D | the N-th one-minute close is above the signal bar's close (price kept going) |
| W{N}HD | both |

N in {2, 3, 5}: 9 variants. A missing candle in the wait, or an entry after 15:10, means no trade.

### C. A + B combined

C = the best T and the best W, both chosen on **design data only** (highest design net under exits P; tie: smaller
design max drawdown), taken together (the trend filter at the signal bar, then the wait). Walk-forward for C: in each
walk-forward quarter, the best T and best W are chosen on the design days before that quarter and applied to it. The
final uses the choice made on the whole design period. One rule (C) per exit set.

## 3. Periods, statistics, gates

- Periods as R64 - R66: design 20 Nov 2024 - 31 Mar 2026; walk-forward quarters Apr 2025 - Mar 2026; **final 1 Apr -
  13 Oct 2026, read once** by `r73study.py final` (guarded by `results/FINAL_READ`). Honest note: the final period has
  already been read for the baseline by R51, R52, R57, R64 - R66 and R70; it has never been read for these filters.
- **Random-entry twins** (R51's, 500, seed 72): same days, sides and counts, random entry minutes 09:20 - 15:10, same
  strike rule and exits. **Benjamini-Hochberg across all 36 tests.** Gates as R51 (never relaxed): final net > 0,
  p < 0.05, q <= 0.10, >= 40 trades, top 5 < net, design and walk-forward net > 0.
- **Random-skip twins** (Boss's real question: does the filter pick the losers, or just trade less?): if the variant
  has k fewer trades than the baseline (same exits), each twin drops k baseline trades at random (500, seed 72);
  p = share of twins whose net is >= the variant's. Read on the final; BH across the 36 tests (skip q). Design skip p
  reported for information.
- Sample size (trades, days) with every number.

## 4. Decision

A variant is **recommended** for Donchian's paper arm only if (1) it passes every R51 gate, (2) its skip-twin q <= 0.10,
and (3) its net is >= the baseline's (same exits) in design, walk-forward **and** final. If several pass, the one with
the highest design net. Otherwise the baseline stays.

Information only (not a recommendation): a variant whose final max drawdown and worst day are both better than the
baseline's at a lower net is listed as a "loss-reduction trade-off", with its cost.

## 5. Reported per variant vs baseline

P&L, trades, win rate, avg win / avg loss, worst day, max drawdown, % green days; **losers avoided** (baseline trades
the variant skipped that lost: count and Rs) and **big winners missed** (baseline trades it skipped that closed at
+30% or more on the premium: count and Rs); trades it took that the baseline did not (a freed slot); for the waits, the
**cost of waiting** on signals taken by both (index points moved in the breakout direction between the two entries, and
the option fill paid, Rs per lot and %).

Every variant is logged in `research/experiments/entries/` (failures included). Write-up:
`research/HUNT_R73_DONCHIAN_TREND_WAIT.md`.
