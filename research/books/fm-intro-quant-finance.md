# Introduction to Quantitative Finance: A Math Tool Kit - Robert R. Reitano (2010)

Source: introduction-to-quantitative-finance.txt, 748 pages (MIT Press; about 690 numbered pages plus front matter),
read in full on 25 Oct 2026 (text layer: introduction, all 10 chapters with their "Applications to Finance" sections and
exercises, references and index; formulas partly garbled and figures not visible - see "Could not read").
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A university mathematics tool kit written by a former insurance-company risk officer for people moving into finance.
Each of its 10 chapters teaches one block of maths (logic, numbers, spaces, topology, sequences, series, probability,
limit theorems, differentiation, integration) and ends with a section applying it to finance.
The finance is classic: bond yields, term structures, duration and convexity, asset-liability matching, loan and
insurance loss models, asset allocation, a binomial stock lattice, its limit to Black-Scholes, the Greeks and utility.
Its constant theme is that a formula is only as good as its assumptions, so the quant must check them each time.

There is no market data, no backtest and no trading rule with an entry signal; every number is a classroom example.
Its value for us is method discipline: how big a Monte Carlo must be, how noisy a volatility estimate is, what a
distribution-free tail bound says, how to find an implied value by bisection, how to compute Greeks numerically, and a
utility-based sizing formula. Bennett's book (research/books/bennett-trading-volatility.md) covers the trading desk side
of volatility; this one is the maths underneath, at a gentler level than the other fm- books.

Our data, for the mapping: NIFTY weekly and BANKNIFTY monthly index options (European, cash-settled), 1-minute rolling
chain of the nearest expiry within ATM +-10 strikes with IV and OI (last prices, no bid/ask), index spot and futures
bars, India VIX daily, MCX futures and options, Delta Exchange crypto options. Costs: about Rs 70 (NIFTY) / Rs 76
(BANKNIFTY) per option round trip; a four-leg structure is eight orders, about Rs 300 (DEEP_OPTIONS_SELLING.md,
summary point 9).

## Methods, rules and setups (chapter by chapter)

### Introduction
States the aim: rigorous maths for finance, never a formula detached from its assumptions. The quant's real job is
judging whether the standard approach applies to the problem in front of them. Starred sections are advanced; a sequel
volume is announced.

### Ch. 1 - Mathematical logic
Proof versus counterexample, axioms, direct and indirect proof, and classic paradoxes (dividing by zero, regrouping an
oscillating series, Zeno, Russell). The finance section makes one useful point: the best theorems are "just barely
true", so a result fails as soon as one hypothesis fails. Its example is the student who finds an "arbitrage" in quoted
prices that could never be traded at those quotes.
- Our research: this is exactly R103's failure. Six BANKNIFTY non-expiry selling tests passed the design gates
  (+Rs 1.9 to +Rs 5.4 lakh) only because far wings were priced at stale prices; repriced, all six lose
  (DEEP_OPTIONS_SELLING.md, summary point 9).

### Ch. 2 - Number systems and functions
Natural, rational, real and complex numbers, countability, functions and inverses. Finance: present and accumulated
value, annuities, converting nominal rates between compounding frequencies, bond prices (above par exactly when the
coupon beats the yield), loan payments, a perpetual preferred share, the constant-growth dividend model, portfolio
return as a weighted sum (shorts as negative weights) and the forward price by replication (buy the stock, borrow).
Side note worth keeping: a position that loses more than 100% of its capital (leverage, short options) has no real
annualised rate of return, so such results should be quoted over the period, not annualised.

### Ch. 3 - Euclidean and other spaces
Vectors, norms (l1, l2, l-infinity and general lp), inner products, the Cauchy-Schwarz, Holder and Minkowski
inequalities, metrics. Finance: portfolios and trades as vectors (with a finite real grid of lot sizes and ticks),
yield, spot and forward term structures that must agree or allow arbitrage, bootstrapping spot rates, and sample
moments written as norms. Two practical items follow.

### 1. Trading cost as an l1 norm, limits as an l-infinity norm (Ch. 3.3)
- What it computes: the cheapest set of trades that moves a portfolio to a target exposure. With a cost per unit traded,
  value after a rebalance is the old value minus the cost rate times the sum of absolute trade sizes (an l1 norm). The
  problem: minimise total traded size subject to the target (for example a portfolio beta or duration) and to a
  self-financing condition; position or liquidity caps are an l-infinity limit on the new holdings. The continuous
  answer is then rounded to lot sizes and checked to still be near-optimal.
- Inputs: current holdings, the target exposure per unit of each instrument, cost per unit, position caps, lot sizes.
- How we could use it: when Autopilot or an arm adjusts a multi-leg option position (re-centring a strangle, cutting
  delta), choose the leg changes that reach the target delta with the fewest lots, since every lot is charged; costs
  already decide most of our results (Rs 70 / Rs 76 a round trip, about Rs 300 for eight orders).
- Signal source: none (an execution / rebalancing tool). Maps to: NSE options and futures yes, MCX yes, crypto yes.
- Our research: not tested by us.

### 2. Best single summary of data depends on the norm (Ch. 3.3, 9.5)
- What it computes: the point closest to a data set. Squared distance (l2) gives the mean, absolute distance (l1) the
  median, worst-case distance (l-infinity) the midpoint of the range.
- How we could use it: a reminder that with fat-tailed P&L or move sizes the median is the robust "typical" value; our
  studies already report medians beside means (for example the median VIX-to-realised ratio 1.28, R102).
- Signal source: none (statistics). Maps to: all.

### Ch. 4 - Set theory and topology
Axiomatic sets, open and closed sets, the Cantor set, compactness and the Heine-Borel theorem. Finance: an optimisation
has a solution only if the objective is continuous on a closed and bounded set; existence (qualitative) is different from
having an algorithm with a known speed (quantitative). The bid-ask spread is described as both a liquidity measure and a
measure of how far a trade price may sit from fair value.

### 3. Implied value by interval bisection (Ch. 4.3, 5.5, 9.8)
- What it computes: the input (yield, or implied volatility) that makes a model price equal a market price. Start with
  an interval whose two ends price above and below the market, halve it, keep the half that still brackets the price.
  Each step halves the error. The book's worked bond (1000 par, 10 years, 8% semi-annual coupon, price 1050, bracket
  7%-8%) reaches 7.28809% after 10 steps, with error at most 0.01 divided by 2 to the number of steps.
- Conditions: the price function must be monotone and continuous in the input (intermediate value theorem); otherwise
  there can be several roots or the iterates converge without the price converging. Option prices are monotone and
  continuous in volatility, so bisection always finds Black-Scholes implied vol when the quote is inside the no-arbitrage
  bounds; a quote outside the bounds (a stale or bad print) has no implied vol at all.
- Inputs: market price, the pricing model, a sensible starting bracket (a good bracket saves steps).
- How we could use it: the robust fallback for the app's IV solver when Newton steps fail (deep wings, tiny vega near
  expiry); a failure to bracket is itself a data-quality flag for the lake, the R103 stale-wing problem again.
- Signal source: options structure (a pricing tool, no signal). Maps to: NSE index options, MCX options on futures,
  crypto options.
- Our research: not tested by us (our IV comes from the data vendor and our own Black-Scholes repricing in
  DEEP_OPTIONS_SELLING.md).

### Ch. 5 - Sequences and their convergence
Limits, monotone and bounded sequences, Bolzano-Weierstrass, limit superior and inferior, Cauchy sequences, complete
spaces. Finance: the bisection example above with its error bound, and the warning that a discontinuous price function
breaks it. Pure theory otherwise.

### Ch. 6 - Series and their convergence
Convergence tests, absolute versus conditional convergence (a conditionally convergent series can be reordered to any
sum), lp, Banach and Hilbert spaces, power series. Finance: perpetuities with changing yields, the growing-dividend model,
increasing perpetuities, and approximating a price function by its Taylor series around today's value - the root of
duration, convexity and the Greeks.

### Ch. 7 - Discrete probability theory
Randomness as "not perfectly predictable" (the author notes an index's up/down day is less random after Asia and Europe
have traded); sample spaces, conditional probability, independence, random variables, expectation, variance,
covariance and correlation (uncorrelated is not independent), the law of total variance, moment and characteristic
functions, and the common discrete distributions (binomial, geometric, negative binomial, multinomial, Poisson as the
limit of rare independent events). Finance: loan default and insurance loss models, asset allocation, equity return
models and option pricing on a binomial lattice. Practical blocks follow.

### 4. How noisy is a sample mean, variance or realised volatility (Ch. 7.5, 8.3)
- What it computes: the sampling error of estimates. A sample mean has sd equal to the true sd over the square root of
  n. The usual variance estimate divides by n-1 (unbiased); dividing by n is biased low. The variance of the sample
  variance depends on the fourth central moment, so a fat-tailed series has a much noisier variance (and realised
  volatility) estimate than a normal series of the same length.
- Inputs: the returns, their kurtosis.
- How we could use it: put an error band on every realised-vol number we compare with India VIX or option IV. NIFTY
  daily returns have kurtosis 14.1 and BANKNIFTY 8.5 against 3 for a normal (DEEP_NIFTY_BANKNIFTY.md, daily return
  table, n 5,087 / 5,086 days), so a 20- or 21-day realised vol is far noisier than the normal formula suggests. This
  matters for R102's "VIX over-states NIFTY's next month on 81% of days" (median ratio 1.28, n 4,507 days;
  DEEP_OPTIONS_SELLING.md point 2): the day-by-day comparison is noisy, the 81% over thousands of days is not.
- Signal source: underlying (realised moves of the index) against volatility (VIX/IV). Maps to: NSE, MCX, crypto.
- Our research: R102 measured the gap; error bands from the fourth moment were not computed - not tested by us.

### 5. Monte Carlo by inverse CDF and historical simulation (Ch. 7.7, 7.8, 10.11)
- What it computes: random samples from any distribution by mapping uniform draws through the inverse cumulative
  distribution (on a fine grid for continuous laws); and, for portfolios, "historical simulation" that applies today's
  weights to whole historical return vectors from the same dates, which keeps the correlations between assets intact.
  For loan losses the book resamples historical loss-given-default values from an urn with replacement (a bootstrap).
- The author's cautions: the chosen distribution is only an assumption; the tails are what matter and are where data
  are thinnest; parameters fitted in a falling market need changing in a rising one.
- How we could use it: our studies already bootstrap daily or monthly P&L (HUNT_H15.md resamples 10 month-blocks for
  Kelly drawdowns; OBUY_FINAL.md uses the SPA bootstrap for fat-tailed daily P&L). The book's rule, resample same-date
  vectors across arms, is the right way to simulate a combined account of several arms.
- Signal source: none (risk tool). Maps to: all.
- Our research: bootstrap used widely; joint same-date resampling across arms not done as a study - not tested by us.

### 6. The "epsilon switch" in allocation (Ch. 7.8.4)
- What it computes: moving a small weight from one asset to another changes the mean linearly and the variance by a
  quadratic in the switch size, so whenever the covariance term is non-zero there is a range of small switches that cuts
  variance; the best switch size follows from two numbers. A switch that raises the mean and cuts the variance is
  preferred (ignoring higher moments).
- Also: there is one risk-free rate per investor horizon; for a day trader a T-bill is not riskless at the day horizon.
- How we could use it: a cheap check for whether shifting capital between two paper arms lowers combined risk.
- Signal source: none (allocation). Maps to: all. Our research: not tested by us.

### 7. Binomial lattice, replication and risk-neutral pricing (Ch. 7.8.5-7.8.7)
- What it computes: equity log returns are described as about 50:50 up/down, with autocorrelation near zero and fat
  tails, and modelled by a lattice with up move mean plus sd and down move mean minus sd at probability one half. A
  one-period option is copied by holding a number of shares equal to the change in option value over the change in
  stock value (the delta) plus cash; that fixes the price and a "risk-neutral" probability. Multi-period prices sum
  over the lattice; path-dependent payoffs need a scenario (path) model. Monte Carlo over risk-neutral paths gives an
  unbiased price.
- Limits stated by the author: costs and taxes are ignored; options need a shortable underlying; different time steps
  and calibrations can give materially different prices.
- Signal source: underlying (spot, its volatility) for pricing; no trade signal. Maps to: NSE index options, MCX options
  on futures, crypto options.
- Our research: the 50:50, near-zero-autocorrelation description matches R16 (only the size of the next 30-60 min move
  was predictable, not its direction; FINDINGS.md, R16 section, 24 holdout tests). Put-call parity (Ch. 7 exercise)
  as a chain check: not tested by us.

### Ch. 8 - Fundamental probability theorems
Uniqueness of moment functions, Chebyshev-type inequalities, the weak and strong laws of large numbers, Kolmogorov's
inequality, Stirling's formula, De Moivre-Laplace, the normal distribution and the central limit theorem. Finance:
capital for tail losses, the lattice limit to the lognormal model and the Black-Scholes formula.

### 8. Distribution-free tail bounds: Chebyshev and the one-sided version (Ch. 8.2, 8.8.1)
- What it computes: for ANY distribution with a variance, the chance of being at least t sds from the mean is at most
  one over t squared (2 sd: 25%, 3 sd: about 11%, 4 sd: about 6%). The one-sided bound for losses beyond the mean is one
  over (1 + t squared). In the exercise on "5-sigma" and "10-sigma" events that analysts call unforeseeable, the
  two-sided bound gives 4% for a 5-sigma move and 1% for a 10-sigma move; the one-sided bound gives about 3.8% and about
  1%. A move that is near-impossible under a normal law is not rare in general.
- Inputs: mean and sd of daily P&L (or of the index move).
- How we could use it: a sanity cap for any risk statement Jarvis makes. Our index data agree with the author: NIFTY's
  worst day was -12.98% (23 Mar 2020) against a daily sd of 1.316% (DEEP_NIFTY_BANKNIFTY.md, n 5,087 days), a move of
  about 10 sd. Selling plans must be sized for that, as R103's naked worst day of -Rs 65,483 showed.
- Signal source: none (risk). Maps to: NSE, MCX, crypto (crypto tails are fatter still).
- Our research: tail sizes measured in R102 / DEEP_OPTIONS_SELLING.md section 5; the bound itself not tested by us.

### 9. Running-P&L bound: Kolmogorov's inequality (Ch. 8.4)
- What it computes: for a sum of independent zero-mean steps, the chance that the WORST point of the running total
  exceeds a level has the same Chebyshev bound as the end point alone. In trading terms: an arm with no edge can dip
  as deep during the month as it can finish, and the bound for the deepest dip is no tighter than for the close.
- How we could use it: a quick, assumption-light floor for drawdown risk against the -Rs 40k / -Rs 13k account stops
  when only the mean and sd of daily P&L are known; the bootstrap (HUNT_H15.md) gives sharper numbers.
- Signal source: none (risk). Maps to: all. Our research: not tested by us.

### 10. How many Monte Carlo runs a tail needs, and the hybrid fix (Ch. 8.8.1)
- What it computes: counting simulated losses beyond a level estimates its probability p with a relative 95% error of
  about 1.96 times the square root of (1-p)/(M p) for M runs. To make the error band as small as p itself the book's
  example needs about 1,537 runs at p = 0.2, about 13,830 at p = 0.1, about 15.2 million at p = 0.01 and on the order
  of 1.5e10 at p = 0.001. Its fix ("simulation plus Chebyshev"): simulate only enough to get a threshold exceeded 10-20%
  of the time, measure the mean and variance of losses beyond it, then apply the one-sided Chebyshev bound to that
  tail and multiply by the share of runs beyond the threshold.
- How we could use it: any "chance of a -Rs 40k drawdown" or "P(ruin)" from our bootstraps (for example HUNT_H15.md's
  P(drawdown >= 50%) of 1% for quarter-Kelly) is a rare-event count and needs either a very large number of resamples
  or this hybrid bound; with few independent blocks (H15 had 10 month-blocks) the count is far less certain than it
  looks, which H15 itself flags.
- Signal source: none (risk method). Maps to: all.
- Our research: not tested by us as a method; H15's own honesty flags agree.
- Same chapter: the weak law of large numbers gives a sample-size rule that works for any distribution but is far too
  conservative in practice (a Poisson example needs n = 200,000); diversification means total capital is less than the
  sum of stand-alone capitals unless losses are perfectly correlated, and correlated tails (asset and liability losses
  together) remove that benefit.

### 11. From lattice to lognormal and Black-Scholes; two kinds of error (Ch. 8.8.2-8.8.3)
- What it computes: as the time step shrinks, the mean and variance of log returns scale linearly with time (so
  volatility scales with the square root of time) and the lattice converges to a lognormal price; a probability of one
  half converges faster. The option price converges to Black-Scholes (call delta = N(d1)), which uses only volatility
  and the rate, not the expected return or the real-world up-probability. Monte Carlo prices carry two separate errors:
  discretisation (time step) and estimation (number of paths, shrinking only with the square root of N).
- How we could use it: when Jarvis or a study reprices options by simulation, report both errors; for European index
  options use the closed form instead.
- Signal source: underlying volatility (pricing). Maps to: NSE, MCX, crypto options.
- Our research: DEEP_OPTIONS_SELLING.md already reprices with Black-Scholes at a flat rate; consistent with R89's
  finding that the move is already in the option price (see research/books/fm-capinski-math-finance.md for the detail).

### Ch. 9 - Calculus I: differentiation
Continuity (and uniform continuity), limits, derivatives, the mean value theorem, Taylor series with remainders,
analytic functions, critical points, convexity and Jensen's inequality (a convex payoff is worth at least the payoff of
the average, so uncertainty adds value to a long option). Finance: price sensitivity, optimisation, minimum-variance
pairs, duration and convexity, asset-liability management, the Greeks, utility and optimal allocation, and the
risk-neutral limit.

### 12. Numerical derivatives for Greeks (Ch. 9.7, 9.8.5)
- What it computes: a derivative from a repricer by finite differences. A forward difference has error proportional to
  the step; a central difference (reprice up and down, divide by twice the step) has error proportional to the step
  squared; the second derivative (gamma) uses up, down and centre prices. The book applies the same idea to "effective"
  duration and convexity for instruments with embedded options.
- How we could use it: compute delta, gamma, vega and theta for any structure the app can price, including ones with
  no closed form (smile-aware wings, multi-leg positions), with central differences; check against the closed forms the
  book gives in its exercises (call delta = N(d1), put delta = N(d1) - 1, gamma the same for call and put).
- Signal source: options structure (risk tool). Maps to: NSE index options, MCX, crypto options.
- Our research: not tested by us.

### 13. Minimum-variance pair weight (Ch. 9.8.4)
- What it computes: the weight in one of two assets that minimises the variance of the pair, from the two sds and their
  correlation. It goes short the first asset when the correlation exceeds the ratio of the second sd to the first; with
  a riskless asset available, the minimum-risk portfolio is all riskless.
- How we could use it: combining two arms or two indices (NIFTY and BANKNIFTY versions of the same rule, as in R72's
  cross-index work) by weight rather than by picking one.
- Signal source: none (allocation of underlying-signal arms). Maps to: NSE, MCX. Our research: not tested by us.

### 14. Duration, convexity and immunisation as a model for Greek hedging (Ch. 9.8.5-9.8.7)
- What it computes: price change for a small rate change from the first two derivatives; how duration itself drifts as
  rates move; hedges that set the target's first-order risk to zero with positive second-order exposure. The author
  lists why this "holy grail" is not a free lunch: trading costs, the hedge must still earn at least the riskless rate,
  and the single risk factor is never the only risk. The Greeks (delta, gamma, rho, vega, theta) are introduced as the
  same Taylor approach applied to options.
- How we could use it: as the frame for delta-neutral, long-gamma ideas: zero delta plus positive gamma is paid for in
  theta and costs, just as positive convexity is paid for in yield.
- Signal source: options structure. Maps to: NSE, MCX, crypto options.
- Our research: consistent with R103 (hedged selling roughly breaks even before costs; costs make it lose,
  DEEP_OPTIONS_SELLING.md point 9).

### 15. Utility and the optimal size of one risky bet (Ch. 9.8.8-9.8.9)
- What it computes: with one risky position and cash over one period, a second-order expansion of expected utility gives
  the best amount at risk as the expected excess return divided by (risk aversion times the expected squared excess
  return), where risk aversion is the Arrow-Pratt measure of the utility function. Long if the edge is positive, short if
  negative, smaller for more risk-averse traders; a risk-seeker's best choice is maximum leverage. The achievable utility
  grows with the square of the Sharpe ratio, so among several risky assets the one with the highest Sharpe ratio is
  chosen. Exponential, quadratic, power and log utilities are worked through in exercises.
- Inputs: estimated edge per trade and its second moment, a chosen risk aversion.
- How we could use it: a Kelly-like sizing rule (log utility is the Kelly case) for paper arms, with a dial (risk
  aversion) instead of full Kelly. Our own experience says the edge estimate is the weak point: HUNT_H15.md found the
  Kelly fraction unstable (f* for BN 0.48 and 0.14 on other windows against 1.29 in the study window) and its SPA over
  sizing variants gave p = 0.14. Boss's current frame is fixed lots (HUNT_FINAL.md, HUNT_H17.md).
- Signal source: none (sizing applied to underlying-signal arms). Maps to: NSE, MCX, crypto.
- Our research: HUNT_H15.md (half- and quarter-Kelly with bootstrap drawdowns); the utility formula itself not tested by
  us.

### 16. Risk-neutral calibration of a lattice (Ch. 9.8.10)
- What it computes: under the risk-neutral probability the log return has mean equal to the rate minus half the
  variance; the risk-neutral probability approaches the real one only slowly as steps shrink, which changes the limit.
  Calibrating the lattice drift to "rate minus half variance" with probability one half makes option prices converge
  much faster; for real-world forecasting the true drift must be used instead, with slower convergence.
- How we could use it: if the app ever prices on a tree (American-style MCX options, path-dependent payoffs), calibrate
  this way. Signal source: none (pricing). Maps to: MCX options, crypto. Our research: not tested by us.

### Ch. 10 - Calculus II: integration
Riemann integration, sets of measure zero, the fundamental theorem of calculus, improper integrals, the integral test,
integration by substitution and by parts, Taylor's theorem with an integral remainder, convergence of integrals,
numerical integration, continuous probability and its distributions (uniform, beta, exponential, gamma, Cauchy, normal,
lognormal), and continuous-time finance: continuous compounding, continuous term structures, dividend-reinvested
funds, duration as an integral, the normal distribution function computed numerically, and a general Black-Scholes
formula for any bounded payoff.

### 17. Numerical integration and the normal CDF (Ch. 10.10, 10.12.5)
- What it computes: integrals by the trapezoid rule (error proportional to the step squared) or Simpson's rule (error
  proportional to the step to the fourth power), with explicit error bounds for the normal distribution function.
- How we could use it: price any European payoff as a discounted integral against the risk-neutral density (see 18);
  check the app's N(x) implementation. Signal source: none. Maps to: all options. Our research: not tested by us.

### 18. Generalised Black-Scholes for any payoff, e.g. binary options (Ch. 10.12.6)
- What it computes: the price of any European payoff that is bounded and piecewise continuous is the discounted
  expectation of the payoff under the risk-neutral lognormal law; a binary call (fixed amount if the price ends above
  the strike) is the simplest case, and a binary call plus a binary put pay the fixed amount for sure.
- How we could use it: value "probability of finishing above level X" questions from the chain consistently, and price
  crypto daily binary-like payoffs if we ever study them; with a smile the density should come from market prices, not
  one IV.
- Signal source: volatility / options structure. Maps to: NSE index options, crypto options.
- Our research: not tested by us.

### 19. Fat-tailed laws: Cauchy and lognormal (Ch. 10.11)
- What it computes: the Cauchy law has no mean, variance or moment function at all; the lognormal has every moment but
  no convergent moment function (its moments grow too fast). The point for us: averages and variances can be misleading
  or unstable for fat-tailed P&L, which is why medians and bootstraps matter.
- Signal source: none. Maps to: all. Our research: kurtosis measured in DEEP_NIFTY_BANKNIFTY.md (14.1 / 8.5).

### References and index
A list of standard maths and finance texts (for example Hull, Shreve, Fabozzi) and an index. Nothing practical.

## Performance claims
None about trading profits. The book claims nothing about any strategy making money and gives no market evidence; every
price, return and loss is invented for teaching. The only claims are mathematical (bounds, convergence rates,
no-arbitrage prices) plus the author's descriptive statements that equity returns are about 50:50 up/down with
autocorrelation near zero and fat tails, and that a 5-sigma event is not rare without a normal assumption.

## Risk, sizing and psychology
- Risk is variance or sd, with Chebyshev and one-sided bounds for tails when the distribution is unknown (Ch. 8).
- Capital for a loss book is mean loss plus a margin set from a tail probability; diversification lowers total capital
  only when losses are not perfectly correlated, and correlated tails cancel the benefit (Ch. 8.8.1).
- Sizing: optimal amount at risk equals edge over (risk aversion times second moment); highest Sharpe wins; risk-seekers
  lever up to the limit (Ch. 9.8.9). Minimum-variance pairs (Ch. 9.8.4).
- Hedging first-order risk is not free: costs, required return and other risk factors (Ch. 9.8.6).
- Losses beyond 100% of capital have no real annualised rate (Ch. 2.3).
- No drawdown rules, stops or psychology; Kelly is not named, though log utility gives it.

## How it relates to our research
- Agrees: fat tails make normal-based risk misleading (Ch. 7, 8, 10) - NIFTY kurtosis 14.1, BANKNIFTY 8.5, worst days
  -12.98% / -16.73% (DEEP_OPTIONS_SELLING.md section 5, R102).
- Agrees: "check the hypothesis" (Ch. 1) - R103's apparent selling edge came from stale wing prices
  (DEEP_OPTIONS_SELLING.md point 9).
- Agrees: return signs are close to coin flips (Ch. 7.8.5) - R16 and R88 found size predictable, direction not
  (FINDINGS.md, R16 section).
- Agrees: sizing from an estimated edge is fragile - HUNT_H15.md's unstable Kelly fraction and SPA p = 0.14.
- Agrees: rare-event probabilities from small simulations are uncertain (Ch. 8.8.1) - H15 flags its 10-block bootstrap.
- Not tested by us: Chebyshev or hybrid tail bounds, fourth-moment error bands on realised vol, central-difference
  Greeks, bisection IV as a data check, l1 cost-minimising rebalancing, joint same-date bootstrap of several arms,
  minimum-variance arm pairs, binary-option pricing.
- GEX (R28/R36/R37), IV1 option-market signals, H39 basis, R41 neural nets: the book has nothing specific on them.
- Bennett's book (research/books/bennett-trading-volatility.md) covers implied versus realised volatility and skew as
  traded; this book is the maths base under it.

## What is new to us
- A sample-size rule for rare-event estimates: about 13,830 runs to pin a 10% tail to within its own size, about 15.2
  million for a 1% tail, and the hybrid "simulate to a moderate threshold, then Chebyshev the rest" alternative. Useful
  for every P(drawdown) or P(ruin) number we publish.
- Error bands on realised volatility driven by the fourth moment: a concrete way to say how much of a VIX-versus-realised
  gap on a given day is noise.
- Kolmogorov's inequality as a distribution-free floor on the deepest point of a no-edge P&L path.
- Bisection as a never-fails IV solver whose failure to bracket flags a quote outside no-arbitrage bounds.
- The l1 cost formulation for choosing the cheapest leg adjustments to reach a target delta.

## Could not read
Figures (the Cantor set, function plots, the Cauchy versus normal density plot, lattice diagrams) are images and did
not come through; only their captions and surrounding text. Many formulas are garbled in the text layer (special
glyphs for sums, integrals, square roots and Greek letters), but the prose, worked numbers and tables (for example the
bisection table in Ch. 5.5) were readable and every formula used above could be reconstructed from context. Nothing
else was missing.
