# RC1: the best way to put a small amount into BTC / ETH from India, after tax (13 Oct 2026)

Boss, 13 Oct 2026: "Find the best way to trade crypto, BTC and ETH, using a small amount." Research only. No app code,
no exchange accounts and no orders were involved. Accounts of Rs 25,000 and Rs 1,00,000 were tested.
This is research, not financial advice.

Pre-registered in `hunt/rc1/PREREG.md` (commit 3ceda7b, before any data was downloaded). The holdout, 13 Oct 2025 to
12 Oct 2026, was read once (commit 4a73d39), after the design results were committed (2062f15). Code, tests and generated
tables are in `hunt/rc1/` (`results/report.md` has every table, including per-coin and Rs 25,000 versions).

## Verdict for Boss (plain English)

- **Best way from India after tax: buy and hold, or spread the buys over a few months (DCA), in BTC and ETH, and sell
  rarely.**
  - Walk-forward, 13 Oct 2019 - 12 Oct 2025 (6 years, 73 months, 50/50 BTC+ETH, base fees, 31.2% tax, TDS lock):
    - buy and hold made **+58.8% a year after tax** (Rs 1,00,000 to Rs 16.0 lakh);
    - 12 monthly DCA buys made +56.4%, and a running Rs 1L/12 monthly SIP had an IRR of +50.7%.
  - In the last 12 months (the holdout), crypto fell. Holding lost **-30.4%**, while the 12-month DCA made **+4.7%**: spreading
    the buys protected the entry.
- **No active strategy is worth it.** 0 of 8 active ideas passed. Every one failed the random-entry test after BH.
  - Trend-following on spot kept less than half of holding's return after tax (+24.4% vs +58.8% a year). Its drawdown was no
    better (-73% vs -76%).
  - Vol-targeted trend cut the drawdown to -46%, but returned only +19.9% a year.
  - Funding carry lost money (-10.9% a year).
  - The 4-hour breakout lost -12.2% a year after tax, even though it made +48.0% a year before fees and tax.
- **Why: India's 30% tax with no loss set-off.**
  - Every winning sale is taxed and every losing sale is wasted.
  - Spot trend paid Rs 2.83 lakh tax on a Rs 1L account over 6 years, and ended with Rs 3.7 lakh. Holding paid Rs 6.84 lakh
    (once, at the end) and ended with Rs 16.0 lakh.
  - The 4h breakout made 133 orders a year and paid Rs 1.62 lakh tax while ending at Rs 45,746.
- **Avoid:**
  - **frequent trading** (anything with dozens of round trips a year);
  - **leverage**: at 3x on perps, trend went from +17.4% to +9.7% a year with a -95% drawdown, ETH alone at 3x was wiped out
    in the March 2020 crash, and 3x lost -70% in the holdout year;
  - **funding carry** (two exchanges, each leg taxed separately, liquidations);
  - **perps when the tax reading is unclear** (reading A, 30% per trade with no set-off, turns a +30% pre-tax 4h breakout into
    -3% a year).
- **Realistic expectations and worst cases (50/50, after tax):**
  - Even the best approach fell **-76% from peak to trough** (2021-22) and took **632 days** to recover.
  - Its worst month was **-42%** and its worst 12 months **-74%**. Only 60% of months were green (of 73).
  - A down year like the holdout (-30% holding) is normal.
  - The past 6 years' +59% a year came from two huge bull runs (+418% in the year to Oct 2021). Do not plan on it repeating.
  - Put in only money that can halve and stay down for two years.
- Tax figures are **modelling assumptions**. Boss should confirm them with a CA, above all the treatment of INR futures.
- Account size barely matters for spot, because fees are percentages.
  - On perps, whole contracts (BTC 0.001, ETH 0.01) make Rs 25,000 slightly worse: carry made -9.4% a year at 1x, against
    -10.9% at Rs 1L, but the absolute amounts are small either way.

## Data (n with every number)

- Binance public archive (data.binance.vision), streamed into a parquet cache outside git; nothing raw is committed.
  - Spot BTCUSDT / ETHUSDT 1d and 1h, 17 Aug 2017 - 12 Oct 2026 (3,344 days each; 80,125 hourly bars).
  - USD-M perps 1d / 1h, 1 Jan 2020 - 12 Oct 2026 (2,477 days).
  - Funding 1 Jan 2020 - 30 Sep 2026 (7,395 8-hourly prints per coin). The archive has no October 2026 funding yet, so the
    perp and carry holdout ends on 30 Sep 2026, as pre-registered.
- USD/INR: the ECB rate (frankfurter.dev, 2,503 days), carried forward.
  - All money is in rupees: USDT price x USD/INR.
  - The INR premium on Indian exchanges is ignored. It moves buy and sell prices together.
- Binance perps stand in for Delta Exchange India (1-hour correlation 0.987 BTC / 0.994 ETH, `NN_CRYPTO.md`).
- The sibling crypto lake (claude/crypto-lake) was not published, so it was not used.

## Assumptions (labelled; sensitivity below)

**Indian spot exchange (CoinDCX / CoinSwitch / Mudrex / ZebPay), per side.** These are assumptions, not quotes:

| case | taker fee | GST | half-spread | total per side |
|---|---|---|---|---|
| low | 0.10% | 18% | 0.05% | 0.168% |
| base | 0.25% | 18% | 0.15% | 0.445% |
| high | 0.50% | 18% | 0.30% | 0.890% |

**INR perps (Delta India / CoinDCX futures), per side:**

| case | fee | half-spread | total per side |
|---|---|---|---|
| low (maker) | 0.02% + GST | 0.01% | 0.034% |
| base | 0.05% + GST | 0.02% | 0.079% |
| high | 0.05% + GST | 0.05% | 0.109% |

- Perp funding: Binance's real prints. Positive funding means longs pay.
- Perp margin: isolated. Liquidation is at the maintenance margin (0.5%) and loses the position's margin. There are no top-ups.
- Leverage tested: 1x and 3x.

**Tax (modelling assumptions; confirm with a CA):**

- Spot (VDA, sec 115BBH):
  - 31.2% on each profitable sale. The gain is the sale value minus the FIFO cost; fees are not deductible.
  - Losses are ignored: no set-off and no carry-forward.
  - Tax is paid at the 31 March close.
- TDS: 1% of each sale is held back and returned 365 days later.
- Perps, two readings, shown side by side because the treatment is **disputed**:
  - **A (VDA):** 31.2% per profitable closed trade, losses ignored.
  - **B (business income):** 31.2% on each year's net, with an 8-year loss carry-forward. A lower slab only helps.
  - Neither reading sets perp losses against spot gains.
  - No TDS is charged on INR perps.
- Every window ends by selling everything, so hold-type results include their tax.

## Results, walk-forward 13 Oct 2019 - 12 Oct 2025 (Rs 1,00,000, 50/50, base costs)

Perp and carry rows run from 1 Jan 2020 (71 months).

| idea | gross CAGR | pre-tax | **after tax+TDS** | final | max DD | worst month | green months | orders/yr | recover from worst DD | tax paid |
|---|---|---|---|---|---|---|---|---|---|---|
| a1 buy and hold | +68.7% | +68.4% | **+58.8%** | Rs 16.0 L | -76% | -42% | 60% of 73 | 0.7 | 632 d | Rs 6.84 L |
| a2 DCA 12 monthly | +66.2% | +65.9% | **+56.4%** | Rs 14.7 L | -76% | -42% | 60% | 4.3 | 632 d | Rs 6.22 L |
| a2 DCA 52 weekly | +65.6% | +65.3% | **+55.9%** | Rs 14.4 L | -76% | -42% | 60% | 17.7 | 632 d | Rs 6.09 L |
| a2 SIP Rs 1L/12 a month (IRR) | +62.2% | +61.8% | **+50.7%** | Rs 27.0 L | -76% | -42% | 60% | 24.7 | 632 d | Rs 9.54 L |
| b rebalanced monthly | +73.7% | +72.9% | **+57.5%** | Rs 15.3 L | -78% | -41% | 59% | 31.7 | 743 d | Rs 6.73 L |
| b rebalanced at 40/60 | +72.1% | +71.7% | **+58.9%** | Rs 16.1 L | -76% | -39% | 62% | 8.3 | 488 d | Rs 6.91 L |
| c spot trend | +52.2% | +43.8% | **+24.4%** | Rs 3.70 L | -73% | -42% | 49% | 29.7 | not yet (1,033 d) | Rs 2.83 L |
| d vol-targeted trend | +41.8% | +35.3% | **+19.9%** | Rs 2.97 L | -46% | -19% | 49% | 35.0 | 133 d | Rs 1.83 L |
| g 4h breakout, spot | +48.0% | +10.6% | **-12.2%** | Rs 45,746 | -82% | -13% | 32% | 133 | not yet | Rs 1.62 L |
| c-perp trend 1x, tax A / B | +35.3% | +33.9% | **+17.4% / +25.5%** | Rs 2.53 / 3.72 L | -76% / -74% | -46% | 46% of 71 | 25.2 | not yet | Rs 1.76 / 1.23 L |
| c-perp trend 3x, tax A / B | +43.5% | +39.6% | **+9.7% / +32.4%** | Rs 1.71 / 5.07 L | -95% / -93% | -68% | 46% | 25.1 | not yet | Rs 2.83 / 1.84 L |
| f funding carry 1x, tax A / B | +4.2% | +2.5% | **-10.9% / -9.1%** | Rs 51,298 / 57,627 | -51% / -45% | -17% | 38% | 23.9 | not yet | Rs 62,245 / 54,468 |
| g-perp 4h breakout 1x, tax A / B | +36.9% | +30.0% | **-3.0% / +21.3%** | Rs 83,978 / 3.06 L | -74% / -47% | -13% | 37% | 133 | not yet | Rs 2.24 / 0.93 L |
| g-perp 4h breakout 3x, tax A / B | +68.0% | +44.0% | **-29.9% / +35.0%** | Rs 12,768 / 5.67 L | -99% / -89% | -49% | 37% | 133 | not yet | Rs 6.62 / 2.86 L |

- Walk-forward rule picks for c, d and c-perp, each chosen on earlier data only:
  - 100-day SMA for OOS years 2019-20 to 2022-23;
  - 200-day SMA for 2023-24 and 2024-25;
  - 50-day Donchian for the holdout.
- Per coin (`results/report.md`):
  - BTC hold made +51.5% after tax, ETH +64.7%. BTC trend made +28.1%, ETH trend +18.8%.
  - ETH-only trend on perps at 3x was liquidated in March 2020 and never recovered (Rs 1L to Rs 20).
- **TDS drag:** at most 0.07 points a year for the sensible ideas.
  - For hold and DCA it is zero, because there is no sale until the end.
  - For the 4h breakout it shows as +5.7 points. Locked cash kept money OUT of a losing strategy; that is not a benefit
    of TDS.
- **Tax, reading A vs B:** on perps the disputed reading swings the 4h breakout from -3.0% to +21.3% a year.
  - That swing is the whole difference between losing and winning.
  - It is the clearest reason to get a CA's view before trading INR futures.

### Gates (primary: 50/50, Rs 1L, base, after tax; BH across the 8 active tests)

| idea | entries | twin p | BH q | gate 1 edge | gate 2 worth it vs hold | gate 3 holdout vs twins | gate 4 high costs | verdict |
|---|---|---|---|---|---|---|---|---|
| b monthly rebalance | 72 rebalances | 0.534 | 0.713 | fail | fail | fail | fail | rejected |
| b 40/60 band | 7 rebalances | 0.836 | 0.836 | fail | pass | fail (0 rebalances) | pass | rejected (equal to hold) |
| c spot trend | 77 | 0.261 | 0.521 | fail | fail | pass | fail | rejected |
| c-perp (1x, A) | 73 | 0.452 | 0.713 | fail | fail | pass | fail | rejected |
| d vol-targeted trend | 76 | 0.075 | 0.356 | fail | fail | pass | fail | rejected |
| f funding carry (1x, A) | 36 | 0.714 | 0.816 | fail | fail | pass | fail | rejected |
| g 4h breakout | 397 | 0.089 | 0.356 | fail | fail | fail | fail | rejected |
| g-perp (1x, A) | 385 | 0.237 | 0.521 | fail | fail | fail | fail | rejected |

- Idea e (day of week) failed at design and was not built.
  - Tests: 16 (2 coins x 7 weekdays + weekend), 425-852 days each.
  - Smallest p: Thursday was weaker, BTC -28.5 bp (p 0.044) and ETH -42.2 bp (p 0.037). After BH the lowest q is 0.355.
- The rebalancing ideas are the same as holding: +58.9% (band) vs +58.8% (hold).
  - Monthly rebalancing paid more in fees and tax and ended 0.6 points worse.

### Cost sensitivity (after tax+TDS CAGR, walk-forward, Rs 1L, 50/50)

| idea | low | base | high |
|---|---|---|---|
| buy and hold | +58.9% | +58.8% | +58.5% |
| DCA 12 monthly | +56.6% | +56.4% | +56.1% |
| rebalance 40/60 | +59.2% | +58.9% | +58.4% |
| spot trend | +28.7% | +24.4% | +17.8% |
| vol-targeted trend | +23.1% | +19.9% | +14.8% |
| 4h breakout, spot | +1.4% | -12.2% | -29.8% |
| funding carry 1x A | -10.1% | -10.9% | -12.1% |

The fee level hardly matters for holding. It decides everything for frequent trading.

### Each OOS year on its own (Rs 1L, 50/50, after tax, flat start)

| year to 12 Oct | hold | spot trend | vol-targeted | 4h breakout | carry 1x |
|---|---|---|---|---|---|
| 2020 | +55% | -8% | +5% | +7% | -15% (from Jan) |
| 2021 | +418% | +266% | +132% | +19% | -15% |
| 2022 | -61% | -34% | -23% | -50% | -11% |
| 2023 | +20% | -14% | -16% | -11% | -5% |
| 2024 | +68% | +46% | +47% | -19% | -14% |
| 2025 | +57% | +26% | +24% | -15% | -6% |

Trend-following helped in exactly one year (the 2022 crash: -34% vs -61%). It cost more than that in every up year.

## Funding carry (idea f): computed, and impractical in India

- Funding is real income for shorts.
  - Binance mean, annualised: BTC 17% (2020), 31% (2021), 4% (2022), 8% (2023), 12% (2024), 5% (2025 to Oct).
  - ETH was similar. 88% of 6,336 prints were positive.
- The rule (enter above 10% a year, exit below 0) made only **+4.2% gross** and **-10.9% after tax**.
  - Without top-ups, the short perp was liquidated **6 times at 1x** when coins doubled during a carry spell (2020, 2021,
    2024): a 1x short dies at about +100%.
  - Each liquidation loses the short's margin while the spot leg's gain is taxed: 31.2% under both readings, never set off
    against the perp loss.
- A post-hoc (not pre-registered, not gated) variant closed and reopened both legs every month to avoid liquidations. It
  was worse still: -20.4% a year, from fees plus 31.2% on the spot leg's monthly gains.
- Practicality in India:
  - It needs two exchanges (an INR spot exchange plus Delta / CoinDCX futures), with the capital split 50/50 at 1x.
  - Bank transfers between them are slow, so a margin top-up comes too late.
  - TDS applies on the spot leg.
  - Each leg is taxed separately with no netting.
- Holdout: -1.1% (the funding was too low to cover costs).
- **Verdict: not for a small Indian account.**

## Holdout (13 Oct 2025 - 12 Oct 2026, read once; perps and carry to 30 Sep 2026)

Rs 1L, 50/50, after tax. Months: 13 for spot, 12 for perps.

| idea | after tax | max DD | orders/yr | note |
|---|---|---|---|---|
| buy and hold | -30.4% | -53% | 4 | BTC -23.8%, ETH -36.9% |
| DCA 12 monthly | **+4.7%** | -24% | 26 | bought into the fall, then the recovery |
| DCA 52 weekly | +5.1% | -21% | 106 | |
| SIP (IRR) | +7.5% | -54% (NAV) | 28 | |
| spot trend (Donchian 50) | -30.6% | -36% | 14 | 7 entries; twin median -37.7% |
| vol-targeted trend | -26.8% | -31% | 18 | |
| 4h breakout, spot | -45.7% | -47% | 152 | 76 entries; twin median -35.8% |
| trend on perps 1x / 3x (A) | -24.1% / -70.4% | -33% / -79% | 15 | 3x lost most of the account |
| funding carry 1x (A) | -1.1% | -1% | 12 | 3 entries |
| 4h breakout perps 1x / 3x (A) | -34.1% / -75.8% | -39% / -79% | 153 | |

- Rs 25,000 accounts gave the same percentages on spot.
  - On perps they were within about 1.5 points, from whole contracts: trend 1x -22.7%, carry -0.2%.
- **Holdout vs walk-forward:** the ranking held.
  - The patient spot approaches (hold, DCA) stay on top over the long run.
  - DCA protected the entry in a falling year.
  - Leverage and frequent trading were the worst in both periods.
  - Trend and vol-targeting reduced the holdout drawdown (-36% and -31% vs -53%), but ended no better than holding (-30.6%
    and -26.8% vs -30.4%).

## Honest limits

- There is one regime of crypto history: two big bull markets and two big bear markets. Six OOS years means only 6
  independent "years".
- The trend rules are textbook settings; nothing was tuned. The holdout year is a single falling year.
- The tax rules are modelled as stated above.
  - Real filing details (lot matching, whether exchange fees count in cost, how advance tax is due) may differ.
  - INR futures' tax is genuinely unsettled.
- Exchange risk, platform failures, withdrawal freezes and the INR premium are not modelled. Each of these makes active
  trading on two exchanges worse, not better.
- Funding before 2020 and Delta India's own history before 2024 are not available. Binance stands in for both.
- Deviations from the pre-registration, stated openly:
  - g-perp's breakout signals and stops are computed on the perp's own 4h bars, not on spot bars, so the stop is checked
    against the price actually traded. Perp and spot correlate at about 0.99.
  - The spot leg of the carry fills at perp prices (basis about -2 bp).
  - Idea g's twins use random entries with g's own exits (the 2-ATR stop and the 10-bar-low exit). The pre-registration
    asks for "the same exits", so this follows it more closely than shuffling spell lengths would.
  - The monthly-reset carry is post-hoc. It is descriptive only: not gated and not in BH.

## Files

- `hunt/rc1/PREREG.md`: the pre-registration.
- `hunt/rc1/config.py`: the numbers.
- `hunt/rc1/data.py`: the archive fetch and the holdout lock.
- `hunt/rc1/engine.py`: the Indian-tax account (FIFO, per-sale tax, TDS lock, FY payment, isolated perps, readings A/B).
- `hunt/rc1/strategies.py`: signals, twins and BH.
- `hunt/rc1/run.py`: the runner.
- `hunt/rc1/report.py`: the tables.
- `hunt/rc1/test_rc1.py`: 34 tests (no loss set-off, TDS lock, look-ahead guard, funding sign, liquidation, reading B
  carry-forward, holdout lock, twins).
- Experiment log: `experiments/entries/rc1-*.yaml` (10 entries).
- Process note: an independent code review before the holdout read found a funding/liquidation bug in the perp engine.
  It was fixed and re-run (commit 308f45a) BEFORE the holdout was opened.
