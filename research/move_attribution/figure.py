"""Plot the first eligible rising and falling replication examples."""
from pathlib import Path
import argparse
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle
import numpy as np
import pandas as pd


def plot(spot, results):
    d=pd.read_parquet(spot).set_index('ts').sort_index()
    fig, axes=plt.subplots(2,1,figsize=(8.5,6.3),layout='constrained')
    for ax,r in zip(axes,pd.read_csv(results/'examples.csv').itertuples()):
        t=pd.Timestamp(r.day,tz='Asia/Kolkata')+pd.Timedelta(minutes=555+r.clock)
        q=d.loc[t-pd.Timedelta(minutes=5):t+pd.Timedelta(minutes=r.delay)].copy()
        q=q[['open','high','low','close']]-d.at[t,'close']
        minutes=((q.index-t).total_seconds()/60).astype(int)
        for i,b in zip(minutes,q.itertuples()):
            color='#007564' if b.close>=b.open else '#b14435'
            ax.vlines(i,b.low,b.high,colors=color,linewidth=1.3)
            ax.add_patch(Rectangle((i-.28,min(b.open,b.close)),.56,max(.22,abs(b.close-b.open)),facecolor=color))
        ax.axhline(0,color='#40535e',lw=.7)
        ax.axhline(r.label*30,color='#4d5c82',ls='--',lw=1)
        ax.axvspan(-5.7,.45,color='#718297',alpha=.10)
        ax.axvline(.45,color='#718297',lw=.7)
        ax.set_xlim(-5.7,max(minutes)+.7);ax.autoscale_view(scalex=False)
        ax.set_xticks([-5,0,*range(2,r.delay+1,2)])
        ax.set_ylabel('NIFTY points')
        ax.set_title(f"{t:%d %b %Y, %H:%M} IST    {r.end_move:+.2f} points across {r.delay} candles",loc='left',fontsize=12)
        for s in ['top','right']:ax.spines[s].set_visible(False)
        ax.grid(axis='y',alpha=.16)
    axes[1].set_xlabel('Minutes from anchor close')
    fig.suptitle('Two paths to a 30-point NIFTY move',x=.08,ha='left',fontsize=17)
    fig.savefig(results/'candle_paths.png',dpi=155)


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('spot',type=Path);p.add_argument('results',type=Path)
    plot(**vars(p.parse_args()))
