# Numerical Methods in Finance and Economics: A MATLAB-Based Introduction - Paolo Brandimarte (2006, 2nd ed.)

Source: numerical-methods-in-finance-and-economics-a-matlab-based-in.txt, 694 pages (12 chapters in 4 parts,
appendices A-C, index), read in full on 25 Oct 2026 (text layer, pdftotext; every chapter read in order, notes kept as
we went in the scratchpad). Most displayed equations came through garbled; the prose, the MATLAB code and the printed
numerical results are readable (see "Could not read").
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A graduate textbook (Wiley) on the computing behind pricing options and building portfolios, with MATLAB code throughout.
Part I recaps finance: bonds, utility, mean-variance portfolios, Value at Risk, stochastic calculus and Black-Scholes.
Part II is numerical analysis: rounding, linear systems, interpolation, root finding, quadrature, Monte Carlo, PDEs, optimisation.
Part III prices equity options three ways: binomial/trinomial lattices, Monte Carlo (with variance reduction) and finite differences.
Part IV covers dynamic programming, stochastic programming with scenario trees, and integer / non-convex optimisation.

There are no trading rules in the book. It is a toolbox: how to compute a fair value, a Greek, an implied volatility,
a tail probability or an optimal allocation correctly, and how such computations go wrong. Its most useful lessons for
us are about honest simulation (error bars, bias from deciding on noisy estimates, common random numbers) and two
small experiments: a stop-loss hedge that never converges to the option price, and an "optimal" allocation that swings
with sampling noise. The volatility-trading side of options is in `research/books/bennett-trading-volatility.md`, and
put-call parity, lattices and the Greeks in detail are in `research/books/fm-binomial-models.md`; neither is repeated.
Optimisation theory overlaps `research/books/fm-nonlinear-optimization.md`.

Our data, for the mapping: NIFTY weekly and BANKNIFTY monthly options (1-minute rolling chain, ATM +-10 strikes, IV and
OI), index spot / futures bars, India VIX daily, MCX futures and options, Delta Exchange crypto options. NSE index
options are European and cash-settled. Costs: about Rs 70 (NIFTY) / Rs 76 (BANKNIFTY) per option round trip.
`research/brain/bs.py` already has Black-Scholes prices, Greeks and an implied-vol solver.

## Methods, rules and setups (chapter by chapter)

### Ch. 1 Motivation
Why finance needs numerics: closed forms exist only for simple contracts; an option price is a discounted risk-neutral
expectation (so an integral, so Monte Carlo); American options need optimisation; calibration is a least-squares fit
of model prices to market prices. Three warnings with live examples: a computer stores 8.1 inexactly, so a rounding
function applied to 9 x 8.1 + 8.1 gives 80, not 81 (never test floats for exact equality); a "best return per rupee
first" rule for an all-or-nothing budget problem gave 17 where the optimum was 34; solving a badly conditioned system
(the Hilbert matrix, size 20) returns garbage with only a warning. The book says it ignores market microstructure
(bid-ask, costs) throughout. No method to test.

### Ch. 2 Financial theory
A tour of the theory the rest of the book computes. Practical pieces:

**Method 2.1 - Value at Risk and Conditional VaR (Ch. 2.4.5)**
- What it computes: VaR is a loss quantile over a horizon (95% or 99%). Under normal returns it is z x daily sd x
  square root of days x position (z 1.6449 at 95%, 2.3263 at 99%). Worked example: two assets weighted 2/3 and 1/3,
  daily vol 2% and 1%, correlation 0.7, 10 days, 10 million: VaR about 1,165,709. For options, a delta approximation
  keeps it normal; anything nonlinear or fat-tailed needs Monte Carlo, and dependence beyond correlation needs copulas
  (named, not covered).
- The book's two criticisms: VaR cannot tell a thin tail from a fat one beyond the quantile; and it is not subadditive:
  two bonds each defaulting with 4% probability each have 95% VaR of 0, but held together the VaR is 100, so
  "diversifying" raised measured risk. CVaR (the average loss beyond VaR) is coherent and easier to optimise.
- Inputs: position values, return distribution or scenarios. Signal source: none (a risk measure, not a signal).
- How we could use it: a CVaR of the open book (paper and live, option legs repriced on scenarios of the underlying)
  next to Boss's -Rs 40k / -Rs 13k stops; for short options the loss is all in the tail, which is exactly where VaR
  is blind. Maps to NSE, MCX and crypto alike.
- Our research: not tested by us. DEEP_OPTIONS_SELLING.md shows why tail measures matter: the naked BANKNIFTY 09:20
  short straddle had a worst day of -Rs 65,483 against a +Rs 33k / +Rs 37k two-year result.

**Method 2.2 - Mean-variance portfolios and utility (Ch. 2.4.1-2.4.4)**
- What it computes: minimise portfolio variance for a target mean (a quadratic programme), tracing the efficient
  frontier; a negatively correlated asset can cut risk below both assets' own; with a risk-free asset everyone holds
  one risky mix plus cash. Utility: concave = risk averse, risk premium about half x absolute risk aversion x
  variance; log utility has constant relative risk aversion, so the fraction in the risky asset does not depend on
  wealth (the book's binomial example) - the Kelly property, though the book does not use that name.
- Book's caveats: normality and symmetry fail for derivatives; correlations rise in crashes; covariances are not
  constant; costs, round lots and minimum weights need integer models (Ch. 12).
- Inputs: expected returns, covariance matrix. Signal source: none (allocation). Maps to: allocating capital across
  our arms (Liquidity, Donchian, R51/R52) rather than across assets.
- Our research: not tested as an arm allocator. The sizing studies (HUNT_H15.md) used Kelly fractions and found them
  unstable (see Method 10.2).

**Method 2.3 - Black-Scholes, the Greeks and the delta-gamma approximation (Ch. 2.6)**
- What it computes: the European call/put price; delta, gamma, theta, vega, rho; the change in an option for a move dS
  as delta x dS + half x gamma x dS squared. Example: a call at 4.8851 rises to 6.2057 when spot moves 50 to 52; the
  delta-gamma estimate (delta 0.6225, gamma 0.0392) gives 6.2086. A continuous dividend yield handles an index.
- Book's remarks: the formula assumes a complete market; costs, jumps and stochastic volatility break replication, so in
  practice it is used as an interpolation device through implied volatility, which depends on strike and maturity (a
  volatility surface). Delta hedging needs continuous rebalancing; the book prefers "an imperfect hedge for big moves
  over a perfect hedge for tiny ones".
- Signal source: volatility / options structure (pricing). Maps to NSE index options: yes (European, cash-settled;
  `bs.py` has it). MCX options are on futures (Black-76 form). Crypto options: yes.
- Our research: used, not tested as a trade. R103 repriced stale wing legs with Black-Scholes at the leg's last IV
  (HUNT_R103_OPTIONS_SELLING.md, Amendment 2); DEEP_NIFTY_BANKNIFTY.md 3.4 measures the surface the book describes:
  puts five strikes out dearer than calls on 91% of NIFTY days (n 1,222).

**Method 2.4 - Barrier options and the discrete-monitoring shift (Ch. 2.7.1)**
- What it computes: knock-in + knock-out = vanilla; a down-and-out put is much cheaper (0.5424 vs 4.0760 for the vanilla
  put at a barrier of 40 on spot 50); lower volatility can raise a barrier option's price. A barrier checked once a day
  is crossed less often than a continuous one: shift the barrier by exp(-0.5826 x vol x square root of the check
  interval) for a down barrier (0.5424 becomes 0.6380 with daily checks).
- How we could use it: our stops are barriers checked at discrete times on the premium or the underlying; the same
  correction says how much a stop checked every minute (or every 5-minute bar) under-states the true chance of the
  level being touched. Signal source: underlying price (stop on the underlying) or option premium (stop on premium).
- Our research: not tested by us.

**Method 2.5 - Price models: GBM, mean reversion, stochastic volatility, jumps (Ch. 2.5)**
- Log-returns normal gives lognormal prices; mean and variance of the log-return grow with time, the standard
  deviation with its square root. Ito's integral is non-anticipative: holdings are fixed before the return is seen (the
  mathematical form of "no look-ahead"). Mean reversion: drift a x (long-run level - current); stochastic variance that
  itself mean-reverts with a square-root noise term (Heston type); jumps by a Poisson process; fat tails acknowledged
  but not modelled. Interest-rate models (Vasicek, CIR, BDT, Hull-White) only outlined.
- Signal source: none (models). Our research: R102 measured the fat tails (NIFTY daily kurtosis 14.1, n 5,087 days);
  R16 / R41 / R88 found the size of the next move predictable and its direction not.

Pure theory in the chapter (one line each): bonds, compounding, present value, IRR, duration/convexity immunisation
(fixed-income, not our market); equilibrium vs arbitrage pricing; St. Petersburg paradox; Wiener process and Ito's
lemma derivations; Feynman-Kac; market price of risk; commodities that are not investment assets make markets
incomplete (relevant in spirit to MCX).

### Ch. 3 Basics of numerical analysis
Rounding and truncation, cancellation when subtracting near-equal numbers, stability (of an algorithm) vs conditioning
(of a problem): changing one coefficient of a degree-8 polynomial from 36 to 36.001 pushes roots into the complex plane.
Linear systems (Gaussian elimination, LU factorisation reused for many right-hand sides, Cholesky for covariance
matrices, tridiagonal solvers for PDEs, Jacobi / Gauss-Seidel / SOR, which converge only when the iteration matrix
has spectral radius below 1). Function approximation: high-degree polynomial interpolation oscillates (Runge's
example); Chebyshev nodes help; cubic splines are the safe default; a rational approximation of the normal CDF.

**Method 3.1 - Implied volatility by bisection or Newton (Ch. 3.4)**
- What it computes: the volatility that makes the model price equal the market price. Example: a call with spot 50,
  strike 54, 7% rate, 5 months, 30% vol prices at 2.8466; both a bracketing root finder and Newton recover 0.30, Newton
  slightly faster. Bisection is robust (needs only a sign change, linear convergence) but returns false zeros on
  discontinuous functions; Newton converges fast but only locally. Use relative stopping rules.
- Signal source: volatility / options structure. Maps to NSE, MCX, crypto options.
- Our research: already implemented as safeguarded Newton with bisection fallback in `research/brain/bs.py`, returning
  NaN outside the no-arbitrage band. DEEP_NIFTY_BANKNIFTY.md 3.4 warns not to read IV in the last minutes of expiry
  day (ATM IV prints 6.3 for NIFTY at 15:15).

**Method 3.2 - Splines, not polynomials, for a smile or a curve (Ch. 3.3)**
- What it computes: a smooth curve through observed points (e.g. IV against strike). A degree-4 polynomial through five
  points of a concave function wiggled and lost monotonicity; cubic splines do not oscillate. Book also notes splines
  for term structures and shape-preserving splines.
- How we could use it: interpolating IV for strikes outside the lake's ATM +-10 window before repricing a far leg (the
  R103 stale-leg problem), and reading Greeks off a grid. Signal source: volatility / options structure. Not tested.

### Ch. 4 Numerical integration: quadrature and Monte Carlo
The chapter that matters most for how we run studies.

**Method 4.1 - Monte Carlo with an honest error bar (Ch. 4.2, 4.4)**
- What it computes: an expectation as a sample mean; the error falls as one over the square root of the number of
  replications. Example: a vanilla call (true value 1.1718) estimated 1.1953 with 50,000 replications, a confidence
  interval 4.2% wide of the price; 1,000,000 replications gave 1.1749 with a 0.94% interval. To hit a target error, run
  about 30 pilot replications, estimate the sd, then size n; for a relative-error target use a slightly tighter bound.
  The interval assumes independent samples; dependent samples understate uncertainty (Appendix B).
- Inputs: a simulator and a seed. Seed control is required for reproducibility and for variance reduction - the book
  explicitly warns against seeding from the clock.
- How we could use it: every bootstrap and simulated P&L number we publish (H15's 5,000 block-bootstrap paths, the
  twin / random-entry tests) should carry its own simulation error next to the sampling error. Signal source: none.
- Our research: we bootstrap widely (HUNT_H15.md, H19, H21, H23, R41); not checked against this rule by us.

**Method 4.2 - Variance reduction: antithetic, common random numbers, control variates, conditioning, stratification
(Ch. 4.5)**
- Antithetic pairs (U and 1-U, or Z and -Z) helped a smooth integral a lot (interval 10.9% to 2.0% of the estimate) and
  a call a little (1.52% to 1.25%), but made a butterfly spread worse (2.56% to 3.55%): it needs a monotone payoff.
- Common random numbers: estimate a difference (two strategies, a finite-difference Greek) with the same random stream
  for both sides, so the noise cancels; also needs monotonicity.
- Control variates: subtract c x (Y - known mean of Y) with c estimated on separate pilot runs (reusing them biases the
  answer); using the stock price as the control cut a call's interval from 1.49% to 0.66%.
- Conditioning and stratification: replace a random quantity by its known conditional expectation, or sample each
  stratum separately (allocate samples in proportion to probability x stratum sd).
- How we could use it: CRN is the honest way to compare two variants of an arm (same resampled days / same simulated
  paths for both), and a known-price instrument (e.g. the vanilla ATM straddle) is a natural control variate when
  simulating a complex basket. Signal source: none. Not tested by us.

**Method 4.3 - Deciding on noisy estimates biases the result low (Ch. 4.5.4)**
- What it shows: a chooser option (decide at month 2 whether it is a call or a put; true value about 9.26-9.30) priced
  by nested Monte Carlo, where the choice at month 2 uses a sampled estimate, came out at 8.698 and, with ten times the
  samples, still 8.930 - biased low because noisy estimates cause wrong choices, and more samples do not remove it. The
  book's fix: learn the decision rule on one set of samples and estimate its payoff on an independent set.
- How we could use it: this is the numerical form of our pre-registration and holdout discipline - a filter or exit
  chosen on the same days it is scored will look better than it is (or, for this structure, a rule fitted on noise
  under-performs its ideal). Signal source: none.
- Our research: our studies already split design and a once-read holdout (e.g. HUNT_R16_PREDICTABILITY.md); the
  book's chooser example is a clean illustration for Jarvis.

**Method 4.4 - Importance sampling for rare events (Ch. 4.5.6, 8.3.3)**
- What it computes: sample from a distribution that makes the rare event common, then reweight each sample by the
  likelihood ratio. For a deep out-of-the-money call (spot 50, strike 80) shifting the drift so the expected terminal
  price equals the strike cut the average error from 3.06% to 1.155%; no gain at the money. For a barrier that only 249
  of 200,000 paths touched, a drift shift plus conditioning took the touches to 8,469 of 10,000 and the interval width
  from about 0.18 to about 0.0016.
- How we could use it: estimating the probability and size of the tail days that sink short-option books (a 10x move
  in a cheap expiry option, R98 in DEEP_OPTIONS_SELLING.md) or the chance a stop is gapped, with far fewer simulated
  days. Signal source: none (risk estimation). Maps to NSE, MCX, crypto. Not tested by us.

**Method 4.5 - Gaussian quadrature and quasi-Monte Carlo (Ch. 4.1, 4.6)**
- Gauss-Hermite quadrature turns a normal expectation into a weighted sum over a few nodes (5 nodes for a normal with
  mean 10 and variance 20 put 0.5333 of the weight at the mean); E[e^X] was exact to 5.5e-7 with 10 nodes. Product
  rules blow up with dimension, which is why Monte Carlo exists. Halton / Sobol low-discrepancy points can beat random
  points (a 2-D integral of true value 0.0199: random 10,000 points gave 0.0098-0.0332, Halton 0.0198-0.0200), but need
  a fixed dimension (no rejection sampling), prime bases, and can be biased: with a non-prime base a call came out
  6.2485 against 5.1911.
- How we could use it: a 5-10 node scenario set for the next day's log-return (sd from VIX or R88's size forecast) to
  value a straddle or a stop rule quickly in the app, instead of thousands of random paths. Signal source: volatility /
  options structure (valuation). Not tested by us.

Also in the chapter: random-number generators (linear congruential generators show lattice patterns; MATLAB's own
generator is better), inverse-transform sampling (exponential waiting times for jumps; sampling an empirical
distribution), acceptance-rejection, Box-Muller and the polar method, correlated normals by Cholesky. The sum-of-12-
uniforms shortcut for normals is poor in the tails, which matters for VaR.

### Ch. 5 Finite differences for PDEs
Pure numerics for the Black-Scholes PDE (which transforms into the heat equation): forward / backward / central
differences; an upwind scheme that never converges if built in the wrong direction; explicit schemes are stable only
when each new value is a positive-weight average of old ones (time step at most half the space step squared); fully
implicit and Crank-Nicolson schemes are unconditionally stable, Crank-Nicolson with second-order accuracy; the
alternating-direction implicit method for two-factor problems; consistency + stability = convergence (Lax). No
trading content; the stability idea reappears in Ch. 9.

### Ch. 6 Convex optimisation
Classification (convex: local = global; concave: optimum on the boundary; LP, QP, integer, stochastic), unconstrained
methods (steepest descent zig-zags; Newton; trust region; quasi-Newton; Nelder-Mead simplex search needs no
derivatives), penalties and barriers, Kuhn-Tucker conditions, duality (a constraint's multiplier is the marginal value
of relaxing it), Kelley cutting planes, active set, simplex and interior point methods. A polynomial objective gave the
global minimum from one start (x 1.4878) and a merely local one from another (x 3.6437).

**Method 6.1 - LP allocations are corner solutions (Ch. 6.5.2)**
- What it shows: maximising average yield subject to 3 equality constraints (duration 10.3181, convexity 157.6346,
  weights sum to 1) always picked exactly 3 bonds, even when 10 were available - an LP optimum has at most as many
  non-zero weights as binding constraints. Interior-point solvers may instead return the middle of a tie (0.5, 0.5).
- How we could use it: any LP-style allocator across our arms will concentrate in a few; diversification has to be
  imposed (bounds, cardinality) or come from a risk term. Signal source: none. Not tested by us.

**Method 6.2 - Tuning rules by simulation is noisy optimisation (Ch. 6.6)**
- What it says: when the objective is a simulated expected P&L of a rule with parameters, the objective is noisy and
  maybe non-convex; use common random numbers, fit a response surface (regression of P&L on parameters) for gradients,
  or derivative-free search (Nelder-Mead, genetic algorithms, tabu search). Differentiating inside an expectation is not
  always allowed.
- Our research: HUNT_R30_ALL_PARAMETERS.md and the F1 factory searched parameter grids with walk-forward and BH
  correction; the book does not discuss data-snooping, which is the bigger danger in our setting.

### Ch. 7 Binomial and trinomial lattices
CRR calibration by moment matching (up = e to the vol x root dt, down = 1/up), the backward recursion, American options
by "max of exercise now and continue", two-asset lattices with four probabilities from the correlation, trinomial
lattices whose spacing must be tied to the time step (vol x root of 3 dt; a bad spacing gave 10.9345 for a call worth
14.7171). Convergence oscillates with the number of steps (5 steps 6.3595, 500 steps 6.1140, true 6.1165). A
"pay-later" option's premium (2.0432) found by a root finder wrapped around the lattice. Lattices are fast in low
dimension and handle early exercise, but struggle with path dependence; explicit finite differences are a trinomial
lattice in disguise. Details and uses are in `research/books/fm-binomial-models.md`; nothing new to test.

### Ch. 8 Option pricing by Monte Carlo

**Method 8.1 - Simulate the hedge, not just the price: stop-loss vs delta hedging (Ch. 8.1.2)**
- What it shows: with real-world drift (10%) a writer of a call (spot 50, strike 52, vol 40%, 5 months; Black-Scholes
  value 4.7328) hedges two ways over 10,000 paths. Stop-loss hedge (hold the stock while the price is above the strike,
  none below, checked at discrete times): average cost 4.8268 with 10 checks and 4.8286 with 100 - it does not converge,
  because each crossing is detected late and the writer buys above the strike and sells below it. Delta hedging: 4.7370
  and 4.7352, converging to the price. The book adds that the spread of hedging cost, not only its mean, should be
  compared.
- Signal source: underlying price (the hedge trades the underlying or futures). Maps to: hedging a short NIFTY /
  BANKNIFTY option with futures; MCX options with the future; crypto options with the perpetual (crypto needs a stop and
  a lock per CLAUDE.md).
- The lesson for us beyond hedging: a stop at a fixed level, checked at discrete times on a jumpy price, systematically
  fills worse than the level, and checking more often does not fix it. Our premium stops sit in the same position.
- Our research: not tested by us as a hedge. Our buying studies check exits on the option's 1-minute high/low (the
  wicks; HUNT_FINAL.md); the book's result supports filling a triggered stop at the next price, never at the level.

**Method 8.2 - Paths, Brownian bridge, correlated assets (Ch. 8.1, 8.1.3, 8.2)**
- Euler discretisation of GBM gives normal, not lognormal, prices; use the exact log step. A rounding trap: rebuilding
  the starting price as exp(log(50)) gave 49.99999..., which flipped an "at or above the strike" test and changed the
  hedging result - set initial values exactly. Brownian bridge generates the end point first and fills in the middle,
  which helps stratification and low-discrepancy sampling. Two correlated assets: second normal = rho x first + root of
  (1 - rho squared) x independent normal; an exchange option (Margrabe) came out 0.8552 by Monte Carlo against 0.8633
  exact.
- Our use: simulating NIFTY and BANKNIFTY together (R72's cross-index view; correlation in DEEP_NIFTY_BANKNIFTY.md 1.4)
  for basket risk. Signal source: underlying price (simulation input). Not tested by us as a simulator.

**Method 8.3 - Barrier and Asian options with control variates; Greeks by simulation (Ch. 8.3-8.5)**
- A down-and-out put checked daily: crude Monte Carlo 1.3600 against an analytic 1.3629; conditioning on the crossing
  time (and pricing the rest with Black-Scholes) narrows the interval sharply. Asian (average-price) calls: the
  geometric-average option, which has a closed form, is a far better control variate than the sum of prices (interval
  3.4814-3.7738 crude, 3.4356-3.4549 with it). Halton points broke down at 24 monthly dimensions (6.62 with 1,000
  points against 8.39).
- Greeks: a naive delta from two independent simulations was useless (0.3588, interval 0.1447-0.5729, true 0.5231);
  central differences with common random numbers gave 0.5296, and the pathwise estimator (discounted ST/S0 when the
  option ends in the money) 0.5297 from one run. Pathwise fails for discontinuous payoffs (digitals, stop-outs).
- Signal source: volatility / options structure. Our use: Greeks of a basket whose legs we reprice on simulated
  underlying paths. Not tested by us.

### Ch. 9 Option pricing by finite differences
Boundary conditions for puts and calls on a truncated price grid; the explicit scheme for a put (4.0669 vs 4.0760)
blows up when the price step is refined (-2.8 x 10^22), and the book reads the explicit scheme as a trinomial tree
whose "probabilities" can turn negative - which is the instability; fully implicit (4.0718) and Crank-Nicolson schemes
are stable; a continuous down-and-out put by Crank-Nicolson 0.5414 vs 0.5424; American puts by Crank-Nicolson with an
"at least intrinsic" step inside SOR iterations (4.2815 vs a lattice's 4.2830). A grid gives delta, gamma and theta
directly. Pure pricing numerics; no method to test (NSE index options are European).

### Ch. 10 Dynamic programming

**Method 10.1 - Bellman recursion and value functions (Ch. 10.1-10.3)**
- What it computes: an optimal sequence of decisions by working backward: today's value = best over actions of (today's
  reward + expected value of tomorrow's state). Greedy choices are not optimal (shortest path: value 16 via 0-1-3-5-7).
  Needs a Markov state; suffers the curse of dimensionality; value functions approximated by basis functions; the
  output is a policy in feedback form (action as a function of state). A consumption-saving problem with log utility
  consumes a fixed fraction of wealth each period.
- How we could use it: intraday exit timing as optimal stopping (hold vs exit given time left and open P&L), which is
  the structure of an American option. Signal source: underlying price (state) with the option's premium as reward.
- Our research: not tested by us as DP; our lock/trail studies (HUNT_R19*, R100) fixed rules by grid search instead.

**Method 10.2 - Optimal risky fraction is fragile to sampling noise (Ch. 10.3, example 10.4)**
- What it shows: one period, log utility, stock drift 10%, vol 40%, cash at 5%. With 10,000 random scenarios the
  "optimal" share in stock came out anywhere from 0.2694 to 0.3763 across seven runs; 5,000,000 scenarios settled at
  0.3049-0.3074; Gauss-Hermite with 3-5 nodes gave 0.3064 every time. The answer does not depend on wealth (constant
  relative risk aversion). Less risk-averse power utility pushed the share up fast (gamma 0.5 gave 0.6295, 0.4 gave
  0.7887, 0.3 gave 0.9999).
- How we could use it: any Kelly-style fraction estimated from a few hundred trades carries the same noise; size from a
  shrunk, cross-period estimate and prefer a fraction of it. Signal source: none (sizing). Maps to NSE, MCX, crypto.
- Our research: agrees. HUNT_H15.md found the half-Kelly fraction for BN unstable: f* 1.29 in the sample used vs 0.48
  (Jun 2023 to Nov 2024) and 0.14 (2020 to May 2023); quarter-Kelly cut P(drawdown of 50% or more) to 1% but grew half
  as fast. Boss's frame since H17 is fixed lots.

**Method 10.3 - Longstaff-Schwartz least-squares Monte Carlo for early exercise (Ch. 10.4)**
- What it computes: simulate paths; going backward, regress the discounted future cash flow on simple functions of the
  current price (1, S, S squared) using only in-the-money paths; exercise where the immediate payoff beats the fitted
  continuation value. An 8-path toy example (strike 1.1) and a full put (6.8074 vs a lattice's 6.8129). The estimate is
  low-biased (sub-optimal rule, discrete exercise dates); a perfect-foresight run gives a high-biased bound; use separate
  samples for fitting and scoring.
- How we could use it: as a template for "exit now or hold" rules fitted by regression on simulated or historical paths
  of the underlying, with the fit and the score on different days. Signal source: underlying price (regressors) with
  the premium as payoff. Not tested by us.

### Ch. 11 Linear stochastic programming with recourse

**Method 11.1 - Scenario-tree planning and the value of the stochastic solution (Ch. 11.1-11.2)**
- What it computes: decide now (first stage), then adapt once uncertainty resolves (recourse), maximising expected
  utility over a scenario tree; decisions may not depend on the future of their own branch (non-anticipativity). VSS
  = cost of planning on average values minus cost of the stochastic plan (never negative). Toy asset-liability model:
  wealth 55, liability 80, stocks up 1.25 / down 1.06, bonds 1.14 / 1.12, reward 1 per rupee of surplus and penalty 4
  per rupee of shortfall: first-stage mix 41.48 stocks / 13.52 bonds; later stages went all-in one asset (an artefact of
  piecewise-linear utility and a 2-branch tree); only the first decision is implemented, then re-solve on a rolling
  basis. With proportional costs, buys and sells need separate variables; hard liabilities can be infeasible in
  extreme scenarios, so use penalties or borrowing.
- How we could use it: little directly; the concepts (only the first decision matters, re-plan daily, penalise
  shortfall below Boss's stops) fit a daily risk-budget planner. Signal source: none. Not tested by us.

**Method 11.2 - Arbitrage-free scenario check (Ch. 11.3.2)**
- What it computes: a set of scenarios (or of option prices) is free of arbitrage if and only if a non-negative vector y
  solves (returns matrix transposed) x y = prices, which is an LP feasibility test; the dual gives risk-neutral state
  prices. The book shows a 2-asset tree that hides a riskless profit.
- How we could use it: before trusting a simulated or recorded chain (stale wings, R103), run this LP across strikes;
  a failure means the prices cannot all be live. Signal source: option premium (chain prices) as a data-quality gate.
- Our research: R103's stale-leg artefact (HUNT_R103_OPTIONS_SELLING.md) is the case it would have flagged; the
  simpler parity / butterfly checks are in `research/books/fm-binomial-models.md`.

Scenario generation (Ch. 11.3): from a model (SDE or a VAR time-series), more branches early and longer steps late,
moment matching by least squares (means, covariances, zero skew, kurtosis 3), with the caveat that different
distributions share moments; probability metrics and scenario reduction. L-shaped (Benders) decomposition and the
comparison with dynamic programming (DP gives a policy and handles infinite horizons; stochastic programming gives
only today's decision but allows any dependence) are pure methods.

### Ch. 12 Non-convex optimisation

**Method 12.1 - Cardinality, minimum lots, fixed costs as integer constraints (Ch. 12.1)**
- What it computes: binary on/off variables give "at most k assets", "if held, at least m" (semicontinuous), round lots,
  fixed or concave costs and logical rules (if A then B). In the book's 3-asset toy with at most 2 assets the variance
  rose 0.27% (0.0631 to 0.0633). Mean absolute deviation over historical scenarios turns the risk term linear (the book
  warns of overfitting the history). Fixed-mix (constant-proportion) policies over scenarios are non-convex; check them
  out of sample on a larger scenario set including stress tests; a simple fixed mix is more robust and easier to
  defend than a dynamic policy.
- How we could use it: "at most N arms live at once", "if Liquidity is on, Donchian size is halved", minimum lot of 1
  per arm. Signal source: none. Not tested by us.

Branch and bound (bounds from relaxations, pruning, cover cuts: the 4-item knapsack solved at value 34), and
heuristics (local search, simulated annealing, tabu search, genetic algorithms) are pure methods.

### Appendices A-C
A: MATLAB (pre-allocate arrays: a 1,000-size Hilbert matrix took 10.6 s without and 0.05 s with; vectorise calls).
B: probability and statistics refresher with three practical warnings: normal returns are "debatable" because of fat
tails; zero correlation does not mean independence; and "mean blur" - 100 samples from a normal with mean 0.3 and a large
sd give sample means far from 0.3, sometimes negative, so expected returns are barely estimable from short histories
(regression likewise: association is not causation, and sampling noise shows relationships that are not there).
C: AMPL models (mean-variance, knapsack, cash-flow matching).

## Performance claims
- No trading-profit claims. Every number is a numerical-accuracy result from the author's own MATLAB runs (one seed per
  experiment, usually without repeats), e.g. the stop-loss hedge cost 4.8268-4.8286 vs 4.7328, the delta-hedge cost
  4.7352-4.7370, variance-reduction interval widths, quasi-Monte Carlo errors. They are illustrations of methods on
  textbook parameters, not evidence about any market.
- The only market-flavoured statements are qualitative: returns have fat tails; correlations rise in crashes; Black-
  Scholes is used through implied volatility in practice; no evidence given.

## Risk, sizing and psychology
- Risk: VaR is a quantile that ignores the tail beyond it and can penalise diversification; CVaR is the coherent
  alternative; chance constraints cap the probability of a large loss; shortfall penalties in planning.
- Sizing: utility-based (log utility gives a wealth-independent fraction - the Kelly logic); the "optimal" fraction is
  very sensitive to sampling noise (Method 10.2); cardinality and minimum-lot constraints (Ch. 12).
- Hedging: a stop-loss hedge leaks money at every crossing and does not improve with more frequent checks; delta hedging
  converges but needs continuous trading and pays costs.
- Process: fix seeds, report error bars, separate the sample that fits a rule from the sample that scores it, compare
  variants on the same random stream, and check that results do not hinge on a rounding accident. No psychology.

## How it relates to our research
- **Agrees with:** H15's warning that a Kelly fraction estimated on one window is unstable (Method 10.2); our design /
  holdout split and pre-registration (Method 4.3's chooser bias, Method 10.3's separate fit and score); R16 / R41 / R88
  (size predictable, direction not) through Appendix B's "mean blur"; R102's fat tails and skew (Ch. 2.5, 2.6.5).
- **Tools we already have:** Black-Scholes, Greeks and the Newton-plus-bisection IV solver in `research/brain/bs.py`;
  R103's Black-Scholes repricing of stale legs; block bootstraps in H15 / H19 / H21 / H23 / R41.
- **R103 and option selling:** the book explains why the rare day matters (importance sampling, CVaR) and offers an
  arbitrage LP that would have caught stale far wings; it gives no evidence that selling pays. R102's
  "VIX over-states NIFTY's next month on 81% of days" is the risk premium the book's complete-market model assumes
  away.
- **GEX (R28 / R36) and IV1:** the book has only the dealer-hedging mechanics (delta hedging); our studies found GEX
  forecasts size not direction, and 0 of 4 expiry-afternoon GEX rules pass (HUNT_R36_GAMMA_EXPOSURE.md).
- **R72 cross-index and H39:** the book's correlated-GBM and exchange/spread option machinery is a simulator for
  index pairs; R72's Breadth Donchian and H39's lead-lag (no lead beyond the same minute) are trading questions it does
  not address. The forward price = spot x e^(r x T) (cost of carry) is stated but not tested by us as a basis trade.
- **Costs (H24, baselines.yaml):** the book ignores costs and bid-ask throughout, except as a reason why replication
  fails and why LP / integer models need lot and cost terms; every method here must have our costs added before use.

## What is new to us
- A ready discrete-monitoring correction for barriers (exp(-0.5826 x vol x root of interval)) to quantify how often a
  minute-checked stop under-states a touch.
- Simulating the hedge of a short option (stop-loss vs delta) on real-world paths, with the spread of the hedging cost;
  we have never simulated a delta-hedged short straddle with futures on our data.
- Importance sampling and conditioning to estimate tail-loss probabilities for short-option baskets with few paths.
- Common random numbers as the standard for comparing two arm variants on bootstrap resamples; control variates when a
  closed-form relative exists.
- Gauss-Hermite scenario sets (5-10 nodes) as a cheap in-app valuation of a straddle or a stop rule under a vol forecast.
- An LP arbitrage check across the chain as a data-quality gate.
- Longstaff-Schwartz regression as a template for fitted "hold or exit" rules, with separate fit and score samples.
- CVaR of the open book as a risk number beside the account stops.

## Could not read
- Most displayed equations (formulas in display mode) came through as garbled symbols or blank lines in the text
  layer; the surrounding prose, the MATLAB code and the printed results were readable, so the methods were followed
  from code and text. Figures (plots of paths, frontiers, error curves, lattices, penalty contours, scenario trees) are
  images; only captions survived. A few tables (e.g. the AMPL cash-flow model) are partly garbled. Nothing else.
