# R20b pre-registration: support and resistance levels as the ORB range (Boss's idea, 13 Oct 2026)

Written 13 Oct 2026, BEFORE any number of this study was computed. This file and the experiment entries
`research/experiments/entries/r20sr*.yaml` are committed on their own before the first run; the commit hash is quoted in
the write-up (`research/HUNT_R20B_SR_RANGE.md`). Nothing below may change after that commit. A change needs a new entry,
a new pre-registration and a fresh holdout.

## Why

13 Oct 2026, BANKNIFTY: the 09:15-10:00 opening range was 630 points wide and price stayed inside it until 14:35, so the
ORB family took no trade. Boss: "the application finds support and resistance, we could take help of it to find the
range." R20 (branch `claude/r20-adaptive-range`, pre-registered in commit c445042) tests clock-based fixes. This study
tests the level-based idea: use support / resistance levels as the range, to clip it, to confirm a break, or to trade
bounces inside a wide range.

## Data, costs, splits (R17 / R20's, unchanged)

- Index and options: the `research-data` release through R17's loader (`research/hunt/r17/data.py`): BANKNIFTY 13 Feb
  2024 - 23 Feb 2026, NIFTY 1 Apr 2024 - 13 Apr 2026. The release holds the nearest expiry AFTER the day, so on expiry
  days the arm trades the next expiry (the app trades today's). Stated, not fixed (R17, R20 did the same).
- Fills: the app's paper fills (+-5 bps, stops -10 bps) plus 1 / 2 / 4 ticks of half-spread by the contract's last
  5 minutes of volume (`Fills(mode="liq")`); charges: the app's model at today's rates (`Costs(mode="app")`); the NSE lot
  in force that day; 1 lot a trade.
- ATR14 = the app's `ShadowRules.atr14`: the mean true range of the 14 sessions BEFORE the day (needs 15 earlier
  sessions). The first 15 sessions of each index are left out of every variant, so all are scored on the same days.
- Holdout (locked, read ONCE for all of R20b, after the design table is frozen in `results/design.json`): BANKNIFTY
  sessions from 25 Aug 2025, NIFTY from 13 Oct 2025 (R16 / R17 / R20's cut dates). Design = the earlier sessions.
  Disclosure: R17 already read the holdout for the PLAIN ORB (it lost); no level variant below has been run on any period.
  I have not looked at R20's results (none were committed when this was written).

## The arm (baseline V0, the same as R20's V0)

`OrbRules` as the app trades it on paper, R14 cap of 1 entry a side a day: the opening range is the high / low of the
5-minute bars labelled 09:15 .. 10:00 (it spans 09:15-10:05). A completed 5-minute bar (labelled 10:05 .. 13:55) that
closes above the range high buys the ATM call, below the range low the ATM put (strike = the 09:20 bar's close, half-up
to 100); entry at the next minute's open (no trade if the option does not print within 3 minutes, or premium <= 40);
-40 / +40 premium points with the profit-lock ladder; out at 15:10; after an exit, decisions resume from the bar after the
exit's bar. All variants keep these entries, exits, strikes, costs and decision bars; only the trigger levels change.

## The levels (each strictly point-in-time)

"Known at bar k" = known when the 5-minute bar k has closed (the decision moment). P0 = the close of the bar labelled
10:00 (known at 10:05, when the opening range is complete).

1. **PIV** (known at 09:15): the previous session's high, low and close (PDH, PDL, PDC) and the classic floor pivots
   P = (H + L + C) / 3, R1 = 2P - L, S1 = 2P - H, from the previous session in the release (its 09:15-15:29 minutes).
2. **LIQ** (as they form): the edges of the ACTIVE liquidity zones of `LiquidityRules.zones` (swing: pivot lookback 20,
   full candle range; pool: 2 contacts 5 bars apart, 10 confirmation bars, max age 300; duplicates of the last 50 dropped),
   ported in `research/hunt/h4/comps.py` (exact port, reused). Chart: 15-minute bars (BANKNIFTY's 15m book), history =
   the sessions of the previous 10 calendar days plus today's 15-minute bars COMPLETED by the decision moment (a 15-minute
   bar labelled t is complete at t + 15). Active at bar i = known <= i and not taken by a close up to i. A zone's level is
   its edge (the top of a high zone, the bottom of a low zone). Zones are recomputed on the truncated bars at each
   decision, so a later bar can never change an earlier answer.
3. **OI** (known at 09:15): the previous trading day's NSE F&O bhavcopy (public end-of-day file, `oi.py`): in the nearest
   expiry on or after the session, the strike with the biggest put OI (support) and the biggest call OI (resistance).
   A missing file = no OI levels that day.
4. **OIi** (intraday OI, separate variant): the release's minute open interest (Dhan, the nearest expiry AFTER the day,
   strikes within 1,000 points of the 09:20 index for BANKNIFTY / about +-10 strikes): at P0's minute, the strike with the
   biggest put OI (support) and call OI (resistance), using each contract's last OI printed at or before 10:04. Limits,
   stated: next-week / next-month expiry on expiry days, and a wall beyond the +-10 window shows as the window's edge.
   This is the release, not the Dhan lake (the lake is not published).

ALL = PIV + LIQ + OI (not OIi). A "level near the price" rule: in (a) and (b) levels within 0.1 x ATR14 of P0 are ignored
(a level at the price is no range).

## Variants (7 tested, plus V0 as the baseline)

| id | rule |
|---|---|
| V0 (baseline) | the plain opening range (above) |
| A-ALL (`r20sr-a-all`) | (a) the range is [S, R]: R = the nearest ALL level above P0 + 0.1 x ATR14, S = the nearest ALL level below P0 - 0.1 x ATR14, both frozen at 10:05; a side with no level uses the opening-range edge. Break = a 5-minute close above R (call) / below S (put). |
| A-PIV (`r20sr-a-piv`) | (a) with PIV levels only (the morning brief's levels) |
| A-OI (`r20sr-a-oi`) | (a) with OI levels only (previous day's bhavcopy) |
| A-OIi (`r20sr-a-oii`) | (a) with OIi levels only (intraday OI at 10:04) |
| B (`r20sr-b`) | (b) wide day = W45 > 0.75 x ATR14 (W45 = the opening range's high minus low). On a wide day the range is clipped: high' = the nearest ALL level above P0 + 0.1 x ATR14 that is below the range high (else the range high); low' = the nearest ALL level below P0 - 0.1 x ATR14 above the range low (else the range low). Other days: the plain range. |
| C (`r20sr-c`) | (c) the plain range; a break is taken only if the bar's close also clears the nearest ALL level (known at that bar) lying between the range edge and 0.5 x ATR14 beyond it (a call needs close > that level; a put close < it). No such level = the break is confirmed. A refused break does not use up the side's entry (a later confirmed break on that side may trade). |
| D (`r20sr-d`) | (d) not a wide day: the plain ORB. Wide day (W45 > 0.75 x ATR14): no breakout trade; bounce trades at the levels inside the range: the levels = ALL levels known at the bar with range low <= level <= range high, plus the two range edges. A level above the previous bar's close is resistance, below is support. Bar touches a support (low <= s + 0.05 x ATR14) and closes above it -> buy the call; touches a resistance (high >= r - 0.05 x ATR14) and closes below it -> buy the put; both on one bar -> no trade. At most 2 entries a day (Range Fade's cap), any side. |

k = 0.75 for B and D (the middle of R20's grid, the same as R20's D). No other setting is tuned: 0.1, 0.5 and 0.05 x ATR14
are fixed here and never changed.

## Scores (per variant, per period)

Daily net = the sum of that day's trades' net P&L (Rs, 1 lot); a day with no trade counts as 0; every eligible session
counts. Reported for each variant and period: trades, sessions, trade days, P&L per session, % green trade days, worst
day, max drawdown of the cumulative daily net, the 4 design blocks vs the holdout, and against V0: how many V0 no-trade
days the variant turns into trade days and what those trades made (and the reverse). Every number with its sample size.

## Gates (a variant PASSES only if all five hold)

- G1 design: design total net > 0 AND > V0's design total net.
- G2 walk-forward stability: the design sessions split into 4 consecutive equal blocks; net > 0 in at least 3 of 4.
- G3 holdout: one-sided t-test that the mean holdout daily net > 0.
- G4 random-level twin (holdout): 1,000 twins. In a twin, every level is mirrored around P0 with a random sign:
  level' = P0 + s x (level - P0), s = +1 or -1 with probability 1/2, drawn once per level per day (a liquidity zone keeps
  its sign for the whole day; its known / taken bars are unchanged). So the twin's levels are at the SAME distances from
  the price but not where the market put them. The opening-range edges (D) are not levels and are not mirrored.
  p = (1 + twins with holdout total net >= the variant's) / 1,001.
- Benjamini-Hochberg at q = 0.05 over ONE family: R20b's 14 holdout p-values (7 x G3, 7 x G4) together with R20's 14
  (7 x G3, 7 x G4), 28 in all. G3 and G4 must both survive. If R20's p-values are not published when this study ends,
  R20b reports (i) BH over its own 14 and (ii) the conservative combined BH with R20's 14 set to 1 (BH can only reject
  more when other p-values are smaller, so a pass there is a pass in the real family); the real combined step must be
  re-run once R20's numbers exist.
- G5 holdout total net > V0's holdout total net.

Walk-forward selector (reported, not a gate): at the start of design blocks 2-4 and of the holdout, pick the variant
(V0 included) with the best net on all earlier design sessions and trade it over the next block.

## NIFTY (replication, not a gate)

The same 8 rules on NIFTY with a 50-point strike step, -20 / +20 premium points and a premium floor of 20 (R20's NIFTY
settings); levels from NIFTY's own data and bhavcopy rows. Reported beside BANKNIFTY; a BANKNIFTY pass whose NIFTY
holdout net is negative is flagged in the verdict.

## Descriptive (no test)

Design first, holdout after the one read: how many levels each source gives per day, the median distance of the nearest
level above / below P0 in ATR14 units, the share of wide days (W45 > 0.75 x ATR14), and on 13-Oct-like days (wide, V0 no
trade) what each variant did.

## Tests

pytest (`research/hunt/r20b/test_r20b.py`): the level builders on synthetic data, and a look-ahead guard: for random
days and bars, every level set and every signal computed with the full day equals the one computed with the bars after
the decision moment replaced by noise.

## App change (not built here)

If a variant passes, the write-up describes the exact rule a PAPER-only change would use; the parent session decides. If
none passes, the write-up says so plainly.
