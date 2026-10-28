# R120: would the time machine, read BEFORE each entry, have improved the arms?

**Answer: no.** Boss asked on 28 Oct: *"Are we using the time machine to get the trades' knowledge before all
strategies?"* Today we do not; it runs only after each entry and only reports. R120 tested whether its verdict,
worked out before the entry from the days before it, would have made any arm better as a filter.

- **Nothing passed** in 28 tested cells (14 arms x 2 rules).
- **The odds do not tell winners from losers.** Over 16,810 historical trades, the time machine's "target first"
  rate and the trade's real win had a correlation of **0.04**. In every arm its forecast scored worse than simply
  guessing the arm's usual win rate.
- **One near miss, not a pass.** On R51 Donchian 20 + Supertrend, "skip 'against'" made more money in all three
  windows. In the final it beat 96% of random skips (p 0.039, 1,670 trades, 73 skipped), but it fails the
  multiple-testing gate. It stays a note.

So the app keeps the time machine as it is: words on the ticket, shadow only, never skipping or resizing a trade.
Nothing changes on paper or live.

- **Pre-registration:** `research/hunt/r120_time_machine_gate/PREREG.md`, commit `63466e9`, pushed before any result.
  Amendment A1 (`b1183cf`) corrected each arm's stop and target to match the app's code, before any verdict was
  computed.
- **Code before the first P&L run:** `d9b220f`. Design results before the final: `3af06c8`. The final was read once
  (`results/FINAL_READ`).
- **Every table, every period:** `research/hunt/r120_time_machine_gate/results/report.md`.
- **Signal source:** each arm's own, unchanged. That is the underlying for every arm tested except Hero, whose strike
  is picked by the option's own price (labelled so). The filter reads only the underlying's own past bars.

## What was tested

**The time machine.** For each entry it replays 2,000 random past sessions of the underlying. Each replay:
- uses one of the last 60 sessions before the entry day;
- starts at the same time of day, give or take 2 bars;
- uses the arm's own stop and target in index points;
- checks the stop before the target inside a bar.

It then compares how often the target came first with the break-even rate and says **edge**, **coin flip** or
**against**. R120 ported the phone's code to Python line for line, including Kotlin's own random numbers. It matches
numbers printed by the engine exactly (39 tests).

**The two rules (pre-registered):**
- **Skip "against"**: skip entries the time machine calls against.
- **Edge only**: take only entries it calls edge.

**Each arm's trades** are its own research trade lists, rebuilt with R113's adapters with the same charges, fills
and lot size as their studies. The trade counts and nets match R113 exactly for R72, R51, R52, Liquidity, Hero, the
ORB family and Solo's design period. The remaining gaps:
- Solo's final: Rs 100,737 against Rs 99,172 published, a known one-pick gap;
- Pine: within 1%;
- VIX divergence: 418 trades against R113's 378.

**Periods:**

| period | dates |
|---|---|
| design | 20 Nov 2024 - 31 Mar 2026 |
| walk-forward | Apr 2025 - Mar 2026 |
| final | 1 Apr - 13 Oct 2026 (read by earlier studies, so weaker than a fresh holdout) |

**Gates (all needed):**
- net up in design, in walk-forward and in the final;
- final above the 95th percentile of 2,000 "random twins" that each skip the same number of trades at random;
- Benjamini-Hochberg q ≤ 0.10 over m = 9,300,113 logged tests;
- at least 40 final trades kept;
- drawdown not worse.

## Results: "skip against"

Net is in rupees, 1 lot, after charges. A skipped trade earns 0. p is the share of random twins that did at least as
well. n is the trades in the period, and "skipped" is how many the rule removed.

| arm | design: n, net before → after (p) | walk-forward: net before → after (p) | final: n, skipped, net before → after (p) | passed? |
|---|---|---|---|---|
| R72 Breadth Donchian | 3,071: 216,929 → 273,222 (0.007) | 335,882 → 374,736 (0.003) | 1,202, 62: 171,366 → 182,527 (0.095) | no: final not above the twin |
| R51 Donchian 20 + ST | 4,208: 50,425 → 105,946 (0.027) | 213,748 → 260,678 (0.009) | 1,670, 73: 134,743 → 157,203 (0.039) | no: BH q 1.0 |
| R52 Open outside value | 706: 290,573 → 338,332 (0.003) | 247,057 → 264,628 (0.028) | 293, 19: 48,612 → 42,786 (0.59) | no: final lower |
| Liquidity 15+5 | 881: 257,249 → 221,709 (0.84) | 238,064 → 206,702 (0.85) | 357, 23: 11,155 → 20,858 (0.15) | no: design lower |
| Solo 2.0 | 166: 55,910 → 73,152 (0.04) | same as design | 127, 15: 99,735 → 88,582 (0.47) | no: final lower |
| Hero Rs 1,000 ticket | 248: −14,507 → −7,500 (0.78) | −12,935 → −5,213 | 107, 79: 1,559 → −1,593 (0.52) | no |
| ORB Sweep | 454: −112,523 → −107,115 | −100,325 → −91,915 | 176, 2: −54,421 → −51,424 (0.15) | no |
| ORB, ORB Fresh, Range Fade, VIX div., Pine x3 | at most 11 skipped in any period | | 0 to 4 skipped in the final | no |

What stands out:

- **The design looked good for R72, R51, R52 and Solo.** Only R51 kept it up in the final, and its p of 0.039 is far
  from what 9.3 million earlier tests demand. R52 and Solo did worse in the final, which is the usual sign of a
  design-period accident.
- **"Edge only" lost money in every profitable arm.** It skipped about 95% of the trades. For example, R72's final
  went from Rs 171,366 on 1,202 trades to −Rs 2,398 on 54 trades.
  - The "edge" entries did not win more than the rest. Final mean per trade: R72 −Rs 44 on 54 trades; R51 −Rs 5 on
    76; Liquidity −Rs 1,580 on 3.
  - So the "against" losses on R51 and R72 are not the time machine reading the direction right. They may mean
    entries in a strongly trending recent tape do worse either way. That is an idea for later (below), not a finding.
- **Most verdicts are "coin flip".** R51: 5,144 of 5,878. ORB, Fade and Pine: over 97%. For those arms the gate
  hardly acts.
- **Hero** is called "against" 72% of the time (255 of 355). Its stop (25% of a Rs 5-30 premium, / 0.5) is a few
  index points, which normal noise hits first. Skipping those left 28 final tickets and a loss.

## Calibration: does "target first %" predict the real win rate?

Not usefully. Pooled over all 14 arms, design and final, 16,810 trades:

| time machine's predicted win | trades | actual win rate |
|---|---:|---:|
| 0-10% | 340 | 36% |
| 10-20% | 344 | 45% |
| 20-30% | 753 | 38% |
| 30-40% | 1,923 | 40% |
| 40-50% | 6,179 | 42% |
| 50-60% | 5,920 | 45% |
| 60-70% | 1,079 | 45% |
| 70-80% | 157 | 45% |
| 80-90% | 48 | 42% |
| 90-100% | 67 | 39% |

- The actual win rate stays between 36% and 45% whatever the time machine says. The correlation is 0.04.
- The Brier score is 0.258, against 0.245 for always guessing the pooled 43%. So the forecast is worse than no
  forecast.
- **Per arm, final period:** the Brier score is worse than the arm's constant design win rate in all 14 arms. The
  correlations range from −0.20 (Solo, 124 trades) to +0.37 (VIX divergence, 7 trades).
- A caveat on what is compared: the time machine replays the index move to the stop or target, while the trade's
  result also depends on the option's premium, its time stop and its lock. That gap is part of why the odds do not
  carry over.

## Not tested, and why

- **FT3:** the app passes no stop and no target, so the verdict is always "unknown".
- **Night (R3):** held overnight, so the app never replays it.
- **mom60 (ntfy):** its inputs are not in the data lake.
- **MCX SILVERM morning and MCX 12-month trend:** the app passes no stop, so the verdict is always "unknown".
- **MCX NG evening:** the app replays the option's own symbol, not the underlying.
- **US-night silver:** needs Yahoo series that are not in the lake.

All of these still count in the multiple-testing family as p = 1.

## Caveats

- **Best case for the time machine.** R120 gave every index the full 60 past sessions. On the phone only NIFTY and
  BANKNIFTY (FINNIFTY and SENSEX on Jarvis builds) have that. The others fall back to about 5 days and say "unknown".
- **Skipped trades are removed, not replaced.** In the app, a skip could free the arm for a later signal that day.
  The random twins are built the same way, so the comparison is fair, but the absolute rupees are approximate.
- **R51 and R72 use the app's current paper settings.** The 30-60-100 lock is on, so there is no fixed target and
  R = 1. The trades' nets are their research exits.

## What this means for the app

- **Paper and live:** nothing changes. Live stays untouched, as CLAUDE.md requires. No gate is proposed, because
  nothing passed.
- **28 Oct, later (Boss: "yes do both"):** the app now says every verdict is not a proven signal and hides the Hero
  ₹1,000 ticket's verdict (display only, still logged). R121 then tested an improved time machine and it also passed
  nowhere (research/HUNT_R121_TIME_MACHINE_SETUP.md).
- **The forward log keeps running** (TODO R21). If Boss wants R51's near miss checked on fresh data, the app's own
  forward log after 30 signal days is the right place, with a new pre-registration. A possible later idea (not
  tested): skip entries when the recent tape is strongly one-sided either way.

## Reproduce

```bash
rclone copy gdrive:IraAlgo-data-lake/{index_1m,options_rolling_1m,index_daily,participants,fut_daily_continuous} ~/.cache/iraalgo/lake
R51_CLEAN=1 python research/hunt/r51/r51data.py build
python research/hunt/r57/r57data.py build
R54_OUT=~/.cache/iraalgo/r54 python research/hunt/r54/r54data.py build
R3_OUT=~/.cache/iraalgo/r3orb python research/hunt/r3orb/r3data.py build BANKNIFTY
python research/hunt/r113_time_of_day/adapters/obuy_layout.py ~/.cache/iraalgo/r113/obuy
for a in R72 R51 R52 LIQ SOLO HERO ORB PINE VIXDIV; do python research/hunt/r120_time_machine_gate/r120arms.py build $a; done
python research/hunt/r120_time_machine_gate/r120study.py verdicts R72 R51 R52 LIQ SOLO HERO ORB_ORB ORB_FRESH ORB_SWEEP ORB_FADE VIXDIV PINE_ST_EMA_5M PINE_ST_EMA_15M PINE_MATRIX_5M
python research/hunt/r120_time_machine_gate/r120study.py design && python research/hunt/r120_time_machine_gate/r120study.py final
python research/hunt/r120_time_machine_gate/r120report.py && python research/hunt/r120_time_machine_gate/make_entries.py
python -m pytest research/hunt/r120_time_machine_gate -q
```

The Hero, ORB, Pine and VIX divergence builds need `IRAALGO_LAKE`, `R3_OUT`, `R54_OUT` and `OBUY_SCRATCH` set, as in
their adapters.
