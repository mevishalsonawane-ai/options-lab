# R131 pre-registration: the 15-minute structure trend on its own, with and without R123's network

Written 29 Oct 2026, before any R131 number was computed. Research only: nothing in the app changes, nothing trades.

Boss: *"Test the 15-minute trend on its own"*, then *"Also try to use neuronetwork from r123 to find if there could be
more enhancement"*.

## 0. Where the idea came from, and what is new

- **R130 Amendment 2:** the 15-minute structure filter was the only change that helped the sweep/FVG setup. But its
  gain matched random entries on the same day and side, which suggests the trend direction itself may carry the edge.
  R131 tests that directly.
- **Earlier trend work:**
  - R90 (Almazov MTF structure + BOS, 0 of 99) targeted the higher-timeframe swing and mostly squared off at 15:15.
  - FINDINGS: Supertrend + EMA on 15-min, 209 trades, lost.
  - R105: Donchian give-back.

  R131 differs: it is a pure stop-and-reverse on 15-minute wick structure, intraday. Its exit is the opposite
  structure break (a trailing level), and there is no far target.
- **R123:** its network did not predict direction better than a coin on its own (holdout log-loss 0.694). Here it is
  used only as a filter on R131's trades.

## 1. The rule (wicks only; signal on the index; buy the option)

- **15-minute bars:** aligned to 09:15. A swing high is a 15-minute bar whose high is strictly above the 2 bars before
  and at or above the 2 after. Swing lows mirror on lows. A swing is known only once the bar 2 after it has finished.
  The latest known swing high and low carry across sessions.
- **A break (minute by minute):**
  - **up:** the first minute whose **high** is at least 1 tick (0.05) above the latest known swing high;
  - **down:** the first minute whose **low** is at least 1 tick below the latest known swing low.

  Each swing level can be broken once.
- **Trading (one position per index):**
  - **Flat:** enter on any break, buying the call (up) or the put (down) at the next minute's open.
  - **Holding a call:** a down break exits it, and reverses into a put if the entry window is open. A put mirrors this.
  - **Same-direction breaks:** do nothing.
  - **Entry window:** the entry minute from 09:30 to 14:30.
  - **Exit:** out at 15:20 (15:05 if the option bought expires that day).
- **The option:** the same rule as R130. The nearest expiry that does not expire that day (the monthly series on the
  index's weekly expiry day, else no trade). ATM from the last index price before entry. Bought at the entry minute's
  open, sold at the last traded price at the exit minute. Dated charges, liquidity slippage (also 2x), 1 lot at
  today's lots, lake cleaning steps 1-3.

## 2. Versions (6, one BH family)

| version | change from T1 |
|---|---|
| **T1, the 15-minute trend as is** | none (ATM) |
| T2 | enter only when the 60-minute structure (the same rule on 60-minute wick bars) points the same way at entry |
| T3 | the 1-in-the-money option |
| T4 | also a target at 1R (R = the entry's index price minus the opposite swing level at entry): a higher-win-rate exit |
| **T5, + R123 direction** | enter only when R123's single network (N1), at its latest 5-minute decision before the entry, gives P(index higher in 60 min) ≥ 0.5 for a call, ≤ 0.5 for a put |
| **T6, + R123 expected value** | enter only when N1's best expected value over the 8 call (or put) cells at that decision is ≥ Rs 0 |

- **R123's predictions are out of sample:** its walk-forward folds (2023-01-01 to 15 Mar 2026, each fold predicted by
  a model trained before it) and its holdout predictions (Apr - Oct 2026).
- **T5 and T6 exist only from 2023.** They are compared with T1 on the same days.
- **A filtered-out break is skipped.** While holding, a filtered-out reverse still exits the old position.

## 3. Periods and checks

- **Design 2021-08-01 .. 2026-03-15:** no fitting, so each year is out of sample. Reported per year; the folds are
  2023, 2024, 2025 and 2026 Q1.
- **Apr - Oct 2026, read once for R131.** R130 read this period for other rules (and R123 for its own), never for this
  rule. It is labelled "used before by other studies".
- **Untouched: NIFTY index minutes 2017-01 .. 2020-12** (no study in this line has read them; there are no option bars).
  T1, T2 and T4 in index points.
  - **Break-even:** the average index points a NIFTY trade needs to cover the option's costs and decay. It is
    estimated from the 2021-2026 NIFTY design trades as the median of (index points - 2 × option net per unit), with an
    ATM delta of 0.5.
  - **Gate:** the 2017-2020 average points per trade must exceed that break-even.
- **Twins (200 per trade):** the same index and day.
  - **Random-side twins (the gate):** a random entry minute 09:30-14:30, a **random side**, the same holding time
    (capped at 15:20), the same option rule. They test the direction and timing together.
  - **Same-side twins:** reported only. The same but the trade's own side; they test timing alone.
- **Gates (all; Boss's 65% rule included):**
  - design: ≥ 300 trades; average net > 0 at 1x and 2x; ≥ 3 of 4 folds positive; BH q ≤ 0.10 over the 6 versions
    (random-side twins);
  - Apr - Oct 2026: ≥ 50 trades; average net > 0; **win rate ≥ 65%**;
  - NIFTY 2017-2020 (T1, T2, T4 only): above break-even;
  - account: drawdown above -Rs 40,000 and no month below -Rs 13,000 (design plus Apr - Oct, 1 lot).
- **If one passes,** a paper arm is proposed (not built). Otherwise the write-up says plainly it fails.

## 4. Outputs

- Code and tests: `research/hunt/r131_trend15/` (`r131.py`, `test_r131.py`, with a look-ahead guard).
- Results: `results/design.json`, `results/holdout.json`, `results/nifty_2017_2020.json`.
- Write-up: `research/HUNT_R131_TREND15.md`.
- Entries: `research/experiments/entries/r131-*.yaml`.
- RESULT.md.
