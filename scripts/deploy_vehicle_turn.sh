#!/usr/bin/env bash
# 用户自有服务器的独立TURN单元；只管理此单元及其3478、49160-49200端口。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
TARGET=${GALAXY_SERVER_SSH:?Set GALAXY_SERVER_SSH from private configuration}
KEY=${GALAXY_SERVER_SSH_KEY:-/Users/anjon/.ssh/id_ed25520_1panel}
ssh -o BatchMode=yes -o ConnectTimeout=8 -i "$KEY" "$TARGET" 'bash -s' -- "${GALAXY_TURN_PUBLIC_IP:?}" "${GALAXY_TURN_RELAY_IP:?}" "${GALAXY_TURN_REALM:?}" <<'REMOTE'
set -euo pipefail
dnf -q -y install coturn
id galaxybridge >/dev/null
install -d -m 700 -o galaxybridge -g galaxybridge /var/lib/galaxyplay-bridge
if [ ! -s /var/lib/galaxyplay-bridge/turn-secret ]; then umask 077; openssl rand -hex 32 > /var/lib/galaxyplay-bridge/turn-secret; fi
chown galaxybridge:galaxybridge /var/lib/galaxyplay-bridge/turn-secret
chmod 600 /var/lib/galaxyplay-bridge/turn-secret
install -d -m 750 -o root -g galaxybridge /etc/galaxyplay-turn
umask 077
cat > /etc/galaxyplay-turn/turnserver.conf <<CONFIG
listening-port=3478
listening-ip=0.0.0.0
relay-ip=$2
external-ip=$1/$2
realm=$3
fingerprint
use-auth-secret
static-auth-secret=$(cat /var/lib/galaxyplay-bridge/turn-secret)
min-port=49160
max-port=49200
no-cli
no-tls
no-multicast-peers
no-tcp-relay
user-quota=12
total-quota=24
max-bps=4000000
bps-capacity=12000000
no-stdout-log
log-file=/var/lib/galaxyplay-bridge/turn.log
simple-log
denied-peer-ip=0.0.0.0-0.255.255.255
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=127.0.0.0-127.255.255.255
denied-peer-ip=169.254.0.0-169.254.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=192.168.0.0-192.168.255.255
CONFIG
chown root:galaxybridge /etc/galaxyplay-turn/turnserver.conf
chmod 640 /etc/galaxyplay-turn/turnserver.conf
cat > /etc/systemd/system/galaxyplay-turn.service <<'UNIT'
[Unit]
Description=GalaxyPlay private WebRTC TURN relay
After=network-online.target
[Service]
User=galaxybridge
Group=galaxybridge
ExecStart=/usr/bin/turnserver -c /etc/galaxyplay-turn/turnserver.conf
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=/var/lib/galaxyplay-bridge
UMask=0077
[Install]
WantedBy=multi-user.target
UNIT
if firewall-cmd --state >/dev/null 2>&1; then
 for port in 3478/tcp 3478/udp 49160-49200/udp; do
  firewall-cmd --permanent --add-port="$port" >/dev/null
  firewall-cmd --add-port="$port" >/dev/null
 done
fi
systemctl daemon-reload
systemctl enable --now galaxyplay-turn.service
systemctl restart galaxyplay-turn.service
systemctl is-active --quiet galaxyplay-turn.service
REMOTE
