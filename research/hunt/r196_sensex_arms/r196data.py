"""R196 data (PREREG.md section 2): one flat folder in R190's layout from the 2024 - 2026 Drive copy, then R51's clean
day stores for the five indices with R196's window (warm-up from 1 Jan 2024).

    python3 -I research/hunt/r196_sensex_arms/r196data.py lake      # ~/.cache/iraalgo/sx/lake
    python3 -I research/hunt/r196_sensex_arms/r196data.py stores    # ~/.cache/iraalgo/sx/out/r51/clean/day_<IDX>.pkl
    python3 -I research/hunt/r196_sensex_arms/r196data.py check     # coverage only (days per file; no prices summarised)

The data are untrusted: read with pandas only, run with `python3 -I`, never from inside the data folder.
Plain-English notes, 20 Nov 2026.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r196cfg as K  # noqa: E402

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
KEYS = {"index": ["ts"], "opt": ["ts", "side", "offset"]}


def _link(src: Path, dst: Path):
    if dst.exists() or dst.is_symlink():
        dst.unlink()
    dst.symlink_to(src)


def join(parts: list, keys: list):
    """Concatenate parts in order and keep the first row of each key (the main file wins over a fill part)."""
    import pandas as pd
    df = pd.concat([pd.read_parquet(p) for p in parts], ignore_index=True)
    for c in df.columns:
        if str(df[c].dtype) == "category":
            df[c] = df[c].astype(str)
    df["ts"] = pd.to_datetime(df.ts).dt.tz_convert("Asia/Kolkata")
    return df.drop_duplicates(keys, keep="first").sort_values(keys).reset_index(drop=True)


def build_lake():
    import pandas as pd
    L, raw = K.LAKE, K.RAW
    L.mkdir(parents=True, exist_ok=True)
    for p in sorted((raw / "index_1m").glob("index_1m_*_202[45].parquet")):
        _link(p, L / p.name)
    for u in UNDS + ("INDIAVIX",):
        parts = [raw / "index_1m" / f"index_1m_{u}_2026.parquet"] + sorted((raw / "index_1m").glob(f"index_1m_{u}_2026_*.parquet"))
        join(parts, KEYS["index"]).to_parquet(L / f"index_1m_{u}_2026.parquet", index=False)
    for p in sorted((raw / "options_rolling_1m").glob("opt_*_202[45].parquet")):
        _link(p, L / p.name)
    for u in UNDS:
        gap = raw / "options_rolling_1m" / f"opt_{u}_WEEK_2026_gap1113.parquet"
        for s in (("WEEK", "MONTH") if u == "BANKNIFTY" else ("WEEK",)):
            join([raw / "options_rolling_1m" / f"opt_{u}_{s}_2026.parquet", gap], KEYS["opt"]).to_parquet(
                L / f"opt_{u}_{s}_2026.parquet", index=False)
    _link(raw / "options_rolling_1m" / "opt_SENSEX_MONTH_2026.parquet", L / "opt_SENSEX_MONTH_2026.parquet")
    d = pd.concat([pd.read_parquet(p) for p in sorted((raw / "index_daily").glob("*.parquet"))], ignore_index=True)
    d = d.drop_duplicates(["symbol", "date"], keep="first").sort_values(["symbol", "date"])
    d.to_parquet(L / "index_daily.parquet", index=False)
    print("lake built", L, flush=True)


def build_stores():
    import r193cfg as C
    C.set_env()
    sys.path.insert(0, str(K.HUNT / "r51"))
    import r51cfg
    r51cfg.WARM_START, r51cfg.START, r51cfg.END = K.STORE
    import r51data
    for u in sys.argv[2:] or UNDS:
        print(u, r51data.build(u), flush=True)


def check():
    import pandas as pd
    for p in sorted(K.LAKE.glob("*.parquet")):
        df = pd.read_parquet(p, columns=["ts"] if "daily" not in p.name else ["date"])
        ts = pd.to_datetime(df[df.columns[0]])
        print(p.name, len(df), ts.min(), ts.max(), ts.dt.date.nunique(), flush=True)


if __name__ == "__main__":
    {"lake": build_lake, "stores": build_stores, "check": check}.get((sys.argv[1:] or [""])[0],
                                                                     lambda: sys.exit(__doc__))()
