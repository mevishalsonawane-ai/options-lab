# HUNT h23: Liquidity 15+5 on BANKEX and NIFTYNXT50, and more indices at 1 lot each on Rs 1 lakh

Code: `research/hunt/h23/`
- `PREREG.md`: written before any h23 P&L.
- `build.py`: one data pass over 7 indices.
- `run.py`, with these stages:
  - `pre`: the choice.
  - `hold`: run once.
  - `val`: reproduces h17.
  - `sens`: post-hoc checks.
- `diag.py`: post-hoc diagnosis.

Logs and CSVs are in `scratchpad/hunt/h23/`: `build.log`, `pre.log`, `hold.log`, `sens.log`, `choice.json`,
`pre_index.csv`, `hold_index.csv`, `pre_portfolio.csv`, `hold_portfolio.csv`, `sens.csv`, `funnel.csv`, `roll.csv`,
and `trades*.parquet`.

All trades are option BUYING. The app's Liquidity 15+5 rules are unchanged:
- 1-ITM strike, nearest expiry (monthly now);
- h14 limit entry at +0.5%, resting 3 minutes, no chase;
- h10/M2 impact model, κ 0.02 central and 0.04 pessimistic;
- fixed 1 lot per index and h17's free-cash check;
- no Kelly and no growing lots.

## Verdict

**NO. BANKEX and NIFTYNXT50 do not make money with this rule. Adding them does not raise Rs/day. Adding FINNIFTY,
NIFTY or SENSEX does not help either.**

1. **NIFTYNXT50 cannot be tested. It is effectively untradeable.**
   - Its options data starts on 2025-11-26, which is inside the holdout. There is no pre-holdout data at all.
   - Of 184 signals, only 36 had a 1-ITM contract in the data. Only 6 had a real print within 2 minutes, and only 3
     passed the liquidity check. Just 1 trade filled, and it lost Rs 3,139.
   - About 95% of its option minutes are carried quotes with zero volume. Even the contracts it did trade printed in
     only 13% of minutes.
2. **BANKEX has no edge, even before costs.**
   - Pre-holdout (Nov 2023 to Sep 2025): 219 trades, **gross -Rs 4.6k, net -Rs 41.4k** (-Rs 88/day, t = -2.3).
   - Random entries did about as well (p = 0.91).
   - It lost in every pre-holdout year: 2023, 2024 and 2025.
   - In the holdout it made +Rs 9.6k net from 30 trades; 23 more signals did not fill. That sample is too small to
     mean anything.
   - Its measured half-spread is about 0.5-0.8% of the premium, roughly 3× BANKNIFTY's.
3. **The pre-registered rule picked BN1 + MIDCP1 + FIN1.**
   - FINNIFTY was the only candidate that passed: pre net +Rs 11k, era-M net +Rs 27k, random-entry BH q < 0.001, and it
     raised portfolio Rs/day from 43.5 to 54.8.
   - **In the holdout FINNIFTY lost money** (-Rs 17.6k, -Rs 163/trade). The plan made **-Rs 259/day against -Rs 223/day
     for the base**. Adding FIN made the holdout worse.
4. **The honest spread model breaks h17's base too, and this matters more than any index choice.**
   - With spreads measured from the data, BN1 + M1 made **-Rs 223/day in the holdout** (h17: +Rs 308/day).
     Capital fell from Rs 1 L to **Rs 44k**, with a low of Rs 29k.
   - BANKNIFTY stayed positive at +Rs 156/day alone.
   - MIDCPNIFTY lost Rs 76k. Where the measured half-spread is 0.5% or more, the +0.5% limit is not marketable. So the
     few big MIDCP winners, which ran straight away, never filled.
   - See the diagnosis section below.

**Rs/day at Rs 1 lakh with any of these additions: no better than h17's BN + MIDCP plan. Adding indices only adds
cost.** If anything, the result points to **BANKNIFTY 1 lot alone**. That was not pre-registered here, so it is
post-hoc.

## What was checked, per index (1 lot alone, κ 0.02)

How to read the table:
- "rand" is the random-entry baseline: mean net per trade, real against random, and p.
- "boot p" is P(mean daily net ≤ 0), from a day-block bootstrap.
- Drawdown and worst day are in Rs.

### Pre-holdout (start of data, or 2021-10, to 2025-09)

| index | trades (missed fills) | net / gross Rs | Rs/day | Rs/trade | t | boot p | rand: real vs random, p | max DD | worst day | green months |
|---|---|---|---|---|---|---|---|---|---|---|
| BANKNIFTY | 734 (16) | +52.0k / +135.0k | +53 | +71 | 0.91 | 0.16 | +71 vs -109, 0.0005 | -40.7k | -4.6k | 23/48 |
| MIDCPNIFTY | 336 (52) | -8.7k / +53.4k | -15 | -26 | -0.19 | 0.59 | -29 vs -240, 0.0005 | -54.1k | -6.8k | 11/28 |
| **BANKEX** | 219 (67) | **-41.4k / -4.6k** | -88 | -189 | -2.33 | 0.98 | -217 vs -164, **0.91** | -44.1k | -3.4k | 4/23 |
| NIFTYNXT50 | 0 | no data before 2025-11-26 | | | | | | | | |
| FINNIFTY | 452 (33) | +11.1k / +72.3k | +13 | +25 | 0.32 | 0.41 | +28 vs -98, 0.0005 | -40.2k | -4.7k | 16/42 |
| NIFTY | 604 (27) | -56.0k / +0.7k | -57 | -93 | -1.35 | 0.93 | -93 vs -77, 0.65 | -61.3k | -4.9k | 16/48 |
| SENSEX | 343 (17) | -19.9k / +11.8k | -34 | -58 | -0.63 | 0.75 | -58 vs -11, 0.79 | -43.3k | -4.1k | 7/29 |

Notes:
- At κ 0.04 every number is slightly worse (`pre_index.csv`).
- Era M (Dec 2024 to Sep 2025, monthly contracts only), net in Rs:

  | index | era M net |
  |---|---|
  | BN | +41.9k |
  | FIN | +26.5k |
  | MIDCP | -28.3k |
  | BANKEX | -16.0k |
  | NIFTY | -30.8k |
  | SENSEX | -8.6k |

### Holdout (2025-10-01 to 2026-10-05), run once

| index | trades (missed fills) | net / gross Rs | Rs/day | Rs/trade | t | boot p | rand p | max DD | worst day | green months |
|---|---|---|---|---|---|---|---|---|---|---|
| BANKNIFTY | 247 (1) | +38.8k / +89.3k | +156 | +157 | 0.95 | 0.12 | 0.0005 | -31.4k | -7.1k | 8/13 |
| MIDCPNIFTY | 202 (28) | -75.9k / -8.7k | -306 | -376 | -2.63 | 0.99 | 0.50 | -81.8k | -6.2k | 3/13 |
| BANKEX | 30 (23) | +9.6k / +20.1k | +41 | +320 | 0.84 | 0.26 | 0.02 | -5.0k | -3.2k | 6/12 |
| NIFTYNXT50 | 1 (2) | -3.1k / -2.6k | | -3,139 | | | | | | 0/8 |
| FINNIFTY | 108 (33) | -17.6k / +21.7k | -71 | -163 | -0.82 | 0.79 | 0.0005 | -26.9k | -3.4k | 4/13 |
| NIFTY | 128 (6) | -4.1k / +12.8k | -19 | -32 | -0.19 | 0.59 | 0.10 | -16.1k | -4.7k | 3/11 |
| SENSEX | 132 (6) | -4.3k / +9.1k | -18 | -33 | -0.23 | 0.62 | 0.05 | -29.6k | -4.6k | 5/13 |

## Pre-registered selection (pre-holdout only, κ 0.02)

| candidate | trades ≥ 30 | net > 0 (all pre and era M) | random BH q < 0.05 | portfolio Rs/day up (base 43.5) | eligible |
|---|---|---|---|---|---|
| BANKEX | yes | **no** (-41k / -16k) | no (0.91) | no (4.4) | no |
| NIFTYNXT50 | **no** (0) | no | no | no | no |
| FINNIFTY | yes | yes (+11k / +27k) | yes (< 0.001) | yes (54.8) | **yes** |
| NIFTY | yes | no | no | no (-19.3) | no |
| SENSEX | yes | no | no | no (23.9) | no |

**Plan = BN1 + MIDCP1 + FIN1, with pre-holdout Rs 54.8/day.**

## Portfolios at Rs 1,00,000, fixed 1 lot each, free-cash check

| window | portfolio | κ | taken | skipped for cash | net Rs/day | gross Rs/day | t | boot p | end capital | min capital | max DD | worst day | green months |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| pre 2021-10..2025-09 | base BN+M | .02 | 1,069 | 1 | 43.5 | 190 | 0.54 | 0.30 | 1.43 L | 0.59 L | -52.4k | -9.3k | 21/48 |
| pre | **plan BN+M+FIN** | .02 | 1,521 | 1 | **54.8** | 263 | 0.59 | 0.28 | 1.54 L | 0.63 L | -52.2k | -9.3k | 19/48 |
| pre | BN+M+BANKEX | .02 | 1,286 | 3 | 4.4 | 187 | 0.05 | 0.49 | 1.04 L | 0.33 L | -78.1k | -9.3k | 19/48 |
| pre | plan | .04 | 1,501 | 4 | 30.4 | 256 | | | 1.30 L | | -56.5k | | 18/48 |
| **HOLDOUT** | base BN+M | .02 | 425 | 24 | **-223** | 213 | -1.14 | 0.86 | 0.44 L | 0.29 L | -106.5k | -7.8k | 4/13 |
| **HOLDOUT** | **plan BN+M+FIN** | .02 | 509 | **48** | **-259** | 306 | -1.15 | 0.87 | **0.35 L** | 0.21 L | -109.8k | -7.4k | 5/13 |
| HOLDOUT | plan | .04 | 342 | 198 | -299 | 167 | -2.00 | 0.95 | 0.26 L | 0.11 L | -88.3k | -7.4k | 3/13 |
| HOLDOUT | plan | .01 | 555 | 14 | -107 | 437 | | | 0.73 L | | -93.7k | | 6/13 |
| HOLDOUT (info) | BN+M+BANKEX | .02 | 460 | 19 | -184 | 305 | -0.91 | 0.78 | 0.54 L | 0.35 L | -110.7k | | 5/13 |
| HOLDOUT (info) | BN+M+NIFTY | .02 | 555 | 22 | -225 | 283 | | | 0.44 L | | -111.0k | | 4/13 |
| HOLDOUT (info) | BN+M+SENSEX | .02 | 522 | 59 | -271 | 189 | | | 0.32 L | | -113.6k | | 3/13 |
| HOLDOUT (info) | all 7 indices | .02 | 734 | 114 | -278 | 401 | | | 0.31 L | 0.19 L | -127.1k | | 4/13 |

- **Plan by month in the holdout (κ 0.02), Rs:**

  | month | net |
  |---|---|
  | Oct 2025 | +0.3k |
  | Nov | -12.1k |
  | Dec | -15.9k |
  | Jan 2026 | +26.3k |
  | Feb | +3.8k |
  | Mar | -15.2k |
  | Apr | -42.1k |
  | May | -5.3k |
  | Jun | -1.2k |
  | Jul | +3.1k |
  | Aug | -7.3k |
  | Sep | +8.2k |
  | Oct (to date) | -7.1k |

- More indices on Rs 1 lakh means more signals skipped for cash: 48 for the plan, 114 for all 7.
- The worst stretch is the same April 2026 loss that h17 saw.

## Why the base looks so much worse than h17 (diagnosis, post-hoc, info only)

h23 differs from h17 in exactly two ways:
- **"prints only"**: zero-volume minutes are treated as having no print;
- **spread measured from the data**: the Roll estimator, applied point-in-time.

`run.py sens` and `diag.py` separate the two effects:

| model | BN1+M1 pre Rs/day | BN1+M1 holdout Rs/day | BN alone holdout | MIDCP alone holdout | BANKEX pre / holdout |
|---|---|---|---|---|---|
| h17 (h15 table, no mask, app spread) | 111 (reproduced exactly: 111.28) | 308 | | | n/a |
| h23 prints-only, app spread (hs = 0) | 111 | 292 | +53.2k | +19.3k | -2.6k / +11.6k |
| **h23 prints-only + measured spread (pre-registered)** | **43.5** | **-223** | +38.8k | **-75.9k** | **-41.4k / +9.6k** |
| same, without the "no marketable fill when half-spread > 0.5%" rule | identical | identical | | | |

Findings:
- **Masking zero-volume minutes changes almost nothing for BN and MIDCP.** The BN 1-lot pre-holdout total is identical
  to h17's Rs 81,322.
- **The measured spread does all the damage.** Median Roll half-spread:

  | index | median half-spread |
  |---|---|
  | BN | ~0.0-0.3% |
  | NIFTY / SENSEX | ~0.0-0.4% |
  | FIN | ~0.0-0.4% (mean 0.3%) |
  | MIDCP | ~0.4-0.6% (holdout 0.40%) |
  | BANKEX | ~0.5-0.8% |
  | NIFTYNXT50 | ~3-6% |

- In thin months the half-spread is at or above the 0.5% limit buffer. A +0.5% limit then only fills if the price comes
  back, so the trades that run away at once are missed.
  - For MIDCP in the holdout, the biggest h17 winners (Sep 24, Feb 1 in both books, Feb 13: about Rs 50-67k gross) did not fill.
  - On top of that, the extra spread cost is about Rs 80 per trade.
- Even without the fill rule, the same trades miss, because a limit at +0.5% cannot beat an ask that is 0.5% or more
  away. The rule is redundant, not the cause.
- **The h17 MIDCP leg, and so h17's +308/day, depends on cheap fills in a thin book.** Whether the Roll spread is too
  pessimistic is the open question. It is a minute-bar estimate; there is no order-book data. Paper fills on MIDCP will
  answer it.
- Even with the cheap app spread (hs = 0), the selection gives the same answer: BANKEX pre-holdout is still negative
  (-2.6k), and NIFTY is negative. Under hs = 0, FINNIFTY in the holdout is +6.7k, so it would have added about +39/day,
  and BANKEX added post-hoc +47/day. Neither choice could have been made on pre-holdout data.

## Data coverage (honest caveats)

Signal funnel, all dates:

| index | signals | contract in data | printed within 2 minutes | ≥ 1 lot traded in the prior 5 minutes, after the book rule | filled (κ .02) | share of minutes with a print, traded contracts |
|---|---|---|---|---|---|---|
| BANKEX | 604 | 510 | 379 | 339 | 249 | 60% |
| NIFTYNXT50 | 184 | 36 | 6 | 3 | 1 | 13% |
| BANKNIFTY | 999 | | 999 | 998 | 981 | 99.6% |
| FINNIFTY | 694 | | 635 | 626 | 560 | 83% |
| MIDCPNIFTY | 696 | | 625 | 618 | 538 | 87% |
| NIFTY | 926 | | 926 | 926 | 892 | 99.8% |
| SENSEX | 505 | | 499 | 498 | 475 | 97.5% |

Data notes:
- BANKEX options cover 2023-10-05 to 2026-10-06: weekly options until Nov 2024, monthly after that. About 80% of the
  monthly option minutes in 2025-26 carry zero volume.
- NIFTYNXT50 options cover 2025-11-26 onwards only.
- The data holds ATM±10 strikes of the nearest expiry only, so there is no next-month contract.

Model notes:
- Index stops were set a priori by h4's ~0.065% rule (BANKEX 40, NIFTYNXT50 45). They were not tuned.
- The spread estimate uses the previous month's value. Each index's first month uses its own month; this is disclosed
  in PREREG.
- The random-entry baseline uses 5 random-minute, random-side alternatives per signal, with the same exits, fills and
  costs, over 2,000 draws.
- Known before writing PREREG:
  - h11's NIFTY/SENSEX result: no edge;
  - h15's FIN holdout: -71/trade;
  - all h17 numbers.

## The rule (no change recommended from h23)

- Do **not** add BANKEX, NIFTYNXT50, NIFTY or SENSEX to Liquidity 15+5. Do not add FINNIFTY either: it was chosen
  pre-holdout and failed in the holdout.
- Before trusting h17's MIDCPNIFTY leg live, log real bid/ask at signal time for 1-2 months.
  - If the MIDCP 1-ITM monthly half-spread is often ≥ 0.5%, run **BANKNIFTY 1 lot alone**. This is post-hoc: BN made
    +Rs 156/day in the holdout and +Rs 53/day pre under the measured spread.
- Rs 5,000/day at Rs 1 lakh: **NO**.
