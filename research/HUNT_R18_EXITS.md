# R18: Better exits for every arm (TODO I9)

Written 13 Oct 2026; corrected the same day after a code review (see "Corrections" below). Renumbered from R17 to R18
(`research/hunt/r17` is the pre-trade checks study). Boss (TODO I9): "Better exits: test exits by VWAP band, the next
support/resistance level, an order-flow turn, and the expected range. Measure how much of the profit each one keeps."
Pre-registration: `research/hunt/r18/PREREG.md` (first committed as `hunt/r17/PREREG.md` in c675807, pushed before any
variant was simulated; amendment 1 added after the review). Code: `research/hunt/r18/` (`data.py`, `lib.py`, `arms.py`,
`liquidity.py`, `run.py`, `holdout.py`, `report.py`, tests in `test_r18.py`). Results: `r18/results/design_release.json`,
`hold_release.json` (summaries only; no raw data in git). Experiment log: `research/experiments/entries/r18.yaml`.

## Verdict (read this first)

**None of these five early-exit overlays helps any arm. The cause of the low capture is not established (entries or
exits). Today's exits stay everywhere. Nothing in the app changes, and no shadow line or switch was added** (the shadow
line was to be built only for a clear pass).

- 60 pre-registered tests (12 arm books x 5 variants), one Benjamini-Hochberg family. The best design q was **0.48**
  (needed < 0.10). **0 design candidates**, so the holdout had nothing to confirm; it is shown below as description only.
- **The holdout is a reused one.** It is R16's holdout (the last ~6 months of each data set), and R16 had already read
  those sessions before this study began. So it is not a clean out-of-sample test: R16 evaluated its expected-range formula (used here by
  V2 and V4) on those sessions, and R16's results shaped which exits this study tried, so a good holdout number here is
  weaker evidence than a fresh one. Every holdout number in this write-up is descriptive. The planned lake re-run
  gets a fresh holdout that no one has looked at (amendment 1; "Data and method").
- With today's exits the losing arms keep about **0% of the best price the option reached** after the entry (ORB -0.4%,
  Pine -0.1% to -3.0%, Sweep / Range Fade -5%; n = 320-1,173 design trades each). That can mean the entries have no
  edge, or that the exits give back moves the entries did find; this study cannot tell the two apart. What it does show is
  narrower: none of these five overlays, which can only exit earlier or raise the stop, turns any of them round. On the
  ORB family and the Pine scripts the best variant moved the design net by 1-9% of the loss. The one exception is VIX
  divergence BANKNIFTY (59 design trades): V2 and V4 turned -Rs 21.3k into about +Rs 15-17k in design, but q was 0.48 and
  the holdout gain was small (+Rs 1.3k / +2.3k on 19 trades, p 0.42-0.44).
- **Liquidity BANKNIFTY (frozen until 26 Oct, study only):** every variant made it worse in design (-Rs 0.1k to -76k on
  336 trades, +Rs 96.6k with its own exits). **Proposal for after 26 Oct: keep its exits as they are.** Nothing changes for
  it now, not even in shadow.
- Order-flow turns (Boss's fourth idea) were **not tested**: the app's order-flow recording only began on 12 Oct.
  ARCHITECTURE.md keeps that family shadow-only until 60 recorded days.

| arm (non-frozen unless said) | design trades | today's net (design / holdout) | best variant in design (delta, BH q) | verdict |
|---|---|---|---|---|
| ORB | 320 / 117 | -31,507 / -16,795 | V2 next S/R +1,494 (q 0.48) | keep today's exits |
| ORB Fresh | 320 / 117 | identical to ORB in this data (see note 1) | V2 +1,494 (q 0.48) | keep today's exits |
| ORB Sweep (parked) | 460 / 160 | -89,054 / -46,031 | V3 time stop +7,842 (q 0.66) | keep |
| Range Fade | 493 / 182 | -91,671 / -58,001 | V3 +6,204 (q 0.48; twin p 0.53) | keep |
| VIX divergence NIFTY | 57 / 15 | +1,219 / +9,326 | none positive | keep |
| VIX divergence BANKNIFTY | 59 / 19 | -21,304 / +7,332 | V2 +37,934 (q 0.48), V4 +36,444 (q 0.48) | keep (59 trades; holdout +1.3k / +2.3k, p 0.42-0.44) |
| Hero (NIFTY expiry) | 8 / 2 | -14,822 / -6,153 | V2 +8,944 (q 0.78) | keep; 10 trades in two years is too few to judge any exit |
| Pine EMA 9/21 NIFTY | 1,101 / 313 | -73,461 / -29,917 | V2 +5,523 (q 0.90) | keep |
| Pine EMA 9/21 BANKNIFTY | 1,173 / 334 | -166,774 / -42,764 | V3 +4,086 (q 0.48) | keep |
| Pine Supertrend NIFTY | 661 / 213 | -63,896 / -35,761 | V2 +4,373 (q 0.90) | keep |
| Pine Supertrend BANKNIFTY | 632 / 184 | -87,448 / -5,846 | V3 +876 (q 0.78) | keep |
| Liquidity BANKNIFTY (FROZEN) | 336 / 131 | +96,584 / +8,705 | none positive (V5 -123) | study only; proposal: keep its exits after 26 Oct |

Rs, 1 lot (Hero: its Rs 5,000 ticket), after the app's charges, the 0.16% real half-spread each side and the app's
paper slippage. "Design trades" = design / holdout.

### What a near miss looks like, plainly
- **V2 "exit at the next support/resistance"** was the most consistent in design: it helped 9 of the 10 distinct
  non-frozen arms (+Rs 64.8k in all, on 4,964 trades). It hurt VIX div NIFTY and Liquidity. No single arm was significant
  after BH (best q 0.48), and +Rs 64.8k is about a tenth of what those arms lost in design (-Rs 6.40 lakh). This pooled
  view is **post hoc** (it was not a pre-registered test). Its holdout rows are not evidence for it: that holdout is
  reused (above) and, with no candidate, untested. It is the one idea worth pre-registering again on the 6-year lake,
  judged there on the lake's fresh holdout only.
- **V5 "breakeven after +15%"** changed nothing on ORB and the Pine scripts: their own ladders and the Pine trail already
  move the stop to breakeven earlier (+10 points / +5%).
- **V4 "expected range used up"** never fired on ORB: ORB's +40 target or its ladder always came first (the 30-minute
  expected range at ORB entries was 118-220 index points, 10th-90th percentile, n = 316).

## Corrections after the review (13 Oct 2026; the same pre-registered variants, nothing new)
Two replay bugs were fixed and the design and holdout runs were re-run with the same code otherwise. Before the fixes,
the original code was re-run on the re-downloaded release data and reproduced the committed design numbers exactly, so
every change below comes from the fixes, not from the data.
1. **Expiry-day square-off.** ORB, ORB Fresh, ORB Sweep, Range Fade and both VIX divergence arms squared off at 15:10 even
   when the contract they trade expires that day; the app squares those off at 15:05. They now use 15:05 on such days
   (test: `test_orb_and_vixdiv_square_off_at_1505_when_the_contract_expires_today`). In this data it moved the exit of 3
   trades, all VIX divergence BANKNIFTY (2 design, 1 holdout) held to the square-off; the other expiry-day trades ended
   earlier on their stop, target or ladder (only the MFE window of those trades shrank by 5 minutes).
2. **Fills after the square-off.** A decision on the minute before the square-off on an option that did not print again
   until after it was filled at that later print. Fills are now capped at the square-off (nothing printed by then: the
   last printed price, at the square-off minute; test: `test_a_decision_just_before_the_square_off_never_fills_after_it`).
   In this data it changed 1 trade: a Pine EMA 9/21 NIFTY holdout trade (V0 and every variant except V2).
- **Effect.** VIX divergence BANKNIFTY design: today's net -19,129 -> -21,304; V2 +35,759 -> +37,934 (p 0.035 -> 0.025),
  V4 +34,251 -> +36,444 (p 0.025 -> 0.018), V5 +11,154 -> +13,329 (p 0.17 -> 0.10). BH q for these stays 0.48 / 0.48 /
  0.64. Holdout: VIX div BANKNIFTY +7,304 -> +7,332; Pine EMA NIFTY -30,066 -> -29,917. Every other number is unchanged
  (a few q values moved slightly because BH is computed over all 60 p-values). **No verdict changed: still 0 of 60 design
  candidates, best q 0.48, today's exits stay on every arm.**
- **The holdout was read a second time** for this re-run, as instructed, with the same 60 rows and no new variant. It was
  already a reused holdout (above); with no design candidate either time, no holdout test was run in either read.
- Also added: look-ahead tests (all data after minute t deleted or perturbed; the value at t unchanged) for the VWAP
  bands, R16's expected range, the support/resistance levels and every variant's exit minute, and synthetic tests of
  V1-V4. The R18 tests now run in CI (`.github/workflows/research.yml`, job "R18 exits tests").

## Data and method
- Release `research-data` (via `research/brain/adapters/release.py`): BANKNIFTY 1-min index and chain 13 Feb 2024 -
  23 Feb 2026 (498 sessions), NIFTY 1 Apr 2024 - 13 Apr 2026 (499); the expiring chain on expiry days (BANKNIFTY from Feb
  2025, NIFTY from Apr 2025); India VIX 1-min. No Dhan API call.
- **Re-run on the full Dhan lake** when it is published: `R18_SOURCE=lake python3 research/hunt/r18/data.py && ... run.py`
  (one switch in `config.py`). That is a NEW experiment: a new log entry and a **fresh** locked holdout (PREREG.md
  amendment 1): both indices from **27 Apr 2026** to the lake's end. Every release session up to 13 Apr 2026 (NIFTY's
  last; BANKNIFTY's ends 23 Feb 2026) has been examined by R16 and R18, so the lake's design ends on 13 Apr 2026 and the
  sessions from 14 to 24 Apr 2026 are an embargo used by neither.
- Holdouts on the release (R16's, reused; see the verdict): BANKNIFTY from 25 Aug 2025 (122 sessions), NIFTY from 13 Oct
  2025 (120). Design = before, in 4 consecutive folds. No setting was fitted: every variant's numbers were fixed in
  PREREG.md.
- Trade set fixed by the arm's own exits (as R12): a variant only changes how a trade ends.
- Gates: BH q < 0.10 over all 60; delta > 0 in 3 of 4 folds; still > 0 at 2x spread; beats 1,000 random-exit twins
  (p < 0.10). Holdout: candidate's delta > 0 with BH p < 0.10.
- "MFE kept" = sum of gross rupees kept / sum of the best option high reachable between the entry and the arm's square-off
  minus the entry. Negative = the arm gives back more than it captures.

## Deviations and caveats (all of them)
1. **ORB and ORB Fresh are identical in this data**: under today's paper cap of 1 entry a side a day, every first break
   on each side here was also a fresh one (the bar before it was inside the range). That need not hold on other data (a
   first break whose previous bar was already beyond the range would be an ORB trade and not an ORB Fresh one). Both were
   kept as pre-registered (two of the 60 tests are duplicates here; BH is therefore slightly conservative).
2. VWAP (V1) on the release uses the ATM +-2 option volume of each minute as weights (futures in the release have no
   volume). Labelled a proxy; the lake run uses futures volume.
3. Expiry days without the expiring chain in the release (BANKNIFTY before Feb 2025, NIFTY before Apr 2025) are skipped
   by the arms that trade today's expiry (ORB family, VIX div). This removes about one session in five before those dates.
4. Not modelled: Liquidity's priority over ORB for BANKNIFTY, ORB Fresh's "new arming" rule, Hero's "no further-OTM
   strike priced more than 25% above" check, the Pine thin-option skip; Pine entries were allowed 09:20-15:00 on 5-minute
   bars. The ORB +40 target fills at the target level (the app sells at the next minute's open after the high).
5. Hero had only 8 design and 2 holdout trades (NIFTY expiry days with the expiring chain: Apr 2025 onwards). Nothing can
   be concluded about Hero's exits from this data.
6. Night R3 (an overnight hold) and Solo midday (needs FINNIFTY's chain) were not covered; Liquidity FINNIFTY/MIDCPNIFTY
   were not covered (parked / no data).
7. The holdout (a reused one, see the verdict) was computed for every row (as pre-registered) but, with no candidates,
   none of it is a test. Holdout p-values below 0.10 in the tables (e.g. Range Fade V2 0.009, Pine Supertrend NIFTY V1/V2/V4 0.02-0.05)
   are descriptive and uncorrected; they did not pass the design gates.
8. Option prices are 1-minute bars (no bid/ask): the spread is R15/h24's 0.16% a side, stressed at 2x.


## The tables (from `report.py`, not edited by hand)
Numbers in Rs; n = trades. "folds" = design delta in each of the 4 folds; "changed" = trades whose exit the variant moved.

### today's exits (V0)

| arm | design trades / days | design net | MFE kept | worst day | max DD | holdout trades / days | holdout net | MFE kept | worst day | max DD |
|---|---|---|---|---|---|---|---|---|---|---|
| ORB | 320 / 287 | -31,507 | -0.4% | -2,757 | -32,404 | 117 / 107 | -16,795 | -1.3% | -1,681 | -17,063 |
| ORB Fresh | 320 / 287 | -31,507 | -0.4% | -2,757 | -32,404 | 117 / 107 | -16,795 | -1.3% | -1,681 | -17,063 |
| ORB Sweep | 460 / 272 | -89,054 | -5.4% | -3,191 | -89,588 | 160 / 101 | -46,031 | -9.6% | -3,133 | -48,407 |
| Range Fade | 493 / 272 | -91,671 | -5.2% | -3,118 | -92,214 | 182 / 106 | -58,001 | -12.1% | -3,196 | -60,723 |
| VIX divergence NIFTY | 57 / 57 | 1,219 | 3.3% | -2,625 | -17,735 | 15 / 15 | 9,326 | 16.1% | -1,644 | -4,516 |
| VIX divergence BANKNIFTY | 59 / 59 | -21,304 | -10.4% | -8,745 | -35,670 | 19 / 19 | 7,332 | 14.0% | -8,544 | -8,630 |
| Hero (NIFTY expiry) | 8 / 8 | -14,822 | -26.9% | -3,951 | -14,822 | 2 / 2 | -6,153 | -58.8% | -3,098 | -6,153 |
| Pine EMA 9/21 NIFTY | 1101 / 351 | -73,461 | -0.1% | -6,272 | -77,873 | 313 / 106 | -29,917 | -0.8% | -5,539 | -33,011 |
| Pine EMA 9/21 BANKNIFTY | 1173 / 351 | -166,774 | -3.0% | -5,995 | -170,547 | 334 / 108 | -42,764 | -1.5% | -4,829 | -45,331 |
| Pine Supertrend NIFTY | 661 / 325 | -63,896 | -1.2% | -4,706 | -75,934 | 213 / 104 | -35,761 | -3.2% | -4,282 | -38,698 |
| Pine Supertrend BANKNIFTY | 632 / 323 | -87,448 | -2.3% | -3,310 | -87,953 | 184 / 103 | -5,846 | 1.8% | -4,146 | -15,639 |
| Liquidity BANKNIFTY (frozen: study only) | 336 / 210 | 96,584 | 10.7% | -3,698 | -16,982 | 131 / 78 | 8,705 | 5.3% | -5,850 | -12,527 |

### V1 VWAP band trail

| arm | design delta | folds | p | BH q | 2x spread | twin p | changed | MFE kept | worst day | max DD | holdout delta (n trades) | holdout p |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ORB | -215 | +153 / -935 / +667 / -100 | 0.581 | 1.00 | -213 | 1.000 | 11 of 320 | -0.4% | -2,757 | -32,713 | +1,428 (117) | 0.098 |
| ORB Fresh | -215 | +153 / -935 / +667 / -100 | 0.581 | 1.00 | -213 | 1.000 | 11 of 320 | -0.4% | -2,757 | -32,713 | +1,428 (117) | 0.098 |
| ORB Sweep | -4,442 | +38 / -808 / +134 / -3,805 | 0.891 | 1.00 | -4,436 | 1.000 | 28 of 460 | -5.9% | -3,191 | -94,029 | -3,143 (160) | 0.774 |
| Range Fade | -2,012 | -577 / -723 / -174 / -538 | 0.921 | 1.00 | -2,009 | 1.000 | 14 of 493 | -5.4% | -2,671 | -94,226 | +1,029 (182) | 0.353 |
| VIX divergence NIFTY | -3,914 | -9,295 / +520 / +5,441 / -580 | 0.646 | 1.00 | -3,907 | 0.939 | 11 of 57 | 0.8% | -2,625 | -12,371 | +2,747 (15) | 0.267 |
| VIX divergence BANKNIFTY | +7,923 | -3,267 / +8,174 / -4,590 / +7,608 | 0.292 | 0.90 | +7,913 | 0.836 | 39 of 59 | -5.4% | -8,745 | -26,037 | -2,925 (19) | 0.613 |
| Hero (NIFTY expiry) | +1,520 | +0 / +0 / +0 / +1,520 | 0.032 | 0.48 | +1,520 | 0.582 | 3 of 8 | -24.1% | -3,951 | -13,303 | +1,085 (2) | 0.255 |
| Pine EMA 9/21 NIFTY | -9,512 | -2,671 / -6,310 / +508 / -1,039 | 0.892 | 1.00 | -9,504 | 1.000 | 129 of 1101 | -0.5% | -5,423 | -87,461 | -1,045 (313) | 0.583 |
| Pine EMA 9/21 BANKNIFTY | +543 | -1,187 / +78 / +934 / +717 | 0.394 | 1.00 | +541 | 1.000 | 26 of 1173 | -3.0% | -5,714 | -170,004 | -3,149 (334) | 0.820 |
| Pine Supertrend NIFTY | -1,047 | -83 / -2,645 / +1,294 / +387 | 0.549 | 1.00 | -1,049 | 1.000 | 102 of 661 | -1.2% | -5,619 | -76,094 | +10,267 (213) | 0.047 |
| Pine Supertrend BANKNIFTY | -2,832 | -1,003 / -345 / +341 / -1,826 | 0.913 | 1.00 | -2,829 | 1.000 | 13 of 632 | -2.5% | -3,310 | -91,029 | +1,266 (184) | 0.351 |
| Liquidity BANKNIFTY (frozen: study only) | -51,936 | -5,520 / -891 / -33,306 / -12,219 | 0.965 | 1.00 | -51,854 | 1.000 | 52 of 336 | 6.2% | -3,692 | -16,107 | -18,154 (131) | 0.940 |

### V2 next support/resistance

| arm | design delta | folds | p | BH q | 2x spread | twin p | changed | MFE kept | worst day | max DD | holdout delta (n trades) | holdout p |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ORB | +1,494 | +32 / +0 / +267 / +1,195 | 0.064 | 0.48 | +1,492 | 0.001 | 11 of 320 | -0.3% | -2,757 | -31,297 | +124 (117) | 0.487 |
| ORB Fresh | +1,494 | +32 / +0 / +267 / +1,195 | 0.064 | 0.48 | +1,492 | 0.001 | 11 of 320 | -0.3% | -2,757 | -31,297 | +124 (117) | 0.487 |
| ORB Sweep | +5,814 | -137 / +555 / +6,819 / -1,422 | 0.200 | 0.78 | +5,804 | 0.001 | 108 of 460 | -4.8% | -3,191 | -83,774 | +5,830 (160) | 0.208 |
| Range Fade | +4,812 | +914 / +167 / +371 / +3,359 | 0.061 | 0.48 | +4,805 | 0.001 | 59 of 493 | -4.7% | -3,118 | -87,402 | +7,583 (182) | 0.009 |
| VIX divergence NIFTY | -8,576 | -13,471 / -2,446 / +2,714 / +4,626 | 0.743 | 1.00 | -8,563 | 0.946 | 19 of 57 | -2.2% | -2,625 | -12,768 | -8,922 (15) | 0.754 |
| VIX divergence BANKNIFTY | +37,934 | +655 / +11,721 / +17,503 / +8,055 | 0.025 | 0.48 | +37,875 | 0.001 | 39 of 59 | 13.5% | -8,745 | -22,121 | +1,288 (19) | 0.444 |
| Hero (NIFTY expiry) | +8,944 | +0 / +0 / +1,939 / +7,005 | 0.197 | 0.78 | +8,944 | 0.320 | 4 of 8 | -10.2% | -3,951 | -12,464 | +0 (2) | 1.000 |
| Pine EMA 9/21 NIFTY | +5,523 | +1,483 / -5,780 / +6,119 / +3,702 | 0.315 | 0.90 | +5,514 | 0.001 | 273 of 1101 | 0.1% | -5,958 | -69,357 | +2,383 (313) | 0.274 |
| Pine EMA 9/21 BANKNIFTY | +3,591 | -33 / +469 / +4,091 / -936 | 0.269 | 0.90 | +3,587 | 0.001 | 176 of 1173 | -2.9% | -5,714 | -164,041 | +1,174 (334) | 0.415 |
| Pine Supertrend NIFTY | +4,373 | +1,079 / -6,828 / +4,990 / +5,132 | 0.311 | 0.90 | +4,360 | 0.001 | 177 of 661 | -0.9% | -4,706 | -64,376 | +9,061 (213) | 0.018 |
| Pine Supertrend BANKNIFTY | +849 | -185 / +539 / +1,825 / -1,330 | 0.428 | 1.00 | +848 | 0.001 | 75 of 632 | -2.2% | -3,310 | -86,979 | -3,563 (184) | 0.817 |
| Liquidity BANKNIFTY (frozen: study only) | -76,324 | -27,237 / -7,106 / -29,582 / -12,400 | 0.993 | 1.00 | -76,204 | 0.996 | 64 of 336 | 4.1% | -3,698 | -23,468 | -496 (131) | 0.546 |

### V3 time stop 45 min

| arm | design delta | folds | p | BH q | 2x spread | twin p | changed | MFE kept | worst day | max DD | holdout delta (n trades) | holdout p |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ORB | -555 | +0 / -1,049 / +0 / +494 | 0.802 | 1.00 | -554 | 0.969 | 3 of 320 | -0.5% | -2,757 | -32,959 | +2,960 (117) | 0.181 |
| ORB Fresh | -555 | +0 / -1,049 / +0 / +494 | 0.802 | 1.00 | -554 | 0.969 | 3 of 320 | -0.5% | -2,757 | -32,959 | +2,960 (117) | 0.181 |
| ORB Sweep | +7,842 | +502 / -2,190 / +3,064 / +6,466 | 0.121 | 0.66 | +7,831 | 0.010 | 104 of 460 | -4.6% | -3,191 | -81,763 | +3,069 (160) | 0.363 |
| Range Fade | +6,204 | +170 / +949 / -1,690 / +6,775 | 0.049 | 0.48 | +6,193 | 0.527 | 45 of 493 | -4.6% | -3,118 | -86,266 | +3,523 (182) | 0.210 |
| VIX divergence NIFTY | -7,144 | -8,350 / -5,332 / +5,127 / +1,411 | 0.767 | 1.00 | -7,133 | 0.589 | 11 of 57 | -1.3% | -2,625 | -13,526 | -3,283 (15) | 0.652 |
| VIX divergence BANKNIFTY | -1,542 | -9,031 / +464 / -579 / +7,604 | 0.526 | 1.00 | -1,536 | 0.792 | 59 of 59 | -11.4% | -5,182 | -28,015 | -12,700 (19) | 0.820 |
| Hero (NIFTY expiry) | +6,447 | +0 / +0 / +0 / +6,447 | 0.107 | 0.64 | +6,447 | 0.220 | 2 of 8 | -14.8% | -3,951 | -12,683 | +0 (2) | 1.000 |
| Pine EMA 9/21 NIFTY | -10,991 | -2,653 / +136 / -9,103 / +629 | 0.976 | 1.00 | -10,976 | 0.649 | 75 of 1101 | -0.6% | -6,395 | -87,615 | +647 (313) | 0.414 |
| Pine EMA 9/21 BANKNIFTY | +4,086 | +396 / +1,103 / +2,235 / +352 | 0.021 | 0.48 | +4,079 | 0.120 | 22 of 1173 | -2.8% | -5,995 | -166,461 | -1,808 (334) | 0.731 |
| Pine Supertrend NIFTY | -3,490 | -3,559 / -1,931 / -2,150 / +4,151 | 0.696 | 1.00 | -3,487 | 0.743 | 77 of 661 | -1.4% | -4,706 | -77,144 | +9,658 (213) | 0.063 |
| Pine Supertrend BANKNIFTY | +876 | +137 / +332 / +866 / -458 | 0.206 | 0.78 | +875 | 0.008 | 8 of 632 | -2.2% | -3,310 | -87,077 | +1,107 (184) | 0.228 |
| Liquidity BANKNIFTY (frozen: study only) | -47,128 | -21,190 / +5,897 / -34,853 / +3,018 | 0.933 | 1.00 | -47,055 | 0.976 | 38 of 336 | 6.6% | -3,692 | -15,841 | -3,129 (131) | 0.717 |

### V4 expected range used up

| arm | design delta | folds | p | BH q | 2x spread | twin p | changed | MFE kept | worst day | max DD | holdout delta (n trades) | holdout p |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ORB | +0 | +0 / +0 / +0 / +0 | 1.000 | 1.00 | +0 | 1.000 | 0 of 320 | -0.4% | -2,757 | -32,404 | +0 (117) | 1.000 |
| ORB Fresh | +0 | +0 / +0 / +0 / +0 | 1.000 | 1.00 | +0 | 1.000 | 0 of 320 | -0.4% | -2,757 | -32,404 | +0 (117) | 1.000 |
| ORB Sweep | +297 | -167 / -20 / +1,032 / -549 | 0.473 | 1.00 | +298 | 0.001 | 37 of 460 | -5.4% | -3,191 | -89,291 | -2,372 (160) | 0.688 |
| Range Fade | -96 | -85 / +146 / -156 / +0 | 0.639 | 1.00 | -95 | 0.001 | 5 of 493 | -5.2% | -3,118 | -92,310 | +459 (182) | 0.383 |
| VIX divergence NIFTY | -4,477 | -12,233 / -3,989 / +7,544 / +4,201 | 0.653 | 1.00 | -4,470 | 0.393 | 12 of 57 | 0.4% | -2,625 | -10,964 | -8,731 (15) | 0.784 |
| VIX divergence BANKNIFTY | +36,444 | -1,204 / +12,225 / +19,983 / +5,440 | 0.018 | 0.48 | +36,387 | 0.001 | 34 of 59 | 12.5% | -8,745 | -24,257 | +2,312 (19) | 0.423 |
| Hero (NIFTY expiry) | +2,162 | +0 / +0 / +0 / +2,162 | 0.376 | 1.00 | +2,162 | 0.452 | 2 of 8 | -22.9% | -3,951 | -16,059 | +0 (2) | 1.000 |
| Pine EMA 9/21 NIFTY | +4,493 | +2,333 / +405 / +585 / +1,170 | 0.167 | 0.78 | +4,484 | 0.001 | 64 of 1101 | 0.1% | -6,272 | -71,743 | +2,447 (313) | 0.098 |
| Pine EMA 9/21 BANKNIFTY | -1,590 | -26 / +23 / -489 / -1,098 | 0.906 | 1.00 | -1,587 | 0.001 | 13 of 1173 | -3.1% | -5,995 | -172,000 | -334 (334) | 0.567 |
| Pine Supertrend NIFTY | +2,734 | +381 / -784 / +5,157 / -2,020 | 0.208 | 0.78 | +2,726 | 0.001 | 35 of 661 | -1.0% | -4,706 | -72,522 | +2,622 (213) | 0.042 |
| Pine Supertrend BANKNIFTY | -912 | -288 / +0 / +0 / -624 | 0.884 | 1.00 | -911 | 0.008 | 3 of 632 | -2.3% | -3,310 | -88,866 | -590 (184) | 0.781 |
| Liquidity BANKNIFTY (frozen: study only) | -61,703 | -15,259 / -682 / -40,306 / -5,456 | 0.975 | 1.00 | -61,607 | 0.893 | 45 of 336 | 5.4% | -3,692 | -15,317 | +495 (131) | 0.517 |

### V5 breakeven after +15%

| arm | design delta | folds | p | BH q | 2x spread | twin p | changed | MFE kept | worst day | max DD | holdout delta (n trades) | holdout p |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ORB | +0 | +0 / +0 / +0 / +0 | 1.000 | 1.00 | +0 | 1.000 | 0 of 320 | -0.4% | -2,757 | -32,404 | +0 (117) | 1.000 |
| ORB Fresh | +0 | +0 / +0 / +0 / +0 | 1.000 | 1.00 | +0 | 1.000 | 0 of 320 | -0.4% | -2,757 | -32,404 | +0 (117) | 1.000 |
| ORB Sweep | -3,044 | +247 / -505 / +6 / -2,793 | 0.874 | 1.00 | -3,039 | 0.903 | 21 of 460 | -5.7% | -3,191 | -92,638 | -5,394 (160) | 0.862 |
| Range Fade | -626 | +5 / +7 / +4 / -643 | 0.747 | 1.00 | -625 | 0.290 | 5 of 493 | -5.2% | -3,118 | -92,840 | -7 (182) | 0.643 |
| VIX divergence NIFTY | -427 | -2,225 / -1,270 / +2,074 / +993 | 0.555 | 1.00 | -426 | 0.994 | 8 of 57 | 3.1% | -2,625 | -16,552 | +889 (15) | 0.267 |
| VIX divergence BANKNIFTY | +13,329 | +203 / +11,248 / +1,163 / +715 | 0.103 | 0.64 | +13,308 | 0.178 | 13 of 59 | -2.0% | -8,745 | -24,538 | +13,666 (19) | 0.076 |
| Hero (NIFTY expiry) | +5,622 | +0 / +0 / +4,506 / +1,116 | 0.310 | 0.90 | +5,622 | 0.705 | 6 of 8 | -16.4% | -3,464 | -9,264 | +2,793 (2) | 0.245 |
| Pine EMA 9/21 NIFTY | +0 | +0 / +0 / +0 / +0 | 1.000 | 1.00 | +0 | 1.000 | 0 of 1101 | -0.1% | -6,272 | -77,873 | +0 (313) | 1.000 |
| Pine EMA 9/21 BANKNIFTY | +0 | +0 / +0 / +0 / +0 | 1.000 | 1.00 | +0 | 1.000 | 0 of 1173 | -3.0% | -5,995 | -170,547 | +0 (334) | 1.000 |
| Pine Supertrend NIFTY | +0 | +0 / +0 / +0 / +0 | 1.000 | 1.00 | +0 | 1.000 | 0 of 661 | -1.2% | -4,706 | -75,934 | +0 (213) | 1.000 |
| Pine Supertrend BANKNIFTY | +0 | +0 / +0 / +0 / +0 | 1.000 | 1.00 | +0 | 1.000 | 0 of 632 | -2.3% | -3,310 | -87,953 | +0 (184) | 1.000 |
| Liquidity BANKNIFTY (frozen: study only) | -123 | -1,982 / +2,398 / -1,632 / +1,093 | 0.539 | 1.00 | -123 | 1.000 | 30 of 336 | 10.7% | -3,692 | -18,830 | -1,776 (131) | 0.921 |
