# Financial Engineering and Computation: Principles, Mathematics, Algorithms - Yuh-Dauh Lyuu (2002)

Source: financial-enginneering-computation-principles-mathematics-al.txt, 648 pages (33 chapters, bibliography, index),
read in full on 25 Oct 2026 (text layer, pdftotext; every chapter read in order with running notes; the answers chapter
33 was read for Chs. 2-10 and 31 and scanned for the rest; formulas and figures came out garbled, see "Could not read").
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A Cambridge University Press textbook that joins finance theory, the maths behind it and the computer algorithms to run it.
It covers bonds and interest rates, options and the Black-Scholes model, futures, swaps, hedging, and stochastic calculus.
Every method comes as pseudocode: binomial and trinomial trees, finite differences, Monte Carlo, root finding, matrices.
About a third of the book is US fixed income: term-structure models, callable and convertible bonds, mortgage securities.
It ends with portfolio theory (Markowitz, CAPM, factor models) and value at risk, plus the author's web calculators.

There are no trading rules in this book. It is a pricing, hedging and computing manual. What it gives IraAlgo is
(a) the correct way to compute fair value, implied volatility and Greeks for NSE index and MCX options, (b) checks
that option prices must pass (useful against the stale prints R103 found), (c) the hedging arithmetic behind option
selling, (d) Monte Carlo, GARCH, PCA and VaR recipes, and (e) a few warnings that match what our research found.
The volatility-trading view of the same tools is in `research/books/bennett-trading-volatility.md`; the binomial
pricing view is in `research/books/fm-binomial-models.md`; this write-up does not repeat them.

## Methods, rules and setups (chapter by chapter)

### Ch. 1 Introduction
History (Markowitz, CAPM, Black-Scholes, Bachelier 1900) and a survey of markets and computers. One warning worth
keeping: every computation rests on inputs and assumptions that are at best approximations, and LTCM (run with two
Nobel laureates' maths) needed a US$3.6 billion rescue in 1998. No method.

### Ch. 2 Analysis of algorithms
Big-O complexity and the book's pseudocode. Practical point: a speed-up matters even when one run is fast, because a
5-second saving becomes 5 minutes over 60 runs (relevant to anything the app recomputes every tick). No method.

### Ch. 3 Basic financial mathematics
Compounding (convert every rate to continuous as the common ground), annuities, loans, yields (IRR, NPV, holding-period
return with reinvestment risk), bonds, day counts and accrued interest. The useful part is root finding.

**Method 3.1 - Robust root finding for implied volatility (Ch. 3.4.3; used again in 9.4.3)**
- What it computes: the input (a yield, or an option's implied volatility) that makes a model price equal a market price.
  Bisection always converges if the root is bracketed (halves the bracket each step). Newton-Raphson converges
  quadratically (digits double each step) but needs the derivative and can fail without a good start; the secant
  method (rate 1.618) needs no derivative; Ridders keeps the root bracketed. Cap the number of iterations.
- Inputs: market option price, spot or future, strike, time, rate; vega for Newton.
- How we could use it: the app's IV calculation should run Newton with a bisection fallback; Black-Scholes price is
  monotone in volatility (vega > 0, Ch. 10), so a bracket [0.01, 3.0] always works.
- Signal source: option premium (it reads the option's own price); a computation, not a trade. Maps to NSE index
  options and MCX options (on futures, with the Black model, Ch. 12); crypto options likewise.
- Mechanical? yes.
- Our research: not a study; R102 (`DEEP_NIFTY_BANKNIFTY.md` 3.4) warns not to read IV in expiry's last minutes
  (ATM IV prints 6.3 NIFTY / 14.4 BANKNIFTY at 15:15).

### Ch. 4 Bond price volatility
Duration (Macaulay, modified, effective), convexity, immunization and the dollar-duration hedge ratio. Two general ideas
carry over to options: "effective" sensitivities computed by bumping the input up and down and repricing (central
difference), and the second-order (convexity) term, which is gamma's twin. Bond-only otherwise.

### Ch. 5 Term structure of interest rates
Spot rates, bootstrapping them from coupon bonds, forward rates and how to lock them in, forward default probabilities
from credit spreads, and the expectations / liquidity-preference / segmentation theories (the yield curve slopes up
about 80% of the time, so pure expectations cannot hold). For us only the rate input to option models. No method.

### Ch. 6 Fundamental statistical concepts
Moments, covariance and correlation matrices, the normal and lognormal distributions, regression and R-squared,
least squares, maximum likelihood and the method of moments. Two small practical items: a fast polynomial
approximation of the normal distribution function N(z) (for on-device Black-Scholes) and the recipe for two correlated
normal draws (for simulating NIFTY and BANKNIFTY together). Tools only.

### Ch. 7 Option basics
Calls, puts, intrinsic and time value, exchange-traded options, and payoff diagrams for the standard positions:
protective put, covered call, cash-secured put, ratio hedge, bull/bear spreads, butterfly, calendar, diagonal,
straddle (long volatility), strangle, strip and strap. The book claims more than 10% of holders let in-the-money options
expire worthless (US, cited). It gives payoffs, not entry rules; Bennett's write-up covers the structures for us.
Our research: DEEP_OPTIONS_SELLING.md already tabulates these structures for NIFTY with Black-Scholes numbers.

### Ch. 8 Arbitrage in option pricing
Model-free price bounds: longer-dated American options are worth at least shorter ones; a call's price falls with
strike, by no more than the strike gap; a call is worth at most the spot; put-call parity C = P + S - PV(K) (minus PV of
dividends); call prices are convex in strike (a butterfly cannot cost less than zero; the book checks this on Merck's
30/35/40 calls, 9.5 x 2 < 15.25 + 5.5); an option on an index is worth no more than the same basket of single-stock
options, the gap growing as correlation falls.

**Method 8.1 - No-arbitrage checks as a data-quality gate (Ch. 8.2-8.5)**
- What it computes: for one expiry, flags any strike where call prices are not decreasing, the slope exceeds one,
  the curve is not convex, or call minus put strays from future minus strike (discounted) by more than costs.
- Inputs: the chain's call and put prices at one minute, the same-expiry index future, a rate.
- How we could use it: before any study prices a wing from the lake, drop or reprice quotes that break a bound; R103's
  "passes" came from exactly such stale wings.
- Signal source: option premium (the chain itself); a filter, not a signal. Maps to NSE index options (European,
  cash-settled), MCX options on futures, crypto options.
- Mechanical? yes.
- Our research: R103 (`HUNT_R103_OPTIONS_SELLING.md`, "The stale-leg artefact"): repriced legs moved a mean -Rs 711 to
  -Rs 1,698 and all six design passes disappeared. The parity and butterfly trades themselves are in
  `fm-binomial-models.md` (rules `put-call-parity-arbitrage`, `butterfly-convexity-arbitrage`); not repeated here.

### Ch. 9 Option pricing models (the key chapter)
Teaches the binomial model: copy the option with delta shares plus cash, so its price is a discounted average of its
payoffs under the "risk-neutral" up-probability p = (R - d)/(u - d). The real-world probability of an up move never
enters, so a direction forecast does not change the fair price. A worked arbitrage: a call worth 85.069 sold at 90 locks
in 4.931 by delta-replicating. Then fast tree algorithms (one array, skip zero nodes, a linear-time sum for European
options, computed in logs), Monte Carlo on the tree, the limit to the lognormal and the Black-Scholes formula, the
binary call e^(-rT) N(d2), and the oscillating convergence of trees (fix: put the strike on a node).

**Method 9.1 - Black-Scholes with a dividend yield for index options (Ch. 9.3, 9.6.4, 11.4)**
- What it computes: European call/put values C = S e^(-qT) N(d1) - K e^(-rT) N(d2) and parity; for a broad index the
  dividends are treated as a continuous yield q (Merton). The book says the model fits a broad index better than a
  single stock. Option value is nearly linear in volatility at the money.
- Inputs: spot, strike, time in years, rate (the zero rate to expiry; rate uncertainty matters little under one
  year), dividend yield, volatility.
- How we could use it: fair value and Greeks in the app; repricing stale legs (R103 already does this).
- Signal source: computation on option premium and the underlying. Maps to NSE index options; for MCX use the Black
  model on the future (Method 12.1); crypto options likewise on the perpetual/futures price.
- Mechanical? yes.
- Our research: R103 Amendment 2 reprices stale exit legs with Black-Scholes at the leg's last IV, floored at intrinsic.

**Method 9.2 - Historical volatility, done right (Ch. 9.4.2)**
- What it computes: annualised standard deviation of log returns ln(S_i/S_(i-1)), divided by the square root of the
  bar length in years. The book's cautions: use log, not simple, returns; adjust for dividends and splits; bid-ask
  bounce and price discreteness bias the estimate; estimators that use the high and low have lower variance; evidence
  says leave out non-trading days and count only trading days in the time to expiry. With deterministic time-varying
  volatility the right input is the root-mean-square volatility over the option's life.
- Inputs: closes (or high/low) of the underlying.
- How we could use it: the realised-vol side of any "implied versus realised" comparison, and the size nowcast.
- Signal source: underlying price. Maps to NIFTY/BANKNIFTY, MCX futures, BTC/ETH.
- Mechanical? yes.
- Our research: R102 (`DEEP_NIFTY_BANKNIFTY.md` 2.2) computed both: the Parkinson high-low measure runs well below
  close-to-close every year (NIFTY 2025: 8.4 vs 11.8) because the overnight gap is a big share of daily variance.
  So a high-low estimator alone understates an index that gaps.

**Method 9.3 - Implied volatility and the smile (Ch. 9.4.3)**
- What it computes: the volatility that makes Black-Scholes match the market price (Method 3.1). The book's facts:
  options on one underlying show different IVs; the typical "smile" is lowest at the money and higher both ways, and
  strongest for short-dated options; averaging them into one composite IV is unsound; the smile itself shows the model
  "cannot be literally true". An implied binomial tree (Rubinstein) can price every listed option exactly.
- Signal source: option premium / volatility structure; option chain as context for an underlying signal.
- Mechanical? yes.
- Our research: R102 3.4: put ATM-5 IV exceeds call ATM+5 IV by a median +2.56 points (NIFTY) on 91% of days, so NSE
  shows a skew (crash-side premium) more than a symmetric smile. IV1 (`HUNT_IV1_OPTION_MARKET.md`): IV skew, straddle,
  PCR and OI alone as signals lost on all 5 indices.

Other points: American puts are priced on the tree with an exercise check at each node; known cash dividends make the
tree stop recombining (approximation: take the PV of dividends off the spot); Curran's diagonal method skips most
nodes for American puts. A note cites the weak-form efficient-markets view that chart reading cannot earn above-average
returns ("has stood up rather well"), and warns that wrong models and poor vol forecasts are a big risk for writers.

### Ch. 10 Sensitivity analysis of options (the Greeks)
Delta N(d1) (e^(-qT) N(d1) with a yield), put delta N(d1) - 1; delta-neutral hedging (hedge derivative 1 with
-delta1/delta2 units of derivative 2); theta (always negative for a European call, can be positive for a deep put);
gamma, the same for call and put; vega, positive and single-peaked in volatility; rho; the at-the-money option has the
largest time value.

**Method 10.1 - Greeks without numerical noise (Ch. 10.2)**
- What it computes: on a tree, delta from the two nodes after the first step and gamma from the three after the
  second (almost free); theta from the middle node two steps on. The book shows that bumping the spot and repricing
  ("numerical differentiation") FAILS on a binomial price: the price is piecewise linear in spot, so the bumped gamma is
  zero most of the time and huge near a kink, and shrinking the bump makes it worse. Fix: start the tree two steps
  before today so delta and gamma sit at time zero. Vega and rho still need two runs.
- How we could use it: in the app use closed-form Black-Scholes Greeks; if a tree or Monte Carlo is ever used, take
  Greeks from the tree nodes or with common random numbers (Ch. 18), never by bumping a tree price.
- Signal source: computation. Maps to NSE, MCX, crypto options.
- Mechanical? yes.
- Our research: not tested by us (no study depends on numerical Greeks).

### Ch. 11 Extensions of options theory
Firm value models (equity is a call on the firm, debt is riskless debt minus a put; shareholders gain from more
volatility at bondholders' expense), warrants, callable and convertible bonds; barrier options (knock-in plus
knock-out = vanilla; trees converge badly unless the barrier sits on a node layer; daily monitoring in practice);
caps and floors; stock index options (cash-settled; an American index option carries "exercise risk" because it settles
on today's close while a stock hedge trades tomorrow); currency options (foreign rate acts as a dividend yield; American
currency calls may be exercised early); compound options; Asian (average-price) options, which are never dearer than
vanilla, priced by Hull-White bucketed averages on a tree; lookback options. A note: index and constituents cannot both
be lognormal, because a sum of lognormals is not lognormal. Mostly pricing theory; none of it is a trade for us.

### Ch. 12 Forwards, futures, futures options, swaps
Forward price F = S e^(rT), with income (S - I) e^(rT), with a yield S e^((r-q)T); a new forward equals long call plus
short put at the forward price; futures differ by daily marking to market (futures = forward price if rates are not
random); basis = spot minus futures, converging to zero and less volatile than either price; currency swaps.

**Method 12.1 - Cost of carry and the Black model for options on futures (Ch. 12.3-12.4)**
- What it computes: index futures fair value F = S e^((r-q)T); for commodities F = S e^((r+u)T) at "full carry" for
  investment assets such as gold and silver, while consumption commodities can trade below it (convenience yield),
  giving "inverted" curves; options on futures are priced by the Black model (Black-Scholes with q = r and S replaced
  by F), so no dividend yield needs guessing. A European futures option equals the option on the cash asset when the
  maturities match. Options can be replicated with futures; the futures hedge ratio is smaller than the stock one.
- Inputs: spot, futures, rate, dividend yield / storage cost.
- How we could use it: price NIFTY options off the same-expiry future (removes the dividend guess); price MCX options
  (which are on futures) with Black; read MCX curve shape (crude, natural gas backwardation) as context.
- Signal source: option chain / futures as context; computation. Maps to NSE index options and futures, MCX
  (directly), crypto (perpetual basis = funding).
- Mechanical? yes.
- Our research: HUNT_H39.md (stage A proxy) found futures returns and basis changes tied to the index in the same minute
  but no lead from 2 minutes on (every |t| below 2.5). Index arbitrage (Rule 1 below) is not tested by us.

The book also states Keynes's normal-backwardation theory (hedgers are net short, so futures sit below the expected
spot and long speculators earn a premium) as theory with "some evidence". Not tested by us.

### Ch. 13 Stochastic processes and Brownian motion
Random walks, martingales (fair games; today's value is the best forecast of a martingale), the risk-neutral measure
as an equivalent martingale measure, the futures price as a martingale (an unbiased risk-neutral forecast of the spot),
Brownian motion as the limit of a random walk, geometric Brownian motion, the Ornstein-Uhlenbeck process, quadratic
variation. Theory; one or two lines suffice.

### Ch. 14 Continuous-time financial mathematics
The Ito integral and Ito's lemma; the Euler (and Milstein) discretisation used to simulate any price process; the
Ornstein-Uhlenbeck mean-reverting process dX = k(m - X)dt + s dW (mean m + (x0 - m)e^(-kt), long-run variance s^2/2k),
proposed for interest rates, volatility and returns; the square-root process. Two practical points: under proportional
transaction costs continuous trading is impossible (a Brownian path has infinite length, so continuous rebalancing
costs infinitely much), hence real hedges are discrete; and the expected log growth of a GBM is mu - sigma^2/2, below
the arithmetic mean return (volatility drag).

### Ch. 15 Continuous-time derivatives pricing
The Black-Scholes equation by Merton's hedge (short the option, hold delta shares, the portfolio is riskless for an
instant and must earn r). The stock's expected return drops out, so predictable drift does not change the fair price
(Exercise 15.2.2). Extensions: dividends, futures, averages, two-asset options, exchange options (Margrabe), currency,
quanto and cross-currency options, convertible bonds, the market price of risk for a non-traded factor, stochastic
volatility (Hull-White).

**Method 15.1 - Theta pays for gamma (Ch. 15.2.1, Eq. 15.3)**
- What it computes: for any option, Theta + r S Delta + (1/2) sigma^2 S^2 Gamma = r C; for a delta-neutral book,
  Theta + (1/2) sigma^2 S^2 Gamma = r C. Time decay is the price of gamma: a long-option holder pays theta to own
  gamma; a seller collects it and carries the gamma risk.
- How we could use it: Jarvis's explanation of decay versus movement; R102's straddle decay clock is this in numbers.
- Signal source: volatility / options structure. Maps to NSE index options, MCX options, crypto options.
- Mechanical? n/a (identity).
- Our research: R102 3.2: the NIFTY ATM straddle falls a median -59.3% from 14:30 to 15:29 on expiry day (89% of
  expiries); R89 (`FINDINGS.md`): buying straddles when a big candle is forecast made 0 of 18 versions positive
  because "options already price the expected move".

**Method 15.2 - Spread (relative) volatility for two correlated assets (Ch. 15.3.5, Margrabe)**
- What it computes: the volatility of the ratio of two assets is sqrt(s1^2 - 2 rho s1 s2 + s2^2); an option to exchange
  one asset for the other is Black-Scholes on the ratio with zero rate.
- How we could use it: size or price a NIFTY-versus-BANKNIFTY relative trade (the ratio's volatility is far below
  either index's when correlation is high).
- Signal source: underlying price (two underlyings). Maps to NIFTY/BANKNIFTY, MCX gold/silver, BTC/ETH.
- Mechanical? yes (as a measurement).
- Our research: R72 (`HUNT_R72_CROSS_INDEX.md`) traded the five indices together: Breadth Donchian passed, the
  laggard catch-up (pairs-like) idea failed (-Rs 1,00,746 final). Margrabe pricing itself not tested by us.

**Method 15.3 - Why Black-Scholes is biased (Ch. 15.5, a list of facts)**
- The book lists: volatility changes month to month; it mean-reverts (extremes return to average); it tends to fall
  as the price rises; out-of-the-money options and options on low-volatility assets are underpriced by Black-Scholes.
  Stochastic-volatility models improve pricing somewhat, but where Black-Scholes is reasonably supported the gain from
  complex models may be limited, and Black-Scholes still holds if all traders believe prices are lognormal.
- Signal source: volatility / options structure (context). Our research: R102's put skew (+2.56 points, 91% of days)
  and VIX falling on up candles (R88) fit "volatility falls as price rises".

### Ch. 16 Hedging
Hedgers, speculators and arbitrageurs; short and long futures hedges; basis risk; cross hedges.

**Method 16.1 - Minimum-variance hedge ratio and beta hedge (Ch. 16.2.3-16.2.4)**
- What it computes: the number of futures that minimises the variance of a hedged position is h = rho x sd(spot
  change) / sd(futures change) = Cov / Var, the slope of a regression of spot changes on futures changes (worked:
  0.9 x 0.4 / 0.3 = 1.2 so 240 soybean contracts for 1,000,000 bushels of corn). For a stock portfolio, short
  beta x portfolio value / futures contract value (worked: $2.4 million at beta 1.25, futures 1200 x 500, 5
  contracts); to move beta from b1 to b2 short (b1 - b2) x value / contract value.
- How we could use it: hedging one MCX metal with another (silver with gold), or sizing a NIFTY-BANKNIFTY pair.
- Signal source: underlying price (regression). Maps to NIFTY/BANKNIFTY futures, MCX, crypto.
- Mechanical? yes.
- Our research: not tested by us.

**Method 16.2 - Delta and delta-gamma hedging of a written option (Ch. 16.3)**
- What it computes: a worked weekly delta hedge of 10,000 written calls (strike 50, 4 weeks, volatility 30%, rate
  6%): the hedge is not self-financing; after the first week $171,622 could be withdrawn (the tracking error); over the
  life the replication cost 1,524,853 against the premium's future value 1,776,088, a gain of 251,235 on that one path.
  The tracking error is positive about 68% of the time though its mean is near zero, is proportional to vega, and
  stops shrinking beyond some rebalancing frequency. Adding a second option to make the book gamma-neutral cut the
  first-week error to $1,757 and the final result to a loss of 56,046. Delta-vega hedging beats delta hedging in
  practice. The answer to Exercise 16.3.3: a delta hedge of a short option loses whichever way the price moves
  (it buys after rises and sells after falls); the premium is the reserve that pays those losses.
- How we could use it: explains why selling options is "implied minus realised volatility", not free premium.
- Signal source: volatility / options structure. Maps to NSE index options only with futures in matching size.
- Mechanical? yes, for a desk; not at one lot.
- Our research: DEEP_OPTIONS_SELLING.md: a one-lot seller cannot delta-hedge (the NIFTY future's lot equals the
  option's lot, so a 0.2-delta hedge would be 13 units that do not exist); R103 therefore tested wings and stops, and
  0 of 24 defined-risk structures passed.

Static hedging of barrier and lookback options with vanillas, and a binary option as a tight bull call spread, close
the chapter.

### Ch. 17 Trees
Counting paths with the reflection principle gives linear-time formulas for barrier, binary and power options; tree
prices for barriers oscillate in a sawtooth because the barrier falls between nodes (choose n so the barrier lies on a
layer: at n = 191 the value is 5.63542 against the exact 5.6605); trinomial trees (Ritchken stretches the step so a layer
hits the barrier); correlated binomial and trinomial trees for two assets. A maxim worth keeping: compare algorithms by
actual running time, not by how few steps they need ("giraffes are not faster than cheetahs"). Computation only.

### Ch. 18 Numerical methods
Finite differences (explicit: stable only for small time steps and the same as a trinomial tree; implicit and
Crank-Nicolson: always stable, tridiagonal solve), Monte Carlo and quasi-Monte Carlo.

**Method 18.1 - Monte Carlo with variance reduction (Ch. 18.2-18.3)**
- What it computes: an expectation (price, risk, P&L distribution) as the average over simulated paths. The error
  falls as 1/sqrt(N) whatever the dimension, so narrowing a confidence interval by a factor f needs f^2 times the runs;
  the number of runs can be set from the central limit theorem. Two error sources: sampling (N) and path discreteness
  (step size). Plain Monte Carlo is poor for American-style exercise (one path cannot decide exercise; the "maximum along
  the path" estimator is biased high). Variance reduction: antithetic paths (reuse each draw with its sign flipped);
  control variates (subtract a similar quantity with a known answer: an arithmetic Asian option with the geometric one
  as control had sample standard deviation 0.000331789 against 0.009032505 for antithetic, both with 10,000 runs);
  conditioning; stratified and importance sampling; common random numbers when bumping for Greeks. Quasi-random
  (Halton, Sobol', Faure) points avoid clustering, with reported speed-ups up to 1,000, but lose their edge in high
  dimension or with non-smooth payoffs and have no error estimate.
- Inputs: a price model (GBM by the exact log step; Euler for others), correlation via Cholesky (Ch. 19).
- How we could use it: stress paths for an arm's P&L, drawdown odds under a sizing rule, pricing exotic payoffs.
- Signal source: computation on the underlying. Maps to all.
- Mechanical? yes.
- Our research: our studies use day-block bootstraps rather than parametric Monte Carlo (R103 gate 1; H15 sizing:
  "the bootstrap is optimistic" because it resampled only 10 good month-blocks). The book's warning that tiny samples
  and seeds matter applies equally to bootstraps.

### Ch. 19 Matrix computation
Gaussian elimination, LU and Cholesky factorisation, eigenvalues, principal components, the singular value
decomposition, least squares (the SVD route is numerically safer than the normal equations), weighted and generalised
least squares, factor analysis, cubic and B-splines.

**Method 19.1 - Correlated scenarios and principal components (Ch. 19.1-19.2)**
- What it computes: correlated normal draws as P y where C = P P^T (Cholesky) and y are independent normals;
  principal components ranked by variance (drop the small ones to cut a model's factor count). For US interest rates
  three factors (level, slope, curvature) explain more than 90% of moves. Splines: changing one data point changes the
  whole curve; end segments bend sharply, so extrapolation is unsafe.
- How we could use it: correlated NIFTY/BANKNIFTY/FINNIFTY paths for Monte Carlo; PCA of the IV surface or of the five
  indices' minute returns (how much is one common factor); smooth the smile across strikes.
- Signal source: underlying price / option chain as context. Maps to all.
- Mechanical? yes.
- Our research: R88 (`FINDINGS.md`) found NIFTY's same candle explains 51-90% of the other indices' candles, i.e. one
  dominant common factor. PCA itself not run by us.

### Ch. 20 Time series analysis

**Method 20.1 - What a price series can and cannot tell you (Ch. 20.1)**
- What it computes: maximum-likelihood drift and volatility of log returns. The key result: the error in the drift
  estimate depends only on the total time span, not on how often you sample; the error in the volatility estimate
  shrinks with the number of observations. In the book's simulation (true drift 0.105, volatility 0.30) 5,999
  observations at a step of 0.01 gave a drift of 0.118348 and a volatility of 0.299906; sampling less often but over a
  longer span (999 steps of 2) gave 0.108039. The closing notes repeat it: the drift is hard to estimate from discrete
  data over a short period however frequent; the diffusion can be estimated precisely.
- How we could use it: the theory behind "size is predictable, direction is not" and the reason intraday direction
  models struggle.
- Signal source: underlying price. Maps to all.
- Our research: R16 (`HUNT_R16_PREDICTABILITY.md`): the range of the next 30-60 minutes is predictable (q 0.003),
  direction is not (holdout AUC 0.509-0.536); R88 and R41 agree (direction AUC 0.510-0.517 for neural nets).

**Method 20.2 - ARCH / GARCH(1,1) volatility forecasts (Ch. 20.2)**
- What it computes: V(t)^2 = a0 + a1 (X(t-1) - mu)^2 + a2 V(t-1)^2 with a1 + a2 < 1: tomorrow's variance is a weighted
  average of past squared returns with the most weight on recent ones; long-run variance a0 / (1 - a1 - a2); the
  k-step forecast decays to it at rate (a1 + a2)^k (mean reversion). Fitted by maximum likelihood; mu is usually taken
  as zero for daily data. Also an AR(1) model on log volatility (fat-tailed returns) and the efficient-markets
  definitions (weak, semi-strong, strong; the book says major US markets are at least weak-form efficient).
- How we could use it: a daily expected-range input for stops and size, compared with VIX.
- Signal source: underlying price. Maps to NIFTY/BANKNIFTY, MCX, crypto.
- Mechanical? yes.
- Our research: GARCH itself not tested by us; R16 found next-day range not predictable beyond VIX, and R88's
  "last size + recent volatility" rule did most of the work of a full model.

### Ch. 21 Interest rate derivative securities
US T-bill, Eurodollar and Treasury-bond futures (conversion factors, cheapest to deliver, delivery options), FRAs,
yield options, caps and floors (puts on zero-coupon bonds; Black model), swaps and swaptions, index-amortising and
quanto swaps. Not relevant to IraAlgo beyond reusing the Black model.

### Ch. 22 Term structure fitting
Fitting a yield curve: linear interpolation (bumpy forwards), least squares weighted by bid-ask or duration, least
absolute deviation (robust to a few odd bonds), splines (bend at the ends), Nelson-Siegel (four parameters, bounded long
end). A general lesson: prefer an economically sensible curve that fits reasonably over one that fits perfectly but
makes no sense, and treat a fit within the bid-ask as exact. Analogous to fitting the NIFTY smile; not tested by us.

### Ch. 23 Introduction to term structure modeling
The lognormal binomial short-rate tree; calibrating it to today's curve by forward induction of state prices
(setting the expected short rate equal to the forward rate overprices bonds when rates are volatile); the
"differential tree" (price and its derivative computed together during backward induction, the author's method,
reused for implied volatility of American options); futures and bond options on the tree. Interest rates only.

### Ch. 24 Foundations of term structure modeling
Notation and identities linking zero prices, forwards and short rates; the local expectations theory as risk-neutral
pricing; the swap rate formula; the term-structure equation with a market price of risk that the model cannot pin
down (rates are not traded); the Heath-Jarrow-Morton drift condition; binomial examples (option on a zero, IO/PO
strips). Theory.

### Ch. 25 Equilibrium term structure models
Vasicek, Cox-Ingersoll-Ross, Ogden, Dothan, CKLS and two-factor models; a general trick to build a recombining tree
(transform to unit volatility first, as the stock tree does with ln S). The calibration section is worth quoting in
substance: time-series fitting cannot recover the risk premium, cross-sectional fitting gives parameters that are not
stable over time, and **calibration cannot fix a misspecified model**: a wrong model calibrated to today's prices still
misprices and mis-hedges, and should not be used for claims unlike the ones it was fitted to. One-factor models make all
maturities move together and hedge poorly. No method for us.

### Ch. 26 No-arbitrage term structure models
Ho-Lee, Black-Derman-Toy, Black-Karasinski, Hull-White (trinomial calibration), Heath-Jarrow-Morton and
Ritchken-Sankarasubramanian. The general criticism: these models fit today by re-estimating time-dependent parameters
every day, so "a new model is born every day" and their behaviour through time, and their hedges, are suspect; use at
most one time-dependent parameter. Interest rates only.

### Ch. 27 Fixed-income securities
Callable, putable, sinking-fund and convertible bonds (a convertible is a straight bond plus a call; a tree on the
stock prices it), floating and structured notes; valuation by yield spread, option decomposition and option-adjusted
spread (with a worked pitfall: valuing the embedded option on the callable tree gives 0.482 where the right answer is
0.873); key-rate durations (bump one point of the curve at a time; the sum approximates total duration). The key-rate
idea maps to bucketed vega by expiry on an IV surface. Otherwise not relevant.

### Ch. 28-30 Mortgage-backed securities and CMOs
US mortgage markets, prepayment (turnover, refinancing, burnout, seasonality; refinancing incentive measured by the
ratio of rates, not the difference), SMM/CPR/PSA conventions, pool cash flows, sequential, PAC and TAC tranches,
floaters and inverse floaters, IO/PO strips (IOs have negative duration), Monte Carlo OAS. Not relevant to IraAlgo.

### Ch. 31 Modern portfolio theory

**Method 31.1 - Diversification limits and mean-variance weights (Ch. 31.1)**
- What it computes: portfolio variance = systematic part + specific part. With uncorrelated assets the variance of an
  equal-weight portfolio falls like 1/n; with a common correlation z it is z s^2 + (1 - z) s^2 / n, so it can never go
  below the average covariance (Markowitz's "law of the average covariance"). Markowitz weights solve a quadratic
  programme; the tangency portfolio (maximum excess return per unit of risk, the Sharpe ratio) needs one linear solve.
  Exercise 31.1.5: an equal-weight mix of n independent assets with the same drift and volatility has a higher expected
  log growth than each asset by (1 - 1/n) sigma^2 / 2, so "volatility is not synonymous with risk".
- How we could use it: deciding how many arms on correlated indices really diversify a day's P&L.
- Signal source: computation on arm P&L. Maps to all.
- Mechanical? yes.
- Our research: R72: Breadth Donchian shares 77% of its trades with Donchian (daily correlation 0.80), so the two
  should not run side by side ("both together would double the same risk"). H4 (`HUNT_H4.md`) tried risk-parity
  weighting across arms.

**Method 31.2 - CAPM beta, factor models and the critique (Ch. 31.2-31.3)**
- What it computes: beta = Cov(asset, market) / Var(market) (regression of excess returns); only the systematic part of
  risk is priced; single- and multi-factor models and Ross's APT. The book's critique: expected returns cannot be
  estimated accurately (only variances and covariances can), and errors in means matter more than errors in variances,
  which matter more than errors in covariances; proxies for "the market" change the beta; one factor is not enough.
  Exercise 31.2.11: short-horizon returns are dominated by noise because the mean grows with time and the noise with its
  square root.
- Signal source: underlying price. Our research: not tested as such; consistent with R16/R88 on direction.

**Method 31.3 - Portfolio insurance with index puts (Ch. 31.2.2)**
- What it computes: to protect a portfolio of beta b and value V against falling below W per rupee, buy b V / (100 S)
  index puts (contract on 100 x index) at the strike where the portfolio would be worth W (worked: S = 1000, beta 1.5,
  floor 0.825 per dollar: 15 puts struck at 900). Dynamic replication with futures is cheaper but needs liquidity that
  vanished in the 1987 crash, when insurers' futures selling fed program selling in a loop.
- Signal source: option structure (hedge). Maps to NSE index puts for a stock portfolio; not part of our arms.
- Our research: not tested by us.

**Method 31.4 - Value at risk and stress tests (Ch. 31.4)**
- What it computes: the loss not exceeded with confidence c over m days: 2.326 standard deviations for 99% and 1.645
  for 95% under normality, times sqrt(m) for m days (the bank rule is 99% over 10 days); variance-covariance VaR for a
  portfolio; options by their delta (the "delta approach", questionable as m grows); Monte Carlo VaR (Cholesky
  correlated paths, sort, take the percentile); historical simulation (replay history); PCA to cut factors. The book's
  critique: returns have fat tails on both sides at daily and monthly horizons (returns are close to normal only at
  about 3 months); VaR depends heavily on parameters, data and method; it is not sufficient to control risk, and stress
  tests (losses under specified extreme moves) are needed. The chapter's epigraph quotes The Economist (1999) that
  users of VaR models were "crucified".
- How we could use it: Jarvis's "how bad can today get" number, with a stress overlay from our worst days.
- Signal source: underlying price / position Greeks. Maps to all.
- Our research: R102 (`DEEP_NIFTY_BANKNIFTY.md` 2.1): NIFTY daily returns 2006 - 14 Jul 2026 (5,087 days) have
  kurtosis 14.1 and a worst day of -12.98 (23 Mar 2020), far fatter than normal; DEEP_OPTIONS_SELLING.md section 5 is a
  stress table of the naked 09:20 straddle on the biggest days. The account stop (-Rs 40k / -Rs 13k a month) is a
  stress limit, not a VaR.

### Ch. 32 Software
The author's web calculators ("The Capitals", Java applets) and a note that Monte Carlo parallelises well. No method.

### Ch. 33 Answers to selected exercises
Worked solutions; the useful ones are quoted above (Exercise 16.3.3 on why a hedged seller loses on every move,
31.1.5 on diversification and log growth, 31.2.11 on short-horizon noise, 9.2.14 on computing tree probabilities in
logarithms).

## Performance claims
- Black-Scholes "ranks as the most successful theory in finance and economics" in explaining data (cited, Ch. 7).
- More than 10% of option holders let in-the-money options expire worthless (cited US study, Ch. 7). No data given.
- Weak-form market efficiency "has stood up rather well"; major US markets are at least weak-form efficient (cited).
- Stochastic-volatility models give "some pricing improvement" (cited); gains may be limited where Black-Scholes fits.
- Firms that manage risk are less risky and perform better (cited, Ch. 16). No numbers.
- Quasi-Monte Carlo speed-ups up to 1,000 over Monte Carlo reported (cited), but mixed: antithetic beat it for bonds.
- Three factors explain more than 90% of US rate moves; parallel shifts explain more than 80% (cited).
- "No consistent performance for mutual funds" (cited in further reading).
None of these is a trading result; the book makes no profit claims of its own.

## Risk, sizing and psychology
Risk is treated mathematically: standard deviation and its systematic/specific split, beta, duration and convexity,
Greeks, hedge ratios, VaR and stress tests. Main messages: hedges are discrete and never perfect (tracking error,
costs make continuous hedging impossible); written options are paid for by hedging losses; diversification cannot go
below the average covariance; expected returns are the least reliable input; normal-distribution risk numbers miss fat
tails; a calibrated model is not a correct model. There is no position-sizing rule (no Kelly) and no psychology; the
further reading mentions prospect theory's loss aversion.

## How it relates to our research
- **Agrees: size, not direction.** Ch. 20's result (drift precision depends on time span, not sampling; volatility is
  precise) and Exercise 31.2.11 (short horizons are noise) are the textbook form of R16, R41, R88 (direction AUC near
  0.51, range predictable). Ch. 9 and 15: the fair option price does not depend on direction or drift at all.
- **Agrees: options price the move.** Theta pays for gamma (Ch. 15); R89's straddles were about zero gross and lost
  to costs (0 of 18).
- **Agrees: selling is implied minus realised.** Ch. 16's hedging arithmetic matches DEEP_OPTIONS_SELLING.md; R102
  shows VIX above NIFTY's forward realised on 81% of days (median ratio 1.28) but the one-lot seller cannot hedge;
  R103: 0 of 24 defined-risk structures.
- **Agrees: fat tails.** Ch. 31's VaR critique matches R102's kurtosis 14.1 and the stress table in
  DEEP_OPTIONS_SELLING.md.
- **Useful check we under-use:** Ch. 8's model-free bounds would have flagged R103's stale wings before any P&L.
- **Already rejected / not relevant:** the book's strategies (spreads, straddles, covered calls) are payoffs without
  entry rules; R103 tested the selling structures, R89 the long straddle. About 40% of the book (rates, MBS) has no
  NSE use.
- Bennett's book covers the same Greeks and volatility-premium ideas from the trader's side; fm-binomial-models.md
  covers trees and parity rules.

## What is new to us
- Price NIFTY options off the future with the Black model (no dividend guess) and MCX options likewise (Ch. 12).
- Robust IV solving (Newton with bisection fallback) and Greeks from closed forms, never by bumping a tree (Ch. 3, 10).
- No-arbitrage bounds (monotone, slope at most one, convex, parity) as an automatic lake-quality filter (Ch. 8).
- Control variates and common random numbers for any Monte Carlo we run (Ch. 18).
- GARCH(1,1) as a daily range/vol baseline next to VIX (Ch. 20); Cholesky/PCA for multi-index scenarios (Ch. 19).
- Minimum-variance and Margrabe spread volatility for any pair (NIFTY/BANKNIFTY, gold/silver) (Ch. 15, 16).
- VaR with an explicit stress-test overlay for Jarvis's risk statements (Ch. 31).
- Calibration cannot fix misspecification; re-fitting parameters daily hides model failure (Ch. 25-26): applies to
  any parameter we re-tune each morning.

## Rules file
One mechanical rule is registered (`rules.yaml`): index-futures fair-value arbitrage (Ch. 12.3.3). The book's other
"strategies" are payoff descriptions; parity and butterfly arbitrage are already registered from fm-binomial-models.

## Could not read
The text layer is complete, but every formula, matrix and pseudocode block came out garbled (subscripts, fractions,
integrals and Greek letters scrambled; figures are captions only), so formulas above are rebuilt from the prose and
standard results, and plotted figures (e.g. Figs. 9.10, 10.1-10.5, 29.6-29.20) could not be read. The answers chapter
(Ch. 33) was read in full for Chs. 2-10 and 31 and scanned for the rest. The bibliography and index were not read.

## R104 results (25 Oct 2026)

Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in `research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:

- `book-fmlyuu-01`: not testable (R104): no minute ETF/basket prices, dividend schedule or futures quotes
