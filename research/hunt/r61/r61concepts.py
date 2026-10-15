"""R61 Part 1: the feature encyclopedia Solo / Jarvis learns from. One source, two outputs:

    python research/hunt/r61/r61concepts.py        # writes CONCEPTS.md (plain English) and concepts.json (for the app)

Each concept: what it is; how it is computed from data the app has (or "not available on the phone"); how traders use
it; known pitfalls; what OUR OWN research found (study, sample, verdict); the signal role it may play under the house
rule "signal on the underlying, trade the option"; and the R61 inputs built from it. `r61_result` is filled from the
R61 study once it has run (until then: "not run yet").
"""
from __future__ import annotations

import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
VERSION = "2026-10-15"
NOT_RUN = "not run yet (the lake download needs approval; see HUNT_R61_SOLO_3.md)"

CTX = "context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side"
SIZE = "size/context only: our research says it predicts how much the index may move, not which way"


def S(study, doc, sample, finding, verdict):
    return dict(study=study, doc=doc, sample=sample, finding=finding, verdict=verdict)


CONCEPTS = [
    dict(
        id="oi", name="Open interest (OI) and OI change", aka=["OI", "change in OI"],
        what="The number of option (or futures) contracts still open at a strike: every contract has one buyer and one "
             "seller, so OI counts positions, not trades. OI rises when new positions open and falls when they close.",
        formula="Read directly from the chain for each strike and side. R61: near-the-money OI = call + put OI at the 5 "
                "strikes nearest the money (log10); OI change = % change of call + put OI at the 11 nearest strikes "
                "since the day's first chain minute; 'writer tilt' = put OI % change minus call OI % change over 30 "
                "minutes.",
        phone="yes: ChainAnalytics.oiData / oiProfile / oiChange from the live chain (Dhan or Zerodha)",
        use="Traders read rising OI as fresh conviction and falling OI as positions being closed. Big call-OI additions "
            "are read as call writing (a ceiling), big put-OI additions as put writing (a floor).",
        pitfalls=["OI is published with a lag (NSE updates it every few minutes), so minute-level OI changes are stale.",
                  "You cannot tell from OI alone whether the buyer or the seller opened the position.",
                  "Lot-size changes and expiry roll-offs move OI without any view behind it."],
        research=[S("OI_FILTER", "research/OI_FILTER.md", "BANKNIFTY, two years (Feb 2024-Feb 2026), 145 arm x check "
                    "results", "No OI or volume check improved the Liquidity arm in both years; the ORB 'volume surge' "
                    "kept 3% of trades and never reached t = 2.", "failed"),
                  S("H26", "research/HUNT_H26.md", "2,268 variants, five indices", "Put-minus-call OI change had a "
                    "15-minute IC of about 0.03, too small to pay costs (holdout -Rs 45 a day). The filter 'skip when "
                    "the 15-minute OI build-up opposes the trade' (BU15): holdout +Rs 81 a day on 876 trades, p 0.035, "
                    "pre-holdout q 0.13.", "failed (BU15 near-miss, paper-log only)"),
                  S("R15 F4", "research/HUNT_R15_DAILY_FILTERS.md", "Liquidity arm days", "Futures OI rising as a "
                    "daily filter: -Rs 9.4 a day, q 0.50.", "failed")],
        role=CTX, features=["oi_near_log", "oi_near_chg_pct", "writer_tilt30"]),
    dict(
        id="buildup", name="OI build-up types", aka=["long build-up", "short build-up", "short covering",
                                                    "long unwinding"],
        what="Price change and OI change read together: price up + OI up = long build-up (new buyers); price down + OI "
             "up = short build-up (new sellers); price up + OI down = short covering (sellers closing); price down + "
             "OI down = long unwinding (buyers closing).",
        formula="R61: the index's 30-minute change and the 30-minute change of call + put OI at the 11 strikes nearest "
                "the money: +2 long build-up, +1 short covering, -1 long unwinding, -2 short build-up, 0 if either is "
                "flat. Classic build-up uses FUTURES OI; the lake has index-futures minutes only from Jul 2026, so R61 "
                "uses near-the-money option OI instead.",
        phone="yes (option OI version); futures build-up from the futures quote stream (recorded from Jul 2026)",
        use="A trend is trusted when it comes with build-up on its side (long build-up for up moves) and doubted when it "
            "is only covering or unwinding.",
        pitfalls=["It labels the past 30 minutes; it is a description, not a forecast.",
                  "Option OI mixes calls and puts and both buyers and writers; the labels come from futures."],
        research=[S("IV1 O3", "research/HUNT_IV1_OPTION_MARKET.md", "5 indices, 7,339 trades", "A fixed 'smart money' "
                    "rule (puts written, calls covered, PCR rising) lost on all 5 indices (-Rs 24 to -245 a trade).",
                    "failed"),
                  S("H38", "research/HUNT_H38.md", "1,080 intraday variants (43 days) + 1,800 daily variants",
                    "Futures build-up: lowest q 0.54; nothing survives.", "failed"),
                  S("F1", "research/HUNT_F1_FACTORY.md", "feature screen, 5 indices", "Put long-unwinding share: "
                    "15-minute direction IC +0.029 ('barely').", "weak")],
        role=CTX, features=["buildup"]),
    dict(
        id="pcr", name="Put-call ratio (PCR), on OI and on volume", aka=["PCR", "PCR OI", "PCR volume"],
        what="Put OI divided by call OI (or put volume / call volume). Above 1: more puts than calls.",
        formula="R61: put / call OI at the 11 strikes nearest the money (A2 already uses this level); its change over "
                "30 minutes; put / call volume summed since 09:15; and the rank of today's OI ratio among the last 5 "
                "and 20 sessions at the same minute.",
        phone="yes: ChainAnalytics.pcr (OI and volume); the ranks need the phone's own stored history of past sessions",
        use="Read both ways: as sentiment (many puts = fear, a contrarian buy) or as writing (many puts written = a "
            "floor, bullish). Extremes versus the index's own history matter more than the raw level.",
        pitfalls=["The same number is read bullish by one school and bearish by another.",
                  "The ratio depends on which strikes are included and on the expiry; compare like with like.",
                  "Near expiry, OI shifts fast and the ratio swings without meaning."],
        research=[S("R47 C4", "research/HUNT_R47_RELAXED_OPTION_BUYING.md", "BANKNIFTY; holdout 91 trades; "
                    "Nov 2024+ final 55 trades", "'PCR in its top fifth of 60 sessions at 10:15 -> buy a call' (and "
                    "bottom fifth -> put): +Rs 865 a trade, p 0.0499 but q 0.50; Nov 2024+ final +Rs 1,125 a trade, q "
                    "0.76; design profit faded from +1,766 a trade (2022) to +9 (2025).", "near-miss, note only"),
                  S("R15 F3", "research/HUNT_R15_DAILY_FILTERS.md", "Liquidity arm days", "PCR extremes as a daily "
                    "filter: -Rs 19.4 a day, q 0.61.", "failed"),
                  S("F1", "research/HUNT_F1_FACTORY.md", "feature screen", "PCR level and change: noise for "
                    "direction (15-minute IC about +0.026).", "failed"),
                  S("R57 A4", "research/HUNT_R57_SOLO_2.md", "final 121 trades", "A2-style model with C4 as an "
                    "extra candidate passed (+Rs 59,706, p 0.002), but it is a non-default option-chain signal.",
                    "passed as a non-default variant only")],
        role=CTX + ". Using PCR AS THE SIGNAL (R47 C4) is non-default: an option-chain signal, Boss-only",
        features=["pcr_oi (A2)", "pcr_oi_chg30", "pcr_vol", "pcr_rank5", "pcr_rank20"]),
    dict(
        id="max_pain", name="Max pain", aka=["option pain", "max pain strike"],
        what="The expiry price at which option holders, taken together, would get the least money: the strike that "
             "minimises the total payout on all open calls and puts.",
        formula="For each chain strike X: sum over strikes K of call OI x max(X - K, 0) + put OI x max(K - X, 0); "
                "max pain = the X with the smallest total (ties: the lowest strike). R61: (max pain - index) / index x "
                "100, signed by the trade's side, over the 21 strikes around the money.",
        phone="yes: ChainAnalytics.maxPain",
        use="Folklore says the index is 'pulled' toward max pain into expiry because option writers defend it.",
        pitfalls=["It changes every minute as OI changes, and depends on the strike window used.",
                  "It assumes writers can move the index; our data says they do not."],
        research=[S("R16", "research/HUNT_R16_PREDICTABILITY.md", "NIFTY and BANKNIFTY expiries", "As a forecast of the "
                    "expiry close, max pain is worse than 'no change': error 0.74% vs 0.43% (BANKNIFTY), 0.58% vs "
                    "0.52% (NIFTY).", "failed"),
                  S("EXPIRY_PLAYBOOK", "research/EXPIRY_PLAYBOOK.md", "53 NIFTY, 11 BANKNIFTY expiries", "The close "
                    "ended 0.58% from max pain vs 0.50% from the open; within 0.25% of it on only 25% / 27% of "
                    "expiries.", "failed"),
                  S("H18", "research/HUNT_H18.md", "intraday, several indices", "No drift toward max pain or max OI "
                    "(BANKNIFTY -10 bps, t -2.2: if anything, away).", "failed")],
        role=CTX, features=["maxpain_dist_pct"]),
    dict(
        id="oi_walls", name="OI walls (support and resistance from OI)", aka=["call wall", "put wall",
                                                                            "max OI strike"],
        what="The strike with the most call OI above the index (read as resistance) and with the most put OI below it "
             "(read as support).",
        formula="R61: % distance from the index to the biggest call-OI strike at or above it and to the biggest put-OI "
                "strike at or below it (ties: the nearer one), within the 21 strikes around the money; expressed as "
                "'wall ahead' (on the trade's way) and 'wall behind'.",
        phone="yes: ChainAnalytics.oiProfile (max call / put OI strikes)",
        use="Option buyers avoid buying calls just under a big call wall; breakouts 'through a wall' are chased.",
        pitfalls=["Walls move as OI shifts; a wall is only as strong as the writers' willingness to hold.",
                  "Far walls just mean the market expects a wide range."],
        research=[S("OF1", "research/HUNT_OF1_ORDERFLOW_INDIA.md", "873 expiries", "Max-OI strikes were neither walls nor "
                    "magnets: 0 of 12 tests; the max-OI strike was closer to settlement than a random same-distance "
                    "strike on 42-43% of expiries (no-magnet level about 44%).", "failed"),
                  S("R20b", "research/HUNT_R20B_SR_RANGE.md", "98 design + 57 holdout trades", "OI walls as the "
                    "breakout range: +1,745 design, -3,972 holdout; random levels did better in 6 of 7 variants.",
                    "failed"),
                  S("R26", "research/HUNT_R26_OI_WALL_BLAST.md", "770 expiries, 15 trades", "OI-wall blast: -Rs 3,851; "
                    "best p 0.73, every q 1.00. 'The OI part adds nothing.'", "failed")],
        role=CTX, features=["wall_ahead_pct", "wall_behind_pct"]),
    dict(
        id="iv", name="Implied volatility (IV), ATM IV", aka=["IV", "ATM IV"],
        what="The volatility that makes the Black-Scholes price equal the option's market price: how much movement "
             "the market is paying for. Higher IV = dearer options.",
        formula="From the chain's IV column (the lake and the broker both give it per strike); ATM IV = mean of the "
                "at-the-money call and put IV. R61 adds its change since the day's first chain minute.",
        phone="yes: ChainAnalytics.ivSmile (ATM IV), broker IV per strike",
        use="Option buyers prefer cheap IV (less to lose if IV falls) and fear 'IV crush' after events; sellers like "
            "high IV.",
        pitfalls=["A buyer can be right on direction and still lose when IV falls.",
                  "IV is quoted per expiry; weekly IV near expiry is noisy.",
                  "Bad ticks in thin strikes give absurd IVs (R61 removes them before use)."],
        research=[S("VOLATILITY", "research/VOLATILITY.md", "BANKNIFTY, 249 days", "Day range rose with opening IV: "
                    "464 / 540 / 631 points by IV third (83 days each). Descriptive.", "size only"),
                  S("H29", "research/HUNT_H29.md", "16 variants + 12 filters", "Buying when IV is cheap versus a "
                    "realised-vol forecast: best -Rs 30 a day net, q 0.80; 0 of 12 filters adopted.", "failed"),
                  S("R57 A2", "research/HUNT_R57_SOLO_2.md", "128 final trades", "In Solo 2.0, ATM IV is the largest "
                    "negative weight (-0.21): dear options are skipped.", "used as context in A2")],
        role=SIZE, features=["iv_atm (A2)", "iv_chg_day"]),
    dict(
        id="iv_rank", name="IV rank and IV percentile", aka=["IVR", "IV percentile"],
        what="Where today's IV sits in its own past: IV rank = (IV - lowest) / (highest - lowest) over a look-back; IV "
             "percentile = share of past days with lower IV.",
        formula="R61: ATM IV rank (min-max) among the previous 60 sessions at the same minute (needs 40 sessions; Boss's "
                "Nov 2024 data rule makes 252-day ranks too short to train on).",
        phone="partly: needs 60 sessions of the phone's own stored ATM IV; missing until then",
        use="Buy options when IV rank is low, sell when it is high.",
        pitfalls=["One spike in the window squashes every later rank (min-max).",
                  "A short look-back mixes event weeks with quiet ones."],
        research=[S("F1 S18", "research/HUNT_F1_FACTORY.md", "158 holdout trades", "BANKNIFTY calls when IV percentile "
                    "is high: +Rs 12,319, p 0.02-0.03 but q 0.28, and it lost in design.", "failed"),
                  S("OBUY_GC", "research/OBUY_GC.md", "straddles", "Buying straddles at low IV percentile loses.",
                    "failed"),
                  S("F1", "research/HUNT_F1_FACTORY.md", "feature screen", "IV percentile / rank are good SIZE "
                    "predictors (IC about +0.23-0.24), not direction.", "size only")],
        role=SIZE, features=["iv_rank60"]),
    dict(
        id="skew", name="IV skew and smile", aka=["skew", "smile", "25-delta skew", "risk reversal"],
        what="How IV differs across strikes. Skew: out-of-the-money puts usually carry higher IV than calls (crash "
             "insurance). Smile: both wings dearer than the middle.",
        formula="A2: put IV 3 strikes below minus call IV 3 strikes above (skew). R61 adds smile curvature = (put IV "
                "-3 + call IV +3) / 2 - ATM IV. 25-delta skew is NOT used: deltas of far strikes on a 21-strike chain "
                "are noisy; the +-3-strike version is the stated substitute.",
        phone="yes: ChainAnalytics.ivSmile",
        use="Steep put skew = demand for protection (fear); flattening skew = calm. Some read rising call skew as "
            "upside speculation.",
        pitfalls=["Skew in points depends on the strike distance chosen and on IV level.",
                  "Thin far strikes give unreliable IVs."],
        research=[S("F1", "research/HUNT_F1_FACTORY.md", "feature screen, 5 indices", "The one direction clue that "
                    "pointed the same way everywhere: when puts are dearer, the index tends to rise over 30-60 "
                    "minutes (IC +0.03 to +0.13 fit, +0.01 to +0.08 selection). Too small to pay costs.", "weak lead"),
                  S("H29", "research/HUNT_H29.md", "24 variants", "Skew / synthetic-forward direction rules all lose "
                    "(best -Rs 76 a day net).", "failed"),
                  S("R30 M4", "research/HUNT_R30_ALL_PARAMETERS.md", "NIFTY, 77 trades", "A rule leaning on skew: "
                    "+Rs 277 a trade, q 0.109; near-miss, in a forward test.", "near-miss")],
        role=CTX, features=["iv_skew (A2)", "smile_curv"]),
    dict(
        id="term", name="IV term structure", aka=["term structure", "calendar IV"],
        what="IV of the next expiry versus a later one. Normally later expiries carry slightly higher IV (contango); "
             "near-term IV above far-term (backwardation) signals near-term stress or an event.",
        formula="R61: NIFTY only, monthly-file ATM IV minus weekly-file ATM IV (0 when both are the same contract). For "
                "BANKNIFTY, FINNIFTY and MIDCPNIFTY there are only monthly expiries since 20 Nov 2024, so there is no "
                "second expiry in the lake to compare (missing).",
        phone="yes: ChainAnalytics.volSurface across expiries",
        use="Backwardation warns of an event or panic; option buyers prefer the expiry whose IV is relatively cheap.",
        pitfalls=["Only exists while two expiries trade; the week of the monthly expiry collapses it.",
                  "Weekly IV jumps around expiry days by construction."],
        research=[S("H29", "research/HUNT_H29.md", "NIFTY, weeklies vs monthlies", "Monthly minus weekly ATM IV 'did "
                    "not sort the realised move at all'.", "failed"),
                  S("MCX_OPTIONS", "research/MCX_OPTIONS.md", "crude oil", "Crude term structure predicts nothing.",
                    "failed")],
        role=CTX, features=["term_slope"]),
    dict(
        id="vix", name="India VIX", aka=["VIX", "fear index"],
        what="NSE's index of NIFTY's expected volatility over the next 30 days, computed from NIFTY option prices "
             "(annualised %).",
        formula="Streamed as an index (INDIAVIX). A2 uses the level and the change from the previous close; R61 adds "
                "its rank among the previous 60 sessions at the same minute.",
        phone="yes: VIX stream / 1-minute bars (MarketRecorder)",
        use="High VIX = bigger expected moves and dearer options; spikes mark fear, falling VIX calm.",
        pitfalls=["VIX is about NIFTY; other indices have their own IV.",
                  "It says how big, not which way."],
        research=[S("R15 F2", "research/HUNT_R15_DAILY_FILTERS.md", "228 trades", "Skipping low-VIX days cost -Rs 73.4 a "
                    "day (q 0.77): low-VIX days were good for the Liquidity arm.", "failed"),
                  S("SIDEWAYS_GUARD", "research/SIDEWAYS_GUARD.md", "2 x 249 days", "VIX pointed the wrong way for ORB "
                    "in one year (-8.9k) and the right way in the other.", "failed"),
                  S("R16 / F1", "research/HUNT_R16_PREDICTABILITY.md", "range forecasts", "VIX alone matches a model "
                    "for the next day's and rest-of-day range (size IC +0.22).", "size only"),
                  S("R57 A2", "research/HUNT_R57_SOLO_2.md", "128 final trades", "VIX is A2's largest positive weight "
                    "(+0.22).", "used as context in A2")],
        role=SIZE, features=["vix (A2)", "vix_chg_pct (A2)", "vix_rank60"]),
    dict(
        id="delta", name="Delta", aka=["option delta", "hedge ratio"],
        what="How much the option's price moves for a 1-point move in the index (0 to 1 for calls, 0 to -1 for puts); "
             "also roughly the chance of expiring in the money.",
        formula="Black-Scholes: call N(d1), put N(d1) - 1, d1 = (ln(S/K) + (r + s^2/2) T) / (s sqrt T), from the "
                "contract's own IV; R61 uses r = 6.5% and T = (sessions to expiry + rest of today / 375) / 252.",
        phone="yes: ChainAnalytics / OptionMath Greeks",
        use="Pick strikes (ITM = higher delta, behaves like the index), size positions, convert an index view into "
            "option points.",
        pitfalls=["Delta changes with the index (gamma), time and IV.",
                  "Model delta with a wrong IV or T misleads near expiry."],
        research=[S("F1", "research/HUNT_F1_FACTORY.md", "feature screen", "Delta as a direction feature: IC about "
                    "+0.012 (noise).", "context only; never tested as a rule")],
        role="context: describes the contract Solo would buy (the strike rule is fixed by the setup)",
        features=["delta_abs"]),
    dict(
        id="gamma", name="Gamma", aka=["option gamma"],
        what="How fast delta changes as the index moves. High gamma (at the money, near expiry) = the option's "
             "exposure grows quickly in a move, both ways.",
        formula="Black-Scholes gamma = pdf(d1) / (S s sqrt T); R61 reports it as the change in delta for a 1% index "
                "move (gamma x S x 0.01).",
        phone="yes: OptionMath Greeks",
        use="Buyers like gamma for fast moves (expiry-day 'hero' trades); sellers fear it.",
        pitfalls=["Gamma explodes in the last hours of expiry; small index moves swing the premium wildly."],
        research=[S("F1", "research/HUNT_F1_FACTORY.md", "feature screen", "Gamma near the money predicts move SIZE (IC "
                    "about +0.15), not direction.", "size only"),
                  S("R21-R37 hero family", "research/HUNT_R37_GAMMA_FLIP.md", "52 expiry-day hero rules (one BH family)",
                    "0 of 52 pass.", "failed")],
        role="context: describes the contract Solo would buy", features=["gamma_1pct"]),
    dict(
        id="theta", name="Theta (time decay)", aka=["time decay"],
        what="How much value the option loses per day if nothing else changes. Always a cost to the buyer; biggest at "
             "the money and near expiry.",
        formula="Black-Scholes theta per session (1/252 year); R61 reports it as % of the premium.",
        phone="yes: OptionMath Greeks",
        use="Buyers want the move to come before decay eats the premium; time stops exist because of theta.",
        pitfalls=["Theta is not linear: the last day's decay is far bigger than the average day's."],
        research=[S("EXPIRY_PLAYBOOK", "research/EXPIRY_PLAYBOOK.md", "53 NIFTY, 11 BANKNIFTY expiries", "On expiry "
                    "day the ATM option keeps a median 11% (NIFTY) / 19% (BANKNIFTY) of its 09:20 time value at "
                    "14:00, and 2% / 4% at 15:00. Descriptive.", "context"),
                  S("F1", "research/HUNT_F1_FACTORY.md", "feature screen", "Theta: size IC about +0.10.",
                    "size only")],
        role="context: describes the contract Solo would buy", features=["theta_pct_day"]),
    dict(
        id="vega", name="Vega", aka=["IV sensitivity"],
        what="How much the option's price moves for a 1-point change in IV.",
        formula="Black-Scholes vega = S pdf(d1) sqrt T / 100 per IV point; R61 reports it as % of the premium.",
        phone="yes: OptionMath Greeks",
        use="Buyers of long-dated or ATM options carry more vega: an IV drop (after an event) hurts them.",
        pitfalls=["Weekly options have little vega; IV crush matters more for monthlies."],
        research=[S("F1", "research/HUNT_F1_FACTORY.md", "feature screen", "Vega: direction IC about +0.01.",
                    "context only")],
        role="context: describes the contract Solo would buy", features=["vega_pct"]),
    dict(
        id="gex", name="Gamma exposure (GEX) and the gamma-flip level", aka=["GEX", "gamma flip", "zero gamma"],
        what="An estimate of how much option dealers must buy or sell the index to stay hedged as it moves. If "
             "dealers are long gamma (positive GEX) they sell rallies and buy dips (calmer market); short gamma "
             "(negative GEX) they chase moves (wilder market). The flip is the index level where net GEX changes "
             "sign.",
        formula="Net GEX = sum over strikes of (call gamma x call OI - put gamma x put OI) x S^2 x 0.01, assuming dealers "
                "are long calls and short puts (the convention R28 used). R61: net / gross (between -1 and +1), its "
                "sign, and the % distance to the level (within +-3%) where net GEX, re-priced, changes sign.",
        phone="yes: ChainAnalytics.gex / gammaDensity (note: the phone prices from the forward; a port must match "
              "R61's definition exactly)",
        use="Positive GEX: fade moves, expect ranges. Negative GEX / below the flip: expect trends and bigger moves.",
        pitfalls=["In India we do not know who holds which side; the sign convention is an assumption (OF1: clients "
                  "and Pros are net option writers).",
                  "GEX is strongly tied to IV (R36: correlation -0.69), so it mostly repeats what IV says."],
        research=[S("R28", "research/HUNT_R28_GEX.md", "lake Aug 2021-Oct 2026, 5 indices", "Negative GEX at 10:00 -> "
                    "somewhat bigger rest-of-day range (BANKNIFTY 0.87% vs 0.75%); 0 of 10 arm filters passed; on the "
                    "Night arm it cut the holdout from +249 to -18 a day.", "size only; failed as a filter"),
                  S("R36", "research/HUNT_R36_GAMMA_EXPOSURE.md", "341 expiries; 264 traded", "Correlation 0.33 with "
                    "the afternoon move, but it is an IV effect; trades 0 of 4 pass, -Rs 1,03,781.", "failed"),
                  S("R37", "research/HUNT_R37_GAMMA_FLIP.md", "446 expiries, 42 trades", "Trading the flip: -Rs 40,003, "
                    "worse than 99.4% of random entries; 0 of 4 pass.", "failed"),
                  S("R9", "research/HUNT_R9_VOLUME_GAMMA.md", "28 filters", "GEX sign flipped between periods; 0 of 28 "
                    "pass.", "failed")],
        role=SIZE, features=["gex_norm", "gex_sign", "flip_dist_pct"]),
    dict(
        id="dealers", name="Dealer positioning", aka=["who is short gamma", "writer positioning"],
        what="Which group holds which side of the options book; GEX depends on it.",
        formula="Not observable directly. NSE's participant-wise OI (client, DII, FII, Pro; daily, after 18:30) is the "
                "closest public proxy.",
        phone="partly: MarketRecorder records the participant file daily; intraday positioning is not available",
        use="Traders assume writers (dealers) hedge and so pin or push the index.",
        pitfalls=["Indian clients and Pros, not 'dealers', are the net writers (OF1: 97% / 92% of days).",
                  "Daily file only, a day late for intraday decisions."],
        research=[S("OF1", "research/HUNT_OF1_ORDERFLOW_INDIA.md", "3,632 days (2012-2026)", "Follow-the-group signals: "
                    "15 of 36 passed on 2012-2025, then 0 of 15 held in the last 12 months (13 flipped sign).",
                    "failed"),
                  S("MARKET_DRIVERS", "research/MARKET_DRIVERS.md", "review", "'GEX sign / flip decides trend': "
                    "inconsistent in our data.", "failed")],
        role=CTX, features=[]),
    dict(
        id="vwap", name="VWAP", aka=["volume-weighted average price"],
        what="The average price of the day weighted by volume: where most of the day's trading happened.",
        formula="sum(price x volume) / sum(volume) from the open. The index itself has no volume, so A2 uses the "
                "typical-price average of the session ('dist_tpavg_pct') as the index proxy; true VWAP needs futures "
                "volume (futures minutes from Jul 2026 in the lake).",
        phone="partly: futures VWAP from the futures stream; the index has no volume",
        use="Above VWAP = buyers in control; pullbacks to VWAP are bought in up-trends.",
        pitfalls=["On an index without volume, 'VWAP' is a different number in every app."],
        research=[S("TREND_FILTER", "research/TREND_FILTER.md", "BANKNIFTY 249 days", "9 EMA + VWAP + HH/HL: every "
                    "version loses or is flat.", "failed"),
                  S("H46", "research/HUNT_H46.md", "2,400 variants", "'VWAP reject': best q 0.12; primary -Rs 1,505 a "
                    "day in the holdout.", "failed"),
                  S("F1 C1/C2", "research/HUNT_F1_FACTORY.md", "3,891 + 2,256 trades", "EMA + Supertrend + VWAP lost "
                    "Rs 2.5-3.3 lakh in design and holdout.", "failed")],
        role=CTX, features=["dist_tpavg_pct (A2)"]),
    dict(
        id="atr", name="ATR (average true range)", aka=["ATR14"],
        what="The average daily (or bar) range including gaps: the index's normal move size.",
        formula="True range = max(high - low, |high - previous close|, |low - previous close|); ATR14 = mean of the "
                "last 14 completed days. A2 uses daily ATR14 as % of price; SoloMidday's stops are in ATR.",
        phone="yes: from index candles",
        use="Scale stops and targets to the market's current movement.",
        pitfalls=["Lags: after a quiet spell a sudden move is far bigger than ATR."],
        research=[S("F1", "research/HUNT_F1_FACTORY.md", "feature screen", "ATR is the strongest SIZE predictor (IC "
                    "+0.335); direction is noise.", "size only"),
                  S("R20 / R17", "research/HUNT_R20_RANGE.md", "ORB and stop studies", "ATR-adaptive ranges and "
                    "range-scaled stops were no better.", "failed")],
        role=SIZE, features=["atr14d_pct (A2)"]),
    dict(
        id="orb", name="Opening range", aka=["ORB", "opening range breakout"],
        what="The high and low of the first minutes of the session (15 or 30); a break above or below is read as the "
             "day's direction.",
        formula="High / low of 09:15-09:29 (R61's S4) or 09:15-09:44 (A2's 'or30_state').",
        phone="yes: from index minutes",
        use="Buy the breakout side, stop at the other side.",
        pitfalls=["Most breaks fail or reverse in a range day; costs eat the small wins."],
        research=[S("R3", "research/HUNT_R3_ORB_FAMILY.md", "2,489 trades, Aug 2021-Oct 2026", "ORB: -Rs 2,23,040; 0 of "
                    "6 years profitable; 0 of 16 versions pass.", "failed"),
                  S("R49", "research/HUNT_R49_ORB_RECENT.md", "Nov 2024+", "Still loses.", "failed"),
                  S("R57", "research/HUNT_R57_SOLO_2.md", "final 459 trades", "S4 (15-min ORB) unfiltered: "
                    "-Rs 1,14,070.", "failed")],
        role="a candidate setup (S4, signal on the index) and a context feature", features=["or30_state (A2)"]),
    dict(
        id="value_area", name="Value area / market profile", aka=["TPO", "POC", "VAH", "VAL", "volume profile"],
        what="Market profile counts how long price spent at each level (time-price opportunities). POC = the most "
             "traded level; the value area holds 70% of the day's activity (VAH top, VAL bottom).",
        formula="Previous day's 30-minute TPO profile, 70% value area (h37 sigs.profile_levels). R61: (index - "
                "previous POC) / (VAH - VAL), signed by the trade's side.",
        phone="yes: from index minutes",
        use="Opening outside yesterday's value and staying there = trend day ('open outside value and go'); inside "
            "value = range.",
        pitfalls=["Many ways to build a profile (bin size, TPO period); results depend on them."],
        research=[S("R52 H37-10 / R57 S3", "research/HUNT_R57_SOLO_2.md", "final 232 trades", "'Open outside value and "
                    "go' (S3) made +Rs 49,360 unfiltered in the final window; A2 took 92 of its 128 trades from it. "
                    "R52 chose its exit on this same window, so it is not fresh.", "candidate setup (not fresh)"),
                  S("R9", "research/HUNT_R9_VOLUME_GAMMA.md", "56 + 32 tests", "HVNs and the POC turn price no more "
                    "often than placebo levels; auction rules 0 of 56.", "failed"),
                  S("H37", "research/HUNT_H37.md", "18,170 variants", "TPO-based rules among them: SPA p 1.00.",
                    "failed")],
        role="a candidate setup (S3, signal on the index) and a context feature", features=["va_pos"]),
    dict(
        id="regime", name="Trend vs range regimes", aka=["efficiency ratio", "ADX", "choppiness"],
        what="Whether the market is trending (moves far for its distance travelled) or ranging (back and forth).",
        formula="Efficiency ratio = |net move| / sum of |bar moves| (A2's 'er_day'); ADX14; choppiness index.",
        phone="yes: from index candles",
        use="Use trend rules in trends and fade rules in ranges.",
        pitfalls=["You know the regime well only after it is over."],
        research=[S("SIDEWAYS_GUARD", "research/SIDEWAYS_GUARD.md", "7 detectors x 5 arms = 35 tests", "None beat "
                    "random skipping in both years; the ADX pause cut Liquidity from +1,03,019 to +52,096.", "failed"),
                  S("R16", "research/HUNT_R16_PREDICTABILITY.md", "first 30/45 minutes", "Trend vs range day not "
                    "predictable (AUC 0.46-0.52).", "failed")],
        role=CTX, features=["er_day (A2)", "rv_30m (A2)", "day_range_vs_adr10 (A2)"]),
    dict(
        id="participants", name="FII / DII flows and participant-wise OI", aka=["FII OI", "client OI", "Pro OI"],
        what="NSE publishes each evening how many index futures and options each group (client, DII, FII, Pro) holds "
             "long and short; FII/DII cash flows are published by exchanges/NSDL.",
        formula="R61: FII index-futures long share = long / (long + short) from the PREVIOUS session's participant "
                "file (published after 18:30), and its change from the session before.",
        phone="yes: MarketRecorder records the participant file daily (after 18:30); DII cash flows not reachable",
        use="Follow FII futures positioning as 'smart money'; fade crowded client positioning.",
        pitfalls=["A day late; FII futures positions are often hedges for cash holdings.",
                  "Signals that worked for years flipped in the last 12 months (OF1)."],
        research=[S("OF1", "research/HUNT_OF1_ORDERFLOW_INDIA.md", "3,632 days", "Follow signals pass in design and fail "
                    "in the holdout; option-buying rules R0-R3 all lose in the holdout.", "failed"),
                  S("H40", "research/HUNT_H40.md", "9,600 variants, 2020-2026", "0 pass (SPA 0.96); FII flows carry a "
                    "small real signal that is smaller than option costs; 0 of 160 Liquidity filters.", "failed")],
        role=CTX, features=["fii_fut_long_share", "fii_fut_long_chg"]),
    dict(
        id="basis", name="Futures basis / premium", aka=["basis", "futures premium", "cost of carry"],
        what="Futures price minus the index: normally positive (interest minus dividends to expiry). An unusual "
             "premium or discount is read as positioning.",
        formula="R61: synthetic forward from put-call parity at the ATM strike: (K + call - put - index) / index x "
                "10,000 bps. Real futures minutes are in the lake only from Jul 2026.",
        phone="yes: ChainAnalytics.syntheticFuture; real basis from the futures quote",
        use="Rising premium = long positioning; discount = hedging or short positioning.",
        pitfalls=["Mostly interest and dividends; the 'signal' part is tiny.",
                  "Synthetic forward uses last-traded prices that can be minutes apart."],
        research=[S("H38", "research/HUNT_H38.md", "daily + intraday + 72 real-basis trades", "Basis change, "
                    "synthetic premium and real basis all fail; a Liquidity skip filter passed design (q 0.008) and "
                    "went -Rs 35 a day in the holdout.", "failed")],
        role=CTX, features=["synth_basis_bps"]),
    dict(
        id="rollover", name="Rollover", aka=["rollover %", "roll cost"],
        what="Near expiry, the share of futures positions moved to the next month, and the price paid to roll.",
        formula="Next-month OI / (near + next OI) in the last sessions before expiry. Not built in the brain.",
        phone="not available on the phone (needs both futures months' OI history); context only",
        use="High rollover with a rising premium = longs carried forward (bullish).",
        pitfalls=["Monthly only; a handful of observations a year."],
        research=[S("H40 S10", "research/HUNT_H40.md", "last 5 sessions of each series", "Rollover % and roll cost carry "
                    "no information.", "failed / barely tested")],
        role="not a model input (not on the phone)", features=[]),
    dict(
        id="events", name="Event days (RBI, Budget, CPI, Fed, expiry)", aka=["event risk", "macro calendar"],
        what="Scheduled news that moves the market: RBI policy (10:00 IST), the Union Budget (11:00), US FOMC decision "
             "and US CPI (after the Indian close, so they hit the next session), India CPI (16:00), and expiry days.",
        formula="R61: event_rbi_budget = 1 on an RBI policy or Budget day; post_us_event = 1 on the first NSE session "
                "after an FOMC decision or US CPI; other_expiry_today = 1 when another of the four indices expires "
                "today (Solo never trades an index on its own expiry day). Calendars pre-listed in r61events.py.",
        phone="partly: EventCalendar.kt has RBI and Budget; a static FOMC / US CPI list would need bundling; expiry "
              "calendar yes",
        use="Avoid or size down before events (IV crush, whipsaw); some trade the post-event trend.",
        pitfalls=["Dates shift (the US 2025 shutdown moved CPI); the app's RBI Aug 2026 date (6 Aug) differs from the "
                  "research list (7 Aug).",
                  "Few event days: every result rests on a handful of sessions."],
        research=[S("EVENT_SHIELD", "research/EVENT_SHIELD.md", "BANKNIFTY 497, NIFTY 498 sessions", "BANKNIFTY moves "
                    "more on RBI + Budget days (range 1.44% vs 0.98%, 15 vs 359 sessions; q 0.08, holdout confirmed "
                    "p 0.03 on 7 sessions); US CPI / FOMC: bigger gap, same day range.", "passed as a size warning"),
                  S("H28", "research/HUNT_H28.md", "about 10,300 tests", "0 survivors as trade rules; Budget days 2.1-"
                    "2.4x range; the session after FOMC gaps more.", "size only"),
                  S("EXPIRY_PLAYBOOK", "research/EXPIRY_PLAYBOOK.md", "53 vs 446 NIFTY sessions", "The last hour of "
                    "expiry moves more (0.15% vs 0.10%, p 0.004); expiry rules R21-R37 0 of 52 pass.",
                    "size only")],
        role=SIZE, features=["event_rbi_budget", "post_us_event", "other_expiry_today", "dte (A2)"]),
]

KEYS = ("id", "name", "aka", "what", "formula", "phone", "use", "pitfalls", "research", "role", "features")


def with_results(results: dict | None = None) -> list:
    out = []
    for c in CONCEPTS:
        c = dict(c)
        c["r61_result"] = (results or {}).get(c["id"], NOT_RUN)
        out.append(c)
    return out


def to_json(results: dict | None = None) -> str:
    return json.dumps(dict(version=VERSION, source="research/hunt/r61/r61concepts.py",
                           house_rule="Signals are read from the underlying index; option-chain data is context or a "
                                      "filter only. Solo is paper only, forever.",
                           concepts=with_results(results)), indent=1, ensure_ascii=False) + "\n"


def to_md(results: dict | None = None) -> str:
    L = ["# What Solo knows: the concepts behind option trading, and what our research found",
         "",
         f"R61 Part 1 ({VERSION}). Generated from `research/hunt/r61/r61concepts.py` (also written as `concepts.json` for "
         "the app); edit the Python, then run it. Plain English, one section per concept.",
         "",
         "**House rule:** every entry and exit signal is read from the underlying (the index), and the option is then "
         "chosen by the strike rule. Option-chain data (OI, PCR, IV, skew, GEX, max pain) may only be context or a "
         "filter around that signal. A rule whose signal comes from the option chain is non-default, labelled, and "
         "switched on only by Boss.",
         "",
         "**The short version of what our studies found:** across every option-chain, volatility and positioning "
         "concept below, nothing passed as a trade rule or as a filter on the arms. Volatility, IV, VIX, ATR and GEX "
         "predict how BIG the next move is, not which WAY. Event days move more. That is why Solo uses them only as "
         "context: to skip dear or risky trades, never to pick a side.",
         ""]
    for c in with_results(results):
        L += [f"## {c['name']}", ""]
        if c["aka"]:
            L += [f"Also called: {', '.join(c['aka'])}.", ""]
        L += [f"**What it is.** {c['what']}", "",
              f"**How it is computed.** {c['formula']}", "",
              f"**On the phone.** {c['phone']}.", "",
              f"**How traders use it.** {c['use']}", "",
              "**Pitfalls.**", ""]
        L += [f"- {p}" for p in c["pitfalls"]]
        L += ["", "**What our research found.**", "", "| study | sample | finding | verdict |", "|---|---|---|---|"]
        for r in c["research"]:
            L.append(f"| {r['study']} (`{r['doc']}`) | {r['sample']} | {r['finding']} | {r['verdict']} |")
        L += ["", f"**Role in Solo.** {c['role']}.", ""]
        if c["features"]:
            L += [f"**Solo's inputs from it.** {', '.join('`' + f + '`' for f in c['features'])}.", ""]
        L += [f"**R61 result.** {c['r61_result']}", ""]
    return "\n".join(L)


def write(results: dict | None = None):
    (HERE / "concepts.json").write_text(to_json(results))
    (HERE / "CONCEPTS.md").write_text(to_md(results))


if __name__ == "__main__":
    import sys
    res = None
    if len(sys.argv) > 1:                       # optional: a JSON file {concept id: plain-English R61 result}
        res = json.loads(Path(sys.argv[1]).read_text())
    write(res)
