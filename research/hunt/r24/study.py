#!/usr/bin/env python3
"""R24 study runner (PREREG.md). Research only, 13 Oct 2026.

    python3 research/hunt/r24/study.py step1      # design-period summary of the what-if table -> results/step1.md|json
    python3 research/hunt/r24/study.py step2      # back-tracking: itemsets, lift, the tree     -> results/step2.md|json
    python3 research/hunt/r24/study.py step3      # walk-forward + the final rules on design    -> results/step3_design.json
    python3 research/hunt/r24/study.py holdout    # the ONE holdout read (flag file)            -> results/step3_holdout.json
    python3 research/hunt/r24/study.py report     # results/report.md from the JSON files

Inputs: the what-if table (<cache>/whatif, whatif.py) and the brain feature store (<cache>/features), both built from
the Dhan lake. Features are read at the decision minute only (items.py); outcomes come only from the what-if table.
"""
from __future__ import annotations

import json
import sys
from datetime import date

import numpy as np
import pandas as pd

sys.path.insert(0, __import__("os").path.dirname(__import__("os").path.abspath(__file__)))
import items as I  # noqa: E402
import mine as MN  # noqa: E402
import r24cfg as K  # noqa: E402
import r24data as DA  # noqa: E402

OUTCOLS = ["key", "d", "und", "side", "offset", "mny", "entry_row", "filled", "e", "lot", "period", "dte", "is_expiry",
           "net_rs_15", "net_rs_30", "net_rs_60", "net_rs_1505", "net_rs_1520", "net_pct_15", "net_pct_30",
           "net_pct_60", "net_pct_1505", "net_pct_1520", "mfe_pct", "mae_pct", "mfe_rs", "mae_rs", "t_best",
           "t_worst", "mfe_pct_1505", "orb_net", "orb_why", "sweep_net", "sweep_why", "liq_net", "liq_why",
           "orb_net_x2", "sweep_net_x2", "liq_net_x2", "net_rs_1505_x2", "orb_net_pct"]
WICOLS = [c for c in OUTCOLS if c not in ("key", "d", "period")] + ["day"]
SKIP = {("trend", "pos"), ("vwapx", "pos"), ("room", "behind"), ("iv", "ivrank"), ("rv", "used")}


# ------------------------------------------------------------------------------------------------ assembly
class Data:
    """Everything a run needs, built once: the what-if lookups (outcomes) and the minute frame (features)."""

    def __init__(self, unds=K.UNDS, wi: pd.DataFrame | None = None, mf: pd.DataFrame | None = None):
        if wi is None:
            wi = DA.whatif(unds, WICOLS)
        if mf is None:
            mf = DA.frames(unds)
        self.wi = wi[wi.filled].reset_index(drop=True)
        self.idx = pd.Index(self.wi.key.values)
        self.mf = mf.reset_index(drop=True)
        self.dord = np.array([d.toordinal() for d in self.mf.d.values], dtype=np.int64)
        self.vals = I.raw_values(self.mf, self.mf.side.values)
        self.days_all = sorted(set(self.mf.d))
        self.blocks = DA.design_blocks(self.days_all)
        self.day_id = {d: i for i, d in enumerate(self.days_all)}

    def mask_days(self, days: set) -> np.ndarray:
        return np.isin(self.dord, np.array([d.toordinal() for d in days], dtype=np.int64))

    def period_days(self, period: str) -> set:
        return {d for d in self.days_all if K.period_of(d) == period}

    def block_days(self, ks) -> set:
        out = set()
        for k in ks:
            a, b = self.blocks[k]
            out |= {d for d in self.days_all if a <= d <= b}
        return out

    def cuts(self, days: set) -> dict:
        m = self.mask_days(days) & (self.mf.entry_row.values % K.GRID_EVERY == 0)
        return I.fit_cuts(self.vals, self.mf.und.values, m)

    def codes(self, cuts: dict) -> pd.DataFrame:
        return I.codes(self.vals, self.mf.side.values, self.mf.und.values, self.mf.entry_row.values, cuts)

    def lookup(self, und, d, entry_row, side, offset) -> np.ndarray:
        ks = DA.key(pd.Series(und).map(DA.UND_I).values, [x.toordinal() for x in d], entry_row, side, offset)
        return self.idx.get_indexer(ks)


def mining_rows(D: Data, codes: pd.DataFrame, days: set):
    """The back-tracking sample: grid rows (every 5th entry minute) of `days`, x 7 offsets, with outcomes."""
    m = D.mask_days(days) & (D.mf.entry_row.values % K.GRID_EVERY == 0)
    base = np.flatnonzero(m)
    reps, offs = [], []
    for o in K.OFFSETS:
        reps.append(base)
        offs.append(np.full(len(base), o, np.int8))
    rep = np.concatenate(reps)
    off = np.concatenate(offs)
    mf = D.mf.iloc[rep]
    pos = D.lookup(mf.und.values, mf.d.values, mf.entry_row.values, mf.side.values, off)
    ok = pos >= 0
    rep, off, pos = rep[ok], off[ok], pos[ok]
    Z = codes.iloc[rep].copy()
    sides = D.mf.side.values[rep]
    mny = np.where(sides > 0, -off, off)
    Z["mnyb"] = np.select([mny >= 2, mny == 1, mny == 0, mny == -1], [0, 1, 2, 3], 4).astype(np.int8)
    out = D.wi.iloc[pos].reset_index(drop=True)
    day = np.array([D.day_id[x] for x in D.mf.d.values[rep]])
    return Z.reset_index(drop=True), out, day


def labels_of(out: pd.DataFrame):
    w = (out.net_pct_1505 >= K.BIG).values
    l_ = (out.net_pct_1505 <= -K.BIG).values
    return w, l_


def run_mine(Z: pd.DataFrame, out: pd.DataFrame, day: np.ndarray) -> pd.DataFrame:
    w, l_ = labels_of(out)
    nb = [len(I.BY[n].bins) for n in I.NAMES]
    return MN.mine(Z[I.NAMES].values, nb, I.NAMES, day, w, l_,
                   {"net1505": out.net_rs_1505.values, "orb": out.orb_net.values},
                   max_items=K.MAX_ITEMS, min_support=K.MIN_SUPPORT, min_days=K.MIN_DAYS, skip_pairs=SKIP)


# ------------------------------------------------------------------------------------------------ rules
def fires(D: Data, codes: pd.DataFrame, pattern: tuple, days: set) -> pd.DataFrame:
    """The rule's entries: per index and session, the first entry minute whose decision-minute items match the pattern
    for one side (both sides at once: that minute is skipped). Returns und, d, entry_row, side, offset."""
    m = D.mask_days(days)
    mny = 0
    for n, c in pattern:
        if n == "mnyb":
            mny = I.MNYB_TO_MNY[c]
        else:
            m &= codes[n].values == c
    if not m.any():
        return pd.DataFrame(columns=["und", "d", "entry_row", "side", "offset"])
    f = D.mf.loc[m, ["und", "d", "entry_row", "side"]]
    both = f.groupby(["und", "d", "entry_row"]).side.transform("size") > 1
    f = f[~both].sort_values(["und", "d", "entry_row"]).drop_duplicates(["und", "d"], keep="first")
    f = f.assign(offset=np.where(f.side.values > 0, -mny, mny).astype(np.int8))
    return f.reset_index(drop=True)


def trades(D: Data, ent: pd.DataFrame) -> pd.DataFrame:
    """Outcome rows of the entries (unfilled ones dropped: no fill, no trade)."""
    if ent.empty:
        return D.wi.iloc[[]].copy()
    pos = D.lookup(ent.und.values, ent.d.values, ent.entry_row.values, ent.side.values, ent.offset.values)
    return D.wi.iloc[pos[pos >= 0]].reset_index(drop=True)


def best_family(t: pd.DataFrame) -> tuple[str, float]:
    nets = {f: float(t[f + "_net"].sum()) for f in K.FAMILIES}
    f = max(K.FAMILIES, key=lambda x: nets[x])
    return f, nets[f]


def select(D: Data, codes: pd.DataFrame, mined: pd.DataFrame, days: set, max_rules=K.MAX_RULES, cap=3000) -> list[dict]:
    """PREREG Step 3 selection on `days` only."""
    el = mined[(mined.lift_w >= K.MIN_LIFT) & (mined.days >= K.MIN_DAYS)]
    el = el[el["items"].map(lambda it: MN.fixes_side(it, I.SIDE_FIXING))]
    el = el.assign(score=el.lift_w * el.support).sort_values("score", ascending=False).head(cap)
    chosen = []
    for _, r in el.iterrows():
        t = trades(D, fires(D, codes, r["items"], days))
        if t.empty:
            continue
        fam, net = best_family(t)
        if not net > 0:
            continue
        ks = set(zip(t.und, t.d, t.side))
        if any(len(ks & c["keys"]) / len(ks) >= K.OVERLAP for c in chosen):
            continue
        chosen.append(dict(items=r["items"], family=fam, design_net=net, n=len(t), keys=ks, lift_w=r.lift_w,
                           support=r.support, days=r.days, score=r.score, size=r["size"]))
        if len(chosen) >= max_rules:
            break
    return chosen


def stats(t: pd.DataFrame, fam: str, sessions: list, x2=False) -> dict:
    """Per-rule numbers on one period: t = its trades, sessions = every session date of the period."""
    col = fam + ("_net_x2" if x2 else "_net")
    n = len(t)
    net = t[col].values.astype(float) if n else np.array([])
    by_day = pd.Series(net, index=t.d.values if n else []).groupby(level=0).sum() if n else pd.Series(dtype=float)
    daily = pd.Series(0.0, index=sorted(sessions))
    daily.loc[by_day.index] = by_day.values
    eq = daily.cumsum().values
    dd = float((eq - np.maximum.accumulate(np.r_[0.0, eq])[1:]).min()) if len(eq) else 0.0
    wins, losses = net[net > 0], net[net <= 0]
    big = t.mfe_pct_1505.values >= K.BIG if n else np.array([], bool)
    kept = (t[fam + "_net"].values / (t.e.values * t.lot.values) * 100) if n else np.array([])
    cut = big & (kept < 0.5 * t.mfe_pct_1505.values) if n else big
    return dict(trades=n, sessions=len(sessions), trades_per_day=n / max(len(sessions), 1),
                net=float(net.sum()), per_day=float(net.sum() / max(len(sessions), 1)),
                win_rate=float(len(wins) / n) if n else np.nan, avg_win=float(wins.mean()) if len(wins) else np.nan,
                avg_loss=float(losses.mean()) if len(losses) else np.nan,
                green_days=float((by_day > 0).mean()) if len(by_day) else np.nan,
                traded_days=int(len(by_day)), worst_day=float(by_day.min()) if len(by_day) else 0.0, max_dd=dd,
                big_reached=int(big.sum()), big_cut_short=int(cut.sum()))


def rule_name(r: dict) -> str:
    return I.describe(r["items"]) + f" -> exit {r['family']}"


def random_entry_p(D: Data, t: pd.DataFrame, fam: str, B=K.TWINS_ENTRY, seed=K.SEED) -> tuple[float, float]:
    """Random-entry twins: each trade re-entered at a uniform random entry minute 09:20-14:45, same day, side, offset
    and exit family; p = share of twin sets with net >= the rule's. Unfilled twin entries count as no trade (0)."""
    if t.empty:
        return 1.0, 0.0
    rng = np.random.default_rng(seed)
    T = len(t)
    rows = rng.integers(K.ENTRY_FIRST, K.ENTRY_LAST + 1, size=(B, T))
    und = np.tile(t.und.values, B)
    d = np.tile(t.d.values, B)
    pos = D.lookup(und, d, rows.ravel(), np.tile(t.side.values, B), np.tile(t.offset.values, B))
    v = np.where(pos >= 0, D.wi[fam + "_net"].values[np.maximum(pos, 0)], 0.0).reshape(B, T).sum(axis=1)
    real = float(t[fam + "_net"].sum())
    return float((1 + np.sum(v >= real)) / (B + 1)), float(v.mean())


def random_pattern_p(D: Data, codes: pd.DataFrame, mined: pd.DataFrame, rule: dict, design: set, hold: set,
                     real: float, n=K.TWINS_PATTERN, seed=K.SEED) -> tuple[float, int, float]:
    """Random-pattern twins: itemsets of the same size, design support within +-25%, fixing a side; each made into a rule
    the same way (exit chosen on design); p = share with holdout net >= the rule's."""
    s = rule["support"]
    pool = mined[(mined["size"] == rule["size"]) & (mined.support.between(s * (1 - K.PATTERN_SUPPORT_BAND),
                                                                         s * (1 + K.PATTERN_SUPPORT_BAND)))]
    pool = pool[pool["items"].map(lambda it: MN.fixes_side(it, I.SIDE_FIXING) and it != rule["items"])]
    if pool.empty:
        return 1.0, 0, 0.0
    rng = np.random.default_rng(seed)
    pick = pool.iloc[rng.choice(len(pool), size=min(n, len(pool)), replace=False)]
    vals = []
    for _, r in pick.iterrows():
        td = trades(D, fires(D, codes, r["items"], design))
        if td.empty:
            vals.append(0.0)
            continue
        fam, _ = best_family(td)
        th = trades(D, fires(D, codes, r["items"], hold))
        vals.append(float(th[fam + "_net"].sum()) if len(th) else 0.0)
    vals = np.array(vals)
    return float((1 + np.sum(vals >= real)) / (len(vals) + 1)), len(vals), float(vals.mean())


def block_nets(t: pd.DataFrame, fam: str, blocks) -> list[float]:
    out = [0.0] * len(blocks)
    for d, v in zip(t.d.values, t[fam + "_net"].values):
        b = DA.block_of(d, blocks)
        if b >= 0:
            out[b] += float(v)
    return out


# ------------------------------------------------------------------------------------------------ steps
def jdump(obj, name):
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / name).write_text(json.dumps(obj, indent=1, default=str))


def step1(D: Data) -> dict:
    """Design-period amounts: what buying each kind of option would have done, per lot, net of costs. The MONTH series
    (nearest monthly) is summarised the same way once its lake files exist."""
    out = summarise(D.wi[D.wi.period == "design"])
    m = DA.whatif(K.UNDS, WICOLS, series="MONTH")
    if len(m):
        m = m[m.filled & (m.period == "design")]
        out["month"] = summarise(m)
    jdump(out, "step1.json")
    return out


def summarise(w: pd.DataFrame) -> dict:
    out = {"rows": int(len(w)), "days": int(w.d.nunique()), "by": {}}
    w = w.assign(tod=np.digitize(w.entry_row, I.BY["tod"].edges),
                 dteb=np.digitize(w.dte.fillna(99), I.BY["dte"].edges),
                 big_w=w.net_pct_1505 >= K.BIG, big_l=w.net_pct_1505 <= -K.BIG)
    agg = dict(rows=("e", "size"), days=("d", "nunique"), premium=("e", "median"),
               net15=("net_rs_15", "mean"), net30=("net_rs_30", "mean"), net60=("net_rs_60", "mean"),
               net1505=("net_rs_1505", "mean"), net1520=("net_rs_1520", "mean"),
               pct1505_med=("net_pct_1505", "median"), mfe_med=("mfe_pct", "median"), mae_med=("mae_pct", "median"),
               mfe_rs_med=("mfe_rs", "median"), t_best_med=("t_best", "median"),
               orb=("orb_net", "mean"), sweep=("sweep_net", "mean"), liq=("liq_net", "mean"),
               orb_win=("orb_net", lambda x: float((x > 0).mean())), big_w=("big_w", "mean"), big_l=("big_l", "mean"))
    for name, keys in (("index_side_mny", ["und", "side", "mny"]), ("index_tod", ["und", "tod"]),
                       ("index_dte", ["und", "dteb"]), ("index", ["und"])):
        g = w.groupby(keys).agg(**agg).reset_index()
        out["by"][name] = g.round(3).to_dict(orient="records")
    return out


def step2(D: Data) -> dict:
    design = D.period_days("design")
    cuts = D.cuts(design)
    codes = D.codes(cuts)
    Z, out, day = mining_rows(D, codes, design)
    mined = run_mine(Z, out, day)
    w, l_ = labels_of(out)
    res = dict(rows=int(len(out)), days=int(len(set(day))), base_w=float(w.mean()), base_l=float(l_.mean()),
               base_net1505=float(out.net_rs_1505.mean()), base_orb=float(out.orb_net.mean()),
               itemsets=int(len(mined)), cuts={f"{a}|{b}": v for (a, b), v in cuts.items()})
    # second view: orb deciles per index
    q = out.groupby("und").orb_net.quantile([0.1, 0.9]).unstack()
    res["orb_deciles"] = q.round(1).to_dict(orient="index")
    top_o = out.orb_net.values >= out.und.map(q[0.9]).values
    bot_o = out.orb_net.values <= out.und.map(q[0.1]).values
    res["orb_top_share"], res["orb_bottom_share"] = float(top_o.mean()), float(bot_o.mean())
    mined2 = MN.mine(Z[I.NAMES].values, [len(I.BY[n].bins) for n in I.NAMES], I.NAMES, day, top_o, bot_o,
                     {"net1505": out.net_rs_1505.values, "orb": out.orb_net.values}, max_items=2,
                     min_support=K.MIN_SUPPORT, min_days=K.MIN_DAYS, skip_pairs=SKIP)

    def tab(df, by, n=20, asc=False):
        cols = ["items", "size", "n", "days", "support", "win", "lift_w", "lose", "lift_l", "cover_w", "cover_l",
                "mean_net1505", "mean_orb"]
        x = df.sort_values(by, ascending=asc).head(n)[cols].copy()
        x["text"] = x["items"].map(I.describe)
        return x.round(4).to_dict(orient="records")
    big = mined[mined.n >= 2000]
    res["top_lift_w"] = tab(big, "lift_w")
    res["top_lift_l"] = tab(big, "lift_l")
    res["top_orb"] = tab(big, "mean_orb")
    res["common_w"] = tab(mined, "cover_w")
    res["common_l"] = tab(mined, "cover_l")
    res["common_w_size1"] = tab(mined[mined["size"] == 1], "cover_w", 15)
    res["orb_view_top_lift"] = tab(mined2[mined2.n >= 2000], "lift_w", 15)
    res["orb_view_bottom_lift"] = tab(mined2[mined2.n >= 2000], "lift_l", 15)
    leaves = MN.tree_leaves(Z[I.NAMES].values, I.NAMES, w, {"net1505": out.net_rs_1505.values,
                                                          "orb": out.orb_net.values, "lose": l_.astype(float)})
    res["tree"] = leaves.round(4).to_dict(orient="records")
    K.OUT.mkdir(parents=True, exist_ok=True)
    mined.to_pickle(K.OUT / "mined_design.pkl")
    jdump(res, "step2.json")
    return res


def step3(D: Data) -> dict:
    """Walk-forward on the design blocks, then the final selection on the whole design period (no holdout here)."""
    res = {"blocks": [(str(a), str(b)) for a, b in D.blocks], "wf": []}
    for k in range(1, K.N_BLOCKS):
        train = D.block_days(range(k))
        test = D.block_days([k])
        cuts = D.cuts(train)
        codes = D.codes(cuts)
        Z, out, day = mining_rows(D, codes, train)
        mined = run_mine(Z, out, day)
        rules = select(D, codes, mined, train)
        fold = dict(test_block=k + 1, rules=[])
        tot = 0.0
        for r in rules:
            tt = trades(D, fires(D, codes, r["items"], test))
            st = stats(tt, r["family"], sorted(test))
            tot += st["net"]
            fold["rules"].append(dict(rule=rule_name(r), train_net=r["design_net"], test=st))
        fold["test_total"] = tot
        res["wf"].append(fold)
        print(f"WF block {k + 1}: {len(rules)} rules, test net {tot:,.0f}", flush=True)
    design = D.period_days("design")
    cuts = D.cuts(design)
    codes = D.codes(cuts)
    Z, out, day = mining_rows(D, codes, design)
    mined = run_mine(Z, out, day)
    rules = select(D, codes, mined, design)
    res["final"] = []
    for i, r in enumerate(rules):
        t = trades(D, fires(D, codes, r["items"], design))
        st = stats(t, r["family"], sorted(design))
        st_x2 = stats(t, r["family"], sorted(design), x2=True)
        fams = {f: float(t[f + "_net"].sum()) for f in K.FAMILIES}
        p_e, twin_mean = random_entry_p(D, t, r["family"])
        res["final"].append(dict(id=f"r24-p{i + 1}", items=[list(x) for x in r["items"]], rule=rule_name(r),
                                 family=r["family"], lift_w=r["lift_w"], support=r["support"], days=r["days"],
                                 size=r["size"], design=st, design_x2_net=st_x2["net"], families_design=fams,
                                 blocks=block_nets(t, r["family"], D.blocks), design_twin_p=p_e,
                                 design_twin_mean=twin_mean))
        print(f"rule {i + 1}: {rule_name(r)} design net {st['net']:,.0f} on {st['trades']} trades", flush=True)
    res["cuts"] = {f"{a}|{b}": v for (a, b), v in cuts.items()}
    jdump(res, "step3_design.json")
    return res


def holdout(D: Data) -> dict:
    """The ONE read of the locked holdout (2026-04-27 onwards). Refuses to run twice."""
    if K.HOLD_FLAG.exists() or (K.RESULTS / "step3_holdout.json").exists():
        raise SystemExit("the R24 holdout was already read (flag file or results/step3_holdout.json); a second read "
                         "needs a new pre-registration and a fresh holdout")
    hold = D.period_days("holdout")
    per_und = {u: len({d for d in hold if d in set(D.mf.d[D.mf.und == u])}) for u in K.UNDS}
    if min(per_und.values()) < K.MIN_HOLD_SESSIONS:
        raise SystemExit(f"holdout too short: {per_und}")
    prev = json.loads((K.RESULTS / "step3_design.json").read_text())
    design = D.period_days("design")
    cuts = {tuple(k.split("|")): tuple(v) for k, v in prev["cuts"].items()}
    codes = D.codes(cuts)
    Z, out, day = mining_rows(D, codes, design)
    mined = run_mine(Z, out, day)
    K.OUT.mkdir(parents=True, exist_ok=True)
    K.HOLD_FLAG.write_text(pd.Timestamp.now(tz="UTC").isoformat())
    res = {"sessions": per_und, "rules": []}
    for r in prev["final"]:
        items = tuple((n, int(c)) for n, c in r["items"])
        t = trades(D, fires(D, codes, items, hold))
        st = stats(t, r["family"], sorted(hold))
        st_x2 = stats(t, r["family"], sorted(hold), x2=True)
        p_e, twin_mean = random_entry_p(D, t, r["family"])
        rr = dict(items=items, family=r["family"], support=r["support"], size=r["size"])
        p_p, n_p, pat_mean = random_pattern_p(D, codes, mined, rr, design, hold, st["net"])
        res["rules"].append(dict(id=r["id"], rule=r["rule"], holdout=st, holdout_x2_net=st_x2["net"],
                                 entry_twin_p=p_e, entry_twin_mean=twin_mean, pattern_twin_p=p_p,
                                 pattern_twins=n_p, pattern_twin_mean=pat_mean,
                                 families_holdout={f: float(t[f + "_net"].sum()) for f in K.FAMILIES} if len(t) else {}))
        print(f"{r['id']}: holdout net {st['net']:,.0f} on {st['trades']} trades, entry p {p_e:.3f}, "
              f"pattern p {p_p:.3f}", flush=True)
    jdump(res, "step3_holdout.json")
    return res


def main(argv=None):
    cmd = (argv or sys.argv[1:])[0]
    D = Data()
    {"step1": step1, "step2": step2, "step3": step3, "holdout": holdout}[cmd](D)


if __name__ == "__main__":
    main()
