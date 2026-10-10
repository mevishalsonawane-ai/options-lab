"""R2: open the locked holdout (1 Oct 2025 - 6 Oct 2026) ONCE and run every frozen script in holdout mode."""
import subprocess, sys, datetime as dt
from lib import R2
lock = R2 / "holdout.lock"
if lock.exists():
    sys.exit("holdout already opened at " + lock.read_text())
lock.write_text(dt.datetime.utcnow().isoformat())
here = "/home/user/options-lab/research/hunt/r2/"
for s in ("daily.py", "hourly.py", "events.py", "leadlag.py", "leadlag_trade.py", "cand.py", "fomc.py", "lead_cand.py"):
    print("=====", s, flush=True)
    r = subprocess.run([sys.executable, here + s, "holdout"], cwd=here, capture_output=True, text=True)
    (R2 / "out" / f"holdout_{s[:-3]}.log").write_text(r.stdout + r.stderr)
    print(r.stdout[-6000:], r.stderr[-1500:], flush=True)
