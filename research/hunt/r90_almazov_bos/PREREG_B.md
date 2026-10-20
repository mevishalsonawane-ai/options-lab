# R90B pre-registration: 5 one-minute confirmation candles, profit lock, stop trail, profit trail (written 20 Oct 2026)

Boss, 20 Oct (after R90): "What if at 5 1min candle for direction confirmation, and with profit lock, and stop loss trail,
profit trail". Written and committed BEFORE any R90B statistic. Later changes go to `NOTES_B.md`, dated, before the numbers
they affect.

**Signal source of every rule: the UNDERLYING** (index spot 1m; MCX near future, close-only). The R90 setup is unchanged
(chart / HTF structure, stop entry at the chart swing, stop at the opposite swing -/+ 1 tick, HTF target, min RR 1).
Confirmation, lock and trails are also read on the underlying, in units of R = the trade's initial risk in points. The
trade is still buying the 1-ITM option on real prints with real costs.

## 1. Rules (one change at a time, then all together)

- **C5 (confirmation):** after the stop entry triggers at minute t, wait for the 5 one-minute candles t+1 .. t+5. Enter
  only if (a) the 5th candle's close is still beyond the entry level, (b) at least 3 of the 5 candles closed in the trade's
  direction (green for a long, red for a short), (c) neither the stop nor the target was touched from t to t+5 (on t only
  its close), and (d) t+6 is before the flat time. The reference price is the close of t+5; the option is bought at the
  open of t+6. A setup that fails confirmation is spent (no trade, the chart level counts as used).
- **L (profit lock):** once the best price since entry is >= +1R, the stop moves to entry +0.5R (never back).
- **T (stop-loss trail):** the stop is max(initial stop, best price - 1R) (mirror for shorts), from entry.
- **P (profit trail):** no fixed HTF target; once the best price is >= +1R, exit when price gives back 50% of the best
  open profit (stop = entry + 0.5 x (best - entry)).
- Stops move only at a minute's close (a new level applies from the next minute: no intrabar look-ahead). Exit when a
  minute's low / high reaches the current stop (gap: at the open) or the target; else flat 15:15 / 15:05 expiry (MCX:
  the R53 session cut). Index options are sold at the next minute's open (R51 engine); MCX options at the exit minute's
  close (else the next print within 10 minutes; R53 convention), the same rule for every variant.

| id | confirmation | target | lock | stop trail | profit trail |
|---|---|---|---|---|---|
| X0 | no | HTF swing | - | - | - (R90 as tested, re-priced the R90B way) |
| C5 | yes | HTF swing | - | - | - |
| LTP | no | none | yes | yes | yes |
| C5L | yes | HTF swing | yes | - | - |
| C5T | yes | HTF swing | - | yes | - |
| C5P | yes | none | - | - | yes |
| C5LTP | yes | none | yes | yes | yes |

Charts: A1 (1m / 4m), A5 (5m / 20m, the default) and H60 (5m / 60m). 7 rules x 3 charts x 9 instruments = **189 cells**,
one BH family.

## 2. Windows, measures, gates

- Design: 20 Nov 2024 - 31 Mar 2026 (indices), 5 Aug 2025 - 31 Mar 2026 (MCX), as R90. Check: 1 Apr - 19 Oct 2026, read
  once by `r90b.py final` (guard `results_b/FINAL_READ`). **This window was already read by R90 for the base rule, so it is
  not unseen for R90B**; it is reported as a second look. A cell passing both would still need unseen data (2021-24 index
  options as in R89B, which needs Boss's OK, or a forward shadow) before any paper use.
- Measures as R90: trades, a day, win rate, average win / loss, expectancy, net, max DD, worst day / month, exit reasons
  (initial stop, moved stop, target, time), underlying points, after-trigger twins (500, seed 91: entry minute uniform from
  the trade's entry minute to 14:45 / 22:30, same side, R, target distance and exit rule), walk-forward folds.
- Gates: the R90 / R89 gates over the 189 cells (>= 40 trades, net > 0, twin p < 0.05, BH q < 0.10, folds, without top
  5, worst month >= -Rs 13,000).
- Tests before any result: confirmation cases, lock / trail / giveback moves, no intrabar look-ahead, X0 equals R90's
  underlying trades, look-ahead guard.
