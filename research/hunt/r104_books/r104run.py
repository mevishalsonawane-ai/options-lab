"""R104 runner (PREREG.md): run a registered entry rule on its market, price every trade, apply the gates.

    python research/hunt/r104_books/r104run.py design <family module> [rule ...]   # design + walk-forward
    python research/hunt/r104_books/r104run.py holdout                              # the ONE holdout read (all tests)
    python research/hunt/r104_books/r104run.py report                               # results/*.json -> results/report.md

A rule implementation is registered with @rule(rule_id, market, variants={name: kwargs}, insts=(...)) and is called as
fn(ctx, **kwargs) -> (intents, trail, xflag). ctx gives the instrument's series, bars, daily frame and helpers.
Intents may only use data up to their decision bar (tests/test_r104.py checks the look-ahead guard on every rule:
truncating the data after a decision never changes the decision).
"""
from __future__ import annotations

import json
import sys
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import r104cfg as K  # noqa: E402
import r104data as DA  # noqa: E402
import r104sim as SIM  # noqa: E402
import r104stats as ST  # noqa: E402

IMPL: dict = {}
MCX_SYMS = {"GOLD": ("GOLD", "GOLDM"), "SILVER": ("SILVER", "SILVERM"), "CRUDE": ("CRUDEOIL", "CRUDEOILM"),
            "NATURALGAS": ("NATURALGAS", "NATGASMINI")}
FUT_UNITS = {"GOLDM": 10.0, "SILVERM": 5.0, "CRUDEOILM": 10.0, "NATGASMINI": 250.0}


def rule(rid: str, market: str, variants: dict | None = None, insts: tuple = (), money: int = 1):
    """insts: index names (IDX / DLY / IDXF), book MCX names (GOLD, SILVER, CRUDE, NATURALGAS) or crypto symbols."""
    def deco(fn):
        IMPL[(rid, market)] = dict(fn=fn, variants=variants or {"base": {}}, insts=insts, money=money, _family=fn.__module__)
        return fn
    return deco


# ================================================================================================ context
@dataclass
class Ctx:
    market: str
    inst: str                      # index name, MCX mini symbol or crypto symbol
    s: object                      # Series (intraday) or daily Series
    daily: pd.DataFrame | None = None
    _bars: dict = field(default_factory=dict)

    def bars(self, tf: int):
        if tf not in self._bars:
            self._bars[tf] = DA.bars(self.s, tf)
        return self._bars[tf]

    def to_rows(self, b, arr, dtype=float):
        return SIM.per_bar_to_rows(b, arr, self.s.n, dtype)

    def day(self, i: int) -> date:
        return self.s.days[self.s.ses[i]]


def daily_series(df: pd.DataFrame, name: str, market: str):
    """A daily frame as a Series: one 'minute' per day (session = day)."""
    n = len(df)
    days = list(df.index)
    v = df["volume"].values.astype(float) if "volume" in df else np.zeros(n)
    ses = np.arange(n)
    return DA.Series(name, market, df.open.values.astype(float), df.high.values.astype(float),
                     df.low.values.astype(float), df.close.values.astype(float), v, ses, np.zeros(n, np.int64), days,
                     ses.copy(), ses.copy(), np.full(n, n), 0)


def make_ctx(market: str, inst: str) -> Ctx:
    if market == "IDX":
        s = DA.idx_series(inst)
        return Ctx(market, inst, s, DA.index_daily(inst))
    if market == "IDXF":
        s = DA.idx_series_long(inst, 2017 if inst == "NIFTY" else 2021)
        return Ctx(market, inst, s, DA.index_daily(inst))
    if market == "MCXI":
        s = DA.mcx_series(inst)
        return Ctx(market, inst, s, None)
    if market == "CRY":
        return Ctx(market, inst, DA.crypto_series(inst), None)
    if market == "DLY":
        d = DA.index_daily(inst).copy()
        fv = DA.fut_daily(inst)[["volume", "oi"]]
        d = d.join(fv, how="left")
        return Ctx(market, inst, daily_series(d, inst, "DLY"), d)
    if market == "MCXD":
        big = [b for b, m in MCX_SYMS.values() if m == inst][0]
        d = DA.back_adjusted(DA.fut_daily(big))
        return Ctx(market, inst, daily_series(d, inst, "MCXD"), d)
    if market == "STK":
        d = DA.equity_daily(inst)
        return Ctx(market, inst, daily_series(d, inst, "STK"), d)
    raise ValueError(market)


def instruments(market: str, insts: tuple) -> list:
    if market in ("IDX", "IDXF", "DLY"):
        return list(insts) or ["NIFTY", "BANKNIFTY"]
    if market in ("MCXI", "MCXD"):
        names = insts or ("GOLD", "SILVER", "CRUDE", "NATURALGAS")
        return [MCX_SYMS[n][1] for n in names]
    if market == "CRY":
        return list(insts) or ["BTCUSDT", "ETHUSDT"]
    if market == "STK":
        return list(insts)
    raise ValueError(market)


# ================================================================================================ run one rule
def _end_of(ctx: Ctx, multiday: bool = False):
    s = ctx.s
    if ctx.market in ("DLY", "MCXD", "STK"):
        return lambda i: s.n
    if multiday:
        if ctx.market == "MCXI":
            # never hold an MCX future across a contract roll: out at the last minute before the roll session
            rolls = sorted(i for i, d in enumerate(s.days) if d in s.meta.get("roll_days", set()))

            def end_mcx(i):
                ses = s.ses[min(i, s.n - 1)]
                nxt = [r for r in rolls if r > ses]
                return int(s.s1[nxt[0] - 1]) if nxt else s.n
            return end_mcx
        return lambda i: s.n
    return lambda i: int(s.sq[s.ses[min(i, s.n - 1)]])


def run_inst(rid: str, market: str, inst: str, var: dict, part: str, ctx: Ctx | None = None) -> pd.DataFrame:
    """Every position of one rule variant on one instrument in one period ('design' / 'holdout')."""
    imp = IMPL[(rid, market)]
    ctx = ctx or make_ctx(market, inst)
    intents, trail, xflag = imp["fn"](ctx, **{k: v for k, v in var.items() if not k.startswith("_")})
    s = ctx.s
    keep = [t for t in intents if 0 <= t[0] < s.n and K.period_of(market, ctx.day(int(t[0]))) == part]
    one_pos = var.get("_one_pos", True)
    ex = SIM.execute(s.o, s.h, s.l, keep, _end_of(ctx, var.get("_multiday", False)), trail, xflag, max_side_day=var.get("_max_side_day", 2),
                     day_of=lambda i: s.ses[i], one_pos=one_pos,
                      tab=var.get("_tab", False), tmode=var.get("_tmode", 0))
    rows = []
    for e in ex:
        d_in = ctx.day(e["f"])
        if not np.isfinite(e["xpx"]) and market not in ("IDX",):
            continue
        d_out = ctx.day(min(e["x"], s.n - 1))
        stop_x = e["reason"] == "stop"
        stop_e = e["kind"] == 1
        if market == "IDX":
            D = DA.store(inst).get(d_in)
            if D is None:
                continue
            e_row = int(s.tod[e["f"]] - K.NSE_OPEN) + (0 if e["kind"] == 0 else 1)
            x_row = int(s.tod[min(e["x"], s.n - 1)] - K.NSE_OPEN) + e["touch"]
            if d_out != d_in:
                x_row = 10 ** 6
            ref = e["ref"] if np.isfinite(e["ref"]) else e["fpx"]
            pr = SIM.price_option(D, inst, d_in, e["side"], e_row, x_row, ref, imp["money"], K.lot(inst, d_in), D.expiry)
            if pr is None:
                continue
        elif market in ("IDXF", "DLY"):
            pr = SIM.price_index_future(inst, d_in, d_out, e["side"], e["fpx"], e["xpx"], stop_x, stop_e,
                                        K.lot(inst, d_in))
        elif market in ("MCXI", "MCXD"):
            pr = SIM.price_mcx_future(inst, e["side"], e["fpx"], e["xpx"], stop_x, stop_e, FUT_UNITS[inst])
        elif market == "CRY":
            pr = SIM.price_crypto(e["side"], e["fpx"], e["xpx"], stop_x, stop_e)
        elif market == "STK":
            pr = SIM.price_stock(e["side"], e["fpx"], e["xpx"], stop_x, stop_e, d_in)
        else:
            raise ValueError(market)
        rows.append(dict(rule=rid, market=market, inst=inst, day=d_in, exit_day=d_out, side=e["side"], grp=e["grp"],
                         w=e["w"], reason=e["reason"], fpx=e["fpx"], xpx=e["xpx"], gross=pr["gross"], cost=pr["cost"],
                         net=pr["net"]))
    t = pd.DataFrame(rows)
    if len(t) == 0:
        return pd.DataFrame(columns=["rule", "market", "inst", "day", "side", "net", "gross", "cost", "reason", "fold"])
    # positions: sub-lots of one group are averaged by their weights (per lot of the position)
    t["wnet"], t["wg"], t["wc"] = t.net * t.w, t.gross * t.w, t.cost * t.w
    agg = t.groupby(["inst", "grp"], sort=False).agg(rule=("rule", "first"), market=("market", "first"),
                                                     day=("day", "first"), exit_day=("exit_day", "max"),
                                                     side=("side", "first"), reason=("reason", "last"),
                                                     net=("wnet", "sum"), gross=("wg", "sum"), cost=("wc", "sum"),
                                                     wsum=("w", "sum")).reset_index()
    for c in ("net", "gross", "cost"):
        agg[c] = agg[c] / agg.wsum
    agg["fold"] = [K.fold_of(market, d) for d in agg.day]
    return agg.drop(columns=["wsum"]).sort_values("day").reset_index(drop=True)


def run_rule(rid: str, market: str, part: str = "design") -> dict:
    """{variant: positions over all instruments}."""
    imp = IMPL[(rid, market)]
    out = {}
    ctxs = {i: make_ctx(market, i) for i in instruments(market, imp["insts"])}
    for vn, var in imp["variants"].items():
        parts = [run_inst(rid, market, i, var, part, ctxs[i]) for i in ctxs]
        parts = [p for p in parts if len(p)]
        out[vn] = (pd.concat(parts).sort_values("day").reset_index(drop=True) if parts
                   else run_inst(rid, market, list(ctxs)[0], var, "none", ctxs[list(ctxs)[0]]))
    return out


def evaluate_design(rid: str, market: str) -> dict:
    byv = run_rule(rid, market, "design")
    nf = len(K.PERIODS[market]["folds"])
    wf, picks, ff = ST.walk_forward(byv, nf)
    p = ST.boot_p(ST.daily_pnl(wf.sort_values("day")) if len(wf) else np.zeros(0))
    best = max(byv, key=lambda v: (byv[v].net.sum() if len(byv[v]) else -1e18, -list(byv).index(v)))
    res = dict(rule=rid, market=market, variants={v: dict(ST.summary(t), folds=ST.fold_nets(t, nf)) for v, t in byv.items()},
               wf=dict(ST.summary(wf), folds=ST.fold_nets(wf, nf), picks=picks, first_fold=ff), p=p, holdout_variant=best)
    OUTT = K.OUT / "trades"
    OUTT.mkdir(parents=True, exist_ok=True)
    for v, t in byv.items():
        t.to_parquet(OUTT / f"{rid}__{market}__{v}__design.parquet")
    return res


def save(name: str, obj):
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / f"{name}.json").write_text(json.dumps(obj, indent=1, default=str))


def load(name: str):
    p = K.RESULTS / f"{name}.json"
    return json.loads(p.read_text()) if p.exists() else None


def run_family(mod: str, only=()):
    import importlib
    importlib.import_module(mod)
    keys = [k for k in IMPL if (not only or k[0] in only)]
    allres = load(f"design_{mod}") or {}
    for rid, mk in keys:
        if IMPL[(rid, mk)]["_family"] != mod:
            continue
        if f"{rid}|{mk}" in allres and not only:      # resume: a finished test is not re-run unless named
            continue
        r = evaluate_design(rid, mk)
        allres[f"{rid}|{mk}"] = r
        w = r["wf"]
        print(f"{rid:24s} {mk:5s} n={w['n']:5d} win={0 if w['win'] is None else w['win']:.2f} net={w['net']:12,.0f} "
              f"folds={[round(x) for x in w['folds']]} p={r['p']:.3f}", flush=True)
        save(f"design_{mod}", allres)
    return allres


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        import r104run as _self                     # the module the families register into (not __main__)
        _self.run_family(sys.argv[2], tuple(sys.argv[3:]))
    else:
        raise SystemExit(__doc__)
