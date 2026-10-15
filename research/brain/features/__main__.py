"""python -m research.brain.features {build,catalog,list} -- see research/brain/README.md."""
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
    ap = argparse.ArgumentParser(prog="python -m research.brain.features")
    ap.add_argument("cmd", choices=["build", "catalog", "list"])
    ap.add_argument("--from", dest="frm", default="2000-01-01")
    ap.add_argument("--to", default="2099-12-31")
    ap.add_argument("--und", default="BANKNIFTY")
    ap.add_argument("--cache", default=None)
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--check", action="store_true", help="catalog: fail if FEATURES.md is out of date")
    a = ap.parse_args(argv)
    load_all()
    if a.cmd == "build":
        from .store import build
        build(config.paths(a.cache).root, a.und, date.fromisoformat(a.frm), date.fromisoformat(a.to), a.force)
    elif a.cmd == "catalog":
        md = catalogue_md() + "\n"
        if a.check:
            cur = FEATURES_MD.read_text() if FEATURES_MD.exists() else ""
            if cur != md:
                print("FEATURES.md is out of date: run `python -m research.brain.features catalog`")
                return 1
            print("FEATURES.md up to date")
            return 0
        FEATURES_MD.write_text(md)
        print(f"wrote {FEATURES_MD} ({len(REGISTRY)} features)")
    elif a.cmd == "list":
        for f in sorted(REGISTRY.values(), key=lambda f: (f.family, f.name)):
            print(f"{f.family:10s} {f.name:28s} v{f.version} {f.lookback:>5s}  {f.description}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
