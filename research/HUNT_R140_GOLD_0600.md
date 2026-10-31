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
