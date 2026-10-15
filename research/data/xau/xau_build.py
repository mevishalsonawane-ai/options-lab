#!/usr/bin/env python3
"""Build the gold lab pack from the Dukascopy XAUUSD bid / ask fetch, and optionally upload it to Google Drive.

  python research/data/xau/xau_build.py RAW.parquet OUT_DIR [--no-gaf] [--upload]
                                        [--remote gdrive:IraAlgo-data-lake/xauusd_1m]

OUT_DIR gets (README.md describes every file): xauusd_1m_clean.parquet, bars/xauusd_{5m,15m,1h,4h,1d}.parquet,
xauusd_1m_features.parquet, xauusd_1m_labels.parquet, nn/ (splits.json, norm_stats.json, dataset.json, month
shards), overview.html, qa.json, README.md and us_events.csv (copies of the repo files), MANIFEST.txt (sha256).
The data never goes into git. --upload copies OUT_DIR and the raw file to the Drive folder with `rclone copy
--checksum` and verifies it with `rclone check --one-way`.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import xau_bars  # noqa: E402
import xau_clean  # noqa: E402
import xau_features  # noqa: E402
import xau_labels  # noqa: E402
import xau_nn  # noqa: E402
import xau_overview  # noqa: E402

REMOTE = "gdrive:IraAlgo-data-lake/xauusd_1m"
FLAGS = ["flag_zero_vol_side", "flag_flat", "flag_stale", "flag_crossed", "flag_out_of_hours", "flag_wide_spread",
         "flag_bad_tick"]


def gap_report(df):
    """Gaps between rows longer than 5 minutes, sorted into weekend / daily break / other; missing weekdays."""
    g = df.gap_min.to_numpy()
    idx = np.flatnonzero(g > 5)
    out = {"weekend": 0, "daily_break": 0, "other": [], "other_count": 0}
    for i in idx:
        a, b = df.ts_utc.iloc[i - 1], df.ts_utc.iloc[i]
        if g[i] >= 24 * 60 and (b - a) >= pd.Timedelta(hours=40):
            out["weekend"] += 1
            continue
        mid = pd.date_range(a + pd.Timedelta(minutes=1), b - pd.Timedelta(minutes=1), freq="1min")
        if len(mid) and xau_clean.out_of_hours(pd.Series(mid)).mean() >= 0.5:
            out["daily_break"] += 1
            continue
        out["other_count"] += 1
        out["other"].append({"after": str(a), "before": str(b), "minutes": float(g[i])})
    out["other"] = sorted(out["other"], key=lambda r: -r["minutes"])[:40]
    days = pd.Series(pd.to_datetime(sorted(df.trading_day.unique())))
    allwd = pd.bdate_range(days.min(), days.max())
    out["missing_weekdays"] = [str(d.date()) for d in allwd.difference(pd.DatetimeIndex(days))]
    return out


def guard_check(raw, feat, cuts, log):
    """Look-ahead spot check on the real data: rebuild on data cut after row k, compare row k."""
    bad = []
    for k in cuts:
        f2 = xau_features.build_features(xau_clean.clean(raw.iloc[:k + 1])).iloc[k]
        for col in feat.columns[1:]:
            a, b = feat[col].iloc[k], f2[col]
            same = (pd.isna(a) and pd.isna(b)) if (pd.isna(a) or pd.isna(b)) else (
                np.isclose(float(a), float(b), rtol=1e-9, atol=1e-9) if isinstance(a, (float, np.floating, int,
                                                                                     np.integer)) else a == b)
            if not same:
                bad.append((int(k), col))
        log("guard cut", k, "mismatches so far", len(bad))
    return bad


def stats_table(qa, nn):
    """Markdown table of the built pack's numbers for the Drive copy of README.md."""
    g = qa["gaps"]
    sh = nn["shards"]
    n_split = {p: sum(v for k, v in sh.items() if k.startswith(p + "_")) for p in ("train", "validation", "final_test")}
    rows = [("first minute (UTC)", qa["first"]), ("last minute (UTC)", qa["last"]), ("1-minute rows", f"{qa['rows']:,}"),
            ("trading days", qa["trading_days"]),
            ("bars", ", ".join(f"{k} {v:,}" for k, v in qa["bars"].items())),
            ("gaps > 5 min", f"{g['weekend']} weekends, {g['daily_break']} daily breaks, {g['other_count']} other"),
            ("weekdays with no data", ", ".join(g["missing_weekdays"]) or "none"),
            ("spread USD (median / mean / p99 / max)", "{median:.3f} / {mean:.3f} / {p99:.3f} / {max:.2f}".format(
                **qa["spread"])),
            ("flags", ", ".join(f"{k[5:]} {v:,}" for k, v in qa["flags"].items())),
            ("NN samples", ", ".join(f"{k} {v:,}" for k, v in n_split.items())),
            ("GAF samples", f"{sum(nn['gaf_shards'].values()):,}"),
            ("look-ahead check on the real data", f"{len(qa['guard_check']['cuts'])} cuts, "
                                                  f"{len(qa['guard_check']['mismatches'])} mismatches")]
    return "| item | value |\n|---|---|\n" + "\n".join(f"| {k} | {v} |" for k, v in rows) + "\n"


def sha(fn):
    h = hashlib.sha256()
    with open(fn, "rb") as fh:
        for b in iter(lambda: fh.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("raw")
    ap.add_argument("out")
    ap.add_argument("--no-gaf", action="store_true")
    ap.add_argument("--upload", action="store_true")
    ap.add_argument("--remote", default=REMOTE)
    ap.add_argument("--guard-cuts", type=int, default=3)
    ap.add_argument("--fetch-log", help="the fetch's log, whose last line (rows ... missed ...) goes into qa.json")
    a = ap.parse_args(argv)
    log = lambda *x: print(*x, flush=True)  # noqa: E731
    os.makedirs(os.path.join(a.out, "bars"), exist_ok=True)
    raw = pd.read_parquet(a.raw)
    log("raw rows", len(raw))
    df = xau_clean.clean(raw)
    df.to_parquet(os.path.join(a.out, "xauusd_1m_clean.parquet"), compression="zstd", index=False)
    bars = xau_bars.all_bars(df)
    for tf, b in bars.items():
        b.to_parquet(os.path.join(a.out, "bars", f"xauusd_{tf}.parquet"), compression="zstd", index=False)
    log("bars", {k: len(v) for k, v in bars.items()})
    feat = xau_features.build_features(df)
    feat.to_parquet(os.path.join(a.out, "xauusd_1m_features.parquet"), compression="zstd", index=False)
    log("features", feat.shape)
    lab = xau_labels.build_labels(df, feat.atr14_15m)
    lab.to_parquet(os.path.join(a.out, "xauusd_1m_labels.parquet"), compression="zstd", index=False)
    log("labels", lab.shape)
    rng = np.random.default_rng(15)
    cuts = sorted(rng.choice(np.arange(len(df) // 2, len(df) - 1), a.guard_cuts, replace=False).tolist())
    bad = guard_check(raw.sort_values("ts_utc").drop_duplicates("ts_utc").reset_index(drop=True), feat, cuts, log)
    nn = xau_nn.build(df, feat, lab, os.path.join(a.out, "nn"), gaf=not a.no_gaf, log=log)
    st = xau_overview.summary_stats(df)
    gaps = gap_report(df)
    hits = {f"{s}_hit_{h}": {str(k): int(v) for k, v in lab[f"{s}_hit_{h}"].value_counts(dropna=False).items()}
            for s in ("long", "short") for h in (60, 240)}
    qa = {"rows": len(df), "first": str(df.ts_utc.iloc[0]), "last": str(df.ts_utc.iloc[-1]),
          "trading_days": int(df.trading_day.nunique()), "flags": {k: int(df[k].sum()) for k in FLAGS},
          "spread": {"median": float(df.spread.median()), "mean": float(df.spread.mean()),
                     "p99": float(df.spread.quantile(0.99)), "max": float(df.spread.max()),
                     "min": float(df.spread.min())},
          "spread_by_ist_hour": dict(zip(st["hours"], [round(x, 4) for x in st["spread_by_hour"]])),
          "absret_bp_by_ist_hour": dict(zip(st["hours"], [round(x, 3) for x in st["absret_by_hour"]])),
          "volume_by_ist_hour": dict(zip(st["hours"], [round(x, 1) for x in st["volume_by_hour"]])),
          "by_session": {s: {"absret_bp": round(a_, 3), "spread": round(b_, 4), "minutes": m}
                         for s, a_, b_, m in zip(st["sessions"], st["absret_by_session"], st["spread_by_session"],
                                                 st["minutes_by_session"])},
          "gaps": gaps, "bars": {k: len(v) for k, v in bars.items()}, "feature_columns": list(feat.columns),
          "label_columns": list(lab.columns), "label_hit_counts": hits,
          "guard_check": {"cuts": cuts, "mismatches": bad},
          "fetch_log_tail": open(a.fetch_log).read().strip().splitlines()[-1:] if a.fetch_log else None,
          "nn": {"shards": nn["shards"], "gaf_shards": nn["gaf_shards"], "shapes": nn["shapes"],
                 "splits": nn["splits"]}}
    with open(os.path.join(a.out, "qa.json"), "w") as fh:
        json.dump(qa, fh, indent=1, default=str)
    notes = [f"Bad-tick flags: {qa['flags']['flag_bad_tick']:,} minutes; wide-spread flags: "
             f"{qa['flags']['flag_wide_spread']:,}. Flags mark rows, nothing is deleted."]
    with open(os.path.join(a.out, "overview.html"), "w") as fh:
        fh.write(xau_overview.render(st, notes))
    shutil.copy(os.path.join(HERE, "us_events.csv"), os.path.join(a.out, "us_events.csv"))
    card = open(os.path.join(HERE, "README.md")).read().replace("<!-- STATS -->", stats_table(qa, nn), 1)
    with open(os.path.join(a.out, "README.md"), "w") as fh:
        fh.write(card)
    lines = ["# path\tsha256\tbytes"]
    for root, _, fs in os.walk(a.out):
        for f in sorted(fs):
            if f == "MANIFEST.txt":
                continue
            fn = os.path.join(root, f)
            lines.append(f"{os.path.relpath(fn, a.out)}\t{sha(fn)}\t{os.path.getsize(fn)}")
    with open(os.path.join(a.out, "MANIFEST.txt"), "w") as fh:
        fh.write("\n".join(sorted(lines)) + "\n")
    log("qa", json.dumps({k: qa[k] for k in ("rows", "first", "last", "flags")}, default=str))
    if bad:
        log("LOOK-AHEAD GUARD FAILED on the real data, nothing uploaded:", bad[:10])
        return 2
    if a.upload:
        for src, dst in ((a.out, a.remote), (a.raw, a.remote + "/raw")):
            cmd = ["rclone", "copy", src, dst, "--checksum", "--transfers", "4", "--retries", "5"]
            log("$", " ".join(cmd))
            if subprocess.run(cmd).returncode != 0:
                return 1
        if subprocess.run(["rclone", "check", a.out, a.remote, "--one-way"]).returncode != 0:
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
