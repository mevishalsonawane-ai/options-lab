"""Join three signed Drive parts without silently resolving conflicting ticks."""
from pathlib import Path
import argparse
import pandas as pd


def assemble(files,dest):
    frames=[]
    for file in files:
        d=pd.read_parquet(file,columns=['symbol','ts','close','volume'],filters=[('ts','>=',pd.Timestamp('2025-11-14',tz='Asia/Kolkata'))])
        frames.append(d)
    full=pd.concat(frames,ignore_index=True).drop_duplicates()
    if full.duplicated(['symbol','ts']).any():
        raise ValueError('Conflicting equity quotes across source parts')
    full.to_parquet(dest,index=False)
    print(len(full), 'equity observations;',full.symbol.nunique(),'symbols')


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('dest',type=Path);p.add_argument('files',type=Path,nargs='+')
    a=p.parse_args();assemble(a.files,a.dest)
