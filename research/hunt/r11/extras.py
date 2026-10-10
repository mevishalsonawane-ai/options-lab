"""R11 extras: event census (when, expiry, scheduled-event days), what happens after, up vs down, futures build-up
classes in the futures window, example stories and fake breakouts.

    python3 -I research/hunt/r11/extras.py   -> <scratch>/hunt/r11/extras.log, stories.txt, fakes.txt
Uses events_hold.parquet (design + holdout rows, one row per event, feature window means already attached).
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import anatomy as A  # noqa: E402

OUT = A.OUT
LOG = open(os.path.join(OUT, "extras.log"), "w")


def say(*a):
    s = " ".join(str(x) for x in a)
    print(s, flush=True)
    LOG.write(s + "\n")


def census(E):
    flags = pd.read_parquet(os.path.join(A.C.SCRATCH, "hunt", "h28", "flags.parquet"))
    flags["day"] = pd.to_datetime(flags.day)
    say("== events per kind, period (n, share up, median size bp, share 09:16-10:14, share 14:00-15:14, share on expiry days)")
    E["m"] = E.t + 555
    g = E.groupby(["und", "kind", "hold"])
    say(g.agg(n=("t", "size"), up=("dir", "mean"), size_bp=("ret_bp", lambda s: s.abs().median()),
              first_hour=("m", lambda s: ((s >= 556) & (s < 615)).mean()), last_hour=("m", lambda s: (s >= 840).mean()),
              expiry=("exp", "mean")).round(3).to_string())
    say("   (up = mean of dir: +1 up / -1 down; 0 = balanced)")
    # events per day: expiry vs not, scheduled flags vs not
    rows = []
    for u in A.ROUND:
        P = A.load(u)
        days = pd.DataFrame(dict(date=P["date"], exp=P["exp"], hold=P["hold"]))
        f = flags[flags.und == u].set_index("day")
        for col in ("rbi", "budget", "election", "fomc_next", "uscpi_next", "incpi_next", "res_bank_next", "res_next", "msci"):
            days[col] = f[col].reindex(days.date).fillna(False).values.astype(bool)
        days["any_sched"] = days[["rbi", "budget", "election", "fomc_next", "uscpi_next", "incpi_next", "res_bank_next", "res_next"]].any(axis=1)
        for kind in ("m1_top1", "leg"):
            ev = E[(E.und == u) & (E.kind == kind)].groupby("date").size()
            days["n"] = ev.reindex(days.date).fillna(0).values
            for col in ("exp", "rbi", "budget", "election", "fomc_next", "uscpi_next", "res_bank_next", "res_next", "any_sched"):
                for hold in (False, True):
                    d = days[days.hold == hold]
                    a, b = d[d[col]], d[~d[col]]
                    if len(a) == 0:
                        continue
                    rows.append(dict(und=u, kind=kind, flag=col, hold=hold, days=len(a), per_day=a.n.mean(), others=b.n.mean(),
                                     ratio=a.n.mean() / max(b.n.mean(), 1e-9)))
    R = pd.DataFrame(rows)
    say("\n== big moves per day on flagged days vs other days (ratio > 1 = more big moves)")
    say(R.round(2).to_string(index=False))
    # time of day (m1_top1, legs): share by half hour vs share of eligible minutes
    E["hh"] = ((E.m - 555) // 30)
    say("\n== time of day: share of events per 30-min block (09:15 + 30k), m1_top1 and legs, design+holdout")
    say(E[E.kind.isin(["m1_top1", "leg"])].groupby(["und", "kind"]).hh.value_counts(normalize=True).unstack().round(3).to_string())


def after(E):
    say("\n== what happened next (bp, in the move's direction, from the end of the event candle/leg): mean, median, share > 0")
    g = E.groupby(["und", "kind", "hold"])
    say(g.agg(a15=("after15_bp", "mean"), a15med=("after15_bp", "median"), cont15=("after15_bp", lambda s: (s > 0).mean()),
              a30=("after30_bp", "mean"), cont30=("after30_bp", lambda s: (s > 0).mean())).round(2).to_string())


def updown(E):
    say("\n== up vs down moves, during the event (m1_top1, window means of the z-scores, raw sign: + = up)")
    feats = ["absret", "optvol", "ce_pe_vol", "iv_chg", "vix_chg", "basis_chg", "oi_build", "hw_vol"]
    x = E[E.kind == "m1_top1"]
    rows = []
    for (u, d), g in x.groupby(["und", "dir"]):
        r = dict(und=u, dir=d, n=len(g))
        for f in feats:
            r[f + "_EV"] = g[f + "|EV"].mean()
            r[f + "_W5"] = g[f + "|W5"].mean()
        rows.append(r)
    say(pd.DataFrame(rows).round(2).T.to_string())


def futures_buildup():
    """in the futures window: share of long build-up / short build-up / short covering / long unwinding during
    m1_top5 events (price change over the candle, OI change over the candle and the next 4 minutes) vs control minutes."""
    say("\n== futures OI build-up class around 1-min top-5% events (futures minutes exist only 2026-07-29 .. 2026-10-06)")
    E = pd.read_parquet(os.path.join(OUT, "events_hold.parquet"))
    for u in A.ROUND:
        P = A.load(u)
        A.LOG = LOG
        fc, foi = P["futc"], P["futoi"]
        has = np.isfinite(fc).sum(1) > 300
        x = E[(E.und == u) & (E.kind == "m1_top5")]
        x = x[has[x.i.values]]

        def cls(i, t):
            a, b = t - 1, min(t + 4, A.W - 1)
            dp = fc[i, t] - fc[i, a]
            do = np.nanmax(foi[i, t:b + 1]) - foi[i, a] if np.isfinite(foi[i, t:b + 1]).any() else np.nan
            if not (np.isfinite(dp) and np.isfinite(do)) or do == 0:
                return "OI flat/unknown"
            return {(1, 1): "long build-up", (-1, 1): "short build-up", (1, -1): "short covering", (-1, -1): "long unwinding"}[
                (int(np.sign(dp)) or 1, int(np.sign(do)))]
        ev = pd.Series([cls(i, t) for i, t in zip(x.i.values, x.t.values)]).value_counts(normalize=True)
        rng = np.random.default_rng(3)
        days = np.nonzero(has)[0]
        ct = pd.Series([cls(int(rng.choice(days)), int(rng.integers(31, 355))) for _ in range(3000)]).value_counts(normalize=True)
        say(u, f"events {len(x)} on {x.date.nunique()} days")
        say(pd.DataFrame(dict(events=ev, random_minutes=ct)).round(3).to_string())


def stories(E):
    """pick examples and write their feature stories."""
    flags = pd.read_parquet(os.path.join(A.C.SCRATCH, "hunt", "h28", "flags.parquet"))
    flags["day"] = pd.to_datetime(flags.day)
    pick = []
    x1 = E[E.kind == "m1_top1"].copy()
    x1["ax"] = x1.x.abs()
    for u in A.ROUND:
        for hold in (False, True):
            for d in (1, -1):
                s = x1[(x1.und == u) & (x1.hold == hold) & (x1.dir == d) & (x1.t >= 31)].sort_values("ax", ascending=False)
                pick.append(s.iloc[0])
    lg = E[E.kind == "leg"].copy()
    lg["a"] = lg.ret_bp.abs()
    for u in A.ROUND:
        for d in (1, -1):
            s = lg[(lg.und == u) & (lg.dir == d) & (lg.t >= 31)].sort_values("a", ascending=False)
            pick.append(s.iloc[0])
    # expiry days and scheduled-news days
    ex = x1[(x1.exp) & (x1.t >= 240)].sort_values("ax", ascending=False)
    pick += [ex[ex.und == "BANKNIFTY"].iloc[0], ex[ex.und == "NIFTY"].iloc[0]]
    f = flags.set_index(["und", "day"])
    for col, u in (("rbi", "BANKNIFTY"), ("budget", "NIFTY"), ("election", "NIFTY")):
        dd = f[f[col]].reset_index()
        dd = dd[dd.und == u].day
        s = x1[(x1.und == u) & (x1.date.isin(dd)) & (x1.t >= 31)].sort_values("ax", ascending=False)
        if len(s):
            pick.append(s.iloc[0])
    P = pd.DataFrame(pick).drop_duplicates(["und", "date", "t"])
    out = []
    for r in P.to_dict("records"):
        out.append(story(r, flags))
    open(os.path.join(OUT, "stories.txt"), "w").write("\n\n".join(out) + "\n")
    say(f"\nstories: {len(out)} written")


def z(v):
    return "n/a" if v is None or not np.isfinite(v) else f"{v:+.1f}"


def story(r, flags):
    d = dict(r)
    r = pd.Series(d)

    def G(f, w):
        return d.get(f"{f}|{w}", np.nan)
    s = "UP" if r.dir > 0 else "DOWN"
    fl = flags[(flags.und == r.und) & (flags.day == pd.Timestamp(r.date))]
    tags = []
    if r.exp:
        tags.append("expiry day")
    if len(fl):
        for col, nm in (("rbi", "RBI policy day"), ("budget", "Budget day"), ("election", "election-result day"),
                        ("fomc_next", "day after FOMC"), ("uscpi_next", "day after US CPI"), ("res_bank_next", "day after a bank heavyweight's results")):
            if bool(fl[col].iloc[0]):
                tags.append(nm)
    kind = {"m1_top1": "1-minute candle (top 1%)", "leg": "trend leg", "m1_top5": "1-minute candle (top 5%)"}.get(r.kind, r.kind)
    ot = {0: "open-auction (no conviction)", 1: "open-drive", 2: "open-rejection-reverse", 3: "open-test-drive"}.get(int(r.otype), "?")
    od = {1: "up", -1: "down", 0: ""}.get(int(r.odir), "")
    lines = [f"{r.und} {pd.Timestamp(r.date).date()} {r.hhmm} IST - {s} {kind}: {r.ret_bp:+.0f} bp"
             + (f" in {r.dur} min" if r.kind == "leg" else "") + (f" ({abs(r.x):.1f}x a normal minute)" if np.isfinite(r.x) else "")
             + (f" [{', '.join(tags)}]" if tags else "") + (" [holdout]" if r.hold else " [design]")]
    lines.append(f"  context: index {r.px:,.0f}, VIX {r.vix_lvl:.1f}, VWAP {r.vwap:,.0f} ({'above' if r.px > r.vwap else 'below'}), "
                 f"IB {r.ibl:,.0f}-{r.ibh:,.0f}, open type {ot} {od}; prior-day value area: "
                 f"{ {1.0: 'above VAH', -1.0: 'below VAL', 0.0: 'inside'}.get(G('va_pos', 'W1'), 'n/a') }; "
                 f"OI concentration near spot z {z(G('gamma_conc', 'W1'))}; dealer gamma (prior day) {'negative' if G('gex_negative', 'W1') == 1 else 'positive' if G('gex_negative', 'W1') == 0 else 'n/a'}")
    m = 1 if r.dir > 0 else -1
    lines.append(f"  30-16 min before: |ret| z {z(G('absret', 'W30'))}, 30-min range z {z(G('range30', 'W30'))}, option volume z {z(G('optvol', 'W30'))}, "
                 f"OI activity z {z(G('oi_activity', 'W30'))}, heavyweight volume z {z(G('hw_vol', 'W30'))}")
    lines.append(f"  15-6 min before: |ret| z {z(G('absret', 'W15'))}, option volume z {z(G('optvol', 'W15'))}, drift (move-aligned) z {z(m * G('ret', 'W15'))}, "
                 f"put-minus-call writing (aligned) z {z(m * G('oi_build', 'W15'))}, CE-PE volume (aligned) z {z(m * G('ce_pe_vol', 'W15'))}")
    lines.append(f"  5-2 min before: |ret| z {z(G('absret', 'W5'))}, option volume z {z(G('optvol', 'W5'))}, drift z {z(m * G('ret', 'W5'))}, "
                 f"forward-minus-index (aligned) z {z(m * G('basis_chg', 'W5'))}, VIX change z {z(G('vix_chg', 'W5'))}, "
                 f"VWAP distance (aligned) {z(m * G('vwap_z', 'W5'))} SD")
    lines.append(f"  the minute before: drift z {z(m * G('ret', 'W1'))}, forward-minus-index z {z(m * G('basis_chg', 'W1'))}, "
                 f"CE-PE premium spread z {z(m * G('prem_spread', 'W1'))}, signed option flow z {z(m * G('flow', 'W1'))}, VIX z {z(G('vix_chg', 'W1'))}")
    lines.append(f"  DURING: |ret| z {z(G('absret', 'EV'))}, option volume z {z(G('optvol', 'EV'))}, CE-PE volume z {z(m * G('ce_pe_vol', 'EV'))}, "
                 f"ATM IV change z {z(G('iv_chg', 'EV'))}, VIX change z {z(G('vix_chg', 'EV'))}, delta proxy z {z(m * G('delta_proxy', 'EV'))}, "
                 f"heavyweight volume z {z(G('hw_vol', 'EV'))}, IB first break {'yes' if abs(G('ib_first_break', 'EV') or 0) > 0 else 'no'}")
    if np.isfinite(G("fut_vol", "EV")):
        lines.append(f"  futures: volume z before {z(G('fut_vol', 'W5'))} / during {z(G('fut_vol', 'EV'))}, OI activity z before {z(G('fut_oi_abs', 'W5'))}, "
                     f"futures delta proxy (aligned) during {z(m * G('fut_delta', 'EV'))}")
    lines.append("  (drift, flow, CE-PE, forward and delta numbers are aligned: + = in the move's direction; VIX and IV are raw: + = rising)")
    lines.append(f"  next: {r.after15_bp:+.0f} bp after 15 min, {r.after30_bp:+.0f} bp after 30 min (in the move's direction)")
    return "\n".join(lines)


def fakes(E):
    """5-minute top-5% candles that make a new session high/low after 10:15 and are fully given back within 15 min."""
    out = []
    rows = []
    for u in A.ROUND:
        P = A.load(u)
        c, hh, ll = P["c"], P["hh"], P["ll"]
        x = E[(E.und == u) & (E.kind == "m5_top5") & (E.t >= 60) & (E.t <= 330)]
        for r in x.to_dict("records"):
            r = pd.Series(r)
            i, t = int(r.i), int(r.t)
            pre_hi, pre_lo = np.nanmax(hh[i, :t]), np.nanmin(ll[i, :t])
            c_hi, c_lo = np.nanmax(hh[i, t:t + 5]), np.nanmin(ll[i, t:t + 5])
            new = (r.dir > 0 and c_hi > pre_hi) or (r.dir < 0 and c_lo < pre_lo)
            if not new:
                continue
            start = c[i, t - 1]
            nxt = c[i, t + 5:t + 20]
            back = (r.dir > 0 and (nxt <= start).any()) or (r.dir < 0 and (nxt >= start).any())
            rows.append(dict(und=u, hold=r.hold, back=back, date=r.date, **{k: r[k] for k in r.index if "|" in k}))
            if back:
                out.append(r)
    R = pd.DataFrame(rows)
    say("\n== breakout 5-min candles (new session extreme after 10:15) fully given back within 15 min")
    say(R.groupby(["und", "hold"]).back.agg(["size", "mean"]).round(3).to_string())
    # do the fakes look different BEFORE / DURING the candle? (signed features aligned with the breakout's direction
    # are already in the window means as raw z; align here)
    cols = ["absret|W15", "range30|W15", "optvol|W5", "oi_activity|W5", "vwap_z|W1", "vwap_absz|W1", "ret|W5", "basis_chg|W1",
            "ce_pe_vol|EV", "optvol|EV", "hw_vol|EV", "iv_chg|EV"]
    from anatomy import cluster_t  # noqa: E402
    out2 = []
    for hold in (False, True):
        x = R[R.hold == hold]
        for c in cols:
            v = x[c].values.astype(float)
            fk = x.back.values
            m, t, p, n = cluster_t(np.where(fk, v, np.nan) - np.nanmean(v[~fk]), x.date.values)
            out2.append(dict(hold=hold, feat=c, fake=np.nanmean(v[fk]), real=np.nanmean(v[~fk]), diff=m, p=p, n_fake=int(fk.sum())))
    say("fakes vs breakouts that held (raw window z; signed ones NOT aligned, both directions pooled):")
    say(pd.DataFrame(out2).round(3).to_string(index=False))
    X = pd.DataFrame([dict(r) for r in out])
    X["a"] = X.x.abs()
    X = X[X.after15_bp < -0.5 * X.ret_bp.abs()]          # still reversed 15 minutes later (not a whipsaw that resumed)
    sel = pd.concat([X[X.hold].sort_values("a", ascending=False).head(3), X[~X.hold].sort_values("a", ascending=False).head(2)])
    flags = pd.read_parquet(os.path.join(A.C.SCRATCH, "hunt", "h28", "flags.parquet"))
    flags["day"] = pd.to_datetime(flags.day)
    open(os.path.join(OUT, "fakes.txt"), "w").write("\n\n".join(story(r, flags) for r in sel.to_dict("records")) + "\n")


def main():
    A.LOG = LOG
    E = pd.read_parquet(os.path.join(OUT, "events_hold.parquet"))
    census(E)
    after(E)
    updown(E)
    futures_buildup()
    stories(E)
    fakes(E)


if __name__ == "__main__":
    main()
