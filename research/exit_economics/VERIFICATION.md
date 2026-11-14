# Execution verification —15November2026 India

- Frozen local design commit839b16c preceded outcomes; 11exit policies plus one
  original-center latency arm,24full endpoints,48chronological diagnostic
  ledgers,2continuous WF ledgers.74CSV members archived and compared bytewise.
- Real locally available Parquet data loaded; fingerprints in results/audit.json.
- 25targeted and shared tests passed;20warnings from intentionally tiny test
  samples estimating variance. No production changes require a full app rebuild.
- Original center ledger reproduced against both the original replay function
  and the previously published retest archive, excluding the renamed time-exit
  reason. Values, contracts, entry/exit times and counts match.
- All account ledgers reconcile counts,wins,net,gross,fees,friction,capital;
  fixed expiry and time ordering checked. No account stops silently removed.
- Initial run exposed an empty-NO_TRADE-ledger dtype issue in the numeric audit
  helper. Explicit float conversion fixed the verification helper; an empty
  ledger regression test was added. Full study reran successfully with unchanged
  trading parameters. No failed run output was treated as a result.
- MonteCarlo:1000permutations and1000five-day blocks per full endpoint. Permuting
  fixed rupee P&L does not reexecute account constraints; intervals aren't proof
  of an edge. BH covers24endpoints of this batch, not the entire project history.
- Matched-entry decomposition is explicitly post-result exploratory analysis;
  it does not introduce a further trading rule or selection filter.
- Results are retrospective, no untouched test, no paper/live approval.
  No new neural model was trained in this focused exit study.
