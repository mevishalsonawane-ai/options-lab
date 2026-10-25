# Financial Mathematics: An Introduction to Derivatives Pricing - Lane P. Hughston and Christopher J. Hunter (2000)

Source: financial-mathematics.txt, 234 pages (King's College London lecture notes, copyright 2000, preface dated January
1999, from L.P. Hughston's 1997 lectures; appendix D by R.F. Streater), read in full on 25 Oct 2026 (text layer: all 22
chapters, appendices A-G including every worked solution, the reprinted 1885 Economist article and the KCL 1998 exam
with solutions; figures and many formulas come through garbled - see "Could not read").
Book claims are the authors', not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A short university course that derives option prices from the single idea that a riskless profit from nothing cannot last.
It starts with an FX forward, then a coin-flip "casino", then binomial trees, building the risk-neutral measure step by step.
It moves to continuous time: Brownian motion, the lognormal price model, Ito's lemma and the Black-Scholes equation.
It solves that equation (via the heat equation and again via martingales and Girsanov) for calls, puts and binaries.
It closes with Greeks, delta and gamma hedging, put-call parity, dividend yields, multi-asset markets and completeness.

Every number in the book is a classroom example (dollar or sterling toy prices, a few 11 Jan 1999 FTSE and LIFFE quotes);
there is no market data, no backtest and no trading rule with an entry signal. It is a pricing primer. Its use for us is
as the theory behind the app's Black-Scholes repricing, Greeks, the chain consistency checks and Jarvis explanations.
Bennett's desk-side book (research/books/bennett-trading-volatility.md) covers the trading of volatility; this book is the
mathematics underneath it, and much of it overlaps research/books/fm-capinski-math-finance.md and fm-binomial-models.md.

Our data, for the mapping: NIFTY weekly and BANKNIFTY monthly index options (European, cash-settled), a 1-minute chain of
the nearest expiry within ATM +-10 strikes with IV and OI (last prices, no bid/ask), index spot and futures bars, India
VIX daily, MCX futures and options on futures, Delta Exchange crypto options. Costs: about Rs 70 (NIFTY) / Rs 76
(BANKNIFTY) per option round trip (DEEP_OPTIONS_SELLING.md).

## Methods, rules and setups (chapter by chapter)

### Ch. 1 - Introduction: markets and payoffs
Stocks, bonds and credit ratings, money-market accounts, commodities (with storage and delivery costs), currencies with
bid/offer quotes; derivatives as side-bets: European and American options, index options settled in cash, forwards and
swaps; long and short. Two uses: insurance (hedging) and leverage (speculation, with correspondingly larger losses). A
dealer who sells a derivative hedges it and earns only the fee added on top of the fair price. Payoff diagrams of call,
put and forward; exercises build a forward from a long call and short put, a call spread and a butterfly from calls. The
FTSE examples (solutions to Ex 1.5) show market price above intrinsic value: a 6000 American call at GBP 1,265 against
an intrinsic GBP 850 with four days left, a 6025 European call at GBP 1,085 against GBP 600, puts at GBP 295 and GBP 340
with zero intrinsic - time value.

### Ch. 2 - Arbitrage pricing (the FX forward)
Shows that setting the forward equal to the expected future spot ("expectation pricing") is wrong: two riskless routes
from pounds to dollars must end equal, so the forward is spot grown at the interest-rate difference. Defines a trading
strategy as holdings of each asset, shows strategies can be added and negated, defines replication, and prices a currency
swap as the discounted gap between the forward and the agreed rate (a forward struck at the forward price costs nothing).
The lesson the authors repeat throughout: the real probabilities of up and down do not enter the price. They also say
no-arbitrage is not a law of nature and is not always obeyed by markets; it works like natural selection on dealers.

### 1. No-arbitrage pricing by replication and the risk-neutral measure (Ch. 2-8, 13-15, 19-20, 22)
- What it computes: the one price of a derivative that leaves no riskless profit: build the stock-and-cash portfolio that
  pays the same in every state; its cost is the price. Equivalently, the price is the discounted average payoff under the
  "risk-neutral" probabilities, the ones under which the underlying earns only the risk-free rate (so price divided by the
  money-market account is a martingale). In continuous time the same result appears three ways: solving the Black-Scholes
  equation (Ch. 15), a change of measure (Ch. 19) and the Girsanov drift shift (Ch. 20).
- Inputs: today's price, the possible next prices (tree) or the volatility (continuous time), the risk-free rate.
- Key consequence: the drift (expected return) of the underlying cancels out of every option price (Ch. 13: "the terms
  involving mu ... cancel"); the exam (App. F.3) states it as: derivatives can be priced without knowing the asset's rate
  of return. Ex 5.3: with real heads probability 0.8 or 0.3 the derivative costs the same 2.97.
- How we could use it: it is the theory under the app's Black-Scholes repricing and our "options already price the move"
  results. As a Jarvis explanation: a directional view changes what a buyer expects to earn, not what the option is worth.
- Signal source: volatility / options structure (a pricing tool, no signal). Maps to: NSE index options yes, MCX options
  on futures yes, crypto options yes.
- Mechanical? yes (a formula).
- Our research: consistent with R89 (FINDINGS.md, "A strategy from R88"): straddles bought when a big move was forecast
  lost because the options already priced the move (out of sample 2,214 straddles, -Rs 4.4 lakh, 0 of 18 versions
  positive), and with IV1 (FINDINGS.md, "Option-market signals alone"): option-only models lost on all 5 indices.

### Ch. 3 - A simple casino
A coin-flip bet (stake 2 dollars returning 3 or 1.50) stands for a stock. A dealer who sells a side-bet hedges with a
fraction delta of the basic bet, delta = (payoff if up - payoff if down) / (up - down), which makes his outcome the same
either way; no-arbitrage then fixes the price as a weighted average of the two payoffs with weights that depend only on
the stake and outcomes. Defines risk-averse, risk-neutral and risk-preferring players by whether the stake is below, equal
to or above the expected payoff, and requires up > stake > down (else arbitrage).

### Ch. 4 - Probability systems
Sample space, event space, probability measure, random variables and expectation, with the point that the same coin
tosses can carry many probability measures (fair coin vs a 1/3-2/3 coin: expected heads 1.5 vs 1). Theory.

### Ch. 5 - Back to the casino
Names the pricing weights as the risk-neutral measure, adds a non-zero interest rate (price = discounted risk-neutral
average; the hedge ratio is unchanged by the rate), and introduces the discount factor. Ex 5.3 (worked): stake 100,
outcomes 105 / 95, growth factor 1.01, risk-neutral up-probability 0.6, a 100-strike call worth 2.97, hedge ratio 0.5, and
the hedged book pays 47.50 whichever way the coin lands. A short aside explains gambling as paying for a "convenience
yield" (fun), which is why expectation pricing differs per player and arbitrage pricing does not.

### Ch. 6-7 - The binomial model and N-period trees
Re-reads the casino as a one-period stock market; general "bushy" trees with conditional probabilities; a money-market
account; the one-period replicating portfolio (bonds plus delta shares); risk-neutral probabilities from the one-step
forward price. The authors note that because a derivative can be replicated, investors buy it only because it is cheaper
to trade and maintain than the replicating portfolio. N periods by backward induction. The worked tree (100 -> 105 / 95 ->
108 / 102 / 98 / 92, 1% a month) prices a forward (1.97), a 100 call (3.29), a 100 put (1.58), a path-dependent sum of
puts struck at 97 (1.45), a digital paying 10 above 100 (5.88), and a 104 call as European (1.59) and American (1.78; the
American is exercised early in the up state). Ex 7.2 shows two different trees satisfying the same martingale conditions.

### 2. Binomial (recombining) lattice pricing (Ch. 9-10)
- What it computes: option values on a recombining tree with up factor u and down factor d; risk-neutral up-probability
  (e^r - d)/(u - d); the price is the discounted binomial-weighted payoff; early exercise is handled by taking at each node
  the larger of exercising and holding. Ch. 10 shows that with log steps of plus or minus sigma times the square root of
  the step, the tree tends to the lognormal model as steps shrink. Worked: 100 with u 1.05, d 0.95, growth 1.01, three
  steps, 100 call = 5.29.
- Inputs: spot or futures, strike, steps, rate, volatility.
- How we could use it: only needed for American-style options (none on NSE index options) or path-dependent payoffs; for
  European index and MCX options the closed form is enough. A tree is a transparent way to show Boss how an option
  price is built.
- Signal source: volatility / options structure. Maps to: NSE yes (as a check), MCX yes, crypto yes.
- Mechanical? yes.
- Our research: not used by us; the app and R103 reprice with the closed form (DEEP_OPTIONS_SELLING.md, PREREG
  Amendment 2: stale legs repriced with Black-Scholes at their last IV).

### Ch. 8 - Martingales and conditional expectation
Stochastic processes, filtrations (what is known at each time), adapted processes (a value at time 1 may not depend on
what happens at time 2), conditional expectation, martingales, and the result that discounted stock and derivative prices
are martingales under the risk-neutral measure. Theory, but the "adapted" condition is exactly our look-ahead guard: a
signal may only use information available at the bar it is computed on.

### Ch. 11 - Continuous-time (Wiener) model
### 3. Lognormal price model and square-root-of-time scaling (Ch. 10-12, App. B.6)
- What it computes: the price as geometric Brownian motion: the log price is normal with mean growing at drift minus half
  the variance and variance sigma squared times time, so the typical move over a horizon is sigma times the square root of
  the horizon; the expected price grows at the drift; the variance of the price is computed in the solutions.
- Inputs: volatility, horizon (drift irrelevant for pricing).
- How we could use it: converting India VIX or ATM IV into an expected move for a window, simulating paths for Monte Carlo
  baselines. The authors say random motions are uncorrelated "to a first approximation" (the market has no memory) and
  that little is understood about correlations - the same as our direction findings.
- Signal source: volatility / options structure (a model). Maps to: NSE yes, MCX yes, crypto yes (24x7 clock).
- Mechanical? yes.
- Our research: R16 (HUNT_R16_PREDICTABILITY.md) found the next 30 minutes' range predictable, error 14-15% smaller than
  the best plain baseline (VIX), because plain time-scaled VIX ignores the time-of-day shape; direction was not predictable
  (AUC 0.509-0.536). The lognormal model has no intraday seasonality and no fat tails, so it is a baseline only.

### Ch. 12 - Stochastic calculus
Stochastic differential equations, the stochastic integral read as the gains of a trading strategy, Ito's lemma (dW
squared equals dt) and the product and quotient rules. Theory.

### Ch. 13-14 - The arbitrage argument and replication portfolios
### 4. Black-Scholes equation, delta and the common "market price of risk" (Ch. 13-14, App. F.3)
- What it computes: holding one option against a quantity of the underlying chosen to cancel the random term gives a
  riskless book that must earn the risk-free rate. Two results follow: (a) any two prices driven by the same noise must
  offer the same excess return per unit of volatility ((mu - r)/sigma, the market price of risk); (b) the option value
  satisfies the Black-Scholes equation, in which the drift does not appear. Ch. 14 shows the option equals delta shares
  plus a bank position, delta = dC/dS, and that the strategy is self-financing. A bank that sells an option and runs this
  replica carries no risk and earns only what it charges above the fair price; clients buy options because the replica
  needs continuous attention.
- How we could use it: result (a) is the cleanest argument for why option buying on a coin-flip direction has no edge
  before costs: an option's expected excess return is the underlying's excess return scaled up by leverage, so if the
  index has no predictable intraday drift, neither does the option, and costs and decay come on top. Also: delta is the
  number of index units one option moves like (for sizing and the position screen).
- Signal source: volatility / options structure. Maps to: NSE, MCX, crypto.
- Mechanical? yes.
- Our research: agrees with R47 (HUNT_R47_RELAXED_OPTION_BUYING.md): 387,392 buying variants searched, none beat random
  entries after correction (best q = 0.50), and with R16 above. Not a separate test.

### Ch. 15-16 - Solving Black-Scholes; call and put prices
### 5. Black-Scholes call and put, written on the forward (Ch. 15-16, 18, App. B.7)
- What it computes: any European payoff's value as the discounted average of the payoff over a lognormal terminal price
  whose mean is the forward (an integral that a computer can evaluate for any payoff, not only calls). For a call: discount
  factor times [forward x N(h+) - strike x N(h-)], with h+/- = (ln(forward/strike) +/- half variance) / (sigma root T); the
  put follows. The book always writes it with the forward, so with an index or commodity future plugged in as the forward
  it is the Black-76 form. Worked: a 6-month 115 call on 100 at 20% volatility and 5% rate is worth 1.76.
- Inputs: futures (or spot x carry), strike, time, rate, volatility.
- How we could use it: already used (app Black-Scholes repricing, GEX gamma, IV backing-out). Plugging the same-expiry
  future in as the forward removes the guessed carry term; for MCX options on futures this is the right model as written.
  The integral form is the template for Monte Carlo pricing of odd payoffs.
- Signal source: volatility / options structure. Maps to: NSE yes, MCX yes (on futures), crypto yes.
- Mechanical? yes.
- Our research: IV in HUNT_H29.md is "Black-76 on the synthetic forward, r = 0, in trading time"; DEEP_OPTIONS_SELLING.md
  uses a Black-Scholes illustration at NIFTY 25,000 and 15% IV with a flat 6.5% rate; R103's stale legs were repriced
  this way.

### 6. The Greeks of a call (Ex 16.1 and its solution)
- What it computes: delta = N(h+); gamma, vega, theta and rho in closed form (the solution derives all five from one
  general derivative). Delta is the hedge quantity, gamma how fast it changes, vega the sensitivity to volatility, theta
  the time decay, rho the rate sensitivity.
- How we could use it: Greek P&L attribution for every paper option trade (move x delta + half gamma x move squared +
  theta x time + vega x IV change), and as the input to GEX.
- Signal source: volatility / options structure. Maps to: NSE, MCX, crypto.
- Mechanical? yes.
- Our research: R28 / R36 GEX studies use Black-Scholes gamma (HUNT_R28_GEX.md); a Greek attribution of paper trades is
  not built.

### Ch. 17 - More topics: binaries, Greeks and hedging, parity
### 7. Binary (digital) options and the risk-neutral chance of finishing in the money (Ch. 17.1, Ex 17.1)
- What it computes: a binary call paying 1 if the price ends above the strike is worth the discount factor times N(h-), a
  binary put the discount factor times N(-h-), and the two together are worth one discounted unit. So N(h-) is the
  option market's own (risk-neutral) probability of finishing above the strike.
- How we could use it: a Jarvis context number ("the chain prices a 30% chance NIFTY ends above 25,200"), computed from
  ATM IV or, better, from a tight call spread. NSE has no listed binaries; a narrow call spread approximates one.
- Signal source: option chain as context (an implied probability, not a direction signal). Maps to: NSE yes, MCX yes,
  crypto yes.
- Mechanical? yes.
- Our research: not tested by us as a forecast; DEEP_NIFTY_BANKNIFTY.md section 3.1 measures how often the realised day
  beats the 09:20 straddle (NIFTY 14.2% of days at the close), which is the empirical counterpart.

### 8. Delta hedging with discrete rehedging, and gamma hedging with a second option (Ch. 17.2, Ex 17.2-17.3)
- What it computes: a delta-neutral book has no first-order exposure; continuous rehedging is unrealistic because every
  trade costs money, so books are rehedged from time to time and drift off neutral in between. Setting gamma to zero with
  a second option keeps delta small near the current price and reduces how often one must rehedge; it needs three
  instruments (the underlying plus two options). Worked: hedging a short binary call (strike 110, price 100, 20% vol, 5%
  rate, one year) with stock and a 120 call: call delta 0.287192, call gamma 0.0170369, hold 0.00668119 calls and
  0.0160703 shares. Any option with non-zero gamma can serve as the second instrument, e.g. a different-strike call.
- How we could use it: explaining why a short straddle is dangerous (a seller is short gamma), and why one-lot traders
  cannot delta-hedge finely.
- Signal source: volatility / options structure. Maps to: NSE in principle; MCX thin; crypto options can be hedged in small
  size with perpetuals.
- Mechanical? yes (rehedge on a delta band).
- Our research: DEEP_OPTIONS_SELLING.md explains why a one-lot seller cannot delta-hedge and why R103 tested wings and
  stops instead; R103 (HUNT_R103_OPTIONS_SELLING.md) found no selling rule passes (0 of 24), and the naked 09:20 straddle
  had a worst day of -Rs 65,483 (DEEP_OPTIONS_SELLING.md). Delta-hedged or gamma-hedged selling: not tested by us (see
  bennett-trading-volatility.md for the desk view).

### 9. Put-call parity, with dividend yield or foreign rate (Ch. 17.3, Ex 17.4-17.5, 18.1, App. F.6)
- What it computes: call minus put at the same strike and expiry equals spot minus the discounted strike (with a dividend
  yield or foreign rate, spot is discounted at that yield first; for an index, equivalently, the discounted forward minus
  the discounted strike). The exam solution spells out the arbitrage: if call minus put is rich by q, sell the call, buy the
  put, buy the underlying, and q grown at the rate is locked in whatever happens; reverse it if the gap is negative.
- How we could use it: as a filter on our 1-minute lake (a strike whose call minus put strays far from the futures-implied
  value almost certainly has a stale print) and to back out a clean implied forward for repricing. As a trade it needs
  executable quotes we do not have.
- Signal source: volatility / options structure (consistency check); it reads option premiums as a data check, not a
  direction signal. Maps to: NSE yes (European, cash-settled), MCX options on futures yes, crypto yes.
- Mechanical? yes.
- Our research: R103 (DEEP_OPTIONS_SELLING.md): six BANKNIFTY tests passed only because far wings sat at stale prices;
  repriced they all lost (-Rs 2.9 to -Rs 3.3 lakh). HUNT_LAKE_STALE_PRINTS.md flags 9,646 stale one-minute prices of 35.4
  million by repeated-print runs; a parity cross-check is not in our cleaner - not tested by us. Listed as rule 1 below.

### Ch. 18 - Continuous dividend model
### 10. Forward / futures fair value with a carry yield (Ch. 2, 18, App. F.4)
- What it computes: forward = spot x e^((r - q) T), q being a continuous dividend yield (a good approximation for an index,
  the authors note) or a foreign interest rate; the hedge ratio is unchanged by dividends; the modified Black-Scholes call
  uses this forward. The exam adds that the forward differs from the expected future price because it is the no-arbitrage
  price, which never depends on the drift.
- How we could use it: NIFTY / BANKNIFTY futures premium over spot as an implied financing rate; MCX futures carry; crypto
  futures basis. Mostly an input for the chain (parity, repricing).
- Signal source: volatility / options structure (carry); as a direction filter it would be underlying price. Maps to: NSE
  yes, MCX yes, crypto yes.
- Mechanical? yes.
- Our research: HUNT_H29.md uses the synthetic forward from options; H39 (HUNT_H39.md) found heavyweight stock options move
  with the index, not before it. Cash-futures arbitrage: not tested by us.

### Ch. 19-20 - Risk-neutral valuation and the Girsanov transformation
Defines the market price of risk process, a density martingale and the change of measure under which price over bank
account is a martingale for any traded asset; Girsanov's theorem (proved with characteristic functions) shows the shifted
Brownian motion is a standard one under the new measure, which recovers the Black-Scholes formula. Theory; no extra
practical content beyond method 1.

### Ch. 21-22 - Multiple asset models
### 11. Covariance matrix, zero-volatility portfolio and factor risk premia (Ch. 21-22)
- What it computes: n assets driven by N independent noises through a volatility matrix; the instantaneous covariance and
  correlation matrices; portfolio variance as the weights' quadratic form (never negative); a portfolio with zero
  volatility must earn the risk-free rate, which forces each asset's expected excess return to be a sum of factor risk
  premia times its exposures; the market is "complete" (every derivative can be hedged) only when the volatility matrix
  has full rank, which needs at least as many traded assets as noises. With dividends each asset's risk-neutral drift is
  the rate minus its yield. Multi-asset, path-dependent derivatives are priced as discounted risk-neutral averages.
- How we could use it: portfolio variance of several paper arms from their correlation (not doubling the same risk); a
  beta hedge of BANKNIFTY with NIFTY; and the incompleteness point as a Jarvis explanation of why volatility and jump risk
  cannot be hedged with the index alone (one reason skew exists).
- Signal source: none (risk and portfolio maths). Maps to: NSE, MCX, crypto.
- Mechanical? yes, but covariances are noisy.
- Our research: R72 (HUNT_R72_CROSS_INDEX.md): 77% of Breadth Donchian's trades are Donchian's own (daily correlation
  0.80), so it should replace, not join, the Donchian arm - the book's point that correlated holdings add no
  diversification. A covariance-based arm sizer: not tested by us.

### Appendices A, B, D, G
A glossary (two entries, "arbitrage" and "martingale", are blank in the original), a formula sheet (normal moments, Ito,
geometric Brownian motion, Black-Scholes call and put with and without dividends, binomial distribution, central limit
theorem; it notes the dividend put was still to be derived), Streater's probability reminders (conditional probability
as updating on arriving price information, filtrations, martingales, the tower law), and a bibliography (Baxter-Rennie,
Hull, Jarrow-Turnbull, Oksendal, Wilmott; Black-Scholes 1973, Merton 1973, Cox-Ross-Rubinstein 1979, HJM 1992). Theory.

### Appendix C - Solutions
Every exercise is worked; the numbers quoted above come from here (the 2.97 casino call, 47.50 hedged payoff, the tree
prices, the 5.29 lattice call, the 1.76 Black-Scholes call, the Greek formulas and the gamma-hedge quantities). Also: a
6.1% rate paid monthly is 6.08% continuously compounded; a bond paying 107 in a year is worth 101.78 at 5% and 100.77 at
6% (bond prices fall when rates rise).

### Appendix E - "The Virtues and Vices of Options" (The Economist, 2 May 1885)
### 12. Options as protection, not as a naked bet (App. E)
- What it says: the dabbler who just buys a put or call on his view and "sits upon it" plays a losing game: inexperience,
  commission and the option price multiply the odds against him "ten fold"; loss is limited but the prospect of profit is
  "reduced almost to the vanishing point", especially when brokers sell options on stocks unlikely to move as much as the
  premium. Used properly - to protect another position (a bear seller who buys a call) - options limit loss and still
  leave a fair chance of profit. Limiting loss also tempts timid people into speculation they would otherwise avoid.
- Entry / stop / exit: not stated (an opinion piece).
- Market and timeframe written for: 1880s London, Paris and New York stock options.
- Signal source: none / discretionary. Maps to: NSE (our option buying), MCX, crypto.
- Mechanical? no.
- Our research: agrees with our option-buying record: R47 found no buying rule beats random entries after correction, and
  costs plus decay kill most buying rules (FINDINGS.md). The protective use (an option bought against an existing futures
  position) is not tested by us.

### Appendix F - KCL 1998 exam
### 13. Quick at-the-money-forward value and deep in-the-money limit (App. F.5)
- What it computes: an at-the-money-forward call is worth the discounted spot times (2N(half sigma root T) - 1), which for
  small sigma root T is about 0.4 x sigma x root T of spot, so an ATM straddle is about 0.8 x sigma x root T of spot; a
  deep in-the-money call is worth about the discounted forward minus the discounted strike (it behaves like a future).
  Also: N(h+) equals one half when spot is the discounted strike lowered by half the variance.
- How we could use it: a mental check that converts the 09:20 straddle into an implied daily volatility and back (straddle
  / spot divided by 0.8 is sigma root T), and an explanation for Jarvis of why deep ITM options carry little time value.
- Signal source: volatility / options structure. Maps to: NSE, MCX, crypto.
- Mechanical? yes.
- Our research: DEEP_NIFTY_BANKNIFTY.md section 3.1: the NIFTY 09:20 ATM straddle is a median 0.96% of spot (n 1,222
  days), BANKNIFTY 1.49%; the rule of thumb turns these into implied moves. India VIX is above NIFTY's next-21-day realised
  vol on 81% of days (DEEP_NIFTY_BANKNIFTY.md section 2.2).

## Performance claims
None about trading profits. All prices, hedges and probabilities are computed on invented numbers (the 5.3 casino, the
6.12 tree, the 9.3 lattice, the 13.2 call). The only "claims" are mathematical: no-arbitrage prices, parity, and that a
hedged book is riskless under the model's assumptions (continuous trading, no costs, constant volatility, lognormal
prices). The authors themselves warn that no-arbitrage is not always obeyed (Ch. 2.6) and that continuous rehedging is
not realistic because of costs (Ch. 17.2). The reprinted 1885 article claims naked option buying is "a losing game", with
no evidence beyond the writer's experience.

## Risk, sizing and psychology
- An option buyer can lose at most the premium; the seller's loss can be much larger (Ch. 1, Ex 1.4, Ex 1.7).
- Derivatives used for leverage magnify losses as well as gains (Ch. 1.2).
- Hedges need frequent rebalancing that costs money; gamma hedging reduces the rebalancing (Ch. 17.2).
- Portfolio variance comes from the covariance matrix; a riskless combination must earn only the risk-free rate (Ch. 21).
- The 1885 article: options used as protection are prudent; naked option buying is gambling, and limited loss lures
  timid people into it.
- No Kelly sizing, no drawdown rules, no VaR, no Monte Carlo of strategies, no psychology beyond the 1885 piece.

## How it relates to our research
- Agrees: options already price the move and the drift does not enter the price (Ch. 13, App. F.3). R89 straddles on size
  forecasts lost (0 of 18), IV1 option-only signals lost on all 5 indices, and direction is a coin flip (R16: AUC
  0.509-0.536), so neither leg of the "beat the option" bet has worked for us.
- Agrees: equal market price of risk (Ch. 13) means an option inherits the underlying's (near-zero) intraday edge; R47 found
  no buying rule that beats random entries after correction.
- Agrees: hedging is costly and imperfect (Ch. 17.2); R103's short straddle carried fat tails (worst day -Rs 65,483) and the
  defined-risk versions failed after costs (0 of 24).
- Agrees: correlated holdings do not diversify (Ch. 21); R72's Breadth Donchian vs Donchian (correlation 0.80).
- Agrees: the "adapted process" condition (Ch. 8) is our look-ahead guard.
- Not tested by us: a parity / convexity check in the lake cleaner (it targets the stale-wing failure of R103), the
  chain-implied probability N(h-) as Jarvis context, a covariance-based arm sizer, protective options on futures arms.
- Already covered more deeply elsewhere: research/books/bennett-trading-volatility.md (volatility trading),
  fm-capinski-math-finance.md (hedging case studies, VaR, Markowitz), fm-binomial-models.md (trees).

## What is new to us
- Black-Scholes written on the forward (Ch. 16, 18): plug the same-expiry index or MCX future in directly and the carry
  guess (flat 6.5% in DEEP_OPTIONS_SELLING.md) disappears; this is Black-76, as H29 already uses for IV.
- The chain's implied probability of finishing beyond a strike, N(h-) or a tight call spread (Ch. 17.1), as a plain-
  English context number for Jarvis.
- The 0.4 x sigma x root T rule of thumb (App. F.5) linking the ATM straddle, IV and the expected move.
- Put-call parity per strike against the future as a cheap stale-print detector (Ch. 17.3, App. F.6).
- The equal-market-price-of-risk argument (Ch. 13) as a clean explanation for Boss of why option buying cannot beat a
  coin-flip underlying before costs.

## Could not read
All figures (payoff diagrams 1.1-1.5 and C.1, strategy diagrams 2.3-2.6, tree diagrams, Wiener paths 11.1-11.4, price
curves 16.1-16.2) came through as broken text; only captions and axis labels were readable. Many formulas are broken
across lines in the text layer (fractions, integrals, sub- and superscripts) but each could be reconstructed from the
prose and the worked numbers. A few printed formulas contain typos in the original (e.g. the FX parity in the Ex 17.4
solution, the "kT" in rho, a garbled sign in the binary-call gamma); they do not affect anything above. Nothing else was
unreadable.
