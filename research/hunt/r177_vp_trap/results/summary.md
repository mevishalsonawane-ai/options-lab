# R177: does the volume-profile "trap" idea (Boss's PDF, Cases 1, 2, 5) help?

**Boss, in short (15 Nov 2026).** I tested the parts of your PDF that our data can measure, on the last year
(**20 Oct 2025 - 19 Oct 2026**, 247 trading days), on Liquidity 15+5, R72 and R51. Money is after real charges and
slippage. Yesterday's value area (VAH / VAL / POC) comes from the index's time-at-price profile, because the data has no
index volume; the footprint / delta / CVD steps need tick data we do not have for a year, so they are not tested.

Three rules, each only skips a trade or exits earlier:
- **X1 VA trap exit**: bought above yesterday's VAH (puts: below VAL), and the index closes back inside within 10 minutes
  -> sell at the next minute.
- **X2 day-range trap exit**: the same with yesterday's high / low (Case 5).
- **K1 skip after a failed break**: in the 30 minutes before the signal, the index closed above VAH and then back below
  it (puts: VAL) -> don't buy calls (puts).

**No rule passed the gates.** One is worth watching: **K1 on Liquidity**.

Net at 1 lot over the year (today's rules -> with the rule):

| | X1 trap exit | X2 day-range exit | **K1 skip** |
|---|---|---|---|
| Liquidity 15+5 (today Rs 1.18 L) | Rs 1.21 L | Rs 1.17 L | **Rs 1.40 L** |
| R72 Breadth Donchian (Rs 2.98 L) | Rs 2.36 L | Rs 3.11 L | Rs 2.98 L |
| R51 Donchian 20 (Rs 2.21 L) | Rs 1.48 L | Rs 2.02 L | Rs 2.12 L |
| All three (Rs 6.37 L) | Rs 5.05 L | Rs 6.30 L | Rs 6.50 L |

**K1 on Liquidity, the one lead:**
- It skipped 96 of 688 trades and made more in every period: early part Rs 1.14 L -> Rs 1.28 L, final Rs 11k -> Rs 17k,
  last 6 months Rs 28k -> Rs 34k, the year Rs 1.18 L -> Rs 1.40 L (3 lots: Rs 4.16 L -> Rs 4.72 L).
- Win rate 39% -> 40%; max drawdown Rs 61k -> Rs 57k.
- Random skips of 96 trades made Rs 1.01 L on average; only 1.4% of 500 random tries beat K1 (p = 0.014).
- **Why it still fails:** with every test this project has ever run counted (3,997), p = 0.014 is far from enough (BH
  q = 1.0); even counting only R177's own 9 tests, q = 0.13 (the bar is 0.10). And the account stop rule fails, as it
  does for today's rules (worst month -Rs 37k at 1 lot).
- Honest reading: a promising hint from 96 skipped trades in one year, not proof. The right next step is a forward test
  on new days (logged only, on paper), not switching it on.

**The rest:**
- X1 hurt R72 and R51 (all three arms Rs 6.37 L -> Rs 5.05 L; Liquidity +Rs 3k): selling when price slips back
  inside value cuts too many trades that recover.
- X2 helped R72 a little in the final period (Rs 1.70 L -> Rs 1.84 L) but hurt it in the early part; overall a wash.
- K1 did nothing for R72 and cost R51 money.
- With the R151 picker on R72 / R51, none of the rules added profit over the picker alone (all three: Rs 7.83 L ->
  X1 Rs 7.04 L, X2 Rs 7.69 L, K1 Rs 7.75 L).

This matches earlier studies (R9, R23, R62, R150): volume-profile levels on their own do not separate good from bad
trades for the Donchian arms. Nothing in the app changes from R177.
