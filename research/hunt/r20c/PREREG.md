# R20c pre-registration: what happens after a support / resistance break is confirmed by 2 candle closes

Written 13 Oct 2026, BEFORE any number of this study was computed. Part A (the event study protocol and the trading-test
framework: data, costs, splits, twins, gates, BH family) is committed on its own before the first run. Part B (at most 4
trading rules) is added in a second commit AFTER the design-period event study and BEFORE any trading test is run. Both
commit hashes are quoted in the write-up (`research/HUNT_R20C_SR_BREAK.md`). Nothing below may change after its commit.

## Why

Boss, 13 Oct 2026: "Analyse what happens if the support and resistance line is broken, like if 2 candles are above
resistance and 2 candles are below support." R20 (branch `claude/r20-adaptive-range`) and R20b (`claude/r20b-sr-range`)
tested range rules for the ORB arm; both are finished (0 of 7 and 0 of 7 pass; their 28 holdout p-values are in R20b's
`results/combined_bh.json`). I read only their pre-registrations, their combined p-value table and their verdict lines
before writing this. R20c is the event study behind them: when price CONFIRMS a break of a level with consecutive candle
closes beyond it, what happens next?

## Part A1. Data, splits (R17 / R20 / R20b's, unchanged)

- Index and options: the `research-data` release through R17's loader (`research/hunt/r17/data.py`): BANKNIFTY 13 Feb
  2024 - 23 Feb 2026, NIFTY 1 Apr 2024 - 13 Apr 2026 (1-minute index, nearest expiry AFTER the day's options with minute
  OI). FINNIFTY: index minutes only (`finnifty_index.parquet`, 12 Feb 2024 - 24 Feb 2026): event study only, no options so
  no trading test, no expiry calendar so no expiry breakdown. MIDCPNIFTY: not in the release (stated, skipped).
- Holdout (locked, read ONCE for all of R20c, after Part B is committed and the design table is frozen in
  `results/design.json`): BANKNIFTY sessions from 25 Aug 2025, NIFTY and FINNIFTY from 13 Oct 2025 (R16 / R17 / R20 /
  R20b's cut dates). Design = the earlier sessions. The event study (Part A2) uses design sessions only; its holdout
  tables are printed only after the one holdout read, as information.
- ATR14 = the app's `ShadowRules.atr14`: the mean true range of the 14 sessions BEFORE the day (R20b's `atr14`). The
  first 15 sessions of each index are left out of everything.
- VIX = India VIX previous close (R17's `Day.vix_prev`; Yahoo's daily close where the release's minutes are missing).
- Expiry day = the session is the index's nearest expiry (`research/brain/calendar.next_expiry`).

## Part A2. The event study (descriptive, design period only)

### Levels (each strictly point-in-time: known before the first candle of the break starts)

| type | resistance / support | known from |
|---|---|---|
| PDHL | the previous session's high / low (09:15-15:29 minutes) | 09:15 |
| PIV | classic floor pivots of the previous session: R1 = 2P - L / S1 = 2P - H, P = (H + L + C) / 3 | 09:15 |
| LIQ | the edges of ACTIVE `LiquidityRules.zones` (swing: lookback 20, full candle range; pool: 2 contacts 5 bars apart, 10 confirmation bars, max age 300; duplicates of the last 50 dropped), R20b's verbatim copy of h4's port; computed on the SAME timeframe as the candles (5- or 15-minute), history = the sessions of the previous 10 calendar days plus today's completed bars. High zone top = resistance, low zone bottom = support. A zone counts at bar b only if it was known at bar b - 1 or earlier and not taken by a close up to bar b - 1. | its confirming bar |
| OI | the previous trading day's NSE F&O bhavcopy, nearest expiry on or after the session: the strike with the biggest call OI = resistance, put OI = support (R20b's `oi.py`; a missing file = no OI levels that day, no fallback) | 09:15 |
| OR | the opening range as the app's ORB uses it: high / low of the minutes 09:15-10:04 (5-minute bars labelled 09:15 .. 10:00) | 10:05 (5-minute: first candle 10:05; 15-minute: first candle 10:15) |

### The event

Candles: 5- and 15-minute bars built from the 1-minute index (bar k covers minutes 09:15 + tf x k ..). A candle CROSSES a
resistance R when its close is > R and the previous candle's close (the previous session's last close for the first
candle) was <= R; support mirrored (close < S, previous close >= S). An N-close break (N = 1, 2, 3) is confirmed when the
crossing candle and the next N - 1 candles all close beyond the level (strictly). Event time = the close of the N-th
candle; the event price P_e = that close. For LIQ the level of a crossing candle is the farthest active edge it crossed
(the highest resistance below its close / lowest support above it). One event per (index, day, level type, direction,
timeframe, N): the first confirmed one. Events are kept only if the crossing candle starts at or after the level's
"known from" and the event time is at or before 15:15 (so that a 15-minute horizon still fits).

Gap tag: an event whose crossing candle OPENED beyond the level (a gap or a jump through it) is tagged "gap" and reported
as its own row; the main tables include both.

### What is measured after each event (index points; also / ATR14)

- move over the next 15, 30 and 60 minutes and to the 15:29 close: direction x (close then - P_e); a horizon past 15:29
  is NaN (sample size shown per column);
- fake-out: any 1-minute close back through the level within 30 minutes after the event (the share of events);
- MFE / MAE over 60 minutes and to the close: the best / worst direction x (1-minute high or low - P_e);
- the wait cost of N = 2 (and 3) against entering on the 1st close of the same breaking candle: direction x (P_e(N) -
  close of the crossing candle), in points and ATR;
- a no-level baseline for each event: the mean of direction x (forward move from the same clock minute) over all design
  sessions of that index. "Excess" = event move - baseline (it removes time-of-day drift).
- a random-level twin per daily level type (PDHL, PIV, OI): each level mirrored around the 09:15 open with a random side
  (seed 2026), the same event logic; reported beside the real levels.

### Breakdowns (each with its event count; cells with < 30 events flagged "n<30")

Level type x timeframe x N; up vs down; time of the event (09:15-10:30, 10:30-12:00, 12:00-13:30, 13:30-15:15); VIX
previous close (< 13, 13-16, >= 16); expiry day vs not; index (BANKNIFTY, NIFTY, FINNIFTY); gap vs not.

Descriptive statistics only: means, medians, shares, t-statistics of means. No p-value from Part A2 enters the gates.

## Part A3. The trading-test framework (fixed now; Part B only picks the rules)

Each rule in Part B is an option-buying rule: a confirmed break (level type(s), timeframe, N, direction(s), index(es),
time window, optional filter on VIX / expiry / time) opens 1 lot of the call (up) or put (down). Exits come from ONE of
the app's existing families:

- **ORB family** (OrbRules exits): ATM strike (the index close at the event, half-up to the 100 / 50 step), nearest expiry
  AFTER the day, entry at the next minute's open (no trade if the option does not print within 3 minutes or premium <=
  40 / 20), -40 / +40 premium points on BANKNIFTY (-20 / +20 NIFTY) with the profit-lock ladder, out at 15:10 (R17
  `arms.sim_fixed_pts`).
- **Liquidity family** (LiquidityRules exits, simplified): 1-ITM strike, the same contract / entry rules, a 15% premium
  stop (resting, filled at the level or worse on a gap), a failed break (a candle of the rule's timeframe closes back
  through the level: sold at the next minute's open), a time stop (not +5% after 20 minutes: sold at the next minute's
  open), out at 15:10. Simplified: no "next liquidity" target and no "new liquidity" exit (they need the zone map of a
  non-LIQ level).

At most 1 trade per rule per index per day per side; a rule may trade both indices (its daily net = the sum over indices
of that calendar day's trades; each index's own design / holdout cut applies).

Costs (R17's): the app's paper fills (+-5 bps, stops -10 bps) plus a half-spread of 1 / 2 / 4 ticks by the contract's
last 5 minutes of volume (`Fills(mode="liq")`, stops x2), the app's charges at today's rates (`Costs(mode="app")`), the
NSE lot in force that day. Expiry days trade the next expiry (the release has no same-day chain for the ORB family);
stated, not fixed.

### Twins (holdout, 1,000 each, seed 2026)

- **Random-level twin**: every level replaced by one at the SAME distance from a reference price on a random side
  (s = +1 / -1, probability 1/2, one draw per level per day): daily levels (PDHL, PIV, OI) and the OR edges around the
  09:15 open; LIQ zone edges around the close of the bar that confirmed the zone (one draw per zone per day, the zone's
  known bar unchanged, its "taken" bar recomputed for the mirrored edge). The same event logic, entries and exits.
  p = (1 + twins with holdout total net >= the rule's) / 1,001.
- **Random-time twin**: on each holdout day the rule traded n times on an index, the twin trades n times at entry minutes
  drawn uniformly from the rule's own decision window that day (from the level's "known from" to the rule's last entry
  time), with the SAME side, strike rule, contract, fills and exits (the Liquidity family's failed-break level = the
  rule's level that day). Stated: keeping the side carries the day's direction, so this twin is conservative (hard to
  beat). p as above.

### Gates (a rule PASSES only if all hold)

- G1 design: design total net > 0.
- G2 walk-forward stability: the design sessions (per index, pooled by calendar day) split into 4 consecutive equal
  blocks; net > 0 in at least 3 of 4.
- G3 holdout: one-sided t-test that the mean holdout daily net > 0 (every eligible session counts; no-trade days = 0).
- G4 random-level twin p, G5 random-time twin p.
- BH at q = 0.05 over ONE family: R20c's holdout p-values (G3, G4, G5 for each rule: up to 12) together with R20's and
  R20b's 28 (R20b `results/combined_bh.json`, branch `claude/r20b-sr-range` commit 52532ce). G3, G4 and G5 must all survive.
- G6 costs: the holdout total net stays > 0 with the half-spread ticks doubled.

Reported for every rule and period: trades, sessions, trades and P&L per day, % green trade days, worst day, max drawdown
of the cumulative daily net, the 4 design blocks and the holdout, every number with its sample size.

## Tests

pytest (`research/hunt/r20c/test_r20c.py`): the candle builder, the event logic (N = 1 / 2 / 3, gaps, one event a day,
the level's known-from), the measurements on synthetic days, the LIQ one-pass zone map against R20b's recompute on
truncated bars, and a look-ahead guard: for random days and bars, every level known at a bar and every event detected up
to that bar are identical when every minute after it is replaced by noise.

## App change (not built here)

If a rule passes every gate, the write-up describes the exact rule a PAPER-only change would use; the parent session
decides. If none passes, the write-up says so plainly.
