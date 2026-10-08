# h38 pre-registration: index FUTURES price + OI, volume, basis (written before any P&L was computed)

Date: 2026-10-08. Hunter h38. Option BUYING only, 1 lot, 1-ITM nearest expiry. Uses h26's machinery (obuy Lab,
measured half-spreads from h24, app charges, same-exit random baseline, BH / SPA, walk-forward).

## What data exists (established before this plan; see HUNT_H38.md "Data")
- Dhan v2 (probed 2026-10-08 with the fresh token): `/charts/intraday` returns 1-minute bars with OI ONLY for live
  contracts and only from their listing date. Expired futures security IDs (taken from NSE bhavcopy FinInstrmId, which
  equals Dhan's NSE_FNO securityId) return 0 bars; a live ID asked for dates before its listing returns 0 bars;
  `expiryCode` on intraday and `FUTIDX` on `/charts/rollingoption` return nothing. `/charts/historical` (DAILY) on a live
  futures ID returns a continuous near-month daily series with OI back to 2020. Full-market depth (20 / 200 level) is a
  live websocket only; there is no historical depth endpoint.
- So 1-minute index-futures OI exists ONLY for 2026-07-29 .. 2026-10-06 (the 2026-10 contract; the 2026-09 contract was
  never fetched while live and is now unobtainable). That whole window is INSIDE the locked holdout.
- NSE F&O daily bhavcopy 2020-01 .. 2026-10-07 (public archive, fetched by `fetch_bhav.py`): every index-futures contract's
  daily OHLC / settle / contracts / OI and every index option's OI by expiry and side. SENSEX / BANKEX futures are
  excluded: BSE bhavcopy shows ~1,600 SENSEX futures contracts and ~800 lots of OI per day, ~10 BANKEX contracts — no
  meaningful OI signal.
- Synthetic futures (put-call parity) from the existing Dhan option minutes: F = median over ATM±2 strikes of
  K + C − P (only strikes whose CE and PE both printed in the last 3 minutes); basis = F/spot − 1 in bps.

## Periods
- CHOICE window: 2020-01-01 .. 2025-09-30. LOCKED HOLDOUT: 2025-10-01 .. 2026-10-06, run once at the end.
- Part B (minute futures OI) can only be run in 2026-07-29 .. 2026-10-06. It is run ONCE with every setting fixed here,
  reported as information. Nothing from Part B can be adopted (no choice-window evidence exists).

## Part A — triggers testable on the choice window (all 18 exits each)
Intraday (minute t uses bars <= t; z = against mean / std of the feature's 5-minute samples 09:30-14:30 over the previous
60 trading days, as h26):
- BASIS (synthetic futures premium change): x = basis_bps(t) − basis_bps(t − L), L ∈ {5, 15, 30, since 09:20}; state +1 if
  z ≥ 2, −1 if z ≤ −2. Conventional sign (premium widening = bullish) and reversed. 8 settings × 5 indices (NIFTY,
  BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX).
Daily (from the bhavcopy; known before the next morning; one signal per day at the 09:30 bar close, entry 09:31 open):
- DBU (previous day's futures build-up): dP = near-month settle change, dOI = change of OI summed over all expiries
  (roll-neutral), dOI z vs the previous 60 days. Classes: long build-up (dP>0, dOI>0) +1, short build-up (dP<0, dOI>0) −1,
  short covering (dP>0, dOI<0) +1, long unwinding (dP<0, dOI<0) −1. Settings: "BU only" (build-up classes with dOI z ≥ 1)
  and "all four classes" (|dOI z| ≥ 1). Conventional sign and reversed. 4 settings × 4 NSE indices.
- DVOL (futures volume spike with direction): previous day's futures contracts (all expiries) / its previous-20-day
  median ≥ k, k ∈ {1.5, 2.0}; side = sign of that day's dP. Conventional and reversed. 4 × 4.
- DDIS (futures OI vs option OI): s_f = sign(dP) if dOI_fut > 0 else 0 (fresh futures positions' direction);
  s_o = sign(dOI_PE − dOI_CE), nearest option expiry, all strikes (put writing = bullish). Settings: "agree" (s_f = s_o ≠ 0,
  trade that side), "disagree, follow futures", "disagree, follow options". 3 × 4.
- DBAS (daily basis change): basis = near-month futures close / index close − 1; x = today − yesterday, z vs previous 60
  days, threshold z ∈ {1, 2}; conventional (widening = bullish) and reversed. 4 × 4.
- Part A variants: (40 + 16 + 16 + 12 + 16) settings × 18 exits = 1,800.

## Part B — minute futures OI (holdout window only, information only)
Futures = the 2026-10 contract (the only continuous one; next-month until 2026-09-29, then near). Indices: NIFTY,
BANKNIFTY, MIDCPNIFTY, SENSEX (FINNIFTY's contract has only 11 days of bars). z normalisers use EXPANDING previous days
in the window (min 10 days), so ~38 tradable days.
- FBU (futures build-up): over L ∈ {5, 15, 30, since open}: score = dOI/OI with classes as DBU; "BU only" (|z| ≥ 2 on
  dOI/OI with dOI > 0) or "all four" (|z| ≥ 2). Conventional sign only. 8 settings.
- FVOL: 5-minute futures volume ≥ k × the median of the same column over previous days, k ∈ {3, 5}; side = sign of the
  5-minute futures price change. Conventional and reversed. 4 settings.
- FBAS: real futures basis (F − spot, bps) change over 15 min, |z| ≥ 2. Conventional and reversed. 2 settings.
- FDIS: 15-min futures build-up sign (as FBU, |z| ≥ 1) agrees with h26's option dOIpc b5 sign (|z| ≥ 1) -> trade it. 1.
- 15 settings × 4 indices × 18 exits = 1,080 variants, all counted; BH over them; random baseline. No adoption possible.
- Also descriptive: correlation of 15-min changes of the real futures basis and the synthetic basis (data check only).

## Triggers -> trades (Part A and B)
- Intraday events: state turns non-zero or flips, 09:30-14:30, ≥ 30 min between events, ≤ 3 trades/day/(setting, index),
  one position at a time. Daily triggers: at most one trade per day.
- Entry 1-ITM nearest expiry (StrikeRule(money=1)), next minute's OPEN, expiry days skipped.
- Exits, FIXED (18, identical to h26): X0 Liquidity arm; X1 −20/+20 premium points; X2 −15%/+30%; X3 −15%/+30% + profit-lock
  ladder; X4/X5/X6 15/30/60-minute time stops with −15% stop; X7..X17 targets +15/+20/+25/+30 × stops −10/−15/−20 premium
  points. All stops / targets checked on the option's 1-minute HIGH / LOW, stop first on ties. Square-off 15:10.

## Costs
App fills + measured real half-spread (h24): BANKNIFTY 0.16%, NIFTY 0.16%, MIDCPNIFTY 0.21%, FINNIFTY 0.42%, SENSEX
0.16%; stops +5 bps. Charges Costs('app'). Stress 1.5× spread. Gross (bar prints, no spread / charges) always shown.

## Statistics and selection (Part A, choice window only)
- obuy Lab: random-entry baseline with the same exits (10 per signal, random minute 09:30-14:30 — for daily triggers the
  baseline is matched by time of day: random side at the same 09:30 entry minute on other days is NOT used; the Lab's
  same-day random minute is used, and in addition I report a same-minute coin-flip baseline for daily triggers), BH / Holm
  over all variants, union White RC and Hansen SPA, anchored walk-forward by year (2 training years), DSR, PBO.
- SURVIVOR = Lab gates G1-G4 pass + variant BH q < 0.10 (over all 1,800) + net > 0 at 1.5× spread.
- Holdout: survivors once. If none, the best family by walk-forward net per trigger type is run once FOR INFORMATION.

## Part C — filters on Liquidity 15+5 (h4 trades, all 5 indices, gross prints + app charges + real spread)
Features signed toward the trade, read at the signal minute's close (daily ones: previous day): BASIS5/15/30/open z
(threshold 1), DBU class (all-four version, ±1), DVOL (±1 when spike), DDIS agree (+1) / disagree with the trade side
per futures (−1), DBAS z (threshold 1). Rules "skip if opposes" and "skip if agrees": 8 × 2 = 16 filters. Score:
change in net Rs/day; p vs random skip of the same count (2,000 draws); BH over 16. ADOPT if BH q < 0.10, positive in
both halves (2020-23, 2024-25.09) and at 1.5× spread. Adopted -> holdout once; none -> the best by choice window is shown
once for information. Part B features (FBU15, FBUopen, FVOL, FBAS, FDIS, "skip if opposes") are applied to the
holdout-window Liquidity trades once, information only.

## Report
Rs/day 1 lot gross / net / 1.5× spread, lots for Rs 5,000/day, the Rs 1 lakh view (fixed lots by premium), per year,
worst day / month, max DD, P(losing month), and the honest variant count (Part A 1,800 + Part B 1,080 + 16 filters).

## AMENDMENT 1 (2026-10-08, before any P&L; event counts only were looked at)
Part B normalisers: the 2026-10 contract was young in Aug (OI grows from 0 to ~18M units), so EXPANDING-window z
scores were dominated by the first weeks and FBU fired 0-7 times in 48 days. Changed to a ROLLING window of the previous
10 days (min 5) for the FBU z, the FVOL median and the FBAS z. Event counts after the change (NIFTY): FBU 10-63, FVOL
196-274, FBAS 161, FDIS 50. No P&L had been computed.
