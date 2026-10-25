# An Introduction to Mathematical Finance: Options and Other Topics - Sheldon M. Ross (1999)

Source: introduction-to-mathematical-finance-ross.pdf ("Introduction to Mathematical Finance-Ross.pdf"), 102 scanned
pages, each a two-page spread (book pages i-xv and 1-183 plus the index), read in full on 25 Oct 2026 (tesseract OCR,
every chapter 1-11, all exercises, the crude-oil and heating-oil / unleaded-gas data tables and the index; no page was
rotated or blank; formulas and figures garbled - see "Could not read").
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

Edition note: the brief named "An Elementary Introduction to Mathematical Finance". The scan is the FIRST edition
(Cambridge University Press 1999, ISBN 0-521-77043-2, title "An Introduction to Mathematical Finance: Options and Other
Topics"); the OCR of the cover reads "second edition" but the title page, copyright page and contents are the 1999
first edition. The later editions (2003 and 2011) were retitled "Elementary" and added chapters, including the one on
trading strategies that beat a random-walk market only when prices mean-revert. **That chapter is not in this scan.**
The nearest material here is Ch. 11 (autoregressive model and mean reversion), Ch. 10 (crude oil is not a random walk)
and the "your drift differs from the risk-neutral drift" sections 7.5.3 and 8.5; they get full method blocks below.

## What it is about
A short, gentle textbook that derives Black-Scholes with no calculus beyond basic probability.
It builds probability, the normal distribution and geometric Brownian motion (GBM) first, then interest and present value.
Arbitrage is taught through worked "sure-win" examples and the arbitrage theorem (proved by linear-programming duality).
Black-Scholes comes from the limit of the multiperiod binomial model; then American puts, utility, Kelly, portfolios, CAPM.
The last chapters price exotics by Monte Carlo and test GBM on real crude-oil data, offering a Markov-state and an AR(1) model.

Most numbers are classroom examples in US dollars. The only real market data are NYMEX nearest-month crude oil
(3 Jan 1995 - 19 Nov 1997, 752 trading days) and unleaded gasoline / heating oil (1995-1997). There is no backtest,
no cost model for trading, and no entry-exit rule; the trading content is statistical (Ch. 10-11). Standard pricing
material is covered more fully in our other write-ups: `fm-binomial-models.md` (binomial trees, parity, American
exercise), `fm-capinski-math-finance.md` (portfolio theory, CAPM, Greeks, VaR), `fm-basics-fin-math.md` (stochastic
calculus, Black-Scholes, random walks) and `bennett-trading-volatility.md` (trading implied vs realised volatility);
below they are pointed to, not repeated.

## Methods, rules and setups

### Ch. 1-2 - Probability, normal and lognormal variables, central limit theorem
Pure basics: events, conditional probability, independence, expectation, variance, covariance and correlation; the
normal and lognormal laws and the CLT, with worked dice, coin and "Dow up today and tomorrow" examples. One practical
item: a six-decimal polynomial approximation of the normal distribution function, enough to compute Black-Scholes N(d1)
on a phone without a library (we already compute it in code). The weekly lognormal example (drift .0165, sd .0730 per
week) gives P(up in a week) .5894: a reminder that drift barely moves the odds of an up move at short horizons.

### Ch. 3 - Geometric Brownian motion
Defines GBM (the log of the price ratio over any interval is normal, independent of the past), shows that the expected
price grows at drift plus half the variance, and builds GBM as the limit of a binomial walk whose up and down factors
are exp(+/- sigma sqrt(step)). Contrasts it with Bachelier's arithmetic Brownian motion (can go negative, absolute
changes). Key consequence, stated plainly: under GBM only the present price matters - past prices carry no
information, so chart reading cannot work if the model is right. Theory; see `fm-basics-fin-math.md` for the
stochastic-calculus version.
- Our research: direction is close to a random walk on our indices. DEEP_NIFTY_BANKNIFTY.md 2.5 measured lag-1
  autocorrelation of +0.006 at 5 minutes (NIFTY, n 167,082 bars) and +0.028 for daily returns (n 5,086 days), with
  nothing tradeable in the pre-registered version; R16 (`HUNT_R16_PREDICTABILITY.md`) found direction over the next
  15/30/60 minutes unpredictable (holdout AUC 0.509-0.536) while the size of the next move is predictable - real prices
  are close to GBM in direction but not in volatility, which constant-sigma GBM misses.

### Ch. 4 - Interest rates, present value, rate of return
Simple, compound and continuous interest; effective rate (5% continuous = 5.127% a year); the rule of 70 for doubling;
present value used to rank cash-flow streams (which one wins depends on the rate); a machine-replacement and a mortgage
example; the effective cost of a loan with fees (a quoted .6% a month becomes about 7.8% a year after fees and one
point); internal rate of return and when it is unique (one sign change); time-varying spot rates and the yield curve.
Practical only as hygiene: costs and fees change the effective rate, as our ~Rs 70 NIFTY round trip changes a rule's
real return (`FINDINGS.md`, opening summary). The rate r for Black-Scholes should be a continuously compounded rate.

### 1. One-period arbitrage and replication (Ch. 5.1) - the book's first arbitrage-detection example
- What it computes: in a one-step market where a stock at 100 goes to 200 or 50, a call struck at 150 has exactly one
  price that allows no sure win. Holding one share against three short calls pays 50 in both states, so the hedge ratio
  is 3 calls per share (equivalently 1/3 share plus borrowing replicates one call). The no-arbitrage price is 55/3
  when the one-period discount factor is .9.
- Detection, as the book works it: if the call trades at 20 (too dear), buy 1 share, sell 3 calls, borrow the 40 net
  cost; at time 1 the position is worth 50 whatever happens, repay 44.44, keep a sure 5.56. If the call trades at 15
  (too cheap), short 1 share, buy 3 calls, bank the 55; it grows to 61.11, the position owes 50, sure profit 11.11.
- Inputs: the two possible next prices, the option payoff, the interest rate.
- How we could use it: only as intuition for delta (the hedge ratio is the change in option value over the change in
  the underlying). Real markets have more than two outcomes, so the trade is never riskless (Ch. 8.1 says so itself).
- Entry / stop / exit as a trade: enter when the quoted price differs from the replication cost by more than costs;
  no stop; hold to expiry. Not a rule on our data: it assumes a two-state world.
- Signal source: option premium compared with a model value (options structure, not our default). Maps to NSE index
  options only as the delta-hedge idea; MCX and crypto the same.
- Mechanical? yes in the toy model; no in a real market.
- Our research: not tested by us as a trade; see method 8 for the model-versus-market version.

### 2. Static no-arbitrage bounds, put-call parity, forwards and covered interest parity (Ch. 5.2)
- What it computes: (a) an American call on a non-dividend stock is never worth exercising early - shorting the stock
  and keeping the call beats exercising; (b) a call is worth at least spot minus the present value of the strike, else
  buy the call, short the stock and lend the proceeds; (c) put-call parity: spot plus put minus call equals the
  present value of the strike, with the exact conversion (buy stock and put, sell call, borrow) or reversal trade when
  it fails; (d) forward price = spot grown at the risk-free rate, with both arbitrage directions; it fails for
  commodities because of storage, insurance and the inability to short-deliver; (e) currency forwards: the gap between
  the DM spot (.5777) and the 90-day forward (.5808) in 1998 is fully explained by the two interest rates.
- Inputs: call, put, spot or futures, rate, time; for currencies, both rates.
- How we could use it: as a data-quality gate and to back out the synthetic forward from the chain - covered in full in
  `fm-binomial-models.md` (method on parity, rules `put-call-parity-arbitrage` and `butterfly-convexity-arbitrage`);
  not repeated here. Point (d) matters for MCX: commodity futures need not sit at spot plus interest, so the
  futures-spot basis carries storage and convenience information (H39-type basis study).
- Signal source: option premium (chain prices against each other); options structure. Maps to NSE index options yes
  (European, cash-settled); MCX options on futures yes; crypto options in principle.
- Mechanical? yes, but needs simultaneous bid and ask.
- Our research: not tested as a trade. R103 (`HUNT_R103_OPTIONS_SELLING.md`, "The stale-leg artefact") showed the
  lake's far-wing prices are stale, so apparent parity breaks will mostly be that artefact. h29 (`HUNT_H29.md`) built
  the synthetic forward from the chain: it "leads" the index but a buyer cannot use it (all 24 variants lose).

### 3. The arbitrage theorem as a sure-win detector (Ch. 6.1, 6.3) - the book's general arbitrage-detection method
- What it computes: given any set of available bets (each paying a known amount in each possible outcome, betting long
  or short allowed), exactly one of two things holds: some probability vector makes every bet fair (zero expected
  gain), or some combination of bets wins in every outcome. The proof is linear-programming duality: maximise the
  guaranteed win; if the dual (find fair probabilities) is infeasible, the primal is unbounded. Weak arbitrage
  (never lose, sometimes win, "a free lottery ticket" in the book's words) is ruled out only if fair probabilities
  exist that are all strictly positive.
- Worked detection: a bookmaker quotes odds against three outcomes of 1, 2 and 3 to 1. No arbitrage needs the sum of
  1/(1+odds) to equal 1; here .5 + .333 + .25 = 1.083 > 1, so betting AGAINST all three (stakes 1, .7 and .5) wins
  .2, .1 or .2 whatever happens. The same theorem recovers the Ch. 5 call price through the fair up-probability
  (1+2r)/3, and in the n-period binomial model forces independent up-moves with probability (1+r-d)/(u-d), which gives
  the call as the discounted fair expectation of its payoff.
- Inputs: the payoff of every quoted instrument in every scenario (a scenario grid of the underlying at expiry), and
  prices.
- How we could use it: a generalised chain checker - put every quoted call, put and the future into one LP over a grid
  of expiry prices; an infeasible "fair probability" problem flags a static arbitrage (vertical, butterfly, calendar or
  parity break) in one pass instead of rule by rule; the positive-probability version flags weak arbitrage. On our
  lake the main use is catching stale quotes, not trading them.
- Entry / stop / exit as a trade: only if the LP's sure win exceeds round-trip costs at executable bid/ask; no stop;
  hold to expiry.
- Signal source: option premium / options structure (prices of the chain). Maps to NSE index options yes, MCX options
  yes, crypto options in principle; a betting-odds book is the same maths.
- Mechanical? yes (an LP).
- Our research: not built by us. Our nearest checks are rule-by-rule (parity and butterfly in `fm-binomial-models.md`);
  R103's stale-leg finding says any "sure win" in last-traded prices must be re-checked against live quotes.

### 4. Binomial pricing, Black-Scholes, Greeks direction and the American put (Ch. 6.2, 7.1-7.4)
Derives Black-Scholes as the limit of the n-period binomial price, gives the risk-neutral reading (log-price drift r
minus half sigma squared), the put by parity, and delta = N(w) as the hedge ratio (worked example: stock 30, strike
34, three months, 8%, vol .20 gives 24 cents). Shows the call value rises with spot, time, volatility and the rate and
falls with the strike, noting that the volatility effect is subtle (more variance but a lower mean log price under
risk-neutral drift). Prices an American put by backward induction on a binomial tree, taking at each node the larger
of exercise value and discounted continuation; the exercise region is monotone (if exercise is right at a price, it is
right at every lower price); five steps give 1.137 against an exact 1.126. Full method blocks, inputs and our use are
in `fm-binomial-models.md` and `fm-basics-fin-math.md`; our options are European (NIFTY/BANKNIFTY cash-settled), so the
American-put routine matters only for stock options.
- Signal source: tool (pricing). Our research: Black-Scholes is used as a tool in R103 (repricing stale legs) and in
  R28 / R36 GEX (gamma from Black-Scholes); not a signal.

### 5. Volatility from daily closes (Ch. 7.3)
- What it computes: sigma from the sample standard deviation of daily log returns, annualised by the square root of
  252.
- Inputs: n daily closes. How we could use it: realised volatility for comparing with IV. We already do (R102
  close-to-close and Parkinson, DEEP_NIFTY_BANKNIFTY.md 2.2). Signal source: underlying price. Mechanical: yes.
- Our research: R102 found India VIX above NIFTY's realised volatility of the next 21 days on 81% of days (median
  ratio 1.28, n 4,507), but below BANKNIFTY's on 60% of days.

### 6. Implied volatility and the skew (Ch. 7.5.1)
- What it says: the market price, not history, is often a better volatility estimate (implied volatility), but
  different strikes and expiries give different IVs; IV from in-the-money calls (low strikes) tends to exceed IV from
  out-of-the-money calls. Read against historical-sigma Black-Scholes, low-strike calls look overpriced and high-strike
  calls underpriced. Ross's hypothesis: the market expects volatility to fall after a rise and to rise after a fall, so
  an OTM call, which needs a rise to pay, is worth less than constant-sigma suggests.
- Inputs: chain prices. How we could use it: skew as context around an underlying signal (see Bennett 23-25).
- Signal source: option chain as context. Maps to NSE index options yes; MCX options yes; crypto options yes.
- Mechanical? the measurement is; the book gives no trade.
- Our research: consistent. DEEP_NIFTY_BANKNIFTY.md 3.4 (n 1,222 NIFTY days): the put five strikes out is dearer than
  the call five strikes out on 91% of days (median +2.56 IV points). h29 (`HUNT_H29.md`): directional buys from skew
  moves lose (all 24 variants).

### 7. Rates and volatility move together (Ch. 7.5.2)
A rate rise lifts a call's model value only if volatility is unchanged, and rate changes shift money between stocks and
bonds, which itself moves volatility. One line: on our weekly options the rate term is tiny; event volatility around
RBI policy days is the relevant effect (our event calendars, not this book).

### 8. Model price versus market price: delta-hedged "mispricing" trade (Preface, Ch. 7.1, 7.5.1, 7.5.3)
- Entry (as the book states it): if the market price of an option is below the GBM / Black-Scholes value, buy the
  option and sell delta units of the underlying; if above, sell the option and buy delta units.
- Stop: not stated. Target / exit: hold, re-hedging; the book's own caution is that this is NOT a sure win -
  continuous re-hedging is impossible, discrete re-hedging costs can exceed the gap, volatility may change over the
  option's life, and GBM is only an approximation. Its stated hope is a positive expected gain with small variance.
- Filters: a sigma estimate you trust (historical, Ch. 7.3).
- Market and timeframe written for: US stock options, no timeframe.
- Signal source: volatility / options structure (option premium against a model value). Maps to NSE index options as a
  delta-hedged straddle or single option against NIFTY/BANKNIFTY futures; MCX options against the future; crypto
  options against perpetuals (with a stop and profit lock per CLAUDE.md).
- Mechanical? partly (needs the sigma choice and a re-hedge rule).
- Our research: not tested as a delta-hedged trade. The premise points to selling NIFTY volatility: R102 (81% above).
  Unhedged selling failed (R103: 0 of 24). One-lot sellers cannot delta-hedge finely (DEEP_OPTIONS_SELLING.md section
  3). The rule is already registered as `delta-hedged-short-straddle-iv-over-hv` (from `fm-binomial-models.md`); see
  Bennett 8 and 13 for the trader's version.

### 9. Incomplete markets and expected utility (Ch. 8.1-8.2)
With three possible next prices (50, 100, 200) arbitrage no longer fixes the call price: any price between 0 and 50/3
is arbitrage-free. Prices then depend on preferences: utility built from indifference probabilities, concave utility =
risk aversion (Jensen), linear = risk neutral; the log and exponential utilities. Theory, but it is the honest reason
our real options (fat tails, jumps) have no single "correct" price.

### 10. Log utility = Kelly sizing (Ch. 8.2)
- What it computes: an investor with log utility facing an even-money bet won with probability p should stake the
  fraction 2p - 1 of capital (p .6 gives 20%, p .7 gives 40%; nothing if p is below one half). If the payoff comes one
  period later and idle money earns r, the fraction is (2p - 1 - r)/(1 - r): p .6 with r .05 gives about 15.8% even
  though the bet's expected return is 20%. Log utility maximises the long-run growth rate and (for large targets)
  minimises the expected time to reach a goal (Breiman 1960, Thorp 1975).
- Inputs: win probability and payoff ratio (our arms: win rate and average win / average loss).
- How we could use it: as the upper bound on lots per arm; fractional Kelly in practice.
- Signal source: sizing (no signal). Maps to NSE, MCX, crypto alike.
- Mechanical? yes.
- Our research: H15 (`HUNT_H15.md`): half-Kelly on the Liquidity books carried a 63% chance of a 30% drawdown, and the
  Kelly fraction was unstable (BN 1.29 in the study window, 0.48 and 0.14 on other windows); H17 and FINAL moved to
  fixed lots. The book's formula assumes p is known; ours is estimated and drifts.

### 11. Mean-variance portfolio choice, minimum-variance weights, CAPM (Ch. 8.3-8.4, 8.6)
- What it computes: with normal end-of-period wealth and exponential utility, maximise expected wealth minus a
  risk-aversion multiple of half its variance; a portfolio with higher mean and lower variance is always preferred.
  Two assets with equal means: the variance-minimising weight follows from the two variances and covariance (sd .20
  and .30, correlation .30 gives 76.6% / 23.4%); uncorrelated assets get weights inversely proportional to variance.
  Power and log utilities give weights that do not depend on wealth; minimum-variance portfolios for different target
  returns are scalar multiples of one portfolio (separation). Covariances are estimated from history (8.3.1). CAPM:
  beta = covariance with the market over market variance (rf 6%, market .10, beta 1.25 gives 11%); risk splits into
  systematic and specific parts. GBM one-period mean return is exp(mu + sigma^2/2) - 1, not mu.
- Inputs: return series of the things being combined.
- How we could use it: combining arms by the covariance of their daily P&L (inverse-variance or minimum-variance
  weights) rather than equal lots; beta of BANKNIFTY to NIFTY. Exercise 8.11 is worth noting: to maximise the chance
  of ending above a goal g (normal wealth), maximise (mean - g) / sd - a "reach the monthly target" sizing view.
- Signal source: sizing / allocation (no signal). Maps to all.
- Mechanical? yes.
- Our research: HUNT_H4.md (combining rules with walk-forward weights) is the closest; DEEP_NIFTY_BANKNIFTY.md 1.4
  measured BANKNIFTY's daily beta to NIFTY at 1.16 (correlation 0.877, n 5,086 days). See `fm-capinski-math-finance.md`
  for the Markowitz details.

### 12. Stock plus call, positive expectation when your drift view differs (Ch. 8.5, 7.5.3) - "beat the fair market"
- Entry (as the book states it): if you believe the stock's GBM drift mu is above the risk-neutral value (r minus half
  sigma squared), both the stock and a fairly priced call have positive expected gain under your view. Combine a long
  in one with a short in the other to get a positive expected gain with the smallest variance. Worked example: stock 62,
  strike 60, five months, vol .20, rate 10%, call at its Black-Scholes price 5.80, own drift .10 against the
  risk-neutral .08. Buying one share: expected present-value gain $.52, sd $8.09. Buying one call: $.39, sd $6.61.
  Minimum variance for an expected gain of 1: buy 2.93 shares and sell 1.34 calls (about .46 calls per share), far
  less risky than 1/.52 shares alone (sd 15.56). This is a covered-call-like hedge sized by mean-variance.
- Stop / exit: hold to the option's expiry; no stop.
- Filters: a drift estimate different from the risk-neutral one.
- Market and timeframe written for: a US stock and its listed call, months.
- Signal source: underlying price (the drift view) plus volatility / options structure for the hedge. Maps to NSE
  index options as "long future + short calls" (a covered call on NIFTY futures), MCX as future + short call, crypto
  as perpetual + short call (needs stop and profit lock).
- Mechanical? partly: the hedge maths is exact, the drift view is not.
- Our research: the drift view is the weak part - direction on NIFTY / BANKNIFTY is a coin flip at intraday horizons
  (R16) and the swing rules found no index edge after costs (`SWING_DEEP.md`, verdict 1). Covered calls / overwriting:
  see Bennett 4; R103's option-selling tests failed (0 of 24). Not tested by us in this exact form.

### 13. Exotic options by Monte Carlo, with variance reduction (Ch. 9)
- What it computes: barrier (knock-in plus knock-out = vanilla; breach judged on daily closes), Asian (average price or
  average strike) and lookback (strike = lowest close) values, by simulating daily log steps with risk-neutral mean
  (r - sigma^2/2)/252 and variance sigma^2/252 and averaging discounted payoffs; the 95% error band is about two
  standard deviations over the square root of the number of runs. Variance reduction: a control variate (subtract c
  times a variable with known mean; the best c removes a share of variance equal to the squared correlation),
  antithetic paths (re-use each path's mirrored normals), using every daily normal as a control for Asian / lookback,
  and for barriers a conditional-expectation estimator (stop at the first breach and plug in the Black-Scholes value of
  the now-live option) plus importance sampling (simulate with a lower drift so breaches are common, then reweight).
  Power options have a closed form; barriers can also be priced on a binomial tree with several steps per day.
- Inputs: spot, vol, rate, the path rule.
- How we could use it: our Monte Carlo and bootstrap studies (H15 drawdown bootstrap, R102 ranges) can use antithetic
  and control variates to cut run counts; a down-and-out barrier is the right model for an option position with a
  hard index stop (the payoff dies if the stop is hit). We trade no exotics.
- Signal source: tool. Maps to all. Mechanical? yes.
- Our research: we use bootstrap / Monte Carlo for risk (`HUNT_H15.md`, "Bootstrap P(ruin within 24 months)") but
  have not used these variance-reduction tricks; not needed at our run sizes so far.

### 14. Crude oil is not a random walk: the four-state Markov test (Ch. 10.1-10.2) - full block
- What it computes: label each day by its close-to-close log change: state 1 below -1%, state 2 from -1% to 0, state 3
  from 0 to +1%, state 4 above +1%. Count how often each state is followed by each state and test the rows for
  equality (chi-square test of independence in a contingency table). Then compare the next-day return distributions by
  today's state (one-way ANOVA for means, Bartlett for variances).
- Data and results (NYMEX nearest-month crude, 3 Jan 1995 - 19 Nov 1997, 751 log changes): next-day state counts
  after state 1 / 2 / 3 / 4 were n 176 / 214 / 168 / 193. A big drop was followed by another big drop 31% of the time,
  by a small drop 23%, a small rise 25% and a big rise 21%. A big rise was followed by a big drop 27% of the time, a
  small rise by a big drop only 15%. Chi-square 23.447, p .00526 ("about 1 in 200"). Next-day mean and sd of the log
  change by today's state: state 1 -.0036 / .0194; state 2 .0024 / .0188; state 3 .0025 / .0165; state 4 -.0011 /
  .0208. ANOVA F 4.50 on 3 and 747 degrees of freedom, p below .001; Bartlett 9.59, variances differ too. A six-state
  split at +/-1% and +/-2% is tabled the same way. The single-day histogram looks normal; the dependence only shows
  when days are conditioned on the day before.
- Reading: after a big down day crude kept falling on average (continuation); after any up day it drifted down; after
  small moves it rose; and next-day volatility was higher after big moves than after a small rise - volatility
  clustering, which is the part that matters for option pricing.
- Inputs: daily closes of the future.
- How we could use it: (a) as a template test - the same 4x4 table and chi-square on MCX CRUDEOIL, NATURALGAS, GOLD,
  SILVER and on NIFTY / BANKNIFTY daily and 15-minute returns, with Benjamini-Hochberg across instruments; (b) the
  state-conditional sd as a one-day-ahead volatility forecast against ATM IV. Rules file: `crude-big-drop-continuation`
  and `crude-state-vol-vs-iv-straddle`.
- Signal source: underlying price (the future's own daily change). Maps to: MCX crude directly (the book's own
  market, though a different era); NSE index options as a test template; crypto daily (with stop and profit lock).
- Mechanical? yes.
- Our research: not tested by us on crude in this form. On NIFTY, DEEP_NIFTY_BANKNIFTY.md 2.5 found daily lag-1
  autocorrelation +0.028 (53.2% follow the previous sign, n 5,086 days) - descriptive, nothing tradeable in the
  pre-registered section 4.2. The volatility half agrees with R16 (size predictable, direction not). For crude our MCX
  work (`HUNT_R96_SUMMARY.md`) studied NR7 breakouts and event days, not next-day state dependence; the NR7 crude
  lead (R4G: +Rs 49,381 on 28 trades) is a range-contraction rule, a cousin of "volatility depends on yesterday".
  Caution: one 3-year sample from 1995-97, one market, 16 cells, no out-of-sample check.

### 15. A state-dependent model for pricing crude options (Ch. 10.3-10.4)
- What it computes: next-day log change drawn from today's state's normal (mean x_i, sd s_i) or, better, a bootstrap
  draw from the days that followed that state; options valued by Monte Carlo. For deciding whether an option is cheap
  or dear relative to the future, use the risk-neutral variant: keep each state's sd but set the mean to r/252 minus
  half the variance - volatility then depends on the last day's move (smoothly, by interpolating sd against the last
  change). This is consistent with the martingale hypothesis (discounted price is fair) while volatility has memory.
- Inputs: daily closes, rate. Output: an option value; the book's comparison (Ch. 11.2) for a 50-day crude call
  struck at 21 with crude at 20: 64 cents with sample means, 81 cents risk-neutral, 79 cents Black-Scholes.
- How we could use it: a volatility forecast for MCX options conditioned on yesterday's move, compared with ATM IV.
- Signal source: volatility / options structure (a vol forecast from the underlying versus the chain's IV). Maps to
  MCX crude options yes; NSE index options yes (same idea as our predictable-range finding); crypto options yes.
- Mechanical? yes.
- Our research: R16's range forecast (holdout error 15-20% below the time-of-day baseline, FINDINGS.md) is a richer
  version; turning a vol forecast into an option trade after costs has not passed for us (R89 straddles, R103 selling).

### 16. AR(1) on log prices and mean reversion (Ch. 11) - full block
- What it computes: regress today's log price on yesterday's, L(n) = a + b L(n-1) + noise. GBM is the special case
  b = 1. If 0 < b < 1 the price reverts: tomorrow's expected price lies between today's price and a level s* =
  exp((a + sigma^2/(2N)) / (1 - b)) with N = 252. The n-day-ahead log price is normal with a closed-form mean and
  variance, so a call's expected payoff has a Black-Scholes-like closed form.
- Crude example: a = .0487, b = .9838, daily residual sd .01908; reversion level 20.44 dollars. With crude at 20, a
  50-day call struck at 21 and an 8% rate: expected present value of the payoff under AR(1) 44.42 cents against a
  Black-Scholes price of 79 cents (historical vol .3032). The gap comes from the horizon variance of the log price:
  .0091 under AR(1) against .01824 under GBM (means almost equal, 3.0016 against 3.0025). Mean reversion halves the
  horizon variance, so options are worth far less if the model is right.
- The book's trading conclusion is careful: AR(1) with b < 1 is not risk-neutral, so no sure win exists, but an
  investor who believes the fitted a and b can build positions with large expected value and small variance (as in
  method 12). Traders' belief in commodity mean reversion is the motivation; no entry rule, stop or backtest is given.
- Inputs: daily closes (the book fits the whole 3-year sample in-sample; exercise 11.3 asks for a fit on heating oil).
- How we could use it: (a) fade rule: when the fitted b is below 1 and price is far from s*, trade toward s*
  (`ar1-reversion-fade`); (b) volatility rule: if AR(1) horizon variance is well below the GBM variance implied by ATM
  IV, the options are dear under the model - sell defined-risk premium (`ar1-mean-reversion-premium-sell`).
- Signal source: underlying price (AR fit on the future's or index's closes); rule (b) is volatility / options
  structure. Maps to MCX CRUDE / NATURALGAS / GOLD / SILVER futures and options (the book's target: commodities);
  NIFTY / BANKNIFTY daily as a test; crypto daily (stop and profit lock required).
- Mechanical? yes once the fit window and thresholds are fixed (we must choose them; the book does not).
- Our research: no AR(1) study by us. Nearest: `SWING_DEEP.md` "Mean reversion: buy after big down day" on NIFTY
  futures (fixed 10-day hold: 71 trades, +Rs 258,007 over 2016-2026, 5 of 11 years positive) did not pass the strict
  test; the verdict says no index swing rule passed walk-forward. R45 and R59 "mean reversion" scalps were artefacts
  (FINDINGS.md: R59 "0 of 84 pass"). A fitted b just below 1 on 3 years of data is statistically indistinguishable
  from a random walk (the classic unit-root bias); the book does not test b = 1, and the crude example (b .9838) would
  likely not reject it. The option-value gap (44 vs 79 cents) is then an estimation artefact as much as an edge.

## Performance claims
- None about trading profits. Every option value is a model output, not a P&L.
- Ch. 10: crude daily changes depend on the previous day's state (p .00526, ANOVA p below .001) on one 1995-97
  sample of 751 changes; no out-of-sample test.
- Ch. 11: the AR(1) value of a crude call is 44.42 cents against 79 cents Black-Scholes; the author reads this as the
  GBM model overpricing options on a mean-reverting commodity, but gives no trade or test of b = 1.
- Ch. 9: the variance-reduction estimators are "improvements over what is presently in the literature" (preface);
  no timing numbers in the text.
- Ch. 8.5: positive expected gain with small variance from stock plus calls - conditional on the investor's own drift
  being right; no evidence.

## Risk, sizing and psychology
Risk enters through utility: concave utility is risk aversion, and with normal outcomes any risk-averse investor
prefers higher mean with lower variance. Log utility gives Kelly sizing (2p - 1 of capital on an even-money bet),
with the reminder that a delayed payoff and a bank rate shrink the fraction. Diversification by covariance and the
systematic / specific split of CAPM. The book is explicit that "arbitrage" between a model and the market is not
riskless in practice (costs, discrete hedging, changing volatility, wrong model). No stop-losses, drawdown limits or
psychology.

## How it relates to our research
- Agree: the gap between Black-Scholes and the market is not free money (method 8) - R103 found apparent edges in
  option selling came from stale prices. The skew observation (method 6) matches R102 (puts dearer on 91% of days).
  GBM's "past prices carry no direction information" matches R16 and DEEP_NIFTY_BANKNIFTY 2.5.
- Agree, sharper: Ch. 10's volatility-clustering result (bigger sd after big moves) is the same structure as R16's
  "size predictable, direction not".
- Already rejected nearby: index mean-reversion swing rules (`SWING_DEEP.md`), premium mean-reversion scalps (R45,
  R59). Kelly in raw form was unstable on our books (H15).
- Not tested by us: the crude next-day state table on MCX; an AR(1) reversion fit on commodities; an LP chain checker.

## What is new to us
1. The four-state next-day contingency test (chi-square plus ANOVA / Bartlett) as a cheap, standard template for any
   instrument - we have autocorrelation tables but not this conditional table for MCX crude, gas and metals.
2. AR(1) on log price with an explicit reversion level and an AR-based option value; the variance ratio (AR horizon
   variance over GBM horizon variance) as a measure of how "overpriced" options are under mean reversion.
3. A one-shot linear-programming arbitrage checker over the whole chain, including weak arbitrage.
4. Volatility as a function of yesterday's move, interpolated, inside a risk-neutral Monte Carlo pricer for MCX
   options.
5. Monte Carlo variance reduction (antithetic, control variates, conditional expectation, importance sampling) for
   our bootstrap and drawdown simulations.
6. Exercise 8.11's "maximise the probability of beating a target" sizing criterion.

## Could not read
- Formulas throughout are garbled by OCR (for example the Black-Scholes w term, the mean-variance functions of 8.5,
  the importance-sampling estimator of 9.6.2, the AR(1) mean and variance of 11.6-11.7 and the reversion-level
  expression); the prose and worked numbers were readable and are what this write-up uses.
- Figures are images: 2.1-2.3, 4.1, 5.1, 7.1, 8.1-8.4 and the crude-oil price chart and histograms 10.1-10.7 (only
  captions came through). Table 4.1 (present values at three rates) is partly garbled.
- The cover page OCR is garbled ("SECOND EDITION" appears; the title and copyright pages say 1999 first edition).
- Data tables 10.5 and 11.1 came through as numbers with scattered OCR digit errors; they were not used.
- No page was rotated or missing, so no re-OCR was needed.

## R104 results (25 Oct 2026)

Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in `research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:

- `book-fmross-01`: tested (R104): MCXD: 1166 trades, win 44%, net -Rs 161,038 design, holdout -Rs 317,905 (fail)
- `book-fmross-02`: tested (R104): VOL: 498 trades, win 33%, net -Rs 110,983 design, holdout -Rs 46,248 (fail)
- `book-fmross-03`: tested (R104): MCXD: 1 trades, win 100%, net Rs 4,554 design, holdout Rs 0 (fail); DLY: 0 trades, win -, net Rs 0 design, holdout Rs 0 (fail)
- `book-fmross-04`: tested (R104): VOL: 3 trades, win 0%, net -Rs 1,789 design, holdout Rs 0 (fail)
