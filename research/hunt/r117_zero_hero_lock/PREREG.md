# R117 pre-registration: profit locks on the EXISTING FT3 and Hero tickets

Written 27 Oct 2026, **before any R117 lock number was computed** (committed and pushed alone on
`claude/r117-zero-hero-lock` before the code's first run). Research only; nothing in `android/**` changes.

Boss, 27 Oct (NIFTY monthly expiry), with a 5-minute chart of NIFTY 27 OCT 22700 PE: about Rs 9-11 at 12:40-12:50, then
about Rs 60-67 (spike near 80) by 13:30-14:00, then Rs 0.05 at the close. "It went from 9 rs to 60": why did we miss it?
The phone's log: FT3 had no NIFTY ticket ("no option met the rule at any mark"); it bought FINNIFTY 24200 PE at 9.85 and
MIDCPNIFTY 13400 CE at 8.50, both sold near zero at 15:05 (about -Rs 8k). The Hero Rs 1,000 ticket has been off since
22 Oct.

**Signal source (CLAUDE.md): NOT the default.** FT3 picks its option from the option chain at the mark (the option's own
close, its Black-76 IV against the ATM's, its distance in straddles); Hero picks by the option's own price band. Both are
labelled option-price rules, on by Boss's choice, and every R117 table says so. The locks below read the held option's
own premium, which CLAUDE.md allows for stops, targets, trails and locks.

**Entries are NOT re-searched.** Only the exit changes.

## 1. Data and periods

- Lake `gdrive:IraAlgo-data-lake` (copied 27 Oct), rolling ATM+-10 1-minute options and index minutes, to 19 Oct 2026.
  27 Oct 2026 is NOT in it: the Dhan token is expired (HTTP 401 today) and the phone's 27 Oct recording is sealed with
  Boss's passphrase, which this session does not have and must not have. Section 6 says what is done instead.
- **FT3 entries:** `research/hunt/r98/ft3_app.py` unchanged (the frozen app version: marks 11:00-12:55, OTM Rs 6-10,
  IV ratio <= 1.0333, <= 1.5466 straddles, LIMIT capped at price x 1.2 + 0.10 for 3 bars, Rs 5,000, R98 charges, all five
  indices' expiry days). Parity: it must reproduce 478 filled tickets, mean +Rs 1,805 (FORWARD_TEST.md) before any lock
  runs; asserted in code.
- **Hero entries:** R55's engine unchanged (`research/hunt/r55/r55lib.py`, R54 picker, fills, stray guard, late spread,
  dated lots and charges): NIFTY and SENSEX own expiry days, the bar closing 12:30, 1 lot CE + 1 lot PE, nearest eligible
  OTM priced Rs 5 - min(30, 1,000 / lot), skipped if fill x lot > Rs 1,000. R117 runs it from the first lake expiry
  (NIFTY Aug 2021, SENSEX May 2023), not only from 20 Nov 2024. Parity: on R55's window, T0 at 15:05 must reproduce design
  248 tickets -Rs 14,507 and final 107 tickets +Rs 1,559; asserted in code.
- **Periods (both):** DESIGN = expiry days to 31 Dec 2023; WALK-FORWARD = 1 Jan 2024 - 31 Dec 2025; FINAL = 1 Jan -
  19 Oct 2026, read once. Nothing is fitted, so walk-forward means "the fixed rule, read again on later years".
  **Honest note:** no period is pristine for the ENTRIES (R98 / ft3_app read FT3 on 2021-26; R50-R55 read Hero from Nov
  2024). No lock below has been run on these tickets. The FT3 forward record from 21 Oct is too short to read (and is
  frozen).

## 2. The rules (7, fixed now; no tuning afterwards)

Decisions read the held option's 1-minute closes (each engine's own cleaned path); a decision on a bar's close sells at
the next bar's open with the engine's own fill and charges. Whatever is still held sells at the arm's own end:
FT3 at the 15:04 bar's close (as `ft3_app.py`), Hero at the 15:05 bar's open (as R55). "m" = the fill price; "best" =
the best close since the fill.

| id | rule | FT3 | Hero |
|---|---|---|---|
| L1 | **half at 3x, trail the rest 50%:** the first close >= 3m sells half; the other half sells when a close <= 0.50 x best | yes | n/a: 1 lot cannot be halved |
| L2 | **half at 2x, trail the rest 50%** (same, at 2m) | yes | n/a |
| L3 | **step lock:** once a close >= 4m the floor is 2m; >= 8m: 4m; >= 16m: 8m; >= 32m: 16m. A close <= the floor sells all. No stop | yes | yes |
| L4 | **trail 40% from the peak, armed at 2x:** once a close >= 2m, a close <= 0.60 x best sells all. No stop | yes | yes |
| L5 | **trail 50% from the peak, armed at 2x** (0.50 x best) | yes | yes |
| L6 | **time lock after 14:00:** from the bar starting 14:00, if best >= 2m, a close <= 0.70 x best sells all. Before 14:00 nothing (no stop) | yes | yes |
| L7 | **stop first, wider trail later** (R55's logged untested idea): a close <= 0.75 x best sells all (the 25% trail from the fill, = Hero T0) until a close >= 2m; from then the trail widens to 0.50 x best | yes | yes |

That is 7 FT3 tests + 5 Hero tests = **12 tests**.

**Baselines (not tests):** FT3 = hold to 15:05 (the frozen rule). Hero = T0, the 25% trail from the fill (R55, the arm's
rule). Also shown: Hero H (hold to 15:05) and FT3 with Hero's T0 trail, for reference only.

## 3. What is reported (every rule, every period, both baselines)

Signal source on every table. Tickets (n), win rate, mean net per ticket, total, % of tickets whose best close reached
3x / 5x / 10x the fill by the end (the same for every exit of a family) and % SOLD at >= 3x, worst losing run (most
tickets lost in a row, and the rupees of the worst run of consecutive losing tickets), max drawdown (cumulative ticket
P&L in time order), and the total without the 3 best days (index-days summed by calendar day). Also a day-bootstrap 95%
range of the mean (5,000 resamples, seed 117).

## 4. Gates for paper (all must hold; never relaxed)

1. Mean net per ticket above the baseline's in DESIGN, WALK-FORWARD and FINAL.
2. FINAL: mean net per ticket > 0 AND total > 0 without the 3 best days.
3. FINAL max drawdown not worse than the baseline's.
4. **Random twin**, FINAL, 2,000 twins (seed 117): every ticket whose exit differs from the baseline's is instead sold
   whole at the open of a uniformly random bar strictly after its fill and before the end; the others stay as they are.
   p = (1 + twins >= the rule's total) / 2,001; with no hit, the normal tail of the twins (R115's convention). p < 0.05.
5. **Benjamini-Hochberg q <= 0.10** with m = every family in `research/experiments/entries/` up to 27 Oct 2026 (R115's
   count: each family the larger of its entry count and its summed `variants_tried`, earlier tests taken as p = 1; this
   includes the 52 zero-hero rules R21-R26 / R34-R37 and R98's 8,280 variants) + R117's 12. Also reported, not a gate: q
   within the zero-hero families only (R21-R26, R34-R37, R39, R43, R50, R54, R55, R98 and R117).
6. At least 40 FINAL tickets.

## 5. What a pass means

FT3 is a FROZEN forward test (`research/hunt/r98/FORWARD_TEST.md`): changing its exit would restart it, so FT3 itself
never changes. A passing FT3 lock may run only as a SEPARATE, labelled paper variant beside FT3 (its own record, logged
in the Autopilot change timeline, undoable in one tap, shown to Boss). A passing Hero lock may go on the Hero paper arm
the same way, if Boss switches the arm back on. Nothing goes live from R117; R117 changes no app code. Failures stay
notes. Every test gets an entry in `research/experiments/entries/r117-*.yaml`.

## 6. 27 Oct itself

- If the 27 Oct prints become available (a renewed Dhan token, or Boss opening the recording on their own PC), `r117day.py`
  replays FT3's 24 marks for that day and prints, for each mark and for the named option, its close, distance in
  straddles and IV ratio, and which FT3 test failed; then every R117 exit on that option. Until then the write-up says
  plainly that 27 Oct could not be replayed.
- Descriptive, from 2021-26 (not a test, no P&L): how often an OTM put inside FT3's price band and distance fails the IV
  ratio, against calls (downside skew makes OTM puts dearer, R98 section 3), and how often NIFTY had no FT3 ticket.
- Boss's chart numbers (about Rs 10 at entry, best close about Rs 60-67) are used only to illustrate what each rule's
  mechanics would give, labelled as "from the chart, not data".
