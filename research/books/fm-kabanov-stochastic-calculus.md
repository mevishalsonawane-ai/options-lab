# From Stochastic Calculus to Mathematical Finance: The Shiryaev Festschrift - Yuri Kabanov, Robert Liptser and Jordan Stoyanov (eds.) (2006)

Source: from-stochastic-calculus-to-mathematical-finance-kabanov.txt, 658 PDF pages, read in full on 25 Oct 2026 (pdftotext
text layer; preface, Shiryaev biography, publication list and all 30 papers present in the PDF, read paper by paper with
running notes in the scratch folder). Formulas came through as broken symbol soup in places; the prose, theorem
statements and the few tables are readable. The last paper listed in the contents (Veretennikov, book p. 623 on) is not
in the PDF at all (see "Could not read").
Book claims are the authors', not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A Springer research volume of 31 papers for A.N. Shiryaev's 70th birthday, written by his students and colleagues.
It is not a textbook and not a trading book: each paper is a journal-style article with theorems and proofs.
About two thirds are pure probability (martingales, SDEs, SPDEs, absolute continuity, limit theorems, optimal stopping).
The finance papers cover realised/bipower variation, copulas on high-frequency data, hedging with a proxy asset,
Kelly-type portfolio growth, stochastic-volatility and continuous-time GARCH models, Levy-market option symmetry and insider information.

## Methods, rules and setups

This volume has no entry or exit rules. Below, every paper gets an entry in book order. Practical methods get a block
("what it computes, inputs, how we could use it"); pure theory gets one or two lines. Our data for the mapping: NIFTY /
BANKNIFTY / FINNIFTY / MIDCPNIFTY / SENSEX spot and futures bars (1-minute, 5-minute, daily since 2006), the nearest
expiry chain within ATM +-10 strikes with IV and OI, India VIX, MCX futures and options, BTC / ETH, and the arms' paper P&L.
For implied-vs-realised volatility trading itself see `research/books/bennett-trading-volatility.md` (not repeated here).

### Front matter: preface, Shiryaev biography and publication list (pp. v-xxx)
Shiryaev's career: the "disorder" (quickest detection) problem from Kolmogorov, optimal stopping theory, nonlinear
filtering, the semimartingale theory, limit theorems with Jacod, and American options as optimal stopping. No methods.

### Paper 1. Alabert and Gyongy - numerical approximation of the stochastic Burgers equation (pp. 1-15)
A finite-difference space grid for a turbulence equation driven by space-time white noise; the grid solution converges
at about the rate of one over the square root of the grid size. Pure numerics, no use for us.

### Paper 2. Arkin and Slastnikov - optimal time to invest under tax exemptions (pp. 17-32)
A real-options model: a firm's profit and its investment cost are two correlated geometric Brownian motions, and a tax
holiday lasts for the payback period. The best moment to invest is the first time the profit / cost ratio crosses a fixed
threshold, found by maximising a one-variable function; it is proven with variational inequalities. The same maths prices
perpetual American options on two assets (exchange, max options).
- What it computes: a "wait until a ratio crosses a fixed barrier" exercise rule; more uncertainty raises the barrier.
- How we could use it: none directly. Indian index options are European; no perpetual two-asset options trade here.
- Signal source: not a trading signal. Maps to NSE / MCX / crypto: no.
- Our research: not tested by us.

### Paper 3. Barndorff-Nielsen, Graversen, Jacod, Podolskij and Shephard - CLT for realised power and bipower variation (pp. 33-68)
The most practical paper for our data. Realised variance (the sum of squared intraday returns) estimates a day's
integrated variance. Bipower variation (the sum of products of absolute values of neighbouring returns, scaled by pi/2)
estimates the same continuous variance but ignores jumps, because a single jump enters only one of the two returns in a
product. So "realised variance minus bipower" measures the jump part; that was the motivation. The paper proves, for
prices with stochastic volatility (volatility may itself jump and may be correlated with price), that the estimation
error shrinks like one over the square root of the number of bars and is mixed normal, giving confidence bands.
Tripower and quad-power versions are stated, and cross-bipower versions for covariance. Most of the paper is proof.

#### 3.1 Realised variance, bipower variation and a jump test (Paper 3)
- What it computes: for one session, RV = sum of squared 1-minute (or 5-minute) log returns; BV = (pi/2) x sum of
  |r_i| x |r_i+1|; jump share = max(RV - BV, 0) / RV; a z-score for "a jump happened today" from the CLT, whose variance
  needs integrated quarticity (estimated by quad-power variation).
- Inputs: intraday index spot or futures bars; nothing else.
- How we could use it: (a) a cleaner daily realised-vol series for the VIX-vs-realised comparison (R102 uses
  close-to-close and Parkinson); (b) label jump days (gap-like moves inside the session, news) for Jarvis and as context for
  option sellers; (c) a candidate input to the size forecasts of R16 (continuous part and jump part forecast differently
  in the literature). Microstructure noise at 1-minute needs care (5-minute bars are the usual compromise).
- Signal source: underlying price (volatility measure, not direction). Maps to: NSE index spot / futures yes; MCX futures
  yes; crypto yes (24h sessions).
- Our research: realised vol is used in R102 (`DEEP_NIFTY_BANKNIFTY.md` 2.2: VIX over-states NIFTY's next 21 days on 81%
  of days, n 4,507) and in H29 (HAR-RV forecast); bipower variation and jump tests are not tested by us.

### Paper 4. Bingham and Schmidt - distributional vs temporal dependence on high-frequency returns (pp. 69-90)
A survey of copulas (Sklar's theorem), rank measures (Kendall's tau, Spearman's rho) and the lower tail-dependence
coefficient: the chance one asset falls below its VaR given the other did. The estimator is rank based: count the bars
where both ranks are among the lowest k, divide by k, and read the value off a plateau when k is varied. Then an
empirical study on GM and IBM 15-minute returns, 4 Jan 1993 to 29 May 1998, n = 36,855 bars, 27 bars a day.

#### 4.1 Intraday volatility seasonality and its removal (Paper 4, section 4.1)
- What it shows: returns are not autocorrelated, but squared and absolute returns are, with a strong daily cycle (high
  volatility at the open, low at midday, peaks at one-day lags) and long memory.
- What it computes: deseasonalised return = return / (average volatility of that time-of-day slot), where the slot's
  variance is the mean squared return in that slot over the sample; then a GARCH(1,1) per series (or a diagonal
  bivariate GARCH with Student-t errors) removes most of the remaining clustering.
- How we could use it: the same slot normalisation is the "usual range at this time" baseline that R16 beat; it is also the
  right way to compare a 09:15 bar with a 13:00 bar in any intraday feature.
- Signal source: underlying price. Maps to: NSE yes, MCX yes, crypto yes (hour-of-day).
- Our research: R16 (`FINDINGS.md`, "What is predictable?") found the range of the next 30/60 min predictable with
  holdout error 15-20% below the time-of-day average; direction was not (AUC 0.509-0.536). Agrees with the paper's
  picture (size clusters, sign does not). GARCH itself is not tested by us.

#### 4.2 Tail dependence depends on the filtering (Paper 4, section 4.4)
- Result: lower tail dependence was 0.15 on raw returns, 0.1 after the time-of-day weighting, and no plateau at all (tail
  independent) after GARCH filtering, while the plain correlation hardly moved (0.24, 0.23, 0.22). Non-i.i.d. data can
  manufacture tail dependence; conclusions about joint crashes depend on how the data were cleaned.
- Kendall-tau autocorrelation: the paper proposes an autocorrelation function built on Kendall's tau; the large one-day
  spikes in the ordinary ACF of squared returns vanish under it, so the linear ACF exaggerated them.
- How we could use it: before claiming NIFTY and BANKNIFTY (or GOLD and SILVER, BTC and ETH) "crash together more than
  correlation says", filter volatility first; useful for R72-type cross-index risk.
- Signal source: underlying price (risk measure). Maps to: NSE, MCX, crypto yes.
- Our research: R102 reports BANKNIFTY / NIFTY daily correlation 0.877 (n 5,086 days); tail dependence not tested by us.
  See also `research/books/fm-copula-methods.md`.

### Paper 5. Carkovs and Stoyanov - stability of two-scale Markov dynamical systems (pp. 91-108)
Slow and fast variables under Markov noise; averaging and diffusion approximation reduce the system, and stability is
read from the sign of a Lyapunov exponent. Engineering theory, no use for us.

### Paper 6. Cherny - some particular problems of martingale theory (pp. 109-124)
"Corner problems" from Shiryaev's seminar: when is a product of independent martingales, or a limit of martingales, still
a martingale (generally no unless bounded); a bounded strategy traded on a martingale need not give a true martingale
(explicit counterexample). Pure theory.

### Paper 7. Cherny and Urusov - separating times for measures on filtered spaces (pp. 125-168)
Two path models are indistinguishable before a stopping time and fully distinguishable after it; explicit forms for Levy
processes, Bessel processes and one-dimensional SDEs. Pure theory. One echo: in continuous time two models that differ in
volatility are told apart at once from the path, while models differing only in drift are not - volatility is observable,
drift is not, which fits our finding that move size is predictable and direction is not.

### Paper 8. Davis - optimal hedging with basis risk (pp. 169-188)
An option is written on an asset that cannot be traded (a stock basket, say) and is hedged with a correlated one that can
(index futures). The opening anecdote: traders hedged stock options with index futures at about 80% correlation with no
idea of the residual risk. With correlation rho, the untradeable share of the variance is 1 - rho squared: at rho = 0.707
half of it; at rho = 0.98 the independent part still carries about 19.9% of the volatility.

#### 8.1 Proxy (cross) hedging an option (Paper 8)
- What it computes: if rho = 1, hold Black-Scholes delta x (sigma_Y x Y) / (sigma_S x S) units of the traded asset (a
  "rescaled delta"). If rho < 1 (exponential utility, convex duality): hold a constant-cash "investment" position plus rho
  times a rescaled delta of a value function W that solves a stochastic-control PDE (no closed form). The fair
  (indifference) price is W at time 0. For rho near 1 the price is the Black-Scholes price (with an implied dividend
  yield) minus one half x risk aversion x (1 - rho squared) x the variance of the payoff: the buyer marks the option
  down for the unhedgeable part. Short calls cannot be handled under exponential utility.
- Evidence it reports (Monoyios's simulations, correlation 0.65 or 0.85, writing a put): the optimal hedge only modestly
  beats the naive rho = 1 hedge (more positive skew, higher median), and the hedge P&L standard deviation is comparable to
  the whole option premium, against 5-10% of the premium for the error from discrete rebalancing. Conclusion: with a proxy
  hedge you are investing, not hedging.
- How we could use it: any plan to hedge BANKNIFTY or FINNIFTY options with NIFTY futures, MCX options with another
  month's future, or a crypto option with a perpetual of a different underlying should assume large residual risk. At
  the 5-minute correlation of 0.84 (R102), about 29% of BANKNIFTY's 5-minute variance (1 - 0.84 squared) is not explained
  by NIFTY.
- Signal source: volatility / options structure (hedging, not a signal). Maps to: NSE yes (cross-index hedges); MCX yes;
  crypto yes.
- Our research: R72 (`HUNT_R72_CROSS_INDEX.md`) uses cross-index breadth as a filter, not as a hedge; proxy hedging is not
  tested by us.

### Paper 9. Delyon, Juditsky and Liptser - moderate deviations for ergodic Markov chains (pp. 189-210)
Large-but-not-huge deviation probabilities for averages along a Markov chain with Lipschitz functions, via the Poisson
equation; a statistical example for an autoregressive estimator. Pure theory.

### Paper 10. Di Masi and Stettner - risk-neutral vs risk-sensitive portfolio optimisation (pp. 211-226)
Assets whose drift and volatility depend on economic factors (dividend yield, P/E, short rates, inflation) that move as a
Markov process; long-only weights rebalanced each period.

#### 10.1 Factor-dependent Kelly (log-optimal) allocation (Paper 10)
- What it computes: maximising the long-run growth rate of wealth (expected log, i.e. Kelly) gives weights that depend
  only on the current factor values: each period choose the weights that maximise next-period expected log return given
  the factors. A "risk-sensitive" criterion with a negative risk parameter is more cautious than Kelly (it penalises the
  variability of growth, like fractional Kelly). The theorem: for a risk parameter close to zero, the Kelly rule is nearly
  optimal for the cautious criterion too.
- Inputs: a regime / factor state and conditional return distributions per asset.
- How we could use it: sizing paper arms by a state variable (VIX level, GEX sign, trend regime) is a legitimate
  "conditional Kelly"; the paper says a little extra caution barely changes the best policy. It gives no numbers.
- Signal source: none / sizing (state variables could be underlying or option chain context). Maps to: NSE, MCX, crypto
  sizing yes; live sizing changes must follow the "only lower risk" rule.
- Our research: H15 (`HUNT_H15.md`) found half-Kelly tradable on Liquidity but the Kelly fraction unstable (f* for BN 0.48
  on Jun 2023 - Nov 2024 and 0.14 on 2020 - May 2023, against 1.29 in-sample); quarter-Kelly cut P(drawdown >= 50%) to 1%
  at kappa 0.02. H17 / H36 / HUNT_FINAL then chose fixed lots.

### Paper 11. Engelbert, Kurenok and Zalinescu - reflected SDEs driven by symmetric stable processes (pp. 227-248)
Existence and uniqueness of a non-negative solution kept at zero by a reflecting push, with infinite-variance stable
noise. Pure theory.

### Paper 12. Fajardo and Mordecki - pricing, duality and symmetry in two-dimensional Levy markets (pp. 249-256)
Payoffs that depend on two assets and scale with them (exchange, swap, quanto options) are reduced by a change of
numeraire to one-asset problems in a "dual market", for European and perpetual American options, with jumps allowed.

#### 12.1 Put-call duality and the symmetry condition (Paper 12, section 4)
- What it computes: a call in a Levy market equals a put with spot and strike swapped and rate and dividend swapped, in a
  dual market whose jump distribution is the mirror image re-weighted by e^-y. The market is "symmetric" (puts and calls
  mirror each other, Carr-Chesney put-call symmetry) only if the jump measure satisfies that mirror condition; without
  jumps (Black-Scholes) it always holds.
- How we could use it: a persistent gap between OTM put IV and the mirror OTM call IV is the market pricing asymmetric
  jumps (crash risk). That is context for skew, not a trade.
- Signal source: option chain as context. Maps to: NSE index options yes; MCX options yes (thin); crypto options yes.
- Our research: R102 (`DEEP_NIFTY_BANKNIFTY.md` 3.4): the put five strikes out is dearer than the call five strikes out on
  91% of NIFTY days (median +2.56 IV points, n 1,222), so NIFTY options are priced as an asymmetric (crash-skewed) market.
  Not tested as a signal.

### Paper 13. Gasbarra, Valkeila and Vostrikova - enlargement of filtration and extra information, Bayesian view (pp. 257-286)
Extra information (an insider who knows the final price, or a "weak insider" who knows a noisy signal of it) is modelled as
a random parameter with a prior; the extra drift the insider sees is the posterior mean. Worked examples for Brownian
motion, gamma and Poisson processes conditioned on their end value.

#### 13.1 The value of an edge in Kelly terms (Paper 13, section 6)
- What it computes: for a log-utility (Kelly) investor the optimal fraction is drift / variance; the extra expected log
  growth of a better-informed investor is one half x (extra drift) squared x variance, summed over time = the
  Kullback-Leibler information between prior and posterior. An investor acting on a wrong prior loses the same kind of
  amount.
- How we could use it: a quick rule for sizing with an uncertain edge: growth lost to a mis-estimated edge rises with the
  square of the error, so when the edge estimate is noisy (as H15 found) a fraction of Kelly is the safer choice.
- Signal source: sizing, not a signal. Maps to: NSE, MCX, crypto sizing yes.
- Our research: H15 as above (Kelly fraction unstable across windows); not tested further by us.

### Paper 14. Gushchin and Zhdanov - a minimax result for f-divergences (pp. 287-294)
A statistician-versus-nature game where the loss is a divergence (Kullback-Leibler, Hellinger, total variation) between
nature's distribution and the statistician's choice; minimax equals maximin and a Bayes-mixture strategy is optimal. Pure
theory (universal prediction).

### Paper 15. Jack and Zervos - impulse and continuous ergodic control of a diffusion (pp. 295-314)
A central bank keeps a log exchange rate near target using a bounded continuous push (interest rates) and occasional
lump interventions that pay a fixed plus a proportional cost.
- What it computes: the optimal policy has five levels; inside an outer band it pushes toward the centre at full rate; at
  the upper or lower edge it jumps the rate back to an inner level (a band / "(s, S)" policy). Same shape as optimal
  rebalancing or delta-hedging bands under fixed plus proportional costs.
- How we could use it: as a principle only - with our Rs 70-76 round-trip costs, re-hedging or re-sizing should happen at
  band edges, not continuously.
- Signal source: none (control). Maps to: hedging logic only.
- Our research: not tested by us.

### Paper 16. Kabanov and Kijima - consumption-investment with production (pp. 315-332)
A firm invests irreversibly in production and freely in a complete securities market; the problem splits into a
production problem (stochastic maximum principle) whose value becomes the starting budget of a standard Merton
portfolio problem. Corporate finance theory, no use for us.

### Paper 17. Kabanov, Mishura and Sakhno - multiparameter Dalang-Morton-Willinger theorems (pp. 333-342)
Whether "no arbitrage iff a martingale measure exists" survives for two-parameter random fields; partial answers. Pure
theory.

### Paper 18. Kallsen - a didactic note on affine stochastic volatility models (pp. 343-368)
Explains semimartingale "characteristics" as a local drift / volatility / jump description, with rules for integrals,
smooth functions, measure changes and time changes. Affine models have a characteristic function solved by Riccati ODEs,
which makes Fourier option pricing, calibration and moment estimation fast.

#### 18.1 The affine SV model family (Paper 18, section 4)
- What it covers: stylised facts (heavy tails, volatility clustering, leverage = volatility rises when prices fall); two
  ways to add stochastic volatility (scale the size of moves, or run the price on a random "business clock" of trading
  activity); Heston (square-root variance, correlated with price), Stein-Stein, Barndorff-Nielsen-Shephard (variance
  driven by up-jumps that decay; several components for long memory; leverage through price drops when variance jumps),
  CGMY time-changed Levy models with price jumps, and a new class where a variance jump comes with a Gaussian price jump.
  Also: real-world (estimation) vs risk-neutral (pricing) measures and when the affine form survives the change.
- How we could use it: Heston or BNS fitted to NIFTY ATM IV and India VIX would give a model-consistent IV surface for
  repricing stale strikes (the R103 problem) and a Jarvis explanation of why VIX jumps on down days.
- Signal source: volatility / options structure (pricing model). Maps to: NSE index options yes; MCX options (thin) yes;
  crypto options yes.
- Our research: not tested by us; R103 repriced stale legs with Black-Scholes (`DEEP_OPTIONS_SELLING.md`). See also
  `research/books/fm-javaheri-vol-arb.md` for Heston / GARCH estimation.

### Paper 19. Katyshev - uniform optimal transmission of Gaussian messages (pp. 369-384)
Optimal coding of a Gaussian signal through a noisy channel with feedback; minimal reconstruction error computed.
Information theory, no use for us.

### Paper 20. Kawazu - a note on Brownian motion (pp. 385-392)
An essay on the central role of Brownian motion: diffusion in a random environment moves like log squared of time, not
its square root; a Laplace-transform computation and an open question checked numerically. Pure theory.

### Paper 21. Kluppelberg, Lindner and Maller - COGARCH versus Ornstein-Uhlenbeck volatility models (pp. 393-420)
The discrete GARCH(1,1) update (variance = constant + lambda x last squared return + delta x last variance) has a famous
continuous-time limit (Nelson) that needs two independent noise sources and loses GARCH's key feedback: a big price move
instantly raises volatility. COGARCH keeps one driver: each price jump raises variance in proportion to the squared jump,
and between jumps variance decays exponentially towards a floor.

#### 21.1 How volatility behaves after a shock (Paper 21)
- What it shows: in both COGARCH and BNS, volatility jumps up and then decays exponentially; returns are uncorrelated while
  squared returns are positively correlated with an exponentially decaying autocorrelation (vol clustering). Differences:
  COGARCH volatility never falls below a floor, and its returns and volatility have Pareto-type (power-law) tails under weak
  conditions, while BNS tails depend on the driver and can be light. COGARCH never has all moments finite.
- How we could use it: (a) a half-life model for "how fast does intraday volatility calm after a spike" - a size forecast,
  in line with R16; (b) power-law tails mean the worst day of a short-option book is far worse than normal-based limits
  suggest.
- Signal source: underlying price (volatility). Maps to: NSE yes, MCX yes, crypto yes.
- Our research: R102 measured NIFTY daily kurtosis 14.1 (n 5,087 days), worst day -12.98% (23 Mar 2020); R103's naked
  09:20 short straddle had a worst day of -Rs 28,876 on one NIFTY lot, more than two months of the -Rs 13k monthly stop.
  GARCH / COGARCH not fitted by us.

### Paper 22. Liptser and Novikov - tails of the supremum and quadratic variation of local martingales (pp. 421-432)
Extends a result for continuous martingales to ones with bounded jumps: if a martingale is stopped so that its final mean is
not zero, its running maximum and its accumulated variance must have heavy tails decaying like one over the level. Pure
theory; the echo is that no stopping rule on a fair game creates a free mean - it is paid for in fat tails.

### Paper 23. Lototsky and Rozovskii - SDEs and SPDEs by Wiener chaos (pp. 433-506, the longest paper)
Generalised solutions of stochastic evolution equations through the Wiener chaos expansion, without the usual regularity
assumptions; Malliavin calculus, regularity, a Feynman-Kac representation; applications to nonlinear filtering (a fast
online filter for a hidden diffusion state observed with noise), passive scalar transport and stochastic Navier-Stokes.
Pure theory and numerics; the filtering section is the only distant link (hidden regime or volatility as a filtered state).

### Paper 24. Mania and Tevzadze - a martingale equation of exponential type (pp. 507-516)
Existence and uniqueness of a BMO solution to an exponential martingale equation, used to describe the variance-optimal
martingale measure behind mean-variance hedging. Pure theory.

### Paper 25. Obloj and Yor - a local martingale and its running maximum (pp. 517-534)
Characterises every function of (price, running maximum) that is a martingale: the "max-martingales" of Azema and Yor,
built from the running maximum and the distance below it - which is the drawdown. A short discrete argument gives Doob's
maximal inequality (lambda x P(max >= lambda) is at most the expected final value on that event) and Doob's Lp
inequality. Applications: the Azema-Yor solution of the Skorohod embedding problem (stop the first time the price falls
to a set function of its running high - a trailing-stop shape), bounds on the law of the maximum given the law of the
final value (the Hardy-Littlewood barycentre bound), and new bounds for local time.

#### 25.1 Drawdown stops on a driftless price (Paper 25)
- What it computes: on a driftless (martingale) price, a stop of the form "exit when the price falls to f(running high)"
  produces a martingale outcome: it cannot raise the expected result, it only reshapes the distribution (our reading of
  the Azema-Yor construction). Conversely, the maximum a fair price can reach before such a stop is bounded by the
  barycentre function of its final distribution - the basis of model-free bounds for lookback and barrier options.
- How we could use it: as the theory behind our repeated finding that trails and give-back stops do not add value unless
  the underlying has drift: they trade a few big winners for many small ones.
- Signal source: trade management on the option premium or the underlying. Maps to: NSE, MCX, crypto (a crypto profit
  lock is still required by CLAUDE.md; this says its value comes from risk control, not from extra expectancy).
- Our research: R19b (`FINDINGS.md`): Boss's Rs 500 give-back trail on Liquidity lowered both books - holdout +Rs 4,584
  vs +Rs 14,633 on plain rules (27 trades) for the 15m book; it cut 12 of the 13 big winners. LIQUIDITY_TRAIL.md (keep 50%
  of the best profit) and R12 found the same.

### Paper 26. Peskir - the fundamental solution of the Kolmogorov-Shiryaev equation (pp. 535-546)
The "Shiryaev process" is the running statistic of Shiryaev's quickest-detection (change-point alarm) problem and has the
same law as the time-integral of a geometric Brownian motion, the core of Asian (average-price) option pricing. The paper
writes its transition density as an integral via Laplace-transform inversion (Bessel / hypergeometric functions,
Hartman-Watson law) and warns the formulas are numerically unstable for short times. Theory; the distant echo is that a
Shiryaev-Roberts change-point alarm on a regime statistic is a principled "the market changed" detector (not tested by us).

### Paper 27. Pham - irreversible investment with stochastic production capacity (pp. 547-566)
A firm buys capital irreversibly (with a cost) to expand a randomly depreciating capacity; singular stochastic control,
viscosity solutions, and an explicit threshold: invest just enough to keep capacity above a boundary. Corporate finance.

### Paper 28. Presman and Sonin - a Gittins-type index for randomly evolving graphs (pp. 567-588)
A decision maker tests independent projects that pay a reward, may stop the process or spawn new projects; each project
gets an index, the best policy always tests the highest index, with an optimal quitting rule; recursive computation; a
link to the Gittins index of multi-armed bandits (independent arms, frozen when not played). Theory; conceptually, choosing
which independent paper arm to keep testing is a bandit problem, but our arms are not independent (R72: Breadth Donchian
and Donchian share 77% of trades).

### Paper 29. Rasonyi and Stettner - existence of optimal portfolios in discrete time (pp. 589-608)
With a concave utility on positive wealth (log utility included) and no arbitrage, an optimal strategy exists whenever the
best achievable expected utility is finite. Pure theory.

### Paper 30. Sonin - optimal stopping of a Markov chain and the Poisson and Bellman equations (pp. 609-621)
The "elimination algorithm": remove states where stopping is clearly not optimal one at a time, recomputing the reduced
chain's transitions, until the stopping set is found; the same idea solves the discrete Poisson equation.
- How we could use it: if a trade's state (P&L bucket, time left, regime) were modelled as a finite Markov chain from our
  trade logs, this computes the exact optimal exit set. Our exit studies (R18, R19) tested exits empirically instead.
- Signal source: trade management. Maps to: NSE, MCX, crypto in principle. Our research: not tested by us.

## Performance claims
- Paper 4: GM-IBM 15-minute data (n = 36,855): lower tail dependence 0.15 raw, 0.1 after time-of-day weighting, none after
  GARCH; correlations 0.24 / 0.23 / 0.22. Descriptive, one stock pair, 1993-1998.
- Paper 8: the correlation arithmetic (at 0.98, about 19.9% of the volatility is unhedgeable) and the cited simulations
  (optimal hedge only slightly better than naive; hedge error comparable to the premium vs 5-10% for discrete hedging).
  The simulations are another author's; no data are shown in this paper.
- Paper 10: the log-optimal rule is near-optimal for mildly risk-averse investors - a limit theorem, no numbers.
- Every other paper makes no performance claim; they are theorems.

## Risk, sizing and psychology
- Sizing: Kelly (log-optimal) appears three times (Papers 10, 13, 29). The fraction is drift / variance; extra caution
  barely changes the policy when the risk parameter is near zero (Paper 10); the cost of a wrong edge estimate grows with
  its square (Paper 13).
- Risk: heavy (power-law) tails arise naturally from GARCH-type feedback (Paper 21); tail dependence can be created or
  destroyed by data cleaning (Paper 4); a proxy hedge leaves risk comparable to the option premium (Paper 8); stopping
  rules on a fair game buy their mean with fat tails (Papers 22, 25).
- Psychology: none. One trading-floor remark in Paper 8: traders' intuition was good on most option sensitivities but
  poor on correlation.

## How it relates to our research
- Agrees: "size is predictable, direction is not" (R16, R88, C1) matches the volatility-clustering facts in Papers 4, 18
  and 21 (squared returns correlated, returns not) and the observability point in Paper 7.
- Agrees: our trailing-stop failures (R19b, LIQUIDITY_TRAIL.md, R12) are what the max-martingale result predicts for a
  driftless price (Paper 25).
- Agrees: R102's crash-skewed chain (puts dearer on 91% of NIFTY days) is an "asymmetric market" in Paper 12's sense, and
  R102's kurtosis of 14.1 plus R103's naked-straddle worst days fit Paper 21's power tails.
- Consistent with H15: the Kelly fraction is fragile; the papers give the theory of why a fraction of Kelly is safer.
- Not tested by us: bipower variation / jump tests, GARCH or COGARCH fits, tail dependence across indices, proxy hedging,
  Heston or BNS fits to the NIFTY surface, change-point alarms.

## What is new to us
- Bipower variation as a jump-robust realised volatility, and RV minus BV as a same-day jump measure (Paper 3).
- Vol-filtering before measuring cross-index tail dependence, and the Kendall-tau autocorrelation (Paper 4).
- The basis-risk arithmetic for cross-hedges and the indifference-price discount (Paper 8).
- Conditional (factor-dependent) Kelly and the squared-error cost of a wrong edge (Papers 10, 13).
- COGARCH: one-driver volatility that jumps with price shocks and decays exponentially, with power-law tails (Paper 21).
- Put-call symmetry as a test of jump asymmetry in the chain (Paper 12).
- Optimal no-trade bands under fixed plus proportional costs (Paper 15) and exact optimal-stopping algorithms for exits
  (Paper 30).

## Could not read
- Veretennikov, "On lower bounds for mixing coefficients of Markov diffusions" (contents p. 623): missing from the PDF,
  which has 658 pages and ends with Sonin's references (book p. 621). Only its one-line preface summary is known (such
  diffusions can have long memory and heavy tails).
- Formulas throughout came out as broken symbol strings; the theorem statements were read from the surrounding prose.
- Figures are images: Paper 4's ACF plots, intraday volatility curve, copula scatter plots and tail-dependence plots
  (only captions and axis numbers; the quoted values are from the text); no other paper relies on figures.
- Long proof sections (Papers 3, 7, 23 especially) were read for structure and results, not line by line.
