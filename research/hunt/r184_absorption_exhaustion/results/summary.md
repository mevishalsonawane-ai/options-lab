# R184 summary: absorption and exhaustion in options buying (16 Nov 2026)

Signal source: the NIFTY / BANKNIFTY near index future's own 1-minute price and tick-rule delta (the underlying); trade:
1 lot of the recorded ATM option; exits -15% premium, breakeven at +10%, index stop beyond the extreme, 15 / 30-minute
time stop. Design 15 Oct - 3 Nov (13 days, 32 versions per setup); holdout 4 - 13 Nov (7 days) read once.

| setup, index | chosen version | trades | win | net (2x slip) | a trade | random twins, a trade | twin p | BH q | verdict |
|---|---|---|---|---|---|---|---|---|---|
| Absorption NIFTY | ABS w3 k2 b6 turn T30 | 8 | 25% | Rs 2,153 (Rs 1,971) | Rs 269 | Rs 188 | 0.39 | 1.00 | too few trades |
| Absorption BANKNIFTY | ABS w5 k2 b6 turn T30 | 6 | 50% | Rs 2,257 (Rs 2,079) | Rs 376 | Rs -46 | 0.25 | 1.00 | too few trades |
| Exhaustion NIFTY | EXH X30 k3 b6 flip T30 | 14 | 21% | Rs -3,728 (Rs -4,055) | Rs -266 | Rs -282 | 0.45 | 1.00 | no evidence |
| Exhaustion BANKNIFTY | EXH X30 k2 b3 flip+close T30 | 22 | 23% | Rs -2,072 (Rs -2,765) | Rs -94 | Rs -294 | 0.24 | 1.00 | no evidence |

- Exhaustion lost on the locked days (36 trades, Rs -5,800) and was no better than random
  entries at the same minutes: **rejected**.
- Absorption made Rs 4,411 on 14 locked trades but did not beat random entries clearly (p
  0.39 / 0.25), had fewer than the 10 trades per index the bar
  needs, and rode calls in a rally: **inconclusive, too few trades**.
- All 8 tests BH q = 1.0 (m = 4,512). 20 days can only detect an edge of roughly Rs 850-1,950 a trade.
- Exhaustion runs against the breakout arms (never the same direction as an arm entry within 15 minutes).
- Nothing in the app changes. Re-check absorption with the same frozen rules when months more of recordings exist.

Full write-up: `results/report.md`.
