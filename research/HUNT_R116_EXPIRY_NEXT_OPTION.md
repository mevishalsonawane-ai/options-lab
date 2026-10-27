# R116: on an expiry day, should the arms buy the NEXT expiry's option?

27 Oct 2026. Boss, on a monthly expiry day (NIFTY, BANKNIFTY, FINNIFTY and MIDCPNIFTY all expired, so R72, R51, R52,
Solo 2.0 and Liquidity skipped them): *"Today was expiry, so most strategies did not work. Why don't we use next expiry
options?"* Pre-registration with the data map: `research/hunt/r116_expiry_next_option/PREREG.md` (pushed in `ab1ab19`
before any profit or loss was computed). Research only; nothing in the app changes.

**Signal source of every rule: the index's own bars** (R72 / R51: 5-minute index bars; R52: the 09:15-09:19 index bar
against yesterday's value area; Liquidity: its 15 / 30 / 5-minute index book bars). Only the option bought changes: the
1-ITM call or put of the next expiry instead of the expiring one. Stops, locks and time exits stay on that option's
premium, exactly as each arm's rules today. 1 lot, after real charges and slippage.

## The answer first

**No. Keep skipping expiry days.** Where the data exist, buying the next expiry's option lost money on all four arms,
in design, in the walk-forward year and in the final:

| arm | design | walk-forward | final |
|---|---|---|---|
| R72 Breadth Donchian | 206 trades, -Rs 41,872 | 158 trades, -Rs 30,327 | 29 trades, -Rs 3,251 |
| R51 Donchian 20 + Supertrend | 266 trades, -Rs 31,128 | 197 trades, -Rs 28,044 | 34 trades, -Rs 7,983 |
| R52 Open outside value | 49 trades, -Rs 17,092 | 38 trades, -Rs 15,937 | 8 trades, -Rs 10,191 |
| Liquidity 15+5 (BANKNIFTY only) | 11 trades, -Rs 3,577 | 7 trades, -Rs 4,629 | no data |

Skipping (today's rule) is Rs 0, so skipping was better every time. Plain reasons:

- **Monthly expiry days, like today, were the worst.** On monthly-expiry days the next-expiry trades lost
  -Rs 39,471 (R72, 57 trades), -Rs 33,955 (R51, 74), -Rs 18,233 (R52, 15) and -Rs 3,577 (Liquidity, 11) in design.
  On ordinary weekly-only expiry days they were about break-even (R72 -Rs 2,401 on 149 trades; R51 +Rs 2,827 on 192;
  R52 +Rs 1,141 on 34), and then lost in the final (R72 -Rs 3,251, R51 -Rs 7,983, R52 -Rs 10,191).
- **The signals carry no edge on these days.** Buying the same next-expiry option at a random minute did as well or
  better (R72 in design: random entries averaged +Rs 10,077 against the signals' -Rs 41,872; final twin p 0.72 / 0.89 /
  0.99 for R72 / R51 / R52: the signals were no better than random).
- **The morning entries do not save it.** R113 found the arms make their money at 09:15-09:30 entries. On expiry days
  with the next expiry, those morning trades lost too (design: R72 40 trades -Rs 33,833; R51 55, -Rs 24,569; R52 all at
  09:20, -Rs 17,092).
- **It costs 2.5-5x more per lot.** NIFTY's next weekly about Rs 14,250 a lot vs Rs 5,808 for the expiring one;
  BANKNIFTY's next monthly about Rs 29,865 vs Rs 5,870. The 15% stop then risks about Rs 2,200 (NIFTY) or Rs 4,540
  (BANKNIFTY) a lot, so Boss's 2% risk cap would skip a single lot on an account below about Rs 1.1 lakh (NIFTY) or
  Rs 2.3 lakh (BANKNIFTY). The paper account's default (Rs 1 crore) is far above that.

**Two indices cannot be tested at all:** FINNIFTY and MIDCPNIFTY have no next-expiry prints anywhere (data map below).
BANKNIFTY has only 13 expiry days (to Jan 2026) and none in the final, so for BANKNIFTY the result is a direction
(-Rs 15,578 for R72 on 23 trades, -Rs 14,837 for R51 on 32), **not enough data to be sure**.

**Is the sample big enough?** Not in the final alone: at most 34 final trades per arm (the gate needs 40), because the
next weekly's prints exist on only 8 NIFTY and 5 SENSEX final expiry days. But the test already failed in design and in
the walk-forward year (200-270 trades on the Donchian arms), and the final pointed the same way. So the answer is "no"
where we can test, and "not enough data" for BANKNIFTY / FINNIFTY / MIDCPNIFTY.

**The same-day (expiring) option is worse still** for R72 (-Rs 89,574 on 412 trades in design), R51 (-Rs 1,14,833 on
544) and Liquidity (-Rs 19,904 on 42). **One lead, not a pass:** R52 (Open outside value) with the same-day option was
positive in all three periods (+Rs 13,509 on 92 trades, +Rs 960 on 71, +Rs 18,724 on 38 in the final; beat its random
twin, p 0.005), but it has only 38 final trades (< 40) and does not survive counting all earlier tests (q = 1;
q 0.04 counting R116's 8 alone). It stays a note.

**What can go on paper: nothing. Nothing goes live.** Every R116 test failed its gate, so per CLAUDE.md the results stay
notes (`research/experiments/entries/r116-*.yaml`).

## The data map (PREREG section 1, written before any number)

The arms skip an index on the days the R51 stores flag as its expiry (20 Nov 2024 - 13 Oct 2026). "With next prints" =
the next expiry's 1-minute contracts exist that day (1-ITM call and put coverage 09:15-15:05: median 1.0).

| index | expiry days design (walk-forward) | with next prints | expiry days final | with next prints | next expiry is | source |
|---|---|---|---|---|---|---|
| NIFTY | 72 (53) | **71 (52)** | 27 | **8** | the next weekly, 5-8 days out | release archive 56, lake MONTH 23 |
| SENSEX | 70 (51) | **14 (9)** | 27 | **5** | the next weekly, 6-7 days out | lake MONTH only (the week before a monthly) |
| BANKNIFTY | 17 (12) | **13 (9)** | 6 | **0** | the next monthly, 27-35 days out | release archive only, to Jan 2026 |
| FINNIFTY | 17 (12) | **0** | 6 | **0** | - | none |
| MIDCPNIFTY | 17 (12) | **0** | 6 | **0** | - | none |

- **Dhan's rolling data** (the Drive lake) keeps only the nearest weekly (WEEK) and the nearest monthly (MONTH). For
  BANKNIFTY, FINNIFTY and MIDCPNIFTY those two files are byte-identical in 2025 and 2026: there is no next month. For
  NIFTY and SENSEX the monthly is the exact next weekly only on the weekly expiry the week before a monthly expiry.
- **The `research-data` release** holds "the nearest expiry after the day" for NIFTY (to 13 Apr 2026) and BANKNIFTY (to
  23 Feb 2026), so the next expiry on an expiry day. It matches the lake on ordinary days, and the lake's exact monthly
  on the 17 NIFTY days where both exist (98.0-99.8% of minutes within 1%).
- **Rejected, not guessed:** BANKNIFTY 24 Dec 2024 (the archive's label 29 Jan vs the exchange's 30 Jan), NIFTY 6 Oct
  2026 (calendar end), days missing from the archive (NIFTY / BANKNIFTY 30 Dec 2025, BANKNIFTY 24 Feb 2026).
- **Today's case (a monthly expiry) has no final data at all:** every monthly-expiry day with next-expiry prints is in
  the design window.

## The tables (n with every number)

Cells: trades / trading days / win rate / **net** / worst day. Skipping (today) = 0 trades, Rs 0 in every period.

### Each arm on its index's expiry days

| arm | what is bought | design | walk-forward (Apr 25 - Mar 26) | final (Apr - 13 Oct 26) | final twin p |
|---|---|---|---|---|---|
| R72 Breadth Donchian | NEXT expiry's option | 206 / 80 d / 40% / **Rs -41,872** / Rs -14,428 | 158 / 57 d / 41% / **Rs -30,327** / Rs -14,428 | 29 / 12 d / 41% / **Rs -3,251** / Rs -5,593 | 0.722 |
| R72 Breadth Donchian | same-day option | 412 / 138 d / 28% / **Rs -89,574** / Rs -15,401 | 325 / 99 d / 28% / **Rs -94,103** / Rs -15,401 | 145 / 51 d / 36% / **Rs -2,905** / Rs -9,134 | 0.504 |
| R72 Breadth Donchian | same-day option, on N's days | 216 / 80 d / 29% / **Rs -57,989** / Rs -8,390 | 165 / 57 d / 29% / **Rs -47,851** / Rs -8,390 | 29 / 12 d / 34% / **Rs -5,055** / Rs -2,182 |  |
| R72 Breadth Donchian | next expiry, monthly-expiry days | 57 / 14 d / 40% / **Rs -39,471** / Rs -14,428 | 49 / 10 d / 41% / **Rs -33,037** / Rs -14,428 | 0 trades |  |
| R72 Breadth Donchian | next expiry, weekly-only expiry days | 149 / 66 d / 40% / **Rs -2,401** / Rs -4,795 | 109 / 47 d / 41% / **Rs 2,710** / Rs -4,544 | 29 / 12 d / 41% / **Rs -3,251** / Rs -5,593 |  |
| R51 Donchian 20 + Supertrend | NEXT expiry's option | 266 / 86 d / 44% / **Rs -31,128** / Rs -16,748 | 197 / 61 d / 43% / **Rs -28,044** / Rs -16,748 | 34 / 13 d / 44% / **Rs -7,983** / Rs -5,903 | 0.887 |
| R51 Donchian 20 + Supertrend | same-day option | 544 / 148 d / 31% / **Rs -114,833** / Rs -15,353 | 409 / 104 d / 31% / **Rs -101,549** / Rs -15,353 | 204 / 54 d / 38% / **Rs -2,301** / Rs -7,248 | 0.338 |
| R51 Donchian 20 + Supertrend | same-day option, on N's days | 276 / 86 d / 35% / **Rs -43,622** / Rs -8,169 | 204 / 61 d / 36% / **Rs -36,986** / Rs -8,169 | 36 / 13 d / 42% / **Rs -6,188** / Rs -2,127 |  |
| R51 Donchian 20 + Supertrend | next expiry, monthly-expiry days | 74 / 18 d / 43% / **Rs -33,955** / Rs -16,748 | 56 / 12 d / 36% / **Rs -39,041** / Rs -16,748 | 0 trades |  |
| R51 Donchian 20 + Supertrend | next expiry, weekly-only expiry days | 192 / 69 d / 45% / **Rs 2,827** / Rs -7,735 | 141 / 50 d / 45% / **Rs 10,996** / Rs -4,479 | 34 / 13 d / 44% / **Rs -7,983** / Rs -5,903 |  |
| R52 Open outside value | NEXT expiry's option | 49 / 44 d / 47% / **Rs -17,092** / Rs -10,092 | 38 / 33 d / 47% / **Rs -15,937** / Rs -10,092 | 8 / 8 d / 25% / **Rs -10,191** / Rs -2,866 | 0.989 |
| R52 Open outside value | same-day option | 92 / 72 d / 49% / **Rs 13,509** / Rs -9,005 | 71 / 53 d / 49% / **Rs 960** / Rs -9,005 | 38 / 32 d / 63% / **Rs 18,724** / Rs -2,515 | 0.005 |
| R52 Open outside value | same-day option, on N's days | 49 / 44 d / 51% / **Rs 10,849** / Rs -4,227 | 38 / 33 d / 47% / **Rs 3,014** / Rs -4,227 | 8 / 8 d / 38% / **Rs -8,065** / Rs -2,515 |  |
| R52 Open outside value | next expiry, monthly-expiry days | 15 / 10 d / 33% / **Rs -18,233** / Rs -10,092 | 13 / 8 d / 31% / **Rs -15,448** / Rs -10,092 | 0 trades |  |
| R52 Open outside value | next expiry, weekly-only expiry days | 34 / 34 d / 53% / **Rs 1,141** / Rs -5,947 | 25 / 25 d / 56% / **Rs -489** / Rs -5,947 | 8 / 8 d / 25% / **Rs -10,191** / Rs -2,866 |  |
| Liquidity 15+5 | NEXT expiry's option | 11 / 6 d / 45% / **Rs -3,577** / Rs -2,177 | 7 / 4 d / 29% / **Rs -4,629** / Rs -2,177 | 0 trades | 1.000 |
| Liquidity 15+5 | same-day option | 42 / 12 d / 24% / **Rs -19,904** / Rs -8,823 | 32 / 8 d / 25% / **Rs -14,552** / Rs -8,823 | 16 / 5 d / 12% / **Rs -9,134** / Rs -5,083 | 0.892 |
| Liquidity 15+5 | same-day option, on N's days | 11 / 6 d / 9% / **Rs -9,848** / Rs -3,262 | 7 / 4 d / 0% / **Rs -8,573** / Rs -3,262 | 0 trades |  |

"On N's days" = the same-day option on exactly the days the next expiry could be traded, the fair side-by-side. On
those days the next expiry lost less than the same-day option on the Donchian arms in design (R72 -Rs 41,872 vs
-Rs 57,989) but more on R52 (-Rs 17,092 vs +Rs 10,849); in the final it lost more on R51 and R52. Both lost on the
Donchians; neither beats skipping.

### The next expiry's option, by index

| arm | index | design | walk-forward | final |
|---|---|---|---|---|
| R72 | NIFTY | 153 / 66 d / 39% / **Rs -30,439** / Rs -6,465 | 119 / 48 d / 40% / **Rs -18,579** / Rs -6,465 | 15 / 7 d / 47% / **Rs 3,016** / Rs -1,763 |
| R72 | BANKNIFTY | 23 / 9 d / 39% / **Rs -15,578** / Rs -7,962 | 21 / 7 d / 38% / **Rs -15,967** / Rs -7,962 | 0 trades |
| R72 | SENSEX | 30 / 14 d / 47% / **Rs 4,144** / Rs -4,795 | 18 / 9 d / 50% / **Rs 4,218** / Rs -962 | 14 / 5 d / 36% / **Rs -6,267** / Rs -5,593 |
| R51 | NIFTY | 193 / 71 d / 44% / **Rs -15,607** / Rs -8,720 | 146 / 52 d / 42% / **Rs -15,210** / Rs -8,720 | 19 / 8 d / 47% / **Rs -1,997** / Rs -4,001 |
| R51 | BANKNIFTY | 32 / 13 d / 47% / **Rs -14,837** / Rs -9,640 | 25 / 9 d / 40% / **Rs -15,496** / Rs -9,640 | 0 trades |
| R51 | SENSEX | 41 / 14 d / 44% / **Rs -684** / Rs -7,735 | 26 / 9 d / 46% / **Rs 2,661** / Rs -2,794 | 15 / 5 d / 40% / **Rs -5,986** / Rs -5,903 |
| R52 | NIFTY | 36 / 36 d / 47% / **Rs -14,051** / Rs -5,947 | 28 / 28 d / 54% / **Rs -4,955** / Rs -5,947 | 4 / 4 d / 25% / **Rs -5,730** / Rs -2,808 |
| R52 | BANKNIFTY | 7 / 7 d / 29% / **Rs -7,925** / Rs -4,778 | 6 / 6 d / 17% / **Rs -8,832** / Rs -4,778 | 0 trades |
| R52 | SENSEX | 6 / 6 d / 67% / **Rs 4,884** / Rs -2,576 | 4 / 4 d / 50% / **Rs -2,150** / Rs -2,576 | 4 / 4 d / 25% / **Rs -4,462** / Rs -2,866 |
| LIQ | BANKNIFTY | 11 / 6 d / 45% / **Rs -3,577** / Rs -2,177 | 7 / 4 d / 29% / **Rs -4,629** / Rs -2,177 | 0 trades |

SENSEX was slightly positive in design for R72 (+Rs 4,144 on 30 trades) but lost in the final (-Rs 6,267 on 14).

### What one lot costs (median entry premium x lot, design) and the 2% risk cap

| arm | index | same-day option | next expiry's option | next: risk to a 15% stop | account for 1 lot at 2% |
|---|---|---|---|---|---|
| R72 | NIFTY | Rs 5,808 | Rs 14,250 | Rs 2,198 | Rs 1,09,875 |
| R72 | BANKNIFTY | Rs 5,870 | Rs 29,865 | Rs 4,540 | Rs 2,26,988 |
| R72 | SENSEX | Rs 3,979 | Rs 12,302 | Rs 1,905 | Rs 95,265 |
| R51 | NIFTY | Rs 5,734 | Rs 14,445 | Rs 2,227 | Rs 1,11,338 |
| R51 | BANKNIFTY | Rs 6,235 | Rs 30,033 | Rs 4,565 | Rs 2,28,248 |
| R51 | SENSEX | Rs 4,137 | Rs 13,243 | Rs 2,046 | Rs 1,02,322 |
| R52 | NIFTY | Rs 6,392 | Rs 14,107 | Rs 2,176 | Rs 1,08,802 |
| R52 | BANKNIFTY | Rs 6,870 | Rs 26,097 | Rs 3,975 | Rs 1,98,727 |
| R52 | SENSEX | Rs 4,759 | Rs 11,627 | Rs 1,804 | Rs 90,202 |
| LIQ | BANKNIFTY | Rs 5,831 | Rs 28,417 | Rs 4,323 | Rs 2,16,128 |

A pricier option moves less in % for the same index move, but the arms' stops and locks are in %, so each loss is
bigger in rupees.

### Reported only: the later monthly (13-56 days out) on NIFTY / SENSEX weekly expiry days

Not what Boss asked about (not the next expiry) and never gated (PREREG section 3, world M):

| arm | design | walk-forward | final | same-day option on those days, final |
|---|---|---|---|---|
| R72 Breadth Donchian | 151 / 71 d / 40% / **Rs 9,396** / Rs -6,944 | 117 / 54 d / 42% / **Rs 6,633** / Rs -5,794 | 47 / 25 d / 43% / **Rs 1,568** / Rs -4,602 | 56 / 27 d / 41% / **Rs 3,594** / Rs -2,486 |
| R51 Donchian 20 + Supertrend | 191 / 75 d / 43% / **Rs 13,805** / Rs -9,166 | 145 / 57 d / 46% / **Rs 19,242** / Rs -5,794 | 66 / 27 d / 39% / **Rs 7,532** / Rs -4,602 | 84 / 29 d / 37% / **Rs 2,788** / Rs -2,486 |
| R52 Open outside value | 25 / 25 d / 56% / **Rs 15,655** / Rs -2,957 | 21 / 21 d / 62% / **Rs 17,071** / Rs -2,957 | 10 / 10 d / 50% / **Rs -1,479** / Rs -2,713 | 16 / 16 d / 50% / **Rs -3,637** / Rs -2,515 |

Small positives on R72 / R51 (one lot here costs about Rs 22,000). It did not beat random entries (final twin p 0.49 /
0.16 / 0.36), and on those same days the same-day option was also positive in the final (R72 +Rs 3,594, R51 +Rs 2,788).
It is a lead for a fresh pre-registered test at most, never a change now.

### Gate (PREREG section 5)

BH over 9,300,055 tests (every earlier logged test + R116's 8). Pass = net above skipping in design, walk-forward and
final; at least 40 final trades; beats the random-entry twin; q <= 0.10.

| test | passed | final twin p | q (all tests) | q (R116 alone) | why not |
|---|---|---|---|---|---|
| NR72 | no | 0.722 | 1 | 1 | lost in design, walk-forward and final; 29 final trades |
| NR51 | no | 0.887 | 1 | 1 | lost in design, walk-forward and final; 34 final trades |
| NR52 | no | 0.989 | 1 | 1 | lost in design, walk-forward and final; 8 final trades |
| NLIQ | no | 1.000 | 1 | 1 | lost in design and walk-forward; no final data |
| SR72 | no | 0.504 | 1 | 1 | lost in design, walk-forward and final |
| SR51 | no | 0.338 | 1 | 1 | lost in design, walk-forward and final |
| SR52 | no | 0.005 | 1 | 0.04 | positive everywhere, but 38 final trades (< 40) and q 1 over all tests |
| SLIQ | no | 0.892 | 1 | 1 | lost in design, walk-forward and final; 16 final trades |

## What to record from now on (a proposal; needs Boss's go-ahead)

The question will come back on every expiry day, and Dhan's history never keeps the next expiry's minutes. So the only
way to build evidence, above all for BANKNIFTY / FINNIFTY / MIDCPNIFTY, is to record it as it happens:

1. **A log-only "would-be" record on expiry days** (no order, no paper trade): for each arm's index signal on its own
   expiry day, the next-expiry 1-ITM contract it would buy, the entry premium, and where today's exits would have sold
   it. Shown beside the day's skip ("its expiry day: no trades; would have bought ... next expiry, would-be -Rs 1,200").
   Off the order and tick path, released first when memory is short, never touching open positions' watch (CLAUDE.md).
2. **Keep those next-expiry contracts' minutes** (the ATM±2 of the next expiry, expiry days only) in the IraAlgo data
   repo, so a future R-study can price any arm on them.
3. **Re-test after about six months** (about 26 weekly expiry days each for NIFTY and SENSEX, and 6 monthly ones for every index),
   when each arm can reach 40 fresh trades. Same gate, pre-registered again.

Added to `TODO.md` as E1 (unchecked, waits for Boss).

## Limits

- The final window (Apr - 13 Oct 2026) was read by many arms studies, but never on expiry days (they all skipped them).
  Boss's 27 Oct is after the lake's end (13 Oct) and is not in any number here.
- Next-expiry prints come from two sources (the release archive and the lake's monthly); they agree closely where both
  exist. The archive has 20 strikes around the 09:20 index, so a trade after a very large move could find no contract
  (it then does not fill; median coverage was full).
- The same strike is bought in every world (1-ITM from the minute before the entry, from the nearest series' ATM
  label); the expiry square-off at 15:05 is applied to the next-expiry option too (the app's expiry rule; the arms'
  usual 15:10 would change little).
- Liquidity's random twins carry no index exits (as R51's Liquidity twin). Each arm is tested alone (its own walk), not
  inside the phone's shared cap of 2 positions a direction.
- **Solo 2.0 was not run:** it needs the R57 model and the brain cube, not in this session (as R101 / R106).
- Counting every earlier test (9.3 million) makes a pass nearly impossible; here every next-expiry test failed long
  before that gate.

## Files

- `research/hunt/r116_expiry_next_option/PREREG.md`, `r116cfg.py`, `r116lib.py` (pure), `r116data.py` (data map and
  next-expiry contracts), `r116study.py` (worlds, design / final / decide), `r116report.py` (these tables and the
  entries), `test_r116.py` (22 tests incl. the look-ahead guards).
- `results/datamap.json`, `design.json`, `final.json`, `decision.json`, `tables.md`, `FINAL_READ`.
- Re-run: copy the lake (index_1m, index_daily, options_rolling_1m WEEK + MONTH) and the `research-data` release files
  to `~/.cache/iraalgo`, `R51_CLEAN=1 python research/hunt/r51/r51data.py build`, then `python
  research/hunt/r116_expiry_next_option/r116data.py map`, `r116study.py design | final | decide`, `r116report.py`
  (the final refuses a second read).
