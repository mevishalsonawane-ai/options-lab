"""R12 holdout: the rules frozen in PREREG.md, applied ONCE to 1 Oct 2025 - 6 Oct 2026.

    python3 -I research/hunt/r12/holdout.py   -> <scratch>/hunt/r12/HOLD_READ, holdout.csv, holdout_members.csv,
                                                 holdout_singles.csv, holdout.json   (refuses a second run)
"""
from __future__ import annotations

import hashlib
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import lib12 as L  # noqa: E402
import loop as LP  # noqa: E402
import specs as SP  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
FLAG = os.path.join(L.OUT, "HOLD_READ")


def frozen():
    txt = open(os.path.join(HERE, "PREREG.md")).read()
    blk = txt.split("```json")[1].split("```")[0]
    return json.loads(blk), hashlib.sha256(txt.encode()).hexdigest()


def cfg_of(r):
    ex = tuple(sorted(LP.E(f, p) for f, p in r["exit"]))
    lk = tuple(sorted((f, tuple(sorted(p.items()))) for f, p in r["lock"]))
    xt = (tuple(r["extend"][0]), r["extend"][1]) if r["extend"] else (None, 0)
    return (ex, r["quorum"], lk, xt[0], xt[1])


def stats(tr, days):
    n = tr.net.values
    w, l = n[n > 0], n[n <= 0]
    dly = tr.groupby("day").net.sum().reindex(days, fill_value=0.0).values
    return dict(trades=len(n), net=float(n.sum()), win=float((n > 0).mean()) if len(n) else np.nan,
                avg_win=float(w.mean()) if len(w) else 0.0, avg_loss=float(l.mean()) if len(l) else 0.0,
                maxdd=L.max_drawdown(dly))


def main():
    if os.path.exists(FLAG):
        sys.exit("holdout already read: " + open(FLAG).read())
    rules, sha = frozen()
    with open(FLAG, "w") as f:
        f.write(f"holdout read {time.strftime('%Y-%m-%d %H:%M:%S %Z')} (UTC epoch {int(time.time())}); PREREG.md sha256 {sha}\n")
    rng = np.random.default_rng(1012)
    rows, mem, sing = [], [], []
    for a, r in rules.items():
        c = LP.Ctx(a, part="holdout")
        b = LP.Book(c)
        cfg = cfg_of(r)
        k = b.run(cfg, 0)
        dl, acted = b.dl[k], b.acted[k]
        tr = L.replay(c.a, c.manager(cfg), c.di)
        days = c.days
        s0, s1 = stats(c.base, days), stats(tr, days)
        p, mu = c.twin_p(dl, acted, rng)
        rows.append(dict(arm=a, name=L.NAMES[a], config=k, **{f"orig_{x}": v for x, v in s0.items()},
                         **{f"mgr_{x}": v for x, v in s1.items()}, delta=float(dl.sum()), acted=int(acted.sum()),
                         twin_mean=mu, p_twin=p, n_ext=int((tr.n_ext > 0).sum())))
        # which member decided each changed trade (the first member voting at the exit decision / the lock / extension)
        mg = c.manager(cfg)
        why = tr.why.values
        xc = tr.xcol.values
        for i in np.nonzero(acted)[0]:
            gi = c.di[i]
            who = []
            if why[i] == 9:
                kd = max(xc[i] - 1, 0)
                for fam, pp, w in cfg[0]:
                    if c.vote(fam, dict(pp))[gi, kd]:
                        who.append(fam)
            elif why[i] == 10:
                who = [f for f, _ in cfg[2]] or ["EXT-lock"]
            if tr.n_ext.values[i] > 0:
                who.append("EXTEND")
            mem.append(dict(arm=a, i=int(gi), day=c.a.day[gi].date(), why=int(why[i]), members="+".join(who) or "other",
                            delta=float(dl[i])))
        # descriptive: every round-1 single on the holdout, and M0 (the current manager's extension without flow)
        singles = [LP.single_exit(f, p) for f, g in SP.EXIT_GRID.items() for p in g] + \
                  [LP.single_lock(f, p) for f, g in SP.LOCK_GRID.items() for p in g]
        if a in LP.EXT_ARMS:
            singles.append(((), 0, (), ("xVW", "xMP", "xOI", "xVX"), 4))
        for sc in singles:
            kk = b.run(sc, 1)
            sing.append(dict(arm=a, config=kk, delta=float(b.dl[kk].sum()), acted=int(b.acted[kk].sum()),
                             excess=float(b.dl[kk].sum() - b.acted[kk].sum() * c.mtwin.mean())))
        print(f"{a}: orig {s0['net']:,.0f} mgr {s1['net']:,.0f} delta {dl.sum():,.0f} acted {acted.sum()} twin p {p:.3f}", flush=True)
    H = pd.DataFrame(rows)
    H["q_bh"] = L.bh(H.p_twin.values)
    H.to_csv(os.path.join(L.OUT, "holdout.csv"), index=False)
    M = pd.DataFrame(mem)
    M.to_csv(os.path.join(L.OUT, "holdout_members.csv"), index=False)
    pd.DataFrame(sing).to_csv(os.path.join(L.OUT, "holdout_singles.csv"), index=False)
    summ = M.groupby(["arm", "members"]).delta.agg(["count", "sum"]).reset_index() if len(M) else pd.DataFrame()
    with open(os.path.join(L.OUT, "holdout.json"), "w") as f:
        json.dump(dict(table=H.to_dict("records"), members=summ.to_dict("records")), f, indent=1, default=str)
    pd.set_option("display.width", 250)
    print(H.drop(columns=["config"]).round(3).to_string())
    print(summ.to_string())


if __name__ == "__main__":
    main()
