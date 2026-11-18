# R201: option-selling ideas from the published literature (side study, 18 Nov 2026)

Boss: "Try more harder, find what all can we do, search internet, find all the papers published for it, get info on
internet, and then give me results". **Research only. The app stays buy-only.**

- **Pre-registration:** `research/hunt/r201_literature/PREREG.md` (the literature table, 7 tests, BH m = 4,912;
  hash-stamped before any number).
- **Code:** `r201study.py`; 4 tests in `test_r201.py`. **Tables:** `results/report.md`, `results/final.json`.

## What the literature says (searched 18 Nov 2026)

- **Coval & Shumway (2001, J. Finance), Bakshi & Kapadia (2003, RFS):** selling index puts and straddles earns a
  volatility risk premium. Delta-hedged option gains are negative, and more negative when volatility is high.
- **Muravyev & Ni (2020, JFE):** S&P 500 sellers earn the premium **overnight** (about -1% close-to-open for the option
  buyer) and lose intraday (+0.3% open-to-close). This is gross of costs; commentators note that bid-ask spreads swamp it
  for most traders.
- **Bhat, Pandey & Rao (2024, J. Futures Markets):** the same holds for Nifty options (2017-2020). Short-option returns
  are positive overnight and negative intraday, and the premium is "mainly a reward for overnight risk".
- **Goyal & Saretto (2009, JFE):** options whose implied volatility is high relative to realised volatility do worse
  for the buyer. Sell the dear ones, cross-sectionally.
- **India studies:** variance risk is priced in Nifty options (market-neutral straddles earn negative returns for the
  buyer). One study reports the premium positive on about 75% of days (2022-2026). A 2026 SSRN preprint (Pillai)
  finds **every short-volatility strategy negative net of realistic costs** on monthly Nifty 2015-2025, the losses coming
  from tail risk.
- **Beckmeyer, Branger & Gayda (2023):** US 0DTE sellers profitable after fees, buyers lose. Our expiry-day tests (R195,
  R199, R200) found the opposite in India after Indian costs.
- **Event-volatility crush:** practitioner work only. Implied vol falls after the event, but large moves often beat it.

## Verdict

**0 of 7 pass.** One idea, **XS1 (the cross-index implied-vs-realised rank, after Goyal & Saretto), is the strongest
selling result in R195-R201**, and it still fails.

| id | idea (source) | design 2021-2025: trades / win / net / max DD | post-Nov-2024 | 2026 | gross / costs a trade | failed gates |
|---|---|---|---|---|---|---|
| **XS1-ALL** | each day, the 09:20 straddle with 25% stops per leg on the index whose IV / RV20 is highest (Goyal & Saretto) | 1,092 / 61.7% / **+Rs 5,22,283** / Rs 40,702 | 276 / 62.3% / +Rs 1,55,610 | 214 / 61.2% / +Rs 72,446 (**clean +Rs 24,299**) | Rs 577 / Rs 122 | 6 BH (q 0.22), 7 account stop |
| ON1-NIFTY | overnight hedged straddle, wings 10 steps, sold 15:16, out at the next 09:15 open (Muravyev & Ni; Bhat et al.) | 604 / 49.8% / -Rs 89,898 | | 123 / 50.4% / -Rs 13,871 | Rs 76 / Rs 219 | 1, 2, 4, 5, 6, 7, 8 |
| ON2-BANKNIFTY | ON1 on BANKNIFTY | 675 / 43.0% / -Rs 85,409 | | 190 / 24.2% / -Rs 2,36,386 | -Rs 106 / Rs 266 | 1, 2, 4, 5, 6, 7, 8 |
| ON3-SENSEX | ON1 on SENSEX | 329 / 43.8% / -Rs 69,963 | | 121 / 50.4% / -Rs 33,416 | Rs 0 / Rs 230 | 1, 2, 4, 5, 6, 7, 8 |
| VX1-NIFTY | the 09:20 straddle only after a VIX spike (Bakshi & Kapadia) | 71 / 54.9% / -Rs 5,686 | | 31 / 48.4% / -Rs 5,479 | Rs 20 / Rs 130 | 1, 2, 3, 4, 5, 6, 8 |
| VX2-NIFTY | ON1 on VIX-spike days | 38 / 47.4% / -Rs 23,193 | | 13 / 61.5% / +Rs 3,904 | -Rs 141 / Rs 238 | 1, 3, 4, 5, 6, 8 |
| EV1-NIFTY | hedged straddle sold on the eve of RBI / Budget / election days, out 13:01 (vol crush) | 21 / 47.6% / +Rs 13,302 | | 3 / -Rs 603 | Rs 749 / Rs 220 | 2, 3, 4, 5, 6 |

(1 lot at today's lot, after slippage and dated charges. XS1's "clean" 2026 drops 4 trades whose entry was a stale or
off-market print, R195 Amendment 1's check; in design only 21 of 1,092 trades were flagged, -Rs 3,522.)

## In plain English

- **The overnight premium the papers found is real in our data too, but it is tiny.** The NIFTY overnight sale earned
  about Rs 76 a trade before costs, against Rs 219 of costs (four legs in and out, and the wide opening minute). This
  matches the commentary on Muravyev & Ni: costs swamp the overnight edge for a retail trader. BANKNIFTY lost even
  before costs.
- **Selling after VIX spikes did not help:** too few trades, and small losses.
- **The event-day crush** made +Rs 13,302 on 21 events in 2021-2025 but lost on the 3 events in 2026. There are too few
  events to judge.
- **The cross-index rank (XS1) is the best selling idea we have found.** On each day, sell the 09:20 straddle (25%
  stops per leg) on whichever of the five indices has the richest implied volatility relative to its own recent
  movement.
  - It made money in every full year 2022-2025: +Rs 77k, +Rs 76k, +Rs 2.37 lakh, +Rs 1.44 lakh.
  - It beat choosing the index at random (twin p 0.001) and survived double slippage (+Rs 5.11 lakh).
  - **It still fails three ways:**
    - BH q 0.22 against the 0.10 bar, with 4,912 ideas logged.
    - In 2026 it made +Rs 72k, but +Rs 48k of that came from 4 FINNIFTY trades sold at stale prints. The clean
      210 trades made +Rs 24k and lost without their 5 best days.
    - Its worst month was -Rs 19.6k (Jan 2026) and its drawdown Rs 44.4k at 1 lot, past Boss's -Rs 13k / -Rs 40k
      stops.
  - It is a naked straddle with stops: about **Rs 2.6 lakh of margin per lot**, nearly five times Rs 56k.

**Bottom line after the literature:** the published edge in option selling (the volatility risk premium) shows up in
Indian index options, but at one lot it is mostly eaten by costs. Only the straddle family with stops keeps some of it,
and it needs about Rs 2.6 lakh and accepts months worse than -Rs 13k. If Boss's capital ever reaches about Rs 3 lakh and
the monthly stop is widened, XS1 is the one selling idea worth a paper forward test from 16 Nov 2026. It is not ready
for money, and it is not possible at Rs 56k.

## Caveats

R195's caveats apply: modelled slippage, the opening minute's spread (the 'liq' model, 2x gated), a margin model,
today's lot, stale prints in the monthly books (XS1's 2026 shows this risk directly), and 2026 as a second look. Some
RBI dates (2021-22, late 2025) come from public reports and memory. No delta hedging (not possible at one lot).
