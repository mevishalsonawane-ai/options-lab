# R135 pre-registration: the ICT / SMC books' claims, measured on NSE index minutes

Written 29 Oct 2026, before any R135 number. Research only. It follows `research/ICT_BOOKS_STUDY.md` section 4 step 1:
measure which of the books' ideas carry information on Indian indices **before** adding them to the network (R136) or
building a strategy (R137). Descriptive tests only: no trades, no options, no costs.

## Data and common definitions

- **Data:** NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX index minutes.
  - Period: the **design period only**, 1 Aug 2021 - 15 Mar 2026.
  - The holdout (Apr - Oct 2026) is not used; R131-R133 already saw it.
- **Wicks** for every level, sweep and gap. Bars of 5 or 15 minutes are aligned to 09:15 (R131's `make_bars`).
- **The race.** From an event's evaluation price (a bar's close), which comes first by the session's end:
  - a move of **d = 0.25% of price** in the predicted direction ("win"), or
  - a move of d against it ("loss").
  - Neither by 15:29 = undecided (counted, left out of the rate).
  - On minute wicks; if both happen in one minute, count it as a loss.
- **The control.** For each event, 20 draws at the **same minute** on other sessions of the same index (in the design
  period), racing in the same direction with the same d. The control win rate is the expected rate for that time of
  day.
- **Tests:**
  - an event rate against its control: two-proportion z, two-sided;
  - two event groups against each other: two-proportion z.
  - BH at q 0.10 over the **12 primary tests** marked [P] below.
  - n is reported with every rate.

## The claims (each from the books; ICT_BOOKS_STUDY.md section 1)

**C1. A sweep of major liquidity reverses** (Algorithm Theory liquidity tiers; all three books).
- **Levels:**
  - PDH/PDL = the previous session's high and low;
  - PWH/PWL = the previous calendar week's high and low;
  - PMH/PML = the previous calendar month's high and low.
- **A sweep of a high level L** on day d: the first 5-minute bar of the day whose high ≥ L + 0.05. The day must open
  below L (no gap through it). Low levels mirror this.
- **Displacement (the algo candle):** the sweep bar t is the first candle of a three-bar gap against the sweep:
  - high sweep: low[t] > high[t+2] by ≥ max(2 ticks, 0.01%);
  - low sweep: the mirror.
- **Evaluation:** every sweep is evaluated at the close of bar t+2 (so displacement is known), predicting a reversal
  (down after a high sweep).
- **Tests [P]:**
  - for each tier (day, week, month): sweep with displacement vs control, and sweep without displacement vs control
    (6 tests);
  - also reported: displaced vs not, within each tier.

**C2. The opening range is swept, then price reverses** (the NSE stand-in for "Asia range swept at London open").
- OR = the 09:15-09:44 high and low.
- **Event:** the first 5-minute bar from 09:45 to 10:59 whose wick goes ≥ 1 tick beyond either OR extreme, evaluated
  at its close, predicting a reversal.
- **Tests:**
  - [P] the reversal rate vs control;
  - reported: how often the opposite OR extreme is reached by the close.
- **[P] When the day's extreme forms.** The share of sessions whose high **or** low is made in 09:15-10:29, against
  the same share in 200 shuffles of that session's 1-minute returns (random order, same path length).

**C3. The weekly cycle** (Algorithm Theory: the week's high or low is often made on Monday/Tuesday).
- **[P]** The share of full 5-session weeks whose high or low is made in the first two sessions, against 200
  shuffles of the order of that week's daily bars (each day's high and low taken relative to its open, then chained).

**C4. Only the break of a strong swing is real** (Algorithm Theory, strong vs weak).
- On 15-minute bars with pivots of 3 bars each side (wicks), today and the previous 4 sessions.
- A **swing high is strong** when both hold:
  - its bar's high swept (≥ 1 tick) an earlier swing high that no high had exceeded since it formed;
  - before any later high exceeds it, a bar closes below the last swing low formed before it (it broke structure).
- Every other swing high is **weak**. Swing lows mirror this.
- **Event:** the first 15-minute close above a known swing high (or below a swing low), predicting continuation.
- **Test [P]:** the continuation rate when the broken swing is strong vs when it is weak.

**C5. A valid pullback retraces past 50% of the leg** (Algorithm Theory, Mati, the booklet).
- On 15-minute pivots: an up-leg runs from swing low A to the next swing high B. The next swing low C has A < C < B.
- r = (B - C) / (B - A).
- **Outcome:** after C is confirmed, which comes first:
  - a high above B (C held: valid), or
  - a low ≤ C - 1 tick (C taken: "just liquidity").
- Down-legs mirror this.
- **Test [P]:** the share held when r ≥ 0.5 vs r < 0.5.

**C6. A fully filled FVG reverses** ("fill > reverse", Algorithm Theory; Mati's 50% fill).
- 15-minute FVGs as R131 defines them.
- **Reported:** the share that are 50% filled within the session, and within 5 sessions.
- **Event:** the first bar that trades through the gap's far edge, evaluated at its close, predicting a reversal (up
  after a bullish gap is fully filled from above).
- **Test [P]:** its rate vs control.

**C7. The 20-day turn after a major sweep** (Algorithm Theory). Descriptive only (few events): after a PMH/PML sweep
with daily displacement, the direction of the next 20 sessions' net move, with n.

## Outputs

- `research/hunt/r135_ict_facts/` (`r135.py`, `test_r135.py`); `results/facts.json`.
- `research/HUNT_R135_ICT_FACTS.md`: one plain table of every claim, with n, its rate, the control's rate, p and q.
- One entry `research/experiments/entries/r135-ict-facts.yaml` (verdict `not-a-test`: descriptive).
