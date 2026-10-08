# h33 pre-registration: unscheduled intraday news and price shocks, option BUYING only

Written 2026-10-08, before any option P&L was computed. Only these had been looked at: headline download status and
counts, and the FREQUENCY of index z-scores (minutes per day above 3/4/5/6/8), used to pick the z grid below. No
returns after any event were looked at.

## Question
When market-moving news breaks during Indian market hours, does buying a 1-ITM option in the direction of the first
move ("follow"), or against it ("fade"), N minutes later, beat costs? Same question for price-only shocks.

## Data
- Index minutes, option minutes: research/obuy loaders (Dhan, nearest series, ATM+-10).
- Option volume: h26 feature panel (`vc5 + vp5`: ATM+-2 call + put volume over the 5 minutes ending at t).
- Headlines: Economic Times monthly news sitemaps and Moneycontrol monthly post sitemaps (URL slug = headline text,
  `lastmod` = time). Publication time = suffix-minimum of lastmod over article ids (ids grow with creation time), which
  strips later edits. GDELT DOC API returned 429 (rate limited / shared IP); Google News RSS has dates only
  (08:00 GMT placeholder), so it cannot time a headline to the minute. Both are recorded as unusable.

## Events
**Price shocks (arm P).** Per index, sigma = RMS of the daily std of 1-minute close returns (09:20-15:25) over the
previous 5 sessions. z_W(t) = (c_t / c_{t-W} - 1) / (sigma sqrt(W)), W in {1, 3}. A shock is the first minute t in
09:25-14:30 with |z_W(t)| >= Z, Z in {5, 8}; after a shock the next one can start 30 minutes later. Direction =
sign of the shock move. Volume filter VB in {none, vb(t) >= 2} where vb = V5(t) / median V5 of the previous 5
sessions.

**News events (arm N).** Keyword classes on the slug (research/hunt/h33/news.py, frozen): rbi -> BANKNIFTY,
govt (govt / cabinet / SEBI / GST / duties) -> NIFTY, bank_corp (HDFC Bank, ICICI, SBI, Kotak, Axis, IndusInd, ...)
-> BANKNIFTY, heavy_corp (Reliance, Infosys, TCS, Airtel, ITC, L&T, Adani, ...) -> NIFTY, geo (war, attack, missile,
tariffs, sanctions, ...) -> NIFTY, any (union) -> NIFTY. Market-wrap / reaction / opinion headlines (sensex, nifty,
shares, stocks, market, live, buy, sell, what / why / will ...) and commentary sections are excluded. An event is the
first class headline after >= 120 minutes without one, published 09:20-14:30 on a weekday. Intensity I in {any (>= 1),
burst (>= 3 class headlines within 20 minutes, either source)}. First move = c_{h+N} / c_{h-1} - 1 where h is the
headline minute. Confirmation C in {none, |move| / (sigma sqrt(N+1)) >= 2}.

## Trades
Entry: signal minute = event minute + N, N in {1, 3, 5}; fill at the next minute's open (obuy engine). Side: follow or
fade. Contract: 1-ITM, nearest expiry (expiry days allowed), 1 lot as of the date. App fills (+-5/10 bps) and app
charges, PLUS the h24 real half-spread per side (NIFTY .16%, BANKNIFTY .16%, FINNIFTY .42%, MIDCPNIFTY .21%,
SENSEX .20% assumed), and a 1.5x spread stress. One position at a time per (variant, index); square-off 15:10.

Exits (8, fixed):
| id | rule |
|---|---|
| P15 | +15 premium points target, -15 point stop, 30-min hard time stop |
| P20 | +20 target, -15 stop, 30 min |
| P25 | +25 target, -15 stop, 30 min |
| P30 | +30 target, -15 stop, 30 min |
| LIQ | Liquidity arm: -15% stop, 20-min time stop unless up 5%, then to 15:10 |
| LOCK | -15 point stop, profit-lock ladder on R = 30 points (at +7.5 lock 0, +15 lock +7.5, +22.5 lock +15), 30 min |
| T15 | out after 15 minutes, no stop |
| T30 | out after 30 minutes, no stop |

Variants: arm P = 5 indices x W 2 x Z 2 x VB 2 x N 3 x side 2 x exits 8 = 1,920.
Arm N = 6 classes x I 2 x C 2 x N 3 x side 2 x exits 8 = 1,152. Total 3,072, all counted.

## Tests (PRE = before 2025-10-01 only)
1. Net per trade and Rs/day (per session in the period) at the real spread; also 1.5x.
2. Random-time baseline: per real trade, one random (minute 09:25-14:30, coin-flip side) trade on the same index and
   day with the same exit; B = 2,000 draws; p = (1 + #{random mean >= real mean}) / (B + 1). BH across all 3,072
   (variants with net <= 0 get p = 1).
3. Hansen SPA and White RC over the (days x variants) daily P&L matrix vs not trading.
4. Anchored walk-forward by year (2022, 2023, 2024, 2025 to Sep): trade the variant with the best earlier net
   (>= 20 earlier trades).
5. Survivor = net > 0 at real AND 1.5x spread, BH q < 0.05, SPA p < 0.10, walk-forward net > 0, >= 30 PRE trades.
6. Holdout (2025-10-01 ..) once, for survivors only. If none, the best PRE variant of each arm is shown on the
   holdout as INFO ONLY (not a test).
7. Descriptive: index continuation after shocks/news (follow sign, +15 / +30 min), overlap between news events and
   price shocks, headline timing check on a few known intraday events.
