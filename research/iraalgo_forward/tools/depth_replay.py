"""One-session execution diagnostic on Dhan chain snapshots; no selection or tuning."""
import argparse
import json
from pathlib import Path
import numpy as np
import pandas as pd
from research.obuy.costs import Costs

LOTS={'NIFTY':65,'BANKNIFTY':30,'FINNIFTY':60,'MIDCPNIFTY':120,'SENSEX':20}


def simulate(path):
    data=pd.read_parquet(path)
    fee=Costs('dated')
    output={}
    for symbol,frame in data.groupby('underlying'):
        qty=LOTS[symbol]
        books=[g for _,g in frame.groupby('answered',sort=True)]
        outcomes=[]
        missed=0
        for i in range(5,len(books)-11,12):
            current=books[i]
            spot=current.spot.iloc[0]
            side='CE' if spot>books[i-5].spot.iloc[0] else 'PE'
            contracts=current[current.side==side]
            contract=contracts.loc[(contracts.strike-spot).abs().idxmin()]
            key=contract.security_id
            enter=books[i+1].query('security_id == @key')
            leave=books[i+11].query('security_id == @key')
            if len(enter)!=1 or enter.iloc[0].ask<=0 or enter.iloc[0].ask_qty<qty:
                missed+=1
                continue
            a=enter.iloc[0]
            assert a.expiry==contract.expiry and a.strike==contract.strike
            buy=a.ask
            d=(a.answered.date()-pd.Timestamp('1970-01-01').date()).days
            sell=0
            if len(leave)==1 and leave.iloc[0].bid_qty>=qty:
                b=leave.iloc[0]
                assert b.expiry==a.expiry and b.strike==a.strike
                sell=max(0,b.bid)
            cost=fee.charge(True,buy,qty,d,bse=symbol=='SENSEX')+fee.charge(False,sell,qty,d,bse=symbol=='SENSEX')
            outcomes.append(float((sell-buy)*qty-cost))
        output[symbol]=dict(trades=len(outcomes),wins=int(sum(v>0 for v in outcomes)),net=round(sum(outcomes),2),
                            unfilled=missed,lot=qty,snapshots=len(books))
    return output


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('snapshot',type=Path)
    parser.add_argument('out',type=Path)
    args=parser.parse_args()
    result=simulate(args.snapshot)
    args.out.write_text(json.dumps(result,indent=2))
    print(result)
