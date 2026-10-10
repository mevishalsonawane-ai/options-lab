# h18 pre-registration (written before any h18 outcome was computed)

Question: do the mechanisms the web literature names as "what moves intraday index prices" leave a footprint in our
data that an option BUYER can use? Only option buying in any rule.

Data: NIFTY, BANKNIFTY, FINNIFTY, SENSEX, MIDCPNIFTY index minutes (obuy loaders) and the nearest-expiry ATM±10 option
chain with OI and IV (obuy `Options.chain(day, 'near')`), India VIX. Days with synthetic index bars (`real` False) are
dropped.

Split: CHOICE window = all days < 2025-10-01. HOLDOUT = 2025-10-01 .. latest, used once at the end for the rule(s)
that survive the choice window, nothing else.

## Tests (all on the index, at 5-minute decision points 09:30 .. 14:30 unless stated)

T1 Dealer gamma (GEX). At each point, from the chain's OI and per-strike IV (OTM side IV for both rights; ATM IV / VIX
   fallback), Black-Scholes gamma, T = calendar time to the near expiry (expiry date = next day the index flags as an
   expiry day), floored at 5 minutes.
   - Convention A ("street"/SpotGamma): dealers long calls, short puts: GEX_A = sum(gC*OI_C) - sum(gP*OI_P), x S^2 x 1%.
   - Convention B (the brief's: dealers short what the public buys; the public is net long both): GEX_B = -sum(g*OI),
     always <= 0; only its SIZE varies. Feature: log(|GEX_B| / its median at the same time of day over the previous
     20 trading days).
   - A's feature: sign and the normalised share GEX_A / |GEX_B| in [-1, 1]; also the gamma-flip distance (spot move
     in % at which GEX_A changes sign, searched within ±2%).
   Outcomes: next 15 / 30 / 60 min realised range (high-low)/spot, and the next 30 / 60 min return regressed on the
   past 30 min return (trend vs reversion = slope sign/size), split by GEX regime.
   Controls for the range test: log past-30-min range, log VIX (prev close), days-to-expiry bucket, time-of-day bucket,
   underlying. Inference: day-clustered standard errors. "Signal" = incremental t >= 3 in the choice window with the
   theoretically predicted sign (A: positive GEX -> smaller range, more reversion; B: bigger |GEX_B| -> larger range,
   more momentum), same sign in every underlying with >= 300 days.

T2 Pinning. Expiry days only (the near series' own expiry). At 13:30, K* = max total-OI strike, max-pain strike,
   and (folklore) max-call-OI as resistance / max-put-OI as support. Outcome: signed drift toward K* from 13:30 to the
   15:29 close in % of spot; |close - K*| vs |spot13:30 - K*|; and the share of expiry closes within 10% of a strike
   step of a strike vs non-expiry days (Ni-Pearson-Poteshman style). Baseline for the drift: the same statistic with K*
   replaced by the nearest strike in the opposite direction (a symmetric placebo) and with the ATM strike.

T3 Expiry-morning push then reversal (the SEBI Jane Street pattern: buy constituents/futures 09:15-11:46, sell after).
   Correlation of the 09:15->11:45 return with the 11:45->15:29 return, expiry days vs non-expiry days, BANKNIFTY and
   NIFTY, by period (2023-01..2025-03 the period SEBI examined; before; after the 2025-07-03 order). Also the
   conditional mean of the afternoon return after |morning| > 0.5%.

T4 Liquidity sweeps. Levels: prior-day high/low, the 09:15-09:45 opening range high/low, and the rolling 60-min
   high/low (all known before the event). Sweep = a 1-min high trades above the level by 0 < x <= 0.10% (low: below)
   and the same or a later bar within 5 min CLOSES back inside. Break = a 1-min CLOSE beyond the level by > 0.05%.
   Outcome: the index return over the next 15 / 30 / 60 min in the reversal direction (sweeps) / continuation direction
   (breaks), against the unconditional mean of random 1-min points with the same side mix. First event per level per
   day only.

## From signal to rule

Only for a test whose index footprint passes in the choice window: one simple option-BUYING rule (1-ITM nearest
expiry, obuy engine fills/charges with dated STT, premium stop / time stop / square-off fixed a priori to the Liquidity
arm's: -15% stop, 20 min not +5% -> out, 15:10). Every parameter variant counts. Random entries with identical exits
(obuy same-exit baseline), BH across all variants, SPA on the daily P&L matrix, h10's square-root impact at
kappa 0.01 / 0.02 / 0.04, sizing on Rs 1 lakh (one third of equity per trade, as h16). Also: GEX regime and sweep
features tested as FILTERS on Liquidity 15+5's existing trades (h4 trades_real.parquet), choosing on pre-holdout only;
a filter is adopted only if it raises pre-holdout net per day in both the 2021-23 and 2024-25 halves and the dropped
trades are net negative in both.
