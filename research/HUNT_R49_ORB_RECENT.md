# R49: the cancelled ORB family on the recent market only (20 Nov 2024 - 13 Oct 2026)

Written 15 Oct 2026 for Boss ("add the Nov 2024 onwards view. Don't test any data before that, and now find a hero-zero,
ORB, and all the strategies which we have cancelled"). This is the ORB part: ORB, ORB Fresh, ORB Sweep and Range Fade,
paused on 13 Oct 2026 after R3 found every version loses on 2021-2026. R49 asks the same question on the market since
NSE's Nov 2024 expiry change only, on all five indices, each alone and pooled. Research only: no app code changed.

Pre-registration `research/hunt/r49/PREREG.md`, committed and pushed in **5448420** before any P&L. The code and the
design results were frozen in **134f188** before the one final-test read (15 Oct 2026 01:54 UTC, `results/FINAL_READ`).
Code: `research/hunt/r49/` (`r49cfg.py`, `r49data.py`, `r49engine.py`, `r49study.py`, `r49report.py`, `r49entries.py`,
tests `test_r49.py`). It reuses R3's engine (`research/hunt/r3orb/`): its signals, exit walk, fills and scoring. All
tables: `results/report.md`; numbers: `results/design.json`, `results/final.json`.

## Verdict for Boss (read this first)

1. **Nothing comes back. 0 of 336 tests pass** (56 rules x 6 scopes: pooled and each of NIFTY, BANKNIFTY, FINNIFTY,
   MIDCPNIFTY, SENSEX). ORB, ORB Fresh, ORB Sweep and Range Fade stay off, on paper and live, on every index.
2. **The old rules, exactly as they were paused, on the Nov-2024+ market** (1 lot of the day's contract, after the
   charges in force on each date and realistic fills):

   | arm | design 21 Nov 2024 - 31 Mar 2026 (5 indices) | final test 1 Apr - 13 Oct 2026 (5 indices) | final p / q | verdict |
   |---|---|---|---|---|
   | ORB | Rs -88,644 (1,595 trades, Rs -264 a day) | Rs -9,813 (636 trades, Rs -74 a day) | 0.53 / 0.89 | FAIL |
   | ORB Fresh | the same as ORB (with the 1-a-side cap the first break is always fresh) | the same | 0.55 / 0.90 | FAIL |
   | ORB Sweep | Rs -4,12,532 (2,093 trades, Rs -1,228 a day) | Rs -92,211 (870 trades, Rs -693 a day) | 0.002 / 0.06 | FAIL (loses money) |
   | Range Fade | Rs -4,06,570 (2,363 trades, Rs -1,210 a day) | Rs -1,14,187 (1,020 trades, Rs -859 a day) | 0.06 / 0.42 | FAIL |

   - Every arm loses on every index in design. In the final test only ORB on BANKNIFTY (+Rs 1,825, 128 trades) and
     FINNIFTY (+Rs 16,997, 120 trades) and Sweep on FINNIFTY (+Rs 1,192, 164 trades) are above zero, and all three
     lost in design.
   - ORB's final-test loss is small only because of one month: April 2026 made +Rs 23,944; May to September lost every
     month.
   - Sweep's twin p of 0.002 does NOT mean it works: random entries lost even more (twin mean Rs -1,87,487). Its timing is
     a little better than random, but it still loses Rs 106 a trade.
3. **R3's 16 enhanced versions** (VIX / expiry filter, Boss's consistency exits, 1 strike in the money with 1 entry a
   side, all three together), unchanged: all 16 lose pooled in design. In the final test only ORB 1-ITM (`r3-orb-s`) is
   above zero pooled: +Rs 1,678 on 632 trades (p 0.46), after Rs -67,990 in design.
4. **R23's three ORB finalists**, frozen on 13 Oct and never read on the lake: the two R22-range versions were the only
   rules positive in design (+Rs 28,121 and +Rs 14,517 pooled), and both **collapsed in the final test** (Rs -57,452 on
   190 trades and Rs -61,789 on 236; 29% and 32% winners). The third (`r23-orb-f2`) lost in design and made +Rs 11,040 in
   the final test on 130 trades (p 0.28).
5. **The small re-tune** (opening range 15 / 30 / 50 minutes x stop and target 0.5 / 1 / 1.5 times the app's points, 32
   new rules, chosen on design only): every one loses pooled in design and in the final test. The design-chosen
   re-tune of each arm lost in its own walk-forward (ORB Rs -1,03,013, Sweep Rs -2,49,162, Fade Rs -2,63,424 pooled).
   Smaller stops mostly lose less (each loss is smaller); none turns a profit.
6. **The near-misses, honestly** (all fail; none is an edge):
   - **FINNIFTY ORB**: the only index where ORB-type rules were above zero in both periods.
     - ORB 1-ITM (`r3-orb-s`): design +Rs 24,846 (310 trades), final +Rs 26,002 (116 trades). It fails p (0.12), q
       (0.55) and the top-5 rule (its 5 best trades are 126% of its final net).
     - ORB with half stops (`g-orb-m50-k0.5`): design +Rs 8,987, final +Rs 21,365 (120 trades); p 0.14, q 0.59, top 5 =
       153%. April 2026 alone made +Rs 28,517 of it.
     - R3 saw the same thing on 2021-2026 (FINNIFTY ORB-ITM +Rs 29,700 while every other index lost) and judged it noise.
       Three studies now say "FINNIFTY ORB is slightly positive, always on a few big days". That is not enough for paper
       under the gates.
   - **ORB Sweep on FINNIFTY / MIDCPNIFTY in the final test**: several versions are positive with twin p 0.002-0.006 and
     q 0.06-0.09, but every one lost Rs 39,857-1,07,410 on the same index in design, and most of them also hang on a few trades
     (Range Fade C on MIDCPNIFTY is the same story: final +Rs 5,598, p 0.002, design Rs -76,469).
     A final-test-only result after a design loss is exactly what the walk-forward gate exists to stop.
7. **What changes in the app: nothing.** The four arms stay paused with their code and settings kept. Nothing goes on
   paper. Bringing anything back is Boss's decision; R49 recommends against it.

Plain answer: on the market since Nov 2024 alone, the ORB family still loses money on every index after costs, both in
the 16 months used for design and in the 6.5 months of the final test. The few positive pieces are on one index, depend
on a handful of days (mostly April 2026), and lost in design. They stay off.

## Signal source

Every rule in R49 reads its signals from the **index itself** (1-minute NIFTY / BANKNIFTY / FINNIFTY / MIDCPNIFTY /
SENSEX spot, 5- or 15-minute bars built from it): the CLAUDE.md default. India VIX is context (R3's filters). The option
is only the instrument: the ATM (or 1-ITM) strike of the 09:20 bar's close, the nearest expiry, held as one contract
from entry to exit. Stops, targets and the profit-lock ladder are on the option premium. No rule reads an option's own
price chart.

## Data, window and costs

- Lake (Drive `IraAlgo-data-lake`, fetched 15 Oct 2026): `opt_<UND>_WEEK_2024-2026` and `index_1m_*_2024-2026`. WEEK
  is the nearest expiry on or after the day: the ORB family's own contract rule. For BANKNIFTY / FINNIFTY / MIDCPNIFTY,
  WEEK and MONTH are the same monthly contract in this window; R49 reads WEEK only.
- **Window**: 20 Nov 2024 (a holiday; first session 21 Nov) to 13 Oct 2026 (NIFTY) / 12 Oct 2026 (the others).
  Nothing before is traded or reported. Design 21 Nov 2024 - 31 Mar 2026: 336 sessions per index. Final test 1 Apr -
  13 Oct 2026: 132-133 sessions per index.
- **One held contract** per trade (strike and side of that day's expiry), never the rolling ATM series (R45). A held
  strike that left the stored ATM+-10 band was priced at its last stored print: 1 of 636 ORB final trades.
- **Lots read from the data**, not a calendar: a trade is always a whole number of lots, so the largest known lot that
  divides at least 90% of the day's traded volumes is the lot. This catches what a calendar misses: contracts listed
  before NSE's Nov 2024 change kept their old lot until they expired (BANKNIFTY 15 until the Jan 2025 monthly, NIFTY 25
  on the Jan 2025 monthly, SENSEX 10 until late Jan 2025), and the Dec 2025 change (BANKNIFTY 35 -> 30, MIDCPNIFTY 140 ->
  120, NIFTY 75 -> 65, FINNIFTY 65 -> 60). The repo's older lot table (`obuy/config.py`) is out of date for 2025-26.
  The data gave the lot on 2,339 of 2,341 sessions (the table on 2 FINNIFTY days).
- **Charges in force on each date** (`obuy/costs.py Costs("dated")`): STT on option sells 0.1% to 31 Mar 2026 and
  **0.15% from 1 Apr 2026** (so the final test pays more than design), NSE exchange 0.03503% + IPFT, BSE 0.0325% for
  SENSEX, SEBI, stamp, GST, Rs 20 brokerage an order.
- **Fills**: R17's "liq" model (as R3): +-5 bps plus a 1-4 tick half-spread by the contract's traded lots in the 5
  minutes before; stops 10 bps and double ticks; stops fill at the worse of their level and the minute's open (gaps);
  entries at the next minute's open after the signal bar closes.
- **Expiry days**: R3's rule, plus a session whose own 15:15 ATM time value is under 0.15% of spot. That adds FINNIFTY
  30 Mar and 25 Aug 2026 (the next day had no 15:15 prices) and NIFTY 13 Oct 2026 (the lake's last day). Expiry days
  square off at 15:05.

**How fresh is the final test?** Not pristine, and that was said before the read. R3 ran these exact four arms and its
16 versions on every index to 12 Oct 2026; R12 / R14 / H19 / H20 ran them on BANKNIFTY to 6 Oct 2026; R24 read 27 Apr - 12
Oct 2026 on NIFTY and BANKNIFTY. What was new here is the restriction to the post-Nov-2024 market, the per-index gates, the
re-tune chosen on this window, and R23's finalists. The only truly unseen data is live paper from 15 Oct 2026. Since
nothing passed, this caveat does not change the answer.

## What was tested (56 rules, each pooled and on each index)

- **Old rules (5)**: ORB, ORB Fresh, ORB Sweep, Range Fade exactly as paused (R3's line-by-line rebuild of `OrbRules` /
  `SweepRules` / `RangeFadeRules` / `ProfitLock`: range 09:15-10:04, ATM of 09:20, -P / +P (Sweep +2P), the ladder, out
  15:10 / 15:05, ORB / Fresh 1 entry a side a day, Sweep / Fade 2 a day; P = BANKNIFTY 40, NIFTY 20, FINNIFTY 20,
  MIDCPNIFTY 10, SENSEX 60), plus ORB without the cap (the app before 13 Oct) as a check.
- **R3's 16 versions (16)**, with R3's filters and cut values unchanged (chosen on 2021-2024).
- **R23's 3 finalists (3)**, with R23's cut values unchanged. As in the app, one position at a time: a break that
  comes while a trade is open is not seen; a side whose first break fails the filter is done for the day.
- **Re-tune grid (32)**: range M = 15 / 30 / 50 minutes x stop / target scale k = 0.5 / 1 / 1.5 for each arm (8 new each).
  The pick per arm and scope = the best design net; its nested quarterly walk-forward is reported.
- **Gates** (all must hold, per rule and scope): final-test net > 0; random-entry twin p < 0.05 (500 twins); BH q <= 0.10
  over all 336; at least 40 final-test trades; the 5 best final trades < 100% of the net; design (walk-forward) net > 0.
- The code reproduces R3's engine to the paisa on the old rules (`test_old_rules_match_r3_engine_exactly`).

## Results per strategy: OLD (R3, 2021-2026) vs NOW (Nov 2024+ design) vs FINAL TEST (Apr-Oct 2026)

Rs per 1 lot, after costs. OLD = R3's full-data result on all five indices (trades / net; R3 used today's charges;
R23 rows show R23's release result, BANKNIFTY + NIFTY 2024-26). The verdict letters are the six gates in order (net, p,
q, 40+ trades, top-5, design): y = met, n = not met. p = twin p; q = BH over 336.

### ORB

| rule | scope | OLD 2021-26 trades / net | NOW design trades | win | net | net/day | FINAL trades | win | net | net/day | avg win / loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `r49-orb` | pooled | 5,061 / -337,033 | 1,595 | 42% | -88,644 | -264 | 636 | 41% | -9,813 | -74 | +833 / -610 | -7,753 | -40,007 | 0.527 | 0.89 | FAIL (nnnynn) |
| `r49-orb` | NIFTY | 1,282 / -99,106 | 325 | 42% | -9,276 | -28 | 128 | 38% | -7,831 | -59 | +595 / -455 | -2,772 | -17,392 | 0.810 | 1.00 | FAIL (nnnynn) |
| `r49-orb` | BANKNIFTY | 1,207 / -123,935 | 320 | 38% | -35,634 | -106 | 128 | 44% | +1,825 | +14 | +645 / -476 | -2,178 | -6,544 | 0.078 | 0.45 | FAIL (ynnynn) |
| `r49-orb` | FINNIFTY | 1,023 / -28,468 | 312 | 43% | -10,290 | -31 | 120 | 41% | +16,997 | +129 | +1,638 / -891 | -4,760 | -23,590 | 0.413 | 0.81 | FAIL (ynnynn) |
| `r49-orb` | MIDCPNIFTY | 746 / -53,819 | 315 | 42% | -29,506 | -88 | 135 | 44% | -11,230 | -85 | +815 / -781 | -3,024 | -18,053 | 0.565 | 0.91 | FAIL (nnnynn) |
| `r49-orb` | SENSEX | 803 / -31,706 | 323 | 44% | -3,938 | -12 | 125 | 40% | -9,575 | -73 | +502 / -462 | -2,557 | -10,487 | 0.890 | 1.00 | FAIL (nnnynn) |
| `r49-orb-uncapped` | pooled | 17,923 / -1,590,769 (BN+NIFTY) | 11,470 | 39% | -1,283,672 | -3,820 | 3,896 | 38% | -413,826 | -3,111 | +793 / -653 | -21,510 | -418,345 | 1.000 | 1.00 | FAIL (nnnynn) |
| `r49-r3-orb-f` | pooled | 4,511 / -245,734 | 1,412 | 42% | -66,672 | -198 | 573 | 40% | -12,191 | -92 | +836 / -600 | -7,753 | -41,927 | 0.703 | 1.00 | FAIL (nnnynn) |
| `r49-r3-orb-e` | pooled | 5,061 / -495,722 | 1,595 | 40% | -108,184 | -322 | 636 | 39% | -64,820 | -487 | +670 / -595 | -7,753 | -74,333 | 0.874 | 1.00 | FAIL (nnnynn) |
| `r49-r3-orb-s` | pooled | 5,043 / -300,177 | 1,591 | 43% | -67,990 | -202 | 632 | 41% | +1,678 | +13 | +957 / -665 | -8,136 | -49,240 | 0.455 | 0.83 | FAIL (ynnynn) |
| `r49-r3-orb-c` | pooled | 4,496 / -316,337 | 1,409 | 42% | -30,509 | -91 | 569 | 38% | -54,261 | -408 | +727 / -591 | -8,136 | -70,519 | 0.946 | 1.00 | FAIL (nnnynn) |
| `r49-g-orb-m15-k0.5` | pooled | - | 2,019 | 39% | -133,913 | -399 | 777 | 42% | -11,376 | -86 | +590 / -458 | -5,326 | -52,596 | 0.006 | 0.09 | FAIL (nyyynn) |
| `r49-g-orb-m15-k1` | pooled | - | 2,019 | 41% | -190,317 | -566 | 777 | 41% | -28,425 | -214 | +801 / -620 | -7,889 | -88,810 | 0.373 | 0.79 | FAIL (nnnynn) |
| `r49-g-orb-m15-k1.5` | pooled | - | 2,017 | 39% | -242,731 | -722 | 776 | 38% | -49,869 | -375 | +1,098 / -781 | -11,591 | -113,306 | 0.828 | 1.00 | FAIL (nnnynn) |
| `r49-g-orb-m30-k0.5` | pooled | - | 1,791 | 39% | -101,795 | -303 | 699 | 43% | -18,841 | -142 | +565 / -472 | -5,326 | -45,404 | 0.078 | 0.45 | FAIL (nnnynn) |
| `r49-g-orb-m30-k1` | pooled | - | 1,790 | 42% | -157,130 | -468 | 699 | 40% | -22,109 | -166 | +818 / -610 | -7,752 | -50,506 | 0.537 | 0.89 | FAIL (nnnynn) |
| `r49-g-orb-m30-k1.5` | pooled | - | 1,789 | 39% | -206,284 | -614 | 698 | 40% | -40,798 | -307 | +1,090 / -819 | -11,331 | -68,404 | 0.892 | 1.00 | FAIL (nnnynn) |
| `r49-g-orb-m50-k0.5` | pooled | - | 1,595 | 41% | -64,516 | -192 | 636 | 42% | -12,100 | -91 | +623 / -478 | -5,928 | -41,714 | 0.064 | 0.42 | FAIL (nnnynn) |
| `r49-g-orb-m50-k1.5` | pooled | - | 1,594 | 40% | -137,427 | -409 | 636 | 38% | -68,766 | -517 | +1,084 / -845 | -9,455 | -107,449 | 0.944 | 1.00 | FAIL (nnnynn) |
| `r49-r23-r22-f2` | pooled | 390 / +16,158 (release, BN+NIFTY) | 625 | 47% | +28,121 | +84 | 190 | 29% | -57,452 | -432 | +827 / -762 | -14,219 | -57,452 | 1.000 | 1.00 | FAIL (nnnyny) |
| `r49-r23-r22-f1` | pooled | 472 / +15,593 (release, BN+NIFTY) | 775 | 46% | +14,517 | +43 | 236 | 32% | -61,789 | -465 | +775 / -745 | -14,079 | -63,523 | 1.000 | 1.00 | FAIL (nnnyny) |
| `r49-r23-orb-f2` | pooled | 349 / -12,461 (release, BN+NIFTY) | 535 | 44% | -33,047 | -98 | 130 | 50% | +11,040 | +83 | +764 / -594 | -3,103 | -7,119 | 0.283 | 0.72 | FAIL (ynnynn) |

Re-tune (design only): pick and walk-forward net by scope: pooled `r49-g-orb-m50-k0.5` (design -64,516, walk-forward -103,013, final -12,100); NIFTY `r49-orb` (design -9,276, walk-forward -31,990, final -7,831); BANKNIFTY `r49-g-orb-m50-k0.5` (design -20,917, walk-forward -20,283, final -3,807); FINNIFTY `r49-g-orb-m50-k0.5` (design +8,987, walk-forward +2,340, final +21,365); MIDCPNIFTY `r49-g-orb-m30-k0.5` (design -19,600, walk-forward -27,588, final -22,018); SENSEX `r49-g-orb-m50-k1.5` (design -2,730, walk-forward -16,762, final -19,189)

### ORB Fresh

| rule | scope | OLD 2021-26 trades / net | NOW design trades | win | net | net/day | FINAL trades | win | net | net/day | avg win / loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `r49-orb-fresh` | pooled | 5,045 / -373,245 | 1,595 | 42% | -88,644 | -264 | 636 | 41% | -9,813 | -74 | +833 / -610 | -7,753 | -40,007 | 0.553 | 0.90 | FAIL (nnnynn) |
| `r49-orb-fresh` | NIFTY | 1,282 / -99,877 | 325 | 42% | -9,276 | -28 | 128 | 38% | -7,831 | -59 | +595 / -455 | -2,772 | -17,392 | 0.842 | 1.00 | FAIL (nnnynn) |
| `r49-orb-fresh` | BANKNIFTY | 1,207 / -123,935 | 320 | 38% | -35,634 | -106 | 128 | 44% | +1,825 | +14 | +645 / -476 | -2,178 | -6,544 | 0.078 | 0.45 | FAIL (ynnynn) |
| `r49-orb-fresh` | FINNIFTY | 1,009 / -64,161 | 312 | 43% | -10,290 | -31 | 120 | 41% | +16,997 | +129 | +1,638 / -891 | -4,760 | -23,590 | 0.417 | 0.81 | FAIL (ynnynn) |
| `r49-orb-fresh` | MIDCPNIFTY | 745 / -54,121 | 315 | 42% | -29,506 | -88 | 135 | 44% | -11,230 | -85 | +815 / -781 | -3,024 | -18,053 | 0.547 | 0.90 | FAIL (nnnynn) |
| `r49-orb-fresh` | SENSEX | 802 / -31,152 | 323 | 44% | -3,938 | -12 | 125 | 40% | -9,575 | -73 | +502 / -462 | -2,557 | -10,487 | 0.888 | 1.00 | FAIL (nnnynn) |
| `r49-r3-orb-fresh-f` | pooled | 3,851 / -274,558 | 1,200 | 41% | -84,612 | -252 | 519 | 40% | -9,777 | -74 | +850 / -609 | -7,753 | -46,668 | 0.661 | 1.00 | FAIL (nnnynn) |
| `r49-r3-orb-fresh-e` | pooled | 5,045 / -502,467 | 1,595 | 40% | -108,184 | -322 | 636 | 39% | -64,820 | -487 | +670 / -595 | -7,753 | -74,333 | 0.846 | 1.00 | FAIL (nnnynn) |
| `r49-r3-orb-fresh-s` | pooled | 5,026 / -313,542 | 1,590 | 43% | -74,457 | -222 | 631 | 41% | -1,150 | -9 | +950 / -665 | -8,136 | -52,067 | 0.451 | 0.83 | FAIL (nnnynn) |
| `r49-r3-orb-fresh-c` | pooled | 3,841 / -303,216 | 1,196 | 42% | -50,454 | -150 | 517 | 38% | -51,541 | -388 | +731 / -603 | -8,136 | -67,258 | 0.852 | 1.00 | FAIL (nnnynn) |
| `r49-g-orb-fresh-m15-k0.5` | pooled | - | 2,018 | 39% | -130,083 | -387 | 776 | 42% | -26,758 | -201 | +546 / -458 | -5,326 | -52,729 | 0.014 | 0.15 | FAIL (nynynn) |
| `r49-g-orb-fresh-m15-k1` | pooled | - | 2,018 | 41% | -186,459 | -555 | 776 | 41% | -43,698 | -329 | +755 / -620 | -7,889 | -88,834 | 0.473 | 0.85 | FAIL (nnnynn) |
| `r49-g-orb-fresh-m15-k1.5` | pooled | - | 2,012 | 39% | -233,951 | -696 | 773 | 38% | -69,715 | -524 | +1,042 / -785 | -11,591 | -118,292 | 0.868 | 1.00 | FAIL (nnnynn) |
| `r49-g-orb-fresh-m30-k0.5` | pooled | - | 1,791 | 39% | -100,815 | -300 | 699 | 43% | -18,841 | -142 | +565 / -472 | -5,326 | -45,404 | 0.074 | 0.45 | FAIL (nnnynn) |
| `r49-g-orb-fresh-m30-k1` | pooled | - | 1,790 | 42% | -154,986 | -461 | 699 | 40% | -22,109 | -166 | +818 / -610 | -7,752 | -50,506 | 0.517 | 0.88 | FAIL (nnnynn) |
| `r49-g-orb-fresh-m30-k1.5` | pooled | - | 1,789 | 39% | -204,940 | -610 | 696 | 40% | -44,045 | -331 | +1,082 / -820 | -11,331 | -72,040 | 0.894 | 1.00 | FAIL (nnnynn) |
| `r49-g-orb-fresh-m50-k0.5` | pooled | - | 1,595 | 41% | -64,516 | -192 | 636 | 42% | -12,100 | -91 | +623 / -478 | -5,928 | -41,714 | 0.090 | 0.47 | FAIL (nnnynn) |
| `r49-g-orb-fresh-m50-k1.5` | pooled | - | 1,594 | 40% | -137,427 | -409 | 636 | 38% | -68,766 | -517 | +1,084 / -845 | -9,455 | -107,449 | 0.948 | 1.00 | FAIL (nnnynn) |

Re-tune (design only): pick and walk-forward net by scope: pooled `r49-g-orb-fresh-m50-k0.5` (design -64,516, walk-forward -103,013, final -12,100); NIFTY `r49-orb-fresh` (design -9,276, walk-forward -31,990, final -7,831); BANKNIFTY `r49-g-orb-fresh-m50-k0.5` (design -20,917, walk-forward -20,283, final -3,807); FINNIFTY `r49-g-orb-fresh-m50-k0.5` (design +8,987, walk-forward +2,340, final +21,365); MIDCPNIFTY `r49-g-orb-fresh-m30-k0.5` (design -19,600, walk-forward -27,588, final -22,018); SENSEX `r49-g-orb-fresh-m50-k1.5` (design -2,730, walk-forward -16,762, final -19,189)

### ORB Sweep

| rule | scope | OLD 2021-26 trades / net | NOW design trades | win | net | net/day | FINAL trades | win | net | net/day | avg win / loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `r49-orb-sweep` | pooled | 6,459 / -1,091,752 | 2,093 | 29% | -412,532 | -1,228 | 870 | 33% | -92,211 | -693 | +1,353 / -828 | -12,171 | -92,211 | 0.002 | 0.06 | FAIL (nyyynn) |
| `r49-orb-sweep` | NIFTY | 1,479 / -206,526 | 369 | 31% | -32,097 | -96 | 169 | 28% | -25,886 | -195 | +1,334 / -743 | -2,831 | -25,886 | 0.437 | 0.83 | FAIL (nnnynn) |
| `r49-orb-sweep` | BANKNIFTY | 1,677 / -299,428 | 454 | 26% | -104,579 | -311 | 176 | 28% | -48,019 | -364 | +1,021 / -772 | -2,820 | -48,019 | 0.856 | 1.00 | FAIL (nnnynn) |
| `r49-orb-sweep` | FINNIFTY | 1,324 / -271,182 | 436 | 31% | -107,410 | -320 | 164 | 37% | +1,192 | +9 | +1,710 / -1,001 | -6,766 | -25,603 | 0.004 | 0.08 | FAIL (yyyynn) |
| `r49-orb-sweep` | MIDCPNIFTY | 988 / -192,949 | 437 | 26% | -109,608 | -326 | 190 | 39% | -2,692 | -20 | +1,403 / -918 | -3,301 | -27,182 | 0.016 | 0.16 | FAIL (nynynn) |
| `r49-orb-sweep` | SENSEX | 991 / -121,667 | 397 | 29% | -58,839 | -175 | 171 | 33% | -16,807 | -127 | +1,201 / -731 | -2,575 | -16,807 | 0.248 | 0.70 | FAIL (nnnynn) |
| `r49-r3-orb-sweep-f` | pooled | 4,412 / -710,861 | 1,378 | 28% | -305,450 | -909 | 546 | 35% | -34,982 | -263 | +1,388 / -858 | -12,171 | -44,566 | 0.002 | 0.06 | FAIL (nyyynn) |
| `r49-r3-orb-sweep-e` | pooled | 6,907 / -944,488 | 2,200 | 34% | -343,451 | -1,022 | 915 | 36% | -73,151 | -550 | +1,032 / -695 | -10,837 | -73,151 | 0.002 | 0.06 | FAIL (nyyynn) |
| `r49-r3-orb-sweep-s` | pooled | 5,035 / -791,161 | 1,571 | 28% | -302,655 | -901 | 653 | 30% | -109,337 | -822 | +1,332 / -797 | -7,980 | -122,420 | 0.349 | 0.78 | FAIL (nnnynn) |
| `r49-r3-orb-sweep-c` | pooled | 3,528 / -465,127 | 1,058 | 34% | -166,748 | -496 | 416 | 33% | -67,978 | -511 | +1,037 / -760 | -7,625 | -83,412 | 0.361 | 0.79 | FAIL (nnnynn) |
| `r49-g-orb-sweep-m15-k0.5` | pooled | - | 2,681 | 31% | -212,647 | -633 | 1,088 | 31% | -101,094 | -760 | +777 / -483 | -20,115 | -111,266 | 0.022 | 0.19 | FAIL (nynynn) |
| `r49-g-orb-sweep-m15-k1` | pooled | - | 2,514 | 32% | -235,920 | -702 | 1,031 | 33% | -166,746 | -1,254 | +1,153 / -823 | -19,973 | -183,714 | 0.289 | 0.73 | FAIL (nnnynn) |
| `r49-g-orb-sweep-m15-k1.5` | pooled | - | 2,337 | 32% | -199,409 | -593 | 960 | 34% | -119,505 | -899 | +1,783 / -1,115 | -20,376 | -199,613 | 0.080 | 0.45 | FAIL (nnnynn) |
| `r49-g-orb-sweep-m30-k0.5` | pooled | - | 2,541 | 31% | -264,808 | -788 | 1,035 | 31% | -44,119 | -332 | +832 / -436 | -8,224 | -49,305 | 0.002 | 0.06 | FAIL (nyyynn) |
| `r49-g-orb-sweep-m30-k1` | pooled | - | 2,336 | 30% | -314,882 | -937 | 976 | 33% | -109,148 | -821 | +1,316 / -802 | -10,844 | -112,410 | 0.028 | 0.22 | FAIL (nynynn) |
| `r49-g-orb-sweep-m30-k1.5` | pooled | - | 2,120 | 30% | -339,679 | -1,011 | 891 | 32% | -97,133 | -730 | +1,951 / -1,083 | -14,814 | -125,078 | 0.022 | 0.19 | FAIL (nynynn) |
| `r49-g-orb-sweep-m50-k0.5` | pooled | - | 2,332 | 30% | -300,259 | -894 | 962 | 29% | -64,032 | -481 | +851 / -447 | -9,678 | -64,493 | 0.004 | 0.08 | FAIL (nyyynn) |
| `r49-g-orb-sweep-m50-k1.5` | pooled | - | 1,850 | 28% | -466,673 | -1,389 | 796 | 34% | -77,030 | -579 | +1,860 / -1,095 | -11,071 | -85,654 | 0.004 | 0.08 | FAIL (nyyynn) |

Re-tune (design only): pick and walk-forward net by scope: pooled `r49-g-orb-sweep-m15-k1.5` (design -199,409, walk-forward -249,162, final -119,505); NIFTY `r49-g-orb-sweep-m15-k1.5` (design +13,211, walk-forward -30,662, final -32,050); BANKNIFTY `r49-g-orb-sweep-m15-k0.5` (design -44,292, walk-forward -80,607, final -30,003); FINNIFTY `r49-g-orb-sweep-m30-k0.5` (design -39,857, walk-forward -69,890, final +20,997); MIDCPNIFTY `r49-g-orb-sweep-m15-k0.5` (design -42,294, walk-forward -65,103, final -9,351); SENSEX `r49-g-orb-sweep-m30-k1.5` (design -18,914, walk-forward -73,643, final -15,294)

### Range Fade

| rule | scope | OLD 2021-26 trades / net | NOW design trades | win | net | net/day | FINAL trades | win | net | net/day | avg win / loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `r49-range-fade` | pooled | 7,306 / -1,088,323 | 2,363 | 34% | -406,570 | -1,210 | 1,020 | 34% | -114,187 | -859 | +767 / -559 | -10,030 | -118,217 | 0.062 | 0.42 | FAIL (nnnynn) |
| `r49-range-fade` | NIFTY | 1,669 / -224,946 | 427 | 35% | -61,269 | -182 | 199 | 29% | -26,756 | -201 | +659 / -461 | -2,831 | -26,756 | 0.729 | 1.00 | FAIL (nnnynn) |
| `r49-range-fade` | BANKNIFTY | 1,843 / -286,033 | 500 | 32% | -98,344 | -293 | 207 | 32% | -27,098 | -205 | +529 / -440 | -2,673 | -29,252 | 0.323 | 0.76 | FAIL (nnnynn) |
| `r49-range-fade` | FINNIFTY | 1,517 / -275,075 | 495 | 37% | -89,765 | -267 | 189 | 34% | -26,227 | -199 | +1,125 / -786 | -5,187 | -31,426 | 0.076 | 0.45 | FAIL (nnnynn) |
| `r49-range-fade` | MIDCPNIFTY | 1,155 / -183,077 | 501 | 34% | -94,357 | -281 | 222 | 40% | -22,276 | -169 | +903 / -759 | -3,283 | -28,817 | 0.114 | 0.55 | FAIL (nnnynn) |
| `r49-range-fade` | SENSEX | 1,122 / -119,192 | 440 | 33% | -62,835 | -187 | 203 | 33% | -11,829 | -90 | +578 / -379 | -2,557 | -15,011 | 0.339 | 0.78 | FAIL (nnnynn) |
| `r49-r3-range-fade-f` | pooled | 6,472 / -994,970 | 2,179 | 34% | -394,617 | -1,174 | 923 | 33% | -117,005 | -880 | +786 / -577 | -10,030 | -119,983 | 0.164 | 0.61 | FAIL (nnnynn) |
| `r49-r3-range-fade-e` | pooled | 7,526 / -1,025,229 | 2,407 | 32% | -380,899 | -1,134 | 1,041 | 31% | -125,087 | -941 | +740 / -514 | -10,030 | -126,025 | 0.164 | 0.61 | FAIL (nnnynn) |
| `r49-r3-range-fade-s` | pooled | 5,281 / -776,413 | 1,640 | 36% | -285,477 | -850 | 713 | 34% | -93,179 | -701 | +700 / -555 | -7,618 | -93,179 | 0.122 | 0.55 | FAIL (nnnynn) |
| `r49-r3-range-fade-c` | pooled | 4,453 / -635,959 | 1,464 | 33% | -276,137 | -822 | 629 | 32% | -64,340 | -484 | +807 / -529 | -7,654 | -74,485 | 0.030 | 0.23 | FAIL (nynynn) |
| `r49-g-range-fade-m15-k0.5` | pooled | - | 2,546 | 35% | -268,314 | -799 | 1,060 | 39% | -86,809 | -653 | +472 / -438 | -18,853 | -87,456 | 0.008 | 0.11 | FAIL (nynynn) |
| `r49-g-range-fade-m15-k1` | pooled | - | 2,453 | 36% | -272,747 | -812 | 1,018 | 38% | -77,755 | -585 | +790 / -612 | -19,884 | -83,279 | 0.014 | 0.15 | FAIL (nynynn) |
| `r49-g-range-fade-m15-k1.5` | pooled | - | 2,315 | 37% | -307,614 | -916 | 973 | 40% | -64,572 | -486 | +1,063 / -822 | -21,488 | -89,699 | 0.012 | 0.14 | FAIL (nynynn) |
| `r49-g-range-fade-m30-k0.5` | pooled | - | 2,534 | 36% | -212,834 | -633 | 1,098 | 33% | -130,817 | -984 | +427 / -383 | -6,753 | -131,288 | 0.345 | 0.78 | FAIL (nnnynn) |
| `r49-g-range-fade-m30-k1` | pooled | - | 2,410 | 38% | -271,751 | -809 | 1,044 | 33% | -159,337 | -1,198 | +771 / -609 | -8,654 | -159,917 | 0.677 | 1.00 | FAIL (nnnynn) |
| `r49-g-range-fade-m30-k1.5` | pooled | - | 2,237 | 36% | -368,798 | -1,098 | 974 | 35% | -185,315 | -1,393 | +1,037 / -854 | -12,085 | -185,315 | 0.808 | 1.00 | FAIL (nnnynn) |
| `r49-g-range-fade-m50-k0.5` | pooled | - | 2,550 | 32% | -315,679 | -940 | 1,076 | 34% | -93,838 | -706 | +450 / -361 | -6,921 | -93,838 | 0.022 | 0.19 | FAIL (nynynn) |
| `r49-g-range-fade-m50-k1.5` | pooled | - | 2,167 | 33% | -454,796 | -1,354 | 946 | 35% | -148,269 | -1,115 | +1,077 / -830 | -11,266 | -148,269 | 0.261 | 0.71 | FAIL (nnnynn) |

Re-tune (design only): pick and walk-forward net by scope: pooled `r49-g-range-fade-m30-k0.5` (design -212,834, walk-forward -263,424, final -130,817); NIFTY `r49-g-range-fade-m15-k1.5` (design -22,605, walk-forward -56,369, final +1,174); BANKNIFTY `r49-g-range-fade-m30-k0.5` (design -62,248, walk-forward -71,006, final -36,079); FINNIFTY `r49-g-range-fade-m30-k0.5` (design -18,280, walk-forward -51,333, final -19,328); MIDCPNIFTY `r49-g-range-fade-m30-k0.5` (design -58,550, walk-forward -79,400, final -34,564); SENSEX `r49-g-range-fade-m15-k1` (design -43,971, walk-forward -58,679, final -12,639)

### Quarter by quarter (old rules, pooled, Rs / trades)

| arm | 2024Q4 (from 21 Nov) | 2025Q1 | 2025Q2 | 2025Q3 | 2025Q4 | 2026Q1 | final Apr - 13 Oct 2026 |
|---|---|---|---|---|---|---|---|
| ORB | -5,645 / 149 | -44,289 / 304 | +24,341 / 264 | -9,150 / 291 | -27,363 / 298 | -26,538 / 289 | -9,813 / 636 |
| ORB Sweep | -6,509 / 177 | -105,115 / 413 | -101,978 / 363 | -67,797 / 371 | -106,833 / 382 | -24,299 / 387 | -92,211 / 870 |
| Range Fade | -9,119 / 191 | -69,137 / 436 | -106,395 / 408 | -64,617 / 472 | -109,102 / 412 | -48,200 / 444 | -114,187 / 1020 |
| R23 r22-f2 (best in design) | +3,202 / 71 | -12,055 / 130 | +7,662 / 106 | +16,414 / 103 | +8,284 / 117 | +4,614 / 98 | -57,452 / 190 |

Of the old rules, only ORB in 2025Q2 had a green quarter. R23's R22-range finalist was green in 5 of 6 design quarters, then lost Rs
57,452 in the final test (May 2026 Rs -29,341, Jul 2026 Rs -22,616).

### Final test month by month (old rules, pooled, Rs / trades)

| arm | Apr 2026 | May | Jun | Jul | Aug | Sep | Oct (to 13th) |
|---|---|---|---|---|---|---|---|
| ORB | +23,944 / 102 | -6,951 / 98 | -4,198 / 114 | -10,980 / 109 | -12,243 / 86 | -1,422 / 87 | +2,037 / 40 |
| ORB Sweep | -24,688 / 122 | -270 / 143 | +20,055 / 147 | -42,613 / 161 | +10,788 / 114 | -32,358 / 127 | -23,125 / 56 |
| Range Fade | -22,650 / 134 | -12,901 / 169 | -3,503 / 168 | -25,364 / 179 | -23,542 / 157 | -8,022 / 152 | -18,204 / 61 |
| R23 r22-f2 (best in design) | -3,988 / 18 | -29,341 / 32 | +713 / 30 | -22,616 / 35 | +1,219 / 22 | +5,938 / 35 | -9,378 / 18 |

## Why it still loses (the same story as R3, on the newer market)

- **ORB** buys after the break. Design exits: 341 stops, 979 ladder locks, 271 targets (1,595 trades): the ladder banks
  small gains, the stops cost the full P, and the charges take the rest. Random entries on the same days and sides lose
  only a little more (final twin mean Rs -15,012 vs ORB's Rs -9,813), so the break itself carries almost no information.
- **Sweep and Fade** bet that the break fails. They win big when it does (average win Rs 1,353 / 767) but win only
  29-34% of the time, and the higher STT since 1 Apr 2026 makes every one of their many trades dearer.
- **Re-tuning** does not find a hidden good setting: no (range, stop) pair is positive pooled in design for any arm. For
  ORB and ORB Fresh a shorter range trades more and loses more (as R20 / R23 found); for Sweep and Fade a shorter range
  loses less, but still loses. Smaller stops mostly lose less only because each loss is smaller.
- **FINNIFTY** is the one index where the ORB is near break-even or better in both periods, but always through a few
  large days (April 2026), which the top-5 gate is there to catch.

## Which arms or versions come back on PAPER under the Nov-2024+ view

**None.** No rule passed the pre-registered gates on any index or pooled. Exact status:

| arm | versions tested | comes back on paper? | settings |
|---|---|---|---|
| ORB | old rule, uncapped, 4 R3 versions, 3 R23 finalists, 8 re-tunes | **No, stays off** | keep the app's code and settings as paused (09:15-10:04 range, -40 / +40 BANKNIFTY, 1 a side) |
| ORB Fresh | old rule, 4 R3 versions, 8 re-tunes | **No, stays off** | as paused |
| ORB Sweep | old rule, 4 R3 versions, 8 re-tunes | **No, stays off** | as paused |
| Range Fade | old rule, 4 R3 versions, 8 re-tunes | **No, stays off** | as paused |

If Boss still wants an ORB-type rule watched, the only candidate with any consistency is **ORB 1-ITM on FINNIFTY**
(`r3-orb-s`: R3's 09:15-10:04 range, 5-minute close beyond it 10:05-13:55, 1 strike in the money, -20 / +20 premium
points, the ladder, 1 entry a side a day, out 15:10 / 15:05). It FAILED the gates (p 0.12, q 0.55, top 5 = 126% of the net),
so under CLAUDE.md it stays a note: it could only ever be a logged shadow, and that is Boss's call. R49 does not
recommend it.

## Files

- `research/hunt/r49/PREREG.md` (5448420), code and tests (`test_r49.py`, 30 tests), `results/design.json`,
  `results/final.json`, `results/report.md` (every rule on every index), `results/FINAL_READ`.
- Experiment entries `research/experiments/entries/r49-*.yaml` (56, family `orb`, each with its 6 tests).
- Re-run: fetch the lake (see `r49cfg.py`), then `python3 research/hunt/r49/r49data.py build`, `r49study.py design`,
  `r49study.py report`. `r49study.py final` refuses a second read.
