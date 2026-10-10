# h40 pre-registration: end-of-day positioning data -> next-morning option buying

Written 2026-10-08, BEFORE any option P&L was computed with these features (data fetch still running when written).
Option BUYING only. 1 lot (lot in force on the date). Rs 1,00,000 capital context.

## Question
Does positioning data that NSE / NSDL publish after the close of day D-1 predict the direction (or size) of the index
on day D well enough to pay an option buyer who enters on the morning of D?

## Publication timing (no look-ahead)
All features for day D use only files dated D-1 or earlier (they are published 17:00-21:00 IST on D-1, or later):
F&O bhavcopy, CM bhavcopy with delivery, fii_stats, participant-wise OI. NSDL FPI "reporting date" R covers trades of
the previous trading day; it is mapped to that trade date T and treated as known on the evening of T (proxy for the
NSE provisional FII cash figure, which IS public the evening of T). A strict variant (one more day of lag) is run as a
check. Mendeley FII/DII (NSE provisional figures, third-party compilation, 2018-2024) is used as-of the trade date.
Overnight holds from 15:20 are NOT tested: none of these files exists at 15:20 of the same day.

## Features (score x for day D; computed on D-1 data; z = (v - mean60) / std60 over the previous 60 sessions)
| id | feature | default sense |
|---|---|---|
| S01 | FII index-futures long ratio L/(L+S) (participant OI), z | follow |
| S02 | change in FII index-futures net (L-S) contracts, z | follow |
| S03 | change in FII index-options net bullish = (CallLong-CallShort) - (PutLong-PutShort), z | follow |
| S04 | change in Pro index-futures net, z | follow |
| S05 | change in Client index-futures net, z | fade (sign flipped) |
| S06 | FII index-futures net buy value of the day (fii_stats: buy Rs cr - sell Rs cr), z | follow |
| S07 | stock-futures build-up breadth: (#long build-up - #short build-up) / #stocks, all F&O stocks, all expiries summed (price = near-month close vs prev; OI = total OI change), z | follow |
| S08 | own index futures build-up: sign(price chg) if OI rose, else 0 (long / short build-up only; NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY own futures; SENSEX uses NIFTY) | follow |
| S09 | own index near-expiry options: (put OI change - call OI change) / total OI, z (put writing = bullish) | follow |
| S10 | rollover (only the last 5 sessions before a monthly expiry, NIFTY/BANKNIFTY futures): roll% = next/(near+next) OI vs the mean of the same offset over the previous 6 expiries; side = +1 if roll above mean AND next-month basis % above its mean, -1 if roll above mean AND basis below mean, else 0 | as defined |
| S11 | heavyweight delivery surge: among 12 index heavyweights, count(delivery-qty z20 >= 1.5 and close up) - count(surge and close down); x = that count / 2 | follow |
| S12 | FII cash net, NSDL equity stock-exchange net (Rs cr), z | follow |
| S13 | FII cash net (Mendeley/NSE provisional), z; 2020-2024 only | follow |
| S14 | DII cash net (Mendeley/NSE provisional), z; 2020-2024 only | follow |
| S15 | composite: sign-sum of S01 S02 S03 S06 S07 S12 (each sign(z) when abs(z)>=0.5); x = sum / 2 | follow |

Heavyweights (S11): RELIANCE HDFCBANK ICICIBANK INFY TCS BHARTIARTL ITC LT SBIN AXISBANK KOTAKBANK HINDUNILVR.

Side rule: thresholds thr in {0.5, 1.0}; side = +1 (CE) if sense*x >= thr, -1 (PE) if <= -thr, else no trade.
Both senses (follow and fade) are run and counted, so each feature x thr gives 2 variants per contract/exit.

## Trades
- Indices: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX; 1-ITM, nearest expiry (weekly if Dhan has it), expiry days
  allowed; lot as of the date; obuy engine, app fills (+-5/10 bps) and app charges.
- Entries: T1 = decide at the 09:15 bar, fill at the 09:16 open; T2 = fill at the 09:30 open.
- Exits (16), fixed now; premium stops/targets are checked on the option's 1-minute HIGH/LOW (resting orders, stop
  first in a minute); square-off 15:10:
  - time: out at 10:15 / 11:15 / 15:10 (no stop)
  - points: target +15/+20/+25/+30 premium points x stop -10/-15/-20 points (12), else 15:10
  - LIQ: Liquidity arm exits (-15% stop, out at 20 minutes unless up 5%), else 15:10
- Costs: GROSS = raw prices. NET = app fills + app charges + h24 real half-spread on entry and exit (NIFTY 0.16%,
  BANKNIFTY 0.16%, FINNIFTY 0.42%, MIDCPNIFTY 0.21%, SENSEX 0.20%). STRESS = 1.5x spread.
- Both CE and PE are simulated every session, so the coin-flip side baseline on the same sessions is exact.

Variant count (declared): 15 features x 2 thr x 2 senses x 5 indices x 2 entries x 16 exits = 9,600.

## Part A (index level, descriptive + BH)
Correlation of each raw feature with the index move open->10:15, open->11:15, open->15:10 and with the gap
(prev close -> 09:15 open) and with abs(open->15:10) (size). 15 x 5 x 5 tests, BH.

## Part C (filter on Liquidity 15+5, rules unchanged)
Trades from h24 (BANKNIFTY, MIDCPNIFTY, real spread, kappa 0.02) and h23 (NIFTY, FINNIFTY, SENSEX). For each feature x
thr (0.5) x {skip trades whose side opposes the signal, take only trades the signal agrees with} x 5 indices.
Permutation p vs random skipping of the same number of trades; BH.

## Periods and gates
- PRE (all choosing): data start .. 2025-09-30. HOLDOUT 2025-10-01 .. latest: read ONCE at the end.
- A variant SURVIVES only if all hold in PRE: >= 60 trades; mean net > 0 and BH q < 0.05 (t-test, all 9,600 variants);
  beats the coin-flip side on the same sessions with BH q < 0.05; net > 0 at 1.5x spread; positive in >= 60% of
  calendar years with trades. Hansen SPA (vs zero) over all variants reported.
- Walk-forward by year (anchored): each test year 2022, 2023, 2024, 2025(Jan-Sep) trades the variant with the best
  total net on all earlier years (>= 40 trades).
- Holdout: survivors only. If none survive, the best PRE variant and the 2025 walk-forward pick are run once for
  information only (nothing promoted).
- Reporting: Rs/day at 1 lot (over all sessions of that index), lots needed for Rs 5,000/day, premium per lot,
  lots that Rs 1 lakh can hold, max drawdown, worst day / month, P(losing month) by bootstrap.

## Amendments (recorded before running test.py, except where marked)
1. Implementation details fixed while writing feat.py / test.py, before any P&L was read:
   - S12L (strict one-day-lag FII cash) is run as its own declared feature and counted.
   - S08 and S10 take values in {-1, 0, 1}, so they use one threshold only (no duplicate variants).
   - S10 "basis" is the calendar spread next-month / near-month future - 1 (the old F&O bhavcopy has no spot price).
   - Final declared count stays 9,600: (14 x 2 + 2 x 1) feature-thresholds x 2 senses x 5 indices x 2 entries x 16 exits.
2. AFTER the PRE results (disclosed, info only, not used to promote anything): the holdout run also reports the
   FII-flow "follow" families (S01 S02 S05 S06 S12 S12L, every index / entry / exit / threshold), because they were
   the ones that beat the coin-flip side in PRE.
