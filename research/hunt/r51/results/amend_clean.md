| Index | option rows (20 Nov 2024 - 13 Oct 2026, WEEK) | foreign dropped | stray opens dropped | highs capped |
|---|---:|---:|---:|---:|
| NIFTY | 7,390,477 | 0 (0.00%) | 33,702 | 71,841 |
| BANKNIFTY | 7,384,644 | 440 (0.01%) | 11,582 | 14,004 |
| FINNIFTY | 6,522,558 | 115,698 (1.77%) | 23,971 | 13,528 |
| MIDCPNIFTY | 7,244,519 | 0 (0.00%) | 12,781 | 16,004 |
| SENSEX | 7,371,834 | 0 (0.00%) | 58,276 | 62,186 |

| Variant | config | ORIGINAL final: trades, net, p, q | CLEANED final: trades, net, p, q | cleaned design walk-forward | verdict now |
|---|---|---|---|---:|---|
| R44-donchian20-RT | donchian20|B|m1 | 1775, +48,689, 0.002, 0.025 | 1787, +55,439, 0.002, 0.025 | +169,661 | PASS |
| R44-ema821_adx15-RT | ema821_adx15|B|m1 | 1262, -34,495, 0.014, 0.1029 | 1270, -24,854, 0.006, 0.0536 | -43,858 | final net <= 0; top-5 trades >= net; design walk-forward <= 0 |
| R44-ema821_adx15_plock-RT | ema821_adx15_plock|B|m1 | 1262, -32,749, 0.014, 0.1029 | 1270, -23,109, 0.006, 0.0536 | -42,272 | final net <= 0; top-5 trades >= net; design walk-forward <= 0 |
| R44-ema921_adx20 | ema921_adx20|A|m0 | 774, +75,769, 0.018, 0.1125 | 781, +89,213, 0.008, 0.0667 | -86,460 | design walk-forward <= 0 |
| R44-ema821_adx15 | ema821_adx15|A|m0 | 1231, +19,511, 0.0319, 0.1595 | 1239, +38,867, 0.016, 0.1023 | -168,932 | q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| R44-ema821_adx15_plock | ema821_adx15_plock|A|m0 | 1231, +21,480, 0.0319, 0.1595 | 1239, +40,837, 0.018, 0.1023 | -207,077 | q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| R44-ema926_adx20 | ema926_adx20|A|m0 | 653, +78,014, 0.0399, 0.1781 | 660, +92,013, 0.0299, 0.1437 | -126,448 | q > 0.10; design walk-forward <= 0 |

**R44-donchian20-RT** by index (cleaned final): BANKNIFTY +13,504, FINNIFTY +41,263, MIDCPNIFTY +5,461, NIFTY -18,955, SENSEX +14,166
- Original rules on the CURRENT lake before cleaning (the lake was re-uploaded after R51's read): final 1787 trades +66,047, p 0.002; by index BANKNIFTY +13,504, FINNIFTY +51,255, MIDCPNIFTY +5,461, NIFTY -18,955, SENSEX +14,784.
- Original design trades that touched a removed or capped row: 29 of 4517 (their net -21,996 of +33,681); foreign rows 0, contract only in foreign rows 0, stray opens 20, capped highs 9.
- Original final trades that touched a removed or capped row: 6 of 1787 (their net +18,814 of +66,047); foreign rows 0, contract only in foreign rows 0, stray opens 5, capped highs 1.
- Twice the spread and slippage (cleaned data): final 1787 trades +5,757 (p 0.002), design 4518 trades -111,255.
