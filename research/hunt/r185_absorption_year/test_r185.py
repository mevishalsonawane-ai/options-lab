"""R185 tests (PREREG section 7): synthetic data only; no network, no token, no recordings.

Look-ahead guards: BVC's sigma / D at bar i, every signal at bar r, and the exits read nothing they should not.
The fetcher is tested with a fake `post`. Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import datetime as dt
import json
import math
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r185 as RN  # noqa: E402
import r185fetch as F  # noqa: E402
import r185lib as L  # noqa: E402
import r185load as LD  # noqa: E402
import r185validate as VA  # noqa: E402

N = L.N


# ------------------------------------------------------------------------------------------------ helpers
def walk(seed=1, n=N, start=24000.0, vol=6.0):
    rng = np.random.default_rng(seed)
    c = start + np.cumsum(rng.normal(0, vol, n))
    o = np.concatenate([[start], c[:-1]])
    h = np.maximum(o, c) + rng.uniform(0, 3, n)
    l = np.minimum(o, c) - rng.uniform(0, 3, n)
    V = rng.uniform(5e4, 2e5, n)
    return o, h, l, c, V


def flat_contract(px=100.0, vol=1e4):
    a = np.full(N, px, dtype=np.float32)
    return (a.copy(), a.copy(), a.copy(), a.copy(), np.full(N, vol, dtype=np.float32))


def make_day(c=None, step=50.0, expiry=False, opt=None, d=date(2025, 6, 2)):
    o, h, l, cc, V = walk() if c is None else c
    if opt is None:
        opt = {"C": {}, "P": {}}
    return L.Day(day=d, und="NIFTY", o=o, h=h, l=l, c=cc, V=V, step=step, lot=75, expiry=expiry, opt=opt)


# ------------------------------------------------------------------------------------------------ BVC
def test_bvc_sign_and_scale():
    c = np.array([100.0] + [100.0 + (i % 2) for i in range(1, 30)])
    V = np.full(30, 1000.0)
    D, sig, thin = L.bvc(c, V)
    assert np.all(thin[:L.SIGMA_MIN + 1])                     # sigma needs 10 earlier changes
    i = 20
    dP = c[i] - c[i - 1]
    s = np.std(np.diff(c[:i]), ddof=1)
    assert sig[i] == pytest.approx(s)
    assert D[i] == pytest.approx(1000 * (2 * 0.5 * (1 + math.erf(dP / s / math.sqrt(2))) - 1))
    assert abs(D[i]) <= 1000


def test_bvc_thin_on_gaps_and_zero_volume():
    o, h, l, c, V = walk()
    c = c.copy()
    V = V.copy()
    c[100] = np.nan
    V[200] = 0
    D, _, thin = L.bvc(c, V)
    assert thin[100] and thin[101] and thin[200]              # no close / no dP / no activity
    assert not thin[150]


def test_bvc_look_ahead_guard():
    o, h, l, c, V = walk(3)
    D, s, _ = L.bvc(c, V)
    rng = np.random.default_rng(9)
    for i in (30, 120, 300):
        c2, V2 = c.copy(), V.copy()
        c2[i + 1:] = rng.normal(24000, 500, N - i - 1)
        V2[i + 1:] = rng.uniform(0, 1e7, N - i - 1)
        D2, s2, _ = L.bvc(c2, V2)
        np.testing.assert_array_equal(D[:i + 1], D2[:i + 1])
        np.testing.assert_array_equal(s[:i + 1], s2[:i + 1])


@pytest.mark.parametrize("v", [L.GRID["ABS"][0], L.GRID["ABS"][3], L.GRID["EXH"][1], L.GRID["EXH"][6]])
def test_signal_look_ahead_guard(v):
    """Every signal at bar r is unchanged when the bars after r (r + 1 for the confirm bar) are replaced by noise."""
    rng = np.random.default_rng(5)
    found = 0
    for seed in range(12):
        o, h, l, c, V = walk(seed, vol=8.0)
        V = V * np.where(rng.uniform(size=N) < 0.08, 6.0, 1.0)   # some heavy minutes so signals exist
        B = L.bars_of(o, h, l, c, V)
        sigs = L.signals(B, v)
        for s in sigs:
            found += 1
            cut = s.r + 1
            o2, h2, l2, c2, V2 = (x.copy() for x in (o, h, l, c, V))
            noise = rng.normal(24000, 300, N - cut)
            c2[cut:], o2[cut:] = noise, noise
            h2[cut:], l2[cut:] = noise + 5, noise - 5
            V2[cut:] = rng.uniform(0, 1e7, N - cut)
            s2 = [x for x in L.signals(L.bars_of(o2, h2, l2, c2, V2), v) if x.r == s.r]
            assert s2 and s2[0].side == s.side and s2[0].stop == pytest.approx(s.stop)
    assert found > 0


# ------------------------------------------------------------------------------------------------ the trade
def day_with_path(path_o, path_h, path_l, path_c, r=100, side="L", expiry=False):
    o, h, l, c, V = walk()
    c = c.copy()
    c[r] = 24010.0                                            # ATM 24,000
    K = 24000.0
    co, ch, cl, cc, cv = flat_contract()
    n = len(path_o)
    for k in range(n):
        j = r + 1 + k
        co[j], ch[j], cl[j], cc[j] = path_o[k], path_h[k], path_l[k], path_c[k]
    for j in range(r + 1 + n, N):
        co[j] = ch[j] = cl[j] = cc[j] = path_c[-1]
    opt = {"C": {K: (co, ch, cl, cc, cv)}, "P": {K: (co, ch, cl, cc, cv)}}
    return make_day((o, h, l, c, V), opt=opt, expiry=expiry)


def test_premium_stop_and_entry_open():
    D = day_with_path([100, 99, 95], [101, 100, 96], [99, 98, 80], [100, 98, 82])
    t, why = L.trade(D, 100, "L", -1e9, 30)
    assert why is None and t["e"] == 101 and t["raw_e"] == 100
    assert t["why"] == "prem_stop" and t["x"] == 103
    assert t["raw_x"] == pytest.approx(0.85 * t["fill"], abs=0.01)
    assert t["net"] < 0


def test_gap_below_stop_sells_at_open():
    D = day_with_path([100, 70], [101, 72], [99, 60], [100, 65])
    t, _ = L.trade(D, 100, "L", -1e9, 30)
    assert t["why"] == "prem_stop" and t["raw_x"] == 70


def test_breakeven_lock_from_next_minute():
    # minute 1 reaches +12% and dips to 99 in the same minute: no breakeven exit yet; minute 2 dips -> breakeven
    D = day_with_path([100, 105, 108], [101, 113, 109], [99, 99, 100], [100, 110, 101])
    t, _ = L.trade(D, 100, "L", -1e9, 30)
    assert t["why"] == "breakeven" and t["x"] == 103
    assert t["raw_x"] > t["fill"]                               # fill + charges per unit


def test_index_stop_uses_index_low_and_option_close():
    D = day_with_path([100, 101, 102], [101, 102, 103], [99, 100, 101], [100, 101, 97])
    D.l = D.l.copy()
    D.l[103] = 23900.0
    D.l[:103] = np.minimum(D.l[:103], 24100)
    D.l[101:103] = 24000.0
    t, _ = L.trade(D, 100, "L", 23950.0, 30)
    assert t["why"] == "index_stop" and t["x"] == 103 and t["raw_x"] == 97


def test_time_stop_and_expiry_day_end():
    D = day_with_path([100] * 5, [101] * 5, [99] * 5, [100] * 5)
    t, _ = L.trade(D, 100, "L", -1e9, 15)
    assert t["why"] == "time" and t["x"] == 115
    D2 = day_with_path([100] * 5, [101] * 5, [99] * 5, [100] * 5, r=340, expiry=True)
    t2, _ = L.trade(D2, 340, "L", -1e9, 30)
    assert t2["why"] == "day_end" and t2["x"] == L.END_EXPIRY


def test_same_minute_takes_lower_price():
    # the premium stop (85) and the index stop (option close 90) in the same minute -> the lower price
    D = day_with_path([100, 95], [101, 96], [99, 80], [100, 90])
    D.l = D.l.copy()
    D.l[101] = 24100.0
    D.l[102] = 23000.0
    t, _ = L.trade(D, 100, "L", 23950.0, 30)
    assert t["x"] == 102 and t["why"] == "prem_stop"


def test_exits_never_read_before_entry():
    """Changing everything at or before the signal minute (except the strike's close at r) changes no exit."""
    D = day_with_path([100, 104, 103, 99], [101, 106, 104, 100], [99, 102, 101, 84], [100, 104, 102, 85])
    t, _ = L.trade(D, 100, "L", -1e9, 30)
    co, ch, cl, cc, cv = D.opt["C"][24000.0]
    cl[:101] = 1.0
    ch[:101] = 1e6
    t2, _ = L.trade(D, 100, "L", -1e9, 30)
    assert (t["x"], t["why"], t["raw_x"]) == (t2["x"], t2["why"], t2["raw_x"])


def test_unpriced_reasons():
    D = make_day()
    D.c = D.c.copy()
    D.c[50] = np.nan
    assert L.trade(D, 50, "L", 0, 15) == (None, "no_spot")
    assert L.trade(D, 60, "L", 0, 15)[1] == "no_strike"
    K = L.atm(float(D.c[70]), 50)
    co, ch, cl, cc, cv = flat_contract()
    co[71:75] = np.nan
    D.opt["C"][K] = (co, ch, cl, cc, cv)
    assert L.trade(D, 70, "L", 0, 15)[1] == "no_price"
    co2 = co.copy()
    co2[73] = 100
    D.opt["C"][K] = (co2, ch, cl, cc, cv)
    t, _ = L.trade(D, 70, "L", 0, 15)
    assert t["e"] == 73                                       # late entry inside r + 3


def test_take_day_one_at_a_time_and_cap():
    D = day_with_path([100] * 40, [101] * 40, [99] * 40, [100] * 40)
    Sig = L.R4.Sig
    sigs = [Sig(100, "L", -1e9, 1.0), Sig(105, "L", -1e9, 1.0), Sig(130, "L", -1e9, 1.0)]
    tr, up = L.take_day(sigs, D, 15)
    assert [t["r"] for t in tr] == [100]                      # 105 inside the first; 130 has no strike (ATM moves)
    assert up.get("no_strike", 0) + up.get("no_spot", 0) <= 1


def test_twin_uses_its_own_day():
    D1 = day_with_path([100, 80], [101, 81], [99, 79], [100, 80])
    D2 = day_with_path([100, 120], [101, 121], [99, 119], [100, 120])
    a = L.twin(D1, 100, "L", 1e6, 15)
    b = L.twin(D2, 100, "L", 1e6, 15)
    assert a < 0 < b


# ------------------------------------------------------------------------------------------------ statistics
def test_summary_month_and_drawdown():
    tr = [dict(day="2025-01-02", e=1, net=100, net2=90, side="L", why="time"),
          dict(day="2025-01-03", e=1, net=-300, net2=-310, side="S", why="prem_stop"),
          dict(day="2025-02-03", e=1, net=50, net2=40, side="L", why="time")]
    s = L.summary(tr)
    assert s["n"] == 3 and s["max_dd"] == 300 and s["worst_month"] == dict(month="2025-01", net=-200)
    assert L.summary([])["n"] == 0
    assert L.max_drawdown([-5, 10, -20]) == 20


def test_select_rules():
    def row(i, net, n1, net1, m1, t1, n2, net2, m2, t2):
        return dict(i=i, vid=f"v{i}", n=n1 + n2, net=net, H1=dict(n=n1, net=net1, mean=m1, twin_mean=t1),
                    H2=dict(n=n2, net=net2, mean=m2, twin_mean=t2))
    rows = [row(0, 900, 40, 500, 12, 1, 40, 400, 10, 2), row(1, 5000, 40, 6000, 150, 1, 40, -1000, -25, -50),
            row(2, 800, 40, 400, 10, 1, 40, 400, 10, 2)]
    ch = L.select(rows)
    assert ch["i"] == 0 and ch["passed_selection"]
    rows2 = [row(0, 900, 40, 500, 12, 20, 40, 400, 10, 2), row(1, 5000, 40, 6000, 150, 1, 40, -1000, -25, -50)]
    ch2 = L.select(rows2)
    assert ch2["i"] == 1 and not ch2["passed_selection"]
    ch3 = L.select([row(0, 9, 3, 5, 1, 0, 2, 4, 2, 0), row(1, 1, 10, 1, 0, 0, 1, 0, 0, 0)])
    assert ch3["i"] == 1 and not ch3["passed_selection"]


def test_bh_and_tests():
    q, qi = L.bh_with_prior([0.001, 0.5], 4852)
    assert q[0] == pytest.approx(min(1, 0.001 * 4852)) and q[1] == 1.0
    rng = np.random.default_rng(0)
    assert L.twin_test([1, 2], [[0], [0]], rng)["p"] == 1.0
    t = L.twin_test([100, 100, 100], [[0, 1], [0, -1], [2, 0]], rng)
    assert t["p"] == pytest.approx(1 / 1001)
    assert L.zero_test([10, 20, 30], rng) < 0.01 and L.zero_test([-1, -2], rng) == 1.0
    assert L.zero_test([5, 5], rng, n_trades=2) == 1.0
    assert RN.M_TOTAL == 4852


def test_gates_and_verdict():
    good = dict(net=1000, net2=500, mean=50, twin_mean=0, n=60, max_dd=5000, worst_month=dict(net=-2000))
    per = {p: dict(good) for p in L.GATE_PERIODS}
    assert L.gates(True, per, 0.05, 0.05)["passed"]
    per["six"] = dict(good, worst_month=dict(net=-14000))
    g = L.gates(True, per, 0.05, 0.05)
    assert not g["passed"] and "account stop" in g["fails"][-1]
    year = dict(n=50, mean=20, mean2=10)
    assert L.verdict(True, g, year, 0, 0.1) == "worth a forward test"
    assert L.verdict(False, g, year, 0, 0.1).startswith("no evidence (nothing")
    assert L.verdict(True, g, dict(n=10, mean=1, mean2=1), 0, 0.1) == "too few trades"


def test_lot_schedule_and_charges():
    assert L.lot_of("NIFTY", date(2025, 6, 1)) == 75 and L.lot_of("NIFTY", date(2026, 1, 5)) == 65
    assert L.lot_of("NIFTY", date(2024, 12, 2)) == 25 and L.schedule_lot_of("NIFTY", date(2024, 12, 2)) == 75
    assert L.lot_of("BANKNIFTY", date(2026, 10, 21)) == 30          # the phone's paper trades, 21 Oct 2026
    assert L.lot_of("SENSEX", date(2025, 1, 27)) == 10 and L.lot_of("SENSEX", date(2026, 5, 1)) == 20
    assert L.charge(False, 100, 75, date(2026, 5, 1)) > L.charge(False, 100, 75, date(2026, 3, 1))   # STT 0.1%
    assert L.charge(True, 0, 75, date(2026, 5, 1)) == 0.0


# ------------------------------------------------------------------------------------------------ periods / loader
def test_periods_split_and_selection_never_sees_final():
    ds = [d.isoformat() for d in pd.bdate_range("2024-11-20", "2026-11-13").date]
    P = RN.periods({d: None for d in ds})
    assert P["H1"][0] == "2024-11-20" and P["H1"][-1] <= "2025-04-30" < P["H2"][0]
    assert P["H2"][-1] <= "2025-10-19" < P["final"][0] == "2025-10-20"
    assert P["final"][-1] <= "2026-10-19" < P["recent"][0]
    assert not set(P["H1"] + P["H2"]) & set(P["final"] + P["recent"])


def test_final_refuses_without_flag(tmp_path):
    with pytest.raises(SystemExit):
        RN.stage_final("NIFTY", out=tmp_path)
    (tmp_path / "selection_NIFTY.json").write_text("{}")
    (tmp_path / "FINAL_READ_NIFTY").write_text("x")
    (tmp_path / "final_NIFTY.json").write_text("{}")
    with pytest.raises(SystemExit):
        RN.stage_final("NIFTY", out=tmp_path)                  # read once


def test_expiry_rule():
    d = [date(2025, 1, i) for i in (1, 2, 3, 6)]
    tv = {d[0]: 0.9, d[1]: 0.2, d[2]: 1.0, d[3]: float("nan")}
    assert LD.expiry_days(tv) == {d[1]}
    assert LD.expiry_days({d[0]: 0.1}) == set()                # last day: not an expiry


def test_step_of():
    o = pd.DataFrame(dict(offset=[0, 1, 0, 1], side=["C", "C", "P", "P"], m=[5, 5, 5, 5],
                          strike=[24000.0, 24050.0, 24000.0, 24050.0]))
    assert LD.step_of(o) == 50.0


# ------------------------------------------------------------------------------------------------ the fetcher (fake post)
def fake_answer(day: dt.date, side="C"):
    t0 = int(dt.datetime(day.year, day.month, day.day, 9, 15, tzinfo=dt.timezone(dt.timedelta(hours=5, minutes=30))).timestamp())
    nxt = day + dt.timedelta(days=1)
    t1 = int(dt.datetime(nxt.year, nxt.month, nxt.day, 9, 15, tzinfo=dt.timezone(dt.timedelta(hours=5, minutes=30))).timestamp())
    ts = [t0 - 60, t0, t0 + 60, t0 + 374 * 60, t0 + 375 * 60, t1]      # 09:14, 09:15, 09:16, 15:29, 15:30, next day
    arr = dict(timestamp=ts, open=[1.0] * 6, high=[2.0] * 6, low=[0.5] * 6, close=[1.5] * 6, volume=[10] * 6,
               oi=[5] * 6, iv=[12.0] * 6, spot=[24000.0] * 6, strike=[24000.0] * 6)
    return {"data": {"ce" if side == "C" else "pe": arr, "pe" if side == "C" else "ce": None}}


def test_option_body_is_one_day():
    b = F.option_body("SENSEX", dt.date(2026, 10, 21), -2, "P")
    assert b["fromDate"] == "2026-10-21" and b["toDate"] == "2026-10-22"
    assert b["strike"] == "ATM-2" and b["drvOptionType"] == "PUT" and b["exchangeSegment"] == "BSE_FNO"
    assert b["expiryCode"] == 1 and b["expiryFlag"] == "WEEK" and b["interval"] == "1"
    assert F.option_body("NIFTY", dt.date(2026, 10, 21), 0, "C")["strike"] == "ATM"
    assert "access-token" not in json.dumps(b)


def test_fetch_keeps_only_the_day_and_resumes(tmp_path):
    calls = []

    def post(path, body):
        calls.append(body)
        return 200, fake_answer(dt.date.fromisoformat(body["fromDate"]), "C" if body["drvOptionType"] == "CALL" else "P"), ""
    day = dt.date(2026, 10, 21)
    assert F.fetch_option_day(post, "NIFTY", day, 1, "C", root=tmp_path) == "ok"
    df = pd.read_parquet(F.chunk_path("NIFTY", day, 1, "C", tmp_path))
    assert len(df) == 3 and set(df.ts.dt.date) == {day}                 # 09:15, 09:16, 15:29
    assert F.fetch_option_day(post, "NIFTY", day, 1, "C", root=tmp_path) == "cached"
    assert len(calls) == 1


def test_fetch_errors_write_nothing_and_empty_marks(tmp_path):
    day = dt.date(2026, 10, 21)
    r = F.fetch_option_day(lambda p, b: (-1, None, "HTTP 429 DH-904"), "NIFTY", day, 0, "P", root=tmp_path)
    assert r.startswith("error") and not F.chunk_path("NIFTY", day, 0, "P", tmp_path).exists()
    r = F.fetch_option_day(lambda p, b: (200, {"data": {"ce": None, "pe": {}}}, ""), "NIFTY", day, 0, "P", root=tmp_path)
    assert r == "empty" and F.chunk_path("NIFTY", day, 0, "P", tmp_path).with_suffix(".empty").exists()


def test_fetch_run_and_build(tmp_path):
    days = [dt.date(2026, 10, 21), dt.date(2026, 10, 22)]

    def post(path, body):
        if path == "/charts/intraday":
            ts, c = [], []
            for d in days:
                t0 = int(dt.datetime(d.year, d.month, d.day, 9, 15, tzinfo=dt.timezone(dt.timedelta(hours=5, minutes=30))).timestamp())
                ts += [t0 + 60 * k for k in range(375)]
            n = len(ts)
            return 200, dict(timestamp=ts, open=[1.0] * n, high=[1.0] * n, low=[1.0] * n, close=[1.0] * n,
                             volume=[0] * n), ""
        return 200, fake_answer(dt.date.fromisoformat(body["fromDate"]), "C" if body["drvOptionType"] == "CALL" else "P"), ""
    r = F.run(post, "NIFTY", days[0], days[-1], threads=2, root=tmp_path, log=lambda *a: None)
    assert r["days"] == [str(d) for d in days] and r["ok"] == 2 * 21 * 2
    m = F.build("NIFTY", root=tmp_path, out=tmp_path / "lake")
    assert m["days"] == 2 and m["option_rows"] == 2 * 42 * 3 and m["index_rows"] == 750
    o = pd.read_parquet(tmp_path / "lake" / "opt_NIFTY_WEEK_2026_gap1113.parquet")
    assert str(o.ts.dtype) == "datetime64[ns, Asia/Kolkata]"
    assert list(o.columns) == ["offset", "side", "open", "high", "low", "close", "iv", "volume", "strike", "oi", "spot", "ts"]
    assert m["per_day"][0]["near_atm2_missing_minutes"] == 10 * 375 - 30
    assert "close" not in json.dumps(m["per_day"])                     # aggregates only, no prices


def test_fetch_index_failure_raises(tmp_path):
    with pytest.raises(RuntimeError):
        F.fetch_index(lambda p, b: (401, None, "HTTP 401"), "NIFTY", dt.date(2026, 10, 20), dt.date(2026, 10, 21), tmp_path)


# ------------------------------------------------------------------------------------------------ validation helpers
def test_validation_minute_last_and_compare():
    t = 1_700_000_040
    ph = pd.DataFrame(dict(epoch_sec=[t, t + 30, t + 59, t + 60], right="CE", strike=24000.0, last=[10, 11, np.nan, 12]))
    m = VA.minute_last(ph)
    assert list(m["last"]) == [11, 12]
    dh = pd.DataFrame(dict(right="CE", strike=24000.0, mstart=[t, t + 60], close=[11.0, 12.5]))
    r = VA.compare(m, dh)
    assert r["matched"] == 2 and r["median_abs"] == pytest.approx(0.25) and r["within_rs1"] == 1.0
    assert VA.compare(m, dh.iloc[:0])["matched"] == 0


def test_true_lots_match_the_bhavcopy_table():
    """Amendment A: TRUE_LOTS agrees with results/lot_sizes.json (NSE's bhavcopy) on every trading day it covers."""
    p = HERE / "results" / "lot_sizes.json"
    if not p.exists():
        pytest.skip("no lot table")
    T = json.loads(p.read_text())
    n = 0
    for und, v in T["indices"].items():
        for r in v["runs"]:
            if r["true"] is None:
                continue
            for d in pd.bdate_range(r["frm"], r["to"]).date:
                assert L.lot_of(und, d) == r["true"], (und, d)
                n += 1
    assert n > 1000


def test_lots_rule():
    import r185lots as LT
    files = {"2025-01-20": {"NIFTY": {"2025-01-23": 75, "2025-01-30": 25}},
             "2025-01-27": {"NIFTY": {"2025-01-30": 25, "2025-02-06": 75}}}
    assert LT.lot_on(files, "NIFTY", date(2025, 1, 21)) == 75
    assert LT.lot_on(files, "NIFTY", date(2025, 1, 24)) == 25
    assert LT.lot_on(files, "NIFTY", date(2025, 1, 31)) == 75
    assert LT.lot_on({}, "NIFTY", date(2025, 1, 31)) is None


def test_a_day_without_a_strike_step_is_not_traded():
    """16 Nov: a day whose strikes give no step (NaN) prices nothing instead of crashing (FINNIFTY final stage)."""
    D = make_day(step=float("nan"))
    t, why = L.trade(D, 100, "L", float(D.c[100]) - 20, 15)
    assert t is None and why == "no_strike"
    assert L.twin(D, 100, "L", 20.0, 15) is None
