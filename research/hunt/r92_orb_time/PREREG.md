# R92 pre-registration: is there a TIME when the ORB family makes money?

Written 21 Oct 2026, BEFORE any trade outcome of R92 was computed. Only the lake's file list, the session / expiry / lot
counts (`r92data.py summary`, no prices of any trade) and the earlier reports were read. This file is committed and
pushed on its own before any P&L. After that commit nothing here changes; a change needs a dated note in `NOTES.md`
written before the numbers it affects.

Boss (21 Oct 2026): "Find if there is any specific time where the ORB family makes money." Research only: R92 changes
no app code. The four arms (ORB, ORB Fresh, ORB Sweep, Range Fade) stay paused (R3, R49) unless a cell passes every gate
below, and even then only a PAPER time filter is proposed, behind a switch, never live by itself.

## A1. Prior work this builds on (not redone)

- R3 (`HUNT_R3_ORB_FAMILY.md`): the exact app rules, rebuilt line by line; every arm loses in every year on all five
  indices. Its anatomy "signal hour" row (BANKNIFTY + NIFTY, Aug 2021 - 12 Oct 2026, app ORB capped 1 a side): Rs per
  trade -121 (10 h) / -84 (11 h) / **+28 (12 h, 311 trades)** / -66 (13 h); Fresh and Sweep negative every hour.
- R49 (`HUNT_R49_ORB_RECENT.md`): Nov 2024 onwards, 5 indices, range 15 / 30 / 50 minutes; 0 of 336 pass.
- R14: 1 entry a side a day is right. R20: a later last entry (14:30) loses. `ENTRY_CUTOFF.md`: later cut-offs lose more.
- R22 / R23 / R56: other ranges and Boss's manual NIFTY plan; all lose.
- FINDINGS.md: ORB -1.94 lakh on 1,109 trades; the family lost in both years.

R92 does not search new entry rules. It slices the SAME app rules by time (and a few day types) and asks whether any
slice makes money after costs, honestly.

## A2. Data, window, split and the holdout

- Lake: Drive `gdrive:IraAlgo-data-lake`, fetched 21 Oct 2026: `options_rolling_1m/opt_<UND>_WEEK_<YEAR>` (real
  1-minute option prints, ATM-10..ATM+10 of the nearest expiry on or after the day: the ORB family's own contract rule,
  `OrbRules.expiryOnOrAfter`) and `index_1m/index_1m_<UND|INDIAVIX>_<YEAR>`, to 19 Oct 2026.
- Records: R3's session builder (`r3data.build`) with R49's two fixes (lot read from the day's traded volumes,
  calendar fallback; the extra 15:15 expiry rule), `r92data.py`.
- Indices: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX. Sessions: see A8 (counts only).
- **DESIGN**: 1 Aug 2021 - 31 Mar 2026. Walk-forward blocks: calendar years (2021 is Aug-Dec, 2026 is Jan-Mar).
- **LOCKED HOLDOUT**: 1 Apr - 19 Oct 2026, all five indices, read ONCE by `r92study.py holdout`, which writes
  `results/HOLDOUT_READ` and refuses a second read. Until then `r92data.sessions()` serves design sessions only.
- **How fresh is the holdout? Not pristine, stated before the read.**
  - R49 read 1 Apr - 13 Oct 2026 for these arms, but only whole-day results, never split by entry time or day type.
  - R3's hour row used BANKNIFTY + NIFTY through 12 Oct 2026, so it already contains BANKNIFTY / NIFTY Apr-Oct 2026
    trades by hour.
  - Only **13 - 19 Oct 2026** (5 sessions per index) was seen by no ORB-family study. It is reported on its own; it is
    too small to gate anything.
- **R3's 12:00-12:59 lead (H12) is a hypothesis that came from the data.** It is tested on data R3's hour row did not
  use: FINNIFTY + MIDCPNIFTY + SENSEX (R3 read their ORB trades as one total, never by hour; R49 did not split by hour).
  Labelled "post hoc from R3" everywhere.

## A3. The arms (signal source: the INDEX itself, the CLAUDE.md default)

Every signal is read from the 1-minute index spot (5-minute bars built from it). The option is only the instrument:
the ATM strike of the 09:20 bar's close (half-up), the nearest expiry, one held contract from entry to exit. Stops,
targets and the profit-lock ladder are on the option premium. India VIX is not used. No rule reads an option's own
chart.

R3's line-by-line rebuild (`r3engine`) of `OrbRules` / `SweepRules` / `RangeFadeRules` / `ProfitLock` / `OrbArms`:
- opening range = 5-minute bars 09:15 .. 10:00 (rows 09:15 .. 10:04), only when the 10:00 bar exists;
- ORB: a 5-minute close beyond the range; ORB Fresh: the same, but the previous bar was not beyond; ORB Sweep: a bar
  that trades beyond an edge and closes back inside (buy the other way); Range Fade: a bar that reaches within 10% of
  the width of an edge and closes back inside;
- app decision windows (bars by start time): ORB / Fresh 10:05 - 13:50, Sweep 10:05 - 14:25, Fade 10:30 - 13:50; in
  ENTRY-MINUTE terms (the signal bar's close = the minute whose open fills the order): ORB / Fresh 10:10 - 13:55, Sweep
  10:10 - 14:30, Fade 10:35 - 13:55. **Every time in R92 is the entry minute** (R3's hour row used the same clock);
- entry 1 lot at the next minute's open (a print within 3 minutes, else no trade); refused at or below P;
- stop -P (resting, tick-floored, filled at min(level, open)); target +P (Sweep +2P); the ladder (25% -> breakeven after
  charges, 50% -> +25%, 75% -> +50%); P = BANKNIFTY 40 (the app's), NIFTY 20, FINNIFTY 20, MIDCPNIFTY 10, SENSEX 60;
- out at 15:10, 15:05 on an expiry day; cooldown to the bar after the exit; one position per arm per index;
- **1 entry a side a day for every arm** (R14, as Boss asked), and at most 2 a day (the Sweep / Fade app cap).
- Costs: the charges in force on each date (`obuy/costs.py Costs("dated")`, BSE rates for SENSEX), R17's "liq" fills
  (the app's slippage model in research: +-5 bps plus 1-4 ticks of half-spread by traded lots; stops 10 bps and double
  ticks); 1 lot of the day's contract (R49's lot rule).

ORB Fresh equals ORB whenever the whole app window is used with the 1-a-side cap (the first break of a side is always
fresh; R3, R18, R19c). So Fresh is tested only where a time slot makes it differ (family T, mode ANY).

## A4. The cells (all pre-registered; m = 257)

"Slot" = a range of ENTRY minutes. "EXT" = a slot outside the arm's app window: ORB / Fresh / Fade entries 14:00-14:30
(the app stops at 13:55); these use the window extended to 14:30 (Sweep's) and are labelled EXT.

**Family T: entry-time slots (166 cells).**
- Hours: 10 (10:10-10:59; Fade 10:35-10:59), 11, 12, 13 (13:00-13:59), 14 (14:00-14:30).
- 30-minute slots: 10:00-10:29, 10:30-10:59, 11:00-11:29, 11:30-11:59, 12:00-12:29, 12:30-12:59, 13:00-13:29,
  13:30-13:59, 14:00-14:30.
- 15-minute slots: 10:00-10:14, 10:15-10:29, 10:30-10:44, ..., 13:45-13:59, 14:00-14:14, 14:15-14:30 (18).
- Fade has no entries before 10:35: its 10:00-10:29, 10:00-10:14 and 10:15-10:29 slots do not exist.
- Two modes:
  - **ANY** ("any signal", the time filter an app switch would apply): the arm enters only on a signal whose entry
    minute is in the slot; a signal outside the slot is ignored and uses none of the cap.
  - **FIRST** ("first signal only"): the arm runs in its window (extended to 14:30); only the day's FIRST taken trade is
    kept, and only if it falls in the slot. Hours and 30-minute slots only.
- Counts: ORB ANY 32 + FIRST 14; Fresh ANY 32; Sweep ANY 32 + FIRST 14; Fade ANY 29 + FIRST 13 = 166.

**Family R: opening-range length (15 cells).** M = 15, 30, 50 (the app's), 60, 75 minutes (range ends 09:29, 09:44,
10:04, 10:14, 10:29). Window: the first bar after the range (Fade 5 bars later, as the app's 10:30), the app's last bar
(R49's `window`). Arms ORB, Sweep, Fade.

**Family D: day type (36 cells).** Arms ORB, Sweep, Fade, app rules, on these days only:
- weekday Mon / Tue / Wed / Thu / Fri (5);
- trading days to the contract's expiry 0 (expiry day) / 1 / 2 / 3+ (4), counted on the index's own session calendar
  (an exchange calendar fact known in advance);
- the first 3 / middle / last 3 sessions of the calendar month (3), on the session calendar (holidays are published in
  advance).

**Family X: exit time, a secondary check (9 cells).** Square-off at 13:30, 14:30, 15:10 (the app's), the earlier of it
and 15:05 on an expiry day. The last entry is 15 minutes before the exit (13:15 / 14:15; 15:10 keeps the app's window).
Arms ORB, Sweep, Fade.

**Family C: declared combinations (30 cells).** Hour (10, 11, 12, 13, 14; ANY mode) x expiry day / not, for ORB,
Sweep, Fade.

**H12: R3's 12:00-12:59 ORB lead, "post hoc from R3" (1 cell).** R3's exact definition: the app ORB (1 a side, the app
window) trades whose entry minute is 12:00-12:59, on FINNIFTY + MIDCPNIFTY + SENSEX, design period. Its holdout read is
the same on FINNIFTY + MIDCPNIFTY + SENSEX for Apr - 19 Oct 2026, plus all five indices on 13 - 19 Oct 2026.

Every cell is reported pooled (the five indices) and per index; the gates are on the pooled cell (H12: its three
indices pooled).

## A5. What is measured per cell

Trades; win rate; net after costs; expectancy (net per trade); average win / loss; net per index and per year; the
worst calendar month (pooled and per index); the top-5 share; the exits; and a **points-only view on the underlying**:
side x (index open at the exit minute - index open at the entry minute), no costs, per trade.

## A6. Random-entry twins ("other minutes of the same window")

500 twins per cell. Each real trade is re-entered at a random decision bar of the arm's window (EXT cells: the window
to 14:30; R: that range's window; X: that exit's window), **outside the cell's own slot** for families T and C and H12
(other minutes), and anywhere but the real trade's own bar for R, D, X. Same session, side, strike rule, stop, target,
ladder and square-off. A draw whose premium is refused is re-drawn (at most 5 times). Twin p = (1 + #twins with net
>= the real net) / 501.

## A7. Statistics and gates

- **Money p**: one-sided t-test that the mean net per traded (index, session) is above 0 (trades of one session summed,
  so same-day trades are not counted as independent).
- **BH** at q 0.10 over all m = 257 cells (money p, design). The twin p's BH q over the same 257 is reported too.
- **Design gates** (pooled, all must hold):
  - G1 net > 0 with at least 100 trades;
  - G2 money q <= 0.10;
  - G3 twin p < 0.05;
  - G4 walk-forward: net > 0 in at least 3 of the 4 full years 2022-2025, and on at least 3 of the 5 indices;
  - G5 net > 0 without its 5 best trades;
  - G6 the worst calendar month on each index alone >= -Rs 13,000 (the account's monthly stop).
- **Nested walk-forward (reported):** for each test year Y = 2023, 2024, 2025, 2026 (Jan-Mar), the cell with the best
  net over the years before Y (all 257 cells, design) is traded in Y; the chain's out-of-sample total is reported.
- **Holdout gates** (read once; for a design passer): H1 net > 0; H2 at least 30 trades; H3 twin p < 0.10; H4 the
  worst calendar month on each index alone >= -Rs 13,000.
- A cell **PASSES** only with G1-G6 and H1-H4. H12 passes with net > 0 and money p < 0.05 on its design data, the BH
  q <= 0.10 in the 257, and holdout net > 0.
- If a cell passes, R92 writes an APP_SPEC for a PAPER-only time filter on the existing ORB arm(s) (off by default,
  logged in the Autopilot change timeline, undoable in one tap, never live by itself). If none passes, R92 says so and
  names the least-bad times (the best design expectancy with its holdout sign), as information only.

## A8. Sessions (counts only, `r92data.py summary`, 21 Oct 2026)

| index | sessions | first | design | holdout | of it 13-19 Oct | expiry days | lot from the data | lots seen |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1,289 | 2021-08-02 | 1,152 | 137 | 5 | 274 | 1,289 | 50, 25, 75, 65 |
| BANKNIFTY | 1,260 | 2021-09-03 | 1,123 | 137 | 5 | 191 | 1,260 | 25, 15, 30, 35, 30 |
| FINNIFTY | 1,259 | 2021-09-03 | 1,122 | 137 | 5 | 191 | 1,189 | 40, 25, 65, 60 |
| MIDCPNIFTY | 911 | 2022-02-21 | 774 | 137 | 5 | 106 | 839 | 75, 50, 120, 140, 120 |
| SENSEX | 850 | 2023-05-15 | 713 | 137 | 5 | 181 | 846 | 10, 20 |

Design 4,884 index-sessions, holdout 685 (25 of them 13-19 Oct 2026). The 15 sessions before each index's first
session only feed ATR14 (unused by R92's cells) and the previous close.

## A9. Code and the look-ahead guard

`research/hunt/r92_orb_time/`: `r92cfg.py`, `r92data.py`, `r92engine.py` (cells, the outcome tables, the arm runner,
twins), `r92study.py` (design / holdout / report), `r92entries.py`, `test_r92.py`. Tests use synthetic sessions (no
lake): slots, windows, modes, the cap and cooldown, day types, exit times, the twins' exclusion, BH, the holdout lock,
parity with R3 / R49's engine on the app rules, and the look-ahead guard: rows after each decision are replaced by noise
and the signals, slot membership, day types and entry prices must not change.
