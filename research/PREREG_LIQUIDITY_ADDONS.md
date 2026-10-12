# Pre-registered SHADOW tests: add-ons to Liquidity 15+5 BANKNIFTY

Written 12 Oct 2026, before any shadow data exists. Frozen: no rule, parameter, threshold or success bar below may
change once recording starts. A change = a new rule with a new start date and a new entry in the family.

## What "shadow" means here
The app keeps trading Liquidity 15+5 exactly as today (paper, or live only with Boss's PIN). For every BANKNIFTY
Liquidity signal it only RECORDS what each add-on would have done. No add-on ever places, skips or sizes an order.
All three can only remove trades (lower risk); none adds size.

## Where the candidates come from (numbers already published; nothing re-chosen)

| id | rule (source) | before the holdout | in the locked holdout | status |
|---|---|---|---|---|
| A1 | OI veto (x2 R1) | +Rs 10/day over plain BN, p 0.128, BH q 0.10: FAILED its bar | +Rs 37/day over plain BN (+205 vs +167), baseline p 0.054 | best locked-year filter that is pure logic |
| A2 | calls need OI, puts always (x2 R5) | +Rs 17.5/day, p 0.031, q 0.10: FAILED (q) | +Rs 67/day (+235 vs +167), p 0.233 | puts carry the arm (h8, h12, R15: puts +228 / calls -60 a trade) |
| A3 | Night R3, BANKNIFTY leg only (x2 R3) | BN +Rs 152k on 332 trades (2020-Sep 2025) | BN +Rs 45.9k (NIFTY leg -8.5k) | already in the app as paper "Night (R3)" on both indices |
| - | R15 daily filters (VIX, range, PCR, futures OI, after-drop) | 0 of 5 passed | - | NOT added (HUNT_R15_DAILY_FILTERS.md) |
| - | x2 R2 (5-index gate) | failed PRE | +415/day, mostly MIDCP fills | not BN-only; out of scope |

## Exact rules (engine code: `android/engine/.../orb/LiquidityAddons.kt`, `LiquidityAddonParams`, default OFF)

Features, BANKNIFTY nearest-expiry chain, read on the 1-minute chain at the signal minute t (the completed bar's
last minute), strikes fixed from the ATM at t (round(index / 100) x 100), compared with minute t - 15:
- `BU15` = sum over ATM +- 2 of [sign(call price change) x |call OI change| - sign(put price change) x |put OI
  change|] / sum over ATM +- 2 of (call OI + put OI) at t. (`LiquidityAddons.bu15`, = h26 `day_feats` BU15.)
- `dOIpc5` = sum over ATM +- 5 of (put OI change - call OI change) / sum of (call + put OI) at t. (`doiPc`.)
- Each z-scored against the previous 60 sessions: per session, the mean and mean square of the feature's 5-minute
  samples 09:30 .. 14:30; mu = mean of means, sd = sqrt(mean of mean squares - mu^2); at least 20 sessions, else
  unknown. (`moments`, `z`; = h26 `trail_z`.) The app starts with no history: the first 20 recording sessions build it,
  and signals before that are "unknown".
- Signed toward the trade: side = +1 (CE) / -1 (PE), signed z = side x z.

- **A1 OI veto:** would skip when signed BU15 z <= -1 OR signed dOIpc5 z <= -1. Unknown z never skips.
- **A2 calls need OI:** puts always taken; a call taken only when signed BU15 z >= +1. Unknown z: call not taken.
- **A3 Night BN:** `NightRules` exactly as in the app, judged on its BANKNIFTY trades only (NIFTY trades ignored for
  this test). Additionally record, at 15:20 and at 09:16, the price of the OPPOSITE 1-ITM option (the coin-flip
  baseline), never traded.

## Measurement
- Per lot, real fills: the paper fill plus the measured BANKNIFTY half-spread (0.16% a side until the app records real
  bid/ask, then the recorded one); charges = the app's SandboxCosts (Zerodha Rs 20 an order, STT 0.15% of sells,
  exchange 0.03503% + IPFT, SEBI, stamp 0.003%, GST 18%).
- A1/A2 statistic: Rs saved per Liquidity BANKNIFTY trade = -(sum of net of trades the add-on would skip) / all trades.
  p = `LiquidityAddons.judge` (random skipping of the same number of trades, 5,000 draws, seed 15, one-sided).
- A3 statistic: mean net per BN night trade; p = one-sided t-test of mean > 0; baseline p = share of 5,000 coin-flip
  side assignments (from the recorded opposite leg) whose mean is >= the rule's.
- Combined evidence for A1/A2 (fixed now): Stouffer z = (z_holdout + z_shadow) / sqrt(2), with z_holdout from the x2
  holdout baseline p already published (A1 p 0.054 -> z 1.61; A2 p 0.233 -> z 0.73). A3: z_holdout from x2's R3
  coin-flip p 0.051 -> z 1.64 (both legs; the BN-only coin-flip p was not published, so this is generous).
- Family: {A1, A2, A3}, Holm (`LiquidityAddons.holm`).

## Minimums, success bar, stops

| | A1 / A2 | A3 |
|---|---|---|
| judged at | 300 BANKNIFTY Liquidity trades (about 6-8 months at 1.9 a day) and >= 40 would-skip trades | 100 BN night trades (about 1.5 years) |
| PASS (all) | Holm-adjusted combined p <= 0.05; shadow saving > 0; saving > 0 in both halves of the shadow sample (by date) | Holm-adjusted combined p <= 0.05; mean > 0 in both halves; coin-flip baseline p <= 0.10; max drawdown of the BN night book <= Rs 40k a lot |
| early harm stop | at 150 trades, saving < -Rs 40 a trade -> rejected | at 50 trades, mean < -Rs 300 a trade -> rejected |
| final stop | 600 trades without a PASS -> rejected | 200 trades without a PASS -> rejected |

A PASS lets Boss CHOOSE to apply the filter to paper first, then live with the PIN. Nothing switches automatically.

## Honest expectations (power)
- Per-trade sd of the arm is about Rs 1,700-2,000 (R15). With ~30% of trades skipped, the shadow saving's standard error
  at 300 trades is about Rs 55-60 a trade. A1's expected saving (+Rs 30-40 a trade from the holdout) is below that:
  **the shadow alone cannot prove A1 within a year; it can only catch harm.** The combined (holdout + shadow) test is
  the realistic path, and even it will probably need the full 600 trades.
- A2 skips most calls (calls were -Rs 60 a trade in R15's design years, -Rs 29 in its holdout part, +Rs 134 in Jan-Feb
  2026): expected saving +Rs 20-60 a trade, also near the noise floor.
- A3 is a separate book with its own risk: the X2 holdout drawdown was -Rs 84k on both legs. Paper only.

## Integration notes (for the coordinating session; not done here because another session owns the Liquidity app code)
At each BANKNIFTY Liquidity signal, record: side, signal minute, the chain (LTP, OI) at t and t - 15 for ATM +- 5,
`bu15`, `doiPc`, their z (with the saved per-session `Moments`), and `LiquidityAddons.decide` for A1 and A2 with
`LiquidityAddonParams(oiVeto = true)` / `(callsNeedOi = true)`. Store the verdict on the trade like
`LiquidityShadow.Trade` does for (a)-(f), and judge with `LiquidityAddons.judge` + `holm`. The trading path keeps
`LiquidityAddonParams.OFF`.
