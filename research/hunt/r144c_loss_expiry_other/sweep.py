"""R144c part B (PREREG_B.md): a hindsight sweep for skip rules held in every period, with a whole-sweep chance check.

    python -I research/hunt/r144c_loss_expiry_other/sweep.py      # -> results/sweep.json

Every period has been read before (part A), so nothing here is a tested rule: survivors are leads for a forward log.
"""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import features as FE  # noqa: E402
import study as ST  # noqa: E402

ARMS = ("FT3", "HEROTKT", "NIGHT", "VIXDIV")
PERIODS = ("unseen", "design", "final")
RANDOM, SEED, LEAD_P = 1000, 1444, 0.10
MIN_SHARE, MAX_SHARE, MIN_SKIP = 0.05, 0.50, 5
MONTHLY_FROM = date(2024, 11, 20)


def extra(arm: str, t: dict) -> dict:
    f = dict(t["f"])
    f["side"] = t["side"]
    f["index"] = t["und"]
    if arm == "NIGHT":
        f.update(loc=t["loc"], move=t["move"], buz=t["buz"])
    if arm == "VIXDIV":
        f["expiry_day"] = "yes" if t.get("expiry") else "no"
    if arm == "FT3":
        f["monthly"] = "yes" if t["und"] in ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY") and t["day"] >= MONTHLY_FROM else "no"
    return f


def candidates(arm: str, tr: list) -> list:
    des = [t for t in tr if t["period"] == "design"]
    nums = list(FE.NUMERIC) + (["loc", "move", "buz"] if arm == "NIGHT" else [])
    out = []
    for k in nums:
        ed = ST.edges([t["fx"].get(k) for t in des])
        if ed is None:
            continue
        out += [dict(feature=k, kind="low", cut=round(ed[0], 4), label=f"{k} lowest fifth"),
                dict(feature=k, kind="low", cut=round(ed[1], 4), label=f"{k} lowest two fifths"),
                dict(feature=k, kind="high", cut=round(ed[3], 4), label=f"{k} highest fifth"),
                dict(feature=k, kind="high", cut=round(ed[2], 4), label=f"{k} highest two fifths")]
    cats = ["quadrant", "side", "index"] + (["expiry_day"] if arm == "VIXDIV" else []) + (["monthly"] if arm == "FT3" else [])
    for k in cats:
        for v in sorted({t["fx"].get(k) for t in tr if t["fx"].get(k) is not None}):
            out.append(dict(feature=k, kind="cat", value=v, label=f"{k} = {v}"))
    return out


def skip(c: dict, f: dict) -> bool:
    x = f.get(c["feature"])
    if c["kind"] == "cat":
        return x == c["value"]
    if x is None or not x == x:
        return False
    return x < c["cut"] if c["kind"] == "low" else x > c["cut"]


class Period:
    """One period's trades as arrays: nets, day index, month index; fast loss measures for a keep-mask."""

    def __init__(self, tr):
        self.net = np.array([t["net"] for t in tr], float)
        days = sorted({t["day"] for t in tr})
        months = sorted({(t["day"].year, t["day"].month) for t in tr})
        self.di = np.array([days.index(t["day"]) for t in tr])
        self.mi = np.array([months.index((t["day"].year, t["day"].month)) for t in tr])
        self.nd, self.nm = len(days), len(months)
        self.base_ls, self.base_wm = self.measures(np.ones(len(tr), bool))

    def measures(self, keep):
        d = np.bincount(self.di[keep], self.net[keep], minlength=self.nd)
        m = np.bincount(self.mi[keep], self.net[keep], minlength=self.nm)
        return float(d[d < 0].sum()), float(m.min()) if len(m) else 0.0

    def held(self, gone) -> tuple[bool, float]:
        """(held, saving share) for a skip-mask."""
        n, k = len(self.net), int(gone.sum())
        if k < MIN_SKIP or not MIN_SHARE <= k / max(n, 1) <= MAX_SHARE or not self.net[gone].sum() < 0:
            return False, 0.0
        ls, wm = self.measures(~gone)
        if not (ls > self.base_ls and wm >= self.base_wm):
            return False, 0.0
        return True, (ls - self.base_ls) / abs(self.base_ls) if self.base_ls else 0.0


def run() -> dict:
    rng = np.random.default_rng(SEED)
    out = {"_note": "hindsight: every period was read in part A; leads only (PREREG_B.md)", "arms": {}}
    allc = []                       # (arm, cand, per-period skip counts, score)
    pers = {}
    for arm in ARMS:
        tr = ST.load(arm)
        for t in tr:
            t["fx"] = extra(arm, t)
        P = {p: [t for t in tr if t["period"] == p] for p in PERIODS}
        pers[arm] = {p: Period(P[p]) for p in PERIODS}
        rows = []
        for c in candidates(arm, tr):
            masks = {p: np.array([skip(c, t["fx"]) for t in P[p]], bool) for p in PERIODS}
            res = {p: pers[arm][p].held(masks[p]) for p in PERIODS}
            held = all(r[0] for r in res.values())
            score = min(r[1] for r in res.values()) if held else -np.inf
            row = dict(c, held=held, weakest_saving=round(score, 4) if held else None)
            for p in PERIODS:
                g, k = masks[p], int(masks[p].sum())
                kept = [t for t, x in zip(P[p], g) if not x]
                s0, s1 = ST.stats(P[p]), ST.stats(kept)
                row[p] = dict(skipped=k, of=len(g), skipped_net=round(float(pers[arm][p].net[g].sum())),
                              skipped_loser_rate=round(float((pers[arm][p].net[g] <= 0).mean()), 3) if k else None,
                              win=(s0["win"], s1["win"]), net=(s0["net"], s1["net"]), loss_sum=(s0["loss_sum"], s1["loss_sum"]),
                              worst_month=(s0["worst_month"], s1["worst_month"]), max_dd=(s0["max_dd"], s1["max_dd"]))
            rows.append(row)
            allc.append((arm, len(rows) - 1, {p: int(masks[p].sum()) for p in PERIODS}, score))
        out["arms"][arm] = rows
    # the whole-sweep chance check: random skips of the same sizes for every candidate, best held score per sweep
    best = np.full(RANDOM, -np.inf)
    for i in range(RANDOM):
        b = -np.inf
        for arm, _, ks, _ in allc:
            sc = np.inf
            for p in PERIODS:
                per = pers[arm][p]
                n = len(per.net)
                if ks[p] > n:
                    sc = -np.inf
                    break
                g = np.zeros(n, bool)
                g[rng.choice(n, ks[p], replace=False)] = True
                ok, s = per.held(g)
                if not ok:
                    sc = -np.inf
                    break
                sc = min(sc, s)
            b = max(b, sc)
        best[i] = b
    out["random_best"] = dict(share_with_any_held=round(float(np.mean(np.isfinite(best))), 3),
                              q50=float(np.quantile(best[np.isfinite(best)], 0.5)) if np.isfinite(best).any() else None,
                              q90=float(np.quantile(best[np.isfinite(best)], 0.9)) if np.isfinite(best).any() else None)
    n_held = 0
    for arm, j, _, sc in allc:
        row = out["arms"][arm][j]
        if row["held"]:
            n_held += 1
            row["familywise_p"] = round(float((1 + (best >= sc).sum()) / (1 + RANDOM)), 4)
            row["lead"] = row["familywise_p"] <= LEAD_P
    out["counts"] = dict(candidates=len(allc), held=n_held,
                         leads=sum(1 for a in out["arms"].values() for r in a if r.get("lead")))
    (HERE / "results" / "sweep.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    o = run()
    print(o["counts"], o["random_best"])
    for arm, rows in o["arms"].items():
        for r in sorted([r for r in rows if r["held"]], key=lambda r: -r["weakest_saving"]):
            print(arm, r["label"], r.get("cut", ""), "save", r["weakest_saving"], "p", r["familywise_p"],
                  {p: (r[p]["skipped"], r[p]["of"], r[p]["skipped_net"], r[p]["win"]) for p in PERIODS})


def per_arm_check(n_random: int = RANDOM, seed: int = SEED + 1) -> dict:
    """ADDED AFTER the pre-registered run (PREREG_B amendment): the same max-statistic check within each arm only, so a
    near-all-losing arm (Hero ticket: any random skip saves its share) does not set the bar for the others. Descriptive."""
    o = json.loads((HERE / "results" / "sweep.json").read_text())
    rng = np.random.default_rng(seed)
    res = {}
    for arm in ARMS:
        tr = ST.load(arm)
        P = {p: Period([t for t in tr if t["period"] == p]) for p in PERIODS}
        rows = o["arms"][arm]
        sizes = [{p: r[p]["skipped"] for p in PERIODS} for r in rows]
        best = np.full(n_random, -np.inf)
        for i in range(n_random):
            b = -np.inf
            for ks in sizes:
                sc = np.inf
                for p in PERIODS:
                    n = len(P[p].net)
                    g = np.zeros(n, bool)
                    g[rng.choice(n, ks[p], replace=False)] = True
                    ok, s = P[p].held(g)
                    if not ok:
                        sc = -np.inf
                        break
                    sc = min(sc, s)
                b = max(b, sc)
            best[i] = b
        res[arm] = {r["label"]: round(float((1 + (best >= r["weakest_saving"]).sum()) / (1 + n_random)), 4)
                    for r in rows if r["held"]}
    o["per_arm_check_added_after"] = res
    (HERE / "results" / "sweep.json").write_text(json.dumps(o, indent=1, default=str))
    return res
