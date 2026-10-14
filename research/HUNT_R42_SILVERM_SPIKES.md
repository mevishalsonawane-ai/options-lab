# R42: Is the SILVERM morning-call edge (M4) made of bad prices?

Written 14 Oct 2026 for Boss. Pre-registered in `hunt/r42/PREREG.md` (commit b91dc30f, before any P&L). Amendment 1
(commit 4143d7c8) came before any real data was filtered. Code: `hunt/r42/` (`spikes.py`, `census.py`,
`m4_rerun.py`, `analyze_r42.py`, tests in `test_r42.py`). Small result tables: `hunt/r42/results/`.

## Short answer

- **Bad prices did not make M4's profit.** None of M4's 51 original holdout trades had a flagged entry or exit price.
  Cleaning changed no trade's price at all: 0 of 95 design trades and 0 of 53 holdout trades. Every M4 number is the
  same before and after cleaning.
- **The scary sweep number comes from how the series is stored, not from bad prices.** In the metals, 98-99% of the
  ">50% one-minute jumps" happen when the rolling ATM+k series switches to another strike. The +4,000x jumps happen on
  expiry day, when an expiring Rs 0.5 contract hands over to a Rs 4,000-6,000 next-month contract. Real one-off bad
  prints in a single contract are rare: 0.02-0.08% of traded minutes in GOLD/GOLDM/SILVER/SILVERM. In the SILVERM
  morning (09:00-10:44) there are 5 of 120,739 traded minutes.
- **But M4 still fails its decision rule.** On the cleaned data, the pre-registered holdout (8 Jul - 8 Oct) gives
  53 trades, +Rs 336/trade, +Rs 17.8k. The random-twin p is **0.079**, which is not below 0.05. Under the rule fixed in
  advance, the verdict is **"switch the M4 paper arm off"**. This is a risk-lowering paper change. Boss is told first;
  R42 does not change the app.
- Two more things weigh against it:
  - The fuller lake data shrinks M4's design edge from +Rs 1,439 to +Rs 871 per trade (78 to 95 trades).
  - M4's walk-forward goes from -Rs 3.5k to -Rs 44.6k (129 trades).

## Signal source

M4 enters on the clock: a fixed 15-minute grid from 09:15 to 13:45 IST, with no indicator. The strike is chosen from
the SILVERM futures price (ATM+2 or ATM+3 call). The option premium is used only for the trade's entry/exit price and
P&L. No signal is read from the option's own chart.

## Data

- Source: the Google Drive lake (`gdrive:IraAlgo-data-lake/options_rolling_1m`, Dhan v2 `rollingoption`, near
  monthly expiry, 1-minute bars), 5 Aug 2025 - 12 Oct 2026. Strikes ATM-3..+3, calls and puts.
- All 16 files match the Drive manifest's sha256 (`results/lake_files_sha256.txt`). The lake ends on 12 Oct, so
  13 Oct is not in it.
- M4's original inputs (M3's download) were not on this machine, so they were rebuilt from the lake in the same
  format. `spot` is the per-minute median of the option rows' `spot`.
- M4's own `build.py` / `analyze.py` were run unchanged. Only file paths were patched, plus an opt-in minute grid for
  the twins (`m4_rerun.py`).
- **Reproduction:** on M4's own span (holdout to 7 Oct) the rebuild gives 51 trades, +Rs 466/trade, +Rs 23.7k,
  p 0.050. M4 had 51 trades, +Rs 454, +Rs 23.2k, p 0.050.
  - 45 of the 51 trades are identical (same minute, strike and entry price).
  - In 6 trades the other strike was picked. When both ATM+2 and ATM+3 qualify at the same minute, M4's code picks one
    arbitrarily.
  - 3 exit prices differ slightly.
  - NATURALGAS and CRUDEOIL walk-forward picks match M4's to the rupee.
- **What differs:** M4's input for 8 Oct was missing (2 extra trades here). M3's SILVERM and GOLDM downloads also had
  holes. The lake gives 57,432 SILVERM entries against M4's 47,286 (+21%), and 68,190 GOLDM against 61,892 (+10%),
  mostly Dec 2025 - May 2026. So the design and walk-forward numbers below are on more complete data than M4 had.

## What counts as a bad price (pre-registered)

- A **contract** is (day, side, strike). The same strike under a different ATM offset is the same contract.
- **F1 (main):** a minute is flagged if its close is more than 1.5x (or less than 1/1.5 of) BOTH of these:
  - the median close of up to 5 priced minutes before it, within 15 minutes, same contract and day;
  - the median close of up to 5 priced minutes after it, under the same conditions.

  So a print that jumps and comes back within about 5 minutes is flagged. A genuine jump that stays is kept.
- **F2 (strict):** F1, plus the same test on the minute's high and low.
- **S0 (naive):** a >50% close-to-close change on consecutive rows of the rolling series. This is roughly what the
  outside sweep did.
- A flagged traded minute becomes "no price". M4 already treats a minute with no trade as "no price": it carries the
  last price forward for up to 5 minutes, then uses Black-76 at the last implied vol.
- Tests (`test_r42.py`, 13 cases) cover these cases: a single-minute spike up and down; a 3-minute burst; a genuine
  sustained 3x jump that must NOT be flagged; empty data; gaps inside and beyond the 15-minute window; the first and
  last minutes; separate contracts and days; duplicate rows; and a strike roll that S0 flags but F1 does not.

## Census: share of minutes flagged (5 Aug 2025 - 12 Oct 2026, ATM-3..+3)

| book | rows | traded share | S0 naive (all rows) | of which strike rolls | F1 all rows | F1 traded | F2 traded | F1 traded, n |
|---|---|---|---|---|---|---|---|---|
| SILVERM | 2,485,707 | 45% | 2.01% | 98.2% | 0.018% | 0.034% | 0.074% | 379 |
| SILVER | 1,568,047 | 22% | 3.10% | 98.8% | 0.018% | 0.075% | 0.197% | 261 |
| GOLDM | 2,947,841 | 52% | 1.86% | 98.3% | 0.015% | 0.022% | 0.055% | 333 |
| GOLD | 1,675,697 | 20% | 2.09% | 98.7% | 0.011% | 0.045% | 0.137% | 149 |
| CRUDEOIL | 3,602,635 | 91% | 0.09% | 85.1% | 0.011% | 0.011% | 0.029% | 370 |
| NATURALGAS | 3,604,457 | 87% | 0.22% | 79.3% | 0.048% | 0.052% | 0.122% | 1,620 |
| NIFTY weekly | 1,534,372 | 100% | 0.87% | 86.0% | 0.095% | 0.095% | 0.283% | 1,454 |
| BANKNIFTY monthly | 1,534,313 | 99% | 0.13% | 77.9% | 0.020% | 0.020% | 0.068% | 307 |

There are 293-304 trading days per book.

- My naive sweep finds 1.9-3.1% of metal-option rows. The outside sweep's 4-7% probably used a different denominator
  or more strikes. Either way, almost all of those rows are strike rolls.
- The largest naive jumps are expiry-day handovers, not bad ticks. Examples: SILVERM 28 Oct 2025 13:34, Rs 0.5 to
  Rs 5,933 (+1,186,500%); SILVER 28 Oct 2025 22:23, Rs 1.0 to Rs 4,204.5 (+420,350%).
- In the metals, real bad prints sit in the **evening** (17:00-23:30), not the morning. F1, traded minutes:

| book | 09:00-10:44 | 10:45-13:59 | 14:00-16:59 | 17:00-23:30 |
|---|---|---|---|---|
| SILVERM | 0.004% (5 of 120,739) | 0.005% | 0.002% | 0.062% (360) |
| SILVER | 0 of 35,694 | 0 | 0.002% | 0.136% (260) |
| GOLDM | 0.005% (8 of 168,310) | 0.002% | 0.002% | 0.041% (315) |
| GOLD | 0.017% (6 of 35,518) | 0.010% | 0 | 0.078% (137) |
| CRUDEOIL | 0.001% (2) | 0 | 0 | 0.023% (368) |
| NATURALGAS | 0.016% (52) | 0.030% | 0.037% | 0.073% (1,161) |
| NIFTY / BANKNIFTY | 0.002% / 0.000% (09:15-10:44) | 0.124% / 0.026% (10:45-15:30) | | |

The SILVERM morning, where M4 trades, is one of the cleanest windows in the whole lake.

## M4's original trades

- Of M4's 51 holdout trades, **0** had an F1- or F2-flagged entry minute. **0** had a flagged exit minute, or a
  flagged minute in the 5 minutes before the exit that the forward-fill could have carried.
- The top 3 trades (145% of M4's net) are unchanged by cleaning:

| entry | strike | entry Rs (raw = clean) | exit Rs (raw = clean) | net Rs (1 lot of 5) | lots traded at entry minute |
|---|---|---|---|---|---|
| 7 Aug 2026 09:30 | 233000 CE | 5,108.0 | 7,700.0 | +12,537 | 3 |
| 28 Aug 2026 09:45 | 245000 CE | 12,000.0 | 14,539.5 | +11,875 | 1 |
| 9 Jul 2026 09:15 | 227000 CE | 7,374.5 | 9,300.0 | +9,153 | 1 |

- The prices are real prints, but thin ones. The median entry minute traded 3 lots, and 24 of 51 entries traded 2 lots
  or fewer. The median exit minute traded 1 lot, and 24 of 51 exit minutes had no trade at all. Whether Boss could actually buy and sell at those prices (the
  spread at the open) is still unmeasured, as M4 said.

## M4's frozen rule re-run (1 lot of 5, M4's costs; "2x" = double spread)

| data | window | trades | hit | net/trade | net total | max DD | top-3 share | p (M4 style) | **p (twins)** |
|---|---|---|---|---|---|---|---|---|---|
| raw = F1 = F2 | design 5 Aug 2025 - 7 Jul 2026 | 95 | 57% | +871 | +82.8k | 22.4k | 51% | 0.084 | 0.332 |
| raw = F1 = F2 | **holdout 8 Jul - 8 Oct 2026** | **53** | 49% | **+336** | **+17.8k** | 14.3k | 188% | 0.066 | **0.079** |
| same, 2x spread | holdout | 53 | 45% | +62 | +3.3k | 21.1k | 988% | 0.066 | 0.070 |
| same | holdout to 7 Oct (M4's span; added for comparison, not in the decision) | 51 | 53% | +466 | +23.7k | 13.8k | 141% | 0.050 | 0.072 |
| same | **extra** 9-12 Oct (not in the decision) | 4 | 25% | -949 | -3.8k | 5.6k | | 0.621 | 0.268 |

- F1 and F2 give exactly the same numbers as raw. They removed 379 and 836 SILVERM minutes, none of them on this
  rule's path. The design p (M4 style) is 0.080 under F2 against 0.084 raw, because that baseline's pool (every entry of the same strike offset, any time of day) lost a few cleaned minutes.
- Holdout by month (net): Jul +4.2k (12 trades), Aug +27.9k (13), Sep -11.8k (18), Oct 1-8 -2.5k (10).
- By entry time (holdout): 09:15-10:44 made +Rs 33.3k (28 trades); 10:45-13:45 lost -Rs 15.4k (25 trades).
- **Twin test:** each trade is compared with a twin bought on the same day, at a random minute between 09:15 and 13:59,
  in the same option cell, with the same 4-hour exit and the same costs (2,000 draws, about 121 possible minutes a day).
  - Holdout p = 0.079.
  - Design p = 0.33. In design, the rule's grid times did no better than random morning minutes on the same days. The
    profit is "a far-OTM call held 4 hours in silver's rising mornings", not the timing.
- **Walk-forward** (M4's procedure, all 4 books, best cell by month): -Rs 44.6k net on 129 trades (gross -Rs 13.1k),
  raw and cleaned alike. M4 had -Rs 3.5k. The difference is entirely the extra SILVERM and GOLDM data, which led to
  different SILVERM picks in Nov 2025, Jan 2026 and Jun 2026.

## Verdict (decision rule fixed before the data)

- Cleaned holdout net/trade +Rs 336 > 0: pass.
- Twin random p 0.079 >= 0.05: **fail**.

**Verdict: switch the M4 paper arm off.** This is a risk-lowering paper change; Boss decides and is told first. R42
changes nothing in the app.

The reason is not bad prices. On the full data the arm's profit cannot be told apart from buying the same option at
a random morning minute. Its design edge shrank by 40% once M3's data holes were filled. Its walk-forward loses
Rs 44.6k. The 4 trades since the holdout (9-12 Oct) lost Rs 3.8k; that is a tiny sample and is not used for the verdict.

## Caveats

- One rule was tested and nothing was selected (m = 1). The twin p is the pre-registered decision p. M4's own baseline
  (0.066) and the 2x-spread runs agree with it.
- The inputs were rebuilt from the lake, not M3's files. They reproduce M4 closely where the data overlap, and they are
  more complete.
- The tie between ATM+2 and ATM+3 at the same minute is broken arbitrarily by M4's code (6 of 51 holdout trades). It
  is left as M4 had it.
- Post-hoc note (not in the decision): in 17 of the 53 cleaned holdout trades no trade printed within 5 minutes of
  the exit. M4 then prices the exit with Black-76 at the last implied vol. Those 17 trades netted -Rs 40.3k; the 36
  with a real exit print netted +Rs 58.2k. Either way, about a third of the exits are model prices, not fills.
- Costs are M4's estimated spreads (no live SILVERM quotes at the open). The 2x-spread holdout is +Rs 62/trade.
