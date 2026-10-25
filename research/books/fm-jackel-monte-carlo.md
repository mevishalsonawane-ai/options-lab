# Monte Carlo Methods in Finance - Peter Jäckel (2002)

Source: monte-carlo-methods-in-finance-jackel.txt (scanned book, tesseract OCR), 232 pages (preface, 14 chapters,
bibliography, index), read in full on 25 Oct 2026, every chapter in order, with running notes kept in the scratch
folder. The prose came through well; most displayed formulas, the colour copula density plots, the convergence charts
and some tables are garbled (see "Could not read").
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A practitioner's book (Wiley Finance) on making Monte Carlo pricing fast, correct and honest, written by a bank quant with C++ in mind.
It covers the maths behind simulation, simulating price processes, co-movement and copulas, and repairing broken correlation matrices.
Then the number engines: pseudo-random generators, low-discrepancy (Sobol') numbers, and turning uniforms into normals safely.
Then speed: variance reduction, path construction (Brownian bridge), and computing Greeks by simulation.
It ends with interest-rate market models, Bermudan swaptions, non-recombining trees and practical engineering tips.

There are no trading rules in the book and no profit claims. It is about how a simulation can quietly give the wrong
answer, and how to make it converge faster. Much of the general Monte Carlo material (error bars, antithetic sampling,
control variates, importance sampling, quasi-random points, the discrete-barrier correction) is also in
`research/books/fm-brandimarte-numerical.md` with worked numbers; this write-up does not repeat those examples and adds
what is specific to Jäckel: copula draws, repairing correlation matrices, generator quality (the Neave effect, parallel
streams), Sobol' plus Brownian bridge, Greeks for kinked payoffs, fitting a decision boundary on training paths and
scoring it on fresh ones, and interpolating volatility in variance. Copulas in depth: `research/books/fm-copula-methods.md`.
Volatility trading: `research/books/bennett-trading-volatility.md`. Neither is repeated.

Our data, for the mapping: NIFTY weekly and BANKNIFTY monthly options (1-minute rolling chain, ATM +-10 strikes, IV and
OI), index spot / futures bars, India VIX daily, MCX futures and options, Delta Exchange crypto. NSE index options are
European and cash-settled. `research/brain/bs.py` already has Black-Scholes prices, Greeks and an IV solver.

## Methods, rules and setups (chapter by chapter)

### Ch. 1 Introduction
Why simulation: realistic models rarely have closed forms, and even closed forms need an independent check. History of
the method (Ulam, von Neumann, Metropolis; the RAND million random digits).

**Method 1.1 - Discrete-monitoring correction for a barrier or a stop (Ch. 1, used again in Ch. 11)**
- What it computes: a barrier checked only at fixed times is touched less often than one watched continuously. The
  Broadie-Glasserman-Kou rule prices the discrete contract as a continuous one with the barrier shifted away from spot
  by a factor exp(0.5826 x vol x square root of the check interval) (upwards for an up-barrier, downwards for a
  down-barrier).
- The book's caveat: it is good only when the check interval is short against the time left and spot is not close to
  the barrier. In its Ch. 11 test (spot 160, barrier 150, checked monthly) the shifted barrier is 157.8, below spot, so
  the formula says the option is already dead and gives zero - the approximation fails exactly where a trader needs it.
- Inputs: vol (IV or a realised forecast), the check interval, the level.
- How we could use it: our stops and targets are checked on 1-minute (live: tick) prices or on 5-minute bar closes in
  some studies. The shift says how much a close-checked stop under-states the touch probability, and therefore how much
  a backtest that checks closes flatters a stop. Near the level, simulate instead.
- Signal source: none (measurement). Maps to NSE index options (premium stops), MCX, crypto (every crypto stop).
- Our research: not tested by us. Also noted in fm-brandimarte-numerical.md ("What is new to us").

### Ch. 2 The mathematics behind Monte Carlo
Expectations, variance, Jensen / Hölder inequalities; simulation as a running average; its error falls with the square
root of the number of samples whatever the dimension, so it beats grids beyond about four dimensions. A path problem's
dimension is assets x time steps. A catalogue of distributions, with useful notes: the generalised beta (GB2) gives
near-lognormal shapes for extrapolating a smile; Pareto and generalised Pareto for rare heavy-tailed events; the
generalised lambda, defined by its inverse distribution function, can mimic almost any shape for fitting data. Strong
law, central limit theorem, Feynman-Kac (an option PDE equals an expectation), and the Moore-Penrose pseudo-inverse.

**Method 2.1 - The standard error is only a probability statement (Ch. 2.7)**
- What it computes: the simulation error is the sample sd divided by the square root of N. Because about 68% of a
  normal lies within one sd, roughly one run in three lands outside one standard error of the truth; and the standard
  error itself is estimated with noise.
- How we could use it: every simulated number we publish (bootstrap p-values, random-twin p-values, P(ruin)) has its own
  simulation error on top of the sampling error. Our arithmetic, not a test: a p of 0.05 estimated from 500 random
  twins (R47's twin count) carries a simulation sd of about 0.01, so R47's "p 0.0499" for the BANKNIFTY PCR rule
  (HUNT_R47_RELAXED_OPTION_BUYING.md, 91 trades) is not distinguishable from 0.06; its q 0.50 decided it anyway.
- Signal source: none (method). Our research: see "How it relates to our research".

**Method 2.2 - Moore-Penrose pseudo-inverse for ill-conditioned least squares (Ch. 2.9)**
- What it computes: a least-squares (or minimum-size) solution through the singular value decomposition, dropping
  near-zero singular values so noise is not amplified. The book uses it wherever a matrix may be singular.
- How we could use it: regressions on many collinear features (our 22-parameter candle studies, R88) or factor models
  across five correlated indices. Signal source: underlying price/volume (features). Not tested by us.

### Ch. 3 Stochastic dynamics
Brownian motion (stationary independent increments plus continuity force it), Itô's lemma, Ornstein-Uhlenbeck mean
reversion with an exact Gaussian transition (long-run sd = vol / square root of twice the reversion speed), mean-reverting
lognormal, why multi-factor rate models cannot be put on recombining trees, Bessel processes, CEV, displaced diffusion.

**Method 3.1 - Skew-producing price processes: CEV and displaced diffusion (Ch. 3.7, 3.8)**
- What it computes: an underlying whose volatility falls as price rises (CEV with exponent below 1) produces a smile in
  which low strikes carry higher implied vol, as in equity indices. A displaced diffusion (lognormal of price plus a
  constant) gives almost the same skew, is solved in one line and is fast. CEV with a low exponent can hit zero.
- Inputs: spot, a skew parameter, a vol level. How we could use it: a one-parameter skew model to simulate NIFTY paths
  whose option prices show the chain's put skew, instead of flat-vol Black-Scholes, when we reprice far legs (R103's
  stale-wing repricing used flat Black-Scholes at the last IV).
- Signal source: volatility / options structure (modelling). Maps to NSE index options; MCX options (commodity skews
  can be positive - the book notes CEV above 1 fits some commodity markets); crypto options.
- Our research: R102 (DEEP_NIFTY_BANKNIFTY.md 3.4) found puts five strikes out dearer than calls on 91% of NIFTY days
  (n 1,222) - the skew such a model reproduces. Not tested by us as a model.

**Method 3.2 - Exact simulation of mean reversion (Ch. 3.3)**
- What it computes: an Ornstein-Uhlenbeck variable can be stepped exactly over any interval with its known Gaussian
  transition - no small-step error. Inputs: reversion speed, long-run level, vol (fitted on history).
- How we could use it: simulating a spread (NIFTY vs BANKNIFTY, futures basis, an IV-minus-VIX gap) for a pairs or basis
  study's null distribution. Signal source: underlying price (spread) or volatility structure (IV gap). Maps to NSE,
  MCX (calendar spreads), crypto (perp funding/basis). Not tested by us (R72 traded agreement across indices, not a
  spread; H39 found no lead beyond the same minute).

### Ch. 4 Process-driven sampling
Strong (path-by-path) versus weak (expectation) convergence; pricing needs only the weak kind. Euler and Milstein steps.
The practical advice: transform the equation so its noise term no longer depends on the state (log for lognormal
prices, square root for a CIR/Heston variance) and then use plain Euler; it matches Milstein's accuracy. Euler on raw
prices sometimes produces negative prices ("spurious paths"); simulating the log removes them. For CEV, a path crossing
zero means absorption and should be treated so. When an exact solution exists, use it.
- For us: our simulated index paths should be built in log-returns (they are, in our bootstraps of returns). Signal
  source: none. Not tested by us.

### Ch. 5 Correlation and co-movement
**Method 5.1 - Copula draws for joint moves (Ch. 5.1, 5.2, 9.4)**
- What it computes: linear correlation describes dependence well only for near-normal variables; it can be zero for
  strongly dependent ones, changes under non-linear transforms, and with given single-asset distributions not every
  correlation is even possible. Rank correlations (Spearman, Kendall) avoid these problems. A copula sets the joint
  behaviour separately from each asset's own distribution. Recipes: Gaussian copula (correlated normals -> uniforms ->
  each asset's own inverse distribution); t-copula (divide the correlated normals by the square root of an independent
  chi-square over its degrees of freedom) which adds joint large moves of the same sign; Archimedean copulas: Gumbel
  (joint upside extremes), Clayton (joint downside extremes - assets crash together), Frank (symmetric).
- The book's risk point: firm-wide VaR leans on history and stress scenarios of two to three sd, while option smiles
  price four- and five-sd moves; and since correlation cannot be hedged, "If you can't hedge it, you better guess it
  right".
- Inputs: each index's return distribution (empirical), a rank correlation or copula parameter.
- How we could use it: a joint simulation of NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX (or GOLD/SILVER/CRUDE) days
  with a lower-tail copula, to estimate how often several arms or several short-option books lose on the same day -
  the account stop (-Rs 40k drawdown / -Rs 13k a month) is hit by joint losses, not single ones.
- Signal source: none (risk). Maps to NSE (multi-index books), MCX, crypto (BTC/ETH move together in crashes).
- Our research: not tested by us. R72 (HUNT_R72_CROSS_INDEX.md) measured Breadth Donchian's daily correlation with
  Donchian at 0.80 and advised replacing rather than stacking them; OBUY_LV05_MULTIDAY.md notes its Monte Carlo
  "resamples whole trades and ignores that the NIFTY and BANKNIFTY positions overlap in time". More copula detail:
  fm-copula-methods.md.

### Ch. 6 Salvaging a linear correlation matrix
**Method 6.1 - Repairing an invalid correlation matrix (Ch. 6.1-6.4)**
- What it computes: matrices estimated from data with asynchronous gaps, or edited by hand for a stress test, often
  stop being valid (a negative eigenvalue), and simulation then fails. The book's example: lowering one entry of a
  3x3 equity-index matrix from 0.4 to 0.3 broke it. Fast fix (spectral): set negative eigenvalues to zero, rebuild, and
  rescale each row to unit length. Best fit: parametrise rows as points on a unit sphere (angles) and optimise the
  angles to match the target; the spectral answer is a good starting point and the two agree closely. A lower-triangular
  angle form halves the parameters.
- Inputs: the estimated matrix (pairwise from gappy data), optional weights for entries that matter most.
- How we could use it: any multi-index or multi-commodity simulation or stress (indices with holidays or missing
  minutes, MCX contracts with different sessions, crypto 24x7 vs NSE hours) - pairwise correlations from overlapping
  windows can produce an invalid matrix.
- Signal source: none (risk). Maps to NSE, MCX, crypto. Not tested by us.

### Ch. 7 Pseudo-random numbers
Chaos is not randomness (the logistic map has hidden near-periodic stretches and an uneven density); von Neumann's
mid-square method dies in short cycles; linear congruential generators and the infamous RANDU; Numerical Recipes'
Ran0-Ran3; the Mersenne twister (period 2^19937 - 1, equidistributed in 623 dimensions), recommended.

**Method 7.1 - Treat the random number engine as a part that can fail (Ch. 7.3-7.6, 14.7)**
- What it says: every generator fails some application; never trust an undocumented system generator; keep a small
  library and re-run a result with a different generator method, not just a new seed. Map integers to (0,1) so that 0
  and 1 never occur. For parallel runs, giving each worker a different seed is a fallacy for simple generators: the
  streams may overlap; use non-overlapping offsets (skip-ahead).
- How we could use it: our studies use numpy's `default_rng` with fixed seeds (often seed + an offset per index or
  variant, e.g. `research/hunt/r20b/study.py`). numpy derives each stream through a hashed seed sequence, which largely
  answers the overlap worry for that generator; the book's cheaper check still applies: re-run a key p-value with a
  second generator (e.g. Philox) and report both.
- Signal source: none (method). Not tested by us.

### Ch. 8 Low-discrepancy numbers
Discrepancy (how unevenly points fill a cube); Halton (prime bases), Sobol' (direction numbers, primitive polynomials,
Gray-code update), Niederreiter. For smooth integrands they converge close to 1/N instead of 1/square root of N. The
book's finding: Halton, Niederreiter and Sobol' with naive ("unit") initialisation become worse than random above about
15 dimensions, but Sobol' with regularity-breaking initialisation never does worse than random and is much better in
the leading dimensions. Use 2^n - 1 draws; put the most important problem dimensions first.
- For us: useful for pricing integrals (valuing a structure under a vol forecast), not for significance tests, which
  need independent random draws to be valid. Signal source: none. Overlaps fm-brandimarte-numerical.md Method 4.5.

### Ch. 9 Non-uniform variates
Inverse distribution function (preferred; tabulate with a shape-preserving spline when it is slow); sampler densities
with likelihood-ratio weights (importance sampling) and rejection sampling (the author avoids it: it collapses in high
dimension and breaks low-discrepancy numbers).

**Method 9.1 - Draw normals by inversion, not Box-Muller (Ch. 9.3)**
- What it says: the Box-Muller transform combined with a simple multiplicative generator (the Neave effect) can never
  produce a normal below about -4.48 over the generator's whole cycle, although about 8,157 such draws are expected,
  and it leaves odd "wings" in the tails. Mixing transforms and generators can interact in ways nobody foresees, and
  finance cares most about the tails. Use an accurate inverse normal (Acklam's or Moro's) instead.
- How we could use it: any parametric simulation of index days for tail risk (P(ruin), the account stop, a short
  straddle's worst day) should draw normals by inversion, or better, resample real days (our block bootstraps do).
- Signal source: none (risk). Not tested by us.

### Ch. 10 Variance reduction
Antithetic sampling (count each pair's average as one sample for the error; helps only when the payoff responds mostly
to the mean move), variate recycling / common random numbers, control variates (estimate the coefficient on a pilot run
to avoid bias; one good control beats several mediocre ones), stratified sampling (no honest error estimate, can bias),
importance sampling. Examples of these are in fm-brandimarte-numerical.md Method 4.2; only Jäckel's extra points follow.

**Method 10.1 - Same random draws for every variant you compare (Ch. 10.2)**
- What it computes: the noise in the difference of two simulated values shrinks with their correlation, so bumped
  Greeks, optimiser steps and variant comparisons should reuse the same paths.
- How we could use it: compare two exits of an arm on the same bootstrap resamples and the same random-twin entry
  minutes, and report the paired difference. Signal source: none. Not tested by us as a rule.

**Method 10.2 - Do not trust moment matching (Ch. 10.6)**
- What it says: forcing a sample's mean and covariance to their targets breaks independence (the standard error no
  longer applies), does not survive non-linear transforms (a two-point uniform set with exact moments gives normals
  whose second moment is 0.64 instead of 1; matched normals do not give matched lognormal prices), disturbs the third
  moment that option values depend on, and can eat most of the run time. "Use at your peril."
- For us: do not "rescale" bootstrap samples to a target mean or vol and then quote a p-value from them. Signal source:
  none.

**Method 10.3 - Brownian bridge path construction (Ch. 10.8)**
- What it computes: build a simulated path end-point first, then midpoints conditional on their neighbours. The first few
  random numbers then decide most of the path's shape: for a 12-step path the first 4 numbers explain 93.2% of the
  variability with the bridge, 95.6% with the (costly) spectral split and only 53.9% step by step. With Sobol' numbers
  this is what makes them fast in high dimensions; the book's 252-day Asian option converged in seconds. A term
  structure of volatility enters through cumulative variance; correlated assets via a spectral split per step.
- How we could use it: simulating an intraday or multi-day path (375 one-minute steps) to value a path-dependent exit
  (premium stop, trail, lock) under a vol forecast. Signal source: volatility / options structure (valuation). Maps to
  NSE, MCX, crypto. Not tested by us.

Latin hypercube sampling is noted as a design for very expensive experiments (Sobol' points already have that property).

### Ch. 11 Greeks
Test case: a monthly-checked up-and-out call (spot 100, strike 100, barrier 150, one year, vol 30%) and a near-barrier
case (spot 160, 0.52 years left).

**Method 11.1 - Greeks of kinked or stop-managed payoffs by simulation (Ch. 11.3-11.8)**
- What it computes: (a) bump-and-reprice with the same paths: the bump size is a trade-off (too big: higher-order error;
  too small: a kinked payoff turns Delta into a 0/1 count and Gamma into a spike), so for barrier-type payoffs plain
  finite differences are "virtually unusable" for Gamma (the book estimates several CPU months to converge). (b)
  Importance sampling: build only paths that survive the barrier and end in the money, weight by their probability -
  the integrand becomes smooth. (c) Pathwise derivative: valid for continuous payoffs (vanilla). (d) Likelihood ratio:
  move the parameter into the density so any payoff, even a digital or knock-out, is multiplied by a smooth weight; for
  lognormal paths Delta's weight uses only the first step's random number; its variance explodes for low vol or a very
  short first step. Methods can be combined.
- How we could use it: an option position with a premium stop, a target and a trail is a path-dependent, knock-out-like
  payoff. Its true Delta / Gamma / Vega (how its expected value moves with the index or IV) differs from the plain
  option's. Likelihood-ratio weights on simulated paths would give these for Jarvis's risk view; the plain option's
  Greeks are in bs.py.
- Signal source: volatility / options structure (risk). Maps to NSE index options, MCX options, crypto options.
  Not tested by us.

### Ch. 12 Monte Carlo in the BGM/J (Libor market) framework
Interest-rate theory with no direct NSE use: lognormal forward rates with state-dependent drifts (so no recombining
tree), factor reduction keeping each rate's variance, a closed-form approximation for European swaption vols, and a
predictor-corrector step (Euler in logs, drift re-estimated at the predicted end and averaged, same random numbers)
that stays accurate even for one step of 30 years.

**Method 12.1 - Fit a decision boundary on training paths, score it on fresh paths (Ch. 12.6-12.9)**
- What it computes: for an early-exercise (Bermudan) contract, find two coordinates in which "exercise" and "hold"
  separate (here the short rate and the next long swap rate), describe the boundary with a 3-parameter curve per date,
  optimise the parameters backwards in time on a stored training set of paths, then re-price on a new independent set
  about twice as large, because the value measured on the training set is biased upwards. Results 5.062% / 5.066% /
  5.069% of notional with 32,768 / 131,072 / 1,048,576 training paths against a tree benchmark of 5.084% +- 0.015%,
  in seconds instead of hours. Near the optimum the price is insensitive to small boundary errors.
- How we could use it: an exit or hold decision for an open option ("exit now or hold to the next check?") is an
  early-exercise problem on the index path. The boundary could be a simple function of two state variables (e.g.
  index move since entry in ATR units and minutes to square-off), fitted on design days and scored on untouched days.
- Signal source: underlying price (state) with option premium P&L as the payoff. Maps to NSE index options, MCX,
  crypto (with stop and lock). Our research: our design / walk-forward / once-read holdout follows the same logic;
  OBUY_SEARCH.md shows the bias in practice: the 10 best in-sample rules' in-sample Monte Carlo promised P(profit in a
  year) of 93-99%, and on the holdout it was 2-71% (1 of 10 made money). Exit rules: HUNT_R18_EXITS.md, R19 locks.

### Ch. 13 Non-recombining trees
A benchmark tool for multi-factor rate models: m factors need at least m + 1 branches per node (a regular simplex
matching mean, covariance and equal probabilities); rotate the simplex for a symmetric spread and flip its sign every
step; a recursive implementation; exact martingale conditioning per branch (also about 1.7-2.8 times faster); clustering
of nodes under flat vol disappears with a realistic vol term structure. A warning worth keeping: matching the variance
with very few branches over-values convex payoffs (a two-point +1/-1 set gives a call worth 0.5 against 0.3989 for the
true normal). Theory for us; no practical method beyond that warning.

### Ch. 14 Miscellanea
**Method 14.1 - Interpolate implied volatility in total variance, not in vol (Ch. 14.1)**
- What it computes: linear interpolation in implied vol across maturities can imply negative forward variance between
  quoted points (the book's steep curve, 45% at 3 months falling to 21% at 3 years, does this near year 3), which no
  path can reproduce. Tabulate cumulative variance (IV squared x time) and interpolate it monotonically: linear gives
  piecewise-flat forward vol; monotone cubic gives a smooth forward vol and is the author's preferred compromise.
- Inputs: ATM IV per expiry and time to each expiry.
- How we could use it: NIFTY weekly vs monthly, BANKNIFTY's single monthly, MCX and crypto daily expiries; and intraday,
  where time to expiry shrinks through the session. Interpolating in variance gives a consistent vol for an in-between
  horizon (e.g. a 3-day hold) and a check for calendar-arbitrage in the chain.
- Signal source: volatility / options structure. Maps to NSE (two expiries coexist for NIFTY), MCX options, Delta
  crypto options. Our research: R102 (DEEP_NIFTY_BANKNIFTY.md 3.4) found the front term structure flat to slightly
  inverted with an expiry-day spike (ATM IV above VIX by 7.7 points on NIFTY expiry days, n 1,222); H29 found monthly
  minus weekly IV did not sort the realised move. Interpolation itself not tested by us.

**Method 14.2 - Never report a single simulated number (Ch. 14.2-14.4)**
- What it says: return a convergence diagram (the running estimate against samples) with every result, log the CPU time
  of every run as an early warning of bugs (an underflow trap once made code several times slower), and guard every
  routine against overflow, underflow and extreme inputs. For quasi-random runs the standard error over-states the true
  error, so the diagram is the better check.
- How we could use it: add a running-estimate plot or table (p after 100, 200, ... draws) to bootstrap and twin tests,
  and the run time. Signal source: none.

Also: building paths inside a covariance-aware generator; the cost of exp(); parallel runs (master-slave threads,
non-overlapping streams, give each worker its own copy of every object to avoid memory contention).

## Performance claims
- No trading-profit or win-rate claims anywhere in the book.
- All numbers are numerical-accuracy results from the author's own runs on his test cases (on 300-400 MHz PCs), e.g.
  the Bermudan prices 5.062-5.069% vs a tree's 5.084% +- 0.015%, Monte Carlo in 92 s vs a tree in about 8 h, a speed-up
  factor of 3125 for a 4-factor tree needing five fewer steps, path-construction variability shares (93.2% / 95.6% /
  53.9%), the Neave tail bound. Evidence is the book's own convergence charts; no market data.

## Risk, sizing and psychology
- Risk: VaR depends on co-dependence assumptions; history cannot show co-movements that have not happened; stress
  scenarios of two to three sd are inconsistent with smiles that price four to five sd; linear correlation hides
  joint tail moves - use copulas with tail dependence. Tails are where flawed random numbers do most damage.
- Sizing: nothing on position sizing or Kelly.
- Process discipline: error bars and convergence diagrams with every number, independent fit and score samples,
  common random numbers for comparisons, distrust of black-box generators, guard rails for extreme inputs. No
  psychology.

## How it relates to our research
How our studies use simulation (from `grep -il "bootstrap\|monte carlo\|random twin\|random entry" research/*.md`,
111 docs):
- **Random-entry twins** are our main null: R47 used 500 random twins per holdout candidate (FINDINGS.md R47: none of
  10 beats random entries after BH); R52 draws 20 alternative entries per real trade, 2,000 times, same day, index and
  side, random minute (HUNT_R52_OLD_CATALOGUE_RECENT.md, Statistics). R52 Amendment 1 found same-side twins leak the
  day's direction to breakout rules and are degenerate (p = 1) for fixed-minute rules, and added a coin-flip-side p.
  Jäckel's Ch. 9 / 10 logic agrees: the sampler must cover what the null claims to cover, or the estimate is biased.
- **Block bootstraps** for P(ruin), P(losing month) and confidence intervals: H16 (stationary bootstrap, 2,000 paths,
  10-day blocks), H19, H21, H23, R3 GOLDM, R41, R77 (2,000 resamples), R88 (1,000), OBUY_FINAL (10,000 one-year paths;
  Hansen SPA / White RC with B = 1,000), R99C (bootstrap p 0.245). Resampling real days keeps fat tails and
  same-day co-movement across indices only if whole days are resampled across all books together; trade-level Monte
  Carlo does not (OBUY_LV05_MULTIDAY.md's own limit). This is the book's Ch. 5 point in our setting.
- **Simulation error**: our docs report B and the block length but rarely the Monte Carlo error of a p-value or a
  convergence check (Method 2.1 / 14.2). With 500-2,000 draws a p near 0.05 has a simulation sd of about 0.005-0.01.
- **Training vs fresh paths** (Method 12.1) is our design / walk-forward / holdout rule; OBUY_SEARCH.md's in-sample
  Monte Carlo (93-99%) against holdout (2-71%) is the same upward bias. H15's warning that the Kelly fraction is
  unstable (estimated on the same months the bootstrap resamples) is the same lesson for sizing (HUNT_H15.md).
- **Random numbers**: numpy `default_rng` with fixed seeds throughout `research/hunt/`; reproducible, and its seed
  sequence avoids the stream-overlap trap the book describes for simple generators. A second-generator re-run is not
  done.
- **R103 / DEEP_OPTIONS_SELLING.md**: the six "passes" came from stale far-wing prices; repriced with flat Black-Scholes
  at the last IV, all six lose. Method 3.1 (a skew model) would reprice wings with the put skew R102 measured; it would
  make wings dearer, not rescue the result.
- **R102**: VIX over-states NIFTY's next-month realised vol on 81% of days (n 4,507) and the put side is dearer on 91%
  of days; the book's CEV / displaced-diffusion skew and variance interpolation are modelling tools for these facts,
  not edges.
- **R16 / R88 (size predictable, direction not)**, **R41 neural nets**, **GEX (R28, R36, R37)**, **IV1**, **R72**,
  **H39**, **costs (HUNT_H24 / baselines.yaml)**: the book says nothing about them; it ignores trading costs entirely.
  A simulated valuation must have our costs (about Rs 70 NIFTY / Rs 76 BANKNIFTY per round trip) added before use.
- Overlaps: fm-brandimarte-numerical.md (error bars, antithetic, CRN, control variates, importance sampling, Sobol',
  discrete-barrier shift, nested-decision bias), fm-copula-methods.md (copulas), bennett-trading-volatility.md (vol
  trading). Not repeated here.

## What is new to us
- A lower-tail copula (Clayton or t) joint simulation of all five indices and the MCX/crypto books to estimate how often
  several arms lose on the same day against the account stop.
- Repairing correlation matrices built from gappy, asynchronous data (NSE vs MCX vs 24x7 crypto hours).
- Monte Carlo error and a convergence table for every bootstrap / twin p-value; a second-generator re-run for key p-values.
- Greeks of a stop-and-target-managed option position by likelihood-ratio weights, rather than the plain option's.
- Fitting a simple two-variable exit boundary on design paths and scoring on fresh ones as a template for exit research.
- Interpolating IV across expiries and intraday horizons in total variance, with a calendar-arbitrage check.
- A one-parameter skew model (displaced diffusion) to reprice off-window wings consistently with the chain's put skew.
- The discrete-monitoring shift to measure how much bar-close stop checks flatter a backtest.

## Could not read
- Most displayed formulas are garbled by OCR throughout (all chapters); the prose around them was enough to follow
  each method, and only short standard results are restated here.
- Figures are images or rotated pages that OCR turned into noise: the copula density plots (Figures 5.2, 5.6-5.9), the
  generator projection plots (Figure 8.1), discrepancy charts (8.2-8.9, legends only), Box-Muller/Neave plots (9.5,
  9.6), path-construction and Asian-option convergence charts (10.5-10.7), all Greek convergence charts (11.1-11.5),
  the non-recombining tree charts (13.2, 13.3) and the volatility interpolation charts (14.1-14.4, the table of input
  vols was readable). Sobol' initialisation Table 8.3 is unreadable. Pages 213-232 (bibliography, index) were skimmed.
