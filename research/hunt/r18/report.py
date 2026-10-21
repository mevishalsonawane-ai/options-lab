"""R18: the markdown tables of HUNT_R18_EXITS.md from results/design_<source>.json and hold_<source>.json.

    python3 research/hunt/r18/report.py     # prints the tables (pasted into the doc; numbers are not edited by hand)
"""
from __future__ import annotations

import json

import arms as A
import config as K
from lib import VARIANTS, VNAME


def rs(x):
    return "-" if x is None else f"{x:+,}" if isinstance(x, int) else f"{x}"


def main():
    d = json.load(open(K.RESULTS / f"design_{K.SOURCE}.json"))
    h = json.load(open(K.RESULTS / f"hold_{K.SOURCE}.json"))
    print("## today's exits (V0)\n")
    print("| arm | design trades / days | design net | MFE kept | worst day | max DD | holdout trades / days | holdout net | MFE kept | worst day | max DD |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for a in A.ARMS:
        x, y = d["arms"][a]["V0"], h["arms"][a]["V0"]
        print(f"| {A.NAMES[a]} | {x['trades']} / {x['days']} | {x['net']:,} | {x['mfe_capture']}% | {x['worst_day']:,} | {x['max_dd']:,} | "
              f"{y['trades']} / {y['days']} | {y['net']:,} | {y['mfe_capture']}% | {y['worst_day']:,} | {y['max_dd']:,} |")
    for v in VARIANTS[1:]:
        print(f"\n## {v} {VNAME[v]}\n")
        print("| arm | design delta | folds | p | BH q | 2x spread | twin p | changed | MFE kept | worst day | max DD | holdout delta (n trades) | holdout p |")
        print("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        for a in A.ARMS:
            x, y = d["arms"][a][v], h["arms"][a][v]
            print(f"| {A.NAMES[a]} | {x['delta']:+,} | {' / '.join(f'{f:+,}' for f in x['folds'])} | {x['p']:.3f} | {x['q']:.2f} | "
                  f"{x['delta_2x_spread']:+,} | {x['twin_p']:.3f} | {x['changed']} of {x['trades']} | {x['mfe_capture']}% | {x['worst_day']:,} | "
                  f"{x['max_dd']:,} | {y['delta']:+,} ({y['trades']}) | {y['p']:.3f} |")


if __name__ == "__main__":
    main()
