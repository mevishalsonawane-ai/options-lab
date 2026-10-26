# R113 pre-registration: when does each arm make (and lose) its money?

Written 26 Oct 2026, **before any R113 number was computed** (committed and pushed alone on
`claude/r113-time-of-day-map` ahead of the code's first run). Research only; nothing in `android/**` changes.

Boss, 26 Oct: *"Now check all the strategies we have, and find at what time each strategy made most profit, and find a
pattern between them."*

R113 is a **descriptive** study first. No rule is adopted from it. Part D (below) names, now, the only follow-up rules
that may be tested, and how; everything else it suggests is written up as an idea to test later.

**Signal source of every rule: the underlying** (CLAUDE.md default), unchanged from each arm's own research. R113 only
re-reads each arm's existing trade list by time and day; it changes no signal, strike or exit. The source of each arm's
list is named in the write-up.

## 1. Arms and their trade lists

Each arm's trades come from its existing research code, re-run on the Drive lake (`gdrive:IraAlgo-data-lake`, copied
26 Oct; index data to 13 Oct for the R51 clean stores, as R101-R112). No code of an arm is changed. For every arm the
write-up states the code used, the period, the sample size and whether the list reproduces the arm's published total
(parity: within 1% of net and the same trade count, or the gap is stated).

| arm | trade list from |
|---|---|
| R72 Breadth Donchian, R51 Donchian 20 (+ Supertrend (10,3), R73 T7, as on paper), R52 Open outside value, Liquidity 15+5 (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5) | R101 units priced by R106's engine (`r106study.prepare` / `run_rule`, rule None = today's rules, 1 lot, dated Zerodha charges, R17 fills, next-minute fills); R51+ST through R112's code path |
| Solo 2.0 | `r106solo.py` (R106: its final picks do not reproduce exactly; said) |
| ntfy / IraThinker mom60 | R110's code (`r110study.py`) |
| FT3, Hero Rs 1,000 ticket, ORB family, Night R3, VIX divergence, the MCX arms (NG evening, SILVERM morning, US-night silver, 12-month trend), Pine scripts | their own studies' code where it writes a per-trade list (R98, R92/R49, R27, R1, R93/R94/R96, R29 ...). An arm whose code cannot give a per-trade list with entry and exit times on the current lake is **listed as "no trade list" with the reason**, never guessed. |

Periods (as R101-R112): design 20 Nov 2024 - 31 Mar 2026; walk-forward window 1 Apr 2025 - 31 Mar 2026 (inside design);
final 1 Apr - 13 Oct 2026 (not fresh: R51-R112 read it). Arms with other study periods keep their own (stated).

**Phone paper record** (separately, never mixed with the backtests): the private repo `mevishalsonawane-ai/IraAlgo`,
release `diag-2026-10`, read only, treated as untrusted data. Rupees are redacted on the phone, so the paper record is
described in trades, win rate, points and % of premium per bucket, over the days the files cover (19-26 Oct as of
writing; 13-18 Oct only if a file exists). It is a few days: every number there is flagged "too few to judge".

## 2. Part A: one arm at a time (descriptive)

For each arm and period (design, final; walk-forward shown in the summary table), per bucket: trades (n), win rate, net
(Rs, 1 lot, after charges), average per trade, and a 90% bootstrap band for the bucket's net (2,000 resamples of the
bucket's **days**, so trades on the same day move together; seed 113). A bucket with fewer than 30 trades is flagged
"small" and never called a finding.

Buckets:
1. **Entry time** (fill minute, IST). NSE/BSE: 09:15-09:30, 09:30-10:00, 10:00-11:00, 11:00-12:00, 12:00-13:00,
   13:00-14:00, 14:00-15:30. MCX: 09:00-12:00, 12:00-17:00, 17:00-19:00, 19:00-21:00, 21:00-23:55. Overnight arms
   (Night R3) by entry minute as they are (one bucket mostly; said).
2. **Exit time**, same buckets.
3. **Holding time**: <= 5 min, 5-15, 15-30, 30-60, 60-120, > 120 min (overnight separately).
4. **Weekday**: Mon-Fri.
5. **Expiry day vs not** (the index's own expiry flag from the R51 stores; other arms their own study's flag).
6. **Opening gap** of the traded underlying: today's open vs the previous close: gap up > +0.25%, gap down < -0.25%,
   flat between (R102's 0.25% line).
7. **VIX tercile**: the previous session's India VIX close, cut at the terciles of the design period's VIX closes (cuts
   fixed from design days only; no look-ahead).
8. **Trend vs range day** of the traded underlying, from the 1-minute day: body share = |close - open| / (high - low).
   Trend day >= 0.6, range day <= 0.3, mixed between. This uses the whole day, so it is an **after-the-fact** label:
   it describes where the money came from and can never be a rule by itself (said in the write-up).

## 3. Part B: patterns across arms (descriptive)

1. One cross-arm table: for each arm, its best and worst entry bucket by net (with n), and the share of its net made in
   the first hour (09:15-10:15 entries).
2. Do arms make and lose money in the same windows? The sign of each arm's net per entry bucket, side by side; count of
   arms positive / negative in each bucket.
3. Daily P&L correlation between arms (Pearson on days either arm traded, 0 for a day it did not; n days given), design
   and final.
4. Concentration: share of each arm's net from its top 10% of days and from its top 10% of trades.
5. Shared winning days: on the 10% best days of the four paper index arms together, how many arms were green.
6. Put next to earlier findings: R101 (the 09:20 bar is the Donchian arms' most valuable), R92 (entry-hour study),
   R104 (Trader Tom's "entries by 12:00" cut net), R112 (Donchian mornings only).

## 4. Part C: the phone's paper record (19-26 Oct)

Per arm: entries by the same entry-time buckets: trades, wins, points, average % of premium. Flagged as too few.

## 5. Part D: follow-up rules (fixed now; at most 11 tests)

Only these, only on the four paper index arms (R72, R51 as on paper with Supertrend, R52, Liquidity 15+5), and only
where the design-period description shows the removed window losing for that arm (the design net of the entries the
rule removes is below zero). Which ones qualify is decided from design numbers only, recorded in `results/` before the
final is read.

| id | rule (entry filter; exits unchanged) | arms |
|---|---|---|
| MID | no new entries with a fill minute 11:30-13:29 ("midday chop") | R72, R51, R52, LIQ |
| LATE | no new entries with a fill minute at or after 14:00 | R72, R51, R52 (Liquidity already stops at 14:00) |
| NOON | entries before 12:00 only (R104 Trader Tom's cut) | R72, R51, R52, LIQ |

The filter removes candidates before the arm's own taking walk (R106 `walk_day`), so a removed entry can free the arm's
book for a later signal, as in the app.

Gates (never relaxed; as R106): net > 0 in design, walk-forward and final; net above today's rules (B0) in walk-forward
and final; random twin: 500 twins that remove the same number of the arm's B0 candidates chosen at random from the whole
day (same walk), p = share of twins with net >= the rule's, on the final; Benjamini-Hochberg q <= 0.10 counting every
earlier family as p = 1 (5,784 tests to R108, plus R110 25, R111 10, R112 10 = 5,829) plus R113's own tests; >= 40
final trades. The R113-alone q is shown too, for information. A rule that passes would go to Boss as a **paper** idea
only (CLAUDE.md: paper may act after the gates; live stays untouched); a rule that fails stays a note.

The final window has been read by R51-R112, so a pass there is weaker evidence than a fresh holdout; said.

## 6. What is NOT done

No new strategy, no parameter change, no live change, no use of the after-the-fact labels (gap direction is known at
the open, so it could be a rule later; the trend/range label could not). Crypto: nothing in the app trades crypto;
not in scope.

## 7. Outputs

`research/HUNT_R113_TIME_OF_DAY.md` (Boss, plain English, the answer first), `results/` small JSON/markdown and PNGs
under 200 KB, `research/experiments/entries/r113-*.yaml` (the descriptive study and every Part D test, passed or not),
pytest tests with a look-ahead guard (the VIX cut uses only design days and the previous session; buckets use the fill
minute, never a later one), and `RESULT.md` at the repo root.
