# Mathematical Economics and Finance - Michael Harrison and Patrick Waldron (draft of 2 December 1998)

Source: mathematical-economics-and-finance-harrison-waldron.txt, 153 pages (Trinity College Dublin lecture notes for
courses MA381 / EC3080), read in full on 25 Oct 2026 (text layer, every chapter 1-7, preface and notation; no figures
came through and many sections of the draft are empty - see "Could not read").
Book claims are the authors', not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
An unfinished set of university lecture notes that teaches economists maths and mathematicians economics.
Part I is the maths: linear algebra, vector calculus, convexity and constrained optimisation (Lagrange, Kuhn-Tucker).
Part II applies it to consumer choice, general equilibrium and the no-arbitrage principle.
It then covers choice under uncertainty: state prices, expected utility, risk aversion, mean-variance and Kelly.
It ends with mean-variance portfolio maths, the CAPM and a short derivation of the Black-Scholes formula.

The book has no market data, no backtest and no trading rule. It is a 1998 draft: the authors left placeholders
("[To be written.]", "Figure 3A goes here", "?" in place of citations), and the binomial model, multi-period and
continuous-time sections were never written. Its value for us is a small number of clean ideas about pricing,
risk preferences and portfolio combination, most of which other books in this set (fm-capinski-math-finance.md,
fm-bjork-arbitrage.md, bennett-trading-volatility.md) cover at greater length. Where they overlap, this write-up points
to them instead of repeating.

Our data, for the mapping: NIFTY weekly and BANKNIFTY monthly index options (European, cash-settled), a 1-minute chain
of the nearest expiry within ATM +-10 strikes with IV and OI (last prices, no bid/ask), index spot and futures bars,
India VIX daily, MCX futures and options, Delta Exchange crypto options. Costs: about Rs 70 (NIFTY) / Rs 76 (BANKNIFTY)
per option round trip (knowledge/baselines.yaml, HUNT_H24.md).

## Methods, rules and setups (chapter by chapter)

### Preface and notation
Frames economics as allocation across goods (relative prices), across time (interest rates) and across states of the
world (risky returns), and finance as the last two. Notation only.

### Ch. 1 - Linear algebra
Gaussian elimination, matrix inverse, rank, eigenvalues and definite matrices (sections 1.10 and 1.11 are empty). The
one practical point: a variance-covariance matrix of returns must be symmetric and positive semi-definite, because a
portfolio's variance can never be negative. Pure theory otherwise.

### Ch. 2 - Vector calculus
Metric-space topology, continuity of functions and correspondences, Jacobian and Hessian, chain and product rules,
the implicit function theorem, directional derivatives, a deterministic Taylor expansion (example: a discount factor
expanded in powers of the interest rate) and the fundamental theorem of calculus. Pure theory.

### Ch. 3 - Convexity and optimisation
Concave, quasi-concave and pseudo-concave functions and the Hessian tests for them; first-order, second-order and
uniqueness theorems for unconstrained, equality-constrained (Lagrange) and inequality-constrained (Kuhn-Tucker)
problems; the envelope theorem (the Lagrange multiplier is the gain in the objective per unit of a loosened
constraint); the theorem of the maximum; a closed-form quadratic programme; duality (maximising the objective under a
cost cap and minimising cost for a target objective trace the same frontier). The practical part is method 1.

### 1. Constrained quadratic optimisation and shadow prices (Ch. 3.4-3.6, used again in 6.4)
- What it computes: the best weights when the objective is a quadratic (for example portfolio variance) and the
  constraints are linear (a budget, a target return, no short positions). The closed form keeps only the binding
  constraints; each constraint's multiplier says how much the objective would improve if that limit were loosened
  by one unit.
- Inputs: a covariance matrix of the components' daily P&L, their expected P&L, the constraints.
- How we could use it: to combine the arms with a real record (Liquidity 15+5, R72 Breadth Donchian, R51 Donchian,
  R52 OOV) into a minimum-variance mix for a given expected P&L, and to read the multiplier on the account's risk cap
  (the -Rs 13k month stop) as "what one more rupee of allowed risk is worth".
- Signal source: none (a sizing / combination tool applied to existing arms). Maps to: NSE arms yes, MCX and crypto
  arms yes if any exist.
- Mechanical? yes (a formula).
- Our research: HUNT_H4.md combined the arms by walk-forward weights; "only components positive on all earlier data,
  risk parity" won with +Rs 173 a day per unit and a max drawdown of -Rs 85,271 over 33 test months, while equal lots on
  all components lost -Rs 1,095 a day. A full mean-variance optimiser was not tried; with so few months the expected-P&L
  inputs would be noisy (see method 9).

### Ch. 4 - Choice under certainty
Goods are distinguished by kind, by date and by state of the world. Preference axioms (complete, reflexive,
transitive, continuous, greedy, convex), existence of a utility function, the consumer's budget problem solved by
Kuhn-Tucker, Marshallian and Hicksian demand, indirect utility and expenditure functions, Shephard's lemma, Roy's
identity, Slutsky; general equilibrium and the two welfare theorems with the separating hyperplane (Walras' law, the
fixed-point theorem and multi-period equilibrium are headings only). The practical part is method 2.

### 2. The no-arbitrage principle (Ch. 4.4.2, used in 5.4, 6.4 and 7.2)
- What it says: in equilibrium nobody can buy a bundle (directly or through a combination of other assets) at one
  price and sell the same bundle at a higher price; if they could, they would do it without limit. The book's
  exercise is forward-rate parity: one year at the one-year rate followed by the one-year forward rate must equal two
  years at the two-year rate. It names option pricing by synthetic portfolios as the most powerful use, and later
  (Ch. 6.4) notes that a set of assets whose covariance matrix allows a zero-variance mix is itself a synthetic
  riskless asset and must earn the riskless rate.
- Inputs: prices of the asset and of every combination that copies it.
- How we could use it: as a consistency check on the chain (put-call parity against the future; spreads that cannot
  cost less than zero) and on the futures basis; a quote that breaks it is far more often a stale print than free money.
- Signal source: volatility / options structure (price consistency, no direction). Maps to: NSE index options yes
  (European, cash-settled, future as the hedge), MCX options on futures yes, crypto options yes.
- Mechanical? yes.
- Our research: HUNT_H29.md built the synthetic forward from calls and puts near the money; it leads the index by
  5-30 minutes but the move is already in the option prices, so a buyer cannot use it. R103 (DEEP_OPTIONS_SELLING.md,
  summary point 9) showed apparent profits in the chain came from stale far-wing prices: six BANKNIFTY passes lost
  -Rs 2.9 to -Rs 3.3 lakh once repriced. A parity-arbitrage rule is written up in fm-capinski-math-finance.md and not
  repeated here.

### Ch. 5 - Choice under uncertainty
Probability review; the beta of one variable on another; a Taylor expansion of an expected function of a random
variable in terms of its moments; state-contingent claims and complete markets; options that complete a market and a
butterfly that copies a single state; the expected-utility axioms and the Allais paradox; Jensen's inequality and
Siegel's paradox; risk aversion and its measures; reasons for mean-variance; a half-page on Kelly; one paragraph on
non-expected-utility ideas.

### 3. Beta, and why "uncorrelated" is not "independent" (Ch. 5.2, 6.4, 6.5)
- What it computes: beta = Cov(y, x) / Var(x) and the intercept that make the residual zero-mean and uncorrelated with
  x. The authors stress that the fitted line is the conditional expectation of y given x only when the residual is
  independent of x (multivariate normal returns are the standard case), not merely uncorrelated. The same point
  returns in Ch. 6.5: every risk-averse investor holds a frontier portfolio only if the leftover part has zero
  conditional mean, which holds for normal-like returns.
- Inputs: two return series.
- How we could use it: hedge ratios between indices (BANKNIFTY against NIFTY, MCX mini against big contract), and as a
  warning for any linear "if A moves, B follows" rule: a zero correlation in the body of the data says nothing about
  the tails, where index returns are far from normal.
- Signal source: underlying price (both legs on the underlyings). Maps to: NSE indices yes, MCX yes, crypto yes.
- Mechanical? yes.
- Our research: R72 (HUNT_R72_CROSS_INDEX.md) reads the five indices together; its passing idea is a breadth count
  (at least 4 indices confirming a Donchian break), not a beta, which is the nonlinear "do they move together"
  question the book warns a correlation cannot answer. A beta hedge or beta-residual pairs rule is not tested by us.

### 4. Moments and the stochastic Taylor expansion (Ch. 5.3, 5.8)
- What it says: the expected value of a curved function of a random outcome equals the function at the mean plus a
  variance term scaled by the second derivative, a skewness term by the third, a kurtosis term by the fourth, and so
  on. Applied to utility, the sign of each derivative says whether the investor likes or dislikes that moment; an
  investor whose risk aversion falls with wealth has a positive third derivative and so likes positive skew.
- How we could use it: it explains why far-OTM options and expiry "hero" tickets (big positive skew) can be priced
  above their expected payoff: buyers pay for skew. It also says that ranking strategies on mean and variance alone
  ignores the fat left tail of short-premium books.
- Signal source: volatility / options structure (a lens on pricing, no signal). Maps to: NSE index options yes, MCX
  yes, crypto yes.
- Mechanical? no (a concept).
- Our research: consistent with R98 (FINDINGS.md, "Hero-zero candles on all five indices": 75% accuracy is not
  reachable) and with the naked 09:20 short straddle (DEEP_OPTIONS_SELLING.md point 8: positive over two years but a
  worst day of -Rs 65,483, beyond the account stop). The skew premium itself is covered in
  bennett-trading-volatility.md.

### 5. State prices, complete markets and the butterfly as a single-state claim (Ch. 5.4)
- What it computes: an Arrow-Debreu claim pays 1 in one state and 0 in all others; with as many independent payoffs as
  states the market is complete and every claim has a price. Calls on one underlying at each possible level complete
  the market (their payoff table is triangular). A butterfly (long the call one step below, short two at the level,
  long one step above) pays 1 only when the underlying ends at that level, so its price is that state's price.
  Every security is worth the sum of state prices times its payoffs; the state price equals the state's probability
  times the ratio of marginal utilities then and now.
- Inputs: call prices at adjacent strikes of one expiry.
- How we could use it: dividing each adjacent-strike butterfly price by the strike step gives the market's
  risk-neutral distribution of the expiry close; the app could show Boss "the option market's odds of NIFTY
  finishing above X" and compare it with the realised distribution. Negative butterfly prices flag bad prints.
- Signal source: option chain as context (a read of what the chain prices; no direction). Maps to: NSE index options
  yes, but only inside ATM +-10 where our lake has strikes, and on last prices; MCX thin; crypto options yes.
- Mechanical? yes for the density; using it to trade is not specified by the book.
- Our research: not tested by us. Nearest: IV1 (FINDINGS.md, "Option-market signals alone"): skew, straddle, PCR and
  OI on their own lost on all 5 indices; H29: option prices already contain the move. A risk-neutral-density display is
  context only.

### 6. Jensen's inequality and Siegel's paradox (Ch. 5.6)
- What it says: the average of a concave function is below the function of the average (above, for convex), with the
  gap growing with curvature and variance. Siegel's paradox: a forward price cannot equal the expected future spot in
  every currency at once, because the reciprocal of an expectation is not the expectation of the reciprocal. The
  authors extend the doubt to any theory that treats today's price as the expected future value, the efficient-markets
  idea included.
- How we could use it: a reminder that "the forward / option-implied price is the market's forecast" is not exact;
  and that averaging returns (simple vs log) or averaging volatility vs variance gives different answers.
- Signal source: none (a concept). Maps to: all.
- Mechanical? no.
- Our research: consistent with R102 (DEEP_NIFTY_BANKNIFTY.md): India VIX sits above NIFTY's next-month realised
  volatility on 81% of days (n 4,507 days), so the option market's price is not an unbiased forecast; the gap is a
  risk premium.

### 7. Risk-aversion measures and what they say about position size (Ch. 5.7, 6.3.2)
- What it computes: absolute risk aversion (minus the second derivative of utility over the first) and relative risk
  aversion (that times wealth); quadratic, negative-exponential (constant absolute), power and log (constant relative)
  utilities. Consequences for a single risky bet: (a) "local risk neutrality": any risk-averse investor takes at least
  a small position in a bet whose expected return beats the riskless rate, and none if it only matches it; (b) with
  falling absolute risk aversion the rupee stake rises with wealth; (c) with constant relative risk aversion the
  FRACTION of capital at risk stays constant, with rising relative risk aversion it falls.
- Inputs: the trader's chosen utility (or simply the chosen rule: fixed rupees, fixed fraction).
- How we could use it: names the choice the app already makes. Fixed lots = constant absolute risk; lots that scale
  with capital = constant relative risk (the Kelly family). The book gives no edge estimate, only the logic.
- Signal source: none (sizing). Maps to: all arms, paper and live (LIVE may only lower risk).
- Mechanical? yes once a rule is chosen.
- Our research: HUNT_H17.md and HUNT_H36.md used fixed lots at Rs 1 lakh by Boss's frame; HUNT_H15.md tested growing
  lots (method 8).

### 8. The Kelly strategy (Ch. 5.9)
- What it says (half a page, no formula): growth can be measured by expected wealth, expected growth rate, or expected
  time to reach a goal; safety by the chance of reaching a goal by a date, of staying above a rising path, or of
  reaching a goal before falling to a floor. The Kelly rule (the citation is missing in the draft) maximises the
  long-run growth rate and minimises the expected time to reach a large goal. Nothing is said about the size of
  drawdowns, estimation error or fractional Kelly.
- Inputs (from the standard formula, not given in the book): win probability and payoff ratio of each trade, or the
  mean and variance of trade returns.
- How we could use it: as the upper bound on size for an arm with a proven edge; always fractional, and only after the
  edge passes the gates.
- Signal source: none (sizing). Maps to: NSE arms yes; MCX and crypto only if an arm passes (none has).
- Mechanical? yes.
- Our research: HUNT_H15.md sized Liquidity 15+5 (BN, FIN, MIDCP) by half-Kelly from Rs 1 lakh. It led the holdout and
  the bootstrap ruin risk was about 0, but P(drawdown >= 50%) was 14% (63% for a 30% drawdown); the BN Kelly fraction
  was 0.48 and 0.14 on other windows against 1.29 in the fit window; and the SPA test across the 18 sizing variants
  gave p = 0.14, so the choice of sizing is not statistically separable. Quarter-Kelly cut P(50% drawdown) to 1-5%.
  This is exactly what the book leaves out.

### Ch. 6 - Portfolio theory
Interest compounding (the book's table: GBP 100 at 10% a year becomes 110 compounded yearly, 110.471 monthly and
110.517 continuously); simple returns add across a portfolio, log returns add across time; NPV and IRR. Unit-cost and
zero-cost (hedge) portfolios, short selling. The single-period problem under expected utility; the stochastic discount
factor; risk aversion and the risky share; two-fund separation; the mean-variance frontier with and without a riskless
asset; borrowing limits; the CAPM.

### 9. The mean-variance frontier and the global minimum-variance portfolio (Ch. 6.4)
- What it computes: for a covariance matrix V and expected returns e, the cheapest-variance mix for each target return
  is a straight line in weight space; every frontier mix is a combination of two fixed portfolios. The global
  minimum-variance mix is V-inverse times a vector of ones, normalised. In mean-variance space the frontier is a
  parabola, in mean-standard-deviation space a hyperbola. With a riskless asset the best mixes lie on the line from the
  riskless rate tangent to the frontier; margin limits and a borrowing rate above the lending rate bend that line.
  Any portfolio splits into a frontier part plus an uncorrelated zero-mean part, which gives a beta relation that holds
  by pure algebra, with no assumption on the distribution.
- Inputs: covariance matrix (must be strictly positive definite) and expected returns of the components.
- How we could use it: the minimum-variance mix needs only the covariance matrix, which is far easier to estimate than
  expected P&L; for combining a handful of arms it is the safer use. The book's other point (Ch. 6.5.1): adding more
  equally weighted components drives the portfolio's variance down only to the average covariance, never below.
- Signal source: none (combination of arms). Maps to: NSE yes, MCX / crypto if arms exist.
- Mechanical? yes.
- Our research: HUNT_H4.md found daily correlations across arm families low (|r| <= 0.2) but high inside a family
  (Liquidity NIFTY vs SENSEX 0.70), and its walk-forward chose risk parity over equal lots; a pure minimum-variance mix
  is not tested by us.

### 10. Two-fund separation and the stochastic discount factor (Ch. 6.3)
- What it says: if utility belongs to the HARA family (log, power, negative exponential), traders of different wealth
  hold the same risky mix and differ only in how much cash sits beside it. The first-order condition prices every
  payoff as the expectation of the payoff times one stochastic discount factor; a risk premium is minus the covariance
  of the asset with marginal utility.
- How we could use it: supports one "risky book" whose size Boss scales up or down, rather than a different mix of arms
  for each account size. The discount-factor view explains why insurance-like payoffs (puts that pay in crashes) are
  dear.
- Signal source: none. Mechanical? partly. Our research: not tested by us.

### 11. CAPM (Ch. 6.5)
- What it says: if everyone holds frontier portfolios the market portfolio is on the frontier, and an asset's expected
  excess return is its beta to the market times the market's excess return (zero-beta form without a riskless asset);
  under normal returns the market premium equals the economy's aggregate risk aversion times the market variance.
- How we could use it: for single-stock options at most (beta to NIFTY as a hedge ratio). Nothing for intraday index
  option buying.
- Signal source: underlying price. Maps to: NSE stock options partly; MCX and crypto no.
- Mechanical? yes. Our research: not tested by us.

### Ch. 7 - Investment analysis
The introduction, the binomial model ("still has to be typed up"), multi-period problems (one paragraph) and
continuous time (a single "?") are empty. Only the Black-Scholes section is written.

### 12. Black-Scholes (Ch. 7.2.2)
- What it computes: with the underlying following a geometric Brownian motion and a riskless bond, a stock-and-option
  mix can be made riskless for an instant, so no arbitrage gives a partial differential equation whose solution for a
  European call is the Black-Scholes formula. The book verifies the solution by computing the partial derivatives:
  delta = N(d1), gamma = N'(d1) / (S sigma sqrt(tau)), and a theta that is the time-decay term. The stock's expected
  return drops out.
- Inputs: spot (or the future for an index), strike, time to expiry, rate, volatility.
- How we could use it: already used in the app and research to reprice options whose last print is stale (R103
  repriced exit legs at their last IV, DEEP_OPTIONS_SELLING.md) and to illustrate decay. The missing drift is the
  formal reason a directional forecast does not change what an option is worth.
- Signal source: volatility / options structure (pricing, no signal). Maps to: NSE index options yes (Black-76 on the
  future in practice), MCX options on futures yes, crypto options yes.
- Mechanical? yes.
- Our research: consistent with R89 (FINDINGS.md, "A strategy from R88"): straddles bought when a big move was forecast
  were about zero gross because options already price the move (out of sample 2,214 straddles, 0 of 18 versions
  positive), and with R16 / R88: size is predictable, direction is not.

## Performance claims
None. The book contains no returns, win rates, backtests or market data. The only figures are classroom examples (the
compounding table, the Allais lotteries of GBP 1 million and 5 million). The Kelly claim (best long-run growth and
shortest expected time to a large goal) is stated without proof or numbers.

## Risk, sizing and psychology
- Risk aversion is concave utility; how fast the stake should change with wealth depends on whether absolute or
  relative risk aversion is constant (method 7). A risk-neutral investor would put everything in the highest-mean
  asset; risk aversion is what stops that.
- Any bet with a positive edge deserves at least a small stake (local risk neutrality); a bet with zero edge deserves
  none. Our costs turn most "small positive gross edges" into zero or negative edges, so this argues for no stake in
  most rules we tested.
- Kelly maximises growth (method 8); the book does not mention drawdowns, which H15 found to be the real risk.
- Diversification lowers variance only to the average covariance between components.
- The Allais paradox (Ch. 5.5) shows real people break the expected-utility axioms; the non-expected-utility section is
  one paragraph with no content. No trading psychology.

## How it relates to our research
- Agrees, measured: options already contain the expected move and the drift does not enter their price (Black-Scholes,
  Ch. 7) - our H29 and R89 findings; option prices are not unbiased forecasts (Siegel / Jensen, Ch. 5.6) - R102's
  81% of days with VIX above realised.
- Agrees, measured: no-arbitrage means apparent free profits in last-price chain data are usually bad prints - R103's
  stale wings.
- Kelly (Ch. 5.9): our H15 applied half-Kelly and found what the book omits: unstable fractions and deep drawdowns.
- Portfolio maths (Ch. 3, 6): H4 combined arms by risk parity walk-forward; a mean-variance or minimum-variance mix of
  the arms with a real record is not tested by us.
- Butterfly state prices (Ch. 5.4): not tested by us; IV1 says option-market numbers alone do not give a trade.
- Nothing in the book touches GEX (R28/R36/R37), neural nets (R41), costs models, Monte Carlo or backtesting method.
  For volatility trading, see bennett-trading-volatility.md.

## What is new to us
- A risk-neutral distribution read from adjacent-strike butterfly prices, as a context display ("the chain's odds of
  NIFTY closing above X at expiry") and as a bad-print check; we have never built it.
- The shadow-price reading of constraints: the multiplier on the account's monthly loss cap says what loosening it
  would be worth - a clean way to show Boss the cost of a risk limit.
- A minimum-variance combination of the arms with a real record, using only their covariance, as an alternative to
  H4's risk parity (paper only, logged, and only ever lowering risk on live).
- The warning that an uncorrelated residual is not an independent one: worth stating in every cross-index or hedge
  study, because index tails are not normal.

## Could not read
- All figures: the draft has placeholders only ("Figure 3A goes here", Figures 3B-3F, 4A, 1.17.1, 3.11.1-3.15.2), and
  the two Cobb-Douglas graphs were external .WMF files that are not in the text.
- Citations are printed as "?" throughout, so the sources (including the Kelly reference) cannot be identified.
- Sections the authors never wrote: 1.1, 1.10, 1.11, 2.1, 3.1, 4.1, 4.7 (Walras, fixed point, existence: headings
  only), 4.9 (one paragraph), 5.1, 5.10 (one paragraph), 7.1, 7.2.1 (binomial model), 7.3 (one paragraph), 7.4 ("?").
- Matrix displays and some formulas came out partly garbled in the text layer, but the surrounding prose made every
  result clear.
