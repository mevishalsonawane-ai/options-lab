# h21 pre-registration (written 2026-10-07 17:55 UTC, BEFORE any h21 result was computed)

Question (Boss): can "smarter" entries (discipline + regime filters known at entry) and an ADAPTIVE profit lock make
any of the four old ORB-family arms (ORB, ORB Fresh, ORB Sweep, Range Fade; BANKNIFTY) profitable after costs, alone
or combined, and additive to Liquidity 15+5? Option BUYING only.

## Data, rules, costs (fixed)
- obuy loaders (Dhan BANKNIFTY index + nearest-expiry option minutes, Aug 2021 .. latest), India VIX (daily + minute),
  h18's gamma panel (BANKNIFTY, `panel_BANKNIFTY.parquet`, 5-min points).
- Signals = the app's current OrbRules / SweepRules / RangeFadeRules exactly as h20 `sim.signals` (opening range 09:15-10:04,
  ATM strike from the 09:20 bar, ORB/Fresh decide bars 10:05..13:55, Sweep 10:05..14:25, Fade 10:30..13:55; Sweep/Fade
  at most 2 a day; app cooldown: the next decision bar must start after the exit's 5-min bar). Contract = nearest
  expiry on or after the day (same-day on expiry day); entry refused when premium <= 40.
- Entry fill: next minute open +5 bps (app). Costs: app SandboxCosts (`Costs()`), lot as of the date. PLUS h10's
  square-root impact on entry and exit: imp = min(0.10, kappa*sqrt(lots / lots traded in the previous 5 minutes)),
  kappa = 0.02 central (0.01 / 0.04 reported for the finalists). Gross (no slippage/charges/impact) reported alongside.
- **FIXED lock mechanics** (h20 F1+F2, what the app will do after the fix): the -40 SL-M rests from entry; when the best
  price since entry (minute HIGHS, counted from the next minute) earns a rung, the resting SL-M is MODIFIED up to the
  rung level (floored at entry + round-trip charges per unit; never lowered; only a level below the peak counts).
  A resting stop fills at min(level, minute open) -10 bps. The premium target stays an app-side check on the sampled
  price (minute close >= target -> MARKET sell at next minute open -5 bps). Square-off 15:10 open.
  The app-today mode (h20 "app": lock decided on closes, market sell next open) is reported as a reference only.

## Variants (33; every one counted; each is run on each arm alone and on the four combined)
Reference (not selectable): R0 = app today (current entries, h20 app-mode lock).
- V01 base: current entries, fixed lock (ladder 25/50/75% of target -> BE+charges/+25%/+50%; target 40, Sweep 80).

Discipline (on V01's exits):
- V02 max 1 trade per arm per day; V03 max 2; V04 max 3.
- V05 stop the arm for the day after 2 consecutive losing trades (net < 0).
- V06 cooldown 30 min after a -40 stop-out (next decision bar must start >= exit minute + 30); V07 60 min.
- V08 PKG = max 2/day + V05 + V06.

Regime filters known at entry (each ON TOP OF PKG). Ranks are past-only: the percentile of today's value among all
earlier sessions' values (>= 60 earlier sessions, else the filter passes). "Breakout arms" = ORB, Fresh; "fade arms" =
Sweep, Fade.
- V10 skip narrow OR: OR width / ATR14(daily, prior 14 days) rank < 1/3.
- V11 skip wide OR: rank > 2/3.   V12 middle third only.
- V13 first-30-min strength, arm-aligned: s30 = |close 09:44 - open 09:15| / ATR14; breakout arms need rank >= 0.5,
  fade arms need rank < 0.5.
- V14 first-30-min direction agreement: trade side == sign(close 09:44 - open 09:15) (all arms).
- V15 VIX: skip when VIX at the signal minute is below the previous VIX close (falling vol), all arms.
- V16 VIX arm-aligned: breakout arms need VIX change >= 0, fade arms need VIX change <= 0.
- V17 dealer gamma arm-aligned (h18 B_lvl = log total near-spot gamma - its 20-day same-time median, last 5-min point
  at/before the signal): breakout arms skip the top tercile (past-only cut points), fade arms skip the bottom tercile.
- V18 dealer gamma: skip the top tercile for all arms (h18: high gamma = calm = bad for buyers).
- V19 higher-timeframe (daily) agreement: side == sign(previous close - SMA20 of the previous 20 closes).
- V20 intraday 60-min agreement: side == sign(index close at the signal - close 60 minutes earlier).
- V21 time window: signal bar ends <= 11:30.   V22 time window: signal bar ends > 11:30.

Adaptive locks (fixed mechanics; ON TOP OF PKG, no filter). A = the option's premium ATR known at entry = mean high-low
of the contract's six 5-min bars before the entry minute (fallback: mean 1-min range x sqrt5), clipped to [5, 60] pts.
The -40 resting stop is unchanged in all.
- V23 ATR ladder: +1A -> BE, +2A -> +1A, +3A -> +2A; target 4A (clipped to [15, 120]).
- V24 half-ATR ladder: +0.5A -> BE, +1A -> +0.5A, +1.5A -> +1A; target 2A (clipped to [15, 120]).
- V25 app ladder, active only from 10 minutes after entry; V26 from 20 minutes.
- V27 trail: once peak >= e + 1A, stop = max(BE, peak - 1.0A); target 40 (Sweep 80).
- V28 trail 2.0A armed at +1A, no target; V29 trail 1.5A armed at +1A, no target.

Pre-fixed combinations (no data choice):
- V30 PKG + V13 + V17 + V23.   V31 PKG + V14 + V19 + V27.   V32 max 1/day + V13 + V21.
- V09 (combined book only): V01 with the app's guard (below) vs V01 without; all combined runs use the guard.

Combined book: the four arms as the app runs them, with the app's AutoExposure guard (06 Oct): no automatic entry
against an open opposite-side position on the same index and at most one position per index per side; arms processed in
time order (ties: ORB, Fresh, Sweep, Fade). Liquidity additivity: the same with Liquidity 15+5 (h19 port, current 1-ITM
rules) BN positions also in the guard (Liquidity's own trades fixed; a Liquidity trade blocked by an old-arm position
is dropped and counted); FINNIFTY Liquidity trades added unchanged.

## Selection and tests
- LOCKED HOLDOUT 2025-10-01 .. latest: not looked at until the end; run ONCE for the per-arm and combined picks.
- Choice sample: < 2025-10-01. Pick per arm (and combined) = best pre-holdout net (app costs + impact kappa 0.02).
- Anchored walk-forward by year (test 2023, 2024, 2025-to-Sep; train = all earlier years): pick the best variant on the
  train years, record the test year's net.
- Random entries with identical rules: per real candidate signal 5 alternatives on the same day / arm, a uniform random
  5-min decision bar in the arm's window, coin-flip side, the same strike (the day's 09:20 ATM), the same exits;
  p = obuy `random_baseline` on the kept trades (mean net per trade).
- Multiple testing: BH over all 33 x 5 (4 arms + combined) = 165 trials; Hansen SPA / White RC on the daily-P&L matrix
  per arm (33 variants) and over all 165.
- PROMOTION (all required, pre-holdout): net > 0 after costs+impact; random-entry BH q < 0.05; walk-forward OOS net > 0
  with >= 2 of 3 test years positive; SPA p < 0.10. A promoted pick must also be net > 0 in the holdout to be
  recommended for the app. Additive to Liquidity = raises both total net and daily Sharpe of (Liquidity + arm) before
  the holdout AND in the holdout.
- Reported: per year, worst day / month, max drawdown, P(losing month) (block bootstrap of days), Rs/day at 1 lot.
