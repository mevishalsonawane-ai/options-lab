# R90 source: "Almazov Trade: MTF Structure & BOS Entries" (Pine v6)

Boss pasted the full source in chat on 20 Oct 2026. © Algorithms_ATS, Mozilla Public License 2.0
(https://mozilla.org/MPL/2.0/). This file is the exact rule set, written out from that source, for R90 to rebuild and test.
If the study needs the verbatim Pine, ask the coordinator: it is in the coordinator's chat, not in git.

## Timeframes
- LTF = the chart timeframe (standard minute/hour charts only).
- HTF = 4 x the chart timeframe by default, or a custom multiple (2..100 x, an integer multiple).
- HTF bars are built by aggregating `tf` chart bars, aligned to `time / (60000 * tf * chart_minutes)`.

## Swings (both timeframes, on that timeframe's bars)
- Swing high: a bar whose high is >= the highs of `D_swing_high` (default 2) bars on each side (no bar on either side is higher).
- Swing low: the mirror, with `D_swing_low` (default 2).
- A swing is known only after its right-hand bars close (no look-ahead).

## Structure and trend, per timeframe
- Start: find the first swing high and swing low. Uptrend if the low came first (low -> high leg), downtrend if the high came first.
- Uptrend: a new swing high above the current high makes a new leg; the swing low between them becomes the protected
  low. If a bar's low goes below the protected low -> CHoCH down: the trend flips and the structure is rebuilt.
- Downtrend: the mirror (a high above the protected high -> CHoCH up).
- BOS (on each timeframe): on a closed bar, price crosses the active swing level in the trend's direction (the high in an
  uptrend, the low in a downtrend), with the path open -> nearer extreme -> far extreme -> close, before reaching the
  opposite level. Each level can be broken once.
- Sweep (shown only, it does not filter): the wick crosses an existing level and the close is back inside.

## Trade model ("BOS to BOS")
- A setup exists on a closed bar when:
  - the LTF trend equals the HTF trend;
  - the entry is the LTF active swing level (the high for a long, the low for a short), not yet broken, and the close
    is still on the near side of it (a buy-stop above / sell-stop below);
  - the stop is the opposite LTF swing level, -/+ 1 tick;
  - the target is the HTF active swing level in the same direction (the HTF high for a long, the HTF low for a short),
    not yet broken;
  - RR = |target - entry| / |entry - stop| >= min_rr (default 1.0).
- Fill: a stop order at the entry level. A gap through it fills at the open. Then the stop and the target are checked
  along the open -> nearer extreme -> far extreme -> close path, the stop first if both are touched in the same segment
  (whichever is nearer from the segment start).
- One trade at a time. After a target, the HTF level counts as broken; after a fill, the LTF level counts as broken.
- The author notes: fills on closed-bar OHLC, no fees, no slippage, not a strategy-tester report.

## Defaults
- Swing 2/2, min RR 1.0, HTF 4x, and both directions.
