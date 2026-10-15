# R61 "Solo 3.0": teaching Solo what OI, IV and gamma are, and testing whether that knowledge helps (15 Oct 2026)

Boss, 15 Oct: "Make Solo more advanced, intelligent and smart; it should understand each and every thing - what OI is,
what gamma is, etc., their features, why and how they are used - so it can think on its own and trade." Solo is
Jarvis's own trader and stays **paper only, forever**. Research only: nothing in the app changed (no `android/**`, no
`.github/**`, no knowledge-pack rebuild). Code: `research/hunt/r61/`.

## The answer

**Solo 3.0 does not beat Solo 2.0, so Solo 2.0 (A2) stays.** Solo now *knows* each concept: what it is, how it is
computed, and what our research says about it. But knowing them did not make it pick trades better on unseen data.

- **Pre-registration.** `hunt/r61/PREREG.md` (commit 701b8c18) was written before any data was read. The design
  results were committed (814284a2) before the one final read.
- **The comparator is exact.** V1 is A2 refitted in R61's pipeline. It reproduces R57 to the rupee: design 166 trades,
  +Rs 55,910; final 128 trades, +Rs 99,172.
- **All 7 variants pass the R47-R60 gates.** Final net is positive, twin p 0.002-0.004, BH q ≤ 0.004, 117-128 trades,
  and top-5 < net. That is mostly A2's own edge: they all choose from the same S1-S4 candidates.
- **The pre-registered Solo 3.0 candidate is V3**: A2 + Greeks + context, no option-chain block; it had the best design
  walk-forward net, +Rs 79,505. On the same final days as V1 it lost three of the four replace checks:

  | check | V3 | V1 (Solo 2.0) | result |
  |---|---|---|---|
  | final net | +Rs 65,219 | +Rs 99,172 | lower |
  | final AUC | 0.537 | 0.538 | lower |
  | paired-day bootstrap | p 0.97 | - | not significant |
  | net without MIDCPNIFTY | +Rs 35,245 | +Rs 27,586 | better |

  It does not replace Solo 2.0.
- **No concept added measurable skill out of sample.** Removing any concept's inputs from the all-inputs model, or
  adding any one input alone to A2's 29, moved walk-forward AUC by less than 0.01 in design and in final. The largest
  changes were:
  - gamma per 1% added alone: +0.005 final;
  - max-pain distance added alone: -0.006 final;
  - IV rank added alone: -0.007 design.
  Net changes went both ways. Adding inputs mostly diluted A2: V2 (all 59) made +Rs 60,399 against V1's +Rs 99,172.
- **One thing worth watching, not acting on.** The tree model with every input (V5) spread its profit better:
  - +Rs 71,787 on 122 trades;
  - only 33 MIDCPNIFTY trades;
  - +Rs 48,680 without MIDCPNIFTY (V1: +Rs 27,586);
  - max drawdown Rs 15,202 (V1: Rs 18,056).
  V5 was not the pre-registered candidate, and it made less money than V1. It is a note for a forward test, not a
  result.
- **News (R61-N, Boss's follow-up):** positive / negative headline tone before the signal does not help either
  (section R61-N below).

**Honest caveats.**
1. This final window is not fresh: R57 read it for A2 with the same setups, and S3's exit was chosen on it by R52.
2. The model skill is tiny everywhere: AUC 0.51-0.54 in walk-forward, against 0.55-0.58 on the leaky random split.
3. Live paper from 15 Oct 2026 is the only truly unseen data.

## Part 1 in one table: what each concept is worth, by our own research

Full details, formulas and sources are in `hunt/r61/CONCEPTS.md`. "Size" means the concept predicts how much the index
may move, not which way.

| concept | best evidence (study, sample) | verdict |
|---|---|---|
| OI level / change | OI_FILTER (145 arm x check results), H26 (2,268 variants; BU15 filter +Rs 81/day on 876 trades, p 0.035, not adopted) | failed (one near-miss filter) |
| OI build-up types | IV1 O3 (7,339 trades, lost on all 5 indices), H38 (1,080 + 1,800 variants) | failed |
| PCR (OI, volume) | R47 C4 (BANKNIFTY, 91 holdout trades, p 0.0499, q 0.50; faded to +9 a trade in 2025), R15 F3 | near-miss, non-default signal only |
| Max pain | R16 (forecast error 0.74% vs 0.43% for "no change"), EXPIRY_PLAYBOOK (64 expiries) | failed (folklore) |
| OI walls | OF1 (873 expiries, 0 of 12), R20b (57 holdout trades), R26 (770 expiries) | failed |
| ATM IV | VOLATILITY (249 days: range rises with IV), H29 (16 variants, best -Rs 30/day) | size only |
| IV rank / percentile | F1 S18 (158 trades, q 0.28, lost in design), OBUY_GC | failed (size only) |
| IV skew / smile | F1 (consistent but tiny direction clue), H29 (24 variants lose), R30 M4 (77 trades, q 0.109) | weak lead |
| IV term structure | H29 ("did not sort the move at all") | failed |
| India VIX | R15 F2 (228 trades, skipping low VIX cost -Rs 73/day), SIDEWAYS_GUARD, R16 | size only |
| Delta, gamma, theta, vega | F1 feature screen (gamma / theta size IC +0.15 / +0.10; delta / vega ~0 for direction) | context only; never a rule |
| GEX and gamma flip | R28 (0 of 10 filters), R36 (264 expiries, -Rs 1.04 lakh), R37 (42 trades, worse than 99.4% of random) | size only; failed as a filter |
| Dealer positioning | OF1 (3,632 days: 15 of 36 passed 2012-2025, 0 of 15 held in the last year) | failed |
| VWAP | TREND_FILTER, H46 (2,400 variants), F1 C1/C2 (Rs 2.5-3.3 lakh losses) | failed |
| ATR | F1 (strongest size predictor, IC +0.335) | size only |
| Opening range | R3 (2,489 trades, -Rs 2.23 lakh, 0 of 16), R49, R57 S4 (459 trades, -Rs 1.14 lakh) | failed |
| Value area / profile | R57 S3 (232 final trades, +Rs 49,360 but not fresh), R9 (0 of 56), H37 | one candidate setup; otherwise failed |
| Trend / range regime | SIDEWAYS_GUARD (35 tests), R16 (AUC 0.46-0.52) | failed |
| FII/DII, participant OI | OF1, H40 (9,600 variants, 0 pass) | failed |
| Futures basis | H38 (design pass, holdout -Rs 35/day) | failed |
| Rollover | H40 S10 | failed / barely tested; not on the phone |
| Event days | EVENT_SHIELD (BANKNIFTY RBI + Budget range 1.44% vs 0.98%, 15 sessions, holdout confirmed), H28 | size warning passed; no trade edge |
| News tone (positive / negative headlines) | h33 (3,072 variants, walk-forward -Rs 1.02 lakh), R61-N below (127 final trades; does not beat Solo 2.0, paired p 0.86) | failed; good pre-open news fading after the open is descriptive only |

What this means for Solo: every one of these is used only as **context**. It can make Solo skip a dear, crowded or
risky trade, or prefer one index's candidate over another's. It never creates a trade and never picks the side.
Solo can now say *why* in plain English, for example "skipped: IV is in the top fifth of its 60-day range and our IV
studies say dear options lose". That is the "understanding" Boss asked for. Part 2 measured whether it also earns money:
it does not, beyond what A2 already knows. Each concept's measured R61 result is in its CONCEPTS.md entry.

## Part 2: what was tested (pre-registered)

**Question.** Solo 2.0 (A2) looks at each day's candidate trades from four index setups and decides take or skip,
using 29 inputs. Does adding 30 more inputs (one or more from each concept above) pick the trades better on unseen
data?

**Setups (unchanged from R57).** Every signal comes from the index:

| id | setup | signal source |
|---|---|---|
| S1 | SoloMidday | index 1-min |
| S2 | Donchian-20 | index 5-min |
| S3 | open outside value and go | index 5-min + the previous day's profile |
| S4 | 15-min opening-range break | index 5-min |

C4 (the PCR signal) is not used.

**Labels and costs.** As R57:
- the held contract's own net P&L;
- real charges (STT 0.15% from 1 Apr 2026) and R17 fills;
- foreign rows and bad ticks removed;
- WEEK files only, so the same contract is never counted twice;
- Solo never trades an index on its own expiry day;
- only 20 Nov 2024 onwards.

**New inputs (all known at the signal minute).** All are context; how the phone gets each one is listed:

| block | inputs | how the phone gets them |
|---|---|---|
| CH, option chain (19) | near-ATM OI and its change, writer tilt, build-up code, PCR change, volume PCR, PCR 5- and 20-session ranks, max-pain distance, OI wall ahead / behind, IV rank (60 sessions), IV change today, smile curvature, term slope (NIFTY only), GEX net/gross and sign, gamma-flip distance, synthetic futures basis | live chain (`ChainAnalytics`); the ranks need 20-60 sessions of the phone's own stored history |
| GK, Greeks of the contract Solo would buy (4) | delta, gamma per 1%, theta per session as % of premium, vega as % of premium | from the contract's own IV |
| CX, context (7) | VIX 60-session rank, position in yesterday's value area, RBI / Budget day, the session after a US FOMC / CPI release, another index's expiry today, FII index-futures long share and its change (previous evening's NSE file) | the FOMC / CPI dates would need a small static list on the phone; the participant file is recorded daily |

**Not used as inputs, and why:**
- real futures basis and futures build-up: futures minutes exist only from Jul 2026;
- rollover: not built, and not on the phone;
- DII cash flows: not reachable;
- 25-delta skew: the ±3-strike skew is used instead;
- India CPI: not in a pre-listed calendar.

**Seven variants, one BH family.**

| id | model | inputs |
|---|---|---|
| V1 | A2 refitted (the comparator) | A2's 29 |
| V2 | logistic | everything (59) |
| V3 | logistic | A2 + Greeks + context, no chain |
| V4 | logistic | A2 + chain |
| V5 | depth-3 boosted trees | everything |
| V6 | depth-3 boosted trees | A2 + Greeks + context, no chain |
| V7 | sparse L1 logistic | everything; it picks its own concepts |

All use A2's take rule: the first candidate of the day scoring at least the training 70th percentile, then nothing
more that day.

**Splits and reports.**
- Design: 21 Nov 2024 - 31 Mar 2026, purged walk-forward with monthly refits and a 1-session embargo.
- Final: 1 Apr 2026 - latest, read once.
- Reports:
  - AUC: random split (leaky) vs walk-forward;
  - a drop-one-concept ablation and an add-one-feature test, so each concept's out-of-sample worth is measured;
  - trades, net, p, q, max drawdown, top-5 share, 2x costs, per index and per setup.

**Gates.** As R47-R60: final net > 0, twin p < 0.05, BH q ≤ 0.10, 40+ trades, top-5 < net, design walk-forward > 0.

**Replacing Solo 2.0.** Passing the gates is not enough.
- The candidate is the passing V2-V7 with the best DESIGN walk-forward net.
- It replaces A2 only if, on the same final days against V1, all four hold:
  - it makes more money;
  - it has a higher AUC;
  - a paired day bootstrap gives p < 0.10;
  - it makes more without MIDCPNIFTY (so it does not just lean harder on one index).

**Honest warning before any result.** The final window (Apr-Oct 2026) is not fresh. R57 already read it for A2 with
these same setups, and R47-R55 read parts of it. R61's final test can only say whether the extra inputs add anything
on a window where A2's result is known. Live paper from 15 Oct 2026 is the only truly unseen data.

## Results

**Data.** The Drive lake was copied on 15 Oct 2026; its last sessions are 14 Oct (NIFTY, BANKNIFTY) and 13 Oct
(FINNIFTY, MIDCPNIFTY). It has 471 / 471 / 470 / 470 option sessions from 21 Nov 2024, and 5,780 design candidate
rows. Cleaning reproduced R57's table exactly:

| index | foreign rows | bad ticks | strike > 12 steps from spot |
|---|---|---|---|
| NIFTY | 0 | 4,905 | 2 |
| BANKNIFTY | 440 | 590 | 5 |
| FINNIFTY | 115,698 | 1,323 | 43,571 |
| MIDCPNIFTY | 0 | 1,207 | 15,463 |

Participant OI covered all 471 sessions (686 NSE files, none damaged). Share of candidate rows that had each input:
- the gamma flip was found within ±3% on 60% (design) / 49% (final);
- term slope exists only for NIFTY, 22% / 23%;
- the 60-session IV and VIX ranks had history on 88% of design rows;
- everything else 94-100%.

**Trading results.** Net is in Rs, 1 lot, after real costs. "Design WF" is the 9 out-of-fold months,
Jul 2025 - Mar 2026. The final test is 1 Apr - 14 Oct 2026, read once.

| variant | inputs | design WF: trades, net | final: trades, win | final net (Rs/trade) | 2x costs | max DD | months green | top-5 share | AUC random / WF / final | p | q | without MIDCPNIFTY | gates |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **V1** | A2's 29 (= Solo 2.0) | 166, +55,910 | 128, 59% | **+99,172 (+775)** | +80,849 | 18,056 | 6/7 | 40% | 0.557 / 0.525 / 0.538 | 0.002 | 0.003 | +27,586 | PASS |
| V2 | all 59, logistic | 163, +47,269 | 118, 57% | +60,399 (+512) | +43,867 | 25,362 | 4/7 | 61% | 0.562 / 0.518 / 0.524 | 0.002 | 0.003 | +28,396 | PASS |
| **V3** (candidate) | A2 + Greeks + context, logistic | 167, +79,505 | 119, 57% | +65,219 (+548) | +48,214 | 17,631 | 5/7 | 56% | 0.562 / 0.529 / 0.537 | 0.004 | 0.004 | +35,245 | PASS |
| V4 | A2 + chain, logistic | 169, +66,676 | 127, 54% | +58,169 (+458) | +40,629 | 25,891 | 4/7 | 68% | 0.560 / 0.514 / 0.525 | 0.002 | 0.003 | +21,772 | PASS |
| V5 | all 59, trees | 154, +50,992 | 122, 59% | +71,787 (+588) | +56,170 | 15,202 | 5/7 | 50% | 0.577 / 0.522 / 0.527 | 0.002 | 0.003 | +48,680 | PASS |
| V6 | A2 + Greeks + context, trees | 152, +28,069 | 120, 57% | +50,878 (+424) | +35,660 | 18,824 | 5/7 | 63% | 0.584 / 0.518 / 0.530 | 0.002 | 0.003 | +34,658 | PASS |
| V7 | all 59, L1 logistic | 158, +50,345 | 117, 55% | +59,941 (+512) | +43,543 | 20,560 | 5/7 | 61% | 0.551 / 0.519 / 0.525 | 0.004 | 0.004 | +22,772 | PASS |
| plain SoloMidday (reference) | - | - | 36 | -21,589 | - | - | - | - | - | - | - | - | reference |

What the p means:
- Each variant is compared with 500 random-entry twins, which on average lost Rs 19k-27k.
- The selection-twin p (random picks from the same day's candidates) was 0.002-0.010 for every variant.
- BH is over the 7 variants. V1's q is lower than in R57 because the family is smaller.

Final test per index and per setup (trades, net Rs):

| variant | NIFTY | BANKNIFTY | FINNIFTY | MIDCPNIFTY | S1 | S2 | S3 | S4 |
|---|---|---|---|---|---|---|---|---|
| V1 | 8, +9,973 | 12, +1,912 | 26, +15,701 | 82, +71,586 | 3, +7,523 | 31, +26,255 | 92, +63,937 | 2, +1,457 |
| V2 | 10, +3,437 | 16, +14,543 | 21, +10,417 | 71, +32,002 | 2, -2,344 | 25, +15,399 | 89, +48,367 | 2, -1,024 |
| V3 | 11, +2,022 | 11, +10,853 | 22, +22,370 | 75, +29,974 | 3, -1,229 | 24, +13,437 | 91, +52,242 | 1, +769 |
| V4 | 12, +420 | 18, +9,231 | 22, +12,121 | 75, +36,398 | 1, -2,869 | 30, +20,256 | 94, +43,915 | 2, -3,132 |
| V5 | 34, -9,440 | 35, +28,777 | 20, +29,343 | 33, +23,107 | 0 | 108, +74,945 | 13, +2,591 | 1, -5,749 |
| V6 | 36, +1,058 | 24, +19,481 | 26, +14,119 | 34, +16,220 | 1, -5,194 | 107, +66,123 | 12, -10,051 | 0 |
| V7 | 12, -2,142 | 17, +13,372 | 30, +11,543 | 58, +37,168 | 3, -9,126 | 19, +8,824 | 95, +60,243 | 0 |

The two model families pick different trades:
- The logistic variants lean on S3 ("open outside value and go"), as A2 does.
- The trees lean on S2 (Donchian) and spread across indices.
- Unfiltered, the setups made: S1 89 trades +1,329; S2 1,468 +49,733; S3 232 +49,360; S4 459 -1,14,070.

**What each concept added, out of sample** (`results/design.json` and `results/final.json`, "ablation"; every
concept's numbers are also written into its CONCEPTS.md entry):

| concept (R61 inputs) | AUC change when removed from V2 (design / final) | each input added alone to A2 (design / final) |
|---|---|---|
| OI level / change | +0.001 / +0.001 | `oi_near_log` -0.001 / -0.001; `oi_near_chg_pct` +0.001 / +0.000; `writer_tilt30` +0.000 / -0.001 |
| build-up | -0.001 / +0.000 | `buildup` +0.002 / +0.001 |
| PCR (change, volume, ranks) | -0.002 / +0.001 | `pcr_oi_chg30` +0.005 / -0.001; `pcr_vol` -0.001 / +0.001; `pcr_rank5` -0.002 / -0.001; `pcr_rank20` -0.003 / -0.002 |
| max pain | +0.001 / +0.001 | `maxpain_dist_pct` +0.000 / -0.006 |
| OI walls | +0.000 / +0.001 | `wall_ahead_pct` -0.000 / +0.000; `wall_behind_pct` +0.000 / -0.001 |
| IV change, IV rank | +0.004 / +0.002 | `iv_chg_day` -0.002 / -0.000; `iv_rank60` -0.007 / +0.000 |
| smile, term structure | +0.003 / +0.002 | `smile_curv` +0.000 / -0.004; `term_slope` -0.000 / +0.002 |
| VIX rank | +0.003 / +0.001 | `vix_rank60` -0.001 / +0.000 |
| Greeks of the held contract | -0.003 / +0.001 | `delta_abs` +0.002 / +0.000; `gamma_1pct` +0.004 / +0.005; `theta_pct_day` -0.001 / -0.002; `vega_pct` -0.000 / -0.001 |
| GEX, gamma flip | +0.002 / +0.001 | `gex_norm` -0.002 / -0.004; `gex_sign` +0.000 / -0.003; `flip_dist_pct` -0.000 / -0.001 |
| synthetic basis | +0.001 / -0.003 | `synth_basis_bps` -0.000 / +0.001 |
| value area position | +0.001 / -0.000 | `va_pos` -0.002 / -0.001 |
| events; other index's expiry | -0.002 / +0.000; -0.002 / -0.001 | `event_rbi_budget` +0.001 / -0.003; `post_us_event` +0.001 / +0.002; `other_expiry_today` +0.005 / -0.001 |
| FII futures positioning | +0.002 / -0.002 | `fii_fut_long_share` -0.001 / +0.001; `fii_fut_long_chg` -0.003 / +0.000 |

A positive "removed" number means the model was better WITHOUT the concept. Every change is under 0.01, which is
noise on these samples. The money effects of removing a group ranged from -Rs 23k to +Rs 18k in design and from
-Rs 15k to +Rs 19k in final, with no group consistent across both.

Block-level comparisons:

| comparison | design | final | reading |
|---|---|---|---|
| option-chain block, logistic: V2 (with) vs V3 (without) | +47,269 vs +79,505 | +60,399 vs +65,219 | the chain block hurt or did nothing |
| option-chain block, trees: V5 (with) vs V6 (without) | +50,992 vs +28,069 | +71,787 vs +50,878 | the chain block helped |

There is no consistent effect.

The L1 model (V7) kept these inputs with the largest weights:
- theta and gamma of the held contract (+);
- NIFTY (-);
- the last 15 minutes in the trade's direction (+);
- the day's range vs its 10-day average (-);
- IV rank (-).
Its out-of-sample AUC was still only 0.519 / 0.525.

**What this means.** A2's 29 inputs already hold what the option chain can tell this meta-labeller: dear IV (A2's ATM
IV), PCR, skew and VIX are in them. The extra concepts are real, well-defined numbers, and Solo can explain each one.
But they add no measurable skill in picking among S1-S4 candidates after costs, which matches what R26-R47 found
about these concepts one by one.

## R61-N: does positive / negative news help Solo? (Boss, 15 Oct: "also check if there is any positive/negative news")

**No.** Neither as a trade trigger (h33 already showed that) nor as context for Solo's take / skip.

**What was already known.** h33 (`HUNT_H33.md`; 3,072 variants, 59,189 timed headlines 2020-2026) tested trading
intraday headlines and price shocks. The walk-forward lost -Rs 1.02 lakh, because the price moves 1-4 minutes before
the headline appears.

**What R61-N added.** It was pre-registered in `hunt/r61/NEWS_PREREG.md` (commit 4a9725a0) before any news was scored.
- Data: every Moneycontrol business headline from Oct 2024 to Oct 2026, from its public monthly sitemaps. That is
  116,570 headlines (headline text from the URL, time = the sitemap's last-modified time, which is never early);
  13,782 of them are market-wide.
- Tone: scored with the phone's own reader (`News.tone` / `News.tag`, ported word for word; a test checks the word
  lists against `News.kt`).
- Inputs: the mean tone of the headlines out between the previous close and Solo's signal minute (signed by the trade's
  side), and their count. Every candidate had at least one such headline.

**Results.** Net in Rs, 1 lot, after costs.

| variant | design WF: net, AUC | final: trades, win | final net | 2x costs | max DD | AUC | p / q | without MIDCPNIFTY | gates | beats Solo 2.0? |
|---|---|---|---|---|---|---|---|---|---|---|
| V1 = Solo 2.0 | +55,910, 0.525 | 128, 59% | +99,172 | +80,849 | 18,056 | 0.538 | 0.002 / - | +27,586 | PASS | - |
| N1: + tone + count | +65,756, 0.523 | 126, 56% | +78,317 | +60,357 | 24,148 | 0.542 | 0.002 / 0.002 | +17,011 | PASS | no (paired p 0.94) |
| N2: + tone | +71,463, 0.526 | 127, 58% | +85,483 | +67,357 | 18,235 | 0.544 | 0.002 / 0.002 | +19,461 | PASS | no (paired p 0.86) |

What these numbers say:
- News lifted the design profit, almost all of it on MIDCPNIFTY: V1 -153 → N2 +22,528.
- News nudged AUC up by 0.002-0.006, which is noise.
- On the final days both news versions made less than Solo 2.0, and less without MIDCPNIFTY.
- This final window has now been used three times (R57, R61, R61-N), so it is weak evidence either way.

**One descriptive finding (not a rule, not gated).** The tone of the news out before 09:15 runs AGAINST the day's
open-to-close move. Correlation on 337 design days:

| NIFTY | BANKNIFTY | FINNIFTY | MIDCPNIFTY |
|---|---|---|---|
| -0.16 | -0.16 | -0.16 | -0.09 |

Good overnight news tends to be priced into the opening gap, and the index then gives some of it back. This fits h33
("the price moves before the headline"). It would need its own pre-registered test before anyone traded on it. Our
earlier gap-fade studies are the place to start.

**What Solo does with news.** Jarvis keeps reading and explaining the news (the "News tone" entry in CONCEPTS.md).
Solo's trade decisions do not use it.

## Running it on the phone (only if a variant ever replaces Solo 2.0)

- No R61 model replaces Solo 2.0, so nothing is to be ported. All 7 are exported anyway (`hunt/r61/results/models/`,
  2.9-86 KB each, parity gap with scikit-learn at most 1.1e-16) as R57's small JSON, with a one-line meaning for every input. It comes with 25 golden vectors.
- `hunt/r61/r61eval.py` is a pure-Python reference with no numpy, file or network. It holds:
  - the scorer;
  - every chain formula (Black-Scholes Greeks, max pain, OI walls, PCR, build-up code, GEX, gamma flip, synthetic
    basis).
- Tests prove it matches the research code feature by feature, so a Kotlin port can be checked line by line.
- The phone's own `ChainAnalytics.gex` prices from the forward and takes its own rate. A port must use R61's exact
  definition or re-test.
- Calendar note: the app's EventCalendar has the Aug 2026 RBI policy on 6 Aug, but the research list says 7 Aug.
  R61 uses the research list. One of the two is wrong and should be checked on rbi.org.in.

## How it was run (re-run)

```
L=~/.cache/iraalgo/lake
rclone copy gdrive:IraAlgo-data-lake/options_rolling_1m $L --include "opt_{NIFTY,BANKNIFTY,FINNIFTY,MIDCPNIFTY}_WEEK_202[456].parquet" \
    --include "opt_NIFTY_MONTH_202[456].parquet"
rclone copy gdrive:IraAlgo-data-lake/index_1m $L --include "index_1m_{NIFTY,BANKNIFTY,FINNIFTY,MIDCPNIFTY,INDIAVIX}_202[456].parquet"
rclone copy gdrive:IraAlgo-data-lake/participants/oi /tmp/pz --include "oi_202[456].zip"   # unzip -j into $L/participants/oi
python research/hunt/r61/r61data.py build && python research/hunt/r61/r61data.py context
python research/hunt/r61/r61study.py design      # writes results/design.json (design months only)
python research/hunt/r61/r61study.py dryrun      # the final pipeline on the last 3 design months (crash check)
python research/hunt/r61/r61study.py final       # the ONE final read (guard file; refuses a second)
python research/hunt/r61/r61report.py              # each concept's measured result -> CONCEPTS.md / concepts.json
```

Build 21 minutes, design and final about 1.5 minutes each. The lake copy and the cache were deleted afterwards.
`final` now refuses to run (results/FINAL_READ).

## Tests

`python -m pytest research/hunt/r61/test_r61.py -q`: 35 tests on synthetic data (4 of them for R61-N's news port and windows), run under pandas 3.0.5 / sklearn 1.9
and under the pinned pandas 2.2.3 / sklearn 1.5.2. They cover:
- the look-ahead guard for every chain feature (values at a minute are identical when later minutes are deleted);
- same-minute ranks that only look back;
- context built from earlier sessions only;
- snapshot carry-forward that never mixes two minutes;
- hand-checked max pain, OI walls, build-up codes and gamma flip;
- Greeks against finite differences;
- numpy vs pure-Python parity;
- signing by side;
- the event calendar against its research sources, including the next-session rule across holidays;
- purge and embargo;
- held-contract labels left untouched;
- the period guard and the one-read guard;
- the replace rule;
- scorer parity for all three model kinds;
- cleaning on a tiny parquet lake;
- edge cases: holidays, expiry day, missing chain minutes, no chain at all.

Signal source of every rule: S1-S4 read the underlying index. Every R61 input is context or a filter. No rule here
reads its signal from an option's own chart.
