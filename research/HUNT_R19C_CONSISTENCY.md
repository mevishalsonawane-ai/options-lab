# R19c: consistency, not total P&L (Boss's "+Rs 1,000, then Rs 500 below the best" floor, and day rules)

Written 13 Oct 2026 for Boss. The pre-registration is `research/hunt/r19c/PREREG.md`. It was committed and pushed on its
own (c65bfb5) before any test ran. The simulator, tests and design results were committed (80ae8ae) before the holdout
was read. The holdout was read once, guarded by a flag file in the cache (13 Oct 2026 09:02 UTC).

- Code: `research/hunt/r19c/` (`sim.py`, `run.py`, `posthoc.py`, `test_r19c.py`). It reuses R19b's engine-exact Liquidity
  walk and R17's arm signals, contracts, fills and charges.
- Results: `r19c/results/design.md` / `.json`, `holdout.md` / `.json`, `posthoc.md` (descriptive, after the read).
- Experiment entry: `research/experiments/entries/r19c.yaml`.
- Raw data stays out of git. The Dhan API was not called.

## Verdict (read this first)

**No strategy can promise "no losing days". The realistic goal is small, capped losses and more green days.** On that
goal, the honest answer from two years of data is:

1. **Boss's floor FAILED on every arm** (54 arm x variant hypotheses, none passes). It does what it promises on single
   trades: more green days, and almost no trade that "was in profit and ended in loss". But it gives up far more than the
   30% of net that Boss accepted. It does not shrink the worst day, and random early exits did as well. Holdout (1 Apr
   2025 onward), Boss's exact rule against plain rules:

   | arm | trades | green days | net | worst day | max drawdown | in profit -> ended in loss | beat random exits |
   |---|---|---|---|---|---|---|---|
   | Liquidity BANKNIFTY 15m | 51 | 37% -> 47% | +Rs 23,373 -> -Rs 6,911 | -Rs 5,294 -> same | -Rs 14,552 -> -Rs 11,144 | 6 -> 1 | 0% |
   | Liquidity BANKNIFTY 5m | 170 | 40% -> 50% | +Rs 27,342 -> +Rs 6,675 | -Rs 4,183 -> same | -Rs 10,806 -> -Rs 13,371 | 19 -> 2 | 0% |
   | Solo (midday) | 75 | 52% -> 80% | +Rs 42,457 -> +Rs 18,451 | -Rs 7,154 -> same | -Rs 13,864 -> -Rs 13,469 | 21 -> 0 | 3% |
   | VIX divergence BANKNIFTY | 37 | 32% -> 68% | -Rs 11,232 -> +Rs 806 | -Rs 8,700 -> same | -Rs 29,298 -> -Rs 18,352 | 13 -> 0 | 70% |
   | VIX divergence NIFTY | 33 | 27% -> 42% | +Rs 6,570 -> -Rs 6,241 | -Rs 2,203 -> -Rs 2,130 | -Rs 12,747 -> -Rs 6,836 | 5 -> 0 | 0% |
   | Hero (NIFTY expiry) | 10 | 10% -> 10% | -Rs 19,576 -> -Rs 9,542 | -Rs 3,353 -> same | -Rs 19,576 -> -Rs 18,251 | 0 -> 0 | 89% (too few trades) |

   VIX divergence BANKNIFTY came closest: the floor turned a losing holdout year to about breakeven, passed gates 1 and
   2 there, and beat 70% of random twins. It failed the net gate in the design year (+Rs 27,513 -> +Rs 14,925, more
   than 30% given up), and 70% is short of the 95% bar. The sensitivity variants (+Rs 750 / +Rs 1,500 with a Rs 500
   give-back; +Rs 1,000 with Rs 300 / Rs 700) tell the same story. Each one fails.

2. **ORB, ORB Fresh, ORB Sweep, Range Fade: their ladder already does this job, earlier.** At lot 30, ORB's whole +40
   target is Rs 1,200 a lot (only Rs 600 at lot 15), so the ladder locks first. On ORB, ORB Fresh and Range Fade, Boss's
   rule on top of the ladder fires on 0-33 holdout trades and moves the net by Rs 3k at most. ORB Sweep's +80 target
   leaves room, so there it fires on 48-142 trades, and the arm still loses (-Rs 82k to -Rs 93k against -Rs 86k). Using
   Boss's rule INSTEAD of the ladder is worse: ORB and ORB Fresh -Rs 18,026 against -Rs 10,958 (holdout, 207 trades);
   Range Fade -Rs 99,264 against -Rs 90,301.

3. **The real leak is the ORB family itself.** All four lost in BOTH years, on plain rules:

   | arm (1 lot) | design year (to Mar 2025) | holdout year (from Apr 2025) |
   |---|---|---|
   | ORB | -Rs 15,868 (267 trades) | -Rs 10,958 (207) |
   | ORB Fresh (the same trades as ORB at one a side) | -Rs 15,868 (267) | -Rs 10,958 (207) |
   | ORB Sweep | -Rs 35,151 (386) | -Rs 85,997 (291) |
   | Range Fade | -Rs 42,414 (411) | -Rs 90,301 (326) |
   | Liquidity BANKNIFTY 15m | +Rs 33,863 (68) | +Rs 23,373 (51) |
   | Liquidity BANKNIFTY 5m | +Rs 43,379 (178) | +Rs 27,342 (170) |
   | VIX divergence BANKNIFTY | +Rs 27,513 (52) | -Rs 11,232 (37) |
   | VIX divergence NIFTY | +Rs 12,233 (49) | +Rs 6,570 (33) |
   | Solo (midday) | -Rs 175 (63) | +Rs 42,457 (75) |
   | Hero (NIFTY expiry) | no expiry-day chain in the data | -Rs 19,576 (10) |

   **Switching the four off (P1)** changes the portfolio (1 lot each arm) like this:

   | | design: everything | design: ORB family off | holdout: everything | holdout: ORB family off |
   |---|---|---|---|---|
   | days traded | 272 | 197 | 222 | 177 |
   | green days | 40% | 43% | 42% | 44% |
   | net | +Rs 7,512 | **+Rs 116,813** | -Rs 129,279 | **+Rs 68,935** |
   | worst day | -Rs 10,136 | -Rs 8,188 | -Rs 13,384 | -Rs 11,951 |
   | max drawdown | -Rs 85,018 | **-Rs 23,585** | -Rs 171,019 | **-Rs 35,221** |
   | green months | 7 of 14 | 10 of 14 | 4 of 12 | 8 of 12 |
   | months below -Rs 13k (Boss's stop rule) | 3 | 0 | 5 | 1 |

   This passed gates 1 (daily) and 2 (net) in both years. It **failed the pre-registered sign test** (q = 1.00), so
   by the letter of the pre-registration it is not a pass. The test counts a day as "turned red" when its only trades were
   ORB-family winners and it now has no trade: 36 such days against 20 that turned green. That is a flaw in how I set up
   the test for an "arm off" rule, found after the read. I report it as it fell; I do not re-score it. The net and drawdown
   case for switching them off is the same as R17's, PROFIT_LOCK.md's and the app's own policy check ("lost in both
   tested years").

4. **Day rules for the whole account:**
   - **Daily loss cap (no new entries after -Rs 2,000 / -Rs 3,000 on the day): FAILED, and it made things worse.**
     It blocked the afternoon trades that recover a red morning: green days 42% -> 36% / 39% (holdout, 222 days), net
     -Rs 1.29L -> -Rs 1.32L / -Rs 1.34L. The worst day did improve (-Rs 13,384 -> -Rs 11,454). On top of the ORB family
     switched off (P6): green 44% -> 43%, net +Rs 68,935 -> +Rs 55,277.
   - **Day profit lock (after +Rs 2,000 on the day, protect +Rs 1,000): FAILED its gates**, but it is the one that
     points the right way. On everything (P4): green days 42% -> 49% (holdout), 16 days turned green and 2 turned red
     (p 0.001). But the net fell (-Rs 1.29L -> -Rs 1.42L), the design-year drawdown got worse, and it beat only 6% of
     random "sell everything at a random minute" twins. On top of the ORB family switched off (P5): green 44% -> 53%,
     net +Rs 68,935 -> +Rs 72,392, max drawdown -Rs 35,221 -> -Rs 29,254, worst month -Rs 12,154 (holdout). Against P1
     that is 16 days turned green and 0 turned red. **That comparison is post hoc.** The pre-registered comparison was
     against P0 and the twin check (62%), and it failed both. It is the lead idea for the next pre-registered test, on
     fresh data.

**Recommendation for consistency (Boss decides; nothing below is switched on by this study):**

1. **Switch off ORB, ORB Fresh, ORB Sweep and Range Fade on paper.** This one change did more for green months, the
   drawdown and the -Rs 13k/month rule than every exit rule tested here. Switching an arm off only lowers risk.
2. **Keep Liquidity BANKNIFTY (15m and 5m), Solo and VIX divergence NIFTY on their plain rules.** They are the profit
   sources in both years, or the latest year. Watch VIX divergence BANKNIFTY (it lost in the holdout year, -Rs 11,232
   on 37 trades) and Hero (-Rs 19,576 on 10 trades; too few to judge).
3. **Do not add the +Rs 1,000 / Rs 500 floor as a rule.** It turns a few big winners into many Rs 1,000-2,000 wins. It
   leaves the losers untouched, and the losers are what make the red days. If you want to feel it on paper anyway, the
   Liquidity "your choice" switch now uses your exact rule (below).
4. **The day profit lock (+Rs 2,000 -> keep +Rs 1,000) on top of (1) is the next test** (R19d, pre-registered on the
   Dhan lake's fresh years when it is published). It is not switched on now.
5. Plainly: even the best combination here had red days on about half its trading days (P5: 53% green in the holdout)
   and a worst day of about -Rs 12k at 1 lot of each arm. What improves is how deep the bad stretches go: the drawdown
   fell from -Rs 1.71L to about -Rs 29k-35k.

**MIDCPNIFTY was not tested.** The `research-data` release has BANKNIFTY and NIFTY option minutes only. The IraAlgo
data repository's catalogue is empty (no phone uploads yet), and no Drive lake is reachable from here. It will be re-run
when the Dhan lake lands. **MCX arms: not tested** (no MCX option or futures minutes in either place). **Night (R3):
not tested.** Its breadth (all F&O stocks) and OI build-up inputs are not in the release. It also holds 15:20 to 09:16,
so a floor would have about 11 open minutes to act. **Pine auto-trade: not tested.** Every Pine strategy already
carries a compulsory profit lock (06 Oct).

## Why Boss's rule fails (plain words)

Boss is right about the symptom. On red days the portfolio's 3 worst trades alone lose more than the whole day: 90 of
128 red days with everything on, 95 of 99 with the ORB family off (holdout; `posthoc.md`). But the floor only acts on
trades that first got to +Rs 1,000 a lot. The trades that make a red day mostly never got there: they went straight to
their stop. So the floor cannot touch them: under Boss's exact rule the worst day was unchanged on 5 of the 6 arms (holdout). What it does touch is the
winners. Rs 500 a lot is only 14-17 BANKNIFTY option points, or 7-8 NIFTY points at lot 65-75: an ordinary minute's
wobble. So it sells most winners on their first pullback, at about +Rs 1,000-2,000. These arms make their money from a
few trades that run far (Liquidity's next level, Solo's 14:30 hold). Cap those and the account's sum falls, even though
more days and trades end green.

## Boss's rule on today's 13:20 trade (an illustration only, never a test)

MIDCPNIFTY 5m PE, entry 13:20 @ 195.45, lot 120. At lot 120, Rs 1,000 a lot is 8.33 points (activates at 203.78) and
Rs 500 is 4.17 points. It peaked at about 216.3 and was sold at 13:29 @ 188.25 on the index stop. The minute prices are
not on hand, so this assumes a steady fall from the peak and a fill at the trigger (before charges):

| variant | rule | exit | P&L a lot |
|---|---|---|---|
| plain rules (what happened) | index stop | 188.25 | -Rs 864 |
| **F1000_500 (Boss's rule)** | from +Rs 1,000, Rs 500 below the best | 212.15 | **+Rs 2,004** |
| F750_500 / F1500_500 | from +Rs 750 / +Rs 1,500, Rs 500 below | 212.15 | +Rs 2,004 |
| F1000_300 | from +Rs 1,000, Rs 300 below | 213.80 | +Rs 2,202 |
| F1000_700 | from +Rs 1,000, Rs 700 below | 210.50 | +Rs 1,806 |

On this one trade the floor would have turned -Rs 864 into about +Rs 2,000. Over the two years, the same rule gave back
more on the trades that would have run than it saved on trades like this one.

## Rs 1,000 a lot in option points

| index | lot | Rs 1,000 | Rs 500 |
|---|---|---|---|
| BANKNIFTY | 15 (Feb - Nov 2024) | 66.7 | 33.3 |
| BANKNIFTY | 30 (Nov 2024 - Jun 2025; the app's lot today) | 33.3 | 16.7 |
| BANKNIFTY | 35 (Jun 2025 - Feb 2026 in the release) | 28.6 | 14.3 |
| NIFTY | 25 (Apr - Nov 2024) | 40.0 | 20.0 |
| NIFTY | 75 (Nov 2024 - Dec 2025) | 13.3 | 6.7 |
| NIFTY | 65 (from 31 Dec 2025) | 15.4 | 7.7 |
| MIDCPNIFTY | 120 | 8.3 | 4.2 |

## Data and setup

| | |
|---|---|
| source | `research-data` release: BANKNIFTY 13 Feb 2024 - 23 Feb 2026 (498 sessions), NIFTY 1 Apr 2024 - 13 Apr 2026 (499), nearest-expiry chains, NIFTY expiry-day chains from 2025 (Hero) |
| design / holdout | one cut for everything: design before 1 Apr 2025, holdout from 1 Apr 2025 (read once) |
| arms | Liquidity 15m / 5m (R19b's engine-exact walk); ORB, ORB Fresh, ORB Sweep, Range Fade, VIX divergence (BANKNIFTY, NIFTY), Hero (R17's signals, contracts, fills); Solo (ported here from engine `SoloMidday`, on NIFTY and BANKNIFTY: FINNIFTY has no option minutes, and the Liquidity "one index one side" conflict is not modelled). 1 lot each; Hero Rs 5,000 |
| the floor | gross open P&L per lot from the option's 1-minute highs, counted from the next minute; a resting stop at best - G / lot on the tick rounded up, raised only; filled like each simulator's resting stops (at the trigger or the open on a gap, less the stop slippage). On the ORB family it rides on top of the ladder (the higher stop wins), or replaces it (R1000_500) |
| day rules | checked on each minute's close with open trades marked at the close less charges; a sell-all sells at the next open (market fill) |
| charges | the app's model at today's rates; each reused simulator's own fills (R19b: +-5 bps + 1-2 ticks; R17: +-5 bps + 1-4 ticks by volume) |
| gates | 1 green days up, worst day and drawdown not worse; 2 net > 0 and >= 70% of plain; both in design AND holdout. 3 sign test on days that changed colour, BH q <= 0.10 over 61 hypotheses. 4 beat 95% of 1,000 random-early-exit twins. At least 30 holdout trades |

Honest notes. No window of the release is unseen: R19b read Liquidity Mar-Aug 2025, and R12, R14, R16 and R17 read
Aug 2025 on. R19c's rules were fixed by Boss's words and the pre-registration, not chosen on any window. The R17
simulators skip their expected-range warm-up here, so the trade counts differ slightly from R17's. One approximation: a day
rule's "sell everything" and a random twin price Hero's sale on its whole size, even after its half at 5x (10 trades;
Hero is inconclusive either way).

## Full tables

`r19c/results/design.md` (design) and `r19c/results/holdout.md` (holdout, with p, q, the twin share and the gate flags
for all 61 hypotheses).

## In the app (13 Oct)

Nothing passed, so nothing acts by itself. No arm's rules change, and live is untouched.

- Liquidity's opt-in "your choice" PAPER switch (R19b) now uses Boss's exact rule: from +Rs 1,000 a lot, sell Rs 500
  below the best, so a trailed trade never keeps less than +Rs 500 a lot. It is still **off by default**, labelled
  "failed research, your choice", and shown with R19c's numbers. It is paper only (never live), and its trades stay
  out of every plain-rules record.
  - Each new paper entry stores the activation it entered with (`trailFrom`). A trade entered under R19b's first rule
    (from +Rs 500) keeps that rule.
  - Code: engine `LiquidityTrail.ACTIVATE_RS` / `level(..., activateRs)` / `forEntryFrom`; app `OrbArms.Position.trailFrom`;
    ira-core `LiquidityFreeze` words.
- Not built: a floor for Solo, VIX divergence or Hero, a day loss cap, or a day profit lock. All failed their gates.
  Switching off the ORB family is Boss's own decision. The app's policy check already flags those four as "lost in
  both tested years".
