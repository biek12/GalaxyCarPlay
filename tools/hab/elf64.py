# SPDX-License-Identifier: GPL-3.0-only
# 本仓库自有静态ELF解析器：只读已指定文件，不加载或执行输入。
#!/usr/bin/env python3
"""Bounded static ELF64/LE parser; never loads or executes input code.
Reconstructs dynamic symbols from program headers and GNU/SysV hash, not sections.
Optional annotated ELF copy adds PT_LOAD-backed sections for static objdump only.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

MAX_FILE = 64 * 1024 * 1024
MAX_ENTRIES = 200000
DT_NAMES = {1:'NEEDED',2:'PLTRELSZ',3:'PLTGOT',4:'HASH',5:'STRTAB',6:'SYMTAB',7:'RELA',8:'RELASZ',9:'RELAENT',10:'STRSZ',11:'SYMENT',12:'INIT',13:'FINI',14:'SONAME',17:'REL',18:'RELSZ',19:'RELENT',20:'PLTREL',23:'JMPREL',25:'INIT_ARRAY',26:'FINI_ARRAY',27:'INIT_ARRAYSZ',28:'FINI_ARRAYSZ',0x6ffffef5:'GNU_HASH'}

class ELF:
    def __init__(self, path):
        self.path = Path(path)
        if self.path.stat().st_size > MAX_FILE: raise ValueError('input exceeds 64 MiB')
        self.b = self.path.read_bytes()
        if self.b[:6] != b'\x7fELF\x02\x01': raise ValueError('requires ELF64 little endian')
        self.h = self.unpack('<HHIQQQIHHHHHH',16)
        self.phoff,self.phent,self.phnum = self.h[4],self.h[8],self.h[9]
        if self.phnum > 1024 or self.phent != 56: raise ValueError('invalid PHDR bounds')
        self.ph = []
        for i in range(self.phnum):
            t,f,o,v,p,fs,ms,a=self.unpack('<IIQQQQQQ',self.phoff+i*self.phent)
            self.take(o,fs)
            self.ph.append(dict(type=t,flags=f,offset=o,vaddr=v,paddr=p,filesz=fs,memsz=ms,align=a))
        self.dt = {}
        self.dynamic=[]
        for ph in self.ph:
            if ph['type'] != 2: continue
            for off in range(ph['offset'],ph['offset']+ph['filesz'],16):
                tag,val=self.unpack('<qQ',off)
                if tag==0: break
                self.dynamic.append(dict(tag=tag,name=DT_NAMES.get(tag,hex(tag)),value=val))
                self.dt.setdefault(tag,[]).append(val)
        self.strtab=self.va(self.one(5),self.one(10))
        self.syms=self.symbols()
        self.relocs=self.relocations()
    def take(self,o,n):
        if o<0 or n<0 or o+n>len(self.b): raise ValueError('out of file bounds')
        return self.b[o:o+n]
    def unpack(self,fmt,o): return struct.unpack(fmt,self.take(o,struct.calcsize(fmt)))
    def off(self,v,n=1):
        for p in self.ph:
            if p['type']==1 and p['vaddr']<=v and v+n<=p['vaddr']+p['filesz']:
                return p['offset']+v-p['vaddr']
        raise ValueError(f'unmapped file-backed VA {v:#x}+{n:#x}')
    def va(self,v,n): return self.take(self.off(v,n),n)
    def one(self,t,default=None):
        return self.dt[t][0] if t in self.dt else default
    def string(self,i):
        if i>=len(self.strtab): raise ValueError('bad string index')
        end=self.strtab.find(b'\0',i)
        if end<0: raise ValueError('unterminated string')
        return self.strtab[i:end].decode('utf-8','replace')
    def symbol_count(self):
        if 4 in self.dt:
            return self.unpack('<II',self.off(self.one(4),8))[1], 'DT_HASH.nchain'
        if 0x6ffffef5 not in self.dt: raise ValueError('no symbol-count hash')
        base=self.off(self.one(0x6ffffef5),16)
        nb,first,bs,shift=self.unpack('<IIII',base)
        if max(nb,bs,first)>MAX_ENTRIES: raise ValueError('GNU hash count bound')
        buckets=base+16+8*bs
        chains=buckets+4*nb
        highest=first-1
        for k in range(nb):
            s=self.unpack('<I',buckets+4*k)[0]
            if not s: continue
            if s<first: raise ValueError('bad GNU bucket')
            while True:
                if s>MAX_ENTRIES: raise ValueError('GNU chain bound')
                h=self.unpack('<I',chains+4*(s-first))[0]
                highest=max(highest,s)
                if h&1: break
                s+=1
        return max(first,highest+1), 'DT_GNU_HASH buckets/chains'
    def symbols(self):
        count,self.count_source=self.symbol_count()
        if count>MAX_ENTRIES or self.one(11)!=24: raise ValueError('symbol table bound/entry size')
        out=[]
        for i in range(count):
            n,info,other,shndx,value,size=struct.unpack('<IBBHQQ',self.va(self.one(6)+24*i,24))
            out.append(dict(index=i,name=self.string(n),bind=info>>4,type=info&15,visibility=other&3,shndx=shndx,value=value,size=size,defined=shndx!=0))
        return out
    def relocations(self):
        out=[]
        tables=[]
        if 7 in self.dt: tables.append(('RELA',self.one(7),self.one(8),True))
        if 17 in self.dt: tables.append(('REL',self.one(17),self.one(18),False))
        if 23 in self.dt: tables.append(('JMPREL',self.one(23),self.one(2),self.one(20)==7))
        for name,base,size,rela in tables:
            ent=24 if rela else 16
            if size%ent or size//ent>MAX_ENTRIES: raise ValueError('relocation bounds')
            for i in range(size//ent):
                vals=struct.unpack('<QQq' if rela else '<QQ',self.va(base+i*ent,ent))
                offset,info=vals[:2]
                si=info>>32
                out.append(dict(table=name,offset=offset,type=info&0xffffffff,symbol_index=si,symbol=self.syms[si]['name'] if si<len(self.syms) else '<bad-index>',addend=vals[2] if rela else None))
        return out
    def report(self):
        return dict(path=str(self.path.resolve()),sha256=hashlib.sha256(self.b).hexdigest(),file_size=len(self.b),machine=self.h[1],entry=self.h[3],program_headers=self.ph,dynamic=self.dynamic,needed=[self.string(v) for v in self.dt.get(1,[])],symbol_count_source=self.count_source,symbols=self.syms,relocations=self.relocs)
    def annotated(self,path):
        # Separate artifact only. Original program headers and code bytes stay intact.
        names=bytearray(b'\0.shstrtab\0')
        sections=[(0,)*10]
        for p in self.ph:
            if p['type']!=1 or not p['filesz']: continue
            no=len(names); names.extend(f".load_{p['vaddr']:x}".encode()+b'\0')
            flags=2 | (4 if p['flags']&1 else 0) | (1 if p['flags']&2 else 0)
            sections.append((no,1,flags,p['vaddr'],p['offset'],p['filesz'],0,0,4,0))
        out=bytearray(self.b)
        noff=len(out); out.extend(names)
        sections.append((1,3,0,0,noff,len(names),0,0,1,0))
        while len(out)%8: out.append(0)
        shoff=len(out)
        for s in sections: out.extend(struct.pack('<IIQQQQIIQQ',*s))
        struct.pack_into('<Q',out,40,shoff)
        struct.pack_into('<HHH',out,58,64,len(sections),len(sections)-1)
        Path(path).write_bytes(out)

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('input'); ap.add_argument('--json',required=True); ap.add_argument('--annotated'); a=ap.parse_args()
    e=ELF(a.input)
    Path(a.json).write_text(json.dumps(e.report(),indent=2,ensure_ascii=False)+'\n')
    if a.annotated: e.annotated(a.annotated)
    print(f"sha256={e.report()['sha256']} symbols={len(e.syms)} count={e.count_source}")
    for s in e.syms:
        print(f"{s['index']:3} {'DEF' if s['defined'] else 'UND'} bind={s['bind']} type={s['type']} VA={s['value']:#x} size={s['size']:#x} {s['name']}")
    print('NEEDED:', ', '.join(e.report()['needed']))

if __name__=='__main__': main()
