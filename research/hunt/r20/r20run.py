"""R20 runner (PREREG.md): Step 1 facts, the 7 variants + baseline on design, then the ONE holdout read.

    IRAALGO_CACHE=<cache> python3 research/hunt/r20/r20run.py design     # facts + design table -> results/design.json
    IRAALGO_CACHE=<cache> python3 research/hunt/r20/r20run.py holdout    # once only: -> results/holdout.json

Data, fills, charges and the ORB exits are R17's (research/hunt/r17: data.py, arms.py), unchanged. The holdout flag lives
in R17_OUT (<cache>/r17/r20_holdout_read); a second `holdout` run refuses. 13 Oct 2026.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "r17"))

import arms as A  # noqa: E402  (R17: fills, charges, ORB exits; sets sys.path)
import config as K  # noqa: E402
import data as DATA  # noqa: E402
import r20lib as L  # noqa: E402

RESULTS = os.path.join(HERE, "results")
FLAG = K.OUT / "r20_holdout_read"
TWINS = 1000
SEED = 20
RULES = {  # stop / target premium points, premium floor, strike step (PREREG: NIFTY halves the points)
    "BANKNIFTY": dict(stop=40.0, tgt=40.0, floor=40.0, step=100),
    "NIFTY": dict(stop=20.0, tgt=20.0, floor=20.0, step=50),
}


class DayCtx:
    """One session: its 5-minute bars, ATR14, day strike, and a cache of option trades by (bar, side)."""

    def __init__(self, d, atr):
        self.d = d
        self.atr = atr
        self.bo, self.bh, self.bl, self.bc = L.bars5(d.ix)
        self.r = RULES[d.und]
        st = self.r["step"]
        self.strike = float(np.floor(self.bc[1] / st + 0.5) * st)   # the 09:20 bar's close, half-up
        self.cache: dict = {}

    def trade(self, k: int, s: int):
        """The ORB option trade decided on bar k's close (R17 run_orb's entry and sim_fixed_pts exits). (record, exit row)
        or None when refused (no contract, no print within 3 minutes, premium at or below the floor)."""
        if (k, s) in self.cache:
            return self.cache[(k, s)]
        d = self.d
        out = None
        exp, a = A.contract(d, self.strike, s, same_day_ok=False)
        if a is not None:
            sig_row = 5 * k + 4
            c0, px = A.next_open(a, sig_row + 1)
            if c0 <= sig_row + 3 and px > self.r["floor"] and sig_row + 1 < A.SQ_ROW:
                e = A.buy_fill(px, a, c0, d.lot)
                x, xr, why = A.sim_fixed_pts(a, c0, e, self.r["stop"], self.r["tgt"], d.lot, d.lot)
                ch = A.charges(e, x, d.lot)
                rec = dict(day=d.day.isoformat(), k=k, side=s, entry=e, exit=x, exit_row=int(xr), why=why,
                           net=(x - e) * d.lot - ch, charges=ch)
                out = (rec, int(xr))
        self.cache[(k, s)] = out
        return out


def contexts(und: str):
    days = DATA.load(und)
    hlc = [L.daily_hlc(d.ix) for d in days]
    return [DayCtx(d, L.atr14(hlc, i)) for i, d in enumerate(days) if np.isfinite(L.atr14(hlc, i))]


def run_variant(v: L.Variant, ctxs):
    """Per session: (trades, plan) for the variant."""
    out = []
    for c in ctxs:
        p = L.plan_for(v, c.bh, c.bl, c.atr)
        if p is None:
            out.append(([], None))
            continue
        out.append((L.take(L.signals(p, c.bh, c.bl, c.bc), p, c.trade), p))
    return out


def daily(res) -> np.ndarray:
    return np.array([sum(t["net"] for t in tr) for tr, _ in res])


def score(res, idx) -> dict:
    sub = [res[i] for i in idx]
    return L.metrics(daily(sub), sum(len(t) for t, _ in sub))


def split(ctxs, und):
    cut = K.holdout_start(und, [c.d.day for c in ctxs])
    des = [i for i, c in enumerate(ctxs) if c.d.day < cut]
    hol = [i for i, c in enumerate(ctxs) if c.d.day >= cut]
    return des, hol, cut


# ------------------------------------------------------------------------------------------------ Step 1 facts
def wbin(x):
    return "<0.5" if x < 0.5 else "0.5-0.75" if x < 0.75 else "0.75-1.0" if x < 1.0 else ">=1.0"


def vbin(v):
    return "n/a" if not np.isfinite(v) else "<13" if v < 13 else "13-16" if v < 16 else ">=16"


def facts(ctxs, idx) -> dict:
    rows = []
    for i in idx:
        c = ctxs[i]
        f = L.day_facts(c.bh, c.bl, c.bc)
        late_pts = late_net = None
        if f["late_k"] is not None:
            late_pts = float(f["late_s"] * (A.next_open(c.d.ix, A.SQ_ROW)[1] - c.bc[f["late_k"]]))
            t = c.trade(f["late_k"], f["late_s"])
            late_net = t[0]["net"] if t else None
        rows.append(dict(day=c.d.day.isoformat(), w_atr=f["w45"] / c.atr, w45=f["w45"], vix=c.d.vix_prev,
                         expiry=bool(c.d.expiry == c.d.day), orb=f["orb"], any=f["orb"] or f["sweep"] or f["fade"],
                         late_k=f["late_k"], late_pts=late_pts, late_net=late_net))

    def tab(key):
        g: dict = {}
        for r in rows:
            g.setdefault(key(r), []).append(r)
        return {k: dict(days=len(v), no_orb=sum(not r["orb"] for r in v), no_orb_pct=round(100 * np.mean([not r["orb"] for r in v]), 1),
                        no_family=sum(not r["any"] for r in v)) for k, v in sorted(g.items())}

    no = [r for r in rows if not r["orb"]]
    late = [r for r in no if r["late_k"] is not None]
    lp = [r["late_pts"] for r in late]
    ln = [r["late_net"] for r in late if r["late_net"] is not None]
    return dict(
        sessions=len(rows), no_orb=len(no), no_orb_pct=round(100 * len(no) / max(1, len(rows)), 1),
        no_family=sum(not r["any"] for r in rows),
        median_w_atr=float(np.median([r["w_atr"] for r in rows])) if rows else None,
        by_width=tab(lambda r: wbin(r["w_atr"])), by_vix=tab(lambda r: vbin(r["vix"])),
        by_expiry=tab(lambda r: "expiry" if r["expiry"] else "other"),
        late_breaks=len(late), late_pts_mean=float(np.mean(lp)) if lp else None,
        late_pts_median=float(np.median(lp)) if lp else None, late_pts_pos=sum(x > 0 for x in lp),
        late_trades=len(ln), late_net_total=float(np.sum(ln)) if ln else 0.0,
        late_net_mean=float(np.mean(ln)) if ln else None, late_net_pos=sum(x > 0 for x in ln),
        days=rows)


# ------------------------------------------------------------------------------------------------ design and holdout
def design(und: str) -> dict:
    ctxs = contexts(und)
    des, _, cut = split(ctxs, und)
    ctxs = ctxs[:des[-1] + 1]                    # design never even simulates a holdout session
    out = dict(und=und, cut=cut.isoformat(), design_sessions=len(des),
               design_from=ctxs[des[0]].d.day.isoformat(), design_to=ctxs[des[-1]].d.day.isoformat(),
               facts=facts(ctxs, des), variants={})
    bl = L.blocks(len(des))
    for v in L.VARIANTS:
        res = run_variant(v, ctxs)
        sc = score(res, des)
        sc["blocks"] = [score(res, des[a:b])["net"] for a, b in bl]
        sc["wide_days"] = sum(1 for i in des if res[i][1] is not None and res[i][1].wide)
        out["variants"][v.name] = sc
    base = out["variants"]["V0"]["net"]
    for name, sc in out["variants"].items():
        sc["G1"] = bool(sc["net"] > 0 and (name == "V0" or sc["net"] > base))
        sc["G2"] = bool(sum(x > 0 for x in sc["blocks"]) >= 3)
    # walk-forward selector: blocks 2..4 picked on everything before them
    names = [v.name for v in L.VARIANTS]
    wf = []
    for b in range(1, 4):
        prior = {n: sum(out["variants"][n]["blocks"][:b]) for n in names}
        pick = max(names, key=lambda n: (prior[n], n == "V0"))
        wf.append(dict(block=b + 1, pick=pick, net=out["variants"][pick]["blocks"][b]))
    out["walk_forward"] = wf
    return out


def twins(ctxs, res, idx, rng) -> np.ndarray:
    """TWINS random-entry totals on the sessions idx: each traded day gets the same count of trades at random bars of
    the day's own decision window with a random side (a refused draw is redrawn up to 20 times, else counts 0)."""
    tot = np.zeros(TWINS)
    for i in idx:
        tr, p = res[i]
        if not tr or p is None:
            continue
        c = ctxs[i]
        bars = np.arange(p.k0, p.k1 + 1)
        for t in range(TWINS):
            s = 0.0
            for _ in range(len(tr)):
                for _try in range(20):
                    r = c.trade(int(rng.choice(bars)), int(rng.choice([1, -1])))
                    if r is not None:
                        s += r[0]["net"]
                        break
            tot[t] += s
    return tot


def holdout(und: str, des_json: dict) -> dict:
    from scipy import stats
    ctxs = contexts(und)
    des, hol, cut = split(ctxs, und)
    rng = np.random.default_rng(SEED)
    out = dict(und=und, cut=cut.isoformat(), holdout_sessions=len(hol), holdout_from=ctxs[hol[0]].d.day.isoformat(),
               holdout_to=ctxs[hol[-1]].d.day.isoformat(), facts=facts(ctxs, hol), variants={})
    for v in L.VARIANTS:
        res = run_variant(v, ctxs)
        sc = score(res, hol)
        dn = daily([res[i] for i in hol])
        sc["t_p"] = float(stats.ttest_1samp(dn, 0.0, alternative="greater").pvalue) if dn.std() > 0 else 1.0
        tw = twins(ctxs, res, hol, rng)
        sc["twin_mean"] = float(tw.mean())
        sc["twin_p"] = float((1 + (tw >= sc["net"]).sum()) / (TWINS + 1))
        out["variants"][v.name] = sc
    tested = [n for n in out["variants"] if n != "V0"]
    q = L.bh_adjust([out["variants"][n]["t_p"] for n in tested] + [out["variants"][n]["twin_p"] for n in tested])
    base = out["variants"]["V0"]["net"]
    for j, n in enumerate(tested):
        sc = out["variants"][n]
        sc["t_q"], sc["twin_q"] = q[j], q[len(tested) + j]
        dv = des_json["variants"][n]
        sc["G1"], sc["G2"] = dv["G1"], dv["G2"]
        sc["G3"] = bool(sc["net"] > 0 and sc["t_q"] < 0.05)
        sc["G4"] = bool(sc["twin_q"] < 0.05)
        sc["G5"] = bool(sc["net"] > base)
        sc["PASS"] = bool(sc["G1"] and sc["G2"] and sc["G3"] and sc["G4"] and sc["G5"])
    wf_pick = des_json["walk_forward"][-1]["pick"]
    # the selector's holdout pick: the best variant over all design sessions
    picks = {n: des_json["variants"][n]["net"] for n in des_json["variants"]}
    hp = max(picks, key=lambda n: (picks[n], n == "V0"))
    out["walk_forward_holdout"] = dict(pick=hp, net=out["variants"][hp]["net"], last_design_pick=wf_pick)
    return out


def _dump(obj, name):
    os.makedirs(RESULTS, exist_ok=True)
    with open(os.path.join(RESULTS, name), "w") as f:
        json.dump(obj, f, indent=1, default=lambda o: o.isoformat() if isinstance(o, date) else float(o))


def main(argv):
    cmd = argv[1] if len(argv) > 1 else "design"
    unds = argv[2:] or ["BANKNIFTY", "NIFTY"]
    if cmd == "design":
        _dump({u: design(u) for u in unds}, "design.json")
    elif cmd == "holdout":
        if FLAG.exists():
            sys.exit(f"the R20 holdout was already read ({FLAG}); a second look needs a new pre-registration")
        with open(os.path.join(RESULTS, "design.json")) as f:
            dj = json.load(f)
        FLAG.parent.mkdir(parents=True, exist_ok=True)
        FLAG.write_text(date.today().isoformat())
        _dump({u: holdout(u, dj[u]) for u in unds}, "holdout.json")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
