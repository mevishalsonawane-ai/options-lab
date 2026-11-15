# R177: does the volume-profile "trap" idea (Boss's PDF, Cases 1, 2, 5) help?

## Update: K1 skip + Rs 1,000 stop + Rs 500 step trail (Amendment A, Boss 15 Nov)

You asked to put the Rs 1,000 a lot stop and the step trail (+Rs 500 locked at +Rs 1,000, +Rs 1,000 at +Rs 1,500, ...)
on top of the K1 skip. Same year, same costs.

**It loses money at 1 lot and is far below today's rules at every lot size; it failed every gate.** K1 does help a
little on top of the stop rule, on all three arms.

Net over the year (today's rules / the stop rule alone / **K1 + the stop rule**):

| | 1 lot | 2 lots | 3 lots |
|---|---|---|---|
| Liquidity 15+5 | Rs 1.18 L / -Rs 3k / **Rs 16k** | Rs 2.67 L / Rs 27k / **Rs 61k** | Rs 4.16 L / Rs 55k / **Rs 1.05 L** |
| R72 | Rs 2.98 L / -Rs 31k / **-Rs 15k** | Rs 7.02 L / Rs 47k / **Rs 75k** | Rs 11.05 L / Rs 1.23 L / **Rs 1.62 L** |
| R51 | Rs 2.21 L / -Rs 61k / **-Rs 39k** | Rs 5.85 L / Rs 26k / **Rs 65k** | Rs 9.47 L / Rs 1.11 L / **Rs 1.67 L** |
| **All three** | **Rs 6.37 L / -Rs 95k / -Rs 38k** | **Rs 15.54 L / Rs 1.00 L / Rs 2.01 L** | **Rs 24.67 L / Rs 2.89 L / Rs 4.34 L** |

With the R151 picker (R72 + R51), all three arms: today Rs 7.83 L -> K1 + stop rule Rs 1.87 L at 1 lot (3 lots:
Rs 26.33 L -> Rs 8.36 L).

- **What K1 adds:** on top of the stop rule, skipping after a failed value-area break added about Rs 57k a year at 1 lot
  across the three arms (Rs 16k-22k each). Random skips of the same number of trades added nothing on average; K1 beat
  them in 90-95% of tries (p 0.046-0.096). Not strong enough with 4,000 tests counted (BH q = 1.0).
- **What still hurts:** the step trail. The stop rule cuts the winners short (as R176 found), and K1 cannot fix that.
- **Safer days, not more money:** worst day, all three at 1 lot, -Rs 54k today -> -Rs 31k; but max drawdown is larger
  (Rs 1.59 L -> Rs 2.46 L) and the worst month is still -Rs 1.15 L, far past the account stop rule.
- Honest note: this combination was chosen after seeing the earlier results on the same year, so it is weaker evidence.

**Bottom line for you:** of everything tested today, the one thing that beat today's rules is **K1 alone on Liquidity**
(no stop change). Adding the Rs 1,000 stop + step trail on top turns Rs 1.40 L into Rs 16k on Liquidity.

## The first R177 test (K1, X1, X2 alone)

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
