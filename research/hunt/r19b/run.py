"""R19b runner (PREREG.md). Design first; the holdout is read ONCE, guarded by a flag file.

    python3 -m research.hunt.r19b.run design      # design period only -> results/design.json, results/design.md
    python3 -m research.hunt.r19b.run holdout     # the one holdout read  -> results/holdout.json, results/holdout.md
    python3 -m research.hunt.r19b.run today       # Boss's 13 Oct trade under each variant (arithmetic, illustration)
"""
from __future__ import annotations

import json
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

from research.brain.adapters.release import ReleaseAdapter
from research.brain import config as BC

from . import data as D
from . import sim as S

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
DESIGN = (date(2024, 2, 13), date(2025, 2, 28))
HOLDOUT = (date(2025, 3, 3), date(2025, 8, 22))
FOLDS = 3
FLIPS, TWINS, SEED = 20_000, 2_000, 19
BH_Q = 0.10
BIG = 2_000.0
MIN_TRADES = 30
TRAILS = [t for t in S.VARIANTS if t is not None]


def in_period(recs, lo, hi):
    return [r for r in recs if lo <= r["day"] <= hi]


def sessions(lo, hi):
    return [d for d in ReleaseAdapter(BC.paths().raw_release).sessions(D.UND) if lo <= d <= hi]


def daily(trades, days):
    by = {d: 0.0 for d in days}
    for t in trades:
        by[t["day"]] = by.get(t["day"], 0.0) + t["net"]
    return np.array([by[d] for d in days])


def max_dd(x):
    eq = np.cumsum(x)
    return float((eq - np.maximum.accumulate(np.maximum(eq, 0))).min()) if len(x) else 0.0


def summary(trades, days):
    net = np.array([t["net"] for t in trades])
    dl = daily(trades, days)
    why = {}
    for t in trades:
        why[t["why"]] = why.get(t["why"], 0) + 1
    return dict(trades=len(trades), win=float((net > 0).mean()) if len(net) else 0.0, net=float(net.sum()),
                per_trade=float(net.mean()) if len(net) else 0.0, worst_day=float(dl.min()) if len(dl) else 0.0,
                max_dd=max_dd(dl), exits=why)


def sign_flip_p(delta, n=FLIPS, seed=SEED):
    """One-sided: P(sum of randomly signed daily deltas >= the observed sum)."""
    d = delta[delta != 0]
    if len(d) == 0:
        return 1.0
    rng = np.random.default_rng(seed)
    obs = d.sum()
    signs = rng.choice([-1.0, 1.0], size=(n, len(d)))
    return float((1 + (signs @ d >= obs - 1e-9).sum()) / (n + 1))


def bh(ps, q=BH_Q):
    """Benjamini-Hochberg adjusted q-values."""
    ps = np.asarray(ps, float)
    m = len(ps)
    o = np.argsort(ps)
    adj = np.empty(m)
    run = 1.0
    for rank in range(m, 0, -1):
        i = o[rank - 1]
        run = min(run, ps[i] * m / rank)
        adj[i] = run
    return adj


def fixed_entry(recs, book, trail, ctrl):
    """The variant's exits on the control's own entries: cuts, MFE captured, big winners cut short."""
    only = {(t["day"], t["entry_min"]) for t in ctrl}
    var = {(t["day"], t["entry_min"]): t for t in S.run_book(recs, book, trail, only=only)}
    cut = lost_big = 0
    lost_rs = 0.0
    gross = mfe = 0.0
    d_fixed = 0.0
    for c in ctrl:
        v = var[(c["day"], c["entry_min"])]
        d_fixed += v["net"] - c["net"]
        if c["mfe"] > 0:
            gross += v["gross"]
            mfe += c["mfe"]
        if v["exit_min"] < c["exit_min"] or v["why"] != c["why"]:
            cut += 1
            if c["net"] >= BIG:
                lost_big += 1
                lost_rs += c["net"] - v["net"]
    big = sum(1 for c in ctrl if c["net"] >= BIG)
    return dict(changed=cut, delta_fixed=d_fixed, mfe_captured=gross / mfe if mfe > 0 else None,
                big_winners=big, big_cut=lost_big, big_cut_rs=lost_rs)


def ctrl_mfe_capture(ctrl):
    g = sum(c["gross"] for c in ctrl if c["mfe"] > 0)
    m = sum(c["mfe"] for c in ctrl if c["mfe"] > 0)
    return g / m if m > 0 else None


def twins(recs, ctrl, k, observed, n=TWINS, seed=SEED):
    """Share of random twins (cut k random control trades at a random minute, next open, market fill) the variant's
    fixed-entry delta beats."""
    if k == 0 or not ctrl:
        return None
    sers = {}
    for r in recs:
        for book, sigs in r["books"].items():
            for s in sigs:
                sers[(r["day"], s["strike"], s["right"])] = r["opts"].get((s["strike"], s["right"]))
    pool = []
    for c in ctrl:
        ser = sers.get((c["day"], c["strike"], "C" if c["side"] > 0 else "P"))
        lo, hi = c["entry_min"] + 1, c["exit_min"] - 1
        if ser is None or hi < lo:
            pool.append(None)
            continue
        alt = []
        for u in range(lo, hi + 1):
            p, _ = S.open_at_or_after(ser, u)
            x = S.sell_fill(p)
            alt.append((x - c["entry"]) * c["qty"] - S.round_trip(c["entry"], x, c["qty"]) - c["net"])
        pool.append(np.array(alt))
    rng = np.random.default_rng(seed)
    k = min(k, len(ctrl))
    sums = np.empty(n)
    for i in range(n):
        pick = rng.choice(len(ctrl), size=k, replace=False)
        sums[i] = sum(float(rng.choice(pool[j])) for j in pick if pool[j] is not None and len(pool[j]))
    return float((observed > sums).mean())


def folds(days, n=FOLDS):
    cut = np.array_split(np.array(days, dtype=object), n)
    return [(f[0], f[-1]) for f in cut]


def run_period(recs, days, with_twins=False, fill_info=True):
    out = {}
    for book, _ in S.BOOKS:
        ctrl = S.run_book(recs, book, None)
        cs = summary(ctrl, days)
        cs["mfe_captured"] = ctrl_mfe_capture(ctrl)
        cs["big_winners"] = sum(1 for c in ctrl if c["net"] >= BIG)
        out[(book, "CTRL")] = dict(summary=cs)
        cdl = daily(ctrl, days)
        for t in TRAILS:
            var = S.run_book(recs, book, t)
            vs = summary(var, days)
            delta = daily(var, days) - cdl
            fx = fixed_entry(recs, book, t, ctrl)
            row = dict(summary=vs, delta=float(delta.sum()), delta_daily=delta, fixed=fx)
            if fill_info:
                nxt = S.run_book(recs, book, t, trail_fill="next")
                row["delta_next_open_fill"] = float((daily(nxt, days) - cdl).sum())
            if with_twins:
                row["twin_beats"] = twins(recs, ctrl, fx["changed"], fx["delta_fixed"])
            out[(book, t.name)] = row
    return out


def jsonable(res):
    o = {}
    for (book, v), row in res.items():
        r = {k: x for k, x in row.items() if k != "delta_daily"}
        o[f"{book}|{v}"] = r
    return o


def table(res, title, extra_cols=()):
    L = [f"### {title}", "",
         "| book | variant | trades | win | net | per trade | Delta vs CTRL | Delta, next-open fill | MFE captured | big winners cut (Rs lost) | worst day | max DD | " +
         " | ".join(extra_cols) + (" |" if extra_cols else ""),
         "|---|---|---|---|---|---|---|---|---|---|---|---|" + "---|" * len(extra_cols)]
    for (book, v), row in res.items():
        s = row["summary"]
        if v == "CTRL":
            L.append(f"| {book} | CTRL | {s['trades']} | {100 * s['win']:.0f}% | Rs {s['net']:+,.0f} | Rs {s['per_trade']:+,.0f} | - | - | "
                     f"{_pct(s['mfe_captured'])} | of {s['big_winners']} | Rs {s['worst_day']:+,.0f} | Rs {s['max_dd']:+,.0f} |" +
                     " - |" * len(extra_cols))
            continue
        fx = row["fixed"]
        ex = [row.get(c, "") for c in extra_cols]
        L.append(f"| {book} | {v} | {s['trades']} | {100 * s['win']:.0f}% | Rs {s['net']:+,.0f} | Rs {s['per_trade']:+,.0f} | "
                 f"Rs {row['delta']:+,.0f} | Rs {row.get('delta_next_open_fill', 0):+,.0f} | {_pct(fx['mfe_captured'])} | "
                 f"{fx['big_cut']} of {fx['big_winners']} (Rs {fx['big_cut_rs']:,.0f}) | Rs {s['worst_day']:+,.0f} | Rs {s['max_dd']:+,.0f} |" +
                 "".join(f" {_fmt(x)} |" for x in ex))
    return L


def _pct(x):
    return "-" if x is None else f"{100 * x:.0f}%"


def _fmt(x):
    if isinstance(x, bool):
        return "yes" if x else "no"
    if isinstance(x, float):
        return f"{x:.3f}"
    return str(x)


def design(recs):
    days = sessions(*DESIGN)
    rd = in_period(recs, *DESIGN)
    res = run_period(rd, days)
    fl = folds(days)
    for (book, v), row in res.items():
        if v == "CTRL":
            continue
        fd = [float(sum(x for d, x in zip(days, row["delta_daily"]) if lo <= d <= hi)) for lo, hi in fl]
        row["fold_delta"] = fd
        row["gate1"] = bool(sum(x > 0 for x in fd) >= 2 and row["delta"] > 0)
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "design.json").write_text(json.dumps(dict(period=[str(x) for x in DESIGN], sessions=len(days),
                                                         folds=[[str(a), str(b)] for a, b in fl], rows=jsonable(res)),
                                                    indent=1, default=str))
    L = table(res, f"Design {DESIGN[0]} .. {DESIGN[1]} ({len(days)} sessions)", ("fold_delta", "gate1"))
    (RESULTS / "design.md").write_text("\n".join(L) + "\n")
    print("\n".join(L))


def flag():
    return D.out_dir() / "HOLDOUT_READ"


def holdout(recs):
    f = flag()
    if f.exists():
        sys.exit(f"The R19b holdout was already read ({f.read_text().strip()}). It is read once.")
    if not (RESULTS / "design.json").exists():
        sys.exit("Run the design step first.")
    f.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds"))
    dj = json.loads((RESULTS / "design.json").read_text())["rows"]
    days = sessions(*HOLDOUT)
    rh = in_period(recs, *HOLDOUT)
    res = run_period(rh, days, with_twins=True)
    keys = [k for k in res if k[1] != "CTRL"]
    ps = [sign_flip_p(res[k]["delta_daily"]) for k in keys]
    qs = bh(ps)
    for k, p, q in zip(keys, ps, qs):
        row = res[k]
        c = res[(k[0], "CTRL")]["summary"]
        s = row["summary"]
        row["p"] = p
        row["bh_q"] = float(q)
        row["gate1"] = dj[f"{k[0]}|{k[1]}"]["gate1"]
        row["gate2"] = bool(row["delta"] > 0 and q <= BH_Q)
        row["gate3"] = bool(row["twin_beats"] is not None and row["twin_beats"] >= 0.95)
        row["gate4"] = bool(s["worst_day"] >= c["worst_day"] - 1e-9 and s["max_dd"] >= c["max_dd"] - 1e-9)
        enough = s["trades"] >= MIN_TRADES
        row["verdict"] = ("PASS" if all(row[g] for g in ("gate1", "gate2", "gate3", "gate4")) and enough
                          else "inconclusive" if not enough else "FAIL")
    (RESULTS / "holdout.json").write_text(json.dumps(dict(period=[str(x) for x in HOLDOUT], sessions=len(days),
                                                          read_at=f.read_text().strip(), rows=jsonable(res)),
                                                     indent=1, default=str))
    L = table(res, f"Holdout {HOLDOUT[0]} .. {HOLDOUT[1]} ({len(days)} sessions, read once)",
              ("p", "bh_q", "twin_beats", "gate1", "gate2", "gate3", "gate4", "verdict"))
    (RESULTS / "holdout.md").write_text("\n".join(L) + "\n")
    print("\n".join(L))


def today():
    """Boss's 13 Oct MIDCPNIFTY trade, from the numbers he gave (no minute data): entry 195.45, lot 120, peak about
    +Rs 2,500 (about 216.30), actual exit 188.25 on the index stop. Illustration only: assumes the price fell steadily
    from the peak, so each trail fills at its trigger (before charges)."""
    e, lot, peak, actual = 195.45, 120, 195.45 + 2500 / 120, 188.25
    rows = [("CTRL (what happened)", actual, "index stop")]
    for t in TRAILS:
        trig = S.trail_trigger(t, e, peak, lot)
        rows.append((t.name, trig if trig is not None and trig > actual else actual,
                     "trail" if trig is not None and trig > actual else "index stop"))
    L = ["| variant | exit | why | P&L before charges |", "|---|---|---|---|"]
    for n, x, why in rows:
        L.append(f"| {n} | {x:.2f} | {why} | Rs {(x - e) * lot:+,.0f} |")
    print("\n".join(L))
    return L


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "design"
    if cmd == "today":
        today()
    else:
        recs = D.load()
        {"design": design, "holdout": holdout}[cmd](recs)
