# R98b pre-registration: exits that keep the hero, confirmation entries, and two more patterns

Written 21 Oct 2026, before any number below was computed. Boss: "enhance or find more things about the hero zero and
try to resolve it, and catch all 85000 rs" (the 13 Oct 22650 CE: +Rs 84,357 held, +Rs 1,378 with the +30% lock).

Why a new pre-registration: R98's tickets, their stop / target / lock exits and the 2023-26 model tests have all been
looked at. The rules below are NEW (never computed on any year). To keep the answer honest:

- **Design** = expiries in 2021-2023; **holdout** = expiries in 2024-2026 (to 19 Oct 2026, incl. 13 Oct).
- Every choice is made on design only. The holdout is read once for the rules chosen.
- The holdout is not virgin for the ticket universe, because R98 reported pooled results on it. It is virgin for these
  exits and entries.

## 1. Tickets (fixed)

- **U-A, every ticket.** R98's 394,956 tickets: every cheap OTM option (Rs 1-10, traded in the last 3 minutes), every 5
  minutes 11:00-14:55, entered at the next open + 1 tick.
- **U-B, daily basket (no prediction).** At 13:30 and at 14:30, on every index expiry: the OTM call and the OTM put
  priced nearest Rs 5 within Rs 2-10 (traded in the last 3 minutes). Up to 4 tickets an index-day.
- **U-C, confirmation entry.**
  - Trigger: from 13:15 to 14:45, the first minute the index close is ≥ k × EM13 from its 13:00 close, where EM13 is
    the ATM straddle at 13:00 and k = 1.0 (U-C1) or 1.5 (U-C15).
  - Buy: the OTM option in that direction priced nearest Rs 5 within Rs 2-10 at that minute.
  - At most 1 per index-day.
- **Money:** Rs 5,000 tickets with R98's charges.
  - Every sell order costs Rs 20 + GST, STT 0.15%, exchange fees, and 1 tick of spread.
  - A part sale is its own order.
  - No order for a leg worth under Rs 0.10.

## 2. Exits (fixed; 26 new + R98's baselines for reference)

**Fill rules (the app's `ProfitLock`):**
- The peak is the minute highs before the current minute.
- A resting sell stop fills at its level, or at the open if the market gaps below it.
- A target (or a part sale) fills at its level once a minute CLOSE confirms it.
- The index trail sells at the option's close in the minute the index condition is met (that close if printed, else
  the next printed close).
- Not out by 15:20: the last close (worthless under Rs 0.10).
- The initial stop is s ∈ {none, −50%}: a resting stop at 0.5 × entry on the whole position until a rule replaces it.

**The exits:**
- **E1 free roll (4):** sell half at 2× or 3×; the rest is held to 15:20 with no stop. s ∈ {none, −50%} (until the
  half is sold).
- **E2 give-back trail armed late (12):** from the minute after the peak first reaches A × entry (A ∈ {2, 3, 5}), stop
  at entry + g × (peak − entry), g ∈ {0.3, 0.5}. s ∈ {none, −50%} before.
- **E3 index trail (4):** from the minute after the option's peak first reaches 2× entry, sell when the index close has
  retraced ≥ R × EM_entry (R ∈ {0.5, 1.0}) from its best close in the option's direction since entry. EM_entry is the
  ATM straddle at the decision minute. s ∈ {none, −50%} before.
- **E4 time stop (4):** at 30 or 60 minutes after the entry, sell if the option is below its entry price (the last close
  at or before then). Alone (s = none), or with E1 (half at 2×, no stop).
- **E1+E2 (2):** half at 2×; the rest gets the give-back trail g = 0.5 armed at 5×. s ∈ {none, −50%}.

## 3. Selection and the test (fixed)

- **The selection:** on DESIGN U-A, the exit with the highest mean net per ticket. Also, separately, the exit with the
  highest mean net on DESIGN U-B and on DESIGN U-C1.
- **On the holdout, for each selected (universe, exit):**
  - mean net per ticket, win rate;
  - mean without the 5 best days;
  - years > 0;
  - a bootstrap 95% interval by day (2,000 draws, seed 98).
- **It "resolves" hero-zero only if, on the holdout, all of these hold:**
  - mean net > 0;
  - the bootstrap lower bound > 0;
  - still > 0 without the 5 best days;
  - every holdout year > 0.
- **Reported for every exit:**
  - the holdout table of all 26 (the selection is the design's, not the holdout's best);
  - what each exit makes on the 13 Oct 22650 CE ticket bought at 13:30.

## 4. Two descriptive checks (no rule chosen from them)

- **D1 clustering:** the 13:00-to-15:20 hero rate on an expiry, split by whether the most recent earlier expiry of ANY
  index (within 3 calendar days) had a 13:00-to-15:20 hero, and by that expiry's best one-way move in straddles.
- **D2 morning activity against the priced move:** the hero rate after 13:00, split by the 09:15-13:00 high-low range
  divided by the 13:00 ATM straddle.
- **D3 continuation (with U-C):** once the index has moved k straddles from 13:00, how often it goes on to 2.5 straddles
  the same way by 15:20, against the unconditional rate from 13:00.
