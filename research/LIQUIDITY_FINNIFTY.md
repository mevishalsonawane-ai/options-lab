## Liquidity 15+5 on FINNIFTY with real options (research/liquidity_finnifty.py)

FINNIFTY, real option prices: 57 days with a chain (2024-10-14 .. 2026-03-27, 4 expiries)

Synthetic index for March 2026 from put-call parity, less a 6.1%/yr carry measured on 45 days that have both (median gap 43 pts before the carry).

| books, premium stop | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot of 65) | 1st / 2nd half | green months |
|---|---|---|---|---|---|---|---|---|
| 15-min, no premium stop | 29 (0.5/day) | 48% | -8.1 | Rs -245 | -0.68 | Rs -7,093 | -6,619 / -474 | 2/4 |
| 5-min, no premium stop | 75 (1.3/day) | 39% | +8.9 | Rs +350 | 1.09 | Rs +26,270 | +2,867 / +23,403 | 2/5 |
| **15+5 together, no premium stop** | 104 (1.8/day) | 41% | +4.2 | Rs +184 | 0.73 | Rs +19,176 | -3,753 / +22,929 | 2/5 |
| 15-min, 15% stop | 29 (0.5/day) | 48% | -2.9 | Rs -103 | -0.31 | Rs -2,998 | -2,525 / -474 | 2/4 |
| 5-min, 15% stop | 75 (1.3/day) | 39% | +9.5 | Rs +360 | 1.13 | Rs +26,972 | +2,610 / +24,362 | 2/5 |
| **15+5 together, 15% stop** | 104 (1.8/day) | 41% | +6.1 | Rs +231 | 0.93 | Rs +23,973 | +86 / +23,888 | 2/5 |

By expiry month (15+5, 15% stop): 2024-10 Rs +8,486 (11 trades), 2025-02 Rs -1,845 (2 trades), 2025-03 Rs -9,990 (32 trades), 2025-10 Rs -2,677 (38 trades), 2026-03 Rs +29,999 (21 trades)
Exits: next liquidity 49%, failed break 41%, premium stop 5%, 15:10 4%, new liquidity 1%

### Reading it

- The option data is the owner's upload (cloudtraderpro sample, 4 expiries: 22 Oct 2024 weekly, Mar 2025, Oct 2025 and
  Mar 2026 monthlies), not two full years; it is not in the repo or the release (third-party data).
- The +Rs 24k total rests on March 2026 (+Rs 30k), the only month without the real index: there the chart is rebuilt
  from option prices (put-call parity, minute closes only). Re-running the other three months on the same rebuilt
  chart instead of the real index changes the result from -Rs 6k to +Rs 9k, so the rebuilt chart moves the signals a
  lot and March 2026 cannot be trusted on its own.
- On the real index (Oct 2024, Mar 2025, Oct 2025; 83 trades) FINNIFTY made **-Rs 6.0k** per lot with the 15% stop.
  BANKNIFTY on the same days made -Rs 2.2k (106 trades): these were weak months for the rule on both indices, and
  FINNIFTY did somewhat worse, not better.
- FINNIFTY's option P&L matches its index direction (5-min +9.5 pts a trade) about as BANKNIFTY's does, so the rule
  can be traded on FINNIFTY options; the sample is too small (t 0.9) to say it adds an edge.
