"""Tests for R53's global BH + gates step (aggregate.py) on small fake result files."""
import json

from research.hunt.r53 import aggregate as A
from research.hunt.r53 import common as C


def _s(n, net, top5=0.5):
    return {"trades": n, "net": net, "win_rate": 0.5, "avg_win": 1.0, "avg_loss": -1.0, "worst_day": -1.0,
            "max_dd": 1.0, "top5_share": top5, "net_per_trading_day": net / 100}


def _write(tmp, name, obj):
    (tmp / name).write_text(json.dumps(obj))


def test_gates_bh_and_labels(tmp_path, monkeypatch):
    monkeypatch.setattr(C, "RESULTS", tmp_path)
    monkeypatch.setattr(A, "R", tmp_path)
    _write(tmp_path, "mcx_design.json", {"rules": {"A": {"design_wf": {"orig_wf_net": 5.0, "retune_wf_net": 1.0},
                                                         "variants": {"orig": {"trades": 50, "net": 9.0, "p": 0.01}}}}})
    _write(tmp_path, "mcx_final.json", {"rules": {"A": {"design_wf": {"orig_wf_net": 5.0},
                                                        "variants": {"orig": {**_s(60, 100.0), "p": 0.002}}}}})
    _write(tmp_path, "mcx_design_ma5u.json", {"rules": {}})
    _write(tmp_path, "mcx_final_ma5u.json", {"rules": {"MA5u": {"design_wf": {"orig_wf_net": 5.0},
                                                                "variants": {"orig": {**_s(60, 100.0), "p": 0.002}}}}})
    fin = {"rules": {"CC1": {"original": "x", "name": "n", "variants": {"x": {
        "p": 0.001, "tds_locked": 1.0,
        "delta_pre_tax": {"net": 50.0}, "delta_after_tax": {**_s(80, -10.0), "net_per_period_day": -0.1},
        "coinswitch_gst_pre_tax": {"net": 1.0}, "coinswitch_gst_after_tax": {"net": -1.0},
        "coinswitch_nogst_pre_tax": {"net": 1.0}, "coinswitch_nogst_after_tax": {"net": -1.0}}}}}}
    des = {"rules": {"CC1": {"walk_forward_delta_after_tax": {"orig_wf_net": 3.0},
                             "variants": {"x": {"trades": 9, "p": 0.5, "delta_pre_tax": {"net": 1.0},
                                                "delta_after_tax": {"net": 0.5}}}}}}
    _write(tmp_path, "crypto_final.json", fin)
    _write(tmp_path, "crypto_design.json", des)
    A.main()
    rows = {f"{r['book']}:{r['rule']}": r for r in json.loads((tmp_path / "r53_gates.json").read_text())["rows"]}
    assert rows["mcx:A"]["verdict"] == "PASS"
    assert rows["mcx:MA5u"]["verdict"].startswith("pass on numbers, but read after the lock")
    # crypto gates read the AFTER-TAX net: positive before tax is not enough
    assert rows["crypto:CC1"]["verdict"] == "fail" and not rows["crypto:CC1"]["gates"]["net_pos"]
    assert abs(rows["mcx:A"]["q"] - 0.002) < 1e-12        # BH over the three p-values together (ranks 2-3 of 3)
    assert "### crypto" in (tmp_path / "r53_tables.md").read_text()
