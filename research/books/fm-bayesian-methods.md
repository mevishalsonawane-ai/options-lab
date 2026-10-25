# Bayesian Methods in Finance - Svetlozar T. Rachev, John S. J. Hsu, Biliana S. Bagasheva, Frank J. Fabozzi (2008)

Source: bayesian-methods-in-finance.txt, 352 pages (Wiley, Frank J. Fabozzi Series), read in full on 25 Oct 2026
(text layer for every page; Chapters 1-14, the appendices, the reference list and the index; references and index were
skimmed, not summarised. Formulas came through with broken layout and charts only as axis numbers - see "Could not
read").
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A graduate-level textbook on Bayesian statistics applied to portfolio choice and market-risk models, US/European data.
Chapters 2-5 teach the toolkit: likelihood, priors, posteriors, predictive distributions, Monte Carlo and MCMC.
Chapters 6-9 apply it to estimation risk: Bayesian mean-variance, shrinkage, model averaging, Black-Litterman, predictability.
Chapters 10-12 cover volatility: GARCH, Student-t and regime-switching GARCH, stochastic volatility with jumps.
Chapters 13-14 cover fat tails, skewness, copulas, CVaR optimisation and multifactor risk decomposition by simulation.

## Methods, rules and setups

Chapter-by-chapter, in our words. The book has no intraday trading rules; it is a tool book. Its value for us is in how
to estimate things honestly from little data (arm win rates, P&L means, volatility) and how to state uncertainty.
Our data for the mapping: NIFTY/BANKNIFTY/FINNIFTY/MIDCPNIFTY/SENSEX spot and futures 1-min and 5-min bars, daily bars
since 2006, the nearest-expiry option chain with IV and OI, India VIX, MCX futures and options, crypto on Delta Exchange.
"Not tested by us" means no study in `research/` has fitted the model; `grep -ril garch` and `grep -ril bayes` over
`research/experiments/entries/` and the study code find nothing.

### Ch. 1 Introduction
Frequentist probability is long-run frequency; Bayesian probability is a degree of belief updated by data. The authors
give three reasons finance adopted Bayes: it combines sources of information, it carries estimation risk explicitly, and
MCMC makes realistic models estimable. They take the "pragmatic Bayesian" line: prior plus model, rarely a full utility.

### Ch. 2 The Bayesian paradigm
Likelihood, Bayes' theorem (discrete and continuous), model selection and classification by posterior probability.
Worked examples: corporate defaults as Poisson (sample mean 51.6 a year over 20 years); a stock screen that lifts a
takeover probability from 40% to 59%; the chance that two trade-by-trade price changes in a row are both up for AT&T
(55,668 records, 176 events, sample rate 0.316%; posteriors 0.318% under a flat prior and 0.319% under a beta prior).

#### Method 1. Beta-binomial estimate of a hit rate (Ch. 2-3)
- What it computes: the full distribution of an unknown success rate from k wins in n trials, starting from a beta
  prior; the posterior is beta(prior wins + k, prior losses + n - k). Posterior mean, credible interval, and the
  probability that the true rate exceeds any threshold.
- Inputs: wins, trials, and a prior chosen from a mean and one quantile (the book fixes a beta by "about 2% on average,
  30% chance below 1%").
- How we could use it: every arm and every Jarvis claim of the form "wins 62% of the time" could carry a posterior
  interval and P(win rate > breakeven win rate after Rs 70 costs). With large samples the prior washes out (the AT&T
  case); with 20-40 trades, as in many of our holdout cells, it does not.
- Signal source: none (a statistic of a trade log); maps to NSE, MCX and crypto equally.
- Our research: not tested by us as a method. Our studies report raw win rates with n and test differences with
  permutation / bootstrap p-values and BH (e.g. HUNT_R16_PREDICTABILITY.md, HUNT_H15.md).

### Ch. 3 Prior and posterior information, predictive inference
Informative priors (elicit a median and a quartile; for a monthly mean of about 1% with a 25% chance below 0.5% the
implied prior sd is about 0.74%), flat and Jeffreys priors (improper priors can give an improper posterior - the
Student-t degrees of freedom are the book's example), heavy-tailed priors for robustness, conjugate priors, and
empirical Bayes (hyperparameters taken from the data). The posterior mean of a normal mean is a precision-weighted
average of the prior mean and the sample mean: with n observations and a prior worth T "imaginary" observations, the
sample gets weight n/(n+T). Credible and highest-density intervals. Hypotheses are compared by their posterior
probabilities or the posterior odds ratio; the book stresses that a p-value is not the probability the null is true and
can overstate evidence. The predictive distribution averages the data distribution over the posterior, so it is wider
than the "plug-in" forecast that treats estimates as true. Illustration: S&P 500 monthly returns, 192 months vs 12
months; with 12 months the prior dominates the posterior.

#### Method 2. Shrinkage of a noisy mean toward a prior (Ch. 3, 4, 6)
- What it computes: posterior mean = w x sample mean + (1 - w) x prior mean, w = n/(n+T) (normal case); in regression
  the same, matrix-weighted toward prior coefficients.
- Inputs: the sample mean and its n; a prior mean (zero edge, a pooled mean across arms or instruments, or a value from
  an earlier "training" sample) and its strength T.
- How we could use it: report each arm's expected P&L per trade shrunk toward zero (or toward the pooled arm average)
  instead of the raw holdout mean; shrink per-instrument or per-time-slot estimates (e.g. R88 size multipliers, MCX
  per-commodity results) toward the pooled value before using them for sizing. Shrinkage toward zero is a risk-LOWERING
  correction, so it fits the LIVE rule.
- Signal source: none (estimator); all markets.
- Our research: not tested by us as a named method. The nearest is R16's max-pain test, which fitted a "shrink" weight
  of about 0.1 from the open toward max pain and found it not significant (HUNT_R16_PREDICTABILITY.md, section d), and
  H15's warning that an in-sample Kelly fraction (1.29 for BN) collapsed to 0.48 and 0.14 on other windows
  (HUNT_H15.md, verdict 5) - exactly the estimation error shrinkage is meant to damp.

#### Method 3. Posterior probability of an effect instead of a p-value (Ch. 3, 4, 9)
- What it computes: P(effect > 0 | data) or the posterior odds of "edge" vs "no edge", using a prior that says most
  ideas have no edge.
- How we could use it: as a second reading next to BH q-values, not a replacement. The book's own regression shows the
  danger: one factor's coefficient had about 50% posterior mass below zero under a flat prior but over 95% above zero
  under an informative prior - the prior, not the data, created the "finding". So any prior must be fixed in the
  pre-registration, before the design data are read.
- Signal source: none; all markets.
- Our research: we use frequentist gates (pre-registration, walk-forward, locked holdout read once, Benjamini-Hochberg
  across all ideas tried; CLAUDE.md "Research must be honest", FINDINGS.md "Method"). The book never mentions false-discovery control or multiple
  testing across many strategies; its Bayesian model averaging (Ch. 7) is the closest it gets.

### Ch. 4 Bayesian linear regression
Univariate and multivariate regression with flat and conjugate priors. Unknown variance makes coefficients Student-t,
fatter than the textbook normal. Priors are set from an earlier, non-overlapping sample (60 months 1990-94 to set the
prior, 132 months 1995-2005 to estimate) - in effect a walk-forward prior. Predictive distributions are Student-t.
Unequal variances across two groups or across a known structural break get separate variance parameters.

#### Method 4. Training-sample (presample) priors (Ch. 4, 6, 7, 11)
- What it computes: prior means and variances taken from data that end before the estimation window starts.
- How we could use it: the honest Bayesian version of our design/holdout split. A prior fitted on design years and
  updated on the walk-forward months never touches the locked holdout. The book also chose a prior mean of zero for
  returns because it distrusted an earlier boom period - the same instinct as our "assume no edge".
- Signal source: none; all markets.
- Our research: our walk-forward studies already refit on past data only (e.g. FINDINGS.md "Learned model, walk-forward
  by month"); priors as such are not used.

### Ch. 5 Bayesian numerical computation
Monte Carlo integration and its standard error (10,000 draws put the error at 1% of the posterior sd); rejection and
importance sampling; Metropolis-Hastings (random-walk proposals tuned in a pilot run to an acceptance rate near 0.5 in
one dimension and 0.23 in many, then frozen; independence proposals from a Student-t at the mode); block M-H and the
Gibbs sampler; drawing from the predictive by "draw parameters, then draw data". Convergence: burn-in (up to half the
chain if it is sticky), autocorrelation plots, a CUSUM of running means, and Gelman-Rubin parallel chains whose ratio
should be near 1. Draws that break a constraint can be thrown away, but many such rejections signal a wrong model.
Normal and Laplace approximations at the posterior mode, shown on a 46-firm default logit (21 defaults): the Laplace
version is the better one for small samples; one firm's posterior default probability comes out at 0.42.

#### Method 5. Simulation with parameter uncertainty (Ch. 5, 7 App. A, 9)
- What it computes: a forecast distribution that includes "we do not know the true mean and variance": draw (mean,
  sd) from their posterior, then draw an outcome, repeat.
- How we could use it: in our Monte Carlo of arm equity curves and drawdowns, resample the arm's parameters as well as
  its trades. H15's bootstrap of Kelly sizing resampled only 10 good month-blocks with a fixed in-sample Kelly fraction
  and "flattered" the aggressive setting (HUNT_H15.md, verdict 5); a parameter-uncertainty draw would have widened the
  drawdown odds. Barberis (Ch. 9) shows the same effect: ignoring parameter uncertainty makes a strategy look better
  the longer it is held.
- Signal source: none; all markets.
- Our research: block bootstrap is used widely (FINDINGS.md, H15, H17); posterior-draw simulation is not tested by us.

### Ch. 6 Bayesian framework for portfolio allocation
Mean-variance weights are hypersensitive to inputs; errors in means are 10 to 11 times more damaging than errors in
variances (Chopra-Ziemba). Alternatives: factor models, robust optimisation, resampled frontiers. With a flat prior the
predictive covariance is the sample covariance scaled up, more so with many assets and short histories; with a
conjugate prior the predictive mean is shrunk toward the prior (they use a prior weight of 200 against 1,671 daily
observations, about one-eighth). Raising one country's sample mean by 10% moved 22 classical weights by more than 30%
and none of the Bayesian ones. James-Stein shrinkage (Jorion's target: the minimum-variance portfolio's return) and
Ledoit-Wolf covariance shrinkage toward a one-factor covariance. Stambaugh's method for unequal histories: regress the
short series on the overlapping part of the long series and use the long history to correct the short series' mean and
covariance.

#### Method 6. Unequal-history estimation (Stambaugh) (Ch. 6)
- What it computes: a better mean and covariance for an instrument with a short history, borrowed through its
  regression on an instrument with a long history.
- Inputs: the long series (e.g. NIFTY daily since 2006), the short series, their overlap.
- How we could use it: SENSEX weekly options, MIDCPNIFTY, newly listed MCX mini contracts and crypto options all have
  short records; their volatility or range estimates could borrow NIFTY's long record through the beta. R88 found
  NIFTY's same candle explains 51-90% of the other indices' candles, so the borrowed information is large.
- Signal source: underlying price; maps to NSE indices and MCX (a gold mini on gold, for instance); crypto only to
  other crypto.
- Our research: not tested by us.

### Ch. 7 Prior beliefs and asset-pricing models
A model's validity becomes a prior on its mispricing (alpha centred at zero with an investor-chosen spread): zero
spread means full belief in the model, infinite spread means none. In the IBM example the stock's allocation rose from
0% to 1.29%, 26.6%, 86.5% and 101% as the spread went from 0 to 1%, 5%, 15% and unlimited. Model risk has two sources:
structure that is really noise (data mining) and a static model fitted to a changing process (the worse one). Bayesian
model averaging weights each model by its posterior probability from its marginal likelihood (which penalises extra
parameters automatically) and averages the forecasts; a prior over predictor subsets gives each candidate variable an
inclusion probability. CAPM vs Fama-French for IBM came out 98.9% vs 1.11%; 30,000 predictive draws gave a mean of
8.52% a year.

#### Method 7. Bayesian model averaging (Ch. 7)
- What it computes: one forecast that averages several candidate models by how well each explains the data after a
  complexity penalty.
- How we could use it: for volatility forecasts (time-of-day baseline, R16 linear, R41 network, a GARCH) average instead
  of picking the winner; for arm selection it is a soft alternative to a hard pass/fail gate. Weights must be computed
  on design data only.
- Signal source: none; all markets.
- Our research: not tested by us. R41 found a network beats trees on move size (holdout R2 0.432 vs 0.382) and R16 found
  a 4-input linear formula keeps most of the gain; an average of these was not tried.

### Ch. 8 The Black-Litterman framework
Equilibrium returns implied by market weights are the prior; investor views (absolute or relative, as view portfolios)
with stated confidence are the data; the posterior is a precision-weighted blend, and with no views you hold the market.
The scale of prior uncertainty has no agreed value (near 0, 1, or 1/T; 10 years gives 0.1) or can be calibrated from
rolling 250-day residuals. View confidence can come from an interval (a 6% view held at 95% between 5% and 7% gives an
sd of 0.5%) or from a backtest: the variance of the gap between a strategy's predicted and realised returns. Views spread
through correlations to every asset. A trading strategy can be a view: cross-sectional momentum on MSCI country indices
(rank on the 9-month return, about 189 days, skip the last day, scaled by volatility; long the top half, short the
bottom half, weights inverse to volatility, scaled to 20% annual volatility, held one month; 1980-2004). For active
managers the view is on alpha, and Grinold's law ties the information ratio to skill and breadth: IR is about IC times
the square root of the number of independent bets. Covariances from exponentially weighted returns with a half-life;
variance errors matter about twice as much as covariance errors.

#### Method 8. Cross-sectional momentum (Ch. 8; rule slug xs-momentum-9m-vol-scaled)
- Entry: at each month start, rank the instruments on their 9-month (about 189 trading days) return ending one day
  earlier, divided by their volatility; long the top half, short the bottom half.
- Stop: not stated. Target / exit: hold one month, then re-rank.
- Filters: none; weights inverse to each instrument's volatility, total scaled to a 20% annual volatility target.
- Market and timeframe written for: MSCI country indices, daily data, monthly rebalance.
- Signal source: underlying price; maps to NSE only weakly (5 indices are too few and too correlated - R88's 51-90%);
  MCX (GOLD, SILVER, CRUDE, NATURALGAS, plus base metals) is the better cross-section; crypto (BTC, ETH and others) with
  a stop and profit lock per CLAUDE.md.
- Mechanical? yes.
- Our research: not tested by us. R72 (cross-index breadth Donchian) is the nearest live idea and it is a time-series,
  not a cross-sectional, rule.

#### Method 9. Exponentially weighted variance (Ch. 8)
- What it computes: a variance that weights recent squared returns more, with a half-life (the book's daily example
  puts the decay near 0.98 per day so data three months old count half); choose the decay by maximum likelihood.
- How we could use it: as the plain baseline any GARCH must beat.
- Signal source: underlying price; all markets.
- Our research: R16 and R88 found "recent range / last size + recent volatility" carries most of the predictable part of
  the next 30-60 minutes' range (HUNT_R16_PREDICTABILITY.md section a; FINDINGS.md R88).

#### Method 10. Turning backtest error into view confidence (Ch. 8)
- What it computes: a strategy's forecast reliability as the variance of (forecast return - realised return) in a
  rolling backtest; plus Grinold's IR = IC x sqrt(breadth).
- How we could use it: Jarvis could weight arms' "views" (e.g. when two arms disagree) by their historical forecast
  error; breadth explains why many small independent bets beat a few big ones.
- Signal source: none; all markets. Our research: not tested by us.

### Ch. 9 Market efficiency and return predictability
Efficiency tests are joint tests with a pricing model. The book opens with the right question for us: a strategy
projected at 1.5% after costs - how much should we trust it, given the coefficients are estimates? Bayes gives the
posterior distribution of an "inefficiency" measure (the Sharpe gap between a benchmark and the efficient frontier) by
redrawing means and covariances and recomputing the frontier (1,000 draws; short-sale limits make the loss bigger). APT
efficiency is priced as a certainty-equivalent return gap (about 1% a year at high risk aversion, about 8% at low).
Predictability of returns by dividend yield is set up as a two-equation VAR with correlated shocks; the predictive
distribution of the cumulative return over a horizon is simulated. Barberis: ignoring parameter uncertainty, stock
allocation rises with horizon; including it, allocation falls at long horizons because uncertainty compounds.

#### Method 11. Posterior distribution of a strategy's edge (Ch. 9)
- What it computes: instead of "Sharpe = 1.2, p = 0.03", the distribution of the true Sharpe (or of P&L per trade)
  given the sample, from which P(edge > costs) follows.
- How we could use it: a natural companion to our holdout read: "P(true mean > 0) = 0.71 on 223 trades" is easier for
  Boss than a q-value. Pre-register the prior.
- Signal source: none; all markets. Our research: not tested by us.

### Ch. 10 Volatility models: overview
Volatility is the forward-looking conditional sd; returns show clustering, fat tails and a bigger volatility response to
falls (leverage). GARCH(1,1) makes today's variance a weighted mix of the long-run variance, yesterday's forecast and
yesterday's squared shock; the sum of the two dynamic weights is the persistence and, below 1, sets how fast volatility
reverts. Extensions: an extra term for negative shocks (GJR), EGARCH, an ARMA mean, GARCH-in-mean. Getting the mean
wrong matters little next to getting tails and asymmetry wrong. Conditional normality is rejected at 99% for over 80% of
382 S&P 500 stocks, against only 6% for a stable law; Student-t residuals beat normal ones almost uniformly; an
absolute-value GARCH fits better under fat tails. Stochastic volatility (SV) gives log-variance its own random shock, so
volatility is latent; the likelihood is a T-dimensional integral, so method-of-moments and quasi-ML estimates are
inefficient and simulation (EMM, which matches a GARCH auxiliary model's scores, or MCMC) is preferred. VaR from a
volatility model: the 5% quantile is the mean minus 1.645 sd (normal) or a Student-t quantile; on MSCI Canada daily
returns the 95% VaR was breached on 3.4% of days instead of 5%. On exchange rates SV forecast better than normal GARCH
around volatility jumps (Geweke), and SV matches the autocorrelation of squared returns better; a fat-tailed GARCH may
close the gap.

#### Method 12. GARCH(1,1) with Student-t shocks (Ch. 10-11)
- What it computes: a one-step (or h-step) variance forecast and its persistence; for us, the expected size of the next
  5-minute, hourly or daily move and a VaR.
- Inputs: a return series (5-minute or daily; for intraday use, returns divided by the time-of-day volatility profile
  first, since our day is front-loaded), a presample variance to start the recursion.
- How we could use it: size stops, targets and lots by forecast volatility (risk-lowering, LIVE-compatible after a
  shadow phase); MCX and crypto, which trade nearly round the clock with fewer gaps, suit GARCH better than the 6-hour
  NSE day. Check calibration with VaR breach counts (the book's 3.4% vs 5%).
- Signal source: underlying price; NSE index options yes (as a size input, not direction), MCX yes, crypto yes (with
  stop and lock).
- Our research: GARCH not tested by us. Volatility clustering itself is confirmed: R16 found the next 30/60 minutes'
  range predictable on both indices, holdout error 15-20% below the time-of-day baseline (q 0.003), and the next-day
  range no better than VIX in the holdout; R88 found the next candle's size predictable on indices and MCX (225 of 305
  hold). Any GARCH must beat these baselines, and VIX for daily horizons. Volatility estimators are covered in
  research/books/bennett-trading-volatility.md method 26 (not repeated here).

### Ch. 11 Bayesian estimation of ARCH-type models
Bayes makes positivity and stationarity constraints easy (reject violating draws) and gives the posterior of the
persistence itself. The Student-t degrees of freedom need a proper prior (a flat one gives an improper posterior): the
book uses an exponential prior with mean 5; a uniform prior to 20 or a half-Cauchy also work, and daily returns rarely
show more than 20. The t is written as a scale mixture of normals so most blocks are Gibbs steps; the GARCH weights get
an M-H step and the degrees of freedom a "griddy Gibbs" step (evaluate the density on a grid, invert the CDF; a grid of
2-30 covers returns). MSCI Canada, 1,901 days Dec 1994 - Mar 2002, 10,000 iterations with 5,000 kept: persistence
0.965, degrees of freedom 9.24; little posterior mass on non-stationarity. Out of sample (467 days to Dec 2003) the
forecasts track volatility but over-predict some spikes and under-predict others, which the book reads as regime
change. Markov-switching GARCH (Henneke-Rachev-Fabozzi): three volatility states, each with its own GARCH weights;
the hidden state path is sampled day by day inside the MCMC (classical ML cannot handle the GARCH term with regimes);
Dirichlet priors on the transition rows. Result: a calm state that persists (stay probability 0.988), a medium state
(0.938), and a short-lived high-volatility state (stay 0.181) that switches on with shocks and is explosive while it
lasts (persistence 1.386); the fitted degrees of freedom rise to 30.07, i.e. the regimes absorb the fat tails.

#### Method 13. Regime-switching volatility state (Ch. 11)
- What it computes: the daily probability of being in a calm, normal or stressed volatility state, with each state's
  own dynamics.
- How we could use it: a risk-lowering size switch (smaller lots or no new short-premium trades in the stressed state),
  started in shadow per CLAUDE.md. A single-regime GARCH over-forecasts in calm periods and under-forecasts in storms,
  which matters for option sellers (R103) and for stop distances.
- Signal source: underlying price; NSE yes as a filter, MCX yes, crypto yes.
- Mechanical? yes once fitted, but the fit must be walk-forward: the filtered state probability (data to t) is
  tradable; the smoothed one (Ch. 12) is not.
- Our research: not tested by us. Nearest: R16 ("days like today" analog regimes forecast worse than pooled days) and
  R102's VIX buckets (low-VIX median NIFTY range 0.75% vs 1.72% above VIX 20).

### Ch. 12 Bayesian estimation of stochastic volatility
The basic SV model and two MCMC samplers. Single-move (Jacquier-Polson-Rossi): each day's latent volatility is drawn
in turn with an inverted-gamma M-H proposal. Weekly NYSE and size portfolios 1962-91: volatility persistence about 0.91-0.95,
small caps the most variable; they strip autoregressive and monthly seasonal effects first. The single-move draws are
highly autocorrelated, so Kim-Shephard-Chib transform to log squared returns, approximate the log chi-square noise by a
seven-component normal mixture, and draw the whole volatility path at once with a Kalman filter and simulation
smoother. A beta prior on the persistence keeps it inside (-1, 1). Jumps: a return jump is a one-off shock (S&P daily
about 0.0137 jumps a day, three to four a year); a volatility jump persists, and with both in the model return jumps
become rarer and explain crashes. Forecasts: for each MCMC draw, step the log-variance forward with a fresh shock to
get a predictive distribution of volatility and return. Appendix: the Kalman filter (predict, compute the prediction
error, update by the gain) and the backward smoother.

#### Method 14. Filtered vs smoothed volatility (Ch. 12 and appendix)
- What it computes: the filtered estimate uses data up to t; the smoothed estimate also uses data after t and is more
  accurate. The book's chart shows filtered volatility reacting to bumps later than smoothed.
- How we could use it: a look-ahead rule for our features: any Kalman, SV or regime model used as a signal or filter in
  a backtest must use filtered (or one-step predicted) values only. Smoothed values are fine for describing history.
- Signal source: underlying price; all markets.
- Our research: the look-ahead guard in research/brain enforces the general rule; not a separate test.

#### Method 15. Jumps in returns vs jumps in volatility (Ch. 12)
- What it computes: a jump probability and size distribution in returns, and/or jumps in the volatility process.
- How we could use it: gap and event risk for short-premium structures and overnight holds; a forecast that has
  volatility jumps predicts that after a shock the market stays wild, not just one bad day.
- Signal source: underlying price; NSE yes (gaps: R102 found gaps of 0.5% or more on 20% of NIFTY days), MCX yes,
  crypto yes.
- Our research: not tested by us as a model; R102 and R103 describe gap and fat-tail behaviour.

### Ch. 13 Advanced techniques for Bayesian portfolio selection
Black Monday 1987 (the Dow down 22% in a day) motivates fat-tailed, skewed models: normal mixtures, skewed Student-t,
stable laws (tail index between 0 and 2; Gaussian at 2), the three extreme-value types, skew-normal. Joint behaviour
either as one multivariate law or as marginals glued by a copula; dependence is stronger in falls. Two ways to choose
portfolios under non-normality: a utility with a skewness term (Harvey-Liechty-Liechty-Muller; four tech stocks 1996-2002:
with no risk or skew aversion the optimiser puts 99% in one stock, a skew preference spreads it), or minimise a
downside risk measure. Meucci's copula opinion pooling: the posterior view distribution is a confidence-weighted
average of the manager's view and the market-implied one, the market's dependence structure is laid back on top through
a copula, and the result feeds a CVaR optimiser (a DAX view of 0% to -2% for the week cut its weight to zero).
Appendices: risk measures (lower partial moments, MiniMax, VaR, CVaR), Rockafellar-Uryasev CVaR optimisation as a
linear programme over scenarios, and copula scenario generation with a Student-t copula.

#### Method 16. CVaR (expected shortfall) as the risk number (Ch. 13 App. A-B, Ch. 14)
- What it computes: the average loss on the worst alpha share of days or scenarios. Unlike VaR it is subadditive (no
  penalty for diversifying) and convex, so it can be minimised; with M scenarios the minimisation is a linear programme.
  Matching performance ratios: Sortino (return over a lower partial moment) and STARR (return over CVaR).
- Inputs: a daily P&L series or simulated scenarios; alpha (5% in the book's examples).
- How we could use it: report each arm's and the account's 5% CVaR next to max drawdown; check the account stop
  (-Rs 40k drawdown, -Rs 13k a month) against simulated CVaR; size arms so combined CVaR stays inside the stop.
- Signal source: none (P&L statistic); all markets.
- Our research: not tested by us as a target; our studies report worst day, worst month and drawdown.

#### Method 17. Student-t copula scenarios (Ch. 13 App. C)
- What it computes: joint scenarios that keep each instrument's own fat-tailed distribution and their tail dependence.
- How we could use it: stress the combined book (NIFTY arm + BANKNIFTY arm + MCX) for days when everything moves
  together; R88 shows NIFTY's candle explains 51-90% of the other indices', so the arms are not independent bets.
- Signal source: underlying price / P&L; all markets. Our research: not tested by us.

#### Method 18. Skewness preference (Ch. 13)
- What it computes: a utility that rewards positive skew; coskewness between assets.
- How we could use it: option buying has positive skew and option selling negative; a skew term explains why a buyer
  may accept a lower mean and why R103's naked straddle "positive but with fat tails" is not a free lunch.
- Signal source: volatility / options structure; NSE yes. Our research: not tested by us.

### Ch. 14 Multifactor equity risk models
Statistical (PCA: three to five factors explain about 90%), macroeconomic and fundamental factor models; in one study
fundamental beat statistical, which beat macro. A factor structure cuts the covariance problem from 500,500 numbers to
1,091 for 1,000 stocks and 13 factors. Risk is split into factor and specific parts, with marginal contributions per
stock and per factor; specific variances can be shrunk toward their average (Litterman). Scenario generation: simulate
factor returns (a low-order multivariate AR) and specific returns (a volatility model), rebuild stock returns, compute
the 5% CVaR from the sorted scenarios and split it by averaging each driver over the tail scenarios. The Bayesian
version runs a posterior per cross-section; the book admits Gaussian, variance-based managers may be better off with
the simple version. Illustration: 229 S&P 500 stocks, 751 days 2001-03, six style and ten industry factors, stable
residuals with a uniform (1, 2) prior on the tail index.

#### Method 19. Tail-risk attribution by simulation (Ch. 14)
- What it computes: how much of the 5% CVaR comes from each arm or each common factor (e.g. NIFTY's move).
- How we could use it: show Boss which arm drives the bad days; with arms on correlated indices most of the tail will
  be the common NIFTY factor.
- Signal source: none (P&L attribution); all markets. Our research: not tested by us.

## Performance claims
- Bayesian (shrunk) portfolios are more stable: one input changed by 10% moved 22 classical weights by more than 30%
  vs none of the Bayesian ones (Ch. 6, one MSCI illustration, 1,671 days). Studies cited, not shown, claim better
  out-of-sample performance from shrinkage.
- Cross-sectional momentum: the optimised strategy's growth chart beats the MSCI World index 1980-2004 (Ch. 8); no
  costs, no statistics, chart only.
- Predictability: past returns explain up to 40% of 2-10-year returns and valuation ratios about 25% of 2-5-year
  returns (Ch. 9, literature review, no evidence shown here).
- Volatility: Student-t GARCH tracked MSCI Canada volatility out of sample over 467 days but missed some spikes; 95% VaR
  breached 3.4% of days (Ch. 10-11, one series). SV beat normal GARCH around volatility jumps (cited study).
- None of the claims include trading costs, and none is a trading-rule backtest.

## Risk, sizing and psychology
The book's whole theme is estimation risk: treat estimates as estimates, widen forecasts for what you do not know,
shrink extreme numbers, and prefer the predictive distribution to the plug-in. It favours downside measures (CVaR, lower
partial moments) over the standard deviation and warns VaR can punish diversification. Model risk is named as the bigger
danger than parameter risk, especially a static model on a changing market. Nothing on psychology, stops or position
limits beyond portfolio constraints.

## How it relates to our research
- Agrees: volatility clusters and is forecastable (R16 q 0.003, R88 225 of 305; R41 networks help on size), while
  direction is not; the book's GARCH/SV toolkit is a size tool, which is how CLAUDE.md and R89 say to use size forecasts
  (sizing only; R89's size straddle lost on 0 of 18 versions positive).
- Agrees: fat tails and gap risk (R102 gaps; R103 naked straddle tails) - the book's case for Student-t, jumps and CVaR.
- Agrees: estimation error in means dominates (Ch. 6) - H15's unstable Kelly fraction (1.29 in-sample vs 0.48 and 0.14
  elsewhere) is the same lesson; fixed lots (H17, HUNT_FINAL) are the conservative answer.
- Complements: our practice is frequentist (pre-registration, walk-forward, locked holdout, BH across ideas, real
  costs). The book supplies shrinkage, posterior intervals and model averaging but says nothing about testing many
  strategies at once, data snooping or costs; it does not replace BH.
- Warns: smoothed latent-state estimates and full-sample regime fits are look-ahead if used as signals (Method 14).
- Not tested by us: GARCH, SV, regime-switching volatility, Black-Litterman, copulas, CVaR sizing, cross-sectional
  momentum, Bayesian shrinkage of arm statistics.

## What is new to us
- Beta-binomial posterior and P(win rate > breakeven) for every arm statistic Jarvis quotes (Method 1).
- Shrinking arm, instrument and time-slot estimates toward zero or the pooled value before sizing (Method 2).
- Simulating drawdowns with parameter uncertainty, not just resampled trades (Method 5).
- Borrowing NIFTY's long history for short-history instruments (Method 6).
- Student-t GARCH and Markov-switching GARCH as volatility baselines to compare with R16/R88; MCX and crypto first (Methods 12-13).
- Filtered-only rule for any latent-state model (Method 14).
- CVaR and tail attribution for the account stop (Methods 16, 19).
- Cross-sectional momentum on MCX commodities (Method 8).

## Could not read
- Formulas: pdftotext broke most displayed equations (subscripts, Greek letters, matrices lost their layout); the prose
  around them was read and is what this write-up uses.
- Charts (Exhibits 2.1-2.5, 3.1, 4.2, 5.1, 5.3-5.4, 5.6, 6.1, 6.4, 8.3-8.6, 9.1-9.2, 10.1, 11.1, 11.3-11.4, 11.6, 12.2-12.3,
  13.2, 14.1-14.2) came through only as axis numbers and captions. Tables (e.g. 4.1, 5.2, 6.2-6.5, 7.1, 8.1-8.2, 11.2,
  11.5, 12.1, 13.1, 13.3) were readable.
- References and index were skimmed only. Footnote 13 of Ch. 14 is a leftover editorial question, not content.
