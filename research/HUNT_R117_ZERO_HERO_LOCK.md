# R117: the 27 Oct 22700 PE that went from Rs 9 to Rs 60, and would a profit lock make FT3 / Hero pay?

Written 27 Oct 2026, for Boss's question about NIFTY 27 OCT 22700 PE: "It went from 9 rs to 60, why did we miss it?"

- **Pre-registration:** `research/hunt/r117_zero_hero_lock/PREREG.md`, commit `e3ad3b3`, pushed before any lock number.
- **Code and tests:** pushed before the first lock run (`c8a3029`).
- **Code, tests and tables:** `research/hunt/r117_zero_hero_lock/`. `results/report.md` has every table, every period.
  `results/anatomy.md` has where the money is. `results/describe.json` has why FT3 rejects options.
- **Experiment entries:** `research/experiments/entries/r117-*.yaml` (12, all failures).
- **Research only:** nothing in the app changes.
- **Signal source: NOT the default** (CLAUDE.md), on by Boss's choice and labelled in every table.
  - FT3 picks its option from the option chain at the mark: the option's own price, its IV against the ATM's, and its
    distance in straddles.
  - Hero picks by the option's own price band.
  - The locks read the held option's premium, which CLAUDE.md allows for stops and locks.

## The answer first

**1. Why we missed 27 Oct.**
- **FT3 never bought the 22700 PE.** At every mark from 11:00 to 12:55, some FT3 test turned it down. I cannot say which
  test at which mark: 27 Oct is not in the data I can open. Details in "27 Oct itself" below.
- **The most likely reasons, from 2021-26:**
  - by Boss's chart, before about 12:40 it cost more than Rs 10 (outside FT3's Rs 6-10 band);
  - after that, an option priced Rs 6-10 is usually too far from spot. **91%** of those fail FT3's distance test
    (more than 1.55 straddles away; 30,807 of 33,757 option-marks);
  - of those close enough, **87% of puts** fail the IV test (1,231 of 1,421; calls 80%, 1,230 of 1,529). Cheap OTM puts
    carry downside skew, so their IV sits above the ATM's.
  - FT3 is strict on purpose: it had no NIFTY ticket on 37 of 273 NIFTY expiries.
- **The Hero ticket was switched off** (since 22 Oct), so it could not buy anything.
- **Even if FT3 had bought it at Rs 10, FT3 would have lost.** FT3 has no stop and no target and holds to 15:05. The
  option was back at Rs 0.05 by then, so a Rs 5,000 ticket would have lost about Rs 5,050. The run was only catchable
  with a profit lock.

**2. Would a profit lock make these tickets pay? No. 0 of 12 locks pass.**
- **On FT3 (478 tickets, 2021-26), today's hold-to-15:05 is the best exit.** It makes +Rs 1,805 a ticket (+Rs 8.6 lakh in
  all). No lock beats it in all three periods (design, walk-forward, final).
- **A lock helps on days like 27 Oct, but those days are the smaller side.** Of the 121 FT3 tickets that reached 3x:
  - **52 round-tripped** into a loss by 15:05, like 27 Oct. Holding lost Rs 2.0 lakh on them. A lock turns that into
    +Rs 1.0 to 4.8 lakh.
  - **69 were still running at 15:05.** Holding made **Rs 26.8 lakh** on them. Every lock sells those early and gives
    back Rs 4.3 to 23 lakh.
  - Expiry heroes mostly peak in the last half hour (R98: 48% peak 15:00-15:20). So a lock sells the very tickets that
    pay for all the losers.
  - FT3's whole record rests on a few days: its 5 best tickets make Rs 7.2 lakh of its Rs 8.6 lakh.
- **The closest miss is the step lock (L3: lock 2x once 4x, 4x once 8x...). It is not a finding.**
  - It beat holding in design (+Rs 2,129 vs +2,018 a ticket) and in the final (+2,202 vs +1,531).
  - It lost in walk-forward 2024-25 (+679 vs +1,568).
  - It changed only 5 of the 51 final tickets, and random exit times did as well (twin p 0.95).
  - It is below 0 in the final without its 3 best days.
- **On Hero (847 tickets, NIFTY and SENSEX, 2021-26), every exit loses.** The current 25% trail from the fill loses the
  least, −Rs 61 a ticket. Every lock without that stop loses about 3 to 4 times as much (−Rs 178 to −226), because about 8
  in 10 Hero tickets never reach 3x and ride to near zero. This is R55's finding again, now on 2021-26 data.

**3. What can go on paper: nothing new.**
- FT3 stays exactly as frozen (`research/hunt/r98/FORWARD_TEST.md`). Changing its exit would restart its forward test,
  and no lock earned a separate labelled paper variant beside it.
- Hero stays off unless Boss switches it back on. If it comes back, it keeps the 25% trail from the fill.
- Nothing live.

## 27 Oct itself

- **Why it cannot be replayed:**
  - the lake ends 19 Oct 2026;
  - the Dhan token is expired (HTTP 401 when tried today);
  - the phone uploaded 27 Oct (`live-2026-10-27.irlive`, 40 MB, in the IraAlgo data repo), but it is sealed with Boss's
    backup passphrase. This session does not have the passphrase and must not have it.
- **The replay is ready.** Once the prints are in the lake (a renewed token, or Boss opening the recording on their own
  PC):

      python3 research/hunt/r117_zero_hero_lock/r117day.py NIFTY 2026-10-27 22700 P

  It prints, for each of FT3's 24 marks:
  - the option's close, its distance in straddles and its IV ratio;
  - which FT3 test failed;
  - FT3's pick that mark, if any.
- **What each exit would have done:** the option's real minute closes are not available, so the table uses only the
  numbers from Boss's chart:
  - entry about Rs 10 (the 12:45-12:50 price);
  - best close about Rs 65 (the ~80 spike is taken to be a single high, not a close);
  - 0.05 at the close.
  - A Rs 5,000 FT3 ticket is 500 units, so each rupee of exit price is Rs 500. Charges are about Rs 60.
  - The Hero ticket is 1 lot (65 units, about Rs 650 at Rs 10), so its rupees are about 13% of the table's.
  - Real sales happen at the next minute's open, which in a fast fall can be lower.

  | exit (rule) | what it would sell at | about |
  |---|---|---|
  | FT3 today: hold to 15:05 | Rs 0.05 | −Rs 5,050 |
  | Hero today: 25% trail from the fill | 0.75 × 65 ≈ Rs 49, if it never dipped 25% below the fill first (else stopped near Rs 7.50) | +Rs 19,000 (or −Rs 1,300) |
  | L1 half at 3x, trail the rest 50% | half at Rs 30, half at ≈ Rs 32 | +Rs 10,500 |
  | L2 half at 2x, trail the rest 50% | half at Rs 20, half at ≈ Rs 32 | +Rs 8,000 |
  | L3 step lock | peak 6.5x is short of 8x, so the floor stays at 2x: Rs 20 | +Rs 5,000 |
  | L4 trail 40% from the peak once 2x | 0.60 × 65 ≈ Rs 39 | +Rs 14,500 |
  | L5 trail 50% from the peak once 2x | ≈ Rs 32 | +Rs 11,000 |
  | L6 after 14:00, trail 30% once 2x | ≈ Rs 45 if it was still above that at 14:00; else the 14:00 close | up to +Rs 17,500 |
  | L7 25% stop, trail widens to 50% once 2x | ≈ Rs 32 (if no early 25% dip) | +Rs 11,000 |

- On this one day every lock wins and holding loses. That is why the idea looks good in hindsight. The 2021-26 tables
  below show the other side: the many days when the option keeps running after the lock has sold.
- FT3's two real 27 Oct tickets (FINNIFTY 24200 PE at 9.85, MIDCPNIFTY 13400 CE at 8.50, about −Rs 8k together) also
  rode to near zero. Their minute paths are not available, so whether a lock would have caught anything on them is
  unknown.

## What was tested

- **Data:** the Drive lake copied 27 Oct: rolling ATM±10 1-minute options and index minutes, 2021 to 19 Oct 2026.
  Expiry days are R98's for FT3 and R50 / R54's for Hero.
- **Entries, unchanged, with exact parity checked in code:**
  - **FT3:** `ft3_app.py`, the frozen app version. It reproduces 478 tickets at +Rs 1,805 exactly.
    - The rule: marks 11:00-12:55; OTM priced Rs 6-10; IV ratio ≤ 1.0333; distance ≤ 1.5466 straddles; a LIMIT capped at
      price × 1.2 + 0.10 for 3 bars; Rs 5,000; R98 charges; out at the 15:04 bar.
    - All five indices: 256 design, 171 walk-forward and 51 final tickets.
  - **Hero:** R55's engine. Its 25% trail from the fill reproduces R55's 248 tickets / −Rs 14,507 and 107 / +Rs 1,559
    exactly, ticket by ticket.
    - The rule: the bar closing 12:30; 1 lot CE + 1 lot PE; nearest eligible OTM priced Rs 5 to min(30, 1,000 / lot);
      out at 15:05.
    - Run from the first lake expiry (NIFTY Aug 2021, SENSEX May 2023): 314 design, 373 walk-forward and 160 final
      tickets.
- **Periods:**
  - design: expiry days to Dec 2023;
  - walk-forward: 2024-25;
  - final: 1 Jan - 19 Oct 2026, read once.
  - Nothing was fitted. Honest note: R98 and R50-R55 had already read these ENTRIES. The locks were new to them.
- **The 7 locks** (12 tests: Hero's 1 lot cannot be halved, so L1 and L2 are FT3 only). Each reads the held option's
  minute closes and sells at the next minute's open:
  - L1 / L2: sell half at 3x / 2x, then trail the rest 50% from the best close;
  - L3: step lock (floor 2x once 4x, 4x once 8x, 8x once 16x, 16x once 32x);
  - L4 / L5: trail 40% / 50% from the best close, armed at 2x;
  - L6: from 14:00, once 2x, trail 30%;
  - L7: the 25% trail from the fill, widened to 50% once 2x (R55's logged idea).
  - None of L1-L6 has a stop. The rest of the ticket sells at 15:05.
- **Gates (all needed):**
  - mean per ticket above today's exit in design AND walk-forward AND final;
  - final mean > 0, and > 0 without the 3 best days;
  - final drawdown not worse than today's;
  - random-twin p < 0.05 (2,000 twins: each changed ticket sold at a random minute instead);
  - Benjamini-Hochberg q ≤ 0.10 over m = 9,300,059 tests (every logged family, earlier tests taken as p = 1, including
    R98's 8,280 and the 52 zero-hero rules);
  - at least 40 final tickets.

## Results

Mean is per ticket. FT3 tickets are Rs 5,000 on five indices; Hero tickets are 1 lot (up to Rs 1,000) on NIFTY and SENSEX.
"Reached" is the best minute close from the fill to the end; it is the same for every exit of an entry. "Worst run" is
the most tickets lost in a row, and the rupees of the worst run of consecutive losing tickets. Signal source:
option-price rule (not the default).

### FT3 (n = 478: design 256, walk-forward 171, final 51)

| exit | design mean | walk-fwd mean | final mean | win | mean | total | reached 3x / 5x / 10x | sold ≥ 3x | worst run | max DD | total w/o 3 best days (all / final) | twin p | passes? |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **hold to 15:05 (today)** | +2,018 | +1,568 | +1,531 | 16% | **+1,805** | +8,62,909 | 25% / 15% / 6.7% | 11.1% | 24, −1,18,871 | 2,38,223 | +3,92,590 / −1,21,870 | | today |
| 25% trail from the fill (ref.) | −174 | −171 | +1,114 | 23% | −35 | −16,776 | same | 1.9% | 18, −20,462 | 1,25,683 | −1,27,509 / −20,304 | | ref. |
| L1 half at 3x, trail rest 50% | +51 | +631 | +776 | 27% | +336 | +1,60,410 | same | 10.9% | 16, −79,111 | 2,23,196 | −81,587 / −69,513 | 0.007 | no |
| L2 half at 2x, trail rest 50% | +11 | −204 | +113 | 37% | −55 | −26,451 | same | 7.1% | 14, −61,407 | 2,42,509 | −2,60,683 / −94,513 | 0.127 | no |
| L3 step lock | +2,129 | +679 | +2,202 | 23% | +1,618 | +7,73,396 | same | 10.3% | 23, −1,13,945 | 2,55,910 | +3,03,077 / −87,635 | 0.947 | no |
| L4 trail 40% once 2x | −540 | −409 | +1,147 | 35% | −313 | −1,49,715 | same | 6.3% | 14, −64,522 | 3,51,928 | −4,17,410 / −1,04,569 | 0.172 | no |
| L5 trail 50% once 2x | +700 | −113 | +1,400 | 30% | +484 | +2,31,427 | same | 6.9% | 14, −64,522 | 2,58,259 | −2,05,731 / −1,00,189 | 0.708 | no |
| L6 after 14:00, 30% once 2x | +804 | +994 | +263 | 28% | +814 | +3,89,206 | same | 8.8% | 17, −78,264 | 2,54,482 | −7,723 / −94,699 | 0.158 | no |
| L7 25% stop, 50% once 2x | +426 | −327 | +2,522 | 21% | +380 | +1,81,789 | same | 1.9% | 18, −23,980 | 95,120 | −89,738 / −24,454 | 0.101 | no |

- L1's low twin p (0.007) says random sell times would do even worse than L1. It does not make L1 better than holding.
- Every q is 1.0 at m = 9.3 million; it is also 1.0 within the zero-hero families alone (m = 9,230).

### Hero (n = 847: design 314, walk-forward 373, final 160)

| exit | design mean | walk-fwd mean | final mean | win | mean | total | reached 3x / 5x / 10x | sold ≥ 3x | worst run | max DD | total w/o 3 best days (all / final) | twin p | passes? |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **25% trail from the fill (today)** | −89 | −59 | −14 | 16% | **−61** | −51,966 | 17% / 10% / 2.2% | 1.4% | 37, −4,537 | 54,919 | −73,133 / −14,030 | | today |
| hold to 15:05 (ref.) | −238 | −162 | −192 | 12% | −196 | −1,65,804 | same | 6.4% | 56, −30,383 | 1,66,464 | −1,99,962 / −55,979 | | ref. |
| L3 step lock | −243 | −170 | −142 | 17% | −192 | −1,62,768 | same | 6.3% | 33, −13,116 | 1,64,724 | −1,96,926 / −46,310 | 0.935 | no |
| L4 trail 40% once 2x | −262 | −213 | −188 | 25% | −226 | −1,91,827 | same | 4.6% | 14, −8,823 | 1,95,840 | −2,11,948 / −41,817 | 0.996 | no |
| L5 trail 50% once 2x | −267 | −165 | −207 | 21% | −211 | −1,78,549 | same | 5.2% | 25, −8,823 | 1,81,887 | −2,05,314 / −43,853 | 1.000 | no |
| L6 after 14:00, 30% once 2x | −225 | −165 | −118 | 22% | −178 | −1,50,850 | same | 6.4% | 27, −9,376 | 1,56,649 | −1,78,222 / −38,648 | 0.837 | no |
| L7 25% stop, 50% once 2x | −104 | −70 | −33 | 14% | −76 | −63,995 | same | 1.3% | 44, −5,490 | 65,102 | −78,917 / −15,845 | 0.735 | no |

### Where the money is (all 2021-26 tickets, total Rs; `results/anatomy.md`)

| FT3 group | n | hold (today) | L1 | L3 | L5 | L6 | L7 |
|---|---|---|---|---|---|---|---|
| reached 3x, then lost if held (like 27 Oct) | 52 | −2,03,108 | +4,78,401 | +1,40,745 | +2,95,497 | +2,07,204 | +98,687 |
| reached 3x, still in profit at 15:05 | 69 | +26,82,759 | +12,98,751 | +22,49,393 | +13,48,837 | +16,07,108 | +3,72,796 |
| never reached 3x | 357 | −16,16,742 | −16,16,742 | −16,16,742 | −14,12,906 | −14,25,106 | −2,89,695 |

| Hero group | n | 25% trail (today) | hold | L3 | L5 | L6 | L7 |
|---|---|---|---|---|---|---|---|
| reached 3x, then lost if held | 70 | +11,009 | −30,292 | +7,300 | +27,353 | +35,213 | +8,041 |
| reached 3x, still in profit at 15:05 | 74 | +28,012 | +2,25,414 | +1,90,858 | +1,26,248 | +1,49,028 | +26,137 |
| never reached 3x | 703 | −90,987 | −3,60,926 | −3,60,926 | −3,32,150 | −3,35,091 | −98,173 |

- **FT3:**
  - A lock wins up to Rs 6.8 lakh on the round trips;
  - it gives up Rs 4.3 to 23 lakh on the late runners;
  - on the 357 tickets that never reach 3x, L1 and L3 change nothing (no stop), and the locks armed at 2x save only a
    little.
- **Hero:** the losers (703 tickets that never reach 3x) are the whole story. Only a stop (today's trail, or L7) keeps
  them small.

## Limits

- **27 Oct could not be replayed;** the per-mark reasons above are the 2021-26 pattern, not that day. The chart
  numbers in the 27 Oct table are Boss's, rounded.
- **FT3's final period is short:** 51 tickets. Its record is very lumpy (its 3 best final days are worth more than its
  final total). Its 95% range of the mean in the final is −Rs 2,967 to +8,083 a ticket.
- **Fill model:**
  - FT3 uses R98's fractional quantity (the app buys whole lots) and a 1-tick sell spread.
  - In a fast fall, a real trail sale could be worse than the next minute's open. That would only hurt the locks
    further.
- **Pre-Nov 2024 Hero tickets** (NIFTY lot 50 / 25, a Rs 20-30 cap) were never in R55. They extend its sample; the
  Nov 2024 - 13 Oct 2026 part matches R55 exactly.

## What this means for the app

- **Nothing changes.**
- FT3 stays frozen and is judged as registered (≥ 100 tickets and 12 months).
- No R117 lock passed, so none runs as a paper variant beside FT3.
- Hero, if Boss switches it back on, keeps the 25% trail from the fill.
- If Boss still wants a profit lock on paper by choice, the least-bad by these tables is the step lock (L3) on FT3. It
  would be Boss's choice, labelled as not having passed:
  - a separate paper variant, with its own record;
  - logged in the Autopilot change timeline and undoable in one tap;
  - never live, and never added to FT3's record.
