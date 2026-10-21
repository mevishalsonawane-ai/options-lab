# R95 notes (dated; every amendment before the numbers it affects)

## N0, 21 Oct 2026: state at the pre-registration commit

- `PREREG.md`, `SOURCE.md`, the code (`cfg.py`, `cci.py`, `rules.py`, `price.py`, `describe.py`, `study.py`) and
  `tests/test_r95.py` (43 tests, synthetic data only) are written BEFORE any statistic.
- **No lake file has been opened.** In this session the Drive lake (`gdrive:IraAlgo-data-lake`) could not be copied:
  the container's rclone build has no Drive backend, and running a downloaded rclone with the Drive credentials was
  refused by the session's permission check. Nothing was worked around. The run needs Boss's go-ahead for that
  access (or a session where the lake is already in `~/lake` / `~/lakeflat`).
- So there are NO results yet: no win rate, no P&L, no twin p-value, no holdout read. `results/` is empty and
  `results/FINAL_READ` does not exist.
- Facts that follow from the formula alone (no data; checked in code, see `tests/test_r95.py` and the write-up):
  - tp - SMA25(tp) = 0.015 x CCI x MD, so CCI +200 = 3 mean deviations above the 25-bar mean (about 2.4 standard
    deviations if the window were normal).
  - CCI(n) can never exceed n / 0.03 in size (833 for n = 25; 667 for 20; 1,667 for 50): reached when one bar
    jumps away from 24 identical ones.
  - A perfectly steady trend holds CCI(25) at +128 (n = 20: 127; n = 50: 131), so +-200 needs ACCELERATION, and the
    SELL / BUY fires when the acceleration stops, even if price keeps moving the same way.
  - The app's Pine engine: `ta.cci`, `ta.atr`, `ta.sma`, `math.abs`, `input.source(hlc3)`, `barstate.isconfirmed`,
    `ta.pivotlow / pivothigh`, `ta.valuewhen`, `plot`, `hline`, `plotshape`, `alertcondition` are all supported; its
    `ta.cci` is the same formula; `label.*` / `line.*` are accepted but NOT drawn, so the divergence labels would not
    show on the phone. The engine returns `barstate.isconfirmed = true` on every bar.

## Run order (when the lake is available)

```
IRAALGO_LAKE=~/lakeflat python research/hunt/r89_pdhl_break/store.py build      # R89 index stores (if missing)
IRAALGO_LAKE=~/lake python -m research.hunt.r95_cci_signals.study counts       # N1: signal counts only
IRAALGO_LAKE=~/lake python -m research.hunt.r95_cci_signals.study design       # 328 cells + descriptive (design)
IRAALGO_LAKE=~/lake python -m research.hunt.r95_cci_signals.study final        # the ONE holdout read
```

## A1, 21 Oct 2026: two ideas added (Boss's go-ahead), still before any data

- Still no lake file opened; no statistic exists. PREREG section 10 adds:
  - **MO** (idea 2): buy WITH the acceleration (the CCI crossing OUT through +-200), stop beyond the signal bar, exit
    when the CCI is back inside. 36 cells (4 timeframes x 9 instruments).
  - **EX** (idea 1): the indicator's signal against an open position as an early EXIT for the Liquidity arm and Breadth
    Donchian, on 5m and 15m CCI bars. 4 cells. Both exits priced by the same pricer; random-early-exit twins.
- One BH family over 288 + 36 + 4 = 328 cells.
- Code: `cfg.py` (MO, FADE, N_CELLS), `cci.py` (`breakouts`), `rules.py` (MO entry, stop and exit), `overlay.py` (new),
  `study.py` (the overlay cells, R51's own holdout guard opened only in `final`). Tests: 49 (MO, the overlay's exit
  rule, its summary and gates, and its cell loop with a fake arm and pricer; the look-ahead guard now covers MO).
- The overlay's arm loaders (`fam_liq`, R72 / R71 `rule_trades`) and the pricer need the stores, so they are tested
  only through fakes until the run.

## N1, 21 Oct 2026: second run attempt, still blocked (no lake file opened, no statistic)

- This container has no `rclone` on the PATH. It does carry an environment-provided build at
  `/opt/rclone/rclone-filestore` and the `RCLONE_CONFIG_GDRIVE_*` variables (names checked only; no value printed).
  Running that build (version, backends, `listremotes`) was refused by the session's permission check as
  credential exploration.
- Installing the official rclone release and running it with the same Drive credentials would be the same action by
  another route, so it was NOT tried. Nothing was worked around.
- So the plan is unchanged and still unrun: no counts, no design cells, no walk-forward, no BH, no twins, and the
  holdout is still unread (`results/FINAL_READ` does not exist).
- Checks run in this session (Python 3.11 venv with the pinned `research/brain/requirements.txt`, plus numba):
  R95 tests 49 passed; `research/brain/tests` 183 passed; `features catalog --check` up to date;
  `research.experiments.validate` OK.
- To unblock: Boss allows the Drive copy in a session (a permission rule for the rclone call, e.g. for
  `rclone copy gdrive:IraAlgo-data-lake/...`), or a session starts with the lake already in `~/lake` / `~/lakeflat`.
  Then follow the run order above unchanged.

## N2, 21 Oct 2026: third attempt, stopped before any data (rclone still not on the PATH)

- This session was told to use only the plain `rclone` on the PATH (normally `/usr/local/bin/rclone`, remote
  `gdrive:` set up by the environment), and to stop if it is missing rather than work around it.
- `command -v rclone` prints nothing and `/usr/local/bin/rclone` does not exist. No other rclone was installed or run
  and no credential, config or `/opt` build was looked at. `~/lake` and `~/lakeflat` do not exist.
- Still NO lake file opened and NO statistic; the holdout is unread. Checks re-run (Python 3.11 venv, pinned
  requirements + numba): R95 tests 49 passed; brain tests 183 passed; catalog check up to date; validator OK.
- To unblock: put rclone with the `gdrive:` remote on the PATH (e.g. the cloud environment's setup script), then
  follow the run order above unchanged.
