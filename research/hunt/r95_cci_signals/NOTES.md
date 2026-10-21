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
IRAALGO_LAKE=~/lake python -m research.hunt.r95_cci_signals.study design       # 288 cells + descriptive (design)
IRAALGO_LAKE=~/lake python -m research.hunt.r95_cci_signals.study final        # the ONE holdout read
```
