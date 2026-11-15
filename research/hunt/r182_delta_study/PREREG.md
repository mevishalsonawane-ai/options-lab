# R182 pre-registration: order-flow delta, what it is, what it did, and a delta skip rule on the three arms

*15 Nov 2026. Written and committed BEFORE any forward return (Part A) or any trade outcome (Part B) was computed.
Research only: nothing in the app changes.*

Boss (they/them): "Add a delta study ... first understand deeply how delta works, and how it worked on the previous data,
then apply it on the other 3 strategies (Liquidity 15+5, R51 Donchian 20, R72 Breadth Donchian) and provide results
before merging."

Delta here is ORDER-FLOW delta: aggressive buy volume minus aggressive sell volume in an interval; CVD (cumulative volume
delta) is its running sum through the day. It is not the option Greek.

## 1. What was looked at before this file (no outcome)

Allowed before pre-registration and disclosed here: the app code that makes each delta, the files' layout, data quality
counts, the two recorders' cross-check on the same minutes (volume and delta only, no forward return), and the rebuilt
trades' COUNTS and their delta labels (no exit price, no rupee, no win/loss was printed or read). `r182.py --stage pre`
writes these to `results/pre.json`. Seen so far:

- **Two deltas exist, built differently** (read from the code):
  - The minute file `orderflow_1m` (`TickZone.Flow`, ira-core): once a SECOND it copies the stream's latest snapshot; the
    second's volume is the rise in Kite's cumulative day volume (so the minute's volume is complete), signed by the tick
    rule on the second's last price against the previous second's (zero tick = the previous side).
  - The 1-second file `orderflow-*.csv.gz` (`OrderFlow.Book.offer`, ira-core; recorded by `OrderFlowLive`): every tick
    PACKET; the packet's volume rise is signed by the tick rule against the previous packet's price. It DROPS (a) an
    "illiquid print": a packet whose volume is over 5x the median packet size and that did not move the price
    (`TrapGuard.illiquidPrint`), from both the volume and the delta; (b) a packet that re-sends an older state (volume
    below the running maximum). `q_buy_vol`/`q_sell_vol` are the quote rule (at or above the offer = buy, at or below the
    bid = sell, inside by the midpoint) against the book before the packet. `ofi`/`mofi` are Cont-Kukanov-Stoikov
    order-flow imbalance (best level / 5 levels) - book changes, not trades. The footprint comes from the same packets
    (`Auction.Day.trade`, illiquid prints unsigned and not counted), which is why it matches the 1-second file.
  - Kite gives NO aggressor flag, so both are estimates. Kite full-mode packets for these futures arrive about 1.0-1.25
    times a second, so "per packet" is close to "per second" but not equal (about a third of volume is in seconds with two
    or more packets).
- **Cross-check (9 overlap days, NIFTY and BANKNIFTY):** the 1-second file holds 65-83% of the minute file's volume (the
  dropped prints), so the deltas are equal in only 0.5-11% of minutes, their signs agree in 61-69%, and the day's
  correlation of the two minute deltas ranges -0.56 to 0.86 (BANKNIFTY 21 Oct: one 29,010-unit print signed "buy" in the
  1-second file dominates the day's CVD).
- **Quality:** FINNIFTY futures barely trade (prints in 53-362 seconds a day, about 5,000-46,000 units a day): its delta is
  zero in most minutes. MIDCPNIFTY is thin and from 3 Nov often gappy (up to 262 thin minutes on 12 Nov). The footprint
  (absorption) exists only for NIFTY and BANKNIFTY. Missing futures minutes up to 82 a day (9 Nov).
- **Trades rebuilt (counts only):** 179 paper trades (Liquidity 9, R51 94, R72 76), all closed, none orphaned; 149 on
  NIFTY / BANKNIFTY / FINNIFTY / MIDCPNIFTY with futures flow (the rest SENSEX, no flow); 129 have a non-zero 5-minute
  futures delta before entry; the primary rule would skip 4 / 14 / 9. 24 R51 and 24 R72 trades are the same option bought
  in the same second by both arms.

## 2. Data (untrusted, read-only, outside git; python -I)

- 1-second order flow, 20 trading days 15 Oct - 13 Nov 2026 (no 20 Oct, 10 Nov): the FUTURE rows of NIFTY, BANKNIFTY,
  FINNIFTY and MIDCPNIFTY (near month), and the footprint (NIFTY, BANKNIFTY).
- Minute order flow, 9 days 15-28 Oct that overlap (cross-check only).
- The phone's daily diagnostics 19 Oct - 14 Nov: ONLY `account == "paper"` orders of `R51 Donchian 20`,
  `R72 Breadth Donchian`, `Liquidity 15+5`, plus the paper `Auto square-off 15:15` sells that close them. Live orders
  carry no fill price and are ignored (Boss's real P&L is never inferred).
- The study's delta is the **1-second file's** tick-rule delta (`buy_vol - sell_vol`): it is what the app's live read
  and FlowGate use, and it is the only one covering the trade days and all four futures. The minute file is a cross-check.

## 3. Definitions (point in time)

Minute i = seconds 09:15 + i minutes (i = 0..374). Bar close c = last traded price in the minute (NaN if no row); delta
d = sum of (buy_vol - sell_vol). Scale s_i = mean |d| of the day's earlier minutes with >= 20 rows (NaN until 15 such).
At minute close i: D1 = d_i / s_i; D5 = sum d_{i-4..i} / s_i (>= 4 of 5 minutes recorded); D15 likewise (>= 12 of 15);
CVD slope 15 = OLS slope of CVD over i-14..i / s_i; Div15 = sign(D15) when sign(D15) = -sign(15-minute price change),
else 0; OBI = the minute's mean 5-level depth imbalance; Absorb5 = sum over i-4..i of TickZone's absorption rule on the
footprint (>= 60% of a >= 2x-average minute at one price, range <= max(0.10, 0.0002 x price), >= 5 earlier minutes),
direction +1 when selling was absorbed, -1 when buying was. Forward return h = log(c_{i+h} / c_i), h = 1, 5, 15, 30, same
day. Sample: minute closes 09:30 - 15:14.

## 4. Part A (one pre-registered test per index; the rest descriptive)

- **Primary test, one per index (4 tests: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY):** the rank correlation (Spearman:
  Pearson of the pooled ranks) of D5 at the minute close with the NEXT 15 minutes' futures return, all 20 days pooled.
  Two-sided p from the day-block bootstrap (resample whole days, 10,000 draws; z = rho / bootstrap SE). Check: a
  within-day circular-shift null (each day's feature shifted by a random 30 .. n - 30 minutes, 10,000 draws).
- **Descriptive (no test claimed):** same-minute rank correlation of D1 with the minute's own return (and of the quote
  rule's delta); the grid of the 7 features x 4 horizons per index with day-bootstrap 95% intervals (1,000 draws); the
  primary by time of day (09:30-10:30, 10:30-13:30, 13:30-15:15); the mean next-15-minute return after a positive vs a
  negative D5; the minute file's same-minute and primary statistics on its 9 days.
- **Which delta to trust (descriptive, decided by this rule now):** the signing whose minute delta tracks the same
  minute's price change more tightly (higher same-minute rank correlation on the 9 overlap days) is the better aggressor
  estimate; volume completeness is judged from the code (section 1).

## 5. Part B (one primary filter per arm; three secondary variants, descriptive)

- **Trades:** each filled paper BUY of an arm is one trade; that arm's filled SELLs of the same symbol close it first-in
  first-out (partial sells allowed), then the paper 15:15 auto square-off closes what is left (first-in first-out across
  arms). Side: CE = long (+1), PE = short (-1); underlying from the symbol.
- **Outcome:** net rupees = sum (exit - entry) x qty - real dated charges (`research/obuy/costs.py Costs('dated')`, BSE
  rates for SENSEX) on the buy and each sell; points = exit - entry; percent of entry; win = net > 0. The paper fills
  are the app's own (its paper slippage), not re-simulated.
- **Features at the entry second S** (the diagnostics' order time, IST), the trade's underlying's near future, seconds
  max(S - w, 09:15) .. S - 1 only: a5 = side x (5-minute delta), a15 = side x (15-minute delta); typ5 = median |5-minute
  delta| of the windows ending at the day's earlier minute ends (>= 10 of them); absorption against the side in the last 5
  completed minutes (NIFTY, BANKNIFTY only).
- **Label:** a trade has a label when its 5-minute window has a row and a5 != 0 (else no opinion: kept, not a twin
  candidate; SENSEX has no flow).
- **Primary filter (each arm, 3 tests):** SKIP the entry when the 5-minute futures delta OPPOSES the side (a5 < 0).
- **Secondary variants (descriptive):** V1 skip when a5 < -typ5 (strong opposition); V2 skip when a15 < 0 (15-minute CVD
  slope opposes); V3 skip when any absorption against the side in the last 5 minutes.
- **Report per arm:** all / kept / skipped trades: count, win %, net, average, points; "delta agrees" (a5 > 0) vs
  "disagrees" (a5 < 0) vs no label. **Random-skip twins:** skip the same number of the arm's labelled trades at random,
  10,000 draws; p = share of draws whose kept net >= the rule's kept net (one-sided: the rule helps). Pooled three arms:
  descriptive only.
- **Multiple testing:** 7 tests (4 Part A + 3 Part B primaries); BH q with m = 4,066 + 7 = 4,073 (the other tests taken
  as larger) and within R182.
- **Bars (fixed now):** Part A "delta predicts" on an index only if q <= 0.10; "suggestive" if its 95% interval excludes 0
  but q > 0.10. Part B "helps (exploratory)" on an arm only if the skipped trades' average net is below the kept trades',
  twin p <= 0.20 and >= 8 trades skipped. About 180 trades over 18 days cannot pass the repo's gates (>= 40 holdout
  trades, every period, BH), so nothing here can be adopted.
- **Recommendation rule (fixed now):** a shadow (log-only) delta label on an arm is worth adding for forward testing if
  that arm's primary "helps (exploratory)", or Part A is at least "suggestive" with a positive sign on >= 2 of NIFTY,
  BANKNIFTY, MIDCPNIFTY. Otherwise: no new label now; re-run as recordings grow. Either way no app code changes here.

## 6. Run once

`r182.py --stage run` once after this file is committed; results/results.json records this file's SHA-256. Per-trade
rows stay out of git (scratch only); the repo gets aggregates. Any later change is an amendment, dated, with its reason.
