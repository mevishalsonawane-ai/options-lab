# HUNT MB1: strategies from the MCX brain's outputs (L4), 20 Oct 2026 - 0 of 16 pass

Part of the MCX Data Brain (`research/BRAIN_MCX.md`); pre-registered in `research/brain/mcx/PREREG.md` section 7
(commit 8f1d9f5b, before any forecast or trade). Code: `research/brain/mcx/l4.py`. Numbers: `research/brain/mcx/results/l4_design.json`.
Entries: `research/experiments/entries/mb1-l4-b1..b4.yaml`. Nothing goes to paper; no APP_SPEC.

**Signal source:** the MCX future (its minute closes) and the brain's forecasts made from them; ATM IV is context
(B4). **Traded:** one mini-future lot (GOLDM; SILVERM; CRUDEOILM from the CRUDEOIL series; NATGASMINI from the
NATURALGAS series), or for B4 one ATM call + one ATM put of the option book (GOLDM, SILVERM, CRUDEOIL, NATURALGAS lots).

**Data and forecasts:** the brain's walk-forward TEST forecasts on design blocks 3-6 (21 Nov 2025 - 23 Jun 2026, about
150 sessions), so every trade used models fitted only on earlier sessions. The locked holdout (from 1 Jul 2026) was
NOT read for rules: nothing passed the design gate.

**Fills and costs:** entry at the first minute close within 5 minutes after the decision; stops and targets on minute
closes; 60-minute time exit; flat 15 minutes before the MCX close; one open trade per symbol and variant. Zerodha MCX
futures charges (R53 `mcx_fut_charges`), R53 futures half-spread on entry and exit, 0.02% stop slippage (R83). Options:
R53 `mcx_opt_charges` and `opt_half_spread` per leg, printed minutes only, no trades on option expiry days.

**Twins:** 200 per variant (seed 20261019 + offsets): the same number of trades at random forecast rows of the same
symbol, test blocks and clock hour, random side (B1-B3) or the same straddle (B4), with the rule's own exits.
**BH** over all 32 variant tests. **Design gate:** frozen net > 0, walk-forward net > 0, q <= 0.10, >= 30 trades, net
> 0 without the 5 best trades.

## Rules

| rule | entry | exit |
|---|---|---|
| B1 committee direction | the L3 committee acts: long at p >= thr, short at p <= 1 - thr (thr 0.55 / 0.60) | 60 min; stop 1.0 x predicted 60-min range |
| B2 big-size follow | predicted range in the top third / fifth of the fold's inner forecasts and the last 30 minutes moved >= 0.33 x it: follow | target 1.0 x, stop 0.75 x predicted range, 60 min |
| B3 quiet fade | predicted range in the bottom third and the close >= k x predicted range from the session mean of closes (k 1.0 / 1.5): fade | target the session mean, stop 0.75 x, 60 min |
| B4 size-edge straddle | predicted range / IV-implied 60-minute move in the top tenth / fifth of the fold's inner rows: buy the ATM straddle | 60 min; stop at -30% of the straddle |

## Every variant (design, net in Rs after costs)

| rule | symbol | variant | trades | win | gross | costs | net | net without top 5 | worst month | twin p | BH q | exits stop/target/time |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| B1 | GOLDM | thr 0.55 | 1341 | 44% | 17,600 | 666,632 | -649,032 | -888,406 | -263,164 | 0.468 | 0.796 | 219/0/1122 |
| B1 | GOLDM | thr 0.6 (frozen) | 710 | 45% | 166,510 | 355,587 | -189,077 | -432,078 | -116,259 | 0.154 | 0.617 | 116/0/594 |
| B2 | GOLDM | q size_q67 | 622 | 42% | -19,130 | 328,623 | -347,753 | -551,956 | -241,027 | 0.542 | 0.826 | 152/116/354 |
| B2 | GOLDM | q size_q80 (frozen) | 454 | 43% | 19,590 | 241,515 | -221,925 | -426,129 | -237,734 | 0.423 | 0.796 | 106/86/262 |
| B3 | GOLDM | k 1.0 | 254 | 45% | 12,170 | 132,953 | -120,783 | -154,896 | -44,306 | 0.483 | 0.796 | 68/21/165 |
| B3 | GOLDM | k 1.5 (frozen) | 113 | 47% | 1,650 | 59,455 | -57,805 | -89,984 | -32,862 | 0.498 | 0.796 | 31/3/79 |
| B4 | GOLDM | q ratio_q90 (frozen) | 110 | 32% | -19,425 | 62,943 | -82,368 | -116,951 | -27,188 | 0.323 | 0.796 | 1/0/109 |
| B4 | GOLDM | q ratio_q80 | 279 | 26% | -36,460 | 160,955 | -197,415 | -243,696 | -52,672 | 0.184 | 0.655 | 1/0/278 |
| B1 | SILVERM | thr 0.55 | 1140 | 48% | -222,205 | 476,436 | -698,641 | -1,028,467 | -336,492 | 0.736 | 0.906 | 156/0/984 |
| B1 | SILVERM | thr 0.6 (frozen) | 685 | 46% | -248,355 | 290,832 | -539,187 | -881,384 | -250,241 | 0.826 | 0.911 | 86/0/599 |
| B2 | SILVERM | q size_q67 | 734 | 45% | 464,975 | 321,485 | 143,490 | -204,956 | -123,046 | 0.045 | 0.287 | 153/120/461 |
| B2 | SILVERM | q size_q80 (frozen) | 570 | 46% | 503,840 | 250,475 | 253,365 | -95,081 | -123,826 | 0.030 | 0.239 | 113/93/364 |
| B3 | SILVERM | k 1.0 | 99 | 49% | 20,290 | 46,616 | -26,326 | -76,221 | -26,116 | 0.378 | 0.796 | 26/9/64 |
| B3 | SILVERM | k 1.5 (frozen) | 50 | 54% | 8,980 | 24,859 | -15,879 | -59,887 | -12,050 | 0.473 | 0.796 | 17/1/32 |
| B4 | SILVERM | q ratio_q90 (frozen) | 315 | 21% | -180,048 | 267,337 | -447,385 | -476,817 | -149,033 | 0.726 | 0.906 | 0/0/315 |
| B4 | SILVERM | q ratio_q80 | 451 | 21% | -282,632 | 386,044 | -668,676 | -696,255 | -149,033 | 0.900 | 0.961 | 0/0/451 |
| B1 | CRUDEOIL | thr 0.55 (frozen) | 1438 | 39% | 34,580 | 110,946 | -76,366 | -106,787 | -22,277 | 0.070 | 0.371 | 217/0/1221 |
| B1 | CRUDEOIL | thr 0.6 | 860 | 37% | -20,690 | 65,225 | -85,915 | -99,793 | -32,845 | 0.826 | 0.911 | 108/0/752 |
| B2 | CRUDEOIL | q size_q67 (frozen) | 785 | 44% | 39,160 | 62,219 | -23,059 | -39,134 | -11,644 | 0.025 | 0.239 | 176/125/484 |
| B2 | CRUDEOIL | q size_q80 | 694 | 43% | 23,910 | 55,522 | -31,612 | -47,688 | -11,703 | 0.144 | 0.617 | 154/102/438 |
| B3 | CRUDEOIL | k 1.0 | 176 | 35% | -290 | 14,291 | -14,581 | -21,118 | -10,484 | 0.617 | 0.879 | 50/14/112 |
| B3 | CRUDEOIL | k 1.5 (frozen) | 89 | 38% | 1,520 | 7,199 | -5,679 | -11,864 | -6,846 | 0.313 | 0.796 | 25/3/61 |
| B4 | CRUDEOIL | q ratio_q90 (frozen) | 472 | 28% | -11,520 | 379,946 | -391,466 | -443,137 | -164,050 | 0.945 | 0.976 | 0/0/472 |
| B4 | CRUDEOIL | q ratio_q80 | 612 | 27% | -58,550 | 448,632 | -507,182 | -557,832 | -180,743 | 0.985 | 0.985 | 0/0/612 |
| B1 | NATURALGAS | thr 0.55 | 1315 | 43% | -4,100 | 116,572 | -120,672 | -168,020 | -37,912 | 0.632 | 0.879 | 243/0/1072 |
| B1 | NATURALGAS | thr 0.6 (frozen) | 628 | 42% | 14,375 | 56,185 | -41,810 | -69,584 | -31,815 | 0.358 | 0.796 | 123/0/505 |
| B2 | NATURALGAS | q size_q67 | 615 | 49% | 76,025 | 56,555 | 19,470 | -6,954 | -11,066 | 0.005 | 0.080 | 173/153/289 |
| B2 | NATURALGAS | q size_q80 (frozen) | 421 | 48% | 58,875 | 38,782 | 20,093 | -6,332 | -9,525 | 0.005 | 0.080 | 110/98/213 |
| B3 | NATURALGAS | k 1.0 | 271 | 39% | 1,475 | 24,090 | -22,615 | -26,494 | -9,126 | 0.478 | 0.796 | 87/31/153 |
| B3 | NATURALGAS | k 1.5 (frozen) | 140 | 40% | 2,975 | 12,374 | -9,399 | -13,182 | -4,826 | 0.259 | 0.796 | 38/5/97 |
| B4 | NATURALGAS | q ratio_q90 (frozen) | 308 | 17% | -58,188 | 119,145 | -177,332 | -198,237 | -67,605 | 0.821 | 0.911 | 0/0/308 |
| B4 | NATURALGAS | q ratio_q80 | 447 | 22% | -66,875 | 174,770 | -241,645 | -258,851 | -76,866 | 0.682 | 0.906 | 0/0/447 |

## Gates (frozen variant)

| cell | frozen | walk-forward net | failed gates | verdict |
|---|---|---|---|---|
| B1:GOLDM | v1 | -121,046 (picks [1, 1, 1]) | net_pos, q<=0.10, top5_removed_pos | fail |
| B1:SILVERM | v1 | -459,389 (picks [1, 1, 1]) | net_pos, q<=0.10, top5_removed_pos | fail |
| B1:CRUDEOIL | v0 | -58,668 (picks [1, 0, 0]) | net_pos, q<=0.10, top5_removed_pos | fail |
| B1:NATURALGAS | v1 | 7,136 (picks [1, 1, 1]) | net_pos, q<=0.10, top5_removed_pos | fail |
| B2:GOLDM | v1 | -217,101 (picks [1, 1, 1]) | net_pos, q<=0.10, top5_removed_pos | fail |
| B2:SILVERM | v1 | 410,547 (picks [1, 1, 1]) | q<=0.10, top5_removed_pos | fail |
| B2:CRUDEOIL | v0 | -17,244 (picks [1, 1, 0]) | net_pos, q<=0.10, top5_removed_pos | fail |
| B2:NATURALGAS | v1 | 19,962 (picks [0, 0, 1]) | top5_removed_pos | fail |
| B3:GOLDM | v1 | -59,006 (picks [1, 1, 1]) | net_pos, q<=0.10, top5_removed_pos | fail |
| B3:SILVERM | v1 | -21,527 (picks [1, 1, 1]) | net_pos, q<=0.10, top5_removed_pos | fail |
| B3:CRUDEOIL | v1 | -3,624 (picks [1, 1, 1]) | net_pos, q<=0.10, top5_removed_pos | fail |
| B3:NATURALGAS | v1 | -19,452 (picks [0, 0, 1]) | net_pos, q<=0.10, top5_removed_pos | fail |
| B4:GOLDM | v0 | -53,775 (picks [0, 0, 0]) | net_pos, q<=0.10, top5_removed_pos | fail |
| B4:SILVERM | v0 | -361,811 (picks [0, 0, 0]) | net_pos, q<=0.10, top5_removed_pos | fail |
| B4:CRUDEOIL | v0 | -375,369 (picks [0, 0, 0]) | net_pos, q<=0.10, top5_removed_pos | fail |
| B4:NATURALGAS | v0 | -161,049 (picks [0, 0, 0]) | net_pos, q<=0.10, top5_removed_pos | fail |

## Reading

- Costs decide again. B1 (trade the committee's direction) is roughly break-even gross on gold and crude and loses
  Rs 42k-5.4 lakh after costs: a 50-53% hit rate cannot pay Rs 75-500 a round trip on a one-hour hold.
- B2 "big-size follow" is the only lead: positive gross and net on silver mini (+Rs 2.53 lakh, 570 trades, twin p 0.03)
  and natural gas mini (+Rs 20k, 421, twin p 0.005, BH q 0.08), and positive walk-forward on both, but both lose
  without their 5 best trades (-Rs 95k, -Rs 6k), and silver mini's q is 0.24. A note for a new pre-registered test,
  not a rule.
- B3 fires rarely (50-271 trades) and loses everywhere: quiet hours do not snap back to the session mean enough.
- B4: buying the straddle when the brain expects more movement than the option prices loses on every commodity
  (17-32% winners, -Rs 82k to -Rs 4.5 lakh): the brain's size edge is real (8-13% on the holdout) but smaller than an
  MCX option buyer's spread and decay.
- Worst month below -Rs 13k for most cells: none could pass Boss's account rule either.
