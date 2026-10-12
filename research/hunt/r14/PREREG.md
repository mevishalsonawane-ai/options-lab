# R14 pre-registration: re-entry churn and "target vs trail" on ORB / ORB Fresh

Written 12 Oct 2026, BEFORE any candidate below was simulated (only `data.py` had run: it builds the candidate entries and
their option minutes, no exits, no P&L). Nothing here was chosen from results.

## Frame (same as R12 / h20)
- Option BUYING only, Rs 1 lakh account, 1 lot, BANKNIFTY ATM from the 09:20 bar, the same-day option on expiry days.
- Signals: the app's `OrbRules.entrySignal` as h20 ported it (`research/hunt/h20/sim.py signals`): decision bars 10:05-13:55,
  close above the 09:15-10:00 range -> CE, below -> PE; ORB Fresh only when the previous bar was not already beyond the
  range the same way. After an exit the next decision bar must START after the exit's 5-minute bar (the app's
  "cooling_down_after_exit"). One position at a time per arm.
- Exits: h20 "fixed" mode = the app since 07 Oct: ONE resting SL-M at max(entry - 40, profit lock); the lock ladder
  (+10 -> breakeven after charges, +20 -> +10, +30 -> +20) is earned on earlier minutes' HIGHS and fills at the trigger or
  the gap open; the +40 target is the app's check on a minute's high, sold at the next minute's open; 15:10 square-off.
- Fills and costs: h20 "real" = app paper fills (+-5 bps, stops -10 bps) + a 1-tick half-spread (2 ticks on stops);
  charges = SandboxCosts (Rs 20 brokerage an order, STT 0.15% on the sell, exchange 0.03553%, SEBI, stamp, GST 18%).
- Periods: DESIGN = 4 Aug 2021 - 30 Sep 2025. HOLDOUT = 1 Oct 2025 - 5 Oct 2026, read ONCE, only for the rule picked per
  arm on design. (The R12 holdout was the same window; R12 read it for different rules - exit managers, never re-entry
  caps or trails - so these rules have not been fitted to it.)

## The candidates (6 per arm; the 5 non-baseline ones are the tests)
| id | name | entries | exits |
|---|---|---|---|
| R0 | baseline (today) | re-enter from the bar after the exit's bar, no cap | -40 / ladder / +40 target / 15:10 |
| R1 | cap 1 a side | at most 1 entry per side (CE / PE) a day | as R0 |
| R2 | cap 2 a side | at most 2 entries per side a day | as R0 |
| R3 | let it run | as R0 | -40 / ladder as R0; NO +40 target; from a best high of +40 the lock is entry + 75% of the best gain (+30 at +40, +75 at +100), only rising; 15:10 |
| R4 | run + cap 1 | at most 1 entry per side a day | as R3 |
| R5 | hybrid | at most 2 entries per side a day | a side's FIRST trade: R0 exits; a same-side entry after that side's trade ended at the +40 target: R3 exits (hold with the trail instead of booking again) |

The trail (R3-R5) is h20's `Spec(trail=(40, 0.75))` on the ladder: the stop never falls, the loss can never exceed the
original -40, and the 15:10 square-off is unchanged. No "confidence" filter is added: one more free condition would be one
more thing to fit, and R5 is already the "it kept going after the first target" condition.

## Statistics (fixed now)
- Per rule: trades, orders (2 a trade), win %, gross, charges, net, charges as % of gross profit, max drawdown of the daily
  net, worst day, net per calendar year.
- Test per (arm, rule): the per-day difference rule - R0 (all design days with a candidate), one-sided paired t (H1:
  better) and a 10,000-draw day bootstrap; BH over the 10 tests (2 arms x 5 rules) at q < 0.10.
- PICK per arm (design only): among R1-R5 with design delta > 0, BH q < 0.10, and delta > 0 in at least 4 of the 5 design
  calendar years (2021 Aug-Dec, 2022, 2023, 2024, 2025 Jan-Sep), the largest design delta; a tie within 5% goes to the
  lower-risk rule (a cap over a trail). None qualifies -> no pick; the holdout is then read for information only.
- HOLDOUT confirmation of a pick: delta > 0 AND one-sided paired p < 0.10. Confirmed -> paper default + proposed for live
  via the app's proposal path. Not confirmed -> shadow / variant only.
