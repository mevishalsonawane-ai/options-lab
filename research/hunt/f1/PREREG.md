# F1 Strategy Factory (India) pre-registration: indicator screen and at most 20 option-buying rules

Written 14 Oct 2026, BEFORE any screen statistic, trade outcome or rule result was computed. It is committed on its own;
the write-up (`research/HUNT_F1_FACTORY.md`) quotes that commit. Nothing below may change after that commit. A change
needs a new entry, a new pre-registration and a fresh holdout.

Boss (14 Oct): "Use gamma exposure too ... and all the other things which could help, like EMA, VWAP, OI and all other
things." TODO F1 (Strategy Factory), India part. Research only: no app change, nothing trades.

## 1. Data

- The brain's L3 cube built from the Dhan lake on Drive (`python -m research.brain.refresh --source lake`): index,
  India VIX and nearest-expiry options ATM-10..ATM+10 with OI, 1-minute, for NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY
  and SENSEX. Only sessions whose cube has an option chain are used (`chain_ok_share` > 0 on the day).
- Features: every registered L4 feature (`research/brain/FEATURES.md`, 201 features incl. the F1 additions in
  `features/factory_f.py`), point in time, as stored by `features build`. A feature that is NaN on more than 80% of
  an index's screen rows is reported as "no data" and not tested for that index (futures OI/volume, the futures VWAP
  and profile families: the lake's futures minutes start in 2026).
- R28's GEX feature was not pushed when this was written; the screen uses the brain's GEX proxy (`gex_net_norm`,
  `gex_near_share`). R28/R29 own the full GEX study.

## 2. Periods (every period earlier studies read; then the F1 split)

What earlier studies read (inventory of 14 Oct 2026, from `research/experiments/entries/`, the HUNT_R2* docs,
`research/hunt/r25/PREREG.md` and `research/brain/holdout.py`):

| study | locked holdout it READ |
|---|---|
| H-series H1-H47, R1-R12 (Dhan hunt cache) | 1 Oct 2025 - 6 Oct 2026 (NIFTY, BANKNIFTY, FINNIFTY; H25 also MIDCPNIFTY, SENSEX) |
| R14 | 1 Oct 2025 - 5 Oct 2026 |
| R15 | BANKNIFTY 1 Oct 2025 - 23 Feb 2026 |
| R16, R17, R18, R20, R20b, R20c, R21, R22 (release) | BANKNIFTY 25 Aug 2025 - 23 Feb 2026, NIFTY 13 Oct 2025 - 13 Apr 2026; R16 daily 9 Apr - 9 Oct 2026 |
| R19 | BANKNIFTY 1 Aug 2024 - 14 Feb 2025 |
| R19b | BANKNIFTY 3 Mar - 22 Aug 2025 |
| R19c | 1 Apr 2025 on (BANKNIFTY, NIFTY) |
| I6, I10, I11, I12 | the last 12 months of the release (Feb 2025 - Apr 2026) |
| R24 what-if patterns | NIFTY, BANKNIFTY 27 Apr - 12 Oct 2026 |
| R25 hero | NIFTY, BANKNIFTY expiry days 5 Aug 2021 - 29 Dec 2022 (Rs 1-10 OTM options only) |
| OF1 | daily NIFTY, BANKNIFTY 13 Oct 2025 - 13 Oct 2026 |
| Brain v1 (not read yet) | 27 Apr 2026 on: reserved, F1 does not touch it |

**F1 holdout (locked, read ONCE for all F1 rules): 1 Jan 2023 - 30 Jun 2024, all five indices** (every session with an
option chain). It is the only block of the lake that no earlier study used as a locked holdout, for any index.

Honest caveat: it is not unseen. The H-series, OBUY's walk-forward, R24's pattern mining (design 2021 - Apr 2026,
NIFTY/BANKNIFTY ATM+-3) and R25's design (expiry days from 2023) read it as DESIGN data. Those studies found no
indicator edge, so the leak can only have told us what does NOT work; it cannot have tuned an F1 rule. The F1 screen
and rule-building never read it.

- Embargo (not used at all): 15-31 Dec 2022 and 1-14 Jul 2024.
- **Design: 2021-08-01 .. 2022-12-14 and 2024-07-15 .. 2026-04-13.** Never used: 14 Apr 2026 on (the brain's embargo
  and locked holdout).
- Inside design, for the screen:
  - **fit split**: 2021-08-01 .. 2022-12-14 and 2024-07-15 .. 2024-12-31;
  - **selection split**: 2025-01-01 .. 2026-04-13.
- Walk-forward of the rules, by year, on design only. Folds: 2022, 2024H2 (15 Jul - 31 Dec), 2025, 2026 (to 13 Apr).
  Each fold's thresholds come from design data BEFORE that fold only (2021 is warm-up only).

## 3. Targets (labels; never features)

At a decision minute t (the decision uses the bar that closes at t + 1 min; the trade fills at the open of t + 1):

- **Index move**: log return of the index from the close of t to the close of t + 15, t + 30, t + 60 (same session; NaN
  if the session ends first), %. Also |return over 30 minutes| (size), reported separately: size is already known to
  be predictable (R16), so it is not used for rules.
- **Arm outcome**: net rupees per lot of buying the option at the open of t + 1:
  - strikes: ATM, and 1-ITM (call ATM - 1 step, put ATM + 1 step);
  - exit families (Boss's India option-buying rules):
    - **X1 "quick"**: stop -15% of the fill (a resting SL-M), Boss's lock (from +Rs 1,000 a lot gross, a stop rests
      Rs 500 under the best price, i.e. keep Rs 500 and trail Rs 500: r19c `Floor("F1000_500")`), time stop at 30
      minutes unless the price is +5% or more, square-off 15:10 (15:05 on expiry day);
    - **X2 "runner"**: stop -15%, the same lock, no time stop, square-off 15:10 (15:05 on expiry day).
  - The screen uses ATM X1 and ATM X2, calls and puts (4 arm targets).

Simulation, conservative inside a bar:
- the stop is checked before the lock can rise on that bar's high;
- the lock uses the best price BEFORE the bar;
- a bar where the contract did not trade checks nothing.

A strike that leaves the ATM+-10 window is valued at its last trade; such trades are counted.

**Costs**:
- Fills (`research/obuy/costs.py`, mode `liq`): +-5 bps plus a half-spread of 1/2/4 ticks by the contract's last
  5-minute volume in lots; a stop fill is 10 bps and double ticks.
- Charges (`Costs("app")`, today's rates): Rs 20 an order, STT 0.15% on sells, exchange, SEBI, stamp, GST; BSE rates
  for SENSEX.
- No fill if the contract does not trade in t + 1 .. t + 3.
- **Lot sizes are today's** (NIFTY 65, BANKNIFTY 35, FINNIFTY 60, MIDCPNIFTY 120, SENSEX 20), because Boss's lock is
  in rupees for today's lots.

## 4. The screen (design data only)

- Decision grid for the screen: every 15 minutes, 09:30 .. 14:30 (21 points a session).
- Features with at most 6 distinct values (directions, states, build-up types, day of week, flags) are tested level
  by level (level vs the rest).
- Statistic, per index, feature (or level) and target:
  - The Spearman rank correlation (IC).
  - Its direction comes from the fit split.
  - On the selection split, the IC is taken in that direction, with a day-clustered t-test: the per-day means of the
    product of the two standardized ranks, across days. The p-value is one-sided.
  - The same is run pooled over the five indices (ranks within each index).
- 2-feature combinations, capped:
  - Per target and index, take the 12 single features with the largest fit-split |IC| (distinct names).
  - Each pair's score is the mean of the two direction-signed standardized ranks; it is tested like a single feature.
  - That is at most 66 pairs per target and index.
- **BH at q = 0.10 over every screen test** (all indices, pooled, targets, levels, pairs). Survivors also need the
  same sign on the selection split as on the fit split, and |IC| >= 0.02.
- The full screen table is reported. Most features are expected to be noise.

## 5. Rules (at most 20, built mechanically from the screen)

**Classic rules**: two rules Boss named, fixed now, whatever the screen says:
- **C1 trend stack**: buy the call when 5-minute EMA9 > EMA21, 5-minute Supertrend is up and the index is above its
  option-volume VWAP. Buy the put on the mirror image.
- **C2 OI + VWAP**: buy the call when the put-writing bias is positive (`bu_writing_bias_15m` > 0) and the index is
  above the option-volume VWAP. Buy the put on the mirror image.

**Screen rules**: up to 18, from the screen's survivors, in order of the selection-split t-statistic. Only arm targets
and the 15/30/60-minute direction targets count; at most 2 rules per feature name.
- The rule: when the feature (or pair score) is in its top quintile in the screen's direction, buy the call; in the
  bottom quintile, buy the put.
  - For a survivor on an arm target, only that target's side is traded.
  - Quintile cut-offs come from earlier data only (walk-forward; for the holdout, all design data).
- Strike and exit:
  - an arm-target survivor uses its own exit (X1 or X2) at 1-ITM;
  - a direction survivor uses X1 for 15/30 minutes and X2 for 60 minutes, at 1-ITM.
  - 1-ITM is the arms' strike (Liquidity, VIX divergence); ATM is the screen's.
- Entry:
  - decisions every 5 minutes 09:30 .. 14:30;
  - one entry a side a day (the first signal);
  - per index, one lot.

Each rule has a **random-entry twin**:
- the same days, sides, strikes and exits;
- the entry minute drawn uniformly from the same 09:30 .. 14:30 grid;
- 500 draws.

p = the share of twin sets whose total net is at least the rule's (one-sided).

## 6. Gates (all required; the factory's L6 gates)

1. Design:
   - net P&L > 0 after costs;
   - positive in at least 3 of the 4 walk-forward folds;
   - p vs twins < 0.05;
   - at least 40 trades.
2. Holdout (read ONCE, all rules together, after design results are written and committed):
   - net > 0;
   - p vs twins < 0.05;
   - **BH q <= 0.10 over the rules taken to the holdout**;
   - max drawdown no worse than Rs 20,000 a lot;
   - at least 40 trades.

Also reported, with the BH count widened to every screen test plus every rule, as the strictest view.

A rule that passes may go to PAPER only: behind a switch that is off by default, logged in the Autopilot change
timeline, undoable in one tap, and never live by itself (CLAUDE.md). A rule that fails is a note only.

## 7. Reported for every rule

For design, walk-forward and holdout separately, each with trades and days:
- trades a day, win rate, average win and loss;
- P&L a day, % green days, worst day, max drawdown;
- big winners cut short: exited by the lock or time stop, then the option's best price before the square-off would
  have added at least Rs 2,000 a lot more;
- p vs twins and BH q.

Crypto: a placeholder section in the write-up; the data watchdog adds it.

## Amendment 1 (14 Oct 2026, before any outcome; only fills gaps, changes nothing above)

- C1 and C2 trade 1-ITM with exit X1. They are scored like the screen rules (one entry a side a day, decisions every
  5 minutes 09:30 .. 14:30).
- Design results of a rule = its four walk-forward folds (2022, 2024H2, 2025, 2026) put together. 2021 is reference
  data only.
- A level (0/1) survivor becomes a rule only where it names an action:
  - level present -> trade the side its sign says;
  - a level survivor with a negative sign on an arm target ("trade when the level is absent") is not a rule.
- Pair scores in rules: each feature becomes its percentile in the reference data (earlier design data in the
  walk-forward; all design data for the holdout). The score is the mean of the two direction-signed percentiles.
  Its quintiles come from the same reference.
- P&L a day, % green days, worst day and max drawdown are on the rule's daily P&L over every session in the period
  that has a chain for at least one traded index; a day without a trade counts as 0.
- Holdout BH: m = every rule in the list (at most 20), whether or not it passed the design gates.
