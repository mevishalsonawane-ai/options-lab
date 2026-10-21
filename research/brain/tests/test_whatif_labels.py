"""R24 what-if LABELS (research/brain/whatif_labels.py): they are the future, so they must never enter a feature frame.

(a) every what-if column is a y_ label; (b) no feature module imports or reads them; (c) they meet features only in
split.join_forward, keyed by the decision minute, and a name clash either way is refused; (d) compact() picks the right
option for each column.
"""
import ast
from pathlib import Path

import pandas as pd
import pytest

from research.brain import labels, split, whatif_labels
from research.brain.features import REGISTRY, compute, load_all

BRAIN = Path(__file__).resolve().parents[1]


def _whatif(ts):
    """A synthetic what-if table: every decision minute in ts, CE/PE, offsets -3..3, known values."""
    rows = []
    for i, t in enumerate(ts):
        for s in (1, -1):
            for o in range(-3, 4):
                m = -o if s > 0 else o
                rows.append(dict(ts_decision=t, side=s, offset=o, mny=m, filled=not (i == 0 and o == 3 and s == 1),
                                 net_pct_30=10 * s + m + i, net_pct_1505=100 * s + m, mfe_pct=50.0 + m,
                                 mae_pct=-20.0 + m, orb_net=1000.0 * s + m, net_rs_1505=(100 * s + m) * 10))
    return pd.DataFrame(rows)


def test_columns_are_labels():
    cols = whatif_labels.columns()
    assert len(cols) == 2 * 3 * 5 + 2
    assert all(c.startswith(labels.PREFIX) for c in cols)
    assert not any(c in REGISTRY for c in cols)


def test_no_feature_module_reads_whatif():
    for p in (BRAIN / "features").glob("*.py"):
        src = p.read_text()
        assert "whatif" not in src.lower(), p.name
        for node in ast.walk(ast.parse(src)):
            if isinstance(node, (ast.Import, ast.ImportFrom)):
                names = [a.name for a in node.names] + [getattr(node, "module", "") or ""]
                assert not any("whatif" in n for n in names), p.name


def test_compact_values():
    ts = pd.date_range("2025-03-24 09:19", periods=3, freq="min")
    out = whatif_labels.compact(_whatif(ts))
    assert list(out.columns) == ["ts"] + whatif_labels.columns()
    assert len(out) == 3
    r = out.iloc[1]
    assert r.y_wi_ce_atm_net30 == 10 + 0 + 1
    assert r.y_wi_pe_itm1_net1505 == -100 + 1
    assert r.y_wi_ce_otm1_mfe == 49.0
    assert r.y_wi_pe_otm1_orb_rs == -1000.0 - 1
    assert r.y_wi_best_net1505 == 103.0                     # CE ITM3
    assert r.y_wi_win_share1505 == pytest.approx(0.5)       # 7 calls gain, 7 puts lose
    # an unfilled option is left out, never counted as zero
    assert out.iloc[0].y_wi_win_share1505 == pytest.approx(6 / 13)


def test_join_only_through_split(built):
    load_all()
    F = compute(built["M"], built["C"], "BANKNIFTY")
    ts = F.ts.iloc[[4, 5, 6]]
    L = whatif_labels.compact(_whatif(ts.values))
    J = split.join_forward(F, L)
    assert len(J) == 3 and set(whatif_labels.columns()) <= set(J.columns)
    assert not any(c.startswith(labels.PREFIX) for c in F.columns)
    with pytest.raises(ValueError):
        split.join_forward(F.assign(y_wi_ce_atm_net30=1.0), L)
    with pytest.raises(ValueError):
        split.join_forward(F, L.assign(atm_iv=1.0))


def test_empty_table():
    t = _whatif(pd.date_range("2025-03-24 09:19", periods=1, freq="min")).assign(filled=False)
    out = whatif_labels.compact(t)
    assert out.empty and list(out.columns) == ["ts"] + whatif_labels.columns()
