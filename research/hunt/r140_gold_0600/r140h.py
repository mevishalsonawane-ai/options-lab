"""R140 Amendment E (PREREG.md, commit cea07a6): the whole day, hour by hour. L1 (vote 4/4, stop 0.5 ATR, 60% lock
after +0.5 ATR) and G4 (the same on busy days only) at every full IST hour, out within 2 hours. US gold only;
information only (not tradable via Zerodha).

Signal source: XAUUSD spot 15-minute candles completed at the entry (the underlying). Every rule has a stop-loss and
a profit lock.

    python -m research.hunt.r140_gold_0600.r140h design
    python -m research.hunt.r140_gold_0600.r140h holdout     # 1 Apr - 31 Aug 2026, one read, passing rules only
    python -m research.hunt.r140_gold_0600.r140h recent      # 1 Sep - 30 Oct 2026, passing / best rules (labelled)
"""
from __future__ import annotations

import json
import sys
from datetime import date, datetime, timedelta

import numpy as np
import pandas as pd

from research.hunt.r140_gold_0600 import r140 as R
from research.hunt.r140_gold_0600 import r140e as E
from research.hunt.r140_gold_0600 import r140g as G

HOURS = tuple(range(24))
STOP, TRIG, KEEP, HOLD_MIN = 0.5, 0.5, 0.60, 120
MIN_TRADES = 50
HOLD = (date(2026, 4, 1), date(2026, 8, 31))
RECENT = (date(2026, 9, 1), date(2026, 10, 30))


def entry_utc(d: date, h: int) -> pd.Timestamp:
    """IST hour h of IST date d, as naive UTC."""
    return pd.Timestamp(d) + pd.Timedelta(hours=h) - pd.Timedelta(hours=5, minutes=30)


def last_ny_close(t_utc: pd.Timestamp) -> pd.Timestamp:
    """The latest 17:00 New York weekday close at or before t (naive UTC)."""
    ny = t_utc.tz_localize("UTC").tz_convert(R.NY)
    d = ny.date()
    for k in range(0, 8):
        dd = d - timedelta(days=k)
        if dd.weekday() < 5:
            c = R.ny_to_utc(dd, 17, 0)
            if c <= t_utc:
                return c
    raise ValueError(t_utc)


def ist_days(X: R.Xau, period) -> list:
    first, last = max(period[0], X.df.index[0].date() + timedelta(days=2)), min(period[1], X.df.index[-1].date())
    return [first + timedelta(days=k) for k in range((last - first).days + 1)
            if (first + timedelta(days=k)).weekday() < 5]


def outcomes(X, ind, days, h):
    """Per day at IST hour h: vote, ATR, entry price and the long / short trade (both sides, the same exits)."""
    rows = {}
    for d in days:
        t = entry_utc(d, h)
        bar = t - pd.Timedelta(minutes=15)
        if bar not in ind.index:
            continue
        r = ind.loc[bar]
        atr = r.atr14
        if not np.isfinite(atr) or atr <= 0:
            continue
        i_in, i_out = X.ix_at_or_after(t), X.ix_at_or_after(t + pd.Timedelta(minutes=HOLD_MIN))
        if i_out >= len(X.ts) or i_out <= i_in:
            continue
        if X.ts[i_in] - np.datetime64(t) > np.timedelta64(5, "m"):
            continue
        if X.ts[i_out] - np.datetime64(t + pd.Timedelta(minutes=HOLD_MIN)) > np.timedelta64(30, "m"):
            continue
        cl = X.close_before(last_ny_close(t))
        now = X.close_before(t)
        v = [np.sign(r.ema_spread), np.sign(r.rsi - 50), np.sign(r.macd_hist), np.sign(now / cl - 1)]
        vote = 1 if all(x == 1 for x in v) else (-1 if all(x == -1 for x in v) else 0)
        e = X.o[i_in]
        res = {}
        for side in (1, -1):
            px, why = E.sim_xau_t(X, i_in, i_out, side, STOP * atr, TRIG * atr, KEEP, None)
            g = side * (px - e) * R.XAU_LOT_OZ
            res[side] = dict(day=d, t_in=t, side=side, gross=g, net=g - R.XAU_COST, net2=g - 2 * R.XAU_COST, why=why,
                             entry=float(e), stop_usd=float(STOP * atr), atr=float(atr))
        rng_ = float(X.h[i_in:i_out].max() - X.l[i_in:i_out].min())
        rows[d] = dict(vote=vote, atr=float(atr), long=res[1], short=res[-1], summer=R.us_summer(t.date()),
                       move=float(X.o[i_out] - e), range=rng_, entry=float(e))
    return rows


def busy(rows_all: dict) -> dict:
    """ATR at the hour >= the median ATR at the same hour on all EARLIER days (expanding, earlier only)."""
    s = pd.Series({d: r["atr"] for d, r in sorted(rows_all.items())})
    med = s.expanding().median().shift(1)
    return {d: bool(s[d] >= med[d]) if np.isfinite(med[d]) else False for d in s.index}


def rule_trades(rows, flags, period, need_busy):
    out = []
    for d, r in sorted(rows.items()):
        if not R.in_p(d, period) or r["vote"] == 0 or (need_busy and not flags.get(d, False)):
            continue
        out.append(r["long"] if r["vote"] > 0 else r["short"])
    return out


def twin(rows, period, trades, rng):
    pool = [r for d, r in rows.items() if R.in_p(d, period)]
    if not trades or not pool:
        return 1.0, 0.0
    L = np.array([r["long"]["net"] for r in pool])[:, None]
    S = np.array([r["short"]["net"] for r in pool])[:, None]
    share = float(np.mean([t["side"] > 0 for t in trades]))
    return R.twin_p(L, S, len(trades), share, float(sum(t["net"] for t in trades)), rng)


def load_all(period_all=(date(2023, 10, 2), date(2026, 10, 30))):
    X, b15, wt = R._load()
    ind = E.indicators(b15)
    days = ist_days(X, period_all)
    per_hour = {h: outcomes(X, ind, days, h) for h in HOURS}
    flags = {h: busy(per_hour[h]) for h in HOURS}
    return X, per_hour, flags


def describe(per_hour, period):
    out = []
    for h in HOURS:
        rows = [r for d, r in per_hour[h].items() if R.in_p(d, period)]
        if not rows:
            out.append(dict(hour=f"{h:02d}:00", n=0))
            continue
        df = pd.DataFrame([dict(summer=r["summer"], move=r["move"], range=r["range"], vote=r["vote"],
                                pct=r["move"] / r["entry"]) for r in rows])
        row = dict(hour=f"{h:02d}:00", n=len(df), range_med=float(df.range.median()), move_abs_med=float(df.move.abs().median()),
                   up=float((df.move > 0).mean()), vote_share=float((df.vote != 0).mean()))
        for lab, m in (("summer", df.summer), ("winter", ~df.summer)):
            row[lab] = dict(n=int(m.sum()), range_med=float(df.range[m].median()) if m.any() else None)
        out.append(row)
    return out


def main(mode):
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    X, per_hour, flags = load_all()
    if mode == "design":
        rng = np.random.default_rng(R.SEED + 5)
        prior = {}
        for fn in ("design.json", "amendA_design.json"):
            prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / fn).read_text())["variants"].items()})
        for fn in ("amendB_design.json", "amendD_design.json"):
            prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / fn).read_text()).items()})
        res, p = {}, {}
        for h in HOURS:
            for suf, need in (("a", False), ("b", True)):
                k = f"H{h:02d}{suf}"
                ts = rule_trades(per_hour[h], flags[h], R.XAU_DESIGN, need)
                s = R.summarise(k, ts, R.XAU_FOLDS, False)
                s["twin_p"], s["twin_median_total"] = twin(per_hour[h], R.XAU_DESIGN, ts, rng)
                p[k] = s["twin_p"]
                res[k] = s
        q = R.bh({**prior, **p})                                   # BH over 90
        for k, s in res.items():
            s["q"] = q[k]
            g = R.gates(s, s["twin_p"], q[k], "A4")
            g["trades"] = s["n"] >= MIN_TRADES
            g["all"] = all(v for kk, v in g.items() if kk != "all")
            s["gates"] = g
            if g["all"]:
                ts = rule_trades(per_hour[int(k[1:3])], flags[int(k[1:3])], R.XAU_DESIGN, k.endswith("b"))
                s["sizing_1000"] = G.report(ts)
        desc = describe(per_hour, R.XAU_DESIGN)
        (R.RESULTS / "amendE_design.json").write_text(json.dumps(R._clean(dict(variants=res, describe=desc)), indent=1))
        (R.RESULTS / "amendE_design.md").write_text("# R140 Amendment E design results (IST hour, a = L1, b = G4)\n\n"
                                                    + R.table_md(res) + "\n")
        print(R.table_md(res))
        for row in desc:
            print(row)
    elif mode == "holdout":
        lock = R.RESULTS / "amendE_holdout.lock"
        if lock.exists():
            raise SystemExit("Amendment E holdout already read: " + lock.read_text())
        dz = json.loads((R.RESULTS / "amendE_design.json").read_text())["variants"]
        passing = [k for k, v in dz.items() if v["gates"]["all"]]
        lock.write_text(f"read {datetime.now().isoformat(timespec='seconds')} for {passing or 'no variant (none passed)'}\n")
        if not passing:
            print("no Amendment E rule passed the design gates; the holdout is not read")
            return
        res = {}
        for k in passing:
            h, need = int(k[1:3]), k.endswith("b")
            ts = rule_trades(per_hour[h], flags[h], HOLD, need)
            res[k] = R.summarise(k, ts, (HOLD,), False) | {"sizing_1000": G.report(ts)}
        (R.RESULTS / "amendE_holdout.json").write_text(json.dumps(R._clean(res), indent=1))
        print(json.dumps(R._clean(res), indent=1))
    elif mode == "recent":
        dz = json.loads((R.RESULTS / "amendE_design.json").read_text())["variants"]
        pick = [k for k, v in dz.items() if v["gates"]["all"]] or sorted(dz, key=lambda k: dz[k]["twin_p"])[:4]
        res = {}
        for k in pick:
            h, need = int(k[1:3]), k.endswith("b")
            ts = rule_trades(per_hour[h], flags[h], RECENT, need)
            res[k] = R.summarise(k, ts, (RECENT,), False) | {"sizing_1000": G.report(ts)}
        (R.RESULTS / "amendE_recent_seen.json").write_text(json.dumps(R._clean(res), indent=1))
        for k, s in res.items():
            print(k, s["n"], f"win {s['win']:.0%}", f"$/lot {s['total']:+,.0f}", json.dumps(R._clean(s["sizing_1000"])))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "design")
