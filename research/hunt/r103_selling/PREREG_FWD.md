# R103 deep, forward test: the NIFTY expiry-day strangle with a profit lock, and its enhancements

Written 22 Oct 2026, before the enhancement search below was run and before any forward data exists (the lake ends on
19 Oct 2026; the forward window starts on 20 Oct 2026). Boss (22 Oct): "Less profit works for me, but the win rate is
more important", then "Ok, and try to enhance it". Research only: nothing trades, on paper or live. Signal source: none
for the entry (a fixed time); the filters below read the underlying index and India VIX only (CLAUDE.md allows option-
chain and context filters around the rule; here they are on the index itself).

## 0. Honesty first

- The base rule was chosen AFTER the fact: it is the only one of 54 exploratory lock variants (entry `r103-deep-lock-
  explore`) with a win rate of at least 70% and a profit in both periods. The holdout of 15 Jul - 19 Oct 2026 has already
  been read for this family. Every number computed on data up to 19 Oct 2026 is therefore in-sample.
- The ONLY clean test is forward: NIFTY expiry days from 20 Oct 2026, scored from the lake as it is refreshed. This file
  fixes that test before the data exists.

## 1. The base rule (B0)

NIFTY expiry days only (the weekly NIFTY expiry; event days in `events.csv` skipped). At 10:15 (decision on the 10:15
bar's close, fills at 10:16's open) sell the call and the put one expected move out (w = round(ATM straddle / 50), 1..6
strikes; PREREG.md section 3, STG). Combined stop: close everything when the mark-to-market loss reaches 100% of the
credit. Profit lock: once the profit reaches 30% of the credit, close everything if it falls back to 20%. Otherwise close
at 15:05. One lot, dated charges, measured-spread slippage, the calibrated model for legs without a print (PREREG.md).

## 2. The enhancement search (on history only; fixed now, before it is run)

Every combination of these is run on Design A (20 Nov 2024 - 14 Jul 2026) and Design B (5 Aug 2021 - 19 Nov 2024), NIFTY
expiry days, the B0 stop and lock unchanged:

| knob | levels |
|---|---|
| strangle width | 1.0x, 1.25x, 1.5x the ATM straddle (rounded to strikes, 1..10) |
| entry time | 10:15, 11:00, 12:00 |
| exit time | 15:05 (expiry square-off), 14:30 (avoid the last half-hour of gamma) |
| skip filter (read at the entry minute, index / VIX only) | none; GAP: the 09:15 open is more than 0.6% from the previous close; RANGE: the index's high - low from 09:15 to the entry minute exceeds the ATM straddle at the entry minute; VIX: India VIX at the entry minute is more than 5% above its previous close; ALL: any of the three |

3 x 3 x 2 x 5 = **90 variants** (B0 is one of them). The holdout window (15 Jul - 19 Oct 2026) is NOT used.

**Finalists (the rule, fixed now):** a variant qualifies if, in BOTH Design A and Design B, it has at least 40 trades (A)
/ 60 trades (B), a win rate of at least 70%, a net above zero, and no calendar month below -Rs 13,000. Qualifiers are
ranked by the lower of their two win rates, ties broken by the lower of their two nets per trade. The top 3 qualifiers
other than B0 are the finalists. If fewer than 3 qualify, fewer go forward; if none qualifies, only B0 goes forward.

## 3. The forward test (fixed now; the rules are filled in from section 2's mechanical selection)

- **Rules:** B0 and the finalists (at most 4), exactly as run in the search.
- **Window:** NIFTY expiry days from 20 Oct 2026, event days skipped, scored by `r103fwd.py score` from the lake as it is
  refreshed. The script refuses any day before 20 Oct 2026.
- **Decision point:** after 40 forward expiries for a rule (about nine months), never earlier. A rule PASSES only if all hold:
  1. win rate >= 65%, and a one-sided binomial test of the win rate against 50% has p <= 0.05, with Benjamini-Hochberg
     q <= 0.10 across the forward rules;
  2. net > 0 after costs;
  3. no calendar month below -Rs 13,000 and a drawdown no worse than -Rs 40,000 (Boss's account stop, one lot);
  4. the average loss is no more than 3 times the average win.
- **What a pass means:** a paper candidate only. It still needs Boss's explicit decision and the selling safety rails
  (DEEP_OPTIONS_SELLING.md section 8) before even paper. A fail closes the idea.
- Interim looks are allowed for monitoring but decide nothing; the 40-expiry mark is the one decision.
