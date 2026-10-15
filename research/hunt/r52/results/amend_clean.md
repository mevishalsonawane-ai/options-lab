| Index / series | option rows (2024 - 13 Oct 2026) | foreign dropped | stray opens dropped | highs capped |
|---|---:|---:|---:|---:|
| NIFTY_WEEK | 10,858,958 | 0 (0.00%) | 51,219 | 113,765 |
| NIFTY_MONTH | 10,841,490 | 600 (0.01%) | 17,733 | 24,458 |
| BANKNIFTY_WEEK | 10,851,131 | 440 (0.00%) | 38,327 | 47,721 |
| BANKNIFTY_MONTH | 10,805,837 | 39,588 (0.37%) | 19,524 | 20,919 |
| FINNIFTY_WEEK | 10,006,449 | 131,934 (1.32%) | 48,986 | 56,935 |
| FINNIFTY_MONTH | 8,418,155 | 191,081 (2.27%) | 74,559 | 22,997 |
| MIDCPNIFTY_WEEK | 10,707,375 | 1,722 (0.02%) | 37,051 | 65,186 |
| MIDCPNIFTY_MONTH | 9,066,828 | 47,945 (0.53%) | 49,917 | 26,372 |
| SENSEX_WEEK | 10,641,918 | 1,921 (0.02%) | 87,773 | 116,315 |
| SENSEX_MONTH | 7,228,511 | 63,109 (0.87%) | 54,177 | 24,859 |

| Setting | ORIGINAL final: trades, net, p, q | CLEANED final: trades, net, p, q, p_flip | cleaned design walk-forward | verdict now |
|---|---|---|---:|---|
| H37-10:orig | 294, -1,912, 0.0030, 0.0564 | 294, -1,912, 0.0030, 0.0564, 0.0100 | -10,205 | FAIL |
| H37-10:g17 | 294, +48,772, 0.0010, 0.0268 | 294, +48,772, 0.0010, 0.0268, 0.0140 | +275,024 | PASS |

**H37-10:orig** final by index, original -> cleaned: BANKNIFTY -2,590 -> -2,590, FINNIFTY -3,943 -> -3,943, MIDCPNIFTY +12,308 -> +12,308, NIFTY -1,768 -> -1,768, SENSEX -5,919 -> -5,919

**H37-10:g17** final by index, original -> cleaned: BANKNIFTY +6,794 -> +6,794, FINNIFTY -4,647 -> -4,647, MIDCPNIFTY +46,329 -> +46,329, NIFTY -4,479 -> -4,479, SENSEX +4,775 -> +4,775
- Original design trades that touched a removed or capped row: 16 of 714 (their net -28,887 of +292,648); foreign rows 0, stray opens 9, capped highs 9; by index {'FINNIFTY': 4, 'SENSEX': 4, 'BANKNIFTY': 3, 'MIDCPNIFTY': 3, 'NIFTY': 2}.
- Original final trades that touched a removed or capped row: 5 of 294 (their net -13,252 of +48,772); foreign rows 0, stray opens 1, capped highs 4; by index {'FINNIFTY': 4, 'MIDCPNIFTY': 1}.
- Twice the spread and slippage (cleaned data): final 294 trades +41,086 (p 0.0020), design 714 trades +277,608.
