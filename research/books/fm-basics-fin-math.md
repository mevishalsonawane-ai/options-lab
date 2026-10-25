# The Basics of Financial Mathematics - Richard F. Bass (2003)

Source: basics-of-financial-mathematics.txt, 106 pages (University of Connecticut lecture notes, Spring 2003), read in
full on 25 Oct 2026 (text layer: all 26 sections, their end-notes and the 26 problems; the book has no figures; some
formulas came out garbled by the text extraction - see "Could not read").
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A short graduate course of lecture notes on the mathematics behind option pricing.
It opens by saying mathematical finance is about pricing options, not predicting stock prices.
It builds probability, conditional expectation, martingales and stopping times, then the binomial pricing model.
It then covers Brownian motion, stochastic integrals, Ito's formula, Girsanov, SDEs and martingale representation.
It ends with Black-Scholes (two derivations), hedging, the fundamental theorem, American puts and interest-rate models.

It is almost entirely theory with proofs. There is no market data, no backtest, no trading rule and no performance
claim; the only numbers are classroom examples (a $40 Microsoft share, a three-step tree with S0 = 10 and K = 15). Its
use for us is as a compact, rigorous statement of the ideas our app already relies on (Black-Scholes, delta, risk-neutral
pricing, "no look-ahead") and of a few results that turn into useful tools: touch probabilities, the martingale
"no free edge from exits" result, and the mean-reverting rate models. The practical overlap with
`research/books/bennett-trading-volatility.md` is small (Bennett is about trading volatility; this book is the maths
under it), and the touch-probability tool is also written up in `research/books/fm-bjork-arbitrage.md` (Ch. 18).

Our data, for the mapping: NIFTY weekly and BANKNIFTY monthly index options (European, cash settled), a 1-minute chain
of the nearest expiry within ATM +-10 strikes with IV and OI (last prices, no bid/ask), index spot and futures bars,
India VIX daily, MCX futures and options, crypto spot/perpetuals and Delta Exchange options. Costs about Rs 70 (NIFTY)
and Rs 76 (BANKNIFTY) per option round trip (`FINDINGS.md`, opening summary).

## Methods, rules and setups (section by section)

### S1. Introduction
Explains a European call with a made-up Microsoft example and states the course's stance: the subject prices options,
it does not forecast the stock. Lists the five parts (elementary probability, binomial model, stochastic calculus,
continuous-time pricing, term structure). No method.

### S2-S3. Probability review and conditional expectation (pure theory)
Sigma-fields are presented as "what you know" at a given time; random variables, expectation, independence, variance.
Conditional expectation given the information so far is shown to be the best mean-square predictor of a quantity (it
is the projection onto what is knowable). Practical reading: any forecast we build is at best this conditional mean;
if the conditional mean of the next move is zero, no model can beat "zero" on direction.

### S4-S5. Martingales, stopping times, optional stopping, Doob's inequalities (theory with two practical results)
A martingale is a fair game: the expected next value, given everything known, equals today's value. Examples include
sums of mean-zero steps and "double or nothing" (expected wealth stays 1 although the player is almost surely broke in
the end). A stopping time is a rule you can decide on using only information so far ("the first time price reaches
A" is one; "the last time price was above A" is not, because it needs the future). Doob's optional stopping theorem:
for a bounded stopping time, stopping a martingale does not change its expected value. Doob's maximal inequality
bounds the chance that a non-negative submartingale ever exceeds a level. Bounded martingales converge (upcrossing
argument).

#### Method 1: "exits cannot create an edge" (optional stopping, S5 Theorem 5.3)
- What it computes: if the price is a fair game (no drift once costs and the risk-free rate are set aside), then ANY
  exit rule that only uses past information and ends by a fixed time - stop-loss, target, trail, time stop - has the
  same expected result as simply holding to that time. Exits reshape the distribution of outcomes (win rate, size of
  wins and losses), not its mean. Costs then make the mean negative.
- Inputs: none beyond the premise that the price has no predictable drift.
- How we could use it: as the theoretical reason our exit overlays and trails rarely add money unless the entry has an
  edge; Jarvis can explain "a stop or target changes the win rate, not the expected value".
- Signal source: trade management (no signal). Maps to: NSE index options, MCX and crypto alike. Crypto rules keep a
  stop and a profit lock anyway (CLAUDE.md) - they are risk controls, not an edge source.
- Mechanical? yes (a theorem).
- Our research: consistent. R18 (`HUNT_R18_EXITS.md`): five early-exit overlays on every arm's own exits, 0 of 60
  passed. HUNT_R12_TRADE_MANAGER.md cites Kaminski & Lo: under a random walk a stop always lowers expected return and
  helps only where returns have momentum. Note for the option leg: the option premium is NOT a martingale in real
  terms (theta makes a bought option drift down), so stops on premium meet a negative drift, not a fair game.

#### Method 2: "a stopping time may not look ahead" (S5, S12 Note 1, S16)
- What it computes: a precise test of look-ahead bias. Every decision (entry, exit, size) must be a function of data
  available at that moment; the book's trading P&L is defined only for such "adapted / predictable" holdings.
- How we could use it: it is exactly our look-ahead guard (research/brain tests; e.g. `HUNT_F1_FACTORY.md` and
  `HUNT_B2_ALL_AT_ONCE.md` mention it). Rules like "exit at the day's high" or "the swing low that turned out to hold"
  fail this test.
- Signal source: research method. Maps to all markets. Mechanical? yes. Our research: already our standard.

### S6. One-step binomial model
A stock goes from S0 to uS0 or dS0; a bank pays rate r. A call is copied exactly by holding delta = (option value up -
option value down) / (stock up - stock down) shares plus cash; any other option price gives a riskless profit (sell the
dear one, buy the copy). The price is the discounted average payoff under "risk-neutral" weights p = (1 + r - d)/(u - d),
which have nothing to do with the real chances of up and down. With three possible outcomes the copy is impossible and
the theory "falls apart" (an incomplete market).

#### Method 3: replication and the risk-neutral price (S6-S7, S19-S20)
- What it computes: the one no-arbitrage price of any option, and the hedge (delta) that copies it.
- Inputs: spot, strike, time, rate and the size of possible moves (tree) or the volatility (Black-Scholes).
- Key consequence: the real probability of up or down, and the expected return of the underlying, do not enter the
  price (Remarks 6.1-6.4; S20 states the call price is "completely independent" of the drift). A directional view
  changes what a buyer expects to earn, not what the option is worth.
- How we could use it: the theory behind the app's Black-Scholes repricing and our finding that options already price
  the expected move; nothing to trade by itself.
- Signal source: volatility / options structure (a pricing tool). Maps to: NSE index options yes; MCX options (on
  futures) yes; crypto options yes.
- Mechanical? yes.
- Our research: consistent with R89 (`FINDINGS.md`, "A strategy from R88"): straddles bought when a big move was
  forecast were about zero before costs because the options already priced the move (out of sample 2,214 straddles,
  -Rs 4.4 lakh, 0 of 18 versions positive).

### S7. Multi-step binomial model
Assumes unlimited short selling and borrowing, no transaction costs and price-taking. Under the risk-neutral weights
the discounted stock price and the discounted wealth of any self-financing strategy are martingales; the model is
complete (every payoff can be copied); the price is the discounted risk-neutral expectation and the hedge at each node
is the up-down value difference over the up-down price difference. Worked example (three steps, u = 2, d = 1/2,
r = 0.1, S0 = 10, K = 15): call worth 4.2074, first hedge 0.6391 shares, later hedges 1.0 / 0.3333 / 0.0 depending on
the path. Shows that even a "sell at the maximum, buy at the minimum" payoff can be priced and copied without looking
ahead - it is simply expensive.
- Practical note: the book's "no transaction costs" assumption is the one that fails for us: a one-lot trader cannot
  rebalance a delta hedge often (see `DEEP_OPTIONS_SELLING.md` section 3), and `research/books/fm-binomial-models.md`
  covers trees in more practical depth.

### S8. American options (S8, S24)
An American call on a non-dividend stock is never worth exercising early, so it equals the European call (proof: a
discounted convex payoff that is zero at zero is a submartingale, and optional stopping says wait). A put can be worth
exercising early; its exact value has no closed form, and the book calls it a major unsolved problem (good
approximations exist). S24 recasts it as optimal stopping: the value is the smallest supermartingale above the
exercise payoff (Snell envelope) and the best time is the first time the value equals the payoff.
- Relevance: NSE index options are European and cash settled, so early exercise does not arise; MCX options are on
  futures and settle at expiry. Not useful to us beyond the optimal-stopping idea. Not tested by us.

### S9-S10. Continuous random variables and stochastic processes (pure theory)
Expectation by discretising; conditional expectation in general via Radon-Nikodym; "almost surely"; filtrations, the
"usual conditions", continuous-time stopping times and martingales; Doob's inequality and optional stopping carried
over to continuous time.

### S11. Brownian motion
Built as the limit of a scaled coin-toss walk (central limit theorem): mean zero, variance equal to elapsed time,
independent normal increments, continuous paths that are nowhere differentiable. Brownian motion and (its square minus
time) are martingales.

#### Method 4: square-root-of-time scaling of the expected move (S11)
- What it computes: under a random walk the variance of the move grows in proportion to time, so the typical move over
  a horizon scales with its square root (a one-day move about sqrt(5) times smaller than a one-week move).
- How we could use it: expected-move bands from IV or VIX for any horizon; scaling a straddle's price to the time left.
- Signal source: volatility / options structure. Maps to NSE, MCX and crypto.
- Mechanical? yes.
- Our research: the intraday clock is not uniform. R102 (`DEEP_NIFTY_BANKNIFTY.md`, summary 16) measured the expiry-day
  ATM straddle at 84% of its 09:20 value at 10:30, 61% at 13:00 and 13% at 15:15 (NIFTY, n 259), and `MARKET_HOW.md`
  shows a random walk needs the real U-shaped intraday volatility to match where the day's extremes fall (47.0% vs
  47.6% in the first 15 minutes). So the square-root rule must run on "volatility time", not clock time.

### S12. Stochastic integrals
Defines the integral of a holding against a Brownian price as the limit of "shares held times price change" sums,
with the holding decided before each change. Its mean is zero and its variance is the integral of the squared holding
(Ito isometry); quadratic variation of Brownian motion is elapsed time. The author's interpretation: the integral is
the trading P&L of holding that many shares; continuous trading is allowed, peeking at the future is not.

### S13. Ito's formula
The change in a smooth function of a random price has an extra half-second-derivative term driven by the squared moves
(quadratic variation); product rule; multi-dimensional version; Levy's theorem (a continuous martingale whose
quadratic variation is elapsed time is Brownian motion).

#### Method 5: volatility drag and the gamma term (S13, S16)
- What it computes: (a) for a lognormal price the log growth rate is the mean return minus half the variance, so a
  volatile asset compounds below its average return; (b) for an option, the P&L over a short step is delta times the
  move plus one half gamma times the squared move, minus time decay - the engine of gamma scalping and of a short
  straddle's losses on big moves.
- How we could use it: Jarvis explanations of why a straddle bought at an IV below later realised volatility can gain
  even with direction wrong, and why a short straddle bleeds on large moves; sizing note that leveraged or volatile
  books compound below their mean.
- Signal source: volatility / options structure. Maps to: NSE index options yes; MCX options yes; crypto options yes.
- Mechanical? yes (a formula).
- Our research: not tested as a gamma-scalping rule by us. R102 (`DEEP_NIFTY_BANKNIFTY.md` 2.2) found VIX over-states
  NIFTY's next 21 days of realised volatility on 81% of days (median ratio 1.28, n 4,507), which is the condition under
  which bought gamma loses on average.

### S14. Girsanov's theorem and the reflection principle
Changing the probability by a suitable weight removes a drift: a price with drift becomes a driftless martingale under
the new measure, which is the risk-neutral measure. Second use: the reflection principle gives the chance that a
Brownian path rises above a level by a given time, and Girsanov extends it to a path with constant drift (written as
two integrals).

#### Method 6: probability of touching a level (S14, S21, problem 24)
- What it computes: with no drift, the chance that the price touches a level before time T is about twice the chance
  that it finishes beyond that level at T (the reflection principle); with drift there is a closed form via Girsanov.
- Inputs: distance to the level in volatility units (level / (sigma x sqrt(T))), and drift (zero for intraday).
- How we could use it: Jarvis can say, from the current IV or a range forecast, how likely a stop or target is to be
  touched before the square-off; a sanity check for "probability of profit" claims on sold strikes (a short strike is
  touched about twice as often as it expires in the money).
- Signal source: volatility / options structure (uses IV or our range forecast). Maps to: NSE index (underlying spot or
  futures for the touch), MCX and crypto alike.
- Mechanical? yes.
- Our research: the random-walk premise has support. R98c (`FINDINGS.md`): after a 1.5-straddle move the index hits +1
  before -0.5 straddle 34.5% of the time against 33.3% for a random walk. A direct check of the twice-the-finish rule on
  our minute data is not done by us (see "What is new to us").

### S15. Stochastic differential equations
Existence and uniqueness when the volatility and drift coefficients are Lipschitz and grow at most linearly (Picard
iteration); the linear SDE solved in closed form (geometric Brownian motion); the link from SDEs to PDEs through the
generator (one half sigma squared times the second derivative plus drift times the first). Theory.

### S16. Continuous-time financial models
The standard stock model is geometric Brownian motion (percentage moves, always positive), with a volatility and a mean
return expected above the risk-free rate. A rebalanced portfolio's wealth is the stochastic integral of shares held
against price; discounting by the risk-free rate gives the present-value price. The author says the model, with no
costs and continuous trading, "has proved to be a very good one" despite discrete prices; no evidence is given.
- Our research: daily NIFTY returns are far from normal: kurtosis 14.1 and worst day -12.98% (`DEEP_NIFTY_BANKNIFTY.md`
  2.1, n 5,087 days), so the lognormal model understates tails; R103's naked short straddle had fat tails
  (`HUNT_R103_OPTIONS_SELLING.md`).

### S17. Markov property of Brownian motion
Future increments are independent of the path so far: "to predict the future, we only need to know where we are and
not how we got there". The strong Markov property restarts the process at any stopping time (e.g. the moment a level
is hit). Also holds for solutions of well-posed SDEs.
- Practical reading: under this model chart patterns, swing structure and candle shapes carry no information about
  direction. That is a model assumption, not a test, but it matches what we found. Our research: R16
  (`HUNT_R16_PREDICTABILITY.md`): direction over the next 15/30/60 minutes not predictable (holdout AUC 0.509-0.536),
  while the size of the next move is predictable (`FINDINGS.md`: range of the next 30/60 min, holdout error 15-20% below
  the time-of-day baseline) - real prices are Markov in direction but not in volatility, which the constant-sigma model
  misses.

### S18-S19. Martingale representation and completeness (theory)
Every square-integrable payoff on a Brownian filtration is a constant plus a stochastic integral (proof via Fourier
transforms). Applied to geometric Brownian motion: with the drift removed using the "market price of risk"
(excess return over volatility), the discounted price is a martingale and every payoff can be copied by trading the
stock - the market is complete.

### S20. Black-Scholes formula, first derivation
The option price is the risk-neutral expected discounted payoff; for a call this gives the Black-Scholes formula
(spot times N(d1) minus discounted strike times N(d2)). Emphasises again that the drift does not appear; only
volatility is unknown, which is why the market quotes options by implied volatility.

#### Method 7: Black-Scholes price and delta (S20-S22)
- What it computes: European call/put prices and the hedge ratio (call delta = N(d1)).
- Inputs: spot (or the futures-implied forward for an index), strike, time to expiry, rate, volatility.
- How we could use it: already used - repricing stale wing legs, Greeks for GEX, the straddle decay table.
- Signal source: volatility / options structure. Maps to: NSE index options yes (use the forward to absorb the dividend
  yield); MCX options on futures need the Black (futures) variant, which the book does not cover; crypto options yes.
- Mechanical? yes.
- Our research: used as a tool in R103 (`DEEP_OPTIONS_SELLING.md`: exit legs that left the ATM +-10 window repriced
  with Black-Scholes at their last IV; the six design passes were stale-price artefacts, 0 of 24 confirmed in
  `HUNT_R103_OPTIONS_SELLING.md`) and in R28/R36 GEX (Black-Scholes gamma times OI; `FINDINGS.md`, "Dealer gamma
  exposure": size of the day only, no arm filter).

### S21. Hedging strategies
The hedge for a call is found from the Markov property: the derivative, with respect to the current Brownian value, of
the expected payoff over the time left - i.e. delta. A lookback "sell at the high" option is hedged the same way once
one tracks the triple (price, running maximum, time), since the running maximum alone is not Markov.
Mentions the Clark-Haussmann-Ocone formula as an alternative. Practical note: the "high so far" is useful state only
together with where the price is now - one more reason a level by itself is not a signal.

### S22. Black-Scholes formula, second derivation (PDE)
Assume the portfolio value is a smooth function of price and time to go; match Ito's expansion with holdings of stock
and bonds. Result: shares held = the price derivative (delta), and the Black-Scholes PDE links time decay, gamma and
delta (theta = one half sigma squared S squared gamma + r S delta - r V, in the usual sign convention).
- Practical reading: for a delta-hedged option, time decay is the exact price of gamma: a long-gamma position pays theta
  and earns it back only if realised variance exceeds implied. This is the core of every straddle question Boss asks.
  Our research: R102's 81% figure above; R89's 0 of 18 above.

### S23. The fundamental theorem of finance (theory)
No free lunch with vanishing risk is equivalent to the existence of an equivalent probability under which discounted
prices are martingales (first theorem); that probability is unique exactly when the market is complete (second, not
proved). Illustrated with a Cantor-function drift that would give riskless profit. One line for us: any truly
predictable drift that survives costs would be an arbitrage, so it should be rare - consistent with our 1 of 70 sign
rules confirmed (and that one an artefact).

### S24. American puts
See S8 above: optimal stopping, Snell envelope, no closed form.

### S25. Term structure
Random interest rates: accumulation factor, zero-coupon bond price as an expectation of the discount, forward rates as
minus the slope of log bond prices, the short rate as the forward rate for "now". Relevance to us is only the rate
input to Black-Scholes (`DEEP_OPTIONS_SELLING.md` uses a flat 6.5%); on intraday index options the rate barely matters.

### S26. Interest-rate models
Heath-Jarrow-Morton (forward rates modelled directly; under the risk-neutral measure the drift is fixed by the
volatility), Hull-White (a mean-reverting Gaussian short rate with explicit mean and variance; can go negative) and
Cox-Ingersoll-Ross (square-root volatility; stays positive when the pull is strong enough).

#### Method 8: mean-reverting (Ornstein-Uhlenbeck / Hull-White) process (S26, problem 22)
- What it computes: a series pulled back toward a level at speed b with random shocks; mean and variance at any
  horizon are explicit and the half-life of a deviation is ln 2 / b.
- Inputs: the series, its long-run level, speed of reversion and shock size (fitted by a lag-one regression).
- How we could use it: a template for spreads that should revert - futures basis to fair value, BANKNIFTY/NIFTY ratio,
  India VIX toward its average, MCX calendar spreads, crypto funding or perpetual basis. The book applies it only to
  interest rates and gives no trading rule.
- Signal source: underlying price (spread of underlyings) or volatility / options structure (VIX). Maps to: NSE (basis,
  cross-index), MCX (calendar spread), crypto (basis, funding; any trade needs a stop and a profit lock).
- Mechanical? partly (the model is; entry and exit thresholds are not in the book).
- Our research: not tested by us as an OU spread rule. Nearest: R72 cross-index (`HUNT_R72_CROSS_INDEX.md`) uses
  breadth as a filter, not a reverting spread; h29 (`HUNT_H29.md`) found the options' synthetic forward does "lead"
  the index but a buyer cannot use it (the move is already in the option price), and H39 (`HUNT_H39.md`) found the
  heavyweights' stock options do not lead the index enough to pay an index option buyer.

### Problems (p. 104-106)
Twenty-six exercises without answers: martingale identities, a three-step tree (u = 3, d = 1/2, r = 0.1, S0 = 20,
K = 10), a continuous-time Black-Scholes price with sigma = 2, lookback and range-digital options priced and hedged,
time-dependent drift (to show it does not change the price), and an Ornstein-Uhlenbeck solution.

## Performance claims
None. The book makes no claim about profits, win rates or returns. Its only empirical statement is that geometric
Brownian motion "has proved to be a very good" model (S16), with no evidence given; our data contradict it on tails
(NIFTY daily kurtosis 14.1).

## Risk, sizing and psychology
Nothing on sizing, Kelly, drawdown or psychology. The risk content is implicit: hedging by replication, the
"double or nothing" martingale whose expected wealth stays 1 while the player is almost surely ruined (S4) - a clean
illustration that a fair expected value can hide certain ruin under all-in sizing - and Doob's maximal inequality as a
bound on how far a fair-game wealth path can run. Our sizing work (H15, `HUNT_H15.md`) found half-Kelly tradable on
Liquidity but the Kelly fraction unstable (f* for BN 0.48 and 0.14 on other windows against 1.29), and random entries
with the same aggressive sizing had a 49% chance of ruin.

## How it relates to our research
- Agrees: drift does not enter option prices (S6, S20) - consistent with R89 (0 of 18) and with option buying failing
  after costs in R16/R47/R48. The Markov assumption (S17) matches our coin-flip direction results (R16 AUC
  0.509-0.536). Optional stopping (S5) matches R18 (0 of 60 exit overlays).
- Already used: Black-Scholes and delta (S20-S22) in R103's stale-leg repricing and in R28/R36 GEX.
- Contradicted by our data: constant volatility and normal returns (S16) - our range is predictable (R16), VIX over- or
  under-states realised vol by index (R102: 81% NIFTY, BANKNIFTY under-stated on 60% of days), and daily kurtosis is
  14.1 on NIFTY.
- Not tested: touch probabilities on our minute data; OU spread trading.

## What is new to us
- A ready test: does NIFTY/BANKNIFTY touch a level (in IV-scaled units) about twice as often as it closes beyond it,
  intraday and to expiry? If touches run below the reflection rate, sold strikes are safer than the model says; if
  above, stops at round distances are hit more than priced. This also gives Jarvis an honest "chance this stop is hit
  before 15:05" figure.
- The volatility-time clock: square-root-of-time scaling applied on R102's measured intraday volatility curve rather
  than clock minutes, for straddle fair value and range targets during the day.
- OU half-life as a common yardstick for any spread idea (basis, cross-index, MCX calendar, crypto funding) before
  writing a rule.
- The "stopping time" definition as the formal wording for our look-ahead guard in research docs.

## Could not read
Prose fully readable. The text extraction garbled many formulas (subscripts, integral limits, the tilde on the
risk-neutral Brownian motion, fractions such as d = 1/2 shown as "21"), notably in S12-S14, S18 and S21-S26; meanings
were recovered from the surrounding text and standard results. The book has no figures or tables beyond the one
three-step tree table in S7, which was readable.
