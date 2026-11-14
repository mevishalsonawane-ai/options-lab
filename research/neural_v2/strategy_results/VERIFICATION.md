# Strategy verification

- 28 neural/strategy tests passed, including 8 new strategy tests.
- Existing research suite: 209 passed, 1 existing skip; no tests disabled.
- Feature-prefix causality, fixed-contract tracking, conservative stop/target ordering, delayed profit-lock updates, missing quote handling, terminal pricing failure, cash admission, day trade cap, monthly risk admission and independent stress-account replay tested.
- Pre-March IPFT explicitly included. Inclusive exchange/IPFT cost remains Rs3553/crore across the March 2026 administrative change; STT change tested independently.
- Both ledgers reconcile gross minus fees minus execution friction to net P&L and ending capital. Win counts, trade counts, one-position chronology and maximum two daily trades verified.
- No price/signal thresholds changed after viewing this strategy's result. Initial inherited fee implementation and corrected account rerun retained for transparency.
- Previously examined historical data; no fresh final validation or profitable edge claim. No production or workflow changes.
