# Computational Finance: Numerical Methods for Pricing Financial Instruments - George Levy (2004)

Source: computational-finance-numerical-methods.txt, 459 pages, read in full on 25 Oct 2026 (pdftotext text layer, preface
to index: Parts I-III, chapters 1-23 and appendices A-L; formulas came through garbled and every figure is an image, see
"Could not read").
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A practitioner's numerical-methods textbook (Butterworth-Heinemann / Elsevier) by a numerical-library developer, written in the style of Numerical Recipes with C, VB and Fortran code.
Part I (chapters 1-7) is pure software: calling C/Fortran numeric libraries (NAG) from Visual Basic, Excel, C#, web pages and XML.
Part II (chapters 8-13) prices options: Black-Scholes and Greeks, American options by approximations, binomial lattices, implied (local-volatility) trees, finite-difference grids, Monte Carlo and quasi-random numbers, multi-asset options, and filling missing data.
Part III (chapters 14-23) is volatility econometrics: stylised facts of returns, stale prices, bid-ask bounce, stochastic volatility, fat-tailed distributions, and above all GARCH (linear, asymmetric, Student-t), how to fit it, test the software and choose a model.
The author says plainly that it is not a book about trading derivatives and that he has no trading experience; there are no entry, stop or exit rules anywhere.

## Methods, rules and setups

The book teaches no trading rules. It teaches how to compute prices, Greeks, implied volatility and volatility forecasts,
and how to check that the numbers are right. Chapters are summarised in order; methods a trader of NSE index options, MCX
or crypto could use get a full block. NSE index options (and NSE stock options) are European and cash-settled, MCX
options are European options on the future, and Delta Exchange crypto options are European, so the long American-option
material is mostly of no use to us and is kept short.

### Part I - software (chapters 1-7)
- **Ch. 1 Introduction.** Split a numeric component (C/Fortran) from its user interface (VB, C#, Excel). No finance.
- **Ch. 2 DLLs.** Data types, null pointers, row versus column order when passing matrices between languages; examples
  are a singular value decomposition, a bounded quadratic programme and summary statistics. No finance.
- **Ch. 3 ActiveX/COM.** How components expose properties and methods; scripting them on a web page. No finance.
- **Ch. 4 A pricing control.** An American put priced on a 10-step lattice, with and without a control variate; the
  control-variate version is the more accurate (see Ch. 10.4.3).
- **Ch. 5 Optimisation components.** A ray-tracing demo (not finance) and a 4-asset Markowitz minimum-variance frontier
  (15 points) on a web page; an Excel optimiser that prints its iterations. See method 13.
- **Ch. 6 XML/XSL.** Storing share prices and a portfolio report in validated XML. No finance content.
- **Ch. 7 Wrapping C in C++ for .NET.** Cumulative normal, eigenvalues, integration and optimisation as callable classes.

### Chapter 8. Introduction to options and Brownian motion
History of Brownian motion; geometric Brownian motion (relative, not absolute, price changes, which is why Bachelier's
absolute model failed); Ito's lemma in one and many dimensions. Key fact used everywhere later: under the model the log
price change over a short step is normal with variance proportional to the step. Theory only.

### Chapter 9. Analytic methods for single-asset European options

#### 1. Black-Scholes price and Greeks (Ch. 9.3.1-9.3.3, App. C)
- What it computes: the European call/put price with a continuous yield or known cash dividends, and closed-form
  delta, gamma, theta, rho and vega (call delta = N(d1) times the yield discount; gamma and vega are the same for a put
  and a call). The PDE is derived from a delta-hedged portfolio; any mix of options (American included) obeys it.
- Inputs: spot (or, for options on a future, the future with the yield set to the rate), strike, time, rate, yield,
  volatility. N(d2) is read as the risk-neutral chance of finishing in the money.
- Book's check: put-call parity on its own table (S = K = 100, r 10%, vol 30%, yield 6%, 1 year: call 12.952, put
  9.260, both sides of parity 103.436).
- How we could use it: this is already how R103 repriced stale wings (Black-Scholes at the leg's last IV) and how
  DEEP_OPTIONS_SELLING.md builds its illustration table. The book's code is a reference implementation to unit-test
  ours against (its Tables 9.1-9.2 give values and Greeks to 3 decimals for maturities 0.1-1.0 years).
- Signal source: volatility / options structure (pricing, not a signal); maps to NSE index options directly, MCX options
  (price on the future), crypto options (Delta India European).
- Our research: used, not a test. R103 (`HUNT_R103_OPTIONS_SELLING.md`) found its Black-Scholes repricing of legs that
  did print runs 1-5 points high, which "if anything flatters the seller".

#### 2. Put-call parity and dividends (Ch. 9.2)
- What it computes: call + discounted strike (+ PV of cash dividends) = put + spot (spot discounted by the yield for a
  continuous yield). Proved by comparing two portfolios at expiry.
- How we could use it: a synthetic forward (strike + call - put) and its gap to spot is the market's carry/basis; a
  parity break in our minute data flags a stale leg.
- Signal source: option chain as context; NSE index options yes, MCX options on futures yes, crypto yes.
- Our research: h29 (`HUNT_H29.md`) built exactly this synthetic forward (median of strike + call - put near the money)
  and its basis jumps as signals: "NO. Option prices do not tell a buyer which side to buy"; h38 (`HUNT_H38.md`) found
  futures basis gives no option-buying rule after costs.

#### 3. Historical volatility and its standard error (Ch. 9.3.4)
- What it computes: annualised standard deviation of log returns; its standard error is about the estimate divided by
  the square root of 2(n-1), so 21 daily returns give roughly +-15% relative error.
- How we could use it: attach that error band whenever Jarvis quotes realised volatility or compares it with VIX.
- Signal source: underlying price; maps to NSE index spot/futures, MCX futures, crypto.
- Our research: R102 (`DEEP_NIFTY_BANKNIFTY.md` 2.2) uses close-to-close and Parkinson high-low realised volatility;
  India VIX sat above the next 21 days' realised NIFTY volatility on 81% of days (median ratio 1.28, n 4,507 days).
  Bennett's write-up (`bennett-trading-volatility.md`, method 26) covers the other estimators; not repeated here.

#### 4. Implied volatility by Newton's method (Ch. 9.3.4)
- What it computes: the volatility that makes Black-Scholes equal the market price, iterating with vega as the slope.
- Book's warnings: first check the price against its intrinsic floor (discounted forward minus strike); if the option
  is at or near that floor the problem is ill-posed. Newton fails when vega goes to zero (far from the money, tiny
  volatility, last minutes); bound the search (the code takes lower/upper bounds) and cap the iterations (50).
- How we could use it: our own IV for strikes Dhan does not give, and a guard that refuses to report IV when vega is
  tiny.
- Signal source: option premium used only to back out IV (context); maps to NSE, MCX options, crypto options.
- Our research: R102 (3.4) says the expiry-day 15:15 ATM IV print (6.3 NIFTY / 14.4 BANKNIFTY) is meaningless: "do not
  read IV there", which is the book's vega-to-zero failure seen in our data.

#### 5. Barrier options (Ch. 9.4, App. G)
Closed forms for down-and-out and up-and-out calls by the reflection principle; knock-in = vanilla minus knock-out
(in-out parity); an up-and-out call with strike at or above the barrier is worthless. Not traded on NSE/MCX; useful only
as intuition that a stop-loss is a knock-out barrier on the position. Signal source: none (pricing). Not tested by us.

### Chapter 10. Numerical methods and single-asset American options

#### 6. American-option shortcuts (Ch. 10.2-10.3)
Perpetual American put/call formulas (Merton); an American call with no dividends is never exercised early. For cash
dividends: Roll-Geske-Whaley (find the critical ex-dividend price by Newton) versus Black's "max of two Europeans"
(Table 10.1: 12.111 vs 12.048 at S = K = 100). MacMillan / Barone-Adesi-Whaley quadratic approximation: the
early-exercise premium also obeys the PDE; drop a small term and solve for the critical price (call errors about 0.001,
put errors up to about 0.05 against a 2,000-step lattice; the put's exercise boundary rises from 82.15 at one year to
93.43 at 0.05 years). Of no use for European NSE/MCX/crypto options. Not tested by us.

#### 7. Binomial lattice, with control variate and Black-Scholes smoothing (Ch. 10.4)
- What it computes: Cox-Ross-Rubinstein tree (up = exp(vol x sqrt(dt)), down = 1/up, probability from the carry); the
  up-probability must lie in [0, 1]. A recombining tree needs only 2n+1 distinct prices (1,000 steps: 2,001 values,
  not 2,003,001 nodes). Greeks from the first two steps; vega by bumping volatility in two trees.
- Accuracy tricks: the error oscillates with odd/even step counts; a control variate (lattice American minus lattice
  European plus Black-Scholes European) cuts it; "BBS" uses Black-Scholes for the last step and "BBSR" adds Richardson
  extrapolation (4/3 of the 2n-step value minus 1/3 of the n-step value). At 100 steps the American call error falls
  from 2.99e-2 (plain) to 7.82e-3 (BBS) to 2.96e-3 (BBSR).
- How we could use it: only for early-exercise products we do not trade; for European options Black-Scholes is exact.
  The general lesson (check a numeric method against a closed form on the same inputs) applies to our backtester's
  option repricing.
- Signal source: none (pricing). Not tested by us.

#### 8. Implied (local-volatility) trees: Derman-Kani, Barle-Cakici, Chriss (Ch. 10.5)
- What it computes: a binomial tree whose node prices and probabilities are solved, level by level, so that it
  reprices the market's European calls (above the centre) and puts (below) at every strike and expiry: the volatility
  smile turned into a local volatility at each node (node local vol = log(up/down) x sqrt(p(1-p)/dt)).
- Book's facts on the smile: implied volatility falls as the strike rises (skew) and rises with maturity (term
  structure). Practical cautions: bad probabilities appear when a node price falls outside the two neighbouring
  forwards; repair by keeping the previous level's log spacing, or (Barle-Cakici) centre on the forward, use forwards
  as strikes and take the midpoint of the forwards. Count the bad nodes and check the tree reprices its inputs.
  Arrow-Debreu prices, inflated at the riskless rate, must sum to 1 at each level.
- Inputs: an interpolated price for every strike and expiry. Our lake keeps ATM +-10 strikes, nearest expiry only, so
  a full surface is not available; a one-expiry smile is.
- How we could use it: a risk-neutral distribution of the expiry close from the NIFTY/BANKNIFTY chain, to compare with
  R102's realised distribution (put side dearer on 91% of NIFTY days). Context for Jarvis, not a trade.
- Signal source: option chain as context / volatility structure; maps to NSE index options (weekly chain), MCX options
  thinly, crypto options (Delta) partly.
- Our research: IV1 (`HUNT_IV1_OPTION_MARKET.md`) found skew, straddle and PCR alone lose on all 5 indices; nobody has
  built a local-volatility tree. Not tested by us.

#### 9. Finite-difference grids (Ch. 10.6, App. L)
Solve the Black-Scholes PDE on a price-time grid: explicit (a trinomial tree, unstable unless steps are tiny), fully
implicit (always stable on the log-price grid, App. L) and Crank-Nicolson (half-half). Put the spot on a grid line, and
for barriers put a grid line on the barrier: with a uniform grid the barrier error oscillates with its distance to the
nearest line; a non-uniform grid cuts it more than fivefold. Working in log price gives constant coefficients and
smaller errors (American put, 50x50 grid: 8.05e-2 plain vs 1.85e-2 log at 0.5 years). A double knock-out call (barriers
90/140) converges to 1.4584 by 200 steps, where Boyle-Tian's trinomial method is still at 1.4581 after 5,000; the grid
also accepts a time-varying volatility. Pricing machinery only. Not tested by us.

#### 10. Stochastic (random-tree) lattice for early exercise (Ch. 10.7)
Broadie-Glasserman: simulate b branches per node, work back taking the better of exercise and continuation, average
over trees (a biased-high estimator). Its value is that the random draw can be any fat-tailed or time-varying return
model. Table 10.13: one 250-branch tree (5.720 vs true 5.731) beat the average of 100 50-branch trees (5.830).
Not relevant to European options. Not tested by us.

### Chapter 11. Monte Carlo simulation

#### 11. Monte Carlo with pseudo- and quasi-random numbers, and correlated fat-tailed draws (Ch. 11, 12.3)
- What it computes: an expectation (option value, risk number) as an average over simulated paths. Error shrinks as
  one over the square root of the number of paths, so each extra decimal costs 100 times the paths. Low-discrepancy
  (quasi-random) Sobol and Niederreiter sequences fill space evenly and converge much faster (Faure is the weakest).
  Correlated normals come from a Cholesky factor of the covariance matrix; if the matrix is not positive definite
  (high correlations, rounding) use its eigen-decomposition and keep only the positive eigenvalues. Correlated
  lognormal and multivariate Student-t draws are built the same way; with 4.5 degrees of freedom the scatter has many
  more tail points than the normal, with 25.5 it looks normal.
- Book's evidence: on a 3-asset basket (Table 12.1, 3,000 paths) Sobol was off by 0.007 on a 22.672 call, pseudo-random
  by 0.133.
- How we could use it: our studies use bootstrap resampling of real trade P&L (h15's sizing study, R103's bootstrap p);
  for forward-looking risk (one-lot drawdown, account stop hit probability, the -Rs 13k month) a correlated Student-t
  simulation of the 5 indices and MCX contracts would add tail scenarios that the sample never saw. Quasi-random draws
  make such a simulation stable at a few thousand paths, which matters on a phone.
- Signal source: none (risk tool); maps to NSE, MCX, crypto alike.
- Our research: bootstrap is used widely (e.g. `HUNT_H15.md`: P(drawdown >= 50%) 14% for half-Kelly, 1% for quarter-
  Kelly, with the warning that the bootstrap flatters because it resamples 10 good months); parametric Monte Carlo
  with fat tails and quasi-random numbers: not tested by us.

#### 12. Portfolio frontier by quasi-random sampling, with transaction costs (Ch. 11.2.1, 5.2)
- What it computes: draw 500,000 Sobol weight vectors inside each asset's bounds, keep those summing to one, compute
  return (net of buy cost 0.07 and sell cost 0.04 per unit traded from the current holding) and variance, and plot; the
  upper-left edge is the efficient frontier. Works when costs make the objective non-smooth, where gradient optimisers
  fail. Ch. 5 solves the cost-free Markowitz problem directly.
- How we could use it: allocating lots across our arms with a real cost of changing allocation; Jarvis could show the
  risk/return cloud of arm mixes. The inputs (expected returns) are the weak point.
- Signal source: none (allocation); maps to any set of arms.
- Our research: not tested by us. R72 (`HUNT_R72_CROSS_INDEX.md`) shows why covariance matters: Breadth Donchian's
  trades are 77% Donchian's own (daily correlation 0.80), so running both doubles one risk.

### Chapter 12. Multi-asset European and American options
Options on the maximum or minimum of 2-4 assets. Closed forms for two assets (Stulz/Johnson, bivariate normal); max +
min call = the two vanilla calls; puts via a parity with zero-strike options. Multi-asset binomial lattices with 2^k
branches whose probabilities carry the correlations (check every probability stays in [0, 1] when correlations are
high or negative); memory limits lattices to about four assets. A truncated lattice drops the low-probability wings
(Table 12.5: 1.3e-5 error in 30 ms versus 280 ms for the full tree) and a recursive lattice refines the last step.
Monte Carlo handles many assets, fat tails, path dependence and GARCH dynamics, but not early exercise. Rainbow options
do not trade in India; no method block. Not tested by us.

### Chapter 13. Missing data

#### 13. Filling gaps with iterative regression (MREG) or the EM algorithm (Ch. 13)
- What it computes: an estimate of the mean and covariance of several series when some values are missing (different
  exchange hours, feed faults, holidays). Naive fixes are to carry the last value forward or to drop every row with a
  gap; the first understates volatility, the second throws away the other series' information. MREG regresses each
  column on the others and refills until the means settle; EM replaces each missing value with its conditional normal
  mean given the known values, then re-estimates the covariance (West's one-pass update).
- Book's evidence (8 series, 5-20% missing at random): both give similar accuracy; EM is far faster on long samples
  (300,000 rows, 20% missing: 48,369 ms vs 195,611 ms).
- How we could use it: a cross-index or MCX covariance when one series is missing minutes (MCX evening hours versus NSE,
  holidays), and as a better fill than forward-filling stale option prints. Our lake rule flags repeated prints; EM would
  estimate rather than freeze.
- Signal source: none (data cleaning); maps to NSE, MCX, crypto data.
- Our research: `HUNT_LAKE_STALE_PRINTS.md` flagged 9,646 stale one-minute option prices (0.03% of 35.4 million) and no
  verdict changed; R103's passes were forward-filled stale wings. EM/regression imputation: not tested by us.

### Chapter 14. Financial econometrics: introduction

#### 14. Stylised facts and what forecasts volatility best (Ch. 14 intro)
- Facts stated: returns have fatter tails than the normal; volatility clusters; bad news is followed by higher
  volatility; stock indices are negatively skewed (currencies have no natural sign).
- A quoted survey (Granger, 2002) of about 40 studies: GARCH beat historical volatility in 5 and lost in 5; implied
  volatility beat historical in 12 of 13 and beat GARCH in 13 of 14. Implied is best because it uses more information;
  GARCH and historical tie for second.
- How we could use it: VIX/ATM IV first, GARCH or a realised formula second, for "expected range".
- Signal source: option chain as context (implied) plus underlying price (realised); NSE yes (India VIX, ATM IV), MCX
  (option IV thin), crypto (Delta IV / DVOL).
- Our research: partly agrees. R16 (`HUNT_R16_PREDICTABILITY.md`): for the next day VIX already holds the information
  (the model beat VIX by 3-6% in design but not in the holdout); for the next 30 or 60 minutes a realised-range model
  beat the VIX-implied forecast by 10.4% to 15.0% in the holdout. MB1 beat the option market's own IV by 8-13% on the
  MCX holdout. Implied wins at a day or more; recent realised ranges win intraday.

#### 15. Log returns (Ch. 14.1)
Log returns add across periods and almost equal simple returns for small moves; scale by 100 for percent. Dividends
barely change returns. Housekeeping; no block.

#### 16. Non-synchronous trading (stale prices) (Ch. 14.2)
- What it says: if a security does not trade in some periods, its recorded return is zero then and catches up later.
  This leaves the mean unchanged but adds variance, creates negative autocorrelation in its own returns, and creates
  spurious lead-lag correlation with actively traded series. With a 0.75 chance of no trade per period, the average run
  of untraded periods is 3.
- How we could use it: a checklist item for every study that marks an illiquid leg at its last print or reads lead-lag
  between a thin series and the index.
- Signal source: none (data hazard); applies to far NSE strikes, thin MCX options, smaller crypto pairs.
- Our research: agrees strongly. R103's six design passes were wings marked at prints often 30+ minutes old; repriced,
  all lost. R102's only "confirmed" sign rule was 1-minute index momentum that is "index staleness" (+0.08-0.11 bps).

#### 17. Roll's bid-ask bounce and effective spread (Ch. 14.3)
- What it computes: when trades alternate between bid and ask, price changes show a lag-1 autocorrelation of minus
  one half (if the true price is fixed) and spurious volatility; the effective spread can be estimated as two times the
  square root of minus the lag-1 covariance of price changes. The traded spread can differ from the quoted one.
- How we could use it: estimate the real spread of an option strike from its minute closes alone, to check the slippage
  in `knowledge/baselines.yaml` (ATM round trip about Rs 98 NIFTY / Rs 133 BANKNIFTY) for strikes where we have no
  quotes; strip bounce before reading short-horizon "reversal".
- Signal source: option premium used only for cost estimation (not a signal); maps to NSE, MCX, crypto.
- Our research: not tested by us (our slippage comes from R16's cost test and Dhan quotes).

#### 18. Stochastic volatility, Levy jumps and the generalised hyperbolic (NIG) distribution (Ch. 14.4-14.6)
Stochastic-volatility models give volatility its own random shock (log volatility persists, coefficient above 0.95 in
studies); GARCH makes volatility a fixed function of the past; Levy processes add jumps. The five-parameter generalised
hyperbolic family (special cases hyperbolic and normal-inverse-Gaussian) fits return histograms almost perfectly: more
mass at zero, less in the flanks, more in the tails. GARCH-NIG adds clustering. Fit by maximum likelihood. Use: a
better tail model for the simulation in method 11. Signal source: underlying price; NSE, MCX, crypto. Not tested by us.

### Chapters 15-16. GARCH, EWMA and asymmetric GARCH

#### 19. GARCH(1,1), EWMA and GJR / AGARCH volatility forecasts (Ch. 15-16, 22)
- What it computes: tomorrow's (or the next bar's) variance as a constant plus a weight on the last squared surprise
  plus a weight on the last variance. Stationary if the two weights sum below 1; the long-run variance is the constant
  divided by one minus that sum. The k-step forecast decays geometrically from today's level to the long-run level at a
  rate equal to the sum: the closer to 1, the slower the mean reversion. GARCH with normal shocks still produces fat
  unconditional tails. EWMA is the special case with no constant (RiskMetrics' decay 0.97, per the book; integrated
  GARCH, no long-run anchor). GJR adds extra weight after negative surprises; AGARCH shifts the response curve;
  EGARCH works in log variance. GARCH-M lets variance enter the mean.
- Book's evidence: S&P 500, 3,000 daily returns 1960-72. Normal shocks: AR(1)-GJR-GARCH(1,1) preferred, alpha 0.0716,
  beta 0.7938, asymmetry 0.1851 (t 7.60), AR(1) term 0.2280 (t 11.94). Student-t shocks: plain GJR-GARCH(1,1) preferred,
  degrees of freedom 8.116, the AR term no longer significant. A currency series: AGARCH-I(1,1) best (alpha 0.128, beta
  0.852, normality statistic 0.575 against 1499.48 for ARCH(1)). The likelihood surface was smooth and convex, so fits
  did not depend on starting values. Leverage: equities and indices negative, commodities both ways, currencies none.
- Inputs: a return series (daily, or intraday bars with the time-of-day pattern removed first; the book only shows
  daily). Scale returns (x100) so the constant is not 1e-6 next to weights near 0.8.
- How we could use it: a forecast of the next 15/60-minute or next-day range on the underlying, to size stops, targets
  and lots (the LIVE rule allows only risk-lowering use), and for Jarvis's "expected move today". A volatility-targeted
  lot size (fewer lots when forecast variance is high) is the natural arm-level use.
- Signal source: underlying price/volume (VIX or ATM IV can be a regressor as option chain context); maps to NSE index
  spot/futures, MCX futures (commodity leverage either sign), crypto (24x7, fits well).
- Our research: GARCH itself not tested by us. The idea behind it is confirmed: R88 (`HUNT_R88_WHAT_MOVES_THE_CANDLE.md`)
  found the next candle 1.4x bigger after a volatile last hour (indices; 1.7x MCX) and 225 of 305 size findings held out
  of sample (0 of 8 direction findings); R16's 4-input formula (usual range, last 30 and 60-minute range, VIX) keeps most
  of the gain; R41's networks reached holdout R2 0.432 for the next-60-minute range. GARCH would be one more size
  model to compare, not a trade: R89 bought straddles on big expected candles and 0 of 18 versions made money.
  `HUNT_R74_STRATEGY_MAP.md` lists volatility-targeted sizing as "not tried for the index arms".

### Chapter 17. Conditional distributions for GARCH
Normal (kurtosis 3), Student-t (kurtosis 3(nu-2)/(nu-4): 9 at 5 degrees of freedom, 4 at 10, 3.375 at 20; below about 5
the tails are very thick, above about 20 almost normal) and the generalised error distribution (shape 1 = Laplace,
kurtosis 6; 2 = normal; very large = uniform). Gives each log-likelihood; corrects a published formula for the mean
absolute shock. Use: fit Student-t shocks for index returns (R102 measured NIFTY daily kurtosis 14.1, BANKNIFTY 8.5,
5,087 days). Theory plus formulas; no separate block.

### Chapter 18. Maximum-likelihood estimation
The likelihood is the product of one-step conditional densities; minimise minus its log. The inverse of the Hessian
(Fisher information) gives the standard errors; a t-statistic of 2.57 is significant at 0.5%, 0.52 is not (30%).
Optimisers: Newton, scoring, BHHH, quasi-Newton; impose the stationarity constraint; difficulty rises from ARCH(1) to
GARCH(2,2) and with long memory. Rescale data so parameters are of similar size. Standard statistics; no block.

### Chapters 19-20. Analytic derivatives and GJR-GARCH algorithms
First derivatives of the normal and Student-t log-likelihoods, expected Hessian blocks, and pseudocode for a regression
GJR-GARCH likelihood and gradient. Practical detail worth keeping: start the pre-sample variance at the mean squared
residual of the first few observations (as many as there are parameters), set pre-sample shocks and derivatives to zero.
Implementation mathematics only.

### Chapter 21. Testing GARCH software

#### 20. Simulate-and-recover testing of an estimator (Ch. 21)
- What it computes: generate 200 series from a known GJR-GARCH, fit each, and compare the mean estimate, the mean
  reported standard error and the actual spread of estimates with the truth.
- Book's evidence: with 300 observations and numeric derivatives the reported standard error of the shock weight was
  0.068 while the actual spread was 0.42 (far too confident); at 1,000 observations reported and actual agree (0.0336 vs
  0.0304) and at 3,000 they are tight. Analytic derivatives give better standard errors on short series; numeric ones
  are faster. Degrees of freedom of 4.2 came back as 4.46 at 800 observations with a large standard error.
- How we could use it: every new estimator in `research/brain` (range models, any GARCH) gets a synthetic
  known-answer test in pytest; and no GARCH fit on fewer than about 1,000 bars (four years of daily data, or a few weeks
  of 5-minute bars after removing the time-of-day shape).
- Signal source: none (method hygiene). Not tested by us as such; our pre-registration and locked holdouts serve the
  same purpose for trading rules.

### Chapter 22. Choosing a GARCH model
Likelihood-ratio test for extra parameters (twice the log-likelihood gain against chi-square); t-statistics; the squared
standardised residuals should be white noise (Box-Pierce Q); their skew and kurtosis give a normality statistic (below
about 3 is fine). Worked S&P 500 and currency cases are in method 19; there is also an Excel and a web-page demo. Use as
the acceptance test if we ever fit GARCH.

### Chapter 23. Multivariate volatility

#### 21. Orthogonal (principal-component) GARCH (Ch. 23)
- What it computes: a time-varying covariance matrix for many series without estimating every pair (100 assets have
  5,050 covariance terms). Take principal components of the returns, keep the few large ones, fit a univariate GARCH
  to each component's score, and rebuild the covariance from the fixed eigenvectors and the forecast component
  variances.
- How we could use it: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX move largely as one factor (R88: NIFTY's candle
  explains 51-90% of the others'); a one- or two-factor model gives the combined risk of several open arms, for a
  risk-lowering cap on total exposure.
- Signal source: underlying price; maps to the 5 NSE indices, the MCX metals (gold/silver pair), BTC/ETH.
- Our research: not tested by us. R72's Breadth Donchian uses the same "move together" fact as a filter.

### Appendices A-L
A: control definition file (software). B: binary cash-or-nothing and asset-or-nothing options, the exchange option
(independent of the interest rate) and look-back options. C: Greek derivations. D: truncated/recursive lattice code and
4-asset probabilities. E: conditional normal mean and covariance (used by EM). F: law of large numbers, central limit
theorem, and why the one-pass "mean of squares minus square of mean" variance is numerically unstable (use West's
running update). G: barrier integrals. H: AGARCH-I pseudocode. I-J: GED and Student-t moments. K: integrals, gamma
function, N(x) from erfc. L: the implicit scheme on the log grid is unconditionally stable.

## Performance claims
- No trading profit, win rate or return is claimed anywhere; the author says he has no trading experience.
- Numerical-accuracy claims, each with a table: Black-Scholes values satisfy parity to 3 decimals; Newton recovers
  implied volatility to about 1e-16; BBSR lattices beat plain lattices (100 steps: 2.96e-3 vs 2.99e-2); non-uniform log
  grids beat uniform grids and Boyle-Tian's trinomial method on barriers; Sobol beats pseudo-random Monte Carlo; EM
  matches iterative regression and is faster on big samples; analytic GARCH derivatives give better standard errors on
  short series.
- Empirical claims, sourced to others: the Granger survey (implied best); stochastic-volatility persistence above 0.95;
  the generalised hyperbolic distribution fits returns "almost perfectly"; the S&P 500 and currency GARCH fits above.
  Evidence is the cited studies and the author's own fits; nothing out of sample.

## Risk, sizing and psychology
Nothing on position sizing, Kelly, stops, drawdown or psychology. Risk appears only as volatility estimation (historical,
implied, GARCH, RiskMetrics' EWMA), the Markowitz minimum-variance portfolio with bounds and transaction costs, and
fat-tailed simulation. Value-at-risk is only in the bibliography (Jorion). For sizing our own work stands: `HUNT_H15.md`
found the Kelly fraction unstable (f* for BANKNIFTY 1.29 in the study window, 0.48 and 0.14 on other windows) and the
bootstrap optimistic; Boss's frame is fixed lots (`HUNT_FINAL.md`).

## How it relates to our research
- **Agrees: size is forecastable.** The whole of Part III rests on volatility clustering and mean reversion. Our R16,
  R41, R88 and MB1 confirm that the size of the next move is predictable out of sample on NSE indices and MCX, and C1
  on crypto; none of them used GARCH. The book never claims direction is forecastable, which also matches F1, R16, R30,
  R41, R88 and R102.
- **Partly agrees: implied versus realised.** The book's survey puts implied volatility first. R16 agrees for the next
  day (VIX holds the information) but finds recent realised ranges beat VIX for 30-60 minutes; R102 adds that VIX
  over-states NIFTY's realised volatility on 81% of days, so "implied is the best forecast" means best-ranked, not
  unbiased.
- **Agrees: stale prices manufacture results.** Lo-MacKinlay's non-trading model is exactly the R103 artefact (stale
  wings), the R102 index-staleness "momentum" and the lake stale-print audit.
- **Agrees: fat tails and negative skew.** R102: NIFTY daily kurtosis 14.1; puts five strikes out dearer than calls on
  91% of days. The book's leverage effect is the same thing seen in volatility.
- **Used already:** Black-Scholes repricing (R103, DEEP_OPTIONS_SELLING.md), put-call parity synthetic forward (h29),
  bootstrap significance in most HUNT studies.
- **Already rejected (as trades):** buying volatility on a high size forecast (R89, 0 of 18); option-chain structure as
  a stand-alone signal (IV1; GEX R28/R36 gives day size only). Nothing in this book is a direction signal, so nothing
  here reopens those.
- **Not covered by the book:** the volatility risk premium as a trade (see `bennett-trading-volatility.md`), Kelly, VaR,
  backtesting method, costs.

## What is new to us
- **GARCH / GJR / EWMA as a size model.** Never fitted in our research; one more candidate next to R16's 4-input
  formula and R41's networks for expected range and volatility-targeted, risk-lowering lot sizing. The GJR asymmetry
  (bigger range after a down move) is a specific, testable addition for indices; for MCX the sign may differ.
- **Fat-tailed, correlated simulation** (multivariate Student-t or NIG, quasi-random) of the arms' joint P&L, to price
  the chance of the -Rs 13k month and -Rs 40k drawdown beyond what the bootstrap sample contains.
- **Roll's spread estimator** to sanity-check slippage on strikes without quotes.
- **EM imputation** for cross-market covariance with missing minutes (NSE vs MCX hours).
- **Orthogonal GARCH** for the combined risk of several index arms open at once.
- **A local-volatility (implied) tree** from the nearest-expiry chain, as a risk-neutral distribution for Jarvis.
- **Simulate-and-recover tests** for every estimator in the Data Brain.
None of these is a trading rule; each is a measurement or risk tool.

## Could not read
- Formulas: the PDF's equations came through garbled (Greek letters and minus signs dropped, fractions and roots split
  across lines). Where a sign mattered (the S&P 500 AR(1) term, the currency asymmetry parameter, some table entries)
  the sign is uncertain; numbers above are given as printed.
- Figures: all 75 figures are images with only captions in the text (screenshots of the VB/Excel/web demos, error
  plots for lattices and grids, quasi-random scatter plots, implied-tree diagrams, GARCH residual plots, likelihood
  surfaces).
- The accompanying CD-ROM (full source code, RISK journal papers) is not part of the text.
