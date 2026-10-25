# Mathematics of Financial Markets - Robert J. Elliott and P. Ekkehard Kopp (2005, 2nd edition)

Source: mathematics-of-financial-markets-elliot.txt, 356 pages, read in full on 25 Oct 2026 (text layer, pdftotext; all
11 chapters; the bibliography and index skimmed as references only). Displayed formulas mostly came through as scrambled
symbols; the prose around them was readable, and the 7 figures are not in the text - see "Could not read".
Book claims are the authors', not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A graduate mathematics textbook on pricing and hedging derivatives by no-arbitrage, written for readers who know measure theory.
Chapters 1-5 work in discrete time: one-step and multi-step binomial models, martingale measures, the two fundamental theorems, American options.
Chapters 6-8 do the same in continuous time: Ito calculus, Girsanov, Black-Scholes, barrier options, the Greeks, the American put.
Chapter 9 covers forwards, futures, change of numeraire and interest-rate models (Vasicek, Hull-White, CIR, HJM, a Markov-chain model).
Chapters 10-11 cover optimal consumption and investment (log utility gives the Kelly / Merton fraction) and risk measures (VaR, CVaR, coherent risk).

## How to read this for IraAlgo
This is a theory book. It has no data, no backtests, no costs and no trading rules; every model assumes a frictionless
market (no costs, unlimited shorting, continuous trading). Its value for us is (a) the exact pricing and Greek formulas
the app already uses to reprice options (Black-Scholes / Black-76), (b) a handful of theorems that explain WHY our
results look the way they do (no exit rule can create an edge on a fair-game price; options already price the expected
move), (c) tools we have not used: first-passage (touch) probabilities, expected shortfall instead of a single loss
number, a Markov-chain regime model with filtering, and the Ornstein-Uhlenbeck mean-reversion model. For practical
volatility trading (skew, term structure, variance swaps, dispersion) see `research/books/bennett-trading-volatility.md`;
this write-up does not repeat it.

## Methods, rules and setups (chapter by chapter)

### Ch. 1 Pricing by arbitrage (pp. 1-26)
Introduces forwards, futures, swaps and options, then prices a claim by building a portfolio of stock and cash that
copies its payoff. In a one-step up/down model the copying portfolio is unique, its stock holding is the option's delta
(change in option value over change in stock value) and the price equals the discounted average payoff under
"risk-neutral" probabilities, which do not depend on how likely the investor thinks up or down is. With three possible
outcomes the copy fails and only a price range exists. Builds the multi-step Cox-Ross-Rubinstein (CRR) tree and its
call formula, and model-free bounds: a call is worth at least spot minus the discounted strike and at most spot; an
American call on a non-dividend stock is never exercised early. A worked example shows a call in the one-step model
can be worth LESS with a wider spread of outcomes (option value is not always increasing in "volatility" there).

#### 1a. Put-call parity as an implied forward and a stale-quote check (Ch. 1.1, 1.6, 2.5, 7.6)
- What it computes: call minus put at the same strike and expiry equals spot minus the discounted strike. Rearranged,
  the chain gives an implied interest rate (the book) or an implied forward K + C - P (our use). The book notes that a
  violation is a short-lived arbitrage and that traders check put and call quotes against the money-market rate this way.
- Inputs: ATM call and put mid prices, strike, time to expiry.
- How we could use it: (1) the forward to price options with Black-76 (see 9a); (2) a data-quality guard: a leg whose
  parity gap is far outside the bid-ask band is probably a stale print (exactly the R103 problem); (3) not a signal.
- Signal source: option chain as context (a pricing / data check, no direction). Maps to: NSE index options yes (we
  already compute the synthetic forward); MCX options yes (forward = the future); crypto options yes.
- Mechanical? yes.
- Our research: h29 and h39 found the synthetic forward "leads" the index by IC +5 to +13% over 5-30 minutes, but it is
  the index catching up to options that have already moved, so a buyer cannot use it (`X2_CONNECTIONS.md` row 5,
  `HUNT_H29.md`). R103's six design passes came from wing prices left stale outside the ATM +-10 window; repriced with
  Black-Scholes at the last IV they all lost (`HUNT_R103_OPTIONS_SELLING.md`). A parity-based stale-leg flag was not
  tested as such.

#### 1b. Minimum-variance hedge ratio (Ch. 1.3)
- What it computes: when a claim cannot be copied exactly, the stock holding that minimises the variance of the hedging
  error is the regression slope cov(claim, price change) / var(price change). The risk left over is the claim's variance
  times (1 - correlation squared), so only perfect correlation removes all risk.
- Inputs: paired changes of the position to hedge and of the hedge instrument.
- How we could use it: lots of NIFTY futures (or NIFTY options) that best offset a BANKNIFTY or SENSEX position, and the
  risk that cannot be hedged; a sizing / hedging tool, not a signal.
- Signal source: none (hedging arithmetic). Maps to: NSE index yes; MCX yes (e.g. GOLDM against GOLD); crypto yes (ETH
  against BTC).
- Mechanical? yes.
- Our research: not tested. Nearest facts: NIFTY's same 5-minute candle explains 51-90% of the other indices' candles
  (R88, FINDINGS.md); Breadth Donchian's daily P&L correlates 0.80 with Donchian's (R72).

#### 1c. Option elasticity: an option is a levered underlying (Ch. 1.4, 7.10)
- What it computes: elasticity = (spot x delta) / option price. For a call it is at least 1, so the option's return
  volatility is elasticity times the stock's, and its expected excess return is elasticity times the stock's excess
  return. Under risk-neutral pricing the option's expected return is just the riskless rate.
- How we could use it: when sizing an option buy, the rupee risk is the elasticity-scaled index risk; a cheap OTM option
  is the most levered and decays the most when nothing happens. It explains why a direction edge in the underlying is
  needed first - leverage multiplies an edge of zero into zero minus costs.
- Signal source: none (risk arithmetic on the bought option; the signal stays on the underlying). Maps to: NSE, MCX,
  crypto options yes.
- Mechanical? yes.
- Our research: consistent with R89: options already price the expected move, gross before costs is about zero
  (FINDINGS.md R89, 0 of 18 straddle versions profitable on 4,302 design straddles). MCX_OPTIONS.md: OTM2-3 pays most
  per rupee when right but costs most per flat hour.

### Ch. 2 Martingale measures (pp. 27-56)
Sets up a general discrete market: information that grows over time, strategies chosen with past information only, and
"self-financing" (value changes only through gains). Key fact: if discounted prices are a martingale (a fair game) under
some probability, then the gains of ANY strategy built from past information have zero expected value, so no arbitrage
exists, and every copyable claim has one price - its discounted expected payoff under that probability. Superhedging:
the seller's price (cheapest strategy that always covers the payoff) is at or above the risk-neutral price, and the
buyer's price is at or below it; they meet only for copyable claims. Shows call-put parity follows from the martingale
property and reads company equity as a call on the firm. Ends by letting the binomial step shrink: CRR prices converge
to Black-Scholes (via the central limit theorem), and the price does not contain the stock's expected return.

#### 2a. Binomial tree pricer (Ch. 1.5, 2.6, 2.7, 5.5, 8.5)
- What it computes: option value by stepping back through an up/down tree with up = exp(sigma x sqrt(step)), down = its
  inverse, and risk-neutral up-probability (growth - down) / (up - down). With many steps it equals Black-Scholes for
  European options; for American options take the larger of "exercise now" and "hold" at each node.
- Inputs: spot (or future), strike, time, volatility, rate.
- How we could use it: a check on the app's closed-form pricer; needed only for early-exercise products, which NSE index
  options, MCX options and the usual crypto options (Deribit, Delta Exchange) are not (all European).
- Signal source: volatility / options structure (pricing tool). Maps to: NSE yes (European, closed form is enough);
  MCX yes; crypto yes.
- Mechanical? yes.
- Our research: we reprice with closed-form Black-Scholes / Black-76 (R103 Amendment 2; H29; MCX_OPTIONS.md). Not
  separately tested.

### Ch. 3 The first fundamental theorem (pp. 57-86)
Proves "no arbitrage if and only if a martingale probability exists", first for finite markets with a separating-
hyperplane argument, then for general discrete markets (Dalang-Morton-Willinger, Kreps-Yan). Useful insight only: an
arbitrage exists in a multi-period market exactly when it exists in some single step, i.e. when at some node the price
can only move one way next. Worked trees show the martingale probability can stop existing once the interest rate is too
high (above 12.5% in one example). The rest is functional analysis.

### Ch. 4 Complete markets (pp. 87-104)
A market is complete (every claim can be copied) exactly when the martingale probability is unique. Completeness needs
as many independent tradable assets as there are branches minus one at each node; two independent random walks already
make the model incomplete, and any information not carried by prices destroys completeness. For a claim that cannot be
copied, the no-arbitrage prices form an OPEN interval between the lowest and highest risk-neutral values; a complete
discrete market must be finite.

#### 4a. Option price as a band, not a point (Ch. 4.5, 2.4, 11.4)
- What it computes: in an incomplete market (jumps, stochastic volatility, discrete hedging - i.e. every real market)
  a model gives one point inside a band of arbitrage-free prices; a seller who must cover the worst case charges toward
  the top of the band.
- How we could use it: as context for Jarvis: a "fair value" from Black-Scholes is one number in a range; a premium above
  it is not automatically mispricing. It also explains the volatility risk premium sellers collect.
- Signal source: volatility / options structure (context only). Maps to: NSE, MCX, crypto yes.
- Mechanical? no (no number for the band edges without a further model).
- Our research: R102 measured that India VIX sits above NIFTY's next-21-day realised volatility on 81% of days (median
  ratio 1.28, n 4,507), below BANKNIFTY's on 60% (`DEEP_NIFTY_BANKNIFTY.md` 2.2); puts five strikes out are dearer than
  calls on 91% of NIFTY days (3.4).

### Ch. 5 Discrete-time American options (pp. 105-130)
American options must be super-hedged because the holder picks the exercise time. Develops stopping times (decisions that
use only the past), the optional stopping theorem, martingale convergence (via the "upcrossing" count), the Doob
decomposition (any process = start + fair-game part + predictable drift) and the Snell envelope: the value of the best
stopping rule, found backwards in time as the larger of "stop now" and "expected value of continuing". The fair American
price is the best expected discounted payoff over all stopping rules; exercise when the immediate payoff reaches the
continuation value. Ends with consumption: spending from a portfolio is affordable exactly when its risk-neutral
present value is within the starting capital.

#### 5a. Optional stopping: exits cannot create an edge on a fair game (Ch. 5.2, 5.3, 6.2)
- What it says: if a price is a martingale (no predictable drift), then stopping it at ANY rule that uses only past
  information - a stop-loss, a target, a trailing stop, a time stop, a "first touch" of a level - leaves the expected
  result equal to the start. A related inequality (the upcrossing bound) says a "buy below a, sell above b" band strategy
  on a fair or unfavourable game earns at most zero on average. Exits change the shape of the results (win rate against
  size of wins), not their mean, before costs; after costs the mean falls.
- How we could use it: as the first test of any exit idea: without a drift in the underlying, a new stop / target / lock
  can only trade win rate for payoff size; real improvement must come from the entry (a real drift) or from cutting
  costs. Explains why "high win rate" rules are not better rules.
- Signal source: underlying price (theory about exits on the underlying or the option). Maps to: all markets.
- Mechanical? yes (a check, not a rule).
- Our research: agrees. R18 tested five early-exit overlays on every arm: 0 of 60 passed (`HUNT_R18_EXITS.md`); R19
  tested six profit locks on Liquidity: 0 of 12 (`HUNT_R19_LIQUIDITY_LOCK.md`); R81 0 of 66 and R100 0 of 15 for
  Liquidity exits (FINDINGS.md). R20b found support/resistance levels no better than random lines.

#### 5b. Backward induction for an exit decision (Snell envelope) (Ch. 5.4-5.5, 8.2)
- What it computes: on a tree or grid, the value of holding = max(payoff if exited now, expected value of holding one
  more step); exit at the first time the two are equal.
- How we could use it: a principled "exit or hold" rule for an open option when we have a model of the next step (for
  example R16 / R88 size forecasts and the expiry decay clock), instead of fixed locks. Only worth building if the model
  has a drift or a mean-reversion term; with a pure martingale (5a) it adds nothing.
- Signal source: underlying price plus option premium for the payoff; maps to: NSE / MCX / crypto options yes.
- Mechanical? yes, given a model.
- Our research: not tested as dynamic programming. Every fixed exit overlay so far failed (5a).

### Ch. 6 Continuous-time stochastic calculus (pp. 131-166)
Filtrations and stopping times in continuous time (the first time a price hits a level is a stopping time; the LAST time
before a date is not, because it needs the future - the same reason "the day's high" cannot be a live trigger). Brownian
motion and its martingales, Doob's maximal inequalities, the Ito integral as the limit of trading gains, quadratic
variation, Ito's formula, stochastic differential equations (existence, uniqueness, Markov property). The lognormal
model follows: log price is a random walk with drift (mean return minus half the variance).

#### 6a. Realised variance from squared returns (Ch. 6.4)
- What it computes: the sum of squared log returns over ever finer steps converges to the integrated variance of the
  process; the same for cross-products and covariance.
- How we could use it: the theory behind realised volatility from 1- or 5-minute bars, and behind comparing it with IV or
  VIX; size forecasting (R16 / R88) and the volatility risk premium both rest on it.
- Signal source: underlying price/volume. Maps to: NSE yes; MCX yes (mind the long evening session); crypto yes (24x7).
- Mechanical? yes.
- Our research: in use. R102 uses close-to-close and Parkinson realised vol (`DEEP_NIFTY_BANKNIFTY.md` 2.2); R16 found
  the next 30-60 minute range predictable (error 18-20% below the time-of-day average); R88: size yes, direction no.

#### 6b. Volatility drag (Ch. 6.4, 7.1)
- What it says: in the lognormal model the typical (median) path grows at the mean return minus half the variance, so
  more volatile holdings compound slower at the same average return.
- How we could use it: Jarvis context on sizing and leverage (why doubling size more than doubles drawdown risk).
- Signal source: none. Maps to: all.
- Mechanical? yes.
- Our research: consistent with H15 / H36: Kelly-style growing size brought 30-85% drawdowns (see 10a).

### Ch. 7 Continuous-time European options (pp. 167-222) - the most practical chapter
Girsanov's theorem (changing the probability removes the drift; the "market price of risk" is excess return over
volatility), first-passage times of Brownian motion, martingale representation (every claim is the end value of some
trading strategy), self-financing in continuous time, the risk-neutral price of any European payoff, the Black-Scholes
formula and hedge, multi-asset pricing (complete only if there are as many independent noise sources as traded assets),
barrier and lookback options, the Black-Scholes equation (and its original delta-hedging derivation), and the Greeks.

#### 7a. Black-Scholes price, delta hedge and Greeks (Ch. 7.5, 7.6, 7.9, 7.10)
- What it computes: call = S N(d1) - K e^(-rT) N(d2); put by parity. Delta (call) = N(d1), put delta = call delta - 1;
  gamma is the same for call and put and is largest near the money and near expiry; theta of a European call is always
  negative (the put's theta is the call's plus the interest on the strike); vega is the same for call and put, largest
  at the money and shrinking to zero at expiry; rho positive for calls. The book stresses that gamma governs how often
  and how much a delta hedge must be adjusted, so keeping gamma small keeps hedging costs down. The price does not depend
  on the expected return of the stock.
- Inputs: spot or forward, strike, time, volatility, rate.
- How we could use it: repricing legs without a print (already done in R103), position Greeks for any paper structure,
  expected decay per hour, and the elasticity in 1c.
- Signal source: volatility / options structure (pricing). Maps to: NSE index options yes; MCX options yes (use the
  forward form, 9a); crypto options yes.
- Mechanical? yes.
- Our research: R102's measured expiry-day decay clock (NIFTY ATM straddle at 84% of its 09:20 value at 10:30, 61% at
  13:00, 22% at 15:00, n 259) is the market's version of theta (`DEEP_NIFTY_BANKNIFTY.md` 3.2); `DEEP_OPTIONS_SELLING.md`
  tabulates Black-Scholes premiums at NIFTY 25,000 and 15% IV. MCX_OPTIONS.md: a flat hour on MCX costs 2-3 times what
  Black-76 theta says - the constant-volatility model misplaces decay in time.

#### 7b. N(d2) as the market's probability of finishing in the money (Ch. 7.7 exercise)
- What it computes: a binary call (pays 1 if the price ends above the strike) is worth e^(-rT) N(d2), so N(d2) is the
  risk-neutral probability of finishing in the money; call delta = (call value + strike x binary value) / spot.
- How we could use it: a per-strike "market-implied chance" for Jarvis to quote, with the warning that it is a
  risk-neutral number (it includes the volatility premium, so it overstates moves when VIX is rich, R102).
- Signal source: option chain as context. Maps to: NSE, MCX, crypto options yes.
- Mechanical? yes.
- Our research: not tested.

#### 7c. First-passage (touch) probability and the reflection principle (Ch. 7.2, 7.8)
- What it computes: for a driftless random walk, the chance the price TOUCHES a level before time T is about twice the
  chance it FINISHES beyond that level. With drift the book gives the exact formula (a normal-probability term minus an
  exponential correction) and the joint law of the close and the running maximum; with drift away from a level the
  chance of ever reaching it decays exponentially in distance.
- Inputs: distance to the level in log terms, volatility over the horizon (from R16 / R88 size forecasts or IV), drift
  (zero by default).
- How we could use it: (1) the chance a stop or a target gets hit before 15:10 for any distance, so stop distances can be
  set by probability rather than points; (2) for option sellers, the chance a short strike is touched (it is about double
  the delta-implied chance of expiring in the money), which drives stop-outs; (3) a quick sanity check on any rule's
  claimed win rate for given stop/target distances: with no drift, a target k times further than the stop is reached
  first only about 1/(k+1) of the time (our corollary of 5a, not a formula the book prints).
- Signal source: underlying price (risk geometry; no direction). Maps to: NSE yes; MCX yes; crypto yes (and helps set the
  mandatory crypto stop and lock distances).
- Mechanical? yes.
- Our research: not tested by us as a tool. Consistent with 5a and R18/R19.

#### 7d. Barrier and lookback options (Ch. 7.8)
- What it computes: closed-form prices for knock-in / knock-out calls and puts (in + out = vanilla) and for lookback
  options, all from the reflection principle; the knock-out call solves the Black-Scholes equation with an "image" term.
- How we could use it: not traded on NSE or MCX. A knock-out is the payoff of "a long call with a hard index stop", so the
  formula values a stop-protected option position. Context only.
- Signal source: volatility / options structure. Maps to: NSE no (not listed); MCX no; crypto rare.
- Mechanical? yes.
- Our research: not tested.

#### 7e. Market price of risk and completeness (Ch. 7.5, 10.1)
- Excess return of each asset = its volatilities times a common "price of risk"; a unique solution gives a complete
  market, several solutions an incomplete one (some claims cannot be hedged), none an arbitrage. Context for why
  stochastic volatility and jumps leave option sellers with unhedgeable risk. Signal source: none. Not tested.

### Ch. 8 The American put (pp. 223-246)
Prices the American put as the best stopping problem in continuous time; shows a critical price below the strike (exercise
when the stock falls below it; the boundary rises toward the strike at expiry), the perpetual put in closed form, the
split "American = European + early-exercise premium" (the premium is the interest earned on the strike while deep in the
money), the free-boundary / smooth-pasting formulation, a variational inequality behind numerical schemes, binomial
dynamic programming, and an approximation for the boundary (Allegretto, Barone-Adesi and Elliott). Little use for us:
NSE index and stock options, MCX options and the usual crypto options are European. The only transferable idea is 5b.

### Ch. 9 Bonds and term structure (pp. 247-284)
Zero-coupon bonds, forward prices, the forward measure, futures prices and daily marking to market, change of numeraire,
an option formula written on the forward, and short-rate models.

#### 9a. Futures, forwards and Black-76 pricing on the forward (Ch. 9.1-9.4)
- What it computes: forward price = spot / discount bond (spot grown at the carry rate); futures price = the
  risk-neutral expectation of the final price, kept at zero contract value by daily settlement; futures and forward
  prices agree when the discount factor and the asset are uncorrelated (if the asset tends to rise when rates fall,
  futures sit below forwards). Choosing the bond as numeraire makes the forward a martingale, so a call = discount x (F N(h1) - K N(h2))
  with the forward's volatility; the hedge holds N(h1) of the asset and N(h2) of strike-bonds and is self-financing.
- Inputs: the future (or the parity forward of 1a), strike, time, volatility.
- How we could use it: price and take IV from options off the future / synthetic forward instead of spot plus a guessed
  rate and dividend (what we already do on MCX and in H29); fair value of index futures for a basis check.
- Signal source: option chain as context / volatility structure (pricing, no direction). Maps to: NSE yes; MCX yes (MCX
  options settle into futures, so Black-76 is the natural model); crypto yes (perpetual or dated future as forward).
- Mechanical? yes.
- Our research: H38 tested futures basis, OI and volume as option-buying signals: nothing survives (1,800 variants on
  2020-Sep 2025, union White RC p 1.00; the one Liquidity filter that passed before the holdout lost -Rs 35/day in it)
  (`HUNT_H38.md`). Black-76 IV is used in H29, MCX_OPTIONS.md and the app's FT3 (FINDINGS.md).

#### 9b. Ornstein-Uhlenbeck mean reversion (Vasicek model) (Ch. 9.6)
- What it computes: a process pulled back to a level b at speed a with noise sigma: its mean decays to b like
  exp(-a t), its long-run spread is sigma / sqrt(2a), it can go negative (the book's criticism for rates). CIR adds a
  square-root noise that keeps it positive (the same process later used for variance in stochastic-volatility models).
- Inputs: a series believed to mean-revert (for us: a spread or ratio between two related prices).
- How we could use it: the standard model for a pairs / spread trade: half-life ln2 / a, entry at k long-run spreads from
  b, exit at b, stop if the fitted speed collapses. The book uses it only for interest rates.
- Signal source: underlying price (spread of two underlyings). Maps to: NSE (NIFTY vs BANKNIFTY ratio, index vs
  heavyweights) partly - each leg would be an option bought on its own underlying; MCX yes (GOLD/SILVER ratio, mini vs
  big); crypto yes (ETH/BTC; any crypto version must carry a stop and a profit lock).
- Mechanical? partly (the book gives the model, not a trading rule; thresholds would be ours).
- Our research: not tested as an OU spread. Nearest: R72's C2 "laggard catch-up" (an index lagging the others catches up)
  failed: -Rs 1,00,746 on 613 final trades (`HUNT_R72_CROSS_INDEX.md`).

#### 9c. Markov-chain regime model with filtering (Ch. 9.8)
- What it computes: the short rate jumps between a few levels following a Markov chain with a rate matrix; bond prices
  come from a matrix exponential, and the hidden current state and the chain's parameters are estimated from noisy
  observed yields by hidden-Markov filtering (Elliott, Hunter and Jamieson).
- How we could use it: the same machinery could filter NIFTY's current volatility regime (calm / normal / stressed) from
  minute returns, as a sizing input or a "do not sell premium" flag. Direction would still come from the underlying.
- Signal source: underlying price (regime of volatility). Maps to: NSE yes; MCX yes; crypto yes.
- Mechanical? yes (estimation is standard).
- Our research: not tested as an HMM. Nearest: "VIX is the range dial" (low-VIX median NIFTY range 0.75% against 1.72%
  above VIX 20, n 1,509 days each, R102 point 14) and R88's "last size + recent volatility" size rule.

Also in Ch. 9: yield curves and the expectation hypotheses, Hull-White (fits today's curve; all bonds perfectly
correlated), bond options in Hull-White and CIR, and the Heath-Jarrow-Morton forward-rate model with its no-arbitrage
drift condition. Pure rates theory; not relevant to our instruments.

### Ch. 10 Consumption-investment strategies (pp. 285-302)
The martingale method for optimal investment: a state-price "deflator" turns the problem into a static budget
constraint; optimal consumption and terminal wealth are the inverse marginal utility of the deflator, and a trading
strategy that delivers them exists by martingale representation. Worked examples use log utility and -1/c utility; the
combined problem splits the endowment so the marginal utilities match.

#### 10a. Log utility gives the Kelly / Merton fraction (Ch. 10.4)
- What it computes: with log utility (maximise the growth rate) the optimal holding keeps a constant FRACTION of wealth in
  the risky asset equal to its excess return divided by its variance (in several assets, the inverse covariance times the
  excess returns), rebalanced continuously; wealth never reaches zero.
- Inputs: the expected excess return and variance of the bet - the expected return is the hard part.
- How we could use it: the formula says size is proportional to edge over variance. Where our tests show no direction
  edge (excess return about zero), the growth-optimal size is about zero; where an arm has an edge, an estimated edge
  that wobbles makes full Kelly dangerous. Use fractions of Kelly at most, and cap by the account stop.
- Signal source: none (sizing). Maps to: NSE, MCX, crypto yes.
- Mechanical? yes.
- Our research: H15 sized Liquidity books at half-Kelly: P(drawdown >= 30%) was 63% and P(drawdown >= 50%) 14%, and the
  BANKNIFTY Kelly fraction was 1.29 in the sample but 0.48 and 0.14 on other windows (`HUNT_H15.md`). H36: Kelly or
  growing size reached Rs 300-1,100/day with 50-85% drawdowns; Boss ruled them out. The app trades fixed lots.

### Ch. 11 Measures of risk (pp. 303-328)
Value at Risk, its flaws, coherent risk measures, deviation measures, conditional VaR (expected shortfall), and hedging
that accepts some shortfall.

#### 11a. VaR and why it misleads (Ch. 11.1)
- What it computes: VaR at level alpha = the loss not exceeded with probability 1 - alpha (a quantile of P&L). The book's
  example: 95% monthly VaR of 2.25% on $100m means a 5% chance of losing more than $2.25m.
- Its flaws, with the book's examples: (1) not subadditive - one $100,000 loan with a 0.8% default chance has zero VaR at
  1%, but two independent $50,000 loans (1.6% chance of at least one default) have a VaR of $50,000, so diversifying
  "raises" risk; (2) it says nothing about how big the loss is beyond the quantile; (3) 150 loans, 1.2% default chance
  each, 1% level: with independent defaults VaR is 5 defaults and the average loss beyond it 6.287; with defaults that
  cluster (same 1.2% each) VaR is only 6 but the average beyond it is 14.5 - VaR barely moves while the tail triples.
- How we could use it: never report "worst day at 95%" alone for an option-selling or lottery rule.
- Signal source: none (risk). Maps to: all.
- Mechanical? yes.
- Our research: we report worst day, worst month and max drawdown, not VaR (R103 tables). Not tested as a measure.

#### 11b. Expected shortfall (CVaR) and coherent risk (Ch. 11.2-11.3)
- What it computes: the average loss over the worst alpha fraction of outcomes (the mean of the lowest quantiles); it is
  coherent - monotone, cash-shift invariant, scales with size, and subadditive, so the risk of two books is at most the
  sum. Every coherent measure is a worst-case expected loss over a set of stress probabilities; too small a set is just
  the mean (too lenient), all of them is the worst case (too strict).
- Inputs: a P&L history or bootstrap paths.
- How we could use it: a tail number for each arm and for the account, e.g. "average of the worst 5% of days", next to
  worst day and max drawdown; compare it with Boss's -Rs 40k drawdown / -Rs 13k month stop; rank selling structures by
  return on margin against expected shortfall.
- Signal source: none (risk). Maps to: all.
- Mechanical? yes.
- Our research: not used. The naked 09:20 short straddle made +Rs 63,792 on NIFTY over 1,272 days (62% wins) with a
  worst day of -Rs 28,876 on one lot (`HUNT_R103_OPTIONS_SELLING.md`) - exactly the high-win, fat-tail profile where a
  quantile hides the risk and expected shortfall shows it.

#### 11c. Quantile and efficient hedging (Ch. 11.4)
- What it computes: with less capital than a full hedge costs, the strategy that maximises the chance the hedge succeeds
  copies a "knock-out" version of the claim on the states where the likelihood ratio of real to risk-neutral
  probability is highest (Neyman-Pearson); with a coherent risk measure, a randomised version minimises shortfall risk.
- How we could use it: theory only for us (no client claims to hedge). Context.
- Signal source: none. Not tested.

## Performance claims
The book makes no trading-performance claims at all: no returns, win rates or backtests. Its only numbers are worked
examples (toy trees, a $100m VaR illustration, the loan-default examples in 11a) and proofs. The approximation for the
American put boundary (Ch. 8.6) is said to give satisfactory results in a cited numerical study; no evidence is shown in
the book.

## Risk, sizing and psychology
- Options are riskier than the underlying: a call's return volatility is its elasticity (at least 1) times the
  stock's, and its excess return scales the same way (Ch. 1.4, 7.10).
- Sizing: log utility gives a constant fraction of wealth equal to excess return over variance (Ch. 10.4); nothing on
  fractional Kelly or estimation error.
- Risk measurement: prefer coherent measures such as expected shortfall to VaR; VaR is not subadditive and ignores tail
  size (Ch. 11).
- Hedging: gamma sets hedge frequency and cost (Ch. 7.10); partial hedges can be optimised for success probability or
  shortfall risk (Ch. 11.4).
- No psychology; every model assumes frictionless markets, which the authors state each time.

## How it relates to our research
- Agrees and explains: options already price the expected move (risk-neutral pricing, 7a) - our R89 straddles gained
  about zero before costs and lost after; the price ignores drift - so an option buyer needs a direction edge that the
  premium does not contain, which R16 / R41 / R88 found almost never exists on NIFTY / BANKNIFTY intraday.
- Agrees: exits do not create edge on a driftless price (optional stopping, 5a) - R18 0 of 60, R19 0 of 12, R81 0 of
  66, R100 0 of 15.
- Agrees: realised variance is measurable and predictable while direction is not (6a) - R16, R41, R88; R102's VIX
  premium over realised NIFTY vol (81% of days) is the incomplete-market band of 4a in practice.
- Agrees: Kelly sizing is fragile when the edge is estimated (10a) - H15's Kelly fraction swung from 0.14 to 1.29.
- Explains R103: the selling profits that "passed" came from stale wings; the book's parity and arbitrage logic (1a, Ch.
  3) is the reason a price that cannot move against you is a data error, not an edge.
- Not tested by us: touch probabilities (7c), expected shortfall (11b), OU spreads (9b), HMM regimes (9c), dynamic-
  programming exits (5b).
- Already rejected nearby: futures basis / OI as signals (H38), synthetic-forward lead for buyers (h29, h39), GEX (R28's 10
  filters all failed; R36 0 of 4; R37 0 of 4), option-chain-only signals (IV1).

## What is new to us
- First-passage (touch) probabilities with drift, and the "touch is about twice finish-beyond" rule (7c): a way to set
  stops, targets and option-selling adjustment points by probability, and to sanity-check any claimed win rate.
- Expected shortfall (CVaR) as the tail number for arms and for the account rule (11b), with the book's reason why VaR
  can fall while the tail grows (clustered losses).
- An HMM-filtered Markov regime model (9c) as a sizing or "no selling today" input.
- The Ornstein-Uhlenbeck spread model (9b) for MCX ratios and ETH/BTC (with mandatory stop and lock for crypto).
- Dynamic-programming exit (5b) as the right frame for exit research: it can only help if the model has drift or mean
  reversion.
- Parity-based stale-print flag (1a) as a data guard for every option study and the app's paper fills.

## Could not read
Most displayed equations came out of pdftotext as scrambled symbols (fractions, integrals and subscripts split across
lines); the formulas quoted here are the standard ones the prose describes, not copies. The 7 figures (payoff diagrams,
the binomial event tree, a two-stock tree, the reflection-principle sketch, the quantile sketch and the excursion
diagram) are not in the text layer. The bibliography (about 305 references) and the index were skimmed only.
