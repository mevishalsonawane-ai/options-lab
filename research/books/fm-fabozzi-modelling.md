# The Mathematics of Financial Modeling and Investment Management - Sergio M. Focardi and Frank J. Fabozzi (2004)

Source: the-mathematics-of-financial-modelling-fabozzi.txt, 801 pages, read in full on 25 Oct 2026 (text layer, preface,
all 23 chapters and the index; charts and diagrams that were images and many equations came through garbled - see
"Could not read").
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

This is a graduate-level textbook (Wiley), not a trading manual. About a third is pure mathematics (calculus, matrices,
probability, optimisation, stochastic calculus), a third is econometrics and fat tails, and a third applies it to
pricing, portfolios, bonds, credit and risk. It contains no complete trading rule with entry, stop and exit; Ch 19
lists classic technical and statistical-arbitrage ideas without parameters.

## What it is about
1. A "financial engineering" view of investing: markets are modelled with probability, stochastic processes and econometrics, then priced, optimised and risk-managed.
2. The maths toolkit: calculus, matrix algebra, probability with filtrations (no look-ahead), optimisation, Ito calculus, SDEs, PDEs.
3. The econometrics: stylized facts of returns, ARMA, unit roots, cointegration, factor models, random-matrix noise, GARCH, regime switching, fat tails and extreme-value theory.
4. Pricing: arbitrage and state prices in finite states, the binomial model, Black-Scholes by replication, martingale measures, term-structure and credit models.
5. Practice: mean-variance and its unstable inputs, CAPM and multifactor models, equity and bond portfolio management, tracking error, VaR versus expected shortfall, SPAN-style scenarios.

## Methods, rules and setups

Chapter by chapter. Practical methods get a block; theory gets 1-2 lines.

### Preface and Ch 1. From art to engineering in finance
Teaches: the five-step investment process (objectives, policy, strategy, selection, evaluation), active versus passive
management and tracking error. Models are approximations that hold for limited spans and must be recalibrated; the
amount of data, not computing power, limits how complex a model can be. A survey of quant shops says they weight models
by past performance and trust a model's "4 or 5 standard deviation" warning over judgement.
For us: small samples call for simple models - the same lesson as our walk-forward gates.

### Ch 2. Overview of financial markets, assets and participants
Teaches: the bid-ask spread as the dealer's price for immediacy, wider when prices are volatile and order flow thin;
total trading cost = commissions and fees + execution cost (market impact and timing) + opportunity cost of trades not
done; order types; futures cost of carry; option value = intrinsic + time value and its six drivers; the three forms of
market efficiency.

#### 2a. Stop orders versus stop-limit orders (practical)
- What it says: a stop becomes a market order when touched, so a brief spike can trigger an early exit and the fill can
  slip; a stop-limit protects the price but may never fill and so may not protect at all.
- Inputs: the order type at the broker.
- How we could use it: this is the SL-M versus SL-L choice for the stops we rest at the broker (CLAUDE.md "live stops
  also rest at the broker"). For a stop whose job is protection, a fill matters more than the price; a stop-limit needs
  a generous limit band.
- Signal source: none / discretionary (execution, not signal).
- NSE / MCX / crypto: NSE options use SL with a limit; thin far strikes and MCX options can gap through; crypto venues
  offer both.
- Our research: HUNT_H24 and knowledge/baselines.yaml price the spread (real half-spread 0.16% of premium on
  NIFTY/BANKNIFTY near-ATM, up to about 2x at signal time); slippage on a triggered stop is not measured separately.

#### 2b. Futures cost of carry (practical)
- What it computes: fair futures = spot plus carry (financing minus dividend yield); outside a band set by costs and
  unequal borrow/lend rates, cash-and-carry or reverse arbitrage pays. Converges to spot at expiry; index arbitrage is
  not riskless because the basket tracks imperfectly. The futures market is where price discovery often happens.
- Inputs: spot, rate, dividend yield, days to expiry.
- How we could use it: the expected basis when we signal on index futures instead of spot (CLAUDE.md allows either);
  a basis far from carry is a data or liquidity flag, not a signal.
- Signal source: underlying price.
- NSE / MCX / crypto: NIFTY/BANKNIFTY futures; MCX futures carry storage too; crypto perpetuals replace carry with a
  funding rate.
- Our research: H39 found the heavyweights' stock options move with the index, not before it (every IC at most 0.012
  from 2 minutes ahead), so a lead from related contracts is weak; the brief's "futures/basis" link is that same idea.

### Ch 3. Milestones in financial modeling and investment management
History only: Walras and Pareto equilibrium, Bachelier's 1900 random walk and option prices, Lundberg's insurance ruin
theory, Markowitz, Modigliani-Miller, efficiency, CAPM, APT, Black-Scholes-Merton, and the martingale approach.
One idea worth keeping: writing options is like writing insurance; if prices can jump, the risk can only be reduced,
never hedged away.

### Ch 4. Principles of calculus
Pure maths (limits, derivatives, integrals, transforms), with two applied bits. The log-derivative is the instantaneous
return. Bond duration and convexity are the first two terms of a Taylor expansion: for a 20-year 9% bond priced at a
6% yield, a 200 bp rise gives -21.32% from duration alone, +3.28% from convexity, so -18.0% against an actual -18.4%;
for a 10 bp move duration alone is nearly exact.

#### 4a. Delta-gamma as a Taylor expansion (practical)
- What it computes: the option's change for a small move is delta x move + half gamma x move squared; the book shows the
  same structure for bonds and warns it is good for small moves and poor for large ones.
- Inputs: delta, gamma, size of move.
- How we could use it: a quick P&L estimate for a short straddle; for gap-sized moves use full repricing.
- Signal source: volatility / options structure (risk measure, not a signal).
- NSE / MCX / crypto: any option book.
- Our research: R103 repriced stale wing legs with full Black-Scholes at the leg's last IV, not a Taylor shortcut - the
  right choice for 4-6% index days such as 4 Jun 2024.

### Ch 5. Matrix algebra
Pure maths: vectors, matrices, determinants, rank, eigenvalues and eigenvectors (the basis of principal components),
diagonalisation, singular values. Portfolio exposure is a weighted sum.

### Ch 6. Concepts of probability
Teaches: the main interpretations of probability; sigma-algebras and filtrations (the formal statement that a strategy
may only use information available at the time); conditional expectation; martingales as fair games; moments,
correlation and copulas. Two cautions: a financial series is one realisation, so nothing can be estimated without
assuming stationarity; and zero correlation is not independence - non-linear dependence can hide risk.

#### 6a. "Mean reversion" that is not a signal (practical caution)
- What it says: even pure IID noise shows a trivial reversion - after an unusually high value the next one is more
  likely to be lower - without any forecasting power.
- How we could use it: every reversal study needs a coin-flip or shuffled baseline that shows the same effect. Our
  random-entry twins in R72 and the coin-flip bands in the swing studies do this.
- Signal source: none (a test-design point).

### Ch 7. Optimization
Pure methods: gradients, Lagrange multipliers, linear and quadratic programming (Markowitz is a convex QP), calculus of
variations, dynamic programming, and stochastic programming with scenarios and recourse (decisions made only on
information known at the time). Practical only as the machinery behind Ch 16 and Ch 21.

### Ch 8. Stochastic integrals
Theory: Brownian motion, Ito versus Stratonovich (Ito does not peek ahead, so finance uses it), paths spread with the
square root of time and are nowhere smooth. The time for a random walk to return to zero has an infinite mean, which is
why a doubling (martingale) betting system needs infinite capital to be sure of winning.

### Ch 9. Differential equations and difference equations
Theory and numerics: ordinary and partial differential equations, Euler and Runge-Kutta schemes, chaos (tests for
low-dimensional chaos in economic data came back negative), and the heat equation, to which Black-Scholes reduces. An
explicit finite-difference grid is only stable if the time step is small relative to the price step squared - relevant
only if we ever price on a grid.

### Ch 10. Stochastic differential equations
Teaches: Ito's formula, strong versus weak solutions, and three workhorse processes: arithmetic Brownian motion, the
Ornstein-Uhlenbeck mean-reverting process (the base model for a spread that reverts), and geometric Brownian motion,
whose log price drifts at mu minus half the variance.

#### 10a. Volatility drag (practical)
- What it computes: the median path of a compounding price grows at the mean return minus half the variance; the
  average outcome is pulled up by a few big winners.
- Inputs: mean and volatility of returns (per trade or per day).
- How we could use it: compounding position sizes on a volatile arm make the typical account grow slower than the
  average back-test path; report median and percentile wealth, not only the mean (see 16b).
- Signal source: none (sizing).
- Our research: H15's compounding study already shows it - half-Kelly had P(drawdown >= 30%) of 63%.

#### 10b. Ornstein-Uhlenbeck spread (practical)
- What it computes: a spread that is pulled back to its mean at a fixed speed; gives a half-life and a z-score band.
- Inputs: a spread series (for example the log ratio of two indices after a hedge ratio).
- How we could use it: the model behind the pairs rule in the rules file; the half-life tells whether reversion is fast
  enough to pay for the options' theta and costs.
- Signal source: underlying price (both legs).
- NSE / MCX / crypto: NIFTY/BANKNIFTY, GOLD/SILVER, BTC/ETH.
- Our research: R72 cross-index C2 "laggard catch-up" failed (-Rs 1,00,746 in the final period): the laggard usually
  stays behind.

### Ch 11. Financial econometrics: time series concepts, representations and models
Teaches the stylized facts: single-stock returns have almost no autocorrelation at any lag; volatility is strongly
autocorrelated and the correlation decays slowly; returns from minutes to days have fat (roughly power-law) tails and
become close to lognormal only after a few days; crashes are outliers even against power laws; assets are strongly
cross-correlated; "trend" depends on the model. Then ARMA models, the Wold representation, stationarity, unit roots
(Dickey-Fuller, ADF, Phillips-Perron) and the trend-stationary versus random-walk distinction.

#### 11a. Size is forecastable, direction is not (practical)
- What it says: the sign of returns is close to unforecastable, while their size (volatility) clusters and can be
  forecast.
- How we could use it: aim models at range, straddle pricing and sizing, not at direction.
- Signal source: underlying price/volume.
- Our research: this is our strongest repeated finding. R16: next 30/60-minute range forecast error 15-20% below the
  time-of-day average in the holdout; direction AUC 0.509-0.536. R88: 225 of 305 size findings held, 0 of 8 direction
  findings. R41 neural nets: direction AUC 0.510-0.517; range R2 0.432 against 0.382 for trees.

### Ch 12. Financial econometrics: model selection, estimation and testing
Teaches: choosing a model family is a creative step - an unconstrained search over all models cannot work, so average
several models when unsure. Flexible models (ARMA with many lags, neural nets) fit any sample and forecast badly; match
complexity to the data and use a train/test split, AIC or BIC. Maximum likelihood and its properties; random-walk tests
(most stocks do not reject a random walk); factor models, PCA and VAR; cointegration and error-correction models;
ARCH/GARCH and Markov switching.

#### 12a. Random-matrix noise in correlation matrices (practical)
- What it computes: compares the eigenvalues of a sample correlation matrix with those of pure noise of the same size
  (Marchenko-Pastur band, which depends on days divided by assets). Only eigenvalues above the band carry information:
  the market and a handful of sectors. The book's S&P 500 sample (2001-03) had an average correlation of about 17%.
- Inputs: return matrix (T days x N assets).
- How we could use it: before building a factor or "breadth" model from many contracts (the 5 indices, 10 MCX
  contracts, many strikes), check how many factors rise above noise; drop the rest.
- Signal source: underlying price.
- Our research: not used yet; R72's breadth Donchian (4 of 5 indices) is a crude one-factor version.

#### 12b. Cointegration test and error-correction (practical)
- What it computes: whether a combination of two or more random-walk prices is stationary; if so the spread reverts and
  an error-correction model forecasts the next move of each leg. The book's ADF test on 487 S&P 500 stocks (Jan 2001 to
  Dec 2003) found 87% integrated of order one.
- Inputs: aligned price series, lag choice.
- How we could use it: test NIFTY versus BANKNIFTY, GOLD versus SILVER, BTC versus ETH, index futures versus spot -
  see the pairs rule. The book says the evidence is mixed, mostly found in small portfolios and unstable.
- Signal source: underlying price.
- Our research: R72 (cross-index) passed only the breadth Donchian (final Rs 1,71,366 against Donchian's Rs 55,439,
  random-twin p 0.008, q 0.032); C2 laggard catch-up and C6 SMT divergence failed. No formal cointegration test yet.

#### 12c. GARCH and its limits (practical)
- What it computes: tomorrow's variance from today's squared return and today's variance; gives volatility clustering
  and fat unconditional tails.
- Book caveat: one GARCH cannot fit both the clustering and the fat tails at once, even with Student-t shocks; useful
  tool, not a theory. Multivariate GARCH explodes in parameters. Realised volatility from intraday data is a better
  observed input.
- How we could use it: a baseline for range forecasts (our R16/R88 models already beat a time-of-day average by 15-22%).
- Signal source: underlying price.
- Our research: see the Javaheri and econometrics write-ups for GARCH detail; our models use realised range directly.

#### 12d. Markov regime switching (practical)
- What it computes: a hidden two-state (calm / stressed) model whose parameters change by state; estimated by EM.
- Book caveat: needs 20-30 years of data for stable estimates on daily data.
- How we could use it: calm/stressed volatility states for sizing straddle sellers; on intraday data we have enough bars.
- Signal source: underlying price (or VIX as volatility / options structure).

### Ch 13. Fat tails, scaling and stable laws
Teaches: definitions of fat tails, power laws and the tail index; stable (Levy) laws; extreme-value theory (the
Frechet/Gumbel/Weibull limits, the generalised extreme-value law, peaks over threshold); estimating the tail (QQ-plot,
Hill estimator, its bias-variance trade-off in how many top observations to use). Key intuition: in a fat-tailed world
the past does not show all that can happen, so history under-samples extremes. GARCH with normal shocks already makes
power-law tails. Evidence: index tails look like power laws with exponent above 2 (finite variance), so stable laws are
not a good fit; scaling holds only from minutes to a few days; crashes remain outliers.

#### 13a. Hill tail estimator (practical)
- What it computes: the tail exponent from the top k absolute returns (the average log ratio of each to the k-th);
  pick k by stability or bootstrap.
- Inputs: daily or intraday returns; choice of k.
- How we could use it: size the worst day of a short-option arm from the fitted tail, not from the sample's worst day.
- Signal source: underlying price.
- NSE / MCX / crypto: BANKNIFTY has the fatter tail of the two indices; NATURALGAS and crypto fatter still.
- Our research: R103's naked 09:20 short straddle made +Rs 64k on NIFTY (1,272 days, 62% win) but its worst day was
  -Rs 28,876 (4 Jun 2024); a tail fit says how often that should recur.

#### 13b. VaR can fall while expected shortfall rises (practical)
- What it says: on the book's 23-bond credit portfolio, moving from normal to fatter-tailed (t) returns lowered the 90%
  VaR from 102.9 to 96.6 bp but raised the 90% expected shortfall from 240.3 to 256.2 bp.
- How we could use it: a VaR-style "loss on 1 day in 20" can look better exactly when the tail gets worse. Report
  expected shortfall (average of the worst days) and the worst day for every option-selling or overnight study.
- Signal source: none (risk measure).
- Our research: our studies report worst day and max drawdown, not expected shortfall; adding it is cheap.

### Ch 14. Arbitrage pricing: finite-state models
Teaches: the law of one price with a worked replication (two assets replicate a third more cheaply, so buy the pair and
sell the third); state prices exist and are positive if and only if there is no arbitrage; risk-neutral probabilities
are normalised state prices; a market is complete when the payoffs span all states. Multi-period trees, martingale
measures, the binomial model (risk-neutral up probability = (1+r-d)/(u-d)) with American options as stopping problems,
and APT. With more states than securities the market is incomplete and many prices fit.

#### 14a. Binomial tree pricing (practical)
- What it computes: an option value by stepping a two-branch tree back from expiry under risk-neutral probabilities;
  handles early exercise.
- Inputs: spot, strike, rate, volatility, time, steps.
- How we could use it: a check for American-style prices; NSE index options are European, so Black-Scholes suffices.
- Signal source: volatility / options structure.
- Our research: see the binomial-models and Capinski write-ups.

### Ch 15. Arbitrage pricing: continuous-state, continuous-time models
Teaches: Black-Scholes by replication - hold delta = dC/dS shares and the rest in cash, and the call is copied exactly
under constant volatility, constant rates, frictionless continuous hedging and no jumps. Girsanov's theorem: changing to
the risk-neutral measure changes the drift to the rate but leaves volatility alone, so the option price does not depend
on the stock's expected return. Generalisations to local volatility and stochastic rates are solved numerically.

#### 15a. The price ignores direction (practical)
- What it says: a fair option price depends on volatility, not on where you think the market is going; a directional
  edge only shows up in the trade, not in the "fair" premium.
- How we could use it: our option-buying arms profit only if the underlying signal has an edge larger than the implied
  volatility already priced in plus costs - the same split as "signal on the underlying, trade the option".
- Signal source: volatility / options structure.
- Our research: R102 found VIX over-states NIFTY's next 21 days on 81% of days (median ratio 1.28, 4,507 days) but
  under-states BANKNIFTY's on 60%; R88 found rich implied against realised gives about 0.85-0.9x. Bennett's write-up
  (research/books/bennett-trading-volatility.md) covers implied versus realised trading; not repeated here.

### Ch 16. Portfolio selection: mean-variance model
Teaches: Markowitz minimum variance for a target return, the efficient frontier, the capital market line and the
tangency portfolio, utility and risk aversion, constraints (sectors, turnover, minimum lots, which make it a mixed-integer
problem), fat-tailed extensions, multi-period consumption models.

#### 16a. Optimisers maximise errors (practical caution)
- What it says: the optimiser is extremely sensitive to the expected-return inputs and piles into whatever is
  over-estimated. The book's inputs moved a lot between 1990-95 and 1996-2000: MSCI emerging markets returned 16.3% a
  year, then 0.1%; the S&P/EAFE five-year correlation rose from about 0.45 to 0.73. The highest-volatility assets have
  the least predictable means. Remedies: cap weights of poorly estimated assets, shrink toward a prior (Black-Litterman).
- How we could use it: any capital split across our arms (or a Kelly fraction) estimated on one window will swing; cap
  and shrink.
- Signal source: none (sizing).
- Our research: H15 found the Kelly fraction unstable - BANKNIFTY f* 1.29 on the study window against 0.48 and 0.14 on
  others - and the app stays on fixed lots (HUNT_FINAL, H17, H36).

#### 16b. Monte Carlo wealth percentiles (practical)
- What it computes: simulate many wealth paths for a chosen mix and show percentiles at each horizon instead of one mean
  and one standard deviation. In the book's example the riskier mix had a lower 5th-percentile 10-year outcome ($137
  against $146 per $100) but a much higher top end; downside dollars weigh more with clients. A related "risk of loss"
  check: the probability of missing a return threshold (for example a 15% chance of a loss) over the horizon.
- Inputs: return distribution per period, rebalancing rule, horizon.
- How we could use it: show Boss the 5th/50th/95th percentile account path for each arm and the probability of hitting
  the account stop (-Rs 40k drawdown or -Rs 13k a month).
- Signal source: none (sizing / reporting).
- Our research: we already bootstrap (H1, H15 with 5,000 paths, H16 stationary bootstrap with 2,000 paths and 10-day
  blocks, H17, B2 day bootstrap). The book's "complete and coherent scenarios" (Ch 21) point applies: a bootstrap of a
  calm window under-samples crashes (H15's flag that its 10 month-blocks flatter K2).

### Ch 17. Capital asset pricing model
Teaches: CAPM assumptions, systematic versus specific risk (about 20 random stocks remove most specific risk), the
security market line, the characteristic-line regression (R2 about 0.3 for one stock, above 0.9 for diversified
portfolios). Tests: the line is flatter than CAPM says (low-beta stocks earn more than predicted, high-beta less);
other factors are priced; CAPM is strictly untestable because the market portfolio is unobservable. Random-matrix
studies find only the market and sector eigenvalues stand out, stably over time. Conditional CAPM lets beta vary.
For us: an index option is pure market factor; nothing to trade here.

### Ch 18. Multifactor models and common trends for common stocks
Teaches: factor models are same-time regressions and do not forecast unless the factors' own dynamics are modelled.
Barra-style risk models (risk indices such as volatility, momentum, size, trading activity, value plus industries).
Factor analysis versus PCA (on 2001-03 S&P 500 data the 10 largest eigenvalues match the 10 sectors; the top one is the
market). Dynamic factor models and the Kalman filter. Cointegration again: Engle-Granger, Johansen, Stock-Watson common
trends. Lead-lag: small-firm returns follow large-firm returns of the previous period.

#### 18a. Spurious cointegration (practical caution)
- What it says: among computer-generated independent random walks a few percent of pairs pass a cointegration test by
  chance; with a big universe ask whether the count of "cointegrated" pairs is above chance, not whether one pair
  passes. The book adds that cointegration is largely unstable and small samples distort the tests.
- How we could use it: any pairs search over our 5 indices, 10 MCX contracts and crypto coins must be corrected across
  all pairs tried (Benjamini-Hochberg, as CLAUDE.md requires) and re-tested walk-forward.
- Signal source: underlying price.
- Our research: R72 used random-entry twins and q-values; C2 and C6 failed.

#### 18b. Kalman filter (practical)
- What it computes: a running best estimate of a hidden state (for example a drifting hedge ratio or a smoothed trend)
  updated bar by bar with only past data.
- How we could use it: a time-varying hedge ratio for the NIFTY/BANKNIFTY spread (R102 measured a fixed beta of 1.157
  and correlation 0.877 over 5,086 days).
- Signal source: underlying price.

### Ch 19. Equity portfolio management
Teaches: value captured = information value minus implementation cost; the risk-return tiers of index (tracking error up
to 0.2%), enhanced (0.5-2%) and active (at least 4%) managers; tracking error as the standard deviation of active
returns, backward and forward looking; 15-20 names remove most specific risk; style classification; passive methods
including cointegration-based tracking (stationary tracking error); top-down and bottom-up active styles; the
fundamental law; technical rules; overreaction; chaos and neural-net claims; market-neutral and statistical arbitrage;
risk decomposition with a Barra example (active variance 96.67 = factor 81.34 + specific 15.33).

#### 19a. Fundamental law of active management (practical)
- What it computes: information ratio is roughly skill (information coefficient, IC) times the square root of the number
  of independent bets a year.
- How we could use it: an index-direction trader makes few independent bets, so needs a high IC; our measured direction
  ICs are near zero (R16, R88, H39), so direction alone cannot carry an arm. Breadth comes from many independent
  contracts or many independent days, not from many trades on the same move.
- Signal source: none (a planning identity).

#### 19b. Classic technical rules (as listed by the book)
- Filter rule: buy after the price rises x% from a low, hold until it falls x% from a high (then sell or go short).
- Moving-average rule: buy when the price is above a band around a long average (the book's example is a 200-day
  average), sell when below.
- Advance/decline line: cumulative advances minus declines, for short-term market direction.
- Relative strength: a rising stock/index ratio marks an uptrend (momentum).
- Short-interest ratio: the book notes the sign is ambiguous.
- Market overreaction: after extreme unexpected news the price over-shoots and corrects; the book gives no rule for
  spotting the event or timing the reversal.
- The book's verdict: academic tests (Brock, Lakonishok and LeBaron and others) found some forecastability, but
  profitability after costs is unclear.
- Signal source: underlying price (breadth for A/D).
- NSE / MCX / crypto: all; the filter and moving-average rules are in the rules file with literal parameters.
- Our research: R102 found daily momentum reversed in the holdout and 1 of 70 sign rules confirmed (an artefact); R72's
  breadth Donchian is the one breadth idea that passed.

#### 19c. Market-neutral and pairs trading (practical)
- What it says: a market-neutral fund needs four things - no correlation with the index, a return target per regime, a
  volatility cap and a maximum drawdown limit. Statistical arbitrage goes long high-alpha and short low-alpha names
  within the same beta band. Pairs trading (Morgan Stanley, 1980s) picks pairs whose relative price varies little and
  trades a spread beyond a threshold back to the mean; cointegration formalises it, but many found pairs are spurious.
- Signal source: underlying price.
- NSE / MCX / crypto: NIFTY/BANKNIFTY (long one index's ATM call, the other's ATM put), GOLD/SILVER, BTC/ETH.
- Our research: R72 (see 12b); the pairs rule in the rules file.

### Ch 20. Term structure modeling and valuation of bonds and bond options
Teaches: bond pricing, yield to maturity, bootstrapping spot rates, stripping arbitrage, forward rates, term-structure
theories; continuous-time bond pricing; Feynman-Kac (any European payoff = the discounted average over simulated paths,
or the solution of a PDE); PCA of yield curves (level about 90%, slope about 8%, curvature about 2%); short-rate models
(Vasicek, CIR, Hull-White and others), HJM and the LIBOR market model; Euler discretisation of an SDE for Monte Carlo.
For us: only the rate input to Black-Scholes and the carry for futures. The PCA idea transfers to the IV surface (level,
skew, term).

#### 20a. Euler Monte Carlo of an SDE (practical)
- What it computes: step a price forward with drift times dt plus volatility times root-dt times a normal draw; repeat
  many paths and average a payoff or a P&L.
- How we could use it: stress an arm under a model with jumps or regime-switching volatility, as a complement to the
  bootstrap (which can only replay history).
- Signal source: none (simulation).

### Ch 21. Bond portfolio management
Teaches: the spectrum from pure indexing to full active; tracking error broken down by risk factors (a 57-bond example
where systematic and specific tracking error combine in variance to 52 bp, and greedy swaps cut it to 16.4 bp with
diminishing returns); cash-flow matching as linear and mixed-integer programmes; immunisation by matching duration and
convexity; scenario optimisation; scenarios must be complete (including extremes) and coherent (respecting
correlations).

#### 21a. SPAN-style scenario grid (practical)
- What it computes: the loss of the whole book under a fixed grid of price and volatility moves; the exchange's SPAN
  uses 16 scenarios and takes the worst of 14 ordinary ones or 35% of the loss in the 2 extreme ones.
- Inputs: positions, a full pricer, the grid.
- How we could use it: a pre-trade check for any short-option arm on paper - reject a structure whose grid loss exceeds
  a set share of the account stop; this lowers risk only.
- Signal source: volatility / options structure.
- NSE / MCX / crypto: NSE and MCX margins are SPAN-based; crypto venues use their own risk grids.
- Our research: DEEP_OPTIONS_SELLING.md explains SPAN margins; the lake has no SPAN files, so R48, R94 and R103 used
  stated margin estimates.

### Ch 22. Credit risk modeling and credit default swaps
Teaches: CDS mechanics and basket swaps; the Merton structural model (equity is a call on the firm's assets); compound
options and barrier, stochastic-rate and jump extensions; reduced-form models with a default intensity, calibrated from
bond prices; rating transition matrices as Markov chains; default correlation (perfect dependence is not perfect
correlation; in the book's example one firm's default implies the other's yet the correlation is only 2/3); exponential
default times; the Gaussian copula.

#### 22a. Hazard rate for rare jumps (practical)
- What it computes: the probability of a rare event in a window from a constant intensity (survival decays
  exponentially); default times can be simulated as minus log of a uniform divided by the intensity.
- How we could use it: model gap days (for example moves of 4% or more) as a Poisson process in a Monte Carlo of a
  short-option arm, and correlate the gaps across NIFTY, BANKNIFTY and MCX with a copula.
- Signal source: underlying price.
- Our research: R102 counted gaps of 0.5% or more on 20% / 34% of days (NIFTY / BANKNIFTY); see the copula write-up.

#### 22b. Transition matrix of regimes (practical)
- What it computes: the chance of moving between states (calm, normal, stressed volatility) from one day to the next;
  n days ahead is the matrix to the power n.
- How we could use it: regime persistence for sizing; estimable from our VIX and realised-range history.
- Signal source: volatility / options structure.

### Ch 23. Risk management: an overview
Teaches: with stochastic volatility or jumps the market is incomplete - an option cannot be replicated, so prices come
from economic arguments and the option writer bears the large moves ("underwriting options is a risky business"; equity
risk has no natural counterparty in aggregate). Risk models: RiskMetrics, factor models, scenario systems, credit
models; operational risk. Risk measures: standard deviation, VaR (silent on tail size, not subadditive), coherent
measures, expected shortfall, SPAN, EVT, Greeks, Sharpe, Sortino and information ratios. Practice: use a battery of
measures; scenario analysis complements VaR; re-estimating covariances daily can cause over-trading; VaR models are
back-tested by counting exceedances.

#### 23a. The option writer carries the jump (practical)
- What it says: in a market with jumps or stochastic volatility the seller cannot hedge the big move away; the premium
  above realised volatility is pay for bearing it.
- How we could use it: any short straddle or strangle arm needs a hard stop on the underlying or a wing, a scenario
  check (21a), and a tail measure (13b); its back-test must contain crash days.
- Signal source: volatility / options structure.
- Our research: R103 - 0 of 24 defined-risk selling tests confirmed; the naked short straddle earned on most days but
  had a -Rs 28,876 worst day on NIFTY, and the winged versions lost -Rs 3.0 / -Rs 3.8 lakh.

## Performance claims
- No trading-performance claims of its own. The empirical figures are illustrations:
  - ADF: 87% of 487 S&P 500 stocks look like random walks (2001-03).
  - Random-matrix: only a few eigenvalues of the S&P 500 correlation matrix stand above noise; 10 match the sectors.
  - Asset-class inputs flip between periods (emerging-market return 16.3% then 0.1%; correlation 0.45 then 0.73).
  - Credit example: VaR fell from 102.9 to 96.6 bp while expected shortfall rose from 240.3 to 256.2 bp.
  - A 50-stock optimised S&P 500 tracker had a tracking error of about 2.19%.
  - Yield-curve PCA (US 1984-88): level about 88%, slope about 8%, curvature about 2%.
- On technical rules the book cites academic work finding some forecastability with "profitability after costs unclear";
  no sample sizes are given.
- Book errors spotted: the CDS example prints the spread as 9.724 bp where the working gives about 97.24 bp; the Merton
  example's d1/d2 arithmetic is loose; the tracking-error annualisation is printed without the square root.

## Risk, sizing and psychology
- Expected shortfall over VaR; a battery of measures (worst day, ES, drawdown, Sortino) rather than one number.
- Scenario grids (SPAN, 16 scenarios) for option books; scenarios must include extremes and respect correlations.
- History under-samples extremes in fat-tailed markets; crashes are outliers even to power laws.
- Volatility drag: compounding makes the typical path worse than the average one; show wealth percentiles.
- Optimised weights and Kelly-type fractions are fragile; cap and shrink.
- Market-neutral books carry a maximum drawdown limit as one of four core rules.
- The doubling (martingale) betting system needs infinite capital.
- Risks add in variance; each further hedge buys less (diminishing returns).
- Psychology: clients weigh downside dollars more than upside; quant firms let model warnings override judgement.

## How it relates to our research
- **R16 / R88 / R41 (size predictable, direction not):** the book's stylized facts say the same. Our numbers: R88 225
  of 305 size findings held, 0 of 8 direction; R41 direction AUC 0.510-0.517.
- **R103 and DEEP_OPTIONS_SELLING.md:** the book's incomplete-market argument explains why the seller is paid and why
  the tail decides the result. R103 repriced stale wings with Black-Scholes at the leg's last IV (the book's replication
  model; it runs 1-5 IV points high on legs that printed); 0 of 24 confirmed. Add expected shortfall and a SPAN-style
  grid to future selling studies.
- **R102 (VIX over-states NIFTY's realised vol on 81% of days; skew; straddle decay clock):** consistent with option
  prices carrying a risk premium in an incomplete market; the book adds no new test.
- **R28 / R36 / R37 (GEX):** not covered by the book (dealer gamma is a market-structure idea); its Greeks chapter only
  defines gamma. R28: 0 of 10 GEX filters passed.
- **IV1 (option-market signals):** the book treats option prices as outputs of volatility, not as direction signals;
  IV1's option-only model lost on all 5 indices.
- **R41 (neural nets):** the book warns flexible models over-fit and says neural-net trading claims are unproven.
- **HUNT_H24 / baselines.yaml (costs):** the book's cost breakdown (spread, impact, timing, missed trades) matches; we
  already use real half-spreads.
- **R72 (cross-index, pairs-like):** cointegration and pairs theory here; the spurious-pair warning backs our BH
  correction.
- **H39 (heavyweights' stock options versus the index):** the book's lead-lag and futures price-discovery ideas; H39
  found no usable lead.
- **Kelly / sizing (H15, HUNT_FINAL, H17, H36):** error-maximisation and volatility-drag cautions agree with keeping
  fixed lots.
- **Monte Carlo / bootstrap (H1, H15, H16, H17, B2):** the book adds model-based simulation (Euler SDE, jumps, copulas)
  and wealth percentiles.
- **Volatility trading:** see research/books/bennett-trading-volatility.md.

## What is new to us
- Expected shortfall alongside worst day, and the example of VaR improving while the tail worsens.
- A SPAN-style scenario grid as a pre-trade risk check on paper option-selling arms.
- Random-matrix filtering before any many-contract factor or breadth model.
- The ensemble test for pairs: compare the count of cointegrated pairs with what independent random walks give.
- A hazard-rate (Poisson) model of gap days, correlated across instruments with a copula, for simulation.
- Kalman-filtered, time-varying hedge ratio for an index spread.
- The fundamental law as a reason to seek breadth (independent bets) rather than more direction trades.

## Could not read
- Charts and diagrams that were images: Exhibits 4.2, 4.4, 4.5, 4.8, 5.1, 6.1, 8.1, 8.2, 9.1-9.6, 12.1, 12.2, 12.4,
  13.1-13.3, 16.1-16.5, 16.9, 16.11, 16.13, 16.14, 16.16, 17.1, 18.2, 19.2-19.9, the chart in 20.3, 20.5 and
  22.1-22.11. Their captions and the surrounding text were read.
- Equations came through the text layer with broken layout; they were followed from the text but not checked symbol by
  symbol.

## R104 results (25 Oct 2026)

Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in `research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:

- `book-fmfabozzi-01`: tested (R104): DLY: 850 trades, win 39%, net Rs 1,317,966 design, holdout -Rs 505,997 (fail)
- `book-fmfabozzi-02`: tested (R104): DLY: 119 trades, win 15%, net Rs 2,592 design, holdout -Rs 535,781 (fail)
- `book-fmfabozzi-03`: tested (R104): IDX: 939 trades, win 30%, net -Rs 231,181 design, holdout -Rs 37,943 (fail)
