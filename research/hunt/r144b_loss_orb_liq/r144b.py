"""R144b (PREREG.md): the losing trades of Liquidity 15+5, the ORB family and Solo 2.0; anatomy and skip filters.

    python research/hunt/r144b_loss_orb_liq/r144b.py status                       # what data is here (no numbers)
    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python .../r144b.py collect orb  <period>   # period: unseen | design | final
    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python .../r144b.py collect liq  <period>   # one process per group: r57cfg
    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python .../r144b.py collect solo <period>   # re-points r51cfg, so never mixed
    python .../r144b.py design          # anatomy on design, filters chosen and frozen -> results/design.json
    python .../r144b.py test            # final + unseen, read once -> results/test.json, results/pvalues.json

`collect ... final` and `collect ... unseen` refuse to run before results/design.json exists, so the filters are
frozen before a test trade is priced. Trades and features are cached in $R144B_OUT (default
~/.cache/iraalgo/r144b; never in git). Signal source: the index; options and VIX as context.

The engine adapters below call R3's, R115 / R101's and R57's engines unchanged. They were written on 1 Nov 2026 in a
session WITHOUT the lake (no rclone, no Drive copy), so they have not yet been run against real stores: the first run
must pass the parity checks in PREREG section 1 before any table is trusted.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
HUNT = HERE.parent
sys.path.insert(0, str(HERE))
import r144b_lib as X  # noqa: E402

RESULTS = HERE / "results"
CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R144B_OUT", CACHE_ROOT / "r144b")).expanduser()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE_ROOT / "lake")).expanduser()
FINAL_FLAG = RESULTS / "FINAL_READ"
SEED, TWINS = 1442, 2000

PERIODS = {"unseen": (date(2022, 12, 1), date(2024, 11, 19)),
           "design": (date(2024, 11, 20), date(2026, 3, 31)),
           "final": (date(2026, 4, 1), date(2026, 10, 13))}
HALVES = {"H1": (date(2024, 11, 20), date(2025, 3, 31)), "H2": (date(2025, 4, 1), date(2026, 3, 31))}
ORB_ARMS = {"ORB": "orb", "ORB Fresh": "orb_fresh", "ORB Sweep": "orb_sweep", "Range Fade": "range_fade"}
ORB_GATED_UND = "BANKNIFTY"
ORB_UNDS = ("BANKNIFTY", "NIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
LIQ_UNDS = ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
SOLO_UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
ARMS = ("LIQ", "ORB", "ORB Fresh", "ORB Sweep", "Range Fade", "Solo 2.0")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}
ITM = {"LIQ": 1, "Solo 2.0": 1}


# ================================================================================================ status (no numbers)
def status() -> dict:
    """Which inputs exist here. Writes results/status.json; reads no prices."""
    r3 = Path(os.environ.get("R3_OUT", CACHE_ROOT / "r3orb")).expanduser()
    r51 = Path(os.environ.get("R51_OUT", CACHE_ROOT / "r51")).expanduser()
    r57 = Path(os.environ.get("R57_OUT", CACHE_ROOT / "r57")).expanduser()
    have = dict(lake=LAKE.exists() and any(LAKE.iterdir()), r3_sessions=sorted(p.name for p in r3.glob("sess_*.pkl"))
                if r3.exists() else [], r51_store=r51.exists(), r143_old_store=(r51 / "r143_old").exists(),
                r57_store=r57.exists(), cache=sorted(p.name for p in OUT.glob("*.pkl")) if OUT.exists() else [])
    out = dict(checked=datetime.now(timezone.utc).isoformat(timespec="seconds"), inputs=have,
               runnable=bool(have["lake"] or have["r3_sessions"]))
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "status.json").write_text(json.dumps(out, indent=1))
    return out


# ================================================================================================ engine adapters
def _r3():
    p = str(HUNT / "r3orb")
    if p not in sys.path:
        sys.path.insert(0, p)
    import r3cfg
    import r3data
    import r3engine
    return r3cfg, r3data, r3engine


_SESS: dict = {}


def sessions(und: str) -> list:
    """R3's per-day store of one index (2021-2026): index, VIX, the option band with volume and OI."""
    if und not in _SESS:
        K, D, _ = _r3()
        _SESS[und] = D.load(und, "holdout" if K.is_holdout_und(und) else "design")
    return _SESS[und]


def norm(arm: str, t: dict) -> dict:
    """One trade in R144b's form; tolerant of each engine's key names."""
    side = t.get("side")
    side = ("C" if side > 0 else "P") if isinstance(side, (int, float, np.integer)) else str(side)[0].upper()
    er = t.get("er", t.get("entry_row"))
    xr = t.get("xr", t.get("exit_row", er))
    return dict(arm=arm, und=t["und"], day=t["day"], er=int(er), xr=int(xr), side=side, net=float(t["net"]),
                why=str(t.get("why", t.get("exit", "unknown"))), k=t.get("k", t.get("strike")),
                setup=t.get("setup"), peak=t.get("peak"))


def orb_trades(lo: date, hi: date) -> list:
    _, _, E = _r3()
    out = []
    for name, arm in ORB_ARMS.items():
        v = E.baseline(arm)
        for und in ORB_UNDS:
            for t in E.run([s for s in sessions(und) if lo <= s.day <= hi], v):
                out.append(norm(name, t))
    return out


def _r115(old: bool):
    if old:          # R143's old store (Sep 2022 - Dec 2024), patched before r51cfg is imported anywhere
        base = Path(os.environ.get("R51_OUT", CACHE_ROOT / "r51")).expanduser()
        os.environ["R51_OUT"] = str(base / "r143_old")
    for p in (HUNT / "r115_price_action_arms",):
        if str(p) not in sys.path:
            sys.path.insert(0, str(p))
    import r115study as S
    if old:
        k = S.mods()["r51data"].K
        k.WARM_START, k.START, k.END = date(2022, 9, 1), date(2022, 11, 1), date(2024, 12, 31)
    return S


def liq_units(lo: date, hi: date, old: bool):
    S = _r115(old)
    return S, S.units("LIQ", lo, hi)


def liq_trades(lo: date, hi: date, old: bool) -> tuple:
    S, us = liq_units(lo, hi, old)
    return [norm("LIQ", t) for t in S.walk("LIQ", us) if t["und"] in LIQ_UNDS], (S, us)


def _r57(old: bool):
    """R57's modules. old=True points R57 (and the R51 settings it re-points) at its own folder for Sep 2022 - Dec 2024,
    before r57data / r57setups are imported. R57's final guard is opened here: R144b reads its own final once, after
    its filters are frozen (collect refuses before design.json exists)."""
    p = str(HUNT / "r57")
    if p not in sys.path:
        sys.path.insert(0, p)
    import r57cfg as K
    if old:
        K.OUT = K._K51.OUT = K.OUT / "r144b_old"
        K.WARM_START = K._K51.WARM_START = date(2022, 9, 1)
        K.START = K._K51.START = PERIODS["unseen"][0]
        K.END = K._K51.END = date(2024, 12, 31)
        K.DESIGN_END = K._K51.DESIGN_END = PERIODS["unseen"][1]
    import r57data
    import r57eval as Ev
    import r57setups as St
    import r57study as Sy
    St._FINAL_OPEN = True
    return K, r57data, Ev, St, Sy


def build_solo_old():
    """R57's day stores for the unseen years, in their own folder (never touches R57's own store)."""
    K, D, _, _, _ = _r57(True)
    for u in SOLO_UNDS:
        print(D.build(u), flush=True)


def solo_trades(lo: date, hi: date) -> list:
    """R57's candidates scored by the frozen app model A2.json (no refit), first taker each day. A2 was trained on
    21 Nov 2024 - 14 Oct 2026 (its meta), so design AND final trades are in-sample for the model (PREREG amendment 1)."""
    K, _, Ev, St, Sy = _r57(lo < date(2024, 11, 20))
    model = json.loads((HUNT / "r57" / "results" / "models" / "A2.json").read_text())
    rows = [r for r in Sy.a_rows(St.candidates(lo, hi)) if r["setup"] != "C4"]
    sc = np.array([Ev.score(model, r["f"]) for r in rows], float)
    thr = np.full(len(rows), float(model["threshold"]))
    return [norm("Solo 2.0", t) for t in Sy.select_a(rows, sc, thr, "logit")]


def strike_of(t: dict, s) -> float | None:
    """The traded strike when the engine does not say (LIQ / Solo: 1-ITM from the last index close before entry)."""
    if t.get("k") is not None:
        return float(t["k"])
    ix = np.asarray(s.ix, float)[:t["er"], 3]
    spot = X._last(ix)
    if not np.isfinite(spot):
        return None
    step = STEP[t["und"]]
    atm = np.floor(spot / step + 0.5) * step
    return float(atm - (1 if t["side"] == "C" else -1) * ITM.get(t["arm"], 0) * step)


def add_features(trades: list) -> dict:
    feats, hist, by_day = {}, {}, {}
    for t in trades:
        u = t["und"]
        if u not in hist:
            ss = sessions(u)
            hist[u] = X.History(ss)
            by_day[u] = {s.day: s for s in ss}
        s = by_day[u].get(t["day"])
        if s is None:
            continue
        feats[X.key(t)] = X.features(s, hist[u], t["er"], t["side"], strike_of(t, s))
    return feats


# ================================================================================================ collect
def cache_path(group: str, period: str) -> Path:
    return OUT / f"{group}_{period}.pkl"


def collect(group: str, period: str):
    if period != "design" and not (RESULTS / "design.json").exists():
        raise SystemExit("filters are not frozen yet: run `design` first")
    lo, hi = PERIODS[period]
    extra = {}
    if group == "orb":
        trades = orb_trades(lo, hi)
    elif group == "liq":
        trades, (S, us) = liq_trades(lo, hi, old=period == "unseen")
        if period != "design":
            extra = liq_rule_runs(S, us, trades, period)
    elif group == "solo":
        trades = solo_trades(lo, hi)
    else:
        raise SystemExit(f"unknown group {group}")
    feats = add_features(trades)
    OUT.mkdir(parents=True, exist_ok=True)
    cache_path(group, period).write_bytes(pickle.dumps(dict(trades=trades, feats=feats, extra=extra)))
    print(group, period, len(trades), "trades", flush=True)


def liq_rule_runs(S, us, trades: list, period: str) -> dict:
    """LIQ's frozen filters applied to the CANDIDATES, then today's walk re-run (as R140), and the random twins
    re-walked the same way. Features of a candidate are read at its own entry row."""
    design = json.loads((RESULTS / "design.json").read_text())
    flts = design["arms"].get("LIQ", {}).get("filters", [])
    if not flts:
        return {}
    by_day = {}
    hist = {}
    rng = np.random.default_rng(SEED)

    def cf(c, key):
        u, d = key[0], key[1]
        if u not in hist:
            ss = sessions(u)
            hist[u], by_day[u] = X.History(ss), {s.day: s for s in ss}
        s = by_day[u].get(d)
        if s is None:
            return {}
        t = dict(arm="LIQ", und=u, day=d, er=int(c["er"]), side=c["side"], k=None)
        return X.features(s, hist[u], t["er"], t["side"], strike_of(t, s))
    cfeat = {(g, j): cf(c, key) for g, (key, cands, _, _) in enumerate(us) for j, c in enumerate(cands)}
    flat = list(cfeat)
    base_ls = X.loss_sum(trades)
    out = {}
    for i, f in enumerate(flts):
        drop = {gj for gj, ff in cfeat.items() if X.skips(f, ff)}
        rule = [norm("LIQ", t) for t in S.walk("LIQ", S.filtered(us, lambda g, j, key, c: (g, j) not in drop))
                if t["und"] in LIQ_UNDS]
        ls = X.loss_sum(rule)
        hits = 0
        for _ in range(TWINS):
            gone = {flat[x] for x in rng.choice(len(flat), size=len(drop), replace=False)} if drop else set()
            tw = [t for t in S.walk("LIQ", S.filtered(us, lambda g, j, key, c: (g, j) not in gone))
                  if t["und"] in LIQ_UNDS]
            hits += X.loss_sum(tw) >= ls
        out[i] = dict(rule=rule, p=(1 + hits) / (1 + TWINS), dropped_candidates=len(drop), base_loss_sum=base_ls)
    return out


def load(group: str, period: str) -> dict:
    p = cache_path(group, period)
    return pickle.loads(p.read_bytes()) if p.exists() else dict(trades=[], feats={}, extra={})


def arm_trades(period: str) -> tuple:
    trades, feats, extra = [], {}, {}
    for g in ("liq", "orb", "solo"):
        c = load(g, period)
        trades += c["trades"]
        feats.update(c["feats"])
        if g == "liq":
            extra = c.get("extra", {})
    return trades, feats, extra


def gated(arm: str, trades: list) -> list:
    """The trades a filter is judged on: the ORB family on the app's index (BANKNIFTY); LIQ and Solo all theirs."""
    tr = [t for t in trades if t["arm"] == arm]
    return [t for t in tr if t["und"] == ORB_GATED_UND] if arm in ORB_ARMS else tr


# ================================================================================================ anatomy
def anatomy(arm: str, trades: list, feats: dict, eds: dict, windows: dict) -> dict:
    tr = gated(arm, trades)
    judge = tuple(p for p in windows if any(X.in_window(t["day"], windows[p]) for t in tr))
    out = dict(overview={p: X.summary([t for t in tr if X.in_window(t["day"], w)]) for p, w in windows.items()},
               judged_on=judge)
    out["exit"] = {p: X.money_by_exit([t for t in tr if X.in_window(t["day"], w)]) for p, w in windows.items()}
    out["bunching"] = {p: X.clustering([t for t in tr if X.in_window(t["day"], w)]) for p, w in windows.items()}
    out["worst20"] = {p: X.worst([t for t in tr if X.in_window(t["day"], w)], feats) for p, w in windows.items()}
    if arm in ("LIQ", "Solo 2.0") or arm in ORB_ARMS:
        out["by_index"] = X.slices([t for t in trades if t["arm"] == arm], lambda t: t["und"], windows, judge)
    fx, steady = {}, []
    for f in X.FEATS:
        ed = eds.get(f, np.array([]))
        sl = X.slices(tr, lambda t, f=f, ed=ed: X.slice_of(f, feats.get(X.key(t), {}).get(f), ed), windows, judge)
        cover = {p: round(sum(1 for t in tr if X.in_window(t["day"], w) and X.slice_of(
            f, feats.get(X.key(t), {}).get(f), ed) is not None) / max(1, sum(1 for t in tr if X.in_window(
                t["day"], w))), 3) for p, w in windows.items()}
        fx[f] = dict(name=X.NAMES[f], edges=[round(float(e), 4) for e in ed], coverage=cover, slices=sl)
        steady += [dict(feature=f, name=X.NAMES[f], slice=s, **{p: r[p] for p in windows})
                   for s, r in sl.items() if r["steady_losing"]]
    out["features"] = fx
    out["steady_losing"] = steady
    return out


def design_edges(arm: str, trades: list, feats: dict) -> dict:
    tr = [t for t in gated(arm, trades) if X.in_window(t["day"], PERIODS["design"])]
    return {f: X.edges([feats.get(X.key(t), {}).get(f) for t in tr]) for f in X.FEATS if f not in X.CATS}


def design() -> dict:
    trades, feats, _ = arm_trades("design")
    out = {"_meta": dict(written=str(date.today()), seed=SEED, note="design only; filters frozen here"), "arms": {}}
    win = dict(HALVES, design=PERIODS["design"])
    for arm in ARMS:
        tr = [t for t in gated(arm, trades) if X.in_window(t["day"], PERIODS["design"])]
        if not tr:
            out["arms"][arm] = dict(rebuilt=False)
            continue
        eds = design_edges(arm, trades, feats)
        cands = X.candidates(tr, feats, eds, HALVES)
        out["arms"][arm] = dict(rebuilt=True, edges={f: [float(e) for e in v] for f, v in eds.items()},
                                filters=X.choose(cands), eligible=len(cands),
                                anatomy=anatomy(arm, [t for t in trades if t["arm"] == arm], feats, eds, win))
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))
    return out


# ================================================================================================ the test (read once)
def test() -> dict:
    if FINAL_FLAG.exists():
        raise SystemExit("the final has been read already (results/FINAL_READ)")
    if not (RESULTS / "design.json").exists():
        raise SystemExit("filters are not frozen yet: run `design` first")
    d = json.loads((RESULTS / "design.json").read_text())
    FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds"))
    rng = np.random.default_rng(SEED)
    data = {p: arm_trades(p) for p in PERIODS}
    trades = [t for p in PERIODS for t in data[p][0]]
    feats = {k: v for p in PERIODS for k, v in data[p][1].items()}
    out = {"_meta": dict(written=str(date.today()), twins=TWINS, seed=SEED), "arms": {}}
    rows = []                                  # (arm, filter index, period, p, skipped, rule, base, before, after)
    for arm in ARMS:
        a = d["arms"].get(arm, {})
        if not a.get("rebuilt"):
            out["arms"][arm] = dict(rebuilt=False)
            continue
        eds = {f: np.array(v) for f, v in a["edges"].items()}
        out["arms"][arm] = dict(anatomy=anatomy(arm, [t for t in trades if t["arm"] == arm], feats, eds, PERIODS),
                                filters=a["filters"], tests={})
        for i, f in enumerate(a["filters"]):
            for per in ("final", "unseen"):
                tr = [t for t in gated(arm, data[per][0]) if X.in_window(t["day"], PERIODS[per])]
                if not tr:
                    out["arms"][arm]["tests"][f"{i}:{per}"] = dict(rebuilt=False)
                    continue
                ex = data[per][2].get(i) if arm == "LIQ" else None
                if ex:
                    rule, p = ex["rule"], ex["p"]
                    skipped = len(tr) - len(rule)
                else:
                    rule = [t for t in tr if not X.skips(f, feats.get(X.key(t), {}))]
                    skipped = len(tr) - len(rule)
                    p = X.twin_p(tr, skipped, X.loss_sum(rule), TWINS, rng)
                rows.append(dict(arm=arm, filter=i, period=per, p=p, skipped=skipped,
                                 rule=X.loss_stats(rule), base=X.loss_stats(tr)))
    qs = X.bh([r["p"] for r in rows])
    for r, q in zip(rows, qs):
        r["q"] = q
        r["gates"] = X.gates(r["rule"], r["base"], r["skipped"], r["p"], q)
        out["arms"][r["arm"]]["tests"][f"{r['filter']}:{r['period']}"] = r
    for arm, a in out["arms"].items():
        for i, f in enumerate(a.get("filters", [])):
            per = [a["tests"].get(f"{i}:{p}", {}) for p in ("final", "unseen")]
            f["passed"] = all(x.get("gates", {}).get("passed") for x in per)
            f["win_rate"] = {p: dict(before=x.get("base", {}).get("win_rate"), after=x.get("rule", {}).get("win_rate"),
                                     meets_65=(x.get("rule", {}).get("win_rate") or 0) >= 0.65)
                             for p, x in zip(("final", "unseen"), per)}
    (RESULTS / "test.json").write_text(json.dumps(out, indent=1, default=str))
    write_pvalues(rows, qs)
    return out


def write_pvalues(rows: list, qs: list, note: str = ""):
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "pvalues.json").write_text(json.dumps(dict(
        study="R144b", group="Liquidity 15+5, ORB family, Solo 2.0", note=note,
        bh_within_group=dict(method="Benjamini-Hochberg", q=X.GATE_Q),
        tests=[dict(id=f"r144b-{r['arm']}-f{r['filter']}-{r['period']}", arm=r["arm"], period=r["period"],
                    raw_p=r["p"], q_within_group=q) for r, q in zip(rows, qs)]), indent=1))


if __name__ == "__main__":
    cmd = (sys.argv[1:] or [""])[0]
    if cmd == "status":
        print(json.dumps(status(), indent=1))
    elif cmd == "build-solo-old":
        build_solo_old()
    elif cmd == "collect":
        collect(sys.argv[2], sys.argv[3])
    elif cmd == "design":
        design()
    elif cmd == "test":
        test()
    else:
        print(__doc__)
