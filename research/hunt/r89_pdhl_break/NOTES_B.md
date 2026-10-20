# R89B notes and amendments (dated, each before the numbers it affects)

## A1 (20 Oct 2026, after the design run, BEFORE the holdout read and before any after-trigger twin was computed)

**What the design showed:** 0 of 45 cells passed. Every 15m / 5m RUN cell had twin p 0.75-1.00, with the twins'
mean strongly POSITIVE (pooled B2 twins +Rs 8.10 lakh against the rule's -Rs 5.57 lakh).

**Diagnosis (a flaw in the pre-registered twin, not in the rule code):** a twin keeps the trade's SIDE and DAY and enters
at a random minute anywhere in 09:16-14:45. On a day the index broke its PDH upward, a random CALL entered before the
break already "knows" the day's direction. With 1-minute exits (R89) this hindsight was small; with ~70-minute RUN
holds and a trailing stop it dominates (hand check, NIFTY B2, 1,200 twin draws: +14.7 index points a twin, +Rs 342 a
twin). The bias makes twins look better, so it can only hide an edge, never create a false pass.

**Added (descriptive, and for carried leads only):**
1. An **after-trigger twin**: the same day, side, exit rule and initial risk, but the entry minute uniform between the
   trade's own trigger minute and 14:45 (no hindsight about the break: the twin enters only once the rule itself knew
   the side). 500 per cell, seed 89 + 2. Computed for all 45 design cells and all 45 holdout cells.
2. **Carry rule (mechanical):** a design cell that passes every NON-twin gate (G1 >= 40 trades, G2 net > 0, G5 >= 3 of 4
   folds, G6 positive without the top 5, G7 worst month >= -Rs 13,000) is carried to the holdout as an AMENDED LEAD.
   From the design table that is FINNIFTY B7 only (FINNIFTY B6 fails G6; NIFTY B8 fails G5 and G6).
3. An amended lead is a paper candidate only if the holdout passes PREREG_B section 5 with the after-trigger twin in
   place of the original twin (p < 0.10), AND the original twin is reported beside it. Because the lead was chosen after
   seeing design results, it also needs the forward check on new sessions before any paper use, whatever the holdout
   says. The pre-registered verdict (0 of 45) is unchanged.
