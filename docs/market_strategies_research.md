# Strategies traders use in BTC, BTC options and gold: research notes

These notes support the `crypto/` and `forex/` study. They were gathered by web search. Many primary pages were blocked, so the figures come from abstracts and search summaries: treat every Sharpe below as gross and in-sample unless it says otherwise.

**Small accounts ($100 per market):**

- **Deribit options:** the minimum is 0.1 BTC, so option strategies can only be backtested or paper-traded at this size.
- **Gold:** the smallest spot lot is 1 oz, about $4k, so sizing needs a fractional CFD or a gold token.
- **BTC spot and perpetual futures:** can be sized properly.

## Tier 1: strong evidence and robust

### 1. BTC trend following (time-series momentum) with volatility targeting

- **Rules:** long/flat when price is above its 50–200-day average, or when the 1–12-month return is positive. An ensemble of several lookbacks works best. Size = target volatility (20–40%) ÷ realised volatility (20–60 days), rebalanced daily or weekly.
- **Evidence:**
  - Moskowitz, Ooi & Pedersen (2012), https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2089463
  - Liu & Tsyvinski, https://www.nber.org/system/files/working_papers/w24877/w24877.pdf
  - Grayscale, 50-day average, Sharpe 1.9 vs 1.3 for buy-and-hold over 2012–2023: https://research.grayscale.com/reports/the-trend-is-your-friend-managing-bitcoins-volatility-with-momentum-signals
  - Ensemble for 2015–2025, Sharpe about 1.6 with a 19% max drawdown: https://paperswithbacktest.com/strategies/quantitative-evaluation-of-volatility-adaptive-trend-following-models-in-cryptocurrency-markets
  - Volatility scaling does most of the drawdown reduction.
- **Fails in:** choppy ranges and V-shaped reversals.

### 2. Gold trend following with volatility targeting

- **Rules:** 12-month sign or 50/200-day average, sized to 10–15% volatility.
- **Evidence:** part of the Moskowitz et al. universe. Standalone Sharpe is modest, around 0.3–0.5.
- **Fails in:** long sideways periods.

### 3. BTC volatility risk premium: sell delta-hedged straddles

- **Rules:** sell 7–30-day ATM straddles or 25-delta strangles. Delta-hedge in bands (for example every 2.5% move). Sell only when DVOL is 5–10 points above 30-day realised volatility. Skip when the term structure is inverted or when funding and open interest are extreme.
- **Evidence:**
  - Alexander & Imeraj, https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3383734
  - Sepp & Lucic, https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4606748
  - Deribit Insights four-year backtest, https://insights.deribit.com/industry/bitcoin-options-finding-edge-in-four-years-of-volatility-regimes/
  - Regime dependence: https://arxiv.org/pdf/2410.15195
- **Fails in:** gap crashes and upside squeezes. Always charge 1–2 volatility points of slippage.

## Tier 2: good evidence, but fragile or hard to execute

### 4. Cash-and-carry / funding carry

- **Rules:** long spot + short perpetual, only when annualised funding is above 10–15%.
- **Evidence:**
  - BIS WP 1087, https://www.bis.org/publications/working-paper-1087-crypto-carry
  - One study finds negative Sharpe after costs: https://www.sciencedirect.com/science/article/pii/S2096720925000818
- **Caveat:** the carry has compressed since the ETFs launched.

### 5. Intraday seasonality

- **Pattern:** 22–00 UTC shows the best mean return (https://quantpedia.com/strategies/intraday-seasonality-in-bitcoin).
- **Caveat:** after the ETFs, hour of day predicts volume and volatility, not direction.

### 6. Covered calls / cash-secured puts

- **Evidence:** Anchorage, https://www.anchorage.com/research/synthetic-yield-on-bitcoin-implementation-discipline-and-performance-boundaries-of-systematic-covered-call-writing; CryptoVol, https://www.cryptovol.io/blog/btc-covered-call-backtest
- **Caveat:** caps upside in strong rallies.

### 7. Gold volatility selling (GVZ)

- **Evidence:** short variance on gold, Sharpe 0.71 (https://arxiv.org/pdf/1409.7720); BIS WP 619.
- **Caveat:** gold's skew often favours calls, so the risk is sharp upside spikes.

## Tier 3: use as filters only

- **Funding / open-interest extremes:** Glassnode commentary.
- **Liquidation cascades:** https://arxiv.org/abs/2607.27070, descriptive only.
- **25-delta skew signals:** no published Sharpe.
- **Term-structure inversion:** a stress filter.
- **Long volatility / event volatility / gamma scalping:** negative expected value unless implied volatility is below realised.
- **Coinbase premium:** vendor claims only.
- **Gold vs real yields and the dollar:** the link broke after 2022 (https://www.rbcwealthmanagement.com/en-us/insights/golds-regime-change).
- **Gold/silver ratio mean reversion:** likely overfit (https://papers.ssrn.com/sol3/papers.cfm?abstract_id=5710242).
- **COT positioning:** weak.

## Tier 4: weak or broken

- **Gold seasonality:** unstable.
- **Fear & Greed contrarian:** overlaps with momentum.
- **BTC weekend effect:** affects volatility, not returns.
- **CME gap fill:** CME has traded 24/7 since May 2026.
- **Halving cycle:** four events, no statistical power.

## What the study tests from this

- **BTC:** trend with volatility targeting, with crowding and term-structure filters; the short-volatility straddle, unhedged and band-hedged, with the IV−RV and term-structure filters; funding carry.
- **Gold:** trend with volatility targeting and a real-yield/dollar overlay; the GVZ-priced straddle.

Rules are chosen on the development year and judged on the last three months.
