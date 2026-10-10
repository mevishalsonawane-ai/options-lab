# h35 pre-registration (written 2026-10-08 BEFORE any h35 P&L was computed)

Question (Boss): "the support and resistance we calculate in the app - are we using it or not?" Part 2 tests, for
OPTION BUYING only, every app level that earlier research did not test exactly as the app computes it.

## Levels tested (faithful ports; Kotlin lines in levels.py)

| id | level | app source |
|---|---|---|
| pivD | classic daily pivots P, R1, S1, R2, S2 from the previous session's H/L/C | ira-core Reason.kt:226-260 (Pivots.of / say) |
| pivW | weekly pivots from the last 5 complete sessions' H/L/last close | Reason.kt:822-826 (Outlook.say, Span.WEEK) |
| brain | Jarvis Brain levels: yesterday's high, low, close; today's 15-min opening range high/low (after 09:30); swing highs/lows of the last 5 days' CLOSED 15-min candles, k = 3 each side | ira-core Brain.kt:76-97, Candles.kt:108-115 |
| struct | Structure.kt swing highs/lows on today's CLOSED 5-min candles, k = 2 each side | Structure.kt:22-23, 142, 173 |
| maxoi | ChainRead "biggest call OI (read as resistance)" and "biggest put OI (read as support)" strikes, nearest expiry | ChainRead.kt:22-24 (ChainIntel.kt:191, ChainDrift.kt:83) |
| maxpain | max pain strike of the nearest-expiry chain | engine ChainAnalytics.kt:141-158 |
| round | round numbers every 500 (NIFTY, FINNIFTY, MIDCPNIFTY: the `else` branch) or 1,000 (BANKNIFTY, SENSEX) | RoundCloses.kt:103 |
| vix | the "usual-day range": previous close +/- close x VIX/100/sqrt(252) (VIX = India VIX previous close) | Reason.kt:107-110, 857 (Outlook.brief) |

Data limits, declared: the chain data holds ATM+/-10 strikes only, so max OI and max pain are over those strikes (the
app reads the broker's chain window). Max OI / max pain are re-read at every 5-minute decision from the OI of the last
minute before the decision bar's close. Jarvis has no MIDCPNIFTY; its formulas are applied to it literally anyway.

## Entries (decisions on CLOSED 5-minute index bars, 09:15-anchored; signal bars closing 09:30..14:30)

Per level L known at the decision bar's close (dynamic levels re-read each bar):
- **brk** break-and-hold: bar k closes beyond L with bar k-1's close on the other side (or at L); bar k+1 also closes
  beyond L -> signal at bar k+1's close in the break direction (up = buy CE, down = buy PE).
- **rt** retest-then-go: after a fresh break at bar k, within bars k+1..k+12 (60 min), the first bar whose low (up
  break) comes to within tol of L (low <= L + tol) and closes beyond L -> signal there; a close back through L first
  cancels it. tol = 0.03% of L.
- **bo** bounce / rejection: bar k-1 closes below L, bar k's high reaches L - tol or above and bar k closes below L ->
  buy PE (rejection from below). Mirror from above -> buy CE. tol = 0.03% of L.
One signal per bar per family/mode (the level nearest the close). Option: 1 ITM, nearest expiry, 1 lot (lot in force
that day), filled at the NEXT minute's open with the app's fills (+5 bps buy, -5 / -10 bps sell / stop) and app costs.
Expiry days skipped (as the Liquidity arm). One position at a time per (index, family, mode) book, max 3 a day.
Index stop where used: L -/+ the arm's index-stop size (BN 30, FIN 15, NIFTY 15, SENSEX 50, MIDCP 8).

## Exits (17, fixed now)
- E0..E11: premium target +15/+20/+25/+30 points x premium stop -10/-15/-20 points (resting, checked on the option's
  1-minute high/low, stop first on a same-minute tie), 15:10 square-off. No index stop.
- E12: the Liquidity arm's exits (obuy LQ.ARM_EXITS: -15% premium stop, out at 20 min unless +5%, 15:10) + the index
  stop at L -/+ index-stop size.
- E13: the app's ORB profit-lock ladder: -40 / +40 points, ladder (0.25->0, 0.5->+0.25R, 0.75->+0.5R) on R = 40.
- E14..E16: pure time stops 15 / 30 / 60 minutes (no premium stop), 15:10.

Variants: 8 families x 3 modes x 17 exits = **408**, all counted.

## Costs reported
Gross (no charges, no slippage), net app (app fills + app costs), and net REAL = net app minus the real half-spread
from research/HUNT_H24.md on BOTH legs (BN 0.16%, MIDCP 0.21%, NIFTY 0.16%, FIN 0.42%, SENSEX 0.20% of premium), and
STRESS = 1.5x those half-spreads.

## Choice window / survival rule (data < 2025-10-01 only)
A variant survives only if ALL hold:
- S1 net REAL > 0 over the choice window;
- S2 same-exit random-entry baseline (obuy, 5 alternatives per signal, B = 2000) BH q < 0.10 across all 408;
- S3 anchored yearly walk-forward of its FAMILY (pick the best net-REAL variant on earlier years, 2 training years)
  is positive in total and in more than half of the test years;
- S4 Hansen SPA p < 0.10 over all 408 daily net-REAL series.
Survivors (at most the 3 best by net REAL) go to the locked holdout (2025-10-01 .. latest) ONCE. If none survives,
the single best variant by choice-window net REAL is run on the holdout once, for information only.

## Filters on the app's Liquidity 15+5 trades (6 books: BN15, BN5, FIN30, FIN5, MID15, MID5; h4 port, app fills)
For each family, skip a Liquidity entry when a level of that family lies AHEAD of the signal close in the trade's
direction within D, with D = 1 index-stop unit (BN 30 / FIN 15 / MID 8: the arm's own room rule) or D = 0.2% (Jarvis
PatternExpert ROOM_PCT, PatternExpert.kt:24). 8 x 2 = **16 filter variants**.
Keep rule (choice window): Rs/day net REAL of the filtered book > unfiltered in BOTH halves (Oct 2021-2023, 2024-Sep
2025) AND the skipped trades' mean is below a random skip of the same count (B = 2000; one-sided p), BH q < 0.10
across the 16. Survivors run once on the holdout.

Reported for anything that reaches the holdout: Rs/day at 1 lot (all trading days), lots for Rs 5,000/day, max
drawdown, per year, worst day / month.
