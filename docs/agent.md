# rui-agent

`rui-agent` runs inside a guest and lets Rui reach the guest's services without any network between them. Rui
connects to it over vsock (port 5741) for AVF VMs, or over TCP when a test points it at one
(`rui-agent --tcp 0.0.0.0:5741`).

It's a static 64-bit Arm Linux binary, about 0.5 MB, built from `crates/rui-agent` with no C toolchain:

```bash
CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER=rust-lld cargo build --release -p rui-agent --target aarch64-unknown-linux-musl
```

The app carries it as the asset `rui-agent-aarch64` and installs it on first boot through cloud-init, as
`/usr/local/bin/rui-agent` with the systemd unit `rui-agent.service`. Never build it inside a guest on the phone.

## Protocol

Each request is a fresh connection. Rui sends one line; the agent answers with one line, and for the two stream
requests the connection then carries the stream.

| Request          | Reply                         | Then                                                                    |
| ---------------- | ----------------------------- | ----------------------------------------------------------------------- |
| `HELLO`          | `OK rui-agent <version>`      | The agent closes                                                        |
| `PORTS`          | `OK [22,5901]`                | The TCP ports something listens on, from `/proc/net/tcp` and `tcp6`     |
| `CONNECT <port>` | `OK`, or `ERR <reason>`       | Bytes both ways between Rui and `127.0.0.1:<port>` in the guest         |
| `UDP <port>`     | `OK`, or `ERR <reason>`       | Datagrams both ways, each a big-endian `u16` length and its bytes       |
| `POWEROFF`       | `OK`                          | The guest runs `systemctl poweroff`                                     |

Anything else gets `ERR unknown request`. A line is at most 256 bytes.

## Security

The agent only listens on vsock, which only the host can reach, and only connects to the guest's own loopback.
`POWEROFF` lets the host shut the guest down, which it can always do anyway by stopping the VM. In TCP test mode it
listens on whatever address it's given, so only use that mode on a guest that nothing else can reach.
