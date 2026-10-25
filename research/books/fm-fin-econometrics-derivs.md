# Financial Econometrics Modeling: Derivatives Pricing, Hedge Funds and Term Structure Models - Greg N. Gregoriou and Razvan Pascalau, editors (2011)

Source: financial-econometrics-modeling-derivatives-pricing.txt, 231 pages (front matter i-xxiii, chapters pp 1-202,
index pp 203-206), read in full on 25 Oct 2026 (text layer, every chapter, references and index; equations and chart
curves came through garbled - see "Could not read").
Book claims are the authors', not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

This is an edited volume (Palgrave Macmillan) of ten academic papers by different authors, not a trading manual. It
contains no trading rule with entry, stop and exit. Part I is "derivatives pricing and hedge funds" (Ch 1-6), Part II
"term structure models" (Ch 7-10). Only Ch 2 touches real option prices; Ch 5 is the only GARCH chapter.

## What it is about
1. Ten separate research papers: hedge-fund risk and Ponzi finance, option-implied distributions, toxic-asset pricing, a fast option-pricing PDE solver, a GARCH variant, emerging-market integration and four interest-rate studies.
2. The option content: an implied binomial tree fitted to S&P 500 index options, turned into a "real-world" density and a pricing kernel.
3. The volatility content: a Fourier-GARCH that lets the long-run variance and the mean drift slowly; it claims most GARCH persistence is a modelling artefact.
4. The econometric toolbox runs through every chapter: Kalman filters, cointegration (linear, Johansen, nonlinear threshold), unit-root tests, spectral lead-lag, ARMA-ARCH, discretised SDEs.
5. All results are in-sample or short out-of-sample academic fits on US/UK/European/emerging data before 2009; none is a cost-adjusted trading test.

## Methods, rules and setups

The brief asked specifically for three things; the honest summary first:
- **Option pricing errors (empirical):** only Ch 2 has market data. Its implied tree priced 198 of 200 S&P 500 calls
  inside the bid-ask spread (10 strikes x 20 months, 1993-1995); this is an in-sample calibration fit, not a forecast.
  Ch 4's "errors" are numerical errors against reference prices, not market errors.
- **GARCH option pricing:** not in this book. Ch 5 is a GARCH volatility model for stock returns, with no option pricing.
- **Implied vs realised volatility:** no chapter compares implied with realised volatility directly. The nearest is Ch 2
  (risk-neutral density from options vs a physical density) and its literature review (Jackwerth found post-1987 index
  puts overpriced relative to realised returns and profitable put-selling strategies). Our own R102 numbers are the
  relevant evidence (see Ch 2 below).

### Ch 1. The operation of hedge funds (Semmler and Chappe, pp 3-34)
Teaches: a literature survey of hedge-fund data biases and performance measures, a toy stochastic wealth model showing
how a fund that promises more than it earns slides into "Ponzi finance" once inflows stop, and a review of US
(Dodd-Frank) and EU (AIFM directive) regulation. The simulation is illustrative only (no calibration). Regulation is
not relevant to us.

#### 1a. Honest performance statistics (practical)
- What it computes: (i) survivorship bias - dead funds drop out of databases (estimates quoted: about 2 percent a year,
  3 percent a year with a 15 percent drop-out rate, 3.74 percent a year); (ii) backfill bias - funds add their good
  history when they join (1.4 percent on TASS 1994-1998; backfilled returns about 4 percent a year higher); (iii) Lo's
  point that annualising a monthly Sharpe ratio by the square root of 12 can over- or under-state it by as much as 65
  percent when returns are serially correlated; (iv) with fat tails and negative skew the Sharpe ratio misleads, so use
  the Sortino ratio (excess return over downside volatility below a minimum acceptable return) and maximum drawdown;
  (v) high serial correlation in monthly returns is a marker of illiquid, smoothed marks (Getmansky, Lo and Makarov).
- Other facts quoted: an equally weighted index of 3,000 funds (1995-2004) earned 12.8 percent pre-fee, split into 3.8
  fees, 3.7 alpha and 5.4 beta (Ibbotson and Cheng); funds fail most in their first years and after a high-volatility
  year (Malkiel and Saha).
- Inputs: a daily or monthly P&L series. How we could use it: Jarvis/Autopilot scorecards should show Sortino and max
  drawdown beside any Sharpe, adjust Sharpe for autocorrelation of daily P&L, and never judge an arm only on survivors
  (arms switched off for losing must stay in the record - our CLAUDE.md "log every idea, failures included" already
  enforces the research side).
- Signal source: none (a measurement method). Maps to: every arm on NSE, MCX and crypto.
- Mechanical? yes.
- Our research: we already report max drawdown, worst day, worst month and day-bootstrap p with BH q (e.g. R103 gates in
  HUNT_R103_OPTIONS_SELLING.md); an autocorrelation-adjusted Sharpe and Sortino are not in our standard tables (not
  tested by us).

### Ch 2. Inferring risk-averse distributions from option prices with implied binomial trees (Arnold, Crack and Schwartz, pp 35-52)
Teaches: the plain Cox-Ross-Rubinstein tree cannot reproduce smiles, skew or fat tails. Rubinstein's implied binomial
tree fits all strikes of one expiry and gives a risk-neutral distribution with varying local volatility. The authors
swap the tree's risk-neutral up-probability for a "risk-averse" one using an assumed risk premium on the index; option
prices do not change, but the tree now describes the real-world distribution. Dividing the two densities gives the
pricing kernel (how much the market pays for a rupee in each future state).

#### 2a. Implied binomial tree / risk-neutral density from a strike strip (practical)
- What it computes: the market's risk-neutral distribution of the index at expiry (and local volatility along the way)
  from one expiry's option prices, arbitrage-free if the quotes are. The related Breeden-Litzenberger route (second
  derivative of call price with respect to strike) is described in the literature review.
- Inputs: about 10 near-the-money call quotes of one expiry with bid and ask, the index (dividend-adjusted), the
  risk-free rate. Data care the authors used: only quotes with bid and ask above $0.50, quotes taken within about 15
  minutes of 11:00, index sampled at the same moment, an arbitrage screen; 200-step tree; 20 usable months of 33.
- Empirical result (the book's only option-pricing-error evidence): of 200 options calibrated, 198 priced strictly inside
  the spread and the other 2 a fraction of a cent below the bid. Example, 19 Jan 1993: ATM implied volatility 11.2
  percent, rate 2.88 percent, 10 strikes all inside the spread. This is in-sample fit quality, not out-of-sample.
- How we could use it: build a daily NIFTY risk-neutral density from the 09:20 or closing chain (ATM +-10 strikes is
  what our lake holds) and compare it with the realised distribution of the next days; skew and tail mass become one
  picture for Jarvis. Strictly context around an underlying signal; also a stale-quote detector (a strike that cannot
  be fitted inside its spread is suspect - R103's stale far wings are exactly this failure).
- Signal source: option chain as context. Maps to: NSE index options yes (European, liquid near ATM); MCX options (options on futures)
  partly (thin strikes); crypto options (Delta India) partly.
- Mechanical? yes (an optimisation), but needs clean simultaneous quotes.
- Our research: not tested by us as a density. R103 / DEEP_OPTIONS_SELLING.md found the six design passes came from far
  wings at stale prices and all lost once repriced with Black-Scholes; R102 (DEEP_NIFTY_BANKNIFTY.md 3.4) measured put
  ATM-5 IV above call ATM+5 IV by a median 2.56 points on NIFTY, put side dearer on 91% of 1,222 days.

#### 2b. Pricing kernel and implied risk aversion (theory with one empirical point)
- What it computes: risk-neutral density divided by real-world density (the pricing kernel) and the implied relative
  risk aversion. With risk premiums of 3.7, 7.5 and 11.3 percent fed in, they find a smooth, downward-sloping kernel
  (no "pricing kernel puzzle") and risk aversion of roughly 3-9, 7-18 and 9-27, rising with wealth.
- The literature point that matters for us: Jackwerth, using historical returns for the real-world side, found
  post-1987 S&P puts priced too high and built profitable put-selling strategies; these authors argue his puzzle may be
  an artefact of using historical data. Either way, the market's risk-neutral tails are fatter on the downside than
  what usually happens, which is the volatility/skew risk premium.
- Signal source: volatility / options structure. Maps to: NSE index options (the variance and skew premium), not a rule.
- Mechanical? the measurement yes; any trade from it not stated.
- Our research: R102 (DEEP_NIFTY_BANKNIFTY.md 2.2): India VIX above NIFTY's next-21-day realised volatility on 81% of
  4,507 days, median ratio 1.28, but above BANKNIFTY's only 40% of days. R103: harvesting it with defined-risk structures
  failed (0 of 24); the naked 09:20 short straddle was positive but with fat tails beyond Boss's stops. For the full
  short-volatility playbook see research/books/bennett-trading-volatility.md (not repeated here).

### Ch 3. Pricing toxic assets (Currie, pp 53-69)
Teaches: background on CDOs and the 2009 US toxic-asset purchase plan, a description of agricultural price-risk tools
(swaps, minimum-price contracts, floors, caps, collars) and a proposal to price toxic assets with put/call floors plus
Basel II economic-capital allocation. The proposal is qualitative; no data test.

#### 3a. Protective put floor and collar (practical, a hedge not a signal)
- What it computes: the guaranteed minimum price of a long holding = put strike + local basis - premium - costs. Worked
  example (a cattle producer): a $60 put bought for $2.75, basis +$1.00, costs $0.25 gives a $58 floor; when futures fell
  to $55 the cash sale at $56 plus a $2 net option gain still delivered $58. Selling an upper call as well makes a
  collar (used by Australian gold miners).
- Entry / stop / exit: not a trading rule; held to the sale date.
- How we could use it: an MCX producer-style hedge or a "lock" on a profitable futures position (buy a put to floor a long
  GOLD/SILVER/CRUDE future). For crypto, a bought put is one form of the mandatory stop.
- Signal source: none (hedge construction). Maps to: MCX futures + options yes; NSE index (a futures long hedged with a
  put) yes; crypto options yes.
- Mechanical? yes. Our research: not tested by us.

#### 3b. Absolute vs marginal risk contribution (practical for a multi-arm book)
- What it computes: absolute risk contribution of a position = its covariance with the whole book divided by book
  volatility; these add up to the book's loss volatility and allocate capital after the fact. Marginal contribution =
  book risk with the position minus without it; used to price or accept a new position. "Retained risk" of a position =
  its correlation with the book. Worked two-loan example: default probabilities 7% and 5%, exposures 100 and 50, default
  correlation 10%: expected loss 9.5, loss volatility 28.73, 1% loss about 100, so capital 90.5.
- How we could use it: before adding a new paper arm, compute its marginal contribution to the paper book's daily P&L
  volatility; an arm highly correlated with existing arms adds risk, not diversification (R72's Breadth Donchian is a
  live example: 77% of its trades are Donchian's own, daily correlation 0.80, so it should replace, not add).
- Signal source: none. Maps to: all. Mechanical? yes. Our research: no formal risk-contribution study (not tested by us).

### Ch 4. A fast general framework for pricing options with exponential time integration (Tangman, Boojhawon, Gopaul and Bhuruth, pp 70-89)
Teaches: a numerical PDE method. Discretise the pricing equation in log-price, then step it forward exactly in time
with a matrix exponential, computed cheaply with a best rational approximation (about four sparse linear solves). It
handles Black-Scholes, Merton jump-diffusion, Heston stochastic volatility, Bates (stochastic volatility with jumps) and
CGMY / Kou / variance-gamma jump processes; European, barrier and (with operator splitting) American options; Greeks
come out of the grid; Richardson extrapolation adds accuracy.

#### 4a. Jump and stochastic-volatility option pricer (practical only if we fit smiles)
- Results (MATLAB, 2.21 GHz laptop; numerical, no market data): a European call on 4,096 grid points priced to an error
  of 5.89e-6 in 0.157 s against 5.72 s for Crank-Nicolson; for American puts, Crank-Nicolson with operator splitting was
  about ten times faster than the new method at T = 0.5 and similar at T = 3, while the classic PSOR method took 572 s.
- The modelling point stated in the chapter: Black-Scholes cannot fit fat tails; stochastic volatility calibrates long
  expiries well but jumps are essential for short-expiry smiles.
- How we could use it: NSE index options are European and short-dated, so closed-form Black-Scholes is enough for our
  repricing and Greeks (as DEEP_OPTIONS_SELLING.md does). A jump model would matter only if we wanted to fit the weekly
  NIFTY smile, especially on expiry day where R102 found ATM IV 7.7 points above VIX and the model "breaks down" in the
  last minutes. Not on the tick path; research only.
- Signal source: volatility / options structure (a pricer). Maps to: NSE index options yes; MCX options
  (options on futures) partly, strikes are thin; crypto options yes. Mechanical? yes.
- Our research: not tested by us (we use Black-Scholes only).

### Ch 5. Unconditional mean, volatility and the Fourier-GARCH (Pascalau, Thomann and Gregoriou, pp 90-106)
Teaches: GARCH(1,1) assumes a constant long-run variance. Structural shifts in the mean or variance make returns look
"long memory" and push GARCH persistence (alpha + beta) toward 1. The authors model the long-run variance (and,
optionally, the mean) as a smooth function of time built from one or two low-frequency sine/cosine terms (Gallant's
flexible Fourier form), with an ordinary GARCH(1,1) riding on top.

#### 5a. Fourier-GARCH / slowly moving long-run volatility (practical idea, not usable as published)
- What it computes: daily conditional variance = (slow long-run variance level) x (normalised GARCH(1,1) component). M1
  lets only the variance level drift; M2 also lets the mean drift; M3 puts the long-run variance into the mean.
  Frequencies are picked by AIC/BIC; one frequency fitted best; more than five overfit.
- Data: CRSP daily returns of the 12 largest S&P 500 stocks, from 1926 where available; S&P 500 index 1963-2005.
- Results (persistence alpha + beta, GARCH vs M2): IBM 0.99180 to 0.51090, Exxon 0.98333 to 0.54307, Procter & Gamble
  0.99595 to 0.29293, Johnson & Johnson 0.95222 to 0.01595, AIG barely moved (0.98024 to 0.97753). Persistence dropped by
  half or more for 10 of 12 stocks when the mean was allowed to drift; letting only the variance drift (M1) changed
  little. Conclusion claimed: GARCH persistence is largely a symptom of a mis-specified mean.
- Caveats (ours): the Fourier terms are functions of t/T over the whole sample, so the fitted long-run level uses future
  data - it cannot be computed live without a rolling re-fit; there is no out-of-sample volatility forecast test; the
  AIC/BIC table looks odd; Microsoft's GARCH persistence of 0.06820 suggests fitting problems.
- How we could use it: our size forecasts (R16, R88) rest on volatility persistence. The lesson is to pair a short
  memory term (last bars' range) with a slowly moving baseline (e.g. a long rolling realised-volatility level or the
  VIX regime) rather than trust one constant long-run level; and to treat very high persistence estimates with
  suspicion when the sample spans regimes (BANKNIFTY's weekly-to-monthly expiry switch in Nov 2024 is such a break).
- Signal source: underlying price/volume (volatility of the index itself). Maps to: NSE index (size forecasting, stop
  and position sizing), MCX (MB1 found size predictable), crypto (C1/C2 size predictable). Not a direction signal.
- Mechanical? yes (estimation); but needs a causal (rolling) version.
- Our research: GARCH itself was never tested by us (no GARCH study in research/). Nearest: R16 (HUNT_R16_PREDICTABILITY.md)
  - next 30/60-minute range predictable, holdout error 15-20% below the time-of-day average; R88 - size of the next
  candle predictable (225 of 305 hold), "last size + recent volatility" does most of it; R41 - neural nets forecast
  60-minute range with holdout R2 0.432 vs trees 0.382; R89 - buying straddles on a big-move forecast lost (0 of 18),
  because options already price the expected move.

### Ch 6. Nonlinear financial integration: the Philippine stock market (Arouri and Jawadi, pp 107-118)
Teaches: whether an emerging market moves with the world index, using cointegration and two nonlinear error-correction
models (ECM). Monthly MSCI Philippines and World in USD, Dec 1987 - Jan 2008. Correlation of monthly returns rose from
0.33 (1988-1994) to 0.44 (1994-2008). Philippine returns: monthly sd 0.0927, kurtosis 4.8155 (fat tails).

#### 6a. Threshold (nonlinear) error-correction - the formal version of a pairs trade with a no-trade band
- What it computes: regress one index on the other (long-run relation), then model the change in the first as pulled
  back toward the relation only when the gap is large. The exponential smooth-transition ECM was rejected (nothing
  significant); the rational-polynomial ECM found a small significant pull (-0.0053, t -2.329) beyond some deviation, and
  cut residual sd only 5% vs the linear ECM. "Mixing" tests passed only at 10% (R/S) or one of two KPSS settings.
- Entry / stop / exit: not stated (an econometric fit, not a trade). A literal pairs reading would be: trade the spread
  back toward the long-run line only when it is beyond a threshold; no stop given.
- How we could use it: as a template for NIFTY vs BANKNIFTY (or GOLD vs SILVER) spread work - test the spread for
  stationarity first (Ch 10), allow a no-trade band, and expect the edge to be weak.
- Signal source: underlying price (two indices). Maps to: NSE index futures/options pairs partly (two option legs double
  costs); MCX GOLD/SILVER partly; crypto BTC/ETH partly (needs stop and profit lock).
- Mechanical? partly (thresholds and holding period not given).
- Our research: R72 (HUNT_R72_CROSS_INDEX.md) tested "C2 Laggard catch-up" across the five indices - 613 final trades,
  -Rs 1,00,746, failed; H39 found heavyweight stock options move with the index, not before it. A true cointegration
  spread with a threshold is not tested by us.

### Ch 7. Yield-curve curvature and real activity (Modena, pp 121-146)
Teaches: Nelson-Siegel level, slope and curvature factors, filtered with a Kalman filter from nine US maturities (1987-
2007); level tracks the inflation target, slope tracks policy, and curvature tracks the business cycle - a sharp
curvature drop came before each NBER recession; a real-activity regression explains curvature better (adjusted R2 0.48,
0.63 on the real curve) than a monetary one (0.38, 0.51); curvature vs the industrial-production cycle a year ahead has
correlation about 0.71. In-sample macro, no market-timing test.
- Practical tool: state-space trend + cycle decomposition with a Kalman filter (Harvey's structural model) - a general
  way to separate a slow trend from a cycle in any series. Indian G-sec curvature could be macro context for Jarvis.
- Signal source: none for trading (macro context). Maps to: none directly. Our research: not tested by us.

### Ch 8. Efficiency at the short end of the UK term structure (Hughes Hallett and Richter, pp 147-162)
Teaches: UK 3-month T-bill explained by the Bank rate, the US T-bill and GBP/USD with coefficients that drift over time
(random-walk parameters, Kalman filter), and "extrapolative" expectations instead of rational ones; then time-varying
spectra and phase shifts (who leads whom at each cycle length). Monthly 1972-2008, 396 observations in the final fit.
Claims: the US rate is the biggest influence; it leads the UK rate by about 0.08 of a month, falling to 0.06 by 2007;
the T-bill leads the Bank rate except 2002-07; the exchange rate leads at cycles longer than about 5.2 months.

#### 8a. Time-varying-coefficient regression (Kalman) and frequency lead-lag (practical tool)
- What it computes: a regression whose betas update each period by the forecast error (Kalman gain), plus a phase shift
  telling how many periods one series leads another at each cycle length.
- How we could use it: a live, drifting hedge ratio or beta between related series (NIFTY vs BANKNIFTY, NIFTY futures vs
  spot basis, GIFT Nifty vs NIFTY overnight). Not a direction signal by itself.
- Caveats (ours): R2 0.996 is a levels regression on very persistent series; no out-of-sample test.
- Signal source: underlying price. Maps to: NSE index futures/spot yes; MCX near/next futures yes; crypto spot/perp yes.
- Mechanical? yes. Our research: H39 (stock options vs index) found co-movement, not lead; R88 found NIFTY's same candle
  explains 51-90% of other indices' candles (with, not before). Kalman betas not tested by us.

### Ch 9. Continuous vs discrete time models of short-term interest rates (Hsiao and Semmler, pp 163-187)
Teaches: the CKLS mean-reverting short-rate SDE with level-dependent volatility, discretised three ways (Euler, Milstein,
new local linearisation). They show Euler and local linearisation are the same model after reparametrising when the
drift is linear, and Milstein adds little at realistic parameters. Monthly overnight rates of Germany, UK and US, fitted
on Jan 1983 - Dec 1997 (180 months) and forecast on Jan 1998 - Jun 2000 (30 months).

#### 9a. Benchmark every forecast against "tomorrow = today", and model volatility with ARCH (practical method)
- Results: the CKLS model forecast the level no better than the naive no-change forecast (relative error 99-105
  percent); its residuals were strongly autocorrelated (normalised autocorrelation about 3.5 to 5, where a Monte Carlo of
  1,000 runs kept 96 percent of maxima under 2.8) and fat-tailed. Two richer continuous-time models (nonlinear drift;
  stochastic volatility) could not reproduce that autocorrelation. A discrete ARMA with level-dependent volatility plus
  ARCH errors cut out-of-sample level error to 76-81 percent of naive for Germany and the US but not the UK (107-116
  percent), and improved volatility forecasts more consistently (US out-of-sample 0.0020% to 0.0014%).
- How we could use it: every Jarvis or research forecast should report its error relative to the naive forecast and
  the time-of-day baseline (R16 already does this); residual autocorrelation and a histogram-vs-normal test are cheap
  specification checks; volatility (size) is the forecastable part, level/direction much less.
- Signal source: underlying (a forecasting method). Maps to: NSE / MCX / crypto as a validation habit.
- Mechanical? yes. Our research: agrees in spirit with R16, R41 and R88 (size predictable, direction not); interest
  rates themselves not tested by us.

### Ch 10. The expectations hypothesis in Egypt and Lebanon (Hakim and Neaime, pp 188-202)
Teaches: unit-root tests (DF-GLS, KPSS) and Johansen cointegration on government yields - Egypt weekly 1-7 years (120
observations, 2006-2009), Lebanon monthly 3-24 months (209 observations, 1991-2009). All yields are I(1); the authors say
the spreads are stationary and the yields cointegrated (one vector Lebanon, three Egypt), so long and short rates do not
drift apart. Note: their KPSS table marks every spread as rejecting stationarity at 1%, and the Lebanon trace test
rejects ranks 0 and 1, so the tables do not cleanly support the text.

#### 10a. Johansen cointegration test (practical tool for pairs)
- What it computes: how many stable long-run combinations exist among several non-stationary price series, and their
  weights (a spread that should mean-revert).
- How we could use it: the first gate for any NIFTY/BANKNIFTY/FINNIFTY or GOLD/SILVER spread idea; test the spread's
  stationarity on design data before any mean-reversion rule, with a walk-forward re-estimate.
- Signal source: underlying price. Maps to: NSE index futures, MCX metals, BTC/ETH. Mechanical? yes.
- Our research: not tested by us as cointegration (R72 C2 is the nearest, failed).

## Performance claims
- No trading performance is claimed anywhere; the book has no back-tests with costs.
- Ch 1 quotes literature: hedge-fund indices 12.8 percent pre-fee (3.8 fees, 3.7 alpha, 5.4 beta), annual Sharpe ratios
  by style from TASS (e.g. convertible arbitrage mean 2.38, long/short equity 0.82, managed futures 0.34; 1977-2004) -
  all subject to the biases the same chapter lists.
- Ch 2: 198 of 200 options fitted inside the spread (in-sample). Evidence: one example table plus the count.
- Ch 4: speed/accuracy tables against reference prices (numerical, no market data).
- Ch 5: persistence falls by half or more for 10 of 12 stocks (in-sample; no forecast test).
- Ch 9: discrete ARMA-ARCH beats continuous models out of sample for 2 of 3 countries (30 monthly forecasts each).
- Ch 6, 7, 8, 10: statistical fits only.

## Risk, sizing and psychology
- Ch 1: leverage is the route from "promise high returns" to insolvency; Long-Term Capital Management ran 28 to 1 debt to
  equity; Sharpe ratios mislead with fat tails and autocorrelation, so use Sortino and maximum drawdown; funds most often
  fail from operational issues or fraud rather than strategy.
- Ch 3: economic capital = loss at a chosen confidence level minus expected loss; marginal risk contribution is the
  right test for adding a new position. No position-sizing rule (no Kelly) anywhere in the book.
- No psychology content.

## How it relates to our research
- Volatility risk premium: the book's option chapter (Ch 2) and its literature (Jackwerth's overpriced puts) agree with
  R102 (VIX over NIFTY realised on 81% of 4,507 days, median 1.28; put side dearer on 91% of days). Our R103 already
  showed that harvesting it with defined-risk spreads did not pass (0 of 24), and the passes were stale wing prices -
  the same data-quality issue Ch 2 guards against with simultaneous, screened quotes.
- Volatility persistence: Ch 5 and Ch 9 both say volatility (size) is modelable and level/direction much less; this
  agrees with R16, R41, R88 and the size-yes/direction-no line in FINDINGS.md. R89 shows the size forecast does not pay
  as a straddle buy. GARCH itself is not tested by us.
- Pairs / cointegration (Ch 6, Ch 10) and lead-lag (Ch 8): our nearest tests, R72 C2 laggard catch-up and H39 heavyweight
  lead, both failed; formal cointegration with a threshold is not tested.
- Option pricers (Ch 2, Ch 4): we use Black-Scholes repricing (DEEP_OPTIONS_SELLING.md); implied trees and jump models
  are not used.
- Nothing in the book contradicts a finding of ours; nothing in it is an already-rejected trading rule, because it has
  no trading rules.

## What is new to us
1. A daily risk-neutral density of NIFTY from the chain (implied tree or Breeden-Litzenberger), compared with the
   realised distribution - a richer version of R102's VIX-vs-realised table, and a stale-quote check.
2. A two-speed volatility model (slow long-run level x short GARCH-like term), built causally, as a candidate to beat
   R88's "last size + recent volatility" baseline; plus a check of how much GARCH persistence on NIFTY/BANKNIFTY drops
   once known regime breaks (expiry-calendar changes) are allowed for.
3. Autocorrelation-adjusted Sharpe and Sortino in arm scorecards; marginal risk contribution before adding an arm.
4. Kalman time-varying hedge ratios for spot/futures/basis and index pairs; Johansen + threshold test before any spread
   mean-reversion idea.
5. The habit from Ch 9: always report forecast error relative to the naive forecast.

## Could not read
- Equations: the text layer breaks every formula (fractions, sums, Greek subscripts, matrices in Ch 2, 4, 5, 7, 8, 9,
  10); we read the prose around them, which states what each model does.
- Charts: only axis numbers and captions survived for Figures 1.1, 2.1-2.2, 4.1, 5.1-5.7, 6.1, 7.1-7.8, 8.1-8.5,
  9.1-9.15 and 10.1-10.2; the chapters' prose describes their message, which is what we used.
- Ch 3's feeder-cattle outcome charts are garbled (labels mixed); the worked table above them was readable.
- Page images were not consulted. Nothing else missing.
