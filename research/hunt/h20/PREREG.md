# h20 pre-registration (written 2026-10-07 BEFORE any h20 result was computed)

Question: do the four ORB-family arms (ORB, ORB Fresh, ORB Sweep, Range Fade; BANKNIFTY, app rules) become profitable
after the app's costs with a smaller target / tighter profit lock / trail / time exit?

Data: obuy (Dhan BANKNIFTY option minutes, Aug 2021 ..). Signals: the app's rules exactly (OrbRules / SweepRules /
RangeFadeRules: 09:15-10:00 range, ATM from the 09:20 bar, ORB/Fresh decide bars 10:05..13:55, Sweep 10:05..14:25,
Fade 10:30..13:55, Sweep/Fade max 2 entries a day, cool-down: next decision bar starts after the exit's bar).
Contract: nearest expiry ON OR AFTER the day (same-day option on expiry days, app since 01 Oct). Entry refused when
premium <= 40 (app). Fill = next minute open +5 bps; resting stop -10 bps; app SandboxCosts (today's rates); lot as of date.
Rs/day also at today's lot.

LOCKED HOLDOUT 2025-10-01 .. latest: not looked at until the end; evaluated ONCE for the chosen variant(s).
Selection sample: everything before 2025-10-01. Anchored walk-forward by year (train >= 2 years).

Simulation modes (both reported, PRIMARY = "app"):
  app   = what the app actually does: the -40 stop is a resting SL-M (fills intrabar at trigger / gap open);
          the profit lock, trail, target and time exit are APP-SIDE checks on the sampled price, modelled as the
          minute CLOSE (peak = best close BEFORE this minute), filled at the NEXT minute's open (market sell).
  tick  = optimistic bound (what the backtest PROFIT_LOCK.md assumed): peak from minute HIGHS, lock/target as resting
          orders on the minute low/high, filled at the level (or the gap open).

Baseline (not an alternative, always reported): the app today = -40 stop, target +40 (Sweep +80), ladder 25/50/75% of
the target -> BE+charges / +25% / +50%; plus "no lock" (-40/+40).

Alternatives: exactly 6 exit variants x 4 arms = 24 trials (all counted in BH and SPA). All keep the -40 resting stop and
15:10 square-off. Ladder rungs are in absolute premium points; every rung floored at BE after charges.
  A1  target +15, no ladder
  A2  target +20, ladder +10 -> BE
  A3  target +30, ladder +10 -> BE, +20 -> +10
  A4  target +40, tight ladder +8 -> BE, +15 -> +8, +20 -> +12, +30 -> +20
  A5  target +40, trail: from peak >= +10 the stop is entry + 50% of (peak - entry) (floored at BE)
  A6  target +40, current 40-pt ladder, plus time exit: out 30 minutes after entry if the premium is below entry + 10
(For Sweep the baseline ladder is on its own +80 target, as the app has it; alternatives use the points above.)

Decision rule (fixed now): an alternative is "promoted" only if, on pre-holdout data in app mode: net > 0 after app
costs, beats random entries with the same exits (p < 0.05 after BH over the 24), positive in > half the walk-forward
years, and SPA p < 0.10 over the 24. Then the single best promoted (by pre-holdout net) per arm is run once on the
holdout. If none is promoted, the holdout is still run once for the best-by-pre-holdout-net variant per arm and
reported for information only (no promotion).
Random baseline: obuy pool, 5 random alternatives per signal, same day / book / strike rule / exits, random minute in
the arm's window, coin-flip side.
