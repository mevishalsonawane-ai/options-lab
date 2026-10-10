# HUNT R8: order-flow traps, how to spot them, and the rules that protect us

Written 9 Oct 2026 by R8 for Boss. Follows `HUNT_R7_ORDER_FLOW.md`. Frame: option BUYING only, 1 lot, NSE index
options and MCX, data from a retail feed (Kite full mode, 5 levels; Dhan 5 levels, 20 levels for NSE F&O only).

- Code: `research/hunt/r8/traps.py` (all measurements), `research/hunt/r8/traps_extra.py` (persistence, close, latency).
- Data: the R7 recording, `scratchpad/hunt/r7/ticks.parquet` (MCX, 9 Oct 2026, 22:55-23:29 IST, 14 contracts,
  217,517 packets). Outputs: `scratchpad/hunt/r8/` (`traps.log`, `traps_extra.log`, csv files).
- **Sample warning:** one evening, 34 minutes, MCX only, no NSE, no session open, no expiry day. The counts of book
  events are large (80,000 size additions, 11,700 "walls"), so the book statistics are solid for *that* evening;
  anything about price moves after an event (stop hunts, absorption, candle-close effects) has 5-50 independent
  cases and is an anecdote.
- Nothing was committed.

## The answer in plain English

Boss is right: much of what a retail screen shows in the order book is not real, and some of it is put there to
fool people. But the biggest trap in our own data is not a villain pulling an order at the last second. It is that
**most of the book is temporary all the time**:

- **About half of all size that appears in the 5 visible levels is cancelled within 3-5 seconds without a single
  trade.** When added size disappears, it was cancelled about 9 times out of 10 and traded only about 1 time in 10.
- **"Walls" (a level 2x the normal size or more) mostly live about 1 second.** Only 22% are still there after 3 s,
  10% after 5 s, 4% after 10 s. When a wall ends, it was pulled 45-50% of the time and traded only 5-12% of the time
  (the rest moved out of the 5-level window).
- **We did NOT find extra pulls in the last seconds before a 1-minute or 5-minute candle closes.** Pulls happen at a
  steady rate all the time (about 1 in 8 walls per second). The 5-minute boundary showed a hint (1.35x, p = 0.09, 35
  pulls) that is not significant after testing several windows. The late-second pull is real as a *tactic* (SEBI's
  spoofing orders describe cancels "within seconds"), but in our data it is not concentrated at candle closes.
- **Total buy / total sell quantity is almost entirely invisible, far-away orders.** The 5 levels we see hold only
  2-15% of it (median about 3%), and 84-97% of its changes happen with no trade at all. This is the number spoofers
  are proven to target (Korea Exchange study below). Our rule: never use it for direction.
- **"Absorption" (big volume trading at a price that does not give way) did not mean "this level will hold".**
  Flagged levels broke 58-68% of the time, no better than ordinary levels (55%). Absorption is only useful after the
  price confirms it.
- **Our data feed has its own traps**: 70-80% of packets are repeats, 0.2-1.1% are old states re-sent, the trades we
  see are 1.2-2 s old at the median and sometimes 5-50 s old. A candle built at the exact boundary second is
  missing its last trades.

So the solution is not one clever detector. It is a small set of **defensive rules**: count only size that has
stayed, cap big levels, ignore total buy/sell quantity, never buy on the first poke through a level, never decide
on a candle until its late packets have arrived, stay out of the open, the close and expiry-day afternoons, and stop
trusting the book when the feed is unhealthy. These rules cost us almost nothing (we lose some fleeting "signals"
that never paid anyway) and remove most ways the book can trick us.

## 1. The traps, one by one

For each: what it is, what a retail screen shows, how we detect it, our rule, and the evidence.

### 1.1 Spoofing (one fake big order) and layering (several fake orders at several prices)

- **What it is.** Someone places large orders they do not want filled, to make the book look heavy on one side, then
  trades small on the *other* side at the better price the fake pressure creates, then cancels the fake orders.
- **Indian cases.** SEBI's Nimi Enterprises order (2023) was the first to define spoofing: large orders on one side,
  smaller executed orders on the other, the large ones cancelled, the gap between execution and cancel "a matter of
  seconds" ([IndiaCorpLaw](https://indiacorplaw.in/2023/06/27/sebis-order-on-spoofing-a-way-forward/)). The Patel
  Wealth Advisors interim order (28 Apr 2025): spoofing in 173 scrips over 292 scrip-days in **cash and derivatives**,
  big orders placed away from the market, quick opposite trades, then cancels; Rs 3.22 crore impounded; the spoof
  orders were fully disclosed while the genuine ones were only partly disclosed
  ([Outlook Business](https://www.outlookbusiness.com/markets/patel-wealth-advisors-and-4-directors-debarred-by-sebi-over-order-spoofing-charges),
  [Outlook Money](https://www.outlookmoney.com/invest/sebi-cracks-rs-32-crore-mega-order-spoofing-scam-involving-173-scrips-check-details),
  [FinSecLaw](https://www.finseclaw.com/article/ghosts-in-the-order-book)). Spoofing is not named in SEBI's PFUTP
  regulations; SEBI treats it as manipulation under them.
- **Research.** Cartea, Jaimungal & Wang, "Spoofing and price manipulation in order-driven markets", Applied
  Mathematical Finance 2020: the spoofer's whole aim is to **skew the volume imbalance**, because other traders read a
  buy-heavy book as upward pressure and send more buy market orders
  ([IDEAS](https://ideas.repec.org/a/taf/apmtfi/v27y2020i1-2p67-98.html)). In plain words: **the imbalance we would
  like to use is exactly what spoofers fake.** Lee, Eom & Park, "Microstructure-based manipulation: strategic behavior
  and performance of spoofing traders", J. Financial Markets 2013, Korea Exchange account-level data: spoof orders
  created the appearance of a big order-book imbalance under the KRX rule that displayed **total bid/ask volume**;
  spoofing **fell drastically after KRX stopped showing total volume** (and showed 10 levels instead of 5)
  ([Korea Univ.](https://scholar.korea.ac.kr/handle/2021.sw.korea/103341)). (Note: "Lee, Mucklow & Ready" is a
  different paper, about depth, see 1.10.) Cancellation alone is not proof (market makers cancel all the time);
  regulators look at the whole pattern.
- **What a retail screen shows.** A big quantity appears at level 2-5 (rarely at the best price, where it could get
  hit), the price drifts the other way or small trades print on the opposite side, then the big quantity vanishes
  with no trade at its price. Often few orders behind it (order count is in both Kite and Dhan depth).
- **How we detect it.** A level drop of at least half its size with less than half of the drop traded at that price
  = a **pull**. A wall that is pulled is ignored, and the side it was on is down-weighted for the pull window.
- **Our rule.** Size counts only after it has **persisted** (5 s for walls, see section 3); every level is **capped**
  at 2x the trailing median level size in imbalance maths; 1-order walls count half; never act on imbalance alone.
- **Our data.** 45-50% of walls ended by being pulled, 5-12% by being traded (pooled, K = 2, 3). Single-order walls
  were pulled a bit more often (57% vs 48% for 3+ order walls at K = 2; 50% vs 44% at K = 3). After a pull the price
  did NOT move against the wall side on average (options: 49% against, 48% with; futures n = 13). So pulls here look
  more like everyday cancels and re-pricing than like profitable spoofs, which is what one would expect: real spoofing
  is the rare exception inside a large volume of ordinary cancels.

### 1.2 Last-second order pulls (before a candle closes, before an auction, before a price is reached)

- **What it is.** A big order sits there to shape what others see, then is removed just before it would matter: before
  the 1-/5-minute candle closes (so the candle "looks" supported), just before the price reaches it, or before an
  auction's random end (NSE pre-open order entry closes at a random moment between minute 7 and 8 precisely so orders
  cannot be pulled at a known last second).
- **Detection.** As 1.1, plus timing: pulls in the last N seconds before a boundary.
- **Our data.** The hazard of a pull (pulls per wall-second alive) in the last 5 s of a minute was **1.03x** (K = 2,
  509 pulls, p = 0.53) and **1.12x** (K = 3, p = 0.15) the rate at other times; in the last 2 s, 0.97x and 1.01x. Last
  5 s before a 5-minute boundary: 1.11x (p = 0.33) and 1.35x (p = 0.09, 35 pulls). Last 5 minutes before the MCX
  close: the pull rate per wall-hour was *lower* (324 vs 508 at K = 2). **No sign of a candle-close pull pattern
  here.** What we did find is that pulls are constant: the typical wall's chance of being pulled is about 13% per
  second.
- **Our rule.** Because pulls happen at any second, the defence cannot be "ignore only the seconds near a boundary".
  It is: (a) never treat visible size as support unless it has lasted 5 s **and** is still there at decision time;
  (b) decide on **closed** candles only, after a short grace period for late packets (1.13); (c) a level that vanishes
  in the 5 s before our decision cancels any "support/resistance" claim that used it.

### 1.3 Flickering / fleeting liquidity

- **What it is.** Orders placed and cancelled within moments, mostly by algorithms re-pricing (chasing the market),
  not by manipulators. Hasbrouck & Saar ("Technology and liquidity provision", J. Financial Markets 2009): about a
  third of limit orders on INET were cancelled within 2 seconds ("fleeting orders"), 93% of limit orders were
  cancelled or revised; they support "chasing" and "search" motives
  ([IDEAS](https://ideas.repec.org/a/eee/finmar/v12y2009i2p143-172.html),
  [working paper](https://pages.stern.nyu.edu/~jhasbrou/Research/Working%20Papers/HS6.pdf)). In India, SEBI's
  order-to-trade ratio (OTR) charges on algorithmic orders start only at an OTR of 50, and from 6 Apr 2026 algo orders
  within +/-0.75% of LTP (options: within +/-40% of premium or Rs 20) are **exempt**
  ([NSE 2020 circular](https://nsearchives.nseindia.com/content/circulars/FA21156.pdf),
  [Outlook Money 2026](https://www.outlookmoney.com/invest/sebi-revises-order-to-trade-ratio-framework-for-algo-trading-what-it-means-for-retail-investors)).
  So near-the-touch cancelling carries no penalty and we should expect lots of it.
- **Our data (every increase in size at a visible price, followed until it is gone).**

| | size additions | cancelled within 1 s | within 3 s | within 5 s | share of removals that were cancels (not trades) |
|---|---|---|---|---|---|
| CRUDEOIL fut | 6,204 | 8% | 45% | 58% | 97% |
| NATURALGAS fut | 4,675 | 6% | 30% | 41% | 91% |
| crude ATM options (6) | 7,856-8,534 each | 11-13% | 45-52% | 52-59% | 88-93% |
| gas ATM options (6) | 2,974-3,754 each | 5-7% | 27-32% | 37-44% | 87-95% |

  Our feed is a snapshot about once a second, so cancels faster than that are invisible: the true flicker rate is
  higher than this. Small additions flicker most: 1-lot additions were cancelled within 3 s 57% of the time, the
  biggest fifth 22-27%.
- **Our rule.** Persistence: size counts in any depth measure only after it has been seen continuously for 3 s (in at
  least 3 distinct snapshots); for anything called a "wall" or "support", 5 s.

### 1.4 Fake walls (big visible levels)

- **Our data (wall = level >= K x trailing 5-minute median level size).**

| K | walls in 34 min | median life | alive at 3 s | at 5 s | at 10 s | ended pulled / traded / out of view |
|---|---|---|---|---|---|---|
| 2 | 11,700 | 1.2 s | 22% | 10% | 4% | 50% / 5% / 45% |
| 3 | 3,904 | 1.2 s | 24% | 12% | 4% | 45% / 8% / 47% |
| 5 | 1,039 | 2.0 s | 35% | 19% | 7% | 31% / 12% / 58% |

  Most are in crude options, where the median level is only 3 lots (so 2x is just 6 lots). In the futures walls
  last longer (crude fut median 5 s, gas fut 42 s) but still end pulled 39-71% of the time and traded 3-12%.
- **Does persistence make a wall more real?** In crude options, yes, a little: of walls (K = 3) still alive at 0 /
  3 / 5 / 10 s, the share that ended by being **traded** rose 8% -> 16% -> 21% -> 27% and pulled fell 45% -> 41% ->
  35% -> 30%. In futures and gas options the change was small (pulled stays 40-60%). **Even a wall that has stood
  for 10 s was pulled more often than traded.**
- **Size of walls.** Peak/median: median 2.3-2.6x, 90th percentile 3.3-4.7x, 99th 5.7-15.7x, max 96x (a 289-lot
  level in a crude put whose median level was 3 lots).
- **Our rule.** Cap every level at 2x the trailing median in imbalance maths (a single wall then moves the
  imbalance a little, not a lot). Do not show or use a "wall" unless it is >= 3x, has lasted 5 s, and has >= 2 orders.
  Never place a stop "behind the wall" or count on a wall to stop a move.

### 1.5 Iceberg / hidden orders and absorption

- **What it is.** A big order shows only a slice; when the slice trades, the next slice appears ("refill"). Frey &
  Sandas (Xetra): iceberg orders are on average 12-20x the size of normal limit orders, and other traders do detect
  and react to them ([CFS paper](https://gfk-cfs.de/media//08_48.pdf)); CME study of iceberg refills
  ([arXiv 1909.09495](https://ar5iv.labs.arxiv.org/html/1909.09495)). On NSE F&O, **exchange disclosed-quantity
  orders are not allowed** (Zerodha's "iceberg" for F&O just slices an order into legs to get under the freeze limit,
  each leg fully visible) ([Zerodha](https://support.zerodha.com/category/trading-and-markets/product-and-order-types/order/articles/iceberg-orders)).
  So on NSE F&O a "refilling" level is usually many participants or a slicing algorithm re-posting, not a true
  hidden order; it still looks the same on screen.
- **Absorption vs exhaustion (behavioural misread).** "Absorption" = lots of selling hits a bid and the bid does not
  drop, read as a big buyer defending. "Exhaustion" = the selling finally runs out and the price turns. Both look
  like "big volume at a level" until the price shows which it was.
- **Our data (best price unchanged = one episode; flagged when traded volume at the price >= 1.5x the most that was
  ever shown there, with at least 2 prints).** 10% of episodes were flagged (47 in the futures, 368 in options;
  median traded/shown 2.3-3x). **Flagged levels broke afterwards 58% (options) and 68% (futures) of the time, vs 55%
  for unflagged levels.** The mid move in the defended direction 30 s after the flag: futures median 0, mean -0.3 bp,
  only 28% positive. Refills at the best price happened in 51% of futures episodes (3+ refills: 10%).
- **Our rule.** Absorption is **not** a buy signal. It can only *confirm* an entry when: traded at the level >= 3x
  the most ever shown there, the level then holds 10 s more, and the price ticks at least 1 tick away in the
  defended direction. If the level breaks within 30 s, relabel it "exhaustion" and treat it as the break it is.

### 1.6 Quote stuffing

- **What it is.** Floods of orders and cancels that slow other people's feeds; named by Nanex in 2010; FINRA fined
  Trillium for layering in a related case; the SEC/CFTC found it was not the cause of the 2010 Flash Crash
  ([Wikipedia summary](https://en.wikipedia.org/wiki/Quote_stuffing)). Listed as an example of manipulation in the
  EU MAR indicators (Delegated Regulation 2016/522, amended in 2026)
  ([Council text](https://data.consilium.europa.eu/doc/document/ST-8101-2026-ADD-1/en/pdf)).
- **What a retail screen shows.** A burst of packets, a jumpy book, and then our feed lagging (old trade times).
- **Our rule.** Feed-health checks (section 4) switch the book signals off when updates arrive in bursts with no
  trades or the last-trade age grows. We cannot see individual orders, so we do not try to "detect stuffers"; we
  just stop trusting the book while it happens.

### 1.7 Momentum ignition and stop hunts

- **What it is.** A burst of aggressive orders pushes the price through an obvious level (prior high/low, round
  number) to trigger stop-losses and breakout algorithms, then the igniter sells into that rush and the price falls
  back. ESMA lists momentum ignition as manipulation; the CFTC's Chilton described ordinary investors' stops being
  triggered ([Finextra](https://finextra.com/blogposting/13080/momentum-ignition-arson-for-financial-markets),
  [Bloomberg](https://www.bloomberg.com/news/articles/2013-05-02/charlie-rose-talks-to-cftc-commissioner-bart-chilton)).
- **What a retail screen shows.** A quick spike through the level on a volume burst, the far side's depth thin, then
  a return into the old range within a minute or two.
- **Our data (futures mid breaking its own trailing 5-minute high/low by a tick; 11 events only).** Crude: back inside
  the old range within 30 s 33%, 60 s 50%, 120 s 83%; 180 s later the price was on average 5.8 bp *against* the
  breakout. Gas: within 60 s 20%, 180 s 40%; 180 s later +8.3 bp *with* the breakout. Too few to measure the trap,
  but enough to see that **reversals often take longer than 30 s**.
- **Our rule.** Never enter on the first tick through a level. A break counts only if a **1-minute candle closes
  beyond the level and the price is still beyond it 60 s later**. A break that comes back inside the old range within
  120 s is labelled a failed break (possible stop hunt), and for the next 2 minutes we do not take trades in the
  break direction. Stops are placed at a distance based on recent volatility, not 1 tick behind an obvious level.

### 1.8 Wash trades / reversal trades / painting the tape

- **What it is.** Trades with no change of ownership (or with a friendly counterparty) that create fake volume.
  SEBI's illiquid BSE stock-options case (Apr 2014-Sep 2015): 2.91 lakh reversal trades, **81% of all trades** and
  55% of volume in that segment, were non-genuine; hundreds of entities fined in batches since 2019
  ([Business Standard](https://www.business-standard.com/amp/article/markets/sebi-slaps-fine-of-rs-1-1-cr-on-22-entities-in-illiquid-stock-options-case-121123101016_1.html),
  [Outlook Business](https://www.outlookbusiness.com/news/10-entities-fined-rs-50-lakh-by-sebi-in-illiquid-stock-options-case-news-47396)).
- **What a retail screen shows.** Big volume with no price change and no lasting OI change, in an illiquid strike.
- **Our rule.** Use flow only from liquid contracts (index future, ATM +/-2 strikes) with a normal spread; ignore
  far OTM and illiquid strikes; a single print larger than 5x the median print that does not move the price is
  ignored in signed-volume sums.

### 1.9 Session open and close effects

- **Open.** NSE cash has a pre-open 9:00-9:08 (random close) with matching to 9:12; since 8 Dec 2025 NSE F&O also has
  a pre-open call auction for **current-month futures** (options are not in it, as far as the sources agree)
  ([NSE](https://www.nseindia.com/static/products-services/equity-derivatives-pre-open-session),
  [Jainam](https://www.jainam.in/blog/nse-pre-open-session-fo-segment/)). Options open straight into continuous
  trading at 9:15 with stale quotes, wide spreads, and an overnight gap being priced: the book and flow in the first
  minutes reflect opening orders, not intraday pressure.
- **Close and settlement.** Daily settlement of futures and the expiry settlement of index derivatives have used the
  **volume-weighted average of the last 30 minutes (15:00-15:30)**. In 2026 SEBI added a cash-market closing auction
  (CAS) and then, after a jolt in the auction on 3 Sep 2026, consulted (comments closed 3 Oct 2026) on using a blend of
  the last-30-minute VWAP and the 10-minute auction, or keeping the 30-minute VWAP; reports say SEBI may pause CAS for
  derivatives settlement. **No final circular seen as of 9 Oct 2026; check NSE circulars**
  ([ANI](https://www.aninews.in/news/business/sebi-proposes-changes-to-derivatives-settlement-closing-auction-timings-seeks-public-comments20260912124233/),
  [Kotak Neo](https://www.kotakneo.com/news/regulations/closing-auction-session-sebi-derivatives-settlement-price/),
  [Outlook Money](https://www.outlookmoney.com/invest/sebi-likely-to-suspend-closing-auction-session-mechanism-for-derivatives-settlement-revert-to-vwap-for-a-year)).
  Either way, from 15:00 big players trade to move or hedge against a *settlement average*, not to signal direction.
- **MCX tonight.** Last 5 minutes before the 23:30 close: futures spreads unchanged, crude futures' 5-level depth fell
  from 111 to 78 lots, some crude option spreads widened (22 -> 33 bp, 29 -> 39 bp); wall pulls did not increase.
- **Our rule.** NSE: ignore order flow 9:15-9:20 (5 minutes, not 3, because options have no opening auction) and from
  15:00 (the settlement window). MCX: ignore 9:00-9:05 and the last 5 minutes.

### 1.10 Scheduled news (depth disappears before information)

- Lee, Mucklow & Ready, "Spreads, depths, and the impact of earnings information", Review of Financial Studies 1993:
  **liquidity providers widen spreads and pull depth before information events**, more so before bigger moves
  ([IDEAS](https://ideas.repec.org/a/oup/rfinst/v6y1993i2p345-74.html)). A thinning book before news is not a signal;
  it is everyone stepping back.
- **Our rule.** Ignore depth signals from 5 minutes before to 5 minutes after scheduled events: RBI policy, US CPI /
  payrolls / FOMC for MCX, the weekly US EIA crude report (Wednesday 10:30 New York time = 20:00 IST in US summer time,
  21:00 IST in winter) and EIA gas storage (Thursday, same clock), and index heavyweight results.

### 1.11 Expiry-day pinning, ramping and marking the close (the Jane Street order)

- **SEBI interim order, 3 July 2025** (ex-parte; Jane Street denies wrongdoing; ban lifted on conditions 21 July 2025):
  over 1 Jan 2023-31 May 2025 SEBI alleged index manipulation on 15 of 18 examined expiry days. **Example, 17 Jan
  2024:** in "Patch I" (09:15-11:47) the group bought about Rs 4,370 crore of BANKNIFTY constituent stocks and futures,
  lifting the index, while building large short index-option positions; in "Patch II" it sold about Rs 5,372 crore
  of the same, pushing the index down, and the options made Rs 735 crore that day. Separately, "**extended marking
  the close**": on three days, including 10 July 2024, aggressive selling in the last 60 minutes, notably **from
  14:30**, in constituents and futures to depress the closing level, for about Rs 560 crore of option profit. Total
  impounded: Rs 4,843.57 crore
  ([Legal500](https://www.legal500.com/developments/thought-leadership/sebi-update-interim-order-against-jane-street-group-for-alleged-index-manipulation/),
  [Mondaq](https://www.mondaq.com/india/commoditiesderivativesstock-exchanges/1648134/sebis-interim-order-against-jane-street-allegations-of-index-manipulation-explained),
  [Kotak](https://www.kotaksecurities.com/news/market-news/sebi-bars-jane-street)).
- **What it says about expiry-day index moves.** On expiry days the index's intraday trend can be manufactured by a
  player who is losing money on the cash/futures leg on purpose to win more on options: the morning order flow in
  constituents and futures looked like genuine buying and **was the trap**; the reversal came later in the day. Order
  flow on such a day is real (actual trades), but its *meaning* is the opposite of what a flow reader assumes.
  Option-expiry "pinning" toward big strikes is a separate, older effect (Ni, Pearson & Poteshman, J. Financial
  Economics 2005, stock prices cluster at strikes on expiry).
- **SEBI response.** Intraday index-option position limits (Rs 5,000 crore net / Rs 10,000 crore gross,
  futures-equivalent) checked by at least four random intraday snapshots, one between 14:45 and 15:30, with extra
  penalties for breaches on expiry days from 6 Dec 2025 (circular SEBI/HO/MRD/TPD/CIR/P/2025/122, 1 Sep 2025)
  ([SCC Online](https://www.scconline.com/blog/post/2025/09/03/sebi-equity-derivatives-intraday-limits-2025/)).
- **Our rule.** On expiry days of the index we trade: order-flow filters are off from **14:30** (not only the last
  hour), total buy/sell quantity is ignored all day, and a morning trend driven by heavy futures/constituent buying is
  not taken as "smart money" confirmation. The current-hunt edge (Liquidity arms) is evaluated as usual; only the
  order-flow overlay is switched off.

### 1.12 Total buy / total sell quantity (Kite `buy_quantity`/`sell_quantity`, Dhan total bid/ask qty)

- **What it is.** The sum of **all pending** orders on each side at any price, not traded volume (Kite forum
  confirmations: [1](https://kite.trade/forum/discussion/comment/426/), [2](https://kite.trade/forum/discussion/comment/17971/)).
  Far-away orders cost nothing to place (no fill risk) and count fully. This is the exact number KRX spoofers gamed
  until KRX stopped showing it (1.1).
- **Our data.** The 5 visible levels held a median **4% (crude fut) to 15% (gas fut)** of the total, **1-3% in crude
  options**. **84-97% of its changes came with no trade.** In the crude future, 22% of the change size was not
  explained by any change in the visible levels (orders far away). R7 found it contrarian in both contracts that
  evening (more resting bids, then price fell).
- **Our rule.** Weight zero in any signal. Show it, if at all, greyed out with a "not a signal" note.

### 1.13 Feed artefacts (our own data lying to us)

- **Snapshot merging.** Kite and Dhan send snapshots, not every event; R7 found 42-77% of futures updates had more
  new volume than the last trade's size (several trades merged), and trade and book fields arriving in separate
  updates. Signing trades by the quote rule fails; use the tick rule.
- **Repeats and stale packets.** 70-80% of packets were exact repeats; 0.2-1.1% were older states re-sent (volume
  stepping back). Both must be dropped or they fake order flow.
- **Lateness.** The age of the last trade when its packet arrived (receive time minus exchange trade time, which is
  whole seconds, so about 0.5 s of this is rounding): median 1.2-1.3 s in the futures, 1.4-2.1 s in options; 95th
  percentile 3-6 s (futures), 3-25 s (options); maximum 25 s (crude fut) to 56 s (thin gas put). **A candle closed at
  the boundary second is still missing trades.**
- **Gaps.** Distinct-update gaps: futures median 0.1-1 s, 99th percentile 1.7-2.1 s, max 3.7-4.2 s; options 99th
  percentile 1.4-5 s, max up to 25 s.
- **5-level limit.** We see 5 prices a side; size beyond is only in the (gameable) totals. A book that "shifts" moves
  levels in and out of view; our measurements count those as "out of view", not as cancels (45% of wall endings).
  Dhan's 20-level feed for NSE F&O shows more, but also shows more far-away cheap-to-fake orders: the persistence and
  cap rules matter more, not less, with deeper books.
- **Crossed books:** none tonight; drop them if seen.

### 1.14 Behavioural misreads

- **"Big bid = support."** Section 1.4: walls are mostly pulled, rarely traded.
- **"Absorption = reversal."** Section 1.5: it did not hold more often than any level.
- **Delta (CVD) divergence.** Price makes a new high while cumulative signed volume does not, read as "buyers are
  exhausted". With snapshot data the signed volume itself is approximate (tick rule, merged trades), and divergence
  is the normal state when passive (limit) orders drive the move. We found no study showing retail-visible delta
  divergence predicts moves net of costs. Rule: divergence is never a trigger; at most a reason to skip.
- **Reading one candle's flow.** R7: order flow explains the same seconds' move (corr 0.7-0.9) but did not lead the
  next 1-5 minutes. A strong flow candle mostly tells us what already happened.

## 2. The measurements in one table (MCX, 9 Oct 2026, 22:55-23:29 IST)

| what | futures | options | sample size | read |
|---|---|---|---|---|
| packets that are exact repeats | 70-80% | 71-79% | 217,517 packets | drop |
| stale (older) packets | 0.7-1.1% | 0.2-0.7% | | drop |
| added size cancelled within 3 s, no trade | 30-45% | 27-52% | 80,626 additions | most depth is temporary |
| removals that were cancels, not trades | 91-97% | 87-95% | | the book is mostly not traded |
| walls (>=2x) alive at 3 / 5 / 10 s | 72% / 60% / 41% (220 walls) | 22% / 10% / 4% pooled (crude options dominate) | 11,700 walls | persist 5 s |
| walls ending pulled vs traded | 39-71% vs 3-12% | 22-64% vs 3-19% | | never rely on a wall |
| pull hazard last 5 s of minute vs rest | 1.03x (p 0.53) K=2; 1.12x (p 0.15) K=3 | | 5,825 pulls | no candle-close pull effect seen |
| same, last 5 s of 5 minutes | 1.11x (p 0.33); 1.35x (p 0.09) | | 129 / 35 pulls in window | hint only |
| total buy/sell qty visible in 5 levels | 4-15% | 1-3% (crude) | | ignore totals |
| total qty changes with no trade | 94-96% | 83-97% | | ignore totals |
| absorption-flagged levels that broke | 68% | 58% | 47 / 368 | absorption is not support |
| breakouts back inside range by 30/60/120 s | crude 33/50/83%, gas 0/20/20% | | 11 events | anecdote; use 60-120 s |
| last-trade age at receipt, median / p95 | 1.2-1.3 s / 3.4-6 s | 1.4-2.1 s / 3-25 s | | wait before closing a candle |
| last 5-s-of-minute move -> next 10 s corr | crude +0.13 vs +0.15 other; gas +0.36 vs -0.06 (n = 12) | | 22 / 12 windows | no painting seen; tiny n |

## 3. Recommended parameters for the app's trap guard

Defaults proposed by the guard builder, and our recommendation:

| parameter | guard default | recommend | why |
|---|---|---|---|
| depth persistence (size counts in imbalance) | 3 s | **3 s and >= 3 distinct snapshots** | 3 s removes 39-45% of size that would be cancelled untraded; snapshots come ~1/s, so 3 s can be 1-2 packets after de-duplication |
| wall persistence (size called "wall"/"support") | 3 s | **5 s** | only 10-12% of walls reach 5 s; survivors are traded more (crude options K=3: traded 8% at birth, 16% at 3 s, 21% at 5 s) |
| wall cap in imbalance maths | 2x median level | **keep 2x**, median = trailing 5-minute median of all 10 visible levels for that contract, floor 1 lot | a typical wall is 2.3-2.6x; 99th percentile 6-16x; cap stops one fake level dominating |
| wall threshold for display / filters | (none) | **>= 3x median, alive >= 5 s, >= 2 orders**; 1-order walls count half | 1-order walls pulled more (57% vs 48%) |
| pull definition | (pull window 5 s) | **keep 5 s**: level drops >= 50% with < 50% of the drop traded at that price, judged over 5 s; and apply it at ANY time, not only near candle boundaries | pulls are uniform in time, ~13%/s per wall; no boundary concentration |
| after a pull | | **down-weight that side's depth for 10 s** | a pulled side's remaining depth is suspect |
| absorption flag | | **traded at level >= 3x max shown (min 2 prints) AND level holds 10 s AND price ticks >= 1 tick away** | 1.5x flags broke 58-68%, no better than base 55% |
| absorption -> exhaustion relabel | | **level breaks within 30 s** | |
| stop-hunt / failed-break window | 30-60 s | **60 s confirm, 120 s failed-break label, 2-minute lockout in the break direction** | crude: only 33% of reversals by 30 s, 50% by 60 s, 83% by 120 s (n = 6) |
| breakout confirmation | | **1-minute candle close beyond the level + still beyond 60 s later** | |
| ignore first minutes | 3 min | **NSE options 5 min (9:15-9:20); NSE futures 3 min after 9:15; MCX 5 min after 9:00** | options have no pre-open auction; opening quotes stale |
| ignore last minutes | 5 min | **NSE: from 15:00 (settlement VWAP window); MCX: keep last 5 min** | 15:00-15:30 VWAP sets daily settlement and expiry settlement (pending SEBI decision on CAS) |
| around candle boundaries | 10 s | **replace with: decide on closed candles only, after a 3 s grace for late packets; drop the 10 s blackout** (or keep at most 5 s before the boundary) | no extra pulls near boundaries; median trade age 1.2-2 s, p95 3-6 s in futures; a 10 s blackout every minute throws away 17% of the time for nothing measured |
| expiry day | last hour | **from 14:30 on the traded index's expiry; total qty ignored all day** | Jane Street "extended marking the close" from 14:30; SEBI's intraday snapshot window 14:45-15:30 |
| scheduled news | | **-5 to +5 min around RBI, US CPI/NFP/FOMC (MCX), EIA crude Wed and gas Thu 10:30 NY time** | depth is withdrawn before information (Lee-Mucklow-Ready 1993) |
| total buy/sell qty | | **weight 0** | 85-99% invisible; 84-97% of changes without trades; KRX spoofing evidence |
| feed: repeats / stale | | **drop exact repeats; drop packets with volume below running max; drop crossed books** | 70-80% repeats, 0.2-1.1% stale |
| feed: stale instrument | | **no distinct update for 5 s (futures) / 15 s (ATM options) during market hours -> book signals off for that contract** | futures max gap 4.2 s, options p99 up to 5 s, rare up to 25 s |
| feed: lag | | **median last-trade age over last 30 s > 3 s -> book signals off; > 10 s -> show "feed late"** | normal median 1.2-2 s, p95 3-6 s in futures |
| feed: bursts | | **update rate > 5x its trailing 5-min median with no new volume for 2 s -> book signals off for 10 s** | quote-stuffing / re-pricing bursts; cheap insurance, not measured tonight |
| illiquid prints | | **ignore single prints > 5x median print size with no price change, and strikes outside ATM +/-2** | wash/reversal-trade evidence (SEBI BSE options case) |

**Defaults to change:** wall persistence 3 s -> 5 s (keep 3 s for ordinary depth, with a 3-snapshot minimum);
stop-hunt window 30-60 s -> 60 s confirm + 120 s failed-break; first 3 min -> 5 min for NSE options and MCX;
NSE last 5 min -> from 15:00; expiry last hour -> from 14:30; candle-boundary 10 s blackout -> closed candles only
with a 3 s late-packet grace. **Keep:** wall cap 2x median (define the median as above), pull window 5 s (but apply
it all the time). **Add:** total-qty weight 0, absorption confirmation, news windows, feed-health switches.

## 4. Limits, honestly

- One MCX evening; no NSE book at all, no open, no expiry day, no news event. NSE index options are far more liquid
  and far more algorithmic; flicker there is likely higher, walls shorter-lived. Re-run `traps.py` on the first week
  of Kite/Dhan recordings from the app's recorder (R7 section 6) before tuning further.
- Snapshot data (about 1 per second after de-duplication) cannot see anything faster; every flicker and pull number
  here is a **lower bound** on the true rate.
- "Pulled" vs "traded" is inferred from volume printed at the level's price between snapshots; merged trades can
  misattribute a few cases. "Out of view" (book shifted) ended 31-58% of walls and is neither.
- We cannot see who placed an order. None of these rules can prove spoofing; they only stop us from acting on
  size that does not stay.
- None of this makes order flow profitable for a buyer (R7: no lead found; best app flow signal is 0.42x cost). The
  trap guard is protection for a filter, not a source of edge.

## Sources

Inline above. Main ones: SEBI Jane Street interim order summaries (Legal500, Mondaq, Kotak); SEBI Nimi Enterprises
(IndiaCorpLaw) and Patel Wealth Advisors (Outlook Business) spoofing orders; SEBI illiquid stock options penalties
(Business Standard); SEBI intraday position limits circular 2025/122 (SCC Online); NSE OTR circular 2020 and SEBI 2026
OTR revision (Outlook Money); NSE F&O pre-open (NSE, Jainam); SEBI 2026 closing-auction/settlement consultation (ANI,
Kotak Neo, Outlook Money); Cartea, Jaimungal & Wang 2020 (IDEAS); Lee, Eom & Park 2013 (Korea University); Hasbrouck &
Saar 2009 (IDEAS, NYU); Lee, Mucklow & Ready 1993 (IDEAS); Frey & Sandas (CFS); CME iceberg detection (arXiv
1909.09495); Kite forum on total buy/sell quantity; Zerodha on F&O icebergs and disclosed quantity; Nanex/SEC on quote
stuffing; ESMA/MAR indicators.
