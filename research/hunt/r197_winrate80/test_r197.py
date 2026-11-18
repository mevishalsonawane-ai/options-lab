"""R197 tests: the spread loop reproduces R195's spread, targets fire on the next open, parsing, Wilson, the selection."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
for p in (HERE, HERE.parent / "r195_option_selling", HERE.parent / "r196_selling_plus"):
    sys.path.insert(0, str(p))
import r195engine as E  # noqa: E402
import r195rules as RU  # noqa: E402
import r196engine as E6  # noqa: E402
import r197engine as E7  # noqa: E402
import r197study as S7  # noqa: E402
from test_r195 import EXP, make_day  # noqa: E402


def test_spread_reproduces_r195_without_target():
    for crash in (False, True):
        D = make_day()
        if crash:
            D.con[("P", 21950.0)][:, 150:] = 300.0
        sp = RU.spec_spread(D, "NIFTY", RU.R0920, bull=True)
        a = E.run(D, "NIFTY", D.d, sp, EXP, False)
        b = E7.run_spread(D, "NIFTY", D.d, ("P", 21950.0), ("P", 21750.0), RU.R0920, EXP, False)
        assert b["net"] == pytest.approx(a["net"]) and b["exits"][0] == a["exits"][0]


def test_spread_target_fires_next_minute():
    D = make_day()
    D.con[("P", 21950.0)][:, 100:] = 60.0                                  # the short put decays
    t = E7.run_spread(D, "NIFTY", D.d, ("P", 21950.0), ("P", 21750.0), RU.R0920, EXP, False, target=0.30)
    assert t["why"] == "target" and t["exits"][0] == 101 and t["net"] > 0


def test_pair_target_and_off_by_default():
    D = make_day()
    for k in (("C", 22000.0), ("P", 22000.0)):
        D.con[k][:, 70:] = 75.0                                            # pair +25% of credit
    t = E6.run_short_pair(D, "NIFTY", D.d, [("C", 22000.0), ("P", 22000.0)], RU.R0920, EXP, False, leg_stop=1.25,
                          target=0.20)
    assert t["why"] == "target" and t["exits"] == [71, 71]
    t0 = E6.run_short_pair(D, "NIFTY", D.d, [("C", 22000.0), ("P", 22000.0)], RU.R0920, EXP, False, leg_stop=1.25)
    assert t0["why"] == "time"


def test_parse_and_wilson():
    c = S7.parse("VIX:G1-o2-s50-t35")
    assert (c["flt"], c["und"], c["o"], c["s"], c["t"]) == ("VIX", "NIFTY", 2, 50, 35)
    c = S7.parse("BANKNIFTY:G2-o4-tN")
    assert (c["und"], c["o"], c["t"]) == ("BANKNIFTY", 4, None)
    lo, hi = S7.wilson(80, 100)
    assert lo == pytest.approx(0.711, abs=0.002) and hi == pytest.approx(0.867, abs=0.002)
    assert len(S7.GRID) == 45


def test_extensions_list():
    ext = S7.extensions({"Pick-N": "G1-o0-s50-t20", "Pick-A": None})
    assert ext[0] == "G1-o0-s50-t20" and "VIX:G1-o0-s50-t20" in ext and "SENSEX:G1-o0-s50-t20" in ext
    assert len(ext) == 1 + 3 + 4
