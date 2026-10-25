# Financial Calculus: An Introduction to Derivative Pricing - Martin Baxter and Andrew Rennie (1996; reprinted with corrections 1997, ninth printing 2003; Cambridge University Press)

Source: financial-calculus-an-introduction-to-derivative-pricing-bax.txt, 242 pages, read in full on 25 Oct 2026 (tesseract
OCR of a scanned book: preface, the bookmaker parable, Chapters 1-6, Appendices 1-4 (reading list, notation, answers
to exercises, glossary) and the index). Almost every displayed formula came through garbled and every figure is an image
(see "Could not read"); the prose around them was read.
Book claims are the authors', not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A short, famously readable textbook on why a derivative's price is fixed by hedging (arbitrage), not by forecasting.
It builds the idea on one-step and many-step binomial trees, then repeats it in continuous time with Brownian motion.
Its three tools are Ito's formula, the change-of-measure (Cameron-Martin-Girsanov) theorem and martingale representation.
It applies them to Black-Scholes, currencies, dividend-paying equities, bonds, quantos and a long interest-rate chapter (HJM, short-rate models, swaps, caps).
It has no trading rules, no market data and no backtests: its value for us is pricing, hedging and one honest message about edges.

Much of this is the same standard material as Björk (`fm-bjork-arbitrage.md`) and the Wilmott books (`fm-wilmott.md`,
`fm-wilmott-howison-dewynne.md`); binomial trees are covered at length in `fm-binomial-models.md`, and trading volatility in
`bennett-trading-volatility.md`. This write-up points to those for the standard blocks and spends its words on what
Baxter and Rennie add or say more clearly.

## Methods, rules and setups

A chapter-by-chapter account. Pure theory gets one or two lines; each practical method gets a block. No chapter gives
an entry/stop/exit trading rule, so the rules file is empty (`[]`).

### Preface and "The parable of the bookmaker"
The authors complain that market practice runs on a "soft" understanding and that academics ignore hedging, which is
both the justification of a price and an end in itself. The parable: a bookmaker on a two-horse race knows the true
chances are 25% and 75%, but punters have staked $5,000 and $10,000. If he sets odds from the true chances (3-1 against,
3-1 on) his expected profit is zero but he loses $5,000 if the outsider wins. If he sets odds from the money wagered (2-1
against, 2-1 on) he breaks even whichever horse wins. With a 107% book (an overround) the "true odds" book expects
+$1,000 but swings between -$3,000 and +$2,333; the "money-wagered" book makes +$1,000 for certain.

#### Method 1: price from the hedge (the money on each side), not from your own probabilities
- What it computes: a quote that leaves the book indifferent to the outcome because the quote is set by what can be
  laid off, not by a forecast.
- Inputs: the amounts or hedge costs on each side; no forecast needed.
- How we could use it: the cleanest way to explain to Boss why an option seller who prices from "my view of where NIFTY
  will close" is gambling, while a market-maker who prices from the hedge is not. It also explains why implied prices
  carry the crowd's demand (the money wagered), not the true odds.
- Signal source: volatility / options structure (a pricing principle, no signal). Maps to NSE index options, MCX
  options and crypto options alike.
- Mechanical? yes (as a principle).
- Our research: R102 measured exactly this gap between the crowd's price and the outcome: India VIX was above the next
  21 days' realised NIFTY volatility on 81% of days (median ratio 1.28, n 4,507), but below BANKNIFTY's realised on 60%
  of days (`DEEP_NIFTY_BANKNIFTY.md`, 2.2). Collecting that gap by selling did not survive our gates: R103 confirmed 0 of
  24 defined-risk selling tests (`HUNT_R103_OPTIONS_SELLING.md`, Verdict).

### Ch. 1. Introduction: expectation pricing versus arbitrage pricing
A coin game paying $1 on heads is "worth" 50 cents by the law of large numbers, but only over many plays; offered once
for a huge stake, nobody rational mortgages the house to exploit a mispricing. A forward is different: the seller can
borrow, buy the stock and wait, so the forward price is forced to spot grown at the interest rate, whatever the stock's
expected return. The authors' line: if an arbitrage price exists, any other price is too dangerous to quote. From 1973
(Black-Scholes) the same turned out to hold for options. The rest of the book never uses "expected value" pricing again.

#### Method 2: cost-of-carry forward
- What it computes: the fair forward/futures price = spot grown at the funding rate (minus dividends or the foreign
  rate, Ch. 4). Proof by a static "borrow, buy, hold" hedge.
- Inputs: spot, rate, time, dividend yield.
- How we could use it: the fair value of NIFTY/BANKNIFTY futures and the synthetic forward (strike + call - put) that
  absorbs the index dividend yield; a sanity check on any option chain.
- Signal source: underlying price (fair value; not a direction signal). Maps to NSE index futures and options yes, MCX
  futures yes (storage costs replace dividends; the book does not cover them), crypto futures/perpetuals yes (funding).
- Mechanical? yes.
- Our research: h29 built the option-implied synthetic forward and traded its jumps: all 24 variants lost, because the
  index catching up to the forward came after the options had already moved (`HUNT_H29.md`, Verdict 1-2). Fair value
  as a pricing input: not tested by us.

### Ch. 2.1-2.2. The binomial branch and tree (practical)
One step: a stock that goes up or down and a bond; any payoff is copied exactly by holding (change in payoff / change
in stock) shares plus a bond position. Example: stock and bond both at $1, stock goes to $2 or $0.50; a bet paying $1
on an up-move is worth $0.33 (hold two thirds of a share, borrow one third). The "q" that makes the price a discounted
average must lie strictly between 0 and 1, or there is an arbitrage. Many steps are solved backwards. Worked example:
a recombining tree from 100 with a real up-probability of 3/4 and zero rates; a call struck at 100 after three steps
costs 15. Under the real 3/4 the average payoff would be 33.75, the wrong price. The hedge is traced along two paths:
up-up-down needs 0.5, 0.75 then 1 share with debt rising to 100; down-up-down needs 0.5, 0.25, 0.5 shares and ends
flat. The holdings are random but always known one step ahead.

#### Method 3: binomial replication and backward induction
- See `fm-bjork-arbitrage.md` (Ch. 2) and `fm-binomial-models.md` for the full block; nothing differs here.
- Signal source: volatility / options structure (pricing tool). Maps to NSE (European index options), MCX (options on
  futures), crypto options.
- Our research: not tested by us as such; R103 used Black-Scholes, not trees, to reprice stale legs.

### Ch. 2.3. The binomial representation theorem (theory, two practical lessons)
Seven careful definitions on a seven-node tree: process, measure, filtration (history), claim (may depend on the whole
path, e.g. the maximum price), conditional expectation, previsible process, martingale; the tower law; then the theorem
that one martingale is built from another by previsible scaling. Two lessons we can use:
- The measure is separate from the process: "the size and interrelation of up-moves affects the values of
  derivatives, the probabilities of achieving them does not" (the book's sentence).
- A trading strategy must be previsible, decided one step before the move; the authors say any model without this
  admits arbitrage "or insider trading". This is the mathematical form of our look-ahead guard.

### Ch. 2.4. Overture to continuous models
A tree whose moves are up or down by a volatility-sized step, with equal real chances, becomes log-normal as the steps
shrink; under the pricing probabilities the log-price drifts at the interest rate minus half the variance. This previews
the Black-Scholes formula.

#### Method 4: building a tree from a volatility
- What it computes: up/down factors from volatility and step length so the tree converges to the log-normal model.
- How we could use it: price payoffs with no formula (path-dependent locks, early exercise) on a small tree in the app.
- Signal source: volatility / options structure. Maps to NSE, MCX, crypto.
- Mechanical? yes.
- Our research: not tested by us.

### Ch. 3.1. Brownian motion (theory)
Brownian motion as the limit of finer random walks; its definition needs independent normal increments, not just
normal marginals (the authors' exercise: the square root of time times one normal draw is not Brownian motion). Its
oddities: nowhere differentiable, eventually hits every level, revisits a level infinitely often, looks the same at every
scale. Fitting the UK FTA index 1963-92: plain Brownian motion with drift goes negative; exponential Brownian motion with
a "noisiness" of 17.8% and an annual drift of 8.7% looks like the real index. An exercise builds two correlated Brownian
motions by mixing two independent ones with weights rho and the square root of (1 - rho squared).

### Ch. 3.2-3.3. Stochastic calculus and Ito's formula (theory, one practical fact)
A process is built from a drift part and a Brownian part; volatility and drift are unique given the path. Squared
Brownian increments add up to elapsed time, which is why the Newtonian chain rule fails and Ito's formula has an extra
half-second-derivative term. The product rule gains a covariance term for processes driven by the same noise.

#### Method 5: volatility drag and realised variance
- What it computes: (a) the log-price of a geometric Brownian motion drifts at the arithmetic drift minus half the
  variance, so a volatile asset compounds below its average return; (b) the sum of many squared small log returns
  converges to the true variance, so realised variance is measurable from intraday bars.
- Inputs: log returns at any bar size.
- How we could use it: (a) explains why a leveraged or high-volatility equity curve grows slower than its average trade
  suggests (relevant to sizing); (b) the realised-vol side of every implied-versus-realised comparison.
- Signal source: underlying price. Maps to NSE, MCX, crypto.
- Mechanical? yes.
- Our research: R102 used close-to-close and Parkinson realised vol; the high-low measure runs well below close-to-close
  every year because the overnight gap is a big part of daily variance (`DEEP_NIFTY_BANKNIFTY.md`, 2.2). R16 found the
  next 30-60 minutes' range predictable, direction not (`HUNT_R16_PREDICTABILITY.md`).

### Ch. 3.4. Change of measure: the Cameron-Martin-Girsanov theorem (theory with one big practical consequence)
Measures on a tree are coded by path probabilities; the ratio of two measures' path probabilities (the Radon-Nikodym
derivative) converts one expectation into the other; two measures must agree on what is possible ("equivalent"). In
continuous time, changing to an equivalent measure can only add a drift to Brownian motion; the authors' slogan is
that "drift is measure and measure drift". Volatility cannot change.

#### Method 6: volatility is the only model input you can estimate from history
- What it computes: nothing new; it says which inputs matter. Because the pricing measure keeps the real volatility and
  replaces the real drift, an option price needs a volatility estimate and never a return forecast.
- How we could use it: Jarvis's explanation of why "I think NIFTY will go up" does not change what a call is worth, and
  why the only debate about an option's fairness is realised versus implied volatility.
- Signal source: volatility / options structure. Maps to NSE, MCX, crypto.
- Mechanical? yes (as a principle).
- Our research: the realised-versus-implied comparison is R102's (VIX above next-21-day NIFTY realised on 81% of
  days, n 4,507; `DEEP_NIFTY_BANKNIFTY.md` 2.2). See `bennett-trading-volatility.md` for how traders use the gap.

### Ch. 3.5. Martingale representation (theory)
Any martingale under a measure can be written as a previsible amount of another (non-degenerate) martingale; the amount
is the ratio of their volatilities. A process is a martingale if and only if it has no drift, under a technical
condition (otherwise only a "local martingale"); a practical test is given for exponential martingales.

### Ch. 3.6. Construction strategies (theory, practical definition)
A portfolio is a previsible stock holding plus a bond holding; it is self-financing when every change in its value comes
from price changes alone (no cash in or out). An example shows that holding "twice the current Brownian value" in stock
with a matching bond position is self-financing; the authors warn that every claim of self-financing must be checked
with Ito. A replicating strategy that ends at the payoff fixes the price at every earlier time.

### Ch. 3.7. The Black-Scholes model
The three-step recipe: find the measure that makes the discounted stock a martingale; take the conditional expectation
of the discounted payoff; read the hedge from the representation theorem. Three surprises the authors stress:
replication works for any payoff; the price is an expectation, but not the real-world one (only if the real drift
happened to equal a special value would they coincide); under the pricing measure the stock is still log-normal with the
same volatility. With interest, everything is discounted. Put prices follow from put-call parity.

#### Method 7: Black-Scholes price
- See `fm-bjork-arbitrage.md` (Ch. 7, "Black-Scholes price and risk-neutral valuation") for the block.
- The book's worked example: stock 20, strike 25, two years, volatility 18%, rate 6%, real drift 8% (irrelevant): call
  worth 1.221.
- Signal source: volatility / options structure. Maps to NSE index options yes; MCX options use the forward version
  (Method 11); crypto options yes.
- Our research: R103 repriced stale wing legs with Black-Scholes at their last implied volatility and the six design
  passers all lost (`HUNT_R103_OPTIONS_SELLING.md`, "The stale-leg artefact").

### Ch. 3.8. Black-Scholes in action (practical)
Price behaviour: deep out of the money the option is near worthless; deep in the money it behaves like a forward (spot
minus discounted strike); as expiry nears the value collapses onto the payoff; more volatility always adds value (tiny
volatility gives the forward's value floored at zero, huge volatility gives the stock price).

#### Method 8: the delta hedge and its two end states
- What it computes: the hedge holding is the slope of the option price against the stock, which for a call is N(d1),
  always between 0 and 1 share; the matching borrowing is bounded by the strike. Near expiry it goes to 1 share and a
  debt of the strike if the option will finish in the money, and to nothing if it will not. The authors' example
  (stock 10, volatility 20%, drift 15%, rate 5%, strike 12, one year) shows one path where the hedge climbs to 1 and one
  where it falls to 0; the exercise answer prices that call at 0.325.
- Inputs: spot, strike, time, rate, volatility.
- How we could use it: the app's delta display; a stop or size rule framed in delta (e.g. "this option now moves like
  0.8 of the index"); and the warning that near expiry and near the strike the hedge swings hardest. The book's digital
  exercise on the tree shows this sharply: the hedge for a digital paying 100 jumps to 2.5 shares one step before expiry
  on a path ending near the strike.
- Signal source: volatility / options structure. Maps to NSE, MCX, crypto.
- Mechanical? yes.
- Our research: GEX studies estimate how market-makers' delta hedging moves the index: negative GEX went with a bigger
  rest-of-day range on all 5 indices but said nothing about direction (`HUNT_R28_GEX.md`, Verdict); 0 of 48 gamma-exposure trading rules pass
  across R21-R26 and R34-R36 (`HUNT_R36_GAMMA_EXPOSURE.md`, Verdict). Expiry-day decay: the 09:20 straddle loses 87% of
  its value by 15:15 (`DEEP_NIFTY_BANKNIFTY.md`, 3.2).

#### Method 9: digital (cash-or-nothing) price as a risk-neutral probability
- What it computes: a contract paying 1 if the stock ends above a level is worth the discounted pricing-measure
  probability of that event (N(d2) in Black-Scholes). The book's exercise: stock 10, volatility 20%, rate 5%, one year,
  pays $1 above 10: worth 0.532. On the three-step tree a digital paying 100 above the start costs 50.
- How we could use it: Jarvis can quote "the market's (risk-neutral) chance this option finishes in the money", with
  the caveat that it is a price, not a forecast, and that it ignores the premium paid.
- Signal source: volatility / options structure. Maps to NSE, MCX, crypto.
- Mechanical? yes.
- Our research: on expiry day the close beat the 09:20 straddle on 40.5% of NIFTY days (n 259), yet 97% of cheap options
  still ended below cost (`DEEP_NIFTY_BANKNIFTY.md`, 3.1, quoting R98): finishing in the money is not the same as paying.

#### Method 10: American options as optimal stopping
- What it computes: the seller of an American option must charge the highest value over all exercise strategies that use
  only past information; if the buyer exercises badly, the seller's hedge ends with a surplus.
- How we could use it: little; NSE index options and Indian stock options are European. Some crypto and foreign
  products are American.
- Signal source: volatility / options structure.
- Mechanical? yes (numerically, e.g. on a tree).
- Our research: not tested by us.

### Ch. 4.1. Foreign exchange (practical)
The exchange rate itself is not a tradable; the foreign bond valued in home currency is. The forward exchange rate is
spot times the exponential of the interest-rate difference times time. A call on the currency is the log-normal "Black"
formula on the forward. A home-currency investor and a foreign-currency investor use different pricing measures yet agree
on every price and every hedge: the measures differ only by the choice of unit (numeraire).

#### Method 11: Black's formula on the forward ("forward plus term volatility is enough")
- What it computes: for any asset that is log-normal under the pricing measure, the call price is the discounted value of
  [forward times N(d1) minus strike times N(d2)], with d1 and d2 built from the forward, the strike and the term
  volatility (Ch. 4.1, 4.2, 4.3, 6.2). Only the forward and the volatility over the option's life are needed.
- Inputs: forward (futures price or synthetic forward), strike, term volatility, discount factor.
- How we could use it: price MCX options (written on futures) directly; price NIFTY options off the future or the
  synthetic forward so dividends and carry drop out; compute implied volatility the same way. Our studies already do
  this (Black-76 implied volatility in h29, R42 and the app's FT3 replay).
- Signal source: volatility / options structure. Maps to MCX yes (directly), NSE index options yes via the forward,
  crypto options on futures yes.
- Mechanical? yes.
- Our research: used as a tool, not tested as a rule (`HUNT_H29.md` "ATM IV: Black-76"; `HUNT_R42_SILVERM_SPIKES.md`
  M4 exit pricing; FINDINGS.md R98 FT3 entry).

### Ch. 4.2. Equities and dividends (practical)
With a continuous dividend yield the tradable is the stock with dividends reinvested; under the pricing measure the
stock drifts at the rate minus the yield, and even a forward needs a dynamic hedge (keep buying stock with the
dividends). Periodic dividends make the price drop on known dates; reinvesting removes the jumps. Worked example: a
five-year FTSE product paying 90% of the index's growth, floored at 130% and capped at 180%, is cash plus a call spread
(strikes 1.444 and 2 on a unit index). With drift 7%, volatility 15%, dividend yield 4% and rate 6.5%, the forward is
1.133, the two calls are worth 0.0422 and 0.0067, and the product 0.9712. Forgetting the dividends gives 1.0183, about
5% too high.

#### Method 12: put the dividend yield (or carry) into the forward before pricing
- What it computes: the correct forward for an index whose constituents pay dividends the index does not include.
- How we could use it: any Black-Scholes number the app shows for NIFTY/BANKNIFTY should use the futures price or
  synthetic forward, not spot with a zero yield; long-dated options are mispriced by several percent otherwise.
  Weekly options are barely affected (the error scales with time).
- Signal source: volatility / options structure. Maps to NSE index options yes; MCX (carry instead of dividends);
  crypto (funding rate instead of dividends).
- Mechanical? yes.
- Our research: not tested by us as a pricing error check; h29 used the synthetic forward (`HUNT_H29.md`, Method).

### Ch. 4.3. Bonds (theory for us)
A discount bond can be treated like a Black-Scholes asset only far from maturity, because it must "pull to par". Coupon
bonds: coupons before the option's expiry are cash, the rest is modelled as log-normal. Not relevant to our markets.

### Ch. 4.4. Market price of risk (practical as a concept)
A process is a tradable if and only if its discounted price is a martingale under the pricing measure; in a one-noise
market only two independent tradables can exist, and any third must be priced off them or there is arbitrage. Every
tradable then has the same "market price of risk", the excess return over the bank rate per unit of volatility (the
Sharpe ratio of the instantaneous move). The authors warn not to read economics into it: it is just the drift change
of the measure change. Non-tradable quantities (an exchange rate, the square of a price, a log price) get their pricing
dynamics through Ito applied to a tradable function of them.

#### Method 13: excess return per unit of volatility must be the same for linked assets
- What it computes: a consistency check. Two instruments driven by the same noise must offer the same excess return
  per unit of volatility; if not, a hedged combination earns money without risk.
- How we could use it: as a lens on "relative value" between instruments tied to one driver (index, future,
  synthetic forward, options): a gap must be smaller than costs or it would be traded away. This is what our studies
  keep finding (gaps exist but are below the ~Rs 70 NIFTY round-trip cost; FINDINGS.md line on costs).
- Signal source: volatility / options structure. Maps to NSE, MCX, crypto.
- Mechanical? partly (needs an instantaneous excess-return estimate, which is noisy).
- Our research: not tested by us in this form.

### Ch. 4.5. Quantos (practical for crypto and commodities)
A quanto pays a foreign asset's price as a number in the home currency (e.g. a sterling share price paid out in dollars).
With a two-noise model (stock and exchange rate with correlation rho), the quanto forward equals the local forward times
an adjustment of exp(minus rho times the two volatilities times time): above the local forward exactly when the
correlation is negative. Digitals and calls are Black's formula on the quanto forward. The authors stress that even the
"clean" digital (pays if the share ends above the strike) depends on the currency, because replication runs through the
exchange rate. The multi-dimensional market price of risk is the inverse volatility matrix times the excess-return vector.

#### Method 14: correlation-adjusted forward for cross-currency payoffs
- What it computes: the fair forward (and so option prices) when a payoff in one currency is paid in another without
  conversion at the market rate.
- Inputs: local forward, the two volatilities, their correlation, time.
- How we could use it: (a) crypto options on Delta Exchange India settle in rupees on a dollar-priced index; whether the
  rupee payout uses the live USD/INR (no adjustment) or a fixed rate (a quanto, needing this adjustment) we have not
  checked; (b) MCX GOLD and SILVER follow dollar bullion times USD/INR, so their volatility includes the currency and
  the correlation term. Mostly a "know what you are pricing" check.
- Signal source: volatility / options structure. Maps to crypto (Delta India INR-settled options) and MCX bullion;
  not NSE index options.
- Mechanical? yes.
- Our research: not tested by us. RC2 tested 8 option-buying rules on Delta India BTC/ETH options, each with a stop and a
  profit lock: none passed (`HUNT_RC2_CRYPTO_OPTIONS.md`, Verdict).

### Ch. 5. Interest rates (theory for us; one practical model)
5.1: discount bonds, yields, the short rate and instantaneous forward rates carry the same information; the forward
curve sits above the yield curve when yields rise with maturity, below when the curve is inverted. 5.2: a one-noise
forward-rate model shows something new: no-arbitrage restricts the real-world drifts of forward rates (they must differ
from the pricing drift only by one common market price of risk). 5.3: the single-factor Heath-Jarrow-Morton framework;
bond prices are pricing-measure expectations of discounting at the short rate, and depend only on volatilities. 5.4:
every popular short-rate model is an HJM model: Ho-Lee (normal rates, constant volatility, can go negative), Vasicek /
Hull-White (mean reverting), Cox-Ingersoll-Ross (square-root volatility keeps rates positive), Black-Karasinski (log of
the rate mean reverts). 5.5: several noises let different maturities move imperfectly together; n noises need n hedging
bonds. 5.6: products: forward rate agreements (static, model-free hedge), floating-rate notes (worth par), swaps and
forward swap rates, bond options (Black's formula with the bond's forward), Jamshidian's trick (an option on a coupon
bond is a portfolio of zero-coupon options), caps and floors (a caplet is a put on a bond), cap-floor parity, swaptions
(options on coupon bonds). 5.7: a two-factor model (parallel shocks plus short-end shocks), the general Gaussian
multi-factor model with a Black-type option formula, and the Brace-Gatarek-Musiela LIBOR market model (LIBOR log-normal
under its own forward measure, so caplets get Black's formula; an approximation for swaptions).

#### Method 15: Ornstein-Uhlenbeck mean reversion (Vasicek, Ch. 5.4)
- What it computes: a process pulled toward a long-run level with strength proportional to its distance from it, plus
  noise. Its distribution settles to a normal with a fixed mean and a variance of noise squared over twice the pull
  strength. The authors stress that the distribution converges, not the path, and that mean reversion seen in real data
  does not by itself choose the model, because a change of measure can add or remove it.
- Inputs: a level, a pull strength and a noise size, fitted to a spread or rate series.
- How we could use it: the standard model behind spread, ratio and volatility mean-reversion ideas (index ratio, gold/
  silver ratio, India VIX). The pull strength gives a half-life for how long a stretched spread should take to revert.
- Signal source: underlying price (the spread or ratio of underlyings); for VIX, volatility. Maps to NSE (cross-index
  spreads), MCX (ratios, calendar spreads), crypto (BTC/ETH ratio, with a stop and a profit lock).
- Mechanical? yes.
- Our research: R94 tested a GOLD/SILVER log-ratio reversion (z over 60 / 120 days, entry at |z| >= 2): it lost in design
  (-Rs 6.3 lakh on the 60-day version) and in the holdout, because the ratio kept running (`HUNT_R94_MCX_ADVANCED.md`;
  FINDINGS.md R94). MCX calendar spreads earned about Rs 0 a trade before costs (same entry). An O-U half-life fit on
  NIFTY/BANKNIFTY spreads: not tested by us (their daily correlation is 0.877, n 5,086; `DEEP_NIFTY_BANKNIFTY.md` 1.4).

### Ch. 6.1-6.2. General stock model and log-normal models
Volatility, drift and rates may depend on time and the past path; the price still needs only the rate path and the
volatility, never the drift. Without a formula, prices come from numerical methods and the hedge from a finite
difference (change in price over change in stock across a short interval). When the asset and the discount factor are
jointly log-normal under the pricing measure, a generalised Black-Scholes formula holds with the forward corrected for
their correlation.

#### Method 16: finite-difference delta from any pricer
- What it computes: hedge ratio = (price after a small bump in the underlying minus price before) / bump size.
- How we could use it: a model-free delta for any option or payoff the app can price, including Boss's locked or
  trailing positions treated as path-dependent claims.
- Signal source: volatility / options structure. Maps to NSE, MCX, crypto.
- Mechanical? yes.
- Our research: not tested by us.

### Ch. 6.3. Multiple stock models
Several stocks driven by several independent noises: volatility becomes a vector, the total volatility is the square
root of the sum of its squared components, the product rule carries a covariance term, and the multi-factor change of
measure needs the volatility matrix to be invertible. With more securities than noises there may be arbitrage; with
fewer, some claims cannot be hedged.

#### Method 17: correlated two-asset model (index pairs)
- What it computes: joint dynamics of two linked assets (e.g. NIFTY and BANKNIFTY) via a volatility matrix; prices of
  spread or relative payoffs depend on the correlation.
- How we could use it: risk of holding options on both indices at once (they are not two independent bets), and any
  future cross-index spread study.
- Signal source: underlying price. Maps to NSE (NIFTY/BANKNIFTY/FINNIFTY), MCX (GOLD/SILVER), crypto (BTC/ETH).
- Mechanical? yes.
- Our research: daily correlation of BANKNIFTY on NIFTY 0.877 (n 5,086), 0.842 on 5-minute bars (n 89,575 bars)
  (`DEEP_NIFTY_BANKNIFTY.md` 1.4). R72's passing rule uses breadth across indices, not a spread: C1 Breadth Donchian
  passed (twin p 0.008, BH q 0.032; `HUNT_R72_CROSS_INDEX.md`).

### Ch. 6.4-6.5. Numeraires and cross-currency interest-rate models (theory)
Any tradable can be the unit of account; the price never changes, only the measure, and the ratio of two numeraires is
the conversion between their measures. The bond maturing at T as numeraire gives the "T-forward measure", under which a
forward price is a plain expectation. A dollar investor and a sterling investor with two HJM rate markets and an exchange
rate again agree on every price.

### Ch. 6.6. Arbitrage-free complete models: Harrison-Pliska (practical message)
No arbitrage holds if and only if there is at least one measure making all discounted prices martingales; the market is
complete (every claim hedgeable) if and only if that measure is unique. The intuition: a martingale is a fair game, and
any strategy that only plays fair games cannot guarantee a profit from nothing.

#### Method 18: the fair-game test for a trading idea
- What it computes: a frame, not a number. If discounted prices behave like martingales (no drift you can forecast), then
  no previsible trading strategy has an edge; any real edge must come from somewhere the martingale picture leaves out:
  a risk premium (the gap between real and pricing probabilities), costs and frictions, or information others lack.
- How we could use it: as a first question for every new idea Boss brings: "which of the three does this rule harvest?"
  A rule that only reads past prices of a near-martingale has none of them.
- Signal source: none (method). Maps to NSE, MCX, crypto.
- Mechanical? yes (as a checklist).
- Our research: it matches our record. Direction over the next 15/30/60 minutes was not predictable (AUC 0.509-0.536;
  `HUNT_R16_PREDICTABILITY.md`); NIFTY 5-minute bars follow the previous sign 48.4% of the time (n 167,082;
  `DEEP_NIFTY_BANKNIFTY.md` 2.5). The one premium we measured is the volatility premium (Method 1 above).

### Appendices
A1, a short reading list (Ross, Grimmett-Stirzaker, Williams, Revuz-Yor, Rogers-Williams; Hull, Duffie,
Wilmott-Dewynne-Howison; papers by Black-Scholes, Merton, Garman-Kohlhagen, HJM, Ho-Lee, Vasicek, Hull-White, CIR,
Black-Karasinski, BGM, Harrison-Pliska, Delbaen-Schachermayer). A2, notation. A3, full answers to the exercises (the
numbers quoted above). A4, a glossary that defines "term volatility" as the annualised volatility over a period and
"exotics" as new products that "will quickly either become standard products or will sink without trace".

## Performance claims
None. The book makes no claims about trading profits, win rates or returns. Its numbers are worked pricing examples:
a call worth 15 on a three-step tree, 1.221 for the 20/25 two-year call, 0.325 for the 10/12 one-year call, 0.532 for a
one-year digital, 0.9712 for the FTSE guaranteed product (1.0183 if dividends are forgotten). The FTA index fit (17.8%
noisiness, 8.7% drift, 1963-92) is a single illustrative fit with no test.

## Risk, sizing and psychology
No sizing, no stops, no psychology. Its risk content is hedging: the seller's risk is removed by holding the delta and
rebalancing; the hedge is self-financing only in the ideal world (no costs, continuous trading), and the authors note
in passing that they ignore transaction costs and illiquidity. The bookmaker parable is the one piece of risk
philosophy: pricing from your own probabilities gives a positive average with a chance of a large single loss; pricing
from the hedge gives a certain small income. Volatility drag (Ch. 3.3) is the only sizing-relevant fact, and the book
does not draw that link itself.

## How it relates to our research
- Agrees: "price depends on volatility, not drift" and "a fair game cannot be beaten" fit our main finding that direction
  is a coin flip while the size of moves is forecastable (R16; `DEEP_NIFTY_BANKNIFTY.md` 2.5 and 4.2: 1 of 70 sign rules
  confirmed and it was an artefact).
- Agrees: the gap between the crowd's price and the outcome (the bookmaker's lesson) is what R102 measured (VIX above NIFTY
  realised on 81% of days, n 4,507). Harvesting it with defined-risk selling failed our gates (R103, 0 of 24), and the
  naked straddle's profit came with fat tails (`HUNT_R103_OPTIONS_SELLING.md`, Verdict).
- Already used: Black-Scholes and Black-76 as tools (R103 repricing, h29, R42, FT3), with the book's insistence that the
  forward (not spot) is the right input.
- Already rejected (nearest tests): mean-reversion of a ratio (R94 gold/silver), option-implied forward as a signal (h29,
  0 of 24 variants), gamma exposure as a signal (R36, 0 of 48).
- Not tested: quanto adjustment for Delta India rupee settlement; dividend-yield error in the app's Greeks; finite-
  difference delta for path-dependent locks; O-U half-life on index spreads.

## What is new to us
- The quanto correlation adjustment (Ch. 4.5) as a check on crypto options settled in rupees and on MCX bullion.
- The explicit dividend-forward error (Ch. 4.2: about 5% on a five-year product) as a reason to price NIFTY options off
  the future or synthetic forward everywhere in the app.
- Numeraire changes (Ch. 6.4): pricing in "forward terms" with a forward measure, a clean way to handle carry and funding.
- Harrison-Pliska as a one-line screening question for Boss's ideas: which premium, friction or information does the
  rule harvest?
- The authors' caution that observed mean reversion does not pick the model (Ch. 5.4), a useful brake on reading
  stationarity into a backtest.

## Could not read
- Nearly all displayed formulas and boxed results (OCR garble: Black-Scholes, the quanto adjustment, HJM drift condition,
  BGM swaption approximation, Ito's formula, Radon-Nikodym boxes); the surrounding prose and the answers in Appendix 3
  were read, and the standard forms are known.
- All figures (binomial trees 2.1-2.13 with their node values, the Brownian and FTA index plots 3.1-3.8, the option value
  and hedge paths 3.14-3.17, yield and forward curves 5.1-5.6, the O-U and CIR sample paths 5.7-5.8, the swap cash-flow
  diagram 5.9) are images; node values of the worked trees were recovered from the tables and the answers.
- Page 1 (cover) and the boxed "three steps to replication" text are partly garbled; nothing else is missing.
