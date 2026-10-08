"""Zerodha MCX round-trip costs per lot (rates from zerodha.com/charges, read 8 Oct 2026):
futures: brokerage min(0.03%, Rs20)/order; CTT 0.01% sell (non-agri); MCX txn 0.0021%; SEBI Rs10/cr; stamp 0.002% buy; GST 18% on brok+txn+SEBI
options: Rs20/order; CTT 0.05% sell premium; MCX txn 0.0418% premium; SEBI Rs10/cr; stamp 0.003% buy premium; GST 18%
Spread: 1 tick paid per round trip (half-spread each side, 1-tick market) unless a measured spread is given."""
def fut_rt(price, mult, tick):
    v = price * mult
    brok = 2 * min(20, 0.0003 * v); txn = 2 * 0.000021 * v; sebi = 2 * 1e-6 * v
    ctt = 0.0001 * v; stamp = 0.00002 * v; gst = 0.18 * (brok + txn + sebi)
    ch = brok + txn + sebi + ctt + stamp + gst
    return ch, tick * mult, v
def opt_rt(prem, mult, tick, spread_pct=None):
    v = prem * mult
    brok = 40; txn = 2 * 0.000418 * v; sebi = 2 * 1e-6 * v; ctt = 0.0005 * v; stamp = 0.00003 * v
    gst = 0.18 * (brok + txn + sebi); ch = brok + txn + sebi + ctt + stamp + gst
    sp = v * spread_pct if spread_pct is not None else tick * mult
    return ch, sp, v
if __name__ == "__main__":
    F = [("CRUDEOIL", 8552, 100, 1), ("CRUDEOILM", 8554, 10, 1), ("NATURALGAS", 309.9, 1250, .1), ("NATGASMINI", 310, 250, .1),
         ("GOLD", 149103, 100, 1), ("GOLDM", 147897, 10, 1), ("GOLDTEN", 148276, 1, 1), ("GOLDPETAL", 14887, 1, 1),
         ("SILVER", 223561, 30, 1), ("SILVERM", 225773, 5, 1), ("SILVERMIC", 225834, 1, 1), ("COPPER", 1419.45, 2500, .05),
         ("ZINC", 419.6, 5000, .05), ("ZINCMINI", 419.6, 1000, .05), ("ALUMINI", 340.55, 1000, .05), ("LEADMINI", 195.1, 1000, .05),
         ("NICKEL", 1543.9, 250, .1)]
    print("FUTURES (1 lot round trip)\nsym | notional | charges | 1-tick spread | total | % of notional")
    for s, p, m, t in F:
        ch, sp, v = fut_rt(p, m, t); print(f"{s} | {v:,.0f} | {ch:,.0f} | {sp:,.0f} | {ch+sp:,.0f} | {100*(ch+sp)/v:.3f}%")
    # ATM premiums from the 8 Oct chain (near expiry) and measured/assumed spreads
    O = [("CRUDEOIL", 242, 100, .1, .0029), ("CRUDEOILM", 244, 10, .05, .003), ("NATURALGAS", 13.9, 1250, .05, None),
         ("NATGASMINI", 14.0, 250, .05, None), ("GOLDM", 2515, 10, .5, None), ("GOLD", 2665, 100, .5, None),
         ("SILVERM", 5400, 5, .5, None), ("SILVER", 5278, 30, .5, None)]
    print("\nOPTIONS (1 lot ATM, buy then sell; premium = 8 Oct ATM)\nsym | premium/lot | charges | spread | total | % of premium")
    for s, pr, m, t, spc in O:
        ch, sp, v = opt_rt(pr, m, t, spc); print(f"{s} | {v:,.0f} | {ch:,.0f} | {sp:,.0f} | {ch+sp:,.0f} | {100*(ch+sp)/v:.2f}%")
