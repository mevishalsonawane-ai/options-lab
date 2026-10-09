# R11 move anatomy: what was fixed before the holdout was read

Written 9 Oct 2026, after the design-period runs (`anatomy.py design`, days before 2025-10-01) and BEFORE
`anatomy.py hold` (2025-10-01 .. 2026-10-06, run once; flag file `scratchpad/hunt/r11/HOLD_READ`).
Design runs were repeated only to fix code problems (a date filter, int32-overflowed volume cells, winsorising z at
+-6 because thin baselines exploded, VWAP z before 09:30, flag features' spike rule); no holdout row was computed
by any of them.

## Events (thresholds from the design period, applied unchanged to the holdout)
- x = 1-min log return / sigma(day, minute), sigma = mean |1-min return| at that minute (+-2 min) over the previous
  60 days (time-of-day AND regime normalised). Eligible minutes 09:16-15:14 (the 15:15-15:27 closing-auction freeze
  and the 09:15 opening print are out).
- m1_top1 / m1_top5: |x| in the top 1% / 5% of design minutes. m5_top1 / m5_top5: same on 5-minute candles of the
  09:15 grid. Within a day, an event less than 10 minutes after the previous one of its kind is merged into it.
- leg: >= 0.4% in <= 30 minutes (closes) without a 0.15% pullback; starts at the lowest (highest) close of the run,
  ends at the extreme before the first 0.15% pullback.

## Controls
- other_day: 5 random days of the same period, SAME minute, with no top-5% minute in [t-30, t+dur+10].
- same_day: up to 3 minutes of the SAME day, >= 45 min away, same busy-free rule (removes the day's regime).

## Statistics
- Minute features are z-scores vs the same minute of the previous 40 days (median / IQR), winsorised at +-6;
  flags and distances are raw (distances in units of the prior day's range). Signed features are multiplied by the
  move's direction. Effect = mean(event window - mean of its controls), SD units; day-clustered t; BH over all design
  tests (both control types, all kinds, both indices).
- Windows: W30 = minutes -30..-16, W15 = -15..-6, W5 = -5..-2, W1 = -1, EV = the event candle / leg.

## Labels (applied to the design numbers, other_day controls, m1_top1 and m1_top5, both indices)
- LEADS (>= 2 min): W15 or W5 has q <= 0.05 and |eff| >= 0.10 in both indices, same sign.
- STATE: as LEADS but W30 is as large (>= 0.8x of W5) - it was already there half an hour before: a regime, not a
  trigger.
- 1-MIN LEAD: only W1 passes (|eff| >= 0.10, q <= 0.05, both indices).
- COINCIDENT: only EV passes (|eff| >= 0.10).
- NONE: nothing passes.
- Legs: signed features' W5/W1 are NOT used (a leg starts at the extreme, so the minutes before it moved the
  other way by construction).
- Holdout confirmation of a label: the same window, sign equal, p < 0.05 in each index (other_day controls).

## Hit rate / false alarm
- Spike: z >= 2 (|z| >= 2 for signed, the predicted direction = sign of the design W5 effect); flags: flag on;
  round numbers: within 5% of the grid (BANKNIFTY 500, NIFTY 100) of a round level.
- Outcome: a top-5% (or top-1%) minute in the next 5 / 15 minutes (in the predicted direction for signed features).
- Hit rate = share of spikes followed by the outcome; base = the same rate at random minutes of the same minute of
  day; lift = hit / base; false alarm = 1 - hit. "quiet" = only spikes with no top-5% minute in the previous 15 min.
