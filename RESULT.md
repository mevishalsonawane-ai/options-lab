# R144b result (1 Nov 2026): NOT RUN, blocked on data
1. Arms: Liquidity 15+5 (BANKNIFTY, FINNIFTY, MIDCPNIFTY), ORB, ORB Fresh, ORB Sweep, Range Fade, Solo 2.0.
2. Blocked: this session had no Dhan data lake (no rclone, no Drive copy, no R3 / R51 / R143 / R57 stores), so no trade was rebuilt.
3. No numbers were guessed. results/pvalues.json has no tests, so R144b adds nothing to the coordinator's BH across R144a-d.
4. Done: the pre-registration (pushed first), every feature, the fixed filter-choice rule and the R142 gates, in research/hunt/r144b_loss_orb_liq/PREREG.md.
5. Done: the code (r144b_lib.py, r144b.py: adapters to the R3, R115/R101 and R57 engines, unchanged) and 11 passing tests, incl. the look-ahead guard.
6. To finish: in a session with the lake, run `collect {orb|liq|solo} design`, then `design`, `collect ... final|unseen`, then `test` (see the write-up).
7. The first real run must pass the parity checks; the adapters have not touched real stores yet.
8. Known already (R3): the ORB family lost in all 6 years, so a skip filter is unlikely to pass the "still makes money" gate.
9. Known already (R141): Liquidity loses 56-63%, its losers bunch on all-lose days, and its one steady sign (a gap against the trade) failed as a filter.
10. Write-up: research/HUNT_R144B_LOSS_ORB_LIQ.md. Entries: r144b-anatomy.yaml and r144b-filters.yaml (not-a-test).
