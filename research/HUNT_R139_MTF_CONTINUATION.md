# R139: the multi-timeframe stack (4H trend -> 1H level -> 15m break -> 5m FVG/OB -> 3m entry), continuation version. 0 of 7 pass

31 Oct 2026. Research only (no app change). Boss shared a "Stop using the wrong timeframe" chart:
- 4 hour: the major trend;
- 1 hour: key levels;
- 15 min: the structure shift;
- 5 min: an order block or FVG;
- 3 min: the precise entry.

Then Boss: *"Build R139 with continuation version and test it"*. The chart's 15-minute step says "trend reversal". On
Indian indices breaks continue more often than they reverse (R135), so here it is a **break of the 1-hour level with the
4-hour trend**.

**Signal source: the index spot on every timeframe.** The trade buys the ATM option (a call with an up trend, a put
with a down trend).

## The answer: it does not pay, and the small timeframes make it worse

**The rule** (pre-registered, `research/hunt/r139_mtf_continuation/PREREG.md`):
- **4 hour:** the trend is up when the last completed 4-hour candle closed above its 20-candle EMA and the EMA is
  rising.
- **1 hour:** the level is an hourly swing high from the last 5 sessions that no wick has gone above since.
- **15 min:** a 15-minute candle closes above that level, with the trend.
- **5 min:** price pulls back into that break's FVG (or, if there is none, its order block) within an hour.
- **3 min:** a 3-minute candle closes above the previous one's high. Buy at the next minute.
- **Stop:** just below the pullback's low.
- **Exit:** 2R target (C1), out by 15:20.

| variant | design trades | won (vs 65%) | avg win / loss | net per trade (2x slip) | total, 1 lot | drawdown / worst month | p / q | holdout (read once) |
|---|---|---|---|---|---|---|---|---|
| **C1** full stack, 2R (primary) | 358 | 37% | +Rs 2,229 / -Rs 1,475 | **-Rs 88** (-Rs 110) | -Rs 31,611 | -Rs 64,965 / -Rs 16,828 | 0.77 / 0.80 | **27 trades, 15% won, -Rs 917 each** |
| **C2** full stack, 3R ("high R:R") | 355 | 34% | +Rs 2,869 / -Rs 1,462 | +Rs 2 (-Rs 20) | +Rs 837 | -Rs 45,970 / -Rs 16,828 | 0.58 / 0.80 | 27, 11%, -Rs 944 |
| **C3** full stack, breakeven + trail | 358 | 37% | +Rs 2,009 / -Rs 1,298 | -Rs 69 (-Rs 95) | -Rs 24,881 | -Rs 66,045 / -Rs 17,367 | 0.80 / 0.80 | 27, 22%, -Rs 714 |
| **C4** no 4-hour trend | 608 | 36% | +Rs 2,479 / -Rs 1,546 | -Rs 83 (-Rs 106) | -Rs 50,511 | -Rs 81,366 / -Rs 21,594 | 0.54 / 0.80 | 68, 28%, -Rs 384 |
| **C5** no 5m / 3m steps (enter on the 15m break) | 1,068 | 40% | +Rs 3,031 / -Rs 2,023 | **+Rs 17** (-Rs 5) | +Rs 17,775 | -Rs 1,08,313 / -Rs 40,111 | **0.02** / 0.14 | **131, 39%, -Rs 294** |
| **C6** full stack + R123 network | 31 | 39% | +Rs 2,430 / -Rs 1,522 | +Rs 8 (-Rs 12) | +Rs 233 | -Rs 13,169 / -Rs 6,939 | 0.42 / 0.80 | 0 trades |
| **C7** 4-hour trend as swing structure | 202 | 43% | +Rs 2,057 / -Rs 1,539 | -Rs 8 (-Rs 30) | -Rs 1,633 | -Rs 36,278 / -Rs 15,190 | 0.53 / 0.80 | 11, 27%, -Rs 649 |

Design: 1 Aug 2021 - 15 Mar 2026. Holdout: 1 Apr - 19 Oct 2026. p is against 100 random-entry twins per trade with the
same stop, target and exit; q is BH over the 7.

**Why each fails:**
- **C1-C4:** they lose in design, at 1x or 2x slippage, in at least two of the four test years.
- **C5, the nearest miss:**
  - In design it beat random entries (p 0.02), but by only Rs 17 a trade, and it lost at 2x slippage.
  - It broke Boss's account limits (drawdown -Rs 1.08 lakh, worst month -Rs 40k) and lost Rs 82k in 2025.
  - In the holdout it lost Rs 294 a trade on 131 trades.
- **C6:** 31 trades in design and none in the holdout: too rare to judge.
- **C7:** 202 trades (300 needed), a small loss.
- **None reaches Boss's 65% win rate:** 34-43% in design, 11-39% in the holdout. The 65% rule is reported, not a gate
  here.

## What each timeframe layer did (the main lesson)

| layer | test | result |
|---|---|---|
| **4 hour trend** | C1 (with) vs C4 (without) | **adds nothing**: -Rs 88 vs -Rs 83 a trade. It only cuts the trade count (608 -> 358). The swing-structure trend (C7) is no better. |
| **1 hour level + 15 min break** | C5 | **the only part with any edge**: +9.5 index points a trade before costs, +Rs 17 after, better than random entries in design. The edge is too small to survive the option's costs and slippage, and it lost in the holdout. |
| **5 min FVG / order block + 3 min entry** | C1 vs C5 | **makes it worse:** +Rs 17 -> -Rs 88 a trade, and +9.5 -> +2.9 index points. Waiting for the pullback and the "precise" entry keeps the trades where the break failed. The breaks that run away never pull back, so they are missed. |
| **"High R:R" (3R)** | C2 vs C1 | lifts design to breakeven at 1x slippage (+Rs 2), but only 34% won, and the holdout lost Rs 944 a trade |

**The funnel, design, five indices:**
- 1,729 trend-aligned 1-hour breaks;
- 1,636 had an FVG or order block (95%);
- 952 pulled back into it (55%);
- 516 gave a 3-minute entry (30%);
- 358 trades after one-at-a-time and the option rules.

**By index and year (C5, design):**
- By index: MIDCPNIFTY +Rs 118 a trade (149 trades), FINNIFTY +Rs 77 (200), BANKNIFTY +Rs 39 (286), NIFTY -Rs 23
  (287), SENSEX -Rs 135 (146).
- By year: 2025 -Rs 299 a trade (275 trades). It is not steady.

## How this fits what we knew

- **It matches R135 and R137.** On Indian indices, a break with the trend continues slightly more often than chance.
  That is the +9.5 points of C5. But it is smaller than the option's round-trip cost on an intraday hold (R89: 1-4
  points, more with slippage on stop exits).
- **It matches R89 / R90.** Higher-timeframe trend filters cut trades without improving them (R89's daily + 15m filter,
  R90's 15m + 75m / day structure).
- **It matches R131.** Order-block and FVG entries lose: S8 -Rs 97 and S6 -Rs 81 a trade on thousands of trades.
- **New here:** the full top-down stack together, and the finding that its lowest steps (5m and 3m) actively hurt.
  The chart's "precise entry with a tight stop" is the part that loses most.

## What is proposed

- **Nothing.** No arm, no paper change. CLAUDE.md: failed ideas stay notes.
- **For Boss's chart reading:**
  - the 1-hour level and the 15-minute close through it with the trend are the meaningful parts;
  - the 4-hour trend is background only;
  - waiting for a 5-minute FVG and a 3-minute trigger makes results worse on these indices.

## How it was done

- **Pre-registration:** `research/hunt/r139_mtf_continuation/PREREG.md`, commit bed6907, before any number. Design
  results committed before the one holdout read (lock file).
- **Data:** the Drive lake (index minutes; weekly and monthly option minutes), five indices.
- **Options:** the ATM option of the nearest expiry that does not expire that day; exit by 15:20. Days when the weekly
  and monthly expire together are left out, as in R131-R138.
- **Costs:** R131's dated charges and liquidity-aware slippage, also 2x slippage.
- **Code and tests:** `r139.py`. `test_r139.py` has 10 tests: each layer on hand-built minutes, a cancelled setup, the
  4-hour trend, the bearish mirror and the look-ahead guard.
- **Results:** `results/design.json`, `results/holdout.json`. Trades in `$R139_OUT`, not in git.
- **Entries:** `research/experiments/entries/r139-c1.yaml` … `r139-c7.yaml`, all rejected.
