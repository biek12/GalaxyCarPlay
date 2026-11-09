#!/usr/bin/env python3
"""按已核对素材清单恢复车辆图标；摘要不符即停止，不自动换成其他图标。"""
from pathlib import Path
import hashlib
import json
import ssl
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / 'galaxy/record/src/main'
TLS = ssl.create_default_context(cafile='/etc/ssl/cert.pem')
MAPPING = dict(hold='auto-brake', cruise='speedo-set', steering='steering-wheel',
               fog='foglight-rear', parking='parking-brake', low_beam='low-beam',
               high_beam='high-beam', position='lights')


def fetch(item):
    url = item.get('asset', item['source'])
    raw = urllib.request.urlopen(url, context=TLS, timeout=30).read()
    expected = item.get('sha256', item.get('sourceSha256'))
    assert hashlib.sha256(raw).hexdigest() == expected, 'Source changed: ' + url
    return raw


def main():
    items = json.loads((ROOT / 'assets/licenses/vehicle-assets.json').read_text())['assets']
    for item in items:
        if item['name'] in ('brake', 'accelerator'):
            raw = fetch(item)
            (ROOT / 'res/drawable-nodpi' / ('record_pedal_' + item['name'] + '.png')).write_bytes(raw)
    for key, title in MAPPING.items():
        item = next(x for x in items if x.get('title', x['name']) == title)
        svg = ET.fromstring(fetch(item))
        symbol = next(x for x in svg.iter() if x.attrib.get('id') == 'icon')
        paths = []
        for element in symbol:
            assert element.tag.split('}')[-1] == 'path' and 'transform' not in element.attrib
            paths.append('    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="' + element.attrib['d'] + '" />')
        output = '<!-- Volvo Cars shared icons v5 / ' + title + '; original geometry. -->\n'
        output += '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'
        output += '\n'.join(paths) + '\n</vector>\n'
        (ROOT / 'res/drawable' / ('record_icon_' + key + '.xml')).write_text(output)
    print('Restored 2 unchanged PNGs and 8 original vector geometries')


if __name__ == '__main__':
    main()
