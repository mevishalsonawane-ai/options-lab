# R64 pre-registration: a 30-60-100 profit lock and a -10% stop on the new paper arms

Written 16 Oct 2026, **before any R64 profit or loss was computed**. Commit this file first; results come after. Research
only: nothing here changes the app (no `android/**`, no `.github/**`).

Boss, 16 Oct, after a paper day where two Donchian stop-outs of about -Rs 3,000 each wiped the day's profit: "add profit
lock on 30-60-100 for Donchian and other new strategies, and stoploss should be 10%, and give me the results before and
after."

Data rule (Boss): only data from 20 Nov 2024 onwards.

## 1. The three arms (signals unchanged, on the underlying)

Every signal below is read from the **index** (the underlying); the option is only what is bought. R64 changes **exits
only**: entries, strikes, days and caps stay exactly as the arm's research and the app's engine have them.

| arm | signal (source) | strike | current exits (BEFORE) | research / app |
|---|---|---|---|---|
| **Donchian** (R51 Donchian 20) | 5-min index bar closes beyond the previous 20 bars' high / low (R44 `ind.signals`), entries 09:20-15:10, one open a side, max 2 a side a day, never on the index's expiry day; NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX | 1-ITM | stop -15%, target +30%, 30-minute time exit, square-off 15:15 | `hunt/r51/fam_r44.py` `donchian20\|B\|m1` (Amendment A clean stores), `DonchianArm.kt` |
| **OOV** (R52 Open outside value) | today opens outside the previous session's 70% value area (30-min TPO) and the first 5-min bar closes on the same side -> that side, entry 09:20, one a day per index, never on its expiry day; the same 5 indices | 1-ITM | stop -30%, +10% locked once +40% (R57's Solo layer, as the app runs it), 60-minute time exit, square-off 15:10 | R57 setup S3 logic on R51's clean stores, `OovArm.kt` |
| **Solo 2.0** (R57 A2) | the A2 logistic model's pick: each day the first candidate of setups S1-S4 (all index signals) scoring at least the threshold, one trade a day; NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY | per setup (S1 4-ITM, else 1-ITM) | per setup: S1 index stop 0.3 ATR14 / breakeven once 0.45 ATR / 14:30; S2 as Donchian; S3 as OOV; S4 stop -25%, target +50%, +10% locked once +40%, 60 minutes, 15:10 | `hunt/r57` (`r57setups.EXITS`), `Solo2.kt` |

Solo 2.0's picks are **held fixed**: the trades A2 picked under R57's own monthly-refit walk-forward (design months
Jul 2025 - Mar 2026) and final monthly refits (Apr - Oct 2026), exactly as `r57study.run_a` makes them. A model pick
is decided at the signal minute and never depends on the exits, so only the exits are re-priced. (A second S2
candidate on the same side can only exist once the first one has exited; R64 does not re-generate candidates, so its
picks are the BEFORE picks. Stated as a limit.)

## 2. Boss's rule as R64 reads it (Boss can correct this)

- **Stop -10%** on the option premium: a resting stop 10% below the fill price.
- **Stepped profit lock 30-60-100 on the premium.** Once the option has traded **+30%** above the fill, the stop rises to
  **breakeven** (the fill price plus the round-trip charges: `real entry + charges(entry, entry) / quantity`); once
  **+60%**, it rises to **+30%** above the fill; once **+100%**, to **+60%**. Locks only rise. A lock is set by a
  minute's high and acts from the next minute (R51 engine semantics); a gap through it fills at the open (the worse
  price) with stop-sell slippage, so a breakeven lock can still close slightly negative.
- The ladder replaces any profit lock the arm had (OOV's and S3/S4's "+10% once +40%"). An index-level exit (S1's index
  stop and index breakeven) is a signal exit on the underlying and is kept in every variant.

A +30% fixed target would make the ladder pointless (the trade would be sold at +30% before the ladder could act), so
four AFTER variants are tested, all fixed here:

| id | stop | profit lock | fixed target | time exit | square-off |
|---|---|---|---|---|---|
| **B0** BEFORE | arm's own | arm's own | arm's own | arm's own | arm's own |
| **A1** | -10% | 30/60/100 ladder | none | arm's own (Donchian 30 min, OOV 60, S2 30, S3 60, S4 60, S1 none) | arm's own (Donchian / S2 15:15, OOV / S3 / S4 15:10, S1 14:30) |
| **A2** | -10% | 30/60/100 ladder | none | none | 15:15 for every arm (15:05 on an expiry day; the arms skip expiry days) |
| **A3** | -10% | arm's own | arm's own | arm's own | arm's own (isolates the stop change) |
| **A4** | arm's own | 30/60/100 ladder | none | arm's own | arm's own (A1 with the arm's own stop: isolates the lock) |

For S1 (no premium stop of its own) "arm's own stop" means none on the premium (its index stop stays); A3 adds -10% to
S1, A4 adds only the ladder.

## 3. Measurement (unchanged from R51 / R57)

R51's engine (`hunt/r51/engine.py`) logic: one HELD contract per trade, next-minute fill (up to 2 minutes later, else no
trade), R17 "liq" fills (+-5 bps, half-spread 1/2/4 ticks by the contract's 5-minute volume, doubled for stops), stops
on the minute low with gap fills at the worse open, the stop wins a tie with the target, locks from the next minute,
**dated charges** (`Costs('dated')`, STT 0.15% from 1 Apr 2026), lots in force on each date, 1 lot. R64's engine
(`r64engine.py`) is the same function with one addition (a breakeven step whose level is fill + charges); a test
proves it gives R51's numbers exactly when that step is absent. Stores: Donchian and OOV on R51's Amendment A clean
stores (foreign rows and stray prints removed, `R51_CLEAN=1`); Solo 2.0 on R57's own clean stores (its model's inputs
need them).

## 4. Periods

- **Design: 20 Nov 2024 - 31 Mar 2026.** **Walk-forward: the four quarters Apr 2025 - Mar 2026** (no variant is fitted:
  every rule is fixed here, so walk-forward = each out-of-sample quarter reported on its own; the gate is their sum > 0).
  Solo 2.0 only has out-of-fold picks from Jul 2025 (R57's walk-forward), so its design = its walk-forward =
  Jul 2025 - Mar 2026 (three quarters).
- **Final: 1 Apr 2026 - the lake's last session** (13 Oct 2026; 14 Oct for NIFTY in R57's stores), read once by
  `r64study.py final` (guard `results/FINAL_READ`). **Honest note: this window was already read by R51, R52 and R57**
  (the arms were chosen on it), so it is weaker evidence than a fresh holdout. Only paper trading from 15-16 Oct is
  unseen. 16 Oct 2026 is reported if the lake has it.

## 5. Gates (as R51, never relaxed) and the decision

Per variant x arm, on the final period: net > 0; random-entry twin p < 0.05 (500 twins, seed 64); Benjamini-Hochberg
q <= 0.10 across **all 15 tests (5 variants x 3 arms)**; >= 40 trades; the top 5 trades < 100% of the net; net > 0 in
design and in the walk-forward sum.

Twins: Donchian as R51 (same days, sides and counts a day, entry rows uniform 09:20-15:10, same strike rule and exits);
OOV and Solo 2.0 as R57 (same days and indices, entry rows uniform 09:20-14:00, side by coin flip, the trade's own
strike rule and exits, S1's index exits recomputed from the twin's entry).

**Decision (per arm):** an AFTER variant is recommended over BEFORE only if it passes every gate AND its net is at least
BEFORE's in design, walk-forward and final. Otherwise BEFORE stays; an AFTER variant that lowers the max drawdown or the
worst day at a lower net is reported as a risk trade-off for Boss to choose, not as a recommendation. Information only
(not gated): a paired day bootstrap (2,000 draws, seed 64) of the AFTER - BEFORE net difference on the same days.

## 6. Reported for every arm and for the three together, per period (design, walk-forward, final)

trades, win rate, net Rs, per trade, average win, average loss, biggest loss, worst day, max drawdown (daily), % green
days, exit reasons (stops hit, locks hit, targets, time, square-off), big winners and whether they were kept or cut
(a **big mover** = a trade whose option traded +30% above the fill at any minute between entry and 15:15; **kept** =
its exit was at least +30% above the fill; **cut** = below), sample sizes (trades, days).

Every variant gets an experiment entry in `research/experiments/entries/`, failures included. The paper arms change
only if Boss chooses; nothing here changes live.
