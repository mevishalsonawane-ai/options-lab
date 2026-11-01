# R144c pre-registration: the losing trades of six arms (FT3, Expiry Hero, Hero Rs 1,000 ticket, Night R3, VIX divergence, IraThinker Flow)

Written 1 Nov 2026, **before any R144c number** (pushed alone, before the first trade list is built). Research only:
nothing in `android/**` or any shared file changes. One of four parallel studies (R144a-d), each on its own arms.

Boss, 1 Nov: *"Find all the lost trades in indian market from our study for current arms, and find what all makes it
lose money ... volume, liquidity, order flow ... find patterns for each arm, so it could help us to ignore those
trades."*

## 1. The arms and how each trade list is rebuilt (no rule is changed)

| arm | app rules | research engine reused | signal source (CLAUDE.md) |
|---|---|---|---|
| FT3 near-ATM morning | `Ft3Rules` | `research/hunt/r98/ft3_app.py` via R113's `adapters/ft3.py` loop (parity: 478 tickets 2021-26) | **option-price pick** (clock + the option's own Rs 6-10 price, IV vs ATM, straddles): labelled, reported apart |
| Expiry Hero | `HeroRules` | no runnable research engine on the lake (hero_deep's files are not in the repo); a **line-for-line Python port of `HeroRules`** with R25's fills and the app's charges | **option-price** (the ATM straddle's premium + NIFTY's 15-min move; strike by price band) |
| Hero Rs 1,000 ticket | `HeroTicketRules` (trail from the fill) | R55's cell 1230/1505/T0 via R117 / R113's `adapters/hero.py` (parity: design 248 / -Rs 14,507, final 107 / +Rs 1,559) | clock only; strike by the option's own price band |
| Night (R3) | `NightRules` | R27's B0 via R113's `adapters/night_r3.py` | the index (close location, day move, breadth) + day-end OI build-up |
| VIX divergence | `VixDivRules` | R1's `n13_vixdiv` + `run_engine` via R113's `adapters/vixdiv.py`, run on every day | the index and India VIX |
| IraThinker Flow | `FlowRules` + `FlowArm` | R110 priced only S1 on 2024-26 release data; here a **line-for-line Python port of `FlowRules.judge`** (scan, IraScore, VIX gate, Time Machine with Java's `Random(19)`, risk gate) on lake 5-minute bars; ATM nearest-expiry option, 1 lot, premium stop 20%, index stop / 2R index target, flat 15:15, own expiry day skipped | the index (+ India VIX's break) |

The two ports are checked against the Kotlin unit tests' examples where they exist, and against the published counts
where those exist (Hero: R12's 51 design / 4 holdout NIFTY trades are on another data source, so only the order of
magnitude is compared and said). An arm whose list cannot be rebuilt is listed with the reason, never guessed.

1 lot (or the arm's own ticket size), each engine's own fills and charges (real charges and slippage).

## 2. Periods

- **Unseen two years:** 1 Dec 2022 - 19 Nov 2024 (where the data and the arm exist: SENSEX options from 2023).
- **Design:** 20 Nov 2024 - 31 Mar 2026.
- **Final:** 1 Apr - 19 Oct 2026 (the lake's end; the arms' own engines may stop at 12-13 Oct).
- Earlier days (2021 - Nov 2022) are shown in the anatomy only, labelled "early".
- Expiry arms (FT3, Expiry Hero, Hero ticket): every number also per expiry day and per index.

Honest limits, said now: the arms were built on studies that read parts of all these periods (R98 read 2021-26; R50-R55
read Nov 2024 on; R1 and R27 had their own holdouts). **No skip filter below has ever been run on any of them**; the
filters are chosen on design only (section 5), so the final and the unseen years are fresh for the filters, not for the
arms.

## 3. Features at entry (each from data stamped before the fill minute; a look-ahead guard test checks it)

- **Price action and time:** minutes to the contract's expiry; entry minute; the morning range (09:15 to the last closed
  minute) / the 20-day average daily range (ADR20); the move from today's open, signed the trade's way, / ADR20; the
  gap / ADR20; the strike's distance from spot in ATM straddles.
- **Volume:** the traded option's volume over the 15 minutes before entry (log); its share of the ATM's volume.
- **Liquidity:** a spread proxy (the median 1-minute high-low / close of the option over 15 minutes; the data has no
  bid/ask or depth, said); the share of the last 15 minutes with a print; the premium level; the option's OI.
- **OI / options:** the option's OI change since 09:15 (%); the PCR (put OI / call OI over ATM +-10); the option's IV and
  IV / ATM IV; the skew (IV of the put two steps out less the call two steps out); the biggest-OI strike's distance from
  spot in straddles (an OI wall, a GEX stand-in); the ATM straddle / the move India VIX implies to the close.
- **Market context:** India VIX's previous close and its change today to entry; breadth (share of the lake's F&O stocks
  above their previous close, where `equity_1m` covers the day); the previous day's range / ADR20 (day regime); Night
  also gets the US overnight move **only as an after-the-fact descriptor** (it happens after entry: never a filter), if a
  US index series is in the lake, else said missing. The futures basis needs history the lake's live-futures file does
  not hold before Oct 2026: said missing.
- **Order-flow proxies (labelled proxies, not real order flow):** the option's tick-direction volume delta over 15
  minutes (sum of volume x sign(close - open) / volume); the OI-price quadrant over 15 minutes (long build-up, short
  covering, short build-up, long unwinding).

## 4. Anatomy per arm (descriptive)

Loser rate; money lost by exit type; bunching (losers on days where every trade of the arm lost; longest losing streak);
the 10 worst trades; the loser rate and average net by quintile of each feature (quintile edges from design), in each
period. **"Steady"** = the slice loses more often than the arm's average AND has a negative average in every period
(unseen, design, final), with >= 30 trades in each; otherwise said "too small to tell" or "not steady".

## 5. Skip filters (at most 3 per arm), chosen on DESIGN ONLY by this fixed recipe

- Candidates: for each feature in section 3 (except the Night US move), "skip the lowest fifth" and "skip the highest
  fifth" (design quintile edges), plus "skip the category" for the quadrant.
- A candidate is eligible if, **on design only**: it drops 10-40% of the arm's design trades, the dropped trades' loser
  rate is above the arm's and their net is below 0, and it lowers the design sum of losing days in BOTH design halves
  (20 Nov 2024 - 30 Jun 2025, 1 Jul 2025 - 31 Mar 2026).
- Ranked by the design sum-of-losing-days saved per trade dropped; the top 3, skipping a candidate whose dropped set
  overlaps an already chosen one by > 50%. Cuts frozen to 4 decimals and written to `results/filters.json` before the
  final and unseen years are read.
- An arm with < 60 design trades gets **no filter** (describe only): too small to tell.

## 6. Gates (R142's lose-less gates on the two test periods)

In each of **final** and **unseen** separately: (1) the sum of losing days smaller; (2) max drawdown, worst day and worst
month no worse; (3) the arm still makes money (net > 0) - an arm that already lost money in a period fails this by
construction, said plainly; plus (4) beats 2,000 random twins (skip as many trades at random) on the sum of losing days
over final + unseen pooled, p <= 0.05; (5) BH q <= 0.10 over all R144c filters (one family); (6) >= 40 test trades and
>= 30 that differ. Reported for every filter: the win rate before -> after, against Boss's 65% rule; net; average win and
loss; n. Every raw p-value goes to `results/pvalues.json`.

A pass earns a **proposed** switch (shadow first for anything live, per CLAUDE.md), not a build.

## 7. Outputs

`r144c.py` and helpers, `test_r144c.py`, `results/*.json`, `research/HUNT_R144C_LOSS_EXPIRY_OTHER.md`,
`research/experiments/entries/r144c-*.yaml`.
