"""R122 tests: point-in-time inputs, the rules, the walk-forward never trains on its own month, twins and the normal p,
and the step-3 gates. Synthetic data only. 28 Oct 2026."""
from __future__ import annotations

import math
import sys
from datetime import date, datetime, timedelta
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r122feat as F  # noqa: E402
import r122lib as L  # noqa: E402


def day(d, base=100.0, step=0.0, n=375):
    m = np.arange(555, 555 + n)
    c = base + step * np.arange(n)
    o = np.concatenate([[base], c[:-1]])
    return dict(minute=m, open=o, high=np.maximum(o, c) + 1, low=np.minimum(o, c) - 1, close=c)


def test_daily_and_prior_values_use_only_earlier_sessions():
    ds = [date(2025, 1, 1) + timedelta(days=i) for i in range(25)]
    days = {d: day(d, base=100 + i) for i, d in enumerate(ds)}
    dt = F.daily_table(days)
    p = F.prior_daily(dt, ds[22])
    assert p["prev_close"] == dt[ds[21]]["close"] and p["n20"] == 20
    days2 = dict(days)
    days2[ds[22]] = day(ds[22], base=999)                 # the day itself changes: its prior values do not
    days2[ds[23]] = day(ds[23], base=5)
    assert F.prior_daily(F.daily_table(days2), ds[22]) == p
    assert F.prior_daily(dt, ds[0]) == {}


def test_nr7_and_inside():
    ds = [date(2025, 1, 1) + timedelta(days=i) for i in range(9)]
    dt = {d: dict(open=100, high=110 - i, low=90 + i, close=100) for i, d in enumerate(ds)}
    p = F.prior_daily(dt, ds[8] + timedelta(days=1))
    assert p["nr7"] == 1.0 and p["inside"] == 1.0


def test_last_closed_5m_bar_is_before_the_entry():
    ds = [date(2025, 1, 1), date(2025, 1, 2)]
    keys, o, h, l, c = F.fold5({d: day(d) for d in ds})
    i = F.last_closed_5m(keys, ds[1], 600)                # 10:00: the 09:55 bar (its last minute 09:59) has closed
    assert keys[i] == (ds[1], 595)
    assert keys[F.last_closed_5m(keys, ds[1], 604)] == (ds[1], 595)   # 10:04: the 10:00 bar is still open
    assert keys[F.last_closed_5m(keys, ds[1], 605)] == (ds[1], 600)
    assert keys[F.last_closed_5m(keys, ds[1], 555)] == (ds[0], 925)   # first minute: yesterday's last bar


def test_counts_before_is_strict():
    e = [(600, 1), (630, -1), (659, 1), (660, 1), (540, -1)]
    assert F.counts_before(e, 660, 1) == (2, 1)
    assert F.counts_before(e, 660, -1) == (1, 2)


def test_supertrend_and_ema_shapes():
    c = np.concatenate([np.linspace(100, 200, 80), np.linspace(200, 120, 80)])
    d = F.supertrend_dir(c + 1, c - 1, c)
    assert d[70] == 1 and d[-1] == -1
    e = F.ema(np.ones(10), 3)
    assert np.allclose(e, 1.0)


def _t(arm, d, net, **f):
    base = {k: 0.0 for k in F.FEATURES}
    base.update(dict(setup_n=0.0, vix_prev=14.0))
    base.update(f)
    base["side"] = 1.0
    return dict(arm=arm, und="NIFTY", day=d, entry_ts=datetime.combine(d, datetime.min.time()), net=net, **base)


def test_rules_skip_what_they_say():
    d = date(2025, 5, 2)
    tr = [_t("R51", d, 1, trend20=-1), _t("R51", d, 1, trend20=1), _t("R51", d, 1, setup_n=12, setup_r=-0.1),
          _t("R51", d, 1, setup_n=5, setup_r=-0.5), _t("R51", d, 1, oppose60=1), _t("R51", d, 1, move_open=1.2)]
    assert L.rule_mask("R1_TREND", [], tr)[:2] == [False, True]
    assert L.rule_mask("R9_TM_SETUP", [], tr)[2:4] == [False, True]
    assert L.rule_mask("R5_OPPOSED", [], tr)[4] is False
    assert L.rule_mask("R8_CHASE", [], tr)[5] is False
    train = [_t("R51", date(2025, 1, 1) + timedelta(days=i), 1, vix_prev=float(10 + i)) for i in range(30)]
    m = L.rule_mask("R3_VIX_HIGH", train, [_t("R51", d, 1, vix_prev=38.0), _t("R51", d, 1, vix_prev=12.0)])
    assert m == [False, True]
    with pytest.raises(KeyError):
        L.rule_mask("NOPE", [], tr)


def test_walk_forward_never_trains_on_its_own_month(monkeypatch):
    seen = []

    def fake(spec, train, test):
        assert all(t["day"] < min(x["day"] for x in test) for t in train)
        assert not {id(t) for t in train} & {id(t) for t in test}
        seen.append(len(test))
        return [True] * len(test)
    monkeypatch.setattr(L, "variant_mask", fake)
    design = [_t("R51", date(2024, 12, 1) + timedelta(days=3 * i), 1) for i in range(160)]
    wf, keep = L.walk_forward({}, design)
    assert wf and all(L.WF_MONTHS[0] <= t["day"] <= date(2026, 3, 31) for t in wf) and sum(seen) == len(wf)


def test_twin_stats_match_draws():
    rng = np.random.default_rng(1)
    x = rng.normal(10, 50, 300)
    mean, var = L.twin_stats(x, 60)
    dr = L.twin_draws(x, 60, n=20_000)
    assert abs(dr.mean() - mean) < 3 * math.sqrt(var) / math.sqrt(20_000) * 3
    assert abs(dr.std() / math.sqrt(var) - 1) < 0.03
    assert L.twin_stats(x, 0) == (pytest.approx(x.sum()), 0.0)
    assert L.twin_stats(x, 300) == (0.0, 0.0)
    assert L.norm_p(0.0) == pytest.approx(0.5) and L.norm_p(6.0) < 1e-8


def test_evaluate_pools_without_the_duplicate():
    d = date(2025, 5, 2)
    tr = [_t("R51", d + timedelta(days=i), float(i - 5)) for i in range(10)] + \
         [_t("ORB_FRESH", d, -100.0), _t("ORB_ORB", d, -100.0)]
    keep = [t["net"] > 0 or t["arm"] != "R51" for t in tr]
    ev = L.evaluate(tr, keep)
    assert ev["pooled"]["trades"] == 11 and ev["arms"]["ORB_FRESH"]["trades"] == 1
    assert ev["arms"]["R51"]["skipped"] == 6 and ev["arms"]["R51"]["net_after"] == 10.0
    assert ev["pooled"]["z"] > 0


def test_learned_mask_skips_about_the_share():
    rng = np.random.default_rng(3)
    tr = []
    for i in range(400):
        d = date(2025, 1, 1) + timedelta(days=i % 200)
        x = float(rng.normal())
        tr.append(_t("R51", d, 1.0 if x + rng.normal() > 0 else -1.0, gap=x))
    train, test = tr[:300], tr[300:]
    for kind in ("L1", "L2", "L3"):
        keep = L.learned_mask(kind, train, test, 0.2)
        assert 0.05 < 1 - np.mean(keep) < 0.45, kind


def test_step3_gates():
    arm = lambda b, a, n=100, s=10: dict(net_before=b, net_after=a, trades=n, skipped=s)  # noqa: E731
    ok_arms = {"R72": arm(1, 2), "R51": arm(1, 2), "R52": arm(1, 0), "LIQ": arm(1, 0)}
    wf = dict(pooled=dict(net_before=1, net_after=2), arms=ok_arms)
    fin = dict(pooled=dict(net_before=1, net_after=5, twin_p95=3, p=0.001, dd_before=5, dd_after=4), arms=ok_arms)
    assert L.step3_gates(wf, fin, 0.05)["passed"]
    assert not L.step3_gates(wf, fin, 0.2)["passed"]
    bad = dict(fin, arms={"R72": arm(1, 2), "R51": arm(1, 0), "R52": arm(1, 0), "LIQ": arm(1, 0)})
    assert "net held on only 1" in " ".join(L.step3_gates(wf, bad, 0.05)["fails"])
    few = dict(fin, arms=dict(ok_arms, R72=arm(1, 2, n=45, s=10)))
    assert not L.step3_gates(wf, few, 0.05)["passed"]


# ---------------------------------------------------------------- the forward test's join (FORWARD_PREREG.md)

import forward as FW  # noqa: E402


def _diag(checks, trades):
    return dict(day="2026-11-03", arms=[dict(key="orb", trades=trades)], time_machine=checks)


def test_forward_join_prefers_the_checks_own_result_then_matches_trades():
    own = dict(at="10:21:00", arm="donchian", direction=1, account="paper", verdict="edge", pct=12.5, outcome="target")
    near = dict(at="10:35:00", arm="orb", direction=1, account="paper", verdict="against", pct=None)
    wrong_side = dict(at="10:35:00", arm="orb", direction=-1, account="paper", verdict="coin flip", pct=None)
    live = dict(at="11:00:00", arm="orb", direction=1, account="live", verdict="edge", pct=None)
    trades = [dict(account="paper", right="CE", entry_at="10:36:00", pct=-20.0, points=-30.0, why="stop", open=False),
              dict(account="paper", right="CE", entry_at="10:40:00", pct=5.0, points=8.0, why="target", open=False),
              dict(account="live", right="CE", entry_at="11:00:00", pct=None, points=None, why=None, open=True)]
    rows, c = FW.join(_diag([own, near, wrong_side, live], trades))
    assert c == dict(checks=4, matched=2, unmatched=1, live=1, open=0)
    assert rows[0]["trade"]["pct"] == 12.5
    assert rows[1]["trade"]["pct"] == -20.0          # the nearest trade within 2 minutes, used once
    assert rows[2]["trade"] is None                  # no PE trade: never guessed


def test_forward_join_on_an_old_file_has_nothing():
    rows, c = FW.join(dict(day="2026-10-20", arms=[]))
    assert rows == [] and c["checks"] == 0
