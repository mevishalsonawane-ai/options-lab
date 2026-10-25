# Copula Methods in Finance - Umberto Cherubini, Elisa Luciano and Walter Vecchiato (2004)

Source: copula-methods-in-finance.txt, 308 pages (xvi + 293), read in full on 25 Oct 2026 (pdftotext text layer, all 15,889
lines: preface, chapters 1-8, bibliography and index; working notes in the scratch folder). The prose and tables came through
cleanly; the 3-D copula plots, scatter plots and line charts are images, so only their axis numbers and captions survived (see
"Could not read").
Book claims are the authors', not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A Wiley mathematical-finance textbook by two Turin academics and a bank risk manager on copulas: functions that join single-asset distributions into a joint one.
Its central idea: describe each market on its own (its "margin"), then describe separately how markets move together (the "copula").
It argues that linear correlation, the dealing-room standard, misleads when returns are not normal and misses the chance of joint crashes (tail dependence).
Chapters 1-4 give the theory (derivative pricing basics, bivariate and multivariate copulas, dependence measures, copula families); 5-6 give estimation and simulation.
Chapters 7-8 apply it to credit baskets / CDOs and to options on two or more assets (digital, rainbow, exchange, barrier, basket, counterparty-risky options).

## Methods, rules and setups

This is a methods book, not a trading manual: it has no entry or exit rules. For each practical method below we give what it
computes, its inputs and how we could use it. Our data for the mapping: NIFTY / BANKNIFTY / FINNIFTY / MIDCPNIFTY / SENSEX
spot and futures bars (1-minute, 5-minute, daily since 2006), the nearest-expiry option chain within ATM +-10 strikes with IV
and OI, India VIX, MCX futures and options, BTC / ETH spot and Delta Exchange options, and the arms' own daily paper P&L. Indian
index options are exchange-cleared, European and cash-settled.

### Chapter 1. Derivatives pricing, hedging and risk management: the state of the art
Pure theory in brief: the binomial model, replication, risk-neutral versus real-world probability and the market price of
risk; Black-Scholes through Ito's lemma and Girsanov; interest-rate models (Vasicek, CIR, forward measure, LIBOR market
model, Black's caplet formula); Merton / reduced-form / KMV credit models and CDS bootstrapping. None of the rate or credit
material maps to our trading. The practical pieces:

#### 1. Digital options and the option-implied distribution (Ch. 1.3.4, 1.5.3)
- What it computes: a cash-or-nothing digital is worth the discounted chance of finishing above the strike (N(d2) under
  Black-Scholes); a call equals an asset-or-nothing digital minus K cash digitals in any model. Minus the forward-valued
  slope of call prices across strikes is the risk-neutral probability of finishing above that strike; the curvature is the
  density (Breeden-Litzenberger). A call is the integral of that probability above the strike.
- Inputs: a strip of call or put prices across strikes for one expiry.
- How we could use it: an implied probability curve for NIFTY / BANKNIFTY expiry from the chain, as context for Jarvis
  ("the market prices a 20% chance of finishing above X"). Limit: our lake keeps only ATM +-10 strikes, so the tails beyond
  them are not observed (the same gap that made R103's far wings stale).
- Signal source: option chain as context; maps to NSE index options yes, MCX options yes (thin), crypto options yes.
- Our research: R102 describes skew and max pain from the chain (put skew +2.56 / +2.11 IV points); no implied-distribution
  study. Not tested by us.

#### 2. Smile models (Ch. 1.5)
- Stochastic volatility (Hull-White style averaging of Black-Scholes over a volatility distribution; Heston), local
  volatility (CEV) and mixtures of lognormals (Brigo-Mercurio: price = weighted sum of Black-Scholes prices) are listed as
  ways to fit the smile. Pricing context only; Bennett's book covers smile trading (see `bennett-trading-volatility.md`,
  methods 23-25). Not tested by us.

#### 3. Incomplete markets: buyer and seller prices, uncertain volatility (Ch. 1.6)
- What it computes: when no perfect hedge exists there is a range of prices, a lower "buyer" and upper "seller" price. A
  fuzzy-measure version widens the gap more for out-of-the-money options. The uncertain volatility model (Avellaneda) hedges
  with the low volatility where the book is long gamma and the high volatility where it is short gamma, so a long and a
  short position get different values.
- Inputs: a volatility band (for example, low and high India VIX of the last year).
- How we could use it: mark a short-option book (R103-type structures) at the worse end of a volatility band rather than at
  one IV; a conservative mark-to-model when wing prices are stale. Signal source: volatility / options structure; NSE yes.
- Our research: R103 repriced stale wings with Black-Scholes at the leg's last IV (model 1-5 points high on legs that did
  print). A band-based mark was not tried.

#### 4. Copula primer and tail dependence (Ch. 1.8)
- What it computes: a two-index digital (pays if both indices are below their strikes) is the copula of the two single
  digital prices. Independence gives the product of the two; perfect co-movement gives the smaller one; perfect opposite
  movement gives max(u + v - 1, 0) (the Frechet bounds). The lower tail coefficient asks: given index A is in its worst v
  of outcomes, how likely is index B to be in its worst v too, as v shrinks to zero? It is 0 for independent markets and 1
  for perfectly dependent ones; the upper tail coefficient asks the same for booms.
- Key point: fat tails in each market and tail dependence between markets are separate properties; a model can have one
  without the other.
- First-to-default example: on two names whose default times have 50% correlation (Gaussian copula) the protection costs
  116,240 euros against 123,200 under perfect dependence, so the book calls that basket "definitely undiversified".
- How we could use it: see "How it relates to our research" (NIFTY / BANKNIFTY and several arms at once).

### Chapter 2. Bivariate copula functions
Theory: groundedness and the 2-increasing property, Sklar's theorem (every joint distribution is a copula of its margins,
unique for continuous margins), conditional probabilities as partial derivatives of the copula, comonotone and
countermonotone pairs, invariance of the copula under increasing transforms (prices and log-returns share one copula),
survival copulas, densities, and the appendix proofs. Practical pieces:

#### 5. Pricing a two-asset digital and its bounds (Ch. 2.3.4)
- Example (marginal probabilities 0.9394 and 0.0606): any dependence gives a price between 0 and 0.0606; independence
  0.0569; a Frank copula fitted to DAX / FTSE (parameter 4.469) 0.0231. The same Frechet bounds give joint default
  probabilities for two firms from rating tables.
- Signal source: volatility / options structure. NSE: no listed two-index digitals. Not tested by us.

#### 6. Value at Risk of a two-asset portfolio through a copula (Ch. 2.3.4, Tables 2.2-2.4)
- What it computes: the loss quantile of w x A + (1 - w) x B using each asset's own distribution and a copula (the
  conditional-probability integral).
- Book example: Student-t margins (5 and 6 degrees of freedom), Frank copula with negative dependence (-3). With an equal
  split the 99% VaR is -1.792; with normal margins of the same sd it is -1.622 (fat tails show only at the high quantile,
  95% barely moves); with independence instead of negative dependence it is -2.217.
- Inputs: each position's return history and a fitted copula. How we could use it: a 95% / 99% one-day loss for the combined
  paper book (several arms on several indices) that respects fat tails and joint moves, to compare with the -Rs 13k month /
  -Rs 40k drawdown account stop. Signal source: none (risk measurement). NSE / MCX / crypto: yes for any book.
- Our research: we have never computed a VaR for the arms' book. Not tested by us.

#### 7. VaR trade-off curves between two desks (Ch. 2.4.4)
- What it computes: the pairs of losses (x on FTSE, y on S&P 100) whose joint probability is 1%, under independence,
  perfect co-movement, perfect hedge and a fitted Clayton copula (parameter 1/2, daily 1995-2000). Near the independence
  curve, moving capital between desks changes the joint loss chance; near the co-movement curve it does not.
- How we could use it: the same curve for two arms (for example Liquidity 15+5 and R72 Breadth) shows whether splitting lots
  between them diversifies anything. Not tested by us.

#### 8. Common-shock (Marshall-Olkin) dependence (Ch. 2.5.1, 3.2.5)
- What it computes: two names each hit by their own random shocks plus one shared shock. The shared shock creates a real
  chance of both failing at the same instant (a singular part of the copula) and upper tail dependence equal to the smaller
  shock share. Example: own shocks every 2 and 1 years, shared one every 4 years; joint survival beyond 3 years 0.5248%.
- How we could use it: a mental model for our arms: one market shock (a 09:30 shock bar, a Budget day) hits every
  breakout arm on every index at once. Not tested by us.

#### 9. VaR bounds with no assumption about dependence (Ch. 2.7, 4.6)
- What it computes: given only each asset's own loss distribution, the best and worst possible distribution of the sum
  (Makarov / Frank-Nelsen-Schweizer bounds, computed numerically by the Williamson-Downs algorithm), so a worst-case VaR.
  The worst case is not the "everything moves together" case.
- Book backtest (Luciano-Marena): six stock indices, daily 30 Dec 1994 - 20 Apr 2000, equal-weight pairs. At 95%, 35
  exceedances were expected: the worst-case bound was exceeded 18-32 times (slightly conservative), the normal VaR 56-65
  times (p 0.00). At 99%, 7 expected: bound 4-10, normal 23-35.
- How we could use it: a dependence-free worst day for the whole arms book from each arm's own daily P&L, as a check on the
  account stop. Signal source: none (risk). Not tested by us.

### Chapter 3. Market comovements and copula families

#### 10. Rank dependence instead of linear correlation (Ch. 3.1)
- What it computes: Kendall's tau (share of concordant minus discordant pairs) and Spearman's rho (correlation of ranks)
  depend only on the copula, reach +1 / -1 exactly at perfect co-movement, and are unchanged by any increasing transform.
  Linear correlation is not: its attainable range depends on the margins (two lognormals with volatilities 0.4 and 0.6 can
  only reach -0.45 to 0.57 even when perfectly dependent), zero correlation does not mean independence, and one series can
  be an exact function of the other with zero correlation.
- Market example: risk-neutral margins from MIB 30 option smiles (Shimko's quadratic smile fit) showed that correlations
  above about 0.7 with other indices were inconsistent with the smile, so 0.7 was already near the ceiling.
- Book numbers (weekly averages, Jan 1992 - Jun 2001, 248 weeks): DAX / S&P 500 Kendall 0.44, Spearman 0.67, Pearson 0.67.
- For a Gaussian copula, Kendall = (2/pi) arcsin(rho) and Spearman = (6/pi) arcsin(rho/2).
- How we could use it: report NIFTY / BANKNIFTY and arm-to-arm dependence as Kendall / Spearman, not only Pearson.
  Signal source: underlying price (descriptive). Not tested by us (our R102 reports Pearson only).

#### 11. Tail dependence coefficients (Ch. 3.1.5)
- What it computes: upper and lower tail coefficients as defined in method 4; a survival copula swaps the two tails; a mix of
  p x perfect co-movement and (1 - p) x independence has both coefficients equal to p.
- Positive quadrant dependence: the joint chance of both being low is never below the independent chance.
- How we could use it: the empirical version, lambda(v) = share of days A is in its worst v given B is, at v = 5% and 1%.

#### 12. Copula families and what their tails do (Ch. 3.2, Table 3.4-3.5)

| family | dependence range | tail dependence | Kendall's tau |
|---|---|---|---|
| Gaussian (correlation rho) | full | **none** for rho < 1 | (2/pi) arcsin rho |
| Student t (rho, degrees of freedom) | full | both tails, equal, > 0 for any rho > -1 | as Gaussian |
| Frechet / mixture | full / one side | mixture: both tails = weight on perfect co-movement | from the weights |
| Gumbel | positive only | upper tail only, 2 - 2^(1/alpha) | 1 - 1/alpha |
| Clayton | full | lower tail only, 2^(-1/alpha) | alpha / (alpha + 2) |
| Frank | full | none | Debye-function formula |
| Marshall-Olkin | positive | upper tail = min(m, n) | mn / (m - mn + n) |

- The book's Figure 3.17 gives Gumbel, Clayton and Frank the same Kendall's tau (0.75) with very different tails. So one
  correlation number cannot say how often two markets crash together.
- Signal source: none (model choice). Not tested by us.

### Chapter 4. Multivariate copulas
Theory: the n-dimensional versions of Chapter 2 (the lower Frechet bound is no longer a copula above two dimensions), n-asset
digitals (example: DAX, DEM/USD and the German 10-year bond, Gaussian correlations 0.3035, 0.1998, 0.0551), orthant
dependence, and the n-dimensional Gaussian, Student t, dispersion and Archimedean copulas (one parameter for every pair, a
real limit). One practical point: the chance that the maximum of n outcomes is below a level is the copula of the n single
chances, and the minimum follows from the survival copula. Applied to us: "all of today's arms lose more than Rs X" is a
copula value, not the product of the single chances.

### Chapter 5. Estimation and calibration from market data

#### 13. Fitting a copula: exact ML, IFM, CML, empirical and kernel copulas (Ch. 5.2-5.5)
- What it computes:
  - Exact ML fits margins and copula together.
  - IFM ("inference functions for margins") fits each margin first, then the copula on the fitted probabilities; standard
    errors by jackknife or block bootstrap.
  - CML ("canonical ML") replaces the margins by ranks (empirical distribution) and fits only the copula. For a Gaussian
    copula it is simply the correlation of normal scores (book: DAX / S&P 500 0.6494).
  - Deheuvels' empirical copula (rank counts) and Scaillet's kernel copula need no family at all. Kernel copula on DAX / S&P:
    Spearman 0.5396, Blomqvist 0.3737.
- Book observation: the normal fits the middle of the joint distribution well and the tails poorly.
- Book application: rolling 250-day kernel-copula VaR at 99% on S&P 500 / US 10-year bond mixes, 1,000 windows (18 Aug
  1997 - 15 Jun 2001). It captured fat tails better than normal VaR, the more so with more of the fat-tailed asset (shown as
  charts only; no exceedance counts given).
- Inputs: aligned return or daily P&L series. How we could use it: CML on NIFTY / BANKNIFTY 5-minute and daily returns and
  on the arms' daily P&L; compare Gaussian, t, Clayton and Gumbel fits by likelihood. Signal source: underlying price
  (descriptive) / arms' P&L. Not tested by us.

#### 14. Calibration by Kendall's tau and a goodness-of-fit check (Ch. 5.6-5.8)
- What it computes: estimate Kendall's tau, invert it to the parameter of an Archimedean family (Genest-MacKay), and compare
  the fitted and empirical distribution of the joint-probability variable (Genest-Rivest K-function) by mean-square error.
  Each margin is checked by testing that its probability transform is uniform and independent (Diebold-Gunther-Tay); the
  joint fit by region "hit" tests (Patton, after Christoffersen's VaR test).
- How we could use it: a cheap check of whether a Gaussian dependence model is acceptable for the arms' P&L. Not tested by us.

#### 15. Time-varying (conditional) copula (Ch. 5.9)
- What it computes (Patton): margins with GARCH(1,1) volatility, and a copula correlation that moves each day with its own
  last value and the latest product of normal scores (held inside -1..1 by a tanh-type map). GM / IBM daily: GARCH
  persistence 0.93 / 0.91 and a visibly moving correlation.
- How we could use it: dependence between indices, and between arms, is not constant; a rolling or GARCH-type copula would
  show whether NIFTY / BANKNIFTY dependence rises on high-VIX days. Signal source: underlying price. Not tested by us.

### Chapter 6. Simulation of market scenarios

#### 16. Monte Carlo with copulas (Ch. 6.1-6.5)
- What it computes: joint scenarios with any margins and any copula.
  - Gaussian: Cholesky of the correlation matrix, normal draws, then normal probabilities, then each margin's quantile.
  - Student t: the same, with one shared chi-square scale per scenario. That shared scale is what makes joint extremes.
  - Archimedean: conditional sampling (invert the copula's partial derivative step by step; closed form for Clayton, a root
    search for Gumbel and Frank), or the Marshall-Olkin frailty method (one shared latent draw: gamma for Clayton, positive
    stable for Gumbel).
- Book example: ABN Amro / Bayer / Renault daily (2 Jan 2001 - 30 Jul 2002) needed a t copula with about 4 degrees of
  freedom; Clayton showed lower-tail clustering, Frank none.
- How we could use it: simulate a month of the multi-arm paper book with each arm's own P&L distribution and a fitted copula,
  to estimate the chance of breaching -Rs 13k in a month or -Rs 40k drawdown. Our past bootstraps (H15) resampled
  calendar blocks, which keeps the real dependence but only the months we have. Signal source: none (risk). Not tested by us.

### Chapter 7. Credit risk applications
Credit derivatives explained (CDS premium and default legs, recovery 30% convention, basket default swaps, CDOs and the
waterfall). Default times are joined with a Gaussian (Li / CreditMetrics), t, Clayton or Frank copula, calibrated to equity
returns through t-GARCH margins and a Kendall-based correlation matrix; hazard rate = CDS spread / (1 - recovery).

#### 17. Copula choice and the loss tail of a basket (Ch. 7.4)
- Book application, EuroStoxx50 synthetic CDO (50 names x 10 million euros, analysis date 30 Aug 2002): moving from a
  Gaussian copula to a t copula with 8 degrees of freedom raised the super-senior tranche's expected loss from 496,364 to
  795,313 euros and its 99% VaR from 20,072,488 to 28,117,222. The Clayton copula (lower tail only) gave the senior
  tranche almost nothing (3,523), and the authors set it aside because it misses the upper tail.
- Four-name first-to-default basket: fair spread 2.34% (Gaussian), 2.53% (t, 4 degrees of freedom), 1.93% (Clayton), 2.03%
  (Frank), against a contract spread of 270 bp. The authors prefer the t copula as closest to market prices.
- Robust correlation (Ch. 7.5.4): Pearson is poor for fat-tailed data; use the Kendall's tau matrix (Lindskog) or the
  Spearman matrix (positive definite by construction).
- How we could use it: the lesson carries over. How much the worst case of a book of many correlated positions depends on
  the copula, far more than its average. Signal source: none. NSE: no credit products. Not tested by us.

### Chapter 8. Option pricing with copulas

#### 18. Two-asset digitals and super-replication bounds (Ch. 8.2-8.3)
- What it computes: the four two-asset digitals (both up, up/down, down/up, both down) are tied by arbitrage, and each is a
  copula of the single digitals. With no view on dependence, the Frechet bounds are static super-replication prices built
  from single digitals and a bond. In an incomplete market there are two layers of uncertainty: each margin, then the copula.
- Signal source: volatility / options structure; NSE no listed products. Not tested by us.

#### 19. Price-volatility dependence by copula (Ch. 8.2.2)
- What it computes: a put price that integrates Black-Scholes over a volatility distribution joined to the price by a copula;
  with independence it is Hull-White.
- How we could use it: the NIFTY spot / India VIX joint behaviour (VIX up when spot falls) as a copula, behind the put skew
  R102 measured (+2.56 points on 91% of days). Not tested by us.

#### 20. Counterparty risk and wrong-way risk (Ch. 8.4)
- What it computes: a counterparty-risky ("vulnerable") option = risk-free option minus loss-given-default x a copula of
  the exercise chance and the default chance. Independence gives option x (1 - expected loss); perfect dependence gives
  closed forms and a hedge with credit protection. Vulnerable call + put from one writer = that writer's risky bond.
- Book numbers: a one-year at-the-money call from a Baa3 writer (expected loss 0.231%, recovery 55%, volatility 20%).
  Counterparty risk is 0.231% of the option value under independence and 2.24963% under perfect dependence. For a Caa3
  writer it is 59.66535% under perfect dependence. Per billion of underlying: 184,005 at the money under independence,
  1,791,961 under perfect dependence. Super-hedge cost on a 1 million euro ATM call: 82.80 (independence), 878.52 (perfect
  dependence), 480.66 at Spearman 50%.
- How we could use it: NSE index options are cleared and guaranteed, so this does not apply there. It does apply to crypto:
  a crypto exchange is most likely to fail exactly when crypto crashes, which is when a long put or a short-side profit pays
  (wrong-way risk). Signal source: none (counterparty risk); crypto yes. Not tested by us.

#### 21. Rainbow, exchange and Everest options (Ch. 8.5)
- What it computes: a call on the lower of two assets = integral above the strike of the "both above" survival copula; a call
  on the higher one = two calls minus the call on the lower (Stulz); a put on the higher one integrates the "both below"
  copula; a rainbow put-call parity; Margrabe's exchange option = asset minus a zero-strike call on the lower. The Frechet
  bounds turn into closed-form super-replicating portfolios of single-asset calls and call spreads.
- Everest note on MIB 30 / Nikkei 225 (6 months, smiles fitted): zero-return protection on 1 million cost 24 cents under
  perfect negative dependence, 25,773.27 under independence and 51,546.31 under perfect positive dependence; the delta rises
  with dependence.
- Signal source: volatility / options structure. NSE: no listed rainbow options (Bennett's method 18 covers worst-of /
  best-of OTC structures). Not tested by us.

#### 22. Barrier options as a two-event problem (Ch. 8.6)
- What it computes: a barrier option pays when the option ends in the money and a trigger event happens (or not), so its
  price is a copula of the two chances. Knock-in + knock-out = vanilla + rebate; a barrier put-call parity uses asset-or-
  nothing and cash-or-nothing digitals. No-touch probabilities come from the reflection principle. The implied "reflection
  copula" between a GBM's final value and its running minimum has Spearman 0.741 (1 year), 0.5842 (5 years) and 0.473 (10
  years), whatever the moneyness.
- Warning from the book: an arbitrary copula for a barrier on the same asset gives a wrong price; the copula must match the
  price process.
- How we could use it: the chance that NIFTY touches a level today and still closes beyond the strike, for Jarvis context
  on stop-loss-touch versus expiry outcome. Signal source: underlying price / options structure. Not tested by us.

#### 23. Monte Carlo basket option, and where the fat tails sit (Ch. 8.7)
- Basket call on the average quarterly return of S&P 500, FTSE 100, DAX, Nikkei and AEX (strike 3%, valued 31 Mar 2003),
  margins t-GARCH on quarterly data Jun 1984 - Mar 2003.
  - Historical simulation: 0.021.
  - t copula with t-GARCH margins (copula: about 10 degrees of freedom): 0.013.
  - t copula with normal margins: 0.020. Here the copula needed about 2 degrees of freedom, because it absorbed the fat
    tails the normal margins left out.
- Lesson: if the margins are fitted badly, the dependence model soaks up the error and looks more "tail dependent" than it
  is. The authors call the 0.013 price more precise. They give no out-of-sample check, and it is the one furthest from the
  historical simulation.

## Performance claims
- Copula VaR captures fat tails better than normal VaR (Ch. 5.5, 1,000 rolling windows): shown only as charts; no exceedance
  counts or tests given.
- Normal VaR on index pairs is over-optimistic: 56-65 exceedances against 35 expected at 95%, 23-35 against 7 at 99%; the
  dependence-free worst-case bound was 18-32 and 4-10 (Ch. 2.7, Table 2.5, with p-values). This is the book's one real
  backtest.
- The t copula is said to give more realistic CDO and basket prices: judged by closeness to one contract's market spread (270 bp),
  one date, no test.
- The kernel copula is said to fit the data better and more flexibly: no test statistic given.
- No trading-profit claims anywhere in the book.

## Risk, sizing and psychology
- The book's risk message: diversification is measured by the copula, not by correlation. A basket of strongly dependent
  positions behaves like one big position (the "definitely undiversified" first-to-default example); joint crashes can be
  likely even when each market alone looks ordinary; and the worst case of a book rests on the tail of the dependence.
- It offers conservative tools when dependence is unknown: Frechet bounds for prices, Makarov / Williamson-Downs bounds for
  VaR, and super-hedges priced at the two extremes and mixed in between.
- Nothing on position sizing rules, Kelly, stops or trader psychology.

## How it relates to our research

**Tail dependence and NIFTY / BANKNIFTY (index_facts `bn-is-nifty-times-1-16`).** Our fact: daily correlation 0.877 and beta
1.157 over 5,086 days (2006-2026); 0.842 / 1.094 on 89,575 five-minute bars (DEEP_NIFTY_BANKNIFTY.md 1.4). That is a
Pearson number. The book's point is that it does not say how often both indices have their worst days together.
- What 0.877 means under a Gaussian copula (our arithmetic with the book's formulas, not a measurement):
  - Kendall about 0.681, Spearman about 0.867.
  - Given NIFTY is in its worst 5% of days, BANKNIFTY is also in its worst 5% with probability about 0.599. At 1% that falls
    to about 0.496, and at 0.1% to about 0.390. It keeps falling towards 0 in the far tail: Gaussian dependence fades in
    a crash.
- A Student t copula with the same correlation keeps a floor in the far tail: about 0.592 with 4 degrees of freedom, 0.414
  with 10.
- Which one fits our indices is an open, cheap descriptive question: the empirical lambda(v) on 5,086 days and on 5-minute
  bars, and a CML fit of Gaussian against t against Clayton.
- If it is t-like (the book found about 4 degrees of freedom for daily European stocks), then "BANKNIFTY is NIFTY x 1.16"
  holds in a crash too: holding both is not diversification on bad days.
- Not tested by us.

**Several arms at once (R84, R72, R75, the account stop).**
- R84 (HUNT_R84_BREADTH_RISK.md) is a copula problem in practice. R72's Breadth Donchian enters only when 4 or 5 of the five
  indices break together, so its positions on one signal are near-comonotone by design: one bet taken 4-5 times.
- R84 found the edge sits exactly there: signals with 4 positions made +Rs 1,89,075 (367 signals) and 5 positions
  +Rs 1,55,881 (168). The joint loss on a reversing shock bar (19 Oct, 5 of 5) is the same dependence seen from the other
  side.
- None of the 8 position-count rules passed. Every rule cut the worst day by 32% to 77%, but none beat a random subset. The
  book's framing explains why: trimming members of a near-comonotone basket removes its payoff, not its dependence.
- Size is the remaining lever, as R75 and R84 concluded: every book still breaks the -Rs 40k / -Rs 13k account stop at 1 lot
  on all five indices.
- R72 already noted Breadth is 77% Donchian's own trades (daily correlation 0.80), so running both "would double the same
  risk". That is the book's "undiversified basket" in our words.
- What the book adds that we lack is a measuring tool:
  - An empirical copula of the arms' daily paper P&L (Liquidity 15+5, R72, R51, R52 OOV, across indices).
  - From it, the chance that all of them lose on the same day.
  - A Monte Carlo month (method 16) for the chance of a -Rs 13k month at a given lot plan.
  - A dependence-free worst-case bound (method 9) as the conservative check.
- This is risk measurement, not a trading rule. Any rule it led to (smaller size when the arms' joint-tail risk is high) would
  be risk-lowering and so allowed in shadow for live and on paper, per CLAUDE.md.

**Sizing and Monte Carlo.**
- H15's sizing study used a block bootstrap of 10 month-blocks and flagged that the Kelly fraction was unstable
  (f* 0.48 / 0.14 / 1.29 on different windows).
- The book's copula simulation is the parametric alternative: it can generate tail scenarios our few months never showed.
  The book's own basket example also warns that it can mislead if the margins are wrong.
- R74's strategy map lists volatility-targeted sizing as "not tried for the index arms". A time-varying copula (method 15)
  would be its multi-arm form.

**Other links.**
- Implied distribution (method 1) and R103: the lake's ATM +-10 strikes limit how far into the tails any implied
  distribution or copula of option-implied margins can see. The stale-wing problem is the same limit.
- Price-volatility copula (method 19) and R102's put skew: consistent with the book's view that skew is joint behaviour of
  price and volatility. Not tested by us.
- Pairs / cross-index (R72's laggard catch-up C2 failed; Bennett method 19): the book does not teach copula pairs trading.
  Not tested by us.
- Crypto (RC1, RC2): BTC / ETH dependence and exchange wrong-way risk (method 20) not tested by us. RC1 only checked that
  Binance and Delta prices move together (1-hour correlation 0.987 BTC / 0.994 ETH).
- MCX: GOLD / SILVER and CRUDE / NATURALGAS dependence not tested by us.
- Bennett (`bennett-trading-volatility.md`) already covers correlation and dispersion trading (method 21) and worst-of /
  best-of exotics (method 18). This book is the mathematics under those; it is not repeated here.

## What is new to us
- Tail dependence as a separate number from correlation, and the fact that a Gaussian model (which our Pearson facts implicitly
  are) has none. We have never measured lambda(v) for NIFTY / BANKNIFTY or for the arms.
- Rank dependence (Kendall, Spearman) and its robustness for fat-tailed data; our index facts are Pearson only.
- Measuring the multi-arm book as a joint distribution: copula VaR, VaR trade-off curves between arms, the chance that every
  arm loses on one day, and a dependence-free worst-case bound. Our account-stop checks so far count past months only.
- Copula Monte Carlo for scenarios beyond the months we have, with the warning that bad margins leak into the copula.
- Time-varying dependence (GARCH margins plus a moving copula correlation).
- Wrong-way counterparty risk, relevant if crypto options on an exchange are ever traded.
- Static super-replication bounds for multi-asset options (pricing context only for us).

## Could not read
- All figures are images: the copula surfaces and level curves (Figs 2.1-2.8, 3.5-3.20), Figure 2.9 and 4.1 (VaR bounds per
  index pair; only axis labels came through), Figure 3.3 (correlation bounds) and Figure 3.18 (estimated tau, rho and alpha
  for MIB / S&P / FTSE / DAX; caption only), the kernel copula plots (Figs 5.1-5.7; Figure 5.7's trivariate table did come
  through), the VaR paths (Figs 5.8-5.10), the time-varying correlation (Fig 5.11), the simulated scatters (Figs 6.1-6.8,
  7.13-7.14), the CDO collateral pies and likelihood curves (Figs 7.1-7.12) and the Chapter 8 pricing charts (Figs
  8.1-8.9).
- Many equations lost symbols in extraction (integral signs, subscripts, the normal distribution symbol). The prose
  around them was enough to follow every method.
- Two worked numbers look inconsistent in the text itself. The Gaussian two-asset digital at 20% correlation prints as
  0.886, above its own upper bound of 0.0606. The Merton joint default example uses -0.0797 where -0.797 was stated. We
  did not rely on either.
