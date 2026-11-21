"""R202 data (PREREG.md section 1).

    python3 -I research/hunt/r202_price_action25/r202data.py lake          # raw -> ~/.cache/iraalgo/r202/lake
    python3 -I research/hunt/r202_price_action25/r202data.py stores [UND]  # R51's clean day stores, 1 Dec 2021 - 13 Nov 2026

The lake is built as R190's r190data.py did (its `join` is used): year files linked, each 2026 file joined with its
fill / gap parts keeping the first print of a minute. Only index minutes and WEEK option files are needed. Data are
untrusted: read with pandas only, run with `python3 -I`.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r202cfg as K  # noqa: E402

sys.path.insert(0, str(K.HUNT / "r190_best_hunt"))
from r190data import KEYS, _link, join  # noqa: E402


def build_lake():
    L, raw = K.LAKE, K.RAW
    L.mkdir(parents=True, exist_ok=True)
    for u in K.UNDS:
        for p in sorted((raw / "index_1m").glob(f"index_1m_{u}_20??.parquet")):
            if not p.name.endswith("_2026.parquet"):
                _link(p, L / p.name)
        parts = [raw / "index_1m" / f"index_1m_{u}_2026.parquet"] + sorted((raw / "index_1m").glob(f"index_1m_{u}_2026_*.parquet"))
        dst = L / f"index_1m_{u}_2026.parquet"
        if dst.is_symlink():
            dst.unlink()
        join(parts, KEYS["index"]).to_parquet(dst, index=False)
        for p in sorted((raw / "options_rolling_1m").glob(f"opt_{u}_WEEK_20??.parquet")):
            if not p.name.endswith("_2026.parquet"):
                _link(p, L / p.name)
        dst = L / f"opt_{u}_WEEK_2026.parquet"
        if dst.is_symlink():
            dst.unlink()
        join([raw / "options_rolling_1m" / f"opt_{u}_WEEK_2026.parquet",
              raw / "options_rolling_1m" / f"opt_{u}_WEEK_2026_gap1113.parquet"], KEYS["opt"]).to_parquet(dst, index=False)
        print("lake", u, flush=True)


def build_stores(unds):
    import r51data
    k = r51data.K
    k.WARM_START, k.START, k.END = K.STORE
    for u in unds:
        print(r51data.build(u), flush=True)


if __name__ == "__main__":
    cmd = (sys.argv[1:] or [""])[0]
    if cmd == "lake":
        build_lake()
    elif cmd == "stores":
        build_stores(sys.argv[2:] or K.UNDS)
    else:
        sys.exit(__doc__)
