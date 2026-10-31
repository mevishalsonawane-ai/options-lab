# R140: US gold from 06:00 to 08:00 IST (15-minute candles), and whether it tells MCX gold anything after 09:00

*31 Oct 2026. Research only: nothing in the app changes. Pre-registration: `research/hunt/r140_gold_0600/PREREG.md`
(commit 7231fcf, pushed before any result). Code and tests: `research/hunt/r140_gold_0600/` (`r140.py`, `test_r140.py`,
`fetch_xau.py`). Results: `results/design.json`, `results/design.md`, `results/full_describe.json`,
`results/holdout.lock`.*

Boss, 31 Oct: *"check for US gold from morning 6 a.m. to 8 a.m. IST [on 15 minute] and find a strategy. I think so.
There is something we could catch."*

**Signal source of everything: XAUUSD spot (the underlying), 15-minute candles built from 1-minute prices.** Part B
trades the MCX GOLDM future (1 lot) on that signal. No rule reads an option's chart. Every rule has a stop-loss and a 60%
profit lock.

## The answer for Boss

**Nothing to catch. 0 of 12 pre-registered rules passed (best BH q 0.67; the bar is 0.10). The locked holdout was not
opened for any rule, because none passed design.**

**Amendment A (Boss's follow-up): the market that closed just before 06:00 (gold's US session, closing at 02:30 IST in
US summer and 03:30 IST in winter) and the hours after it do not guess the window's direction either. They match it on
47-53% of days, and 0 of 6 more rules pass. See the Amendment A section.**

**Whole day (Amendment E): one rule passes everything, including the unseen holdout: 11:00 IST on busy days with the
4-signal vote (H11b): +$95 a 100-oz lot on 186 design trades and +$58 on 29 holdout trades. $1,000 at 5x: +21.5% in
2.5 years. It is a forward-test candidate, not proven (one hour of 24; most profit in one fold; Sep-Oct slightly
negative). Boss's indicators (three Pine scripts, the origin-low sweep and the break-and-retest from his screenshot;
Amendments F-J) all lose on gold.**

**Amendment B (Boss: add indicators, strategies and stop / lock settings to raise the win rate): 0 of 18 more pass.
The win rate can be lifted to 67-72% by taking small profits early, but the average trade stays at about zero. See
the Amendment B section.**

1. **What gold does from 06:00 to 08:00 IST** (575 window days, Oct 2023 - Mar 2026):
   - It is a quiet-to-medium stretch. The median 2-hour move is **$4.28**, and the median high-to-low range is **$11.28**
     (0.39%). On MCX that range is about **550 GOLDM points**, but the start-to-end move is much smaller.
   - It went up on **56%** of days, on average **+4.4 bp** (standard error 1.8). That is gold's 2023-26 bull run showing
     through, not a feature of this window: the random "same hour, random side" twins pick it up just as well.
   - The 06:30 candle is the busiest (median range $5.65). Every 15-minute candle is green on 48% to 55% of days.
   - **Nothing before 06:00 predicts it.** The US day session's direction matches the window's on 47% of days (correlation
     +0.04). The previous day's trend matches on 49% (+0.06). Release mornings (after CPI, NFP or FOMC; 71 days) move
     about the same as other days (25.8 vs 25.6 bp on average).
   - **The first 15 minutes do not predict the rest.** The rest of the window goes the same way as the 06:00 candle on
     48% of days.
   - **US summer vs winter time** (the window sits 3.5 h after the US close in summer, 2.5 h in winter):
     - summer: 333 days, +4.6 bp, up 54%;
     - winter: 242 days, +4.1 bp, up 59%.
     - In winter the correlations look larger (window vs the US session +0.15, first candle vs the rest +0.30). But the
       same sign still shows up only 46-49% of the time, so a few big days make the correlation. A rule that trades it
       loses money (A1, A2).
2. **Rules on XAUUSD inside or after the window (information only; Boss cannot trade spot gold through Zerodha):**
   - All 6 rules lose money after a $32 round trip on a 100-oz lot. The exception is A6, which makes +$57 a trade on 60
     trades, but its twin p is 0.15 and its q is 0.67.
   - Breakouts of the 06:00-08:00 range after 08:00 lose: -$23 a trade on 396 trades. Fading them loses too: -$52 a
     trade.
   - This repeats GOLD_SESSION_STRATS (the Asian-range sweep at London lost) with a new window and new entries.
3. **The link to MCX gold (what Boss CAN trade):**
   - **Yes, the window shows up in MCX's first hour.**
     - On 132 design days with no contract roll, the window's XAUUSD move correlates **+0.30 with the GOLDM 09:00 gap**
       and **+0.36 with GOLDM from 09:05 to 10:00**.
     - Correlation with GOLDM from 09:05 to the close: +0.04 (nothing).
   - **But it does not pay.**
     - Following the window's direction from 09:05 to 10:00 makes **+Rs 523 a lot before costs** (standard error Rs 747,
       151 days; t 0.7).
     - A round trip costs about **Rs 400**: about Rs 270 of charges plus Rs 130 of slippage.
     - The ten best days make more than the whole total. GOLDM moves the window's way on only **55%** of days. The
       correlation comes from a handful of big mornings, and nobody knows in advance which ones.
   - **As rules, with the stop and lock:**
     - B1 (follow 09:05-10:00): **-Rs 345 a trade**, 151 trades, 52% win.
     - B5 (fade it): -Rs 735 a trade.
     - B2 (follow to the close): -Rs 65 a trade.
     - B6 (the window's high and low as GOLDM levels): -Rs 269 a trade.
   - **The two that made money in design are not real enough:**
     - B3 (big windows only, to the close): +Rs 112 a trade, 105 trades, 68% win.
     - B4 (the 09:00 gap under-reacted, 09:05-14:00): +Rs 378 a trade, 72 trades, 65% win.
     - Both lose at 2x costs. Neither beats chance after BH: B4's twin p is 0.078, q 0.67.
     - Each made all its money in one fold, Feb-Mar 2026. That was the wildest stretch: GOLDM went from about Rs 1.41
       lakh to Rs 1.64 lakh and back to Rs 1.39 lakh. B3 lost -Rs 34.6k in Dec-Jan, and B4 lost in 3 of 4 folds.
   - This agrees with R2 ("US overnight -> MCX gold: +Rs 37 a trade, no edge") and R3 (no GOLDM morning effect).
4. **In practice for Boss:** there is no 06:00-08:00 gold trade to set up.
   - Looking at the window before 09:00 tells you which way GOLDM is *slightly more likely* to open and drift in the
     first hour: about 55 in 100. It is not enough to pay Zerodha's costs and slippage on a futures lot.
   - It is far from enough for an option buyer: R97 and R99 showed a GOLDM option needs 150-200 future points just to
     break even.
   - Use it as context only, not as a signal. **No paper arm is proposed.**
5. **Boss's 65% win-rate rule:** only B3 (68%) and B4 (65%) reach it, and both fail the money gates. A high win rate
   with a 60% lock and a 0.30% stop still loses on the days the stop is hit.

## Amendment A: does the market that closed before 06:00 guess the direction? (Boss, 31 Oct)

*Pre-registered in PREREG.md Amendment A (commit f85c7dc) before any number. Code `r140c.py`, tests `test_r140c.py`,
results `results/amendA_design.json` / `amendA_design.md` / `amendA_holdout.lock`.*

**No. Nothing that happens in the US session or in the hours after it guesses which way gold goes from 06:00 to 08:00
IST. 0 of 6 new rules pass (best BH q 0.74 over 18). The holdout was not opened.**

- The market that closes just before 06:00 IST is gold's US session. It closes at 17:00 New York: 02:30 IST in US
  summer time, 03:30 IST in winter. Gold reopens an hour later, and Sydney and Tokyo start before 06:00.
- Each signal below was checked as "the window goes the same way" and as a trade. The trade buys or sells at 06:00,
  exits at 08:00, uses the same stop and lock as A2, and learns follow or fade walk-forward.
- Design period: Oct 2023 - Mar 2026. Per signal, the "same way" columns use 545-574 days, except the big-US-day signal
  (147 days). The trades use the 473-495 days in folds F2-F5 (128 for C6).

| signal (known by 06:00 IST) | window goes the same way | summer / winter | correlation | as a trade (n, avg net, twin p, q) |
|---|---|---|---|---|
| US day session 08:20-17:00 NY (A2, already tested) | 48% | 48% / 48% | +0.04 | A2: 487, -$43, 0.55, 0.74 (q of the first 12) |
| prior day 17:00 -> 17:00 NY (A3, already tested) | 50% | 49% / 51% | +0.06 | A3: 487, -$43, 0.62, 0.74 (q of the first 12) |
| C1 last US hour 16:00-17:00 NY | 53% | 51% / 55% | -0.05 | 473, -$35, 0.45, 0.74 |
| C2 US afternoon 13:30-17:00 NY | 50% | 51% / 50% | -0.09 | 486, -$96, 0.96, 0.99 |
| C3 US close -> 06:00 IST (reopen, Sydney, Tokyo) | 53% | 54% / 51% | -0.11 | 494, -$28, 0.41, 0.74 |
| C4 the last 30 min, 05:30-06:00 IST | 52% | 54% / 49% | -0.03 | 493, -$35, 0.45, 0.74 |
| C5 MCX close -> 06:00 IST (the whole night) | 50% | 52% / 48% | -0.15 | 495, -$31, 0.45, 0.74 |
| C6 big US day only (top 20% of moves) | 47% | 49% / 43% | +0.05 | 128, -$178, 0.99, 0.99 |

- **Every signal is close to a coin flip, 47% to 53%.** The best "same way" rate is 53% (the last US hour, and the move
  after the close). With about 550 days, 53% is within normal luck (about plus or minus 4 points).
- **All 6 rules lose after costs** ($32 a round trip on a 100-oz lot). They lose at -$28 to -$178 a trade, and none
  beats the random same-hour twins. Win rates are 48-51%, far below Boss's 65% rule.
- **Big US days do not help.** After the biggest 20% of US sessions, the window went the same way only 47% of the time
  (147 days). Trading it lost -$178 a trade on 128 trades.
- **Winter-time correlations look stronger, but they come from a few big days.** In US winter, the night's move since
  the MCX close has a correlation of -0.35 with the window. The US afternoon has -0.32. These hint at a reversal after
  a big night. But the window goes the opposite way on only 52% and 50% of those days (n 242 and 232). The walk-forward rules
  that try to use it lose money.
- **For MCX GOLDM (09:05-10:00, 144-151 design days):**
  - None of these signals points GOLDM's first hour the right way more than 57% of the time. The best is the night's
    move since MCX closed: 57%, correlation +0.13, on 151 days.
  - The 06:00-08:00 window's own move stays the strongest link (correlation +0.36). As shown above, even that does not
    pay after costs.
- **Not tested:** US stock indices. They are not in the data lake, and Dukascopy refused rapid downloads from this
  container. In this test, gold's own US session stands for "the US market".

## Amendment B: indicators, a vote, models, high win-rate exits and stop / lock settings (Boss, 31 Oct)

*Pre-registered in PREREG.md Amendment B (commits a1da2f1 and bf02a0d, part L added at Boss's "Stoploss profit lock")
before any number. Code `r140e.py`, tests `test_r140e.py`, results `results/amendB_design.json` / `amendB_design.md` /
`amendB_holdout.lock`.*

**No enhancement makes the window or its GOLDM link tradable. 0 of 18 pass (best BH q 0.40 over all 36 R140 rules).
The win rate CAN be pushed to 67-72%, but only by taking small profits early, and the money stays at about zero. The
holdout was not opened.**

**XAUUSD window, 06:00 -> 08:00 IST ($ per 100-oz lot; design Oct 2023 - Mar 2026; signals from the 15-minute candle
completed by 06:00):**

| id | rule | n | win (65% rule) | avg net | total 1x / 2x | folds + | twin p | q |
|---|---|---|---|---|---|---|---|---|
| E1 | follow EMA20 vs EMA50 | 574 | 48% | -$64 | -$36,551 / -$54,919 | 0 of 5 | 0.83 | 0.93 |
| E2 | RSI < 30 buy / > 70 sell | 49 | 39% | -$95 | -$4,651 / -$6,219 | 2 of 5 | 0.66 | 0.85 |
| E3 | Bollinger band, revert | 90 | 47% | -$109 | -$9,810 / -$12,690 | 1 of 5 | 0.79 | 0.92 |
| E4 | follow the Supertrend | 574 | 51% | -$49 | -$27,984 / -$46,352 | 1 of 5 | 0.65 | 0.85 |
| E5 | vote: EMA, RSI, MACD and the move since the US close all agree | 195 | 53% | -$55 | -$10,714 / -$16,954 | 1 of 5 | 0.69 | 0.85 |
| E6 | walk-forward model on 13 inputs, trade at P >= 0.58 / <= 0.42 | 327 | 55% | -$22 | -$7,271 / -$17,735 | 1 of 4 | 0.54 | 0.85 |
| E7 | E5 + high win-rate exits (target 0.5 ATR) | 195 | **68%** (meets) | -$5 | -$1,043 / -$7,283 | 1 of 5 | 0.33 | 0.85 |
| E8 | E6 + high win-rate exits | 327 | **72%** (meets) | -$29 | -$9,337 / -$19,801 | 0 of 4 | 0.67 | 0.85 |
| L1 | E5, stop 0.5 ATR, lock after +0.5 ATR, keep 60% | 195 | 50% | **+$43** | +$8,450 / +$2,210 | 3 of 5 | **0.011** | 0.40 |
| L2 | E5, stop 1.5 ATR, lock after +0.5 ATR, keep 50% | 195 | **69%** (meets) | +$28 | +$5,426 / -$814 | 3 of 5 | 0.14 | 0.85 |
| L3 | E5, stop 1 ATR, lock after +0.5 ATR, keep 75% | 195 | **67%** (meets) | +$37 | +$7,281 / +$1,041 | 2 of 5 | 0.08 | 0.85 |

**MCX GOLDM futures, in 09:05, out 10:00 (Rs per lot; design Aug 2025 - Mar 2026):**

| id | rule | n | win (65% rule) | avg net | total 1x / 2x | twin p | q | worst month |
|---|---|---|---|---|---|---|---|---|
| M1 | follow W only if the XAUUSD EMA trend and GOLDM's 09:00-09:05 agree | 40 | 42% | -Rs 1,390 | -Rs 55,614 / -Rs 71,858 | 0.93 | 0.99 | -Rs 27,541 |
| M2 | walk-forward model (W, gap, 09:00-09:05, US moves, RSI, EMA) | 73 | 56% | -Rs 226 | -Rs 16,484 / -Rs 47,348 | 0.22 | 0.85 | -Rs 17,086 |
| M3 | follow W + high win-rate exits (target 0.15%) | 150 | 58% | -Rs 345 | -Rs 51,789 / -Rs 111,958 | 0.47 | 0.85 | -Rs 36,256 |
| M4 | M1 + high win-rate exits | 40 | 45% | -Rs 1,442 | -Rs 57,674 / -Rs 73,917 | 0.99 | 0.99 | -Rs 26,301 |
| L4 | follow W, stop 0.20%, lock after +0.10%, keep 60% | 150 | 54% | -Rs 475 | -Rs 71,281 / -Rs 131,442 | 0.37 | 0.85 | -Rs 45,672 |
| L5 | follow W, stop 0.50%, lock after +0.10%, keep 50% | 150 | 55% | -Rs 245 | -Rs 36,742 / -Rs 96,904 | 0.19 | 0.85 | -Rs 36,184 |
| L6 | follow W, stop 0.30%, lock after +0.10%, keep 75% | 150 | 59% | -Rs 263 | -Rs 39,428 / -Rs 99,596 | 0.30 | 0.85 | -Rs 34,035 |

What this says, in plain words:

1. **Indicators do not find the direction.** The EMA trend, Supertrend, RSI extremes and Bollinger bands at 06:00 all
   lose. They are right 39-51% of the time. Putting four signals together (E5) or letting a model weigh 13 inputs (E6)
   lifts the hit rate to 53-55%, still not enough to pay the $32 round trip.
2. **The win rate goes up when the exits change, not when the signal gets better.**
   - A small target (E7, E8) or an early lock with a wider stop (L2, L3) wins 67-72% of trades. That reaches Boss's 65%
     rule.
   - But the wins are small and the losses are full-sized. The averages stay at -$29 to +$37 a trade. None beats
     random trades with the same exits.
   - A 70% win rate with 1-to-2 payoffs is the same coin flip.
3. **The closest one, L1** (the vote with a tight stop of 0.5 ATR and an early lock), made +$43 a trade on 195 trades.
   It beats its random twins (p 0.011), but:
   - it fails BH over the 36 rules tried (q 0.40);
   - it has only 3 of 5 folds positive;
   - one fold, Oct 2025 - Mar 2026, made +$11.4k of its +$8.5k total;
   - it wins only 50%.
   - It is information only (spot gold), and a 1-in-36 lucky draw is about what p 0.011 looks like after 36 tries.
4. **On MCX nothing helps.**
   - Confirmation filters (M1, M4) cut the trades to 40 and make them worse.
   - The model (M2) and every stop / lock setting (L4-L6) lose -Rs 226 to -Rs 1,442 a trade. The best win rate is 59%.
   - Every M and L4-L6 rule breaks the -Rs 13k monthly account stop.
5. **For Boss:** there is no setting of stop, lock, indicator or model that turns the 06:00-08:00 window into a trade.
   Raising the win rate by itself is easy and does not make money. No paper arm is proposed.

Note on the run: the first Amendment B run required GOLDM's 09:00 minute for every MCX rule. That dropped 13 of 152
sessions that open at 09:01-09:02, which the plan does not ask for M3 and L4-L6. This was fixed (`mcx_frame`) and the
design step re-run before any holdout read. The verdict did not change: 0 of 18 both times.

## Recent look: the high win-rate US-gold versions on 1 Sep - 30 Oct 2026, a $200 account (Boss, 31 Oct)

*Not a test; no verdict changes. These rules were already rejected in design. The 2 months lie inside the locked
holdout, so the holdout is now SEEN for E7, E8, L1, L2 and L3 (`results/recent_look.lock`, `recent_look.json`, code
`r140r.py`). The size is 0.01 lot = 1 oz (about $4,200 of gold at Sep-Oct prices; about $42 margin at 1:100), so $1 of
gold = $1 of profit or loss. Cost $0.32 a trade. The window is 06:00-08:00 IST. The exits are as in Amendment B. E8's
model was trained on the design period only. Spot gold is information only for Boss (not tradable via Zerodha).*

| version | trades | win rate | net ($200 ->) | at 2x costs | Sep / Oct | worst drawdown | random same-exit trades (median, p) |
|---|---|---|---|---|---|---|---|
| E7 vote + target 0.5 ATR | 17 | 71% | +$9.05 (-> $209) | +$3.61 | -$0.63 / +$9.68 | -$7.13 | +$5.99, p 0.40 |
| E8 model + target 0.5 ATR | 14 (all buys) | 71% | -$1.44 (-> $199) | -$5.92 | +$3.67 / -$5.11 | -$9.17 | -$7.25, p 0.30 |
| L1 vote, stop 0.5 ATR, lock +0.5 ATR keep 60% | 17 | 76% | **+$22.57 (-> $223)** | +$17.13 | +$14.99 / +$7.58 | -$4.93 | -$2.49, **p 0.034** |
| L2 vote, stop 1.5 ATR, lock keep 50% | 17 | 88% | +$20.41 (-> $220) | +$14.97 | +$14.00 / +$6.41 | -$9.53 | +$0.82, p 0.14 |
| L3 vote, stop 1 ATR, lock keep 75% | 17 | 82% | +$25.16 (-> $225) | +$19.72 | +$11.46 / +$13.70 | -$7.13 | +$9.27, p 0.16 |

How to read it:
- **Two good months are common for these rules, even though they failed overall.** Over the 29 two-month windows of
  the design period (Oct 2023 - Mar 2026, same 0.01-lot size), the share of positive windows was: L1 52%, L2 38%,
  L3 38%, E7 28%, E8 39%. The best windows were +$57 to +$68 and the worst -$19 to -$34. So a +$20-25 stretch says
  little on its own.
- **L1 is the only one that keeps looking better than chance.**
  - It beat its random twins in design (p 0.011, 195 trades, +$84.50 over 2.5 years at 0.01 lot). It did so again in
    these 2 months (p 0.034, 17 trades).
  - But it failed BH over the 36 rules tried, it was positive in only 3 of 5 design periods, and 17 trades is a very
    small sample.
  - It is a candidate to keep watching, not a proven edge.
- **The 76-88% win rates come from small wins; losses are bigger.** For L2, an average win is +$2.50 and an average
  loss is -$8.55. Two losers take back about seven winners.
- Per trade at 0.01 lot: average win +$1.10 to +$3.14, average loss -$2.69 to -$8.55, worst single trade -$9.53
  (-4.8% of $200).

## Amendment D: bigger profit per trade on US gold, and a $1,000 account (Boss, 31 Oct)

*Pre-registered in PREREG.md Amendment D (commit 54d9619) before any number. Code `r140g.py`, tests `test_r140g.py`,
results `results/amendD_design.json`, `amendD_holdout.lock`, `amendD_recent_seen.json`. US gold only; information only.*

**Closer, but still no pass (0 of 6).** Three versions (G1, G3, G4) now pass four of the five gates: profit at 1x and
2x costs, they beat random twins (p 0.006-0.007), and BH over all 42 R140 rules (q 0.098). They fail the walk-forward
gate: 2023-24 (F1, F2) lost, and almost all the profit came in one fold, Oct 2025 - Mar 2026 (+$10.3k to +$11.4k of
+$8.7k to +$9.9k in total per 100-oz lot). So the holdout (Apr - Aug 2026) stays closed.

| id | rule | n | win | avg net / 100 oz | folds (F1..F5) | twin p | q (42) | $1,000 at 5x: return / worst DD (2.5 y) | $1,000 at risk 2%: return / worst DD (2.5 y) | Sep-Oct 2026 (seen): n, 5x / risk 2% |
|---|---|---|---|---|---|---|---|---|---|---|
| G1 | vote 4/4, stop 0.5 ATR, lock +0.5 keep 60%, out 12:30 IST | 195 | 50% | $+45 | -2,382 / -1,553 / +890 / +390 / +11,351 | 0.006 | 0.098 | +5.8% / -8.8% | -2.0% / -28.7% | 17, +2.7% / +11.0% |
| G2 | vote 4/4, stop 0.5 ATR, lock +1.0 keep 60%, out 12:30 IST | 195 | 35% | $-5 | -2,085 / -912 / +1,521 / -1,243 / +1,717 | 0.146 | 0.576 | -3.2% / -11.2% | -6.6% / -24.1% | 17, +0.2% / +0.5% |
| G3 | vote 4/4, stop 0.5 ATR, lock +0.5 keep 50%, out 17:30 IST | 195 | 49% | $+45 | -2,485 / -1,703 / +1,489 / +1,132 / +10,328 | 0.007 | 0.098 | +6.4% / -9.3% | +5.9% / -27.3% | 17, +2.4% / +9.6% |
| G4 | vote 4/4 on busy days only (ATR >= earlier median), L1 exits, out 08:00 IST | 166 | 54% | $+59 | -1,338 / -1,154 / +475 / +542 / +11,351 | 0.007 | 0.098 | +9.0% / -5.5% | +10.0% / -20.5% | 17, +2.7% / +11.0% |
| G5 | vote 3/4, L1 exits, out 08:00 IST | 458 | 48% | $-6 | -3,563 / -3,281 / -310 / -880 / +5,513 | 0.089 | 0.477 | -10.9% / -21.0% | -51.2% / -58.7% | 38, +4.6% / +18.9% |
| G6 | vote 3/4, L1 exits, out 12:30 IST | 458 | 48% | $-5 | -3,828 / -3,281 / +202 / -880 / +5,513 | 0.091 | 0.477 | -10.7% / -20.8% | -50.6% / -58.2% | 38, +4.6% / +18.9% |

What this says for a $1,000 account:
1. **Holding winners longer does not help.** The stop or the lock closes almost every trade before 08:00, so
   G1 (out 12:30) and G3 (out 17:30) are almost L1 again (+$45 a 100-oz lot).
2. **Busy days help a little (G4).**
   - Trading only when gold's 15-minute range at 06:00 is above its usual size gives +$59 a lot on 166 trades, the
     best average so far.
   - Costs are the same, and the moves are bigger.
3. **The looser 3-of-4 vote (G5, G6) loses (-$6 a lot on 458 trades).** It wins 74% in Sep-Oct 2026 but lost over
   2.5 years. With 2% risk sizing it would have halved a $1,000 account (-51%, worst drawdown -59%). This is the
   clearest warning in R140: a great 2 months says nothing.
4. **Sizing:**
   - With a fixed 5x, the best versions made **+6% to +9% over 2.5 years** (G1, G3, G4), with worst drawdowns of -6%
     to -9%.
   - Risking 1-2% a trade on a 0.5 ATR stop makes the positions large on quiet days, when the edge is weakest. G1 at
     risk 1% lost 11%; G4 at risk 2% made +10% with a -21% drawdown.
   - At double costs every sized version loses (G4 risk 2%: -19%).
5. **Sep - Oct 2026 (already seen, not a test):** G1 / G4 made +2.7% at 5x and +11% at risk 2%, and G5 / G6 made +4.6%
   and +19%. Gold was very lively then, so every day counted as busy and G4 = G1.
6. **For Boss:** the most promising US-gold rule after six rounds is **G4**.
   - The rule: at 06:00 IST, only on busy mornings, trade when the EMA trend, RSI, MACD and the move since the US
     close all agree. Use a 0.5 ATR stop and a lock that keeps 60% of the open profit after +0.5 ATR.
   - It is not proven: it lost in 2023-24 and made its money in one 6-month stretch.
   - It is a forward-test candidate (paper, on an offshore or crypto-gold venue), at 5x or less, not money to risk
     yet.

## Amendment E: the whole day, hour by hour (Boss: "check whole day then try again")

*Pre-registered in PREREG.md Amendment E (commit cea07a6) before any number. Code `r140h.py`, tests `test_r140h.py`,
results `results/amendE_design.json`, `amendE_holdout.json` (+ `.lock`), `amendE_recent_seen.json`. US gold only;
information only. L1 (the 4-signal vote, 0.5 ATR stop, 60% lock after +0.5 ATR) and G4 (the same on busy days) were
run at every IST hour, out within 2 hours. 48 rules; BH over 90. Hour 06:00 reproduces L1 and G4 exactly (a check
on the code).*

**One hour passes every gate, including the one read of the unseen holdout: 11:00 IST on busy days (H11b).**

| H11b: 11:00 IST, busy days, the 4-signal vote | design Oct 2023 - Mar 2026 | holdout Apr - Aug 2026 (read once) | Sep - Oct 2026 |
|---|---|---|---|
| trades | 186 | 29 | 18 |
| win rate (65% rule: below) | 62% | 55% | 56% |
| avg net / 100-oz lot (2x costs) | $+95 ($+63) | $+58 ($+26) | $-16 |
| twin p / BH q (over 90) | 0.001 / 0.045 | - | - |
| folds F1..F5 | +275 / -972 / +180 / +4,094 / +14,140 | - | - |
| $1,000 at 5x | +21.5% (worst DD -3.6%) | +1.9% | -0.3% |
| $1,000 risking 2% a trade | +87.9% (worst DD -13.0%) | +6.5% | -1.5% |

How to read it:
- **The rule:** at 11:00 IST, only when gold's 15-minute ATR is above its usual 11:00 size, trade only if the EMA
  trend, RSI, MACD and the move since the last US close all point the same way. Stop 0.5 ATR, lock 60% of the profit
  after +0.5 ATR, out by 13:00.
- 11:00 IST is about 05:30-06:30 GMT: the end of Tokyo and the run-up to London.
- **Why it is still only a candidate:**
  - It is 1 hour out of 24 tried. The BH step allows for that (q 0.045), but the margin is small.
  - Most of the design profit came in one fold (Oct 2025 - Mar 2026: +$14.1k of +$17.7k a lot).
  - The holdout is positive but small (29 trades, +$58 a lot).
  - Sep - Oct 2026 is slightly negative (-$285 on 18 trades).
  - Its win rate (62%) is below Boss's 65% rule.
- **Every other hour fails.** The 06:00 rules (L1, G4) fail the fold gate. Hours 05:00, 18:00 and 22:00 show some
  profit but fail BH or the folds.
- **For Boss:** this is the best US-gold rule R140 found. It is for a forward test on paper on an offshore or
  crypto-gold venue, at 5x or less, for 3-6 months. It is not money yet and not an app arm (the app does not trade
  spot gold).

| IST hour | median 2-h range $ | vote (a): n, avg / 100 oz, twin p | busy + vote (b): n, avg, twin p, q (90) |
|---|---|---|---|
| 00:00 | 7.42 | 140, $-53, p 0.84 | 117, $-63, p 0.88, q 1.00 |
| 01:00 | 6.22 | 127, $-34, p 0.69 | 105, $-40, p 0.70, q 0.95 |
| 02:00 | 4.95 | 115, $+35, p 0.10 | 97, $+48, p 0.11, q 0.40 |
| 03:00 | 3.94 | 48, $+13, p 0.19 | 41, $+28, p 0.14, q 0.44 |
| 04:00 | 6.91 | 75, $-127, p 0.99 | 57, $-152, p 1.00, q 1.00 |
| 05:00 | 10.43 | 150, $+48, p 0.05 | 122, $+67, p 0.03, q 0.28 |
| 06:00 | 11.29 | 195, $+43, p 0.01 | 166, $+59, p 0.01, q 0.10 |
| 07:00 | 10.19 | 228, $-58, p 0.93 | 199, $-64, p 0.96, q 1.00 |
| 08:00 | 8.84 | 267, $-24, p 0.29 | 224, $-24, p 0.27, q 0.59 |
| 09:00 | 7.22 | 268, $-74, p 0.97 | 222, $-84, p 0.97, q 1.00 |
| 10:00 | 9.42 | 248, $+15, p 0.12 | 211, $+19, p 0.12, q 0.40 |
| 11:00 | 10.59 | 218, $+73, p 0.00 | 186, $+95, p 0.00, q 0.04 |
| 12:00 | 11.22 | 228, $-15, p 0.20 | 198, $-13, p 0.18, q 0.48 |
| 13:00 | 10.79 | 238, $-48, p 0.81 | 198, $-48, p 0.79, q 1.00 |
| 14:00 | 9.63 | 239, $+20, p 0.08 | 202, $+29, p 0.04, q 0.33 |
| 15:00 | 9.31 | 256, $-67, p 0.91 | 219, $-70, p 0.92, q 1.00 |
| 16:00 | 10.40 | 250, $-65, p 0.82 | 214, $-71, p 0.88, q 1.00 |
| 17:00 | 13.93 | 210, $-83, p 0.99 | 177, $-89, p 0.99, q 1.00 |
| 18:00 | 18.51 | 206, $+49, p 0.11 | 175, $+63, p 0.09, q 0.39 |
| 19:00 | 19.87 | 235, $-8, p 0.44 | 188, $-10, p 0.50, q 0.80 |
| 20:00 | 15.18 | 298, $+13, p 0.07 | 234, $+19, p 0.07, q 0.39 |
| 21:00 | 11.52 | 321, $-13, p 0.24 | 257, $-2, p 0.18, q 0.48 |
| 22:00 | 9.85 | 304, $+21, p 0.04 | 248, $+30, p 0.04, q 0.33 |
| 23:00 | 8.32 | 258, $+2, p 0.17 | 200, $+16, p 0.11, q 0.40 |

The spread is kept at $0.25 every hour, which flatters the quiet early-Asian hours a little.

## Amendment F: Boss's Pine indicator "5-Step Liquidity Sweep POI" on US gold

*Pre-registered in PREREG.md Amendment F (commit 0243f22). Code `r140p.py`, tests `test_r140p.py`, results
`results/amendF_design.json`, `amendF_holdout.lock`. The script is ported as written: the 60-minute EMA 50 trend,
pivot-3 swings, a +/- 0.5 ATR POI zone, a sweep plus a confirmation candle, and RR >= 1.5 with SL at the signal
candle's low / high and TP at the last swing. The port adds a 12-hour cap. BH over 95.*

**It does not work on gold: 0 of 5 pass, and the holdout was not read.** As written on 15-minute candles it wins only
32%: 172 of its 255 trades hit the stop, and only 73 reached the target. A $1,000 account at 5x would have fallen
-15% in 2.5 years. Adding the 60% lock lifts the win rate to 51% but loses more. The 5-minute version is about flat
before double costs and loses -$31k a lot at 2x.

| id | rule | n | win | avg / 100 oz | total 1x / 2x | twin p | q | $1,000 at 5x (2.5 y) |
|---|---|---|---|---|---|---|---|---|
| F1 | as written, 15m | 255 | 32% | $-32 | $-8,111 / $-16,271 | 0.61 | 0.92 | -15% (DD -21%) |
| F2 | 15m + 60% lock | 257 | 51% | $-49 | $-12,562 / $-20,786 | 0.74 | 0.98 | -20% (DD -22%) |
| F3 | as written, 5m | 1139 | 28% | $+5 | $+5,378 / $-31,070 | 0.07 | 0.39 | -21% (DD -44%) |
| F4 | 5m + lock | 1159 | 47% | $-22 | $-25,622 / $-62,710 | 0.37 | 0.71 | -44% (DD -53%) |
| F5 | 15m + lock, 06:00-08:00 IST only | 20 | 55% | $+109 | $+2,178 / $+1,538 | 0.10 | 0.39 | +2% (DD -3%) |

## Amendment G: Boss's second setup, the internal-origin-low sweep after a BOS (from his diagram)

*Pre-registered in PREREG.md Amendment G (commit 0d7e3e4). The Pine v5 code Boss mentioned did not arrive, so the
rule was built from his description. After a break of structure, the "origin" low that launched it gets swept (a dip
below, then a close back above) and the trade buys. The mirror is used for sells. SL is under the sweep, TP is the
impulse high, RR >= 1.5, with a 60% lock after 1R and a 12-hour cap. Code `r140k.py`, tests `test_r140k.py` (a
hand-built copy of the diagram fires exactly one BUY). BH over 99.*

**It loses in every version: 0 of 4 pass, and the holdout was not read.** Buys and sells both lose. The pattern
appears often (700-2,000 times in 2.5 years), but price keeps going after the sweep about as often as it recovers.

| id | rule | n | win | avg / 100 oz | total 1x / 2x | twin p | q | $1,000 at 5x (2.5 y) |
|---|---|---|---|---|---|---|---|---|
| K1 | 15m, strict sweep | 702 | 45% | $-41 | $-28,571 / $-51,035 | 0.77 | 1.00 | -41% (DD -47%) |
| K2 | 15m, simple break | 946 | 45% | $-73 | $-69,428 / $-99,700 | 0.99 | 1.00 | -66% (DD -68%) |
| K3 | 5m, strict | 2035 | 39% | $-35 | $-71,305 / $-136,425 | 0.81 | 1.00 | -69% (DD -70%) |
| K4 | 15m, strict, with the 60m trend | 405 | 47% | $-15 | $-6,272 / $-19,232 | 0.44 | 0.77 | -16% (DD -22%) |

## Amendment H: Boss's refined Pine "5-Step Liquidity Sweep POI [Refined]"

*Pre-registered in PREREG.md Amendment H (commit 67651d5). Ported as written: the HTF trend one more completed hour
back, the TP at the older swing / newer swing / close + 3 ATR, and the SL at min(low, swing - 0.25 ATR). Code
`r140q.py`, tests `test_r140q.py`. BH over 104.*

**It does not work on gold: 0 of 5 pass, and the holdout was not read.**
- As written (15m), it wins 30%. 228 of 332 trades hit the stop.
- Shorts lose most (-$23.7k a lot against +$2.7k for longs).
- The best line, R5 (the HTF filter switched off, plus the lock), made +$16 a lot on 877 trades and beat its twins
  (p 0.034). But it fails BH (q 0.35), loses at 2x costs, and made all its money in Oct 2025 - Mar 2026 (+$29k after
  -$15k in the fold before).

| id | rule | n | win | avg / 100 oz | total 1x / 2x | twin p | q | $1,000 at 5x (2.5 y) |
|---|---|---|---|---|---|---|---|---|
| R1 | refined Pine as written, 15m | 332 | 30% | $-63 | $-20,989 / $-31,613 | 0.78 | 1.00 | -28% (DD -29%) |
| R2 | refined Pine, 15m + 60% lock | 337 | 54% | $-11 | $-3,656 / $-14,440 | 0.36 | 0.75 | -8% (DD -15%) |
| R3 | refined Pine as written, 5m | 1418 | 25% | $-35 | $-49,380 / $-94,756 | 0.74 | 1.00 | -64% (DD -65%) |
| R4 | refined Pine, 5m + lock | 1469 | 46% | $-37 | $-54,644 / $-101,652 | 0.80 | 1.00 | -63% (DD -63%) |
| R5 | refined Pine, 15m, HTF filter off, + lock | 877 | 54% | $+16 | $+13,921 / $-14,143 | 0.03 | 0.35 | +5% (DD -28%) |

## Amendment I: break and retest (Boss's screenshot)

*Pre-registered in PREREG.md Amendment I (commit eeb5c1e). Built from the screenshot: a swing level breaks (a close
beyond a +/- 0.25 ATR zone). Price comes back into the zone within 30 candles and closes on the broken side. SL is
0.1 ATR past the zone and TP is 2R; T2-T4 add the 60% lock. Code `r140t.py`, tests `test_r140t.py`. BH over 108.*

**The screenshot's trade was a good one, but the pattern loses over 2.5 years: 0 of 4 pass, and the holdout was not
read.**
- As drawn (15m, TP 2R), it made 1,574 trades and won 34%. 1,026 hit the stop and 516 reached the target, for -$34 a
  lot.
- The sells, like the screenshot's, lost most (-$70.5k a lot; the buys made +$16.5k).
- On 5-minute candles, or with the trend filter, it loses too. A $1,000 account at 5x would have lost 52-87%.

| id | rule | n | win | avg / 100 oz | total 1x / 2x | twin p | q | $1,000 at 5x (2.5 y) |
|---|---|---|---|---|---|---|---|---|
| T1 | break and retest, 15m, SL past the zone, TP 2R | 1574 | 34% | $-34 | $-54,027 / $-104,395 | 0.71 | 1.00 | -71% (DD -78%) |
| T2 | break and retest, 15m, + 60% lock | 1620 | 48% | $-45 | $-73,153 / $-124,993 | 0.87 | 1.00 | -77% (DD -81%) |
| T3 | break and retest, 5m, + lock | 4997 | 48% | $-18 | $-92,050 / $-251,954 | 0.05 | 0.39 | -87% (DD -91%) |
| T4 | break and retest, 15m, + lock, with the 60m trend | 1058 | 49% | $-39 | $-41,430 / $-75,286 | 0.78 | 1.00 | -52% (DD -59%) |

## Amendment J: Boss's "Internal SMC BOS & Sweep Signals (HL/LH)" (Pine v5)

*Pre-registered in PREREG.md Amendment J (commit 0a20db7). Ported as written: the first break against the trend is a
silent CHoCH, and the next break with the trend is a BOS that arms the origin HL (LH). A wick through it that closes
back is the signal; a close through it disarms. The script has no exits, so SL = the sweep wick and TP = 2R, with a
12-hour cap; J2-J5 add the 60% lock. Code `r140j.py`, tests `test_r140j.py` (a hand-built CHoCH -> BOS -> HL sweep
fires one BUY). BH over 113.*

**It does not work on gold: 0 of 5 pass, and the holdout was not read.**
- As written (15m), it made 232 trades and won 39%, for -$108 a lot.
- Buys lose more than sells (-$18.4k against -$6.6k a lot).
- A $1,000 account at 5x would have fallen 17-37% in 2.5 years.
- **A logic point in the script:** with "Confirm BOS on Candle Close" switched off, it can never signal. The wick that
  sweeps the HL is also a wick break of that same low. The bearish block runs first, flips the trend and cancels the
  buy setup, so J3 has 0 trades.

| id | rule | n | win | avg / 100 oz | total 1x / 2x | twin p | q | $1,000 at 5x (2.5 y) |
|---|---|---|---|---|---|---|---|---|
| J1 | Internal SMC BOS + HL/LH sweep, 15m, SL at the sweep wick, TP 2R | 232 | 39% | $-108 | $-25,007 / $-32,431 | 0.92 | 1.00 | -28% |
| J2 | Internal SMC sweep, 15m, + 60% lock | 232 | 51% | $-105 | $-24,276 / $-31,700 | 0.95 | 1.00 | -24% |
| J3 | Internal SMC sweep, 15m, wick BOS (confirmClose off), + lock | 0 | 0% | $+0 | $+0 / $+0 | 1.00 | 1.00 | +0% |
| J4 | Internal SMC sweep, 5m, + lock | 577 | 41% | $-49 | $-28,069 / $-46,533 | 0.87 | 1.00 | -37% |
| J5 | Internal SMC sweep, 15m, + lock, with the 60m trend | 154 | 51% | $-106 | $-16,355 / $-21,283 | 0.93 | 1.00 | -17% |

## What is new compared with the earlier gold work

- GOLD_15M_PATTERNS and GOLD_30M_1H_PATTERNS had unconditional hour tables (01:00 UTC green 50.8%, 02:00 UTC 50.7%).
  R140 conditions the window on the US session, the prior day, the first candle and release days, and splits it by US
  daylight-saving time. None of these conditions helps.
- GOLD_SESSION_STRATS tested the Asian-range sweep at London (it lost). R140 tests the 06:00-08:00 range breaking or
  failing right after 08:00, while Asia is still trading. Both directions lose.
- HUNT_R2_US_MCX tested the whole US overnight move -> MCX. R140 isolates the 06:00-08:00 IST piece. It is visible in
  GOLDM's first hour (correlation +0.36) but too small to trade (+Rs 523 gross against about Rs 400 of costs per lot).
- GOLD_1H_PLUS ("no Asian-session buys": little change) and GOLD_REOPEN (03:35-06:25 reopen entries, t 0.55) agree: the
  Asian hours carry no tradable edge.

## Variants (design period; 1x costs; folds in order; win rate against Boss's 65% rule)

XAUUSD in $ for 1 lot of 100 oz (design 2 Oct 2023 - 31 Mar 2026; walk-forward signs, so F1 is training only for A1-A3
and A6). GOLDM in Rs for 1 lot (design 5 Aug 2025 - 31 Mar 2026). Twin p: 1,000 sets of same-clock-time trades on random
days with random sides. BH over all 12.

| id | rule | n | win (65% rule) | avg net | total 1x | total 2x | folds | twin p | BH q | drawdown | worst month | passes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A1 | first candle -> rest of window | 494 | 49% (below) | $ -17 | $ -8,535 | $ -24,343 | - / -8,911 / +349 / +6,673 / -6,647 | 0.443 | 0.737 | $ -20,879 | $ -4,654 | trades |
| A2 | US session -> window | 487 | 51% (below) | $ -43 | $ -21,154 | $ -36,738 | - / -6,708 / -4,747 / +9,091 / -18,790 | 0.552 | 0.737 | $ -28,914 | $ -6,834 | trades |
| A3 | prior day -> window | 487 | 47% (below) | $ -43 | $ -20,856 | $ -36,440 | - / -4,444 / -2,500 / -10,309 / -3,604 | 0.624 | 0.738 | $ -36,361 | $ -5,736 | trades |
| A4 | Asian-range breakout, 08:00-11:00 | 396 | 50% (below) | $ -23 | $ -9,235 | $ -21,907 | -3,572 / -4,197 / -4,176 / +7,082 / -4,371 | 0.433 | 0.737 | $ -18,831 | $ -10,316 | trades |
| A5 | Asian-range fade, 08:00-11:00 | 396 | 49% (below) | $ -52 | $ -20,551 | $ -33,223 | +255 / +1,472 / -701 / -16,629 / -4,948 | 0.676 | 0.738 | $ -22,919 | $ -6,786 | trades |
| A6 | release mornings: US session -> window | 60 | 50% (below) | $ +57 | $ +3,441 | $ +1,521 | - / +952 / -1,897 / +6,143 / -1,758 | 0.151 | 0.674 | $ -4,574 | $ -2,793 | net, trades |
| B1 | GOLDM follows window, 09:05-10:00 | 151 | 52% (below) | Rs -345 | Rs -52,122 | Rs -112,605 | -8,509 / -8,026 / -49,812 / +14,225 | 0.225 | 0.674 | Rs -66,348 | Rs -38,924 | trades |
| B2 | GOLDM follows window, 09:05-close | 151 | 64% (below) | Rs -65 | Rs -9,804 | Rs -70,298 | +2,086 / +5,791 / -59,563 / +41,883 | 0.287 | 0.688 | Rs -75,483 | Rs -34,186 | trades, folds |
| B3 | B2 on big windows only | 105 | 68% (meets) | Rs +112 | Rs +11,711 | Rs -30,918 | +185 / +5,593 / -34,586 / +40,519 | 0.201 | 0.674 | Rs -43,855 | Rs -17,981 | trades, folds |
| B4 | gap under-reacted, 09:05-14:00 | 72 | 65% (meets) | Rs +378 | Rs +27,199 | Rs -2,052 | -5,942 / -1,540 / -18,816 / +53,497 | 0.078 | 0.674 | Rs -30,146 | Rs -11,726 | trades, account |
| B5 | GOLDM fades window, 09:05-10:00 | 151 | 45% (below) | Rs -735 | Rs -111,053 | Rs -171,536 | -17,529 / -20,878 / -18,005 / -54,641 | 0.775 | 0.775 | Rs -117,524 | Rs -34,171 | trades |
| B6 | window range as GOLDM levels | 109 | 60% (below) | Rs -269 | Rs -29,324 | Rs -73,229 | -3,343 / -10,476 / +12,833 / -28,339 | 0.546 | 0.737 | Rs -45,351 | Rs -15,382 | trades |

Account stop (-Rs 40k drawdown, -Rs 13k a month): B1, B2, B3, B5 and B6 break the monthly limit (-Rs 15k to -Rs 39k).
B1, B2, B3, B5 and B6 also break the drawdown limit.

## The window, candle by candle (design, 575 days)

| IST candle | green | median body $ | median range $ | mean bp |
|---|---|---|---|---|
| 06:00 | 54.6% | 1.28 | 2.76 | +0.55 |
| 06:15 | 47.7% | 1.20 | 2.98 | -0.28 |
| 06:30 | 53.0% | 2.33 | 5.65 | +1.06 |
| 06:45 | 50.3% | 1.84 | 4.34 | +0.89 |
| 07:00 | 54.8% | 1.85 | 4.44 | +1.86 |
| 07:15 | 50.3% | 1.72 | 3.90 | -0.65 |
| 07:30 | 52.5% | 1.65 | 3.89 | +0.62 |
| 07:45 | 52.0% | 1.17 | 3.01 | +0.30 |

| weekday | n | mean bp | up | mean abs move bp |
|---|---|---|---|---|
| Mon | 116 | +6.2 | 58% | 32.1 |
| Tue | 117 | +5.0 | 53% | 24.5 |
| Wed | 114 | +10.0 | 61% | 22.9 |
| Thu | 114 | -1.4 | 54% | 21.7 |
| Fri | 114 | +2.1 | 54% | 26.8 |

Monday's window is the most active (32 bp on average). It is the first Asian session after the weekend. No weekday's
drift is separable from gold's up-trend; with about 115 days each, the standard error is about 4-5 bp.

| MCX link (design, non-roll days) | n | corr: 09:00 gap | corr: 09:05-10:00 | corr: 09:05-close | same sign 09:05-10:00 | same sign 09:05-close |
|---|---|---|---|---|---|---|
| all | 132 | +0.30 | +0.36 | +0.04 | 55% | 50% |
| US summer | 65 | +0.34 | +0.24 | +0.13 | 54% | 52% |
| US winter | 67 | +0.28 | +0.40 | +0.00 | 57% | 48% |

After 08:00, XAUUSD from 08:00 to 12:30 IST goes the window's way on 51% of days (correlation +0.06, 575 days).

## Full period, made after the holdout step (labelled; no decision uses it)

- The holdout was not read for any rule: `results/holdout.lock` says "no variant (none passed)".
- The descriptive tables were then repeated on all 713 window days (Oct 2023 - 30 Oct 2026). The whole holdout period
  is US summer time.
- **Apr - Oct 2026 (138 days):** the window became much bigger, with a median range of **$28.9** (66 bp) and a median
  move of $13.8, because gold's price and volatility rose. It went up on 50% of days, at -0.9 bp on average (se 3.9). So
  the design period's upward drift did not continue.
- **MCX link in the holdout (118 days):** correlation with the gap +0.23, with 09:05-10:00 +0.29, and with 09:05 to the
  close +0.19. Same sign 53% (to 10:00) and 59% (to the close). The link has the same size and is still a coin flip
  for direction.

## Method and data (short)

- **XAUUSD:** Dukascopy BID 1-minute candles: the repo's `research-data` release (Oct 2023 - Sep 2026, sha256 checked)
  plus a Dukascopy tail to 30 Oct 2026. The Drive lake's `xauusd_1m/` holds no price file.
  - Older years were not used. Dukascopy answered HTTP 429 even to one request at a time (about 7 days a minute), so
    2012-2023 would have taken more than 6 hours.
  - That leaves about 3 years (713 window days).
- **GOLDM:** the near future's minute closes from the lake's GOLDM option book, 289 sessions opening by 09:02 (Aug 2025
  - Oct 2026). The true futures minute file starts only in May 2026.
- **USD/INR:** not in the lake. The onshore rupee market is shut from 06:00 to 08:00 IST, so the window's rupee move is
  XAUUSD's % move. The overnight rupee change is part of the 09:00 gap (B4 treats it as noise).
- **Costs:**
  - XAUUSD: $0.25 spread plus $7 commission per 100-oz lot.
  - GOLDM: Zerodha charges (brokerage, CTT, MCX txn, SEBI, stamp, GST) plus half the spread on each fill
    (max(1 tick, 1 bp)/2), 1 lot.
  - 2x doubles every cost.
- **Stops and locks:**
  - XAUUSD: stop at 1 ATR (15-minute candles); lock at 60% of the peak after +1 ATR.
  - GOLDM: stop at 0.30%; lock at 60% of the peak after +0.20%.
  - All fixed in advance; none tuned.
- **Gates:** net > 0 at 1x and 2x costs; random same-time twins p <= 0.05; BH q <= 0.10 over 12 rules; enough trades;
  walk-forward folds; the account stop for MCX; then one holdout read.
- **Tests:** 17 unit tests (`test_r140.py`) cover:
  - the engines (stop, gap through the stop, lock, time exit, close-only engine);
  - costs at 1x and 2x;
  - DST mapping;
  - the window table and its 110-minute rule;
  - look-ahead guards (window signals and ATR unchanged when later prices change);
  - the walk-forward sign uses only earlier days;
  - the range trigger, BH, drawdown, twin p and gate logic.
