"""Stage fingerprinted inputs from a mounted lake into private local storage."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile

ROOT=Path(__file__).resolve().parents[1]


def stage(source, dest):
    expected=json.loads((ROOT/'run/audit.json').read_text())['inputs']
    dest.mkdir(parents=True,exist_ok=True)
    for name,entry in expected.items():
        target=dest/name
        digest=entry['sha256']
        if target.exists() and hashlib.file_digest(target.open('rb'),'sha256').hexdigest()==digest:
            continue
        candidates=list(source.rglob(name))
        if len(candidates)!=1:raise ValueError(f'{name}: found {len(candidates)} copies')
        with tempfile.NamedTemporaryFile(dir=dest,delete=False) as tmp:
            temp=Path(tmp.name)
        try:
            shutil.copyfile(candidates[0],temp)
            with temp.open('rb') as fp:
                if hashlib.file_digest(fp,'sha256').hexdigest()!=digest:raise ValueError(f'Checksum mismatch: {name}')
            os.replace(temp,target)
        finally:temp.unlink(missing_ok=True)


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('source',type=Path)
    p.add_argument('destination',type=Path)
    a=p.parse_args()
    stage(a.source,a.destination)
