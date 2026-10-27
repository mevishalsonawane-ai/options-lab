# R119: R118's stable expiry-day patterns, tested as strategies

Written 27 Oct 2026, for Boss: *"Test the stable patterns as pre-registered strategies, and provide results."*

- **Pre-registration:** `research/hunt/r119_atlas_tests/PREREG.md`, commit `825eecd`, pushed before any profit or loss.
- **Code and tests:** pushed before the full run (`3995743`).
- **Code, tests and tables:** `research/hunt/r119_atlas_tests/`. `results/report.md` has every period of every strategy.
- **Experiment entries:** `research/experiments/entries/r119-p1..p6.yaml` (6, all failures).
- **Research only.** Nothing in the app changes; nothing goes to paper or live.

## Verdict for Boss

**0 of 6 pass. Every strategy fails at the first gate: none is positive in all three periods.**

- **The patterns are real, but most are too small to pay for trading them.** They describe how prices behave. After
  the spread and the charges on each order, only one keeps a profit before costs that is worth looking at, and it
  still loses after costs.
- **The closest is the 09:30 iron fly (P1).**
  - The structure: sell the ATM straddle, buy wings 2 straddles out, hold to 15:05.
  - **Before costs it makes money in every period:** +Rs 122 a trade in design, +Rs 350 in walk-forward, +Rs 192 in the
    final (860 trades, 1 lot per leg). This is R118's "the straddle out-prices the typical move" showing up in
    rupees.
  - **The costs take it all.** Eight orders cost about Rs 184 a trade. Net: −Rs 60 in design, +Rs 167 in walk-forward,
    −Rs 1 in the final (105 trades).
  - **Its risk breaks Boss's rules anyway:** a worst day of −Rs 23,917 on one lot of each leg, and 10 months at or below
    the −Rs 13,000 monthly stop.
  - This is the same answer R103 got at 09:20 on NIFTY and BANKNIFTY. R119 confirms it on all five indices, and at
    12:00 (P2) it is worse.
- **The others lose before costs, or have no edge to lose to costs:**
  - **P3, the late long straddle** (15:00 → 15:20): −Rs 180 a trade before costs. R118 flagged the reason in advance:
    the "last 30 minutes are under-priced" reading was partly 1-minute chop inflating realised variance. On top of that,
    late spreads are wide.
  - **P4, selling cheap "longshots"** as capped spreads: the overpricing is real, but once the wing is bought the spread
    makes about nothing before costs (−Rs 5 a trade). Charges average Rs 156 a trade, so 97% of trades lose.
  - **P5, fading sharp 15-minute index moves:** the chop is real (R118 pattern 5), but too small to beat an ATM option's
    decay and spread in 15 minutes. Gross −Rs 17 a trade. Its random-direction twin did better (twin p 0.90), so fading
    beats a coin flip on nothing.
  - **P6, buying the OTM call 1.25 straddles out:** calls do run further than puts, but buying them still loses
    −Rs 139 a trade before costs. This is R118's longshot finding again: cheap options pay back less than they cost.

## The six strategies and their results

Every strategy fixed in advance; expiry days of all five indices; 1 lot per leg at the lot in force on the day.
- **Fills:** the next minute's open plus R25's volume-based slippage and R54's late-minute widening.
- **Charges:** dated, per order.
- **Stale wings:** repriced by Black-76 (R103's fix).

Periods: design 2021-23, walk-forward 2024-25, final 2026 to 19 Oct. Mean is net rupees per trade.

| id | from the R118 pattern | rule (signal source) | design mean (n) | walk-fwd mean (n) | final mean (n) | all: win | all: gross / costs / net per trade | worst day | months ≤ −13k | twin p | passes? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| P1 | straddle rich, tails fat, implied > realised early | iron fly 09:30, wings 2 straddles, to 15:05 (underlying) | −60 (375) | +167 (380) | −1 (105) | 58% | +231 / 184 / **+47** | −23,917 | 10 | 0.19 | no |
| P2 | the same, from 12:00 | iron fly 12:00, to 15:05 (underlying) | −20 (379) | −64 (385) | −519 (108) | 59% | +85 / 186 / −101 | −25,131 | 13 | 0.82 | no |
| P3 | late straddle cheap, livelier last 30 minutes | long straddle 15:00 → 15:20 (underlying) | −293 (391) | −214 (389) | −375 (108) | 28% | −180 / 88 / −268 | −6,055 | 3 | 1.00 | no |
| P4 | longshots overpriced (risk-neutral odds too high) | short spreads at ~3% odds, 12:00, to 15:05 (**option chain, non-default**) | −169 (353) | −152 (236) | −145 (57) | 3% | −5 / 156 / −161 | −4,070 | 0 | 0.09 | no |
| P5 | expiry-day chop (mean reversion) | fade 15-min moves ≥ 1.5 sd with the ATM option, 15 min (underlying, default) | −57 (344) | −41 (372) | −208 (97) | 39% | −17 / 51 / −68 | −6,190 | 0 | 0.90 | no |
| P6 | OTM calls reach 10x more often than puts | buy OTM call 1.25 straddles out at 12:00, to 15:05 (underlying) | −146 (386) | −180 (388) | −330 (108) | 11% | −139 / 44 / −183 | −5,673 | 0 | 0.38 | no |

- **Every q is 1.0** at m = 9,300,054. Within R119's own 6 tests the best q is 0.52 (P4).
- **At 2x slippage** every final mean is lower still (P1 −Rs 23, P2 −Rs 542, P3 −Rs 393, P4 −Rs 170, P5 −Rs 212,
  P6 −Rs 333).
- **Stale legs** were repriced: P1 186 of its 3,440 legs, P2 72, P4 169, the others almost none.
- **Margin estimates** (sellers, R103's rule) average Rs 73k a trade for the iron flies, about Rs 1.4 lakh in 2026 with
  the expiry-day ELM. On that capital P1's +Rs 47 a trade is a fraction of a percent.

## What was tested

- **P1 / P2: iron fly.** Decide on the 09:30 (P1) or 12:00 (P2) bar's close.
  - K0 = the index rounded to the strike; S = the ATM straddle.
  - Sell C(K0) + P(K0); buy C(K0 + 2S) + P(K0 − 2S).
  - Out at the 15:05 open (the app's expiry square-off). No stop: R103 already tried stops.
- **P3: long straddle.** Buy C(K0) + P(K0) on the 15:00 bar's close; sell at the 15:20 open. It holds past the app's
  15:05 square-off, so a pass would have needed Boss's decision. It did not pass.
- **P4: short longshot spreads.** At 12:00, each side:
  - sell the OTM option (Rs 1+) whose Black-76 probability of ending ITM, from its own price, is nearest 3%;
  - buy the one 2 strikes further out;
  - out at 15:05.
- **P5: fade.** At 11:00, 11:15 ... 14:30:
  - signal: the index's 15-minute move is at least 1.5 times its expected size (the straddle's sd scaled to 15
    minutes);
  - trade: buy the ATM option against the move, sell 15 minutes later;
  - one position at a time.
- **P6: OTM call.** Buy the call 1.25 straddles out at 12:00; out at 15:05.
- **Gates (all needed):**
  - mean > 0 in design, walk-forward and final;
  - final > 0 without its 3 best days;
  - final > 0 at 2x slippage;
  - random-twin p < 0.05 (2,000 twins: random entry times; for P5, random direction);
  - BH q ≤ 0.10 over all logged tests;
  - at least 40 final trades.

## Honest notes

- **No period was pristine.** R118 found these patterns on all of 2021-26, so the final period had already been looked
  at descriptively, and one 2026 day (NIFTY 19 Oct) was seen in a smoke test before the run. This makes a pass easier,
  not harder, and still nothing passed.
- **Lots and charges are dated.** Rs 20 an order is a flat cost, so the cheaper the legs, the larger the charges are
  against what the trade can make (P4: −Rs 5 a trade before costs, Rs 156 of charges).
- **Pattern numbers:** PREREG.md numbers the patterns as in the chat summary of R118; R118's write-up numbers them
  differently. This write-up names them instead. The rules themselves are exactly as pre-registered.
- **The data is the stored ATM±10 chain.** The far wings that leave it are repriced by Black-76, which R103 found runs
  slightly high, so if anything it flatters the sellers.

## What this means

- **Nothing changes.** No R119 strategy goes to paper or live.
- **What the patterns are good for:** understanding expiry days. They are not trades on their own at 1 lot with
  today's costs:
  - the straddle is usually too dear;
  - the close is not pinned;
  - cheap options are lottery tickets priced generously;
  - the last half hour moves more than normal days' last half hour.
- **The one piece with a pre-cost edge is short ATM premium in the morning** (P1, and R103's naked straddle). It cannot
  pay for its eight orders at 1 lot, and naked it breaks the −Rs 13k monthly stop in one bad afternoon.
