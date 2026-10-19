# R86d: the BANKNIFTY seller detector, improved and tested on unseen data

*19 Oct 2026. Research only; nothing in the app changes. Pre-registration: `research/hunt/r86/PREREG_D.md` (commit
544ddd5, before any run). Code: `research/hunt/r86/r86d.py`, tests `test_r86d.py`. Results: `results/d_design.json`
(post hoc, committed a9624bc before the final read) and `results/d_final.json` (read once; guard `FINAL_D_READ`).*

Boss, 19 Oct: *"improve the BANKNIFTY seller detector and test again"*.

## The answer for Boss

**The detector still RECOGNISES seller mornings on data it has never seen, but trading it lost money in 2025-26. All
four improved versions fail; nothing goes to paper.**

- On 1 Jan 2025 - 14 Oct 2026 (unseen, read once), the model ranked heavy FII-selling mornings well: AUC 0.72
  (BANKNIFTY-trained) and 0.74 (pooled). AUC is how well it ranks days: 0.5 = coin flip, 1 = perfect. That skill is real.
- But shorting BANKNIFTY at 09:45 on those mornings lost money in every version (futures, after costs).
  - The 2023-24 profit (+Rs 36,188 on 13 trades) did not repeat: the same rule made -Rs 10,477 on 23 trades.
  - Win rate 32-39% (design 33-54%).
- Bought as an ATM put instead (how the app would trade it), three versions were slightly positive (+Rs 7.5k to
  +Rs 17.7k on 23-62 trades). Every one turned negative without its 5 best trades, so they are not proven either.

**Why it fails:** knowing that a seller is at work is not the same as knowing the price will fall further after
09:45. On many flagged mornings the selling was already done at the open, and the index bounced and hit the stop.

## Variants (fixed before any run) and results

Signal on the underlying (BANKNIFTY index minutes, the 14 bank stocks, India VIX), decided at 09:45 from 09:15-09:44.
Short the BANKNIFTY future at the 09:45 open; stop = max(0.5 x the 09:15-09:44 range, 0.15%); R86's futures costs;
500 random-day twins (short at 09:45, same stop and exit).

| id | change | design 2022-24 (post hoc) | FINAL 2025 - Oct 2026: trades | net (Rs) | win | without top 5 | random twins | twin p | BH q | as ATM put |
|---|---|---|---|---|---|---|---|---|---|---|
| D0 | C6 as it was (reference, not gated) | 13, +36,188 | 23 | -10,477 | 39% | -62,583 | -30,748 | 0.218 | - | +7,471 (23) |
| D1 | train on NIFTY + BANKNIFTY days | 14, +38,956 | 31 | -50,470 | 36% | -1,02,576 | -39,094 | 0.651 | 0.651 | -10,587 (31) |
| D2 | hold to 15:15 | 13, +58,774 | 23 | -5,084 | 39% | -99,863 | -29,454 | 0.273 | 0.547 | +17,694 (23) |
| D3 | no model: gap down >= 0.5% and still down at 09:44 | 39, +15,785 | 25 | -16,336 | 32% | -1,41,126 | -24,429 | 0.469 | 0.625 | +16,977 (25) |
| D4 | pooled + hold to 15:15 + top 20% | 36, +8,792 | 62 | -41,361 | 39% | -1,70,618 | -77,390 | 0.265 | 0.547 | +9,490 (62) |

- Stops: D2 13 of 23, D3 17 of 25, D4 37 of 62 trades hit the stop in the final.
- D2 (hold to 15:15) made +Rs 10,756 in 2025 (7 trades) and lost Rs 15,840 in 2026 (16).
- The futures losses are smaller than random shorts at 09:45 (twins -Rs 24k to -77k): the mornings it picks are
  better than random ones, but still not profitable. No p-value is below 0.10.
- The put version does better than the future because a put's loss is capped and the big down days pay several
  times the premium. But it rests on a few days (without the top 5: -Rs 22k to -75k).

## What this means for the bot

| item | label |
|---|---|
| A "seller at work" morning can be recognised (AUC 0.72-0.74 on unseen 2025-26 data) | fact, usable by Jarvis as a description |
| Shorting it at 09:45 (future or put) | fails on unseen data; rejected (notes only, CLAUDE.md) |
| A BANKNIFTY gap down >= 0.5% that is still down at 09:44 | context only: the 2023-24 continuation (catalogue P2) did not hold in 2025-26 |

This closes the seller-detector line unless a new idea changes the entry (for example waiting for the first bounce to
fail after 09:45), which would need its own pre-registration and fresh data. The 2025-26 window has now been read for
this family.
