"""python -m research.brain.crypto.features {build,catalog,list} -- see research/brain/crypto/README.md."""
from __future__ import annotations

import argparse
import sys
from datetime import date
from pathlib import Path

from .. import config
from . import load_all
from .registry import REGISTRY, catalogue_md

FEATURES_MD = Path(__file__).resolve().parents[1] / "FEATURES.md"


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.crypto.features")
    ap.add_argument("cmd", choices=["build", "catalog", "list"])
    ap.add_argument("--symbols", nargs="+", default=["BTCUSDT", "ETHUSDT"])
    ap.add_argument("--from", dest="frm", default="2017-01-01")
    ap.add_argument("--to", default="2099-12-31")
    ap.add_argument("--market", default="crypto", choices=["crypto", "crypto-ist"],
                    help="analysis-day boundary: UTC midnight (default) or IST midnight")
    ap.add_argument("--cache", default=None)
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--check", action="store_true", help="catalog: fail if FEATURES.md is out of date")
    a = ap.parse_args(argv)
    load_all()
    if a.cmd == "build":
        from .store import build
        for s in a.symbols:
            build(config.paths(a.cache).root, s, date.fromisoformat(a.frm), date.fromisoformat(a.to), a.force, a.market)
    elif a.cmd == "catalog":
        md = catalogue_md() + "\n"
        if a.check:
            cur = FEATURES_MD.read_text() if FEATURES_MD.exists() else ""
            if cur != md:
                print("crypto FEATURES.md is out of date: run `python -m research.brain.crypto.features catalog`")
                return 1
            print("crypto FEATURES.md up to date")
            return 0
        FEATURES_MD.write_text(md)
        print(f"wrote {FEATURES_MD} ({len(REGISTRY)} features)")
    elif a.cmd == "list":
        for f in sorted(REGISTRY.values(), key=lambda f: (f.family, f.name)):
            print(f"{f.family:9s} {f.name:24s} v{f.version} {f.lookback:>6s}  {f.description}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
