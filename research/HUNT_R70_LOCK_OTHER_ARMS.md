# R70: Boss's selected lock (+5% once +25%) on the other arms

16 Oct 2026. Research only (no `android/**`). Pre-registered in `research/hunt/r70/PREREG.md` before any result. Code:
`research/hunt/r70/`.

Boss, 16 Oct: *"what if we add this same in all the other arms and strategies too"*, then *"Select the best"*. The best
lock of the day was R66 L25: once the option has traded +25% above the fill, the stop rises to +5%. Donchian, OOV and
Solo 2.0 were tested in R66. R70 tests the same lock on every other arm that the research engine can re-price.

## Answer

**On the other arms it makes no real difference, and it is not worth adding there.** It changes very few trades
(Liquidity: 18 of 881 in design, 4 of 357 in the final; Solo midday: 2 of 117 and 1 of 36). It is a little worse in
design and a little better in the final on both arms. That is noise.

| arm (trades design / final) | rule | design 20 Nov 24 - 31 Mar 26 | walk-forward Apr 25 - Mar 26 | final 1 Apr - 13 Oct 26 | final worst day | final max DD |
|---|---|---|---|---|---|---|
| **Liquidity 15+5** (881 / 357) | own exits (today) | **Rs 2,57,249** | **Rs 2,38,064** | Rs 11,155 | -Rs 11,405 | Rs 61,062 |
| | + stop +5% once +25% | Rs 2,54,018 | Rs 2,32,137 | **Rs 13,091** | -Rs 11,405 | Rs 61,062 |
| **Solo midday** (117 / 36) | own exits (today) | **Rs 39,606** | **Rs 42,173** | -Rs 21,589 | -Rs 6,235 | Rs 28,815 |
| | + stop +5% once +25% | Rs 38,620 | Rs 41,187 | **-Rs 21,275** | -Rs 6,235 | Rs 28,815 |

Paired, L25 minus the own exits: Liquidity design -Rs 3,232 (bootstrap p 0.58), final +Rs 1,936 (p 0.31); Solo midday
design -Rs 986, final +Rs 313. Random-exit twins: p 0.26 / 0.48 (BH q 0.48), so the lock's timing is not better than
luck. Decision (pre-registered): not recommended on either arm, and not "harmless" either, because it lost a little in
design on both.

**Why it barely acts here:** both arms already leave a trade early. Liquidity sells at the next liquidity level (an index
target), at its index stop or after 20 minutes unless +5%. Solo midday has index stops and a breakeven at 0.45 ATR. Few of
their options ever reach +25% before those exits fire. Examples (final):
- BANKNIFTY put, 24 Sep 2026: L25 sold at +5% (Rs 492); the own exits waited for the index exit at -14% (-Rs 1,745).
- BANKNIFTY put, 24 Jul 2026: L25 sold at +5% (Rs 476); the own exits waited for the index exit at +19.6% (Rs 2,156).

## The other arms (not tested, and why)

| arm | why the lock was not tested |
|---|---|
| ORB, ORB Fresh, ORB Sweep, Range Fade (hidden since 15 Oct) | they already have a premium ladder at 25/50/75% of their target, on their own engine; R49: 0 of 336 lock variants passed |
| Night (R3) | bought 15:20, sold 09:16 the next day: no intraday run to lock |
| Hero, Hero Rs 1,000 ticket | lottery-style tickets with their own trail (25% trail armed at 2x), Boss's choice |
| MCX arms (NG, SILVERM, trend, US silver) and Gold | their own engines, closes only (MCX lake from Aug 2025); the futures and gold arms have no option premium |
| Telegram calls, forge ideas, Pine, Jarvis | no fixed rule to replay; their own stops and targets |
| VIX divergence | off by default, failed its luck checks; its engine (`hunt/r1`) is not ported |

## Where this leaves Boss's choice

The lock helps a little only on the arms with long-running trades (Donchian, OOV, Solo 2.0: R66, +Rs 8k design / +Rs 5k
final across the three). On Liquidity and Solo midday it is noise. The pre-registered advice is to put it, if Boss wants
it, only on Donchian, OOV and Solo 2.0 (paper only, logged, undoable), and to leave every other arm's exits as they are.
**Liquidity BANKNIFTY** is the go-live candidate for 26 Oct. Nothing here touches it, and its plain-rules record stays
separable.

Method and limits as R65 / R66. Each arm's trades are made once with its own exits (Liquidity: `fam_liq`, exactly as R63
ran it; Solo midday: `plain_solo`). They are priced again with L25 from the same entry row and the same index-exit row,
and the own-exits price reproduces each arm's record exactly. An earlier exit that would have let Liquidity take a
later signal is not modelled (that could only happen on the 4-18 changed trades). The final period was already read by
R51, R57 and R63 (weak evidence). Solo midday's final period lost money under both rules (36 trades).

Files: `research/hunt/r70/` (PREREG.md, r70study.py, r70entries.py, test_r70.py, results/),
`research/experiments/entries/r70-*.yaml` (4 entries).
