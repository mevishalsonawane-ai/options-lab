# Quantitative Trading: How to Build Your Own Algorithmic Trading Business - Ernest P. Chan (2008; copyright 2009)

Source: quantitative-trading.txt, 203 pages (xxvii front matter + 175 numbered + index), read in full on 25 Oct 2026
(tesseract OCR of a scan; all prose readable; MATLAB listings and the few formulas partly garbled but recoverable from
the prose; screenshots and charts unreadable, see "Could not read").
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A practitioner's guide to running a one-person statistical-arbitrage business in US stocks, ETFs and futures.
It teaches how to screen published ideas, backtest them honestly, set up a broker and an automated order system.
Its core risk tool is the Kelly formula (leverage = mean excess return / variance), with half-Kelly as the safe default.
Chapter 7 covers mean reversion vs momentum, cointegration (GLD vs GDX), half-life exits, seasonals and factor models.
Options are explicitly out of scope ("not my area"); every example is a stock, ETF or futures strategy on daily bars.

## Methods, rules and setups

Chapter by chapter. Practical methods get a full block; the business chapters get a line or two. Where a method is a
measurement or a process, not a trading rule, the block says what it computes, its inputs and how we could use it.

### Preface and Chapter 1 - The whats, whos and whys (pp. xi-8)
Quant trading = rules a computer can run in full; a head-and-shoulders call that needs judgement does not qualify. Chan
says his complex models at banks lost money and his simple ones at home made money. Start with USD 50-100k, scale by
leverage, spend about two hours a day on operations and resist intervening by hand. Nothing testable here.

### Chapter 2 - Fishing for ideas (pp. 9-29)
Teaches: ideas are easy to find (SSRN, blogs, forums) and most fail a careful backtest, but small variations (a shorter
hold, different entry and exit times) can rescue one. Pick strategies that fit your hours, programming skill and capital
(part-time: once-a-day or overnight; under USD 50k: not recommended; hedged books need twice the capital). Then screen
before backtesting, using the checks below.

#### 2.1 Sharpe ratio, information ratio and drawdown as the screen (Ch. 2, pp. 18-22)
- What it computes: information ratio = mean / sd of returns in excess of the benchmark (long-only books); Sharpe ratio
  = the same against the risk-free rate (dollar-neutral books; used by everyone for comparison). Drawdown = fall from the
  high-water mark; also the maximum drawdown and the longest time to recover (maximum drawdown duration).
- Rules of thumb (claims): a Sharpe below 1 is not a stand-alone strategy; a strategy profitable almost every month
  has a Sharpe above 2; one profitable almost every day, above 3. Few trades a year means a low Sharpe. A drawdown deeper
  than 10% or longer than four months suggests a low Sharpe. Gold futures should be judged against spot gold, not a
  stock index.
- Use for us: our reports lead with net rupees, worst day, worst month, max drawdown and green days, not Sharpe.
  Adding an annualised Sharpe and the drawdown duration to the gate tables would let Jarvis compare arms. Our account
  stop (-Rs 40k drawdown, -Rs 13k a month) is exactly the "how deep and long can you sit through" question.
- Signal source: n/a (measurement). Mechanical? yes.

#### 2.2 Transaction-cost estimate and the ES Bollinger example (Ch. 2, pp. 22-23)
- Cost model: half the bid-ask spread plus commission per side; a round trip counts twice. His figures: about 5 bp a
  side for S&P 500 stocks (10 bp a round trip), about 1 bp for the E-mini future. Slippage from order delay has either
  sign but costs on average.
- The example rule (book): on ES 5-minute bars, short when price is more than 2 moving standard deviations above its
  moving average, buy when 2 below; exit when price returns within 1 sd. Stop: none. Lookback: not stated.
- Claim: Sharpe about 3 before costs, about -3 after a 1 bp cost. That is the whole lesson: a fine-grained
  mean-reversion edge can be smaller than one tick of cost.
- Market and timeframe: S&P 500 E-mini future, 5-minute bars, intraday.
- Signal source: underlying price; maps to NSE index futures directly (NIFTY/BANKNIFTY futures), MCX futures, crypto
  perps (with a stop and a profit lock added).
- Mechanical? yes (lookback must be chosen).
- Our research: F1 (HUNT_F1_FACTORY.md, indicator table) found Bollinger %B, RSI, MACD and Supertrend on 1-minute bars
  lean to a small 15-minute reversal (IC ~ -0.05) that is gone by 60 minutes, and 0 of 20 indicator rules pass after
  costs. R102 (DEEP_NIFTY_BANKNIFTY.md 2.5): the next 5-minute bar repeats the previous sign 48.4% of the time on NIFTY
  (n 167,082), a reversal far too small to pay the Rs 98 NIFTY ATM round trip (knowledge/baselines.yaml, R16). Crypto:
  the next-hour reversal is real (AUC 0.535 BTC) but worth about 0 bps gross against a 12 bps perp round trip
  (FINDINGS.md, C1). Chan's example and our data agree: reversal exists, costs win. The futures-only version on NIFTY
  was not tested by us (rule `nifty-fut-bollinger-5m` below).

#### 2.3 Survivorship, regime shifts, data snooping and capacity (Ch. 2, pp. 24-27)
- Survivorship: databases missing delisted names inflate "buy cheap" strategies most. For us: index futures and MCX
  continuous series have no survivors problem; stock-option studies (R58, R59) would.
- Performance by year: recent years matter most (older spreads were wider; regimes change); "more data" helps only
  if the process is stationary, which markets are not.
- Data snooping: the more rules and parameters, the more the backtest flatters. Sidebar on AI: neural nets, decision
  trees and genetic algorithms overfit financial data because truly independent data is scarce; what works for him is
  few parameters, an economic reason, linear regression, conceptual simplicity, and every optimisation done in a
  backward-looking moving window and proven on unseen data. This matches R41 (FINDINGS.md "Neural networks"): index
  direction AUC 0.510-0.517 on the holdout, no trade passes, but nets forecast move size better than trees.
- Capacity: small traders should look for niches large funds cannot use (low capacity, few trades, seasonal
  commodity trades).

### Chapter 3 - Backtesting (pp. 31-67)
Teaches: replicate any published strategy yourself; platform choice (Excel, MATLAB and clones, TradeStation); data
sources; the measurements; the two big pitfalls; costs; refinement.

#### 3.1 Data hygiene (Ch. 3, pp. 36-43)
- Split and dividend adjustment by a multiplier (keeps returns intact), worked on the IGE ETF (Example 3.2).
- Survivorship-free data on a budget: save your own universe's prices every day (point-in-time). Example 3.3, a toy
  "buy the 10 lowest-priced of the 1,000 largest stocks" for 2001: -42% on complete data vs +388% on survivor-only data.
- Daily highs and lows are noisier than opens and closes; a backtest whose limit orders fill at the recorded high or low
  is optimistic. Screen data by flagging returns more than 4 sd from the mean that have no news or market move behind
  them.
- Use for us: our lake already uses point-in-time snapshots; the high/low warning applies to every study that fills
  stops or targets on 1-minute bar extremes (R103 found its "passes" were stale wing prints, LAKE stale-print audit).
  The 4-sd outlier screen is cheap to add to data checks. Signal source: n/a.

#### 3.2 Sharpe annualisation and drawdown arithmetic (Ch. 3, pp. 43-50)
- No risk-free subtraction for dollar-neutral or intraday-only books (no financing). Annualise by the square root of
  the number of periods in a year; for hourly returns in a 6.5-hour session that is 252 x 6.5 = 1,638, not 252 x 24.
  For NSE (375-minute session) the hour count is 252 x 6.25.
- Example 3.4: IGE buy-and-hold Sharpe 0.789; IGE long / SPY short 0.784. Example 3.5: max drawdown 10.53%, longest
  drawdown 497 trading days. Use for us: a definition check for any Sharpe we quote.

#### 3.3 Look-ahead bias and the truncation test (Ch. 3, pp. 51-52)
- What it is: using information not available at decision time ("buy within 1% of the day's low"; regression
  coefficients fitted on the whole sample).
- Fix: compute every signal from data lagged to the previous bar. Check: run the backtest on all data and on data with
  the last N days cut (N 10-100); positions up to the cut must be identical, or the program peeks ahead.
- Our research: our look-ahead guard (research/brain/guard.py; tests in research/brain/tests) does this and more: every
  feature is recomputed with the rows after t deleted AND with them perturbed, and values at or before t must not change;
  a test proves the guard catches a leaky feature. Chan's check is a subset of ours.

#### 3.4 Data-snooping controls (Ch. 3, pp. 52-60)
- Rules of thumb (claims, from experience, not statistics): at most five free parameters; at least 252 observations per
  free parameter (a 3-parameter daily model needs 3 years; a 3-parameter one-minute model about seven months of minutes).
- Out-of-sample: split into a training set and a later test set of similar size (at least a third of the training size).
  If the test is poor, simplify. Better: moving (rolling) optimisation, which he calls a "parameterless" model. Even
  better: average decisions over several parameter sets instead of picking the single best.
- Sensitivity analysis: vary each parameter and drop conditions one at a time; keep removing whatever does not hurt the
  test set; never add or tune anything to improve the test set (that turns it into training data). Then split capital
  across the surviving parameter sets.
- Paper trading is the ultimate out-of-sample test; the time since a strategy was published is also genuine
  out-of-sample.
- Our research: we go further than the book on every point: walk-forward with purging, one locked holdout read once,
  Benjamini-Hochberg across every idea tried and logged (research/experiments), random-entry twins, top-5-trades removed,
  2x slippage (research/data/ARCHITECTURE.md L6; R72's gates). Chan has no multiple-testing correction and no
  random-entry benchmark; his train/test split is a single split read repeatedly. Two of his ideas we use less than we
  could: parameter averaging (split size across neighbouring settings) and the explicit "drop one condition at a time"
  ablation.

#### 3.5 Pair trading GLD vs GDX (Example 3.6, pp. 55-59; spread in Ch. 7)
- Entry: daily closes of GLD (gold ETF) and GDX (gold-miners ETF). Hedge ratio = OLS slope of GLD on GDX over the first
  252 days (training set). Spread = GLD - hedge x GDX. z = (spread - training mean) / training sd. Buy the spread
  (long GLD, short GDX) when z <= -2; short it when z >= +2.
- Stop: none.
- Exit: when |z| <= 1.
- Filters: none; the pair was chosen because gold and gold miners should move together (an economic reason).
- Market and timeframe: US ETFs, daily, positions carried over days; positions lagged one day in the P&L.
- Claims: Sharpe about 2.3 in training and 1.5 in the test set, before costs; with entry 1 sd and exit 0.5 sd, 2.9 and
  2.1. Costs "do not matter much" because it trades rarely (not shown). The truncation test with a 60-day cut passed.
- Signal source: underlying price of both legs (the spread). Maps to: MCX GOLD vs SILVER futures (nearest analogue; no
  listed gold-miner basket), NIFTY vs BANKNIFTY futures, ETH vs BTC perps. Not to options except as two bought options,
  which H6 shows is a structural loser.
- Mechanical? yes.
- Our research: R94 (HUNT_R94_MCX_ADVANCED.md, idea 2) traded almost exactly this on MCX proxies: z of the GOLD/SILVER
  log ratio over 60 / 120 days, |z| >= 2, exit at z = 0, 20 days or |z| >= 3.5. GOLD/SILVER lost in design (68 trades,
  -Rs 6,26,347 at W60) and in the holdout; only 14 of 68 design trades reverted to z = 0, 14 hit the stop and 40 ran out
  of time. Base-metal pairs were positive in design but not significant (q 1.00) and failed the liquidity rule. R82 G1
  (ratio regime) and R96 R5F (ratio breakout, 27 trades, -Rs 47,162) also failed. H6 (intraday NIFTY/BANKNIFTY divergence
  with bought options): all 24 variants lost. What R94 did not do is Chan's cointegration pre-check and half-life exit
  (3.5 below and 7.5): rule `mcx-gold-silver-coint-gate`.

#### 3.6 One-day reversal across stocks (Khandani-Lo; Examples 3.7 and 3.8, pp. 61-66)
- Entry: each day, weight every stock by minus (its previous-day return - the equal-weighted market's return);
  long the losers, short the winners; dollar-neutral. Rebalance daily. No stop; exit = next rebalance.
- Claims: the original paper's Sharpe 4.47 in 2006 came from small caps; on the S&P 500 in 2006 Chan gets 0.25 before
  costs and -3.19 after 5 bp a trade. Rebalancing at the open instead of the close lifts it to 4.43 before and 0.78 after
  costs (Example 3.8): a timing variation, offered as an example of refinement.
- Signal source: underlying prices (stock cross-section). Maps to NSE F&O stocks via stock futures; not to index options.
- Our research: not tested as a stock cross-section. R102 found index direction a coin flip at every horizon. The
  close-to-open lesson fits R102's finding that the index drift is overnight (NIFTY close-to-close +0.051% a day vs
  open-to-close -0.035%, 5,086 days).

#### 3.7 Strategy refinement (pp. 65-66)
Refinements should have an economic reason (exclude news-heavy names such as pharma or pending mergers; change timing or
universe) and must improve the test set as well as the training set.

### Chapter 4 - Setting up the business (pp. 69-78)
Retail account (US Reg T: 2x overnight, 4x intraday, insured) vs proprietary firm (20x or more, not insured, the firm can
copy a profitable strategy). Choose a broker on total execution cost, not just commission; insist on an API and a paper
account. Infrastructure: a PC, broadband, a UPS, a news feed; more information does not mean better decisions (a
handicapper study); hosting for continuity. Nothing testable; our setup (Dhan API, paper arms) already covers it.

### Chapter 5 - Execution systems (pp. 79-94)
#### 5.1 Semi- vs fully automated systems and cost control (pp. 79-89)
- Semi-automated: a program writes an order file that a basket or spread trader submits; unfilled orders are cancelled
  before the close. A spread trader with a limit on the spread (not on each leg) is how he executes pairs. Fully
  automated: a loop on live data sending orders through the API.
- Cost rules (claims): avoid stocks under USD 5; keep each order under 1% of average daily volume; weight by the fourth
  root of market cap so the largest/smallest weight ratio stays near 10; slicing a big order cuts impact but adds
  slippage, so a retail trader rarely needs it.
- Use for us: the 1%-of-volume cap matters for MCX minis and illiquid strikes (R94 failed several pairs on Boss's
  liquidity rule). Signal source: n/a.
#### 5.2 Paper trading and why live diverges from backtest (pp. 89-94)
- Paper trading finds bugs and hidden look-ahead (data that is not there before the order), and teaches the strategy's
  P&L swings and timings; compare paper trades and P&L with the backtest run on the same days.
- Divergence checklist: bugs; do live trades match the backtest's; costs higher than assumed; illiquid names; then data
  snooping (strip rules; if the backtest collapses, it was snooped) or a regime shift (US decimalisation in 2001 hurt
  stat-arb; the uptick rule's removal in 2007; hard-to-borrow shorts).
- Use for us: this is our paper-arm practice (replay vs app record, H24 and baselines.yaml fill checks). Mechanical? yes.

### Chapter 6 - Money and risk management (pp. 95-113)
#### 6.1 Kelly leverage and allocation (pp. 95-103)
- What it computes: under a Gaussian assumption, the leverage that maximises long-run compounded growth is f = m / s^2
  (mean excess return over its variance, per period; independent of the time scale). For several strategies, the vector
  F = C^-1 M (inverse covariance times means). The maximum growth rate is r + S^2 / 2, where S is the Sharpe ratio, so
  growth depends on Sharpe squared, not on raw return.
- Puzzle (Example 6.1): a price that goes up or down 1% with equal odds each minute loses 0.5 bp a minute when held,
  because compounded growth is m - s^2 / 2. Risk always costs growth.
- Example 6.2 (SPY): mean 11.23%, sd 16.91%, risk-free 4%, Sharpe 0.4275, Kelly leverage 2.528, levered growth 13.14%
  vs 9.8% unlevered. Example 6.3 (OIH, RKH, RTH): F = 1.2919, 1.1723, -1.4882 (short the ETF with negative excess
  return); portfolio growth 15.29%, Sharpe 0.4751, above any single ETF (OIH alone 12.78%).
- Practice: half-Kelly is the norm (estimation error, fat tails); with a leverage cap, shrink all f in proportion.
  Rebalance at least daily: after a 10% loss on 2.528x SPY, equity 74,720 means cutting the book from 227,520 to 188,892
  (sell into the loss). Re-estimate m and s on a trailing window: for strategies held about a day, six months. When the
  number of signals varies, Kelly gives the maximum capital; staying below it is always safer.
- Inputs: per-period net returns of each arm. How we could use it: as a risk throttle, see 6.2 and rule
  `arm-trailing-kelly-throttle`.
- Our research: H15 (HUNT_H15.md) is our Kelly study, on Liquidity 15+5 from Rs 1 lakh. Half-Kelly (share of capital
  per trade BN 0.65, FIN 0.53, MIDCP 0.17) was the pre-registered choice and grew Rs 1 L to Rs 7.41 L in the holdout at
  κ 0.02 (2.69 L at κ 0.04), but with a -44% drawdown; quarter-Kelly gave P(50% drawdown) of 1-5% at half the growth;
  the SPA test over the 18 sizing variants gave p = 0.14 (sizing choice not separable), and the Kelly fraction was
  unstable (BN 1.29 in the estimation window vs 0.48 and 0.14 on other windows). Random entries with the same sizing went
  to ruin 49% of the time. Chan's warnings (half-Kelly, re-estimate on a trailing window, fat tails) are exactly what H15
  found. HUNT_FINAL / H17 / H36 deliberately use fixed lots.

#### 6.2 Worst-loss cap on leverage (pp. 105-106)
- What it computes: max leverage = (largest one-period loss you can stand) / (worst historical one-period loss). Use
  the smaller of this and half-Kelly. His case: S&P 500's worst day was -20.47% (19 Oct 1987); with a 20% tolerance the
  cap is about 1, below half-Kelly's 1.26.
- How we could use it: lots per arm <= Rs 13k (the monthly stop) / worst historical day per lot, which R72 showed is
  binding (C1's worst design day -Rs 58,613 at 1 lot on five indices). Only lowers risk, so it fits the LIVE rule in
  shadow. Signal source: n/a (sizing).

#### 6.3 Stop-loss doctrine (sidebar, pp. 106-107; again in Ch. 7)
- Claim: stops do not prevent catastrophes (gaps fill far through the stop); they help only in a momentum regime and
  hurt in a mean-reverting one. News- or fundamentals-driven moves trend (do not stand in front); moves with no news are
  usually liquidity events that revert.
- Our research: partly consistent. R82 T4 (HUNT_R82_MCX_PRECIOUS.md): a 3-ATR trailing stop on the 12-month gold/silver
  trend arm kept most of the final profit (+Rs 10.1 lakh on 34 trades) and cut the worst drawdown from -Rs 13.4 lakh to
  -Rs 1.9 lakh, but failed two gates. LIQUIDITY_STOP.md: tight premium stops on Liquidity 15+5 helped one year and hurt
  the other. R94's pair stop (|z| >= 3.5) was hit on 14 of 68 trades because the ratio trended. CLAUDE.md requires stops
  on crypto and every live trade regardless; Chan's view applies to where to put them, not whether.

#### 6.4 Model risk, software risk and gradual de-leveraging (pp. 107-108)
- Have someone else reproduce the backtest; compare live trades with backtest trades every day; lower leverage
  gradually as a model loses (a trailing Kelly estimate does it automatically) instead of shutting it in a panic.
- Use for us: matches our replay-to-the-rupee practice (R101 reproduced every B0 first). Gradual de-leveraging is
  untested for our arms (R74's map lists volatility-targeted sizing as "not tried for the index arms").

#### 6.5 Psychology (pp. 108-111)
Loss aversion, status-quo and endowment bias (hold losers, cut winners); exit a mistaken trade at once; do not retune
after one big loss (representativeness); despair (shut down or double up) and greed (scale up fast) both end in
over-leverage (LTCM; Amaranth's USD 6 billion loss on natural-gas calendar spreads). His own errors: adding USD 100
million to a strategy six months old (lost over USD 1 million); scaling an XLE vs crude-oil spread to about USD 500k
and exiting near a six-figure loss just before it reverted. Start small; scale by Kelly.

### Chapter 7 - Special topics (pp. 115-156)
#### 7.1 Mean reversion vs momentum (pp. 116-119)
- Claims: prices are close to a random walk; trading only pays where they revert or trend for a horizon. Stocks revert
  unless earnings expectations change. Momentum comes from slow news diffusion (post-earnings drift), large orders
  worked over time (most short-term momentum) and herding; its horizon is unpredictable. Competition removes
  reversion opportunities and shortens momentum horizons. Bad quotes and survivorship inflate reversion backtests.
- Our research: R102 2.5 shows tiny reversal at 5-15 minutes and tiny continuation at 1 hour to 1 day on both indices,
  nothing tradeable after costs. R86 (big players' footprints, 0 of 23) did not find tradeable large-order momentum.

#### 7.2 Regime switching and the GS turning-point example (pp. 119-126)
- Claims: volatility regimes are the most predictable (GARCH), useful to option traders; Markov / hidden-Markov regime
  models are "generally useless" for trading because they assume fixed switching odds; turning-point models mine many
  inputs instead.
- Example 7.1 rule (GS daily): inputs are the one-day % change (thresholds +-1% and +-3%) and a 10-day high/low
  condition (a new 10-day low just before a big up day signals a turn up; a new high before a big down day, a turn
  down); holding periods 1, 5, 10, 20, 40, 60 days; a perceptron reweights the rules daily on a 50-day moving window.
  Stop: none. Claim: +37.93% gross over a six-month backtest with 89 round trips vs buy-and-hold 15.77%; best single
  rule (1%, 10-day hold) 18.55%. Six months, no costs, built on a vendor platform he consults for (disclosed).
- Signal source: underlying price. Maps to NIFTY/BANKNIFTY daily (buy ATM CE / PE) and MCX futures.
- Mechanical? partly (the perceptron's settings are not given).
- Our research: R102 found size predictable and direction not; R41's networks gave index direction AUC 0.510-0.517.
  Volatility regimes: R102 VIX vs realised, R88 (size yes, 225 of 305 hold). The 10-day-extreme-then-big-reversal-day
  pattern is close to R78 (gold daily high/low reversal) and R87 candle shapes (no direction). Not tested in this form.

#### 7.3 Stationarity, cointegration and the CADF test (pp. 126-133)
- What it computes: a series is stationary (I(0)) if it does not drift away; two non-stationary prices are cointegrated
  if some long/short mix of them is stationary. Test (cointegrating augmented Dickey-Fuller): regress one price on the
  other, test the residual for a unit root. GLD vs GDX: t -3.357 against critical values -3.819 (1%) and -3.343 (5%), so
  cointegrated at better than 95%; hedge 1.6766 GDX per GLD. KO vs PEP: t -2.14, above the 10% critical value -3.038,
  not cointegrated, yet daily returns correlate at 0.4849.
- Key point: correlation (short-term co-movement of returns) is not cointegration (long-run tie of prices). A stationary
  spread makes reversion trading pay as long as it stays stationary; reversion can work without it too. Commodity
  currencies (CAD/AUD) and futures calendar spreads are given as natural cointegrated pairs.
- Use for us: a rolling CADF gate before any pair trade (rule `mcx-gold-silver-coint-gate`); R94 skipped it. R102:
  BANKNIFTY vs NIFTY daily correlation 0.877, beta 1.157 (5,086 days), which says nothing about cointegration. R94 found
  MCX calendar spreads do not revert at all before costs (gross about Rs 0 a trade, 16 pairs): Chan's "simplest
  cointegrating pair" does not hold on MCX minis.

#### 7.4 Factor models and PCA (pp. 133-139)
- Returns = exposures x factor returns + specific return (APT; Fama-French: beta, size, book-to-price). A good model
  explains 30-40% of monthly variance (1,000 stocks, 50 factors). Factor returns tend to persist, which makes them
  predictive; they break when investors change how they value stocks (growth vs value, 2007).
- Example 7.4: PCA on S&P 600 small caps (252-day lookback, 5 factors, long top 50 / short bottom 50 expected returns)
  gives a negative average return; the factor-momentum assumption fails there.
- Maps to NSE stocks only; not to index options. Not tested by us.

#### 7.5 Exit strategies and the Ornstein-Uhlenbeck half-life (pp. 140-143)
- Four exits: a fixed holding period (the default; for momentum the best hold shrinks over time and is snoop-prone to
  estimate from few trades); a target (for reversion: the mean); the latest opposite entry signal (in a momentum model
  this acts as a stop with no extra parameter); a stop price.
- Half-life: regress the daily change in the spread on yesterday's spread minus its mean; the slope is -theta and the
  half-life of a deviation is ln 2 / theta. It uses every day of data, not just trade days, so it is far more robust
  than optimising the holding period. GLD/GDX: about 10 days (10.0037). Exit at the mean or after the half-life,
  whichever first.
- Claim: a reversion model never asks for a stop (a loss only strengthens its signal); a stop there sells at the worst
  moment, unless news has turned the market into a trend.
- Signal source: underlying price (the spread). Use for us: the half-life gives a pair's time stop without a backtest
  search; it also gives a quick plausibility check (a half-life of months on daily data means "no reversion to trade").
  Our research: R94's 20-day time exit was a fixed guess; 40 of 68 GOLD/SILVER trades timed out. Not tested by us.

#### 7.6 Seasonal trades (pp. 143-151)
- January effect (Example 7.6): long the prior year's worst-decile small caps, short the best decile, from the last
  close of December to the last close of January, 5 bp a side: -2.44% (Jan 2006), -0.68% (Jan 2007), +8.81% (Jan 2008).
  Year-on-year same-month momentum (Heston-Sadka, Example 7.7): over 13% a year before 2002 in the paper; Chan's test on
  S&P 500 names gives a negative Sharpe (-0.1055), worse in the last five years. His verdict: stock seasonals have faded.
- Gasoline (sidebar, p. 149): buy 1 NYMEX RB May contract at the close of 13 April (next trading day if a holiday),
  sell at the close of 25 April (previous trading day if a holiday). Stop: none. Claim: profitable every year 1995-2008
  (2007-08 traded live as 2 mini contracts); per-contract P&L 118 to 6,985 dollars, worst open drawdown -9,816 dollars
  (2007).
- Natural gas (sidebar, p. 150): buy the NYMEX June NG contract at the close of 25 February (next trading day if a
  holiday), exit 15 April (previous trading day if a holiday). Stop: none. Claim: profitable 14 years running,
  1995-2008; per-contract P&L 450 to 10,137 dollars, worst open drawdowns -5,550 (2003) and -7,470 (2007). Warns that
  gas is violent (Amaranth, Bank of Montreal USD 450 million) and suggests the half-size mini.
- His own caveats: one trade a year means few data points and a high snooping risk; shift the dates to check
  robustness; keep only seasonals with an economic reason (summer driving, power for air conditioning).
- Signal source: the calendar (no price signal), trading the future. Maps to MCX NATURALGAS / NATGASMINI (priced off
  Henry Hub, so the same season applies; contract months differ). No MCX gasoline; crude is the nearest and has no
  claim here.
- Mechanical? yes.
- Our research: R83 E9 (HUNT_R83_MCX_ENERGY.md) tested natural-gas month-of-year seasonality on MCX NATGASMINI: 14
  trades, -37,413 rupees, worse than simply staying short (+14,687, the contango carry). MCX_GUIDE.md: MCX NG monthly
  means 2012-2026 Dec -10.7%, Feb -5.7%, Aug +3.7%. R94 idea 4: bullion seasonals (Diwali, Akshaya Tritiya, wedding
  months) did not beat random windows of the same years (lowest design q 0.70). Chan's exact NG window (late February to
  mid-April) was not tested: rule `mcx-natgas-spring-seasonal`.

#### 7.7 High-frequency strategies (pp. 151-153)
- Claim: anything flat overnight; many small bets give a high Sharpe (law of large numbers), and losses can be cut
  fast. But testing needs bid/ask and sometimes order-book history, costs dominate, and speed of execution decides much
  of the P&L. Use for us: our intraday arms are in this class; H24's real spreads and R7/OF1 order-flow work are the
  relevant tests. Not a rule.

#### 7.8 High leverage vs high beta (pp. 153-154)
- Claim: since growth goes with Sharpe squared, a levered low-beta book beats an unlevered high-beta one; risk parity
  (23% stocks / 77% bonds levered 1.8x, Qian) vs 60/40. Fat tails argue for caution. Maps loosely to option buying:
  a far-OTM "hero" option is the high-beta choice (R43: 0 of 88 hero rules pass; R50: 0 of 28). Not tested in this form.

### Chapter 8 - Conclusion (pp. 157-162)
Independent traders win through capacity: small, short-horizon, liquidity-providing strategies that a billion-dollar
fund cannot use; institutions face crowding, complexity, constraints and bad incentives (SocGen's USD 7.1 billion rogue
loss). Grow by adding strategies, frequencies, markets and automation; edges decay, so research never stops.

### Appendix - MATLAB survey (pp. 163-168)
Arrays, indexing, built-ins, free toolboxes (LeSage econometrics, GARCH). No trading content.

## Performance claims
- Example strategies (all before costs unless stated; no sample sizes given except where noted): ES 5-minute Bollinger
  Sharpe about 3, about -3 after 1 bp; GLD/GDX Sharpe 2.3 training / 1.5 test (2.9 / 2.1 with tighter thresholds), one
  pair, about 1.5 years of test data, costs not deducted; Khandani-Lo 0.25 / -3.19 after costs (S&P 500, 2006), 4.43 /
  0.78 at the open; GS turning points +37.93% in six months on 89 round trips; gasoline 14 of 14 years and natural gas 14
  of 14 years profitable (2007-08 partly live). Evidence: tables and code, no costs on most, no significance tests, no
  correction for the many variants tried.
- Personal: his first independent strategy was profitable in its first month; the year ended with a small loss after
  the XLE/crude spread. Anecdote only.
- Rules of thumb: Sharpe below 1 not stand-alone; above 2 for monthly-profitable, above 3 for daily-profitable;
  max five parameters; 252 observations per parameter. Experience, no evidence.

## Risk, sizing and psychology
Kelly f = m / s^2 for one strategy, C^-1 M for several; trade half-Kelly; rebalance daily so size shrinks after losses
and grows after gains; re-estimate on a trailing window (six months for day-long holds); cap leverage at the tolerable
one-period loss divided by the worst historical one-period loss; prefer gradual de-leveraging of a failing model to an
abrupt shutdown; stops only in momentum trades. Behaviour: do not override the system, do not retune after one loss,
beware despair and greed (both end in over-leverage), start small and scale slowly; outside income helps patience.

## How it relates to our research
- **Agrees with us (method).** Simple models, few parameters, real costs, out-of-sample testing, paper trading before
  money, replicating claims ourselves: all are our L6 gates (research/data/ARCHITECTURE.md). We are stricter: purged
  walk-forward, a holdout read once, BH across every logged idea, random-entry twins, top-5 removal and 2x slippage.
  Chan's look-ahead truncation test is weaker than our guard (delete AND perturb future rows).
- **Agrees with us (results).** Costs erase fine-grained reversal (his ES Bollinger and Khandani-Lo; our F1 15-minute
  reversal IC ~ -0.05, C1 crypto reversal worth about 0 bps vs a 12 bps round trip). Neural nets overfit direction (R41).
  Kelly helps growth but needs halving and is unstable (H15: -44% drawdown at half-Kelly; f* BN 1.29 vs 0.48 / 0.14).
- **Already rejected by us, close cousins.** Gold/silver ratio reversion (R94 idea 2, R82 G1, R96 R5F); MCX calendar
  spreads (R94 idea 1); NIFTY/BANKNIFTY intraday divergence pairs with bought options (H6, 24 variants lost);
  natural-gas month-of-year seasonality (R83 E9); bullion festival seasonals (R94 idea 4); stock seasonals (not ours,
  but Chan himself rejects them).
- **Contradicts or differs.** Chan treats futures calendar spreads as the easiest cointegrated pair; R94 found MCX
  spreads do not revert at all. Chan reports seasonals as alive in US energy futures; on MCX the gas month-of-year rule
  lost. He calls stops useless for reversion; our crypto rule (CLAUDE.md) and Boss's live rules require a stop anyway.
- **Not tested by us.** A cointegration (CADF) gate and half-life exit on pairs; NIFTY vs BANKNIFTY futures as a daily
  pair; Chan's exact late-February natural-gas window; trailing-Kelly / worst-loss leverage throttles on the arms;
  parameter averaging across neighbouring settings; ETH/BTC as a pair.

## What is new to us
1. **Half-life as the pair's clock.** Estimate the reversion speed from every day of data (OU regression) and use
   ln 2 / theta as the time exit; reject pairs whose half-life is too long to trade. R94 used a fixed 20 days.
2. **Cointegration is not correlation.** A rolling CADF test before trading any spread; R94 traded the ratio without
   checking it was stationary in the training window.
3. **Trailing-Kelly risk throttle.** Size each arm by its trailing six-month mean / variance, capped by half-Kelly and by
   the worst-day cap; a losing arm shrinks to zero without a manual shutdown. Lowers risk only, so it can run in shadow
   on live.
4. **Parameter averaging.** Split one lot's worth of signals across neighbouring settings instead of the single best,
   to make live closer to the backtest.
5. **The "opposite entry signal is the exit" rule** for momentum arms (a stop with no extra parameter).
6. **The capacity argument.** Our edge, if any, is in small size and short holds; it also explains why impact flattens
   H15's curve above about Rs 20 lakh.

## Could not read
- Figures: 2.1 (drawdown sketch), 3.1 (cumulative returns, labelled Figure 3.4 by the OCR), 5.1-5.2 (diagrams, partly),
  7.1-7.3 (Alphacet screenshots, unreadable), 7.4-7.6 (charts; captions and axis labels only).
- MATLAB listings: partly garbled (operators, quotes, line breaks); their logic was recoverable and matches the prose.
  The rule box in Figure 7.2 reads ">= 1" and ">= -1"; the second is almost certainly "<= -1" (the short side).
- Formulas (Kelly F = C^-1 M, the growth formula, the OU equation) came out garbled but are stated in words in the text.
- Example 7.7's printed "average annual return -0.9167" is in units of summed positions, not a percentage of capital;
  only its sign and the Sharpe (-0.1055) are meaningful.
- Nothing else; every chapter, the appendix, bibliography and index were read.

## R104 results (25 Oct 2026)

Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in `research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:

- `book-fmchan-01`: tested (R104): IDXF: 6113 trades, win 52%, net -Rs 2,649,139 design, holdout -Rs 1,004,805 (fail)
- `book-fmchan-02`: tested (R104): DLY: 4 trades, win 50%, net Rs 53,482 design, holdout Rs 0 (fail)
- `book-fmchan-03`: tested (R104): MCXD: 2 trades, win 0%, net -Rs 16,836 design, holdout Rs 0 (fail)
- `book-fmchan-04`: tested (R104): MCXD: 10 trades, win 40%, net -Rs 4,026 design, holdout Rs 25,181 (fail)
- `book-fmchan-05`: tested (R104): MM: drawdown cut 59%, net cut 27% design (fail)
- `book-fmchan-06`: tested (R104): MM: drawdown cut 97%, net cut 101% design (fail)
- `book-fmchan-07`: tested (R104): DLY: 349 trades, win 53%, net -Rs 426,187 design, holdout Rs 219,315 (fail)
- `book-fmchan-08`: tested (R104): CRY: 0 trades, win -, net Rs 0 design, holdout Rs 0 (fail)
- `book-fmchan-09`: tested (R104): CRY: 6346 trades, win 41%, net -Rs 834,590 design, holdout -Rs 171,924 (fail)
