#!/usr/bin/env python3
"""显式Android root Telnet安装入口；临时HTTP只提供已核验APK，pm -r后回读摘要。"""
import argparse
from datetime import datetime
import hashlib
import http.server
import json
from pathlib import Path
import re
import shlex
import socket
import subprocess
import sys
import threading
from zoneinfo import ZoneInfo
from prepare_autoservice_adb import Channel, RootShell


def install(bundle, host, results, interface='en0'):
    bundle = bundle.resolve()
    if results.resolve().is_relative_to(bundle):
        raise ValueError('results_must_be_outside_bundle')
    subprocess.run([sys.executable, str(bundle / 'verify_bundle.py'), str(bundle)], check=True)
    manifest = json.loads((bundle / 'manifest.json').read_text())
    items = [('AutoService', 'com.ecarx.autoservice', 'app/AutoService-debug.apk'),
             ('CarPlay', 'com.ecarx.carplay', 'app/CarPlay-debug.apk'),
             ('AutoCluster', 'com.galaxy.autocluster', 'app/AutoCluster-debug.apk'),
             ('ProjectionSmoke', 'com.ecarx.autoservice.test', 'tests/ProjectionSmoke-test.apk')]
    files = {'/' + relative: bundle / relative for _, _, relative in items}
    for relative, path in files.items():
        if hashlib.sha256(path.read_bytes()).hexdigest() != manifest['files'][relative[1:]]['sha256']:
            raise ValueError('local_digest_failed')

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            path = files.get(self.path)
            if path is None:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header('Content-Length', str(path.stat().st_size))
            self.end_headers()
            with path.open('rb') as stream:
                while data := stream.read(65536):
                    self.wfile.write(data)

        def log_message(self, *args):
            pass

    interface_state = subprocess.check_output(['/sbin/ifconfig', interface], text=True)
    ipv6 = re.findall(r'inet6 (2[0-9a-f:]+)', interface_state)
    ip = ipv6[-1] if ipv6 else re.findall(r'inet ([0-9.]+)', interface_state)[-1]

    class Server(http.server.ThreadingHTTPServer):
        address_family = socket.AF_INET6 if ipv6 else socket.AF_INET

    server = Server(('::' if ipv6 else '0.0.0.0', 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    http_host = '[' + ip + ']' if ipv6 else ip
    results.mkdir(parents=True, exist_ok=True)
    report = dict(recorded_at=datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),
                  preserve_data=True, passed=False, packages={})

    def save():
        (results / 'installed-http.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')

    channel = None
    remote = None
    try:
        channel = Channel(host, 23)
        shell = RootShell(channel)
        if shell.command('uname -s; id -u').splitlines() != ['Linux', '0']:
            raise ValueError('android_identity_failed')
        remote = '/data/local/tmp/galaxy-field-' + datetime.now().strftime('%Y%m%d-%H%M%S')
        shell.command('umask 077; mkdir ' + shlex.quote(remote))
        for label, package, relative in items:
            expected = manifest['files'][relative]['sha256']
            entry = dict(expected_sha256=expected, installed=False)
            report['packages'][label] = entry
            read = 'p=$(pm path ' + package + ' | sed -n "s/^package://p"); if [ -n "$p" ]; then sha256sum "$p" | cut -d" " -f1; fi'
            actual = shell.command(read)
            if actual != expected:
                print(label + ': 传输并同包更新', flush=True)
                destination = remote + '/' + label + '.apk'
                address = f'http://{http_host}:{server.server_port}/{relative}'
                shell.command('curl -g -f -sS --connect-timeout 8 --max-time 120 ' + shlex.quote(address) + ' -o ' + shlex.quote(destination), timeout=130)
                if shell.command('sha256sum ' + shlex.quote(destination) + ' | cut -d" " -f1') != expected:
                    raise ValueError('transfer_digest_failed')
                response = shell.command('pm install -r ' + shlex.quote(destination), timeout=80)
                if 'Success' not in response.splitlines():
                    raise ValueError('package_update_failed')
                entry['installed'] = True
                shell.command('rm -f ' + shlex.quote(destination))
            entry['matches_bundle'] = shell.command(read) == expected
            save()
            if not entry['matches_bundle']:
                raise ValueError('installed_digest_failed')
            print(label + ': 已装摘要一致', flush=True)
        report['passed'] = True
        return report
    except Exception as error:
        report['error_type'] = type(error).__name__
        raise
    finally:
        if channel:
            if remote:
                try:
                    RootShell(channel).command('rm -f ' + shlex.quote(remote) + '/*.apk; rmdir ' + shlex.quote(remote))
                except Exception:
                    pass
            channel.socket.close()
        save()
        server.shutdown()
        server.server_close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--host', required=True)
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--interface', default='en0')
    args = parser.parse_args()
    install(args.bundle, args.host, args.results, args.interface)


if __name__ == '__main__':
    main()
