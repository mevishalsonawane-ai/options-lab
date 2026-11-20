"""R204 / R205 settings (PREREG.md): an earlier breakeven, an earlier time stop, Liquidity without the MIDCPNIFTY 5-minute
book (R204, 10 tests on top of today's rules V5), and the arms replayed through one pot of money (R205, descriptive).
Data, stores, windows and the gate's numbers are R202's (r202cfg). Plain-English notes, 20 Nov 2026.
"""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
R202 = HERE.parent / "r202_trend_exit"
for p in (str(R202), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)
import r202cfg as K2  # noqa: E402

RESULTS = HERE / "results"
HOLDOUT_FLAG = RESULTS / "HOLDOUT_READ"
OUT = K2.OUT

ARMS = K2.ARMS
BE_AT = 500.0                              # BE500: breakeven once the best open P&L is +Rs 500 a lot net
ET_NEED = 250.0                            # ET: "never got going" = never up Rs 250 a lot net
ET_MIN = {"ET10": 10, "ET15": 15}
NOM5 = ("MIDCPNIFTY", 5)                   # the book NOM5 removes
EXIT_RULES = ("BE500", "ET10", "ET15")
RULES = EXIT_RULES + ("NOM5",)
TESTS = tuple((a, r) for r in EXIT_RULES for a in ARMS) + (("LIQ", "NOM5"),)       # 10
LABEL = {
    "B": "today's rule (V5), the baseline",
    "BE500": "breakeven once the trade is up Rs 500 a lot (net)",
    "ET10": "sold at 10 minutes if never up Rs 250 a lot (net)",
    "ET15": "sold at 15 minutes if never up Rs 250 a lot (net)",
    "NOM5": "Liquidity without the MIDCPNIFTY 5-minute book",
}

SEED = 204
N_PRIOR = 4869                             # entry files on the branch before R204
M_TOTAL = N_PRIOR + len(TESTS)             # 4,879

# R205: account sizes (None = no limit) and the same-minute order of the arms
ACCOUNTS = (56000.0, 100000.0, 150000.0, 200000.0, 300000.0, 500000.0, None)
ARM_ORDER = ("R72", "LIQ", "R51")
SETS = {"all three arms": ("R72", "LIQ", "R51"), "R72 + Liquidity (no R51)": ("R72", "LIQ")}
LIQ_FROM = K2.HOLDOUT[0]                   # "Liquidity from 1 April 2026"
