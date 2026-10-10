## obuy catalog run "gc": expiry-day, OI / sentiment, long-volatility, event and overnight / positional option buying

Written 7 Oct 2026. Code: `research/obuy/strategies/gc_*.py` (5 modules, 19 strategies) plus the new multi-day support
`research/obuy/multiday.py` (and a 6-line dispatch in `engine.prepare_many`). Run:
`python3 -I research/obuy/run.py run gc_exp01_hero ... gc_pos04_nextwk --name gc_all --pool 5` (one run, 1,060 variants,
1,528 trading days Aug 2020 - Oct 2026, 17 minutes).
Per-variant results: `/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/obuy_cache/runs/gc_all/variants.csv`
(families: `families.csv`, trades: `trades.csv.gz`, framework report: `REPORT.md` in the same folder).

### Verdict

**Nothing passes the gates. None of the 22 catalog ideas in these five families shows a tradable edge after costs,
out of sample.**

- 18 catalog strategies were tested (19 strategy objects: EXP-05 runs as two, at 1 and 5 trades a day). 4 were skipped
  because the data cannot test them.
- Of the 19 strategies, 2 made money in the walk-forward, and only small amounts:
  - EXP-04 max pain: Rs +6,969 on 27 trades.
  - VOL-04 VIX + ORB: Rs +6,795 on 127 trades.
  - Neither beats the random-entry baseline (raw p 0.16 and 0.69, Holm 1.0).
- 17 of the 19 lost money in the walk-forward. The bottom of the list:
  - Hero-zero: Rs -1.51 million at Rs 5k tickets.
  - Max-OI magnet: Rs -339k.
  - Intraday OI change: Rs -249k.
- Over all 1,060 variants:
  - White's Reality Check p = 0.97 and Hansen SPA p = 0.74: no variant beats not trading.
  - Every family's best variant has a Deflated Sharpe of 0.01 or less.
  - Only 101 of the 1,060 variants are positive even in sample.

**One in-sample pattern is worth knowing about, but it decayed: the BTST strong-close rule.** Buy a call at 15:15 when
the day closes in the top 25% of its range above its TWAP, buy a put on the mirror close, and exit at the next open.
In sample:
- 16 of its variants beat the random baseline after BH (q = 0.028; 21 at raw p < 0.05). The best, ITM1 with a -30% stop and a 09:30 exit,
  made Rs +318k on 1,236 trades with 7 of 7 years positive.
- Almost all of that was earned in 2020-2022 (Rs +256k). 2023-2026 added only Rs +62k: +38.7k, +4.1k, +0.7k, +18.5k.

The walk-forward picked it from 2023 on, and the walk-forward lost Rs -42k (3 of 5 years positive). The 2026 pick was
the calls-only version, which lost Rs -112k. So the overnight momentum it caught in 2020-22 is gone.

The other BH survivors are also in-sample only and did not hold in the walk-forward:
- VOL-02's "faster leg" breakout.
- POS-02's Supertrend + Varsity strike (66 trades).
- Two EXP-04 variants with 10 trades each.

**Best by walk-forward:** EXP-04 max-pain convergence, Rs +6,969, PF 1.80, max DD Rs -4.6k. That is 27 trades over
5 years, worth about Rs 1.5k a year and not distinguishable from random (p 0.16).

Per strategy (in-sample best = best full-sample variant; walk-forward = anchored yearly, test years after 2 training
years; p = walk-forward trades vs the same-exit random-entry baseline, raw / BH across the 19 families; DD = walk-forward
max drawdown at 1 lot):

| id | strategy | in-sample best | walk-forward net (trades) | beats random p raw / BH | WF years + | WF max DD |
|---|---|---|---|---|---|---|
| EXP-01 | gc_exp01_hero | -237,117 (Rs 2-5, 14:30, breakout, hold to 15:20) | -1,509,700 (878) | 0.383 / 0.995 | 0/5 | -1,531,050 |
| EXP-02 | gc_exp02_gamma | -2,033 | -25,544 (273) | 0.699 / 0.995 | 1/5 | -38,644 |
| EXP-03 | gc_exp03_orb | -56,484 | -141,218 (706) | 0.633 / 0.995 | 1/5 | -165,467 |
| EXP-04 | gc_exp04_maxpain | +18,771 (10:30, d 0.4%, expiry day, ATM, -30%) | **+6,969 (27)** | 0.163 / 0.776 | 3/5 | -4,593 |
| EXP-05 | gc_exp05_scalp1 | -18,465 | -23,365 (421) | 0.236 / 0.898 | 0/5 | -29,195 |
| EXP-05 | gc_exp05_scalp5 | -119,139 | -114,993 (2,103) | 0.400 / 0.995 | 0/5 | -116,760 |
| OI-01 | gc_oi01_pcr | +53,480 (rolling 90/10 pct OI-PCR, hold 12, -30%/+50%) | -38,821 (133) | 0.081 / 0.513 | 1/4 | -129,788 |
| OI-02 | gc_oi02_doi | -103,282 | -249,478 (1,449) | 0.914 / 0.995 | 0/5 | -281,997 |
| OI-04 | gc_oi04_maxoi | -303,701 | -338,575 (467) | 0.940 / 0.995 | 1/5 | -343,038 |
| VOL-01 | gc_vol01_strad | -122,486 | -164,167 (855) | 0.515 / 0.995 | 0/5 | -188,076 |
| VOL-02 | gc_vol02_sbo | +87,477 (OR-high, fixed strike, faster leg, trail) | -40,242 (1,008) | 0.928 / 0.995 | 2/5 | -162,757 |
| VOL-03 | gc_vol03_ivp | -60,664 | -204,087 (82) | 0.995 / 0.995 | 0/5 | -205,183 |
| VOL-04 | gc_vol04_vixorb | +56,166 (VIX +8%, OR15, ITM1, 1.5R) | **+6,795 (127)** | 0.687 / 0.995 | 3/4 | -40,189 |
| VOL-05 | gc_ev05_runup | -93,519 | -102,552 (137) | 0.055 / 0.513 | 0/5 | -104,464 |
| VOL-06 | gc_ev06_eventday | -37,391 | -57,396 (62) | 0.986 / 0.995 | 0/4 | -57,396 |
| VOL-07 | gc_ev07_post | +22,155 (wait 60, all events, ITM1, 2R) | -88,461 (255) | 0.713 / 0.995 | 0/5 | -99,775 |
| POS-01 | gc_pos01_btst | +318,028 (strong close + put mirror, ITM1, -30%, 09:30) | -42,394 (863) | 0.066 / 0.513 | 3/5 | -134,496 |
| POS-02 | gc_pos02_trend | +68,425 (Supertrend, Varsity strike, hold 5) | -404 (17) | 0.436 / 0.995 | 2/3 | -18,269 |
| POS-04 | gc_pos04_nextwk | +24,988 | -136,843 (317) | 0.802 / 0.995 | 1/5 | -177,208 |

Skipped (cannot be tested with this data):
- **VOL-08** stock results-day straddle: no stock options.
- **OI-03** price-OI build-up: needs index futures OI history; the futures in the data are only today's contracts.
- **OI-05** FII long/short ratio: needs NSE participant-wise OI, which is not in the data.
- **POS-03** Stockbee momentum burst: F&O stock options are missing.

### Tables

**Families (walk-forward, 1 lot, after the app's charges and fills).** MC = Monte Carlo of the walk-forward trades
resampled into one-year sequences on Rs 5 lakh.

| strategy | variants | WF trades | WF net | per year | PF | win | max DD | Sharpe | years + | p raw / Holm / BH | MC P(profit 1y) | MC P(DD>=20%) 1 lot | MC P(DD>=20%) 1% risk | MC P(DD>=50%) 2% risk | best IS net | DSR | PBO |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| gc_exp04_maxpain | 72 | 27 | +6,969 | +1,473 | 1.80 | 41% | -4,593 | 0.44 | 3/5 | 0.163 / 1.000 / 0.776 | 67% | 0% | 0% | 0% | +18,771 | 0.00 | 20% |
| gc_vol04_vixorb | 48 | 127 | +6,795 | +1,814 | 1.04 | 40% | -40,189 | 0.07 | 3/4 | 0.687 / 1.000 / 0.995 | 53% | 0% | 0% | 0% | +56,166 | 0.01 | 29% |
| gc_pos02_trend | 48 | 17 | -404 | -149 | 0.98 | 29% | -18,269 | -0.01 | 2/3 | 0.436 / 1.000 / 0.995 | 35% | 0% | 0% | 0% | +68,425 | 0.00 | 68% |
| gc_exp05_scalp1 | 12 | 421 | -23,365 | -4,940 | 0.72 | 24% | -29,195 | -1.20 | 0/5 | 0.236 / 1.000 / 0.898 | 10% | 0% | 84% | 50% | -18,465 | 0.00 | 4% |
| gc_exp02_gamma | 96 | 273 | -25,544 | -5,391 | 0.73 | 19% | -38,644 | -0.80 | 1/5 | 0.699 / 1.000 / 0.995 | 20% | 0% | 64% | 20% | -2,033 | 0.00 | 28% |
| gc_oi01_pcr | 54 | 133 | -38,821 | -10,465 | 0.90 | 42% | -129,788 | -0.25 | 1/4 | 0.081 / 1.000 / 0.513 | 39% | 2% | 0% | 0% | +53,480 | 0.00 | 14% |
| gc_vol02_sbo | 96 | 1,008 | -40,242 | -8,486 | 0.96 | 37% | -162,757 | -0.18 | 2/5 | 0.928 / 1.000 / 0.995 | 41% | 2% | 47% | 19% | +87,477 | 0.00 | 16% |
| gc_pos01_btst | 48 | 863 | -42,394 | -8,971 | 0.96 | 45% | -134,496 | -0.17 | 3/5 | 0.066 / 1.000 / 0.513 | 41% | 4% | 0% | 0% | +318,028 | 0.00 | 0% |
| gc_ev06_eventday | 36 | 62 | -57,396 | -15,339 | 0.31 | 19% | -57,396 | -1.06 | 0/4 | 0.986 / 1.000 / 0.995 | 6% | 0% | 0% | 0% | -37,391 | 0.00 | 52% |
| gc_ev07_post | 72 | 255 | -88,461 | -18,671 | 0.77 | 39% | -99,775 | -0.63 | 0/5 | 0.713 / 1.000 / 0.995 | 22% | 0% | 0% | 0% | +22,155 | 0.00 | 56% |
| gc_ev05_runup | 36 | 137 | -102,552 | -21,887 | 0.51 | 30% | -104,464 | -0.94 | 0/5 | 0.055 / 1.000 / 0.513 | 14% | 0% | 0% | 0% | -93,519 | 0.00 | 62% |
| gc_exp05_scalp5 | 12 | 2,103 | -114,993 | -24,312 | 0.69 | 25% | -116,760 | -2.95 | 0/5 | 0.400 / 1.000 / 0.995 | 0% | 0% | 100% | 100% | -119,139 | 0.00 | 0% |
| gc_pos04_nextwk | 48 | 317 | -136,843 | -28,957 | 0.79 | 24% | -177,208 | -0.63 | 1/5 | 0.802 / 1.000 / 0.995 | 24% | 6% | 16% | 2% | +24,988 | 0.00 | 15% |
| gc_exp03_orb | 18 | 706 | -141,218 | -29,806 | 0.81 | 30% | -165,467 | -0.85 | 1/5 | 0.633 / 1.000 / 0.995 | 17% | 1% | 78% | 43% | -56,484 | 0.00 | 68% |
| gc_vol01_strad | 64 | 855 | -164,167 | -34,620 | 0.74 | 31% | -188,076 | -1.13 | 0/5 | 0.515 / 1.000 / 0.995 | 9% | 0% | 0% | 0% | -122,486 | 0.00 | 27% |
| gc_vol03_ivp | 96 | 82 | -204,087 | -43,670 | 0.34 | 27% | -205,183 | -1.34 | 0/5 | 0.995 / 1.000 / 0.995 | 3% | 1% | 0% | 0% | -60,664 | 0.00 | 61% |
| gc_oi02_doi | 72 | 1,449 | -249,478 | -52,611 | 0.84 | 39% | -281,997 | -0.98 | 0/5 | 0.914 / 1.000 / 0.995 | 13% | 29% | 37% | 13% | -103,282 | 0.00 | 40% |
| gc_oi04_maxoi | 36 | 467 | -338,575 | -71,644 | 0.69 | 39% | -343,038 | -1.29 | 1/5 | 0.940 / 1.000 / 0.995 | 9% | 44% | 4% | 0% | -303,701 | 0.00 | 3% |
| gc_exp01_hero | 96 | 878 | -1,509,700 | -318,643 | 0.47 | 6% | -1,531,050 | -1.92 | 0/5 | 0.383 / 1.000 / 0.995 | 3% | 0% | 100% | 100% | -237,117 | 0.00 | 29% |

How to read the Monte Carlo columns:
- **Hero-zero is sized by budget, not lots.** Its walk-forward net is at Rs 5k tickets, but the Monte Carlo "1 lot"
  column re-sizes each trade to one lot of a Rs 2-10 option. That is why it shows 0% drawdown risk there.
- **Strategies without a premium stop risk the full premium.** At 1% or 2% risk, Rs 5 lakh rarely affords one lot of a
  straddle, so most of their trades are skipped and those columns read 0%. Read their 1-lot column.

**Walk-forward by test year (net of the variant picked on the earlier years):**

| strategy | 2022 | 2023 | 2024 | 2025 | 2026 (to Oct) |
|---|---|---|---|---|---|
| gc_exp04_maxpain | +2,966 | +2,370 | +4,279 | -65 | -2,580 |
| gc_vol04_vixorb | (train) | +2,775 | +8,181 | -13,330 | +9,169 |
| gc_pos02_trend | (train) | (train) | -7,637 | +3,388 | +3,845 |
| gc_pos01_btst | +46,719 | +32,310 | -10,589 | +726 | -111,560 |
| gc_oi01_pcr | (train) | +78,716 | -20,026 | -6,870 | -90,641 |
| gc_vol02_sbo | +24,282 | -1,227 | -14,073 | -105,093 | +55,870 |
| gc_pos04_nextwk | -62,349 | +44,516 | -22,751 | -73,505 | -22,754 |
| gc_exp02_gamma | +7,577 | -12,214 | -7,496 | -10,484 | -2,927 |
| gc_exp03_orb | +8,754 | -29,447 | -19,623 | -42,364 | -58,537 |
| gc_vol01_strad | -14,236 | -22,592 | -14,245 | -88,421 | -24,674 |
| gc_vol03_ivp | -28,502 | -22,881 | -3,494 | -136,809 | -12,400 |
| gc_ev05_runup | -12,553 | -28,356 | -29,863 | -8,595 | -23,185 |
| gc_ev06_eventday | (train) | -10,246 | -4,541 | -28,378 | -14,231 |
| gc_ev07_post | -9,547 | -10,955 | -7,901 | -28,921 | -31,135 |
| gc_oi02_doi | -47,814 | -41,481 | -10,942 | -122,038 | -27,203 |
| gc_oi04_maxoi | -85,257 | -29,017 | +549 | -89,073 | -135,778 |
| gc_exp05_scalp1 | -3,113 | -3,425 | -5,618 | -3,246 | -7,962 |
| gc_exp05_scalp5 | -27,978 | -25,075 | -34,917 | -6,811 | -20,212 |
| gc_exp01_hero | -315,251 | -366,911 | -226,035 | -516,589 | -84,914 |

Strategies whose signals start later (VIX minute data from Oct 2021; 252-day lookbacks; rare events) train on their
first two calendar years with signals.

**In-sample variants that beat the random baseline after BH (23 of 1,060; selection-biased):**
- 16 are POS-01 strong-close BTST variants: Rs +1k to +318k. All are the "strong close" filter; the unconditional BTST
  calls lose, Rs -14k to -565k.
- 4 VOL-02 VWAP / OR-high "faster leg" variants: up to Rs +70k, 3-4 of 7 years positive.
- POS-02 Supertrend + Varsity strike, hold 5: Rs +68k on 66 trades, 4 of 7 years positive.
- 2 EXP-04 variants with 10 trades each.

**BTST strong-close (s10, ITM1, -30%, exit 09:30) by year:**

| | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|
| net | +66,427 | +106,073 | +83,511 | +38,703 | +4,090 | +726 | +18,499 |
| of which NIFTY | +66,427 | +72,969 | +54,751 | +29,216 | +7,204 | -27,996 | +5,584 |

### What each strategy did, briefly

**Expiry day**
- **Hero-zero (EXP-01)** loses in every variant:
  - The best variant loses Rs 237k, and the median Rs 1.4M at Rs 5k tickets.
  - The famous jackpot days exist (the 2024-09-12 kind), but buying Rs 2-10 tickets every expiry pays for far more
    than they return. Fixed-time and breakout entries do no better than random entries (p 0.38).
  - The repo's own Hero rule (hero_grid) was already shown not to survive the walk-forward.
- **Gamma blast (EXP-02)** loses; 19% wins.
- **Expiry-day ORB with today's option (EXP-03)** loses at every strike depth. The repo's earlier +1.9k on
  11 expiries was a small-sample artefact.
- **The +10% scalp (EXP-05)** loses like a coin flip, as EXPIRY_SCALP.md found on 11 days. At 5 a day it loses
  Rs -24k a year.

**OI / sentiment**
- **The PCR contrarian (OI-01)** was the best OI idea in sample: Rs +53k with rolling 90/10 percentiles, a 12-session
  hold and -30/+50 exits. It was positive 2022-23 only, and its walk-forward lost.
- **The max-OI magnet (OI-04)** is clearly negative. The max-OI strike is a level, not a direction.
- **Intraday OI change (OI-02)** is negative, consistent with GREEN_CANDLES.md.

**Long volatility**
- **Long straddles lose** whether bought at a fixed time (VOL-01, including the Tue/Fri pick), on low IV percentile
  (VOL-03), before events (VOL-05) or through events (VOL-06). This is the persistent variance risk premium the
  catalog notes. The pre-event run-up loses in every variant (best Rs -94k): IV rises into events, but not enough to
  pay the theta.
- **VIX + ORB (VOL-04)** is roughly flat out of sample.

**Event**
- **Post-event breakout (VOL-07)** is slightly positive in sample for one variant (Rs +22k) and negative in the
  walk-forward.

**Overnight / positional**
- **Unconditional BTST calls lose** (2022 and 2024-26).
- **The strong-close BTST** is the one real-looking pattern, and it decayed (see above).
- **POS-02 trend** fires only about 10 times a year, too few to judge.
- **POS-04, the new weekly the day after expiry,** loses.

### Rules as implemented, and deviations

The general rules:
- Data: real Dhan option minutes, the app's fills (next-minute open, ±5 / 10 bps) and the app's charges at today's
  rates, 1 lot as of each date (Rs 5k ticket sizing for hero-zero, with the 'liq' spread fill as in hero.py).
- Expiry days are skipped unless the strategy is about expiry or events.
- Underlyings: NIFTY and BANKNIFTY unless stated otherwise:
  - Expiry strategies EXP-01 to EXP-03 use all four indices.
  - VOL-02 adds SENSEX.
  - POS-04 uses NIFTY, BANKNIFTY and SENSEX weeklies.
- Grids were fixed before running, at 12 to 96 variants per strategy (1,060 in all). Every variant counts in the
  corrections.

**Multi-day holds (new, `multiday.py`).**
- **Composite path.** A position held across days runs the unchanged exit engine on a path of up to 375 columns:
  - The entry day is at 1-minute resolution around the entry window.
  - Later days use b-minute buckets. A bucket never spans two days, so an overnight gap appears as the next bucket's
    open, and a stop gapped through fills there.
  - The exit day is at 1-minute resolution from the exit time.
  - BTST fits at 1-minute resolution throughout. A 12-session hold uses 14-minute buckets.
- **Stops and exits.** Bucket highs and lows keep stops and targets exact. Decisions on a bucket's close fill at the
  next bucket's open, so they can be up to b minutes late.
- **Contract.** The contract is followed by strike on each later day's chain of the same series, and must not expire
  before the exit day. On days the strike is outside the data's ATM±10 window it has no bars.
- **P&L and positions.** P&L is booked on the entry day. One position per book per entry day is enforced; overlapping
  positional trades are prevented in the signals (no new signal while one is open).
- **Checked against the raw chain:** a BANKNIFTY BTST entry at 15:15 and its exit at 09:20 the next day fill at that
  contract's real bar opens.

**Event calendar (`gc_common.py`)** was hand-built from memory, not downloaded:
- Budgets (7, including the 2025 Saturday and 2026 Sunday special sessions).
- RBI MPC decisions (39, incl. the May 2022 off-cycle one).
- Election counting days and the 2024 exit polls (11).
- US FOMC decisions (49). These hit the next Indian session.
- Heavyweight results (Reliance, HDFC Bank, Infosys, TCS; 20 dates each). These dates are APPROXIMATE (may be off by a
  day), are after-hours, and hit the next session.

The macro dates are believed correct; the RBI dates after Feb 2026 are the least certain. Results dates are used only
by VOL-07's 'all' event set.

**Deviations and simplifications by strategy:**
- **EXP-01 hero-zero**
  - The direction choices are:
    - Breakout: the first 1-minute close beyond the 12:00-to-entry range, until 14:50.
    - Trend: the index versus its open.
    - Both: a call and a put, one Rs 5k ticket each.
  - "Lock 2x after 4x" is a ladder rung.
  - Square-off at 15:20, not 15:25, to keep the next-minute fill inside the data.
- **EXP-02 gamma blast**
  - Straddle compression is ATM CE+PE / spot < 0.3% at the start time.
  - The IV-spike and VIX-not-collapsing conditions are not modelled.
  - "Index back inside the range" is an index stop at the broken edge.
- **EXP-03** uses orb.py's ORB15 (5-minute close, first signal, cutoff 13:30), restricted to expiry days.
- **EXP-04** computes max pain over the ATM±10 strikes only (the data holds no others). A T-1 variant is included.
- **EXP-05** puts an entry candidate every 2 minutes from 09:30 to 14:30 (not every minute). Re-entry after an exit can
  therefore be 1 minute later; this was done to keep memory within the shared machine's limit.
- **OI-01**
  - PCR is over the 21 strikes in the data, not the full chain.
  - Entry is at 15:15 on the signal day in the nearest monthly ATM, exiting 3 sessions before expiry at the latest.
  - The "PCR normalises" exit is not modelled.
- **OI-02**
  - "Rising" is implemented as the condition holding at two consecutive snapshots.
  - A signal fires only when the condition starts.
  - Exit when the opposite condition appears.
  - VWAP is time-weighted, because there is no index volume.
- **OI-04** exits the next session at 09:30 / 12:00 / 15:15, or when the index touches the max-OI strike.
- **VOL-01** skips expiry days (the source traded the next weekly, which the data does not have).
- **VOL-02**
  - Combined-premium VWAP is time-weighted.
  - The Supertrend trail on the combined premium is not modelled (a 10% trail after +20% is used instead).
  - The compression precondition is not modelled.
- **VOL-03**
  - IVP is the India VIX percentile of yesterday's close, used for BANKNIFTY too.
  - The "IVP > 50" exit is not modelled.
  - Entries are skipped if any macro event falls in the hold.
- **VOL-05** uses the nearest monthly. Events where that monthly expires before the event are skipped, because the data
  has no next month.
- **VOL-06** does not model the "expected > implied move" filter.
- **VOL-07**
  - For overnight news, the post-event range starts at 09:15.
  - The IV filter compares the ATM IV at the signal with the ATM IV at the minute before the announcement, or with the
    previous session's 15:20 IV.
- **POS-01**
  - "Strong close" means the top 25% of the day's range at 15:14 and above the TWAP.
  - Event nights before macro event sessions are always skipped.
- **POS-02**
  - The Varsity matrix is mapped as: first half of the series means more than 14 calendar days to the monthly expiry.
    In the first half, a 5-day hold buys OTM2 and a 10-day hold ATM; in the second half, 5 days buys OTM1 and 10 days
    ITM1.
  - Out at the next 09:20 after a reversal close, or at the end of the hold, or 3 sessions before expiry.
- **POS-04** uses only weekly series (BANKNIFTY to Nov 2024).

### Caveats

- **No bid/ask.** Fills on Rs 2-10 options and on wide strikes are modelled. Real fills are likely worse, which only
  strengthens the negative verdicts.
- **Multi-day paths** use bucketed bars after the entry day, so decision-based exits there can be a few minutes late.
  Positions on strikes that leave the ATM±10 window have gaps.
- **Low statistical power.** The event strategies have 60-280 walk-forward trades, and POS-02 only 17, so a small edge
  could hide there. Nothing in the in-sample results suggests one, though: the best variants are negative or tiny.
- **The event calendar is hand-made.** Results dates in particular are approximate.
