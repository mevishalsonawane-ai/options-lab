# The Advanced Trading Methods in the Financial Markets - Farid Boussafsaf and Mohamed Elamine Oualid Taleb (2019)

Source: advanced-trading-methods.txt, 18 pages (journal pages 207-224 of the Journal of Financial, Accounting and
Managerial Studies, Vol. 06 No. 02, September 2019, University of Oum El Bouaghi, Algeria), read in full on 25 Oct 2026
(text layer; the Arabic abstract is a mirror of the English one; the bar charts in Figures 2 and 3 have no text layer,
only the authors' prose about them could be read).
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A short academic survey paper, not a trading manual: it describes, it does not test.
It traces how trading went from floor to screen (NASDAQ 1971, NYSE DOT 1976, ECNs in the 1990s, decimals in 2001, MiFID, Reg NMS).
It defines Algorithmic Trading (AT) and High Frequency Trading (HFT), HFT being a subset of AT, and lists their traits.
It names six HFT strategies and seven kinds of execution algorithm in one or two lines each, with no rules or parameters.
It closes with 2015-2017 survey scores from THE TRADE magazine on why buy-side firms use algorithms.

## Methods, rules and setups

The paper names 13 "methods": six HFT strategies (section 3.1) and seven execution algorithm types (section 3.2). Each
is described in one sentence or only named. None has an entry, stop, target, timeframe or parameter. Below, "not stated"
means the paper says nothing; we add no rules the paper does not give.

### 1. Market making (Sec. 3.1.1)
- Entry: not stated. The paper says only that firms earn the bid-ask spread.
- Stop: not stated.
- Target / exit: not stated (implicitly, the spread on each round trip).
- Filters: not stated.
- Market and timeframe written for: all electronic markets; HFT speed (milliseconds), with co-location.
- Signal source: none of ours; it is quoting both sides of the order book (Level 2 / tick data). Maps to: NSE index
  options no (needs exchange co-location, a market-maker scheme and sub-second quoting; retail pays the spread rather than
  earns it); MCX no; crypto no for us (maker rebates exist on exchanges but we have no tick-level book history and
  every crypto rule would also need a stop and a profit lock).
- Mechanical? no as written (no rule); in practice fully mechanical but infrastructure-bound.
- Our research: not tested as a strategy (we cannot quote at HFT speed). `HUNT_R7_ORDER_FLOW.md` sec. 2 notes the clear
  published order-flow edge sits at seconds and the next price change, used by market makers who earn the spread, and
  that our phone path takes about 0.3-1 s to see a tick. `BIG_PLAYERS_GUIDE.md` sec. 0: market makers quote both sides
  and offload stock; 99% of FPI and prop F&O profit in FY26 came from algo entities.

### 2. Liquidity providing (Sec. 3.1.2)
- Entry: not stated. Described as spotting order-book discrepancies for a currency pair and price gaps between
  platforms.
- Stop / target / filters: not stated.
- Market and timeframe written for: FX, multiple venues, HFT speed.
- Signal source: order books across venues (not one of our categories; nearest is "underlying price/volume" at tick
  level). Maps to: NSE no (a single exchange for index options; NSE vs BSE index options are different contracts);
  MCX no; crypto only in principle (many venues), we lack the data and the speed.
- Mechanical? no as written.
- Our research: not tested by us. Nearest: `HUNT_R8_ORDER_FLOW_TRAPS.md` sec. 1 (book imbalance, spoofing, flicker) and
  `HUNT_R7_ORDER_FLOW.md` (our best flow signal is 0.42x of cost).

### 3. Statistical arbitrage (Sec. 3.1.3)
- Entry: not stated. Described as profiting from brief inconsistencies in rates, prices or other conditions between
  exchanges or asset classes.
- Stop / target / filters: not stated.
- Market and timeframe written for: any; momentary (HFT).
- Signal source: underlying price/volume across two or more related instruments. Maps to: NSE index options partly (the
  nearest NSE versions are index vs futures basis, index vs constituents, NIFTY vs BANKNIFTY relative value, or
  put-call parity; at HFT speed these close in seconds); MCX partly (MCX vs international gold/silver, spot vs future);
  crypto partly (cross-exchange or perp vs spot), needs stop and profit lock.
- Mechanical? no as written; the idea can be made mechanical only by choosing pairs and thresholds ourselves.
- Our research: nearest tests are negative. `HUNT_R72_CROSS_INDEX.md`: "C2 Laggard catch-up" (buy the index that has
  not yet broken out) lost -Rs 1,00,746 on 613 final trades (twin p 0.89, fail). `HUNT_H39.md`: heavyweight futures,
  basis and synthetic forward move with the index in the same minute; from 2 minutes ahead every |t| is below 2.5,
  and "even the index's own future does not lead the index by a tradeable amount"; h39 also says option and futures
  arbitrage closes any gap within seconds. `FINDINGS.md` (DIRECTION MODEL): futures basis rising was right
  50.4-51.6% of the time.

### 4. Momentum ignition (Sec. 3.1.4)
- Entry: not stated as a rule. Described as placing and cancelling many orders in one direction to set off a fast move
  that draws in other traders' algorithms.
- Stop / target / filters: not stated.
- Market and timeframe written for: any electronic market; seconds.
- Signal source: none for us; it is an order-placement tactic (regulators treat it as manipulation; the paper does not
  say this). We must never do it; at most we can avoid being its victim.
- Maps to: NSE no (and illegal; SEBI acts against spoofing and layering); MCX no; crypto no.
- Mechanical? n/a (not something we may run).
- Our research: `HUNT_R8_ORDER_FLOW_TRAPS.md` sec. 1.7 (momentum ignition and stop hunts): only 11 events in our
  tick data, too few to measure; our defensive rule is never to enter on the first tick through a level, and to need a
  1-minute close beyond it still holding 60 s later. `FINDINGS.md` R86 (big players, 0 of 23 pass): fading stop runs lost
  on all 6 level x index tests (295-804 trades).

### 5. Leveraging structural differences (Sec. 3.1.5)
- Entry: not stated. Described as getting market data before others through co-location or direct exchange feeds and
  trading on that head start.
- Stop / target / filters: not stated.
- Market and timeframe written for: any; microseconds.
- Signal source: latency edge, not a signal. Maps to: NSE no (we are on the slow side of this edge); MCX no; crypto no.
- Mechanical? n/a for us.
- Our research: `HUNT_R7_ORDER_FLOW.md` sec. 5 "Retail latency": phone to broker to exchange is hundreds of
  milliseconds to a second each way, so we can only use the slow tail (1-15 min). Not tested as a strategy.

### 6. Complex event processing (Sec. 3.1.6)
- Entry: not stated. Described vaguely as combining several strategies with different properties of currency prices
  to find opportunities.
- Stop / target / filters: not stated.
- Market and timeframe written for: FX; HFT.
- Signal source: none / discretionary as written (too vague to pin down). Maps to: not determinable.
- Mechanical? no.
- Our research: not tested by us as such. Nearest in spirit, combining many inputs: `FINDINGS.md` DIRECTION MODEL
  (35 inputs, 30,378 decision points): 50.9-51.7% right; and the Data Brain studies (direction not predictable, size is).

### 7. Percentage of Volume (PVOL / POV) algorithm (Sec. 3.2, item 1)
- Only named. Not described. (Common meaning, not the paper's: trade a fixed share of market volume.)
- Entry / stop / target / filters / timeframe: not stated. It is an execution method, not a signal.
- Signal source: n/a (execution). Maps to: NSE index options in principle for large exits; with our lot sizes it is not
  needed. MCX same. Crypto same.
- Mechanical? yes in principle, but the paper gives nothing to implement.
- Our research: not tested by us. `HUNT_H10.md` notes using limit orders sliced over minutes on exits in thin contracts.

### 8. TWAP algorithm (Sec. 3.2, item 2)
- Only named. (Common meaning: split an order evenly over time.)
- Entry / stop / target / filters: not stated. Execution only.
- Signal source: n/a. Maps to: NSE/MCX/crypto as an execution choice for multi-lot orders.
- Mechanical? yes in principle; no parameters given.
- Our research: `HUNT_H14.md` tested 9 entry execution policies; the only one that passed was E2, a marketable
  limit at the entry-bar price +0.5%, rest left for 3 minutes then cancelled, +Rs 288/day against a required +250.
  A pure TWAP slice was not tested by us.

### 9. VWAP algorithm (Sec. 3.2, item 3)
- Only named. (Common meaning: execute so the average price tracks the day's volume-weighted average.)
- Entry / stop / target / filters: not stated. Execution only.
- Signal source: n/a for the algorithm; the VWAP level itself is underlying price/volume.
- Maps to: NSE/MCX/crypto as an execution benchmark.
- Mechanical? yes in principle.
- Our research: we tested VWAP as a footprint of big funds' algorithms, not as our execution. `HUNT_R86_BIG_PLAYERS.md`
  V1: a 12:00 fade to VWAP when stretched by at least 0.25% on busy days lost -64,786 on 71 trades; midday stretches from
  VWAP tend to continue (1,465 days 2019-2024, per `FINDINGS.md`). `HUNT_R86C`: buying the VWAP dip on slow-buying days
  (C1) failed.

### 10. Implementation shortfall (IMSH) algorithm (Sec. 3.2, item 4)
- Only named. (Common meaning: minimise the gap between the decision price and the final fill price.)
- Everything else not stated. Execution only. Signal source n/a.
- Our research: we measure this gap implicitly in every study via slippage and fill models (e.g. `HUNT_H14.md` fill
  models M1/M2). No IMSH algorithm tested.

### 11. Adaptive algorithms (Sec. 3.2, item 5)
- Only named. Nothing else stated. Execution only. Not tested by us.

### 12. Liquidity-seeking algorithms (Sec. 3.2, item 6)
- Only named. (Common meaning: hunt for hidden or dark liquidity across venues.) Nothing else stated.
- Maps to: NSE no (one lit venue for index options, no dark pools, no true icebergs in F&O per
  `HUNT_R8_ORDER_FLOW_TRAPS.md` sec. 1.5). Not tested by us.

### 13. Newsreader algorithms (Sec. 3.2, item 7)
- Only named. (Common meaning: parse machine-readable news and trade on it in milliseconds.)
- Signal source: news (not one of our categories; closest is "none / discretionary" for us, since we have no low-latency
  news feed). Maps to: NSE/MCX/crypto only as a slow event filter, never as a speed race.
- Mechanical? yes for HFT firms; no for us.
- Our research: not tested by us as a news-speed strategy. Event days were tested as calendar filters on MCX
  (`FINDINGS.md` R83: 0 of 13 pass) and `BIG_PLAYERS_GUIDE.md` lists scheduled event calendars as data a retail bot can
  use.

### Also described (not strategies)
- AT traits (Sec. 2.1.2, from Gomber et al.): replaces the human trader, minimises market impact on large orders, aims at
  a benchmark, holding periods may be days to months, works an order over time and across markets.
- HFT traits (Sec. 2.2.2, SEC via Agarwal): very fast programs, co-location and direct feeds, very short holding periods
  with many small positions, many orders placed and cancelled, few or no overnight positions, own capital.
- Shared traits (Sec. 2.3): pre-designed decisions, professional users, real-time market data, automated order entry and
  management, no human intervention, direct market access.
- The authors' own definitions: AT is algorithms plus computers executing traders' strategies automatically through an
  electronic system; HFT is the subset of AT that does so at high speed without human intervention.

## Performance claims
- No profit, win-rate or return claim for any strategy. No evidence of any kind for any strategy's profitability.
- Literature summary (the cited authors' findings, not the paper's own tests): HFT narrows spreads and reduces adverse
  selection in large stocks (Hendershott, Jones, Menkveld 2011); one large HFT firm loses on inventory but earns on the
  spread, and its result is very sensitive to the cost of capital (Menkveld 2013); HFT trades with permanent price changes
  and against transitory errors (Brogaard et al. 2014); AT improves price efficiency in FX (Chaboud et al. 2014).
- Survey scores (THE TRADE 2017, buy-side rating their algorithm providers): execution consulting scored highest; market
  impact second at 5.89; anonymity third at 5.85. The scale and sample size are not given in the text.
- Reasons for using algorithms (THE TRADE): consistency of execution was top in 2017; 13.9% named reducing market impact;
  trading at speed fell from 7.1% in 2015 to 5.8% in 2017; internal crossing 7% in 2017, 4.4% in 2016 and 9.2% in 2015.
  No sample size is given.
- Historical: in the first three months of 2007 more than 82% of NYSE order volume reached the floor electronically
  (cited to Wikipedia).
- Conclusion claims that algorithms are free from fear and make markets fairer and safer: an opinion, no evidence given.

## Risk, sizing and psychology
- No position sizing, stop rules or risk limits at all.
- Risk is framed as market impact: AT exists to cut the price impact of large orders by working them through time and
  across venues.
- Psychology: the conclusion contrasts old manual trading, driven by hope and fear, with algorithms that execute
  without hesitation whatever the outcome. This matches our own reason for rules-based arms, but the paper gives no data.
- Menkveld's point (cited) that an HFT's result is very sensitive to cost assumptions echoes our finding that costs decide
  most of our rules.

## How it relates to our research
- Agree: the edge of the strategies named here lives at milliseconds to seconds and belongs to co-located firms. Our
  `HUNT_R7_ORDER_FLOW.md` reaches the same view and adds that our best 1-15 minute flow signal is 0.42x of cost;
  `BIG_PLAYERS_GUIDE.md` sec. 0 shows algo entities take almost all FPI/prop F&O profit in India.
- Already rejected (nearest form): relative-value catch-up between indices (`HUNT_R72_CROSS_INDEX.md` C2, -Rs 1,00,746 on
  613 trades); futures/heavyweight leads (`HUNT_H39.md`, nothing tradeable from 2 minutes ahead); fading to VWAP
  (`HUNT_R86_BIG_PLAYERS.md` V1, -64,786 on 71); fading stop runs (R86, all 6 tests lost).
- Defensive overlap: momentum ignition is covered by our trap rules in `HUNT_R8_ORDER_FLOW_TRAPS.md` sec. 1.7.
- Execution overlap: our only execution study, `HUNT_H14.md`, adopted a marketable limit at +0.5% (E2); TWAP, POV, IMSH
  and adaptive slicing were not tested.
- Not tested and not testable for us: market making, cross-venue liquidity providing, latency arbitrage, newsreader HFT.

## What is new to us
- A ready vocabulary of execution algorithm types (POV, TWAP, VWAP, implementation shortfall, adaptive, liquidity-seeking,
  newsreader). We have never measured implementation shortfall per trade (decision price vs fill) as a named metric in
  the app; that could be logged on paper at no risk.
- Sliced exits (TWAP or POV style) for multi-lot paper exits in thin strikes or MCX options were suggested in
  `HUNT_H10.md` but never tested against a single market order.
- THE TRADE survey's point that buy-side firms value consistency of execution and low market impact over speed: for a
  retail phone app, execution quality, not speed, is the lever we can still pull.
- Nothing in the paper is a new signal idea for NIFTY/BANKNIFTY direction.

## Could not read
- Figure 2 (average score by product feature, 2015-2017) and Figure 3 (reasons for using algorithms) are bar charts with
  no text layer; only the numbers quoted in the prose (5.89, 5.85, 13.9%, 7.1%, 5.8%, 7%, 4.4%, 9.2%) could be read.
- The Arabic abstract was not read separately; it mirrors the English abstract.
