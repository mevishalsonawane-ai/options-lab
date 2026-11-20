"""R202 diagnosis: where R72, R51 and Liquidity 15+5 make and lose money under today's rules (V5), from the baseline trades
the final run saved (~/.cache/iraalgo/r202/r202_trades_final.pkl). Descriptive only: no rule is tested and nothing is gated.

    python3 -I research/hunt/r202_trend_exit/r202diag.py        # -> results/diagnosis.json

Every slice here was looked at AFTER the holdout was read, so a pattern in it is a lead, not a result. 1 lot, after dated
charges and slippage, 21 Nov 2024 - 5 Oct 2026 (the dhan-data build, PREREG 1). Plain-English notes, 20 Nov 2026.
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import numpy as np  # noqa: E402

import r202cfg as K  # noqa: E402
import r202lib as G  # noqa: E402

HALVES = (("2024-11", "2025-03"), ("2025-04", "2025-09"), ("2025-10", "2026-03"), ("2026-04", "2026-10"))
WINS = {"design": K.DESIGN, "holdout": K.HOLDOUT, "total": K.SPAN, "last_year": K.LAST_YEAR}


def ym(t) -> str:
    return f"{t['day'].year:04d}-{t['day'].month:02d}"


def brief(tr: list) -> dict:
    if not tr:
        return dict(trades=0, win=None, net=0, avg_win=None, avg_loss=None, per_trade=None)
    n = np.array([t["net"] for t in tr], float)
    w, l = n[n > 0], n[n <= 0]
    return dict(trades=len(tr), win=round(float((n > 0).mean()) * 100, 1), net=round(float(n.sum())),
                avg_win=round(float(w.mean())) if len(w) else None, avg_loss=round(float(l.mean())) if len(l) else None,
                per_trade=round(float(n.mean())))


def by(tr: list, fn) -> dict:
    g = defaultdict(list)
    for t in tr:
        g[str(fn(t))].append(t)
    return {k: brief(v) for k, v in sorted(g.items())}


def full(tr: list, w: str) -> dict:
    s = G.stats(G.in_window(tr, *WINS[w]), *WINS[w])
    return {k: s[k] for k in ("trades", "win", "net", "max_dd", "worst_month", "worst_day", "account_stop_ok")}


def arm_report(b: list) -> dict:
    n = np.array([t["net"] for t in b], float)
    g = np.array([t["gross"] for t in b], float)
    q = np.array([t["qty"] for t in b], float)
    best = np.array([t["best"] for t in b], float)
    pct = np.array([(t["rX"] - t["rE"]) / t["rE"] for t in b], float)
    hold = np.array([t["xr"] - t["er"] for t in b], float)
    why = np.array([t["why"] for t in b])
    months = defaultdict(float)
    days = defaultdict(float)
    for t in b:
        months[ym(t)] += t["net"]
        days[t["day"]] += t["net"]
    dv = np.array(list(days.values()))
    tm, sp = why == "time", why == "stop"
    return dict(
        all=brief(b), half_years={f"{lo}..{hi}": brief([t for t in b if lo <= ym(t) <= hi]) for lo, hi in HALVES},
        months={k: round(v) for k, v in sorted(months.items())},
        months_negative=int(sum(v < 0 for v in months.values())), months_under_13k=sorted(k for k, v in months.items() if v < K.MONTH_CAP),
        by_exit=by(b, lambda t: t["why"]), by_index=by(b, lambda t: t["und"]), by_side=by(b, lambda t: t["side"]),
        by_hour=by(b, G.b_hour), by_book=by(b, lambda t: (t["und"], t.get("book"))) if "book" in b[0] else None,
        by_book_holdout=by([t for t in b if t["day"] >= K.HOLDOUT[0]], lambda t: (t["und"], t.get("book"))) if "book" in b[0] else None,
        charges=dict(gross=round(float(g.sum())), charges=round(float((g - n).sum())), per_trade=round(float((g - n).mean())),
                     share_of_gross=round(float((g - n).sum() / g.sum()) * 100, 1)),
        one_more_tick_each_way=dict(cost=round(float((q * 2 * 0.05).sum())), share_of_net=round(float((q * 0.10).sum() / n.sum()) * 100, 1)),
        losers_once_up_500=dict(n=int(((n <= 0) & (best >= 500)).sum()), of=int((n <= 0).sum()), net=round(float(n[(n <= 0) & (best >= 500)].sum()))),
        winners=dict(avg_best=round(float(best[n > 0].mean())), avg_kept=round(float(n[n > 0].mean()))),
        time_exits=dict(n=int(tm.sum()), net=round(float(n[tm].sum())), never_up_250=int((tm & (best < 250)).sum()),
                        never_up_250_net=round(float(n[tm & (best < 250)].sum()))),
        stops=dict(n=int(sp.sum()), net=round(float(n[sp].sum())), within_5_min=int((sp & (hold <= 5)).sum()),
                   within_5_min_net=round(float(n[sp & (hold <= 5)].sum())), once_up_500=int((sp & (best >= 500)).sum()),
                   once_up_500_net=round(float(n[sp & (best >= 500)].sum())), worse_than_20pct=int((pct < -0.20).sum()),
                   worse_than_20pct_net=round(float(n[pct < -0.20].sum())), worst_pct=round(float(pct.min()), 3)),
        hold_minutes=dict(median=float(np.median(hold)), losers=float(np.median(hold[n <= 0])), winners=float(np.median(hold[n > 0]))),
        days=dict(n=len(dv), losing=int((dv < 0).sum()), worst=round(float(dv.min())), worst5=round(float(np.sort(dv)[:5].sum())),
                  best5=round(float(np.sort(dv)[-5:].sum())), most_trades=max(Counter(t["day"] for t in b).values())),
        cost_a_lot=dict(median=round(float(np.median([t["rE"] * t["qty"] for t in b]))), max=round(max(t["rE"] * t["qty"] for t in b))))


def run() -> dict:
    tr = pickle.loads((K.OUT / "r202_trades_final.pkl").read_bytes())
    B = {a: tr[a + ":B"] for a in K.ARMS}
    out = {"_meta": dict(note="descriptive, looked at after the holdout read; 1 lot; 21 Nov 2024 - 5 Oct 2026 (dhan-data build)")}
    for a in K.ARMS:
        out[a] = arm_report(B[a])
    tw51, only51 = G.split_twins(B["R51"], B["R72"])
    _, only72 = G.split_twins(B["R72"], B["R51"])
    twl, onlyl = G.split_twins(B["LIQ"], B["R72"] + B["R51"])
    out["twins"] = {w: dict(r51_same_as_r72=brief(G.in_window(tw51, *WINS[w])), r51_only=brief(G.in_window(only51, *WINS[w])),
                            r72_only=brief(G.in_window(only72, *WINS[w])), liq_same_as_donchian=brief(G.in_window(twl, *WINS[w])),
                            liq_only=brief(G.in_window(onlyl, *WINS[w]))) for w in WINS}
    out["twins"]["r51_only_by_index"] = by(only51, lambda t: t["und"])
    out["twins"]["r51_only_half_years"] = {f"{lo}..{hi}": brief([t for t in only51 if lo <= ym(t) <= hi]) for lo, hi in HALVES}
    allt = [t for a in K.ARMS for t in B[a]]
    ded = G.dedupe(B, ("R72", "LIQ", "R51"))
    out["account"] = {w: dict(all_three_each_counted=full(allt, w), one_position_per_option=full(ded, w),
                              r72_plus_r51=full(B["R72"] + B["R51"], w), r72_alone=full(B["R72"], w),
                              r72_plus_r51_only=full(B["R72"] + only51, w)) for w in WINS}
    months = defaultdict(float)
    days = defaultdict(lambda: defaultdict(float))
    for a in K.ARMS:
        for t in B[a]:
            months[ym(t)] += t["net"]
            days[t["day"]][a] += t["net"]
    ds = sorted(days)
    M = np.array([[days[d][a] for d in ds] for a in K.ARMS])
    out["account"]["months"] = {k: round(v) for k, v in sorted(months.items())}
    out["account"]["daily_correlation"] = {f"{a}-{b}": round(float(np.corrcoef(M[i], M[j])[0, 1]), 2)
                                           for i, a in enumerate(K.ARMS) for j, b in enumerate(K.ARMS) if i < j}
    out["account"]["worst_days"] = [(str(d), round(float(v))) for v, d in sorted(zip(M.sum(0), ds))[:6]]
    n, c = G.peak_use(allt)
    per_day = defaultdict(list)
    for t in allt:
        per_day[t["day"]].append(t)
    peaks = np.array([G.peak_use(v)[1] for v in per_day.values()])
    out["account"]["capital"] = dict(most_positions_at_once=n, most_premium_in_use=round(c), median_day_peak=round(float(np.median(peaks))),
                                     p90_day_peak=round(float(np.percentile(peaks, 90))), days=len(peaks),
                                     days_over_56k=int((peaks > 56000).sum()), days_over_1_lakh=int((peaks > 100000).sum()))
    (K.RESULTS / "diagnosis.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    r = run()
    print(json.dumps({k: r[k] for k in ("twins", "account")}, indent=1, default=str)[:6000])
