# Minori and Asahi (and Airi)

Asahi is a web page that shows a Pixel's desktop in a browser tab and gives the phone a network, all over one
USB cable, built on Shinonome's and Shira's libraries. This is the plan for letting Asahi also show Minori's VMs, and
what Minori already provides for it. Minori's side is built and tested; Asahi's source picker isn't built yet.

## What Asahi should become

After connecting, the page lets the user pick a source, and can still use Shira for the phone's network:

1. **Pixel desktop**: Shinonome's virtual display, as today. This stays the default, so nobody who never installs
   Minori notices a change.
2. **Pixel screen**: the phone's own display. This belongs in Shinonome's library (a mirror mode next to its
   virtual display), released and consumed by Asahi, not copied into Asahi.
3. **A Minori VM's desktop**: the guest's VNC server, through Minori's helper (below), drawn in the tab with keyboard and
   mouse.
4. **One Linux app**: from Airi (`airi://app/<desktop file ID>` and Airi's client library, unchanged), or later
   from a Minori VM running an Airi-style agent.
5. **A terminal**: the VM's serial console or SSH, through the helper, in a terminal in the tab.

## Minori's helper

While Minori runs, it listens on the abstract Unix socket `minori`. A page reaches it through the adb connection it
already has, with Tango's `adb.createSocket("localabstract:minori")`; from a computer, `adb forward tcp:5742
localabstract:minori`. Android tells Minori the uid of each peer, and Minori only answers adb's shell user (2000) and root.
Every other app on the phone is refused.

Requests and replies are one JSON object per line. A request has an `id`, which the reply repeats, a `method` and
`params`. A reply has `result` or `error`.

| Method    | Params             | Result                                                                                       |
| --------- | ------------------ | -------------------------------------------------------------------------------------------- |
| `hello`   |                    | `{ "name": "Minori", "version", "protocol": 1, "methods": [...] }`                              |
| `list`    |                    | Every machine: `id`, `name`, `backend`, `state`, `os`, `agent`, `vncPort`, `sshPort`, `rdpPort`, `user` |
| `start`   | `vm`               | The machine, as `list` describes it. Starting continues after the reply.                     |
| `stop`    | `vm`               | The same. Minori asks the guest to shut down, then stops it after 30 seconds.                   |
| `ports`   | `vm`               | `published`: the ports the user published; `listening`: what the guest's agent reports       |
| `connect` | `vm`, `port`       | `{ "stream": true }`, then the connection is a byte stream to that port in the guest         |
| `console` | `vm`               | `{ "stream": true }`, then the connection is the VM's console: its output, and your keys     |

`vm` is a machine's id or its name. `state` is `stopped`, `starting`, `running`, `stopping` or `error`. After a
stream starts, the connection carries nothing else; open a new one for more requests. `connect` reaches guest ports
whether or not the user published them, because only adb can ask; it's how the page reaches the guest's VNC server
(`vncPort`) for a desktop, or its SSH server.

`tools/minori-client.ts` is a stand-in for Asahi that exercises all of this:

```bash
adb forward tcp:5742 localabstract:minori
node tools/minori-client.ts check
```

## Links

`minori://vm/<id or name>` opens a machine's page in Minori, and `minori://vm/<id or name>/console`, `/ssh` or `/vnc` one
of its screens. They never start anything. A page can send them with `am start -a android.intent.action.VIEW -d
<link>` over adb. For one app inside a Minori VM, the link is planned as `minori://vm/<id>/app/<desktop file ID>`,
mirroring Airi's `airi://app/<desktop file ID>`, once a guest agent can show single windows.

## What's left, in Asahi and the libraries

- A TypeScript library for this protocol. By the Wonderhoy Labs rules it lives in Minori as `@wonderhoy-labs/minori`,
  released with Minori, and Asahi depends on the release. Minori has no web app yet, and the shared web workflow builds
  `web/`, so adding the package also means a small `web/` (a page that uses it) or a change to that workflow.
- An RFB (VNC) viewer and a terminal in the page. noVNC (MPL 2.0) and xterm.js (MIT) are the obvious choices.
- The source picker in Asahi, defaulting to the Pixel desktop.
- Shinonome's mirror mode for the Pixel screen.

## Airi and Minori

Airi shows apps from Android's own Terminal VM as Android windows. Minori runs other VMs. They don't overlap: Airi is
the way to pin one Linux app to the launcher, Minori the way to make a Fedora, a second Debian or a KDE desktop. A Minori
guest could later run an Airi-style compositor agent so its apps appear as Android windows too; that isn't built,
and Minori doesn't ship a half-working one.
