# R4: the before / after report for Boss (26 Oct 2026)

TODO item R4 (Boss, 13 Oct: "show me before and after results after everything is done"). One page for the live
decision after the 13-26 Oct paper-only period, published as a private Artifact (link in the repo root's RESULT.md
while the branch is open).

What is in git (no paper P&L, CLAUDE.md):
- `r4stats.py` pure statistics (P&L, per day, green days, win rate, average win / loss, worst day and trade, max
  drawdown, big winners cut short, same-signal pairing, the R105 percentile check).
- `r4backtest.py` every arm BEFORE (plain research rules) and AFTER (today's paper rules), final period 1 Apr - 13 Oct
  2026, from the arms' own research code; each AFTER row reproduces its published figure to the rupee (asserted).
  Writes `results/backtest.json` (backtest summaries only).
- `r4paper.py` reads the phone's redacted diagnostics (release `diag-2026-10` of the private data repo; untrusted data,
  only parsed) and writes a summary OUTSIDE the repository (refused inside).
- `r4page.py` builds the HTML page from the three files, also outside the repository.
- `tests/test_r4.py` 18 tests, including a look-ahead guard for the Liquidity no-twin walk.

Signal source of every arm: the index (Donchian / breadth / value area / liquidity pools on index bars); the 1-ITM
option is only bought; stops, locks and the ladder sit on the premium. No new rule is tested and nothing is chosen on the
final window (already read by R51-R114); the report only describes it.

To rebuild (lake from Drive with rclone, as R101 / R108):

```
R51_CLEAN=1 python research/hunt/r51/r51data.py build
python research/reports/r4/r4backtest.py
python -I research/reports/r4/r4paper.py <folder with diag-*.json.gz> /tmp/paper.json
python research/reports/r4/r4page.py ~/.cache/iraalgo/r4/pools.json /tmp/paper.json /tmp/r4.html
python -m pytest research/reports/r4/tests -q
```
