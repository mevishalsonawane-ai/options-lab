# indicator

155 technical indicators for BANKNIFTY research, written from each indicator's published formula (Wilder, Lane,
Appel, Bollinger, Chaikin, Ehlers, Hosoda, Williams, ...). They are not copies of anyone's community scripts: those
belong to their authors. Plain pandas / numpy, no TA-Lib needed.

| file | what is in it |
|---|---|
| `moving_averages.py` | SMA, EMA, WMA, RMA, DEMA, TEMA, TRIMA, HMA, ZLEMA, KAMA, ALMA, LSMA, VWMA, T3, McGinley, VIDYA, FRAMA, JMA (approx.), GMMA, ribbon, envelopes |
| `trend.py` | MACD, PPO, APO, ADX/DMI, ADXR, Aroon, Supertrend, Parabolic SAR, Ichimoku, Vortex, TRIX, KST, DPO, Mass Index, Coppock, STC, QStick, Choppiness, Elder Ray, Alligator, regression slope/channel, Heikin Ashi, Chande Kroll, Chandelier, Half Trend, EMA trend |
| `momentum.py` | RSI, Stochastic, Stoch RSI, Williams %R, CCI, ROC, Momentum, CMO, Ultimate, Awesome, Accelerator, TSI, RVGI, Connors RSI, Fisher, Inverse Fisher RSI, KDJ, SMI, Laguerre RSI, BOP, DeMarker, Psychological line, RMI, Squeeze, WaveTrend, QQE, Elder Impulse, PMO, CFO, RWI, IMI |
| `volatility.py` | True range, ATR, NATR, Bollinger (%B, bandwidth), Keltner, Donchian, ATR bands, std dev, historical / Parkinson / Garman-Klass / Rogers-Satchell / Yang-Zhang volatility, Chaikin volatility, Ulcer, RVI, squeeze ratio, ADR |
| `volume.py` | OBV, A/D line, CMF, Chaikin oscillator, MFI, Force index, EOM, VPT, NVI, PVI, Klinger, VWAP with bands, volume oscillator, PVO, RVOL, Twiggs, VZO, volume z-score, volume profile (POC, value area) |
| `levels.py` | Classic / Fibonacci / Camarilla / Woodie / DeMark pivots, CPR, previous-day levels, opening range, fractals, swing pivots, ZigZag, market structure (BOS / CHoCH), fair value gaps, order blocks, Fibonacci retracements, HH/HL count |
| `candles.py` | Doji, hammer / hanging man, shooting star / inverted hammer, engulfing, harami, piercing / dark cloud, morning / evening star, three soldiers / crows, inside / outside bar, marubozu, tweezers, spinning top |
| `statistics.py` | z-score, percentile rank, correlation, beta, Hurst exponent, efficiency ratio, skew, kurtosis, entropy, drawdown |

```python
import pandas as pd
import indicator as ind

df = pd.read_csv("banknifty_recent.csv", parse_dates=["ts"], index_col="ts")   # open, high, low, close [, volume]
ind.RSI(df, 14)
ind.Supertrend(df, 10, 3)          # DataFrame: supertrend, direction (+1 / -1)
ind.CPR(df)                        # pivot, tc, bc, width_pct from the previous day
ind.catalog()                      # the full list with what each one measures
ind.compute_all(df)                # every per-bar indicator at default settings, one wide DataFrame
```

- Every function takes a DataFrame with `open, high, low, close` (and `volume` where marked) and returns a Series or
  DataFrame on the same index, NaN until there is enough history.
- Volume indicators raise an error on an index (BANKNIFTY spot has no volume); use futures minutes for them.
- Where platforms differ, the source says which definition is used (for example RSI and ATR use Wilder's smoothing,
  as TradingView does; the Supertrend matches the Pine-exact version in `research/st_ema_pine.py`).
- Full list: [CATALOG.md](CATALOG.md) (regenerate with `python -m indicator.make_catalog`).
- Tests: `python -m pytest indicator -q`.
