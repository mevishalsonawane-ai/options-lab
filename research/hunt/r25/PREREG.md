# R25 pre-registration: the deep hero-zero study (expiry-day Rs 1-10 options) on the 6-year Dhan lake

Part A was written 14 Oct 2026, BEFORE any option outcome of any R25 period was computed. Only the expiry calendar and
the day counts had been read. It is committed and pushed on its own. Part B (the trigger rules) is added after the
design-only anatomy (Step 2) and BEFORE any rule is run on any period. Part B may only fill in what Part A leaves open
(the 3 trigger rules and their threshold grids). It changes nothing in Part A. After the Part B commit, nothing here
changes. A change needs a new entry, a new pre-registration and a fresh holdout.

Boss's request (14 Oct 2026): "Deep analyse the hero-zero strategies we discussed on Tuesday". On Tue 13 Oct 2026 the
NIFTY 22700 CE and 22650 CE ran from a few rupees to about Rs 100 in the afternoon, and the app's Hero arm took no
trade. **13 Oct 2026 is not in the lake** (the lake ends Mon 12 Oct 2026), so it is a forward example only, using the
prices in Boss's screenshots and in R21. It chooses nothing.

## Data

- **Lake** (Drive `IraAlgo-data-lake`; read straight from its Parquet by `r25data.py`):
  - `options_rolling_1m/opt_{NIFTY,BANKNIFTY,SENSEX}_WEEK_<YEAR>`: Dhan "rollingoption", the nearest expiry at
    ATM-10 .. ATM+10 strikes, chosen at each minute;
  - `index_1m_{NIFTY,BANKNIFTY,SENSEX,INDIAVIX}_<YEAR>`.
- **Expiry days come from the data.** The files have no expiry column. A session is an expiry day when the ATM time value
  at 15:15 (CE + PE − intrinsic) is below 0.6% of spot and the next session's is more than twice it (the series rolls to
  the next contract).
  - This finds 271 NIFTY expiries (5 Aug 2021 – 6 Oct 2026). It agrees with the brain's calendar on every NIFTY date except
    30 Dec 2025, which the calendar misses.
  - For BANKNIFTY it finds the Sep 2023 – Jan 2024 Thursday monthlies, where the calendar's weekday rule is wrong.
  - Day counts by index and year are in `results/census.json`.
- **BANKNIFTY**: weekly expiries only, to the last weekly (13 Nov 2024). Later BANKNIFTY expiries are monthlies and
  are out of scope.
- **SENSEX**: BSE weeklies, from 2023 (the lake's first SENSEX options).
- **Strike coverage gap.** Only ATM±10 strikes are stored at each minute (NIFTY ±500 points, BANKNIFTY / SENSEX ±1,000).
  A cheap far-OTM strike has no rows until it comes within 10 strikes. The census reports:
  - how often the outermost stored strike on a side was itself above Rs 10, so every Rs 1-10 strike on that side was
    unseen;
  - how many jackpots were first seen already above Rs 10.
- **Missing prices while a position is open.** A held strike can leave the stored range:
  - Going further OTM: its value is taken as the outermost stored strike's price on that side. That is an upper bound,
    so it is slightly optimistic. Every result is repeated with such prices set to 0, and the count of affected trades
    is reported.
  - Going deep ITM: its value is the intrinsic value, a lower bound.

## Holdout (locked; read ONCE for all of R25)

**Holdout: every expiry day from the start of the lake to 31 Dec 2022.** That is NIFTY 74 expiries and BANKNIFTY 74 (5 Aug 2021 – 29 Dec
2022): 148 expiry days. SENSEX has no weeklies then. **Design: every expiry day from 1 Jan 2023 to 12 Oct 2026**: NIFTY 197
(to 6 Oct 2026), BANKNIFTY 98 (to 13 Nov 2024), SENSEX 175 (19 May 2023 – 8 Oct 2026): 470 expiry days.

Why this block. Here is what earlier studies read (inventory of 14 Oct 2026):

| study | data | hero / expiry-buying periods it read |
|---|---|---|
| OBUY_VALIDATION, OBUY_GC EXP-01, OBUY_FINAL | Dhan, Aug 2020 – Oct 2026 | anchored walk-forward. The first 2 calendar years were training only; test years 2022-2026 (app hero_zero, a 54-variant grid, 96 variants). No locked holdout. |
| H34 Hero grid | Dhan | design to 30 Sep 2025, holdout 1 Oct 2025 – 6 Oct 2026 (read; no Hero candidate reached it) |
| R12 F07 Expiry Hero | Dhan | design before 1 Oct 2025, holdout 1 Oct 2025 – 6 Oct 2026 (read) |
| R16 / R17 / R18 / R19C | release | holdout NIFTY ≥ 13 Oct 2025, BANKNIFTY ≥ 25 Aug 2025 (read). R19C: 2025-04-01 on. |
| R21 Hero (9 variants) | release | design NIFTY Apr–Oct 2025 + BANKNIFTY Feb–Aug 2025; holdout NIFTY 13 Oct 2025 – 13 Apr 2026, BANKNIFTY 25 Aug 2025 – 27 Jan 2026 (read) |
| R24 what-if patterns | lake | design 2021 – 26 Apr 2026 (pattern mining incl. expiry-day OTM2-3); holdout 27 Apr – 12 Oct 2026 (read) |

- Every locked holdout of the hero family lies in Apr 2025 – Oct 2026. The oldest lake block was never a locked holdout.
- Honest caveats:
  - OBUY's walk-forward printed results for the app's hero_zero rule and its grids on test year 2022. So the app
    baseline (H0 below) has been seen on 2022, and only 2021 is unseen for it.
  - R24's design mining covered 2021-22 at ATM±3, including "expiry-day OTM2-3 buys lose 90-95%".
  - None of R25's new rules (the lottery baskets, the triggers from Step 2) has ever been run on 2021-22.
- **R25's own census and anatomy (Steps 1-2) read design days only.** The census of the holdout days is added to the
  report only after the holdout read.
- The holdout is older: lots were NIFTY 50 and BANKNIFTY 25, and VIX was higher in 2022. Costs are today's (below) in
  every period, so the periods differ only in the market.
- The read is one command (`study.py holdout`). It writes a flag file and `results/holdout.json`, and refuses a second
  read.

## Costs and fills (every rule, every period)

- **Charges:** the app's model at today's rates (`research/obuy/costs.py` `Costs("app")`). That is Rs 20 an order,
  STT 0.15% of the sell, exchange (NSE 0.03553%; BSE 0.0325% for SENSEX), SEBI, stamp 0.003% of the buy, and GST 18%.
  Rs 20 an order is about 4-20% of a Rs 100-500 ticket.
- **Fills** (`Fills("liq")`):
  - Buy = the next bar's open + 5 bps + a half-spread of 1 tick (≥ 20 lots traded in the 5 minutes before), 2 ticks
    (some) or 4 ticks (none).
  - Sell = the open − 5 bps − the same half-spread.
  - A tick (Rs 0.05) is 1-5% of a Rs 1-5 option, so this is the dominant cost.
  - The lake has no bid / ask. This model is conservative for liquid NIFTY strikes (a real NIFTY Rs 3 option is often
    1 tick wide in total) and may be light for thin SENSEX strikes. Hence the stress test: 2 × the half-spread.
- **Entry orders:** a LIMIT at the ask, capped at the signal price × 1.2 + 0.10, worked for 3 minutes, never chased (as
  the app).
- **Targets** fill at their limit only once the price trades a tick through it.
- **Expiring worthless:** when the sell price after the spread is ≤ 0, no sell order is sent. Value 0, no charges.
- **The stop.** For a Rs 1-10 option the stop is the premium itself: the most a ticket can lose is the premium plus
  the buy-side charges. Rules with an explicit stop say so.

## Sizing and reporting

- Each rule has a rupee budget. Lots = floor(budget / (fill × lot)), and 0 lots skips. The lot is the one in force
  that day (NIFTY 50 → 25 → 75 → 65; BANKNIFTY 25 → 15 → 30; SENSEX 10 → 20).
- **Every rule is reported per lot** (1 lot every trade) **and per Rs 1,000 risked** (net ÷ premium paid incl. buy
  charges × 1,000). Lot-size changes don't distort these.
- For every rule, design, walk-forward and holdout, the report gives:
  - trades, hit rate (net > 0), average win and average loss;
  - P&L per expiry and % green expiries;
  - worst day, max drawdown and the longest losing streak (in trades and in expiries);
  - the share of net P&L from the single best day (lottery dependence);
  - the sample sizes.

## Step 1: census (descriptive; design days only until the holdout read)

- A **unit** is (expiry day, strike, side, slot), for the hourly slots 11:00, 12:00, 13:00 and 14:00 (bar close times
  11:00-11:59, …, 14:00-15:00).
- The unit's **start** is the first minute in the slot at which the option's traded close is in Rs 1-10. Its reference
  price is that close.
- **Reached k×:** the best traded high after the start ≥ k × the reference, for k = 5, 10, 20, 33. It is measured
  before 15:05 (bars starting ≤ 15:04) and before the close.
- **Splits:** index, year, weekday, VIX regime (VIX at the start: < 13, 13-17, 17-22, ≥ 22), slot, distance OTM (in
  strikes and in % of spot), and the reference price band (1-3, 3-5, 5-10).
- **For each 10× unit:**
  - the minutes at or above half its peak;
  - the give-back from the peak to the 15:05 price;
  - the share still ≥ 2× and ≥ 1× its reference at 15:05.
- Also reported:
  - per day, whether any unit reached 5× / 10× / 20× / 33×;
  - the coverage gap (above);
  - the outcome as a trade (fill at the next bar, as above, held to 15:05). This shows the jackpot rate and the
    average result side by side.

## Step 2: anatomy (design days only; point-in-time features with the look-ahead guard)

- **Decision grid:** every 5 minutes, bar closes 11:00 … 14:55. At each decision, every option whose traded close is in
  Rs 1-10 and has traded in the last 5 bars.
- **Outcome labels:**
  - JACKPOT = the best high before 15:05 ≥ 10× the decision price;
  - DUD = never ≥ 2×;
  - the trade's net per Rs 1,000 at the 15:05 exit and with "half at 5×".
- **Features** are computed from rows ≤ the decision row only. A test re-computes them on a day with the future blanked
  out and with the future set to absurd values, and requires identical values. The features:
  - index move over the last 5 / 15 / 30 minutes, in the option's direction;
  - speed (the 5-minute move ÷ the day's mean absolute 5-minute move so far);
  - a break of the day's high (CE) or low (PE) within the last 15 minutes;
  - beyond the 15-minute opening range on the option's side;
  - distance of the strike from spot in % of spot, in the day's range so far, in the previous session's range, and in
    expected-move units (spot × VIX / 100 × √(minutes left / (252 × 375)));
  - the option's IV (the lake's vendor IV) and its 15-minute change;
  - the strike's own OI change over 30 minutes (falling = writers covering);
  - the OI change at the same distance on the opposite side;
  - the change in PCR (Σ put OI / Σ call OI over the stored strikes) over 30 minutes;
  - the ATM straddle vs 15 minutes earlier and vs its low since 12:00;
  - a volume burst (the option's last-5-minute volume vs its 5-minute average since 11:00);
  - VIX and its change since the open;
  - distance to max pain (over the stored strikes);
  - the gap; the trend since the open and since 12:00, in the option's direction;
  - the option's own move (close ÷ its low since 12:00).
- **Ranking:** each feature in design-tercile bins (cut points from design rows), and the top 2-feature combinations,
  by:
  - LIFT = P(jackpot | state) / P(jackpot);
  - DUD LIFT;
  - EXPECTANCY = the mean net per Rs 1,000 at 15:05 and with half at 5×.
  Both sides are always shown. A state that lifts jackpots but has negative expectancy is reported as such.
- **Minimum support:** ≥ 2,000 rows and ≥ 60 expiry days.

## Step 3: rules (at most 10; all tested; BH across all of them plus R21's 9)

Common to all rules:
- one entry per index per expiry day per side;
- option = OTM, traded in the last 5 bars, with the app's stale-slack rule (no further-OTM strike priced above it by more
  than max(0.10, 25%));
- the costs and fills above.

| id | rule | indices | budget | exits |
|---|---|---|---|---|
| H0 | **the app's Hero arm today** (HeroRules.kt = R21 V0): straddle ≥ 15% over its low since 12:00 + 0.25% 15-min momentum, 13:30-14:45, Rs 1-5 | NIFTY | Rs 5,000 | app: half at 5×, the rest at 20× or 15:05; a minute close ≤ 0.4 × the fill sells (the −60% stop) |
| L1 | **lottery basket, 13:30, hold.** At the 13:30 bar close, buy the CE and the PE whose price is closest to Rs 3 within Rs 1-5, no prediction | all 3 | Rs 500 a side (Rs 1,000 an expiry) | hold to 15:05. The stop is the premium (max loss = premium + charges). |
| L2 | **lottery basket, 13:30, Boss's exits.** As L1 | all 3 | Rs 500 a side | ladder: sell half at 3×; then step lock: once the high reaches 5×, a close ≤ 2.5× sells the rest; once 10×, a close ≤ 5×; once 20×, a close ≤ 10×; else 15:05 |
| L3 | **lottery basket, 14:30, hold.** As L1 at the 14:30 bar close | all 3 | Rs 500 a side | hold to 15:05 |
| L4 | **lottery basket, 14:30, Boss's exits** | all 3 | Rs 500 a side | the L2 ladder |
| V4 | **R21's best design variant, re-tested on the lake**: one-sided (the option's own close ≥ 2.5 × its low since 12:00) + momentum agreeing, 13:30-15:00, Rs 1-15 | all 3 | Rs 5,000 | app |
| T1-T3 | **trigger rules from Step 2** (Part B), one side at a time, window and band as Part B states | all 3 | Rs 1,000 | each picks ONE exit on design by a rule fixed in Part B, from {hold to 15:05, the L2 ladder, the app's exits} |

(9 rules. The 10th slot is left unused on purpose.)

**Exit comparison (secondary, reported, not gated):** for every entry rule, the same trades with each exit:
- hold to 15:05;
- half at 3× / half at 5× (rest 15:05);
- the L2 step lock;
- a rupee trail: once the open profit is ≥ Rs 500 per Rs 1,000, sell at a close ≤ 50% of the best open profit;
- the app's exits.

Each of these gets a holdout p as well. A second BH over all of them is reported.

### Controls

- **Random-time twin** (trigger rules, H0, V4):
  - 1,000 twin sets;
  - each trade is re-entered at a uniformly random minute of the rule's window, same day, side, band, strike rule,
    budget and exits;
  - p = the share of twin sets with net ≥ the rule's.
- **Random-strike twin** (all rules): the same minute, a uniformly random other OTM strike on the same side in the band.
- **Lottery baskets** make no timing claim. Their test is whether the mean per-expiry net is > 0: a one-sided bootstrap
  over expiry days, 10,000 resamples. Their random-strike twin is also reported.

### Walk-forward (inside design)

- Anchored, by calendar year: fit on 2023 → test 2024; fit 2023-24 → test 2025; fit 2023-25 → test 2026 (to 12 Oct).
- Rules with a threshold grid (T1-T3) re-choose it in each fold from the training years only, by Part B's selection
  rule. The fixed rules (H0, L1-L4, V4) have nothing to fit, so their walk-forward is their per-year result.
- Honest caveat: the Step 2 anatomy saw every design year, so walk-forward is not fully clean for T1-T3. The holdout is.

### Gates (a rule passes only if ALL hold)

1. Design net > 0 (all design expiry days, at the rule's budget, with costs).
2. Walk-forward: the sum over the 3 test years > 0 AND at least 2 of the 3 years > 0.
3. Holdout (read once): at least 30 trades and net > 0.
4. Significance on the holdout:
   - trigger rules, H0 and V4: random-time twin p;
   - lottery baskets: the bootstrap p.
   It must pass **BH at q = 0.05 across the 9 R25 rules plus R21's 9 p-values (18)**.
5. Holdout net > 0 at 2 × the half-spread, and > 0 with out-of-range prices set to 0.
6. Lottery dependence: holdout net > 0 after removing the single best expiry day.
7. Holdout max drawdown < Rs 40,000 at the rule's budget (Boss's account stop).

## 13 Oct case study (Step 4)

- **13 Oct 2026.** Each rule's decision is reconstructed only as far as Boss's screenshots and R21's notes allow (no minute
  file). Stated as such.
- **The 10 biggest jackpot days of the design period** (by the best multiple reached by any Rs 1-10 unit; holdout days are
  added after the read): what each rule did, what was knowable at each minute, and when.

## If a rule passes

- **Paper only**, per CLAUDE.md: a labelled, logged, one-tap-undoable change to a paper arm, shown to Boss, and only
  after Boss's go-ahead.
- Never live by itself.
- R25 changes no app code.

If nothing passes: no app change. Say so plainly.

---

## Part B (written 14 Oct 2026, after the design-only census and anatomy, BEFORE any rule was run on any period)

### What Steps 1-2 found (design days only; `results/census.json`, `results/anatomy.md`)

- **Census:** 27,169 cheap-option units on 470 design expiries.
  - Some Rs 1-10 unit reached 5× before 15:05 on 257 of 470 days, 10× on 119, 20× on 52 and 33× on 25.
  - Per unit: 6.2% reached 5×, 2.6% 10×, 0.46% 33×. Close-confirmed: 4.8%, 2.1% and 0.31%.
  - The average unit held to 15:05 lost Rs 919 per Rs 1,000 after costs. 3.0% of units were profitable.
  - 10× units gave back a median 92.9% of the peak by 15:05. Only 46% were still ≥ 2× at 15:05.
- **Anatomy:** 173,624 decision rows on 467 days. Base rates: 2.42% jackpot, 80.9% dud.
  - The best lifts are:
    - the strike near in expected-move or day-range units;
    - a fresh break of the day's high/low or beyond the opening range;
    - max pain behind the option;
    - more time left.
  - The best pairs reach lift 2.0-2.3, and the dud rate falls only to 64-71%.
  - **Every state, single or pair, has negative expectancy**: the best is −Rs 330 per Rs 1,000. So the triggers below
    are tested because Boss asked for them, not because the design data promises a profit.
  - Own-strike OI *falling* (writers covering) has LOW jackpot lift (0.68). Jackpots come with OI *rising* on the strike
    and on the other side. "OI unwinding" is therefore tested in its supported form: other-side OI building.

### Data-quality fixes (found in the design census, fixed before any rule ran; they apply to every period)

1. **Mislabelled rows.** Rows of another index sit in some files. On 5 Apr 2024 the SENSEX file has BANKEX rows, with
   strikes near 54,000 and their own spot column. A row is kept only while its strike is within 12 strikes of this
   index's own close in that minute (the store holds ±10).
2. **Freak prints.** A further-OTM option cannot be worth more than a nearer one of the same side and expiry. Each bar's
   high / open / close is capped at 1.25 × + Rs 0.10 the high of the nearest stored nearer strike in the same minute.
   This is same-minute data only, so there is no look-ahead. It changed 0.028% of NIFTY bars, 0.006% of BANKNIFTY and
   0.19% of SENSEX in design.
3. **Targets need a confirmed price.** A target fills only when the high trades a tick through it AND the bar's close or
   the next bar's open is at or above it. A single freak print fills nothing.

SENSEX on 2 Feb 2024 (11:57-12:35) shows disorderly trading: every put traded far above fair value for a few minutes,
and the neighbour rule cannot remove it because all strikes spiked together. Results are reported with and without that
day wherever it matters.

### T1-T3 (code: `triggers.py`; one entry per side per index per expiry; budget Rs 1,000; Rs 1-10 band)

| id | trigger (checked at every bar close in the window) | window | strike | threshold grid |
|---|---|---|---|---|
| T1 | the index made a new day high (CE) / low (PE) within the last 15 minutes AND the strike's distance from spot ≤ θ expected moves (spot × VIX / 100 × √(minutes left / (252 × 375))) | 11:00-14:30 | the nearest-the-money eligible option meeting the distance | θ ∈ {1.0, 1.25, 1.44} |
| T2 | distance ≤ θ1 × the day's range so far AND max pain (over the stored strikes) on the other side of spot by ≥ θ2 % of spot | 11:00-13:30 | as T1 | θ1 ∈ {0.8, 1.0, 1.08}; θ2 ∈ {0.03, 0.058, 0.10} |
| T3 | the 15-minute index move with the option ≥ θ1 % AND the index beyond the 15-minute opening range on that side AND the mirror strike on the other side has 30-minute OI change ≥ θ2 % | 13:00-14:45 | the nearest-the-money eligible option | θ1 ∈ {0.10, 0.15, 0.25}; θ2 ∈ {0, 2.86} |

- **Eligible** = OTM, Rs 1-10, traded in the last 5 bars, passing the app's stale-slack rule.
- **Exit for T1-T3:** one of {hold to 15:05, the L2 ladder, the app's exits}, chosen together with the thresholds.
- **Selection rule** (all design days for the final rule; the training years in each walk-forward fold):
  - the best net per Rs 1,000 risked among combinations with at least max(15, 10% of the expiries) trades;
  - ties go to fewer trades;
  - if no combination qualifies, the strictest thresholds with hold to 15:05.
- **Random-time twins for T1-T3** use the nearest-the-money eligible option at the random minute, without the distance
  condition.
- **Exit-grid p-values** (secondary) are the bootstrap of per-expiry net for every rule × exit, under their own BH.

The family for gate 4 is the 9 R25 rules (H0, L1-L4, V4, T1-T3) plus R21's V0-V8 (18 p-values), as Part A says.
