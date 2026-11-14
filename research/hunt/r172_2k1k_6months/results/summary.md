# R172: the last 6 months with the new +Rs 2,000 / -Rs 1,000 rule

**Boss, in short (14 Nov 2026).** I replayed **20 Apr - 19 Oct 2026** (126 trading days; the data ends on 19 Oct) on
Liquidity 15+5, R72 Breadth Donchian and R51 Donchian 20 + Supertrend. I ran each at 1, 2 and 3 lots, with and without the
new rule, and with and without the R151 picker. All money is after real Zerodha charges and slippage.

**The new rule would have lost money on every arm, at every lot size, with or without the picker.** Without it, all three
arms made money.

Net over the 6 months (today's rules -> with the new rule):

| | 1 lot | 2 lots | 3 lots |
|---|---|---|---|
| Liquidity 15+5 | Rs 28k -> **-Rs 38k** | Rs 73k -> **-Rs 62k** | Rs 1.16 L -> **-Rs 1.12 L** |
| R72 Breadth Donchian | Rs 1.90 L -> **-Rs 16k** | Rs 4.32 L -> **-Rs 1.99 L** | Rs 6.73 L -> **-Rs 3.98 L** |
| R51 Donchian 20 | Rs 1.48 L -> **-Rs 51k** | Rs 3.70 L -> **-Rs 2.34 L** | Rs 5.91 L -> **-Rs 4.10 L** |
| **All three** | **Rs 3.67 L -> -Rs 1.05 L** | **Rs 8.74 L -> -Rs 4.95 L** | **Rs 13.80 L -> -Rs 9.20 L** |

With the R151 picker on R72 and R51, as live today (Liquidity has no picker):

| | 1 lot | 2 lots | 3 lots |
|---|---|---|---|
| R72 + picker | Rs 1.82 L -> **Rs 25k** | Rs 3.88 L -> **-Rs 95k** | Rs 5.94 L -> **-Rs 1.63 L** |
| R51 + picker | Rs 1.48 L -> **-Rs 3k** | Rs 3.23 L -> **-Rs 1.04 L** | Rs 4.98 L -> **-Rs 1.59 L** |
| **All three** | **Rs 3.58 L -> -Rs 15k** | **Rs 7.84 L -> -Rs 2.61 L** | **Rs 12.09 L -> -Rs 4.34 L** |

**Why it loses.**
- The Rs 1,000 stop is nearer than the arm's -15% stop on 95-100% of trades. Its premium distance is half the Rs 2,000
  target's, so it is hit about twice as often: at 3 lots, 2,083 stops against 1,071 targets, all arms together.
- One target hit nets about Rs 1,800-1,900. One stop hit costs about Rs 1,150-1,390, because a stop sells below its price
  (slippage) and the charges come on top.
- The win rate falls from 45-46% to 34-40%, below the 42-43% this payoff needs to break even.
- It also caps the big winners the Donchian arms live on. At 3 lots the average winning trade drops from about
  Rs 5,000 to Rs 1,800.
- More lots make it worse: Rs 1,000 for 3 lots is only a Rs 333 move a lot, inside normal noise.

**What it does do.**
- **Smaller single bad days.** Worst day for all three arms at 1 lot: -Rs 54k -> -Rs 31k. At 3 lots: -Rs 1.59 L ->
  -Rs 42k.
- **No big single-trade losses**, except gaps. In thin FINNIFTY / MIDCPNIFTY books, a price can jump past the stop. The
  worst such trade was -Rs 11,250 at 3 lots (FINNIFTY, 3 Sep).
- **But the losses keep adding up, so the drawdown is larger.**
  - All three arms at 1 lot: Rs 1.39 L -> Rs 1.71 L.
  - At 3 lots: Rs 3.30 L -> Rs 9.48 L. That is far past the account stop rule of -Rs 40k drawdown / -Rs 13k a month.
- Red months with the new rule, all three arms together (7 months, Apr and Oct part months):
  - 1 lot: 5 red (4 with the picker);
  - 2 lots: all 7 red (5 with the picker);
  - 3 lots: all 7 red, with or without the picker.
  - Today's rules at the same lots: only June red without the picker; May and June red with it.

**Honest notes.**
- This is a report of the rule you chose, not a gate test, so no pass / fail or multiple-testing step.
- These months overlap the period that many earlier studies have read. R106 and R152 already found that rupee targets and
  stops hurt these arms there.
- It agrees with them.
- Nothing in the app changes from this report.
