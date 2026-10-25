# Methods of Mathematical Finance - Ioannis Karatzas and Steven E. Shreve (1998)

Source: Methods of Mathematical Finance-Karatzas Shreve.pdf (Springer, Applications of Mathematics vol. 39), 215 scanned
PDF pages, each holding two book pages (about 405 book pages: preface, Chapters 1-6, Appendices A-E, references, index),
read in full on 25 Oct 2026 (tesseract OCR; no page was rotated; every displayed formula is garbled, and a few spreads
are clipped or interleaved, see "Could not read"). Book claims are the authors', not tested results. Signal source per
method is in IraAlgo terms (CLAUDE.md).

## What it is about
A research monograph, in definition-theorem-proof style, on continuous-time markets driven by Brownian motion.
It prices and hedges claims in a complete market (European, forwards and futures, American calls and puts).
It solves one investor's best consumption and investment plan, including the growth-optimal (Kelly-type) portfolio.
It builds a market equilibrium from many investors, then prices claims and invests when trading is constrained.
Constraints covered: no short selling, borrowing limits, untradeable risks, and a borrowing rate above the lending rate.

## Methods, rules and setups

The book teaches no trading rule and no market-timing signal. It assumes prices never jump, trading is free and
continuous, and the investor knows the drift and volatility; the authors say plainly that estimation and numerics are
left out. Most of it is proof. Standard pricing material (Black-Scholes, risk-neutral pricing, forwards, put-call
parity, Merton's constant fraction, mutual-fund theorem) is written up already in `fm-bjork-arbitrage.md` and
`fm-musiela-martingale.md`; Kelly money management in practice is in `fm-wilmott.md` (method 4). Below, theory gets
a line or two per section and only the parts a trader can use get method blocks (numbered 1-7).

### Chapter 1. The Brownian market model
- 1.1-1.3 Stocks, money market, portfolios, gains and wealth with outside income. Prices are continuous; the authors
  call "no jumps" (no surprises) a major simplifying assumption, and trading is frictionless. Theory.
- 1.2 Doubling strategy (Example 2.3): doubling the bet after every loss "wins" for sure only if one can stand
  unlimited losses; in continuous time the same trick works on a finite horizon, so the model must forbid it. They
  admit only "tame" portfolios whose losses are bounded below (a credit limit). The practical reading is the old one:
  martingale (averaging-down) betting is only safe with infinite capital.
- 1.4-1.6 No arbitrage (viability) holds exactly when a "market price of risk" exists; the risk-neutral (martingale)
  measure and state-price density; the market is complete when there are as many independent stocks as noise sources
  and the volatility matrix is invertible. 1.7 infinite horizon. Theory.
- Notes: the efficient-market hypothesis is presented with its evidence; the lognormal law is "a reasonable fit" but
  other distributions fit better; CEV and other volatility models are cited.

### Chapter 2. Pricing and hedging in a complete market
- 2.2 A claim's price is the expected discounted payoff under the risk-neutral measure; the hedge comes from the
  martingale representation theorem. Why buy rather than replicate: building the hedge costs a model, fees and data.
- 2.3 Forward price = cost of carry (a commodity's storage cost enters as a negative dividend). Futures are re-settled
  continuously, so a futures contract is always worth zero and can be closed free. Forward minus futures price is set
  by the covariance between the payoff and the discount factor; it is zero when interest rates are not random.
  Relevance: H39 basis work treats futures-spot basis empirically; this is only the theoretical anchor.
- 2.4 Constant coefficients: Black-Scholes; the price and hedge do not depend on the stock's mean return (see method 1);
  the hedge of a short call always borrows; put-call parity; a look-back option priced through the Clark formula
  (Appendix E).
- 2.5-2.7 American claims = optimal stopping (Snell envelope); an American call on a non-dividend stock is never
  exercised early; perpetual call and put have a fixed exercise threshold (McKean, "smooth fit"); the finite-expiry
  American put has a free exercise boundary and an early-exercise premium equal to the value of earning interest on
  the strike while deep in the money. NSE index options are European, so this does not apply to our trading.
- Notes (useful): constant-volatility Black-Scholes "should not be used injudiciously"; volatility estimators from
  high and low prices are cited (Garman-Klass type); exotic options are only listed; transaction costs and
  incomplete markets get the review summarised in method 6.

#### 1. Option value does not depend on the underlying's expected return (Ch. 2.4)
- What it computes: in the constant-coefficient model, the price and the hedge ratio of any European payoff depend on
  the interest rate, dividend yield and volatility only; the drift (mean return) drops out. The authors call this
  attractive because the mean return is hard to estimate.
- Inputs: spot, strike, time, rate, dividend yield, volatility.
- How we could use it: the reason a direction view never enters a fair option price; an option bought on a direction
  call is a bet on direction plus a price paid for volatility. Already in `fm-bjork-arbitrage.md` (card 1).
- Signal source: volatility / options structure (pricing tool, not a signal); maps to: NSE index options (Black-76 on
  the future), MCX options (Black-76), crypto options (where listed; any crypto trade needs a stop and a profit lock).
- Mechanical? yes (a formula).
- Our research: R103 / DEEP_OPTIONS_SELLING.md reprice stale wings with Black-Scholes; R16/R88 found direction
  unpredictable while size is predictable, which is the half of the option price that matters.

### Chapter 3. One investor: consumption and investment (complete market)
- 3.2-3.5 Utility functions, their convex duals, and three problems: utility of consumption, of final wealth, or both.
  The budget constraint says discounted spending plus discounted final wealth cannot exceed starting capital.
- 3.6-3.7 Martingale (duality) method: the best final wealth is the inverse marginal utility of a multiple of the
  state-price density; the hedge that produces it exists by completeness. Bankruptcy is absorbing. Examples with log
  and power utility, and with a minimum ("subsistence") consumption or final wealth (method 3).
- 3.8 Deterministic coefficients: the best policy depends only on current wealth and time (wealth is a sufficient
  statistic); Hamilton-Jacobi-Bellman equation; Merton's mutual-fund theorem (everyone holds the same risky mix, only
  the amount differs; see `fm-bjork-arbitrage.md`). A curiosity: with time-varying coefficients the optimal stock
  holding can rise as the horizon nears.
- 3.9 Infinite horizon with discounting: the value is finite only if the discount rate is large enough; explicit
  constant-coefficient formulas.
- 3.10 Maximising the growth rate of wealth (method 2).
- Notes: Markowitz, Tobin separation and CAPM (whose risk premia "have been found not to conform well" to data);
  Merton's two- and three-fund theorems; the consumption CAPM fails on data (equity-premium puzzle); the value of the
  problem is "quite sensitive" to transaction costs (no-trade-band literature, method 6); filtering problems where the
  mean return must be estimated; "inside information" via enlarged filtrations.

#### 2. Growth-optimal (log, Kelly-type) portfolio (Ch. 3.10, Theorem 10.1; Ch. 3.7 Example 7.9)
- What it computes: the portfolio that maximises expected log of final wealth. Its stock dollar amounts are wealth
  times (covariance matrix)^-1 x (excess mean returns); for one asset the fraction is (mean - rate) / variance (the
  continuous Kelly fraction). Two properties the book proves: it does not depend on the horizon (a log investor is
  myopic), and it maximises the realised long-run growth rate almost surely against every admissible portfolio, not
  only on average (after Breiman 1961). With constant coefficients the best growth rate is the rate plus half the
  squared market price of risk.
- The authors' caution (Notes): growth maximisation does not maximise other utilities at any finite horizon (Merton and
  Samuelson; Samuelson's argument "in words of literally one syllable"), so it is not automatically the right goal for
  a fixed-horizon investor. Kelly with transaction costs and with jumps is cited, not solved.
- Inputs: the mean excess return and covariance of each traded thing (here, each arm's per-trade P&L); the mean is the
  hard input.
- How we could use it: as the upper bound on size for an arm with a proven edge, then scaled down (fractional Kelly)
  because the edge is estimated with large error.
- Signal source: none (sizing); maps to: any arm on NSE index options or MCX; crypto only with a stop and a profit lock.
- Mechanical? yes once the inputs are fixed.
- Our research: HUNT_H15.md tested half-Kelly on Liquidity 15+5: fastest growth, but P(drawdown >= 30%) 63% and
  P(drawdown >= 50%) 14%, the Kelly fraction was unstable (BANKNIFTY f* 0.48 and 0.14 on other windows against 1.29)
  and SPA p = 0.14 could not separate the sizings; quarter-Kelly cut the 50% drawdown odds to 1%. HUNT_FINAL.md and
  h36 chose fixed lots. The book's "a.s. long-run optimal" is a statement about the long run with known parameters,
  which is exactly what H15 lacked.

#### 3. Growth-optimal sizing on the cushion above a floor (Ch. 3.7 Examples 7.5 and 7.9, "portfolio insurance")
- What it computes: when final wealth must never fall below a floor (or consumption below a subsistence level), the
  optimal plan sets aside the present value of the floor in cash and invests ONLY the excess ("cushion") as the
  unconstrained log investor would. Wealth then never breaches the floor, and the risky amount shrinks to zero as
  wealth approaches it. The book credits Basak's portfolio-insurance papers.
- Inputs: capital, floor, the per-trade edge and variance.
- How we could use it: Boss's account stop (-Rs 40k drawdown) is such a floor. Sizing an arm as a fraction of
  (capital - floor) instead of capital automatically shrinks size during a drawdown and reaches zero at the stop,
  which is a risk-LOWERING rule under CLAUDE.md. See the rule `kelly-on-cushion-above-account-floor`.
- Signal source: none (sizing); maps to: all arms (NSE, MCX); crypto with stop and profit lock.
- Mechanical? yes.
- Our research: not tested by us. H15 sized on full capital (lots = k x capital / premium); h17/h36 used fixed lots.

### Chapter 4. Equilibrium in a complete market
- Many investors with endowments and utilities trade; prices clear supply and demand. A "representative agent" with
  suitable (Negishi) weights reduces the problem to finding a few weights; equilibrium exists, and it is unique when no
  investor's relative risk aversion exceeds 1. Consumption CAPM: an asset's risk premium is proportional to its
  covariance with aggregate consumption; the interest rate is minus the growth of marginal utility. Equilibrium does
  not pin down drifts and volatilities of individual stocks (they can be repackaged as funds). Pure theory; nothing
  usable for trading.

### Chapter 5. Hedging when trading is constrained (incomplete markets)
- 5.2-5.6 The constraint is a convex set K for the fractions of wealth in each stock: no short selling, a short-sale
  cap, a borrowing cap, or some risks untradeable. The "upper hedging price" is the least capital that lets the seller
  finish with at least the payoff for sure while obeying K; it equals the highest unconstrained price over a family of
  fictitious markets (dual processes acting like Lagrange multipliers). Theory, with method 4 as the usable result.
- 5.8 When the best dual process exists, the claim is replicated exactly inside K.
- 5.9-5.10 The buyer's side: the "lower hedging price" is the most one can borrow against the claim and still repay
  surely. Arbitrage only says the price lies between the lower and upper hedging prices; a single price needs
  preferences (Notes: a "fair" price from zero marginal utility of buying a little, Karatzas-Kou after Davis).

#### 4. Super-hedging under leverage and short-sale limits (Ch. 5.7 Examples 7.3-7.4, 5.8 Remark 8.7, 5.10)
- What it computes: with constant coefficients and cone or interval constraints, the upper hedging price of a payoff
  of the final price is the Black-Scholes price of a "lifted" payoff (the smallest payoff above it that is consistent
  with the constraint). The worked cases:
  - Short call, stock fraction capped at b: if b < 1 the call cannot be super-hedged at ANY price; if the cap is
    finite and at least 1, the cheapest sure hedge is to buy and hold one share (a covered call); only with no cap
    does Black-Scholes apply.
  - Short put with no short selling: the cheapest sure hedge is to hold the discounted strike in cash (a cash-secured
    put); the price is the present value of the strike.
  - A call on something the hedger cannot trade has an infinite super-hedge price.
  - Buyer's side: under no short selling the lower hedging price of a call can be zero, and under no borrowing that of
    a put can be zero (the buyer's hedge cannot be built).
- Inputs: the constraint set, volatility, rate, the payoff.
- How we could use it: as the plain statement of why a small account cannot sell options "safely". A one-lot NSE
  seller cannot delta-hedge with futures (the futures lot equals the option lot), so a sure hedge degenerates to
  covered or cash-secured positions, whose margin makes them pointless on index options; otherwise the risk is carried
  with wings and stops.
- Signal source: volatility / options structure; maps to: NSE index option selling (R103), MCX option selling; crypto
  options in research only (stop and profit lock required).
- Mechanical? yes as a bound; not a trade.
- Our research: DEEP_OPTIONS_SELLING.md (delta hedging section) reaches the same conclusion empirically: a one-lot
  seller "hedges with the structure (the wings) and the stop, not with futures". R103: defined-risk selling 0 of 24
  confirmed (the passes were stale prices).

### Chapter 6. Constrained consumption and investment
- 6.2-6.5 The Chapter 3 problem with portfolio fractions forced into K. Each constrained problem is matched with an
  unconstrained "fictitious" market in which the optimal plan happens to respect K; the dual process is a Lagrange
  multiplier; four equivalent optimality conditions; existence by convex duality.
- 6.6 Deterministic coefficients and cone constraints: a constrained HJB equation and feedback rules.
- 6.7 Incomplete markets: when some noise sources cannot be traded, a log investor simply ignores them; with power
  utility and coefficients driven by independent untradeable noise, Merton's formula still holds (myopic).
- 6.8 Borrowing rate above the lending rate (method 5). Notes: Xu's no-short-sale duality; Schweizer's mean-variance
  hedging (minimise expected squared hedging error) is summarised (method 6).

#### 5. Kelly fraction with constraints: clip it to the allowed set (Ch. 6.4 Example 4.2, 6.7 Examples 7.2-7.3, 6.8 Example 8.5)
- What it computes: for log utility the constrained optimum is found point by point: take the unconstrained Kelly
  vector and move it to the nearest allowed portfolio, distance measured with the covariance matrix. For one asset
  with the fraction limited to an interval, the answer is the Kelly fraction clipped to that interval (no short
  selling: never below 0; no borrowing: never above 1). With a borrowing rate R above the lending rate r, three zones:
  invest (mean - r) / variance if that is at most 1; hold exactly 100% (no borrowing, no cash) when (mean - R) /
  variance <= 1 <= (mean - r) / variance; and lever to (mean - R) / variance only when that exceeds 1. The costlier
  borrowing creates a wide "stay fully invested, do not lever" band.
- Inputs: edge and variance per arm, the allowed range (e.g. premium at risk at most the free cash), the borrowing cost.
- How we could use it: our arms already obey "premium at most free cash" (H15 rule 4: never pay more premium than
  the free cash). The book says the right response to such a cap is to clip the Kelly fraction, not to rescale it;
  and an option buyer's leverage is already inside the premium, so any extra borrowing would need a far bigger edge.
- Signal source: none (sizing); maps to: all arms; crypto with stop and profit lock.
- Mechanical? yes.
- Our research: H15 used capped fractions (BN k 0.65, FIN 0.53, MIDCP 0.17, BN capped at 100 lots, premium never more
  than free cash). Constraint-aware Kelly as such was not tested by us.

#### 6. Transaction costs, discrete hedging and no-trade bands (Ch. 2 Notes, Ch. 3 Notes, Ch. 6 Notes)
- What the book reports (as a literature review, no derivation):
  - Figlewski's simulation: transaction costs have a significant impact on hedging options.
  - Soner, Shreve and Cvitanic: with proportional costs in continuous time the cheapest hedge that surely covers a
    short call is to buy one share and hold it: perfect hedging becomes trivial and useless.
  - Leland: hedge at discrete times and price with an adjusted volatility (the standard form multiplies variance by
    1 + sqrt(2/pi) x cost rate / (sigma x sqrt(hedge interval)) for a short option); as rehedging gets more frequent the
    adjusted volatility blows up and the price tends to the buy-and-hold one.
  - Hodges-Neuberger: utility-based hedging, which in practice gives a band around the Black-Scholes delta inside which
    one does not rehedge.
  - Merton's problem with costs (Davis-Norman, Shreve-Soner): a no-trade band around the optimal fraction; the
    value is very sensitive to costs.
  - Schweizer: mean-variance hedging when exact replication is impossible (minimise expected squared hedge error).
- How we could use it: any delta-hedged structure we ever test must charge the hedge trades and use a band, not a
  clock; for sizing, rebalance lots only when the target leaves a band. The details live in `fm-wilmott.md` (hedging
  with costs) and `fm-musiela-martingale.md` (costs and super-replication).
- Signal source: volatility / options structure (hedging) and none (rebalancing); maps to: NSE index options (hedge in
  index futures), MCX options (hedge in the future), crypto options (hedge in the perp; stop and profit lock still).
- Mechanical? partly: the band width needs a cost model (HUNT_H24 / baselines.yaml).
- Our research: costs are measured (an ATM round trip ~Rs 98 NIFTY / ~Rs 133 BANKNIFTY, FINDINGS.md R16 section);
  delta hedging was judged impossible at one lot (DEEP_OPTIONS_SELLING.md); a band-hedged straddle was not tested.

#### 7. Reaching a goal with too little capital (Ch. 1 Notes, Ch. 3 Notes)
- What it says: if the capital is below the price of a sure hedge of a target, one can instead maximise the
  probability of reaching it by a date (Kulldorff; Heath; the "goal problem"). Cited, not solved.
- How we could use it: Boss's Rs 5,000/day goal is exactly such a target. The known solution of this problem bets
  aggressively and accepts a large chance of ending far below the target, which matches H15's finding that random
  entries with the same aggressive sizing were ruin half the time.
- Signal source: none (sizing objective); maps to: account level.
- Mechanical? no (only cited).
- Our research: HUNT_H15.md: random entries with the same sizing had P(ruin) 49% over 2,000 replays; HUNT_FINAL.md:
  Rs 5,000/day with fixed lots from Rs 1 lakh: NO.

### Appendices
- A: essential supremum. B: why every positive continuous price is of the model's form, and why a stray singular part
  would be an arbitrage. C: a proof for Chapter 6. D: optimal stopping and the Snell envelope (used for American
  options). E: the Clark formula for the hedge of path-dependent claims (Malliavin calculus). Theory only.

## Performance claims
None. The book makes no claim about trading profits, win rates or returns and contains no data. Its empirical remarks
are second-hand and cautious: CAPM risk premia and the consumption CAPM fit data poorly; the lognormal law is a
reasonable but beatable fit; constant-volatility Black-Scholes should not be used blindly; transaction costs matter a
lot for hedging (Figlewski's simulation). No evidence is given beyond the citations.

## Risk, sizing and psychology
- Sizing is the book's one practical subject: growth-optimal (log) investing maximises long-run growth almost surely,
  but the authors cite Merton and Samuelson's warning that it is not the best choice for a finite horizon.
- A floor on wealth is handled by investing only the cushion above the floor's present value (method 3).
- Constraints (no shorting, no or costly borrowing) clip the optimal fraction rather than scale it (method 5).
- Doubling (martingale) betting needs unbounded credit; models must bound losses from below.
- No psychology.

## How it relates to our research
- Sizing: agrees with HUNT_H15.md that Kelly-type sizing maximises growth in theory, and its own caveats (known
  parameters, long horizon, other goals) explain why H15's in-sample Kelly fraction (BANKNIFTY 1.29 against 0.48 and 0.14
  on other windows) was unstable and why HUNT_FINAL / h36 chose fixed lots. Nothing here contradicts that choice.
- Option selling: Chapter 5 agrees with DEEP_OPTIONS_SELLING.md that a constrained (one-lot, no-futures-hedge) seller
  cannot hedge; a sure hedge collapses to covered or cash-secured positions. R103 (0 of 24 defined-risk passes) is not
  touched by the theory.
- Direction: method 1 (drift drops out of the option price) is the theoretical side of R16/R88: an option buyer pays
  for volatility and must supply the direction edge that our tests could not find.
- Volatility: the book's only volatility remark (constant volatility should not be trusted) is consistent with R102
  (India VIX over-states NIFTY realised volatility on 81% of days); it adds nothing testable. Bennett's practical
  volatility trading is in `bennett-trading-volatility.md`.
- Costs: Chapter 2 and 3 notes agree that costs dominate hedging and rebalancing decisions, as HUNT_H24 /
  baselines.yaml costs do in every option-buying study of ours.
- GEX (R28/R36), IV1 option-market signals, neural nets (R41), cross-index pairs (R72), H39 basis: not covered beyond
  the forward/futures theory of Chapter 2.3.

## What is new to us
- Sizing on the cushion above the account floor (method 3), which shrinks lots during a drawdown and stops at the
  account stop; risk-lowering, so allowed in shadow on live and directly on paper after the gates. Not tested.
- Clipping (not rescaling) the Kelly fraction when a cap binds, and the "do not lever" band when borrowing costs more
  (method 5).
- The goal problem (maximise the chance of reaching a target by a date) as the honest frame for a "Rs X per day" goal.
- No-trade bands for rebalancing lots and for any future delta hedge (method 6).
- The lower/upper hedging price band as the right way to read an option's "fair" value when we cannot hedge.

## Could not read
- Every displayed equation and most inline formulas are garbled by OCR; the text above relies on the prose around them
  and on standard results (the Leland form in method 6 is the standard one, not read from the book).
- Some lines lose their first characters at the left edge (preface, PDF pages 5-10; the openings of Chapters 2 and 3).
- On PDF pages 49, 76-77 and 146 the two halves of the spread are interleaved line by line; the prose could still be
  followed.
- No page was rotated or blank; no figures were missed (the book has essentially none). PDF pages 196-212 are the
  references, 213-214 the index, 215 the series list.

## R104 results (25 Oct 2026)

Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in `research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:

- `book-fmkaratzas-01`: tested (R104): MM: drawdown cut -3%, net cut 8% design (fail)
