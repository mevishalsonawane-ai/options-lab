# HUNT R1: a second internet sweep of option-buying strategies, and the new ones tested

Written 9 Oct 2026. The frame is fixed: option BUYING only, Rs 1,00,000 capital, FIXED 1 lot (NIFTY 65, BANKNIFTY 30),
1-ITM strike of the nearest expiry. Exits are checked on the option's 1-minute high/low. Costs are the app's charges at
today's rates plus the real h24 half-spread (0.16% a side, NIFTY and BANKNIFTY). Each trade is compared with
random-entry twins. Results are corrected for multiple testing (BH and a White reality check). The locked year
(1 Oct 2025 - 6 Oct 2026) was opened once, after the design picks were frozen.

- Plan, written before any P&L: `research/hunt/r1/PREREG.md`.
- Code: `research/hunt/r1/` (`lib.py` engine and stats, `signals.py` the 15 ideas, `design.py`, `holdout.py`).
- Outputs: `scratchpad/hunt/r1/` (3 MB): `design_variants.csv` (all 214 variants), `frozen.json` (picks),
  `holdout_picks.csv`, `design_trades_*.parquet`, `holdout_trades_*.parquet`, `design.log`, `holdout.log`.
- Data: local Dhan minute data only (index, India VIX, nearest-expiry ATM±10 options, NIFTY-50 stock minutes).
  The refreshed Dhan token was not needed, because the local data already covers the whole locked year.

## Verdict (plain English)

**No. None of the new internet strategies makes money for an option buyer with Rs 1 lakh and 1 fixed lot after real
costs.**

1. **Most of what the internet recommends was already tested here, and all of it lost.** The sweep found 59 distinct
   ideas.
   - 40 had already been tested in this repo: ORB, 9:20 straddle, hero-zero, gamma blast, OI shift, PCR, max pain,
     IV crush, VWAP + Supertrend, CPR, Heikin-Ashi, inside bar, Bollinger squeeze, volume profile, event straddles,
     FII long/short, Gao intraday momentum, overnight drift and the rest.
   - 4 are not testable for a buy-only index trader.
   - **15 were genuinely new.** I tested all 15, in 214 versions on NIFTY and BANKNIFTY.
2. **Design years (2020/21 - Sep 2025):**
   - Only 69 of 214 versions made any money.
   - None survives the correction: the smallest BH q is 1.00 and the best reality-check p is 0.73.
   - The best version made Rs 142/day (ICT fair value gap, BANKNIFTY), and that came from two good years.
3. **Locked year (opened once, 30 frozen picks, one per idea per index):**
   - 13 of 30 were positive and 17 negative.
   - The two design favourites flipped to losses. The fair value gap made -Rs 334/day (NIFTY) and -Rs 196/day
     (BANKNIFTY). The "noise area" momentum from the 2024 SPY paper made -Rs 297/day (NIFTY).
   - The one big positive locked-year result (turtle soup, BANKNIFTY, +Rs 247/day) had lost Rs 77/day in design. That
     is noise, not an edge.
4. **Rs 5,000/day remains out of reach.** Even the best honest figure here is about 2% of that.
5. **One weak lead, for paper only (it does not pass): VIX/index divergence (N13).**
   - **The rule:** buy a PE when the index is up more than 0.2% since the open while India VIX is up more than 2%
     since the open. Buy a CE in the mirror case. The check runs at 10:30, 11:30, 12:30 and 13:30, and takes the first
     signal of the day.
   - **Why it is the lead:** it is the only idea positive in both periods on both indices.
     - Design: NIFTY +Rs 67/day, BANKNIFTY +Rs 89/day. 10 of its 12 variants were positive, and it beat random entries
       (p 0.03 / 0.01).
     - Locked year: NIFTY +Rs 70/day (29 trades), BANKNIFTY +Rs 112/day (45 trades).
   - **Why it fails:**
     - It has only about 30-45 trades a year.
     - The correction kills it: BH q 1.0, reality-check p 0.85-0.96.
     - Its locked-year beat over random entries is not significant (p 0.10 / 0.17).
     - It was green in only 3 (NIFTY) and 7 (BANKNIFTY) of the locked year's 13 months.
   - It is worth a paper arm only as a cheap, rare signal to log, never with money until about 150 paper trades agree.
6. **The plan stays the same.** BANKNIFTY Liquidity 15+5 at 1 lot is still the only rule with a measured edge
   (h24/h36/X3: about Rs 70/day before the locked year and Rs 190/day in it).

## Results: every new idea (the design pick per index, then the locked year once)

Rs/day is over all sessions of the period, with 0 on days without a trade. DD is the worst peak-to-trough of daily
equity. q is BH over all 214 design p-values. p rand is the one-sided test against random-entry twins (same days,
random side and minute in the idea's window, same exit type). Variant names are `idea|params|exit`.

| idx | design pick | trades | design Rs/day | win | DD | q | p rand | hold trades | hold Rs/day | win | DD | p rand | green months | result |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| NIFTY | `N1_NOISE\|m1.0\|EOD` | 659 | +89 | 46% | 97k | 1.00 | 0.23 | 133 | **-297** | 43% | 100k | 0.93 | 4/13 | FAIL |
| BN | `N1_NOISE\|m1.5\|trail1` | 339 | +26 | 23% | 62k | 1.00 | 0.46 | 87 | -76 | 22% | 34k | 0.83 | 4/13 | FAIL |
| NIFTY | `N2_WVB\|k0.75\|EOD` | 500 | +61 | 42% | 70k | 1.00 | 0.55 | 95 | +93 | 45% | 43k | 0.23 | 7/13 | FAIL (no better than random) |
| BN | `N2_WVB\|k0.75\|LIQ` | 404 | +101 | 28% | 67k | 1.00 | 0.15 | 108 | -84 | 30% | 51k | 0.82 | 4/13 | FAIL |
| NIFTY | `N3_HKS\|L20\|t1.0` | 4,375 | -552 | 42% | 705k | 1.00 | 0.99 | 945 | -431 | 43% | 131k | 0.66 | 1/13 | FAIL |
| BN | `N3_HKS\|L20\|t1.0` | 3,540 | -626 | 43% | 639k | 1.00 | 0.92 | 923 | -947 | 40% | 245k | 0.92 | 1/13 | FAIL |
| NIFTY | `N4_ROD\|1440\|pc\|expiry` | 268 | -33 | 45% | 54k | 1.00 | 0.62 | 51 | +6 | 45% | 12k | 0.25 | 6/13 | FAIL |
| BN | `N4_ROD\|1440\|pc\|expiry` | 177 | -96 | 36% | 104k | 1.00 | 0.96 | 13 | +35 | 54% | 9k | 0.15 | 7/13 | FAIL |
| NIFTY | `N5_NOON\|1200\|CE` | 1,276 | -137 | 44% | 202k | 1.00 | 0.20 | 239 | -327 | 39% | 88k | 0.72 | 4/13 | FAIL |
| BN | `N5_NOON\|1300\|CE` | 996 | -188 | 41% | 201k | 1.00 | 0.22 | 248 | -375 | 40% | 97k | 0.57 | 5/13 | FAIL |
| NIFTY | `N6_FVG\|0930\|g0.0005\|EOD` | 982 | +125 | 32% | 74k | 1.00 | **0.006** | 183 | **-334** | 28% | 81k | 0.81 | 2/13 | FAIL (flipped) |
| BN | `N6_FVG\|0930\|g0.0005\|LIQ` | 814 | **+142** | 28% | 46k | 1.00 | **0.002** | 201 | **-196** | 36% | 60k | 0.46 | 3/13 | FAIL (flipped) |
| NIFTY | `N7_TURTLE\|B5\|EOD` | 777 | -25 | 28% | 105k | 1.00 | 0.20 | 147 | -222 | 31% | 81k | 0.82 | 2/13 | FAIL |
| BN | `N7_TURTLE\|B5\|native` | 657 | -77 | 36% | 167k | 1.00 | 0.36 | 163 | +247 | 40% | 31k | 0.045 | 8/13 | FAIL (design < 0) |
| NIFTY | `N8_IBS\|0.1\|LIQ` | 339 | -2 | 22% | 43k | 1.00 | 0.61 | 59 | +18 | 24% | 18k | 0.42 | 4/13 | FAIL |
| BN | `N8_IBS\|0.2\|60m` | 471 | +53 | 47% | 61k | 1.00 | 0.17 | 125 | -8 | 44% | 34k | 0.60 | 6/13 | FAIL |
| NIFTY | `N9_VIXSPK\|chg15\|0916\|EOD` | 18 | +44 | 72% | 14k | 1.00 | 0.04 | 7 | +13 | 43% | 8k | 0.05 | 2/13 | FAIL (18 trades in 5 yrs) |
| BN | `N9_VIXSPK\|chg15\|0916\|EOD` | 14 | +51 | 43% | 10k | 1.00 | 0.12 | 7 | -26 | 43% | 11k | 0.42 | 1/13 | FAIL |
| NIFTY | `N10_MIDDAY\|1130\|EOD` | 941 | -46 | 43% | 143k | 1.00 | 0.17 | 173 | -282 | 38% | 72k | 0.88 | 2/13 | FAIL |
| BN | `N10_MIDDAY\|1130\|LIQ` | 688 | -136 | 27% | 146k | 1.00 | 0.91 | 165 | -72 | 31% | 33k | 0.43 | 6/13 | FAIL |
| NIFTY | `N11_FIRST2\|EOD` | 587 | +10 | 25% | 98k | 1.00 | 0.13 | 105 | -191 | 18% | 78k | 0.73 | 4/13 | FAIL |
| BN | `N11_FIRST2\|EOD` | 486 | -71 | 21% | 177k | 1.00 | 0.52 | 110 | -291 | 18% | 87k | 0.91 | 5/13 | FAIL |
| NIFTY | `N12_RVORB\|rv1.5\|LIQ` | 258 | +83 | 17% | 22k | 1.00 | 0.22 | 47 | +3 | 11% | 25k | 0.53 | 3/13 | FAIL |
| BN | `N12_RVORB\|rv1.5\|LIQ` | 210 | +4 | 14% | 48k | 1.00 | 0.56 | 62 | +0 | 26% | 26k | 0.37 | 6/13 | FAIL |
| NIFTY | `N13_VIXDIV\|a0.002\|LIQ` | 148 | +67 | 32% | 22k | 1.00 | **0.03** | 29 | **+70** | 28% | 7k | 0.10 | 3/13 | FAIL (correction); paper lead |
| BN | `N13_VIXDIV\|a0.002\|EOD` | 178 | +89 | 50% | 42k | 1.00 | **0.01** | 45 | **+112** | 51% | 13k | 0.17 | 7/13 | FAIL (correction); paper lead |
| NIFTY | `N14_LUNCHREV\|0.006\|LIQ` | 242 | -29 | 26% | 37k | 1.00 | 0.74 | 36 | +83 | 31% | 11k | 0.09 | 4/13 | FAIL (design < 0) |
| BN | `N14_LUNCHREV\|0.006\|LIQ` | 281 | -80 | 21% | 110k | 1.00 | 0.63 | 59 | +47 | 36% | 17k | 0.34 | 6/13 | FAIL (design < 0) |
| NIFTY | `N15_BREADTH\|1100\|0.8\|LIQ` | 31 | +64 | 35% | 9k | 1.00 | 0.34 | 22 | -44 | 27% | 11k | 0.74 | 3/13 | FAIL |
| BN | `N15_BREADTH\|1100\|0.7\|LIQ` | 91 | +63 | 38% | 16k | 1.00 | 0.11 | 67 | +38 | 34% | 17k | 0.52 | 5/13 | FAIL |

**PASS** needed all five of: design Rs/day > 0, design BH q < 0.10, design p rand < 0.05, holdout Rs/day > 0, and
holdout p rand < 0.10. **0 of 30 passed.** Design periods: NIFTY Aug 2020 - Sep 2025 (about 1,270 sessions),
BANKNIFTY 2021 - Sep 2025. VIX-minute ideas start in 2022, and breadth (N15) starts only in Oct 2024 (about 245
design sessions).

### All 214 design variants, by idea

| idea | variants | positive | best Rs/day | median Rs/day | worst Rs/day | median gross Rs/trade |
|---|---|---|---|---|---|---|
| N1 noise area | 16 | 9 | +89 | +17 | -127 | +128 |
| N2 Williams breakout | 18 | 6 | +101 | -48 | -691 | +36 |
| N3 same-slot periodicity | 8 | 0 | -552 | -989 | -1,537 | -57 |
| N4 rest-of-day → close | 18 | 0 | -33 | -183 | -320 | -175 |
| N5 post-noon long | 12 | 0 | -132 | -304 | -593 | -194 |
| N6 fair value gap | 24 | 13 | +142 | +4 | -174 | +101 |
| N7 turtle soup | 12 | 0 | -25 | -87 | -141 | -44 |
| N8 IBS | 12 | 4 | +53 | -21 | -249 | +34 |
| N9 VIX spike | 24 | 15 | +56 | +6 | -29 | +369 |
| N10 midday box | 12 | 0 | -43 | -121 | -308 | -64 |
| N11 first two candles | 6 | 1 | +10 | -63 | -131 | -27 |
| N12 RVOL 5-min ORB | 12 | 5 | +83 | -21 | -392 | +26 |
| N13 VIX/index divergence | 12 | 10 | +89 | +24 | -11 | +354 |
| N14 lunch reversal | 12 | 0 | -29 | -114 | -196 | -213 |
| N15 breadth | 16 | 6 | +133 | -69 | -248 | -115 |

- 16 variants beat their random twins at p < 0.05, mostly fair value gap and VIX/index divergence. Against random
  entries the best BH q is 0.21, so even "better than random" does not survive the correction.
- Year by year, the fair value gap pick's design profit is one year: NIFTY +Rs 1.81 lakh in 2024, against
  -Rs 47k (2025), -Rs 10k (2021), etc.

## What each new idea was (exact rules as tested) and its source

| id | idea | rule as tested | source |
|---|---|---|---|
| N1 | Noise-area intraday momentum (Barbon, Aziz, Zarattini 2024, SPY, claimed Sharpe 1.33) | band = max/min(open, prev close) × (1 ± m·σ), σ = 14-day mean \|move from open\| at that minute; checks every 30 min 10:00-14:30; exit trailing on max(band, TWAP), EOD or LIQ | [SFI paper](https://www.sfi.ch/fr/publications/n-24-97-beat-the-market-an-effective-intraday-momentum-strategy-for-s-p500-etf-spy), [Concretum bands](https://de.tradingview.com/script/CUpWCZhe-Concretum-Bands) |
| N2 | Larry Williams volatility breakout | open ± k × prev-day range, first close beyond; stop back at the open | [LuxAlgo](https://www.luxalgo.com/library/indicator/vzWeDyXd-volatility-breakout-strategy/), [TradingView](https://de.tradingview.com/script/P8cxeiJy) |
| N3 | Intraday periodicity (Heston, Korajczyk, Sadka 2010) | trade each 30-min slot in the sign of that slot's mean over the last 20/40 days | [paper](https://www.bauer.uh.edu/departments/finance/documents/Heston_Korajczyk_Sadka_paper_UH.pdf) |
| N4 | Market intraday momentum, rest-of-day → last 30 min (Baltussen, Da, Lammers, Martens 2021; Gao et al. SLH) | 14:30/14:40 return from prev close or open beyond 0/0.5% → buy that way, out 15:10; expiry-only variant | [JFE paper](https://www3.nd.edu/~zda/intramom.pdf), [onetradejournal last hour](https://onetradejournal.com/strategies/last-hour-trading-strategy) |
| N5 | Day/night and post-noon option-return asymmetry in NIFTY (Bhat, Pandey, Rao 2024) | buy CE / PE / straddle at 12:00 or 13:00, hold to 15:10 | [J. Futures Markets](https://ideas.repec.org/a/wly/jfutmk/v44y2024i8p1320-1337.html), [Muravyev & Ni](https://www.cxoadvisory.com/equity-options/intraday-versus-overnight-option-returns/) |
| N6 | ICT fair value gap, first FVG of the day | 3-bar 5-min gap after 09:30/10:00 (size ≥ 0 / 0.05%); buy on the first touch back into the gap; stop beyond bar 1, 2R target | [TradingView ICT first FVG](https://id.tradingview.com/scripts/ictconcepts/), [Nasdaq FVG backtest](https://backtrex.com/en/backtests/ict-fair-value-gap-nasdaq) |
| N7 | Turtle soup (Connors/Raschke) on prev-day low/high | trade below PDL then a 5/15-min close back above → CE (mirror PDH → PE); stop at the session extreme, 2R | [LuxAlgo turtle soup](https://www.luxalgo.com/library/concept/turtle-soup.md) |
| N8 | Internal bar strength | yesterday's IBS < 0.2 (0.1) → CE at 09:16, > 0.8 (0.9) → PE | [arXiv 2306.12434](https://arxiv.org/pdf/2306.12434) |
| N9 | India VIX spike → buy calls | yesterday's VIX +10% / +15% or above its 250-day 90th percentile → CE at 09:16 / 09:45 | [5paisa](https://www.5paisa.com/marathi/blog/mean-reversion-strategy-using-india-vix-extremes), [dev.to](https://dev.to/shaktitiwari/india-vix-mastery-how-to-use-fear-as-a-trading-signal-4ghk), [CMT](https://content.cmtassociation.org/a/vix-at-28-signal-or-noise) |
| N10 | Lunch-box breakout after 13:30 | 11:30(12:00)-13:29 range; first 5-min close outside, 13:30-14:45; stop the other side, 1× box target | [AlgoTest expiry phases](https://algotest.in/blog/bank-nifty-expiry-day.md) |
| N11 | First two 5-min candles (Kotak / IIFL) | both green → CE above bar 2's high (mirror); stop bar 2 low; target 2× bar height | [Kotak](https://kotaksecurities.com/futures-and-options/bank-nifty-options-tips-and-strategies), [IIFL](https://indiainfoline.com/knowledge-center/share-market/bank-nifty-option-tips-and-strategy) |
| N12 | 5-min ORB "in play" with relative volume (Zarattini & Aziz 2023) | first 5-min candle's direction at 09:20, stop at its other end; only when the opening option volume is ≥ 1.0/1.5× its 14-day mean | [SSRN 4416622](https://papers.ssrn.com/abstract=4416622), [replication: net zero](https://www.mql5.com/en/blogs/post/776235) |
| N13 | VIX / index divergence | index +0.2-0.3% since open while VIX +2-3% → PE (mirror → CE), at 10:30-13:30 | [TradingView VIX primer](https://my.tradingview.com/chart/ETHUSD/yI9ywCMV-Volatility-Index-India-VIX-Trading), [dev.to](https://dev.to/shaktitiwari/india-vix-mastery-how-to-use-fear-as-a-trading-signal-4ghk) |
| N14 | Midday reversal | at 12:00, a move from the open beyond ±0.3/0.6% → fade it, out at 13:30 / 15:10 / LIQ | [multibagg 1-2 pm dip](https://www.multibagg.ai/market-pulse/articles/intraday-dip-1-2pm-pattern-cmqunr4zw9oiwnz0j0drf8zg9), [onetradejournal timing](https://onetradejournal.com/learn/best-time-to-trade-indian-markets) |
| N15 | Market breadth (advance/decline) | share of NIFTY-50 stocks above their open at 10:00/11:00 > 0.8 (0.7) → CE, < 0.2 (0.3) → PE | [Kotak A/D guide](https://www.kotaksecurities.com/investing-guide/articles/advance-decline-ratio-guide), [Angel One](https://community.angelone.in/t/understanding-the-a-d-ratio-advance-decline-ratio-adr/7317) |

Every idea was also run with the app's Liquidity exit (LIQ: -15% premium stop, out after 20 min unless +5%) and/or a
plain 15:10 exit. All versions are counted in the correction.

## The full catalog: 59 ideas, and where each was tested

**Already tested in this repo (40; not repeated).**

| # | idea | where tested | result there | source |
|---|---|---|---|---|
| 1 | 15-min ORB (and +VWAP) | OBUY_GA OR-01, FINDINGS, h19-h21 | loses, worse than random | [onetradejournal](https://onetradejournal.com/strategies/fifteen-minute-orb-strategy) |
| 2 | First 5-min candle breakout | OBUY_GA OR-02 | loses | [TradingQnA](https://tradingqna.com/t/help-in-creating-bank-nifty-strategy/142374) |
| 3 | Late / first-hour range ORB | OBUY_GA OR-03 | loses | catalog OR-03 |
| 4 | BankNifty Box 9 | OBUY_GA OR-04 | loses | catalog OR-04 |
| 5 | Open = high / open = low | OBUY_GA OR-05 | loses | catalog OR-05 |
| 6 | 09:20 premium momentum / AlgoTest re-entry | OBUY_GA OR-06 | loses | [AlgoTest glossary](https://algotest.in/blog/definition-of-terms-used-in-backtesting-platform/) |
| 7 | Gap-and-go / gap fill | OBUY_GA OR-07/08, h27, MARKET_HOW 3d | walk-forward negative | catalog |
| 8 | Supertrend flip / + EMA | OBUY_GB TI-01/02 | loses | catalog |
| 9 | EMA 9/21, EMA9×VWAP, VWAP pullback | OBUY_GB TI-03/04/05, h46 | loses | [marketcalls VWAP](https://www.marketcalls.in/amibroker/vwap-intraday-trading-strategy-do-simple-trading-strategies-really-work-part4.html) |
| 10 | RSI > 50, RSI 60/40, RSI 30/70 | OBUY_GB TI-06/07, MR-03 | loses | catalog |
| 11 | Heikin-Ashi | OBUY_GB TI-08, h25 | loses | catalog |
| 12 | ADX/DMI, multi-confluence VWAP+Supertrend+RSI | OBUY_GB TI-09/10 | loses | catalog |
| 13 | Power of Stocks 5-EMA / Bollinger alert candle | OBUY_GB MR-01/02 | loses | catalog |
| 14 | Camarilla fade / breakout | OBUY_GB MR-04, LV-03 | ~0 since 2023 | catalog |
| 15 | VWAP stretch / range fade | OBUY_GB MR-05/06 | loses | catalog |
| 16 | PDH/PDL breakout, CPR, pivots | OBUY_GA LV-01/02, h35 | loses | catalog |
| 17 | NR7 / inside bar / inside day | LV-04, h25 | loses | catalog |
| 18 | Donchian / RSI(2) / Connors swing | SWING_DEEP, SWING_EXITS, LV-05 | gains are overnight gaps, intraday loses | catalog |
| 19 | Highest-OI strike break / OI wall | OBUY_GA LV-06, h26 | no | catalog |
| 20 | 2:50 pm candle, last-30-min OTM "jackpot" | OBUY_GA TD-01/03 | loses | catalog |
| 21 | Gao first half-hour → last half-hour | OBUY_GA TD-02, MARKET_HOW 3c | corr -0.08 to 0 in India | [paper](https://c.mql5.com/forextsd/forum/173/intraday_momentum_-_the_first_half-hour_return_predicts_the_last_half-hour_return.pdf) |
| 22 | 09:20 long straddle; straddle-premium breakout | OBUY_GC VOL-01/02, LONG_VOL, STRAD_INDEX | every version loses | catalog |
| 23 | Low-IV-percentile straddle; VIX expansion | VOL-03/04, h29 | no | catalog |
| 24 | Long straddle before events / event day / IV crush | VOL-05/06/07, h28, STRAD_INDEX | loses; realised 0.69× implied | catalog |
| 25 | Hero-zero, gamma blast, expiry ORB | EXP-01/02/03, EXPIRY_SAME_DAY | hero-zero lost in all 96 variants | [AlgoTest expiry](https://algotest.in/blog/bank-nifty-expiry-day.md) |
| 26 | Max-pain pin | EXP-04 | +7k on 27 trades | catalog |
| 27 | PCR reversal, OI shift, OI build-up | OI-01/02/03, h26, h38 | 1-2 bp lean, far below cost | [PCR study](https://www.indianjournalofentrepreneurship.com/index.php/IJF/article/view/72105) |
| 28 | FII index-futures long/short ratio, participant OI | OI-05, h40 | predicts the gap, not the day | NSE archives |
| 29 | BTST / overnight drift | POS-01, h31 | weak, decaying | catalog |
| 30 | Volatility risk premium sign for buyers | STRAD_INDEX, LONG_VOL | buyers pay it (0.61-0.72×) | [Manipal VRP](https://researcher.manipal.edu/en/publications/dynamics-of-variance-risk-premium-evidence-from-india/) |
| 31 | Intraday long-option returns (Muravyev & Ni) | STRAD_INDEX (every 15 min, all horizons) | 0 of 2,520 net positive | [CXO](https://www.cxoadvisory.com/equity-options/intraday-versus-overnight-option-returns/) |
| 32 | Lottery-like / cheap far-OTM options | EXP-01, TD-03, h16, h29 | loses | [Animal Spirits](https://www.cfr-cologne.de/download/workingpaper/cfr-25-09.pdf) |
| 33 | Time-of-day, calendar, pre-expiry | h28, h41, h42 | intraday drift negative | [SEBI](https://www.sebi.gov.in) studies |
| 34 | Dealer gamma (GEX) | h18 | predicts size, not direction | ADVANCED.md |
| 35 | All candlestick / chart patterns, 25 indicators, Bollinger/TTM squeeze, Ichimoku | h25 (215,280 versions) | equal to random | h25 |
| 36 | Fibonacci, Gann, Elliott, harmonics, Renko, Market Profile / volume-profile POC, Wyckoff | h37 | equal to random | h37 |
| 37 | Round numbers, app S/R levels | h35 | lose | h35 |
| 38 | Option-premium chart signals (premium VWAP/EMA) | h30 | worse than random | h30 |
| 39 | Global cues / GIFT / news | h27, h33 | priced into the gap | h27 |
| 40 | Heavyweight stock options lead, relative value across indices | h39, h6 | no tradable lead | h39 |

**Genuinely new, tested above (15):** N1-N15.

**Not testable or not allowed for a buy-only index trader (4):**
- End-of-day reversal in single stocks ([Baltussen, Da, Soebhag 2025](https://www3.nd.edu/~zda/EOD.pdf)): a
  cross-section long/short of stocks.
- Delta-neutral / gamma scalping: needs a futures hedge or option selling.
- The weekend effect in options ([Jones & Shemesh 2018](https://ideas.repec.org/a/bla/jfinan/v73y2018i2p861-900.html)):
  it is a reason *not* to hold long options over weekends, not a buy signal. It agrees with h31 and SWING_DEEP.
- 0DTE retail-loss studies ([Frankfurt / SSRN summaries](https://medium.com/@y125789/retail-traders-love-0dte-options-paper-summary-6da4a7ff7d72),
  [SEBI FY26](https://www.moneylife.in/article/92-percentage-of-aggregate-losses-incurred-by-individuals-are-from-options-trading-sebi-study/81429.html)):
  these are evidence, not strategies. They say 0DTE buyers lose on average, which matches everything here.

## Why the internet ideas fail for a buyer (what the numbers show)

- **Costs and decay eat the small directional edges.** A random 1-ITM trade loses Rs 100-300 at 1 lot, depending on
  hold time. Several ideas had a positive *gross* trade (VIX spike +369, VIX divergence +354, noise area +128, fair
  value gap +101 a trade at the median variant), but only a few survive costs, and none survives the count of tries.
- **US papers do not carry over.**
  - SPY noise-area momentum, Baltussen's last-30-minute momentum and the HKS same-slot persistence all lose on NIFTY
    and BANKNIFTY options.
  - HKS is the worst idea of all (-Rs 550 to -950/day). Following each half-hour's recent average sign is pure noise
    and turnover.
- **"Buy options after noon" (the asymmetry papers) loses about Rs 130-380/day.** The papers measure delta-hedged
  returns before costs. A naked 1-ITM buyer at 12:00-13:00 just pays theta and the spread.
- **Popular Indian rules** (first-two-candles, midday box, lunch reversal, turtle soup on PDL/PDH) lose in design.
  None has a published backtest; the sources are blogs and broker guides.

## Caveats

- The breadth idea (N15) has only about 1 year of design data (stock minutes start Oct 2024).
- The VIX-minute ideas start in 2022.
- India VIX is computed from option prices, so N13 partly reads the option market itself. This is legitimate: it
  uses data available at the decision minute.
- Holding is intraday only. Overnight versions of IBS and VIX spike map onto the BTST study (h31), which was weak and
  decaying.
- The random twins use the same exit type. Index-based exits are replaced by the same holding time.

## Paper-arm suggestion (optional, not money)

**N13 VIX/index divergence, BANKNIFTY and NIFTY, 1 lot, 1-ITM nearest expiry.**
- **Rule:** check at 10:30, 11:30, 12:30 and 13:30. If the index is up more than 0.2% from its 09:15 open AND India
  VIX is up more than 2% from its open, buy a PE. If the index is down more than 0.2% AND VIX is down more than 2%,
  buy a CE. First signal of the day only.
- **Exit:** BANKNIFTY holds to 15:10 (EOD). NIFTY uses the Liquidity exit (-15% stop, out after 20 min unless +5%).
- **Expect:** about 30-45 signals a year and Rs 70-110/day on average if it holds. It does not pass the correction.
  Log it with the bid/ask at each signal, and drop it if 150 paper trades are not positive.
