"""R94 orchestrator tests: one BH family over every idea's cells, the trade-count exceptions, the design gates."""
from __future__ import annotations

import json

import pytest

from research.hunt.r94_mcx_advanced import common as C
from research.hunt.r94_mcx_advanced import study as St


def _cell(trades, net, p, worst_month=-1000.0, wo5=1.0, folds=(1.0, 1.0, -1.0)):
    return {"summary": {"trades": trades, "net": net, "worst_month": worst_month, "net_wo_top5": wo5},
            "p": p, "folds": list(folds), "extra": {}}


def test_min_trades_exceptions():
    assert St.min_trades("s-DW-POST-GOLD") == 10
    assert St.min_trades("o-IC-CRUDEOIL-CY-ALL") == 10
    assert St.min_trades("o-IC-CRUDEOIL-D1-ALL") == 40
    assert St.min_trades("c-Z1-GOLDM-2611-2612") == 40


def test_design_bh_over_all_cells_and_gates(tmp_path, monkeypatch):
    monkeypatch.setattr(C, "RESULTS", tmp_path)
    files = {
        "idea1": {"c-a": _cell(50, 100.0, 0.001), "c-zero": _cell(0, 0.0, None)},
        "idea2": {"p-a": _cell(60, -5.0, 0.002)},
        "idea3": {"o-x-CY-ALL": _cell(12, 50.0, 0.004), "o-y-D1-ALL": _cell(30, 50.0, 0.001)},
        "idea4": {"s-a": _cell(10, 10.0, 0.9)},
        "idea5": {"g-a": _cell(80, 10.0, 0.01, worst_month=-20000.0)},
    }
    for k, v in files.items():
        (tmp_path / f"design_{k}.json").write_text(json.dumps(v))
    d = St.design()
    assert len(d) == 7
    assert d["c-zero"]["p_used"] == 1.0                      # a cell with no trade counts with p = 1
    assert d["c-a"]["q"] == pytest.approx(0.001 * 7 / 2)     # ranks over all 7 cells of all ideas
    assert d["c-a"]["gate"]["pass"]
    assert not d["p-a"]["gate"]["pass"] and not d["p-a"]["gate"]["net"]
    assert d["o-x-CY-ALL"]["gate"]["n"]                      # 12 trades are enough for a cycle cell ...
    assert not d["o-x-CY-ALL"]["gate"]["pass"]               # ... but option selling is out of scope (NOTES N2)
    assert d["o-x-CY-ALL"]["gate"]["out_of_scope"]
    assert not d["o-y-D1-ALL"]["gate"]["n"]                  # 30 are not for a D1 cell
    assert not d["g-a"]["gate"]["month_stop"]
    saved = json.loads((tmp_path / "design_all.json").read_text())
    assert set(saved["passed"]) == {"c-a"}
    assert saved["n_cells"] == 7                             # out-of-scope cells still count in the BH family


def test_design_refuses_missing_idea(tmp_path, monkeypatch):
    monkeypatch.setattr(C, "RESULTS", tmp_path)
    with pytest.raises(FileNotFoundError):
        St.design()
