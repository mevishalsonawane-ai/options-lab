"""Export R123's N1 holdout network (the one S5 Trail was tested with) for the app's paper arm. 28 Oct 2026.

    python research/hunt/r128_missing/export_s5.py <out_dir>

Writes:
    s5net.bin      little-endian float32 blob: the 3 seeds' weights, then the input scalers (see LAYOUT)
    s5net.json     the layout, the 42 input names, the cells, the 'neither' means and a checksum
    s5golden.json  8 decision rows: their raw inputs, the raw 120-minute window and the network's outputs (for the
                   Kotlin test, which must match to 1e-4)

The scalers are refitted exactly as run.py's holdout fit did (training rows = design rows before the 80% day cut;
sequence scale from 20,000 windows drawn with seed K.SEED), and the script refuses to write anything unless its own
numpy forward pass reproduces the saved holdout predictions (preds_N1_holdout.npz) to 1e-5.
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r123_neural_70"))
import r123cfg as K  # noqa: E402
import r123data as D  # noqa: E402
import r123net as M  # noqa: E402
import r123rules as R  # noqa: E402

LAYOUT = ("inp.weight", "inp.bias", *[f"convs.{i}.conv.{w}" for i in range(4) for w in ("weight", "bias")],
          "tab.0.weight", "tab.0.bias", "trunk.0.weight", "trunk.0.bias", "trunk.3.weight", "trunk.3.bias",
          "head_a.weight", "head_a.bias", "head_b.weight", "head_b.bias", "head_c.weight", "head_c.bias")


def relu(x):
    return np.maximum(x, 0.0)


def forward(st: dict, seq: np.ndarray, tab: np.ndarray):
    """numpy N1 forward pass for one row: seq (120, 7) already scaled, tab (42,) already scaled.
    Returns probs (16, 3) softmax, range (2,) raw head, dir (2,) sigmoid."""
    h = st["inp.weight"][:, :, 0] @ seq.T + st["inp.bias"][:, None]          # (32, 120)
    for i, dil in enumerate((1, 2, 4, 8)):
        w, b = st[f"convs.{i}.conv.weight"], st[f"convs.{i}.conv.bias"]
        pad = np.concatenate([np.zeros((32, 2 * dil)), h], 1)
        out = np.zeros_like(h)
        for k in range(3):
            out += w[:, :, k] @ pad[:, k * dil:k * dil + h.shape[1]]
        h = h + relu(out + b[:, None])
    enc = np.concatenate([h[:, -1], h[:, -15:].mean(1), h.mean(1)])
    t = relu(st["tab.0.weight"] @ tab + st["tab.0.bias"])
    z = relu(st["trunk.0.weight"] @ np.concatenate([enc, t]) + st["trunk.0.bias"])
    z = relu(st["trunk.3.weight"] @ z + st["trunk.3.bias"])
    la = (st["head_a.weight"] @ z + st["head_a.bias"]).reshape(16, 3)
    la = la - la.max(1, keepdims=True)
    p = np.exp(la) / np.exp(la).sum(1, keepdims=True)
    rg = st["head_b.weight"] @ z + st["head_b.bias"]
    dr = 1 / (1 + np.exp(-(st["head_c.weight"] @ z + st["head_c.bias"])))
    return p, rg, dr


def main(out_dir: Path) -> None:
    import torch
    blob = torch.load(K.OUT / "net_N1_holdout.pt", weights_only=False)
    states = [{k: v.double().numpy() for k, v in s.items()} for s in blob["states"]]
    Fd, _ = D.load("design")
    Fh = pd.read_parquet(K.OUT / "rows_holdout.parquet")
    Sh = dict(np.load(K.OUT / "seq_holdout.npz"))
    cols = list(D.TAB_COLS)
    days = np.array(sorted(Fd.day.unique()))
    cut = days[int(len(days) * (1 - K.VAL_SHARE))]
    tr = Fd[Fd.day < cut]
    tab = M.Tab().fit(tr[cols].values.astype(np.float64))
    assert len(tab.keep) == len(cols), "a tabular column was dropped; the app expects all 42"
    store = M.SeqStore(Sh).fit(M.SeqStore(Sh).flat(tr.und.values, tr.gpos.values))
    rg = tr[[f"y_range_{h}" for h in K.HS]].values
    rg_mu, rg_sd = np.nanmean(rg, 0), np.nanstd(rg, 0)
    mn = R.neither_means(Fd)

    # check: our forward pass reproduces the saved holdout predictions
    saved = np.load(K.OUT / "preds_N1_holdout.npz")
    rng = np.random.default_rng(7)
    pick = np.sort(rng.choice(len(Fh), 400, replace=False))
    X = tab.transform(Fh[cols].values.astype(np.float64)).astype(np.float64)
    fp = store.flat(Fh.und.values, Fh.gpos.values)
    worst = 0.0
    for i in pick:
        W = store.window(fp[i:i + 1])[0].astype(np.float64)
        ps = [forward(s, W, X[i]) for s in states]
        p = np.mean([q[0] for q in ps], 0)
        r = np.mean([q[1] for q in ps], 0) * rg_sd + rg_mu
        worst = max(worst, float(np.abs(p - saved["probs"][i]).max()), float(np.abs(r - saved["range"][i]).max()))
    print(f"max |numpy - saved| over 400 holdout rows: {worst:.2e}")
    if worst > 1e-5:
        raise SystemExit("refused: the export does not reproduce the tested network")

    out_dir.mkdir(parents=True, exist_ok=True)
    parts, layout = [], []
    for s_i, st in enumerate(states):
        for k in LAYOUT:
            a = st[k].astype("<f4").ravel()
            layout.append(dict(seed=s_i, name=k, shape=list(st[k].shape), n=int(a.size)))
            parts.append(a)
    for name, a in (("tab.med", tab.med), ("tab.mu", tab.mu), ("tab.sd", tab.sd), ("seq.mu", store.mu[:5]),
                    ("seq.sd", store.sd[:5]), ("range.mu", rg_mu), ("range.sd", rg_sd)):
        a = np.asarray(a, dtype="<f4").ravel()
        layout.append(dict(seed=None, name=name, shape=[int(a.size)], n=int(a.size)))
        parts.append(a)
    raw = np.concatenate(parts).astype("<f4").tobytes()
    (out_dir / "s5net.bin").write_bytes(raw)
    neither = {u: [mn.get((u, j), 0.0) for j in range(len(K.CS))] for u in K.UNDS}
    meta = dict(
        what="R123 N1 holdout network (3 seeds) for the S5 Trail paper arm (R128 T2-S5x1); Boss's exception, 28 Oct",
        trained_on=f"design rows to {cut} (early-stopping slice to {K.DESIGN[1]})", sha256=hashlib.sha256(raw).hexdigest(),
        floats=len(raw) // 4, layout=layout, tab_cols=cols, seq_channels=list(D.SEQ_CH), seq_n=K.SEQ_N, clip=K.CLIP,
        cells=[dict(x=x, y=y, h=h) for x, y, h in K.CELLS], sides=list(K.SIDES), neither_mean=neither,
        ev_threshold=K.RULES["S5"]["thr"], decision_rows=list(K.MORNING_ROWS), unds=list(K.UNDS), lot=K.LOT,
        step=K.STEP)
    (out_dir / "s5net.json").write_text(json.dumps(meta, indent=1))

    gold = []
    for i in pick[:8]:
        raw_w = store.A[fp[i] - K.SEQ_N + 1:fp[i] + 1]
        p = saved["probs"][i]
        ev = [float(p[j, 0] * K.CELLS[j // 2][0] - p[j, 1] * K.CELLS[j // 2][1] + p[j, 2] * neither[Fh.und.iat[i]][j])
              for j in range(len(K.CS))]
        gold.append(dict(und=Fh.und.iat[i], day=str(Fh.day.iat[i]), row=int(Fh.row.iat[i]),
                         tab_raw=[None if not np.isfinite(v) else float(v) for v in Fh[cols].values[i]],
                         seq_raw=raw_w.astype(float).tolist(),
                         probs=p.astype(float).tolist(), range=saved["range"][i].astype(float).tolist(),
                         dir=saved["dir"][i].astype(float).tolist(), ev=ev))
    (out_dir / "s5golden.json").write_text(json.dumps(gold))
    print("written", out_dir, len(raw) // 4, "floats")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
