# R124: R123's straddle with an index stop, on normal days (research loop, round 4)

Written 27 Oct 2026, for Boss (*"find the best strategy you can with max winrate"*).

- **Pre-registration:** `research/hunt/r124_straddle_stop_normal_days/PREREG.md`, commit `436e476`, pushed before any
  number.
- **Code and tests:** pushed before the run (`c6ec183`).
- **Tables:** `research/hunt/r124_straddle_stop_normal_days/results/report.md`.
- **Experiment entries:** `research/experiments/entries/r124-w1.yaml`, `r124-w2.yaml` (both failures).
- **Research only.**
- **Signal source:** the stop reads the underlying (the index's minute closes, the default); the entry is the clock.

## Verdict for Boss

**0 of 2 pass. The out-of-sample check says R123's edge belongs to expiry days only. Normal days give the clearest
example yet of a high win rate that does not pay.**

- **The test:** R123's rule, frozen, on the five indices' normal (non-expiry) days, which it had never been run on. Sell
  the ATM call and put at 09:30; close both if the index moves 1 (W1) or 1.5 (W2) straddles; otherwise out at 15:05.
- **The win rate is high, the money is not** (4,277 trades, 1 lot):

  | | design 2021-23 | walk-forward 2024-25 | final 2026 |
  |---|---|---|---|
  | W2 win rate | 64% | 65% | 62% |
  | W2 mean after costs | +Rs 11 | +Rs 219 | **−Rs 106** |
  | W1 mean after costs | +Rs 34 | +Rs 201 | **−Rs 159** |
  | worst day (W2) | −Rs 20,747 | −Rs 46,610 | −Rs 44,627 |

- **Why:**
  - On a normal day the straddle has one or more days left. The index rarely moves 1.5 of those straddles in a session:
    the stop fires on only 0.6-3.4% of days (W2; W1, at 1 straddle, 5-12%).
  - So almost every day is a small win from time decay, and the rare large move is a large loss.
  - The worst day, −Rs 47k at 1 lot per index, is a day when all five indices moved together.
  - In 2026 the small wins did not cover the large losses. That is the win-rate trap from the maths in R121, now in
    the data.
- **Gates failed:**
  - the final period is negative, also without its 3 best days and at 2x slippage;
  - 5 months of 2026 at or below −Rs 13k, and drawdowns up to Rs 2.2 lakh;
  - BH q = 1.0.
  - Only W2's twin p (0.037) passes. It says random entry times did even worse, not that W2 made money.
- **What it means for R123:** the mechanism did not carry over to new days. R123's after-cost profit came from the
  expiry day's faster decay (the straddle melts to near zero by 15:05), not from the stop. On expiry days the stop
  fired on about 30% of days and cut the losers; on normal days it almost never fires. R123 stays a note.

## By index (W2, mean after costs; n; win rate)

| index | design | walk-forward | final |
|---|---|---|---|
| NIFTY | −Rs 21 (470, 63%) | +Rs 261 (391, 63%) | −Rs 244 (155, 61%) |
| BANKNIFTY | +Rs 5 (449, 66%) | +Rs 221 (436, 65%) | −Rs 132 (175, 61%) |
| FINNIFTY | −Rs 14 (272, 61%) | +Rs 132 (434, 65%) | +Rs 191 (137, 64%) |
| MIDCPNIFTY | +Rs 289 (87, 68%) | +Rs 350 (434, 66%) | −Rs 40 (175, 65%) |
| SENSEX | +Rs 10 (114, 64%) | +Rs 124 (394, 63%) | −Rs 275 (154, 61%) |

2024-25 was good for every index and 2026 bad for four of five: a market-regime swing, not an index effect.

## What was tested

- Every normal session 2021 to 19 Oct 2026 (R122's builder), five indices; 1 lot.
- R119's fills and dated charges (4 orders; about Rs 137 a trade).
- A leg with no print at the exit is priced by Black-76 with calendar time to its expiry.
- Gates as R123.

## Scoreboard

`research/LOOP_SCOREBOARD.md`.
