## Profit-lock ladders on the normal arms (research/profit_lock.py)

BANKNIFTY year A 2024-02-13 .. 2025-02-14, year B 2025-02-17 .. 2026-02-23; real ATM option prices, 1 lot of 30, after costs.
Each ladder: once the option has gone X% of the way to the target, the stop moves to the stated level.

### ORB (stop -40, target +40)

| ladder | year A: trades, win, net, t | year B: trades, win, net, t | both years |
|---|---|---|---|
| none (today) | 1774 | 47% | Rs -218,332 | -4.33 | 1109 | 45% | Rs -193,592 | -4.94 | Rs -411,924 |
| 75% -> lock 50% | 1972 | 56% | Rs -130,612 | -2.72 | 1257 | 52% | Rs -171,633 | -4.49 | Rs -302,245 |
| 50% -> breakeven | 2114 | 33% | Rs -194,603 | -4.27 | 1381 | 30% | Rs -204,186 | -5.64 | Rs -398,788 |
| 50% -> BE, 75% -> lock 50% | 2167 | 44% | Rs -134,582 | -2.98 | 1434 | 41% | Rs -167,550 | -4.65 | Rs -302,132 |
| 25% -> BE, 50% -> lock 25%, 75% -> lock 50% | 2558 | 44% | Rs -77,013 | -1.99 | 1895 | 41% | Rs -69,754 | -2.15 | Rs -146,767 |
| 50% -> lock 25%, 75% -> lock 50% | 2207 | 64% | Rs -121,986 | -2.74 | 1494 | 64% | Rs -113,814 | -3.16 | Rs -235,800 |
| 25% -> half risk, 50% -> BE, 75% -> lock 50% | 2323 | 40% | Rs -116,144 | -2.74 | 1602 | 36% | Rs -175,002 | -5.18 | Rs -291,146 |

### ORB Fresh (stop -40, target +40)

| ladder | year A: trades, win, net, t | year B: trades, win, net, t | both years |
|---|---|---|---|
| none (today) | 498 | 46% | Rs -68,799 | -2.58 | 410 | 47% | Rs -56,777 | -2.39 | Rs -125,576 |
| 75% -> lock 50% | 510 | 54% | Rs -42,586 | -1.73 | 424 | 55% | Rs -36,291 | -1.66 | Rs -78,877 |
| 50% -> breakeven | 526 | 31% | Rs -50,322 | -2.25 | 450 | 31% | Rs -42,988 | -2.10 | Rs -93,310 |
| 50% -> BE, 75% -> lock 50% | 526 | 43% | Rs -35,322 | -1.61 | 450 | 42% | Rs -37,299 | -1.88 | Rs -72,620 |
| 25% -> BE, 50% -> lock 25%, 75% -> lock 50% | 553 | 46% | Rs -16,838 | -0.90 | 482 | 44% | Rs -6,361 | -0.40 | Rs -23,199 |
| 50% -> lock 25%, 75% -> lock 50% | 526 | 65% | Rs -20,621 | -0.95 | 450 | 66% | Rs -20,499 | -1.05 | Rs -41,120 |
| 25% -> half risk, 50% -> BE, 75% -> lock 50% | 541 | 37% | Rs -47,078 | -2.30 | 471 | 38% | Rs -33,744 | -1.85 | Rs -80,822 |

### ORB Sweep (stop -40, target +80)

| ladder | year A: trades, win, net, t | year B: trades, win, net, t | both years |
|---|---|---|---|
| none (today) | 315 | 36% | Rs -12,395 | -0.43 | 291 | 30% | Rs -73,480 | -2.95 | Rs -85,875 |
| 75% -> lock 50% | 320 | 38% | Rs -26,832 | -1.01 | 298 | 34% | Rs -77,526 | -3.39 | Rs -104,358 |
| 50% -> breakeven | 325 | 25% | Rs -30,445 | -1.19 | 307 | 25% | Rs -60,118 | -2.53 | Rs -90,563 |
| 50% -> BE, 75% -> lock 50% | 327 | 31% | Rs -30,026 | -1.23 | 310 | 30% | Rs -69,896 | -3.17 | Rs -99,922 |
| 25% -> BE, 50% -> lock 25%, 75% -> lock 50% | 349 | 33% | Rs -24,046 | -1.18 | 326 | 27% | Rs -76,025 | -4.19 | Rs -100,071 |
| 50% -> lock 25%, 75% -> lock 50% | 328 | 48% | Rs -25,279 | -1.08 | 310 | 42% | Rs -70,556 | -3.36 | Rs -95,836 |
| 25% -> half risk, 50% -> BE, 75% -> lock 50% | 338 | 28% | Rs -27,786 | -1.20 | 318 | 27% | Rs -73,845 | -3.53 | Rs -101,632 |

### Range Fade (stop -40, target +40)

| ladder | year A: trades, win, net, t | year B: trades, win, net, t | both years |
|---|---|---|---|
| none (today) | 330 | 47% | Rs -45,474 | -2.13 | 322 | 40% | Rs -84,458 | -4.14 | Rs -129,932 |
| 75% -> lock 50% | 340 | 53% | Rs -44,502 | -2.28 | 330 | 48% | Rs -73,918 | -3.88 | Rs -118,420 |
| 50% -> breakeven | 344 | 28% | Rs -61,186 | -3.44 | 342 | 25% | Rs -83,030 | -4.73 | Rs -144,215 |
| 50% -> BE, 75% -> lock 50% | 347 | 39% | Rs -54,830 | -3.12 | 342 | 34% | Rs -82,430 | -4.84 | Rs -137,260 |
| 25% -> BE, 50% -> lock 25%, 75% -> lock 50% | 368 | 36% | Rs -47,158 | -3.37 | 367 | 33% | Rs -72,340 | -5.23 | Rs -119,498 |
| 50% -> lock 25%, 75% -> lock 50% | 347 | 61% | Rs -47,051 | -2.73 | 342 | 56% | Rs -79,240 | -4.72 | Rs -126,292 |
| 25% -> half risk, 50% -> BE, 75% -> lock 50% | 358 | 34% | Rs -58,846 | -3.68 | 355 | 33% | Rs -75,986 | -4.76 | Rs -134,832 |

### ORB (stop -40, target +50)

| ladder | year A: trades, win, net, t | year B: trades, win, net, t | both years |
|---|---|---|---|
| none (today) | 1620 | 43% | Rs -153,614 | -2.86 | 988 | 41% | Rs -163,633 | -4.01 | Rs -317,246 |
| 75% -> lock 50% | 1788 | 49% | Rs -173,175 | -3.38 | 1121 | 47% | Rs -165,256 | -4.15 | Rs -338,430 |
| 50% -> breakeven | 1930 | 30% | Rs -178,147 | -3.63 | 1212 | 29% | Rs -173,260 | -4.50 | Rs -351,408 |
| 50% -> BE, 75% -> lock 50% | 2010 | 39% | Rs -181,558 | -3.76 | 1262 | 37% | Rs -174,282 | -4.57 | Rs -355,841 |
| 25% -> BE, 50% -> lock 25%, 75% -> lock 50% | 2401 | 42% | Rs -62,314 | -1.48 | 1696 | 37% | Rs -138,208 | -4.08 | Rs -200,522 |
| 50% -> lock 25%, 75% -> lock 50% | 2073 | 60% | Rs -127,875 | -2.68 | 1319 | 57% | Rs -166,520 | -4.45 | Rs -294,395 |
| 25% -> half risk, 50% -> BE, 75% -> lock 50% | 2150 | 35% | Rs -171,628 | -3.75 | 1397 | 32% | Rs -181,276 | -5.06 | Rs -352,903 |
### Reading it

- The owner's ladder (25% -> breakeven, 50% -> lock 25%, 75% -> lock 50%) is the best for ORB and ORB Fresh in BOTH
  years: ORB -218k / -194k -> -77k / -70k; ORB Fresh -69k / -57k -> -17k / -6k. It cuts the losses by 60-80% but does
  not make either arm profitable.
- ORB has no daily cap, so earlier exits mean more re-entries (1,774 -> 2,558 trades in year A).
- It does not help ORB Sweep (target +80) or Range Fade: their losses are about the same or worse with any ladder.
- With a +50 target ORB is worse than with +40 whatever the ladder.
- Optimistic: a moved stop fills at its level less 0.5; a fast option can fill worse.
