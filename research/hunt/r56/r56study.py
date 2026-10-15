"""R56 study: runs Boss's plan and the context variants on every session, the random-entry twins, and writes
results/summary.json, results/report.md and results/trades.csv.

    python3 research/hunt/r56/r56study.py run
"""
from __future__ import annotations

import csv
import json
import sys
from collections import Counter, defaultdict

import numpy as np

import r56cfg as K
import r56data as D
import r56engine as R

PARTS = ("whole", "design", "recent")
LOSS_BUCKETS = ((0, 500), (500, 1000), (1000, 1500), (1500, 2000), (2000, 3000), (3000, float("inf")))


def in_part(d, part: str) -> bool:
    return part == "whole" or K.period(d) == part


def run_all(sessions, v=R.PRIMARY) -> list[dict]:
    return [R.run_session(s, v) for s in sessions]


def max_drawdown(nets) -> float:
    eq = np.cumsum(np.asarray(nets, dtype=float))
    if not len(eq):
        return 0.0
    peak = np.maximum.accumulate(np.concatenate([[0.0], eq]))[1:]
    return float(np.min(eq - peak))


def longest_losing_streak(nets) -> int:
    best = cur = 0
    for x in nets:
        cur = cur + 1 if x <= 0 else 0
        best = max(best, cur)
    return best


def worst_run(daily: list[float], n: int = 5) -> float:
    a = np.asarray(daily, dtype=float)
    if len(a) < n:
        return float(a.sum()) if len(a) else 0.0
    c = np.concatenate([[0.0], np.cumsum(a)])
    return float(np.min(c[n:] - c[:-n]))


def _basic(ts: list[dict]) -> dict:
    nets = [t["net"] for t in ts]
    w = [x for x in nets if x > 0]
    lo = [x for x in nets if x <= 0]
    return dict(trades=len(ts), win_rate=len(w) / len(ts) if ts else 0.0, net=float(sum(nets)),
                net_per_trade=float(np.mean(nets)) if ts else 0.0,
                avg_win=float(np.mean(w)) if w else 0.0, avg_loss=float(np.mean(lo)) if lo else 0.0)


def metrics(days: list[dict]) -> dict:
    """Every PREREG A6 metric on a list of run_session results (one per session, in date order)."""
    ts = [x["trade"] for x in days if x["trade"] is not None]
    nets = [t["net"] for t in ts]
    out = _basic(ts)
    out["sessions"] = len(days)
    out["status"] = dict(Counter(x["status"] for x in days))
    out["exits"] = {k: v / len(ts) for k, v in Counter(t["why"] for t in ts).items()} if ts else {}
    out["exit_counts"] = dict(Counter(t["why"] for t in ts))
    daily = [x["trade"]["net"] if x["trade"] else 0.0 for x in days]
    out["worst_day"] = float(min(nets)) if nets else 0.0
    out["best_day"] = float(max(nets)) if nets else 0.0
    out["worst_5_sessions"] = worst_run(daily, 5)
    out["max_dd"] = max_drawdown(nets)
    out["longest_losing_streak"] = longest_losing_streak(nets)
    months = defaultdict(list)
    for t in ts:
        months[t["day"].strftime("%Y-%m")].append(t)
    out["months"] = {m: _basic(v) for m, v in sorted(months.items())}
    out["months_green"] = sum(1 for m in out["months"].values() if m["net"] > 0)
    out["months_total"] = len(out["months"])
    out["expiry"] = _basic([t for t in ts if t["expiry"]])
    out["non_expiry"] = _basic([t for t in ts if not t["expiry"]])
    out["calls"] = _basic([t for t in ts if t["side"] > 0])
    out["puts"] = _basic([t for t in ts if t["side"] < 0])
    losses = np.array([-x for x in nets if x <= 0])
    out["loss"] = dict(
        n=int(len(losses)),
        over_1000=int((losses > 1000).sum()),
        over_1000_share=float((losses > 1000).mean()) if len(losses) else 0.0,
        median=float(np.median(losses)) if len(losses) else 0.0,
        p75=float(np.percentile(losses, 75)) if len(losses) else 0.0,
        p90=float(np.percentile(losses, 90)) if len(losses) else 0.0,
        worst=float(losses.max()) if len(losses) else 0.0,
        buckets={f"{a:g}-{b:g}": int(((losses > a) & (losses <= b)).sum()) if a else int((losses <= b).sum())
                 for a, b in LOSS_BUCKETS},
    )
    stop = [t for t in ts if t["why"] == "stop"]
    out["stop_exits"] = dict(n=len(stop), avg_net=float(np.mean([t["net"] for t in stop])) if stop else 0.0,
                             over_1000=sum(t["net"] < -1000 for t in stop))
    tg = [t for t in ts if t["why"] in ("target", "target_limit")]
    out["target_exits"] = dict(n=len(tg), avg_gross=float(np.mean([t["gross"] for t in tg])) if tg else 0.0,
                               avg_net=float(np.mean([t["net"] for t in tg])) if tg else 0.0,
                               avg_charges=float(np.mean([t["charges"] for t in tg])) if tg else 0.0)
    out["avg_charges"] = float(np.mean([t["charges"] for t in ts])) if ts else 0.0
    out["by_lot"] = {str(k): _basic([t for t in ts if t["lot"] == k]) for k in sorted({t["lot"] for t in ts})}
    return out


def twins(sessions, days: list[dict], n: int = K.TWINS, seed: int = K.SEED) -> dict:
    """PREREG A5: n random-entry twins; returns per part the twin nets and p."""
    by_day = {s.day: s for s in sessions}
    real = [x["trade"] for x in days if x["trade"] is not None]
    rng = np.random.default_rng(seed)
    tot = {p: np.zeros(n) for p in PARTS}
    unfilled = 0
    for i in range(n):
        for t in real:
            tw = R.twin_trade(by_day[t["day"]], t["side"], rng)
            if tw is None:
                unfilled += 1
                continue
            for p in PARTS:
                if in_part(t["day"], p):
                    tot[p][i] += tw["net"]
    out = {}
    for p in PARTS:
        net = sum(t["net"] for t in real if in_part(t["day"], p))
        out[p] = dict(rule_net=float(net), twin_mean=float(tot[p].mean()), twin_p5=float(np.percentile(tot[p], 5)),
                      twin_p95=float(np.percentile(tot[p], 95)),
                      p=float((1 + (tot[p] >= net - 1e-9).sum()) / (n + 1)))
    out["n"] = n
    out["unfilled_draws"] = unfilled
    return out


def fmt_rs(x: float) -> str:
    return f"Rs {x:+,.0f}"


def report(res: dict) -> str:
    L = ["# R56 results: Boss's manual NIFTY ORB plan", "",
         "Signal source: the NIFTY index (1-minute closes vs the 09:15-09:29 range). Instrument: ATM option, nearest "
         "expiry, 1 lot. Rupees per 1 lot, net of dated charges and slippage unless marked gross.", ""]
    for vid, block in res["variants"].items():
        tag = "" if vid == R.PRIMARY.id else " (CONTEXT ONLY, not a decision)"
        L += [f"## {vid}{tag}", "",
              "| part | trades | win | target / stop / sq-off | avg win | avg loss | net | net/trade | worst day | "
              "worst 5 sessions | max DD | losing streak | months green |", "|---|" + "---|" * 12]
        for p in PARTS:
            m = block[p]
            ex = m["exits"]
            keys = ("target_limit" if vid == R.C3.id else "target", "hard_stop" if vid == R.C2.id else "stop", "square_off")
            exs = " / ".join(f"{ex.get(k, 0):.0%}" for k in keys)
            L.append(f"| {p} | {m['trades']} | {m['win_rate']:.0%} | {exs} | {fmt_rs(m['avg_win'])} | {fmt_rs(m['avg_loss'])} | "
                     f"{fmt_rs(m['net'])} | {fmt_rs(m['net_per_trade'])} | {fmt_rs(m['worst_day'])} | "
                     f"{fmt_rs(m['worst_5_sessions'])} | {fmt_rs(m['max_dd'])} | {m['longest_losing_streak']} | "
                     f"{m['months_green']} / {m['months_total']} |")
        L.append("")
        if vid != R.PRIMARY.id:
            continue
        w = block["whole"]
        tw = res["twins"]
        L += ["### Random-entry twins (1,000; same days, same sides, same exits; entry minute uniform 09:30-14:59)", "",
              "| part | rule net | twin mean | twin 5%-95% | p |", "|---|---|---|---|---|"]
        for p in PARTS:
            x = tw[p]
            L.append(f"| {p} | {fmt_rs(x['rule_net'])} | {fmt_rs(x['twin_mean'])} | {fmt_rs(x['twin_p5'])} .. "
                     f"{fmt_rs(x['twin_p95'])} | {x['p']:.3f} |")
        L += ["", "### Sessions", ""]
        for p in PARTS:
            L.append(f"- {p}: {block[p]['sessions']} sessions, {block[p]['status']}")
        L += ["", "### Loss size (net, per losing trade)", "",
              "| part | losers | worse than -Rs 1,000 | median | 75th pct | 90th pct | worst | buckets (Rs) |",
              "|---|---|---|---|---|---|---|---|"]
        for p in PARTS:
            lo = block[p]["loss"]
            L.append(f"| {p} | {lo['n']} | {lo['over_1000']} ({lo['over_1000_share']:.0%}) | Rs {lo['median']:,.0f} | "
                     f"Rs {lo['p75']:,.0f} | Rs {lo['p90']:,.0f} | Rs {lo['worst']:,.0f} | "
                     + ", ".join(f"{k}: {v}" for k, v in lo["buckets"].items()) + " |")
        L += ["", "### Target exits: gross trigger vs net", "", "| part | target exits | avg gross | avg charges | avg net |",
              "|---|---|---|---|---|"]
        for p in PARTS:
            te = block[p]["target_exits"]
            L.append(f"| {p} | {te['n']} | {fmt_rs(te['avg_gross'])} | Rs {te['avg_charges']:,.0f} | {fmt_rs(te['avg_net'])} |")
        L += ["", "### Splits (whole span / design / recent)", "", "| split | " + " | ".join(PARTS) + " |", "|---|---|---|---|"]
        for key, name in (("expiry", "expiry days"), ("non_expiry", "other days"), ("calls", "calls"), ("puts", "puts")):
            L.append(f"| {name} | " + " | ".join(
                f"{block[p][key]['trades']} tr, {block[p][key]['win_rate']:.0%} win, {fmt_rs(block[p][key]['net'])}"
                for p in PARTS) + " |")
        L.append("| by lot | " + " | ".join(
            "; ".join(f"lot {k}: {v['trades']} tr {fmt_rs(v['net'])}" for k, v in block[p]["by_lot"].items())
            for p in PARTS) + " |")
        L += ["", "### Per month (whole span)", "", "| month | trades | win | net |", "|---|---|---|---|"]
        for mth, m in w["months"].items():
            L.append(f"| {mth} | {m['trades']} | {m['win_rate']:.0%} | {fmt_rs(m['net'])} |")
        L.append("")
    return "\n".join(L) + "\n"


def main(argv):
    if argv[:1] != ["run"]:
        print(__doc__)
        return
    ss = D.load()
    ss = [s for s in ss if K.period(s.day) != "out"]
    res = dict(window=[str(ss[0].day), str(ss[-1].day)], sessions=len(ss), variants={})
    prim = None
    for v in R.VARIANTS:
        days = run_all(ss, v)
        if v is R.PRIMARY:
            prim = days
        res["variants"][v.id] = {p: metrics([x for x in days if in_part(x["day"], p)]) for p in PARTS}
        print(v.id, {p: (res["variants"][v.id][p]["trades"], round(res["variants"][v.id][p]["net"]))
                     for p in PARTS}, flush=True)
    res["twins"] = twins(ss, prim)
    print("twins", {p: round(res["twins"][p]["p"], 3) for p in PARTS}, flush=True)
    K.RESULTS.mkdir(exist_ok=True)
    (K.RESULTS / "summary.json").write_text(json.dumps(res, indent=1, default=str))
    (K.RESULTS / "report.md").write_text(report(res))
    with open(K.RESULTS / "trades.csv", "w", newline="") as f:
        cols = ["day", "part", "side", "signal", "strike", "lot", "expiry", "entry_time", "entry", "target_px", "exit_time",
                "exit", "why", "gross", "charges", "net"]
        w = csv.writer(f)
        w.writerow(cols)
        for x in prim:
            t = x["trade"]
            if t is None:
                continue
            w.writerow([t["day"], K.period(t["day"]), "CE" if t["side"] > 0 else "PE", K.hm(t["sig_row"]), f"{t['strike']:g}",
                        t["lot"], int(t["expiry"]), K.hm(t["entry_row"]), f"{t['entry']:.2f}", f"{t['target_px']:.2f}",
                        K.hm(t["exit_row"]), f"{t['exit']:.2f}", t["why"], f"{t['gross']:.2f}", f"{t['charges']:.2f}",
                        f"{t['net']:.2f}"])


if __name__ == "__main__":
    main(sys.argv[1:])
