## Expiry day: same-day option vs the next expiry (research/expiry_same_day.py)

BANKNIFTY, 11 monthly expiry days with same-day option prices (2025-02-27 .. 2026-01-27). Same signals and exits; only the option bought changes. 1 lot of 30, after costs.

| arm | trades | next expiry (today's rule): net, per trade | same-day expiry: net, per trade |
|---|---|---|---|
| Liquidity 15+5 (index + time stop) | 19 / 19 | Rs -514, -27 | Rs -2,275, -120 |
| ORB (profit lock) | 63 / 101 | Rs -4,365, -69 | Rs +1,945, +19 |
| ORB Fresh (profit lock) | 25 / 26 | Rs -1,375, -55 | Rs +3,070, +118 |
| ORB Sweep (profit lock) | 14 / 14 | Rs +4,030, +288 | Rs +5,647, +403 |
| Range Fade (profit lock) | 16 / 16 | Rs +3,020, +189 | Rs +3,320, +208 |
### Reading it

- Over 11 expiry days: next expiry (today's rule) +0.8k in all, same-day expiry +11.7k. The ORB family gains
  (ORB -4.4k -> +1.9k, ORB Fresh -1.4k -> +3.1k, Sweep +4.0k -> +5.6k, Range Fade +3.0k -> +3.3k); Liquidity 15+5 loses
  a little more (-0.5k -> -2.3k).
- Only 11 days (one a month): a direction, not a proof. Same-day options move faster both ways; late in the day an ATM
  premium can fall near the ORB's 40-point stop (the app refuses an entry at or below 40). The expiry square-off at
  15:05 would close same-day positions 5 minutes before the arms' 15:10.
