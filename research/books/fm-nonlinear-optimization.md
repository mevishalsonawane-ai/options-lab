# Nonlinear Optimization with Financial Applications - Michael Bartholomew-Biggs (2005)

Source: nonlinear-optimization-with-financial-applications.txt, 276 pages, read in full on 25 Oct 2026 (text layer,
pdftotext; all 23 chapters, the appendix, references and index). Most displayed formulas and most numeric tables came
through empty or garbled, and every figure is an image - see "Could not read".
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A university textbook on numerical methods for continuous nonlinear optimisation, from one-variable search to SQP and interior point methods.
The finance theme is Markowitz mean-variance portfolio selection: least risk for a target return, most return for a risk budget.
It adds no-short-selling and per-asset bounds, transaction costs, rebalancing, downside and worst-case risk, and buy-in thresholds.
A smaller theme is time series: least-squares trend lines, a simple autoregressive fit, minimax fits and support / resistance trend channels.
Every method is run on tiny tables (3-10 periods) and a 5-10 share London Stock Exchange sample (2002-03), using the author's Fortran package SAMPO.

## How to read this for IraAlgo
This is a methods book, not a trading book. It has no option pricing, no Greeks, no volatility models and no statistics
of strategy performance; for option and volatility material see `research/books/bennett-trading-volatility.md`. What
matters for us is (a) what the portfolio chapters say about how unstable "optimal" weights estimated from history are,
(b) how flat the optimum usually is, (c) local versus global optima when a search is started from one point, and (d) the
cost-aware rebalancing and trend-channel ideas. The book never discusses over-fitting, out-of-sample testing, multiple
testing or significance; where it touches those themes it does so indirectly, and we say so below.

## Methods, rules and setups (chapter by chapter)

### Ch. 1 Portfolio optimisation
Builds the mean-variance model from a history of returns: mean return per asset, the variance-covariance matrix, portfolio
return as the weighted mean and portfolio risk as the weighted variance. Four problems: least risk (weights sum to one),
a blend of return minus a multiple of risk (the multiple sets the trade-off; very large multiples give the least-risk
answer), least risk for a target return, and most return for a risk budget. Negative weights mean short selling and are
less likely when the target return sits well inside the range of the assets' own means. The least risk for each target
traces a parabola, the efficient frontier; the weights move linearly with the target. A lower target does not always mean
lower risk (covariances can make it worse). Least variance is the same as a least-squares fit of "same return in every
period". The worked data are 6 monthly returns of 3 assets, so every number here is a toy.

#### 1a. Mean-variance weights (Ch. 1, 3, 13)
- What it computes: the weights across n assets that give the least variance of the combined return for a chosen mean
  return (or the most mean for a chosen variance). Closed forms exist for the plain least-risk problem (weights
  proportional to the inverse covariance times a vector of ones, then scaled to sum to one) and for least risk at a
  target return.
- Inputs: a table of past returns per asset per period. Nothing else.
- How we could use it: our "assets" would be our arms' daily P&L series (Liquidity 15+5, Breadth Donchian, R51 Donchian,
  R52 OOV) or the five indices. It would say how many lots each arm should carry so the account's daily P&L varies
  least for the same expected P&L. It is a sizing rule, not a signal.
- Signal source: none (sizing / allocation; the arms keep their own underlying signals). Maps to: NSE index option arms
  yes (as lot allocation across arms); MCX yes in principle; crypto not relevant (no crypto arm).
- Mechanical? yes.
- Our research: not tested as such. Nearest: R84 (`HUNT_R84_BREADTH_RISK.md`) held fewer of one signal's correlated
  positions: the worst day fell 32-77% but none beat a random subset of the same size (best p 0.13, BH q 0.91+). R72 found
  Breadth Donchian's trades are 77% Donchian's own (daily correlation 0.80) and said to replace, not run beside, the
  Donchian arm - a covariance argument made by judgement, not by optimisation. R101 found 2 lots on 5-of-5 breadth earned
  what any extra lot earns (Rs 161 a lot vs random 160, p 0.475). See rule `min-variance-arm-lots`.

### Ch. 2 One-variable optimisation
Optimality conditions; bisection (halving a bracket that holds one minimum), bracketing, the secant method, Newton's
method (very fast near the answer but can overshoot or head for a maximum, so a safeguarded version is given), and
quadratic / cubic interpolation. Practical lesson: the most-return problem can have two local optima, and which one a
method finds depends on its starting point (Newton started at one end found a better answer than bisection and secant).
Pure numerics otherwise.

### Ch. 3 Optimal portfolios with n assets
Removes one weight using the sum-to-one rule and writes gradients and second-derivative matrices of each portfolio
problem. An exercise asks the reader why some "utility" objectives might be unsatisfactory in practice; the book does
not answer it. Pure algebra.

### Ch. 4 Unconstrained optimisation in n variables
Gradient, Hessian, positive definiteness, convexity (a convex function has one minimum; a non-convex one can have
several stationary points - the most-return objective becomes non-convex as the risk budget grows). Direct search methods
that need no derivatives: grid search (called inefficient), random sampling, one-variable-at-a-time search, Hooke-Jeeves,
Nelder-Mead simplex, finite-difference gradients. Introduces the SAMPO test problems: real London data (Real-m-n = m days,
n shares) and synthetic data made by randomly perturbing it. Notes that real data give negative expected returns and much
larger covariances than the toy tables.

#### 4a. Derivative-free search of a parameter space (Ch. 4, 23)
- What it computes: the best setting of a few continuous parameters when only the objective's value can be computed
  (no gradient) - exactly our situation when a backtest's net P&L is the objective and parameters are a stop, a lookback
  or a threshold.
- Inputs: an objective function and a box of allowed values.
- How we could use it: our sweeps (R30, R47, R48) are grid or exhaustive searches. The book's point is that grids waste
  evaluations; Nelder-Mead or DIRECT find a good region in far fewer runs. But a faster search finds an over-fitted peak
  faster too; it does not change the multiple-testing problem.
- Signal source: not a signal (search method). Maps to: any study. Mechanical? yes.
- Our research: R48 scanned 6,651,600 configs, 32,879 passed every design gate and 0 of 10 passed the holdout; R47 tried
  387,392 variants, none beat random entries after BH. Search speed was never our bottleneck; over-fitting was.

### Ch. 5 Steepest descent
Move downhill along the negative gradient with a line search; Wolfe conditions for an acceptable step; the Armijo
search. Converges, but slowly (linearly) on long narrow valleys: on one example the error shrinks by about 0.82 per step,
about 60 steps to gain five decimal places. On the portfolio test problems it needs hundreds to thousands of iterations
and fails one case after 5000. Useful aside: the larger the penalty weight used to force a target, the harder the
problem becomes. Pure numerics.

### Ch. 6 Newton's method
Uses a local quadratic model; one step solves a quadratic problem exactly (the least-risk problem is quadratic). Cholesky
factorisation tests positive definiteness; when the Hessian is not positive definite the method needs a fall-back
(modified Cholesky, a diagonal boost, or a trust region that limits the step). Rounding error limits the accuracy reached.
Pure numerics.

### Ch. 7 Quasi-Newton methods
Builds up an approximate (inverse) Hessian from gradient changes: DFP, BFGS (preferred in practice), and the symmetric
rank-one update. Faster than steepest descent, nearly as fast as Newton, and cheaper per step on large problems. Pure
numerics.

### Ch. 8 Conjugate gradients
Search directions that do not undo each other; no matrices are stored, so memory is small; ends in n steps on a quadratic
in exact arithmetic but is sensitive to rounding and to inaccurate line searches; truncated Newton combines it with
Newton steps. Pure numerics.

### Ch. 9 Portfolios with restrictions
No short selling by writing each weight as a square; upper and lower limits (for example at most 25% in one asset) by a
bounded transform or a penalty. Scaling matters: a return constraint must be divided by the target, or the sum-to-one
rule dominates. Banning short sales roughly doubled the least risk on one toy example (0.0018 to 0.0038) and on another
dropped three of five assets and cut the best return from 1.19% to 1.16%. The book warns it is easy to impose
restrictions that leave no feasible answer (a target return above every asset's mean).

### Ch. 10 Larger-scale portfolios (the most practical chapter)
First half: speed of the methods as the number of assets grows to 200 on synthetic data (quasi-Newton is fastest in run
time; the least-risk optimum is "flat", so methods stop early). Second half uses real data for 5 shares:
- Time-variation: the optimal weights computed from a history of m days, for m from 50 to 80, stay roughly constant for
  about ten more days and then shift substantially (one share's weight falls sharply, another rises); risk at the optimum
  jumps after day 70. The book reads this as a case for periodic rebalancing, but only when the change is large, because
  each trade costs money.
- Out-of-sample check: the least-risk portfolio fixed on day 50 is held for days 51-100. Its running mean return wanders
  around the 0.1% daily target, mostly above it, but loses money for a stretch (roughly days 62-72). The book says even
  optimal portfolios can lose in the short and long term, and that if the assets are doing badly now, optimising on their
  past will not prevent losses.
- This is a single path of one 5-share portfolio; no sample size, no significance and no comparison with equal weights.

#### 10a. Rolling re-estimation and rebalance-only-on-big-change (Ch. 10, 16, 22)
- What it computes: re-run the optimiser as new data arrive; act only when the new weights differ enough to pay for the
  trades.
- Inputs: a rolling return window, a cost per unit traded, a threshold for "enough change".
- How we could use it: the same logic applies to any parameter we re-fit on rolling windows (lot sizes, Kelly fractions,
  model weights). Estimates drift; changes should cross a cost-aware threshold before the app acts.
- Signal source: none (re-estimation / sizing policy). Maps to: NSE / MCX arms as a sizing policy; crypto not relevant.
- Mechanical? partly (the book gives no threshold).
- Our research: H15 (`HUNT_H15.md`) found the Kelly fraction for BANKNIFTY was 0.48 on Jun 2023 to Nov 2024 and 0.14 on
  2020 to May 2023, against 1.29 on the window used, and flagged its own bootstrap as optimistic for that reason - the
  same instability the book shows for portfolio weights.

### Ch. 11 Data fitting and the Gauss-Newton method
Least squares, plus L1 and minimax (worst error) fits, which are non-smooth. Gauss-Newton exploits the sum-of-squares
shape; Levenberg-Marquardt handles a singular case. Time-series example on 10 closing prices of one share: a straight
least-squares trend line, then a 2-lag autoregressive model of the de-trended residuals, then all four coefficients fitted
together. The in-sample squared error falls from 0.0517 (line) to about 0.04 (two-stage) to about 0.013 (one-stage), about
a third. All of this is in-sample on 10 points with 4 parameters; an exercise asks the reader to extrapolate to day 11 and
day 15 and compare with the real prices, but the book never reports how the extrapolation did. The least-risk problem can
itself be written as least squares (Gauss-Newton then solves it in one step, but stalls when a weight sits at zero).

#### 11a. Trend line plus autoregressive residual model (Ch. 11)
- Entry / stop / exit: none; it is a forecasting fit, not a rule.
- Market and timeframe written for: daily closes of one London share.
- Signal source: underlying price (the share's own closes). Maps to: NSE index spot / futures closes, MCX futures closes,
  crypto closes - as a forecast of the next value.
- Mechanical? yes, as a fit; no trading rule is given.
- Our research: autoregressive / direction forecasts on our indices have been coin flips (R16, R88: size predictable,
  direction not; F1: 0 of 20 rules). The book's 4-parameter fit on 10 points is a textbook case of in-sample error falling
  as parameters are added, which is what our R30 C2 combinations showed (+Rs 300-520 a trade on training years,
  Rs -111 out of sample).

### Ch. 12 Equality-constrained optimisation
Constrained forms of the least-risk and most-return problems; feasibility; the Karush-Kuhn-Tucker conditions; Lagrange
multipliers. Practical point: a multiplier measures how much the optimum changes per unit shift in a constraint, so the
least-risk problem's multiplier on the return target says how much extra risk each extra unit of target return costs,
and the biggest multipliers show which constraints bind hardest. Constraints should be scaled so they shrink at similar
rates.

#### 12a. Constraint sensitivity via Lagrange multipliers (Ch. 12, 13)
- What it computes: the first-order change in the optimum (least risk) when a constraint (target return, a cap, a budget)
  is moved a little.
- How we could use it: when Boss asks "what does tightening the stop / lowering the daily cap cost?", the honest answer is a
  marginal-cost number, not a re-run of every setting. On one example the multiplier predicted the risk at a 1.16% target
  from the 1.15% solution; the actual was 0.00426, "a reasonable guide" but not exact.
- Signal source: none. Mechanical? yes. Our research: not tested by us.

### Ch. 13 Linear equality constraints
Equality-constrained quadratic programs solved directly from the optimality equations; reduced-gradient (work inside the
space the constraints allow) and projected-gradient methods; Excel SOLVER's GRG method. Noted: the penalty form of the
least-risk problem gives a slightly lower risk (3.409e-3) than the exact form (3.446e-3) only because it quietly misses the
return target. Pure numerics otherwise.

### Ch. 14 Penalty function methods
Replace constraints by a penalty for violating them and solve a sequence of easier problems (SUMT). Starting with a very
strong penalty is a mistake: the problem becomes badly conditioned and the method stops at a feasible but non-optimal
point. The augmented Lagrangian avoids that by also estimating the multipliers. Exact penalty functions solve in one go but
are non-smooth or need higher derivatives. GRG crawls along curved constraint boundaries and can stop short of the optimum
(on one example it stopped at 1.265% instead of the true maximum). Pure numerics.

### Ch. 15 Sequential quadratic programming
Solve a quadratic model with linearised constraints at each step; Wilson-Han-Powell and the augmented-Lagrangian version
(the author's own); the Maratos effect (a merit function rejecting good steps near the answer); filter methods that accept
a step if it improves either the objective or the constraint violation compared with every point kept so far (a Pareto
idea). Fastest of the constrained methods on the book's tests. Pure numerics.

### Ch. 16 Further portfolio problems
- Transaction costs proportional to the amount bought: holdings shrink by the cost factor; risk must be measured relative to
  what is actually invested, or a tiny investment looks safe. On 5 real shares, costs from 0% to 5% barely moved the
  weights; raising the cost on the one asset most needed for low risk made the optimiser buy MORE of it (0.363 to 0.375),
  so the fraction actually held stays big enough. Counter-intuitive, and a warning against reasoning about costs by
  intuition.
- Minimum-change rebalancing: after 45 more days the old portfolio's expected return had fallen from 0.1% to about 0.082%
  and its risk had risen to about 0.84; the smallest change of weights that restores the 0.1% target at risk 0.8 is small
  (a squared change of about 0.0034), and a full re-optimisation (risk about 0.779) would change far more.
- Sensitivity ("room for manoeuvre"): find the largest change of weights that keeps return within 99% and risk within 101%
  of the optimum, or within 95% / 105%. With the looser margin three weights could each move by 10-20% while performance
  stayed within about 5% of optimal; the other two had little room. Useful when weights must be rounded to lots.

#### 16a. Flat-optimum check: how far can the settings move before results suffer? (Ch. 16, 10)
- What it computes: the size of the region around a chosen optimum inside which performance stays near optimal.
- How we could use it: this is the portfolio version of a parameter-plateau check. A chosen setting whose neighbours do much
  worse is fragile; a setting inside a wide plateau is more believable. It also means precise "optimal" weights are not
  well determined by the data: many quite different weightings perform almost the same.
- Signal source: none (robustness check). Mechanical? yes. Maps to: every sweep we run.
- Our research: not a formal step in our gates. Our gates rely on walk-forward, a locked holdout, random twins and BH
  (R30, R47, R48, R72). A neighbour / plateau check would be an extra diagnostic, not a replacement for BH.

### Ch. 17 Inequality-constrained optimisation
Writes the realistic constraints: no short sales, a sector cap (for example no more than 25% in supermarket shares),
"return at least the target" (more flexible than "exactly the target", since higher return can come with lower risk),
"risk at most the budget", and allowing cash (weights summing to at most one). Optimality conditions with complementarity
(an inequality is either tight or its multiplier is zero). Slack and squared-slack variables. Practical lesson: one example
has two solutions with equal risk but different returns (1.09% and 1%); both are equally optimal "as posed", because the
formulation did not say higher return is preferred. The objective must encode what you actually want.

### Ch. 18 Extending equality methods to inequalities
Active-set quadratic programming, reduced gradients with simple bounds, penalty / augmented Lagrangian / SQP versions for
inequalities. SOLVER returned a different one of the two equal-risk solutions depending on its starting point. More
inequality constraints make problems harder. Pure numerics otherwise.

### Ch. 19 Barrier function methods
Keep every iterate strictly inside the allowed region by adding a term that blows up at the boundary (reciprocal or log
barrier). Needs a feasible start; usually the least competitive method; useful only when the objective cannot be computed
outside the allowed region. The barrier and penalty methods stopped at different, equally valid points of the non-unique
problem. Pure numerics.

### Ch. 20 Interior point methods
Slack variables plus a log barrier, with Newton steps on the perturbed optimality conditions; competitive with SQP, and the
book calls it an open question which is better. Pure numerics.

### Ch. 21 Data fitting with inequality constraints
Minimax (smallest worst error) fits as a smooth constrained problem. On 65 days of one share's closes the minimax trend
line was pulled toward the two lowest points (intercept 3.7, slope 0.00286 a day) compared with least squares (3.9 and
0.00105): minimax is more sensitive to extremes. Then the trend channel.

#### 21a. Least-width trend channel and its break (Ch. 21)
- Entry: fit two parallel lines (same slope) to a window of daily closes, the lower ("support") below every close and the
  upper ("resistance") above every close, choosing slope and intercepts so the channel is as narrow as possible. Project
  the lines forward. A close below the projected support suggests selling the share; a close above the projected
  resistance suggests buying; while price stays inside, hold.
- Stop: not stated.
- Target / exit: not stated (implicitly, act on the next break).
- Filters: the book says one must judge "the severity" of a break before acting; no threshold given.
- Market and timeframe written for: daily closes of one share; windows of 20 days (days 1-20, 20-40, 40-60 of a 65-day
  series).
- Book's example: the days 1-20 channel (support intercept 3.86, resistance 4.46, slope -0.00632 a day) held for seven
  days, then price broke below support, and the following days 28-50 fell, so selling "could well" have been justified; the
  days 20-40 channel was downward and price broke up through resistance at day 48, which "suggests" buying; the days
  40-60 channel was upward and the advice was to hold. Three illustrations on one share, no statistics.
- Signal source: underlying price (closes of the instrument itself). Maps to: NSE index spot / futures, then buy ATM CE on
  an upward break and ATM PE on a downward break (our default); MCX futures likewise; crypto possible but would need a stop
  and a profit lock added, which the book does not give.
- Mechanical? partly: the fit is exact; the "severity" judgement and the exit are not stated (see rule
  `least-width-trend-channel-break`).
- Our research: not tested in this exact form. Nearest: Donchian 20-bar channel breaks (R51 Donchian is a paper arm; R72
  Breadth Donchian passed with twin p 0.008, BH q 0.032 when 4 of 5 indices break together); the R71 squeeze-release
  channel break looked good in design (+Rs 57,763 on 803 trades) and lost in the final (-Rs 41,281 on 305 trades, twin p
  0.91); support / resistance levels were no better than random lines (R20b: random mirrored levels did as well or better
  in 6 of 7 variants), and 2-close level breaks did about as well at random levels (R20c). A sloped least-width channel is
  a different line from any of these, so it is not yet rejected.

### Ch. 22 Rebalancing and other risk measures
- Rebalancing with separate buy and sell amounts and a 5% cost each way: a portfolio built for 0.2% had drifted to 0.187%;
  the cost-aware rebalance reached 0.2% at risk 0.654, against 0.651 for the cost-free optimum and 0.659 for a fresh build
  that pays costs on everything. So the best rebalance is not the best fresh portfolio. A curiosity: when the target is set
  below the least-risk portfolio's own return, buying and selling the same asset can lower the measured risk; the book
  calls the setup artificial.
- Downside risk (only periods below the target count): on a 5-asset toy the downside risk barely changed (0.00174 vs
  0.00178) but the weights changed appreciably; the least-downside-risk portfolio was very different from the
  least-variance one.
- Worst-case risk (the single worst period's miss from target), solved as a minimax: again very different weights.
- Robust two-window choice (after Rustem and Howe): minimise the larger of the risk measured on a short recent window and
  on the full history, so neither view is ignored.

#### 22a. Downside / worst-case risk and the two-window minimax (Ch. 22)
- What it computes: allocations that minimise only the bad deviations, or the worst single period, or the worse of two
  estimation windows.
- How we could use it: Boss's account stop (-Rs 40k drawdown, -Rs 13k a month) is a downside / worst-case constraint, not
  a variance one. If we ever allocate lots across arms, the objective should be worst day or drawdown, and the
  short-window vs long-window minimax is a direct guard against the drift shown in Ch. 10.
- Signal source: none (sizing / risk measure). Maps to: NSE / MCX arms. Mechanical? yes.
- Our research: our gates already report worst day and max drawdown beside net (R48's lesson: judge a rule by net, average
  win vs loss, worst day and drawdown together). No study of ours optimised an allocation on them.

### Ch. 23 Global optimisation
Local methods usually stop at the optimum nearest their start. Multi-start: on a two-minimum example a quasi-Newton method
from 10 random starts found the global answer 7 times; with a stronger penalty term, 5 times in 12. Clustering plus a
Bayesian estimate of how many optima exist (after Rinnooy Kan and Timmer): finding 5 optima in 30 searches suggests about
6.3 in total; finding no new one by 100 searches brings the estimate to about 5.3. DIRECT (repeatedly trisect the most
promising boxes) found the global answer in about 20 iterations but has no firm stopping test, so its best point is then
polished by a local method. Buy-in threshold (each weight either zero or at least 5%): the local method stopped at risk
0.7005, DIRECT then local polishing reached 0.691, close to the 0.6894 without the threshold; with a most-return objective
the threshold cost little (0.2855% vs 0.2877%).

#### 23a. Multi-start and the "how many optima are there?" estimate (Ch. 23)
- What it computes: how likely it is that a search has found the best region, from how often repeated random starts land on
  the same answers.
- How we could use it: when a model or a sweep is re-run from different seeds or folds, the spread of the answers is
  information. If each restart lands on a different "best" setting, the data do not pin the setting down; that is a
  warning sign of fitting noise, consistent with Ch. 10 and Ch. 16.
- Signal source: none. Mechanical? yes. Our research: not tested by us as a gate.

## Performance claims
- No trading-performance claims. All results are optimiser outputs (weights, risk, return) on tiny tables or a 5-share
  London sample from 2002-03. Evidence: tables of iterations and solutions, with no sample sizes, no out-of-sample
  statistics and no significance tests.
- The one out-of-sample look (Ch. 10): a least-risk portfolio fixed on day 50 had a running mean return around its 0.1%
  daily target over days 51-100, mostly above it, but lost money for about ten days. One path; no evidence beyond the
  figure.
- Trend channels (Ch. 21): three illustrations on one share where the break direction matched what followed. No evidence
  of a rule's profitability.
- Method speed claims (Newton fastest per iteration, quasi-Newton fastest in run time for large n, AL-SQP about half the
  run time of the penalty method at 200 assets) are numerical-analysis results and are backed by the tables.

## Risk, sizing and psychology
- Risk is portfolio variance by default; Ch. 22 adds downside (below-target only) and worst-case (single worst period)
  versions and shows they lead to quite different weights.
- Sizing is the whole subject of the portfolio chapters: weights per asset, short-sale bans, per-asset caps (25%), sector
  caps, cash allowed, buy-in minimum (5%), lot rounding, transaction costs.
- Warnings the author gives: optimal portfolios can lose money; optimising on history does not protect against assets
  doing badly now; small rebalances are not worth their costs; it is easy to impose constraints that have no solution.
- No psychology content.

## How it relates to our research
- Over-fitting of parameters. The book never names over-fitting or multiple testing. But three of its results say the same
  thing our studies found: (1) "optimal" weights estimated from history drift within days as the window grows (Ch. 10),
  as our H15 Kelly fraction did across windows (0.48 / 0.14 vs 1.29); (2) optima are flat, so quite different settings
  perform almost the same in sample (Ch. 10, 16), meaning the exact best setting carries little information; (3) adding
  parameters always lowers in-sample error (Ch. 11: about a third of the error with 4 parameters on 10 points), which is
  what R30's C2 combinations did before losing out of sample (+Rs 300-520 a trade on training years, Rs -111 after).
- Parameter sweeps and BH. The book's search advice (grid search is wasteful; use derivative-free or global methods;
  restart from many points) is about finding an optimum faster, not about whether the optimum is real. It offers nothing on
  Benjamini-Hochberg, holdouts or random baselines; our gates (pre-registration, walk-forward, one holdout read, random
  twins, BH across the family) remain necessary. A plateau / neighbour check (Ch. 16 "room for manoeuvre") and a
  restart-spread check (Ch. 23) could be added as diagnostics, never as substitutes.
- Portfolio of arms. Agrees with R72 (do not run two 0.80-correlated arms side by side) and with R84 in spirit (cutting
  correlated positions lowers the worst day); R84 also found that cutting them did not beat a random subset (best p 0.13),
  so diversification alone did not create an edge.
- Costs. Agrees with our cost-first stance (about Rs 70 NIFTY / Rs 76 BANKNIFTY a round trip): the book shows costs change
  the optimal action (rebalance only on big changes; optimal rebalance differs from optimal fresh build).
- Trend channels. Closest to our channel and level studies (R51, R72 pass; R71 squeeze, R20b, R20c fail). Not tested in the
  book's least-width sloped form.
- Option pricing, Greeks, volatility: none in this book (see `bennett-trading-volatility.md`).

## What is new to us
- A cost-aware rebalancing objective: change the current allocation by the least amount that restores the target, instead
  of re-optimising from scratch.
- A "room for manoeuvre" check: the largest change to a chosen setting that keeps results within 1-5% of the best.
- Two-window minimax: choose the setting that is least bad under both a short recent window and the full history.
- Downside and worst-case risk as optimisation objectives (our gates report worst day and drawdown but no study optimised
  on them).
- Lagrange multipliers as the marginal cost of a constraint (what a tighter cap or stop costs per unit).
- Restart statistics (Rinnooy Kan and Timmer) as a way to judge whether a search's "best" answer is stable.
- The least-width sloped trend channel as a level definition (different from Donchian's flat highest-high / lowest-low).

## Could not read
- Most displayed equations came through blank or scrambled (the text layer drops formula glyphs); the prose around them
  was enough to follow every method. No equations are reproduced here.
- Many numeric tables are partly or wholly blank in the text layer: Tables 1.4-1.7, 2.2, 5.1-5.3, 7.1, 8.1, 9.3, 9.5,
  10.2-10.4, 11.1, 13.1, 14.1-14.4, 15.1, 18.2-18.4, 19.1-19.3 and several solution vectors (for example the weights in
  Ch. 16 rebalancing and sensitivity, Ch. 22 downside and worst-case solutions, Ch. 23 buy-in solutions). Numbers quoted
  above are only those that came through in the prose.
- All figures (contour plots, Wolfe-condition sketches, the time-variation plots of Figures 10.1-10.4, the trend line and
  trend channel charts of Figures 11.1-11.3 and 21.1-21.5, the DIRECT diagrams) are images; only captions and some axis
  labels survived. The share-price series behind the trend-channel example is not listed in the text.

## R104 results (25 Oct 2026)

Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in `research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:

- `book-fmoptim-01`: tested (R104): DLY: 996 trades, win 39%, net Rs 9,827 design, holdout -Rs 214,333 (fail); MCXD: 409 trades, win 40%, net -Rs 101,013 design, holdout -Rs 249,461 (fail)
- `book-fmoptim-02`: tested (R104): MM: drawdown cut 59%, net cut -35% design (fail)
