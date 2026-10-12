# HUNT R14: ORB's re-entry churn - cap the re-entries, or "let it run"?

Written 12 Oct 2026 by R14 for Boss. Option BUYING only, Rs 1 lakh, 1 lot, BANKNIFTY ATM, real costs. Design period =
4 Aug 2021 - 30 Sep 2025. Holdout = 1 Oct 2025 - 5 Oct 2026, read ONCE for the picked rule (`HOLD_READ`:
12 Oct 2026 07:09 UTC, after `research/hunt/r14/PREREG.md` was written and hashed, sha256 `0c8b57f4...e576`).

- Code: `research/hunt/r14/`
  - `data.py`: every ORB / ORB Fresh candidate entry (each 5-minute bar beyond the range) with its option minutes;
  - `study.py`: the six pre-registered rules, the churn numbers, the tests, the pick, the one holdout read;
  - `posthoc.py`: information only, after the holdout read (exits by kind, random twins);
  - `audit.py`: the other strategies' churn (Part B);
  - `PREREG.md`: the frozen rules.
- Outputs (about 20 MB, mostly the option-minute cache): `scratchpad/hunt/r14/`.
- Exits, fills and costs reuse h20's simulator (`research/hunt/h20/sim.py`, mode "fixed" = the app since 07 Oct: one resting
  SL-M at max(-40, the profit lock), the lock earned on minute highs, the +40 target checked on a minute's high and sold at the
  next minute's open, 15:10 square-off). Fills: the app's paper fills (+-5 bps, stops -10 bps) plus a 1-tick half-spread
  (2 ticks on stops). Charges: the app's model at today's rates - Rs 20 brokerage an order, STT 0.15% on the sell, exchange
  0.03553%, SEBI, stamp 0.003% on the buy, GST 18%.
- No secrets were read or printed.

## The answer in plain English

1. **Boss is right about the churn.** Without a cap the ORB re-enters on the very next bar while BANKNIFTY stays beyond its
   opening range. It averaged **9 entries a day** (max 23, all on one side). On 727 of 868 design days it bought the same side
   3 or more times. ORB Fresh churns less (2.3 a day, max 7), because it needs the price to come back inside first.
2. **The small profits Boss sees are the profit lock, not the target.**
   - The +10 rung exits make Rs 146-224 gross, Rs 70-110 after charges.
   - The breakeven rung exits make about Rs 7-20 gross and LOSE Rs 65-93 after charges.
   - The +40 target exits (13% of trades) make Rs 870-1,330 gross; charges eat only 8% of them.
3. **The re-entries made nothing before charges.**
   - Design, ORB: the 6,858 trades after the first on each side made -Rs 39,872 gross in all (about Rs -6 a trade).
   - Their charges were Rs 5.13 lakh. Charges are 36% of all the winners' gross profit.
   - So every re-entry is about Rs 75-110 of charges for nothing.
4. **Capping the re-entries is what helps, not removing the target.**
   - Cap at 1 entry a side a day: ORB's design loss falls from -Rs 6.50 lakh to -Rs 0.97 lakh. Its holdout loss falls from
     -Rs 1.89 lakh to -Rs 0.18 lakh. Orders fall from 15,640 to 1,924.
   - ORB Fresh: -Rs 2.00 lakh to -Rs 0.97 lakh (design), -Rs 38k to -Rs 18k (holdout).
   - Both picks were confirmed in the holdout (ORB p < 0.001; ORB Fresh p 0.054).
5. **Boss's idea, "remove the target and let it run", does not help.**
   - At +40 the stop moves to +30, then trails 75% of the best gain.
   - With today's re-entry rule: ORB +Rs 14k over 4 years (p 0.16, not significant), ORB Fresh +Rs 0.5k.
   - In the holdout: ORB -Rs 0.4k, ORB Fresh +Rs 1.9k.
   - Few trades reach +40 (13%), and those that run past it give much of it back. Removing the target does not cut the
     orders: a trailed exit is followed by a re-entry just the same.
   - "Run + cap" (R4) is the same as the cap alone (+Rs 110 over 4 years).
6. **Honest caveat: the cap is a damage limiter, not an edge.**
   - Both arms still lose with it.
   - The first entry of the day is NOT a better trade than the later ones. Dropping the same number of trades at random would
     have done about as well (random-twin p 0.84 design, 0.31 holdout for ORB).
   - The cap works because every ORB trade has about zero edge before charges, so fewer trades means fewer charges.

**Bottom line for Boss:**
- ORB and ORB Fresh: **at most 1 entry a side a day** - the PAPER default from the next session, through the strategy lab
  (undo on the card).
- Live: **proposed** with your PIN (it only lowers risk and charges).
- No "let it run" rule.
- The arm says why it does not re-enter, and its row shows the charges paid today and this month.

---

## 1. The churn (baseline R0 = the app today), per trade, Rs

| | ORB design | ORB holdout | ORB Fresh design | ORB Fresh holdout |
|---|---|---|---|---|
| days with a candidate | 868 | 214 | 868 | 214 |
| entries a trade day (max) | 9.0 (23) | 8.9 (21) | 2.3 (7) | 2.2 (7) |
| days with 3+ entries on one side | 727 | 184 | 277 | 69 |
| trades / orders | 7,820 / 15,640 | 1,904 / 3,808 | 1,980 / 3,960 | 474 / 948 |
| gross / charges / net (all) | -0.68 / 5.82 / -6.50 lakh | +0.17 / 2.06 / -1.89 lakh | -0.58 / 1.42 / -2.00 lakh | +0.12 / 0.50 / -0.38 lakh |
| charges as % of the winners' gross | 36.4% | 35.0% | 36.5% | 35.0% |
| 1st entry on a side: n, gross, net a trade | 962, -29, -101 | 246, +31, -75 | 962, -29, -101 | 246, +31, -75 |
| later entries on a side: n, gross, net a trade | 6,858, -6, -81 | 1,658, +6, -103 | 1,018, -30, -102 | 228, +17, -87 |
| same side right after a +40 target: n, gross, net a trade | 908, +1, -76 | 239, +48, -66 | 5, -106, -191 | 1, +594, +526 |

Charges a trade: about Rs 72-77 in the design years and Rs 104-115 in the holdout (BANKNIFTY premiums and lots were larger).

**R0's exits by kind** (design ORB; the holdout is alike):

| exit | n | gross a trade | charges | net a trade |
|---|---|---|---|---|
| lock at breakeven | 2,831 | +7 | 74 | -67 |
| lock at +10 | 1,544 | +146 | 76 | +70 |
| lock at +20 / +30 | 708 | +383 | 75 | +308 |
| +40 target | 1,019 | +917 | 76 | +840 |
| -40 stop | 1,714 | -885 | 73 | -957 |

The "Rs 100-200 profit" trades are the +10 rung exits: about Rs 70-110 after charges. The breakeven rung, which is built
to cover charges, still loses about Rs 67-93 after the spread and slippage. Then the arm buys again on the next bar.

## 2. The candidates (pre-registered, `PREREG.md`) and the results

| id | rule |
|---|---|
| R0 | today: re-enter from the bar after the exit, no cap; -40 / ladder / +40 / 15:10 |
| R1 | at most 1 entry per side (CE / PE) a day |
| R2 | at most 2 entries per side a day |
| R3 | let it run: no +40 target; from +40 the lock is entry + 75% of the best gain (+30 at +40), only rising; re-entries as today |
| R4 | R3 + at most 1 entry per side a day |
| R5 | hybrid: a side's first trade books +40; a same-side re-entry after a target trails as R3; at most 2 a side |

**Design** (4 Aug 2021 - 30 Sep 2025). Delta = against R0; one-sided paired t on daily nets; BH over the 10 tests; years
positive out of 5:

| arm | rule | trades | orders | win % | charges | net | max DD | worst day | delta | p (t) | BH q | years + |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ORB | R0 | 7,820 | 15,640 | 36.9 | 5,82,478 | -6,50,080 | -6,57,479 | -8,035 | | | | |
| ORB | **R1** | 962 | 1,924 | 37.4 | 69,010 | **-96,740** | -1,00,856 | -2,674 | **+5,53,340** | <0.001 | <0.001 | 5/5 |
| ORB | R2 | 1,806 | 3,612 | 36.7 | 1,30,568 | -1,85,522 | -1,89,520 | -3,862 | +4,64,558 | <0.001 | <0.001 | 5/5 |
| ORB | R3 | 7,773 | 15,546 | 36.9 | 5,78,813 | -6,36,053 | -6,46,064 | -8,289 | +14,027 | 0.162 | 0.180 | 4/5 |
| ORB | R4 | 962 | 1,924 | 37.4 | 69,010 | -96,630 | -1,01,494 | -2,674 | +5,53,450 | <0.001 | <0.001 | 5/5 |
| ORB | R5 | 1,806 | 3,612 | 36.7 | 1,30,574 | -1,82,452 | -1,86,450 | -3,862 | +4,67,628 | <0.001 | <0.001 | 5/5 |
| ORB Fresh | R0 | 1,980 | 3,960 | 36.9 | 1,41,705 | -2,00,058 | -2,04,515 | -3,862 | | | | |
| ORB Fresh | **R1** | 962 | 1,924 | 37.4 | 69,010 | **-96,740** | -1,00,856 | -2,674 | **+1,03,318** | <0.001 | <0.001 | 5/5 |
| ORB Fresh | R2 | 1,515 | 3,030 | 36.5 | 1,08,942 | -1,63,100 | -1,67,567 | -3,991 | +36,958 | 0.002 | 0.003 | 5/5 |
| ORB Fresh | R3 | 1,980 | 3,960 | 36.9 | 1,41,706 | -1,99,531 | -2,04,572 | -3,862 | +528 | 0.459 | 0.459 | 2/5 |
| ORB Fresh | R4 | 962 | 1,924 | 37.4 | 69,010 | -96,630 | -1,01,494 | -2,674 | +1,03,428 | <0.001 | <0.001 | 5/5 |
| ORB Fresh | R5 | 1,515 | 3,030 | 36.5 | 1,08,941 | -1,63,339 | -1,67,805 | -3,991 | +36,720 | 0.002 | 0.003 | 5/5 |

With a cap of 1, ORB and ORB Fresh take the same trades: the first break on a side is always a fresh one.

R1 and R4 are within 5% of each other. The pre-registered tie rule picks the lower-risk one, **R1, for both arms**
(`picks.json`, frozen before the holdout read).

**Net by year (Rs; design):**

| arm | rule | 2021 (Aug-Dec) | 2022 | 2023 | 2024 | 2025 (Jan-Sep) |
|---|---|---|---|---|---|---|
| ORB | R0 | -63,773 | -1,64,277 | -1,05,422 | -1,61,194 | -1,55,414 |
| ORB | R1 | -1,538 | -40,534 | -29,514 | -11,741 | -13,413 |
| ORB | R3 | -58,122 | -1,61,619 | -97,469 | -1,56,813 | -1,62,030 |
| ORB Fresh | R0 | -23,853 | -60,146 | -36,996 | -33,247 | -45,816 |
| ORB Fresh | R1 | -1,538 | -40,534 | -29,514 | -11,741 | -13,413 |

**Holdout** (1 Oct 2025 - 5 Oct 2026, 214 days). The picks were read first:
- ORB R1: delta +Rs 1,70,652, p < 0.001 - **CONFIRMED**;
- ORB Fresh R1: delta +Rs 19,779, p 0.054 (bootstrap 0.049) - **CONFIRMED** (bar: p < 0.10).

Then every candidate, for information only:

| arm | rule | trades | orders | win % | charges | net | max DD | worst day | delta | p (t) |
|---|---|---|---|---|---|---|---|---|---|---|
| ORB | R0 | 1,904 | 3,808 | 37.6 | 2,06,380 | -1,89,091 | -1,92,447 | -8,231 | | |
| ORB | **R1** | 246 | 492 | 38.2 | 26,104 | **-18,439** | -28,126 | -2,161 | **+1,70,652** | <0.001 |
| ORB | R2 | 460 | 920 | 35.9 | 49,128 | -38,698 | -48,750 | -4,036 | +1,50,394 | <0.001 |
| ORB | R3 | 1,892 | 3,784 | 37.3 | 2,04,840 | -1,89,512 | -1,93,000 | -8,231 | -421 | 0.514 |
| ORB | R4 | 246 | 492 | 38.2 | 26,100 | -20,405 | -27,766 | -2,161 | +1,68,686 | <0.001 |
| ORB | R5 | 460 | 920 | 35.9 | 49,138 | -33,599 | -47,666 | -4,036 | +1,55,492 | <0.001 |
| ORB Fresh | R0 | 474 | 948 | 38.4 | 49,772 | -38,218 | -41,071 | -4,198 | | |
| ORB Fresh | **R1** | 246 | 492 | 38.2 | 26,104 | **-18,439** | -28,126 | -2,161 | **+19,779** | 0.054 |
| ORB Fresh | R2 | 373 | 746 | 38.1 | 39,433 | -32,827 | -36,934 | -3,141 | +5,391 | 0.255 |
| ORB Fresh | R3 | 474 | 948 | 38.4 | 49,776 | -36,277 | -41,041 | -4,198 | +1,941 | 0.310 |
| ORB Fresh | R4 | 246 | 492 | 38.2 | 26,100 | -20,405 | -27,766 | -2,161 | +17,813 | 0.076 |
| ORB Fresh | R5 | 373 | 746 | 38.1 | 39,436 | -31,351 | -35,459 | -3,141 | +6,867 | 0.205 |

## 3. The honesty checks (information only, after the holdout read)

- **Random twins** (R12's check). The question: is the cap better than dropping the same number of R0 trades at random?
  - ORB: no. Design p 0.84: the first entries lost Rs 101 a trade against Rs 81 for the later ones. Holdout p 0.31.
  - ORB Fresh: p 0.49 / 0.45.
  - So the cap does not pick better trades. It removes trades that, as a group, made about Rs 0 before charges.
- **What the cap gives up.**
  - ORB design: the dropped trades' winners made +Rs 10.9 lakh net; their losers lost more.
  - On a strong trend day the cap misses the second and third +40.
  - Over all days the dropped trades netted -Rs 5.53 lakh.
- **Multiple testing.** 10 pre-registered tests, BH at q < 0.10. The caps pass at q < 0.003; the trail rules do not
  (q 0.18 / 0.46).
- **Same data, earlier reads.** The holdout window was read by R12 for exit-manager rules. Re-entry caps and trails were
  never tested there, and no R14 rule was fitted to it.
- **Model limits.**
  - Charges use today's rates (STT 0.15%) for every year. Earlier years paid less STT, so the charges share is a little
    high for 2021-2024.
  - The half-spread is modelled, not measured.
  - 868 + 214 days have a usable index and option series. Missing days are skipped for every rule alike.

**Confidence:**
- HIGH that the re-entries cost charges for nothing. The effect is huge, consistent in all 5 design years, and confirmed
  in the holdout.
- HIGH that "let it run" does not help.
- LOW that ORB / ORB Fresh have any edge at all, capped or not. They still lose.

## 4. Part B - the other strategies (same churn question)

Re-entry rules from the code (12 Oct 2026). Charges share and re-entry numbers come from R12's rebuilt trades
(`audit.py`, real costs, design / holdout).

| strategy | max entries a day | re-entry after an exit | same-side re-entry right after a target? | charges / winners' gross | verdict |
|---|---|---|---|---|---|
| ORB | none before R14 (now 1 a side on paper) | the next bar | yes: 908 in design, 239 in holdout | 36% / 35% | **churn: capped (R14)** |
| ORB Fresh | none before R14 (now 1 a side on paper) | the next fresh break | rare (5 / 1) | 37% / 35% | **churn: capped (R14)** |
| ORB Sweep | 2 a day (SweepRules) | the next bar | rare: 10 design (-Rs 5.8k), 3 holdout | 24% / 27% | already capped; no further test |
| Range Fade | 2 a day (RangeFadeRules) | the next bar | 31 design (-Rs 86), 10 holdout | 40% / 39% | already capped. Charges are high because the arm loses, not because it churns |
| Liquidity 15+5 (books) | one position per book; about 1.6 a day, max 5 | the next qualifying break | 40 design (+Rs 2.5k), 15 holdout (+Rs 13.6k) | 12% / 12% | no churn problem: those re-entries make money |
| VIX divergence | 1 a day per index | none (first signal a day) | no | 5-10% | no churn |
| Hero | 1 a day ([HeroRules.mayEnter]) | none | no | 1% | no churn |
| Night arm | 1 decision a day per index | none | no | not testable (no data) | no churn |
| IraSolo (midday) | 1 trade a day, no re-entry | none | no | not testable here | no churn |
| Pine auto (scripts) | no count cap; enters only when the script's signal CHANGES | a target / lock exit does not re-enter; the signal must change first | only if the script itself flips back | not testable (Boss's scripts) | no churn from the app; a choppy script can churn by its own logic (per-script daily loss limit applies) |
| GrammarArm / forge paper ideas | 1-2 a day per idea (`maxTrades`), at most 3 ideas at once | after a decision minute is used | at most once more | not testable (no data) | capped by design |
| MCX paper arms | eve breakout 1 a day; SILVERM morning call on a 15-minute grid with a 4-hour hold (max about 2); silver 1 a day; trend legs monthly | morning call: the next grid time after its 4-hour exit; others none | no target (time exits) | not testable (no data) | no churn |
| Jarvis paper trades | at most 2 a day (NewsTrade.MAX_A_DAY) | a new suggestion | not automatic | not testable | no churn |

Only ORB and ORB Fresh churn. Nothing else needs a cap.

## 5. What changed in the app (R14)

- **Engine:**
  - `OrbParams.sideCap` (0 = no cap, the default);
  - `OrbRules.entrySignal(..., taken)` refuses a break on a side whose entries are used up, with status
    `side_cap_reached:CE:1:1`;
  - `ReentryCap` holds the rule, its words, the evidence and the charges line;
  - `ArmsBacktest` and `Replay` honour the cap.
  - Exits are untouched: the -40 stop, the profit lock, the 15:10 square-off and the 15:05 expiry exit.
- **Strategy lab:**
  - `sideCap` is an ORB / ORB Fresh parameter (0-3; lifting it counts as a risk raise, like a wider stop);
  - `VariantLab.research` puts R14 through the lab's own path once. PAPER is applied by itself from the next session (kind
    RESEARCH, undoable on the card, the safety net never lifts it). LIVE is only PROPOSED while the arm trades live, and is
    applied only with Boss's PIN; undoing it later also needs the PIN.
  - Until the lab's book has its own paper tune for the arm, the arm's paper entries use the cap by default.
- **Arms and Jarvis:**
  - The status reads "Not re-entering CE: 1 of 1 entry on that side used today; saves charges (research R14 ...)".
  - Jarvis's account facts say the same.
  - The armed message names the cap.
  - Each arm's row has "Show charges": "Charges ₹X today · ₹Y this month (N orders)" and the cap in force on paper and live.
- **Live:** nothing changes by itself. The cap reaches Zerodha only through Boss's PIN on the Strategy lab proposal.
