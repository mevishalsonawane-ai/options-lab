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
