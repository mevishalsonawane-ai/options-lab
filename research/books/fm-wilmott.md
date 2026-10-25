# Paul Wilmott on Quantitative Finance (2nd edition, 3 volumes) - Paul Wilmott (2006)

Source: paul-wilmott-on-quantitative-finance.txt, 1,401 pages, read in full on 25 Oct 2026 (pdftotext text layer; all
prose of chapters 1-83, Appendix A and a skim of the bibliography read; formulas, most figures and many tables are
flattened or missing in the text layer, see "Could not read").
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A three-volume textbook on pricing, hedging and risk-managing derivatives, written by a practitioner-academic who mocks over-fitted models.
Volume 1 builds Black-Scholes, the Greeks, delta hedging, Kelly money management, VaR, exotics and interest-rate products.
Volume 2 covers credit, CrashMetrics, famous derivatives disasters, and every broken Black-Scholes assumption one chapter at a time.
The practical core: hedging with the wrong vol, discrete hedging, transaction costs, uncertain vol, crashes, speculating with options.
Volume 3 is numerics (finite differences, Monte Carlo, quadrature) with Visual Basic code; Appendix A is the elementary maths.

## Methods, rules and setups

Chapter by chapter. Practical methods get a full block; pure theory gets a line or two. Almost nothing in the book is a
trading rule with entry, stop and exit; the practical methods are pricing, hedging, sizing and risk tools, so most blocks
say what the tool computes, its inputs and how we could use it. For the trader's view of volatility (vol cones, VIX,
variance swaps, dispersion, skew trading) see research/books/bennett-trading-volatility.md; this write-up does not repeat it.

### Part One: mathematical and financial foundations (Ch. 1-21)

**Ch. 1 Products and markets.** Compounding, equities, commodities, FX, indices, bonds; the forward price is spot grown at
the interest rate (cash-and-carry). Practical: index futures fair value = spot carried at the rate minus dividends; the
gap is the basis. Not tested by us as a signal (H39 tested heavyweight stock options leading the index: NO).

**Ch. 2 Derivatives.** Calls, puts, payoff vs profit, put-call parity (model-free), spreads, straddles and strangles as
the simplest volatility trades (bought before news or at a level that should either break or hold; discretionary), butterflies, calendars, margin. Shows
leverage: an OTM call can gain far more than the stock, and usually expires worthless.

**Ch. 3 The random behaviour of assets.** Technical analysis is called mostly "bunk" by the academic evidence; daily
returns are roughly Normal with a drift that is invisible at short horizons (example stock: daily mean 0.0029 against SD
0.0245); mean scales with time, SD with its square root. A moving-window vol shows a false plateau after one big move.

**Ch. 4 Elementary stochastic calculus.** Brownian motion as scaled coin tosses, Ito's lemma via Taylor series, the Ito
integral is non-anticipating (no look-ahead), common SDE models. Theory.

**Ch. 5 Black-Scholes.** Hold an option, short delta of the asset, the portfolio is riskless, so it earns the risk-free
rate; the real drift drops out. Lists every assumption (continuous hedging, no costs, known vol) and where each is relaxed
later. Black-76 for options on futures (MCX).

**Ch. 6 Partial differential equations.** The Black-Scholes equation as a diffusion; final condition = payoff. Theory.

#### 1. Black-Scholes values, Greeks and a quick ATM rule (Ch. 7)
- What it computes: call/put values, delta = N(d1), gamma, theta, vega, rho; implied vol by Newton-Raphson (divide the
  price error by vega). Quick rule: an ATM-forward call or put is worth about 0.4 x spot x vol x sqrt(time), so an ATM
  straddle is about 0.8 x spot x vol x sqrt(time); invert it for a fast implied vol from the straddle price.
- Key teaching: a delta-hedged long option makes money on big moves and loses on small ones; under Normal returns it loses
  on about 68% of rehedges and wins on 32%, breaking even on average at fair vol. Vega means little when gamma changes sign
  (spreads, binaries). Delta of a binary explodes at the strike near expiry. Hedging taxonomy: model-independent, delta,
  gamma, vega, static, margin and crash hedging.
- Inputs: spot or future, strike, time, rate, dividend yield, vol. Use for us: the repricer R103 already uses; the 0.8 rule
  as a sanity check on the 09:20 straddle (R102: a median 0.96% of spot on NIFTY, 1,222 days).
- Signal source: volatility / options structure. Maps to NSE index options (European, cash-settled), MCX (Black-76),
  crypto options. Mechanical: yes. Our research: used as a tool in R103; MCX_OPTIONS.md measured delta-hedged buyer P&L.

**Ch. 8 Simple generalisations.** Discrete dividends (value continuous, delta jumps), commodities (consumption goods can
trade below carry: convenience yield; gold behaves like an investment asset), stock-borrow cost makes pricing non-linear,
time-dependent vol enters through total variance; the log contract (forerunner of variance swaps). MCX crude and natural
gas futures can sit in backwardation; gold mostly follows carry.

**Ch. 9 Early exercise.** American options as free-boundary problems, smooth pasting, never exercise an American call early
without dividends, one-touch and instalment options. NSE index options are European: little use.

**Ch. 10 Probability density functions and first-exit times.** Forward and backward Kolmogorov equations; the probability
of leaving a range before a date and the expected exit time. Myth corrected: delta is NOT the probability of expiring in
the money; the real probability uses the real drift and d2.

#### 2. Touch and expiry probabilities with the real drift (Ch. 10)
- What it computes: probability that the index touches a level (stop or target) before a time, or finishes beyond a
  strike, from vol and the real drift (for intraday NIFTY the drift is about zero).
- Inputs: spot, level, time, realised or implied vol. Use for us: Jarvis's "chance of hitting the stop/target" estimate,
  instead of quoting delta as a probability.
- Signal source: underlying price (vol as input). Maps to NSE, MCX, crypto. Mechanical: yes. Our research: not tested by
  us as a calibrated forecast; R16 found the size of the next 30/60 minutes predictable, which would feed the vol input.

**Ch. 11 Multi-asset options.** Correlated walks, baskets, exchange options, quantos. Correlations are notoriously unstable,
more than vols; cointegration is suggested as a sturdier link. Finite differences up to 3-4 assets, Monte Carlo beyond.

#### 3. Hedging with implied vs actual volatility: the vol-arb P&L (Ch. 12, the key practical chapter)
- What it computes: the P&L of buying (or selling) an option whose implied vol differs from the vol you forecast, then
  delta hedging. Two choices: hedge with the ACTUAL (forecast) vol's delta and the final profit is locked at the difference
  between the two Black-Scholes values, but the mark-to-market path is noisy; hedge with the IMPLIED vol's delta and each
  day's profit is steady and positive (proportional to the difference of the two variances times the option's gamma), but
  the total depends on the path: largest if the index stays near the strike, small if it trends away. Any hedging vol
  between implied and actual cannot lose in the model; hedging below implied can.
- Evidence: SPX realised vol averages about 15% against VIX about 20%, realised being more dispersed; an unhedged straddle
  bet on vol has a standard deviation of profit as big as its expected profit. A model portfolio optimiser with a negative
  skew picked a risk reversal (sell OTM puts, buy OTM calls). VIX daily changes depend on day of week and days to expiry.
- Inputs: chain IV, a realised-vol forecast, futures for the hedge. Use for us: the honest frame for any short-premium idea:
  the edge is implied minus realised variance weighted by gamma along the path, not "theta".
- Signal source: volatility / options structure; hedge on the underlying future. Maps to NSE (NIFTY monthly; 1 lot cannot
  be hedged finely: one NIFTY future lot = one option lot, DEEP_OPTIONS_SELLING.md "Delta hedging"), MCX (futures hedge
  available), crypto (perpetuals allow fine hedging). Mechanical: yes (see rules file).
- Our research: R102 (FINDINGS.md "Deep learn NIFTY and BANKNIFTY"): VIX over-states NIFTY's next 21 days on 81% of days
  (median ratio 1.28, 4,507 days) but under-states BANKNIFTY's on 60%. R88: rich implied-vs-realised forecasts a smaller
  next candle (0.85-0.9x). MCX_OPTIONS.md: buyers lose on average, realised moves 0.73-0.93 of what options priced. A
  delta-hedged NIFTY short straddle has not been backtested by us (R103 tested wings and stops only).

**Ch. 13-14 Fixed income basics and swaps.** Bonds, yields, duration, convexity, forward rates, swaps as bond pairs.
Lesson: never use a model where a static model-free hedge exists. Not relevant to index options.

**Ch. 15 The binomial model.** One-step hedge, risk-neutral probability as a construct; the author calls trees intuition
only and prefers finite differences. Perfect hedging fails once three outcomes are possible.

**Ch. 16 How accurate is the Normal approximation?** S&P 500 daily returns have fat tails and a high peak. The 1987 crash
(-20.5% in a day) happened about once in 25 years of data (empirical probability 0.000165) while the Normal gives
1.8e-79. Author's practice: use Normal most of the time but buy OTM puts as tail cover and diversify.

#### 4. Kelly criterion and money management (Ch. 17, also Ch. 66)
- What it computes: the fraction of capital to stake each bet to maximise long-run growth: f* = edge / variance (mu /
  sigma^2), growth about mu^2 / (2 sigma^2) per bet. Staking twice Kelly gives zero expected growth, more is negative; half
  Kelly halves volatility and costs only 25% of the growth, so the author recommends it when the edge is uncertain. Total
  growth scales with the NUMBER of bets: a frequent small edge can beat a rare big one. Ch. 66 shows Kelly is the
  log-utility investor's constant risky fraction (mu - r) / sigma^2, with an N-asset version using the covariance matrix.
- Also: Thorp's blackjack card counting and roulette computer; horse racing (bookmaker odds reflect money, not
  probabilities; an arbitrage exists only when the inverse odds sum below one).
- Inputs: per-trade mean and variance of return (or win rate and payoff ratio). Use for us: an upper bound on lots per arm;
  half or quarter Kelly at most because our edge estimates are noisy.
- Signal source: n/a (sizing). Maps to all markets. Mechanical: yes.
- Our research: HUNT_H15.md (half-Kelly on Liquidity 15+5): best 12-month median capital but P(drawdown >= 50%) 14% and
  P(drawdown >= 30%) 63%; quarter Kelly cut the 50% drawdown odds to 1%. The Kelly fraction was unstable: BANKNIFTY f* 0.48
  and 0.14 on other windows against 1.29 in the fitted one. Boss's frame since is fixed lots (HUNT_FINAL.md).

**Ch. 18 Portfolio management.** Markowitz frontier (only the vols are reliable inputs), CAPM and beta, safety-first
choice, cointegration for index tracking and pairs trading (needs fewer assumptions than correlation), Sharpe and Treynor.
Pairs/cointegration: our nearest is R72 cross-index (HUNT_R72_CROSS_INDEX.md): only "Breadth Donchian" passed, a filter on
Donchian rather than a pairs trade; a true NIFTY-BANKNIFTY spread trade is not tested by us.

#### 5. Value at Risk: delta, delta-gamma, bootstrap, EVT (Ch. 19)
- What it computes: the loss not exceeded with a set confidence over a horizon (99% = 2.33 SD under Normal). For options:
  delta-VaR (delta from implied vol, moves from actual vol); delta-gamma shows that short gamma turns the distribution's
  long tail against you, so delta-VaR understates short-option risk; full revaluation by simulation is best. Historical
  bootstrap resamples whole days (keeps fat tails and cross-asset links, loses autocorrelation). Extreme value theory fitted
  to annual maxima of S&P 500 falls before Oct 1987 (largest fall in the data 6.7%) put the 50-year fall at 24%; the next
  day fell 20.4%. VaR is not coherent (it can punish diversification).
- Inputs: positions, Greeks, return history. Use for us: an open-book worst-day number for paper arms; bootstrap whole days.
- Signal source: n/a (risk). Maps to all. Mechanical: yes. Our research: block bootstraps are already standard in our
  hunts (e.g. HUNT_H15.md ruin and drawdown odds); no options-book VaR exists in the app (not tested by us).

#### 6. Technical analysis survey (Ch. 20)
- What the book describes (sceptically): support/resistance, trendlines, moving averages and crossovers (the one area where he
  grants a little evidence), RSI read as overbought above 70 / oversold below 30, a stochastic-style oscillator, Bollinger
  Bands, head and shoulders, candlesticks, point and figure, Elliott/Fibonacci and Gann (dismissed). Models of trend
  followers vs noise traders: patterns are created by trend followers and pay mainly other trend followers; crowding hurts.
  He found direction forecasts worked only for seasonal assets.
- Entry/stop/exit: not stated (definitions only). Signal source: underlying price. Mechanical: indicator definitions only.
- Our research: R29 EMA ribbons (195 tests) no pattern passes; R95 CCI 0 of 288 as given; R16/R88 direction not predictable.

**Ch. 21 A trading game.** Classroom dice market to teach bid/offer, arbitrage and margin. No trading content.

### Part Two: exotic contracts (Ch. 22-29)
**Ch. 22-26** classify exotics (time dependence, path dependence, dimension, order, embedded decisions) and price barriers,
Asians and lookbacks with state variables and jump conditions. Practical points: barrier hit probabilities need the real
drift; slippage at a barrier is handled by shifting the barrier (the same idea applies to our stops gapping through);
contracts whose gamma changes sign can show near-zero vega yet be very vol-exposed. **Ch. 27** passport options: limiting
trades to 3-4 keeps most of the value of unlimited trading. **Ch. 28** forward-starts, variance swaps (replicated by a strip
of options weighted by one over strike squared, the basis of VIX-style indices), correlation swaps, dispersion, cliquets,
Parisians. **Ch. 29** term sheets: payoffs on averages are model-robust, payoffs on differences are model-fragile; cut
parameters (garbage in, garbage out with many correlations). None trade on NSE; dispersion is in the Bennett write-up.

### Part Three: fixed income (Ch. 30-38)
One-factor spot-rate models, the market price of risk (which he says hides problems), yield-curve fitting (refitting a
few weeks later gives a different function, so the model is wrong; a calibrated parameter that changes daily signals
misspecification), bond options, caps, swaptions, convertible bonds (hedge funds' delta hedging of long convertibles
damps the stock's vol), mortgage-backed securities, two-factor models, an empirical method (Ch. 36: estimate the vol shape
and drift from data by bucketing squared changes and fitting the steady-state density), HJM/BGM and principal components of
curve moves (level, slope, curvature). For us: only the model-risk lessons and the PCA idea (a NIFTY weekly/monthly IV
term-structure PCA is not tested by us).

### Part Four: credit risk (Ch. 39-42)
Firm-value models, hazard rates (implied default intensity from Brady bonds), rating-transition Markov chains (a generator
matrix converts yearly transition probabilities to any horizon), credit derivatives, copulas (the Gaussian copula has no
tail dependence; one correlation parameter for a whole tranche oversimplifies), RiskMetrics and CreditMetrics.

#### 7. EWMA volatility (RiskMetrics) (Ch. 42, also Ch. 49)
- What it computes: today's variance = lambda x yesterday's variance + (1 - lambda) x today's squared return; lambda 0.94
  for a 1-day horizon, 0.97 for a month. Avoids the plateau of equal-weight windows. Non-synchronous closes (time zones)
  kill measured correlations.
- Inputs: daily returns. Use for us: a realised-vol input for stops, sizes and the Ch. 12 comparison; Ch. 49 adds GARCH,
  whose forecast decays back to a long-run level.
- Signal source: underlying price. Maps to NSE, MCX, crypto. Mechanical: yes. Our research: R16/R88 show "last size +
  recent volatility" forecasts the next candle's size; EWMA itself is not tested by us as a separate estimator.

### Part Five: advanced topics, models and model risk (Ch. 43-75)

#### 8. CrashMetrics, the Platinum hedge and the crash coefficient (Ch. 43)
- What it computes: the worst-case loss of the whole book over a bounded index move (say +-x%), assuming everything moves
  together in a crash (correlations go to one), by delta-gamma or full revaluation. The Platinum hedge picks static option
  quantities that maximise that worst case net of bid-offer. Multi-asset books use a crash coefficient: each asset's
  move on the index's 20-40 most extreme days (fitted through zero), said to be more stable than beta. Margin hedging:
  Metallgesellschaft was delta-hedged but died of margin calls. After a crash implied vol jumps, spreads widen, and about
  20% of the sudden loss is typically recovered soon after.
- Inputs: positions, full repricer, a gap size, IV jump. Use for us: a pre-trade and overnight check on any short-premium
  or MCX overnight book: "if NIFTY gaps 3% (or BANKNIFTY 5%) with IV +5 points, what do we lose?", compared with the
  -Rs 40k / -Rs 13k account stops. BANKNIFTY's crash coefficient against NIFTY could size cross-index exposure.
- Signal source: n/a (risk). Maps to NSE, MCX, crypto. Mechanical: yes. Our research: DEEP_OPTIONS_SELLING.md section 5
  (NIFTY worst day -12.98%, BANKNIFTY -16.73%, kurtosis 14 and 8.5); the naked 09:20 BANKNIFTY short straddle's worst day was
  -Rs 65,483. No worst-case optimiser has been built (not tested by us).

**Ch. 44 Derivatives disasters.** Orange County (leveraged inverse floaters, -$1.6bn), Procter & Gamble (a simple
historical scenario gave about 14% odds of losses starting and 3% of large losses: expected -$8.7m vs the hoped +$7.5m),
Metallgesellschaft (basis and funding), Gibson Greetings, Barings (Leeson sold Nikkei straddles at 18500-20000 in
Nov-Dec 1994; the Kobe quake; $1.3bn), LTCM ($4.8bn levered to about $100bn, roughly twice Kelly; lost $550m on 21 Aug
1998; all trades were one bet). Themes: over-gearing, margin, misunderstood risk. Lesson for us: a short straddle with no
worst-case limit is the Barings trade.

**Ch. 45 Financial modelling (cynic's rules).** Simple robust models beat clever ones; add complexity one step at a time;
distrust parameter-rich fits that cannot be proven wrong; test on real data.

**Ch. 46 Defects in Black-Scholes.** Map of the following chapters; non-linear models make value depend on the hedge.

#### 9. Discrete hedging error (Ch. 47)
- What it computes: the P&L noise of hedging at intervals instead of continuously: each interval's error is proportional
  to gamma times (squared move minus its expected value), a skewed chi-square; mean zero, total SD shrinks only with the
  square root of the interval (four times the rehedges to halve it), and it is largest when the index sits near the strike.
  Empirically (Dow 1977-96) 78% of days moved less than one SD and 22% more (Normal: 68/32), so a long-gamma hedger loses
  small on about four days in five and is repaid by rare big days; real hedging errors are larger than theory for every
  asset (Ajinomoto factor 1.9). Theta is not the expected P&L of a fairly priced, hedged option.
- Use for us: explains why option buyers bleed on quiet days and why short-gamma sellers feel "safe" until the jump; expiry
  ATM gamma makes hedging and pins chaotic.
- Signal source: volatility / options structure. Maps to NSE, MCX, crypto. Mechanical: yes (as a measurement).
- Our research: MCX_OPTIONS.md measured delta-hedged long-option P&L: -2% to -4.5% of premium per hour on expiry day, about
  zero in morning, 11-20-day buckets. R102's decay clock (84% of the 09:20 straddle left at 10:30, 22% at 15:00) is the
  seller's side of the same thing.

#### 10. Transaction costs and hedging bands (Ch. 48)
- What it computes: Leland: with proportional costs and a fixed rehedge interval, a long option is worth Black-Scholes at a
  LOWER vol and a short one at a HIGHER vol; the adjustment grows with cost over vol times the square root of the interval.
  Non-linear portfolio models: costs depend on the whole book's gamma, so adding opposite gamma lowers costs. Utility
  (Hodges-Neuberger, Whalley-Wilmott): do not trade while delta is inside a band around the Black-Scholes delta; trade
  only to the band edge; band width grows with the cube root of cost and gamma squared.
- Evidence: simulations (ATM 1-year call, 20% vol): Leland was best at rehedging every 4 days; at the 95th percentile of
  cost the delta-tolerance band priced 15.15 and the utility band 15.03. On real data (about 40 stocks, 1985-96) the
  delta-tolerance band won about 70% of paths, utility 23%, fixed-interval Leland 7%.
- Inputs: cost per unit traded, vol, gamma. Use for us: any futures hedge of an option position (MCX, crypto, a NIFTY
  multi-lot book) should rebalance on a delta band, not on the clock; the vol drag from costs is the hurdle a vol view
  must clear.
- Signal source: n/a (execution). Maps to NSE (futures lot size is coarse), MCX, crypto. Mechanical: yes. Our research:
  costs in research/knowledge/baselines.yaml (ATM round trip about Rs 98 NIFTY, Rs 133 BANKNIFTY); hedging bands not tested
  by us.

#### 11. Volatility estimators and the implied-vol view (Ch. 49)
- What it computes: close-to-close, EWMA, GARCH(1,1); range estimators Parkinson (high-low, about 5x as efficient as
  close-to-close), Garman-Klass (OHLC, about 7.4x), Rogers-Satchell (drift-free). Skew read as fear (OTM puts dear) and
  greed (OTM calls cheap); straddle = vol-level trade, risk reversal = skew trade, butterfly = smile trade. The author doubts
  that implied vol forecasts realised vol well, and calls local-vol calibration an ill-posed exercise that gives a new
  surface each week.
- Use for us: Parkinson/Garman-Klass on 1-minute or daily bars for the realised side (overnight gaps make them read low).
- Signal source: underlying price. Maps to all. Mechanical: yes. Our research: R102 (DEEP_NIFTY_BANKNIFTY.md 2.2) computes
  close-to-close and Parkinson; Yang-Zhang is in the Bennett write-up.

**Ch. 50 Deterministic vol surfaces.** Implied vol includes event premium (an election example) and supply/demand;
forward vol between two expiries comes from total variance (example 1m 30%, 2m 25%, 3m 26%); the risk-neutral density is
the second strike-derivative of call prices; local vol has little forecasting value and over-fitting destroys information.
Use for us: forward vol between NIFTY weekly and monthly, event premium before RBI/Budget (not tested by us); the density
needs clean wing prices, which our lake lacks (R103's stale-wing lesson).

**Ch. 51 Stochastic volatility.** Market price of vol risk; spot-vol correlation drives the skew; Heston's parameters are
unstable when calibrated; a range-based EGARCH limit shows two vol speeds (a fast part reverting in about a week around a
slow level reverting over about four months). Forecast variance, not vol (averaging vol understates option value).

#### 12. Uncertain volatility: worst-case pricing with a vol band (Ch. 52, Ch. 56, Ch. 60)
- What it computes: give vol a range instead of a number; the worst-case value uses the low vol where the book's gamma is
  positive and the high vol where it is negative (a non-linear equation, solved on a grid). For a single option it is
  Black-Scholes at one end; for spreads and sign-changing-gamma books it can be far outside both ends. Example: a 6-month ATM
  call with vol 20-30% has a spread of 6.85 to 9.85 alone; hedging statically with two traded calls tightened it to
  7.36-8.74 (Ch. 60, which also optimises hedge quantities net of bid-ask and finds arbitrage when a traded vol falls
  outside your band). Ch. 56 cliquet: constant vols 22-27% moved the price by 0.75%, the uncertain band by 10.5%: the true
  vol sensitivity was 14x what vega showed.
- Inputs: positions, a vol band (e.g. VIX-implied band over the trade), bid-ask of hedges. Use for us: iron flies, condors
  and strangle-plus-wings (R103 structures) have sign-changing gamma, so vega understates their risk; a worst-case price
  with a vol band is the right sizing input. Choosing wings by maximising worst-case value net of spread is a concrete
  alternative to fixed-width wings.
- Signal source: volatility / options structure. Maps to NSE, MCX, crypto. Mechanical: yes. Our research: not tested by us.

**Ch. 53 Empirical analysis of volatility.** Builds a stochastic-vol model from data (DJIA 30-day vol): vol-of-vol from
bucketed squared changes on a log-log plot, drift from the steady-state density; out-of-sample vol ranges matched realised
well on about 150 series. Use: probability that India VIX or NIFTY realised vol stays in a band over an option's life (not
tested by us).

**Ch. 54 Stochastic vol and mean-variance pricing.** Track the mean and variance of a hedged P&L; price at mean plus a
multiple of SD; a short up-and-out call's "safe" price fell from 1.6836 unhedged to 1.3752 with an optimal static vega
hedge. Being too greedy (a high multiple) means fewer trades.

**Ch. 55 Asymptotics of stochastic vol.** Fast mean-reverting vol gives a smile polynomial in moneyness, skew set by
correlation. Theory.

**Ch. 57 Jump diffusion.** Fat tails look exponential; Merton jumps; jump risk cannot be delta-hedged, only diversified or
hedged with other options; a vol jump that decays is realistic after shocks. NIFTY overnight gaps are the jump term.

**Ch. 58 Crash modelling (worst case).** No crash probability, only a maximum size. A delta-hedged long-gamma book is helped
by a crash; short gamma is hurt. Example short-gamma book, 15% crash: worst-case value 21.2 vs Black-Scholes 30.5 (crash
VaR 9.3); buying 3.5 of the 90 calls cut it to 5.7: positive gamma that offsets the crash is worth paying above model value.
Extensions: several crashes, minimum time between them, de-pegging FX.

#### 13. Speculating with options: real drift, risk-reward and when to sell (Ch. 59)
- What it computes: an option's value to a speculator is the present value of its payoff under the REAL drift (and its
  SD as risk), compared with its market price. With a bullish drift, calls show rising reward with risk; puts are poor.
  With changing views (random drift, or two states such as 15% and 0% drift switching randomly), the rule is optimal
  closure: sell when your own value falls to the market value. In the two-state model the option is worth exactly the market
  price in the low-drift state, so sell as soon as you believe the drift is low; optimal closure raised value from 11.64
  to 11.84. A hedger-speculator hedges only when expected to lose.
- Entry: buy when your real-drift value exceeds the price by more than costs. Exit: when your view's value meets the market
  price (view gone). Stop: not stated. Market/timeframe: generic, months.
- Signal source: the speculator's drift forecast for the underlying (IraAlgo default), option bought. Maps to NSE
  index options, MCX, crypto options. Mechanical: partly (needs a drift forecast and a regime signal).
- Our research: R16 (FINDINGS.md "What is predictable?"): direction 15/30/60 min not predictable (holdout AUC 0.509-0.536);
  every option-buying version lost Rs 12-142 a trade. R88: 0 of 8 direction tests held. With no drift edge the speculator's
  value equals the market price minus costs: no buy.

#### 14. Hedging feedback, pinning and gamma exposure (Ch. 61)
- What it computes: in an illiquid market, replicating positive gamma (portfolio insurance: buy rises, sell falls)
  destabilises the price (cited for 1987); near expiry and strike the demand curve folds, so prices can jump. If hedgers are
  net long gamma (they sell rises, buy dips) the price is damped and drawn to the strike near expiry (pinning).
- Use for us: the theory behind GEX. Signal source: option chain (OI x gamma) as context. Maps to NSE expiry days, MCX
  thinly, crypto. Mechanical: partly.
- Our research: R28 (FINDINGS.md): negative GEX at 10:00 came before bigger rest-of-day ranges (BANKNIFTY 0.87% vs 0.75%)
  but 0 of 10 arm filters passed; R36 (HUNT_R36_GAMMA_EXPOSURE.md): 0 of 4 rules pass; holding IV fixed, GEX's link to the
  afternoon move was slightly negative, a faint pin. R102: expiry close lands 0.60% from max pain (NIFTY).

**Ch. 62 Utility theory.** Risk aversion, certainty equivalent; theory. **Ch. 63 American options from the holder's view:**
a holder exercising on his own drift and risk aversion gives the writer a windfall. Not for European NSE options.
**Ch. 64 Dividends:** dividend risk can exceed vega; implied dividends from futures (NIFTY basis).

#### 15. Serial autocorrelation (Ch. 65)
- What it computes: lag-1 autocorrelation of returns; S&P 500 252-day average about 0.02 (above 4% before 2000, lately
  negative). Option prices are unchanged by autocorrelation if hedged, but with negative autocorrelation plus IV rising on
  falls, selling puts after drops collects more premium with a likely bounce (stated as an idea, no test).
- Signal source: underlying price. Mechanical: partly (no thresholds). Our research: R102's 1-minute "momentum" (+0.11 bps,
  853,718 pairs) was the cash index's staleness, below cost; daily momentum reversed in the holdout. Put-selling after drops
  is not tested by us.

**Ch. 66 Asset allocation in continuous time.** Merton: constant risky fraction for power utility; Kelly as the log case;
with costs, rebalance only when weights leave a no-trade region (realistically once a year or two).

#### 16. Allocation under threat of a crash (Ch. 67)
- What it computes: with a maximum crash size but no probability, the best exposure makes you indifferent between a crash
  now and none; it is lower than Merton's and falls to zero at the horizon. Example (drift 20%, rate 5%, vol 40%, max crash
  20%, one year): Merton's fraction 0.9375, crash-optimal much lower, best constant crash-proof fraction 0. Never hold more
  than one over the crash size.
- Use for us: size every overnight or expiry short-premium book so the assumed maximum gap cannot breach the -Rs 40k
  drawdown stop; cut exposure as the month's stop or the horizon approaches. Signal source: n/a (sizing). Mechanical: yes.
  Our research: not tested by us as a rule; Boss's fixed-lot frame already caps size.

**Ch. 68-70 Interest rates without probabilities.** Bounded rate and bounded speed give worst/best values (yield
envelopes), static hedges with traded bonds, a leasing portfolio example; uses: market-making at the bounds, a hard maximum
loss. The bounded-band idea could frame an India VIX band; otherwise not relevant.

**Ch. 71 Inflation.** Index-linked products. Not relevant.

**Ch. 72 Energy derivatives.** Electricity spikes revert in a day or two (jumps plus fast mean reversion); seasonal forward
curves; short-dated vol far above long-dated (applying spot vol to longer options overprices them); convenience yield;
two-factor spot-to-equilibrium model. For MCX crude and natural gas options: use expiry-specific vol, expect seasonality.
Not tested by us.

**Ch. 73 Real options.** Value of waiting, closure decisions; Ashanti gold over-hedging case. **Ch. 74 Life settlements.**
Not relevant.

#### 17. Bonus incentives and an implied-skill model (Ch. 75)
- What it computes: a bonus on profits is a call option on the account and rewards gambling; a trader who knows the
  direction a fraction p of the time is right 1/2 + p/2 of the time. Example: a friend's Sharpe ratios 3.7 and 4.7 implied
  skill of 30% and 38%, predicting 69% correct calls against an actual 68%.
- Use for us: translate an arm's hit rate into "skill" (55% right = 10% skill) and judge arms by Sharpe and drawdown, not
  raw P&L; deferred evaluation like hedge-fund high-water marks. Signal source: n/a. Our research: not tested by us.

### Part Six: numerical methods and programs (Ch. 76-83, Appendix A)
**Ch. 76** overview: finite differences for up to 3-4 factors and early exercise (the author's choice about 75% of the
time), Monte Carlo for many dimensions and path dependence, quadrature for Europeans. **Ch. 77-79** explicit, implicit,
Crank-Nicolson, projected SOR for American exercise, Richardson extrapolation (13.067 and 13.183 combine to 13.275 vs exact
13.269), upwind differencing, two-factor ADI and Hopscotch. **Ch. 81** quadrature and low-discrepancy (Halton, Sobol)
sequences beat plain Monte Carlo; always use antithetic variables. **Ch. 82-83** Visual Basic listings (not run; book code
is untrusted). **Appendix A**: e, logs, Taylor series, expectation and variance, Black-Scholes in words (time decay
balances interest and half vol squared times gamma; hedging error comes from the random squared move).

#### 18. Monte Carlo for option values and for speculative P&L (Ch. 80)
- What it computes: value = discounted average payoff over risk-neutral paths; exact lognormal steps; error falls with the
  square root of the number of paths (ten times the accuracy needs 100 times the paths); correlated paths via Cholesky;
  antithetic and control variates; Longstaff-Schwartz regression for early exercise. Key distinction: to judge an UNHEDGED
  position, simulate with the REAL drift (and real tails), never the risk-neutral one; a 1-year call and put with 20% drift
  showed the put expiring worthless about 75% of the time.
- Use for us: our P&L simulations should resample real days (bootstrap) rather than draw Normal risk-neutral paths.
  Signal source: n/a. Mechanical: yes. Our research: bootstraps used across hunts (HUNT_H15.md); no risk-neutral MC needed
  for European NSE options (closed form suffices).

## Performance claims
- No trading-performance claims of the usual kind; the book prices and hedges. Its numbers are model examples or historical
  anecdotes: VIX about 20% vs realised about 15% (Ch. 12, SPX, period not stated in the prose); hedging error factors above
  1 on real stocks (Ch. 47); delta-tolerance band best on about 70% of real paths (Ch. 48, about 40 stocks, 1985-96);
  convertible arbitrage once had a Sharpe ratio of 1.04 before crowding (Ch. 33); a friend's Sharpe 3.7 and 4.7 (Ch. 75,
  one trader). The 1987 and EVT figures are single historical events. No out-of-sample trading test is given anywhere.

## Risk, sizing and psychology
- Kelly at most, half Kelly in practice; twice Kelly is zero growth and LTCM ran about there.
- Worst-case thinking for crashes and parameters (CrashMetrics, uncertain vol, crash allocation) instead of probabilities
  for events that cannot be estimated; buy tail protection rather than skip it because implied vol looks high.
- Margin and funding kill hedged positions (Metallgesellschaft, LTCM, Barings); check margin under stress, not just P&L.
- Model risk: calibrated parameters that change daily mean the model is wrong; prefer simple robust models; vega can be
  meaningless; correlations break in crashes; "Think before you fit."
- Incentives: profit bonuses reward gambling; judge by risk-adjusted results.

## How it relates to our research
- Agrees: implied over realised (R102: 81% of days, n 4,507) is the seller's edge; but short gamma's tails are huge (R103,
  DEEP_OPTIONS_SELLING.md section 5: kurtosis 14 NIFTY; naked BANKNIFTY straddle worst day -Rs 65,483). Wilmott's answer is
  worst-case sizing and paid-for positive gamma, which matches R103's choice of defined-risk wings.
- Agrees: option buyers bleed without a real drift edge (Ch. 47, Ch. 59) and our direction tests found none (R16, R88);
  MCX buyers lose on average (MCX_OPTIONS.md).
- Agrees: Kelly is fragile with estimated edges (HUNT_H15.md: f* 1.29 vs 0.48 / 0.14 on other windows).
- Agrees: technical indicators lack evidence (Ch. 20) and ours failed (R29, R95).
- Partly: hedging feedback and pinning (Ch. 61) vs GEX: our R28/R36 found size information only, 0 trades.
- Not tested by us: a delta-hedged vol-arb on NIFTY monthlies, delta-band hedging, worst-case wing optimisation,
  crash-coefficient sizing, forward vol between weekly and monthly expiries, event premium, India VIX band models.

## What is new to us
1. The two hedging choices in Ch. 12 (hedge at implied = steady daily P&L, path-dependent total; at forecast = locked total,
   noisy path) and the rule that any hedge vol between the two cannot lose in the model.
2. Delta-tolerance band hedging (Ch. 48) as the empirically best way to rebalance a futures hedge (MCX, crypto).
3. Worst-case pricing with a vol band and a crash size (Ch. 52, 58, 60), including choosing wings by optimisation net of
   bid-ask; vega understates sign-changing-gamma risk (Ch. 56: 14x).
4. Crash-aware allocation (Ch. 67): exposure that falls toward the horizon and never above one over the crash size.
5. The crash coefficient (Ch. 43) fitted on extreme days only, for BANKNIFTY vs NIFTY exposure.
6. Implied skill from hit rate (Ch. 75) as a sober way to read an arm's win rate.
7. Real-drift Monte Carlo for unhedged P&L (Ch. 80) vs risk-neutral pricing paths.

## Could not read
- Formulas: the text layer flattens most equations (subscripts, fractions, Greek letters); prose was read, formula lines
  were skipped, so derivations were followed from the text, not the maths.
- Figures and most tables are absent or garbled (charts of VIX, hedging-error histograms, Monte Carlo spreadsheets); several
  newspaper figures in Ch. 44 are replaced by a note that online reproduction was not permitted.
- The bibliography and index were skimmed, not read line by line.
