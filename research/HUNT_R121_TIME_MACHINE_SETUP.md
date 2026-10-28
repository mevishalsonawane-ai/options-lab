# R121: can the time machine be made useful? Not as a filter.

**Answer: no.** After R120, Boss asked (28 Oct): *"Can we enhance it"*, then *"yes do both"*. R121 rebuilt the time
machine two ways. Neither made any arm better as a pre-entry filter: **0 of 28 tests passed**.

- **The replay is now faithful.** It replays the arm's own past entries with its real exits (time stops, locks,
  trail) on the index. On the design period that replay has the same win/loss sign as the real trade 90% of the
  time for R51, R72 and Solo (4,208, 3,071 and 163 trades). The app's current replay manages 69-76%.
- **But the past still doesn't predict the next trade.** The average result of an arm's own entries over the last 60
  sessions has a correlation with the next trade's real win of:
  - **0.02** for the setup replay (16,552 trades);
  - **−0.003** with the real exits (16,552 trades).
- **"Against" almost never fires on the profitable arms.** In the final period:
  - R72, R51 and R52 had **0** trades skipped;
  - Solo had 1, and skipping it lost ₹4,235;
  - Liquidity had 26 skipped, and the net fell from ₹11,155 to ₹10,420.

Together with R120: the time machine is not a filter, in its app form or in this improved form. It stays shadow,
labelled as unproven.

- **Pre-registration:** `research/hunt/r121_time_machine_setup/PREREG.md`, commit `ce20907`, pushed before any
  result.
- **Code before any gate result:** `38add8b`. Design results before the final: `db4788b`. The final was read once.
- **Every table:** `research/hunt/r121_time_machine_setup/results/report.md`.
- **Signal source:** each arm's own, unchanged (the underlying; Hero's strike is an option-price pick, labelled).
  The filter reads only the arm's own past entries and the index's 1-minute bars of the 60 sessions before the
  entry day.

## What changed from the app's time machine

1. **Setup, not clock time.** R120 found the app's version replays a random past day at the same time of day, so it
   measures how the index moves at 10:20, not how this arm's trades go. R121 instead replays the arm's **own entries**
   on the same index in the 60 sessions before the trade's day.
2. **Real exits.** The app plays every replay to 15:15. R121 (variant V_EXITS) uses what the arm really does on
   paper, converted to index points at delta 0.5:
   - R51, R72, R52 and Solo: the 30 or 60 minute time stop and the L25 + 30-60-100 lock;
   - Liquidity and VIX divergence: the 20-minute "out unless 5% up";
   - the ORB family: its ladder;
   - Hero: the 25% trail.

Each replay is scored in R (its result in index points divided by its stop distance). The verdict is:
- **edge** when the average R is clearly above 0;
- **against** when it is clearly below 0 (clearly = by at least 0.05 R and two standard errors);
- **coin flip** otherwise;
- **unknown** with fewer than 10 past entries.

Rule tested: **skip "against"**. Gates as R120:
- net up in design, in walk-forward and in the final;
- final above 95% of 2,000 random skips;
- multiple-testing q ≤ 0.10 over m = 9,300,141;
- at least 40 final trades kept;
- drawdown not worse.

## Results (final period, 1 Apr - 13 Oct 2026; net in rupees after charges, 1 lot)

| arm | final trades | V_SETUP: skipped, net after (p) | V_EXITS: skipped, net after (p) | arm's own net |
|---|---:|---|---|---:|
| R72 Breadth Donchian | 1,202 | 0, 171,366 | 0, 171,366 | 171,366 |
| R51 Donchian 20 + ST | 1,670 | 0, 134,743 | 0, 134,743 | 134,743 |
| R52 Open outside value | 293 | 0, 48,612 | 0, 48,612 | 48,612 |
| Liquidity 15+5 | 357 | 5, 5,188 (0.90) | 26, 10,420 (0.54) | 11,155 |
| Solo 2.0 | 127 | 0, 99,735 | 1, 95,500 (0.88) | 99,735 |
| Hero Rs 1,000 ticket | 107 | 8, 2,448 (0.37) | 0, 1,559 | 1,559 |
| Range Fade | 207 | 71, −25,816 (0.88) | 0, −29,811 | −29,811 |
| Pine Matrix 5m | 173 | 6, 39,777 (0.46) | 0, 39,116 | 39,116 |
| ORB, ORB Fresh, ORB Sweep, VIX div., Pine ST 5m / 15m | 27 to 182 | 0 skipped | 0 skipped | |

In design and walk-forward the picture was the same: few skips, and no arm better in all three windows.

## Why it doesn't work

- **The sample is small.** An arm has about 30 to 200 own entries on one index in 60 sessions. Their average R
  almost always sits within two standard errors of zero, so the verdict is "coin flip". For the profitable arms,
  recent results are positive on average, so "against" hardly ever appears.
- **When it does say "against", the next trades don't do worse.**
  - Liquidity's 26 "against" trades in the final won 46% (mean +₹28), against 37% (mean +₹32) for the rest.
  - Range Fade's 71 (setup variant) won 42%, against 29% for the rest.
- **Recent form doesn't carry over.** Pooled over 16,552 trades, the real win rate stays between about 33% and 46%
  whatever the sample's average R was:

| average R of the arm's last 60 sessions (V_EXITS) | trades | real win rate |
|---|---:|---:|
| below −0.40 | 75 | 44% |
| −0.40 to −0.20 | 377 | 33% |
| −0.20 to −0.10 | 778 | 35% |
| −0.10 to −0.05 | 995 | 36% |
| −0.05 to 0 | 1,914 | 44% |
| 0 to +0.05 | 3,680 | 46% |
| +0.05 to +0.10 | 4,139 | 45% |
| +0.10 to +0.20 | 2,764 | 44% |
| +0.20 to +0.40 | 897 | 42% |
| above +0.40 | 933 | 37% |

## Caveats

- **The final is not fresh.** R51-R120 read it, and R121 was designed after seeing R120's final. A fail here is
  still a fail.
- **The replay is on the index at delta 0.5, not on the option.** That's why it tracks the trade well but not
  perfectly. ORB's breakeven locks show as 0 while the real trade pays charges.
- **A skipped trade earns 0**, and the arm's book is not re-walked. The random twins are built the same way.
- **The arm's taken entries stand for "the rule re-run".** A signal the arm's book did not take is not in the sample.

## What this means

- **Nothing passed, so no gate is proposed** for paper or live. The phone's forward log (TODO R21) keeps running.
- **What is shown has changed in the app (28 Oct, same branch).** Every time machine verdict now says it is not a
  proven signal. The Hero ₹1,000 ticket's verdict is hidden, though still logged for research. Display only: no
  trade, order, size or exit changes.
- **What is left to try:** a longer or regime-matched history (more sessions with the same VIX level, gap or trend).
  This is a new idea and would need its own pre-registration and fresh data (the phone's log after 13 Oct). Given
  R120 and R121, it is not expected to help much.

## Reproduce

```bash
# R120's trade lists first (research/HUNT_R120_TIME_MACHINE_GATE.md, Reproduce)
python research/hunt/r121_time_machine_setup/r121study.py replay
python research/hunt/r121_time_machine_setup/r121study.py design && python research/hunt/r121_time_machine_setup/r121study.py final
python research/hunt/r121_time_machine_setup/r121report.py
python -m pytest research/hunt/r121_time_machine_setup -q
```
