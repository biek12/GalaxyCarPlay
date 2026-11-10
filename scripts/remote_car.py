#!/usr/bin/env python3
"""个人车机远程入口：验证码一次配对；会话内Shell/同签名APK更新无需逐次审批。"""
import argparse
import getpass
import hashlib
import json
import os
from pathlib import Path
import secrets
import ssl
import sys
import time
import urllib.parse
import urllib.request
import urllib.error


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request(origin, path, value=None, token=None, method=None, data=None):
    if not origin.startswith('https://'):
        raise ValueError('remote_requires_https')
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if value is not None:
        data = json.dumps(value).encode()
    req = urllib.request.Request(origin + path, data=data, headers=headers, method=method)
    ca = os.environ.get('GALAXY_CA_FILE') or ('/etc/ssl/cert.pem' if sys.platform == 'darwin' else None)
    opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=ca)), NoRedirect())
    with opener.open(req, timeout=90) as response:
        return json.load(response)


class Car:
    def __init__(self, session):
        self.session = session
        self.query = '?device=' + urllib.parse.quote(session['device'])

    def call(self, path, value=None, method=None, data=None):
        return request(self.session['origin'], path, value, self.session['token'], method, data)

    def job(self, value, wait=150):
        value = dict(value, id=secrets.token_hex(16), timeoutMillis=30000)
        result = self.call('/bridge/command' + self.query, value)
        deadline = time.monotonic() + wait
        while time.monotonic() < deadline:
            if 'result' in result:
                return result['result']
            time.sleep(1)
            try:
                result = self.call('/bridge/command' + self.query + '&id=' + value['id'])
            except urllib.error.HTTPError:
                raise
            except (urllib.error.URLError, TimeoutError):
                # 现场TLS偶发EOF只影响取结果；同一ID的GET可重试，POST命令绝不自动重发。
                continue
        raise TimeoutError('remote_result_timeout_no_automatic_reexecution')

    def install(self, apk, package):
        payload = apk.read_bytes()
        digest = hashlib.sha256(payload).hexdigest()
        artifact = digest[:32]  # 同一个包上传重试复用 ID，不累积孤儿产物占满配额。
        self.call('/bridge/artifact' + self.query + '&id=' + artifact + '&sha256=' + digest,
                  method='PUT', data=payload)
        response = self.job(dict(kind='install', artifact=artifact, package=package, sha256=digest))
        # 更新AutoService可能中断自身结果回传；始终以已装包的摘要验证安装结果。
        command = 'p=$(pm path ' + package + ' | sed -n "s/^package://p"); sha256sum "$p"'
        check = {}
        for attempt in range(8):
            check = self.job(dict(kind='shell', transport='ROOT', command=command))
            if not check.get('error') and not check.get('timedOut'):
                break
            time.sleep(3)
        if digest not in check.get('output', '').split():
            raise RuntimeError('installed_digest_mismatch: ' + json.dumps(response, ensure_ascii=False))
        return dict(package=package, installed_sha256=digest, verified=True)



def resolve_session(explicit=None, directory=None):
    """Prefer the latest local pairing for the same device; never rotate server tokens or replay a command."""
    if explicit is not None:
        return Path(explicit)
    configured = os.environ.get('GALAXY_REMOTE_SESSION')
    if configured:
        return Path(configured)
    root = Path(directory) if directory else Path(__file__).resolve().parents[1] / '.local'
    canonical = root / 'remote-session.json'
    candidates = []
    for path in [canonical, *root.glob('remote-session-*.json')]:
        try:
            value = json.loads(path.read_text())
            if value.get('token') and value.get('device') and float(value.get('until', 0)) > time.time():
                candidates.append((path.stat().st_mtime_ns, path, value))
        except (OSError, ValueError, TypeError):
            continue
    identities = {(v.get('origin'), v['device']) for _, _, v in candidates}
    if len(identities) > 1:
        raise ValueError('multiple_remote_devices_specify_session')
    return max(candidates, key=lambda item: item[0])[1] if candidates else canonical


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', type=Path)
    sub = parser.add_subparsers(dest='action', required=True)
    pair = sub.add_parser('pair')
    pair.add_argument('--origin', default=os.environ.get('GALAXY_ORIGIN', 'https://bridge.example.invalid/galaxy'))
    pair.add_argument('--code-file', type=Path)
    shell = sub.add_parser('shell')
    shell.add_argument('command')
    shell.add_argument('--transport', choices=['ROOT', 'ADB'], default='ROOT')
    install = sub.add_parser('install')
    install.add_argument('apk', type=Path)
    install.add_argument('--package', choices=['com.ecarx.autoservice', 'com.ecarx.carplay',
                                              'com.galaxy.autocluster', 'com.ecarx.autoservice.test', 'com.galaxy.autorecord'], required=True)
    args = parser.parse_args()
    args.session = resolve_session(args.session) if args.action != 'pair' else (args.session or Path(os.environ.get('GALAXY_REMOTE_SESSION', '.local/remote-session.json')))
    if args.action == 'pair':
        code = args.code_file.read_text().strip() if args.code_file else getpass.getpass('车机验证码：')
        session = request(args.origin, '/bridge/pair', {'code': code})
        session['origin'] = args.origin
        args.session.parent.mkdir(parents=True, exist_ok=True)
        descriptor = os.open(args.session, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, 'w') as output:
            json.dump(session, output)
        print('配对完成，授权到期时间：', session['until'])
    else:
        car = Car(json.loads(args.session.read_text()))
        result = car.job(dict(kind='shell', command=args.command, transport=args.transport)) if args.action == 'shell' else car.install(args.apk, args.package)
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
