# HUNT h36: the single best honest plan at Rs 1 lakh, fixed lots, seen day by day

Written 8 Oct 2026. Option BUYING only. Rs 1,00,000 capital. Fixed lots: no Kelly, no growing size.

- Code: `research/hunt/h36/`
  - `flags.py` attaches h26's OI filter to each trade.
  - `plan.py` does all the numbers.
- Logs: `scratchpad/hunt/h36/`
  - `plan.log`, `run.log`, `flags.log`;
  - `plans.csv`, `sizing.csv`, `boss_compare.json`, `months_*.csv`.

No new rule was made and nothing was tuned. This reuses:
- h24's trade table `trades24.parquet`. It covers Liquidity 15+5, 1-ITM nearest monthly, the h14 limit entry
  (+0.5%, resting 3 minutes, no chase), the app's own exits, h10 impact, app charges, and the real spread from the
  6 Oct 2026 option-chain snapshot (BN 0.16%, MIDCP 0.21%).
- h23's Rs 1 lakh walk, with its free-cash check.
- h26's OI filter code.

Checks:
- The walk reproduces h24 exactly: Plan A +167/day and Plan B +198/day in the holdout.
- Central case is κ 0.02. κ 0.04 and a 1.5× spread are shown as stress.

## Verdict (read this first)

1. **The best honest plan is Plan A: BANKNIFTY Liquidity 15+5, 1 lot, with the app's own exits.**
   - It made **Rs 65/day** before the holdout and **Rs 167/day** in the holdout, after all costs.
   - Gross it made Rs 152 and Rs 362/day.
   - That is about **Rs 1,400-3,500 a month on average**.
   - In a single month, expect anything from about **-Rs 9,000 to +Rs 18,000**.
   - **4 to 5 months in 10 lose.**
2. **Rs 5,000/day with Rs 1 lakh and fixed lots: NO.**
   - The plan earns Rs 65-167/day per lot. Rs 5,000/day needs **30 to 77 BANKNIFTY lots**.
   - That needs about **Rs 22-63 lakh**.
   - Rs 1 lakh safely carries 1 lot.
3. **Rs 5,000 days are real, but rare.**
   - Plan A had a day of +Rs 5,000 or more on **2.2% of days** before the holdout and **3.2%** in the holdout.
   - That is about **once every 30-45 trading days**, or roughly 5-8 a year.
   - **The typical day is Rs 0.** On 40-52% of days there is no trade at all.
   - The typical day *with* a trade **loses about Rs 400**.
   - All the profit comes from the best ~10 days a year. Those 10 days make 2 to 2.5 times the year's total net.
4. **Plan B (add MIDCPNIFTY 1 lot)** adds a little on average (+Rs 14-31/day) but **more than doubles the bad days
   and the drawdown**.
   - Holdout max drawdown: -Rs 83k, against -Rs 32k for Plan A.
   - Holdout chance of capital falling to Rs 50k within a year: 28%, against 1% for Plan A.
   - At κ 0.04 Plan B is worse than Plan A in both periods.
5. **Plan C (B plus the OI filter) is post-hoc.**
   - It looks best in the holdout (+Rs 256/day), but the filter failed its own pre-registered bar (BH q 0.13 > 0.10).
   - Before the holdout it added only +Rs 10/day, and it hurt in 2021 and 2023.
   - Log it on paper. Do not trade it.
6. **Boss's 7 paper days do not look like this plan.** They swing about twice as hard (day-to-day spread Rs 4.5k
   against Rs 1.9-2.6k).
   - Two near-+Rs 5k days and three days of -Rs 3k or worse in 7 sessions would happen under Plan A well under 2% of
     the time.
   - His seven days add up to **-Rs 3,791**.
   - The big green days came from many arms running at once. Most of those arms are long-run losers (h19-h22). They
     were not an edge.

## What each plan is

| plan | what | status |
|---|---|---|
| **A** | BANKNIFTY Liquidity 15+5 (15-minute + 5-minute books), **1 lot**. 1-ITM nearest monthly. Limit at +0.5% for 3 minutes, no chase. App exits unchanged. Skip if free cash cannot pay. | BN 1 lot was the only size that passed h17's pre-holdout ruin test. h17 pre-registered A + MIDCP. **Dropping MIDCP was decided after the holdout** (h23/h24, spread evidence). So A is honest in its parts, but its final choice is partly post-hoc. |
| **B** | A + MIDCPNIFTY 1 lot (same rules) | **h17's pre-registered plan** |
| **C** | B, but skip a trade when the 15-minute OI build-up opposes it (h26 "BU15") | **POST-HOC.** The filter missed its adoption bar. Its holdout was read once, by h26. |

## 1. Day by day: how the plans behave (κ 0.02, real spread, Rs 1 lakh, fixed lots)

- **Pre-holdout:** 1 Oct 2021 to 30 Sep 2025, 989 trading days.
- **Holdout:** 1 Oct 2025 to 5 Oct 2026, 249 trading days.
- All Rs figures are net of all costs, per trading day (days with no trade count as Rs 0).
- Bootstrap: stationary blocks (mean 10 days), 4,000 simulated 250-day years, drawn from that period's own days.

| | A pre | A **holdout** | B pre | B holdout | C pre | C holdout |
|---|---|---|---|---|---|---|
| net Rs/day, mean | **65** | **167** | 79 | 198 | 89 | 256 |
| net Rs/day, median | 0 | 0 | 0 | -20 | 0 | 0 |
| median day *with a trade* | -387 | -442 | -439 | -641 | -409 | -648 |
| gross Rs/day (Boss's headline) | 152 | 362 | 236 | 682 | 239 | 724 |
| days with any trade | 48% | 60% | 59% | 77% | 57% | 76% |
| days ≥ +Rs 1k | 7.8% | 14.1% | 10.0% | 15.7% | 9.8% | 14.9% |
| days ≥ +Rs 3k | 3.7% | 4.4% | 4.9% | 9.6% | 4.9% | 10.0% |
| **days ≥ +Rs 5k** | **2.2%** (22) | **3.2%** (8) | 3.3% (33) | 6.4% (16) | 3.2% (32) | 6.8% (17) |
| days ≤ -Rs 3k | 1.2% | 2.4% | 2.7% | 9.2% | 2.5% | 8.8% |
| days ≤ -Rs 5k | 0% | 1.6% (4) | 0.3% | 3.2% (8) | 0.3% | 2.4% |
| best day | +18.1k | +19.4k | +37.2k | +55.4k | +37.2k | +55.4k |
| worst day | -4.6k | -7.2k | -9.0k | -10.6k | -9.0k | -8.8k |
| longest losing streak (trade days in a row) | 16 | 8 | 16 | 14 | 16 | 13 |
| longest losing streak (sessions in a row) | 5 | 5 | 6 | 11 | 6 | 11 |
| longest time below the previous high | 623 sessions | 160 | 643 | 165 | 631 | 165 |
| **max drawdown** | **-39.9k** | **-32.5k** | -47.8k | **-82.6k** | -37.8k | -69.9k |
| lowest capital (from Rs 1 L) | 71k | 91k | 63k | 85k | 64k | 86k |
| green months | 25/48 (52%) | 8/13 (62%) | 23/48 (48%) | 6/13 (46%) | 23/48 (48%) | 7/13 (54%) |
| worst month | -9.2k | -12.6k | -19.2k | -44.1k | -20.7k | -35.3k |
| best 10 days as % of total net | 205% | 245% | 236% | 370% | 197% | 286% |
| **P(positive year)**, bootstrap | **73%** | **88%** | 67% | 71% | 69% | 77% |
| **P(capital falls to Rs 50k within a year)** | **<0.5%** | **1%** | 6% | **28%** | 5% | 20% |
| P(a Rs 50k peak-to-trough drop within a year) | 1% | 3% | 12% | 64% | 11% | 54% |
| P(capital below Rs 25k within a year) | 0% | 0% | 1% | 14% | 1% | 9% |
| one year: 10th / 50th / 90th percentile | -16.5k / +14.9k / +50.7k | -3.6k / +41.1k / +89.8k | -29.8k / +17.7k / +74.4k | -58.3k / +46.9k / +160.9k | -25.9k / +20.1k / +76.8k | -37.7k / +60.8k / +171.8k |
| one month (21 days): 10th / 50th / 90th percentile | -6.8k / -0.1k / +11.6k | -9.0k / +2.3k / +17.7k | -10.2k / -0.9k / +17.7k | -20.7k / -0.5k / +35.6k | -9.7k / -0.7k / +17.9k | -19.1k / +0.4k / +35.9k |
| P(losing month), bootstrap | 50% | 40% | 54% | 51% | 53% | 49% |

**Stress tests (mean net Rs/day, pre / holdout):**

| | A | B | C |
|---|---|---|---|
| κ 0.04 (more impact) | 62 / 143 | 46 / 56 | 57 / 116 |
| spread 1.5× | 52 / 132 | 52 / 140 | 63 / 200 |

- Plan A barely moves under either stress.
- Plans B and C lose most of their edge at κ 0.04. That comes from MIDCP's thin book.
- At κ 0.04, B's holdout P(capital to Rs 50k) rises to 42%.

**Pre-holdout by year (net Rs):**

| year | A | B | C |
|---|---|---|---|
| 2021 (Oct-Dec) | -10.5k | -10.5k | -23.3k |
| 2022 | -9.6k | -9.6k | +8.5k |
| 2023 | +8.3k | +0.5k | -4.8k |
| 2024 | +41.2k | +82.9k | +87.2k |
| 2025 (Jan-Sep) | +35.2k | +14.9k | +20.9k |

The edge was absent in 2021-22. That is one more reason to expect long flat stretches.

**Holdout months (net Rs, κ 0.02):**

| month | A | B | C |
|---|---|---|---|
| Oct 2025 | -5.4k | +0.3k | +0.3k |
| Nov | +1.2k | -6.6k | -6.6k |
| Dec | -0.3k | -0.6k | -0.0k |
| Jan 2026 | +21.0k | +34.0k | +34.0k |
| Feb | +9.2k | +42.2k | +43.6k |
| Mar | +6.9k | +7.0k | +6.7k |
| Apr | **-12.6k** | **-44.1k** | -35.3k |
| May | -10.4k | -17.9k | -18.0k |
| Jun | +4.5k | -3.2k | +1.7k |
| Jul | +13.0k | +18.0k | +17.7k |
| Aug | +4.0k | -3.9k | -3.9k |
| Sep | +13.2k | +27.3k | +26.7k |
| Oct (to 5th) | -2.7k | -3.1k | -3.1k |

### The best and worst 10 days (net Rs)

**Plan A, holdout**
- Best: 1 Feb 2026 +19,418; 8 Jul +16,892; 28 Sep +13,059; 12 Jun +11,833; 21 Jan +9,424; 16 Jan +9,008;
  23 Mar +8,927; 2 Jan +6,188; 3 Aug +3,820; 24 Aug +3,739.
- Worst: 15 Apr 2026 -7,205; 3 Feb -6,337; 19 Mar -5,983; 1 Oct 2025 -5,800; 3 Jun -3,120; 7 May -3,099;
  6 Apr -2,972; 11 Aug -2,780; 23 Apr -2,763; 11 Dec 2025 -2,467.

**Plan A, pre-holdout**
- Best: 29 Apr 2024 +18,113; 25 Apr 2025 +17,180; 22 Feb 2023 +17,055; 25 Oct 2021 +14,069; 13 Mar 2023 +13,659;
  4 Jun 2024 +12,179; 18 Mar 2025 +11,503; 30 Aug 2022 +9,528; 6 Jan 2025 +9,504; 30 Sep 2024 +9,502.
- Worst: 29 Oct 2021 -4,568; 1 Feb 2024 -4,559; 3 Jun 2024 -3,719; 18 Aug 2025 -3,675; 8 May 2025 -3,515;
  6 Dec 2021 -3,462; 11 Apr 2025 -3,392; 8 Apr 2022 -3,372; 7 Feb 2025 -3,329; 8 Feb 2022 -3,314.

**Plan B, holdout**
- Best: 1 Feb 2026 +55,351; 8 Jul +22,614; 24 Sep +20,454; 23 Mar +17,306; 28 Sep +14,117; 13 Feb +12,200;
  12 Jun +11,833; 16 Jan +11,339; 21 Jan +9,424; 8 Jan +7,964.
- Worst: 15 Apr 2026 -10,588; 23 Apr -9,878; 6 Apr -8,784; 1 Oct 2025 -6,544; 19 Mar -5,983; 11 Jun -5,728;
  3 Feb -5,504; 4 Feb -5,316; 9 Jan -4,898; 27 Nov 2025 -4,811.

**Plan B, pre-holdout**
- Best: 4 Jun 2024 +37,211; 7 May 2024 +21,807; 29 Apr 2024 +18,113; 25 Apr 2025 +17,984; 22 Feb 2023 +17,055;
  26 Aug 2025 +16,028; 18 Mar 2025 +16,005; 25 Oct 2021 +14,069; 13 Mar 2023 +13,659; 8 May 2025 +12,237.
- Worst: 1 Feb 2025 -9,030; 8 Sep 2025 -6,950; 28 Mar 2025 -5,396; 4 Jul 2025 -4,815; 18 Aug 2025 -4,757;
  10 Feb 2025 -4,707; 1 Apr 2025 -4,672; 28 Feb 2024 -4,574; 29 Oct 2021 -4,568; 24 Jun 2025 -4,226.

**Plan C**
- Holdout best days are the same as B's.
- Holdout worst days: 6 Apr 2026 -8,784; 23 Apr -8,304; 1 Oct 2025 -6,544; 19 Mar -5,983; 3 Feb -5,504; 4 Feb -5,316;
  9 Jan -4,898; 27 Nov 2025 -4,811; 7 Nov 2025 -4,563; 25 Feb -4,214.
- The filter removed B's worst day (15 Apr) and part of 23 Apr. Full lists are in `plan.log`.

**Read this as:**
- One day (1 Feb 2026, Budget day) made a third of Plan B's holdout profit.
- Without its 2-3 best days a year, each plan is roughly flat.
- So the plan only pays if you are trading on the rare runaway days. **Skipping days, or stopping after a bad week,
  is the surest way to miss them.**

## 2. Why fixed lots at Rs 1 lakh cannot reach Rs 5,000/day

| plan | net Rs/day at 1 lot (pre / holdout) | lots needed for Rs 5,000/day (pre rate / holdout rate) | premium for one set today (holdout median) | capital needed* | drawdown at that size (pre / holdout) | MIDCP capacity (h10) |
|---|---|---|---|---|---|---|
| **A** | 65 / 167 | **77 / 30** BN lots | Rs 21k | **Rs 63 L / Rs 22 L** | -30.7 L / -9.7 L | n/a |
| B | 79 / 198 | 64 / 26 sets | Rs 46k (BN 21k + MIDCP 25k) | Rs 89 L / Rs 44 L | -30.6 L / -20.8 L | **about 4 lots**: 26-64 MIDCP lots do not exist |
| C | 89 / 256 | 56 / 20 sets | Rs 46k | Rs 72 L / Rs 31 L | -21.2 L / -13.6 L | same problem |

\* Capital = two positions open at once (the 15-minute and 5-minute books can overlap) × premium at today's prices,
plus the max drawdown at that size.

This scaling is **straight-line and optimistic**: at 26+ lots, impact grows. h14 found that the ~26-BN-lot plan only
broke even at κ 0.04.

**Why Rs 1 lakh cannot do it:**
- One BANKNIFTY 1-ITM lot costs about **Rs 21,000** today (lot 30 × about Rs 700).
- Two books can be open at once, so Rs 1 lakh funds at most about 2 lots per book by premium.
- h17's pre-registered ruin test (P(capital < Rs 50k in a year) < 5%) allowed **only 1 BN lot**. With 2 lots it was
  6%, with 3 lots 16%, with 4 lots 26%.
- Even 4 lots at the holdout rate is about Rs 670/day. That is 13% of the goal, at a 1-in-4 chance of halving the
  account.
- To earn Rs 5,000/day you need either about 30-77× the lots, which means Rs 22-63 lakh, or an edge 30-77× larger.
  **No study in this hunt (h1-h35) found such an edge.**

**Realistic income with Rs 1 lakh, Plan A, 1 lot:**

| | per day | per month | per year |
|---|---|---|---|
| average (pre-holdout rate to holdout rate) | Rs 65-167 | **Rs 1,400-3,500** | Rs 15,000-41,000 (bootstrap median) |
| bad month or year (10th percentile) | | -Rs 7k to -9k | -Rs 4k to -17k |
| good month or year (90th percentile) | | +Rs 12k to +18k | +Rs 51k to +90k |
| chance a month loses | | 40-50% | 12-27% chance the year loses |
| chance of a Rs 1 lakh month | | 0% | |

That is about 15-40% a year on Rs 1 lakh in a decent year. It is worth having, but it is **not an income**.

## 3. Boss's paper days against the plan

Boss's paper account: 28 Sep -56, 29 Sep +589, 30 Sep -6,014, 1 Oct +5,032, 5 Oct +4,955, 6 Oct -4,551,
7 Oct -3,746.

| | Boss, 7 days | Plan A pre | Plan A holdout | Plan B holdout |
|---|---|---|---|---|
| total | **-Rs 3,791** (mean -542/day) | | | |
| day-to-day spread (sd) | **Rs 4,455** | Rs 1,850 | Rs 2,610 | Rs 5,068 |
| share of days ≥ +Rs 4.9k | 2 of 7 (29%) | 2.2% (1 per 45 days) | 3.2% (1 per 31) | 6.4% (1 per 16) |
| share of days ≤ -Rs 3k | 3 of 7 (43%) | 1.2% | 2.4% | 9.2% |
| chance of 2+ days ≥ +4.9k in 7 | | 1.0% | 2.0% | 7.0% |
| chance of 3+ days ≤ -3k in 7 | | 0.01% | 0.05% | 2.1% |

The same dates in the backtest (data ends 5 Oct):

| date | Boss | Plan A | Plan B | what h19/h22 found |
|---|---|---|---|---|
| 28 Sep | -56 | **+13,059** | +14,117 | Liquidity was not on the phone yet; it was added 1 Oct |
| 29 Sep | +589 | 0 (no trade) | 0 | |
| 30 Sep | -6,014 | +1,246 | +93 | |
| 1 Oct | +5,032 | -630 | -1,079 | Boss's green came from Liquidity's *first-day* rules (ATM, no index stop) plus the ORB family (h22) |
| 5 Oct | +4,955 | -2,048 | -2,048 | no replayable strategy made it (h22) |

**What the distribution says:**
- **Rs 5k days do happen with the honest plan, about once every 1-1.5 months.** Boss is right that they exist.
- **They do not come two in a week.** Under Plan A:
  - two near-Rs 5k days in one week happen in about 1-2% of weeks;
  - three losses of Rs 3k or more in one week happen in about 1 week in 2,000-10,000.
- Boss's account swings like 3-4 strategies at once, each betting 1 lot: ORB, ORB Fresh, Sweep, Fade, Pine scripts,
  Solo and Liquidity.
- More arms means bigger green days **and** bigger red days. All of those arms except Liquidity lose over the years.
  So the swings grow and the average falls. The 7-day sum is negative.
- **A +Rs 5k paper day says the account is volatile, not that it has an edge.** The edge is judged by the average over
  hundreds of days. For the only strategy that passed, that average is Rs 65-170/day per lot.
- On the exact dates, the plan and Boss's account had opposite days 4 times out of 5. Single days are noise.

## 4. What passed, what didn't (one line each, h1-h35)

**Passed (weakly):**
- **Liquidity 15+5** with its own exits (h4/h10/h14/h17/h24).
- **BANKNIFTY** carries it (h23/h24).
- The **+0.5% limit entry** helps a little (h14).

**Kept on paper only:**
- The MIDCP leg (h24).
- The BU15 OI filter (h26: +Rs 81/day holdout across 5 indices; +Rs 55/day on BN+MIDCP; not adopted).

**Faded:**
- NIFTY R17 overnight (h31: +2.6 L in 2025, -1.5 L in 2026).
- BTST.

**Failed everything:**
- ML (h1).
- Stocks (h2, h8).
- Trend-day pyramiding (h5).
- Pairs (h6).
- More timeframes or indices (h9, h11, h23).
- Filters (h12, h25, h28).
- Strikes (h13, h16).
- ORB family (h19-h21).
- Overnight cues (h27).
- Option-price signals (h29, h30).
- Jump-hunting (h32).
- News (h33).
- Premium-point exits (h34: worse than Liquidity's own exits).
- App/Jarvis levels (h35, still finishing: every level family loses before the holdout, and no level filter was
  kept).

**Ways of growing the account that worked on paper (h7, h15, h16):**
- Kelly or growing size reached Rs 300-1,100/day.
- That came with 50-85% drawdowns. Boss ruled them out.

## 5. Plain recommendation

1. **Trade Plan A only:** BANKNIFTY Liquidity 15+5, 1 lot, app exits, +0.5% limit entry, no chase.
   - Take **every** signal. The profit is in a few runaway days.
   - Expect Rs 1.4-3.5k a month on average and a -Rs 30-40k drawdown at some point.
   - Review (not panic) if capital falls below Rs 60k.
2. **Turn off every other arm on real money** (ORB family, Pine scripts, Solo). They are what makes the paper account
   swing ±Rs 5k a day while losing on balance.
3. **On paper only:** MIDCP 1 lot and the BU15 filter. Use h24's 30-signal spread and fill check to decide on MIDCP.
   Re-test BU15 on new data after about 150 more BN trades.
4. **Rs 5,000/day is a capital question, not a strategy question.**
   - At Rs 65-167/day per lot it needs roughly Rs 22-63 lakh.
   - It also needs a BANKNIFTY book deep enough that 30-77 lots do not move the price. h14 says that is doubtful at
     κ 0.04.
   - With Rs 1 lakh and fixed lots, it is **not reachable**.

## Honesty notes

- **Plan A was not strictly pre-registered.**
  - h17 pre-registered BN1+M1.
  - Dropping MIDCP (h23/h24) came after the holdout was seen.
  - BN 1 lot was the pre-registered size, and BANKNIFTY was positive before the holdout in every spread model.
- **The "real spread" is one snapshot** (6 Oct 2026, 11:03 IST, a calm day). Pre-holdout spreads were probably wider.
  So the pre-holdout numbers are, if anything, flattered.
- **Liquidity's exits were tuned on Feb 2024 to Feb 2026** (OBUY_FINAL). That overlaps 5 holdout months. The 2021-23
  figures are the most honest, and they are about zero.
- **Bootstrap limits.** It assumes the future looks like the sampled period. The pre and holdout periods give quite
  different answers (P(positive year) 73% against 88% for A), so read the pair as a range.
- **Plan C** uses a filter whose holdout was already read (h26). It is shown because Boss asked. It is not evidence.
- The fixed-lot walk's free-cash check skipped 0-2 trades. The results are effectively fixed 1 lot per index.
