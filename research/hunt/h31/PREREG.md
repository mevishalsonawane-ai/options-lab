# h31 pre-registration: overnight option BUYING from 15:20 (written 2026-10-08, BEFORE any option P&L was computed)

Question: index gains arrive overnight (h28: intraday drift negative, gap positive). Can buying a 1-ITM CE (or PE) near
the close and selling at / after the next open beat option costs and overnight theta, using only what is known at
15:20 IST? Option BUYING only, fixed 1 lot, Rs 1,00,000. Indices: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX.

## Periods
- PRE (all choosing): option-data start .. 2025-09-30. HOLDOUT: 2025-10-01 .. latest, read ONCE at the end.

## Timing (no look-ahead)
- Decision on the close of the 15:19 bar (known at 15:20:00). Entry = option OPEN of the 15:20 bar (first bar within
  3 minutes), app fill (+5 bps).
- Next session (the next day in the index data): exits at the option OPEN of 09:16, 09:30 or 10:15 (first bar within
  5 minutes after), app fill (-5 bps). Gap rule GX: on the 09:15 index bar close vs the entry-day 15:29 close,
  if the move is AGAINST the side -> sell 09:16 open, else sell 10:15 open.
- No stops (a pure overnight hold; the gap is the trade).

## Contracts (strike: 1-ITM from the 15:19 index close, engine StrikeRule(1))
- M: nearest MONTHLY whose expiry is AFTER the exit session (never held into its expiry day; the next monthly is not
  in the data, so the last 1-2 sessions before each monthly expiry have no M trade).
- W: nearest WEEKLY whose expiry is after the exit session (Dhan has only the nearest weekly; the "next-week weekly"
  is the same contract once the current one has >= 2 sessions left). Only where the weekly series exists
  (NIFTY all; BANKNIFTY/FINNIFTY/MIDCP to Nov 2024; SENSEX from 2023).
- Expiry-day entries are never taken (the contract would be today's expiry).

## Conditions known at 15:20 (features)
loc = (c1519 - day low) / (day high - day low) up to 15:19; LH = c1519 / c1419 - 1; DAY = c1519 / prev close - 1;
BR = share of the 214 F&O stocks above their previous daily close (at 15:19 from minute data, Oct 2024+; BEFORE
Oct 2024 only daily candles exist, so the 15:30 close is used: a 10-minute look-ahead, flagged; the rule is also
reported on the minute-data period alone); VIXCH = India VIX at 15:19 / prev daily close - 1 (minute VIX from
Oct 2021; NaN before -> no trade); VIXLOW = VIX at 15:19 below the median of the previous 60 daily closes;
ES = ES=F (Yahoo 1h, h27's raw file, May 2024+) close of the 08:00 UTC bar / open of the 04:00 UTC bar - 1
(09:30 -> 14:30 IST, known by 15:20); FII = change in FII index-futures net long from NSE participant OI of the
PREVIOUS session (published that evening; h27's raw files), z-scored on its trailing 60 values. Same-day FII cash
flow is published ~18:00-19:00 IST, i.e. NOT known at 15:20, and no bulk history is free (h27), so it is not used.
Events (h28's events.csv): an FOMC decision or US CPI release on US date e falls in the night after the last Indian
session <= e.

## Side rules (17)
R01 CE always (unconditional call).  R02 PE always.
R03 LOC follow: CE if loc >= 0.75, PE if loc <= 0.25.  R04 LOC CE only (loc >= 0.75; BTST strong close).
R05 LOC fade: PE if loc >= 0.75, CE if loc <= 0.25.
R06 LH follow (|LH| >= 0.10%).  R07 LH fade.
R08 DAY follow (|DAY| >= 0.30%).  R09 DAY fade.
R10 BREADTH follow: CE if BR >= 0.60, PE if BR <= 0.40.
R11 VIX change: CE if VIXCH <= -3%, PE if VIXCH >= +3%.  R12 VIXLOW CE: CE when VIXLOW.
R13 ES follow (|ES| >= 0.20%; May 2024+ only).  R14 FII follow (|z| >= 0.5).
R15 EVENT CE: CE on FOMC or US-CPI nights.  R16 FOMC CE: CE on FOMC nights.
R17 ALL-AGREE: CE if loc >= 0.6 and DAY >= 0 and BR >= 0.5; PE if loc <= 0.4 and DAY <= 0 and BR <= 0.5.

## Filters (3): F0 all nights; F1 one-night holds only (skip weekends / holidays: theta); F2 = F1 and the contract
has >= 7 calendar days to expiry after the exit day.
## Exits (4): X0916, X0930, X1015, GX.
Variants: 17 rules x 3 filters x 2 contracts x 4 exits x 5 indices = 2,040 (all counted, even empty ones).

## Costs
GROSS = raw option prices (no slippage, no charges). NET = app fills (+-5 bps) + app charges (Costs('app'), STT
0.15%) + the real half-spread on entry and exit (h24): NIFTY 0.16%, BANKNIFTY 0.16%, FINNIFTY 0.42%, MIDCPNIFTY
0.21%, SENSEX 0.20% (assumed). STRESS = 1.5x half-spreads (the 09:16 book is usually wider than h24's 11:03 snapshot).

## Tests (PRE only)
- Per variant: trades, net, Rs/trade, Rs/day (over all PRE sessions of that index), hit rate, max drawdown, worst
  night, per-year net.
- Coin-flip direction baseline: same nights, same contract and exit, side by a fair coin (both sides are priced on
  every night, so this is exact). p from the normal approximation of the coin-flip sum (checked by 2,000 draws for
  the top variants). BH across all 2,040.
- Profit test: one-sided t-test of mean net per trade > 0. BH across all 2,040.
- Hansen SPA / White RC over the (PRE sessions x variants) daily net matrix vs not trading (obuy.overfit.spa).
- Walk-forward by year: for test years 2022, 2023, 2024, 2025 (Jan-Sep) trade the variant with the best net over
  all earlier PRE years (>= 20 trades); report each year and the total.
## Promotion (all must hold): PRE net > 0 at 1.5x spread; coin-flip BH q < 0.05; t-test BH q < 0.05; positive
net in >= 60% of PRE calendar years with >= 10 trades; SPA p < 0.10. At most the 3 best promoted variants (by PRE net)
go to the holdout ONCE. If none is promoted, the single best PRE variant and the walk-forward 2025 pick are run on the
holdout for information only.
## Also reported (descriptive, not variants): index overnight return (15:19 close -> next 09:15 open / 10:15) by
condition; the overnight "cost of carry" of 1-ITM CE+PE (theta + spread); worst night; lots for Rs 5,000/day.

## Amendment 1 (2026-10-08, coordinator / Boss clarification, BEFORE any option P&L was computed)
"Points" = absolute PREMIUM points (Rs of option price). Four point exits are added on the next session, alongside
the 4 time exits (so 8 exits; variants 17 x 3 x 2 x 8 x 5 = 4,080, all counted in BH / SPA / walk-forward):
- P15, P20, P25, P30: target = entry fill + N points, stop = entry fill - N points, placed before the open
  (nothing rests overnight). If the 09:15 option OPEN is at/through the target or the stop, it fills at that open
  (stop: -10 bps). Then on each 1-minute bar 09:15 .. 10:14: stop checked first (fills at the stop, -10 bps), then
  the target (fills at the target). Not hit by 10:14 -> sold at the 10:15 open (-5 bps).
