# HUNT h28: calendar and scheduled-event days for option BUYING

Written 2026-10-08. Code: `research/hunt/h28/` (`PREREG.md`, `cal.py`, `index_study.py`, `build.py`, `test.py`,
`liqfilter.py`). Logs and tables: `scratchpad/hunt/h28/`. Raw public pages: `scratchpad/hunt/h28/raw/`.
Everything here is option BUYING only: 1-ITM nearest-expiry CE or PE, 1 lot, Rs 1,00,000.

## Verdict

**NO. No calendar or scheduled-event day gives an option-buying edge that survives costs and honest testing.**

- **Option trades:** 8,820 pre-registered variants were run. These cover 42 calendar flags × 5 indices × 4 entry
  times × 6 exits × 2 direction rules. None survives.
  - Hansen SPA p = 1.00 and White RC p = 0.97: the best variant does not beat "do nothing".
  - Against a coin-flip direction, the best BH q is 0.74.
  - Walk-forward by year (pick the best so far, trade it next year) **lost Rs -18,414** over 2022 to Sep 2025.
- **Liquidity 15+5 filter:** 420 variants (42 flags × skip/only × 5 indices). None survives. The best BH q is 0.42.
- **Rs 5,000/day: NO.** Calendar days cannot even pay for themselves on average.
  - Blind 1-ITM buying loses Rs 120-300 per trade after costs.
  - The calendar flags do not lift the average above zero in a way that repeats.
- **The holdout was not needed for any survivor.** The info-only runs on it were mixed:
  - +Rs 12.9k (WF pick)
  - +Rs 22.7k
  - +Rs 33.4k
  - -Rs 23.0k
  - -Rs 17.4k
- **What the index data does show is real but too small or too rare to trade:**
  - Budget days have about 2.1-2.4× a normal day's range. The extra movement comes 11:00-14:00, during the speech.
    There are only 5-6 budget days in the option era.
  - The session after an FOMC decision opens higher. The gap is +0.36% vs +0.10% on other days for BANKNIFTY
    (BH q 0.03). But that move happens overnight, before an intraday buyer can enter.
  - July to September days are quieter (range about 0.8× normal). Wednesdays and Diwali week are slightly quieter.

## What was tested

| part | what | variants | result |
|---|---|---|---|
| A | Index direction / gap / range on flag days, daily 2006 to Sep 2025 (MIDCP from 2022); hour-by-hour timing on minutes 2020/21 to Sep 2025 | 1,050 daily tests (210 flag×index × 5 metrics) + descriptive timing | 1 direction flag after BH (gdp_next MIDCP, n = 16); 3 gap; 10 range |
| B | Buy 1-ITM CE/PE at 09:20 / 10:15 / 12:00 / 14:00 on flag days, 6 exits, side from walk-forward history ("prior") or the move since the open ("mom") | 8,820 (6,984 with >= 20 PRE trades) | 0 survivors |
| C | Each flag as a skip / only filter on the app's Liquidity 15+5 trades | 420 | 0 survivors |

**Honest count: about 10,300 tests.**

**Costs:** app fills (±5/10 bps) and app charges (today's STT 0.15%). On top of that, the real half-spread from h24 is
charged on entry and exit:

| index | half-spread |
|---|---|
| NIFTY | 0.16% |
| BANKNIFTY | 0.16% |
| FINNIFTY | 0.42% |
| MIDCPNIFTY | 0.21% |
| SENSEX | 0.20% (assumed) |

All results were also rerun at 1.5× these spreads.

**Periods:**
- PRE (used for choosing): before 2025-10-01.
- Holdout: 2025-10-01 to 2026-10-05.

## 1. Blind buying is the hurdle

PRE average net per trade at the real spread, averaged over the 6 exits. This is the price a buyer pays for having no
edge.

| index | 09:20 coin-flip | 12:00 coin-flip | 14:00 coin-flip | costs per trade | median premium (1 lot) |
|---|---|---|---|---|---|
| NIFTY | -131 | -122 | -138 | 82-86 | Rs 6-6.5k |
| BANKNIFTY | -164 | -145 | -150 | 96-100 | Rs 7-7.7k |
| FINNIFTY | -194 | -189 | -205 | 131-139 | Rs 5.7-6k |
| MIDCPNIFTY | -301 | -235 | -158 | 124-129 | Rs 7-7.7k |
| SENSEX | -156 | -118 | -119 | 78-83 | Rs 4.4-5k |

- A calendar flag would need to add about Rs 150 per trade of real direction to break even.
- The median tested variant earns -Rs 134 per trade. That is the same as blind buying.
- Only 24% of variants made money in PRE. That is about what chance gives when the average is negative.

## 2. Option trades on flag days (Part B)

The best PRE variants look good on their own. None of them is significant once you count how many were tried.

| variant (flag, index, time, exit, side rule) | PRE trades | PRE net Rs | net at 1.5× spread | WF years + (of 4) | p vs coin-flip direction | p vs random day | holdout (info) |
|---|---|---|---|---|---|---|---|
| January, NIFTY, 09:20, Liquidity exits, prior (= PE) | 105 | +100,164 | +98,758 | 4 | 0.047 | 0.0005 | +22,724 (20 trades) |
| expiry day, BANKNIFTY, 09:20, hold, prior (= PE) | 177 | +79,759 | +78,459 | 2 | 0.027 | 0.004 | +12,855 (13) |
| April, BANKNIFTY, 09:20, hold, prior (= CE) | 75 | +72,743 | +71,288 | 4 | 0.023 | 0.009 | +33,434 (20) |
| January, NIFTY, 10:15, hold, mom | 105 | +68,119 | +66,740 | 3 | 0.027 | 0.015 | -22,987 (20) |
| first 3 days of month, BANKNIFTY, 14:00, hold, mom | 148 | +67,619 | +65,160 | 4 | 0.001 | 0.0005 | -17,408 (38) |

- **Multiple testing.** With 8,820 variants, raw p-values like these are expected by chance.
  - Against a coin-flip side, the best BH q is 0.74. A post-hoc normal-approximation version gives q = 1.0.
  - Against a random day, the best BH q is 0.28. The post-hoc normal approximation gives 0.0006 for 29 variants, but
    none of them also beats a coin-flip direction.
  - Read together: some flag days MOVE more, which helps either side. But the calendar does not tell you WHICH SIDE.
- **SPA over all 6,984 tested variants:** p = 1.00 (best t = 2.63). White RC p = 0.97.
- **Walk-forward by year** (each year trades the variant that was best on all earlier years):

| test year | pick | test net Rs | top-10 average |
|---|---|---|---|
| 2022 | roll3, NIFTY, 10:15, hold, mom | +12,799 | +3,877 |
| 2023 | expiry day, BANKNIFTY, 09:20, Liquidity exits, prior | +13,103 | +803 |
| 2024 | expiry day, BANKNIFTY, 09:20, hold, prior | -29,285 | -10,988 |
| 2025 (Jan-Sep) | expiry day, BANKNIFTY, 09:20, hold, prior | -15,031 | -5,412 |
| **total** | | **-18,414** | **-11,720** |

The pattern is the usual one. The in-sample winner of 2021-23 (BANKNIFTY expiry-day puts) turned into a loser.

- **Size, if one still wanted to trade the best-looking one.** January NIFTY puts at 09:20 made about Rs 20k a year at
  1 lot, from about 21 trades in one month a year. That is Rs 78 per session averaged over the year.
  - Rs 5,000/day would need about 64 lots.
  - 64 lots is about Rs 5 lakh of premium at once, 5× the capital.
  - It would also mean trading in one month only, on a pattern that is not significant.

## 3. Index regularities (Part A, PRE)

Daily direction, open to close, 2006 to Sep 2025.

- **The baseline is negative.** Every index drifts down from the open to the close on an average day: NIFTY -0.04%,
  BANKNIFTY -0.04%. The positive return comes in the overnight gap (about +0.1% on average).
  - That is bad news for intraday buyers of either side. Calls fight the intraday drift, and puts fight theta.
- **Only 1 of 210 flag×index direction tests passes BH:** MIDCPNIFTY on the session after an Indian GDP release,
  +0.43% vs -0.12% (n = 16, 2022-2025). That is too few events to trade (4 a year).
- **Biggest raw direction effects for NIFTY / BANKNIFTY (not significant after BH):**

  | flag | NIFTY | BANKNIFTY |
  |---|---|---|
  | January | -0.13%/day (t -1.7) | -0.15% |
  | February | -0.13% | -0.17% |
  | RBI day | -0.20% (n 55) | |
  | Budget | -0.71% (n 24; since 2016 only -0.05%) | |
  | Last 2 days of month | +0.07% (t 2.3; since 2016 -0.01%) | |
  | First session after monthly expiry | +0.12% (since 2016 -0.01%) | +0.21% (since 2016 +0.02%) |

  Most "effects" vanish after 2016. That is the classic sign of a pattern that has been arbitraged away, or of noise.
- **Gaps (BH q < 0.05):** the session after an FOMC decision gaps up.

  | index | gap after FOMC | gap on other days |
  |---|---|---|
  | BANKNIFTY | +0.36% | +0.10% |
  | SENSEX | +0.34% | +0.13% |

  The move is overnight. An intraday buyer at 09:20 does not get it. A BTST call held through the FOMC night was not
  pre-registered here. OBUY_GC found generic BTST decayed after 2022, so treat this as an untested lead, worth about
  8 events a year.
- **Range (vs the 20-day average range; BH q < 0.05):**

  | flag | range ratio | note |
  |---|---|---|
  | Budget (NIFTY/BN/FIN/SENSEX) | 2.0-2.4× | rare (≈1 a year) |
  | January (BANKNIFTY) | 1.12× | |
  | Diwali week (NIFTY/SENSEX) | 0.90× | quieter |
  | Wednesday (NIFTY/SENSEX) | 0.98× | quieter |
  | November (MIDCP) | 0.82× | quieter |

### When in the day the move happens (minute data, NIFTY, PRE)

The table shows the hour-bucket absolute move as a ratio to the same bucket on non-flag days. Values above 1.3 are in
bold.

| flag | n | 09:15-10 | 10-11 | 11-12 | 12-13 | 13-14 | 14-15:30 | day range ratio |
|---|---|---|---|---|---|---|---|---|
| Budget | 6 | 0.70 | 0.95 | **2.07** | **3.40** | **3.27** | **1.68** | 2.35 |
| Election results | 9 | **1.37** | **1.82** | **1.87** | 0.89 | **2.04** | **1.41** | 1.84 |
| RBI policy (10:00) | 32 | 0.84 | **1.64** | 1.04 | 0.96 | 0.98 | 0.81 | 1.03 |
| MSCI review day | 21 | 0.89 | **1.51** | **1.53** | 0.80 | 1.05 | **1.44** | 1.34 |
| FTSE review day | 21 | **1.46** | **1.54** | 0.96 | 1.12 | **1.45** | 1.11 | 1.28 |
| After US CPI | 62 | 0.95 | 0.82 | 0.85 | 0.85 | 0.96 | 1.01 | 0.97 |
| After FOMC | 41 | 0.89 | 0.84 | 1.16 | 1.01 | 1.20 | 0.92 | 1.01 |
| Expiry day | 268 | 0.92 | 0.91 | 0.96 | 0.91 | 1.05 | 1.00 | 0.99 |
| July | 108 | 0.76 | 0.86 | 0.73 | 0.82 | 0.69 | 0.78 | 0.77 |

BANKNIFTY is similar:
- Budget: 4.4× in 12-13 and 3.8× in 13-14.
- RBI: 2.1× in 10-11.
- On MSCI days NIFTY made its high or low after 14:00 on 90% of days, against 65% normally. That is the closing
  rebalancing flow, but its direction is not known in advance.

**For a buyer:**
- On RBI days, the move is in the first hour after 10:00.
- On Budget days, it is 11:00-14:00.
- Neither gives a side. Both are too rare to build a daily income on. OBUY_GC already showed that straddles through
  them lose.

## 4. Calendar filters on Liquidity 15+5 (Part C)

Trades: BANKNIFTY and MIDCPNIFTY from h24 (real spread, κ 0.02). NIFTY, FINNIFTY and SENSEX from h23 (Roll spread).
PRE only. The test permutes the flag label over sessions (B = 2,000), with BH over 420 variants.

| filter | index | flag days with trades | mean net on flag days vs others (Rs) | gain if skipped, Rs/day | raw p | BH q |
|---|---|---|---|---|---|---|
| skip Budget | BANKNIFTY | 5 | -2,246 vs +162 | +11 | 0.001 | 0.42 |
| skip February | NIFTY | 42 | -837 vs -72 | +28 | 0.002 | 0.42 |
| skip February | MIDCPNIFTY | 22 | -1,237 vs +178 | +48 | 0.004 | 0.47 |
| skip Monday | MIDCPNIFTY | 27 | -1,085 vs +191 | +51 | 0.004 | 0.47 |
| only election days | NIFTY | 5 | +3,965 vs -179 | | 0.008 | 0.71 |

None passes q < 0.10, so the app's Liquidity rules stay unchanged. Skipping Budget day on BANKNIFTY is common sense, but
it is worth only about Rs 11/day.

## 5. Calendars: sources and what I could not get

| calendar | source | coverage | confidence |
|---|---|---|---|
| FOMC decisions | federalreserve.gov calendars + historical pages (parsed) | 2006-2026, scheduled only | high |
| US CPI release dates | ALFRED vintage dates of CPIAUCSL (parsed; one per month, day 10-18) | 2006-2026 | high |
| Union Budget | Wikipedia list (parsed); July 2024 corrected to 23 Jul | 2006-2026 | high |
| RBI MPC | recalled 2016-10 to 2020; obuy gc_common list 2020-2025; RBI site for Apr-Oct 2026 | 2016-10+ | medium-high |
| Elections | general 2009/2014/2019/2024 (recalled) + gc_common state list 2021+ | partial | medium |
| India CPI / GDP | rule: 12th (or next weekday) 17:30; last weekday of Feb/May/Aug/Nov 17:30 | 2012+/2006+ | medium (rule, not official dates) |
| Expiry, rollover | the data's expiry flag (option era); last-Thursday rule before | full | high in the option era |
| MSCI / FTSE / NSE rebalancing | rules: last session of Feb/May/Aug/Nov; 3rd Friday of Mar/Jun/Sep/Dec; last session of Mar/Sep | 2006+ | approximate |
| Holidays | weekdays without a session in the data | 2006+ | high |
| Diwali / Muhurat | recalled dates; Muhurat sessions dropped | 2006-2025 | high |
| Heavyweight results | gc_common recalled list: RELIANCE, HDFCBANK, INFY, TCS 2021-2025 | 2021-2025 | approximate (±1-2 days) |

**Not obtained:**
- **ICICIBANK results dates.** No public source was reachable, and inferring them from volume spikes would bias the
  range test.
- **Official MOSPI CPI/GDP release calendars** (rules used instead).
- **Official MSCI/FTSE/NSE effective dates** (rules used instead).
- **State elections before 2021.**
- **RBI policy dates before Oct 2016.** The pre-MPC reviews were irregular.
- **Muhurat-session trading itself.** It is a 1-hour evening session and was dropped.

## 6. Caveats

- **Expiry-day entries.** These use that day's expiry (`near`), where theta is extreme. They are included as
  pre-registered.
- **Prior side for MIDCPNIFTY.** It uses NIFTY's history, because MIDCP daily data starts in 2022.
- **Test resolution (post-hoc note).** B = 2,000 bootstrap draws cap the smallest p at 0.0005. BH over 8,820 variants
  then cannot reach q < 0.10 unless hundreds of variants are tiny. So I added a normal-approximation p as a post-hoc
  check. It does not change the verdict: the best coin-flip-direction q is 1.0, and SPA is 1.00.
- **Holdout runs.** These were information only (no survivor existed). The variants were picked on PRE before the
  holdout was read.
