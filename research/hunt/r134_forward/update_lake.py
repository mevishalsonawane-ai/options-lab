"""R134 data step (PREREG section 4): sync the Drive lake (read only), then top up sessions after its last day from
the Dhan API into the LOCAL lake only. Prints one status line per step; never prints credentials."""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATA = HERE.parents[1] / "data"
sys.path.insert(0, str(HERE.parent / "r124_big_moves"))
import r124cfg as K  # noqa: E402

LAKE = K.K3.LAKE
RAW = K.K3.CACHE_ROOT / "r134_raw"
BUILT = K.K3.CACHE_ROOT / "r134_built"
FLAT = K.K3.CACHE_ROOT / "r134_flat"          # lake_fetch/lake_build use one flat folder of published files
SUB = {"index_1m_": "index_1m", "opt_": "options_rolling_1m"}
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
GROUPS = ["idx_1m"] + [f"opt_{u}_{s}" for u in UNDS for s in ("WEEK", "MONTH")]
SINCE = "2026-10-20"


def sh(cmd: list, **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def sync() -> str:
    LAKE.mkdir(parents=True, exist_ok=True)
    inc = ["--include", "index_1m_{NIFTY,BANKNIFTY,FINNIFTY,MIDCPNIFTY,SENSEX,INDIAVIX}_*"]
    a = sh(["rclone", "copy", "gdrive:IraAlgo-data-lake/index_1m", str(LAKE / "index_1m"), *inc])
    b = sh(["rclone", "copy", "gdrive:IraAlgo-data-lake/options_rolling_1m", str(LAKE / "options_rolling_1m"),
            "--include", "opt_{NIFTY,BANKNIFTY,FINNIFTY,MIDCPNIFTY,SENSEX}_*", "--transfers", "8"])
    return "sync ok" if a.returncode == 0 and b.returncode == 0 else f"sync failed ({a.returncode}, {b.returncode})"


def topup() -> str:
    if not os.environ.get("DHAN_ACCESS_TOKEN"):
        return "top-up skipped: no DHAN_ACCESS_TOKEN in the environment"
    for d in (RAW, BUILT, FLAT):
        shutil.rmtree(d, ignore_errors=True)
    FLAT.mkdir(parents=True)
    for sub in SUB.values():
        for p in (LAKE / sub).glob("*.parquet"):
            (FLAT / p.name).symlink_to(p)
    f = sh([sys.executable, "-I", str(DATA / "lake_fetch.py"), str(RAW), "--only-missing", str(FLAT), "--since", SINCE,
            *GROUPS], timeout=7200)
    errs = sum(1 for _ in RAW.rglob("*.err"))
    auth = any("DH-901" in p.read_text() for p in RAW.rglob("*.err"))
    if auth:
        return "top-up failed: the Dhan access token is invalid or expired (Boss: refresh DHAN_ACCESS_TOKEN)"
    groups = ["idx_1m", "opt"]
    b = sh([sys.executable, "-I", str(DATA / "lake_build.py"), str(RAW), str(BUILT), *groups], timeout=7200)
    if b.returncode != 0:
        return f"top-up build failed (exit {b.returncode})"
    n = 0
    for p in BUILT.glob("*.parquet"):
        sub = next((v for k, v in SUB.items() if p.name.startswith(k)), None)
        if sub is None:
            continue
        dest = LAKE / sub / p.name
        if dest.is_symlink() or dest.exists():
            dest.unlink()
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(p, dest)
        n += 1
    return f"top-up ok: {n} files rebuilt locally ({errs} chunk errors, fetch exit {f.returncode})"


if __name__ == "__main__":
    print(sync())
    print(topup())
