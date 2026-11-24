# R210 result for Boss: hybrid trail, Chandelier exit and higher-timeframe filter on R72, R51 and Liquidity 15+5

**Status (24 Nov 2026): pre-registered, code and tests ready, NOT RUN YET. There are no results.**

## What is done

- `PREREG.md` fixes everything before any number: 18 tests (6 ideas x 3 arms), the design years (1 Nov 2024 - 31 Mar 2026,
  4 walk-forward folds), the locked holdout (1 Apr - 13 Nov 2026, read once and only for survivors), the metric, the
  significance test (paired daily sign-flip vs today's exits) and the gate (BH over m = 4,826).
- The 18 ideas, each judged against the arm's CURRENT exits (V5):
  1. **HU** hybrid trail on the index (breakeven at +1R, ATR trail at +2R, swing-low trail at +3R);
  2. **HUNT** the same without the arm's 30 / 20-minute time stop (the only version where a slow trail can act);
  3. **HP** the hybrid on the option's own premium chart (labelled: an option-chart rule, not the default);
  4. **CH** Chandelier exit on the index (3 x ATR(14), 5-minute bars) added to today's exits;
  5. **F15** / 6. **F60** take a break only when the 15-minute / 60-minute index trend (close vs EMA(20)) agrees.
- `r210lib.py` (the rules), `r210study.py` (design -> decide -> holdout -> report, on R193's stack and R186's engine,
  unchanged), `r210entries.py` (the 18 log entries, now `proposed`), `test_r210.py` (25 tests: the rules, the
  look-ahead guard, the gate, engine edge cases).
- The earlier multi-timeframe study (`research/mtf-alignment-20261114`) asked a different question (a stand-alone NIFTY
  rule on 1- and 5-minute candle colours; it lost money) - see PREREG section 0.

## Why there are no numbers yet

The study needs the data lake (`gdrive:IraAlgo-data-lake`, the way R190 / R193 / R194 got it). In this session the
download with rclone and the environment's Drive credentials was refused by the session's permission check, so nothing
was priced. The `research-data` GitHub release only holds older single-index files, not the option minutes these arms
need. Nothing was guessed or simulated in their place.

## To run it (once the data is reachable)

    python3 -I research/hunt/r210_trail_ideas/r210study.py design    # reproduces R193's V5 first, then the 18 tests
    python3 -I research/hunt/r210_trail_ideas/r210study.py decide
    python3 -I research/hunt/r210_trail_ideas/r210study.py holdout   # survivors only, once
    python3 -I research/hunt/r210_trail_ideas/r210study.py report
    python3 -I research/hunt/r210_trail_ideas/r210entries.py results

## What to expect (written before any number)

The arms hold for at most 30 minutes (Liquidity 20 unless up 5%), and R72 / R51's own L25 ladder already locks +5% at
+25%. So the hybrid's +30% / +45% stages and a 5-minute ATR(14) trail will rarely act; HU / HP will mostly differ from
today's exits by dropping the step lock and moving breakeven from +10% to +15%. Earlier studies found that removing the
step lock adds money (R193: V4 beat V5 by Rs 2.6 lakh on R72 over the span), so HU may look better than V5 for that
reason alone, not because of the trail. That will be said plainly in the result. Nothing here changes the app; anything
that passes is only a candidate for Boss's choice.
