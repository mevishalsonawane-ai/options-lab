# R93 result: Breadth Donchian on MCX commodities (21 Oct 2026)

**Answer: no. Breadth Donchian (and plain Donchian) does not work on MCX commodities. 0 of 5 pre-registered variants
pass; nothing goes to paper, so there is no APP_SPEC.** Research only; no app or workflow change.

- **5-minute (the app's rule moved to MCX):** a Donchian break on a commodity earns about nothing before costs (option
  trades -Rs 82 to +Rs 86 a trade) against Rs 100-520 of MCX option costs. Locked holdout (1 Apr - 19 Oct 2026, read
  once): plain -Rs 30.06 lakh on 3,365 trades, breadth -Rs 17.93 lakh on 1,957, cross-market -Rs 22.57 lakh on 2,485;
  every month below the -Rs 13k account stop; no better than random entries.
- **Breadth helps a little, not enough:** breaks the group confirms lose less (-Rs 805 vs -Rs 914 a trade); on the
  indices confirmed breaks made +Rs 183 a trade, on MCX both lose.
- **Daily (futures, 2012-2026):** plain Donchian +Rs 7.26 lakh in the holdout, but random entries made as much (the
  2023-26 metals rally); daily breadth lost.
- **Sample:** long enough to know for the intraday rule (1,957-3,365 holdout trades per variant; even the optimistic
  bound of each option book's gross, computed after the read, is below its cost).

Details: `research/HUNT_R93_BREADTH_DONCHIAN_MCX.md`; code, tests and results: `research/hunt/r93_breadth_donchian_mcx/`;
entries `research/experiments/entries/r93-*.yaml`; FINDINGS.md (R93).

Checks run: `python -m pytest research/brain/tests -q`, `python -m research.brain.features catalog --check`,
`python -m research.experiments.validate`, R93's 38 tests (incl. the look-ahead guard), secret grep, self-review of the
diff.
