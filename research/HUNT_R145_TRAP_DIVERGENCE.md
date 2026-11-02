# R145: the "demand zone trap + put premium divergence + OI" video setup, backtested as stated

2 Nov 2026. Research only: nothing in the app changes, nothing trades. Boss sent a video (Bank Nifty and Nifty, Kite
with Sensibull's OI profile) and said *"yes run the backtest"*. Its four factors for a call buy:
1. **Demand zone:** a sharp rally (about 600 BANKNIFTY points) from a support level. The big buyer there defends it.
2. **The trap ("lollipop"):** price drifts back and breaks below that support, tempting put buyers.
3. **Premium divergence:** the index breaks down but put premiums do not rise: a false breakdown, so buy calls.
4. **OI confirmation:** put OI (Sensibull's green bars) builds at the support.
Claimed: Rs 10,000-15,000 is "easy" on such moves.

## The answer: it loses money, in every version, before and after the cut-off

**On all five indices from Aug 2021 to 19 Oct 2026, buying the ATM call on this setup lost money after charges and
slippage in all 6 versions, in the design period and again in the locked holdout. No trade of any version made
Rs 10,000.**

And the video's key signal points the wrong way:
- **A put that does not rise is rare, and it is not a sign of a false breakdown.** When the index makes a new 30-minute
  low below a demand zone, the ATM put almost always makes a new high too. Only 72 of the 1,600 breakdowns with put data
  (4.5%; 3% of all 2,218) had the put not rising. In the design period those reversed up *less* often than the rest: 32% (66 decided races)
  against 47% (1,434), p 0.014. In the holdout the direction flipped (58% of 12 against 42% of 123, p 0.28): too few to
  mean anything.
- **Put OI building at the support adds nothing reliable:** 48% up-first with it (852) against 45% without (669),
  p 0.20 (design); holdout 49% (83) against 37% (71), p 0.11.
- **With the video's conditions applied, there are almost no trades:** 37 in four and a half years (V1), 9 in the
  holdout. Far below the 300 / 50 the gates need.

Design, 1 Aug 2021 to 15 Mar 2026 (1,144 sessions), 1 lot, net after charges and slippage:

| version | trades | win | avg win / avg loss | avg net (2x slip) | total | max drawdown | vs random entries (p / BH q) |
|---|---|---|---|---|---|---|---|
| **V1, the setup as stated** | 37 | 27% | Rs 575 / -Rs 653 | **-Rs 321** (-Rs 358) | **-Rs 11,870** | -Rs 12,667 | no better (0.84 / 0.98) |
| V2, enter on a close back above the zone | 12 | 42% | Rs 1,011 / -Rs 1,163 | -Rs 257 (-Rs 287) | -Rs 3,082 | -Rs 5,251 | no better (0.70 / 0.98) |
| V3, no OI condition | 55 | 22% | Rs 510 / -Rs 591 | -Rs 351 (-Rs 386) | -Rs 19,283 | -Rs 19,283 | no better (0.98 / 0.98) |
| V4, baseline: zone + breakdown only | 1,153 | 35% | Rs 639 / -Rs 625 | -Rs 186 (-Rs 217) | **-Rs 2,14,991** | -Rs 2,21,348 | better than random, still losing (0.050 / 0.30) |
| V5, ride it: index stop + 60% lock after Rs 1,000 | 37 | 16% | Rs 632 / -Rs 646 | -Rs 439 (-Rs 479) | -Rs 16,233 | -Rs 16,233 | no better (0.91 / 0.98) |
| V6, both sides (mirror) | 71 | 37% | Rs 474 / -Rs 554 | -Rs 177 (-Rs 208) | -Rs 12,584 | -Rs 14,043 | no better (0.36 / 0.98) |

- **Folds (2023 / 2024 / 2025 / Jan-Mar 2026):** V4 lost in all four (-Rs 40.0k / -43.3k / -68.8k / -36.8k). V1 was
  positive only in 2023 (+Rs 188 on its few trades).
- **V4 by index (design):** every index lost: BANKNIFTY -Rs 51,460 (364), FINNIFTY -Rs 51,318 (207), MIDCPNIFTY
  -Rs 69,690 (251), NIFTY -Rs 32,004 (233), SENSEX -Rs 10,519 (98).
- **Exits:** most trades hit the index stop (V1: 28 stops, 9 targets; V4: 821 stops, 328 targets, 4 at the close).

Holdout, 1 Apr to 19 Oct 2026 (137 sessions), read once after the design results were committed (`df245f6`):

| version | trades | win | avg net | total | vs random (p) |
|---|---|---|---|---|---|
| V1 | 9 | 33% | -Rs 339 | -Rs 3,051 | 0.51 |
| V2 | 4 | 25% | -Rs 719 | -Rs 2,878 | 0.57 |
| V3 | 9 | 33% | -Rs 339 | -Rs 3,051 | 0.42 |
| V4 | 116 | 41% | -Rs 33 | -Rs 3,810 | 0.005 |
| V5 | 9 | 11% | -Rs 629 | -Rs 5,658 | 0.86 |
| V6 | 12 | 33% | -Rs 311 | -Rs 3,728 | 0.45 |

(V1 and V3 are the same 9 trades: every holdout trade with the put not rising also had put OI up.)

**No version passes.** Every one fails the design gates (V1-V3, V5, V6: too few trades, negative, folds, q; V4:
negative, folds, q 0.30) and the holdout gates (all negative; win rates 11-41% against Boss's 65%). V4 also breaks the
account stop (max drawdown -Rs 2.29 lakh, worst month -Rs 21,789).

**One thing worth noting, not a strategy:** the plain baseline (V4: buy the call after a close below a rallied swing
low) beat random entries with the same stop and target in both periods (p 0.050 design, 0.005 holdout). It still lost
after costs (-Rs 186 and -Rs 33 a trade), because random entries lose more (twin mean -Rs 2.60 lakh in design).
"Less bad than random" is not an edge. Its holdout was close to break-even on 116 trades; that would need a fresh,
pre-registered study, not a tweak of this one.

## The video's claims, checked

| claim | result |
|---|---|
| "Put premiums not rising marks a false breakdown" | **No.** It happens on 4.5% of breakdowns with put data (72 of 1,600), and those reversed up less often in design (32% of 66 vs 47% of 1,434, p 0.014). Holdout: 58% of 12 vs 42% of 123 (p 0.28). |
| "Put OI building at the support confirms it" | **Not reliably.** 48% vs 45% (p 0.20) in design; 49% vs 37% (p 0.11) in the holdout. |
| "Both together, and it reverses sharply" | **No.** Both: 33% up-first (39) vs neither 46% (611) in design (p 0.13); holdout 58% (12) vs 34% (59), p 0.11. |
| "Rs 10,000-15,000 is easy on such moves" | **No.** 0 of 37 (V1) and 0 of 37 (V5, riding the move) design trades made Rs 10,000; best average win about Rs 1,000 (V2). |
| "Rs 1 to Rs 52" | not retested: R117 / R118 measured every expiry option (Rs 1-3 options reach 10x 2.3% of the time; 88% expire near zero). |

All race figures: a 1:1 race of 0.25% on the index from the breakdown bar's close, decided races only (2,080 design,
161 holdout). Overall, 48% of design breakdowns went up 0.25% first (43% holdout): breakdowns of a demand zone more
often keep falling.

## Why it fails, in plain words

1. **The divergence the video shows is mostly not there.** A fixed ATM put gains when the index falls (its delta), so
   a new index low almost always brings a new put high. The rare cases where it does not are usually thin or
   falling-IV moments, and they did not reverse more.
2. **The support is drawn after the fact.** In the video the zone is marked on a chart that already shows the
   reversal. Defined mechanically (any swing low that rallied 1%), most breakdowns keep going: 52% fell 0.25% before
   rising 0.25%.
3. **The OI "green bars" are always there.** R118 found put OI sits about one straddle below spot every day and grows
   through the session as writers sell. Rising put OI at support is the normal picture.
4. **The examples are one day.** The Bank Nifty and Nifty charts in the video are the same session; one rally on two
   correlated indices is one example.

## Method (as pre-registered)

- **Pre-registration:** `research/hunt/r145_trap_divergence/PREREG.md`, commit `00bd318`, pushed before any data was
  read. Code and tests `e6293a1` before any trade was priced; design results `df245f6` before the one holdout read
  (flag file).
- **Signal source:** the index spot (zone, breakdown, reclaim, stop, target). Factor 3 (the ATM put's traded premium)
  and factor 4 (the support strike's put OI) are option-chain context around the index signal, as CLAUDE.md allows.
  The ATM call is bought (V6 also buys the ATM put on the mirrored setup). V5's lock reads the held option's premium.
- **Rules in one line:** a 5-minute swing low (3 bars each side) that rallies 1.0% before its low is taken; the first
  5-minute close below it, making a new 30-minute low, 09:44-14:59; factor 3: the ATM put's highest traded close in the
  breakdown bar not above its high of the 25 minutes before; factor 4: the support strike's put OI up 2% over the 30
  minutes; enter at the next minute's open; index stop below the breakdown low (0.02%); 2R target; out at 15:20.
- **Data:** the Drive lake (index_1m; options_rolling_1m WEEK / MONTH after lakeclean steps 1-3), copied 2 Nov 2026.
  The nearest expiry that does not expire that day; 1 lot at today's lots; liquidity-aware slippage (also 2x); dated
  charges. 200 random-entry twins per trade (same day, side, stop and target distances).
- **Gates (R130's):** design >= 300 trades, positive at 1x and 2x, 3 of 4 folds, BH q <= 0.10 over 6; holdout >= 50
  trades, positive, win >= 65%; account drawdown above -Rs 40,000 and no month below -Rs 13,000.
- **One clarification made while coding (before any result):** for a breakdown bar ending at 09:44, "the index's close
  at e - 30" falls before the open; the 09:15 open is used instead.
- **Honest notes:** the holdout months had been read by earlier studies for other rules, never with this one. The lake
  ends 19 Oct 2026.

## Files

- Code and tests: `research/hunt/r145_trap_divergence/` (`r145.py`, `test_r145.py`: 15 tests, including the look-ahead
  guard on the breakdown and on both factors). Reuses R130's bars, exits, option pricing and twin test.
- Results: `results/design.json`, `results/holdout.json`. Trade lists: the local cache (`$R145_OUT`), not in git.
- Experiment entries: `research/experiments/entries/r145-v1.yaml` .. `r145-v6.yaml` (6, all rejected).

**Verdict: rejected. Nothing changes in the app.**
