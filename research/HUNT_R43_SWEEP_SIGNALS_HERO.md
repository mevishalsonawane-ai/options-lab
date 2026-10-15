# R43: Boss's 7 sweep signals as zero-to-hero expiry-day trades

Written 14 Oct 2026, for Boss ("for zero to hero").
- **What Boss's sweep did.** Boss's own 5-year sweep read 7 signal families on the 5-minute index close. It bought the
  ATM option at the next bar's open, with a 25% stop, a 50% target and a 60-minute time exit. It skipped expiry days.
- **What it found.** 0 of 14 were positive, in-sample (98,761 trades) and out-of-sample (181,313).
- **What R43 asks.** Do the same signals work as hero trades? That means the index's own expiry day, a cheap OTM
  option, and holding for a big multiple.

- **Pre-registration:** `research/hunt/r43/PREREG.md`, commit `f4fc6f9`. It was pushed before any R43 trade or P&L
  existed; only expiry-day counts had been read.
  - The design results (`design.json`, commit `bbb3752`) were pushed before the holdout was read.
  - The holdout was read once (`study.py holdout` writes a flag and refuses a second read).
- **Code, tests and tables:** `research/hunt/r43/`. The tables are `results/report.md` (all 88 tests),
  `results/final.json`, `design.json` and `holdout.json`. There are 26 unit tests in `test_r43.py`.
- **Experiment entries:** `research/experiments/entries/r43-*.yaml`: one per signal family plus the baselines, 8 in
  all, every one rejected.
- **Research only:** nothing in the app changes.

## Verdict for Boss

- **No. None of the 7 signals catches zero-to-hero trades better than random after costs. 0 of 88 tests pass.**
  - The 88 tests are 7 families × strike rule × window × exit, plus 12 no-signal baselines.
  - The smallest holdout BH q is 0.132, and it belongs to a no-signal baseline. Among the signal rules it is 0.201,
    on a rule that lost money.
- **The signals do no better than a random minute.**
  - Each rule was compared with its own 500 random-entry twins: same index-day, side, window, strike rule and exit.
  - Of the 76 signal variants, 39 beat their twins' average in design and 38 in the holdout. That is a coin flip.
- **Every profit is a lottery ticket.**
  - 22 of 88 tests made money in design and 18 in the holdout. Only 3 made money in both.
  - **In every one of those 40 profitable cases, removing the 5 best trades turns the profit into a loss.**
  - A single 29× SENSEX call on 2 Apr 2026 (+Rs 1,40,013) is the best trade of several holdout rules.
- **Signal on the underlying, as the default rule says.** Every signal is read on the 5-minute INDEX spot bars. The
  option is only what is bought. No rule here reads an option's own chart.
- **What to do: no app change, nothing on paper, nothing live.** Nothing passed every gate. R43 confirms R21-R39:
  on expiry day, cheap options are a lottery, and a standard index indicator does not tell you which ticket wins.

## The headline rules

Holdout is 1 Jan 2025 - 12 Oct 2026 (245 index-expiry-days). Design is 5 Aug 2021 - 31 Dec 2024 (670). Rs 5,000 a
trade, real charges and slippage.

| rule (signal on the index) | period | trades | win % | peaked 5× / 10× | net Rs | net a day | worst day | max DD | top-5 share | net w/o top 5 | twin mean | p | q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| EMA8×21 ADX>15, cheap Rs 5-30, 12:30-14:45, hold | design | 324 | 10.8 | 30 / 9 | +1,23,022 | +184 | −6,885 | −1,83,635 | 313% | −2,62,494 | +10,144 | 0.172 | 0.540 |
| same | holdout | 119 | 10.1 | 13 / 3 | +87,534 | +357 | −9,069 | −74,730 | 322% | −1,94,680 | +62,634 | 0.341 | 0.781 |
| 1b: the same with the stepped lock | design | 324 | 29.0 | 14 / 3 | +67,722 | +101 | −5,877 | −88,461 | 368% | −1,81,389 | +74,778 | 0.535 | 0.865 |
| same | holdout | 119 | 31.1 | 4 / 0 | −19,993 | −82 | −7,358 | −43,947 | - | −85,095 | −9,924 | 0.569 | 0.781 |
| EMA9×21 ADX>20, cheap, 12:30-14:45, hold | design | 178 | 9.6 | 17 / 5 | −29,677 | −44 | −5,998 | −1,88,772 | - | −2,99,144 | −3,410 | 0.609 | 0.865 |
| same (best holdout net of all 88) | holdout | 75 | 12.0 | 9 / 3 | +1,37,909 | +563 | −5,818 | −50,257 | 198% | −1,35,663 | +65,001 | 0.186 | 0.710 |
| Donchian 20, OTM2, all day, hold (best design net of all 88) | design | 662 | 13.3 | 62 / 18 | +5,83,105 | +870 | −7,962 | −96,418 | 104% | −21,239 | −5,438 | 0.034 | 0.351 |
| same | holdout | 245 | 14.3 | 16 / 3 | −67,696 | −276 | −9,877 | −1,31,331 | - | −2,18,357 | −21,855 | 0.659 | 0.814 |
| RSI fade (<30 CE / >70 PE), cheap, all day, hold | design | 509 | 9.6 | 48 / 16 | +52,688 | +79 | −5,901 | −3,33,133 | 818% | −3,78,379 | −3,07,467 | 0.024 | 0.302 |
| same | holdout | 186 | 4.8 | 15 / 2 | −2,15,365 | −879 | −10,107 | −2,15,365 | - | −4,03,792 | −1,43,715 | 0.741 | 0.846 |
| Baseline: CE + PE at 09:45 every expiry, cheap, hold | design | 1,276 | 10.7 | 129 / 44 | +46,309 | +69 | −15,844 | −3,50,365 | 996% | −4,14,770 | −4,78,167 | 0.020 | 0.302 |
| same | holdout | 460 | 5.4 | 25 / 6 | −4,20,864 | −1,718 | −17,880 | −4,91,946 | - | −7,72,968 | −2,00,795 | 0.934 | 0.934 |
| Baseline: CE + PE at 12:30, cheap, hold | design | 1,268 | 9.9 | 86 / 28 | −7,43,397 | −1,110 | −15,841 | −8,38,238 | - | −11,06,782 | −8,94,635 | 0.214 | 0.586 |
| same | holdout | 467 | 11.3 | 38 / 11 | +33,777 | +138 | −20,379 | −1,99,053 | 1,152% | −3,55,255 | −2,90,174 | 0.012 | 0.176 |

- **"Peaked 5× / 10×"** counts trades whose option traded at 5× or 10× the fill while held. Many of them gave it back
  before the exit.
- **"Net a day"** divides by every index-expiry-day in the period, including days with no trade.
- **p** is the share of the 500 random twin sets that did at least as well. **q** is BH over all 88 tests.
- **A small p with a loss** means random entries lost even more. That is not an edge.

### Every family in one line (12 variants each; 1b has 4)

| family | variants profitable in design | in holdout | average variant net, design | average, holdout | smallest holdout q |
|---|---|---|---|---|---|
| 1 EMA8×21, ADX>15 | 4 of 12 | 3 of 12 | −41,330 | −27,593 | 0.556 |
| 1b same + stepped lock | 1 of 4 | 0 of 4 | −26,934 | −34,097 | 0.781 |
| 2 EMA9×21, ADX>20 | 3 of 12 | 5 of 12 | −89,771 | +8,223 | 0.414 |
| 3 EMA9×26, ADX>20 | 0 of 12 | 4 of 12 | −1,18,927 | −22,986 | 0.781 |
| 4 RSI fade | 3 of 12 | 0 of 12 | −2,13,775 | −1,11,719 | 0.201 |
| 5 RSI momentum | 2 of 12 | 4 of 12 | −1,64,232 | −44,334 | 0.414 |
| 6 Donchian 20 | 4 of 12 | 1 of 12 | +9,854 | −61,851 | 0.781 |
| baselines (no signal) | 5 of 12 | 1 of 12 | −2,12,212 | −1,94,312 | 0.132 |

Sample sizes: signal rules made 62-482 holdout trades each and 178-1,293 design trades. Variants of one family share most
of their entries, so these counts are not independent.

## What the numbers say

- **The EMA crosses in the afternoon window** are the only family that made money in both periods: EMA8×21 ADX>15,
  cheap, held to 15:05.
  - Its design profit comes from 2022 alone: 2021 −Rs 54,359, 2022 +Rs 2,17,180, 2023 −Rs 11,051, 2024 −Rs 28,749.
  - Its holdout profit comes from a handful of tickets:
    - 2 Apr 2026, SENSEX CE at Rs 24.91 → 29×, +Rs 1,40,013;
    - 13 Feb 2025, NIFTY PE → 11.6×, +Rs 50,887;
    - 31 Jul 2025, NIFTY PE → 9.2×, +Rs 40,266.
  - Random entries on the same days, in the same window, made +Rs 62,634 on average. p 0.34.
- **The stepped profit lock (1b) kills the heroes.** It wins more often: 29-31% against about 10% held to 15:05. But it
  sells long before 5×. In the holdout this variant sold nothing at 10× and lost Rs 19,993 on 119 trades. All four 1b variants
    together banked one 10× in 667 holdout trades, and all four lost (−Rs 19,993 to −Rs 47,088).
  - That is the zero-to-hero trade-off seen since R25. Any early lock cuts the rare 10-30× ticket that pays for all the
    losers.
- **Donchian was the design's star and failed the holdout.**
  - Design: +Rs 5.83 lakh on 662 trades (p 0.034).
  - Holdout: −Rs 67,696 on 245 trades (p 0.66).
  - Even in design it was −Rs 21,239 without its 5 best trades. One was 30 Aug 2022: a FINNIFTY 18000 CE bought at Rs 5,
    sold at 44×.
- **The baselines show the period, not a signal.**
  - Buying both sides at 09:45 every expiry was +Rs 46,309 in design and −Rs 4.21 lakh in the holdout.
  - At 12:30 it was −Rs 7.43 lakh in design and +Rs 33,777 in the holdout.
  - Whether cheap expiry options pay in a given year depends on how many jackpot days that year had.
- **Costs are not the problem.**
  - 2× the spread changes nets by a few percent, because cheap OTM tickets at Rs 5-30 are 1-4 ticks wide.
  - Pricing out-of-range strikes at 0 moves a handful of trades (4-23 per rule).
  - The losses come from the 85-95% of tickets that die.

## Method (PREREG.md has every detail)

- **Data:** the Dhan lake on Drive, `opt_<UND>_WEEK` (ATM±10, 1-minute) and `index_1m`, for NIFTY, BANKNIFTY,
  FINNIFTY, MIDCPNIFTY and SENSEX. The lake ends 12 Oct 2026.
- **Expiry days come from the data** (R25's rule). That gives 915 index-expiry-days:

  | index | design | holdout |
  |---|---|---|
  | NIFTY | 178 | 93 |
  | BANKNIFTY | 174 | 21 |
  | FINNIFTY | 154 | 19 |
  | MIDCPNIFTY | 80 | 21 |
  | SENSEX | 84 | 91 |

  - Since Nov 2024 BANKNIFTY, FINNIFTY and MIDCPNIFTY have only monthlies. Only the WEEK file is read, so an expiry is
    never counted twice. Their monthly expiry days are included.
- **Signals (all on the index spot):**
  - 5-minute bars are built from the 1-minute index data as one continuous series over every session, so indicators
    warm up on earlier days.
  - Indicators follow TradingView Pine: Wilder's RSI(14) and ADX(14), and EMAs seeded with the first n-bar average.
  - crossover(a, b) = a > b and a[1] <= b[1].
  - The signal is read at the bar's close. The entry is the app's limit order over the NEXT bar's first 3 minutes,
    capped at 1.2× the signal-time price + Rs 0.10, never chased.
  - At most 1 trade per index-day: the first signal in the window that gets a fill.
- **Strikes:**
  - `cheap`: the nearest OTM strike priced Rs 5-30, with the app's stale-price check;
  - `otm2`: the second OTM strike.
  - Both are in the signal's direction.
- **Windows:** signal bar closes from 09:20 to 14:45 (`all`), or from 12:30 to 14:45 (`hero`).
- **Exits:** every exit has a −50% stop on a minute close, and none has a fixed target.
  - `hold`: hold to the 15:05 square-off;
  - `trail`: once the best close reaches 3×, sell at 50% of the best close;
  - `time`: the trail, plus sell if no 2× close comes within 60 minutes;
  - `step` (1b): 1.25× locks 1.0×, 1.5× locks 1.25×, 1.75× locks 1.5×, 2× locks 1.75×, then m× locks (m − 1)×.
- **Size and costs:**
  - Rs 5,000 a trade (the app's Hero arm), in whole lots of that day's lot size.
  - The app's real charges and R25's fills: the open ± 5 bps ± a 1/2/4-tick half-spread set by recent volume.
  - Every rule is also re-run at 2× the spread.
- **Tests:** 26 unit tests in `test_r43.py`:
  - indicator values against hand-computed Wilder RSI and ADX and seeded EMA/RMA on tiny series;
  - crossover edge cases (equal before, touching, NaN);
  - next-bar-open entry, no fill above the cap;
  - the stop, the trail, the time stop and the step lock;
  - the expiry-day rule and the design/holdout split;
  - empty days and gaps in 5-minute bars;
  - the brain-style look-ahead guard (future cut off, and future poisoned: identical signals). The same check was also
    run on real NIFTY bars.
- **Honest caveats:**
  - **No fitting.** Every value was fixed in advance, so design per-year results stand in for walk-forward.
  - **The holdout period is not fresh.** R25, R34, R35 and R39 had read 2025-26 expiry days for other rules. They were
    new for these signals.
  - **Some MIDCPNIFTY dates look odd.** A few expiry dates found by the data rule fall on unusual weekdays (for example
    MIDCPNIFTY 7 and 8 Jun 2023). They are kept as the rule found them.

## Across the hero series

- R21-R39 tested 82 hero rules. R43 adds 88 more. **None of the 170 passes.**
- The only clue the series has found is still R34's: cheap options priced far above how much the index is actually
  moving lose the most. That is a "when not to buy" rule, logged in shadow only.
