# The indicator's source (Boss, 21 Oct 2026)

Pine v6, "Mo3ty CCI Signals", as Boss sent it. The divergence block was summarised in the request (the full source
also plots the CCI with lines at +200 / 0 / -200 and has alert conditions on the BUY / SELL signals).

```
//@version=6
indicator("Mo3ty CCI Signals", shorttitle="Mo3ty CCI", overlay=false, max_lines_count=200, max_labels_count=200)
cciLength  = input.int(25, "CCI Period", minval=1)
upperLevel = input.float(200.0, "Overbought Level")
lowerLevel = input.float(-200.0, "Oversold Level")
src        = input.source(hlc3, "CCI Source")
cci = ta.cci(src, cciLength)
showSignals = input.bool(true, "Show Buy / Sell Signals")
usePulse = input.bool(false, "Enable Volatility Pulse Filter")
atrLength = input.int(14, "ATR Period", minval=1)
atrAvgLength = input.int(20, "ATR Average Period", minval=1)
minBodyATR = input.float(0.25, "Minimum Candle Body / ATR", minval=0.0, step=0.05)
requireATRExpansion = input.bool(true, "Require ATR Expansion")
atrValue = ta.atr(atrLength)
atrAverage = ta.sma(atrValue, atrAvgLength)
bodySize = math.abs(close - open)
atrExpansionOK = not requireATRExpansion or atrValue > atrAverage
bodyStrengthOK = bodySize >= atrValue * minBodyATR
pulseOK = not usePulse or (atrExpansionOK and bodyStrengthOK)
// SELL: CCI was at/above overbought level, then closes below it. BUY: CCI was at/below oversold, then closes above it.
sellSetup = cci[1] >= upperLevel and cci < upperLevel
buySetup = cci[1] <= lowerLevel and cci > lowerLevel
buySignal = barstate.isconfirmed and buySetup and pulseOK
sellSignal = barstate.isconfirmed and sellSetup and pulseOK
// Divergence: confirmed pivots (left 5, right 5) on price lows/highs; CCI at the pivot = cci[rightBars]
// Bullish div: new price pivot low < previous pivot low AND CCI at it > previous CCI AND (either CCI <= -200)
// Bearish div: new price pivot high > previous pivot high AND CCI at it < previous CCI AND (either CCI >= +200)
// Divergences are drawn only (labels "BULL"/"BEAR" placed back at the pivot bar, known only rightBars=5 bars later).
```
