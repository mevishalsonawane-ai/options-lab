# The Day Trader's Bible (Or... My Secrets of Day Trading in Stocks) - Richard D. Wyckoff (1919; this edition edited 1999-2001)

Source: wyckoff-day-traders-bible.txt, 116 pages, read in full on 25 Oct 2026 (text layer; no OCR file existed. The
figures have no text layer and could not be read: the swing chart in Ch. IV, the Harriman volume study in Ch. V, the
pendulum diagrams and the swing chart in Ch. VII, the Kansas City Southern chart in Ch. VII, the Amalgamated Copper
figure chart and the volume figure chart in Ch. VIII, the two triangles in Ch. XII and the whole trade table in
Ch. XIII. The prose around each figure was read).
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A 1919 manual on "tape reading": judging the next few minutes of a US stock from each printed trade (price and size).
Its core is volume: which side the big lots hit, how volume behaves on advances versus reactions, and heavy volume in a narrow band.
Prices follow the "line of least resistance"; the tape reader goes with the immediate trend and with the market's leaders.
Most of the book is trade management: stops at swing turning points, closing or reversing on the tape's cue, costs, and size.
It is openly discretionary: the author says no formula can capture it, and he mistrusts mechanical chart systems.

## How the book maps to our data (read this first)
- The book's raw material is the time-and-sales tape of single US stocks around 1906-1916: every trade with its size,
  plus the bid and ask. Its unit of thinking is "points" on stocks priced 50-180 dollars.
- **The NIFTY and BANKNIFTY cash indices have no volume of their own.** Every volume rule in the book can only be
  tested on index futures (or the index stocks' turnover). Our lake has true index-futures minute volume only from
  29 Jul 2026 (`HUNT_R9_VOLUME_GAMMA.md` sec. 1; `HUNT_R61B_TRAPS_ORDERFLOW.md`), and the phone's TickZoneRecorder
  (ticks, 5-level book, tick-rule delta, large prints, an absorption flag) has run only since 12 Oct 2026 and lives in
  the private IraAlgo repo. Most of our earlier "volume" tests used an ATM-option activity proxy that correlates only
  0.07-0.15 with true futures volume (`HUNT_R87_CANDLE_ANATOMY.md`, Data).
- So the book's central volume ideas are mostly **not yet testable at the scale they need**; its price-only ideas
  (follow the trend, swing stops, half-retracements, range breakouts, breadth) have been tested many times.
- Best fit outside the indices: MCX futures and crypto, which carry their own traded volume (crypto exchanges also
  publish the aggressor side of each trade). Whether our MCX lake holds per-minute volume needs checking: R89 and R90
  used the MCX near future close-only. Any crypto rule must carry a stop and a profit lock (CLAUDE.md).

## Methods, rules and setups

### 1. Reading the scales: which side the big lots trade on (Ch. V)
- Entry: when most of the large lots print at the offer (higher fraction) and the small lots at the bid, buying is
  stronger and the next print is more likely up (his example: 700 shares at the bid against 2,500 at the offer); go
  with that side. The reverse for selling.
- Stop: not stated for this signal (see method 9).
- Target / exit: not stated; close when the balance shifts.
- Filters: lot sizes are judged relative to the stock's normal activity ("no set rule"); trust the leaders most;
  never rely on one stock alone. A later section warns that rule changes against matched orders had made big-lot
  prints less reliable, so volume side alone is not tape reading.
- Market and timeframe written for: NYSE leaders, trade by trade (seconds to minutes).
- Signal source: underlying price/volume (aggressor-signed volume, i.e. order flow); maps to: NSE index options yes,
  via the index future's tick-rule signed volume, only from the phone's recordings; MCX yes (futures ticks); crypto yes
  (exchange trade feed with aggressor flag), with a stop and lock.
- Mechanical? partly (signed volume is mechanical; "relative size" is a judgement).
- Our research: `HUNT_R7_ORDER_FLOW.md`: order flow explains the same seconds well (5-level OFI correlation +0.81 to
  +0.90 in one 34-minute MCX sample) but leads little; the app's signed option flow leads the index (IC +0.109 at 1
  minute, +0.049 at 5) yet its edge is 0.42x of an option trade's cost. `HUNT_R9_VOLUME_GAMMA.md`: delta divergence
  (proxy) no. `HUNT_R12_TRADE_MANAGER.md`: order-flow rules cannot be tested on history; in the MCX sample the 10-second
  OFI barely predicted the next 60 seconds (correlation 0.05 / 0.08). True futures tick test: not yet done.

### 2. Price "reaching": gaps between consecutive prints (Ch. V)
- Entry: when successive prints skip fractions on the way up (buyers take the offer at ever higher prices instead of
  waiting), demand is urgent and sellers are thin: bullish. Skips on the way down show lack of support.
- Stop / exit / filters: not stated.
- Market and timeframe: NYSE leaders, tick by tick.
- Signal source: underlying price/volume (tick prints); maps to: NSE index futures only with tick data (the phone's
  recordings); MCX ticks; crypto trade feed. Not visible on 1-minute bars.
- Mechanical? yes (print-to-print price step relative to the tick size).
- Our research: not tested by us (needs ticks). `HUNT_R8_ORDER_FLOW_TRAPS.md` sec. 1.13 warns our retail snapshots
  merge trades (42-77% of updates) and resend old states, which would fake such skips.

### 3. Heavy volume in a narrow band: distribution at the top, absorption at a low (Ch. IV, V, VI, X)
- Entry: abnormally large volume traded within a small price range after an advance, while the rest of the market
  responds poorly, is "notice of a coming decline": prepare to sell short when the price starts to slip (Reading:
  some 80,000 shares within about half a point of 159; another time over 50,000 shares within three-quarters of a
  point, then a fall from 144 3/8). The mirror: very large volume after a big decline that the price will not give way
  to means someone is buying (Rock Island). A big seller meeting more bids than he can fill shows as repeated prints at
  one price and then a rally.
- Stop: short just under the heavy-volume top, stop just above it (his Reading short: stop 144 1/2-5/8 above a 144 3/8
  top).
- Target / exit: not stated; ride while the tape stays weak.
- Filters: compare volume with the stock's own normal and with the rest of the market; the leaders turn on 25,000-50,000
  shares.
- Market and timeframe: NYSE leaders, intraday to a few days.
- Signal source: underlying price/volume; maps to: NSE index futures (volume) or the index stocks' turnover, not the
  cash index; MCX futures yes; crypto yes (with stop and lock).
- Mechanical? partly ("abnormal" and "small radius" need thresholds).
- Our research: `HUNT_R87_CANDLE_ANATOMY.md`: absorption on NIFTY 5m (activity >= 2x usual, range <= 0.7 ATR, a proxy)
  went up 51.5% vs 51.5% matched (n 2,266, q 0.79). `HUNT_R8_ORDER_FLOW_TRAPS.md` sec. 1.5: levels with heavy volume
  that did not give way broke afterwards 58-68% of the time vs 55% for ordinary levels (one MCX evening).
  `HUNT_R86B_HOW_BIG_PLAYERS_TRADE.md`: absorption at the day's extreme was almost never seen (2 + 4 cases).
  `HUNT_R9_VOLUME_GAMMA.md`: absorption proxy, NIFTY +2-6 bp, q 0.29, 52 events. All proxies; a true-volume test is open.

### 4. Volume on advances versus reactions (Ch. V, IX, X)
- Entry: in a genuine bull move volume swells on the advances and dries up on reactions ("like the ocean at ebb
  tide"); stay long while that holds. A decline on heavy, active trading followed by a sluggish rally in small lots
  means liquidation: expect lower. A rally made of small lots is hollow; do not cover a short on it.
- Stop: not stated.
- Target / exit: exit when reactions start to carry more volume than advances, or a new high fails.
- Filters: the rest of the market must not oppose the move.
- Market and timeframe: stocks, intraday swings and multi-day swings.
- Signal source: underlying price/volume; maps to: index futures volume, MCX futures, crypto. Not the cash index.
- Mechanical? partly.
- Our research: not tested by us with true volume. `HUNT_R86B_HOW_BIG_PLAYERS_TRADE.md` found the related fact that big
  selling is fast and big buying slow (61% of the day's move by 10:15 on heavy FII selling days, 169 days; 40% on
  heavy buying days, 179), but following it early passed 0 of 8 rules.

### 5. Climax: a violent, wide move ends a long advance or decline (Ch. VI, XII)
- Entry: an exceptionally violent move after a long rise or fall usually marks its end; do not start a fresh short
  after a 15-point break; wait for the long side. Ch. XII: a move widens like a triangle, with volume and activity
  expanding until it ends.
- Stop / target: not stated.
- Filters: a prolonged move first.
- Market and timeframe: stocks; intraday and multi-day.
- Signal source: underlying price/volume; maps to: NSE index (range only) or futures (volume); MCX; crypto.
- Mechanical? partly.
- Our research: `HUNT_R87_CANDLE_ANATOMY.md`: climax bars (proxy) on NIFTY 5m went up 52.7% vs 51.1% matched (n 1,386,
  q 0.81); "reversal" shapes at trend extremes mostly marked continuation. `HUNT_R12_TRADE_MANAGER.md` tested a volume
  climax exit (VO) among its specialists; none passed on the profitable arms.

### 6. The line of least resistance: follow the immediate trend; avoid trendless markets (Ch. IV)
- Entry: trade only in the direction of the immediate trend, never against it. A market swinging inside a couple of
  points has no trend: stay out, because unless you catch the extremes you cannot pay costs.
- Stop: see method 9.
- Target / exit: let the profit run while strength grows; no fixed target.
- Filters: judge the force behind the start of a move; each risk should promise 2 to 5 points (method 18).
- Market and timeframe: stocks, intraday.
- Signal source: underlying price; maps to: NSE index options yes (index spot), MCX yes, crypto yes (stop and lock).
- Mechanical? no as written ("immediate trend" is judged); a literal form is "trade with the last N-bar direction".
- Our research: `DEEP_NIFTY_BANKNIFTY.md` (R102) sec. 2.5: the next NIFTY 5-minute bar keeps the previous bar's sign
  48.4% of the time (n 167,082); 1 of 70 sign rules confirmed and it was an artefact. `HUNT_F1_FACTORY.md`: 1-minute
  trend indicators lean slightly the wrong way over 15 minutes. Already rejected as a direction signal.

### 7. Range breakout out of a dull market (Ch. VII, X)
- Entry: after a stock has sat in a narrow range, draw lines at the high and low of that range and buy or sell when a
  line is crossed; the strongest cue is volume suddenly printing "in thousands instead of hundreds" out of a dead
  market at the offered prices. Go with the first sharp move. If the range was about 3 points and the price bursts out
  with new life, go with it.
- Stop: just inside the range (his examples use the last point of resistance).
- Target / exit: hold while volume and breadth keep expanding; a 1 to 1.5 point dip on light volume after a 3-point
  rise is a normal reaction, not a reversal.
- Filters: the break should come on large and increasing volume; a sudden volume burst inside the range starts a move
  "but not necessarily in the direction first indicated"; be wary if a burst of volume is packed into a small range
  (possible distribution, method 3).
- Market and timeframe: leaders, intraday and multi-day.
- Signal source: underlying price (and volume for the filter); maps to: NSE index options yes (range on the index, volume
  on the future), MCX yes, crypto yes (stop and lock).
- Mechanical? yes for the range break; the volume filter needs a threshold.
- Our research: the price-only part is close to what we trade. Donchian 20 is on paper (R51) and Breadth Donchian
  passed (`HUNT_R72_CROSS_INDEX.md`, method 8). The opening-range version lost everywhere (`HUNT_R92_ORB_TIME.md`, 0 of
  257). NR7 breakouts: MCX crude lead only (`HUNT_R96_SUMMARY.md`; FINDINGS R96: +Rs 48,875 on 46, q 0.161, not
  passed). Support/resistance levels are no better than random lines (`HUNT_R20B_SR_RANGE.md`); PDH/PDL breaks 0 of
  180 (`HUNT_R89_PDHL_BREAK.md`). `HUNT_R84_BREADTH_RISK.md` (post hoc): Breadth's best signals were NIFTY shock bars
  5-10x the usual size (+Rs 1.44 lakh on 142), calm bars lost. The true-volume filter is not tested by us.

### 8. The break that carries the market along; leaders and laggards (Ch. III, IV, V, VI, VIII)
- Entry: a break through resistance is best followed "especially if ... it carries the rest of the market along".
  Trade the leader of the strongest group, not low-priced laggards (while a laggard moves 5 points the leader may move
  10-15). A stock being marked up alone, without others following, usually does not go far.
- Stop / target: as in methods 9 and 15.
- Filters: if a secondary leader suddenly bursts into activity, the main leaders are probably being distributed under
  its cover ("indicators").
- Market and timeframe: groups of NYSE stocks, intraday.
- Signal source: underlying price (several underlyings at once); maps to: NSE indices yes (NIFTY, BANKNIFTY, FINNIFTY,
  MIDCPNIFTY, SENSEX; or the big index stocks), MCX partly (metals/energy groups), crypto partly (BTC as leader).
- Mechanical? yes for "most indices break together"; partly for "who leads".
- Our research: `HUNT_R72_CROSS_INDEX.md`: Breadth Donchian (a 20-bar break taken only when at least 4 of 5 indices
  break on the same 5-minute bar) made Rs 1,71,366 in the final window vs Rs 55,439 for plain Donchian (1,202 vs 1,787
  trades), twin p 0.008: agrees with the book. The laggard catch-up lost Rs 1,00,746 on 613; "leader at 10:15" lost
  Rs 30,969 on 55; the lonely breakout faded lost Rs 1,60,188 on 499. On MCX, breadth did not carry over
  (`HUNT_R93` in FINDINGS, 0 of 5). Secondary-leader-as-warning: not tested by us.

### 9. Stops at the "point of resistance" and raising them (Ch. IV, IX)
- Entry: buy near the low of a range after the low has held twice and the third dip stopped higher (the point of
  resistance raised); his example buys at 128 3/4.
- Stop: 1/4 point beyond the last point of resistance (127 7/8 under a 128 1/8 low). After the price crosses well
  above, raise the stop to cost plus commission; after each new high, raise it to 1/4 below the latest reaction low.
  Never move a stop to increase risk; no averaging down.
- Target / exit: no fixed target; ride higher tops and bottoms.
- Filters: initial risk under 1 point; the tape reader usually loses only half a point to a point.
- Market and timeframe: stocks, intraday.
- Signal source: underlying price; maps to: NSE index (swing levels on the index, stop on the index or translated to
  the premium), MCX, crypto (it is a stop-and-trail by design).
- Mechanical? yes (swing lows from a fixed swing definition).
- Our research: stops at chart swings were part of R89 PDHL (0 of 180) and R90 BOS (0 of 99, "the loss is slow, not
  whipsaw"). Breakeven locks on Liquidity: `HUNT_R19_LIQUIDITY_LOCK.md` 0 of 12 passed; R18's "breakeven after +15%"
  overlay failed (`HUNT_R18_EXITS.md`). A swing-low trail on the arms with a record: not tested by us.

### 10. The automatic (fixed-distance) trailing stop (Ch. IV)
- Entry: any.
- Stop: start 1 point away; for every 1/4 point in your favour move it to stay exactly 1 point from the extreme; once
  it covers commissions, stop trailing mechanically and let the market show the exit.
- Target / exit: the trail or the tape.
- Filters: none. The author himself prefers mental stops and judgement; automatic stops "interfere with judgement".
- Market and timeframe: stocks, intraday.
- Signal source: underlying price (or option premium if the trail is put on the premium, which CLAUDE.md allows);
  maps to: NSE index options yes, MCX yes, crypto yes (this is the profit lock CLAUDE.md requires).
- Mechanical? yes.
- Our research: fixed give-back trails hurt our profitable arms: Rs 500 give-back on Liquidity (`HUNT_R19B_LIQUIDITY_TRAIL.md`,
  holdout 15m +Rs 4,584 vs +Rs 14,633 plain, 27 trades); `HUNT_R100` give-back protection 0 of 15 (FINDINGS);
  LIQUIDITY_TRAIL.md halves the average winner.

### 11. A fixed maximum stop when away from the tape (Ch. IV)
- Rule: whenever you leave the screen, the feed fails, or you hold overnight, put a real stop in; the consensus of
  experienced traders is at most 2 points gross loss per trade. When watching, use mental stops and market orders.
- Signal source: none (risk rule); maps to all markets. Our app already keeps live stops resting at the broker where
  possible (CLAUDE.md "open positions first").
- Mechanical? yes.
- Our research: not tested by us as such (a safety rule, not an edge).

### 12. Close the trade, and usually reverse, when the tape says so (Ch. IV, VII)
- Rule: close (1) when the tape says so, (2) when the stop is hit, (3) when the position is unclear, (4) when a large
  profit can be redeployed. In a broad, swinging market the moment to close is usually the moment to reverse; his
  "double stop" (stop order for twice the size) closes and reverses at once.
- Signal source: underlying price; maps to: NSE index options (close the call, buy the put), MCX, crypto.
- Mechanical? partly (the double stop is mechanical; "the tape says so" is not).
- Our research: stop-and-reverse not tested by us. `HUNT_R14` (FINDINGS): re-entering ORB on every bar made about Rs 0
  before charges and charges were 36% of the winners' gross; cap re-entries. A reversing system pays twice the costs.

### 13. Exit cues from other stocks: the market as one body (Ch. III, IV, VI)
- Rule: a long in one leader is closed when other leaders fail (his example: heavy volume stalls Reading, Union shows
  poor support, New York Central slides on big lots, then Consolidated Gas breaks; he exits Union and shorts Reading).
  A heavily held stock that cannot rally while the leader rallies drags the market down ("can Union lift Steel?").
- Signal source: underlying price/volume of several instruments; maps to: NSE indices and the big index stocks (e.g.
  the five big banks are 62.98% of BANKNIFTY, `DEEP_NIFTY_BANKNIFTY.md`), MCX groups, crypto (BTC vs ETH).
- Mechanical? partly (a cross-index exit can be made mechanical).
- Our research: cross-index exits: not tested by us. SMT divergence at the day's extreme (`HUNT_R72_CROSS_INDEX.md` C6)
  failed (lost in design, top 5 trades larger than the net).

### 14. Response to stimulus and news: strength under pressure (Ch. I, VI, VII)
- Rule: judge a stock by what it does and does not do at critical points. A stock that loses little when a leader
  breaks 3 points is in strong hands; a stock that fails to break on bad news is being bought by insiders; a market
  that cannot hold rallies or ignores good news is weak. The tape shows news before the news ticker.
- Signal source: underlying price; maps to: NSE indices (e.g. NIFTY versus BANKNIFTY after a shock, behaviour on
  event days), MCX, crypto.
- Mechanical? no (which news is "bad" and what counts as "not breaking" are judgements).
- Our research: not tested by us as written. Nearest: `HUNT_R86C_CATCH_THEM.md`: after a gap down NIFTY tends to bounce
  from 09:45 while BANKNIFTY keeps falling when the gap is not recovered (45 days); R102 found US-event and gap-fade
  rules never passed.

### 15. The "normal" rally: half to two-thirds of the decline (Ch. VI, IX, X)
- Rule: after a decline, a normal rally recovers one half to two thirds of it; if the rally falls short, the decline is
  not over (keep the short); if all of it and more is recovered in a bull market, the rise continues. His example: a
  2 5/8 point drop due a 1 1/4 point rally, which failed. A short at 53 that breaks to 51 should have its stop at 51 1/4.
- Signal source: underlying price; maps to: NSE index, MCX, crypto.
- Mechanical? yes (swings from a fixed swing rule).
- Our research: `HUNT_R4_GANN_FIB_MAGIC.md`: swing retracement levels (38.2-78.6%) turn NIFTY 49.0% of the time vs 49.1%
  for shifted ratios (25,000+ touches); the Gann 50% rule shows no extra turns. Already rejected as a level.

### 16. Do not chase a prolonged swing (Ch. VI)
- Rule: the market seldom runs more than three or four days one way without a reaction; the odds fall as a swing
  lengthens; in a bull market reactions of 2-5 points are normal, 10-15 point drops occasional.
- Signal source: underlying price (daily); maps to: NSE index daily bars since 2006, MCX, crypto.
- Mechanical? yes (count consecutive days).
- Our research: not tested by us as a streak rule. R102: daily returns follow the previous day's sign 53.2% of the time
  (NIFTY, n 5,086), and the 2006-2026 daily momentum reversed in the holdout.

### 17. Dull markets lead to big swings (Ch. VII)
- Rule: dullness means the forces have balanced; it closes one chapter and a pronounced swing follows. Gradual
  hardening, failed bear raids and no decline on bad news point up; failed rallies point down. Watch dull markets for
  the start; scalping inside them does not pay.
- Signal source: underlying price (range compression); maps to: NSE index, MCX, crypto.
- Mechanical? partly (compression is mechanical; the direction clues are not).
- Our research: agrees on size, not direction: `HUNT_R16_PREDICTABILITY.md` (only the size of the next 30-60 minutes is
  predictable, direction AUC 0.509-0.536); `HUNT_R88` (FINDINGS: next-candle size predictable, direction 0 of 8).

### 18. Costs and the "invisible eighth" (Ch. IV, IX)
- Rule: every trade pays commission, tax, fees and the bid-ask spread (the "invisible eighth"), so add them to the
  entry price at once; with costs near half a point a trade, each risk should promise 2 to 5 points; do not scalp
  fractions in dull markets. Use market orders in active leaders; limit orders lose more than they save.
- Signal source: none (execution); maps to all.
- Our research: agrees. Costs decide most of our tests: about Rs 70 a NIFTY round trip and Rs 76 BANKNIFTY (R102 in
  FINDINGS); `HUNT_R89_SIZE_STRADDLE` gross about zero, costs make it lose.

### 19. Exit at the culmination of the immediate move; re-enter on the reaction (Ch. XII)
- Rule: close when the small move you entered for culminates (for example a "spectacular" bid that looks like bait),
  then buy again on the normal reaction if the tape is still bullish. He claims more small profits this way than by
  sitting through reactions.
- Signal source: underlying price (and order book); maps to: NSE index options, MCX, crypto.
- Mechanical? no ("culmination" is judged).
- Our research: exits that take profit early made our profitable arms worse (`HUNT_R12_TRADE_MANAGER.md`; R18: every
  overlay worsened Liquidity, +Rs 96.6k on 336 design trades with own exits). Contradicts the book for our arms.

### 20. Abandon the trade if it does not act right at once (Ch. X, XI)
- Rule: after entry the stock must act up to expectations or be closed immediately; in a bull move volume must rise,
  reactions must be on lighter volume and each upswing must make a new high.
- Signal source: underlying price/volume (or premium progress); maps to: NSE index options, MCX, crypto.
- Mechanical? partly; a literal form is a "no progress" time stop.
- Our research: `HUNT_R12_TRADE_MANAGER.md`: the "no progress" exit (out if the option never gains 3% within 20
  minutes, plus a 12% stop) beat random exits on ORB Fresh, +Rs 14,365 over 474 holdout trades, twin p 0.001, but ORB
  Fresh still lost and the rule did not help Liquidity. Weak support.

### 21. Wait for a defined trend, or the first reaction (Ch. IX, X)
- Rule: do not buy on the first sign of support; a stock holding within half a point is a halting place, not a turn.
  Either get in at the start of a move or wait for its first reaction and judge how it acts there.
- Signal source: underlying price; maps to: NSE index, MCX, crypto.
- Mechanical? partly.
- Our research: `HUNT_R73_DONCHIAN_TREND_WAIT.md`: "wait 5 candles and the break holds" lost Rs 1,26,378 in design;
  `HUNT_R20C_SR_BREAK.md`: the 2nd confirming candle costs 14-18 points on 5m/15m; `HUNT_R89_PDHL_BREAK.md`: break, then a
  same-colour candle, then entry, no better than a random minute. Already rejected.

### 22. Late-day stop squeeze and profit protection (Ch. X)
- Rule: if you close everything daily, tighten the stop in the last fifteen minutes and on a sharp move in your favour
  so it sits only a fraction from the extreme; once a point of profit exists, never let it become a loss.
- Signal source: underlying price (or premium); maps to: NSE index options (before the 15:05 expiry / 15:15 square-off),
  MCX, crypto (this is a profit lock).
- Mechanical? yes.
- Our research: locks on Liquidity 0 of 12 (R19) and 0 of 15 (R100); `PROFIT_LOCK.md` ladders cut ORB's loss but did not
  make it profitable. Late-day squeeze alone: not tested by us.

### 23. Pyramiding a confirmed move (Ch. VII, I)
- Rule: when a move is "emphasised", add (his Kansas City Southern example buys at 44, 45 and 46), keep one stop just
  behind the average cost and raise it.
- Signal source: underlying price; maps to: NSE index options (add lots), MCX, crypto.
- Mechanical? yes.
- Our research: `HUNT_R84_BREADTH_RISK.md` (positions per signal, 0 of 8) and R101 (2 lots on 5-of-5 breadth earned what
  any extra lot earns, Rs 161 a lot vs random 160). Pyramiding into a move: not tested by us.

### 24. Accumulation, mark-up, distribution, and the figure chart (Ch. V, VII, VIII, XIV)
- Rule: a manipulated campaign has three parts (accumulate, mark up, distribute; for a short campaign the order is
  reversed). Shakeouts during accumulation mean insiders want the stock: buy. A point-and-figure ("figure") chart shows
  long rows at one level (accumulation or distribution) and rising bottoms before the mark-up; "the chart gives the
  direction, the tape says when". Longer preparation means a bigger move (Ch. XIV).
- Signal source: underlying price/volume; maps to: NSE index daily or intraday, MCX, crypto.
- Mechanical? partly (P&F is mechanical; reading a campaign is not).
- Our research: `HUNT_H37.md` tested Wyckoff spring / upthrust (680 variants, 187,607 trades, Rs -160 a trade net, -11
  vs random) and Point & Figure (850 variants, Rs -111 net) as option-buying triggers: no. `HUNT_R87_CANDLE_ANATOMY.md`:
  NIFTY 1-minute pokes below the day's low that close back bounced +0.195 ATR out of sample (n 1,702), worth 1.6-1.8
  points, below an option's cost. `HUNT_R86` (FINDINGS): fading stop runs lost on all 6 level x index tests.

### 25. Composite trend chart of ten leaders (Ch. VIII)
- Rule: for 5-10 point swing trading, keep a chart of the average high and low of ten leading stocks (noting which came
  first) and take no trade against its trend.
- Signal source: underlying price (an equal-weight basket); maps to: NSE (a basket of the big index stocks or the five
  indices), MCX groups, crypto (top coins).
- Mechanical? yes.
- Our research: trend filters: R73's Supertrend filter improved Donchian (final Rs 1,34,743 vs Rs 81,845) but added
  nothing on top of Breadth; R89's daily + 15m trend filter did not cut false breaks; R72 C5 "breadth thrust" lost.
  This exact basket: not tested by us.

### 26. Day trading flat at the close; a separate long-pull lot (Ch. I, IX)
- Rule: the pure tape reader ends each day flat because news can strike overnight; overnight holds need a stop. A tape
  reader may add an extra lot at major turns for the long swing, kept apart from daily trading, and close it if it
  clouds judgement.
- Signal source: none (holding rule); maps to: NSE index options (intraday arms already flat), MCX, crypto.
- Our research: R102: NIFTY's drift is overnight (close-to-close +0.051% a day vs open-to-close -0.035%), so being flat
  at night gives up the drift but also the gap risk. The long-pull lot: not tested by us.

### Discretionary ideas (listed, not made into rules)
- Choose high-priced, active, widely held leaders with definable trends; concentrate on one or two (Ch. II).
- Study each stock's "habits" and character (Ch. II).
- Do not be long one stock and short another at once; it clouds judgement (Ch. X).
- Who is behind a group (the big banking houses) tells you how far a campaign will go (Ch. III).
- The dull-market range scalp with double stops: the author describes it and then says he cannot recommend it (Ch. VII).
- Use a broker who reports quickly and can tell you the depth (Ch. II, XI).

## Performance claims
- Jesse Livermore traded only from the tape, flat each night, and won three trades out of five; he turned a $5,000 loan
  into over a million dollars short in the 1907 panic. No record given.
- Joe Manning built over $100,000 of working capital from ten-share lots by tape reading. Anecdote.
- About 90% of stock market disasters come from overtrading (stated as an estimate, no data).
- A $150 stock moves about 2 1/2 points a day vs one point for a $50 stock ("looking over the records"; no table).
- Compounding table (Ch. XI): 1/8 point profit a day on one trade a day, adding 100 shares per $1,000 gained, turns
  $1,000 into $12,137.50 gross and $10,195.50 net in 250 days, and then $46,800 a year at 1,200 shares. Explicitly an
  arithmetic illustration, not a record.
- One day's Union Pacific trading (Ch. IX): caught twice on the wrong side, still $102.50 net after $135 of costs;
  $421 with better judgement (hindsight).
- Kansas City Southern pyramid: $406.50 net on 100-share units, $2,032.50 "on 50-share units" (the unit sizes look
  swapped in this edition).
- A Reading short covered "within 1/4 of the bottom", netting 2 5/8 points after commissions.
- One day of three trades: the price went his way at once on 5 of 6 orders; he says he manages this only occasionally.
- A West Coast reader made 42 ten-share trades in two months, largest loss 1 1/2 points, largest gain 3, and was ahead
  after costs. Second-hand.
- A scalper went from about -$20 a trade to +$12-30 a day per 100 shares; medium traders should aim for $150-350 per
  1,000 shares.
- Two days in one stock (Ch. XIII): 15 trades, 13 winners, 1 loser, 1 flat on prices alone (7 long, 8 short, a 3 5/8
  point range). The trade table itself is missing from this copy.
- Ch. XIV: applying the method to 5-20 point swings gave results "highly outstanding". No numbers.
- Against charts: he has never met anyone who made money trading any kind of chart over an extended period.
Evidence: all anecdotes, hindsight examples or arithmetic. No costs-included record across many trades is shown.
The author does say his tape illustrations were taken as they printed, before he knew the outcome.

## Risk, sizing and psychology
- Start tiny (10-50 shares); measure progress in points, not dollars; keep a book of every trade with costs and a
  running total of points, and chart it so you cannot fool yourself.
- Have capital you can afford to lose (he suggests $5,000 per 50-share unit at the start, or at least $1,000 to test
  yourself); never depend on trading profits for living money.
- Overtrading is the main cause of ruin: reduce size as capital shrinks; after a run of losses cut size to a half or a
  quarter (or ten shares) and stop to find the cause.
- The 7 commandments (Ch. XI): do not overtrade; eliminate anxiety; do not trade when the market is not acting right;
  get a broker you can trust; do not leave orders to the broker's discretion; stay calm after losses; stay physically
  and mentally fit.
- Never increase the original risk, never average, act at once on a clear signal (hesitation is fatal, more so in
  closing than opening), and work alone, away from tips and gossip.
- Paper trading proves little: test with real money in small size. Yet, on charts, he proposes a blind forward test:
  have a friend prepare an unnamed chart, write fixed rules, cover the chart and slide the paper right one step at a
  time, recording every order. That is our walk-forward / locked holdout in 1919 form.
- Grow the unit only as capital grows (100 shares per $1,000 in his table).

## How it relates to our research
- **Agrees, and we have it:** a break joined by the rest of the market is worth following (R72 Breadth Donchian, our one
  cross-index pass); costs decide (every hunt); grow size only with capital and cut it after losses (CLAUDE.md account
  stop rule: -Rs 40k drawdown or -Rs 13k a month); test rules blind and forward (our pre-registration and holdout).
- **Already rejected by us (price-only forms):** "go with the immediate trend" as a bar-to-bar rule (R102, F1);
  half-retracement levels (R4, h37); support/resistance points as levels (R20b, R20c, R89); waiting for confirmation
  (R73, R20c); following leaders or laggards in time (R72 C2, C4); stop-run / shakeout fades (R86, R87); P&F and
  spring/upthrust triggers (h37); fixed give-back trails and breakeven locks on our profitable arms (R19, R19b, R100);
  early profit-taking exits (R12, R18).
- **Proxy-only so far (not a real test):** absorption, climax, heavy-volume wicks and delta divergence were tested on
  an ATM-option activity proxy that barely tracks futures volume (R9, R61b, R87). The book's real claims about volume
  need index-futures or tick volume, which we now record but have not tested (R7, R8 set up the pipe; R61b says about
  3 months of TickZoneRecorder data before a design read).
- **Partly supported:** the "act right at once or get out" idea (R12's no-progress exit beat random exits on ORB Fresh
  only); dull markets precede big moves (size is predictable in R16 and R88, direction is not).
- **Contradicts our data:** the book's bias to cut winners at the "culmination" and to protect every point; on our arms
  with a record the profit comes from a few runners, and early exits and locks cut them (R12, R18, R19).

## What is new to us
- **Volume on advances versus volume on reactions** (method 4) with true futures volume: never tested.
- **Heavy volume in a narrow band near a new high as a short setup** (method 3) with true futures volume, and its
  mirror at lows: only proxies tested.
- **Volume-confirmed breakout of a quiet range** (method 7): our breakout tests used price only; "volume in thousands
  instead of hundreds" as the filter is untested, and it may sort false breaks the way breadth did in R72.
- **Cross-index exit cue** (method 13): close an index trade when the other indices turn against it; we only tested
  breadth for entries.
- **Price "reaching" between ticks** (method 2) and print-to-print gaps: needs the phone's tick recordings.
- **Secondary-leader burst as a warning** that leaders are being sold (method 8): untested; on NSE it could be a mid-cap
  index or a non-bank sector suddenly leading while BANKNIFTY stalls.
- **Streak exhaustion** (method 16): no 3-4 day streak rule tested on daily bars since 2006.
- **Response-to-news test** (method 14) on scheduled event days: untested as written.

## Could not read
- Figures with no text layer: the point-of-resistance chart (Ch. IV), the Harriman Union Pacific volume study (39,300
  shares at 149 3/4-150; Ch. V), the pendulum and "dull market" diagrams and the 181 swing chart (Ch. VII), the Kansas
  City Southern chart (Ch. VII), the Amalgamated Copper figure chart and the volume figure chart (Ch. VIII), the two
  triangle diagrams (Ch. XII) and the full trade record table of Ch. XIII. The surrounding prose was read for each.

## R104 results (25 Oct 2026)

Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in `research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:

- `book-wyckoff-01`: not testable (R104): index-futures tick prints exist only in the phone's recorder since 12 Oct 2026 (private repo); ~3 months needed
- `book-wyckoff-02`: tested (R104): MCXI: 26 trades, win 35%, net -Rs 36,075 design, holdout -Rs 180 (fail)
- `book-wyckoff-03`: not testable (R104): index only; needs index-futures minute volume, which exists only from 29 Jul 2026 (57 sessions, too few for design + holdout)
- `book-wyckoff-04`: tested (R104): MCXI: 90 trades, win 14%, net -Rs 7,055 design, holdout -Rs 1,128 (fail)
- `book-wyckoff-05`: not re-run (R104): covered: is the R72 Breadth Donchian arm: PASS (final +Rs 1,71,366 on 1,202 trades), on paper
- `book-wyckoff-06`: tested (R104): ARM: delta vs the arm -Rs 14,978 design (fail)
- `book-wyckoff-07`: tested (R104): IDX: 2057 trades, win 48%, net -Rs 300,751 design, holdout -Rs 57,198 (fail)
- `book-wyckoff-08`: tested (R104): ARM: delta vs the arm Rs 29,056 design (fail)
- `book-wyckoff-09`: tested (R104): ARM: delta vs the arm -Rs 269,502 design (fail)
- `book-wyckoff-10`: tested (R104): ARM: delta vs the arm -Rs 163,167 design (fail)
- `book-wyckoff-11`: tested (R104): ARM: delta vs the arm -Rs 130,016 design (fail)
- `book-wyckoff-12`: tested (R104): ARM: delta vs the arm -Rs 439 design (fail)
- `book-wyckoff-13`: tested (R104): ARM: delta vs the arm Rs 4,081 design (fail)
