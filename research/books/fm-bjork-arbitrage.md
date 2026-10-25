# Arbitrage Theory in Continuous Time, 2nd edition - Tomas Björk (2003, the second edition's preface date; Oxford University Press)

Source: arbitrage-theory-in-continuous-time-bjoerk.txt, 481 pages, read in full on 25 Oct 2026 (pdftotext text layer:
prefaces, contents, Chapters 1-26, Appendices A-C, references skimmed to the end). Most displayed equations came through
garbled or missing, and every figure is an image (see "Could not read"); the prose around them was read.
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A graduate textbook on how derivatives are priced by no-arbitrage, not by forecasting.
It builds the tools (binomial trees, Ito calculus, stochastic differential equations, martingales, change of measure).
It applies them to Black-Scholes, hedging and the Greeks, futures, currencies, barrier and lookback options.
It covers optimal investment (Merton) and a long interest-rate part (short-rate, HJM, LIBOR and swap market models).
It contains no trading rules and no market data: its practical value for us is pricing, hedging, sizing and honesty about what is estimable.

## Methods, rules and setups

A chapter-by-chapter account. Pure theory gets one or two lines; each practical method gets a full block. No chapter
gives an entry/stop/exit trading rule; the "rules" file holds the few backtestable ideas we derived from it.

### Ch. 1. Introduction
A company with a dollar bill due in six months can hedge with cash now, a forward or a call option. The two questions
of the book: what is a fair price for a derivative, and how does its seller hedge? The answer is neither "discounted
expected payoff" nor "whatever supply and demand say": the price is pinned to the underlying by no-arbitrage.

### Ch. 2. The binomial model (practical)
One period, then many periods. No arbitrage holds exactly when the bank return lies between the down and up moves;
then "martingale" probabilities exist and price = discounted expected payoff under them. Worked example: stock 100,
up 1.2 / down 0.8, real up-probability 0.6, call strike 110: the real-world expectation says 6, the arbitrage price
is 5, and a seller who gets 6 locks a riskless 1 by hedging with a quarter share and a 20 loan. Multi-period trees
are solved backwards (example price 27.5) and the hedge is recomputed only at the nodes actually visited.

#### Method: binomial (Cox-Ross-Rubinstein) tree pricing and hedging
- What it computes: an option's price and its hedge ratio (shares per option = change in option value / change in
  stock value between the up and down nodes) by backward induction on a recombining tree.
- Inputs: spot, up/down factors (set from volatility), rate, strike, steps to expiry.
- How we could use it: price American-style or path-dependent payoffs where no formula exists; a cross-check of the
  app's Black-Scholes numbers; a teaching tool for Jarvis ("why the option costs this much").
- Signal source: volatility / options structure (pricing tool, no signal). Maps to: NSE index options yes (European),
  MCX options on futures yes, crypto options yes.
- Mechanical? yes.
- Our research: not tested by us as such; R103 used Black-Scholes (not trees) to reprice stale wings
  (HUNT_R103_OPTIONS_SELLING.md, "The stale-leg artefact").

### Ch. 3. A more general one-period model (theory)
Finite states, many assets. A separation (Farkas) argument gives the First Fundamental Theorem (no arbitrage iff state
prices / a martingale measure exist) and the Second (complete market iff that measure is unique). Incomplete markets
admit a range of no-arbitrage prices. Stochastic discount factors are the same object written under real probabilities.

### Ch. 4. Stochastic integrals (theory, two practical facts)
Brownian motion, information flows, the Ito integral and Ito's formula. Two facts matter for trading research: the
integral evaluates the holding at the START of each step (you choose the position before the move: the formal
no-look-ahead rule), and squared Brownian increments add up to elapsed time, so realised variance from many small
returns converges to the true variance. Correlated Brownian motions are built by mixing independent ones.

### Ch. 5. Stochastic differential equations (theory + Monte Carlo basis)
Existence and uniqueness; geometric Brownian motion (the log-price drifts at mu minus half sigma squared); the linear
(Ornstein-Uhlenbeck type) equation; the generator; Feynman-Kac (a pricing PDE's solution equals a discounted
expectation over simulated paths); Kolmogorov backward and forward (Fokker-Planck) equations.

#### Method: Monte Carlo pricing via Feynman-Kac (Ch. 5, 7, 25.5)
- What it computes: a price or a probability as the average of a payoff over many simulated paths of the underlying.
- Inputs: a model of the underlying (drift, volatility; under the pricing measure the drift is the risk-free rate),
  the payoff, a time step. Ch. 25.5 adds: simulate the LOG of the price, because its noise term is then deterministic
  and the discrete scheme is more stable.
- How we could use it: price unusual payoffs (Boss's step locks as path-dependent claims), and estimate stop/target
  hit probabilities under a stated volatility.
- Signal source: none (tool). Maps to: NSE, MCX, crypto alike.
- Mechanical? yes.
- Our research: our studies use bootstrap resampling of real trades rather than model paths (e.g. HUNT_H15.md
  "Bootstrap P(ruin within 24 months)"); model Monte Carlo of option payoffs: not tested by us.

### Ch. 6. Portfolio dynamics (theory)
Self-financing portfolios: every change in wealth comes from price changes on the holdings (minus consumption); with
dividends, from price plus dividend. Decisions are taken at the start of each period, before the price move.

### Ch. 7. Arbitrage pricing (practical core)
A portfolio with no random term must earn the risk-free rate. Hedging an option with the stock removes the randomness
and yields the Black-Scholes equation. The striking result: the stock's expected return does not enter the price,
only its volatility. Risk-neutral valuation: price = discounted expected payoff when the stock is assumed to grow at
the risk-free rate. Then: forwards and futures, Black's formula for options on futures, historic and implied
volatility, the smile, and American options.

#### Method: Black-Scholes price and risk-neutral valuation
- What it computes: the fair price of a European option from spot, strike, time, rate and volatility; call = S N(d1) -
  K e^(-r T) N(d2). The book's illustration uses strike 100, volatility 0.2 and a quarter of a year.
- Inputs: the only unobserved input is volatility.
- How we could use it: repricing options with no recent print (exactly what R103's Amendment 2 did), Greeks for the
  app, "what is priced in" explanations for Jarvis.
- Signal source: volatility / options structure. Maps to: NSE index options yes (European, cash settled); MCX options
  are on futures, so use Black-76 below; crypto options yes.
- Mechanical? yes.
- Our research: R103 repriced stale wing legs with Black-Scholes at their last implied volatility and found the six
  design passers all lose (HUNT_R103_OPTIONS_SELLING.md; repricing runs 1-5 points high, so it flatters the seller).

#### Method: Black-76 for options on futures (Ch. 7.6, Ch. 26)
- What it computes: call = e^(-r T) [F N(d1) - K N(d2)], d1 = (ln(F/K) + sigma^2 T / 2) / (sigma sqrt T). The futures
  price replaces spot, so the dividend yield and carry drop out. Forward and futures prices coincide when rates are
  deterministic.
- How we could use it: the natural model for MCX options (written on futures) and for NIFTY options priced off the
  NIFTY future or a synthetic forward (strike + call - put), which absorbs the index dividend yield.
- Signal source: volatility / options structure. Maps to: MCX yes (directly); NSE index options yes via the future;
  crypto options on perpetuals/futures yes.
- Mechanical? yes.
- Our research: h29 built the synthetic forward (K + C - P near the money) and traded its moves: all 24 variants lost
  (HUNT_H29.md, Verdict). A Black-76 implementation for MCX: not tested by us.

#### Method: historic volatility estimate (Ch. 7.7.1)
- What it computes: annualised standard deviation of log returns; the book's practice is to use a look-back as long as
  the option's remaining life; the estimate's standard error is roughly sigma / sqrt(2n).
- How we could use it: the realised-vol side of any "implied versus realised" comparison.
- Signal source: underlying price. Maps to: NSE, MCX, crypto.
- Mechanical? yes.
- Our research: R102 compared India VIX with the next 21 days' realised volatility: VIX higher on 81% of NIFTY days
  (median ratio 1.28, n 4,507) but lower than BANKNIFTY's realised on 60% (DEEP_NIFTY_BANKNIFTY.md, 2.2). R16 found the
  next 30-60 minutes' range predictable (HUNT_R16_PREDICTABILITY.md).

#### Method: implied volatility and the smile (Ch. 7.7.2)
- What it computes: the volatility that makes Black-Scholes match a traded benchmark option; other options are then
  priced relative to it. If the model were right, implied volatility would be flat across strikes; in practice
  far-from-the-money options trade at higher implied volatility (the smile), so the model is only a quoting device.
- How we could use it: quote and compare options in volatility, not rupees; never treat ATM IV as the vol of a wing.
- Signal source: option chain as context. Maps to: NSE yes, MCX yes, crypto yes.
- Mechanical? yes.
- Our research: IV1 tested IV skew, straddle, PCR and OI build-up as signals: none positive in every test period
  (HUNT_IV1_OPTION_MARKET.md). R102 section 3.4 describes skew and ATM IV at 09:20 (descriptive).

American options (7.8): never exercise an American call on a non-dividend stock early; with dividends only just before
a dividend date; the American put has no formula (an optimal-stopping problem). Not relevant to NSE index options
(European), relevant only to stock options.

### Ch. 8. Completeness and hedging
Any payoff on one stock can be replicated by holding "delta" shares (the option price's slope against spot) plus cash,
rebalanced continuously; the option is therefore "superfluous" and its price unique and independent of anyone's risk
appetite. Path-dependent averages (Asian options) are handled by adding the running average as a state. A rule of
thumb: with M traded risky assets and R sources of randomness, no arbitrage needs M <= R and completeness needs
M >= R. An exercise adds Poisson jumps: then the model is incomplete and an option has no unique price (relevant to
gap days on NIFTY and BANKNIFTY).

### Ch. 9. Parity relations and delta hedging (practical)
Pricing is linear, so any piecewise-linear payoff is a fixed (buy-and-hold) mix of cash, the underlying and calls: put
= call - stock + discounted strike (put-call parity); straddles, bull spreads and tent-shaped payoffs are built the same
way. The Greeks are defined (delta, gamma, theta, vega, rho). Delta hedging and delta-gamma hedging are described.

#### Method: put-call parity and static replication
- What it computes: a no-arbitrage identity linking call, put, underlying (or future) and cash at the same strike and
  expiry. With a future: call - put = discounted (F - K).
- How we could use it: (1) a data-quality check: a minute where parity breaks by more than costs usually means a stale
  or bad print (the very artefact that sank R103's passers); (2) a conversion/reversal arbitrage if it breaks by more
  than all costs on synchronous quotes (rules file).
- Signal source: volatility / options structure. Maps to: NSE index options with NIFTY/BANKNIFTY futures yes; MCX yes;
  crypto yes (Delta Exchange calls/puts with futures).
- Mechanical? yes.
- Our research: parity arbitrage not tested by us; R103's stale-wing artefact (43-63% of design trades on its six
  passers had a stale leg at exit) is the problem a parity check would flag.

#### Method: the Greeks (Ch. 9.2)
- What it computes: sensitivities of a book's value: delta (spot), gamma (delta's change), theta (time), vega
  (volatility), rho (rate). A book with a Greek of zero is "neutral" in it. An exercise shows that for any hedged book
  theta + r S delta + 0.5 sigma^2 S^2 gamma = r times value: a delta- and gamma-neutral book earns only the risk-free
  rate, i.e. the time decay a seller collects is exactly the payment for being short gamma.
- How we could use it: Jarvis explanations ("theta is the rent you are paid for gamma risk"), position risk display.
- Signal source: volatility / options structure. Maps to: NSE, MCX, crypto.
- Mechanical? yes.
- Our research: DEEP_OPTIONS_SELLING.md ("Theta is the pay, gamma is the bill") measured it: a normal NIFTY day burns
  12-14% of the 09:20 straddle, expiry day 87% by 15:15 (n 963 / 259).

#### Method: discrete delta hedging (Ch. 9.3)
- What it computes: the seller of an option buys delta units of the underlying, waits (a day, a minute), recomputes
  delta, rebalances, and finances the difference through the bank. In the continuous limit the hedge replicates the
  option exactly. The dilemma the book states: frequent rebalancing = good hedge but high transaction costs; gamma
  tells you how fast the hedge goes stale, so high gamma needs frequent rebalancing.
- Entry / stop / exit: not a trading rule in the book. Our derived rule (rules file: delta-hedged short straddle; and
  the long, gamma-scalping mirror) turns it into "sell implied, earn the gap to realised".
- Market and timeframe written for: generic continuous-time stock market.
- Signal source: volatility / options structure. Maps to: NSE index options hedged with index futures (needs several
  option lots per futures lot: the NIFTY future lot equals the option lot, so one lot cannot be hedged finely, see
  DEEP_OPTIONS_SELLING.md "Delta hedging"); MCX yes; crypto yes (fine-grained perpetuals).
- Mechanical? yes.
- Our research: MCX_OPTIONS.md measured option P&L delta-hedged every 15 minutes: the average MCX option loses for a
  buyer, and in the best cells delta-hedged P&L was about zero. A delta-hedged NSE straddle: not tested by us.

#### Method: delta-gamma hedging with two instruments (Ch. 9.3)
- What it computes: since the underlying has zero gamma, first use another option to cancel gamma, then the
  underlying (or future) to cancel delta; the two steps do not undo each other. Exercise numbers: a book with delta 2
  and gamma 3 hedged with options of (delta -1, gamma 2) and (delta 5, gamma -2).
- How we could use it: keeping a short-premium book's gamma inside a limit near expiry (the hour where one ATM option
  moves 13.5% a minute, R102).
- Signal source: volatility / options structure. Maps to: NSE, MCX, crypto.
- Mechanical? yes.
- Our research: not tested by us.

### Ch. 10. The martingale approach (theory, one warning)
With unlimited credit, a doubling strategy ("double the stake after each loss") is an arbitrage; real theory must
forbid strategies whose losses are unbounded below. Then the full Fundamental Theorems (Delbaen-Schachermayer) hold.
"Summary for the working economist": in an incomplete market the pricing measure is "chosen by the market", i.e.
option prices carry risk premia set by supply, demand and risk aversion.

### Ch. 11. Mathematics of the martingale approach (theory, one practical fact)
Martingale representation; Girsanov's theorem (changing probabilities shifts the drift, never the volatility).
Maximum-likelihood estimation for diffusions: the drift estimate of a Brownian motion with drift is simply the total
move divided by elapsed time, so it depends only on the first and last point; volatility, by contrast, is estimated
from every increment. Practical reading: direction (drift) needs very long samples to estimate, size (volatility)
does not.

### Ch. 12. Black-Scholes from a martingale point of view (theory)
The change of measure's kernel is minus the market price of risk (excess return per unit volatility); the model is
arbitrage-free and complete; the risk-neutral formula holds for every payoff, the PDE only for simple ones.

### Ch. 13. Multidimensional models, classical approach (practical: spread options)
Several stocks driven by as many Brownian motions; the pricing PDE uses the covariance matrix; drifts drop out; hedge
weights are each asset's delta. Payoffs that scale with the prices (homogeneous of degree one) reduce by one dimension.

#### Method: exchange (Margrabe) option and the volatility of a ratio (Ch. 13.4, 24.3)
- What it computes: the right to swap asset 2 for asset 1 is a call with strike 1 on the RATIO S1/S2, priced with zero
  rate and volatility sqrt(s1^2 + s2^2 - 2 rho s1 s2): price = S1 N(d1) - S2 N(d2), independent of interest rates.
  Exercises extend it to options on the maximum of two assets.
- How we could use it: the right way to think about relative-value trades between NIFTY and BANKNIFTY (or GOLD and
  SILVER): the value of a spread position depends on the ratio's volatility, which falls as correlation rises.
- Signal source: underlying price (two underlyings). Maps to: NSE indices (no listed exchange options; useful for
  sizing spread trades), MCX GOLD/SILVER yes, crypto BTC/ETH yes.
- Mechanical? yes (as a valuation); not a trading rule.
- Our research: R72 read the five indices together; only Breadth Donchian passed, and 77% of its trades are
  Donchian's own (HUNT_R72_CROSS_INDEX.md). Ratio-volatility valuation: not tested by us.

### Ch. 14. Multidimensional models, martingale approach (theory + one bound)
No arbitrage needs the volatility matrix to have full row rank; completeness needs as many Brownian motions as
assets. One market price of risk per random factor. Hansen-Jagannathan bound: no asset's Sharpe ratio can exceed the
volatility of the stochastic discount factor (the size of the market price of risk vector).

#### Method: Sharpe-ratio ceiling (Hansen-Jagannathan, Ch. 14.8)
- What it computes: an upper bound on any asset's excess return per unit risk, set by the market's price of risk.
- How we could use it: a sanity check on backtests: an annualised Sharpe far above what liquid markets price is more
  likely overfitting or an artefact (our reading, not the book's).
- Signal source: none. Maps to: all. Mechanical? partly (the bound needs an assumed SDF volatility).
- Our research: our studies use BH and holdouts for the same purpose (e.g. R103's design passers were an artefact).

### Ch. 15. Incomplete markets (practical concept: calibration)
A derivative on something that is not traded (the book's example: a contract paying 100 if the temperature on
Brighton pier is below 20 degrees C) cannot be replicated, so no unique price exists. But all derivatives on it must
share ONE market price of risk; fix one benchmark price and every other price follows. "Who chooses the martingale
measure? The market." Assuming a zero market price of risk silently assumes a risk-neutral market.

#### Method: calibration / implied parameters (Ch. 15.6, 22.2)
- What it computes: choose a parametric family, compute model prices of the traded contracts, and pick the parameters
  that minimise the squared gap to observed prices; then price everything else with those parameters.
- How we could use it: fit a smile or term structure to the NSE chain each morning; Jarvis can then say what the
  market prices in (expected range) rather than guess.
- Warning the book stresses (22.2): fitting pricing-measure parameters by statistics on historical prices is
  "nonsense" for the drift; only the volatility is common to both worlds. Corollary for us: an implied number (VIX,
  forward, futures) is a price including a risk premium, not an unbiased forecast.
- Signal source: option chain as context. Maps to: NSE yes, MCX yes, crypto yes. Mechanical? yes.
- Our research: R102: VIX over-states NIFTY's next month on 81% of days (median 1.28, n 4,507), consistent with a
  premium in implied prices (DEEP_NIFTY_BANKNIFTY.md 2.2). R16: next-day range "not beyond VIX" (VIX itself is the
  forecast to use).

### Ch. 16. Dividends
Discrete dividends make the price jump down by the dividend; options are priced backwards between dividend dates. A
proportional dividend is handled by scaling spot down by (1 - dividend fraction) per dividend before expiry. A
continuous dividend yield q makes the stock drift at r - q under the pricing measure; price = no-dividend formula with
spot S e^(-q T). Cost-of-carry and put-call parity with dividends follow.

#### Method: cost-of-carry fair value of an index future (Ch. 16, 26)
- What it computes: fair future = S e^((r - q) T); the basis above or below it is the market's carry.
- How we could use it: compute the NIFTY / BANKNIFTY fair basis and flag days when the traded basis is far from it;
  use the future (or synthetic forward) as the Black-76 input.
- Signal source: underlying price (spot and future). Maps to: NSE index futures yes, MCX yes (carry includes storage
  and convenience), crypto perpetuals (funding replaces carry).
- Mechanical? yes.
- Our research: h38 tested futures basis (and OI build-up, volume) as option-buying signals: NO, none made money after
  costs (HUNT_H38.md); h39 found basis changes move with the index in the same minute but do not lead it at 2-15
  minutes (HUNT_H39.md). Basis-vs-fair-value arbitrage: not tested by us.

### Ch. 17. Currency derivatives
A foreign currency behaves like a stock paying a continuous dividend equal to the foreign interest rate (Garman-
Kohlhagen call). Contracts on foreign stocks struck in either currency (quantos) are priced by converting everything to
domestic traded assets. The domestic and foreign market prices of risk differ by the FX volatility, so a market cannot
be risk-neutral in both currencies (a Jensen-inequality effect). Relevant only to INR-settled crypto options and
USDINR; not to index options.

### Ch. 18. Barrier options, ladders and lookbacks (practical: touch probabilities)
Distributions of the running maximum/minimum and of a Brownian motion absorbed at a level. A down-and-out contract is
priced from a normal contract with the payoff cut below the barrier, minus a reflected copy (spot replaced by L^2/s,
scaled by a power of L/s). In + out = plain contract. Ladders pay according to the highest band the price reached
(a sum of up-and-in digitals; a ladder call locks in the highest barrier crossed); lookbacks pay off the realised
maximum or minimum.

#### Method: barrier and touch probabilities (Ch. 18.1, Appendix C exercise C.8)
- What it computes: the probability that the price touches a level before expiry (reflection principle; with no drift
  the chance of touching an upper level is about twice the chance of finishing above it), and the chance of hitting a
  level a above before a level b below. For driftless Brownian motion: P(up level first) = b / (a + b), with a and b
  the distances; symmetric levels give one half.
- How we could use it: Jarvis can state, for a given volatility, how likely a stop or target is to be touched before
  15:05, and why a target twice as far as the stop is touched first only about one time in three when there is no edge
  (driftless case: stop distance / (stop + target) = 1/3).
- Signal source: underlying price (or premium if the levels are on the premium). Maps to: NSE, MCX, crypto.
- Mechanical? yes.
- Our research: our arms' win rates are reported per study (e.g. R19, R48); the touch-probability benchmark itself is
  not tested by us.

#### Method: ladders and lookbacks as the value of a lock or a perfect trail (Ch. 18.4-18.5)
- What it computes: a ladder pays the highest band reached (like a step profit-lock that ratchets up); a lookback
  pays the full best price (the value of a perfect trailing exit). Their premium over a plain option is the market
  value of "keeping the best price".
- How we could use it: an upper bound on what any trailing stop or step lock can capture; a way to price Boss's step
  locks (+Rs 1,000 keep Rs 500) under a volatility model.
- Signal source: volatility / options structure. Maps to: all. Mechanical? yes (valuation).
- Our research: R19 tested six profit locks on Liquidity 15+5: 0 of 12 passed, a lock sells the runners early
  (HUNT_R19_LIQUIDITY_LOCK.md). R18: none of five early-exit overlays helped any arm (HUNT_R18_EXITS.md).

### Ch. 19. Stochastic optimal control (practical: sizing)
Dynamic programming, the Hamilton-Jacobi-Bellman equation and the verification theorem; the linear-quadratic
regulator (Riccati equation). Problems must include a bankruptcy boundary or they become nonsensical (consume
infinitely, wealth goes negative). Merton's consumption-investment problem and the mutual fund theorems.

#### Method: Merton's constant risky fraction (Ch. 19.6)
- What it computes: with power utility, the optimal share of wealth in the risky asset is CONSTANT: (expected return -
  risk-free rate) / (variance x (1 - gamma)), and consumption is proportional to wealth. In the log-utility limit this
  is the growth-optimal (Kelly) fraction (our identification; the book does not use the word Kelly).
- Inputs: the risky asset's expected excess return and variance; a risk-aversion parameter.
- How we could use it: size an arm with a positive, stable per-trade edge as a fixed fraction of capital, scaled down
  (fractional Kelly) because the expected return is the badly-estimated input (Ch. 11).
- Signal source: none (sizing). Maps to: all arms; crypto requires stop and profit lock regardless.
- Mechanical? yes.
- Our research: h15 tested half-Kelly on Liquidity 15+5: fastest growth, but P(drawdown >= 30%) 63% and P(drawdown
  >= 50%) 14%; the Kelly fraction was unstable (0.48 and 0.14 on other windows against 1.29) and the SPA p = 0.14
  could not separate sizings (HUNT_H15.md). HUNT_FINAL / h36 chose fixed lots.

#### Method: mutual fund theorem / mean-variance allocation across books (Ch. 19.7)
- What it computes: with a risk-free asset, every investor holds cash plus ONE risky fund whose weights are proportional
  to (covariance matrix)^-1 x (expected excess returns); only the split between cash and that fund depends on risk
  appetite.
- How we could use it: allocate capital across the arms with a record (Liquidity 15+5, R72 Breadth Donchian, R51,
  R52) from their daily P&L covariance, estimated walk-forward and shrunk.
- Signal source: none (allocation). Maps to: all. Mechanical? yes.
- Our research: not tested by us (HUNT_R74_STRATEGY_MAP.md lists volatility-targeted sizing as "not tried for the
  index arms"). R72 notes Breadth Donchian and Donchian correlate 0.80 daily, exactly the overlap this method penalises.

### Ch. 20. Bonds and interest rates (definitions)
Zero-coupon bonds, simple (LIBOR) and continuous forward and spot rates, the money account; coupon bonds as bundles
of zeros; a floating-rate bond is worth par at reset; interest-rate swaps and the swap rate; yield to maturity;
duration (a bond's "delta" to yield) and convexity (its "gamma").

### Ch. 21. Short-rate models (theory)
The short rate is not a traded price, so its real-world dynamics do not fix bond prices; all bonds share one market
price of risk; the term-structure PDE; bond price = risk-neutral expectation of discounting.

### Ch. 22. Martingale models for the short rate (theory + mean reversion)
Vasicek, CIR, Dothan, Black-Derman-Toy, Ho-Lee, Hull-White; affine term structures (Riccati equations); fitting the
initial yield curve exactly with time-dependent parameters, with a warning that an infinite-parameter fit is
numerically unstable; bond-option formulas.

#### Method: Ornstein-Uhlenbeck (Vasicek) mean reversion (Ch. 5.3, 22.4)
- What it computes: a process pulled back to a level b/a at speed a with noise sigma; its long-run distribution is
  normal with mean b/a and standard deviation sigma / sqrt(2a); the half-life of a deviation is ln 2 / a.
- How we could use it: the standard model for a spread (e.g. the NIFTY/BANKNIFTY log-ratio or GOLD/SILVER) or for
  implied volatility itself; estimate a, b, sigma on past data only and read a z-score.
- Signal source: underlying price (spread of two underlyings). Maps to: NSE indices, MCX pairs, crypto BTC/ETH.
- Mechanical? yes, but the book gives no trading rule on it.
- Our research: R102 2.5 measured lag-1 autocorrelation by horizon (descriptive); a spread mean-reversion rule: not
  tested by us.

### Ch. 23. Forward-rate models (Heath-Jarrow-Morton) (theory)
Model the whole forward curve; under the pricing measure the drift is fixed by the volatility (HJM drift condition);
constant volatility gives Ho-Lee; the Musiela time-to-maturity form. Not relevant to our markets.

### Ch. 24. Change of numeraire (theory with a practical trick)
Pick the numeraire that simplifies the problem: with a bond as numeraire (the forward measure) a price is today's bond
price times a single expectation; with one stock as numeraire the exchange option becomes a call on the ratio. The
"forward rate = expected future rate" hypothesis is false under both real and risk-neutral probabilities; it holds only
under the forward measure. A numeraire must be a traded asset without dividends (not an index level, not a futures
quote). General option formula (Geman-El Karoui-Rochet); caps as portfolios of bond puts.

### Ch. 25. LIBOR and swap market models (theory + market practice)
The market quotes caps and swaptions in Black-76 implied volatilities (flat versus forward volatilities); the LIBOR
and swap market models make each rate lognormal under its own numeraire, reproducing Black's formula. Calibration to
implied volatilities is underdetermined, so practitioners impose a simple volatility shape; exotics are then priced
by Monte Carlo on log rates. The two model families are incompatible.

### Ch. 26. Forwards and futures
A forward fixes the price paid at delivery; a futures contract is marked to market daily, costs nothing to enter, and
its quotation is the risk-neutral expectation of the underlying; the two coincide when rates are deterministic.
Futures are rarely delivered; "futures price" is a quotation nobody pays. An option can be replicated with futures
plus cash.

### Appendix A. Measure and integration (theory)
Sigma-algebras, measures, the Lebesgue integral, the convergence theorems, Lp and Hilbert spaces, the projection and
Riesz theorems, Borel sets, Fubini, Radon-Nikodym.

### Appendix B. Probability theory (theory, one practical idea)
Information as partitions and sigma-algebras; an adapted process "does not look into the future" (the formal version of
our look-ahead guard); conditional expectation as the best mean-square predictor given the information; equivalent
measures and the abstract Bayes formula.

### Appendix C. Martingales and stopping times (theory, one practical idea)
A predictable betting scheme on a martingale is still a martingale, and a martingale stopped at a stopping time is
still a martingale (optional sampling). Plainly: if the price has no drift, no combination of position sizing, stops,
targets or exit timing creates a positive expected profit; exits can reshape the distribution (win rate versus size
of win), not its mean. Exercise C.8 gives the "hit a before b" probabilities used above.

## Performance claims
None. The book makes no claim about trading profits, win rates or returns. Its claims are mathematical theorems
(proved, or proofs cited) under stated assumptions: no transaction costs, no bid-ask spread, unlimited short selling
and borrowing, continuous trading, and (for Black-Scholes) constant volatility. The book itself notes the smile
contradicts constant volatility, and that transaction costs make continuous hedging impossible.

## Risk, sizing and psychology
- Risk: the core idea is that an option seller removes risk by hedging, not by forecasting; hedging frequency trades
  hedge quality against costs (Ch. 9). Jumps make hedging imperfect and prices non-unique (Ch. 8 exercise, Ch. 11).
- Sizing: Merton's constant fraction and the mutual fund theorem (Ch. 19); the doubling strategy works only with
  unlimited credit and is excluded by any realistic bound on losses (Ch. 10).
- Psychology: none.

## How it relates to our research
- Agrees: "Price depends on volatility, not on expected direction" fits our finding that direction is a coin flip
  while size is predictable (R16, R41: direction AUC 0.510-0.517; R88: 0 of 8 direction findings held). The
  maximum-likelihood point (drift uses only the endpoints) explains why.
- Agrees: implied quantities carry a premium chosen by the market (Ch. 15, 22.2, 24.4); R102's VIX above realised on
  81% of NIFTY days (n 4,507) is that premium, and the reason option buying bleeds (DEEP_OPTIONS_SELLING.md).
- Agrees: theta is the price of gamma (Ch. 9 exercise); R102/R103 measured the bill (expiry-day straddle burn 87%,
  worst naked-straddle days near -Rs 29k per lot).
- Agrees: no exit rule can create edge on a driftless price (Appendix C); our R18 (0 design candidates of 60) and R19
  (0 of 12 locks) found exits did not add value.
- Already rejected: option-chain and basis signals as direction (IV1, h29, h38); defined-risk selling (R103: 0 of 24).
- Not tested: delta-hedged straddles (the book's hedge as a volatility trade on NSE), parity arbitrage, mean-variance
  allocation across arms, ratio-volatility valuation of NIFTY/BANKNIFTY spreads. See the rules file.
- Bennett's book (research/books/bennett-trading-volatility.md) covers the practitioner side (variance swaps, skew,
  term structure); this book gives the theory beneath it. Not repeated here.

## What is new to us
1. A delta-hedged straddle as a pure implied-versus-realised trade on NIFTY (sell) or BANKNIFTY (where VIX
   under-states realised on 60% of days) - our selling tests used wings and stops, never a futures hedge.
2. Put-call parity as a stale-print filter for every option backtest (R103's artefact).
3. Touch probabilities (reflection principle, hit-a-before-b) as Jarvis's honest baseline for stop/target odds.
4. Mean-variance (Merton mutual fund) allocation across the arms with a record.
5. Valuing step locks and trails as ladder/lookback options, giving an upper bound on what a lock can capture.
6. Black-76 on futures for MCX options and a synthetic-forward input for NIFTY.

## Could not read
The text layer drops or scrambles most displayed formulas (e.g. the Black-Scholes d1/d2 expressions, Greeks formulas in
Proposition 9.5, the barrier, lookback, Vasicek/CIR/Hull-White and LIBOR formulas); these were reconstructed only
where standard. All figures are images (binomial trees Figs 2.1-2.10, Wiener and GBM paths Figs 4.1, 5.1-5.2, payoff
and price graphs Figs 7.1-7.3, Greeks Figs 9.1-9.7). A few pages are scanning noise (back of the preface, the end of
Ch. 6 and of Ch. 8). The references and index were skimmed, not studied.
