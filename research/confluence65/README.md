# Confluence65: finite hypothesis search and robustness

Start with results/REPORT.md and SOURCES.md. This batch established no65%-win
profitable strategy. It is not a promise that unlimited searching must find one.

Reproduce from the local data lake:

```bash
python -m pip install -r research/neural_v2/requirements-final-lock.txt
python -m pytest research/confluence65 research/neural_v2/test_strategy.py -q
python -m research.confluence65.study --lake /absolute/path/to/lake
python -m research.confluence65.report
```

The plotting command additionally needs matplotlib. Executed environment:
Python3.12.14, numpy2.3.5, pandas2.2.3, torch2.5.1+cpu, scipy1.18.1,
scikit-learn1.8.0, pyarrow17.0.0. See the actual package lock in neural_v2 for
available pinned versions; these dependencies support the shared engine even
though this batch does not train new neural weights.

Inputs: NIFTY/BANKNIFTY/INDIAVIX index minute files2025/2026; NIFTY weekly option
files2025/2026; fut_1m_live_index.parquet. Hashes in results/audit.json. The
existing expiry calendar supplies scheduled dates; option files have rolling
entry snapshots, so fixed strike/expiry tracking and missing-price checks remain
essential. No raw source files or private recordings are committed.

The new engine is a research copy of neural_v2.strategy.replay with explicit
stop/target settings and carried WF account state. Default fills, ledgers and
summaries match the shared engine in unit tests. Production source is unchanged.
The stop setting affects both risk admission and executable exits. Base/stress
and chronological diagnostics replay their own account; their trade counts can
differ. Default start100000, max2trades/day, one position and one lot.

32 frozen rules plus8 design-leader neighbors,40unique settings;80full-period
friction endpoints. Additional chronological and WF training runs overlap;
they are not independent discoveries. The first-stage leader was frozen before
later evaluations and all sensitivity cells are disclosed. The eligibility
selector may return NO_TRADE, and did. A zero-return empty ledger is not an edge.

The saved leader and WF ledgers provide individual executed trades. All40rules'
full normal/stress summaries,240calendar-window summaries, design-selection
scores, walk-forward training scores, decisions and risk states are in JSON.
Other rule ledgers can be recreated by build/adapt/evaluate; they are not
silently claimed saved. Progress records the completed stage; restart reruns
feature construction and replay, not a claimed persistent background service.

MonteCarlo:1000fixed-rupee permutations and1000five-day-block resamples including
zero-trade days. Trade shuffles retain total P&L. These simulations do not rerun
signals/cash/margin admission in a counterfactual sequence and are not intratrade
worst-case bounds. All80full endpoints use day-block p-values with BH, but unknown
prior research multiplicity is not cured. Wilson95% win intervals assume trade
independence and are descriptive, not a guarantee about the next trade.

No clean final holdout exists in this previously inspected period. Real
buyer/seller delta, spoofing, tick-level volume-at-price and resting liquidity
remain unavailable. Approximate futures profiles and signed-volume proxies are
not substitutes for those data. New encrypted phone recordings must be decrypted
and validated outside the public research repository before those tests exist.

Local prereg713c7cd and implementation clarification22d9718 preceded this batch's
outcomes. No paid resources, orders, application/workflow changes or main merge.
