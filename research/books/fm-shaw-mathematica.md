# Modelling Financial Derivatives with Mathematica - William T. Shaw (1998)

Source: modelling-financial-derivatives-with-mathematica (Cambridge University Press 1998, Nomura International; ISBN
0 521 59233 X), 547 scanned pages (preface, 28 chapters, index), read in full on 25 Oct 2026 (tesseract OCR of a scanned
book; every chapter read in order, notes kept as we went). Formulas and Mathematica code came out partly garbled; the
prose, tables of numbers and section structure were readable. The book's CD-ROM (Mathematica 3 notebooks and the
`Derivatives` packages) is in the repo download; it is code, and per the brief it was not opened or run.
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A quant's handbook from Nomura's London model-verification team, written as Mathematica 3 notebooks.
It derives the Black-Scholes equation by dynamic hedging and solves it by heat-equation methods for many contracts.
It builds exact formulas and symbolic Greeks for log, power, binary, vanilla, barrier, lookback, Asian, American and other exotics.
It then tests the three numerical workhorses (finite differences, trees, Monte Carlo) against those exact answers and catalogues how each one fails.
It ends with interest-rate models, yield curves, Black-76 and the CEV model, which produces a volatility skew.

There are no trading rules in this book. Its value for IraAlgo is (a) a set of warnings about when option numbers we
compute (implied vol, Greeks, simulated values) can be quietly wrong, (b) a few pricing tools we use or could use
(Black-Scholes and Black-76 Greeks, dividend handling, two-asset spread pricing, correlated simulation, robust
regression), and (c) one structural explanation of the put skew. Standard pricing and hedging theory is not repeated
here: see `research/books/fm-wilmott.md` (Black-Scholes, vol-arb hedging, discrete hedging, Kelly, VaR),
`research/books/fm-binomial-models.md` (trees, parity, Greeks from trees, implied trees) and
`research/books/bennett-trading-volatility.md` (volatility trading in practice).

## Methods, rules and setups (chapter by chapter)

### Preface and Ch. 1 Advanced tools for rocket science: model risk and verification
Defines "algorithm risk" (a correct model solved wrongly) and argues that no number should be believed until two
independent methods agree, or the method has been checked against an exact benchmark on special cases. Greeks should be
got by exact differentiation of a formula, not by bumping a spreadsheet. The opening case study shows that implied
volatility only makes sense when price rises strictly with volatility.

**Method 1 - Verification discipline for every pricing number (Pref., Ch. 1, 7, 13)**
- What it computes: a test suite. Every numerical pricer (tree, grid, simulation, or our repricing code) must first
  reproduce the exact Black-Scholes value and Greeks on vanilla cases, then agree with a second independent method on the
  exotic case. Two of the author's sayings: give one problem to six groups and get at least three different answers;
  get the answers you can check right before trusting the ones you cannot.
- Inputs: the pricer under test, a closed-form benchmark, a grid of spots, strikes, vols and expiries.
- How we could use it: R103 and DEEP_OPTIONS_SELLING reprice stale far-wing legs with Black-Scholes at the last print's
  IV; the app also computes Greeks for display and for the option-chain context features. A small fixed table of
  benchmark values (e.g. call S9 K10 vol 20% r 10% T 1y = 0.694898, delta 0.529175, gamma 0.221042, theta -0.764856;
  put at S110 K100 = 1.73251) belongs in the unit tests of any pricing code we ship.
- Signal source: none (tooling). Maps to: NSE index options, MCX options, crypto options alike.
- Mechanical? yes.
- Our research: not tested as such; R103's repricing step is exactly the kind of model use this method is meant to check.

**Method 2 - Only quote implied volatility where vega is clearly non-zero (Ch. 1, 5, 8)**
- What it computes: a validity check on an implied-vol inversion. Shaw shows a warrant whose price is almost flat in vol
  below 20% (IV unstable), an up-and-out call with two (with a rebate, three) implied vols for one price, an
  up-and-out put with strike = barrier and r = q whose vega and gamma are identically zero (any or no IV), and a log
  contract whose IV does not exist above a critical price (0.0904837 in his example) and swings from 4.7% to 6.6% for a
  0.001 price change just below it. His rule: do not report an implied vol unless the absolute vega is bounded away
  from zero.
- Inputs: option price, model, vega at the solution.
- How we could use it: gate every IV we read from the chain on vega (and on time left). R102 section 3.4 already saw the
  symptom: at 15:15 on expiry day the ATM IV print was 6.3 (NIFTY) / 14.4 (BANKNIFTY) and the doc says not to read IV
  there. A vega floor (and a minimum premium in ticks) would make that rule mechanical for the IV features in the Data
  Brain and for R103-style repricing.
- Signal source: option chain as context (data-quality filter). Maps to: NSE index options yes; MCX and crypto options yes.
- Mechanical? yes.
- Our research: consistent with R102 3.4's expiry-day warning; not tested as a filter.

### Ch. 2 An introduction to Mathematica
A software tutorial (numbers, lists, algebra, calculus, special functions, graphics, packages). Finance-relevant asides:
a Newton root-finder needs a good start or it jumps to a far root; a numerical integral that warns about convergence
must not be believed; random walks are built by repeated multiplication. No method for us.

### Ch. 3 Mathematical finance preliminaries
Ito's lemma, the delta-hedged portfolio and the Black-Scholes equation with dividends; risk-neutral pricing as the basis
of Monte Carlo. A simple default model for convertibles (grow at r - q, discount at r plus a spread equal to default
intensity times loss). A two-currency framework that reduces foreign, composite, quanto and FX options to ordinary
Black-Scholes with adjusted rates, yields and vols (quanto yield q + r_dom - r_for + correlation x both vols). The useful
part for us is two identities that hold for any European option:

**Method 3 - Greek identities as cheap cross-checks (Ch. 3, 5, 6, 20)**
- What it computes: vega = S^2 x sigma x (time to expiry) x gamma, and rho = -(time to expiry) x (V - S x delta). They hold
  even for binaries with singular payoffs and for multi-asset Europeans, not for American options.
- Inputs: price, delta, gamma from any engine.
- How we could use it: a unit test on our Greek code (vega and rho must match these from delta and gamma); and a way to
  get vega from a gamma estimate without re-pricing. Shaw's trinomial tree in Ch. 20 gets all six Greeks from one tree this
  way (rho 4.06889 and vega 3.58193 against exact 4.06768 and 3.58087).
- Signal source: none (tooling). Maps to: all European options we touch (NSE index, MCX, Deribit/Delta crypto).
- Mechanical? yes. Our research: not tested by us.

### Ch. 4 Mathematical preliminaries: solving the PDE
Pure theory, done carefully. The Black-Scholes equation becomes the heat equation in log-price and scaled time; its
steady states give the perpetual American put and call; the Green's function gives every European value as a
discounted average of the payoff over a lognormal; "cut-off power" payoffs are the building blocks of binaries and
vanillas; barriers come from the method of images, rebates from Laplace transforms, lookbacks from an impedance boundary
condition; discrete dividends and coupons enter as jump conditions and source terms. No trading content.

### Ch. 5 Log and power contracts
The log contract (pays the log of the final price) has a delta of discount factor / S: independent of volatility and
stable in time, so it hedges correctly even with the wrong vol (the ancestor of the variance swap; Neuberger 1990).
Delta here exceeds 1 for small S, which the author uses to kill the idea that delta is a probability. Power contracts
show assorted Greek and IV oddities. Context only (no listed log contracts in India).

### Ch. 6 Binary options and the normal distribution
**Method 4 - The near-expiry, near-the-money hedging nightmare (Ch. 6)**
- What it says: a cash-or-nothing binary is worth the discounted N(d2). As expiry nears, its delta collapses into a
  spike at the strike and its gamma into the derivative of a spike, so a hedger near the strike buys and sells huge
  amounts as the price wanders across it; with costs this wrecks the hedge. The vega-gamma identity still holds.
- How we could use it: this is the mathematical face of what R102 measured empirically: an ATM option moves 13.5%
  (NIFTY) / 15.9% (BANKNIFTY) a minute in the last hour of expiry day (R102 3.3), and fixed strikes lose 82-88% of
  their value by 15:09 on expiry (EXPIRY_PLAYBOOK and h42, as summarised in DEEP_NIFTY_BANKNIFTY 3.2). Any expiry-day logic that uses delta or gamma (sizing, hedge ratios) should cap them near the
  strike in the last hour.
- Signal source: volatility / options structure. Maps to: NSE weekly expiry yes (NIFTY Tuesday, SENSEX Thursday);
  MCX option expiries; crypto daily expiries even more so.
- Mechanical? partly (a cap is mechanical; its level is a choice). Our research: the measurements above, not a rule test.

**Method 5 - Computing the cumulative normal correctly (Ch. 6)**
- What it computes: the error of the textbook rational approximations to N(x). The 3-term Hastings form is off by
  about 4% (relative) in the far left tail, and its numerical derivatives (used if gamma is got by differencing prices)
  are off by 0.003 at the centre; the 5-term form (Hull's) is better (under 0.7% relative in the tail, about 1e-7
  absolute). Never compute N(-x) as 1 - N(x) for large x (cancellation). Recommended: the continued-fraction
  expansions (Abramowitz-Stegun 26.2.14 and 26.2.15) or a library erfc.
- How we could use it: check which N(x) the Kotlin pricing code uses; far-OTM wings (the ones R103 had to reprice)
  live exactly in the tail where the cheap forms are worst.
- Signal source: none (tooling). Maps to: all. Mechanical? yes. Our research: not tested by us.

### Ch. 7 Vanilla European calls and puts
Black-Scholes-Merton with a dividend yield; Greeks by symbolic differentiation and 2D/3D surfaces; implied vol by
Newton's method from a start of 20% (call S58.5 K60 r 4% T 0.3 at price 3.34886 gives 0.29; at S56.5 the same price
gives 0.364928); a packaged library that all later chapters test against. Standard; see fm-wilmott.md Method 1.

### Ch. 8 Barrier options
Rubinstein-Reiner barrier formulas (eight in/out up/down call/put types, with rebates) checked against the published
tables, with every Greek by differentiation. The point for us: barrier options can have negative gamma and negative
vega (more vol means more knock-outs), so a cost model that just adjusts the volatility breaks down. Context only: NSE
lists no barrier options; the mechanics resemble a stop-loss on a long option (the position "knocks out" at the stop).

### Ch. 9 Lookback and ladder options
Lookbacks pay against the best price seen since the start. Two published formula sets agree (S50, vol 20%, r 5%, 1y:
floating lookback call 8.6084, put 7.14528; call on the maximum, strike 50, 9.58381). Warning: the running max or min
counts only from the option's start. A ladder option locks in gains each time the price reaches a preset rung and is
built from a vanilla call, puts and up-and-out puts (three rungs 60/70/80, strike 50, S50, vol 40%, 3 months: 5.77933
if the high so far is 55, 29.3159 once it has been above 80). Context: a ladder is the option-market price of a step
profit lock, the lock family we test in HUNT_R19*; not tested by us as a priced product.

### Ch. 10 Asian options (analytic)
Geometric averages are exactly lognormal (Black-Scholes with vol / sqrt 3 and an adjusted yield). The Turnbull-Wakeman
moment match for arithmetic averages is good up to about 20% vol and biased above (ATM 0.249791 against the exact
0.246417 at 50% vol). The Geman-Yor exact answer is a Laplace transform inverted numerically; its residues give the
deep in-the-money formula. Effective vol for discrete, delayed-start geometric averaging. Context only (no average-price
options on NSE/MCX).

### Ch. 11 American options (analytic)
The American put as a moving-boundary problem; the MacMillan / Barone-Adesi-Whaley quadratic approximation explained as
a "quasi-stationary" method; the Carr-Jarrow-Myneni integral form with an accurate semi-analytic front (K50, vol 40%,
r 10%, 1y ATM: European 5.40111, MBAW 6.01219, CJM 5.97688); Roll-Geske-Whaley for a call with one cash dividend
(early exercise only if the dividend exceeds K(1 - e^(-r tau)), 2.43853 in his example). Low relevance: NSE index and
stock options, MCX options and the crypto options in scope are all European.

### Ch. 12 Double barrier, compound, quanto and other exotics
Double knock-out calls by the Geman-Yor Laplace transform (Fourier series suffer the Gibbs effect near the barriers;
Monte Carlo needs huge samples; trees misplace nodes), with negative gamma and vega (S2 K2 barriers 1.5/2.5: value
0.0410889, gamma -0.454404, vega -0.38284). Compound options through the bivariate normal; quantos by yield
adjustment; warrants with dilution solved as a fixed point; choosers as a call plus a put; and:

**Method 6 - Exchange (Margrabe) option: pricing one index against another (Ch. 12, 23)**
- What it computes: the value of swapping asset 1 for asset 2. It is Black-Scholes on the price ratio with
  vol^2 = vol1^2 + vol2^2 - 2 x correlation x vol1 x vol2, and it does not depend on the interest rate (100 vs 100,
  both 25% vol, zero correlation, about 1y: 14.0506). Ch. 23 extends this to spread calls with a strike by
  two-dimensional integration (12.0357, matching a Monte Carlo 12.1685 with standard error 0.0695).
- How we could use it: a fair-value yardstick for relative-value ideas between NIFTY and BANKNIFTY (or GOLD vs SILVER on
  MCX): the ratio vol tells how much one index's option overlay should cost against the other. R72 found its one pass
  (Breadth Donchian) by reading the indices together, not by trading the spread.
- Signal source: volatility / options structure (pricing of a two-asset payoff). Maps to: NSE (no listed spread
  options; synthetic via two legs), MCX (GOLD/SILVER ratio), crypto (BTC/ETH) as an analysis tool.
- Mechanical? yes (as a pricer). Our research: R72's laggard catch-up and lonely-breakout ideas (pairs-like) failed
  (C2 final -Rs 1,00,746 on 613 trades); the exchange-option pricer itself not tested by us.

### Ch. 13 The discipline of the Greeks; overview of finite differences
The key lesson of the book: a numerical error that is small in the price can be moderate in delta and huge in gamma
(the author's example: a function that tends to zero while its second derivative grows without limit). The theta-method
family is laid out: explicit (the binomial tree on a grid), fully implicit, Crank-Nicolson, and the Douglas scheme
(fourth-order in space; the natural implicit form of the trinomial tree). Crank-Nicolson is not strongly stable, so a
kinked payoff leaves slowly decaying wiggles near the strike when steps are large.

### Ch. 14 Finite differences with smooth initial data
On a smooth test problem: explicit with 400 steps errs by about 3.5e-5, fully implicit with 400 by 8.4e-5 (no better,
just stable), Crank-Nicolson with 40 by 2.4e-5, Douglas with 40 by 6.1e-7 (about 1/40 of Crank-Nicolson). Linear
interpolation of grid output is useless if you then differentiate it; use cubic.

### Ch. 15 Finite differences with option payoffs
**Method 7 - Validate Greeks, not just prices, near the strike near expiry (Ch. 13, 15, 16)**
- What it says: on a European put (K10, vol 20%, r 5%, 5y) Crank-Nicolson with a large step (20 steps) priced within
  0.00171 everywhere, yet erred by 12.4% in gamma and 197% in theta at the strike. The three-time-level Douglas scheme
  with the same step cut those to 0.2% and 2.6% (delta within 0.1%). The two-level Douglas scheme was as bad as
  Crank-Nicolson here.
- How we could use it: any place we compute Greeks numerically (by bumping our own repricing, or by differencing chain
  prices across strikes) should be checked for gamma and theta near the strike close to expiry, where R102 shows the
  option moves fastest. Prefer the closed form for European index options.
- Signal source: none (tooling). Maps to: all. Mechanical? yes. Our research: not tested by us.

### Ch. 16 SOR and PSOR with the three-level Douglas scheme; American options
Successive over-relaxation (relaxation 1.1) replaces the tridiagonal solver; the projected form enforces early
exercise. A 5-year European put with only 20 steps still gets delta within 0.1%, gamma 1.3%, theta 3.5%. American puts
from the grid and from the CJM formula agree to three decimals (1y, S10: 0.609, delta -0.411, gamma 0.229 / 0.230).
Gamma jumps at the exercise front (8.08038 here); ordinary differencing smears the jump and even makes gamma negative;
the fix is to locate the front from the spike in the third derivative and difference each side separately.
Low relevance for us (European exercise everywhere we trade).

### Ch. 17 Linear programming in place of PSOR; robust regression
The American-option grid problem is a linear complementarity problem and can be solved as a linear programme
(Dempster-Hutton); the answers equal PSOR's. Then a tool we can use:

**Method 8 - Least-absolute-deviation (robust) line fits (Ch. 17)**
- What it computes: the line minimising the sum of absolute errors, solved as a linear programme. On the "pilot plant"
  data least squares and LAD agree (slopes 0.321608 and 0.317568); after one point is moved far away, least squares
  collapses (slope 0.0807115) while LAD stays on the trend (0.308943).
- Inputs: x, y pairs. How we could use it: any fit on noisy market data with bad prints (expiry-day spikes, the stale
  wing prints R103 met, auction ticks), e.g. fitting a smile or a vol term structure, or estimating a hedge ratio
  between two indices.
- Signal source: none (estimation tool). Maps to: all. Mechanical? yes. Our research: not tested by us.

### Ch. 18 Traditional and "supersymmetric" trees
Trees re-derived without probabilities: weights and step sizes are chosen so the tree reproduces simple exact
solutions (cash, the asset, its square, its inverse). Cox-Ross-Rubinstein weights can go negative for big steps; exact
Jarrow-Rudd steps can go non-positive; Shaw's new binomial (CRR on the forward) and a trinomial that matches six such
solutions never do. Theory; see fm-binomial-models.md for tree basics.

### Ch. 19 Tree implementation and tree pathology
CRR values oscillate with the number of steps (odd/even), the envelope is lopsided so averaging odd and even trees is
not reliable, and the "exact" CRR is no better than the approximate one. "Magic" tree sizes put the strike midway
between two final nodes (for S9 K10 vol 20%: 8, 22, 44, 73, 109 ... steps) and converge fast, but they do not exist
at the money. With a barrier the error is huge: a down-and-out call worth 5.99684 prices at 6.18582 with 21 steps and
9.21181 with 22 (6.01963 and 7.8628 at 85 and 86); Boyle-Lau sizes (21, 85, 192, 342, 534) fix CRR, while drift-built
trees (JR, supersymmetric) cannot be aligned with a flat barrier at all. Context; we do not price with trees.

### Ch. 20 Compiled trees
Compiled, list-based trees run about seven times faster than the recursive version; a 200-step CRR gives 0.694964
against 0.694898. The supersymmetric trinomial removes the fast oscillation and, through Method 3's identities, gives
all six Greeks from one tree. Context only.

### Ch. 21 Monte Carlo and low-discrepancy sampling
**Method 9 - Monte Carlo Greeks with common random numbers (Ch. 21, 23)**
- What it computes: delta and gamma by re-using the SAME simulated paths for the bumped inputs (the author's "golden
  rule"), so the bump effect is not buried in sampling noise. Delta comes out well (0.856831, standard error 0.0022,
  against 0.85916); gamma stays noisy (0.0121205 against 0.0101583, standard error 0.00125) because most paths sit away
  from the payoff kink. For a two-index basket, per-index deltas from the same paths (0.652683 and 0.559352) differ from
  the single delta a "treat the basket as one asset" shortcut gives (0.615031), so that shortcut hedges wrongly.
- How we could use it: whenever we compare two variants of a rule on simulated or bootstrapped data (a lock level, a
  size), run both on the same resampled days; our twin and paired-difference tests already follow this logic.
- Signal source: none (method). Maps to: all. Mechanical? yes. Our research: H15's bootstrap of sizing rules resampled
  the same 10 month-blocks for every variant (a common-random-numbers design), and still flagged itself as optimistic.

Also in the chapter: the polar (Marsaglia) method is the fastest normal generator; antithetic pairs mostly just double
the sample; simulating straight to each event date with the exact lognormal step beats small Euler steps between events;
Hammersley-type evenly spread points (5,000 of them priced a call at 21.2637 against 21.2488) are a useful
quasi-Monte-Carlo alternative in many dimensions.

### Ch. 22 Monte Carlo on path-dependent options: the dangers
**Method 10 - Discrete monitoring bias and the "low standard error" trap (Ch. 22)**
- What it says: two separate errors. More paths shrink the standard error; more time steps per path shrink the
  discretisation bias. A maximum sampled at a few points is always below the continuous maximum, so path-extreme payoffs
  are under-priced. Lookback call (exact 8.6084): 2,000 paths x 10 steps give 7.1541 with standard error 0.081, a tight
  but wrong answer; 2,000 x 100 give 8.05555. His conclusions: a low standard error is no guarantee of an accurate
  result, and daily-sampled extremes need on the order of millions of samples.
- How we could use it: our backtests detect stop, target and trail hits on 1-minute or 5-minute bars. If a bar's
  high/low is used, touches are seen; if only closes (or a few intrabar samples) are used, touches of a level are
  missed and stopped-out trades look like survivors, flattering tight stops. Any simulated or bootstrapped path study of
  locks should step finely or use the bar range, and report bias separately from the standard error.
- Signal source: none (backtest method). Maps to: NSE, MCX and crypto backtests alike.
- Mechanical? yes. Our research: not measured as such; our engines use bar highs/lows (worth confirming per study).

Asians and control variates: geometric-average values are exact (continuous 0.222788 at the money; monthly sampling
0.216852, Monte Carlo 10,000 paths 0.213316). Using "arithmetic minus geometric on the same paths" as a control variate
cuts the standard error several-fold, but the answer converges to whichever version (discrete or continuous) the control
represents: with the discrete control the at-the-money result is 0.240 (the discretely sampled value), with the
continuous one 0.248, nearer the continuous exact 0.246417. Choose the control for what you want to estimate.

### Ch. 23 Monte Carlo for baskets
Correlated normals from the eigen-decomposition (or Cholesky) of the covariance matrix; antithetic samples; memory
tricks. Example (vols 40% and 15%, correlation 0.1, 225 days): basket call 17.3487 (standard error 0.0827); with the
geometric basket as control variate 17.2536 (standard error 0.01135, worth about 55 times the paths); direct
integration 17.2325; a moment-matched one-asset lognormal 17.548. Appendices derive basket moments from the moment
generating function. Useful for us only as the recipe for simulating several indices together (R72-type studies).

### Ch. 24 Discrete cash dividends
**Method 11 - Which price is lognormal when dividends are cash (Ch. 24)**
- What it says: with a cash dividend, "spot minus the present value of dividends is lognormal" and "spot is lognormal
  and drops by the dividend" are different models. In his example (S100 K100 vol 20%, dividend 20 at six months, 1y)
  simulation gives 2.58777, a grid with a jump condition 2.5645 and exact integration 2.56562, while the
  present-value-adjusted Black-Scholes gives 1.94853. Adding an effective-vol correction (0.225191) to the adjusted
  formula restores 2.58741; for a 5 dividend the naive formula is off by about 5% (7.52292 against 7.71977). He prefers
  spot as the underlying: it gets both limits right (dividend now: spot minus D; dividend at expiry: strike plus D).
- How we could use it: for index options, sidestep the issue by pricing off the index future or the put-call-parity
  forward (Black-76, Ch. 27) rather than spot minus estimated dividends; for single-stock options, ex-dates matter.
- Signal source: none (pricing input). Maps to: NSE index options yes (use the forward); stock options yes; MCX already
  on futures; crypto no dividends (funding plays the carry role). Mechanical? yes. Our research: not tested by us.

### Ch. 25 Interest-rate models
Bond pricing with time-dependent rates and the one-factor short-rate family (Rendleman-Bartter, Vasicek, CIR, Ho-Lee,
Hull-White, BDT, Black-Karasinski); bond prices of the form exp(A - rB) whenever drift and variance are linear in the
rate, with closed forms derived symbolically for Vasicek and CIR. Model risk is large. Not used by us (the
mean-reverting Vasicek/OU equation is only a template for spreads or vol).

### Ch. 26 Building yield curves
Fitting and interpolation tools, then the simplest curve: money-market rates at the short end, linearly interpolated
swap rates, a bootstrap to discount factors, yields and forwards, flat beyond 10 years. Not used by us beyond the
risk-free input to Black-Scholes.

### Ch. 27 Simple interest-rate options
**Method 12 - Black-76 for options on futures (Ch. 27)**
- What it computes: treat the futures (forward) price as lognormal with zero drift: Black-Scholes with the dividend
  yield set equal to the interest rate, discounting off the curve. Used for swaptions (Hull's example 2.07006 per 100),
  caplets and bond options; consistent within each instrument class, not across them.
- How we could use it: the right pricer for MCX options (they are options on the futures contract) and a clean way to
  price NSE index options off the index future, which also absorbs dividends (Method 11).
- Signal source: none (pricing input). Maps to: MCX options yes; NSE index options yes; crypto options on futures yes.
- Mechanical? yes. Our research: not tested by us.

Also: Vasicek bond options reduce to Black with a model-given vol; CIR rates follow a noncentral chi-square law; only
combinations of real-world parameters and the market price of risk can be calibrated from bond prices.

### Ch. 28 Volatility by elasticity: the CEV model
**Method 13 - Level-dependent volatility as the cause of the put skew (Ch. 28)**
- What it computes: in the constant-elasticity-of-variance model the volatility rises as the price falls (the leverage
  effect); the square-root case is the CIR process. Shaw gives a fast exact square-root CEV call formula (an integral
  of normal CDFs over a fixed interval; 60 points give six decimals: 13.2731 against Black-Scholes 13.2697), checks the
  Cox-Beckers approximation (13.2763) and Cox's series (140 to 6,000 terms), and notes the price can be absorbed at
  zero, which a put must include. Pricing with this model and reading the result back through Black-Scholes produces a
  skew with no change in today's vol: implied 0.20538 at K90, 0.200104 at K100, 0.195409 at K110, from nearly 22% at
  K70 to under 19% at K130. A 2.5% bid-offer markup on top raises the out-of-the-money implied vols sharply and turns
  the put skew into a smile. His lesson: strip out level-dependent and spread effects before fitting stochastic-vol or
  GARCH models.
- How we could use it: (a) an explanation for R102 3.4 (puts five strikes out dearer than calls five strikes out by a
  median 2.56 points on 91% of NIFTY days); (b) a warning that implied vols read from last-traded or offer prices on
  far strikes are inflated by the spread, the same far-wing problem that made R103's six "passes" vanish when repriced;
  (c) a cheap skew model if we ever need to price strikes the lake does not keep (the lake keeps ATM +-10 only).
- Signal source: option chain as context (skew); volatility / options structure. Maps to: NSE index options yes; MCX
  partly (commodities can skew the other way); crypto yes.
- Mechanical? partly (a model, not a rule). Our research: R102 measured the skew; IV1 found option-chain inputs alone
  lost after costs on all five indices (O1, e.g. BANKNIFTY Rs -224 a trade on 646 trades). Not tested as a pricer.

## Performance claims
No trading-performance claims; the book is about pricing accuracy. Its numerical claims are verification results: the
three-time-level Douglas scheme cuts Crank-Nicolson's theta error at the strike from 197% to 2.6%; the CJM American put
beats binomial trees unless several hundred steps are taken; the geometric control variate is worth about 55 times
the paths on a two-asset basket; 60 trapezoid points price the square-root CEV call to six decimals. Each is shown on
one or a few worked examples, not a systematic study. The preface disclaims the code for real pricing in isolation.

## Risk, sizing and psychology
Nothing on position sizing, drawdown or psychology. The risk discussed is model and algorithm risk: verify against
exact cases, distrust single implementations, distrust Greeks from coarse numerics, and treat any implied volatility
with near-zero vega as meaningless. The book also notes that negative-gamma payoffs make simple cost-adjusted hedging
fail. For Kelly sizing and VaR see fm-wilmott.md Methods 4-5.

## How it relates to our research
- Agrees: R102 3.4's "do not read IV at 15:15 on expiry day" is Shaw's vega rule (Method 2) seen in data; R102's
  put-side skew (+2.56 points, 91% of days) is what his CEV chapter generates (Method 13); R102's expiry-hour speed
  (13.5% a minute) is the binary-gamma problem of Ch. 6 (Method 4).
- Sharpens: R103 / DEEP_OPTIONS_SELLING repriced stale wing legs with Black-Scholes at the last IV, floored at
  intrinsic. Shaw adds two checks before trusting such repricing: an accurate N(x) in the tails (Method 5) and the
  spread inflation of OTM implied vols (Method 13). R103's lesson (the passes came from stale far-wing prices) is the
  same lesson in data.
- Backtest method: Method 10 (discrete monitoring bias) and Method 9 (common random numbers) bear on how we simulate
  locks and stops and compare variants; H15's own honesty flags (Kelly fraction unstable, bootstrap on 10 good
  month-blocks optimistic) match Shaw's "a low standard error is no guarantee of accuracy".
- Costs: Shaw's cost remarks are about hedging, not trading; ours are measured (ATM round trip about Rs 98 NIFTY and
  Rs 133 BANKNIFTY including spread, R16 in knowledge/baselines.yaml).
- Already rejected by us, nearest ideas: option-chain signals alone (IV1, 0 forward candidates), GEX (R28/R36, 0 of
  48), cross-index pairs-like rules (R72 C2/C3 failed). The book does not propose trading rules, so nothing here is
  contradicted.

## What is new to us
- A vega floor (and minimum premium) on every implied vol we compute or store, made explicit.
- Benchmark unit tests for our Black-Scholes, Black-76 and Greek code (values above), including the vega-gamma and
  rho-delta identities and a tail-accurate N(x).
- Pricing index options off the futures/forward (Black-76) to avoid dividend modelling; Black-76 for MCX options.
- Discrete-monitoring bias as an explicit check in path-based backtests of stops, trails and locks.
- The exchange/spread-option pricer (Margrabe) as a yardstick for NIFTY-BANKNIFTY or GOLD-SILVER relative value.
- Robust (least-absolute-deviation) fits for smile and hedge-ratio estimation on noisy prints.
- The CEV skew plus spread effect as a model of why far-OTM put IVs look high.

## Could not read
Mathematica code and displayed formulas are partly garbled throughout (OCR of a scanned book); results were taken from
the readable printed numbers and the prose. All 2D/3D plots (Greek surfaces, convergence plots, error plots, the CEV
skew and smile figures) are unreadable as images; their content was taken from the surrounding text. The compound
call-on-call table in Ch. 12 is scrambled. Two scan pages are swapped (book page 175 appears at scan page 527 and book
page 517 at scan page 185) and book pages 345-347 appear before the Ch. 18 title page; no page is missing. The CD-ROM was
not opened (code).
