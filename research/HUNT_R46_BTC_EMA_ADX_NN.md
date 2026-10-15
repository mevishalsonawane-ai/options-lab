# R46: the other AI's "Option 1" BTC call strategy, re-tested (15 Oct 2026)

Boss (15 Oct 2026): "test it and give me the back results, test results." Research only. Nothing in the app trades
crypto, and nothing here placed an order. Not financial advice.

- Pre-registration: `research/hunt/r46/PREREG.md`, commit `3388242`, pushed before any profit or loss was computed.
- Design results: commit `d934a9e`, pushed before the holdout was opened.
- Holdout: read once, 15 Oct 2026 01:08 UTC (`research/hunt/r46/results/HOLDOUT_READ.json`).
- Code and tests: `research/hunt/r46/`. Every table: `research/hunt/r46/results/report.md`.
- Experiment entries: `research/experiments/entries/r46-*.yaml`.

## Verdict for Boss

**No. The claim does not hold, and nothing goes on paper.** We tested the same entry (BTC EMA 8/21 cross with ADX(14)
above 15) on Delta Exchange India's BTC ATM calls over 2 years 9 months of real option trades. It lost money:

- **in the design years:** 1,486 trades, about 25% winners;
- **in the locked holdout:** 391 trades, 28-41% winners;
- **in the other AI's own window,** 1 Sep - 12 Oct 2026: 83 trades, 25-37% winners, not 85.7%.

This holds before tax and after tax, for every stop-and-lock variant, and for the puts and the futures version too.
Our own copy of their neural-net filter took almost no trades (24 in 27 months), and those lost as well. None of the
14 variants passes a single gate.

## Their claim vs our backtest vs our holdout

Money in US dollars at the other AI's size, **1,000 contracts = 1 BTC of underlying**.

- Our fill is the realistic one: the option's price one minute after the first real trade, plus half the spread.
- The exits are 60 minutes, a 50% stop on premium, and a profit lock. Boss's rule requires the lock, and the other AI
  had none.
- After tax is reading A: 31.2% of every winning trade, with no set-off for losers.
- The best of the three lock variants (L2, the trailing lock) is shown. The other two are a little worse.

| | their claim | our backtest (design) | our holdout (test) | their window, our data |
|---|---|---|---|---|
| period | Sep-Oct 2026, 44 days | 1 Jan 2024 - 31 Mar 2026, 821 days | 1 Apr - 12 Oct 2026, 195 days | 1 Sep - 12 Oct 2026, 42 days |
| filter | EMA + ADX + their NN | EMA + ADX (A) | EMA + ADX (A) | EMA + ADX (A) |
| trades | 21 | 1,486 | 391 | 83 |
| win rate | 85.7% | 25.8% | 41.4% | 37.4% |
| per trade, before tax | +$122.30 | -$71.33 | -$15.26 | -$29.61 |
| total, before tax | +$2,568 | -$105,999 | -$5,968 | -$2,457 |
| total, after tax | +$1,798 ("30% of net") | -$127,828 | -$11,003 | -$3,233 |
| beats random entries? | not tested | no (p 0.58, q 0.83) | yes in this span only (p 0.001) | - |
| our NN copy (B), trades and total before tax | - | 24 trades, -$1,200 | 2 trades, -$51 | 0 trades |

In rupees (85 per USD), per single Delta lot (0.001 BTC, about Rs 24-35 of premium), the design loss after tax is
Rs 10,865 and the holdout loss Rs 935. At their 1,000-contract size it is about Rs 1.09 crore and Rs 9.35 lakh.

## What went wrong with the claim

1. **The sample was tiny and probably fitted.** Their 21 trades in 44 days cannot tell skill from luck.
   - Their net was most likely trained on the same weeks it was scored on. Its features and weights were not given.
   - On 83 trades in the same 42 days, the same EMA+ADX entry won 25-37% of the time, not 85.7%.
2. **The signal has no edge before costs.**
   - Design, at the optimistic printed-trade fill (no spread, no delay), the calls made -$5,616 gross on 1,486 trades.
   - In the holdout the calls did better than random entry times (BTC rose about 20% in that span). Even then the gross
     was only -$1,331 to +$134 on 391 trades at the realistic fill, and the costs then took about $6,100.
   - Puts and the perpetual future were worse.
3. **Delta's thin option book costs a lot.**
   - Buying at the printed trade instead of one minute later flatters the result by about $33,000 in design (1,486
     trades) and $6,300 in the holdout (391 trades). The printed-trade fill even shows the holdout calls at +$288
     before tax.
   - That is the same artefact F1 crypto found. With the honest fill they lose.
4. **Fees and GST were left out.**
   - Delta charged 0.03% of notional a side until Jul 2025, and 0.01% now (capped by a share of the premium), plus 18%
     GST.
   - That is about $45 a round trip per BTC in 2024 and about $16 now, on a premium of only $280-410.
   - Fees were $67,000 of the design loss.
5. **The tax was done wrong.**
   - India taxes each winning crypto trade at 30% + 4% cess. Losses are not set off. 1% TDS is withheld on every sale.
   - "30% of the net" understates the tax whenever there are losing trades.
   - Example on their own numbers: assume 3 losers at the 50% stop on a $300 premium. Then the 18 winners made about
     $3,018, the tax is about $942, and $1,626 is left, not $1,798. That is before any fee.
   - In our holdout, the printed-trade fill makes +$288 before tax but -$5,919 after it.
6. **The size is real but the book is not.** We checked the "1,000 contracts ~ $300" figure.
   - It matches: Delta's contract is 0.001 BTC, so 1,000 contracts is 1 BTC. The median ATM premium for that was $278
     in the holdout and $410 in design.
   - But an order of 1,000 contracts on Delta's daily options is far bigger than the small orders our spread model is
     fitted on. At that size, real fills would be worse than shown here.
7. **No profit lock.** Boss's crypto rule needs a stop and a lock on every trade, so their exit (stop + time only) was
   not run. Our three stop-and-lock versions all lose, and the differences between them are small (L2 trail best).

## What exactly was tested

- **Signal source: BTC spot, the underlying (the default).** Binance BTCUSDT 1-minute bars are built into 5-minute
  bars. Indicators run warm across days.
  - EMA(8) crosses above EMA(21) (crossover = above now, at or below on the bar before) AND Wilder ADX(14) > 15.
  - The mirrored put: EMA(8) crosses below EMA(21), labelled extra.
- **Instrument:** a Delta Exchange India BTC daily call.
  - Expiry: the nearest one with at least 90 minutes left. That is today's 17:30 IST expiry when the entry is by 16:00
    IST, else tomorrow's.
  - Strike: the one nearest spot (within 1%) that really traded in the 5 minutes from the next bar's open.
  - 7-8% of design signals had no such trade and were skipped. In the holdout none were (0 of 1,027 call signals).
- **Fills:**
  - main: the option's price one minute later plus half the spread;
  - also the naive printed trade;
  - and a stress reading with the spread x1.5.
  - Exits sell less half the spread, a tick and 1%.
- **Exits:** 60 minutes, and every trade had a stop AND a lock:
  - L1: 50% stop, step lock (a peak of +25/+50/+75/+100% locks entry/+25/+50/+75%, and so on);
  - L2: 50% stop, trailing lock from +30% that keeps half the peak gain;
  - L3: 30% stop with the L1 step lock.
  - On a gap the fill is the worse of the level and the open.
- **Limits:** one position at a time; at most 2 trades per IST day.
- **Variant B, our net:** the same shape (12 -> 64 -> 32 -> 1).
  - Inputs, all from BTC: returns over 5/15/60/240 minutes, short and daily volatility, ADX, +DI minus -DI, the
    EMA gap, relative volume, distance from the 24-hour high, and the perp basis.
  - Trained month by month only on data before each month; threshold 0.65.
  - Its validation loss stayed at about 0.692, which is coin-flip level. It cleared 0.65 on only 88 of 7,536 design
    signals and 6 of 2,063 holdout signals.
- **Baselines:**
  - random-entry twins: 1,000 draws on the same days, same exits;
  - the same signal on the BTC perpetual with a stop (-0.4%) and a trailing lock (1 BTC; Binance bars stand in for
    Delta's perp);
  - buying and holding 1 BTC: +$17,778 after fees and tax in design and +$9,183 in the holdout. This is a baseline, not
    advice: Boss does not hold crypto.
- **BH family:** 14 variants (A and B x calls and puts x L1-L3, plus perp long and short).

## All variants (main fill, 1,000 contracts, after tax)

| variant | design trades | design after tax | design p / q | holdout trades | holdout after tax | gate |
|---|---|---|---|---|---|---|
| A call L1 | 1,486 | -$128,340 | 0.52 / 0.83 | 391 | -$12,187 | fail |
| A call L2 | 1,486 | -$127,828 | 0.58 / 0.83 | 391 | -$11,003 | fail |
| A call L3 | 1,486 | -$131,621 | 0.69 / 0.83 | 391 | -$12,171 | fail |
| A put L1 (extra) | 1,478 | -$118,472 | 0.21 / 0.64 | 391 | -$18,439 | fail |
| A put L2 (extra) | 1,478 | -$119,126 | 0.27 / 0.64 | 391 | -$18,867 | fail |
| A put L3 (extra) | 1,478 | -$118,625 | 0.16 / 0.64 | 391 | -$18,162 | fail |
| B call L1 | 24 | -$1,892 | 0.25 / 0.64 | 2 | -$51 | fail |
| B call L2 | 24 | -$1,789 | 0.23 / 0.64 | 2 | -$51 | fail |
| B call L3 | 24 | -$2,844 | 0.61 / 0.83 | 2 | -$51 | fail |
| B put L1-L3 (extra) | 1 | -$99 | 0.81-0.83 | 0 | - | fail |
| perp long (1 BTC) | 1,639 | -$249,401 | 0.15 / 0.64 | 391 | -$46,033 | fail |
| perp short (extra) | 1,636 | -$256,564 | 0.46 / 0.83 | 391 | -$58,645 | fail |

Other readings:

- **Walk-forward:** every A and perp variant lost in all 9 design quarters.
- **Stress spread:** A call L2 loses $138,249 in design.
- **Boss's 00:00-09:00 IST window only:** A call L2 lost $96.41 per lot after tax on 1,097 design trades (about
  -$96,410 at 1,000 contracts).
- **TDS:** about $1,150 withheld over 391 holdout trades at the 1,000-contract size (options, refunded only after the year's return). The perp would lock
  $1.36 million over the design years, so TDS alone rules out perps.
- **Holdout twins:** the A calls beat random entries in the holdout (p 0.001-0.02, holdout-family q 0.01-0.07). In design
  they did not (p 0.52-0.69), so this is not a stable edge. It still lost money after costs in the holdout.

## Honest limits

- The holdout (Apr - Oct 2026) was opened before by RC1, RC2, NN_CRYPTO and STRAD_CRYPTO for other crypto ideas. It
  is "partly seen", but nothing in R46 was chosen on it.
- Their real net is unknown. Ours is a fair stand-in for "an MLP on price features at 0.65", but not their model. On
  this data, no net we can build passes 0.65 often, because the 60-minute direction of BTC is close to a coin flip
  (validation loss about 0.692).
- The spread model comes from a few dozen live snapshots of Delta's chain in Oct 2026. Older books were probably thinner,
  and 1,000-contract orders would move the price. Both make the true result worse, not better.
- The tax reading (A) is a modelling assumption that a CA must confirm. Even before tax, nothing here makes money at
  the realistic fill.
- Option candles are trades. Between trades the last price is carried forward, so a stop, lock or time exit can be
  checked against a price that is a few minutes old. Entries always need a real trade; exits do not, since a market exit
  would fill against the book at once. The 1% exit slippage and the half spread cover part of that gap.
- Missing data: none. All 1,018 Delta expiry days are present, and no BTC minute is missing.

## Amendments (15 Oct 2026, labelled): CoinSwitch costs, the "v2 Ensemble", and the 25% dip buy

Boss asked for three more checks after R46 was finished. R46's own results above stay exactly as they were. Everything
below is new: code `hunt/r46/amend*.py`, `chain.py`, `panel.py`, `ens.py`, `dip.py`, `costs.py` (tests
`hunt/r46/test_amend.py`); numbers in `hunt/r46/results/amend.json` and `amend_report.md`. Research only; nothing in
the app trades crypto, and nothing here placed an order.

**Which view to read.** Boss (15 Oct) wants only 20 Nov 2024 onwards. So the **PRIMARY view** is:

- design: 20 Nov 2024 - 31 Mar 2026 (no trade or training before 20 Nov 2024);
- final test: 1 Apr - 12 Oct 2026.

The full span (design from 1 Jan 2024) is shown beside it. **The final test is the same span as R46's holdout, which
R46 already read once.** The amendment reports on that same span. The spans were fixed in `amend_cfg.py` (commit
d492524) before the amendment run, and no rule, threshold or span was changed after any result. Before that commit only
a 4-day smoke test had been seen.

**Verdict: nothing passes, in either view, under Delta's or CoinSwitch's costs, with or without GST.** 0 of 22
variants pass: R46's 14, v2 x 4 and dip x 4, with BH across all 22.

### Amendment 1: CoinSwitch PRO costs

**Costs used.**

- CoinSwitch PRO BTC options: 0.015% of notional a side.
- CoinSwitch futures: 0.05% taker, 0.02% maker.
- These are third-party figures, NOT verified. We charge the option fee on notional with no cap, which is the dearer
  reading. If CoinSwitch charges it on the premium, the fee is about zero and the results move toward the "no fee"
  case.
- GST is shown with 18% on the fee and without.
- Tax is unchanged: 30% + 4% cess on each winning trade, no set-off. 1% TDS is withheld on each sale (cash flow), for
  example about $4,600 on the 991 design trades of A call L2.
- CoinSwitch PRO routes BTC options under "dtrade", most likely Delta's own book, so the spread model and the
  one-minute-later fill stay.

**R46 A call L2 (best lock), 1,000 contracts = 1 BTC, after tax:**

| view | trades | Delta + GST | CoinSwitch + GST | CoinSwitch, no GST |
|---|---|---|---|---|
| Nov24+ design | 991 (win 27.8%) | -$89,295 | -$77,822 | -$72,640 |
| Nov24+ final test | 391 (win 41.4%) | -$11,003 | -$14,895 | -$13,370 |
| full-span design | 1,486 | -$127,828 | -$106,221 | -$99,300 |

- CoinSwitch's 0.015% is cheaper than Delta's old 0.03% (to Jul 2025), but dearer than Delta's current 0.01% with
  its premium cap. So it helps the older months and hurts the final test.
- **No variant turns positive.**
- **Their net B:** 4 design trades in the Nov 2024+ view (+$410 to +$549 after tax) and 0 in the final test. Its
  walk-forward MLP may only train on data from 20 Nov 2024, so it starts in May 2025.
- **Perp:** even at the CoinSwitch maker fee without GST, the perp long loses $101k (design) and $24k (final) per
  1 BTC after tax.

### Amendment 2: the other AI's "v2 Ensemble"

**Their claim:**

- Entry: the R46 entry (EMA 8/21 cross up + ADX(14) > 15 on 5-minute BTC spot, ATM call next bar, 60 minutes, 50%
  stop, max 2 a day).
- Filter: the average of an MLP (12 -> 64 -> 32 -> 1, dropout 0.2), GradientBoosting (200 trees, depth 4, lr 0.05) and
  a RandomForest (200 trees, depth 10) above 0.65.
- Target: "the call premium is up 60 minutes later".
- 12 inputs from the call's 5-minute chart and BTC.
- Result claimed: Sep-Oct 2026, 35 trades, 85.7% win, +$132.20 a trade, AUC 0.78.
- Signal source: the entry is on the underlying. The filter reads the option's own chart (non-default, labelled).

**What we did.**

- We rebuilt the 12 inputs twice:
  - on the **rolling** ATM call series, which is what a "btc_atm" file holds: the strike nearest spot, rolling to the
    next daily expiry;
  - on the **held** contract: the ATM call at the decision minute, followed on its own prices.
- We scored AUC with a shuffled 80/20 split and with time-ordered walk-forward blocks (quarterly, purge + 12-bar
  embargo).
- We traded it with walk-forward ensembles only, on the held contract, with the one-minute-later fill, Delta and
  CoinSwitch costs and per-trade tax.
- Every trade had a profit lock: L1 step or L2 trail (a no-lock version was not tested).

**AUC, Nov 2024+ view (n = rows scored):**

| inputs + label | random split (design) | walk-forward design | walk-forward final | the same rolling model judged on the held contract's own move |
|---|---|---|---|---|
| rolling series | 0.786 (28,618) | 0.782 (122,068) | 0.796 (56,148) | 0.510 design / 0.502 final |
| held contract | 0.575 (28,354) | 0.538 (121,112) | 0.543 (56,045) | - |

What this means:

- **Their 0.78 is real, and worthless.** It comes from the rolling series, not mainly from the random split. The
  leakage gap is only 0.004 on rolling and 0.037 on held.
- The rolling strike or expiry changes inside **97.9%** of the 60-minute label windows. **98%** of the rolling "up"
  labels coincide with such a switch. The rolling label agrees with the held contract's real move only 57% of the time.
  So the model learns *when the series will jump to another contract*, which no trade can capture.
- Judged on the contract you would actually hold, the same model is a coin flip (AUC 0.50-0.51).
- On the held contract the honest AUC is 0.54, with a base rate of 42% "up". The ensemble then almost never clears
  0.65: **1 design trade, 0 final-test trades**.

**Trades (1,000 contracts, Delta costs; L1 step lock shown, L2 similar):**

| filter inputs | Nov24+ design | Nov24+ final test | full-span design |
|---|---|---|---|
| rolling series | 281 trades, win 24.6%, -$12,814 before tax, -$16,561 after; worst -$525; max DD $16,561; p 0.006, q 0.11 | 140 trades, win 30.7%, -$2,656 / -$4,027; p 0.095 | 496 trades, -$33,795 after tax |
| held contract | 1 trade (+$6 after tax) | 0 trades | 6 trades, -$24 |

- CoinSwitch costs (with or without GST) change no sign: for the rolling L1 design, -$15,893 and -$14,401 after tax.
- **In their own window (Sep - 12 Oct 2026)** the rolling-filter version made 23 trades, 43.5% win, -$183 before tax.
  Their claim was 35 trades, 85.7% and +$132 a trade.
- The ensemble's design p of 0.006 means its entries were better than random times on the same days. They still lost
  after costs.
- GradientBoosting was fitted with histogram split finding (sklearn HistGradientBoosting, same 200 trees, depth 4 and
  lr 0.05) to make 38 walk-forward fits feasible.

### Amendment 3: "BTC options mean reversion: buy after a 25% drop"

**Their claim:**

- The rule: on 15-minute bars of the BTC ATM call (and separately the put), buy at the bar close when the premium fell
  25% or more in that bar and is at least $50. Sell 4 bars (60 minutes) later. $300 a trade, no stop.
- Results claimed, Sep 2024 - Oct 2026:
  - CE: 5,504 trades, 59.5% win, average +$424, median +$28, total +$2.34 M, max DD -$2,875;
  - PE: 5,743 trades, 59.1% win, +$408 average.
- How: synthetic fills, a $5 cost, a 2.1 M-row file, `df.premium.pct_change()` over the whole frame, exit at row
  idx + 4.
- Signal source: the option's own chart (NON-DEFAULT, labelled).

**Reproduced in kind.** We built a frame like theirs: the rolling ATM call and put, one row per minute each,
interleaved. It has 2,223,282 rows for Sep 2024 - 12 Oct 2026.

1. **Their code, literally.**
   - Every call row's `pct_change` compares it with the **put row** before it, so 100% of signals are mixed rows.
   - `idx + 4` is **2 minutes** later, not 60.
   - It "finds" 66,150 call trades (58% win, +$49 average, +$3.24 M) and 109,097 put trades (+$5.40 M).
   - **84% of that profit** is from exits on another strike or expiry.
   - The rest is +$10 a trade over 2 minutes on stale last-trade prices with no spread.
2. **Their words (15-minute closes of one side's rolling series).**
   - Calls: 3,482 trades, 69.6% win, +$323 average, median +$44, total +$1.12 M, max DD -$398. Puts are similar
     (3,697 trades, +$296 average). This is close to their claim's shape.
   - But **99.5% of the profit comes from rows that are not one contract**. 68% of signals are strike switches.
   - The daily expiry roll alone made **$1.03 M** on 645 call trades: the exit is priced on the next day's contract,
     which has a day more of time value.
   - The 351 trades that were one contract throughout averaged +$17.6, before any spread.

**Re-tested honestly on held contracts.**

- Rule: at each 15-minute close, the ATM contract whose own price fell 25% or more since the previous close and is at
  least $50.
- Fill: buy it at the first real trade 1-5 minutes after the close, plus half the spread.
- Exit: 60 minutes on the same contract, with a 50% stop and an L1 step or L2 trailing lock. Exits pay half the
  spread, a tick and 1%. $300 a trade.

| variant | Nov24+ design: trades, win, before / after tax, worst, max DD, p | Nov24+ final test | full-span design |
|---|---|---|---|
| call L1 | 3,548, 26.8%, -$181,242 / -$226,911, -$260, $227,164, p 0.97 | 1,380, 33.8%, -$43,685 / -$61,249, p 0.57 | 5,044, -$338,443 after tax |
| call L2 | 3,502, 30.1%, -$180,779 / -$223,641, -$260, $223,894, p 0.99 | 1,362, 43.5%, -$47,405 / -$62,887, p 0.76 | 4,977, -$337,229 |
| put L1 | 3,727, 27.1%, -$196,630 / -$244,824, -$276, $245,060, p 1.00 | 1,401, 31.1%, -$57,460 / -$72,744, p 1.00 | 5,206, -$357,321 |
| put L2 | 3,688, 29.7%, -$198,164 / -$243,667, -$276, $244,042, p 1.00 | 1,378, 40.2%, -$55,741 / -$71,069, p 1.00 | 5,153, -$354,962 |

- On the held contract, a call that dropped 25% in 15 minutes loses about $51 a trade on $300 (-17%), before tax.
  That is worse than random entries (p 0.97-1.00).
- The spread on a $50-$100 premium costs about 10% each way.
- CoinSwitch costs make it worse in the final test: call L1 -$93,109 after tax with GST, -$84,264 without.
- Over their claim window from Boss's start (20 Nov 2024 - 12 Oct 2026), the honest call L1 made 4,928 trades,
  28.7% win, -$224,927 before tax.

### Honest limits of the amendments

- The final test is R46's already-read holdout span. Nothing was tuned on it, but it is not a fresh test.
- The rolling ATM series is our construction of a "btc_atm" file: R46's expiry rule (roll 90 minutes before the 12:00
  UTC settlement), the strike nearest spot within 1%, and the last traded price. Their file may roll at expiry itself.
  That would add even more roll artefacts, not fewer.
- The CoinSwitch fee figures are unverified, and the spread model is Delta's. Both readings lose by a wide margin.
- The tax reading (A) still needs a CA. Every result here loses before tax too.
