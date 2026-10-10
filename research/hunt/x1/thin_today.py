"""X1: would the ThinOption gate (>= 4 of the last 6 minutes traded, >= 25 lots in all) have let today's automatic buys
through? Uses the fetched 8 Oct minutes (fetch.py). The forming minute counts with the volume seen by then (unknown):
both 'forming minute empty' and 'forming minute full' are shown.
    python3 -I research/hunt/x1/thin_today.py"""
import json, datetime as dt
RAW = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/x1/raw"
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))
ENTRIES = [("Liquidity", "BANKNIFTY_54900PE", "09:22", 30), ("Liquidity", "MIDCPNIFTY_13675PE", "09:22", 120),
           ("Pine", "FINNIFTY_24800PE", "09:30", 60), ("Liquidity", "BANKNIFTY_54800PE", "11:31", 30),
           ("Liquidity", "FINNIFTY_24750PE", "11:31", 60), ("Liquidity", "BANKNIFTY_54800PE", "12:38", 30),
           ("Liquidity", "BANKNIFTY_54700PE", "13:20", 30), ("Pine", "FINNIFTY_24650PE", "13:31", 60),
           ("Pine", "FINNIFTY_24600PE", "14:02", 60), ("ORB", "BANKNIFTY_54900PE", "11:31", 30)]


def bars(name):
    d = json.load(open(f"{RAW}/{name}.json"))
    out = {}
    for t, v in zip(d["timestamp"], d["volume"]):
        x = dt.datetime.fromtimestamp(t, IST)
        if x.date() == dt.date(2026, 10, 8):
            out[x.strftime("%H:%M")] = v
    return out


def main():
    for who, name, hm, lot in ENTRIES:
        b = bars(name)
        h, m = map(int, hm.split(":"))
        mins = [f"{(h*60+m-k)//60:02d}:{(h*60+m-k)%60:02d}" for k in range(5, -1, -1)]
        prev = [b.get(x, 0) for x in mins[:-1]]; cur = b.get(mins[-1], 0)
        for lab, cv in (("forming empty", 0), ("forming full", cur)):
            vs = prev + [cv]
            traded = sum(1 for v in vs if v > 0); lots = sum(vs) / lot
            ok = traded >= 4 and lots >= 25
            print(f"{who:9s} {name:20s} {hm} {lab:13s} traded {traded}/6 lots {lots:6.1f} -> {'BUY' if ok else 'SKIP'}")


if __name__ == "__main__":
    main()
