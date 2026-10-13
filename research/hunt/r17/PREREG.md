# R17 pre-registration: the pre-trade checks (TODO I3 charges check, I5 smart stop, I4 danger meter)

Written 13 Oct 2026, BEFORE any result of these tests was computed. This file is committed on its own before the
first run; the commit hash is quoted in the write-up (`research/HUNT_R17_PRETRADE.md`). Nothing below may change after
that commit. A change needs a new entry, a new pre-registration and a fresh holdout.

Arms: every arm the app trades on PAPER except Liquidity (all Liquidity arms are out: Liquidity BANKNIFTY is frozen
until 26 Oct and the others share its rules). Night (R3) is out too: it holds overnight, so a 30-60 minute range says
nothing about its risk, and its breadth input (F&O stocks) is not in the release data. Gold / MCX paper arms are out:
no MCX data in the release.

| arm | rules simulated (the app's, 13 Oct) | index |
|---|---|---|
| ORB | OrbRules, 1 entry a side a day (R14 paper cap), -40 / +40 premium points, profit-lock ladder, 15:10 | BANKNIFTY |
| ORB Fresh | the same with "fresh" breaks only (with the cap of 1 it takes the same trades as ORB; reported, not double-counted in tests) | BANKNIFTY |
| ORB Sweep | SweepRules, at most 2 a day, -40 / +80, ladder on +80 | BANKNIFTY |
| Range Fade | RangeFadeRules, 10:30-13:55 bars, at most 2 a day, -40 / +40, ladder | BANKNIFTY |
| VIX divergence | VixDivRules, 1-ITM, BANKNIFTY to 15:10, NIFTY -15% stop on the minute low + 20-minute time stop | both |
| Hero | HeroRules, NIFTY expiry days only, OTM Rs 1-5, half at 5x, rest at 20x / 15:05, -60% stop on a minute close | NIFTY |

## Data, costs, splits (shared)

- Source: one switch, `R17_SOURCE` = `release` (default: the `research-data` release, BANKNIFTY 13 Feb 2024 - 23 Feb
  2026, NIFTY 1 Apr 2024 - 13 Apr 2026, NIFTY expiry-day chains 9 Apr 2025 - 13 Apr 2026) or `lake` (the 6-year Dhan
  lake once it is published; read through `research/brain/adapters`). Nothing else changes between the two.
- Options: the release holds the NEAREST expiry AFTER the day (expiry-day chains only in `expiry_days.parquet`), so the
  ORB family and VIX divergence trade the next expiry on expiry days (the app trades today's). Stated, not fixed.
- Fills: the app's paper fills (+-5 bps, stops -10 bps) plus a half-spread of 1 tick when the contract traded >= 20 lots
  in the 5 minutes before, 2 ticks if less, 4 if nothing (stops: x2) (research/obuy/costs.py `Fills(mode="liq")`).
- Charges: the app's model at today's rates (Rs 20 an order, STT 0.15% of the sell, exchange 0.03553%, SEBI, stamp
  0.003% of the buy, GST 18%) (`Costs(mode="app")`). Lot sizes: the NSE lot in force that day (obuy LOT_SCHEDULE).
  1 lot a trade (Hero: its Rs 5,000 budget).
- Holdout (locked, read ONCE for the whole of R17, after the picks below are frozen in `results/picks.json`):
  BANKNIFTY sessions from 25 Aug 2025, NIFTY sessions from 13 Oct 2025 (the same cut dates as R16). Design = all
  earlier sessions. On the lake the cuts become "the last 12 months" of each index (ARCHITECTURE.md L6).

## The expected range (input to all three)

E30 / E60 = the R16 4-input linear model of the next 30 / 60 minutes' log(high - low) / price: inputs `tod` (mean log
range of the same clock window over the previous 20 sessions), log range of the last 30 bars, log range of the last 60
bars (or since 09:15 if fewer), log India VIX previous close. In the research the coefficients are REFIT walk-forward:
each calendar month is predicted by OLS on rows (every 15 minutes, 09:45-14:45) of all earlier sessions with a
5-session embargo; the first 60 sessions are warm-up (no prediction, no trade counted). The app uses R16's published
design coefficients. E is in index points = exp(prediction) x price.

Premium sensitivity: delta from Black-Scholes with sigma = VIX previous close / 100, T to 15:30 on the expiry, r 6.5%.
Expected premium move = E30 x |delta|.

## I3: charges check

Cost ratio X = (round-trip charges + modelled slippage of both fills, per unit) / (E30 x |delta|), at the entry minute.

Hypothesis: trades with a larger X lose more after costs; a "skip when X > X*" rule removes trades that are worse than
random ones.

- Grid: X* in {10, 15, 20, 25, 30, 40, 50}% (7 variants). Pooled over the arms above (ORB Fresh not double-counted).
- Pick on DESIGN: the X* with the largest total net gain from skipping (= minus the summed net of skipped trades),
  among those that skip >= 30 design trades. Ties: the larger X* (skips less).
- Holdout test (one): the skipped trades' mean net < the kept trades' mean net (one-sided Welch t), AND the skip beats
  1,000 random twins (skip the same number of trades at random within each arm) at p < 0.05.
- Descriptive, both periods: net per trade by X fifth for each arm ("the threshold at which trades lose").
- App: SHADOW only, behind a switch that is off; it logs "costs take X% of the expected move" and "would skip" at X*.

## I5: smart stop-loss

Smart stop (premium points) = k x E30 x |delta| at the entry minute, floored at 2 ticks. Two forms:
- `smart`: replaces the fixed stop (it may be wider than the fixed one);
- `tight`: min(fixed, smart): only ever tighter (the only form that could ever be automatic).
- Grid: k in {0.5, 0.75, 1.0, 1.25, 1.5} x 2 forms = 10 variants per arm.
- Applies to: ORB, ORB Sweep, Range Fade (fixed 40), VIX divergence NIFTY (fixed 15% of premium) and BANKNIFTY (no stop
  today: `smart` and `tight` are the same, a new stop), Hero (fixed 60% of premium, on the minute close as its own stop).
  The target, ladder, time stops and square-off do not change. Entries do not change (the same trades).
- Pick per arm on DESIGN: the variant with the best total net; it must beat the fixed stop on design by a paired
  one-sided t on daily nets at p < 0.10, else the pick is "fixed" (no holdout test for that arm).
- Holdout test per picked arm: paired one-sided t on daily nets (picked minus fixed), p < 0.05 after BH.
- App: SHADOW line per trade: "smart stop would be N points; fixed stop is M", with k = 1.0 shown unless a pick is made.

## I4: daily danger meter

Day = the sum of all the arms' net P&L that session (1 lot each, ORB Fresh counted, as the paper account sees it).
Features known by 09:16: gap (09:15 open vs previous close, % of price), India VIX previous close, VIX change
(previous close vs the close before), event day (calendar_data/events.csv: budget, RBI), expiry day of either index,
day of week. No global cues: none are in the data (stated).

Buckets (cut points from the trailing 120 sessions only): |gap| terciles; VIX level terciles; VIX change: up > 5%,
down < -5%, else flat; event yes/no; expiry yes/no; weekday (5).

Model, walk-forward monthly: for each feature, each bucket's mean day net over all earlier sessions minus the overall
mean, shrunk by n / (n + 20); score = the sum over features. "Careful" = score in the lowest 20% of earlier days' scores;
"paper-only" = lowest 5%; else "normal". Warm-up 120 sessions.

- Holdout test (one): careful + paper-only days have a lower mean day net than normal days (one-sided Welch t), AND
  halving the arms on those days beats halving the same number of random days in >= 95% of 1,000 twins.
- Shown to Boss with the bucket's sample size, as advice. A switch (off) may set careful days to half size on PAPER
  arms only (never live, never Liquidity).

## Multiple testing

One Benjamini-Hochberg family over every R17 holdout test actually run (1 for I3, up to 6 for I5, 1 for I4), q = 0.05.
Every variant tried is logged in `research/experiments/entries/r17*.yaml`, failures included.
