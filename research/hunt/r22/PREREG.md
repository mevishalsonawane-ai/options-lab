# R22 pre-registration: the morning's three 15-minute candles as support and resistance through the day

Written 13 Oct 2026, BEFORE any number of this study was computed. Part A (the levels, the event study protocol and the
trading-test framework: data, costs, splits, twins, gates, BH family) is committed on its own before the first run. Part
B (at most 6 trading rules) is added in a second commit AFTER the design-period event study and BEFORE any trading test.
Both commit hashes are quoted in the write-up (`research/HUNT_R22_ORB_15M_LEVELS.md`). Nothing below may change after its
commit.

## Why

Boss, 13 Oct 2026: "Test the morning's three 15-minute candles, the high and the low of that, then make support and
resistance from there and check the ORB." Already known (read before writing this, not repeated): the ORB arm's range is
the 5-minute bars labelled 09:15 .. 10:00 (so it also holds the 10:00 bar) and the arm lost after costs in every tested
year (paused); R20 (adaptive range), R20b (S/R levels as the range) and R20c (2-close breaks of levels, the opening
range among them) all failed; Range Fade (fading the edges) lost every year. New here: the 09:15-09:59 range's edges, mid,
candle extremes and extensions used as support and resistance LATER in the day (touch and reject, break and hold, break
and retest, extension targets).

## Part A1. Data, splits (R17 / R20 / R20b / R20c's, unchanged)

- Index and options: the `research-data` release through R17's loader (`research/hunt/r17/data.py`): BANKNIFTY 13 Feb
  2024 - 23 Feb 2026, NIFTY 1 Apr 2024 - 13 Apr 2026 (1-minute index; options of the nearest expiry AFTER the day).
  FINNIFTY: index minutes only (release `finnifty_index.parquet`): event study only. MIDCPNIFTY: not in the release
  (stated, skipped).
- One switch: `R22_SOURCE=release` (default) or `R22_SOURCE=lake` (the 6-year Dhan lake through R17's adapter, once it is
  published). On the lake the holdout is each index's last 12 months (R17's `holdout_start`), outputs go to
  `results/lake/` and the cache folder `r22_lake`, with their own holdout flag.
- Holdout (locked, read ONCE for all of R22, after Part B is committed and the design table is frozen in
  `results/design.json`): BANKNIFTY sessions from 25 Aug 2025, NIFTY and FINNIFTY from 13 Oct 2025. Design = earlier
  sessions. The event study uses design sessions only; its holdout tables are printed only after the one holdout read, as
  information.
- ATR14 = the app's `ShadowRules.atr14` (mean true range of the 14 sessions BEFORE the day); the first 15 sessions of
  each index are left out of everything. VIX = India VIX previous close (R17's `Day.vix_prev`; FINNIFTY borrows the same
  day's value). Expiry day = the session is the index's nearest expiry (`research/brain/calendar.next_expiry`; n/a for
  FINNIFTY).

## Part A2. The levels (strictly the three 15-minute candles 09:15, 09:30, 09:45; all closed by 10:00)

Grid: R17's `Day.ix`, 375 rows, row r = the minute labelled 09:15 + r. Candle j (j = 0, 1, 2) = rows 15j .. 15j + 14.
Nothing from row 45 (the minute labelled 10:00) or later is read to build a level.

| level | definition |
|---|---|
| OH, OL | the highest high / lowest low of rows 0 .. 44 |
| OM | (OH + OL) / 2 ; range R = OH - OL |
| C1H .. C3L | each candle's own high and low (an "inner" candle level is one not equal to OH / OL) |
| X+0.5, X+1, X-0.5, X-1 | extensions OH + 0.5R, OH + R, OL - 0.5R, OL - R |
| P10 | the close of row 44 (the price at 10:00), the reference of the random-level twin |

Look-ahead guard (pytest): for random synthetic days, every level is identical when every minute from row 45 on is
replaced by noise; and every event confirmed by a cut minute is identical when every minute after the cut is replaced by
noise.

## Part A3. The event study (descriptive, design period only)

Candles after 10:00: 5-minute bars labelled 10:00 .. 14:55 (k = 9 .. 59, the last closes at 15:00) and, for breaks only,
15-minute bars labelled 10:00 .. 14:45 (k = 3 .. 19). "Beyond OH" = a close > OH; "inside" = a close <= OH (OL mirrored).
One event of each kind per (index, day, edge, timeframe): the first.

| event | definition (OH; OL mirrored) | event time = decision | direction measured |
|---|---|---|---|
| touch | the first 5-min bar whose high >= OH | - | (count only) |
| REJECT | the touch bar and the next 2 bars all close inside | the 3rd close | down (back into the range) |
| BREAK | the first close beyond (5-min or 15-min) | its close | up |
| HOLD | the break bar and the next 2 bars of its timeframe all close beyond | the 3rd close | up |
| FAIL | a break that is not a hold: the first close back inside within the next 2 bars | that close | down |
| RETEST-HOLD | after a 5-min HOLD, the first later bar whose low <= OH + 0.10R, if it closes beyond | its close | up |
| RETEST-FAIL | the same first retest bar, if it closes inside | its close | down |
| X reach | after a 5-min BREAK: the 1-minute high reaches OH + 0.5R / OH + R by 15:29, and before any 5-min close back inside | - | (share) |
| MID-REJECT | the first 5-min bar touching OM from the side of the previous close (low <= OM after a close above; mirrored), if it and the next 2 bars close on that side | the 3rd close | away from OM |
| INNER-REJECT | the same for each inner candle level, approached from the previous close's side (pooled) | the 3rd close | away from the level |

Events whose decision is after 15:00 are dropped (retests and rejects may not finish). Measured after each event (index
points; `_a` = / ATR14): the move over 15, 30, 60 minutes and to the 15:29 close (direction x (close then - decision
close)); MFE / MAE over 60 minutes; "excess" = move minus the mean same-direction move from the same clock minute over
the index's design sessions (removes time-of-day drift). Shares: reject / hold / fail / retest-hold rates per touch or
break, with their denominators.

Random-level twin (`_rnd` rows): every level mirrored around P10 (x -> 2 P10 - x): twin OH = 2 P10 - OL, twin OL =
2 P10 - OH (so each twin edge sits at the OTHER real edge's distance from the 10:00 price), the range and extensions
mirrored the same way; the same event logic.

Breakdowns, each with its count (cells with < 30 events flagged "n<30"): index (BANKNIFTY, NIFTY, FINNIFTY); range width
W = R / ATR14 in design terciles pooled over the indices (narrow / normal / wide; the two cut points printed with the
table); gap = (09:15 open - previous close) / ATR14 (down <= -0.15, flat, up >= +0.15); VIX previous close (< 13, 13-16,
>= 16); expiry day vs not; time of the event (10:00-11:00, 11:00-12:30, 12:30-15:00). t-values treat events as
independent (descriptive only; no event-study number enters the gates).

## Part A4. The trading-test framework (fixed now; Part B only picks the rules)

**Baseline** = today's ORB, exactly R20's V0: range = 5-min bars 09:15 .. 10:00, a 5-min close beyond it, decision bars
10:05 .. 13:55, at most 1 entry a side a day (a new decision only from the bar after an exit), strike = ATM of the 09:20
bar's close held all day, ORB exits. Reported beside every rule on the same sessions.

**New rules**: option buying, BANKNIFTY and NIFTY (a day's net = the sum of both; each index's own cut applies). Levels
from Part A2. Signals on 5- or 15-minute closes from the bar labelled 10:00 on, the decision candle closing by 14:00; at
most 1 trade per side per index per day (the first signal of that side). Contract = the ORB arm's: strike = ATM of the
09:20 bar's close held all day (half-up to 100 / 50), nearest expiry AFTER the day, entry at the next minute's open (the
option must print within 3 minutes, premium above 40 / 20). Exits = the ORB family (R17 `arms.sim_fixed_pts`: -40 / +40
premium points on BANKNIFTY, -20 / +20 on NIFTY, the profit-lock ladder, out at 15:10), with at most these changes from a
fixed menu that Part B may pick:

- index stop: a 5-min close back through a named index level (the broken OR edge = "back inside", or OM) is sold at the
  next minute's open; it is added to the premium stop or replaces it (Part B says which);
- index target: the 1-minute index high (low for puts) reaches a named extension (X+-0.5 or X+-1): sold at the next
  minute's open; it replaces the +40 / +20 premium target and the ladder;
- a skip filter on W = R / ATR14 (no trade above a threshold fixed in Part B).

Costs (R17's, the hunt studies'): the app's paper fills (+-5 bps, stops -10 bps) plus a half-spread of 1 / 2 / 4 ticks by
the contract's last 5 minutes of volume (`Fills(mode="liq")`, stops x2), the app's charges at today's rates
(`Costs(mode="app")`), the NSE lot in force that day.

**Twins** (holdout, 1,000 each, seed 2022):

- Random-level twin: on each holdout index-day, with probability 1/2 every level of the rule (edges, mid, extensions) is
  mirrored around P10 (Part A3), else kept; the same signals, entries and exits. p = (1 + twins with holdout total net
  >= the rule's) / 1,001.
- Random-time twin: each holdout trade is re-entered at a decision candle drawn uniformly from the rule's own window that
  day (the first candle closing at or after 10:00 .. the last closing by 14:00), with the same side, contract and exits
  (index stop / target levels = the rule's real levels that day). Keeping the side carries the day's direction, so this
  twin is hard to beat (stated). p as above.

**Gates** (a rule PASSES only if all hold):

- G1 design: design total net > 0.
- G2 walk-forward stability: the design sessions (pooled by calendar day) in 4 consecutive equal blocks; net > 0 in at
  least 3 of 4.
- G3 holdout: one-sided t-test that the mean holdout daily net > 0 (every eligible session counts; no-trade days = 0).
- G4 random-level twin p, G5 random-time twin p.
- BH at q = 0.05 over ONE family: R22's G3, G4, G5 for every rule (up to 18) + R20c's 12 (`hunt/r20c/results/holdout.json`)
  + R20 / R20b's 28 (`hunt/r20c/prior_pvalues.json`). G3, G4 and G5 must all survive.
- G6 costs: holdout total net > 0 with the half-spread ticks doubled.
- G7 better than today's ORB: the rule's net beats the baseline's on the same sessions in design AND in the holdout.

Reported for every rule and the baseline, design and holdout: trades, sessions, trades and P&L per day, % green trade
days, worst day, max drawdown of the cumulative daily net, the 4 design blocks; "big winners cut short" = trades whose
option high between entry and 15:10 reached entry + 2 x the target (80 / 40 points) but which were sold below entry +
the target (count, and the money between the exit and that high). The 13 Oct 2026 session is a forward example only (if
its minutes are available to this study; never used to choose anything).

## Tests

pytest (`research/hunt/r22/test_r22.py`): the levels on hand-made days, every event kind on synthetic days (reject,
hold, fail, retest hold / fail, extension reach, mid and inner rejects, 15-minute breaks), the mirror twin, the index
stop / target exits, the baseline matches R20's V0 rule, and the look-ahead guards above.

## App change (not built here)

If a rule passes every gate, the write-up describes the exact rule a PAPER-only change would use; the parent session
decides. If none passes, the write-up says so plainly.
