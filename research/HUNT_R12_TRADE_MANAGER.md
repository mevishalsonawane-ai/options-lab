# HUNT R12: the trade manager - which ways of managing an open option buy are worth having?

Written 10 Oct 2026 by R12 for Boss. Option BUYING only, Rs 1 lakh, fixed 1 lot, no partial exits by the manager, real
costs (app fills, app charges, the h24 half-spreads), exits on the option's 1-minute wicks, stop first on a tie. Design
period = everything before 1 Oct 2025. Holdout = 1 Oct 2025 - 6 Oct 2026, read once (`HOLD_READ`: 10 Oct 2026 09:48 UTC,
after `research/hunt/r12/PREREG.md` was written and hashed).

- Code: `research/hunt/r12/`
  - `build.py`: rebuilds each strategy's original trades with its own source code;
  - `parity.py`: checks the replay against those sources;
  - `lib12.py`: the minute replay (original exits plus EXIT / TRAIL / EXTEND), costs, BH, White RC;
  - `specs.py`: the 14 specialists;
  - `loop.py`: the design loop;
  - `holdout.py`: the one holdout read;
  - `PREREG.md`: the frozen rules.
- Outputs (30 MB): `scratchpad/hunt/r12/`
  - `loop_log.csv` (every configuration), `loop_rounds.*`, `singles.csv` (twins + BH);
  - `whiteRC.json`, `final_design.json`;
  - `holdout*.csv/json`, `loopA/` (the rejected first loop).
- No secrets were read or printed.

## The answer in plain English

1. **On the strategies that make money, every manager we could test made them worse.** That covers Liquidity BANKNIFTY,
   VIX divergence and Hero. Out of sample, the best-looking rules lost money in the walk-forward:
   - Liquidity: -70k in the first loop, about 0 in the second;
   - VIX divergence: -7k NIFTY, -21k BANKNIFTY;
   - Hero: -8k.

   No rule survived the luck check (White's reality check p 0.65-0.87). Their original exits already work; early exits
   mostly cut the few big winners that pay for everything. **Keep their original exits. Run the manager in SHADOW
   only.**
2. **On the losing ORB arms, almost ANY early exit "helps", including random ones.** These arms lose about Rs 85-165
   a trade after costs, so cutting trades short saves money whatever the rule is. Each rule was therefore compared with
   the same number of random early exits ("random twins"). That comparison is the real test.
3. **One rule beat random exits in the design period AND in the holdout, on ORB Fresh.** The rule:
   - exit if, 20 minutes after entry, the option has never traded 3% above the entry ("no progress");
   - or exit if a minute closes 12% below the entry;
   - plus a chandelier lock (best high minus 3 option-ATRs).

   Results:
   - holdout: +Rs 14,365 over 474 trades, twin p 0.001, BH q 0.008;
   - design walk-forward: positive in all three folds.

   ORB Fresh still loses money with it (-Rs 59.7k instead of -Rs 74.0k in the holdout). It is a damage limiter, not an
   edge.
4. **ORB and Range Fade improved in the holdout too, but no more than random exits would have.**
   - ORB: +12.7k, twin p 0.15. Range Fade: +28.1k, twin p 0.49.
   - ORB Sweep's rule lost 1.9k.
   - So these three stay in SHADOW.
5. **The "no progress" exit (MFE time stop) is the most consistent specialist we found.**
   - It beat random exits on ORB, ORB Fresh and Range Fade in the design period.
   - It helped ORB and ORB Fresh again in the holdout.
   - It is also the best-supported idea in the outside evidence (Part A).
6. **Target extension: there is no evidence either way.**
   - The current manager's extension, rebuilt without its live-only flow condition (VWAP + value area + OI + calm
     VIX, 90% of the way to target), fired on 0-4 trades a year. It moved results by Rs -1k to +1k in both periods.
   - No extension rule was ever picked on its own merit.
   - The ratcheting-lock arithmetic is safe; its benefit is unproven.
7. **Order flow, delta divergence, absorption, traps, news, consensus and data checks cannot be tested on history.** NSE
   history has no tape or book.
   - The only recordings are the one 33-minute MCX evening in R11 Part B, and no strategy traded in it.
   - In that sample the 10-second order-flow imbalance barely predicted the next 60 seconds (correlation 0.05 on crude
     oil, 0.08 on natural gas), and delta flipped sign between the two contracts.
   - **These rules stay in SHADOW until the app's own recordings can test them.**

**Bottom line for Boss:**
- Turn the manager to ACT for ORB Fresh only, with the rule above, and only if that arm is running at all.
- Everything else: SHADOW.
- Night and the MCX legs: OFF. They could not be tested.

---

## Part A: the outside evidence

Quality scale:
- **A**: peer-reviewed, large sample, replicated;
- **B**: peer-reviewed or rigorous but narrow, or not about intraday option buying;
- **C**: practitioner backtest or white paper;
- **D**: vendor or forum claim, no method.

Few sources test *exits* for *intraday option buyers* directly. Most of what follows is indirect.

| # | topic | source | claim | quality / relevance |
|---|---|---|---|---|
| 1 | stops in general | Kaminski & Lo, "When do stop-loss rules stop losses?", J. Financial Markets 2014 ([MIT](https://dspace.mit.edu/entities/publication/bb69ca4b-0cdc-487f-831d-63b2e84fafee)) | Under a random walk, a stop always lowers expected return. A stop adds value only when returns have momentum. Their stop added 50-100 bp a month in stop-out periods (monthly US equities). | A theory + B data. Relevance: **a manager can only help where prices trend after the signal.** |
| 2 | stops + costs | Lo & Remorov, J. Financial Markets 2017 ([MIT](https://dspace.mit.edu/handle/1721.1/107017)) | Tight stops underperform because of trading costs. They help only with high serial correlation. They cut downside risk only modestly. | B. Relevance: high. Our costs (spread + charges) are about 0.4% a round trip. |
| 3 | stops on momentum | Han, Zhou & Zhu, "Taming momentum crashes" ([summary](https://alphaarchitect.com/2016/08/taming-the-momentum-roller-coaster-fact-or-fiction/)) | A 10% stop cut momentum's worst month from -49.8% to -11.3% and doubled the Sharpe, gross of costs. Critics say costs and liquidity eat much of it. | B (monthly, stocks). Supports stops on trend strategies, before costs. |
| 4 | let winners run | Odean 1998, J. Finance ([paper](https://faculty.haas.berkeley.edu/odean/papers/disposition/disposition.html)); Locke & Mann 2005, JFE ([abstract](https://ideas.repec.org/a/eee/beexfi/v8y2015icp54-63.html)) | Retail investors sell winners too early, and the winners they sell go on to do better (disposition effect). Professional floor traders also hold losers longer, but showed no measurable cost. Their "discipline" predicted later success. | A for the bias, B for the professionals. Argues against early profit-taking. Says nothing direct about extending targets. |
| 5 | risk after losses | Coval & Shumway 2005, J. Finance ([abstract](https://ideas.repec.org/a/bla/jfinan/v60y2005i1p1-34.html)) | CBOT traders with morning losses took 16% more afternoon risk, and those trades reverted within 10 minutes. | A. Relevance: why a manager must never add risk or widen a stop (our fixed rule). |
| 6 | MFE / MAE exits | Sweeney, *Maximum Adverse Excursion* (1996); summaries ([LuxAlgo](https://www.luxalgo.com/library/concept/mae-mfe-distributions.md), [TradesViz](https://www.tradesviz.com/glossary/mfe-mae-duration/)) | Plot each trade's excursions to place stops. A big gap between average MFE and realised profit means the exit is the weak link. Time-to-MFE gives a time stop. | C/D (method, no controlled test). We tested it directly: specialist PA. |
| 7 | time stops for long options | Beckmeyer et al., "Retail traders love 0DTE options... but should they?" ([coverage](https://www.cxoadvisory.com/individual-investing/retail-0dte-option-trader-performance)); broker notes ([Schwab](https://www.schwab.com.sg/story/zeroing-on-0dte-options-learn-basics), [Sahi](https://www.sahi.com/blogs/how-to-trade-options-on-expiry-day)) | Retail 0DTE buyers lose steadily, much of it to costs. Same-day theta concentrates in the afternoon. The "70-80% lost 1-3 pm" figure has no data behind it. | B on the losses; D on intraday decay timing. We tested it: specialist TT. |
| 8 | Indian F&O retail | SEBI study, Sep 2024 ([press release](https://www.sebi.gov.in/media-and-notifications/press-releases/sep-2024/updated-sebi-study-reveals-93-of-individual-traders-incurred-losses-in-equity-fando-between-fy22-and-fy24-aggregate-losses-exceed-1-8-lakh-crores-over-three-years_86906.html)) | 93% of individual F&O traders lost money over FY22-24, about Rs 2 lakh each on average, costs included. Algo prop traders and FPIs made the money. | A (population data). Context: costs and over-trading dominate. |
| 9 | ATR / chandelier trails | Le Beau / Elder (origin); StockCharts SystemTrader test ([article](https://articles.stockcharts.com/article/articles-arthurhill-2016-12-systemtrader---testing-a-mean-reverion-system-with-the-chandelier-exit-spy-qqq-ijr---rsi5/)); our `CHANDELIER_EXIT.md` | No independent evidence that the chandelier exit adds value on its own. One narrow test was mixed. Our own BANKNIFTY test lost in 15 of 16 versions. | C/D. Tested as specialist VC. |
| 10 | ATR stop multiples | practitioner guides ([LuxAlgo](https://www.luxalgo.com/library/concept/atr-based-stop-distance.md)) | Rules of thumb: 1.5-2 ATR for day trading, 2-3 for swing. A "32% lower drawdown" figure has no source. | D. |
| 11 | breakeven moves | [ATAS / Quantified Strategies](https://atas.net/blog/break-even-in-trading/), [LuxAlgo](https://www.luxalgo.com/library/concept/breakeven-move-rules.md) | Moving the stop to breakeven early lowers trend-following expectancy, because normal pullbacks scratch the trades that would have run. | C/D. Our `PROFIT_LOCK.md` found ladders cut ORB losses but never made an arm profitable. Tested as BE. |
| 12 | parabolic SAR | theses (Aalto, KLSE, SET) and a vendor test ([unofficed](https://unofficed.com/courses/entropy/lessons/backtesting-parabolic-sar-strategy/), [liberatedstocktrader](https://www.liberatedstocktrader.com/videos/chart-indicators/parabolic-sar)) | No peer-reviewed evidence of profit. One vendor test on DJ30 found PSAR not profitable on normal charts. | D. Tested as SR. |
| 13 | VWAP / intraday trend | Zarattini, Aziz & Barbon, "Beat the Market" (SPY, 2024; [SFI](https://www.sfi.ch/fr/publications/n-24-97-beat-the-market-an-effective-intraday-momentum-strategy-for-s-p500-etf-spy)); Gao, Han, Li & Zhou, "Market intraday momentum", JFE 2018 ([abstract](https://profiles.wustl.edu/en/publications/market-intraday-momentum/)) | Intraday trend-following with dynamic (VWAP / band) trailing stops: Sharpe 1.33 net of costs, 2007-2024. The first half-hour return predicts the last half-hour (R² about 2%), more so on volatile days. | B (working paper; one replicated JFE result). The closest support for a VWAP-based exit on trend trades. Tested as VW. |
| 14 | volume climax | Campbell, Grossman & Wang 1993, QJE ([SSRN](https://papers.ssrn.com/abstract=297335)); Conrad, Hameed & Niden 1992 | High-volume price moves tend to reverse; low-volume moves tend to continue (daily data). Intraday "climax" rules are vendor material only ([StockSharp](https://doc.stocksharp.com/en/api-examples/0115_Volume_Climax_Reversal)). | A daily; D intraday. Tested as VO. |
| 15 | order-flow imbalance | Cont, Kukanov & Stoikov, J. Financial Econometrics 2014 ([arXiv](https://arxiv.org/abs/1011.6402)) | Over seconds, order-flow imbalance at the best quotes explains most price changes, with a slope inversely proportional to depth. The relation is contemporaneous, not forecasting. | A. Our R11 Part B saw the same: it explains, it does not forecast. Live-only. |
| 16 | cumulative delta divergence | indicator pages ([LuxAlgo](https://www.luxalgo.com/library/concept/delta-divergence.md)) | Price makes a higher high on weaker delta, read as exhaustion. No published win rates. | D. Live-only (proxy tested as DP). |
| 17 | absorption / icebergs | Frey & Sandas (iceberg orders, [paper](https://www.cfr-cologne.de/download/workingpaper/cfr-09-06.pdf)) | Detected icebergs attract market orders. Executed icebergs have small price impact. Nobody tests "absorption predicts reversal". | B (microstructure); no exit evidence. Live-only. |
| 18 | OI unwinding | broker glossaries ([Motilal](https://www.motilaloswal.com/learning-centre/2020/2/open-interest-trading-strategy)); our R11 | Price down with OI down = long unwinding, read as a pause. No academic test found. R11: put-minus-call writing leaned the move's way on NIFTY and failed on BANKNIFTY. | D external; B internal. Tested as OI. |
| 19 | VIX / IV | NSE working paper and Indian studies ([NSE WP 9](https://nsearchives.nseindia.com/research/content/res_WorkingPaper9.pdf), [IJF](https://indianjournalofentrepreneurship.com/index.php/IJF/article/view/72415)); IV crush notes ([SpotGamma](https://spotgamma.com/iv-crush-explained/)) | India VIX moves against NIFTY, more so on falls (daily). IV collapses after scheduled events and hurts long options even when the direction is right. | B daily, D intraday. Tested as VX. |
| 20 | market profile | Dalton's "80% rule" ([forum tests](https://nexusfi.com/a/concepts/80-percent-rule)) | The claimed 80% "fill the value area" rate is not reproduced by independent testers (about 62% in one study). | D. Our R9/R10: prior-day value area shows nothing. Tested as MP. |
| 21 | momentum fade / RSI | Park & Irwin 2007 survey (from memory, not re-fetched); TradingView scripts | Technical rules looked profitable before the 1990s and much less since; data snooping is the main worry. No evidence for RSI-divergence exits. | B (survey) / D. Tested as MO. |
| 22 | Greeks / gamma exits | vendor notes ([SpotGamma](https://spotgamma.com/how-to-trade-iv-crush-earnings/)) | No tested rule for exiting long options on delta/gamma acceleration was found. | D. Not testable here: no tick Greeks. Covered indirectly by PA/VC on the premium itself. |
| 23 | committees | Timmermann, "Forecast Combinations" ([CEPR DP5361](https://causalclaims.trfetzer.com/paper/DP5361.html)) | Simple equal-weight combinations of forecasts usually beat the "best" model and estimated-weight schemes, because estimated weights overfit. | A (forecasting). Supports equal weights and few members. Our loop found OR-pairs of two rules, nothing bigger. |
| 24 | trailing vs fixed targets | broker / blog pieces ([Optimus](https://optimusfutures.com/blog/the-pros-cons-fixed-targets-vs-trailing-stop-technique/), [ForexOp](https://forexop.com/strategy/trailing-stops/)) | Trailing suits trend systems and targets suit mean reversion. One FX test found almost no difference. | D. |
| 25 | desk risk practice | prop-firm rules ([Tradezella](https://www.tradezella.com/blog/risk-management-tools)); Locke & Mann | Layered limits (per-trade stop, daily loss, trailing drawdown), and discipline over discretion. | C/D. Matches the app's "manager may only lower risk" rule. |

What the outside evidence predicts:
- Stops and trails help only where prices keep going after the signal (1, 2).
- Costs punish frequent tight exits (2, 8).
- Cutting winners is the classic mistake (4).
- Nobody shows that order-flow or absorption *exit* rules work (15-17).
- Simple, equally weighted committees beat tuned ones (23).

Our data agree on all of these.

## Part B: the specialists and the loop

### What was managed

Each strategy's original trades were rebuilt with the code that produced its numbers. The replay of the original exits
was checked trade by trade (`parity.py`).

| strategy | source | trades (design / holdout) | parity of original exits |
|---|---|---|---|
| Liquidity BANKNIFTY 15+5 | obuy `liquidity.py` (liqx = port of the Kotlin replay), 1-ITM, ARM_EXITS, one per book | 750 / 248 | 99.7% of trades to the paisa (-1.8k on +146.8k) |
| ORB, ORB Fresh, ORB Sweep, Range Fade | `hunt/h20/sim.py` signals + "fixed" mode (the app since 07 Oct: lock ladder as the resting stop) | 7,820 / 1,904; 1,981 / 474; 1,330 / 331; 1,460 / 376 | exact |
| VIX divergence NIFTY (LIQ exit) and BANKNIFTY (15:10) | VixDivRules.kt / r1 `n13_vixdiv`, 0.2% / 2% | 148 / 29; 178 / 45 | exact |
| Expiry Hero (NIFTY, F07) | hero_deep `v2_trades` entries + `sim.py` exits (half at 5x, rest 20x or 15:05, -60% on closes) | 51 / 4 | within Rs 1 (paisa rounding) |

Not rebuilt (said plainly):
- **Night (R3)**: it buys at 15:20 and sells at 09:16 the next day. There are only 10 in-session minutes to manage, so
  there is nothing for a manager to do.
- **MCX NG evening breakout and US-night silver**: MCX minute data starts on 5 Aug 2025. That leaves under 2 months of
  design period (about 35-40 trades), too few to tune anything.
- **MCX trend**: multi-month futures holds. A minute-level option manager does not apply.
- **Solo and Pine** were not in the brief.

### The 14 specialists

Each specialist votes EXIT, raises a lock (TRAIL), or agrees to EXTEND. Each fires with strength 1. It is decided on a
minute's close using only data up to that minute; the fill is the next minute's open, or the lock rests on the low.

| code | family | rule |
|---|---|---|
| PA | price action / MFE | T min after entry, best high still < entry x (1 + m) → exit |
| PB | price action / MAE | a close ≤ entry x (1 - a) → exit |
| TT | time / theta | after clock tau (expiry day: 60 min earlier), premium < entry x (1 + g) → exit |
| VC | volatility trail | lock = best high - k x option ATR(10), armed after a ATRs of profit |
| BE | breakeven move | lock at BE + charges once best high ≥ entry x (1 + b) |
| SR | parabolic SAR | Wilder's SAR on the option's minutes as the lock |
| GB | giveback trail | lock = entry + keep x (best - entry) once best ≥ entry x (1 + a) (the current manager's 50% trail) |
| VW | VWAP | index on the wrong side of session VWAP (R10 option-volume proxy) by z SD for p min → exit |
| VO | volume climax | option volume ≥ m x its 30-min mean on a new-high bar closing in its lower 40% → exit |
| OI | open interest | ATM±2 put-minus-call writing over 15 min against the trade, z ≤ -z0 → exit |
| VX | VIX / IV | VIX 5-min move ≥ x% the way that hurts (up for calls, down for puts) → exit |
| MP | market profile | index was outside the developing / prior-day value area the trade's way, back inside for p min → exit |
| MO | momentum fade | RSI(14) was beyond 70/30 the trade's way and is back through 50, or ROC(10) against by r → exit |
| DP | delta PROXY (thin) | tick-rule signed option volume (R11 `flow`) over 10 min against, z ≤ -z0 → exit |

The extension votes are VWAP ≥ 0.5 SD, outside value, OI agreeing, VIX calm, ROC agreeing and delta-proxy agreeing.
**M0** is the current manager's extension without its live-only flow condition.

### The loop (design period only)

Walk-forward folds: 2023, 2024 and 2025 (Jan-Sep). Each fold is chosen on the earlier design years only. Every
configuration is logged.

**Loop A** picked by the largest training improvement over the original exits. It ran 8 rounds and 1,829 configurations.

| round | what | design WF net (all 8 strategies) | change |
|---|---|---|---|
| 1 | each specialist alone | -676,941 | - |
| 2 | committees (pairs OR/AND, 2-of-3 triples) | -708,162 | -4.6% |
| 3 | exits x locks | -687,984 | +2.8% |
| 4 | extensions | -703,155 | -2.2% |
| 5-8 | refinement | -707,720 / -684,082 / -696,047 / -724,306 | -0.6 / +3.3 / -1.7 / -4.1% |

Loop A was rejected:
- On the ORB arms its picks were no better than random early exits (twin p 0.32-0.89).
- On Liquidity its walk-forward was -70,504.
- Refinement drifted to fragile corners, such as a 1-minute "no progress" rule.
- Its stop rule was also coded wrongly (|change| instead of change). Under the correct rule it would have stopped
  after round 5.

**Loop B** picked by the improvement *minus what the same number of random early exits would have gained*. It also
required:
- a positive result in both halves of the training years;
- for a single specialist, a positive neighbouring grid value;
- refinements inside the declared grid.

Rounds 1-4 were fixed in advance. The stop rule (two consecutive rounds under +2%) ended the loop after round 4.

| round | what | design WF net | change |
|---|---|---|---|
| 1 | each specialist alone | -780,917 | - |
| 2 | committees | -772,803 | +1.04% |
| 3 | exits x locks | -770,083 | +0.35% |
| 4 | extensions | -771,002 | -0.12% → stop |

The walk-forward net is dominated by the ORB arms' losses. The manager moves it by only about 1%.

Single specialists, design period: 624 tests (78 per strategy). Each was compared with random twins of the same size;
BH was applied over all 624.
- 32 pass at q < 0.10:
  - Range Fade: 20 (VWAP exits, SAR, OI, "no progress", market profile);
  - ORB Fresh: 8 ("no progress", MAE, time);
  - ORB: 3 ("no progress", chandelier);
  - VIX divergence NIFTY: 1.
- **Zero** pass on Liquidity, VIX divergence BANKNIFTY, Hero or ORB Sweep.

White's reality check covers every configuration of both loops (2,363 distinct), with the original exits as the
benchmark:

| strategy | Liquidity | ORB | Fresh | Sweep | Fade | VIX-div NIFTY | VIX-div BN | Hero | all |
|---|---|---|---|---|---|---|---|---|---|
| White RC p | 0.87 | 0.057 | 0.002 | 0.0005 | 0.0005 | 0.65 | 0.67 | 0.84 | 0.15 |

Note: on the losing arms, "beats the original" is easy. The twin test is the stricter bar.

## Part C: the holdout (1 Oct 2025 - 6 Oct 2026, read once)

Rules exactly as frozen in PREREG.md.
- Rs, 1 lot, after all costs including the h24 half-spreads.
- Max drawdown is on daily equity.
- Twin p uses 1,000 random-twin sets; BH is across the 8 strategies.

| strategy | trades | original net | manager net | delta | win % orig → mgr | avg win orig → mgr | avg loss orig → mgr | max DD orig → mgr | trades changed | twin p (BH q) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Liquidity BANKNIFTY 15+5 | 248 | +42,441 | +45,619 | +3,179 | 35.9 → 35.9 | 2,107 → 2,107 | -913 → -893 | -32,005 → -28,826 | 1 | 0.018 (0.072) | SHADOW (design WF failed) |
| ORB | 1,904 | -338,937 | -326,201 | +12,736 | 36.5 → 35.5 | 584 → 574 | -616 → -582 | -338,937 → -326,201 | 152 | 0.15 (0.41) | SHADOW (no better than random) |
| **ORB Fresh** | 474 | -74,044 | -59,679 | **+14,365** | 36.9 → 36.1 | 553 → 548 | -571 → -506 | -75,482 → -59,679 | 50 | **0.001 (0.008)** | **ACT** |
| ORB Sweep | 331 | -99,121 | -101,057 | -1,936 | 26.9 → 26.0 | 1,024 → 923 | -786 → -737 | -101,631 → -103,567 | 40 | 0.98 (0.98) | SHADOW |
| Range Fade | 376 | -84,826 | -56,772 | +28,054 | 31.6 → 23.7 | 490 → 263 | -557 → -279 | -84,826 → -56,772 | 315 | 0.49 (0.78) | SHADOW (random exits do the same) |
| VIX divergence NIFTY | 29 | +16,127 | +19,084 | +2,957 | 27.6 → 31.0 | 4,302 → 3,999 | -871 → -845 | -7,366 → -6,952 | 8 | 0.32 (0.64) | SHADOW |
| VIX divergence BANKNIFTY | 45 | +27,693 | +17,892 | -9,801 | 51.1 → 48.9 | 3,790 → 3,020 | -2,704 → -2,111 | -13,602 → -12,720 | 9 | 0.71 (0.90) | SHADOW |
| Expiry Hero (NIFTY) | 4 | -9,642 | -4,449 | +5,193 | 0 → 0 | - | -2,411 → -1,112 | -9,642 → -4,449 | 4 | 0.79 (0.90) | SHADOW (4 trades) |

Which members did what in the holdout (net change on the trades each one decided):

| strategy | helped | hurt |
|---|---|---|
| ORB Fresh | "no progress" PA +6,353 (36 trades), chandelier lock VC +6,886 (12), MAE PB +1,126 (2) | - |
| ORB | PA +9,452 (127), PB +1,995 (6), VC +1,289 (19) | - |
| Range Fade | VWAP exit VW +22,107 (293), PA +4,739 (21) | - (but random exits would have saved about the same, about 27.6k) |
| ORB Sweep | OI +599 (3) | PA -2,535 (37) |
| VIX divergence BANKNIFTY | delta proxy DP +7,330 (2) | market profile MP -17,131 (7) |
| VIX divergence NIFTY | MP +2,523 (3), PB +433 (5) | - |
| Liquidity | OI +3,179 (1 trade) | - |
| Hero | breakeven lock +4,759 (3) | - |

Every single specialist on the holdout (descriptive; median excess over random twins across its grid):
- **"No progress" PA was positive on ORB (+1.4k) and ORB Fresh (+7.8k).** It was positive there in the design period
  too.
- Market profile MP was positive on ORB (+6.6k) and ORB Fresh (+9.3k). In the design period it was about 0 or
  negative.
- VWAP exits were strong on Range Fade in the design period (+27.7k median excess) but negative in the holdout (-3.2k).
  They did not hold.
- Trails were negative or zero almost everywhere, and badly so on Liquidity (-5k to -32k). These are SAR, chandelier,
  giveback and breakeven.
- Time/theta exits, momentum fade and VWAP exits on Liquidity and VIX divergence were negative in both periods.
- The current manager's extension without flow (M0): Liquidity +873 on 2 trades; the ORB arms 0 (it never fired).

BH over the 8 holdout tests: only ORB Fresh passes (q 0.008). Liquidity's q is 0.072, but that rests on one changed
trade, and its design walk-forward failed.

## Part D: recommended app settings

Notes on the app:
- TradeManager's modes are per family ("orb" covers ORB, Fresh, Sweep and Fade together).
- `canAct = false` for orb, liquidity and hero.

ORB Fresh's ACT therefore needs two app changes before it can act:
- a per-arm mode inside the ORB family;
- `canAct` for ORB Fresh's exit path.

Until then, run everything in SHADOW. The rules below are what the shadow record should compute, so the app can confirm
them on live paper.

```json
{
  "version": "R12-2026-10-10",
  "costs": "app fills + app charges + h24 half-spread; decisions on the minute close, filled next open; locks rest on the low",
  "strategies": {
    "orb_fresh":  {"mode": "ACT", "quorum": 1,
                   "exit": [{"spec": "PA", "family": "price action / MFE", "T_min": 20, "m": 0.03, "weight": 1},
                            {"spec": "PB", "family": "price action / MAE", "a": 0.12, "on": "minute close", "weight": 1}],
                   "lock": [{"spec": "VC", "family": "volatility trail", "k_atr": 3.0, "arm_atr": 1.0, "atr": "option 1-min range, mean of previous 10"}],
                   "extend": null,
                   "evidence": "design WF +9,169 (3/3 folds), holdout +14,365, twin p 0.001, BH q 0.008; the arm still loses"},
    "orb":        {"mode": "SHADOW", "quorum": 1,
                   "exit": [{"spec": "PA", "T_min": 20, "m": 0.03, "weight": 1}, {"spec": "PB", "a": 0.12, "weight": 1}],
                   "lock": [{"spec": "VC", "k_atr": 6.0, "arm_atr": 1.0}], "extend": null,
                   "evidence": "holdout +12,736 but twin p 0.15: no better than random cutting"},
    "orb_sweep":  {"mode": "SHADOW", "quorum": 1,
                   "exit": [{"spec": "OI", "z0": 1.5, "window_min": 15, "strikes": "ATM+-2", "weight": 1},
                            {"spec": "PA", "T_min": 45, "m": 0.03, "weight": 1}],
                   "lock": [], "extend": {"votes": ["VWAP>=0.5SD", "flow>=+0.5SD"], "quorum": 2},
                   "evidence": "holdout -1,936, twin p 0.98"},
    "range_fade": {"mode": "SHADOW", "quorum": 1,
                   "exit": [{"spec": "PA", "T_min": 10, "m": 0.10, "weight": 1},
                            {"spec": "VW", "z_sd": 0.5, "persist_min": 5, "weight": 1}],
                   "lock": [], "extend": null,
                   "evidence": "holdout +28,054 but twin p 0.49: random exits save as much"},
    "liquidity":  {"mode": "SHADOW", "quorum": 1,
                   "exit": [{"spec": "OI", "z0": 1.0, "weight": 1}, {"spec": "PA", "T_min": 45, "m": 0.10, "weight": 1}],
                   "lock": [], "extend": null,
                   "evidence": "design WF +797 (1/3 folds), White RC 0.87; keep the original exits"},
    "vixdiv":     {"mode": "SHADOW",
                   "NIFTY": {"quorum": 1, "exit": [{"spec": "MP", "value_area": "prior day", "persist_min": 5}, {"spec": "PB", "a": 0.12}]},
                   "BANKNIFTY": {"quorum": 1, "exit": [{"spec": "DP", "z0": 2.0}, {"spec": "MP", "value_area": "prior day", "persist_min": 5}]},
                   "evidence": "design WF -7,419 / -21,286; keep the original exits"},
    "hero":       {"mode": "SHADOW", "quorum": 1,
                   "exit": [{"spec": "MP", "value_area": "prior day", "persist_min": 1}, {"spec": "PA", "T_min": 10, "m": 0.10}],
                   "lock": [{"spec": "BE", "b": 0.15}],
                   "evidence": "design WF -7,620, 4 holdout trades; a lottery ticket - never cut it on evidence this thin"},
    "night":        {"mode": "OFF", "why": "15:20 -> next 09:16; 10 in-session minutes, nothing to manage"},
    "mcx-eve":      {"mode": "OFF", "why": "under 2 months of design data (MCX minutes start 5 Aug 2025)"},
    "mcx-morning":  {"mode": "OFF", "why": "not rebuilt; same data limit"},
    "mcx-trend":    {"mode": "OFF", "why": "multi-month futures holds; a minute option manager does not apply"},
    "silver-night": {"mode": "OFF", "why": "futures; under 2 months of design data"},
    "solo":         {"mode": "SHADOW", "why": "not tested in R12; the R12 evidence says trails and early exits hurt profitable arms"},
    "pine":         {"mode": "SHADOW", "why": "not tested in R12; same"}
  },
  "current_manager_rules": {
    "EXTEND (flow>=65 & VWAP>=0.5SD & outside value & OI & VIX calm & 90% to target)": "SHADOW - without flow it fired 0-4 times a year, effect about Rs 0",
    "lock ratchet on extension + 50% trail between extensions": "keep as coded (risk-reducing), SHADOW with EXTEND",
    "OPPOSITE_FLOW": "SHADOW (live-only)",
    "DIVERGENCE (delta)": "SHADOW (live-only)",
    "ABSORPTION": "SHADOW (live-only)",
    "TRAP": "SHADOW (live-only)",
    "NEWS": "SHADOW (live-only)",
    "CONSENSUS (3 findings against)": "SHADOW (live-only)",
    "DATA near stop": "SHADOW (live-only; it is a safety rule, can be ACT by Boss's choice)"
  }
}
```

**Live-only specialists to keep in SHADOW** until the app's own recordings can test them:
- order flow (OPPOSITE_FLOW);
- delta divergence;
- absorption;
- traps;
- news;
- findings consensus;
- the data-near-stop rule.

What would settle them:
- about 150-200 recorded trades per strategy family, with the 1-second book/tape saved (R11 Part C lists the fields);
- the same twin test as here: does the rule beat the same number of random early exits on the same trades?

## Limits, said plainly

- The manager only changes how a trade ends. For ORB and ORB Fresh, an earlier exit would in the app free the arm to
  re-enter; those extra trades were not simulated. They are negative-expectancy trades, so this flatters the manager on
  those arms.
- VWAP, value area, OI and delta are proxies:
  - VWAP is weighted by option volume, since the index has no volume;
  - OI is the 3-minute ATM±2 option snapshot;
  - the delta proxy is the tick-rule option flow.

  The app's live versions are different inputs.
- Hero has 4 holdout trades; nothing can be concluded.
- The extension twin is a random early exit, not a random extension. No extension was picked anyway.
- Loop A used its full 8 rounds and was examined before Loop B was designed, all inside the design period. Both loops'
  2,363 configurations are in White's reality check.
