# h17 pre-registration: Liquidity 15+5 with FIXED lots at Rs 1,00,000 (written 2026-10-07, BEFORE any h17 P&L)

Boss rejected sizing that grows with the account (Kelly, h15). Question: at Rs 1 lakh with a FIXED number of lots
(never scaled with equity), which book set, lot count and daily discipline, and what Rs/day does it honestly give?
OPTION BUYING ONLY. Signals, books, strike (1-ITM, nearest = monthly), exits: the app's Liquidity 15+5 arm unchanged
(h4/h7 port, h10 trade list). Execution: h14 E2 (limit buy at entry print +0.5%, rests 3 min, cancel, no chase),
fill model M2 with h10 impact, kappa 0.02 central and 0.04 pessimistic (0.01 info). Tables: h15 `tab_real.pkl`
(built by research/hunt/h15/tab.py, read-only reuse; net/gross/premium/exit per trade for every lot count).

Known before writing (disclosed): h10/h13/h14/h15/h16 reports incl. their holdouts (h15's A-K2 Kelly holdout
Rs 7.41 L, h16's 1-ITM/third holdout Rs 3.81 L; h15's 1-lot mean net per trade BN +240 / MIDCP -72 regime window,
BN +214 / MIDCP +99 holdout; h15 F1 = 1 lot per book row). Nothing about fixed 2-4 lot BN, the daily stops, or
bootstrap P(<25k)/P(<50k) under fixed lots.

## Grid: 8 sizes x 3 disciplines = 24 variants (all counted)
- Books/size (8): BANKNIFTY only at 1/2/3/4 lots; BANKNIFTY 1/2/3/4 lots + MIDCPNIFTY 1 lot.
- Daily discipline (3), threshold X = Rs 5,000 when BN lots <= 2, Rs 10,000 when BN lots 3-4 (about 2.5k per lot):
  - `none`;
  - `L`: no NEW entries for the rest of the day once that day's realised net <= -X;
  - `LP`: `L` and also no new entries once that day's realised net >= +X (profit lock-in).
  Open positions always run to their own exits. (Grid of the brief is 2x4x(1+2+2+4) > 24; this is the pre-registered
  subset: thresholds tied to size, profit lock tested together with the loss stop.)

## Capital walk (fixed lots, no compounding of size)
Start Rs 1,00,000. Trades in time order; one position per book. E = realised capital (a trade's net is added at its
last exit slice). Free cash = E - premium of open positions. A signal is SKIPPED (counted) if its fixed lots'
premium + Rs 100 > free cash (no partial fills of the plan; the other book is judged on its own). Trading continues as
long as the lots are affordable; "P(capital < 25k)" = capital ever below 25k within the horizon.

## Windows
PRE = 2021-10-01 .. 2025-09-30 (MIDCP trades start 2023-05). Era M = 2024-12-01 .. 2025-09-30 (monthly-only market).
HOLDOUT = 2025-10-01 .. 2026-10-05, run ONCE for the choice after choice.json is written.

## Metrics per variant (kappa 0.02; kappa 0.04 also)
Net and gross Rs/day; ending capital from Rs 1 lakh (replay from 2021-10-01 and, restart, from each calendar year and
era M); max drawdown in Rs and % of Rs 1 lakh; worst day; worst month; P(losing month) (stationary bootstrap of
days, 21-day months); P(capital < 25k) and P(capital < 50k) over 248 and 496 days (stationary block bootstrap of PRE
trading days, mean block 10 days, 2,000 paths, start Rs 1 lakh); signals skipped for cash.
Biggest fixed size: for each book set x discipline, BN lots 1..8 (risk-only scan, PRE bootstrap, kappa 0.02): the
largest lot count with P(capital < 50k within 248 days) < 5%. For 5-8 lots X = Rs 10,000. Sizes where more than
50% of signals are skipped for cash are flagged "not tradable" (a size you cannot afford trivially never loses).

## Choice rule (PRE only)
1. Eligible: P(capital < 50k within 248 days) < 5% (PRE bootstrap, kappa 0.02) AND PRE net Rs/day > 0 at kappa 0.04.
2. Score = net Rs/day at kappa 0.02 over PRE, each calendar year restarted at Rs 1 lakh (days-weighted).
3. Take the highest score; if a simpler variant (order: fewer BN lots, then BN only, then none < L < LP) is within
   5% of it, take the simplest such.
4. Anchored walk-forward (test years 2023, 2024, 2025-Jan..Sep): apply rules 1-3 on data before the test year; report
   the pick's test-year Rs/day vs the final choice's.
5. Multiple testing: BH over the 24 one-sided stationary-bootstrap p-values of mean daily net > 0 (PRE); SPA/White RC
   over the 24 PRE daily series. Random entries: inherited from h15 (same tables, same exits; every h15 variant beat
   random entries) - the pool tables were deleted for disk; not re-run.
6. Holdout once: the choice at kappa 0.02/0.04/0.01 from Rs 1 lakh; MC from holdout days. Everything else in the
   holdout is post-hoc and labelled.
