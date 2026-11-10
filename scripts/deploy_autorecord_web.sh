#!/usr/bin/env bash
# 只部署自有Go服务与网页，不连接车辆、不自动邀请账号。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
TARGET=${GALAXY_SERVER_SSH:?Set GALAXY_SERVER_SSH from private configuration}
KEY=${GALAXY_SERVER_SSH_KEY:-/Users/anjon/.ssh/id_ed25520_1panel}
(cd "$ROOT/web" && npm ci && npm exec -- vue-tsc --noEmit && npm run build)
bash "$ROOT/scripts/deploy_bridge.sh"
tar -C "$ROOT/web/dist" -czf "$ROOT/.work/autorecord-web-dist.tar.gz" .
scp -O -q -o BatchMode=yes -i "$KEY" "$ROOT/.work/autorecord-web-dist.tar.gz" "$TARGET:/opt/galaxyplay-bridge/web.next.tar.gz"
ssh -o BatchMode=yes -o ConnectTimeout=8 -i "$KEY" "$TARGET" 'set -eu
mkdir -p /opt/galaxyplay-bridge/web.next
tar -xzf /opt/galaxyplay-bridge/web.next.tar.gz -C /opt/galaxyplay-bridge/web.next
if [ -d /opt/galaxyplay-bridge/web.previous ]; then rm -rf /opt/galaxyplay-bridge/web.previous; fi
if [ -d /opt/galaxyplay-bridge/web ]; then mv /opt/galaxyplay-bridge/web /opt/galaxyplay-bridge/web.previous; fi
mv /opt/galaxyplay-bridge/web.next /opt/galaxyplay-bridge/web'
curl --fail --silent --show-error "${GALAXY_ORIGIN:?Set GALAXY_ORIGIN}/app/" > /dev/null
