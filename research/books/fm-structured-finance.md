# Structured Finance: The Object-Oriented Approach - Umberto Cherubini and Giovanni Della Lunga (2007)

Source: structured-finance-the-object-oriented-approach.txt, 301 pages (Wiley Finance), read in full on 25 Oct 2026
(text layer, pdftotext; all nine chapters and both appendices read; see "Could not read" for garbled formulas,
chart-only figures and Java listings read for their prose only).
Book claims are the authors', not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

**Plain verdict first.** This is a textbook for bank structurers and the programmers who build their pricing systems:
how to take apart equity-linked notes, convertibles, credit-linked notes and CDOs into options, and how to code that in
Java with UML. Very little of it maps to an Indian trader buying NIFTY / BANKNIFTY options: there are **no trading
rules at all**, no backtests and no performance claims. Roughly a third of the book is Java, UML, XML and FpML (bank
data formats), and another third is credit products (CDS, CDOs) that do not exist for us. What does carry over is
(a) the habit of asking "is this position long or short volatility, gamma and correlation?", (b) volatility and
correlation measurement (Ch. 3), (c) Monte Carlo technique (Ch. 6) and (d) the risk-management chapter (Ch. 9):
historical and **filtered historical simulation**, expected shortfall, stress testing. Those are tools for sizing,
risk reports and research method, not entry signals.

## What it is about
1. Every structured product is a bond plus options, and its price is the price of that replicating portfolio.
2. The structurer, the pricer and the risk manager all work from that replicating portfolio.
3. Volatility and correlation, implied and historical, decide most of the value, so the book surveys how to measure them.
4. Products are built from equity, credit and basket (multi-name) building blocks, each coded as Java "objects".
5. Risk is managed with Greek buckets, historical / filtered simulation, stress tests and counterparty-risk models.

## Methods, rules and setups

Chapter by chapter. Practical methods (things a NIFTY / BANKNIFTY / MCX / crypto trader could actually use) get a full
block; theory and software get one or two lines. Bennett's "Trading Volatility" is already written up
(`research/books/bennett-trading-volatility.md`); where the two books overlap (light exotics, dispersion, measuring
historical volatility, volatility risk premium) this file points there instead of repeating it.

### Ch. 1 - Structured finance: a primer
Teaches the replicating-portfolio view: a forward is the asset bought with borrowed money (static, no rebalancing);
a call is delta units of the asset plus a loan; a call equals an asset-or-nothing digital minus strike times a
cash-or-nothing digital; put-call parity. Without a model the call is only bounded (between zero and the forward), which
the authors call the simplest "incomplete market" problem. Then the risk-neutral measure, the Black-Scholes equation and
formula (delta = N(d1)), smile and term structure as evidence that volatility is not constant, and "model risk".
Multi-asset claims: copulas split the joint distribution into marginals plus dependence; the exchange option
(Margrabe) and Black's formula by change of numeraire. Structuring is choosing dates, coupons and a repayment plan; risk
is described by kind of risk factor, which moment (level vs volatility) and dimension (single vs basket, i.e.
correlation). Risk-management issues: optionality pushes risk measurement to simulation; measurement risk (implied or
historical volatility?); model risk; long-dated risk; counterparty risk.

#### 1. "Tale of two bonds": find the sign of your delta, gamma and vega (Ch. 1.6; repeated Ch. 5.5, 6.4, 9.3)
- What it computes: decomposes a product into options to read its exposures. An equity-linked note = bond + call: long
  the asset, long volatility, long gamma. A reverse convertible = bond minus a put: also long the asset, but short
  volatility and short gamma, so even when delta-hedged it loses on a large move in either direction. The authors say
  selling an option earns little if nothing happens and loses a lot if the market moves, and that a gamma hedge is
  "strongly recommended".
- Inputs: the payoff of each leg.
- How we could use it: Jarvis / the paper arms should always show, per open position, the sign of delta, gamma and vega
  (an option BUY is long gamma and long vega and pays theta; any SELL is the reverse). This is the right frame for any
  future option-selling idea.
- Signal source: volatility / options structure (risk description, not a signal). Maps to NSE index options: yes, as a
  risk read-out; MCX options: yes; crypto options: yes.
- Mechanical? yes (pure arithmetic of the payoff).
- Our research: R103 (`HUNT_R103_OPTIONS_SELLING.md`) is exactly this lesson in our data: defined-risk selling 0 of 24
  confirmed (the passes were stale wing prices), and the naked 09:20 short straddle (short gamma, no stop) made
  +Rs 64k on NIFTY over 1,272 days with a worst day of -Rs 28,876 on one lot: small steady gains, rare large losses.
  `DEEP_OPTIONS_SELLING.md` gives the Black-Scholes payoffs and Greeks at NIFTY 25,000, IV 15%.

#### 2. Black-Scholes pricing, put-call parity and digitals as building blocks (Ch. 1.3-1.4, 5.7, 6.4)
- What it computes: the price and delta of European calls and puts; a digital call is minus the slope of the call price
  with respect to strike (a tight call spread), and in Black-Scholes it is the discounted N(d2).
- Inputs: spot or forward, strike, time, rate, volatility (the book's "Lego" design: payoff + exercise + underlying +
  discounting + a swappable pricing engine: closed form, a 1,000-step binomial tree for early exercise, or Monte Carlo).
- How we could use it: we already reprice stale legs with Black-Scholes at the last IV (R103 Amendment 2). The design
  idea (one option object, pricing engine swappable) matches what the engine module should do if more pricers are ever
  added. The digital = call-spread identity gives the chain's own probability that expiry ends above a strike.
- Signal source: volatility / options structure. Maps to NSE index options: yes (European, cash-settled); MCX options:
  yes (options on futures, Black's formula); crypto options: yes.
- Mechanical? yes.
- Our research: used, not tested as a signal (R103, DEEP_OPTIONS_SELLING).

### Ch. 2 - Object-oriented programming
Software only: a dialogue between Giovanni (software) and Umberto (finance) builds Option / Asset / Country classes in
UML, then explains encapsulation, inheritance (European and American options as subclasses), abstract classes,
association vs aggregation vs composition, collections and polymorphism (a Monte Carlo engine that calls an abstract
`Value()` without knowing which option it prices). One finance point: an exchange-listed option is a "composition" of
two digitals (one contract, clearing-house counterparty) while the same payoff built from two OTC digitals is an
"aggregation" with different default exposure. Nothing to trade.

### Ch. 3 - Volatility and correlation (the most useful chapter for research)
A survey, not a study: three questions decide the method (model the distribution or only its moments; implied or
historical information; how many parameters). Historical data suffer from structural breaks; implied data carry risk
premia and illiquidity. The chapter then lists the main tools, which follow.

#### 3. Implied probability distribution from the option chain (Ch. 3.3, 3.4.1)
- What it computes: the market's (risk-neutral) probability that expiry ends above each strike, from call spreads
  (Breeden-Litzenberger); the density from butterflies. With few strikes, interpolate the smile first: a quadratic fit
  in strike (Shimko), a Hermite expansion matching four moments (Jarrow-Rudd), or a mixture of shifted lognormals.
  Implied trees (Rubinstein, Derman-Kani) and Dupire's local-volatility surface extend this through time.
- Inputs: a dense, liquid strip of call and put prices for one expiry; the forward.
- How we could use it: as context for Jarvis ("the chain prices a 1-in-5 chance NIFTY ends above X") and as a
  cross-check of our expected-range nowcast. Caveats from the book: the distribution is centred on the forward and
  includes the risk premium, so it is not a real-world forecast; wings need liquid prices (our lake keeps only ATM +-10
  strikes, and R103 showed how stale wing prices mislead).
- Signal source: option chain as context. Maps to NSE index options: yes (NIFTY and BANKNIFTY chains are dense near the
  money; wings are thin); MCX options: weakly (few liquid strikes); crypto (Deribit / Delta Exchange): yes for BTC/ETH.
- Mechanical? yes.
- Our research: not tested by us as a distribution. Nearest: IV1 (`HUNT_IV1_OPTION_MARKET.md`) found IV skew, straddle,
  PCR and OI alone lose on all 5 indices; R16 found max pain worse than "no change" for the expiry close. R102 section
  3.4 measured the skew: puts five strikes out dearer than calls on 91% of NIFTY days (n 1,222), median +2.56 IV points.

#### 4. GARCH-family and EWMA volatility forecasts (Ch. 3.4.2; used again in Ch. 9.3-9.4)
- What it computes: tomorrow's variance from today's squared return and today's variance (GARCH(1,1)); variants for
  the leverage effect (GJR, EGARCH: falls raise volatility more than rises), fat-tailed shocks (Student t, GED),
  persistence (IGARCH, which is the RiskMetrics exponentially weighted average, EWMA) and long memory (FIEGARCH).
  Stochastic volatility (Hull-White, Heston) and local volatility (CEV) are named as the pricing-side alternatives.
- Inputs: a return series (daily or intraday).
- How we could use it: as one more input to the move-SIZE forecasts that our research found genuinely predictable,
  and as the filter in filtered historical simulation (method 15). An EWMA of squared returns is the simplest version.
- Signal source: underlying price (a size / risk forecast, not a direction signal). Maps to NSE index: yes; MCX: yes;
  crypto: yes.
- Mechanical? yes.
- Our research: GARCH itself not tested by us (no study names it). Nearest: R16 (`HUNT_R16_PREDICTABILITY.md`): the
  next 30/60-minute range is predictable (holdout error 15-20% below the time-of-day average), direction is not; R41
  neural nets forecast the next 60-minute range with holdout R2 0.432 vs trees 0.382; R88 found size predictable on 225
  of 305 tests. A GARCH baseline would be a fair benchmark to add to those size models.

#### 5. Realised volatility and range-based estimators (Ch. 3.2.3, 3.4.3)
- What it computes: volatility from the sum of squared returns, ideally sampled every 5 minutes or less, and the
  high-low estimator of Parkinson (plus Garman-Klass, Rogers-Satchell, Yang-Zhang using open and close too). The book
  warns that at very high frequency bid-ask bounce and non-synchronous index constituents add noise, and that the range
  estimators assume a continuous (no-gap) price path.
- Inputs: OHLC bars or intraday returns.
- How we could use it: our 1-minute lake supports a proper 5-minute realised volatility per day; compare it with ATM IV
  and VIX for the volatility risk premium.
- Signal source: underlying price. Maps to NSE index: yes; MCX: yes; crypto: yes (24-hour, so no gap problem).
- Mechanical? yes.
- Our research: R102 (`DEEP_NIFTY_BANKNIFTY.md`, by-year table) found the Parkinson high-low measure runs well below
  close-to-close on both indices every year (NIFTY 2025: 11.8 vs 8.4 close-to-close vs Parkinson) because the overnight
  gap is a big part of daily variance; that is exactly the book's caveat. Measuring historical volatility is also in
  Bennett section 26.

#### 6. Implied versus historical volatility (Ch. 3.2, 3.4.3, Fig. 3.3-3.4)
- What it says: implied volatility is forward-looking but includes a risk premium; realised is model-free but
  backward-looking. On USD/EUR options (2000-2005, chart only) implied volatility moved more than even the reactive
  RiskMetrics estimate. Which one to trust "remains an important open issue".
- Signal source: volatility / options structure. Maps to NSE: yes.
- Our research: R102: India VIX over-states NIFTY's next 21 days on 81% of days (median ratio 1.28, n 4,507 days) but
  BANKNIFTY's realised runs about 1.3x NIFTY's, so VIX under-states it on 60% of days. Bennett section 13 covers the
  same premium.

#### 7. Implied correlation (Ch. 3.5)
- What it computes: (a) FX triangle: three implied volatilities give the implied correlation; (b) equity "average
  implied correlation": index implied variance versus the weighted constituent implied volatilities (for many stocks,
  roughly the square of index vol over the weighted sum of constituent vols); (c) credit tranche correlation (Ch. 8).
- Inputs: index IV and constituent IVs with index weights.
- How we could use it: NIFTY and BANKNIFTY have liquid stock options on their heavyweights, so an average implied
  correlation is computable. It is context for dispersion (index vs stocks), which is a dealer trade, not ours.
- Signal source: volatility / options structure. Maps to NSE: possible (stock options exist; would need constituent
  chains we do not record); MCX: no; crypto: no.
- Mechanical? yes.
- Our research: not tested by us. Dispersion and correlation trading are in Bennett section 21.

#### 8. Historical correlation: multivariate GARCH and DCC (Ch. 3.6)
- What it computes: time-varying correlations. Full multivariate GARCH (BEKK) has too many parameters; reductions are
  orthogonal GARCH (principal components) and factor GARCH; Engle's dynamic conditional correlation (DCC) fits each
  series' GARCH first and the correlations second.
- How we could use it: a live NIFTY-BANKNIFTY (and other index) correlation for portfolio risk when several arms hold
  positions at once, and for the cross-index arm (R72).
- Signal source: underlying price. Maps to NSE: yes; MCX (gold-silver, crude-natural gas): yes; crypto (BTC-ETH): yes.
- Mechanical? yes.
- Our research: only static correlations: R102 section 1.4, BANKNIFTY on NIFTY daily correlation 0.877, beta 1.157 over
  2006-2026 (n 5,086), with year-to-year drift (0.856 in 2024, 0.928 in Jan-Jul 2026). R72 (`HUNT_R72_CROSS_INDEX.md`):
  Breadth Donchian passed; its trades overlap Donchian's (daily correlation 0.80), so run one, not both. DCC itself not
  tested by us.

#### 9. Copulas, tail dependence and correlation asymmetry (Ch. 3.7-3.13)
- What it computes: a copula is the joint distribution of the ranks; it separates "how each market moves" from "how they
  move together". Gaussian copula: no tail dependence (extreme moves become independent); Student t: tail dependence;
  Clayton: dependence concentrated in joint falls. Rank correlations (Spearman, Kendall) are copula properties.
  Exceedance correlation (Longin-Solnik, Ang-Chen): equity correlations are higher in down markets than up markets.
  Non-exchangeable copulas capture one market leading another ("when market X catches a cold, market Y takes
  pneumonia"). Estimation: match Kendall's tau, full maximum likelihood, two-stage (margins first) or rank-based.
- How we could use it: when several index-option positions are open, assume correlations go UP in a sell-off; a
  Gaussian-correlation risk estimate will understate the joint crash. A simple test we have not run: exceedance
  correlation of NIFTY vs BANKNIFTY 5-minute returns below vs above a threshold.
- Signal source: underlying price (risk structure). Maps to NSE: yes; MCX: yes; crypto: yes (BTC-ETH).
- Mechanical? yes.
- Our research: not tested by us.

#### 10. The AND / OR rule for correlation exposure (Ch. 3.7, 6.5)
- What it says: a payoff that needs event A AND event B is long correlation; one that pays on A OR B is short
  correlation. A call on the worst of several assets is long correlation; on the best, short.
- How we could use it: as a sanity check on multi-condition setups. A rule that needs "4 of 5 indices break together"
  (R72's Breadth Donchian) is an AND condition: it fires more when the indices are tightly correlated, so its trade count
  depends on the correlation regime.
- Signal source: none (a reasoning device). Mechanical? yes. Our research: not tested by us.

Also in Ch. 3, one line each: Lévy / jump models (Clark's idea that the number of trades drives both volume and
volatility and fattens the tails; Merton jumps, variance gamma, CGMY) - theory, no method for us.

### Ch. 4 - Cash flow design
Bond types (floaters, convertibles, equity-linked notes with a World Bank S&P 500 note as example, inflation-linked,
asset-backed and CDOs); business-day conventions (following, modified following, IMM dates) and day counts (Act/365,
30/360); a Java schedule builder using interfaces and the factory-method pattern; UML activity and sequence diagrams;
the FpML layout of an interest-rate swap. Nothing to trade. The only echo for us is that expiry and holiday calendars
must be data, not code, which the app already does.

### Ch. 5 - Convertible bonds
The three questions for any product: does the option change the coupons or the principal; what is the sign of delta,
gamma and vega; which option is the "main course" and which are add-ons. Convertible = bond plus calls on the stock;
callable / putable = bond with an option on itself; reverse convertible = bond minus puts (short gamma, short vega; see
method 1). Barriers: an "in" plus an "out" option equals the plain one; contingent convertibles use up-and-in calls,
contingent reverse convertibles down-and-in puts. Closed-form barrier prices (Zhang's formulas, with rebates) and the
"strategy pattern" that lets one option object switch between Black-Scholes, a binomial tree and Monte Carlo.

#### 11. Parisian (time-in-zone) barriers instead of one-touch (Ch. 5.6.4)
- What it says: a plain barrier can be triggered (or manipulated) by one outlier print, especially in thin markets; a
  Parisian barrier triggers only if price stays beyond the level for a set time. Example: a General Mills convertible
  that could convert only if the stock closed above the barrier on 20 of 30 consecutive days.
- How we could use it: as a design principle for our stops and level triggers, a stop or breakout "confirmed" only when
  price holds beyond the level for N bars rather than on one tick. This is a contract feature in the book, not a
  tested trading rule.
- Signal source: underlying price. Maps to NSE index: yes (stop / confirmation logic on spot); MCX: yes; crypto: yes
  (crypto rules still need a hard stop and a profit lock; a time-confirmed stop cannot replace the hard stop).
- Mechanical? yes.
- Our research: R20c (`HUNT_R20C_SR_BREAK.md`) tested support/resistance breaks confirmed by 2 candles: not an edge.
  Confirmation on stops not tested by us; OPEN POSITIONS FIRST means any such stop logic must still run at full cadence.

### Ch. 6 - Equity-linked notes
Single-coupon notes (bond + call); "crash protection" that resets the strike if the index falls to a barrier, with an
"outside" barrier version triggered by a global index and priced with copulas (Clayton gives more value than Gaussian
because tail dependence favours knock-in over knock-out); caps by participation rate or call spread; callable / putable
notes as compound options (Geske); Asian (average-price) coupons to soften a five-year horizon, priced by moment
matching (Turnbull-Wakeman) and seasoned as fixings accrue; digital notes; cliquets (strings of forward-start at-the-money
options) and reverse cliquets (the investor quietly sells a put strip: long the market, short volatility); multi-asset
notes (Altiplano, memory coupons, Everest = worst-of, basket notes). All of this is product design for issuers.

#### 12. Monte Carlo pricing and its error control (Ch. 6.6)
- What it computes: an expected payoff as the average over simulated paths. The book's points: the standard error falls
  with the square root of the number of paths (four times the paths halves the error) and does not depend on the
  dimension; quote a confidence interval with every estimate; sample by inverting the distribution function.
  Variance reduction: **common random numbers** (use the same random draws for the base and the bumped case, e.g. a
  finite-difference delta; independent draws make the difference explode as the bump shrinks), **antithetic variates**
  (pair each draw with its negative; helps when the payoff is monotone), **control variates** (simulate the error
  against a related quantity with a known value, e.g. geometric-average Asian as control for the arithmetic one;
  Figures 6.4-6.5 show a large reduction, no numbers in the text).
- Inputs: a path model and a payoff.
- How we could use it: (1) every bootstrap / Monte Carlo number in our studies should carry its standard error;
  (2) when comparing two variants of a rule (e.g. two stop levels) on resampled paths, use the SAME resampled paths for
  both (common random numbers): the paired difference is far less noisy than two independent runs; (3) antithetic
  pairs for simulated price paths.
- Signal source: none (research method). Maps to NSE / MCX / crypto: yes, as method.
- Mechanical? yes.
- Our research: we already use a stationary block bootstrap for ruin and drawdown, e.g. H16 (`HUNT_H16.md`, 2,000 paths,
  block of 10 days); common random numbers across variants are not written down as a rule in our PREREGs.

### Ch. 7 - Credit-linked notes
A defaultable bond = a safe bond minus a "default put"; credit spreads from expected loss; asset swap spreads (an NTT
bond, 38.3 bp, between the A and BBB curves); total return swaps; credit default swaps (protection and premium legs,
cheapest-to-deliver option, restructuring moral hazard) and bootstrapping default probabilities from CDS quotes (Fiat,
2002); credit-linked notes (= bond minus CDS); rating-linked coupon step-ups priced with transition matrices (a Telecom
Italia 6.125% bond); structural models (Merton: equity is a call on the firm's assets; KMV "distance to default"; Duan's
maximum likelihood) versus reduced-form (default intensity) models; recovery falls when defaults rise. Parmalat's stock
and CDS moved as mirror images before its 2003 default. Nothing in this chapter maps to index options, MCX or crypto.

### Ch. 8 - Basket credit derivatives and CDOs
First-to-default and nth-to-default swaps (first-to-default is short correlation); dependent defaults by common shocks
(Marshall-Olkin), copulas (Gaussian, Student t), one-factor copulas with conditional independence, regime-switching
correlation (Andersen-Sidenius: a low and a high correlation state); CDO structure, tranches and the waterfall; the
equity tranche as a put on losses (long correlation), senior as a short call (short correlation); i-Traxx / CDX
standard tranches and base vs compound correlation; a Java CDO simulator (Poisson default times from copula draws,
CDO-squared with cross-subordination). Credit only, but three lessons carry over:

#### 13. Simulating correlated markets: Cholesky for Gaussian, chi-square scaling for Student t (Ch. 8.7)
- What it computes: joint scenarios for several markets with a chosen correlation matrix: draw independent normals,
  multiply by the Cholesky factor of the correlation matrix; for fat joint tails divide by the square root of a scaled
  chi-square draw (Student t copula); map to uniforms, then to each market's own return distribution.
- How we could use it: a joint stress of all open paper positions across NIFTY, BANKNIFTY, FINNIFTY, SENSEX and MCX,
  with t-copula tails so that crashes arrive together.
- Signal source: underlying price (risk tool). Maps to NSE / MCX / crypto: yes. Mechanical? yes.
- Our research: not tested by us.

#### 14. Do not back an implied parameter out of a non-monotone instrument (Ch. 8.5.2, 8.6.2)
- What it says: a mezzanine tranche (like an option call spread) is not monotone in correlation (or volatility), so
  "implied correlation" from it can be meaningless or multiple; the market moved to "base correlation" from
  equity-style tranches. The authors draw the parallel with implying volatility from call spreads.
- How we could use it: if we ever compute an "implied" anything from the chain, use single options or monotone
  structures, never spreads or flies.
- Signal source: volatility / options structure. Maps to NSE: yes, as a research rule. Our research: not tested by us.

The May 2005 "delta-hedged equity" story (Ch. 8.6.3) belongs here too: hedge funds held CDX equity tranches hedged with
the mezzanine because that hedge paid more carry (about 1.4m a year vs 0.9m for an index hedge on a 10m position, per
the broker research the authors quote); on one weekend the index moved 3 bp, the equity up-front rose 3.125% while the
mezzanine spread fell 16 bp, and with hedge leverage about 2.2 the hedge added roughly 1.6% more loss. Lesson: model
hedge ratios are unstable, and a hedge chosen for its carry hides basis risk. For us this is a caution about any
cross-index hedge or pairs position (R72), not a rule.

### Ch. 9 - Risk management (the second useful chapter)
Three questions: are the prices right (illiquid products have no exact replicating portfolio); how does a non-linear book
respond to moves, volatility and correlation; hedge in-house or buy the hedge OTC and take counterparty risk. Futures
markets (marking to market, margin, clearing house) cut counterparty and liquidity risk at the cost of basis risk; VaR
limits and daily collateral are banks copying that model. Risk measurement steps: replicating portfolio, mark to market,
map to risk factors, joint distribution, risk measure.

#### 15. Greek buckets per risk factor (Ch. 9.3.1)
- What it computes: for each underlying, the summed delta, gamma and vega of every open position (sensitivities add up).
  The authors stress that with negative gamma and vega a delta-hedged book still loses whichever way the market moves.
- How we could use it: a three-number risk line per underlying in the positions screen and in Jarvis ("NIFTY: delta
  +0.6 lot, gamma +, vega +"). Read-only; must not touch the order or tick path (computed off the tick thread).
- Signal source: volatility / options structure (risk report). Maps to NSE / MCX / crypto options: yes. Mechanical? yes.
- Our research: not a research question; a display idea.

#### 16. Historical simulation VaR and expected shortfall (Ch. 9.3.2-9.4)
- What it computes: hold today's positions fixed, revalue them under each past day's market moves (typically one year of
  days), sort the profits and losses, read the loss percentile (VaR, e.g. 1% or 5%) or the average loss beyond it
  (expected shortfall). The authors explain why parametric (normal) VaR fails for option books (squared moves follow a
  chi-square law, so no closed form), note that VaR can fail subadditivity, and expect expected shortfall to win because
  it is easier to use as a constraint.
- Inputs: positions, a revaluation function (Black-Scholes at current IV), a window of past index moves.
- How we could use it: an "if the worst days of the last year repeat" number for Boss's account against the stop rule
  (-Rs 40k drawdown, -Rs 13k a month): e.g. "the 1% worst day for today's paper book is -Rs X". Use expected shortfall,
  not only VaR, because option-selling losses are fat-tailed.
- Signal source: underlying price + volatility (risk measure). Maps to NSE / MCX / crypto: yes. Mechanical? yes.
- Our research: not tested by us as VaR / ES. Nearest: our studies report worst day, worst month, max drawdown and
  bootstrap ruin (H15, H16, R103 tail table), which is the empirical-percentile idea without the name.

#### 17. Filtered historical simulation (Barone-Adesi and Giannopoulos) (Ch. 9.4.1-9.4.3)
- What it computes: fixes plain historical simulation's two flaws (past days carry one-off quirks, and they are not
  independent because volatility clusters, so a calm window understates risk going into a storm). Steps: fit a GARCH
  model to the portfolio (or index) returns; divide each residual by its fitted volatility to get standardised shocks;
  draw shocks at random and rebuild an n-day path using TODAY's volatility and the GARCH recursion; sum; repeat many
  times; read VaR / ES from the histogram. Multi-asset: fit GARCH per series plus DCC for correlation and decorrelate the
  shock vectors; with constant correlation, simply bootstrap the standardised shock vectors together (they carry their
  own correlation). Or use a conditional copula. Their recipe: constant volatility and correlation -> plain historical
  simulation; changing volatility, constant correlation -> filtered per series; both changing -> filtered with DCC.
- Inputs: return history per underlying; current positions; GARCH (or EWMA) fit.
- How we could use it: (a) a volatility-aware day-loss / drawdown estimate for the paper book; (b) a better resampling
  scheme for our own strategy bootstraps: a plain or block bootstrap of trade P&L from a calm period understates the risk
  of the next volatile period; scaling resampled returns by the current volatility is the FHS fix.
- Signal source: underlying price (risk / research method). Maps to NSE / MCX / crypto: yes. Mechanical? yes.
- Our research: not tested by us. Our bootstraps (H16: stationary bootstrap, block of 10 days) keep some clustering but
  do not rescale to current volatility; H15 (`HUNT_H15.md`) already warns that its bootstrap flattered half-Kelly because
  it resampled 10 good months.

#### 18. Stress testing: shock propagation and worst-case search (Ch. 9.5)
- What it computes: (a) sources: historical crises (19 Oct 1987, May 2005), implied information, and in-house analyst
  views; (b) propagate a shock in one factor to the others with the Cholesky factor (order-dependent, so not unique) or,
  better, a Black-Litterman style conditional distribution (a regression of all factor moves on the scenario's stated
  views, with a precision for each view); (c) "Murphy's machine": a genetic algorithm that breeds scenarios (crossover,
  mutation, fitter = more painful) to search for worse ones than you started with.
- How we could use it: a short list of named Indian stress days (e.g. the March 2020 crash, the 4 Jun 2024 election-result
  day) replayed against open paper positions; (c) is overkill for us.
- Signal source: underlying price + volatility (risk). Maps to NSE / MCX / crypto: yes. Mechanical? (a)-(b) yes, (c) yes
  but heavy.
- Our research: not tested by us as stress tests; several HUNT studies use a "stress test" of costs or slippage (e.g.
  `HUNT_H25.md`, `HUNT_R29_EMA_RIBBON.md`), which is a different thing.

#### 19. Counterparty risk and collateral (Ch. 9.6)
- What it says: default risk turns even a forward into an option-like exposure; "wrong-way" risk when the counterparty is
  likely to fail exactly when you are owed (buying an oil put from an oil producer); daily marking to market plus
  collateral removes most of it; netting turns the exposure into a basket option.
- How we could use it: irrelevant for NSE and MCX trades (clearing corporation in the middle). Relevant for crypto: an
  exchange such as Delta Exchange India is the counterparty for the whole balance, and a crypto crash is when an exchange
  is most likely to fail (wrong-way risk). Keep crypto balances small and withdraw profits; context only, since nothing in
  the app trades crypto.
- Signal source: none. Maps to crypto: yes (as a risk note). Our research: not tested by us.

### Appendices A and B
Eclipse IDE; XML, DTDs, schemas and FpML. Software only.

## Performance claims
- No trading performance claims anywhere in the book. No strategy, win rate or return is claimed.
- Pricing illustrations only: e.g. a bivariate digital priced at 0.06614 with a Gaussian copula (correlation 30%,
  marginals 20%) versus 0.0484 for the matching call under a Clayton copula, to show correlation asymmetry; CDO tranche
  quotes and deltas for 27 Sep 2005 from a broker's report.
- The May 2005 hedge-fund losses (method 14) and the carry figures are quoted from broker research, not the authors'
  own data.
- Control variates are said to give an "impressive" variance cut (charts only, no numbers stated).
- Merton-type structural credit models are said to under-price short-dated spreads (cited literature, no data shown).

## Risk, sizing and psychology
- Risk: the core message is to know the sign of every exposure (direction, gamma, volatility, correlation), to prefer
  simulation over normal-distribution formulas for option books, to use expected shortfall alongside VaR, to filter
  historical scenarios for volatility clustering, and to stress test with consistent multi-market scenarios. Short
  option positions (reverse convertibles, reverse cliquets) are singled out as dangerous: small gains, large losses.
- Model risk and estimation risk are treated as real risks: too few parameters misses the shape, too many overfits ("the
  same trade-off as generalisation in artificial intelligence"); the authors' remedy is stress tests and asking which way
  the position is exposed.
- Sizing: no position-sizing rule (no Kelly, no fixed fraction). Capital is discussed only as VaR limits per desk.
- Psychology: none.

## How it relates to our research
- **Agrees:** the "know your gamma and vega sign; short options earn little and lose big" message matches R103 (0 of 24;
  naked short straddle positive but with -Rs 28,876 worst days) and DEEP_OPTIONS_SELLING. The "implied includes a risk
  premium" point matches R102 (VIX above NIFTY's realised on 81% of days). The caveat that range estimators assume no
  gaps matches R102's finding that Parkinson runs below close-to-close because of overnight gaps. The parsimony warning
  matches our walk-forward / holdout / Benjamini-Hochberg discipline.
- **Already rejected by us (nearest):** nothing in the book is a direction rule, so none of our direction rejections
  apply directly. Option-chain-only signals (IV1, all 5 indices lose) and max pain (R16) cover what the implied
  distribution would most tempt us to try.
- **Not tested by us:** GARCH / EWMA as a size baseline; DCC or exceedance correlation between indices; copula tail
  dependence; filtered historical simulation; expected shortfall for the paper book; stress replays; common random
  numbers when comparing variants; average implied correlation.
- **Signal source:** every practical method here is either underlying price (volatility, correlation, risk) or
  volatility / options structure (pricing, Greeks, implied distribution). None reads an option's own price chart, so
  nothing conflicts with "signal on the underlying, trade the option".

## What is new to us
1. **Filtered historical simulation** for the account's day-loss / drawdown risk and as a better resampling scheme for
   our strategy bootstraps (rescale resampled shocks to current volatility).
2. **Expected shortfall** as the headline tail number for any selling or multi-position paper book.
3. **Common random numbers** as a written rule in PREREGs when comparing variants on simulated or resampled paths.
4. **Exceedance / tail-dependence check** for NIFTY vs BANKNIFTY (and gold vs silver on MCX): are correlations higher
   on down moves? This decides how much multi-arm risk really diversifies.
5. **A GARCH / EWMA baseline** for the move-size forecasts (R16, R41, R88).
6. **Greek buckets** per underlying in the app's risk display.
7. Implied risk-neutral distribution from the chain as Jarvis context, with the forward-centring and wing-liquidity caveats.

## Could not read
- Formulas: pdftotext dropped most mathematical symbols (Greek letters, integrals, fraction bars), so equations in Ch. 1,
  3, 5, 6, 7, 8 and 9 were read from the surrounding prose; the methods above are described from that prose and standard
  knowledge of the named models, not from the garbled equations.
- Figures are charts without numbers in the text: 1.1, 3.1-3.4 (USD/EUR implied distribution, smile, implied vs realised
  vs RiskMetrics), 6.1, 6.4-6.5 (variance reduction), 7.2-7.3 (NTT spreads, Parmalat stock vs CDS), 8.4-8.5 (base
  correlation skews), 9.1-9.7 (counterparty-risk profiles and collateral effects).
- Tables 7.1-7.2 (Telecom Italia step-ups, Fiat CDS quotes) and 8.1-8.3 (i-Traxx / CDX quotes, correlations, deltas) came
  out partly garbled; only the figures quoted in the prose are used here.
- The Java, UML and XML listings (much of Ch. 2, 4, 5, 6, 8 and Appendices A-B) were read for their explanatory prose,
  not line by line; they contain no finance beyond what the prose states.
