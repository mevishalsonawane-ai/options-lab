#!/usr/bin/env python3
"""Write research/data/STOCK_OPTIONS_COVERAGE.json: per stock and ATM offset, the fraction of planned stock-option
chunks (CALL and PUT, 30-day windows) that lake_fetch.py finished (.parquet or .empty). 1.0 = complete.

  python -I stkopt_coverage.py RAW_DIR [OUT_JSON] [--since YYYY-MM-DD]
  --since: count only windows ending on or after the date (20 Oct 2026: Boss asked for data from Nov 2024 only)
"""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lake_fetch as lf


def coverage(jobs, since=None):
    tot, done = {}, {}
    for j in jobs:
        if since is not None and lf.window(j)[1] < since:
            continue
        parts = j.path.split("/")
        k = (parts[-5], str(int(parts[-2][:-1])))  # stock, offset (e.g. "+03C" -> "3")
        tot[k] = tot.get(k, 0) + 1
        done[k] = done.get(k, 0) + j.done()
    out = {}
    for (s, off) in sorted(tot, key=lambda k: (k[0], int(k[1]))):
        out.setdefault(s, {})[off] = round(done[(s, off)] / tot[(s, off)], 3)
    return out


if __name__ == "__main__":
    args, since = sys.argv[1:], None
    if "--since" in args:
        i = args.index("--since")
        since, args = lf.dt.date.fromisoformat(args[i + 1]), args[:i] + args[i + 2:]
    raw = args[0]
    fn = args[1] if len(args) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                            "STOCK_OPTIONS_COVERAGE.json")
    cov = coverage(lf.jobs_for("stkopt", raw), since)
    json.dump(cov, open(fn, "w"), indent=0)
    full = [s for s, v in cov.items() if min(v.values()) >= 0.9]
    print(f"{len(cov)} stocks; >=90% at every offset: {len(full)}; ATM mean {sum(v['0'] for v in cov.values()) / len(cov):.2f}")
