# R132: does R123's network improve Solo (midday)'s picks? Not enough to pass a gate

29 Oct 2026. Research only: nothing in the app changes. Boss asked to put R123's neural network "under Jarvis, also in the Solo
family". Jarvis's own paper strategy is **Solo (midday)**. R132 asks one question: does the network (N1) make Solo's picks better?

## The answer

**No idea passes the gates, and nothing goes to paper or a forward test.**

- **Solo's rule trades too rarely to reach the gates.** It makes at most one trade a day.
  - In the walk-forward (Jan 2023 - Mar 2026, 791 sessions) it made **245 trades**; the gate needs 300.
  - On Apr - Oct 2026 (137 sessions) it made **35 trades**; the gate needs 100.
  - So no member of this family could pass, whatever its money. The gates were not lowered.
- **The only idea that helped in both periods is F4, the network re-pick.** On days when two or more indices signal, it takes
  the one with the network's best expected net for Solo's side, instead of the strongest move.
  - Walk-forward: 245 trades, 48% won, **+₹313 a trade** (+₹287 at 2x slippage), against Solo's +₹89 on the same days.
    - It changed the pick on 62 of the 125 days with two or more signals.
    - Paired gain: +₹54,879 (90% interval +₹15,821 to +₹103,038).
    - Twins (a random index of those signalling): p 0.021, q 0.105, just above the 0.10 bar.
    - 3 of 4 folds were positive (2023 lost −₹27,973).
    - The best 5 trades are 87% of the profit.
  - Apr - Oct 2026: 35 trades, 49% won, **−₹552 a trade** (−₹584 at 2x). It lost less than plain Solo (+₹5,765 on the 6
    changed days, interval ₹0 to +₹14,663), but it still lost.
- **The vetoes (F1, F2, F5) did not help in the walk-forward.**
  - Each skipped trades that, on average, made money (+₹22 to +₹119 a trade). On Apr - Oct 2026 the skipped trades lost
    (−₹774 to −₹952 a trade), so the vetoes helped there.
  - A veto that hurts in one period and helps in the other is not evidence.
- **The direction veto (F3) did nothing, as expected.** The network is a coin at direction. Paired with plain Solo, it was +₹510
  over 3¼ years.
- **Plain Solo lost on Apr - Oct 2026: 35 trades, 43% won, −₹717 a trade, −₹25,094 in all** (worst drawdown −₹34,259). This
  is the same period's re-read, not a forward test. Its own forward-test bar (−₹25,000 drawdown) is for the app's paper record.
  Boss may want to look at Solo's paper card.

## Every idea

Signal source of every rule: **the underlying index.** Solo reads the index's 1-minute bars at 12:00. The network reads index,
VIX and breadth minutes, with the option chain as context. A **4-in-the-money option** is what is bought. The network's EV is
for the ATM option over R123's 8 target/stop/time cells, so for Solo's option it is a proxy.

The walk-forward ran Jan 2023 - Mar 2026: 791 sessions, 2,373 index-days, 508 signals (77 on expiry days, 10 untradable).

| idea | trades | won | avg net | 2x | folds + | worst month | twins p | q | paired vs Solo (90% interval) | skipped trades' avg |
|---|---|---|---|---|---|---|---|---|---|---|
| Solo plain (reference) | 245 | 47% | +₹89 | +₹62 | 3/4 | −₹16,964 | - | - | - | - |
| F1 EV veto | 72 | 49% | +₹152 | +₹127 | 2/4 | −₹7,467 | 0.255 | 0.42 | −₹10,884 (−₹98,478 to +₹74,495) | +₹63 (173) |
| F2 quiet-hour veto | 187 | 47% | +₹80 | +₹54 | 3/4 | −₹16,886 | 0.489 | 0.49 | −₹6,881 (−₹39,117 to +₹25,004) | +₹119 (58) |
| F3 direction veto | 141 | 51% | +₹158 | +₹130 | 3/4 | −₹14,753 | 0.454 | 0.49 | +₹510 (−₹73,386 to +₹70,545) | −₹5 (104) |
| F4 network re-pick | 245 | 48% | +₹313 | +₹287 | 3/4 | −₹16,964 | 0.021 | 0.105 | +₹54,879 (+₹15,821 to +₹103,038) | - (62 re-picks) |
| F5 F1 and F2 | 48 | 48% | +₹364 | +₹338 | 2/4 | −₹7,467 | 0.233 | 0.42 | −₹4,380 (−₹94,899 to +₹84,236) | +₹22 (197) |

Folds (2023 / 2024 / 2025 / Jan-Mar 2026):
- Solo: −₹41,202 / +₹25,880 / +₹28,114 / +₹9,037.
- F4: −₹27,973 / +₹44,135 / +₹43,105 / +₹17,441.

Apr - Oct 2026 (re-read, not fresh): 137 sessions, 411 index-days, 71 signals.

| idea | trades | won | avg net | 2x | total | worst drawdown | worst month | paired vs Solo (90% interval) | skipped trades' avg |
|---|---|---|---|---|---|---|---|---|---|
| Solo plain | 35 | 43% | −₹717 | −₹750 | −₹25,094 | −₹34,259 | −₹11,813 | - | - |
| F1 | 8 | 38% | −₹428 | −₹454 | −₹3,422 | −₹5,711 | −₹2,956 | +₹21,672 (−₹8,172 to +₹53,693) | −₹803 (27) |
| F2 | 23 | 48% | −₹594 | −₹630 | −₹13,671 | −₹22,836 | −₹9,770 | +₹11,423 (+₹1,046 to +₹22,217) | −₹952 (12) |
| F3 | 26 | 38% | −₹815 | −₹849 | −₹21,194 | −₹23,580 | −₹4,813 | +₹3,900 (−₹14,884 to +₹21,401) | −₹433 (9) |
| F4 | 35 | 49% | −₹552 | −₹584 | −₹19,329 | −₹28,494 | −₹11,813 | +₹5,765 (₹0 to +₹14,663) | - (6 re-picks) |
| F5 | 4 | 50% | −₹277 | −₹307 | −₹1,110 | −₹3,399 | −₹1,996 | +₹23,984 (−₹7,951 to +₹54,254) | −₹774 (31) |

Verdicts (gates: walk-forward ≥ 300 trades, net > 0 at 1x and 2x, ≥ 3 of 4 folds, q ≤ 0.10; holdout ≥ 100 trades, net > 0, account
stop):

| idea | verdict | why |
|---|---|---|
| F1 EV veto | rejected | 72 trades; 2 of 4 folds; q 0.42; hurt Solo in the walk-forward |
| F2 quiet-hour veto | rejected | 187 trades; q 0.49; hurt Solo in the walk-forward; lost in 2026 |
| F3 direction veto | rejected (as expected) | 141 trades; q 0.49; no paired gain to speak of |
| F4 network re-pick | rejected | 245 trades (< 300); q 0.105 (> 0.10); 35 holdout trades, −₹552 a trade. The best of the family: it helped in both periods, but on few days |
| F5 F1 and F2 | rejected | 48 trades; 2 of 4 folds; hurt Solo in the walk-forward |

## How it was done

- **Pre-registration:** `research/hunt/r132_solo_network/PREREG.md`, commit `96799e0`, before any number.
- **Commits:** the walk-forward was committed (`77a8514`) before the holdout read once (flag `results/HOLDOUT_READ.json`).
- **Code and tests:** `r132.py` and `test_r132.py` (19 tests).
  - The tests check the port of `SoloMidday.kt` against hand-made cases.
  - They include the look-ahead guard: bars after 12:00 can change, and the decision does not move.
  - They check that F2's threshold reads only earlier sessions.
- **Solo, ported from `SoloMidday.kt`:**
  - **Indices:** NIFTY, BANKNIFTY, FINNIFTY.
  - **Signal at 12:00:** the move from the 09:15 open is at least 0.5 ATR14, and the close is in the outer quarter of the
    morning's range. The strongest index is taken. An index on its expiry day, or with no tradable strike, passes the turn.
  - **Option:** 4 in the money, 1 lot, bought at the 12:00-12:03 open.
  - **Exit:** an index 1-minute close 0.3 ATR against the trade; breakeven once the index has gone 0.45 ATR its way; else
    14:30. The option is sold at the next bar's open.
  - Exits seen in the walk-forward: 180 at 14:30, 61 index stops, 4 breakeven.
- **The network:** R123's N1, out-of-sample. Forecasts are read from the row that closes at 12:00 (row 164):
  - the best EV over the 8 cells for Solo's side;
  - the 60-minute range;
  - P(up in 60 minutes).
- **Money:** `Costs("dated")`, `Fills("liq")`, 1 lot at today's lots; 2x slippage is the slippage counted once more.
- **Universe:** the (index, day) pairs that have a 12:00 forecast. Plain Solo runs on the same days, so every comparison is paired.
- **Twins (1,000):**
  - for a veto: random vetoes of the same number of Solo's trades in each fold;
  - for F4: a random signalling index on the days with two or more.
- **BH:** across the 5 ideas.

## Honest limits

- **The holdout is not fresh.** R123-R131 had read Apr - Oct 2026 before.
- **Solo's own design overlaps the walk-forward.** Solo's research tuned its rule on data before Jul 2024, so Solo's plain
  numbers for 2023 - Jun 2024 are in-sample for Solo. The paired comparisons are not affected.
- **The port may differ slightly from the app.** The app also skips an index held by another automatic arm; that is not
  modelled. In 2025 - 2026 FINNIFTY trades use the lake's nearest contract, which is monthly.
- **Expiry days come from the lake.** They are inferred as R123 does, from the time value at 15:15 and on the next day. The app
  reads them in advance from the instrument master, so this is the published calendar, not a look-ahead.
- **F4 changes few days.** It acts only on days with two or more signals: 62 changes in 3¼ years and 6 in 2026. Its walk-forward
  gain leans on a few trades: the best 5 trades are 87% of the profit.

## What it means

The network's strength is the size of the next hour's move, and its weakness is direction. Neither changes Solo's record
enough to pass a gate. **Solo stays as it is, on paper.** If Boss wants more, the only lead is F4: let the network break ties
when several indices signal. It would need a logged-only forward test, decided by Boss. At about one changed day a fortnight,
it would take years to judge.

Results: `research/hunt/r132_solo_network/results/results.json`. Trades are in the local cache (`$R132_OUT`), not in git.
