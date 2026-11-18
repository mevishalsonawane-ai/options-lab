# R201 pre-registration: option-selling ideas from the published literature (side study, 18 Nov 2026)

Written 18 Nov 2026, **before any R201 profit or loss was computed**; hash and time in `results/PREREG_STAMP.txt`.
Owner: Boss (they/them). Boss: "Try more harder, find what all can we do, search internet, find all the papers published
for it, get info on internet, and then give me results". **Research only. The app stays buy-only.**

## 1. The literature (searched 18 Nov 2026) and what is new to test

| source | finding | tested before here? | R201 test |
|---|---|---|---|
| Coval & Shumway (2001, J. Finance); Bakshi & Kapadia (2003, RFS 16(2)) | Selling S&P 500 puts / straddles earns a volatility risk premium; delta-hedged option gains are negative and **more negative at times of higher volatility** | the plain sale: R195-R200; volatility *timing* only as a VIX-level filter (R195 S2) | **VX1, VX2**: sell after a VIX spike |
| Muravyev & Ni (2020, JFE 136(1)): option returns change sign from day to night | Delta-hedged S&P 500 option returns are about -1% close-to-open and +0.3% open-to-close; **sellers earn overnight, lose intraday** (gross of costs) | P1 (R195): a 4-step-wing fly 15:16 -> 09:21, lost | **ON1-ON3**: overnight sale with cheap far hedges, out at the open |
| Bhat, Pandey & Rao (2024, J. Futures Markets 44(8)): day-night asymmetry, Nifty 2017-2020 | Short Nifty option strategies: **positive, significant overnight returns, negative intraday**; the premium is "mainly a reward for overnight risk"; weaker on jump days | as above | ON1-ON3 (NIFTY, BANKNIFTY, SENSEX) |
| Goyal & Saretto (2009, JFE 94): implied minus realised volatility | Options whose implied vol is high relative to historical realised vol earn lower returns (sell them), **cross-sectionally** | time-series filter only (R195 S3, V2) | **XS1**: each day sell on the index with the highest IV / RV |
| Event-volatility (practitioner work; no academic India study found) | Implied vol rises into scheduled events and drops after ("crush"), but large moves often beat it | never | **EV1**: sell into RBI policy / Budget / election days |
| Beckmeyer, Branger & Gayda (2023): 0DTE | US 0DTE buyers lose; sellers profitable after fees | expiry-day sales R195 E1-E3, R199 H6-H8, R200 IF3-IF4: all lost | not repeated |
| Pillai (SSRN 2026 preprint): Nifty VRP with realistic frictions, 2015-2025 monthly | every short-vol strategy negative net of costs, losses from tail risk | consistent with R195-R200 | none |

**Signal source:** none directional for the structures (strikes from the index); India VIX for VX1 / VX2; the 09:20
straddle and index realised volatility for XS1; a calendar for EV1. No rule reads an option's own chart.

**Delta hedging** (the papers' setting) is not tested: at one lot an ATM straddle's delta is near zero at entry, and a
NIFTY future is a whole lot of 65, so a one-lot seller cannot hedge a fraction of it. Every R201 rule has a defined max
loss through bought wings or stops.

## 2. Data, engine, costs, periods

R195's stores and engine (`r195engine.run` with its overnight branch: exit on the next session at a given minute, never
below the model), dated charges, 'liq' slippage (stop ticks doubled), today's lot, R195's margin model. Design 2 Aug 2021
- 31 Dec 2025; 2026 a second look; the forward window from 16 Nov 2026 is the clean holdout.
**Exit "at the open"** = an order for the next session's 09:15 minute, filled at that minute's open (the first trade of
the day). The opening minute has the widest spreads; the 'liq' model charges 1-4 ticks by the minute's traded volume
and 5 bps, and 2x slippage is gated.

## 3. The candidates (7, fixed now)

| id | rule |
|---|---|
| **ON1-NIFTY** | Overnight hedged straddle: decision 15:15, sell C(K0), P(K0), buy C(K0 + 10), P(K0 - 10) steps (cheap far wings, inside the lake's window); exit at the next session's 09:15 open. Not on an expiry day or the session before one (no expiry carry, no ELM). |
| **ON2-BANKNIFTY** | ON1 on BANKNIFTY. |
| **ON3-SENSEX** | ON1 on SENSEX. |
| **VX1-NIFTY** | R195's S1 (09:20 straddle, 25% stop per leg) only on days after a VIX spike: yesterday's India VIX close >= 1.15 x its close five sessions before that. |
| **VX2-NIFTY** | ON1 only when India VIX at 15:15 today >= 1.15 x its close five sessions before. |
| **XS1-ALL** | Each day at 09:20, among the indices with a trade that day, compute IV / RV20 (R195 S3's IV from the straddle, RV20 from daily closes); trade S1 (09:20 straddle, 25% stop per leg, the index's own lot) on the index with the highest ratio. |
| **EV1-NIFTY** | Event days: RBI policy decision days, Union Budget days and the 4 Jun 2024 election result. Decision 15:15 on the session before, the ON1 structure; exit at 13:01 on the event day (after the RBI's 10:00 statement and most of the Budget speech). **About 34 design events: it cannot meet gate 3 (40+); reported for the record.** |

RBI decision days used: 2021-08-06, 2021-10-08, 2021-12-08, 2022-02-10, 2022-04-08, 2022-05-04 (unscheduled, 14:00),
2022-06-08, 2022-08-05, 2022-09-30, 2022-12-07, 2023-02-08, 2023-04-06, 2023-06-08, 2023-08-10, 2023-10-06, 2023-12-08,
2024-02-08, 2024-04-05, 2024-06-07, 2024-08-08, 2024-10-09, 2024-12-06, 2025-02-07, 2025-04-09, 2025-06-06, 2025-08-06,
2025-10-01, 2025-12-05, 2026-02-06, 2026-04-08, 2026-06-05, 2026-08-05, 2026-10-07. FY2023-24, FY2024-25 and FY2026-27
are from RBI's published schedules; the others are from public reports and memory, noted as such. Budget days:
2022-02-01, 2023-02-01, 2024-02-01, 2024-07-23, 2025-02-01, 2026-02-01; election: 2024-06-04. A date the market did not
trade is skipped.

## 4. Gates (R200's 1-8)

1 design net > 0 and post-Nov-2024 part > 0; 2 2026 net > 0; 3 40+ trades in each; 4 > 0 without the 5 best, in each;
5 twin p < 0.05: ON1-ON3 the same structure entered at a random minute 13:00-15:15 the same day with the same exit; VX1 /
VX2 random-day twins from S1-NIFTY / ON1-NIFTY; XS1 a random eligible index each day; EV1 random non-event sessions
with the same entry and exit; 6 BH q <= 0.10, m = 4,905 + 7 = **4,912**; 7 worst month >= -Rs 13k and drawdown <=
Rs 40k over design + 2026; 8 design > 0 at 2x slippage. Win rate, margin and "possible at Rs 56k" are reported.
