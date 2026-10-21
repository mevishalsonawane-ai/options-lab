# R99 pre-registration: "catch the 100+ rupee move" on index options (21 Oct 2026)

Boss, 21 Oct: catch the 100+ rupee move in any condition (after R97 / R98 on gold: "Continue now"). Research only;
nothing in `android/**` or `.github/**` changes; nothing goes to paper or live.

Written and committed **before any R99 rule or exit variant below was priced.** Looked at first (design only,
descriptive): the NIFTY base rates and the one-feature scan (`r99dataset.py`, `r99scan.py`, `results/scan.json`), and
a reproduction of R57 Solo 2.0 A2's committed design walk-forward (166 trades, +Rs 55,910, matched to the rupee; it is
R57's own result, not an R99 statistic).

## 1. What the descriptive step found (NIFTY, design 21 Nov 2024 - 31 Mar 2026, 336 sessions)

- "100 rupees" = **100 points of the ATM weekly option premium** (Rs 100 on the quote; x 75 / 65 a lot in rupees).
- NIFTY's median day range 211 points; ATM weekly premium median 117 (43 on expiry days). A round trip costs about
  Rs 60-75 of charges a lot + R17 fills (1-4 ticks + 5 bps), so costs are ~1-2% of a +100 move (gold: ~30%).
- Buying the ATM call or put at a random 5-minute moment (09:20-14:30) with a +100 limit target, a -40 stop, else the
  square-off: target 9.2%, stop 29.3%, square-off 61.5%; **-Rs 161 a trade** (42,334 moments; calls -Rs 274, puts
  -Rs 48). +50 / -25: -Rs 175.
- One-feature scan (218 buckets, pooled net with day-clustered p, BH): **no bucket positive at q <= 0.10**; the best
  (a gap of 64+ points in the trade's direction) +Rs 115, p 0.54. So on NIFTY a +100 target needs a real entry edge.

## 2. Window, data, engine

R51 / R57's window and split (Boss, 15 Oct: "Nov 2024 onwards"): **DESIGN 21 Nov 2024 - 31 Mar 2026; FINAL 1 Apr -
13 Oct 2026, read once by R99** (`r99study.py final`, lock file `results/FINAL_READ`), only if a rule passes every
design gate. The final window has been read by other studies for their own rules, so it is **not fresh**; that is
reported. Prices: R51's held-contract engine unchanged (real 1-minute prints of the one contract bought; lakeclean
foreign / stray / stale prints removed; R17 fills; dated charges and lots; square-off 15:10, 15:05 on expiry days).
**Signal source: the index** (NIFTY; for R99B / C the four indices R57 trades), never an option's chart.

## 3. The three ideas (BH over these 3; R57's 10 variants are reported alongside)

**R99A - NIFTY model for the +100 trade.** sklearn `HistGradientBoostingRegressor(learning_rate=0.05, max_iter=300,
max_leaf_nodes=15, min_samples_leaf=300, l2_regularization=1.0, random_state=99)` on every causal feature column of
the R99 dataset (`r99scan.feature_cols`: index features of R97 `features`, R98 candles, VIX level / 15-min change,
sessions to expiry, expiry flag, premium as % of the index, side, clock), label = net rupees of the +100 / -40 /
square-off trade (`net_100`). Walk-forward: each of R51's design quarters (Apr-Jun, Jul-Sep, Oct-Dec 2025, Jan-Mar
2026) is predicted by a model trained only on design rows dated before the quarter's first day minus one session;
21 Nov 2024 - 31 Mar 2025 is training only. Trade: the FIRST 5-minute moment of a session whose prediction >= +Rs 300,
one trade a session, the +100 / -40 / square-off exits. Final: one model trained on all design rows.

**R99B - Solo 2.0 entries, "catch 100" exit.** R57 A2's own trades (its walk-forward selection, unchanged: same index,
day, side, strike, entry minute), re-priced with: limit target **+100 premium points**, resting stop **-40 points**,
else the square-off. (Solo's index stop for S1 is not used.)

**R99C - Solo 2.0 entries, "let it run" exit.** The same A2 trades with Solo's own premium stop for the setup
(S1 none -> -30%; S2 -15%; S3 -30%; S4 -25%), **no target**, and a profit lock: once +50 premium points, keep +25,
and trail 40 points under the best price; else the square-off.

The reference (not a test): A2's trades with Solo's own exits (R57).

## 4. Gates (all on design; never relaxed)

R99A: >= 40 trades; net > 0 at 1x and at 2x fills (R51 SLIP2); random-entry twins p < 0.05 (each trade re-run on the
same session at a uniformly random 5-minute moment 09:20-14:30, same side, strike rule and exits; 500 sets); BH q <=
0.10 over 3; >= 3 of the 4 quarters positive; no month < -Rs 13,000; drawdown > -Rs 40,000.

R99B / R99C (exit changes on the same trades): net > 0; **net minus Solo's own net on the same trades > 0** with a
day-block bootstrap p < 0.05 (resampling sessions, 2,000 draws, seed 99) and BH q <= 0.10 over 3; the difference > 0
in >= 3 of the 4 quarters; no month < -Rs 13,000; drawdown > -Rs 40,000; net at 2x fills > 0.

Final (only for a full design pass): R99A net > 0, twin p <= 0.10; R99B / C net > 0 and better than Solo's own exits
on the same final trades.
