# h32 pre-registration: work BACKWARDS from big option jumps (option buying only)

Part A (protocol) was written BEFORE any data was analysed. Part B (the forward rules) is written after the event
study on DISCOVERY data only, and BEFORE any forward P&L was computed. Nothing in A or B changes after P&L is seen;
anything added later is labelled post-hoc in HUNT_H32.md and counted.

## Part A - protocol

### Data and universe
- Real Dhan 1-minute option bars (nearest expiry 'near': weekly when listed, else monthly), ATM±10, with volume and OI.
  NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX. Index minutes from obuy.data (days with a non-real index skipped).
- Decision points: the close of every 2nd minute, 09:20 .. 14:50 (166 a day). Contracts at each decision: CE/PE x ATM /
  1-ITM, strike from the index close at the decision minute. Expiry days INCLUDED (expiry is a candidate precursor).
- Reference entry = the option's OPEN at the next minute.

### Big move (event) definitions - absolute PREMIUM points (Boss's clarification)
- Jump(U, W, S): the option's high reaches entry + U points within W minutes, before its low touches entry - S points
  (a touch of both in the same minute counts as the drop: conservative). U in {15, 20, 25, 30}, W in {5, 15, 30},
  S in {10, 15}: 24 definitions, base rates reported for all, per index.
- Drop(U, W, S): the mirror (the low touches entry - S within W minutes first).
- PRIMARY event for the event study: Jump(20, 15, 15). Percent definition (+30% before -15%) is a side note only.

### Splits
- DISCOVERY (event study, precursor ranking, quintile cut-points, rule choice): first day .. 2023-12-31.
- PSEUDO-OUT-OF-SAMPLE (forward test, walk-forward by year): 2024 and 2025-01-01 .. 2025-09-30.
- LOCKED HOLDOUT: 2025-10-01 .. latest. Run ONCE at the end, only for rules that survive the gates below
  (if none survive, the single best pre-holdout variant is run once as a descriptive check, labelled as such).

### Event study
- Precursors: 32 causal features at the decision minute (index chart, option chart, opposite option, OI both sides,
  VIX, straddle, clock, days to expiry). List and meanings in common.py FDESC.
- Matched controls: (t) time-matched: the feature's percentile among all decisions of the same index, same contract
  type, same 30-minute slot, same calendar quarter; (d) day-matched: percentile among the same index, same day, same
  contract type. A precursor's strength = mean percentile at event ONSETS (first decision of a run, no event in the
  previous 10 minutes); 0.5 = no information. The same is computed at Drop onsets: a precursor that is high before
  BOTH jumps and drops is a volatility signal, not a direction signal.

### Forward rules (Part B fills in which)
- A rule = a condition on precursors (thresholds = quintile cut-points per index from DISCOVERY) on a contract type.
- Precision = P(Jump | condition), recall = share of all Jump rows covered, lift = precision / base rate, all on
  the forward periods; also P(Drop | condition).
- Trading: when the condition is true at a decision minute, buy that contract at the next minute's open (obuy engine:
  app fills ±5 bps, stops -10 bps, Costs('app') charges), one position at a time per index, max 3 entries per index
  per day, square-off 15:10, expiry days allowed, lot = the day's lot from the data, 1 lot.
- Spread on top (net+spread): half-spread per side BN 0.16%, NIFTY 0.16%, MIDCP 0.21%, FIN 0.42%, SENSEX 0.20%;
  stress = 1.5x.
- EXIT MENU (fixed, all also square off at 15:10):
  X1 ARM   Liquidity arm exits: stop 15%, time stop 20 min unless premium >= +5%.
  X2 P15   target +15 pts, stop -10 pts, time stop 30 min.
  X3 P20   target +20 pts, stop -15 pts, time stop 30 min.
  X4 P2020 target +20 pts, stop -20 pts, time stop 30 min.
  X5 P25   target +25 pts, stop -15 pts, time stop 30 min.
  X6 P30   target +30 pts, stop -15 pts, time stop 30 min.
  X7 PCT   stop 15%, target 30%.
  X8 LAD   stop 15%, profit-lock ladder (0.25,0)(0.5,0.25)(0.75,0.5) with R = 30% of entry.
  X9 T15   exit after 15 minutes (no stop/target).
  X10 T30  exit after 30 minutes (no stop/target).
- Random-entry baseline: obuy pools (same day/index/book, random minute in 09:20-14:50, coin-flip side, same strike
  rule, same exits). p per variant; BH q over ALL variants; Holm; Hansen SPA / White RC on the daily P&L matrix.
- Walk-forward: anchored by year over 2022..2025-09 (each test year uses the variant best on all earlier years).
- GATES to reach the holdout: forward-period (2024 + 2025-01..09) NET+spread > 0 AND random-baseline BH q < 0.10 AND
  walk-forward net > 0 AND positive in both forward years.
- Report: Rs/day at 1 lot (gross, net app, net+spread, stress), Rs per point per lot per index, lots needed for
  Rs 5,000/day, max drawdown, worst day/month, P(losing month) by bootstrap, honest counts of precursors/rules/variants.

## Part B - forward rules (chosen by sel.py on DISCOVERY data only, written BEFORE any forward P&L)

Event study results that shaped this (discovery, `es.log`):
- At event ONSETS (first decision of a run) the strongest "precursors" were the option having FALLEN over the last
  5-15 minutes and the index moving against it. The same features are equally strong (mirror image) before DROPS.
  This is a selection artefact of defining onsets (a contract that was already rising had an event at the previous
  decision, so it is not an onset). It is NOT used for rules.
- On ALL decision minutes (the honest forward question "does a jump follow more often than usual?"), every
  precursor is weak: the best time-matched mean percentile is 0.548 (index range speed, rexp15) and it is just as
  high before drops (0.540). Volatility features raise both jumps and drops.
- Selection criterion (stated in sel.py before running it): stratified liftJ - liftK, n_days >= 150, liftJ >= 1.15,
  one condition per feature; 6 singles + the best 3 pairwise ANDs among them (n_days >= 80).

Cut-points (20th/80th percentile per index, discovery) are stored in `rules.json`. Rules (the condition is
evaluated per contract; "in favour" = up for a CE, down for a PE):

| rule | condition | plain words |
|---|---|---|
| R1 | a_pcrch Q5 | net put-minus-call OI added near ATM over 15 min in the top fifth, in the option's favour (puts written before a CE buy, calls written before a PE buy) |
| R2 | o_off_lo Q5 | the option is in the top fifth for % above its own day low |
| R3 | o15 Q5 | the option's premium rose the most over the last 15 min (top fifth) |
| R4 | a_ir60 Q5 | the index moved most in the option's favour over 60 min (top fifth) |
| R5 | x_oi15 Q5 | the OPPOSITE option's OI grew the most over 15 min (writers selling the other side) |
| R6 | a_ir30 Q5 | the index moved most in favour over 30 min |
| R7 | R2 & R6 | |
| R8 | R2 & R5 | |
| R9 | R2 & R3 | |

9 rules x 10 exits = 90 variants. Signals: at most one contract per index per decision minute (random pick among
those that fire, fixed seed), signals at least 10 minutes apart, at most 8 a day per index before the position
filter (one open position per index, max 3 entries per index per day). Discovery-period P&L is reported but is
in-sample; the gates use 2024 + 2025-01..09 only.
