# I12 Level map: how often support and resistance levels held

*13 Oct 2026. Pre-registration: `research/context/PREREG.md` (I12). Code: `research/context/level_map.py` (the same rules
as ira-core's `LevelMap.kt`, which counts "held N of M" on the phone's own candles); numbers:
`research/context/results/level_map.json`. Data: the `research-data` release, BANKNIFTY 493 and NIFTY 494 sessions with
5 earlier sessions. Re-runs on the Dhan lake with one switch. Advisory only: nothing here trades.*

## Verdict (read this first)

**No kind of level held more often than a random price nearby.** Every level was tested against 5 placebo levels a day
(the same level moved 0.3-0.7% up or down). Across 24 tests (12 level types x 2 indices) none passed BH (lowest q = 0.76;
the best raw p: R2 at 0.05 and 0.06). Real levels held 18-37% of the times they were reached; placebos 24-32%.

So the chart shows each level with its own count ("PDH · held 60 of 256") and the placebo rate beside it, and calls
none of them strong. A level is a place to watch, not a place that holds.

## Held N of M (whole release; design / holdout split in the JSON)

A level is **tested** on a day when the index comes within 0.05% of it, and **held** when no 5-minute close after the
first touch went beyond it by more than 0.05% for the rest of the session (resistance from below, support from above,
by the side the index came from). Point in time: every level is built only from earlier sessions; the max-OI strikes
from the 09:20 OI are used from the 09:25 bar.

| level | BANKNIFTY held / tested | % | NIFTY held / tested | % |
|---|---|---|---|---|
| previous day high (PDH) | 60 / 256 | 23% | 74 / 247 | 30% |
| previous day low (PDL) | 59 / 239 | 25% | 52 / 225 | 23% |
| previous day close (PDC) | 70 / 381 | 18% | 92 / 364 | 25% |
| pivot P | 99 / 354 | 28% | 95 / 328 | 29% |
| R1 | 68 / 242 | 28% | 72 / 248 | 29% |
| S1 | 55 / 220 | 25% | 54 / 218 | 25% |
| R2 | 40 / 120 | 33% | 42 / 115 | 37% |
| S2 | 31 / 110 | 28% | 31 / 124 | 25% |
| nearest swing high above the open (5 sessions) | 78 / 396 | 20% | 98 / 384 | 26% |
| nearest swing low below the open | 93 / 398 | 23% | 91 / 383 | 24% |
| largest call OI strike (09:20) | 40 / 164 | 24% | 40 / 139 | 29% |
| largest put OI strike (09:20) | 51 / 207 | 25% | 50 / 193 | 26% |
| **placebo levels (random, same distance)** | | **25-28%** | | **24-32%** |

VWAP and its bands are drawn too (from the live order flow's futures VWAP when the phone has it); they move all day, so
they carry no "held" count. The index has no volume of its own: VWAP is the near future's.

## What the app does

The chart's indicator menu has "Ira: level map (held N of M)" in the "Market context" group, off until Boss adds it. It
draws today's levels with a label each ("PDH 52,310 · held 7 of 25 on this phone"). The phone counts on its own stored
candles (fewer sessions than here); the label says how many. Computed off the main thread and cached; drawing reads the
cache only.
