"""R61 tests on synthetic data only (no lake needed):

    python -m pytest research/hunt/r61/test_r61.py -q

Covers: the look-ahead guard for every R61 chain and context feature, snapshot carry-forward (never mixing minutes),
max pain / walls / build-up / PCR / Greeks / GEX / gamma flip / synthetic basis on hand-checked chains, parity of the
numpy research code with the pure-Python reference (r61eval), signing by side, same-minute ranks that only look back,
the pre-listed event calendar, purge / embargo, held-contract labels left untouched, the period guard, the replace
rule, scorer parity with exported models, and edge cases: holidays, expiry day, missing chain minutes, no chain at all.
"""
from __future__ import annotations

import ast
import copy
import json
import math
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import r61cfg as K  # noqa: E402
import r51data  # noqa: E402
import r57data  # noqa: E402
import r57model as M57  # noqa: E402
import r57setups as S57  # noqa: E402
import r61chain as C  # noqa: E402
import r61data  # noqa: E402
import r61eval as EV  # noqa: E402
import r61events  # noqa: E402
import r61feat as F  # noqa: E402
import r61study as ST  # noqa: E402

N = K.NROWS
STEP = 50.0


# ------------------------------------------------------------------------------------------------ synthetic data
def index_path(rng, base=20000.0) -> np.ndarray:
    c = base + np.cumsum(rng.normal(0, 4, N))
    o = np.r_[base, c[:-1]]
    return np.vstack([o, np.maximum(o, c) + 1, np.minimum(o, c) - 1, c])


def chain_rows(rng, idx: np.ndarray, drop: float = 0.1, start: int = 0) -> pd.DataFrame:
    """Rolling chain rows (offsets -10..+10, both sides) for one day, with ~drop of the minutes missing."""
    rows = []
    oi_c = rng.uniform(1e5, 5e5, 41)
    oi_p = rng.uniform(1e5, 5e5, 41)
    for r in range(start, N):
        oi_c *= rng.uniform(0.99, 1.012, 41)
        oi_p *= rng.uniform(0.99, 1.012, 41)
        if rng.random() < drop:
            continue
        S = idx[3, r]
        atm = round(S / STEP) * STEP
        for o in range(-10, 11):
            k = atm + o * STEP
            j = int((k - 19000) / STEP) % 41
            for side, oi in (("C", oi_c[j]), ("P", oi_p[j])):
                iv = 12 + 0.02 * abs(o) ** 2 + (0.5 if side == "P" and o < 0 else 0)
                intrinsic = max(S - k, 0) if side == "C" else max(k - S, 0)
                rows.append(dict(r=r, offset=o, side=side, strike=k, spot=S, oi=oi, iv=iv,
                                 close=intrinsic + 40 * math.exp(-abs(S - k) / 300), volume=float(rng.integers(0, 500))))
    return pd.DataFrame(rows)


@pytest.fixture(scope="module")
def day1():
    rng = np.random.default_rng(7)
    idx = index_path(rng)
    g = chain_rows(rng, idx, start=2)
    return idx, g


# ------------------------------------------------------------------------------------------------ snapshot
def test_snapshot_carries_whole_slot_forward():
    g = pd.DataFrame([dict(r=1, offset=0, side="C", strike=100.0, oi=10.0, iv=12.0, close=5.0, volume=3.0),
                      dict(r=4, offset=0, side="C", strike=150.0, oi=20.0, iv=15.0, close=7.0, volume=1.0)])
    s = C.snapshot(g, nrows=6)
    st, oi = s["snap"]["strike"][0, 10], s["snap"]["oi"][0, 10]
    assert np.isnan(st[0]) and st[1:4].tolist() == [100, 100, 100] and st[4:].tolist() == [150, 150]
    assert oi[1:4].tolist() == [10, 10, 10] and oi[4:].tolist() == [20, 20]
    assert s["vol"][0, 10].tolist() == [0, 3, 0, 0, 1, 0]           # volume is never carried forward


def test_no_chain_at_all_is_all_missing():
    s = C.snapshot(pd.DataFrame(columns=["r", "offset", "side", "strike", "oi", "iv", "close", "volume"]))
    f = C.day_features(s, index_path(np.random.default_rng(1))[3], 2.0)
    assert all(np.isnan(v).all() for v in f.values())


# ------------------------------------------------------------------------------------------------ look-ahead guard
def test_chain_features_never_look_ahead(day1):
    idx, g = day1
    full = C.day_features(C.snapshot(g), idx[3], 3.0)
    for t in (2, 30, 100, 200, 374):
        cut = idx[3].copy()
        cut[t + 1:] = np.nan
        part = C.day_features(C.snapshot(g[g.r <= t]), cut, 3.0)
        for k in full:
            a, b = full[k][t], part[k][t]
            assert (np.isnan(a) and np.isnan(b)) or abs(a - b) < 1e-9, (k, t, a, b)


def test_rank_uses_only_previous_sessions():
    rng = np.random.default_rng(2)
    hist = [rng.normal(size=N) for _ in range(30)]
    today = rng.normal(size=N)
    r20 = C.same_row_rank(hist, today, 20, 15)
    manual = np.mean(np.vstack(hist[-20:]) < today[None], axis=0)
    assert np.allclose(r20, manual)
    assert np.isnan(C.same_row_rank(hist[:10], today, 20, 15)).all()        # fewer than min_n: missing
    mm = C.same_row_minmax(hist, today, 60, 20)
    H = np.vstack(hist)
    assert np.allclose(mm, (today - H.min(0)) / (H.max(0) - H.min(0)))


def _ctx_stores(n=70):
    rng = np.random.default_rng(3)
    ds, d = [], date(2025, 1, 1)
    while len(ds) < n:
        if d.weekday() < 5:
            ds.append(d)
        d += timedelta(days=1)
    stores, chains = {}, {}
    for u in ("NIFTY", "BANKNIFTY"):
        st, ch = {}, {}
        for i, dd in enumerate(ds):
            D = r51data.Day(dd, np.zeros((4, N)))
            D.atm = np.zeros(N)
            D.expiry = (u == "NIFTY" and i % 5 == 1)
            D.vix = rng.uniform(10, 20, N).astype(np.float32)
            st[dd] = D
            ch[dd] = dict(f=dict(pcr_oi=rng.uniform(0.6, 1.4, N), iv_atm=rng.uniform(10, 20, N)))
        stores[u], chains[u] = st, ch
    return ds, stores, chains


def test_context_never_looks_ahead_and_flags_other_expiry():
    ds, stores, chains = _ctx_stores()
    a = r61data.context(chains, stores)
    t = ds[50]
    ch2 = copy.deepcopy(chains)
    for d in ds[51:]:                                   # change every later session
        ch2["NIFTY"][d]["f"]["pcr_oi"][:] = 99.0
        ch2["NIFTY"][d]["f"]["iv_atm"][:] = 99.0
    b = r61data.context(ch2, stores)
    for k in ("pcr_rank5", "pcr_rank20", "iv_rank60", "vix_rank60"):
        assert np.allclose(a["NIFTY"][t][k], b["NIFTY"][t][k], equal_nan=True)
    assert np.isnan(a["NIFTY"][ds[3]]["pcr_rank20"]).all()                     # no history yet
    assert a["BANKNIFTY"][ds[1]]["other_expiry_today"] == 1.0                   # NIFTY expires that day
    assert a["NIFTY"][ds[1]]["other_expiry_today"] == 0.0                       # its own expiry is not "other"


# ------------------------------------------------------------------------------------------------ hand-checked chains
def test_max_pain_hand_example():
    # strikes 90, 100, 110. Call OI only at 90 (100), put OI only at 110 (100) -> pain(X): 90 -> 100*20 = 2000,
    # 100 -> 100*10 + 100*10 = 2000, 110 -> 100*20 = 2000: a tie, the lowest strike wins
    ks = np.array([90.0, 100.0, 110.0])
    strikes = np.full((2, 21, 1), np.nan)
    ois = np.full((2, 21, 1), np.nan)
    strikes[0, 9:12, 0] = ks
    strikes[1, 9:12, 0] = ks
    ois[0, 9:12, 0] = [100, 0, 0]
    ois[1, 9:12, 0] = [0, 0, 100]
    assert C.max_pain(strikes, ois)[0] == 90.0
    ois[0, 9:12, 0] = [0, 0, 500]                     # heavy call OI at 110 and put OI at 110: pain least at 110
    assert C.max_pain(strikes, ois)[0] == 110.0
    calls = [(k, o, 12, 1) for k, o in zip(ks, ois[0, 9:12, 0])]
    puts = [(k, o, 12, 1) for k, o in zip(ks, ois[1, 9:12, 0])]
    assert EV.max_pain(calls, puts) == 110.0


def test_walls_hand_example():
    S = np.array([100.0])
    strikes = np.full((2, 21, 1), np.nan)
    ois = np.full((2, 21, 1), np.nan)
    strikes[:, 8:13, 0] = [90, 95, 100, 105, 110]
    ois[0, 8:13, 0] = [999, 1, 5, 50, 50]             # biggest call OI above 100: 105 and 110 tie -> 105 (nearer)
    ois[1, 8:13, 0] = [7, 30, 2, 999, 1]              # biggest put OI at or below 100: 95
    cw, pw = C.walls(S, strikes, ois)
    assert cw[0] == pytest.approx(5.0) and pw[0] == pytest.approx(5.0)
    calls = [(k, o, 12, 1) for k, o in zip(strikes[0, 8:13, 0], ois[0, 8:13, 0])]
    puts = [(k, o, 12, 1) for k, o in zip(strikes[1, 8:13, 0], ois[1, 8:13, 0])]
    assert EV.walls(100.0, calls, puts) == (pytest.approx(5.0), pytest.approx(5.0))


def test_buildup_codes():
    dp = np.array([1, 1, -1, -1, 0, 1, np.nan])
    doi = np.array([1, -1, -1, 1, 1, 0, 1])
    assert C.buildup_code(dp, doi)[:6].tolist() == [2, 1, -1, -2, 0, 0] and np.isnan(C.buildup_code(dp, doi)[6])
    assert [EV.buildup_code(a, b) for a, b in zip(dp[:6], doi[:6])] == [2, 1, -1, -2, 0, 0]


def test_greeks_against_finite_differences_and_parity():
    S, Kx, iv, T = 20000.0, 20100.0, 14.0, 3 / 252
    for call in (True, False):
        g = C.bs(S, Kx, iv / 100, T, call)
        e = EV.greeks(S, Kx, iv, T, call)
        assert g["delta"] == pytest.approx(e[0], abs=1e-12) and g["gamma"] == pytest.approx(e[1], rel=1e-12)
        assert g["theta"] == pytest.approx(e[2], rel=1e-12) and g["vega"] == pytest.approx(e[3], rel=1e-12)
        h = 1.0
        d_up, d_dn = EV.greeks(S + h, Kx, iv, T, call)[0], EV.greeks(S - h, Kx, iv, T, call)[0]
        assert (d_up - d_dn) / (2 * h) == pytest.approx(e[1], rel=1e-4)
        assert e[1] > 0 and e[2] < 0 and e[3] > 0
    c, p = EV.greeks(S, Kx, iv, T, True), EV.greeks(S, Kx, iv, T, False)
    assert c[0] - p[0] == pytest.approx(1.0)
    assert EV.greeks(S, Kx, 0.0, T, True) is None and np.isnan(C.bs(S, Kx, 0.0, T, True)["gamma"])


def test_held_greeks_reads_the_held_strike(day1):
    idx, g = day1
    snap = C.snapshot(g)
    frow = 120
    k = float(snap["snap"]["strike"][0, 12, frow])                  # 2 strikes above ATM, the held call
    got = C.held_greeks(snap, "C", k, frow, float(C.ffill(idx[3])[frow]), 2.0)
    iv, prem = snap["snap"]["iv"][0, 12, frow], snap["snap"]["close"][0, 12, frow]
    ref = EV.held_greeks(float(C.ffill(idx[3])[frow]), k, float(iv), float(prem), EV.years_left(2.0, frow), True)
    for n in ref:
        assert got[n] == pytest.approx(ref[n], rel=1e-9)
    assert np.isnan(C.held_greeks(snap, "C", 1.0, frow, 20000.0, 2.0)["delta_abs"])   # strike not in the chain
    assert np.isnan(C.held_greeks(snap, "C", k, frow, 20000.0, np.nan)["delta_abs"])  # past the last known expiry


def _slots(snap, si, m):
    out = []
    for j in range(21):
        k = snap["strike"][si, j, m]
        if not np.isfinite(k):
            out.append(None)
            continue
        oi, iv, cl = (snap[f][si, j, m] for f in ("oi", "iv", "close"))
        out.append((float(k), None if not np.isfinite(oi) else float(oi), None if not np.isfinite(iv) else float(iv),
                    float(cl)))
    return out


def test_numpy_matches_pure_python_reference(day1):
    idx, g = day1
    snap = C.snapshot(g)
    s = snap["snap"]
    S = C.ffill(idx[3])
    T = C.years_left(2.0)
    f = C.day_features(snap, idx[3], 2.0)
    for m in (5, 77, 200, 360):
        calls, puts = _slots(s, 0, m), _slots(s, 1, m)
        assert f["maxpain_dist_pct"][m] == pytest.approx((EV.max_pain(calls, puts) - S[m]) / S[m] * 100)
        cw, pw = EV.walls(S[m], calls, puts)
        assert f["call_wall_pct"][m] == pytest.approx(cw) and f["put_wall_pct"][m] == pytest.approx(pw)
        net, gross = EV.net_gex(S[m], calls, puts, T[m])
        assert f["gex_norm"][m] == pytest.approx(net / gross, rel=1e-9)
        fl = EV.flip_distance(S[m], calls, puts, T[m])
        assert (fl is None and np.isnan(f["flip_dist_pct"][m])) or f["flip_dist_pct"][m] == pytest.approx(fl, rel=1e-6)
        sb = EV.synth_basis_bps(S[m], calls[10][0], calls[10][3], puts[10][3])
        assert f["synth_basis_bps"][m] == pytest.approx(sb)


def test_gamma_flip_found_between_put_and_call_oi():
    # puts heavy below 100, calls heavy above: net GEX is negative low and positive high -> a flip near 100
    T = np.array([5 / 252])
    strikes = np.full((2, 21, 1), np.nan)
    ois = np.zeros((2, 21, 1))
    ivs = np.full((2, 21, 1), 15.0)
    ks = 100 + np.arange(-10, 11) * 1.0
    strikes[0, :, 0] = ks
    strikes[1, :, 0] = ks
    ois[0, 11:, 0] = 1000                           # calls 101..110
    ois[1, :10, 0] = 1000                           # puts 90..99
    fl = C.flip_distance(np.array([100.0]), strikes, ivs, ois, T)[0]
    assert abs(fl) < 0.5
    fl2 = C.flip_distance(np.array([97.0]), strikes, ivs, ois, T)[0]
    assert fl2 > 0                                  # below the flip: the flip is above the index
    ois[1] = 0                                      # calls only: never changes sign
    assert np.isnan(C.flip_distance(np.array([100.0]), strikes, ivs, ois, T)[0])


def test_pcr_and_synthetic_basis_definitions(day1):
    idx, g = day1
    snap = C.snapshot(g)
    f = C.day_features(snap, idx[3], 2.0)
    m = 150
    near = np.abs(C.OFFS) <= 5
    p = np.nansum(snap["snap"]["oi"][1][near, m]) / np.nansum(snap["snap"]["oi"][0][near, m])
    assert f["pcr_oi"][m] == pytest.approx(p)
    cv = snap["vol"][0][near, : m + 1].sum()
    pv = snap["vol"][1][near, : m + 1].sum()
    assert f["pcr_vol"][m] == pytest.approx(pv / cv)
    assert EV.pcr(5.0, 0.0) is None and EV.synth_basis_bps(100.0, 100.0, 0.0, 1.0) is None


# ------------------------------------------------------------------------------------------------ feature rows
def _fake_day(d, idx, dte=2.0):
    D = r51data.Day(d, idx)
    D.atm = np.round(idx[3] / STEP) * STEP
    D.dte = dte
    return D


def test_new_features_signed_by_side_and_walls_swap(day1):
    idx, g = day1
    snap = C.snapshot(g)
    f = C.day_features(snap, idx[3], 2.0)
    cd = dict(f=f, snap={k: snap["snap"][k] for k in ("strike", "iv", "close")})
    cd["f"]["term_slope"] = np.full(N, np.nan)
    ctx = dict(pcr_rank5=np.full(N, 0.8), pcr_rank20=np.full(N, 0.3), iv_rank60=np.full(N, 0.4),
               vix_rank60=np.full(N, 0.6), other_expiry_today=1.0, event_rbi_budget=0.0, post_us_event=1.0,
               fii_fut_long_share=0.3, fii_fut_long_chg=-0.01)
    D = _fake_day(date(2025, 7, 2), idx)
    frow = 150
    lev = np.array([D.idx[3, frow] - 20, D.idx[3, frow] + 50, D.idx[3, frow] - 100])
    c = F.new_features("NIFTY", D, frow, "C", None, cd, ctx, lev)
    p = F.new_features("NIFTY", D, frow, "P", None, cd, ctx, lev)
    for n in K.SIGNED_NEW:
        if np.isfinite(c[n]):
            assert c[n] == pytest.approx(-p[n]), n
    assert c["pcr_rank5"] == pytest.approx(0.3) and p["pcr_rank5"] == pytest.approx(-0.3)
    assert c["wall_ahead_pct"] == p["wall_behind_pct"] and c["wall_behind_pct"] == p["wall_ahead_pct"]
    assert c["va_pos"] == pytest.approx(20 / 150)
    assert c["iv_rank60"] == p["iv_rank60"] == 0.4                    # unsigned features stay the same
    assert set(c) == set(K.NEW)


def test_missing_chain_and_context_give_missing_features(day1):
    idx, _ = day1
    D = _fake_day(date(2025, 7, 3), idx)
    out = F.new_features("NIFTY", D, 100, "C", 20000.0, None, None, None)
    assert all(np.isnan(v) for v in out.values())


def test_names_and_blocks():
    assert F.names(()) == M57.names_a(False) and len(F.names(())) == 29
    assert len(F.names(("CH", "GK", "CX"))) == 59 and len(K.NEW) == 30
    assert "pcr_oi" in F.names(()) and not set(K.CH) & set(F.names(("GK", "CX")))


# ------------------------------------------------------------------------------------------------ calendar
def test_event_lists_match_research_sources():
    src = (K.HERE.parents[1] / "obuy" / "strategies" / "gc_common.py").read_text()
    tree = ast.parse(src)
    vals = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name) and \
                node.targets[0].id in ("_RBI", "_BUDGET"):
            vals[node.targets[0].id] = ast.literal_eval(node.value)
    assert r61events.BUDGET == vals["_BUDGET"]
    assert sorted(r61events.RBI) == sorted(vals["_RBI"] + ["2022-05-04"])     # gc_common adds 2022-05-04 itself


def test_post_us_event_hits_the_next_session_only():
    ss = [date(2026, 4, 28), date(2026, 4, 29), date(2026, 4, 30), date(2026, 5, 4)]   # 1 May holiday, weekend
    fl = r61events.flags(ss)
    assert fl[date(2026, 4, 29)][1] == 0.0          # FOMC 29 Apr is decided after the NSE close
    assert fl[date(2026, 4, 30)][1] == 1.0
    assert fl[date(2026, 5, 4)][1] == 0.0
    fl2 = r61events.flags([date(2026, 6, 5), date(2026, 6, 8), date(2026, 6, 10), date(2026, 6, 11)])
    assert fl2[date(2026, 6, 5)][0] == 1.0          # RBI policy day (research list)
    assert fl2[date(2026, 6, 11)][1] == 1.0         # US CPI 10 Jun -> 11 Jun


def test_post_us_event_across_a_holiday_gap():
    fl = r61events.flags([date(2026, 7, 13), date(2026, 7, 16)])      # CPI 14 Jul falls in the gap
    assert fl[date(2026, 7, 16)][1] == 1.0


# ------------------------------------------------------------------------------------------------ walk-forward
def test_walk_purges_and_embargoes(monkeypatch):
    days = []
    d = date(2025, 5, 1)
    while d <= date(2025, 8, 31):
        if d.weekday() < 5:
            days.append(d)
        d += timedelta(days=1)
    cal = list(days)
    rows_days = np.array([x for x in days for _ in range(3)], dtype=object)
    X = np.random.default_rng(1).normal(size=(len(rows_days), 3))
    y = np.random.default_rng(2).integers(0, 2, len(rows_days))
    seen = []

    def spy(kind, Xt, yt):
        seen.append(len(Xt))
        return ST.make("logit").fit(Xt, yt)
    monkeypatch.setattr(ST, "fit", spy)
    sc, thr, _ = ST.walk(rows_days, X, y, "logit", [(2025, 7), (2025, 8)], cal)
    first_jul = date(2025, 7, 1)
    last_before = max(x for x in cal if x < first_jul)
    n_train = sum(1 for x in rows_days if x < last_before)      # the last session before July is embargoed
    assert seen[0] == n_train
    assert np.isnan(sc[[i for i, x in enumerate(rows_days) if x < first_jul]]).all()
    assert np.isfinite(sc[[i for i, x in enumerate(rows_days) if x >= first_jul]]).all()


def test_rows_keep_held_contract_labels(monkeypatch):
    # F.add only adds features; label, net and the held strike are R57's, untouched
    idx = index_path(np.random.default_rng(4))
    D = _fake_day(date(2025, 7, 7), idx)
    D.vix = np.full(N, 14.0, np.float32)
    monkeypatch.setitem(r57data._CACHE, "NIFTY", {D.d: D})
    monkeypatch.setattr(r61data, "load_ctx", lambda: {})
    monkeypatch.setattr(r61data, "load_chain", lambda u: {})
    monkeypatch.setitem(S57._LEV, "NIFTY", {})
    row = dict(und="NIFTY", day=D.d, er=10, side="C", strike=20000.0, net=-123.0, y_win=0, f=dict(a=1.0))
    out = F.add([dict(row, f=dict(row["f"]))])[0]
    assert out["net"] == -123.0 and out["y_win"] == 0 and out["strike"] == 20000.0 and out["f"]["a"] == 1.0
    assert set(K.NEW) <= set(out["f"])


def test_period_guard_and_expiry_day_dropped(monkeypatch):
    idx = index_path(np.random.default_rng(5))
    ds = [date(2025, 7, 7), date(2025, 7, 8), date(2025, 7, 10)]          # 9 Jul a holiday (absent)
    store = {d: _fake_day(d, idx) for d in ds}
    store[ds[1]].expiry = True
    monkeypatch.setitem(r57data._CACHE, "NIFTY", store)
    assert S57.days_in("NIFTY", K.START, K.DESIGN_END) == [ds[0], ds[2]]
    monkeypatch.setattr(S57, "_FINAL_OPEN", False)
    with pytest.raises(PermissionError):
        S57.days_in("NIFTY", K.FINAL_START, K.END)
    with pytest.raises(PermissionError):
        S57.days_in("NIFTY", date(2024, 11, 1), K.DESIGN_END)            # nothing before 20 Nov 2024


def test_final_refuses_a_second_read(tmp_path, monkeypatch):
    flag = tmp_path / "FINAL_READ"
    flag.write_text("read")
    monkeypatch.setattr(K, "FINAL_FLAG", flag)
    with pytest.raises(SystemExit):
        ST.final()


# ------------------------------------------------------------------------------------------------ replace rule
def _t(d, net, und="NIFTY"):
    return dict(day=d, net=net, und=und)


def test_paired_bootstrap_and_replace_rule():
    ds = [date(2026, 4, 1) + timedelta(days=i) for i in range(60)]
    better = [_t(d, 500.0) for d in ds]
    worse = [_t(d, -100.0) for d in ds]
    assert ST.paired_boot(better, worse, ds) < 0.01
    assert ST.paired_boot(worse, better, ds) > 0.99
    st = dict(variants={})
    for v in K.VARIANTS:
        st["variants"][v] = dict(verdict="FAIL: x", test=dict(net=0), auc_test=0.5, ex_midcp=0)
    st["variants"]["V1"].update(verdict="PASS", test=dict(net=-6000), auc_test=0.52, ex_midcp=-6000)
    st["variants"]["V3"].update(verdict="PASS", test=dict(net=30000), auc_test=0.55, ex_midcp=30000)
    st["variants"]["V5"].update(verdict="PASS", test=dict(net=50000), auc_test=0.56, ex_midcp=50000)
    design = dict(variants={v: dict(wf=dict(net=1000 if v == "V3" else 500)) for v in K.VARIANTS})
    trades = {v: worse for v in K.VARIANTS}
    trades["V3"] = better
    r = ST.replace_decision(st, trades, ds, design)
    assert r["candidate"] == "V3" and r["replaces"]           # picked by DESIGN net, not by the bigger final net
    st["variants"]["V3"]["ex_midcp"] = -7000                    # leans on MIDCPNIFTY: no replacement
    assert not ST.replace_decision(st, trades, ds, design)["replaces"]
    for v in K.VARIANTS:
        st["variants"][v]["verdict"] = "FAIL: x"
    assert ST.replace_decision(st, trades, ds, design)["candidate"] is None


# ------------------------------------------------------------------------------------------------ scorer parity
@pytest.mark.parametrize("kind", ["logit", "l1", "gbc"])
def test_exported_model_parity(kind):
    rng = np.random.default_rng(11)
    X = rng.normal(size=(600, 6))
    X[rng.random(X.shape) < 0.1] = np.nan
    y = (np.nan_to_num(X[:, 0]) + rng.normal(0, 1, 600) > 0).astype(int)
    m = ST.fit(kind, X, y)
    js = M57.export("gbc" if kind == "gbc" else "logit", m, [f"x{i}" for i in range(6)], 0.5, ">=", {})
    js = json.loads(json.dumps(js))
    sk = ST.predict(m, X[:50])
    for i in range(50):
        row = [None if not np.isfinite(v) else float(v) for v in X[i]]
        assert abs(EV.score(js, row) - sk[i]) < 1e-9
    if kind == "l1":                                           # pure L1: the noise inputs are dropped
        assert sum(abs(w) > 0 for w in js["coef"]) < 6 and abs(js["coef"][0]) > 0


# ------------------------------------------------------------------------------------------------ data path and runner
def test_build_chain_from_a_parquet_lake(tmp_path, monkeypatch):
    """r61data.build_chain on a tiny synthetic lake: cleaning (a foreign row, a bad tick) then the chain features."""
    rng = np.random.default_rng(9)
    d = date(2025, 7, 7)
    idx = index_path(rng)
    g = chain_rows(rng, idx, drop=0.0)
    g = g[g.r < 60].copy()
    g["ts"] = pd.Timestamp(d) + pd.to_timedelta(9 * 60 + 15 + g.r, unit="min")
    foreign = g.iloc[[0]].copy()
    foreign["spot"] = foreign.spot * 1.2                 # another underlying's row: dropped
    foreign["offset"] = 9
    foreign["strike"] = (foreign.spot / 100).round() * 100   # its own strike near its own spot
    spike = g[(g.side == "C") & (g.offset == 0) & (g.r == 30)].index
    g.loc[spike, "close"] = g.loc[spike, "close"] * 5    # one-minute bad tick: dropped
    lake = tmp_path / "lake"
    lake.mkdir()
    pd.concat([g, foreign]).drop(columns=["r"]).to_parquet(lake / "opt_BANKNIFTY_WEEK_2025.parquet")
    monkeypatch.setattr(K, "LAKE", lake)
    monkeypatch.setattr(K, "OUT", tmp_path / "out")
    D = _fake_day(d, idx)
    info = r61data.build_chain("BANKNIFTY", {d: D}, with_flip=False)
    assert info["chain_days"] == 1 and info["foreign"] == 1 and info["spikes"] >= 1
    import pickle
    out = pickle.loads((tmp_path / "out" / "chain_BANKNIFTY.pkl").read_bytes())
    f = out[d]["f"]
    assert np.isfinite(f["pcr_oi"][10]) and np.isnan(f["pcr_oi"][70:]).sum() == 0     # carried forward after 10:14
    assert np.isnan(f["term_slope"]).all()                                            # NIFTY only
    assert out[d]["snap"]["strike"].dtype == np.float32


def test_run_selects_at_most_one_trade_a_day():
    rng = np.random.default_rng(12)
    rows, d = [], date(2025, 1, 1)
    while d <= date(2025, 9, 30):
        if d.weekday() < 5:
            for er in (5, 60, 180):
                f = {n: float(rng.normal()) for n in F.names(("CH", "GK", "CX"))}
                rows.append(dict(day=d, er=er, und="NIFTY", setup="S2", net=float(rng.normal(0, 100)), f=f))
        d += timedelta(days=1)
    for r in rows:
        r["y_win"] = int(r["net"] > 0)
    cal = sorted({r["day"] for r in rows})
    for kind, blocks in (("logit", ()), ("gbc", ("CH",)), ("l1", ("CH", "GK", "CX"))):
        res = ST.run(kind, F.names(blocks), rows, [(2025, 8), (2025, 9)], cal)
        days = [t["day"] for t in res["trades"]]
        assert len(days) == len(set(days)) and all(x >= date(2025, 8, 1) for x in days)
        assert res["tested"] == sum(1 for r in rows if r["day"] >= date(2025, 8, 1))


# ------------------------------------------------------------------------------------------------ concepts (Part 1)
def test_concepts_complete_and_files_current():
    import r61concepts as CC
    ids = [c["id"] for c in CC.CONCEPTS]
    assert len(ids) == len(set(ids))
    for c in CC.CONCEPTS:
        assert set(CC.KEYS) <= set(c), c["id"]
        assert c["what"] and c["formula"] and c["phone"] and c["use"] and c["pitfalls"] and c["research"]
        for r in c["research"]:
            assert (K.HERE.parents[2] / r["doc"]).exists(), r["doc"]
    named = {f.split(" ")[0] for c in CC.CONCEPTS for f in c["features"]}
    assert set(K.NEW) <= named                                    # every R61 input is explained by a concept
    for need in ("oi", "buildup", "pcr", "max_pain", "oi_walls", "iv", "iv_rank", "skew", "term", "vix", "delta",
                 "gamma", "theta", "vega", "gex", "dealers", "vwap", "atr", "orb", "value_area", "regime",
                 "participants", "basis", "rollover", "events"):
        assert need in ids
    js = json.loads((K.HERE / "concepts.json").read_text())
    assert [c["id"] for c in js["concepts"]] == ids
    assert all("r61_result" in c for c in js["concepts"])
