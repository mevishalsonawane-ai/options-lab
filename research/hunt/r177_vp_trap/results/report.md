# R177: does the volume-profile "trap" idea (Boss's PDF, Cases 1, 2, 5) help?

## Update: Rs 1,500 initial stop, up Rs 500 for every Rs 1,000 gain (Amendment C, Boss 15 Nov)

The rule: stop at -Rs 1,500 a lot at entry (where tighter than -15%); for every Rs 1,000 a lot of best profit the stop
rises Rs 500 a lot: -Rs 1,000 at +Rs 1,000, -Rs 500 at +Rs 2,000, cost at +Rs 3,000, +Rs 500 at +Rs 4,000, ...

**By far the best stop rule tested today, but still below today's rules; it failed the gates.**

Net over the year (today's rules / Rs 1,000 gap rule from Amendment B / **this rule**):

| | 1 lot | 2 lots | 3 lots |
|---|---|---|---|
| Liquidity | Rs 1.18 L / Rs 9k / **Rs 78k** | Rs 2.67 L / Rs 52k / **Rs 1.86 L** | Rs 4.16 L / Rs 92k / **Rs 2.94 L** |
| R72 | Rs 2.98 L / Rs 32k / **Rs 1.79 L** | Rs 7.02 L / Rs 1.72 L / **Rs 4.63 L** | Rs 11.05 L / Rs 3.11 L / **Rs 7.46 L** |
| R51 | Rs 2.21 L / -Rs 8k / **Rs 1.00 L** | Rs 5.85 L / Rs 1.28 L / **Rs 3.43 L** | Rs 9.47 L / Rs 2.63 L / **Rs 5.83 L** |
| **All three** | **Rs 6.37 L / Rs 33k / Rs 3.57 L** | **Rs 15.54 L / Rs 3.52 L / Rs 9.92 L** | **Rs 24.67 L / Rs 6.67 L / Rs 16.23 L** |

- **With K1 on top:** Liquidity improves (Rs 78k -> Rs 1.04 L at 1 lot; K1 beat random skips, p 0.02), but R72 and R51
  get slightly worse; all three together the same (Rs 3.56 L).
- **With the R151 picker** (R72 + R51), all three at 1 lot: today Rs 7.83 L -> this rule Rs 4.60 L (with K1 Rs 4.70 L).
- **Liquidity in the most recent months:** this rule beat today's rules in the final period (Rs 11k -> Rs 17k; with K1
  Rs 27k) and in the last 6 months (Rs 28k -> Rs 29k; with K1 Rs 37k), but lost much more in the early part (Rs 1.14 L ->
  Rs 67k), so over the year it is behind.
- **Why it is closer:** the wide first stop rarely triggers (only 20% of trades end on it; about 1 in 5 trades keep the
  -15% stop because it is tighter), and the slow trail leaves room for big winners: average win Rs 1,721 at 1 lot vs
  Rs 1,744 today. But the win rate is 41% vs 45% today: the trail still closes some trades that today's rules let recover.
- **Risk:** worst day, all three at 1 lot, -Rs 54k today -> -Rs 42k. Max drawdown Rs 1.59 L -> Rs 1.93 L; worst month
  -Rs 1.29 L -> -Rs 99k. Still far past the account stop rule (as today's rules are).
- Gates: net below today's rules in the early part, final and year on every arm; random-exit twins p = 1.0; BH q = 1.0
  with 4,012 tests.

**Summary of every rule tested today (all three arms, 1 lot, the year):** today's rules Rs 6.37 L; Rs 500 stop + Rs 500
gap -Rs 3.23 L; Rs 1,000 stop + Rs 500 gap -Rs 95k; Rs 1,000 stop + Rs 1,000 gap Rs 33k; **Rs 1,500 stop + Rs 500 per
Rs 1,000 Rs 3.57 L**. The wider and slower the stop, the closer to today's rules; none beat them.

## Earlier update: a Rs 1,000 gap under each step (Amendment B)

You asked to test a Rs 1,000 gap. Same Rs 1,000 a lot entry stop and the same steps (every Rs 500 a lot from +Rs 1,000),
but the stop sits Rs 1,000 a lot under each step: **cost at +Rs 1,000, +Rs 500 at +Rs 1,500, +Rs 1,000 at +Rs 2,000**.

**Better than the Rs 500 gap at every lot size, but still far below today's rules; it failed every gate.**

Net over the year, all three arms together (today's rules / Rs 500 gap / **Rs 1,000 gap**):

| | 1 lot | 2 lots | 3 lots |
|---|---|---|---|
| stop rule alone | Rs 6.37 L / -Rs 95k / **Rs 33k** | Rs 15.54 L / Rs 1.00 L / **Rs 3.52 L** | Rs 24.67 L / Rs 2.89 L / **Rs 6.67 L** |
| with K1 skip | Rs 6.37 L / -Rs 38k / **Rs 81k** | Rs 15.54 L / Rs 2.01 L / **Rs 4.35 L** | Rs 24.67 L / Rs 4.34 L / **Rs 7.84 L** |

Per arm, 1 lot (today's rules / **Rs 1,000 gap** / **Rs 1,000 gap + K1**):
- Liquidity: Rs 1.18 L / Rs 9k / Rs 32k
- R72: Rs 2.98 L / Rs 32k / Rs 45k
- R51: Rs 2.21 L / -Rs 8k / Rs 5k

With the R151 picker (R72 + R51), all three at 1 lot: today Rs 7.83 L -> Rs 1,000 gap Rs 2.54 L, with K1 Rs 2.93 L.

- Winners grow (average win Rs 1,376 at 1 lot vs Rs 1,026 with the Rs 500 gap), but the win rate falls to 38% (48%
  with the Rs 500 gap): the stop at cost catches many trades that touched +Rs 1,000 and came back. Today's rules: average
  win Rs 1,744, win rate 45%.
- The wider gap is still the main cost compared with today's rules: today's ladder lets the big trades run much further.
- Worst day, all three at 1 lot: -Rs 54k today -> -Rs 34k (-Rs 30k with K1). Max drawdown Rs 1.59 L -> Rs 2.29 L
  (Rs 2.39 L with K1). Worst month -Rs 1.46 L (-Rs 1.26 L with K1).
- Twins: the Rs 1,000 gap alone lost to random exits (p = 1.0); K1 on top beat random skips on Liquidity (p 0.028), not
  on R72 / R51 (p 0.18-0.19). BH q = 1.0 with 4,006 tests.

**The pattern across today's tests:** every step trail we tried (Rs 500 or Rs 1,000 gap, with or without K1) earns much
less than today's exits, because it sells the big winners early. The wider the gap, the less it hurts. The only rule that
beat today's rules was **K1 alone on Liquidity** (Rs 1.18 L -> Rs 1.40 L), and even that is not proven.

## Earlier update: K1 + Rs 1,000 stop + Rs 500 gap (Amendment A)

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

## Data, checks and method

- Parity, today's rules at 1 lot, 1 Apr - 13 Oct 2026: Liquidity 15+5 357 / Rs 11,155 (R172 357 / Rs 11,155); R72 Breadth Donchian 1200 / Rs 170,336 (R172 1200 / Rs 170,336); R51 Donchian 20 + Supertrend 1668 / Rs 131,546 (R172 1668 / Rs 131,546); the engine with no rule priced every candidate exactly as R101.
- Signal source: the index for every arm and every filter (yesterday's TPO profile and today's 1-minute closes of the traded index). Costs: dated Zerodha charges, R17 slippage, stops sold as stops.
- How often each rule acted (1 lot): LIQ:X1 38 candidates (38 trades exited by the trap); LIQ:X2 90 candidates (90 trades exited by the trap); LIQ:K1 96 candidates; R72:X1 141 candidates (89 trades exited by the trap); R72:X2 163 candidates (105 trades exited by the trap); R72:K1 305 candidates; R51:X1 298 candidates (164 trades exited by the trap); R51:X2 364 candidates (173 trades exited by the trap); R51:K1 701 candidates.
- Honesty: Apr - Oct 2026 has been read by many studies; one year is a short sample; the pickers were fitted on trades up to Mar 2026 (picker numbers in the early part are in-sample). Variants: A today's rules; B = A + R151 picker (R72 / R51; Liquidity has none); B+X = picker + rule.

## Gates (1 lot; BH m = 3,997)

| arm : rule | passed | passes except the account-stop gate | twin p | BH q | q within R177 | failed gates |
|---|---|---|---|---|---|---|
| LIQ:X1 | **no** | no | 1.0 | 1.0 | 1.0 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in final, six, year |
| R72:X1 | **no** | no | 1.0 | 1.0 | 1.0 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R51:X1 | **no** | no | 1.0 | 1.0 | 1.0 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| LIQ:X2 | **no** | no | 1.0 | 1.0 | 1.0 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in final, year |
| R72:X2 | **no** | no | 1.0 | 1.0 | 1.0 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R51:X2 | **no** | no | 1.0 | 1.0 | 1.0 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| LIQ:K1 | **no** | no | 0.014 | 1.0 | 0.126 | exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in final, six, year |
| R72:K1 | **no** | no | 0.2395 | 1.0 | 1.0 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R51:K1 | **no** | no | 0.5469 | 1.0 | 1.0 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |

Twins: LIQ:X1 p 1.0 (twin mean Rs 130,656, 20 changed), LIQ:X2 p 1.0 (twin mean Rs 152,046, 64 changed), LIQ:K1 p 0.014 (twin mean Rs 101,067, 96 changed), R72:X1 p 1.0 (twin mean Rs 271,287, 75 changed), R72:X2 p 1.0 (twin mean Rs 344,489, 87 changed), R72:K1 p 0.2395 (twin mean Rs 283,337, 96 changed), R51:X1 p 1.0 (twin mean Rs 217,474, 148 changed), R51:X2 p 1.0 (twin mean Rs 252,903, 153 changed), R51:K1 p 0.5469 (twin mean Rs 213,607, 98 changed).

## Tables

### whole year (20 Oct 2025 - 19 Oct 2026), 1 lot (247 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 688 | 39% | **Rs 117,923** | Rs 1,935 / -Rs 968 | -Rs 11,405 | Apr 26 -Rs 37,821 | -Rs 61,062 |
| Liquidity 15+5 | X1 | 688 | 39% | **Rs 121,016** | Rs 1,949 / -Rs 955 | -Rs 11,405 | Apr 26 -Rs 39,269 | -Rs 62,510 |
| Liquidity 15+5 | X2 | 688 | 38% | **Rs 117,363** | Rs 1,917 / -Rs 904 | -Rs 11,405 | Apr 26 -Rs 34,879 | -Rs 57,518 |
| Liquidity 15+5 | K1 | 592 | 40% | **Rs 139,609** | Rs 2,060 / -Rs 982 | -Rs 11,405 | Apr 26 -Rs 36,925 | -Rs 57,084 |
| R72 Breadth Donchian | A | 2281 | 46% | **Rs 298,253** | Rs 1,763 / -Rs 1,279 | -Rs 25,837 | Dec 25 -Rs 72,605 | -Rs 86,529 |
| R72 Breadth Donchian | X1 | 2282 | 45% | **Rs 235,783** | Rs 1,754 / -Rs 1,252 | -Rs 26,041 | Dec 25 -Rs 74,043 | -Rs 87,564 |
| R72 Breadth Donchian | X2 | 2283 | 45% | **Rs 311,197** | Rs 1,782 / -Rs 1,221 | -Rs 25,837 | Dec 25 -Rs 62,561 | -Rs 76,012 |
| R72 Breadth Donchian | K1 | 2185 | 47% | **Rs 298,382** | Rs 1,763 / -Rs 1,280 | -Rs 25,837 | Dec 25 -Rs 66,819 | -Rs 74,212 |
| R72 Breadth Donchian | B | 1062 | 52% | **Rs 371,717** | Rs 2,019 / -Rs 1,449 | -Rs 25,837 | May 26 -Rs 20,632 | -Rs 34,940 |
| R72 Breadth Donchian | B+X1 | 1062 | 50% | **Rs 338,699** | Rs 2,031 / -Rs 1,413 | -Rs 26,041 | May 26 -Rs 29,078 | -Rs 42,433 |
| R72 Breadth Donchian | B+X2 | 1060 | 50% | **Rs 367,976** | Rs 2,055 / -Rs 1,387 | -Rs 25,837 | May 26 -Rs 15,876 | -Rs 38,080 |
| R72 Breadth Donchian | B+K1 | 1026 | 52% | **Rs 349,482** | Rs 1,977 / -Rs 1,450 | -Rs 25,837 | May 26 -Rs 20,652 | -Rs 40,844 |
| R51 Donchian 20 + Supertrend | A | 3084 | 45% | **Rs 221,238** | Rs 1,692 / -Rs 1,259 | -Rs 28,942 | Jun 26 -Rs 54,587 | -Rs 94,176 |
| R51 Donchian 20 + Supertrend | X1 | 3089 | 44% | **Rs 148,166** | Rs 1,683 / -Rs 1,212 | -Rs 29,338 | Dec 25 -Rs 56,460 | -Rs 96,187 |
| R51 Donchian 20 + Supertrend | X2 | 3089 | 43% | **Rs 201,541** | Rs 1,716 / -Rs 1,203 | -Rs 28,942 | Jun 26 -Rs 54,782 | -Rs 94,729 |
| R51 Donchian 20 + Supertrend | K1 | 2986 | 45% | **Rs 212,002** | Rs 1,682 / -Rs 1,243 | -Rs 28,942 | Jun 26 -Rs 57,727 | -Rs 104,329 |
| R51 Donchian 20 + Supertrend | B | 1361 | 51% | **Rs 293,395** | Rs 1,814 / -Rs 1,438 | -Rs 24,757 | Jun 26 -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+X1 | 1368 | 49% | **Rs 244,486** | Rs 1,801 / -Rs 1,370 | -Rs 25,951 | Jun 26 -Rs 24,327 | -Rs 47,790 |
| R51 Donchian 20 + Supertrend | B+X2 | 1364 | 48% | **Rs 283,584** | Rs 1,855 / -Rs 1,341 | -Rs 20,030 | Jun 26 -Rs 22,632 | -Rs 41,152 |
| R51 Donchian 20 + Supertrend | B+K1 | 1278 | 51% | **Rs 285,612** | Rs 1,814 / -Rs 1,423 | -Rs 20,030 | Jun 26 -Rs 23,476 | -Rs 32,491 |
| All three | A | 6053 | 45% | **Rs 637,414** | Rs 1,744 / -Rs 1,230 | -Rs 53,710 | Dec 25 -Rs 129,283 | -Rs 159,077 |
| All three | X1 | 6059 | 44% | **Rs 504,965** | Rs 1,738 / -Rs 1,195 | -Rs 54,113 | Dec 25 -Rs 135,672 | -Rs 162,805 |
| All three | X2 | 6060 | 44% | **Rs 630,101** | Rs 1,762 / -Rs 1,172 | -Rs 53,710 | Dec 25 -Rs 101,210 | -Rs 146,925 |
| All three | K1 | 5763 | 45% | **Rs 649,993** | Rs 1,749 / -Rs 1,227 | -Rs 53,710 | Jun 26 -Rs 108,695 | -Rs 126,179 |
| All three | B | 3111 | 49% | **Rs 783,034** | Rs 1,910 / -Rs 1,318 | -Rs 43,996 | Apr 26 -Rs 29,430 | -Rs 76,847 |
| All three | B+X1 | 3118 | 47% | **Rs 704,202** | Rs 1,912 / -Rs 1,278 | -Rs 46,459 | Apr 26 -Rs 40,335 | -Rs 106,539 |
| All three | B+X2 | 3112 | 47% | **Rs 768,924** | Rs 1,940 / -Rs 1,243 | -Rs 39,870 | Jun 26 -Rs 18,979 | -Rs 73,987 |
| All three | B+K1 | 2896 | 49% | **Rs 774,704** | Rs 1,916 / -Rs 1,325 | -Rs 39,870 | Apr 26 -Rs 38,628 | -Rs 80,396 |

### early (20 Oct 2025 - 31 Mar 2026), 1 lot (110 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 316 | 42% | **Rs 113,873** | Rs 2,142 / -Rs 935 | -Rs 5,535 | Dec 25 -Rs 4,980 | -Rs 16,913 |
| Liquidity 15+5 | X1 | 316 | 42% | **Rs 113,848** | Rs 2,142 / -Rs 935 | -Rs 5,535 | Dec 25 -Rs 5,169 | -Rs 17,102 |
| Liquidity 15+5 | X2 | 316 | 41% | **Rs 103,562** | Rs 2,110 / -Rs 886 | -Rs 5,364 | Dec 25 -Rs 1,046 | -Rs 15,345 |
| Liquidity 15+5 | K1 | 278 | 44% | **Rs 128,305** | Rs 2,279 / -Rs 960 | -Rs 5,218 | Oct 25 -Rs 995 | -Rs 12,978 |
| R72 Breadth Donchian | A | 1056 | 47% | **Rs 128,541** | Rs 1,792 / -Rs 1,341 | -Rs 25,837 | Dec 25 -Rs 72,605 | -Rs 86,529 |
| R72 Breadth Donchian | X1 | 1056 | 46% | **Rs 122,044** | Rs 1,792 / -Rs 1,308 | -Rs 26,041 | Dec 25 -Rs 74,043 | -Rs 87,564 |
| R72 Breadth Donchian | X2 | 1056 | 45% | **Rs 124,203** | Rs 1,823 / -Rs 1,272 | -Rs 25,837 | Dec 25 -Rs 62,561 | -Rs 76,012 |
| R72 Breadth Donchian | K1 | 1011 | 47% | **Rs 128,168** | Rs 1,768 / -Rs 1,351 | -Rs 25,837 | Dec 25 -Rs 66,819 | -Rs 74,212 |
| R72 Breadth Donchian | B | 452 | 55% | **Rs 200,162** | Rs 2,153 / -Rs 1,673 | -Rs 25,837 | Dec 25 -Rs 1,721 | -Rs 34,940 |
| R72 Breadth Donchian | B+X1 | 452 | 54% | **Rs 190,078** | Rs 2,146 / -Rs 1,622 | -Rs 26,041 | Dec 25 -Rs 5,610 | -Rs 32,690 |
| R72 Breadth Donchian | B+X2 | 450 | 52% | **Rs 187,310** | Rs 2,234 / -Rs 1,588 | -Rs 25,837 | Dec 25 -Rs 2,384 | -Rs 38,080 |
| R72 Breadth Donchian | B+K1 | 441 | 56% | **Rs 177,403** | Rs 2,069 / -Rs 1,681 | -Rs 25,837 | Dec 25 Rs 4,736 | -Rs 40,844 |
| R51 Donchian 20 + Supertrend | A | 1370 | 45% | **Rs 86,280** | Rs 1,744 / -Rs 1,326 | -Rs 28,942 | Dec 25 -Rs 51,699 | -Rs 94,176 |
| R51 Donchian 20 + Supertrend | X1 | 1372 | 44% | **Rs 65,092** | Rs 1,755 / -Rs 1,279 | -Rs 29,338 | Dec 25 -Rs 56,460 | -Rs 93,876 |
| R51 Donchian 20 + Supertrend | X2 | 1372 | 43% | **Rs 76,681** | Rs 1,790 / -Rs 1,241 | -Rs 28,942 | Dec 25 -Rs 37,603 | -Rs 94,729 |
| R51 Donchian 20 + Supertrend | K1 | 1325 | 45% | **Rs 90,384** | Rs 1,757 / -Rs 1,300 | -Rs 28,942 | Feb 26 -Rs 49,011 | -Rs 104,329 |
| R51 Donchian 20 + Supertrend | B | 691 | 51% | **Rs 156,237** | Rs 1,905 / -Rs 1,537 | -Rs 24,757 | Dec 25 -Rs 904 | -Rs 29,931 |
| R51 Donchian 20 + Supertrend | B+X1 | 694 | 50% | **Rs 149,472** | Rs 1,925 / -Rs 1,475 | -Rs 25,951 | Dec 25 -Rs 5,964 | -Rs 33,588 |
| R51 Donchian 20 + Supertrend | B+X2 | 692 | 48% | **Rs 155,649** | Rs 1,987 / -Rs 1,391 | -Rs 20,030 | Oct 25 -Rs 1,194 | -Rs 30,669 |
| R51 Donchian 20 + Supertrend | B+K1 | 648 | 50% | **Rs 134,069** | Rs 1,900 / -Rs 1,518 | -Rs 20,030 | Oct 25 Rs 4,330 | -Rs 31,023 |
| All three | A | 2742 | 45% | **Rs 328,694** | Rs 1,805 / -Rs 1,284 | -Rs 51,650 | Dec 25 -Rs 129,283 | -Rs 159,077 |
| All three | X1 | 2744 | 44% | **Rs 300,984** | Rs 1,812 / -Rs 1,249 | -Rs 54,113 | Dec 25 -Rs 135,672 | -Rs 162,805 |
| All three | X2 | 2744 | 43% | **Rs 304,445** | Rs 1,838 / -Rs 1,209 | -Rs 42,827 | Dec 25 -Rs 101,210 | -Rs 130,561 |
| All three | K1 | 2614 | 46% | **Rs 346,858** | Rs 1,815 / -Rs 1,282 | -Rs 40,376 | Dec 25 -Rs 100,295 | -Rs 120,652 |
| All three | B | 1459 | 51% | **Rs 470,271** | Rs 2,032 / -Rs 1,423 | -Rs 43,996 | Dec 25 -Rs 7,604 | -Rs 60,566 |
| All three | B+X1 | 1462 | 49% | **Rs 453,398** | Rs 2,040 / -Rs 1,382 | -Rs 46,459 | Dec 25 -Rs 16,743 | -Rs 60,104 |
| All three | B+X2 | 1458 | 48% | **Rs 446,521** | Rs 2,094 / -Rs 1,322 | -Rs 35,172 | Dec 25 Rs 6,717 | -Rs 62,276 |
| All three | B+K1 | 1367 | 51% | **Rs 439,777** | Rs 2,027 / -Rs 1,436 | -Rs 34,987 | Dec 25 Rs 14,134 | -Rs 68,141 |

### final (1 Apr - 13 Oct 2026), 1 lot (133 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 357 | 37% | **Rs 11,155** | Rs 1,762 / -Rs 997 | -Rs 11,405 | Apr 26 -Rs 37,821 | -Rs 61,062 |
| Liquidity 15+5 | X1 | 357 | 37% | **Rs 13,573** | Rs 1,788 / -Rs 976 | -Rs 11,405 | Apr 26 -Rs 39,269 | -Rs 62,510 |
| Liquidity 15+5 | X2 | 357 | 36% | **Rs 20,443** | Rs 1,762 / -Rs 919 | -Rs 11,405 | Apr 26 -Rs 34,879 | -Rs 57,518 |
| Liquidity 15+5 | K1 | 305 | 38% | **Rs 16,826** | Rs 1,828 / -Rs 1,018 | -Rs 11,405 | Apr 26 -Rs 36,925 | -Rs 57,084 |
| R72 Breadth Donchian | A | 1200 | 46% | **Rs 170,336** | Rs 1,748 / -Rs 1,217 | -Rs 22,571 | Jun 26 -Rs 40,908 | -Rs 61,884 |
| R72 Breadth Donchian | X1 | 1201 | 44% | **Rs 117,103** | Rs 1,731 / -Rs 1,197 | -Rs 22,571 | Jun 26 -Rs 41,029 | -Rs 69,937 |
| R72 Breadth Donchian | X2 | 1202 | 45% | **Rs 184,066** | Rs 1,758 / -Rs 1,174 | -Rs 20,728 | Jun 26 -Rs 41,628 | -Rs 61,993 |
| R72 Breadth Donchian | K1 | 1153 | 46% | **Rs 175,703** | Rs 1,771 / -Rs 1,211 | -Rs 20,728 | Jun 26 -Rs 43,876 | -Rs 58,616 |
| R72 Breadth Donchian | B | 602 | 49% | **Rs 170,735** | Rs 1,920 / -Rs 1,299 | -Rs 21,301 | May 26 -Rs 20,632 | -Rs 31,620 |
| R72 Breadth Donchian | B+X1 | 602 | 47% | **Rs 147,802** | Rs 1,947 / -Rs 1,274 | -Rs 21,301 | May 26 -Rs 29,078 | -Rs 38,717 |
| R72 Breadth Donchian | B+X2 | 602 | 49% | **Rs 179,846** | Rs 1,926 / -Rs 1,244 | -Rs 21,301 | May 26 -Rs 15,876 | -Rs 29,869 |
| R72 Breadth Donchian | B+K1 | 577 | 50% | **Rs 171,259** | Rs 1,912 / -Rs 1,291 | -Rs 21,301 | May 26 -Rs 20,652 | -Rs 33,168 |
| R51 Donchian 20 + Supertrend | A | 1668 | 45% | **Rs 131,546** | Rs 1,648 / -Rs 1,197 | -Rs 21,578 | Jun 26 -Rs 54,587 | -Rs 81,683 |
| R51 Donchian 20 + Supertrend | X1 | 1671 | 43% | **Rs 79,592** | Rs 1,624 / -Rs 1,152 | -Rs 21,578 | Jun 26 -Rs 54,568 | -Rs 96,187 |
| R51 Donchian 20 + Supertrend | X2 | 1671 | 44% | **Rs 125,428** | Rs 1,658 / -Rs 1,162 | -Rs 21,578 | Jun 26 -Rs 54,782 | -Rs 91,434 |
| R51 Donchian 20 + Supertrend | K1 | 1616 | 45% | **Rs 125,602** | Rs 1,626 / -Rs 1,188 | -Rs 21,578 | Jun 26 -Rs 57,727 | -Rs 77,260 |
| R51 Donchian 20 + Supertrend | B | 652 | 50% | **Rs 143,945** | Rs 1,733 / -Rs 1,320 | -Rs 14,642 | Jun 26 -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+X1 | 656 | 48% | **Rs 101,730** | Rs 1,686 / -Rs 1,259 | -Rs 14,642 | Jun 26 -Rs 24,327 | -Rs 47,790 |
| R51 Donchian 20 + Supertrend | B+X2 | 654 | 49% | **Rs 138,702** | Rs 1,737 / -Rs 1,267 | -Rs 14,642 | Jun 26 -Rs 22,632 | -Rs 41,152 |
| R51 Donchian 20 + Supertrend | B+K1 | 613 | 51% | **Rs 156,004** | Rs 1,742 / -Rs 1,308 | -Rs 14,642 | Jun 26 -Rs 23,476 | -Rs 32,491 |
| All three | A | 3225 | 44% | **Rs 313,037** | Rs 1,697 / -Rs 1,179 | -Rs 53,710 | Jun 26 -Rs 96,444 | -Rs 139,134 |
| All three | X1 | 3229 | 43% | **Rs 210,268** | Rs 1,681 / -Rs 1,147 | -Rs 53,710 | Jun 26 -Rs 94,450 | -Rs 160,629 |
| All three | X2 | 3230 | 44% | **Rs 329,938** | Rs 1,706 / -Rs 1,136 | -Rs 53,710 | Jun 26 -Rs 97,186 | -Rs 146,925 |
| All three | K1 | 3074 | 45% | **Rs 318,131** | Rs 1,699 / -Rs 1,177 | -Rs 53,710 | Jun 26 -Rs 108,695 | -Rs 126,179 |
| All three | B | 1611 | 47% | **Rs 325,835** | Rs 1,811 / -Rs 1,228 | -Rs 39,870 | Apr 26 -Rs 29,430 | -Rs 68,921 |
| All three | B+X1 | 1615 | 45% | **Rs 263,105** | Rs 1,806 / -Rs 1,192 | -Rs 39,870 | Apr 26 -Rs 40,335 | -Rs 98,613 |
| All three | B+X2 | 1613 | 46% | **Rs 338,991** | Rs 1,816 / -Rs 1,168 | -Rs 39,870 | Jun 26 -Rs 18,979 | -Rs 60,389 |
| All three | B+K1 | 1495 | 48% | **Rs 344,090** | Rs 1,824 / -Rs 1,231 | -Rs 39,870 | Apr 26 -Rs 38,628 | -Rs 72,469 |

### last 6 months (20 Apr - 19 Oct 2026), 1 lot (126 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 346 | 38% | **Rs 28,392** | Rs 1,750 / -Rs 934 | -Rs 11,405 | Oct 26 -Rs 15,384 | -Rs 38,009 |
| Liquidity 15+5 | X1 | 346 | 37% | **Rs 31,511** | Rs 1,776 / -Rs 911 | -Rs 11,405 | Apr 26 -Rs 14,926 | -Rs 39,457 |
| Liquidity 15+5 | X2 | 346 | 37% | **Rs 36,702** | Rs 1,750 / -Rs 859 | -Rs 11,405 | Apr 26 -Rs 11,979 | -Rs 35,907 |
| Liquidity 15+5 | K1 | 291 | 38% | **Rs 34,030** | Rs 1,841 / -Rs 931 | -Rs 11,405 | Apr 26 -Rs 14,198 | -Rs 35,647 |
| R72 Breadth Donchian | A | 1116 | 47% | **Rs 189,980** | Rs 1,717 / -Rs 1,179 | -Rs 22,571 | Jun 26 -Rs 40,908 | -Rs 61,884 |
| R72 Breadth Donchian | X1 | 1117 | 45% | **Rs 135,658** | Rs 1,694 / -Rs 1,157 | -Rs 22,571 | Jun 26 -Rs 41,029 | -Rs 69,937 |
| R72 Breadth Donchian | X2 | 1118 | 46% | **Rs 204,808** | Rs 1,723 / -Rs 1,136 | -Rs 20,728 | Jun 26 -Rs 41,628 | -Rs 61,993 |
| R72 Breadth Donchian | K1 | 1066 | 46% | **Rs 192,384** | Rs 1,743 / -Rs 1,174 | -Rs 20,728 | Jun 26 -Rs 43,876 | -Rs 58,616 |
| R72 Breadth Donchian | B | 537 | 50% | **Rs 181,614** | Rs 1,862 / -Rs 1,214 | -Rs 17,394 | May 26 -Rs 20,632 | -Rs 31,620 |
| R72 Breadth Donchian | B+X1 | 537 | 48% | **Rs 160,332** | Rs 1,883 / -Rs 1,188 | -Rs 18,591 | May 26 -Rs 29,078 | -Rs 38,717 |
| R72 Breadth Donchian | B+X2 | 537 | 50% | **Rs 187,034** | Rs 1,868 / -Rs 1,166 | -Rs 15,869 | May 26 -Rs 15,876 | -Rs 29,869 |
| R72 Breadth Donchian | B+K1 | 514 | 51% | **Rs 183,623** | Rs 1,859 / -Rs 1,204 | -Rs 15,252 | May 26 -Rs 20,652 | -Rs 33,168 |
| R51 Donchian 20 + Supertrend | A | 1584 | 45% | **Rs 148,408** | Rs 1,609 / -Rs 1,166 | -Rs 21,578 | Jun 26 -Rs 54,587 | -Rs 81,683 |
| R51 Donchian 20 + Supertrend | X1 | 1587 | 44% | **Rs 94,507** | Rs 1,588 / -Rs 1,125 | -Rs 21,578 | Jun 26 -Rs 54,568 | -Rs 96,187 |
| R51 Donchian 20 + Supertrend | X2 | 1587 | 44% | **Rs 132,666** | Rs 1,621 / -Rs 1,139 | -Rs 21,578 | Jun 26 -Rs 54,782 | -Rs 91,434 |
| R51 Donchian 20 + Supertrend | K1 | 1532 | 45% | **Rs 136,213** | Rs 1,584 / -Rs 1,156 | -Rs 21,578 | Jun 26 -Rs 57,727 | -Rs 77,260 |
| R51 Donchian 20 + Supertrend | B | 598 | 51% | **Rs 147,723** | Rs 1,662 / -Rs 1,236 | -Rs 14,642 | Jun 26 -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+X1 | 602 | 48% | **Rs 107,211** | Rs 1,619 / -Rs 1,179 | -Rs 14,642 | Jun 26 -Rs 24,327 | -Rs 47,790 |
| R51 Donchian 20 + Supertrend | B+X2 | 600 | 50% | **Rs 133,984** | Rs 1,673 / -Rs 1,207 | -Rs 14,642 | Jun 26 -Rs 22,632 | -Rs 41,152 |
| R51 Donchian 20 + Supertrend | B+K1 | 558 | 52% | **Rs 162,109** | Rs 1,668 / -Rs 1,211 | -Rs 14,642 | Jun 26 -Rs 23,476 | -Rs 32,491 |
| All three | A | 3046 | 45% | **Rs 366,780** | Rs 1,664 / -Rs 1,141 | -Rs 53,710 | Jun 26 -Rs 96,444 | -Rs 139,134 |
| All three | X1 | 3050 | 43% | **Rs 261,676** | Rs 1,647 / -Rs 1,110 | -Rs 53,710 | Jun 26 -Rs 94,450 | -Rs 160,629 |
| All three | X2 | 3051 | 44% | **Rs 374,175** | Rs 1,672 / -Rs 1,102 | -Rs 53,710 | Jun 26 -Rs 97,186 | -Rs 146,925 |
| All three | K1 | 2889 | 45% | **Rs 362,627** | Rs 1,667 / -Rs 1,137 | -Rs 53,710 | Jun 26 -Rs 108,695 | -Rs 126,179 |
| All three | B | 1481 | 48% | **Rs 357,730** | Rs 1,755 / -Rs 1,145 | -Rs 39,870 | Jun 26 -Rs 21,248 | -Rs 59,893 |
| All three | B+X1 | 1485 | 46% | **Rs 299,053** | Rs 1,749 / -Rs 1,110 | -Rs 39,870 | May 26 -Rs 30,491 | -Rs 78,680 |
| All three | B+X2 | 1483 | 47% | **Rs 357,719** | Rs 1,763 / -Rs 1,097 | -Rs 39,870 | Jun 26 -Rs 18,979 | -Rs 60,389 |
| All three | B+K1 | 1363 | 49% | **Rs 379,762** | Rs 1,772 / -Rs 1,136 | -Rs 39,870 | Jun 26 -Rs 32,625 | -Rs 57,918 |

### whole year (20 Oct 2025 - 19 Oct 2026), 3 lots (247 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 688 | 41% | **Rs 415,755** | Rs 5,645 / -Rs 2,897 | -Rs 33,486 | Apr 26 -Rs 108,688 | -Rs 175,017 |
| Liquidity 15+5 | X1 | 688 | 41% | **Rs 425,049** | Rs 5,684 / -Rs 2,859 | -Rs 33,486 | Apr 26 -Rs 113,032 | -Rs 179,361 |
| Liquidity 15+5 | X2 | 688 | 40% | **Rs 414,113** | Rs 5,567 / -Rs 2,704 | -Rs 33,486 | Apr 26 -Rs 99,846 | -Rs 164,360 |
| Liquidity 15+5 | K1 | 592 | 42% | **Rs 472,168** | Rs 6,019 / -Rs 2,941 | -Rs 33,486 | Apr 26 -Rs 106,985 | -Rs 164,437 |
| R72 Breadth Donchian | A | 2281 | 47% | **Rs 1,104,831** | Rs 5,312 / -Rs 3,788 | -Rs 76,355 | Dec 25 -Rs 197,761 | -Rs 242,618 |
| R72 Breadth Donchian | X1 | 2282 | 46% | **Rs 917,523** | Rs 5,282 / -Rs 3,706 | -Rs 76,948 | Dec 25 -Rs 202,094 | -Rs 245,741 |
| R72 Breadth Donchian | X2 | 2283 | 46% | **Rs 1,143,858** | Rs 5,372 / -Rs 3,610 | -Rs 76,355 | Dec 25 -Rs 167,639 | -Rs 211,073 |
| R72 Breadth Donchian | K1 | 2185 | 47% | **Rs 1,096,306** | Rs 5,315 / -Rs 3,791 | -Rs 76,355 | Dec 25 -Rs 182,167 | -Rs 205,395 |
| R72 Breadth Donchian | B | 1062 | 53% | **Rs 1,212,701** | Rs 6,072 / -Rs 4,316 | -Rs 76,355 | May 26 -Rs 48,023 | -Rs 100,468 |
| R72 Breadth Donchian | B+X1 | 1062 | 51% | **Rs 1,113,676** | Rs 6,107 / -Rs 4,203 | -Rs 76,948 | May 26 -Rs 73,352 | -Rs 103,365 |
| R72 Breadth Donchian | B+X2 | 1060 | 51% | **Rs 1,201,290** | Rs 6,177 / -Rs 4,125 | -Rs 76,355 | May 26 -Rs 33,737 | -Rs 109,889 |
| R72 Breadth Donchian | B+K1 | 1026 | 53% | **Rs 1,142,676** | Rs 5,957 / -Rs 4,310 | -Rs 76,355 | May 26 -Rs 48,838 | -Rs 118,464 |
| R51 Donchian 20 + Supertrend | A | 3084 | 46% | **Rs 946,603** | Rs 5,069 / -Rs 3,746 | -Rs 85,577 | Jun 26 -Rs 138,454 | -Rs 263,933 |
| R51 Donchian 20 + Supertrend | X1 | 3089 | 44% | **Rs 727,916** | Rs 5,056 / -Rs 3,591 | -Rs 86,648 | Dec 25 -Rs 143,557 | -Rs 262,921 |
| R51 Donchian 20 + Supertrend | X2 | 3089 | 44% | **Rs 888,162** | Rs 5,138 / -Rs 3,573 | -Rs 85,577 | Jun 26 -Rs 139,037 | -Rs 265,580 |
| R51 Donchian 20 + Supertrend | K1 | 2986 | 46% | **Rs 909,917** | Rs 5,038 / -Rs 3,698 | -Rs 85,577 | Jun 26 -Rs 148,487 | -Rs 289,297 |
| R51 Donchian 20 + Supertrend | B | 1361 | 51% | **Rs 1,004,440** | Rs 5,470 / -Rs 4,274 | -Rs 73,545 | Jun 26 -Rs 62,761 | -Rs 108,826 |
| R51 Donchian 20 + Supertrend | B+X1 | 1368 | 49% | **Rs 858,378** | Rs 5,447 / -Rs 4,053 | -Rs 77,126 | Jun 26 -Rs 66,808 | -Rs 131,258 |
| R51 Donchian 20 + Supertrend | B+X2 | 1364 | 49% | **Rs 975,444** | Rs 5,590 / -Rs 3,977 | -Rs 59,596 | Jun 26 -Rs 61,723 | -Rs 111,309 |
| R51 Donchian 20 + Supertrend | B+K1 | 1278 | 52% | **Rs 973,620** | Rs 5,450 / -Rs 4,245 | -Rs 59,596 | Jun 26 -Rs 64,539 | -Rs 93,325 |
| All three | A | 6053 | 46% | **Rs 2,467,188** | Rs 5,221 / -Rs 3,657 | -Rs 158,555 | Dec 25 -Rs 336,310 | -Rs 432,666 |
| All three | X1 | 6059 | 44% | **Rs 2,070,487** | Rs 5,209 / -Rs 3,545 | -Rs 159,323 | Dec 25 -Rs 355,358 | -Rs 443,733 |
| All three | X2 | 6060 | 44% | **Rs 2,446,133** | Rs 5,273 / -Rs 3,480 | -Rs 158,555 | Dec 25 -Rs 252,007 | -Rs 353,648 |
| All three | K1 | 5763 | 46% | **Rs 2,478,391** | Rs 5,238 / -Rs 3,648 | -Rs 158,555 | Jun 26 -Rs 279,734 | -Rs 334,868 |
| All three | B | 3111 | 50% | **Rs 2,632,895** | Rs 5,720 / -Rs 3,932 | -Rs 130,335 | Apr 26 -Rs 61,992 | -Rs 199,852 |
| All three | B+X1 | 3118 | 48% | **Rs 2,397,102** | Rs 5,730 / -Rs 3,801 | -Rs 137,703 | Apr 26 -Rs 94,621 | -Rs 245,283 |
| All three | B+X2 | 3112 | 48% | **Rs 2,590,847** | Rs 5,800 / -Rs 3,701 | -Rs 117,746 | Jun 26 -Rs 39,081 | -Rs 205,134 |
| All three | B+K1 | 2896 | 50% | **Rs 2,588,463** | Rs 5,737 / -Rs 3,956 | -Rs 117,746 | Apr 26 -Rs 91,669 | -Rs 195,064 |

### Month by month, whole year, 1 lot

| arm | variant | Oct 25 | Nov 25 | Dec 25 | Jan 26 | Feb 26 | Mar 26 | Apr 26 | May 26 | Jun 26 | Jul 26 | Aug 26 | Sep 26 | Oct 26 | total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | -Rs 611 | Rs 2,638 | -Rs 4,980 | Rs 45,771 | Rs 43,708 | Rs 27,346 | -Rs 37,821 | -Rs 8,832 | -Rs 949 | Rs 33,022 | -Rs 1,605 | Rs 35,620 | -Rs 15,384 | Rs 117,923 |
| Liquidity 15+5 | X1 | -Rs 611 | Rs 2,638 | -Rs 5,169 | Rs 45,738 | Rs 43,708 | Rs 27,544 | -Rs 39,269 | -Rs 8,832 | Rs 1,147 | Rs 34,408 | -Rs 1,605 | Rs 36,004 | -Rs 14,684 | Rs 121,016 |
| Liquidity 15+5 | X2 | -Rs 433 | -Rs 460 | -Rs 1,046 | Rs 34,831 | Rs 41,941 | Rs 28,729 | -Rs 34,879 | -Rs 8,230 | -Rs 776 | Rs 34,828 | -Rs 1,486 | Rs 34,054 | -Rs 9,710 | Rs 117,363 |
| Liquidity 15+5 | K1 | -Rs 995 | Rs 932 | Rs 1,557 | Rs 49,786 | Rs 46,519 | Rs 30,507 | -Rs 36,925 | -Rs 4,160 | -Rs 7,092 | Rs 30,275 | Rs 586 | Rs 35,276 | -Rs 6,658 | Rs 139,609 |
| R72 Breadth Donchian | A | Rs 22,318 | Rs 8,007 | -Rs 72,605 | Rs 57,857 | Rs 27,329 | Rs 85,635 | -Rs 15,245 | Rs 39,929 | -Rs 40,908 | Rs 64,441 | Rs 21,113 | Rs 29,606 | Rs 70,777 | Rs 298,253 |
| R72 Breadth Donchian | X1 | Rs 26,703 | Rs 6,942 | -Rs 74,043 | Rs 52,330 | Rs 29,529 | Rs 80,582 | -Rs 20,981 | Rs 19,866 | -Rs 41,029 | Rs 58,087 | Rs 18,496 | Rs 22,483 | Rs 56,818 | Rs 235,783 |
| R72 Breadth Donchian | X2 | Rs 22,466 | Rs 5,481 | -Rs 62,561 | Rs 58,609 | Rs 22,643 | Rs 77,564 | -Rs 11,763 | Rs 43,515 | -Rs 41,628 | Rs 68,399 | Rs 24,795 | Rs 32,229 | Rs 71,447 | Rs 311,197 |
| R72 Breadth Donchian | K1 | Rs 22,293 | Rs 10,911 | -Rs 66,819 | Rs 69,730 | Rs 8,032 | Rs 84,021 | -Rs 21,705 | Rs 41,146 | -Rs 43,876 | Rs 79,370 | Rs 26,492 | Rs 30,109 | Rs 58,677 | Rs 298,382 |
| R51 Donchian 20 + Supertrend | A | Rs 1,267 | Rs 16,638 | -Rs 51,699 | Rs 75,958 | -Rs 28,345 | Rs 72,461 | Rs 16,172 | Rs 29,306 | -Rs 54,587 | Rs 68,207 | Rs 18,751 | -Rs 2,459 | Rs 59,567 | Rs 221,238 |
| R51 Donchian 20 + Supertrend | X1 | Rs 3,584 | Rs 9,960 | -Rs 56,460 | Rs 70,733 | -Rs 30,860 | Rs 68,134 | Rs 17,097 | Rs 4,216 | -Rs 54,568 | Rs 64,147 | Rs 12,937 | -Rs 8,414 | Rs 47,659 | Rs 148,166 |
| R51 Donchian 20 + Supertrend | X2 | -Rs 299 | Rs 5,860 | -Rs 37,603 | Rs 74,991 | -Rs 36,260 | Rs 69,992 | Rs 21,566 | Rs 18,231 | -Rs 54,782 | Rs 66,827 | Rs 18,687 | Rs 2,022 | Rs 52,309 | Rs 201,541 |
| R51 Donchian 20 + Supertrend | K1 | Rs 5,021 | Rs 16,986 | -Rs 35,032 | Rs 80,419 | -Rs 49,011 | Rs 72,001 | Rs 9,275 | Rs 47,040 | -Rs 57,727 | Rs 65,359 | Rs 16,229 | -Rs 2,944 | Rs 44,384 | Rs 212,002 |
| All three | A | Rs 22,974 | Rs 27,283 | -Rs 129,283 | Rs 179,585 | Rs 42,693 | Rs 185,442 | -Rs 36,894 | Rs 60,403 | -Rs 96,444 | Rs 165,669 | Rs 38,260 | Rs 62,766 | Rs 114,960 | Rs 637,414 |
| All three | X1 | Rs 29,676 | Rs 19,541 | -Rs 135,672 | Rs 168,801 | Rs 42,377 | Rs 176,260 | -Rs 43,152 | Rs 15,250 | -Rs 94,450 | Rs 156,642 | Rs 29,827 | Rs 50,073 | Rs 89,793 | Rs 504,965 |
| All three | X2 | Rs 21,734 | Rs 10,881 | -Rs 101,210 | Rs 168,431 | Rs 28,324 | Rs 176,285 | -Rs 25,076 | Rs 53,516 | -Rs 97,186 | Rs 170,054 | Rs 41,997 | Rs 68,305 | Rs 114,046 | Rs 630,101 |
| All three | K1 | Rs 26,319 | Rs 28,830 | -Rs 100,295 | Rs 199,935 | Rs 5,540 | Rs 186,529 | -Rs 49,355 | Rs 84,027 | -Rs 108,695 | Rs 175,004 | Rs 43,308 | Rs 62,441 | Rs 96,404 | Rs 649,993 |

## Amendment A: K1 + Rs 1,000 a lot stop + Rs 500 step trail (K1S)

S1k = R176 Amendment B's stop rule alone (reproduced exactly here: LIQ@1 692 / -Rs 3,176; R72@1 2383 / -Rs 31,372; R51@1 3206 / -Rs 60,516). K1S = K1's skip plus that stop rule. BH m = 4,000.

| arm | passed | passes except account stop | twin p (K1 on top of S1k vs random skips) | BH q | q within the 3 | failed gates |
|---|---|---|---|---|---|---|
| Liquidity 15+5 | **no** | no | 0.0639 | 1.0 | 0.0958 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in final, year |
| R72 Breadth Donchian | **no** | no | 0.0958 | 1.0 | 0.0958 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R51 Donchian 20 + Supertrend | **no** | no | 0.0459 | 1.0 | 0.0958 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |

### whole year (20 Oct 2025 - 19 Oct 2026), 1 lot (247 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 688 | 39% | **Rs 117,923** | Rs 1,935 / -Rs 968 | -Rs 11,405 | Apr 26 -Rs 37,821 | -Rs 61,062 |
| Liquidity 15+5 | S1k | 692 | 45% | **-Rs 3,176** | Rs 1,024 / -Rs 835 | -Rs 6,659 | Apr 26 -Rs 22,266 | -Rs 65,836 |
| Liquidity 15+5 | K1S | 596 | 46% | **Rs 16,285** | Rs 1,055 / -Rs 836 | -Rs 6,018 | Apr 26 -Rs 21,230 | -Rs 55,285 |
| R72 Breadth Donchian | A | 2281 | 46% | **Rs 298,253** | Rs 1,763 / -Rs 1,279 | -Rs 25,837 | Dec 25 -Rs 72,605 | -Rs 86,529 |
| R72 Breadth Donchian | S1k | 2383 | 48% | **-Rs 31,372** | Rs 1,049 / -Rs 1,000 | -Rs 13,754 | Dec 25 -Rs 69,609 | -Rs 114,080 |
| R72 Breadth Donchian | K1S | 2287 | 48% | **-Rs 15,498** | Rs 1,063 / -Rs 999 | -Rs 13,754 | Dec 25 -Rs 67,450 | -Rs 108,741 |
| R72 Breadth Donchian | B | 1062 | 52% | **Rs 371,717** | Rs 2,019 / -Rs 1,449 | -Rs 25,837 | May 26 -Rs 20,632 | -Rs 34,940 |
| R72 Breadth Donchian | B+S1k | 1093 | 52% | **Rs 126,381** | Rs 1,176 / -Rs 1,044 | -Rs 14,843 | May 26 -Rs 16,448 | -Rs 59,266 |
| R72 Breadth Donchian | B+K1S | 1058 | 52% | **Rs 122,739** | Rs 1,176 / -Rs 1,036 | -Rs 14,843 | Apr 26 -Rs 18,883 | -Rs 69,977 |
| R51 Donchian 20 + Supertrend | A | 3084 | 45% | **Rs 221,238** | Rs 1,692 / -Rs 1,259 | -Rs 28,942 | Jun 26 -Rs 54,587 | -Rs 94,176 |
| R51 Donchian 20 + Supertrend | S1k | 3206 | 48% | **-Rs 60,516** | Rs 1,009 / -Rs 983 | -Rs 17,041 | Dec 25 -Rs 58,264 | -Rs 115,806 |
| R51 Donchian 20 + Supertrend | K1S | 3109 | 49% | **-Rs 38,632** | Rs 1,015 / -Rs 982 | -Rs 17,041 | Dec 25 -Rs 50,955 | -Rs 124,701 |
| R51 Donchian 20 + Supertrend | B | 1361 | 51% | **Rs 293,395** | Rs 1,814 / -Rs 1,438 | -Rs 24,757 | Jun 26 -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+S1k | 1448 | 51% | **Rs 24,379** | Rs 1,026 / -Rs 1,044 | -Rs 12,386 | Apr 26 -Rs 36,545 | -Rs 79,834 |
| R51 Donchian 20 + Supertrend | B+K1S | 1363 | 52% | **Rs 47,656** | Rs 1,039 / -Rs 1,034 | -Rs 12,386 | Apr 26 -Rs 34,976 | -Rs 76,924 |
| All three | A | 6053 | 45% | **Rs 637,414** | Rs 1,744 / -Rs 1,230 | -Rs 53,710 | Dec 25 -Rs 129,283 | -Rs 159,077 |
| All three | S1k | 6281 | 48% | **-Rs 95,063** | Rs 1,026 / -Rs 972 | -Rs 33,517 | Dec 25 -Rs 130,878 | -Rs 252,185 |
| All three | K1S | 5992 | 48% | **-Rs 37,845** | Rs 1,038 / -Rs 973 | -Rs 30,843 | Dec 25 -Rs 115,382 | -Rs 245,686 |
| All three | B | 3111 | 49% | **Rs 783,034** | Rs 1,910 / -Rs 1,318 | -Rs 43,996 | Apr 26 -Rs 29,430 | -Rs 76,847 |
| All three | B+S1k | 3233 | 50% | **Rs 147,584** | Rs 1,078 / -Rs 994 | -Rs 27,277 | Apr 26 -Rs 70,074 | -Rs 167,093 |
| All three | B+K1S | 3017 | 51% | **Rs 186,681** | Rs 1,091 / -Rs 992 | -Rs 27,277 | Apr 26 -Rs 75,089 | -Rs 174,277 |

### early (20 Oct 2025 - 31 Mar 2026), 1 lot (110 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 316 | 42% | **Rs 113,873** | Rs 2,142 / -Rs 935 | -Rs 5,535 | Dec 25 -Rs 4,980 | -Rs 16,913 |
| Liquidity 15+5 | S1k | 319 | 47% | **Rs 36,139** | Rs 1,181 / -Rs 847 | -Rs 5,355 | Mar 26 -Rs 7,544 | -Rs 19,385 |
| Liquidity 15+5 | K1S | 281 | 48% | **Rs 44,282** | Rs 1,240 / -Rs 858 | -Rs 4,577 | Mar 26 -Rs 6,209 | -Rs 16,276 |
| R72 Breadth Donchian | A | 1056 | 47% | **Rs 128,541** | Rs 1,792 / -Rs 1,341 | -Rs 25,837 | Dec 25 -Rs 72,605 | -Rs 86,529 |
| R72 Breadth Donchian | S1k | 1112 | 50% | **-Rs 33,053** | Rs 949 / -Rs 995 | -Rs 12,611 | Dec 25 -Rs 69,609 | -Rs 73,591 |
| R72 Breadth Donchian | K1S | 1069 | 50% | **-Rs 24,063** | Rs 967 / -Rs 996 | -Rs 12,611 | Dec 25 -Rs 67,450 | -Rs 69,749 |
| R72 Breadth Donchian | B | 452 | 55% | **Rs 200,162** | Rs 2,153 / -Rs 1,673 | -Rs 25,837 | Dec 25 -Rs 1,721 | -Rs 34,940 |
| R72 Breadth Donchian | B+S1k | 460 | 57% | **Rs 65,871** | Rs 1,069 / -Rs 1,082 | -Rs 9,963 | Mar 26 -Rs 7,707 | -Rs 27,564 |
| R72 Breadth Donchian | B+K1S | 449 | 57% | **Rs 67,115** | Rs 1,088 / -Rs 1,074 | -Rs 9,963 | Mar 26 -Rs 10,798 | -Rs 30,656 |
| R51 Donchian 20 + Supertrend | A | 1370 | 45% | **Rs 86,280** | Rs 1,744 / -Rs 1,326 | -Rs 28,942 | Dec 25 -Rs 51,699 | -Rs 94,176 |
| R51 Donchian 20 + Supertrend | S1k | 1431 | 50% | **-Rs 32,135** | Rs 958 / -Rs 993 | -Rs 14,104 | Dec 25 -Rs 58,264 | -Rs 82,450 |
| R51 Donchian 20 + Supertrend | K1S | 1392 | 50% | **-Rs 12,632** | Rs 979 / -Rs 991 | -Rs 14,104 | Dec 25 -Rs 50,955 | -Rs 69,239 |
| R51 Donchian 20 + Supertrend | B | 691 | 51% | **Rs 156,237** | Rs 1,905 / -Rs 1,537 | -Rs 24,757 | Dec 25 -Rs 904 | -Rs 29,931 |
| R51 Donchian 20 + Supertrend | B+S1k | 731 | 53% | **Rs 52,934** | Rs 1,058 / -Rs 1,061 | -Rs 12,215 | Dec 25 -Rs 14,906 | -Rs 39,953 |
| R51 Donchian 20 + Supertrend | B+K1S | 691 | 54% | **Rs 61,958** | Rs 1,079 / -Rs 1,057 | -Rs 12,215 | Dec 25 -Rs 15,279 | -Rs 32,445 |
| All three | A | 2742 | 45% | **Rs 328,694** | Rs 1,805 / -Rs 1,284 | -Rs 51,650 | Dec 25 -Rs 129,283 | -Rs 159,077 |
| All three | S1k | 2862 | 49% | **-Rs 29,049** | Rs 978 / -Rs 977 | -Rs 26,543 | Dec 25 -Rs 130,878 | -Rs 149,330 |
| All three | K1S | 2742 | 50% | **Rs 7,587** | Rs 1,000 / -Rs 979 | -Rs 26,543 | Dec 25 -Rs 115,382 | -Rs 128,570 |
| All three | B | 1459 | 51% | **Rs 470,271** | Rs 2,032 / -Rs 1,423 | -Rs 43,996 | Dec 25 -Rs 7,604 | -Rs 60,566 |
| All three | B+S1k | 1510 | 53% | **Rs 154,944** | Rs 1,085 / -Rs 1,016 | -Rs 21,878 | Mar 26 -Rs 24,771 | -Rs 63,684 |
| All three | B+K1S | 1421 | 54% | **Rs 173,356** | Rs 1,111 / -Rs 1,018 | -Rs 22,440 | Mar 26 -Rs 28,114 | -Rs 67,027 |

### final (1 Apr - 13 Oct 2026), 1 lot (133 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 357 | 37% | **Rs 11,155** | Rs 1,762 / -Rs 997 | -Rs 11,405 | Apr 26 -Rs 37,821 | -Rs 61,062 |
| Liquidity 15+5 | S1k | 358 | 43% | **-Rs 32,616** | Rs 891 / -Rs 824 | -Rs 6,018 | Apr 26 -Rs 22,266 | -Rs 47,236 |
| Liquidity 15+5 | K1S | 306 | 44% | **-Rs 23,488** | Rs 879 / -Rs 822 | -Rs 6,018 | Apr 26 -Rs 21,230 | -Rs 39,795 |
| R72 Breadth Donchian | A | 1200 | 46% | **Rs 170,336** | Rs 1,748 / -Rs 1,217 | -Rs 22,571 | Jun 26 -Rs 40,908 | -Rs 61,884 |
| R72 Breadth Donchian | S1k | 1242 | 47% | **Rs 8,611** | Rs 1,155 / -Rs 999 | -Rs 13,754 | Apr 26 -Rs 35,083 | -Rs 66,263 |
| R72 Breadth Donchian | K1S | 1192 | 47% | **Rs 17,114** | Rs 1,165 / -Rs 995 | -Rs 13,754 | Apr 26 -Rs 35,779 | -Rs 61,427 |
| R72 Breadth Donchian | B | 602 | 49% | **Rs 170,735** | Rs 1,920 / -Rs 1,299 | -Rs 21,301 | May 26 -Rs 20,632 | -Rs 31,620 |
| R72 Breadth Donchian | B+S1k | 625 | 49% | **Rs 63,650** | Rs 1,271 / -Rs 1,020 | -Rs 14,843 | May 26 -Rs 16,448 | -Rs 31,702 |
| R72 Breadth Donchian | B+K1S | 601 | 49% | **Rs 58,765** | Rs 1,255 / -Rs 1,011 | -Rs 14,843 | Apr 26 -Rs 18,883 | -Rs 39,322 |
| R51 Donchian 20 + Supertrend | A | 1668 | 45% | **Rs 131,546** | Rs 1,648 / -Rs 1,197 | -Rs 21,578 | Jun 26 -Rs 54,587 | -Rs 81,683 |
| R51 Donchian 20 + Supertrend | S1k | 1724 | 47% | **-Rs 21,897** | Rs 1,059 / -Rs 969 | -Rs 17,041 | Jun 26 -Rs 35,397 | -Rs 68,497 |
| R51 Donchian 20 + Supertrend | K1S | 1667 | 47% | **-Rs 17,703** | Rs 1,052 / -Rs 968 | -Rs 17,041 | Jun 26 -Rs 40,557 | -Rs 63,197 |
| R51 Donchian 20 + Supertrend | B | 652 | 50% | **Rs 143,945** | Rs 1,733 / -Rs 1,320 | -Rs 14,642 | Jun 26 -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+S1k | 697 | 50% | **-Rs 19,749** | Rs 992 / -Rs 1,028 | -Rs 12,386 | Apr 26 -Rs 36,545 | -Rs 53,197 |
| R51 Donchian 20 + Supertrend | B+K1S | 653 | 50% | **-Rs 6,593** | Rs 995 / -Rs 1,012 | -Rs 12,386 | Apr 26 -Rs 34,976 | -Rs 48,701 |
| All three | A | 3225 | 44% | **Rs 313,037** | Rs 1,697 / -Rs 1,179 | -Rs 53,710 | Jun 26 -Rs 96,444 | -Rs 139,134 |
| All three | S1k | 3324 | 47% | **-Rs 45,902** | Rs 1,079 / -Rs 964 | -Rs 30,843 | Apr 26 -Rs 81,483 | -Rs 161,332 |
| All three | K1S | 3165 | 47% | **-Rs 24,077** | Rs 1,079 / -Rs 963 | -Rs 30,843 | Apr 26 -Rs 87,825 | -Rs 151,796 |
| All three | B | 1611 | 47% | **Rs 325,835** | Rs 1,811 / -Rs 1,228 | -Rs 39,870 | Apr 26 -Rs 29,430 | -Rs 68,921 |
| All three | B+S1k | 1680 | 48% | **Rs 11,284** | Rs 1,079 / -Rs 977 | -Rs 27,277 | Apr 26 -Rs 70,074 | -Rs 103,409 |
| All three | B+K1S | 1560 | 48% | **Rs 28,684** | Rs 1,076 / -Rs 971 | -Rs 27,277 | Apr 26 -Rs 75,089 | -Rs 107,250 |

### last 6 months (20 Apr - 19 Oct 2026), 1 lot (126 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 346 | 38% | **Rs 28,392** | Rs 1,750 / -Rs 934 | -Rs 11,405 | Oct 26 -Rs 15,384 | -Rs 38,009 |
| Liquidity 15+5 | S1k | 347 | 44% | **-Rs 25,959** | Rs 862 / -Rs 814 | -Rs 6,659 | May 26 -Rs 11,959 | -Rs 34,607 |
| Liquidity 15+5 | K1S | 292 | 45% | **-Rs 16,517** | Rs 855 / -Rs 808 | -Rs 6,018 | Jun 26 -Rs 11,558 | -Rs 29,043 |
| R72 Breadth Donchian | A | 1116 | 47% | **Rs 189,980** | Rs 1,717 / -Rs 1,179 | -Rs 22,571 | Jun 26 -Rs 40,908 | -Rs 61,884 |
| R72 Breadth Donchian | S1k | 1158 | 48% | **Rs 32,623** | Rs 1,142 / -Rs 986 | -Rs 13,727 | Jun 26 -Rs 23,823 | -Rs 49,955 |
| R72 Breadth Donchian | K1S | 1106 | 47% | **Rs 39,558** | Rs 1,159 / -Rs 979 | -Rs 12,602 | Jun 26 -Rs 22,547 | -Rs 44,422 |
| R72 Breadth Donchian | B | 537 | 50% | **Rs 181,614** | Rs 1,862 / -Rs 1,214 | -Rs 17,394 | May 26 -Rs 20,632 | -Rs 31,620 |
| R72 Breadth Donchian | B+S1k | 552 | 50% | **Rs 73,315** | Rs 1,281 / -Rs 999 | -Rs 8,958 | May 26 -Rs 16,448 | -Rs 27,067 |
| R72 Breadth Donchian | B+K1S | 530 | 50% | **Rs 72,977** | Rs 1,272 / -Rs 988 | -Rs 8,958 | May 26 -Rs 15,277 | -Rs 26,345 |
| R51 Donchian 20 + Supertrend | A | 1584 | 45% | **Rs 148,408** | Rs 1,609 / -Rs 1,166 | -Rs 21,578 | Jun 26 -Rs 54,587 | -Rs 81,683 |
| R51 Donchian 20 + Supertrend | S1k | 1645 | 48% | **Rs 13,940** | Rs 1,052 / -Rs 958 | -Rs 13,130 | Jun 26 -Rs 35,397 | -Rs 68,497 |
| R51 Donchian 20 + Supertrend | K1S | 1588 | 48% | **Rs 19,554** | Rs 1,049 / -Rs 956 | -Rs 13,071 | Jun 26 -Rs 40,557 | -Rs 63,197 |
| R51 Donchian 20 + Supertrend | B | 598 | 51% | **Rs 147,723** | Rs 1,662 / -Rs 1,236 | -Rs 14,642 | Jun 26 -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+S1k | 645 | 50% | **Rs 7,953** | Rs 1,004 / -Rs 995 | -Rs 10,952 | Sep 26 -Rs 30,097 | -Rs 47,003 |
| R51 Donchian 20 + Supertrend | B+K1S | 600 | 51% | **Rs 22,206** | Rs 1,008 / -Rs 974 | -Rs 10,952 | Sep 26 -Rs 27,724 | -Rs 42,249 |
| All three | A | 3046 | 45% | **Rs 366,780** | Rs 1,664 / -Rs 1,141 | -Rs 53,710 | Jun 26 -Rs 96,444 | -Rs 139,134 |
| All three | S1k | 3150 | 47% | **Rs 20,604** | Rs 1,066 / -Rs 951 | -Rs 33,517 | Jun 26 -Rs 67,524 | -Rs 119,677 |
| All three | K1S | 2986 | 48% | **Rs 42,594** | Rs 1,072 / -Rs 950 | -Rs 29,015 | Jun 26 -Rs 74,662 | -Rs 103,799 |
| All three | B | 1481 | 48% | **Rs 357,730** | Rs 1,755 / -Rs 1,145 | -Rs 39,870 | Jun 26 -Rs 21,248 | -Rs 59,893 |
| All three | B+S1k | 1544 | 49% | **Rs 55,309** | Rs 1,076 / -Rs 952 | -Rs 20,617 | May 26 -Rs 18,555 | -Rs 82,608 |
| All three | B+K1S | 1422 | 49% | **Rs 78,665** | Rs 1,078 / -Rs 942 | -Rs 20,617 | Sep 26 -Rs 13,236 | -Rs 78,078 |

### whole year (20 Oct 2025 - 19 Oct 2026), 3 lots (247 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 688 | 41% | **Rs 415,755** | Rs 5,645 / -Rs 2,897 | -Rs 33,486 | Apr 26 -Rs 108,688 | -Rs 175,017 |
| Liquidity 15+5 | S1k | 692 | 46% | **Rs 54,912** | Rs 3,094 / -Rs 2,454 | -Rs 19,380 | Apr 26 -Rs 59,915 | -Rs 174,518 |
| Liquidity 15+5 | K1S | 596 | 46% | **Rs 104,654** | Rs 3,199 / -Rs 2,450 | -Rs 17,309 | Apr 26 -Rs 57,774 | -Rs 145,515 |
| R72 Breadth Donchian | A | 2281 | 47% | **Rs 1,104,831** | Rs 5,312 / -Rs 3,788 | -Rs 76,355 | Dec 25 -Rs 197,761 | -Rs 242,618 |
| R72 Breadth Donchian | S1k | 2383 | 49% | **Rs 123,300** | Rs 3,210 / -Rs 2,933 | -Rs 40,283 | Dec 25 -Rs 188,381 | -Rs 239,296 |
| R72 Breadth Donchian | K1S | 2287 | 49% | **Rs 161,983** | Rs 3,246 / -Rs 2,934 | -Rs 39,674 | Dec 25 -Rs 183,717 | -Rs 231,812 |
| R72 Breadth Donchian | B | 1062 | 53% | **Rs 1,212,701** | Rs 6,072 / -Rs 4,316 | -Rs 76,355 | May 26 -Rs 48,023 | -Rs 100,468 |
| R72 Breadth Donchian | B+S1k | 1093 | 52% | **Rs 477,495** | Rs 3,603 / -Rs 3,052 | -Rs 43,209 | May 26 -Rs 34,586 | -Rs 152,702 |
| R72 Breadth Donchian | B+K1S | 1058 | 52% | **Rs 463,400** | Rs 3,591 / -Rs 3,041 | -Rs 43,209 | Apr 26 -Rs 45,374 | -Rs 182,034 |
| R51 Donchian 20 + Supertrend | A | 3084 | 46% | **Rs 946,603** | Rs 5,069 / -Rs 3,746 | -Rs 85,577 | Jun 26 -Rs 138,454 | -Rs 263,933 |
| R51 Donchian 20 + Supertrend | S1k | 3206 | 49% | **Rs 110,589** | Rs 3,086 / -Rs 2,887 | -Rs 49,650 | Dec 25 -Rs 148,232 | -Rs 285,313 |
| R51 Donchian 20 + Supertrend | K1S | 3109 | 49% | **Rs 167,340** | Rs 3,100 / -Rs 2,887 | -Rs 49,650 | Dec 25 -Rs 127,562 | -Rs 313,242 |
| R51 Donchian 20 + Supertrend | B | 1361 | 51% | **Rs 1,004,440** | Rs 5,470 / -Rs 4,274 | -Rs 73,545 | Jun 26 -Rs 62,761 | -Rs 108,826 |
| R51 Donchian 20 + Supertrend | B+S1k | 1448 | 52% | **Rs 205,476** | Rs 3,137 / -Rs 3,077 | -Rs 36,115 | Apr 26 -Rs 98,708 | -Rs 218,483 |
| R51 Donchian 20 + Supertrend | B+K1S | 1363 | 52% | **Rs 267,517** | Rs 3,164 / -Rs 3,059 | -Rs 36,115 | Apr 26 -Rs 94,918 | -Rs 210,499 |
| All three | A | 6053 | 46% | **Rs 2,467,188** | Rs 5,221 / -Rs 3,657 | -Rs 158,555 | Dec 25 -Rs 336,310 | -Rs 432,666 |
| All three | S1k | 6281 | 48% | **Rs 288,801** | Rs 3,134 / -Rs 2,854 | -Rs 97,835 | Dec 25 -Rs 339,880 | -Rs 536,817 |
| All three | K1S | 5992 | 49% | **Rs 433,977** | Rs 3,165 / -Rs 2,860 | -Rs 89,390 | Dec 25 -Rs 297,481 | -Rs 523,104 |
| All three | B | 3111 | 50% | **Rs 2,632,895** | Rs 5,720 / -Rs 3,932 | -Rs 130,335 | Apr 26 -Rs 61,992 | -Rs 199,852 |
| All three | B+S1k | 3233 | 51% | **Rs 737,883** | Rs 3,292 / -Rs 2,922 | -Rs 79,300 | Apr 26 -Rs 180,778 | -Rs 437,335 |
| All three | B+K1S | 3017 | 51% | **Rs 835,572** | Rs 3,323 / -Rs 2,921 | -Rs 79,300 | Apr 26 -Rs 198,065 | -Rs 459,498 |


## Amendment B: a Rs 1,000 gap under each step (SG alone, K1SG with K1)

In the tables below 'S1k' = SG (Rs 1,000 a lot entry stop; from +Rs 1,000 a lot the stop sits Rs 1,000 a lot under each Rs 500 step: cost at +1,000, +500 at +1,500, ...) and 'K1S' = K1SG (K1 + SG). BH m = 4,006.

| arm : rule | passed | passes except account stop | twin p | BH q | q within the 6 | failed gates |
|---|---|---|---|---|---|---|
| LIQ:SG | **no** | no | 1.0 | 1.0 | 1.0 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in final, six, year |
| R72:SG | **no** | no | 1.0 | 1.0 | 1.0 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R51:SG | **no** | no | 1.0 | 1.0 | 1.0 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| LIQ:K1SG | **no** | no | 0.0279 | 1.0 | 0.1674 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in final, year |
| R72:K1SG | **no** | no | 0.1876 | 1.0 | 0.3752 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R51:K1SG | **no** | no | 0.1796 | 1.0 | 0.3752 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |

### whole year (20 Oct 2025 - 19 Oct 2026), 1 lot (247 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 688 | 39% | **Rs 117,923** | Rs 1,935 / -Rs 968 | -Rs 11,405 | Apr 26 -Rs 37,821 | -Rs 61,062 |
| Liquidity 15+5 | S1k | 691 | 37% | **Rs 9,412** | Rs 1,332 / -Rs 748 | -Rs 6,659 | Apr 26 -Rs 19,546 | -Rs 69,257 |
| Liquidity 15+5 | K1S | 595 | 37% | **Rs 31,884** | Rs 1,390 / -Rs 747 | -Rs 6,018 | Apr 26 -Rs 18,510 | -Rs 59,018 |
| R72 Breadth Donchian | A | 2281 | 46% | **Rs 298,253** | Rs 1,763 / -Rs 1,279 | -Rs 25,837 | Dec 25 -Rs 72,605 | -Rs 86,529 |
| R72 Breadth Donchian | S1k | 2339 | 38% | **Rs 32,059** | Rs 1,423 / -Rs 853 | -Rs 14,533 | Dec 25 -Rs 73,918 | -Rs 103,625 |
| R72 Breadth Donchian | K1S | 2246 | 38% | **Rs 44,822** | Rs 1,442 / -Rs 851 | -Rs 14,533 | Dec 25 -Rs 69,807 | -Rs 107,659 |
| R72 Breadth Donchian | B | 1062 | 52% | **Rs 371,717** | Rs 2,019 / -Rs 1,449 | -Rs 25,837 | May 26 -Rs 20,632 | -Rs 34,940 |
| R72 Breadth Donchian | B+S1k | 1084 | 41% | **Rs 167,226** | Rs 1,609 / -Rs 870 | -Rs 13,283 | May 26 -Rs 21,707 | -Rs 58,956 |
| R72 Breadth Donchian | B+K1S | 1049 | 41% | **Rs 163,354** | Rs 1,616 / -Rs 863 | -Rs 13,283 | May 26 -Rs 23,378 | -Rs 71,067 |
| R51 Donchian 20 + Supertrend | A | 3084 | 45% | **Rs 221,238** | Rs 1,692 / -Rs 1,259 | -Rs 28,942 | Jun 26 -Rs 54,587 | -Rs 94,176 |
| R51 Donchian 20 + Supertrend | S1k | 3161 | 39% | **-Rs 8,398** | Rs 1,350 / -Rs 851 | -Rs 14,348 | Dec 25 -Rs 68,278 | -Rs 116,428 |
| R51 Donchian 20 + Supertrend | K1S | 3062 | 38% | **Rs 4,530** | Rs 1,354 / -Rs 845 | -Rs 14,348 | Dec 25 -Rs 58,814 | -Rs 124,578 |
| R51 Donchian 20 + Supertrend | B | 1361 | 51% | **Rs 293,395** | Rs 1,814 / -Rs 1,438 | -Rs 24,757 | Jun 26 -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+S1k | 1414 | 41% | **Rs 77,463** | Rs 1,400 / -Rs 895 | -Rs 12,179 | Sep 26 -Rs 30,558 | -Rs 70,818 |
| R51 Donchian 20 + Supertrend | B+K1S | 1330 | 41% | **Rs 97,498** | Rs 1,430 / -Rs 883 | -Rs 12,179 | Sep 26 -Rs 26,095 | -Rs 67,884 |
| All three | A | 6053 | 45% | **Rs 637,414** | Rs 1,744 / -Rs 1,230 | -Rs 53,710 | Dec 25 -Rs 129,283 | -Rs 159,077 |
| All three | S1k | 6191 | 38% | **Rs 33,073** | Rs 1,376 / -Rs 840 | -Rs 33,908 | Dec 25 -Rs 146,048 | -Rs 229,236 |
| All three | K1S | 5903 | 38% | **Rs 81,235** | Rs 1,391 / -Rs 837 | -Rs 29,913 | Dec 25 -Rs 125,946 | -Rs 238,950 |
| All three | B | 3111 | 49% | **Rs 783,034** | Rs 1,910 / -Rs 1,318 | -Rs 43,996 | Apr 26 -Rs 29,430 | -Rs 76,847 |
| All three | B+S1k | 3189 | 40% | **Rs 254,100** | Rs 1,460 / -Rs 853 | -Rs 23,550 | Apr 26 -Rs 48,012 | -Rs 153,301 |
| All three | B+K1S | 2974 | 40% | **Rs 292,735** | Rs 1,489 / -Rs 848 | -Rs 23,550 | Apr 26 -Rs 54,306 | -Rs 164,328 |

### early (20 Oct 2025 - 31 Mar 2026), 1 lot (110 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 316 | 42% | **Rs 113,873** | Rs 2,142 / -Rs 935 | -Rs 5,535 | Dec 25 -Rs 4,980 | -Rs 16,913 |
| Liquidity 15+5 | S1k | 319 | 41% | **Rs 32,445** | Rs 1,364 / -Rs 767 | -Rs 5,355 | Mar 26 -Rs 7,884 | -Rs 23,568 |
| Liquidity 15+5 | K1S | 281 | 42% | **Rs 42,862** | Rs 1,424 / -Rs 781 | -Rs 4,577 | Mar 26 -Rs 6,550 | -Rs 19,462 |
| R72 Breadth Donchian | A | 1056 | 47% | **Rs 128,541** | Rs 1,792 / -Rs 1,341 | -Rs 25,837 | Dec 25 -Rs 72,605 | -Rs 86,529 |
| R72 Breadth Donchian | S1k | 1090 | 38% | **Rs 2,704** | Rs 1,361 / -Rs 817 | -Rs 14,533 | Dec 25 -Rs 73,918 | -Rs 78,019 |
| R72 Breadth Donchian | K1S | 1049 | 38% | **Rs 11,492** | Rs 1,375 / -Rs 823 | -Rs 14,533 | Dec 25 -Rs 69,807 | -Rs 74,299 |
| R72 Breadth Donchian | B | 452 | 55% | **Rs 200,162** | Rs 2,153 / -Rs 1,673 | -Rs 25,837 | Dec 25 -Rs 1,721 | -Rs 34,940 |
| R72 Breadth Donchian | B+S1k | 455 | 43% | **Rs 91,352** | Rs 1,589 / -Rs 831 | -Rs 9,963 | Mar 26 -Rs 10,292 | -Rs 31,008 |
| R72 Breadth Donchian | B+K1S | 444 | 43% | **Rs 92,741** | Rs 1,617 / -Rs 835 | -Rs 9,963 | Mar 26 -Rs 13,016 | -Rs 33,733 |
| R51 Donchian 20 + Supertrend | A | 1370 | 45% | **Rs 86,280** | Rs 1,744 / -Rs 1,326 | -Rs 28,942 | Dec 25 -Rs 51,699 | -Rs 94,176 |
| R51 Donchian 20 + Supertrend | S1k | 1409 | 38% | **-Rs 26,238** | Rs 1,290 / -Rs 837 | -Rs 14,348 | Dec 25 -Rs 68,278 | -Rs 78,372 |
| R51 Donchian 20 + Supertrend | K1S | 1367 | 39% | **-Rs 11,092** | Rs 1,306 / -Rs 833 | -Rs 14,348 | Dec 25 -Rs 58,814 | -Rs 76,393 |
| R51 Donchian 20 + Supertrend | B | 691 | 51% | **Rs 156,237** | Rs 1,905 / -Rs 1,537 | -Rs 24,757 | Dec 25 -Rs 904 | -Rs 29,931 |
| R51 Donchian 20 + Supertrend | B+S1k | 712 | 42% | **Rs 64,035** | Rs 1,412 / -Rs 884 | -Rs 12,179 | Dec 25 -Rs 21,744 | -Rs 32,157 |
| R51 Donchian 20 + Supertrend | B+K1S | 672 | 42% | **Rs 65,263** | Rs 1,455 / -Rs 878 | -Rs 12,179 | Dec 25 -Rs 20,188 | -Rs 33,112 |
| All three | A | 2742 | 45% | **Rs 328,694** | Rs 1,805 / -Rs 1,284 | -Rs 51,650 | Dec 25 -Rs 129,283 | -Rs 159,077 |
| All three | S1k | 2818 | 38% | **Rs 8,910** | Rs 1,326 / -Rs 821 | -Rs 29,913 | Dec 25 -Rs 146,048 | -Rs 158,478 |
| All three | K1S | 2697 | 39% | **Rs 43,262** | Rs 1,346 / -Rs 824 | -Rs 29,913 | Dec 25 -Rs 125,946 | -Rs 135,115 |
| All three | B | 1459 | 51% | **Rs 470,271** | Rs 2,032 / -Rs 1,423 | -Rs 43,996 | Dec 25 -Rs 7,604 | -Rs 60,566 |
| All three | B+S1k | 1486 | 42% | **Rs 187,832** | Rs 1,457 / -Rs 842 | -Rs 21,893 | Mar 26 -Rs 33,288 | -Rs 70,559 |
| All three | B+K1S | 1397 | 42% | **Rs 200,867** | Rs 1,501 / -Rs 845 | -Rs 22,455 | Mar 26 -Rs 36,241 | -Rs 73,512 |

### final (1 Apr - 13 Oct 2026), 1 lot (133 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 357 | 37% | **Rs 11,155** | Rs 1,762 / -Rs 997 | -Rs 11,405 | Apr 26 -Rs 37,821 | -Rs 61,062 |
| Liquidity 15+5 | S1k | 357 | 34% | **-Rs 14,465** | Rs 1,307 / -Rs 740 | -Rs 6,018 | Apr 26 -Rs 19,546 | -Rs 46,993 |
| Liquidity 15+5 | K1S | 305 | 34% | **-Rs 5,614** | Rs 1,351 / -Rs 727 | -Rs 6,018 | Apr 26 -Rs 18,510 | -Rs 40,859 |
| R72 Breadth Donchian | A | 1200 | 46% | **Rs 170,336** | Rs 1,748 / -Rs 1,217 | -Rs 22,571 | Jun 26 -Rs 40,908 | -Rs 61,884 |
| R72 Breadth Donchian | S1k | 1222 | 38% | **Rs 36,507** | Rs 1,492 / -Rs 881 | -Rs 14,123 | Jun 26 -Rs 39,105 | -Rs 68,598 |
| R72 Breadth Donchian | K1S | 1173 | 38% | **Rs 43,167** | Rs 1,515 / -Rs 870 | -Rs 11,707 | Jun 26 -Rs 33,808 | -Rs 64,273 |
| R72 Breadth Donchian | B | 602 | 49% | **Rs 170,735** | Rs 1,920 / -Rs 1,299 | -Rs 21,301 | May 26 -Rs 20,632 | -Rs 31,620 |
| R72 Breadth Donchian | B+S1k | 621 | 40% | **Rs 79,188** | Rs 1,634 / -Rs 895 | -Rs 13,283 | May 26 -Rs 21,707 | -Rs 29,536 |
| R72 Breadth Donchian | B+K1S | 597 | 40% | **Rs 73,926** | Rs 1,627 / -Rs 880 | -Rs 13,283 | May 26 -Rs 23,378 | -Rs 38,923 |
| R51 Donchian 20 + Supertrend | A | 1668 | 45% | **Rs 131,546** | Rs 1,648 / -Rs 1,197 | -Rs 21,578 | Jun 26 -Rs 54,587 | -Rs 81,683 |
| R51 Donchian 20 + Supertrend | S1k | 1702 | 39% | **Rs 24,964** | Rs 1,401 / -Rs 859 | -Rs 13,640 | Jun 26 -Rs 45,868 | -Rs 63,802 |
| R51 Donchian 20 + Supertrend | K1S | 1646 | 39% | **Rs 28,840** | Rs 1,399 / -Rs 852 | -Rs 13,640 | Jun 26 -Rs 46,605 | -Rs 61,512 |
| R51 Donchian 20 + Supertrend | B | 652 | 50% | **Rs 143,945** | Rs 1,733 / -Rs 1,320 | -Rs 14,642 | Jun 26 -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+S1k | 683 | 41% | **Rs 23,689** | Rs 1,392 / -Rs 903 | -Rs 11,007 | Sep 26 -Rs 30,558 | -Rs 42,804 |
| R51 Donchian 20 + Supertrend | B+K1S | 640 | 41% | **Rs 41,400** | Rs 1,409 / -Rs 885 | -Rs 11,007 | Sep 26 -Rs 26,095 | -Rs 39,477 |
| All three | A | 3225 | 44% | **Rs 313,037** | Rs 1,697 / -Rs 1,179 | -Rs 53,710 | Jun 26 -Rs 96,444 | -Rs 139,134 |
| All three | S1k | 3281 | 38% | **Rs 47,006** | Rs 1,426 / -Rs 853 | -Rs 26,424 | Jun 26 -Rs 91,177 | -Rs 141,293 |
| All three | K1S | 3124 | 38% | **Rs 66,393** | Rs 1,438 / -Rs 846 | -Rs 26,424 | Jun 26 -Rs 90,734 | -Rs 137,979 |
| All three | B | 1611 | 47% | **Rs 325,835** | Rs 1,811 / -Rs 1,228 | -Rs 39,870 | Apr 26 -Rs 29,430 | -Rs 68,921 |
| All three | B+S1k | 1661 | 39% | **Rs 88,412** | Rs 1,469 / -Rs 862 | -Rs 23,550 | Apr 26 -Rs 48,012 | -Rs 82,741 |
| All three | B+K1S | 1542 | 39% | **Rs 109,713** | Rs 1,485 / -Rs 849 | -Rs 23,550 | Apr 26 -Rs 54,306 | -Rs 90,816 |

### last 6 months (20 Apr - 19 Oct 2026), 1 lot (126 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 346 | 38% | **Rs 28,392** | Rs 1,750 / -Rs 934 | -Rs 11,405 | Oct 26 -Rs 15,384 | -Rs 38,009 |
| Liquidity 15+5 | S1k | 346 | 34% | **-Rs 10,761** | Rs 1,292 / -Rs 716 | -Rs 6,659 | May 26 -Rs 17,118 | -Rs 37,254 |
| Liquidity 15+5 | K1S | 291 | 34% | **-Rs 583** | Rs 1,336 / -Rs 703 | -Rs 6,018 | May 26 -Rs 11,611 | -Rs 32,997 |
| R72 Breadth Donchian | A | 1116 | 47% | **Rs 189,980** | Rs 1,717 / -Rs 1,179 | -Rs 22,571 | Jun 26 -Rs 40,908 | -Rs 61,884 |
| R72 Breadth Donchian | S1k | 1139 | 39% | **Rs 54,761** | Rs 1,471 / -Rs 867 | -Rs 14,123 | Jun 26 -Rs 39,105 | -Rs 68,598 |
| R72 Breadth Donchian | K1S | 1088 | 39% | **Rs 59,464** | Rs 1,503 / -Rs 856 | -Rs 12,602 | Jun 26 -Rs 33,808 | -Rs 62,932 |
| R72 Breadth Donchian | B | 537 | 50% | **Rs 181,614** | Rs 1,862 / -Rs 1,214 | -Rs 17,394 | May 26 -Rs 20,632 | -Rs 31,620 |
| R72 Breadth Donchian | B+S1k | 549 | 41% | **Rs 84,406** | Rs 1,624 / -Rs 883 | -Rs 8,493 | May 26 -Rs 21,707 | -Rs 26,658 |
| R72 Breadth Donchian | B+K1S | 527 | 41% | **Rs 82,877** | Rs 1,618 / -Rs 865 | -Rs 8,493 | May 26 -Rs 23,378 | -Rs 27,307 |
| R51 Donchian 20 + Supertrend | A | 1584 | 45% | **Rs 148,408** | Rs 1,609 / -Rs 1,166 | -Rs 21,578 | Jun 26 -Rs 54,587 | -Rs 81,683 |
| R51 Donchian 20 + Supertrend | S1k | 1622 | 39% | **Rs 57,185** | Rs 1,405 / -Rs 846 | -Rs 13,521 | Jun 26 -Rs 45,868 | -Rs 63,802 |
| R51 Donchian 20 + Supertrend | K1S | 1566 | 39% | **Rs 58,379** | Rs 1,405 / -Rs 838 | -Rs 13,462 | Jun 26 -Rs 46,605 | -Rs 61,512 |
| R51 Donchian 20 + Supertrend | B | 598 | 51% | **Rs 147,723** | Rs 1,662 / -Rs 1,236 | -Rs 14,642 | Jun 26 -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+S1k | 629 | 41% | **Rs 41,307** | Rs 1,391 / -Rs 874 | -Rs 11,007 | Sep 26 -Rs 30,558 | -Rs 42,804 |
| R51 Donchian 20 + Supertrend | B+K1S | 585 | 42% | **Rs 60,114** | Rs 1,410 / -Rs 852 | -Rs 11,007 | Sep 26 -Rs 26,095 | -Rs 39,477 |
| All three | A | 3046 | 45% | **Rs 366,780** | Rs 1,664 / -Rs 1,141 | -Rs 53,710 | Jun 26 -Rs 96,444 | -Rs 139,134 |
| All three | S1k | 3107 | 39% | **Rs 101,186** | Rs 1,418 / -Rs 838 | -Rs 33,908 | Jun 26 -Rs 91,177 | -Rs 141,293 |
| All three | K1S | 2945 | 38% | **Rs 117,260** | Rs 1,435 / -Rs 830 | -Rs 29,406 | Jun 26 -Rs 90,734 | -Rs 132,031 |
| All three | B | 1481 | 48% | **Rs 357,730** | Rs 1,755 / -Rs 1,145 | -Rs 39,870 | Jun 26 -Rs 21,248 | -Rs 59,893 |
| All three | B+S1k | 1524 | 40% | **Rs 114,952** | Rs 1,459 / -Rs 838 | -Rs 20,672 | May 26 -Rs 27,804 | -Rs 79,736 |
| All three | B+K1S | 1403 | 40% | **Rs 142,408** | Rs 1,477 / -Rs 823 | -Rs 20,672 | May 26 -Rs 19,396 | -Rs 76,307 |

### whole year (20 Oct 2025 - 19 Oct 2026), 3 lots (247 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 688 | 41% | **Rs 415,755** | Rs 5,645 / -Rs 2,897 | -Rs 33,486 | Apr 26 -Rs 108,688 | -Rs 175,017 |
| Liquidity 15+5 | S1k | 691 | 38% | **Rs 92,460** | Rs 3,978 / -Rs 2,185 | -Rs 19,380 | Apr 26 -Rs 51,756 | -Rs 184,684 |
| Liquidity 15+5 | K1S | 595 | 38% | **Rs 151,236** | Rs 4,167 / -Rs 2,177 | -Rs 17,309 | Apr 26 -Rs 49,615 | -Rs 154,806 |
| R72 Breadth Donchian | A | 2281 | 47% | **Rs 1,104,831** | Rs 5,312 / -Rs 3,788 | -Rs 76,355 | Dec 25 -Rs 197,761 | -Rs 242,618 |
| R72 Breadth Donchian | S1k | 2339 | 39% | **Rs 311,408** | Rs 4,313 / -Rs 2,486 | -Rs 41,900 | Dec 25 -Rs 201,464 | -Rs 224,362 |
| R72 Breadth Donchian | K1S | 2246 | 38% | **Rs 340,953** | Rs 4,363 / -Rs 2,481 | -Rs 41,900 | Dec 25 -Rs 190,942 | -Rs 237,038 |
| R72 Breadth Donchian | B | 1062 | 53% | **Rs 1,212,701** | Rs 6,072 / -Rs 4,316 | -Rs 76,355 | May 26 -Rs 48,023 | -Rs 100,468 |
| R72 Breadth Donchian | B+S1k | 1084 | 42% | **Rs 601,137** | Rs 4,886 / -Rs 2,531 | -Rs 38,527 | May 26 -Rs 50,663 | -Rs 148,725 |
| R72 Breadth Donchian | B+K1S | 1049 | 41% | **Rs 586,289** | Rs 4,896 / -Rs 2,514 | -Rs 38,527 | May 26 -Rs 56,337 | -Rs 175,474 |
| R51 Donchian 20 + Supertrend | A | 3084 | 46% | **Rs 946,603** | Rs 5,069 / -Rs 3,746 | -Rs 85,577 | Jun 26 -Rs 138,454 | -Rs 263,933 |
| R51 Donchian 20 + Supertrend | S1k | 3161 | 39% | **Rs 262,791** | Rs 4,094 / -Rs 2,479 | -Rs 41,273 | Dec 25 -Rs 178,495 | -Rs 288,173 |
| R51 Donchian 20 + Supertrend | K1S | 3062 | 39% | **Rs 292,221** | Rs 4,096 / -Rs 2,465 | -Rs 41,273 | Dec 25 -Rs 151,601 | -Rs 313,868 |
| R51 Donchian 20 + Supertrend | B | 1361 | 51% | **Rs 1,004,440** | Rs 5,470 / -Rs 4,274 | -Rs 73,545 | Jun 26 -Rs 62,761 | -Rs 108,826 |
| R51 Donchian 20 + Supertrend | B+S1k | 1414 | 42% | **Rs 361,624** | Rs 4,250 / -Rs 2,612 | -Rs 36,007 | Sep 26 -Rs 78,902 | -Rs 191,627 |
| R51 Donchian 20 + Supertrend | B+K1S | 1330 | 42% | **Rs 413,924** | Rs 4,320 / -Rs 2,586 | -Rs 36,007 | Sep 26 -Rs 66,440 | -Rs 183,570 |
| All three | A | 6053 | 46% | **Rs 2,467,188** | Rs 5,221 / -Rs 3,657 | -Rs 158,555 | Dec 25 -Rs 336,310 | -Rs 432,666 |
| All three | S1k | 6191 | 39% | **Rs 666,659** | Rs 4,164 / -Rs 2,448 | -Rs 99,043 | Dec 25 -Rs 385,725 | -Rs 476,924 |
| All three | K1S | 5903 | 39% | **Rs 784,409** | Rs 4,204 / -Rs 2,442 | -Rs 85,895 | Dec 25 -Rs 329,746 | -Rs 489,148 |
| All three | B | 3111 | 50% | **Rs 2,632,895** | Rs 5,720 / -Rs 3,932 | -Rs 130,335 | Apr 26 -Rs 61,992 | -Rs 199,852 |
| All three | B+S1k | 3189 | 41% | **Rs 1,055,221** | Rs 4,416 / -Rs 2,488 | -Rs 68,026 | Apr 26 -Rs 114,573 | -Rs 391,912 |
| All three | B+K1S | 2974 | 41% | **Rs 1,151,448** | Rs 4,497 / -Rs 2,475 | -Rs 68,026 | Apr 26 -Rs 135,648 | -Rs 410,526 |


## Amendment C: Rs 1,500 initial stop, up Rs 500 per Rs 1,000 gain (SC alone, K1SC with K1)

In the tables below 'S1k' = SC (entry stop -Rs 1,500 a lot where tighter than -15%; for every Rs 1,000 a lot of best profit the stop rises Rs 500 a lot: -1,000 at +1,000, -500 at +2,000, cost at +3,000, ...) and 'K1S' = K1SC (K1 + SC). The 'entry stop' exit column is SC's -Rs 1,500 stop. BH m = 4,012.

| arm : rule | passed | passes except account stop | twin p | BH q | q within the 6 | failed gates |
|---|---|---|---|---|---|---|
| LIQ:SC | **no** | no | 1.0 | 1.0 | 1.0 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in final, six, year |
| R72:SC | **no** | no | 1.0 | 1.0 | 1.0 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R51:SC | **no** | no | 1.0 | 1.0 | 1.0 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| LIQ:K1SC | **no** | no | 0.02 | 1.0 | 0.12 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in final, year |
| R72:K1SC | **no** | no | 0.4351 | 1.0 | 1.0 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R51:K1SC | **no** | no | 0.8523 | 1.0 | 1.0 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |

### whole year (20 Oct 2025 - 19 Oct 2026), 1 lot (247 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 688 | 39% | **Rs 117,923** | Rs 1,935 / -Rs 968 | -Rs 11,405 | Apr 26 -Rs 37,821 | -Rs 61,062 |
| Liquidity 15+5 | S1k | 688 | 37% | **Rs 77,921** | Rs 1,796 / -Rs 890 | -Rs 7,926 | Apr 26 -Rs 27,066 | -Rs 75,202 |
| Liquidity 15+5 | K1S | 592 | 38% | **Rs 103,686** | Rs 1,906 / -Rs 886 | -Rs 7,926 | Apr 26 -Rs 25,303 | -Rs 63,635 |
| R72 Breadth Donchian | A | 2281 | 46% | **Rs 298,253** | Rs 1,763 / -Rs 1,279 | -Rs 25,837 | Dec 25 -Rs 72,605 | -Rs 86,529 |
| R72 Breadth Donchian | S1k | 2283 | 42% | **Rs 178,703** | Rs 1,757 / -Rs 1,122 | -Rs 20,909 | Dec 25 -Rs 63,000 | -Rs 71,862 |
| R72 Breadth Donchian | K1S | 2187 | 42% | **Rs 175,098** | Rs 1,765 / -Rs 1,127 | -Rs 20,909 | Dec 25 -Rs 61,498 | -Rs 81,526 |
| R72 Breadth Donchian | B | 1062 | 52% | **Rs 371,717** | Rs 2,019 / -Rs 1,449 | -Rs 25,837 | May 26 -Rs 20,632 | -Rs 34,940 |
| R72 Breadth Donchian | B+S1k | 1063 | 44% | **Rs 242,940** | Rs 2,029 / -Rs 1,198 | -Rs 22,377 | Mar 26 -Rs 17,422 | -Rs 66,516 |
| R72 Breadth Donchian | B+K1S | 1026 | 44% | **Rs 225,432** | Rs 2,004 / -Rs 1,202 | -Rs 22,377 | Mar 26 -Rs 28,572 | -Rs 80,272 |
| R51 Donchian 20 + Supertrend | A | 3084 | 45% | **Rs 221,238** | Rs 1,692 / -Rs 1,259 | -Rs 28,942 | Jun 26 -Rs 54,587 | -Rs 94,176 |
| R51 Donchian 20 + Supertrend | S1k | 3098 | 41% | **Rs 100,137** | Rs 1,678 / -Rs 1,100 | -Rs 20,872 | Feb 26 -Rs 46,350 | -Rs 124,370 |
| R51 Donchian 20 + Supertrend | K1S | 2999 | 41% | **Rs 77,488** | Rs 1,671 / -Rs 1,096 | -Rs 20,872 | Feb 26 -Rs 57,187 | -Rs 147,022 |
| R51 Donchian 20 + Supertrend | B | 1361 | 51% | **Rs 293,395** | Rs 1,814 / -Rs 1,438 | -Rs 24,757 | Jun 26 -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+S1k | 1374 | 43% | **Rs 139,546** | Rs 1,777 / -Rs 1,186 | -Rs 15,963 | Mar 26 -Rs 22,906 | -Rs 74,854 |
| R51 Donchian 20 + Supertrend | B+K1S | 1289 | 43% | **Rs 140,591** | Rs 1,782 / -Rs 1,172 | -Rs 15,963 | Mar 26 -Rs 31,918 | -Rs 83,865 |
| All three | A | 6053 | 45% | **Rs 637,414** | Rs 1,744 / -Rs 1,230 | -Rs 53,710 | Dec 25 -Rs 129,283 | -Rs 159,077 |
| All three | S1k | 6069 | 41% | **Rs 356,760** | Rs 1,721 / -Rs 1,083 | -Rs 41,828 | Dec 25 -Rs 99,226 | -Rs 193,355 |
| All three | K1S | 5778 | 41% | **Rs 356,272** | Rs 1,730 / -Rs 1,085 | -Rs 41,828 | Apr 26 -Rs 97,885 | -Rs 223,089 |
| All three | B | 3111 | 49% | **Rs 783,034** | Rs 1,910 / -Rs 1,318 | -Rs 43,996 | Apr 26 -Rs 29,430 | -Rs 76,847 |
| All three | B+S1k | 3125 | 42% | **Rs 460,407** | Rs 1,870 / -Rs 1,119 | -Rs 38,388 | Apr 26 -Rs 53,305 | -Rs 172,332 |
| All three | B+K1S | 2907 | 43% | **Rs 469,708** | Rs 1,886 / -Rs 1,120 | -Rs 38,388 | Mar 26 -Rs 69,844 | -Rs 187,036 |

### early (20 Oct 2025 - 31 Mar 2026), 1 lot (110 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 316 | 42% | **Rs 113,873** | Rs 2,142 / -Rs 935 | -Rs 5,535 | Dec 25 -Rs 4,980 | -Rs 16,913 |
| Liquidity 15+5 | S1k | 316 | 39% | **Rs 66,452** | Rs 1,892 / -Rs 876 | -Rs 6,918 | Mar 26 -Rs 11,191 | -Rs 25,803 |
| Liquidity 15+5 | K1S | 278 | 41% | **Rs 82,014** | Rs 2,016 / -Rs 884 | -Rs 5,562 | Mar 26 -Rs 9,354 | -Rs 21,868 |
| R72 Breadth Donchian | A | 1056 | 47% | **Rs 128,541** | Rs 1,792 / -Rs 1,341 | -Rs 25,837 | Dec 25 -Rs 72,605 | -Rs 86,529 |
| R72 Breadth Donchian | S1k | 1056 | 41% | **Rs 75,123** | Rs 1,756 / -Rs 1,081 | -Rs 15,720 | Dec 25 -Rs 63,000 | -Rs 71,543 |
| R72 Breadth Donchian | K1S | 1011 | 41% | **Rs 76,424** | Rs 1,746 / -Rs 1,092 | -Rs 15,720 | Dec 25 -Rs 61,498 | -Rs 66,084 |
| R72 Breadth Donchian | B | 452 | 55% | **Rs 200,162** | Rs 2,153 / -Rs 1,673 | -Rs 25,837 | Dec 25 -Rs 1,721 | -Rs 34,940 |
| R72 Breadth Donchian | B+S1k | 451 | 43% | **Rs 114,346** | Rs 2,143 / -Rs 1,199 | -Rs 15,720 | Mar 26 -Rs 17,422 | -Rs 33,779 |
| R72 Breadth Donchian | B+K1S | 439 | 43% | **Rs 98,148** | Rs 2,094 / -Rs 1,204 | -Rs 15,720 | Mar 26 -Rs 28,572 | -Rs 46,027 |
| R51 Donchian 20 + Supertrend | A | 1370 | 45% | **Rs 86,280** | Rs 1,744 / -Rs 1,326 | -Rs 28,942 | Dec 25 -Rs 51,699 | -Rs 94,176 |
| R51 Donchian 20 + Supertrend | S1k | 1377 | 40% | **Rs 35,380** | Rs 1,689 / -Rs 1,091 | -Rs 18,679 | Feb 26 -Rs 46,350 | -Rs 98,375 |
| R51 Donchian 20 + Supertrend | K1S | 1333 | 40% | **Rs 33,007** | Rs 1,706 / -Rs 1,081 | -Rs 18,679 | Feb 26 -Rs 57,187 | -Rs 107,093 |
| R51 Donchian 20 + Supertrend | B | 691 | 51% | **Rs 156,237** | Rs 1,905 / -Rs 1,537 | -Rs 24,757 | Dec 25 -Rs 904 | -Rs 29,931 |
| R51 Donchian 20 + Supertrend | B+S1k | 695 | 43% | **Rs 75,374** | Rs 1,802 / -Rs 1,178 | -Rs 13,692 | Mar 26 -Rs 22,906 | -Rs 34,024 |
| R51 Donchian 20 + Supertrend | B+K1S | 652 | 42% | **Rs 60,744** | Rs 1,804 / -Rs 1,171 | -Rs 13,692 | Mar 26 -Rs 31,918 | -Rs 43,036 |
| All three | A | 2742 | 45% | **Rs 328,694** | Rs 1,805 / -Rs 1,284 | -Rs 51,650 | Dec 25 -Rs 129,283 | -Rs 159,077 |
| All three | S1k | 2749 | 40% | **Rs 176,955** | Rs 1,738 / -Rs 1,062 | -Rs 32,665 | Dec 25 -Rs 99,226 | -Rs 123,159 |
| All three | K1S | 2622 | 40% | **Rs 191,446** | Rs 1,755 / -Rs 1,065 | -Rs 32,665 | Dec 25 -Rs 78,879 | -Rs 122,285 |
| All three | B | 1459 | 51% | **Rs 470,271** | Rs 2,032 / -Rs 1,423 | -Rs 43,996 | Dec 25 -Rs 7,604 | -Rs 60,566 |
| All three | B+S1k | 1462 | 42% | **Rs 256,172** | Rs 1,928 / -Rs 1,115 | -Rs 22,353 | Mar 26 -Rs 51,520 | -Rs 75,837 |
| All three | B+K1S | 1369 | 42% | **Rs 240,906** | Rs 1,940 / -Rs 1,121 | -Rs 22,894 | Mar 26 -Rs 69,844 | -Rs 94,162 |

### final (1 Apr - 13 Oct 2026), 1 lot (133 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 357 | 37% | **Rs 11,155** | Rs 1,762 / -Rs 997 | -Rs 11,405 | Apr 26 -Rs 37,821 | -Rs 61,062 |
| Liquidity 15+5 | S1k | 357 | 36% | **Rs 17,022** | Rs 1,734 / -Rs 907 | -Rs 7,926 | Apr 26 -Rs 27,066 | -Rs 53,006 |
| Liquidity 15+5 | K1S | 305 | 37% | **Rs 27,105** | Rs 1,795 / -Rs 901 | -Rs 7,926 | Apr 26 -Rs 25,303 | -Rs 45,375 |
| R72 Breadth Donchian | A | 1200 | 46% | **Rs 170,336** | Rs 1,748 / -Rs 1,217 | -Rs 22,571 | Jun 26 -Rs 40,908 | -Rs 61,884 |
| R72 Breadth Donchian | S1k | 1202 | 43% | **Rs 110,694** | Rs 1,766 / -Rs 1,150 | -Rs 20,909 | Jun 26 -Rs 39,230 | -Rs 62,180 |
| R72 Breadth Donchian | K1S | 1155 | 42% | **Rs 111,013** | Rs 1,791 / -Rs 1,148 | -Rs 20,909 | Jun 26 -Rs 43,141 | -Rs 61,146 |
| R72 Breadth Donchian | B | 602 | 49% | **Rs 170,735** | Rs 1,920 / -Rs 1,299 | -Rs 21,301 | May 26 -Rs 20,632 | -Rs 31,620 |
| R72 Breadth Donchian | B+S1k | 604 | 45% | **Rs 136,322** | Rs 1,952 / -Rs 1,189 | -Rs 22,377 | May 26 -Rs 15,304 | -Rs 36,395 |
| R72 Breadth Donchian | B+K1S | 579 | 45% | **Rs 135,011** | Rs 1,945 / -Rs 1,191 | -Rs 22,377 | May 26 -Rs 18,078 | -Rs 39,001 |
| R51 Donchian 20 + Supertrend | A | 1668 | 45% | **Rs 131,546** | Rs 1,648 / -Rs 1,197 | -Rs 21,578 | Jun 26 -Rs 54,587 | -Rs 81,683 |
| R51 Donchian 20 + Supertrend | S1k | 1675 | 41% | **Rs 68,417** | Rs 1,665 / -Rs 1,102 | -Rs 20,872 | Jun 26 -Rs 42,227 | -Rs 65,146 |
| R51 Donchian 20 + Supertrend | K1S | 1621 | 41% | **Rs 55,392** | Rs 1,642 / -Rs 1,102 | -Rs 20,872 | Jun 26 -Rs 43,371 | -Rs 67,226 |
| R51 Donchian 20 + Supertrend | B | 652 | 50% | **Rs 143,945** | Rs 1,733 / -Rs 1,320 | -Rs 14,642 | Jun 26 -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+S1k | 661 | 44% | **Rs 79,153** | Rs 1,762 / -Rs 1,180 | -Rs 15,963 | Apr 26 -Rs 18,216 | -Rs 40,830 |
| R51 Donchian 20 + Supertrend | B+K1S | 620 | 45% | **Rs 93,233** | Rs 1,773 / -Rs 1,160 | -Rs 15,963 | Apr 26 -Rs 20,511 | -Rs 40,830 |
| All three | A | 3225 | 44% | **Rs 313,037** | Rs 1,697 / -Rs 1,179 | -Rs 53,710 | Jun 26 -Rs 96,444 | -Rs 139,134 |
| All three | S1k | 3234 | 41% | **Rs 196,133** | Rs 1,710 / -Rs 1,096 | -Rs 41,828 | Jun 26 -Rs 82,824 | -Rs 134,767 |
| All three | K1S | 3081 | 41% | **Rs 193,510** | Rs 1,713 / -Rs 1,097 | -Rs 41,828 | Apr 26 -Rs 97,885 | -Rs 139,737 |
| All three | B | 1611 | 47% | **Rs 325,835** | Rs 1,811 / -Rs 1,228 | -Rs 39,870 | Apr 26 -Rs 29,430 | -Rs 68,921 |
| All three | B+S1k | 1622 | 43% | **Rs 232,497** | Rs 1,832 / -Rs 1,116 | -Rs 38,388 | Apr 26 -Rs 53,305 | -Rs 96,495 |
| All three | B+K1S | 1504 | 43% | **Rs 255,348** | Rs 1,846 / -Rs 1,113 | -Rs 38,388 | Apr 26 -Rs 61,346 | -Rs 92,874 |

### last 6 months (20 Apr - 19 Oct 2026), 1 lot (126 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 346 | 38% | **Rs 28,392** | Rs 1,750 / -Rs 934 | -Rs 11,405 | Oct 26 -Rs 15,384 | -Rs 38,009 |
| Liquidity 15+5 | S1k | 346 | 37% | **Rs 28,975** | Rs 1,712 / -Rs 872 | -Rs 7,926 | May 26 -Rs 13,794 | -Rs 37,533 |
| Liquidity 15+5 | K1S | 291 | 37% | **Rs 37,215** | Rs 1,795 / -Rs 856 | -Rs 7,926 | Apr 26 -Rs 9,760 | -Rs 31,865 |
| R72 Breadth Donchian | A | 1116 | 47% | **Rs 189,980** | Rs 1,717 / -Rs 1,179 | -Rs 22,571 | Jun 26 -Rs 40,908 | -Rs 61,884 |
| R72 Breadth Donchian | S1k | 1117 | 44% | **Rs 145,124** | Rs 1,731 / -Rs 1,117 | -Rs 19,181 | Jun 26 -Rs 39,230 | -Rs 62,180 |
| R72 Breadth Donchian | K1S | 1067 | 43% | **Rs 144,028** | Rs 1,758 / -Rs 1,114 | -Rs 15,691 | Jun 26 -Rs 43,141 | -Rs 59,117 |
| R72 Breadth Donchian | B | 537 | 50% | **Rs 181,614** | Rs 1,862 / -Rs 1,214 | -Rs 17,394 | May 26 -Rs 20,632 | -Rs 31,620 |
| R72 Breadth Donchian | B+S1k | 537 | 47% | **Rs 148,820** | Rs 1,882 / -Rs 1,142 | -Rs 13,937 | May 26 -Rs 15,304 | -Rs 25,172 |
| R72 Breadth Donchian | B+K1S | 514 | 47% | **Rs 150,176** | Rs 1,880 / -Rs 1,143 | -Rs 12,849 | May 26 -Rs 18,078 | -Rs 28,827 |
| R51 Donchian 20 + Supertrend | A | 1584 | 45% | **Rs 148,408** | Rs 1,609 / -Rs 1,166 | -Rs 21,578 | Jun 26 -Rs 54,587 | -Rs 81,683 |
| R51 Donchian 20 + Supertrend | S1k | 1591 | 42% | **Rs 115,514** | Rs 1,634 / -Rs 1,075 | -Rs 15,977 | Jun 26 -Rs 42,227 | -Rs 65,146 |
| R51 Donchian 20 + Supertrend | K1S | 1537 | 42% | **Rs 100,215** | Rs 1,609 / -Rs 1,072 | -Rs 15,853 | Jun 26 -Rs 43,371 | -Rs 67,226 |
| R51 Donchian 20 + Supertrend | B | 598 | 51% | **Rs 147,723** | Rs 1,662 / -Rs 1,236 | -Rs 14,642 | Jun 26 -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+S1k | 607 | 46% | **Rs 101,356** | Rs 1,690 / -Rs 1,128 | -Rs 10,045 | Sep 26 -Rs 14,128 | -Rs 37,043 |
| R51 Donchian 20 + Supertrend | B+K1S | 565 | 47% | **Rs 117,031** | Rs 1,697 / -Rs 1,099 | -Rs 10,045 | Sep 26 -Rs 11,517 | -Rs 34,728 |
| All three | A | 3046 | 45% | **Rs 366,780** | Rs 1,664 / -Rs 1,141 | -Rs 53,710 | Jun 26 -Rs 96,444 | -Rs 139,134 |
| All three | S1k | 3054 | 42% | **Rs 289,613** | Rs 1,678 / -Rs 1,065 | -Rs 39,471 | Jun 26 -Rs 82,824 | -Rs 118,161 |
| All three | K1S | 2895 | 42% | **Rs 281,459** | Rs 1,682 / -Rs 1,064 | -Rs 39,471 | Jun 26 -Rs 94,046 | -Rs 117,362 |
| All three | B | 1481 | 48% | **Rs 357,730** | Rs 1,755 / -Rs 1,145 | -Rs 39,870 | Jun 26 -Rs 21,248 | -Rs 59,893 |
| All three | B+S1k | 1490 | 44% | **Rs 279,150** | Rs 1,768 / -Rs 1,066 | -Rs 26,624 | Jun 26 -Rs 11,358 | -Rs 71,187 |
| All three | B+K1S | 1370 | 45% | **Rs 304,422** | Rs 1,787 / -Rs 1,056 | -Rs 26,624 | Jun 26 -Rs 19,209 | -Rs 61,752 |

### whole year (20 Oct 2025 - 19 Oct 2026), 3 lots (247 sessions)

| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 688 | 41% | **Rs 415,755** | Rs 5,645 / -Rs 2,897 | -Rs 33,486 | Apr 26 -Rs 108,688 | -Rs 175,017 |
| Liquidity 15+5 | S1k | 688 | 39% | **Rs 294,249** | Rs 5,273 / -Rs 2,645 | -Rs 23,023 | Apr 26 -Rs 76,396 | -Rs 207,153 |
| Liquidity 15+5 | K1S | 592 | 39% | **Rs 362,887** | Rs 5,610 / -Rs 2,630 | -Rs 23,023 | Apr 26 -Rs 72,093 | -Rs 178,652 |
| R72 Breadth Donchian | A | 2281 | 47% | **Rs 1,104,831** | Rs 5,312 / -Rs 3,788 | -Rs 76,355 | Dec 25 -Rs 197,761 | -Rs 242,618 |
| R72 Breadth Donchian | S1k | 2283 | 42% | **Rs 745,984** | Rs 5,286 / -Rs 3,311 | -Rs 61,184 | Dec 25 -Rs 169,025 | -Rs 204,486 |
| R72 Breadth Donchian | K1S | 2187 | 42% | **Rs 726,204** | Rs 5,311 / -Rs 3,324 | -Rs 61,184 | Dec 25 -Rs 166,302 | -Rs 214,629 |
| R72 Breadth Donchian | B | 1062 | 53% | **Rs 1,212,701** | Rs 6,072 / -Rs 4,316 | -Rs 76,355 | May 26 -Rs 48,023 | -Rs 100,468 |
| R72 Breadth Donchian | B+S1k | 1063 | 45% | **Rs 826,492** | Rs 6,075 / -Rs 3,551 | -Rs 65,854 | Mar 26 -Rs 36,970 | -Rs 180,799 |
| R72 Breadth Donchian | B+K1S | 1026 | 45% | **Rs 770,530** | Rs 6,012 / -Rs 3,559 | -Rs 65,854 | Mar 26 -Rs 70,911 | -Rs 211,380 |
| R51 Donchian 20 + Supertrend | A | 3084 | 46% | **Rs 946,603** | Rs 5,069 / -Rs 3,746 | -Rs 85,577 | Jun 26 -Rs 138,454 | -Rs 263,933 |
| R51 Donchian 20 + Supertrend | S1k | 3098 | 42% | **Rs 583,206** | Rs 5,024 / -Rs 3,257 | -Rs 61,113 | Feb 26 -Rs 114,785 | -Rs 314,291 |
| R51 Donchian 20 + Supertrend | K1S | 2999 | 41% | **Rs 506,263** | Rs 4,998 / -Rs 3,245 | -Rs 61,113 | Feb 26 -Rs 148,128 | -Rs 383,603 |
| R51 Donchian 20 + Supertrend | B | 1361 | 51% | **Rs 1,004,440** | Rs 5,470 / -Rs 4,274 | -Rs 73,545 | Jun 26 -Rs 62,761 | -Rs 108,826 |
| R51 Donchian 20 + Supertrend | B+S1k | 1374 | 44% | **Rs 544,131** | Rs 5,342 / -Rs 3,507 | -Rs 46,672 | Mar 26 -Rs 55,283 | -Rs 206,548 |
| R51 Donchian 20 + Supertrend | B+K1S | 1289 | 44% | **Rs 539,581** | Rs 5,335 / -Rs 3,479 | -Rs 46,672 | Mar 26 -Rs 82,687 | -Rs 233,952 |
| All three | A | 6053 | 46% | **Rs 2,467,188** | Rs 5,221 / -Rs 3,657 | -Rs 158,555 | Dec 25 -Rs 336,310 | -Rs 432,666 |
| All three | S1k | 6069 | 42% | **Rs 1,623,439** | Rs 5,151 / -Rs 3,205 | -Rs 122,364 | Dec 25 -Rs 246,133 | -Rs 498,717 |
| All three | K1S | 5778 | 42% | **Rs 1,595,355** | Rs 5,178 / -Rs 3,209 | -Rs 122,364 | Apr 26 -Rs 250,131 | -Rs 540,085 |
| All three | B | 3111 | 50% | **Rs 2,632,895** | Rs 5,720 / -Rs 3,932 | -Rs 130,335 | Apr 26 -Rs 61,992 | -Rs 199,852 |
| All three | B+S1k | 3125 | 43% | **Rs 1,664,872** | Rs 5,588 / -Rs 3,317 | -Rs 112,592 | Apr 26 -Rs 133,396 | -Rs 474,983 |
| All three | B+K1S | 2907 | 44% | **Rs 1,672,998** | Rs 5,633 / -Rs 3,321 | -Rs 112,592 | Mar 26 -Rs 177,815 | -Rs 520,425 |

