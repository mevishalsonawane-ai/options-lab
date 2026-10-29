# R137 pre-registration: continuation after the break of a strong swing (from R135's facts), with and without R123's network

Written 29 Oct 2026, before any R137 trade or number. Research only. Step 3 of `research/ICT_BOOKS_STUDY.md`; Boss:
*"Ok"* to the plan.

## Why this and not something else

- **R135:** on Indian indices the books' "sweep then reverse" fails, and breaks tend to continue. The one structural
  idea with an edge in the books' favour: the first 15-minute close beyond a **strong** swing continues more often
  than beyond a weak one (55.8% vs 52.6% on a 0.25% race, n = 1,169 vs 14,330, q 0.08).
- **Already tested and failed, so not repeated here:**
  - continuation on yesterday's high/low (R89, 0 of 180);
  - the opening-range breakout family (R92, 0 of 257).
- **R136:** the books' concepts made the network worse, so the filter here is **R123's original N1** (as in
  R132-R134).

## The rule (signal on the index spot; buy the ATM option, R131's option rule and costs)

- **Swings:** 15-minute bars, pivots of 3 bars each side on wicks, today and the previous 4 sessions; known only once
  confirmed. This is R131 / R135.
- **A strong swing high** (R135 C4) satisfies both, each known before the break:
  - its bar's high swept an earlier untaken swing high;
  - price then closed below the last swing low formed before it, before any high went above it.
- **Entry (bullish):** the first 15-minute bar that **closes** above a strong swing high, at least 3 bars after the
  swing's bar. Buy the ATM call at the next minute's open. The entry minute must be 09:15-15:00.
- **Stop:** 0.02% below that breakout bar's low (index wick).
- **Bearish:** the mirror (a close below a strong swing low; buy the ATM put).
- **One trade at a time** per index per variant. If a buy and a sell enter in the same minute, neither is taken.
- **Exits** (index wicks; out by 15:20):
  - **B1:** a fixed target 2R from the entry open;
  - **B2:** R132's E4: breakeven once the index is 1R ahead, then a trail 1R behind the best price; no target.

## Variants (4, one BH family)

| variant | entry | exit | filter | design period |
|---|---|---|---|---|
| **B1** | strong-swing break | 2R | none | 1 Aug 2021 - 15 Mar 2026 |
| **B2** | strong-swing break | breakeven + 1R trail | none | 1 Aug 2021 - 15 Mar 2026 |
| **B1N** | strong-swing break | 2R | R123 N1 EV filter (R132 Amendment A) | folds 2023 - 15 Mar 2026 |
| **B2N** | strong-swing break | breakeven + 1R trail | R123 N1 EV filter | folds 2023 - 15 Mar 2026 |

Reported only, as a control: **W1**, the same rule on **weak** swings with B1's exit.

## Tests and gates (R130's gates; no 65% rule: Boss's strategy family)

- **Random-entry twins, 100 per trade:** an entry minute drawn uniformly from the rule's own entry minute to 15:00
  (after the signal), with the same day, side, stop distance and exit rule. p = (1 + twin totals ≥ the rule's
  total) / 101.
  - For B1N and B2N the test is R132's instead: random subsets of the unfiltered trades, same fold counts.
- **BH over the 4 variants.**
- **Design gates:**
  - ≥ 300 trades;
  - average net > 0 at 1x and 2x slippage;
  - ≥ 3 of 4 walk-forward folds positive (2023, 2024, 2025, 2026 Q1);
  - BH q ≤ 0.10;
  - max drawdown above -Rs 40,000 and no month below -Rs 13,000 on 1 lot.
- **The holdout (1 Apr - 19 Oct 2026), read once** for all 4 variants after the design results are committed:
  - positive average net on ≥ 50 trades;
  - B1N / B2N use R123's holdout views.
  - **This entry family has never been read on the holdout.**
- **A variant that passes everything** is proposed (not built) as a **paper** arm by Boss's choice, never live.
  Otherwise it is rejected.

## Outputs

- `research/hunt/r137_strong_break/` (`r137.py`, `test_r137.py`); `results/design.json`, `results/holdout.json`.
- `research/HUNT_R137_STRONG_BREAK.md`; entries `research/experiments/entries/r137-*.yaml`; RESULT.md updated.
