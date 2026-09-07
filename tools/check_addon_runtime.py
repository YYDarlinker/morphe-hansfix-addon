"""Inspect only the addon runtime DEX embedded in a locally built .mpp."""
from pathlib import Path
import argparse, struct, zipfile
PREFIX='Lio/github/yydarlinker/hansfix/'
def dex_classes(data):
    if len(data)<112 or data[:4]!=b'dex\n':raise ValueError('Invalid DEX header')
    ns,so=struct.unpack_from('<II',data,56);nt,to=struct.unpack_from('<II',data,64);nc,co=struct.unpack_from('<II',data,96)
    for count,off,width in [(ns,so,4),(nt,to,4),(nc,co,32)]:
        if off+count*width>len(data):raise ValueError('DEX table exceeds input')
    strings=[]
    for i in range(ns):
        p=struct.unpack_from('<I',data,so+4*i)[0]
        for _ in range(5):
            value=data[p];p+=1
            if not value&128:break
        else:raise ValueError('Invalid DEX string length')
        end=data.index(0,p);strings.append(data[p:end].decode('utf8','replace'))
    types=[strings[struct.unpack_from('<I',data,to+4*i)[0]] for i in range(nt)]
    return {types[struct.unpack_from('<I',data,co+32*i)[0]] for i in range(nc)}
def validate_namespace(names):
    if not names:raise ValueError('Empty runtime is not a namespace proof')
    bad=sorted(n for n in names if not n.startswith(PREFIX))
    if bad:raise ValueError('Foreign runtime class descriptors: '+', '.join(bad[:8]))
def inspect(bundle):
    with zipfile.ZipFile(bundle) as z:
        if z.testzip() is not None:raise ValueError('Bundle ZIP CRC failed')
        names=dex_classes(z.read('extensions/hansfix-addon.mpe'))
    validate_namespace(names)
    print(f'PASS: {len(names)} runtime classes, all in {PREFIX}; no official or shared-library classes injected.')
    return names
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('bundle',type=Path);args=p.parse_args()
    inspect(args.bundle)
