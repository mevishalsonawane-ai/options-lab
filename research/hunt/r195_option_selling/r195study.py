"""R195 study (PREREG.md sections 5-6): design (walk-forward) trades, twins, the ONE holdout read, gates and BH.

    python3 -I research/hunt/r195_option_selling/r195study.py design CID [CID ...]   # design trades + twins, cached
    python3 -I research/hunt/r195_option_selling/r195study.py slip2 CID [CID ...]    # design at 2x slippage ticks
    python3 -I research/hunt/r195_option_selling/r195study.py dgates                 # design gates -> results/design.json
    python3 -I research/hunt/r195_option_selling/r195study.py holdout                # ONCE: writes HOLDOUT_READ
    python3 -I research/hunt/r195_option_selling/r195study.py final                  # every gate + BH -> results/final.json

Trade caches live in ~/.cache/iraalgo/r195 (never in git). Each design run covers named candidates, so a run stays
inside the 30-minute limit and results are written as they finish.
"""
from __future__ import annotations

import json
import math
import pickle
import sys
from collections import Counter
from datetime import date
from multiprocessing import Pool
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r195cfg as K  # noqa: E402
import r195data as RD  # noqa: E402
import r195engine as E  # noqa: E402
import r195rules as RU  # noqa: E402

CACHE = K.OUT / "trades"
_G: dict = {}           # per-process state (stores are loaded before the pool forks)


def setup(und: str):
    if und in _G:
        return _G[und]
    store = RD.load(und)
    if "vix" not in _G:
        _G["vix"] = RD.vix_days()
    Dl = RU.daily(store, _G["vix"])
    _G[und] = (store, Dl, E.expiry_of(store))
    return _G[und]


def next_session(Dl, d):
    i = Dl.pos[d]
    return Dl.days[i + 1] if i + 1 < len(Dl.days) else None


def trade(cid: str, d: date, decide=None, side=None, mult: float = 1.0):
    und = RU.und_of(cid)
    store, Dl, exp = setup(und)
    D = store.get(d)
    if D is None or D.atm is None:
        return None
    sp = RU.spec_for(cid, D, d, Dl, exp[d], decide, side)
    if sp is None:
        return None
    if RU.fam(cid) == "P1":
        dn = next_session(Dl, d)
        if dn is None or dn not in store or store[dn].expiry or K.period(dn) != K.period(d):
            return None              # a P1 trade never straddles the design / holdout boundary
        return E.run(D, und, d, sp, exp[d], False, mult, D_next=store[dn], d_next=dn, exit_next=RU.R0920 + 1)
    return E.run(D, und, d, sp, exp[d], bool(D.expiry), mult)


def days_of(cid: str, part: str) -> list:
    store, Dl, _ = setup(RU.und_of(cid))
    return [d for d in sorted(store) if K.period(d) == part and store[d].atm is not None]


def _work(args):
    cid, ds, mode, mult = args
    out = []
    f = RU.fam(cid)
    for d in ds:
        if mode == "rule":
            out.append((d, trade(cid, d, mult=mult)))
        elif mode == "timing":
            out.append((d, {r: (t["net"] if (t := trade(cid, d, decide=r)) else None) for r in RU.GRID[f]}))
        elif mode == "side":
            out.append((d, {s: (t["net"] if (t := trade(cid, d, side=s)) else None) for s in (True, False)}))
    return out


def _pool_map(cid, ds, mode, mult=1.0, chunks=24):
    setup(RU.und_of(cid))
    parts = [ds[i::chunks] for i in range(chunks)]
    with Pool(4) as p:
        res = p.map(_work, [(cid, x, mode, mult) for x in parts])
    return dict(sorted((d, v) for r in res for d, v in r))


def _save(name, obj):
    CACHE.mkdir(parents=True, exist_ok=True)
    with open(CACHE / name, "wb") as f:
        pickle.dump(obj, f)


def _load(name):
    with open(CACHE / name, "rb") as f:
        return pickle.load(f)


def run_part(cid: str, part: str, mult: float = 1.0) -> list:
    """The rule's trades in a period (N1 via r195nn on S4's trades)."""
    if RU.fam(cid) == "N1":
        import r195nn
        return r195nn.trades(part)
    got = _pool_map(cid, days_of(cid, part), "rule", mult)
    return [t for t in got.values() if t is not None]


def design(cids: list):
    for cid in cids:
        tr = run_part(cid, "wf")
        _save(f"wf_{cid}.pkl", tr)
        kind = RU.TWIN.get(RU.fam(cid))
        if kind == "timing":
            _save(f"twin_wf_{cid}.pkl", _pool_map(cid, [t["day"] for t in tr], "timing"))
        elif kind == "side":
            _save(f"twin_wf_{cid}.pkl", _pool_map(cid, [t["day"] for t in tr], "side"))
        print(cid, len(tr), "trades", flush=True)


def slip2(cids: list):
    for cid in cids:
        if RU.fam(cid) == "N1":
            import r195nn
            tr = r195nn.trades("wf", mult=2.0)
        else:
            tr = run_part(cid, "wf", 2.0)
        _save(f"wf2x_{cid}.pkl", tr)
        print(cid, "2x", round(sum(t["net"] for t in tr)), flush=True)


# ------------------------------------------------------------------------------------------------ statistics
def stats(tr: list, lo: date | None = None, hi: date | None = None) -> dict:
    tr = [t for t in tr if (lo is None or t["day"] >= lo) and (hi is None or t["day"] <= hi)]
    if not tr:
        return dict(n=0, net=0.0)
    tr = sorted(tr, key=lambda t: t["day"])
    n = np.array([t["net"] for t in tr])
    eq = np.cumsum(n)
    dd = np.maximum.accumulate(np.maximum(eq, 0)) - eq
    months = Counter()
    for t in tr:
        months[(t["day"].year, t["day"].month)] += t["net"]
    wm = min(months.items(), key=lambda kv: kv[1])
    wd = int(np.argmin(n))
    mg = np.array([t["margin"] for t in tr])
    yrs = max((tr[-1]["day"] - tr[0]["day"]).days / 365.25, 1 / 12)
    dd_breach = next((str(tr[j]["day"]) for j in range(len(tr)) if dd[j] > K.DD_STOP), None)
    return dict(
        n=len(tr), win=round(float((n > 0).mean()), 3), net=round(float(n.sum())),
        avg_win=round(float(n[n > 0].mean())) if (n > 0).any() else 0, avg_loss=round(float(n[n <= 0].mean())) if (n <= 0).any() else 0,
        max_dd=round(float(dd.max())), worst_month=round(wm[1]), worst_month_at=f"{wm[0][0]}-{wm[0][1]:02d}",
        worst_day=round(float(n[wd])), worst_day_at=str(tr[wd]["day"]),
        net_wo_top5=round(float(np.sort(n)[:-5].sum())) if len(n) > 5 else round(float(min(n.sum(), 0))),
        margin_mean=round(float(mg.mean())), margin_p95=round(float(np.percentile(mg, 95))),
        rom_year=round(float(n.sum() / mg.mean() / yrs), 3) if mg.mean() > 0 else None,
        months_le_13k=sum(1 for v in months.values() if v <= K.MONTH_STOP), dd_over_40k_from=dd_breach,
        affordable_share=round(float((mg <= K.ACCOUNT).mean()), 3),
        why=dict(Counter(t["why"] for t in tr)), stale_trades=int(sum(1 for t in tr if t["stale_legs"] or t["model_fills"])),
        first=str(tr[0]["day"]), last=str(tr[-1]["day"]),
        credit_mean=round(float(np.mean([t["credit"] for t in tr]))),
        charges_mean=round(float(np.mean([t["charges"] for t in tr]))))


def by_year(tr: list) -> dict:
    out = {}
    for y in sorted({t["day"].year for t in tr}):
        s = [t["net"] for t in tr if t["day"].year == y]
        out[str(y)] = dict(n=len(s), net=round(sum(s)), win=round(float(np.mean(np.array(s) > 0)), 3))
    return out


def boot_p(tr: list, n: int = 10_000) -> float:
    x = np.array([t["net"] for t in tr])
    if len(x) < 2:
        return 1.0
    rng = np.random.default_rng(K.SEED)
    m = x[rng.integers(0, len(x), (n, len(x)))].mean(axis=1)
    return float((m <= 0).mean())


def twin_p(cid: str, tr: list, part: str = "wf") -> dict:
    """PREREG 6.5: empirical one-sided p and the normal-tail p of the rule's net against 1,000 twins."""
    rng = np.random.default_rng(K.SEED)
    kind = RU.TWIN.get(RU.fam(cid))
    real = sum(t["net"] for t in tr)
    sums = np.zeros(K.N_TWINS)
    if kind in ("timing", "side"):
        g = _load(f"twin_{part}_{cid}.pkl")
        for d, row in g.items():
            vals = np.array([v for v in row.values() if v is not None])
            if len(vals):
                sums += vals[rng.integers(0, len(vals), K.N_TWINS)]
    elif kind == "days":
        base = _load(f"{part}_{RU.BASE[cid]}.pkl")
        if RU.fam(cid) == "N1":
            lo = min(t["day"] for t in tr) if tr else None
            base = [t for t in base if lo is None or t["day"] >= date(lo.year, 1, 1)]
        bn = np.array([t["net"] for t in base])
        for j in range(K.N_TWINS):
            sums[j] = bn[rng.choice(len(bn), size=min(len(tr), len(bn)), replace=False)].sum()
    else:
        return dict(kind=None, p_emp=None, p_norm=None)
    p_emp = (1 + int((sums >= real).sum())) / (K.N_TWINS + 1)
    sd = float(sums.std(ddof=1))
    from scipy.stats import norm
    p_norm = float(norm.sf((real - sums.mean()) / sd)) if sd > 0 else (0.0 if real > sums.mean() else 1.0)
    return dict(kind=kind, p_emp=round(p_emp, 4), p_norm=p_norm, twin_mean=round(float(sums.mean())),
                twin_sd=round(sd))


def bh(ps: dict, m: int) -> dict:
    """Benjamini-Hochberg q for our tests among m (the other m - len(ps) entries count as p = 1)."""
    items = sorted(ps.items(), key=lambda kv: kv[1])
    allp = [p for _, p in items] + [1.0] * (m - len(items))
    q = [0.0] * len(allp)
    run = 1.0
    for i in range(len(allp) - 1, -1, -1):
        run = min(run, allp[i] * m / (i + 1))
        q[i] = run
    return {k: min(1.0, q[i]) for i, (k, _) in enumerate(items)}


def design_gates():
    out = {}
    for cid in RU.TESTS:
        p = CACHE / f"wf_{cid}.pkl"
        if not p.exists():
            continue
        tr = _load(p.name)
        s = stats(tr)
        post = stats(tr, lo=K.REGIME)
        tw = twin_p(cid, tr)
        bp = boot_p(tr)
        s2 = (CACHE / f"wf2x_{cid}.pkl")
        net2 = round(sum(t["net"] for t in _load(s2.name))) if s2.exists() else None
        out[cid] = dict(design=s, post_regime=post, years=by_year(tr), twin=tw, boot_p=bp, net_2x_slip=net2,
                        p_bh=max(bp, tw["p_norm"]) if tw.get("p_norm") is not None else bp)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str) + "\n")
    return out


# ------------------------------------------------------------------------------------------------ holdout (once)
def holdout():
    if K.HOLDOUT_FLAG.exists():
        raise PermissionError("the holdout was already read (results/HOLDOUT_READ); PREREG.md allows one read")
    missing = [c for c in RU.TESTS if not (CACHE / f"wf_{c}.pkl").exists()]
    if missing:
        raise RuntimeError(f"design incomplete: {missing}")
    K.HOLDOUT_FLAG.write_text(f"R195 holdout 1 Jan - 13 Nov 2026 read once on {date.today()}\n")
    order = ["S4-NIFTY"] + [c for c in RU.TESTS if c != "S4-NIFTY"]   # N1 needs S4's holdout trades
    for cid in order:
        tr = run_part(cid, "ho")
        _save(f"ho_{cid}.pkl", tr)
        print(cid, len(tr), round(sum(t["net"] for t in tr)), flush=True)


# ------------------------------------------------------------------------------------------------ the final gate
TAIL_DAYS = [date(2022, 2, 1), date(2023, 2, 1), date(2024, 2, 1), date(2024, 6, 4), date(2024, 6, 5),
             date(2024, 7, 23), date(2025, 2, 1), date(2026, 2, 1)]


def tail(cid: str, tr: list) -> dict:
    und = RU.und_of(cid)
    store, Dl, _ = setup(und)
    gaps = []
    for i in range(1, len(Dl.days)):
        if Dl.days[i] >= K.START and np.isfinite(Dl.open_[i]) and np.isfinite(Dl.close[i - 1]):
            gaps.append((abs(Dl.open_[i] / Dl.close[i - 1] - 1), Dl.days[i], Dl.open_[i] / Dl.close[i - 1] - 1))
    gaps.sort(reverse=True)
    by = {t["day"]: t["net"] for t in tr}
    out = {}
    for d in TAIL_DAYS:
        if d in Dl.pos:
            out[str(d)] = round(by[d]) if d in by else "no trade"
    for _, d, g in gaps[:5]:
        out[f"{d} gap {g * 100:+.2f}%"] = round(by[d]) if d in by else "no trade"
    return out


def final():
    if not K.HOLDOUT_FLAG.exists():
        raise PermissionError("run the holdout first")
    dj = json.loads((K.RESULTS / "design.json").read_text())
    m = K.M_PRIOR + len(RU.TESTS)
    q = bh({c: dj[c]["p_bh"] for c in RU.TESTS}, m)
    out = {}
    for cid in RU.TESTS:
        wf, ho = _load(f"wf_{cid}.pkl"), _load(f"ho_{cid}.pkl")
        d, hs, al = dj[cid]["design"], stats(ho), stats(wf + ho)
        post_all = stats(wf + ho, lo=K.REGIME)
        fails = []
        if not (d.get("net", 0) > 0 and dj[cid]["post_regime"].get("net", 0) > 0):
            fails.append("1 walk-forward")
        if not hs.get("net", 0) > 0:
            fails.append("2 holdout")
        if d.get("n", 0) < 40 or hs.get("n", 0) < 40:
            fails.append("3 trades")
        if not (d.get("net_wo_top5", 0) > 0 and hs.get("net_wo_top5", 0) > 0):
            fails.append("4 without top 5")
        tp = dj[cid]["twin"].get("p_emp")
        if tp is None or tp >= 0.05:
            fails.append("5 twin")
        if q[cid] > 0.10:
            fails.append("6 BH")
        if al.get("worst_month", 0) < K.MONTH_STOP or al.get("max_dd", 0) > K.DD_STOP:
            fails.append("7 account stop")
        if not (dj[cid]["net_2x_slip"] or 0) > 0:
            fails.append("8 2x slippage")
        afford = hs.get("n", 0) > 0 and hs.get("affordable_share", 0) >= 0.95
        out[cid] = dict(design=d, post_regime_design=dj[cid]["post_regime"], holdout=hs, all=al, post_regime_all=post_all,
                        years=dj[cid]["years"], twin=dj[cid]["twin"], boot_p=dj[cid]["boot_p"], p_bh=dj[cid]["p_bh"],
                        bh_q=q[cid], net_2x_slip=dj[cid]["net_2x_slip"], failed=fails,
                        verdict=("baseline" if cid in RU.BASELINES else ("PASS" if not fails else "fail")),
                        possible_at_56k=bool(afford), tail=tail(cid, wf + ho))
    (K.RESULTS / "final.json").write_text(json.dumps(dict(m=m, tests=out), indent=1, default=str) + "\n")
    return out


if __name__ == "__main__":
    a = sys.argv[1:] or [""]
    if a[0] == "design":
        design(a[1:])
    elif a[0] == "slip2":
        slip2(a[1:])
    elif a[0] == "dgates":
        for k, v in design_gates().items():
            print(k, v["design"].get("n"), v["design"].get("net"), v["twin"].get("p_emp"), flush=True)
    elif a[0] == "holdout":
        holdout()
    elif a[0] == "final":
        for k, v in final().items():
            print(k, v["verdict"], v["failed"], flush=True)
    else:
        raise SystemExit(__doc__)
