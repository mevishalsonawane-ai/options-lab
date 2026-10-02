# Sideways guard: would "stop the arms when the market is sideways" have helped? (research/sideways_guard.py)

BANKNIFTY, two years of 1-minute data: A 2024-02-13 .. 2025-02-14 (249 sessions), B 2025-02-17 .. 2026-02-23 (249 sessions). Arms exactly as research/entry_cutoff.py replicates them today (1 lot of 30, real option minute prices, 0.5 slippage a side, Rs 40 a trip). Rs per lot after costs.
Run: `python research/sideways_guard.py banknifty_prev_year_wide.parquet banknifty_year_wide.parquet out.md <cache dir> --vix extra_series.parquet`
(the unguarded totals reproduce ENTRY_CUTOFF.md's "14:30" rows exactly, and Range Fade's "14:00" row).

## Verdict (read this first)

**Do not add a sideways guard. No detector improved any arm robustly in both years.** 7 rules x 5 arms = 35 tests;
none beat "skip the same number of random days" in both years (p < 0.10) without deepening the drawdown.

- **Liquidity 15+5 (the only arm that makes money) would be hurt.** Its trades on "sideways" days were profitable
  under almost every detector: prior-day-narrow days +16.8k (A) / +33.7k (B); low-VIX days +24.9k / +16.2k. Skipping
  narrow-prior-day days would have turned year B from +30,250 to -3,466. The intraday ADX pause cuts it from
  +103,019 / +30,250 to +52,096 / +8,972. A breakout-of-liquidity arm makes its money when price leaves a range, so
  "sideways" is exactly where its setups form. **Keep Liquidity 15+5 running on sideways days.**
- **ORB, ORB Fresh, ORB Sweep, Range Fade lose in both years with or without any guard.** Every skip "improves" them
  only because removing any losing days does; against random skips the improvements are mostly noise. The best
  candidate is below, and even it leaves every one of them deeply negative.
- **Closest to a real effect: ORB / ORB Fresh with the opening-range rule** (`OR(09:15-10:00) < 0.7 x average
  OR of the previous 20 sessions`, evaluated at 10:00, block that day's entries). ORB's trades on those days averaged
  Rs -98 vs -17 (A) and -63 vs -31 (B); skipping them: +41,654 (random +13,959, p 0.03) in A, +21,850 (random +11,178,
  p 0.18) in B; positive at 0.6 / 0.7 / 0.8 in both years; max DD -88,953 -> -43,610 and -87,654 -> -65,038. ORB
  Fresh: +13,068 (p 0.09) / +5,170 (p 0.23). It fails the bar in year B, and ORB still loses -35k / -48k a year with it.
  It is a hint that ORB breakouts out of an unusually narrow opening range fail more, not a fix; worth at most a note
  if ORB is ever re-tuned, not a global "stop all arms" switch.
- **The owner's "pause and resume" version** (ADX or CHOP on 15-min candles re-checked every 15 minutes) did no
  better: ADX-pause beats random only for ORB year B (p 0.15); CHOP-pause helps Sweep / Range Fade in A (p 0.04 / 0.01)
  and slightly hurts them in B. It costs Liquidity 15+5 half of its profit.
- **VIX** (low percentile = quiet) points the wrong way for ORB in A (-8.9k) and right in B; no consistency.
- CHOP(14) > 61.8 at 10:00 almost never fires on BANKNIFTY 15-min candles (8% / 4% of days), so it cannot matter much.

What the data says instead: the "sideways = don't trade" intuition is wrong for this app. The losing arms lose on
trending and sideways days alike (their sideways-day and other-day per-trade averages are both negative in every
detector), and the winning arm earns a good part of its money on days a sideways filter would switch off.

Caveats: BANKNIFTY only (the arms' home, with real option prices); no NIFTY/FINNIFTY/SENSEX option replication of
these arms with the app's parameters exists, so other indices were not tested. Year A's first ~20 sessions have no
20-day history for PDR / OR (treated as "not sideways"); VIX percentile needs 20 sessions of history.


## The arms without any guard

| arm | A trades | A net | A max DD | B trades | B net | B max DD |
|---|---|---|---|---|---|---|
| ORB | 2558 | -77,013 | -88,953 | 1895 | -69,754 | -87,654 |
| ORB Fresh | 553 | -16,838 | -27,658 | 482 | -6,361 | -16,241 |
| ORB Sweep | 349 | -24,046 | -31,716 | 326 | -76,025 | -77,230 |
| Range Fade | 368 | -47,158 | -49,248 | 367 | -72,340 | -76,390 |
| Liquidity 15+5 | 495 | +103,019 | -21,730 | 545 | +30,250 | -14,745 |

## How often each detector calls a day sideways

| detector | A days flagged | B days flagged |
|---|---|---|
| PDR < 0.7 (prior day range / 20d avg) | 69 / 249 (28%) | 53 / 249 (21%) |
| VIX pct < 25% (prior close, 60d) | 70 / 249 (28%) | 103 / 249 (41%) |
| ADX15m(14) < 20 at 10:00 | 65 / 249 (26%) | 81 / 249 (33%) |
| CHOP15m(14) > 61.8 at 10:00 | 19 / 249 (8%) | 9 / 249 (4%) |
| OR(09:15-10:00) < 0.7 x 20d avg | 45 / 249 (18%) | 42 / 249 (17%) |

## Day-level detectors: sideways days vs the rest, and the effect of skipping them

Per arm and year: trades / net / win% / avg on sideways days (S) and other days (O); the net and max DD with the guard on; the change vs no guard; the change expected from skipping the same number of random days (for a losing arm any skip 'helps'); p = share of 2,000 random skips that did at least as well. The 10:00 detectors only block entries decided at or after 10:00 (Liquidity's 09:20-09:55 trades stay).

### PDR < 0.7 (prior day range / 20d avg)

| arm | yr | S trades | S net | S win% | S avg | O trades | O net | O win% | O avg | guarded net | max DD (no guard -> guard) | change | random-skip change | p |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ORB | A | 723 | -29,865 | 43 | -41 | 1835 | -47,148 | 45 | -26 | -47,148 | -88,953 -> -58,478 | +29,865 | +21,406 | 0.32 |
| ORB | B | 377 | -13,157 | 38 | -35 | 1518 | -56,597 | 42 | -37 | -56,597 | -87,654 -> -69,846 | +13,157 | +15,079 | 0.57 |
| ORB Fresh | A | 146 | -2,330 | 45 | -16 | 407 | -14,508 | 46 | -36 | -14,508 | -27,658 -> -27,248 | +2,330 | +4,714 | 0.60 |
| ORB Fresh | B | 99 | -9,045 | 41 | -91 | 383 | +2,684 | 44 | +7 | +2,684 | -16,241 -> -14,261 | +9,045 | +1,319 | 0.13 |
| ORB Sweep | A | 92 | -13,835 | 26 | -150 | 257 | -10,211 | 35 | -40 | -10,211 | -31,716 -> -23,128 | +13,835 | +6,801 | 0.23 |
| ORB Sweep | B | 61 | -13,299 | 30 | -218 | 265 | -62,726 | 26 | -237 | -62,726 | -77,230 -> -63,762 | +13,298 | +16,298 | 0.65 |
| Range Fade | A | 93 | -17,304 | 32 | -186 | 275 | -29,854 | 37 | -109 | -29,854 | -49,248 -> -33,254 | +17,304 | +13,047 | 0.25 |
| Range Fade | B | 73 | -18,415 | 32 | -252 | 294 | -53,925 | 33 | -183 | -53,925 | -76,390 -> -60,505 | +18,415 | +15,431 | 0.32 |
| Liquidity 15+5 | A | 168 | +16,762 | 45 | +100 | 327 | +86,257 | 45 | +264 | +86,257 | -21,730 -> -18,415 | -16,762 | -27,878 | 0.34 |
| Liquidity 15+5 | B | 151 | +33,716 | 54 | +223 | 394 | -3,466 | 46 | -9 | -3,466 | -14,745 -> -18,274 | -33,716 | -6,867 | 0.98 |

Sensitivity (change in net vs no guard, A / B; thresholds [0.6, 0.8, 0.7]):

- ORB: 0.6: +5,700 / +8,812; 0.7: +29,865 / +13,157; 0.8: +29,505 / +18,292
- ORB Fresh: 0.6: -3,940 / +6,490; 0.7: +2,330 / +9,045; 0.8: -485 / +11,426
- ORB Sweep: 0.6: +2,585 / +915; 0.7: +13,835 / +13,299; 0.8: +15,925 / +26,842
- Range Fade: 0.6: +7,855 / +10,765; 0.7: +17,304 / +18,415; 0.8: +23,024 / +29,725
- Liquidity 15+5: 0.6: -8,163 / -8,421; 0.7: -16,762 / -33,716; 0.8: -20,874 / -42,467

### VIX pct < 25% (prior close, 60d)

| arm | yr | S trades | S net | S win% | S avg | O trades | O net | O win% | O avg | guarded net | max DD (no guard -> guard) | change | random-skip change | p |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ORB | A | 641 | +8,917 | 47 | +14 | 1917 | -85,930 | 43 | -45 | -85,930 | -88,953 -> -94,180 | -8,917 | +21,194 | 0.96 |
| ORB | B | 700 | -22,188 | 39 | -32 | 1195 | -47,566 | 42 | -40 | -47,566 | -87,654 -> -70,096 | +22,187 | +29,105 | 0.68 |
| ORB Fresh | A | 159 | -5,373 | 45 | -34 | 394 | -11,465 | 46 | -29 | -11,465 | -27,658 -> -17,970 | +5,373 | +4,620 | 0.47 |
| ORB Fresh | B | 183 | +3,184 | 43 | +17 | 299 | -9,545 | 44 | -32 | -9,545 | -16,241 -> -15,615 | -3,184 | +2,505 | 0.76 |
| ORB Sweep | A | 94 | -1,621 | 32 | -17 | 255 | -22,425 | 33 | -88 | -22,425 | -31,716 -> -23,880 | +1,621 | +6,623 | 0.70 |
| ORB Sweep | B | 128 | -43,112 | 23 | -337 | 198 | -32,913 | 29 | -166 | -32,913 | -77,230 -> -38,738 | +43,112 | +31,582 | 0.11 |
| Range Fade | A | 104 | -17,338 | 34 | -167 | 264 | -29,820 | 37 | -113 | -29,820 | -49,248 -> -31,970 | +17,338 | +13,134 | 0.24 |
| Range Fade | B | 151 | -41,815 | 30 | -277 | 216 | -30,525 | 35 | -141 | -30,525 | -76,390 -> -32,885 | +41,815 | +29,996 | 0.04 |
| Liquidity 15+5 | A | 155 | +24,893 | 39 | +161 | 340 | +78,126 | 48 | +230 | +78,126 | -21,730 -> -13,387 | -24,893 | -28,883 | 0.46 |
| Liquidity 15+5 | B | 244 | +16,207 | 51 | +66 | 301 | +14,043 | 47 | +47 | +14,043 | -14,745 -> -22,587 | -16,207 | -12,085 | 0.61 |

Sensitivity (change in net vs no guard, A / B; thresholds [0.15, 0.33, 0.25]):

- ORB: 0.15: -7,872 / +21,482; 0.25: -8,917 / +22,188; 0.33: +9,473 / +21,964
- ORB Fresh: 0.15: +3,223 / -1,514; 0.25: +5,373 / -3,184; 0.33: +15,533 / +1,491
- ORB Sweep: 0.15: +10,331 / +30,667; 0.25: +1,621 / +43,112; 0.33: -3,604 / +42,816
- Range Fade: 0.15: +20,538 / +36,560; 0.25: +17,338 / +41,815; 0.33: +21,958 / +40,790
- Liquidity 15+5: 0.15: -8,667 / -21,832; 0.25: -24,893 / -16,207; 0.33: -24,098 / -17,699

### ADX15m(14) < 20 at 10:00

| arm | yr | S trades | S net | S win% | S avg | O trades | O net | O win% | O avg | guarded net | max DD (no guard -> guard) | change | random-skip change | p |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ORB | A | 735 | -28,420 | 43 | -39 | 1823 | -48,593 | 45 | -27 | -48,593 | -88,953 -> -66,798 | +28,420 | +20,525 | 0.32 |
| ORB | B | 596 | -13,202 | 44 | -22 | 1299 | -56,552 | 40 | -44 | -56,552 | -87,654 -> -74,196 | +13,202 | +22,315 | 0.74 |
| ORB Fresh | A | 146 | -6,525 | 42 | -45 | 407 | -10,313 | 47 | -25 | -10,313 | -27,658 -> -19,640 | +6,525 | +4,267 | 0.39 |
| ORB Fresh | B | 149 | +5,605 | 48 | +38 | 333 | -11,966 | 42 | -36 | -11,966 | -16,241 -> -19,001 | -5,605 | +2,160 | 0.85 |
| ORB Sweep | A | 92 | -10,552 | 29 | -115 | 257 | -13,494 | 34 | -53 | -13,494 | -31,716 -> -28,515 | +10,552 | +6,473 | 0.32 |
| ORB Sweep | B | 103 | -13,764 | 29 | -134 | 223 | -62,262 | 26 | -279 | -62,262 | -77,230 -> -62,806 | +13,764 | +24,657 | 0.88 |
| Range Fade | A | 93 | -12,915 | 35 | -139 | 275 | -34,242 | 36 | -125 | -34,242 | -49,248 -> -36,632 | +12,915 | +12,448 | 0.46 |
| Range Fade | B | 112 | -12,460 | 38 | -111 | 255 | -59,880 | 30 | -235 | -59,880 | -76,390 -> -61,320 | +12,460 | +23,491 | 0.95 |
| Liquidity 15+5 | A | 109 | +6,830 | 41 | +63 | 386 | +96,189 | 46 | +249 | +96,189 | -21,730 -> -15,913 | -6,830 | -13,073 | 0.37 |
| Liquidity 15+5 | B | 118 | -6,299 | 47 | -53 | 427 | +36,549 | 49 | +86 | +36,549 | -14,745 -> -12,493 | +6,299 | +4,750 | 0.41 |

Sensitivity (change in net vs no guard, A / B; thresholds [15, 25, 20]):

- ORB: 15: +9,790 / +9,397; 20: +28,420 / +13,202; 25: +51,084 / +47,972
- ORB Fresh: 15: +11,365 / -885; 20: +6,525 / -5,605; 25: +3,353 / +196
- ORB Sweep: 15: +940 / +8,550; 20: +10,552 / +13,764; 25: +17,136 / +33,700
- Range Fade: 15: +7,760 / +8,220; 20: +12,915 / +12,460; 25: +37,230 / +31,195
- Liquidity 15+5: 15: +6,959 / +1,705; 20: -6,830 / +6,299; 25: -27,986 / +12,907

### CHOP15m(14) > 61.8 at 10:00

| arm | yr | S trades | S net | S win% | S avg | O trades | O net | O win% | O avg | guarded net | max DD (no guard -> guard) | change | random-skip change | p |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ORB | A | 273 | +9,290 | 46 | +34 | 2285 | -86,303 | 44 | -38 | -86,303 | -88,953 -> -100,023 | -9,289 | +6,021 | 0.93 |
| ORB | B | 75 | -4,425 | 37 | -59 | 1820 | -65,329 | 41 | -36 | -65,329 | -87,654 -> -82,304 | +4,425 | +2,462 | 0.39 |
| ORB Fresh | A | 40 | +8,004 | 57 | +200 | 513 | -24,843 | 45 | -48 | -24,843 | -27,658 -> -32,693 | -8,005 | +1,383 | 0.98 |
| ORB Fresh | B | 16 | +20 | 44 | +1 | 466 | -6,381 | 44 | -14 | -6,381 | -16,241 -> -15,996 | -20 | +357 | 0.54 |
| ORB Sweep | A | 26 | -8,030 | 31 | -309 | 323 | -16,016 | 33 | -50 | -16,016 | -31,716 -> -23,686 | +8,030 | +1,797 | 0.13 |
| ORB Sweep | B | 15 | -2,053 | 13 | -137 | 311 | -73,972 | 27 | -238 | -73,972 | -77,230 -> -76,090 | +2,054 | +2,754 | 0.59 |
| Range Fade | A | 23 | -7,865 | 30 | -342 | 345 | -39,292 | 36 | -114 | -39,292 | -49,248 -> -41,382 | +7,865 | +3,518 | 0.13 |
| Range Fade | B | 15 | -4,035 | 27 | -269 | 352 | -68,305 | 33 | -194 | -68,305 | -76,390 -> -72,355 | +4,035 | +2,670 | 0.30 |
| Liquidity 15+5 | A | 51 | +9,431 | 51 | +185 | 444 | +93,589 | 44 | +211 | +93,589 | -21,730 -> -20,723 | -9,430 | -3,588 | 0.76 |
| Liquidity 15+5 | B | 16 | +5,277 | 69 | +330 | 529 | +24,972 | 48 | +47 | +24,972 | -14,745 -> -16,172 | -5,278 | +528 | 0.98 |

Sensitivity (change in net vs no guard, A / B; thresholds [55, 65, 61.8]):

- ORB: 55: +1,215 / +26,154; 61.8: -9,290 / +4,425; 65: -5,365 / +5,385
- ORB Fresh: 55: -2,545 / +1,611; 61.8: -8,004 / -20; 65: -5,225 / +1,175
- ORB Sweep: 55: +22,230 / +18,859; 61.8: +8,030 / +2,053; 65: -375 / +248
- Range Fade: 55: +17,074 / +21,795; 61.8: +7,865 / +4,035; 65: +660 / +2,285
- Liquidity 15+5: 55: -20,598 / -6,541; 61.8: -9,431 / -5,277; 65: -5,287 / -306

### OR(09:15-10:00) < 0.7 x 20d avg

| arm | yr | S trades | S net | S win% | S avg | O trades | O net | O win% | O avg | guarded net | max DD (no guard -> guard) | change | random-skip change | p |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ORB | A | 426 | -41,654 | 41 | -98 | 2132 | -35,360 | 45 | -17 | -35,360 | -88,953 -> -43,610 | +41,654 | +13,959 | 0.03 |
| ORB | B | 348 | -21,850 | 38 | -63 | 1547 | -47,903 | 42 | -31 | -47,903 | -87,654 -> -65,038 | +21,850 | +11,178 | 0.18 |
| ORB Fresh | A | 119 | -13,068 | 42 | -110 | 434 | -3,770 | 47 | -9 | -3,770 | -27,658 -> -13,445 | +13,068 | +3,288 | 0.09 |
| ORB Fresh | B | 94 | -5,170 | 40 | -55 | 388 | -1,191 | 45 | -3 | -1,191 | -16,241 -> -13,911 | +5,170 | +878 | 0.23 |
| ORB Sweep | A | 70 | -9,006 | 37 | -129 | 279 | -15,040 | 32 | -54 | -15,040 | -31,716 -> -21,000 | +9,006 | +4,146 | 0.26 |
| ORB Sweep | B | 60 | -12,332 | 25 | -206 | 266 | -63,694 | 27 | -239 | -63,694 | -77,230 -> -67,788 | +12,332 | +13,037 | 0.55 |
| Range Fade | A | 71 | -7,769 | 39 | -109 | 297 | -39,388 | 35 | -133 | -39,388 | -49,248 -> -41,318 | +7,769 | +8,501 | 0.56 |
| Range Fade | B | 63 | -14,326 | 22 | -227 | 304 | -58,014 | 35 | -191 | -58,014 | -76,390 -> -61,958 | +14,326 | +12,167 | 0.34 |
| Liquidity 15+5 | A | 80 | +2,791 | 38 | +35 | 415 | +100,228 | 46 | +242 | +100,228 | -21,730 -> -29,097 | -2,791 | -8,363 | 0.36 |
| Liquidity 15+5 | B | 57 | -1,262 | 56 | -22 | 488 | +31,512 | 48 | +65 | +31,512 | -14,745 -> -13,885 | +1,262 | +2,349 | 0.59 |

Sensitivity (change in net vs no guard, A / B; thresholds [0.6, 0.8, 0.7]):

- ORB: 0.6: +25,683 / +26,540; 0.7: +41,654 / +21,850; 0.8: +45,308 / +30,472
- ORB Fresh: 0.6: +9,228 / +6,270; 0.7: +13,068 / +5,170; 0.8: +8,973 / +6,920
- ORB Sweep: 0.6: +3,332 / +3,626; 0.7: +9,006 / +12,332; 0.8: +12,840 / +16,866
- Range Fade: 0.6: +915 / +8,115; 0.7: +7,769 / +14,326; 0.8: +13,348 / +16,862
- Liquidity 15+5: 0.6: -10,133 / -704; 0.7: -2,791 / +1,262; 0.8: +5,304 / +5,511

## Intraday pause: arms skip new entries while the last 15-minute candle reads sideways

Re-run with the gate inside each arm (a blocked signal leaves the arm free for a later one). Random-skip baseline: removing the same number of trades at random from the unguarded run.

| detector | arm | yr | trades no guard -> paused | net no guard | net paused | change | random change | p | max DD no guard -> paused |
|---|---|---|---|---|---|---|---|---|---|
| ADX-pause (15m ADX14 < 20) | ORB | A | 2558 -> 1925 | -77,013 | -59,375 | +17,638 | +18,255 | 0.52 | -88,953 -> -73,185 |
| ADX-pause (15m ADX14 < 20) | ORB | B | 1895 -> 1471 | -69,754 | -40,677 | +29,077 | +15,825 | 0.15 | -87,654 -> -60,707 |
| ADX-pause (15m ADX14 < 20) | ORB Fresh | A | 553 -> 382 | -16,838 | -9,610 | +7,228 | +5,114 | 0.41 | -27,658 -> -17,985 |
| ADX-pause (15m ADX14 < 20) | ORB Fresh | B | 482 -> 326 | -6,361 | -10,130 | -3,769 | +1,822 | 0.78 | -16,241 -> -14,650 |
| ADX-pause (15m ADX14 < 20) | ORB Sweep | A | 349 -> 265 | -24,046 | -15,226 | +8,820 | +5,707 | 0.36 | -31,716 -> -27,926 |
| ADX-pause (15m ADX14 < 20) | ORB Sweep | B | 326 -> 241 | -76,025 | -64,699 | +11,326 | +19,806 | 0.86 | -77,230 -> -66,329 |
| ADX-pause (15m ADX14 < 20) | Range Fade | A | 368 -> 287 | -47,158 | -34,602 | +12,555 | +10,068 | 0.33 | -49,248 -> -37,432 |
| ADX-pause (15m ADX14 < 20) | Range Fade | B | 367 -> 273 | -72,340 | -57,870 | +14,470 | +18,721 | 0.75 | -76,390 -> -59,935 |
| ADX-pause (15m ADX14 < 20) | Liquidity 15+5 | A | 495 -> 307 | +103,019 | +52,096 | -50,924 | -40,297 | 0.68 | -21,730 -> -24,948 |
| ADX-pause (15m ADX14 < 20) | Liquidity 15+5 | B | 545 -> 358 | +30,250 | +8,972 | -21,278 | -10,181 | 0.81 | -14,745 -> -16,921 |
| CHOP-pause (15m CHOP14 > 61.8) | ORB | A | 2558 -> 2463 | -77,013 | -71,793 | +5,220 | +3,097 | 0.40 | -88,953 -> -87,518 |
| CHOP-pause (15m CHOP14 > 61.8) | ORB | B | 1895 -> 1832 | -69,754 | -72,035 | -2,282 | +2,386 | 0.80 | -87,654 -> -88,845 |
| CHOP-pause (15m CHOP14 > 61.8) | ORB Fresh | A | 553 -> 509 | -16,838 | -20,123 | -3,284 | +1,270 | 0.83 | -27,658 -> -27,428 |
| CHOP-pause (15m CHOP14 > 61.8) | ORB Fresh | B | 482 -> 441 | -6,361 | -10,406 | -4,045 | +641 | 0.85 | -16,241 -> -17,731 |
| CHOP-pause (15m CHOP14 > 61.8) | ORB Sweep | A | 349 -> 332 | -24,046 | -15,311 | +8,735 | +1,300 | 0.04 | -31,716 -> -24,401 |
| CHOP-pause (15m CHOP14 > 61.8) | ORB Sweep | B | 326 -> 307 | -76,025 | -76,682 | -658 | +4,392 | 0.89 | -77,230 -> -77,888 |
| CHOP-pause (15m CHOP14 > 61.8) | Range Fade | A | 368 -> 354 | -47,158 | -38,698 | +8,459 | +1,818 | 0.01 | -49,248 -> -40,684 |
| CHOP-pause (15m CHOP14 > 61.8) | Range Fade | B | 367 -> 346 | -72,340 | -72,475 | -135 | +4,245 | 0.91 | -76,390 -> -77,180 |
| CHOP-pause (15m CHOP14 > 61.8) | Liquidity 15+5 | A | 495 -> 433 | +103,019 | +84,211 | -18,808 | -12,482 | 0.67 | -21,730 -> -19,543 |
| CHOP-pause (15m CHOP14 > 61.8) | Liquidity 15+5 | B | 545 -> 472 | +30,250 | +28,154 | -2,096 | -4,288 | 0.41 | -14,745 -> -11,902 |

## Scorecard

A guard 'passes' for an arm only if, in BOTH years, it raises net P&L by more than skipping the same number of random days/trades would (p < 0.10) and does not deepen the max drawdown.

| arm | detector | A change (random) p | B change (random) p | passes |
|---|---|---|---|---|
| ORB | PDR < 0.7 (prior day range / 20d avg) | +29,865 (+21,406) 0.32 | +13,157 (+15,079) 0.57 | no |
| ORB | VIX pct < 25% (prior close, 60d) | -8,917 (+21,194) 0.96 | +22,187 (+29,105) 0.68 | no |
| ORB | ADX15m(14) < 20 at 10:00 | +28,420 (+20,525) 0.32 | +13,202 (+22,315) 0.74 | no |
| ORB | CHOP15m(14) > 61.8 at 10:00 | -9,289 (+6,021) 0.93 | +4,425 (+2,462) 0.39 | no |
| ORB | OR(09:15-10:00) < 0.7 x 20d avg | +41,654 (+13,959) 0.03 | +21,850 (+11,178) 0.18 | no |
| ORB | ADX-pause (15m ADX14 < 20) | +17,638 (+18,255) 0.52 | +29,077 (+15,825) 0.15 | no |
| ORB | CHOP-pause (15m CHOP14 > 61.8) | +5,220 (+3,097) 0.40 | -2,282 (+2,386) 0.80 | no |
| ORB Fresh | PDR < 0.7 (prior day range / 20d avg) | +2,330 (+4,714) 0.60 | +9,045 (+1,319) 0.13 | no |
| ORB Fresh | VIX pct < 25% (prior close, 60d) | +5,373 (+4,620) 0.47 | -3,184 (+2,505) 0.76 | no |
| ORB Fresh | ADX15m(14) < 20 at 10:00 | +6,525 (+4,267) 0.39 | -5,605 (+2,160) 0.85 | no |
| ORB Fresh | CHOP15m(14) > 61.8 at 10:00 | -8,005 (+1,383) 0.98 | -20 (+357) 0.54 | no |
| ORB Fresh | OR(09:15-10:00) < 0.7 x 20d avg | +13,068 (+3,288) 0.09 | +5,170 (+878) 0.23 | no |
| ORB Fresh | ADX-pause (15m ADX14 < 20) | +7,228 (+5,114) 0.41 | -3,769 (+1,822) 0.78 | no |
| ORB Fresh | CHOP-pause (15m CHOP14 > 61.8) | -3,284 (+1,270) 0.83 | -4,045 (+641) 0.85 | no |
| ORB Sweep | PDR < 0.7 (prior day range / 20d avg) | +13,835 (+6,801) 0.23 | +13,298 (+16,298) 0.65 | no |
| ORB Sweep | VIX pct < 25% (prior close, 60d) | +1,621 (+6,623) 0.70 | +43,112 (+31,582) 0.11 | no |
| ORB Sweep | ADX15m(14) < 20 at 10:00 | +10,552 (+6,473) 0.32 | +13,764 (+24,657) 0.88 | no |
| ORB Sweep | CHOP15m(14) > 61.8 at 10:00 | +8,030 (+1,797) 0.13 | +2,054 (+2,754) 0.59 | no |
| ORB Sweep | OR(09:15-10:00) < 0.7 x 20d avg | +9,006 (+4,146) 0.26 | +12,332 (+13,037) 0.55 | no |
| ORB Sweep | ADX-pause (15m ADX14 < 20) | +8,820 (+5,707) 0.36 | +11,326 (+19,806) 0.86 | no |
| ORB Sweep | CHOP-pause (15m CHOP14 > 61.8) | +8,735 (+1,300) 0.04 | -658 (+4,392) 0.89 | no |
| Range Fade | PDR < 0.7 (prior day range / 20d avg) | +17,304 (+13,047) 0.25 | +18,415 (+15,431) 0.32 | no |
| Range Fade | VIX pct < 25% (prior close, 60d) | +17,338 (+13,134) 0.24 | +41,815 (+29,996) 0.04 | no |
| Range Fade | ADX15m(14) < 20 at 10:00 | +12,915 (+12,448) 0.46 | +12,460 (+23,491) 0.95 | no |
| Range Fade | CHOP15m(14) > 61.8 at 10:00 | +7,865 (+3,518) 0.13 | +4,035 (+2,670) 0.30 | no |
| Range Fade | OR(09:15-10:00) < 0.7 x 20d avg | +7,769 (+8,501) 0.56 | +14,326 (+12,167) 0.34 | no |
| Range Fade | ADX-pause (15m ADX14 < 20) | +12,555 (+10,068) 0.33 | +14,470 (+18,721) 0.75 | no |
| Range Fade | CHOP-pause (15m CHOP14 > 61.8) | +8,459 (+1,818) 0.01 | -135 (+4,245) 0.91 | no |
| Liquidity 15+5 | PDR < 0.7 (prior day range / 20d avg) | -16,762 (-27,878) 0.34 | -33,716 (-6,867) 0.98 | no |
| Liquidity 15+5 | VIX pct < 25% (prior close, 60d) | -24,893 (-28,883) 0.46 | -16,207 (-12,085) 0.61 | no |
| Liquidity 15+5 | ADX15m(14) < 20 at 10:00 | -6,830 (-13,073) 0.37 | +6,299 (+4,750) 0.41 | no |
| Liquidity 15+5 | CHOP15m(14) > 61.8 at 10:00 | -9,430 (-3,588) 0.76 | -5,278 (+528) 0.98 | no |
| Liquidity 15+5 | OR(09:15-10:00) < 0.7 x 20d avg | -2,791 (-8,363) 0.36 | +1,262 (+2,349) 0.59 | no |
| Liquidity 15+5 | ADX-pause (15m ADX14 < 20) | -50,924 (-40,297) 0.68 | -21,278 (-10,181) 0.81 | no |
| Liquidity 15+5 | CHOP-pause (15m CHOP14 > 61.8) | -18,808 (-12,482) 0.67 | -2,096 (-4,288) 0.41 | no |
