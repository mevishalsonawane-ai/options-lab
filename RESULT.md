# R95 result: the "Mo3ty CCI Signals" indicator (21 Oct 2026)

**Not run yet, so there is no win rate.** The plan, the code and 49 tests (including the look-ahead guard for the
CCI, the signals, the divergence pivots and the trades) are pushed before any number. The Drive lake could not be
copied in this session: the container's rclone has no Drive backend, and a downloaded rclone using the Drive
credentials was refused by the permission check.

- Plain-English explanation, the repaint warning and the Pine-engine check: `research/HUNT_R95_CCI_SIGNALS.md`.
- Plan: `research/hunt/r95_cci_signals/PREREG.md` (288 cells, twins, BH, walk-forward, one holdout read). Amendment A1
  adds two ideas from the formula, still before any data: MO (buy WITH the acceleration, 36 cells) and EX (the CCI as
  an early exit for the Liquidity and Breadth Donchian arms, 4 cells), 328 cells in all.
- Run order: `research/hunt/r95_cci_signals/NOTES.md`.
- Nothing goes to paper or live.
