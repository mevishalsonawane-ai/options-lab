# How to add a rule family to R52 (brief for helpers)

R52 re-tests older REJECTED index-option rules on 20 Nov 2024 onwards (PREREG.md). The harness (r52lab.py) prices
signals with research/obuy's engine on HELD contracts with real fills/charges. You write ONE module
`research/hunt/r52/fam_<name>.py` plus `research/hunt/r52/test_fam_<name>.py`. Read PREREG.md, r52lab.py,
r52rules.py (spec_rules), research/obuy/engine.py (module doc: signal columns, Exits, StrikeRule) and
research/obuy/strategies/common.py (helpers) first. Look at research/obuy/strategies/gb_meanrev.py for examples of
signal functions in this format.

## The module

```python
SPECS = [dict(
    key="R1-2",                      # catalogue key (research/hunt/r52/CATALOGUE.md)
    title="Larry Williams volatility breakout",
    source="index",                  # signal source: "index" | "index + option-chain context (OI)" | "option-chart [NON-DEFAULT]"
    why="original = R1's pre-registered N2 setting (HUNT_R1_INTERNET.md table, research/hunt/r1/signals.py n2_wvb)",
    orig=dict(fn=signal_fn, params={...}, money=0, series="near", unds=("NIFTY", "BANKNIFTY"),
              exits=dict(stop_pct=0.3, sq_off=15*60+15), expiry="skip", pos=dict(one_at_a_time=True, max_per_day=1),
              window=(9*60+20, 14*60+30)),
    grid=[dict(params={...}), dict(money=1), dict(exits=dict(...)), ...],   # <= 23 overrides from the ORIGINAL study's own grid
)]
```

* `fn(mk, **params) -> pandas.DataFrame`, one row per entry candidate, columns:
  `und, day (datetime.date), sig_min (clock minute 555..928 whose 1-minute bar CLOSE triggers; fill = next minute's open),
  side (+1 buy CE, -1 buy PE, 0 straddle)`; optional `book` (one position at a time per book - use f"{name}_{und}"),
  `idx_stop`, `idx_target` (absolute index levels), `exit_at` (clock minute: structural time exit), and for multi-day
  holds `exit_day` + `exit_min` (see research/obuy/multiday.py; then exits must use sq_off=MD_SQ).
* Data: `mk.index(u)` (`.days`, `.d[day]['exp']` expiry flag, `.mat()` dense (n_days, 375) o/h/l/c, `.daily()`),
  `mk.options(u).chain(day)` (OI / IV / volume by strike, for chain-context rules), `mk.vix` (daily, `.minutes()`).
  Indices available: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX; data from 1 Jan 2024 (warm-up) to 13 Oct 2026.
  Make signals for every day you can (the harness drops days before 20 Nov 2024 and keeps the final test locked).
* REUSE THE ORIGINAL STUDY'S OWN CODE: import its signal / event / level functions where they are pure functions of
  index bars (add `research/` and the study folder to sys.path as needed), and only write the thin adapter to the
  columns above. Where the original code cannot be imported (needs deleted caches, other data layouts), port it
  faithfully and cite the file and function in a comment. Keep the original rule's exact settings, strike (ATM / ITM1,
  nearest expiry unless the rule says monthly), exits (premium %/points stop/target, index stop/target, time exit,
  square-off), expiry-day handling and trade caps. Where the original used a stop/target in index points, use
  idx_stop/idx_target; premium points -> Exits(stop_pts=..., tgt_pts=...).
* Signal on the underlying (CLAUDE.md). A rule whose signal reads an option's own price chart must say
  `source="option-chart [NON-DEFAULT]"`.
* No look-ahead: a row's decision may use only bars up to and including sig_min (and earlier days).
* Grid: the original study's own grid around the original setting, at most 23 overrides; leave it [] if the original
  had none.
* Skip a catalogue key you cannot test faithfully (data missing, rule unclear): do NOT invent; list it with the reason
  in the module docstring under "LEFT".

## Tests (pytest, no lake needed)

`test_fam_<name>.py`: for each signal function, a synthetic market (a tiny fake `mk` with `.index(u)` returning an
object with `days`, `d`, `mat()`, `daily()` - build it from numpy arrays) that should fire exactly the expected signal
(side, minute) and one that should not; plus a look-ahead guard: changing every bar AFTER a signal's sig_min (same
day and later days) must not change that signal. Edge cases: empty data, a missing-minute gap, an expiry day.
Run: `cd /home/user/options-lab/research/hunt/r52 && python -m pytest -q test_fam_<name>.py`.

## What you must NOT do

* Do not price trades or compute any P&L (no r52study.py, no r52lab.run, no obuy prepare/run_exits). Signal counts are
  fine: `OBUY_DATA=~/obuydata OBUY_CACHE=~/obuycache python -c "..."` calling your fn on the real data and printing
  signals per index per year is allowed and useful (check counts look like the original study's frequency).
* Do not edit any file outside your two files (not r52rules.py, not obuy, not the original studies). Do not commit.
* Do not print secrets.

Final reply: module path, keys covered (with the original setting in one line each), keys LEFT with reasons, signal
counts per index from 20 Nov 2024 to 31 Mar 2026 (design) for each key's original setting, test result line.
