# Mathematical Finance: Theory, Modeling, Implementation - Christian P. Fries (2007)

Source: mathematical-finance-fries.txt (pdftotext text layer), 403 numbered pages (405 in the file; "public beta" version
1.4.18 of 1 May 2007), read in full on 25 Oct 2026: chapters 1-27, appendices A-D, list of figures, bibliography and
index. Formulas came through the text layer jumbled but mostly readable from context; charts came through as axis
labels only (see "Could not read").
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A bank quant's textbook on pricing derivatives: probability, Ito calculus, replication and risk-neutral pricing.
It applies these to Black-Scholes stock options, the Greeks and hedging when you can only trade at discrete times.
Most of the book is interest-rate modelling (bonds, swaps, caps, the LIBOR market model, short-rate and HJM models).
A large numerical part covers Monte Carlo, lattices, Bermudan (early-exercise) pricing and Monte Carlo sensitivities.
It ends with hybrid models, object-oriented Java design, and tools (regression, random numbers, PCA, root finding).

## Methods, rules and setups

The book has **no trading rules**: no entries, stops or exits on any market. It teaches how to price, hedge and simulate.
Below is a chapter-by-chapter account; each PRACTICAL method gets a full block, pure theory gets a line or two. Our
data, for the mapping: NIFTY weekly (Tuesday) and BANKNIFTY monthly options (1-minute rolling chain, ATM +-10 strikes,
IV and OI), index spot / futures bars, India VIX daily, MCX futures and options, Delta Exchange India crypto options
(rupee wallet, daily expiries). Indian index options are European and cash-settled, which is the book's main case.
Costs: about Rs 70 (NIFTY) / Rs 76 (BANKNIFTY) per option round trip. Volatility-trading desk practice (variance swaps,
dispersion, skew trades, gamma scalping as a trade) is covered in `research/books/bennett-trading-volatility.md` and is
not repeated here.

### Chapter-by-chapter

- **Ch. 1 Introduction.** Theory, model and code must go together; the reference asset (numeraire) is usually an
  interest-rate product, so rates come first. Suggested short reading paths (e.g. "Monte Carlo pricing": Ch. 2-4, 13).
- **Ch. 2 Foundations (theory).** Probability spaces, sigma-algebras read as "information", conditional expectation as a
  filtered version of a random quantity, Brownian motion as the limit of a scaled random walk, Ito integral and Ito's
  lemma, correlated Brownian motions built from independent factors, martingales, Girsanov (a change of measure is a
  change of drift), Feynman-Kac (an expected payoff equals a PDE solved backwards from the payoff). One practical crumb
  is in method 1 (the volatility drag).
- **Ch. 3 Replication (mostly theory).** A forward on a traded stock is priced by buy-and-hold replication; a forward on
  rainfall cannot be, because rain is not traded. Two-state example: the "risk-neutral" probability does not depend on
  the payoff, so one measure prices everything; each numeraire has its own measure. Self-financing strategies,
  completeness and the universal pricing theorem. Method 2 below.
- **Ch. 4 Black-Scholes European option.** Method 3.
- **Ch. 5 Density of the underlying from call prices.** Method 4.
- **Ch. 6 Interpolating option prices across strikes.** Method 5.
- **Ch. 7 Hedging in continuous and discrete time, the Greeks.** Methods 6-8.
- **Ch. 8 Interest-rate structures.** Zero bonds as the building block; forward rate, yield, instantaneous forward,
  short rate. Store discount factors and derive every rate from them; bootstrap the curve one maturity at a time;
  interpolate linearly in the log of the discount factor (keeps monotone discount factors and flat curves flat). For us
  only the input rate of an option pricer depends on this (we use a flat rate).
- **Ch. 9 Simple interest-rate products.** Coupon bond, clean vs dirty price, floater (worth par at reset), swap, par
  swap rate as a weighted average of forward rates, caps, floors, swaptions, digital caplet as minus the strike slope of
  the caplet price (approximated by a tight call spread), foreign caplet and quanto. The call-spread idea is in method 4.
- **Ch. 10 Black model for a caplet.** Method 9.
- **Ch. 11 Quanto caplet.** Method 10.
- **Ch. 12 Exotic derivatives.** European prices depend only on total variance; products with several exercise dates
  also depend on how volatility is spread over time, so exotics "test" a model. Catalogue: structured bonds and swaps,
  Bermudan options (choose the larger of exercise now and hold), callable vs cancelable, compound options, trigger
  products, target redemption notes (coupons stop once their sum reaches a target), capped / floored / inverse
  floaters, CMS and CMS spread, range accruals (pay for each day a rate stays inside a band), snowballs, ratchet caps,
  auto caps vs chooser caps (limited number of exercises), shout options. Theorem: a right to "lock in" a convex payoff of
  a rising (sub-martingale) price early is worth nothing; you always wait. None of these products is open to Boss.
- **Ch. 13 Discretisation of time and state.** Methods 11 and 12.
- **Ch. 14 PDE numerics.** One page: trees are explicit finite-difference schemes; references only.
- **Ch. 15 Bermudan options in Monte Carlo.** Methods 13-15 (exit timing, foresight bias, perfect-foresight bound).
- **Ch. 16 Path-dependent options backwards.** Add the running quantity (accrued notional, last coupon, exercises used)
  as an extra state and interpolate between its grid values. Conceptual link to method 12.
- **Ch. 17 Sensitivities of Monte Carlo prices.** Method 16.
- **Ch. 18 Proxy simulation schemes, importance sampling.** Methods 16 and 17.
- **Ch. 19 Market models (LIBOR / swap-rate).** Method 18 (calibration and overfitting).
- **Ch. 20 Instantaneous vs terminal correlation.** Method 19.
- **Ch. 21 Heath-Jarrow-Morton (theory).** Model the whole forward curve; no-arbitrage fixes the drift once the
  volatility is chosen.
- **Ch. 22 Short-rate models.** Market price of risk (excess return per unit of risk, the same for every bond); table of
  models (Vasicek, Hull-White, Ho-Lee, Dothan, Black-Derman-Toy, Black-Karasinski, Cox-Ingersoll-Ross). Method 20.
- **Ch. 23 Short-rate and LIBOR models inside HJM (theory).** Constant volatility (Ho-Lee) makes the curve steepen
  forever; volatility that decays with maturity is the same thing as mean reversion of the short rate. The LIBOR model
  is HJM on a discrete grid.
- **Ch. 24 Curve shapes under mean reversion and factors.** Simulations (100 paths, same random numbers across settings)
  show mean reversion growing with the decay speed, and how few factors force near-parallel curve moves. Factor count
  is in method 18.
- **Ch. 25 Markov functional models.** Method 21 (skew-correct digital probabilities; marginals vs dynamics).
- **Ch. 26 Hybrid models.** Rates in two currencies, FX and stocks under one common measure; a foreign asset is only
  tradable after conversion, which adds a correlation term to its drift (method 10).
- **Ch. 27 Object-oriented Java.** Classes, encapsulation, interfaces, inheritance ("extends" rather than "is a"),
  polymorphism, illustrated with root finders. Method 22.
- **Appendix A Tools.** Least-squares regression; Mersenne Twister (period 2^19937 - 1, evenly spread in up to 623
  dimensions); inverse-CDF and Box-Muller normals; event intensity from an event probability; correlated Brownian
  motion by eigen-decomposition; factor reduction (PCA); golden-section search; closed-form partial expectations of
  exp(aX) for normal X. Methods 18, 22.
- **Appendices B-D.** Exercises (tower law, Brownian scaling, quadratic variation, Ito vs Stratonovich, Ito product /
  quotient rules, the Black-76 swaption formula), list of symbols, Java listings. Nothing practical beyond the above.

### 1. Volatility drag: a log price drifts below the simple drift (Ch. 2.6, Lemma 49)
- What it computes: for a price with drift mu and volatility sigma, the log price grows at mu - sigma^2 / 2; a
  non-linear function of a driftless walk gains a drift (the book's x^2 example gains +1 a step).
- Inputs: drift and volatility estimates.
- How we could use it: Monte Carlo of NIFTY or crypto must use the log form (method 11); explains why a strategy's
  average daily return overstates its compounded growth; the same convexity is what an option holder owns.
- Signal source: none (a modelling fact); maps to NSE / MCX / crypto: all.
- Mechanical? yes (a formula).
- Our research: not tested by us as such; H15 (`HUNT_H15.md`) compounding with half-Kelly showed growth far below the
  arithmetic edge, with P(drawdown >= 50%) 14%.

### 2. Replication and the forward / synthetic forward (Ch. 3)
- What it computes: the fair forward of a traded asset = spot minus the present value of the delivery price (plus
  carry); real-world probabilities do not enter. Put-call parity gives the same forward from options.
- Inputs: spot, rate, dividends / carry, time.
- How we could use it: the fair NIFTY / BANKNIFTY futures level and basis; the option-implied (synthetic) forward from a
  call and a put at the same strike, as a cleaner "underlying" than a stale spot print.
- Signal source: underlying price (spot and futures) and the option chain as context; maps to NSE yes, MCX (futures vs
  spot gold / silver) yes, crypto (perpetual vs index) yes.
- Mechanical? yes.
- Our research: h29 (`HUNT_H29.md`) found the option-implied forward leads the index by 5-30 minutes (IC +5 to +13%,
  all 5 indices) but a buyer cannot use it: the options have already moved. h39 (`HUNT_H39.md`) found heavyweight
  futures basis changes move in the same minute as the index, with no lead at 2-15 minutes.

### 3. Black-Scholes price and implied volatility (Ch. 4, Remark 80)
- What it computes: European call price S N(d1) - K e^(-rT) N(d2) under a lognormal price with constant rate; the
  stock's real drift drops out. Implied volatility is the single sigma that reproduces a market price; Black-Scholes
  volatility is the square root of the average variance over the option's life.
- Inputs: spot (or forward), strike, time, rate, volatility.
- How we could use it: already used: R103 reprices stale wing legs with Black-Scholes at the last print's IV; our IV
  and GEX code (`research/brain/gex.py`) relies on it.
- Signal source: volatility / options structure; maps to NSE index options yes, MCX options (better via Black-76,
  method 9), crypto options yes.
- Mechanical? yes.
- Our research: DEEP_OPTIONS_SELLING.md uses a Black-Scholes table (NIFTY 25,000, 15% IV) for illustration and
  reprices stale legs; R102 warns the model breaks down in the last minutes of expiry (ATM IV prints 6.3 for NIFTY at
  15:15, DEEP_NIFTY_BANKNIFTY.md section 3.4).

### 4. The market's implied distribution and digital probabilities (Ch. 5, 9.2, 10)
- What it computes: the second strike-derivative of call prices (discounted) is the risk-neutral density of the index
  at expiry; minus the first derivative is the price of a digital (the risk-neutral probability of finishing above K).
  A tight call spread (buy K - e, sell K + e, divide by 2e) approximates the digital. Under Black, the digital is
  P(T) N(d2). Prices must be convex in strike, else some region has negative probability.
- Inputs: a strip of call (or put) prices for one expiry, the discount factor.
- How we could use it: from the NIFTY chain, the market-implied probability that NIFTY finishes above / below a level,
  and the implied tail mass beyond the short strikes of a strangle; compare with realised frequencies (R102 already
  measures how often the day beats the straddle). Risk-neutral probabilities include the risk premium, so they are not
  forecasts.
- Signal source: option chain as context (a read-out of the market's distribution, not a trading signal); maps to NSE
  yes (needs strikes beyond ATM +-10 for the tails, which our lake lacks), MCX options (thin chains) partly, crypto
  options (Delta) yes.
- Mechanical? yes.
- Our research: not tested by us as a density. Nearest: R102 measured the 09:20 straddle being beaten by the close on
  7.1% of NIFTY non-expiry days (n 961) and 40.5% of expiry days (n 259) (DEEP_NIFTY_BANKNIFTY.md section 3.1).

### 5. No-arbitrage checks and safe interpolation across strikes (Ch. 6)
- What it computes: a valid call-price curve falls as strike rises, falls no faster than the discount factor per unit of
  strike, and is convex (every butterfly costs >= 0). Linear interpolation of prices is arbitrage-free but implies a
  density made only of spikes at the quoted strikes; linear interpolation of implied vols creates negative density at
  the joins; cubic splines of prices or vols can create large negative-density regions. An implied vol that falls too
  fast with strike (book example 0.9 to 0.1) or rises too fast (0.2 to 0.8, which makes the higher-strike call dearer)
  is itself an arbitrage. Fixes cited: a mixture of lognormals (price = weighted sum of Black-Scholes prices), a
  shape-constrained spline, or a few-parameter smile model.
- Inputs: the quoted strike strip and the discount factor.
- How we could use it: a quote-quality filter for the lake: flag a strike whose price breaks monotonicity, the slope
  bound or convexity against its neighbours (a stale or bad print, not free money once costs and spreads count); fit
  smiles with a constrained form before reading tails or densities.
- Signal source: option chain (data quality, not a signal); maps to NSE yes, MCX options yes, crypto options yes.
- Mechanical? yes.
- Our research: our lake check (`HUNT_LAKE_STALE_PRINTS.md`) already applies the first condition (a call never above the
  call one strike lower, never below the one higher) to stale runs: 9,646 one-minute prices flagged of 35.4 million,
  none on NIFTY; it says smaller stale errors are missed. R103's six design "passes" came from far wings priced at
  stale prices; repriced, all six lose (DEEP_OPTIONS_SELLING.md section 0). The convexity (butterfly) and slope tests
  are not in our filter yet.

### 6. Greeks and the delta hedge (Ch. 7.2-7.3)
- What it computes: the replicating holding in each underlying is the price's first derivative (delta); the
  Black-Scholes PDE says time decay pays for gamma. Table of Black-Scholes Greeks: delta N(d1), gamma, vega, theta, rho.
- Inputs: the pricing formula and its parameters.
- How we could use it: position Greeks for any open basket (net delta in futures-equivalent lots, gamma near expiry,
  vega), as risk read-outs for Jarvis; the theta-gamma trade-off is R102's decay clock.
- Signal source: volatility / options structure; maps to NSE yes, MCX options yes, crypto options yes.
- Mechanical? yes.
- Our research: R102 decay clock (expiry-day straddle loses 87% of its 09:20 value by 15:15, n 259) and the per-minute
  gamma of expiry afternoons (ATM option moves a median 13.5% a minute in the last hour, n 30,562 contract-minutes),
  DEEP_NIFTY_BANKNIFTY.md sections 3.2-3.3.

### 7. Discrete-time delta and delta-gamma hedging, and the cost of a wrong volatility (Ch. 7.4)
- What it computes: hedging only at fixed times leaves an error each step of roughly half the gamma times (the squared
  move minus the variance the model priced); the cash account carries the error forward. With the right parameters a
  weekly hedge ends near the payoff with scatter (book example: volatility 0.5, rate and drift 0.05, strike 1, six
  years). Hedging with a volatility of 0.4 instead of 0.5 under-funds the portfolio: its mean ends below the payoff and
  its scatter grows. Adding a second (longer) option to match gamma cuts the error to third order; a static hedge (never
  rebalanced) is much worse; a vega hedge neutralises volatility changes.
- Inputs: model Greeks, hedge interval, realised path.
- How we could use it: a short straddle's day P&L is, to first order, theta earned minus half-gamma times the squared
  index move, i.e. implied variance against realised variance. That is the mechanism behind the sellers' 81% and their
  rare disasters. A delta-hedge with futures at fixed times is a testable structure (not a direction call).
- Signal source: volatility / options structure (hedge on the underlying futures); maps to NSE yes (NIFTY futures as the
  hedge; each rebalance pays costs), MCX yes, crypto yes (with a stop and a lock, CLAUDE.md).
- Mechanical? yes.
- Our research: R102: India VIX above NIFTY's next-21-day realised vol on 81% of days (median ratio 1.28, n 4,507), but
  below BANKNIFTY's on 60%. R103: defined-risk selling 0 of 24 after costs; a naked 09:20 short straddle on BANKNIFTY
  was positive but with a worst day of -Rs 65,483 (DEEP_OPTIONS_SELLING.md section 0). A delta-hedged version was not
  tested by us; see bennett-trading-volatility.md method 8 for the desk view.

### 8. Variance-minimising hedge ratio (Bouchaud-Sornette) (Ch. 7.5)
- What it computes: when trading is discrete the hedge is not exact, so pick the hedge ratio that minimises the variance
  of the hedging error under the REAL probabilities: per step, hedge = covariance(option change, underlying change) /
  variance(underlying change), or a backward recursion that targets the error at expiry. It generally differs from the
  Black-Scholes delta (skew, fat tails, jumps).
- Inputs: joint history (or simulation) of option and underlying changes over the hedge interval.
- How we could use it: estimate the empirical "minimum-variance delta" of NIFTY / BANKNIFTY options against futures from
  our 1-minute chain, by moneyness and time to expiry, and compare with Black-Scholes delta; useful for hedging a basket
  and for sizing a futures hedge of an option position.
- Signal source: volatility / options structure plus underlying price; maps to NSE yes, MCX partly, crypto yes.
- Mechanical? yes.
- Our research: not tested by us.

### 9. Black-76: options on a forward or futures price (Ch. 10)
- What it computes: under the measure that uses the payment-date bond as numeraire, the forward is driftless, so a call
  on it has the Black formula with the forward in place of spot and no carry term; the digital is the discounted N(d2).
- Inputs: futures price, strike, time, discount factor, volatility.
- How we could use it: the right pricer for MCX options (they are options on futures) and for crypto options quoted
  against a futures or index level; avoids guessing carry.
- Signal source: volatility / options structure; maps to MCX yes, NSE (index options priced off the synthetic forward)
  yes, crypto yes.
- Mechanical? yes.
- Our research: not tested by us; MCX_OPTIONS.md and HUNT_R96_SUMMARY.md study MCX option trades, not pricing.

### 10. Quanto and cross-currency drift corrections (Ch. 11, 26)
- What it computes: a foreign quantity paid in home currency at a fixed rate needs a drift adjustment of minus
  (correlation x FX volatility x the quantity's volatility).
- Inputs: the two volatilities and their correlation.
- How we could use it: for rupee-settled crypto options on a dollar index, only if the rupee conversion floats with the
  dollar rate; over a day the correction is tiny.
- Signal source: volatility / options structure; maps to crypto (Delta Exchange India) only.
- Mechanical? yes.
- Our research: not tested by us; HUNT_RC2_CRYPTO_OPTIONS.md notes Delta settles in cash on its index with a rupee wallet.

### 11. Monte Carlo simulation of prices (Ch. 13.1-13.2, Appendix A.2-A.3)
- What it computes: paths from small random steps. Euler on the price itself can go negative and is the wrong shape;
  stepping the log price is lognormal and, when the drift does not depend on the price, exact. Milstein only helps when
  volatility depends on the level; predictor-corrector improves the drift. Correlated assets come from independent
  normals times a factor matrix (eigen or Cholesky). Weighted Monte Carlo lets paths carry unequal weights. Good
  practice: store results as [time][asset][path] so payoffs are vector operations; draw each path's random numbers in
  one block (Mersenne Twister is evenly spread up to 623 dimensions, e.g. 7 assets x 89 steps); typical sizes are
  10,000-100,000 paths.
- Inputs: drift, volatility (or a bootstrapped return history), correlation, step size.
- How we could use it: simulate NIFTY / BANKNIFTY / gold / BTC paths to price stop-target-trail rules on an option, to
  check how often a stop would be gapped through, or to stress a short-option basket; our bootstraps already resample
  history (H15 used 5,000 paths of month blocks).
- Signal source: none (a tool); maps to NSE / MCX / crypto: all.
- Mechanical? yes.
- Our research: H15 (`HUNT_H15.md`) bootstrap of the capital path; it warns the bootstrap was optimistic because it
  resampled only 10 month-blocks from a good stretch.

### 12. Lattices and backward induction (Ch. 13.3-13.4, 14, 16)
- What it computes: a tree or grid of states with transition probabilities; values roll back from the payoff one step
  at a time (memory grows linearly, versus exponentially for all paths: 9 states x 4 steps = 6,561 paths but 252
  transitions). Trees cannot see the past, so path-dependent payoffs need an extra state (method in Ch. 16); more than
  about three state variables become too costly. A Monte Carlo can be run "through" a lattice.
- Inputs: a state grid and transition probabilities.
- How we could use it: dynamic programming for a day's decisions with a running state (trades used, day P&L against the
  day lock), e.g. to find when stopping for the day is optimal under a given return model.
- Signal source: none (a tool); maps to all.
- Mechanical? yes.
- Our research: not tested by us.

### 13. Exit timing as optimal stopping: regress the value of holding (Ch. 15.1-15.10)
- What it computes: at each decision date compare "exit now" with the expected value of holding; that expectation is a
  function of today's state, estimated by least-squares regression of realised future values on a few basis functions
  (Longstaff-Schwartz), or by averaging paths in bins (binning is regression on bin indicators). The estimate is used
  only for the decision, never as the payoff. Restricting the regression to paths where exercising is plausible
  improves the fit; high-order polynomials wiggle at the edges.
- Inputs: simulated or historical paths, a state vector (e.g. index move since entry, minutes left, IV, premium vs
  entry), the exit payoff.
- How we could use it: an exit rule "take profit / cut now if the regressed value of holding is below what we get now",
  fitted on design data and frozen. It is a principled version of our lock / trail studies.
- Signal source: underlying price for the state (option premium may be the stop / target level, which CLAUDE.md allows);
  maps to NSE yes, MCX yes, crypto yes (stop and lock still required).
- Mechanical? yes.
- Our research: not tested by us in this form; our exit studies (R18 exits, R19 locks, R64-R70 lock and trail variants)
  tested fixed rules.

### 14. Foresight bias: a rule fitted and judged on the same sample is too good (Ch. 15.5-15.9, 15.11)
- What it computes: using a path's own future for the decision gives a "super-optimal" value (book's two-path example:
  3 with foresight against 2.5 honestly). Even an honest rule, if estimated and then priced on the same simulated paths,
  inherits the estimation noise as a free option (the bias equals the value of an option on the Monte Carlo error). The
  cure is two independent samples: estimate on one, value on the other. Tuning a single threshold to maximise the
  simulated value gives a jumpy objective with spurious local maxima of size about 1/n, and a rule family of the wrong
  shape can miss the truth entirely (book example: when the true exercise region is an interval, a one-threshold rule
  only finds "always" or "never", both worth less).
- Inputs: any backtest that fits parameters.
- How we could use it: the textbook reason for our pre-registration, walk-forward and once-read holdout; also a warning
  to read a parameter grid as a plateau, not a peak, and to check that the rule family can express the effect.
- Signal source: none (method); maps to all.
- Mechanical? yes.
- Our research: agrees with our practice (CLAUDE.md research rules; HUNT_R30_ALL_PARAMETERS.md fitted on earlier years,
  tested on later: 0 of 30 passed the full gate). H15's Kelly fraction, fitted on the same 10 months the bootstrap
  resampled, was 1.29 there but 0.48 and 0.14 on other windows: the same bias.

### 15. Perfect-foresight upper bound and the dual method (Ch. 15.12-15.13)
- What it computes: the best exit with hindsight on each path is an upper bound; subtracting a suitable martingale
  removes the hindsight, and the gap between this upper bound and a rule's honest (lower) value shows how much better
  any exit rule could do.
- Inputs: paths and a fitted continuation value.
- How we could use it: report, for each arm, the hindsight-best exit (maximum favourable excursion) beside the rule's
  result, as an honest ceiling; if the ceiling minus costs is small, stop searching for exits.
- Signal source: none (method); maps to all.
- Mechanical? yes.
- Our research: not tested by us as a bound; our exit studies report excursions descriptively.

### 16. Sensitivities of simulated prices to a parameter (Ch. 17, 18.2)
- What it computes: the true price is smooth in a parameter, but a simulated price inherits every jump in the payoff,
  so bump-and-rerun derivatives of trigger payoffs have variance growing like 1/bump size. Remedies: pathwise
  derivatives (with an explicit term for each jump: jump size x density at the trigger), likelihood-ratio or Malliavin
  weights (good for jumps, worse for smooth payoffs), or a proxy scheme that keeps paths fixed and changes their
  weights. A very small bump can look stable and still be wrong, because no path changes its trigger outcome (book:
  a 1-basis-point vega that converges to a wrong value with tiny noise).
- Inputs: the simulator and payoff.
- How we could use it: when comparing parameter variants of a rule (stop 20% vs 25%), use the same days (common random
  numbers), bumps big enough that some trades actually flip, and look at which trades flipped; a stop or lock is a
  trigger payoff.
- Signal source: none (method); maps to all.
- Mechanical? yes.
- Our research: not tested by us as such; our twin / random-comparison p-values (e.g. HUNT_LAKE_STALE_PRINTS.md) are a
  related device.

### 17. Importance sampling for rare tails (Ch. 18.3)
- What it computes: simulate from a shifted process that visits the region that matters (e.g. an artificial drift
  towards a far strike) and correct with likelihood weights; the price spread across runs shrinks sharply (book: a caplet
  struck at 0.3 with the rate at 0.1).
- Inputs: the target model and a proxy drift.
- How we could use it: estimate the probability and size of a gap through a short strangle's wing, or of a crypto stop
  being skipped, with far fewer paths.
- Signal source: none (a tool); maps to NSE / MCX / crypto: all.
- Mechanical? yes.
- Our research: not tested by us.

### 18. Calibrate to today's market, avoid overfitting, reduce factors (Ch. 19, 24, Appendix A.4)
- What it computes: a pricing model must reproduce current traded prices, because the hedge is bought at today's
  prices; history is used only for parameters no market prices (and then conservatively). Fitting every quote exactly
  ("bootstrapping") also fits bad or stale quotes and cannot weight them; restricting to a few-parameter family (a
  volatility "hump" in time to maturity, correlation decaying with distance) trades a perfect fit for robustness.
  Principal-component factor reduction keeps the few largest eigenvalues (book: 40 or more rates, 5 or fewer factors);
  dropping large eigenvalues distorts correlations. Deriving a fine quantity by differencing coarse ones amplifies noise
  (a forward rate from two swap rates), while averaging fine ones smooths it.
- Inputs: market quotes, a parametric form, a correlation matrix.
- How we could use it: fit a 3-4 parameter NIFTY smile per expiry instead of trusting each wing quote; read forward
  volatility between two expiries with care (it is a difference of total variances and goes negative with noisy IVs);
  PCA of the five indices' returns or of the IV term structure (level / slope / curvature).
- Signal source: option chain as context; maps to NSE yes, MCX partly, crypto yes.
- Mechanical? yes.
- Our research: agrees with R103's lesson (stale far quotes created fake profits) and R102's caution that the front
  expiry's IV spikes on expiry day (ATM IV minus VIX +7.7 points for NIFTY, DEEP_NIFTY_BANKNIFTY.md section 3.4).

### 19. Instantaneous vs end-of-horizon correlation; spread volatility (Ch. 20, 24)
- What it computes: two quantities can be perfectly correlated move by move and still end the horizon uncorrelated if
  they receive their volatility at different times; an option on a sum or spread depends on the covariance of the sum.
  Book table: four settings with identical caplet prices (0.26%) give swaption prices of 0.51%, 0.50%, 0.36% and 0.27%.
- Inputs: the volatility time profile and correlation of each leg.
- How we could use it: for NIFTY-BANKNIFTY spread or breadth ideas, measure correlation at the holding horizon, not on
  1-minute returns; lead-lag between indices lowers short-horizon correlation.
- Signal source: underlying price (two indices); maps to NSE yes (R72-style cross-index), MCX (gold-silver) yes, crypto
  (BTC-ETH) yes.
- Mechanical? yes.
- Our research: R102 measured BANKNIFTY-NIFTY correlation 0.877 on daily returns (n 5,086) and 0.842 on 5-minute bars
  (89,575 bars) (DEEP_NIFTY_BANKNIFTY.md section 1.4). R72's laggard catch-up idea lost (C2, `HUNT_R72_CROSS_INDEX.md`),
  while C1 Breadth Donchian passed.

### 20. Mean-reverting processes and the market price of risk (Ch. 22-23)
- What it computes: Vasicek / Hull-White style mean reversion (pull towards a level at speed a; half-life ln 2 / a);
  volatility that decays with horizon is mean reversion in disguise. The market price of risk is the excess return per
  unit of volatility; under the pricing measure it is zero.
- Inputs: a level, a speed, a volatility (fitted to a spread or an index like VIX).
- How we could use it: model a spread (pairs) or India VIX as mean-reverting for sizing or stress, not as a timing
  signal by itself.
- Signal source: underlying price (spread or VIX level); maps to NSE (VIX, index spreads) yes, MCX (gold-silver ratio)
  yes, crypto (BTC-ETH) yes.
- Mechanical? yes.
- Our research: R102 lag-1 autocorrelation by horizon is in DEEP_NIFTY_BANKNIFTY.md section 2.5 (descriptive); no
  OU-model test by us.

### 21. Skew-correct digital probabilities; marginals do not fix the path (Ch. 25)
- What it computes: a digital's price equals the flat-vol digital plus a smile-slope term (vega times the IV slope in
  strike); the model is calibrated to these digitals so it matches every strike. Option prices at each expiry pin down
  only the distribution AT that expiry; behaviour between dates (forward volatility, how quickly the index forgets, what
  a barrier or stop sees) is a separate modelling choice.
- Inputs: the smile (IV by strike) and its slope.
- How we could use it: when quoting "the market's probability NIFTY ends below K", add the skew term; with puts dearer
  than calls (R102: put ATM-5 IV minus call ATM+5 IV +2.56 points, puts dearer on 91% of days), the downside tail
  probability is larger than flat-vol N(-d2) suggests. And: expiry prices alone cannot tell us how often an intraday stop
  is hit.
- Signal source: option chain as context; maps to NSE yes, crypto yes, MCX partly.
- Mechanical? yes.
- Our research: not tested by us.

### 22. Numerical building blocks (Ch. 27, Appendix A)
- What it computes: root finders as question-and-answer objects (bisection 43 iterations, Ridders 22, secant 15, Newton
  10 on the book's test function to 1e-11); golden-section search; the hazard rate from an event probability
  (intensity = -log(1 - q) / period); separation of product, model and calibration in code.
- How we could use it: implied-vol inversion by Newton with a bisection fallback where vega is near zero (deep wings,
  last minutes of expiry); event hazard from "probability of a 2% gap this week".
- Signal source: none (tools); maps to all.
- Mechanical? yes.
- Our research: not tested by us.

## Performance claims
No trading performance claims: the book does not trade. Its numbers are model illustrations, each with no market
evidence: weekly delta hedging with a wrong volatility ends below the payoff (Fig. 7.1); delta-gamma hedging is tighter
than delta hedging (Fig. 7.3); importance sampling narrows price spreads (Fig. 18.2); root-finder iteration counts; the
correlation table in Ch. 20; the 2004 target redemption note coupon (7.5% minus twice the rate) as a product example.

## Risk, sizing and psychology
Risk here means hedging error: the residual from discrete hedging, model risk from a wrong volatility or rate (the
hedge is under-funded and noisier), and measuring hedge risk under real-world probabilities (Ch. 7.5). Calibration to
noisy quotes is a risk in itself (Ch. 19). No position sizing, Kelly, drawdown rules or psychology.

## How it relates to our research
- **Agrees:** the seller's edge is implied over realised (R102: 81% of days, median 1.28, n 4,507), and the discrete
  hedge maths shows why it comes with gamma tails (R103: 0 of 24 defined-risk tests; naked straddle worst day
  -Rs 65,483).
- **Agrees, and explains a bug we hit:** prices must be monotone and convex across strikes; R103's fake profits came from
  stale far wings (repriced, all six lose). Our lake filter checks monotonicity only (9,646 flagged of 35.4 million);
  the book adds convexity and slope bounds.
- **Agrees with our method:** foresight bias (Ch. 15) is the reason for our pre-registration, walk-forward and one
  holdout read; H15's unstable Kelly fraction (1.29 in sample, 0.48 / 0.14 elsewhere) is the same bias.
- **Consistent with "size, not direction":** pricing never needs the drift (Ch. 3-4), only volatility; our size
  forecasts work (R16: 30-minute range error 18-20% smaller than the time-of-day average) and direction does not (R16
  AUC 0.509-0.536; R41 AUC 0.510-0.517).
- **GEX** (R28, R36, R37) is Greeks aggregated over OI; the book gives the Greeks but not GEX. R36 found GEX's effect
  was mostly low IV (correlation -0.69).
- **Option-market signals** (IV1, h29) are not discussed as signals; the book treats the chain as prices to fit.
- **Not covered:** costs and slippage, Kelly, GARCH, neural nets, regime models, copulas, VaR. See
  bennett-trading-volatility.md for vol trading practice.

## What is new to us
1. **Convexity and slope checks on the strike strip** (butterflies >= 0, call-spread slope within the discount factor)
   as an extra lake filter for stale or bad wing prints, beyond the monotonicity test we run.
2. **Market-implied tail probabilities from the chain** (call-spread digitals with the skew term) compared with realised
   frequencies by VIX level and days to expiry: a description of how dear the wings are, needing strikes beyond ATM
   +-10.
3. **Minimum-variance hedge ratios** of NIFTY / BANKNIFTY options against futures, by moneyness and time to expiry,
   versus Black-Scholes delta.
4. **Regression-based exit timing** (Longstaff-Schwartz continuation value) fitted on design data, as a principled
   alternative to fixed locks; plus the hindsight-best exit as an honest ceiling for every arm.
5. **Testing practice:** common random numbers (same days) and big-enough bumps when comparing rule parameters; check
   that the rule family can express the effect; importance sampling for gap-through-stop risk.
6. **Black-76 on futures** as the default pricer for MCX options.

## Could not read
- Formulas: the text layer splits fractions, subscripts and integrals across lines; most were readable from context,
  but some derivations (Ch. 19 drift terms, Ch. 25 Markov functional formulas) were only partly legible. Nothing
  practical depended on them.
- Figures: about 89 charts (hedge scatter plots, simulated curves, densities) came through as axis labels and
  captions only.
- Ch. 9 (around the swap definitions, about page 126): the text layer overlays an older German version on the English,
  so a few lines are garbled; the English definitions that follow are intact.
- The book's online applets (hedge simulator, interpolation, LIBOR simulations) are referenced but not part of the text.
