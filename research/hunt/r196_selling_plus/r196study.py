"""R196 study (PREREG.md sections 3-5): design trades, twins, the 2026 second look, gates, BH; the forward read later.

    python3 -I research/hunt/r196_selling_plus/r196study.py design [CID ...]   # design trades + twins (+ 2x slippage)
    python3 -I research/hunt/r196_selling_plus/r196study.py second              # 2026 second look (R195 read 2026 once)
    python3 -I research/hunt/r196_selling_plus/r196study.py final               # gates + BH -> results/final.json
    python3 -I research/hunt/r196_selling_plus/r196study.py forward             # ONCE, when 40+ forward trades exist

Caches in ~/.cache/iraalgo/r196 (never in git). R195's stores and its S1 / T2 trade caches are read, not changed.
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date
from multiprocessing import Pool
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r195_option_selling"))
import r195cfg as K  # noqa: E402
import r195engine as E  # noqa: E402
import r195rules as RU  # noqa: E402
import r195study as S5  # noqa: E402
import r196engine as E6  # noqa: E402

OUT = K.OUT.parent / "r196"
RESULTS = HERE / "results"
FORWARD_FLAG = RESULTS / "FORWARD_READ"
FORWARD_START = date(2026, 11, 16)
R1030 = K.row(10, 30)
UNDS = K.UNDS
CANDIDATES = (["A1-NIFTY", "A2-NIFTY", "A3-NIFTY", "A4-NIFTY", "A5-NIFTY", "A6-NIFTY"]
              + [f"A7-{u}" for u in UNDS] + ["PS1-NIFTY", "PS2-NIFTY", "PS3-BANKNIFTY"])
HELPERS = [f"H7-{u}" for u in UNDS]            # S1 + stop to cost + lock, no VIX filter: A7's random-day twin base
M_PRIOR = 4807
LOCK = (0.30, 0.15)


def fam(cid):
    return cid.split("-")[0]


def und_of(cid):
    return cid.split("-", 1)[1]


def shock(Dl, i) -> bool:
    """A6: the 09:15 open more than 0.6% from yesterday's close, or yesterday's range >= 1.5x its 20-session average."""
    if i < 22:
        return True
    gap = Dl.open_[i] / Dl.close[i - 1] - 1
    rng = Dl.high - Dl.low
    base = np.nanmean(rng[i - 21: i - 1])
    return bool(not np.isfinite(gap) or abs(gap) > 0.006 or not np.isfinite(base) or rng[i - 1] >= 1.5 * base)


def trade(cid, d, decide=None, side=None, mult=1.0):
    f, und = fam(cid), und_of(cid)
    store, Dl, exp = S5.setup(und)
    D = store.get(d)
    i = Dl.pos.get(d)
    if D is None or D.atm is None or i is None:
        return None
    ex, xday = exp[d], bool(D.expiry)
    if f.startswith("PS"):
        if xday or (f == "PS2" and not RU.vix_ok(Dl, i)):
            return None
        r = RU.R0920 if decide is None else decide
        k0 = RU.atm(D, und, r)
        if k0 is None:
            return None
        st = K.STEP[und]
        bull = True if side is None else side
        legs = ([E.Leg("P", k0 - 2 * st, -1), E.Leg("P", k0 - 6 * st, 1)] if bull
                else [E.Leg("C", k0 + 2 * st, -1), E.Leg("C", k0 + 6 * st, 1)])
        return E.run(D, und, d, E.Spec(legs, r, {0: ("maxloss", 0.5)}, None, cid), ex, False, mult)
    if f == "A6" and shock(Dl, i):
        return None
    if f == "A7" and not RU.vix_ok(Dl, i):
        return None
    r = (R1030 if f == "A4" else RU.R0920) if decide is None else decide
    k0 = RU.atm(D, und, r)
    if k0 is None:
        return None
    st = K.STEP[und]
    legs = [("C", k0 + 2 * st), ("P", k0 - 2 * st)] if f == "A5" else [("C", k0), ("P", k0)]
    kw = dict(A1=dict(leg_stop=1.25, sl_to_cost=True), A2=dict(comb_stop=1.20), A3=dict(leg_stop=1.25, lock=LOCK),
              A4=dict(leg_stop=1.25), A5=dict(leg_stop=1.30), A6=dict(leg_stop=1.25),
              A7=dict(leg_stop=1.25, sl_to_cost=True, lock=LOCK), H7=dict(leg_stop=1.25, sl_to_cost=True, lock=LOCK))[f]
    return E6.run_short_pair(D, und, d, legs, r, ex, xday, mult=mult, tag=cid, **kw)


GRID = {f: range(RU.R0920, K.row(13, 0) + 1, 5) for f in ("A1", "A2", "A3", "A5")}
GRID["A4"] = range(R1030, K.row(13, 0) + 1, 5)
TWIN = dict(A1="timing", A2="timing", A3="timing", A4="timing", A5="timing", A6="days", A7="days",
            PS1="side", PS2="side", PS3="side")


def days_of(und, part):
    store, _, _ = S5.setup(und)
    return [d for d in sorted(store) if K.period(d) == part and store[d].atm is not None]


def _work(args):
    cid, ds, mode, mult = args
    out = []
    for d in ds:
        if mode == "rule":
            out.append((d, trade(cid, d, mult=mult)))
        elif mode == "timing":
            out.append((d, {r: (t["net"] if (t := trade(cid, d, decide=r)) else None) for r in GRID[fam(cid)]}))
        else:
            out.append((d, {s: (t["net"] if (t := trade(cid, d, side=s)) else None) for s in (True, False)}))
    return out


def pmap(cid, ds, mode, mult=1.0):
    S5.setup(und_of(cid))
    parts = [ds[i::24] for i in range(24)]
    with Pool(4) as p:
        res = p.map(_work, [(cid, x, mode, mult) for x in parts])
    return dict(sorted((d, v) for r in res for d, v in r))


def save(name, obj):
    OUT.mkdir(parents=True, exist_ok=True)
    with open(OUT / name, "wb") as f:
        pickle.dump(obj, f)


def load(name):
    with open(OUT / name, "rb") as f:
        return pickle.load(f)


def design(cids):
    for cid in cids:
        ds = days_of(und_of(cid), "wf")
        tr = [t for t in pmap(cid, ds, "rule").values() if t]
        save(f"wf_{cid}.pkl", tr)
        if cid in CANDIDATES:
            save(f"wf2x_{cid}.pkl", [t for t in pmap(cid, ds, "rule", 2.0).values() if t])
            k = TWIN[fam(cid)]
            if k in ("timing", "side"):
                save(f"twin_wf_{cid}.pkl", pmap(cid, [t["day"] for t in tr], k))
        print(cid, len(tr), round(sum(t["net"] for t in tr)), flush=True)


def second():
    for cid in CANDIDATES:
        tr = [t for t in pmap(cid, days_of(und_of(cid), "ho"), "rule").values() if t]
        save(f"ho_{cid}.pkl", tr)
        print(cid, len(tr), round(sum(t["net"] for t in tr)), flush=True)


def twin_p(cid, tr):
    rng = np.random.default_rng(K.SEED)
    real = sum(t["net"] for t in tr)
    sums = np.zeros(K.N_TWINS)
    k = TWIN[fam(cid)]
    if k in ("timing", "side"):
        for row in load(f"twin_wf_{cid}.pkl").values():
            v = np.array([x for x in row.values() if x is not None])
            if len(v):
                sums += v[rng.integers(0, len(v), K.N_TWINS)]
    else:
        base = load(f"wf_H7-{und_of(cid)}.pkl") if fam(cid) == "A7" else S5._load(f"wf_S1-{und_of(cid)}.pkl")
        bn = np.array([t["net"] for t in base])
        for j in range(K.N_TWINS):
            sums[j] = bn[rng.choice(len(bn), size=min(len(tr), len(bn)), replace=False)].sum()
    from scipy.stats import norm
    sd = float(sums.std(ddof=1))
    return dict(kind=k, p_emp=round((1 + int((sums >= real).sum())) / (K.N_TWINS + 1), 4),
                p_norm=float(norm.sf((real - sums.mean()) / sd)) if sd > 0 else 1.0, twin_mean=round(float(sums.mean())))


def paired_p(tr, base, n=10_000):
    """Day-bootstrap p that the mean daily (variant - base) is <= 0 over the base's days (no variant trade = 0)."""
    v = {t["day"]: t["net"] for t in tr}
    diff = np.array([v.get(t["day"], 0.0) - t["net"] for t in base])
    rng = np.random.default_rng(K.SEED)
    m = diff[rng.integers(0, len(diff), (n, len(diff)))].mean(axis=1)
    return float((m <= 0).mean()), round(float(diff.sum()))


def final():
    m = M_PRIOR + len(CANDIDATES)
    rows, ps = {}, {}
    for cid in CANDIDATES:
        wf, ho, wf2 = load(f"wf_{cid}.pkl"), load(f"ho_{cid}.pkl"), load(f"wf2x_{cid}.pkl")
        d, post, h, al = S5.stats(wf), S5.stats(wf, lo=K.REGIME), S5.stats(ho), S5.stats(wf + ho)
        tw, bp = twin_p(cid, wf), S5.boot_p(wf)
        base = S5._load(f"wf_{'T2-NIFTY' if fam(cid).startswith('PS') else 'S1-' + und_of(cid)}.pkl")
        days = {t["day"] for t in wf}
        b_same = S5.stats([t for t in base if t["day"] in days])
        pp, dsum = paired_p(wf, base) if not fam(cid).startswith("PS") else (None, None)
        ps[cid] = max(bp, tw["p_norm"], pp if pp is not None else 0.0)
        fails = []
        if not (d.get("net", 0) > 0 and post.get("net", 0) > 0):
            fails.append("1 design")
        if not h.get("net", 0) > 0:
            fails.append("2 2026")
        if d.get("n", 0) < 40 or h.get("n", 0) < 40:
            fails.append("3 trades")
        if not (d.get("net_wo_top5", 0) > 0 and h.get("net_wo_top5", 0) > 0):
            fails.append("4 without top 5")
        if tw["p_emp"] >= 0.05:
            fails.append("5 twin")
        if al.get("worst_month", 0) < K.MONTH_STOP or al.get("max_dd", 0) > K.DD_STOP:
            fails.append("7 account stop")
        if not sum(t["net"] for t in wf2) > 0:
            fails.append("8 2x slippage")
        aims = dict(net=d["net"] > b_same.get("net", 0), win=d["win"] > b_same.get("win", 0),
                    dd=d["max_dd"] < b_same.get("max_dd", 0))
        if not all(aims.values()):
            fails.append("9 aims vs base")
        rows[cid] = dict(design=d, post_regime=post, second_look_2026=h, all=al, years=S5.by_year(wf), twin=tw, boot_p=bp,
                         paired_p=pp, paired_gain_vs_base=dsum, base_same_days=b_same, aims=aims,
                         net_2x_slip=round(sum(t["net"] for t in wf2)), fails=fails,
                         possible_at_56k=bool(h.get("n", 0) and h.get("affordable_share", 0) >= 0.95))
    q = S5.bh(ps, m)
    for cid in CANDIDATES:
        rows[cid]["p_bh"], rows[cid]["bh_q"] = ps[cid], q[cid]
        if q[cid] > 0.10:
            rows[cid]["fails"].append("6 BH")
            rows[cid]["fails"].sort(key=lambda x: int(x.split()[0]))
        rows[cid]["verdict"] = "provisional (needs the forward read)" if not rows[cid]["fails"] else "fail"
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "final.json").write_text(json.dumps(dict(m=m, tests=rows), indent=1, default=str) + "\n")
    return rows


def forward():
    """PREREG 3: the clean holdout, sessions from 16 Nov 2026, read ONCE when 40+ trades exist."""
    if FORWARD_FLAG.exists():
        raise PermissionError("the forward window was already read (results/FORWARD_READ)")
    store, _, _ = S5.setup("NIFTY")
    if not [d for d in store if d >= FORWARD_START]:
        raise RuntimeError("no session after 16 Nov 2026 in the stores yet: refresh the lake and rebuild first")
    raise RuntimeError("forward read not wired until the data exists; extend K.period first (PREREG 3)")


if __name__ == "__main__":
    a = sys.argv[1:] or [""]
    if a[0] == "design":
        design(a[1:] or (HELPERS + CANDIDATES))
    elif a[0] == "second":
        second()
    elif a[0] == "final":
        for k, v in final().items():
            print(k, v["verdict"], v["fails"], flush=True)
    elif a[0] == "forward":
        forward()
    else:
        raise SystemExit(__doc__)
