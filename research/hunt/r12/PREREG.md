# R12 pre-registration: the trade manager's rules, frozen BEFORE the holdout is read

Written 10 Oct 2026 by R12, after the design loop (`loop.py`) finished and before `holdout.py` was run. Nothing below was
chosen with holdout data (1 Oct 2025 - 6 Oct 2026). The design period is everything before 1 Oct 2025.

## Frame
- Option BUYING only, Rs 1 lakh, fixed 1 lot (Hero: its own Rs 5,000 ticket, as the arm trades), no partial exits by the
  manager, no adding, stops never widened, the lock never falls.
- Costs: obuy app fills (+5 bps buy, -5 bps market sell, -10 bps resting stop/lock), app charges (STT 0.15%), plus the
  h24 real half-spread on entry and exit (0.16% NIFTY and BANKNIFTY). Hero keeps its source's 1-tick LIMIT fill model.
- Exits on the option's 1-minute high/low (wicks); stop first when a bar touches a stop and a target. Manager exits are
  decided on a minute's close and filled at the next printed minute's open.
- Trades managed = each strategy's ORIGINAL trades, rebuilt with its source code (`build.py`; parity in `parity.py`:
  ORB family, VIX divergence exact to the paisa, Liquidity 99.7% of trades exact, Hero within rounding). The manager can
  only change how a trade ends; it never creates trades (earlier exits do not add re-entries).

## The specialists (`specs.py`) and the loop (`loop.py`)
14 specialists over the task's 9 data families + a labelled delta PROXY (r11 tick-rule option flow); exact rules in
`specs.py`. Two loops were run on the design period; every configuration of both enters White's reality check:
- Loop A (selection = largest training improvement over the original exits): 8 rounds, 1,829 configurations. Rejected:
  on the losing ORB arms its picks did no better than the SAME NUMBER OF RANDOM early exits (twin p 0.32-0.89), and on
  the profitable arms its walk-forward was negative (Liquidity -70,504).
- Loop B (selection = training improvement MINUS what as many random early exits would have gained, positive in both
  halves of training; single specialists also need a neighbouring grid value positive; refinements stay inside the
  declared grid): rounds 1-4 fixed in advance (alone, committees, locks, extensions); the stop rule (two consecutive
  rounds improving the design walk-forward net by under 2%: round 2 +1.04%, round 3 +0.35%, round 4 -0.12%) ended it
  after round 4. 1,349 configurations. 2,363 distinct configurations in all.

## The frozen rules (one per strategy; the full-design pick of loop B)
Notation: EXIT[...]q1 = exit when ANY member votes (OR), LOCK = a resting stop raised to the specialist's level, EXT =
extension votes needed (with the app's ratcheting lock). Member rules:
- PA(T, m): T minutes after the entry the option's best high is still under entry x (1 + m) -> exit.
- PB(a): a minute closes at or under entry x (1 - a) -> exit.
- OI(z0): ATM+-2 put-minus-call OI change over 15 minutes, against the trade, at z <= -z0 (z on the design std) -> exit.
- VW(z, p): the index closes on the wrong side of the session VWAP (option-volume proxy) by more than z SD for p minutes.
- MP(prior, p): the index was outside the PRIOR day's value area (15th-85th pct of its minute closes) the trade's way
  and is back inside for p minutes -> exit.
- DP(z0): signed option flow (tick rule, ATM+-5) over 10 minutes against the trade at z <= -z0 -> exit (PROXY).
- VC(k, a): lock = best high - k x the option's 10-minute mean range, once the profit is a ranges.
- BE(b): lock = breakeven + charges once the best high is entry x (1 + b).
- EXT[xVW&xDP]q2: extend (target + min(50% of the original distance, 3 x option ATR); lock = max(BE + charges, entry +
  50% of the open profit, old target - 25% of the gap); then trail 50% of new highs) when the index is >= 0.5 VWAP SD
  the trade's way AND the 10-minute signed option flow is >= +0.5 SD the trade's way.

```json
{
 "liq_bn":     {"exit": [["OI", {"z0": 1.0}], ["PA", {"T": 45, "m": 0.10}]], "quorum": 1, "lock": [], "extend": null},
 "orb":        {"exit": [["PA", {"T": 20, "m": 0.03}], ["PB", {"a": 0.12}]], "quorum": 1, "lock": [["VC", {"k": 6.0, "a": 1.0}]], "extend": null},
 "orb_fresh":  {"exit": [["PA", {"T": 20, "m": 0.03}], ["PB", {"a": 0.12}]], "quorum": 1, "lock": [["VC", {"k": 3.0, "a": 1.0}]], "extend": null},
 "orb_sweep":  {"exit": [["OI", {"z0": 1.5}], ["PA", {"T": 45, "m": 0.03}]], "quorum": 1, "lock": [], "extend": [["xVW", "xDP"], 2]},
 "range_fade": {"exit": [["PA", {"T": 10, "m": 0.10}], ["VW", {"z": 0.5, "p": 5}]], "quorum": 1, "lock": [], "extend": null},
 "vixdiv_ni":  {"exit": [["MP", {"which": "prior", "p": 5}], ["PB", {"a": 0.12}]], "quorum": 1, "lock": [], "extend": null},
 "vixdiv_ba":  {"exit": [["DP", {"z0": 2.0}], ["MP", {"which": "prior", "p": 5}]], "quorum": 1, "lock": [], "extend": null},
 "hero":       {"exit": [["MP", {"which": "prior", "p": 1}], ["PA", {"T": 10, "m": 0.10}]], "quorum": 1, "lock": [["BE", {"b": 0.15}]], "extend": null}
}
```

Design evidence for each (Rs, design period; WF = walk-forward folds 2023 / 2024 / 2025 Jan-Sep, each picked on earlier
years only; twin p = the frozen rule against 1,000 sets of the same number of random early exits; BH over the 8 frozen
rules; White RC over every configuration tried for that strategy, both loops):

| strategy | design trades | WF delta (folds) | full-design delta | twin p (BH q) | White RC p | design status |
|---|---|---|---|---|---|---|
| liq_bn | 750 | +797 (-1,710 / +2,507 / 0) | +11,430 | 0.023 (0.029) | 0.87 | SHADOW (1 of 3 folds positive, RC fails) |
| orb | 7,820 | +1,181 (-1,672 / +2,024 / +830) | +44,883 | 0.001 (0.003) | 0.057 | ACT candidate |
| orb_fresh | 1,981 | +9,169 (+3,490 / +2,270 / +3,409) | +38,718 | 0.001 (0.003) | 0.002 | ACT candidate |
| orb_sweep | 1,330 | +33,227 (+19,758 / +5,187 / +8,282) | +55,781 | 0.002 (0.004) | 0.0005 | ACT candidate |
| range_fade | 1,460 | +57,240 (+32,321 / +8,366 / +16,552) | +113,328 | 0.001 (0.003) | 0.0005 | ACT candidate |
| vixdiv_ni | 148 | -7,419 | +12,889 | 0.025 (0.029) | 0.65 | SHADOW (WF negative) |
| vixdiv_ba | 178 | -21,286 | +16,942 | 0.018 (0.029) | 0.67 | SHADOW (WF negative) |
| hero | 51 | -7,620 | +25,759 | 0.067 (0.067) | 0.84 | SHADOW (WF negative) |

Global White RC over all 2,363 configurations x 8 strategies: p = 0.15.

## Decision rules (fixed now)
- ACT candidate (design): WF delta > 0 with at least 2 of 3 folds positive, twin BH q < 0.10, strategy White RC p < 0.10.
- Holdout confirmation: an ACT candidate becomes **ACT** only if, in the holdout, the manager's net beats the original
  net (delta > 0) AND beats its random twins (holdout twin p < 0.10). Otherwise **SHADOW** (record only, the original
  exits stay in charge).
- Strategies that are not ACT candidates stay **SHADOW** whatever the holdout shows.
- Strategies the manager cannot sensibly manage, or that could not be rebuilt: **OFF** (Night R3: 10 in-session minutes;
  MCX legs: under 2 months of design-period data).
- Live-only specialists (order flow, delta divergence, absorption, traps, news, findings consensus, data near stop):
  **SHADOW** until the app's own recordings can test them.

## Holdout report (pre-registered content)
Per strategy, original vs manager: net, trades, win rate, average win and loss, max drawdown (daily equity), delta,
holdout twin p, BH across the 8 strategies; which members fired and the net effect of the trades they changed;
descriptive: every round-1 single specialist on the holdout and the current app manager's extension without its flow
condition (M0). Read once (`HOLD_READ`); a second run is refused.
