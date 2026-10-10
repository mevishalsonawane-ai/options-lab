"""x3 task 5: Boss's 20 paper fills on 8 Oct 2026 vs the research cost/fill model AT THE SAME MINUTES.
For each trade: the Dhan minute bar containing the app's fill time; the research model's buy = open x 1.0005 + 1 tick
then + the h24 real half-spread (BN 0.16%, FIN 0.42%, MIDCP 0.21%, NIFTY 0.16%) [+ impact k .02 at 1 lot];
sell = open x 0.9995 - 1 tick - half-spread. Charges = SandboxCosts (identical in app and research).
    python3 -I research/hunt/x3/p1_paper8oct.py
"""
import sys, math
sys.path.insert(0, "/home/user/options-lab/research/hunt/lock8")
sys.path.append("/root/.local/lib/python3.11/site-packages")
import pandas as pd
import day as DY, bars as B
HS = {"BANKNIFTY": 0.0016, "FINNIFTY": 0.0042, "MIDCPNIFTY": 0.0021, "NIFTY": 0.0016}
rows = []
for t in DY.TRADES:
    b = {r[0]: r for r in B.load(t.feed)}
    def bar(ts):
        mm = ts[:5]
        return b.get(mm)
    bi, bo = bar(t.t_in), bar(t.t_out)
    prev_in = b.get(f"{int(t.t_in[:2]):02d}:{int(t.t_in[3:5]) - 1:02d}") if t.t_in[3:5] != "00" else None
    q, hs = t.qty, HS[t.und]
    oi, oo = bi[1], bo[1]
    vin = bi[5] / q
    imp_in = 0.02 * math.sqrt(1 / max(vin, 1))
    r_buy = (oi * 1.0005 + 0.05) * (1 + 0) + max(0, hs * oi - (oi * 0.0005 + 0.05))      # max(liq, hs) as h23/h24
    r_sell = oo * 0.9995 - 0.05 - max(0, hs * oo - (oo * 0.0005 + 0.05))
    chg_r = DY.charge("BUY", r_buy, q) + DY.charge("SELL", r_sell, q)
    r_net = (r_sell - r_buy) * q - chg_r
    rows.append(dict(n=t.n, arm=t.arm, und=t.und[:5], q=q, t_in=t.t_in, app_in=t.p_in, bar_in=f"{bi[1]:.2f}/{bi[2]:.2f}/{bi[3]:.2f}/{bi[4]:.2f}",
                     vol_in_lots=round(vin, 1), in_vs_open=round(t.p_in - oi, 2), in_in_range=bi[3] - 1e-6 <= t.p_in <= bi[2] + 1e-6,
                     prev_close=prev_in[4] if prev_in else None,
                     t_out=t.t_out, app_out=t.p_out, out_vs_open=round(t.p_out - oo, 2), out_in_range=bo[3] - 1e-6 <= t.p_out <= bo[2] + 1e-6,
                     app_net=round(t.net, 0), app_gross=round(t.gross, 0), mid_gross=round((oo - oi) * q, 0),
                     research_net_same_min=round(r_net, 0), app_minus_research=round(t.net - r_net, 0),
                     entry_edge_rs=round((oi - t.p_in) * q, 0), exit_edge_rs=round((t.p_out - oo) * q, 0),
                     model_cost_rs=round((r_buy - oi + oo - r_sell) * q, 0), impact_rs=round(imp_in * oi * q, 0)))
R = pd.DataFrame(rows)
pd.set_option("display.width", 300); pd.set_option("display.max_columns", 40)
print(R.to_string(index=False))
print("\nTOTALS: app net", R.app_net.sum(), "| research model at the same minutes", R.research_net_same_min.sum(),
      "| app - research", R.app_minus_research.sum(), "| of which entry timing/price vs bar open", R.entry_edge_rs.sum(),
      "exit vs bar open", R.exit_edge_rs.sum(), "model spread+slippage", R.model_cost_rs.sum())
print("per trade: app - research mean", round(R.app_minus_research.mean(), 0), "median", R.app_minus_research.median())
print("app fills outside the minute's range: entries", int((~R.in_in_range).sum()), "exits", int((~R.out_in_range).sum()))
R.to_csv("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/x3/paper8oct.csv", index=False)
