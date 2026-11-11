# Minori and Asahi (and Airi)

Asahi is a web page that shows a Pixel's desktop in a browser tab and gives the phone a network, all over one
USB cable, built on Shinonome's and Shira's libraries. This is how Asahi shows Minori's VMs too, and what
each side provides. Minori's side and Asahi's source picker are built and tested on the emulator; Asahi's side is on a
local branch until Minori is released, since it needs Minori's library from a release.

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

While Minori runs, it listens on the abstract Unix socket `minori`. A page starts Minori without opening it on the
phone by running `am start-foreground-service -a com.wonderhoylabs.minori.WAKE -n
com.wonderhoylabs.minori/.service.MinoriService` over adb (the library's `WAKE_COMMAND`). Only adb's shell may: the
service needs `DUMP`, which the shell has and apps can't get. Android freezes apps in the background, so Minori then
runs its foreground service, with a notification saying a computer is using it, until a minute after the page's last
request or stream. A page reaches it through the adb connection it
already has, with Tango's `adb.createSocket("localabstract:minori")`; from a computer, `adb forward tcp:5742
localabstract:minori`. Android tells Minori the uid of each peer, and Minori only answers adb's shell user (2000) and root.
Every other app on the phone is refused.

Requests and replies are one JSON object per line. A request has an `id`, which the reply repeats, a `method` and
`params`. A reply has `result` or `error`.

| Method    | Params             | Result                                                                                       |
| --------- | ------------------ | -------------------------------------------------------------------------------------------- |
| `hello`   |                    | `{ "name": "Minori", "version", "protocol": 1, "methods": [...] }`                              |
| `list`    |                    | Every machine: `id`, `name`, `backend`, `state`, `os`, `agent`, `vncPort`, `sshPort`, `rdpPort`, `user`, `airi` |
| `start`   | `vm`               | The machine, as `list` describes it. Starting continues after the reply.                     |
| `stop`    | `vm`               | The same. Minori asks the guest to shut down, then stops it after 30 seconds.                   |
| `ports`   | `vm`               | `published`: the ports the user published; `listening`: what the guest's agent reports       |
| `desktop` | `vm`               | `{ "port", "password" }`: the guest's VNC port, and its VNC password if it has one           |
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
<link>` over adb.

`minori://vm/<id or name>/app/<desktop file ID>` opens one Linux app from a machine, the way Airi's
`airi://app/<desktop file ID>` does from Android's terminal, and Airi is what shows it. It needs Airi set up in that
machine (`airi` is true in `list`). Minori starts the machine if it has to, waits for Airi's agent in it, then opens
the app; a machine that isn't set up gets a message instead. Asahi shows one alone through Shinonome's
`--app minori://vm/<id>/app/<desktop file ID>`. Airi lists the apps while the machine runs: see Airi's README.

## The library

`packages/minori` is `@wonderhoy-labs/minori`, the TypeScript side of all this: `Minori` for the helper, and a small
VNC client (RFB 3.8, VNC passwords, raw and copy-rect updates) that draws on a canvas and sends the mouse and keyboard.
The README's "Use it in your app" has an example. It has no dependencies, and takes anything that opens adb services,
so it works with Tango.

## In Asahi

Asahi checks whether Minori is installed when a phone connects, which doesn't start it. Without Minori nothing changes.
With it, the bar gets a source menu: the Pixel's desktop, and each machine's desktop (if it has Minori's agent) and
console. Picking a machine starts it if it's stopped, waits for its desktop to listen, and draws it where the Pixel's
desktop was, with the network still running. The Pixel's desktop keeps running on the phone meanwhile, and picking it
again reconnects. The console is xterm.js.

Tested on the emulator with the dev adb bridge: the menu listed the machine, its desktop came up at 1280×720 with
keyboard and mouse, its console ran commands, and switching back reconnected the Pixel's desktop. From Node over the
same helper path a key takes 16 ms to show up on the desktop; in the page it took seconds, but the browser pane was
hidden, so the page ran as a throttled background tab, and that number says little.

## What's left

- Releasing Minori, so Asahi can depend on the library's tarball instead of a local link. The shared web workflow
  builds `web/`, which Minori doesn't have, so releasing the package also needs a small `web/` or a library-only option
  in that workflow.
- Shinonome's mirror mode for the Pixel's own screen.
- One Linux app from Airi or a Minori machine in the tab. Airi's windows are Android windows, so in Asahi they'd come
  through the Pixel desktop; opening one there needs Shinonome to say which display its desktop is.

## Airi and Minori

Airi shows apps from Android's own Linux terminal as Android windows and launcher apps. Minori can put the same agent
in one of its machines: a machine's page has "Apps on your launcher", which takes the agent and installer from the
installed Airi app, runs the installer in the guest over SSH with the token from Airi's setup command, and publishes the
guest's port 47821 on the phone's loopback, where Airi looks. Airi itself doesn't change. Only one of Android's terminal
and a Minori machine can have that port at a time.
