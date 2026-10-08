# HUNT h31: buy a CE / PE near the close, sell after the next open (overnight option buying)

Written 2026-10-08. Code: `research/hunt/h31/` (`PREREG.md`, `feat.py`, `build.py`, `test.py`, `extra.py`).
Logs and tables: `scratchpad/hunt/h31/` (`feat.log`, `build.log`, `test_pre.log`, `variants_pre.csv`, `wf.csv`,
`holdout.log`, `holdout_families.csv`, `extra.log`, `index_overnight_pre.csv`, `carry_pre.csv`).
Everything is option BUYING only: 1-ITM CE or PE, 1 lot, Rs 1,00,000.

## Verdict

**NO. Overnight option buying from 15:20 does not give a proven edge after costs, and Rs 5,000/day is out of reach.**

- **The direction signal is real.**
  - A strong close tends to carry on overnight, and a weak close tends to fall further.
  - The rules that follow the close beat a coin-flip side with the same contract and exit (BH q < 0.001). They did so
    again in the holdout.
- **The money is not proven.**
  - No variant passes the profit test once the number of tries is counted (best BH q = 0.085 against 0.05).
  - Hansen SPA p = 0.19, so the best variant does not beat "do nothing".
  - Walk-forward by year lost **Rs -44,738** over 2022 to Sep 2025.
- **Every family lost money in 2026**, summed over all indices.
  - The best family, "all agree" (R17), made +Rs 2.6 lakh in 2025 and lost Rs -1.5 lakh in 2026.
  - That is the BTST story again: strong in 2020-23, fading after.
- **The unconditional overnight call is a loser.**
  - NIFTY monthly, out at 09:16: Rs -44/day before the holdout, and **Rs -1,136/day in the holdout**.
  - BANKNIFTY, FINNIFTY and SENSEX lost in every exit.
  - The overnight gap is too small to pay theta, the spread and the charges.
- **Holdout (info only, read once, no variant was promoted):**
  - The best pre-holdout variant made **+Rs 43,792** (Rs 245/day, 81 trades).
  - A coin-flip side on the same nights made Rs -7,035.
  - It still had a Rs -31k drawdown and lost in 4 of 12 months.
- **Rs 5,000/day at Rs 1 lakh: NO.** Even if the best variant were real (about Rs 200-245/day per lot):
  - it would need **21-26 lots**;
  - that is about **Rs 1.8-2.6 lakh of premium overnight**, 2-2.6× the capital;
  - the drawdown at that size would be **Rs 6.5-11.8 lakh**.

## What was tested

- **Entry:** decide on the 15:19 bar close and buy at the option's 15:20 open (app fill +5 bps). Only information
  known by 15:20 is used.
- **Contracts:** 1-ITM.
  - **M:** the nearest monthly.
  - **W:** the nearest weekly ("next-week" weekly; Dhan stores only the nearest weekly, so this is it once it has
    2 or more sessions left).
  - The contract is never held into its expiry day, and no trade is entered on an expiry day.
- **Exits on the next session (fixed before results):**
  - 09:16 open
  - 09:30 open
  - 10:15 open
  - **GX gap rule:** if the 09:15 bar is against the trade, sell at 09:16; otherwise hold to 10:15.
  - **Amendment 1 (Boss: points are premium rupees), added before any P&L:** P15 / P20 / P25 / P30. The target is
    entry +N premium points and the stop is entry −N, with a time exit at 10:15.
  - **Point targets and stops are checked minute by minute on the option's own 1-minute HIGH and LOW (wicks, not
    closes). If both are hit in the same minute, the stop counts first.** A gap through either level at the open
    fills at the open (stops at −10 bps).
- **Costs:**
  - GROSS = raw option prices.
  - NET = app fills + app charges (STT 0.15%) + the h24 real half-spread on entry and exit: NIFTY 0.16%,
    BANKNIFTY 0.16%, FINNIFTY 0.42%, MIDCPNIFTY 0.21%, SENSEX 0.20%.
  - STRESS = 1.5× the spread.
- **Periods:**
  - PRE (all choosing): option-data start (NIFTY Aug 2020, BANKNIFTY / FINNIFTY Aug 2021, MIDCP / SENSEX 2023) to
    30 Sep 2025.
  - HOLDOUT: 1 Oct 2025 to 29 Sep 2026, read once.

### Side rules (17)

| rule | what |
|---|---|
| R01 / R02 | CE always (unconditional call) / PE always |
| R03 | close location: CE if the 15:19 price is in the top 25% of the day's range, PE if in the bottom 25% |
| R04 | strong close, CE only (BTST) |
| R05 | fade the close location |
| R06 / R07 | last-hour trend: follow / fade |
| R08 / R09 | day's move vs the previous close (±0.3%): follow / fade |
| R10 | breadth (share of 214 F&O stocks up): CE if 60% or more, PE if 40% or less |
| R11 | India VIX change on the day: CE if down 3% or more, PE if up 3% or more |
| R12 | CE when VIX is below its 60-day median |
| R13 | US ES futures 09:30→14:30 IST: follow (data only from May 2024) |
| R14 | FII index-futures positioning from the previous evening's NSE participant OI: follow |
| R15 / R16 | CE on FOMC or US-CPI nights / CE on FOMC nights only (h28's calendars) |
| R17 | all agree: CE if close location ≥ 0.6, the day is up and breadth ≥ 50%; PE if all three are opposite |

### Filters (3)

| filter | what |
|---|---|
| F0 | every night |
| F1 | one-night holds only (skip weekends and holidays: theta) |
| F2 | F1, and only when the contract has 7 or more days to expiry |

**Honest variant count:** 17 rules × 3 filters × 2 contracts × 8 exits × 5 indices = **4,080 declared**.
- 3,848 had trades and 3,116 had 20 or more trades.
- BH, SPA and the walk-forward use all of them.
- On top of that: 95 descriptive index tables, and 280 info-only holdout family reads.

### Data limits

- **FII cash flow of the day is not usable.** NSE publishes it around 18:00-19:00, after a 15:20 entry, and h27
  found no free bulk history. R14 uses the previous evening's participant OI instead.
- **US futures:** only Yahoo's hourly ES bars from May 2024.
- **Breadth before Oct 2024** uses the 15:30 daily close, because there are no stock minutes before then. That is a
  10-minute look-ahead.
  - Check: on 2,462 index-days with both versions, the R17 side was the same on 98.1% of days.

## 1. The overnight move itself (index, PRE)

Mean move from the 15:19 close to the next 09:15 open, in %. The t-statistic is in brackets.

| condition | NIFTY | BANKNIFTY | FINNIFTY | MIDCP | SENSEX |
|---|---|---|---|---|---|
| all nights | +0.11 | +0.06 | +0.04 | +0.16 | +0.08 |
| close in top 25% | **+0.18** | +0.18 | +0.14 | +0.20 | +0.15 |
| close in bottom 25% | +0.03 | -0.06 | -0.05 | +0.08 | -0.02 |
| day up ≥ 0.3% | **+0.17 (t 8.1)** | +0.17 (5.6) | +0.15 | +0.27 (6.9) | +0.15 |
| day down ≥ 0.3% | +0.07 | -0.04 | -0.05 | -0.02 | -0.05 |
| breadth ≥ 60% | +0.16 (7.1) | +0.13 (4.0) | +0.11 | +0.25 (5.2) | +0.16 (6.6) |
| FOMC night | +0.15 | +0.10 | -0.09 | +0.12 | +0.14 |
| weekend / holiday | +0.10 | +0.06 | +0.01 | +0.11 | +0.08 |
| average size of the move | 0.39 | 0.43 | 0.42 | 0.53 | 0.31 |

- **Strength carries overnight.** But even the best condition adds only about +0.1% over an ordinary night.
- **The average move is about 0.4%,** so the direction is right only a little more often than not.

## 2. What it costs to carry a 1-ITM option overnight

A coin-flip side is the "no edge" price: the mean of the CE and PE results, net, Rs per trade, PRE.

| index | monthly, GX | monthly, 09:16 | weekly, GX | weekly, 09:16 | premium of 1 lot (M / W) |
|---|---|---|---|---|---|
| NIFTY | -183 | -245 | -141 | -202 | Rs 12.0k / 7.0k |
| BANKNIFTY | -258 | -292 | -309 | -236 | Rs 13.3k / 7.2k |
| FINNIFTY | -162 | -380 | -168 | -217 | Rs 10.2k / 5.4k |
| MIDCPNIFTY | -235 | -245 | -48 | -139 | Rs 16.0k / 6.0k |
| SENSEX | -200 | -304 | -264 | -244 | Rs 7.2k / 4.9k |

- **The hurdle:** a rule has to add about Rs 150-300 per trade of real direction just to break even.
- **The unconditional call:**
  - In PRE it made money on NIFTY weekly (+Rs 14k to +102k depending on the exit) and lost on BANKNIFTY, FINNIFTY
    and SENSEX in every exit.
  - In the holdout it lost everywhere, for example NIFTY monthly 09:16 **-Rs 2.03 lakh** and BANKNIFTY monthly
    **-Rs 1.72 lakh**.

## 3. The 4,080 variants (PRE)

| test | result |
|---|---|
| share of variants (with 20+ trades) with net > 0 | 36%. The median is -Rs 154 per trade |
| beats a coin-flip side (same nights, contract and exit), BH | **390 variants at q < 0.05**: R03, R04, R08, R17, R10 and R06 |
| mean net > 0 (t-test), BH | **0 at q < 0.05** (best q = 0.085) |
| Hansen SPA / White RC vs not trading | **p = 0.19 / 0.43** (best t = 3.66) |
| walk-forward by year | **-Rs 44,738** (table below) |
| promoted (all gates) | **none** |

By rule family (variants with 20+ trades):

| rule | share positive | median Rs/trade | best q vs coin-flip |
|---|---|---|---|
| R17 all agree | 91% | +250 | 0.000 |
| R03 close location, follow | 82% | +151 | 0.000 |
| R04 strong close, CE only | 68% | +174 | 0.000 |
| R08 day trend, follow | 65% | +71 | 0.000 |
| R15 / R16 event-night CE | 76% / 100% | +262 / +413 | 0.06 / 0.13 (only about 30 FOMC nights per index; about Rs 25/day) |
| R10 breadth | 44% | -28 | 0.003 |
| R13 US futures | 44% | -127 | 0.06 |
| R14 FII OI | 15% | -264 | 0.13 |
| R11 / R12 VIX | 24% / 4% | -206 / -352 | 0.14 / 0.22 |
| R01 CE always | 24% | -209 | 0.003 |
| R05 / R07 / R09 fades | 0% | -400 to -660 | about 1 |

**Filters and contracts:**
- Skipping weekends or holidays (F1) did not help: median -145 vs -117 for F0.
- Requiring a far expiry (F2) was worse: -236.
- Weeklies beat monthlies on the median (-70 vs -207), but that is mostly NIFTY.

Top variants in PRE (net at the real spread; Rs/day over all sessions):

| variant | trades | gross | net | net at 1.5× spread | Rs/day | hit | max DD | worst night | years + | BH q (profit) |
|---|---|---|---|---|---|---|---|---|---|---|
| NIFTY, W, GX, R17 | 443 | 231,176 | 187,357 | 181,818 | 198 | 46% | -45,232 | -7,676 | 6/6 | 0.69 |
| NIFTY, W, 10:15, R04 | 254 | 211,723 | 185,948 | 182,629 | 197 | 55% | -26,547 | -8,333 | 5/6 | 0.22 |
| NIFTY, W, 09:30, R04 | 255 | 210,289 | 184,410 | 181,077 | 195 | 59% | -24,030 | -8,714 | 6/6 | 0.22 |
| BANKNIFTY, M, P15, R17 | 432 | 231,768 | 166,550 | 155,953 | 211 | 56% | -32,188 | -7,351 | 4/5 | 0.22 |

The edge is shrinking. For example, NIFTY W GX R17 by year: 2020 +43k, 2021 +67k, 2022 +21k, 2023 +50k, 2024 +6k,
2025 (Jan-Sep) +1k.

**Walk-forward** (each year trades the best variant on all earlier years):

| test year | pick | trades | net | net at 1.5× spread |
|---|---|---|---|---|
| 2022 | NIFTY, W, 10:15, R01 (unconditional call) | 143 | -47,288 | -49,109 |
| 2023 | NIFTY, W, 10:15, R08 | 94 | +30,450 | +29,580 |
| 2024 | NIFTY, W, 10:15, R17 | 78 | +3,873 | +3,229 |
| 2025 (Jan-Sep) | NIFTY, W, 10:15, R17 | 70 | -31,774 | -32,950 |
| **total** | | 385 | **-44,738** | **-49,250** |

## 4. Holdout (1 Oct 2025 to 29 Sep 2026, read once, info only)

| variant | trades | gross | net | Rs/day | hit | max DD | worst night | coin-flip side, same nights |
|---|---|---|---|---|---|---|---|---|
| best PRE: NIFTY, W, GX, R17 | 81 | 53,326 | **+43,792** | +245 | 50% | -31,090 | -6,972 | -7,035 |
| WF 2025 pick: NIFTY, W, 10:15, R17 | 81 | 45,269 | +35,765 | +200 | 50% | -31,857 | -7,859 | -23,589 |
| unconditional CE, NIFTY M 09:16 | 179 | -169,593 | -203,291 | -1,136 | 40% | -227,081 | -13,987 | |
| unconditional CE, BANKNIFTY M 09:16 | 211 | -126,929 | -172,063 | -812 | 40% | -200,496 | -13,105 | |
| unconditional CE, FINNIFTY / MIDCP / SENSEX M 09:16 | | | -92.7k / -151.4k / -16.1k | | | | | |

- **The best PRE variant's holdout months:** Oct -9.7k, Nov +4.8k, Dec +5.8k, Jan +1.3k, Feb +12.0k, Mar +8.5k,
  Apr -15.4k, May +23.2k, Jun +7.6k, Jul +14.7k, Aug -0.6k, Sep -8.4k.
  - That is 4 losing months of 12. A bootstrap gives a 38% chance of a losing month.
- **Families across all indices, contracts and exits (F0, holdout):**

  | family | variants with net > 0 | beat the coin-flip side |
  |---|---|---|
  | R03 | 48% | 98% |
  | R17 | 41% | 70% |
  | R04 | 23% | 59% |
  | R08 | 5% | 68% |
  | R01 | 0% | |

  - By index, R17 made money on NIFTY (+2.5 lakh summed over 16 variants) and BANKNIFTY (+4.3 lakh). It lost on
    FINNIFTY, MIDCP and SENSEX.
  - **Read:** the direction holds out of sample. The profit does not hold reliably.

## 5. Size, capital and risk (best PRE variant, NIFTY weekly, GX exit, R17)

| | PRE | holdout |
|---|---|---|
| Rs/day at 1 lot | 198 | 245 |
| lots for Rs 5,000/day | 26 | 21 |
| premium held overnight at that size | Rs 1.84 lakh | Rs 2.07 lakh |
| max drawdown at that size | Rs -11.8 lakh | Rs -6.5 lakh |
| worst single night, 1 lot | Rs -7,676 | Rs -6,972 |
| P(losing month), bootstrap | 38% | 38% |

- **What Rs 1 lakh can actually hold:** about 10-14 NIFTY weekly lots, at Rs 7-10k each. That is at most about
  Rs 2,000-2,700/day IF the PRE average held.
- **The risk at that size:** about Rs 3-6 lakh of drawdown, which is ruin long before the target.
- **Worst nights:**
  - At 1 lot, the worst night of any top variant was Rs -7.7k to -10.8k.
  - The unconditional call's worst holdout night was Rs -14.0k.

## 6. Bottom line

1. Index gains do arrive overnight, and a strong 15:20 close predicts a slightly better gap.
2. A bought 1-ITM option pays about Rs 150-300 a night in theta, spread and charges. The predictable part of the
   gap (+0.1%) barely covers that.
3. After 4,080 honest tries:
   - nothing passes the profit test;
   - SPA does not reject "do nothing";
   - the walk-forward lost money;
   - the only rules that look good are close-strength momentum rules, the same BTST pattern OBUY_FINAL saw fade.
     They lost money in 2026 when summed over indices.
4. The NIFTY R17 rule's positive holdout (+Rs 44k at 1 lot) is worth **paper tracking only**: "buy the NIFTY weekly
   1-ITM call at 15:20 when the close is in the top 40% of the range, the day is up and most F&O stocks are up (put
   for the mirror); sell at 09:16 if it opens against you, else at 10:15."
5. **Rs 5,000/day on Rs 1 lakh: NO.**
