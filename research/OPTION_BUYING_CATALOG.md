# Option-buying strategy catalog (Indian index options)

Written 7 Oct 2026 from a web survey of brokers' blogs, Zerodha Varsity and TradingQnA, AlgoTest, marketcalls, TradingView scripts, community forums, SEBI studies and academic papers. It also cross-references this repo's own backtests (`research/FINDINGS.md` and others). There are 55 strategies in 10 families. The machine-readable copy is `research/option_buying_catalog.json`, with fields `id, name, family, instrument, timeframe, entry, filters, stop, target, trail, time_exit, params_to_sweep, data_needed, sources`, plus `claimed`, `ambiguity`, `evidence_note` and `popularity`.

**How to read the claims.** Almost nothing published by retail sources is a real backtest: most give rules with no statistics, or a single worked example. The few numbers that exist (for example, RSI>50: 829 trades and 52.96% wins) leave out costs, lot size and test period. Treat every claimed figure as a hypothesis to test.

---

## 1. What the evidence says about option buying in India

### 1.1 SEBI studies of individual F&O traders

| study (release) | period | % of individuals losing | avg loss per loser / trader | option-buyer specifics |
|---|---|---|---|---|
| SEBI DEPA (25 Jan 2023) | FY22 | 89% (90% of active traders) | Rs 1.1 lakh (active: Rs 1.25 lakh); losers paid a further **28%** of their losses as transaction costs | 11% profitable, avg profit Rs 1.5 lakh; unique traders up 500% (FY19 7.1 lakh to FY22 45.2 lakh) |
| SEBI (23 Sep 2024, updated) | FY22 to FY24 (3 yrs) | **93%** of more than 1 crore traders | ~Rs 2 lakh each over 3 yrs; aggregate loss > Rs 1.8 lakh crore; top 3.5% of losers (~4 lakh people) lost Rs 28 lakh each | only 1% made > Rs 1 lakh after costs; FY24 gross profits were props Rs 33k cr and FPIs Rs 28k cr |
| SEBI (7 Jul 2025) | FY25 | **91%** | net losses Rs 1,05,603 cr (FY24 Rs 74,812 cr); avg about Rs 1.1 lakh | unique traders fell 20% after the Nov-2024 measures |
| SEBI DEPA, 2 studies (20 Aug 2026) | FY26 (and FY22 to FY26 panel) | **87.7%** | Rs 1.17 lakh avg; aggregate Rs 91,685 cr (down 18%) | **97% of individuals are mainly option buyers, and about 90% of buyers lose (avg Rs 1.3 lakh)**. Only ~2% are mainly sellers: ~44% of them lose, but Rs 51.7 lakh on average when they do. Sellers were the only group with a positive median return on capital. Options = 92% of losses. 0DTE = **70% of index-option turnover in FY25** and 59% in FY26; 75 to 80% within 1 day of expiry. Only 15.4% of trader-quarters were profitable; the median losing quarter is 2.4x the median winning one. Loss rate rises with experience (91% in year 1 to 95.3% in year 5); 65.6% of 5-year traders lost in every year. Traders with 100+ active days are 42% of traders but 87% of losses. Costs were Rs 24,859 cr (STT Rs 6,645 cr) and turned 4.4 lakh gross-profitable traders into net losers. |
| SEBI intraday cash study (24 Jul 2024) | FY23 | 70% of intraday cash traders | Rs 5,371 | costs = 57% of losers' trading losses (context for costs) |

**Takeaway.** The typical retail profile loses about 9 times in 10. It is a high-frequency, near-expiry, short-holding option buyer: exactly the profile that most strategies in this catalog describe. Being a seller is not safe either (44% lose, and big). Costs alone flip a large share of small winners into losers.

Sources:
- [SEBI 2024 press release](https://www.sebi.gov.in/media-and-notifications/press-releases/sep-2024/updated-sebi-study-reveals-93-of-individual-traders-incurred-losses-in-equity-fando-between-fy22-and-fy24-aggregate-losses-exceed-1-8-lakh-crores-over-three-years_86906.html)
- [Business Standard on the Jan 2023 study](https://www.business-standard.com/amp/article/markets/sebi-study-suggests-89-retail-traders-in-equity-f-o-suffered-losses-123012501466_1.html) and [Moneylife](https://www.moneylife.in/article/90-percentage-individual-traders-in-equity-fo-segment-incurred-losses-sebi-research/69634.html)
- [Taxmann on FY22 to FY24](https://www.taxmann.com/post/blog/93-of-1-crore-fo-traders-saw-rs-2l-avg-loss-while-top-3-5-faced-rs-28l-avg-loss-incl-costs-sebi/)
- [Business Standard on FY25](https://www.business-standard.com/amp/markets/news/net-losses-of-traders-in-fo-widens-in-fy25-sebi-study-125070701221_1.html) and [Angel One](https://www.angelone.in/news/market-updates/retail-f-o-losses-rose-to-over-1-lakh-crore-trader-participation-dropped-20-percent)
- [Moneylife on FY26](https://www.moneylife.in/article/92-percentage-of-aggregate-losses-incurred-by-individuals-are-from-options-trading-sebi-study/81429.html), [Policy Edge](https://www.policyedge.in/p/nearly-9-in-10-individual-fo-traders-lost-money-as-participation-fell-18-sebi-studies), [Open Magazine](https://openthemagazine.com/business/sebi-fo-loss-study-explained-why-9-in-10-retail-traders-lost-91685-crore-in-fy26), [TradingQnA summary](https://tradingqna.com/t/win-rate-capital-experience-what-sebi-found-about-f-o-traders/197266) and [Business Today](https://www.businesstoday.in/markets/story/rs91685-cr-lost-88-of-individual-traders-lost-money-in-fy26-options-drove-92-of-losses-550474-2026-08-21)
- [Business Standard on the intraday cash study](https://www.business-standard.com/amp/markets/news/over-70-intra-day-traders-incur-losses-during-fy23-reveals-sebi-study-124072401110_1.html)

### 1.2 Academic and other evidence

- **Animal Spirits on Steroids** (Agarwal, Ghosh, Prabhala, Zhao): a market-wide panel of Indian retail options trading. India has about 80% of the world's option contracts. Retail investors dominate index options, day-trade, and make short directional bets that concentrate as options approach 0DTE. They lost about INR 506 bn in the sample. When BANKNIFTY weeklies were introduced in 2016, BANKNIFTY volumes surged relative to NIFTY. When lot sizes or margins were raised, traders shifted to **smaller-ticket, riskier options**, which is consistent with a lottery preference. [Seminar abstract](https://en.rmbs.ruc.edu.cn/Media/Seminars/8e4a1d83214e4f6fb0a742bd927cf320.htm), [CFR working paper](https://www.cfr-cologne.de/download/workingpaper/cfr-25-09.pdf).
- **Variance risk premium (VRP)** in NIFTY options is consistently positive: implied volatility overstates realised volatility, so selling volatility earns a premium and buying it pays one. This is the structural headwind for every strategy below. [Manipal study](https://researcher.manipal.edu/en/publications/dynamics-of-variance-risk-premium-evidence-from-india/).
- **Expiry effects.** Studies before weekly options found an upward price shift and a transitory rise in volatility around expiry, effects that faded after weeklies arrived in 2019. Changing the expiry day raised volume and volatility in NIFTY and BANKNIFTY ([IDEAS, Rev Deriv Res 2025](https://ideas.repec.org/a/kap/revdev/v28y2025i3d10.1007_s11147-025-09221-8.html); [AMH JEBS](https://ojs.amhinternational.com/index.php/jebs/article/view/210)).
- **Expiry manipulation.** SEBI's interim order against Jane Street (3 Jul 2025, Rs 4,843.57 cr impounded) describes trades on 18 expiry days (Jan 2023 to Mar 2025). In the morning the group bought BANKNIFTY constituents and futures, building option positions; later in the day it sold them heavily, pushing the index the way its options needed. Expiry-day price paths, which hero-zero and gamma-blast trades depend on, could be driven by large players. [Oxford Law blog](https://blogs.law.ox.ac.uk/oblb/blog-post/2025/07/jane-street-and-expiry-day-trap-unpacking-sebis-crackdown-algorithmic), [ECGI](https://www.ecgi.global/publications/blog/expiry-day-and-the-governance-of-algorithmic-trading-the-jane-street-episode).
- **Intraday momentum** (Gao, Han, Li, Zhou; S&P 500 ETF 1993 to 2013): the first half-hour return predicts the last half-hour return, more strongly on volatile, high-volume and news days. I found no NIFTY replication; Indian work finds a U-shaped intraday volatility pattern and short-horizon reversals after extreme moves. [Paper PDF](https://c.mql5.com/forextsd/forum/173/intraday_momentum_-_the_first_half-hour_return_predicts_the_last_half-hour_return.pdf), [MPRA 89689](https://mpra.ub.uni-muenchen.de/89689/).
- **PCR and OI.** NIFTY 2001 to 2013: OI-PCR predicts returns at about a 12-day horizon and volume PCR at about 2.5 days ([IJF](https://www.indianjournalofentrepreneurship.com/index.php/IJF/article/view/72105)). OI-based active strategies beat passive ones on daily NIFTY options ([Inderscience](https://www.inderscience.com/filter.php?aid=98900)). A sceptical view is [here](https://harbourfrontquant.substack.com/p/is-the-put-call-ratio-a-reliable).
- **This repo** (BANKNIFTY, 249 sessions 2025-26, real 1-min option prices, Rs 40 a trip): every intraday ATM buying arm lost. ORB lost Rs 1.94 lakh over 1,109 trades; Supertrend+EMA, RSI 30/70, Range Fade and the 9EMA/VWAP filter also lost. A learned model on 26 features had about zero rank correlation with outcomes. Buying blind costs about Rs 150 a trade on average (`research/FINDINGS.md`).

### 1.3 Costs and taxes (what a backtest must charge)

| date | change |
|---|---|
| 1 Oct 2024 | STT on option sales up from **0.0625% to 0.1%** of premium. STT on exercised options is 0.125% of intrinsic value (rate as quoted in the Budget 2026 coverage), which is why ITM longs should be closed before expiry. NSE moved to a flat 'true-to-label' option transaction charge of 0.03503% of premium (down from 0.0495%). Net effect +0.023% of sell-side premium. [marketcalls](https://www.marketcalls.in/exchange-news/impact-of-revised-transaction-charges-and-stt-on-traders-effective-october-1-2024.html), [Outlook Money](https://www.outlookmoney.com/invest/new-stt-rules-effective-october-1-important-updates-for-futures-and-options-traders) |
| Budget 2026 (announced 1 Feb 2026) | STT on options premium **0.1% to 0.15%**; on exercised options 0.125% to 0.15%; on futures 0.02% to 0.05%. Sources give different effective dates (5paisa says 1 Feb 2026; Finance Act changes normally take effect 1 Apr 2026), so check before modelling. Estimated effect is about +3% on a lot's round-trip cost for options. [5paisa](https://www.5paisa.com/index.php/news/stt-hike-in-fo-what-market-participants-need-to-know), [HDFC Sky](https://hdfcsky.com/news/union-budget-2026-impact-on-exchanges-with-recommendations), [ELP](https://elplaw.in/wp-content/uploads/2026/02/Budget-Buzz-Capital-Markets-Increase-in-STT-in-FO-Segment.pdf) |
| 1 Feb 2025 | Option premium must be collected upfront from buyers (no intraday leverage on long premium). |

The repo's model of Rs 40 a trip is optimistic; FINDINGS.md suggests about Rs 60 to 70 including slippage. For cheap expiry-day OTM options, half the bid-ask spread can exceed 5% of the premium.

### 1.4 Contract changes (they matter for any backtest across 2020 to 2026)

| date | change |
|---|---|
| 20 Nov 2024 | SEBI framework: index contract value raised from Rs 5 to 7.5 lakh up to **Rs 15 lakh** (lots fixed so value is Rs 15 to 20 lakh at review); **one weekly expiry per exchange**; +2% ELM on short options on expiry day; calendar-spread benefit removed on expiry day. Lots became NIFTY 25 to 75, BANKNIFTY 15 to 30, FINNIFTY 25 to 65, MIDCPNIFTY 50 to 120, SENSEX 10 to 20 (lot numbers are from background knowledge, not the cited pages; verify against the exchange circulars). [Business Standard](https://www.business-standard.com/amp/markets/news/sebi-announces-six-key-changes-to-curb-speculation-in-derivatives-trading-124100101316_1.html), [Angel One](https://www.angelone.in/news/market-updates/sebi-strengthens-index-derivative-rules) |
| Nov 2024 | **Last weeklies:** BANKNIFTY 13 Nov 2024, MIDCPNIFTY 18 Nov 2024, FINNIFTY 19 Nov 2024. After that these are monthly only. NSE weekly = NIFTY only; BSE weekly = SENSEX only (BANKEX/SENSEX50 weeklies also dropped; background knowledge, verify). [Zerodha bulletin](https://zerodha.com/marketintel/bulletin/392870/discontinuation-of-weekly-derivatives-contracts-from-november-2024), [Motilal](https://www.motilaloswal.com/learning-centre/2024/10/nse-discontinues-weekly-derivatives-on-bank-nifty-nifty-midcap-select-and-finnifty) |
| 1 Sep 2025 | **NSE expiries moved to Tuesday** (weekly NIFTY; monthlies on the last Tuesday); **BSE to Thursday** (SENSEX weekly). Before this NIFTY was Thursday (BANKNIFTY weekly Wednesday until Nov 2024, FINNIFTY Tuesday, MIDCPNIFTY Monday; SENSEX Friday, then Tuesday from Jan 2025; the pre-2025 weekdays are background knowledge, so verify them against the data). [Moneylife](https://moneylife.in/article/nse-equity-fo-contracts-to-expire-on-tuesdays-bse-on-thursdays-from-1st-september/77434.html), [TradingQnA](https://tradingqna.com/t/sebi-confirms-derivatives-expiries-tuesday-for-nse-thursday-for-bse/183366) |
| mid 2025 | Semi-annual lot review: BANKNIFTY 30 to 35, FINNIFTY 65, MIDCPNIFTY 140, NIFTY 75 (check exact dates against the exchange circular). |
| Jan 2026 | **NIFTY 75 to 65, BANKNIFTY 35 to 30, FINNIFTY 65 to 60, MIDCPNIFTY 140 to 120** (weekly from the 6 Jan 2026 expiry; monthly from the 27 Jan 2026 expiry). [HDFC Sky](https://hdfcsky.com/news/nse-revises-market-lot-sizes-for-major-index-derivatives-effective-january-2026), [Zerodha bulletin](https://zerodha.com/marketintel/bulletin/429705/revision-in-lot-size-of-index-derivative-contracts-from-december-30-2025) |

**Backtest implications.** Use the expiry calendar of each period; that is, find the nearest expiry from the data, not from a fixed weekday. Report P&L in index points or in rupees per Rs 1 lakh of premium so that lot changes don't distort results. Model BANKNIFTY, FINNIFTY and MIDCPNIFTY "weekly" strategies on monthly contracts after Nov 2024, and NIFTY expiry-day strategies on Tuesday after Sep 2025.

---

## 2. Conventions used in the cards

- **Signal source:** spot index 1-min bars resampled. **ATM** is the strike nearest spot at the signal bar (NIFTY 50-point, BANKNIFTY 100, FINNIFTY 50, MIDCPNIFTY 25, SENSEX 100). **1-ITM CE** = ATM minus one step; **1-ITM PE** = ATM plus one step.
- **Expiry:** "nearest" means the nearest listed expiry (weekly where available, otherwise monthly). Expiry-day strategies use the same-day option.
- **Fill:** the option's next 1-min bar open after the signal bar closes, plus slippage. Stops and targets are checked on option minute high/low, with the stop counted first when both hit in one minute (repo convention).
- **VWAP:** the spot index has no volume. Use index-futures volume if available, otherwise TWAP. Results then differ from the sources.
- **R:R targets** are measured on the index move unless the card says premium.

## 3. Data coverage against our holdings

We have:
- index 1-min bars from 2016
- weekly and monthly index option 1-min bars with OI, about 2020 to 2026
- India VIX bars
- F&O stock futures
- daily stocks

| need | status |
|---|---|
| Index 1-min, option 1-min, OI by strike, ATM straddle, IV (computed by Black-Scholes from option prices) | **HAVE**. Check strike coverage: the repo's BANKNIFTY file keeps only ±300 points, which is too narrow for hero-zero far OTM, max pain and the full-chain PCR. |
| India VIX (VOL-03, VOL-04, filters) | HAVE |
| Volume for true VWAP | index-futures volume **not confirmed**; use TWAP or option volume |
| Event calendar (RBI MPC, Budget, elections, results) | **BUILD** by hand; small and easy |
| NSE participant-wise OI (FII long/short; OI-05) | **MISSING**; free daily CSV in NSE archives |
| Stock options (VOL-08, POS-03, OI-03 on stocks) | **MISSING**; proxy with stock futures plus a delta/theta approximation |
| Tick data or bid-ask | **MISSING**; matters for expiry-day Rs 2 to 10 options (EXP-01) where the spread dominates. Model it as a fixed % spread. |

## 4. Most-cited setups (top 10)

Ranked by how often each appeared across independent sources found in this survey:

1. ORB, 15-min and variants (OR-01/02/03/04)
2. VWAP-based buying (TI-04/05, MR-05)
3. Supertrend flip (TI-01/02)
4. Expiry-day hero-zero (EXP-01)
5. Expiry-day gamma blast (EXP-02)
6. Long straddle and event straddles (VOL-01/02, VOL-05/06)
7. PCR and OI signals (OI-01/02, LV-06)
8. EMA 9/21 crossover (TI-03)
9. Previous-day high/low breakout (LV-01)
10. Power-of-Stocks 5-EMA (MR-01), then CPR narrow-range (LV-02) and RSI-50/60-40 (TI-06/07)

Of these, the repo has already shown that ORB, Supertrend+EMA, RSI reversal, the EMA9/VWAP filter, CPR and OI/PCR next-candle prediction lose or show no edge on BANKNIFTY 2025-26.

**Not yet tested here, and most worth a backtest:**
- EXP-01/EXP-02/EXP-03 on NIFTY weekly 0DTE, 2020 to 2026, with spread modelling
- VOL-03 (IV-percentile long straddle)
- VOL-02 (straddle-premium breakout)
- OR-06 (09:20 premium momentum)
- POS-01 (BTST call)
- TD-02 (first-half-hour momentum into the close)
- OI-01 (daily PCR extremes)
- LV-04 (NR7 swing)

## 5. Families and counts

| family | count |
|---|---|
| A. Opening range / opening-time | 8 |
| B. Trend indicators | 10 |
| C. Mean reversion / reversal | 6 |
| D. Price levels & breakouts | 6 |
| E. Time-of-day | 3 |
| F. Long volatility (straddles) | 4 |
| G. Event trades | 4 |
| H. Expiry day (0DTE) | 5 |
| I. OI / sentiment | 5 |
| J. Overnight / positional / swing | 4 |
| **total** | **55** |

## Strategy cards

Fields: instrument & strike | timeframe | entry | filters | stop | target | trail | time exit | params to sweep | data | claims | ambiguity | evidence. `(HAVE)` = in our datasets; `(MISSING)` = need to source.

### A. Opening range / opening-time

#### OR-01 - 15-minute Opening Range Breakout (ORB15), close-confirmed  
*popularity: very high*

- **Instrument/strike:** NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX; buy ATM or 1-ITM CE/PE of nearest weekly (NIFTY/SENSEX) or current monthly (BANKNIFTY/FINNIFTY/MIDCPNIFTY after Nov-2024)
- **Timeframe:** Range 09:15-09:30; signal on 5-min (or 15-min) closes; intraday
- **Entry:** After 09:30, first 5-min candle that CLOSES above OR-high -> buy CE; closes below OR-low -> buy PE. Fill at next minute open of the option. One trade per direction per day (most common: first signal only).
- **Filters:** Skip if OR width is abnormally small or wide (common interpretation: outside 20th-80th percentile of last 20 days' OR width); skip right before scheduled events; optional ADX(14)>25 and rising; optional volume confirmation (index has no volume -> use futures volume or skip).
- **Stop:** Index at opposite end of OR (long: OR-low). Option-premium version: -30% of entry premium (or half the index stop distance x delta for ATM per TradingView plans).
- **Target:** 1.5x-2x OR width on the index (or 1:2 R:R).
- **Trail:** Optional: trail with a moving average (e.g. 20-EMA 5-min) or move stop to entry at 1R.
- **Time exit:** 15:15 if neither hit.
- **Sweep:** OR length {5,15,30,60} min; signal bar {1,5,15} min; close vs touch trigger; target {1,1.5,2,3}xR; OR-width percentile band; strike {ITM1,ATM,OTM1}; first-signal-only vs both directions; entry cutoff time {11:00,12:00,13:30}
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** onetradejournal worked example: winning 3-lot BANKNIFTY futures +Rs12,000 net vs losing -Rs6,500 (single-trade illustration, no backtest). A US 0DTE ORB backtest quotes 78% win (not India).
- **Ambiguity / common interpretation:** Range length (15 is most common), close vs wick trigger (close preferred), stop on index vs premium. Most common: 15-min range, 5-min close trigger, index stop at opposite side, 1:2 target, exit 15:15.
- **Independent evidence:** Repo test (BANKNIFTY ATM, 249 days 2025-26): 1109 trades, 45% win, -Rs193,592, 0/13 green months (FINDINGS.md). On same-day-expiry options it was positive on 11 monthly expiries (EXPIRY_SAME_DAY.md) - tiny sample.
- **Sources:** [otj_orb15](https://onetradejournal.com/strategies/fifteen-minute-orb-strategy), [otj_or5](https://onetradejournal.com/learn/how-to-trade-the-opening-range), [algotest_6](https://algotest.in/blog/6-popular-algo-trading-strategies-for-retail-traders-in-india.md), [angel_orb](https://www.angelone.in/knowledge-center/online-share-trading/opening-range-breakout-strategy-hindi), [mc_orb_afl](https://www.marketcalls.in/amibroker/backtestable-open-range-breakout-orb-study-for-amibroker.html), `research/FINDINGS.md (this repo)`, `research/EXPIRY_SAME_DAY.md (this repo)`

#### OR-02 - First 5-minute candle breakout (09:15-09:20 high/low)  
*popularity: high*

- **Instrument/strike:** NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX; ATM CE/PE nearest expiry
- **Timeframe:** Range = first 5-min candle; trigger on 5-min close (aggressive: on 1-min close / tick break)
- **Entry:** Buy CE on first 5-min close above 09:15 candle high; PE on close below its low. First valid signal only.
- **Filters:** Skip if first candle range > 0.6% of index (gap-chaos) or < 0.1%; optional: trade only in direction of gap.
- **Stop:** Opposite side of the first candle (index).
- **Target:** 1:2 R:R or previous day high/low, whichever comes first.
- **Trail:** Move to breakeven at 1R.
- **Time exit:** 15:15
- **Sweep:** trigger bar size; range-size filter; target R; with/without gap-direction filter
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** none published
- **Ambiguity / common interpretation:** Some versions use the 3rd candle (09:25-09:30) or first 3 candles; most common = first 5-min candle.
- **Sources:** [otj_or5](https://onetradejournal.com/learn/how-to-trade-the-opening-range), [tqna_5ema](https://tradingqna.com/t/power-of-stock-5-ema-stretegy/155424)

#### OR-03 - Late / wide ORB (30-min or 60-min range; trade only after 11:15)  
*popularity: medium*

- **Instrument/strike:** BANKNIFTY (original), NIFTY; ATM CE/PE
- **Timeframe:** Range 09:15-09:45 or 09:15-10:15 (marketcalls AFL variant uses range then trades after 11:15)
- **Entry:** After range time (variant: only after 11:15), buy CE when index crosses above range high; PE below range low.
- **Filters:** One trade per day; none else in source.
- **Stop:** Opposite range side (always-in variant reverses).
- **Target:** Fixed 150 index points on BANKNIFTY (scale ~0.3% of index for others).
- **Trail:** none
- **Time exit:** 15:15
- **Sweep:** range end {09:45,10:15,11:15}; earliest entry; fixed target points/%
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** No numbers published for options.
- **Sources:** [mc_orb_afl](https://www.marketcalls.in/amibroker/backtestable-open-range-breakout-orb-study-for-amibroker.html)

#### OR-04 - Bank Nifty Box 9 (+/-0.09% band around first 5-min close)  
*popularity: medium*

- **Instrument/strike:** BANKNIFTY (applicable to NIFTY); ATM CE/PE
- **Timeframe:** 5-min
- **Entry:** Upper = first 5-min close x 1.0009, Lower = x 0.9991. Buy CE when a 5-min candle closes above Upper; PE when closes below Lower. Only the first valid signal of the day.
- **Filters:** none
- **Stop:** Long: Lower band - 10 index points, confirmed only on a 5-min CLOSE beyond it (wicks ignored). Short mirror.
- **Target:** TP1 = 1:1, TP2 = 1:2 (book half at each, common interpretation).
- **Trail:** Optional breakeven after TP1.
- **Time exit:** Session end (use 15:15).
- **Sweep:** band % {0.05,0.09,0.15,0.25}; buffer points; close-based vs intrabar stop
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** none published
- **Sources:** [tv_box9](https://in.tradingview.com/script/EOY3ndjg-Bank-Nifty-Box-9/)

#### OR-05 - Open = Low / Open = High (OHOL)  
*popularity: high*

- **Instrument/strike:** Index (and F&O stocks in the original); ATM CE/PE
- **Timeframe:** Check at 09:20-09:30 (common 09:30); intraday
- **Entry:** At check time, if day OPEN == day LOW (tolerance e.g. 0.05%) buy CE; if OPEN == HIGH buy PE.
- **Filters:** Common: only if also above/below previous close or VWAP; avoid huge gaps.
- **Stop:** Day low (long) / day high (short) at entry time.
- **Target:** 1:2 R:R or EOD.
- **Trail:** none / breakeven at 1R
- **Time exit:** 15:15
- **Sweep:** check time; tolerance; target R
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** none published
- **Ambiguity / common interpretation:** Exact equality vs tolerance; check time. Most common: 09:30 check with exact/near equality on 5-min data.
- **Sources:** [kotak_ohol](https://www.kotaksecurities.com/investing-guide/intraday-trading/understand-open-high-open-low-strategy), [indmoney_ohol](https://www.indmoney.com/articles/stocks/open-high-open-low-strategy-steps-to-execute-the-strategy)

#### OR-06 - Premium momentum at 09:20 (AlgoTest 'Simple Momentum' / which-leg-moves-first)  
*popularity: high*

- **Instrument/strike:** NIFTY/BANKNIFTY/SENSEX ATM CE and ATM PE of nearest expiry (strike frozen at 09:20)
- **Timeframe:** 1-min option premiums
- **Entry:** At 09:20 record LTP of ATM CE and ATM PE. Buy the leg whose premium first rises by X% (or X points) above its 09:20 price (e.g. CE 200 -> buy at 215 with 'Points Above 15'). Other leg cancelled (one-leg variant) or kept pending (both-legs variant).
- **Filters:** Optional: X scaled to India VIX; skip expiry day or trade only expiry day.
- **Stop:** -20% to -30% of entry premium.
- **Target:** +40% to +100% of entry premium, or trail.
- **Trail:** Trail SL: every +X% move lock +Y% (AlgoTest 'trail SL').
- **Time exit:** 15:15
- **Sweep:** entry time {09:16,09:20,09:30}; momentum X {5,10,15,20}%; SL %; target %; trail step; re-entry count
- **Data:** option 1-min OHLC (HAVE)
- **Claimed results:** Platform feature; no published results.
- **Sources:** [algotest_terms](https://algotest.in/blog/definition-of-terms-used-in-backtesting-platform/)

#### OR-07 - Gap-and-go  
*popularity: medium*

- **Instrument/strike:** NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX; ATM CE (gap-up) / PE (gap-down)
- **Timeframe:** 5-min; first 5-15 min define opening range
- **Entry:** Gap = open/prev close - 1. If gap > +G (common 0.5%), wait 5-15 min, buy CE on break of opening-range high; gap-down mirror.
- **Filters:** Breakaway/news gaps (>1.2%) preferred for continuation; skip if price below VWAP (gap-up).
- **Stop:** Below VWAP or opening-range low, whichever is tighter.
- **Target:** none fixed; trail.
- **Trail:** Trail stop under each higher low (5-min swing lows).
- **Time exit:** 15:15
- **Sweep:** gap threshold G {0.3,0.5,0.8,1.2}%; OR length; stop choice
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** none published
- **Sources:** [optionx_gap](https://optionx.trade/blogs/nifty-bank-nifty-gap-fill-strategy), [otj_gapfill](https://onetradejournal.com/strategies/gap-fill-strategy)

#### OR-08 - Gap fill (fade the opening gap)  
*popularity: medium*

- **Instrument/strike:** NIFTY/BANKNIFTY; ATM PE on gap-up, ATM CE on gap-down
- **Timeframe:** 15-min first candle, intraday
- **Entry:** Gap between 0.3% and 0.8% (reject >0.8% unless reversal evidence; avoid news gaps). After 09:30, gap-down: buy CE on break of first 15-min candle HIGH; gap-up: buy PE on break of its LOW.
- **Filters:** Gap into resistance/support, weak follow-through; gap-downs fill slightly more often.
- **Stop:** Just past the opposite extreme of the first 15-min candle.
- **Target:** Previous day's close (the fill). Scale 2/3 at fill, trail rest.
- **Trail:** Trail remainder after fill.
- **Time exit:** 15:15
- **Sweep:** gap band; entry trigger (break vs close); target = prev close vs 50% fill
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** onetradejournal table (no method shown): same-day fill <0.3%: 80-90%; 0.3-0.6%: 65-75%; 0.6-1.2%: 45-60%; >1.2%: 25-40%. optionx: gap-ups fill 40-50% same day, gap-downs 20-30% (conflicting).
- **Independent evidence:** Repo: gap down >0.3% -> 69% green days on BANKNIFTY 2025-26 (42 days).
- **Sources:** [otj_gapfill](https://onetradejournal.com/strategies/gap-fill-strategy), [optionx_gap](https://optionx.trade/blogs/nifty-bank-nifty-gap-fill-strategy), [quantinsti_gap](https://blog.quantinsti.com/epat-project-gap-trading-strategy-based-on-the-markov-rule/)

### B. Trend indicators

#### TI-01 - Supertrend flip (5-min, 10/3)  
*popularity: very high*

- **Instrument/strike:** NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX; ATM CE on green flip, ATM PE on red flip
- **Timeframe:** 5-min (also 3/15-min)
- **Entry:** Skip first N candles (user-defined; common N=1-3). On a 5-min close where Supertrend(ATR 10, mult 3) turns up buy CE; turns down buy PE (always-in, reverse on flip).
- **Filters:** Optional ADX>20/25; optional 'no new entries after 14:30'.
- **Stop:** Supertrend line (close-based) or fixed index points (user-defined).
- **Target:** Opposite flip, or fixed points.
- **Trail:** Supertrend itself is the trail; optional % trailing on premium.
- **Time exit:** Session close (15:10-15:15)
- **Sweep:** ATR len {7,10,14}; mult {1.5,2,3}; timeframe {3,5,15}; N skip candles; ADX filter; fixed SL/TP
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** TradingView author: 'good results in futures', no stats. marketcalls Nifty futures backtest 2010-2016 (futures, not options).
- **Independent evidence:** Repo: Supertrend(10,3)+EMA20/50 15-min on BANKNIFTY ATM: 209 trades, 47% win, -Rs22,709 (FINDINGS.md).
- **Sources:** [tv_st_bnf](https://tradingview.com/script/LTRBSQI1-BankNifty-5min-Supertrend-Based-Strategy), [mc_supertrend](https://www.marketcalls.in/?p=37757), `research/FINDINGS.md (this repo)`

#### TI-02 - Supertrend + EMA trend filter (15-min, ST 10/3 with EMA20 > EMA50)  
*popularity: high*

- **Instrument/strike:** BANKNIFTY/NIFTY ATM
- **Timeframe:** 15-min
- **Entry:** Buy CE when Supertrend flips up AND EMA20 > EMA50 (PE mirror).
- **Filters:** EMA alignment.
- **Stop:** Supertrend line / -40 premium points (repo).
- **Target:** +80 premium points (repo) or opposite flip.
- **Trail:** Supertrend
- **Time exit:** 15:10
- **Sweep:** EMA pair; ST params; SL/TP points
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** none published
- **Independent evidence:** Repo: -Rs22,709 / 209 trades on BANKNIFTY 2025-26.
- **Sources:** `research/FINDINGS.md (this repo)`, [tv_st_bnf](https://tradingview.com/script/LTRBSQI1-BankNifty-5min-Supertrend-Based-Strategy)

#### TI-03 - EMA 9/21 (or 9/20) crossover  
*popularity: very high*

- **Instrument/strike:** NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX; ATM/1-ITM
- **Timeframe:** 5-min (also 3/15)
- **Entry:** Buy CE when EMA9 crosses above EMA21 on a closed bar; PE when crosses below.
- **Filters:** Trending markets only (common: ADX>25 or price on same side of VWAP).
- **Stop:** Opposite crossover, or swing low.
- **Target:** Opposite crossover / 1:2.
- **Trail:** Exit on opposite cross.
- **Time exit:** 15:15
- **Sweep:** fast {5,9,13}; slow {20,21,34,50}; timeframe; filter on/off
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** none; AlgoTest notes false signals in sideways markets.
- **Sources:** [algotest_6](https://algotest.in/blog/6-popular-algo-trading-strategies-for-retail-traders-in-india.md)

#### TI-04 - EMA9 x VWAP cross, 1-ITM, +8% premium target  
*popularity: high*

- **Instrument/strike:** NIFTY; 1-ITM CE (ATM-50) / 1-ITM PE (ATM+50) nearest weekly
- **Timeframe:** Not stated; common use 5-min
- **Entry:** Buy 1-ITM CE when EMA9 crosses above VWAP; 1-ITM PE when it crosses below.
- **Filters:** none in script.
- **Stop:** Opposite cross (exit and reverse).
- **Target:** Entry premium +8%.
- **Trail:** none
- **Time exit:** Not stated; use 15:15.
- **Sweep:** target % {5,8,12,20}; timeframe; VWAP vs TWAP (index has no volume); SL %
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026); volume for VWAP: index futures volume (NOT confirmed in our data) or use TWAP
- **Claimed results:** none published
- **Independent evidence:** Repo (9 EMA+VWAP+HH/HL, TWAP): next 15 min go slightly against the signal; best version breaks even.
- **Sources:** [tv_ema9vwap](https://cn.tradingview.com/script/rBNnn5TH-NIFTY-EMA-9-VWAP-ITM-Option-Signals/), `research/TREND_FILTER.md (this repo, summarised in FINDINGS.md)`

#### TI-05 - VWAP pullback continuation  
*popularity: very high*

- **Instrument/strike:** BANKNIFTY/NIFTY; ATM CE/PE
- **Timeframe:** 5-min
- **Entry:** Trend: price has stayed above VWAP and VWAP slope > 0. Pullback to within ~0.1% of VWAP then a bullish candle (e.g. engulfing) closes back above VWAP -> buy CE (PE mirror).
- **Filters:** Don't chase when far from VWAP; skip VWAP-chop days (several crosses in last hour).
- **Stop:** Close below VWAP or below the pullback swing low, whichever is lower.
- **Target:** 1:2, or prior day high.
- **Trail:** Trail under swing lows.
- **Time exit:** 15:15
- **Sweep:** pullback distance; confirmation candle type; chop filter (# VWAP crosses); target R
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026); volume for VWAP (futures) or TWAP proxy
- **Claimed results:** none published
- **Sources:** [rupeezy_vwap](https://rupeezy.in/blog/vwap-trading-strategy-intraday-options), [otj_vwap](https://onetradejournal.com/learn/how-to-use-vwap-for-intraday), `research/TREND_FILTER.md (this repo, summarised in FINDINGS.md)`

#### TI-06 - RSI > 50 regime ATM buy (viral 2026 setup)  
*popularity: high*

- **Instrument/strike:** NIFTY ATM CE (RSI>50) / ATM PE (RSI<50)
- **Timeframe:** Not stated (common 5-min); RSI length 14
- **Entry:** When RSI(14) crosses above 50 buy ATM CE; below 50 buy ATM PE.
- **Filters:** none
- **Stop:** -25% premium (e.g. 100 -> 75).
- **Target:** +50% premium (100 -> 150).
- **Trail:** Trailing stop exists (188 of 829 exits) - step not disclosed.
- **Time exit:** EOD (641 of 829 exits were end-of-day).
- **Sweep:** RSI len; threshold {50,55/45,60/40}; SL/TP %; trail step
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** 5-yr backtest: 829 trades, 52.96% win, total PnL 'Rs 4 lakh' (lot size/costs unspecified; no live validation).
- **Sources:** [multibagg_rsi](https://www.multibagg.ai/market-pulse/articles/nifty-algo-backtest-results-2026-cmrg191cpjai5np0jleu5s47x)

#### TI-07 - RSI 60/40 range-shift  
*popularity: medium*

- **Instrument/strike:** Index ATM/ITM; also positional
- **Timeframe:** 15-min / 60-min / daily
- **Entry:** Bull regime when RSI(14) > 60 -> buy CE on cross above 60 (breakout version) or on dip to 40-50 that turns up (pullback version). Bear regime RSI < 40 mirror.
- **Filters:** Regime defined by RSI staying in 40-80 (bull) / 20-60 (bear).
- **Stop:** Swing low / RSI back below 40.
- **Target:** 1:2 or RSI > 80 exhaustion.
- **Trail:** none
- **Time exit:** Intraday 15:15; positional: 2 days before expiry.
- **Sweep:** timeframe; breakout vs pullback version; thresholds
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** none published
- **Ambiguity / common interpretation:** Source describes regime idea, not a full rule set; buyers combine with price action.
- **Sources:** [fundsindia_rsi](https://fundsindia.com/blog/equities/using-rsi-more-effectively-part-iii/2384), [tv_rsi_shift](https://kr.tradingview.com/chart/NIFTY/I51DdQ7v-RSI-RANGE-SHIFT-Strategy)

#### TI-08 - Heikin-Ashi wickless trend  
*popularity: medium*

- **Instrument/strike:** NIFTY/BANKNIFTY ATM
- **Timeframe:** 5/15-min HA
- **Entry:** Buy CE on the 3rd consecutive strong green HA candle with no lower wick (variant: HA close crosses above 50 EMA). PE mirror.
- **Filters:** none
- **Stop:** Low of the signal HA candle.
- **Target:** none
- **Trail:** Exit on first red HA candle (aggressive) or first doji-like candle with wicks both sides (conservative).
- **Time exit:** 15:15
- **Sweep:** # consecutive candles {1,2,3}; wick tolerance; EMA filter; exit mode
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** none published
- **Ambiguity / common interpretation:** Lag 1-2 bars acknowledged by source.
- **Sources:** [otj_ha](https://onetradejournal.com/strategies/heikin-ashi-trend-strategy), [tv_ha_ema](https://es.tradingview.com/script/fZ90PyR8-HA-EMA-Entry-Exit-First-Wick-Candle-Exit)

#### TI-09 - ADX/DMI trend-strength entry  
*popularity: medium*

- **Instrument/strike:** Index ATM
- **Timeframe:** 5/15-min
- **Entry:** ADX(14) > 25 and rising, +DI > -DI -> buy CE on next bar break of high (PE mirror).
- **Filters:** Commonly used as filter on ORB/VWAP setups.
- **Stop:** Swing low / VWAP
- **Target:** 1:1 (VWAP-stop variant) or 1:2
- **Trail:** Exit when ADX turns down
- **Time exit:** 15:15
- **Sweep:** ADX threshold; DI length; timeframe
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** none published
- **Sources:** [otj_or5](https://onetradejournal.com/learn/how-to-trade-the-opening-range)

#### TI-10 - Multi-confluence (Supertrend + VWAP + EMA + RSI) TradingView 'option buyer' scripts  
*popularity: high*

- **Instrument/strike:** BANKNIFTY/NIFTY ATM
- **Timeframe:** 5-min
- **Entry:** All agree: price > VWAP, EMA9 > EMA21, Supertrend green, RSI > 55 -> buy CE (PE mirror).
- **Filters:** Daily loss limit, max consecutive losses (Etharia-style risk module).
- **Stop:** Supertrend / 1% capital risk
- **Target:** 1:2
- **Trail:** Supertrend
- **Time exit:** 15:15
- **Sweep:** which subset of conditions; RSI threshold; daily loss cap
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026); VWAP volume or TWAP
- **Claimed results:** none published
- **Ambiguity / common interpretation:** Many scripts are closed-source; rule set here is the common denominator.
- **Sources:** [tv_ema9vwap](https://cn.tradingview.com/script/rBNnn5TH-NIFTY-EMA-9-VWAP-ITM-Option-Signals/), [tv_st_bnf](https://tradingview.com/script/LTRBSQI1-BankNifty-5min-Supertrend-Based-Strategy)

### C. Mean reversion / reversal

#### MR-01 - Power of Stocks 5-EMA alert candle (Subhashish Pani)  
*popularity: very high*

- **Instrument/strike:** NIFTY/BANKNIFTY; buy ATM PE for shorts, ATM CE for longs
- **Timeframe:** Short side 5-min; long side 15-min
- **Entry:** Short: alert candle = candle fully ABOVE EMA5 (low > EMA5). If next candle also fully above and doesn't break alert low, it becomes the new alert. Enter (buy PE) when a later candle breaks alert LOW. Long (15-min): alert fully BELOW EMA5 (high < EMA5); buy CE when a candle breaks alert HIGH.
- **Filters:** none (sometimes: trade only in first half of day).
- **Stop:** Alert candle high (short) / low (long). tradingqna variant: or EMA3 crossing EMA5.
- **Target:** Minimum 1:3 R:R (tradingqna variant 1:2).
- **Trail:** none
- **Time exit:** 15:15
- **Sweep:** EMA len {5,9}; timeframe pairs; R:R {2,3,4}; break vs close trigger
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** Author claims ~60% success with 1:3 (unverified).
- **Sources:** [tv_pos_bb5ema](https://cn.tradingview.com/script/q4oJNMqK-Power-Of-Stocks-Bollinger-Band-5Ema-Indicator-Keanu-RiTz), [tqna_5ema](https://tradingqna.com/t/power-of-stock-5-ema-stretegy/155424), [tv_5ema](https://ru.tradingview.com/script/EaZHR7q6-5-ema-strategy)

#### MR-02 - Power of Stocks Bollinger alert candle (BB 20, 1.5 SD)  
*popularity: high*

- **Instrument/strike:** Index ATM CE/PE
- **Timeframe:** 5-min (common)
- **Entry:** Candle fully above upper BB(20,1.5) = alert; buy PE when its low breaks. Fully below lower band = alert; buy CE when its high breaks.
- **Filters:** Optional combined with 5-EMA alert (both must agree).
- **Stop:** Alert candle high (PE) / low (CE).
- **Target:** 1:4 R:R.
- **Trail:** none
- **Time exit:** 15:15
- **Sweep:** BB SD {1.5,2}; R:R; combined filter
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** none published
- **Sources:** [tv_pos_bb5ema](https://cn.tradingview.com/script/q4oJNMqK-Power-Of-Stocks-Bollinger-Band-5Ema-Indicator-Keanu-RiTz)

#### MR-03 - RSI 30/70 reversal  
*popularity: medium*

- **Instrument/strike:** BANKNIFTY/NIFTY ATM
- **Timeframe:** 5-min
- **Entry:** RSI(14) crosses back above 30 -> buy CE; back below 70 -> buy PE.
- **Filters:** none
- **Stop:** -40 premium points (repo).
- **Target:** +80 premium points (repo).
- **Trail:** none
- **Time exit:** 15:10
- **Sweep:** RSI len; levels {20/80,30/70}; SL/TP
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** none published
- **Independent evidence:** Repo: 235 trades, 34% win, -Rs31,613 (BANKNIFTY 2025-26).
- **Sources:** `research/FINDINGS.md (this repo)`

#### MR-04 - Camarilla R3/S3 fade  
*popularity: medium*

- **Instrument/strike:** Index ATM
- **Timeframe:** 5-min, levels from previous day H/L/C
- **Entry:** R3 = C + 1.1(H-L)/4. If price trades up to R3 and a 5-min candle closes back below it -> buy PE. S3 mirror -> CE.
- **Filters:** Opening inside R3-S3; avoid 09:15-09:30 false breaks.
- **Stop:** R4 (or S4).
- **Target:** Pivot / previous close / R1.
- **Trail:** none
- **Time exit:** 15:15
- **Sweep:** fade level R2/R3; confirmation bar; target level
- **Data:** index 1-min + daily H/L/C (HAVE); option 1-min (HAVE)
- **Claimed results:** none published
- **Sources:** [otj_camarilla](https://onetradejournal.com/indicators/camarilla-pivots), [mc_camarilla](https://www.marketcalls.in/amibroker/camarilla-as-trailing-stop-loss-amibroker-afl-code.html)

#### MR-05 - VWAP stretch reversion  
*popularity: medium*

- **Instrument/strike:** Index ATM
- **Timeframe:** 5-min
- **Entry:** Price > k standard deviations (VWAP bands) above VWAP -> buy PE (target VWAP); below -> CE.
- **Filters:** Range-bound days only (e.g. wide CPR, low ADX).
- **Stop:** Further 1 SD extension.
- **Target:** VWAP touch.
- **Trail:** none
- **Time exit:** 15:15
- **Sweep:** k {1.5,2,2.5}; range filter
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026); volume for VWAP bands or TWAP
- **Claimed results:** none published
- **Sources:** [algotest_6](https://algotest.in/blog/6-popular-algo-trading-strategies-for-retail-traders-in-india.md)

#### MR-06 - Opening-range fade (Range Fade)  
*popularity: medium*

- **Instrument/strike:** BANKNIFTY ATM
- **Timeframe:** 5-min
- **Entry:** Price pokes outside opening range then closes back inside -> buy option toward the opposite side of the range.
- **Filters:** Most profitable on days that close inside OR; losses on trend days.
- **Stop:** -40 premium pts
- **Target:** +30 / +60 premium pts
- **Trail:** none
- **Time exit:** 15:10
- **Sweep:** OR length; SL/TP
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** none published
- **Independent evidence:** Repo: 322 trades, 40% win, -Rs84,458; +19,916 on inside days vs -104,375 on trend days.
- **Sources:** `research/FINDINGS.md (this repo)`

### D. Price levels & breakouts

#### LV-01 - Previous-day high/low (PDH/PDL) breakout with retest  
*popularity: very high*

- **Instrument/strike:** BANKNIFTY/NIFTY ATM; 2 lots
- **Timeframe:** 5-min
- **Entry:** Zone between PDL and PDH = no-trade. Long: index breaks PDH, retraces, then a 5-min candle closes above the day high -> buy CE. Short mirror below PDL.
- **Filters:** Opening above PDH = bias long only.
- **Stop:** ATM option stop = half of the index stop distance (delta ~0.5); index stop = retest swing low.
- **Target:** Lot 1 at 1:1; then lot 2 stop to cost, target 1:3.
- **Trail:** Breakeven after lot 1.
- **Time exit:** 15:15
- **Sweep:** retest required yes/no; touch vs close; target split
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** marketcalls always-in PDH/PDL futures test (part 1 of 'do simple strategies work').
- **Sources:** [tv_pdh_plan](https://in.tradingview.com/chart/BANKNIFTY/oE6MLTt6-Banknifty-Trading-Plan-for-3-Feb-2022), [mc_pdh](https://www.marketcalls.in/amibroker/trading-the-previous-day-high-and-previous-day-low-breakout-do-simple-strategies-really-work-part1.html)

#### LV-02 - Narrow-CPR trend-day breakout  
*popularity: very high*

- **Instrument/strike:** NIFTY/BANKNIFTY ATM
- **Timeframe:** Daily CPR, 5/15-min signals
- **Entry:** Pivot=(H+L+C)/3, BC=(H+L)/2, TC=2P-BC from previous day. If |TC-BC| < 0.3% of price (narrow), buy CE on first 5-min close above TC (PE below BC).
- **Filters:** Narrow CPR only; skip wide CPR (>~0.5-0.6%).
- **Stop:** Opposite CPR edge / below BC.
- **Target:** R1 then R2 (floor pivots).
- **Trail:** Move to BE at R1.
- **Time exit:** 15:15
- **Sweep:** narrow threshold {0.1,0.2,0.3}%; trigger bar; target R1/R2
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** none published
- **Independent evidence:** Repo: narrow-CPR days were NOT more trending on BANKNIFTY (day range 513 vs 583 on wide). Skipping wide-CPR days cut losses.
- **Sources:** [jainam_cpr](https://www.jainam.in/blog/cpr-in-trading/), [tv_cpr_dash](https://id.tradingview.com/script/294UHHew-Options-Decision-Dashboard-CPR-Expected-Move-Day-Type/), `research/FINDINGS.md (this repo)`

#### LV-03 - Camarilla H4/L4 breakout  
*popularity: medium*

- **Instrument/strike:** Index ATM
- **Timeframe:** 5-min
- **Entry:** R4 = C + 1.1(H-L)/2. 5-min close above R4 -> buy CE; below S4 -> PE.
- **Filters:** Avoid 09:15-09:30.
- **Stop:** Back inside R3.
- **Target:** R4 + (R4-R3)x2 (common) or EOD.
- **Trail:** none
- **Time exit:** 15:15
- **Sweep:** level; target multiple
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** none published
- **Sources:** [otj_camarilla](https://onetradejournal.com/indicators/camarilla-pivots)

#### LV-04 - NR7 / inside-day breakout (swing)  
*popularity: medium*

- **Instrument/strike:** NIFTY/BANKNIFTY; 1-ITM CE/PE of next weekly or monthly (>=5 DTE)
- **Timeframe:** Daily signal, 15-min execution, hold 1-3 days
- **Entry:** Day t is NR7 (smallest range of last 7) and inside day. Day t+1: buy CE on break of day-t high; PE on break of day-t low.
- **Filters:** Long-only variant: close>open on day t and SMA slope up.
- **Stop:** Opposite side of NR7 bar.
- **Target:** 1x-2x NR7 range or 2-3 day hold.
- **Trail:** Trail under prior day's low.
- **Time exit:** 3 sessions or 2 days before expiry.
- **Sweep:** NR4/NR7; inside-day required; hold days; strike/expiry
- **Data:** index daily/1-min (HAVE); option 1-min for next expiry (HAVE)
- **Claimed results:** none published
- **Sources:** [unofficed_nr7](https://unofficed.com/?p=4910)

#### LV-05 - N-day range (Donchian) breakout, positional  
*popularity: medium*

- **Instrument/strike:** BANKNIFTY/NIFTY; ATM/1-ITM monthly
- **Timeframe:** Daily
- **Entry:** Close above highest high of last N days (TradingView idea uses 7-day range) -> buy CE; below lowest low -> PE.
- **Filters:** Optional India VIX < its 60-day median (cheap options).
- **Stop:** Opposite N/2-day channel.
- **Target:** Trail.
- **Trail:** N/2-day channel.
- **Time exit:** Roll or exit 5 sessions before expiry.
- **Sweep:** N {5,7,10,20}; VIX filter; strike
- **Data:** index daily (HAVE); option prices (HAVE); India VIX (HAVE)
- **Claimed results:** none published
- **Sources:** [tv_7day](https://www.tradingview.com/chart/BANKNIFTY/ilkHwdsO-Banknifty-7-day-range-breakout)

#### LV-06 - Highest-OI strike breakout (OI wall break / short-covering)  
*popularity: high*

- **Instrument/strike:** NIFTY/BANKNIFTY ATM or the wall strike
- **Timeframe:** 5-min with live OI by strike
- **Entry:** Identify strike with max call OI above spot (resistance). When index closes a 5-min bar above it AND that strike's call OI is falling over last 15-30 min (writers covering) -> buy CE. Put-wall mirror -> PE.
- **Filters:** OI change computed over ATM +/- 5 strikes.
- **Stop:** Close back below wall strike.
- **Target:** Next OI wall.
- **Trail:** none
- **Time exit:** 15:15
- **Sweep:** OI lookback; # strikes; OI drop %
- **Data:** option 1-min OI by strike (HAVE, minute OI in dataset); index 1-min (HAVE)
- **Claimed results:** none published
- **Sources:** [upstox_oi](https://upstox.com/learning-center/share-market/how-to-use-open-interest-for-intraday-trading-complete-guide/article-1858/), [otj_chgoi](https://onetradejournal.com/glossary/change-in-oi), [mc_oi_price](https://www.marketcalls.in/futures-and-options/interpreting-options-price-open-interest-relationship.html)

### E. Time-of-day

#### TD-01 - 2:50 PM candle high break  
*popularity: low*

- **Instrument/strike:** NIFTY; ITM CE
- **Timeframe:** 5-min (futures chart)
- **Entry:** Mark high/low of 14:50-14:55 candle; buy ITM CE when the 14:55-15:00 candle breaks that high.
- **Filters:** none
- **Stop:** 15 index points.
- **Target:** 20 index points.
- **Trail:** none
- **Time exit:** 15:15 hard exit.
- **Sweep:** SL/TP points; include PE mirror; candle time
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** none; community called SL too tight.
- **Sources:** [tqna_250](https://tradingqna.com/t/2-50-pm-candle-high-break/152230)

#### TD-02 - Intraday momentum: first half-hour predicts last half-hour (Gao et al.)  
*popularity: medium*

- **Instrument/strike:** NIFTY/BANKNIFTY; ATM CE/PE of nearest expiry
- **Timeframe:** Daily decision at 15:00
- **Entry:** r1 = return from previous close to 09:45 (paper: prior close -> first 30 min). At 15:00 buy CE if r1 > 0, PE if r1 < 0. (Paper also uses 12th half-hour return.)
- **Filters:** Stronger on high-volatility, high-volume, news days (paper).
- **Stop:** none (paper) / -30% premium
- **Target:** none
- **Trail:** none
- **Time exit:** 15:25-15:29 (last half-hour = 15:00-15:30).
- **Sweep:** r1 definition (gap incl./excl.); decision time; |r1| threshold; VIX filter
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** US S&P500 ETF 1993-2013: significant predictability; out-of-sample R2 ~1.6%; not shown for Nifty.
- **Independent evidence:** Option-buying version must beat 30-min theta + costs; BANKNIFTY 2:45-3:15 moves were within +/-1% 97% of time (tradingqna).
- **Sources:** [gao_intraday](https://c.mql5.com/forextsd/forum/173/intraday_momentum_-_the_first_half-hour_return_predicts_the_last_half-hour_return.pdf), [alpha_intraday](https://alphaarchitect.com/2014/08/attention-prop-traders-the-first-half-hour-of-trading-predicts-the-last-half-hour/), [tqna_last30](https://tradingqna.com/t/option-trading-strategy-based-on-bank-nifty-last-30-mins-movement/78256)

#### TD-03 - Last-30-minute OTM 'jackpot' buy (non-expiry)  
*popularity: medium*

- **Instrument/strike:** BANKNIFTY OTM CE/PE
- **Timeframe:** 14:45-15:15
- **Entry:** At 14:45 buy 1-2 strike OTM option in direction of the day's trend.
- **Filters:** none
- **Stop:** Premium
- **Target:** 2x
- **Trail:** none
- **Time exit:** 15:15-15:25
- **Sweep:** OTM distance; direction rule
- **Data:** index 1-min candles (HAVE); option 1-min OHLC for chosen strike/expiry (HAVE 2020-2026)
- **Claimed results:** Negative: 10 yrs BANKNIFTY 2:45-3:15 moves within +/-1% 97% of the time; OTM buying loses ~97/100 (tradingqna).
- **Sources:** [tqna_last30](https://tradingqna.com/t/option-trading-strategy-based-on-bank-nifty-last-30-mins-movement/78256)

### F. Long volatility (straddles)

#### VOL-01 - Long ATM straddle 09:20 -> 14:20, Tue & Fri only (no SL, no target)  
*popularity: high*

- **Instrument/strike:** BANKNIFTY weekly ATM CE + ATM PE (original, 2018-19 era)
- **Timeframe:** Intraday, fixed times
- **Entry:** 09:20 (or 09:30, little difference) buy ATM CE + ATM PE.
- **Filters:** Only on Tuesday and Friday (in-sample weekday selection).
- **Stop:** none (max loss = premium)
- **Target:** none
- **Trail:** none
- **Time exit:** 14:20
- **Sweep:** entry time; exit time; weekday filter; DTE filter; VIX filter; combined SL %
- **Data:** option 1-min (HAVE)
- **Claimed results:** Rs10k capital: gross +42,355/yr, -25,500 brokerage => +16,855 net, ~11,798 after 30% slippage; wins 3 days in 10. Weekday filter is data-mined.
- **Sources:** [unofficed_straddle](https://unofficed.com/theta/a-systematic-approach-on-option-buy-with-10000-inr/)

#### VOL-02 - Straddle-premium breakout (combined ATM premium chart)  
*popularity: high*

- **Instrument/strike:** NIFTY/BANKNIFTY/SENSEX: ATM straddle at fixed strike (frozen at 09:20) or rolling ATM
- **Timeframe:** Combined premium 1/5-min
- **Entry:** Plot CE+PE. After a compression (combined premium making lower highs/decaying), when the combined premium closes above its opening-range high or above its own VWAP/Supertrend -> buy straddle (or buy only the leg rising faster).
- **Filters:** Expiry day / after 13:00 preferred by practitioners; index structure breakout confirmation.
- **Stop:** Combined premium closes back below its VWAP or -15% of combined entry.
- **Target:** +20-50% of combined premium or trail.
- **Trail:** Supertrend on combined premium.
- **Time exit:** 15:15
- **Sweep:** breakout reference (OR high / VWAP / ST); fixed vs rolling strike; one-leg vs both; SL/TP %; start time
- **Data:** option 1-min both legs (HAVE); option volume for premium VWAP (HAVE if in dataset)
- **Claimed results:** none published
- **Ambiguity / common interpretation:** Mostly discussed for straddle SELLERS (sell on break of combined low); buying version = mirror. Most common buy rule: combined premium breaks above its day high after 12:00.
- **Sources:** [tv_straddle_prem](https://cn.tradingview.com/script/bTsA8azk-Straddle-and-Strangle-Premium-AlgoStraddle), [tv_momgamma](https://de.tradingview.com/script/heoRolnb-Momentum-Gamma-Straddle), [algotest_rolling](https://algotest.in/blog/how-to-backtest-rolling-straddles-with-indicators.md), [mc_920_gamed](https://www.marketcalls.in/futures-and-options/how-the-9-20-intraday-straddlers-are-being-gamed.html)

#### VOL-03 - Low IV-percentile long straddle/strangle (positional)  
*popularity: medium*

- **Instrument/strike:** NIFTY (or BANKNIFTY monthly): ATM straddle or 1-OTM strangle, 10-30 DTE
- **Timeframe:** Daily
- **Entry:** IVP (share of past 252 days with IV below today's) < 20 -> buy straddle. India VIX percentile is the proxy for NIFTY.
- **Filters:** No scheduled event within holding window (else see VOL-05).
- **Stop:** -30% of premium or 5 sessions.
- **Target:** +30-50% or IVP > 50.
- **Trail:** none
- **Time exit:** Exit >=3 days before expiry.
- **Sweep:** IVP threshold {10,20,30}; DTE; hold days; straddle vs strangle
- **Data:** India VIX daily (HAVE); option prices for IV (HAVE; compute IV by Black-Scholes)
- **Claimed results:** Sensibull: IVP<20 = cheap; rationale = IV mean reversion. No backtest. Note Indian VRP is persistently positive (sellers' edge).
- **Sources:** [sensibull_ivp](https://blog.sensibull.com/2018/11/25/how-high-is-high-the-iv-percentile/), [vrp_manipal](https://researcher.manipal.edu/en/publications/dynamics-of-variance-risk-premium-evidence-from-india/)

#### VOL-04 - India VIX expansion + breakout (directional)  
*popularity: low*

- **Instrument/strike:** NIFTY ATM
- **Timeframe:** 5-min
- **Entry:** India VIX up > +X% from day's open AND index breaks opening range -> buy option in breakout direction.
- **Filters:** VIX rising confirms demand for options.
- **Stop:** OR opposite side
- **Target:** 1:2
- **Trail:** none
- **Time exit:** 15:15
- **Sweep:** X {3,5,8}%; OR length
- **Data:** India VIX intraday bars (HAVE); index/option 1-min (HAVE)
- **Claimed results:** none published
- **Ambiguity / common interpretation:** Composed from broker guidance ('buy when VIX high/rising'); no single canonical rule.
- **Sources:** [fivepaisa_event](https://www.5paisa.com/blog/event-driven-option-trades)

### G. Event trades

#### VOL-05 - Pre-event IV run-up (buy straddle days before event, exit before it)  
*popularity: medium*

- **Instrument/strike:** NIFTY/BANKNIFTY ATM straddle, expiry after the event
- **Timeframe:** Daily
- **Entry:** Buy ATM straddle T-5..T-3 sessions before RBI policy / Union Budget / election result / major results.
- **Filters:** Event calendar.
- **Stop:** -20%
- **Target:** IV expansion; exit T-1 close (before announcement).
- **Trail:** none
- **Time exit:** T-1 15:20
- **Sweep:** entry lead days; exit day; straddle vs strangle
- **Data:** option prices (HAVE); event calendar (BUILD MANUALLY: RBI MPC dates, budgets, elections)
- **Claimed results:** none published
- **Sources:** [upstox_ivcrush](https://community.upstox.com/t/what-happens-when-iv-crashes-after-earnings-or-major-events-and-how-to-trade-it/12047), [fivepaisa_event](https://www.5paisa.com/blog/event-driven-option-trades)

#### VOL-06 - Event-day long straddle/strangle held through announcement  
*popularity: high*

- **Instrument/strike:** NIFTY/BANKNIFTY ATM straddle or 1-2 OTM strangle
- **Timeframe:** Event day intraday
- **Entry:** Buy before announcement (Budget ~11:00, RBI 10:00, election counting 08:00).
- **Filters:** Only if expected move > implied move (practitioner rule).
- **Stop:** -30% combined
- **Target:** +50% combined or exit on IV crush after 30-60 min
- **Trail:** none
- **Time exit:** Same day 15:15
- **Sweep:** entry timing; straddle vs strangle; exit timing
- **Data:** option prices (HAVE); event calendar (BUILD)
- **Claimed results:** tradingqna practitioners report losses even when underlying moved 3-4.5% (IV crush). Counter-example: 4 Jun 2024 election result day, NIFTY -5.9% intraday, India VIX +~40-50% -> long puts/straddles paid large.
- **Sources:** [tqna_budget](https://tradingqna.com/t/how-many-of-you-will-do-long-strangle-on-budget-day/31739), [upstox_ivcrush](https://community.upstox.com/t/what-happens-when-iv-crashes-after-earnings-or-major-events-and-how-to-trade-it/12047), [angel_election](https://www.angelone.in/news/market-updates/election-result-day-and-market-volatility), [mc_election](https://marketcalls.in/futures-and-options/navigating-volatility-ahead-of-final-election-results.html)

#### VOL-07 - Post-event directional (wait for event, trade first-hour breakout after IV crush)  
*popularity: medium*

- **Instrument/strike:** NIFTY/BANKNIFTY ATM; F&O stocks on results
- **Timeframe:** 5/15-min
- **Entry:** After announcement (or next morning for results), wait 15-60 min; buy option in direction of break of the post-event range.
- **Filters:** IV already crushed (IV < pre-event IV).
- **Stop:** Opposite side of post-event range
- **Target:** 1:2
- **Trail:** none
- **Time exit:** 15:15
- **Sweep:** wait time; range length
- **Data:** option prices (HAVE); event calendar (BUILD)
- **Claimed results:** none published
- **Sources:** [otj_news](https://onetradejournal.com/learn/how-to-trade-news-events), [upstox_ivcrush](https://community.upstox.com/t/what-happens-when-iv-crashes-after-earnings-or-major-events-and-how-to-trade-it/12047)

#### VOL-08 - Stock results-day long straddle  
*popularity: medium*

- **Instrument/strike:** F&O stocks ATM straddle, current monthly
- **Timeframe:** Buy day before results close, exit next day
- **Entry:** Buy ATM straddle at close before results.
- **Filters:** Historical post-result moves > implied move.
- **Stop:** none
- **Target:** exit next day 10:00-11:00
- **Trail:** none
- **Time exit:** next day
- **Sweep:** entry day; exit time
- **Data:** stock option prices (MISSING - we only have stock futures + daily stocks); results calendar (MISSING)
- **Claimed results:** Negative evidence: US AAPL buy-before/sell-after lost 40-60% of premium historically; Indian practitioners report losses even on 3-4.5% moves.
- **Sources:** [tqna_budget](https://tradingqna.com/t/how-many-of-you-will-do-long-strangle-on-budget-day/31739), [upstox_ivcrush](https://community.upstox.com/t/what-happens-when-iv-crashes-after-earnings-or-major-events-and-how-to-trade-it/12047)

### H. Expiry day (0DTE)

#### EXP-01 - Hero-Zero (zero-to-hero) far-OTM on expiry day  
*popularity: very high*

- **Instrument/strike:** Expiring weekly NIFTY (Tue since Sep-2025, Thu before) / SENSEX (Thu since Sep-2025); historically BANKNIFTY/FINNIFTY/MIDCPNIFTY weeklies (<= Nov-2024)
- **Timeframe:** Expiry day, usually after 13:30-14:30
- **Entry:** Buy OTM CE or PE priced Rs 2-10 (contract value <= Rs 500-600 per lot per fyers community) in direction of an afternoon breakout or simply both sides.
- **Filters:** Max 2-3 lots; only on expiry day.
- **Stop:** None (premium is the risk) or -50%.
- **Target:** >= 4x (1:4 of contract value); hold to 15:20-15:25 if running.
- **Trail:** Optional: lock 2x after 4x.
- **Time exit:** 15:25 (avoid exercise STT on ITM at expiry).
- **Sweep:** entry time {13:00,13:30,14:00,14:30}; premium band {2-5,5-10,10-20}; direction rule (breakout/both/trend); target multiple; SL
- **Data:** expiry-day option 1-min (HAVE, check far-OTM strikes present); index 1-min (HAVE)
- **Claimed results:** Anecdote: Rs 2.65 -> Rs 34 (+1,200%). AlgoTest: a Rs 40 option at 13:00 can be Rs 5 by 15:00. SEBI FY25: 70% of index-option turnover was 0DTE.
- **Sources:** [fyers_hero](https://fyers.in/community/t/what-is-zero-to-hero-options-strategy-on-expiry-day/18841), [algotest_expiry](https://algotest.in/blog/nifty-expiry-day/), [sahi_expiry](https://www.sahi.com/blogs/nifty-expiry-day-strategies-scalping-guide), [kotak_expiry](https://www.kotaksecurities.com/investing-guide/futures-and-options/understand-expiry-day-option-buying-strategy)

#### EXP-02 - Gamma blast (afternoon expiry breakout, ATM/1-OTM)  
*popularity: very high*

- **Instrument/strike:** Expiring weekly ATM or 1-OTM
- **Timeframe:** Expiry day after 13:45; 1/5-min
- **Entry:** After 13:45, index breaks the day's range (or the 12:00-13:45 consolidation) on a 5-min close with a spike in ATM IV/straddle -> buy ATM/1-OTM in breakout direction.
- **Filters:** ATM straddle compressed below X% of spot before the break (straddle compression); VIX not collapsing.
- **Stop:** -30 to -50% of premium or index back inside range.
- **Target:** 2-3x premium within minutes.
- **Trail:** Lock 1.5x after 2x.
- **Time exit:** 15:20
- **Sweep:** start time; range reference; compression threshold; SL/TP
- **Data:** expiry-day option 1-min (HAVE); index 1-min (HAVE)
- **Claimed results:** Enrich Money: premiums can multiply 2-3x within minutes (no stats).
- **Sources:** [enrich_gamma](https://enrichmoney.in/blog/gamma-blast-strategy-nse-options-expiry), [tv_gamma](https://it.tradingview.com/script/fqymTFet-Gamma-Blast-Detector-Nifty), [groww_expiry](https://groww.in/blog/expiry-day-trading), [sahi_expiry](https://www.sahi.com/blogs/nifty-expiry-day-strategies-scalping-guide)

#### EXP-03 - Expiry-day ORB on the SAME-DAY option (ITM preferred)  
*popularity: high*

- **Instrument/strike:** Expiring weekly/monthly ITM-1 or ATM
- **Timeframe:** 5-min, expiry day only
- **Entry:** ORB15 rules (OR-01) but buy the option expiring today. Varsity matrix: target on expiry day -> ITM.
- **Filters:** Expiry day only.
- **Stop:** OR opposite / -30% premium.
- **Target:** 1:2 / profit-lock.
- **Trail:** Profit lock (repo).
- **Time exit:** 15:15
- **Sweep:** ITM depth; SL/TP; profit lock
- **Data:** expiry-day option 1-min (HAVE)
- **Claimed results:** none published
- **Independent evidence:** Repo: on 11 BANKNIFTY monthly expiries, same-day option turned ORB from -4.4k to +1.9k (tiny sample).
- **Sources:** [varsity_strike](https://zerodha.com/varsity/?p=2698), `research/EXPIRY_SAME_DAY.md (this repo)`

#### EXP-04 - Max-pain convergence (expiry)  
*popularity: medium*

- **Instrument/strike:** NIFTY weekly expiring
- **Timeframe:** Expiry day / T-1
- **Entry:** Compute max pain from OI at 10:30-11:00. If spot is > d% away from max pain, buy ATM option toward max pain.
- **Filters:** Varsity author only used max pain with a frozen calc 15 days out + 5% buffer for SELLING calls; buying version is retail folklore.
- **Stop:** -30%
- **Target:** Spot reaches max pain
- **Trail:** none
- **Time exit:** 15:15
- **Sweep:** d threshold; calc time
- **Data:** OI by strike for all strikes (HAVE minute OI; check strike coverage - research file only holds +/-300 pts)
- **Claimed results:** none published
- **Ambiguity / common interpretation:** Buying use is not in Varsity; most common folklore = price drifts toward max pain on expiry.
- **Sources:** [varsity_maxpain](https://zerodha.com/varsity/chapter/max-pain-pcr-ratio/)

#### EXP-05 - Expiry +10% scalp (repo owner's style)  
*popularity: medium*

- **Instrument/strike:** BANKNIFTY / NIFTY expiring ATM
- **Timeframe:** 1/5-min
- **Entry:** Buy ATM in direction of last 5/15 min or day trend.
- **Filters:** Expiry day only.
- **Stop:** -3.3%
- **Target:** +10%
- **Trail:** none
- **Time exit:** 15:15
- **Sweep:** direction rule; target/stop; trades per day
- **Data:** expiry option 1-min (HAVE)
- **Claimed results:** none published
- **Independent evidence:** Repo: 11 BANKNIFTY expiry days, 9-18% win, all direction rules lose like coin flips.
- **Sources:** `research/EXPIRY_SCALP.md (this repo)`

### I. OI / sentiment

#### OI-01 - PCR contrarian (OI PCR extremes)  
*popularity: very high*

- **Instrument/strike:** NIFTY monthly/next-weekly ATM (positional)
- **Timeframe:** Daily (EOD OI PCR)
- **Entry:** Varsity: PCR > 1.3 = extreme bearishness -> contrarian buy CE; PCR < 0.5 = extreme bullishness -> buy PE. Academic: OI-PCR predicts returns over ~12 days, volume-PCR over ~2.5 days (2001-2013).
- **Filters:** Use OI PCR across all strikes of nearest expiry; optional 5-day change of PCR.
- **Stop:** -30% premium or PCR normalises
- **Target:** +50% or 12 sessions
- **Trail:** none
- **Time exit:** 12 sessions / 3 days before expiry
- **Sweep:** PCR thresholds; OI vs volume PCR; hold days {2,5,12}
- **Data:** EOD OI across strikes (HAVE from option minute OI; check full-chain coverage)
- **Claimed results:** Varsity thresholds (not backtested). Paper (2001-13): OI-PCR efficient predictor at 12-day horizon. Skeptic piece: predictive power weaker than media suggests.
- **Sources:** [varsity_maxpain](https://zerodha.com/varsity/chapter/max-pain-pcr-ratio/), [pcr_paper](https://www.indianjournalofentrepreneurship.com/index.php/IJF/article/view/72105), [pcr_skeptic](https://harbourfrontquant.substack.com/p/is-the-put-call-ratio-a-reliable)

#### OI-02 - Intraday change-in-OI / PCR trend (option-chain shift)  
*popularity: high*

- **Instrument/strike:** NIFTY/BANKNIFTY ATM
- **Timeframe:** 5-15 min snapshots
- **Entry:** Sum change in put OI minus call OI over ATM +/- N strikes since open. If put-OI addition > call-OI addition by ratio > 1.2 (and rising) -> buy CE; reverse -> PE. Shifting writer walls up (call OI unwinding at lower strike, building higher) = bullish.
- **Filters:** Confirm with price above VWAP.
- **Stop:** OI ratio flips
- **Target:** 1:2 / next OI wall
- **Trail:** none
- **Time exit:** 15:15
- **Sweep:** N strikes; ratio threshold; snapshot interval
- **Data:** option 1-min OI by strike (HAVE)
- **Claimed results:** none published
- **Independent evidence:** Repo: OI/PCR conditions concurrent with candles but do NOT predict next candle (GREEN_CANDLES.md).
- **Sources:** [upstox_oi](https://upstox.com/learning-center/share-market/how-to-use-open-interest-for-intraday-trading-complete-guide/article-1858/), [otj_chgoi](https://onetradejournal.com/glossary/change-in-oi), `research/FINDINGS.md (this repo)`

#### OI-03 - Price-OI build-up classification (long build-up / short covering)  
*popularity: medium*

- **Instrument/strike:** Index futures or F&O stock futures -> buy corresponding ATM CE/PE
- **Timeframe:** 15-min / daily
- **Entry:** Price up & futures OI up = long build-up -> buy CE; price down & OI up = short build-up -> buy PE; price up & OI down = short covering -> short-lived CE scalp.
- **Filters:** OI change > X% of OI.
- **Stop:** Signal reversal
- **Target:** 1:2
- **Trail:** none
- **Time exit:** intraday or 3 days
- **Sweep:** OI change threshold; horizon
- **Data:** futures OI (HAVE for F&O stock futures; index futures OI - NOT confirmed); stock options (MISSING) -> test on futures returns as proxy
- **Claimed results:** none published
- **Sources:** [motilal_oi](https://www.motilaloswal.com/learning-centre/2020/2/open-interest-trading-strategy), [mc_oi_price](https://www.marketcalls.in/futures-and-options/interpreting-options-price-open-interest-relationship.html)

#### OI-04 - Highest-OI strike as next-day magnet  
*popularity: medium*

- **Instrument/strike:** NIFTY weekly
- **Timeframe:** Daily
- **Entry:** Strike with highest total OI today ~ next-day close; buy option toward that strike if spot is far from it.
- **Filters:** none
- **Stop:** -30%
- **Target:** strike reached
- **Trail:** none
- **Time exit:** next day
- **Sweep:** distance threshold
- **Data:** EOD OI by strike (HAVE)
- **Claimed results:** Study (2014-2020) reports 99.2% correlation between max-OI strike and next close - a LEVEL correlation, near-meaningless for direction (treat as unproven).
- **Sources:** [oi_paper](https://www.inderscience.com/filter.php?aid=98900)

#### OI-05 - FII index-futures long/short ratio (positional contrarian/trend)  
*popularity: medium*

- **Instrument/strike:** NIFTY monthly ATM/ITM
- **Timeframe:** Daily (NSE participant-wise OI)
- **Entry:** FII long/short ratio in index futures at multi-month extreme low (e.g. < 0.3) and turning up -> buy CE (short-covering rally); high & turning down -> PE.
- **Filters:** Ratio change over 3-5 days.
- **Stop:** -30% / ratio reverses
- **Target:** 10 sessions
- **Trail:** none
- **Time exit:** before expiry week
- **Sweep:** thresholds; lookback
- **Data:** NSE participant-wise OI (MISSING - free daily CSV from NSE archives)
- **Claimed results:** Business Standard reports ratios (e.g. FII 0.66, prop 0.42) as commentary; no backtest.
- **Sources:** [bs_fii](https://www.business-standard.com/markets/news/fiis-diis-retail-find-out-who-holds-most-bullish-bearish-bets-in-f-o-125032800165_1.html)

### J. Overnight / positional / swing

#### POS-01 - BTST index call (buy near close, sell next morning)  
*popularity: high*

- **Instrument/strike:** NIFTY/BANKNIFTY ATM CE of next weekly (avoid expiring)
- **Timeframe:** Entry 15:15, exit next day 09:20-09:30
- **Entry:** At 15:15 buy ATM CE (marketcalls futures version: unconditional). Common filter version: day closed in top 20-25% of range and above VWAP/TWAP.
- **Filters:** Skip event nights; skip Fridays (weekend theta) - common interpretation.
- **Stop:** none intraday (gap risk); next-day 09:20 exit
- **Target:** exit 09:20-09:30
- **Trail:** none
- **Time exit:** 09:20-09:30 next session
- **Sweep:** close-location filter; exit time; weekday; CE only vs PE mirror on weak closes
- **Data:** index 1-min (HAVE); option 1-min across days (HAVE)
- **Claimed results:** marketcalls: NIFTY futures buy 15:15 / sell 09:30, Jan-2011..Jun-2018, +595% on Rs1L capital, zero costs/slippage (futures, not options).
- **Sources:** [mc_btst](https://www.marketcalls.in/amibroker/buy-at-todays-close-and-sell-at-next-day-open-do-simple-trading-strategies-really-work-part2.html), [algotest_btst](https://algotest.in/blog/btst-trading.md), [otj_btst](https://onetradejournal.com/strategies/btst-momentum-strategy), [tqna_btst](https://tradingqna.com/t/btst-buy-today-sell-tomorrow-trading-strategy/43659)

#### POS-02 - Positional trend with Varsity strike matrix  
*popularity: medium*

- **Instrument/strike:** NIFTY/BANKNIFTY; strike by Varsity table
- **Timeframe:** Daily / 60-min
- **Entry:** Trend signal (e.g. daily Supertrend green or EMA20>EMA50 cross). Strike per Varsity: first half of series & target in 5 days -> 2-strike OTM; 15 days -> ATM/1-OTM; 25 days -> slightly ITM; target on expiry -> ITM. Second half: same-day target -> far OTM 2-3 strikes; 5 days -> 1-OTM; 10 days -> ATM/slightly ITM.
- **Filters:** Low India VIX preferred.
- **Stop:** Signal reversal / -35%
- **Target:** Index target
- **Trail:** Supertrend
- **Time exit:** Exit 2-3 days before expiry or roll
- **Sweep:** signal; strike rule; hold
- **Data:** index daily (HAVE); option monthly prices (HAVE)
- **Claimed results:** none published
- **Sources:** [varsity_strike](https://zerodha.com/varsity/?p=2698)

#### POS-03 - Momentum burst (Stockbee 4% breakout) on F&O stocks  
*popularity: medium*

- **Instrument/strike:** F&O stocks; ATM CE monthly
- **Timeframe:** Daily, hold 3-5 days
- **Entry:** Day: close > +4% vs prev close, volume > prev day, close within top 30% of range, prior 3-10 days range contraction -> buy ATM CE at close or next open.
- **Filters:** Not after 3+ consecutive up days.
- **Stop:** Low of breakout day
- **Target:** 3-5 day hold / +8-20% underlying
- **Trail:** none
- **Time exit:** 5 sessions
- **Sweep:** breakout %; hold days
- **Data:** daily stocks (HAVE); stock futures (HAVE, use as proxy); stock options (MISSING)
- **Claimed results:** none published
- **Sources:** [stockbee_skill](https://tessl.io/registry/skills/github/tradermonty/claude-trading-skills/stockbee-momentum-burst-screener/review), [tv_mb](https://cn.tradingview.com/script/Rf67M40u-StockBee-MB-Bullish)

#### POS-04 - Weekly expiry 'cheap next-week' directional swing  
*popularity: medium*

- **Instrument/strike:** NIFTY next-weekly ATM
- **Timeframe:** Daily
- **Entry:** On the day AFTER weekly expiry buy next-week ATM in direction of daily trend (EMA20 slope).
- **Filters:** VIX < 60-day median
- **Stop:** -30%
- **Target:** +50%
- **Trail:** none
- **Time exit:** T-1 before that expiry
- **Sweep:** entry weekday; trend rule
- **Data:** option prices (HAVE); India VIX (HAVE)
- **Claimed results:** none published
- **Ambiguity / common interpretation:** Folk setup; composed from broker guidance on DTE and theta.
- **Sources:** [varsity_strike](https://zerodha.com/varsity/?p=2698), [algotest_expiry](https://algotest.in/blog/nifty-expiry-day/)
