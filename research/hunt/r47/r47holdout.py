"""R47 locked-holdout read (ONCE): the frozen candidates on 10 Nov 2025 - the lake's last session, with 500
random-entry twins each, Benjamini-Hochberg across the candidates sent, and the PREREG gates.

    python3 research/hunt/r47/r47holdout.py      # refuses unless results/candidates.json is committed unchanged and
                                                 # results/holdout.json does not exist
"""
from __future__ import annotations

import json
import sys
from datetime import date, datetime, timezone

import numpy as np
import pandas as pd

import r47cfg as K
import r47data as D
import r47ml as ML
import r47run as R
import r47sig as S

N = K.N


def bh(p):
    """Benjamini-Hochberg q-values."""
    p = np.asarray(p, dtype=np.float64)
    n = len(p)
    if n == 0:
        return p
    o = np.argsort(p)
    q = np.empty(n)
    run = 1.0
    for rank in range(n, 0, -1):
        i = o[rank - 1]
        run = min(run, p[i] * n / rank)
        q[i] = run
    return q


def events_for(u, c, f):
    """The candidate's events on every session of the full data (its signal reads nothing after its minute)."""
    fam, p = c["fam"], c["params"]
    if fam == "M1_gbm":
        di, m, X, y = ML.feature_rows(u, f)
        blk = R.block_of(u.days[di])
        hold = u.days[di] >= K.HOLDOUT_START.toordinal()
        pr = ML.fit_predict(di, X, y, blk >= 0, hold)          # trained on design sessions only
        return ML.events_from_probs(di, m, pr, p["th"])
    if fam == "E2_expiry_vol":
        return S.e2_expiry_vol(u, tuple(p["at"]), R.series_of(c["instr"]))
    p = {k: (tuple(v) if isinstance(v, list) else v) for k, v in p.items()}
    return S.SIGNALS[fam](u, **p)


def only_holdout(u, ev):
    keep = u.days[ev.di] >= K.HOLDOUT_START.toordinal()
    return S.Events(ev.di[keep], ev.m[keep], ev.d[keep], ev.cid[keep], ev.win, ev.days)


def exit_tuple(x):
    return (x[0], x[1], x[2], tuple(x[3]) if x[3] else None)


def twin_events(u, c, ev, trades, rng, n_twins):
    """For each twin and each candidate trade: a random holdout session (expiry days for expiry families) and a random
    decision minute in the family window, same direction (and, for O rules, the 09:15 ATM contract of that side)."""
    hold = np.flatnonzero(u.days >= K.HOLDOUT_START.toordinal())
    if ev.days == "expiry":
        ex = S.expiry_set(u, R.series_of(c["instr"]))
        hold = np.array([d for d in hold if d in ex], dtype=np.int64)
    lo, hi = ev.win
    n = len(trades)
    di = hold[rng.integers(0, len(hold), n * n_twins)]
    m = rng.integers(lo, hi + 1, n * n_twins)
    d = np.tile(trades.d.to_numpy().astype(np.int8), n_twins)
    cid = np.full(n * n_twins, -1, dtype=np.int64)
    if c["fam"].startswith("O"):
        st = u.stores["WEEK"]
        cid = np.array([S.open_atm(st, int(a), 0 if s > 0 else 1) for a, s in zip(di, d)], dtype=np.int64)
    tw = np.repeat(np.arange(n_twins), n)
    return S.Events(di.astype(np.int64), m.astype(np.int64), d, cid, ev.win, ev.days), tw


def stats(x: pd.DataFrame) -> dict:
    net = x.net.to_numpy()
    if len(net) == 0:
        return dict(trades=0, net=0.0, npt=0.0)
    mon = x.groupby(pd.to_datetime([date.fromordinal(int(d)) for d in x.day]).to_period("M")).net.sum()
    return dict(trades=int(len(net)), net=round(float(net.sum()), 0), npt=round(float(net.mean()), 1),
                win=round(float((net > 0).mean() * 100), 1), top5=round(float(np.sort(net)[-5:].sum()), 0),
                best_month=round(float(mon.max()), 0), months={str(k): round(float(v), 0) for k, v in mon.items()},
                fallback=int(x.fb.sum()), mean_outlay=round(float(x.outlay.mean()), 0))


def main(argv):
    key = D.holdout_key()
    cands = json.loads(K.CANDIDATES.read_text())["candidates"]
    rng = np.random.default_rng(K.SEED)
    out, cache = [], {}
    for c in cands:
        if c["und"] not in cache:
            u = D.load(c["und"], "full", key=key)
            cache[c["und"]] = (u, S.day_features(u), R.lots_of(u))
        u, f, lots = cache[c["und"]]
        ev = only_holdout(u, events_for(u, c, f))
        ex = exit_tuple(c["exit_spec"])
        tr = R.price(u, ev, c["instr"], ex, lots=lots)
        tr = tr[S.filter_mask(c["filt"], f, tr.di.to_numpy())].reset_index(drop=True)
        st = stats(tr)
        twin_net = np.zeros(K.N_TWINS)
        if len(tr):
            tev, tw = twin_events(u, c, ev, tr, rng, K.N_TWINS)
            ttr = R.price(u, tev, c["instr"], ex, lots=lots, sequential=False)
            twin_net = np.bincount(tw[ttr.ev.to_numpy()], weights=ttr.net.to_numpy(), minlength=K.N_TWINS)
        p = (1 + int((twin_net >= st["net"]).sum())) / (K.N_TWINS + 1) if len(tr) else 1.0
        out.append(dict(id=c["id"], fam=c["fam"], und=c["und"], kind=c["kind"], instr=c["instr"], filt=c["filt"],
                        params=c["params"], exit_spec=c["exit_spec"], signal_source=c["signal_source"], holdout=st,
                        twins=dict(n=K.N_TWINS, mean_net=round(float(twin_net.mean()), 0),
                                   mean_npt=round(float(twin_net.mean() / max(len(tr), 1)), 1),
                                   p95_net=round(float(np.percentile(twin_net, 95)), 0)), p=round(p, 4)))
        print(c["id"], c["fam"], c["und"], st.get("trades"), st.get("npt"), "p", round(p, 4), flush=True)
    q = bh([o["p"] for o in out])
    for o, qq in zip(out, q):
        h = o["holdout"]
        o["q"] = round(float(qq), 4)
        g = dict(G1_net_positive=h["net"] > 0, G2_trades_40=h["trades"] >= K.GATE_MIN_TRADES, G3_p=o["p"] < K.GATE_P,
                 G4_q=o["q"] <= K.BH_Q, G5_top5=h["trades"] > 0 and h["net"] - h["top5"] > 0,
                 G6_best_month=h["trades"] > 0 and h["net"] - h["best_month"] > 0)
        o["gates"] = {k: bool(v) for k, v in g.items()}
        o["passes"] = all(g.values())
    res = dict(read_at=datetime.now(timezone.utc).isoformat(timespec="seconds"), holdout_start=str(K.HOLDOUT_START),
               last_session=str(date.fromordinal(int(max(c[0].days[-1] for c in cache.values())))) if cache else None,
               candidates=out, any_pass=any(o["passes"] for o in out))
    K.HOLDOUT_JSON.write_text(json.dumps(res, indent=1))
    print("any candidate passes every gate:", res["any_pass"])
    return 0


if __name__ == "__main__":
    import r47holdout  # noqa: E402  (pickles name the module, never __main__)
    sys.exit(r47holdout.main(sys.argv[1:]))
