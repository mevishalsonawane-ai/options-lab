# R98f pre-registration: order flow at the hero move

Written 21 Oct 2026, before any number below was computed. Boss: "Also check orderflow at that movement".

## What "order flow" can mean with this data

The lake has no ticks, no bid/ask and no aggressor side. It has, per minute:
- every stored option strike's volume, OI, IV and OHLC (all years);
- index-futures volume and OI for the live contracts only, 29 Jul – 19 Oct 2026 (about 40 expiry days, incl. 13 Oct).

So "order flow" here means minute volume, the minute tick rule (volume on up-close minutes against down-close minutes)
and OI change. These are the proxies a chain-watching trader sees.

## Part 1: option flow around every hero start (all years, descriptive)

- **Heroes:** the R98 census option-days that reached ≥ 10× (closes, by 15:20). The anchor t0 is the start (the
  cheapest point). It is chosen with hindsight, so the PRE window shows what was visible before the run, not a tradable
  signal.
- **Controls:** for each hero, up to 5 cheap OTM options of the same index and side at the same minute t0, on OTHER
  expiry days, priced 0.5-2× the hero's p0, whose best close afterwards (to 15:20) stayed < 2×. Drawn at random,
  seed 98.
- **Windows:**
  - PRE = t0−30 .. t0−1;
  - EARLY = t0 .. t0+9 (the first 10 minutes of the run);
  - BASE = 09:15 .. t0−31.
- **Metrics:**
  1. **vol_x:** the option's mean volume per minute in the window / its BASE mean.
  2. **oi_chg:** the option's OI change over the window, relative.
  3. **upvol:** the share of the window's volume on minutes whose close is above the previous close (tick rule).
  4. **side_vol_x / opp_vol_x:** the same-side and opposite-side chain volume (all stored strikes), window / BASE.
  5. **iv_chg:** the option's IV change over the window.
- **Tests:** hero against control medians, Mann-Whitney, by window. The PRE-window metrics also get an AUC (how well
  each separates heroes from duds before the move).

## Part 2: futures flow (29 Jul – 19 Oct 2026, descriptive)

- **The futures:** near-month index futures volume and OI per minute.
- **Each expiry day** of the five indices in that span is split into hero days (some cheap option ≥ 10×) and the rest.
- **Compared:**
  - futures volume in the 30 minutes before the day's biggest hero start against the same minutes on non-hero days;
  - the OI change during the run, read as "fresh positions" (OI up with the move) or "covering" (OI down).
- **13 Oct 2026, minute by minute 12:30-15:25:** NIFTY futures price, volume and OI, and the 22650 CE's volume and OI.

## Use

- **Descriptive.** No trading rule is chosen here.
- **If a PRE-window metric separates heroes from controls clearly** (AUC ≥ 0.65 and Mann-Whitney p < 0.001), it is
  written up as a candidate for its own pre-registered test.
