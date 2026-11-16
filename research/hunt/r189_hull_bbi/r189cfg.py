"""R189 settings (PREREG.md): Boss's Hull line ("BBI"), 2 closed 1-minute index candles above the rising line (calls) or
below the falling line (puts), buy the option; the exit is chosen by research on the design folds (Boss, 16 Nov 2026:
"Find the exit by research"). 1-min only: no 10-second index data is reachable (PREREG section 1).

    data      the `dhan-data` branch of options-lab, copied out of git with `git archive` (never committed) and read
              with research/obuy/data.py: R189_DATA (default <scratch>/r189data/repo/dhan-data)
    cache     R189_OUT (default ~/.cache/iraalgo/r189): per-index day stores, trades and twin tables (never in git)
    results   research/hunt/r189_hull_bbi/results (small JSON / markdown only)

Signal source for every rule: the index spot (CLAUDE.md default); the option is only traded. Plain-English notes,
16 Nov 2026.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
R51 = HERE.parent / "r51"
for p in (str(ROOT), str(ROOT / "research"), str(R51), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

_SCRATCH = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
DATA = Path(os.environ.get("R189_DATA", f"{_SCRATCH}/r189data/repo/dhan-data")).expanduser()
CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R189_OUT", CACHE_ROOT / "r189")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
BSE = {"SENSEX"}
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}
TICK = 0.05

# ------------------------------------------------------------------------------------------------ grid (PREREG 3)
N = 375                  # 1-minute rows 09:15 .. 15:29
OPEN_M = 9 * 60 + 15
FIRST_T = 4              # decision candle 09:19 -> order 09:20 (no entry before 09:20)
LAST_T = 344             # decision candle 14:59 -> order 15:00 (the arms' last entry)
SQ_ROW = 355             # 15:10 square-off (r51cfg.SQ_ROW)
SQ_ROW_EXPIRY = 350      # 15:05 on an expiry day (r51cfg.SQ_ROW_EXPIRY, CLAUDE.md)
FILL_SLACK = 3           # the order minute or up to 2 minutes later (R51)

# ------------------------------------------------------------------------------------------------ the Hull line
HMA_LEN = 55
HMA_HALF = HMA_LEN // 2                    # Pine: 55 / 2 with an int input -> 27 (PREREG 3)
HMA_SQRT = int(round(HMA_LEN ** 0.5))      # math.round(math.sqrt(55)) = 7
LINES = ("BOSS", "STD")                    # Boss's script (2 x WMA55 - WMA27) / the standard Hull (2 x WMA27 - WMA55)

# ------------------------------------------------------------------------------------------------ exits (PREREG 3)
STOP_F = 0.15            # the repo's usual -15% premium stop, on every candidate
# id -> dict(line: 0 none / 1 flip / 2 cross / 3 either, istop: bool, rk: R target (0 none), tgt: premium target
#             fraction (<0 none), steps: ((best gain, kept gain), ...), tmax: minutes held (<0 none))
LOCK50 = tuple((g / 100, g / 200) for g in range(20, 501, 10))
LOCK60 = tuple((g / 100, round(g * 0.6) / 100) for g in range(10, 501, 10))
_X = dict(line=0, istop=False, rk=0.0, tgt=-1.0, steps=(), tmax=-1)
EXITS = {
    "X01": dict(_X, line=1),
    "X02": dict(_X, line=2),
    "X03": dict(_X, line=3),
    "X04": dict(_X),
    "X05": dict(_X, tgt=0.10),
    "X06": dict(_X, tgt=0.20),
    "X07": dict(_X, tgt=0.30),
    "X08": dict(_X, istop=True),
    "X09": dict(_X, istop=True, rk=1.5),
    "X10": dict(_X, istop=True, rk=2.0),
    "X11": dict(_X, steps=LOCK50),
    "X12": dict(_X, steps=LOCK60),
    "X13": dict(_X, tmax=5),
    "X14": dict(_X, tmax=10),
    "X15": dict(_X, tmax=15),
    "X16": dict(_X, tmax=30),
}
EXIT_NAME = {
    "X01": "1-min line flips against the trade", "X02": "1-min candle closes back across the line",
    "X03": "1-min flip or close back across (the brief's first primary)", "X04": "-15% premium stop alone",
    "X05": "+10% premium target", "X06": "+20% premium target", "X07": "+30% premium target",
    "X08": "index stop at the signal candle's low / high", "X09": "index stop + 1.5R index target",
    "X10": "index stop + 2R index target", "X11": "step lock: keep 50% of the best gain from +20%",
    "X12": "step lock: keep 60% of the best gain from +10%", "X13": "time stop 5 minutes",
    "X14": "time stop 10 minutes", "X15": "time stop 15 minutes", "X16": "time stop 30 minutes",
}
UNTESTABLE = {"X17": "10-sec line flips against the trade", "X18": "10-sec candle closes back across the line"}

# ------------------------------------------------------------------------------------------------ rule sets
# (line, reading, money, exit). E-BOSS x every exit; the three entry variants take the picked exit (filled at run time).
PRIMARY = ("BOSS", "close", 0)
VARIANTS = {"STD": ("STD", "close", 0), "LOW": ("BOSS", "low", 0), "ITM": ("BOSS", "close", 1)}
VARIANT_NAME = {"STD": "standard Hull line", "LOW": "the whole candle (low / high) beyond the line", "ITM": "1-ITM strike"}


def rule_sets(pick: str | None) -> dict:
    """id -> (line, reading, money, exit). 16 E-BOSS candidates, plus the 3 variants once the exit is picked."""
    out = {f"BOSS-{x}": PRIMARY + (x,) for x in EXITS}
    if pick is not None:
        for v, e in VARIANTS.items():
            out[f"{v}-{pick}"] = e + (pick,)
    return out


N_RULESETS = len(EXITS) + len(VARIANTS)    # 19

# ------------------------------------------------------------------------------------------------ cells and tests
IDX_SCOPES = UNDS + ("all",)
SIDE_SCOPES = ("C", "P", "both")
SIDE_NAME = {"C": "calls", "P": "puts", "both": "calls + puts"}
LOTS = (1, 2, 3)
N_TESTS = N_RULESETS * len(IDX_SCOPES) * len(SIDE_SCOPES) * len(LOTS)    # 1,026

# ------------------------------------------------------------------------------------------------ periods (PREREG 4)
WARM_START = date(2024, 9, 2)
START = date(2024, 11, 20)
DESIGN_END = date(2026, 3, 31)
FINAL_START = date(2026, 4, 1)
END = date(2026, 10, 6)
FOLDS = ((date(2024, 11, 20), date(2025, 3, 31)), (date(2025, 4, 1), date(2025, 7, 31)),
         (date(2025, 8, 1), date(2025, 11, 30)), (date(2025, 12, 1), date(2026, 3, 31)))
PERIODS = ("design", "final", "full")
PERIOD_NAME = {"design": "design (20 Nov 2024 - 31 Mar 2026)", "final": "last 6 months = final (1 Apr - 6 Oct 2026)",
               "full": "full period (20 Nov 2024 - 6 Oct 2026)"}


def span_of(period: str) -> tuple:
    return {"design": (START, DESIGN_END), "final": (FINAL_START, END), "full": (START, END)}[period]


def in_period(d: date, period: str) -> bool:
    lo, hi = span_of(period)
    return lo <= d <= hi


# ------------------------------------------------------------------------------------------------ twins and gates
TWINS = 2000
SEED = 189
GATE_P = 0.05
GATE_Q = 0.10
MIN_FINAL = 40
MAX_DD = 40000.0
WORST_MONTH = -13000.0
N_PRIOR = 4686           # entry files in research/experiments/entries at the branch base (origin/main e4e4f1d24)
M_TOTAL = N_PRIOR + N_TESTS   # 5,712


def month_of(d: date) -> str:
    return f"{d.year:04d}-{d.month:02d}"
