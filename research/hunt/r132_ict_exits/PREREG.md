# R132 pre-registration: the R131 ICT entries with stop-losses and profit locks

Written 29 Oct 2026, before any R132 exit, trade or statistic was computed. Research only: nothing in the app changes,
nothing trades.

Boss, 29 Oct, after R131 (0 of 39 pass): *"Can we enhance it and also try with profit lock and stoploss"*.

## 0. What is known, and what this can and cannot show

- R131: every setup is close to a coin flip on the index before costs (-3 to +5 points a trade in 33 of 39
  variants). An exit rule cannot create an edge that the entry does not have. R81 (66 exit variants on the Liquidity
  arm, 0 pass) and R128 (S5 Trail failed the gates) say the same. R132 tests whether a stop-loss and a profit lock
  change that here.
- **No unseen data is left.** The lake ends on 19 Oct 2026, and R131 already read the holdout (1 Apr - 19 Oct 2026)
  for these entries. So R132 decides on the **design walk-forward** only. The holdout is reported once, labelled
  "already seen for these entries". A variant that passes everything can at most be **proposed for a paper forward
  test**, by Boss's choice. It cannot be called proven.

## 1. Entries (unchanged from R131, signal on the index spot)

The ten as-stated V1 entries of R131 (`research/hunt/r131_ict_ten/PREREG.md`), with the same definitions, option
rule (ATM CE / PE of the nearest non-expiry-day expiry), costs, entry window (09:15-15:00) and one trade at a time per
index per variant: S1-V1 Silver Bullet, S2-V1 Cameron, S3-V1 CISD, S4-V1 CRT, S5-V1 AMD, S6-V1 IFVG, S7-V1 Turtle
Soup, S8-V1 Order Block, S9-V1 Breaker, S10-V1 OTE. The R131 baseline exits (index stop, structural target) are
reported as E0 for comparison. E0 is not a new test.

## 2. Exits (4, the same for every entry; 40 variants, one BH family)

Money terms are on 1 lot, on the option premium. The option bought at B (after slippage). Each minute that has a
trade uses its own high, low and open (wick to wick, Boss's 29 Oct note). A minute without a trade cannot trigger.

| exit | stop-loss | profit lock | target |
|---|---|---|---|
| **E1** | premium stop at -Rs 1,000 a lot | after the open profit has reached +Rs 1,000, lock 60% of the peak open profit | none |
| **E2** | premium stop at -Rs 2,000 a lot | the same 60% lock after +Rs 1,000 | none |
| **E3** | R131's index stop (the sweep or zone's extreme - 0.02%) | the same 60% lock after +Rs 1,000 | none |
| **E4** | R131's index stop | on the index: once price is 1R beyond the entry, the stop moves to the entry (breakeven); after that it trails 1R behind the best index price since entry | none |

These cells match CLAUDE.md's S5 Trail exception (-Rs 1,000 / -Rs 2,000, a 60% lock after +Rs 1,000) so they can be
compared with what already runs on paper. Everything is out by 15:20.

**How a minute is read (no look-ahead, worst case inside a minute):**
- The peak open profit and the lock level use the highs of earlier minutes only. Minute m's high raises the peak only
  after minute m's exit checks.
- The premium stop / lock triggers when minute m's low ≤ its level. It fills at the level, or at the minute's open if
  the open is already below it (a gap). The fill takes stop slippage (Fills "liq", stop=True), plus the dated charges.
- E3 / E4 index stops trigger on the index's wick, as R131. The option is sold at that minute's last traded close.
- If an index stop and a premium lock trigger in the same minute, the worse exit for the trade counts.
- The time exit sells at the last traded close at or before 15:20.

## 3. Tests and gates

- **Random-entry twins with the same exits (the gate twin, 100 per trade).** The same index, day, side and option
  rule. The twin's entry minute is uniform from the rule's own entry minute to 15:00 (the after-signal twin of R131
  N2; it removes the earlier-entry tilt R131 found). The twin uses the same exit rule; E3 / E4 use the same index stop
  distance in points. p = (1 + twin totals ≥ the rule's total) / (1 + twins).
- **The exit's own effect:** the paired change against E0 on the same entries is reported (it is not a gate).
- **BH across all 40 variants.**
- **Gates (design walk-forward, folds F1 2023, F2 2024, F3 2025, F4 2026-01-01 .. 03-15):**
  - ≥ 300 trades;
  - average net > 0 at 1x and 2x slippage;
  - positive in ≥ 3 of 4 folds;
  - BH q ≤ 0.10 against the twins;
  - design max drawdown above -Rs 40,000 and no month below -Rs 13,000 on 1 lot.
- **The seen holdout (1 Apr - 19 Oct 2026):** read once for R132, reported as context with the label above. A passer
  must also be positive there on ≥ 50 trades before it is even proposed for a paper forward test.
- The 65% win-rate rule does not apply: these are Boss's strategies (Boss, 29 Oct).

## 4. Outputs

- Code and tests: `research/hunt/r132_ict_exits/` (`r132.py`, `test_r132.py`), with a look-ahead test of the exit
  simulator: an exit decided at minute m is the same when every minute after m is deleted.
- Results: `results/design.json`, then `results/holdout_seen.json`.
- Write-up: `research/HUNT_R132_ICT_EXITS.md`. Entries: `research/experiments/entries/r132-*.yaml`. RESULT.md is
  updated.
