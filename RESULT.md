# R116 for Boss: next-expiry options on expiry days (27 Oct 2026)

1. Answer: **no, keep skipping expiry days.** Buying the next expiry's option on an index's expiry day lost on all four arms.
2. Design (20 Nov 2024 - Mar 2026): R72 -Rs 41,872 (206 trades), R51 -Rs 31,128 (266), R52 -Rs 17,092 (49), Liquidity -Rs 3,577 (11).
3. Walk-forward year and final were negative too (final: R72 -Rs 3,251 on 29, R51 -Rs 7,983 on 34, R52 -Rs 10,191 on 8).
4. Monthly expiries like today were the worst: R72 -Rs 39,471 (57 trades), R51 -Rs 33,955 (74), R52 -Rs 18,233 (15).
5. Random entries in the same next-expiry options did as well or better, so the signals have no edge on these days.
6. A lot costs 2.5-5x more (NIFTY ~Rs 14,250 vs Rs 5,808; BANKNIFTY ~Rs 29,865); the 2% cap needs an account above Rs 1.1-2.3 lakh.
7. Data: NIFTY has next-expiry prints on 79 expiry days, SENSEX 19, BANKNIFTY 13 (none after Jan 2026), FINNIFTY / MIDCPNIFTY none.
8. So for BANKNIFTY / FINNIFTY / MIDCPNIFTY it is "not enough data"; only 8-34 final trades per arm (the gate needs 40).
9. Nothing goes on paper or live (0 of 8 tests passed). One lead only: R52 with the same-day option (+Rs 18,724, 38 final trades).
10. Proposed (TODO E1, needs your go-ahead): a log-only would-be record on expiry days, so evidence builds; re-test in ~6 months.

Full write-up: `research/HUNT_R116_EXPIRY_NEXT_OPTION.md`. Pre-registration: `research/hunt/r116_expiry_next_option/PREREG.md`.
