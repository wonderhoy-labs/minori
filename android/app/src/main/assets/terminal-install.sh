#!/bin/sh
# Lets Minori carry the traffic of Android's Linux terminal. Android gives this Linux its network by tethering, which
# never goes through a VPN app such as Saki, Akiyama or Shira. With this, minori-agent puts the traffic on a TUN device
# and hands it to Minori over a port the terminal forwards, and Minori sends it on through the phone's VPN, if any.
# The Minori app puts this next to the agent and gives you the command that runs it, with a token only it knows.
set -eu

token=${1:?Run this with the command from the Minori app}
here=$(dirname "$(readlink -f "$0")")

if [ ! -e /dev/net/tun ]; then
	echo "This Linux has no TUN device (/dev/net/tun), so Minori can't carry its traffic." >&2
	exit 1
fi

sudo install -D -m 755 "$here/minori-agent" /usr/local/bin/minori-agent
sudo install -d -m 700 /etc/minori
printf '%s\n' "$token" | sudo tee /etc/minori/token > /dev/null
sudo chmod 600 /etc/minori/token

sudo tee /etc/systemd/system/minori-agent.service > /dev/null << 'UNIT'
[Unit]
Description=Minori's agent, which carries this Linux's traffic through the phone's apps
After=network.target

[Service]
ExecStart=/usr/local/bin/minori-agent --tcp 127.0.0.1:5741 --token-file /etc/minori/token
Restart=always
RestartSec=1

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable minori-agent
sudo systemctl restart minori-agent

rm -rf "$here" || true
echo
echo 'Minori can carry this Linux'"'"'s traffic now. If Android asks about port 5741, tap "Allow always", then go back to Minori.'
