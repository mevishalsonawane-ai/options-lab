# An Introduction to the Mathematics of Financial Derivatives (2nd ed.) - Salih N. Neftci (2000), with its Solution Manual

Source: `an-introduction-to-the-financial-derivatives` (main text, 255 scanned PDF pages, each one a two-page spread, so
book pages 1-509: Chapters 1-22 and the first page of the references) and
`an-introduction-to-the-mathematics-of-financial-derivatives-` (Solution Manual, 90 pages, author not stated in the file),
read in full on 25 Oct 2026. How: the supplied tesseract OCR of the main text was unusable (every spread is rotated 90
degrees, so it came out as mirrored gibberish); we re-ran OCR on the rotated page images (good prose quality on all 255
spreads) and also OCR'd the separate Contents, Introduction and Bibliography/Index files. The Solution Manual has a text
layer. Unreadable parts are listed at the end (mostly formulas and figures).
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A gentle, informal textbook of the mathematics behind derivative pricing, written for finance readers, not mathematicians.
It starts from the arbitrage theorem (state prices, risk-neutral probabilities) and the binomial tree.
It builds stochastic calculus step by step: martingales, the Wiener and Poisson processes, the Ito integral, Ito's lemma, SDEs.
It derives Black-Scholes twice (risk-free portfolio and PDE; Girsanov and the martingale measure) and shows they agree.
The last third covers interest-rate models (forward measure, classical and HJM), Feynman-Kac, and American options as optimal stopping.

The book has no market data, no backtest and no trading rule. Every number is a classroom example in US dollars.
Its value for us is conceptual: why options do not price direction, what delta hedging can and cannot do, how jumps
("rare events") differ from ordinary volatility, how to price by tree, PDE grid and Monte Carlo, and how to decide when
to stop (exercise or exit) by dynamic programming. About a third of the book (Ch. 16-21) is fixed income and has no
use for NSE index options, MCX or crypto; it gets a line or two per chapter. Volatility trading itself is covered far
better in `research/books/bennett-trading-volatility.md`; this note does not repeat it.

Our data, for the mapping: NIFTY weekly and BANKNIFTY monthly index options (European, cash settled), a 1-minute chain
of the nearest expiry within ATM +-10 strikes with IV and OI (last prices, no bid/ask), index spot and futures bars,
India VIX, MCX futures and options (options on futures), Delta Exchange crypto options. Costs about Rs 70 (NIFTY) /
Rs 76 (BANKNIFTY) per option round trip (DEEP_NIFTY_BANKNIFTY.md, R102 summary in FINDINGS.md).

## Methods, rules and setups (chapter by chapter)

### Ch. 1 - Financial derivatives, a brief introduction
Definitions of forwards and futures (linear, marked to market daily), options (non-linear; the value curve is smooth
before expiry and only gets its kink at the strike on expiry day) and swaps (which can always be broken into forwards and
options and priced piece by piece). Two kinds of underlying: cash-and-carry markets (gold, silver, currencies, bonds),
where one can borrow, buy, store and insure, so the futures-spot gap is set by interest, storage and insurance and news
moves both prices alike; and price-discovery markets (perishable or not-yet-harvested goods), where storage is
impossible and the futures market itself discovers the price. A footnote warns that storing crude oil can be very
expensive. The exercises (payoff diagrams of short stock plus short call, a put-and-call strangle, risk reversals; a
wheat futures arbitrage; a forward and put-call parity arbitrage) are solved in the Solution Manual.

### 1. Cash-and-carry fair value and its no-arbitrage band (Ch. 1, Ch. 4; Solution Manual Ch. 1)
- What it computes: the futures price that leaves no riskless profit: spot grown at the interest rate plus the carry
  costs (storage and insurance) to delivery. For an asset held for investment (gold) the relation is an equality; for a
  consumption good (the book's wheat) only a band holds: futures can sit anywhere between spot grown at the rate and
  spot-plus-carry grown at the rate, because holders of the physical will not sell it just to arbitrage (they want it
  for use). The manual's wheat example: spot 1,470, rate 5%, futures quoted at 1,500 is below the lower bound of
  1,543.50, so buying futures and selling the physical earns 43.50 riskless.
- Inputs: spot, rate, storage and insurance cost, time to delivery (and the dividend or convenience yield).
- How we could use it: a sanity check on MCX futures against spot (gold and silver should hug carry; crude and natural
  gas can sit far from it because storage is costly and they are consumption goods); for NIFTY/BANKNIFTY the same idea
  is the futures premium over spot as an implied financing rate minus dividend yield.
- Signal source: volatility / options structure (carry relation, no direction). Maps to: NSE index futures yes; MCX
  futures yes (needs a spot series we do not hold); crypto dated futures and perpetual basis yes.
- Mechanical? yes (a formula), but arbitrage needs the physical or the cash basket.
- Our research: H39 (HUNT_H39.md) found heavyweight stock options move with the index, not before it; H29
  (HUNT_H29.md) found the option-implied synthetic forward "leads" the index by 5-30 minutes (IC +5 to +13%) only
  because the index catches up to options that have already moved, so a buyer pays for a move already in the price.
  Cash-and-carry arbitrage itself: not tested by us (no MCX spot data, no cash basket).

### Ch. 2 - A primer on the arbitrage theorem
Arbitrage of the first kind (no money down, a sure profit) and the second kind (money back today, never a loss later).
Four uses of an arbitrage-free price: pricing a brand-new product, stress scenarios (with the warning that in an
extreme shock markets may not be liquid enough for no-arbitrage to hold), marking an illiquid position, and spotting a
mispricing ("if the fair value is above the market, the derivative is cheap"). The formal machinery: a payoff matrix
over states of the world, positive state prices, and risk-adjusted ("synthetic") probabilities under which every asset
earns the risk-free rate and discounted prices are martingales. Under the true probabilities, risky assets earn a
premium, so their discounted prices drift upward (submartingales). Worked example: a stock at 100 that goes to 100 or
150, rate 10%: state prices 0.7273 and 0.1818, call worth 9.09; a quoted 25 is an arbitrage. With three states and two
assets the price is not unique (an incomplete market). Dividends change the underlying's risk-neutral drift to rate
minus dividend yield but leave the option's discounting unchanged; currencies use the rate difference. The chapter ends
with a five-step pricing recipe: model the underlying, write the payoff at expiry, find the risk-adjusted
probabilities, take the expected payoff, discount at the risk-free rate.

### 2. Binomial (lattice) pricing, including American exercise and dividends (Ch. 2, Ch. 8, Ch. 22; Solution Manual Ch. 2, 22)
- What it computes: an option value by moving the underlying up or down by a fixed multiple each step (the size set
  by volatility times the square root of the step), solving the up-probability that makes the discounted underlying a
  fair game, filling in payoffs at expiry and rolling back. For an American option each node takes the larger of
  exercising now or holding (the value of continuing).
- Inputs: spot, strike, volatility, rate, dividend yield or discrete dividends, steps.
- Worked numbers in the manual: a 4-step call with no dividends is 5.15 against a Black-Scholes 5.34 (it is never
  optimal to exercise early); a 4% continuous dividend lowers it to 3.80 (Black-Scholes 4.01); a 5% or $5 discrete
  dividend at the third node makes exercise just before the dividend optimal and the value 4.61. A 3-step replication
  example (buy 0.173 shares and borrow 16.113) gives a call of 1.53; if the market quotes it at 5, selling it and
  replicating locks in 3.47.
- How we could use it: pricing MCX options (American on futures), Delta Exchange crypto options and stock options
  where early exercise or dividends matter; cross-checking the app's Black-Scholes values; repricing legs with no
  recent print.
- Signal source: volatility / options structure (a pricing tool, no signal). Maps to: NSE index options yes (European,
  so a tree only reproduces Black-Scholes); MCX options yes; crypto options yes.
- Mechanical? yes.
- Our research: R103 (HUNT_R103_OPTIONS_SELLING.md) repriced far legs that had stopped printing with Black-Scholes at
  their last IV; the six "passing" BANKNIFTY selling tests turned into -Rs 2.9 to -Rs 3.3 lakh losses. A model price is
  the right tool for stale legs. A tree for MCX options: not tested by us.

### Ch. 3 - Calculus in deterministic and stochastic environments
A review of functions, limits, derivatives, the chain rule, integrals and Taylor series, with bond duration and
convexity as the worked example (the bigger the rate move, the smaller its relative effect). The practical messages:
price paths have unbounded variation, so the ordinary derivative and chain rule fail for them; for a smooth function
it does not matter where in each slice an integral's rectangle is measured, but for a random path it does. A side
remark: on volatile days an analyst may want a finer observation interval (a time-change idea). Theory otherwise.

### Ch. 4 - Pricing derivatives: models and notation
Two routes to a price: an equivalent martingale measure, or a risk-free portfolio that yields a PDE. A gold forward
equals spot grown at the rate plus storage and insurance costs (method 1). Then delta hedging is introduced
(method 3) and a first look at why squared changes cannot be dropped when prices are random. The election-bet exercise
shows that a hedged pair of bets makes the true probability irrelevant.

### 3. Delta hedging, and why it fails on big moves (Ch. 4, Ch. 12)
- What it computes: hold one option and short "delta" units of the underlying (the slope of the option value curve),
  so small moves cancel. Figure 2 of Ch. 4 shows that a large move breaks the hedge: the option gains far more than
  the short leg loses (the curvature, gamma). The hedge needs continuous re-balancing and no transaction costs. In
  Ch. 12 the author shows that because delta itself changes with the price, the hedged book is not self-financing: it
  keeps needing or releasing cash (a term in gamma times the squared price), and this leak averages to zero only under
  the risk-neutral probability, not under real-world odds.
- Inputs: option delta (from Black-Scholes or a tree), the underlying's price, a re-hedge schedule.
- How we could use it: as the mental model for a short straddle (short gamma: steady theta, big losses on gaps); for
  sizing a futures hedge on multi-lot MCX or crypto option positions.
- Signal source: volatility / options structure. Maps to: NSE index options partly (one lot cannot be hedged: the
  NIFTY future lot equals the option lot, so a 0.2-delta hedge would be 13 units, which does not exist); MCX yes for
  several lots; crypto yes (fractional perpetuals).
- Mechanical? yes.
- Our research: DEEP_OPTIONS_SELLING.md section 3 explains that a hedger's P&L is implied minus realised variance and
  that a one-lot seller can only hedge with wings and stops; MCX_OPTIONS.md measured long options delta-hedged every 15
  minutes: buyers bleed -2% to -4.5% of premium per hour on expiry day. A delta-hedged NIFTY volatility trade: not
  tested by us.

### Ch. 5 - Tools in probability theory
Probability spaces (a USDA harvest report as the "state of the world" for a grain-futures day), moments, conditional
expectation as the formal version of a forecast. Heavy tails are described well: compared with the normal curve, the
passage from ordinary to extreme is sudden, the middle of the tails is thinner and there are "too many" extremes,
common in financial data. A binomial tick model (up or down by a fixed amount times the square root of the step)
tends to the normal distribution; a Poisson process models jumps (the 1987 crash). Markov processes: the future depends
only on the latest value; a single interest rate is generally not Markov, though a vector of rates can be, and
Monte Carlo and PDE methods are only interchangeable for Markov processes. Three kinds of convergence (mean square,
almost sure, weak), the last being what justifies a binomial tree.

### Ch. 6 - Martingales and martingale representations
A martingale has no forecastable trend; stocks and bonds are not martingales under real-world odds (they earn a
premium) and a European option's time value makes it a supermartingale. Two ways to remove a trend: subtract it
(Doob-Meyer; needs the unknown risk premium) or change the probabilities (Girsanov; what practice uses). Martingale
paths have infinite length but finite sum of squared changes, so small increments may as well be treated as normal.
A good-news/bad-news Poisson difference is a martingale only if both arrive at the same rate. The first stochastic
integral is a trader's gains: holdings fixed before each price change times the change (a self-financing strategy).
The pricing recipe: replicate, normalise by the money-market account, switch to risk-neutral odds.

### Ch. 7 - Differentiation in stochastic environments
A price change is split into a forecastable part and an innovation ("news", what traders watch live). Following
Merton, three plain assumptions (risk never vanishes with finer sampling, it never explodes with more frequent
trading, and no single instant holds all the risk, which rules out lottery-like markets) imply that the variance of
the innovation is proportional to the length of the interval.

### 4. Square-root-of-time scaling (Ch. 7, Ch. 8)
- What it computes: if variance grows in proportion to time, the typical move over a window is volatility times the
  square root of the window; the drift grows in proportion to time and is negligible over short windows.
- Inputs: annualised volatility (India VIX, ATM IV, or realised), window length.
- How we could use it: turning VIX or ATM IV into an expected rupee move for the next hour or until expiry, for stop
  and target sizing.
- Signal source: volatility / options structure (a sizing yardstick, no direction). Maps to: NSE yes, MCX yes, crypto
  yes (24x7 clock).
- Mechanical? yes.
- Our research: R16 (HUNT_R16_PREDICTABILITY.md) found the next 30-60 minutes' range predictable, with holdout error
  15-20% below the time-of-day average and 10-15% below VIX-implied: plain scaling misses the intraday shape (R102: 61%
  of NIFTY's day range is made by 10:30). R102 also found VIX over-states NIFTY's next 21 days on 81% of days (median
  ratio 1.28, n 4,507 days). So scaled VIX is a baseline, not the forecast.

### Ch. 8 - The Wiener process and rare events in financial markets
The most practical chapter. On an ordinary day a liquid price moves one tick up, one tick down or not at all, but the
extreme days are exactly when accurate pricing matters most. Turbulence (higher volatility) is not the same as a rare
event. Normal events shrink with the observation window while their probability stays the same; rare events keep
their size while their probability shrinks with the window. A crash is about the same size whether you look at ten
minutes or a whole day. Both give a variance proportional to the window, so variance alone cannot tell them apart. For
normal events only the mean and variance matter over short intervals; for rare events every higher moment (skew,
kurtosis) still carries information. Commodity futures jump more often than financial ones (one crop report can move
grain futures). The chapter ends with two binomial set-ups: "normal" (step size shrinks, probabilities near one half,
the sum tends to the normal) and "rare" (fixed jump size, jump probability proportional to the step, the sum tends to
Poisson).

### 5. Rare-event (jump) diagnosis and jump-diffusion (Ch. 8, Ch. 10)
- What it computes: a price model with a continuous part (Wiener) plus a compensated Poisson jump part, the two
  independent; Ito's lemma gets an extra jump term (the expected jump effect in the drift plus the actual jump when one
  happens). Practical consequence stated by the author: Value at Risk can use the normal distribution only if jumps are
  not a systematic part of the market; otherwise VaR must model the jump process.
- Inputs: jump rate (how many per year), jump size distribution, continuous volatility.
- How we could use it: (a) a diagnostic: if kurtosis of returns stays high as the sampling interval shrinks, jumps
  dominate; (b) stress losses for any short-gamma position from the gap distribution rather than from VIX; (c) jump
  windows as known event days (results, budget, election count, RBI, crop and inventory reports on MCX).
- Signal source: volatility / options structure (risk model). Maps to: NSE yes (overnight gaps are jumps for an
  intraday option); MCX yes (inventory and crop reports); crypto yes (24x7 but with liquidation cascades).
- Mechanical? yes as a risk measure; no trade rule.
- Our research: DEEP_OPTIONS_SELLING.md section 5: NIFTY daily kurtosis 14 and BANKNIFTY 8.5 (normal is 3); NIFTY's
  worst day -12.98% (23 Mar 2020). R102: gaps of 0.5% or more on 20% of NIFTY and 34% of BANKNIFTY days (n 5,086). R103:
  the naked 09:20 short straddle made money on average but its worst day was -Rs 28.9k (NIFTY) / -Rs 29.3k (BANKNIFTY)
  on one lot, the book's point in rupees. A fitted jump-diffusion: not tested by us.

### Ch. 9 - Integration in stochastic environments: the Ito integral
The Ito integral is the mean-square limit of sums whose integrand is fixed at the start of each slice (non-anticipating).
Measuring it at the middle or end of the slice gives a different, predictable answer. A striking example: a pathwise
sum whose integrand "knows" the sign of the next move grows without bound, which is the mathematician's way of saying
that look-ahead makes infinite money. Integrals against pure-jump processes can be done path by path. Practical note:
numerical work must respect the left-point convention.

### 6. Non-anticipation as a backtest rule (Ch. 6, Ch. 9)
- What it says: gains from trading are holdings decided before the price change times that change; any rule whose
  position uses information from the same or a later bar is the "anticipating" integrand that blows up.
- How we could use it: it is exactly our look-ahead guard (research/brain features catalog check); fills at the next
  bar's price, features computed on closed bars only.
- Signal source: none (method). Maps to: all markets. Mechanical? yes.
- Our research: built into every study (CLAUDE.md, look-ahead guard); R102 found the one "confirmed" 1-minute momentum
  rule (+0.11 bps, 853,718 pairs) was the cash index's staleness, a look-ahead-like artefact below cost.

### Ch. 10 - Ito's lemma
Partial derivatives are hypothetical multipliers (delta is one), the total change is what a trader sees. Over a short
step the squared random change is of the same order as the time step, so the second-derivative (gamma) term stays in
the chain rule. Cross terms and higher powers can be dropped only if there are no jumps. Multivariate version (two
correlated rates), the change in a portfolio's wealth (an extra quantity-times-price cross term), and the jump
version. Using ordinary calculus is the same as assuming zero volatility.

### Ch. 11 - The dynamics of derivative prices (SDEs)
The SDE depends on who is forecasting: an insider who knew every future move would have no random term. Strong versus
weak solutions (pricing uses weak ones: only drift and volatility are known). Geometric Brownian motion's solution,
with the half-variance correction; ordinary calculus would get the expectation wrong. The chapter closes with a
practical catalogue of SDEs (method 7) and stochastic volatility, and advises learning SDEs by solving them numerically.

### 7. A catalogue of price models and when each fits (Ch. 11, Ch. 19, Ch. 21)
- What it gives: arithmetic Brownian motion (fluctuations around a straight line that do not grow); geometric
  (Black-Scholes; exponential trend, fluctuations grow with price, may add too much variation); square-root (variance
  proportional to the price level, so sigma is not a percentage volatility); mean-reverting (excursions away from a
  long-run level, their length set by the reversion speed; the author notes such predictable cycles are usually
  inconsistent with an efficient market and that the model is mostly used for interest rates); Ornstein-Uhlenbeck
  around zero; stochastic volatility (volatility itself mean-reverts with its own shock, which adds a risk that may not
  be hedgeable). The author's simulations use 1,000 Euler steps of 0.001.
- How we could use it: geometric for index levels and option pricing; mean-reverting or Ornstein-Uhlenbeck for
  spreads (BANKNIFTY vs NIFTY ratio, futures basis), India VIX and realised volatility; stochastic volatility to explain
  the skew and why short-gamma sizing must assume volatility can jump.
- Signal source: underlying price/volume (model fitted to the underlying); a spread model would be a pairs signal.
  Maps to: NSE yes, MCX yes, crypto yes.
- Mechanical? yes (models), no trading rule given.
- Our research: R16 found range (size) predictable and direction not (holdout AUC 0.509-0.536), consistent with a
  volatility process that mean-reverts while price direction does not. Pairs on BANKNIFTY/NIFTY: R72
  (HUNT_R72_CROSS_INDEX.md) is the closest cross-index study. An Ornstein-Uhlenbeck spread trade: not tested by us.

### Ch. 12 - Pricing derivative products: PDEs
The risk-free portfolio argument (the derivative and the underlying share one source of risk, so the right mix is
riskless and must earn the risk-free rate, less any dividend) gives the Black-Scholes PDE plus the payoff as its
boundary. The "accuracy" section is unusually honest: the portfolio is not truly self-financing (method 3). Boundary
conditions are the contract's clauses. A primer on PDE types (finance PDEs are parabolic) with toy examples.

### Ch. 13 - The Black-Scholes PDE: an application
The closed form (call = spot times N(d1) minus discounted strike times N(d2)). The Black-Scholes surface over price and
time-to-expiry is fixed once strike, rate and volatility are fixed; news only moves you along it, so with one random
factor the option's path is fully determined by the underlying's path. The assumptions are listed: no dividends,
European (an American call on a non-dividend stock obeys the same PDE), constant rate, no transaction costs or
indivisibilities. A constant dividend adds one term. Exotic options and a numerical finite-difference method follow.

### 8. Black-Scholes value and its assumptions (Ch. 13, Ch. 15, Ch. 16)
- What it computes: the European option value from spot, strike, time, rate, volatility (and dividend yield); the
  same formula is derived in Ch. 15 by taking the risk-neutral expectation, which shows the underlying's expected return
  never enters. Ch. 16 adds that Black-Scholes stays reasonably accurate even when volatility or rates are random
  (citing El Karoui and others), which is why practitioners keep using it, and that it is fine for short-dated options.
- How we could use it: implied volatility from chain prices, Greeks, repricing stale legs, the expiry decay clock,
  stop and target levels on the premium translated from underlying levels (the surface maps one to the other).
- Signal source: volatility / options structure. Maps to: NSE yes (European, cash settled); MCX use the futures
  version (Black-76); crypto yes.
- Mechanical? yes.
- Our research: DEEP_OPTIONS_SELLING.md uses a Black-Scholes table at NIFTY 25,000 and 15% IV; R102's decay clock
  (84% of the 09:20 straddle left at 10:30, 22% at 15:00, NIFTY, 259 expiries) and put skew (+2.56 IV points on 91% of
  NIFTY days) are where the constant-volatility assumption visibly breaks. R89 (FINDINGS.md): straddles bought when a big
  candle was forecast lost (2,214 straddles out of sample, -Rs 4.4 lakh, 0 of 18 versions positive) because the options
  already priced the move, as the formula implies.

### 9. Exotic payoffs: ladder, lookback, barrier, spread and average options (Ch. 13, Ch. 15, Ch. 22)
- What they are: a lookback call pays the final price minus the lowest price seen (or the highest minus a fixed
  strike), so it is dearer; a ladder option locks in its gain each time the underlying crosses a preset threshold; a
  knock-out (knock-in) dies (starts) when a barrier is touched, with a rebate; basket options damp volatility; a spread
  call pays the excess of one asset over another minus a strike; Asian options pay on an average. The chooser option
  (choose call or put later) equals a call plus a put with an adjusted strike (Solution Manual Ch. 15). Exotics change
  the boundary conditions, can have random expiry and several underlyings.
- How we could use it: as a pricing lens on our own trade management. A stop-loss turns a long option into something
  like a knock-out; a step profit lock is a ladder; a trailing lock is lookback-like. Each of these features has a cost
  that can be priced: locking gains early gives up part of the upside, the option-pricing view of "no free lock".
- Signal source: volatility / options structure. Maps to: none of these trade on NSE or MCX; the lens applies to any
  exit rule on any market, including the stop-and-lock that CLAUDE.md requires on every crypto trade.
- Mechanical? yes (as payoff definitions).
- Our research: R19 (FINDINGS.md "Profit lock for Liquidity 15+5") found no lock helps Liquidity, R19b found Boss's
  Rs 500 give-back trail fails and R100 found 0 of 15 give-back protections pass: consistent with locks having a price.
  Pricing our exits as exotic options: not tested by us.

### 10. Finite-difference (PDE grid) pricing (Ch. 13; Solution Manual Ch. 12)
- What it computes: the option value on a grid of prices and times by replacing derivatives with differences, starting
  from the payoff at expiry and two side boundaries (far below the strike the call is worth 0; far above it is the price
  minus the discounted strike) and stepping back in time. Grid steps are chosen "by trial and error". The manual's
  worked grid (explicit scheme, five price nodes) gives 0, 0.2342, 1.1428, 3.2342 and 6 at the start; it names
  Crank-Nicolson as the better scheme.
- How we could use it: American MCX options and barrier-like exits where a closed form does not exist. Ch. 21 adds that
  for American-style products a PDE grid is usually more convenient than Monte Carlo.
- Signal source: volatility / options structure. Maps to: MCX yes, crypto yes, NSE not needed (closed form exists).
- Mechanical? yes. Our research: not tested by us.

### Ch. 14 - Pricing derivative products: equivalent martingale measures
Two ways to change a mean: shift the values (needs the risk premium, which is unknown before the price is known) or
reweight the probabilities (the Girsanov route; the die example moves the mean from 2 to 1 while keeping the variance at
98/3). The Radon-Nikodym derivative, equivalent measures (same impossible events), the Novikov condition and the
Girsanov theorem. The pricing recipe in practice: pick the risk-neutral measure by its first two moments, calibrate the
volatility (from liquid options, "with care"), then compute the expectation in closed form or by Monte Carlo.

### 11. Risk-neutral odds are for pricing, real-world odds are for forecasting (Ch. 14 section 6, Ch. 17, Ch. 19)
- What it says: risk-neutral probabilities price assets without modelling the risk premium, but used as a forecast they
  give "miserable results" because under them everything grows at the risk-free rate. Forecasts must use real-world
  odds. Likewise forward rates are unbiased forecasts only under the matching forward measure, and econometric
  (real-world) estimates of a model are not arbitrage-free prices.
- How we could use it: never read option-implied quantities (implied move, max pain, PCR-implied odds, the implied
  forward) as a directional forecast; the implied move is a price, often an over-estimate.
- Signal source: option chain as context. Maps to: NSE, MCX, crypto alike. Mechanical? n/a (a principle).
- Our research: agrees with IV1 (HUNT_IV1_OPTION_MARKET.md): option-market signals alone lost after costs, the fixed
  "smart money" chain rule lost on all five indices (7,339 trades); R16: max pain was worse than "no change" as an
  expiry forecast; H29: the index moved less by 15:10 than IV priced in (0.62-0.73 of it before the holdout).

### 12. Monte Carlo pricing and its two error sources (Ch. 14, Ch. 19, Ch. 22; Solution Manual Ch. 11, 17)
- What it computes: the average discounted payoff over many simulated risk-neutral paths; by the law of large numbers
  the error shrinks with more paths if the randomness is modelled correctly. The manual adds the key caveat: there are
  two errors, the time-step (discretisation) error and the sampling error, and more paths only converge to the coarse
  model's answer (a 5-step simulation with 1,000 paths gave 0.0381 with normal shocks and 0.0388 with uniform ones of
  the same variance). Ch. 19 warns that the Euler scheme is only first order and can build up large cumulative errors.
  Path-dependent European payoffs (averages) are natural Monte Carlo cases; the manual's rate example shows that
  expectation of a product is not the product of expectations when discount and payoff are correlated.
- How we could use it: pricing path-dependent exits; risk of ruin and drawdown for sizing via bootstrapped P&L paths.
- Signal source: none (method). Maps to: all. Mechanical? yes.
- Our research: H16 and H15 (HUNT_H16.md, HUNT_H15.md) used a stationary-bootstrap Monte Carlo (2,000 paths, 10-day
  blocks) for P(ruin) on Liquidity 15+5: 4.9% over one year when resampling all pre-holdout days, and H15 warned the
  bootstrap was optimistic because it reused in-sample Kelly fractions (the book's "model the randomness correctly").

### 13. Calibration to market prices (Ch. 14, Ch. 19, Ch. 20)
- What it computes: model parameters (volatility, mean-reversion level) chosen so that model prices match liquid market
  prices, by least squares over the quoted instruments, or exactly by a tree (Black-Derman-Toy). The author stresses
  that calibration is the hard, non-trivial part, and that a model estimated from history alone does not reproduce
  arbitrage-free prices.
- How we could use it: an implied-volatility smile fitted per expiry to the chain, then used to reprice illiquid or
  stale strikes instead of their last print.
- Signal source: option chain as context. Maps to: NSE yes, MCX yes, crypto yes. Mechanical? yes.
- Our research: R103 repriced stale legs at each leg's own last IV and found the model 1-5 points high on legs that did
  print; a fitted smile for repricing: not tested by us.

### Ch. 15 - Equivalent martingale measures: applications
The moment-generating function of a normal variable, the conditional expectation of a geometric process, the choice of
drift (rate minus half the variance) that makes the discounted price a martingale, a full derivation of Black-Scholes
as an expectation, and the proof that the PDE and martingale routes give the same answer (the Girsanov drift change
replaces the expected return by the risk-free rate). Exercises: the chooser option and currency options.

### Ch. 16 - New results and tools for interest-sensitive securities
Most traded derivatives are American and rates are not constant; Libor futures, FRAs, caps and floors (a caplet is a
put on a bond or a call on the rate), swaps, bond options, swaptions. Complications: rates are not assets (their drift
needs an unobservable market price of risk), many maturities must stay mutually arbitrage-free, a bond's volatility
must shrink to zero at maturity, and futures differ from forwards when rates are random. Fixed income; no use to us.

### Ch. 17 - The arbitrage theorem in a new setting: normalisation and random rates
In a two-period, four-state model with a savings account, FRA, two bonds and a caplet, the money-market measure makes the
FRA rate a biased forecast and pricing needs a joint model of two rates; normalising by the zero bond that matures on
the payment date (the forward measure) removes the random discount and makes forward rates fair games. Lesson: the
numeraire is a tool, the state prices are what is unique. Fixed income.

### Ch. 18-21 - Term structure, classical and HJM approaches, bond PDEs, Feynman-Kac
Yield, discount and credit-spread curves; bond prices as risk-neutral expectations of discounting; forward rates from
bond prices. The classical approach fits a short-rate model to the discount curve (a geometric short rate is
unrealistic; mean-reverting square-root models fit better) and needs a market price of interest-rate risk, which is the
same excess-return-per-unit-of-volatility for every bond when one shock drives them (equal Sharpe ratios). HJM instead
fixes the forward-rate drift from volatilities alone, the true heir of Black-Scholes, and shows the short rate is not
Markov. Closed forms (Vasicek with a 5% long-run level and reversion speed 0.25; Cox-Ingersoll-Ross). Ch. 21: the
Feynman-Kac link between expectations and PDEs requires a Markov process; use whichever is faster numerically; the
generator and Kolmogorov's backward equation. Fixed income and theory; nothing to trade.

### Ch. 22 - Stopping times and American-type securities
An American option adds a decision: exercise when the immediate payoff beats the discounted value of continuing, using
only information known so far. Bermudan options can be exercised only on set dates. The binomial tree read sideways is
a Markov chain on price levels, and the exercise decision is found by dynamic programming.

### 14. Optimal stopping by dynamic programming (Ch. 22; Solution Manual Ch. 22)
- What it computes: the value at each state is the larger of "stop now" and "the expected value of continuing and then
  behaving optimally", computed backward from the end. In the book's 11-state example the payoff at state a5 is 8, a
  local peak, and the one-step-ahead expectation of continuing is only 3, so a naive look says stop; at another local
  peak the right answer is to continue because a higher payoff is reachable. Judging only the next step undervalues
  continuing. The stopping points are where the payoff touches its "envelope" (the smallest concave curve on top of
  the payoffs), which gives an exercise boundary.
- Inputs: a Markov model of the state (price level, time left), the payoff of stopping in each state.
- How we could use it: as the exact formulation of an exit question (when to book a target, when to give up), with
  costs included; it is how a tree prices early exercise.
- Signal source: underlying price/volume if the state is the index; option premium if the state is the premium (not
  our default; it would need labelling). Maps to: NSE, MCX, crypto alike.
- Mechanical? yes, given a calibrated state model (which is the hard part).
- Our research: R18 (FINDINGS.md "Better exits") found none of five early-exit overlays helps; R55 found settlement does
  not beat the 15:05 exit; R81 found 0 of 66 exit and management variants pass for Liquidity 15+5. A dynamic-programming
  exit: not tested by us.

### 15. The double-or-nothing paradox: maximising expected value can ruin you (Ch. 22 exercises; Solution Manual Ch. 22)
- What it says: in a game where the stake doubles or goes to zero on each coin toss (later "sweetened" so the expected
  reward keeps rising with every toss), maximising the expected reward says never stop, yet the game ends in zero with
  probability one. Expected value is the right criterion only if you can play many times; with one life a different
  criterion is needed.
- How we could use it: the argument against full-Kelly or bigger sizing on a positive average, and for growth-based
  (log) or drawdown-based sizing; also why Boss's account stop (-Rs 40k drawdown or -Rs 13k a month) is sound.
- Signal source: none (sizing). Maps to: all. Mechanical? n/a.
- Our research: H15 (HUNT_H15.md) found the Kelly fraction unstable: 1.29 on the chosen window but 0.48 and 0.14 on
  other windows, and preferred half-Kelly; quarter-Kelly cut P(drawdown of 50% or more) to 1-5% at half the growth.

## Performance claims
None. The book makes no claim about trading profits, win rates or returns. The only "performance" statements are
model properties: Black-Scholes prices are reasonably accurate even with random volatility or rates (cited to El Karoui
et al., no evidence shown in the book), a 4-step tree came within 0.19 of Black-Scholes (5.15 vs 5.34), and Monte Carlo
converges with enough paths if the randomness is modelled correctly.

## Risk, sizing and psychology
No psychology. Risk content: delta hedges fail on large moves and leak cash through gamma (Ch. 4, 12); jumps make normal
VaR wrong and need their own model (Ch. 8); stress scenarios may face illiquid markets where no-arbitrage breaks down
(Ch. 2); stochastic volatility adds an unhedgeable risk (Ch. 11); maximising expected value without regard to ruin
fails (Ch. 22 exercise). Sizing appears only indirectly through the double-or-nothing game; there is no Kelly, no
position-sizing rule and no drawdown measure in the book.

## How it relates to our research
- Agrees: options do not price direction and implied quantities are poor forecasts (Ch. 14) - IV1 (none pass), R16
  (direction AUC 0.509-0.536, max pain worse than "no change"), R89 (0 of 18 straddle versions positive), H29 (the
  index moved 0.62-0.73 of what IV priced).
- Agrees: rare events (jumps) dominate short-gamma risk (Ch. 8) - DEEP_OPTIONS_SELLING.md section 5 (kurtosis 14 and
  8.5; NIFTY -12.98% on 23 Mar 2020), R102 gaps (0.5%+ on 20% / 34% of days), R103's naked straddle worst days.
- Agrees: a one-lot seller cannot delta hedge (Ch. 4 and 12 need continuous hedging) - DEEP_OPTIONS_SELLING.md
  section 3, which is why R103 tested wings and stops.
- Agrees: the scaled-volatility move is a baseline - R16 (time-of-day shape adds 15-20%), R102 (VIX over-states NIFTY on
  81% of days).
- Agrees: look-ahead makes fake money (Ch. 9) - our look-ahead guard; R102's only "confirmed" rule was index staleness.
- Agrees: model prices beat stale prints (Ch. 2, 13) - R103's stale-wing repricing.
- Agrees: expected value is not the sizing criterion (Ch. 22) - H15's unstable Kelly and preference for fractional Kelly.
- Partly: exotic-option pricing of exits (locks as ladders, stops as knock-outs) explains why R19, R19b and R100 found
  locks do not add value, but we have not priced our exits that way.
- Not tested by us: a delta-hedged NIFTY or MCX volatility trade, a fitted jump-diffusion, a fitted IV smile for
  repricing, a dynamic-programming exit, cash-and-carry checks on MCX, mean-reverting spread trades.

## What is new to us
1. The normal-versus-rare split as a test: do return kurtosis and the share of variance from the largest moves stay
   high as the sampling interval shrinks from a day to a minute? If yes, jumps drive the risk and VIX-based stops and
   normal VaR understate it (Ch. 8).
2. Pricing our own exit rules as exotic options (stop = knock-out, step lock = ladder, trail = lookback-like) to put a
   rupee cost on each lock before testing it (Ch. 13).
3. Dynamic programming with an envelope boundary as the formal exit decision, instead of fixed targets (Ch. 22).
4. The cash-and-carry versus consumption-good distinction for MCX: gold and silver should sit near carry, crude and
   natural gas need not (Ch. 1, Manual Ch. 1).
5. The two Monte Carlo errors (time step and sample size) as a checklist for any simulation-based study (Manual Ch. 11).

Cards: `fm-neftci-derivatives.cards.yaml` (10 cards). Rules: none; the book has no backtestable trading rule
(`fm-neftci-derivatives.rules.yaml` is an empty list).

## Could not read
- Formulas: OCR garbles most displayed equations and many inline symbols (the Ito, Girsanov, HJM and Vasicek formulas
  in particular); the prose around them was read and is what this note relies on. No equation is reproduced here
  beyond short standard ones.
- Figures: payoff diagrams, the Black-Scholes surface, simulated Brownian, Poisson and mean-reverting paths, the
  stopping-time "envelope" figure and the yield-curve plots are images; only their captions and axis labels came
  through. The Solution Manual's figures (payoff diagrams, binomial densities, simulated paths, the PDE grid plot) were
  likewise read only through their labels and the numbers printed beside them.
- The Solution Manual's text layer drops the "fi"/"ff" ligatures and some Greek letters; the meaning was clear.
- The Bibliography and Index file was skimmed (references and index entries only, no content).
- The originally supplied OCR of the main text was unusable (rotated scans); everything above comes from our re-OCR.
