# minori-agent

`minori-agent` runs inside a guest and lets Minori reach the guest's services without any network between them. Minori
connects to it over vsock (port 5741) for AVF VMs, or over TCP when a test points it at one
(`minori-agent --tcp 0.0.0.0:5741`). It also runs in Android's own Linux terminal, on TCP with a token, so that Minori
can carry the terminal's traffic (see below).

It's a static 64-bit Arm Linux binary, about 0.5 MB, built from `crates/minori-agent` with no C toolchain:

```bash
CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER=rust-lld cargo build --release -p minori-agent --target aarch64-unknown-linux-musl
```

The app carries it as the asset `minori-agent-aarch64` and installs it on first boot through cloud-init, as
`/usr/local/bin/minori-agent` with the systemd unit `minori-agent.service`. Never build it inside a guest on the phone.

## Protocol

Each request is a fresh connection. Minori sends one line; the agent answers with one line, and for the two stream
requests the connection then carries the stream.

| Request          | Reply                         | Then                                                                    |
| ---------------- | ----------------------------- | ----------------------------------------------------------------------- |
| `HELLO`          | `OK minori-agent <version>`      | The agent closes                                                        |
| `PORTS`          | `OK [22,5901]`                | The TCP ports something listens on, from `/proc/net/tcp` and `tcp6`     |
| `CONNECT <port>` | `OK`, or `ERR <reason>`       | Bytes both ways between Minori and `127.0.0.1:<port>` in the guest         |
| `UDP <port>`     | `OK`, or `ERR <reason>`       | Datagrams both ways, each a big-endian `u16` length and its bytes       |
| `NET`            | `OK`, or `ERR <reason>`       | The guest's IP packets both ways, framed like `UDP`, through TUN `minori0` |
| `TIME <ms>`      | `OK`, or `ERR <reason>`       | Sets the guest's clock to the phone's if they differ by over a second    |
| `POWEROFF`       | `OK`                          | The guest runs `systemctl poweroff`                                     |

Anything else gets `ERR unknown request`. A line is at most 256 bytes.

With `--token-file <path>` (TCP only), every connection first sends `TOKEN <token>` on a line of its own, then the
request. A missing or wrong token gets `ERR wrong token` and the connection closes. The token is at least 32
characters; Minori's are 64 hex digits.

`NET` gives the TUN device the address `10.233.77.2/24`, MTU 1280 and the default route with metric 50, so it wins
over any other network card, and points DNS at 1.1.1.1 and 9.9.9.9 through it: with systemd-resolved for that link,
otherwise by replacing `/etc/resolv.conf` and putting the old one back when the connection ends.

## Android's terminal

Android gives its terminal's VM a network by tethering (`avf_tap_*`, `TETHERING_VIRTUAL`). Tethering's upstream is the
system's default network, never an app's VPN, so the terminal's traffic skips Saki, Akiyama, Shira or any other VPN.
On the Pixel this showed as `avf_tap_2075 - TetheredState` with `Current upstream interface(s): [wlan0]` in
`dumpsys tethering`.

Minori's **Android's terminal** card in Settings puts the agent and `terminal-install.sh` in a fresh folder in
Downloads, which the terminal sees as `/mnt/shared/Download`, and gives a command to run there with a token. The
installer puts the agent in `/usr/local/bin/minori-agent`, the token in `/etc/minori/token` (root only), and runs
`minori-agent --tcp 127.0.0.1:5741 --token-file /etc/minori/token` as the system unit `minori-agent.service`, since
the TUN device needs root. The terminal then asks to forward port 5741 to the phone's loopback. With the card's switch
on, Minori asks that port for `NET` and puts the packets through the same NAT as its own machines, so they leave as
Minori's sockets and follow the phone's VPN. When Minori stops, the TUN device and its route go away and the terminal
falls back to tethering.

Any app on the phone can connect to a port the terminal forwards, which is why the token is needed. It travels in the
command rather than a file, because every app with storage access can read Downloads.

## Security

In a Minori machine the agent only listens on vsock, which only the host can reach. In Android's terminal it listens
on the VM's loopback and answers only Minori's token. Either way it only connects to the guest's own loopback.
`POWEROFF` lets the host shut the guest down, which it can always do anyway by stopping the VM. In TCP test mode it
listens on whatever address it's given, so only use that mode on a guest that nothing else can reach.
