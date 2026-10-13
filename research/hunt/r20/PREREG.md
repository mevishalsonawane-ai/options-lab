# R20 pre-registration: an opening range that adapts to the day (ORB family, wide-range days)

Written 13 Oct 2026, BEFORE any number of this study was computed (Step 1 facts included). This file and the experiment
entries `research/experiments/entries/r20*.yaml` are committed on their own before the first run; the commit hash is
quoted in the write-up (`research/HUNT_R20_RANGE.md`). Nothing below may change after that commit. A change needs a new
entry, a new pre-registration and a fresh holdout.

## Why

13 Oct 2026, BANKNIFTY: the 09:15-10:00 opening range was 630 points wide (54,736-55,366). The index stayed inside it
until 14:35; the ORB family's last entry is 14:00, so ORB, ORB Fresh, ORB Sweep and Range Fade took no trade. Boss: "If
the price always stayed in range, then it will never trade again, we should resolve it." The ORB family is paused on
paper (it lost in every backtest year). Question: does a range that adapts to the day make the ORB worth trading, and how
often do days like today happen?

## Data, costs, splits (R17's, unchanged)

- Source: the `research-data` release through `research/brain/adapters` (R17's loader `research/hunt/r17/data.py`):
  BANKNIFTY 13 Feb 2024 - 23 Feb 2026 (498 sessions), NIFTY 1 Apr 2024 - 13 Apr 2026. If the Dhan lake is published,
  the same code runs on it (`R17_SOURCE=lake`); this study does not wait for it.
- Options: the release holds the nearest expiry AFTER the day, so on expiry days the arm trades the next expiry (the app
  trades today's). Stated, not fixed (R17 did the same).
- Fills: the app's paper fills (+-5 bps, stops -10 bps) plus 1 / 2 / 4 ticks of half-spread by the contract's last
  5 minutes of volume (`Fills(mode="liq")`); charges: the app's model at today's rates (`Costs(mode="app")`); the NSE
  lot in force that day; 1 lot a trade.
- ATR14 = the app's (`ShadowRules.atr14`): the mean true range of the 14 sessions BEFORE the day; needs 15 earlier
  sessions. The first 15 sessions of each index have no ATR14 and are left out of every variant (so all variants are
  scored on the same days).
- Holdout (locked, read ONCE for all of R20, after the design table is frozen in `results/design.json`): BANKNIFTY
  sessions from 25 Aug 2025, NIFTY from 13 Oct 2025 (R16 / R17's cut dates). Design = the earlier sessions. Disclosure:
  R17 already read the holdout for the PLAIN ORB (it lost); none of the variants below has been run on any period.

## The arm under test

The ORB arm as the app trades it on paper (`OrbRules`, R14 cap of 1 entry a side a day): a completed 5-minute bar that
closes above the range high buys the ATM call (strike = the 09:20 bar's close, half-up to 100), below the range low
buys the ATM put; -40 / +40 premium points with the profit-lock ladder; out at 15:10; premium <= 40 refused; after an
exit, decisions resume from the next bar. ORB Fresh takes the same trades with a cap of 1 and is not counted twice.
ORB Sweep and Range Fade keep their own rules and are not changed by this study (except variant D, which uses the
Range Fade rule as the ORB's replacement on wide days).

Range naming follows the app's `OrbParams.orEnd`: "the L-minute range" is the high / low of the 5-minute bars labelled
09:15 up to and including the bar labelled 09:15 + L. So the plain 45-minute range (orEnd 10:00) is the app's (it really
spans 09:15-10:05); the 15-minute range is orEnd 09:30 and the 30-minute range orEnd 09:45. Decisions start on the bar
after orEnd and end on the bar before the last entry (13:55 for a 14:00 last entry, 14:25 for 14:30).

## Variants (7 tested, plus the plain rules as the baseline)

| id | rule |
|---|---|
| V0 (baseline) | orEnd 10:00, decide 10:05-13:55 (the app today) |
| A15 (r20a) | orEnd 09:30, decide 09:35-13:55 |
| A30 (r20b) | orEnd 09:45, decide 09:50-13:55 |
| B0.5, B0.75, B1.0 (r20c, r20d, r20e) | once the 10:00 bar is in: if W45 <= k x ATR14 use the 45-minute range, else the 15-minute range; decide 10:05-13:55 either way (k = 0.5, 0.75, 1.0) |
| C1430 (r20f) | orEnd 10:00, decide 10:05-14:25 (last entry 14:30; the 15:10 square-off is unchanged) |
| D (r20g) | if W45 <= 0.75 x ATR14: the plain ORB; else (a wide day) NO breakout trade: Range Fade (10% edge, at most 2 entries a day, -40 / +40, ladder) on the 15-minute sub-range (09:15-09:30 bars), decide 10:05-13:55 |

W45 = the 45-minute range's high minus low. Only k = 0.75 is used for D (the middle of B's grid), to keep the count at 7.

## Scores (per variant, per period)

Daily net = the sum of that day's trades' net P&L (Rs, 1 lot); a day with no trade counts as 0; every eligible session
counts. Reported: trades, sessions, trade days, P&L per session, % green trade days, worst day, max drawdown of the
cumulative daily net, and design blocks vs holdout. Every number with its sample size.

## Gates (a variant PASSES only if all five hold)

- G1 design: design total net > 0 AND > the baseline's design total net.
- G2 walk-forward stability: the design sessions split into 4 consecutive equal blocks; net > 0 in at least 3 of 4.
- G3 holdout: one-sided t-test that the mean holdout daily net > 0.
- G4 random-entry twin: 1,000 twins on the holdout. On each holdout day the variant traded n times, a twin trades n
  times at decision bars drawn uniformly from the variant's own decision window that day, with a random side, the same
  strike, contract, fills and exits. p = (1 + twins with total net >= the variant's) / 1,001.
- Benjamini-Hochberg over all 14 holdout p-values (7 x G3, 7 x G4) at q = 0.05: G3 and G4 must both survive.
- G5 holdout net > the baseline's holdout net.

Walk-forward selector (reported, not a gate): at the start of design blocks 2, 3, 4 and of the holdout, pick the variant
(V0 included) with the best net on all earlier design sessions and trade it over the next block; its out-of-sample record
is reported.

## NIFTY (replication, not a gate)

The same 8 rules on NIFTY with a 50-point strike step, -20 / +20 premium points (NIFTY's ATR14 is roughly half of
BANKNIFTY's) and a premium floor of 20. Reported beside BANKNIFTY; a BANKNIFTY pass whose NIFTY holdout net is negative
is flagged in the verdict.

## Step 1 facts (descriptive, design sessions first; the holdout facts are added after the one holdout read)

For each index, on the plain 45-minute range:
- share of sessions with NO ORB trade (no 5-minute close beyond the range on 10:05-13:55 bars), and with no trade from
  any of ORB / ORB Sweep (to 14:25) / Range Fade (10:30-13:55) signals;
- broken down by W45 / ATR14 (< 0.5, 0.5-0.75, 0.75-1.0, >= 1.0), by India VIX previous close (< 13, 13-16, >= 16)
  and expiry day vs not;
- of the no-ORB-trade days, how many broke out on a 14:00-15:00 bar, and what that late break was worth: index points
  from the break bar's close to 15:10 in the break's direction, and (BANKNIFTY) the ORB option trade entered on it with
  the usual exits.

## App change (not built here)

If a variant passes, the write-up describes the exact rule a PAPER-only change would use; the parent session decides.
If none passes, the write-up says so plainly.
