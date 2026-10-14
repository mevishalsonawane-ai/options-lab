# R35: the 1-minute Dual-Supertrend hero framework, on 446 NIFTY and SENSEX expiries

Renumbered 14 Oct from R28 to R35 (the number was taken).

Written 14 Oct 2026, for the framework Boss sent in four messages that day:
- the Supertrends: ST(7, 3) and ST(10, 2) on the option's 1-minute premium;
- a volume spike: the option's volume at least 2× its 10-bar EMA;
- the index above (calls) or below (puts) its VWAP;
- HDFC Bank, Reliance and ICICI Bank moving with the trade;
- entries 14:00-14:45, Rs 2,000 a trade, and the 2× step lock or the 15:05 exit.

- **Pre-registration:** `research/hunt/r35/PREREG.md`, commit `277d21a`, pushed before any trigger or outcome was
  computed.
- **Code, tests and tables:** `research/hunt/r35/` (`results/results.json`).
- **Experiment entries:** `research/experiments/entries/r35-*.yaml` (5 rules, all rejected).
- **Research only:** nothing in the app changes.

## Verdict for Boss

- **It loses money, every year, on both indices.**
  - The framework as specified (S1) traded on 114 of 446 expiries: 77 NIFTY, 37 SENSEX.
  - It lost **−Rs 76,771** after all costs, which is −Rs 379 per Rs 1,000 risked.
  - 25% of trades won. 9.6% reached 4× and 0.9% reached 10×.
  - The worst losing run was 18 trades in a row, and the max drawdown −Rs 79,479. That is beyond Boss's −Rs 40,000
    account stop.
- **The Supertrend flip is worse than no timing at all.**
  - Entering at a random minute between 14:00 and 14:45 on the same days, in the same direction, with the nearest OTM strike at that
    minute and the same size and exit, averages **−Rs 1,675**, against the rule's −Rs 76,771 (p 1.00).
  - A 1-minute Supertrend turning green on an option's premium means the premium has *just jumped*. The rule buys
    after the jump, pays the spread on a fast candle, and then the premium usually falls back.
- **None of the add-ons rescue it:**

  | version | trades | net Rs |
  |---|---|---|
  | S2: held to 15:05 | 114 | −87,116 |
  | S3: without the heavyweight check | 163 | −1,16,661 |
  | S4: with R34's price check (only when the option is not dear compared with how much the index is moving) | 35 | −31,637 |
  | S5: Supertrends on the index chart instead | 70 | −56,251 |

  - Without the heavyweight check (S3) the rule takes 49 more trades and loses Rs 39,890 more. So the check helps, but
    what is left still loses.
  - S4 is the only one with a positive year (2026: +Rs 1,044 on 8 trades).
- **Best and worst.** The best trade was 12 Sep 2024 again: NIFTY 25050 CE, bought at 14:00 for Rs 24.36, peaked
  14.8×, +Rs 9,541. That is the same jackpot day as R25, R26 and R34. Without the best day, S1 is −Rs 86,312.
- **What to do:** no app change, nothing on paper, nothing live. Fast 1-minute indicator flips on option premiums are
  the wrong tool for this: the cost of chasing the flip is larger than anything the flip predicts.
- **Across R21-R26 and R34-R35: 0 of 44 hero rules pass.** The smallest BH q is 0.088. The only lead remains R34's price clue: cheap
  options priced far above the move NIFTY is actually making lose most. That is a "when not to buy" rule, not a
  "when to buy" one.

## The rule as tested (PREREG.md has every detail)

- **Window:** 1-minute bar closes 14:00-14:45; the first trigger per index per expiry.
- **Option:** the nearest OTM strike at that minute, ATM+1 CE or ATM-1 PE. (With NIFTY at 25,020, ATM is 25,000, so the
  call is the 25,050 CE.)
- **Supertrends** ST(7, 3) and ST(10, 2), on that option's own traded 1-minute bars of the day:
  - Wilder ATR and the standard band rules;
  - the trigger is both turning green together (for a put, the same on the PE's premium).
- **Volume:** the option's bar volume ≥ 2× the 10-bar EMA of its earlier bars.
- **Index filter:** the lake's index minutes have **no volume**, so a true VWAP can't be computed. The rule uses a
  **time-weighted average price (TWAP)** of (high + low + close) / 3 since 09:15. A call needs spot above it, a put
  below it.
- **Heavyweights:** HDFC Bank, Reliance and ICICI Bank (`equity_1m` in the lake). Each one's bar at the trigger minute
  must close above its open (calls) or below it (puts).
- **Size:** lots = floor(2,000 ÷ (fill × lot)). **Below 1 lot, skip.** This skipped 7 SENSEX/NIFTY trades in S1.
- **Exit and costs:**
  - S1: half at 2×, then step lock (the best high at m× locks (m − 1)×), else 15:05.
  - Fills and charges are R25's, with real charges and a spread model. The entry is a limit at 1.2× the signal + 0.10,
    never chased.

## Results (all 446 expiries; nothing is fitted, so all of them are out-of-sample)

| rule | trades (CE / PE) | hit % | ≥ 2× / 4× / 10× % | net Rs | per Rs 1,000 | worst day | max DD | losing streak | w/o best day | 2× spread | twin mean | p | q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| S1 | 114 (48 / 66) | 24.6 | 21.9 / 9.6 / 0.9 | **−76,771** | −379 | −1,998 | −79,479 | 18 | −86,312 | −77,560 | −1,675 | 1.00 | 1.00 |
| S2 hold 15:05 | 114 | 13.2 | 21.9 / 9.6 / 0.9 | −87,116 | −431 | −1,998 | −87,116 | 24 | −1,12,269 | −89,395 | −12,358 | 1.00 | 1.00 |
| S3 no heavyweights | 163 (68 / 95) | 22.7 | 20.2 / 11.0 / 1.2 | −1,16,661 | −400 | −1,998 | −1,17,248 | 25 | −1,26,202 | −1,18,098 | −14,153 | 1.00 | 1.00 |
| S4 + price check | 35 (13 / 22) | 22.9 | 20.0 / 2.9 / 0 | −31,637 | −521 | −1,962 | −34,688 | 9 | −34,576 | −32,159 | −4,703 | 1.00 | 1.00 |
| S5 index Supertrends | 70 (34 / 36) | 22.9 | 20.0 / 8.6 / 0 | −56,251 | −460 | −1,948 | −56,251 | 7 | −63,942 | −55,993 | −20,476 | 1.00 | 1.00 |

**By index (S1):** NIFTY 77 trades, −Rs 54,513; SENSEX 37 trades, −Rs 22,257.

**By year (S1, trades / net Rs):**

| 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|
| 5 / −2,700 | 16 / −16,380 | 19 / −11,921 | 25 / −17,205 | 31 / −28,344 | 18 / −220 |

- **Gates:** all 5 fail gates 2 (net > 0), 3, 4 (BH), 5 and 6. S1, S2, S3 and S5 also break the drawdown limit (gate 7); only
  S4 stays inside it (−Rs 34,688).
- **BH family: 44** (R21's 9, R25's 9, R26's 10, R34's 11, R35's 5). The smallest q is 0.088.

## Honest notes

- **The VWAP is a TWAP**, because the lake's index minutes have no volume. A futures-based VWAP could differ on a few
  days. But the core failure is the Supertrend timing, which a VWAP change would not fix.
- **Strike choice.** The spec named "the option premium chart" without a strike. The nearest OTM (ATM ± 1) was fixed
  before the run, and Boss later confirmed it.
- **The heavyweight check** uses the trigger minute's own candle (close vs open), as specified. It is a very noisy
  1-minute signal; a 5-minute version was not tested.
- **SENSEX:** expiries are Fridays, then Tuesdays, then Thursdays over the period. The Rs 2,000 cap skips an expensive
  SENSEX option instead of buying 1 lot over budget.
- **13 Oct 2026 is not in the lake.**

## Checks run

- `python -m pytest research/hunt/r35/test_r35.py -q` passes 10 tests. They cover:
  - the Supertrend (green on a rally, red on a fall, each bar reading the past only);
  - the volume EMA leaving out the current bar, and the TWAP;
  - every trigger condition (heavyweights, TWAP side, volume), and the 14:00-14:45 window;
  - the 1-lot skip and whole lots;
  - a missing heavyweight bar;
  - the look-ahead guard on synthetic days and on real NIFTY and SENSEX days.
- R25, R26 and R34 tests still pass. Brain checks: `python -m pytest research/brain/tests -q` and
  `python -m research.brain.features catalog --check`. `python research/experiments/validate.py`, and
  `python3 research/knowledge/build_pack.py` then `--check`.
