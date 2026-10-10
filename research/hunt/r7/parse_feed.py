"""R7: decode Dhan v2 FULL packets (code 8) from feed_*.bin into a tick table (parquet).
Each frame may hold several packets; header = code u8, len i16, seg u8, sid i32 (little-endian)."""
import sys, struct, json
from pathlib import Path
import pandas as pd
S = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/r7")
FULL = struct.Struct("<fhifiiiiiiiffff")   # ltp ltq ltt atp vol tsq tbq oi oihi oilo open close high low  (bytes 8..62)
LVL = struct.Struct("<iihhff")
def frames(path):
    b = path.read_bytes(); i = 0
    while i + 12 <= len(b):
        ts, n = struct.unpack_from("<dI", b, i); i += 12
        yield ts, b[i:i + n]; i += n
rows, codes = [], {}
for f in sorted(S.glob("feed_*.bin")):
    for ts, m in frames(f):
        j = 0
        while j + 8 <= len(m):
            code, ln, seg, sid = struct.unpack_from("<BhBi", m, j)
            codes[code] = codes.get(code, 0) + 1
            if ln <= 0: break
            if code == 8 and ln >= 162:
                v = FULL.unpack_from(m, j + 8)
                r = dict(ts=ts, sid=str(sid), ltp=v[0], ltq=v[1], ltt=v[2], atp=v[3], vol=v[4], tsq=v[5], tbq=v[6], oi=v[7])
                for k in range(5):
                    bq, aq, bo, ao, bp, ap = LVL.unpack_from(m, j + 62 + 20 * k)
                    r.update({f"bq{k}": bq, f"aq{k}": aq, f"bo{k}": bo, f"ao{k}": ao, f"bp{k}": bp, f"ap{k}": ap})
                rows.append(r)
            j += ln
df = pd.DataFrame(rows)
meta = json.load(open(S / "instruments.json"))
df["sym"] = df.sid.map(lambda s: meta.get(s, {}).get("sym")); df["kind"] = df.sid.map(lambda s: meta.get(s, {}).get("kind"))
df["strike"] = df.sid.map(lambda s: meta.get(s, {}).get("strike"))
df.to_parquet(S / "ticks.parquet")
print("packet codes", codes); print(len(df), "full packets"); print(df.groupby(["sym", "kind", "strike"], dropna=False).size())
print(df[df.kind == "FUT"].groupby("sym")[["ltp", "bp0", "ap0", "bq0", "aq0", "tbq", "tsq", "vol", "oi"]].last())
