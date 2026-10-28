# Forward test pre-registration: the time machine and R122's frozen rule on the phone's own new trades

Written 28 Oct 2026, **before the first logged check exists** (the shadow logging ships in the app build after commit
of this file). Boss, 28 Oct: *"yes build the shadow logging"*. This is the only honest test left after R120-R122
(research/HUNT_R122_CONTEXT_GATE.md): trades no study has seen.

**Signal source:** each arm's own (the underlying; Hero's strike by the option's price, labelled). The rules below are
filters read from the underlying index, India VIX and the arms' earlier entries only.

## Data

- The phone's daily diagnostics (private repo `mevishalsonawane-ai/IraAlgo`, releases `diag-YYYY-MM`), key
  `time_machine` (since the build with this change). Each check carries its paper trade's result (`pct`) once the arm
  reports the close; a check without it is joined by `forward.py` to the same file's arm trades (same day, arm key and
  direction, entry minutes within 2 minutes). Checks with no result either way are counted and reported, never guessed.
- Paper trades only (live trades are few and their lots are hidden). Hidden arms (the Hero Rs 1,000 ticket) are kept.
- Outcome: the trade's result in percent of the premium (`pct`, the price move BEFORE charges; charges are about equal
  per trade, so a skip-versus-random comparison is not biased by them; research adds each arm's charges when it reports
  rupees); win = pct > 0. A few close paths (the ntfy arm's partial sells) report no pct: joined by `forward.py` or
  counted as unmatched. MCX checks name the contract, not an index (R122's inputs then use that future's own bars).
- The index's 1-minute bars of those days from the Drive lake (or the phone's recordings) for R122's inputs, computed by
  the same code as R122 (`r122feat.py`), from bars before the entry minute only.

## Rules (frozen now; nothing else is tested on this data)

- **F1 time machine SKIP_AGAINST**: skip entries the app's own verdict calls "against" (R120's rule).
- **F2 R122 D1** (the loop's frozen rule): skip when the 5-minute Supertrend (10, 3), the opening gap (> 0.25%), chasing
  (move since the open > 1.0 ATR14) or the 20-day trend is against the trade.

## When it is read, once

After at least **30 signal days** with checks AND at least **300** joined paper trades; if 60 signal days pass with
fewer, it is reported as too few and read again only with a new pre-registration.

## Gates (per rule, pooled over the arms)

1. pooled mean pct after skipping > before;
2. above the random-skip twin's 95th percentile (same number skipped at random per arm), normal-approximation p <= 0.05;
3. BH q <= 0.10 over every logged test to the read day plus these 2;
4. at least 40 trades kept on every arm it would apply to; pooled drawdown (cumulative pct) not worse.

A rule that passes is proposed to Boss as a **paper** gate (logged in the Autopilot change timeline, one-tap undo, only
on arms whose net rose); live is never touched by it. A rule that fails ends this line of work.
