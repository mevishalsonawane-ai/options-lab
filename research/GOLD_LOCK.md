## A profit lock for IraGoldAlgo (research/gold_lock.py)

XAUUSD 1-hour liquidity, London + New York, buys only as the app trades it. Dukascopy 2023-10-01 .. 2026-09-25, ask = bid + 0.30, $7 a lot. USD per standard lot (100 oz) after costs.

| exit | Oct 2023 - Sep 2024 | Oct 2024 - Sep 2025 | Oct 2025 - Sep 2026 | all | trades | win | worst trade | locks |
|---|---|---|---|---|---|---|---|---|
| no lock (the app today) | +427 | +19,920 | +1,045 | +21,393 | 88 | 59% | -6,277 | 0 |
| ladder to the next liquidity | +771 | +15,423 | -1,981 | +14,213 | 88 | 42% | -6,277 | 36 |
| breakeven at +$3 | -936 | -163 | -4,760 | -5,859 | 88 | 32% | -2,749 | 31 |
| breakeven at +$5 | -881 | +3,874 | -3,350 | -357 | 88 | 38% | -2,749 | 24 |
| breakeven at +$10 | +427 | +10,248 | -2,397 | +8,278 | 88 | 51% | -6,277 | 10 |
| step lock every +$5 | -345 | +3,732 | -760 | +2,626 | 88 | 65% | -2,749 | 30 |
| step lock every +$10 | +427 | +12,014 | -4,327 | +8,114 | 88 | 62% | -6,277 | 16 |