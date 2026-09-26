#!/usr/bin/env bash
# IraAlgo static-IP relay: a WireGuard VPN on a small VPS, so the phone and the
# PC reach Zerodha from ONE fixed IP that is registered in your Kite Connect app.
#
# The relay only forwards encrypted traffic. It runs no IraAlgo code, holds no
# credentials, and cannot read the HTTPS between your devices and Zerodha.
#
# Usage (as root on a fresh Ubuntu 22.04/24.04 VPS with a static public IP):
#   curl -fsSLO <this file> && sudo bash wg-relay-setup.sh            # phone + pc
#   sudo bash wg-relay-setup.sh phone pc laptop                       # custom peers
#
# Re-running is safe: existing keys and peers are kept, new peer names are added.
set -euo pipefail

PEERS=("$@")
[ "$#" -eq 0 ] && PEERS=(phone pc)
WG_DIR=/etc/wireguard
PORT=51820
NET=10.66.66
DNS=1.1.1.1

[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)"; exit 1; }

echo "== installing WireGuard"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq wireguard qrencode iptables curl >/dev/null

PUBLIC_IP=$(curl -fsS4 https://api.ipify.org || curl -fsS4 https://ifconfig.me)
IFACE=$(ip -4 route show default | awk '{print $5; exit}')
echo "   public IP $PUBLIC_IP on $IFACE"

umask 077
mkdir -p "$WG_DIR/peers"
[ -f "$WG_DIR/server.key" ] || wg genkey | tee "$WG_DIR/server.key" | wg pubkey > "$WG_DIR/server.pub"
SERVER_PUB=$(cat "$WG_DIR/server.pub")

if [ ! -f "$WG_DIR/wg0.conf" ]; then
cat > "$WG_DIR/wg0.conf" <<EOF
[Interface]
Address = $NET.1/24
ListenPort = $PORT
PrivateKey = $(cat "$WG_DIR/server.key")
PostUp = iptables -t nat -A POSTROUTING -s $NET.0/24 -o $IFACE -j MASQUERADE; iptables -I INPUT 1 -p udp --dport $PORT -j ACCEPT; iptables -I FORWARD 1 -i wg0 -j ACCEPT; iptables -I FORWARD 1 -o wg0 -j ACCEPT
PostDown = iptables -t nat -D POSTROUTING -s $NET.0/24 -o $IFACE -j MASQUERADE; iptables -D INPUT -p udp --dport $PORT -j ACCEPT; iptables -D FORWARD -i wg0 -j ACCEPT; iptables -D FORWARD -o wg0 -j ACCEPT
EOF
fi

echo "net.ipv4.ip_forward=1" > /etc/sysctl.d/99-wg-relay.conf
sysctl -q -p /etc/sysctl.d/99-wg-relay.conf

next_ip() {
  local used; used=$( (grep -ho "AllowedIPs = $NET\.[0-9]*" "$WG_DIR/wg0.conf" || true) | awk -F. '{print $4}' | sort -n | tail -1)
  echo $(( ${used:-1} + 1 ))
}

for name in "${PEERS[@]}"; do
  conf="$WG_DIR/peers/$name.conf"
  if [ -f "$conf" ]; then echo "== peer $name exists, keeping it"; continue; fi
  echo "== adding peer $name"
  key=$(wg genkey); pub=$(echo "$key" | wg pubkey); psk=$(wg genpsk); host=$(next_ip)
  cat >> "$WG_DIR/wg0.conf" <<EOF

[Peer]
# $name
PublicKey = $pub
PresharedKey = $psk
AllowedIPs = $NET.$host/32
EOF
  cat > "$conf" <<EOF
[Interface]
PrivateKey = $key
Address = $NET.$host/32
DNS = $DNS

[Peer]
PublicKey = $SERVER_PUB
PresharedKey = $psk
Endpoint = $PUBLIC_IP:$PORT
# Full tunnel: every request, Zerodha's included, leaves from $PUBLIC_IP.
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25
EOF
done

if command -v ufw >/dev/null && ufw status | grep -q active; then ufw allow $PORT/udp >/dev/null; fi
systemctl enable --now wg-quick@wg0 >/dev/null 2>&1 || true
wg syncconf wg0 <(wg-quick strip wg0)

echo
echo "================================================================"
echo " Relay ready. Register THIS IP in your Kite Connect app:"
echo "     $PUBLIC_IP"
echo " (developers.kite.trade -> your app -> IP whitelist / static IP)"
echo "================================================================"
for name in "${PEERS[@]}"; do
  echo; echo "---- $name: $WG_DIR/peers/$name.conf"
  [ "$name" = "phone" ] && qrencode -t ansiutf8 < "$WG_DIR/peers/$name.conf"
done
echo
echo "Phone: WireGuard app -> + -> Scan from QR code (above)."
echo "PC:    copy $WG_DIR/peers/pc.conf to the PC and import it in the WireGuard app."
echo "Check: with the tunnel on, open https://api.ipify.org - it must show $PUBLIC_IP."
