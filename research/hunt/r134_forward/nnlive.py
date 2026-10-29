"""R134: R123's single network N1 (the model trained on the whole R123 design period, the one that scored R123's holdout),
rebuilt from its saved weights so it can score new days.

The weights file holds only the three seeds' state dicts. Everything else the model needs (tabular scaling, minute-
channel scaling, range scaling, the 'neither' means) is refitted from R123's design rows exactly as R123's
run.py cmd_holdout did; check() proves it by reproducing R123's saved holdout predictions.

Inputs ($R123_PREDS, default <cache>/r123, from gdrive:IraAlgo-data-lake/derived/r123/cleaned/):
rows_design.parquet, rows_holdout.parquet, seq_holdout.npz, net_N1_holdout.pt, holdout_rows_preds_N1.parquet.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
R123 = HERE.parent / "r123_neural_70"
sys.path.insert(0, str(R123))
import r123cfg as K  # noqa: E402
import r123data as D  # noqa: E402
import r123net as M  # noqa: E402
import r123rules as RR  # noqa: E402
import run as RUN  # noqa: E402

PREDS = Path(os.environ.get("R123_PREDS", K.CACHE_ROOT / "r123")).expanduser()


def load_model():
    """(model, neither means) for N1 as fitted in R123's holdout read."""
    import torch
    Fd = pd.read_parquet(PREDS / "rows_design.parquet")
    Sh = dict(np.load(PREDS / "seq_holdout.npz"))
    m = M.NetModel(arch="N1")
    m.cols = list(D.TAB_COLS)
    days = np.array(sorted(Fd.day.unique()))
    cut = days[int(len(days) * (1 - K.VAL_SHARE))]
    tr = Fd[Fd.day < cut]
    m.tab = M.Tab().fit(tr[m.cols].values.astype(np.float64))
    store = M.SeqStore(Sh)
    store.fit(store.flat(tr.und.values, tr.gpos.values))
    m.store = store
    rg = tr[[f"y_range_{h}" for h in K.HS]].values
    m.rg_mu, m.rg_sd = np.nanmean(rg, 0), np.nanstd(rg, 0)
    saved = torch.load(PREDS / "net_N1_holdout.pt", weights_only=False)
    m.nets = []
    for st in saved["states"]:
        net = M.make_net(len(m.tab.keep))
        net.load_state_dict(st)
        net.eval()
        m.nets.append(net)
    m.temps = saved.get("temps")
    return m, RR.neither_means(Fd)


def score(m, mn, F: pd.DataFrame, seqs: dict) -> pd.DataFrame:
    """R123's prediction columns (pw_j, ev_j, pdir30, ...) for rows F built with sequences `seqs`."""
    pn = m.predict(F, M.SeqStore(seqs))
    return RR.attach(F, pn, mn)


def check(tol: float = 1e-4) -> dict:
    """Rebuilt model on R123's holdout rows vs R123's saved holdout predictions."""
    m, mn = load_model()
    Fh = pd.read_parquet(PREDS / "rows_holdout.parquet")
    Sh = dict(np.load(PREDS / "seq_holdout.npz"))
    P = score(m, mn, Fh, Sh)
    ref = pd.read_parquet(PREDS / "holdout_rows_preds_N1.parquet")
    cols = [f"pw_{j}" for j in range(16)] + [f"ev_{j}" for j in range(16)] + ["pdir30"]
    a = P.sort_values(["und", "day", "row"])[cols].values.astype(float)
    b = ref.sort_values(["und", "day", "row"])[cols].values.astype(float)
    pw = np.nanmax(np.abs(a[:, :16] - b[:, :16]))
    ev = np.nanmax(np.abs(a[:, 16:32] - b[:, 16:32]))
    sign = float(np.mean(np.sign(a[:, 16:32]) == np.sign(b[:, 16:32])))
    return dict(rows=len(a), max_pw_diff=float(pw), max_ev_diff=float(ev), ev_sign_agree=sign, ok=bool(pw < tol))


if __name__ == "__main__":
    print(check())
