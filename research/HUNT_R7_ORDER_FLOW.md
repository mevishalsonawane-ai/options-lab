# HUNT R7: can order flow tell us the direction? Where to get it, what it costs, what to build

Written 9 Oct 2026 by R7 for Boss. Frame: option BUYING only, Rs 1,00,000, 1 lot fixed, NSE index options and MCX.

- Code: `research/hunt/r7/` (`record_dhan_feed.py` recorder, `parse_feed.py` decoder, `analyze_flow.py` features and lead test).
- Data: `scratchpad/hunt/r7/` (`feed_20261009.bin` raw frames, `ticks.parquet`, `leadlag.csv`, `rec.log`, `analyze.log`).
- Nothing was committed. The other MCX recorder (`m3/snap_mcx.py`, `quote_fut.py`) was not touched.

## The answer in plain English

**Can order flow give us direction?** Yes, but only for a very short time, and mostly not enough to pay an option buyer.

- Order flow means who is pushing: market orders that hit the ask (buyers in a hurry) versus market orders that hit the
  bid (sellers in a hurry), and how the queues of waiting orders change.
- Research everywhere agrees that **order flow explains the price move in the same seconds very well** (often 50-80% of
  it). This is close to a definition: price moves because someone pushed it.
- **Order flow forecasts the NEXT move only a little, and only for seconds to a minute or two.** The forecast fades
  after about two price changes in US stocks (Kolm et al.). The lagged effect "decays rapidly" (Cont et al. 2023).
- **Our own app already found this.** The signed option flow (calls bought minus puts bought, per minute) is the only
  option signal that LEADS the index: IC +0.109 at 1 minute, +0.049 at 5, +0.034 at 15. But its edge is **0.42x of the
  cost** of an option trade (`options_lab/features/flow.py`, `docs/design.md`). It points the right way; it does not pay.
- **The hurdle is high for a buyer.** A BANKNIFTY ATM option round trip costs about 0.7% of premium (0.32% spread plus
  ~0.38% charges). That equals about **2 bp of the index**. On MCX crude/gas options it is about **5 bp of the future**.
  An order-flow signal must predict a move bigger than that, AFTER our phone's delay, to make money.
- **Best realistic use: a filter, not a signal.** Skip a Liquidity entry when the flow of the last 1-5 minutes clearly
  disagrees with it. That can only be tested with data we must record ourselves, because **no free history of depth
  or order flow exists for Indian index options**.

**What does it cost to get?**

- **Live, 5 levels: free with what we already pay.** Zerodha Kite (Rs 500/month data plan, already used by the app)
  full mode gives 5 bids and 5 offers, total buy and sell quantity, last traded quantity and time, volume and OI.
  Dhan (Rs 499/month data plan, already used for research) gives the same, and **20 and 200 levels for NSE F&O
  (not MCX)**.
- **History: not free.** Brokers keep only 1-minute candles. Vendors (TrueData, GlobalDataFeeds) sell tick history for
  roughly Rs 1,500-3,000/month per segment, but mostly trades and best bid/ask, not full depth. NSE's own 20-depth
  end-of-day order files cost **Rs 4,00,000 a year per segment** (institutional). So: **record it ourselves.**

**What should we build?** (details in section 6)

1. In the app's existing Zerodha stream (already in full mode), compute five numbers per second for the index future
   and the ATM +/-2 options: order-flow imbalance (OFI), 5-level depth imbalance, signed volume, total buy/sell
   quantity imbalance, and the spread. Save one line per minute (sums and last values) to the market recorder.
2. Record every trading day for **at least 8-12 weeks** (40-60 sessions) before testing anything, then test once on
   a locked later block.
3. Test it as a **paper-only filter** on the existing arms: "Liquidity enters only when the last 5 minutes' OFI agrees".

## 1. Data sources (checked October 2026)

| source | live / history | depth levels | segments | cost | limits and notes |
|---|---|---|---|---|---|
| **Zerodha Kite Connect** WebSocket, `full` mode | live only | 5 + total buy/sell qty, LTQ, last trade time, OI | NSE, BSE, NFO, MCX, CDS | Rs 500/month (data plan, 2025 price cut) | 3,000 instruments per socket, 3 sockets per key; event-driven snapshots, not every trade ([docs](https://kite.trade/docs/connect/v3/websocket/), [price](https://kite.trade/forum/discussion/comment/49592/), [snapshot note](https://kite.trade/forum/discussion/14326/developing-a-chatviewing-platform)). **The app already subscribes in full mode** (`KiteStream.kt` line ~434). History: 1-minute candles only, no depth. |
| **Dhan v2 live market feed**, `Full` packet (RequestCode 21) | live only | 5 + total buy/sell qty, LTQ, LTT, OI | NSE, BSE, NFO, **MCX** | Rs 499/month + GST (Data API) | 5 sockets x 5,000 instruments, 100 per subscribe message ([docs](https://dhanhq.co/docs/v2/live-market-feed/), [price](https://dhan.co/support/platforms/dhanhq-api/is-the-dhanhq-data-api-subscription-free-if-i-execute-a-minimum-number-of-trades-every-month/)). Proven tonight on MCX (section 4). |
| **Dhan 20-level depth** WebSocket | live only | 20 | **NSE EQ and NSE F&O only** | same plan | 50 instruments per socket ([docs](https://dhanhq.co/docs/v2/full-market-depth/)). Not MCX. |
| **Dhan 200-level depth** WebSocket | live only | 200 | NSE EQ and F&O only | same plan | **1 instrument per socket** (good for one BANKNIFTY future). |
| **Upstox v3** feed (`full` / `full_d30`) | live only | 5 / 30 | NSE, BSE, MCX | APIs free; 30 levels need Upstox Plus | Protobuf; community reports of `full_d30` returning only 5 levels and a 50-instrument cap ([docs](https://upstox.com/developer/api-documentation/v3/get-market-data-feed), [forum](https://community.upstox.com/t/market-feed-api/11899)). The app's Paper mode already uses Upstox. |
| **Fyers API v3 TBT** | live only | **50**, true tick-by-tick diffs | **NFO only** | free with account | Python SDK depth-diff bug reported Sept 2026 ([marketcalls guide](https://www.marketcalls.in/python/a-simple-guide-to-using-fyers-tbt-feed-via-websocket-with-protobuf-python-tutorial.html), [bug](https://fyers.in/community/t/tbt-50-level-depth-bug-in-fyers-apiv3-depth-adddepth-ignores-num/24470)). Best free depth for NSE F&O if Boss opens a Fyers account. |
| **Angel One SmartAPI** WebSocket 2.0 | live only | 5 (20-depth **withdrawn 25 Apr 2025**) | NSE, NFO, MCX | free | ([notice](https://smartapi.angelone.in/smartapi/forum/post/18147)) |
| **ICICI Breeze**, **Kotak Neo** | live only | 5 (Breeze `get_market_depth` flag; Neo depth in feed) | NSE, NFO (+MCX on Neo) | free with account | ([Breeze SDK](https://pypi.org/project/breeze-connect/), [Neo guide](https://www.kotakneo.com/uploads/kotak_neo_market_data_websocket_guide_38e4831feb.pdf)) |
| **TrueData** | live + tick history | 5 where available | NSE, NFO, MCX | about Rs 1,500-2,600/month per segment (older quotes; ask for a quote) | Authorised vendor; history is trades and quotes, not full depth ([site](https://www.truedata.in/market-data-apis), [tradingqna](https://tradingqna.com/t/need-reliable-tick-by-tick-data-for-indian-markets-suggestions/194950)) |
| **GlobalDataFeeds** | live + 1-min IEOD history | tick + best levels | NFO, MCX | about Rs 2,775/month NFO, Rs 2,890/month MCX live (reseller pages) | ([marketcalls](https://www.marketcalls.in/premium/globaldatafeeds/nimblentpro)) |
| **NSE Data & Analytics** | live multicast (TBT, full book) + historical trade/order snapshots | L1 / L2 (5) / L3 (20) / full TBT | all NSE | 20-depth EOD file **Rs 4,00,000/year per segment**; TBT needs a leased line | Institutional, not practical for us ([NSE](https://www.nseindia.com/nsedataandanalytics), [real-time](https://nseindia.com/market-data/real-time-data-subscription)) |
| **GoCharting / procharting** | live footprint charts | footprint, delta bars, imbalance | NSE, NFO, MCX | free tier EOD; real-time a few hundred rupees/month | Charts only, no data export; good to LOOK at flow by hand ([procharting](https://procharting.in/features/orderflow)) |
| **TradingView** volume footprint | live charts | footprint (premium plan) | depends on data plan | paid | charts only |
| **Sensibull / Opstra / NSE option chain** | live + some history | none (OI and volume change by strike) | NFO | free / paid | This is "positioning" (OI), not order flow. Our h26 tested OI flows: 1-2 bp lean, far below cost. |
| **MCX** | | | | | For MCX the only cheap routes are broker feeds (Kite, Dhan, Upstox, Angel, Neo; all 5 levels) and the vendors (TrueData, GDFL). No 20-level retail feed for MCX was found. |

**Bottom line on sources:** for what we need (live signals at retail speed, then our own history), the app's Zerodha
stream is enough. If we later want deeper books for BANKNIFTY futures, Dhan's 20/200-level feeds (already paid for)
or Fyers' free 50-level TBT feed are the upgrades. Nothing gives free historical depth.

## 2. What the research says order flow predicts

| idea | what it is | what was found | horizon | link |
|---|---|---|---|---|
| **Order-flow imbalance (OFI)**, Cont, Kukanov & Stoikov 2014 | at the best bid/ask: queue added on the bid side minus queue added on the ask side (cancels and trades included) | explains about 65% of same-interval mid-price moves (10 s), linear, slope ~ 1/depth; traded volume alone is noisier | **same interval** | [arXiv 1011.6402](https://arxiv.org/abs/1011.6402) |
| Multi-level / integrated OFI, Cont, Cucuringu & Zhang 2023 | OFI over the top levels, combined | better same-interval fit; **lagged OFI helps forecast only at short horizons and decays rapidly** | seconds to a minute | [arXiv 2112.13213](https://arxiv.org/abs/2112.13213) |
| Deep OFI, Kolm, Turiel & Westray 2021/23 | neural nets on OFI from 10 levels, 115 Nasdaq stocks | best inputs are OFI, not raw books; **effective forecast horizon about two average price changes** | seconds | [SSRN 3900141](https://papers.ssrn.com/abstract=3900141) |
| **Queue imbalance**, Gould & Bonart 2016 | (bid qty - ask qty) / (sum) at the best level | strongly significant predictor of the **next mid-price move**, best for large-tick stocks | next tick | [arXiv 1512.03492](https://arxiv.org/abs/1512.03492) |
| Volume imbalance, Cartea, Donnelly & Jaimungal 2018 | same, used in a trading model | predicts the sign of the next market order and the price change right after it; improves a **limit-order** (market-making) strategy by cutting adverse selection | next order | [IDEAS](https://ideas.repec.org/a/taf/apmtfi/v25y2018i1p1-35.html) |
| **VPIN**, Easley, Lopez de Prado & O'Hara 2012 | "toxicity" from volume-bucketed buy/sell imbalance | claimed to warn before the 2010 flash crash; **Andersen & Bondarenko 2014: peaked after, not before; predictive content mostly mechanical (trading intensity); sensitive to settings**. It is a volatility/toxicity gauge, not a direction signal | hours, disputed | [SSRN 1695596](https://papers.ssrn.com/abstract=1695596), [critique](https://papers.ssrn.com/abstract=2062450), [Kellogg](https://insight.kellogg.northwestern.edu/article/the_trouble_with_vpin) |
| **Option order flow -> underlying**, Easley, O'Hara & Srinivas 1998; Pan & Poteshman 2006 | signed option volume (buyer-initiated calls vs puts) | signed (not raw) option volume carries information; Pan-Poteshman: low put-call ratio of **open-buy** volume beats high by 40 bp next day, 1% next week, from private information in **single stocks**. Needs the open/close and buyer flag, which NSE does not publish. For an index, private information is rare. | days (stocks) | [NBER w10925](https://www.nber.org/papers/w10925), [EOS digest](https://rpc.cfainstitute.org/en/research/cfa-digest/1998/11/option-volume-and-stock-prices-evidence-on-where-informed-traders-trade-digest-summary) |
| Index option order imbalance, Korea (KAIST) | KOSPI200 option imbalance in the first 10 minutes | predicts the index for the rest of the day; driven by institutions, not day traders | intraday | [KAIST](https://dspace.kaist.ac.kr/handle/10203/292245) |
| **India: BANKNIFTY futures LOB**, IISc/AlgoQuant 2025 | order-book imbalance on NSE BANKNIFTY futures | unfiltered imbalance is a strong **short-horizon** directional indicator; filtering on parent orders of executed trades helps | seconds-minutes, diagnostic only (no costs) | [arXiv 2507.22712](https://arxiv.org/abs/2507.22712v2) |
| India: NSE tick data, Hawkes OFI 2024 | forecasting OFI itself | OFI is persistent and forecastable (the flow, not the return) | ticks | [arXiv 2408.03594](https://arxiv.org/abs/2408.03594v1) |
| China CSI 300 index futures 2025 | OFI and price impact | OFI memory and forecasting power depend on regime; tested 0.5 s to 30 min | 0.5 s - 30 min | [arXiv 2505.17388](https://arxiv.org/abs/2505.17388) |
| **Our own** (`options_lab/features/flow.py`) | per-minute signed option flow on the whole NIFTY/BANKNIFTY chain, from 1-minute candles (tick rule) | **leads** the index: partial IC +0.109 at 1 min, +0.049 at 5, +0.034 at 15; **edge/cost 0.42x** | 1-15 min | `docs/design.md` |
| Our h26, h29, h39 | OI and volume flows, option forward, heavyweight options | right-way leans of 1-2 bp; forward leads 1-3 min; too small to pay | minutes | `HUNT_H26.md`, `HUNT_H29.md`, `HUNT_H39.md` |

**What this means for an option buyer:**

- The clear, published edge is at **seconds and the next price change**. It is used by market makers placing limit
  orders (they earn the spread) and by colocated HFT (they react in microseconds). SEBI found 96-97% of prop and FPI
  profits come from algorithms (`MARKET_DRIVERS.md`).
- We act from a phone through Zerodha: maybe 0.3-1 s to see a tick, 0.2-1 s to send an order, and we pay the spread.
  By the time we act, the "next two price changes" are gone.
- What is left at 1-15 minutes is small: our own best flow signal is under half the cost. So we should expect order
  flow to help as a **filter** (avoid trades against strong flow), not as a stand-alone entry.
- 5-level depth is also weak against HFT: large players hide size (iceberg orders), and quotes are cancelled in
  milliseconds ("spoofing" and fleeting liquidity). Total buy/sell quantity over the whole book is especially easy to
  game and is often far from the price. Trade-based measures (signed volume) and best-level OFI are harder to fake.

## 3. Practical recipe: what to compute from Kite full-mode ticks

Per instrument, on every tick (a "tick" from Kite or Dhan is a snapshot sent when something changes, not every trade):

| feature | formula | use |
|---|---|---|
| **OFI (best level)** | e = 1[b >= b'] q_b - 1[b <= b'] q_b' - 1[a <= a'] q_a + 1[a >= a'] q_a' (primes = previous tick) | the main order-flow signal (Cont et al.) |
| **MOFI (5 levels)** | the same per level 1-5, summed (or weighted 1, 0.8, 0.6...) | more robust than the best level |
| **QI** queue imbalance | (q_b - q_a)/(q_b + q_a) at the best level | next-tick pressure |
| **DI5** depth imbalance | (sum of 5 bid qty - sum of 5 ask qty)/(total) | slower book lean |
| **TQI** total qty imbalance | (total buy qty - total sell qty)/(sum) from Kite's `buy_quantity`/`sell_quantity` | easy to read, easy to game; record but distrust |
| **Signed volume / CVD** | d(volume) since last tick x sign of the last price change (tick rule). The quote rule (LTP vs previous mid) failed on snapshot data (section 4) | aggressive buyers minus sellers; CVD = running sum |
| **Aggressor flags** | LTP >= previous ask = buy; LTP <= previous bid = sell | same, stricter |
| **Signed option flow** | over ATM +/-2: calls' signed volume minus puts' signed volume (delta-weight optional) | the app's existing leading signal, now at tick level |
| **Spread** | ask - bid, in % of mid | cost gate and data check |

Where: the **index future** (NIFTY, BANKNIFTY current month) for direction, the **ATM +/-2 options** of the traded
expiry for option flow, and for BANKNIFTY optionally the top 3-5 heavyweight stocks (HDFCBANK, ICICIBANK, SBIN, AXIS,
KOTAK) whose flow drives the index.

Sampling: aggregate to **1-second bars** in memory (sum OFI, MOFI, signed volume; last QI, DI5, TQI, spread), and write
**one row per minute** with the minute sums and the 10-s, 60-s windows at the minute's end. One minute row per
instrument for ~12 instruments is ~4,500 rows a day: tiny. Optionally keep the 1-s bars for the 30 minutes around each
paper signal.

Normalise: divide OFI by the average depth at the best level over the last 30 minutes (Cont's slope is 1/depth), so
values compare across days and expiries.

How to validate (the same discipline as the hunt):

1. Pre-register: features, windows (10 s, 60 s, 300 s), horizons (1, 3, 5, 15 min), the cost bar, the filter rule.
2. Pipe check first: same-interval correlation of OFI with the mid move must be strongly positive (0.5+). If not,
   the data or the sign is wrong.
3. Lead test: IC of past-window OFI against the NEXT 1-5 minutes' future move, with a **within-session sign-permutation
   null** and **partial IC** after the contemporaneous return, lagged return and time-of-day (as `ic.py` already does).
4. Cost bar: the top-vs-bottom quintile move must beat ~2 bp (BANKNIFTY option round trip in index terms; ~5 bp MCX).
5. Filter test on paper (section 6), then a locked later block opened once.

## 4. Feasibility sample tonight (MCX, Dhan full feed)

**The data pipe works.** Dhan's live market feed (Full packets, 5 levels) ran through the session's network proxy
with no trouble, 17:25-17:59 UTC (22:55-23:29 IST), for 14 MCX contracts: CRUDEOIL and NATURALGAS October futures and
ATM +/-1 calls and puts (15 Oct crude, 23 Oct gas expiry chains). 217,517 packets, 42 MB raw.

What the feed really is (measured, `analyze.log`):

| item | CRUDEOIL fut | NATURALGAS fut | ATM options |
|---|---|---|---|
| packets received | 15,630 | 12,805 | 8,500-22,600 each |
| **distinct** states after removing exact repeats | 2,935 (1.5/s) | 3,660 (1.8/s) | 1,900-5,000 |
| median spread | 2.3 bp | 3.2 bp | crude 22-33 bp, gas 29-43 bp of premium |
| updates where volume rose by more than the last trade size (trades merged) | 42% | 77% | 36-81% |
| median abs. move over 60 s / 300 s | 4.5 / 7.9 bp | 3.2 / 9.6 bp | |

Three data lessons. Kite's stream is also a snapshot feed, so the app's code should assume the same until a day of Kite ticks shows otherwise:

1. **About 80% of packets are exact repeats.** Drop them.
2. **About 1 in 150 packets is an OLDER state re-sent** (volume and last-trade time step back, the book reverts).
   Keep only packets whose volume is at its running maximum; otherwise these fake order flow.
3. **Trade fields and book fields arrive in separate updates**, so signing a trade by comparing its price with the
   previous packet's mid fails (same-10-s correlation with the move: -0.04 crude, 0.00 gas). The plain **tick rule**
   (sign of the last price change) works (+0.20 crude, +0.56 gas). With an exchange tick-by-tick feed this would be
   different; with retail snapshots, use the tick rule.

**Pipe check (same 10 seconds): order flow explains the move, as the research says.**

| same-10-s correlation with the futures' mid move | CRUDEOIL | NATURALGAS |
|---|---|---|
| OFI, best level | +0.71 | +0.73 |
| MOFI, 5 levels | **+0.81** | **+0.90** |
| signed volume (tick rule) | +0.20 | +0.56 |
| signed option flow (ATM +/-1 calls minus puts) | +0.77 | +0.61 |

**Lead check (does the last 10 s / 60 s predict the next 1, 3, 5 minutes?) - anecdote only.**

- 66 feature x window x horizon cells (`leadlag.csv`). A null band was made by shifting each feature in time by a random
  300+ s (200 draws). 15 cells passed the 95% band; about 3 would by chance, but the cells overlap heavily and there
  are only 33 independent 1-minute windows and 6 independent 5-minute windows in 34 minutes.
- **OFI and MOFI (the strongest same-time measures) showed NO lead** in either contract: correlations -0.13 to +0.12,
  all inside the null band.
- **The signs disagree between contracts.** Signed volume over 60 s pointed the right way for NATURALGAS (+0.28 at 1
  min) and the WRONG way for CRUDEOIL (-0.45 at 5 min). Option flow was contrarian in crude. Total buy/sell quantity
  imbalance (TQI) was contrarian in both (-0.27 to -0.58): more resting bids, then the price fell. That fits the view
  that resting quantity is passive, not aggressive - but in one evening with both contracts drifting up (+16 bp,
  +38 bp) it can just as well be a trend artefact.
- Cost bar for comparison: a crude ATM option round trip costs about 0.6% spread + ~0.3% charges = ~0.9% of a
  Rs 263 premium; with delta 0.5 on an 8,880 future (option moves ~17x the future in %) that is **~5 bp of the
  future**. The median 60-s move is 4.5 bp. A 1-minute order-flow signal would have to call most of a typical
  minute's move correctly just to break even.

**Verdict on the sample:** the feed is good enough to compute every order-flow measure live, and they line up with the
price in the same seconds. Nothing tonight shows a lead that an option buyer could pay for; one evening could not
show it either way. This is why section 6 is "record first, test later".

## 5. Limits, honestly

- **Retail latency.** Phone to Zerodha to exchange is hundreds of milliseconds to a second each way. Published
  order-flow edges live in milliseconds to seconds. We can only use the slow tail (1-15 min).
- **5 levels is a thin slice.** HFT books are deep and fast; most quotes at the best level live for milliseconds. Kite
  and Dhan send snapshots, so many order events between ticks are invisible (OFI from snapshots is an approximation).
- **Total buy/sell quantity** counts far-away orders and is easily spoofed. Use it only as a weak extra.
- **Option books are thin and wide.** Option OFI is noisy; the index future and the whole chain's signed volume are
  better carriers of direction.
- **Costs.** Even the app's best flow signal is 0.42x of cost. A filter can raise the hit rate of an existing edge
  (Liquidity) more cheaply than a new entry signal can create one.
- **No history.** Every test must wait for our own recording. One evening (section 4) is an anecdote.

## 6. Recommendation and build plan for the app

**Build (small, inside what exists):**

1. `KiteTicks.packet`: keep all 5 bid and 5 offer levels (price, qty, orders), last traded quantity (bytes 8-12) and
   last trade time (bytes 44-48). Today it keeps only the best level and the totals.
2. A pure `FlowMeter` in `engine` (unit-tested like `KiteTicks`): per token, drop repeated and stale ticks (volume
   below its running maximum, section 4), then on each tick update OFI, MOFI, signed volume (d volume x tick-rule sign), aggressor buy/sell volume, QI, DI5, TQI, spread; roll 1-s bars; expose
   10-s / 60-s / 300-s sums. No allocation per tick beyond a ring buffer of 300 one-second bars.
3. `KiteStream`: feed every parsed tick into `FlowMeter` for a fixed watch list: NIFTY and BANKNIFTY current-month
   futures, ATM +/-2 CE/PE of each traded expiry (re-centred every 5 min), and while MCX arms run, the CRUDEOIL and
   NATURALGAS near futures and ATM +/-1. The stream is already in full mode, so no new subscription mode is needed.
   Keep the stream alive during market hours while recording (today it stops after idle; the recorder must own a
   "keep alive 09:15-15:30" reason, which costs battery and data: ~20 instruments x 1-3 ticks/s x 184 bytes over
   6.25 h is roughly 80-250 MB of mobile data a day; on Wi-Fi this is minor, on mobile data it is not).
4. `MarketRecorder` (already on since 6 Oct; it writes a once-a-minute QUOTE snapshot - last, volume, OI, best bid/ask
   with sizes, total buy/sell qty - which shows the book's state but not the flow between snapshots). Add one encrypted line per instrument per minute with the minute's OFI, MOFI, signed volume, buy and
   sell aggressor volume, QI/DI5/TQI (last and minute mean), median spread, tick count; plus at every paper or live
   signal of any arm, a snapshot of the 10-s/60-s/300-s values and the 5-level book of the traded option (this is
   HUNT_FINAL recommendation 4).
5. Show nothing to Boss as a "signal" yet. A small "flow" line on the Liquidity card can come after the test.

**Record:** every session, **8-12 weeks minimum (40-60 sessions)** before the first look; the decision test needs about
**300 Liquidity paper signals**, which at the arms' current rate is likely 3-6 months. Why that long: a filter that
skips the worst third of trades must show a difference in average R between kept and skipped trades; with R's
standard deviation near 1 and ~100 trades per group, the standard error of the difference is ~0.14 R, so an honest
0.3 R improvement needs ~300 trades to be told apart from luck.

**The paper-only order-flow filter test (pre-register before looking):**

- Arms: Liquidity 15+5 BANKNIFTY (the one real edge), plus MIDCPNIFTY Liquidity and the ORB arms as a check.
- Rule A (agree): take a long-call signal only if the BANKNIFTY futures' normalised MOFI over the last 60 s and 300 s
  is >= 0 (long put: <= 0). Rule B (strong disagree skip): skip only when the 300-s value is in the opposite 20% tail
  of its own trailing 20-day distribution. Rule C: the same with signed option flow (calls minus puts, ATM +/-2).
- Every signal is still paper-traded; the filter only labels it "kept" or "skipped". Compare kept vs all vs skipped:
  average R, net Rs per trade after the app's costs, hit rate, with a bootstrap by day and a permutation of the label.
- Pass only if kept beats all by more than the noise band in BOTH halves of the data and in a locked last block, and
  the skipped trades lose on average. Otherwise drop it, as with h26's OI filter.

**Optional upgrade if the filter shows promise:** add Dhan's 20-level (or 200-level) feed for the BANKNIFTY future on
a laptop or a cheap cloud box during market hours (the Dhan plan is already paid) and compare 5- vs 20-level OFI.

## Sources

Listed inline in sections 1 and 2.
