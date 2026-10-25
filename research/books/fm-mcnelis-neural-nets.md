# Neural Networks in Finance: Gaining Predictive Edge in the Market - Paul D. McNelis (2005)

Source: neural-networks-in-finance-gaining-predictive-edge-in-the-ma.txt, 261 pages, read in full on 25 Oct 2026 (text
layer, preface to index; equations came through partly garbled and all charts are images, see "Could not read").
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
An econometrics textbook (Elsevier Academic Press) on small neural networks as a nonlinear alternative to the linear regression model.
Part I (chapters 2-4) covers network types, how to fit them (genetic algorithm plus gradient search) and how to judge them in and out of sample.
Part II applies them to artificial data (chaos, jump-diffusion, regime switching, Black-Scholes implied volatility), US car output, junk-bond spreads, Hong Kong and Japan inflation, credit-card and bank-failure classification, and swaption implied-volatility term structures.
The benchmark is always the linear model; the claim is a "predictive edge" in forecast accuracy, not trading profit.
The author says plainly that the real payoff of networks in markets is likely VOLATILITY forecasting, not return forecasting; the book never forecasts stock returns or intraday data.

## Methods, rules and setups

The book teaches no trading rules: no entries, stops or targets. It teaches forecasting models and, more usefully for
us, a full kit for judging whether a forecast is real. Each chapter is summarised below; practical methods get a block.

### Chapter 1. Introduction
Forecasting, classification and dimension reduction as three jobs; markets react with thresholds (little reaction to
small changes, a big one past a critical level), which a squashing function captures; model risk (implied) and
forecast risk (realised) may diverge and either can be an input. Notes that options are priced off the underlying's
return process, so better forecasts of that process mean better prices. No method block.

### Chapter 2. What are neural networks?
Linear and autoregressive (ARX) models; GARCH-in-mean (volatility feeds the mean, volatility clusters); polynomial and
orthogonal-polynomial (Chebyshev, Hermite, Legendre, Laguerre) approximations and the curse of dimensionality; then the
network family.

#### 2.1 Feedforward network (multilayer perceptron, MLP) (Ch. 2.4)
- What it computes: a forecast of one variable from several inputs, through one hidden layer of "neurons" that each
  squash a weighted sum of the inputs (logistic, tanh or cumulative-normal) and are then combined linearly.
- Inputs: lagged values of the target and other variables, scaled to [0,1] or [-1,1].
- Book's advice: one hidden layer with two or three neurons is usually enough; the "plain vanilla" logistic MLP is as
  good as fancier choices; two hidden layers add parameters and local optima.
- How we could use it: as one candidate model for move-SIZE forecasts (expected range), never for direction.
- Signal source: underlying price/volume (plus option chain as context if IV/PCR are inputs); maps to NSE index, MCX
  futures and crypto alike; it is a forecaster, not a trade.
- Our research: R41 (`HUNT_R41_NEURAL_NETS.md`) ran MLP, TCN and GRU 3-seed ensembles on the underlying. Direction over
  15/30/60 minutes is a coin (holdout AUC 0.510-0.517); next-60-minute range R2 0.432 against trees 0.382 (36,320
  forecasts, q 0.006). The book's "small net, one layer" advice matches our tiny models (R41's India MLP has 11,046 parameters).

#### 2.2 Other architectures (Ch. 2.4)
- Radial basis (RBF) and ridgelet networks: Gaussian-shaped or derivative-of-Gaussian units around chosen centres.
- Jump-connection network: inputs go to the output directly AND through the hidden layer, so it nests the linear model;
  if only the direct links matter, the relation is linear. A "partitioned" variant fits a network to the residuals of
  a linear regression to look for missed nonlinearity.
- Elman recurrent network: hidden units also see their own previous (unsquashed) values, giving memory with fewer
  parameters than many lags; the author suggests it for daily or intraday returns. Our R41 GRU / TCN are the modern
  form of this idea and edged the MLP on size (GRU 0.434 vs MLP 0.424).
- Multi-output network: one net forecasts several horizons at once (e.g. 1, 3, 6, 12 months), cheaper than separate
  models. Usable for our 5/15/30/60-minute range forecasts in one model. Not tested by us as a single multi-output net.

#### 2.3 Smooth-transition regime switching, linear (STRS) and neural (NNRS) (Ch. 2.5; used in Ch. 6-7)
- What it computes: two regimes (e.g. growth / recession, inflation / deflation) blended by a logistic weight on a
  transition variable (lagged target, income growth) with threshold c (set to 0) and a smoothness parameter. The neural
  version adds two logistic regime neurons on top of a linear core. The weight is read as "probability of staying in
  the regime".
- How we could use it: a volatility-regime blend (quiet vs busy) for range forecasts, with India VIX or recent realised
  range as the transition variable.
- Signal source: underlying (or VIX as context). Maps to NSE/MCX/crypto as a forecaster only.
- Our research: no regime-switching model tested by us. Note the book's own result in Ch. 7: the neural regime weight
  sat near 0.5 the whole time, i.e. the gain came from general curvature, not from detecting the regime.

#### 2.4 Nonlinear principal components (auto-associative network) (Ch. 2.6; used in Ch. 9)
- What it computes: a network that squeezes many series (e.g. implied vols at six maturities) through a narrow middle
  layer and rebuilds them, so the middle unit is a one-number summary of the whole set. Errors of noisier series can
  be down-weighted during fitting. Linear PCA is the benchmark.
- Claimed use: the most useful application in the book is distilling a single "market riskiness" number from many
  implied volatilities; one nonlinear component does what two or more linear ones do.
- How we could use it: one number from the NIFTY/BANKNIFTY IV surface (ATM IV by expiry plus put/call wing IVs) as a
  context feature for size forecasts and option-selling filters.
- Signal source: volatility / options structure (option chain as context). Maps to NSE index options (we have chain IV);
  MCX options thinly; crypto via Deribit/Delta IV.
- Our research: the MCX brain's L2 autoencoder (`BRAIN_MCX.md`) is this idea on price windows: it rebuilt windows a
  little better than PCA (error 0.112-0.122 vs 0.116-0.124) but added nothing for size or direction (0 of 12 pass).
  Not tested on the IV surface.

#### 2.5 Classification: discriminant, logit, probit, Weibull and neural classifiers (Ch. 2.7; used in Ch. 8)
- What it computes: probability of a 0/1 outcome (default, bank failure). Logit is a one-neuron network; the neural
  classifier averages several logistic neurons; softmax for three or more classes. Judged by false-positive and
  false-negative rates.
- How we could use it: meta-labels such as "will this arm's trade hit its stop" or "big candle next".
- Signal source: whatever the inputs are. Our research: R41's meta-label filters on ORB, Range Fade, Liquidity, Night
  and L3 trades did not beat the base rate; the Liquidity filter made the arm worse (holdout Rs 234 to -Rs 70 a day).

#### 2.6 The black-box criticism (Ch. 2.8)
Free parameters and data mining: a model that explains everything explains nothing; Occam's razor; prefer simple nets;
markets are traders learning from approximate models (bounded rationality), which is the author's case for nets.

### Chapter 3. Estimation with evolutionary computation

#### 3.1 Data preparation (Ch. 3.1)
- Stationarity: test with Dickey-Fuller / Phillips-Perron; log first differences usually fix prices.
- Calendar adjustment (Gallant-Rossi-Tauchen): regress returns on day-of-week, number of non-trading days before the
  session, month and December/January week dummies; then regress the log of squared residuals on the same dummies plus
  a trend, and rescale, so both mean and variance are calendar-cleaned. Keep the raw series as a check.
- Scaling: min-max to [0,1] or [-1,1], a nonlinear logistic scaling, or standardise then squash; pick by fit.
- How we could use it: the calendar-variance step is a clean way to remove time-of-day / day-of-week / holiday-gap
  effects from a range forecast; our R16 already uses "usual range at this time" as a baseline. Caution (ours, not the
  book's): min-max scaling with full-sample min and max leaks the future; we fit scalers on training rows only (R41).
- Signal source: underlying. Not tested by us as the GRT procedure.

#### 3.2 Fitting: gradient search, simulated annealing, genetic algorithm, hybrids (Ch. 3.2)
- Quasi-Newton (BFGS) and back-propagation (learning rate about .05-.5, momentum about .9) find only local optima.
- Simulated annealing accepts some worse steps early to escape local minima.
- Genetic algorithm: a population of weight vectors, tournament selection, three crossover types, a mutation rate that
  shrinks over generations, an "election" among parents and children, optional elitism; the author runs about 100
  generations and then hands the best vector to gradient search.
- For us: an optimiser, not a signal. Relevant only as a warning that a GA searching rule PARAMETERS is a data-mining
  engine; our Benjamini-Hochberg and holdout rules exist for that. Not used by us.

#### 3.3 Thick models and bagging (Ch. 3.3, 4.4)
- What it computes: refit the same (or slightly different) network many times, with different starts, scalings or
  methods, and use the mean or trimmed mean of the forecasts; the spread gives a forecast interval. Bagging does the
  same on bootstrap resamples and helps when the data have breaks.
- Book's claim (citing Granger and Jeon): single networks did not beat linear models on most macro series, but thick
  (averaged) networks did, uniformly.
- How we could use it: any size forecaster we ship should be a seed ensemble with a trimmed mean, and show its spread.
- Our research: R41 already uses 3-seed ensembles with dropout and early stopping; we have not tested single vs thick.

#### 3.4 Toy test (Ch. 3.4)
On a noiseless curve (y = sin(x)^2 + exp(-x), 1000 points, 1000 draws) R2 was linear .55, any order-2 polynomial .91, a
2-neuron net .99, all in sample. It shows flexibility, not forecasting power (there is no noise to overfit).

### Chapter 4. Evaluating a network

#### 4.1 In-sample checks (Ch. 4.1)
- R2 and the Hannan-Quinn information criterion (penalty between Akaike and Schwarz) to rank models of different size.
- Residual tests: Durbin-Watson and Ljung-Box (serial correlation), McLeod-Li (correlation of squared residuals, i.e.
  leftover volatility clustering), Engle-Ng (asymmetry), Jarque-Bera (normality).
- Lee-White-Granger test: regress the residuals on many randomly weighted logistic neurons of the inputs, 1000 times;
  if more than 5% of the regressions are significant there is missed nonlinearity. In the toy it caught the
  misspecification in 99% of runs against 6.6% for the BDS test (BDS: a test for any non-iid structure, with the
  tolerance set at .5-1.5 standard deviations).
- Fairness rule: purge the linear model's serial correlation (more lags or moving-average errors) before comparing,
  and beat the BEST linear model, not a weak one.
- How we could use it: Lee-White-Granger is a cheap pre-check before building any nonlinear model on our features: if
  the linear model's residuals show no neglected nonlinearity, a network will not help. Not tested by us.

#### 4.2 Out-of-sample checks (Ch. 4.2)
- Hold out the end of the sample, or better, re-estimate recursively (expanding or moving window) and forecast one step
  at a time, a "simulated real time" test. The author notes this favours the linear model and calls it the realistic
  match-up.
- Root mean squared error; Diebold-Mariano test on the difference in absolute errors with an autocorrelation
  correction (reject equal accuracy at about -1.69 for 5%); Harvey-Leybourne-Newbold small-sample correction; Clark-West
  correction for nested models (a bigger model looks worse out of sample just from fitting noise).
- Pesaran-Timmermann directional-accuracy test: is the share of correct signs above what independent signs would give?
- Predictive stochastic complexity: choose lags and architecture by honest out-of-sample error; ties go to the simpler.
- Cross-validation and the 0.632 bootstrap (in-sample error weighted .368, out-of-bag error .632); a ranking device
  only, with no test distribution.
- Data size: citing Walczak, for daily currency forecasts at most two years of training data gave the best nets (the
  "recency effect", about 5% better), single-hidden-layer nets beat two-layer ones and could reach about 60%
  directional accuracy. The author adds that too much old data can hurt.
- Our research: our walk-forward plus one locked holdout read once is the "simulated real time" design; we judge size
  models by R2 against baselines with session-block bootstrap CIs and BH across cells (R41). We do not run
  Diebold-Mariano or Pesaran-Timmermann by name; a Diebold-Mariano test on the R16/R41 range forecasts against the
  time-of-day baseline would be a cheap, standard addition. The 60% directional claim is not supported on our indices:
  R16 holdout AUC 0.509-0.536, R41 0.510-0.517, R88 0.485-0.535.

#### 4.3 Interpreting a network (Ch. 4.3)
- Impulse responses; pruning an input and refitting (unreliable because the refit lands in a different optimum).
- Partial derivatives of the output to each input, analytic or by finite differences; they change with the state.
- Bootstrap the residuals 500-1000 times, refit each time, and read p-values of the derivatives. An absurd sign (more
  profit, more bankruptcy risk) means overfitting: prune.
- How we could use it: a sanity check on any shipped size model (bigger recent range or higher VIX must raise the
  forecast). Not done by us formally.

#### 4.4 Implementation recipe (Ch. 4.4)
Same data transforms as the linear model; lags chosen by Hannan-Quinn on the linear model; [0,1] scaling first; start
with a small feedforward or jump net; genetic algorithm then a few quasi-Newton steps; real-time out-of-sample for time
series; thick-model trimmed mean; RMSE plus Diebold-Mariano; expect linear to win if the world is linear.

### Chapter 5. Artificial data (500 points, last 100 held out, 4 lags, 3 neurons unless stated)
Results as the book prints them (n = 100 out-of-sample points each):

| process | in-sample R2 lin / net | out-of-sample RMSE lin / net | sign success lin / net | bootstrap ratio |
|---|---|---|---|---|
| stochastic chaos (always positive) | .29 / .53 | .147 / .117 (DM significant) | 1 / 1 | .872 |
| stochastic volatility + jumps (returns) | .42 / .45 | .157 / .167 (net worse, not significant) | .646 / .656 | .968 |
| Markov regime switching (GDP-like) | .35 / .38 | 1.122 / 1.224 (net worse, not significant) | .77 / .72 | .982 |
| volatility regime switching (returns) | .422 / .438 | 1.37 / 1.38 (not significant) | .76 / .76 | .99 |
| distorted long memory (bubbles) | .955 / .957 | 6.81 / 6.58 (DM p .01-.09) | 1 / 1 | .99 |
| Black-Scholes implied vol (cross-section) | .91 / .99 | .0602 / .0173 | 1 / 1 | .28 |

- 5.7 Black-Scholes implied volatility: random option cross-sections (spot, strike, rate, maturity, volatility); the
  net recovers the volatility from the price and other inputs far better than a linear model (RMSE more than 80% lower,
  bootstrap error more than 70% lower). The book notes (citing Hutchinson, Lo and Poggio) that for time series one
  should use spot/strike and price/strike ratios.
  - How we could use it: a fast IV inverter or a smoother for stale or missing chain prints. We already reprice stale
    wings with Black-Scholes at the last print's IV (R103 / `DEEP_OPTIONS_SELLING.md`); a net adds nothing there because
    the formula is known and exact. Signal source: volatility / options structure.
- Reading the table honestly: on the three RETURN-like series (jump-diffusion, Markov switching, volatility switching)
  the net was no better, or slightly worse, out of sample; it won only where the process was strongly nonlinear in
  level (chaos, bubbles) or a known pricing function. The "sign success" of 1 in three rows is meaningless: those series
  are always positive. The author's own conclusion is that the payoff is in volatility, not returns.
- Our research agrees: direction is a coin (R16, R41, R88: 0 of 8 direction tests held); size is predictable (R88: 225
  of 305 size tests hold).

### Chapter 6. Real time series (monthly)
- 6.1 US car production, 12-month-ahead growth (1992-2002) from its own growth, car prices, mortgage rates and income;
  linear model with moving-average errors vs STRS vs NNSTRS. Out of sample (recursive, last 53 months): RMSE .180 /
  .122 / .130, sign success .491 / .679 / .698; both nonlinear models beat linear (DM p up to .013) but not each other
  (p .85-.94). Bootstrapped derivatives: mortgage rate -0.45 and income +0.25 matter, own lag and price do not.
- 6.2 High-yield bond spread, one month ahead (1988-2002) from default-rate change, lagged spread change, real exchange
  rate, industrial production, MSCI and NAPM. Out of sample (recursive, last 86 months): RMSE .015 / .006 / .007, sign
  success .733 / .917 / .905; again both nonlinear beat linear, not each other. Strong persistence in spread changes
  (derivative about .77); defaults insignificant; stocks negative.
- Lesson: in both cases the near-linear regime model (STRS) was as good as the network; the gain came from allowing a
  regime blend, not from the net. The big jump in fit for spreads came mostly from fixing the linear model's serial
  correlation (its Ljung-Box failed).
- For us: macro/credit, monthly; no NSE intraday use. The one transferable point is "fix the baseline first".

### Chapter 7. Inflation and deflation, Hong Kong and Japan (quarterly)
- Stock-Watson Phillips curve, four-quarter-ahead inflation, moving-average errors; regime weight on lagged inflation
  (threshold 0). Hong Kong inputs add the price gap with mainland China, unit labour costs, import prices, Hang Seng and
  property prices; Japan adds the Gensaki rate and bank lending.
- Hong Kong out of sample: RMSE .030 / .027 / .023 (linear / STRS / NNRS), sign success .767 / .900 / .867; NNRS beats
  linear only at longer autocorrelation corrections (DM p .065 down to .000). Property and import prices drive inflation.
- Japan out of sample: RMSE .018 / .017 / .013, sign success .511 / .489 / .644; NNRS beats linear (DM p .001-.016),
  STRS does not. The NNRS regime weight stayed near 0.5: no regime was detected. All models missed the 1997 slump.
- Granger causality between land prices and bank lending runs both ways (F 3.061, p .020 one way; 2.429, p .053 other).
- For us: macro only. Not relevant to intraday options beyond the method.

### Chapter 8. Classification: credit cards and bank failures
- German credit data (1000 applicants, 300 defaults, 20 attributes). 0.632 bootstrap out of sample (100 draws):
  weighted error discriminant .382, network .146, logit .146, probit .352, Weibull .354. The 3-neuron net was
  IDENTICAL to logit.
- Texas banks (12,605 bank records, 16.7% closed or taken over, 12 ratios). Out of sample (40 draws): network .073,
  logit .107, discriminant .401, probit .415, Weibull .340. Capital-to-assets ratio, return on assets and US loans/GDP
  matter.
- Lessons the author draws: run logit and a small net side by side, one checks the other; discriminant analysis and
  probit/Weibull fail one-sidedly (zero false positives with huge false negatives, or the reverse).
- For us: classification method only; no market data. Our meta-label filters (R41) are this kind of model; they did not
  beat the base rate.

### Chapter 9. Implied-volatility term structures: linear vs nonlinear principal component
- Data: daily swaption (cap-floor) implied vols for maturities 2, 3, 4, 5, 7 and 10 years, Hong Kong and US, Jan 1997 -
  Jul 2003. Mean IV and its standard deviation both fall with maturity (Hong Kong 2-year mean 28.581, sd 12.906; 10-year
  19.936, sd 4.303); IV levels show no fat tails.
- One linear component vs one network component (five encoding and five decoding neurons). In sample the linear one
  explains the 3-10 year vols better (Hong Kong R2 .986-.751 vs .978-.698); the network fits the volatile 2-year better.
- Out of sample: fit on the first 80%, then add one day at a time, refit and rebuild that day's six vols from the
  component. Hong Kong RMSE linear 4.195 / 2.384 / 1.270 / 2.111 / 4.860 / 7.309 vs network 1.873 / 1.986 / 2.598 /
  2.479 / 1.718 / 1.636; the network is significantly better at 2, 3, 7 and 10 years and worse at 4. US: better at all
  maturities except 4 years. US components Granger-cause Hong Kong ones, not the reverse.
- Our reading: this is a same-day reconstruction test (how well one number summarises today's curve with yesterday's
  weights), not a forecast of tomorrow's volatility; and the linear component winning in sample but losing by 3-5x out
  of sample at the long end suggests the recursive linear fit was unstable. Still, the method fits a real need of ours.
- How we could use it: one "IV level" number from the NSE chain (ATM IV of weekly and monthly expiry, put-wing and
  call-wing IV) as a context feature. Signal source: volatility / options structure (option chain as context). Maps to
  NSE index options; MCX options have sparse IV; crypto has Deribit/Delta IV. Our R102 facts would be its inputs: puts
  five strikes out dearer than calls on 91% of NIFTY days, ATM IV 7.7 points above VIX on expiry day
  (`DEEP_NIFTY_BANKNIFTY.md` 3.4). Not tested by us. Bennett's term-structure and skew chapters are covered in
  `research/books/bennett-trading-volatility.md`.

## Performance claims
- "Neural networks with evolutionary estimation have a predictive edge out of sample relative to linear models" - the
  author explicitly does not claim this means trading profit (cites Qi 1999). Evidence: the tables above. Read fairly,
  the nets win clearly on strongly nonlinear or pricing-formula data and on two macro series with regime blends, tie or
  lose on return-like series, and tie logit in credit scoring.
- "Networks never do appreciably worse than linear models; the only cost is compute" (Ch. 5 conclusion). Partly true in
  his tables (RMSE worse by up to about 9% in the regime-switching case, but not significantly).
- Black-Scholes implied vol: RMSE .0602 to .0173 (n = 100 held-out option cases). Artificial, noiseless pricing data.
- Bank failures: weighted out-of-sample error .073 vs logit .107 (40 bootstrap draws, 12,605 records).
- Implied-vol component: network much better out of sample at most maturities (the last 20% of the daily sample, roughly 2002-2003).
- Cited, not his own: Walczak's ~60% direction accuracy on daily FX with at most two years of training data; Granger and
  Jeon's thick models uniformly beating linear on macro series. No evidence printed for either.
- No profit, Sharpe, drawdown or cost figure appears anywhere in the book.

## Risk, sizing and psychology
Nothing on position sizing, stops or trading psychology. Risk appears only as a forecasting target (volatility, default,
bank failure) and as the cost of a wrong classification (a false negative, lending to a bad risk, is worse than a false
positive). The behavioural idea is bounded rationality: traders are learners using approximate models, so markets react
with thresholds and asymmetry.

## How it relates to our research
- AGREES with our central finding. The author's own artificial tests show nets do not help on return-like series, and he
  concludes the payoff is in volatility. Our R16, R41 and R88 found the same on NIFTY/BANKNIFTY/MCX: direction a coin
  (AUC about 0.51), size predictable, and networks the best size forecasters we have (R41 holdout R2 0.432 vs trees
  0.382). Our learned model in `FINDINGS.md` ("Learned model (ml_long.py)") had rank correlation about 0 for the neural
  net (-0.004/+0.004) on 14,442 points; `DIRECTION_MODEL.md` top-confidence buckets reach only 52-53% correct.
- AGREES on method: walk-forward / recursive real-time testing, fixing the baseline first, small networks, ensembles.
  R41 does all of these and adds what the book lacks: costs, a locked holdout read once, BH across cells.
- CONTRADICTS the cited claims of ~60% daily direction accuracy (Walczak) - not on our data. R46 tested a direction MLP
  on BTC in the other AI's 12-64-32-1 shape: validation loss about 0.692 (coin), cleared its 0.65 confidence bar on 88
  of 7,536 design signals and lost money.
- ALREADY REJECTED by us: networks as direction filters on arm trades (R41 meta-labels), straddle-buying on a size
  forecast (R89, 0 of 18 profitable: options already price the expected move).
- NOT TESTED by us: regime-switching (STRS/NNRS) size models; a nonlinear principal component of the IV surface;
  Diebold-Mariano and Pesaran-Timmermann tests by name; the Lee-White-Granger neglected-nonlinearity pre-check;
  Gallant-Rossi-Tauchen calendar variance adjustment; thick vs single-network comparison; multi-output horizon nets.
- Weaknesses to remember when citing the book: no costs, no trading P&L, artificial data in the strongest results, the
  "sign success" on always-positive series, one specification per real example, and an implied-vol "out-of-sample" test
  that is really same-day reconstruction.

## What is new to us
1. A Diebold-Mariano (with Harvey-Leybourne-Newbold correction) and Clark-West test for our range-forecast comparisons
   (R16 / R41 / R88 size models vs the time-of-day baseline), and Pesaran-Timmermann as the standard name for our
   direction coin-flip checks.
2. Lee-White-Granger: a fast, random-neuron test of whether a linear size model leaves nonlinearity behind, before
   paying for a network.
3. A one-number nonlinear summary of the NSE IV surface (ATM by expiry plus wings) as a context feature for size
   forecasts and option-selling filters.
4. Smooth-transition regime blending for volatility (quiet vs busy regime, VIX or recent range as the switch).
5. Gallant-Rossi-Tauchen: removing calendar effects from both the mean and the variance (day of week, holidays before
   the session, month), useful for MCX evening sessions and post-holiday NSE opens.
6. Thick-model reporting: show the trimmed-mean forecast with its spread across seeds, as Jarvis's honesty band.

## Could not read
- All figures are images (network diagrams, series plots, error paths, bootstrap histograms); only axis numbers and
  captions came through. Tables came through intact.
- Many equations (GARCH likelihood, Petersohn scaling, mutation operator, Diebold-Mariano and Pesaran-Timmermann
  formulas, Clark-West variance) are partly garbled in the text layer; the prose around them was readable and the
  methods are standard. Bibliography and index were skimmed, nothing practical.
