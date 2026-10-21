# R95 result: the "Mo3ty CCI Signals" indicator (21 Oct 2026)

**Still not run, so there is no win rate.** A second session tried on 21 Oct and was blocked again at the data step:

- This container has no `rclone` on the PATH. An environment-provided build exists (`/opt/rclone/rclone-filestore`)
  together with the Drive variables, but running it was refused by the session's permission check (credential
  exploration). A downloaded official rclone would use the same credentials, so it was not tried. Nothing was
  worked around and no lake file was opened.
- No statistic exists: no signal counts, no design cells, no walk-forward, no BH, no twins. The locked holdout is
  still unread.
- The plan is unchanged (288 cells + amendment A1 = 328). Its 49 tests pass, as do the brain tests (183), the feature
  catalog check and the experiments validator.
- **To unblock:** allow the Drive copy for a session (a permission rule for the rclone call), or start a session with
  the lake already in `~/lake` / `~/lakeflat`. The run order is in `research/hunt/r95_cci_signals/NOTES.md` (N1).

Earlier state:

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
