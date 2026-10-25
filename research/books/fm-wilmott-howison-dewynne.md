# The Mathematics of Financial Derivatives: A Student Introduction - Paul Wilmott, Sam Howison, Jeff Dewynne (1995)

Source: the-mathematics-of-financial-derivatives.txt, 339 PDF pages (preface pages ix-xiii, text pages 1-294, hints,
bibliography and index to page 317), read in full on 25 Oct 2026 (tesseract OCR of a scanned book; every chapter, the
hints, bibliography and index read; displayed formulas are garbled by the OCR, see "Could not read").
Book claims are the authors', not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

This is the short Oxford student textbook by the same first author as the three-volume "Paul Wilmott on Quantitative
Finance". Most of its practical material is covered at greater depth in research/books/fm-wilmott.md (vol-arb hedging,
discrete hedging error, Kelly, VaR, Leland and hedging bands, uncertain volatility, Monte Carlo); this write-up points
there instead of repeating it and adds what this book does differently. The trader's view of volatility (vol cones, VIX,
skew trading, dispersion) is in research/books/bennett-trading-volatility.md.

## What it is about
An applied mathematician's introduction to pricing derivatives with partial differential equations, written for students.
Part One builds the lognormal random walk, Ito's lemma, the Black-Scholes equation, its explicit formulas and American exercise.
Part Two is numerical: explicit, implicit and Crank-Nicolson finite differences, projected SOR for early exercise, binomial trees.
Part Three prices exotics in one framework (binaries, barriers, Asians, lookbacks, stop-loss and Russian options) plus transaction costs.
Part Four covers bond pricing with a random short rate (Vasicek, CIR, Hull-White), swaps, caps and convertible bonds.

## Methods, rules and setups

Chapter by chapter. Practical methods get a full block; pure theory gets a line or two. The book contains one explicit
trading rule (the implied-volatility convergence trade in section 3.10) and one exercise that reads as a setup (the
election straddle, Ch. 3 exercise 1); everything else is a pricing, hedging or numerical tool, so most blocks say what the
tool computes, its inputs and how we could use it.

### Part One: basic option theory (Ch. 1-7)

**Ch. 1 Options and markets.** Calls, puts, writers, expiry, strike; reading a 1993 LIFFE price table; bid-ask; margin;
American vs European; exotics in outline; forwards vs futures (daily settlement; the two prices coincide when rates are
predictable); continuous discounting. Gearing example: an XYZ call bought for 10p on a 250p strike makes +100% or -100%
where the share makes +8% or -8%. Rates matter little for short options: a move from 8% to 10% a year lowers a
nine-month option's value by about 2%. Options as insurance; market makers profit by selling slightly above fair value and
hedging the rest away.

**Ch. 2 Asset price random walks.** The efficient-market argument for randomness; returns, not price changes; the
lognormal walk with drift and volatility; typical stock and index volatility 0.05-0.4 a year; the model fits indices well
"on the whole" but real data has more large moves than it predicts (fat tails); a recipe to simulate daily paths;
Ito's lemma by Taylor series with the rule that a squared random increment equals dt; log price is Normal with mean
reduced by half the variance; delta-hedging as "elimination of randomness". Mostly theory.

#### 1. Historical volatility estimate (Ch. 2, technical point on parameter estimation)
- What it computes: annualised volatility from closing prices: the sample standard deviation (n-1 divisor) of the
  period returns, scaled by the square root of periods per year. The authors flag that window length and time variation
  matter and leave it there.
- Inputs: daily or intraday closes. Use for us: the realised-vol leg of any implied-vs-realised comparison; we already
  compute it (R102 used the annualised close-to-close SD of the next 21 days). Better estimators (Parkinson, Garman-Klass,
  EWMA) are in fm-wilmott.md block 11 and block 7.
- Signal source: underlying price/volume (a measurement, not a signal). Maps to NSE, MCX, crypto. Mechanical: yes.
- Our research: R16 and R88 show move SIZE is predictable from recent range, realised vol, VIX and IV (FINDINGS.md "Every
  indicator on the Indian indices": IC 0.2-0.37), so this estimator is one of our size inputs, not a direction tool.

**Ch. 3 The Black-Scholes model.** The key chapter. Arbitrage; specific vs market risk (variance as the usual but
symmetric risk measure); payoff diagrams for calls, puts, cash-or-nothing binaries, bull call spreads, combinations and
calendars; time value vs intrinsic value (a deep in-the-money European put can be worth less than its payoff); put-call
parity; the assumption list (lognormal walk, known rate and vol, no costs, no dividends, continuous trading, short selling
allowed); the delta-hedged portfolio leads to the Black-Scholes equation, in which the asset's drift does not appear,
"two people may differ in their estimates" of growth "yet still agree on the value of an option".

#### 2. Black-Scholes values, Greeks, parity and arbitrage bounds (Ch. 3.4-3.9, 5.4, hints to Ch. 3 and 5)
- What it computes: European call and put values, delta (call N(d1), put N(d1)-1), gamma, theta, vega, rho (formulas in
  the Ch. 5 hints); put-call parity; model-free bounds: a call is worth no more than the share and at least spot minus the
  discounted strike; a lower-strike call exceeds a higher-strike call by no more than the strike gap; a longer-dated call
  is worth at least a shorter one. Delta lies between 0 and 1 and becomes a step at expiry: a delta-hedged writer ends
  holding exactly one share or none, and if the index sits near the strike late on, the hedge flips between nearly 0 and
  nearly 1 many times, each flip costing money.
- Hedging in practice (3.9): parity is a one-off static hedge; delta hedging is dynamic and only instantaneously
  riskless. A writer who sells above fair value and delta-hedges with FUTURES (cheaper to trade) locks a profit "in
  theory", practical only at market-maker costs. Hedging gamma, vega or rho needs other options.
- Inputs: spot or future, strike, time, rate, dividend yield, vol. Use for us: the repricer R103 already uses for stale
  legs; the bounds as a data-quality filter on the lake (a chain row that breaks them is a stale or bad print).
- Signal source: volatility / options structure (a pricing tool). Maps to NSE index options (European, cash-settled), MCX
  (on futures: block 5), crypto options. Mechanical: yes.
- Our research: HUNT_R103_OPTIONS_SELLING.md: the six design passers were "wings priced at stale prices"; repricing with
  Black-Scholes at the last print's IV turned them negative (0 of 24 confirmed). Same tool as fm-wilmott.md block 1.

#### 3. Implied volatility, the smile, and the convergence trade (Ch. 3.10 and its technical point "Trading volatility")
- What it computes: the volatility that makes Black-Scholes match a market price (unique because value rises with vol);
  several expiries give a term structure; in practice implied vol differs by strike (the FT-SE example: deep
  in-the-money calls, i.e. low strikes, show higher IV than at-the-money; lopsided "smile", sometimes a "frown") and puts
  and calls give slightly different values. Historical vol is neither constant nor a reliable forecast of the vol over the
  option's life, which is why markets quote IV.
- The one trading rule in the book: compute IV for every option on one underlying and one expiry, BUY the option with the
  lowest IV and SELL the one with the highest, and hope the IVs converge.
  - Entry: as stated (no threshold, no hedge given). Stop: not stated. Target / exit: when the IVs "become more or less
    comparable"; no time limit stated. Filters: none. Market and timeframe: FT-SE index options, timeframe not stated.
  - The authors also say volatility is itself random (two-factor models), so a perfect hedge then needs other options.
- Signal source: option premium / volatility structure (the signal is read from the chain's own prices, not our default;
  it must be labelled and switched on only by Boss). Maps to NSE index options (one weekly or monthly expiry of NIFTY or
  BANKNIFTY), MCX options (thin chains), crypto options (Delta Exchange). Mechanical: partly (needs a rule for when IVs
  "converge" and a delta hedge; see rules file).
- Our research: R102 (DEEP_NIFTY_BANKNIFTY.md, option chain table): puts are dearer than calls on 91% of NIFTY days and
  90% of BANKNIFTY days, and NIFTY's 09:20 ATM IV medians 22.6 on expiry days vs 15.0 on other days (n 1,222 days), so the smile is
  persistent and structural, not a mispricing that "converges". IV1 (HUNT_IV1_OPTION_MARKET.md): skew, straddle, PCR and
  OI on their own lost on all five indices as direction signals. The convergence spread itself is not tested by us, and
  R103 showed wing prices in the lake are often stale (an apparent high- or low-IV wing is usually a stale print).

**Ch. 4 Partial differential equations.** The diffusion equation, parabolic equations, well-posed problems, delta and
Heaviside functions, why a backward equation must be solved from a final condition. Pure theory; one practical remark: a
zero price is an absorbing barrier no information crosses.

**Ch. 5 The Black-Scholes formulae.** Derives the formulas by turning the equation into the heat equation; only two
dimensionless numbers matter (rate over half the variance, and half the variance times the time to expiry). Any payoff is
valued by one integral of the payoff against the lognormal density. Maple's blackscholes routine is shown.

#### 4. Binary options, the expiry-delta problem, and the risk-neutral trap (Ch. 5.5-5.6, exercises 15-19)
- What it computes: a cash-or-nothing call is worth B times the discounted N(d2). Near expiry with spot close to the
  strike its delta spikes toward a spike-shaped function: the hedge swings from nothing to a huge position and back each
  time the strike is crossed, so the authors doubt the simple formula is even valid for discontinuous payoffs; the same
  effect, milder, hits a vanilla at-the-money option near expiry (footnote).
- Risk neutrality: value equals the discounted expected payoff with the drift replaced by the interest rate. The authors
  name two common errors: that the real drift equals the interest rate, and that "the delta of an option is the
  probability that it will expire in the money". Both are wrong; the real probability of finishing in the money needs the
  real drift.
- Static replication (exercise 18 and hint): any expiry payoff can be built from calls of all strikes with weights equal
  to the payoff's second derivative; the call price as a function of strike satisfies its own equation (exercise 19).
- Inputs: chain prices across strikes, spot, time. Use for us: (a) do not read delta or N(d2) as a forecast in Jarvis; (b)
  expiry-afternoon ATM positions are the hardest to hedge or to stop cleanly; (c) butterfly prices give the market's
  risk-neutral odds for the expiry close (already a card in research/books/fm-binomial-models.md).
- Signal source: volatility / options structure. Maps to NSE (weekly expiry afternoons), MCX, crypto daily expiries.
  Mechanical: yes (as measurements).
- Our research: R102's decay clock: the rolling ATM straddle is a median 84% of its 09:20 value at 10:30 and 22% at 15:00
  on expiry days (NIFTY, n 259 expiries), the price side of the same gamma cliff. R21 (FINDINGS.md "Catching expiry zero-to-hero
  moves") found nothing that passes.

**Ch. 6 Variations on Black-Scholes.**

#### 5. Dividends, forwards, futures and options on futures (Ch. 6.2-6.4)
- What it computes: an index is modelled with a continuous dividend yield (replace spot by spot discounted at the yield);
  a discrete dividend makes the share jump down by the dividend, the option value stays continuous along each path but
  its delta jumps, so a hedge must be adjusted across the date; a call with one proportional dividend equals a fraction of
  a call at a scaled-up strike. The forward price is spot carried at the interest rate less the dividend yield
  (cash-and-carry); storage costs (gold) act as a negative yield; a long forward equals a long call plus a short put at the
  same strike. Options on futures (Black-76): the call is the discounted (F N(d1) - E N(d2)); futures are "often more
  liquid" and cheaper to trade, and the future has the same volatility as spot. Parity on futures: call minus put equals
  the discounted (F - E).
- Inputs: spot, future, rate, yield, time. Use for us: (a) MCX options are written on the futures contract, so Black-76 is
  the right repricer; (b) NIFTY and BANKNIFTY futures carry fair value above spot by the rate less the index yield, and
  using the same-expiry future in place of spot removes the dividend and rate guesses from IV; (c) delta hedges with
  futures, not stock baskets.
- Signal source: volatility / options structure (pricing). Maps to NSE (index futures and options), MCX (Black-76), crypto
  (perpetual funding plays the role of carry). Mechanical: yes.
- Our research: H39 (HUNT_H39.md) found heavyweight futures returns, basis changes and OI tied to the index in the same
  minute (IC 0.6-0.9) but no lead an option buyer can use; MCX_OPTIONS.md found realised moves 0.73-0.93 of what MCX
  options priced. Basis as a signal: not tested by us beyond H39.

#### 6. Time-varying volatility and rates: use the average variance (Ch. 6.5, Ch. 5 exercise 5)
- What it computes: when vol and rate are known functions of time, the plain formulas hold with the rate replaced by its
  average over the option's remaining life and the variance by its average (equivalently, time measured in "volatility
  time", which runs slowly when vol is high).
- Inputs: a vol path (for example a higher vol on a known event day). Use for us: price a weekly NIFTY option that spans an
  RBI or Budget day as calm days plus one high-variance day; read the extra premium on a pre-event chain as the market's
  implied event move. The forward-vol idea is in fm-wilmott.md "What is new to us" and in the Bennett write-up.
- Signal source: volatility / options structure. Maps to NSE (event weeks), MCX (US data nights), crypto. Mechanical: yes.
- Our research: EVENT_SHIELD.md: BANKNIFTY's median day range was 1.44% against 0.98% on normal days (1.48x; 15 sessions)
  on RBI and Budget days, confirmed on the holdout (7 sessions). Event-implied variance: not tested by us.

**Ch. 7 American options.** Early exercise turns pricing into a free-boundary (obstacle) problem: the value must stay at
or above the payoff and meets it smoothly (value and delta continuous) at the optimal exercise price; a linear
complementarity form removes the need to track the boundary. Without dividends an American call is never exercised early;
with a dividend yield, just before expiry exercise pays if spot exceeds the rate times the strike divided by the yield.
American put example (six months, r 0.1, vol 0.4) lies above the European. Theory for us: NSE index options are European.

### Part Two: numerical methods (Ch. 8-10)

#### 7. Finite differences, projected SOR and binomial trees (Ch. 8-10)
- What it computes: option values on a grid after transforming to the heat equation. Explicit scheme: simple but stable
  only when the time step over the squared space step is at most one half (the book's put table at 0.52 gives nonsense
  values such as -17.4 and 625.0); doubling the space points costs eight times the work. Fully implicit (tridiagonal LU or
  SOR with an auto-tuned over-relaxation factor): stable for any step; even at a ratio of 5.0 accurate to 2 decimal places.
  Crank-Nicolson: second-order in time and stable. American and Bermudan exercise: projected SOR, applying the payoff
  floor inside each iteration; the naive "solve then clip to the payoff" is wrong for implicit schemes. At-the-money
  three-month put (E 10, r 0.1, vol 0.4): American 0.6913 vs European 0.6694. Binomial trees match the risk-neutral mean
  and variance (two common choices: up = 1/down, or p = 1/2); too large a step gives negative probabilities; the tree
  recombines so path-dependent payoffs cannot be priced on it; the binomial tree is an explicit finite-difference scheme.
  Pseudo-code for every method is given.
- Inputs: payoff, vol, rate, grid. Use for us: none needed for vanilla NSE options (closed form). Useful only if we price
  path-dependent stops (block 9-10) or American-style features; the stability rule and the "clip inside the iteration"
  warning are the parts worth remembering for any future grid code.
- Signal source: n/a (numerical tool). Maps to NSE, MCX, crypto. Mechanical: yes. Our research: not used by us; Monte
  Carlo and bootstrap tests in our studies (for example R19b's 2,000 random-exit tries) play the numerical role.

### Part Three: further option theory (Ch. 11-16)

**Ch. 11 Exotic and path-dependent options.** Classification: path-dependent (payoff depends on the price history) vs
exotic (not a vanilla); binaries, compounds (a call on a call is a call whose payoff is the underlying call's value less
the first strike), choosers, barriers (up/down, in/out, rebates; cheaper than vanillas), Asians (arithmetic or geometric,
weighted or not, continuous or discrete sampling), lookbacks. None trade on NSE; the ideas matter as models of our exits.

#### 8. Barriers, the method of images, and in + out = vanilla (Ch. 12)
- What it computes: a down-and-out call (dies if spot touches a lower barrier) has a closed form by reflecting the payoff
  across the barrier (the method of images); a knock-in plus the matching knock-out equals the vanilla, so the in-option is
  the vanilla minus the out-option; rebates, double knock-outs and perpetual barriers are exercises; the hint to exercise
  9 notes that a random walk that touches a level crosses it infinitely often, so "touched three times" needs a time
  window to mean anything.
- Use for us: a stop-loss on the underlying turns an option position into a knock-out; the knock-in part is the value the
  stop gives away (the rebound scenarios). This prices "what does my stop cost" and touch probabilities with the risk-neutral
  drift; the real-drift version and gap slippage at a barrier are in fm-wilmott.md Part Two. A stop that is touched
  repeatedly in a choppy market (the infinite-crossing point) is why tight underlying stops whipsaw.
- Signal source: underlying price/volume (the barrier sits on the index) with option pricing. Maps to NSE (stops on index
  level for option buys), MCX, crypto (every crypto rule needs a stop: this is how to price one). Mechanical: yes.
- Our research: stop placement on our arms is tested empirically, not priced: R65 (HUNT_R65_STEP_TRAIL.md) found a -5% stop
  "is hit by ordinary noise on about half of all trades". Pricing a stop as a barrier: not done by us.

**Ch. 13 A unifying framework for path-dependent options.** Add one state variable for the path quantity (running
integral, sum or maximum); continuous sampling adds one term to the equation; discrete sampling leaves Black-Scholes
between sampling dates with a jump condition across each date (like a dividend). Theory, but it is the recipe for valuing
any rule that depends on the running high, low or average.

#### 9. Asian (average) options (Ch. 14)
- What it computes: average-strike and average-rate options; the average-strike option reduces to two variables;
  put-call parity for the average strike; the geometric average-rate option has a closed form with an effective variance
  of one third of the variance (for a full averaging period), i.e. averaging cuts the effective vol to about 0.58 of the
  spot vol; arithmetic averages need numerics, Monte Carlo (Kemna-Vorst) or a lognormal approximation (Levy). Contracts
  average daily or weekly closes because tick data has bad prints and is open to dispute.
- Use for us: (a) entering or exiting in slices over a window has the risk of an average-rate position, smaller than a
  single fill; (b) the same "use closes, not ticks" lesson as our stale-print work. No Asian options trade on NSE or MCX.
- Signal source: underlying price/volume (the average) with option pricing. Maps to NSE, MCX, crypto as an execution idea.
  Mechanical: yes. Our research: HUNT_LAKE_STALE_PRINTS.md (bad prints in the lake; no verdict changed). Sliced entries:
  not tested by us.

#### 10. Lookbacks, the Russian option and the stop-loss option (Ch. 15)
- What it computes: a lookback put pays the running maximum minus spot ("buy at the low, sell at the high"), so it is
  expensive; with a continuously sampled maximum it is almost surely exercised; discrete sampling (most commercial
  contracts) makes it cheaper, and fewer sampling dates cheaper still (European one-year at-the-money values per unit of
  maximum: 0.089, 0.079, 0.067 for 12, 6 and 3 sampling dates, vs 0.038 for a vanilla put; r 0.1, vol 0.2). The Russian
  option (perpetual, pays the running maximum when the holder chooses) has a solution only with a dividend yield.
- The stop-loss option (section 15.6.2): a perpetual contract that pays the asset price when it falls to a set fraction
  of its running maximum, i.e. a trailing stop written as a contract; the authors pitch it as a way "to lock in a good
  proportion of a profit" without guessing the top. Their result: with no dividend yield it is worth exactly the asset
  price, whatever the trail fraction. In risk-neutral terms a trailing stop by itself adds no value; any edge must come
  from a real drift or a real pattern in the path.
- Use for us: the theory behind why our trails and locks keep failing against plain exits; a trail only helps if the
  underlying's real path after a peak is predictable, which our direction tests say it is not.
- Signal source: underlying price/volume (running maximum) or option premium for premium-based trails (label it). Maps to
  NSE (trails on paper arms), MCX, crypto (CLAUDE.md requires a profit lock on every crypto trade; this says choose it for
  risk control, not expected return). Mechanical: yes.
- Our research: HUNT_R19B_LIQUIDITY_TRAIL.md: Boss's Rs 500 give-back trail did worse than random early exits in 97%
  (15m) and 99.8% (5m) of 2,000 tries and cut 12 of the 13 big winners; R65: the step trail's timing "is no better than
  selling the same number of trades at random minutes"; R19, R19c, R100 (FINDINGS.md): no lock or give-back protection
  passed (R100: 0 of 15). Consistent with the book's zero-value result.

#### 11. Transaction costs: Leland's modified volatility (Ch. 16)
- What it computes: hedge at fixed intervals with costs proportional to the value traded; the expected cost per interval
  is proportional to the absolute gamma, so the pricing equation becomes non-linear. A long vanilla is worth Black-Scholes
  at a LOWER variance, a short one at a HIGHER variance; there is no single price, it depends on the hedger's cost. The key
  number is cost divided by (vol times the square root of the rehedge interval): well above 1 means rehedging too often
  (costs swamp), well below 1 means too seldom (risk); the book's examples use 0.25. Portfolios: hedge the NET book; a
  long and a short of the same call hedged separately pay costs twice for a zero position. With large enough costs the
  long-option equation becomes ill-posed; adding a fixed fee per trade can even make prices negative (exercises).
- Use for us: same as fm-wilmott.md block 10 (which adds hedging bands and the real-data comparison); this book's extra
  point is the dimensionless cost number, a quick check of how often a futures hedge on MCX or crypto should rebalance.
- Signal source: n/a (execution). Maps to NSE (coarse futures lots: DEEP_OPTIONS_SELLING.md "Delta hedging" explains a
  one-lot NIFTY seller cannot hedge in 0.2-delta steps), MCX, crypto. Mechanical: yes.
- Our research: costs in research/knowledge/baselines.yaml (ATM round trip about Rs 98 NIFTY, Rs 133 BANKNIFTY); R89
  (FINDINGS.md "buy a straddle when a big candle is expected"): gross P&L about zero, costs decide, 0 of 18 profitable.
  Leland pricing: not tested by us.

### Part Four: interest rate products (Ch. 17-18)

**Ch. 17 Interest rate derivatives.** Bond pricing with known rates; discrete coupons as jumps; the yield curve (rising,
falling, humped) and the term structure; a random short rate needs hedging with a second bond, which brings in the market
price of risk (for a traded asset it is the excess return over the rate per unit of vol and drops out of Black-Scholes);
the general affine model covering Vasicek, Cox-Ingersoll-Ross and Hull-White, with closed-form bond prices; fitting one
time-dependent parameter to today's curve exactly, with the warning that refitted parameters drift because the model is
chosen for tractability, not economics; bond options, swaps, caps, floors, swaptions. For us: only the risk-free rate in
our repricer (a T-bill rate is enough for weekly options, per the Ch. 1 point that rates barely move short options).

**Ch. 18 Convertible bonds.** A bond convertible into shares is an American-style problem (value at least the conversion
value); issuer call and holder put features add upper and lower constraints; a two-factor version with random rates and
correlated noise; dilution when new shares are issued. Not relevant to NSE index options.

**Hints, bibliography, index (p. 295-317).** The hints give the Greek formulas, the static-replication density (block 4),
the American-call no-early-exercise proof, and model answers; read for completeness.

## Performance claims
- None about trading profits. The numbers are worked examples: the gearing example (+100% / -100% on the option vs +8% on
  the share); the rate sensitivity (about 2% for a nine-month option); numerical accuracy tables (explicit scheme unstable
  above one half; implicit accurate to 2 decimal places at a step ratio of 5.0); American vs European put (0.6913 vs
  0.6694); lookback values by sampling frequency. The convergence trade in section 3.10 is offered as a hope ("the hope is
  then that the prices move"), with no evidence. No backtests, no samples, no out-of-sample tests anywhere.

## Risk, sizing and psychology
- Risk is defined as variance, with the caveat that it weighs gains and losses alike; specific risk can be diversified,
  market risk only hedged.
- Option writers face losses unlimited (calls) or large (puts) for a capped premium; selling above fair value is safe only
  with continuous, cheap hedging, which only market makers have.
- Hedging is never perfect in practice: discrete rebalancing, costs, near-expiry delta jumps, discontinuous payoffs and
  random volatility all break the riskless portfolio.
- Nothing on position sizing, Kelly or psychology (for Kelly see fm-wilmott.md block 4 and HUNT_H15.md).

## How it relates to our research
- Agrees: the option value carries no view on direction (drift drops out), and our tests found direction a coin flip on
  NIFTY and BANKNIFTY intraday (R16, R30, R47, R48, R88: FINDINGS.md); the book's warning that delta is not a probability
  matches our rule of using size forecasts only for risk.
- Agrees: the smile and term structure are real and persistent (R102: puts dearer on 91% of NIFTY days; expiry-day ATM IV
  22.6 vs 15.0), which argues against the book's own "IVs will converge" trade on NSE.
- Agrees: a trailing stop has no value in itself (Ch. 15 stop-loss option); our trail and lock studies (R19, R19b, R19c,
  R65, R100) all failed against plain exits.
- Agrees: costs decide hedged and straddle trades (Ch. 16; R89, R103, baselines.yaml).
- Already used: Black-Scholes repricing of stale legs (R103, DEEP_OPTIONS_SELLING.md).
- Not tested by us: the IV convergence spread; Leland-adjusted pricing of our futures hedges; pricing stops as barriers;
  event-day implied variance; static replication of payoffs from the chain.

## What is new to us
1. The stop-loss option result: a trailing stop, priced, is worth exactly the asset when there is no dividend; this is a
   clean theoretical reason to expect trails to add nothing on their own (Ch. 15).
2. Barrier view of stops: in + out = vanilla, so the cost of a stop on the index can be priced as the matching knock-in
   (Ch. 12), and the infinite-crossing property explains whipsaw at tight stops.
3. Average variance over the remaining life for event weeks (Ch. 6.5): separate the event-day variance a chain implies.
4. The arbitrage bounds of Ch. 3 exercise 7 and Black-76 parity as a cheap data-quality filter for the lake.
5. Leland's dimensionless cost number as a rule of thumb for how often to rebalance a futures hedge (Ch. 16).

## Could not read
- Formulas: the OCR garbles nearly every displayed equation (subscripts, fractions, integrals, Greek letters); derivations
  were followed from the prose, and formulas quoted here are standard ones, not copied.
- Figures: the 1993 Financial Times LIFFE table (Fig. 1.1) is illegible; most plots (option values, deltas, implied-vol
  smile Fig. 3.9, free boundaries, transaction-cost spreads Fig. 16.1-16.4, convertible bonds Fig. 18.1-18.3) survive only
  as axis labels; the numerical tables (Fig. 8.6, 8.12, 8.15, 9.4, 10.5-10.11, 15.3-15.4) were readable.
- Duplicate scans: PDF pages 150, 171, 172, 174, 224, 311 and 312 repeat neighbouring pages; nothing appears to be missing.

## R104 results (25 Oct 2026)

Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in `research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:

- `book-fmwhd-01`: tested (R104): VOL: 1145 trades, win 28%, net -Rs 1,542,428 design, holdout -Rs 391,601 (fail)
- `book-fmwhd-02`: tested (R104): VOL: 5 trades, win 40%, net -Rs 7,709 design, holdout Rs 0 (fail)
