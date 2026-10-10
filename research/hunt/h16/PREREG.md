# h16 pre-registration: cheaper / more convex options for Liquidity 15+5 at Rs 1,00,000 capital
(written 2026-10-07, BEFORE any h16 P&L was computed)

Question: with only Rs 1 lakh, the 1-ITM monthly option (BN 1 lot ~ Rs 30-50k of premium) cannot even be bought at a
sensible fraction of capital. Does buying cheaper, more convex options (ATM / 1-OTM / 2-OTM) on the SAME Liquidity 15+5
signals earn more Rs per Rs of premium and more Rs/day at Rs 1 lakh, with compounding and a ruin check? OPTION BUYING
ONLY. Entries, index stop, level target, failed break, new level, 20-min time stop, 15:10 square-off, expiry-day skip,
one position per book: the app's arm UNCHANGED (h4/h7 port, h4 cached signals; books BANKNIFTY 15m+5m, FINNIFTY
30m+5m, MIDCPNIFTY 15m+5m). Contract: nearest expiry (`near`, = the app; monthly-only since Nov 2024).

Known before writing this (disclosed): h10/h13/h14 reports, including h13's equal-lot (BN 26/FIN 3/MIDCP 11) numbers
for 1-OTM/ATM/1-ITM (fixed and scaled stops) pre-holdout AND its post-hoc holdout table (1-OTM 1,894/day vs 1-ITM
4,830/day at equal lots). Nothing about 2-OTM, the no-premium-stop exit, or any capital-constrained (Rs 1 lakh) result.

## Grid (16 variants, all counted)
- money (4): ITM1 (incumbent strike), ATM, OTM1, OTM2 (strike step from the signal close, the obuy StrikeRule).
- premium stop (2):
  - `scaled`: h13's rule, stop = clip(0.15 * lambda_k / lambda_ITM1, 0.05, 0.60), time-stop gain = stop/3
    (lambda = delta/premium at the signal minute; missing -> pre-holdout per-index median). For ITM1 this is exactly
    the arm's -15% / +5% (the incumbent).
  - `none`: no premium stop at all; time-stop gain stays the arm's +5%. Index stop / target etc. as is.
- allocation (2), the per-trade premium budget at entry:
  - `third`: min(free cash, equity/3)  (three books can be open together),
  - `full`: free cash (whatever is not tied up in open positions).
  lots = the largest n with premium(n) + entry charges <= budget, then the h10 participation cap (15% of 5-min volume)
  clips it; n = 0 -> trade skipped (counted).
- INCUMBENT = ITM1 / scaled(=arm) / third.

## Capital model (Rs 1 lakh, compounding both ways)
- Start equity Rs 1,00,000 at the start of each window. Trades of all three books are processed in time order; a trade's
  net (after app charges, h10 fills: 'real' + sqrt impact kappa*sqrt(q/v5), kappa 0.02; exits worked over minutes when
  the cap binds) is realised at its last exit minute; exits at minute m settle before entries at minute >= m.
- free cash = realised equity - premium paid (incl. entry charges) of open positions. Never borrow; no margin (bought
  options). Equity < Rs 25,000 = RUIN: trading stops (absorbing).
- Per-trade net / premium for any n come from h10's simulator run at n = 1..30 and a geometric grid above (linear
  interpolation in between), so impact and per-order charges scale correctly as equity grows.

## Windows
PRE = 2021-10-01 .. 2025-09-30; era W = .. 2024-11-30 (weeklies), era M = 2024-12-01 .. 2025-09-30 (monthly-only =
today's market). HOLDOUT = 2025-10-01 .. latest, run ONCE after choice.json is written.

## Metrics
- Rs/day at Rs 1 lakh = (final equity - 1 lakh) / trading days of the window (deterministic replay, compounding).
- Also: return on premium deployed (sum net / sum premium), trades taken / skipped, max drawdown as % of running peak
  equity, worst day, worst month, per-year (yearly restart at 1 lakh, era-W walk-forward table).
- Monte Carlo: stationary block bootstrap of trading days (mean block 10 days, 2,000 paths, horizon 248 days and 496
  days), compounding replay from 1 lakh. P(ruin: equity ever < 25k), P(1-year loss), median / p5 / p95 final equity,
  median days to 2 lakh, and to Rs 35 lakh (the capital h10/h14 say the Rs 5,000/day plan needs).
- Random baseline: per variant, 5 random minutes / coin-flip side per signal, same strike rule and exits, h10 model at
  1 lot; OF.random_baseline on PRE; BH over the 16.
- SPA / White RC over the 16 daily Rs series of the 1-lakh replay (era M and PRE), benchmark 0.
- Capacity/impact: kappa 0.01 / 0.04 replays reported for the choice and the incumbent.

## Choice rule (PRE only)
1. Eligible: era-W 1-lakh replay ends above 1 lakh AND era-M replay ends above 1 lakh AND random-baseline BH q < 0.05
   AND MC P(ruin within 248 days), bootstrapped from era-M days, < 10%.
2. Score = era-M Rs/day at 1 lakh (kappa 0.02). Pick the highest; tie-break by the era-W replay.
3. Keep the incumbent if it is eligible and its score is >= 85% of the best.
4. Holdout once: the choice and the incumbent from 1 lakh at 2025-10-01 (kappa 0.02, also 0.01/0.04), MC from holdout
   days, random baseline. Any other holdout number is post-hoc and labelled so.
