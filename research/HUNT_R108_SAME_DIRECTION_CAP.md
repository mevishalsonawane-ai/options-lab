# R108: the same-direction cap, checked as the app runs it, and 7 alternatives tested

26 Oct 2026. R107 left a lead: "the same-direction cap of 2 cut the four arms from Rs 7.75 lakh to -Rs 4.03 lakh in
design". R108 checks that lead by modelling the cap exactly as the app runs it. It then tests 7 pre-registered
alternatives that keep a risk limit.

- **Pre-registered:** `research/hunt/r108_same_direction_cap/PREREG.md`, commit dc9861f, pushed before any number.
- **Order of steps:** the code and the design read were committed in 4209de3 before the one final read (`results/FINAL_READ`).
- **Code, tests and every table:** `research/hunt/r108_same_direction_cap/` (`results/report.md`, 30 tests).
- **Entries:** `research/experiments/entries/r108-*.yaml` (7 entries, all rejected).
- **Signal source of every rule: the index.** The arms keep their own index signals, strikes and exits. R108 changes only
  which entries the account takes.

## The answer first

1. **The lead was real but overstated.** As the app really runs it, the cap costs:
   - about Rs 3.6 lakh in design (Rs 2,30,649 → -Rs 1,28,786);
   - Rs 4.1 lakh in the walk-forward window (Rs 4,37,733 → Rs 25,383);
   - Rs 1.2 lakh in the final (Rs 1,38,560 → Rs 15,955).

   R107's -Rs 4.03 lakh came mostly from one modelling choice. In the app, a refused Donchian or R52 entry becomes a
   would-be trade and holds the arm's own place. In R107's model it freed the arm to take a later, weaker signal.
2. **"Rs 7.75 lakh without the cap" was never possible.** The app's "one option, one arm" rule (19 Oct) already turns
   R51's and R52's copies of R72's option into would-be trades. With no cap at all, the paper book makes:
   - Rs 2.31 lakh in design (6,520 trades);
   - Rs 1.39 lakh in the final (2,538 trades).
3. **The cap refuses about half the entries, and it picks them no better or worse than chance.** Refusing the same number
   of trades at random gives the same result (random-refusal twin p 0.41 in design, 0.45 in the final). What the cap
   does buy is fewer very bad days:
   - days at or below -Rs 13,000 fall from 52 to 13 in design, and from 12 to 2 in the final;
   - the worst day goes from -Rs 86,966 to -Rs 27,269 in design;
   - separate Rs 40,000 drawdowns fall from 6 to 1 in design, and from 3 to 1 in the final.
4. **None of the 7 alternatives passed.** Every one makes more net than the cap in design and the walk-forward window, and
   all but two in the final. But every one also brings more bad days or bad months than the cap in design, which is
   Boss's account rule. None is close on the multiple-testing check: BH q = 1.0 for all, over 5,784 tests. The closest
   was cap 3:
   - net -Rs 27,019 / Rs 1,41,561 / Rs 87,231 in design / walk-forward / final;
   - final twin p 0.043;
   - but 27 days at or below -Rs 13,000 in design against the cap's 13, and 3 Rs 40,000 drawdowns against 1.
5. **Boss's account rule is broken at 1 lot, cap or no cap.** No set of these four arms stays inside a Rs 40,000
   drawdown with no month at or below -Rs 13,000 in both design and the final. That covers all 15 sets, with or without
   the cap. 1 lot is already the smallest size, so no lot size fixes it.
   - The closest is Liquidity alone with the cap: Rs 30,827 drawdown and no bad month in design, but Rs 35,911 and 1 bad
     month in the final.
   - Today's four arms with the cap: Rs 2,09,037 drawdown in design, Rs 74,624 in the final (2 of 7 months at or below
     -Rs 13,000).

**What can go on paper: nothing from R108.** The cap stays at 2, Boss's rule, unchanged. Nothing goes live. If Boss
still wants more trades, cap 3 is the only number worth discussing. In the final it made Rs 87,231 against Rs 15,955, with
a smaller drawdown (Rs 51,894 against Rs 74,624). But it did not pass, and in design it roughly doubled the very bad days.
That is Boss's choice, not a research result.

## With and without the cap (1 lot, after charges; n = trades)

"Months" = months at or below -Rs 13,000. "DD breaches" = separate times the drawdown reached Rs 40,000. "Bad days" = days
at or below -Rs 13,000.

| period | rule | net | trades | win | worst day | max drawdown | months | DD breaches | bad days | refused |
|---|---|---|---|---|---|---|---|---|---|---|
| design (337 sessions) | four arms alone (R107) | Rs 7,74,750 | 9,156 | 46% | -Rs 1,21,774 | Rs 4,44,695 | 6 of 17 | 7 | 79 | - |
| | book, no cap | Rs 2,30,649 | 6,520 | 45% | -Rs 86,966 | Rs 2,74,308 | 5 of 17 | 6 | 52 | 0 |
| | **book, the app's cap** | **-Rs 1,28,786** | 3,456 | 43% | -Rs 27,269 | Rs 2,09,037 | 6 of 17 | 1 | 13 | 4,647 |
| walk-forward (Apr 2025 - Mar 2026) | four arms alone | Rs 9,96,792 | 6,690 | 48% | -Rs 63,037 | Rs 1,77,785 | 3 of 12 | 7 | 51 | - |
| | book, no cap | Rs 4,37,733 | 4,772 | 46% | -Rs 27,731 | Rs 1,30,217 | 2 of 12 | 5 | 32 | 0 |
| | **book, the app's cap** | **Rs 25,383** | 2,536 | 44% | -Rs 17,200 | Rs 1,24,652 | 3 of 12 | 1 | 8 | 3,383 |
| final (1 Apr - 13 Oct 2026, read once) | four arms alone | Rs 3,12,978 | 3,633 | 45% | -Rs 69,940 | Rs 1,70,942 | 2 of 7 | 4 | 30 | - |
| | book, no cap | Rs 1,38,560 | 2,538 | 43% | -Rs 39,963 | Rs 88,497 | 2 of 7 | 3 | 12 | 0 |
| | **book, the app's cap** | **Rs 15,955** | 1,384 | 42% | -Rs 14,149 | Rs 74,624 | 2 of 7 | 1 | 2 | 1,799 |

Who gets refused under the cap (design; n, net of the would-be trades):

| arm | kept on the account | refused by the cap | would-be, "one option, one arm" |
|---|---|---|---|
| Liquidity 15+5 | 364, Rs 88,184 | 521, Rs 1,99,497 | - |
| R72 Breadth Donchian | 902, Rs 96,176 | 1,971, Rs 1,88,378 | 198, -Rs 67,624 |
| R51 Donchian 20 | 2,092, **-Rs 3,29,467** | 1,778, Rs 2,48,905 | 628, Rs 90,560 |
| R52 Open outside value | 98, Rs 16,321 | 377, Rs 2,04,755 | 231, Rs 69,497 |

In the final the refusals were, in the same order: Liquidity 205 (-Rs 5,628); R72 758 (Rs 1,29,471); R51 676
(Rs 87,873); R52 160 (Rs 32,115).

- No Liquidity trade was taken only because a refused entry left its book free (0 in every period).
- The full tables per period, with no cap as well, are in `results/report.md`.

## Why R107's number was bigger

R108 replays R107's model, then adds the app's choices one at a time:

| step | design net (trades) | walk-forward | final |
|---|---|---|---|
| R107's model (its order, refused entries free the arm, no "one option, one arm", no kept place) | -Rs 4,04,832 (4,303) | -Rs 1,71,951 (3,127) | Rs 57,202 (1,692) |
| + a refused Donchian / R52 entry is a would-be trade that holds the arm's place (the app) | -Rs 1,92,039 (3,553) | -Rs 7,574 (2,596) | Rs 49,877 (1,428) |
| + "one option, one arm" (the app, 19 Oct) | -Rs 1,70,073 (3,514) | Rs 6,897 (2,570) | Rs 39,993 (1,411) |
| + the app's order inside a minute (Liquidity's step runs before the passed arms') | -Rs 1,27,220 (3,528) | Rs 25,105 (2,582) | Rs 16,878 (1,419) |
| + R72's kept place (22 Oct / 25 Oct) = **the app's cap** | **-Rs 1,28,786** (3,456) | **Rs 25,383** (2,536) | **Rs 15,955** (1,384) |

- The replay of R107's model gives 4,303 design trades, the same as R107, and -Rs 4,04,832 against R107's -Rs 4,02,702.
  R107 noted a counting slip in its own code.
- R72's kept place changes little: about -Rs 1,500 in design and -Rs 900 in the final.

## The app's rule, as modelled (read from the source on 26 Oct)

- **The cap.** At most 2 automatic open index-option positions betting the same way (call = up, put = down), across all
  arms and all five indices (`SameDirectionCap.refusal`).
- **The order inside a minute.**
  - The ORB step runs before the passed arms' step (`work/Jobs.kt`). So at a bar close, Liquidity's six books decide
    first: BANKNIFTY 15m / 5m, FINNIFTY 30m / 5m, MIDCPNIFTY 15m / 5m.
  - Then come R72 (first, because it is paper only), R51, then R52. Inside each arm the indices go NIFTY, BANKNIFTY,
    FINNIFTY, MIDCPNIFTY, SENSEX (`PassedArmsHost.tick`).
- **R72's kept place.** Inside R72's window (minutes 0-2 after each 5-minute bar close, 09:20 to 15:12), the last place a
  way is kept for R72:
  - for Liquidity, always (it decides before R72 has looked at the bar);
  - for R51 / R52, only while R72 had a break due that way at the start of the pass.

  It does not apply once R72 already holds a position that way (`placeKeptFor`, `noteR72Due`).
- **What a refusal does.**
  - R72, R51, R52: the entry becomes a would-be trade. It counts against the arm's own limits (one a side, 2 a side a day,
    R52's one a day), so the arm does not take a later signal instead (`buyOne` → `wouldBe`).
  - Liquidity: nothing is booked, and its book stays free.
- **One option, one arm.** An arm wanting an option another passed arm holds gets a would-be trade, which the cap never
  counts (`buy`'s `held` / `twin`).

## The phone check (release diag-2026-10 of the private data repo; read only, untrusted, rupees redacted)

The same mechanisms appear on the phone:

- **26 Oct:**
  - 09:20:03: R72 bought NIFTY and BANKNIFTY puts.
  - 09:25: Liquidity 5m FINNIFTY and MIDCPNIFTY puts were refused ("2 puts already open ... by R72").
  - 10:00: Liquidity 5m FINNIFTY bought a call, and Liquidity 5m MIDCPNIFTY's call was refused because "the last call
    place is kept for R72 Breadth Donchian at its bar close". R72 bought its NIFTY call 9 seconds later.
  - R72's FINNIFTY, MIDCPNIFTY and SENSEX calls and R51's FINNIFTY and SENSEX calls were kept as would-be trades. R51's
    NIFTY call was the "one option, one arm" copy of R72's.
- **19-26 Oct (6 sessions):**
  - would-be trades: R72 15 of 26, R51 9 of 29, R52 1 of 4, Solo 2.0 1 of 2;
  - Liquidity cap refusals: 1 on 21 Oct, 1 on 22 Oct, 3 on 26 Oct.

  In the model, R72 has about 70% of its signals refused or kept as would-be trades (2,169 of 3,071 in design).
  The phone shows 58%.
- **Not checkable:**
  - The upload has no cap summary line and no rupees, so the "16 paper entries refused today" figure cannot be
    confirmed from it. Up to 12:55 the record shows about 8 cap refusals (Liquidity 3, R72 3, R51 2).
  - The lake ends on 13 Oct, so 19-26 Oct cannot be replayed.

## The 7 alternatives (pre-registered; final read once)

| test | rule | net: design / walk-forward / final | final twin p | BH q | verdict, first reasons |
|---|---|---|---|---|---|
| (today) CAP2 | the app's cap | -Rs 1,28,786 / Rs 25,383 / Rs 15,955 | 0.45 | - | - |
| CAP3 | the same cap at 3 | -Rs 27,019 / Rs 1,41,561 / Rs 87,231 | 0.043 | 1.0 | fail: design 27 bad days vs 13, 3 DD breaches vs 1, 7 bad months vs 6; final 2 breaches vs 1 |
| IDX1 | at most 1 a way per index (no cross-index count) | Rs 63,195 / Rs 2,39,789 / Rs 66,275 | 0.46 | 1.0 | fail: design 35 bad days vs 13, 5 breaches vs 1 |
| IDX1C3 | 1 a way per index and at most 3 in all | -Rs 13,349 / Rs 1,38,339 / Rs 67,903 | 0.17 | 1.0 | fail: design 25 bad days vs 13, 2 breaches vs 1 |
| RISK | open stop-risk a way at most Rs 6,500 | -Rs 63,400 / Rs 48,513 / Rs 69,927 | 0.33 | 1.0 | fail: design 7 bad months vs 6; final 4 bad days vs 2 |
| EDGE | cap 2; the arm with the best per-trade net so far decides first | -Rs 70,534 / Rs 66,121 / Rs 8,100 | 0.51 | 1.0 | fail: design 9 bad months vs 6; final net below the cap |
| STOPS | no cap; day -Rs 6,500, week -Rs 20,000, month -Rs 13,000 loss stops | Rs 84,731 / Rs 1,57,839 / -Rs 2,041 | 1.0 | 1.0 | fail: 13 bad months vs 6 in design; final net below the cap |
| EXEMPT | cap 2 on R72 and R51 only (R52, Liquidity outside) | Rs 2,38,692 / Rs 3,61,776 / Rs 70,140 | 0.41 | 1.0 | fail: design 29 bad days vs 13, 3 breaches vs 1; final 3 breaches vs 1 |

Final details, as trades / max drawdown / worst day:

| test | trades | max drawdown | worst day |
|---|---|---|---|
| CAP3 | 1,807 | Rs 51,894 | -Rs 22,027 |
| IDX1 | 2,105 | Rs 81,864 | -Rs 25,523 |
| IDX1C3 | 1,744 | Rs 62,750 | -Rs 19,438 |
| RISK | 1,451 | Rs 63,461 | -Rs 15,309 |
| EDGE | 1,360 | Rs 83,281 | -Rs 17,197 |
| STOPS | 1,076 | Rs 64,106 | -Rs 13,487 |
| EXEMPT | 1,813 | Rs 66,020 | -Rs 34,357 |

- **The pattern.** Every rule that lets more trades through earns more and brings more very bad days. None gets the
  extra net with no extra damage. The account-rule gate was failed by all 7 in design.
- **Loss stops alone (STOPS) were worse than chance at choosing.** Their twin p was 1.0 in all three periods: the
  entries they block after a losing day tend to be the winners.
- **The multiple-testing bar.** With 5,777 earlier tests counted (every family R64-R107), only a twin p near 1 in 100,000
  can pass. The best R108 p was 0.043.

## What Boss's account rule needs (descriptive)

Max drawdown and the number of months at or below -Rs 13,000, at 1 lot:

| arms | design: no cap | design: cap 2 | final: no cap | final: cap 2 |
|---|---|---|---|---|
| Liquidity alone | Rs 32,537, 1 | **Rs 30,827, 0** | Rs 61,062, 1 | Rs 35,911, 1 |
| R52 alone | Rs 82,713, 2 | Rs 61,289, 2 | Rs 47,194, 1 | Rs 36,095, 1 |
| Liquidity + R52 | Rs 93,779, 1 | Rs 63,007, 1 | Rs 52,289, 3 | Rs 20,388, 1 |
| R72 + R52 | Rs 2,16,943, 4 | Rs 1,35,995, 3 | Rs 55,636, 1 | **Rs 33,512, 0** |
| Liquidity + R72 + R52 | Rs 2,06,650, 4 | Rs 1,53,077, 3 | Rs 58,148, 2 | **Rs 27,599, 0** |
| all four (today) | Rs 2,74,308, 5 | Rs 2,09,037, 6 | Rs 88,497, 2 | Rs 74,624, 2 |

(All 15 sets are in `results/report.md`.)

- No set fits in both periods. With the cap, three sets fit the final (R72 + R52, Liquidity + R72 + R52, and close for
  Liquidity alone), but none of them fits design.
- Every set that includes R51 is far outside in design (drawdown above Rs 1.9 lakh).
- 1 lot is the smallest size, so the honest answer is that **no size of today's four arms keeps the portfolio inside Rs
  40,000 / -Rs 13,000 in both periods**. Getting inside needs fewer arms, a larger budget for the rule, or a new tested rule.

## Two leads (not tests, not gated; each needs its own pre-registered study)

1. **R51's own entries lose on the account.** These are the R51 entries that are not copies of R72's option. In the
   no-cap book they lost in every period:
   - design: -Rs 3,31,143 (2,639 trades);
   - walk-forward: -Rs 1,80,097 (1,935);
   - final: -Rs 67,104 (1,021).

   Its copies of R72's option (would-be trades) made Rs 3,41,141 in design. Under the cap, R51 still takes most of the
   places it gets: 2,092 trades, -Rs 3,29,467 in design. This fits the app's own comparison of "Donchian's trades breadth
   did not confirm". A test of "R51 record only" (or behind R72) is the natural next study.
2. **"One option, one arm" takes R52's best trades.** R52's 09:20 option is often the one R72 has just bought. The rule
   then makes R52's version a would-be trade: 526 trades, Rs 2,87,980 in design, against 180 kept trades worth Rs 2,593.
   R72's version of the same option is the one kept, and it earns less. A study of which arm should own a shared option
   could recover part of this, without loosening the cap.

## Honest limits

- **The final window is not fresh.** R51-R107 have all read it.
- **R51 is plain Donchian 20 (R101).** The app runs it with its Supertrend filter, which skips some breaks.
- **Not modelled:**
  - Solo 2.0, whose picks do not reproduce;
  - 25 Oct's opposite-bets rule, weekly stop and 2% cap;
  - Liquidity's own no-twin rule and its h14 capped limit;
  - missing prices on the phone;
  - R72 signals on an index's own expiry day, which in the app can keep a place for 3 minutes and so refuse slightly more.
- **The exit minute counts as open,** the arms' own convention.
- **The walk-forward window sits inside design** (as R107).
- **RISK's Rs 6,500 and STOPS' Rs 6,500 day limit** were set in the pre-registration as half of Boss's -Rs 13,000 month,
  not tuned.

## Checks

- Each arm alone reproduces R101 to the rupee:
  - design: Liquidity 881 / Rs 2,57,249; R72 3,071 / Rs 2,16,929; R51 4,498 / Rs 9,998; R52 Rs 2,90,573;
  - final: Liquidity 357 / Rs 11,155; R72 1,202 / Rs 1,71,366; R51 1,781 / Rs 81,845; R52 Rs 48,612.
- R108's own tests: 30 passed. They include the look-ahead guard: changing any later trade's result never changes an
  earlier decision, for every rule.
- `research/brain/tests`: 183 passed. `features catalog --check`: up to date.
