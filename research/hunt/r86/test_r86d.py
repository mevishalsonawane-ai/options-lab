"""R86d tests: D3's rule, the shared feature columns, the final-read guard, the put pricing (same strike)."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r86 import r86d as R


def test_d3_rule():
    x = pd.DataFrame({"gap": [-0.006, -0.006, -0.004, 0.01], "mv": [-0.001, 0.002, -0.003, -0.01]})
    assert R.d3_rule(x).tolist() == [True, False, False, False]


def test_columns_drop_p10_and_have_15_features():
    assert "P10" not in R.COLS and len(R.COLS) == 15
    assert set(R.GATED) == {"D1", "D2", "D3", "D4"} and "D0" not in R.GATED


def test_final_refuses_a_second_read(tmp_path, monkeypatch):
    monkeypatch.setattr(R, "OUT", tmp_path)
    (tmp_path / "FINAL_D_READ").write_text("read")
    with pytest.raises(SystemExit, match="already read once"):
        R.final()


def test_final_needs_the_design_check(tmp_path, monkeypatch):
    monkeypatch.setattr(R, "OUT", tmp_path)
    (tmp_path / "d_design.json").write_text('{"_D0_reproduces_C6": false}')
    with pytest.raises(SystemExit, match="does not reproduce"):
        R.final()
    assert not (tmp_path / "FINAL_D_READ").exists()       # a refused run must not burn the one read


class _Bk:
    mod = np.array([585, 600, 700])


def test_put_pnl_same_strike():
    rows = []
    for mod, spot_k in ((584, 50000.0), (585, 50000.0), (600, 49900.0), (700, 49800.0)):
        for k in (49800.0, 49900.0, 50000.0):
            px = {585: 300.0, 600: 350.0, 700: 400.0}.get(mod, 290.0) if k == 50000.0 else 100.0
            rows.append(dict(mod=mod, offset=int((k - spot_k) / 100), side="P", strike=k, open=px, close=px))
    opts = {pd.Timestamp("2025-03-03"): pd.DataFrame(rows)}
    tr = pd.DataFrame({"day": [pd.Timestamp("2025-03-03")], "xi": [2]})
    s = R.put_pnl(opts, _Bk(), tr)
    lot = 30                                              # BANKNIFTY lot in Mar 2025
    assert s["n"] == 1 and s["net"] == pytest.approx((400 * 0.9995 - 0.05 - (300 * 1.0005 + 0.05)) * lot, abs=60)
