# R131: the 15-minute trend on its own, and with R123's neural network as a filter

> Number note (coordinator, 29 Oct): two studies carry the number R131. This one is **R131 trend15**; the other is **R131 ICT ten** (research/HUNT_R131_ICT_TEN.md, the ten ICT/SMC video strategies). Their files and entries do not overlap.

29 Oct 2026. Research only: nothing in the app changes, nothing trades. Boss: *"Test the 15-minute trend on its own"*,
then *"Also try to use neuronetwork from r123 to find if there could be more enhancement"*.

The rule (signal on the index, wicks only):
- **Swings:** on 15-minute bars, a swing high is a bar whose high is above the 2 bars on each side; swing lows mirror.
- **Breaks:** when a minute's wick goes 1 tick beyond the latest known swing, that is a break up (or down).
- **Trading:**
  - flat: enter on any break, buying the call (up) or the put (down);
  - holding: an opposite break exits and reverses;
  - entries 09:30-14:30, out by 15:20.

This is stop-and-reverse on the 15-minute structure.

## The answer: it fails, with or without the network

**On 2021 - Mar 2026 the trend picked the right direction more often than chance, and R123's network made that look
like a strategy:**
- T6, the network's expected-value filter: +Rs 267 a trade on 1,887 trades. It passed every design gate.
- T5, the network's direction filter: +Rs 106 on 2,750 trades. It also passed every design gate.

**But in the holdout the edge faded, and the win rate was never near 65%:**
- Apr - Oct 2026: T5 lost Rs 122 a trade (533 trades). T6 made +Rs 88 a trade on 167 trades, with 34% wins and no
  better than random entries (p 0.31).
- On untouched NIFTY minutes from 2017-2020, the plain trend made +4.0 index points a trade. Covering the option's
  costs needs about 7.3.
- Every version wins only 32-43% of the time. This is a trend-follower: many small losses and fewer big wins.
- Even the best one, T6, had a -Rs 92,471 drawdown and a -Rs 40,786 month on 1 lot (five indices). That is far beyond
  Boss's -Rs 40,000 / -Rs 13,000 account stop.

**Nothing passes. Nothing goes on paper.**

Design, Aug 2021 to 15 Mar 2026 (each year out of sample: nothing was fitted):

| version | trades | win rate (vs 65%) | avg win / avg loss | avg net per trade | total (1 lot) | max drawdown | vs random side (p / q) |
|---|---|---|---|---|---|---|---|
| **T1, the 15-minute trend as is** (ATM) | 6,317 | 38% (below) | Rs 3,180 / -Rs 1,915 | **+Rs 6** (2x: -Rs 16) | +Rs 36,919 | -Rs 4,17,826 | **0.005 / 0.006** |
| T2, + the 60-minute trend agrees | 3,445 | 38% (below) | Rs 2,961 / -Rs 1,902 | -Rs 60 | -Rs 2,06,904 | -Rs 2,90,811 | 0.09 / 0.09 |
| T3, 1-in-the-money | 6,289 | 39% (below) | Rs 3,434 / -Rs 2,120 | +Rs 20 (2x: -Rs 5) | +Rs 1,23,484 | -Rs 4,02,450 | 0.005 / 0.006 |
| T4, + a 1R target | 6,593 | 43% (below) | Rs 2,534 / -Rs 1,941 | -Rs 21 | -Rs 1,37,829 | -Rs 4,04,843 | 0.005 / 0.006 |
| **T5, + R123 direction** (2023 on) | 2,750 | 40% (below) | Rs 3,071 / -Rs 1,888 | **+Rs 106** (2x: +Rs 83) | +Rs 2,92,332 | -Rs 2,02,876 | 0.005 / 0.006 |
| **T6, + R123 expected value** (2023 on) | 1,887 | 40% (below) | Rs 3,068 / -Rs 1,569 | **+Rs 267** (2x: +Rs 248) | +Rs 5,02,986 | -Rs 92,471 | 0.005 / 0.006 |

For a like-for-like view, T1 on the network's days (2023 on) was 5,130 trades at +Rs 30 a trade. The network lifted
that to +Rs 106 (T5) and +Rs 267 (T6).

Holdout, 1 Apr to 19 Oct 2026, read once (14:51-14:53 UTC, 29 Oct):

| version | trades | win rate (vs 65%) | avg net per trade | total (1 lot) | max drawdown | vs random side (p) |
|---|---|---|---|---|---|---|
| T1 | 894 | 36% (below) | -Rs 56 | -Rs 50,067 | -Rs 1,92,313 | 0.24 |
| T2 | 492 | 32% (below) | -Rs 344 | -Rs 1,69,200 | -Rs 1,76,775 | 0.89 |
| T3 | 869 | 37% (below) | -Rs 68 | -Rs 59,357 | -Rs 1,91,023 | 0.18 |
| T4 | 927 | 40% (below) | -Rs 248 | -Rs 2,29,853 | -Rs 2,76,372 | 0.72 |
| **T5, + R123 direction** | 533 | 34% (below) | **-Rs 122** | -Rs 64,970 | -Rs 1,79,251 | 0.42 |
| **T6, + R123 expected value** | 167 | 34% (below) | **+Rs 88** | +Rs 14,733 | -Rs 45,021 | 0.31 |

NIFTY 2017-2020 (925 sessions, index points only; never read before in this line):

| version | trades | avg index points per trade | needed to cover the option (2021-26 NIFTY estimate) | index win rate |
|---|---|---|---|---|
| T1 | 1,492 | **+4.0** | 7.3 | 43% |
| T2 | 792 | +2.0 | 7.3 | 43% |
| T4 | 1,545 | +0.9 | 7.3 | 48% |

## What the network added, and why it is not enough

- **In design it helped clearly.**
  - The expected-value filter (T6) kept about a third of T1's trades, cut the average loss (-Rs 1,569 vs -Rs 1,915),
    and made money in 2023 (+Rs 3.0 lakh on 885 trades) and 2024 (+Rs 2.1 lakh on 620).
  - It lost a little in 2025 (-Rs 18,996 on 303) and made +Rs 10,034 in Jan - Mar 2026 (79).
- **The holdout did not hold it up.**
  - T6 traded far less (167 trades in 6.5 months): the network rarely saw a positive expected value.
  - Its +Rs 88 a trade came from NIFTY (+Rs 24,548 on 72) and MIDCPNIFTY (+Rs 14,919 on 15). The other three lost.
  - It was no better than random-side entries (p 0.31).
- **The network's pattern was the same as in R123:** good on its walk-forward years, much weaker on the newest months.

## What the trend itself does

- **Direction, not timing.**
  - Against random-side twins (same day, a random time, a random side), T1 did better in design (p 0.005).
  - Against same-side twins (same day and side, a random time), it did worse (p 1.00). Entering exactly at the break
    is worse than entering at a random time the same way: a break means buying after the move.
  - In the holdout the direction edge also faded (p 0.24).
- **The index edge is real but small.** +4.0 points a trade on NIFTY in 2017-2020 (1,492 trades, all 4 years
  positive: 2017 +1.1, 2018 +2.6, 2019 +2.9, 2020 +8.8). That is about half of what the option needs.
- **The 60-minute filter and the 1R target made it worse** (T2, T4). The target raised the win rate (43%) and cut the
  big winners, which a trend-follower needs.

## Gates

| version | design (n / net at 1x and 2x / folds / q) | holdout (n / net / 65%) | NIFTY 2017-20 | account |
|---|---|---|---|---|
| T1 | pass / **fail** (2x -Rs 16) / pass / pass | pass / **fail** / **fail** | **fail** (4.0 < 7.3) | **fail** |
| T2 | pass / **fail** / **fail** / pass | pass / **fail** / **fail** | **fail** | **fail** |
| T3 | pass / **fail** (2x -Rs 5) / pass / pass | pass / **fail** / **fail** | n/a | **fail** |
| T4 | pass / **fail** / **fail** / pass | pass / **fail** / **fail** | **fail** | **fail** |
| T5 | **pass** all | pass / **fail** / **fail** | n/a | **fail** (drawdown -Rs 2,02,876) |
| T6 | **pass** all | pass / pass (+Rs 88) / **fail** (34%) | n/a | **fail** (drawdown -Rs 92,471; month -Rs 40,786) |

**Verdict: no version passes.** The two network versions passed the design gates and then failed the holdout (T5 lost
money; T6 won 34% and was no better than random). Their drawdowns break the account stop. Nothing is proposed for paper.

## How it was done

- **Pre-registration:** `research/hunt/r131_trend15/PREREG.md`, commit `f374112`, before any number.
  - Design results were committed in `3537dff` before the holdout and the 2017-2020 reads.
- **Code and 6 tests:** `research/hunt/r131_trend15/` (`r131.py`, `test_r131.py`). The tests cover: point-in-time
  swings, a look-ahead guard on the breaks, stop-and-reverse, the entry window, the 1R target, the 60-minute filter,
  and the network lookup using only decisions made before the entry.
- **Data:**
  - the Drive lake (index minutes, WEEK and MONTH options), lake cleaning steps 1-3;
  - R123's single network (N1): its out-of-sample walk-forward predictions (2023 - 15 Mar 2026) and its holdout
    predictions (`derived/r123/cleaned/*_rows_preds_N1.parquet`).
- **The option:** the nearest expiry that does not expire that day, ATM (T3: 1-ITM), 1 lot at today's lots, dated
  charges, liquidity slippage (also 2x).
- **Twins:** 200 per trade, the same day and holding time.
  - random-side twins: a random time and a random side (the gate);
  - same-side twins: a random time and the trade's own side.
- **Break-even for NIFTY 2017-2020:** the median over the 2021-26 NIFTY T1 trades of (index points - 2 × option net per
  unit), i.e. an ATM delta of 0.5. It came to 7.3 points.

## Notes

1. **The holdout period was read before by R130 and R123 for other rules,** never for this one. The 15-minute trend
   idea came from R130's design data, not from this period.
2. **The 2017-2020 check is in index points only** (there are no option prints then). The break-even is an estimate:
   it assumes the 2021-26 cost and decay per trade.
3. **Signal source:** every version's signal is read on the index. R123's predictions use index and option-chain
   inputs, which CLAUDE.md allows as context or filters around an underlying signal.
4. **Earlier trend studies agree:**
   - FINDINGS: Supertrend + EMA on 15 minutes lost;
   - R90: MTF structure / BOS, 0 of 99;
   - R105: Donchian give-back.

   Intraday index trend is real but small, and option costs eat it.
