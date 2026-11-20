# R207 + R208: trade only when a big move is expected, and the late-day break (20 Nov 2026)

Boss, 20 Nov, after R206 offered two next tests: *"Both"*.

Pre-registered in `research/hunt/r207_move_gate_late/PREREG.md` (commit 70386978, before any number); code and design
results frozen in 0c0165a3 before the holdout and the fresh index days were read. Every table: `results/report.md`;
numbers: `results/final.json`, `index_part1.json`, `index_fresh.json`, `decision.json`. Research only: **nothing in the
app changed.** Options: today's rules (V5), 1 lot, after real charges and slippage, 21 Nov 2024 - 5 Oct 2026. Index:
1-minute bars, 8 Oct 2021 - 5 Oct 2026.

**Honest limits.** The option holdout (Apr - Oct 2026) is a second look for both ideas. R183 (15 Nov) had already tested
16 "is the market sideways" skips on R72 and R51 and all failed, so R207 tested only what it had not: a forecast of the
**next** 30 minutes, on all three arms.

## Verdict for Boss

**Neither idea works as a rule: 0 of 15 tests clear their gate.**

### 1. The size of the move really is predictable - and it still does not help

The forecast ("today is this much busier than usual so far" x "the usual next 30 minutes at this time of day") ranks the
real next-30-minute range well: rank correlation 0.61 - 0.68 on 71,000 signals over 4.5 years. In its top third the index
moved about twice as far as in its bottom third (R72, recent days: 0.46% against 0.22% of the index).

But a bigger expected move did not make the call more right (R72: right 51.3% / 52.3% / 51.1% across the thirds), and
skipping the "small move" signals:

| arm | skip the lowest third of expected move | money, design | money, holdout | trades removed (design / holdout) | verdict |
|---|---|---|---|---|---|
| R72 | | **-Rs 70,341** | **-Rs 22,407** | 449 / 277 | fail: R72 makes money on its small-move signals too |
| R51 | | +Rs 34,373 | +Rs 3,247 | 673 / 285 | fail: better in only 2 of 4 quarters; random skips did as well 13% of the time |
| Liquidity | | +Rs 2,158 | **-Rs 28,895** | 261 / 153 | fail |

Skipping the lower half instead was the same picture (R72 -Rs 79,934 / -Rs 40,224; R51 +Rs 19,851 / +Rs 636; Liquidity
-Rs 1,239 / -Rs 18,478), and the range-so-far skip on Liquidity lost Rs 20,287 / Rs 11,006.

The one thing worth noting: on **R51** the gate removed 44% of its trades and the money stayed the same or rose, and the
design drawdown halved (Rs 85,937 to Rs 42,474). That fits the diagnosis (R51's own trades are dead weight). It did not
pass, and you are stopping R51 live anyway.

### 2. The late-day break: the index pattern shows up a third time, but it does not pay on options

- **On the index, on days never looked at (1 Nov 2021 - 31 Mar 2022):** R51 breaks at 15:00 - 15:10 were right 58.3% of
  218 signals (50 days; earlier breaks 49.8% of 4,439); R72's 56.9% of 123 (23 days; earlier 50.3%). Same direction as
  R206's two periods (55 - 61%), but on so few days the interval runs from 46% to 71%, so on their own these days do not
  prove it (p 0.10 and 0.23). Fails the test as written.
- **As an option trade with real costs it loses money in every version:**

  | late breaks only, 1 lot | square-off 15:15 | square-off 15:20 | square-off 15:25 |
  |---|---|---|---|
  | R72 breaks, design (278 trades) | -Rs 21,278 (47.5% wins) | -Rs 49,654 | -Rs 36,830 |
  | R72 breaks, holdout (101 trades) | -Rs 26,543 (33.7% wins) | -Rs 34,172 | -Rs 24,565 |
  | R51 breaks, design (520 trades) | -Rs 64,179 (43.5% wins) | -Rs 94,087 | -Rs 69,019 |
  | R51 breaks, holdout (183 trades) | -Rs 23,563 (37.7% wins) | -Rs 27,492 | -Rs 16,013 |

  Why: the index's average move the right way in those 15 minutes is about 0.04 - 0.05% of the index. That is too small
  to pay an option's charges and spread in the last minutes of the day. Being right 57% of the time on the index is not
  the same as making money on the option.
- What the arms already do is better: after the picker and the day caps, R72 took 51 late trades in 22 months and made
  Rs 21,266 on them. Nothing to change.

## Where this leaves us

Today I tested 93 ideas on these three arms (exits, skips, direction, size of move, late day). **None passed.** That is
itself the finding: with today's rules these arms are close to as good as simple rules can make them, and the levers that
actually move your result are the ones you already hold:

1. **Which arms run live** - you are stopping R51, which the data supports.
2. **How much money and how many lots** - below about Rs 1.5 lakh the drawdowns are bigger than the account (R205).
3. **New days** - every table here stops on 5 October. When the Drive lake can be copied again I can re-run to 13 Nov,
   and the forward days will judge the near-misses better than any more back-testing.

Two things could be **logged in shadow** (no trade changes) if you want them judged on new days: the R72 day loss cap
(Rs 3,000), and the expected-move label on R51's paper trades. Both are app work and need your OK.
