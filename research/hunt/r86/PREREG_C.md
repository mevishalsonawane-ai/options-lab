# R86c pre-registration: five "out of the box" ways to catch the big players (committed before any outcome)

Written 19 Oct 2026, BEFORE any outcome is computed. Research only: nothing in `android/**` or `.github/**`.

Boss, 19 Oct: *"Is there any way we could catch it, think out of the box"* -> *"run all of them, 1 to 5"*.

R86b found: big SELLING is front-loaded (61% of NIFTY's move by 10:15 on FII-sell days), big BUYING is worked slowly
into the close; month-end closes carry 27% of turnover; footprints are visible but the obvious "follow" rules lost.
R86c tests five new angles built on those facts. One BH family of 10 tests.

## Data (point in time; nothing raw committed)

R86/R86b's lake copies in /dev/shm: index 1-minute (NIFTY 2017-, BANKNIFTY Aug 2021-), INDIAVIX 1-minute, the 59
constituents' minute turnover and direction (R86b basket tables), NSE participant OI (labels only, idea 5), R28's GEX
table (only its `dte` column, to skip each index's own expiry day for option trades), and the Drive lake's rolling
weekly option minutes `opt_<UND>_WEEK_<YEAR>` (nearest expiry, ATM -10..+10, 1-minute OHLC, volume, OI, strike).

## Periods

Design = data start - 31 Dec 2024, walk-forward folds = the years 2022, 2023, 2024 (C2: every design year from 2018,
because month-ends are few). Final = 1 Jan 2025 - the data end (12-14 Oct 2026), read ONCE (`results/FINAL_C_READ`)
and only for tests that pass the design gates. If none pass, the final is not read.

## The 10 tests

Signal source is the underlying (index minutes + constituents) for C1, C2, C3, C5. C4's trigger is OPTION-CHAIN
volume/OI (not an option price chart), confirmed by the underlying's own move; it is labelled **option-flow, not the
default class**, reported separately, and could only ever be switched on by Boss. Futures-equivalent trades use R86's
cost model (Rs 40, dated STT, exchange, SEBI, stamp, GST, slippage 0.02% a side / 0.04% on stops, carry overnight).

| id | idea | rule (fixed now) |
|---|---|---|
| C1-NIFTY, C1-BANKNIFTY | **trade only the slow side**: on a steady, busy UP morning the patient buyer is still buying; buy the dip to VWAP | Day qualifies at 11:30 as R86b T1 but only when the 09:30-11:29 move is UP (ER top 20% and abs move above median of the previous 250 sessions, morning turnover >= 20-session median). Then the first minute from 11:30 to 14:30 whose low touches the turnover-weighted VWAP (from 09:15, R86 V): buy at the next minute's open; stop = entry - 0.5 x the 09:30-11:29 range; exit 15:15. Down mornings are skipped (their move is mostly done). |
| C2-NIFTY, C2-BANKNIFTY | **fade the month-end closing push** (benchmark trades, not information) | On the last session of each month: the 15:00-15:24 move; sell at the 15:25 open if it was up, buy if down; exit at the next session's 09:30 close; stop 1 x ATR14 (daily, to yesterday), checked at the next open (a gap through fills at the open) and on 09:15-09:29 minutes. |
| C3-NIFTY, C3-BANKNIFTY | **use footprints to pick busy afternoons, buy volatility** | At 11:30, forecast log(11:30-15:15 range / price) by OLS on: log VIX at 11:29, log 09:15-11:29 range, log mean afternoon range of the previous 20 sessions, log early-volume ratio (R86 V), basket bursts 09:30-11:29 (R86b T2 definition), abs overnight gap. Walk-forward by year. Buy the ATM straddle (the call and put of the strike nearest the 11:29 spot, nearest weekly expiry) at the 11:30 minute's open when the forecast is in the top 20% of the fitting years' forecasts; hold the same two contracts to 15:15 (no stop; the loss is capped at the premium). Never on the index's own expiry day. 1 lot in force. Option costs: research/obuy Costs('dated') charges + slippage 5 bps + Rs 0.05 a side. |
| C4-NIFTY, C4-BANKNIFTY | **unusual option buying in one OTM strike** (informed traders use options first) | 5-minute windows from 09:30 to 14:30: an OTM call (offset +3..+8) or put (-3..-8) whose 5-minute volume >= 8x the median 5-minute volume of the same offset and side over the previous 20 sessions AND whose OI rose in the window AND the index moved the same way in the window (up for calls, down for puts): enter the index future at the next minute's open in that direction; stop 0.15%; exit after 30 minutes or 15:15. One position at a time; at most 3 a day. |
| C5-NIFTY, C5-BANKNIFTY | **learned "FII buying day in progress" detector** (buying days continue into the close) | Label each design day FII-BUY if the FII change in index-futures net is in the top 10% of its previous 250 days (R86b). At 12:00, features from 09:15-11:59: gap %, the 09:15-10:14 move, the 09:15-11:59 move, ER, the early-volume ratio, up bursts, down bursts, first-30-minute turnover share. Logistic regression (sklearn, C = 1, standardised), trained on all days before the fold year. Go long the index future at the 12:00 open when P(FII-BUY) is in the top 10% of the training days' probabilities and the 09:15-11:59 move is up; stop = entry - 0.5 x the 09:15-11:59 range; exit 15:15. |

## Twins (seed 862, 500 each) and the pass rule

- C1, C5: random sessions of the same fold years (same count per year), long, at a random minute in the rule's entry
  window (C1 11:31-14:31, C5 12:00), the same stop in % and exit.
- C2: random NON-month-end sessions of the same years (same count), the same fade rule.
- C3: random non-expiry sessions of the same fold years (same count), the same straddle trade.
- C4: the same day, a random minute 09:35-14:35, the same side and stop in %, the same exit.
- p = (1 + #twins with net >= the rule's) / 501.

Pass (as R84-R86b), design: pooled net > 0 and > the twins' mean, twin p < 0.10, BH q < 0.10 over the 10 tests, net
without its top 5 trades > 0. Final (once): net > 0, > its twins' mean, > 0 without the top 5. A pass would only be a
PAPER candidate (logged in the Autopilot timeline, undoable, never live by itself); C4 additionally needs Boss's switch.

Reported, not gated: C1's mirror on DOWN mornings; C3's forecast error vs the VIX + range baseline; C5's walk-forward
AUC for FII-BUY.

## Tests and logging

`research/hunt/r86/test_r86c.py`: VWAP touch after 11:30; month-end detection (holidays, last session of the month);
straddle tracking of the SAME strike while the rolling offset changes; the option-volume spike (needs OI up and the
index confirming); the detector's features at 12:00 with a look-ahead guard (minutes after the decision changed -> the
same decision). One `research/experiments/entries/r86c-*.yaml` per idea; a FINDINGS section; the write-up
`research/HUNT_R86C_CATCH_THEM.md`.
