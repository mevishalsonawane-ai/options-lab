## TAS 1h with a profit lock on XAUUSD (research/gold_tas_lock.py)

Buys only, tracker ATR 19. USD per standard lot after costs; the last year is held out.

| targets | lock (start / giveback ATR) | Oct 2023 - Sep 2024 | Oct 2024 - Sep 2025 | Oct 2025 - Sep 2026 | 3 years | trades | win | t | deepest drawdown | avg month at 0.01 lot |
|---|---|---|---|---|---|---|---|---|---|
| 1.5/2.5/3.5 R | none (as added) | +47,624 | +52,151 | +65,704 | +165,479 | 222 | 45% | 2.21 | -38,065 | +46.0 |
| 1.5/2.5/3.5 R | 1 / 1 | +15,355 | -6,845 | +25,223 | +33,733 | 222 | 73% | 0.76 | -21,984 | +9.4 |
| 1.5/2.5/3.5 R | 1 / 1.5 | +21,261 | -1,442 | +42,144 | +61,964 | 222 | 58% | 1.21 | -27,057 | +17.2 |
| 1.5/2.5/3.5 R | 1 / 2 | +30,175 | +14,977 | +16,711 | +61,864 | 222 | 49% | 1.14 | -32,906 | +17.2 |
| 1.5/2.5/3.5 R | 1 / 3 | +31,556 | +33,858 | +51,111 | +116,525 | 222 | 45% | 1.77 | -39,165 | +32.4 |
| 1.5/2.5/3.5 R | 1 / 4 | +45,155 | +51,287 | +46,524 | +142,965 | 222 | 45% | 2.01 | -43,349 | +39.7 |
| 1.5/2.5/3.5 R | 2 / 2 | +33,127 | +27,232 | +11,868 | +72,227 | 222 | 60% | 1.25 | -41,198 | +20.1 |
| 1.5/2.5/3.5 R | 2 / 3 | +36,132 | +35,694 | +70,219 | +142,045 | 222 | 48% | 2.04 | -34,544 | +39.5 |
| 1.5/2.5/3.5 R | 0.5 / 1 | +16,186 | -10,969 | +13,764 | +18,981 | 223 | 57% | 0.49 | -23,597 | +5.3 |
| none | none (as added) | +45,930 | +82,622 | +84,511 | +213,064 | 222 | 44% | 2.37 | -42,601 | +59.2 |
| none | 1 / 1 | +15,744 | -6,657 | +25,791 | +34,879 | 222 | 73% | 0.78 | -21,796 | +9.7 |
| none | 1 / 1.5 | +22,825 | -1,612 | +41,505 | +62,718 | 222 | 58% | 1.22 | -27,057 | +17.4 |
| none | 1 / 2 | +31,378 | +13,937 | +13,340 | +58,655 | 222 | 49% | 1.09 | -33,473 | +16.3 |
| none | 1 / 3 | +31,386 | +34,876 | +50,565 | +116,827 | 222 | 45% | 1.74 | -42,779 | +32.5 |
| none | 1 / 4 | +43,119 | +73,116 | +42,239 | +158,474 | 222 | 45% | 2.04 | -48,845 | +44.0 |
| none | 2 / 2 | +34,179 | +26,965 | +8,001 | +69,145 | 222 | 60% | 1.20 | -42,262 | +19.2 |
| none | 2 / 3 | +37,146 | +36,502 | +68,372 | +142,020 | 222 | 48% | 2.02 | -38,157 | +39.4 |
| none | 0.5 / 1 | +16,575 | -10,441 | +14,642 | +20,776 | 223 | 57% | 0.53 | -23,597 | +5.8 |

### Reading it

- **Every profit lock lowered the profit**, in the fitting years and in the held-out year. The tight ones (1 ATR
  giveback) win 73% of trades but give back most of the edge (+$33.7k, t 0.76): TAS's winners are the long runs, and
  a tight lock sells them early. The loosest (1 / 4, 2 / 3) come closest (+$142-143k) but still under the arm as added
  (+$165.5k), and their drawdowns are no smaller (-$34.5k to -$43.3k vs -$38.1k).
- The arm already locks profit in its own way: after the first target its stop moves to the buy price.
- Without the targets at all (hold everything until the tracker turns down or the stop): +$213.1k (+45.9k, +82.6k,
  +84.5k), t 2.37, deepest drawdown -$42.6k - more in both the fitting and held-out years, at a slightly deeper fall.
  Not applied: the app keeps the strategy as written.
