# R9 pre-registration: volume profile, auction regime, dealer gamma, delta/absorption proxies

Written 2026-10-09, BEFORE any outcome of this study was computed (no reversal rate, no regime statistic, no filtered
P&L). Nothing below changes after results are seen; anything added later is labelled POST-HOC in the report.
Frame: option BUYING only, Rs 1 lakh, fixed 1 lot. Design = sessions before 2025-10-01. Holdout = 2025-10-01 ..
2026-10-06, opened ONCE by `holdout.py` (writes `scratchpad/hunt/r9/HOLDOUT_OPENED`; refuses a second run).

## Data

- Index minutes (obuy `Index`): NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY. Index has no volume.
- **Volume weight, long history (proxy) `OPTV`:** per minute, the summed volume (units) of all ATM±10 strikes, CE+PE,
  of the nearest option series (obuy `Options.chain(d, "near")`). Placed at the INDEX minute's price range.
- **Volume weight, real `FUTV`:** Dhan 1-minute futures volume of the 2026-10 contract (the only index futures minute
  history that exists, 2026-07-29 .. 2026-10-06, ~48 sessions, all inside the holdout; h38). Also placed at the index
  minute's price range (so basis does not shift levels). Used for: (i) a proxy check, (ii) information-only repeats.
- **Proxy check (data, not outcome):** on the FUTV days, per-minute Spearman of OPTV vs FUTV, and prior-day POC / value
  area from OPTV vs FUTV: share of days with |POC difference| <= 2 bins, mean value-area overlap (intersection/union).
  The proxy is called "usable" if POC agreement >= 50% and mean VA overlap >= 0.6. Reported either way.
- NSE F&O bhavcopy (h40 `fo_idxopt_near`, `fo_futures`) for per-strike OI and settle prices; NSE participant-wise OI
  (h27/h40 raw files) for the customer variant. Day t's files are used only for session t+1.
- Arm trade lists (no re-simulation; rules only choose which trades are kept):
  ORB, ORB Fresh, ORB Sweep, Range Fade, Liquidity FINNIFTY = `hunt/h19/trades.parquet` (app fills + charges) MINUS the
  h24 real half-spread on both legs (BANKNIFTY 0.16%, FINNIFTY 0.42%) = net. Liquidity BANKNIFTY and MIDCPNIFTY =
  `hunt/h24/trades24.parquet`, model flat_x1, kappa 0.02 (already includes the real spread). Stress = 1.5x spread
  (h19: 1.5x hs; h24: flat_x1.5 rows), reported for anything that passes.
- Arm types (fixed): TREND = ORB, ORB Fresh, Liquidity BN / FIN / MIDCP (they buy breaks). RANGE = Range Fade,
  ORB Sweep (they fade). Secondary (counted in BH): the opposite type for the three Liquidity arms and ORB Sweep.

## Volume profile (all tests)

- Bin width = 0.05% of the profile day's first close (rounded to 0.05 point). Each minute's weight is spread evenly over
  the bins its [low, high] covers. TPO profile = each 30-minute period adds 1 to every bin its range covers.
- POC = heaviest bin (ties: nearest to the day's VWAP-proxy). Value area: start at POC, add the heavier adjacent bin
  one at a time until >= 70% of the total. VAH / VAL = top / bottom edge.
- Smoothed profile = 5-bin triangular kernel (1,2,3,2,1). HVN = bins that are the strict maximum of the smoothed profile
  within +-3 bins and >= 1.0x its mean over the day's span. LVN = strict minima within +-3 bins, strictly between the
  lowest and highest HVN, <= 0.6x the mean.

## A. Placebo test of prior-day volume levels (method of R4)

- Families: POC, VAEDGE (VAH and VAL), HVN (all, incl. POC), LVN; from the PRIOR day's OPTV profile.
- Placebo: the whole level set shifted by s x prior close, s = +-U(0.15%, 0.60%) (random sign), 20 draws a day.
- Event: first touch of each level, 09:20 .. 14:45 (R4 `events`). Outcome (R4 `outcome`): REVERSAL if within 60 minutes
  price comes back d before it moves d through, d = 0.10% and 0.25%; unresolved = dropped.
- Statistic: reversal rate claimed minus placebo, **time-matched**: rates computed within touch-time buckets
  (09:20-10:30, 10:30-12:30, 12:30-14:45) and the placebo weighted by the claimed buckets' shares. Raw diff also shown.
  p = one-sided day-cluster bootstrap (1,000). Claimed direction: POC / VAEDGE / HVN reverse MORE; LVN reverses LESS.
- 4 indices x 4 families x 2 d = 32 design tests, BH. Confirmed = BH q < 0.05 in design AND p < 0.05 in the holdout,
  same sign. FUTV repeat on the 48 FUTV days: information only.

## B. Auction regime (prior-day value area)

- VA sources: OPTV profile (primary) and TPO profile.
- Day classes: OPEN = 09:15 open inside [VAL, VAH] / above / below. ACC at 10:15 = both 30-minute closes (09:45, 10:15)
  above VAH (ACC_UP) or below VAL (ACC_DN); IN2 = both inside; else MIX.
- Rest-of-day (10:15 -> 15:15) outcomes: TR = |close(15:15) - close(10:15)| / (high - low); crosses of the VWAP-proxy
  (OPTV-weighted average price from 09:15; a cross needs the close to move 0.02% beyond it); RV = sqrt(sum of squared
  1-minute log returns) in %; ZZ = number of 0.20% zigzag reversals. Continuation: share of ACC days whose rest-of-day
  move is on the acceptance side, and the mean signed move.
- Claims: ACC (UP+DN) vs IN2: TR higher, crosses fewer, ZZ fewer (RV reported, not tested: it is a size measure).
  OPEN outside vs inside: same three. Continuation > 50% (binomial). One-sided Mann-Whitney. NIFTY, BANKNIFTY,
  FINNIFTY, MIDCPNIFTY x 2 VA sources x 7 tests = 56 design tests, BH.
- **Filter** (regime at the trade's entry minute uses only 30-minute bars completed by then):
  - TREND arms: F1 keep if OPEN is outside VA; F2 keep if the last two completed 30-minute closes are outside VA on the
    same side (fewer than 2 completed: use OPEN); F3 = F2 and the trade's side matches the acceptance side (CE above).
  - RANGE arms: G1 keep if OPEN inside VA; G2 keep if the last completed 30-minute close is inside VA (none: OPEN).
  - Secondary: Liquidity arms with G1/G2, ORB Sweep with F1-F3.
  - Each arm uses its own index's profile. 2 VA sources. Count: 5x3x2 + 2x2x2 + 3x2x2 + 1x3x2 = 56 filters.
- Filter statistics: kept / skipped trades, win%, Rs/trade, Rs/day (over all sessions of the period); improvement =
  kept Rs/day - all Rs/day (= -skipped Rs/day). p = one-sided day-cluster bootstrap (2,000) of kept Rs/trade minus
  skipped Rs/trade. BH over all 56 (B) and separately over the 28 (C) filters; White's reality check (stationary
  bootstrap, block 5, 1,000) of the daily improvement series over each family.
- **Gate (design):** BH q < 0.05, >= 100 kept trades, improvement > 0, improvement positive in more than half the design
  years. **Confirmed:** gate passed AND holdout improvement > 0 AND holdout kept-minus-skipped p < 0.05.
  The holdout run reports every filter (information), but only gate passers can be confirmed.

## C. Dealer gamma (bhavcopy, prior evening)

- For bhavcopy day t, index u, each of the nearest 3 expiries > t: forward F_e = K* + C - P at the strike with the
  smallest |C - P| (settle prices). T = calendar days to expiry / 365 (expiry day itself dropped). IV per strike from the
  OTM option's settle (Black-76, r = 0), clipped 3%-150%; failure -> that expiry's ATM IV; no ATM IV -> 15% flat.
  Gamma per unit Black-76; GEX in Rs per 1% move = OI(units) x gamma x S^2 x 0.01, S = near forward.
- Conventions: A (US standard: dealers long calls, short puts) GEX_A = sum(G_c OI_c) - sum(G_p OI_p).
  B (dealers short calls, long puts) = -A. C (customer net, dealers = everyone but Client): per-strike gammas scaled by
  the Client's net long share of all index calls / puts (participant OI, all indices pooled), sign flipped.
  D (Pro = dealer): scaled by the Pro's net share, sign as is.
  Ratio = GEX / sum(G (OI_c + OI_p)) in [-1, 1]. Zero-gamma flip (A): GEX_A(S) on S = spot x (0.94 .. 1.06, step 0.1%)
  with each strike's IV and T fixed and forwards scaled with S; the crossing nearest spot.
- Next-session outcomes (09:15 -> 15:25): TR = |close - open| / (high - low); VWAP-proxy crosses; RV (1-minute, %);
  range %.
- Claims: under A, GEX_A > 0 (and spot above the flip) -> lower TR, more crosses, lower RV and range; under B the
  reverse. Tests per index (NIFTY, BANKNIFTY): sign A two-sided Mann-Whitney (covers A and B), flip side two-sided,
  C sign two-sided, D sign two-sided, ratio A partial: OLS of outcome on ratio controlling for log prior-day RV, log
  prior VIX close, days to the nearest expiry (1/(1+days)), Newey-West(5) t, two-sided. 2 x 5 x 4 = 40 tests, BH.
- **Filter:** TREND arms keep on "trending gamma" days: A: GEX_A < 0; B: GEX_A > 0; C: GEX_C < 0; D: GEX_D < 0.
  RANGE arms keep on the opposite. 7 arms x 4 = 28 filters. Same statistics and gate as B.

## D. Delta divergence and absorption (PROXIES; no tick or bid/ask history exists)

- Long-history proxy `FLOW`: per minute, over ATM±5 of the nearest series, sum over calls of sign(dclose) x volume minus
  the same over puts (tick rule; zero change keeps the last sign). "Delta" of the option complex, a crude proxy.
- D1 divergence: at 5-minute marks 09:45 .. 14:45, 30-minute index return r30 >= +0.15% with 30-minute FLOW sum < 0
  (bearish divergence) or r30 <= -0.15% with FLOW > 0 (bullish). Control: same-size moves with FLOW agreeing.
  Outcome: index return over the next 15 / 30 / 60 minutes in the reversal direction (bp). Claim: divergence mean > 0
  and > control. One-sided day-cluster bootstrap. Events at least 15 minutes apart per day and sign.
- D2 absorption: 5-minute bar with OPTV >= 2x the median of the same bar over the previous 20 sessions and range <= 0.5x
  that bar's 20-session median range, after a 30-minute move of >= 0.15%. Outcome: reversal-direction return 15 / 30 /
  60 min. Control: bars after the same-size move with OPTV below 1.5x the median.
- NIFTY, BANKNIFTY x 2 claims x 3 horizons = 12 design tests, BH. Cost bar: an option buyer needs the move to beat about
  2 bp of the index in spread alone (R7) plus theta; a pass with a mean below 5 bp is reported as untradeable.
- FUTV repeats on 48 days (delta = sign(fut close - fut open) x fut volume; absorption with FUTV): information only.
- What to compute from the app's shadow log is specified in the report (it cannot be tested yet).
