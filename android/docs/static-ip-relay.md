# Static-IP relay for Zerodha orders

Since 1 April 2026 SEBI requires API orders to come from an IP address you have
registered with your broker. A phone on mobile data - and most home broadband -
has no fixed IP, so orders sent straight from IraAlgo on the phone would be
rejected.

The fix keeps both apps standalone: route the phone (and the PC) through a small
VPN server whose public IP never changes, and register that one IP with Zerodha.
The server runs nothing but WireGuard. It holds no keys, runs no IraAlgo code,
and only forwards encrypted traffic it cannot read.

```
phone (IraAlgo)  ──WireGuard──┐
                              ├──>  VPS  1.2.3.4  ──HTTPS──>  api.kite.trade
PC (IraAlgo)     ──WireGuard──┘     (registered with Zerodha)
```

## 1. Rent the server (10 minutes)

Any provider with a **static public IPv4**; pick an **Indian region (Mumbai)** so
latency stays low. The smallest plan is plenty (1 vCPU, 512 MB-1 GB RAM).
Choose **Ubuntu 22.04 or 24.04**. Note the public IP it shows.

Open **UDP 51820** in the provider's firewall / security group.

**Free option: Oracle Cloud "Always Free".** Sign up at https://signup.cloud.oracle.com/
with India West (Mumbai) or India South (Hyderabad) as the home region (Always Free
resources exist only there, and it cannot be changed later). Create an Always Free VM
(VM.Standard.E2.1.Micro or Ampere A1) with Ubuntu, make its public IP **Reserved**
(Networking -> Reserved public IPs), and add an ingress rule for UDP 51820 from 0.0.0.0/0
in the subnet's security list. Oracle's Ubuntu images also reject new ports in the VM's
own firewall; the setup script inserts its rules ahead of that, so nothing else is needed.

## 2. Install the relay (one command)

```bash
# from your PC, in the options-lab repository:
scp android/tools/wg-relay-setup.sh root@<server-ip>:
ssh root@<server-ip>
sudo bash wg-relay-setup.sh            # creates peers "phone" and "pc"
```

It installs WireGuard, turns on forwarding, creates a key pair per device, and
prints the IP to register plus a **QR code for the phone**. Run it again with
extra names (`sudo bash wg-relay-setup.sh tablet`) to add devices; existing ones
are kept.

## 3. Register the IP with Zerodha

developers.kite.trade → your app → the static IP / IP whitelist setting → enter
the server's IP exactly as the script printed it. (Check there how many IPs your
app may register; if two are allowed you can also add a static home-broadband IP.)

## 4. Connect the phone

1. Install **WireGuard** from the Play Store.
2. **+** → **Scan from QR code** → scan the code the script printed.
3. Switch the tunnel on. Open `https://api.ipify.org` in the browser: it must show
   the server's IP.
4. Optional but recommended: Android **Settings → Network → VPN → WireGuard →
   Always-on VPN**, so no order can leave outside the tunnel.

## 5. Connect the PC

Copy `/etc/wireguard/peers/pc.conf` from the server to the PC
(`scp root@<server-ip>:/etc/wireguard/peers/pc.conf .`), install WireGuard for
Windows, **Import tunnel(s) from file**, activate. IraAlgo on the PC now reaches
Zerodha from the same registered IP.

## Checks and troubleshooting

- **Orders rejected with an IP message:** the tunnel is off, or the registered IP
  differs from `https://api.ipify.org`. Both must match.
- **No internet with the tunnel on:** UDP 51820 is blocked at the provider, or the
  server rebooted before `wg-quick@wg0` was enabled (`systemctl status wg-quick@wg0`).
- **Revoke a lost phone:** delete its `[Peer]` block in `/etc/wireguard/wg0.conf`
  and run `wg syncconf wg0 <(wg-quick strip wg0)`; issue a new peer.
- The server sees only encrypted packets. Your Kite API secret and access token
  stay in IraAlgo's encrypted vault on each device.
