<h1 align="center">Rui</h1>

<p align="center">Your Pixel's hypervisor.</p>

<p align="center">
  <a href="https://github.com/wonderhoy-labs/rui/actions/workflows/ci.yml"><img src="https://github.com/wonderhoy-labs/rui/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License: Apache 2.0"></a>
  <a href="#license-and-credits"><img src="https://img.shields.io/badge/Kamishiro%20Rui-Wonderlands%C3%97Showtime-BB88EE" alt="Named for Kamishiro Rui of Wonderlands×Showtime"></a>
</p>

Rui runs virtual machines on your phone, with you in charge of them. Android's own Terminal app gives you one
Debian VM the way Google set it up; Rui lets you make as many as you like, from Debian, Ubuntu or Fedora cloud
images or your own disk images and ISOs, and choose their memory, CPUs, disks, ports and firewall. A serial
console, SSH and VNC are each one tap from the VM's page. It uses Android's Virtualization Framework (AVF), the
hypervisor Pixel phones have had since Android 14, and needs no root.

It's for people who want a real Linux machine in their pocket: to run servers, try distributions, or keep a
desktop VM around. Phones without AVF can keep Linux userlands through Termux instead, clearly labelled as not
being VMs.

<p align="center"><img src="docs/screenshots/machine.png" alt="A machine's page in Rui, on the Android emulator" width="320"></p>

This is early software, and its main part has **not run on a phone yet**: no device with AVF was available while it
was written, so every VM shown here ran on the emulator's demo backend. [What has been tested](#what-has-been-tested)
says exactly what ran.

## What you need

- A phone with Android's Virtualization Framework: a Pixel 6 or newer on Android 14 or later. Rui checks, and says
  so if yours doesn't have it.
- Two permissions Android only grants through adb or Shizuku, once. Either:
  - [Shizuku](https://shizuku.rikka.app/) running, so Rui can grant them itself, or
  - a computer with [adb](https://developer.android.com/tools/adb) and USB debugging turned on.
- 64-bit Arm (`aarch64`, `arm64`) guests. A phone can't run x86-64 images or Windows.
- Space: a Debian VM takes about 1.2 GB to start with.
- For the Termux backend instead: [Termux](https://termux.dev) from F-Droid or its GitHub releases.

## Install

There's no release yet. To build from source, see [Development](#development), then:

```bash
adb install android/app/build/outputs/apk/release/app-release.apk
```

## Use it

### Give Rui its permissions

Open Rui. If Android hasn't granted its two AVF permissions, the setup screen opens and shows what's missing.

- **With Shizuku:** tap **Allow Rui in Shizuku**, accept Shizuku's prompt, then **Grant with Shizuku**. Android
  closes Rui for a moment when the permissions land, and Rui opens on the setup screen again.
- **With a computer:** run the two commands the screen shows, which you can copy from it:

```bash
adb shell pm grant com.wonderhoylabs.rui android.permission.USE_CUSTOM_VIRTUAL_MACHINE
```

```bash
adb shell pm grant com.wonderhoylabs.rui android.permission.MANAGE_VIRTUAL_MACHINE
```

Android closes Rui when the second one is granted. Open it again and it checks by itself.

### Make a machine

Tap **New machine** and pick a source:

- **Catalog:** Debian 13, Ubuntu 24.04 LTS or Fedora 43, downloaded from the project's own servers and checked
  against its published checksums, or a KDE Plasma desktop or a Chromium browser built from Debian on first boot.
- **Your images:** a raw disk, `.img`, qcow2 or ISO you imported. Rui looks inside first and tells you what it is,
  which CPU it's for and whether it can boot it.
- **Termux userland:** see below.

Choose the name, CPUs, memory and disk size. Rui suggests 2 GiB of memory on phones with 8 GiB or more and warns
past half of the phone's memory. AVF offers one virtual CPU or one per core, nothing in between. For cloud images,
Rui sets up a user, a password it shows you once, an SSH key and its guest agent on first boot, with cloud-init.

### Start, stop and connect

The machine's page has **Start**, **Shut down** (asks the guest to power off) and **Force stop**. Under
**Connect**:

- **Console:** the guest's serial console, kept open while the VM runs. Login shells on it learn the terminal's
  size, so `top` and `vim` fit the screen.
- **SSH:** logs in with the user and password or key Rui set up, through its own connection to the guest. You're
  asked to trust the guest's host key the first time.
- **VNC:** the guest's desktop, if it runs a VNC server. Tap to click, long-press to right-click, drag to move.
- **RDP** and **SSH in another app:** open your RDP or SSH app on a port Rui publishes on the phone for it.

### Publish ports and set the firewall

Under **Network**, **Publish a port** makes a guest port reachable on the phone: TCP or UDP, a phone port of 1024
or more, and either this phone only (127.0.0.1) or its networks too. Each port can have an allow list and a deny
list of addresses or ranges such as `192.168.1.0/24`; deny always wins. Nothing is published until you add it, and
the page shows each port's state and how many connections it served or refused.

### Disks, images, import and export

- **Images** lists what you imported, with its format, size, SHA-256, CPU and what Rui found inside.
- **Edit** a stopped machine to change its CPUs and memory, grow a disk, add an empty disk or attach an image,
  choose which disk it boots from, and change the kernel command line.
- **Export** writes a `.ruivm` archive anywhere the system's file picker can save; **Import a .ruivm archive** on
  the main screen brings one back. The format is in [docs/archive.md](docs/archive.md).
- **Clone** copies a machine, disks and all. **Delete** asks whether to keep its disks in Images.

### Without a hypervisor: Termux

On a phone without AVF, a **Termux userland** is a Debian, Ubuntu, Arch, Alpine or Fedora installed by Termux's
proot-distro, with an SSH server that Rui's console logs in to. Allow Rui in Termux first:

```bash
mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties
```

It shares the phone's kernel and network; it's not a VM, and Rui labels it **Termux** everywhere.

## Use it in your app

Rui has three ways in.

**`rui-core`, the Rust library** that does Rui's image work: inspect an image and find its kernel, convert qcow2 to a
sparse raw disk, extract a bootable kernel and initrd, write cloud-init seeds, and pack or unpack `.ruivm`
archives. Add it by the release tag:

```toml
rui-core = { git = "https://github.com/wonderhoy-labs/rui", tag = "v0.1.0" }
```

```rust
let report = rui_core::inspect::inspect(Path::new("debian.qcow2"))?;
if let Some(plan) = &report.boot {
	let files = rui_core::boot::extract(Path::new("debian.qcow2"), plan, Path::new("boot"))?;
	println!("{} with {}", files.kernel.display(), files.cmdline);
}
```

Its main entry points are `inspect::inspect`, `boot::extract`, `convert::to_raw`, `convert::grow`,
`seed::write_nocloud` with `seed::CloudConfig`, and `archive::export` and `archive::import`.
`crates/rui-core/examples/in_process.rs` runs them together. The `rui` command-line tool is the same thing on a
computer (`rui inspect`, `convert`, `boot`, `seed`, `export`, `import`).

**The helper socket**, for pages and tools on a computer that reach the phone over adb, such as Asahi: JSON lines on
the abstract socket `rui`, answering only adb and root. It lists machines, starts and stops them, reports ports,
and opens a stream to any guest port or to the console:

```bash
adb forward tcp:5742 localabstract:rui
node tools/rui-client.ts call list
```

**Links:** `rui://vm/<id or name>`, and `/console`, `/ssh` or `/vnc` after it, open a machine's page or screen.
They never start anything.

The protocol and links are in [docs/asahi-integration.md](docs/asahi-integration.md).

## What has been tested

Everything below ran on an Apple M5 Mac (macOS 27.0.1) with Android Emulator 37.2.13 and Docker Desktop. **No
phone was used**: the maintainer's Pixel 10 Pro was unavailable, so nothing has run under AVF.

| What                                                                                              | Where                                       | Result |
| ------------------------------------------------------------------------------------------------- | ------------------------------------------- | ------ |
| `cargo test --workspace` (15 tests), Clippy, rustfmt                                              | Mac                                         | Pass   |
| `:app:testDebugUnitTest` (27 tests), Spotless, `:app:lintRelease`, `:app:assembleRelease`         | Mac                                         | Pass   |
| Inspect Debian 13's official arm64 cloud image (qcow2)                                            | Mac, `rui` CLI, and in the app on the emulator | Found GPT, ext4 root, arm64, kernel and initrd |
| qcow2 to raw                                                                                       | Mac                                         | Byte-identical to `qemu-img convert` |
| Kernel, initrd and command line Rui extracted, plus a seed from `rui seed`                       | QEMU 10 `virt`, TCG, in Docker              | Debian 13 booted, cloud-init applied the seed, SSH logged in |
| The disk, kernel and seed the **app** made on the emulator, exported, unpacked by `rui import`   | Same QEMU                                    | Booted: hostname, password, Rui's SSH key and `rui-agent` all set up; root grew to 8 GiB |
| Alpine 3.24 arm64 "virt" ISO: inspect, extract its kernel, boot with the ISO attached             | Same QEMU                                    | Reached the login prompt |
| `rui-agent` (HELLO, PORTS, CONNECT) over TCP                                                      | Debian 13 guest in QEMU                     | Pass   |
| First run, setup screen, adb grant, Shizuku grant (Shizuku 13.6.0), relaunch after the grant kill | Emulator, Android 17 (`CE2A.260420.051.A2`), user build | Pass   |
| Hidden API exemption reaches `VirtualMachineCustomImageConfig`                                    | Same emulator                               | Pass   |
| Import an image through the file picker, with SHA-256, make a VM (convert, extract kernel, seed) | Same emulator                               | Pass   |
| Demo backend console (a shell on the phone), typing with injected keys and Gboard                 | Same emulator                               | Pass   |
| Console attached to the real Debian guest's serial port (`ttyAMA0`, via TCP), login, `top` sized to the screen | Same emulator, debug test hooks | Pass   |
| SSH through `rui-agent` with host-key prompt (fingerprint checked against the guest)              | Same                                        | Pass   |
| Publish guest port 22, SSH to it from the Mac via `adb forward`; deny and allow rules            | Same                                        | Pass   |
| VNC to the guest's TigerVNC with a VNC password: live updates, pointer                            | Same                                        | Pass   |
| Export to `.ruivm` through the file picker (403 MB for an 8 GiB disk), import it back, clone, delete keeping disks | Same emulator | Pass   |
| Helper socket with `tools/rui-client.ts`: hello, list, start, ports, connect to SSH               | Same, with the real guest                   | Pass   |
| `rui://vm/Debian/console`                                                                         | Same emulator                               | Opens the console |
| Release build: start, list, settings, JNI disk usage                                              | Same emulator                               | Pass   |

**Never run:**

- **The AVF backend**: creating and running a VM, the console on AVF's UART, vsock to `rui-agent`, published ports
  over vsock, **Internet for the guest** (`useNetwork`), shutdown through the agent, and how much of this Android 17
  lets a non-system app do. [docs/avf.md](docs/avf.md) has the details.
- Catalog downloads in the app (Debian's image was downloaded and checked on the Mac instead), and the Ubuntu,
  Fedora, KDE Plasma and Chromium templates.
- The Termux backend, UDP ports, the RDP and "SSH in another app" buttons, auto-start at boot, growing a disk
  from Edit, and the helper's `console` stream.
- Refusing a non-adb app on the helper socket. Rui checks the peer's uid, but no other app tried.

## How it works

### The hypervisor

AVF runs crosvm, started by Android's virtualization service, which boots a Linux kernel with its disks as virtio
block devices. There's no PC firmware, so Rui finds the guest's kernel itself: it reads the image's partitions and
ext4 filesystem in Rust, takes the newest `/boot/vmlinuz-*` and its initrd, unpacks it to a raw arm64 kernel, and
builds the command line from the image's own boot loader settings. Custom kernels are hidden API on Android 17;
Rui exempts that one package for its own process. More in [docs/avf.md](docs/avf.md).

### Disks and images

Every VM disk is a raw, sparse file in Rui's private storage, so it takes only the space the guest writes and grows
with a `truncate`. qcow2 images are converted on import, ISOs are attached read-only, and installer ISOs for Debian,
Ubuntu, Fedora and Alpine boot their own installer kernel.

### First boot

For cloud images Rui writes a small FAT disk labelled `CIDATA` that cloud-init reads: the hostname, a user with a
password and an SSH key, `openssh-server`, Rui's guest agent and its systemd unit, and a login snippet that asks the
terminal its size.

### Ports and the guest agent

A published port is a listening socket in Rui's process. Each connection is checked against the port's allow and
deny lists, then joined to a connection into the guest: over vsock to `rui-agent`, which connects the guest port on
its own loopback. SSH and VNC in the app use the same path without publishing anything. The agent's protocol is in
[docs/agent.md](docs/agent.md).

### Backends

`AvfHypervisor` runs VMs, `TermuxHypervisor` runs proot userlands through Termux's `RUN_COMMAND` intent with SSH as
the console, and `DemoHypervisor` (debug builds only) stands in for a hypervisor with a shell on the phone. They
share one interface, so the rest of the app doesn't care which one runs a machine.

## Security and privacy

- **What Rui can reach:** its own storage, the files you pick, and the network for catalog downloads (only each
  project's official servers) and for ports you publish. It sends nothing anywhere else and has no analytics.
- **Guests** are unprotected AVF VMs: the Android side can read their memory and disks, which is what lets you
  manage them. They can't reach Android, except through ports you publish to them and through AVF's network if you
  turn it on.
- **Published ports** listen on 127.0.0.1 unless you choose the network. Any app on the phone can connect to a
  127.0.0.1 port, so protect services with their own passwords, or use the allow and deny lists. A port open to the
  network can be reached by anyone on the same Wi-Fi.
- **The helper socket** answers only adb's shell user and root, by the uid Android reports. Anyone with adb access to
  your phone can already do far more.
- **Hidden API:** Rui exempts `android.system.virtualmachine` from Android's hidden API checks, in its own process
  only. It doesn't change any system setting.
- **Secrets:** passwords and SSH keys stay in Rui's private storage. Exports leave them out unless you tick the box.
- The Shizuku helper only runs `pm grant` for Rui's own two permissions, then `am start` to reopen Rui.

## Troubleshooting

**"This device doesn't have Android's Virtualization Framework."** Rui needs a phone with AVF, such as a Pixel 6 or
newer on Android 14 or later. Emulators don't have it. Use the Termux backend instead.

**Rui closed while I granted the permissions.** That's Android: granting `MANAGE_VIRTUAL_MACHINE` restarts the app.
Open Rui again.

**"Shizuku is installed but Rui can't reach it."** Start Shizuku in its app. If it's running already, close Rui and
open it again, so it gets Shizuku's connection.

**An image says "Won't run here: it's for x86-64 PCs."** Download the ARM64 (`aarch64`) version of that image.

**An ISO says no kernel was found.** Rui knows the installer kernels of Debian, Ubuntu, Fedora and Alpine ISOs. For
others, use the distribution's ARM64 cloud image.

**A published port says it's already in use.** Another app or port uses that phone port; pick another above 1024.

**Published ports never connect.** They need Rui's guest agent, which machines made with first-boot setup have. Check
`systemctl status rui-agent` in the guest.

**`top` or `vim` doesn't fit the console.** Run `resize-console` after turning the phone; serial consoles can't tell
the guest the size by themselves.

## Known issues

- Not run on a phone with AVF at all yet (see above).
- Without AVF's network, guests have no internet. Guest internet over the agent is on the [roadmap](docs/roadmap.md).
- No logo or launcher icon yet: the app shows Android's default icon.
- AVF only offers one virtual CPU or one per core.
- Resizing the terminal doesn't reflow lines already printed.
- The VNC viewer only speaks raw and copy-rect updates, fine over Rui's own connection but heavy over a network.

## Development

| Path                  | What                                                                     |
| --------------------- | ------------------------------------------------------------------------ |
| `crates/rui-core/`    | The library: images, kernels, archives, seeds                            |
| `crates/rui/`         | The command-line tool                                                    |
| `crates/rui-jni/`     | The library and pseudo-terminals for the Android app                     |
| `crates/rui-agent/`   | The guest agent                                                          |
| `android/app/`        | The Android app                                                          |
| `android/avf-stubs/`  | Compile-only stubs of AVF's system API                                   |
| `tools/`              | `theme.ts`, `unify.ts`, and `rui-client.ts`, a stand-in for Asahi        |
| `docs/`               | Design notes and screenshots                                             |

You need Node 24 and pnpm, a Rust toolchain with the targets `aarch64-linux-android`, `armv7-linux-androideabi`,
`x86_64-linux-android` and `aarch64-unknown-linux-musl`, `cargo-ndk`, and the Android SDK with platform 37 and NDK
30.

```bash
pnpm install && pnpm unify && pnpm lint && pnpm typecheck
```

```bash
cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
```

```bash
cd android && ./gradlew spotlessApply :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

To release: bump `VERSION` and the Cargo workspace version, update [What has been tested](#what-has-been-tested),
then tag `v<VERSION>` and push both. CI builds, signs and publishes.

## Under the hood

- [docs/avf.md](docs/avf.md): AVF's API on Android 17, its permissions, and how Rui builds a VM
- [docs/agent.md](docs/agent.md): the guest agent and its protocol
- [docs/archive.md](docs/archive.md): the `.ruivm` format
- [docs/asahi-integration.md](docs/asahi-integration.md): the helper socket, links, and the plan for Asahi and Airi
- [docs/roadmap.md](docs/roadmap.md): the versions, and what's next

## License and credits

Rui is a [Wonderhoy Labs](https://github.com/wonderhoy-labs) project, released under the
[Apache License 2.0](LICENSE). It isn't affiliated with Google. Android and Pixel are trademarks of Google LLC.

It stands on the work of these projects:
- [Android's Virtualization Framework and crosvm](https://source.android.com/docs/core/virtualization) (Apache 2.0),
  which run the VMs
- [Jetpack Compose and AndroidX](https://developer.android.com/jetpack) (Apache 2.0), for the app
- [Shizuku](https://github.com/RikkaApps/Shizuku) (Apache 2.0), for granting permissions without a computer
- [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) (Apache 2.0), for AVF's custom-image
  API
- [JSch, mwiede's fork](https://github.com/mwiede/jsch) (BSD), the SSH client
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) and
  [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) (Apache 2.0)
- [ext4-view](https://github.com/nicholasbishop/ext4-view-rs) (Apache 2.0 or MIT), which reads guest filesystems
- [rust-fatfs](https://github.com/rafalh/rust-fatfs) (MIT), for cloud-init seeds and EFI partitions
- [ruzstd](https://github.com/KillingSpark/zstd-rs) (MIT), for zstd-compressed kernels and qcow2 clusters
- [flate2](https://github.com/rust-lang/flate2-rs), [tar](https://github.com/alexcrichton/tar-rs), [sha2](https://github.com/RustCrypto/hashes),
  [serde](https://github.com/serde-rs/serde), [jni](https://github.com/jni-rs/jni-rs) and
  [libc](https://github.com/rust-lang/libc) (MIT or Apache 2.0)
- [cloud-init](https://github.com/canonical/cloud-init) (Apache 2.0 or GPL 3), which sets up guests on first boot
- [Debian](https://www.debian.org), [Ubuntu](https://ubuntu.com) and [Fedora](https://fedoraproject.org), whose
  official cloud images the catalog downloads
- [Biome](https://github.com/biomejs/biome) (MIT or Apache 2.0) and [Spotless](https://github.com/diffplug/spotless)
  (Apache 2.0), which keep everything formatted

Rui is named for Kamishiro Rui of Wonderlands×Showtime, from Project SEKAI COLORFUL STAGE! feat. Hatsune Miku.
Wonderhoy Labs is a fan project, not affiliated with SEGA, Colorful Palette or Crypton Future Media, who own Project
SEKAI and its characters. The Apache License doesn't cover the Rui name or logo.
