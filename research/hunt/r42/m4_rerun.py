"""R42: re-run M4's frozen code on inputs rebuilt from the data lake, raw and with bad prints removed (PREREG.md).

    python -I m4_rerun.py inputs WORK LAKE FLAGS VARIANT      # VARIANT raw | f1 | f2; FLAGS = census.py's out dir
    python -I m4_rerun.py build  WORK VARIANT SYM [SYM ...]   # runs a patched copy of research/hunt/m4/build.py

WORK/<VARIANT>/ mimics M4's scratchpad layout (m3/raw, strad_crude/cache, h28/events.csv, m4/work). The patched copies
of build.py / analyze.py change only file paths, plus two opt-in switches used for the twin baseline: R42_GRID (entry
grid in minutes, default 15) and R42_ONLY (only the M4 cell's candidates: morning, calls ATM+2/+3, DTE 11-20).
Cleaning = a flagged traded minute gets volume 0, which M4's build already reads as "no price"."""
import os
import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
M4 = HERE.parent / "m4"
BOOKS = ("SILVERM", "GOLDM", "NATURALGAS", "CRUDEOIL")
T0, T1 = pd.Timestamp("2025-08-05", tz="Asia/Kolkata"), pd.Timestamp("2026-10-13", tz="Asia/Kolkata")
OLD_SP = 'SP = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt")'


def _sub(src, old, new):
    assert src.count(old) == 1, f"patch target not found exactly once: {old[:60]!r}"
    return src.replace(old, new)


def patched_build():
    s = (M4 / "build.py").read_text()
    s = _sub(s, OLD_SP, 'import os\nSP = Path(os.environ["R42_SP"])')
    s = _sub(s, "GRID, CUT = 15, 23 * 60 + 25",
             'GRID, CUT = int(os.environ.get("R42_GRID", 15)), 23 * 60 + 25\nONLY = bool(os.environ.get("R42_ONLY"))\n'
             'TAG = os.environ.get("R42_TAG", "")')
    s = _sub(s, "        dte = int(md.dte.values[0])\n",
             "        dte = int(md.dte.values[0])\n        if ONLY and not (11 <= dte <= 20):\n"
             "            continue\n")
    s = _sub(s, "            if t < 9 * 60 + 15 or t % GRID or t > CUT - 15 or i >= cut_i:\n                continue\n",
             "            if t < 9 * 60 + 15 or t % GRID or t > CUT - 15 or i >= cut_i:\n                continue\n"
             "            if ONLY and t > 13 * 60 + 59:\n                continue\n")
    s = _sub(s, "            for k in range(-3, 4):\n                p = kpos.get(K0 + k * step)\n                if p is None:"
                "\n                    continue\n                for cp in (0, 1):",
             "            for k in ((2, 3) if ONLY else range(-3, 4)):\n                p = kpos.get(K0 + k * step)\n"
             "                if p is None:\n                    continue\n                for cp in ((0,) if ONLY else (0, 1)):")
    s = _sub(s, 'W / f"ent_{sym}.parquet"', 'W / f"ent{TAG}_{sym}.parquet"')
    s = _sub(s, 'W / f"sig_{sym}.parquet"', 'W / f"sig{TAG}_{sym}.parquet"')
    return s


def patched_analyze():
    s = (M4 / "analyze.py").read_text()
    s = _sub(s, OLD_SP, 'import os\nSP = Path(os.environ["R42_SP"])')
    s = _sub(s, 'RES = Path("/home/user/options-lab/research/hunt/m4/results")', 'RES = Path(os.environ["R42_RES"])')
    s = _sub(s, "def load(sym):\n    E = pd.read_parquet(W / f\"ent_{sym}.parquet\")",
             "def load(sym, tag=\"\"):\n    E = pd.read_parquet(W / f\"ent{tag}_{sym}.parquet\")")
    s = _sub(s, '    E["hold"] = E.day >= HOLD0\n',
             '    E["hold"] = E.day >= HOLD0\n    E["extra"] = E.day > pd.Timestamp("2026-10-08")  # R42: 9-12 Oct\n')
    return s


def write_inputs(work, lake, flags, variant):
    root = Path(work) / variant
    (root / "m3" / "raw").mkdir(parents=True, exist_ok=True)
    (root / "strad_crude" / "cache").mkdir(parents=True, exist_ok=True)
    (root / "h28").mkdir(parents=True, exist_ok=True)
    (root / "m4" / "work").mkdir(parents=True, exist_ok=True)
    pd.DataFrame(columns=["date", "kind"]).to_csv(root / "h28" / "events.csv", index=False)  # events unused by Q4
    log = []
    for book in BOOKS:
        fs = sorted((Path(lake) / "options_rolling_1m").glob(f"opt_{book}_MONTH_202[56].parquet"))
        d = pd.concat([pd.read_parquet(f) for f in fs], ignore_index=True)
        d = d[(d.ts >= T0) & (d.ts < T1) & d.offset.between(-3, 3)].reset_index(drop=True)
        d["side"] = d.side.astype(str)
        n_clean = 0
        if variant != "raw":
            fl = pd.read_parquet(Path(flags) / f"flags_{book}.parquet", columns=["ts", "offset", "side", f"{variant}_tr"])
            fl["side"] = fl.side.astype(str)
            m = d.merge(fl, on=["ts", "offset", "side"], how="left", validate="one_to_one")[f"{variant}_tr"]
            bad = m.fillna(False).astype(bool).values & (d.volume.values > 0)
            n_clean = int(bad.sum())
            d.loc[bad, "volume"] = 0
        log.append(dict(book=book, variant=variant, rows=len(d), cleaned=n_clean))
        d["k"] = d.offset.astype(int)
        d["cp"] = np.where(d.side == "C", 0, 1)
        spot = d.groupby("ts").spot.median()
        if book == "CRUDEOIL":
            for (k, cp), x in d.groupby(["k", "cp"]):
                nm = "ATM" if k == 0 else f"ATM{k:+d}"
                side = "CALL" if cp == 0 else "PUT"
                x[["ts", "strike", "close", "iv", "spot", "volume", "oi"]].to_parquet(
                    root / "strad_crude" / "cache" / f"c1_{nm}_{side}.parquet")
                if k == 0:  # next-month file only feeds M4's Q3 'nextiv' signal (not used by Q4): placeholder copy
                    x[["ts", "strike", "close", "spot"]].to_parquet(root / "strad_crude" / "cache" / f"c2_ATM_{side}.parquet")
        else:
            d[["ts", "k", "cp", "strike", "close", "iv", "oi", "volume"]].to_parquet(root / "m3" / "raw" / f"opt_{book}.parquet")
            spot.rename("spot").reset_index().to_parquet(root / "m3" / "raw" / f"spot_{book}.parquet")
    (root / "build_r42.py").write_text(patched_build())
    (root / "analyze_r42.py").write_text(patched_analyze())
    res = root / "results"
    res.mkdir(exist_ok=True)
    for f in ("spread_estimates.json", "q1_cells_CRUDEOIL_NATURALGAS_GOLDM_SILVERM.csv"):  # M4's own costs and cells
        shutil.copy(M4 / "results" / f, res / f)
    pd.DataFrame(log).to_csv(root / "inputs_log.csv", index=False)
    print(pd.DataFrame(log).to_string())


def build(work, variant, syms, extra_env=None):
    root = Path(work) / variant
    env = dict(os.environ, R42_SP=str(root), **(extra_env or {}))
    subprocess.run([sys.executable, "-I", str(root / "build_r42.py"), *syms], env=env, check=True)


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "inputs":
        write_inputs(*sys.argv[2:6])
    elif cmd == "build":
        build(sys.argv[2], sys.argv[3], sys.argv[4:])
    elif cmd == "build_twins":
        build(sys.argv[2], sys.argv[3], ["SILVERM"], dict(R42_GRID="1", R42_ONLY="1", R42_TAG="1m"))
