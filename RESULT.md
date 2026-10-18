# R81 result: Liquidity exits (18 Oct 2026). Full write-up: research/HUNT_R81_LIQUIDITY_EXITS.md

1. Liquidity keeps its own exits. None of the 11 new exit and trade-management ideas passed (0 of 66 tests, all five indices).
2. Tested: index ATR trail, index no-progress stop, stop beyond the sweep bar, half off at +15%, re-entry after a stop, adding to runners.
3. Also tested: 14:30 or 15:20 square-off, holding runners overnight with a GTT, breakeven after a structure confirmation, no -15% premium stop.
4. Near miss: dropping the -15% premium stop. It was never worse (+Rs 2.6k design, +Rs 23k final), but one trade carries the final gain and BH q is 0.165.
5. Early exits cut the few runners that make Liquidity's money (-Rs 62k to -85k in design). Holding runners overnight lost money in both periods.
6. Per index (plain rules): BANKNIFTY and FINNIFTY were positive in design, walk-forward and final. MIDCPNIFTY lost in the final period.
7. NIFTY loses and SENSEX is only its top 5 trades: do not add either. In the final period, no index beat random entries.
8. Nothing changes in the app. BANKNIFTY's plain-rules record for 26 Oct is untouched. No APP_SPEC (no pass). The final period was already read before (weak).
