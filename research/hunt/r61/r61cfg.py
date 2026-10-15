"""R61 "Solo 3.0" settings (PREREG.md). Research only; nothing here changes the app.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake), copied with rclone from gdrive:IraAlgo-data-lake; never
              committed
    cache     $R61_OUT (default ~/.cache/iraalgo/r61): R57's day stores rebuilt here, plus R61's chain features
    results   research/hunt/r61/results (small JSON / markdown only)

R61 reuses R57 (candidates S1-S4, held-contract labels, walk-forward, twins) unchanged. R57's config reads $R57_OUT when
it is imported, so it is pointed at R61's cache here, before r57cfg is imported anywhere.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
R57 = HERE.parent / "r57"
CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R61_OUT", CACHE_ROOT / "r61")).expanduser()
os.environ["R57_OUT"] = str(OUT)
for p in (R57, HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

import r57cfg as K57  # noqa: E402

# if r57cfg was imported earlier in this process it kept its own cache: point it (and R51's) at R61's either way
K57.OUT = OUT
K57._K51.OUT = OUT
LAKE = K57.LAKE
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
START, DESIGN_END, FINAL_START = K57.START, K57.DESIGN_END, K57.FINAL_START
WF_MONTHS = K57.WF_MONTHS
UNDS = K57.UNDS
NROWS = K57.NROWS
SEED = 61
TWINS = 500
BOOT = 10_000
row = K57.row

# Black-Scholes settings for Greeks / GEX (PREREG section 4)
RATE = 0.065
YEAR_SESSIONS = 252.0
FLIP_GRID = 0.03          # +-3% of the index
FLIP_STEP = 0.001         # 0.1% steps

# ------------------------------------------------------------------------------------------------ feature blocks
CH = ["oi_near_log", "oi_near_chg_pct", "writer_tilt30", "buildup", "pcr_oi_chg30", "pcr_vol", "pcr_rank5",
      "pcr_rank20", "maxpain_dist_pct", "wall_ahead_pct", "wall_behind_pct", "iv_rank60", "iv_chg_day", "smile_curv",
      "term_slope", "gex_norm", "gex_sign", "flip_dist_pct", "synth_basis_bps"]
GK = ["delta_abs", "gamma_1pct", "theta_pct_day", "vega_pct"]
CX = ["vix_rank60", "va_pos", "event_rbi_budget", "post_us_event", "other_expiry_today", "fii_fut_long_share",
      "fii_fut_long_chg"]
NEW = CH + GK + CX
# signed by the candidate's side (x +1 call, x -1 put); the wall features are built side-aware instead
SIGNED_NEW = ("writer_tilt30", "buildup", "pcr_oi_chg30", "pcr_vol", "pcr_rank5", "pcr_rank20", "maxpain_dist_pct",
              "flip_dist_pct", "va_pos")
# ratio / rank features are centred before signing so that "neutral" is 0 (pcr 1, rank 0.5)
CENTRE = {"pcr_vol": 1.0, "pcr_rank5": 0.5, "pcr_rank20": 0.5}

# concept groups for the ablation (PREREG section 6)
GROUPS = {
    "oi_level_change": ["oi_near_log", "oi_near_chg_pct", "writer_tilt30"],
    "buildup": ["buildup"],
    "pcr": ["pcr_oi_chg30", "pcr_vol", "pcr_rank5", "pcr_rank20"],
    "max_pain": ["maxpain_dist_pct"],
    "oi_walls": ["wall_ahead_pct", "wall_behind_pct"],
    "iv_level_rank": ["iv_rank60", "iv_chg_day"],
    "skew_smile_term": ["smile_curv", "term_slope"],
    "gex_flip": ["gex_norm", "gex_sign", "flip_dist_pct"],
    "basis": ["synth_basis_bps"],
    "greeks": GK,
    "vix_rank": ["vix_rank60"],
    "value_area": ["va_pos"],
    "events": ["event_rbi_budget", "post_us_event"],
    "other_expiry": ["other_expiry_today"],
    "participants": ["fii_fut_long_share", "fii_fut_long_chg"],
}
assert sorted(sum(GROUPS.values(), [])) == sorted(NEW)

# ------------------------------------------------------------------------------------------------ variants
VARIANTS = {
    "V1": dict(kind="logit", blocks=()),
    "V2": dict(kind="logit", blocks=("CH", "GK", "CX")),
    "V3": dict(kind="logit", blocks=("GK", "CX")),
    "V4": dict(kind="logit", blocks=("CH",)),
    "V5": dict(kind="gbc", blocks=("CH", "GK", "CX")),
    "V6": dict(kind="gbc", blocks=("GK", "CX")),
    "V7": dict(kind="l1", blocks=("CH", "GK", "CX")),
}
BLOCKS = {"CH": CH, "GK": GK, "CX": CX}

# gates (never relaxed)
GATE_P, GATE_Q, GATE_MIN_TRADES = 0.05, 0.10, 40
REPLACE_BOOT_P = 0.10

# the lake's last session (14 Oct 2026 in catalogue release data-20261014); R61_END overrides it when the lake is newer
if os.environ.get("R61_END"):
    from datetime import date as _date
    K57.END = _date.fromisoformat(os.environ["R61_END"])
    K57._K51.END = K57.END
END = K57.END
FINAL_MONTHS = [(2026 + (m - 1) // 12, (m - 1) % 12 + 1) for m in range(4, 12 * (END.year - 2026) + END.month + 1)]
