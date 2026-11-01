# R144c: the losing trades of FT3, Expiry Hero, the Hero Rs 1,000 ticket, Night (R3), VIX divergence and IraThinker Flow

1 Nov 2026. Research only: nothing in the app or any arm changes. One of four parallel studies (R144a-d). Boss, 1 Nov:
*"Find all the lost trades in indian market from our study for current arms, and find what all makes it lose money ...
find patterns for each arm, so it could help us to ignore those trades."*

Pre-registration: `research/hunt/r144c_loss_expiry_other/PREREG.md` (pushed before any number). The skip filters were
chosen on the design months only and frozen in `results/filters.json` (pushed) before the test periods were read.

## The answer in short

1. **No skip filter passed.** 12 were tested (3 each on FT3, the Hero ticket, Night and VIX divergence). None passed
   every lose-less gate. Nothing is proposed as a switch.
2. **Expiry Hero (48 trades in five years) and Flow (62 trades in four years) are too small to test.** They are described
   only.
3. **The losses are mostly built into the arms, not in a skippable kind of day:**
   - **FT3 and the Hero ticket are lottery tickets.** 81-91% of FT3 tickets and 78-85% of Hero tickets lose. FT3 makes
     money only through rare runners: 72 of 478 tickets reached 5x and made +Rs 25.2 lakh; the other 406 lost
     Rs 16.5 lakh. In design (Nov 2024 - Mar 2026) only 7 of 78 tickets ran, so the arm lost Rs 1.66 lakh.
   - **Night loses on the next morning's gap,** which it cannot see at 15:20. In every period, 67-79% of its losing
     nights opened against the trade. Nights with a gap against lost 80-82% of the time.
   - **VIX divergence and Flow lose through their tight exits.**
     - VIX divergence on NIFTY: the 20-minute rule and the -15% stop lost Rs 98k over 156 trades; the 35 trades held to
       15:10 made +Rs 1.77 lakh.
     - Flow: the 20% premium stop closed half its trades (31 of 62), every one a loss.
4. **Three near-misses**, worth a forward log but not a switch:
   - **FT3, skip "long unwinding"** (the option's price and OI both fell in the 15 minutes before the mark).
     - It passed every lose-less gate in both test periods and the random twins (p 0.049).
     - It failed only the multiple-test check (BH q 0.20).
     - Those tickets lost 96% (n 24) in the unseen years, 100% (n 17) in design and 83% (n 6) in the final months.
   - **Night, skip when yesterday's India VIX closed above 15.09:** twin p 0.003 and BH q 0.036, but the arm then lost
     money in the final months (+Rs 48k turned into -Rs 15.5k), so it fails.
   - **Hero ticket, skip a quiet morning** (the range by 12:30 under 0.54 of the 20-day average range):
     - twin p 0.016, q 0.093;
     - but the arm loses money anyway, and the final drawdown was Rs 9 worse.
5. **Boss's 65% win rule:** no arm is near it. Win rates are Night 51-55%, VIX divergence 32-47%, Flow 14-44%, FT3 9-19%
   and the Hero ticket 15-23%. No filter moved any arm by more than about 4 points.

Periods (PREREG 2):
- **unseen:** 1 Dec 2022 - 19 Nov 2024;
- **design:** 20 Nov 2024 - 31 Mar 2026;
- **final:** 1 Apr - 19 Oct 2026.

"n" is the number of trades. Rupees are after charges and slippage.

## Summary table

| arm | signal source | unseen: n, win, net | design: n, win, net | final: n, win, net | main flaw | filters tested -> passed |
|---|---|---|---|---|---|---|
| FT3 near-ATM morning | **option-price pick** (labelled) | 262, 19%, +Rs 6.88 lakh | 78, 9%, -Rs 1.66 lakh | 38, 16%, +Rs 1.02 lakh | lives on rare 5x runners; doubles given back; monthly FINNIFTY / MIDCPNIFTY lose | 3 -> 0 (one near-miss) |
| Expiry Hero | **option-price** (straddle premium) + NIFTY move | 21, 24%, +Rs 1.25 lakh | 12, 8%, -Rs 27k | 2, 0%, -Rs 3.9k | the -60% stop on a close sells 39 of 48 trades | too small: none |
| Hero Rs 1,000 ticket | clock; strike by the option's price | 350, 15%, -Rs 27k | 248, 16%, -Rs 14.5k | 111, 23%, +Rs 1.0k | the 25% trail from the fill stops almost every ticket; both tickets lose together | 3 -> 0 (one near-miss) |
| Night (R3) | the index + day-end OI build-up | 334, 55%, +Rs 32.8k | 245, 53%, +Rs 96.3k | 85, 51%, +Rs 48.1k | the overnight gap; losses bunch on both indices | 3 -> 0 (one near-miss) |
| VIX divergence | the index and India VIX | 171, 47%, +Rs 1.55 lakh | 116, 36%, +Rs 15.7k | 28, 32%, -Rs 6.7k | NIFTY's 20-min / -15% exits; fading | 3 -> 0 |
| IraThinker Flow | the index's opening-range break + VIX's break | 30, 27%, -Rs 4.8k | 25, 44%, +Rs 7.8k | 7, 14%, -Rs 9.0k | its 20% premium stop; a coin-flip Time Machine | too small: none |

Sizes: FT3 and Expiry Hero use Rs 5,000 a ticket; the Hero ticket uses 1 lot of at most Rs 1,000; Night, VIX divergence and
Flow use 1 lot. Early days (2021 - Nov 2022) are in `results/anatomy.json` only.

## How each trade list was rebuilt

- **FT3:** R117's FT3 tickets: R98's `ft3_app` pick and LIMIT fill, held to 15:05. **Parity: 478 tickets, mean
  Rs 1,805.25, exact.**
- **Hero ticket:** R117 / R55's cell 12:30 / 15:05 / trail from the fill. **Parity on R55's window: design 248 /
  -Rs 14,507, final 107 / +Rs 1,559, exact.** It is run from NIFTY Aug 2021 and SENSEX May 2023.
- **Night:** R27's B0 (= `NightRules`). All 869 entries matched R27's stored 15:20 price.
- **VIX divergence:** R1's own code on every good session.
  - Its holdout counts match R1 exactly: NIFTY 29, BANKNIFTY 45.
  - Its design counts are 162 / 182 against R1's 148 / 178. The lake now carries a few more days than R1's raw files.
- **Expiry Hero:** no research engine for it runs on the lake (hero_deep's files are not in the repo), so
  `HeroRules.kt` was ported line for line (`hero_port.py`).
  - It gives 21 trades in 2023-24 (the arm's note says 20) and 11 in 2025 - Apr 2026 (the note says 13, 0 wins; here
    1 win).
  - That is close, not exact (another data source).
- **Flow:** R110 priced only the ORB alert without the Flow's Time Machine, FINNIFTY or its exits, so
  `FlowRules.judge` + `FlowArm`'s exits were ported line for line (`flow_port.py`). This includes the Monte Carlo with
  Java's `Random(19)`, tested against Java's own output. Its history is the best case: a full 60 sessions.
  - Of 1,266 days with a first opening-range breakdown on NIFTY, BANKNIFTY and FINNIFTY (own expiry days skipped),
    India VIX's gate stopped 921, the score (or the range / 10-point rules) 268 and the Time Machine 13.
  - That leaves 64 takes, 62 of them priced (2 had no option print).

## FT3 near-ATM morning

**Signal source: an option-price pick (not the default; on by Boss's choice). Reported apart.**

| period | tickets | lose | net | lost all (about -Rs 5,000) | 5x runners | net without runners |
|---|---|---|---|---|---|---|
| unseen | 262 | 81% | +Rs 6,88,029 | 123 | 44 | -Rs 8,47,922 |
| design | 78 | 91% | -Rs 1,65,746 | 47 | 7 | -Rs 3,23,479 |
| final | 38 | 84% | +Rs 1,02,093 | 20 | 6 | -Rs 1,29,556 |

**Per expiry day** (one ticket per index-day, so tickets = expiry days traded):

| index | unseen | design | final |
|---|---|---|---|
| NIFTY | 89, 19% won, +Rs 2.00 lakh | 51, 12%, -Rs 1.06 lakh | 28, 21%, +Rs 1.49 lakh |
| FINNIFTY | 81, 17%, +Rs 2.95 lakh | 9, 0%, -Rs 43k | 4, 0%, -Rs 19k |
| MIDCPNIFTY | 70, 21%, +Rs 25k | 16, 6%, -Rs 7.7k | 6, 0%, -Rs 28k |
| BANKNIFTY | 5, 20%, +Rs 79k | 2, 0%, -Rs 9.3k | 0 |
| SENSEX | 17, 18%, +Rs 88k | 0 | 0 |

**Its flaws, plainly:**
- **Everything rides on a few runners.** Every ticket is held to 15:05 with no stop (exit type: 15:05 for all 478).
  - 60 tickets never rose 5%: all lost.
  - 243 rose 5% but not to 2x: 99.6% lost.
  - **98 doubled and still ended in a loss.** R117 already tested seven locks for this; none helped, because a lock also
    sells the runners.
- **Since Nov 2024 (the monthly-only FINNIFTY / MIDCPNIFTY / BANKNIFTY expiries), those indices lost:** 37 tickets in
  design + final, 1 won.
  - This is a lead only. It was not a pre-registered filter and the sample is small.
- **Calls lost in design and final:** 35 tickets won 9% (-Rs 65k) and 19 won 5% (-Rs 63k). Puts made +Rs 1.65 lakh in the
  final months. The unseen years had calls well ahead, so this is not steady.
- **Bunching:** the longest losing streak was 18 (unseen), 24 (design) and 9 (final).
  - The phone's 27 Oct case (R117: FINNIFTY 24200 PE and MIDCPNIFTY 13400 CE sold near zero, about -Rs 8k) is the
    typical loser.
  - The private diagnostics repo was not attached to this session, so that day is quoted from R117, not re-read.
- **Steady patterns at entry:** none can be called steady. The final period has 38 tickets, under the 30-per-fifth bar.
  - Low volume, wide spread proxy, premium level and IV all flip between periods.

**Filters (chosen on design):**

| filter | unseen n / win / net | final n / win / net | twin p | q | result |
|---|---|---|---|---|---|
| skip VIX down more than 1.8% today | 262 -> 211, 19% -> 18%, +Rs 6.88 -> 4.05 lakh | 38 -> 24, 16% -> 17%, +Rs 1.02 lakh -> +Rs 11k | 0.48 | 0.84 | rejected (unseen drawdown worse) |
| skip IV above 1.034x the ATM's | 262 -> 252, 19% -> 19% | 38 -> 33, 16% -> 12%, turns to -Rs 28k | 0.69 | 0.89 | rejected |
| **skip "long unwinding" (price and OI both falling, 15 min)** | 262 -> 238, 19% -> 21%, **+Rs 6.88 -> 7.66 lakh** | 38 -> 32, 16% -> 16%, **+Rs 1.02 -> 1.08 lakh** | **0.049** | 0.20 | **rejected on BH only**: every lose-less gate passed |

## Expiry Hero (HeroRules)

**Signal source: option-price (the ATM straddle's premium) plus NIFTY's 15-minute move. Too small to test:** 48 trades
on NIFTY expiry days, Aug 2021 - Oct 2026. Unseen 21, design 12, final 2.

- **39 of 48 trades end on the -60% stop** (a 1-minute close at 0.4x the fill). All lose, -Rs 1.29 lakh.
  - 21 trades never rose even 5% after the fill.
  - The trigger fires after the straddle has already jumped 15% and NIFTY has moved 0.25%, so the cheap option is
    bought after the move. If the move pauses, a Rs 1-5 option halves in minutes.
- **The money is in 7 trades:**
  - two hit 20x (+Rs 1.15 lakh);
  - three sold half at 5x and held the rest to 15:05 (+Rs 63k);
  - two sold half at 5x and stopped the rest (+Rs 17k).
- **Puts lost in every period** (unseen 12 trades -Rs 14k, design 5 -Rs 5k, final 1).
  - Calls made all the profit, and only in the unseen years (9 trades, +Rs 1.39 lakh).
- **By period:** design 12 trades, 1 win, -Rs 27k; final 2 trades, 0 wins. That matches the arm's own note that it
  failed out of sample.

## Hero Rs 1,000 ticket (HeroTicketRules, trail from the fill)

**Signal source: the clock (12:30); strike by the option's own price band.** 847 tickets (2021-26).

- **Almost every ticket is sold by the 25% trail** (the trail from the fill is also a 25% stop).
  - 143 never rose 5%: all lost.
  - 468 rose 5% but not to 2x: 92% lost.
  - Even 81 that reached 5x lost 56% of the time: the trail sold them before the run, or after giving it back.
- **Both tickets of a day lose together.** On 406 index-days with a call and a put, both lost on 273 (67%) and the day
  lost on 350 (86%).
  - The longest losing streaks: 37 tickets (unseen), 21 (design) and 17 (final).
- **Cheap options at 12:30 rarely move enough:** the median ticket is 1.15 straddles from spot (n 847). R98 found a hero
  needs about 2.9 straddles of travel.
- **Steady pattern:** one fifth (signed 15-minute volume slightly negative, -0.05 to +0.14) loses more in every period.
  - n: unseen 83 (87% lose), design 49 (92%), final 34 (79%).
  - It is weak, and the arm loses on almost every slice anyway.

**Filters:**

| filter | unseen n / win / net | final n / win / net | twin p | q | result |
|---|---|---|---|---|---|
| skip IV above 1.24x the ATM's | 350 -> 296, 15% -> 16%, -Rs 27.1k -> -21.7k | 111 -> 102, 23% -> 23%, +Rs 1.0k -> +2.1k | 0.14 | 0.41 | rejected (still loses; p) |
| **skip a quiet morning** (range by 12:30 < 0.54 x ADR20) | 350 -> 269, 15% -> 17%, -Rs 27.1k -> -18.6k | 111 -> 88, 23% -> 24%, +Rs 1.0k -> +2.0k | **0.016** | **0.093** | rejected: the arm still loses (unseen) and the final drawdown is Rs 9 worse |
| skip OI up less than 1.6x since its first print | 350 -> 277, 15% -> 12% | 111 -> 91, 23% -> 22% | 0.83 | 0.89 | rejected |

## Night (R3)

**Signal source: the index (close location, day move, breadth) and the day-end OI build-up.** 869 nights (2021-26).

- **The loss is the overnight gap.** Exit type is always 09:16 next day.

  | period | nights with the next open against the trade | they lost | money lost on them | losers that had a gap against |
  |---|---|---|---|---|
  | unseen | 142 of 334 | 82% | -Rs 1.87 lakh | 79% |
  | design | 97 of 245 | 80% | -Rs 2.17 lakh | 67% |
  | final | 34 of 85 | 82% | -Rs 1.16 lakh | 67% |

  The gap is decided after the entry, mostly by the US session. The lake has no US index series, so the US move itself
  could not be measured (said, not guessed). It can never be a filter: it happens after entry.
- **Bunching:** 198 of 396 losing nights fall on days where both NIFTY and BANKNIFTY lost. The longest losing streaks:
  8 (unseen), 13 (design) and 10 (final).
- **Biggest losses come from high-premium BANKNIFTY ITM options** in fast markets: -Rs 15.1k on 23 Mar 2026 and
  -Rs 11.3k on NIFTY 18 Mar 2026.
- **Very weak breadth (under 20% of stocks up) puts** lost in the unseen years (49 nights, -Rs 27.5k) and final (13,
  -Rs 6.5k) but not in design (51, +Rs 0.7k): not steady.
- Calls in the final months: 39 nights, 46% won, -Rs 1.6k. Puts made +Rs 49.7k. The unseen years were the other way
  round.

**Filters:**

| filter | unseen n / win / net | final n / win / net | twin p | q | result |
|---|---|---|---|---|---|
| **skip when yesterday's VIX closed above 15.09** | 334 -> 273, 55% -> 55%, +Rs 32.8k -> 52.9k | 85 -> 48, 51% -> 46%, **+Rs 48.1k -> -Rs 15.5k** | **0.003** | **0.036** | rejected: the arm loses money in the final; unseen drawdown worse (Rs 24.7k -> 26.4k) |
| skip VIX up more than 4% today | 334 -> 248, 55% -> 57%, +Rs 32.8k -> 29.1k | 85 -> 65, 51% -> 54%, +Rs 48.1k -> 69.8k (drawdown 43.6k -> 26.0k) | 0.22 | 0.53 | rejected (p; unseen drawdown worse) |
| skip a strong run the trade's way (> 1.02 ADR20) | 334 -> 267, turns to -Rs 191 | 85 -> 77 | 0.89 | 0.89 | rejected |

The VIX-above-15 skip cut the losing days in both test periods by much more than random skipping did. But in the final
months the high-VIX nights were the profitable ones, so it cannot be adopted.

## VIX divergence

**Signal source: the index and India VIX.** 419 trades (2021-26).

- **It is fading:** unseen 171 trades, 47% won, +Rs 1.55 lakh; design 116, 36%, +Rs 15.7k; final 28, 32%, -Rs 6.7k.
- **NIFTY's exits lose; holding wins.**
  - NIFTY: the 20-minute rule (98 exits, -Rs 28.8k) and the -15% stop (58, -Rs 69.3k) together lost Rs 98k.
  - NIFTY's 35 trades held to 15:10 made +Rs 1.77 lakh.
  - BANKNIFTY holds every trade to 15:10: 228 trades, +Rs 1.14 lakh.
  - NIFTY in the final months: 7 trades, 1 win.
- **Expiry days:** 68 trades, 71% lost, but +Rs 51k net (a few big expiry winners).
- **Bunching:** 116 of 247 losers fall on days where both indices lost.
- **Steady patterns:** none (final has 28 trades).

**Filters:** all three rejected.
- **Skip IV above 1.07x ATM:** p 0.56. The final still loses.
- **Skip a wide spread proxy:** p 0.51. The unseen worst month is worse; it would have made the final +Rs 5.5k (24 trades).
- **Skip a gap against the trade:** p 0.75.

A lead worth its own pre-registered test (not run here): NIFTY's Liquidity exit against holding to 15:10, as BANKNIFTY
does.

## IraThinker Flow

**Signal source: the index's own 09:15-09:30 range break, confirmed by India VIX's own break up (puts only, by its
rule).** **Too small to test:** 62 trades, Dec 2022 - Oct 2026 (unseen 30, design 25, final 7).

- **Exits:**

  | exit | trades | result |
  |---|---|---|
  | premium stop (20% under the fill) | 31 | all lost, -Rs 43.9k |
  | index stop (the range's midpoint) | 10 | -Rs 26.9k |
  | index target (2R) | 19 | +Rs 65.5k |
  | 15:15 | 2 | -Rs 0.8k |

  The 20% stop on an ATM option is hit by ordinary noise before the index decides, in half of all trades.
- **The Time Machine adds almost nothing:** the median target-first odds of the taken trades were 50.1% (10th-90th
  percentile 47-53%). The gate is close to a coin flip.
- **Its entries are early:** median 09:45. A morning breakdown that reverses hits the premium stop.
- **By period:** unseen 27% won, -Rs 4.8k; design 44% won, +Rs 7.8k; final 1 of 7 won, -Rs 9.0k.

## Part B (1 Nov, Boss: "Can we find any way to skip this trades"): a hindsight sweep

Plan: `research/hunt/r144c_loss_expiry_other/PREREG_B.md` (pushed before the run). Every period had already been read in
part A, so this sweep can only find leads, never a tested rule.

**What was tried:** 435 simple skip rules on FT3, the Hero ticket, Night and VIX divergence:
- every feature's lowest / highest fifth and two fifths;
- every OI-price quadrant;
- calls or puts, each index;
- VIX divergence's expiry days, FT3's monthly-expiry tickets, Night's close location / day move / OI build-up.

**Answer: no.** No entry-time rule skips the losers better than skipping the same number of trades at random.
- **33 of 435 rules helped in all three periods** (unseen, design, final).
- **None beat random skipping** of the same size: the whole-sweep check gave 0 leads.
- A per-arm check (added afterwards, see the amendment) gave a best family-wise p of 0.23: Night "skip when VIX fell
  more than 3% today". The Hero ticket's best was 0.26 and FT3's "long unwinding" 0.91.
- **VIX divergence:** no rule helped in all three periods.

**Why nothing works as a skip:**
- **The Hero ticket loses money overall.** Skipping ANY 40% of its tickets at random cuts its losses by about 40%. The
  only skip that helps it is trading it less, or not at all.
- **FT3's profit is its rare runners.** Nothing at 11:00-13:00 tells a future 5x ticket from a dud. R98 found the same
  (AUC 0.64-0.69), so any skip throws away runners along with losers.
- **Night's losses come from the next morning's gap,** decided after entry.
- **VIX divergence and Flow lose through their exits,** not their entries.

**What can still be done (rule changes, not skips; each needs its own pre-registered test and Boss's approval):**
1. **Hero ticket:** switch it off, or trade it smaller. It lost money in the unseen years (350 tickets, -Rs 27k) and in
   design (248, -Rs 14.5k).
2. **VIX divergence, NIFTY:** hold to 15:10 as BANKNIFTY does (part C: this HURTS; see below). The 20-minute rule and the -15% stop lost Rs 98k over
   156 trades; NIFTY's 35 trades held to 15:10 made +Rs 1.77 lakh. This is hindsight and needs its own test.
3. **FT3:** stop the monthly-only expiries (FINNIFTY, MIDCPNIFTY, BANKNIFTY since Nov 2024: 37 tickets, 1 won). They had
   no unseen-period trades, so only new days can test it.
4. **Flow:** a wider premium stop than 20% (31 of 62 trades were stopped by noise; part C: this HURTS).
5. **Forward (shadow) log** of FT3's "long unwinding" skip and Night's "VIX fell > 3%" skip on new days. This is the only
   honest way to check them now.

## Part C (1 Nov, Boss: "Backtest all and provide me before and after results")

Plan: `research/hunt/r144c_loss_expiry_other/PREREG_C.md` (pushed before the run). Code: `backtest_c.py`. Results:
`results/before_after.json`. **Hindsight:** every period had been read before, and these changes came from looking at
them, so these numbers are not proof.

Read each "before -> after" as trades / win / net / max drawdown.

**Summary (all three periods together, Dec 2022 - Oct 2026):**

| change | before: trades / win / net / max drawdown | after | verdict |
|---|---|---|---|
| **C1 Hero ticket off** | 709 / 16% / -Rs 40,620 / Rs 43,429 | 0 / - / Rs 0 / Rs 0 | **helps**: it lost money in two of three periods |
| **C2 VIX divergence: NIFTY held to 15:10** | 315 / 42% / +Rs 1,64,055 / Rs 60,491 | 315 / 47% / +Rs 1,41,099 / Rs 1,06,082 | **hurts**: more wins, less money, much bigger drawdown |
| **C3 FT3: no monthly-only expiries** | 378 / 17% / +Rs 6,24,376 / Rs 2,38,223 | 341 / 18% / +Rs 7,31,214 / Rs 1,57,748 | helps in design and final (unseen had none to skip); random-skip p 0.017 |
| **C5 FT3: skip "long unwinding"** | 378 / 17% / +Rs 6,24,376 / Rs 2,38,223 | 331 / 18% / +Rs 7,91,491 / Rs 1,59,734 | helps in all three periods; random-skip p 0.008 |
| **C3 + C5 together (FT3)** | 378 / 17% / +Rs 6,24,376 / Rs 2,38,223 | 306 / 20% / +Rs 8,39,867 / Rs 1,13,214 | best of the FT3 options; p 0.002 |
| **C6 Night: skip when VIX is down > 3.13% today** | 664 / 54% / +Rs 1,77,072 / Rs 85,200 | 536 / 55% / +Rs 2,24,287 / Rs 59,456 | helps on net in all three periods, but the unseen years' drawdown got worse; p 0.002 |
| **C4a Flow stop 30%** | 62 / 32% / -Rs 6,051 / Rs 23,528 | 62 / 34% / -Rs 14,813 / Rs 31,520 | **hurts** |
| **C4b Flow stop 40%** | same | 62 / 34% / -Rs 19,660 / Rs 36,282 | **hurts** |
| **C4c Flow, no premium stop** | same | 62 / 34% / -Rs 20,916 / Rs 37,539 | **hurts** |

**By period (before -> after):**

| change | unseen (Dec 2022 - Nov 2024) | design (Nov 2024 - Mar 2026) | final (Apr - Oct 2026) |
|---|---|---|---|
| C1 Hero off | 350, 15%, -Rs 27,129 -> 0 | 248, 16%, -Rs 14,507 -> 0 | 111, 23%, +Rs 1,016 -> 0 |
| C2 VIX NIFTY to 15:10 | net +Rs 1,55,139 -> +1,79,768; drawdown Rs 20,251 -> 26,755 | net +Rs 15,658 -> **-Rs 33,829**; drawdown Rs 43,407 -> 1,03,147 | net -Rs 6,742 -> -4,841; drawdown Rs 17,417 -> 13,566 |
| C3 FT3 monthly off | 262 -> 262 (nothing to skip) | 78 -> 51, -Rs 1,65,746 -> -1,05,780 | 38 -> 28, +Rs 1,02,093 -> +1,48,965 |
| C5 FT3 long unwinding | 262 -> 238, +Rs 6,88,029 -> +7,66,404 | 78 -> 61, -Rs 1,65,746 -> -82,466 | 38 -> 32, +Rs 1,02,093 -> +1,07,552 |
| C3 + C5 | 262 -> 238, +Rs 6,88,029 -> +7,66,404 | 78 -> 43, -Rs 1,65,746 -> -66,186 | 38 -> 25, +Rs 1,02,093 -> +1,39,648 |
| C6 Night VIX down | 334 -> 279, +Rs 32,755 -> +41,853; drawdown Rs 24,652 -> **36,027** | 245 -> 199, +Rs 96,250 -> +1,20,071 | 85 -> 58, +Rs 48,068 -> +62,363; drawdown Rs 43,635 -> 20,672 |
| C4a Flow 30% | -Rs 4,812 -> -7,826 | +Rs 7,793 -> +2,814 | -Rs 9,031 -> -9,801 |

**What changed from part B's suggestions, said plainly:**
- **VIX divergence:** part B said NIFTY's 35 trades held to 15:10 made +Rs 1.77 lakh. Those were only the trades that
  survived the stop and the 20-minute rule. Holding EVERY NIFTY trade to 15:10 makes less money (+Rs 72.6k ->
  +Rs 49.6k over 142 trades) with three times the drawdown. **NIFTY's exits are doing their job; keep them.**
- **Flow:** a wider or no premium stop loses more. **The 20% stop is cutting real losers; keep it.** Flow's problem is
  its entries.
- **FT3 (C3 + C5) and Night (C6)** improve every period's net, and beat random skipping (hindsight p 0.002 each). They
  were found by looking at these same periods, so they are still leads.
- **FT3 stays a lottery:** win rate 17% -> 20%, far from Boss's 65%.

**Recommendation:**
1. **Hero ticket: switch it off** (Boss's choice; a paper arm, one tap). It lost Rs 40.6k over 709 tickets.
2. **FT3 C3 + C5 and Night C6:** a shadow log on new days (the skipped trades recorded, not traded), behind switches
   that are off by default, before any change to the arms.
3. **No change** to VIX divergence's NIFTY exits or Flow's stop.

## What this means for Boss

- **No switch is proposed.** No filter passed the gates. CLAUDE.md's rule for paper (only ideas that pass) means none
  goes on an arm.
- **The losses are mostly the arms' design, not days that could be skipped:**
  - FT3 and the Hero ticket are lottery tickets;
  - Night carries the overnight gap;
  - VIX divergence and Flow are cut by tight exits.
- **Worth a forward (shadow) log on new days, not a switch** (both found and tested here, so only new days are fresh):
  - FT3's "long unwinding" skip (failed only BH);
  - the Hero ticket's quiet-morning skip.
- **Leads for separately pre-registered tests** (not tested here):
  - FT3 on the monthly-only indices since Nov 2024;
  - NIFTY VIX divergence held to 15:10;
  - Flow with a wider premium stop.

## Honest limits

- The arms were built on studies that read parts of these periods. The filters were fresh for the final and unseen
  periods; the arms were not.
- **Missing data:**
  - no bid / ask or depth: the spread is a proxy;
  - no futures basis history;
  - no US index series (Night's US move);
  - breadth uses the lake's 59 stocks, not the full market.
- The two ports (Expiry Hero, Flow) are close to the arms' published records, not exact. Flow's Time Machine is given a
  full 60-session history; the phone has only what it recorded.
- The 12 filters are one BH family. R144a, b and d test other arms separately.

## Files

- `research/hunt/r144c_loss_expiry_other/`:
  - `PREREG.md`;
  - `arms_expiry.py`, `arms_other.py`, `hero_port.py`, `flow_port.py`, `lake.py`, `features.py`, `study.py`,
    `make_entries.py`, `test_r144c.py` (22 tests, including the look-ahead guard);
  - `results/filters.json`, `anatomy.json` (every table, every fifth of every feature, per period), `tests.json`,
    `pvalues.json`.
- Entries: `research/experiments/entries/r144c-*.yaml` (12 rejected tests, 6 descriptive).
- Commands:

  ```
  IRAALGO_LAKE=<lake> R98_OUT=... R54_OUT=... python research/hunt/r144c_loss_expiry_other/arms_expiry.py
  IRAALGO_LAKE=<lake> R27_OUT=... OBUY_SCRATCH=... python research/hunt/r144c_loss_expiry_other/arms_other.py
  IRAALGO_LAKE=<lake> python research/hunt/r144c_loss_expiry_other/features.py
  python research/hunt/r144c_loss_expiry_other/study.py choose     # design only
  python research/hunt/r144c_loss_expiry_other/study.py run
  ```
