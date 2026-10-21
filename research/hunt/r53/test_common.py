"""Tests for the R53 harness (common.py): split edges, final lock, metrics, twins, BH, gates, walk-forward, costs."""
from datetime import date

import numpy as np
import pandas as pd
import pytest

from research.hunt.r53 import common as C


def tr(d, net):
    return {"entry_ts": pd.Timestamp(d), "net": net}


def test_split_edges_and_nothing_before_20_nov_2024():
    assert C.part_of(date(2024, 11, 19)) is None
    assert C.part_of(date(2024, 11, 20)) == "design"
    assert C.part_of(date(2026, 3, 31)) == "design"
    assert C.part_of(date(2026, 4, 1)) == "final"
    assert C.part_of(date(2026, 10, 13)) == "final"
    assert C.part_of(date(2026, 10, 14)) is None
    # MCX minutes start on 5 Aug 2025
    assert C.part_of(date(2025, 8, 4), mcx=True) is None
    assert C.part_of(date(2025, 8, 5), mcx=True) == "design"
    assert C.part_of(pd.Timestamp("2026-04-01 00:05", tz="Asia/Kolkata")) == "final"


def test_final_lock_refuses_a_second_read(tmp_path, monkeypatch):
    monkeypatch.setattr(C, "RESULTS", tmp_path)
    C.open_final("x", ["a", "b"])
    with pytest.raises(C.FinalReadError):
        C.open_final("x", ["b"])
    C.open_final("x", ["c"])            # a new idea may still be read once


def test_summary_metrics():
    t = [tr("2025-01-02 10:00", 100), tr("2025-01-02 11:00", -50), tr("2025-01-03 10:00", -80), tr("2025-01-06", 200)]
    s = C.summary(t)
    assert s["trades"] == 4 and s["win_rate"] == 0.5 and s["net"] == 170
    assert s["avg_win"] == 150 and s["avg_loss"] == -65
    assert s["worst_day"] == -80
    assert s["max_dd"] == 130           # 100 -> -30
    assert s["days"] == 3 and abs(s["net_per_day"] - 170 / 3) < 1e-9
    assert abs(s["top5_share"] - 170 / 170) < 1e-9   # all four trades are the top 5
    assert C.summary([])["trades"] == 0


def test_top5_share_flags_a_net_made_by_few_trades():
    t = [tr(f"2025-02-{i + 1:02d}", -10) for i in range(20)] + [tr("2025-03-01", 500)]
    s = C.summary(t)
    assert s["top5_share"] > 1.0        # the five best (500 + 4 x -10) exceed the whole net (300)


def test_twin_p():
    assert C.twin_p(10, [0] * 500) == 1 / 501
    assert C.twin_p(-10, [0] * 500) == 1.0
    assert abs(C.twin_p(5, list(range(10))) - (1 + 5) / 11) < 1e-12


def test_bh_matches_textbook():
    p = {"a": 0.01, "b": 0.04, "c": 0.03, "d": 0.20}
    q = C.bh(p)
    assert q["a"] == pytest.approx(0.04)
    assert q["b"] == pytest.approx(0.04 * 4 / 3)
    assert q["c"] == pytest.approx(0.04 * 4 / 3)       # min(0.03 x 4 / 2, q of the next rank)
    assert q["d"] == pytest.approx(0.20)
    assert C.bh({"x": None}) == {}


def test_gates():
    good = {"trades": 50, "net": 100.0, "top5_share": 0.5}
    assert C.gates(good, 0.01, 0.05, 10.0)["verdict"] == "PASS"
    assert C.gates(good, 0.01, 0.05, -1.0)["verdict"] == "fail"
    assert C.gates(good, 0.06, 0.05, 10.0)["verdict"] == "fail"
    assert C.gates(good, 0.01, 0.11, 10.0)["verdict"] == "fail"
    assert C.gates({**good, "top5_share": 1.2}, 0.01, 0.05, 1.0)["verdict"] == "fail"
    assert C.gates({**good, "trades": 39}, 0.01, 0.05, 1.0)["verdict"] == "too few trades to judge"
    # crypto: gate on the after-tax net
    assert C.gates(good, 0.01, 0.05, 1.0, key_net=-5.0)["verdict"] == "fail"


def test_walk_forward_original_and_retune():
    v = {
        "orig": [tr("2025-01-10", 10), tr("2025-05-10", -5), tr("2025-08-10", 3), tr("2025-11-10", 1), tr("2026-02-10", 1)],
        "rt": [tr("2025-01-10", 20), tr("2025-05-10", 7), tr("2025-08-10", -2), tr("2025-11-10", 2), tr("2026-02-10", 2)],
    }
    w = C.walk_forward(v, "orig")
    assert w["orig_wf_net"] == -5 + 3 + 1 + 1
    assert w["retune_picks"] == ["rt", "rt", "rt", "rt"]
    assert w["retune_wf_net"] == 7 - 2 + 2 + 2
    assert w["design_pick"] == "rt"
    # a tie goes to the original; a losing best variant is no pick
    w2 = C.walk_forward({"orig": [tr("2025-01-10", -1)], "rt": [tr("2025-01-10", -1)]}, "orig")
    assert w2["retune_picks"][0] == "orig" and w2["design_pick"] is None


def test_walk_forward_ignores_trades_outside_design():
    v = {"orig": [tr("2024-11-01", 999), tr("2026-05-01", 999), tr("2025-05-01", 1)]}
    w = C.walk_forward(v, "orig")
    assert w["orig_wf_net"] == 1 and w["design_net"]["orig"] == 1


def test_mcx_charges_match_m3lib():
    from research.hunt.m3 import m3lib
    for b, s in [(10_000, 12_000), (500, 100), (2_00_000, 1_90_000)]:
        assert C.mcx_opt_charges(b, s) == pytest.approx(m3lib.opt_charges(b, s))
        assert C.mcx_fut_charges(b, s) == pytest.approx(float(m3lib.fut_charges(b, s)))


def test_option_half_spread_doubles_before_17():
    assert C.opt_half_spread("natgas", 19 * 60) == pytest.approx(0.00215)
    assert C.opt_half_spread("natgas", 10 * 60) == pytest.approx(0.0043)


def test_crypto_fees_and_tax():
    assert C.perp_fee("delta", 10_000) == pytest.approx(5.9)
    assert C.perp_fee("coinswitch_nogst", 10_000) == pytest.approx(5.0)
    # Delta option fee is capped at 3.5% of premium
    assert C.option_fee("delta", 10.0, 100_000) == pytest.approx(0.35 * 1.18)
    assert C.option_fee("delta", 1000.0, 100_000) == pytest.approx(10 * 1.18)
    assert C.option_fee("coinswitch_gst", 10.0, 100_000) == pytest.approx(15 * 1.18)
    x = C.after_tax([100, -50, 0])
    assert list(x) == [pytest.approx(68.8), -50, 0]
    assert C.tds_locked([1000, 2000]) == pytest.approx(30)
