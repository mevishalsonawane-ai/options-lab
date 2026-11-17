# R187 for Boss: a multi-layer neural network over the whole lake, on the ORB family, Liquidity, R51 and R72 - 0 of 20 pass

16 Nov 2026. Research only: **nothing in the app changes.** Boss asked: *"1st create a neuro network of multiple layers with
all the data we have in data lake for all the index, then ... check all the combinations with it and find if it will be
enhance and make the orb family and other stratergies more profitable with more winrate And less loss"*.

## The answer in short

- **No.** I built the network and used it four ways on six arms (20 tests). **None passed.** At the moment of entry the
  network could not tell a winning trade from a losing one better than a coin toss.
- **One lead: Liquidity 15+5 with the network's skip (U1).** On the last year it made more (Rs 1,22,326 vs Rs 98,239),
  won more often (46.8% vs 44.5%; 564 vs 688 trades) and lost less in its worst month (-Rs 24,346 vs -Rs 32,670). But
  skipping the same number of trades at random beat it 1 time in 42 (p 0.024): against the 4,902 tests now on the books
  that is chance (BH q 1.0). In the walk-forward year before (Apr 2025 - Mar 2026) the same skip made LESS (Rs 1,80,326 on
  559 trades vs Rs 1,94,985 on 657). A note, not a change.
- **R51: the network "helped" only by leaving the Rs 500 step lock.** Choosing exits (U2) made Rs 2,17,180 vs Rs 1,62,959
  in the year (1,410 vs 1,430 trades) with a smaller drawdown, but it won LESS often (60.4% vs 62.7%), and a random other
  exit did almost as well (twin average Rs 2,07,879; p 0.30). This is R186's finding again: on R51 the L500 lock (V5)
  costs money against V4 or the arm's own exits. The network adds nothing on top.
- **The ORB family stays parked.** The network's skip roughly halved ORB's loss in the year (-Rs 11,967 on 88 trades vs
  -Rs 22,716 on 237), but it still lost, lost in the final too (-Rs 669 on 39 trades vs +Rs 2,876 on 128) and random skips
  did as well (p 0.72). ORB Sweep lost with every use (year -Rs 76,056 to -Rs 83,361 vs -Rs 81,308).
- **R151's picker stays.** A network picker in its place (U4) made far less on R72 (Rs 1,70,488 vs Rs 2,13,110) and on R51
  (Rs 99,475 vs Rs 1,62,959), with bigger drawdowns.

## What was built

- **Data (the whole lake that has these arms' trades):** 1-minute bars of all 7 indices (NIFTY from 2017, the others from
  2021-2022, India VIX) and the rolling index options ATM-10..+10 (IV, OI, volume; from Aug 2021), to 13 Nov 2026.
- **The network:** a 3-hidden-layer perceptron (64-32-16 chosen in every fold; 128-64-32 also tried). Two of them: one
  gives the chance a trade nets a profit after charges, the other the expected rupees. One shared network for all six
  arms (the arm, the index and the exit rule are inputs). About 105 inputs, all known at the decision minute: the traded
  index (returns, range, gap, trend strength ADX / efficiency / choppiness from R183), the other five indices and VIX, the
  option chain as context only (ATM IV, skew, PCR, OI changes, FT3's "price and OI both fell"), R151's 28 picker features
  and its score, and R169's 11 ORB features.
- **Walk-forward:** refitted every quarter from Apr 2025, each time only on trades before that quarter (4,337 to 11,018
  training trades; 32,673 to 85,564 rows), then frozen on 31 Mar 2026 for the final and the new month. Settings and
  cut-offs were chosen only on the quarter before each fold.
- **How good is it?** On those validation quarters its "chance of profit" scored a log-loss of 0.69 - 0.81. A coin that
  always says the average scores about 0.69. So the network has no real skill at the entry minute.

## The uses ("all the combinations", fixed in advance)

- **U1 skip:** skip a trade the network gives a low chance of profit (the cut-off is chosen on the quarter before).
- **U2 exit choice:** for each trade, pick the exit the network expects to net most, from the exits already studied
  (V5, V4, own exits, the other lock, P60, the index lock, +Rs 1,000 / +Rs 1,500 targets; R169's X1-X6 on ORB).
- **U3:** U2 then U1.
- **U4 (R72, R51):** the network as the picker instead of R151's.

## Results, 1 lot: the last year (20 Oct 2025 - 19 Oct 2026) and the final (1 Apr - 13 Oct 2026)

| arm | rule | year trades | win | net | avg win | avg loss | worst month | max drawdown | final trades | final win | final net |
|---|---|---|---|---|---|---|---|---|---|---|---|
| R72 | base (V5, app today) | 1,097 | 64.4% | Rs 2,13,110 | Rs 1,166 | -Rs 1,560 | -Rs 13,690 | Rs 42,286 | 624 | 60.4% | Rs 1,11,117 |
| R72 | U1 skip | 1,097 | 64.4% | Rs 2,13,110 | Rs 1,166 | -Rs 1,560 | -Rs 13,690 | Rs 42,286 | 624 | 60.4% | Rs 1,11,117 |
| R72 | U2 exits | 1,094 | 63.2% | Rs 2,16,794 | Rs 1,215 | -Rs 1,546 | -Rs 16,594 | Rs 42,286 | 623 | 59.9% | Rs 1,09,696 |
| R72 | U3 | 1,094 | 63.2% | Rs 2,16,794 | Rs 1,215 | -Rs 1,546 | -Rs 16,594 | Rs 42,286 | 623 | 59.9% | Rs 1,09,696 |
| R72 | U4 net picker | 1,351 | 62.0% | Rs 1,70,488 | Rs 1,069 | -Rs 1,414 | -Rs 45,320 | Rs 62,904 | 531 | 61.8% | Rs 1,07,549 |
| R51 | base (V5) | 1,430 | 62.7% | Rs 1,62,959 | Rs 1,067 | -Rs 1,489 | -Rs 29,871 | Rs 54,101 | 684 | 60.7% | Rs 57,450 |
| R51 | U1 skip | 1,283 | 62.4% | Rs 1,38,009 | Rs 1,079 | -Rs 1,507 | -Rs 29,871 | Rs 54,101 | 684 | 60.7% | Rs 57,450 |
| R51 | U2 exits | 1,410 | 60.4% | Rs 2,17,180 | Rs 1,201 | -Rs 1,445 | -Rs 18,547 | Rs 33,497 | 671 | 58.3% | Rs 1,12,679 |
| R51 | U3 | 1,323 | 60.8% | Rs 2,17,596 | Rs 1,216 | -Rs 1,464 | -Rs 18,547 | Rs 33,497 | 671 | 58.3% | Rs 1,12,679 |
| R51 | U4 net picker | 1,757 | 61.0% | Rs 99,475 | Rs 1,029 | -Rs 1,465 | -Rs 33,687 | Rs 56,679 | 677 | 59.8% | Rs 62,390 |
| Liquidity | base (BE + LBE1000) | 688 | 44.5% | Rs 98,239 | Rs 1,471 | -Rs 921 | -Rs 32,670 | Rs 60,281 | 357 | 43.7% | Rs 6,424 |
| Liquidity | U1 skip | 564 | 46.8% | Rs 1,22,326 | Rs 1,570 | -Rs 974 | -Rs 24,346 | Rs 48,322 | 305 | 45.6% | Rs 18,590 |
| Liquidity | U2 exits | 688 | 43.9% | Rs 85,996 | Rs 1,473 | -Rs 930 | -Rs 32,670 | Rs 60,281 | 357 | 43.7% | Rs 6,424 |
| Liquidity | U3 | 611 | 45.5% | Rs 1,11,356 | Rs 1,537 | -Rs 948 | -Rs 24,346 | Rs 48,322 | 305 | 45.6% | Rs 18,590 |
| ORB (= ORB Fresh) | base (R169 B0) | 237 | 39.2% | -Rs 22,716 | Rs 669 | -Rs 590 | -Rs 6,704 | Rs 30,782 | 128 | 43.8% | Rs 2,876 |
| ORB (= ORB Fresh) | U1 skip | 88 | 40.9% | -Rs 11,967 | Rs 688 | -Rs 706 | -Rs 3,422 | Rs 18,532 | 39 | 43.6% | -Rs 669 |
| ORB (= ORB Fresh) | U2 exits | 237 | 39.7% | -Rs 22,263 | Rs 665 | -Rs 593 | -Rs 6,493 | Rs 30,178 | 128 | 44.5% | Rs 2,725 |
| ORB (= ORB Fresh) | U3 | 102 | 42.2% | -Rs 12,707 | Rs 675 | -Rs 707 | -Rs 3,422 | Rs 19,294 | 54 | 46.3% | -Rs 1,032 |
| ORB Sweep | base | 326 | 28.5% | -Rs 81,308 | Rs 1,196 | -Rs 826 | -Rs 15,900 | Rs 86,350 | 176 | 28.4% | -Rs 54,421 |
| ORB Sweep | U1 skip | 276 | 29.0% | -Rs 76,056 | Rs 1,178 | -Rs 869 | -Rs 15,900 | Rs 76,553 | 176 | 28.4% | -Rs 54,421 |
| ORB Sweep | U2 exits | 332 | 28.6% | -Rs 83,361 | Rs 1,076 | -Rs 783 | -Rs 15,900 | Rs 86,009 | 177 | 28.2% | -Rs 53,271 |
| ORB Sweep | U3 | 280 | 28.2% | -Rs 78,728 | Rs 1,101 | -Rs 824 | -Rs 15,900 | Rs 78,728 | 177 | 28.2% | -Rs 53,271 |

R72's U1 skipped nothing in the year: on each quarter before, no cut-off beat taking every trade.

## The new month, 21 Oct - 13 Nov 2026 (never used to choose anything; small; descriptive only)

| arm | base | U1 | U2 | U3 | U4 |
|---|---|---|---|---|---|
| R72 | 126 trades, 61.9%, Rs 9,481 | same as base | 126, 61.9%, Rs 7,493 | same as U2 | 112, 63.4%, Rs 10,937 |
| R51 | 142, 69.0%, Rs 25,448 | same as base | 131, 64.9%, Rs 28,793 | same as U2 | 136, 66.2%, Rs 16,040 |
| Liquidity | 43, 62.8%, Rs 4,575 | 40, 62.5%, Rs 4,658 | same as base | 40, 62.5%, Rs 4,658 | - |
| ORB (= Fresh) | 18, 50.0%, Rs 2,430 | 6, 50.0%, Rs 5,397 | same as base | 12, 41.7%, Rs 1,960 | - |
| ORB Sweep | 25, 24.0%, -Rs 9,033 | same | same | same | - |

## Gates and honesty

- **The gates** (each at 1 lot): net at least the base's, a higher win rate, and a smaller drawdown or a better worst
  month, in the year AND the final; profit in every period; beat 500 random twins (seed 187) after Benjamini-Hochberg over
  **m = 4,902** tests (q <= 0.10); at least 40 final trades. **0 of 20 passed.**
- **The closest:** Liquidity U1 passed every gate except the twins' BH q (p 0.024, q 1.0; 0.24 counting R187's 20 tests
  alone). Liquidity U3 was next (p 0.042).
- **The final has been read many times** (R151 - R186), and today's V5 was chosen on it. R187 read it once more
  (`results/FINAL_READ`). Even a pass would have been a second look, good only for a shadow log.
- **One bug was fixed before any result was seen,** and it is disclosed (PREREG Amendment A): sklearn's early stopping
  split a trade's stacked rows at random, so the first networks memorised.
- **The account stop rule** (Rs 40k drawdown / Rs 13k month) is already broken by the bases in the year: R72's drawdown
  Rs 42,286, R51's worst month -Rs 29,871, Liquidity's -Rs 32,670, ORB Sweep's -Rs 15,900. No use fixes all of it.
- **Not simulated, and why:**
  - Range Fade: only R3's engine exists, with no dated-charge base and no exit menu; it loses every year.
  - S5 Trail and CISD S3-V2-E3: driven by R123's network predictions, which are not local and do not cover the new month.
  - FT3, Night, VIX divergence, Hero: their own engines, sessions and exits; outside this study's disk and time.
- **Signal source everywhere:** the index. Option-chain data was used only as network inputs.

Full tables: `results/report.md`. Pre-registration: `PREREG.md`.
