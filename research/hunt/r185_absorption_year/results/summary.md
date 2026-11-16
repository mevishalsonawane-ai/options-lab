# R185 summary: absorption and exhaustion on a full year (16 Nov 2026)

Signal: the index's own 1-minute price with the ATM-2..+2 options' volume as BVC context (OVC; no futures volume history, no second-level history at Dhan). Trade: 1 lot ATM option, R184's exits. Selection 20 Nov 2024 - 19 Oct 2025 (two halves must beat zero and the random twins), final 20 Oct 2025 - 19 Oct 2026 read once.

| setup, index | chosen version | passed selection | year trades | win | net (2x slip) | a trade | twins a trade | twin p | BH q | worst month | max DD | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Absorption NIFTY | ABS w3 k2 b6 turn T30 | no (fallback) | 451 | 26% | Rs -50,042 (Rs -57,176) | Rs -111 | Rs -105 | 0.544 | 1.00 | Rs -13,085 | Rs 51,770 | no evidence (nothing passed the selection) |
| Exhaustion NIFTY | EXH X30 k3 b6 flip+close T15 | no (fallback) | 888 | 38% | Rs -30,656 (Rs -45,644) | Rs -34 | Rs -90 | 0.022 | 1.00 | Rs -7,207 | Rs 36,009 | no evidence (nothing passed the selection) |

- Indices not read yet: BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX (same frozen rules; their tests count in m = 4,852 either way).
- Nothing in the app changes. Full write-up: `results/report.md`.
