# R144b result (1 Nov 2026): Liquidity 15+5, ORB family, Solo 2.0 losing trades; no skip filter passed
1. Rebuilt from the Drive lake with parity to the rupee (ORB family vs R3, Liquidity vs R101); periods: unseen Dec 2022-Nov 2024, design, final (read once).
2. Solo 2.0 LOST Rs 77,142 on 451 trades in 2022-24, the only years its shipped model never saw; its design/final profits are in-sample.
3. ORB, ORB Fresh, ORB Sweep, Range Fade lose on BANKNIFTY in the unseen years and design; the -40-point stop-outs are 79-98% of their money lost.
4. ORB family losers bunch on all-lose days (72-92%); steady signs: quiet break bar, gap against, low option volume, high VIX, directional days (Sweep).
5. Liquidity made money in every period (+83,307 / +2,57,249 / +11,155) but loses 56-69% of trades; 67-80% of losers on all-lose days; no steady entry sign.
6. Filters chosen on design: Liquidity 3, Range Fade 1; none qualified for ORB, ORB Fresh, ORB Sweep, Solo 2.0.
7. All 4 failed (8 tests, BH within group). Best: Range Fade narrow-morning skip halved its loss (unseen p 0.035) but BH q 0.28 and the arm still loses.
8. No arm reaches Boss's 65% win rule in any period (best: Solo 2.0 57.5% in-sample final). No switch proposed; nothing in the app changed.
9. Raw p-values for the coordinator: research/hunt/r144b_loss_orb_liq/results/pvalues.json (8 tests).
10. Write-up: research/HUNT_R144B_LOSS_ORB_LIQ.md. Entries: r144b-anatomy.yaml (not-a-test), r144b-filters.yaml (rejected).
