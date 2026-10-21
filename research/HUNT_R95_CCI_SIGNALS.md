# R95: the "Mo3ty CCI Signals" indicator (21 Oct 2026) - pre-registered, NOT RUN yet

Boss, 21 Oct: "Deep understand the indicator below, find the connection between the indicator data and actual data, and
find out the strategy's win rate."

**Status: there is no win rate yet.** The plan, the code and its tests are done and pushed BEFORE any number. The
data run has not happened: in this session the Drive lake (`gdrive:IraAlgo-data-lake`) could not be copied. The
container's rclone has no Google Drive backend, and running a downloaded rclone with the Drive credentials was refused
by the session's permission check. Nothing was worked around. Every number below comes from the formula itself, not
from market data. The run order is in `hunt/r95_cci_signals/NOTES.md`.

Research only: nothing in `android/**` or `.github/**` changed.

- **Plan:** `hunt/r95_cci_signals/PREREG.md`: 8 variants x 4 timeframes x 9 instruments = 288 cells, the gates, the
  twins, the walk-forward and the one holdout read.
- **Source:** `hunt/r95_cci_signals/SOURCE.md` (Boss's Pine v6, verbatim; the divergence block as summarised).
- **Code:** `cci.py` (the indicator, bar for bar as Pine), `rules.py` (trades), `price.py` (option prices, twins),
  `describe.py` (indicator vs price, the repaint test), `study.py` (cells, gates, holdout guard).
- **Tests:** `tests/test_r95.py`, 43 tests on synthetic data:
  - the CCI against a hand-written Pine reference, and the ATR / RMA, pivots, divergences and the pulse;
  - the trade rules and the twins' exits;
  - the look-ahead guard: indicator values and signals on data cut at any bar equal the full-data values; a
    divergence is never known before its pivot + 5 bars; trades on data cut at a session boundary equal the
    full-data trades, for all 8 variants.

## Signal source of every rule: the UNDERLYING

| rule piece | read on |
|---|---|
| CCI of hlc3 (25; variants 20 / 50), levels +-200 (variant +-100) | the underlying (index spot 1m OHLC built into 3m / 5m / 15m / 60m bars; MCX near future, close-only) |
| the Volatility Pulse (ATR14 vs its SMA20, body vs ATR) | the underlying |
| price pivots (5 / 5) and divergences | the underlying |
| entry (the next minute's open), stop (beyond the stretch's extreme), exits (CCI crosses 0 / 2R / 10 bars / flat) | the underlying |
| what is bought | the 1-ITM call on BUY, the 1-ITM put on SELL, nearest expiry, real 1-minute prints |

No rule reads an option's own chart, and no option-chain data are used.

## What the indicator measures, in plain English

**CCI** (Commodity Channel Index, Lambert 1980) compares each candle's typical price, tp = (high + low + close) / 3,
with the average tp of the last 25 candles:

`CCI = (tp - average of the last 25 tp) / (0.015 x MD)`

MD (mean deviation) is how far, on average, those 25 tp values sit from their own average, in points. So CCI is "how
many MDs today's candle is from the 25-candle average", times 66.7 (1 / 0.015).

- **What +200 means:** tp - average = 0.015 x 200 x MD = **3 MDs above the 25-candle average**. If the 25 values were
  spread like a bell curve, one MD would be about 0.8 standard deviations, so 3 MDs is about 2.4 standard deviations.
  Minus 200 is the same distance below. In points it depends on the market: if NIFTY's 5-minute tp values sit on
  average 10 points from their mean, +200 means about 30 points above it. The run will report this distance in points
  and in ATR for every instrument and timeframe (`describe.py`, `mapping`).
- **The CCI has a ceiling.** With 25 candles it can never go beyond +-833 (n / 0.03). That happens when one candle jumps
  away from 24 identical ones. With 20 candles the limit is +-667, with 50 it is +-1,667.
- **A steady trend does not reach +200.** If price rises by the same amount every candle, CCI(25) stays at about
  **+128** forever (CCI 20: +127, CCI 50: +131). To get above +200, price must ACCELERATE: move faster than its own
  recent pace.
- **So what does the SELL signal really say?** "The CCI was at or above +200 on the last candle and is below it now."
  The acceleration has stopped. It does NOT say price has turned down. The average can catch up, or MD can widen, while
  price keeps rising. Worked example (in the tests): price climbs 3 points a candle for 8 candles, then keeps climbing
  at 1.5 a candle. The SELL fires while price is still rising, and price rises another 15 points over the next 10
  candles. It is a mean-reversion bet that the stretch will relax, made at the moment the stretch starts to relax.
  BUY is the mirror image.
- **The Volatility Pulse filter** (off by default) lets a signal through only if (a) ATR(14) is above its own 20-candle
  average, so volatility is expanding, and (b) the signal candle's body |close - open| is at least 0.25 x ATR. It does
  not check the body's colour: a SELL can pass on a green candle.
- **The divergences** look at confirmed price pivots: a candle whose low is below the 5 candles on each side (or whose
  high is above them).
  - **BULL:** the new pivot low is below the previous pivot low, the CCI at the new low is higher than at the old one,
    and one of the two CCI values is at or below -200. BEAR is the mirror image.
  - In plain words: price made a lower low, but with less stretch than the last time.

## WARNING: the divergence labels repaint in time

The label is drawn AT the pivot candle, but nobody can know it is a pivot until 5 more candles have closed after it.

- On a chart the labels look perfectly placed at the bottoms and tops. That is hindsight.
- Any "win rate" read from the chart's labels assumes you bought at the pivot candle, which no live trader can do.
- On 5-minute candles the true entry comes 25 minutes later; on 60-minute candles it comes 5 hours later, usually the
  next day. By then price has already moved away from the pivot, by definition, because the next 5 candles did not
  make a new low.
- R95 measures this inflation directly (`describe.py`, `repaint`). Every divergence is entered twice: at the label
  candle (hindsight, what the chart shows) and at the confirmation candle (what is really possible). Both are priced
  on the real option, with the same exit.
- The BUY / SELL arrows themselves do NOT repaint. They use only closed candles: `barstate.isconfirmed`, `cci[1]` and
  the current close.

## What the run will report (pre-registered)

Per cell, the design period first, then the locked holdout (1 Apr - 19 Oct 2026), read once:
- THE WIN RATE on the underlying (points, no costs) and on the option after costs;
- average win / average loss and the payoff ratio. A high win rate can still lose money when the wins are small and
  the losses big, and a CCI-zero exit tends to take small wins;
- expectancy per trade, trades a day, net, max drawdown, the worst day and the worst month (against the -Rs 13k
  monthly stop);
- the after-trigger random-entry twins (1000 per cell) and BH q-values over all 288 cells, the 4 walk-forward folds,
  and the pooled index and MCX books.

Descriptive, for the B signals:
- the price distance from the 25-candle mean at the cross (points, ATR, MD);
- signals per day by timeframe;
- forward returns and MFE / MAE over 5 / 15 / 30 / 60 candles, against every candle as a baseline;
- the divergence repaint inflation.

## Prior work, and what to expect

- **R85 / R87 / R88:** candles and 22 parameters predict the next candle's SIZE, not its DIRECTION. The CCI is a
  function of the last 25 candles' prices, so the prior is that it will not predict direction either.
- **R90 (Almazov BOS indicator, 0 of 99) and R91 (ICT sweep, 0 of 63):** indicator strategies failed after costs. The
  underlying moved too little per trade to pay the option round trip (about 1-4 points on the indices) and the time
  decay.
- **R86 / R87:** fading failed pokes beyond yesterday's high / low, round numbers and the opening range lost (6 of 6
  tests), and stop runs above the day's high were followed by more upside. A CCI +-200 cross is also a stretch fade.
- The prior is a fail. The run decides.

## The app's Pine engine: would it run unchanged?

Checked against `android/engine/.../pine` (Pine.kt, PineInterp.kt, PineCheck.kt):
- **Mostly yes.** These are all supported: `ta.cci`, `ta.atr`, `ta.sma`, `math.abs`, `input.int / float / bool /
  source(hlc3)`, `cci[1]`, `barstate.isconfirmed`, `ta.pivotlow / ta.pivothigh`, `ta.valuewhen`, `plot`, `hline`,
  `plotshape` (shown as markers) and `alertcondition` (shown as signals). The declaration's `max_lines_count /
  max_labels_count` are accepted.
- **The same CCI.** The engine's `ta.cci` uses the same formula (the SMA and mean absolute deviation of the source).
  Its pivot rule (a tie on the left counts, a tie on the right does not) is what `cci.py` copies, so the research
  matches the phone.
- **The divergence labels would not show.** The engine accepts `label.*` / `line.*` drawings but does not draw them;
  it gives a warning instead. The BUY / SELL markers and alerts work.
- **One caveat for any live use:** the engine returns `barstate.isconfirmed = true` on every bar. An arm built on it
  must act only on closed candles, or a signal could appear and then vanish on a candle that is still forming.

## Verdict

**None yet.** Nothing goes to paper and nothing goes live until the run is done and a cell passes every gate in the
design and the one holdout read. Without the data:
- The indicator measures how far the 25-candle typical price is stretched from its own average, in units of its
  recent spread.
- A cross back through +-200 says the stretch has stopped growing, not that price has turned.
- As a chart overlay it is an honest "the move is stretched / stopped accelerating" gauge.
- Its divergence labels look better on the chart than they can be traded, because each is known only 5 candles
  after the place where it is drawn.
