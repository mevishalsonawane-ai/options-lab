# Quantitative Finance for Physicists: An Introduction - Anatoly B. Schmidt (2005)

Source: quantitative-finance-for-physicists-an-introduction.txt, 179 pages (Elsevier Academic Press; 12 chapters,
comments, references, answers, index), read in full on 25 Oct 2026 (text layer; formulas partly garbled and the
figures show only axis numbers - see "Could not read").
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A short crash course written by a physicist turned Wall Street data analyst, for scientists moving into finance.
Chapters 3-7 review the maths: distributions, stochastic calculus, time series, fractals and chaos.
Chapter 8 is the "econophysics" core: fat tails, power laws, scaling and multifractals in return data.
Chapters 9-11 are standard mathematical finance: Black-Scholes, portfolio theory and VaR / expected tail loss.
Chapter 12 builds agent-based market models, including the author's own "why technical trading may work" model.

There is no market data study of its own beyond SPY weekly returns (1996-2003) in exercises, no backtest and no
trading rule offered for profit; the author says in his own notes that he cannot answer "how do we make money with
this" for the academic half. Its value to us is the list of **stylised facts** of returns (fat tails, volatility
clustering, no memory in signs), the econometrics toolbox (AR/ARMA, GARCH/EWMA, unit roots, cointegration, Granger
causality, Hurst exponent), the risk-measure section (VaR vs ETL, historical simulation, Kupiec back-test) and a
warning about data snooping. Option pricing is a textbook summary; the desk side of volatility is in
`research/books/bennett-trading-volatility.md` and is not repeated here.

Our data, for the mapping: NIFTY weekly / BANKNIFTY monthly index options (European, cash-settled), a 1-minute
rolling chain ATM +-10 strikes with IV and OI (last prices, no bid/ask), index spot and futures bars, India VIX
daily, daily bars since 2006, MCX futures and options, Delta Exchange crypto options. Costs: about Rs 70 (NIFTY) /
Rs 76 (BANKNIFTY) per option round trip (FINDINGS.md, R102 public facts).

## The stylised facts, checked against our own data (the main use of this book)

| stylised fact (book, Ch. 3, 5.3, 8) | what the book says | what our data say | verdict |
|---|---|---|---|
| **Fat tails** | Returns have excess kurtosis; SPY weekly 1996-2003 lies between the normal and Cauchy curves (Fig. 3.1). Stanley's group: tails follow a power law with index about 3 ("inverse cubic") for horizons under 4 days on the S&P 500 (1984-1996) and under 16 days on several thousand US stocks; beyond that the distribution drifts slowly toward normal. | R102 (DEEP_NIFTY_BANKNIFTY.md 2.1), daily close-to-close 2006 - 14 Jul 2026, n 5,087: kurtosis **14.1** (NIFTY) and **8.5** (BANKNIFTY) against 3 for a normal curve; worst days -12.98% / -16.73% (23 Mar 2020); 1st/99th percentiles -3.77 / +3.60% (NIFTY). DEEP_OPTIONS_SELLING.md section 5 uses the same numbers for tail risk. | **Confirmed** on NSE daily data. We have NOT estimated a tail index (no power-law / Hill fit) and NOT checked whether kurtosis falls at weekly or monthly horizons: both are cheap descriptive checks. |
| **Volatility clustering** | Large moves follow large moves of either sign: squared returns are autocorrelated (ARCH/GARCH). Outliers can hide this by depressing the autocorrelation; remove them and it shows. | R88 (HUNT_R88_WHAT_MOVES_THE_CANDLE.md, 5 indices + 10 MCX books, holdout Jun-Oct 2026): after a big candle the next is **1.5x bigger** (indices), 1.65x (MCX), held in 10 of 10 index and 20 of 20 MCX cells; size autocorrelation 0.30-0.80; 225 of 305 size findings held. R16 (HUNT_R16_PREDICTABILITY.md): the next 30/60-minute range is predictable, holdout error 18-20% below the time-of-day average (q 0.003); R41 neural nets do better still on size (R2 0.432 vs trees 0.382). R11 calls its minute-level finding "volatility clustering" and found it does not pay an option buyer after costs. | **Strongly confirmed**, at every horizon we tested (1-minute to daily). It is the one thing we can predict. |
| **No return autocorrelation** (weak-form efficiency) | Signs of returns are close to independent; Lo and MacKinlay rejected the random walk for weekly US returns 1962-1986, but 1986-1996 looked closer to random, plausibly because statistical arbitrage traded the inefficiency away. | R88: lag-1 autocorrelation of candle returns -0.04 to +0.06; 0 of 8 direction findings held. R102 2.5: lag-1 autocorrelation +0.006 at 5 minutes (n 167,082), "follows previous sign" 48.4%; R102 4.2: 70 pre-registered sign rules, 1 confirmed and it was the cash index's stale-price artefact (+0.11 bps on 853,718 pairs vs 1.25-1.54 bps cost). The 20-year daily momentum (+5.8 / +12.4 bps a day) faded from +28 bps (BANKNIFTY 2006-2008) to +2 to +6 (2018-2026) and reversed in the holdout (-9.4 / -24.6 bps, n 65). | **Confirmed**, including the book's "inefficiencies fade once known" pattern (our daily momentum fading over 20 years is the same shape as Lo-MacKinlay's two periods). |
| **Asymmetry / leverage** (EGARCH, Ch. 5.3) | Falls raise volatility more than rises. | R88: VIX falls on up candles and rises on down candles (alone 17-36% of the variance). R102: put skew +2.56 / +2.11 IV points on 91% / 90% of days. Daily skew itself is near zero (-0.04 NIFTY, +0.07 BANKNIFTY). | Consistent (through VIX and skew), not tested as a GARCH fit. |

What this means for us, in one line: the book's three facts are exactly our research verdict - size is forecastable
(clustering), direction is not (no autocorrelation), and tails are much fatter than Black-Scholes assumes (kurtosis
8.5-14.1), so short premium carries gap risk that a normal model under-prices.

## Methods, rules and setups (chapter by chapter)

### Ch. 1 - Introduction
Plan of the book; parts called "applied" (econometrics, mathematical finance) and "academic" (econophysics,
agent-based models). Exercises use Excel and free Yahoo data.

### Ch. 2 - Financial markets
Bid, ask, market, limit and stop orders; liquidity as how far a market order moves the price; ticks arrive at
irregular times, so data are put on regular grids or on an "operational time" that drops non-trading hours; seasonal
effects handled with dummy variables. Simple vs log returns (log returns add over time but not across a portfolio).
Discounted dividends and the constant-growth price (very sensitive when the required return nears the growth rate),
and a collapsing "rational bubble" model. Law of one price and a triangular FX arbitrage worked example that vanishes
once the finite size at the best price is used up. The efficient-market hypothesis in weak, semi-strong and strong
forms; Lo-MacKinlay; behavioural finance (Kahneman-Tversky: people take a sure gain but gamble to avoid a sure loss).
Calendar effects ("Dogs of the Dow", "January effect") are judged unreliable; whether simple chart patterns beat the
market is called an open academic question; statistical arbitrage edges are said to be fading.
- Our research: calendar effects failed for us too (R102 2.6: every weekday BH q 0.43 or worse); chart patterns
  failed (R71), see "How it relates".

### Ch. 3 - Probability distributions
Moments, skew, kurtosis (normal = 3; excess kurtosis = fat tails and a peaked centre); median is the better centre for
skewed data. Uniform, binomial, Poisson, normal (with the Box-Muller recipe), lognormal, Cauchy. Extreme-value theory
(Gumbel thin tails, Frechet fat tails, Weibull bounded). Stable (Levy) laws: power-law tails, infinite variance
below index 2; the truncated Levy flight keeps finite variance.

### 1. Extreme-value and fat-tail description of returns (Ch. 3.2-3.3, 8.2)
- What it computes: how often moves of k standard deviations occur, via kurtosis, a fitted tail index, or an
  extreme-value (Frechet) fit to the largest moves.
- Inputs: a return series at a fixed horizon.
- How we could use it: size stops and position limits on the real tail, not on a normal curve; estimate a tail index
  for NIFTY / BANKNIFTY 1-minute to daily returns and see whether, as the book reports for the US, tails thin toward
  normal beyond a few days. Feeds option-selling risk (DEEP_OPTIONS_SELLING.md section 5).
- Signal source: underlying price (descriptive); maps to NSE yes, MCX yes, crypto yes.
- Our research: kurtosis measured (14.1 / 8.5, n 5,087); tail index not estimated by us.

### Ch. 4 - Stochastic processes (theory)
Markov processes, Chapman-Kolmogorov and Fokker-Planck equations, Brownian motion three ways (diffusion equation,
Langevin, random-walk limit), geometric Brownian motion, Ito's lemma, Ito vs Stratonovich integrals, martingales
(asset prices are not martingales because of the risk premium). Exercises: simulate four years of daily GBM prices
(the Wiener step is a normal draw times the square root of the time step); solve the Ornstein-Uhlenbeck mean-reverting
process.

### 2. Monte Carlo price paths (Ch. 4.2, exercise 4.1)
- What it computes: simulated price paths from a drift and volatility.
- How we could use it: only as a null model (random walk with our measured vol) to see how often a "pattern" appears
  by chance; GBM has thin tails, so for risk use a fat-tailed or GARCH generator instead (point 1).
- Signal source: none (simulation). Our research: our studies use day-block bootstrap (e.g. R88: 1,000 resamples)
  and random-entry "twins" (R72) as nulls, which keep the real fat tails; nothing to add.

### Ch. 5 - Time series analysis
AR(p): mean-reverting when the lag weight is below 1, a random walk when it equals 1; differencing, unit roots,
ARMA / ARIMA, MA invertibility, the autocorrelation function, forecasts as conditional expectations (least squares,
maximum likelihood). Deterministic vs stochastic trend look alike on a chart but the stochastic one can wander far;
the Dickey-Fuller test separates them. Seasonal dummies cost one parameter per season.

### 3. ARMA / autocorrelation check of returns (Ch. 5.1)
- What it computes: whether past returns forecast the next one (lag-k autocorrelation, AR fit).
- Signal source: underlying price; maps to all markets.
- Our research: done many times; returns have no usable memory (R88 lag-1 -0.04 to +0.06; R102 2.5 and 4.2; R16
  direction AUC 0.509-0.536). Already rejected as a direction signal.

### 4. GARCH(1,1) / EWMA volatility forecast (Ch. 5.3)
- What it computes: today's variance as a constant plus a weight on yesterday's squared shock plus a weight on
  yesterday's variance. Long-run variance = constant / (1 - a - b); stationary only if a + b < 1; a k-step forecast
  decays toward the long-run level at the rate (a + b) to the power k. With a + b = 1 and no constant it becomes the
  EWMA (RiskMetrics-style) estimator. EGARCH adds the sign of the shock so falls raise vol more than rises.
- Inputs: a return series (5-minute, hourly or daily).
- How we could use it: as a size forecast for stops, targets, lot size and "is this premium rich?" (compare a GARCH
  forecast of NIFTY's next 21 days with India VIX). Volatility-targeted sizing (lower size when forecast vol is high)
  is a risk-LOWERING change and would start in shadow under CLAUDE.md.
- Signal source: underlying price; maps to NSE yes, MCX yes, crypto yes.
- Our research: not fitted by name. R16 found a 4-input linear range formula (usual range for the time, last 30-min
  range, last 60-min range, VIX) that keeps most of the skill (13-14% better than time-of-day in the holdout), which
  is a GARCH-like rule in all but name; R41's neural nets add a little more. R102: VIX over-states NIFTY's next 21
  days on 81% of days (median ratio 1.28, 4,507 days). R74 lists volatility-targeted sizing as "not tried for the
  index arms". A GARCH(1,1) baseline vs VIX vs R16's formula is a cheap missing comparison.

### 5. Spurious regression and data snooping (Ch. 5.4)
- What it says: regressing one trending series on another gives false "relationships" (the book's example: butter
  production in Bangladesh as the best UN-database predictor of the S&P 500); a statistic built on the same data it
  is tested on can look significant by chance (data snooping, citing Sullivan-Timmermann-White's bootstrap).
- Our research: this is why every study of ours is pre-registered, walk-forward, Benjamini-Hochberg corrected and read
  once on a locked holdout (CLAUDE.md). Agrees.

### 6. VAR, Granger causality and cointegration (Ch. 5.4)
- What it computes: whether lags of series Y improve the forecast of X (Granger), and whether two random-walk prices
  have a stationary combination (cointegration, the statistical base of pairs trading).
- How we could use it: lead-lag between BANKNIFTY and NIFTY, futures vs spot, GOLD vs SILVER.
- Signal source: underlying price (two underlyings); maps to NSE (index pairs), MCX (metal pairs), crypto (BTC/ETH).
- Our research: R72 cross-index (HUNT_R72_CROSS_INDEX.md): only the breadth filter on Donchian passed; the laggard
  catch-up idea (C2, pairs-like) lost (-Rs 1,00,746 final, 613 trades). R94 (FINDINGS.md): GOLD/SILVER ratio
  reversion lost in both windows (design -Rs 6.3 lakh, 68 trades). R11: derivatives lead cash by about one minute,
  not tradeable after costs. A formal cointegration test was not run by us.

### Ch. 6 - Fractals
Self-affinity (time and price scale differently), box-counting dimension, fractional Brownian motion and the Hurst
exponent H: above 1/2 a series is persistent (trends), below 1/2 anti-persistent (mean-reverting); fractal dimension =
2 - H. Rescaled-range (R/S) analysis estimates H but is fooled by short-range memory. Multifractals: binomial and
lognormal cascades, the multifractal spectrum, and the scaling function estimated from a log-log plot of summed
absolute moves against the time step.

### 7. Hurst exponent / R/S as a trend-or-revert gauge (Ch. 6.1)
- What it computes: H from how the range of cumulative deviations grows with window length.
- How we could use it: descriptive regime check (trending vs mean-reverting) per index and horizon.
- Signal source: underlying price; maps to all.
- Our research: not tested by us under that name. Our horizon autocorrelation table (R102 2.5) already answers the
  same question with a cleaner statistic: slight reversal at 5-15 minutes (48% follow), slight continuation at 1
  hour to 1 day, none tradeable. The book itself warns R/S is biased by short memory, so expect H near 1/2.

### Ch. 7 - Nonlinear dynamical systems (theory)
Logistic map (period doubling at about 3, 3.45, chaos from 3.57; two starts 0.400 and 0.405 diverge in 10 steps),
damped and Van der Pol oscillators, limit cycles, the Lorenz model, routes to chaos, Lyapunov exponent and correlation
dimension estimated from a series. Main points for us: little if any evidence of low-dimensional chaos has been found
in financial series, and nonlinear discrete models (including agent-based ones) can create chaos as an artefact.

### Ch. 8 - Scaling in financial time series
The two stylised facts (fat tails, volatility clustering); no model of price is accepted by all. Problems: index
composition changes, FX data that are bank quotes not trades, and data granularity (coarse data miss crashes, fine
data are noisy). Stable laws fell out of favour (infinite variance); truncated Levy flights; the "inverse cubic" tail
(index about 3) from Stanley's group, explained by power laws in trade volume (index about 1.5) and trade counts
(about 3.4) and Zipf-sized funds trading optimally. Sornette's log-periodic crash precursors (some success, disputed).
Then the caveats: LeBaron reproduced a power-law tail index of 2.98 to 3.33 (1-20 day aggregation, Dow 1900-2000)
from nothing but normal returns whose volatility mixes three time scales (half-lives about 2.7 years, 2.5 weeks and one
day), so a "power law" can be a by-product of stochastic volatility; Lux warns about spurious scaling in finite
samples and log-log regressions. Multifractal "trading time" models are said to beat GARCH on some series.

### 8. Volatility as a mixture of time scales (Ch. 8.3)
- What it computes: volatility = slow component (months-years) + medium (weeks) + fast (a day); returns are normal
  given the volatility, yet the mix has fat tails.
- How we could use it: a size model with three memories (yesterday's range, last weeks' realised vol, the long-run
  level), close to what R16 and R88 found works (last candle, last hour, yesterday, VIX).
- Signal source: underlying price; maps to all. Our research: consistent with R88's size drivers (last candle 1.5x,
  last hour 1.4x, yesterday 1.3x, high VIX 1.3x); not fitted as such.

### Ch. 9 - Option pricing
Six inputs to an option price (spot, strike, time, volatility, rate, dividends), payoffs of the four basic positions,
put-call parity (with dividends), the one-step and multi-step binomial tree (hedge ratio, risk-neutral odds, backward
induction, up factor exp(sigma * sqrt(dt))), Black-Scholes assumptions and formulas (drift drops out), implied
volatility by numerical inversion, the Greeks (call delta N(d1), put delta N(d1) - 1; gamma; theta; vega; rho),
delta-neutral portfolios needing dynamic re-hedging, gamma-neutralising with other options, and the smile with its
usual fixes (term structure, other distributions, jumps). An appendix argues, as the author's own paradox, that only
the total hedging expense, not the option price, is pinned down by the no-arbitrage argument (theory only).

### 9. Option leverage and protective put (Ch. 9.2)
- Book's example: IBM at 83.95, a one-month 85 call at 2.55 breaks even at 87.55; at 90 the call returns 96% against
  7.2% for the stock, but if the stock stays put the buyer loses 100% of the premium. A protective 80 put at 1.50 caps
  the loss at 5.45 instead of 8.95.
- Signal source: none (payoff arithmetic); maps to NSE yes.
- Our research: the asymmetry is why option buying needs a large, timely move; R16/R88 say direction is a coin, and
  costs plus decay sink most buying rules (FINDINGS.md; R47, R48). Context only.

### 10. Black-Scholes pricing, implied vol and Greeks (Ch. 9.3-9.4)
- What it computes: fair value under GBM, the IV the market price implies, and sensitivities.
- How we could use it: already used (DEEP_OPTIONS_SELLING.md reprices stale wings with Black-Scholes; R102's straddle
  and skew numbers). The book's own list of BS failures (fat tails, smile) matches our kurtosis 14.1 and the put skew
  on 91% of days.
- Signal source: volatility / options structure; maps to NSE index options yes (European), MCX options, crypto options.
- Our research: see bennett-trading-volatility.md and R103 (0 of 24 selling tests pass; the passes were stale prices).

### Ch. 10 - Portfolio management
Risk-free plus risky asset (the excess-return-per-risk line), two-asset minimum-variance weights (adding a riskier
uncorrelated asset can cut risk: in the example the mix has sigma about 0.134, below the safer asset's 0.15, with a
higher return of 0.12), the tangency portfolio and leverage beyond it, CAPM (beta, Sharpe ratio; correlations rise in
bear markets, results depend on the market proxy), APT multi-factor model. Section 10.4 lists market-neutral hedge
fund styles: pairs trading (long the consistently stronger stock of an industry, short the weaker), convertible,
fixed-income, mortgage and merger arbitrage, equity hedge / index arbitrage (index future vs its stocks), equity
market-neutral, statistical arbitrage and relative value, with the remark that the more people chase an
inefficiency the faster it disappears. No rules, no numbers.

### 11. Diversifying uncorrelated arms (Ch. 10.1)
- What it computes: the weight split between two return streams that minimises variance (inverse-variance when they
  are uncorrelated).
- How we could use it: splitting capital between paper arms with low correlation; the book's warning that
  correlations rise in bad markets applies.
- Signal source: none (allocation); maps to all.
- Our research: R72 found Breadth Donchian has daily correlation 0.80 with Donchian, so it should replace, not add
  to, Donchian (77% shared trades). No formal arm allocation study by us.

### 12. Pairs trading / index arbitrage (Ch. 10.4)
- Entry: long the stronger, short the weaker stock of the same industry (not mechanical as stated: no threshold, no
  exit). Index arbitrage: index future against its constituent basket.
- Signal source: underlying price (two underlyings); NSE index-vs-futures basis is our H39 area; MCX metal pairs.
- Mechanical? no (no entry or exit levels given).
- Our research: R94 GOLD/SILVER ratio reversion lost; R72 C2 laggard catch-up lost; see method 6.

### Ch. 11 - Market risk measurement
Kinds of risk (market, liquidity, credit, operational). VaR from a normal P/L (z = -1.64 at 95%, -2.33 at 99%);
VaR's faults: model-sensitive, silent beyond the threshold, can punish diversification, and not sub-additive unless
P/L is normal. Coherent risk measures; expected tail loss (ETL, also called expected shortfall or conditional VaR) =
average loss beyond VaR, always larger than VaR (normal case z = -2.06 at 95%, -2.66 at 99%).

### 13. Historical-simulation VaR and ETL, with a volatility rescale (Ch. 11.2)
- What it computes: with 100 daily P/L values at 95%, VaR is the sixth-worst value and ETL the average of the five
  worst (in general VaR is the [N(1 - a) + 1]-th lowest and ETL the mean of the N(1 - a) lowest). Old data are handled
  with a moving window or exponential weights; the Hull-White variant rescales each past return by today's volatility
  forecast divided by the forecast on that day, so a calm history does not under-state risk in a storm. Parametric
  VaR scales with the square root of the horizon only under independent normal returns. Back-test a VaR model with
  the Kupiec test: the count of days beyond VaR should behave like a binomial count (weak on small samples).
- Inputs: the daily P/L of an arm or the whole paper book.
- How we could use it: report ETL next to max drawdown for every arm; check whether one bad day could break Boss's
  -Rs 40k drawdown / -Rs 13k month stop; for option selling, ETL is the honest number (the naked 09:20 BANKNIFTY
  straddle's worst day was -Rs 65,483, DEEP_OPTIONS_SELLING.md point 8).
- Signal source: none (risk measure on P/L); maps to all.
- Our research: not tested by us. Our studies report worst day, max drawdown and green-day share, never VaR/ETL.

### Ch. 12 - Agent-based models of markets
Minority game and El Farol bar; Beja-Goldman (equilibrium becomes unstable as trend followers grow). Adaptive
equilibrium models (Brock-Hommes): each agent's share in the risky asset under log utility is (expected excess
return) / (variance) - the growth-optimal (Kelly) fraction - or the same divided by a risk-aversion constant under
CARA utility; agents are fundamentalists, momentum traders or contrarians and switch strategy by past fitness minus
cost. Non-equilibrium models (Lux): price changes in proportion to excess demand; contagion among chartists produces
fat tails, clustering and power-law scaling. The author's observable-variables model (buyers vs sellers only):
stable when chartist weight times price sensitivity is small; more chartist weight means more volatility; beyond the
limit, cycles or a crash. Section 12.4.3 adds "technical traders" who buy when price turns up after a dip and sell
when it turns down after a top; with enough volume (a share of 0.005 already shows) they amplify swings and drag
regular chartists along, so the rule can be self-fulfilling inside the model. Section 12.4.4: a new market can die if
unmatched traders are too impatient. The author also warns that agent-based models are arbitrary (agent types,
parameters) and their chaos may be an artefact.

### 14. Growth-optimal (Kelly-type) fraction (Ch. 12.2)
- What it computes: fraction of capital at risk = expected excess return / variance of return (log utility).
- How we could use it: an upper bound on size for an arm with a measured edge; use a fraction of it given estimation
  error. Size changes on LIVE may only lower risk (CLAUDE.md).
- Signal source: none (sizing); maps to all.
- Our research: our option-buying hunts used fixed lots by Boss's choice ("no Kelly, no growing size", HUNT_FINAL.md,
  HUNT_H36.md, which notes Kelly or growing size reached Rs 300-1,100 a day). No Kelly study of the live arms.

### 15. Three-bar turn ("buy on dips, sell on tops") from the technical-trader model (Ch. 12.4.3)
- Entry: buy when the latest price is above the previous one and the previous one was below the one before (a turn up
  after a dip); sell (go short) on the mirror turn down after a top.
- Stop: not stated. Target / exit: the opposite signal (the model holds +1 / -1 / 0 each step).
- Filters: none. Market and timeframe: a simulated market in abstract time steps, not real data.
- Signal source: underlying price; maps to NSE index options (buy ATM CE / PE on the turn), MCX, crypto (would need a
  stop and a profit lock).
- Mechanical? yes, but it is a model ingredient: the book shows it moves prices in a simulation where technical
  traders are big enough, not that it makes money in a real market.
- Our research: the nearest test is our direction work: at 5 minutes the next bar follows the previous bar's sign
  48.4% of the time (R102 2.5, n 167,082), R88 0 of 8 direction findings held, R87 candle shapes and R71 patterns
  failed. Not tested in this exact form; prior is very weak. Listed in the rules file for completeness.

## Performance claims
- No trading profit claims. The only "returns" are worked examples: the IBM call returning 96% vs 7.2% for the
  stock (or -100%), the FX triangle earning about $840 on $100,000 before costs, a put-call parity arbitrage of $2.50.
- Empirical claims are cited from others, with no new data: Lo-MacKinlay's random-walk rejection (1962-1986) and its
  weakening (1986-1996); Stanley's tail index about 3; LeBaron's 2.98-3.33 from a volatility mixture; multifractal
  models beating GARCH on some series. Each is described, none re-tested in the book.
- "Why technical trading may be successful" is a simulation result (Fig. 12.3), not market evidence.

## Risk, sizing and psychology
- Risk: VaR is popular but blind beyond its threshold and not sub-additive for fat tails; prefer ETL. Use rolling or
  volatility-rescaled history. Liquidity risk is the finite size at the best price.
- Sizing: log-utility / Kelly-type share = excess return / variance; CARA divides by risk aversion. Diversify across
  uncorrelated streams, knowing correlations climb in sell-offs.
- Psychology: loss aversion (take sure gains, gamble to avoid sure losses), overconfidence, over-reaction, herding
  (mimetic contagion) as drivers of bubbles and clustering. Markets may fall on expected good news that was priced in.

## How it relates to our research
- **Agrees (stylised facts):** fat tails (R102: kurtosis 14.1 / 8.5, n 5,087), volatility clustering (R88 1.5x after a
  big candle, 225 of 305 size findings held; R16 size predictable, q 0.003; R41), no return memory (R88 lag-1 -0.04
  to +0.06; R102 1 of 70 sign rules, an artefact). See the table above.
- **Agrees (method):** data snooping and spurious regression are the book's warnings and the reason for our
  pre-registration, walk-forward, BH and single-read holdouts.
- **Agrees (inefficiencies fade):** Lo-MacKinlay's weakening random-walk rejection mirrors our BANKNIFTY daily
  momentum falling from +28 bps (2006-2008) to +2 to +6 bps (2018-2026) and reversing in the holdout (R102 4.2).
- **Already rejected by us:** calendar effects (R102 2.6), chart patterns (R71), return autocorrelation as a signal
  (R16, R88, R102), pairs-like cross-index catch-up (R72 C2) and GOLD/SILVER ratio reversion (R94).
- **Tension:** the book's agent-based section suggests technical rules can be self-fulfilling when enough traders use
  them; our data show no such edge in NIFTY/BANKNIFTY direction after costs, except where a breadth filter helped
  Donchian (R72 C1, paper only).
- **Option pricing:** the book's list of Black-Scholes flaws (fat tails, smile) matches DEEP_OPTIONS_SELLING.md's
  tail section and R102's skew; R103's stale-wing lesson goes beyond the book.

## What is new to us
- A **tail index** (Hill / power-law fit) for NIFTY / BANKNIFTY returns by horizon, and a check whether kurtosis drops
  toward 3 at weekly / monthly horizons as the book reports for the US. Descriptive, cheap.
- A **GARCH(1,1) / EWMA baseline** against VIX and R16's 4-input formula for the next-day and next-21-day range; and
  the "outliers hide clustering" caution when reading autocorrelation of squared returns.
- **ETL / expected shortfall and Kupiec-style breach counts** for each paper arm and for Boss's account stops;
  volatility-rescaled historical simulation for option-selling risk.
- A **three-time-scale volatility model** (LeBaron) as a structured version of our size drivers.
- A formal **cointegration / Granger** test for NIFTY-BANKNIFTY, futures-spot and GOLD-SILVER (we tested trading
  rules, not the statistics).
- **Growth-optimal (Kelly-type) fraction** as an upper bound on size for arms with a measured edge (we use fixed lots).

## Could not read
- Formulas came through the text layer partly garbled (symbols such as minus signs, Greek letters and fractions
  dropped); the prose around each was clear, so the meaning was recoverable.
- Figures show only axis numbers and captions: Fig. 3.1 (SPY weekly return histogram vs normal and Cauchy), 5.1,
  6.1-6.3, 7.1-7.9, 9.1, 10.1, 11.1-11.2 and 12.1-12.4. Their captions and surrounding text were read.
- Nothing else; the comments, references, answers to exercises and index were read.

## R104 results (25 Oct 2026)

Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in `research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:

- `book-fmschmidt-01`: tested (R104): IDX: 9207 trades, win 30%, net -Rs 543,959 design, holdout -Rs 141,565 (fail)
