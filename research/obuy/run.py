"""obuy command line.

    python3 -I research/obuy/run.py list
    python3 -I research/obuy/run.py run <strategy> [<strategy> ...] [--name RUN] [--pool K] [--B N] [--pool-all]
    python3 -I research/obuy/run.py validate            # the validation suite -> <cache>/runs/validate + parity checks

Untrusted market data: always run with python -I (this file puts research/ on sys.path itself).
Outputs: <OBUY_CACHE>/runs/<RUN>/{REPORT.md, families.csv, variants.csv, trades.csv.gz}.
"""
from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import obuy  # noqa: E402,F401
from obuy.lab import Lab  # noqa: E402
from obuy.strategies.registry import all_strategies  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["list", "run", "validate"])
    ap.add_argument("names", nargs="*")
    ap.add_argument("--name", default=None)
    ap.add_argument("--pool", type=int, default=10)
    ap.add_argument("--B", type=int, default=2000)
    ap.add_argument("--pool-all", action="store_true")
    a = ap.parse_args()
    S = all_strategies()
    if a.cmd == "list":
        for n, st in S.items():
            print(f"{n:24s} {st.n_variants():5d} variants  {st.family:16s} {st.doc}")
        return
    if a.cmd == "validate":
        from obuy import validate
        validate.main()
        return
    sts = [S[n] for n in a.names]
    lab = Lab(sts, name=a.name or "_".join(a.names)[:60], pool_k=a.pool, B=a.B, pool_all=a.pool_all).run()
    print(lab.report())


if __name__ == "__main__":
    main()
