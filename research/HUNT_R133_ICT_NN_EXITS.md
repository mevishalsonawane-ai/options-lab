# R133: every R131 ICT / SMC entry, filtered by R123's network, with stops and profit locks

29 Oct 2026. Research only: nothing in the app changes, nothing trades. Boss, after R132: *"What about other smc/ict
strategies enhance them too, use neuro network and profit lock and stop and try again"*.

## The answer: 0 of 185 pass. The network filter helps clearly, but the trades it leaves are too few to prove it

**All 39 R131 entries were combined with R123's network filter (take a trade only when the network expects that
option to pay) and with each of five exits.** The exits are R131's own; the -Rs 1,000 or -Rs 2,000 premium stop with
a 60% lock after +Rs 1,000; the index stop with that lock; and breakeven at 1R, then a 1R trail. That makes 185 new
combinations.

1. **With the filter, most combinations make money in design (2023 - 15 Mar 2026): 114 of 185.**
   - Without it, almost every one of them lost: R131, R132.
   - So the network is doing real work. It keeps only about 10-15% of the trades, and those are better than random
     picks from the same trades (p < 0.05 in most of the positive cases).
2. **6 distinct combinations pass every design gate** (≥ 300 trades, net > 0 at 2x slippage, 3 of 4 folds positive,
   BH q ≤ 0.10 over 185, inside Boss's account limits). 4 of them are CISD.

| combination | design kept trades | win | net / trade (2x slip) | total, 1 lot | drawdown / worst month | q | holdout period (seen) |
|---|---|---|---|---|---|---|---|
| S3-V2-E0 CISD, ICT's: close above the drop run's open + R131 exits | 413 of 3211 | 62% | Rs 270 (Rs 254) | Rs 111,520 | Rs -18,307 / Rs -11,901 | 0.012 | 32 trades, Rs +302 each (50% won) |
| S7-V2-E4 Turtle Soup: PDH/PDL sweep, CISD + R131 index stop, breakeven at 1R, then a 1R trail | 365 of 2365 | 45% | Rs 301 (Rs 278) | Rs 109,894 | Rs -26,406 / Rs -11,433 | 0.091 | 27 trades, Rs +1,879 each (67% won) |
| S3-V2-E3 CISD, ICT's: close above the drop run's open + R131 index stop, 60% lock after +Rs 1,000 | 413 of 3291 | 69% | Rs 261 (Rs 240) | Rs 107,697 | Rs -15,447 / Rs -7,474 | 0.012 | 35 trades, Rs +77 each (71% won) |
| S3-V1-E3 CISD as stated: close above the last green high + R131 index stop, 60% lock after +Rs 1,000 | 305 of 2484 | 69% | Rs 322 (Rs 302) | Rs 98,164 | Rs -10,712 / Rs -5,863 | 0.010 | 22 trades, Rs -20 each (68% won) |
| S9-W-E4 Breaker wick-only: fails on a wick + R131 index stop, breakeven at 1R, then a 1R trail | 742 of 6023 | 40% | Rs 117 (Rs 94) | Rs 86,585 | Rs -27,709 / Rs -8,940 | 0.015 | 30 trades, Rs -262 each (30% won) |
| S3-W-E1 CISD wick-only: a wick above the last green high + premium stop -Rs 1,000, 60% lock after +Rs 1,000 | 542 of 5163 | 52% | Rs 70 (Rs 48) | Rs 37,774 | Rs -16,537 / Rs -9,864 | 0.031 | 28 trades, Rs +154 each (57% won) |

3. **None passes the last check.** The holdout period (Apr - Oct 2026) gave only 22-35 kept trades for each, against
   the 50 needed. Four of the six were positive there (CISD V2 twice, Turtle Soup on PDH/PDL, CISD wick-only) and two
   lost.
4. **The same warning as R132, more strongly.**
   - **Most of the design profit is in 2023-24.** From 2025 the network says "go" far less often, so each passer has
     only 10-37 trades in 2025 - Mar 2026.
   - **This is many tries on the same data.** R131-R133 together tried about 285 rules. BH covers each study, not all
     three.
   - **The holdout period was already seen**, and the lake ends on 19 Oct.
   - **40 of the 185 combinations duplicate another one's trades.** A fixed-2R entry with an exit that has no target is
     the same trades as its V1, so the BH family is a little larger than the distinct tests.

**Verdict.** Nothing passes, so nothing goes on paper or live (CLAUDE.md: failed ideas stay notes). The lead is
clearer than after R132: **R123's network EV filter on a CISD entry** (CISD V2 with R131's exits, or with the index
stop + lock). It needs fresh data to decide: a pre-registered forward log from today, with a fixed trade count before
any decision. That is Boss's call.

## How it was done

- **Pre-registration:** `research/hunt/r133_ict_nn_exits/PREREG.md`, committed before any number. Nothing new was
  defined: R131's entries, R132's exits (`sim_exit`) and R132's network filter (`r132nn.py`), all unchanged. The design
  results were committed before the one read of the holdout period.
- **Order of rules:** one trade at a time is applied first, then the filter removes trades (as R132).
- **Gate test:** 2,000 random subsets of the same entry and exit's trades, with the same count in each fold.
- **Code and tests:** `research/hunt/r133_ict_nn_exits/` (`r133.py`, `test_r133.py`). Results: `results/design.json`,
  `results/holdout_seen.json`. Entries: `research/experiments/entries/r133-*.yaml` (185, all rejected).
- **Signal source:** the index spot for every entry. The network uses option-chain data only as context, as CLAUDE.md
  allows. Stops and locks are on the option premium or the index, read wick to wick.
