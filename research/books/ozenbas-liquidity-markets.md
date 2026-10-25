# Liquidity, Markets and Trading in Action - Deniz Ozenbas, Michael S. Pagano, Robert A. Schwartz, Bruce W. Weber (2022)

Source: ozenbas-liquidity-markets.txt, 111 pages, read in full on 25 Oct 2026 (text layer for every page; the OCR file
only adds the series name on pages 2-3). The charts and screenshots (Exhibits 1.1-1.11, 3.1-3.7, 5.1-5.8, Figs 4.3-4.8)
have no text layer, so only their captions and the surrounding text were read; the tables (Exhibits 2.1-2.5, Fig 4.7)
were readable.
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A short, open-access textbook on market microstructure for business-school courses, built around the TraderEx simulator.
It argues that real markets have friction (fees, spreads, market impact, divergent opinions), so trade prices wander
around an unseen equilibrium value and must be discovered by trading.
It explains order types, the limit order book, call auctions, dealer markets, dark pools and the cost of trading.
It links liquidity to macro news shocks (Fed statements, a jobs report, COVID) and to trading technology and rules.
It is a concepts book: it gives almost no mechanical trading rules, and it says plainly that intraday patterns are hard
to exploit.

Honest summary for Boss: this book teaches **how orders become trades and what that costs**, not when to buy. Its value
for IraAlgo is in execution (order type, timing, size, measuring slippage) and in explaining why our direction tests keep
coming out as coin flips. The one mechanical entry rule it shows (a moving-average crossover) is given only as an example
of what technical traders do, and the authors say they neither support nor deny it.

## Methods, rules and setups

Most items below are concepts, not trade setups. For each one the "practical lesson" line says what it means for an NSE
index-option buyer.

### 1. Friction and the "one order" constraint (Ch. 1, sections 1.7-1.9)
- Entry / Stop / Target: not applicable (theory).
- The idea: with no friction, everyone's full buy and sell curves meet at one equilibrium price P* and nobody wants to
  re-trade. Once each trader may send just one price and one size, the opening price equals P* only by luck; prices
  then wander around P* (the Gulf Stream vs surface-waves picture).
- Market and timeframe written for: US equities, the 9:30 open.
- Signal source: none / discretionary; maps to NSE, MCX, crypto as background only.
- Mechanical? no.
- Practical lesson: an index print at any minute is a noisy estimate of value, not value itself; the first prints after
  an auction are the noisiest.
- Our research: consistent with R16 (direction not predictable, holdout AUC 0.509-0.536) and R102 (1 of 70 sign rules
  confirmed, an artefact).

### 2. Order types: market vs limit, and the rest (Ch. 2.1, 5.3)
- Market order = take liquidity now, pay the spread. Limit order = provide liquidity, save the spread, but risk (a) no
  fill (non-execution risk) and (b) being "picked off" when news arrives before you cancel. Also described: stop order
  (becomes active when a trade prints at or through the stop; the book's example is a stop-limit: stop at $30, limit $29),
  not-held orders, pegged orders, iceberg orders, fill-or-kill, all-or-nothing. Orders with special conditions, hidden
  size and orders kept "in the pocket" form latent liquidity.
- Stop / Target: not stated.
- Market and timeframe: US equities, any.
- Signal source: none (execution choice); maps to NSE options, MCX and crypto directly.
- Mechanical? partly (the choice can be made a rule).
- Practical lesson for NSE options: (1) a plain market order on a monthly option pays the half-spread every time; a
  marketable limit with a cap pays at most the cap and refuses bad fills. (2) A stop-limit (SL) with a tight limit can
  be jumped over in an air pocket and never fill: that is the book's non-execution risk applied to a stop. Where the
  broker allows only SL for options, leave room between trigger and limit, and keep the broker-side SL/GTT that
  CLAUDE.md requires. (3) Iceberg/disclosed quantity is not allowed for NSE F&O; a broker "iceberg" just slices the order
  into fully visible legs (R8 section 1.5).
- Our research: h14 tested exactly this choice on the Liquidity arm: a marketable limit at the entry-bar price +0.5%,
  resting 3 minutes, never chasing, was the only one of the 9 policies to pass every gate (+Rs 288/day against a required
  +250 at the plan size; holdout +Rs 625/day at kappa 0.02). It works by refusing fills above about 0.5%. Not robust at
  kappa 0.04. Correction (app audit, 25 Oct): the app's Liquidity arm does NOT use this entry yet; it sends a plain
  market order (research/APP_AUDIT_BOOKS.md). The capped limit is a proposal for Boss.

### 3. Trading costs: explicit, implicit, implementation shortfall (Ch. 2.2)
- Explicit costs: commissions, fees, taxes. Implicit: the bid-ask spread (the price of immediacy; for a market-order
  trader the full spread is the round-trip cost), opportunity cost (a limit order that never fills while price runs
  away) and market impact (walking the book, or slicing that sets off a short trend, or others jumping on your signal).
- Implementation shortfall (IS) = actual fill price after all charges minus a benchmark, usually the mid-quote when the
  decision was made.
- Signal source: none (measurement); maps to all markets.
- Mechanical? yes as a measurement.
- Practical lesson: score every IraAlgo fill against the mid at the signal second. That is exactly the log h24 asked the
  app to keep (bid, ask and sizes at the signal, at order placement, at each fill, at the exit).
- Our research: our cost baselines already split costs this way. Charges: about Rs 70 of the Rs 124 a random entry
  loses per trade at 1 lot (OBUY_FINAL). Spread: real half-spread 0.16% of premium for 1-ITM/ATM nearest-monthly NIFTY
  and BANKNIFTY, 0.21% MIDCPNIFTY, 0.42% FINNIFTY, from one snapshot at 11:02-11:06 IST on 2026-10-06 (HUNT_H24 section 1;
  research/knowledge/baselines.yaml). All-in ATM round trip about Rs 98 NIFTY and Rs 133 BANKNIFTY (FINDINGS, R16). R7:
  a BANKNIFTY ATM round trip is about 0.7% of premium, about 2 bp of the index; MCX crude/gas options about 5 bp of the
  future. Opportunity cost is measured too: h14's limit missed 29 trades that would have netted -Rs 14k, so missing them
  cost nothing. The signal-time spread is unknown; it "may be up to about 2x the calm level" (h24).

### 4. Information shocks vs liquidity shocks (Ch. 2.3, 3 intro)
- Book's claim: news (an information shock) moves the equilibrium value and the price does not come back; a trade driven
  by one person's cash needs, reassessment or algorithm (a liquidity shock) pushes the price away from equilibrium and it
  reverts. Information shocks are correlated across traders; liquidity shocks are independent.
- Entry / Stop / Target: not stated. The implied rule: fade moves that are liquidity shocks, follow information shocks.
- Signal source: underlying price (plus a news calendar); maps to NSE indices, MCX and crypto in principle.
- Mechanical? partly: telling the two apart in real time is the hard part, and the book gives no test.
- Practical lesson: a sharp move on a scheduled event (RBI, Budget, CPI/FOMC for MCX) is more likely to be news that
  stays; a spike on nothing may give some back, but that is a hypothesis, not a rule.
- Our research: the fade side has failed repeatedly. R86 stop runs: fading a failed poke beyond yesterday's high/low, a
  round number or the opening range lost on all 6 level x index tests (295-804 trades, Rs -2.3 to -5.5 lakh, 2022-24);
  the index did not reverse more than at a random minute. R86 VWAP: midday stretches from VWAP tend to continue; the fade
  to VWAP on busy days lost Rs 64,786 on 71 trades. The follow side: h28 found event days move more (Budget days 2.0-2.4x
  range, RBI days 1.6x) but 8,820 variants found no side (SPA p = 1.00). h27: overnight cues are priced into the gap;
  nothing left after 09:20.

### 5. Price discovery, quantity discovery and divergent expectations (Ch. 2.4, 3.3)
- Book's claim: because investors read huge, messy information differently, there is no single "intrinsic value";
  price must be found by trading. A two-group model (bulls valuing $30, bears $20, share k bulls) puts the price at
  k x 30 + (1-k) x 20, and k is learnt only from the orders as they arrive, so discovery can take days. Large traders
  hide size (slicing, dark pools), so quantity discovery is also hard.
- Signal source: none / discretionary.
- Mechanical? no.
- Practical lesson: after news, the first move is fast and the settling is slow and two-sided; a phone-speed trader
  cannot be early in the fast part.
- Our research: R86b found "big selling is fast, big buying is slow": on FIIs' heaviest index-futures selling days
  NIFTY made about 61% of its move by 10:15 (169 days), on their heaviest buying days 40% (179 days); but 0 of 8 follow
  rules passed. R86c/R86d: a seller detector ranks heavy FII-selling mornings (AUC 0.72-0.74 unseen) yet shorting them
  lost (-Rs 10,477 on 23 trades). Recognising discovery in progress did not pay.

### 6. Accentuated intraday volatility, and the opening half hour (Ch. 2.6)
- Book's evidence: Exhibit 2.1, S&P 500 stocks in 2019, variances scaled to the same interval: opening half hour vs
  day 4.36 (top 50 stocks 3.50), closing half hour vs day 1.82 (1.40), day vs month 1.31 (1.35). In a frictionless
  market all would be 1. Scaling factors used: 13 half-hours a day, 21 days a month. The open is highest because it is
  a price-discovery period after the overnight close.
- Signal source: underlying price (a description, not a signal).
- Mechanical? yes as a measurement.
- Practical lesson: the open is where the price jumps and spreads are widest; any cost model must be time-of-day aware.
- Our research: agrees in shape. MARKET_HOW section 3a: NIFTY's mean absolute 1-minute move in 09:15-09:30 is 2.02x
  the day's average minute (BANKNIFTY 2.06x), 11:00-13:00 about 0.84-0.85x; the very first minute is about 6x. Per rupee
  of cost, the open gives about 2x the movement of midday (MARKET_HOW 5a). R80: removing entries before 10:15 cost the
  Liquidity arm Rs 90-93k in design, so the morning is where our one real edge earns. We have not measured the variance
  ratio itself (open variance x 13 / daily variance) on NSE; easy to add.

### 7. Liquidity: depth, breadth, resiliency; revealed vs latent (Ch. 2.7)
- Book's claim: depth = size near the best prices; breadth = orders at many nearby prices; resiliency = how fast price
  recovers after a liquidity shock, which depends on unseen (latent) liquidity. Posted liquidity can be measured; latent
  liquidity cannot, but illiquidity leaves a footprint: extra short-term volatility.
- Signal source: order book (not one of our default sources; closest is option chain as context).
- Mechanical? partly.
- Practical lesson: the 5 levels a broker shows are a small, temporary slice; do not read size on screen as a promise.
- Our research: R8 (one MCX evening, 217,517 packets): about half of size added to the 5 visible levels was cancelled
  within 3-5 seconds without a trade; "walls" (2x normal size) mostly lived about 1 second and ended pulled 45-50% of the
  time, traded 5-12%; total buy/sell quantity is mostly far-away orders (the 5 levels hold a median of about 3% of it).
  R8's rule: weight zero on totals, size counts only after it persists. h24: top-of-book quantity was 1-11 lots for
  NIFTY and 1-5 lots for BANKNIFTY at the calm snapshot.

### 8. The limit order book: spread, air pockets, the "pull of certainty", walking the book (Ch. 2.8, 2.9.1)
- Book's example (Exhibit 2.2): best bid $24.49, best ask $24.51, spread 2 cents with a 1-cent tick. A price level with
  no orders inside the book is an "air pocket" (a thin book). The empty $24.50 inside the spread is not an air pocket:
  when one more tick buys certainty, buyers prefer a market order (the "pull of certainty"), which keeps spreads wider
  than one tick. A market buy of 5000 shares against 400 shares at the ask walks the book to an average of $24.5312,
  2.12 cents a share worse, an extra $106.
- Signal source: none (execution).
- Mechanical? yes (impact can be computed from a book snapshot).
- Practical lesson for NSE options: at 1 lot our order usually fits at the touch (h24: 1-11 NIFTY lots shown), so walking
  the book is small; at size it is not. The NIFTY monthly ATM spread was a median 18 ticks (BANKNIFTY 54) at the calm
  snapshot, so the pull of certainty is strong there too. Air pockets matter most for stops on thin strikes: a stop on
  the option premium can fill several rupees through the trigger. Our default (signal and stop logic on the underlying,
  option chosen by a strike rule near the money) avoids the thinnest books.
- Our research: h10 measured capacity (15% of the 25th-percentile exit volume): BANKNIFTY 287 lots before the holdout,
  129 in it; FINNIFTY 3, then 0; MIDCPNIFTY 11, then 4. Costs (spread, impact, charges) ate 55-65% of gross at plan size;
  the deciding unknown was the impact constant kappa. Walking the book is the reason size cannot be scaled freely.

### 9. Call auctions, indicative price, hybrid opens and closes (Ch. 2.8, 2.9.2, 3.4, Figs 4.5-4.6)
- Book's mechanics (Exhibits 2.3-2.4): orders are batched and all trade at one clearing price, the price that maximises
  the number of shares executed (90 round lots at $24.49 in the example; one new 30-lot buy at $24.51 moves the
  indicative price to $24.50 and 107 lots). Bids above and asks below the clearing price get price improvement. Excess on
  the bigger side is usually rationed by time priority. Some calls show only the indicative price, not the book.
  Benefits claimed: concentrated liquidity, lower volatility and costs, less information leakage for big orders, price and
  quantity found together. No immediacy. Exchanges open and close with calls and use them to reopen after halts. NYSE's
  opening and closing calls were about 9.7% of 2020 dollar volume. The Arizona Stock Exchange's after-hours call failed
  for lack of order flow.
- Signal source: none (market structure); at NSE the pre-open discovered price is an underlying price.
- Mechanical? the auction is; no trading rule is given.
- Practical lesson at NSE (from our docs, not the book): cash stocks have a 09:00-09:08 pre-open call with a random close
  between minute 7 and 8 (so orders cannot be pulled at a known last second), matching after it; index futures got their
  own pre-open on 8 Dec 2025; index options are not in it and open straight into continuous trading at 09:15 with stale
  quotes and wide spreads (R8 section 1.9). Since 3 Aug 2026 a closing auction sets the close in about 220 F&O stocks;
  the NIFTY print now freezes 15:15-15:27 and jumps at 15:28-15:29, while options trade to 15:39 (MARKET_HOW 3e). So:
  avoid entering options in 09:15-09:20 (R8's rule), use the futures pre-open price as the honest gap estimate, and do not
  run index-candle exits after 15:15.
- Our research: R15 tested 5 pre-open filters on Liquidity BANKNIFTY: 0 of 5 passed. The pre-open order book itself
  (indicative price, buy/sell imbalance) has never been tested by us; we have no history of it.

### 10. Dealer (quote-driven) markets (Ch. 2.8, 2.9.3, 3.4)
- Book's claim: dealers post two-sided quotes, profit from the spread and short holding, and see the order flow; they
  cut execution risk at the price of wider spreads. A dealer who buys from you is not an investor; they will sell the
  shares again soon.
- Signal source: none. NSE options are order-driven, but the resting quotes are largely prop/HFT market makers
  (MARKET_HOW: prop is 46-49% of index-option premium turnover).
- Mechanical? no.
- Practical lesson: the person on the other side of a retail option buyer is usually a fast liquidity provider who
  earns the spread; we pay it.
- Our research: R7 section 2: the published order-flow edge lives at seconds and the next price change, used by market
  makers and co-located HFT; a phone is too slow.

### 11. Handling large orders: blocks, slicing, dark pools, hidden orders (Ch. 2.8.2, 4.7, Fig 4.7)
- Book's claim: big traders slice a parent order into child orders over time, use dark pools (priced off lit-market
  quotes) or iceberg orders to avoid leaking intent; each method can turn out better or worse. Fig 4.7 compares selling
  50,000 shares lit, hidden, or in a dark pool at the mid.
- Signal source: none (execution). Maps to us only at size.
- Mechanical? partly.
- Practical lesson: at 1-2 lots irrelevant; if the Liquidity arm ever trades tens of BANKNIFTY lots, slice and cap
  (h14's limit "stops the curve from bending down at large size, because it buys only the cheap liquidity").
- Our research: h10/h14 above. R86b saw basket bursts (about 0.7 a day on NIFTY) but they did not predict the next 15-60
  minutes (997 bursts).

### 12. Thin markets: bid-ask bounce, impact reversal, illiquid lag (Ch. 3.4-3.5)
- Book's claims: in a thin book, trades bounce between bid and ask (adding fake volatility); a large order's impact tends
  to revert; signals from illiquid prices are noisier. On 4 Jan 2019, Microsoft traded 2,520 times between 9:30 and 11:15
  and moved smoothly; Flushing Financial traded 25 times (once every 4.2 minutes), lagged the index and jumped to catch
  up.
- Signal source: underlying price / option premium (the effect is strongest in option premiums).
- Mechanical? partly.
- Practical lesson: an option's own minute chart is full of bid-ask bounce and stale last prices; reading signals from
  it is unreliable. This supports CLAUDE.md's default: signal on the underlying, not on the option's chart.
- Our research: R102 found the only "confirmed" pattern, 1-minute continuation (+0.11 bps NIFTY), is a stale-price
  artefact: the cash index is built from stocks that update at different moments, exactly the book's lag effect, and it
  is far below a 1.25-1.54 bps cost. R103: the 6 design passes of defined-risk option selling were stale wing prices.
  R45: a "premium mean-reversion scalp" was an artefact of the rolling ATM series. Stale prices have fooled us three
  times.

### 13. Macro news shocks and protracted discovery (Ch. 3.2-3.5, 3.8)
- Book's examples: after the Fed chair's early-October 2018 remark, the S&P 500 fell 0.4% from 2:15 pm to the close,
  mostly in the first 40 minutes, and 1.8% over about two days; the index fell 14% in the quarter. On 4 Jan 2019 a
  strong jobs report gapped the index up 1.1% at the open, a Fed "patience" remark added a second jump, and the day
  closed +3.02%. COVID 2020: conflicting news, divergent expectations, record intraday volatility.
- Signal source: underlying price plus news.
- Mechanical? no (no rule, only description).
- Practical lesson: on event days expect more movement, not a known side; size and stops should widen, not the
  conviction.
- Our research: h28 (above): more movement on event days, no side. R16: the size of the next 30-60 minutes is
  predictable (holdout error 15-20% below the time-of-day average), direction is not.

### 14. Top-down investing, fundamentals, and technical timing: the moving-average crossover (Ch. 3.7)
- Entry (book's example): a price "bottom" is signalled when price rises above a moving average of past prices (price
  $22 above a 65-day average of $21); sell when price falls below the average (footnote 7). The authors say they neither
  support nor deny technical analysis.
- Stop: not stated. Target / exit: the opposite cross (implied); the example's fundamental target is $30.
- Filters: a top-down view (business cycle, sector rotation) and a fundamental value estimate come first.
- Market and timeframe: single US stocks, daily bars, weeks to months.
- Signal source: underlying price; maps to NSE indices (daily), MCX futures (daily), crypto (daily, with a stop and a
  profit lock added per CLAUDE.md).
- Mechanical? yes for the crossover; no for the fundamental and top-down parts.
- Our research: a daily 65-day crossover on NIFTY/BANKNIFTY has not been tested by us. Intraday EMA crosses lost in
  every catalogue: OBUY_FINAL TI-03 EMA cross lost Rs 369,856 over 2022-2026 (96 variants); R29 EMA ribbons, no pattern
  passes. Daily trend on crypto (RC1, walk-forward 100/200-day SMA, then a 50-day Donchian) made +24.4% a year after tax
  against +58.8% for buy and hold. The nearest arms with a record are intraday channel breaks, not daily crosses: R72
  Breadth Donchian and R51 Donchian 20 (R101).

### 15. Efficient markets and intraday correlation patterns (Ch. 2.11, 3.7)
- Book's claim: intraday tests reject the random walk; both positive and negative short-term correlations exist in
  shifting combinations, and exploiting them is "far from an easy task". The efficient market hypothesis should be
  neither fully accepted nor fully rejected; markets are efficient enough to punish over-confident traders.
- Signal source: underlying price.
- Mechanical? no.
- Our research: agrees almost word for word. R102 section 2.5: lag-1 autocorrelations at 1-60 minutes are tiny
  (e.g. NIFTY 5-minute +0.006, the next bar follows the sign 48.4% of the time, n 167,082); the 20-year daily momentum
  (+5.8 bps NIFTY, +12.4 bps BANKNIFTY) reversed in the holdout quarter (n 65). F1, B2, R30, R47, R48, R87 and R88: direction
  a coin flip.

### 16. Trading technology: latency, co-location, kill switches, reliability (Ch. 4)
- Book's points: latency and co-location decide who acts first; most algorithms carry kill switches for extreme markets;
  an algorithm is only as good as the match between its test data and the future; failures are costly (Knight Capital
  lost $440 million in about 45 minutes in 2012 from a routing-software error; the Tokyo exchange lost a whole day in
  October 2020 when a backup failed). Maker-taker fees can route orders to rebates rather than best prices.
- Signal source: none.
- Mechanical? n/a.
- Practical lesson for the app: our kill switch, day lock and broker-side SL/GTT (CLAUDE.md) are the right kind of
  control; test fail-over paths, not just the happy path.
- Our research: R7/R8 measured our own feed: 70-80% repeated packets, 0.2-1.1% stale states re-sent, last trade 1.2-2 s
  old at the median. Retail latency rules out the seconds-scale edges.

### 17. TraderEx: how the simulated market is built (Ch. 5.1)
- The model: an unseen equilibrium value P* follows a driftless random walk (lognormal jumps, mean zero). Informed
  orders trade one-way only while P* sits outside the quotes (market orders rise from 50% to 67% of flow on that side);
  liquidity orders are random buys and sells; momentum traders chase trends and make false trends, and their overshoot
  is what pays limit-order providers.
- Signal source: none (a model).
- Practical lesson: in this design, trend-chasing is deliberately crowded and often fooled; profits go to whoever is
  closest to P* or provides liquidity into overshoots. That is the book's view of why simple momentum fails.
- Our research: consistent with our breakout record: ORB family 0 of 257 (R92), previous-day high/low break 0 of 180
  (R89). Liquidity 15+5, which trades level breaks with its own exits, is the exception with a record (R101).

### 18. Working a large order against VWAP (Ch. 5.4, Exercises 1-2)
- Setup: "buy 500 shares by the close". Hitting the book with one big market order costs impact; a small market order
  plus patient limit orders is better. Success is measured against the day's VWAP. The worked example paid $21.4078
  against a VWAP of $21.3077, 10 cents worse; 10 cents under VWAP would have added 0.47% to the holding's return.
- Signal source: none (execution benchmark).
- Mechanical? yes as a benchmark.
- Practical lesson: VWAP is a fair yardstick for fills in an arm that trades often; for one-lot signal trades the
  mid at the signal (implementation shortfall) is the better one.
- Our research: we have not compared our fills with interval VWAP. h24's logging list gives the inputs.

### 19. Market making by two-sided limit orders (Ch. 5.4, Exercise 3)
- Setup: post a buy limit below and a sell limit above, capture the spread, keep inventory small, finish the day flat.
  What can go wrong: one-sided fills in a trend (adverse selection), position risk, the time left in the day.
- Signal source: order book; maps to NSE options only for co-located firms; not for our phone app; crypto makers earn fee
  rebates.
- Mechanical? yes, but needs tick-level book data and speed we lack.
- Our research: not tested by us. R7 says this is where the order-flow edge is, and it belongs to co-located market
  makers. Also: selling options (R103) is a different thing (premium, not spread).

### 20. Momentum trading with market orders (Ch. 5.4, Exercise 4)
- Entry: when the bid steps up to a new, higher level (buying pressure), buy with a market order; mirror for sells.
- Stop: not stated. Exit: sell later at a higher price; be flat by the close.
- Market and timeframe: the TraderEx single stock, seconds to minutes.
- Signal source: order book (best bid), nearest for us: underlying price (index futures best bid or 1-minute highs).
- Mechanical? partly (the "new higher level" needs a lookback).
- Our research: the book itself warns that paying the spread on every round trip is the cost; our direction work agrees
  that short-term momentum does not pay an option buyer (R16, F1, R30). R7: the app's signed option flow leads the index
  (IC +0.109 at 1 minute) but its edge is 0.42x of the cost of an option trade.

## Performance claims
- No claim of trading profits, win rates or returns anywhere in the book.
- Descriptive numbers it gives (with its evidence): S&P 500 variance ratios for 2019 (Exhibit 2.1: open/day 4.36,
  close/day 1.82, day/month 1.31; computed by the authors per stock, then averaged); opening plus closing calls about 9.7%
  of NYSE dollar volume in 2020 (TAQ/CRSP, 2593 stocks); dark pools about 39% of US volume in 2019 (TABB Group), ATSs 40%
  (Rosenblatt); NYSE market share from about 80% to about 20%; the S&P 500 rose 29% in 2019; Knight Capital's $440
  million loss. These are market facts, not strategy results.
- The TraderEx example trade lost against VWAP (10 cents a share worse); the authors present it as a lesson, not a result.

## Risk, sizing and psychology
- No position sizing rules. Risk is discussed as portfolio theory (CAPM: an investor's weight in the risky portfolio
  rises with the risk premium and falls with variance and risk aversion) and as execution risk.
- Trading is presented as a separate profession from investing; on a trading desk "12:00 noon can seem like the long
  run" because intraday volatility is high.
- Prop traders in the simulation aim to end flat to avoid overnight risk.
- Algorithms need kill switches; a human must design and switch them on.
- The closing note is a humility check: markets are efficient enough to discipline any over-eager trader, and anyone
  claiming a sure-fire mispricing formula should be doubted.

## How it relates to our research
- **Agrees: direction at short horizons is mostly noise.** The book says intraday correlations exist but shift and are
  hard to exploit; our F1, B2, R16, R30, R47, R48, R87, R88 and R102 found direction a coin flip (R102: 1 of 70 sign
  rules confirmed and it was an artefact). FINDINGS "What is predictable? (R16)": size yes, direction no.
- **Agrees: friction is the main enemy.** The book's whole frame (explicit + implicit costs) matches our cost
  baselines: about Rs 70 charges of a Rs 124 random-entry loss (OBUY_FINAL), real half-spread 0.16% NIFTY/BANKNIFTY
  (HUNT_H24, baselines.yaml), round trip about Rs 98 / Rs 133 (FINDINGS, R16). The book never quantifies option costs;
  ours are larger in percent than its stock examples because option premiums are small and ticks are coarse relative to
  the spread.
- **Agrees: the open is a discovery period with extra volatility.** MARKET_HOW 3a (first 15 minutes about 2x a normal
  minute) matches Exhibit 2.1's direction.
- **Agrees: visible book size is not reliable.** R8's walls and cancels, R7's "explains the same seconds, does not lead",
  R61b (trap/order-flow proxies add nothing to Solo), R86 (0 of 23), R86b (0 of 8), R86c (0 of 12), R86d (0 of 4).
- **Already tested and rejected (nearest form):** fading liquidity-shock moves (R86 stop runs and VWAP fades, all lost);
  following news days for a side (h28, h27); momentum/breakout chasing (ORB family 0 of 257 in R92; R89 0 of 180);
  intraday EMA crosses (OBUY_FINAL TI-03; R29).
- **Already tested, passed, not yet in the app:** a capped marketable limit instead of a market order (h14 E2; the
  Liquidity arm still sends market orders, research/APP_AUDIT_BOOKS.md).
- **Not tested by us:** the daily 65-day crossover on indices and MCX; passive (resting at the bid) option entries; a
  variance-ratio measure of NSE intraday friction; any use of the NSE pre-open book; fills vs VWAP; two-sided market
  making (not feasible for us).

## What is new to us
1. **Implementation shortfall as the app's fill score.** Every fill measured against the mid at the signal second, split
   into spread paid, delay and impact. h24 listed the fields to log; the book gives the standard name and formula. This
   is the most useful practical import, and it needs no backtest, only logging.
2. **The variance-ratio test of friction** (open-half-hour variance x 13 / daily variance; day variance x 21 / month
   variance) as a single number per index and per period, to track how much "excess" short-term noise each regime has
   (before/after Nov 2024, after the closing auction). Descriptive, cheap, honest.
3. **The pre-open call as a price-discovery tool for the gap.** The NSE index-futures pre-open (since 8 Dec 2025) gives an
   auction-discovered price before options open at 09:15. We have never recorded it. A recorder for the 09:00-09:08
   indicative price and buy/sell imbalance would let us test whether it says anything about the first 15 minutes'
   size (not direction; we expect size at most).
4. **Non-execution risk on stops.** The book's stop-limit example makes the point that a stop-limit can be skipped in an
   air pocket. Worth a check of how the app's broker-side SL orders are priced for options.
5. **Resiliency** (how fast a book refills after a hit) as a liquidity measure, alongside spread and depth; R8 measured
   cancels but not refill speed after a trade.

## Could not read
- The charts and screenshots have no text layer: Exhibits 1.1-1.11 (curves), 3.1-3.7 (Fed funds rate, Treasury yields,
  S&P 500 paths, the Microsoft vs Flushing Financial chart), Figs 4.3-4.6 and 4.8 (quote screens, auction screens, a FIX
  message), and Exhibits 5.1-5.8 (TraderEx screens). Their captions and the text describing them were read.
- The table of contents page is garbled in the text layer (only chapter titles and page numbers are recoverable).
- Nothing else.
