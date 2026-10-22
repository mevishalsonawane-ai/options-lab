# R102: Deep learn NIFTY and BANKNIFTY (22 Oct 2026)

Boss asked: "Deep learn nifty and banknifty." The study is `research/DEEP_NIFTY_BANKNIFTY.md`; Jarvis's facts are in
`research/knowledge/index_facts.yaml` (27 cards, each with its sample size); the code, pre-registration and results are in
`research/hunt/r102_deep/`. Every number is from our Drive lake or a public source checked on 22 Oct 2026. Research only:
nothing in `android/**` or `.github/**` changed; the knowledge pack is rebuilt in the next app session.

## The 20 facts (descriptive, not trading rules; n = sample size)

1. BANKNIFTY is NIFTY times 1.16: daily correlation 0.877, beta 1.157 (n 5,086 days, 2006-2026); on 5-minute bars 0.842 and 1.094
   (n 89,575). The five big banks are 63% of BANKNIFTY and 29.5% of NIFTY 50.
2. A normal NIFTY day moves 0.74-1.05% (daily sd; 0.744% in 2025, 1.051% in 2026 to July), a BANKNIFTY day 0.79-1.36%. Over 20
   years NIFTY's sd is 1.316% and BANKNIFTY's 1.734% (n 5,087).
3. The drift is overnight: NIFTY's close-to-close mean is +0.051% a day and its open-to-close mean -0.035% (n 5,087); BANKNIFTY
   +0.065% / -0.039%. An intraday-only trader does not get the drift.
4. The median day range is 0.91% on NIFTY (n 2,288 minute days) and 1.13% on BANKNIFTY (n 1,185). By 10:30 the day has made 61% /
   65% of it, by 13:00 82% / 87%; at 14:30 the range is already complete on 44% / 53% of days.
5. The first minute spans 0.21% (NIFTY) / 0.30% (BANKNIFTY), the first five 0.27% / 0.38%. Minute for minute, 09:15-09:30 is 8x
   the 10:30-13:00 rate.
6. The open (first five minutes) is the day's high or low on 41% of NIFTY days and 46% of BANKNIFTY days.
7. Gaps of 0.5% or more open 20% of NIFTY days and 34% of BANKNIFTY days (n 5,086). A 0.5-1% gap fills by the close 42% (NIFTY) /
   53% (BANKNIFTY) of the time; a 2%-plus NIFTY gap closes on its own side 94-96% of the time.
8. When a gap fills it fills early: median 52 minutes after the open on NIFTY (n 1,245 gaps of 0.25%+), 32 on BANKNIFTY (n 694).
9. Direction is a coin at every horizon: the next bar carries the previous bar's sign 48% of the time at 5 and 15 minutes and 49-51%
   at one hour (n 167,082 / 52,640 / 9,154 NIFTY pairs); the first 30 minutes' direction holds to the close 53% / 50% of the time.
10. The 20-year daily momentum (+5.8 bps NIFTY, +12.4 bps BANKNIFTY per day, n 5,086) is gone: +2 to +6 bps in 2018-2026 and -9.4 /
    -24.6 bps in the Jul-Oct 2026 holdout (n 65).
11. Weekday means nothing: NIFTY's median range is 0.89-0.94% on every weekday (n 451-459 each); no weekday survives BH (best q 0.43).
12. Expiry day is not a bigger day: NIFTY range 0.92% on 289 expiry days vs 0.91% on 1,999 others; BANKNIFTY 1.17% (185) vs 1.12%.
13. Budget day doubles the range (2.13% / 2.62%, n 6). RBI days have been up days (+0.36% / +0.50%, n 30) with a normal range. FOMC
    (n 39) and US CPI (n 58) sessions look like any other day.
14. VIX is the range dial, not a direction dial: VIX 9-15 means a 0.75% / 1.01% median day, VIX above 20 means 1.72% / 2.37%
    (n 1,509 each). VIX over-states NIFTY's next month on 81% of days (n 4,507).
15. The 09:20 ATM straddle is a median 0.96% of spot on NIFTY (n 1,222) and 1.49% on BANKNIFTY (2.15% in 2025, 2.78% in 2026 as a
    monthly). The close beats it on 7.1% / 6.5% of non-expiry days and 40.5% / 37.8% of expiry days (n 259 / 185).
16. The expiry-day decay clock: 84% of the 09:20 ATM straddle is left at 10:30, 70% at 12:00, 61% at 13:00, 40% at 14:30, 22% at
    15:00, 13% at 15:15 (NIFTY, n 259; BANKNIFTY 85 / 73 / 61 / 38 / 23 / 11%, n 186). A normal day: 96.5% at 10:30, 86% at 15:29.
17. The straddle falls 09:15 -> 09:30 on 69% of normal NIFTY days (median -1.8%; expiry -8.0%) and 14:30 -> 15:29 on 77% (median
    -3.6%; expiry -59%). One ATM option moves 3.3% a minute in the first 15 minutes and 13.5% a minute in expiry's last hour.
18. The chain always prices the crash: the ATM-5 put is dearer than the ATM+5 call by 2.56 IV points on NIFTY (91% of days) and 2.11
    on BANKNIFTY (90%). PCR of OI is 0.86 on both (n 1,363 days).
19. Max pain is not a magnet: the expiry close lands a median 0.60% (NIFTY) / 0.68% (BANKNIFTY) from the previous day's max pain,
    within one strike only 18% / 21% of the time.
20. Who is positioned (2025 - Jul 2026): FIIs net short index futures by 61-72% of their book, clients net long by 30-46%; clients hold
    58% of index-option longs, prop desks write 23-24% of the shorts (n 3,577 days). None of 36 participant rules was confirmed.

## What it means for our trading

* **Direction cannot be called**, at one minute, five, fifteen, an hour, a day or a week, from the participant file, the weekday,
  the expiry or the gap. 70 pre-registered rules, 8 passed design, 1 confirmed on the holdout, and that one is the cash index's
  1-minute staleness (0.1 bps, below the 1.25-1.54 bps cost of one ATM round trip). This agrees with forty earlier studies.
* **Size can be called**: VIX regime, the front-loaded day (the first 15 minutes make 40-55% of the range), the expiry decay clock,
  R16's 30-60-minute range forecast. Stops, targets and whether to trade at all should come from these; entries should not pretend
  to know the side.
* **Costs are lower than the repo assumes**: lots are 65 (NIFTY) and 30 (BANKNIFTY) since January 2026, a round trip is about Rs 70-76
  in charges. The lot table in `research/obuy/config.py` still says BANKNIFTY 35 and should get its Jan 2026 row in a later change.
* **BANKNIFTY's chain is a monthly chain now**: its 09:20 straddle is 2.8% of spot and the close beats it on 2% of days; its expiry
  comes once a month (last Tuesday). Weekly-expiry habits (hero tickets, expiry scalps) belong to NIFTY (Tuesday) and SENSEX
  (Thursday) only.
* **Nothing here changes paper or live.** Liquidity BANKNIFTY's plain-rules record for the 26 Oct decision is untouched. The one
  untested idea the facts point at is defined-risk selling of expiry-day decay, already an open question in the knowledge pack.

## What was checked before pushing

`python -m pytest research/hunt/r102_deep/tests -q` (13 tests: synthetic lake, expiry calendar, holdout lock, look-ahead guard,
BH, the 70-test count, the facts file's numbers against the study, the pack loader), `python -m pytest research/brain/tests -q`,
`python -m research.brain.features catalog --check`, `python -m research.experiments.validate` (5 new r102 entries), an in-memory
`build_pack.build()` (0 errors, 27 index facts, 1.84 MB), the CLAUDE.md secret grep (nothing), no raw data or `__pycache__` in git
(results under 160 KB each), and an adversarial self-review of the diff.
