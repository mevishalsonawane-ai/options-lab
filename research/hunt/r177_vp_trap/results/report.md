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
