# Binomial Models in Finance - John van der Hoek and Robert J. Elliott (2006)

Source: binomial-models-in-finance.txt, 308 pages (14 chapters, appendices A-F, references, index), read in full on 25 Oct
2026 (text layer, pdftotext; every chapter read in order, notes kept as we went).
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A university textbook (Springer Finance) on pricing derivatives with binomial (up/down) trees instead of calculus.
It builds everything from one axiom: there is no free lunch (no arbitrage), so a payoff that can be copied costs what the copy costs.
It covers stock, currency, futures and bond options, American and exotic options, the Greeks, dividends and trees fitted to the smile.
Later chapters fit trees to market option prices, model interest rates, value "real options" and price risks that cannot be hedged.
Appendices cover the binomial-to-Black-Scholes limit, incomplete markets by linear programming, and historical and implied volatility.

There are no trading rules in the book. It is a pricing and hedging manual; what it gives us is tools (fair value,
Greeks, the market's implied distribution of the expiry close, bounds that option prices must respect) and a few warnings.
The volatility-trading view of the same tools is already in `research/books/bennett-trading-volatility.md`; this
write-up does not repeat it.

## Methods, rules and setups (chapter by chapter)

### Ch. 1 Introduction: no arbitrage and its consequences
Teaches the no-arbitrage axiom and the "law of one price" (two things with the same payoff in every future state have
the same price today). Defines forwards, calls and puts, European / American / Bermudan styles, long and short, and
the three kinds of market player (speculators, hedgers, arbitrageurs). Time is measured in years: calendar days / 365
or trading days / 250 (both shown). A worked leverage example: a stock bet earns +6.82% if right; the same view through
an out-of-the-money call earns +66.67% if right but loses 100% if wrong. Notes that buyers and sellers both mostly trade
out-of-the-money options and that such American options are rarely exercised early. No method to test.

### Ch. 2 The one-step binomial model for stock options
Teaches the core recipe in two steps: (1) copy the option's two possible payoffs with H1 shares plus H0 cash; (2) by no
arbitrage the option costs H0 + H1 x spot. The share count H1 = (option up - option down) / (stock up - stock down) is
the hedge ratio (delta). The price equals a discounted average of the payoffs with a "risk-neutral" up-probability
pi = (R - d)/(u - d), which needs no view on the real chance of up or down; the model is arbitrage-free only if
d < R < u. Shows why pi is the probability a risk-indifferent person would use, and that with real-world odds you must
use a risk-adjusted discount (a CAPM-like beta formula holds in this toy model). Then model-free results:

**Method 2.1 - Put-call parity and the no-arbitrage bounds (Ch. 2.5-2.6, Exercises 2.44, 2.45, 2.49)**
- What it computes: for European options on the same strike and expiry, call - put = spot - PV(strike) (with an index
  future: call - put = PV(future - strike)). Also: a call is worth at least spot - PV(strike); a longer-dated call is
  worth at least a shorter one; a lower-strike call is worth more but by no more than PV of the strike gap; a butterfly
  (buy K1 and K3, sell two K2) must cost more than zero. The interest rate implied by parity is K / (S + P - C).
  Any violation is a riskless profit (the book spells out the conversion / reversal trades).
- Inputs: call and put prices, spot or future, rate, time.
- How we could use it: as a data-quality gate on the option lake (a quote that breaks parity or makes a butterfly
  negative is almost certainly stale), and to back out the "synthetic forward" from the chain.
- Entry / stop / exit as a trade: enter the conversion or reversal when the violation exceeds round-trip costs; no stop;
  hold to expiry (cash-settled). See rules file (`put-call-parity-arbitrage`, `butterfly-convexity-arbitrage`).
- Signal source: option premium (the prices of the chain itself; an options-structure trade, not our default).
  Maps to NSE index options: yes (European, cash-settled; NIFTY/BANKNIFTY have futures for the forward). MCX options
  (on futures): yes in principle. Crypto options: yes in principle (would need a stop and lock per CLAUDE.md, which
  makes no sense for a locked-in arbitrage; flag before any use).
- Mechanical? yes, but needs simultaneous bid and ask quotes.
- Our research: not tested as a trading rule by us. R103 (`HUNT_R103_OPTIONS_SELLING.md`, "The stale-leg artefact")
  found the lake's far-wing prices stale: repriced legs moved a mean -Rs 711 to -Rs 1,698, and that staleness alone
  created all six design "passes". Apparent parity breaks in the lake will mostly be the same artefact.

Other points of the chapter: American calls on non-dividend stocks are never exercised early; American puts carry an
early-exercise premium. Exercises fit u, d and pi by an open-interest-weighted regression of call price on strike, and
back out u from an at-the-money call price (u = (1 + R x)/(R (1 - x)), x = call / spot), checking it against
volatility x square root of time.

### Ch. 3 The binomial model for other contracts
Forwards (fair forward = spot x growth of cash), pay-later ("contingent premium") options (the premium paid only if
in the money is larger than the up-front premium), currency options with two interest rates, covered interest parity
(forward FX = spot x e^((r domestic - r foreign) t)), Arrow-Debreu "state prices" (the price today of 1 rupee paid in
one state only), and one-step interest-rate models (Ho-Lee, Black-Derman-Toy). An exercise shows the Derman-Kani idea:
spot, rate, forward and the ATM call price are enough to pin the up and down values of a one-step tree.
Practical only as background: the forward/futures carry relation is how NIFTY futures sit above spot.

### Ch. 4 Multiperiod binomial models
Labels the nodes of a recombining tree, lets u, d, R and pi differ by node, and prices any European claim by backward
induction from expiry. State prices can be built forward from today (Jamshidian's forward induction) and then any
payoff is a sum of payoff x state price. For the constant Cox-Ross-Rubinstein (CRR) tree the call price is a
binomial-distribution formula, and with u = e^(sigma sqrt(dt)) it tends to Black-Scholes as the steps grow.

**Method 4.1 - CRR / Black-Scholes fair value and risk-neutral probability of finishing in the money (Ch. 4.9-4.10,
App. A.7)**
- What it computes: the fair price of a European option, and (as a by-product) the risk-neutral probability that the
  expiry close ends above the strike, which in the Black-Scholes limit is N(d2); N(d1) is the same probability under
  the "stock as numeraire" measure.
- Inputs: spot (or future), strike, time to expiry, rate, volatility.
- How we could use it: Jarvis can quote "the market prices a 30% chance this strike finishes in the money" from N(d2)
  at the chain's own IV; it is a risk-neutral number, not a forecast. Already used: R103 repriced stale legs with
  Black-Scholes at the leg's last IV (`HUNT_R103_OPTIONS_SELLING.md`, Amendment 2), and `DEEP_OPTIONS_SELLING.md` has a
  Black-Scholes table at NIFTY 25,000 and 15% IV.
- Signal source: volatility / options structure (pricing tool, no signal). Maps to NSE index options yes (European);
  MCX options on futures yes (use Black-76 form); crypto options yes.
- Mechanical? yes.
- Our research: used as a tool, not tested as a signal.

Exercises (same chapter): American put on a 10-step spreadsheet (S 90, K 100, r 8% gives 3.08), implying volatility by
search, chooser options, compound options (an option on an option), binary options (the price of a binary call is the
discounted risk-neutral probability of finishing above the strike) and forward-start options.

### Ch. 5 Hedging
Teaches self-financing dynamic replication: at each node hold H1 = change in option value / change in stock value
shares and the rest in cash; the portfolio needs no new money and ends exactly at the option's payoff. Worked example
(S 80, u 1.5, d 0.5, R 1.1, 3 steps, strike 80): model call 34.0796; if the market pays 36, sell the call, hedge, and
1.92 is locked in on every path - "if the model is correct". The book names the catch: model risk.

**Method 5.1 - Sell an overpriced option and delta-hedge to expiry (Ch. 5.1, Remark 5.2)**
- Entry: when the market price of an option is above the model price (the book's literal case); in practice this means
  market implied volatility above the volatility you expect to be realised.
- Stop: none stated (the hedge is the risk control).
- Exit: hold to expiry, rebalancing the stock/futures hedge at every step.
- Filters: none stated; relies on the tree being the true model.
- Market and timeframe: stock options, any horizon.
- Signal source: volatility / options structure (implied vs forecast volatility). Maps to NSE index options: yes with a
  NIFTY/BANKNIFTY futures hedge; MCX options: yes with the future; crypto: yes (perpetual hedge; stop and lock needed).
- Mechanical? partly: the hedge is mechanical, the "fair" volatility is a modelling choice.
- Our research: not tested by us as a delta-hedged trade. The premise has support: R102 (`DEEP_NIFTY_BANKNIFTY.md`
  2.2) found India VIX above NIFTY's forward realised volatility on 81% of days (median ratio 1.28) but BANKNIFTY's
  realised above VIX on 60% of days. Unhedged selling failed R103 (0 of 24). See Bennett's write-up for the gamma
  scalping view. Rules file: `delta-hedged-short-straddle-iv-over-hv`.

### Ch. 6 Forward and futures contracts
Forward price = spot / discount factor; futures prices follow a risk-neutral average (no discounting) because of
daily marking to market through margin accounts; margin and the clearing house remove default risk. Futures and
forward prices are equal when interest rates are not random (example with random rates: forward 107.36 vs futures
107.12). Practical background only: NIFTY futures basis is carry minus dividends; no basis signal is proposed.
Our research: H39 (`HUNT_H39.md`) found heavyweight synthetic forwards and IV move with the index in the same minute
but lead it by at most an IC of 0.012 from 2 minutes on - nothing to trade.

### Ch. 7 American and exotic option pricing
American options: at each node take the larger of exercising now and holding (example: European put 14.18, American
18.51, early-exercise premium 4.33; a 10-step example gives 3.0762 and an "early exercise frontier"). Barrier options
(knock-out / knock-in, up / down) priced by zeroing or switching values at barrier nodes; an up-and-out call (spot 100,
strike 80, barrier 120) costs 6.26 against 24.64 for the plain call; knock-in plus knock-out equals the plain option;
how the barrier is monitored (end of day vs continuous) changes the price; rebates; FX hedging stories; "booster"
(pays for time spent inside a range) and partial-barrier exercises.
Practical: NSE index options are European, so early exercise does not matter to us. Barrier logic is the payoff of a
"knock-out" stop: a long option with a stop on the underlying behaves like a down-and-out option, which is cheaper to
hold only in the sense that you give up the rebound. No rule.

### Ch. 8 Path-dependent options
Non-recombining trees (2^n paths) for payoffs that depend on the route: Asian (average-price) options cost less than
plain ones because an average moves less (5.6661 vs 9.0469 for the call in the example), lookback options are dear
(lookback put 9.6589 vs 4.1698), floating-strike and ladder options, and the Hull-White trick of carrying a few
representative averages per node to keep the tree small. No trading content. For us: an average-price exit (e.g. scaling
out over several minutes) has lower variance than one exit price, which is the same arithmetic.

### Ch. 9 The Greeks

**Method 9.1 - Delta, gamma, theta, vega, rho from the formula and from a tree (Ch. 9.1-9.5)**
- What it computes: delta (call N(d1), put N(d1) - 1), gamma (same for calls and puts), theta, vega, rho, both in
  Black-Scholes closed form and numerically from a tree by bumping an input. Identity tying them together:
  theta + r S delta + half sigma^2 S^2 gamma - r C = 0 (the Black-Scholes equation), so theta can be read off delta and
  gamma.
- Inputs: spot, strike, time, rate, volatility; or a tree.
- The warning: on a 10-step tree a naive "bump spot by 1" gamma came out 0.186 against the true 0.0202 (about ten times
  too big); a formula matched to the tree's own step (Pelsser-Vorst) gave 0.01957. Tree delta by bumping was close
  (0.7670 vs 0.7709). Choose the bump to match the tree step, or use closed forms.
- How we could use it: any app or research code that shows Greeks from a numerical model should use closed forms or a
  step-consistent bump; the gamma behind our GEX features (R28) is the closed-form Black-Scholes gamma, which avoids
  this trap.
- Signal source: volatility / options structure (risk measures). Maps to NSE index options yes; MCX and crypto options yes.
- Mechanical? yes.
- Our research: R28 / R36 GEX built from Black-Scholes gamma x OI (`FINDINGS.md`, "Dealer gamma exposure"): negative GEX
  came before bigger rest-of-day ranges on all 5 indices, but 0 of 10 GEX arm filters passed.

### Ch. 10 Dividends
Ex-dividend price drops; forward = spot x growth - dividend; trees with dividends as a fraction of price (recombining)
or as known cash amounts (model "price minus PV of dividends" as the random part so the tree still recombines).
American calls may be exercised only just before an ex-dividend date and only when the dividend outweighs the interest
on the strike and the lost time value. Practical for us: index forwards and IVs should use the dividend-adjusted forward
(read it from the futures price or from parity, Method 2.1), not spot.

### Ch. 11 Implied volatility trees (Derman-Kani, Barle-Cakici)

**Method 11.1 - Local-volatility tree fitted to the smile**
- What it computes: a tree whose node prices are chosen one by one so that the tree reprices every market call and
  put across strikes and expiries (the smile and term structure), using state prices built forward. Fix-ups when a node
  would imply a negative probability (Derman-Kani) or centring on forward prices (Barle-Cakici).
- Inputs: spot, rates, dividends, an implied-volatility surface (interpolated from the quoted strikes).
- How we could use it: smile-consistent Greeks and fair values for strikes outside the lake's ATM +-10 window (instead of
  pricing far wings at the ATM IV); a better reprice for stale legs than flat Black-Scholes.
- Signal source: volatility / options structure. Maps to NSE index options yes (we have 1-minute ATM +-10 IV);
  MCX options partly (thin chains); crypto options yes (Deribit / Delta chains).
- Mechanical? yes, but numerically fragile (the book says so).
- Our research: not built by us. R102 (`DEEP_NIFTY_BANKNIFTY.md` 3.4) measured the skew it would fit: puts five strikes
  out dearer than calls on 91% of NIFTY days (median +2.56 IV points), ATM IV median 22.6 on expiry days vs 15.0 on
  other days.

Exercises use real (Telstra) quotes to back out implied volatility per strike, plot the smile, and interpolate with a
four-point cubic to price an off-grid strike.

### Ch. 12 Implied binomial trees (Rubinstein; van der Hoek; Jackwerth)

**Method 12.1 - The market's implied distribution of the expiry close from butterfly prices**
- What it computes: the risk-neutral probability that the expiry close lands at each strike. With equally spaced
  strikes, the probability at strike K is (growth factor / strike gap) x (call at K below - 2 x call at K + call at K
  above), i.e. the price of a butterfly scaled up; the two tails come from the end calls (van der Hoek 1998). Rubinstein
  (1994) instead picks the probabilities closest to a plain binomial prior that reprice all calls exactly (a quadratic
  program). A backward "one, two, three" pass then turns the terminal probabilities into a full tree that reprices
  every quoted call. Jackwerth's weights let the tree also fit American or other-expiry prices.
- Check in the book: for Black-Scholes calls (spot 100, 20% vol, strikes 75-125 step 5) the butterfly probabilities
  match the model's bucket probabilities (0.0981 vs 0.0976 at the 100 strike).
- Inputs: one expiry's call (or put) prices at evenly spaced strikes, spot or future, rate.
- How we could use it: (a) for Jarvis and the expected-range nowcast, the chain's own odds of finishing beyond +-1% or
  beyond a straddle breakeven; (b) a research comparison of implied tail odds against what NIFTY actually did (a
  measure of the tail premium sellers collect); (c) a cleaner "where does the market expect expiry" number than max pain.
- Signal source: option chain as context (a distribution read from option prices; it does not pick direction).
  Maps to NSE index options yes (weekly NIFTY, strike step 50; BANKNIFTY step 100); MCX partly; crypto yes.
- Mechanical? yes. Needs clean prices at every strike; stale wings (R103) will bend the tails, and the lake stops at
  ATM +-10.
- Our research: not tested by us. Nearest: R16 (`FINDINGS.md`, "What is predictable?") found max pain WORSE than "no
  change" at predicting the expiry close (0.74% vs 0.43% error, BANKNIFTY), and R102 found expiry closes a median 0.60%
  from the previous day's max pain (n 289). The implied distribution is a different object (it prices odds, it does not
  claim a target), so it is context only.

### Ch. 13 Interest rate models
Builds today's discount curve from treasury coupon bonds (bootstrapping, polynomial and spline fits), from bank bill
and swap rates (a quadratic bootstrap used by practitioners), log-linear interpolation of discount factors, a credit
spread curve, Nelson-Siegel fits, and binomial short-rate models (Ho-Lee and its negative-rate problem, Pedersen-Shiu-
Thorlacius, Morgan-Neave, Black-Derman-Toy calibrated to yields and yield volatilities with bisection, Newton and secant
root-finders), absolute vs proportional rate volatility, and Merton's view of a defaultable bond (equity is a call on
the firm). Theory for our purposes; the only practical piece is the rate input to option formulas (use the T-bill /
MIBOR rate for the option's tenor) and the root-finding methods, which are the same tools used to solve for implied
volatility.

### Ch. 14 Real options and options on things you cannot trade
Shows that a discounted-cash-flow valuation with one risk-adjusted rate misprices flexibility (a government guarantee
valued at 50 by DCF is worth 66.67 by risk-neutral pricing) and lists the options inside a project: defer, stage,
expand, contract, shut down, abandon. When the underlying cannot be traded, the market is incomplete and there is no
single price; the authors use "indifference pricing" with exponential utility.

**Method 14.1 - Certainty-equivalent (indifference) value of a risk you cannot hedge**
- What it computes: the amount that leaves you as happy as carrying the risk. Hedgeable part priced risk-neutrally;
  the leftover priced at roughly (mean - risk aversion / 2 x variance) for a bid, plus for an ask. The bid-ask gap widens
  with risk aversion (worked example: ask 7.43, 7.66, 7.77 and bid 6.57, 6.34, 6.23 for risk aversion 1, 2, 3). A
  firm's "local" risk aversion can be backed out from its required return.
- How we could use it: as a sizing and comparison yardstick for an arm's P&L stream (score = mean - k x variance per
  trade rather than mean alone), consistent with how our H15 work found full-size bets unstable.
- Signal source: none (a valuation / risk measure). Maps to all markets as a scoring rule.
- Mechanical? yes once the risk-aversion number is fixed (that number is a choice).
- Our research: nearest is H15 (`HUNT_H15.md`): half-Kelly sizing was "tradable" but the Kelly fraction was unstable
  (estimated on the same 10 regime months the bootstrap resampled); H17 and FINAL then fixed lots with no Kelly.

The chapter ends with Copeland-Antikarov's "market asset disclaimer" vs indifference prices for abandon / contract /
expand options on a project tree (they differ, and can change the decision), and calibration of a non-traded asset to
an index through its correlation.

### Appendix A - The binomial distribution and the limit to Black-Scholes
Bernoulli trials, binomial counts, mean np and variance np(1-p), a polynomial approximation to the normal curve
(4 decimals), the central limit theorem with continuity correction, the Berry-Esseen error bound, and the proof that the
CRR price tends to Black-Scholes. Pure background.

### Appendix B - Incomplete markets, linear programming and transaction costs

**Method B.1 - No-arbitrage price band by super- and sub-replication**
- What it computes: when the next step has three or more outcomes (a trinomial market), stock and cash cannot copy an
  option, so there is no single price but a band. The ask is the cheapest stock-and-cash portfolio that covers the
  payoff in every state (a linear program); the bid is the dearest portfolio the payoff always covers. Example (spot
  80 going to 85 / 80 / 75, strike 78): super-replication price 4.02; adding a traded strike-80 call at 2 completes the
  market and fixes the strike-78 call at 3.50. By LP duality the ask is the highest and the bid the lowest
  risk-neutral expected payoff. Proportional transaction costs (Boyle-Tan) enter as k x |change in hedge| x price and
  turn the problem into a mixed-integer program; costs widen the band.
- How we could use it: model-free bounds on an option's value from the options next to it (a sanity range for quotes,
  for repricing stale legs, and for "is this premium cheap?" claims); and a reminder that rehedging costs widen any
  fair-value band.
- Signal source: volatility / options structure. Maps to NSE index options yes; MCX and crypto yes.
- Mechanical? yes.
- Our research: not tested. Costs are central in ours: ~Rs 70 a trip incl. slippage (`FINDINGS.md`, opening summary),
  and an ATM option round trip about Rs 98 NIFTY / Rs 133 BANKNIFTY (R16 cost test, `knowledge/baselines.yaml`).

Also: the first fundamental theorem (no arbitrage if and only if positive risk-neutral probabilities exist) and the
second (complete if and only if they are unique), with a test for which payoffs are attainable. Theory.

### Appendix C - Volatility estimation

**Method C.1 - Historical volatility**
- What it computes: standard deviation of daily log returns (divide by N - 1), annualised by the square root of the
  number of trading days (252 in the example; 250, 260 or 261 elsewhere). Hull's 20-day example gives 19.5% a year with
  a standard error of about 3.1% (sigma / square root of 2N). The book raises the practical questions: which daily
  price, Friday-to-Monday counted as one day, whether volatility is the same every weekday, and using the same day count
  when the number goes into Black-Scholes.
- Signal source: underlying price/volume. Maps to all.
- Our research: R102 (`DEEP_NIFTY_BANKNIFTY.md` 2.2) computes close-to-close and Parkinson high-low realised volatility
  by year; R16 found the next 30-60 minute range predictable (holdout error 15-20% below the time-of-day average).

**Method C.2 - Implied volatility by Newton-Raphson or bisection**
- What it computes: the volatility that makes the formula match the market price. Newton steps with vega converge in a
  few steps (example: 20% start, 9.48% after two steps for a call at 8.61); bisection needs about 11 steps for 0.01%
  accuracy but no vega, so it works for American or tree prices.
- Practitioner notes in the book: use out-of-the-money options to read IV (puts below spot, calls above); implied
  volatility differs by strike (smile / skew) and by expiry (higher near expiry); fit a smooth curve through IV against
  strike before pricing an off-grid strike; whether historical or implied is "better" is open.
- Signal source: option chain as context. Maps to NSE index options yes; MCX and crypto options yes.
- Our research: our IV columns come from Dhan; R102 3.4 notes the ATM IV print breaks down in the last minutes of expiry
  day (6.3 at 15:15 for NIFTY) - "do not read IV there", which matches the book's caution that the model fails near
  expiry.

### Appendices D-F
D: Farkas' lemma - non-negative terminal probabilities that reprice all quoted calls exist if and only if the quotes
allow no static arbitrage (otherwise the lemma hands you the arbitrage portfolio). E: any piecewise-linear payoff is a
sum of calls (static replication), butterflies with uneven strikes, and the general van der Hoek recipe. F: cubic-spline
discount curves (knots about the square root of the number of bonds, weighted least squares), forward rates and yields
from the fitted curve. Theory and curve fitting; E is the maths behind Method 12.1.

## Performance claims
- No trading performance claims. Every number is a worked pricing example on invented or textbook data (e.g. the 1.92
  "locked-in" profit of Ch. 5 assumes the tree is the true model; the book itself labels the gap "model risk").
- Accuracy claims with evidence: CRR converges to Black-Scholes (proved in App. A); butterfly-implied probabilities match
  Black-Scholes bucket probabilities (Table 12.6, all 11 buckets within about 0.002); naive tree gamma is about ten
  times off (0.186 vs 0.0202) while the Pelsser-Vorst formula is close (0.01957).
- Real-options claim: indifference pricing and the "market asset disclaimer" method give different values (e.g.
  abandonment option 5.066 vs 2.155 on the same tree) and can lead to different decisions; evidence is the worked
  example only.

## Risk, sizing and psychology
- Model risk is the central warning: a hedge "locks in" profit only if the volatility and tree are right.
- Options give leverage both ways: the 66.67% gain example becomes a 100% loss if the view is wrong.
- Incomplete markets (more than two outcomes, jumps, costs) mean there is no exact hedge, only a price band; costs and
  limits on rebalancing widen it.
- Indifference pricing: an unhedgeable risk is worth less to a more risk-averse holder; mean minus a variance penalty is
  the simple approximation. No Kelly or position sizing beyond this; no psychology.

## How it relates to our research
- Agrees with R102 / R103 on the mechanics: the chain prices a smile and a term structure (R102 3.4: puts dearer on 91%
  of NIFTY days; ATM IV 22.6 on expiry days vs 15.0 otherwise), and Black-Scholes at the leg's own IV is the right
  tool to reprice stale legs (R103 Amendment 2). The book's Derman-Kani and implied-tree methods would reprice far wings
  with the smile instead of a flat IV - a refinement of what R103 did.
- The book's "sell the rich option and hedge" (Ch. 5) is the delta-hedged version of what R103 tested unhedged
  (0 of 24 confirmed). R102's 81% VIX-above-realised (NIFTY) supports the premise; BANKNIFTY's realised above VIX on 60%
  of days warns against it there. Not tested hedged.
- Greeks: our GEX work (R28 / R36) uses closed-form gamma, which avoids the book's tree-gamma trap; GEX told size of the
  day but 0 of 10 arm filters passed.
- Implied distribution vs max pain: R16 rejected max pain as an expiry forecast; the book's butterfly-implied odds are a
  pricing quantity, untested by us, and should stay context.
- Nothing here bears on direction; it is consistent with R16 / R88 (size predictable, direction not) because pricing
  theory never needs a direction forecast.
- Costs: the book ignores costs except in App. B, where it shows they widen the fair band; our costs (~Rs 70 a trip incl.
  slippage; an ATM round trip about Rs 98 NIFTY / Rs 133 BANKNIFTY) are what any arbitrage rule from Method 2.1 must beat.

## What is new to us
- Butterfly-implied (risk-neutral) distribution of the expiry close from the chain (Method 12.1) - we have never computed
  it; it would give Jarvis "the market's odds" and give research a tail-premium measure.
- Smile-consistent repricing (implied volatility / implied binomial trees) for strikes outside ATM +-10, instead of one
  flat IV.
- Static no-arbitrage checks (parity, monotonicity, butterfly >= 0, call spread <= PV of strike gap) as an automatic
  stale-quote detector on the lake.
- Super- / sub-replication price bands (App. B) as model-free bounds.
- Mean-minus-variance (certainty-equivalent) scoring of an arm's trade stream (Ch. 14).
- Step-consistent numerical Greeks (Ch. 9 warning).

## Could not read
Nothing was unreadable in the prose. Many displayed formulas came through the text layer with broken layout (fractions
and sub/superscripts split across lines), and the figures (payoff diagrams, Fig. 1.1, 1.2, B.1) are blank in the text;
the surrounding text was enough to follow every method.

## R104 results (25 Oct 2026)

Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in `research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:

- `book-fmbinomial-01`: not testable (R104): needs simultaneous bid/ask
- `book-fmbinomial-02`: not testable (R104): needs simultaneous bid/ask
- `book-fmbinomial-03`: tested (R104): VOL: 424 trades, win 58%, net Rs 372,595 design, holdout -Rs 28,900 (fail)
