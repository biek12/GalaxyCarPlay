#!/usr/bin/env python3
"""将审定稿采用的 Lucide 原始几何机械转换为 Android vector；不创作图标。"""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import ssl
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BASE = 'https://cdn.jsdelivr.net/npm/lucide-static@0.468.0/'
TLS = ssl.create_default_context(cafile='/etc/ssl/cert.pem')
ICONS = dict(live='video', library='gallery-vertical-end', settings='settings-2',
             back='chevron-left', chevron='chevron-right', expand='chevron-down', collapse='chevron-up',
             storage='hard-drive', encode='film', record='circle-dot', vehicle='car-front',
             playback='panels-top-left', diagnose='activity', about='info', refresh='refresh-cw',
             play='play', pause='pause', previous='skip-back', next='skip-forward',
             filter='filter', sliders='sliders-horizontal', more='ellipsis', lock='lock-keyhole',
             event_previous='chevron-left', event_next='chevron-right')


def path(e):
    a = e.attrib
    tag = e.tag.split('}')[-1]
    if tag == 'path': return a['d']
    if tag == 'line': return f'M{a["x1"]},{a["y1"]} L{a["x2"]},{a["y2"]}'
    if tag in ('polyline', 'polygon'):
        return 'M' + a['points'] + ('Z' if tag == 'polygon' else '')
    if tag == 'circle':
        x,y,r = (float(a[k]) for k in ('cx','cy','r'))
        return f'M{x-r},{y} a{r},{r} 0 1,0 {2*r},0 a{r},{r} 0 1,0 {-2*r},0'
    if tag == 'rect':
        x,y = float(a.get('x',0)),float(a.get('y',0))
        w,h,r = float(a['width']),float(a['height']),float(a.get('rx',0))
        if not r: return f'M{x},{y} h{w} v{h} h{-w}Z'
        return (f'M{x+r},{y} h{w-2*r} a{r},{r} 0 0,1 {r},{r} v{h-2*r}'
                f' a{r},{r} 0 0,1 {-r},{r} h{2*r-w} a{r},{r} 0 0,1 {-r},{-r}'
                f' v{2*r-h} a{r},{r} 0 0,1 {r},{-r}Z')
    raise ValueError('Unsupported source element: '+tag)


def convert(item):
    key,name = item
    url = BASE+'icons/'+name+'.svg'
    raw = urllib.request.urlopen(url,context=TLS,timeout=30).read()
    svg = ET.fromstring(raw)
    assert svg.attrib['viewBox'] == '0 0 24 24'
    paths = []
    for e in svg:
        assert not e.attrib.get('transform'), name
        d = path(e)
        paths.append(f'    <path android:fillColor="#00000000" android:strokeColor="#FFFFFFFF" android:strokeWidth="2" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{d}" />')
    dest = ROOT/'galaxy/record/src/main/res/drawable'/('record_icon_'+key+'.xml')
    dest.write_text('<!-- Lucide 0.468.0 / '+name+'; ISC; generated -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'+'\n'.join(paths)+'\n</vector>\n')
    return dict(name=key,source=url,sourceSha256=hashlib.sha256(raw).hexdigest())


if __name__ == '__main__':
    with ThreadPoolExecutor(max_workers=6) as pool:
        manifest = list(pool.map(convert,ICONS.items()))
    dest = ROOT/'galaxy/record/src/main/assets/licenses'
    dest.joinpath('lucide-LICENSE.txt').write_bytes(urllib.request.urlopen(BASE+'LICENSE',context=TLS,timeout=30).read())
    dest.joinpath('lucide-icons.json').write_text(json.dumps(dict(version='0.468.0',icons=manifest),indent=2)+'\n')
    print('Imported',len(manifest),'original Lucide icons')
