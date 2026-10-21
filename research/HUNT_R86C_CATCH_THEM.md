# R86c: six out-of-the-box ways to catch the big players, and every early seller pattern

*19 Oct 2026. Research only; nothing in the app changes. Pre-registration: `research/hunt/r86/PREREG_C.md` (commit
6f7c61f) and amendment C-A1 for the seller work (commit e603e38), both pushed before any outcome. Code:
`research/hunt/r86/r86c.py`, tests `test_r86c.py`. Results: `research/hunt/r86/results/c_design.json`. Follows R86
and R86b.*

Boss, 19 Oct: *"Is there any way we could catch it, think out of the box"*, *"run all of them, 1 to 5"*, and *"can't
you find all the patterns when the seller is selling, so that we could catch it"*.

## The answer for Boss

**We can RECOGNISE a big seller early, and a big buyer by noon, much better than chance. But none of the six ways
to trade it passed: 0 of 12 tests.** The final window (2025 - Oct 2026) was not read (pre-registered). No APP_SPEC.

1. **Seller patterns (the catalogue).** By 09:45, three patterns mark a heavy FII selling day 2-3.5 times more often
   than chance:
   - a gap down of 0.5% or more;
   - a gap down with no recovery in the first 30 minutes;
   - India VIX up 5% or more.
   They still catch only 13-36% of the selling days, and most flagged days are not FII selling days. What happens next
   differs by index:
   - **NIFTY bounces** after a gap down (+7 to +21 bps from 09:45 to 11:15): the selling was done before the bell.
   - **BANKNIFTY keeps falling** after a gap down with no recovery (-17 bps to 11:15, -23 bps to 15:15; 45 days).
2. **The seller detector (C6).**
   - **BANKNIFTY:** the nearest miss of all R86 work. It made +Rs 36,188 on 13 short trades, beating random days
     (twin p 0.002, BH q 0.024). But without its 5 best trades it lost Rs 12,654, so it fails. 13 trades are too few.
   - **NIFTY:** lost (16 trades).
3. **The FII-buyer detector (C5).**
   - The model recognises an FII buying day by 12:00 well: walk-forward AUC 0.75-0.90 on NIFTY. AUC measures how well
     it ranks days (0.5 = coin flip, 1 = perfect).
   - The trade made +Rs 28,842 on 40 trades (twin p 0.038), but BH q was 0.23 and it lost Rs 25,890 without its top 5.
     Not proven.
4. **Buy the VWAP dip on slow-buying days (C1):** lost on both indices (16 and 31 trades; NIFTY won 2 of 16).
5. **Fade the month-end closing push (C2):** it reversed only 42-44% of the time. -Rs 5,858 on 84 NIFTY month-ends,
   -Rs 10,453 on 40 BANKNIFTY ones.
6. **Buy a straddle on predicted busy afternoons (C3):**
   - The footprints did not improve the afternoon range forecast over VIX + the morning range.
   - Buying the 11:30 straddle on every day lost about Rs 124-135 a day (581-585 days).
   - The few picked days (14 and 9) were small and mixed.
7. **Unusual OTM option buying (C4):** following single-strike volume spikes LOST more than random minutes (-Rs 1.71
   lakh on 215 NIFTY trades, -Rs 1.38 lakh on 256 BANKNIFTY). Those spikes are mostly not informed buyers.

**For the bot:**
- nothing new to switch on;
- a gap-down-plus-no-recovery BANKNIFTY morning is the clearest "seller still at work" picture;
- the BANKNIFTY seller detector deserves a fresh test once more data exists.

Under CLAUDE.md a failed idea stays a note: it cannot act on paper.

## 6a. Every early seller pattern (09:15-09:44, design 2019-2024)

FII-SELL day = the FII change in index-futures net in the bottom 10% of its previous 250 days (known only after the
close; this is description). Base rate: NIFTY 169 of 1,478 days (11.4%), BANKNIFTY 97 of 811 (12.0%). "Lift" = how
many times more often a flagged day is an FII-SELL day. Moves are from 09:45, in basis points (bps), on flagged days
(vs other days).

| pattern | NIFTY days | NIFTY lift (BH q) | caught | NIFTY 09:45 -> 11:15 / -> 15:15 | BANKNIFTY days | BANKNIFTY lift (q) | caught | BANKNIFTY 09:45 -> 11:15 / -> 15:15 |
|---|---|---|---|---|---|---|---|---|
| P1 gap down >= 0.5% | 132 | **3.25** (3e-15) | 29% | +7.0 / +0.8 | 107 | **2.73** (5e-9) | 36% | -9.3 / -2.8 |
| P2 gap down, first 30 min also down | 55 | **3.50** (1e-7) | 13% | +20.8 / +5.5 | 45 | **3.16** (2e-5) | 18% | **-16.7 / -23.4** |
| P3 09:15-09:44 down >= 0.4% | 197 | 1.55 (0.007) | 21% | +7.7 / +10.9 | 112 | 1.05 (0.59) | 14% | +1.8 / +8.6 |
| P4 a down basket burst by 09:44 | 8 | 4.37 (0.016) | 2% | -32.3 / -32.7 | 29 | 2.02 (0.12) | 7% | -14.3 / -4.7 |
| P5 >= 80% of the stocks below their open | 78 | 1.68 (0.039) | 9% | +3.7 / +12.7 | 104 | 1.05 (0.59) | 13% | +4.7 / +11.0 |
| P6 heavy open (turnover >= 1.5x) | 89 | 2.06 (0.002) | 12% | -6.4 / -5.5 | 130 | 1.16 (0.44) | 19% | -2.5 / -1.8 |
| P7 staircase down (each 5-min bar a lower low) | 40 | 1.75 (0.10) | 5% | -8.3 / -3.8 | 24 | 1.05 (0.62) | 3% | -26.4 / -19.5 |
| P8 closing at the lows of the first 30 min | 321 | 1.20 (0.11) | 26% | +4.1 / +8.9 | 175 | 0.96 (0.64) | 21% | -2.2 / +4.8 |
| P9 India VIX up >= 5% | 87 | **2.31** (2e-4) | 14% | -1.7 / +2.8 | 87 | **2.21** (2e-4) | 24% | -5.7 / +2.6 |
| P10 BANKNIFTY falling more than NIFTY | 381 | 0.99 (0.57) | 25% | -0.0 / +1.6 | - | - | - | - |
| P11 a failed bounce | 451 | 1.12 (0.16) | 34% | -0.3 / -1.8 | 232 | 1.15 (0.34) | 33% | -6.0 / -5.6 |
| P12 down minutes on more volume than up minutes | 731 | 1.17 (0.019) | 58% | -1.2 / -2.8 | 419 | 1.16 (0.12) | 60% | -0.3 / +4.0 |

"caught" = the share of all FII-SELL days that showed the pattern. Days without each pattern moved -4.5 to +2.1 bps over
the same windows. P10 on NIFTY is False before Aug 2021 (no BANKNIFTY minutes then).

What this says about how sellers work:
- **The big selling decision is made overnight.** The gap carries most of it (P1, P2). On NIFTY the gap is often the
  END of the selling: the index bounces from 09:45.
- **BANKNIFTY is where the selling keeps going.** When the gap is not bought in the first 30 minutes (P2), banks
  usually fall further all day. *Inference:* sellers of the banking index are still working their orders.
- **Fear shows in VIX before the price finishes moving** (P9, lift 2.2-2.3). The flagged days move little after 09:45.
- **"Classic" chart signs are weak or noise:** closing at the lows (P8), failed bounces (P11), breadth sell-offs (P5),
  banks leading (P10) and selling on volume (P12) are 1.0-1.7x at best. A staircase down (P7) and a down basket burst
  (P4) are followed by bigger falls (-8 to -33 bps by 11:15) but are rare (8-40 days).
- **Clients buy into it:** on P1/P2 days 27-33% are also heavy client-buying days, vs about 11% normally.

## Results of the 12 tests (design walk-forward)

Futures-equivalent trades with R86's costs and a stop (C3: options with dated charges and slippage). 500 random twins
each; one BH family of 12.

| test | idea | trades | net (Rs) | without top 5 | random twins | twin p | BH q |
|---|---|---|---|---|---|---|---|
| C1-NIFTY | buy the VWAP dip on a steady up morning | 16 | -43,642 | -40,916 | -8,484 | 0.998 | 0.998 |
| C1-BANKNIFTY | same | 31 | -21,842 | -50,921 | -14,818 | 0.627 | 0.940 |
| C2-NIFTY | fade the month-end close overnight | 84 | -5,858 | -81,741 | -42,425 | 0.238 | 0.713 |
| C2-BANKNIFTY | same | 40 | -10,453 | -76,741 | -23,481 | 0.365 | 0.731 |
| C3-NIFTY | straddle on predicted busy afternoons | 14 | +2,156 | -13,075 | -1,681 | 0.226 | 0.713 |
| C3-BANKNIFTY | same | 9 | -6,037 | -14,962 | -1,375 | 0.830 | 0.996 |
| C4-NIFTY | unusual OTM option buying (non-default class) | 215 | -1,71,410 | -1,91,960 | -1,07,224 | 0.996 | 0.998 |
| C4-BANKNIFTY | same | 256 | -1,38,247 | -1,65,274 | -1,16,369 | 0.758 | 0.996 |
| C5-NIFTY | FII-buyer detector at 12:00, long to 15:15 | 40 | +28,842 | -25,890 | -17,094 | 0.038 | 0.228 |
| C5-BANKNIFTY | same | 10 | +529 | -13,818 | -5,066 | 0.321 | 0.731 |
| C6-NIFTY | seller detector at 09:45, short to 11:15 | 16 | -7,046 | -30,315 | -7,773 | 0.441 | 0.756 |
| C6-BANKNIFTY | same | 13 | **+36,188** | -12,654 | -6,561 | **0.002** | **0.024** |

Notes per idea:
- **C1:** 37 NIFTY and 51 BANKNIFTY mornings qualified; 16 and 31 touched VWAP after 11:30. When an up morning falls
  back to VWAP, the buyer is usually done: 11 of 16 NIFTY trades hit the stop. The mirror on down mornings (not gated)
  also lost (-Rs 22,326 on 14, -Rs 17,680 on 12).
- **C2:** the fade worked in 2020-21 and failed in 2023-24 on NIFTY (12 month-ends a year). Month-end closing flows are
  real (R86b), but the next morning does not reliably give them back.
- **C3:**
  - Forecast error with the footprints 0.2154 vs 0.2150 without (NIFTY, 490 days): no gain.
  - The threshold was fixed from the busier 2021-22 years, so calmer 2023-24 rarely cleared it: only 14 and 9 trades.
  - Buying the straddle on every eligible day lost Rs 72,114 (581 NIFTY days) and Rs 78,834 (585 BANKNIFTY).
- **C4:** 9,451 and 11,314 spike windows (8x volume, OI up). The index had to confirm, and still the trades did worse
  than random minutes. This trigger reads option-chain data, so it is labelled non-default; it would need Boss's switch
  even if it had passed.
- **C5:** AUC by fold year 0.90 / 0.80 / 0.75 (NIFTY 2022-24), 0.66 / 0.69 (BANKNIFTY 2023-24). The detector sees
  buying days, but the afternoon move left to catch is small (R86b: on FII buying days about two thirds of NIFTY's move is
  already done by 12:15).
- **C6:**
  - AUC 0.63-0.64 (NIFTY), 0.54 / 0.68 (BANKNIFTY).
  - BANKNIFTY's 13 trades are 2023 (3) and 2024 (10). There is no 2022 fold: too few labelled training days before
    2022.
  - Held to 15:15 instead of 11:15 (reported, not gated): +Rs 58,774, still negative without its top 5.
  - It is the one idea worth re-testing on fresh data, pre-registered as it is now.

## Part C: what changes for the bot

| item | label |
|---|---|
| Recognising a seller by 09:45 (gap down >= 0.5%, no recovery, VIX +5%) is possible; Jarvis can SAY "this looks like an institutional selling morning" | descriptive fact (lift 2.2-3.5, q < 0.001, 2019-2024) |
| On NIFTY, a gap-down morning tends to bounce after 09:45; on BANKNIFTY a gap down with no recovery tends to keep falling | descriptive (45-132 days), not a tested rule |
| No new entries from C1-C6 | proven negatives for C1, C2, C4; not proven for C3, C5, C6 |
| BANKNIFTY seller detector (C6): re-test later on unseen data | not proven (13 trades, carried by its top 5) |

Knowledge items (they reach the pack via FINDINGS.md and `r86c-*.yaml`, tag `big-players`):
- "A gap down of 0.5% or more marks a heavy FII selling day about 3x more often than chance (NIFTY 132 days, lift 3.25;
  BANKNIFTY 107, lift 2.73; 2019-2024)."
- "After a gap down, NIFTY tends to bounce from 09:45; BANKNIFTY keeps falling when the first 30 minutes do not
  recover (-23 bps to 15:15 on 45 days)."
- "Single-strike OTM option volume spikes do not mark informed buying: following them lost more than random minutes
  (471 trades)."

## Honest limits

- The FII label is end-of-day and covers all index futures. A "selling day" is one where FIIs ADDED shorts in
  futures, not necessarily one where they sold stocks.
- Constituents are today's 59 (survivorship). Option minutes start Aug 2021, VIX minutes 2021, BANKNIFTY minutes Aug
  2021. So BANKNIFTY and the option ideas have only 2-3 fold years.
- C3's straddle uses the minute open/close of the same two contracts with modelled slippage, no bid/ask.
