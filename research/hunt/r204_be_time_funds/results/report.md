# R204 / R205 results: earlier breakeven, earlier time stop, Liquidity without the MIDCPNIFTY 5-minute book, account sizes

Pre-registered in `PREREG.md` before any number. Data: the dhan-data branch to 5 Oct 2026 (not R190's lake to 13 Nov). 1 lot, after dated charges and slippage. The holdout is a SECOND LOOK: these ideas came from a diagnosis of the whole span (PREREG section 0), so a rule that clears the gate is not "passed" until new days agree.

**0 of 10 tests clear the gate on the second look**.

"Twin p" = the share of 2,000 random twins that did at least as well (small is good). For max drawdown a minus change is good; for worst month a plus change is good.

The final run's design rows equal `design.json`.

## R204, holdout 1 Apr - 5 Oct 2026 (a second look)

| arm | rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p | gate |
|---|---|---|---|---|---|---|---|---|---|---|
| R72 Breadth Donchian | **B: today's rule** | - | 596 | 60.4% | Rs 98,609 | -Rs 1,475 | Rs 36,477 | -Rs 13,690 | - | - |
| R72 Breadth Donchian | BE500: breakeven once the trade is up Rs 500 a lot (net) | 167 | 603 | 61.2% | Rs 66,444 (-Rs 32,165) | -Rs 1,173 | Rs 35,897 (-Rs 580) | -Rs 23,941 (-Rs 10,251) | 0.707 | fail |
| R72 Breadth Donchian | ET10: sold at 10 minutes if never up Rs 250 a lot (net) | 150 | 600 | 54.2% | Rs 73,481 (-Rs 25,128) | -Rs 1,158 | Rs 34,544 (-Rs 1,933) | -Rs 15,874 (-Rs 2,184) | 0.998 | fail |
| R72 Breadth Donchian | ET15: sold at 15 minutes if never up Rs 250 a lot (net) | 113 | 598 | 57.7% | Rs 82,440 (-Rs 16,169) | -Rs 1,299 | Rs 38,449 (+Rs 1,972) | -Rs 17,308 (-Rs 3,618) | 0.991 | fail |
| R51 Donchian 20 + Supertrend | **B: today's rule** | - | 652 | 60.4% | Rs 43,680 | -Rs 1,429 | Rs 45,825 | -Rs 29,871 | - | - |
| R51 Donchian 20 + Supertrend | BE500: breakeven once the trade is up Rs 500 a lot (net) | 175 | 668 | 63.9% | Rs 43,907 (+Rs 227) | -Rs 1,157 | Rs 41,905 (-Rs 3,920) | -Rs 28,286 (+Rs 1,585) | 0.439 | fail |
| R51 Donchian 20 + Supertrend | ET10: sold at 10 minutes if never up Rs 250 a lot (net) | 177 | 663 | 53.5% | Rs 2,946 (-Rs 40,734) | -Rs 1,166 | Rs 42,313 (-Rs 3,512) | -Rs 25,721 (+Rs 4,150) | 0.997 | fail |
| R51 Donchian 20 + Supertrend | ET15: sold at 15 minutes if never up Rs 250 a lot (net) | 138 | 658 | 56.8% | Rs 17,332 (-Rs 26,348) | -Rs 1,265 | Rs 51,079 (+Rs 5,254) | -Rs 35,298 (-Rs 5,427) | 0.999 | fail |
| Liquidity 15+5 | **B: today's rule** | - | 330 | 43.9% | Rs 11,087 | -Rs 913 | Rs 59,441 | -Rs 32,670 | - | - |
| Liquidity 15+5 | BE500: breakeven once the trade is up Rs 500 a lot (net) | 58 | 330 | 48.5% | Rs 16,730 (+Rs 5,643) | -Rs 826 | Rs 49,904 (-Rs 9,537) | -Rs 31,992 (+Rs 678) | 1.000 | fail |
| Liquidity 15+5 | ET10: sold at 10 minutes if never up Rs 250 a lot (net) | 54 | 330 | 41.2% | Rs 7,988 (-Rs 3,099) | -Rs 832 | Rs 57,437 (-Rs 2,004) | -Rs 28,544 (+Rs 4,126) | 0.999 | fail |
| Liquidity 15+5 | ET15: sold at 15 minutes if never up Rs 250 a lot (net) | 29 | 330 | 42.7% | Rs 11,787 (+Rs 700) | -Rs 874 | Rs 58,756 (-Rs 685) | -Rs 29,885 (+Rs 2,785) | 0.962 | fail |
| Liquidity 15+5 | NOM5: Liquidity without the MIDCPNIFTY 5-minute book | 81 | 249 | 43.0% | Rs 18,851 (+Rs 7,764) | -Rs 874 | Rs 44,140 (-Rs 15,301) | -Rs 18,889 (+Rs 13,781) | 0.276 | fail |

## R204, design 21 Nov 2024 - 31 Mar 2026

| arm | rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p | gate |
|---|---|---|---|---|---|---|---|---|---|---|
| R72 Breadth Donchian | **B: today's rule** | - | 1,561 | 65.7% | Rs 232,769 | -Rs 1,629 | Rs 100,582 | -Rs 26,420 | - | - |
| R72 Breadth Donchian | BE500: breakeven once the trade is up Rs 500 a lot (net) | 473 | 1,578 | 70.6% | Rs 154,247 (-Rs 78,522) | -Rs 1,362 | Rs 112,222 (+Rs 11,640) | -Rs 45,772 (-Rs 19,352) | 0.139 | - |
| R72 Breadth Donchian | ET10: sold at 10 minutes if never up Rs 250 a lot (net) | 276 | 1,567 | 60.4% | Rs 189,645 (-Rs 43,124) | -Rs 1,335 | Rs 83,530 (-Rs 17,052) | -Rs 24,014 (+Rs 2,406) | 1.000 | - |
| R72 Breadth Donchian | ET15: sold at 15 minutes if never up Rs 250 a lot (net) | 202 | 1,562 | 62.2% | Rs 200,707 (-Rs 32,062) | -Rs 1,438 | Rs 95,413 (-Rs 5,169) | -Rs 25,300 (+Rs 1,120) | 0.999 | - |
| R51 Donchian 20 + Supertrend | **B: today's rule** | - | 2,170 | 62.6% | Rs 147,686 | -Rs 1,579 | Rs 85,937 | -Rs 36,762 | - | - |
| R51 Donchian 20 + Supertrend | BE500: breakeven once the trade is up Rs 500 a lot (net) | 653 | 2,225 | 67.7% | Rs 79,285 (-Rs 68,401) | -Rs 1,350 | Rs 108,484 (+Rs 22,547) | -Rs 37,308 (-Rs 546) | 0.055 | - |
| R51 Donchian 20 + Supertrend | ET10: sold at 10 minutes if never up Rs 250 a lot (net) | 408 | 2,189 | 58.3% | Rs 118,552 (-Rs 29,134) | -Rs 1,335 | Rs 99,807 (+Rs 13,870) | -Rs 34,408 (+Rs 2,354) | 1.000 | - |
| R51 Donchian 20 + Supertrend | ET15: sold at 15 minutes if never up Rs 250 a lot (net) | 316 | 2,183 | 59.6% | Rs 134,113 (-Rs 13,573) | -Rs 1,411 | Rs 86,088 (+Rs 151) | -Rs 36,558 (+Rs 204) | 1.000 | - |
| Liquidity 15+5 | **B: today's rule** | - | 878 | 50.9% | Rs 231,580 | -Rs 909 | Rs 25,771 | -Rs 9,714 | - | - |
| Liquidity 15+5 | BE500: breakeven once the trade is up Rs 500 a lot (net) | 214 | 878 | 56.5% | Rs 151,163 (-Rs 80,417) | -Rs 801 | Rs 23,911 (-Rs 1,860) | -Rs 11,680 (-Rs 1,966) | 1.000 | - |
| Liquidity 15+5 | ET10: sold at 10 minutes if never up Rs 250 a lot (net) | 82 | 879 | 49.5% | Rs 219,567 (-Rs 12,013) | -Rs 851 | Rs 27,469 (+Rs 1,698) | -Rs 9,592 (+Rs 122) | 0.999 | - |
| Liquidity 15+5 | ET15: sold at 15 minutes if never up Rs 250 a lot (net) | 48 | 878 | 50.3% | Rs 211,977 (-Rs 19,603) | -Rs 901 | Rs 26,634 (+Rs 863) | -Rs 10,631 (-Rs 917) | 0.994 | - |
| Liquidity 15+5 | NOM5: Liquidity without the MIDCPNIFTY 5-minute book | 212 | 666 | 50.8% | Rs 223,403 (-Rs 8,177) | -Rs 853 | Rs 22,059 (-Rs 3,712) | -Rs 6,741 (+Rs 2,973) | 0.040 | - |

## R204, last year 24 Nov 2025 - 5 Oct 2026 (descriptive)

| arm | rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p | gate |
|---|---|---|---|---|---|---|---|---|---|---|
| R72 Breadth Donchian | **B: today's rule** | - | 938 | 64.8% | Rs 195,249 | -Rs 1,600 | Rs 42,286 | -Rs 13,690 | - | - |
| R72 Breadth Donchian | BE500: breakeven once the trade is up Rs 500 a lot (net) | 280 | 947 | 66.1% | Rs 137,356 (-Rs 57,893) | -Rs 1,231 | Rs 39,970 (-Rs 2,316) | -Rs 23,941 (-Rs 10,251) | n/a | - |
| R72 Breadth Donchian | ET10: sold at 10 minutes if never up Rs 250 a lot (net) | 201 | 942 | 58.6% | Rs 140,768 (-Rs 54,481) | -Rs 1,282 | Rs 50,079 (+Rs 7,793) | -Rs 15,874 (-Rs 2,184) | n/a | - |
| R72 Breadth Donchian | ET15: sold at 15 minutes if never up Rs 250 a lot (net) | 153 | 940 | 61.5% | Rs 156,845 (-Rs 38,404) | -Rs 1,405 | Rs 49,367 (+Rs 7,081) | -Rs 17,308 (-Rs 3,618) | n/a | - |
| R51 Donchian 20 + Supertrend | **B: today's rule** | - | 1,165 | 63.1% | Rs 143,844 | -Rs 1,519 | Rs 54,101 | -Rs 29,871 | - | - |
| R51 Donchian 20 + Supertrend | BE500: breakeven once the trade is up Rs 500 a lot (net) | 323 | 1,193 | 65.9% | Rs 112,012 (-Rs 31,832) | -Rs 1,212 | Rs 46,479 (-Rs 7,622) | -Rs 28,286 (+Rs 1,585) | n/a | - |
| R51 Donchian 20 + Supertrend | ET10: sold at 10 minutes if never up Rs 250 a lot (net) | 284 | 1,179 | 56.8% | Rs 96,666 (-Rs 47,178) | -Rs 1,222 | Rs 50,685 (-Rs 3,416) | -Rs 25,721 (+Rs 4,150) | n/a | - |
| R51 Donchian 20 + Supertrend | ET15: sold at 15 minutes if never up Rs 250 a lot (net) | 227 | 1,173 | 59.3% | Rs 119,077 (-Rs 24,767) | -Rs 1,300 | Rs 58,827 (+Rs 4,726) | -Rs 35,298 (-Rs 5,427) | n/a | - |
| Liquidity 15+5 | **B: today's rule** | - | 590 | 44.4% | Rs 106,212 | -Rs 928 | Rs 60,281 | -Rs 32,670 | - | - |
| Liquidity 15+5 | BE500: breakeven once the trade is up Rs 500 a lot (net) | 116 | 590 | 49.8% | Rs 95,969 (-Rs 10,243) | -Rs 835 | Rs 57,634 (-Rs 2,647) | -Rs 31,992 (+Rs 678) | n/a | - |
| Liquidity 15+5 | ET10: sold at 10 minutes if never up Rs 250 a lot (net) | 88 | 591 | 42.3% | Rs 111,964 (+Rs 5,752) | -Rs 834 | Rs 58,307 (-Rs 1,974) | -Rs 28,544 (+Rs 4,126) | n/a | - |
| Liquidity 15+5 | ET15: sold at 15 minutes if never up Rs 250 a lot (net) | 44 | 590 | 43.9% | Rs 108,448 (+Rs 2,236) | -Rs 900 | Rs 60,028 (-Rs 253) | -Rs 29,885 (+Rs 2,785) | n/a | - |
| Liquidity 15+5 | NOM5: Liquidity without the MIDCPNIFTY 5-minute book | 147 | 443 | 43.3% | Rs 94,323 (-Rs 11,889) | -Rs 873 | Rs 44,841 (-Rs 15,440) | -Rs 18,889 (+Rs 13,781) | n/a | - |

## R204, total 21 Nov 2024 - 5 Oct 2026

| arm | rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p | gate |
|---|---|---|---|---|---|---|---|---|---|---|
| R72 Breadth Donchian | **B: today's rule** | - | 2,157 | 64.3% | Rs 331,379 | -Rs 1,582 | Rs 100,582 | -Rs 26,420 | - | - |
| R72 Breadth Donchian | BE500: breakeven once the trade is up Rs 500 a lot (net) | 640 | 2,181 | 68.0% | Rs 220,691 (-Rs 110,688) | -Rs 1,299 | Rs 112,222 (+Rs 11,640) | -Rs 45,772 (-Rs 19,352) | n/a | - |
| R72 Breadth Donchian | ET10: sold at 10 minutes if never up Rs 250 a lot (net) | 426 | 2,167 | 58.7% | Rs 263,127 (-Rs 68,252) | -Rs 1,281 | Rs 83,530 (-Rs 17,052) | -Rs 24,014 (+Rs 2,406) | n/a | - |
| R72 Breadth Donchian | ET15: sold at 15 minutes if never up Rs 250 a lot (net) | 315 | 2,160 | 61.0% | Rs 283,146 (-Rs 48,233) | -Rs 1,396 | Rs 95,413 (-Rs 5,169) | -Rs 25,300 (+Rs 1,120) | n/a | - |
| R51 Donchian 20 + Supertrend | **B: today's rule** | - | 2,822 | 62.1% | Rs 191,366 | -Rs 1,543 | Rs 85,937 | -Rs 36,762 | - | - |
| R51 Donchian 20 + Supertrend | BE500: breakeven once the trade is up Rs 500 a lot (net) | 828 | 2,893 | 66.8% | Rs 123,192 (-Rs 68,174) | -Rs 1,301 | Rs 108,484 (+Rs 22,547) | -Rs 37,308 (-Rs 546) | n/a | - |
| R51 Donchian 20 + Supertrend | ET10: sold at 10 minutes if never up Rs 250 a lot (net) | 585 | 2,852 | 57.2% | Rs 121,498 (-Rs 69,868) | -Rs 1,292 | Rs 99,807 (+Rs 13,870) | -Rs 34,408 (+Rs 2,354) | n/a | - |
| R51 Donchian 20 + Supertrend | ET15: sold at 15 minutes if never up Rs 250 a lot (net) | 454 | 2,841 | 59.0% | Rs 151,445 (-Rs 39,921) | -Rs 1,376 | Rs 86,088 (+Rs 151) | -Rs 36,558 (+Rs 204) | n/a | - |
| Liquidity 15+5 | **B: today's rule** | - | 1,208 | 49.0% | Rs 242,666 | -Rs 910 | Rs 60,281 | -Rs 32,670 | - | - |
| Liquidity 15+5 | BE500: breakeven once the trade is up Rs 500 a lot (net) | 272 | 1,208 | 54.3% | Rs 167,893 (-Rs 74,773) | -Rs 809 | Rs 57,634 (-Rs 2,647) | -Rs 31,992 (+Rs 678) | n/a | - |
| Liquidity 15+5 | ET10: sold at 10 minutes if never up Rs 250 a lot (net) | 136 | 1,209 | 47.2% | Rs 227,555 (-Rs 15,111) | -Rs 845 | Rs 58,307 (-Rs 1,974) | -Rs 28,544 (+Rs 4,126) | n/a | - |
| Liquidity 15+5 | ET15: sold at 15 minutes if never up Rs 250 a lot (net) | 77 | 1,208 | 48.3% | Rs 223,764 (-Rs 18,902) | -Rs 893 | Rs 60,028 (-Rs 253) | -Rs 29,885 (+Rs 2,785) | n/a | - |
| Liquidity 15+5 | NOM5: Liquidity without the MIDCPNIFTY 5-minute book | 293 | 915 | 48.6% | Rs 242,254 (-Rs 412) | -Rs 859 | Rs 44,841 (-Rs 15,440) | -Rs 18,889 (+Rs 13,781) | n/a | - |

## R204: why each one fails or clears

- **R72 Breadth Donchian, BE500** (fail): design: net 154,247 not better than B's 232,769; design: max drawdown 112,222 above B's 100,582; holdout: net 66,444 not better than B's 98,609; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 0.7071 >= 0.05; BH q 1 > 0.1. Design: saved Rs 267,315 on B's losers, gave up Rs 353,848 on B's winners (60 winners became losers). Holdout: saved Rs 79,028 on B's losers, gave up Rs 120,221 on B's winners (31 winners became losers). BH q 1 (m = 4,879; within these 10: 1); better in 1 of 4 design quarters; design twin p 0.139.
- **R51 Donchian 20 + Supertrend, BE500** (fail): design: net 79,285 not better than B's 147,686; design: max drawdown 108,484 above B's 85,937; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 0.4393 >= 0.05; BH q 1 > 0.1. Design: saved Rs 377,175 on B's losers, gave up Rs 449,863 on B's winners (89 winners became losers). Holdout: saved Rs 96,651 on B's losers, gave up Rs 98,595 on B's winners (25 winners became losers). BH q 1 (m = 4,879; within these 10: 1); better in 2 of 4 design quarters; design twin p 0.055.
- **Liquidity 15+5, BE500** (fail): design: net 151,163 not better than B's 231,580; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Design: saved Rs 92,603 on B's losers, gave up Rs 173,019 on B's winners (23 winners became losers). Holdout: saved Rs 29,486 on B's losers, gave up Rs 23,843 on B's winners (7 winners became losers). BH q 1 (m = 4,879; within these 10: 1); better in 1 of 4 design quarters; design twin p 1.000.
- **R72 Breadth Donchian, ET10** (fail): design: net 189,645 not better than B's 232,769; holdout: net 73,481 not better than B's 98,609; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 0.9980 >= 0.05; BH q 1 > 0.1. Design: saved Rs 104,514 on B's losers, gave up Rs 156,112 on B's winners (90 winners became losers). Holdout: saved Rs 54,298 on B's losers, gave up Rs 67,774 on B's winners (40 winners became losers). BH q 1 (m = 4,879; within these 10: 1); better in 1 of 4 design quarters; design twin p 1.000.
- **R51 Donchian 20 + Supertrend, ET10** (fail): design: net 118,552 not better than B's 147,686; design: max drawdown 99,807 above B's 85,937; holdout: net 2,946 not better than B's 43,680; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 0.9965 >= 0.05; BH q 1 > 0.1. Design: saved Rs 145,881 on B's losers, gave up Rs 198,102 on B's winners (107 winners became losers). Holdout: saved Rs 44,565 on B's losers, gave up Rs 75,094 on B's winners (46 winners became losers). BH q 1 (m = 4,879; within these 10: 1); better in 1 of 4 design quarters; design twin p 1.000.
- **Liquidity 15+5, ET10** (fail): design: net 219,567 not better than B's 231,580; design: max drawdown 27,469 above B's 25,771; holdout: net 7,988 not better than B's 11,087; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 0.9985 >= 0.05; BH q 1 > 0.1. Design: saved Rs 23,021 on B's losers, gave up Rs 34,925 on B's winners (15 winners became losers). Holdout: saved Rs 12,125 on B's losers, gave up Rs 15,223 on B's winners (11 winners became losers). BH q 1 (m = 4,879; within these 10: 1); better in 2 of 4 design quarters; design twin p 0.999.
- **R72 Breadth Donchian, ET15** (fail): design: net 200,707 not better than B's 232,769; holdout: net 82,440 not better than B's 98,609; holdout: max drawdown 38,449 above B's 36,477; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 0.9910 >= 0.05; BH q 1 > 0.1. Design: saved Rs 54,336 on B's losers, gave up Rs 89,358 on B's winners (56 winners became losers). Holdout: saved Rs 37,558 on B's losers, gave up Rs 36,404 on B's winners (19 winners became losers). BH q 1 (m = 4,879; within these 10: 1); better in 1 of 4 design quarters; design twin p 0.999.
- **R51 Donchian 20 + Supertrend, ET15** (fail): design: net 134,113 not better than B's 147,686; design: max drawdown 86,088 above B's 85,937; holdout: net 17,332 not better than B's 43,680; holdout: max drawdown 51,079 above B's 45,825; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 0.9985 >= 0.05; BH q 1 > 0.1. Design: saved Rs 88,316 on B's losers, gave up Rs 114,763 on B's winners (72 winners became losers). Holdout: saved Rs 28,988 on B's losers, gave up Rs 38,576 on B's winners (24 winners became losers). BH q 1 (m = 4,879; within these 10: 1); better in 2 of 4 design quarters; design twin p 1.000.
- **Liquidity 15+5, ET15** (fail): design: net 211,977 not better than B's 231,580; design: max drawdown 26,634 above B's 25,771; holdout: 29 trades changed / removed < 40; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 0.9620 >= 0.05; BH q 1 > 0.1. Design: saved Rs 1,903 on B's losers, gave up Rs 21,506 on B's winners (6 winners became losers). Holdout: saved Rs 5,925 on B's losers, gave up Rs 5,226 on B's winners (4 winners became losers). BH q 1 (m = 4,879; within these 10: 1); better in 2 of 4 design quarters; design twin p 0.994.
- **Liquidity 15+5, NOM5** (fail): design: net 223,403 not better than B's 231,580; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 0.2764 >= 0.05; BH q 1 > 0.1. Design: the removed trades had made Rs 8,177 (103 of them losers). Holdout: the removed trades had made -Rs 7,765 (43 of them losers). BH q 1 (m = 4,879; within these 10: 1); better in 2 of 4 design quarters; design twin p 0.040.

## R205: what an account of a given size can take (descriptive)

One shared pot of money, 1 lot a trade, every day starting with the account size; a trade the free cash cannot pay for is not taken. Same-minute order: R72, Liquidity, R51. No re-walk (an approximation, PREREG 4).

### all three arms, total 21 Nov 2024 - 5 Oct 2026

| account | trades taken | not taken (their net) | win % | net | net as % of account | max drawdown | drawdown as % of account | worst month | worst day | most money in use |
|---|---|---|---|---|---|---|---|---|---|---|
| Rs 56,000 | 3,830 | 2,357 (Rs 471,651) | 58.1% | Rs 293,761 | 524.6% | Rs 83,161 | 148.5% | -Rs 35,493 (2026-05) | -Rs 18,422 | Rs 78,151 |
| Rs 100,000 | 5,077 | 1,110 (Rs 291,906) | 59.0% | Rs 473,505 | 473.5% | Rs 127,843 | 127.8% | -Rs 33,076 (2025-02) | -Rs 26,246 | Rs 119,497 |
| Rs 150,000 | 5,736 | 451 (Rs 88,380) | 59.9% | Rs 677,031 | 451.4% | Rs 144,633 | 96.4% | -Rs 45,412 (2025-03) | -Rs 33,334 | Rs 165,538 |
| Rs 200,000 | 6,016 | 171 (Rs 5,287) | 60.2% | Rs 760,124 | 380.1% | Rs 150,211 | 75.1% | -Rs 55,092 (2025-03) | -Rs 39,287 | Rs 207,496 |
| Rs 300,000 | 6,175 | 12 (-Rs 445) | 60.2% | Rs 765,856 | 255.3% | Rs 148,047 | 49.3% | -Rs 74,463 (2026-04) | -Rs 40,097 | Rs 319,032 |
| Rs 500,000 | 6,187 | 0 (Rs 0) | 60.3% | Rs 765,411 | 153.1% | Rs 148,047 | 29.6% | -Rs 76,231 (2026-04) | -Rs 40,097 | Rs 354,120 |
| no limit | 6,187 | 0 (Rs 0) | 60.3% | Rs 765,411 | n/a | Rs 148,047 | n/a | -Rs 76,231 (2026-04) | -Rs 40,097 | Rs 354,120 |

### all three arms, last year 24 Nov 2025 - 5 Oct 2026 (descriptive)

| account | trades taken | not taken (their net) | win % | net | net as % of account | max drawdown | drawdown as % of account | worst month | worst day | most money in use |
|---|---|---|---|---|---|---|---|---|---|---|
| Rs 56,000 | 1,616 | 1,077 (Rs 310,057) | 57.2% | Rs 135,248 | 241.5% | Rs 78,644 | 140.4% | -Rs 35,493 (2026-05) | -Rs 12,203 | Rs 78,151 |
| Rs 100,000 | 2,150 | 543 (Rs 172,972) | 58.3% | Rs 272,333 | 272.3% | Rs 70,462 | 70.5% | -Rs 31,346 (2026-05) | -Rs 19,205 | Rs 119,497 |
| Rs 150,000 | 2,448 | 245 (Rs 28,810) | 59.3% | Rs 416,494 | 277.7% | Rs 72,646 | 48.4% | -Rs 24,798 (2026-04) | -Rs 26,121 | Rs 165,538 |
| Rs 200,000 | 2,589 | 104 (-Rs 349) | 59.5% | Rs 445,653 | 222.8% | Rs 91,731 | 45.9% | -Rs 46,725 (2026-04) | -Rs 35,850 | Rs 207,496 |
| Rs 300,000 | 2,684 | 9 (-Rs 2,770) | 59.5% | Rs 448,075 | 149.4% | Rs 116,848 | 38.9% | -Rs 74,463 (2026-04) | -Rs 39,779 | Rs 319,032 |
| Rs 500,000 | 2,693 | 0 (Rs 0) | 59.6% | Rs 445,305 | 89.1% | Rs 118,616 | 23.7% | -Rs 76,231 (2026-04) | -Rs 39,779 | Rs 354,120 |
| no limit | 2,693 | 0 (Rs 0) | 59.6% | Rs 445,305 | n/a | Rs 118,616 | n/a | -Rs 76,231 (2026-04) | -Rs 39,779 | Rs 354,120 |

### all three arms, holdout 1 Apr - 5 Oct 2026 (a second look)

| account | trades taken | not taken (their net) | win % | net | net as % of account | max drawdown | drawdown as % of account | worst month | worst day | most money in use |
|---|---|---|---|---|---|---|---|---|---|---|
| Rs 56,000 | 962 | 616 (Rs 101,119) | 55.8% | Rs 52,257 | 93.3% | Rs 68,580 | 122.5% | -Rs 35,493 (2026-05) | -Rs 12,203 | Rs 78,151 |
| Rs 100,000 | 1,289 | 289 (Rs 34,049) | 56.7% | Rs 119,328 | 119.3% | Rs 64,676 | 64.7% | -Rs 31,346 (2026-05) | -Rs 19,205 | Rs 119,497 |
| Rs 150,000 | 1,458 | 120 (-Rs 31,072) | 57.3% | Rs 184,448 | 123.0% | Rs 61,698 | 41.1% | -Rs 24,798 (2026-04) | -Rs 26,121 | Rs 165,538 |
| Rs 200,000 | 1,535 | 43 (-Rs 34,627) | 57.3% | Rs 188,004 | 94.0% | Rs 77,384 | 38.7% | -Rs 46,725 (2026-04) | -Rs 35,850 | Rs 207,496 |
| Rs 300,000 | 1,575 | 3 (-Rs 1,768) | 57.0% | Rs 155,144 | 51.7% | Rs 105,122 | 35.0% | -Rs 74,463 (2026-04) | -Rs 39,779 | Rs 319,032 |
| Rs 500,000 | 1,578 | 0 (Rs 0) | 57.0% | Rs 153,376 | 30.7% | Rs 106,890 | 21.4% | -Rs 76,231 (2026-04) | -Rs 39,779 | Rs 354,120 |
| no limit | 1,578 | 0 (Rs 0) | 57.0% | Rs 153,376 | n/a | Rs 106,890 | n/a | -Rs 76,231 (2026-04) | -Rs 39,779 | Rs 354,120 |

### all three arms: by arm, total 21 Nov 2024 - 5 Oct 2026

| account | R72 Breadth Donchian: taken / not taken, net | Liquidity 15+5: taken / not taken, net | R51 Donchian 20 + Supertrend: taken / not taken, net |
|---|---|---|---|
| Rs 56,000 | 1,529 / 628, Rs 274,088 | 805 / 403, Rs 57,867 | 1,496 / 1,326, -Rs 38,194 |
| Rs 100,000 | 1,973 / 184, Rs 306,301 | 1,051 / 157, Rs 128,521 | 2,053 / 769, Rs 38,682 |
| Rs 150,000 | 2,121 / 36, Rs 326,073 | 1,155 / 53, Rs 182,633 | 2,460 / 362, Rs 168,325 |
| Rs 200,000 | 2,149 / 8, Rs 333,708 | 1,193 / 15, Rs 226,608 | 2,674 / 148, Rs 199,809 |
| Rs 300,000 | 2,157 / 0, Rs 331,379 | 1,206 / 2, Rs 244,935 | 2,812 / 10, Rs 189,543 |
| Rs 500,000 | 2,157 / 0, Rs 331,379 | 1,208 / 0, Rs 242,666 | 2,822 / 0, Rs 191,366 |
| no limit | 2,157 / 0, Rs 331,379 | 1,208 / 0, Rs 242,666 | 2,822 / 0, Rs 191,366 |

### R72 + Liquidity (no R51), total 21 Nov 2024 - 5 Oct 2026

| account | trades taken | not taken (their net) | win % | net | net as % of account | max drawdown | drawdown as % of account | worst month | worst day | most money in use |
|---|---|---|---|---|---|---|---|---|---|---|
| Rs 56,000 | 2,453 | 912 (Rs 241,543) | 57.3% | Rs 332,502 | 593.8% | Rs 90,902 | 162.3% | -Rs 24,685 (2026-05) | -Rs 15,614 | Rs 69,058 |
| Rs 100,000 | 3,083 | 282 (Rs 106,962) | 58.3% | Rs 467,084 | 467.1% | Rs 103,339 | 103.3% | -Rs 26,293 (2025-02) | -Rs 25,208 | Rs 120,901 |
| Rs 150,000 | 3,311 | 54 (Rs 71,743) | 58.6% | Rs 502,302 | 334.9% | Rs 103,957 | 69.3% | -Rs 41,536 (2026-04) | -Rs 31,636 | Rs 153,494 |
| Rs 200,000 | 3,360 | 5 (Rs 292) | 58.8% | Rs 573,753 | 286.9% | Rs 105,406 | 52.7% | -Rs 44,444 (2026-04) | -Rs 33,743 | Rs 204,437 |
| Rs 300,000 | 3,365 | 0 (Rs 0) | 58.8% | Rs 574,045 | 191.3% | Rs 105,406 | 35.1% | -Rs 46,360 (2026-04) | -Rs 33,743 | Rs 249,385 |
| Rs 500,000 | 3,365 | 0 (Rs 0) | 58.8% | Rs 574,045 | 114.8% | Rs 105,406 | 21.1% | -Rs 46,360 (2026-04) | -Rs 33,743 | Rs 249,385 |
| no limit | 3,365 | 0 (Rs 0) | 58.8% | Rs 574,045 | n/a | Rs 105,406 | n/a | -Rs 46,360 (2026-04) | -Rs 33,743 | Rs 249,385 |

### R72 + Liquidity (no R51), last year 24 Nov 2025 - 5 Oct 2026 (descriptive)

| account | trades taken | not taken (their net) | win % | net | net as % of account | max drawdown | drawdown as % of account | worst month | worst day | most money in use |
|---|---|---|---|---|---|---|---|---|---|---|
| Rs 56,000 | 1,090 | 438 (Rs 124,309) | 55.9% | Rs 177,152 | 316.3% | Rs 52,128 | 93.1% | -Rs 24,685 (2026-05) | -Rs 13,701 | Rs 69,058 |
| Rs 100,000 | 1,372 | 156 (Rs 30,376) | 57.0% | Rs 271,085 | 271.1% | Rs 46,235 | 46.2% | -Rs 20,307 (2026-05) | -Rs 19,705 | Rs 120,901 |
| Rs 150,000 | 1,498 | 30 (Rs 34,779) | 56.8% | Rs 266,682 | 177.8% | Rs 74,266 | 49.5% | -Rs 41,536 (2026-04) | -Rs 23,741 | Rs 153,494 |
| Rs 200,000 | 1,524 | 4 (-Rs 2,508) | 57.0% | Rs 303,969 | 152.0% | Rs 81,358 | 40.7% | -Rs 44,444 (2026-04) | -Rs 23,741 | Rs 204,437 |
| Rs 300,000 | 1,528 | 0 (Rs 0) | 56.9% | Rs 301,461 | 100.5% | Rs 83,274 | 27.8% | -Rs 46,360 (2026-04) | -Rs 23,741 | Rs 249,385 |
| Rs 500,000 | 1,528 | 0 (Rs 0) | 56.9% | Rs 301,461 | 60.3% | Rs 83,274 | 16.7% | -Rs 46,360 (2026-04) | -Rs 23,741 | Rs 249,385 |
| no limit | 1,528 | 0 (Rs 0) | 56.9% | Rs 301,461 | n/a | Rs 83,274 | n/a | -Rs 46,360 (2026-04) | -Rs 23,741 | Rs 249,385 |

### R72 + Liquidity (no R51), holdout 1 Apr - 5 Oct 2026 (a second look)

| account | trades taken | not taken (their net) | win % | net | net as % of account | max drawdown | drawdown as % of account | worst month | worst day | most money in use |
|---|---|---|---|---|---|---|---|---|---|---|
| Rs 56,000 | 671 | 255 (Rs 25,552) | 54.4% | Rs 84,145 | 150.3% | Rs 47,120 | 84.1% | -Rs 24,685 (2026-05) | -Rs 13,701 | Rs 69,058 |
| Rs 100,000 | 846 | 80 (-Rs 23,175) | 55.2% | Rs 132,871 | 132.9% | Rs 42,525 | 42.5% | -Rs 20,307 (2026-05) | -Rs 19,705 | Rs 120,901 |
| Rs 150,000 | 913 | 13 (-Rs 9,009) | 54.4% | Rs 118,705 | 79.1% | Rs 70,815 | 47.2% | -Rs 41,536 (2026-04) | -Rs 23,741 | Rs 153,494 |
| Rs 200,000 | 923 | 3 (-Rs 1,917) | 54.5% | Rs 111,613 | 55.8% | Rs 77,907 | 39.0% | -Rs 44,444 (2026-04) | -Rs 23,741 | Rs 204,437 |
| Rs 300,000 | 926 | 0 (Rs 0) | 54.5% | Rs 109,696 | 36.6% | Rs 79,824 | 26.6% | -Rs 46,360 (2026-04) | -Rs 23,741 | Rs 249,385 |
| Rs 500,000 | 926 | 0 (Rs 0) | 54.5% | Rs 109,696 | 21.9% | Rs 79,824 | 16.0% | -Rs 46,360 (2026-04) | -Rs 23,741 | Rs 249,385 |
| no limit | 926 | 0 (Rs 0) | 54.5% | Rs 109,696 | n/a | Rs 79,824 | n/a | -Rs 46,360 (2026-04) | -Rs 23,741 | Rs 249,385 |

### R72 + Liquidity (no R51): by arm, total 21 Nov 2024 - 5 Oct 2026

| account | R72 Breadth Donchian: taken / not taken, net | Liquidity 15+5: taken / not taken, net |
|---|---|---|
| Rs 56,000 | 1,594 / 563, Rs 262,075 | 859 / 349, Rs 70,427 |
| Rs 100,000 | 2,014 / 143, Rs 311,300 | 1,069 / 139, Rs 155,784 |
| Rs 150,000 | 2,139 / 18, Rs 321,638 | 1,172 / 36, Rs 180,664 |
| Rs 200,000 | 2,157 / 0, Rs 331,379 | 1,203 / 5, Rs 242,374 |
| Rs 300,000 | 2,157 / 0, Rs 331,379 | 1,208 / 0, Rs 242,666 |
| Rs 500,000 | 2,157 / 0, Rs 331,379 | 1,208 / 0, Rs 242,666 |
| no limit | 2,157 / 0, Rs 331,379 | 1,208 / 0, Rs 242,666 |

## Liquidity from 1 April 2026 (baseline, descriptive)

All books: 330 trades, 43.9% wins, Rs 11,087; max drawdown Rs 59,441; worst month -Rs 32,670 (2026-04).

| month | trades | win % | net |
|---|---|---|---|
| 2026-04 | 54 | 33.3% | -Rs 32,670 |
| 2026-05 | 47 | 40.4% | -Rs 14,657 |
| 2026-06 | 51 | 47.1% | Rs 707 |
| 2026-07 | 66 | 45.5% | Rs 23,012 |
| 2026-08 | 53 | 41.5% | Rs 2,100 |
| 2026-09 | 50 | 58.0% | Rs 33,938 |
| 2026-10 | 9 | 33.3% | -Rs 1,343 |

| book | trades | win % | net |
|---|---|---|---|
| BANKNIFTY 15-minute | 30 | 50.0% | Rs 4,229 |
| BANKNIFTY 5-minute | 93 | 46.2% | -Rs 1,754 |
| FINNIFTY 30-minute | 14 | 28.6% | -Rs 4,138 |
| FINNIFTY 5-minute | 76 | 38.2% | Rs 12,956 |
| MIDCPNIFTY 15-minute | 36 | 44.4% | Rs 7,559 |
| MIDCPNIFTY 5-minute | 81 | 46.9% | -Rs 7,765 |
