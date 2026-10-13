# C2: Crypto in Boss's window, 00:00-09:00 IST only (pre-registration)

Written 13 Oct 2026, before any result for this window was computed. Boss: "I want crypto market should only run from
morning 12am to morning 9am." Read as: crypto is studied (and would only ever be acted on) between **00:00 and 09:00
IST** = **18:30-03:30 UTC** (the previous UTC day's 18:30 to 03:30), every day including weekends; every position is
flat by 09:00 IST, before NSE opens at 09:15. The cube and the features stay 24x7 (a 24-hour VWAP or funding z-score
needs the hours outside the window); only decisions, targets and trades are restricted to the window
(`config.WINDOW`, `markets.window_flags`).

## Data, holdout, method

As C1 (`PREREG.md`): BTCUSDT and ETHUSDT, crypto cube v2, Oct 2023 - Mar 2026, design data only; the holdout from
1 Apr 2026 00:00 UTC is locked and unread; windows whose end reaches it are dropped. Walk-forward with
`holdout.walk_forward` (30 blocks, first 6 train only, purged, 1-day embargo; a block is tested only when its training
set has >= 200 rows, as C1 ran). Ridge (alpha 1) for sizes, L2 logistic (C 1) for direction, fixed now.

## Decision points

- Window hours: the rows at 18:29, 19:29, ..., 02:29 UTC (each known at the start of an IST hour 00:00 ... 08:00);
  the target is the next 60 minutes, which ends inside the window. 9 a day.
- Whole window: the 18:29 UTC row (known at 00:00 IST); the target is the next 540 minutes (to 09:00 IST).
- Weekend = the window's IST date is a Saturday or Sunday.

## Tests (one Benjamini-Hochberg family of 26, q = 0.05)

| id | question | inputs | baseline | statistic | pass bar |
|---|---|---|---|---|---|
| W1 | range of the next window hour | log range 60/240 min, log 24h realised vol, log DVOL (BTC), window-hour x weekend mean (fitted in train), weekend | window-hour x weekend mean of log range | weekly means of (abs error baseline - model), one-sided t | q < 0.05, skill >= 5% |
| W2 | direction of the next window hour | C1 B1's inputs | coin | AUC, one-sided weekly-block bootstrap (2,000) | q < 0.05, AUC >= 0.52, net > 0 |
| W3 | range of the whole window | log range 240 min, log 24h realised vol, log DVOL (BTC), weekend | mean log range of the previous 20 windows | as W1 | q < 0.05, skill >= 5% |
| W4 | direction of the whole window | returns 240/1440 min, funding z, basis z, OI change 24h, VWAP-24h z, weekend | coin | as W2 | as W2 |
| W5-h | mean return of window hour h (9 per symbol) | - | 0 | t across days | q < 0.05 and |mean| > round trip |

13 tests per symbol, 26 in all. Trades: perpetual taker, 0.05% + 1 bp a side (0.12% a round trip) plus funding at the
last settled rate for each settlement held (the 00:00 UTC settlement = 05:30 IST falls inside the window), 2x slippage
stress; long at p >= 0.55, short at p <= 0.45.

## Honest limits, said now

- Design data only: a pass is a candidate for one holdout read, never a trade. Nothing here trades, on paper or live;
  the app does not trade crypto.
- C1 already looked at all 24 UTC hours (including these). C2 is a new family on a subset of the same design data, so
  its passes are not independent evidence of C1's; both families count in the experiment log.
