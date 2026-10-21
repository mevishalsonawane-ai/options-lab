# Lake stale prints: result

18 Oct 2026. Write-up: `research/HUNT_LAKE_STALE_PRINTS.md`. Results commit: **8e296a811c17f91e7dffad2a003712971e0e616a**
(branch `claude/lake-stale-print`; pre-registration 5c6168a7).

## The rule (lakeclean step 3, opt-in `R51_CLEAN=1 R51_STALE=1`, unchanged from PREREG.md)

An option minute is dropped when both are true:
- It is in a run of 5+ flat (O = H = L = C), zero-volume minutes at one price.
- Its price is more than 50% (and 2 ticks) outside the no-arbitrage band of the nearest lower and higher strikes of the
  same side, priced from their latest real trade, at most 15 minutes old.

No amendment was needed.

## How much it flagged (WEEK options, 20 Nov 2024 - 13 Oct 2026)

9,646 of 35,402,471 option minutes (0.03%; 9,666 counting duplicate rows), in 544 runs on 271 contracts and 159
index-days:

| Index | minutes flagged |
|---|---:|
| FINNIFTY | 5,099 |
| MIDCPNIFTY | 4,437 |
| BANKNIFTY | 92 |
| SENSEX | 18 |
| NIFTY | 0 |

- 481 of the 544 runs are in 2026.
- 4.78 million minutes are in a stale run; 99.8% of them are kept.
- Causal version: 9,078 minutes.
- Sanity read: of 351 runs with a later real trade that day, 91% traded inside the band. 28 runs (201 minutes) are
  possible false positives; none of them touched a study trade.

## Effect on each study

All 74 headline rows reproduced the committed results exactly on the Amendment A stores before step 3. No verdict changes.

| Study | effect of step 3 |
|---|---|
| R51 Amendment A | No trade changes. The passing Donchian 20 RT is identical (1,787 final trades, +Rs 55,439, q 0.025). Two near-misses' twin p rise by 0.006; they still fail. |
| R72 | No trade changes. C1 passes, identical (1,202 trades, +Rs 1,71,366, q 0.032). Six twin p move by up to 0.062; no gate flips. |
| R73 | T7 recommendation and trade-offs unchanged. The 6 passes are identical. One Budget-day design trade moves in the 1-minute-wait variants (W5D -Rs 4). |
| R76 | B0 design Rs 2,57,249 -> Rs 2,66,643 (L35 also +Rs 9,394; one Budget-day trade). Final unchanged; nothing recommended. |
| R77 | The Rs 1,855.85 fill is gone. A/B finals lose about Rs 99,700 less (A-X1 -Rs 1,13,096 -> -Rs 13,383, p 0.996 -> 0.198). All 8 still FAIL. |

The rule misses errors under its 50% margin (FINNIFTY puts on Budget day, 1 Feb 2026); details in the write-up.
