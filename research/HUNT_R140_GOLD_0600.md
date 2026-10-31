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
