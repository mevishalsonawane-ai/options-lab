"""Load the day's 1-minute bars fetched by fetch_today.py: name -> list of (HH:MM, o, h, l, c, vol)."""
import json, datetime as dt
from pathlib import Path
D = Path(__file__).resolve().parent / "data"
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))
def load(name):
    d = json.load(open(D / f"{name}.json"))
    return [(dt.datetime.fromtimestamp(t, IST).strftime("%H:%M"), o, h, l, c, v)
            for t, o, h, l, c, v in zip(d["timestamp"], d["open"], d["high"], d["low"], d["close"], d["volume"])]
def show(name, a, b):
    for r in load(name):
        if a <= r[0] <= b: print(name, *r)
if __name__ == "__main__":
    import sys; show(sys.argv[1], sys.argv[2], sys.argv[3])
