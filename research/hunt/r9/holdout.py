"""R9: open the locked holdout (2025-10-01 .. 2026-10-06) ONCE. Runs every holdout piece and the FUTV information runs
(the futures minutes all lie inside the holdout). Refuses a second run.  python3 -I holdout.py"""
from __future__ import annotations

import datetime
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib9 as R  # noqa: E402

if os.path.exists(R.FLAG):
    sys.exit(f"holdout already opened: {open(R.FLAG).read()}")
for f in ("A_placebo_design.csv", "B_filter_design.csv", "C_filter_design.csv", "D_design.csv"):
    if not os.path.exists(os.path.join(R.OUT, f)):
        sys.exit(f"design result missing: {f}")
with open(R.FLAG, "w") as fh:
    fh.write(datetime.datetime.utcnow().isoformat() + "Z\n")
env = dict(os.environ, R9_HOLDOUT_OK="1")
here = os.path.dirname(os.path.abspath(__file__))
for script, arg in (("placebo_vp.py", "hold"), ("placebo_vp.py", "fut"), ("auction.py", "hold"), ("gamma_test.py", "hold"),
                    ("flowproxy.py", "hold"), ("flowproxy.py", "fut")):
    log = os.path.join(R.OUT, f"{script[:-3]}_{arg}.log")
    with open(log, "w") as fh:
        rc = subprocess.call([sys.executable, "-I", os.path.join(here, script), arg], stdout=fh, stderr=subprocess.STDOUT,
                             env=env, cwd="/tmp")
    print(script, arg, "rc", rc, flush=True)
