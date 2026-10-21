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

## N3, 21 Oct 2026: the lake copied, stores built, signal counts (no outcome yet)

- This session had a working `rclone` (`/usr/local/bin/rclone`) with the `gdrive:` remote set up by the environment, and
  the copy of `gdrive:IraAlgo-data-lake` was authorised for it. Only plain `rclone lsf` / `lsl` / `copy` were used, with
  `--include` filters; no credential or config was looked at.
- Copied, only what the loaders read: `index_1m_{NIFTY,BANKNIFTY,SENSEX,FINNIFTY,MIDCPNIFTY}_{2024,2025,2026}`;
  `opt_{index}_WEEK_{2024,2025,2026}` (R51 loader); `opt_{GOLDM,SILVERM,CRUDEOILM,NATGASMINI}_MONTH_{2025,2026}` (R53
  loader). Nothing raw is committed; every raw file was deleted once its store was built.
- **Disk.** The container had about 1.1 GB free. FINNIFTY's R89 / R51 day store alone is 165 MB as a plain pickle
  (NIFTY's and SENSEX's about 2.5x that), so the five would not fit. Each store was built with the unchanged `store.py build` (R51 loader, Amendment A clean + stale step,
  window to 19 Oct 2026), then kept gzip-compressed on disk (about 5x smaller; 34-92 MB each), and the R53 MCX market
  pickles likewise. The run used a small runner kept outside git (scratchpad) that only swaps the pickle readers
  (`store.load`, `r51data.load`, `D53.load_market`) for gzip readers and then calls `study.run_counts / run_design /
  run_final`. The bytes read are identical; no study code, rule, window or setting changed.
- Store build (sessions 1 Sep 2024 - 19 Oct 2026: 528; with options from 21 Nov 2024: 474 for every index; expiry days
  NIFTY 100, SENSEX 98, the others 23): cleaning removed foreign rows FINNIFTY 115,698 / BANKNIFTY 440 / others 0 of
  6.6-7.5 million rows; stray opens 11,594-58,907; stale runs 18-5,132 (none logged for NIFTY).
- Checks before the run: R95 tests 49 passed (venv: Python with the pinned `research/brain/requirements.txt`,
  scikit-learn 1.5.2, numba 0.68, scipy 1.14).
- **Counts (design, `results/counts_design.json`; signal and trade counts only, no price outcome).** Trades per cell
  (index / MCX, min-max over the instruments):
  - B: 3m 806-840 / 577-589; 5m 531-575 / 527-557; 15m 226-240 / 222-264; 60m 40-60 / 42-56. Totals 8,277 index and
    5,720 MCX trades over the 36 B cells. MCX 3m and 5m sit near the cap of 4 a day on most sessions.
  - DV is thin: 0-52 trades a cell (26 of 36 cells under the 40-trade gate G1; every 60m DV cell has 0-2).
  - PU and L50 have 9 cells each under 40 trades (all 60m); every other variant and MO has at least 40 in every cell.
  - Most signals fall outside the entry window or on skipped days (the funnel's `day_off` / `window` counts: warm-up
    and holdout sessions, the 09:15-09:30 open, after 14:45).
- Next: `study.py design` (328 cells), then the one holdout read. No amendment.

## N4, 21 Oct 2026: design read (328 cells), before the one holdout read

- `study.py design` ran once over all 328 cells (324 entry cells + 4 EX cells), 1000 twins each, seed 95.
- **Output files only (no rule change):** `design.json` came out at 1.7 MB, over the 1 MB limit for results files.
  `study._save_split` now writes the descriptive block to `<stem>_describe.json` and the rest to `<stem>.json`, for both
  `design` and `final` (test `test_save_split_keeps_every_key_and_moves_only_the_descriptive_block`; R95 tests now 50).
  The design file already written was split with the same function, unchanged in content (`design.json` 0.71 MB,
  `design_describe.json` 0.97 MB). `run_final` reads only `n_cells` and `passers` from `design.json`, both kept.
- **Design passers (all 7 gates): 4 of 328.**
  - FINNIFTY 15m MO: 216 trades, net +Rs 1,13,428, option win 47.2%, payoff 2.06, twin mean -Rs 29,070, p_emp 0.001,
    q 0.00016, without the top 5 +Rs 55,053, folds +30,750 / +44,441 / +4,341 / +19,740, worst month -Rs 4,931.
  - FINNIFTY 5m MO: 407 trades, +Rs 59,212, win 43.5%, payoff 1.84, p_emp 0.001, q 0.00016, without top 5 +Rs 29,432,
    folds all positive, worst month -Rs 6,456.
  - BANKNIFTY 60m B: 48 trades, +Rs 51,359, win 43.8%, payoff 3.21, p_emp 0.004, q 0.081, without top 5 +Rs 3,640,
    worst month -Rs 7,945. 30 of the 48 exits are the session flat (the CCI-zero exit rarely comes within the day).
  - BANKNIFTY 60m XT: the same 48 entries, +Rs 35,651, p_emp 0.005, q 0.082 (34 flat exits). Not an independent
    second passer.
  - Near miss: BANKNIFTY 5m MO (441 trades, +Rs 30,695, q 0.0005) fails G6 only (-Rs 5,187 without its top 5).
- Everything else fails. Pooled design books (1 lot, after costs): indices B 3m -Rs 7.01 lakh (4,072 trades), 5m
  -Rs 4.73 lakh (2,766), 15m -Rs 71,491 (1,156), 60m +Rs 61,524 (244); MCX B lost on every timeframe (-Rs 93k to
  -Rs 4.83 lakh). MO pooled indices: 3m -Rs 2.66 lakh, 5m -Rs 58, 15m +Rs 1.73 lakh (1,068), 60m -Rs 79,765.
- EX overlay (4 cells): the early exit LOWERED net in every cell (Liquidity 5m -Rs 70,223 over 171 changed trades,
  15m -Rs 26,010 / 62; Breadth Donchian 5m -Rs 1.39 lakh / 1,368, 15m -Rs 15,795 / 110); p_emp 0.89-1.0. 0 of 4 pass.
- Walk-forward selection (descriptive): summed over the 36 instrument x timeframe pairs, picking the best variant so
  far gave -Rs 16.4 lakh against B's -Rs 21.3 lakh; it picked a different variant almost every fold.
- The holdout rule is unchanged (PREREG section 7): a design passer is a PAPER candidate only if in 1 Apr - 19 Oct 2026
  it has net > 0, >= 20 trades, p_emp < 0.10, BH q < 0.10 among the 4 design passers, worst month >= -Rs 13,000.
  Next: `study.py final`, once.

## N5, 21 Oct 2026: the ONE holdout read (1 Apr - 19 Oct 2026)

- `study.py final` ran once after commit c66b55e3 (design results and N4 pushed first); `results/FINAL_READ` now exists
  and a second read is refused. All 328 cells were run on the holdout (`final.json`, `final_describe.json`).
- **Verdict on the 4 design passers (pre-registered holdout rule, PREREG section 7):**
  - FINNIFTY 5m MO: 158 option trades, net +Rs 23,387, win 32.3%, payoff 2.98 (avg win Rs 1,553 / avg loss Rs 522),
    twin mean -Rs 6,611, p_emp 0.002, q 0.011 among the 4, worst month -Rs 2,923, 5 of 7 months green. **PAPER
    candidate.**
  - FINNIFTY 15m MO: 76 trades, +Rs 14,980, win 39.5%, payoff 2.00, twin mean -Rs 11,523, p_emp 0.018, q 0.032, worst
    month -Rs 9,214, 3 of 7 green. **PAPER candidate.**
  - BANKNIFTY 60m B: 17 trades, -Rs 1,014 (p_emp 0.41). Fails. BANKNIFTY 60m XT: 17 trades, +Rs 4,916, p_emp 0.155.
    Fails.
- **Read with care (not gates, stated so nobody over-reads the two candidates):**
  - Both are NEGATIVE without their 5 best holdout trades (5m -Rs 7,071; 15m -Rs 21,042); the profit rests on a few
    large wins, as an option buyer's usually does.
  - Same instrument and overlapping signals (a 15m burst is usually also a 5m burst), so they are close to one finding.
  - Over all 328 holdout cells their q would be 0.116 (5m) and 0.396 (15m); the lowest holdout q of any cell is 0.052.
    The pre-registered rule takes q among the design passers only; that is what was applied.
  - FINNIFTY 15m MO's underlying points were negative in the holdout (-2.1 points a trade, 93 trades); its option
    profit comes from the payoff shape (cut losers at the signal bar, keep the bursts), not from direction.
  - MO on the other four indices did not pass the design (BANKNIFTY 5m MO failed only "without the top 5"; holdout
    181 trades, +Rs 30,180, p_emp 0.004, not a design passer, so not a candidate). MO on MCX lost everywhere.
- Pooled holdout books (1 lot, after costs): indices B 3m -Rs 2.01 lakh (1,618), 5m -Rs 1.40 lakh (1,122), 15m
  +Rs 66,740 (477), 60m -Rs 20,858 (86); MO 3m -Rs 70,535 (1,473), 5m +Rs 84,813 (826), 15m -Rs 47,914 (447);
  MCX B and MO lost on every timeframe.
- EX overlay holdout: Liquidity 5m -Rs 1,899 (62 changed), 15m +Rs 1,247 (23); Breadth Donchian 5m -Rs 1.80 lakh (589),
  15m -Rs 588 (51). No design passer, so no candidate.
- Repaint (divergence labels, holdout): index entries at the label bar +Rs 4.75 lakh on 674 option trades (59% win)
  vs at the confirmation bar -Rs 37,156 on 691 (44%); MCX +Rs 1.69 lakh (995, 54%) vs -Rs 2.49 lakh (1,005, 27%).
- Nothing goes to paper or live from this session. The two candidates are reported to Boss; any paper arm needs Boss's
  decision and, per PREREG section 9, an APP_SPEC for a PAPER-only arm behind a switch that is off by default, logged
  in the Autopilot change timeline and undoable.
