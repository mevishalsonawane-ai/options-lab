# R98 implementation notes (dated; each written before the result it could affect)

- **N1 (21 Oct, after the pre-registration commit b532522c, before any rule result was seen):** the first design run
  stopped in the report step (R96 `frame` expects `fill` / `exit` as lists; `engine.py` gave floats). Fixed the dict
  shape only; no trading logic, rule, exit or cost changed. The rerun is the same frozen rules.
- **N2 (21 Oct, AFTER the design results; a diagnosis, not a gate):** the stop exits averaged -186 points against a
  -100 stop (minute closes overshoot; gold often moves 50-100+ points inside a minute). Re-priced with every stop,
  lock and target filled exactly at its level and no spread (the most generous case): gross Rs 56-168 a trade vs
  Zerodha charges Rs 282-296 a trade; every rule still negative. The step-1 descriptive +42.5 points of the XAU-lead
  cell was inflated by the same overshoot on targets: at levels it is +13.6 points (P(target first) 0.554 vs stop
  0.418, 3,197 moments). The pre-registered verdicts stand as run; nothing was re-gated.
