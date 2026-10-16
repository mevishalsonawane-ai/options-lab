# R74: the strategy map (what has been tried, what the outside evidence says, what is left)

16 Oct 2026. Research only (no `android/**`). Boss, 16 Oct: *"Except Donchian, what all things could we think of as a
strategy or arm? Deep research."*

Part A (this section) is the map. Part B (the test of the best untried ideas) is pre-registered in
`research/hunt/r74/PREREG.md` and its results are added at the end of this file once read.

## The short answer

- **Almost every popular family has already been tested here, and nearly all of them failed after real costs.** The
  experiment log holds **1,190 entries** (about 9.3 million variants counted, a lower bound). Over 40 hunts (H1-H47,
  R1-R72, F1, B2, IV1, OF1) the repository has tested candles, chart patterns, about 25 indicators, ORB, sweeps,
  SMC, Gann and Fibonacci, volume profile, order flow, OI, PCR, GEX, max pain, IV, straddles, expiry-day "hero" trades,
  overnight holds, gaps, global cues, FII flows, calendar and event days, ML and neural nets, and about 35,000 exit
  variants.
- **Only five ideas have passed the full gates on the recent market (Nov 2024 onward):**
  1. **Donchian 20**: the R51 re-tune.
  2. **Open outside value (OOV)**: H37-10, re-tested in R52.
  3. **Solo 2.0**: the R57 A2 meta-labeller.
  4. **Breadth Donchian**: R72 C1. It is a filter on Donchian, not a new edge.
  5. **The A4 ladder exit**: R64.

  Liquidity 15+5 is the older edge from the BANKNIFTY two-year study. It was never gated on the recent market, and it
  was weak there (R51/R70).
- **The outside evidence agrees with what we found.**
  - SEBI's own studies show 87.7-93% of individual F&O traders lose money. Its Aug 2026 study (FY26) found that 82% lose
    even before costs.
  - There is no peer-reviewed, after-cost evidence of an intraday long-option edge on NIFTY or BANKNIFTY.
  - The strongest academic return effect for an intraday index trader is **"intraday momentum into the close"** (Gao et
    al. 2018; Baltussen et al. 2021). It is strongest on high-volatility days. On India it is only a preprint, and the
    edge is about the size of option costs.
- **What is genuinely left untried** is a short list, ranked below:
  1. late-day momentum on high-volatility days;
  2. the India VIX vs index divergence (never re-tested on the recent market);
  3. stock-breadth thrusts from the 59 large caps' VWAPs;
  4. volume-confirmed index breaks (the index itself has no volume, but its stocks do);
  5. VIX-confirmed breaks;
  6. three risk tools rather than signals: volatility-targeted sizing across the passed arms, a no-new-buys window
     before scheduled events, and a delta-based strike choice.

  The first five are tested in Part B.
- **Honest expectation:** most of these will fail too. Buying options intraday is hard after the 2026 STT rise (0.15%
  on the sell side), theta and the spread. Breadth Donchian is the best thing found so far, and it is Donchian's own
  trades, filtered.

## 1. What has already been tried (by family)

Sample sizes are the studies' own. Periods:
- **"Old hunt"**: Dhan 1-minute data from 2020/21, with a locked holdout from Oct 2025 to Oct 2026, read once.
- **"Recent"**: design 20 Nov 2024 - 31 Mar 2026, final 1 Apr - 13 Oct 2026 (133 sessions). This final window has now
  been read by R49-R72, so every new pass on it is weaker evidence than the last.

Signal source is the underlying unless marked OPT (the option's own chart) or CHAIN (option-chain data).

| family | ideas tried (write-ups) | variants (approx.) | samples | verdict and why |
|---|---|---|---|---|
| ORB / opening range | FINDINGS arms, OBUY_GA OR-01..08, H20, H21, R3, R14, R20/R20b, R22, R23, R49 (336 re-tunes), R56 | 2,000+ | BN 2y; 2020-26; recent | **FAIL everywhere.** ORB alone lost Rs 1,93,592 on 1,109 trades in one BN year. R49 found 0 of 336 on the recent market. Only R14's re-entry cap was adopted. The family is parked. |
| Liquidity sweeps / levels | LIQUIDITY*, H9, H11, H23, H8, H35, H44, H45, R19*, R43, R44 | ~600 | BN Aug 2021 - Oct 2026, 1,651 trades | Liquidity 15+5 BANKNIFTY is the one old edge: about Rs 65-167 a day, never formally gated. On the recent market it made only +Rs 10,978 in the final (p 0.70). It fails on other indices. |
| Candles and chart patterns | H25 (**195 signals** x follow/fade x 5 indices x 6 timeframes x 18 exits = 210,600), GREEN/RED_CANDLES, H18, H32, R24, R39, R40, **R71** (8 patterns, 16 tests) | 210,000+ | full lake | **FAIL.** H25 averaged -Rs 146 net a trade and was no better than random (SPA p 1.00); 28 of its 31 gate-passers lost in the holdout. R71 passed 0 of 16. |
| Indicators (EMA, Supertrend, RSI, MACD, Bollinger, ADX, TAS, ribbons, Donchian) | ST_EMA_PINE, TAS*, MATRIX*, CHANDELIER, OBUY_GB, H25, H46, R29 (165), R30, R35, **R44** (Boss's 5-year sweep, 112 cells), F1 (11,772) | 230,000+ | 2020-26 | **FAIL, except Donchian 20 on 5-minute index bars.** It is the R51 re-tune: -15% / +30% / 30 min, 1-ITM. In the final it made +Rs 55,439 on 1,787 trades (twin p 0.002, q 0.025). It is thin: at twice the slippage, design was -Rs 1.1 lakh. |
| Cross-index / breadth / heavyweights | **R72** (6 rules, 12 tests), H6, H39, SCALP_1MIN A/D, R1 N15 (stock A/D), F1 breadth, DIRECTION_MODEL | ~60 | recent | **Breadth Donchian (R72 C1) PASSED:** final 1,202 trades, +Rs 1,71,366, p 0.008, q 0.032. It is 77% Donchian's own trades (correlation 0.80). Laggard, lonely-break fade, leader, thrust and SMT divergence failed. |
| SMC / order blocks / volume profile / Fib / Gann / Elliott / Wyckoff | ADVANCED, MATRIX, H37 (19,034), R4 (660), R5, R6, R9, R62 (96), R62b (288), R61b | ~20,000 | | **FAIL, except H37-10 "open outside value" (OOV).** In the R52 re-test: final 294 trades, +Rs 48,772, p 0.001, q 0.027. |
| VWAP | H46, OBUY_GB TI-04/05, MR-05, TREND_FILTER, R10, F1 C1/C2, R63 | dozens | | **FAIL as entries.** The R63 T3 VWAP exit passed on the Hero ticket only. |
| Order flow | R7, R8, R10, OF1, B2, H39, R61b | ~100 | depth snapshots; 2012-26 participant data | **FAIL.** Order flow explains the candle as it forms (AUC 0.83), but it does not predict the next one. |
| OI / option chain (walls, PCR, max pain, GEX, gamma flip, skew) [CHAIN] | H26 (2,268), H38, OI_FILTER (145), R15, R24, R26, R28 (388), R36, R37, IV1, R47 C1-C3 (4,560), OBUY_GC EXP-04 | ~3,500 | | **FAIL.** GEX predicts the size of the move, not its direction. Max pain did worse than "no change" (R16). The R47 PCR rule and the A1/A2 OI vetoes are in shadow only. |
| IV / volatility (long straddles, IV vs RV, VIX) | LONG_VOL (66+), STRAD_INDEX, VOLATILITY, R34 (IV <= theta x forecast RV), R36, R47 U9_vix (1,024), R15, OBUY_GC VOL-01..07, R1 N9/N13 | ~1,500 | | **FAIL for buyers.** The index moved only 0.69x what the straddle priced. The R1 N13 VIX/index divergence was the only weak lead (positive in both old periods, BH q 1.0). It is **never re-tested on the recent market**. |
| Expiry day / zero-to-hero / pinning | EXPIRY_*, OBUY_GC EXP, R21, R25, R26, R34, R35, R37-R40, R43, R50, R54, R55 | ~1,200 | 33-770 expiries | **FAIL.** R43: 0 of 170; R50: 0 of 28. Every expiry-afternoon state had negative expectancy. The Hero Rs 1,000 ticket runs on paper only because Boss chose it. |
| Overnight / gap / global cues | H27 (GIFT, US close, ES/NQ, Asia, ADRs: 234 + 45 tests), H31 (4,080), X2, R27, R41, R47 U2_gap (3,840), OBUY_GA OR-07/08, R51 Night | ~9,000 | | **Direction is real, but money is unproven.** Global cues are priced into the gap. The Night R3 arm was paper-only and is now hidden. |
| Calendar / events / time of day | H28 (42 flags, 8,820), H33 (3,072), EVENT_SHIELD, ENTRY_CUTOFF, BIG_BARS, R1 N3/N5/N10/N14, R47 E1-E3 | ~12,000 | | **FAIL as trades.** Event days are bigger, not directional (BN policy days had 1.48x the range). Entries after 14:30 lose. EventShield is in shadow. |
| Intraday momentum (Gao / Baltussen) | MARKET_HOW 3c (measured), R1 N4 (rest-of-day to 15:10, old data), OBUY_GA TD-02, R34 Q1 (hero, OTM) | ~30 | 1,515 NIFTY days | **Measured flat:** first half-hour vs last half-hour correlation -0.08 to 0.00. N4 lost on the old data. **The version conditioned on high-volatility days, which is where the papers find the effect, was never tested.** |
| Mean reversion after extreme moves | REVERSAL_SETUP, R13, OBUY_GB MR-01..06, R44 rsi_fade, R71 P7, R72 C3, R1 N14, H42-3/8/9 | hundreds | | A small reversal is real, but it **never pays option costs.** |
| ML / neural nets | H1, H44, OBUY_SEARCH (26M rules), DIRECTION_MODEL, R30, R41, B2, BRAIN_V1, R57, R60, R61 | >26M | | **FAIL as a direction forecaster** (AUC 0.48-0.54). The R57 A2 meta-labeller (Solo 2.0) PASSED: final 128 trades, +Rs 99,172, q 0.01. |
| Exits / trade management | R12, R18, R19*, R38, R63-R70, LIQUIDITY_*, JARVIS_EXITS, H34, H45 | ~35,000 | 2,200-5,400 trades | **Adopted:** the A4 30/60/100 ladder (R64), L25 (Boss's comfort rule; noise), and Liquidity's 15% + index + time stops. Everything else is no better. |
| Sizing / portfolio | H4 (risk parity, old arms), H5, H7, H10, H15, H17, R17, RC1 (crypto vol-target) | ~150 | | Fixed lots adopted (H17). Risk parity needed Rs 60L-1Cr (H4). **Volatility-targeted sizing across today's passed arms has never been tested.** |
| Option structure | H6, H13, H16, H29, R47 (ITM3..OTM5, debit spreads), R48 (8.8M), SWING_DEEP debit spreads, SELL_LEVELS, NONDIRECTIONAL, BOTH_SIDES, EXPIRY_SAME_DAY | ~9M | | **Keep 1-ITM, nearest expiry.** OTM, cheap options, debit spreads and long straddles all fail. Short straddles were positive, but that is selling (not app-eligible). **A delta-based strike choice was never tried** (strikes have always been fixed steps). |
| Stocks | H2, H8, SWING_DEEP, R58 (38 rules), R59, R59b | ~400 | | **FAIL.** H2 had a real pre-cost edge, but price impact caps it at about Rs 330 a day. |
| MCX / gold / crypto | M1-M4, GOLD_* (~22 docs), R2, R42, R53, NN_*, STRAD_*, C1, C2, RC1, RC2, R46 | ~1,250 | | **Nothing passes the gates.** The gold and MCX paper arms are "not proven". No crypto arm exists (CLAUDE.md crypto rules). |

**The arms in the app today** (from the arm code; read only):

| arm | basis | status |
|---|---|---|
| Liquidity 15+5 | old two-year study | paper now; the only arm with a live path, and it needs the PIN |
| Donchian 20 | passed (R51) | paper only |
| OOV | passed (R52) | paper only |
| Solo 2.0 | passed (R57 A2) | paper only |
| Solo midday | not proven | paper |
| Hero Rs 1,000 ticket | failed (R50/R54/R55) | paper, because Boss chose it |
| Watchlist | near misses | shadow, never trades |
| MCX paper arms | not proven | paper, off by default |

- Hidden on 15 Oct: ORB family, Hero Rs 5,000, Night R3, VIX divergence.
- Breadth Donchian (R72) is handed over in `research/hunt/r72/APP_SPEC.md` but is not in the app yet.
- **R73 (trend / wait) is not on this branch**, so it is not counted here.

## 2. What the outside evidence says, family by family

Sources were opened and read unless marked *(abstract/snippet only)*. "Fit" means Boss's style: index signal, buy the
option, intraday, paper first. "Here" is what this repository already did.

| family | mechanism | best evidence | quality | India | fit | data we have | tried here? |
|---|---|---|---|---|---|---|---|
| **Intraday momentum into the close** | Late-informed traders, plus dealers short gamma hedging with the move near the close | Gao, Han, Li & Zhou (2018), "Market intraday momentum", *JFE* 129(2), SSRN 2440866: SPY 1993-2013, first half-hour predicts last half-hour, R² 1.6% (out of sample ~1.7%), **stronger on high-volatility and high-volume days**. Baltussen, Da, Lammers & Martens (2021), "Hedging demand and market intraday momentum", *JFE* 142(1), SSRN 3760365: 60+ futures 1974-2020; the rest-of-day return predicts the last 30 min (Sharpe 0.87-1.73 before costs); driven by negative dealer gamma. Li, Sakkas & Urquhart (international, 12 of 16 markets). | strong (US/developed) | Motwani, Burrin & Sharma (2024), ResearchGate preprint *(abstract only)*; no peer-reviewed NIFTY study | good: one trade a day, 30 min, index signal | yes | measured flat unconditionally (MARKET_HOW); N4 failed on old data; **high-volatility-day version untried** |
| Noise-area intraday momentum | Moves beyond the usual noise band from the open continue | Zarattini, Aziz & Barbon (2024), SFI RP 24-97, SPY: Sharpe 1.33 after costs (practitioner working paper) | moderate (US) | none | medium | yes | **R1 N1: failed** (NIFTY -Rs 297 a day in the holdout) |
| Time of day / day of week | U-shaped volatility and volume from information arrival and inventory | Sampath & Gopalaswamy (2020), *J. Emerging Market Finance* 19(3), NSE tick data: U-shape, midday not abnormal once volume is controlled. Singh & Gangwar (2018), MPRA 89689, NIFTY futures. Day-of-week effects mostly faded after rolling settlement. | strong for volatility, none for direction | yes | as a stop/target scaler only | yes | H28, ENTRY_CUTOFF: size yes, direction no |
| Expiry pinning / max pain | Dealers' hedging pulls the price to big strikes | Ni, Pearson & Poteshman (2005), *JFE* 78(1); Golez & Jackwerth (2012), *JFE* 106(3) (S&P pins on serial expiries) | moderate (US) | Chhimwal, Pandey & Pandey (2025), *Rev. Derivatives Research* 28(3) (expiry changes raise volatility); **no peer-reviewed max-pain study: anecdotal** | poor: favours sellers, 0DTE theta | yes | R16, EXPIRY_PLAYBOOK, hero series: FAIL |
| Overnight vs intraday returns / gaps | Different clienteles; an overnight risk premium | Lou, Polk & Skouras (2019), "A tug of war", *JFE* 134(1) | strong (US) | blogs only (NIFTY's gain is overnight, the intraday drift is ~0) | poor: the gap is priced at the open | yes | H27, H31, R47 U2: FAIL / unproven |
| Global cues | US moves feed the Indian open | Camilleri (2010), CRC volume: India reacts early to the prior US day, low explanatory power. NSE research paper 39 *(snippet only)*. Sundararajan & Balasubramanian (2023), *IJoEM*: SGX led price discovery. | moderate for the gap, none after 09:15 | yes | poor | partly | H27: priced into the gap |
| **Volatility risk premium / when to buy** | Implied volatility is above realised, so buyers pay | Coval & Shumway (2001), *JF* 56(3); Bondarenko (2014), *QJF*; Muravyev & Ni (2020), *JFE* 135: the premium is earned overnight. **Bhat, Pandey & Rao (2024), *J. Futures Markets* 44(8), NIFTY options 2017-2020: short options earn overnight; intraday returns are not significant** | strong, India-replicated | yes | **a rule, not an edge: never carry long options overnight** (the app already squares off) | yes | STRAD_INDEX (0.69x), LONG_VOL, R34: buyers lose |
| **India VIX vs index (spot-vol relation)** | Volatility rises when the index falls (leverage/fear). A rally on rising VIX is fragile; a fall on falling VIX is a shakeout. | Thenmozhi & Chandra (2013), NSE Working Paper 9/2013: asymmetric negative VIX-NIFTY relation, VIX-change switching. Shaikh & Padhi (2014), IIM Calcutta. | moderate | yes | good: index signal, VIX as context | yes (VIX minutes) | **R1 N13: the only weak lead (old data, q 1.0); never re-tested on the recent market** |
| Event days (RBI, Budget, CPI, FOMC) | Implied volatility builds into the release and collapses after it (IV crush) | Lucca & Moench (2015), *JF* 70(1) (pre-FOMC drift); Kurov, Wolfe & Gilbert (2021), *FRL* 40 (the drift disappeared after 2015); Donders & Vorst (1996), *JBF*. **Shaikh & Padhi (2013), *Margin* 7(4): India VIX rises before RBI/CPI/WPI/IIP/GDP releases and falls after.** RBI WP 03/2024 (Gupta et al.): Sensex responds to path surprises. | moderate (India) | yes | as a "no new buys before the release" risk rule | calendar partial | H28, EVENT_SHIELD (shadow), LONG_VOL events: FAIL as trades |
| FII / DII flows | Foreign flows push prices | IIMB Management Review (2016): returns drive FII flows more than the reverse. Groww digest 2007-2026 *(non-peer-reviewed)*: about 50/50 next-day. | weak | yes | poor (published after the close) | participant OI yes; DII cash no | H40, OF1: predicts the gap, not the day; FAIL after costs |
| Mean reversion after extreme moves | Overreaction, liquidity provision | Fung, Mok & Lam (2000), *JBF*; Grant, Wolf & Yu (2005), *JBF*: S&P futures reversals fade after the bid-ask. Tripathi & Aggarwal (2015) on NSE stocks *(snippet only)* | weak-moderate | stocks only | poor: fades buy the side whose IV just jumped | yes | many: FAIL |
| VWAP / order-flow imbalance | Order imbalance moves price | Cont, Kukanov & Stoikov (2014), *J. Fin. Econometrics* 12(1): contemporaneous. Tripathi, Dixit & Vipul (2021), *FRL* 41: NSE stock imbalance predicts about 5 minutes ahead | strong but seconds-to-minutes | yes | poor: too short for an option buyer | stock minutes only | R7-R10, OF1, B2, H46: FAIL |
| Option-chain context (PCR, OI, skew, GEX) [CHAIN] | Positioning and hedging flows | Economies 7(1):24 (2019), NSE 2001-2013: PCR predicts at about 2.5-12 days, daily only. Pan & Poteshman (2006, *RFS*): index PCR null in the US. GEX: Baltussen et al. mechanism. | weak (India) | yes | allowed only as a filter | yes (lake chains, R28 GEX) | many: FAIL |
| **Breadth / participation** | A move that most stocks join is a real move; a narrow move is noise | Practitioner (Zweig breadth thrust, daily); Gao et al.: momentum stronger on high-volume days. Constituent-to-index lead-lag: none found peer-reviewed. | weak-moderate | none rigorous | good as a confirmation | **yes: 59 large caps with volume, Sep 2024 onward** | R72 C1 passed (index breadth); N15 stock A/D failed (old, levels only); **stock-VWAP thrust and stock-volume confirmation untried** |
| **Volatility-targeted sizing** | Scale exposure inversely to recent volatility; drawdowns shrink | Moreira & Muir (2017), *JF* 72(4); Harvey et al. (2018), *JPM* 45(1) (tail risk cut in every asset class); counter: Cederburg et al. (2020), *JFE* 138 (no systematic out-of-sample Sharpe gain); MacLean, Thorp & Ziemba (2010) (fractional Kelly) | strong for drawdowns, mixed for returns | none | **fits the LIVE "only lower risk" rule** | yes (VIX, the arms' trades) | **not tried for the index arms** |
| Option structure for the same signal | ITM/ATM pays less extrinsic value per unit of delta; spreads cut vega and theta but cap the right tail | Coval & Shumway; Bondarenko; Agarwal, Ghosh, Prabhala & Zhao (2025), "Animal Spirits on Steroids", FMA WP: Indian retail losses concentrate in cheap OTM options; Sinclair (2020), *Positional Option Trading* (book). **No study compares structures for the same signal.** | moderate (against OTM) | yes | good | yes (ATM+-10 chains) | 1-ITM kept; spreads failed (R47); **delta-based strike untried** |
| Trend-day / NR7 / Crabel | Volatility contraction then expansion | Crabel (1990), *Day Trading with Short Term Price Patterns and Opening Range Breakout* (book, no peer review) | weak | none | medium | yes | H25, R71 NR7/inside bar: FAIL |
| Turn of the month / holidays | Month-start flows | Satish & Bheemanagouda (2023), *Rev. Finance and Banking* 15(2): Indian turn-of-month effect, a few bp a day. Calendar anomalies in India are diminishing (Inderscience 2020). | weak-moderate | yes | a tilt only | yes | H28, R52 H42-7: FAIL |
| New closing auction (from 3 Aug 2026) | A structural change at the close | NSE closing auction session for F&O stocks *(sources differ on the exact windows; how the index close is now built is unverified)* | n/a | yes | unknown | about 2.5 months only | too little data |

**Cost and regime facts behind every row:**
- **SEBI studies:**
  - 25 Jan 2023 (FY22): 89% of individuals lost money.
  - 23 Sep 2024 (FY22-24): 93% of over 1 crore individuals lost, about Rs 1.81 lakh crore in all.
  - 7 Jul 2025 (FY25): about 91% lost.
  - Aug 2026, Patankar & Rath, SEBI DEPA (FY26): 87.7% lost and 82.1% lost even before costs. Options were 92% of the
    losses. 99% of FPI and prop profits came from algos.
- **SEBI circular of 1 Oct 2024** (from 20 Nov 2024): one weekly expiry per exchange, larger contracts, and premium
  collected upfront. That date is exactly where our "recent" window starts.
- **STT** on the option sell side rose to 0.15% from 1 Apr 2026. Our engine already uses the dated rate.

## 3. What is left: the untried families, ranked

Scored on outside evidence x fit with Boss's style x data in the lake x low overlap with Donchian / Breadth Donchian
(H = high, M = medium, L = low).

| rank | candidate | evidence | fit | data | overlap with Donchian | one-line reason | Part B |
|---|---|---|---|---|---|---|---|
| 1 | **Late-day momentum on high-volatility days** (rest-of-day return at 14:45 -> buy that way for the last 30 min, only when today's range is above normal) | H (US, 45 years) / L (India) | H | H | **L** (fixed 14:45, one trade a day) | the strongest academic return effect, tested here only unconditionally | **M1** |
| 2 | **India VIX / index divergence, faded** (index up on rising VIX -> put; down on falling VIX -> call) | M | H | H (VIX minutes) | **L** | the only weak lead of R1's 15 new ideas; never gated on the recent market | **V1** |
| 3 | **Stock-breadth thrust** (share of the 59 large caps above their own VWAP jumps from <= 30% to >= 70% within 30 min -> calls; mirror -> puts) | L-M | H | H (Sep 2024 onward) | M | uses the stocks' real VWAP, which the index cannot have; R72's breadth worked | **B1** |
| 4 | **Volume-confirmed Donchian break** (index break only when the large caps' volume in that bar is >= 1.5x normal for that time of day) | M (Gao: high-volume days) | H | H | H (a filter on Donchian) | volume confirmation was never possible on the index itself | **B2** |
| 5 | **VIX-confirmed Donchian break** (call breaks only while VIX is falling over the last 30 min; put breaks only while it is rising) | M | H | H | H (a filter) | the spot-vol relation as a confirmation, the regime filter the brief asks for | **V2** |
| 6 | Volatility-targeted sizing across the passed arms (fewer lots when VIX or recent arm volatility is high) | H for drawdowns | H (lowers risk only) | H | n/a (sizing) | the account stop (-Rs 40k / -Rs 13k a month) is the binding limit; Breadth Donchian's design drawdown was Rs 1.99 lakh | later (R75): a portfolio study, not an arm |
| 7 | No new buys in the window before scheduled events (RBI, CPI, Budget, FOMC, US CPI) | M (India IV crush) | H (lowers risk only) | partial calendar | n/a | EventShield already runs in shadow; measure it on the passed arms | later, with EventShield |
| 8 | Delta-based strike choice for the same signal (e.g. the strike nearest delta 0.6-0.7 instead of a fixed 1-ITM step) | L-M | H | H | n/a (structure) | cost saving, not alpha; no literature compares structures | later |
| 9 | The new closing auction (Aug 2026) | none yet | ? | 2.5 months | L | a real structural change, but far too little data | wait |
| 10 | Sector-index rotation | weak | M | **no sector indices in the lake** | M | needs new data | needs data |

**Already tried, so not re-proposed:** ORB in every form, sweeps, candles, chart patterns, about 25 indicators, SMC,
Gann/Fib, volume profile (beyond OOV), order flow, PCR, OI walls, GEX, max pain, IV-cheap buys, straddles, hero
trades, gaps, overnight, global cues, FII flows, calendar flags, plain time-of-day, mean reversion, VWAP entries, ML
direction forecasts, debit spreads, OTM strikes, and the cross-index rules of R72.

## 4. Honest notes for Boss

1. **Most single-chart patterns fail here, and the reason is now clear.** A random 1-ITM option buy loses about Rs 100-300
   a trade at 1 lot (theta, spread, STT, charges). A pattern has to beat that before it can show a profit. Hundreds
   could not.
2. **The passes are thin and share a window.** Donchian's final profit depends on a few big days (the top 5 trades are 76%
   of its net). Breadth Donchian is Donchian filtered. The 1 Apr - 13 Oct 2026 window has been read by about 25 studies,
   so a new pass there is weak evidence. Paper trading from 14 Oct onward is the only truly unseen data.
3. **The academic effects are small.** Intraday momentum is about 2-3 bp of index a day in the US. After converting
   through delta 0.5-0.7 and paying the 2026 costs, the edge is about the size of the costs.
4. **The best evidence-based improvements are risk rules, not new signals:**
   - Never hold long options overnight (already true).
   - Do not buy into scheduled events.
   - Size down when volatility is high.

   Each of these only lowers risk, so it could later be considered for live through the shadow path.
5. **Signal source:** every Part B rule reads its signal from the index (and, as context, India VIX or the large caps'
   prices and volume), then buys the 1-ITM option. None reads an option's own chart.
