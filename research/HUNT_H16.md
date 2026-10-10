# HUNT h16: cheaper, more convex options for Liquidity 15+5 at Rs 1,00,000 capital

Code: `research/hunt/h16/`
- `PREREG.md`: the 16 variants, the capital model, the windows and the choice rule, written before any h16 P&L.
- `build.py`: the path packs, for ITM1 / ATM / OTM1 / OTM2 on the nearest expiry, with h13's elasticity pass.
- `run.py`, run in two stages:
  - `pre`: the choice, made on data before 1 Oct 2025.
  - `hold`: one holdout test.

Logs and CSVs are in `scratchpad/hunt/h16/`.

Everything here is option BUYING. The signals and the index stop, level target, failed break, time stop and 15:10
square-off are the app's Liquidity 15+5 arm, unchanged (the h4/h7 port, through h13's code). Fills use h10's capacity
and impact model (κ 0.02, 15% participation cap, app charges per order slice). At 1 lot, ITM1 reproduces h10 to the
rupee: BN 144,334 / FIN 91,911 / MIDCP 102,311.

**The capital model.**
- Start with Rs 1,00,000 and compound both ways.
- At any moment, the premium tied up is no more than the free cash.
- Ruin means equity falls below Rs 25k; trading then stops.

## Verdict (plain language)

**Cheaper options do NOT earn more per rupee. Keep buying the 1-ITM option, but put at most one third of equity into
any one trade.**

1. **Per rupee of premium, OTM is no better.** At 1 lot before the holdout:

   | | 1-ITM | ATM | 1-OTM | 2-OTM |
   |---|---|---|---|---|
   | gross return on premium | 1.9% | 1.8% | 1.9% | 1.8% |
   | net return on premium | 0.9% | 0.7% | 0.7% | 0.3% |

   Cheaper options buy more lots but take the same index move. Per rupee they earn the same gross and lose more to
   costs.
2. **The pre-registered choice was the incumbent.** That is 1-ITM, with the arm's -15% stop, budgeting at most a third
   of equity per trade.
   - It had the best era-M score: Rs 1,117/day, Dec 2024 to Sep 2025, starting from Rs 1 lakh.
   - Next were 1-OTM scaled at 993, ATM scaled at 769 and 2-OTM scaled at 487.
   - SPA over the 16 variants: p = 0.39 on era M and 0.38 on all pre-holdout data. No variant is significantly better
     than another.
   - Every variant beats random entries (BH q < 0.001), so the signal is real. The strike choice adds nothing.
3. **What matters at Rs 1 lakh is how much you put into each trade, not the strike.**
   - The "full" budget (all free cash into the next signal) went below Rs 25k (ruin) from Oct 2021 in all 8 versions.
   - It also lost in era M in 7 of the 8.
   - Its 1-year probability of ruin was 25-67%.
   - The "third" budget never ruined in era M.
   - Removing the premium stop (`none`) hit ruin in 2021-23 for 1-ITM, 1-OTM and 2-OTM. Only ATM/none survived that
     period.
4. **Holdout (run once).** Results for the choice (1-ITM, scaled stop, third), starting from Rs 1 lakh on 1 Oct 2025:

   | | κ 0.02 | κ 0.01 | κ 0.04 |
   |---|---|---|---|
   | equity after 249 days | **Rs 3.81 L** | 7.04 L | 1.62 L |
   | net Rs/day (average over the year) | **Rs 1,128** | 2,426 | 248 |
   | gross Rs/day | Rs 2,542 | 3,751 | 1,682 |
   | max drawdown, % of peak equity | **60%** | 51% | 73% |

   - Worst day -Rs 37.9k; worst month -Rs 1.09 L (Apr 2026).
   - 6 of 13 months lost; bootstrap P(losing month) is 45%.
   - The year was lumpy. Equity ran from 1.0 L to 2.94 L by March, fell to 1.75 L in June, and reached 3.99 L in
     September.
   - On average it held 2.7 lots and skipped 14 trades for lack of cash. 1-ITM 1-lot premium in the holdout: BN about
     Rs 21k, FIN 20k, MIDCP 25k.
   - Random entries with the same exits, at 1 lot: +Rs 59 per trade against a null of -Rs 452, p = 0.0005.
   - By index, net: BN +1.93 L, MIDCP +0.89 L, FIN -0.01 L.
5. **P(ruin below Rs 25k)**, from a stationary-bootstrap Monte Carlo (2,000 paths, block of 10 days, starting at Rs 1
   lakh):

   | days resampled from | 1 year | 2 years |
   |---|---|---|
   | holdout days | **0%** (P(loss) 10%) | 0% |
   | all pre-holdout days, including 2021-23 when the edge was weak | **4.9%** (P(loss) 22%) | **9.6%** |

   - The deterministic replay that started at Rs 1 lakh in Oct 2021 survived, but with an **84.5% drawdown**.
6. **Time to target.**
   - Median time to double to Rs 2 L: 70-91 days. P(reaching it within 1 year) is 76-82%.
   - Median time to Rs 35 L, the capital the Rs 5,000/day plan needs (h10/h14): about 1.4 years (352-358 days), and it
     is reached at all within 2 years in only 27-44% of paths.
   - **At Rs 1 lakh, the realistic income is about Rs 300-1,100/day in year one.** It is compounding, not income. Rs
     5,000/day is not realistic until equity reaches tens of lakhs.

**Rs 5,000/day at Rs 1 lakh capital: NO.**
- The best case is about Rs 1.1k/day averaged over the first year, net (Rs 2.5k gross), and only at κ ≈ 0.02.
- Along the way you must stomach a 60% drawdown, and there is a 5-10% chance of falling below Rs 25k.

## Post-hoc holdout (all 16 variants; NOT used for anything)

Net Rs/day from Rs 1 lakh with the third budget, κ 0.02:

| | 1-ITM | ATM | 1-OTM | 2-OTM |
|---|---|---|---|---|
| scaled stop | 1,128 | 682 | 1,284 | 1,820 |
| no premium stop | 1,833 | 882 | 1,868 | **2,998** |

- Every full-budget variant made Rs -305 to +201/day, and 4 of the 8 hit ruin.
- 2-OTM with no premium stop looks best in the holdout, but **it hit ruin from Oct 2021 before the holdout**: its
  2021-23 Rs/day was -626, -164 and -174. That kind of reversal is what the locked holdout is there to catch.
- Read it as a hint for live paper-trading at most. It is not a rule.

## Pre-holdout table (start Rs 1 lakh at each window start; κ 0.02)

| variant | era W Rs/day | era M Rs/day (final equity) | era M max DD | net return on premium, 1 lot | MC 1-year P(ruin), era M |
|---|---|---|---|---|---|
| **ITM1/scaled/third (choice = incumbent)** | 1,916 (DD 84%) | **1,117 (3.32 L)** | 32% | 0.9% | 0.0% |
| ITM1/none/third | ruin | 969 | 35% | 0.7% | 0.0% |
| ATM/scaled/third | 2,507 | 769 | 35% | 0.7% | 0.0% |
| ATM/none/third | 845 | 597 | 42% | 0.7% | 0.1% |
| OTM1/scaled/third | 1,830 | 993 | 38% | 0.7% | 0.2% |
| OTM1/none/third | ruin | 833 | 45% | 0.6% | 0.3% |
| OTM2/scaled/third | 1,449 | 487 | 37% | 0.3% | 0.2% |
| OTM2/none/third | ruin | 388 | 35% | 0.3% | 0.3% |
| all 8 `full` variants | ruin | -361 .. +167 | 71-82% | (same as above) | 25-67% |

Per-year Rs/day for ITM1/scaled/third, restarting at Rs 1 lakh each year:

| 2021 (Q4) | 2022 | 2023 | 2024 | 2025 (Jan-Sep) |
|---|---|---|---|---|
| -372 | -34 | 115 | 6,197 | 1,243 |

Walk-forward picks (anchored by year), with the pick's test-year Rs/day against the incumbent's:

| test year | pick | pick | incumbent |
|---|---|---|---|
| 2023 | ITM1 | 115 | 115 |
| 2024 | ATM scaled | 5,978 | 6,197 |
| 2025 | OTM1 scaled | 1,555 | 1,243 |

## The rule (a few lines)

1. Trade the app's Liquidity 15+5 unchanged (BN 15m+5m, FIN 30m+5m, MIDCP 15m+5m). Buy the **1-ITM nearest-expiry**
   option, with the arm's -15% premium stop.
2. **Size every trade at most one third of current equity** (and never more than the free cash):
   lots = floor(budget / (premium × lot size)). If that is 0 lots, skip the trade.
3. Never more than 15% of the last 5 minutes' volume. Use h14's limit entry at +0.5% (not modelled here).
4. Expect to hold 1-4 lots at Rs 1 lakh, and drawdowns of 30-60% of equity. Never go "all in" on one signal.

## Honesty notes

- 16 variants, all counted. The choice used only pre-2025-10-01 data, and the holdout ran once.
- Prior knowledge, disclosed in PREREG: h13's equal-lot results for 1-OTM, ATM and 1-ITM, including its post-hoc
  holdout table. None of the 2-OTM, no-stop or Rs 1 lakh results were known.
- The Rs/day figures with compounding depend on the start date: 2021-23 was flat to negative for the edge. Each window
  restarts at Rs 1 lakh.
- Lot interpolation is linear between simulator grid points above 30 lots. Entry charges inside the cash check are
  approximated as half the round-trip charges.
- Gross is computed at bar prints. κ is still the unknown that decides the result: at κ 0.04 the holdout made only
  Rs 248/day.
- Inherited caveats (h4/h10/h13): the exits were tuned through Feb 2026; exchange freeze limits are ignored; there is
  no order-book data.
