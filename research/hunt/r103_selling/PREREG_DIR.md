# R103 deep, idea 2: sell a credit spread in the direction of Boss's own signals

Written 22 Oct 2026, before any spread result was computed (the signals' timing comes from existing research code; no
spread was priced). Boss (22 Oct) agreed to test idea 2 after idea 1. Research only: nothing trades.

## Why this could work

Boss's default (CLAUDE.md) is to signal on the index and then trade the option. Today a bullish signal buys a call: it
wins only if the index moves enough, soon enough, to beat the premium's decay. Selling a put spread below the market on
the same signal wins if the index rises, stays flat, or falls a little. The win rate should rise by design, which is
Boss's stated goal, and decay works for the trade instead of against it. The signal's edge, if any, is kept; the risk is
capped by the long leg.

**Signal source:** the index (underlying), exactly as each arm computes it. Option prices set the strikes, the stop and
the lock only.

## Signals (unchanged research code; timing only)

- **LIQ-BN:** Liquidity 15+5 on BANKNIFTY, the live arm (`research/hunt/r51/fam_liq.signals("BANKNIFTY", tf, 1.0)` for tf
  15 and 5). Entry at the open of row `er`. Expiry days skipped (as the arm does).
- **DON-NIFTY, DON-BN:** Donchian 20 on 5-minute index bars, the paper arm's base rule
  (`research/hunt/r51/fam_r44.signal_list(und, "donchian20")`). Entry at the open of minute 5 x (bar + 1). Expiry days
  skipped.
- One position per signal source at a time: a signal that arrives while the previous spread is open is skipped. Entries
  09:20-14:00 only.

## The spread (fixed)

A bullish signal sells a put spread; a bearish one sells a call spread. K0 = the strike nearest the index at the decision
minute (the minute before the entry fill). Short strike at **ATM** (K0) or **OTM2** (2 strikes out of the money); the long
leg 4 strikes further out (NIFTY 200 points, BANKNIFTY 400). Both legs must have traded in the last 3 minutes.

**Exits** (one of):
- **E1:** combined stop at 100% of the credit; else square-off at 15:10;
- **E2:** E1 plus the 30/20 profit lock;
- **E3:** E1, but close when the arm itself would exit: LIQ-BN at the arm's index exit row (`fam_liq.forced_row`: index
  stop, next-level target, failed break), DON at 30 minutes (the arm's time stop).

Charges, slippage and the model for legs without a print as PREREG.md. One lot.

## Cells and gates

3 signal sources x 2 placements x 3 exits = **18 cells**, one BH family. Design: 20 Nov 2024 - 14 Jul 2026 (the stores
start trading on 20 Nov 2024, so there is no earlier period); four walk-forward blocks.

A cell passes only if: at least 40 trades; net > 0 with sign-flip p <= 0.05 and BH q <= 0.10; **a random-direction twin**
(the same trades with the side flipped at random, 500 draws, seed 103) has p <= 0.05, i.e. the signal's direction
matters; at least 3 of 4 blocks positive; max drawdown no worse than -Rs 40,000 and no month below -Rs 13,000.

**Holdout:** 15 Jul - 19 Oct 2026 (the stores end on 13 Oct 2026), read once for passers only. It is partly seen: the
arms' option-BUYING results were read on it (R51 final, R80). A pass only promotes a cell to the forward test
(PREREG_FWD.md section 3 rules, 60 forward signals), never to paper.

**Comparison:** the arms' published option-buying results (R51 / R80, their own cost model) are quoted beside the
spreads, labelled as such; they are not re-run here.
