# R86b pre-registration: reverse-engineering HOW the big players trade, from index and constituent minutes

Written 19 Oct 2026, committed BEFORE any outcome is computed. Research only: nothing in `android/**` or `.github/**`.

Boss, 19 Oct (after R86): *"You could also test on index data, i dont want who are the big players, i want to reverse
engineering and find out how do they trade, find what could be thinking while buying or selling trades."*

R86 asked "does a published positioning number predict the market?". R86b asks the other way round: **from the price
and volume path itself, what does big-money trading look like, and can the bot spot it early enough to use it?**

## 1. Data (point in time; nothing raw is committed)

- NIFTY 1-minute index bars (2019 -), BANKNIFTY (Aug 2021 -), Drive lake `index_1m`.
- 1-minute OHLCV of the 59 current NIFTY 50 + NIFTY BANK stocks (Drive lake `equity_1m`, 2019 - 12 Oct 2026). The
  NIFTY basket = all 59; the BANK basket = the 14 bank stocks (AUBANK, AXISBANK, BANKBARODA, CANBK, FEDERALBNK, HDFCBANK,
  ICICIBANK, IDFCFIRSTB, INDUSINDBK, KOTAKBANK, PNB, SBIN, UNIONBANK, YESBANK). Today's constituents only (survivorship,
  stated). Turnover = close x volume.
- NSE participant OI (R86's loader), used ONLY as an after-the-close label in Part 1 (never as a signal).

## 2. Part 1: footprints (descriptive, not gated; design years 2019-2024 only)

Day labels from the participant file of the SAME day (known only after the close, so description only):
FII-BUY / FII-SELL = the FII change in index-futures net contracts (long - short, today minus yesterday) in the top /
bottom 10% of the previous 250 days; the same for Pro and Client. Plus: expiry days (NIFTY monthly futures expiry),
month-end sessions, and all other days.

For each label and index, the mean (with a bootstrap 90% interval, 1,000 draws) of:
- the day's move (open -> close, %), the opening gap's share of the close-to-close move;
- the share of the open -> close move made in each hour (09:15-10:15, ..., 14:15-15:30);
- efficiency ER = abs(close - open) / sum of abs 1-minute changes (a steady one-way drift is high; chop is low);
- the first-30 and last-30 minute shares of the day's basket turnover;
- basket-burst minutes (section 3, T2 definition) per day, and their sign agreement with the day's move;
- the correlation, inside the day, between a minute's absolute return and its basket turnover.

Also reported: the minute-of-day turnover profile, when basket bursts happen (by half hour, by weekday, on expiry and
month-end days), and what follows bursts and absorption bars on average (signed 15/30/60-minute move vs random minutes).

## 3. Part 2: can the bot spot it early? (8 gated tests, one BH family)

Signal source: the index's own bars and its constituents' minute volume and prices (the underlying). No option chart.
Trades are on the index future (futures-equivalent: index points x the lot in force, R86's cost model: Rs 40, dated STT,
exchange, SEBI, stamp, GST, 0.02% slippage a side, 0.04% on stops; overnight trades also pay/receive carry). Every rule
has a stop.

| id | footprint (what a big player would be doing) | rule |
|---|---|---|
| T1-NIFTY, T1-BANKNIFTY | **steady accumulation** (a VWAP/TWAP algo working an order: a one-way drift with few pullbacks) | At 11:30: morning move m = close(11:29) / open(09:30) - 1, morning ER over 09:30-11:29. If ER is in the top 20% and abs m above the median of the previous 250 sessions' values AND morning basket turnover >= its 20-session median: follow sign(m) at the 11:30 open, stop = entry -+ 0.5 x the 09:30-11:29 range, exit 15:15. |
| T2-NIFTY, T2-BANKNIFTY | **program / basket trade** (index arbitrage, ETF creation, a fund buying the whole index) | A minute 09:30-14:45 where >= 80% of the basket stocks that printed moved the same way (close vs previous minute's close) AND basket turnover >= 3x the median of the same minute over the previous 20 sessions: follow its direction at the next minute's open, stop = 2 x that minute's index range (at least 0.05%), exit after 30 minutes or 15:15. One position at a time; at most 3 entries a day. |
| T3-NIFTY, T3-BANKNIFTY | **absorption** (a large passive seller/buyer soaking up the crowd at the day's extreme) | A completed 5-minute bar after 10:15 that makes a new day high (low), with basket turnover >= 2x and index range <= 0.5x the same 5-minute slot's medians over the previous 20 sessions: fade at the next minute's open, stop 0.02% beyond the bar's extreme, target 2R, exit after 60 minutes or 15:15. One position at a time. |
| T4-NIFTY, T4-BANKNIFTY | **close / benchmark flow** (funds executing into the close; "smart money moves at the end") | If the 14:30-15:24 move is in the top or bottom 10% of the previous 250 sessions' and the last-hour basket turnover share is above its 250-session median: follow it overnight, entry at the 15:25 open, exit at the next session's 09:30 close; stop = 1 x ATR14 of daily ranges, checked at the next open (a gap through it fills at the open) and then on 09:15-09:29 minutes. |

Thresholds (80%, 3x, 2x, 0.5x, top 20%, top/bottom 10%) are fixed now, before any data is scored. Medians and
percentiles use only sessions before the decision day (the same day's earlier minutes for intraday values).

## 4. Periods and twins

- Design 2019 - 31 Dec 2024 (BANKNIFTY from Aug 2021); walk-forward folds = the years 2022, 2023, 2024 (the rules have
  no fitted parameters; folds are reported year by year and pooled). Final 1 Jan 2025 - 12 Oct 2026, read ONCE
  (`results/FINAL_B_READ`), only for tests that pass the design gates. If none pass, the final is not read.
- Random twins (seed 861, 500): T1 and T4 = the same count of random sessions in each fold year, the rule's sides
  shuffled, the same stop/exit rules; T2 and T3 = the same day, a random minute in the rule's window, the same side and
  stop distance in %, the same exit rules.

## 5. Pass rule (as R84/R85/R86)

Design: pooled folds net > 0 and > the twins' mean; twin p < 0.10; BH q < 0.10 over the 8 tests; net without its top 5
trades > 0. Final (read once): net > 0, > the final twins' mean, and > 0 without the top 5. Labels: proven / not proven.

## 6. Tests and logging

`research/hunt/r86/test_r86b.py`: basket direction share and burst flag on hand-made minutes; ER; absorption bar;
late-flow decile; every signal with a look-ahead guard (minutes after the decision changed -> same decision); the
overnight simulator (gap through the stop). One `research/experiments/entries/r86b-*.yaml` per idea; a FINDINGS
section; the write-up `research/HUNT_R86B_HOW_BIG_PLAYERS_TRADE.md`.
