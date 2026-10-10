# LV-05 Donchian breakout as a multi-day option trade

*Data: real Dhan option minute bars, NIFTY Aug 2020 to Oct 2026 and BANKNIFTY Aug 2021 to Oct 2026. Code:
`research/obuy/strategies/lv05_multiday.py` and `research/obuy/lv05_run.py`.
Outputs: `<scratchpad>/obuy_cache/runs/lv05_multiday/` (`variants.csv`, `futures.csv`, `summary.json`, `wf_trades.csv`).*

## Verdict for Boss (plain language)

**No. Holding the Donchian breakout for several days does not work. It is not better than random, and it loses
money on rules picked from past years.**

On **Rs 5 lakh, 1 lot each of NIFTY and BANKNIFTY**, with the rule picked each year using only earlier years
(walk-forward, 2022 to Oct 2026):

| question | answer |
|---|---|
| Rs per year | **about -Rs 18,000 a year** (-Rs 85,300 over 4.8 years, 135 trades, 30% winners) |
| worst drawdown | **-Rs 1.86 lakh** (37% of the capital), marked to market daily |
| worst month | -Rs 28,800 (Jul 2025) |
| years positive | **2 of 5**: 2022 +4.6k, 2023 +31.4k, 2024 -35.5k, 2025 -32.9k, 2026 -53.0k |
| chance of a profitable year | **34%** at 1 lot (Monte Carlo). 51% at 1% risk per trade, but the median result is only about +Rs 250 |
| better than random? | **No.** Random entries held just as long made more per trade than the real ones (p = 0.68). |

What the test found:

1. **The holding period kills it.** The first-day leg made Rs +85.8k walk-forward in the earlier study (OBUY_GA).
   Held for days, the same signal makes -Rs 85k. The rule that keeps getting picked (N = 7, VIX filter, monthly
   ITM1) was strong in 2020 to 2023 and has lost money every year since 2024, in the full sample too.
2. **The best of the 100 variants is just the luckiest of 100.** It made +Rs 1.51 lakh over six years, and its raw
   p-value is 0.03. After correcting for 100 tries, it is nowhere near significant (BH q = 0.99, Holm = 1).
   Other checks agree: Hansen SPA p = 0.77 and White's Reality Check p = 0.80 (no variant beats not trading),
   Deflated Sharpe 0.05, PBO 47% (the best in-sample pick is a coin toss out of sample).
3. **The famous settings are the worst.** The classic 20- and 55-day channels lost money in all 24 combinations
   without the VIX filter: from -Rs 23k to -Rs 5 lakh over the sample. Weekly options (with rolls) did worse than
   monthly ones in 44 of 48 pairs.
4. **This is not option decay. The signal itself has no edge.** The same signal traded with index futures over
   the same holding periods also lost money. Over all 100 variants the futures mirror lost more than the options
   did (median -Rs 1.25 lakh against -Rs 58k). One futures lot moves about twice as much as one ATM option, and
   an option's loss is capped at its premium. The pure futures system (channel exits, no expiry rules) lost money
   in 9 of 12 settings. None of the 3 profitable ones beats random (best p = 0.08), and all 3 are N = 7 with the
   VIX filter, the same fading 2020-23 pattern.
5. **Overnight versus market hours.** The overnight gaps made money and the market hours lost it: walk-forward
   options +Rs 78k overnight and -Rs 152k intraday. That matches SWING_DEEP. Gap risk is not what hurts this system.
   Intraday reversals that hit the trailing channel are.

**Recommendation:** do not trade LV-05, in either its same-day or its multi-day form. Close the LV-05 lead from
OBUY_GA. It was the first-day leg of a bull-market-era pattern, and stretching the hold makes it worse, not better.

## What was tested

Catalog rules (LV-05, `option_buying_catalog.json`), implemented exactly where the data allows:

- **Signal**: `ga_levels.lv05_signals(mode="nextday")`, the same code as the GA study. When yesterday's daily close
  is above the highest high of the N sessions before it, buy a call at today's 09:16 open. When it is below the
  lowest low, buy a put. Optional filter: yesterday's India VIX below its 60-day median.
- **Stop and trail**: the opposite N/2-day channel, trailed daily. A long closes when the index trades below the
  lowest low of the previous N/2 sessions. The decision is on the 1-minute bar, filled at the next minute's open,
  as the engine handles an index stop. If the index gaps through at the open, the trade exits on the first minute.
- **Strike / expiry**: ATM or 1-ITM.
  - `M5` (the catalog's choice): the nearest monthly. Exit at 15:15 five sessions before expiry. Entries with 5
    or fewer sessions left are skipped, because the next monthly is not in Dhan's data, so "roll" is impossible
    and the catalog's "exit" applies.
  - `W1`: the nearest expiry with at least 2 sessions left, which is the weekly where Dhan has one, otherwise the
    monthly. At 15:15 one session before expiry, the position rolls into the nearest contract with at least 2
    sessions left (in practice the monthly). The next weekly is never in the data. When nothing is available
    (the weekly is the monthly, or BANKNIFTY after Nov 2024), the position stays flat over the expiry day and
    re-enters at 09:16 the next session, unless the channel stop was hit in between.
- **Premium stop**: none, -30% or -50% of each leg's fill. It is a resting order on the option's minute bars and
  fills at the open when the option gaps through it.
- **Costs**: obuy's app fills (±5 bps, stops -10 bps) and SandboxCosts charges on every leg, including rolls. The
  lot size is as of the entry or roll date. One position at a time per index.
- **Prices**: the held contract's real minute bars, on every day of the hold.
  - Dhan only stores ATM±10 strikes, so a contract that moves about 2-3% into or out of the money drops out of
    the data. While it is out, it is priced by Black-Scholes from the index minute and the IV of the edge strike on
    that side. SWING_DEEP used the same method.
  - This affected 17% of held leg-days (median over variants).
  - To check the method, I priced 6,000 real contracts near the edge of the window as if they were missing. The
    model came out 0.5% too high on the median, with a median absolute error of 3.7% and a 90th percentile of 11%.

**Declared grid (100 variants, fixed before any results; nothing pruned):** N {7, 10, 20, 55} x VIX filter
{off, on} x strike {ATM, ITM1} x expiry {M5, W1} x premium stop {none, -30%, -50%} = 96. On top of that, 4
one-at-a-time sensitivities around the GA's walk-forward pick (N = 7, VIX filter, ITM1, M5, no stop): an exit
channel of 7 days, an exit channel of 2 days, a 5-session maximum hold, and the channel checked on the daily close
(exit next 09:16).
- N = 5 from the catalog sweep was dropped to stay within 100 variants. Boss's 10/20/55 and the GA's 7 were kept.
- The exit channel and the maximum hold appear only as these sensitivities.

**Judging**: obuy's machinery, extended to multi-day positions:
- **Daily P&L**: marked to market at each close. Charges are booked on the day they are paid. The daily P&L sums
  to each trade's net to the paisa, and gross overnight + intraday equals gross exactly.
- **Walk-forward**: anchored and yearly. For each test year, it trades the variant with the best net over trades
  that had closed before 1 January of that year (so no open trade leaks into the choice), with a minimum of 20
  training trades.
- **Random baseline**: for each real position, 20 alternatives that also enter at 09:16, each on a random
  session of the same year. Each alternative takes a coin-flip side, uses the same strike, expiry and roll rule
  and the same premium stop, and is held exactly as many sessions as the real trade before closing at the same
  minute. A second baseline keeps the same day and hold and flips the side.
- **Overfitting checks**: BH and Holm across all 100 variants, White's Reality Check and Hansen SPA over the
  1,528-day x 100 daily matrix, a Deflated Sharpe with N = 100, PBO by CSCV (16 blocks, 4,000 splits), and a
  Monte Carlo on Rs 5 lakh.

**Same-day behaviour is unchanged**: `engine.py` was not modified.
- With a 1-session hold and the monthly contract, the multi-day walker reproduces the same-day engine's trades for
  the GA variants.
- It matches to the paisa on 3 of the 4 index x variant checks (181, 204 and 142 trades).
- On BANKNIFTY n = 20 ATM -30% stop, 149 of 151 trades match. The other 2 (4 Jun 2024, 17 Apr 2025) are days
  when the contract left Dhan's ATM±10 window during the day. There the engine waits for the contract's next real
  bar, and the walker uses the model price.
- All 74 NIFTY and 62 BANKNIFTY monthly expiry dates were confirmed against the monthly option chain.

## Results

### Walk-forward (out of sample, 1 lot per index, after costs)

| test year | rule picked (from earlier years) | trades | options net | same trades in futures |
|---|---|---|---|---|
| 2022 | N=20, all, ATM, M5, -30% stop | 32 | +4,609 | +80,753 |
| 2023 | N=7, VIX, ITM1, M5, max hold 5 | 34 | +31,437 | +30,833 |
| 2024 | N=7, VIX, ITM1, M5, -50% stop | 23 | -35,458 | -58,677 |
| 2025 | N=7, VIX, ITM1, M5, no stop | 26 | -32,904 | -116,553 |
| 2026 (to Oct) | N=7, VIX, ITM1, M5, -30% stop | 20 | -53,006 | -69,630 |
| **total** | | **135** | **-85,323** | **-133,274** |

- **Overall:** PF 0.83, 30% winners, held 4.3 sessions on average, Sharpe -0.26.
- **Random baseline:** the mean per trade is -Rs 632, against a random-entry mean of -Rs 234
  (95% band -1,734 to +1,359), so **p = 0.68**.
- **By index and side:** BANKNIFTY longs +19.0k, BANKNIFTY shorts -46.3k, NIFTY longs -14.9k, NIFTY shorts -43.1k.
- **Exits:** channel 56, premium stop 37, expiry 28, max hold 14.
- **Gates:** G1 (WF net > 0) fail, G2 (beats random) fail, G3 (more than half the years positive) fail, G4
  (drawdown ≤ 20% of capital) fail (-37%).

Monte Carlo on Rs 5 lakh (walk-forward trades, about 28 a year):

| sizing | P(profit in a year) | median year | 5th pct | 95th pct | P(drawdown ≥ 20%) | P(drawdown ≥ 50%) |
|---|---|---|---|---|---|---|
| 1 lot | 34% | -21,279 | -98,957 | +74,707 | 8% | 0% |
| 1% risk per trade | 51% | +247 | -21,402 | +43,167 | 0% | 0% |
| 2% risk per trade | 37% | -16,502 | -74,930 | +86,679 | 1% | 0% |

### Full sample, all 100 variants (in-sample, selection-biased)

- **Profit:** 30 of 100 variants made money. The median variant lost -Rs 58k over six years.
- **Random baseline:** no variant survives the correction for 100 tries. The smallest BH q is 0.99, and Holm is
  1.00 for all of them.
- **Overfitting checks:** Hansen SPA p = 0.77 and White's Reality Check p = 0.80. The Deflated Sharpe of the best
  variant is 0.05 (daily SR 0.023 against a benchmark of 0.065 for the best of 100 trials). PBO = 47%.

Per year (Rs, 1 lot per index), selected variants:

| variant | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 | total | p random | futures mirror |
|---|---|---|---|---|---|---|---|---|---|---|
| best: N=7, VIX, ITM1, M5, no stop | +57.8k | -30.7k | +110.9k | +66.8k | -21.7k | -32.9k | +0.9k | **+151.1k** | 0.032 (BH 0.99) | +166.5k |
| classic N=20, ITM1, M5 | +16.1k | -1.2k | +32.8k | +1.5k | -120.3k | -163.5k | +60.9k | -173.6k | 0.76 | -557.1k |
| N=10, ITM1, M5 | +17.1k | -64.7k | +43.3k | -6.0k | -124.1k | -148.2k | +118.3k | -164.2k | 0.66 | -535.4k |
| N=55, ITM1, M5 | +47.7k | +59.2k | -84.6k | -50.3k | -76.4k | -152.3k | -49.9k | -306.6k | 0.92 | -685.7k |

Headline numbers of the best and the typical variants. Drawdown and worst month are on daily mark-to-market P&L.
Overnight and intraday are gross Rs.

| variant | trades | net | PF | max DD | worst month | years + | avg sessions | overnight | intraday | p random |
|---|---|---|---|---|---|---|---|---|---|---|
| N=7, VIX, ITM1, M5, no stop | 135 | +151,103 | 1.31 | -157,476 | -28,781 | 4/7 | 5.4 | +94,421 | +68,775 | 0.032 |
| N=7, VIX, ATM, M5, -50% | 136 | +146,349 | 1.32 | -155,414 | -26,761 | 4/7 | 5.1 | +90,495 | +67,557 | 0.035 |
| N=7, all days, ATM, M5, -30% | 253 | +122,225 | 1.14 | -233,008 | -35,846 | 4/7 | 3.9 | +341,262 | -196,979 | 0.11 |
| N=20, all, ITM1, M5, no stop | 127 | -173,578 | 0.83 | -398,852 | -54,030 | 4/7 | 8.1 | +170,189 | -332,604 | 0.76 |
| N=20, all, ITM1, W1, no stop | 109 | -502,735 | 0.59 | -716,887 | -141,039 | 4/7 | 15.0 | +201,915 | -684,669 | 0.96 |
| N=55, all, ITM1, M5, no stop | 78 | -306,630 | 0.60 | -476,345 | -84,620 | 2/7 | 10.8 | +64,327 | -364,302 | 0.92 |

Sensitivities around the GA lead (N = 7, VIX, ITM1, M5, no stop: +151.1k):
- exit channel 2 days: +98.1k
- 5-session max hold: +128.1k
- exit channel 7 days: -6.6k
- daily-close channel exit (next 09:16): -145.1k

Tighter and faster exits are better here, which again points to the first day or two holding whatever there is.

### Signal edge versus option decay (index futures)

1 lot of futures = spot + 5.5% a year carry paid by longs, a roll charge per monthly expiry held, and SWING_DEEP's
costs and slippage.

- **Futures mirror** (the same legs and timestamps as each option variant): the futures lost more than the options
  in 67 of 100 variants (median -Rs 1.25 lakh against -Rs 58k). Futures returns are not significant either
  (SPA p = 0.86).
- **Pure futures system** (signal + channel exits only, no expiry rule): 12 settings, every distinct
  signal/exit setting in the grid. Over 2020 to Oct 2026:

| futures setting | trades | net | PF | max DD | years + | avg sessions | p random |
|---|---|---|---|---|---|---|---|
| N=7, all days | 275 | -288.5k | 0.88 | -571.9k | 3/7 | 5.9 | 0.52 |
| N=7, VIX | 161 | +184.0k | 1.18 | -273.1k | 4/7 | 6.5 | 0.18 |
| N=10, all / VIX | 203 / 124 | -779.0k / -177.0k | 0.67 / 0.86 | -1.08M / -700k | 2/7, 4/7 | 8.7 / 9.3 | 0.90 / 0.53 |
| N=20, all / VIX | 113 / 63 | -751.5k / -217.1k | 0.60 / 0.76 | -1.06M / -731k | 2/7, 4/7 | 15.0 / 15.8 | 0.96 / 0.67 |
| N=55, all / VIX | 41 / 24 | -345.8k / -33.4k | 0.66 / 0.94 | -838k / -549k | 4/7, 3/7 | 42.0 / 44.8 | 0.77 / 0.53 |
| N=7, VIX, exit channel 7 / 2 | 132 / 183 | -264.8k / +126.5k | 0.84 / 1.12 | -888k / -291k | 4/7, 4/7 | 12.0 / 4.7 | 0.44 / 0.12 |
| N=7, VIX, max hold 5 | 198 | +280.7k | 1.25 | -228.4k | 5/7 | 4.2 | 0.08 |
| N=7, VIX, daily-close exit | 132 | -328.8k | 0.80 | -738.4k | 4/7 | 10.5 | 0.52 |

- **Where the profitable settings made their money:** every profitable futures setting is N = 7 with the VIX
  filter, which made its money in 2020 to 2023 and lost in 2025 and 2026. For example, the max-hold-5 setting made
  +240.7k in 2022 and -90.0k in 2026.
- **The classic turtle settings** (20/55 days, all days) lose heavily in futures, as in SWING_DEEP.

**Conclusion:** the options did not lose because of theta. The breakout signal has no lasting direction edge after
2023, and none at all for the classic channel lengths.

### Overnight versus intraday

Gross P&L split into close-to-open (overnight) and open-to-close (intraday) pieces on every held day:

- **Options, walk-forward:** overnight +Rs 78.5k, intraday -Rs 152.3k.
- **Futures mirror, walk-forward:** overnight +Rs 194.7k, intraday -Rs 158.3k.
- **All variants:** overnight was positive in 89 of 100 and intraday negative in 84 of 100.

Holding through the night is what earns, as SWING_DEEP found. The daytime reversals into the trailing channel give
it back, plus costs.

## Limits

- **Missing contracts:** the next weekly and next monthly contracts are not in Dhan's rolling data. So M5 exits
  (the catalog allows "exit") rather than rolling, and W1 rolls into the monthly or waits flat for one expiry day.
- **Model prices:** 17% of held leg-days use model prices because the contract left the ATM±10 window. The median
  model error is +0.5% (absolute 3.7%), and the bias slightly flatters deep-ITM winners if anything.
- **Monte Carlo:** it resamples whole trades and ignores that the NIFTY and BANKNIFTY positions overlap in time.
  The daily mark-to-market drawdown above does include the overlap.
- **Futures modelling:** futures are modelled as spot + carry, because the dataset has no historical futures.
- **Grid coverage:** catalog N = 5 was not run (100-variant cap). The VIX filter, strikes and the catalog's exit
  rule were all run.
