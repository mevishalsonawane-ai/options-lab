# Inside Volatility Arbitrage: The Secrets of Skewness - Alireza Javaheri (2005)

Source: inside-volatility-arbitrage-javaheri.txt, 272 pages (236 numbered plus front matter), read in full on 25 Oct 2026
(text layer, pdftotext; all prose, tables and C++ listings readable; the formulas are flattened, see "Could not read").
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A PhD thesis (Ecole des Mines de Paris) turned into a book on estimating stochastic-volatility models.
Chapter 1 surveys volatility models: Black-Scholes, jumps, CEV, local vol, Heston, GARCH, variance gamma.
Chapter 2 estimates those models from the index's own price history with Kalman and particle filters plus maximum likelihood.
Chapter 3 compares the volatility-of-volatility and spot-vol correlation implied by S&P 500 options with those found in S&P 500 history, and trades the gap.
The trade (buy OTM call, sell OTM put, delta-hedged) is "selling insurance": small regular gains, sudden large losses, profit only at mid prices.

## Methods, rules and setups

Chapter by chapter. Practical methods get a full block; pure theory gets a line or two. Where a method is a pricing or
estimation tool, not a trading rule, the block says what it computes, its inputs and how we could use it.

### Chapter 1 - The Volatility Problem (pp. 1-45)
Teaches: volatility is not constant (rolling one-year SPX vol moves between 0.18 and 0.24 in 2000-01); the smile cannot
come from time-dependence alone; the survey runs from level-dependent to stochastic to pure-jump models, with how each is
priced. Footnote: the smile barely existed before the 1987 crash, so many think markets "learnt" to price a crash.

#### 1.1 Historic volatility and the Parkinson high-low estimator (Ch. 1, pp. 3-4)
- What it computes: annualised standard deviation of daily log returns (times the square root of 252); Parkinson uses
  the daily high/low range scaled by 4 ln 2. The window length and end date change the answer; use recent data.
- Inputs: daily closes (or highs and lows). Use for us: the realised-vol leg of any implied-vs-realised comparison.
- Signal source: underlying price; maps to NSE index (spot/futures), MCX futures and crypto alike.
- Mechanical? yes. Our research: R102 (DEEP_NIFTY_BANKNIFTY.md 2.2) already computes both; Parkinson runs well below
  close-to-close every year (NIFTY 2025: 8.4 vs 11.8) because the overnight gap is a large share of daily variance. Bennett
  method 26 covers better estimators (Yang-Zhang); see research/books/bennett-trading-volatility.md.

#### 1.2 Black-Scholes, the binomial tree and implied volatility (Ch. 1, pp. 5-7)
- What it computes: option value from spot, strike, time, rate, dividend yield and one volatility; put-call parity; the
  binomial tree (handles early exercise); implied vol by inverting the formula.
- Use for us: the repricing engine R103 Amendment 2 already uses (Black-Scholes at the leg's last IV) and the delta for any
  hedge. Signal source: volatility / options structure. Maps to NSE (European, cash-settled index options), MCX options
  (options on futures: Black-76), crypto options. Mechanical: yes.

#### 1.3 Jump diffusion, CEV and the firm-value (BCG) model (Ch. 1, pp. 7-13)
Merton/Kou jumps give fat tails and skew; a default jump adds the credit spread to the variance. CEV makes vol a power of
spot (fits the smile but forces perfect negative spot-vol correlation, the "leverage effect"); BCG derives the same from
a firm-value model. Theory for us; no trading content.

#### 1.4 Local volatility and the implied density (Ch. 1, pp. 14-19)
- What it computes: the risk-neutral density of the index at expiry from the second strike-derivative of call prices
  (Breeden-Litzenberger); Dupire's local vol surface; local variance equals the expected instantaneous variance given the
  index ends at that strike.
- Warnings: the inversion is ill-posed and very sensitive to input prices (negative probabilities possible); smoothing
  penalties are artificial; local vol predicts that future smiles flatten; traders recalibrate daily, which breaks the
  no-arbitrage property and misprices path-dependent options (fine for a digital "bet", bad for barriers).
- Practical tip: interpolate across maturities in total variance (time x vol squared), not in vol; forward variance
  between two expiries then stays positive and smooth.
- Inputs: a full strike chain per expiry. Use for us: an implied probability of NIFTY closing beyond a level, as context.
- Signal source: option chain as context. Maps to NSE partly (the 1-minute lake keeps only ATM +-10 strikes; the EOD
  bhavcopy has all strikes), MCX thinly, crypto yes. Mechanical: yes. Our research: not tested by us (R16 found max pain,
  a crude chain-implied level, worse than no forecast: 0.74% vs 0.43% error on BANKNIFTY).

#### 1.5 Stochastic-volatility models: Heston, GARCH, NGARCH (Ch. 1, pp. 20-26)
- What it computes: Heston lets the variance mean-revert to a long-run level with its own noise (vol-of-vol, xi) and a
  correlation (rho, usually negative) with the index. GARCH(1,1) updates tomorrow's variance from today's variance and
  today's squared surprise; it is parsimonious (equal to an infinite-order AR model with three parameters). Its continuous
  limit has ZERO spot-vol correlation, so the asymmetric NGARCH (a leverage parameter c) is needed to get the skew.
- Key concept: options give the risk-neutral variance drift; history gives the real-world drift; the difference is the
  market price of volatility risk. Vol-of-vol and correlation should be the SAME in both worlds (Girsanov), which is the
  basis of Chapter 3's test.
- Inputs: daily (or intraday) index returns. Use for us: a GARCH or NGARCH variance forecast for NIFTY/BANKNIFTY/MCX/crypto
  as a realised-vol forecast to set stops or sizes by expected range.
- Signal source: underlying price. Maps everywhere. Mechanical: yes. Our research: not tested as GARCH; the nearest is
  R16 (next 30-60 minute range predictable, error 18-20% below the time-of-day average; next-day range no better than VIX)
  and h29's log-HAR forecast (median IV / HAR forecast 1.2-1.36).

#### 1.6 Pricing under stochastic vol: Fourier transform, mixing Monte Carlo, asymptotics (Ch. 1, pp. 24-40)
- What it computes: Heston option prices by inverting the characteristic function (Lewis's fundamental transform; the
  author's "method of choice": fast and robust); the Romano-Touzi "mixing" Monte Carlo that simulates only the variance
  path and applies Black-Scholes on each path; long-maturity closed forms; a series in vol-of-vol.
- Findings: one Heston fit to a whole 1-8 year SPX surface (March 2004) gives long-run vol about 0.17 and rho -0.47;
  a single fit across short maturities fits badly, so calibrate per maturity. In the vol-of-vol series, xi creates the
  smile's curvature (kurtosis: fatter wings) and rho creates the slope (skew); with rho < 0 an OTM call is worth less than
  Black-Scholes. The series breaks down once xi exceeds 0.40 near one-year maturity.
- Use for us: a pricer for strikes outside the lake's window (better than flat-IV Black-Scholes for stale far wings, the
  R103 artefact). Signal source: volatility / options structure. Maps to NSE/MCX/crypto options. Mechanical: yes. Our
  research: not tested by us; R103 repriced stale legs with flat-IV Black-Scholes and found it runs 1-5 points high.

#### 1.7 Pure-jump models: variance gamma, VGSA, VGG (Ch. 1, pp. 40-45)
Variance gamma runs a Brownian motion on a random (gamma) clock; VGSA makes the clock's speed mean-revert, which gives
volatility clustering. Priced by characteristic function. Theory for us; the useful idea is that VG's skew parameter
(theta) is near zero in history but strongly negative in options (Ch. 3).

### Chapter 2 - The Inference Problem (pp. 46-186)
Teaches: two ways to estimate a model (option prices at one moment, or a long price history); how to filter the hidden
variance out of prices; and, most usefully, how badly the noise parameters are estimated from daily data.

#### 2.1 Calibrating to option prices (Ch. 2, pp. 49-54)
- What it computes: model parameters that minimise squared price errors across strikes, using Powell's direction-set
  optimiser (no gradients needed, so it survives jumpy objective functions). Check for bias by regressing the pricing
  errors on moneyness and maturity: a low R-squared means no systematic bias.
- Finding: SPX on 21 May 2002, four maturities: rho came out at -1.00 every time and xi 0.18-0.27, fairly stable.
- Use for us: fit a smile model each morning to the NIFTY chain; the residual by strike flags mispriced or stale quotes.
- Signal source: option chain as context. Maps to NSE yes (inside ATM +-10), MCX thin, crypto yes. Mechanical: yes.
  Our research: not tested by us.

#### 2.2 Maximum likelihood with Kalman filters (EKF, UKF, Kushner) (Ch. 2, pp. 54-98)
- What it computes: the best estimate of today's hidden variance from all past returns (predict, then update with the new
  return), and the likelihood of the parameters as a by-product; the parameters are then chosen by maximising it.
  EKF linearises, UKF propagates a few "sigma points", Kushner's filter uses Gauss-Hermite points. The Kalman gain is just
  a regression slope.
- The practical recipe: a one-dimensional state (the variance only), with the spot-vol correlation removed by subtracting
  rho x xi x the last return, gives an observable, fast filter; the book prints about 30 lines of C++ for it. Naive state
  choices (parameters inside the state) are unobservable and depend on tuning. Keep variance positive with the bound the
  book derives.
- Findings: on SPX 1996-2001 (1,256 days) EKF and UKF track equally well but give very different parameters (EKF xi 0.19,
  UKF xi 0.44), while the long-run vol (omega / theta) agrees: the filters are insensitive to the parameters.
- Diagnostics to copy: chi-square normality of standardised residuals (21 bins; the 5% critical value is 31.5; EKF 27.7,
  UKF 22.7), Box-Ljung autocorrelation (24 lags; about 0.01, no autocorrelation), and a variogram (flat at 1 for
  independent residuals). MPE and RMSE of the residuals measure the filter.
- Inputs: index log prices, a drift. Use for us: a filtered "current variance" for NIFTY, BANKNIFTY, MCX futures or BTC as
  a realised-vol state (compare with ATM IV, size by it).
- Signal source: underlying price. Maps everywhere. Mechanical: yes. Our research: not tested by us (no Kalman or
  state-space volatility model in research/; R41's networks forecast the next-hour range, R2 0.432).

#### 2.3 Joint filter and parameter learning (Ch. 2, pp. 67-81, 125-127)
Put the parameters into the state and filter them too, discard a burn-in, average the rest. Works on a toy example; on
SPX it converges for the drift parameters and poorly for xi and rho. A particle version with Metropolis-Hastings moves
exists. Practical only as an on-line updater; the MLE route is more robust.

#### 2.4 Particle filters (generic, extended, unscented; with jumps) (Ch. 2, pp. 98-120, 158-168)
- What it computes: the same hidden-variance estimate and likelihood by simulation (importance sampling, weights,
  resampling; optional Metropolis-Hastings step against particle collapse). The proposal is improved by running a Kalman
  filter inside each particle (EPF/UPF). Handles jumps (Bates model) and non-Gaussian models (VGSA) where Kalman cannot.
- Findings: on daily data particle filters bring only a marginal gain over Kalman; with yearly steps (strong
  nonlinearity) they help (200 points: EPF rho -0.58 vs EKF -1.00, true -0.5; UPF RMSE 0.00136 vs EKF 0.00342). The MH
  step adds little. In a jump test (jump size 20%, frequency 0.01 a day, 245 days) the jump parameters were recovered
  (intensity 2.65 vs 2.52, size 0.20) but the diffusion parameters were not; the filter's residuals reveal the jumps.
- Use for us: a jump-aware volatility state for MCX NATURALGAS/CRUDE or crypto, where gaps dominate.
- Signal source: underlying price. Maps everywhere. Mechanical: yes. Our research: not tested by us.

#### 2.5 Model identification: Heston vs GARCH vs 3/2 (Ch. 2, pp. 120-127, 184-185)
Filtering errors on SPX 1996-2001 favour the 3/2 model (variance noise grows faster than in Heston) for most filters,
matching Engle and Ishida. Adding jumps always lowers errors but adds parameters, so choose the model family on financial
grounds; AIC-type criteria are only valid with lots of data. Practical note: if we ever fit a vol model to NIFTY, test the
3/2 form, not only Heston. Not tested by us.

#### 2.6 How reliable is the estimate? Sample size, sampling distribution, high-frequency data (Ch. 2, pp. 127-144)
- What it computes: by simulating many price paths from known parameters and re-estimating, the spread (sampling
  distribution) of each estimate.
- Findings (the book's central result): parameters that move the drift of the observation (variance mean and speed) are
  estimated well; parameters that move its noise (vol-of-vol, correlation: "the noise of the noise") are not. With 5,000
  simulated days and true xi 0.03 / rho -0.50, EKF gave 0.052 / -0.874; it needed 50,000 points (200 years) to reach
  0.036 / -0.608 and 500,000 to reach 0.032 / -0.532. Over 500 paths of 5,000 days, xi averaged 0.056 (sd 0.050) and rho
  -0.343 (sd 0.297): biased and very wide. The likelihood is flat in xi and rho. Rescaling to daily units does not help;
  the cause is a low signal-to-noise ratio. A log-squared-return trick (Harvey-Ruiz-Shephard) helps only at very large N.
  BUT the product rho x xi is estimated much better (50,000 points: -0.0205 vs true -0.015), and the product is what
  sets skewness; xi alone sets kurtosis. GARCH has no such problem because its variance is a known function of past
  returns.
- Fix: high-frequency data (5-second sampling for 10 years gives 11,793,600 points; a simulated 1,000-a-day sample over
  5,000 days recovered the parameters reasonably), after cleaning. Always run a simulation test before trusting an
  estimate.
- Use for us: the same discipline as our bootstrap p-values: before trusting any fitted vol parameter from our 1-minute
  lake, simulate it. Our 1-minute bars (about 375 a day) are the "high-frequency" route the book recommends.
- Signal source: underlying price (method). Mechanical: yes. Our research: consistent with our method (FINDINGS uses
  bootstrap p and BH q); and with R16 / R41: the variance level is forecastable, the finer structure is not.

#### 2.7 Bayesian MCMC (Gibbs, Metropolis-Hastings) and characteristic-function filters (Ch. 2, pp. 144-158)
Gibbs and MH samplers explained on a normal example (1,000 points, true mean 10 and sd 5, recovered 9.94 / 4.82); the
Forbes-Martin-Wright MCMC for Heston is equally inefficient on simulated data. Bates's characteristic-function filter works
only for affine models. Theory for us.

#### 2.8 Pure-jump estimation (VG, VGSA, VGG) (Ch. 2, pp. 168-184)
VG has a closed-form density, so plain MLE works (500 points recovered theta, sigma, nu). In VGSA and VGG the clock
(arrival-rate) parameters are not recovered, and very different values produce near-identical price paths; the S&P 500
1992-94 residuals pass normality (chi-square 10.4) and autocorrelation tests. Same lesson as 2.6.

### Chapter 3 - The Consistency Problem (pp. 187-223)
Teaches: whether option prices and the index's own history agree about vol-of-vol and correlation (so about skew and
kurtosis), why they differ (model error, crash fear, or a trade), and two trades that would profit if options are wrong.

#### 3.1 The consistency test: options-implied vs history-implied skew (Ch. 3, pp. 189-197)
- What it computes: Heston (xi, rho) from one-year SPX options on 2 Jan 2002 (risk-neutral: omega 0.0362, theta 1.161,
  xi 0.420, rho -0.674; long-run vol about 0.17) against the same from filtered MLE on SPX January 1992 - January 2004,
  about 3,000 days (statistical: xi 0.096, rho -0.133). Options imply much stronger skew and kurtosis. Robust to daily
  Monte Carlo steps, mid call/put IVs (xi 0.403, rho -0.646), a free starting variance, wider strikes (xi 0.386), and a
  later date (Sep 2002: xi 0.547, rho -0.662). Plotting both smiles shows the options smile far steeper.
- Caveats the author gives himself: prices barely depend on xi and rho (simulations with very different values look
  alike), so the history numbers are uncertain (2.6); the window starts 10 years before the options, assuming stable
  dynamics. The "consensus" in the literature: options xi and |rho| always exceed history's. Skew depends on the product
  xi x rho, which history estimates more reliably than kurtosis.
- Data hygiene: spot and option closes must be synchronised (minutes matter); put and call IVs differ (within the
  bid-ask), so average the four IVs (call bid, call ask, put bid, put ask).
- Inputs: chain + long index history. Use for us: a "skew gap" = chain skew (ATM-5 put IV minus ATM+5 call IV) minus the
  skew a model fitted on NIFTY history would produce.
- Signal source: volatility / options structure. Maps to NSE (NIFTY, BANKNIFTY chain; but our weeklies are 1-7 days, not
  one year), MCX (thin chains), crypto (BTC/ETH options). Mechanical: yes. Our research: R102 measured both sides: the chain
  prices puts five strikes out 2.56 IV points above calls on NIFTY (91% of 1,222 days; BANKNIFTY 2.11, 90%) while daily
  NIFTY returns since 2006 have skew -0.04 and kurtosis 14.1 (n 5,087). So history is fat-tailed but nearly symmetric,
  the chain is strongly skewed: the same picture as SPX. The gap itself was never traded.

#### 3.2 Peso theory / crash-o-phobia (Ch. 3, pp. 197-199)
- Claim: a history without a crash can still come from a process with crashes (Friedman's peso: high Mexican rates before
  the 1976 37.5% devaluation). Fitting a Merton jump to the SPX options gives a rare (intensity 0.009) but huge
  (j 0.91) jump; even then options xi 0.361 and rho -0.585 stay well above history. The option skew may be correct
  insurance pricing, not mispricing.
- Use for us: context for every skew or premium-selling idea. Signal source: none (interpretation). Our research: NIFTY's
  worst day -12.98% on 23 Mar 2020 (R102) is the claim the skew pays for (DEEP_OPTIONS_SELLING.md "Skew").

#### 3.3 The skewness trade (risk reversal, vega-neutral, delta-hedged) (Ch. 3, pp. 199-209)
- Entry: when options imply more negative skew than history (a more negative rho or a higher xi), buy an OTM call and sell
  an OTM put, sized vega-neutral (calls per put = put vega / call vega), and delta-hedge with the underlying. Worked and
  repeated version: about three months to expiry, call strike nearest 110% of spot, put nearest 90%, call bought at the
  ask, put sold at the bid.
- Stop: none stated (the position is held; the hedge is the only risk control).
- Target / exit: hold to expiry, or (multiple-trade test) unwind about 20 business days before expiry so the hedge does not
  swing near delta 1; an early unwind pays the spread twice and adds option-price risk.
- Filters: the opportunity exists when history shows weak vol-of-vol (SPX) or weak spot-vol correlation (Grey Wolf, a
  penny stock); none when history already shows strong negative correlation and high vol-of-vol (MSFT, NDX in
  March 2003). Safest on an index (diversification lowers crash odds).
- Hedge ratios: the book used fixed implied vols at entry; it discusses using the history-implied smile's deltas, or
  Bates's skew-adjusted delta, and notes the upward SPX term structure moves IVs during the trade.
- Market and timeframe: S&P 500 index options, 3-month trades, daily delta hedge; tested 2002-2003.
- Worked example (SPX 4 Apr - 22 Jun 2002, spot 1126.34): bought 1250 call at 3.20, sold 1050 put at 14.20 (IVs 0.154
  vs 0.214); index ended 989.14; option loss 60.86 less the 11.00 credit, hedge +50.39, interest +1.32: P&L +1.85 points.
  Jumps around day 50 caused most of the hedge losses.
- Risks: loses in a bear market or crash (the short put), and big up-moves can also hurt the hedge account because xi
  matters as much as rho. "Selling insurance."
- Signal source: volatility / options structure (needs the chain and a history-fitted model); the hedge is on the
  underlying. Maps to NSE: partly (NIFTY monthly 10% OTM strikes are outside the 1-minute lake's ATM +-10 window; EOD
  bhavcopy has them but no bid/ask; the naked short put needs about Rs 1.5 lakh margin; a one-lot futures hedge cannot be
  fractional). MCX: chains too thin. Crypto: possible on BTC/ETH options but CLAUDE.md requires a stop and a profit lock.
- Mechanical? yes (as tested in the book). Our research: not tested by us as a risk reversal. Nearest: R103 (defined-risk
  short premium, 0 of 24 confirmed; "before costs the structures roughly break even; the costs make them losers") and
  R102 (puts dearer on 91% of days). See rules file: skew-risk-reversal-monthly.

#### 3.4 The kurtosis trade (Ch. 3, pp. 200-202)
- Entry: when options imply fatter tails than history (higher xi), sell ATM and far-OTM options and buy near-OTM options
  (the book's generic payoff diagram), delta-hedged.
- Stop / exit: not stated beyond holding to expiry. Profits if the index stays put, loses on large moves either way.
- Filters: the book says the skew trade is simpler and more likely; kurtosis needs only high option xi, as in EUR/USD
  (options xi 0.45 vs history 0.11 with rho near zero: a symmetric smile), where it suggests the kurtosis trade instead.
- Signal source: volatility / options structure. Maps to NSE yes inside ATM +-10 (a short straddle plus long near strangle
  plus short far strangle); MCX thin; crypto with stop and lock. Mechanical? partly (strike distances not given).
- Our research: the closest structures (short straddle with long wings: iron fly) failed in R103 (all six NIFTY non-expiry
  tests lose); see rules file: kurtosis-fly-condor.

#### 3.5 Mirror trades (buying the skew or kurtosis) (Ch. 3, p. 203)
When options under-price skew or tails (typically right after a crash), buy them: you pay premium and wait for a crash.
The author calls it unattractive except as insurance. Our research: long-vol buying lost (R89 0 of 18 straddle tests; DEEP_NIFTY_BANKNIFTY.md
summary table).

#### 3.6 Carr-Madan exact replication of moments (Ch. 3, pp. 202-203)
A strip of OTM puts and calls across all strikes replicates any payoff, including the third and fourth moments of the
log return, so the skew can be traded "exactly". Far higher transaction cost. Signal source: volatility / options
structure. NSE: needs all strikes (not in the lake). Not tested by us.

#### 3.7 Optimal position from the real vs risk-neutral densities (Ch. 3, pp. 213-215)
- What it computes: given the real-world density p (from history) and the risk-neutral density q (from the chain), the
  payoff that maximises expected utility for a fixed budget: marginal utility of the payoff is proportional to q / p.
  Where history says a region is more likely than options price it, own more payoff there.
- Use for us: a principled way to choose strikes and sizes, the options analogue of Kelly. Signal source: volatility /
  options structure. Maps to NSE partly (needs a full density). Not tested by us; our Kelly study (HUNT_H15.md) found the
  Kelly fraction unstable when estimated on few months.

#### 3.8 Non-Gaussian (VG / VGSA) version of the test (Ch. 3, pp. 213-218)
History's VG skew parameter for SPX 1992-94 is near zero (0.0056) while options give -0.1436: the negative skew lives in
options. For VGSA, options sometimes imply a far more volatile arrival rate (Mar and Jun 2000) and sometimes not, so the
skew trade is only open in some periods. Under pure-jump models the two measures are not directly comparable.

#### 3.9 A word of caution; FX and fixed income (Ch. 3, pp. 218-223)
Models are simplifications of a changing market, so a detected inconsistency is not a riskless profit; the skew trade is
"more similar to selling insurance than to an arbitrage". FX smiles are symmetric (low correlation), so FX suits the
kurtosis trade. For rates, the short rate is the initial slope of the yield curve, so the same filters apply.

## Performance claims
- Multiple skew trades, SPX, Jan 2002 - Feb 2003, 40 cases of about 3 months (10% OTM call bought, 10% OTM put sold,
  unwound about 20 business days before expiry): a LOSS if entered and exited at bid/ask, a profit only at mid prices.
  The author's reading: "a lack of decisive proof" that options and history are inconsistent. No per-trade numbers given.
- Single worked trade: +1.85 index points on SPX (described as "hardly generated a profit").
- Bid-ask spread about $1 for 10% OTM SPX options, enough to flip the average sign of the P&L.
- The trade "generates a regular and stable profit and sudden large losses" (footnote); no drawdown statistics given.
- Estimation results (Chapter 2) are on simulated data with known truth, so they are evidence about the method, not about
  markets; the market results are one date (2 Jan 2002) plus robustness variations.

## Risk, sizing and psychology
- No position sizing, stop-loss or psychology content. Risk content is structural: the skew and kurtosis trades are short
  crash insurance (left-tail losses), vega-neutral by construction, delta-hedged daily; continuous re-hedging is too costly,
  so the hedge is discrete and jumps hit it.
- Execution matters as much as the idea: entering at the ask/bid instead of mid turns the strategy from profit to loss;
  early unwinding pays the spread twice.
- Model risk: parameters estimated from limited daily data can be badly wrong (2.6); use the sampling distribution, and
  only call a mispricing conclusive when the option value lies outside the whole range of history-based estimates
  (footnote example: history xi between -0.20 and 0.20, options 0.40).

## How it relates to our research
- Agree, measured: options price more left tail than history shows. R102: puts five strikes out dearer than calls by 2.56
  IV points on NIFTY (91% of 1,222 days) and 2.11 on BANKNIFTY (90% of 1,190), while NIFTY's daily return skew is -0.04
  with kurtosis 14.1 over 5,087 days: history fat-tailed but near-symmetric, the chain skewed. This is Javaheri's SPX
  finding on our index. R102 also found India VIX above forward 21-day realised on 81% of days (median 1.28, n 4,507) for
  NIFTY: a level premium on top of the skew premium. (Bennett's write-up covers the level premium; this book adds the
  higher moments.)
- Agree: selling that premium is insurance, and costs decide it. Javaheri's 40 trades lose at bid/ask and win only at
  mid; our R103 (0 of 24 defined-risk selling tests; "before costs the structures roughly break even; the costs make them
  losers", Rs 228-256 of charges for eight orders) and the naked 09:20 straddle's worst days of about -Rs 29k on one lot
  (beyond the -Rs 13k month stop) say the same in India.
- Agree, and it explains a lesson of ours: stale and unsynchronised prices. Javaheri insists on synchronised spot and
  option closes and averaging call/put bid/ask IVs; R103's design passes were wings marked at prices 30+ minutes old.
  His per-maturity Heston repricing would be a better repricer than flat-IV Black-Scholes, which R103 found runs 1-5
  points high.
- Comparing implied with estimated vol: our h29 compared ATM IV with a log-HAR realised forecast (median ratio 1.2-1.36)
  and found buying "cheap" IV still lost (best -Rs 30/day net; BH q 0.80) because even the cheapest fifth realised only
  about 0.7x the implied move. Javaheri's filter-based variance estimate is a more elaborate HAR; the book never trades
  the level gap itself, and our evidence says the level gap does not help a buyer.
- Consistent with R16 / R41 / R88 (size predictable, direction not): the book shows the variance level and mean reversion
  are estimable from prices while vol-of-vol and correlation (the "noise of the noise") are not from daily data.
  IV1 and h29 found skew carries no direction (option-only models lost on all 5 indices; h29 skew IC about 0); Javaheri
  never uses skew for direction either: he trades it as a price.
- Monte Carlo / bootstrap: his "simulate to get each parameter's sampling distribution" is the same discipline as our
  bootstrap p-values and pre-registration; nothing to change.
- Not related: GEX (R28/R36/R37), cross-index pairs (R72), futures basis (H39; the book only derives dividend yield from
  the forward, q = r - ln(F/S)/T, which is h29's synthetic forward), neural nets (R41), Kelly (H15: only 3.7 is close).
- Already rejected by us: long-vol "mirror" buying (R89, 0 of 18).
- Bennett (research/books/bennett-trading-volatility.md) already covers skew measurement, sticky-strike regimes, the
  volatility risk premium, delta hedging and realised-vol estimators; not repeated here.

## What is new to us
- A history-vs-chain skew gap as a measured quantity: chain skew (ATM-5 put minus ATM+5 call IV, or a fitted rho x xi)
  minus the skew a model fitted on past NIFTY returns would price. Never computed by us; R102 has both halves.
- The risk reversal (buy OTM call, sell OTM put, vega-neutral, delta-hedged) on NIFTY monthlies as an explicit skew trade,
  run at mid and at bid/ask to see if costs flip it, as the book found. Not tested by us; needs the far strikes (EOD
  bhavcopy) and is margin-heavy.
- A Kalman / particle-filtered variance state for NIFTY, MCX and BTC, with the residual diagnostics (chi-square,
  Box-Ljung, variogram) as model checks. We have HAR and networks for range, not a state-space vol model.
- Model identification: try the 3/2 variance model against Heston/GARCH on NIFTY 1-minute data.
- Per-maturity Heston (or SVI-style) smile fit as a repricer for strikes that stop printing (R103's stale-wing problem).
- The rule "call a mispricing only when it lies outside the simulated sampling distribution of the history estimate".
- The utility-optimal payoff from real vs risk-neutral densities (3.7) as an options sizing idea.

## Could not read
- Formulas: the text layer flattens fractions, square roots, sub/superscripts and Greek symbols (for example the
  Heston transform on p. 29, the Das skewness/kurtosis formulas on pp. 196-197, the jump-diffusion particle-filter
  equations on pp. 159-162). The prose around each was read; no formula was needed for this write-up.
- Figures (about 80): only captions, axis labels and legends survived (for example Figures 3.7/3.8, the skew and kurtosis
  payoff diagrams, are readable only from their captions and the text).
- Nothing else missing; pp. 224-236 are references and index.

## R104 results (25 Oct 2026)

Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in `research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:

- `book-fmjavaheri-01`: not testable (R104): 10% OTM monthly strikes are outside the lake's ATM +-10; bhavcopy in the lake is a weekly sample, closes only
- `book-fmjavaheri-02`: not testable (R104): needs the next-monthly chain and far legs (see javaheri-01)
- `book-fmjavaheri-03`: tested (R104): VOL: 358 trades, win 51%, net -Rs 29,272 design, holdout -Rs 21,176 (fail)
