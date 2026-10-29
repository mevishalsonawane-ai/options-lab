"""CISD + network (R133 S3-V2-E3) parity fixture for the engine's CisdNetParityTest. 29 Oct 2026.

    R123_PREDS=<dir with R123's cleaned files> IRAALGO_LAKE=<lake> \
        python3 android/tools/cisd_parity_fixture.py . android/engine/src/test/resources/cisd/parity.json.gz

Everything here is computed by the research code itself, unchanged (R131's detector and filters, R132's E3 exit simulator,
R123's input builder via R134's build_rows, R123's network rebuilt by R134's nnlive), on RECORDED lake days:

  network   NET_ROWS holdout rows: R123's raw inputs (42 tabular + the raw 120 x 7 window) and R123's saved outputs
            (holdout_rows_preds_N1.parquet: P(+X first), P(-Y first), P(neither) and EV for the 16 cell-sides).
  days      for each recorded day: every index's and India VIX's minute sessions (today and the SESSIONS earlier ones), the
            completed sessions' daily stats (R123's d_range / d_close), the calendar R123 used, and the option chain's
            per-strike minutes (lake rows after lakeclean, as R123 reads them) up to the last view row needed;
  setups    every S3-V2 setup R131 finds on those days (both sides), what R131 / R132 decided (skipped and why, or traded),
            its entry minute, stop, the option bought, R132's buy fill and E3 exit (minute, why, price), the network view row
            and that view's side EV (R132 N-EV) - and whether the filter kept it;
  inputs    R123's 42 inputs, the raw window and the side EVs at each setup's view row (and a few fixed rows).

The Kotlin test rebuilds the sessions and the chain from these numbers and compares: the network (1e-5), every input, the
window, the setups, the filter's verdicts and the exits.
"""
from __future__ import annotations

import gzip
import json
import sys
from datetime import date, timedelta

import numpy as np
import pandas as pd

repo = sys.argv[1]
out_path = sys.argv[2]
sys.path.insert(0, repo + "/research/hunt/r134_forward")
sys.path.insert(0, repo + "/research/hunt/r132_ict_exits")
import forward as FW  # noqa: E402
import nnlive  # noqa: E402
import r132 as Q  # noqa: E402

R, K = Q.R, Q.K
D3 = R.D3
DAYS = [date(2026, 5, 25), date(2026, 9, 11)]
SESSIONS = 6
PAST = 25
NET_ROWS = 24
EXTRA_ROWS = (4, 60)
CHAIN_HALF = 6                                        # offsets the inputs read (-5..+5) and one spare each way
RULE = "S3-V2"
AHEAD = timedelta(days=45)          # the calendar (expiry days) is read from later sessions, as R134's replay had them


def fl(a, nd=4):
    return [None if not np.isfinite(v) else round(float(v), nd) for v in np.asarray(a, dtype=float).ravel()]


def delta(a):
    """Whole numbers as first value then differences (NaN as None, kept in place; the Kotlin test undoes it)."""
    out, last = [], 0
    for v in np.asarray(a, dtype=float):
        if not np.isfinite(v):
            out.append(None)
            continue
        out.append(int(round(v)) - last)
        last = int(round(v))
    return out


def net_rows(m):
    Fh = pd.read_parquet(nnlive.PREDS / "rows_holdout.parquet")
    Sh = dict(np.load(nnlive.PREDS / "seq_holdout.npz"))
    ref = pd.read_parquet(nnlive.PREDS / "holdout_rows_preds_N1.parquet")
    rng = np.random.default_rng(29)
    pick = np.sort(rng.choice(len(Fh), NET_ROWS, replace=False))
    F = Fh.iloc[pick].reset_index(drop=True)
    store = nnlive.M.SeqStore(Sh)
    fp = store.flat(F.und.values, F.gpos.values)
    W = store.A[fp[:, None] + np.arange(-K.K3.SEQ_N + 1, 1)[None, :]]
    key = ref.set_index(["und", "day", "row"])
    out = []
    for i, r in F.iterrows():
        p = key.loc[(r.und, r.day, r.row)]
        out.append(dict(und=r.und, day=str(r.day), row=int(r.row), tab=fl(r[list(D3.TAB_COLS)].values.astype(float), 9),
                        seq=[fl(w, 7) for w in W[i]],
                        pw=fl([p[f"pw_{j}"] for j in range(16)], 9), pl=fl([p[f"pl_{j}"] for j in range(16)], 9),
                        pn=fl([p[f"pn_{j}"] for j in range(16)], 9), ev=fl([p[f"ev_{j}"] for j in range(16)], 6)))
    return out


def setups(und: str, d0: date):
    """R131 S3-V2 setups on d0 with R132's run_index filters (as forward._sim_trades applies them for E3), all recorded."""
    g = D3.load_grid(und, d0)
    W, Mo = R.Series(und, d0 + AHEAD, "WEEK"), R.Series(und, d0 + AHEAD, "MONTH")
    wexp, mexp = W.expiry_days(), Mo.expiry_days()
    i0 = g.pos()[d0]
    blocked = (d0 in wexp and d0 in mexp)
    sides = R.sides_of(g)
    cfg = R.ALL_VARIANTS[RULE]
    cands = [(sd, c) for sd, c in R.candidates(sides, cfg) if c.d == i0]
    opts = (Mo if d0 in wexp else W).day(d0, g.c[i0]) if not blocked else None
    lastc = R.last_before(g.c[i0])
    ex = Q.EXITS["E3"]
    lot, step = K.LOT[und], K.STEP[und]
    cands.sort(key=lambda z: (z[1].r0, z[0]))
    out, busy, i = [], -1, 0
    while i < len(cands):
        j = i
        while j < len(cands) and cands[j][1].r0 == cands[i][1].r0:
            j += 1
        same, i = cands[i:j], j
        both = len({s for s, _ in same}) == 2
        for sd, c in same:
            S = sides[sd]
            rec = dict(side=sd, r0=int(c.r0), stop=float(sd * c.stop))
            ref = S.mo[i0][c.r0] if c.r0 < K.N else np.nan
            why = None
            if both:
                why = "both"
            elif c.r0 <= busy:
                why = "busy"
            elif c.r0 > Q.LAST_ENTRY:
                why = "late"
            elif blocked:
                why = "blocked"
            elif not np.isfinite(ref):
                why = "no_price"
            elif ref <= c.stop:
                why = "no_room"
            else:
                tgt = R.struct_target(S, S.chart(c.tf), i0, c.r0, ref)
                rec["target"] = None if tgt is None else float(sd * tgt)
                if tgt is None or tgt <= ref:
                    why = "no_target"
                elif opts is None or not np.isfinite(lastc[c.r0]):
                    why = "no_option"
                else:
                    k = float(D3.atm(lastc[c.r0], step))
                    a = opts.contracts.get(("C" if sd > 0 else "P", k))
                    rec["strike"] = k
                    if a is None:
                        why = "no_option"
                    else:
                        cv, li = R._contract_aux(a)
                        res = Q.sim_exit(a, cv, li, S.mh[i0], S.ml[i0], c.r0, ref, c.stop, ex, lot, d0, und)
                        if res is None:
                            why = "no_option"
                        else:
                            op = a[c.r0, 0]
                            buy = float(D3.FILLS.buy(np.array([op]), np.array([Q._vol5(cv, c.r0) / lot]))[0])
                            busy = res[0]
                            rec.update(ref=float(sd * ref), buy=buy, exit=int(res[0]), why=res[1], net=round(float(res[2]), 2),
                                       opt=[fl(a[r, :4], 2) for r in range(K.N)], lot=lot)
            rec["skip"] = why
            out.append(rec)
    return out, sorted(e for e in (W.expiry_days() | Mo.expiry_days()) if e >= d0)


def chain_bars(und: str, d0: date, through: int):
    """The lake's per-strike minutes for d0 (WEEK series, after lakeclean as R123's DayOptions reads them), strikes the
    inputs can reach: (strike, side) -> rows -> (close, oi, volume, iv)."""
    LO = D3.LakeOptions(und, d0)
    g = D3.load_grid(und, d0)
    i0 = g.pos()[d0]
    o = LO.by_day.get(d0) if LO.year == d0.year else None
    if o is None:
        LO.day(d0, g.c[i0], {})
        o = LO.by_day.get(d0)
    w = o.rename(columns={"d": "day", "m": "r"})
    kept, _, _ = D3.lakeclean.clean(w, {d0: g.c[i0]}, stale=True)
    gg = kept.rename(columns={"day": "d", "r": "m"})
    step = K.STEP[und]
    gg = gg[(gg.strike - gg.spot).abs() <= 12 * step]
    gg = gg[gg.m <= through].drop_duplicates(["m", "strike", "side"], keep="first")
    cl = pd.Series(g.c[i0][:through + 1]).ffill().values
    lo, hi = np.nanmin(cl), np.nanmax(cl)
    lo_k, hi_k = D3.atm(lo, step) - CHAIN_HALF * step, D3.atm(hi, step) + CHAIN_HALF * step
    gg = gg[(gg.strike >= lo_k) & (gg.strike <= hi_k)]
    out = []
    for (s, k), h in gg.groupby(["side", "strike"]):
        rows = h.m.values.astype(int)
        out.append(dict(strike=float(k), call=bool(s == "C"), rows=delta(rows), close=fl(h.close.values, 2),
                        oi=delta(h.oi.values), vol=delta(h.volume.values), iv=fl(h.iv.values, 4),
                        off=delta(h.offset.values)))
    return out


def main():
    m, mn = nnlive.load_model()
    fixture = dict(what="R133 S3-V2-E3 parity fixture (research code on recorded lake days)", net=net_rows(m), days=[])
    for d0 in DAYS:
        first = d0
        F, S = FW.build_rows(first, d0 + AHEAD)
        F = F[F.day == d0].reset_index(drop=True)
        pn = m.predict(F, nnlive.M.SeqStore(S))
        A = nnlive.RR.attach(F, pn, mn).reset_index(drop=True)
        store = nnlive.M.SeqStore(S)
        later = [x for x in D3.load_grid("NIFTY", d0 + timedelta(days=60)).days if x > d0][:30]
        day = dict(day=str(d0), trading_after=[str(x) for x in later], index={}, vix={}, past={}, cal={}, chains={}, setups={}, rows=[], expiries={})
        grids = {u: D3.load_grid(u, d0) for u in K.UNDS}
        vix = D3.load_grid("INDIAVIX", d0)
        for u, g in list(grids.items()) + [("INDIAVIX", vix)]:
            i0 = g.pos().get(d0)
            if i0 is None:
                continue
            ss = range(max(0, i0 - SESSIONS), i0 + 1)
            tgt = day["vix"] if u == "INDIAVIX" else day["index"].setdefault(u, {})
            for i in ss:
                tgt[str(g.days[i])] = [fl(g.o[i], 2), fl(g.h[i], 2), fl(g.l[i], 2), fl(g.c[i], 2)]
        for u in K.UNDS:
            I = D3.IndexData(u, grids, vix)
            i0 = I.dpos.get(d0)
            if i0 is None:
                continue
            day["past"][u] = [[str(I.days[i]), float(I.d_range[i]), float(I.d_close[i])] for i in range(max(0, i0 - PAST), i0)]
            su, exps = setups(u, d0)
            day["setups"][u] = su
            day["expiries"][u] = [str(e) for e in exps]
            want = set(EXTRA_ROWS) if u == "NIFTY" else set()
            for s in su:
                t = FW.NN.view_row(s["r0"])
                s["view"] = t
                if t is not None and s["skip"] is None:          # the network is read only for a setup R131 would trade
                    want.add(t)
            Au = A[(A.und == u) & (A.day == d0)]
            rows_here = []
            for t in sorted(want):
                r = Au[Au.row == t]
                if not len(r):
                    continue
                r = r.iloc[0]
                Fr = F[(F.und == u) & (F.day == d0) & (F.row == t)].iloc[0]
                fp = store.flat(np.array([u]), np.array([Fr.gpos]))[0]
                ev_c = float(np.mean([r[f"ev_{2 * c}"] for c in range(8)]))
                ev_p = float(np.mean([r[f"ev_{2 * c + 1}"] for c in range(8)]))
                rows_here.append(dict(und=u, row=int(t), tab=fl(Fr[list(D3.TAB_COLS)].values.astype(float), 9),
                                      seq=[fl(w, 7) for w in store.A[fp - K.K3.SEQ_N + 1:fp + 1]],
                                      ev=fl([r[f"ev_{j}"] for j in range(16)], 6), ev_c=ev_c, ev_p=ev_p))
                if t in [s.get("view") for s in su]:
                    for s in su:
                        if s.get("view") == t and s["skip"] is None:
                            s["ev_side"] = ev_c if s["side"] > 0 else ev_p
                            s["kept"] = bool(s["skip"] is None and s["ev_side"] > 0)
            day["rows"] += rows_here
            Fu = F[(F.und == u) & (F.day == d0)]
            if len(Fu):
                c0 = Fu.iloc[0]
                day["cal"][u] = dict(to_expiry=None if not np.isfinite(c0.to_expiry) else float(c0.to_expiry),
                                     is_expiry=bool(c0.is_expiry), is_budget=bool(c0.is_budget))
            if rows_here:
                day["chains"][u] = chain_bars(u, d0, max(r["row"] for r in rows_here))
        fixture["days"].append(day)
        kept = [(u, s["side"], s["r0"], s.get("why")) for u, su in day["setups"].items() for s in su if s.get("kept")]
        print(d0, "setups", sum(len(v) for v in day["setups"].values()), "kept", kept, flush=True)
    raw = json.dumps(fixture, separators=(",", ":")).encode()
    with gzip.open(out_path, "wb", compresslevel=9) as f:
        f.write(raw)
    print(f"wrote {out_path}: {len(raw) / 1e6:.2f} MB raw")


if __name__ == "__main__":
    main()
