# R122: feeding the strategies, chart patterns and market context to the time machine. It failed the final.

**Answer: no.** Boss asked (28 Oct) to *"feed our strategies and chart pattern and all the other things to the
timemachine"* and to *"enhance it in loop until we get success"*, then approved a bounded loop: up to 30 versions
searched on the design period, the best one frozen, and the test period read once.

- **In the search, a filter looked very good.** The best version skips a trade when any of these is against its
  direction:
  - the 5-minute Supertrend;
  - the opening gap;
  - "chasing" (the move since the open);
  - the 20-day trend.

  On the design walk-forward (8,603 trades) it beat random skipping with **p 0.000006**: net ₹7,70,557 → ₹8,94,678,
  with 5,441 trades skipped.
- **On the test period it collapsed.** On 1 Apr – 13 Oct 2026 (4,724 trades, 3,059 skipped) net fell from
  **₹2,83,133 to ₹97,921**. That is worse than skipping the same number of trades at random (z −0.59, p 0.72).
  - R72 fell from ₹1,71,366 to ₹57,008.
  - R51 fell from ₹1,34,743 to ₹27,952.
  - Of the four paper index arms, only R52 gained.
- **So the loop stops here, as agreed.** Nothing is proposed for paper, and live is untouched.

This is the result the bounded loop was built to catch. Searching 30 versions found one that looked like a sure
thing on the data it was chosen on, and it did not hold up on data it had not seen. Had we kept looping on the test
period until something "passed", we would now be running a filter that cuts profits by two thirds.

- **Pre-registration:** `research/hunt/r122_context_gate/PREREG.md`, commit `c017a46`, pushed before any result.
- **Code before round 1:** `a7c06c6`. Every round's choices and results were pushed before the next round ran:
  `results/round2.json`, `round3.json`, `round4.json` and `loop.json`.
- **Every table:** `research/hunt/r122_context_gate/results/report.md`.
- **Signal source:** each arm's own, unchanged (the underlying; Hero's strike is an option-price pick, labelled).
  The filter reads only:
  - the index's bars before the entry minute;
  - earlier sessions' daily values;
  - India VIX;
  - the other arms' earlier entries.

  No option price is used as a signal.

## What was fed in (every input known before the entry)

- **The time machine:** R120's odds and R121's own-setup replay.
- **The other strategies:** how many other arms entered the same index in the last 60 minutes, the same way or the
  opposite way.
- **Chart patterns on the index:**
  - yesterday's NR7 or inside day;
  - price beyond yesterday's high or low;
  - 5-minute Supertrend (10, 3);
  - EMA20 vs EMA50;
  - the Donchian-20 position;
  - the last three 5-minute bars.
- **Market context:**
  - opening gap;
  - move since the open (in ATR);
  - the 20-day trend;
  - 30-minute volatility;
  - distance from the day's high and low;
  - time and weekday;
  - India VIX level and change.

  The expiry-day flag from the plan was left out, because R120's trade lists don't carry it.

## The loop (design period only, monthly walk-forward)

| round | what was tried | best result (pooled, 8,603 walk-forward trades) |
|---|---|---|
| 1 (12) | single rules; logistic, nearest-analog and tree models (skip the bottom 20%) | Supertrend against: ₹7.71 → 11.12 lakh, p 0.0009 |
| 2 (11) | combinations of round 1's helpers; models on the helpful inputs | Supertrend + gap + chase: ₹7.71 → 12.01 lakh, p 0.00001 |
| 3 (3) | variations around that | + 20-day trend: ₹7.71 → 8.95 lakh, p 0.000006 (frozen) |
| 4 (4) | the last pre-registered combinations | none better |

- **The learned models did worse than simple rules.** Logistic regression and trees were below random skipping in
  the walk-forward. The nearest-analog model was only slightly above it.
- **The pre-registered pick favoured the version with the smallest p.** That was the most aggressive skipper (63% of
  trades). It also leaned on the losing, parked arms (Range Fade, ORB Sweep, Pine Matrix), where skipping almost
  anything helps. Both are the kinds of edges that tend not to last.

## The test period (1 Apr – 13 Oct 2026, read once)

Net in rupees after charges, 1 lot. "Skipped" is the number of trades the filter removed.

| arm | trades | skipped | net before | net after |
|---|---:|---:|---:|---:|
| R72 Breadth Donchian | 1,202 | 770 | 1,71,366 | 57,008 |
| R51 Donchian 20 + ST | 1,670 | 1,022 | 1,34,743 | 27,952 |
| R52 Open outside value | 293 | 135 | 48,612 | 63,379 |
| Liquidity 15+5 | 357 | 191 | 11,155 | 8,416 |
| Solo 2.0 | 127 | 63 | 99,735 | 54,615 |
| Hero ₹1,000 ticket | 107 | 88 | 1,559 | −1,428 |
| ORB (and ORB Fresh, same list) | 128 | 93 | 2,876 | −1,613 |
| ORB Sweep | 176 | 158 | −54,421 | −5,582 |
| Range Fade | 207 | 182 | −29,811 | −1,707 |
| Pine Matrix 5m | 173 | 166 | 39,116 | 4,721 |
| Pine ST+EMA 5m | 182 | 114 | −52,573 | −43,665 |
| Pine ST+EMA 15m | 75 | 52 | −83,469 | −59,799 |
| VIX divergence | 27 | 25 | −5,756 | −4,376 |
| **Pooled (ORB Fresh not counted twice)** | **4,724** | **3,059** | **2,83,133** | **97,921** |

Gates failed:
- pooled net down;
- not above 95% of random skips (p 0.72);
- multiple-testing q = 1 over m = 9,300,171;
- fewer than 40 trades kept on four arms;
- net held on only 1 of the 4 paper index arms.

## A correction to R120 and R121

Their random-twin p could never be smaller than 1 in 2,001. Across about 9.3 million logged tests that can never
clear the multiple-testing gate, however strong the effect. R122 used the normal approximation of the same twin
distribution, so a real effect could have passed. The change did not alter R120's or R121's conclusions: their
filters also failed the other gates, including net up in all periods and above 95% of random skips.

## What this means

- **Nothing changes in the app**, paper or live. The time machine stays shadow, labelled "not a proven signal".
- **Across R120, R121 and R122, 86 versions of "read the context before the entry and skip the bad trades" failed.**
  The arms' own entry rules already use what these filters looked at, and what's left looks like noise at this
  sample size.
- **Only clean new evidence could reopen this:** the phone's own forward log (TODO R21), judged with a new
  pre-registration on trades after 13 Oct. Searching the same history again is not recommended.

## Reproduce

```bash
# R120's trade lists and R121's replays first (their write-ups, Reproduce)
python research/hunt/r122_context_gate/r122feat.py build
python research/hunt/r122_context_gate/r122loop.py round 1
python research/hunt/r122_context_gate/r122loop.py round 2 research/hunt/r122_context_gate/results/round2.json   # 3, 4 alike
python research/hunt/r122_context_gate/r122loop.py final D1_ST_GAP_CHASE_TREND     # refuses a second read
python research/hunt/r122_context_gate/r122report.py
python -m pytest research/hunt/r122_context_gate -q
```
