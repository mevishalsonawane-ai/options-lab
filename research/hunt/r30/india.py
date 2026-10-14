"""R30 India data (PREREG section 3): decision rows, point-in-time inputs, labels and option-trade outcomes.

    build(und, part)  -> <OUT>/india/<UND>_<part>.pkl  dict of frames for Q1 / Q3 / Q4 (+ the minute arrays for Q2)

Inputs at row t: the brain feature store's row t (look-ahead guarded) and the raw-minute sequence of rows <= t
(`seq_at`). Labels and trade outcomes read rows > t only and live in separate columns ("y_*", "tr_*") that are never
inputs (`input_cols` refuses them). `part="holdout"` is only built by run.py's one holdout read (holdout_gate).
"""
from __future__ import annotations

import json
import math
import pickle
from datetime import date, datetime

import numpy as np
import pandas as pd

import r30cfg as K
from research.brain import cube, labels as brain_labels
from research.obuy.config import LOT_SCHEDULE
from research.obuy.costs import Costs, Fills

FILLS = Fills("liq")
COSTS = Costs("app")
N = 375
O_, H_, L_, C_, V_, OI_, IV_ = range(7)
SEQ_CH = ("ret", "hl", "from_open", "vix_chg", "mask")


class HoldoutError(RuntimeError):
    pass


# ------------------------------------------------------------------------------------------------ holdout lock
def flag_path(kind: str):
    return K.OUT / f"HOLDOUT_READ_{kind}.json"


def holdout_gate(kind: str, status: str) -> None:
    """The ONE holdout read per market (PREREG section 2). 'start' refuses if a finished read exists; 'done' records
    it. A crash between the two leaves 'started', which the report must disclose if the read is resumed."""
    p = flag_path(kind)
    old = json.loads(p.read_text()) if p.exists() else None
    if status == "start":
        if old and old.get("status") == "done":
            raise HoldoutError(f"refused: the {kind} holdout was already read at {old.get('done_at')}")
        rec = dict(status="started", started_at=datetime.utcnow().isoformat(timespec="seconds") + "Z",
                   resumed=bool(old))
    else:
        rec = dict(old or {}, status="done", done_at=datetime.utcnow().isoformat(timespec="seconds") + "Z")
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(rec))


def holdout_open(kind: str) -> bool:
    p = flag_path(kind)
    return p.exists() and json.loads(p.read_text()).get("status") == "started"


# ------------------------------------------------------------------------------------------------ small helpers
def lot(und: str, d: date) -> int:
    out = 0
    for s, n in LOT_SCHEDULE[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


def atm(x: float, step: float) -> float:
    """Half-up rounding to the strike step (PREREG R1-R6)."""
    return math.floor(x / step + 0.5) * step


def charge(buy: bool, px: float, qty: int, und: str) -> float:
    if px <= 0 or qty <= 0:
        return 0.0
    return float(COSTS.charge(buy, np.array([px]), np.array([qty]), bse=und in K.BSE)[0])


def days_for(und: str, part: str) -> list[date]:
    return [d for d in cube.built_days(K.CACHE.root, und) if K.part(d) == part]


# ------------------------------------------------------------------------------------------------ minutes + sequence
class Minutes:
    """Every built session of an index, concatenated in time order (minutes only), with the 5 sequence channels.
    pos(day, row) gives the position of a grid row; seq_at(p) the N rows ending at p (rows <= p only)."""

    def __init__(self, und: str, to: date):
        days = [d for d in cube.built_days(K.CACHE.root, und) if d <= to]
        M, _ = cube.load(K.CACHE.root, und, days[0], days[-1], chain=False)
        M = M.sort_values("ts").reset_index(drop=True)
        self.M = M
        self.day = M.day.dt.date.values
        self.minute = M.minute.values.astype(int)
        self.idx = {(d, m): i for i, (d, m) in enumerate(zip(self.day, self.minute))}
        ok = M.flag.isin(["ok", "stale"]).values
        c = np.where(ok, M.close.values, np.nan)
        h = np.where(ok, M.high.values, np.nan)
        lo = np.where(ok, M.low.values, np.nan)
        o = np.where(ok, M.open.values, np.nan)
        self.c, self.h, self.l, self.o = c, h, lo, o
        vix = np.where(M.vix_flag.eq("ok").values, M.vix.values, np.nan) if "vix" in M else np.full(len(M), np.nan)
        sess_open = pd.Series(o).groupby(self.day).transform("first").values
        prev_c = pd.Series(c).ffill().shift(1).values             # the last real close before this row
        prev_v = pd.Series(vix).ffill().shift(1).values
        with np.errstate(all="ignore"):
            ch = np.column_stack([np.log(c / prev_c) * 100, (h - lo) / c * 100, np.log(c / sess_open) * 100,
                                  np.log(vix / prev_v) * 100])
        mask = np.isfinite(ch[:, 0]) & np.isfinite(ch[:, 1])
        ch = np.where(np.isfinite(ch), ch, 0.0)
        self.ch = np.column_stack([ch, mask.astype(float)]).astype(np.float32)
        self.is_exp = M.is_expiry_day.values.astype(bool)
        self.expiry = M.expiry.values

    def pos(self, d: date, r: int):
        return self.idx.get((d, r))

    def seq_at(self, p: int, n: int = K.SEQ_N) -> np.ndarray:
        """Rows p-n+1 .. p (never later). Before the first row: zeros with mask 0."""
        lo = p - n + 1
        if lo >= 0:
            return self.ch[lo:p + 1]
        out = np.zeros((n, self.ch.shape[1]), np.float32)
        out[-(p + 1):] = self.ch[:p + 1]
        return out


# ------------------------------------------------------------------------------------------------ option arrays
def chain_arrays(und: str, d: date) -> tuple[dict, pd.Timestamp | None]:
    """{(side, strike): (375, 7) O H L C V OI IV}, the chain's expiry. Missing bars stay NaN."""
    _, pc, _ = cube.day_paths(K.CACHE.root, und, d)
    if not pc.exists():
        return {}, None
    ch = pd.read_parquet(pc, columns=["ts", "side", "strike", "open", "high", "low", "close", "volume", "oi", "iv",
                                      "expiry", "flag"])
    ch = ch[ch.strike.notna()]
    m = ((ch.ts.dt.hour - 9) * 60 + ch.ts.dt.minute - 15).values
    traded = ch.flag.eq("ok").values
    out = {}
    for (s, k), g in ch.assign(m=m, tr=traded).groupby(["side", "strike"]):
        a = np.full((N, 7), np.nan)
        gg = g[g.tr]
        a[gg.m.values, O_] = gg.open.values
        a[gg.m.values, H_] = gg.high.values
        a[gg.m.values, L_] = gg.low.values
        a[gg.m.values, C_] = gg.close.values
        a[gg.m.values, V_] = gg.volume.values
        a[g.m.values, OI_] = g.oi.values                         # last reported OI (point in time)
        a[gg.m.values, IV_] = gg.iv.values
        out[(s, float(k))] = a
    exp = ch.expiry.dropna()
    return out, (pd.Timestamp(exp.iloc[0]) if len(exp) else None)


def vol5(a: np.ndarray, r: int, lt: int) -> float:
    return float(np.nansum(a[max(r - 5, 0):r, V_])) / max(lt, 1)


def sim_buy(a: np.ndarray, t: int, last: int, lt: int, und: str, stop: float | None = None,
            target: float | None = None, cut_until: int | None = None, nxt: np.ndarray | None = None,
            exit_next_open: int | None = None) -> dict | None:
    """Buy 1 lot at the open of row t+1; hold to the close of row `last` (or, with `nxt`, sell at the open of row
    `exit_next_open` of the next session's array of the same contract). Premium stop: a minute close <= (1 - stop) x
    fill, sold at that close with stop fills. Target: a resting sell at `target` rupees, filled when a later high
    reaches it. A row without a bar is skipped (never priced); if the planned exit bar is missing, the first bar after
    it (to 15:29) is used, else the last real close (flagged)."""
    r0 = t + 1
    if r0 >= N or not np.isfinite(a[r0, O_]) or a[r0, O_] <= 0:
        return None
    p0 = float(a[r0, O_])
    buy = float(FILLS.buy(np.array([p0]), np.array([vol5(a, r0, lt)]))[0])
    sell, why, xr, raw_exit = None, "time", None, None
    for r in range(r0, last + 1):
        if target is not None and np.isfinite(a[r, H_]) and a[r, H_] >= target:
            raw_exit = target
            sell = float(FILLS.sell(np.array([target]), np.array([vol5(a, r, lt)]))[0])
            why, xr = "target", r
            break
        c = a[r, C_]
        if stop is not None and np.isfinite(c) and c <= (1 - stop) * buy:
            raw_exit = float(c)
            sell = float(FILLS.sell(np.array([c]), np.array([vol5(a, r, lt)]), stop=True)[0])
            why, xr = "stop", r
            break
    if sell is None and nxt is not None:
        r = exit_next_open
        if not np.isfinite(nxt[r, O_]):
            return None
        raw_exit = float(nxt[r, O_])
        sell = float(FILLS.sell(np.array([raw_exit]), np.array([vol5(nxt, r, lt)]))[0])
        why, xr = "next_open", N + r
    if sell is None:
        cand = [r for r in range(last, N) if np.isfinite(a[r, C_])]
        if cand:
            xr = cand[0]
        else:
            prev = [r for r in range(r0, last + 1) if np.isfinite(a[r, C_])]
            if not prev:
                return None
            xr, why = prev[-1], "stale_exit"
        raw_exit = float(a[xr, C_])
        sell = float(FILLS.sell(np.array([raw_exit]), np.array([vol5(a, xr, lt)]))[0])
    net = (sell - buy) * lt - charge(True, buy, lt, und) - charge(False, sell, lt, und)
    slip = (buy - p0 + raw_exit - sell) * lt
    cut = False
    if cut_until is not None:
        src, lo_r = (nxt, xr - N + 1) if xr >= N else (a, xr + 1)
        if src is not None and lo_r <= cut_until:
            hh = src[lo_r:cut_until + 1, H_]
            cut = bool(np.isfinite(hh).any() and np.nanmax(hh) >= 2 * buy) and why != "target"
    return dict(net=net, net_x2=net - slip, buy=buy, sell=sell, why=why, exit_row=xr, cut=cut)


# ------------------------------------------------------------------------------------------------ features
def feature_rows(und: str, keys: list[tuple[date, int]]) -> pd.DataFrame:
    """Brain feature-store rows at (day, row) keys. Missing day/row -> all-NaN row."""
    want: dict[date, list[int]] = {}
    for d, r in keys:
        want.setdefault(d, []).append(r)
    parts = []
    base = K.CACHE.root / "features" / und
    for d, rows in sorted(want.items()):
        p = base / str(d.year) / f"{d}.parquet"
        if not p.exists():
            continue
        f = pd.read_parquet(p)
        m = (f.ts.dt.hour - 9) * 60 + f.ts.dt.minute - 15
        g = f[m.isin(rows)].copy()
        g.insert(0, "row", m[m.isin(rows)].values)
        g.insert(0, "day", d)
        parts.append(g)
    F = pd.concat(parts, ignore_index=True) if parts else pd.DataFrame(columns=["day", "row"])
    kk = pd.DataFrame(keys, columns=["day", "row"])
    return kk.merge(F, on=["day", "row"], how="left")


def input_cols(frame: pd.DataFrame) -> list[str]:
    """Tabular model inputs: brain features only (never labels y_*, trade outcomes tr_*, keys or precision)."""
    drop = {"day", "row", "ts", "precision", "und", "side", "q", "fold", "part"}
    cols = [c for c in frame.columns if c not in drop and not c.startswith(("y_", "tr_", "ctx_"))]
    num = [c for c in cols if pd.api.types.is_numeric_dtype(frame[c]) or pd.api.types.is_bool_dtype(frame[c])]
    return num


# ------------------------------------------------------------------------------------------------ build
def build(und: str, part: str) -> dict:
    if part == "holdout" and not holdout_open("india"):
        raise HoldoutError("the India holdout is built only inside run.py's one holdout read")
    if part not in ("design", "holdout"):
        raise ValueError(part)
    days = days_for(und, part)
    MM = Minutes(und, days[-1])
    # labels only from this part's own sessions (a label never reads another part's prices)
    sub = MM.M[np.isin(MM.day, np.array(days, dtype=object))]
    lab = brain_labels.compute(sub[["ts", "day", "flag", "close", "high", "low"]])
    lab.index = sub.index
    step = K.STEP[und]
    q1, q3, q4, seq1, seq3, seq4 = [], [], [], [], [], []
    all_days = cube.built_days(K.CACHE.root, und)
    nxt_day = {a: b for a, b in zip(all_days[:-1], all_days[1:])}
    for d in days:
        lt = lot(und, d)
        arr, exp = chain_arrays(und, d)
        is_exp = bool(MM.is_exp[MM.pos(d, 0)]) if MM.pos(d, 0) is not None else False
        # ---- Q1
        for t in K.Q1_ROWS:
            p = MM.pos(d, t)
            if p is None or not np.isfinite(MM.c[p]):
                continue
            rec = dict(und=und, day=d, row=t, is_exp=is_exp)
            for h in K.HORIZONS:
                rec[f"y_ret_{h}"] = lab.at[p, f"y_ret_{h}m"]
                rec[f"y_range_{h}"] = lab.at[p, f"y_range_{h}m"]
            k = atm(MM.c[p], step)
            last_ok = K.Q3_LAST if is_exp else K.EXIT_LAST
            for h in K.HORIZONS:
                for s in ("C", "P"):
                    tr = None
                    if t + h <= last_ok and (s, k) in arr:
                        tr = sim_buy(arr[(s, k)], t, t + h, lt, und, stop=K.Q1_STOP, cut_until=last_ok)
                    for f in ("net", "net_x2", "exit_row", "cut"):
                        rec[f"tr_{h}_{s}_{f}"] = np.nan if tr is None else tr[f]
            q1.append(rec)
            seq1.append(MM.seq_at(p))
        # ---- Q3 (expiry days)
        p = MM.pos(d, K.Q3_ROW)
        if is_exp and p is not None and np.isfinite(MM.c[p]) and arr:
            k = atm(MM.c[p], step)
            for s, kk in (("C", k + step), ("P", k - step)):
                a = arr.get((s, kk))
                if a is None or not np.isfinite(a[K.Q3_ROW, C_]) or a[K.Q3_ROW, C_] <= 0:
                    continue
                p0 = float(a[K.Q3_ROW, C_])
                hh = a[K.Q3_ROW + 1:K.Q3_LAST + 1, H_]
                mx = float(np.nanmax(hh)) if np.isfinite(hh).any() else np.nan
                oi0, oi1 = a[K.row(12, 0), OI_], a[K.Q3_ROW, OI_]
                tr = sim_buy(a, K.Q3_ROW, K.Q3_LAST, lt, und, target=K.Q3_MULT * p0)
                q3.append(dict(und=und, day=d, row=K.Q3_ROW, side=1 if s == "C" else -1, strike=kk,
                               y_blast=float(mx >= K.Q3_MULT * p0) if np.isfinite(mx) else np.nan,
                               y_x10=float(mx >= 10 * p0) if np.isfinite(mx) else np.nan,
                               ctx_logp0=math.log(p0), ctx_p0_spot=p0 / MM.c[p] * 100,
                               ctx_oi_chg=(oi1 / oi0 - 1) * 100 if oi0 and np.isfinite(oi0) and oi0 > 0 else np.nan,
                               ctx_iv=a[K.Q3_ROW, IV_],
                               tr_net=np.nan if tr is None else tr["net"],
                               tr_net_x2=np.nan if tr is None else tr["net_x2"]))
                seq3.append(MM.seq_at(p))
        # ---- Q4 (non-expiry days, next session's 09:16 open)
        p = MM.pos(d, K.Q4_ROW)
        nd = nxt_day.get(d)
        if not is_exp and p is not None and np.isfinite(MM.c[p]) and nd is not None and K.part(nd) == part:
            pn = MM.pos(nd, 1)
            y = np.nan
            if pn is not None and np.isfinite(MM.o[pn]):
                y = float(MM.o[pn] > MM.c[p]) if MM.o[pn] != MM.c[p] else np.nan
            rec = dict(und=und, day=d, row=K.Q4_ROW, y_gap=y)
            narr, nexp = chain_arrays(und, nd) if arr else ({}, None)
            k = atm(MM.c[p], step)
            for s, kk in (("C", k - step), ("P", k + step)):
                tr = None
                if (s, kk) in arr and (s, kk) in narr and exp is not None and nexp == exp:
                    tr = sim_buy(arr[(s, kk)], K.Q4_ROW, N - 1, lot(und, d), und, nxt=narr[(s, kk)],
                                 exit_next_open=1, cut_until=K.EXIT_LAST)
                for f in ("net", "net_x2", "cut"):
                    rec[f"tr_{s}_{f}"] = np.nan if tr is None else tr[f]
            q4.append(rec)
            seq4.append(MM.seq_at(p))
    out = {}
    for name, rows, seqs in (("q1", q1, seq1), ("q3", q3, seq3), ("q4", q4, seq4)):
        T = pd.DataFrame(rows)
        if len(T):
            F = feature_rows(und, list(zip(T.day, T.row)))
            T = pd.concat([T.reset_index(drop=True), F.drop(columns=["day", "row"]).reset_index(drop=True)], axis=1)
        out[name] = T
        out[name + "_seq"] = np.stack(seqs) if seqs else np.zeros((0, K.SEQ_N, len(SEQ_CH)), np.float32)
    out["sessions"] = days
    return out


def path(und: str, part: str):
    return K.OUT / "india" / f"{und}_{part}.pkl"


def save(und: str, part: str) -> dict:
    out = build(und, part)
    p = path(und, part)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(pickle.dumps(out))
    print(und, part, {k: len(v) for k, v in out.items()}, flush=True)
    return out


def load(und: str, part: str) -> dict:
    if part == "holdout" and not holdout_open("india"):
        raise HoldoutError("the India holdout is read only inside run.py's one holdout read")
    return pickle.loads(path(und, part).read_bytes())
