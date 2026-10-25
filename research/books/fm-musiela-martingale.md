# Martingale Methods in Financial Modelling, 2nd edition - Marek Musiela and Marek Rutkowski (2005; preface dated September 2004; Springer)

Source: martingale-methods-in-financial-modelling-musiela.txt, 327 scanned pages (two book pages per scan, about 636
book pages: prefaces, Chapters 1-14, Appendices A-B, references), read in full on 25 Oct 2026 (tesseract OCR of a scan;
nearly every displayed formula came out garbled, so the prose was read and formulas were reconstructed only where
standard; see "Could not read"). Book claims are the author's, not tested results. Signal source per method is in
IraAlgo terms (CLAUDE.md).

## What it is about
A graduate textbook on pricing and hedging derivatives by no-arbitrage (martingale measures), not by forecasting.
Part I builds it from one-period and binomial models to Black-Scholes, Bachelier and Black-76, then American and exotic options.
Its most useful chapter for us (Ch. 7, new in this edition) is on volatility risk: implied vs historical, smiles, local and stochastic volatility.
Part II (about half the book) is interest-rate modelling: short-rate, HJM, LIBOR and swap market models, cross-currency.
It has no trading rules and no market data; its value for us is pricing sanity, hedging logic and honest limits of models.

## Methods, rules and setups

Chapter by chapter. Pure theory gets one or two lines; each practical method gets a block. Standard material already
written up is pointed to, not repeated: Black-Scholes, Greeks, put-call parity, Black-76, binomial trees, barrier and
touch odds, Merton sizing and the delta-hedged straddle are in `fm-bjork-arbitrage.md`; the intuition of risk-neutral
pricing in `fm-baxter-financial-calculus.md`; practitioner volatility models and numerics in `fm-wilmott.md`; variance
swaps, skew and term-structure trading in `bennett-trading-volatility.md`. No chapter gives an entry/stop/exit trading
rule; the rules file holds the few backtestable ideas we derived from it.

### Ch. 1. An introduction to financial derivatives (book pp. 3-34)
Options, futures, forwards, market makers, margin, assignment; the standard combinations (bull and bear spreads,
butterfly, calendar, straddle "for a large move of unknown direction", strangles). A list of futures-vs-forward
differences (fewer than 2% of futures are delivered, over 90% of forwards are). The one-period example sets the tone:
stock 280 going to 320 or 260, rate 5%, strike 280. The call costs 21.59 (hold 2/3 share, borrow 165.08) and the put
8.25, whatever probability of a rise the investor believes: a bear (20% up) would "expect" 7.62, a bull (80% up)
30.48, and both are wrong prices. A futures option on the same numbers costs 12.70, call and put alike when the strike
equals the futures price. American calls on non-dividend stocks are never exercised early; American puts can be.

#### Method: model-free no-arbitrage bounds on a chain (Ch. 1.8)
- What it computes: inequalities any arbitrage-free chain must satisfy whatever the model: a call is worth at least
  spot minus the discounted strike and at most spot; a put at most the discounted strike; two calls one strike apart
  differ by no more than the discounted strike gap; option prices are convex in strike (every butterfly costs >= 0);
  longer expiries cost at least as much (American, and European calendar total variance must not fall).
- Inputs: same-minute prices of calls and puts across strikes and expiries, the futures price (as forward), the rate.
- How we could use it: a stale-print and bad-data filter for every option backtest and for the app's chain screen.
  A wing whose price breaks convexity or the call-spread bound was not traded at that minute.
- Signal source: volatility / options structure (data check, no signal). Maps to: NSE index options yes; MCX options
  yes; crypto options yes.
- Mechanical? yes.
- Our research: not run as a filter by us. R103's six design passers were stale wing prices (HUNT_R103_OPTIONS_SELLING.md,
  "The stale-leg artefact"); a convexity/bound check flags exactly that. Rules file: `static-arbitrage-chain-check`.

### Ch. 2. Discrete-time security markets (pp. 35-83)
The Cox-Ross-Rubinstein tree, backward induction, the martingale measure p* = (1 + r - d)/(u - d), and the limit to
Black-Scholes (another choice of up/down steps gives a jump limit instead). American puts by backward induction
(exercise when intrinsic beats continuation); American calls on a dividend payer only just before the ex-date. Then
theory: finite markets, the two fundamental theorems (no arbitrage iff a martingale measure; complete iff unique),
state prices, change of numeraire, futures markets (the futures price itself is a martingale), futures equal forwards
when rates are deterministic. Tree pricing is written up in `fm-bjork-arbitrage.md`; NSE index options are European,
so the American machinery matters only for stock options.

### Ch. 3. Benchmark models in continuous time (pp. 84-146)
Black-Scholes by replication and by expectation, time-dependent rates and volatility (use the average variance over the
option's life), Merton's stochastic-rate formula, put-call parity, Feynman-Kac. Two cautions: a "suicide" doubling
strategy shows that unconstrained continuous trading allows arbitrage, so strategies must have wealth bounded below;
and the textbook "riskless portfolio" derivation is not strictly self-financing (the price comes out right anyway).
The authors call the lognormal model unrealistic for stocks but note its option prices are "reasonably close" to
exchange prices.

#### Method: option elasticity (leverage) and position delta (Ch. 3.1.11)
- What it computes: elasticity = delta x spot / option price, the percentage move of the option per 1% of the
  underlying. Worked example: spot 31, strike 30, 3 months, volatility 10%, rate 5%: call 1.52, delta 0.82, so the
  call moves nearly 17 times the stock's percentage (a 0.65% rise in the stock lifts it about 11%); the put's elasticity
  is several times larger. Position delta = lots x lot size x delta (5 lots of 100 at 0.82 = 410 shares).
- Also in the chapter: covered calls cut variance mostly on the right tail, "where high variance is desirable";
  protective puts cut the left tail and shift the whole distribution left by their cost; mean-variance analysis is the
  wrong yardstick for option portfolios (skewed, non-constant volatility). Gamma and theta are dominated by
  near-the-money, short-dated options; vega by near-the-money, longer-dated ones.
- Inputs: Black-Scholes delta and price (app already has both).
- How we could use it: Jarvis can state the leverage of a chosen strike in plain words ("this option moves about 17%
  for a 1% index move"), and why an OTM option's percentage risk is so large.
- Signal source: none (risk description). Maps to: NSE yes; MCX yes; crypto options yes.
- Mechanical? yes.
- Our research: consistent with R102/DEEP_OPTIONS_SELLING.md: one ATM option moves a median 3.3% a minute in the first
  15 minutes and 13.5% a minute in expiry's last hour.

#### Method: index options with a dividend yield (Ch. 3.2)
- What it computes: Black-Scholes with a continuous dividend yield (Merton); the authors say practitioners use it for
  stock index options; with known cash dividends, use spot minus their present value. The ex-dividend drop is usually a
  bit less than the dividend (taxes).
- How we could use it: for NIFTY/BANKNIFTY, read the forward off the future (or a synthetic from parity) instead of
  guessing a yield; `fm-bjork-arbitrage.md` already proposes the synthetic forward input.
- Signal source: volatility / options structure. Maps to: NSE yes. Mechanical? yes. Our research: not tested by us.

#### Method: Bachelier (normal-volatility) pricing (Ch. 3.3; Ch. 7 intro)
- What it computes: option prices when the price moves by a fixed number of points per root-time (arithmetic Brownian
  motion) rather than a fixed percentage. The authors say it can be the better tool for some markets, "especially
  short-lived" options. With zero rates an ATM straddle is then worth about 0.8 x (point volatility) x root-time; the
  Greeks have the same signs as Black-Scholes. Choosing absolute (normal) or relative (lognormal) volatility is
  "almost equivalent to" choosing the model.
- Inputs: points volatility (e.g. NIFTY points per root-day), time, strike, forward.
- How we could use it: quote expiry-day and intraday straddles in index points per root-minute, which stays sane when
  the premium is a few points; it also prices contracts that can go to or below zero (crude, April 2020).
- Signal source: volatility / options structure. Maps to: NSE yes (weekly/expiry-day); MCX crude yes; crypto yes.
- Mechanical? yes.
- Our research: R102 measured the 09:20 ATM straddle as a median 0.96% of spot (NIFTY, n 1,222 days) and 1.49%
  (BANKNIFTY, n 1,190): that is a normal-style "expected move" quote. Not modelled by us as Bachelier.

#### Method: Black-76 for options on futures (Ch. 3.4)
Same as `fm-bjork-arbitrage.md`: price on the futures price with no carry; the option on the future equals the option on
spot when rates are deterministic. Example: futures call 1.22, put 0.23. Maps to MCX options on futures. The authors
add that measured index-futures mispricing does occur, but trading lags and costs make the arbitrage small and risky,
and with costs the no-arbitrage futures price is a band, not a point (Ch. 3.4.5). Our h38 tested futures basis as a
direction signal for option buying and rejected it (HUNT_H38.md).

#### Method: robustness to wrong volatility; super-replication (Ch. 3.5)
- What it computes: a seller who charges the Black-Scholes price at a volatility at least as high as the true
  (unknown) volatility at every moment, and delta-hedges with that volatility, ends with a non-negative result for any
  convex payoff (calls, puts, straddles). The hedge P&L accumulates half of gamma x spot squared x (hedging variance
  minus realised variance) over time. So the seller's price is at most the Black-Scholes price at the top volatility,
  the buyer's at least the price at the bottom volatility. For non-convex payoffs (spreads, flies, barriers) the
  worst-case price comes from the Avellaneda-Levy-Paras "uncertain volatility" equation: use the high volatility where
  gamma is positive and the low one where gamma is negative.
- The authors themselves doubt its practical appeal: no robust estimator of instantaneous volatility exists, and a
  competitive quote cannot be set at the maximum volatility.
- Inputs: a volatility band (e.g. recent realised low/high), the position's gamma profile.
- How we could use it: a worst-case value for any multi-leg position in the app (price it at the band ends, choosing by
  the sign of gamma); and the formula explains why a hedged short straddle earns implied minus realised.
- Signal source: volatility / options structure. Maps to: NSE yes (but hedging needs several lots); MCX yes; crypto yes.
- Mechanical? yes (pricing tool).
- Our research: MCX_OPTIONS.md found realised moves 0.73-0.93 of what MCX options priced (60-minute window) and
  delta-hedged long-option P&L about zero at best, i.e. the seller side of this formula. R102: VIX above NIFTY's next
  21 days' realised on 81% of days (n 4,507). The hedged straddle itself is a rule in `fm-bjork-arbitrage.md`.

#### Method: numerical methods survey (Ch. 3.1.13)
Moment-matched binomial and trinomial trees, implied trees, Monte Carlo with control variates, finite differences
(explicit, implicit, Crank-Nicolson). A survey with references only; see `fm-wilmott.md` and `fm-brandimarte-numerical.md`.

### Ch. 4. Foreign market derivatives (pp. 147-170)
Domestic and foreign martingale measures; interest-rate parity for forward FX; currency options are Black-Scholes with
the foreign rate as a dividend yield (Garman-Kohlhagen), but the authors report that this lognormal model gave biased
prices empirically and jump models fit better. Options on foreign stocks struck in foreign or domestic currency,
quanto forwards and options (the payoff converted at a fixed rate; the price shifts by the stock-FX covariance, which
acts like a dividend yield), equity-linked FX options. Two real examples: dollar gold on COMEX against yen gold in
Tokyo, and the dollar Nikkei future on CME against the yen one in Osaka.

#### Method: cross-currency (quanto) fair value of one commodity in two currencies (Ch. 4.3-4.4)
- What it computes: the price of the same underlying in another currency must equal the foreign price times the
  forward exchange rate (plus carry and, for MCX gold, import duty); for a quanto contract, an extra covariance term.
- Inputs: MCX GOLD future, COMEX gold future, USDINR forward, duty and carry.
- How we could use it: a fair-value gap for MCX gold/silver, as context or as a mean-reversion spread.
- Signal source: underlying price (both futures and FX); maps to: MCX GOLD/SILVER yes; NSE no; crypto partly
  (INR-settled crypto contracts vs USD prices).
- Mechanical? yes once duty and carry are fixed.
- Our research: R82 used COMEX moves as a lead for MCX and rejected it (O1, the overnight COMEX move: design +Rs 85k on
  398 trades, failed the final period; HUNT_R82_MCX_PRECIOUS.md). A fair-value gap was not tested by us. Rules file:
  `mcx-gold-comex-fair-value-gap` (data: lack).

### Ch. 5. American options (pp. 171-192)
Arbitrage price of an American claim = largest expected discounted payoff over all exercise times under the martingale
measure (the Snell envelope); the seller hedges with a strategy that may "consume" money. American put: a critical
price below which to exercise, the early-exercise premium, the free-boundary problem with smooth fit, the perpetual put
in closed form. Approximations: the binomial tree is "far from being the most efficient", Geske-Johnson with three
exercise dates is about as accurate as a 150-step tree, plus Richardson extrapolation, Barone-Adesi-Whaley and the
method of lines. Unprotected American calls on dividend stocks are exercised just before the ex-date (Roll's formula).
Not needed for European NSE index options; relevant only to NSE stock options and any American crypto contract.

### Ch. 6. Exotic options (pp. 193-216)
Packages (collar, break forward, zero-cost range forward), forward-start options, choosers (= a call plus a put),
compound options, digitals, barriers (vanilla minus a "knock-out discount", by the reflection principle), lookbacks,
Asian options, basket options, quantile options, multi-asset (max/min, exchange), Russian and passport options. Barrier
and lookback mathematics, and their use as touch odds and as the value of a perfect trail, are in
`fm-bjork-arbitrage.md`.

#### Method: averaging (Asian) payoffs and baskets (Ch. 6.8-6.9)
- What it computes: options on an average price. The authors note averaging makes them robust to manipulation near
  expiry and cheaper, which suits thinly traded assets and commodities. The arithmetic average is not lognormal: the
  quick geometric-average formula "significantly underprices" the call; better are moment-matched lognormal
  approximations or Monte Carlo with the geometric option as control variate (Kemna-Vorst). Basket options cost less
  than a set of single options because they net correlation.
- How we could use it: two ideas only. (1) A settlement price set by averaging (as on some exchanges) changes expiry
  behaviour; check how each contract we trade settles. (2) The control-variate trick for any Monte Carlo the app runs.
- Signal source: volatility / options structure. Maps to: MCX (average-settled contracts if any) partly; NSE no.
- Mechanical? yes. Our research: not tested by us.

### Ch. 7. Volatility risk (pp. 217-278) - the most practical chapter

#### 7.1.1-7.1.3 Historical vs implied volatility
- Black and Scholes' own 1972 test (OTC options 1966-1969, one year of daily volatility): the model overpriced options
  on high-historical-volatility stocks and underpriced low-volatility ones. Longer samples do not help because variance
  is not stationary; an unbiased volatility estimate does not give an unbiased option price; "historical precedent is a
  poor guide".
- Forecasting: early studies found implied beats historical; later stock-index-option studies found implied had
  "virtually no correlation" with future volatility, blamed on high transaction costs; a 2003 study of futures options
  (low frictions) found implied beats historical again. Jarrow and O'Hara: the gap between implied and historical
  volatility should reflect the cost of dynamic hedging.

#### Method: implied volatility from price, with quick approximations (Ch. 7.1.4)
- What it computes: the unique volatility matching a market price (exists whenever the price is above the model-free
  lower bound), solved by Newton-Raphson. Quick closed forms: at the money forward, Brenner-Subrahmanyam: volatility x
  root-T is about root-(2 pi) x call / spot (about 2.5 x call / spot); Corrado-Miller extends it near the money.
- How we could use it: a fast, dependency-free IV for Jarvis and for checks; and the inverse: an ATM straddle costs
  about 0.8 x spot x volatility x root-T.
- Signal source: volatility / options structure. Maps to: NSE yes; MCX yes; crypto yes. Mechanical? yes.
- Our research: R102 uses Dhan's IV (NIFTY ATM IV median 16.1, n 1,222 days); not a study topic.

#### Method: the implied volatility surface and its static limits (Ch. 7.1.5-7.1.6)
- What it computes: skew (low strikes dearer), smile, term structure. If the smile is flat, a time-dependent volatility
  fits all expiries, with forward variance between two expiries = (variance x time) of the later minus that of the
  earlier, which must be non-negative. Lee's moment formula: in the wings, implied variance can grow at most linearly
  in log-moneyness; do not extrapolate a smile with tails steeper than that; the tail slopes reveal how fat the
  underlying's tails are and pin jump-model parameters.
- How we could use it: (1) extract the implied variance of a single day (e.g. expiry day) as forward variance between
  two expiries and compare it with what such days realise; (2) sanity-check wing IVs before using them (stale wings).
- Signal source: option chain as context (or volatility / options structure). Maps to: NSE yes; MCX yes; crypto yes.
- Mechanical? yes.
- Our research: R102 (DEEP_NIFTY_BANKNIFTY.md 3.4): put ATM-5 IV minus call ATM+5 IV median +2.56 points, puts dearer on
  91% of NIFTY days (n 1,222); nearest-expiry ATM IV above VIX by 0.3 points on normal days and 7.7 on expiry day; the
  09:20 straddle is beaten by the close on 7% of NIFTY non-expiry days but 41% of expiry days. Day-variance extraction
  not done by us.

#### 7.1.7-7.1.8 Marking to market, vega, volga, vanna, modified delta
Liquid vanillas are treated like the underlying: given by the market; models are calibrated to them and used only for
what is not liquid. Vega is positive for calls and puts; volga and vanna measure how vega changes. Practice: neutralise
vega, vanna and volga of an exotic with a static set of vanillas and take the delta from a constant-volatility model.
The authors say Black-Scholes survives because it ties each underlying move to an action (the Greeks) and gives traders
a common quoting language (implied volatility).

#### Method: smile-adjusted ("modified") delta (Ch. 7.1.8, 7.2.1)
- What it computes: delta = Black-Scholes delta + vega x (change of the option's implied volatility per unit change of
  the underlying). Because implied volatility moves with the index (put skew), the plain delta mis-hedges.
- Inputs: the chain's IV by strike over time (to estimate how IV moves with spot), BS delta and vega.
- How we could use it: for any delta-hedged structure, and for Jarvis's "how much does this option gain if NIFTY rises
  100 points" answer, which is wrong with a frozen IV on a skewed chain.
- Signal source: option chain as context / volatility structure. Maps to: NSE yes; MCX yes; crypto yes.
- Mechanical? partly (the IV-vs-spot slope must be estimated, sticky-strike vs sticky-moneyness is a choice).
- Our research: not tested by us. Rules file: `smile-adjusted-delta-hedge` (a hedge-error comparison).

#### 7.1.10 Forward-start options
With deterministic volatility the forward volatility follows from the term structure; once there is a smile the
surface no longer determines it: such contracts need a model of how volatility moves.

#### Method: CEV and displaced diffusion (Ch. 7.2)
Volatility that rises as the price falls (constant elasticity of variance) produces a downward skew; its delta adds a
vega x skew term. Hagan and Woodward show these models predict the wrong future smile. A shifted-lognormal model fits a
skew cheaply but has the same dynamics problem. Context only.

#### Method: implied (risk-neutral) distribution from option prices (Ch. 7.3.1, Breeden-Litzenberger)
- What it computes: the market's risk-neutral density at an expiry = growth factor x the second strike-derivative of
  call prices, i.e. the price of a tight butterfly per unit width. Only the single-date distribution is pinned down;
  vanilla prices say nothing unique about paths.
- How we could use it: Jarvis can quote "the chain prices a X% chance NIFTY ends beyond Y" and compare it to how often
  that happened; it is also the honest frame for R103-style selling: selling a fly or condor is a bet against this
  density, and the density already carries a premium.
- Signal source: option chain as context. Maps to: NSE yes (strikes ATM+-10 in our lake limit the tails); MCX partly;
  crypto yes.
- Mechanical? yes (needs smoothing across strikes).
- Our research: defined-risk selling against the chain failed (R103: 0 of 24 confirmed); implied-vs-realised
  frequencies measured in R102 (above). Density extraction not done by us.

#### Method: local volatility (Dupire), implied trees, mixtures (Ch. 7.3)
Dupire's formula recovers a volatility function of price and time that reproduces every vanilla price; local variance
equals the expected instantaneous variance given the price ends at that strike. Lognormal mixtures (Brigo-Mercurio) fit
smiles easily but have no sound dynamics. Drawbacks per the authors: needs a continuum of strikes (heavy numerics); it
pretends vanillas determine exotics; Dumas-Fleming-Whaley found implied-volatility-function hedging "rather poor".
Context only; see `fm-wilmott.md`.

#### Method: stochastic volatility (Ch. 7.4): Hull-White mixing, Heston, SABR
- What it computes: models where volatility has its own random driver; the market is incomplete, so a "market price of
  volatility risk" must be chosen. Hull-White mixing: if volatility is independent of the price, the option price is
  the average Black-Scholes price over the distribution of integrated variance. Renault-Touzi: independent volatility
  gives a symmetric smile with its bottom at the forward; a skew needs negative price-volatility correlation (the
  "leverage" effect: returns and volatility changes are inversely correlated, Black 1976, Christie 1982). Heston has a
  Fourier closed form; SABR fits one expiry's smile well and, per Hagan et al. (2002), moves the smile the right way
  when spot moves, while any local-volatility model moves it the wrong way, so its hedges "may be worse than" plain
  Black-Scholes.
- How we could use it: (1) the mixing formula prices a straddle under volatility uncertainty (average BS over a
  realised-variance forecast, e.g. R16's range model); (2) the skew sign tells Jarvis that NSE options price falls with
  rising volatility; (3) for hedging, prefer plain BS or SABR-type smile dynamics over local volatility.
- Signal source: volatility / options structure. Maps to: NSE yes; MCX yes; crypto yes. Mechanical? partly
  (calibration choices).
- Our research: R102: puts dearer on 91% of NIFTY days and 90% of BANKNIFTY days (consistent with negative correlation).
  R36: the GEX afternoon effect was really an implied-volatility effect (GEX vs IV correlation -0.69); model fitting
  not done by us.

#### 7.5 Dynamic volatility surfaces
Neuberger: delta-hedging a log contract accumulates realised minus hedged variance (the variance-swap idea, see
`bennett-trading-volatility.md`); Dupire's forward variances; Derman-Kani stochastic implied trees; Schonbucher's
stochastic implied volatility with its no-arbitrage drift; Brace et al.'s "feedback condition": the shortest-dated ATM
implied volatility becomes the instantaneous volatility. Theory.

#### Method: GARCH-type models, jumps, Levy processes, business time (Ch. 7.6.1)
- What it says: volatility clusters, returns are fat-tailed and negatively correlated with volatility. GARCH and the
  asymmetric NGARCH (Engle-Ng, Duan) price options by simulation or a lattice; diffusion volatility models are limits
  of GARCH; studies found GARCH and stochastic-volatility option values close, and "no notable differences" between
  them for risk management. Jump-diffusion (with or without stochastic volatility) fits the observed smile better than
  continuous models, "especially for shorter expirations"; simultaneous price and volatility jumps fit with sensible
  parameters. Pure-jump Levy models (NIG, hyperbolic, CGMY) fit SPX options well; stable laws have infinite variance.
  Random time change: in "business time" (the count of trades) returns behave more normally; volatility follows
  activity.
- How we could use it: a volatility forecast from recent volatility (GARCH-style) as the size input to option pricing
  and sizing; a jump term when pricing very short expiries (weekly, expiry day); a trade-count clock as a feature.
- Signal source: underlying price/volume (forecasts of size, not direction). Maps to: NSE yes; MCX yes; crypto yes.
- Mechanical? yes.
- Our research: R88 (HUNT_R88_WHAT_MOVES_THE_CANDLE.md): a big last candle predicts a 1.5x bigger next candle on the
  indices (10 of 10 index cells held), a volatile last hour 1.4x, an option-volume surge 1.2-1.4x; R16: the next 30-60
  minutes' range is predictable, direction is not (AUC 0.509-0.536). GARCH itself: see `fm-javaheri-vol-arb.md` and
  `fm-fin-econometrics-derivs.md`.

#### 7.6.2 Volatility as an underlying
VIX (1993) is an implied-volatility index; futures and options on it; variance swaps on summed squared daily log
returns and volatility swaps. See `bennett-trading-volatility.md`. India VIX is our version (R102).

### Ch. 8. Continuous-time security markets (pp. 279-313)
Theory: admissible ("tame") strategies whose wealth is bounded below, which excludes doubling; the arbitrage price as
the cheapest super-replication and the largest expectation over martingale measures; no free lunch with vanishing risk
(Delbaen-Schachermayer); change of numeraire ("virtually all European options can be priced" this way); completeness
of the multi-asset Black-Scholes model (at least as many traded assets as noise sources, full-rank volatility). Then
incomplete-market hedging and imperfections.

#### Method: hedging an index option with only some of its stocks (Ch. 8.2, risk-minimising hedge)
- What it computes: when an option on an index can be hedged only with a subset of its constituents (or another index),
  the market is incomplete; Follmer-Schweizer local risk minimisation gives the hedge that minimises the variance of
  the unhedged cost, with the "minimal martingale measure" (traded parts drift at the riskless rate, untraded parts
  keep their real drift).
- How we could use it: cross-hedging (FINNIFTY or SENSEX option risk with NIFTY futures; a BANKNIFTY position with
  heavyweight stock futures): the hedge ratio is a regression-type beta and some risk always remains.
- Signal source: underlying price (hedge ratios). Maps to: NSE yes; MCX (gold vs silver) partly; crypto (ETH vs BTC)
  partly.
- Mechanical? yes.
- Our research: R72 found the five indices move together closely enough that Breadth Donchian's trades are 77% the
  same as Donchian's (daily correlation 0.80, HUNT_R72_CROSS_INDEX.md); cross-hedging itself not tested by us.

#### Method: transaction costs and super-replication (Ch. 8.2.4)
- What it says: with proportional transaction costs, no non-trivial super-replicating strategy for a call exists: the
  cheapest perfect hedge is to buy the stock (Soner-Shreve-Cvitanic). Different borrowing and lending rates make
  pricing nonlinear (backward SDEs). Leland and Whalley-Wilmott (Ch. 3.1.12) cover approximate hedging with costs.
- How we could use it: a reason not to chase "perfect" hedges at one lot; hedge bands and few rebalances.
- Signal source: none (cost theory). Maps to: all. Mechanical? n/a.
- Our research: costs dominate our results (about Rs 70 a NIFTY round trip; h24's real half-spreads 0.16% BANKNIFTY to
  0.42% FINNIFTY); DEEP_OPTIONS_SELLING.md explains a one-lot seller cannot delta-hedge (futures lot = option lot).

### Ch. 9. Interest rates and related contracts (pp. 315-346)
Zero-coupon bonds, yields, forward and short rates, coupon bonds, US Treasury conventions (bill discount rates,
cheapest-to-deliver bond futures, Eurodollar futures where 1 basis point = $25), swaps, FRAs with bid-offer forward
rates. The expectations hypotheses: the forward rate is an unbiased forecast of the future short rate only under the
forward measure, not under real-world probabilities; the gap is a risk premium. Forward measures and the
"option to exchange two bond portfolios" formula behind caps and swaptions. Low relevance; the one transferable point is
that implied numbers (forward rates, implied volatility) are prices with a premium, not forecasts.

### Ch. 10. Short-term rate models (pp. 347-380)
Merton, Vasicek (mean-reverting, Gaussian, affine bond prices), Dothan, Brennan-Schwartz, Cox-Ingersoll-Ross (square
root, stays positive), Longstaff, Hull-White (fits today's curve), Black-Derman-Toy and Black-Karasinski (lognormal),
multi-factor and affine models, consols, the extended CIR via squared Bessel processes. Empirical notes: a single
factor cannot explain bond prices; Longstaff's model beat CIR.

#### Method: mean-reverting (Vasicek / Ornstein-Uhlenbeck) process (Ch. 10.1)
- What it computes: a variable pulled toward a long-run mean at speed b, with a stationary normal distribution (mean a/b,
  variance sigma squared over 2b) and half-life ln 2 / b; CIR adds a square-root volatility so it stays positive (the
  same process drives variance in Heston).
- How we could use it: model a spread (cross-index, MCX vs COMEX gap) or implied volatility itself as mean-reverting;
  half-life sets holding time. Already written up as a method in `fm-bjork-arbitrage.md` (Ch. 22).
- Signal source: underlying price (spread) or volatility. Maps to: NSE (cross-index spreads) yes; MCX yes; crypto yes.
- Mechanical? yes. Our research: R72 cross-index (above); a pairs/OU rule not tested by us.

### Ch. 11. Models of instantaneous forward rates (pp. 381-430)
Ho-Lee and Heath-Jarrow-Morton: forward-rate drift is fixed by its volatility; with deterministic volatility the risk
premium drops out of all derivative prices. Constant forward volatility = parallel shifts only; damped volatility =
Hull-White. Bond and stock options under random rates (only the volatility of the forward price matters); coupon-bond
options; callable and convertible bonds; replication with forward contracts; futures minus forward = covariance of the
asset with the savings account. Empirical: German data favoured a two-factor Vasicek (it can twist the curve);
short-rate models often outperformed forward-rate models (Moraleda-Pelsser). Theory for us.

### Ch. 12. Market LIBOR models (pp. 431-474)
Forward vs futures LIBOR (convexity), caps and floors as strips of puts on zero-coupon bonds, cap-floor parity = swap,
exotic caps, Black's caplet formula (market convention) and the lognormal LIBOR (BGM) model that justifies it, built by
forward or backward induction; hedging caplets with forward swaps; CEV and stochastic-volatility LIBOR extensions
because caplet implied volatility falls with strike. Not relevant to NSE/MCX/crypto trading.

### Ch. 13. Alternative market models (pp. 475-526)
Swaps priced model-free as a coupon bond against a floater; swaptions as options on swap rates (Black's swaption formula
justified by Jamshidian's swap-market model); co-terminal (Bermudan swaptions), co-initial (CMS spreads, mid-curve
options, needs Monte Carlo) and co-sliding swap-rate models; lognormal LIBOR and swap models cannot both hold;
calibration (volatilities from swaptions, correlation from history); Markov-functional models calibrated to digital
prices (an implied-distribution idea); Flesaker-Hughston rational models. Not relevant for us.

### Ch. 14. Cross-currency derivatives (pp. 527-562)
Multi-currency HJM: domestic and foreign pricing agree; currency options under random rates use the forward FX
volatility; options on foreign assets, quanto options, equity-linked FX; cross-currency (differential) swaps as quanto
roll bonds; cross-currency swaptions; basket caps; quanto caplets in a LIBOR model. Credit risk and inflation are said to
be out of scope. Relevant only through the quanto idea in Ch. 4.

### Appendices A-B
Conditional expectation and the abstract Bayes formula; the Ito integral, Ito's formula, Levy's characterisation,
predictable representation, Girsanov; local time and the Ito-Tanaka-Meyer formula; the joint law of a drifting Brownian
motion and its running maximum/minimum (the source of barrier and touch probabilities; see `fm-bjork-arbitrage.md`).

## Performance claims
None about trading profits. The book's claims are theorems under stated assumptions (frictionless, continuous
trading, unlimited borrowing at one rate) plus cited empirical findings: Black-Scholes 1972 (historical-volatility
pricing biased), stock-index implied volatility a poor forecaster (costs), futures-option implied volatility a better
one, lognormal FX option prices biased, local-volatility hedging poor (Dumas-Fleming-Whaley), jump models fit short
expiries better, GARCH vs stochastic volatility close for pricing and risk management, CGMY fits SPX well, Longstaff
beats CIR, two-factor Vasicek fits German bonds best. No sample sizes are given in the text for these; they are
citations.

## Risk, sizing and psychology
- Risk: a seller removes risk by hedging, not forecasting; volatility mis-specification is the main residual risk, and
  for convex payoffs over-estimating volatility is the safe side (Ch. 3.5). Non-convex books (spreads, flies) need the
  worst-case volatility chosen by the sign of gamma. Incomplete markets leave unhedgeable risk (Ch. 7.4, 8.2).
- Sizing: the doubling ("suicide") strategy shows unlimited leverage turns near-certain ruin into apparent arbitrage;
  realistic strategies need a known maximum leverage (wealth bounded below). No Kelly or position-size rule is given.
- Psychology: none.

## How it relates to our research
- Agrees: prices do not depend on anyone's view of direction (the 21.59 call under both the 7.62 bear and the 30.48 bull
  view); our direction tests are coin flips (R16 AUC 0.509-0.536; R41 AUC 0.510-0.517; R88 0 of 8 direction findings
  held) while size is predictable (R16, R88).
- Agrees: implied volatility exceeds realised by roughly the cost of hedging (Jarrow-O'Hara); R102: VIX over-states
  NIFTY's next 21 days on 81% of days (median ratio 1.28, n 4,507) but under-states BANKNIFTY's on 60%; MCX options
  priced 0.73-0.93 more movement than came (MCX_OPTIONS.md).
- Agrees: volatility clusters and follows activity (Ch. 7.6); R88: big candles follow big candles (1.5x), volatile hours
  follow volatile hours (1.4x).
- Agrees: a skew needs negative price-volatility correlation; R102: NIFTY puts dearer than calls on 91% of days.
- Agrees: short expiries carry jump risk the smooth models miss; R102: the 09:20 straddle is beaten by the close on 41%
  of NIFTY expiry days against 7% of other days.
- Already rejected: defined-risk option selling (R103: 0 of 24); GEX as a signal (R36: 0 of 4, an IV effect); COMEX
  lead for MCX (R82); futures basis (h38); option-market signals (IV1: no signal positive in every period).
- Not tested: model-free bound checks as a data filter, smile-adjusted delta, implied densities, day-variance extraction,
  the MCX-COMEX fair-value gap, cross-hedging. See the rules file.

## What is new to us
1. Chain-wide no-arbitrage checks (call-spread bound, butterfly convexity, calendar total variance) as a stale-print
   filter: a stronger version of the put-call parity check proposed from Bjork.
2. Smile-adjusted delta (BS delta + vega x IV-vs-spot slope) for any hedge and for Jarvis's "what if NIFTY moves" answer.
3. The risk-neutral density from butterflies (Breeden-Litzenberger): an honest "the chain prices X% odds" statement.
4. Forward variance between expiries: the implied variance of one specific day (expiry day) to compare with realised.
5. The uncertain-volatility worst-case price of a multi-leg position (high volatility where gamma is positive, low where
   negative) as a risk number in the app.
6. Normal (Bachelier) volatility in points per root-time for very short expiries.
7. Quanto/cross-currency fair value for MCX gold and silver against COMEX and USDINR.

## Could not read
Almost every displayed equation is OCR-garbled (Black-Scholes, Bachelier and Black formulas, Greeks, the CRR formula,
Dupire's formula, Heston/SABR expressions, all of Part II's bond, cap and swaption formulas); they were reconstructed
only where standard and none is copied here. The book has almost no figures. One scan page of Ch. 14 (book pp. 527-529)
appears twice in the OCR. Pages 300-326 are the reference list (skimmed) and page 327 the back cover; there is no index
in the scan.

## R104 results (25 Oct 2026)

Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in `research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:

- `book-fmmusiela-01`: not testable (R104): needs synchronous bid/ask for all legs
- `book-fmmusiela-02`: tested (R104): VOL: 879 trades, win 59%, net Rs 116,775 design, holdout -Rs 79,347 (fail)
- `book-fmmusiela-03`: tested (R104): VOL: 307 trades, win 66%, net Rs 110,474 design, holdout Rs 4,088 (fail)
- `book-fmmusiela-04`: not testable (R104): no intraday USDINR or dated import-duty history
