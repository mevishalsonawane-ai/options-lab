# R55: when should the hero ticket's 25% trail start, and what if it is held to expiry settlement? (20 Nov 2024 - 13 Oct 2026)

Written 15 Oct 2026, for Boss. Boss chose the Rs 1,000 hero ticket with "the 25% trail", and had understood the trail to
start "once the option has run up". Boss also asked what happens if the 15:05 square-off is removed for hero tickets
only.

- **Pre-registration:** `research/hunt/r55/PREREG.md`, commit `1fbcc46`, pushed before any P&L.
- **Code, tests and design results:** pushed before the final test was read (`1580fff`).
- **Code, tests and tables:** `research/hunt/r55/`. `results/report.md` holds every table (all 30 cells, both periods,
  every paired comparison, the two called-out days in every cell). The other results files are `design.json` and
  `final.json`. Trade lists stay on the cache, not in git.
- **Experiment entries:** `research/experiments/entries/r55-*.yaml`.
- **Research only:** nothing in the app changes. The 15:05 square-off stays for every arm and for anything live.

## Verdict for Boss

**Keep the trail starting at the fill (T0, what R54 tested). Starting it only after the option has run up makes the
ticket clearly worse. Holding to expiry settlement does not beat 15:05. No cell passes the paper gate.**

- **Which trail start is best (decided on DESIGN, at 12:30): the trail from the fill, at every end time.**
  - Design, 12:30, 15:05 (248 tickets on 142 expiry days):

    | trail start | net | per Rs 1,000 staked |
    |---|---|---|
    | T0, from the fill | -Rs 14,507 | -91 |
    | H, hold, no trail | -Rs 48,733 | -305 |
    | T3, armed after 3x | -Rs 53,943 | -338 |
    | T5, armed after 5x | -Rs 57,356 | -359 |
    | T2, armed after 2x | -Rs 59,286 | -371 |

  - Day by day, T2 / T3 / T5 each lose Rs 278-315 more per expiry day than T0 in design (95% CIs all below 0), and
    Rs 152-204 more in the final test. The pre-registered reading is "worse" for all three, at 15:05, 15:15 and
    settlement (9 of 9 comparisons at 12:30).
  - The final test agrees: at 12:30 / 15:05, T0 made +Rs 1,559 on 107 tickets; T2 -Rs 9,680, T3 -Rs 6,823, T5
    -Rs 9,264, hold -Rs 12,331.
  - At 14:30 (reference only) every trail start loses about the same. No paired comparison is clear.
- **Why the armed trail is worse.** The 25% trail's value on this ticket is mostly that it is also a 25% stop.
  - About 7 in 10 cheap expiry options never double. With T0 such a ticket is out in about 12 minutes (median), losing
    on average Rs 175. Without a stop it rides to almost nothing, losing on average Rs 624 (design).
  - An armed trail removes that stop. In design, T2's 175 tickets that never reached 2x lost Rs 1,09,692. Its 73
    tickets that did reach 2x made +Rs 50,406, sold at a median 1.7x the fill.
  - Arming later (3x, 5x) keeps more of each winner, but more tickets never arm. That gives no stop for even more of
    the losers.
  - Holding with no trail at all keeps the rare 10x-30x winners whole. It still loses more than T0 in design (-Rs 241 a
    day; CI -542 to +98, "no clear difference").
  - So "start the trail once it has run up" gives the losers no protection and still cuts the big winners short. It is
    the worst of the five in design.
- **Hold to settlement instead of 15:05: no.**
  - With T0 it makes no difference. No 12:30 trail ticket was still open at 15:05, in design or in the final test.
  - Held without a trail (H) at 12:30, settlement minus 15:05 is +Rs 36 a day in design (CI -59 to +138). In the final
    test it is -Rs 188 a day (CI -365 to -13). Reading: "no clear difference", and the final test leans worse:
    -Rs 22,644 at settlement vs -Rs 12,331 at 15:05.
  - Why it does not help:
    - Selling at 15:05 still collects the option's remaining time value.
    - At settlement an OTM option pays nothing. In the final test the 97 tickets that expired OTM gave up Rs 12,721
      they would have sold for at 15:05.
    - The 10 ITM tickets gained Rs 2,408 net of STT on exercise.
  - With armed trails, settlement is also no better: "no clear difference" in all 6 comparisons, with design and
    final pulling opposite ways.
  - 15:15 instead of 15:05 is also no clear gain (R54 found the same).
  - Changing 15:05 for the hero paper arm only is Boss's call. The data gives no reason to. 15:05 stays for everything
    else and anything live.
- **Nothing passes.** 0 of 30 cells pass the paper gate.
  - Every cell lost in design, and every cell is negative without its top 5 in the final test.
  - The best final cell is still 12:30 + T0: +Rs 1,559, p 0.044, q 0.15. It is 832% its top 5 and -Rs 11,411 without
    them.

## The two days you asked about (final test, 12:30 entry)

| day | leg | T0 trail from fill | T2 armed 2x | T3 armed 3x | T5 armed 5x | H hold to 15:05 | H hold to 15:15 | H hold to settlement |
|---|---|---|---|---|---|---|---|---|
| **2 Apr 2026, SENSEX** | 72,500 CE at Rs 24.91 | sold 12:43 at Rs 41.38, **+Rs 280** | armed 12:36, sold 12:43 at Rs 41.38, +Rs 280 | armed 13:00, sold 13:04 at Rs 56.07, +Rs 574 | armed 13:26, sold 13:36 at Rs 137.78, +Rs 2,205 | Rs 726.53, **+Rs 13,958** | Rs 892.01, +Rs 17,261 | settles 817.31 ITM (index 73,317.31), **+Rs 15,770** |
| | 71,200 PE at Rs 27.71 | stopped 12:44, -Rs 205 | -Rs 599 | -Rs 599 | -Rs 599 | -Rs 599 | -Rs 578 | worthless, -Rs 578 |
| **13 Oct 2026, NIFTY** | 22,650 CE at Rs 9.55 | stopped 12:37 at Rs 8.15, **-Rs 140** | armed 14:32, sold 15:00 at Rs 54.76, **+Rs 2,884** | the same, +Rs 2,884 | armed 14:48, the same, +Rs 2,884 | Rs 52.21, +Rs 2,719 | Rs 60.53, +Rs 3,259 | settles 48.05 ITM (index 22,698.05), +Rs 2,449 |
| | 22,450 PE at Rs 10.16 | stopped 12:54, -Rs 186 | -Rs 679 | -Rs 679 | -Rs 679 | -Rs 679 | -Rs 681 | worthless, -Rs 684 |

- On these two days an armed trail or holding wins. That is exactly why the idea looks good in hindsight.
- Over the 142 design days the other side dominates: the PE legs and the calls that never ran lose Rs 500-600 each
  without a stop.
- These two days were also seen before R55 was designed (R50, R54). They are the reason it was asked, not evidence for
  it.

## What was tested

- **Window:** NIFTY and SENSEX own expiry days, 20 Nov 2024 - 13 Oct 2026 only.
  - R54's list (R50's expiry rule): 100 NIFTY and 97 SENSEX days, matching `r50/results/expiry_days.json` exactly.
  - DESIGN = 20 Nov 2024 - 31 Mar 2026: 142 days (72 NIFTY, 70 SENSEX).
  - FINAL TEST = 1 Apr - 13 Oct 2026: 55 days (28 NIFTY, 27 SENSEX), read once.
  - **Honest note:** the final test is not pristine.
    - R43, R44, R45, R47, R50 and R54 already read these months.
    - R54 read these exact tickets with T0 and H at 15:05 and 15:15, including both called-out days.
    - The armed trails were proposed after the 2 Apr 2026 ticket was seen.
    - Only live paper from 15 Oct 2026 is unseen.
- **The ticket (R54's, unchanged):**
  - Signal source: none (time only, both sides). The strike rule reads option prices.
  - At the bar closing 12:30 (or 14:30, for reference), 1 lot of the nearest-the-money eligible OTM CE and PE priced
    Rs 5 to min(30, 1,000 / lot). Skipped if fill x lot > Rs 1,000.
  - The trail and stop work on the option's premium (allowed by the signal-source rule).
- **Exits (30 cells = entry {12:30, 14:30} x exit {T0, T2, T3, T5, H} x end {15:05, 15:15, settlement}):**
  - T0: R50 / R54 tr25. A minute close <= 75% of the best close since the fill sells everything at the next minute's
    open. It reproduces R54's numbers exactly: design 248 tickets -Rs 14,507, final 107 tickets +Rs 1,559.
  - T2 / T3 / T5: no stop. The trail arms once a minute close is >= 2x / 3x / 5x the fill price. Then a close <= 75%
    of the best close since arming sells at the next open. Otherwise the ticket is held to the end.
  - H: no trail.
  - 15:05 / 15:15: whatever is held sells at the open of that bar, with R54's late-minute spread (x2.25 from 15:00,
    x2.75 from 15:10).
  - **Settlement:**
    - The trail stays active until the close: it reads closes up to 15:29 and sells at the 15:29 open at the latest.
    - Anything still held at 15:30 is cash-settled. The price is the mean of the index's 1-minute closes 15:00-15:29.
      The exchanges use a weighted last-30-minute average; the lake has no index volume, so the mean is a proxy. No
      day fell back to the last close.
    - ITM pays intrinsic x quantity with no spread. It pays STT on exercise on that value: 0.125% to 31 Mar 2026,
      0.15% from 1 Apr 2026 (Budget 2026).
    - To stay conservative, ITM settlement also pays brokerage Rs 20, exchange and SEBI charges at the dated rates,
      and GST.
    - OTM expires worthless, with no sell charges.
- **Measurement (R54's, never relaxed):**
  - One held contract per ticket.
  - Next-minute fills capped at ref x 1.2 + 0.10 for 3 minutes. Gaps fill at the worse open.
  - R17 liquidity spread with a 2x stress.
  - Dated charges and lots.
  - R25 bad-print clean and R47's foreign-rows filter and stray-print guard.
- **Statistics:**
  - 500 random twins per cell: each ticket is re-entered at a random minute of 12:00-14:45, with the same end and exit.
    So p asks "better than a random afternoon minute?". The seed is 55, so T0 / H p-values differ slightly from R54's.
  - BH runs over all 30 cells in each period.
  - Paired day-by-day differences use 95% bootstrap CIs (5,000 resamples).
  - The reading is R54's: "better" or "worse" only when the sign agrees in design and final AND the design CI excludes
    0.

## Results (12:30, the arm being built; every cell in `hunt/r55/results/report.md`)

"per 1,000" = net per Rs 1,000 staked (premium + buy charges). "2x / 5x / 10x" = tickets that reached that multiple of
the fill while held (stray-guarded confirmed high). ITM / OTM = how the tickets still held at 15:30 settled.

### Design (20 Nov 2024 - 31 Mar 2026, 142 expiry days)

| end | exit | tickets | hit | 2x / 5x / 10x | net | per 1,000 | worst day | max DD | without top 5 | ITM / OTM | p | q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 15:05 | T0 trail from fill | 248 | 16% | 19 / 4 / 1 | -14,507 | -91 | -549 | 18,386 | -28,596 | - | 0.086 | 0.46 |
| 15:05 | T2 armed at 2x | 248 | 30% | 72 / 8 / 2 | -59,286 | -371 | -1,999 | 59,853 | -76,116 | - | 0.693 | 0.83 |
| 15:05 | T3 armed at 3x | 248 | 21% | 73 / 14 / 3 | -53,943 | -338 | -1,999 | 56,839 | -79,537 | - | 0.463 | 0.68 |
| 15:05 | T5 armed at 5x | 248 | 14% | 73 / 24 / 5 | -57,356 | -359 | -1,999 | 64,325 | -87,271 | - | 0.531 | 0.68 |
| 15:05 | H hold | 248 | 10% | 73 / 25 / 8 | -48,733 | -305 | -1,999 | 53,096 | -91,172 | - | 0.271 | 0.53 |
| 15:15 | T0 trail from fill | 248 | 16% | 19 / 4 / 1 | -14,507 | -91 | -549 | 18,386 | -28,596 | - | 0.088 | 0.46 |
| 15:15 | T2 armed at 2x | 248 | 29% | 72 / 8 / 2 | -63,233 | -396 | -2,032 | 63,233 | -80,063 | - | 0.790 | 0.91 |
| 15:15 | T3 armed at 3x | 248 | 19% | 73 / 14 / 3 | -56,689 | -355 | -2,032 | 58,775 | -82,283 | - | 0.543 | 0.68 |
| 15:15 | T5 armed at 5x | 248 | 12% | 73 / 24 / 6 | -56,960 | -357 | -2,032 | 63,114 | -89,704 | - | 0.507 | 0.68 |
| 15:15 | H hold | 248 | 10% | 73 / 25 / 9 | -49,945 | -313 | -2,032 | 53,851 | -92,392 | - | 0.335 | 0.56 |
| settlement | T0 trail from fill | 248 | 16% | 19 / 4 / 1 | -14,507 | -91 | -549 | 18,386 | -28,596 | 0 / 0 | 0.088 | 0.46 |
| settlement | T2 armed at 2x | 248 | 29% | 72 / 8 / 2 | -61,465 | -385 | -1,993 | 61,465 | -78,295 | 5 / 169 | 0.816 | 0.91 |
| settlement | T3 armed at 3x | 248 | 20% | 73 / 15 / 3 | -52,237 | -327 | -1,993 | 55,670 | -77,831 | 9 / 193 | 0.519 | 0.68 |
| settlement | T5 armed at 5x | 248 | 13% | 73 / 25 / 6 | -52,244 | -327 | -1,993 | 58,421 | -84,916 | 13 / 210 | 0.501 | 0.68 |
| settlement | H hold | 248 | 10% | 73 / 26 / 10 | -43,691 | -274 | -1,993 | 45,139 | -87,574 | 33 / 215 | 0.331 | 0.56 |

Every design cell lost, so the top-1 / top-5 shares are not defined ("-" in `report.md`).

### Final test (1 Apr - 13 Oct 2026, 55 expiry days, read once)

| end | exit | tickets | hit | 2x / 5x / 10x | net | per 1,000 | worst day | max DD | top-1 / top-5 | without top 5 | ITM / OTM | p | q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 15:05 | T0 trail from fill | 107 | 22% | 12 / 2 / 1 | +1,559 | +24 | -557 | 5,359 | 582% / 832% | -11,411 | - | 0.044 | 0.15 |
| 15:05 | T2 armed at 2x | 107 | 32% | 31 / 6 / 1 | -9,680 | -149 | -1,752 | 13,429 | - | -27,899 | - | 0.046 | 0.15 |
| 15:05 | T3 armed at 3x | 107 | 24% | 32 / 9 / 1 | -6,823 | -105 | -1,752 | 14,312 | - | -26,943 | - | 0.006 | 0.12 |
| 15:05 | T5 armed at 5x | 107 | 20% | 32 / 11 / 1 | -9,264 | -143 | -1,752 | 17,157 | - | -29,383 | - | 0.016 | 0.12 |
| 15:05 | H hold | 107 | 15% | 32 / 11 / 3 | -12,331 | -190 | -1,752 | 27,730 | - | -41,971 | - | 0.150 | 0.20 |
| 15:15 | T0 trail from fill | 107 | 22% | 12 / 2 / 1 | +1,559 | +24 | -557 | 5,359 | 582% / 832% | -11,411 | - | 0.044 | 0.15 |
| 15:15 | T2 armed at 2x | 107 | 32% | 31 / 6 / 1 | -9,747 | -150 | -1,739 | 13,514 | - | -27,880 | - | 0.068 | 0.15 |
| 15:15 | T3 armed at 3x | 107 | 23% | 32 / 9 / 1 | -7,127 | -110 | -1,739 | 14,344 | - | -27,161 | - | 0.012 | 0.12 |
| 15:15 | T5 armed at 5x | 107 | 18% | 32 / 11 / 1 | -10,692 | -165 | -1,739 | 18,289 | - | -30,726 | - | 0.050 | 0.15 |
| 15:15 | H hold | 107 | 13% | 32 / 11 / 3 | -9,545 | -147 | -1,739 | 28,806 | - | -43,191 | - | 0.204 | 0.25 |
| settlement | T0 trail from fill | 107 | 22% | 12 / 2 / 1 | +1,559 | +24 | -557 | 5,359 | 582% / 832% | -11,411 | 0 / 0 | 0.042 | 0.15 |
| settlement | T2 armed at 2x | 107 | 30% | 33 / 7 / 1 | -14,427 | -223 | -1,752 | 18,410 | - | -32,560 | 0 / 73 | 0.066 | 0.15 |
| settlement | T3 armed at 3x | 107 | 21% | 34 / 10 / 1 | -13,789 | -213 | -1,752 | 19,191 | - | -33,823 | 3 / 84 | 0.012 | 0.12 |
| settlement | T5 armed at 5x | 107 | 14% | 34 / 13 / 2 | -20,265 | -313 | -1,752 | 26,075 | - | -40,299 | 4 / 91 | 0.032 | 0.15 |
| settlement | H hold | 107 | 8% | 34 / 13 / 5 | -22,644 | -349 | -1,752 | 39,601 | - | -54,240 | 10 / 97 | 0.114 | 0.20 |

- A low p on a losing cell (T3, p 0.006) only says random afternoon minutes did even worse. It is not an edge. No q
  is below 0.12.

### 14:30 (reference)

- Design, 15:05: T0 -Rs 37,353 (208 tickets), T2 -Rs 40,527, T3 -Rs 41,172, T5 -Rs 40,300, H -Rs 45,193.
- At settlement: T5 -Rs 25,131 (p 0.022, q 0.46), H -Rs 32,706.
- Final, 15:05: T0 -Rs 6,029 (98 tickets), T2 -Rs 6,440, H -Rs 12,284. At settlement: H -Rs 29,235, T5 -Rs 20,321.
- At 14:30 the trail from the fill fires almost at once (R54). So the armed trails are not worse there, but nothing
  is better either: all 12 trail-start comparisons read "no clear difference".
- Settlement vs 15:05 at 14:30 is positive in design and negative in the final test for T2 / T3 / T5 / H (e.g. H:
  +Rs 88 a day, then -Rs 308 a day). Reading: "no clear difference".

### Paired comparisons at 12:30 (per index-expiry-day, design then final)

| comparison | 15:05 | 15:15 | settlement |
|---|---|---|---|
| T2 minus T0 | -315 [-432, -191]; -204: **worse** | -343 [-463, -215]; -206: **worse** | -331 [-449, -203]; -291: **worse** |
| T3 minus T0 | -278 [-459, -68]; -152: **worse** | -297 [-484, -85]; -158: **worse** | -266 [-456, -53]; -279: **worse** |
| T5 minus T0 | -302 [-520, -67]; -197: **worse** | -299 [-532, -47]; -223: **worse** | -266 [-499, -13]; -397: **worse** |
| H minus T0 | -241 [-542, +98]; -253: no clear difference | -250 [-550, +82]; -202: no clear difference | -206 [-518, +139]; -440: no clear difference |

| comparison | T0 | T2 | T3 | T5 | H |
|---|---|---|---|---|---|
| settlement minus 15:05 | 0; 0 | -15 [-37, +4]; -86 | +12 [-29, +64]; -127 | +36 [-24, +110]; -200 | +36 [-59, +138]; -188 |
| 15:15 minus 15:05 | 0; 0 | -28 [-48, -10]; -1 (reads "worse") | -19 [-47, +12]; -6 | +3 [-46, +71]; -26 | -8 [-84, +78]; +51 |

All settlement and 15:15 readings except the one marked are "no clear difference". None is "better".

## Limits

- The settlement price is a proxy: an unweighted mean of the index's 1-minute closes over 15:00-15:29. An option
  within a few points of its strike could settle on the other side. This touches very few tickets.
- The exercise charges include brokerage, exchange and SEBI charges in case the broker or exchange levies them. If
  they do not, ITM settlement is worth about Rs 25 more per ticket. That changes no reading: at most 33 ITM tickets
  in design, so about Rs 800 in all.
- Arming reads the same raw minute closes as R54's trail (after R25's bad-print clean). The "reached 2x" count uses the
  stray-guarded confirmed high. So a few tickets armed on a 2x close show a peak just under 2x (T2: 74 armed vs 72
  reached 2x in design at settlement).
- An untested idea, logged here and not run: a stop at 25% from the fill PLUS a wider trail (for example 40-50%) once
  the option has doubled. It would keep T0's loss control and let big winners run longer. T0 itself already trails
  from the best close, so it is "stop first, trail later" with one 25% width. A new rule would need its own
  pre-registration, and the final test is used up. Only live paper from 15 Oct 2026 could test it honestly.

## What this means for the app

- Nothing changes. The hero paper arm, if built, should use the trail from the fill (T0). That is the "25% trail" R54
  measured, and it is the best of the five starts tested.
- Holding the hero paper ticket past 15:05, or to settlement, is Boss's call. The data does not support it.
  - If Boss wants it anyway, it applies only to the hero PAPER arm.
  - It must be logged in the Autopilot change timeline and undoable in one tap.
  - It never carries over to live: the 15:05 square-off stays for every live position, and live risk changes start
    in shadow.
