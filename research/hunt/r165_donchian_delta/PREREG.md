# R165 pre-registration: DELTA on the Donchian arms (option delta and order-flow delta)

Written 12 Nov 2026, **before any R165 number was computed**. Research only; nothing in `android/**` changes.

Boss (12 Nov) on the Donchian family (R72 Breadth Donchian, R51 Donchian 20 + Supertrend; live with R151's pickers;
exits -15% stop, ladder / locks, 30-min time stop; about 46% won): *"try your best ... use all resources"*, then
*"Also check delta study in all the sessions"*. Sibling sessions R161 (entries), R162 (exits / strikes), R163 (ML picker)
and R164 (order flow) run the same day; they could not be messaged, so **R165 covers delta in all four angles**.

**Signal source for every rule: the index** (unchanged R72 / R51 breaks on index 5-min bars). Delta only (a) filters
which index breaks are taken, (b) chooses which strike is bought, (c) sets the exit, or (d) feeds the picker. The
order-flow proxies read the bought option's own minute volume and price change: that is option data used as a
**filter / exit around an index signal**, which CLAUDE.md allows as context; it is labelled as option-derived everywhere.

## 0. What is already known

- R105C: exits alone reach 60% wins only by selling at +5%, and then both arms lose money.
- R151: pickers on index / context features found no 60% threshold; its R72 and R51 logistic pickers failed their gates
  but act on paper and live by Boss's exception (6 Nov). They are the second-look baseline here.
- R152 / R153: rupee targets, stops, locks, day caps failed.
- Order flow: R7 / R8 / R10 and 54 earlier order-flow rules failed or could not be tested; no aggressor-side history
  exists for Indian index options or futures (R10). TODO S7 waits for the phone's own recordings.
- No earlier study of the bought option's delta on these arms was found (searched "delta", "ITM", "strike").

## 1. Data, engine, baseline

- Lake `gdrive:IraAlgo-data-lake` (index_1m, options_rolling_1m WEEK, fut_1m_live_index), copied 12 Nov 2026.
- R51 Amendment A clean stores; R112 / R152 candidates; R106's walk (one open a side, 2 fresh fills a side a day);
  R152's engine (dated Zerodha charges on the whole quantity, R17 slippage from the order's size), 1, 2 and 3 lots.
- **B0** = today's rules (1-ITM, -15% stop, 30-60-100 lock + L25, 30-min time stop, 15:15 square-off). At 1 lot it must
  reproduce R72 3,071 / Rs 2,16,929 (design) and 1,202 / Rs 1,71,366 (final); R51 4,208 / Rs 50,425 and 1,670 /
  Rs 1,34,743 (asserted).
- **Periods** (as R152 / R153): design 20 Nov 2024 - 31 Mar 2026 with the walk-forward window Apr 2025 - Mar 2026 (R151's
  four quarterly folds); final 1 Apr - 13 Oct 2026, read once by R165 (`results/FINAL_READ`). **Honesty: Apr - Oct 2026
  has been read by many earlier studies (R51 - R160) on these arms, so the final is a weak, repeated look.**

## 2. Option delta (Black-Scholes)

- At the **decision minute** (the minute before the entry row): S = the index close, K = the strike, P = the option's
  close (the last finite close within 5 minutes; else unknown), T = calendar time to the expiry's 15:30 (the next day
  flagged as expiry in the store, on or after today; the exchange calendar is known in advance), r = 6.5% a year, no
  dividend.
- **IV = the option's own implied volatility, solved from P** (bisection, 1% - 300%). Unknown when P is below the
  discounted intrinsic value or missing. The lake's own `iv` column (Dhan) is compared as a sanity check only.
- Delta, gamma, theta and vega follow from that IV. Put delta is used as its absolute value.
- A candidate whose delta is unknown is **kept as today** by every filter (a rule only skips what it can judge), and keeps
  today's strike in the strike rules. The count is reported.

## 3. The rules (fixed now; no parameter is fitted except the picker's model)

**A. Delta band filter** (take the break only when today's 1-ITM option's delta is in the band; others skipped before the
walk, as R151's research filters):

| id | band |
|---|---|
| DB1 | 0.45 <= delta < 0.55 |
| DB2 | 0.55 <= delta < 0.65 |
| DB3 | 0.65 <= delta <= 0.80 |

**B. Strike by target delta** (buy the strike of the same side, nearest expiry, whose delta at the decision minute is
nearest the target, instead of 1-ITM; ties go to the strike nearer today's; exits unchanged):

| id | target |
|---|---|
| SD50 | 0.50 |
| SD60 | 0.60 |
| SD70 | 0.70 |
| SD80 | 0.80 |

**C. Exits scaled by delta** (the stop set in index points via delta). P = 0.15 x premium / delta: the index move that
costs 15% of the premium at the entry delta. The stop rests at the decision-minute index close -P (call) / +P (put); when
an index 1-minute close (from the entry row on) is beyond it, the option is sold at the next minute's open.

| id | rule |
|---|---|
| XI1 | the index stop REPLACES the -15% premium stop (the ladder, lock, time stop and square-off stay) |
| XI2 | the index stop is ADDED to the -15% premium stop (whichever comes first) |

**D. Greeks in the picker.** PKD = R151's logistic picker M2 (L2, C 0.1, median-imputed, standardised; same label "net >
0", same quarterly expanding folds) with R151's 28 features **plus** 5: delta, gamma x S / 1000 / delta (the relative
delta change for a 0.1% index move), theta per day / premium, IV, and IV / the ATM option's IV at the same minute.
Its threshold keeps **the same share of walk-forward candidates as R151's app picker keeps there** (0.4656 for R72,
0.4469 for R51 on R151's own out-of-fold scores), so the two pickers are compared at the same size. For the final, the
model is trained on all design trades and frozen.

**E. Order-flow delta: PROXY ONLY (weaker evidence, reported separately).**
- The lake has no aggressor-side data. The phone's recordings since 13 Oct (private repo IraAlgo) are sealed with Boss's
  passphrase; R165 does not and will not try to open them. Index futures 1-minute bars exist only from 29 Jul 2026 (inside
  the final), so they cannot be designed on.
- **Proxy:** the bought option's minute volume signed by the tick rule (the close above the previous close = buyers,
  below = sellers, unchanged = the previous sign). Cumulative delta = the running sum.

| id | rule |
|---|---|
| OF1 | entry filter: skip when the bought option's proxy delta over the 3 minutes before entry is < 0 |
| OF2 | divergence filter: the index has just broken out (a new Donchian high / low) but the bought option's proxy cumulative delta over the 15 minutes before entry is < 0: skip |
| OF3 | in-trade exit: sell at the next minute's open once the proxy cumulative delta since the fill is below -V5, V5 = the option's total volume in the 5 minutes before entry (the arm's own exits stay) |

- **Descriptive only (never gated):** the index future's proxy delta (1-minute futures volume, tick rule) over the 3
  minutes before entry, 29 Jul - 13 Oct 2026: win rate and net by sign.

**Tests:** 13 rules x 2 arms x 3 lots = **78**.

## 4. Gates (all must hold, at the same lots)

1. **Win rate up >= 5.0 points** vs B0 in the walk-forward window AND in the final.
2. **Net not lower** than B0 in the walk-forward window AND in the final.
3. **Second look:** final net not lower than R151's app picker's final net at the same lots (the arm as it runs today).
4. **>= 40 final trades.**
5. **Better than random (final), 2,000 twins (seed 165), statistic = the win rate**; p = (1 + twins >= the rule) / 2,001:
   - filters (DB*, PKD, OF1, OF2): the same number of candidates dropped at random, walked again (R151);
   - strike rules (SD*): each candidate's strike drawn at random from the strikes whose delta is in 0.40 - 0.85 at the
     decision minute (today's strike when none);
   - exits (XI*, OF3): each trade the rule closed at a different minute from B0 is sold instead at a random minute
     strictly between its fill and the later of the two exits (the arm's exits active before it; R140).
6. **BH q <= 0.10** with m = R165's 78 + 2,164 earlier (R160's m) = **2,242**, earlier tests taken as p = 1. Raw p-values
   are published in `results/pvalues.csv` for one joint BH with the siblings R161 - R164.

Reported for every test: trades, win rate, net, per trade, worst month, max drawdown, per period. Delta distribution of
the arms' trades and win rate / net by delta bucket are reported (descriptive).

## 5. What a pass means (CLAUDE.md)

- Nothing changes in the app from R165.
- A passing rule could at most act on PAPER for that arm behind a labelled switch, logged in the Autopilot timeline,
  undoable in one tap. Anything that could skip or resize LIVE trades starts in shadow (logged only), off by default.
- If order-flow data is too thin (it is: no aggressor history; 5 sealed recorded days), R165 writes a pre-registered
  forward SHADOW log for the app (what to log at entry and every N seconds) with a fixed count before deciding.
- Every test is logged in `research/experiments/entries/r165-*.yaml`, failures included.

## 6. Tests (pytest)

Black-Scholes price / delta / IV round trip and edge cases (expiry minute, deep ITM, missing price); the strike chooser;
the index-stop trigger row; the tick-rule proxy; and the look-ahead guard (scrambling every minute from the entry row on
changes no decision-minute value).
