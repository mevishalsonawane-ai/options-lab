# An Introduction to Computational Finance Without Agonizing Pain - Peter A. Forsyth (2007)

Source: introduction-to-computational-finance-without-agonizing-pain.txt, 77 pages, read in full on 25 Oct 2026 (pdftotext
text layer, sections 1-13; the title page names P.A. Forsyth, School of Computer Science, University of Waterloo, dated
12 February 2007; formulas came through partly garbled and every figure is an image, see "Could not read").
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
University course notes (Waterloo) that build option pricing from a two-state tree up to Black-Scholes, by hedging.
They teach the numerical tools: Monte Carlo (errors, random numbers, variance reduction, Sobol, correlated draws), and lattices.
The practical core is section 8: a Monte Carlo study of how big the hedging error is when you rebalance monthly, weekly or daily.
Then models beyond Black-Scholes: stochastic and uncertain volatility, jump diffusion, regime switching, non-traded assets.
It closes with mean-variance portfolios, CAPM beta, and a warning that long-run equity returns are riskier than ads suggest.

## Methods, rules and setups

The notes teach no trading rules (no entry, stop or exit anywhere). They teach how an option seller hedges, how big the
leftover risk is, and how to compute prices and Greeks. Every section is summarised in order; methods a trader of NSE
index options, MCX or crypto could use get a full block. NSE index options, MCX options (on the future) and Delta Exchange
crypto options are European, so the American-option material (sections 2.10, 5) matters little to us.

### Section 1 - The first option trade
Thales of Miletus put deposits on olive presses before a bumper harvest and sublet them at a profit: an option on a
non-storable crop, where the deposit was the most he could lose. Anecdote only.

### Section 2 - The Black-Scholes equation (pp. 3-16)
- Two-state tree (Hull's example): a stock at $20 goes to $22 or $18; a $21 call is hedged by holding 0.25 shares per
  call sold, the portfolio is riskless, so its value is fixed by the risk-free rate and the call is worth 0.633. The
  price does not depend on the probability of the up move.
- Brownian motion as the limit of a lattice random walk: the step must shrink with the square root of the time step,
  variance grows linearly in time, and the paths are jagged at every scale. Geometric Brownian motion (GBM) for prices.
- Ito's lemma (informal derivation) and its uses: the exact solution of GBM, its mean, variance and skewness.
- Black-Scholes by delta hedging: hold the option's delta (dV/dS) of the underlying; the random term cancels; the
  equation does not contain the stock's drift.
- Continuous hedging recipe: sell the option, buy delta shares, borrow the rest, rebalance continuously; it ends at
  zero gain or loss whatever the path.
- Key statement (2.9): the Black-Scholes price is the **cost of the hedge**, not the expected payoff. A speculator who
  buys and holds is betting on the drift, and that value needs a drift guess ("art, not science").
- American options: the price must stay at or above the payoff, which turns the PDE into a complementarity problem.

#### Method 1. Delta and the hedge-cost view of option prices (2.3-2.9)
- What it computes: the option price as the cost of a self-financing delta hedge; delta = N(d1) for a call under
  Black-Scholes.
- Inputs: spot, strike, time, rate, volatility (no drift).
- How we could use it: the right mental model for both arms. A buyer of an unhedged option is paying the hedger's cost
  and betting on direction plus realised volatility; a seller unhedged is short that same bet.
- Signal source: volatility / options structure (pricing, not a signal); maps to NSE index options yes (European), MCX
  options yes (Black-76 on the future), crypto options yes.
- Mechanical? yes (formula).
- Our research: the app already prices with Black-Scholes (R103 repricer, h29 synthetic forward). R102 found India VIX
  above NIFTY's forward 21-day realised volatility on 81% of days (median ratio 1.28, n 4,507 days;
  research/DEEP_NIFTY_BANKNIFTY.md): on most days the hedge cost priced in more movement than came.

### Section 3 - The risk-neutral world (pp. 17-18)
The expected value of an option to a holder needs the real drift and a risk-adjusted discount rate; setting both to the
risk-free rate gives back Black-Scholes. The author calls this a mathematical fluke with no reality ("no such thing as a
risk-neutral world") that is useful only for computing prices by simulation. Theory; one practical point: risk-neutral
simulated paths are not forecasts of real paths.

### Section 4 - Monte Carlo methods (pp. 19-36)
- Simulate risk-neutral paths, average the discounted payoff. Two errors: time-step error (forward Euler, first order)
  and sampling error (one over the square root of the number of paths). Balance them: paths should grow like one over
  the square of the time step, so total error falls only like cost to the power -1/3.
- People often pick a one-day step and only add paths; the author warns this "sometimes gives bad results".
- Use the exact GBM step when the model allows it; step log S, not S, so prices cannot go negative.
- 95% confidence interval = mean +- 1.96 standard errors; ten times the accuracy needs a hundred times the paths.
- Random numbers: system generators like C's rand and IBM's RANDU are bad; Mersenne Twister is good. Normals by the
  inverse cumulative normal, by Box-Muller, or by its polar form (about 21% of draws rejected, still faster).
- Antithetic variates: pair each path with its mirror. It helps for monotone payoffs but can be **worse** than plain
  Monte Carlo for non-monotone ones (a capped call). For a distribution, a histogram or VaR, do not average the pairs.
- A one-pass running mean and variance (Seydel's recursion) avoids round-off.
- Low-discrepancy (quasi-random) sequences: Sobol worked best in the author's tests; fine up to about 100-200 time steps,
  slower beyond a few hundred dimensions; they need the inverse normal, not Box-Muller.
- Correlated normals: Cholesky-factor the correlation matrix and multiply.
- Brownian bridge: refine a path by filling in points between existing ones; used to measure convergence.
- Strong vs weak convergence: Table 4.2 (T 0.25, sigma 0.4, drift 0.06, S0 100, 100,000 paths): with 72, 144, 288, 576
  steps the strong (path-by-path) error is .0269, .0190, .0135, .0095 and the weak (mean) error .00194, .00174, .00093,
  .00047, i.e. Euler is order 0.5 strong and order 1 weak.
- Matlab tip: loop over time and vectorise over paths; orders of magnitude faster.

#### Method 2. Monte Carlo of an option or a strategy's P&L, with error bars (4.1-4.5)
- What it computes: a price, or a whole P&L distribution (histogram, variance, VaR), from simulated paths.
- Inputs: a path model (GBM, jumps, regimes), number of paths, time step; costs if a strategy is simulated.
- How we could use it: (a) every simulated number we report should carry the 1.96-standard-error band; (b) check the
  time step by rerunning with half the step, not just more paths; (c) for VaR or drawdown histograms keep antithetic
  pairs separate. Our studies mostly bootstrap real days rather than simulate model paths (H15's Kelly study bootstraps
  regime months; research/HUNT_H15.md), which avoids the model-error problem the author flags.
- Signal source: none (a measuring tool); maps to NSE / MCX / crypto: yes, any instrument.
- Mechanical? yes.
- Our research: bootstrap p-values and Benjamini-Hochberg across ideas are our standard (CLAUDE.md); nothing in the
  notes contradicts that.

#### Method 3. Correlated paths by Cholesky (4.7)
- What it computes: joint random moves of several assets with a given correlation matrix.
- How we could use it: simulate NIFTY and BANKNIFTY (or the five indices of R72) together for a joint drawdown or margin
  study. Needs the correlation matrix to be positive definite (the author notes measured matrices can fail this).
- Signal source: none; maps to NSE yes, MCX yes (GOLD/SILVER), crypto yes (BTC/ETH).
- Our research: not tested by us as a simulation; R72 measures cross-index co-movement on real bars
  (research/HUNT_R72_CROSS_INDEX.md: Breadth Donchian trades are 77% Donchian's own, daily correlation 0.80).

### Section 5 - The binomial model (pp. 37-41)
Build a lattice of prices in log space, set the payoff at expiry, roll back with discounted risk-neutral weights;
American options take the larger of hold and exercise at each node. The author's view: easy to code and to explain to
managers, but it is just an explicit finite-difference scheme (first-order accurate), barrier options become
"nightmarish", and the up/down weights are not real probabilities. The no-arbitrage tree weights differ from the
random-walk ones only at higher order. A two-price-tomorrow world is not believable; it is a numerical device.
- Practical use: none new for us (European options: the closed form is exact). See research/books/fm-binomial-models.md.

### Section 6 - More on Ito's lemma (pp. 41-44)
Pure theory: a mean-square argument that squared Brownian increments add up to elapsed time. One line.

### Section 7 - Derivatives on non-traded assets, real options, forwards (pp. 44-49)
- If the underlying cannot be traded (an interest rate, bandwidth), hedge one contract with another; a "market price
  of risk" then enters the pricing equation and must be estimated (the author suggests CAPM beta of firms whose business
  rests on that variable, unlevered for debt; Canadian equity premium about 6% over 50 years).
- Forward contracts: value is (forward price minus delivery price) discounted; the forward price is the risk-neutral
  expected spot at expiry and has zero drift under that measure.
- Convenience yield: the benefit of holding the physical asset (natural gas in storage is his example); it enters like
  a dividend.

#### Method 4. Forward price, convenience yield and forward volatility (7.2)
- What it computes: the fair forward/futures price from spot, rate and convenience yield; the volatility of the forward.
- How we could use it: on MCX, read the implied convenience yield (or carry) from the near and next futures; the curve
  shape (contango/backwardation) is the input to the NATURALGAS contango idea in research/MCX_GUIDE.md (rolled long NG
  lost -1.56% a month 2012-2026). For index futures the same identity gives the dividend yield from the basis.
- Signal source: underlying price (futures curve); maps to MCX yes (GOLD, SILVER, CRUDE, NATURALGAS), NSE index futures
  yes (basis), crypto perpetuals/futures yes (funding/basis).
- Mechanical? yes.
- Our research: H39 found heavyweight futures basis changes tied to the index in the same minute but no lead at 2-15
  minutes (research/HUNT_H39.md). Convenience yield as a signal: not tested by us.

### Section 8 - Discrete hedging (pp. 49-54) - the practical core
The author's motivation: real hedges cannot be continuous, and because of transaction costs one wants to hedge "as
infrequently as possible". Costs turn Black-Scholes into a nonlinear equation; **the notes mention this but do not
simulate costs** - every hedging result below is before costs.

#### Method 5. Discrete delta hedging and its error distribution (8.1)
- Setup: short one option, long delta units of the underlying, the rest in a bank account earning or paying the
  risk-free rate. At each rebalance date buy or sell the change in delta, financed through the bank. At expiry record
  the discounted P&L divided by the option's initial price ("relative hedging error"); repeat over many Monte Carlo
  paths; plot the histogram, its variance and the 95% VaR.
- Worked case: short an American put, T 0.25 years, sigma 0.3, r 0.06, real drift 0.08, strike = spot = 100; fair
  value $5.34. Because hedging is discrete, the result now depends on the real drift.
- Findings (read from figures 8.1-8.2; the notes give no table of numbers):
  - **No hedge:** the writer usually keeps the whole premium (a spike at +1 premium, because the stock drifts up and
    most puts expire worthless), but with a long loss tail reaching several premiums (the plotted axis runs to -3).
  - **Monthly** (only three rebalances in the life): mean near zero but a wide spread.
  - **Weekly**, then **daily**: centred on zero, spread shrinking visibly at each step.
  - Rule stated: the standard deviation of the hedging error is proportional to the square root of the rebalancing
    interval. So halving the error needs four times as many rebalances (and four times the trading cost).
  - Warning: funds that write puts and do not hedge do well for years in rising markets, then "blow up" in a sudden
    drop.
- Transaction costs: stated as the reason to hedge less often; not quantified. The trade-off therefore is implicit:
  error falls like the square root of the interval while cost rises like the number of rebalances.
- Signal source: volatility / options structure. Maps to NSE index options: only for a seller big enough to hedge with
  futures; for one lot it does not exist (a 0.2-delta hedge of one NIFTY lot is 13 units, below the future's lot;
  research/DEEP_OPTIONS_SELLING.md). MCX: same lot problem; the minis (GOLDM, SILVERM) help. Crypto: yes, perpetuals
  allow small fractional hedges (must still carry a stop and a profit lock per CLAUDE.md).
- Mechanical? yes.
- Our research: MCX_OPTIONS.md uses a delta-hedged-every-15-minutes P&L ("DH") as its honest cheap/dear measure: on
  expiry day a long MCX option lost -2% to -4.5% of premium per hour delta-hedged; at morning, 11-20 days to expiry it
  was about zero. DEEP_OPTIONS_SELLING.md explains that the hedger earns implied minus realised variance, positive on
  81% of NIFTY months and negative on crash days. A NIFTY/BANKNIFTY discrete-hedging simulation: not done by us.
  Bennett (research/books/bennett-trading-volatility.md, method 8) gives the matching formula (four times as often
  halves the noise); not repeated here.

#### Method 6. Delta-gamma hedging (8.2)
- Setup: add a second option so the book is both delta- and gamma-neutral; the underlying alone has no gamma.
- Worked case: hedge the same put with a European put of the same strike and 0.5-year expiry, rebalanced weekly or
  daily. Figure 8.3 shows a far narrower error distribution than delta hedging (its vertical scale runs to 40 against
  4 for the delta-hedge plots). The author's caveat: more model error, because the hedge now relies on the model's
  second derivative.
- How we could use it: this is what a calendar or a ratio structure does. For a short ATM weekly straddle seller,
  buying a further-dated option offsets gamma near expiry, where DEEP_OPTIONS_SELLING.md measured ATM delta moving 0.41
  per 100 NIFTY points at 13:30 on expiry day.
- Signal source: volatility / options structure; maps to NSE yes (weekly vs monthly expiries), MCX partly (thin far
  months), crypto yes (Deribit/Delta weeklies).
- Mechanical? yes.
- Our research: not tested by us (R103 tested wings and stops, not gamma hedges with other expiries).

#### Method 7. Vega hedging and uncertain volatility (8.3)
- Stochastic volatility (Heston): two risks, so hedge with the underlying and another option.
- Uncertain volatility (a band of possible vols, priced at the worst case): the hedger can never lose, but the price is
  so high nobody may buy.
- Delta-vega hedging (the industry route): price at today's implied vol, then at each rebalance recompute delta and vega
  at the new implied vol and hold another option to cancel vega. The author cites Hull and White that a constant-vol
  vega hedge works "surprisingly well" even when volatility is stochastic.
- Signal source: volatility / options structure; maps to NSE yes (straddle seller hedging India VIX moves with a
  further expiry), MCX partly, crypto yes.
- Mechanical? yes.
- Our research: not tested by us.

### Section 9 - Jump diffusion (pp. 54-62)
- S&P 500 monthly returns 1982-2002 have a taller peak and fatter tails than the normal; GBM makes large moves vanish
  as the step shrinks, real prices still jump (a drug stock's chart; figure not in the text).
- Merton-style model: GBM plus Poisson jumps of random size (log-normal is common). A delta hedge removes the diffusion
  risk but **not** the jump risk. Assuming jump risk diversifies away gives a pricing equation with an integral term.
- Fitted to option prices (Andersen-Andreasen), the jump parameters show larger and more frequent jumps than history:
  option prices carry a premium for jump risk.
- A pure hedging derivation: with one possible jump size, the underlying plus two options hedge both risks; with n jump
  sizes you need n+2 instruments. The jump intensity in prices is a risk-adjusted number, not the real frequency.

#### Method 8. Jump risk as the part a delta hedge cannot remove (9.2-9.3)
- What it computes: option values with gap risk; the share of a seller's risk that delta hedging leaves.
- How we could use it: it is the theory behind R103's tail: a short straddle can be hedged against drift, not against
  an overnight or event gap. It argues for the wing (a far option bought) over a futures hedge for any seller.
- Signal source: volatility / options structure; maps to NSE yes (event days, gaps), MCX yes (inventory reports, OPEC),
  crypto yes (weekend gaps).
- Mechanical? yes (pricing), no rule.
- Our research: R102 measured NIFTY daily returns with kurtosis 14.1 over 5,087 days, near-symmetric (skew -0.04)
  (research/DEEP_NIFTY_BANKNIFTY.md). R103's naked 09:20 BANKNIFTY straddle had a worst day of -Rs 29,257 on one lot
  (research/HUNT_R103_OPTIONS_SELLING.md) - the jump the hedge cannot catch.

### Section 10 - Regime switching (pp. 62-64)
Volatility jumps between a few levels; the same jump event may move the price too. Pricing reduces to a few coupled
one-dimensional equations. The volatility levels and jump sizes are real-world parameters, the switching intensities
risk-adjusted; in practice all are calibrated to option prices.

#### Method 9. Regime-switching volatility (10)
- What it computes: option values when volatility sits in one of a few states (calm / stressed).
- How we could use it: our arms already use a VIX regime as a filter (R101/R52); a two-state volatility model could
  price options consistently across regimes and say how much the chain is pricing a switch. Calibration needs a good
  surface; not a trading rule.
- Signal source: volatility / options structure; maps to NSE yes (India VIX regimes), MCX partly, crypto yes.
- Our research: regime filters were used in arms (FINDINGS.md R101 lists VIX regime filters on R51/R52); a regime
  pricing model: not tested by us.

### Section 11 - Mean-variance portfolio optimisation (pp. 64-74)
- Portfolio variance from weights and the covariance matrix. Uncorrelated assets: risk falls like 1/N; perfectly
  correlated: the portfolio's sd is the weighted average; 10-20 stocks reap most of the benefit.
- Efficient frontier as a quadratic programme with weight limits (long-only, or a cap such as 0.20 a name); long-short
  frontiers dominate long-only. Adding a risk-free asset gives the capital market line and the Sharpe ratio.
- Criticism (the useful part): expected returns are very hard to estimate from short series (noise dominates); studies
  find equal weights (1/N) do about as well once estimation error is counted; standard deviation punishes upside too, so
  downside risk may be better; resampled frontiers reduce sensitivity to data errors.
- CAPM beta as the regression slope of a stock on the index; in a single-index model a diversified portfolio's risk is
  the market's sd times the weighted beta.

#### Method 10. Allocation across arms: 1/N vs mean-variance (11.2-11.4)
- What it computes: weights across several strategies or instruments for least variance at a given return.
- How we could use it: allocate capital across the paper arms with a real record (Liquidity 15+5, Breadth Donchian, R51
  Donchian, R52 OOV) by their daily P&L covariance - but the author's own warning applies: means estimated from a few
  months are noise, so equal risk per arm is the honest default. Overlapping arms are one bet, not two (R72: Breadth
  Donchian and Donchian daily correlation 0.80, so "replace, not run beside").
- Signal source: none (sizing); maps to NSE / MCX / crypto: yes.
- Mechanical? yes.
- Our research: H15 found the Kelly fraction "unstable" because it was estimated on the same 10 regime months the
  bootstrap resamples (research/HUNT_H15.md) - the same estimation-error point; portfolio-level mean-variance across
  arms: not tested by us.

### Section 12 - Stocks for the long run? (pp. 74-75)
- Annualised returns narrow with horizon but total returns widen; a mutual-fund table of annualised returns misleads.
- Cited study (Dimson, Marsh, Staunton 2004): a diversified holder has about a 14% chance of a negative real return over
  20 years; a typical defined-contribution pension about 25%.
- Simulation (100,000 paths, drift 0.08, sigma 0.2, 5 and 30 years): many 30-year paths end below 3% bonds.
- For us: a reminder that buy-and-hold is a baseline with real risk (CLAUDE.md: buy-and-hold only as a baseline in
  crypto). Not a method.

### Section 13 - Further reading
Book list (Hull, Wilmott, Glasserman, Jackel, Seydel, Taleb's Dynamic Hedging and others). Nothing to summarise.

## Performance claims
- No trading performance is claimed. The only numbers are worked examples and simulations:
  - two-state tree call worth 0.633; American put worth $5.34 (T 0.25, sigma 0.3, r 0.06);
  - Euler error table (100,000 paths): strong error .0269 at 72 steps to .0095 at 576; weak .00194 to .00047;
  - discrete-hedge histograms (no numbers): spread shrinks from monthly to weekly to daily, sd proportional to the
    square root of the interval; gamma hedging much tighter;
  - jump parameters fitted to options imply larger, more frequent jumps than history (cited, no numbers);
  - 1/N roughly matches optimised portfolios under estimation error (cited, no numbers);
  - 14% / 25% chance of a negative 20-year real equity return (cited study).
- Evidence for the hedging claims is simulation under the model itself (GBM), so they show hedging error from discrete
  time only, not from jumps, wrong volatility or costs.

## Risk, sizing and psychology
- Unhedged option writing looks like steady income and ends in a blow-up; the author is scathing about put-writing
  funds that pay managers bonuses and leave shareholders the losses.
- Discrete hedging leaves a risk that shrinks only with the square root of the rebalance interval; costs push the other
  way; jumps are not hedged at all by the underlying.
- Model risk grows with the hedge's sophistication (gamma hedging relies on second derivatives).
- Sizing: no Kelly; mean-variance with the warning that expected returns cannot be estimated well; equal weights are a
  sound default. Use downside risk rather than variance if you are long.
- No psychology.

## How it relates to our research
- Agree, measured: the hedge-cost view says a seller who hedges earns implied minus realised. R102: India VIX above
  NIFTY's forward 21-day realised on 81% of days (median 1.28, n 4,507). DEEP_OPTIONS_SELLING.md draws the same
  conclusion and explains why one lot cannot delta hedge with futures (13 units do not exist).
- Agree: unhedged writing has a fat left tail. R103: 0 of 24 defined-risk selling tests confirmed (the six design
  passes were stale-priced wings); the naked 09:20 straddle was positive before the tail but its worst BANKNIFTY day was
  -Rs 29,257 on one lot, beyond the -Rs 13k month stop (research/HUNT_R103_OPTIONS_SELLING.md).
- Agree: delta-hedged P&L is the honest way to say an option was cheap or dear; MCX_OPTIONS.md does exactly this (DH,
  every 15 minutes) and finds no bucket a buyer can exploit after costs.
- Consistent with R16 / R88 / R41 (size predictable, direction not): the notes' whole framework prices volatility and
  never needs direction; the speculator's drift bet is "art, not science".
- Monte Carlo practice: our studies bootstrap real days (H15 regime months) and apply BH across ideas; the notes add
  checking time-step error and keeping antithetic pairs apart for VaR - relevant only if we ever simulate model paths.
- Fat tails: R102 kurtosis 14.1 over 5,087 NIFTY days matches section 9's S&P histogram.
- Not related: GEX (R28/R36/R37), IV1 option-chain direction signals, neural nets (R41), cross-index pairs (R72, except
  as a correlation input), order flow.
- Bennett (research/books/bennett-trading-volatility.md) covers delta hedging, gamma scalping and the volatility premium
  in trading terms; this book adds the Monte Carlo method to measure hedge error and the gamma/vega hedge extensions.

## What is new to us
- A **discrete-hedging simulator** for our own instruments: short (or long) ATM NIFTY/BANKNIFTY weekly straddle,
  hedged with the future every 5, 15, 60 minutes or at fixed delta bands, run on **real 1-minute index paths** (not GBM)
  with our real costs (~Rs 70 a NIFTY round trip, HUNT_H24 / baselines.yaml), to see where cost and residual error
  balance and how much the jump days dominate. Not tested by us; for one lot it is academic (13-unit hedge), so it
  would size the minimum book at which a hedged selling arm makes sense.
- **Gamma hedge across expiries** (weekly short, monthly long) as the seller's alternative to wings. Not tested.
- **Delta-vega hedge** that re-marks at the current India VIX/ATM IV. Not tested.
- **Jump-diffusion or regime-switching fit** to the NIFTY chain to read how big a jump the market is pricing on event
  days. Not tested.
- **Monte Carlo hygiene** for any future simulation: error bars at 1.96 standard errors, time-step convergence, Sobol
  for low dimensions, antithetic only for monotone payoffs.
- **1/N or equal-risk allocation across arms** with a covariance check, as an explicit portfolio rule. Not tested.

## Could not read
- Every figure is an image with no data in the text: price paths (2.3), Brownian-bridge paths (4.1-4.2), the hedging
  error histograms (8.1, 8.2, 8.3; read only from axis labels and captions), the S&P 500 return histogram (9.1),
  jump-diffusion paths (9.2), the drug-company price chart (9.3, image missing entirely), efficient frontiers
  (11.1-11.3), the Rogers vs TSE 300 scatter (11.4) and the return histograms (12.1-12.2).
- Many displayed formulas came through garbled (fractions, matrices, integrals); the prose around them was readable.
- Transaction costs in hedging are mentioned in one sentence and never quantified; nothing to read there.
