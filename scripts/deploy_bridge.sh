#!/usr/bin/env bash
# 用户自有服务器；新增独立服务及/galaxy/反代，不替换原星枢认证/服务。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
TARGET=${GALAXY_SERVER_SSH:?Set GALAXY_SERVER_SSH from private configuration}
KEY=${GALAXY_SERVER_SSH_KEY:-/Users/anjon/.ssh/id_ed25520_1panel}
SSH=(ssh -o BatchMode=yes -o ConnectTimeout=8 -i "$KEY")
ARCH=$("${SSH[@]}" "$TARGET" uname -m)
case "$ARCH" in x86_64) GO_ARCH=amd64;; aarch64) GO_ARCH=arm64;; *) exit 2;; esac
cd "$ROOT/server/galaxyd"
go test -race ./...
CGO_ENABLED=0 GOOS=linux GOARCH="$GO_ARCH" go build -o "$ROOT/.work/galaxyd" .
"${SSH[@]}" "$TARGET" 'set -eu
id galaxybridge >/dev/null 2>&1 || useradd --system --no-create-home --shell /sbin/nologin galaxybridge
mkdir -p /opt/galaxyplay-bridge /var/lib/galaxyplay-bridge
if [ ! -s /var/lib/galaxyplay-bridge/enrollment ]; then umask 077; openssl rand -hex 32 > /var/lib/galaxyplay-bridge/enrollment; fi
chown -R galaxybridge:galaxybridge /var/lib/galaxyplay-bridge
chmod 700 /var/lib/galaxyplay-bridge'
scp -O -q -o BatchMode=yes -i "$KEY" "$ROOT/.work/galaxyd" "$TARGET:/opt/galaxyplay-bridge/galaxyd.next"
scp -O -q -o BatchMode=yes -i "$KEY" galaxyd.service "$TARGET:/opt/galaxyplay-bridge/galaxyd.service"
"${SSH[@]}" "$TARGET" 'set -eu
# Preserve existing deployment-only environment before replacing the public unit.
install -d -m 700 /etc/galaxyplay-bridge
if [ ! -f /etc/galaxyplay-bridge/server.env ]; then
    umask 077
    sed -n -e "s/^Environment=GALAXY_WEB_ORIGIN=/GALAXY_WEB_ORIGIN=/p" -e "s/^Environment=GALAXY_TURN_URLS=/GALAXY_TURN_URLS=/p" /etc/systemd/system/galaxyd.service > /etc/galaxyplay-bridge/server.env
    test -s /etc/galaxyplay-bridge/server.env
fi
if [ -f /opt/galaxyplay-bridge/galaxyd ]; then cp -p /opt/galaxyplay-bridge/galaxyd /opt/galaxyplay-bridge/galaxyd.previous; fi
mv /opt/galaxyplay-bridge/galaxyd.next /opt/galaxyplay-bridge/galaxyd
chmod 755 /opt/galaxyplay-bridge/galaxyd
cp /opt/galaxyplay-bridge/galaxyd.service /etc/systemd/system/galaxyd.service
systemctl daemon-reload
systemctl enable galaxyd.service
systemctl restart galaxyd.service
systemctl is-active --quiet galaxyd.service'
"${SSH[@]}" "$TARGET" python3 - "${GALAXY_PROXY_CONFIG:?Set remote proxy config path}" <<'PY'
from pathlib import Path
from datetime import datetime,timezone
import shutil,subprocess,sys
p=Path(sys.argv[1])
text=p.read_text()
backup=None
if 'location ^~ /galaxy/' not in text:
    backup=p.with_suffix('.conf.galaxy-backup-'+datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S'))
    shutil.copy2(p,backup)
    block='    location ^~ /galaxy/ {\n        client_max_body_size 128m;\n        proxy_pass http://127.0.0.1:8781/;\n    }\n'
    assert '    location / {' in text
    p.write_text(text.replace('    location / {',block+'    location / {',1))
names=subprocess.check_output(['docker','ps','--format','{{.Names}}'],universal_newlines=True).splitlines()
proxies=[x for x in names if 'openresty' in x.lower()]
assert len(proxies)==1
result=subprocess.run(['docker','exec',proxies[0],'nginx','-t'])
if result.returncode:
    if backup:shutil.copy2(backup,p)
    raise SystemExit(result.returncode)
subprocess.run(['docker','exec',proxies[0],'nginx','-s','reload'],check=True)
PY
curl --retry 4 --retry-all-errors --retry-delay 1 --fail --silent --show-error "${GALAXY_ORIGIN:?Set GALAXY_ORIGIN}/bridge/health"
echo
mkdir -p "$ROOT/.local"
(umask 077; "${SSH[@]}" "$TARGET" cat /var/lib/galaxyplay-bridge/enrollment > "$ROOT/.local/remote-enrollment")
