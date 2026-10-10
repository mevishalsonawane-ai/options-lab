"""h42: tables -> answers -> answers.csv -> PREREG (pre-holdout only). Run under the obuy flock."""
import os
import subprocess
import sys

H = os.path.dirname(os.path.abspath(__file__))
for s in ("tables.py", "answer.py", "report.py", "make_prereg.py"):
    print("==>", s, flush=True)
    subprocess.run([sys.executable, "-I", "-W", "ignore", os.path.join(H, s)], check=True)
