# Retest entry timing research

Read results/REPORT.md after execution. This batch compares explicit delayed
retests with immediate entry on four underlying level types. It does not claim
that all retest strategies are new or that earlier negative studies never
examined pullbacks. It does not retrain Payoff20 to force a65% result.

```bash
python -m pip install -r research/neural_v2/requirements-final-lock.txt
OMP_NUM_THREADS=2 OPENBLAS_NUM_THREADS=2 python -m pytest research/retest research/neural_v2/test_strategy.py -q
OMP_NUM_THREADS=2 OPENBLAS_NUM_THREADS=2 python -m research.retest.study --lake /absolute/path/to/lake
python -m research.retest.report
```

Runtime versions are in environment.json. Inputs are2025/2026NIFTY/INDIAVIX
minute files and NIFTY weekly option files; hashes in results/audit.json. No raw
market bars or private recordings are committed.

48primary rules:4levels x2entries x3contexts x2exits. Eight sensitivity
neighbors of a PREDECLARED central rule give56unique rules. Normal/double
friction plus a central1minute execution-delay replay give114endpoints, jointly
BH adjusted. All full ledgers are saved in results/ledgers.zip. Reproduction creates loose CSVs; the archive preserves them without duplicate git records. Three chronological restart diagnostics
per endpoint are separate accounts; full account does not restart silently.
A preceding-data selector considers only the48primary rules for the two outer
windows and carries cash/peak/monthly state across those evaluations.

Entry source is the underlying index. Levels exclude current price except
ATR/current confirmation, which are known at the close. A pending retest freezes
its level and ATR tolerance at breakout; later moving levels cannot redefine it.
A same-bar break/touch cannot be a delayed confirmation. Missing minutes clear
pending states. Opening range requires all15actual minutes; previous session
levels need>=300bars. Static levels also require contiguous ATR warmup; rolling
range/compression require their20/60preceding minutes.

Higher trend uses completed5minute candles only, carries earlier complete bars
into EMA9/21, requires21bars and disables stale/overnight higher bars. Trend/cost
gates are applied at actual entry-confirmation time, never using future fill
prices. Reported-IV Black-Scholes delta uses6.5% rate; the expected-move/cost
ratio is a prior realized-volatility proxy, not a certified payoff forecast.
Retest sensitivity changes waiting time and touch tolerance; the breakout buffer
stays fixed at0.10ATR. A delayed execution retains the original selected strike.

The same corrected cost and research account engine is reused. Quick exit:
-15%stop,+10%target,20minute limit. Runner: -15%stop,no target/profit lock,
20minute failure exit unless+5%,60minute limit. Shared engine's unused peak-lock
updates are explicitly ignored by these exit functions. A touched OHLC fill is
modeled; stop precedes target if both touch. Gaps can exceed budgets.

Immediate and retest accounts may execute different later signals because their
holding times/cash paths differ. This is full policy comparison, not a remove-
only filter, guaranteed subset or complete app-engine parity proof.

Progress/events/dashboard and a partial.json recovery snapshot are updated.
No permanent background service is configured. Restart rebuilds features and
replays accounts; snapshots are not advertised as exact resumable account state.
Final duplicate partial snapshot is excluded from git.

Every account has1000fixed-amount permutations plus1000five-day block resamples;
these preserve within-day order in blocks but do not replay reordered cash or
signal gates. Pure shuffling preserves total P&L. Wilson win intervals assume
independent trades; sparse samples and reused history cannot prove65% future
wins. No actual depth, aggressor delta, spoofing or tick volume-profile test is
claimed. No real orders, paid resources, production/workflow change or main merge.
Local prereg93944e5 preceded numerical outcomes.

An additional opening-range April–October ledger-subset comparison is explicitly
post-review exploratory analysis. Its point win rate cannot be promoted while
the full-period/stress/sample gates fail. It is not a newly selected WF strategy
and cannot inherit OR-specific robustness from the fixed range-center grid.
