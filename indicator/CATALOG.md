# Indicator catalog

155 indicators, each written from its author's published formula (see the source for the exact definition used where platforms differ). Import with `import indicator as ind` and call `ind.<Name>(df, ...)` on a DataFrame with open, high, low, close (and volume where marked).

## Candlestick patterns (13)

| name | what it is | needs volume |
|---|---|---|
| `Doji` | Doji: body under 10% of the range. |  |
| `Engulfing` | Engulfing: this body fully covers the previous opposite-colour body. |  |
| `Hammer` | Hammer (+1) after a fall / hanging man (-1) after a rise: long lower shadow, small body at the top. |  |
| `Harami` | Harami: a small body inside the previous large opposite body. |  |
| `InsideBar` | Inside bar (1): the whole range inside the previous bar's range. |  |
| `Marubozu` | Marubozu: body at least 95% of the range; +1 green, -1 red. |  |
| `OutsideBar` | Outside bar: range covers the previous bar's; +1 if it closes up, -1 down. |  |
| `PiercingDarkCloud` | Piercing line (+1) / dark cloud cover (-1): opens beyond the previous close, closes past its midpoint. |  |
| `ShootingStar` | Inverted hammer (+1 after a fall) / shooting star (-1 after a rise): long upper shadow. |  |
| `SpinningTop` | Spinning top: small body (<30% of range) with both shadows longer than the body. |  |
| `Star` | Morning star (+1) / evening star (-1): big candle, small star, big opposite candle past the midpoint. |  |
| `ThreeSoldiersCrows` | Three white soldiers (+1) / three black crows (-1): three strong candles in a row, each closing further. |  |
| `Tweezer` | Tweezer bottom (+1) / top (-1): two bars with matching lows / highs (within tol of the range). |  |

## Levels & structure (16)

| name | what it is | needs volume |
|---|---|---|
| `CPR` | Central Pivot Range: pivot, top and bottom central levels, and width % (narrow = trend day). |  |
| `FairValueGap` | Fair value gaps: a 3-candle gap (low > high two bars back = bullish; the mirror bearish). |  |
| `FibRetracement` | Fibonacci retracements of the last n bars' swing: 0, 23.6, 38.2, 50, 61.8, 78.6, 100 %. |  |
| `Fractals` | Williams fractals: a high with 2 lower highs each side (bearish), and the mirror for lows. |  |
| `HHHL` | Higher highs / higher lows count: +1 per bar that makes both, -1 for lower highs and lower lows. |  |
| `MarketStructure` | Market structure: break of structure (BOS, with the trend) and change of character (CHoCH, against it) on closes through the last confirmed swing high / low; +1 bullish / -1 bearish. |  |
| `OpeningRange` | Opening range: the first n minutes' high and low each day (the ORB levels). |  |
| `OrderBlocks` | Order blocks (common SMC definition): the last opposite candle before a move of k x ATR. |  |
| `PivotCamarilla` | Camarilla pivots (Nick Scott): close +/- range x 1.1/12, /6, /4, /2. |  |
| `PivotClassic` | Classic floor pivots from the previous day: P, R1-R3, S1-S3. |  |
| `PivotDeMark` | DeMark pivots: X from the previous open/close relation; R1 = X/2 - L, S1 = X/2 - H. |  |
| `PivotFibonacci` | Fibonacci pivots: P +/- 0.382 / 0.618 / 1.0 of the previous day's range. |  |
| `PivotWoodie` | Woodie pivots: P = (H + L + 2*today's open)/4. |  |
| `PreviousDayLevels` | Previous day high / low / close and today's open, for every bar. |  |
| `SwingPivots` | Swing pivots (ta.pivothigh / pivotlow): confirmed after [right] bars, placed at the pivot bar. |  |
| `ZigZag` | ZigZag: swing points where price reverses by at least pct % (uses highs/lows). |  |

## Momentum (31)

| name | what it is | needs volume |
|---|---|---|
| `AcceleratorOscillator` | Accelerator Oscillator (Williams): AO - SMA5(AO). |  |
| `AwesomeOscillator` | Awesome Oscillator (Williams): SMA5(hl2) - SMA34(hl2). |  |
| `BOP` | Balance of Power (Livshin): (close - open)/(high - low), smoothed. |  |
| `CCI` | Commodity Channel Index (Lambert): (TP - SMA(TP))/(0.015 * mean deviation). |  |
| `CFO` | Chande Forecast Oscillator: % distance of the close from the n-bar regression forecast. |  |
| `CMO` | Chande Momentum Oscillator: (sum up - sum down)/(sum up + sum down)*100 over n. |  |
| `ConnorsRSI` | Connors RSI: mean of RSI(3), RSI(2) of the up/down streak, and the 100-bar percentile rank of ROC(1). |  |
| `DeMarker` | DeMarker (DeMark): up-pressure / (up + down pressure) over n, 0-1. |  |
| `ElderImpulse` | Elder Impulse: +1 when EMA(13) and the MACD histogram both rise, -1 when both fall, else 0. |  |
| `FisherTransform` | Fisher Transform (Ehlers): Gaussian-normalised price position in the n-bar range, with trigger. |  |
| `IMI` | Intraday Momentum Index (Chande): RSI of candle bodies (close vs open) over n. |  |
| `InverseFisherRSI` | Inverse Fisher Transform of RSI (Vervoort): -1..+1, sharp turns at extremes. |  |
| `KDJ` | KDJ: stochastic K and D with J = 3K - 2D (common in Asian markets). |  |
| `LaguerreRSI` | Laguerre RSI (Ehlers): a four-element Laguerre filter RSI, gamma 0.5, 0-1. |  |
| `Momentum` | Momentum: close - close n bars ago. |  |
| `PMO` | Price Momentum Oscillator (Swenlin): double-smoothed ROC(1) x 10, signal EMA(10). |  |
| `PsychologicalLine` | Psychological line: % of up closes in the last n bars. |  |
| `QQE` | QQE (Quantitative Qualitative Estimation): smoothed RSI with a trailing band of 4.236 x its smoothed ATR. |  |
| `RMI` | Relative Momentum Index (Altman): RSI on the m-bar change instead of 1-bar. |  |
| `ROC` | Rate of change: % change over n bars. |  |
| `RSI` | Relative Strength Index (Wilder), Wilder smoothing, 0-100. |  |
| `RVGI` | Relative Vigor Index (Ehlers): symmetric-weighted (close-open)/(high-low), with signal. |  |
| `RWI` | Random Walk Index (Poulos): trend strength vs a random walk, high and low lines. |  |
| `SMI` | Stochastic Momentum Index (Blau): close vs the range midpoint, double-smoothed, -100..100. |  |
| `Squeeze` | TTM-style squeeze: Bollinger(20,2) inside Keltner(20,1.5) = squeeze on; momentum = linreg of close minus the midpoint of the Donchian/SMA mean (the widely published formula). |  |
| `StochRSI` | Stochastic RSI (Chande & Kroll): the stochastic of RSI, smoothed. |  |
| `Stochastic` | Stochastic (Lane): %K = (close - n-low)/(n-high - n-low), slowed by k, %D = SMA(d). |  |
| `TSI` | True Strength Index (Blau): double-smoothed momentum / double-smoothed |momentum|, signal EMA. |  |
| `UltimateOscillator` | Ultimate Oscillator (Williams): buying pressure over 7/14/28 bars, weighted 4:2:1. |  |
| `WaveTrend` | WaveTrend oscillator (the widely published channel-index formula): wt1 and wt2 = SMA4(wt1). |  |
| `WilliamsR` | Williams %R: (n-high - close)/(n-high - n-low) * -100, 0 to -100. |  |

## Moving averages (21)

| name | what it is | needs volume |
|---|---|---|
| `ALMA` | Arnaud Legoux MA: Gaussian weights centred at [offset] of the window, width n/sigma. |  |
| `DEMA` | Double EMA (Mulloy): 2*EMA - EMA(EMA), less lag. |  |
| `EMA` | Exponential moving average, alpha = 2/(n+1). |  |
| `Envelope` | Envelopes: SMA(n) +/- pct %. |  |
| `FRAMA` | Fractal adaptive MA (Ehlers): alpha from the fractal dimension of the window (n even). |  |
| `GMMA` | Guppy multiple MAs: 6 short (3..15) and 6 long (30..60) EMAs. |  |
| `HMA` | Hull moving average: WMA(2*WMA(n/2) - WMA(n), sqrt(n)). |  |
| `JMA` | Jurik-style smoothing is proprietary; this is the published JMA approximation (phase 0, power 2). |  |
| `KAMA` | Kaufman adaptive MA: speed set by the efficiency ratio (net move / path length). |  |
| `LSMA` | Least-squares MA: the end point of the n-bar linear regression line. |  |
| `MARibbon` | Moving-average ribbon: EMAs 8..89 (Fibonacci) and whether they are stacked up (+1) / down (-1). |  |
| `McGinley` | McGinley Dynamic: MD += (close - MD) / (k*n*(close/MD)^4), tracks price speed. |  |
| `RMA` | Wilder's smoothed moving average (RMA / SMMA), alpha = 1/n. |  |
| `SMA` | Simple moving average: the plain mean of the last n closes. |  |
| `T3` | Tillson T3: six chained EMAs with volume factor v (default 0.7). |  |
| `TEMA` | Triple EMA (Mulloy): 3*E1 - 3*E2 + E3. |  |
| `TRIMA` | Triangular moving average: an SMA of an SMA (weights peak mid-window). |  |
| `VIDYA` | VIDYA (Chande): an EMA whose alpha is scaled by |CMO(9)|. |  |
| `VWMA` | Volume-weighted MA: sum(close*volume)/sum(volume) over n bars. | yes |
| `WMA` | Weighted moving average: linear weights 1..n, newest heaviest. |  |
| `ZLEMA` | Zero-lag EMA (Ehlers): EMA of close + (close - close[lag]), lag = (n-1)/2. |  |

## Statistics (10)

| name | what it is | needs volume |
|---|---|---|
| `Beta` | Rolling beta of this close's returns against [other]'s over n. |  |
| `Correlation` | Rolling correlation of this close with another series [other] (e.g. NIFTY) over n. |  |
| `Drawdown` | Drawdown from the running peak, %. |  |
| `EfficiencyRatio` | Efficiency ratio (Kaufman): net move / sum of absolute moves over n (1 = straight line). |  |
| `Entropy` | Shannon entropy of up/down closes over n (1 = random, 0 = one-way). |  |
| `Hurst` | Hurst exponent (rescaled range) over n bars: > 0.5 trending, < 0.5 mean-reverting. |  |
| `Kurtosis` | Rolling excess kurtosis of returns over n. |  |
| `PercentRank` | Percentile rank of the close within the last n bars (0-100). |  |
| `Skew` | Rolling skewness of returns over n. |  |
| `ZScore` | Z-score of the close vs its n-bar mean, in standard deviations. |  |

## Trend (27)

| name | what it is | needs volume |
|---|---|---|
| `ADX` | ADX / DMI (Wilder): +DI, -DI and ADX with Wilder smoothing. |  |
| `ADXR` | ADX rating (ADXR): average of ADX now and n bars ago. |  |
| `APO` | Absolute price oscillator: fast SMA - slow SMA. |  |
| `Alligator` | Williams Alligator: SMMA(hl2) jaw 13/8, teeth 8/5, lips 5/3 (period/shift). |  |
| `Aroon` | Aroon (Chande): bars since the n-bar high / low as 0-100, and the oscillator. |  |
| `ChandeKrollStop` | Chande Kroll stop: ATR stops off the n-bar extremes, smoothed over q bars. |  |
| `ChandelierExit` | Chandelier exit (Le Beau): highest high - k*ATR for longs, lowest low + k*ATR for shorts. |  |
| `Choppiness` | Choppiness Index (Dreiss): 100*log10(sum ATR / range)/log10(n); >61.8 choppy, <38.2 trending. |  |
| `Coppock` | Coppock curve: WMA(10) of ROC(14) + ROC(11) (monthly in the original). |  |
| `DPO` | Detrended price oscillator: close[n/2+1 ago] - SMA(n) (cycle, not trend). |  |
| `EMATrend` | Directional trend: +1 when EMA(fast) > EMA(slow) and the close is above both, -1 the opposite, else 0. |  |
| `ElderRay` | Elder Ray: bull power = high - EMA(13), bear power = low - EMA(13). |  |
| `HalfTrend` | Half Trend (Alex Orekhov's published logic, simplified): trend flips when the close crosses the running high-low midline of amplitude 2; returns the trend line and direction. |  |
| `HeikinAshi` | Heikin Ashi candles (smoothed open/high/low/close). |  |
| `Ichimoku` | Ichimoku (Hosoda): tenkan 9, kijun 26, spans A/B shifted 26 ahead, chikou 26 back. |  |
| `KST` | Know Sure Thing (Pring): weighted sum of four smoothed ROCs, signal SMA(9). |  |
| `LinRegChannel` | Linear regression channel: the n-bar line +/- k standard deviations of the residual. |  |
| `LinRegSlope` | Linear regression slope of the close over n bars (points per bar). |  |
| `MACD` | MACD (Appel): EMA(12)-EMA(26), signal EMA(9), histogram. |  |
| `MassIndex` | Mass Index (Dorsey): sum of EMA9(range)/EMA9(EMA9(range)) over 25; >27 then <26.5 = reversal bulge. |  |
| `PPO` | Percentage price oscillator: MACD as % of the slow EMA. |  |
| `PSAR` | Parabolic SAR (Wilder): step 0.02, max 0.2; trend +1 / -1. |  |
| `QStick` | QStick (Chande): SMA of close - open; > 0 buying pressure. |  |
| `STC` | Schaff Trend Cycle: a double stochastic of the MACD line, 0-100. |  |
| `Supertrend` | Supertrend: hl2 -/+ mult*ATR bands that flip with the close; direction +1 up / -1 down. |  |
| `TRIX` | TRIX (Hutson): 1-bar % change of a triple EMA, with signal. |  |
| `Vortex` | Vortex (Botes & Siepman): VI+ and VI- from up/down trend movement over n. |  |

## Volatility & bands (18)

| name | what it is | needs volume |
|---|---|---|
| `ADR` | Average Day Range: mean of (high - low) over n bars. |  |
| `ATR` | Average True Range (Wilder), Wilder smoothing. |  |
| `ATRBands` | ATR bands: close +/- k x ATR. |  |
| `Bollinger` | Bollinger Bands (Bollinger): SMA(20) +/- 2 population std devs, %B and bandwidth. |  |
| `ChaikinVolatility` | Chaikin Volatility: % change over n of the EMA(10) of the high-low range. |  |
| `Donchian` | Donchian Channels: n-bar highest high / lowest low and the midline. |  |
| `GarmanKlassVolatility` | Garman-Klass volatility: uses open, high, low and close, annualised %. |  |
| `HistoricalVolatility` | Historical volatility: annualised std dev of log returns (bars_per_year: 252 daily, 252*375 1-min). |  |
| `Keltner` | Keltner Channels (Raschke form): EMA(20) +/- 2 x ATR(10). |  |
| `NATR` | Normalised ATR: ATR as % of the close. |  |
| `ParkinsonVolatility` | Parkinson volatility: from the high-low range, annualised %. |  |
| `RVI` | Relative Volatility Index (Dorsey): RSI computed on the std dev instead of price change. |  |
| `RogersSatchellVolatility` | Rogers-Satchell volatility: drift-independent OHLC estimator, annualised %. |  |
| `SqueezeRatio` | Bollinger/Keltner squeeze ratio: Bollinger width / Keltner width (< 1 = squeeze). |  |
| `StdDev` | Standard deviation of the close over n (population). |  |
| `TrueRange` | True range: the largest of high-low, |high-prev close|, |low-prev close|. |  |
| `UlcerIndex` | Ulcer Index (Martin): RMS of the % drawdown from the n-bar high. |  |
| `YangZhangVolatility` | Yang-Zhang volatility: overnight + open-close + Rogers-Satchell parts, annualised %. |  |

## Volume (19)

| name | what it is | needs volume |
|---|---|---|
| `ADLine` | Accumulation/Distribution line (Chaikin): cumulative close-location value x volume. | yes |
| `CMF` | Chaikin Money Flow: sum(CLV x volume)/sum(volume) over n. | yes |
| `ChaikinOscillator` | Chaikin Oscillator: EMA(3) - EMA(10) of the A/D line. | yes |
| `EOM` | Ease of Movement (Arms): midpoint move / (volume / range), smoothed. | yes |
| `ForceIndex` | Force Index (Elder): EMA(13) of price change x volume. | yes |
| `Klinger` | Klinger Volume Oscillator: EMA(34) - EMA(55) of signed volume force, signal EMA(13). | yes |
| `MFI` | Money Flow Index (Quong & Soudack): volume-weighted RSI of the typical price. | yes |
| `NVI` | Negative Volume Index (Fosback): moves only on bars where volume fell. | yes |
| `OBV` | On-Balance Volume (Granville): volume added on up closes, subtracted on down closes. | yes |
| `PVI` | Positive Volume Index: moves only on bars where volume rose. | yes |
| `PVO` | Percentage Volume Oscillator: MACD applied to volume, in %. | yes |
| `RVOL` | Relative volume: volume / its n-bar average. | yes |
| `TwiggsMoneyFlow` | Twiggs Money Flow: CMF with true range and Wilder smoothing. | yes |
| `VPT` | Volume Price Trend: cumulative volume x % change. | yes |
| `VWAP` | Session VWAP with +/- 1 and 2 standard-deviation bands, reset each day. | yes |
| `VZO` | Volume Zone Oscillator (Khalil): EMA of signed volume / EMA of volume, %. | yes |
| `VolumeOscillator` | Volume oscillator: % difference of fast and slow volume EMAs. | yes |
| `VolumeProfile` | Volume profile: volume per price bin over the frame, with the point of control and 70% value area. | yes |
| `VolumeZ` | Volume z-score: how unusual this bar's volume is vs the last n. | yes |
