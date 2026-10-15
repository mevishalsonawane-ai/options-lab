"""Boss's follow-up idea (13 Oct, after R19): once the open profit is >= Rs 1,000, a stop Rs 500 under the best
profit, rising with it. EXPLORATORY: design sessions only, no pre-registration, no holdout (none is left unread in
the release). Not evidence; logged as research/experiments/entries/r19-rupee-trail.yaml.

    python3 -I research/hunt/r19/rupee_trail.py <release dir> <out dir>
"""
import sys; sys.path.insert(0,'/home/user/options-lab/research/hunt/r19')
import run as R, sim as S, numpy as np
from dataclasses import dataclass
@dataclass(frozen=True)
class RupeeTrail(S.Lock):
    arm_rs: float = 1000.0
    gap_rs: float = 500.0
    qty: int = 30
    def level(self, e, be, peak, progress):
        if (peak - e) * self.qty < self.arm_rs - 1e-9: return None
        lv = max(be, peak - self.gap_rs / self.qty)
        return lv if lv < peak - 1e-9 else None
rel, out = sys.argv[1], sys.argv[2]
days = R.load_days(rel, out); sigs = R.all_signals(days, out)
byd = {d['day']: d for d in days}
tr, _, _ = R.build_trades(days, sigs, lambda d: not R.in_hold(d))
for arm, gap in [(1000, 500)]:
    for book in ('15m', '5m'):
        tt = [t for t in tr if t['book'] == book]
        dl, locks, cut, cutrs, helped, helprs, n_arm = 0, 0, 0, 0, 0, 0, 0
        for t in tt:
            d = byd[t['day']]; s = t['sig']; opt = d['opts'][(s['strike'], 'C' if s['side'] > 0 else 'P')]
            v = RupeeTrail('rt', 'custom', arm_rs=arm, gap_rs=gap, qty=t['qty'])
            r = S.run_trade(opt, d['ix'], s, t['c0'], t['e'], t['qty'], t['qty'], v)
            x = r['net'] - t['d']['net']; dl += x
            locks += r['why'] == 'lock'
            if t['mfe'] * t['qty'] >= arm: n_arm += 1
            if t['d']['net'] > 0 and x < -1e-6: cut += 1; cutrs += x
            if x > 1e-6: helped += 1; helprs += x
        base = sum(t['d']['net'] for t in tt)
        print(f"{book}: n={len(tt)} plain={base:+,.0f} with-trail={base+dl:+,.0f} delta={dl:+,.0f} reached+1000={n_arm} trail-exits={locks} winners-cut={cut} ({cutrs:+,.0f}) helped={helped} ({helprs:+,.0f})")
