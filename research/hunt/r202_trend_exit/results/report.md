# R202 / R203 results: sell when the trend turns, and other ways to lose less (R72, R51, Liquidity 15+5)

Pre-registered in `PREREG.md` before any number. Data: the dhan-data branch to 5 Oct 2026 (not R190's lake to 13 Nov); it reproduces R193's months within 2.5% (PREREG 1). 1 lot, after dated charges and slippage. Holdout read once (2026-11-20T04:46:45.222176+00:00 UTC). **0 of 60 tests pass the gate**.

"Twin p" = the share of 2,000 random twins (the same trades cut at random minutes, or the same number of trades skipped at random) that did at least as well; small is good. "vs B" = against today's rule on the same days: for max drawdown a minus change is a smaller drawdown (good); for worst month a plus change is a less bad month (good).

## Baseline B: what the arms run today (V5)

| arm | window | trades | win % | net | average loss | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| R72 Breadth Donchian | design 21 Nov 2024 - 31 Mar 2026 | 1,561 | 65.7% | Rs 232,769 | -Rs 1,629 | Rs 100,582 | -Rs 26,420 (2025-02) |
| R72 Breadth Donchian | holdout 1 Apr - 5 Oct 2026 (read once) | 596 | 60.4% | Rs 98,609 | -Rs 1,475 | Rs 36,477 | -Rs 13,690 (2026-04) |
| R72 Breadth Donchian | total 21 Nov 2024 - 5 Oct 2026 | 2,157 | 64.3% | Rs 331,379 | -Rs 1,582 | Rs 100,582 | -Rs 26,420 (2025-02) |
| R72 Breadth Donchian | last year 24 Nov 2025 - 5 Oct 2026 (descriptive) | 938 | 64.8% | Rs 195,249 | -Rs 1,600 | Rs 42,286 | -Rs 13,690 (2026-04) |
| R51 Donchian 20 + Supertrend | design 21 Nov 2024 - 31 Mar 2026 | 2,170 | 62.6% | Rs 147,686 | -Rs 1,579 | Rs 85,937 | -Rs 36,762 (2025-03) |
| R51 Donchian 20 + Supertrend | holdout 1 Apr - 5 Oct 2026 (read once) | 652 | 60.4% | Rs 43,680 | -Rs 1,429 | Rs 45,825 | -Rs 29,871 (2026-04) |
| R51 Donchian 20 + Supertrend | total 21 Nov 2024 - 5 Oct 2026 | 2,822 | 62.1% | Rs 191,366 | -Rs 1,543 | Rs 85,937 | -Rs 36,762 (2025-03) |
| R51 Donchian 20 + Supertrend | last year 24 Nov 2025 - 5 Oct 2026 (descriptive) | 1,165 | 63.1% | Rs 143,844 | -Rs 1,519 | Rs 54,101 | -Rs 29,871 (2026-04) |
| Liquidity 15+5 | design 21 Nov 2024 - 31 Mar 2026 | 878 | 50.9% | Rs 231,580 | -Rs 909 | Rs 25,771 | -Rs 9,714 (2025-09) |
| Liquidity 15+5 | holdout 1 Apr - 5 Oct 2026 (read once) | 330 | 43.9% | Rs 11,087 | -Rs 913 | Rs 59,441 | -Rs 32,670 (2026-04) |
| Liquidity 15+5 | total 21 Nov 2024 - 5 Oct 2026 | 1,208 | 49.0% | Rs 242,666 | -Rs 910 | Rs 60,281 | -Rs 32,670 (2026-04) |
| Liquidity 15+5 | last year 24 Nov 2025 - 5 Oct 2026 (descriptive) | 590 | 44.4% | Rs 106,212 | -Rs 928 | Rs 60,281 | -Rs 32,670 (2026-04) |

The final run's design rows equal `design.json` for every rule and arm.

## R202 trend-turn exits: R72 Breadth Donchian, holdout 1 Apr - 5 Oct 2026 (read once)

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p | verdict |
|---|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 596 | 60.4% | Rs 98,609 | -Rs 1,475 | Rs 36,477 | -Rs 13,690 | - | - |
| X1: sell on a Donchian-20 break the other way (R51's opposite signal) | 0 | 596 | 60.4% | Rs 98,609 (+Rs 0) | -Rs 1,475 | Rs 36,477 (+Rs 0) | -Rs 13,690 (+Rs 0) | 1.000 | fail |
| X2: sell on an R72 breadth break the other way | 0 | 596 | 60.4% | Rs 98,609 (+Rs 0) | -Rs 1,475 | Rs 36,477 (+Rs 0) | -Rs 13,690 (+Rs 0) | 1.000 | fail |
| X3: sell on an opposite signal from any of the three arms | 0 | 596 | 60.4% | Rs 98,609 (+Rs 0) | -Rs 1,475 | Rs 36,477 (+Rs 0) | -Rs 13,690 (+Rs 0) | 1.000 | fail |
| X4: sell after two 5-min closes back through the entry level | 245 | 600 | 51.3% | Rs 82,344 (-Rs 16,265) | -Rs 1,022 | Rs 35,139 (-Rs 1,338) | -Rs 21,504 (-Rs 7,814) | 1.000 | fail |
| X5: sell after two 5-min closes on the wrong side of EMA20 | 14 | 596 | 60.4% | Rs 101,742 (+Rs 3,133) | -Rs 1,461 | Rs 36,305 (-Rs 172) | -Rs 14,434 (-Rs 744) | 1.000 | fail |
| X6: sell after two 5-min closes on the wrong side of the day's average price | 102 | 597 | 56.3% | Rs 93,132 (-Rs 5,477) | -Rs 1,273 | Rs 36,639 (+Rs 162) | -Rs 26,555 (-Rs 12,865) | 1.000 | fail |
| X7: sell when the 5-min Supertrend(10, 3) flips against the trade | 0 | 596 | 60.4% | Rs 98,609 (+Rs 0) | -Rs 1,475 | Rs 36,477 (+Rs 0) | -Rs 13,690 (+Rs 0) | 1.000 | fail |
| X8: sell when breadth turns (4 of 5 indices on the wrong side of their day's average, two bars) | 88 | 597 | 57.1% | Rs 82,196 (-Rs 16,413) | -Rs 1,319 | Rs 41,869 (+Rs 5,392) | -Rs 29,784 (-Rs 16,094) | 1.000 | fail |
| X9: sell when NIFTY's 15-min close is on the wrong side of its EMA20 | 60 | 602 | 57.6% | Rs 62,933 (-Rs 35,676) | -Rs 1,373 | Rs 34,743 (-Rs 1,734) | -Rs 21,871 (-Rs 8,181) | 0.930 | fail |

## R202 trend-turn exits: R72 Breadth Donchian, design 21 Nov 2024 - 31 Mar 2026

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 1,561 | 65.7% | Rs 232,769 | -Rs 1,629 | Rs 100,582 | -Rs 26,420 | - |
| X1: sell on a Donchian-20 break the other way (R51's opposite signal) | 2 | 1,561 | 65.7% | Rs 234,102 (+Rs 1,333) | -Rs 1,627 | Rs 99,949 (-Rs 633) | -Rs 26,420 (+Rs 0) | 0.937 |
| X2: sell on an R72 breadth break the other way | 0 | 1,561 | 65.7% | Rs 232,769 (+Rs 0) | -Rs 1,629 | Rs 100,582 (+Rs 0) | -Rs 26,420 (+Rs 0) | 1.000 |
| X3: sell on an opposite signal from any of the three arms | 2 | 1,561 | 65.7% | Rs 234,102 (+Rs 1,333) | -Rs 1,627 | Rs 99,949 (-Rs 633) | -Rs 26,420 (+Rs 0) | 0.937 |
| X4: sell after two 5-min closes back through the entry level | 471 | 1,570 | 57.1% | Rs 186,831 (-Rs 45,938) | -Rs 1,171 | Rs 90,980 (-Rs 9,602) | -Rs 26,577 (-Rs 157) | 1.000 |
| X5: sell after two 5-min closes on the wrong side of EMA20 | 38 | 1,561 | 65.7% | Rs 226,043 (-Rs 6,726) | -Rs 1,633 | Rs 96,122 (-Rs 4,460) | -Rs 25,974 (+Rs 446) | 1.000 |
| X6: sell after two 5-min closes on the wrong side of the day's average price | 130 | 1,563 | 63.2% | Rs 176,321 (-Rs 56,448) | -Rs 1,539 | Rs 101,319 (+Rs 737) | -Rs 37,862 (-Rs 11,442) | 1.000 |
| X7: sell when the 5-min Supertrend(10, 3) flips against the trade | 9 | 1,561 | 65.7% | Rs 234,670 (+Rs 1,901) | -Rs 1,626 | Rs 95,954 (-Rs 4,628) | -Rs 24,839 (+Rs 1,581) | 0.995 |
| X8: sell when breadth turns (4 of 5 indices on the wrong side of their day's average, two bars) | 89 | 1,563 | 64.1% | Rs 184,711 (-Rs 48,058) | -Rs 1,586 | Rs 100,590 (+Rs 8) | -Rs 37,367 (-Rs 10,947) | 1.000 |
| X9: sell when NIFTY's 15-min close is on the wrong side of its EMA20 | 128 | 1,570 | 64.0% | Rs 206,277 (-Rs 26,492) | -Rs 1,502 | Rs 104,698 (+Rs 4,116) | -Rs 35,718 (-Rs 9,298) | 0.996 |

## R202 trend-turn exits: R72 Breadth Donchian, last year 24 Nov 2025 - 5 Oct 2026 (descriptive)

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 938 | 64.8% | Rs 195,249 | -Rs 1,600 | Rs 42,286 | -Rs 13,690 | - |
| X1: sell on a Donchian-20 break the other way (R51's opposite signal) | 0 | 938 | 64.8% | Rs 195,249 (+Rs 0) | -Rs 1,600 | Rs 42,286 (+Rs 0) | -Rs 13,690 (+Rs 0) | n/a |
| X2: sell on an R72 breadth break the other way | 0 | 938 | 64.8% | Rs 195,249 (+Rs 0) | -Rs 1,600 | Rs 42,286 (+Rs 0) | -Rs 13,690 (+Rs 0) | n/a |
| X3: sell on an opposite signal from any of the three arms | 0 | 938 | 64.8% | Rs 195,249 (+Rs 0) | -Rs 1,600 | Rs 42,286 (+Rs 0) | -Rs 13,690 (+Rs 0) | n/a |
| X4: sell after two 5-min closes back through the entry level | 348 | 941 | 55.1% | Rs 168,062 (-Rs 27,187) | -Rs 1,080 | Rs 48,216 (+Rs 5,930) | -Rs 21,504 (-Rs 7,814) | n/a |
| X5: sell after two 5-min closes on the wrong side of EMA20 | 19 | 938 | 64.8% | Rs 196,426 (+Rs 1,177) | -Rs 1,596 | Rs 42,114 (-Rs 172) | -Rs 14,434 (-Rs 744) | n/a |
| X6: sell after two 5-min closes on the wrong side of the day's average price | 131 | 939 | 61.2% | Rs 175,679 (-Rs 19,570) | -Rs 1,420 | Rs 45,906 (+Rs 3,620) | -Rs 26,555 (-Rs 12,865) | n/a |
| X7: sell when the 5-min Supertrend(10, 3) flips against the trade | 1 | 938 | 64.8% | Rs 195,249 (+Rs 0) | -Rs 1,600 | Rs 42,286 (+Rs 0) | -Rs 13,690 (+Rs 0) | n/a |
| X8: sell when breadth turns (4 of 5 indices on the wrong side of their day's average, two bars) | 100 | 939 | 62.4% | Rs 170,043 (-Rs 25,206) | -Rs 1,482 | Rs 49,320 (+Rs 7,034) | -Rs 29,784 (-Rs 16,094) | n/a |
| X9: sell when NIFTY's 15-min close is on the wrong side of its EMA20 | 86 | 945 | 62.3% | Rs 180,204 (-Rs 15,045) | -Rs 1,426 | Rs 40,302 (-Rs 1,984) | -Rs 21,871 (-Rs 8,181) | n/a |

## R202 trend-turn exits: R72 Breadth Donchian, total 21 Nov 2024 - 5 Oct 2026

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 2,157 | 64.3% | Rs 331,379 | -Rs 1,582 | Rs 100,582 | -Rs 26,420 | - |
| X1: sell on a Donchian-20 break the other way (R51's opposite signal) | 2 | 2,157 | 64.3% | Rs 332,712 (+Rs 1,333) | -Rs 1,580 | Rs 99,949 (-Rs 633) | -Rs 26,420 (+Rs 0) | n/a |
| X2: sell on an R72 breadth break the other way | 0 | 2,157 | 64.3% | Rs 331,379 (+Rs 0) | -Rs 1,582 | Rs 100,582 (+Rs 0) | -Rs 26,420 (+Rs 0) | n/a |
| X3: sell on an opposite signal from any of the three arms | 2 | 2,157 | 64.3% | Rs 332,712 (+Rs 1,333) | -Rs 1,580 | Rs 99,949 (-Rs 633) | -Rs 26,420 (+Rs 0) | n/a |
| X4: sell after two 5-min closes back through the entry level | 716 | 2,170 | 55.5% | Rs 269,174 (-Rs 62,205) | -Rs 1,126 | Rs 90,980 (-Rs 9,602) | -Rs 26,577 (-Rs 157) | n/a |
| X5: sell after two 5-min closes on the wrong side of EMA20 | 52 | 2,157 | 64.2% | Rs 327,784 (-Rs 3,595) | -Rs 1,581 | Rs 96,122 (-Rs 4,460) | -Rs 25,974 (+Rs 446) | n/a |
| X6: sell after two 5-min closes on the wrong side of the day's average price | 232 | 2,160 | 61.3% | Rs 269,452 (-Rs 61,927) | -Rs 1,456 | Rs 101,319 (+Rs 737) | -Rs 37,862 (-Rs 11,442) | n/a |
| X7: sell when the 5-min Supertrend(10, 3) flips against the trade | 9 | 2,157 | 64.3% | Rs 333,280 (+Rs 1,901) | -Rs 1,580 | Rs 95,954 (-Rs 4,628) | -Rs 24,839 (+Rs 1,581) | n/a |
| X8: sell when breadth turns (4 of 5 indices on the wrong side of their day's average, two bars) | 177 | 2,160 | 62.2% | Rs 266,906 (-Rs 64,473) | -Rs 1,502 | Rs 100,590 (+Rs 8) | -Rs 37,367 (-Rs 10,947) | n/a |
| X9: sell when NIFTY's 15-min close is on the wrong side of its EMA20 | 188 | 2,172 | 62.2% | Rs 269,210 (-Rs 62,169) | -Rs 1,462 | Rs 104,698 (+Rs 4,116) | -Rs 35,718 (-Rs 9,298) | n/a |

Why each one fails or passes (R72 Breadth Donchian):

- **X1** (fail): design: 2 trades changed / removed < 40; holdout: net 98,609 not better than B's 98,609; holdout: 0 trades changed / removed < 40; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 0 on B's losers, gave up Rs 0 on B's winners; BH q 1 (m = 4,868); better in 2 of 4 design quarters.
- **X2** (fail): design: net 232,769 not better than B's 232,769; design: 0 trades changed / removed < 40; holdout: net 98,609 not better than B's 98,609; holdout: 0 trades changed / removed < 40; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 0 on B's losers, gave up Rs 0 on B's winners; BH q 1 (m = 4,868); better in 0 of 4 design quarters.
- **X3** (fail): design: 2 trades changed / removed < 40; holdout: net 98,609 not better than B's 98,609; holdout: 0 trades changed / removed < 40; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 0 on B's losers, gave up Rs 0 on B's winners; BH q 1 (m = 4,868); better in 2 of 4 design quarters.
- **X4** (fail): design: net 186,831 not better than B's 232,769; holdout: net 82,344 not better than B's 98,609; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 88,971 on B's losers, gave up Rs 106,923 on B's winners; BH q 1 (m = 4,868); better in 0 of 4 design quarters.
- **X5** (fail): design: net 226,043 not better than B's 232,769; design: 38 trades changed / removed < 40; holdout: 14 trades changed / removed < 40; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 0.9995 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 3,132 on B's losers, gave up Rs 0 on B's winners; BH q 1 (m = 4,868); better in 0 of 4 design quarters.
- **X6** (fail): design: net 176,321 not better than B's 232,769; design: max drawdown 101,319 above B's 100,582; holdout: net 93,132 not better than B's 98,609; holdout: max drawdown 36,639 above B's 36,477; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 32,852 on B's losers, gave up Rs 38,958 on B's winners; BH q 1 (m = 4,868); better in 0 of 4 design quarters.
- **X7** (fail): design: 9 trades changed / removed < 40; holdout: net 98,609 not better than B's 98,609; holdout: 0 trades changed / removed < 40; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 0 on B's losers, gave up Rs 0 on B's winners; BH q 1 (m = 4,868); better in 0 of 4 design quarters.
- **X8** (fail): design: net 184,711 not better than B's 232,769; design: max drawdown 100,590 above B's 100,582; holdout: net 82,196 not better than B's 98,609; holdout: max drawdown 41,869 above B's 36,477; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 29,053 on B's losers, gave up Rs 46,095 on B's winners; BH q 1 (m = 4,868); better in 0 of 4 design quarters.
- **X9** (fail): design: net 206,277 not better than B's 232,769; design: max drawdown 104,698 above B's 100,582; holdout: net 62,933 not better than B's 98,609; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 0.9300 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 6,356 on B's losers, gave up Rs 50,799 on B's winners; BH q 1 (m = 4,868); better in 1 of 4 design quarters.

## R202 trend-turn exits: R51 Donchian 20 + Supertrend, holdout 1 Apr - 5 Oct 2026 (read once)

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p | verdict |
|---|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 652 | 60.4% | Rs 43,680 | -Rs 1,429 | Rs 45,825 | -Rs 29,871 | - | - |
| X1: sell on a Donchian-20 break the other way (R51's opposite signal) | 1 | 652 | 60.4% | Rs 43,800 (+Rs 120) | -Rs 1,428 | Rs 45,825 (+Rs 0) | -Rs 29,871 (+Rs 0) | 0.700 | fail |
| X2: sell on an R72 breadth break the other way | 1 | 652 | 60.4% | Rs 44,242 (+Rs 562) | -Rs 1,427 | Rs 45,825 (+Rs 0) | -Rs 29,871 (+Rs 0) | 0.411 | fail |
| X3: sell on an opposite signal from any of the three arms | 1 | 652 | 60.4% | Rs 44,242 (+Rs 562) | -Rs 1,427 | Rs 45,825 (+Rs 0) | -Rs 29,871 (+Rs 0) | 0.401 | fail |
| X4: sell after two 5-min closes back through the entry level | 274 | 662 | 51.1% | Rs 4,733 (-Rs 38,947) | -Rs 1,063 | Rs 51,337 (+Rs 5,512) | -Rs 34,560 (-Rs 4,689) | 1.000 | fail |
| X5: sell after two 5-min closes on the wrong side of EMA20 | 21 | 652 | 60.4% | Rs 45,382 (+Rs 1,702) | -Rs 1,422 | Rs 44,368 (-Rs 1,457) | -Rs 28,414 (+Rs 1,457) | 1.000 | fail |
| X6: sell after two 5-min closes on the wrong side of the day's average price | 123 | 653 | 56.0% | Rs 24,220 (-Rs 19,460) | -Rs 1,259 | Rs 47,271 (+Rs 1,446) | -Rs 31,245 (-Rs 1,374) | 1.000 | fail |
| X7: sell when the 5-min Supertrend(10, 3) flips against the trade | 5 | 652 | 60.4% | Rs 43,915 (+Rs 235) | -Rs 1,428 | Rs 45,825 (+Rs 0) | -Rs 29,871 (+Rs 0) | 0.995 | fail |
| X8: sell when breadth turns (4 of 5 indices on the wrong side of their day's average, two bars) | 118 | 655 | 57.0% | Rs 13,964 (-Rs 29,716) | -Rs 1,295 | Rs 51,806 (+Rs 5,981) | -Rs 35,056 (-Rs 5,185) | 1.000 | fail |
| X9: sell when NIFTY's 15-min close is on the wrong side of its EMA20 | 98 | 660 | 57.4% | Rs 26,284 (-Rs 17,396) | -Rs 1,281 | Rs 48,060 (+Rs 2,235) | -Rs 31,152 (-Rs 1,281) | 0.982 | fail |

## R202 trend-turn exits: R51 Donchian 20 + Supertrend, design 21 Nov 2024 - 31 Mar 2026

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 2,170 | 62.6% | Rs 147,686 | -Rs 1,579 | Rs 85,937 | -Rs 36,762 | - |
| X1: sell on a Donchian-20 break the other way (R51's opposite signal) | 3 | 2,170 | 62.5% | Rs 142,504 (-Rs 5,182) | -Rs 1,579 | Rs 91,770 (+Rs 5,833) | -Rs 36,129 (+Rs 633) | 0.994 |
| X2: sell on an R72 breadth break the other way | 1 | 2,170 | 62.5% | Rs 141,220 (-Rs 6,466) | -Rs 1,581 | Rs 92,403 (+Rs 6,466) | -Rs 36,762 (+Rs 0) | 1.000 |
| X3: sell on an opposite signal from any of the three arms | 3 | 2,170 | 62.5% | Rs 142,504 (-Rs 5,182) | -Rs 1,579 | Rs 91,770 (+Rs 5,833) | -Rs 36,129 (+Rs 633) | 0.997 |
| X4: sell after two 5-min closes back through the entry level | 721 | 2,192 | 54.8% | Rs 97,656 (-Rs 50,030) | -Rs 1,174 | Rs 124,387 (+Rs 38,450) | -Rs 41,121 (-Rs 4,359) | 1.000 |
| X5: sell after two 5-min closes on the wrong side of EMA20 | 64 | 2,170 | 62.5% | Rs 130,554 (-Rs 17,132) | -Rs 1,592 | Rs 88,665 (+Rs 2,728) | -Rs 38,216 (-Rs 1,454) | 1.000 |
| X6: sell after two 5-min closes on the wrong side of the day's average price | 231 | 2,172 | 60.3% | Rs 100,244 (-Rs 47,442) | -Rs 1,478 | Rs 108,363 (+Rs 22,426) | -Rs 32,838 (+Rs 3,924) | 1.000 |
| X7: sell when the 5-min Supertrend(10, 3) flips against the trade | 17 | 2,170 | 62.5% | Rs 138,727 (-Rs 8,959) | -Rs 1,582 | Rs 89,646 (+Rs 3,709) | -Rs 32,615 (+Rs 4,147) | 1.000 |
| X8: sell when breadth turns (4 of 5 indices on the wrong side of their day's average, two bars) | 192 | 2,173 | 61.0% | Rs 99,585 (-Rs 48,101) | -Rs 1,522 | Rs 105,565 (+Rs 19,628) | -Rs 33,041 (+Rs 3,721) | 1.000 |
| X9: sell when NIFTY's 15-min close is on the wrong side of its EMA20 | 281 | 2,189 | 60.2% | Rs 102,052 (-Rs 45,634) | -Rs 1,438 | Rs 127,278 (+Rs 41,341) | -Rs 47,469 (-Rs 10,707) | 1.000 |

## R202 trend-turn exits: R51 Donchian 20 + Supertrend, last year 24 Nov 2025 - 5 Oct 2026 (descriptive)

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 1,165 | 63.1% | Rs 143,844 | -Rs 1,519 | Rs 54,101 | -Rs 29,871 | - |
| X1: sell on a Donchian-20 break the other way (R51's opposite signal) | 1 | 1,165 | 63.1% | Rs 143,963 (+Rs 119) | -Rs 1,519 | Rs 54,101 (+Rs 0) | -Rs 29,871 (+Rs 0) | n/a |
| X2: sell on an R72 breadth break the other way | 1 | 1,165 | 63.1% | Rs 144,405 (+Rs 561) | -Rs 1,518 | Rs 54,101 (+Rs 0) | -Rs 29,871 (+Rs 0) | n/a |
| X3: sell on an opposite signal from any of the three arms | 1 | 1,165 | 63.1% | Rs 144,405 (+Rs 561) | -Rs 1,518 | Rs 54,101 (+Rs 0) | -Rs 29,871 (+Rs 0) | n/a |
| X4: sell after two 5-min closes back through the entry level | 445 | 1,177 | 53.9% | Rs 91,854 (-Rs 51,990) | -Rs 1,098 | Rs 74,368 (+Rs 20,267) | -Rs 34,560 (-Rs 4,689) | n/a |
| X5: sell after two 5-min closes on the wrong side of EMA20 | 29 | 1,165 | 63.1% | Rs 146,056 (+Rs 2,212) | -Rs 1,514 | Rs 52,500 (-Rs 1,601) | -Rs 28,414 (+Rs 1,457) | n/a |
| X6: sell after two 5-min closes on the wrong side of the day's average price | 177 | 1,166 | 59.8% | Rs 112,631 (-Rs 31,213) | -Rs 1,380 | Rs 58,123 (+Rs 4,022) | -Rs 31,245 (-Rs 1,374) | n/a |
| X7: sell when the 5-min Supertrend(10, 3) flips against the trade | 7 | 1,165 | 63.1% | Rs 144,721 (+Rs 877) | -Rs 1,517 | Rs 54,101 (+Rs 0) | -Rs 29,871 (+Rs 0) | n/a |
| X8: sell when breadth turns (4 of 5 indices on the wrong side of their day's average, two bars) | 153 | 1,168 | 60.7% | Rs 103,115 (-Rs 40,729) | -Rs 1,430 | Rs 63,363 (+Rs 9,262) | -Rs 35,056 (-Rs 5,185) | n/a |
| X9: sell when NIFTY's 15-min close is on the wrong side of its EMA20 | 162 | 1,177 | 60.3% | Rs 136,439 (-Rs 7,405) | -Rs 1,331 | Rs 53,226 (-Rs 875) | -Rs 31,152 (-Rs 1,281) | n/a |

## R202 trend-turn exits: R51 Donchian 20 + Supertrend, total 21 Nov 2024 - 5 Oct 2026

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 2,822 | 62.1% | Rs 191,366 | -Rs 1,543 | Rs 85,937 | -Rs 36,762 | - |
| X1: sell on a Donchian-20 break the other way (R51's opposite signal) | 4 | 2,822 | 62.0% | Rs 186,304 (-Rs 5,062) | -Rs 1,543 | Rs 91,770 (+Rs 5,833) | -Rs 36,129 (+Rs 633) | n/a |
| X2: sell on an R72 breadth break the other way | 2 | 2,822 | 62.0% | Rs 185,462 (-Rs 5,904) | -Rs 1,544 | Rs 92,403 (+Rs 6,466) | -Rs 36,762 (+Rs 0) | n/a |
| X3: sell on an opposite signal from any of the three arms | 4 | 2,822 | 62.0% | Rs 186,746 (-Rs 4,620) | -Rs 1,543 | Rs 91,770 (+Rs 5,833) | -Rs 36,129 (+Rs 633) | n/a |
| X4: sell after two 5-min closes back through the entry level | 995 | 2,854 | 53.9% | Rs 102,389 (-Rs 88,977) | -Rs 1,146 | Rs 124,387 (+Rs 38,450) | -Rs 41,121 (-Rs 4,359) | n/a |
| X5: sell after two 5-min closes on the wrong side of EMA20 | 85 | 2,822 | 62.0% | Rs 175,936 (-Rs 15,430) | -Rs 1,551 | Rs 88,665 (+Rs 2,728) | -Rs 38,216 (-Rs 1,454) | n/a |
| X6: sell after two 5-min closes on the wrong side of the day's average price | 354 | 2,825 | 59.3% | Rs 124,464 (-Rs 66,902) | -Rs 1,423 | Rs 108,363 (+Rs 22,426) | -Rs 32,838 (+Rs 3,924) | n/a |
| X7: sell when the 5-min Supertrend(10, 3) flips against the trade | 22 | 2,822 | 62.0% | Rs 182,641 (-Rs 8,725) | -Rs 1,545 | Rs 89,646 (+Rs 3,709) | -Rs 32,615 (+Rs 4,147) | n/a |
| X8: sell when breadth turns (4 of 5 indices on the wrong side of their day's average, two bars) | 310 | 2,828 | 60.0% | Rs 113,549 (-Rs 77,817) | -Rs 1,466 | Rs 105,565 (+Rs 19,628) | -Rs 35,056 (+Rs 1,706) | n/a |
| X9: sell when NIFTY's 15-min close is on the wrong side of its EMA20 | 379 | 2,849 | 59.6% | Rs 128,336 (-Rs 63,030) | -Rs 1,400 | Rs 127,278 (+Rs 41,341) | -Rs 47,469 (-Rs 10,707) | n/a |

Why each one fails or passes (R51 Donchian 20 + Supertrend):

- **X1** (fail): design: net 142,504 not better than B's 147,686; design: max drawdown 91,770 above B's 85,937; design: 3 trades changed / removed < 40; holdout: 1 trades changed / removed < 40; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 0.7002 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 120 on B's losers, gave up Rs 0 on B's winners; BH q 1 (m = 4,868); better in 1 of 4 design quarters.
- **X2** (fail): design: net 141,220 not better than B's 147,686; design: max drawdown 92,403 above B's 85,937; design: 1 trades changed / removed < 40; holdout: 1 trades changed / removed < 40; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 0.4113 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 562 on B's losers, gave up Rs 0 on B's winners; BH q 1 (m = 4,868); better in 0 of 4 design quarters.
- **X3** (fail): design: net 142,504 not better than B's 147,686; design: max drawdown 91,770 above B's 85,937; design: 3 trades changed / removed < 40; holdout: 1 trades changed / removed < 40; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 0.4013 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 562 on B's losers, gave up Rs 0 on B's winners; BH q 1 (m = 4,868); better in 1 of 4 design quarters.
- **X4** (fail): design: net 97,656 not better than B's 147,686; design: max drawdown 124,387 above B's 85,937; holdout: net 4,733 not better than B's 43,680; holdout: max drawdown 51,337 above B's 45,825; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 76,684 on B's losers, gave up Rs 121,605 on B's winners; BH q 1 (m = 4,868); better in 1 of 4 design quarters.
- **X5** (fail): design: net 130,554 not better than B's 147,686; design: max drawdown 88,665 above B's 85,937; holdout: 21 trades changed / removed < 40; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 1,690 on B's losers, gave up Rs 0 on B's winners; BH q 1 (m = 4,868); better in 1 of 4 design quarters.
- **X6** (fail): design: net 100,244 not better than B's 147,686; design: max drawdown 108,363 above B's 85,937; holdout: net 24,220 not better than B's 43,680; holdout: max drawdown 47,271 above B's 45,825; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 30,663 on B's losers, gave up Rs 50,310 on B's winners; BH q 1 (m = 4,868); better in 1 of 4 design quarters.
- **X7** (fail): design: net 138,727 not better than B's 147,686; design: max drawdown 89,646 above B's 85,937; design: 17 trades changed / removed < 40; holdout: 5 trades changed / removed < 40; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 0.9950 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 235 on B's losers, gave up Rs 0 on B's winners; BH q 1 (m = 4,868); better in 1 of 4 design quarters.
- **X8** (fail): design: net 99,585 not better than B's 147,686; design: max drawdown 105,565 above B's 85,937; holdout: net 13,964 not better than B's 43,680; holdout: max drawdown 51,806 above B's 45,825; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 30,038 on B's losers, gave up Rs 60,577 on B's winners; BH q 1 (m = 4,868); better in 0 of 4 design quarters.
- **X9** (fail): design: net 102,052 not better than B's 147,686; design: max drawdown 127,278 above B's 85,937; holdout: net 26,284 not better than B's 43,680; holdout: max drawdown 48,060 above B's 45,825; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 0.9820 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 25,545 on B's losers, gave up Rs 55,629 on B's winners; BH q 1 (m = 4,868); better in 1 of 4 design quarters.

## R202 trend-turn exits: Liquidity 15+5, holdout 1 Apr - 5 Oct 2026 (read once)

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p | verdict |
|---|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 330 | 43.9% | Rs 11,087 | -Rs 913 | Rs 59,441 | -Rs 32,670 | - | - |
| X1: sell on a Donchian-20 break the other way (R51's opposite signal) | 0 | 330 | 43.9% | Rs 11,087 (+Rs 0) | -Rs 913 | Rs 59,441 (+Rs 0) | -Rs 32,670 (+Rs 0) | 1.000 | fail |
| X2: sell on an R72 breadth break the other way | 0 | 330 | 43.9% | Rs 11,087 (+Rs 0) | -Rs 913 | Rs 59,441 (+Rs 0) | -Rs 32,670 (+Rs 0) | 1.000 | fail |
| X3: sell on an opposite signal from any of the three arms | 0 | 330 | 43.9% | Rs 11,087 (+Rs 0) | -Rs 913 | Rs 59,441 (+Rs 0) | -Rs 32,670 (+Rs 0) | 1.000 | fail |
| X4: sell after two 5-min closes back through the entry level | 52 | 330 | 41.2% | Rs 7,998 (-Rs 3,089) | -Rs 828 | Rs 55,779 (-Rs 3,662) | -Rs 27,346 (+Rs 5,324) | 0.997 | fail |
| X5: sell after two 5-min closes on the wrong side of EMA20 | 7 | 330 | 44.2% | Rs 760 (-Rs 10,327) | -Rs 918 | Rs 63,382 (+Rs 3,941) | -Rs 32,670 (+Rs 0) | 0.954 | fail |
| X6: sell after two 5-min closes on the wrong side of the day's average price | 28 | 330 | 43.3% | Rs 11,095 (+Rs 8) | -Rs 884 | Rs 57,230 (-Rs 2,211) | -Rs 28,385 (+Rs 4,285) | 0.976 | fail |
| X7: sell when the 5-min Supertrend(10, 3) flips against the trade | 2 | 330 | 43.9% | Rs 10,683 (-Rs 404) | -Rs 913 | Rs 59,441 (+Rs 0) | -Rs 32,670 (+Rs 0) | 0.587 | fail |
| X8: sell when breadth turns (4 of 5 indices on the wrong side of their day's average, two bars) | 22 | 330 | 43.0% | -Rs 1,483 (-Rs 12,570) | -Rs 883 | Rs 60,616 (+Rs 1,175) | -Rs 32,322 (+Rs 348) | 0.957 | fail |
| X9: sell when NIFTY's 15-min close is on the wrong side of its EMA20 | 6 | 330 | 43.9% | Rs 9,594 (-Rs 1,493) | -Rs 908 | Rs 59,693 (+Rs 252) | -Rs 32,922 (-Rs 252) | 0.434 | fail |

## R202 trend-turn exits: Liquidity 15+5, design 21 Nov 2024 - 31 Mar 2026

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 878 | 50.9% | Rs 231,580 | -Rs 909 | Rs 25,771 | -Rs 9,714 | - |
| X1: sell on a Donchian-20 break the other way (R51's opposite signal) | 10 | 878 | 51.0% | Rs 214,915 (-Rs 16,665) | -Rs 910 | Rs 25,771 (+Rs 0) | -Rs 13,143 (-Rs 3,429) | 0.962 |
| X2: sell on an R72 breadth break the other way | 7 | 878 | 50.9% | Rs 217,000 (-Rs 14,580) | -Rs 909 | Rs 25,771 (+Rs 0) | -Rs 13,390 (-Rs 3,676) | 0.915 |
| X3: sell on an opposite signal from any of the three arms | 10 | 878 | 51.0% | Rs 214,915 (-Rs 16,665) | -Rs 910 | Rs 25,771 (+Rs 0) | -Rs 13,143 (-Rs 3,429) | 0.961 |
| X4: sell after two 5-min closes back through the entry level | 110 | 878 | 49.4% | Rs 194,214 (-Rs 37,366) | -Rs 855 | Rs 25,710 (-Rs 61) | -Rs 8,177 (+Rs 1,537) | 1.000 |
| X5: sell after two 5-min closes on the wrong side of EMA20 | 22 | 878 | 51.1% | Rs 232,609 (+Rs 1,029) | -Rs 911 | Rs 25,771 (+Rs 0) | -Rs 12,351 (-Rs 2,637) | 0.490 |
| X6: sell after two 5-min closes on the wrong side of the day's average price | 35 | 878 | 50.8% | Rs 187,880 (-Rs 43,700) | -Rs 895 | Rs 24,506 (-Rs 1,265) | -Rs 13,078 (-Rs 3,364) | 1.000 |
| X7: sell when the 5-min Supertrend(10, 3) flips against the trade | 13 | 878 | 51.0% | Rs 214,816 (-Rs 16,764) | -Rs 910 | Rs 25,771 (+Rs 0) | -Rs 13,143 (-Rs 3,429) | 0.898 |
| X8: sell when breadth turns (4 of 5 indices on the wrong side of their day's average, two bars) | 31 | 878 | 51.0% | Rs 201,787 (-Rs 29,793) | -Rs 896 | Rs 24,506 (-Rs 1,265) | -Rs 13,390 (-Rs 3,676) | 0.998 |
| X9: sell when NIFTY's 15-min close is on the wrong side of its EMA20 | 27 | 879 | 50.5% | Rs 199,522 (-Rs 32,058) | -Rs 899 | Rs 27,825 (+Rs 2,054) | -Rs 9,714 (+Rs 0) | 0.999 |

## R202 trend-turn exits: Liquidity 15+5, last year 24 Nov 2025 - 5 Oct 2026 (descriptive)

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 590 | 44.4% | Rs 106,212 | -Rs 928 | Rs 60,281 | -Rs 32,670 | - |
| X1: sell on a Donchian-20 break the other way (R51's opposite signal) | 5 | 590 | 44.6% | Rs 92,617 (-Rs 13,595) | -Rs 929 | Rs 63,385 (+Rs 3,104) | -Rs 32,670 (+Rs 0) | n/a |
| X2: sell on an R72 breadth break the other way | 3 | 590 | 44.4% | Rs 97,114 (-Rs 9,098) | -Rs 928 | Rs 60,281 (+Rs 0) | -Rs 32,670 (+Rs 0) | n/a |
| X3: sell on an opposite signal from any of the three arms | 5 | 590 | 44.6% | Rs 92,617 (-Rs 13,595) | -Rs 929 | Rs 63,385 (+Rs 3,104) | -Rs 32,670 (+Rs 0) | n/a |
| X4: sell after two 5-min closes back through the entry level | 89 | 590 | 42.2% | Rs 90,577 (-Rs 15,635) | -Rs 850 | Rs 65,921 (+Rs 5,640) | -Rs 27,346 (+Rs 5,324) | n/a |
| X5: sell after two 5-min closes on the wrong side of EMA20 | 15 | 590 | 44.9% | Rs 93,120 (-Rs 13,092) | -Rs 933 | Rs 64,222 (+Rs 3,941) | -Rs 32,670 (+Rs 0) | n/a |
| X6: sell after two 5-min closes on the wrong side of the day's average price | 44 | 590 | 44.2% | Rs 68,976 (-Rs 37,236) | -Rs 911 | Rs 74,485 (+Rs 14,204) | -Rs 28,385 (+Rs 4,285) | n/a |
| X7: sell when the 5-min Supertrend(10, 3) flips against the trade | 7 | 590 | 44.6% | Rs 94,279 (-Rs 11,933) | -Rs 929 | Rs 63,385 (+Rs 3,104) | -Rs 32,670 (+Rs 0) | n/a |
| X8: sell when breadth turns (4 of 5 indices on the wrong side of their day's average, two bars) | 33 | 590 | 44.2% | Rs 75,505 (-Rs 30,707) | -Rs 910 | Rs 63,933 (+Rs 3,652) | -Rs 32,322 (+Rs 348) | n/a |
| X9: sell when NIFTY's 15-min close is on the wrong side of its EMA20 | 14 | 590 | 44.9% | Rs 103,303 (-Rs 2,909) | -Rs 928 | Rs 60,533 (+Rs 252) | -Rs 32,922 (-Rs 252) | n/a |

## R202 trend-turn exits: Liquidity 15+5, total 21 Nov 2024 - 5 Oct 2026

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 1,208 | 49.0% | Rs 242,666 | -Rs 910 | Rs 60,281 | -Rs 32,670 | - |
| X1: sell on a Donchian-20 break the other way (R51's opposite signal) | 10 | 1,208 | 49.1% | Rs 226,002 (-Rs 16,664) | -Rs 911 | Rs 63,385 (+Rs 3,104) | -Rs 32,670 (+Rs 0) | n/a |
| X2: sell on an R72 breadth break the other way | 7 | 1,208 | 49.0% | Rs 228,086 (-Rs 14,580) | -Rs 910 | Rs 60,281 (+Rs 0) | -Rs 32,670 (+Rs 0) | n/a |
| X3: sell on an opposite signal from any of the three arms | 10 | 1,208 | 49.1% | Rs 226,002 (-Rs 16,664) | -Rs 911 | Rs 63,385 (+Rs 3,104) | -Rs 32,670 (+Rs 0) | n/a |
| X4: sell after two 5-min closes back through the entry level | 162 | 1,208 | 47.2% | Rs 202,212 (-Rs 40,454) | -Rs 847 | Rs 65,921 (+Rs 5,640) | -Rs 27,346 (+Rs 5,324) | n/a |
| X5: sell after two 5-min closes on the wrong side of EMA20 | 29 | 1,208 | 49.2% | Rs 233,369 (-Rs 9,297) | -Rs 913 | Rs 64,222 (+Rs 3,941) | -Rs 32,670 (+Rs 0) | n/a |
| X6: sell after two 5-min closes on the wrong side of the day's average price | 63 | 1,208 | 48.8% | Rs 198,975 (-Rs 43,691) | -Rs 892 | Rs 74,485 (+Rs 14,204) | -Rs 28,385 (+Rs 4,285) | n/a |
| X7: sell when the 5-min Supertrend(10, 3) flips against the trade | 15 | 1,208 | 49.1% | Rs 225,500 (-Rs 17,166) | -Rs 911 | Rs 63,385 (+Rs 3,104) | -Rs 32,670 (+Rs 0) | n/a |
| X8: sell when breadth turns (4 of 5 indices on the wrong side of their day's average, two bars) | 53 | 1,208 | 48.8% | Rs 200,304 (-Rs 42,362) | -Rs 892 | Rs 63,933 (+Rs 3,652) | -Rs 32,322 (+Rs 348) | n/a |
| X9: sell when NIFTY's 15-min close is on the wrong side of its EMA20 | 33 | 1,209 | 48.7% | Rs 209,116 (-Rs 33,550) | -Rs 902 | Rs 60,533 (+Rs 252) | -Rs 32,922 (-Rs 252) | n/a |

Why each one fails or passes (Liquidity 15+5):

- **X1** (fail): design: net 214,915 not better than B's 231,580; design: 10 trades changed / removed < 40; holdout: net 11,087 not better than B's 11,087; holdout: 0 trades changed / removed < 40; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 0 on B's losers, gave up Rs 0 on B's winners; BH q 1 (m = 4,868); better in 2 of 4 design quarters.
- **X2** (fail): design: net 217,000 not better than B's 231,580; design: 7 trades changed / removed < 40; holdout: net 11,087 not better than B's 11,087; holdout: 0 trades changed / removed < 40; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 0 on B's losers, gave up Rs 0 on B's winners; BH q 1 (m = 4,868); better in 1 of 4 design quarters.
- **X3** (fail): design: net 214,915 not better than B's 231,580; design: 10 trades changed / removed < 40; holdout: net 11,087 not better than B's 11,087; holdout: 0 trades changed / removed < 40; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 0 on B's losers, gave up Rs 0 on B's winners; BH q 1 (m = 4,868); better in 2 of 4 design quarters.
- **X4** (fail): design: net 194,214 not better than B's 231,580; holdout: net 7,998 not better than B's 11,087; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 0.9970 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 13,478 on B's losers, gave up Rs 16,567 on B's winners; BH q 1 (m = 4,868); better in 2 of 4 design quarters.
- **X5** (fail): design: 22 trades changed / removed < 40; holdout: net 760 not better than B's 11,087; holdout: max drawdown 63,382 above B's 59,441; holdout: 7 trades changed / removed < 40; holdout twin p 0.9535 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 111 on B's losers, gave up Rs 10,437 on B's winners; BH q 1 (m = 4,868); better in 3 of 4 design quarters.
- **X6** (fail): design: net 187,880 not better than B's 231,580; design: 35 trades changed / removed < 40; holdout: 28 trades changed / removed < 40; holdout twin p 0.9760 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 5,997 on B's losers, gave up Rs 5,989 on B's winners; BH q 1 (m = 4,868); better in 3 of 4 design quarters.
- **X7** (fail): design: net 214,816 not better than B's 231,580; design: 13 trades changed / removed < 40; holdout: net 10,683 not better than B's 11,087; holdout: 2 trades changed / removed < 40; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 0.5867 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 0 on B's losers, gave up Rs 404 on B's winners; BH q 1 (m = 4,868); better in 2 of 4 design quarters.
- **X8** (fail): design: net 201,787 not better than B's 231,580; design: 31 trades changed / removed < 40; holdout: net -1,483 not better than B's 11,087; holdout: max drawdown 60,616 above B's 59,441; holdout: 22 trades changed / removed < 40; holdout twin p 0.9570 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 5,678 on B's losers, gave up Rs 18,248 on B's winners; BH q 1 (m = 4,868); better in 3 of 4 design quarters.
- **X9** (fail): design: net 199,522 not better than B's 231,580; design: max drawdown 27,825 above B's 25,771; design: 27 trades changed / removed < 40; holdout: net 9,594 not better than B's 11,087; holdout: max drawdown 59,693 above B's 59,441; holdout: 6 trades changed / removed < 40; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 0.4343 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 874 on B's losers, gave up Rs 2,367 on B's winners; BH q 1 (m = 4,868); better in 2 of 4 design quarters.

## R203 other loss-cutting rules: R72 Breadth Donchian, holdout 1 Apr - 5 Oct 2026 (read once)

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p | verdict |
|---|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 596 | 60.4% | Rs 98,609 | -Rs 1,475 | Rs 36,477 | -Rs 13,690 | - | - |
| W-hour: skip the worst entry-time bucket (walk-forward) | 85 | 511 | 60.9% | Rs 92,498 (-Rs 6,111) | -Rs 1,454 | Rs 47,315 (+Rs 10,838) | -Rs 30,103 (-Rs 16,413) | 0.311 | fail |
| W-vix: skip the worst VIX regime (walk-forward) | 0 | 596 | 60.4% | Rs 98,609 (+Rs 0) | -Rs 1,475 | Rs 36,477 (+Rs 0) | -Rs 13,690 (+Rs 0) | 1.000 | fail |
| W-gap: skip the worst opening-gap bucket (walk-forward) | 46 | 550 | 58.9% | Rs 55,371 (-Rs 43,238) | -Rs 1,504 | Rs 36,477 (+Rs 0) | -Rs 13,690 (+Rs 0) | 0.996 | fail |
| W-dow: skip the worst weekday (walk-forward) | 132 | 464 | 60.3% | Rs 51,741 (-Rs 46,868) | -Rs 1,462 | Rs 46,871 (+Rs 10,394) | -Rs 24,046 (-Rs 10,356) | 0.905 | fail |
| W-rv: skip the worst realised-volatility third (walk-forward) | 122 | 474 | 58.0% | Rs 16,258 (-Rs 82,351) | -Rs 1,542 | Rs 36,477 (+Rs 0) | -Rs 13,690 (+Rs 0) | 1.000 | fail |
| W-und: skip the worst index (walk-forward) | 0 | 596 | 60.4% | Rs 98,609 (+Rs 0) | -Rs 1,475 | Rs 36,477 (+Rs 0) | -Rs 13,690 (+Rs 0) | 1.000 | fail |
| W-trend: skip the worst trend-strength bucket (walk-forward) | 0 | 596 | 60.4% | Rs 98,609 (+Rs 0) | -Rs 1,475 | Rs 36,477 (+Rs 0) | -Rs 13,690 (+Rs 0) | 1.000 | fail |
| S10: entry stop at -10% instead of -15% | 130 | 595 | 57.6% | Rs 100,952 (+Rs 2,343) | -Rs 1,306 | Rs 36,726 (+Rs 249) | -Rs 10,249 (+Rs 3,441) | 1.000 | fail |
| DC3: no new entry after the arm is down Rs 3,000 closed that day | 67 | 529 | 62.2% | Rs 134,332 (+Rs 35,723) | -Rs 1,433 | Rs 23,871 (-Rs 12,606) | -Rs 1,875 (+Rs 11,815) | 0.001 | fail |
| DC5: no new entry after the arm is down Rs 5,000 closed that day | 37 | 559 | 61.7% | Rs 124,998 (+Rs 26,389) | -Rs 1,448 | Rs 23,428 (-Rs 13,049) | -Rs 5,407 (+Rs 8,283) | 0.001 | fail |
| L2: no new entry on an index after two losers in a row on it that day | 7 | 589 | 60.6% | Rs 105,332 (+Rs 6,723) | -Rs 1,463 | Rs 31,241 (-Rs 5,236) | -Rs 10,900 (+Rs 2,790) | 0.044 | fail |

## R203 other loss-cutting rules: R72 Breadth Donchian, design 21 Nov 2024 - 31 Mar 2026

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 1,561 | 65.7% | Rs 232,769 | -Rs 1,629 | Rs 100,582 | -Rs 26,420 | - |
| W-hour: skip the worst entry-time bucket (walk-forward) | 122 | 1,439 | 66.5% | Rs 234,977 (+Rs 2,208) | -Rs 1,656 | Rs 100,582 (+Rs 0) | -Rs 26,420 (+Rs 0) | 0.121 |
| W-vix: skip the worst VIX regime (walk-forward) | 401 | 1,160 | 63.8% | Rs 76,648 (-Rs 156,121) | -Rs 1,703 | Rs 94,929 (-Rs 5,653) | -Rs 26,420 (+Rs 0) | 1.000 |
| W-gap: skip the worst opening-gap bucket (walk-forward) | 274 | 1,287 | 64.3% | Rs 120,422 (-Rs 112,347) | -Rs 1,610 | Rs 98,613 (-Rs 1,969) | -Rs 26,420 (+Rs 0) | 0.998 |
| W-dow: skip the worst weekday (walk-forward) | 184 | 1,377 | 64.6% | Rs 137,485 (-Rs 95,284) | -Rs 1,619 | Rs 106,633 (+Rs 6,051) | -Rs 26,420 (+Rs 0) | 1.000 |
| W-rv: skip the worst realised-volatility third (walk-forward) | 446 | 1,115 | 63.3% | Rs 49,821 (-Rs 182,948) | -Rs 1,675 | Rs 104,649 (+Rs 4,067) | -Rs 26,420 (+Rs 0) | 1.000 |
| W-und: skip the worst index (walk-forward) | 114 | 1,447 | 65.2% | Rs 190,578 (-Rs 42,191) | -Rs 1,637 | Rs 96,579 (-Rs 4,003) | -Rs 26,420 (+Rs 0) | 0.932 |
| W-trend: skip the worst trend-strength bucket (walk-forward) | 254 | 1,307 | 66.0% | Rs 175,000 (-Rs 57,769) | -Rs 1,656 | Rs 97,880 (-Rs 2,702) | -Rs 26,420 (+Rs 0) | 0.802 |
| S10: entry stop at -10% instead of -15% | 358 | 1,563 | 61.5% | Rs 185,683 (-Rs 47,086) | -Rs 1,408 | Rs 90,579 (-Rs 10,003) | -Rs 25,299 (+Rs 1,121) | 1.000 |
| DC3: no new entry after the arm is down Rs 3,000 closed that day | 188 | 1,373 | 65.6% | Rs 197,865 (-Rs 34,904) | -Rs 1,632 | Rs 105,698 (+Rs 5,116) | -Rs 26,540 (-Rs 120) | 0.605 |
| DC5: no new entry after the arm is down Rs 5,000 closed that day | 134 | 1,427 | 65.5% | Rs 193,259 (-Rs 39,510) | -Rs 1,627 | Rs 102,413 (+Rs 1,831) | -Rs 24,122 (+Rs 2,298) | 0.864 |
| L2: no new entry on an index after two losers in a row on it that day | 25 | 1,536 | 65.9% | Rs 232,063 (-Rs 706) | -Rs 1,630 | Rs 96,317 (-Rs 4,265) | -Rs 25,734 (+Rs 686) | 0.340 |

## R203 other loss-cutting rules: R72 Breadth Donchian, last year 24 Nov 2025 - 5 Oct 2026 (descriptive)

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 938 | 64.8% | Rs 195,249 | -Rs 1,600 | Rs 42,286 | -Rs 13,690 | - |
| W-hour: skip the worst entry-time bucket (walk-forward) | 124 | 814 | 66.2% | Rs 204,609 (+Rs 9,360) | -Rs 1,598 | Rs 53,124 (+Rs 10,838) | -Rs 30,103 (-Rs 16,413) | n/a |
| W-vix: skip the worst VIX regime (walk-forward) | 0 | 938 | 64.8% | Rs 195,249 (+Rs 0) | -Rs 1,600 | Rs 42,286 (+Rs 0) | -Rs 13,690 (+Rs 0) | n/a |
| W-gap: skip the worst opening-gap bucket (walk-forward) | 152 | 786 | 62.5% | Rs 126,116 (-Rs 69,133) | -Rs 1,542 | Rs 39,242 (-Rs 3,044) | -Rs 13,690 (+Rs 0) | n/a |
| W-dow: skip the worst weekday (walk-forward) | 201 | 737 | 64.0% | Rs 97,037 (-Rs 98,212) | -Rs 1,612 | Rs 52,931 (+Rs 10,645) | -Rs 24,046 (-Rs 10,356) | n/a |
| W-rv: skip the worst realised-volatility third (walk-forward) | 168 | 770 | 63.8% | Rs 95,790 (-Rs 99,459) | -Rs 1,622 | Rs 42,286 (+Rs 0) | -Rs 13,690 (+Rs 0) | n/a |
| W-und: skip the worst index (walk-forward) | 0 | 938 | 64.8% | Rs 195,249 (+Rs 0) | -Rs 1,600 | Rs 42,286 (+Rs 0) | -Rs 13,690 (+Rs 0) | n/a |
| W-trend: skip the worst trend-strength bucket (walk-forward) | 76 | 862 | 64.4% | Rs 163,074 (-Rs 32,175) | -Rs 1,589 | Rs 45,673 (+Rs 3,387) | -Rs 13,690 (+Rs 0) | n/a |
| S10: entry stop at -10% instead of -15% | 202 | 937 | 60.9% | Rs 170,088 (-Rs 25,161) | -Rs 1,405 | Rs 59,379 (+Rs 17,093) | -Rs 10,249 (+Rs 3,441) | n/a |
| DC3: no new entry after the arm is down Rs 3,000 closed that day | 91 | 847 | 66.2% | Rs 221,141 (+Rs 25,892) | -Rs 1,589 | Rs 24,299 (-Rs 17,987) | -Rs 1,875 (+Rs 11,815) | n/a |
| DC5: no new entry after the arm is down Rs 5,000 closed that day | 56 | 882 | 65.7% | Rs 204,913 (+Rs 9,664) | -Rs 1,602 | Rs 29,237 (-Rs 13,049) | -Rs 5,407 (+Rs 8,283) | n/a |
| L2: no new entry on an index after two losers in a row on it that day | 14 | 924 | 65.0% | Rs 201,951 (+Rs 6,702) | -Rs 1,595 | Rs 37,050 (-Rs 5,236) | -Rs 10,900 (+Rs 2,790) | n/a |

## R203 other loss-cutting rules: R72 Breadth Donchian, total 21 Nov 2024 - 5 Oct 2026

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 2,157 | 64.3% | Rs 331,379 | -Rs 1,582 | Rs 100,582 | -Rs 26,420 | - |
| W-hour: skip the worst entry-time bucket (walk-forward) | 207 | 1,950 | 65.0% | Rs 327,475 (-Rs 3,904) | -Rs 1,596 | Rs 100,582 (+Rs 0) | -Rs 30,103 (-Rs 3,683) | n/a |
| W-vix: skip the worst VIX regime (walk-forward) | 401 | 1,756 | 62.6% | Rs 175,258 (-Rs 156,121) | -Rs 1,621 | Rs 94,929 (-Rs 5,653) | -Rs 26,420 (+Rs 0) | n/a |
| W-gap: skip the worst opening-gap bucket (walk-forward) | 320 | 1,837 | 62.7% | Rs 175,793 (-Rs 155,586) | -Rs 1,575 | Rs 98,613 (-Rs 1,969) | -Rs 26,420 (+Rs 0) | n/a |
| W-dow: skip the worst weekday (walk-forward) | 316 | 1,841 | 63.5% | Rs 189,226 (-Rs 142,153) | -Rs 1,576 | Rs 106,633 (+Rs 6,051) | -Rs 26,420 (+Rs 0) | n/a |
| W-rv: skip the worst realised-volatility third (walk-forward) | 568 | 1,589 | 61.7% | Rs 66,079 (-Rs 265,300) | -Rs 1,631 | Rs 104,649 (+Rs 4,067) | -Rs 26,420 (+Rs 0) | n/a |
| W-und: skip the worst index (walk-forward) | 114 | 2,043 | 63.8% | Rs 289,187 (-Rs 42,192) | -Rs 1,585 | Rs 96,579 (-Rs 4,003) | -Rs 26,420 (+Rs 0) | n/a |
| W-trend: skip the worst trend-strength bucket (walk-forward) | 254 | 1,903 | 64.2% | Rs 273,610 (-Rs 57,769) | -Rs 1,593 | Rs 97,880 (-Rs 2,702) | -Rs 26,420 (+Rs 0) | n/a |
| S10: entry stop at -10% instead of -15% | 488 | 2,158 | 60.5% | Rs 286,635 (-Rs 44,744) | -Rs 1,378 | Rs 90,579 (-Rs 10,003) | -Rs 25,299 (+Rs 1,121) | n/a |
| DC3: no new entry after the arm is down Rs 3,000 closed that day | 255 | 1,902 | 64.7% | Rs 332,197 (+Rs 818) | -Rs 1,573 | Rs 105,698 (+Rs 5,116) | -Rs 26,540 (-Rs 120) | n/a |
| DC5: no new entry after the arm is down Rs 5,000 closed that day | 171 | 1,986 | 64.4% | Rs 318,257 (-Rs 13,122) | -Rs 1,573 | Rs 102,413 (+Rs 1,831) | -Rs 24,122 (+Rs 2,298) | n/a |
| L2: no new entry on an index after two losers in a row on it that day | 32 | 2,125 | 64.4% | Rs 337,395 (+Rs 6,016) | -Rs 1,579 | Rs 96,317 (-Rs 4,265) | -Rs 25,734 (+Rs 686) | n/a |

Why each one fails or passes (R72 Breadth Donchian):

- **W-hour** (fail): holdout: net 92,498 not better than B's 98,609; holdout: max drawdown 47,315 above B's 36,477; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 0.3108 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 6,111 (36 of them losers); BH q 1 (m = 4,868); better in 2 of 4 design quarters. Walk-forward skips: {'2025-04-01': '11', '2025-07-01': '11', '2025-10-01': '11', '2026-01-01': '11', '2026-04-01': '11', '2026-07-01': '11', '2026-10-01': '11'}.
- **W-vix** (fail): design: net 76,648 not better than B's 232,769; holdout: net 98,609 not better than B's 98,609; holdout: 0 trades changed / removed < 40; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 0 (0 of them losers); BH q 1 (m = 4,868); better in 0 of 4 design quarters. Walk-forward skips: {'2025-04-01': 'HI', '2025-07-01': 'LO', '2025-10-01': None, '2026-01-01': None, '2026-04-01': None, '2026-07-01': None, '2026-10-01': None}.
- **W-gap** (fail): design: net 120,422 not better than B's 232,769; holdout: net 55,371 not better than B's 98,609; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 0.9960 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 43,238 (10 of them losers); BH q 1 (m = 4,868); better in 0 of 4 design quarters. Walk-forward skips: {'2025-04-01': 'under 0.25%', '2025-07-01': '0.75% or more', '2025-10-01': '0.75% or more', '2026-01-01': '0.75% or more', '2026-04-01': None, '2026-07-01': '0.75% or more', '2026-10-01': None}.
- **W-dow** (fail): design: net 137,485 not better than B's 232,769; design: max drawdown 106,633 above B's 100,582; holdout: net 51,741 not better than B's 98,609; holdout: max drawdown 46,871 above B's 36,477; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 0.9045 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 46,869 (52 of them losers); BH q 1 (m = 4,868); better in 0 of 4 design quarters. Walk-forward skips: {'2025-04-01': '2', '2025-07-01': '0', '2025-10-01': '0', '2026-01-01': '2', '2026-04-01': '3', '2026-07-01': '2', '2026-10-01': None}.
- **W-rv** (fail): design: net 49,821 not better than B's 232,769; design: max drawdown 104,649 above B's 100,582; holdout: net 16,258 not better than B's 98,609; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 0.9995 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 82,351 (37 of them losers); BH q 1 (m = 4,868); better in 0 of 4 design quarters. Walk-forward skips: {'2025-04-01': 'high', '2025-07-01': 'low', '2025-10-01': 'mid', '2026-01-01': 'mid', '2026-04-01': None, '2026-07-01': 'mid', '2026-10-01': None}.
- **W-und** (fail): design: net 190,578 not better than B's 232,769; holdout: net 98,609 not better than B's 98,609; holdout: 0 trades changed / removed < 40; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 0 (0 of them losers); BH q 1 (m = 4,868); better in 0 of 4 design quarters. Walk-forward skips: {'2025-04-01': 'FINNIFTY', '2025-07-01': 'NIFTY', '2025-10-01': None, '2026-01-01': None, '2026-04-01': None, '2026-07-01': None, '2026-10-01': None}.
- **W-trend** (fail): design: net 175,000 not better than B's 232,769; holdout: net 98,609 not better than B's 98,609; holdout: 0 trades changed / removed < 40; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 0 (0 of them losers); BH q 1 (m = 4,868); better in 1 of 4 design quarters. Walk-forward skips: {'2025-04-01': '0.6% or more', '2025-07-01': '0.3-0.6%', '2025-10-01': '0.3-0.6%', '2026-01-01': '0.3-0.6%', '2026-04-01': None, '2026-07-01': None, '2026-10-01': None}.
- **S10** (fail): design: net 185,683 not better than B's 232,769; holdout: max drawdown 36,726 above B's 36,477; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 36,306 on B's losers, gave up Rs 38,379 on B's winners; BH q 1 (m = 4,868); better in 0 of 4 design quarters.
- **DC3** (fail): design: net 197,865 not better than B's 232,769; design: max drawdown 105,698 above B's 100,582; walk-forward: better in 0 of 4 design quarters < 3; BH q 1 > 0.1. Holdout: the removed trades had made -Rs 35,722 (36 of them losers); BH q 1 (m = 4,868); better in 0 of 4 design quarters.
- **DC5** (fail): design: net 193,259 not better than B's 232,769; design: max drawdown 102,413 above B's 100,582; holdout: 37 trades changed / removed < 40; walk-forward: better in 0 of 4 design quarters < 3; BH q 1 > 0.1. Holdout: the removed trades had made -Rs 26,388 (22 of them losers); BH q 1 (m = 4,868); better in 0 of 4 design quarters.
- **L2** (fail): design: net 232,063 not better than B's 232,769; design: 25 trades changed / removed < 40; holdout: 7 trades changed / removed < 40; walk-forward: better in 1 of 4 design quarters < 3; BH q 1 > 0.1. Holdout: the removed trades had made -Rs 6,723 (4 of them losers); BH q 1 (m = 4,868); better in 1 of 4 design quarters.

## R203 other loss-cutting rules: R51 Donchian 20 + Supertrend, holdout 1 Apr - 5 Oct 2026 (read once)

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p | verdict |
|---|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 652 | 60.4% | Rs 43,680 | -Rs 1,429 | Rs 45,825 | -Rs 29,871 | - | - |
| W-hour: skip the worst entry-time bucket (walk-forward) | 98 | 554 | 61.4% | Rs 52,995 (+Rs 9,315) | -Rs 1,441 | Rs 39,592 (-Rs 6,233) | -Rs 23,638 (+Rs 6,233) | 0.140 | fail |
| W-vix: skip the worst VIX regime (walk-forward) | 0 | 652 | 60.4% | Rs 43,680 (+Rs 0) | -Rs 1,429 | Rs 45,825 (+Rs 0) | -Rs 29,871 (+Rs 0) | 1.000 | fail |
| W-gap: skip the worst opening-gap bucket (walk-forward) | 49 | 603 | 60.2% | Rs 35,998 (-Rs 7,682) | -Rs 1,467 | Rs 45,825 (+Rs 0) | -Rs 29,871 (+Rs 0) | 0.656 | fail |
| W-dow: skip the worst weekday (walk-forward) | 131 | 521 | 61.4% | Rs 59,440 (+Rs 15,760) | -Rs 1,369 | Rs 40,247 (-Rs 5,578) | -Rs 27,631 (+Rs 2,240) | 0.076 | fail |
| W-rv: skip the worst realised-volatility third (walk-forward) | 148 | 504 | 59.1% | -Rs 11,997 (-Rs 55,677) | -Rs 1,553 | Rs 45,825 (+Rs 0) | -Rs 29,871 (+Rs 0) | 0.995 | fail |
| W-und: skip the worst index (walk-forward) | 121 | 531 | 61.4% | Rs 51,355 (+Rs 7,675) | -Rs 1,512 | Rs 30,818 (-Rs 15,007) | -Rs 14,841 (+Rs 15,030) | 0.166 | fail |
| W-trend: skip the worst trend-strength bucket (walk-forward) | 0 | 652 | 60.4% | Rs 43,680 (+Rs 0) | -Rs 1,429 | Rs 45,825 (+Rs 0) | -Rs 29,871 (+Rs 0) | 1.000 | fail |
| S10: entry stop at -10% instead of -15% | 143 | 653 | 56.8% | Rs 23,649 (-Rs 20,031) | -Rs 1,309 | Rs 48,263 (+Rs 2,438) | -Rs 31,068 (-Rs 1,197) | 1.000 | fail |
| DC3: no new entry after the arm is down Rs 3,000 closed that day | 81 | 571 | 61.1% | Rs 55,424 (+Rs 11,744) | -Rs 1,416 | Rs 37,262 (-Rs 8,563) | -Rs 21,308 (+Rs 8,563) | 0.111 | fail |
| DC5: no new entry after the arm is down Rs 5,000 closed that day | 45 | 607 | 61.0% | Rs 55,514 (+Rs 11,834) | -Rs 1,420 | Rs 36,846 (-Rs 8,979) | -Rs 20,891 (+Rs 8,980) | 0.076 | fail |
| L2: no new entry on an index after two losers in a row on it that day | 15 | 637 | 60.6% | Rs 45,910 (+Rs 2,230) | -Rs 1,430 | Rs 46,454 (+Rs 629) | -Rs 30,499 (-Rs 628) | 0.275 | fail |

## R203 other loss-cutting rules: R51 Donchian 20 + Supertrend, design 21 Nov 2024 - 31 Mar 2026

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 2,170 | 62.6% | Rs 147,686 | -Rs 1,579 | Rs 85,937 | -Rs 36,762 | - |
| W-hour: skip the worst entry-time bucket (walk-forward) | 163 | 2,007 | 62.7% | Rs 111,949 (-Rs 35,737) | -Rs 1,622 | Rs 85,937 (+Rs 0) | -Rs 36,762 (+Rs 0) | 0.889 |
| W-vix: skip the worst VIX regime (walk-forward) | 767 | 1,403 | 63.4% | Rs 113,057 (-Rs 34,629) | -Rs 1,681 | Rs 76,457 (-Rs 9,480) | -Rs 36,762 (+Rs 0) | 0.321 |
| W-gap: skip the worst opening-gap bucket (walk-forward) | 308 | 1,862 | 62.4% | Rs 87,528 (-Rs 60,158) | -Rs 1,550 | Rs 90,849 (+Rs 4,912) | -Rs 36,762 (+Rs 0) | 0.919 |
| W-dow: skip the worst weekday (walk-forward) | 259 | 1,911 | 62.9% | Rs 144,406 (-Rs 3,280) | -Rs 1,565 | Rs 79,767 (-Rs 6,170) | -Rs 38,977 (-Rs 2,215) | 0.269 |
| W-rv: skip the worst realised-volatility third (walk-forward) | 593 | 1,577 | 63.4% | Rs 83,269 (-Rs 64,417) | -Rs 1,696 | Rs 86,586 (+Rs 649) | -Rs 36,762 (+Rs 0) | 0.772 |
| W-und: skip the worst index (walk-forward) | 281 | 1,889 | 62.7% | Rs 149,438 (+Rs 1,752) | -Rs 1,633 | Rs 82,542 (-Rs 3,395) | -Rs 36,762 (+Rs 0) | 0.199 |
| W-trend: skip the worst trend-strength bucket (walk-forward) | 264 | 1,906 | 62.1% | Rs 86,548 (-Rs 61,138) | -Rs 1,598 | Rs 95,182 (+Rs 9,245) | -Rs 42,192 (-Rs 5,430) | 0.958 |
| S10: entry stop at -10% instead of -15% | 522 | 2,177 | 59.0% | Rs 107,800 (-Rs 39,886) | -Rs 1,399 | Rs 76,918 (-Rs 9,019) | -Rs 31,733 (+Rs 5,029) | 1.000 |
| DC3: no new entry after the arm is down Rs 3,000 closed that day | 319 | 1,851 | 61.8% | Rs 68,775 (-Rs 78,911) | -Rs 1,598 | Rs 109,343 (+Rs 23,406) | -Rs 39,701 (-Rs 2,939) | 0.977 |
| DC5: no new entry after the arm is down Rs 5,000 closed that day | 207 | 1,963 | 62.0% | Rs 88,455 (-Rs 59,231) | -Rs 1,580 | Rs 97,991 (+Rs 12,054) | -Rs 39,383 (-Rs 2,621) | 0.978 |
| L2: no new entry on an index after two losers in a row on it that day | 43 | 2,127 | 63.0% | Rs 163,789 (+Rs 16,103) | -Rs 1,583 | Rs 77,513 (-Rs 8,424) | -Rs 36,063 (+Rs 699) | 0.033 |

## R203 other loss-cutting rules: R51 Donchian 20 + Supertrend, last year 24 Nov 2025 - 5 Oct 2026 (descriptive)

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 1,165 | 63.1% | Rs 143,844 | -Rs 1,519 | Rs 54,101 | -Rs 29,871 | - |
| W-hour: skip the worst entry-time bucket (walk-forward) | 147 | 1,018 | 63.8% | Rs 140,769 (-Rs 3,075) | -Rs 1,570 | Rs 46,078 (-Rs 8,023) | -Rs 23,638 (+Rs 6,233) | n/a |
| W-vix: skip the worst VIX regime (walk-forward) | 107 | 1,058 | 63.0% | Rs 143,704 (-Rs 140) | -Rs 1,516 | Rs 54,101 (+Rs 0) | -Rs 29,871 (+Rs 0) | n/a |
| W-gap: skip the worst opening-gap bucket (walk-forward) | 163 | 1,002 | 62.6% | Rs 98,872 (-Rs 44,972) | -Rs 1,505 | Rs 47,181 (-Rs 6,920) | -Rs 29,871 (+Rs 0) | n/a |
| W-dow: skip the worst weekday (walk-forward) | 215 | 950 | 64.3% | Rs 171,580 (+Rs 27,736) | -Rs 1,457 | Rs 48,522 (-Rs 5,579) | -Rs 27,631 (+Rs 2,240) | n/a |
| W-rv: skip the worst realised-volatility third (walk-forward) | 232 | 933 | 62.5% | Rs 55,011 (-Rs 88,833) | -Rs 1,577 | Rs 54,101 (+Rs 0) | -Rs 29,871 (+Rs 0) | n/a |
| W-und: skip the worst index (walk-forward) | 212 | 953 | 63.7% | Rs 135,592 (-Rs 8,252) | -Rs 1,613 | Rs 36,381 (-Rs 17,720) | -Rs 14,841 (+Rs 15,030) | n/a |
| W-trend: skip the worst trend-strength bucket (walk-forward) | 19 | 1,146 | 62.6% | Rs 130,195 (-Rs 13,649) | -Rs 1,524 | Rs 54,101 (+Rs 0) | -Rs 29,871 (+Rs 0) | n/a |
| S10: entry stop at -10% instead of -15% | 253 | 1,170 | 59.2% | Rs 110,474 (-Rs 33,370) | -Rs 1,362 | Rs 67,775 (+Rs 13,674) | -Rs 31,068 (-Rs 1,197) | n/a |
| DC3: no new entry after the arm is down Rs 3,000 closed that day | 144 | 1,021 | 63.5% | Rs 126,974 (-Rs 16,870) | -Rs 1,507 | Rs 50,894 (-Rs 3,207) | -Rs 21,308 (+Rs 8,563) | n/a |
| DC5: no new entry after the arm is down Rs 5,000 closed that day | 90 | 1,075 | 63.4% | Rs 136,735 (-Rs 7,109) | -Rs 1,504 | Rs 48,078 (-Rs 6,023) | -Rs 20,891 (+Rs 8,980) | n/a |
| L2: no new entry on an index after two losers in a row on it that day | 24 | 1,141 | 63.2% | Rs 151,692 (+Rs 7,848) | -Rs 1,508 | Rs 54,730 (+Rs 629) | -Rs 30,499 (-Rs 628) | n/a |

## R203 other loss-cutting rules: R51 Donchian 20 + Supertrend, total 21 Nov 2024 - 5 Oct 2026

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 2,822 | 62.1% | Rs 191,366 | -Rs 1,543 | Rs 85,937 | -Rs 36,762 | - |
| W-hour: skip the worst entry-time bucket (walk-forward) | 261 | 2,561 | 62.4% | Rs 164,945 (-Rs 26,421) | -Rs 1,582 | Rs 85,937 (+Rs 0) | -Rs 36,762 (+Rs 0) | n/a |
| W-vix: skip the worst VIX regime (walk-forward) | 767 | 2,055 | 62.5% | Rs 156,737 (-Rs 34,629) | -Rs 1,597 | Rs 76,457 (-Rs 9,480) | -Rs 36,762 (+Rs 0) | n/a |
| W-gap: skip the worst opening-gap bucket (walk-forward) | 357 | 2,465 | 61.9% | Rs 123,526 (-Rs 67,840) | -Rs 1,529 | Rs 90,849 (+Rs 4,912) | -Rs 36,762 (+Rs 0) | n/a |
| W-dow: skip the worst weekday (walk-forward) | 390 | 2,432 | 62.5% | Rs 203,845 (+Rs 12,479) | -Rs 1,522 | Rs 79,767 (-Rs 6,170) | -Rs 38,977 (-Rs 2,215) | n/a |
| W-rv: skip the worst realised-volatility third (walk-forward) | 741 | 2,081 | 62.4% | Rs 71,271 (-Rs 120,095) | -Rs 1,659 | Rs 86,586 (+Rs 649) | -Rs 36,762 (+Rs 0) | n/a |
| W-und: skip the worst index (walk-forward) | 402 | 2,420 | 62.4% | Rs 200,794 (+Rs 9,428) | -Rs 1,606 | Rs 82,542 (-Rs 3,395) | -Rs 36,762 (+Rs 0) | n/a |
| W-trend: skip the worst trend-strength bucket (walk-forward) | 264 | 2,558 | 61.7% | Rs 130,228 (-Rs 61,138) | -Rs 1,553 | Rs 95,182 (+Rs 9,245) | -Rs 42,192 (-Rs 5,430) | n/a |
| S10: entry stop at -10% instead of -15% | 665 | 2,830 | 58.5% | Rs 131,449 (-Rs 59,917) | -Rs 1,377 | Rs 76,918 (-Rs 9,019) | -Rs 31,733 (+Rs 5,029) | n/a |
| DC3: no new entry after the arm is down Rs 3,000 closed that day | 400 | 2,422 | 61.6% | Rs 124,199 (-Rs 67,167) | -Rs 1,554 | Rs 109,343 (+Rs 23,406) | -Rs 39,701 (-Rs 2,939) | n/a |
| DC5: no new entry after the arm is down Rs 5,000 closed that day | 252 | 2,570 | 61.8% | Rs 143,968 (-Rs 47,398) | -Rs 1,542 | Rs 97,991 (+Rs 12,054) | -Rs 39,383 (-Rs 2,621) | n/a |
| L2: no new entry on an index after two losers in a row on it that day | 58 | 2,764 | 62.4% | Rs 209,698 (+Rs 18,332) | -Rs 1,546 | Rs 77,513 (-Rs 8,424) | -Rs 36,063 (+Rs 699) | n/a |

Why each one fails or passes (R51 Donchian 20 + Supertrend):

- **W-hour** (fail): design: net 111,949 not better than B's 147,686; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 0.1399 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made -Rs 9,315 (44 of them losers); BH q 1 (m = 4,868); better in 1 of 4 design quarters. Walk-forward skips: {'2025-04-01': '11', '2025-07-01': '12', '2025-10-01': '11', '2026-01-01': '13', '2026-04-01': '11', '2026-07-01': '11', '2026-10-01': '11'}.
- **W-vix** (fail): design: net 113,057 not better than B's 147,686; holdout: net 43,680 not better than B's 43,680; holdout: 0 trades changed / removed < 40; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 0 (0 of them losers); BH q 1 (m = 4,868); better in 1 of 4 design quarters. Walk-forward skips: {'2025-04-01': 'LO', '2025-07-01': 'LO', '2025-10-01': 'LO', '2026-01-01': None, '2026-04-01': None, '2026-07-01': None, '2026-10-01': None}.
- **W-gap** (fail): design: net 87,528 not better than B's 147,686; design: max drawdown 90,849 above B's 85,937; holdout: net 35,998 not better than B's 43,680; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 0.6562 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 7,683 (18 of them losers); BH q 1 (m = 4,868); better in 0 of 4 design quarters. Walk-forward skips: {'2025-04-01': 'under 0.25%', '2025-07-01': '0.75% or more', '2025-10-01': '0.75% or more', '2026-01-01': '0.75% or more', '2026-04-01': None, '2026-07-01': '0.75% or more', '2026-10-01': '0.75% or more'}.
- **W-dow** (fail): design: net 144,406 not better than B's 147,686; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 0.0765 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made -Rs 15,760 (57 of them losers); BH q 1 (m = 4,868); better in 2 of 4 design quarters. Walk-forward skips: {'2025-04-01': '3', '2025-07-01': '0', '2025-10-01': '3', '2026-01-01': '3', '2026-04-01': '3', '2026-07-01': '3', '2026-10-01': '3'}.
- **W-rv** (fail): design: net 83,269 not better than B's 147,686; design: max drawdown 86,586 above B's 85,937; holdout: net -11,997 not better than B's 43,680; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 0.9945 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 55,677 (52 of them losers); BH q 1 (m = 4,868); better in 1 of 4 design quarters. Walk-forward skips: {'2025-04-01': 'low', '2025-07-01': 'low', '2025-10-01': 'mid', '2026-01-01': 'mid', '2026-04-01': None, '2026-07-01': 'mid', '2026-10-01': None}.
- **W-und** (fail): holdout twin p 0.1664 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made -Rs 7,675 (53 of them losers); BH q 1 (m = 4,868); better in 3 of 4 design quarters. Walk-forward skips: {'2025-04-01': 'SENSEX', '2025-07-01': 'SENSEX', '2025-10-01': 'SENSEX', '2026-01-01': 'SENSEX', '2026-04-01': 'SENSEX', '2026-07-01': 'SENSEX', '2026-10-01': 'SENSEX'}.
- **W-trend** (fail): design: net 86,548 not better than B's 147,686; design: max drawdown 95,182 above B's 85,937; holdout: net 43,680 not better than B's 43,680; holdout: 0 trades changed / removed < 40; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 0 (0 of them losers); BH q 1 (m = 4,868); better in 1 of 4 design quarters. Walk-forward skips: {'2025-04-01': '0.3-0.6%', '2025-07-01': 'under 0', '2025-10-01': 'under 0', '2026-01-01': None, '2026-04-01': None, '2026-07-01': None, '2026-10-01': None}.
- **S10** (fail): design: net 107,800 not better than B's 147,686; holdout: net 23,649 not better than B's 43,680; holdout: max drawdown 48,263 above B's 45,825; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 29,041 on B's losers, gave up Rs 53,614 on B's winners; BH q 1 (m = 4,868); better in 0 of 4 design quarters.
- **DC3** (fail): design: net 68,775 not better than B's 147,686; design: max drawdown 109,343 above B's 85,937; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 0.1114 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made -Rs 11,744 (36 of them losers); BH q 1 (m = 4,868); better in 1 of 4 design quarters.
- **DC5** (fail): design: net 88,455 not better than B's 147,686; design: max drawdown 97,991 above B's 85,937; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 0.0765 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made -Rs 11,834 (21 of them losers); BH q 1 (m = 4,868); better in 0 of 4 design quarters.
- **L2** (fail): holdout: max drawdown 46,454 above B's 45,825; holdout: 15 trades changed / removed < 40; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 0.2749 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made -Rs 2,229 (7 of them losers); BH q 1 (m = 4,868); better in 2 of 4 design quarters.

## R203 other loss-cutting rules: Liquidity 15+5, holdout 1 Apr - 5 Oct 2026 (read once)

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p | verdict |
|---|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 330 | 43.9% | Rs 11,087 | -Rs 913 | Rs 59,441 | -Rs 32,670 | - | - |
| W-hour: skip the worst entry-time bucket (walk-forward) | 45 | 285 | 44.2% | Rs 10,785 (-Rs 302) | -Rs 948 | Rs 51,937 (-Rs 7,504) | -Rs 31,343 (+Rs 1,327) | 0.487 | fail |
| W-vix: skip the worst VIX regime (walk-forward) | 0 | 330 | 43.9% | Rs 11,087 (+Rs 0) | -Rs 913 | Rs 59,441 (+Rs 0) | -Rs 32,670 (+Rs 0) | 1.000 | fail |
| W-gap: skip the worst opening-gap bucket (walk-forward) | 0 | 330 | 43.9% | Rs 11,087 (+Rs 0) | -Rs 913 | Rs 59,441 (+Rs 0) | -Rs 32,670 (+Rs 0) | 1.000 | fail |
| W-dow: skip the worst weekday (walk-forward) | 64 | 266 | 45.5% | Rs 16,824 (+Rs 5,737) | -Rs 913 | Rs 44,087 (-Rs 15,354) | -Rs 21,581 (+Rs 11,089) | 0.318 | fail |
| W-rv: skip the worst realised-volatility third (walk-forward) | 0 | 330 | 43.9% | Rs 11,087 (+Rs 0) | -Rs 913 | Rs 59,441 (+Rs 0) | -Rs 32,670 (+Rs 0) | 1.000 | fail |
| W-und: skip the worst index (walk-forward) | 0 | 330 | 43.9% | Rs 11,087 (+Rs 0) | -Rs 913 | Rs 59,441 (+Rs 0) | -Rs 32,670 (+Rs 0) | 1.000 | fail |
| W-trend: skip the worst trend-strength bucket (walk-forward) | 51 | 279 | 44.1% | Rs 2,428 (-Rs 8,659) | -Rs 951 | Rs 59,441 (+Rs 0) | -Rs 32,670 (+Rs 0) | 0.707 | fail |
| S10: entry stop at -10% instead of -15% | 20 | 330 | 43.3% | Rs 15,171 (+Rs 4,084) | -Rs 881 | Rs 55,875 (-Rs 3,566) | -Rs 29,779 (+Rs 2,891) | 1.000 | fail |
| DC3: no new entry after the arm is down Rs 3,000 closed that day | 15 | 315 | 43.2% | Rs 7,925 (-Rs 3,162) | -Rs 921 | Rs 56,494 (-Rs 2,947) | -Rs 30,481 (+Rs 2,189) | 0.726 | fail |
| DC5: no new entry after the arm is down Rs 5,000 closed that day | 6 | 324 | 43.8% | Rs 13,276 (+Rs 2,189) | -Rs 914 | Rs 57,252 (-Rs 2,189) | -Rs 30,481 (+Rs 2,189) | 0.345 | fail |
| L2: no new entry on an index after two losers in a row on it that day | 12 | 318 | 43.4% | Rs 995 (-Rs 10,092) | -Rs 919 | Rs 56,595 (-Rs 2,846) | -Rs 31,370 (+Rs 1,300) | 0.917 | fail |

## R203 other loss-cutting rules: Liquidity 15+5, design 21 Nov 2024 - 31 Mar 2026

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 878 | 50.9% | Rs 231,580 | -Rs 909 | Rs 25,771 | -Rs 9,714 | - |
| W-hour: skip the worst entry-time bucket (walk-forward) | 47 | 831 | 51.7% | Rs 243,268 (+Rs 11,688) | -Rs 929 | Rs 25,771 (+Rs 0) | -Rs 9,714 (+Rs 0) | 0.037 |
| W-vix: skip the worst VIX regime (walk-forward) | 0 | 878 | 50.9% | Rs 231,580 (+Rs 0) | -Rs 909 | Rs 25,771 (+Rs 0) | -Rs 9,714 (+Rs 0) | 1.000 |
| W-gap: skip the worst opening-gap bucket (walk-forward) | 0 | 878 | 50.9% | Rs 231,580 (+Rs 0) | -Rs 909 | Rs 25,771 (+Rs 0) | -Rs 9,714 (+Rs 0) | 1.000 |
| W-dow: skip the worst weekday (walk-forward) | 66 | 812 | 50.7% | Rs 224,842 (-Rs 6,738) | -Rs 906 | Rs 25,771 (+Rs 0) | -Rs 6,056 (+Rs 3,658) | 0.288 |
| W-rv: skip the worst realised-volatility third (walk-forward) | 0 | 878 | 50.9% | Rs 231,580 (+Rs 0) | -Rs 909 | Rs 25,771 (+Rs 0) | -Rs 9,714 (+Rs 0) | 1.000 |
| W-und: skip the worst index (walk-forward) | 227 | 651 | 49.9% | Rs 172,139 (-Rs 59,441) | -Rs 863 | Rs 25,771 (+Rs 0) | -Rs 6,056 (+Rs 3,658) | 0.503 |
| W-trend: skip the worst trend-strength bucket (walk-forward) | 38 | 840 | 51.0% | Rs 228,869 (-Rs 2,711) | -Rs 904 | Rs 25,771 (+Rs 0) | -Rs 6,056 (+Rs 3,658) | 0.318 |
| S10: entry stop at -10% instead of -15% | 46 | 878 | 49.7% | Rs 167,883 (-Rs 63,697) | -Rs 917 | Rs 26,443 (+Rs 672) | -Rs 10,727 (-Rs 1,013) | 1.000 |
| DC3: no new entry after the arm is down Rs 3,000 closed that day | 28 | 850 | 51.5% | Rs 238,552 (+Rs 6,972) | -Rs 910 | Rs 24,434 (-Rs 1,337) | -Rs 5,815 (+Rs 3,899) | 0.076 |
| DC5: no new entry after the arm is down Rs 5,000 closed that day | 4 | 874 | 51.0% | Rs 233,547 (+Rs 1,967) | -Rs 909 | Rs 26,637 (+Rs 866) | -Rs 8,621 (+Rs 1,093) | 0.203 |
| L2: no new entry on an index after two losers in a row on it that day | 15 | 863 | 50.8% | Rs 230,088 (-Rs 1,492) | -Rs 906 | Rs 27,841 (+Rs 2,070) | -Rs 8,133 (+Rs 1,581) | 0.432 |

## R203 other loss-cutting rules: Liquidity 15+5, last year 24 Nov 2025 - 5 Oct 2026 (descriptive)

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 590 | 44.4% | Rs 106,212 | -Rs 928 | Rs 60,281 | -Rs 32,670 | - |
| W-hour: skip the worst entry-time bucket (walk-forward) | 77 | 513 | 44.6% | Rs 111,314 (+Rs 5,102) | -Rs 962 | Rs 52,144 (-Rs 8,137) | -Rs 31,343 (+Rs 1,327) | n/a |
| W-vix: skip the worst VIX regime (walk-forward) | 0 | 590 | 44.4% | Rs 106,212 (+Rs 0) | -Rs 928 | Rs 60,281 (+Rs 0) | -Rs 32,670 (+Rs 0) | n/a |
| W-gap: skip the worst opening-gap bucket (walk-forward) | 0 | 590 | 44.4% | Rs 106,212 (+Rs 0) | -Rs 928 | Rs 60,281 (+Rs 0) | -Rs 32,670 (+Rs 0) | n/a |
| W-dow: skip the worst weekday (walk-forward) | 87 | 503 | 44.7% | Rs 102,789 (-Rs 3,423) | -Rs 937 | Rs 44,879 (-Rs 15,402) | -Rs 21,581 (+Rs 11,089) | n/a |
| W-rv: skip the worst realised-volatility third (walk-forward) | 0 | 590 | 44.4% | Rs 106,212 (+Rs 0) | -Rs 928 | Rs 60,281 (+Rs 0) | -Rs 32,670 (+Rs 0) | n/a |
| W-und: skip the worst index (walk-forward) | 91 | 499 | 43.3% | Rs 59,709 (-Rs 46,503) | -Rs 880 | Rs 60,190 (-Rs 91) | -Rs 32,670 (+Rs 0) | n/a |
| W-trend: skip the worst trend-strength bucket (walk-forward) | 51 | 539 | 44.5% | Rs 97,553 (-Rs 8,659) | -Rs 950 | Rs 60,281 (+Rs 0) | -Rs 32,670 (+Rs 0) | n/a |
| S10: entry stop at -10% instead of -15% | 32 | 590 | 43.6% | Rs 86,155 (-Rs 20,057) | -Rs 915 | Rs 63,279 (+Rs 2,998) | -Rs 29,779 (+Rs 2,891) | n/a |
| DC3: no new entry after the arm is down Rs 3,000 closed that day | 22 | 568 | 44.4% | Rs 107,333 (+Rs 1,121) | -Rs 934 | Rs 57,334 (-Rs 2,947) | -Rs 30,481 (+Rs 2,189) | n/a |
| DC5: no new entry after the arm is down Rs 5,000 closed that day | 7 | 583 | 44.4% | Rs 109,381 (+Rs 3,169) | -Rs 929 | Rs 58,092 (-Rs 2,189) | -Rs 30,481 (+Rs 2,189) | n/a |
| L2: no new entry on an index after two losers in a row on it that day | 19 | 571 | 44.0% | Rs 96,045 (-Rs 10,167) | -Rs 931 | Rs 57,435 (-Rs 2,846) | -Rs 31,370 (+Rs 1,300) | n/a |

## R203 other loss-cutting rules: Liquidity 15+5, total 21 Nov 2024 - 5 Oct 2026

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 1,208 | 49.0% | Rs 242,666 | -Rs 910 | Rs 60,281 | -Rs 32,670 | - |
| W-hour: skip the worst entry-time bucket (walk-forward) | 92 | 1,116 | 49.8% | Rs 254,053 (+Rs 11,387) | -Rs 934 | Rs 52,144 (-Rs 8,137) | -Rs 31,343 (+Rs 1,327) | n/a |
| W-vix: skip the worst VIX regime (walk-forward) | 0 | 1,208 | 49.0% | Rs 242,666 (+Rs 0) | -Rs 910 | Rs 60,281 (+Rs 0) | -Rs 32,670 (+Rs 0) | n/a |
| W-gap: skip the worst opening-gap bucket (walk-forward) | 0 | 1,208 | 49.0% | Rs 242,666 (+Rs 0) | -Rs 910 | Rs 60,281 (+Rs 0) | -Rs 32,670 (+Rs 0) | n/a |
| W-dow: skip the worst weekday (walk-forward) | 130 | 1,078 | 49.4% | Rs 241,666 (-Rs 1,000) | -Rs 908 | Rs 44,879 (-Rs 15,402) | -Rs 21,581 (+Rs 11,089) | n/a |
| W-rv: skip the worst realised-volatility third (walk-forward) | 0 | 1,208 | 49.0% | Rs 242,666 (+Rs 0) | -Rs 910 | Rs 60,281 (+Rs 0) | -Rs 32,670 (+Rs 0) | n/a |
| W-und: skip the worst index (walk-forward) | 227 | 981 | 47.9% | Rs 183,225 (-Rs 59,441) | -Rs 881 | Rs 60,190 (-Rs 91) | -Rs 32,670 (+Rs 0) | n/a |
| W-trend: skip the worst trend-strength bucket (walk-forward) | 89 | 1,119 | 49.2% | Rs 231,297 (-Rs 11,369) | -Rs 917 | Rs 60,281 (+Rs 0) | -Rs 32,670 (+Rs 0) | n/a |
| S10: entry stop at -10% instead of -15% | 66 | 1,208 | 47.9% | Rs 183,053 (-Rs 59,613) | -Rs 907 | Rs 63,279 (+Rs 2,998) | -Rs 29,779 (+Rs 2,891) | n/a |
| DC3: no new entry after the arm is down Rs 3,000 closed that day | 43 | 1,165 | 49.3% | Rs 246,477 (+Rs 3,811) | -Rs 913 | Rs 57,334 (-Rs 2,947) | -Rs 30,481 (+Rs 2,189) | n/a |
| DC5: no new entry after the arm is down Rs 5,000 closed that day | 10 | 1,198 | 49.1% | Rs 246,824 (+Rs 4,158) | -Rs 910 | Rs 58,092 (-Rs 2,189) | -Rs 30,481 (+Rs 2,189) | n/a |
| L2: no new entry on an index after two losers in a row on it that day | 27 | 1,181 | 48.8% | Rs 231,084 (-Rs 11,582) | -Rs 910 | Rs 57,435 (-Rs 2,846) | -Rs 31,370 (+Rs 1,300) | n/a |

Why each one fails or passes (Liquidity 15+5):

- **W-hour** (fail): holdout: net 10,785 not better than B's 11,087; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 0.4873 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 302 (26 of them losers); BH q 1 (m = 4,868); better in 2 of 4 design quarters. Walk-forward skips: {'2025-04-01': None, '2025-07-01': None, '2025-10-01': '13', '2026-01-01': '13', '2026-04-01': '11', '2026-07-01': '11', '2026-10-01': '11'}.
- **W-vix** (fail): design: net 231,580 not better than B's 231,580; design: 0 trades changed / removed < 40; holdout: net 11,087 not better than B's 11,087; holdout: 0 trades changed / removed < 40; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 0 (0 of them losers); BH q 1 (m = 4,868); better in 0 of 4 design quarters. Walk-forward skips: {'2025-04-01': None, '2025-07-01': None, '2025-10-01': None, '2026-01-01': None, '2026-04-01': None, '2026-07-01': None, '2026-10-01': None}.
- **W-gap** (fail): design: net 231,580 not better than B's 231,580; design: 0 trades changed / removed < 40; holdout: net 11,087 not better than B's 11,087; holdout: 0 trades changed / removed < 40; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 0 (0 of them losers); BH q 1 (m = 4,868); better in 0 of 4 design quarters. Walk-forward skips: {'2025-04-01': None, '2025-07-01': None, '2025-10-01': None, '2026-01-01': None, '2026-04-01': None, '2026-07-01': None, '2026-10-01': None}.
- **W-dow** (fail): design: net 224,842 not better than B's 231,580; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 0.3183 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made -Rs 5,737 (40 of them losers); BH q 1 (m = 4,868); better in 1 of 4 design quarters. Walk-forward skips: {'2025-04-01': None, '2025-07-01': '2', '2025-10-01': '2', '2026-01-01': None, '2026-04-01': '2', '2026-07-01': '2', '2026-10-01': '2'}.
- **W-rv** (fail): design: net 231,580 not better than B's 231,580; design: 0 trades changed / removed < 40; holdout: net 11,087 not better than B's 11,087; holdout: 0 trades changed / removed < 40; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 0 (0 of them losers); BH q 1 (m = 4,868); better in 0 of 4 design quarters. Walk-forward skips: {'2025-04-01': None, '2025-07-01': None, '2025-10-01': None, '2026-01-01': None, '2026-04-01': None, '2026-07-01': None, '2026-10-01': None}.
- **W-und** (fail): design: net 172,139 not better than B's 231,580; holdout: net 11,087 not better than B's 11,087; holdout: 0 trades changed / removed < 40; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 0 (0 of them losers); BH q 1 (m = 4,868); better in 1 of 4 design quarters. Walk-forward skips: {'2025-04-01': 'MIDCPNIFTY', '2025-07-01': 'MIDCPNIFTY', '2025-10-01': 'MIDCPNIFTY', '2026-01-01': 'MIDCPNIFTY', '2026-04-01': None, '2026-07-01': None, '2026-10-01': None}.
- **W-trend** (fail): design: net 228,869 not better than B's 231,580; design: 38 trades changed / removed < 40; holdout: net 2,428 not better than B's 11,087; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 0.7067 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 8,659 (29 of them losers); BH q 1 (m = 4,868); better in 0 of 4 design quarters. Walk-forward skips: {'2025-04-01': None, '2025-07-01': '0.3-0.6%', '2025-10-01': None, '2026-01-01': None, '2026-04-01': None, '2026-07-01': '0.3-0.6%', '2026-10-01': None}.
- **S10** (fail): design: net 167,883 not better than B's 231,580; design: max drawdown 26,443 above B's 25,771; holdout: 20 trades changed / removed < 40; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 1.0000 >= 0.05; BH q 1 > 0.1. Holdout: saved Rs 5,254 on B's losers, gave up Rs 2,566 on B's winners; BH q 1 (m = 4,868); better in 1 of 4 design quarters.
- **DC3** (fail): design: 28 trades changed / removed < 40; holdout: net 7,925 not better than B's 11,087; holdout: 15 trades changed / removed < 40; holdout twin p 0.7256 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 3,162 (6 of them losers); BH q 1 (m = 4,868); better in 3 of 4 design quarters.
- **DC5** (fail): design: max drawdown 26,637 above B's 25,771; design: 4 trades changed / removed < 40; holdout: 6 trades changed / removed < 40; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 0.3448 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made -Rs 2,189 (3 of them losers); BH q 1 (m = 4,868); better in 2 of 4 design quarters.
- **L2** (fail): design: net 230,088 not better than B's 231,580; design: max drawdown 27,841 above B's 25,771; design: 15 trades changed / removed < 40; holdout: net 995 not better than B's 11,087; holdout: 12 trades changed / removed < 40; holdout twin p 0.9165 >= 0.05; BH q 1 > 0.1. Holdout: the removed trades had made Rs 10,091 (5 of them losers); BH q 1 (m = 4,868); better in 3 of 4 design quarters.
