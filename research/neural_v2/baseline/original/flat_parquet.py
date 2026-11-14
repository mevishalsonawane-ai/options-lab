"""Limited, strict reader for flat ZSTD/PLAIN/dictionary Parquet used by this lake.
Prefer pyarrow for ordinary production use. No nested or repeated columns.
"""
import ctypes as ct, ctypes.util, struct
from pathlib import Path
import numpy as np
import pandas as pd
from thrift.protocol.TCompactProtocol import TCompactProtocol
from thrift.transport.TTransport import TMemoryBuffer
from thrift.Thrift import TType as T

def thrift(p,t=T.STRUCT):
    if t==T.STRUCT:
        d={};p.readStructBegin()
        while True:
            _,typ,i=p.readFieldBegin()
            if typ==T.STOP:break
            d[i]=thrift(p,typ);p.readFieldEnd()
        p.readStructEnd();return d
    if t in (T.LIST,T.SET):
        typ,n=p.readListBegin();a=[thrift(p,typ) for _ in range(n)];p.readListEnd();return a
    if t==T.MAP:
        k,v,n=p.readMapBegin();d={thrift(p,k):thrift(p,v) for _ in range(n)};p.readMapEnd();return d
    if t==T.STRING:return p.readBinary()
    return {T.I32:p.readI32,T.I64:p.readI64,T.I16:p.readI16,T.BYTE:p.readByte,T.BOOL:p.readBool,T.DOUBLE:p.readDouble}[t]()

def parse(b):
    f=TMemoryBuffer(b);d=thrift(TCompactProtocol(f));return d,f._buffer.tell()

z=ct.CDLL(ctypes.util.find_library('zstd'))
z.ZSTD_decompress.argtypes=[ct.c_void_p,ct.c_size_t,ct.c_void_p,ct.c_size_t];z.ZSTD_decompress.restype=ct.c_size_t
z.ZSTD_isError.argtypes=[ct.c_size_t];z.ZSTD_isError.restype=ct.c_uint

def decompress(b,n,codec):
    if codec==0:
        assert len(b)==n;return b
    if codec!=6:raise ValueError(f'unsupported codec {codec}')
    dst=ct.create_string_buffer(n);r=z.ZSTD_decompress(dst,n,b,len(b))
    if z.ZSTD_isError(r) or r!=n:raise ValueError(f'bad zstd size {r} vs {n}')
    return dst.raw

def hybrid(b,width,n):
    if width==0:return np.zeros(n,dtype=np.int32)
    out=np.empty(n,dtype=np.int32);i=0;pos=0
    while i<n:
        h=0;shift=0
        while True:
            q=b[pos];pos+=1;h|=(q&127)<<shift;shift+=7
            if q<128:break
        if h==0:raise ValueError('bad zero run')
        if h&1:
            count=(h>>1)*8;size=count*width//8;take=min(count,n-i)
            if width==8:v=np.frombuffer(b,dtype=np.uint8,count=count,offset=pos)[:take]
            elif width==16:v=np.frombuffer(b,dtype='<u2',count=count,offset=pos)[:take]
            else:
                data=np.frombuffer(b,dtype=np.uint8,count=size,offset=pos)
                # Extract little-endian bit-packed integers from 4-byte windows.
                data=np.pad(data,(0,4));idx=np.arange(take,dtype=np.int64)*width;ix=idx//8
                v=(data[ix].astype(np.uint64)|(data[ix+1].astype(np.uint64)<<8)|(data[ix+2].astype(np.uint64)<<16)|(data[ix+3].astype(np.uint64)<<24))>>(idx%8).astype(np.uint64);v&=(1<<width)-1
            out[i:i+take]=v;pos+=size;i+=take
        else:
            count=h>>1;size=(width+7)//8;v=int.from_bytes(b[pos:pos+size],'little');pos+=size;take=min(count,n-i);out[i:i+take]=v;i+=take
    return out

def plain(b,typ,n):
    if typ in (1,2,4,5):return np.frombuffer(b,dtype={1:'<i4',2:'<i8',4:'<f4',5:'<f8'}[typ],count=n)
    if typ==6:
        a=[];pos=0
        for _ in range(n):
            size=struct.unpack_from('<I',b,pos)[0];pos+=4;a.append(b[pos:pos+size].decode('utf-8'));pos+=size
        return np.array(a,dtype=object)
    if typ==0:return np.unpackbits(np.frombuffer(b,dtype=np.uint8),bitorder='little')[:n].astype(bool)
    raise ValueError(f'unsupported physical type {typ}')

def metadata(path):
    with open(path,'rb') as f:
        assert f.read(4)==b'PAR1';f.seek(-8,2);n,magic=struct.unpack('<I4s',f.read(8));assert magic==b'PAR1';f.seek(-8-n,2);return parse(f.read(n))[0]

def read_parquet(path,columns=None):
    m=metadata(path);schema={x[4].decode():x for x in m[2][1:]};parts=[]
    if len(schema)!=len(m[2])-1:raise ValueError('non-flat schema')
    with open(path,'rb') as f:
        for g in m[4]:
            result={}
            for ch in g[1]:
                c=ch[3];name=c[3][0].decode()
                if columns and name not in columns:continue
                s=schema[name];typ=c[1];optional=s.get(3,0)==1
                if s.get(3,0)==2:raise ValueError('repeated column')
                start=c.get(11,c[9]);f.seek(start);buf=f.read(c[7]);pos=0;vals=[];count=0;dictionary=None
                while count<c[5]:
                    h,nhead=parse(buf[pos:pos+256]);pos+=nhead;body=buf[pos:pos+h[3]];pos+=h[3];pt=h[1]
                    if pt==2:
                        dictionary=plain(decompress(body,h[2],c[4]),typ,h[7][1]);continue
                    if pt!=0:raise ValueError(f'unsupported page type {pt}')
                    d=h[5];n=d[1];enc=d[2];raw=decompress(body,h[2],c[4]);count+=n
                    if optional:
                        size=struct.unpack_from('<I',raw)[0];valid=hybrid(raw[4:4+size],1,n).astype(bool);raw=raw[4+size:];nn=int(valid.sum())
                    else:valid=None;nn=n
                    if nn==0:a=np.empty(0,dtype=object if typ==6 else np.float64)
                    elif enc in (2,8):
                        if dictionary is None:raise ValueError('missing dictionary')
                        a=dictionary[hybrid(raw[1:],raw[0],nn)]
                    elif enc==0:a=plain(raw,typ,nn)
                    else:raise ValueError(f'unsupported encoding {enc}')
                    if nn!=n:
                        arr=np.full(n,None if typ==6 else np.nan,dtype=object if typ==6 else np.float64);arr[valid]=a;a=arr
                    vals.append(a)
                a=np.concatenate(vals);assert len(a)==g[3],(name,len(a),g[3]);
                if s.get(6) in (9,10) or 8 in s.get(10,{}):
                    unit='ms' if s.get(6)==9 else 'us' if s.get(6)==10 else {1:'ms',2:'us',3:'ns'}[next(iter(s[10][8][2]))]
                    a=pd.to_datetime(a,unit=unit,utc=True).tz_convert('Asia/Kolkata')
                elif s.get(6)==6:a=pd.to_datetime(a,unit='D')
                result[name]=a
            parts.append(pd.DataFrame(result))
    return pd.concat(parts,ignore_index=True)
