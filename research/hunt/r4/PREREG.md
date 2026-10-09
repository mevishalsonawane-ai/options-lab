# R4 pre-registration: "magic numbers", Gann, Fibonacci and harmonic patterns, done deeper than h37

Written 9 Oct 2026, BEFORE any R4 result (P&L or level statistic) was computed. Nothing below changes after results
are seen; anything added later is labelled POST-HOC in the report.

## What h37 already did (not repeated as-is)

h37 tested, on ZigZag swings (k = 2 / 4 x ATR14) of 5/15/60-min and daily bars, with 17 generic premium-point exits:
Fibonacci bounce / break at 38.2/50/61.8/78.6% of the last swing and 127.2/161.8% extension targets; Gann square-of-9
(sqrt(ref) +- j/4)^2, j = 1..4, ref = open or prev close, break / bounce; Gann 1x1 from swing pivots at 0.25 / 0.5 ATR
per bar; Gann bar-count time cycles (9s, squares) and daily calendar cycles; Gartley / Bat / Butterfly / Crab / ABCD
entered on the touch of D. h35 tested round numbers (500 / 1000) break / retest / bounce and classic pivots.

**Gaps R4 fills:** (1) the placebo-level test, i.e. do prices react at the claimed levels MORE than at randomly shifted
levels of the same spacing; (2) the exact Indian retail formulas (square-of-9 "buy above / sell below" calculator with
0.125 steps and its own targets / stops, 45-degree calculator, Fibonacci pivots, previous-day-range Fibonacci levels,
Gann 50% / quarters, opening-range Fibonacci extensions, "00 & 50" magnets, digital-root-9 numerology, Gann
point-numbers from the open); (3) Gann HiLo activator, Gann swing chart, intraday Gann angles from the opening range,
Gann minute cycles (45/90/180 from the open, 90/144 from the morning extreme), Gann seasonal dates, Fibonacci time zones;
(4) golden pocket (0.618-0.65) with confirmation and the Upstox 3-minute 61.8% + reversal-candle rule; (5) harmonics
NOT in h37 (Alt Bat, Deep Crab, Shark, Cypher, 5-0) plus all classic ones on 3-min bars, entered on PRZ CONFIRMATION
(close back out of D) with the patterns' own stops and 0.382 / 0.618 AD targets; (6) FINNIFTY with its 0.42% spread.

## Frame (fixed)

- Option BUYING only, Rs 1,00,000, FIXED 1 lot (today's lots: NIFTY 65, BANKNIFTY 30, FINNIFTY 60), no compounding.
- 1-ITM strike of the nearest expiry, decided on the CLOSE of index minute s, bought at the OPEN of minute s+1 (first
  printed bar within 3 minutes, volume > 0). Long signal = CE, short = PE. Engine = research/hunt/r1/lib.run_engine.
- Exits checked on the option's 1-minute HIGH/LOW (stop first if both in one minute); index exits leave at the open
  of the minute after the index touch (index 1-minute high/low); everything out at 15:10.
- Costs: app charges (obuy.costs 'app') + h24 real half-spread a side: NIFTY 0.16%, BANKNIFTY 0.16%, FINNIFTY 0.42%.
  The app's own +-5 bps fill is NOT stacked (X3 #2): each side pays max(half-spread, 5 bps); stop exits +5 bps.
- Entry window: signal minute 09:20 .. 14:45. Expiry days included (as r1). One position at a time per variant, at
  most 3 trades a day (signals while a trade is open are skipped).

## Exits (fixed now; each is its own variant)

- NAT = the variant's own index stop / target from the source (listed per signal), else none, square-off 15:10.
- OPT = standard option exit: premium stop -25%, premium target +50%, else 15:10.
- T30 = out after 30 minutes (T60 where stated for time-cycle rules).

## Signals (index minute data; bars anchored 09:15; a signal is known at its bar's close)

Magic numbers / Gann price levels
- M1 SQ9I (unofficed "Gann square of 9 intraday", the popular Indian calculator): ref in {LTP 09:25 close, 09:15 open,
  prev close}; grid g_i = (floor(sqrt(ref)) - 2 + 0.125 i)^2; buy_above = first grid level > ref, sell_below = the
  level below it. First 1-min close >= buy_above -> CE, <= sell_below -> PE (after the ref minute). NAT: CE stop =
  sell_below, target = k-th grid level above buy_above x 0.9995 (k in {1, 3}); mirror for PE (x 1.0005).
- M2 SQ9H (45-degree calculator, step 0.25): ref in {09:25, prev close}; same rule, k = 1.
- M3 RMAG ("00 & 50 strikes are magnets"): grid minor (NIFTY/FIN 100, BN 500) and major (NIFTY/FIN 500, BN 1000).
  At a 5-min close 0.05-0.15% away from the nearest grid level, trade TOWARD it (one per level per day). NAT: target
  = the level, stop = the same distance on the other side.
- M4 RFADE (round-number fade, minor grid only): a 5-min bar touches the level from one side and closes back on that
  side -> fade. NAT: stop 0.10% beyond the level, target 0.20% back.
- M9 ORFIB ("magic 15-minute candle" + Fibonacci extensions): OR = 09:15-09:29; first 5-min close beyond -> follow.
  NAT: stop = OR middle, target = OR low + 1.272 x OR range (k127) or + 1.618 x range (k162) (mirror).
Gann
- G2 HILO (Krausz Gann HiLo activator, 3-bar SMA of highs / lows) state flip on tf {5, 15, 60} -> follow. NAT = exit
  at the opposite flip.
- G3 SWING (Gann 2-bar swing chart: two higher highs = up-swing; inside bars ignored): close above the last swing
  top while in a down-trend -> CE (mirror). tf {15, 60}. NAT: stop = last swing bottom (top), else 15:10.
- G4 MID50 (Gann 50% / quarters of the previous day's range: L + {0, .25, .5, .75, 1} R): BNC = 5-min bar touches
  the 50% level and closes back on its side -> fade; BRK = first 5-min close through 50% -> follow. NAT: target the
  next quarter level, stop the quarter level beyond.
- G5 ANG (Gann 1x1 / 2x1 from the opening range, unit = previous day range / 75 per 5-min bar, i.e. "one day's range
  per session"): from 09:45, rising line from the 09:15-09:44 low; first 5-min close below it -> PE; falling line
  from the OR high, first close above -> CE; first signal of the day. NAT = exit at the first 5-min close back across
  the line.
- G6 TIME: TOPEN = at 10:00, 10:45, 12:15 (45 / 90 / 180 min from the open) if |close - open| > 0.2%, fade the move
  (first qualifying checkpoint); TEXT: anchor = the minute of the later of the 09:15-10:14 high and low; at anchor + 90
  and + 144 min fade the move since the anchor if |move| > 0.2% (first qualifying mark). Exits OPT, T30, T60.
- G7 SEAS (Gann seasonal dates Feb 4, Mar 21, May 6, Jun 21, Aug 8, Sep 23, Nov 7, Dec 21; first session on/after):
  at 09:16 fade the 5-day trend (CE if the 5-day return < 0). Exits OPT and 15:10 (EOD).
Fibonacci
- F1 FIBPIV (Fibonacci pivots: P = (H+L+C)/3 previous day, R/S = P +- {0.382, 0.618, 1.0} x range): BRK = first
  5-min close above R1 -> CE (below S1 -> PE), NAT target R2 / S2, stop P; BNC = bar touches R1 and closes below ->
  PE (mirror S1 -> CE), NAT target P, stop R2 / S2.
- F2 PDRFIB (previous-day-range Fibonacci levels L + r R, r in {0.382, 0.618}): BNC = 5-min touch-and-close-back ->
  fade; BRK = first close through 0.618 (from below) / 0.382 (from above) -> follow. NAT: target / stop = next /
  previous level of the grid {-0.618, -0.272, 0, .236, .382, .5, .618, .786, 1, 1.272, 1.618}.
- F3 GP (golden pocket 0.618-0.65 of the last ZigZag swing, k = 2 ATR14, tf {3, 5, 15}): a bar's low (high) enters the
  pocket and the bar closes back above 0.618 (below) -> trade the swing direction. NAT: stop at the 0.786 level,
  target the swing end (1.0); NAT127: target the 1.272 extension.
- F4 UPX61 (Upstox 3-minute 61.8% + reversal candle): 3-min ZigZag k = 2; a bar touches 61.8% and closes beyond the
  previous bar's open in the swing direction; NAT: stop = the bar's extreme, target 1R.
- F5 = M9 ORFIB (counted once, listed under both).
- F6 FTZ (Fibonacci time zones): from the last confirmed 5-min ZigZag pivot (k = 2) of the day, at bar counts
  {5, 8, 13, 21, 34, 55}: fade the move since the pivot (PE if above it). Exits OPT, T30, T60.
- F7 CONF (confluence): an F1 R1/S1 or F2 0.382/0.618 level within 0.05% of a round 100 (NIFTY/FIN) / 100 (BN)
  level: BNC rule as F2.
Harmonics (tf {3, 5, 15, 60}, ZigZag k = 2 ATR14, continuous across days)
- GARTLEY B 0.618 XA (+-0.03), D 0.786 XA, stop X; BAT B 0.382-0.50, D 0.886, stop 1.13 XA; ALTBAT B 0.30-0.382, D 1.13,
  stop 1.27 XA; BUTTERFLY B 0.786 (+-0.03), D 1.272, stop 1.414 XA; CRAB B 0.382-0.618, D 1.618, stop 2.0 XA;
  DEEPCRAB B 0.886 (+-0.03), D 1.618, stop 2.0 XA. All: BC/AB 0.382-0.886 (5% tolerance on range ends).
- CYPHER: B 0.382-0.618 XA, C 1.272-1.414 XA beyond A, D = 0.786 XC; stop X.
- SHARK (0 X A B): AB/XA 1.13-1.618 (B beyond X), completion C = 0.886 retracement of 0X (zone to 1.13); stop 1.13 of 0X.
- FIVE0 (0 X A B C): AB/XA 1.13-1.618, BC/AB 1.618-2.24, D = 50% retracement of BC; stop = the 0.786 retracement of BC.
- ABCD: BC/AB 0.382-0.886, D: CD = AB; stop D - 0.272 CD.
- HALL: union of all the above.
- Entry: the first bar after the last pivot's confirmation whose low (bull) reaches D and which CLOSES back above D
  (bear mirror), before the price runs past the stop. NAT1 target 0.382 AD retracement, NAT2 0.618 AD; stop as listed.

## Placebo-level test (the main scientific test)

For each level family, every day: claimed levels (known before the session or at the anchor time) and placebo levels =
the same construction with a random shift of the same spacing (20 draws per day):
- SQ9_125 / SQ9_25: (sqrt(prev close) + 0.125 i + phi)^2 / step 0.25; placebo phi ~ U(0, step).
- RND100 / RND50 (NIFTY/FIN: 100 and 50; BN: 500 and 100), RND_MAJ (NIFTY/FIN 500, BN 1000): placebo = grid + U(0, spacing).
- NUM9 (digital root 9 = multiples of 9): placebo = grid + U(0, 9).
- GANNPTS: open +- {45, 90, 144, 180, 360} points; placebo: all offsets + common U(-22.5, 22.5).
- FIBPIV: ratios {.382, .618, 1.0}; placebo ratios + delta ~ U(+-0.03 .. +-0.12).
- PDRFIB: L + r R, r in {.236, .382, .5, .618, .786}; placebo r + delta ~ U(+-0.015 .. +-0.06).
- GQTR: L + {.25, .5, .75} R; placebo + delta ~ U(+-0.03 .. +-0.12).
- SWFIB: last 5-min ZigZag swing (k = 2) retracements {.382, .5, .618, .786} and extensions {1.272, 1.618}; placebo
  ratio + delta ~ U(+-0.015 .. +-0.06).
Event = first touch of a level in the day (after 09:20): a minute whose high (low) reaches the level while the
previous close was below (above). Outcome within 60 minutes: REVERSAL if the price retreats d back from the level before
it moves d through it; BREAK otherwise if it moves d through; unresolved excluded. d in {0.10%, 0.25%}. A bar that
already reaches level +- d through counts as a break. Statistic: reversal rate (claimed) minus reversal rate (placebo),
day-cluster bootstrap (B = 1000) p one-sided (claimed > placebo). Also the touch rate (magnet claim): touches per
level within 0.5% of the open, claimed vs placebo.
Time placebo: Gann minute marks (G6 TOPEN times, TEXT anchor + 90/144) and Fibonacci time zones vs the same rule at
times shifted by U(+-10 .. +-30) minutes: share of marks within +-5 minutes of a 5-min ZigZag (k = 2) turning bar.
Run separately on design and on the holdout; BH over all placebo tests. A level family "matters" only if claimed > placebo
with BH q < 0.05 in design AND p < 0.05 in the holdout, same sign.

## Periods, statistics, gates

- DESIGN = before 1 Oct 2025 (NIFTY from Aug 2020, BANKNIFTY / FINNIFTY from Aug 2021). HOLDOUT = 1 Oct 2025 - 6 Oct
  2026, opened ONCE by holdout.py after design.py writes frozen.json (marker file prevents a second run). Note: h37 /
  h35 / r1 have opened this calendar window before for OTHER rules; R4's rules have not been run on it.
- Per variant: trades, win%, Rs/day (all sessions, 0 on no-trade days), Rs/trade, max drawdown of daily equity,
  day-clustered t. Random twins: 10 per trade, same day, random side, random signal minute within +-30 minutes of the
  real one (clamped to 09:20-14:45), same exit type (index exits -> same holding minutes). p_rand = one-sided Welch.
- BH q over ALL design variants of all indices (t vs 0) and separately on p_rand; White Reality Check (max-t,
  stationary bootstrap, block 5, B = 1000) over each index's design daily P&L matrix of all variants.
- Holdout picks: per family (signal name) per index, the design variant with the highest daily t (whatever its sign).
- PASS = design Rs/day > 0 AND BH q < 0.10 AND design p_rand < 0.05 AND RC p < 0.10 AND holdout Rs/day > 0 AND holdout
  p_rand < 0.10. Variants with < 30 design trades get p = 1.
- Optional MCX / gold check (information only): the placebo-level test for RND, SQ9_125 and PDRFIB on XAUUSD minutes
  (2015-2023, the gold proxy available locally).
