# Applied Quantitative Finance - Wolfgang Härdle, Torsten Kleinow, Gerhard Stahl (eds.) (2002)

Source: applied-quantitative-finance.txt (pdftotext of the XploRe e-book), 423 pages (xxii + 401), read in full on 25 Oct
2026 (text layer, all 18 chapters, preface and index; bibliographies skimmed). Formulas came through partly garbled and
the figures are plots whose axis numbers only survive; the prose and the result tables are readable (see "Could not read").
Book claims are the authors', not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
An edited textbook from the Humboldt-Universität CASE group: 18 chapters by 30 authors, each with XploRe code ("quantlets").
Part I is market-risk measurement: delta-gamma Value-at-Risk, copulas, historical simulation of spread risk.
Part II is credit risk: rating-migration matrices and how copula choice drives portfolio default losses.
Part III is implied volatility: the IV surface and its principal components, implied trees, state-price densities, and a skew/kurtosis option trade on the DAX.
Part IV is econometrics: multivariate GARCH, control charts, a diffusion test, Kalman filters, Hurst long memory with a pairs trade, adaptive windows, Monte Carlo pricing, nonparametric GARCH.

## Methods, rules and setups

Our data, for the mapping: NIFTY weekly / BANKNIFTY monthly options (1-minute lake of the nearest expiry, ATM +-10 strikes
with IV and OI; end-of-day bhavcopy for every strike since Jan 2021), India VIX, index spot / futures bars since 2006, MCX
futures and options, Delta Exchange crypto. Index options are European and cash-settled. One ATM round trip costs about
Rs 70 (NIFTY) / Rs 76 (BANKNIFTY) in charges, about Rs 98 / Rs 133 with the spread (`knowledge/baselines.yaml`). Volatility
trading basics (variance swaps, skew and term-structure trades, dispersion) are already written up in
`bennett-trading-volatility.md`; this note does not repeat them.

### Chapter 1 - Approximating VaR in conditional Gaussian models (Jaschke, Jiang)
Teaches how to get a portfolio's loss quantile when the book is approximated by its first and second derivatives (delta
and gamma) and the risk factors are normal given yesterday. Compares three ways to get the quantile: Cornish-Fisher
(moment-based, very fast, good on near-normal books but its worst case error is about one standard deviation, non-monotone
and wrong in the far tail), Fourier inversion (exact up to a tunable error; most of the chapter is the error analysis, pure
numerics) and "partial" Monte Carlo (simulate factors, revalue by delta-gamma). Covariance can be the plain window or an
exponential moving average, which the authors note is a special GARCH estimate.

#### 1. Delta-gamma VaR with variance-reduced Monte Carlo (Ch. 1.5)
- What it computes: the 1-day loss not exceeded with 99% probability for an option book, by simulating factor moves and
  revaluing with delta and gamma.
- Inputs: aggregated delta and gamma per factor, factor covariance matrix, confidence level, number of scenarios.
- Method detail: plain sampling converges slowly (error shrinks with the square root of the scenario count). Variance
  reduction: antithetic pairs, control variates, moment matching, stratified sampling, Latin hypercube, importance sampling
  (tilt the sampling towards the loss region and reweight by the likelihood ratio).
- Book's example: three German stocks plus 156 options (62,476 EUR), 1,000 scenarios: VaR 771.73 EUR (1.24% of value).
  Standard error versus plain sampling: moment matching -1.96%, Latin hypercube -42.31%, importance sampling -84.68%
  (about 42 times fewer scenarios for the same precision).
- Caution in the book: a quadratic approximation can give very large VaR errors for option books, and the Gaussian
  dependence understates joint extremes.
- Signal source: none (risk measure); maps to: NSE index options yes (the Greeks of a short straddle or iron fly book; a
  short-gamma book is exactly where the quadratic approximation fails on a gap), MCX options yes, crypto options yes.
- How we could use it: a risk-of-ruin and worst-day estimate for any paper option-selling book; but revalue legs fully
  with Black-Scholes rather than delta-gamma, since our book is small (a few legs) and full revaluation is cheap.
- Mechanical? yes. Our research: not tested by us as a VaR model. R103 (`HUNT_R103_OPTIONS_SELLING.md`) measured the
  realised worst day of a naked short straddle directly (-Rs 28.9k NIFTY on one lot), which is the empirical version.

### Chapter 2 - Copulas for VaR (Rank, Siegl)
Teaches that a joint distribution splits into its margins plus a "copula" that carries all the dependence (Sklar's
theorem), why correlation is a weak summary of dependence, how to fit a copula (least squares fits the centre and misses
the tails; maximum likelihood is better) and how to simulate from it (conditional inversion).

#### 2. Copula Monte Carlo VaR with exceedance backtest (Ch. 2.2-2.4)
- What it computes: VaR of a two-factor linear position whose dependence is any of 21 copulas, plus a backtest that counts
  days the next-day loss beat the VaR and compares the share with the target 10% / 5% / 1%.
- Inputs: daily returns of the two factors (they used USD/EUR and GBP/EUR, 2 Jan 1991 - 9 Mar 2000), 250-day estimation
  window, four long/short portfolios.
- Book's result: historical simulation scored best (average error .009, rank 1) because the real margins are not normal;
  plain variance-covariance scored .028 (rank 6); some copulas with normal margins (numbers 12-14) beat it (.022-.026).
- Signal source: none (risk); maps to: NIFTY with BANKNIFTY legs, MCX GOLD with SILVER, BTC with ETH.
- How we could use it: estimate the chance that two open arms lose together on one day (the account stop is -Rs 40k
  drawdown / -Rs 13k a month). Mechanical? yes. Our research: not tested by us.

### Chapter 3 - Spread risk by historical simulation (Frisch, Knöchlein)
Bond yield spreads (US, 1992-2000, about 2,150 days) have daily changes with kurtosis around 10-20, so a normal-based VaR
is wrong. Compares four historical-simulation variants on a synthetic 10-year zero bond: basic, mean-adjusted (remove the
window's drift), volatility-updated (Hull: rescale each past change by the ratio of today's EWMA volatility to the
volatility on that day, decay 0.94) and both.

#### 3. Volatility-updated ("filtered") historical simulation and its calibration check (Ch. 3.4-3.9)
- What it computes: a next-day loss quantile from the last 250 days of moves, each rescaled to today's volatility level.
- Inputs: daily returns, EWMA decay 0.94, window 250, horizon 1 day, 99% level.
- Book's result: target violation rate 1%; basic simulation 1.27% (full yield) and 1.57% (benchmark only); with volatility
  updating 1.18-1.20% and 1.14%. Adding the two VaRs as if fully correlated over-estimates (0.41% violations). Best was the
  joint simulation of both factors with volatility updating (1.03-1.05%). The P-P plot (where each realised loss falls in
  the predicted distribution) with a mean-squared-deviation score checks calibration at every level at once.
- Signal source: none (risk); maps to: any instrument we hold, NSE, MCX, crypto.
- How we could use it: the "expected worst day" shown to Boss for an arm or the whole paper book; the P-P score is a
  ready-made test for R16 / R41 / R88 range forecasts (do realised ranges fall uniformly across forecast quantiles?).
- Mechanical? yes. Our research: not tested by us; our size forecasts (R16, R88) are judged on error, not on calibration.

### Chapter 4 - Rating migrations (Höse, Huschens, Wania)
Estimates credit-rating transition matrices from counts, with binomial standard errors. Key statistical point: when
events are positively correlated, the observed frequency does not settle down as the sample grows (no law of large
numbers). Tests time-stability with chi-square homogeneity tests (rejected across business-cycle phases, Moody's
1970-1997), builds multi-year matrices as matrix powers, and bootstraps the counts for their standard errors.

#### 4. Regime transition matrix with stability test and bootstrap errors (Ch. 4.1-4.3)
- What it computes: probabilities of moving between discrete states from one period to the next, whether they change over
  time, and how uncertain multi-step forecasts are.
- Inputs: a state label per period (for us, a VIX band or realised-volatility band per day or week), counts per pair.
- How we could use it: "after a high-VIX week, how often is the next week also high" with honest error bars; the
  correlated-events point also explains why 1,000 same-day trades are worth far fewer independent observations.
- Signal source: underlying (or VIX); maps to: NIFTY/BANKNIFTY, MCX, crypto as context only. Mechanical? yes.
- Our research: not tested by us as a transition matrix; R16 found volatility state predictable (size), not direction.

### Chapter 5 - Sensitivity of credit portfolio models (Kiesel, Kleinow)
Default-mode portfolio model with latent drivers. Default correlation is far smaller than the asset correlation behind it
(0.2 gives 0.0241). The headline is about tail dependence: a Gaussian copula has none, a Student-t copula (more as its
degrees of freedom fall) and a Gumbel copula do.

#### 5. Tail-dependent copula for joint losses (Ch. 5.3-5.4)
- Book's result: 500 obligors, asset correlation 0.2, 99% default-count quantile: rating class A Gaussian 22 versus t4 71
  (more than three times); the copula choice moves the tail far more than adding a correlated cluster does.
- How we could use it: when several arms or instruments are open, simulate joint losses with a t copula rather than a
  correlation matrix (NIFTY and BANKNIFTY have daily correlation 0.877 in R102, and both crash together).
- Signal source: none (risk); maps to: NSE, MCX, crypto. Mechanical? yes. Our research: not tested by us.

### Chapter 6 - The analysis of implied volatilities (Fengler, Härdle, Schmidt)
Implied volatility by inverting Black-Scholes (bisection or Newton; unique because price rises with volatility), puts via
put-call parity. The DAX smile is convex across strikes. The surface is smoothed with a two-dimensional kernel
(Nadaraya-Watson, quartic kernel) over moneyness (strike over forward) and maturity.

#### 6. PCA of implied-volatility changes, plain and common (Ch. 6.3)
- What it computes: the few factors that drive daily IV changes, and each maturity's sensitivity to them.
- Inputs: daily IV at fixed maturities (interpolate between listed expiries), first differences, covariance, eigenvectors.
- Book's result: VDAX at eight fixed maturities, 440 days: first factor about 70% of variance (an almost parallel level
  shift), second about 13% (front against back: the term-structure twist), together about 83%. Common PCA across 1-, 2- and
  3-month smiles (moneyness 0.85-1.10): the same shift, slope and twist shapes for every maturity, with variance falling
  from front to back; likelihood-ratio test 31.836, p 0.37512 on 30 degrees of freedom, so the common model holds. Oct
  1997: the term structure inverted overnight and normalised within weeks.
- How we could use it: a short-vega book's risk is mostly one number (the level factor); stress it with the first two
  factors instead of every strike. A daily NIFTY IV surface from the bhavcopy would give the factors.
- Signal source: volatility / options structure (risk, not a trade); maps to: NSE index options yes, MCX options (thin),
  crypto options yes. Mechanical? yes.
- Our research: R102 section 3.4 (`DEEP_NIFTY_BANKNIFTY.md`) measured the levels (ATM IV, put skew, expiry-day IV jump)
  but not the factor structure of their changes. Not tested by us.

### Chapter 7 - How precise are implied binomial trees? (Härdle, Zheng)
Implied binomial trees (Derman-Kani; Barle-Cakici, which centres nodes on the forward and copes with higher rates) bend the
Cox-Ross-Rubinstein tree so it reprices the whole smile. Out come the risk-neutral price distribution at expiry (the
"state-price density", SPD) and a local volatility for every price and time; local volatility falls as price rises (the
equity skew). Edge nodes produce negative probabilities that must be overridden, which throws away smile information.
Checked against a Monte Carlo of a known local-volatility process (they agree), then applied to the DAX (4 Jan 1999).

#### 7. Implied tree / local volatility (Ch. 7.1-7.2)
- What it computes: a recombining tree consistent with today's option prices, the implied distribution of the index at
  expiry, and the local volatility surface.
- Inputs: a smoothed IV surface, rate, spot. Signal source: volatility / options structure.
- How we could use it: probability that NIFTY ends beyond a strike by expiry consistent with the skew (a better "chance of
  target" for a strike rule than flat Black-Scholes). Maps to: NSE yes, crypto yes, MCX only where strikes are liquid.
- Mechanical? yes. Our research: not tested by us. The chapter also lists the skew and kurtosis rules tested in Ch. 9.

### Chapter 8 - State-price densities by nonparametric regression (Huynh, Kervella, Zheng)
The risk-neutral density equals the discounted second derivative of the call price with respect to strike (Breeden and
Litzenberger; the limit of a butterfly spread). Strikes are discrete, so one smooths the IV smile with a local quadratic
polynomial, which gives the volatility and its first two derivatives at once, and plugs them into an analytic formula for
the density, delta and gamma at every strike (Rookley's method). Bandwidth choice matters most; the edges are unreliable.

#### 8. Risk-neutral density from the smile with bootstrap bands (Ch. 8.3-8.4)
- What it computes: the market's implied distribution of the index at expiry, its skewness and kurtosis, and smile-aware
  delta and gamma.
- Inputs: one day's option prices for one expiry, the forward (they back out implied dividends from put-call parity and
  futures, which removes the put/call IV gap; for NIFTY use the futures price as the forward), a bandwidth.
- Book's result: DAX Jan 1997: the implied density is negatively skewed against the lognormal; kurtosis falls with
  maturity. Wild-bootstrap 95% bands (100 resamples). Cross-validated on at-the-money butterflies (21 days): both this and
  the implied tree under-price butterflies (too little kurtosis), this method less so.
- Signal source: option chain as context / volatility structure; maps to: NIFTY/BANKNIFTY yes (from bhavcopy or the lake's
  ATM +-10 strikes for the centre only), crypto options yes, MCX options thin.
- How we could use it: daily implied skew and kurtosis as features; "chance of reaching the target by expiry" for Jarvis.
- Mechanical? yes. Our research: IV1 (`HUNT_IV1_OPTION_MARKET.md`) found option-market signals (skew, straddle, PCR, OI)
  lose on their own on all 5 indices; implied density moments specifically are not tested by us.

### Chapter 9 - Trading on deviations of implied and historical densities (Blaskowitz, Schmidt)
The one chapter that is a full trading study. Each month (30 periods, Apr 1997 - Sep 1999, DAX), on the Monday after the
third Friday: estimate the 3-month implied density f from two weeks of option settlements (Barle-Cakici tree, skewness and
kurtosis averaged over trees of 10 to 100 steps because they never converged); estimate a "historical" risk-neutral
density g by fitting the index's volatility as a function of its level to the last 3 months of closes (kernel estimator),
simulating 10,000 paths with drift equal to the interest rate and smoothing the end points; shift g to the futures price
and match its variance to f, so only skewness and kurtosis are compared. Moneyness is strike over forward: far-OTM put
below 0.90, near-OTM put 0.90-0.95, ATM put 0.95-1.00, ATM call 1.00-1.05, near-OTM call 1.05-1.10, far-OTM call 1.10 up.
Finding: the implied density was more negatively skewed than the historical one in 29 of 30 periods, and had fatter
tails (implied leptokurtic, historical slightly below 3) in all but one.

#### 9. Skewness trade S1 / S2 (Ch. 9.5, Table 7.1)
- Entry: if implied skewness is below historical (S1): sell every OTM put (moneyness 0.86-0.95) and buy every OTM call
  (1.05-1.14), one each, on the Monday after monthly expiry, 3-month options. If implied is above historical (S2): the
  reverse (buy OTM puts, sell OTM calls).
- Stop: none. Target / exit: hold to expiry, cash-settled.
- Filters: only the density comparison (which said S1 in 29 of 30 months, so it is effectively "always S1").
- Market and timeframe written for: DAX index options (EUREX), monthly entries, 3-month holding, 1997-1999.
- Book's result: with 5% transaction costs on option prices, total net +9855.50 EUR; 28 small gains and 2 large losses
  (positions opened June 1998 and September 1998, into the 1998 sell-off); 24 periods had zero payoff at expiry. The
  position is a risk reversal (short put, long call), so it is a long-index bet: big gains after the Dec 1997 low, big
  losses in Aug-Sep 1998. S2 run every month would have lost.
- Signal source: volatility / options structure (implied versus historical density), plus an implicit long-index exposure.
  Maps to: NSE index options partly (NIFTY monthly 5-14% OTM strikes exist and settle in the bhavcopy, but are thin; the
  1-minute lake holds only about +-2%); MCX options no (too thin that far out); crypto options yes, but our crypto rule needs
  a stop and a profit lock, which this rule does not have.
- Mechanical? yes (as a monthly hold-to-expiry rule; the density step can be replaced by "implied skew richer than
  realised", which held almost always).
- Our research: R102 section 3.4 found the same raw fact on NIFTY: puts five strikes out dearer than calls five out on 91%
  of 1,222 days (BANKNIFTY 90%). The risk-reversal trade itself is not tested by us. R103 found short-premium structures
  break even before costs and lose after them on intraday holds; this is a 3-month hold, which R103 did not cover.

#### 10. Kurtosis trade K1 / K2 (Ch. 9.6)
- Entry: if implied kurtosis is above historical (K1): sell far-OTM puts, buy near-OTM puts, sell ATM puts and calls, buy
  near-OTM calls, sell far-OTM calls (whole moneyness bands as above). K2 is the mirror.
- Stop: none. Target / exit: hold to expiry.
- Book's result: K1 total net +10,915.77 EUR with 5% costs. Every K1 portfolio lost at expiry; all of the profit was the
  premium taken in at entry. Worse in trending months (Apr-May 1997, Nov 1997 - Jun 1998), better in sideways months. The
  payoff shape depends on which strikes exist; with the full strike set it is negative everywhere at expiry. K2 had one
  qualifying month and could not be judged.
- Signal source: volatility / options structure; short ATM premium plus short far wings (NOT a defined-risk structure:
  the far wings are sold, not bought). Maps to: NSE index options partly (same strike-range problem as S1); MCX no; crypto
  no (no stop).
- Mechanical? yes. Our research: R103 found defined-risk selling 0 of 24 (the passes were stale wing prices), and that a
  naked short straddle made +Rs 64k on NIFTY over 1,272 days (62% win) with a worst day of -Rs 28.9k (-28,876) on one lot;
  with 1-straddle wings it lost. K1 adds sold far wings, i.e. more tail risk, not less.
- Authors' own caution (Ch. 9.7): strong directional risk, only 30 periods, no risk adjustment (no Sharpe), the profit is
  payment for crash insurance and could vanish in a crisis ("peso risk").

### Chapter 10 - Multivariate volatility models (Fengler, Herwartz)
Bivariate GARCH(1,1): the full model has 21 parameters, constant correlation 7, diagonal 9, BEKK 11 (positive definite by
construction and allows volatility spill-over between markets). Fitted by quasi-maximum likelihood (BHHH algorithm);
sensible start values about 0.3 for the shock loadings and 0.9 for persistence.

#### 11. BEKK bivariate GARCH and density-forecast comparison (Ch. 10.2-10.3)
- What it computes: today's variance of each series and their covariance, from yesterday's shocks and variances; forecast
  joint densities by simulation.
- Book's result: DEM/USD and GBP/USD daily, 31 Dec 1979 - 1 Apr 1994 (3,720 days); persistence 0.939 on both; covariance
  positive throughout. One-week-ahead densities (50,000 simulations each, 3,714 dates): the bivariate model gave the higher
  density at the realised outcome 81.6% of the time (0.744-0.856 by two-year window), more so when covariance was high.
- Signal source: underlying; maps to: NIFTY + BANKNIFTY (or the five indices), GOLD + SILVER, BTC + ETH.
- How we could use it: the combined range forecast when several arms are open, and a volatility forecast for sizing.
- Mechanical? yes. Our research: R16, R41 and R88 found the SIZE of the next move predictable (R88: 225 of 305 size
  tests hold; "last size + recent volatility" does most of it), never the direction. GARCH itself is not tested by us.

### Chapter 11 - Statistical process control (Knoth)
Sequential change detection on a standardised stream: Shewhart (one big point), EWMA (smoothed mean beyond a band) and
CUSUM (running sum of excess over a reference value k, alarm above a limit h; Crosier's two-sided version). Charts are
designed by average run length: how long until a false alarm when nothing changed (e.g. 300 or 500 observations) versus
how fast a real shift is caught. Computed with a Markov-chain approximation; matches the published tables.

#### 12. CUSUM / EWMA monitor for a broken model or arm (Ch. 11.1-11.4)
- What it computes: an alarm when a stream (residuals, per-trade results) has shifted away from its in-control behaviour.
- Inputs: in-control mean and sd from a pre-run; k (half the shift to detect, in sd; 0.5 is standard); target false-alarm
  run length; for EWMA a weight lambda (0.1-0.2). Book's limits for run length 300: two-sided EWMA (lambda 0.1) 2.6203,
  one-sided CUSUM (k 0.5) 3.8929, Crosier 4.288. A one-sd shift is caught in about 6-11 observations.
- Book's example: Deutsche Bank against the DAX (beta 0.8838, about 450 pre-run days 1995-97), standardised residuals of
  an AR(1) on the regression errors, two-sided EWMA (lambda 0.2, run length 500, limit 2.9623): the first alarm came at
  observation 64 of the monitoring period (24 Jun 1997), then alarms became frequent: the model had changed. Fat tails
  (kurtosis 6.65) raise false alarms.
- Signal source: none (trade management); maps to: every arm, NSE, MCX, crypto.
- How we could use it: monitor each paper arm's per-trade net against its backtest distribution; an alarm may only pause
  or size down (a risk-lowering change, so it fits the LIVE rule as a shadow switch first).
- Mechanical? yes. Our research: not tested by us (no CUSUM/EWMA monitor in research/).

### Chapter 12 - Empirical-likelihood goodness-of-fit test for diffusions (Chen, Härdle, Kleinow)
Mostly theory: tests whether a parametric drift (for example mean reversion towards a level) fits, against a kernel
estimate, using empirical likelihood, which standardises itself and has a chi-square limit. S&P 500 1977-1997 (5,479
days, detrended): a mean-reverting (CIR-type) drift is not rejected. Simulations (5,000 runs) show correct size and power
falling with bandwidth. Practical carry-over only: a formal check that a spread really mean-reverts before trading it.
Signal source: underlying. Not tested by us.

### Chapter 13 - A state-space model of house prices (Schulz, Werwatz)
A common price index hidden under noisy, heterogeneous transactions (1,502 Berlin house sales, 1980-1999), modelled as an
AR(2) state with a hedonic regression and estimated with the Kalman filter (handles missing periods), maximum likelihood
via the prediction-error form, a Kalman smoother and residual checks (Q-Q plot, Jarque-Bera; residuals fat-tailed).

#### 13. Kalman filter for a hidden common level (Ch. 13.2-13.5)
- What it computes: the best running estimate of an unobserved level or coefficient from noisy observations, with its
  uncertainty, updated one observation at a time.
- How we could use it: a time-varying NIFTY/BANKNIFTY hedge ratio for a spread; a filtered ATM IV from noisy, sometimes
  stale option prints (R103's lesson: illiquid wings print stale prices); a fair futures basis.
- Signal source: underlying (or option chain as context); maps to: NSE, MCX, crypto. Mechanical? yes. Not tested by us.

### Chapter 14 - Long-memory effects trading strategy (Blaskowitz, Schmidt)
Hurst's rescaled-range (R/S) analysis: cut the series into blocks, remove each block's trend, take range over standard
deviation, regress its log on log block length; the slope H is about 0.5 for a random walk, above 0.5 for persistence
(trends), below for anti-persistence (reversion). Fractional Brownian motion, slowly decaying autocorrelation, and the
warning that the R/S estimate is biased upwards in finite samples (simulated Brownian motion, 7,289 days: mean 0.543, 95%
band 0.504-0.582), so it must be tested against simulation, not against 0.5.

#### 14. Hurst exponent by R/S with a simulated null (Ch. 14.2-14.4)
- What it computes: whether a price or a spread trends, reverts or wanders, with a significance band from simulation.
- Book's result: four German voting / non-voting share pairs, 7,290 daily closes 1973-2000: single shares H 0.50 to 0.57
  (random walk); the log-difference of each pair 0.33 to 0.41 (significant reversion).
- Signal source: underlying. Maps to: NIFTY/BANKNIFTY ratio, GOLD/SILVER ratio, BTC/ETH ratio, futures basis.
- Our research: not tested by us (Hurst appears in research/ only as a paper author in MCX_GUIDE.md).

#### 15. Long-memory spread reversal (Ch. 14.5)
- Entry: X = log(price A) - log(price B). If X falls below its lowest value of the last M days, buy A and sell B
  (cash-neutral: one A against m units of B of equal value); if X rises above its M-day high, do the reverse.
- Stop: none. Target / exit: the opposite signal (always in the market, stop-and-reverse).
- Filters: only pairs whose spread has H clearly below the simulated band. M = 50, 100 or 150 days.
- Market and timeframe written for: daily closes of German voting / non-voting shares, 1973-2000.
- Book's result (total net cash flow in EUR, WMF numeraire): M 50 at 0.5% cost per share traded: WMF 116.92, Dyckerhoff
  176.49, KSB 122.32, RWE 21.50; at 1% cost RWE goes negative (M 150: -3.01). Reverses over 26 years: 120-145 at M 50,
  35-47 at M 150. The pair with the highest H (RWE 0.41) did worst. No risk or interest adjustment; H chosen in-sample.
- Signal source: underlying price (the spread of two underlyings). Maps to: NIFTY vs BANKNIFTY futures (or buy the
  cheaper index's ATM call and the dearer one's ATM put), MCX GOLD vs SILVER futures, crypto BTC vs ETH perpetuals (only
  with a stop and a profit lock added, per CLAUDE.md).
- Mechanical? yes. Our research: R72 C2 "laggard catch-up" (`HUNT_R72_CROSS_INDEX.md`; buy the index that has not broken
  out when the others have) lost -Rs 1,00,746 in the final period (613 trades): the laggard usually stays behind. That is
  an intraday breakout-convergence bet, not a daily spread-extreme fade, so the book's rule is not tested by us. Note that
  fading an M-day extreme is the opposite of our Donchian arms (R51, R72 C1), which profit from breaks of a single index.

### Chapter 15 - Locally time-homogeneous time series (Mercurio)
Instead of a fixed look-back, pick, every day, the longest recent window over which the model's parameters have not
changed: test the candidate window against its sub-windows and stop at the first significant difference (Lepski /
Spokoiny method). Tuning: grid step 10-50 days (30 used), two sensitivity constants chosen from {2, 4, 6, 8} by the
smallest past forecast error over the last 200 days. Simulated parameter jumps are found within about the 30-day minimum.

#### 16. Adaptive look-back window for coefficients and volatility (Ch. 15.1-15.3)
- What it computes: a regression coefficient or a volatility that adapts quickly after a regime break and averages over a
  long window when nothing changed. For volatility it averages the square root of absolute returns (a power that makes
  the noise near-normal) over the chosen window.
- Book's results: Thai baht basket carry trade (borrow USD/DEM/JPY, lend baht), Apr 1993 - Feb 1997, per 100 USD: 3-month
  average realised profit 1.182 adaptive versus 1.166 recursive, 1.141 rolling 250-day, 1.147 Kalman (all significant);
  1-month 0.420 (standard error 0.333, not significant). The adaptive estimator had the smallest gap between expected and
  realised profit. The peg broke on 2 July 1997, after the sample.
- Signal source: underlying; maps to: NSE, MCX, crypto (volatility input for sizing and stops).
- How we could use it: a regime-aware realised-volatility input for R16/R88-style size forecasts instead of a fixed
  20-day window. Mechanical? yes. Not tested by us. (The currency-basket carry trade itself has no Indian counterpart.)

### Chapter 16 - Simulation-based option pricing (Lüssem, Schumacher)
Monte Carlo price = discounted average payoff over simulated risk-neutral end prices; the error falls with the square root
of the number of paths, independent of dimension, so it suits baskets and path-dependent options (Asian, barrier,
lookback); early exercise is hard. Quasi-Monte Carlo uses low-discrepancy sequences (Halton, Sobol, Faure, Niederreiter)
that fill space evenly and converge faster for smooth payoffs in low dimension; a random shift restores an error estimate;
high dimensions are poorly covered unless paths are built by a Brownian bridge (works to about 50 time points).

#### 17. Monte Carlo / quasi-Monte Carlo pricing and its limits (Ch. 16.1-16.3)
- What it computes: a price (or probability) for any payoff, with an error bar from the sample standard deviation.
- Limits in the book: rare positive payoffs (deep OTM) give huge relative errors unless importance sampling is used;
  random-number generators repeat after a cycle.
- How we could use it: probability that an option-buying stop or target is touched before expiry; the same square-root
  rule sizes our bootstraps (H15/H16 used 2,000-path stationary bootstraps); deep-OTM wing estimates need importance
  sampling. Signal source: none (tool); maps to: all. Mechanical? yes. Not tested by us as a pricing engine.

### Chapter 17 - Nonparametric estimators of GARCH processes (Franke, Holzberger, Müller)
GARCH(1,1) basics (long-run variance omega / (1 - alpha - beta); IGARCH with alpha + beta = 1 is RiskMetrics' EWMA);
threshold GARCH adds extra weight to negative returns (the leverage effect). The chapter's method estimates the volatility
function without assuming its form, by turning squared GARCH into an ARMA in logs and removing the known noise by
"deconvolution" kernels; separate functions for positive and negative previous returns. Simulations (1,000 points) recover
the function only where data are dense.

#### 18. Asymmetric (threshold) volatility check (Ch. 17)
- What it computes: whether volatility reacts more to falls than to rises, and the shape of that reaction.
- How we could use it: decide between a plain EWMA and a threshold GARCH for the size forecast on NIFTY (daily skew -0.04,
  kurtosis 14.1 in R102) and on crypto. Signal source: underlying. Mechanical? yes. Not tested by us.

### Chapter 18 - Net-based spreadsheets (Aydınlı)
A software chapter: the MD*ReX Excel add-in that sends work to an XploRe server, demonstrated on the copula VaR of Ch. 2 and
the DAX IV surfaces of Ch. 6 (2-3 Jan 1997). No new method.

## Performance claims
- Ch. 1: importance sampling cut the VaR standard error by 84.68% against plain sampling (1,000 runs of one 62,476 EUR
  book); a demonstration of computing speed, not trading profit.
- Ch. 2: historical simulation had the smallest VaR backtest error on two FX rates, 1991-2000 (one data set).
- Ch. 3: volatility updating moved violation rates towards the 1% target (1.57% to 1.14% on the benchmark curve).
- Ch. 9: S1 skewness trade +9855.50 EUR and K1 kurtosis trade +10,915.77 EUR net of 5% costs over 30 monthly DAX
  periods, 1997-1999. Evidence: one market, 30 overlapping 3-month positions, no risk adjustment, in a period with one big
  crash (1998) that produced S1's two large losses. The authors themselves call it preliminary.
- Ch. 10: bivariate GARCH density forecasts beat univariate 81.6% of the time (3,714 weekly forecasts, two FX rates).
- Ch. 14: the voting / non-voting reversal made money on all four pairs at 0 and 0.5% costs and on three of four at 1%
  (26 years, in-sample choice of pairs by H, no risk adjustment).
- Ch. 15: the adaptive basket weights gave the highest realised carry profit (1.182 per 100 USD over 3 months), by small
  margins over the other estimators.

## Risk, sizing and psychology
The book is about measuring risk, not about trading psychology, and never discusses position sizing or Kelly. Its risk
lessons: a normal or correlation-based model understates joint and extreme losses (Ch. 1, 2, 3, 5); option books need full
revaluation, not a delta-gamma shortcut, in the tails (Ch. 1); recent volatility should rescale history (Ch. 3, 15); any
model should be monitored and retired when its residuals drift (Ch. 11); correlated observations are worth fewer than
their count (Ch. 4). The two option trades of Ch. 9 carry unhedged directional and tail risk and have no stop. For our
own sizing work see H15 (half-Kelly on Liquidity 15+5: P(drawdown of 50% or more) 14%, and the Kelly fraction was unstable
across windows) and R74's note that volatility-targeted sizing fits the LIVE "only lower risk" rule but is untried.

## How it relates to our research
- Implied richer than realised (agree): Ch. 9's implied density was more negatively skewed and fatter-tailed than the
  historical one in almost every month; R102 found put skew richer on 91% / 90% of days and India VIX above NIFTY's next
  21-day realised volatility on 81% of days (4,507 days). See also `bennett-trading-volatility.md`.
- Harvesting it with short premium (partly contradicted for our horizon): R103 found defined-risk selling 0 of 24 and only
  the naked straddle positive, with fat tails; Ch. 9's K1 is naked short ATM plus short far wings, held 3 months. The
  book's evidence (30 periods, one market) is too thin to overturn R103; the 3-month horizon is untested by us.
- Option-chain shape as a signal (already rejected as stand-alone): IV1 found skew, straddle, PCR and OI lose on their own;
  R28/R36 found GEX tells day size only. Ch. 9 uses the density as a monthly regime switch, which said "S1" 29 of 30 times,
  so in practice its trades were unconditional.
- Volatility forecasting (agree): Ch. 3, 10, 15, 17 all forecast the size of moves; R16, R41 and R88 found size predictable
  and direction not. The book adds tools we have not used: GARCH/BEKK, adaptive windows, calibration by P-P plot.
- Mean reversion of a spread (not tested): Ch. 14's rule fades extremes of a two-asset spread. R72's C2 laggard catch-up
  (intraday convergence) failed; daily spread fades on NIFTY/BANKNIFTY or GOLD/SILVER are untested.
- Costs (agree): Ch. 14's edge disappears for the weakest pair at 1% cost per share; R16 and R89 found costs decide most
  option-buying rules (an ATM round trip costs about Rs 98 / Rs 133 including the spread).
- Monte Carlo and bootstrap (consistent): our studies already use stationary bootstraps (H15, H16) and Monte Carlo of trade
  sequences (OBUY_* studies); Ch. 16's warning about rare payoffs applies to any far-wing estimate.

## What is new to us
- Risk-neutral density from the chain (Breeden-Litzenberger with a smoothed smile) and its skewness/kurtosis as daily
  features; implied probability of reaching a level by expiry.
- PCA / common PCA of IV changes: level, slope and twist factors for stress-testing an option book.
- The implied-versus-historical density comparison as a formal "volatility risk premium in the tails" measure.
- CUSUM / EWMA control charts with run-length design as an objective "this arm has broken" alarm.
- Hurst R/S with a simulated null band (and its upward bias), and a stop-and-reverse fade of spread extremes.
- Copulas with tail dependence (Student-t, Gumbel) for joint losses across open arms or instruments.
- Volatility-updated historical simulation and P-P calibration scores for our range forecasts.
- Adaptive (Lepski/Spokoiny) look-back windows; Kalman filtering of hedge ratios or noisy IV; BEKK multivariate GARCH.

## Could not read
- Figure 7.1 (the implied-tree construction diagram) came through as symbols only; all other figures are plots, of which
  only axis numbers and captions survived.
- Many formulas are garbled by the text extraction (subscripts and fractions split across lines), notably the Fourier
  inversion error bounds (Ch. 1.4), the copula formulas in Table 2.1, the state-price-density derivatives (Ch. 8.3) and the
  empirical-likelihood asymptotics (Ch. 12); the surrounding prose and all result tables were readable.
- Bibliographies were skimmed, not studied.
