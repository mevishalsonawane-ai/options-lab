# x2 pre-registration: combined rules built from the cross-study map (option BUYING only)

Written 2026-10-08 BEFORE any P&L of these rules was computed. Nothing below was chosen with holdout data.
Author: X2 (cross-study synthesiser). Code: `research/hunt/x2/run.py` (`pre`, then `hold` once). Logs:
`scratchpad/hunt/x2/`.

## Frame (same as the hunt)
- Option BUYING only, fixed 1 lot per index (lot in force on the date), Rs 1,00,000 capital, no Kelly, no adding.
- Capital check in every rule: a signal is skipped if the premium of open positions plus the new one exceeds equity
  (Rs 1 lakh + realised P&L of the period). Equity < Rs 25k = ruin (stop).
- Costs: app fills (+5 bps buy / -5 bps sell / -10 bps stop), app charges (`Costs('app')`, STT 0.15%), the h24 real
  half-spread on entry and exit (NIFTY 0.16%, BANKNIFTY 0.16%, FINNIFTY 0.42%, MIDCPNIFTY 0.21%, SENSEX 0.20%),
  h10 impact kappa 0.02 on the Liquidity legs. STRESS = 1.5x spread.
- Exits on the option's 1-minute HIGH/LOW (resting stop first in a tie), as in the source tables.
- PRE = data start .. 2025-09-30 (all choosing). HOLDOUT = 2025-10-01 .. latest, run ONCE by `run.py hold`, which
  writes a flag file and refuses a second run.

## Source outcome tables (built and validated by earlier studies; no new simulation of entries/exits)
- Liquidity 15+5 trades, 5 indices, with 41 signal-time features: h44 `trades44.parquet` + `feats44.parquet`
  (the h24/h36 real-spread, kappa 0.02, 1-lot table; BN reproduces h36 Plan A, Rs 65.5/day PRE).
- Overnight 1-ITM CE and PE for EVERY night, contract M/W, exit X: h31 `trades.parquet` + `feat.parquet`.
- Day-end option OI build-up: h26 `opt_<U>.npz`, feature BUopen at the 15:19 column (364), z-scored against the
  same column over the previous 60 sessions (shift 1) = `buz`.

## Descriptive steps done before this file (index level, PRE only, no option P&L) - disclosed
- D0 (`d0_desc.py`): next overnight move (15:19 -> next 09:15 open) vs signals known by 15:19.
  - h40 evening positioning lagged so it is known at 15:20 (S01 S02 S06 S07 S08 S09 S12 S15): |rho| <= 0.04, all
    p > 0.1 on NIFTY and BANKNIFTY. h40's FII signals predict the gap that follows THEIR OWN publication (rho 0.1-0.3,
    h40 Part A), which no buyer can enter (no evening session). So FII data is NOT used in any rule.
  - Day-end option OI build-up buz: rho +0.10 (NIFTY, p 0.0003), +0.15 (BN, p 1e-6); partial on (day move, close
    location): +0.059 (p 0.037) NIFTY, +0.043 (p 0.17) BN. Small but same sign -> used as one stacking vote.
  - (A first run read the 13:19 column by mistake; it is superseded by the 15:19 run, both logged.)
- D1 (`d1_desc.py`): h18 60-min break continuation is NOT larger on "active" days (prev day |move| >= 1% or
  |gap| >= 0.5%): BN +1.5 bp/30 min active vs +1.6 other; NIFTY +2.2 vs +2.1. Absolute moves are bigger but the
  directional part is not. The planned rule "break continuation in active regimes" is therefore DROPPED before any P&L
  (a 2 bp edge cannot pay an 18 bp option round trip in any regime).

## The 5 rules

### R1 LIQ-BN-OIVETO (stack: h4/h36 Liquidity edge + h26 OI-against)
BANKNIFTY Liquidity 15+5 (15m+5m books, 1-ITM nearest, h14 limit entry, app exits = h36 Plan A). Skip a signal when
the option OI build-up opposes it: `bu15_dir <= -1` OR `doipc5_dir <= -1` (h26 z-features signed toward the trade,
read at the signal minute).
Why (PRE evidence only): h26 PRE filter table, all 5 indices: BU15 skip-opposed +28.5 Rs/day (p 0.010, both halves
positive), dOIpc5 skip-opposed +16.2 (p 0.011), dOIpc2 +12.7 (p 0.059); h26 IC of dOIpc ~0.03 (t 4.5).
Baseline: random skipping of the same number of BN trades (B = 5000).

### R2 LIQ-5IX-GATE (stack: Liquidity + h12 per-trade lifts + h44/h24 cost-avoidance on thin indices)
BANKNIFTY Liquidity: every trade. NIFTY, FINNIFTY, MIDCPNIFTY, SENSEX Liquidity: take a trade only if
(a) quality score >= 2 of {side = PE; room >= 6 index stops (room_atr / stop_atr, no level = infinite);
|gap_dir| >= 0.3 ATR; India VIX up on the day (vix_chg > 0)} AND (b) not OI-against (R1's veto condition false).
Why: h12 PRE per-trade uplifts that beat its random-entry control (puts +80, room>=6 +137, large gap +89, VIX up +70
Rs/lot-trade); h44 PRE walk-forward gain came from skipping low-quality trades on the thin indices; h24 BN is the only
index whose spread does not eat the edge; h26 OI veto as in R1.
Baseline: BN trades as is + for the other indices a random subset of the same size per index (B = 5000).

### R3 NIGHT-STACK (stack: h31 strong close + h26 OI build-up; exit matched to h40/h27 "information is in the gap")
NIFTY (weekly contract, K = W) and BANKNIFTY (monthly, K = M), 1-ITM, decide on the 15:19 close, buy at the 15:20
open, sell at the next session's 09:16 open (X0916). Side s = h31 R17 "all agree" (close location >= 0.6, day up,
breadth >= 50% -> CE; mirror -> PE), traded only when the day-end OI build-up agrees: s x buz >= 0.5.
Never held into the contract's expiry day, no entry on an expiry day (h31's table rules).
Why: h31 PRE overnight move after strong days +0.17% (t 8.1 NIFTY), R17 beats coin-flip side at BH q < 0.001;
D0 OI partial; h40 Part A and h27: the information is priced by 09:15, so exit at the first minute.
Baseline: coin-flip side on the same nights (both sides are in the table; B = 5000).

### R4 NIGHT-CE-VOTE (stack: positive overnight drift vs negative intraday drift, h28 x h31 + h28 FOMC gap + h26 OI)
Same contracts, entry and exit as R3, CALLS ONLY. Buy the CE when at least 2 of 4 votes hold:
close location >= 0.75; breadth >= 0.6; buz >= 0.5; tonight is an FOMC or US-CPI night (h28 calendars, as in h31).
Why: h28 intraday drift negative and gains come overnight; h31 R04 strong-close CE median +174/trade PRE, R15/R16
event-night CE +262/+413 median; h28 post-FOMC gap +0.36% vs +0.10% (BN, q 0.03).
Baseline: CE on the same number of random nights per index from all eligible nights (B = 5000).

### R5 LIQ-BN-ASYM (stack: h8/h12 short-side edge + h28 negative intraday drift + h26 OI)
BANKNIFTY Liquidity (as R1 base): take every PE signal; take a CE signal only if the OI build-up confirms it,
`bu15_dir >= +1`.
Why: h8 the arm's money is on the short side (+5.3 bp vs +2.0 bp longs below random drift); h12 PRE puts +80 vs
calls; h28 negative intraday drift; h26 BU15 agreeing has positive IC.
Baseline: random skipping of the same number of BN CE trades (B = 5000).

## Tests (PRE), all five counted together
- Daily net series per rule over its trading calendar (zero on no-trade days; multi-index rules summed per day).
- Mean daily net > 0: one-sided t-test p; BH over the 5; Hansen SPA and White RC over the 5 (obuy.overfit.spa,
  stationary bootstrap, mean block 5, B = 2000).
- Random baseline p as declared per rule.
- Walk-forward by calendar year (rules are fixed, so this is per-year out-of-sample-style reporting), plus the
  anchored policy "trade the rule in year Y only if its net on all years before Y is > 0".
- Also reported: gross, net at 1.5x spread, Rs/day at 1 lot, lots for Rs 5,000/day, max drawdown, worst day / month,
  P(losing month) by bootstrap of months, Rs 1 lakh walk (ruin, end equity), excess over the unfiltered base for
  R1/R2/R5.
- PASS (all needed): BH q <= 0.10; baseline p <= 0.05; net > 0 at 1.5x spread; positive in >= 60% of calendar years
  with trades; for R1/R2/R5 also excess over the base > 0 in PRE.

## Holdout
`run.py hold` runs all 5 rules once on 2025-10-01 .. latest with the same code and reports PASS rules as the test and
non-PASS rules as information only. No rule, threshold, index or exit may change after this file.
