"""R144a (PREREG.md): why the breakout arms lose (R72, R51, R52): anatomy on volume, liquidity, OI, context and
order-flow proxies, and at most 3 pre-registered skip filters per arm, tested on the final AND the unseen two years.

    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r144a_loss_breakout/r144a.py design   # choose filters
    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r144a_loss_breakout/r144a.py final    # read once
    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r144a_loss_breakout/r144a.py unseen   # read once (old store)
    python research/hunt/r144a_loss_breakout/r144a.py report                                         # gates, BH, p-values

The unseen phase reads R143's old store ($R51_OUT/r143_old, built by r143.py build). Research only: no app or arm code
changes. 1 Nov 2026.
"""
from __future__ import annotations

import json
import math
import os
import pickle
import sys
from collections import Counter, defaultdict
from datetime import date, datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
CMD = (sys.argv[1:] or [""])[0] if __name__ == "__main__" else ""
if CMD == "unseen":                     # before anything imports r51cfg: R143's old store
    _base = Path(os.environ.get("R51_OUT", Path.home() / ".cache" / "iraalgo" / "r51")).expanduser()
    os.environ["R51_OUT"] = str(_base / "r143_old")

import numpy as np  # noqa: E402

sys.path.insert(0, str(HERE))
import r144a_feat as F  # noqa: E402

RESULTS = HERE / "results"
CACHE = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser() / "r144a"
US_FILE = CACHE / "gspc_daily.json"
ARMS = ("R72", "R51", "R52")
ARM_NAME = {"R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend", "R52": "R52 Open outside value"}
SEED = 1441
TWINS = 2000
MIN_N = 30
MAX_PER_ARM = 3
GATE_P, GATE_Q = 0.05, 0.10
MIN_TRADES, MIN_CHANGED = 40, 30
D_START, D_END = date(2024, 11, 20), date(2026, 3, 31)
H1 = (date(2024, 11, 20), date(2025, 3, 31))
H2 = (date(2025, 4, 1), date(2026, 3, 31))
FINAL = (date(2026, 4, 1), date(2026, 10, 13))
UNSEEN = (date(2022, 12, 1), date(2024, 11, 19))
PERIODS = {"unseen": UNSEEN, "design": (D_START, D_END), "final": FINAL, "H1": H1, "H2": H2}
NEVER, GAVE = 0.05, 0.15


# ================================================================================================ pure helpers
def in_p(t, per) -> bool:
    a, b = PERIODS[per]
    return a <= t["day"] <= b


def summary(tr: list) -> dict:
    if not tr:
        return dict(n=0)
    nets = np.array([float(t["net"]) for t in tr])
    lo = nets < 0
    return dict(n=len(tr), losers=int(lo.sum()), loser_rate=round(float(lo.mean()), 3), win=round(float((nets > 0).mean()), 3),
                avg=round(float(nets.mean())), net=round(float(nets.sum())), sum_losses=round(float(nets[lo].sum())),
                avg_loss=round(float(nets[lo].mean())) if lo.any() else 0)


def exit_type(t) -> str:
    w = str(t.get("why", ""))
    if w.startswith("stop"):
        return "stop"
    if w in ("time", "squareoff"):
        return "time / square-off"
    if w == "lock":
        return "lock"
    return "other"


def peak_type(t) -> str:
    p = t.get("peak")
    if p is None or not np.isfinite(p):
        return "unknown"
    return "never worked (<5%)" if p < NEVER else ("gave it back (>=15%)" if p >= GAVE else "in between")


def money_lost(tr: list, label) -> dict:
    """{label: {losers, lost, share of all money lost}} over the losing trades."""
    lose = [t for t in tr if t["net"] < 0]
    tot = sum(float(t["net"]) for t in lose) or -1.0
    g = defaultdict(list)
    for t in lose:
        g[label(t)].append(float(t["net"]))
    return {k: dict(losers=len(v), lost=round(sum(v)), share=round(sum(v) / tot, 3), avg=round(float(np.mean(v))))
            for k, v in sorted(g.items(), key=lambda kv: sum(kv[1]))}


def bunching(tr: list) -> dict:
    daily = defaultdict(float)
    by_ix = defaultdict(list)
    for t in tr:
        daily[t["day"]] += float(t["net"])
        by_ix[(t["day"], t["und"])].append(float(t["net"]))
    lose = [t for t in tr if t["net"] < 0]
    all_loss = {k for k, v in by_ix.items() if all(x < 0 for x in v)}
    seq = [float(t["net"]) < 0 for t in sorted(tr, key=lambda t: (t["day"], int(t["er"]), t["und"]))]
    s = best = 0
    for x in seq:
        s = s + 1 if x else 0
        best = max(best, s)
    # losing-day streak (calendar of trading days with a trade)
    ds = [daily[d] < 0 for d in sorted(daily)]
    s = bestd = 0
    for x in ds:
        s = s + 1 if x else 0
        bestd = max(bestd, s)
    neg = sorted(v for v in daily.values() if v < 0)
    return dict(days=len(daily), losing_days=len(neg), losing_day_share=round(len(neg) / max(len(daily), 1), 3),
                worst_day=round(neg[0]) if neg else 0, worst5_days_share_of_losses=round(sum(neg[:5]) / sum(neg), 3)
                if neg else 0, losers_on_all_loss_index_days=round(sum(1 for t in lose if (t["day"], t["und"]) in
                                                                         all_loss) / max(len(lose), 1), 3),
                longest_losing_trade_streak=best, longest_losing_day_streak=bestd)


def edges(values) -> list:
    v = np.asarray([x for x in values if x is not None and np.isfinite(x)], float)
    return [float(x) for x in np.quantile(v, [0.2, 0.4, 0.6, 0.8])] if len(v) >= 5 else []


def slice_of(f: str, x, ed, cats=()):
    if f in cats:
        return None if x is None or (isinstance(x, float) and not np.isfinite(x)) else (
            x if not isinstance(x, (float, np.floating)) else float(x))
    if x is None or not isinstance(x, (int, float, np.floating, np.integer)) or not np.isfinite(x) or not len(ed):
        return None
    return int(np.searchsorted(np.asarray(ed), x, side="right"))


def steady(row: dict, base: dict, pers=("unseen", "design", "final")) -> bool:
    """PREREG section 3: loser rate above the arm's and average net below 0 in every period, >= 30 trades each."""
    for p in pers:
        r, b = row.get(p, {}), base.get(p, {})
        if r.get("n", 0) < MIN_N or not b.get("n"):
            return False
        if not (r["loser_rate"] > b["loser_rate"] and r["avg"] < 0):
            return False
    return True


def tscore(a, b) -> float:
    a, b = np.asarray(a, float), np.asarray(b, float)
    if len(a) < 2 or len(b) < 2:
        return float("nan")
    se = math.sqrt(a.var(ddof=1) / len(a) + b.var(ddof=1) / len(b))
    return float((a.mean() - b.mean()) / se) if se > 0 else float("nan")


def qualifies(halves: dict, arm_halves: dict) -> bool:
    """PREREG section 4: in BOTH design halves >= 30 trades, average below 0 and below the arm's, loser rate above."""
    for h in ("H1", "H2"):
        r, b = halves.get(h, {}), arm_halves[h]
        if r.get("n", 0) < MIN_N:
            return False
        if not (r["avg"] < 0 and r["avg"] < b["avg"] and r["loser_rate"] > b["loser_rate"]):
            return False
    return True


def design_ok(rule: dict, base: dict) -> bool:
    return (rule["loss_sum"] > base["loss_sum"] and rule["max_dd"] <= base["max_dd"]
            and rule["worst_day"] >= base["worst_day"] and rule["worst_month"] >= base["worst_month"] and rule["net"] > 0)


def pick(cands: list) -> list:
    """Top MAX_PER_ARM by t-score (most negative first), one per feature."""
    out, seen = [], set()
    for c in sorted(cands, key=lambda c: c["t"]):
        if c["feature"] in seen:
            continue
        seen.add(c["feature"])
        out.append(c)
        if len(out) == MAX_PER_ARM:
            break
    return out


def gate_period(rule: dict, base: dict, p: float, trades: int, changed: int) -> list:
    """PREREG section 5 in one period; returns the failures."""
    f = []
    if not rule["loss_sum"] > base["loss_sum"]:
        f.append("losing days not smaller")
    if rule["max_dd"] > base["max_dd"]:
        f.append("drawdown worse")
    if rule["worst_day"] < base["worst_day"]:
        f.append("worst day worse")
    if rule["worst_month"] < base["worst_month"]:
        f.append("worst month worse")
    if base["net"] > 0 and not rule["net"] > 0:
        f.append("no longer makes money")
    if not p <= GATE_P:
        f.append(f"twin p {p:.3g} > {GATE_P}")
    if trades < MIN_TRADES:
        f.append(f"< {MIN_TRADES} trades")
    if changed < MIN_CHANGED:
        f.append(f"< {MIN_CHANGED} trades differ")
    return f


def bh(p) -> np.ndarray:
    """Benjamini-Hochberg q of the p-values (within this group)."""
    p = np.asarray(p, float)
    n = len(p)
    q = np.empty(n)
    prev = 1.0
    for rank, i in enumerate(np.argsort(p)[::-1]):
        k = n - rank
        prev = min(prev, p[i] * n / k)
        q[i] = prev
    return q


def drop_candidate(sel: dict, feats: dict, key) -> bool:
    x = feats.get(key, {}).get(sel["feature"])
    return slice_of(sel["feature"], x, sel["edges"], F.CATS) == sel["slice"]


# ================================================================================================ engine-side (lake)
def engine():
    sys.path.insert(0, str(HERE.parent / "r140_structure_arms"))
    import r140a as A
    return A


def cache_key(und, d, c) -> tuple:
    return (os.environ.get("R51_OUT", ""), und, str(d), int(c["er"]), c["side"], float(c["strike"]))


_FC: dict = {}


def feature_cache() -> dict:
    """R144a features per candidate, kept outside git (they depend only on the candidate and the store)."""
    if not _FC:
        p = CACHE / "features.pkl"
        if p.exists():
            with open(p, "rb") as f:
                _FC.update(pickle.load(f))
    return _FC


def save_cache(fc: dict):
    CACHE.mkdir(parents=True, exist_ok=True)
    with open(CACHE / "features.pkl", "wb") as f:
        pickle.dump(fc, f, protocol=pickle.HIGHEST_PROTOCOL)


def candidate_features(us: list) -> dict:
    """{cand_key: R144a features + R141's} for every candidate of the units."""
    A = engine()
    S = A.S
    r51 = S.mods()["r51data"]
    by_und = defaultdict(lambda: defaultdict(list))
    for key, cands, _, _ in us:
        for c in cands:
            by_und[key[0]][key[1]].append(c)
    years = sorted({d.year for u in by_und.values() for d in u})
    vix = F.vix_minutes(range(years[0] - 1, years[-1] + 1))
    dctx = F.daily_context()
    us_mv = F.us_moves(US_FILE)
    out = {}
    fc = feature_cache()
    for und, days in by_und.items():
        store = r51.load(und)
        todo = {d for d, cs in days.items() if any(cache_key(und, d, c) not in fc for c in cs)}
        if not todo:
            for d, cs in days.items():
                for c in cs:
                    out[A.cand_key(und, d, c)] = fc[cache_key(und, d, c)]
            print("features", und, "cached", flush=True)
            continue
        vec = {}                                     # day -> (atm vol, itm C, itm P), all days read so far
        for y in range(min(days).year - 1, max(days).year + 1):
            chains = F.chains_for(und, y)
            for d in sorted(chains):
                ch = chains[d]
                vec[d] = (F.atm_volume(ch), F.itm_volume(ch, "C"), F.itm_volume(ch, "P"))
                if d not in days:
                    continue
                prev = sorted(k for k in vec if k < d)[-F.HIST:]
                hist_atm = np.array([vec[k][0] for k in prev]) if prev else None
                D = store.get(d)
                dc = dctx.get(und, {}).get(d, {})
                for c in days[d]:
                    ck = cache_key(und, d, c)
                    if ck in fc:
                        out[A.cand_key(und, d, c)] = fc[ck]
                        continue
                    si = 1 if c["side"] == "C" else 2
                    ctx = dict(dc, vix=vix.get(d), hist_atm=hist_atm,
                               hist_itm=np.array([vec[k][si] for k in prev]) if prev else None,
                               us_chg=F.us_change_for(us_mv, d))
                    con = D.contract(c["side"], c["strike"]) if D is not None else None
                    idx = D.idx if D is not None else np.full((4, F.N), np.nan)
                    out[A.cand_key(und, d, c)] = fc[ck] = F.features(ch, con, idx, int(c["er"]), c["side"],
                                                                      c["strike"], ctx)
            print("features", und, y, len(out), flush=True)
    save_cache(fc)
    a_feats = A.all_features(us)
    for k, v in a_feats.items():
        out.setdefault(k, {}).update({f: v.get(f) for f in A.FEATS})
    return out


def stats(tr: list) -> dict:
    A = engine()
    s = A.M.loss_stats(tr)
    return s


def feature_tables(trades: list, feats: dict, edge_map: dict, pers: tuple) -> dict:
    """{feature: {slice: {period: summary}}} with steady flags (when all three periods are present)."""
    A = engine()
    cats = F.CATS + A.CATS
    base = {p: summary([t for t in trades if in_p(t, p)]) for p in pers}
    out = {}
    for f, ed in edge_map.items():
        g = defaultdict(list)
        for t in trades:
            sl = slice_of(f, feats.get(A.trade_key(t), {}).get(f), ed, cats)
            if sl is not None:
                g[str(sl)].append(t)
        rows = {}
        for sl, tr in g.items():
            row = {p: summary([t for t in tr if in_p(t, p)]) for p in pers}
            if set(("unseen", "design", "final")) <= set(pers):
                row["steady"] = steady(row, base)
            rows[sl] = row
        out[f] = rows
    return out


def worst(trades: list, feats: dict, k: int = 20) -> list:
    A = engine()
    keep = ("A1", "A7", "A10", "A11", "V1", "V2", "Q1", "Q2", "O2", "O4", "O5", "O7", "C7", "C8", "C11", "F1", "F2", "F3")
    out = []
    for t in sorted(trades, key=lambda t: float(t["net"]))[:k]:
        f = feats.get(A.trade_key(t), {})
        out.append(dict(und=t["und"], day=str(t["day"]), side=t["side"], strike=t["strike"],
                        entry=f"{(555 + int(t['er'])) // 60:02d}:{(555 + int(t['er'])) % 60:02d}",
                        held_min=int(t["xr"]) - int(t["er"]), why=t["why"], peak=round(float(t["peak"]), 3)
                        if t.get("peak") is not None and np.isfinite(t["peak"]) else None,
                        net=round(float(t["net"])), E=round(float(t["E"]), 2), X=round(float(t["X"]), 2),
                        **{x: (round(float(f[x]), 3) if isinstance(f.get(x), (float, np.floating)) and np.isfinite(f[x])
                               else f.get(x)) for x in keep}))
    return out


def anatomy(trades: list, feats: dict, edge_map: dict, pers: tuple) -> dict:
    out = {"overview": {p: summary([t for t in trades if in_p(t, p)]) for p in pers}}
    for p in pers:
        tr = [t for t in trades if in_p(t, p)]
        out.setdefault("lost_by_exit", {})[p] = money_lost(tr, exit_type)
        out.setdefault("lost_by_peak", {})[p] = money_lost(tr, peak_type)
        out.setdefault("lost_by_exit_and_peak", {})[p] = money_lost(tr, lambda t: exit_type(t) + " / " + peak_type(t))
        out.setdefault("bunching", {})[p] = bunching(tr)
        out.setdefault("worst20", {})[p] = worst(tr, feats)
    out["features"] = feature_tables(trades, feats, edge_map, pers)
    return out


def twins(arm: str, us: list, k: int, n: int, rng) -> np.ndarray:
    A = engine()
    S, L, M = A.S, A.L, A.M
    flat = [(g, j) for g, u in enumerate(us) for j in range(len(u[1]))]
    out = np.zeros(n)
    for i in range(n):
        gone = {flat[q] for q in L.random_drop(len(flat), k, rng)}
        tr = S.walk(arm, S.filtered(us, lambda g, j, key, c: (g, j) not in gone))
        out[i] = M.loss_sum((t["day"], t["net"]) for t in tr)
    return out


def test_filter(arm: str, us: list, base: list, feats: dict, sel: dict, rng) -> dict:
    A = engine()
    S, L, M = A.S, A.L, A.M
    keep = lambda g, j, key, c: not drop_candidate(sel, feats, A.cand_key(key[0], key[1], c))  # noqa: E731
    k = sum(1 for g, u in enumerate(us) for j, c in enumerate(u[1]) if not keep(g, j, u[0], c))
    tr = S.walk(arm, S.filtered(us, keep))
    r = dict(rule=stats(tr), today=stats(base), dropped_candidates=k, candidates=sum(len(u[1]) for u in us),
             changed=L.changed(tr, base))
    tw = twins(arm, us, k, TWINS, rng)
    r["twin"] = L.twin_p(M.loss_sum((t["day"], t["net"]) for t in tr), tw)
    r["fails"] = gate_period(r["rule"], r["today"], r["twin"]["p"], r["rule"]["trades"], r["changed"])
    return r


def _jsonable(o):
    return json.loads(json.dumps(o, default=lambda x: x.item() if hasattr(x, "item") else str(x)))


# ================================================================================================ phases
def assert_published(arm: str, base: list, phases):
    A = engine()
    K = A.K
    for ph in phases:
        a, b = (K.START, K.DESIGN_END) if ph == "design" else (K.FINAL_START, K.FINAL_END)
        tr = [x for x in base if a <= x["day"] <= b]
        pub = K.PUBLISHED[(arm, ph)]
        assert pub[0] == len(tr) and abs(pub[1] - sum(x["net"] for x in tr)) <= 1, (arm, ph, "parity")


def design():
    A = engine()
    S = A.S
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), phase="design"), "arms": {}}
    selection = {}
    for arm in ARMS:
        us = S.units(arm, D_START, D_END)
        base = S.walk(arm, us)
        assert_published(arm, base, ("design",))
        feats = candidate_features(us)
        edge_map = {}
        for f in F.FEATS + A.FEATS:
            if f in F.QUINT or f in A.QUINT:
                edge_map[f] = edges([feats.get(A.trade_key(t), {}).get(f, np.nan) for t in base])
            else:
                edge_map[f] = []
        pers = ("design", "H1", "H2")
        res = anatomy(base, feats, edge_map, pers)
        res["edges"] = edge_map
        res["coverage"] = {f: round(float(np.mean([isinstance(feats.get(A.trade_key(t), {}).get(f), str) or (
            isinstance(feats.get(A.trade_key(t), {}).get(f), (float, np.floating, int)) and
            np.isfinite(feats.get(A.trade_key(t), {}).get(f))) for t in base])), 3) for f in edge_map}
        arm_h = {h: summary([t for t in base if in_p(t, h)]) for h in ("H1", "H2")}
        base_stats = stats(base)
        cands = []
        for f in F.FEATS:
            tab = res["features"].get(f, {})
            opts = [sl for sl in tab if (f in F.CATS or sl in ("0", "4"))]
            for sl in opts:
                if not qualifies(tab[sl], arm_h):
                    continue
                slv = sl if f in F.CATS else int(sl)
                sel = dict(feature=f, slice=slv, edges=edge_map[f])
                keep = lambda g, j, key, c, sel=sel: not drop_candidate(sel, feats, A.cand_key(key[0], key[1], c))  # noqa
                tr = S.walk(arm, S.filtered(us, keep))
                st = stats(tr)
                ok = design_ok(st, base_stats)
                ins = [float(t["net"]) for t in base if drop_candidate(sel, feats, A.trade_key(t))]
                rest = [float(t["net"]) for t in base if not drop_candidate(sel, feats, A.trade_key(t))]
                c = dict(feature=f, name=F.NAMES[f], slice=slv, edges=edge_map[f], t=tscore(ins, rest),
                         design_rule=st, design_ok=ok, halves={h: tab[sl][h] for h in ("H1", "H2")})
                cands.append(c)
                print(arm, "candidate", f, sl, round(c["t"], 2), ok, flush=True)
        chosen = pick([c for c in cands if c["design_ok"]])
        for i, c in enumerate(chosen, 1):
            c["id"] = f"R144A-{arm[1:]}-{c['feature']}"
        res["candidates"] = cands
        res["today"] = base_stats
        selection[arm] = chosen
        out["arms"][arm] = res
        print(arm, "chosen", [(c["id"], c["slice"]) for c in chosen], flush=True)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "design.json").write_text(json.dumps(_jsonable(out), indent=1))
    (RESULTS / "selection.json").write_text(json.dumps(_jsonable(selection), indent=1))


def tested(phase: str, us_all: list, arm: str, lo: date, hi: date, feats: dict, sel: list, rng) -> dict:
    A = engine()
    S = A.S
    us = [u for u in us_all if lo <= u[0][1] <= hi]
    base = S.walk(arm, us)
    out = {}
    for s in sel:
        r = test_filter(arm, us, base, feats, s, rng)
        out[s["id"]] = r
        print(phase, s["id"], r["rule"]["trades"], r["today"]["trades"], r["rule"]["net"], r["today"]["net"],
              r["rule"]["loss_sum"], r["today"]["loss_sum"], "p", round(r["twin"]["p"], 4), r["fails"], flush=True)
    return out


def phase_run(phase: str):
    A = engine()
    S = A.S
    flag = RESULTS / ("FINAL_READ" if phase == "final" else "UNSEEN_READ")
    if flag.exists():
        raise SystemExit(f"R144a {phase} already read once: " + flag.read_text())
    sel = json.loads((RESULTS / "selection.json").read_text())
    dj = json.loads((RESULTS / "design.json").read_text())["arms"]
    flag.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    rng = np.random.default_rng(SEED + (0 if phase == "final" else 1))
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), phase=phase), "arms": {}}
    if phase == "final":
        S.mods()["C"].open_final()
        lo, hi, pers = D_START, FINAL[1], ("design", "final")
    else:
        sys.path.insert(0, str(HERE.parent / "r143_unseen_two_years"))
        import r143 as T
        T.patch_old()
        lo, hi, pers = UNSEEN[0], T.CHECK[1], ("unseen",)
        main = json.loads((T.RESULTS / "parity_main.json").read_text())
    for arm in ARMS:
        us = S.units(arm, lo, hi)
        base = S.walk(arm, us)
        if phase == "final":
            assert_published(arm, base, ("design", "final"))
        else:
            mine = T.trade_set([x for x in base if T.in_(x, *T.CHECK_CMP)])
            ref = [x for x in main[arm] if x[1] <= str(T.CHECK_CMP[1])]
            assert mine == ref, (arm, "old store parity")
            out.setdefault("parity", {})[arm] = dict(trades=len(ref), identical=True)
        feats = candidate_features(us)
        edge_map = dj[arm]["edges"]
        tr = [t for t in base if any(in_p(t, p) for p in pers)]
        res = anatomy(tr, feats, edge_map, pers)
        res["tests"] = tested(phase, us, arm, *(FINAL if phase == "final" else UNSEEN), feats, sel[arm], rng)
        out["arms"][arm] = res
    (RESULTS / f"{phase}.json").write_text(json.dumps(_jsonable(out), indent=1))


def report():
    """Gates over both test periods, BH within the group, the p-value file and the merged anatomy with steady flags."""
    sel = json.loads((RESULTS / "selection.json").read_text())
    dj = json.loads((RESULTS / "design.json").read_text())["arms"]
    fj = json.loads((RESULTS / "final.json").read_text())["arms"]
    uj = json.loads((RESULTS / "unseen.json").read_text())["arms"]
    tests, rows = [], {}
    for arm in ARMS:
        for s in sel[arm]:
            i = s["id"]
            rf, ru = fj[arm]["tests"][i], uj[arm]["tests"][i]
            p = max(rf["twin"]["p"], ru["twin"]["p"])
            tests.append(i)
            rows[i] = dict(arm=arm, feature=s["feature"], name=s["name"], slice=s["slice"], edges=s["edges"],
                           p_final=rf["twin"]["p"], p_unseen=ru["twin"]["p"], p=p, final=rf, unseen=ru,
                           design_rule=s["design_rule"], design_today=dj[arm]["today"])
    qs = bh([rows[t]["p"] for t in tests]) if tests else []
    for t, q in zip(tests, qs):
        r = rows[t]
        fails = [f"final: {x}" for x in r["final"]["fails"]] + [f"unseen: {x}" for x in r["unseen"]["fails"]]
        if q > GATE_Q:
            fails.append(f"BH q {q:.3g} > {GATE_Q}")
        r.update(q=round(float(q), 4), passed=not fails, all_fails=fails)
    # merged anatomy: steady across the three periods
    merged = {}
    for arm in ARMS:
        fd, ff, fu = dj[arm]["features"], fj[arm]["features"], uj[arm]["features"]
        base = dict(design=dj[arm]["overview"]["design"], final=fj[arm]["overview"]["final"],
                    unseen=uj[arm]["overview"]["unseen"])
        st = []
        for f in fd:
            for sl in set(fd[f]) | set(ff.get(f, {})) | set(fu.get(f, {})):
                row = dict(design=fd[f].get(sl, {}).get("design", {}), final=ff.get(f, {}).get(sl, {}).get("final", {}),
                           unseen=fu.get(f, {}).get(sl, {}).get("unseen", {}))
                if steady(row, base):
                    st.append(dict(feature=f, slice=sl, **row))
        merged[arm] = dict(overview=base, steady=st)
    pv = dict(_meta=dict(group="R144a", note="raw twin p per test and period; p = max(final, unseen) is what BH used",
                         written=datetime.now(timezone.utc).isoformat()),
              tests={t: dict(arm=rows[t]["arm"], p_final=rows[t]["p_final"], p_unseen=rows[t]["p_unseen"],
                             p_used=rows[t]["p"], q_within_group=rows[t]["q"]) for t in tests})
    (RESULTS / "pvalues.json").write_text(json.dumps(pv, indent=1))
    (RESULTS / "report.json").write_text(json.dumps(_jsonable(dict(tests=rows, steady=merged)), indent=1))
    for t in tests:
        print(t, rows[t]["p_final"], rows[t]["p_unseen"], rows[t]["q"], rows[t]["passed"], rows[t]["all_fails"])
    for arm in ARMS:
        print(arm, "steady", [(x["feature"], x["slice"]) for x in merged[arm]["steady"]])


if __name__ == "__main__":
    {"design": design, "final": lambda: phase_run("final"), "unseen": lambda: phase_run("unseen"),
     "report": report}.get(CMD, lambda: (_ for _ in ()).throw(SystemExit(__doc__)))()
