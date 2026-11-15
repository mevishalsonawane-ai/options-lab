# R178 pre-registration: can the K1 skip be improved? (select on the year before, test on the last year)

Written 15 Nov 2026, **before any R178 number was computed**; committed on `claude/r178-k1-enhance` before the code's first
run on data. Research only: nothing in `android/**` changes. Owner: Boss (they/them).

Boss, 15 Nov 2026: *"Can we enhance k1 skip more"*. K1 (R177): skip a call when, in the 30 minutes before the signal,
the index closed above yesterday's value-area high and then back at or below it (puts mirrored with the value-area low).
On the last year it lifted Liquidity 15+5 from Rs 1,17,923 to Rs 1,39,609 at 1 lot (twin p 0.014; failed BH).

**Signal source: the traded index** (yesterday's TPO profile / high / low and today's 1-minute closes). Entries unchanged;
every variant only skips.

## 1. Why a separate selection window

K1 was found on the last year. Tuning it on that same year would overstate any gain. So variants are compared on the
**selection window: 20 Nov 2024 - 19 Oct 2025** (never read by R177), and only the chosen variant per arm is then tested on
the **test year: 20 Oct 2025 - 19 Oct 2026**. Honesty: the test year already produced K1, so a K1-like winner there is
weak confirmation; and 20 Nov 2024 - 31 Mar 2025 / Apr - Oct 2025 were read by R51-R173 for other rules (not for K1).

## 2. Variants (fixed now)

For a call (put): level = VAH (VAL) unless stated; "failed break" = a 1-minute close beyond the level followed by a later
close back at or inside it, both before the signal row (r177lib.failed_break).
- **K1**: failed VA break in the 30 rows before the signal (R177's K1).
- **K2**: the same, 15 rows.
- **K3**: the same, 60 rows.
- **K4**: the same, any row earlier today (from 09:15).
- **K5**: failed break of yesterday's HIGH (calls) / LOW (puts), 30 rows.
- **K6**: K1 or K5.
A skipped candidate is removed before the walk (it opens nothing). No previous-session levels: no skip.

## 3. Selection rule (fixed now; per arm, 1 lot, today's exits)

On the selection window, split into H1 = 20 Nov 2024 - 30 Apr 2025 and H2 = 1 May - 19 Oct 2025:
- eligible = the variant's net is above A's in H1 AND in H2;
- chosen = the eligible variant with the largest whole-window net gain over A (ties: the earlier in the list);
- no eligible variant: nothing is chosen for that arm, and no test is run for it.

## 4. Test on the last year (as R177 section 5, 1 lot)

For each arm's chosen variant: net > 0 in early / final / last 6 months / year; net above A's in early, final and the
year; selection twin (drop as many of A's trades at random, 500, seed 178) BH q <= 0.10; >= 40 final trades; the account
stop rule (A already breaks it). BH **m = 4,012 + 18 (selection comparisons) + up to 3 tests = up to 4,033**.
Lots 2-3 and the R151 picker (R72 / R51) are reported, not gated.

## 5. Engine, costs, data

R176 / R177's: R106 simulator and walk, R17 fills, dated Zerodha charges, the stores built to 19 Oct 2026 for both windows
(R176_POST=1). Skips do not change any trade's price, so every variant is today's priced candidates with a skip mask.
Parity on the test year's final period as R177 (LIQ 357 / Rs 11,155; R72 1,200 / Rs 1,70,336; R51 1,668 / Rs 1,31,546).

## 6. Tests

pytest (synthetic): each variant's predicate (windows 15 / 30 / 60 / whole day; PDH / PDL; the OR), the selection rule
(both halves, ties, none eligible), the mask walk equals removing candidates. Look-ahead guard: a decision is unchanged when
every bar from the signal row on is replaced by noise.
