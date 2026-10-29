"""R134 forward test (PREREG.md): the frozen CISD + R123-network rules on days after the lake's 19 Oct 2026 end.

    python research/hunt/r134_forward/forward.py run                      # every lake day from FWD_START on
    python research/hunt/r134_forward/forward.py replay 2026-04-01 2026-10-19   # the validation replay (R133 check)

Nothing here is new: R131's detectors, R132's exits, R123's network (nnlive.py) and R132's EV filter, frozen. The
log (results/forward_log.json) holds simulated research trades only (no account data).
"""
from __future__ import annotations

import json
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r132_ict_exits"))
sys.path.insert(0, str(HERE))
import r132 as Q  # noqa: E402
import r132nn as NN  # noqa: E402
import nnlive  # noqa: E402

R, K = Q.R, Q.K
D3 = R.D3
RESULTS = HERE / "results"
FWD_START = date(2026, 10, 20)
PRIMARY = "S3-V2-E0"
RULES = ("S3-V2-E0", "S3-V2-E3", "S7-V2-E4", "S3-V1-E3", "S9-W-E4", "S3-W-E1")   # R133's six design passers
DECIDE_N = 50


# ------------------------------------------------------------------------------------------------ network rows
def build_rows(first: date, last: date):
    """R123's decision rows (inputs only; labels are not needed) for sessions first..last, with the sequences of all
    sessions up to `last`: r123data.build's loop with the period as arguments."""
    grids = {u: D3.load_grid(u, last) for u in K.UNDS}
    vix = D3.load_grid("INDIAVIX", last)
    budget = D3.budget_days()
    rows, seqs = [], {}
    for und in K.UNDS:
        I = D3.IndexData(und, grids, vix)
        LO = D3.LakeOptions(und, last)
        exp_sorted = sorted(LO.expiry_days())
        seqs[und] = I.seq
        for i, d in enumerate(I.days):
            if not first <= d <= last:
                continue
            opts = LO.day(d, I.g.c[i], {})
            if opts is None:
                continue
            nxt = [e for e in exp_sorted if e >= d]
            to_exp = sum(1 for x in I.days[i:] if x <= nxt[0]) - 1 if nxt else None
            ctx = dict(to_expiry=to_exp, is_expiry=d in set(exp_sorted), is_budget=d in budget)
            for t in K.K3.DECISION_ROWS:
                p = I.gpos(i, t)
                if not np.isfinite(I.g.c[i, t]):
                    continue
                rec = dict(und=und, day=d, row=t, gpos=p, close=float(I.cf[p]))
                rec.update(D3.tab_features(I, i, t, ctx, opts.chain))
                rows.append(rec)
    return pd.DataFrame(rows), seqs


def views(first: date, last: date) -> pd.DataFrame:
    F, S = build_rows(first, last)
    if not len(F):
        return pd.DataFrame(columns=["und", "day", "row"])
    m, mn = nnlive.load_model()
    pn = m.predict(F, nnlive.M.SeqStore(S))
    A = nnlive.RR.attach(F, pn, mn).reset_index(drop=True)
    return A[["und", "day", "row", "pdir30"] + [f"ev_{j}" for j in range(16)]]


# ------------------------------------------------------------------------------------------------ trades
def trades(first: date, last: date) -> pd.DataFrame:
    """Every rule's trades on sessions first..last, before the network filter (one trade at a time per index)."""
    out = []
    for und in K.UNDS:
        g = D3.load_grid(und, last)
        W, Mo = R.Series(und, last, "WEEK"), R.Series(und, last, "MONTH")
        wexp, mexp = W.expiry_days(), Mo.expiry_days()
        blocked = {i for i, d in enumerate(g.days) if d in wexp and d in mexp}
        keep = lambda d: first <= d <= last  # noqa: E731
        lot, step = K.LOT[und], K.STEP[und]
        sides = R.sides_of(g)
        for rule in RULES:
            e, x = rule.rsplit("-", 1)
            cfg = R.ALL_VARIANTS[e]
            cands = R.candidates(sides, cfg)
            if x == "E0":
                T, _ = R.sequence(sides, cands, cfg, blocked, g.days, keep)
                for t in T:
                    d = t["d"]
                    src = Mo if g.days[d] in wexp else W
                    opts = src.day(g.days[d], g.c[d])
                    ref_c = D3._last(g.c[d][:t["entry"]])
                    if opts is None or not np.isfinite(ref_c):
                        continue
                    cp = "C" if t["side"] > 0 else "P"
                    tr = R.option_trade(opts.contracts.get((cp, D3.atm(ref_c, step))), t["entry"], t["exit"], t["why"],
                                        lot, g.days[d], und)
                    if tr is not None:
                        out.append(dict(rule=rule, und=und, day=g.days[d], side=t["side"], entry=t["entry"],
                                        exit=t["exit"], why=t["why"], net=tr[0], netx2=tr[1]))
            else:
                out += _sim_trades(rule, e, x, und, g, sides, cands, blocked, keep, wexp, W, Mo, lot, step)
    return pd.DataFrame(out)


def _sim_trades(rule, e, x, und, g, sides, cands, blocked, keep, wexp, W, Mo, lot, step):
    """R132's run_index loop for one rule and index (no twins)."""
    ex = Q.EXITS[x]
    by_day = {}
    for sd, c in cands:
        if keep(g.days[c.d]):
            by_day.setdefault(c.d, []).append((sd, c))
    out = []
    for d, cl in sorted(by_day.items()):
        day = g.days[d]
        opts = (Mo if day in wexp else W).day(day, g.c[d]) if d not in blocked else None
        lastc = R.last_before(g.c[d])
        cl.sort(key=lambda z: (z[1].r0, z[0]))
        busy, i = -1, 0
        while i < len(cl):
            sd, c = cl[i]
            j = i
            while j < len(cl) and cl[j][1].r0 == c.r0:
                j += 1
            same, i = cl[i:j], j
            if len({s for s, _ in same}) == 2 or c.r0 <= busy or c.r0 > Q.LAST_ENTRY or d in blocked:
                continue
            S = sides[sd]
            ref = S.mo[d][c.r0]
            if not np.isfinite(ref) or ref <= c.stop:
                continue
            tgt = R.struct_target(S, S.chart(c.tf), d, c.r0, ref)
            if tgt is None or tgt <= ref or opts is None or not np.isfinite(lastc[c.r0]):
                continue
            a = opts.contracts.get(("C" if sd > 0 else "P", float(D3.atm(lastc[c.r0], step))))
            if a is None:
                continue
            res = Q.sim_exit(a, *R._contract_aux(a), S.mh[d], S.ml[d], c.r0, ref, c.stop, ex, lot, day, und)
            if res is None:
                continue
            busy = res[0]
            out.append(dict(rule=rule, und=und, day=day, side=sd, entry=c.r0, exit=res[0], why=res[1], net=res[2],
                            netx2=res[3]))
    return out


def filtered(T: pd.DataFrame, P: pd.DataFrame) -> pd.DataFrame:
    """R132's N-EV filter: keep a trade when its side's mean EV in the network's latest view (<= 5 min old) is > 0."""
    if not len(T):
        return T.assign(kept=pd.Series(dtype=bool))
    M = NN.attach(T, P)
    return M[NN.keep_mask(M, "NEV")]


# ------------------------------------------------------------------------------------------------ the log
def summary(K_: pd.DataFrame) -> dict:
    out = {}
    for rule in RULES:
        X = K_[K_.rule == rule].sort_values(["day", "und", "entry"]) if len(K_) else K_
        if not len(X):
            out[rule] = dict(n=0)
            continue
        net = X.net.values
        eq = np.cumsum(net)
        dd = float(min((eq - np.maximum.accumulate(np.r_[0.0, eq])[1:]).min(), 0.0))
        mo = X.assign(mo=[f"{d.year}-{d.month:02d}" for d in X.day]).groupby("mo").net.sum()
        out[rule] = dict(n=int(len(X)), win=float((net > 0).mean()), avg_net=float(net.mean()),
                         avg_net_x2=float(X.netx2.mean()), total=float(net.sum()), max_dd=dd,
                         worst_month=float(mo.min()))
    return out


def decision(s: dict) -> dict:
    """PREREG section 3, for the primary rule only, once it has DECIDE_N trades."""
    if s.get("n", 0) < DECIDE_N:
        return dict(status="collecting", trades=s.get("n", 0), needed=DECIDE_N)
    ok = dict(net_x2=s["avg_net_x2"] > 0, drawdown=s["max_dd"] > -40000.0, month=s["worst_month"] > -13000.0)
    return dict(status="pass" if all(ok.values()) else "fail", checks=ok)


def run(first: date = FWD_START, last: date | None = None, tag: str = "forward"):
    cal = D3.load_grid("NIFTY", date(2100, 1, 1)).days
    last = last or max(cal)
    if last < first:
        res = dict(tag=tag, first=str(first), last_lake_day=str(max(cal)), status="no new sessions in the lake yet")
    else:
        T = trades(first, last)
        P = views(first, last)
        Kp = filtered(T, P)
        s = summary(Kp)
        res = dict(tag=tag, first=str(first), last=str(last), sessions=sum(first <= d <= last for d in cal),
                   rules=s, primary=PRIMARY, decision=decision(s.get(PRIMARY, {})),
                   trades=[dict(rule=r.rule, und=r.und, day=str(r.day), side=int(r.side), entry=int(r.entry),
                                exit=int(r.exit), why=r.why, net=round(float(r.net), 2))
                           for r in Kp.sort_values(["day", "und", "entry"]).itertuples()] if len(Kp) else [])
    res["updated"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{tag}_log.json").write_text(json.dumps(res, indent=1, default=str))
    print(json.dumps({k: v for k, v in res.items() if k != "trades"}, indent=1, default=str))
    return res


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "replay":
        run(date.fromisoformat(sys.argv[2]), date.fromisoformat(sys.argv[3]), tag="replay")
    else:
        run()
