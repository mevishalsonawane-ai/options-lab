"""R6 trading variants on MCX. python3 -I design.py [design|holdout|smoke]
design  -> scratchpad/hunt/r6/{design_variants.csv, frozen.json, ...}
holdout -> ONCE: (A) the frozen picks, (B) every variant on the holdout (pre-registered secondary test, BH + RC)
smoke   -> pipeline check: request/trade counts only, no P&L printed
"""
from __future__ import annotations

import json
import zlib
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib6 as L6  # noqa: E402
import signals6 as S6  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

OUT = str(L6.OUT)
# (book name, signal series, instrument)
BOOKS = [("CRUDEOIL opt", "CRUDEOIL", "OPT"), ("NATURALGAS opt", "NATURALGAS", "OPT"),
         ("NATGASMINI opt", "NATGASMINI", "OPT"), ("GOLDM opt", "GOLDM", "OPT"), ("SILVERM opt", "SILVERM", "OPT"),
         ("CRUDEOILM fut", "CRUDEOIL", "CRUDEOILM"), ("NATGASMINI fut", "NATURALGAS", "NATGASMINI"),
         ("SILVERMIC fut", "SILVERM", "SILVERMIC"), ("GOLDPETAL fut", "GOLDM", "GOLDPETAL")]


def days_for(P, period):
    mask = (P["design"] if period == "design" else P["holdout"]) & ~P["roll"]
    return mask


_REQ = {}


def requests(sym, period):
    if (sym, period) not in _REQ:
        P = L6.load_panel(sym)
        mask = days_for(P, period)
        t0 = time.time()
        req = S6.build_all(P, np.nonzero(mask)[0])
        print(sym, period, "requests", len(req), "variants", req["var"].nunique(), f"{time.time() - t0:.0f}s", flush=True)
        _REQ[(sym, period)] = (P, mask, req)
    return _REQ[(sym, period)]


def run_book(book, sym, inst, period, only=None, seed=11, smoke=False):
    P, mask, req = requests(sym, period)
    if inst != "OPT":
        req = req.copy()
        req["var"] = req["var"].str.replace(r"\|OPT$", "|PCT", regex=True)
    if only is not None:
        req = req[req["var"].isin(only)].reset_index(drop=True)
    if smoke:
        req = req[req.di.isin(np.unique(req.di)[:5])]
    t0 = time.time()
    real = L6.run(P, req, inst)
    real = L6.nonoverlap(real)
    tw = L6.twins(real, seed)
    rnd = L6.run(P, tw, inst)
    print(book, period, "kept", len(real), "twins", len(rnd), "variants", real["var"].nunique(),
          f"{time.time() - t0:.0f}s", flush=True)
    df, daily = L6.summarize(real, rnd, P, mask)
    df.insert(0, "book", book)
    return P, mask, real, rnd, df, daily


def design():
    allrows = []
    for book, sym, inst in BOOKS:
        P, mask, real, rnd, df, daily = run_book(book, sym, inst, "design", seed=zlib.crc32(book.encode()) % 1000)
        rc, tst = L6.reality_check(daily)
        df["rc_p"] = df["var"].map(rc)
        df["t_daily"] = df["var"].map(tst)
        allrows.append(df)
        tag = book.replace(" ", "_")
        real[["var", "di", "s", "side", "x", "E", "X", "stop", "ent", "ex", "gross", "net"]].to_parquet(
            os.path.join(OUT, f"design_trades_{tag}.parquet"))
        rg = rnd[np.isfinite(rnd.net)]
        rg[["var", "gross", "net"]].astype({"gross": "float32", "net": "float32"}).to_parquet(
            os.path.join(OUT, f"design_rand_{tag}.parquet"))
    res = pd.concat(allrows, ignore_index=True)
    res["q_bh"] = L6.bh(res.p.values)
    res["q_bh_rand"] = L6.bh(res.p_rand.values)
    res = res.sort_values(["book", "fam", "t_daily"], ascending=[True, True, False])
    res.to_csv(os.path.join(OUT, "design_variants.csv"), index=False)
    picks = res[res.trades >= 30].groupby(["book", "fam"]).head(1)
    frozen = {"written": time.strftime("%Y-%m-%d %H:%M:%S"), "n_variants": int(len(res)),
              "picks": picks[["book", "fam", "var"]].to_dict("records")}
    with open(os.path.join(OUT, "frozen.json"), "w") as f:
        json.dump(frozen, f, indent=1)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_rows", 1000)
    print(picks[["book", "var", "trades", "rs_day", "rs_trade", "win", "t", "q_bh", "rand_trade", "p_rand", "maxdd",
                 "rc_p"]].round(3).to_string())
    print("variants", len(res), "positive rs_day", int((res.rs_day > 0).sum()), "min q", res.q_bh.min(),
          "min q_rand", res.q_bh_rand.min(), "min rc_p", res.rc_p.min())
    passed = res[(res.rs_day > 0) & (res.q_bh < 0.10) & (res.p_rand < 0.05) & (res.rc_p < 0.10)]
    print("design candidates (before holdout gates):", len(passed))
    print(passed[["book", "var", "trades", "rs_day"]].to_string())


def holdout():
    marker = os.path.join(OUT, "holdout_opened.txt")
    if os.path.exists(marker):
        sys.exit("holdout already opened once: " + open(marker).read())
    with open(marker, "w") as f:
        f.write(pd.Timestamp.now().isoformat())
    fr = json.load(open(os.path.join(OUT, "frozen.json")))
    picks = pd.DataFrame(fr["picks"])
    out = []
    for book, sym, inst in BOOKS:
        P, mask, real, rnd, df, daily = run_book(book, sym, inst, "holdout", seed=99)
        rc, tst = L6.reality_check(daily)
        df["rc_p"] = df["var"].map(rc)
        df["t_daily"] = df["var"].map(tst)
        df["is_pick"] = df["var"].isin(set(picks[picks.book == book]["var"]))
        out.append(df)
        tag = book.replace(" ", "_")
        real[["var", "di", "s", "side", "x", "E", "X", "stop", "ent", "ex", "gross", "net"]].to_parquet(
            os.path.join(OUT, f"holdout_trades_{tag}.parquet"))
        rg = rnd[np.isfinite(rnd.net)]
        rg[["var", "gross", "net"]].astype({"gross": "float32", "net": "float32"}).to_parquet(
            os.path.join(OUT, f"holdout_rand_{tag}.parquet"))
    res = pd.concat(out, ignore_index=True)
    res["q_bh"] = L6.bh(res.p.values)
    res["q_bh_rand"] = L6.bh(res.p_rand.values)
    res.to_csv(os.path.join(OUT, "holdout_variants.csv"), index=False)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_rows", 1000)
    pk = res[res.is_pick]
    print(pk[["book", "var", "trades", "rs_day", "rs_trade", "win", "rand_trade", "p_rand", "maxdd", "green_months"]]
          .round(3).to_string())
    print("holdout all variants", len(res), "positive", int((res.rs_day > 0).sum()), "min q", res.q_bh.min(),
          "min q_rand", res.q_bh_rand.min(), "min rc_p", res.rc_p.min())


def smoke():
    for book, sym, inst in BOOKS[:1] + BOOKS[5:6]:
        P, mask, real, rnd, df, daily = run_book(book, sym, inst, "design", smoke=True)
        print(book, "trades", len(real), "with price", int(np.isfinite(real.E).sum()), "twins priced",
              int(np.isfinite(rnd.net).sum()), "variants", real["var"].nunique())


if __name__ == "__main__":
    {"design": design, "holdout": holdout, "smoke": smoke}[sys.argv[1] if len(sys.argv) > 1 else "smoke"]()
