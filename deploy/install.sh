#!/usr/bin/env bash
# Install Foodtale CMS on Raspberry Pi OS.
# Usage:
#   sudo ./install.sh
#   sudo ./install.sh --static-ip 192.168.1.50 --gateway 192.168.1.1 --interface eth0
set -euo pipefail

STATIC_IP=""
GATEWAY=""
IFACE="eth0"
PREFIX="24"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --static-ip) STATIC_IP="$2"; shift 2 ;;
    --gateway) GATEWAY="$2"; shift 2 ;;
    --interface) IFACE="$2"; shift 2 ;;
    --prefix) PREFIX="$2"; shift 2 ;;
    *) echo "unknown arg $1"; exit 1 ;;
  esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN_SRC="$ROOT/cms/foodtale-cms"
if [[ ! -x "$BIN_SRC" ]]; then
  echo "build the binary first: (cd cms && GOOS=linux GOARCH=arm64 go build -o foodtale-cms .)"
  exit 1
fi

id foodtale >/dev/null 2>&1 || useradd --system --home /var/lib/foodtale --shell /usr/sbin/nologin foodtale
mkdir -p /etc/foodtale /var/lib/foodtale/media /var/lib/foodtale/public
if [[ ! -f /etc/foodtale/cms.env ]]; then
  CMS_ID="$(cat /proc/sys/kernel/random/uuid | tr -d '-')"
  sed "s/^CMS_ID=.*/CMS_ID=${CMS_ID}/" "$ROOT/deploy/cms.env.example" > /etc/foodtale/cms.env
fi
install -m 0755 "$BIN_SRC" /usr/local/bin/foodtale-cms
install -m 0644 "$ROOT/deploy/foodtale-cms.service" /etc/systemd/system/foodtale-cms.service
chown -R foodtale:foodtale /var/lib/foodtale

if [[ -n "$STATIC_IP" ]]; then
  if [[ -z "$GATEWAY" ]]; then
    echo "--gateway is required with --static-ip"
    exit 1
  fi
  echo "Pinning $IFACE to ${STATIC_IP}/${PREFIX} via $GATEWAY"
  if command -v nmcli >/dev/null 2>&1; then
    CON="$(nmcli -t -f NAME,DEVICE con show --active | awk -F: -v i="$IFACE" '$2==i{print $1; exit}')"
    CON="${CON:-foodtale-static}"
    nmcli con mod "$CON" ipv4.addresses "${STATIC_IP}/${PREFIX}" ipv4.gateway "$GATEWAY" ipv4.method manual || \
      nmcli con add type ethernet ifname "$IFACE" con-name foodtale-static ipv4.method manual ipv4.addresses "${STATIC_IP}/${PREFIX}" ipv4.gateway "$GATEWAY"
    nmcli con up "$CON" || true
  elif [[ -w /etc/dhcpcd.conf ]]; then
    if ! grep -q "Foodtale static" /etc/dhcpcd.conf; then
      cat >> /etc/dhcpcd.conf <<EOF

# Foodtale static
interface $IFACE
static ip_address=${STATIC_IP}/${PREFIX}
static routers=${GATEWAY}
static domain_name_servers=${GATEWAY} 1.1.1.1
EOF
    fi
  else
    echo "Could not pin IP. DHCP-reserve ${STATIC_IP} on the router."
  fi
fi

systemctl daemon-reload
systemctl enable --now foodtale-cms
sleep 1
hostname -I || true
echo "Admin: http://$(hostname -I | awk '{print $1}'):8080"
