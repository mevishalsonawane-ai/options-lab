"""NN-CRUDE: build the MCX near-month 1-minute base table from Dhan rolling-option data.
spot = near-month CRUDEOIL futures LTP (checked: equals live contract close to the rupee after each roll).
Also: ATM CE/PE close, IV, option volume and OI (ATM-3..ATM+3 summed), roll-day flag.
Output: data/mcx_min.parquet (one row per minute, IST)."""
import sys
sys.path.insert(0, "/home/user/options-lab/research/hunt/nn_crude")
from common import *

r = roll_parts("CRUDEOIL", 1)
r.loc[r.volume < 0, "volume"] = np.nan          # a few negative volume prints in Dhan data
atm = r[r.off == 0]
ce = atm[atm.type == "C"].set_index("ts")
pe = atm[atm.type == "P"].set_index("ts")
b = pd.DataFrame({"spot": ce.spot}).combine_first(pd.DataFrame({"spot": pe.spot}))
b["atm_strike"] = ce.strike.reindex(b.index).fillna(pe.strike.reindex(b.index))
b["ce"], b["pe"] = ce.close.reindex(b.index), pe.close.reindex(b.index)
b["ce_open"], b["pe_open"] = ce.open.reindex(b.index), pe.open.reindex(b.index)
b["iv"] = pd.concat([ce.iv, pe.iv], axis=1, sort=True).replace(0, np.nan).mean(axis=1).reindex(b.index)
g = r.groupby(["ts", "type"])
b["optvol_c"] = g.volume.sum().xs("C", level="type").reindex(b.index)
b["optvol_p"] = g.volume.sum().xs("P", level="type").reindex(b.index)
b["oi_c"] = g.oi.sum().xs("C", level="type").reindex(b.index)
b["oi_p"] = g.oi.sum().xs("P", level="type").reindex(b.index)
b = b.sort_index()
b = b[b.spot > 0]
b["day"] = b.index.normalize()
# OHLC of the future is not in rolling data; use spot (minute LTP) as close. High/low proxies = rolling max/min of spot.
# roll days: the near future switches after its expiry. MCX crude futures expire 2 business days after the
# monthly option expiry (checked on live 2026 contracts: options 17 Sep -> futures 21 Sep, roll seen 22 Sep;
# master: options 15 Oct/17 Nov/16 Dec -> futures 19 Oct/19 Nov/18 Dec). Option expiries are inferred from the ATM
# straddle (it collapses into expiry and jumps the next session). roll day = 3rd trading day after option expiry.
day = b.groupby("day").agg(first=("spot", "first"), last=("spot", "last"))
strad = ((b.ce + b.pe) / b.spot).groupby(b.day).median()
opt_exp = strad.index[(strad.shift(-1) / strad) > 1.6]
days = list(day.index)
roll = set()
for e in opt_exp:
    i = days.index(e)
    if i + 3 < len(days):
        roll.add(days[i + 3])
day["roll"] = day.index.isin(roll)
day["gap"] = np.log(day["first"] / day["last"].shift())
pd.Series(opt_exp, name="opt_expiry").to_frame().to_parquet(f"{D}/opt_expiries.parquet")
b["roll_day"] = b.day.map(day.roll)
b.to_parquet(f"{D}/mcx_min.parquet", compression="zstd")
print("rows", len(b), "days", b.day.nunique(), b.index.min(), b.index.max())
print("roll days:", list(day.index[day.roll].strftime("%Y-%m-%d")), "gaps:", list((day.gap[day.roll] * 100).round(2)))
