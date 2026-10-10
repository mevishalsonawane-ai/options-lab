# HUNT R10: the ten "order-flow" tools: what they are, what we can test, and what to record

Written 9 Oct 2026 by R10 for Boss. Frame: option BUYING only, Rs 1,00,000, 1 lot fixed, exits on the option's 1-minute
wicks, real costs including the h24 half-spreads, random twins, BH + White reality check, locked holdout
(1 Oct 2025 - 6 Oct 2026) opened once after a written PREREG.

- Code: `research/hunt/r10/` (`build.py` panels, `feats.py` VWAP + Market-Profile features, `validate.py` proxy check,
  `eval.py` filters, `PREREG.md` written before any filter P&L was seen).
- Outputs: `scratchpad/hunt/r10/` (`panel_<U>.npz`, `validate.csv`, `pre.csv/.log`, `hold.csv/.log`, `HOLD_OPENED`). 25 MB.
- Not repeated: volume profile / HVN, value-area auction regime, GEX / zero-gamma (R9); order flow and traps (R7, R8);
  VWAP as a stand-alone signal (catalog TI-03/04/05, MR-05/06, h46, R1); TPO levels, IB break, 80% rule (h37).
- Nothing was committed.

## The answer in plain English

1. **Of the ten tools, only two can be tested on our history: VWAP and Market Profile day structure.** The other
   five that matter (heatmap, time & sales, footprint, delta, true order flow) need tick or depth history. No free tick
   history exists for Indian index options or futures, so we must record it ourselves. The app already records most of it.
2. **VWAP as a regime filter on our arms: NO.** We tested 7 VWAP rules (side of VWAP, the 1 and 2 SD bands, VWAP slope,
   prior-day VWAP, a VWAP anchored at the prior day's 15:00 closing window) on the six arms. None helps Liquidity
   BANKNIFTY in both periods. Only one rule passed the pre-registered test: **"Range Fade: skip when the index is beyond
   +-2 SD of VWAP"** (+Rs 25/day before the holdout, +Rs 57/day in it). Range Fade still loses about Rs 309/day after
   the filter, so it stays off. The rule is a paper note only.
3. **Market Profile day structure: the folk rule is backwards for an option buyer.** Narrow-IB days are labelled
   "trend days" more often, but only because "trend" is measured in multiples of the IB. In rupees of movement, a
   narrow IB is followed by a **smaller** rest of the day: 0.34x a typical day's range against 0.49-0.54x after a wide
   IB. This held before and in the holdout. The opening type (open-drive etc.) says which way the rest of the day goes
   51-55% of the time, a coin flip. As filters, narrow-IB rules cut the ORB arms' losses mostly by cutting trades, and
   none passed BH. ORB stays a loser.
4. **GEX in India: the sign is a guess.** R9 is testing it. The internet adds one important point: SEBI finds that
   about 97% of individual F&O traders mostly BUY options. So the people short gamma are mainly prop desks and
   brokers, not "dealers long calls / short puts" as US GEX tools assume. A US-style GEX chart on NIFTY may have the
   wrong sign.
5. **What to record (section 4):** 1-second futures order-flow bars, a per-minute price-level footprint, a 1-second
   5-level book for the BANKNIFTY future, and the 5-level book of the traded option at every signal. Check after
   **4 weeks (20 sessions)** whether the flow's 1-5 minute lead beats the 2 bp cost hurdle. A P&L test on Liquidity
   cannot be read in weeks: it needs about 1,700 trades.

## 1. The ten tools in one page

"Available" means for a retail Indian trader. Live depth sources (R7 checked prices and limits): Kite full mode, 5
levels (Rs 500/month, already in the app). Dhan, 5 levels, plus 20 and 200 levels for NSE F&O only (200 = 1
instrument per socket). Fyers TBT, 50 levels, true tick-by-tick, NFO only, free with an account. NSE's own TBT and
depth history is institutional-priced (about Rs 4 lakh a year per segment, R7). **History: brokers keep 1-minute
candles only. None keeps depth or trade-by-trade history.** Vendors (TrueData, GlobalDataFeeds) sell trade and
best-quote ticks, not full depth, by quote.

| tool | what it is (plain English) | data it needs | live, retail India | history, retail India | evidence | known traps |
|---|---|---|---|---|---|---|
| **Heatmap** (Bookmap-style) | A picture of the resting orders at every price over time: bright bands are big waiting orders | Full depth (all price levels), every update | 5 levels (Kite / Dhan), 20 / 200 (Dhan, NSE F&O), 50 TBT (Fyers) | none (record it) | No peer-reviewed test that reading a heatmap pays. Vendor material only | Most visible size is fleeting: in R8, half of all added size was cancelled within 3-5 s and "walls" lived about 1 s. SEBI spoofing cases (Nimi 2023, Patel Wealth 2025). 5 levels see only 2-15% of the book |
| **Time & sales** (the tape) | Every trade printed: time, price, size, and whether it hit the bid or lifted the offer | Trade-by-trade prints, with aggressor side | Kite / Dhan give last trade + cumulative volume per packet. Many trades are merged; side must be inferred (tick rule). Fyers TBT is closest to the true tape | none (vendors sell trade ticks for some segments) | Trade-sign research (Lee-Ready, Kolm et al.): sign predicts seconds ahead, fading fast | The feed is 1-2 s late at the median, sometimes 5-50 s (R8). Packets merge trades, so "big prints" are often many small ones |
| **Footprint** | Each candle split into price rows showing buy vs sell volume at each price | Trades with aggressor side, by price | Built live from the tick stream (approximate: tick-rule side) | none | Practitioner only. No peer-reviewed out-of-sample test found | Side classification error. "Imbalance" stacks fire constantly. Candle-close re-sends (R8) |
| **Delta / cumulative delta** | Buy volume minus sell volume per bar, and its running total for the day | Same as footprint | Same (the app computes CVD by the tick rule) | none. A crude proxy from 1-minute OHLCV only | Close cousin of OFI. Strong same-time link, weak forward link | "Divergences" are mostly noise. Proxies from candle shape are not delta |
| **Order flow (OFI, queue imbalance)** | Who is pushing: changes at the best bid/offer (Cont-Kukanov-Stoikov) | Best bid/ask price and size, each update | Yes, all brokers (5 levels) | none | **Peer-reviewed:** OFI explains same-interval price change linearly (Cont et al. 2014). The forward effect decays within seconds to a minute (R7). The app's option flow leads by IC 0.11 at 1 min but is worth 0.42x of cost (R7) | Repeats, stale packets, spoofed size (R8) |
| **VWAP** | Average price of the day weighted by volume. Institutions benchmark fills to it | Price and volume per minute | Futures: yes. The index itself has no volume | Futures minute data only for LIVE contracts (Dhan). Expired futures minutes: no (h38). **We used an option-volume proxy (89% sign agreement with real futures VWAP)** | Zarattini-Aziz (practitioner): QQQ above/below VWAP. An independent QuantConnect replication says it fades out of sample and costs kill it. Ours: catalog / h46 / R1 losers. **This study: no filter value** | NSE closing price = VWAP of the last 30 min (since 3 Aug 2026, 15:10-15:40 for F&O), and the settlement rule is under SEBI review. That window is mechanical flow. Bands assume normal prices |
| **Gamma exposure (GEX)** | An estimate of how much option sellers must buy or sell the underlying as it moves. "Positive" damps moves, "negative" amplifies them | Option OI by strike + greeks + **who is short** | Chain OI: yes (every 3 min in NSE data) | Daily OI (bhavcopy), our 1-minute OI (Dhan rolling) | Mostly US vendor work (SpotGamma etc.). Even they say the dealer sign is an assumption | **India: 97% of individuals mostly buy options (SEBI), so the short side is props / FPIs. The US sign convention may be wrong here.** OI is yesterday's. Expiry pinning is real only near expiry. R9 tests it |
| **Auction market theory** | Price moves to find buyers and sellers. "Balance" (range) alternates with "imbalance" (trend). Value = where most trade happens | Price + volume / time at price | Yes (concept) | yes (from minutes) | Theory (Steidlmayer, Dalton). Little formal testing. Our tests of its rules: h37 levels lost. **This study: day-type rules do not help** | Labels are hindsight. Day types are defined in IB multiples (mechanical, see 2.3) |
| **Volume profile** | Volume traded at each price over a period: POC = most traded price, value area = 70% | Volume by price (ticks, or minute volume spread over the bar) | Futures yes. Index no | Futures minutes only for live contracts. h37 used constituent volume (from Oct 2024) | h37: POC / value-area levels = random. **R9 is testing HVN / value-area regime** | Index has no volume. Minute-bar volume spread over the bar is a guess |
| **Market profile (TPO)** | Same idea as volume profile but counts TIME at each price (30-minute letters). Initial balance (IB) = first hour; opening types (open-drive, open-test-drive, open-rejection-reverse, open-auction) | Price only (minutes are enough) | yes | **yes: fully testable** | No published backtest with conditional probabilities by IB width or opening type (searched). h37: TPO levels = random. **This study: see section 2** | "Narrow IB = trend day" is partly a definition. Opening types are subjective (we used fixed numeric rules). Index cash opens with a pre-open auction gap |

## 2. Test results (history)

### 2.1 Data and method

- **VWAP without futures history.** Expired index futures minutes cannot be fetched (h38). Weights = the nearest
  expiry's option volume per minute (CE+PE, ATM+-10), applied to the index typical price. Checked against the real
  near-month futures VWAP on the days where futures minutes exist (Jul-Oct 2026):

  | index | days | sign of (price - VWAP) agrees | z-score correlation | +-2 SD state agrees |
  |---|---|---|---|---|
  | BANKNIFTY | 47 | 89% | 0.86 | 93% |
  | NIFTY | 42 | 89% | 0.86 | 91% |
  | MIDCPNIFTY | 44 | 82% | 0.67 | 83% |
  | SENSEX | 43 | 80% | 0.59 | 88% |

  An equal-weight TWAP was almost as close (89% / 0.85 on BANKNIFTY). Intraday, VWAP is mostly "the day's average
  price". The weighting matters little.
- **Trades are not re-simulated.** Filters only choose rows of validated trade lists: h19 ORB, ORB Fresh, ORB Sweep,
  Range Fade (BANKNIFTY, 1 lot, minus the h24 spread), and h44 Liquidity 15+5 (BANKNIFTY; and NIFTY / FIN / MIDCP /
  SENSEX pooled), all net of charges and spread.
- Every feature uses only bars closed before the entry minute. IB rules apply only after 10:15 and opening-type rules
  only after 09:45; earlier trades are kept.
- Delta/day = the money saved per trading day by skipping (positive = the filter helps). Random twin = 5,000 random
  skips of the same number of trades. BH over 75 design tests. White reality check over the whole family.
  PASS = design Delta > 0 with BH q <= 0.10, AND holdout Delta > 0 with twin p <= 0.10.

### 2.2 Filter results (Rs per day at 1 lot; design = Aug 2021 - Sep 2025, about 996 days; holdout = Oct 2025 - Oct 2026, 248 days)

Arm baselines, net per day: ORB -1,035 / -1,570 (design / holdout), ORB Fresh -275 / -325, ORB Sweep -236 / -430,
Range Fade -240 / -366, **Liquidity BN +66 / +168**, Liquidity other four -22 / -67.

**VWAP family** (Delta/day design -> holdout; twin p design / holdout; BH q design):

| filter (trend arms; Range Fade version in PREREG) | ORB | ORB Fresh | ORB Sweep | Range Fade | **Liq BN** | Liq other |
|---|---|---|---|---|---|---|
| V1 trade on the VWAP side of the move | +9 -> -7 | +7 -> -7 | +234 -> +388 (keeps 9%) | +12 -> -6 | **-26 -> +17** | +29 -> -37 |
| V2 not stretched beyond +2 SD in the trade's direction (RF: within +-1 SD) | +167 -> +224 (p .41/.82) | +62 -> +8 | 0 | +118 -> +201 (p .43/.41) | **-18 -> -125** | -15 -> -95 |
| V3 skip beyond +-2 SD | same as V2 | same as V2 | +15 -> +53 | **+25 -> +57 (p .001/.039, q .075) PASS** | **-17 -> -115** | -7 -> -90 |
| V4 VWAP slope (15 min) agrees | +11 -> +10 | +13 -> +2 | +237 -> +428 (keeps 3%) | n/a | **-22 -> +4** | -7 -> -33 |
| V5 side of prior-day VWAP | +180 -> +297 (p .84/.62) | +79 -> +87 | +161 -> +237 | +71 -> +132 | **+5 -> -33** | -5 -> +43 |
| V6 side of VWAP anchored at prior day 15:00 | +31 -> +34 | +21 -> +2 (p .015/.67) | +213 -> +350 | +21 -> +9 | **+1 -> +2** | +3 -> +5 |
| V7 V1 and V2 | +176 -> +217 | +68 -> +1 | +234 -> +388 | n/a | **-44 -> -108** | +14 -> -132 |

**Market-Profile day structure:**

| filter | ORB | ORB Fresh | ORB Sweep | Range Fade | **Liq BN** | Liq other |
|---|---|---|---|---|---|---|
| T1 skip if the opening type points against the trade | +163 -> +196 | +36 -> -17 | +108 -> +198 | +110 -> +166 | **+15 -> -50** | +37 -> +44 (p .04/.33) |
| T2 skip open-auction days (RF: only those) | +404 -> +708 (p .49/.12) | +125 -> +163 | +70 -> +143 | +151 -> +223 | **-16 -> -113** | +14 -> -65 |
| T3 narrow IB only (< 1.0x 20-day avg; RF: wide only) | +438 -> +765 (p .12/.09, q .61) | +80 -> +200 (p .79/.01) | +64 -> +98 | +161 -> +240 (p .03/.07, q .32) | **-29 -> +76** | -9 -> -6 |
| T4 skip wide IB (> 1.3x) | +238 -> +357 (p .007/.42, q .20) | +37 -> +39 | +31 -> +82 | n/a | **-34 -> +45** | -32 -> +86 |
| T5 skip if the IB already broke against the trade (RF: only before any IB break) | +87 -> +100 | +25 -> -8 | +166 -> +355 | +158 -> +221 | **+10 -> -218** | +31 -> -201 |
| T6 narrow first-30-min range only (RF: wide only) | +436 -> +773 (p .03/.06, q .32) | +103 -> +200 (p .55/.02) | +87 -> +187 | +146 -> +200 | **-25 -> +65** | -11 -> -11 |

Reading the tables:

- **Big positive numbers on the losing arms mostly mean "fewer trades".** Any filter that skips 40% of ORB trades
  saves about 40% of ORB's loss. The random-twin p asks whether the filter beats skipping the SAME number of trades at
  random. Almost none does in both periods.
- **One pass: Range Fade V3.** 70 of 1,459 design trades and 29 of 376 holdout trades were beyond +-2 SD. They lost
  Rs 353 and Rs 485 per trade, against Rs 154 and Rs 220 for the rest. Range Fade still loses Rs 215 / 309 per day
  after the filter.
- **Liquidity BN, the only arm that pays:** no VWAP or TPO filter is positive in both periods. The stretched-skip rules
  (V2, V3, V7, T2) **hurt it in the holdout by Rs 108-125/day**: Liquidity's best trades come when the index is
  already stretched. Do not put a VWAP-band filter on Liquidity.
- **Closest near-miss: narrow IB or narrow first-30-min range on ORB / ORB Fresh** (T3, T6). Positive in both
  periods, twin p 0.01-0.09 in the holdout, but BH q 0.32-0.79 in design. Even the kept trades lose Rs 105-183 each in
  the holdout. It does not rescue ORB.
- White reality check across all 75 design filters: max z 2.88, **p = 0.11**. As a family, nothing beats luck.

### 2.3 Do IB width and opening type predict the day? (description, no P&L)

All five indices pooled (design 4,435 index-days; holdout 1,229):

| IB / 20-day average IB | "trend day" (range >= 2x IB, close in the outer 20%) design / holdout | range day (< 1.3x IB) | **rest-of-day move after 10:14, in typical day ranges** design / holdout | whole-day range vs typical |
|---|---|---|---|---|
| < 0.75 (narrow) | 27% / 21% | 14% / 15% | **0.34 / 0.35** | 0.75 |
| 0.75 - 1.0 | 20% / 22% | 24% / 18% | 0.40 / 0.44 | 0.93 |
| 1.0 - 1.3 | 16% / 13% | 28% / 29% | 0.47 / 0.46 | 1.11 |
| > 1.3 (wide) | 9% / 7% | 44% / 42% | **0.54 / 0.49** | 1.45 |

- **The "narrow IB = trend day" rule is true only by its own yardstick.** Trend is measured in multiples of the IB, so
  a small IB is easy to double. In money terms a narrow first hour comes before a **quieter** rest of the day. Volatility
  clusters: a wide first hour means a busy day. The pattern is significant (Kruskal p < 1e-27 design, 2e-4 holdout).
- **An option buyer needs absolute moves, so a narrow IB is a reason for LESS appetite, not more.** It is also not a
  usable filter (T3 / T6 above).
- **Opening type** (fixed rules: OD, OTD, ORR, OA from the first 30 minutes). Open-drive days have bigger ranges (1.1x
  typical) but more "range day" labels. **Direction hit rate for the rest of the day: OD 51% / 54%, OTD 52% / 55%,
  ORR 54% / 45%** (pooled, design / holdout). BANKNIFTY OD was 48% in design and 62% in the holdout on only 47 days.
  That is a coin flip.

## 3. GEX (R9 is testing it): what the internet says about India

- GEX multiplies each strike's open interest by its gamma and lot size, then adds up with a SIGN for who is short.
  US vendors assume "dealers are long calls, short puts". Even they say public OI cannot show who holds the short side.
- **In India the sign is uncertain and probably different.** SEBI's FY25-26 study: about 97% of individual F&O traders
  mostly BUY options, and 92% of individual losses come from options. The writers are mostly proprietary traders and
  FPIs, who short both calls and puts, so the net gamma sign is not the US convention.
- OI is a snapshot. Our 1-minute OI exists (Dhan rolling options), but the participant split exists only daily (NSE
  participant-wise OI: client / FII / DII / pro), and only in total across all strikes.
- Weekly expiry: gamma is huge on expiry day near ATM, and pinning to a big-OI strike is the only GEX effect with a
  plausible mechanism. h26 found OI "walls" add nothing tradable for a buyer.

## 4. What the app must record, and the exact tests to run after 4 weeks

The app already has most of this: `OrderFlow.kt` (1-s OFI, depth and queue imbalance, tick-rule CVD, option net flow),
`FlowShadow.kt` (an `S|...` line at every signal with the flow read, the traded option's 5-level book and trap flags,
plus `M|` 15/30-min moves and `R|` results), and `MarketRecord.kt` (minute futures bars `B`, book `D`, chain `C`).
**What is missing is the raw per-second and per-price record**, so that any later idea can be tested. Today only the
rolled-up read is saved at signals.

### 4.1 Add these records (NSE: the BANKNIFTY and NIFTY near-month futures; plus ATM+-2 options of BANKNIFTY at 5 s)

| record | contents (one line each) | why | size |
|---|---|---|---|
| `Q` 1-second order-flow bar (future) | second; last; high/low of trades; volume in the second; buy volume and sell volume (tick rule) and the quote-rule split; trade count proxy (packets with volume change); OFI (best level); best bid/ask price and size; sum of 5 bid sizes and 5 ask sizes; Kite total buy/sell qty; OI; packets received, repeats dropped, stale dropped; feed lag (exchange timestamp vs phone clock) | time & sales, delta, OFI, feed health | about 22,500 lines/day per instrument, about 1.5 MB/day compressed |
| `K` 1-second book (future) | second; 5 bid prices + sizes; 5 ask prices + sizes (from the last packet in that second) | heatmap (5-level), wall life, pulls | about 2 MB/day |
| `FP` footprint, per minute (future) | minute; for each price tick traded: buy volume, sell volume (tick rule) | footprint, delta at price, POC per bar | about 0.5 MB/day |
| `S|` at each signal (exists) | keep; add the signal's VWAP-proxy z (option-volume weighted, as in r10) and the IB / opening-type state | lets the r10 filters be checked live | negligible |
| Optional: Dhan 20-level (BANKNIFTY future + ATM+-2) or Fyers 50-level TBT | the same `K` line with 20 / 50 levels | deeper heatmap; tests whether deeper levels add anything | about 8-20 MB/day; only if disk allows |

Rules for all lines: phone timestamp to the millisecond plus the exchange timestamp. Mark every gap (`G` lines).
Never store tokens. Record 09:00-15:45 (F&O trades to 15:40 since 3 Aug 2026).

### 4.2 Tests to run after 20 sessions (pre-register before looking; the last 5 sessions as the locked check)

| # | question | test | sample needed | can 20 sessions answer it? |
|---|---|---|---|---|
| 1 | Does 1-s order flow (OFI, delta, depth imbalance) lead the future? | Information coefficient (IC) of each 10 s / 60 s / 300 s feature with the next 1, 5 and 15 minutes' futures return; non-overlapping minutes; Newey-West t | IC 0.05 needs about 3,100 independent minutes (80% power): **about 9-10 sessions**; 20 for safety | **Yes** |
| 2 | Is the lead big enough to pay? | Mean next-5-min futures move in the top and bottom deciles of each feature, against the hurdle of **2 bp of the index** (BANKNIFTY ATM round trip, R7) | about 750 minutes per decile in 20 sessions; standard error about 0.3 bp | **Yes** (the key go / no-go) |
| 3 | Do heatmap walls hold price? | For walls (>= 2x median level size, alive >= 3 s): share of times price touches and reverses >= 5 ticks vs touches and breaks; compare with random price levels at the same distance | about 300 touches | Yes for NSE futures (R8 saw 11,700 walls in 34 min of MCX) |
| 4 | Does footprint "absorption" or "stacked imbalance" predict the next 5 minutes? | Event study; same placebo as 3 | about 200 events per type | Probably |
| 5 | Does delta divergence (new high with falling CVD) predict reversal? | Event study vs matched non-divergent highs | about 150 events | Borderline |
| 6 | Does the flow filter improve the arms' P&L? (`FlowShadow` CONFIRM vs SHADOW) | Kept vs skipped net with random twins (as r10) | Liquidity BN: trade SD about Rs 2,060. To see a Rs 300/trade gap with 30% skipped needs about **1,700 trades (about 9 years at 0.76 a day)**. On 15/30-min **moves** (`M|` lines, all signals including not taken, about 12 a day across arms): about 1,800 signals for a 2 bp gap, **about 6-7 months** | **No.** Weeks can only answer 1-2; P&L stays on paper |

Decision rule: go further only if test 2 shows a top-decile next-5-minute move above 2 bp, AFTER the phone's measured
lag, in both the first 15 and the last 5 sessions. Otherwise order flow stays a display, as R7 and R8 concluded.

## 5. Paper filter (the one pass)

**Range Fade (paper only; the arm stays off):** skip an entry when the BANKNIFTY index is more than 2 standard
deviations from its session VWAP at the bar closed before entry. Compute VWAP as the volume-weighted (h+l+c)/3 from
09:15. Volume = the near-month future's (live), or the nearest-expiry option volume as in r10. The SD is the
volume-weighted SD of the typical price around VWAP. Expect about 1 skip in 13 Range Fade trades. Range Fade still
loses money after it.

Nothing else is adopted. In particular: **no VWAP-band or IB filter on Liquidity BANKNIFTY**. The stretched-skip
versions cost it Rs 108-125/day in the locked year.

## Sources

- R7 / R8 (data sources, prices, traps): `research/HUNT_R7_ORDER_FLOW.md`, `research/HUNT_R8_ORDER_FLOW_TRAPS.md`.
- [Dhan full market depth (20/200 levels, NSE only)](https://dhanhq.co/docs/v2/full-market-depth/), [Dhan 20-depth](https://dhanhq.co/docs/v2/20-market-depth),
  [Fyers TBT guide (marketcalls)](https://www.marketcalls.in/python/a-simple-guide-to-using-fyers-tbt-feed-via-websocket-with-protobuf-python-tutorial.html),
  [Fyers 50 depth](https://www.marketcalls.in/fintech/unveiling-fyers-50-market-depth-the-next-level-of-market-transparency.html),
  [historical depth data (TradingQnA)](https://tradingqna.com/t/historical-market-depth-data/17573).
- [Cont, Kukanov, Stoikov, The price impact of order book events (J. Fin. Econometrics 2014)](https://arxiv.org/abs/1011.6402);
  [ATAS footprint and delta](https://learn.atas.net/volume-basics/volume-analysis/reading-footprint-delta).
- [Bookmap on spoofing](https://bookmap.com/blog/what-is-spoofing-in-trading), [Bookmap heatmap guide](https://bookmap.com/es/blog/heatmap-in-trading-the-complete-guide-to-market-depth-visualization/),
  [SpotGamma: What is Bookmap](https://support.spotgamma.com/hc/en-us/articles/15246378276115-What-is-Bookmap).
- [Zarattini and Aziz, VWAP for day trading](https://concretumgroup.com/volume-weighted-average-price-vwap-the-holy-grail-for-day-trading-systems/),
  [QuantConnect replication](https://www.quantconnect.com/terminal/cache/embedded_backtest_b115d0894231d55994b1068202f6c0ae.html).
- NSE close / CAS: [5paisa on CAS from 3 Aug](https://www.5paisa.com/blog/closing-auction-session-august-3-new-rules),
  [SEBI circular Jan 2026](https://www.sebi.gov.in/sebi_data/attachdocs/jan-2026/1768576287344.pdf),
  [SEBI proposal on settlement prices, 12 Sep 2026](https://newsonair.gov.in/sebi-proposes-changes-to-methodology-for-calculating-expiry-day-settlement-prices-of-index-and-stock-derivatives/),
  [Kotak Neo on 15:40 close](https://www.kotakneo.com/news/trading/nse-derivatives-trading-extended-3-40-pm-august-2026-closing-auction/).
- Initial balance / market profile: [LuxAlgo IB](https://www.luxalgo.com/library/concept/initial-balance/), [ATAS IB](https://atas.net/blog/initial-balance-indicator-how-to-use-initial-balance/),
  [IB strategy](https://daytradingtoolkit.com/strategies/initial-balance-trading-strategy) (no published conditional statistics found).
- GEX: [SpotGamma GEX](https://spotgamma.com/gamma-exposure-gex/), [LuxAlgo GEX](https://www.luxalgo.com/library/concept/gamma-exposure.md),
  [FlashAlpha dealer positioning](https://flashalpha.com/articles/dealer-positioning-gex-quantitative-approach-options-flow), [StockMojo (India)](https://stockmojo.in/learn/gamma-exposure-explained/);
  SEBI F&O studies: [Business Standard FY25](https://www.business-standard.com/markets/news/net-losses-of-traders-in-fo-widens-in-fy25-sebi-study-125070701221_1.html),
  [Open magazine FY26 (97% option buyers)](https://openthemagazine.com/business/sebi-fo-loss-study-explained-why-9-in-10-retail-traders-lost-91685-crore-in-fy26).
