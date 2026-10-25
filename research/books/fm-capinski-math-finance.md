# Mathematics for Finance: An Introduction to Financial Engineering - Marek Capinski and Tomasz Zastawniak (2003)

Source: mathematics-for-finance-an-introduction-to-financial-enginee.txt, 321 pages (Springer Undergraduate Mathematics
Series), read in full on 25 Oct 2026 (text layer, every chapter 1-11, all worked solutions, bibliography, glossary and
index; the 75 figures are images, so only their captions came through - see "Could not read").
Book claims are the authors', not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
An undergraduate textbook of mathematical finance built up from one-period, two-state toy markets.
It teaches no-arbitrage pricing: forwards, futures, options by replication, binomial trees and the Black-Scholes formula.
It covers Markowitz portfolio choice, the efficient frontier and CAPM (beta, market portfolio).
It shows hedging with Greeks, Value at Risk and payoff design with spreads, worked as small case studies.
The last third is fixed income: compounding, bonds, duration, term structure, interest-rate trees, swaps and caps.

Every number in the book is a made-up classroom example (US dollars, toy stocks at $60 or $100); there is no market
data, no backtest and no trading rule with an entry signal. Its value for us is as a reference for pricing, Greeks,
no-arbitrage checks on our option chain, hedge ratios and risk measures, not as a source of trade ideas.

Our data, for the mapping: NIFTY weekly and BANKNIFTY monthly index options (European, cash-settled, no dividends to
handle at index level beyond the index's dividend yield), 1-minute rolling chain of the nearest expiry within ATM +-10
strikes with IV and OI (last prices, no bid/ask), index spot and futures bars, India VIX daily, MCX futures and options
(options on futures), Delta Exchange crypto options. Costs: about Rs 70 (NIFTY) / Rs 76 (BANKNIFTY) per option round
trip; a four-leg structure is eight orders, about Rs 300 (DEEP_OPTIONS_SELLING.md, summary point 9).

## Methods, rules and setups (chapter by chapter)

### Ch. 1 - A simple one-period market
Two assets (a bond and a stock), two dates, two possible stock prices. States the working assumptions (random prices,
positive prices, any fractional or short position, solvency, finitely many prices) and the no-arbitrage principle:
no portfolio costs nothing today and can only gain. Shows the forward price equals spot grown at the risk-free rate,
prices a call by building a stock-plus-borrowing portfolio with the same payoff, and notes that the real probability of
"up" never enters the price. Shows options as leverage (all-in calls: +46.67% or -100% where the stock made +-20%) and
as insurance (buying a call to cap a future purchase halves the spread of outcomes; two calls remove the risk, which is
the same as a forward).

### 1. Pricing by replication and no arbitrage (Ch. 1, 4, 8)
- What it computes: the one price of a derivative that leaves no riskless profit, by finding the stock-and-cash portfolio
  (or, in a tree, the step-by-step strategy) whose payoff matches the derivative in every state. The stock quantity in
  that portfolio is the option's delta.
- Inputs: today's price, the possible next prices (tree) or the volatility (Black-Scholes), the risk-free rate.
- Key consequence: the expected return of the underlying and the real up-probability do not enter the option price
  (Remark 1.2, Remark 8.1, Remark 8.2). A directional view changes nothing about what an option is worth; it only changes
  what the buyer expects to make.
- How we could use it: it is the theory behind the app's Black-Scholes repricing and our "options already price the
  expected move" findings; nothing to trade.
- Signal source: volatility / options structure (a pricing tool, no signal). Maps to: NSE index options yes, MCX
  options yes (on futures), crypto options yes.
- Mechanical? yes (a formula).
- Our research: consistent with R89 (FINDINGS.md, "A strategy from R88"): straddles bought when a big move was
  forecast were about zero gross before costs because the options already priced the move (out of sample 2,214
  straddles, -Rs 4.4 lakh, 0 of 18 versions positive), and with h29 (HUNT_H29.md): the synthetic forward from option
  prices leads the index by 5-30 minutes but the move is already in the option price.

### Ch. 2 - Risk-free assets
Simple, periodic and continuous compounding; effective rates to compare them; present value of annuities and
perpetuities; zero-coupon and coupon bond prices by discounting; a bond trades at par when its coupon rate equals the
interest rate; the money market account. Pure groundwork. Practical bit only: Black-Scholes and futures fair value need
a continuously compounded rate (DEEP_OPTIONS_SELLING.md uses a flat 6.5%), and log returns add up over time while
simple returns do not.

### Ch. 3 - Risky assets and the binomial tree
Returns and log returns; non-additivity of simple returns. Remark 3.5 warns that averaging past returns as a forecast is
fragile: from the same ten daily prices the 9-day average log return is about 0.77% but the last 4 days give 6.18%.
The binomial tree (independent up/down steps), the condition down < risk-free < up (else arbitrage), the risk-neutral
probability (r - d)/(u - d), and the fact that discounted prices are a martingale under it. A trinomial tree has
infinitely many risk-neutral probabilities (the market is incomplete). The continuous-time limit: with up/down log
steps of mean m times the step plus or minus sigma times the square root of the step, the random walk tends to Brownian
motion and the price becomes lognormal (geometric Brownian motion); sigma is the volatility.

### 2. Lognormal model and square-root-of-time volatility scaling (Ch. 3.3)
- What it computes: the distribution of the price at a horizon; the sd of the log return over a horizon is sigma times
  the square root of the horizon. A binomial step of length tau uses log up/down moves of m*tau plus or minus
  sigma*sqrt(tau).
- Inputs: drift m (irrelevant for pricing), volatility sigma, horizon.
- How we could use it: converting India VIX or ATM IV into an expected rupee move for a window (e.g. the straddle-sized
  move for the next hour), building a CRR tree for MCX options, simulating paths for Monte Carlo tests.
- Signal source: volatility / options structure (a model). Maps to: NSE yes, MCX yes, crypto yes (24x7 clock).
- Mechanical? yes.
- Our research: R16 (HUNT_R16_PREDICTABILITY.md) found the next 30-60 minutes' range is predictable (error 14-15%
  smaller than VIX alone) because the plain time-scaled VIX ignores the time-of-day shape; the lognormal model has no
  intraday seasonality and no fat tails, so it is a baseline, not the forecast.

### Ch. 4 - Discrete-time market models
Many assets, multi-step strategies; self-financing (no cash in or out), predictable (decide only on information up to
now) and admissible (never insolvent) strategies; no-arbitrage stated over strategies; the Fundamental Theorem of Asset
Pricing (no arbitrage if and only if there is a probability under which all discounted prices are martingales);
pricing a call by risk-neutral expectation through a tree with three branches. Two exercises matter to us: Ex 4.4 shows
that if you could predict the next step from the last one there would be a sure profit, which is why the theory
forbids look-ahead (our look-ahead guard is the same idea), and Ex 4.6-4.7 show that integer lots or a 5% transaction
cost can make an apparent arbitrage disappear. Short-sale deposits (50% security deposit, lost interest) are worked
in Example 4.3. Otherwise theory.

### Ch. 5 - Portfolio management (Markowitz and CAPM)
Risk as variance (or sd) of return; portfolio return is the weighted average; portfolio variance needs the covariances.
Two assets: minimum-variance weight in closed form; with perfect negative correlation risk can be removed without
shorting; without shorting, a mix beats the safer asset only when the correlation is below the ratio of the smaller to
the larger sd (Corollary 5.6). Many assets: minimum-variance portfolio and minimum-variance line by Lagrange multipliers
(weights move linearly with the target return); the Markowitz bullet and efficient frontier; with a risk-free asset the
capital market line and the market portfolio (tangency); beta = covariance with the market over market variance; total
risk = residual (diversifiable) + beta squared times market variance (systematic); security market line (expected
return = risk-free + beta times market premium). The book admits that estimating the covariance matrix is hard.

### 3. Minimum-variance mix of return streams (Ch. 5.2-5.3)
- What it computes: weights for several assets (for us, several arms' daily P&L streams) that give the lowest variance,
  or the lowest variance for a target mean.
- Inputs: means, sds and correlations of the streams, estimated from history.
- How we could use it: sizing several paper arms together (Liquidity 15+5, Breadth Donchian, R51 Donchian, OOV) so that
  correlated arms do not double the same risk; the two-asset rule tells when adding a second arm can lower risk at all.
- Signal source: none (a sizing method on P&L series). Maps to: any book of arms, NSE / MCX / crypto.
- Mechanical? yes, but the inputs are noisy.
- Our research: closest is HUNT_H4.md (combining rules, walk-forward weights): risk parity over only the components
  positive on all earlier data was the chosen portfolio (+Rs 173/day per unit, max DD -Rs 85,271, 18 of 33 losing
  months) while equal lots over all components lost (-Rs 1,095/day); the edge failed the multiple-testing check (SPA
  p = 0.17). R72 (HUNT_R72_CROSS_INDEX.md) found Breadth Donchian and Donchian share 77% of trades (daily correlation
  0.80), so they should not run side by side - exactly the book's point that highly correlated assets give no
  diversification. A full Markowitz optimiser on arms: not tested by us.

### 4. Beta and alpha against the index (Ch. 5.4)
- What it computes: beta (sensitivity to the market) and alpha (return not explained by it) by regression of a return
  series on the market's; CAPM says only beta risk earns a premium.
- How we could use it: beta of BANKNIFTY, FINNIFTY or an arm's P&L to NIFTY, for hedge sizing and for checking whether an
  arm's profit is just market exposure.
- Signal source: underlying price/volume (returns). Maps to: NSE yes, MCX (vs a commodity index) partly, crypto (vs BTC)
  yes.
- Mechanical? yes.
- Our research: DEEP_NIFTY_BANKNIFTY.md section 1.4 measured BANKNIFTY daily beta 1.16 to NIFTY (correlation 0.877, n
  5,086 days) and SENSEX beta 0.986. The CAPM pricing claim itself: not tested by us (not relevant to intraday options).

### Ch. 6 - Forwards and futures
Forward price by no arbitrage: spot grown at the risk-free rate; minus the present value of dividends; with a continuous
dividend yield q the forward is spot times e to the (r - q)T; currency forwards use the rate difference. The basis
(futures minus spot, or the reverse) shrinks to zero at expiry. Value of an existing forward. With a short-sale deposit
or different borrowing and lending rates there is a no-arbitrage band, not a single price (Ex 6.1, 6.4). Futures: daily
marking to market, initial and maintenance margin, margin call (worked with 10% / 5% margins); with constant rates
futures and forward prices are equal. Hedging a planned sale with short futures locks in about the futures price
(both scenarios end near $102.02); using a later contract month leaves basis risk; the minimum-variance hedge ratio is
the correlation times the ratio of sds; index futures can set a portfolio's beta to any target, and beta zero earns only
the risk-free rate on average (it is not riskless). Example 6.5: in Poland, where short selling was rare, a short in one
stock was built from short index futures plus the other 19 index stocks.

### 5. Futures fair value, implied carry and the basis (Ch. 6.1-6.2)
- What it computes: fair futures = spot x e^((r - q) T); from a quoted futures price one can back out the implied
  dividend yield or rate (Ex 6.10: index 13,500, 9-month futures 14,100, rate 8% gives q about 2.20%).
- How we could use it: NIFTY / BANKNIFTY futures premium over spot as an implied financing rate; MCX futures vs spot
  carry; crypto perpetual or dated futures basis. Also needed to put the option chain on a forward (parity, below).
- Signal source: volatility / options structure (carry); as a direction signal it would be underlying price. Maps to:
  NSE yes, MCX yes, crypto yes.
- Mechanical? yes.
- Our research: basis as a direction filter appears in h29 (synthetic forward from options leads spot but is already
  priced); cash-futures arbitrage itself needs the stock basket and is not tested by us. H39 (HUNT_H39.md) tested
  heavyweight stock options as a lead for the index: they move with the index, not before it.

### 6. Minimum-variance hedge ratio and beta hedging with index futures (Ch. 6.2)
- What it computes: number of futures per unit of exposure, N = correlation x sd(spot) / sd(futures); and the futures
  needed to move a portfolio's beta from b to a target a: N = (b - a)(1 + r)V / f.
- How we could use it: hedging a BANKNIFTY position with NIFTY futures (beta 1.16), sizing a cross-index pair, or hedging
  an MCX mini position with the big contract.
- Signal source: none (risk management). Maps to: NSE yes, MCX yes, crypto yes.
- Mechanical? yes.
- Our research: no hedged positions are run by the app; one-lot futures hedges are too coarse (DEEP_OPTIONS_SELLING.md
  section 3: a NIFTY futures lot equals one option lot, so a 0.2-delta hedge would need 13 units). Not tested by us.

### Ch. 7 - Options: general properties
Definitions; payoffs and gains (the buyer's loss is capped at the premium, the writer's can be unlimited); break-even =
strike + premium grown at the rate (Example 7.1). Put-call parity for European options (with dividend versions) and the
weaker two-sided version for American options. Model-free bounds: a call is worth at least spot minus discounted strike
and less than spot; a put less than the discounted strike. Without dividends an American call is never worth exercising
early, so it equals the European call; an American put may be. As functions of strike, calls fall and puts rise, the
price change between two strikes is less than the discounted strike gap, and prices are convex in strike (a butterfly
can never cost less than zero). As functions of the underlying, prices are monotone, change by less than the spot move
(delta between 0 and 1 in size) and are convex. Longer American options are worth at least as much. Moneyness,
intrinsic and time value; the time value is largest at the money (Proposition 7.19), and a deep in-the-money European
put can have negative time value.

### 7. Put-call parity and the no-arbitrage bounds as a data check (Ch. 7.2-7.4)
- What it computes: C - P = S - X e^(-rT) (for an index, use the futures / forward: C - P = (F - X) e^(-rT)); plus the
  monotone, slope (vertical spread worth less than the discounted strike gap) and convexity (butterfly at least zero)
  conditions across strikes; time value peaks at the money.
- Inputs: call and put prices at each strike, futures or spot, rate, time to expiry.
- How we could use it: as a filter on our 1-minute lake. A minute where a strike's call minus put strays far from the
  forward, or where premiums are not convex across strikes, almost certainly has a stale or bad print. R103's false
  passes came from exactly such stale far-wing prices. It also gives a clean implied forward and lets one check IV
  surfaces.
- Signal source: volatility / options structure (consistency check), option premium (the check reads option prices,
  but it is a data filter, not a trading signal). Maps to: NSE yes (European, cash-settled, so parity is exact up to
  costs), MCX options on futures yes (parity on the future), crypto options yes.
- Mechanical? yes.
- Our research: R103 (DEEP_OPTIONS_SELLING.md summary point 9): six BANKNIFTY tests passed the design gates
  (+Rs 1.9 to +Rs 5.4 lakh) only because far wings sat at stale prices; repriced with Black-Scholes they all lost
  (-Rs 2.9 to -Rs 3.3 lakh). HUNT_LAKE_STALE_PRINTS.md flags 9,646 stale one-minute prices of 35.4 million (0.03%) by
  repeated-print runs; a parity / convexity cross-check is not in our cleaner yet - not tested by us. Trading the
  violations is listed as a candidate rule (put-call-parity-conversion, butterfly-convexity-violation), with
  the doubt that most violations in last-price data are stale prints.

### Ch. 8 - Option pricing
Replication and risk-neutral expectation in one-step, two-step and N-step binomial trees; the Cox-Ross-Rubinstein
formula; American options by backward induction, taking at each node the larger of exercising now and holding (Example
8.1: an American put worth 1.27 where the European is 0.79); an American call can be worth exercising early just before
a dividend (Example 8.2). Black-Scholes derived by changing to the risk-neutral measure (sketched, with the Girsanov
theorem named): call = S N(d1) - X e^(-rT) N(d2), put by parity; the drift does not appear. A 10-step tree already
matches Black-Scholes closely (Figure 8.2). Exercises add two practical points: proportional costs on selling stock
raise the cost of replicating a call (7.1429 to 8.1633 at a 2% cost, Ex 8.3), and different borrowing and lending rates
give different call and put prices (Ex 8.4). Ex 8.2 shows that a stock with higher variance can still have a cheaper
call (0.0095 against 0.025) when its moves are skewed - the shape of the moves matters, not just their variance.

### 8. Binomial (CRR) and Black-Scholes pricing (Ch. 8)
- What it computes: fair option value and the hedge quantity; American values by backward induction.
- Inputs: spot or futures, strike, time, rate, volatility (or tree up/down sizes).
- How we could use it: already used - the app and R103 reprice legs with Black-Scholes; a CRR tree would be needed only
  for American-style options (none on NSE index options; MCX options are European on futures, so Black-76 is the right
  variant, which the book does not cover). Repricing stale legs at their last IV (DEEP_OPTIONS_SELLING.md section on
  PREREG Amendment 2) is this method.
- Signal source: volatility / options structure. Maps to: NSE yes, MCX yes (Black-76 form), crypto yes.
- Mechanical? yes.
- Our research: used as a tool in R103, R28/R36 (GEX uses Black-Scholes gamma) and DEEP_OPTIONS_SELLING.md's
  illustration table (NIFTY 25,000, IV 15%).

### Ch. 9 - Financial engineering (the most practical chapter)
9.1 Hedging option positions: a short book of 1,000 calls (stock 60, strike 60, 90 days, vol 30%, rate 8%; call
4.14452, delta 0.581957) is delta-hedged with stock. After one day with no move the hedged book makes +19.45 (the naked
book +27.10); small moves to 59 or 61 cost only -3.26 / -1.77; but large moves hurt the hedge too (-2,233.19 at 50,
-1,765.15 at 70, gamma), and a volatility rise from 30% to 32% costs about -212 at every price (vega). The Greeks (delta,
gamma, theta, vega, rho) give a first-order P&L split, and the Black-Scholes equation ties theta, delta and gamma
together: a delta-hedged short option earns time decay exactly when the price does not move. Adding a second option
(60-day, strike 65) to make the book delta-gamma neutral keeps the one-day result within about +-2 between 58 and 62
(-614.08 at 50, +440.81 at 70); a delta-vega hedge keeps a vol jump to 32% to between -16.05 and +73.62 instead of
-212 to -300. Each extra Greek needs another option and more transaction costs.
9.2 Value at Risk: the loss (counting the risk-free return given up) not exceeded with 95% probability; a lognormal
example gives 39.50 on a $100 stock. A foreign-exchange business case compares no hedge (VaR 431,818), a forward
(locks +471,852), full call protection (-27,328, the premium is costly), half calls (-229,573) and half calls plus half
forward (+222,263). VaR gives only one point of the outcome curve; plot the whole curve.
9.3 Speculating: bull call spread, bear put spread, butterfly and reversed butterfly; any piecewise-linear payoff can be
built from calls and puts. Case 9.2: a 20-day at-the-money call (2.112 on a 60 stock) has expected return 14.1268% but
sd 153.006% and a 0.4119 chance of losing everything; 94.77% in cash plus the rest in calls matches the stock's risk
with VaR 798.73 against 1,931.78 for the stock; all the risky choices have about the same "market price of risk"
(excess return per unit of sd), about 0.1. Case 9.3: an analyst who knows the price will stay between 58 and 66 does
best with a 58/60 bull spread plus 94.58% cash (sd 2.8396% vs 3.9257% for the stock at the same mean, VaR $412 vs
$447; a naked call's VaR 12,426) - but only because the view is right.

### 9. Delta hedging and the Greeks (Ch. 9.1)
- What it computes: the futures (or underlying) quantity that makes a book's delta zero, and the second-order exposures
  (gamma to big moves, vega to volatility changes, theta per day).
- How we could use it: explaining and limiting short-option risk (a seller's loss grows with the square of the move);
  the P&L split delta x move + theta x time + vega x vol change + half gamma x move squared is a ready tool for the
  app's position screen and for paper-arm attribution.
- Signal source: volatility / options structure. Maps to: NSE in principle (futures as hedge) but not at one lot; MCX
  same; crypto options can be hedged in small sizes with perpetuals.
- Mechanical? yes (rebalance on a delta band).
- Our research: DEEP_OPTIONS_SELLING.md section 3 explains why a one-lot seller cannot delta-hedge (lot sizes match) and
  why R103 tested wings and stops instead; R103 found no defined-risk selling structure that survives (0 of 24). The
  hedger's P&L is implied minus realised variance; DEEP_NIFTY_BANKNIFTY.md section 2.2 finds India VIX above NIFTY's
  next-21-day realised vol on 81% of days (median ratio 1.28, n 4,507 days) but below BANKNIFTY's on 60% of days.
  Delta-hedged selling itself: not tested by us (see bennett-trading-volatility.md method 8 for the desk view).

### 10. Delta-gamma and delta-vega hedging with a second option (Ch. 9.1.3)
- What it computes: positions in the underlying and in a second option so the book's delta and gamma (or delta and
  vega) are both zero.
- How we could use it: a short straddle protected with a further-dated or different-strike long option; a calendar is a
  crude vega hedge.
- Signal source: volatility / options structure. Maps to: NSE (weekly vs monthly NIFTY) yes in principle; MCX thin; crypto
  yes.
- Mechanical? yes.
- Our research: not tested by us; R103's iron flies and condors are the defined-risk cousins and all failed after costs.

### 11. Value at Risk (Ch. 9.2)
- What it computes: a loss level exceeded with a chosen small probability over a horizon, here from a lognormal model
  and including the risk-free return forgone.
- How we could use it: a daily risk number per arm or for the whole paper book; but our account rules are drawdown
  rules (stop at -Rs 40k drawdown or -Rs 13k a month), which VaR does not replace.
- Signal source: none (risk measure). Maps to: all markets.
- Mechanical? yes, but the lognormal tail is too thin for index options (the naked BANKNIFTY 09:20 straddle's worst day
  was -Rs 65,483, DEEP_OPTIONS_SELLING.md summary point 8).
- Our research: our studies report worst day, worst month and max drawdown instead; bootstrap / Monte Carlo drawdown
  probabilities are in HUNT_H15.md and HUNT_H16.md (2,000-path stationary bootstrap). Parametric VaR: not tested by us.

### 12. Expressing a view with spreads plus cash (Ch. 9.3)
- Entry (as the book states it): when you expect a moderate rise, buy a bull call spread (long lower strike, short higher
  strike); moderate fall, bear put spread; no move, butterfly; big move either way, reversed butterfly. Keep most of the
  capital in cash so the total risk matches what you would accept in the underlying.
- Stop: not stated. Target / exit: expiry.
- Filters: the view itself (an analyst's range forecast).
- Market and timeframe written for: a single stock, 20-day options.
- Signal source: none / discretionary (the view); the structure is volatility / options structure. Maps to: NSE yes
  (strikes 50 or 100 apart), MCX options yes, crypto yes (with a stop and a profit lock, per CLAUDE.md).
- Mechanical? no (the edge is the view, which the book assumes is correct).
- Our research: the view is the problem - direction on NIFTY / BANKNIFTY is a coin flip (R16: AUC 0.509-0.536 for 15-60
  minutes; DEEP_NIFTY_BANKNIFTY.md: 1 of 70 sign rules confirmed, an artefact). Range (size) is predictable (R16, R88),
  but R89 straddles on size forecasts lost (0 of 18), and R103's defined-risk spreads, flies and condors lost after costs
  (0 of 24). The sizing lesson (small option risk plus cash) matches our fixed-lot findings (HUNT_H36.md).

### 13. Option leverage and the chance of losing everything (Ch. 1.7, 9.3)
- What it computes: return distribution of a pure call position vs the stock; the probability that the premium goes to
  zero.
- How we could use it: as a Jarvis explanation for why the app sizes option buys in fixed lots with a premium stop.
- Signal source: option premium as an outcome (not a signal). Maps to: NSE, MCX, crypto.
- Mechanical? yes (arithmetic).
- Our research: HUNT_H15.md's half-Kelly on the Liquidity books carried a 63% chance of a 30% drawdown and the Kelly
  fraction was unstable (BN 1.29 here, 0.48 and 0.14 on other windows); H17 / H36 moved to fixed lots. Consistent with
  the book's warning; not a separate test.

### Ch. 10 - Variable interest rates
Yields independent of maturity; return on a bond sold before maturity falls when rates rise; duration as the
present-value-weighted average payment time and as minus the price sensitivity to the yield; immunisation (a portfolio
whose duration equals the holding period is unhurt by small rate shifts, shown with a $32-coupon bond and a 3-year
rebalanced example ending between 100,107 and 100,389 against a 100,000 target); portfolio duration as a weighted
average; general term structure, spot rates, stripping coupon bonds, forward rates and forward-rate agreements, short
rate, a random money market account. Pure fixed income; no use for index options beyond the choice of discount rate.

### Ch. 11 - Stochastic interest rates
Binomial trees of bond prices, yields, forward rates and short rates; arbitrage pricing of bonds by replication with the
longest bond and the money market; Example 11.5 shows a hand-made tree that contains an arbitrage of 0.0004 and must be
repaired - a reminder that a model can be internally inconsistent; risk-neutral probabilities per node, independent of
maturity; floating-rate bond at par; options on bonds, callable bonds, swaps (with a comparative-advantage example
splitting a $1,000 saving as $640 / $360), caps, caplets and floors (cap value 0.45450 = caplets 0.32773 + 0.12677);
short-rate models (Vasicek, Cox-Ingersoll-Ross, Black-Derman-Toy) and Heath-Jarrow-Morton named only. Not relevant to
our trading.

### Solutions, bibliography, glossary, index
Worked answers to every exercise (read; they add the numeric details quoted above, e.g. Ex 9.3-9.4: a delta-hedged book
of 50,000 short puts gains 5.59 in a day but loses 179.56 if the rate drops from 5% to 3%; Ex 9.7 adds a delta-rho
hedge). Bibliography points to Hull, Wilmott, Bjork, Pliska and others.

## Performance claims
None about trading profits. The book makes no claim that any strategy makes money and gives no market evidence; all
returns, VaRs and hedge results are computed on invented prices (e.g. the delta-hedge tables in Ch. 9.1, the 0.4119
chance of losing everything in Case 9.2). The only "claims" are mathematical: no-arbitrage prices, parity, bounds, and
that hedges reduce risk under the model. The book itself notes that arbitrage opportunities are rare, short-lived and
mostly beyond small investors (Ch. 1.2), and that beta-zero hedging works only on average (Remark 6.7).

## Risk, sizing and psychology
- Risk is defined as the sd of return (Ch. 1, 5); later VaR (Ch. 9.2). Diversification lowers risk only when
  correlations are low enough.
- Options can raise risk (all-in calls) or lower it (protective calls, forwards); the writer of an option has the large
  losses (Ch. 7.1).
- Hedges only work for small moves unless more Greeks are hedged; every extra hedge costs transaction costs (Ch. 9.1).
- Sizing in Case 9.2 / 9.3 is by matching the total sd to an acceptable level, putting most money in cash.
- Kelly sizing and drawdowns are not covered. No psychology.

## How it relates to our research
- Agrees: options already price the expected move and the drift (Ch. 1, 8) - R89 found straddles bought on size
  forecasts about zero before costs and losing after (0 of 18), and h29 found option-price signals are already in the
  price. The book's statement that direction does not enter the option price fits our finding that direction is a coin
  flip while size is predictable (R16, R88, R102).
- Agrees: transaction costs kill small edges and even arbitrage (Ex 4.7, 8.3) - our costs (about Rs 70 / Rs 76 a round
  trip, about Rs 300 for an eight-order structure) decided R103 and R89.
- Agrees: short gamma is dangerous for big moves (Ch. 9.1) - R103's naked straddle had a worst day of -Rs 65,483;
  defined-risk versions failed after costs.
- Agrees: correlated assets give no diversification (Ch. 5) - R72's Breadth Donchian vs Donchian (daily correlation
  0.80) should replace, not join.
- Not tested by us: put-call parity / convexity checks on the lake (would have caught R103's stale wings early), a
  Markowitz optimiser over arms, minimum-variance futures hedges, parametric VaR, delta-gamma hedged selling.
- Bennett's book (research/books/bennett-trading-volatility.md) covers the desk side of the same Greeks, variance swaps,
  skew and the implied-over-realised premium in far more trading detail; this book is the mathematical base under it.

## What is new to us
- A model-free consistency check of the option chain: put-call parity against the futures, monotone and convex premiums
  across strikes, call-spread value below the discounted strike gap, time value peaking at the money. Cheap to code as a
  lake cleaner step; it targets exactly the stale-wing failure of R103.
- The implied forward and implied carry from the chain and from futures (Ex 6.10 style), as a clean input for
  Black-Scholes repricing instead of spot plus a flat 6.5% rate.
- A minimum-variance or risk-parity combination of the paper arms with covariance from walk-forward windows (H4 did a
  version; Markowitz with a target return was not tried).
- The Greek P&L split (delta, gamma, theta, vega) as an attribution tool for every paper option trade.
- The point from Ex 8.2 that skewed up/down moves change option value at the same variance - a reminder that the smile
  matters when we reprice wings at one IV.

## Could not read
The 75 figures (trees, payoff diagrams, the Markowitz bullet, VaR comparison curves, Figure 8.2 comparing CRR with
Black-Scholes) are images; only captions and surrounding text came through. Some formulas in the text layer are broken
across lines (fractions, sums, square roots), but every one could be reconstructed from context and the worked numbers.
Nothing else was unreadable.

## R104 results (25 Oct 2026)

Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in `research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:

- `book-fmcapinski-01`: not testable (R104): needs executable bid/ask; last prints in the lake are often stale (R103)
- `book-fmcapinski-02`: not testable (R104): needs executable bid/ask
