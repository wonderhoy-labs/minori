# AGENTS.md

Read the Wonderhoy Labs unification skill, [.claude/skills/wonderhoy-unification/SKILL.md](.claude/skills/wonderhoy-unification/SKILL.md),
before changing anything. It's the same in every Wonderhoy Labs repository. This file only adds what's particular
to Minori.

## What Minori is

A hypervisor app for Pixel phones: create, start, stop, edit, import and export virtual machines that run your
own 64-bit Arm Linux through Android's Virtualization Framework (AVF), with published ports and a firewall, and a
console, SSH and VNC one tap away. A Termux backend keeps proot userlands on phones without AVF, and a debug-only
demo backend stands in for a hypervisor on the emulator. Named for Hanasato Minori of MORE MORE JUMP!. It was
called Rui while it was first built.

## Layout

| Path                  | What                                                                                                     |
| --------------------- | -------------------------------------------------------------------------------------------------------- |
| `crates/minori-core/`    | The library: image inspection, qcow2 and sparse copies, kernel extraction, `.minorivm` archives, cloud-init seeds |
| `crates/minori/`         | A command-line tool with the same image tools, for preparing images on a computer                       |
| `crates/minori-jni/`     | `minori-core` and pseudo-terminals for the app (`com.wonderhoylabs.minori.core.Native`)                        |
| `crates/minori-agent/`   | The guest agent: vsock to guest ports, UDP, port list, poweroff. Static aarch64 musl                     |
| `android/app/`        | The Compose app. `backend/` is the hypervisors, `service/` the runtime, `net/` ports and the firewall    |
| `android/avf-stubs/`  | Compile-only stubs of `android.system.virtualmachine`, copied from Android 17's jar                      |
| `tools/minori-client.ts` | A stand-in for Asahi that tests the helper socket                                                        |
| `docs/`               | `avf.md`, `agent.md`, `archive.md`, `asahi-integration.md`, `roadmap.md`, screenshots                    |

## Build, test and format

```bash
pnpm install && pnpm unify && pnpm lint && pnpm typecheck
cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
cd android && ./gradlew spotlessApply :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

The Android build runs `cargo ndk` for `minori-jni` and builds `minori-agent` for `aarch64-unknown-linux-musl` with
`rust-lld`, so it needs those Rust targets (`rustup target add aarch64-linux-android armv7-linux-androideabi
x86_64-linux-android aarch64-unknown-linux-musl`). `-Pminori.abis=arm64-v8a` builds the app for one ABI.

## Things to know

- **AVF can't run on the emulator** on a Mac: there's no nested virtualization, so no `/dev/kvm` and no
  `android.software.virtualization_framework`. Test UI and flows on the emulator with the demo backend; only a
  phone can test the AVF backend. Never claim a guest booted under AVF unless it did, on a named device.
- **Custom kernels are hidden API on Android 17.** Minori exempts `Landroid/system/virtualmachine/` for its own
  process with LSPosed's HiddenApiBypass. Everything AVF-specific is in `backend/AvfHypervisor.kt`;
  [docs/avf.md](docs/avf.md) has the API, the permissions and what was checked.
- **Granting `MANAGE_VIRTUAL_MACHINE` kills Minori** (it changes the app's gids). Grant it last; the Shizuku path
  reopens Minori on its setup screen afterwards.
- **Real guests for testing.** Debug builds take `--es dev_guest_console host:port` and `--es dev_guest_agent
  host:port`, kept until changed, which point the demo backend at a TCP serial console and a `minori-agent --tcp`. A
  Debian 13 arm64 cloud image under QEMU in Docker, with `-serial tcp::4555,server=on,wait=off` and a hostfwd to
  the agent, plus `adb reverse`, gives the emulator a real guest for the console, SSH, published ports, the
  firewall and VNC. QEMU's UART is `ttyAMA0`, not AVF's `ttyS0`; say which you used.
- **Never compile anything inside a guest on a phone.** It froze Android's Terminal VM for Airi. Cross-compile
  `minori-agent` on the computer; the app carries it.
- **Loopback means 127.0.0.1.** `InetAddress.getLoopbackAddress()` is `::1` on Android; published ports bind
  `127.0.0.1`, and the firewall treats `::1` as `127.0.0.1`.
- **Terminal input is `TYPE_NULL`** (`terminal/TerminalInput.kt`). A hidden text field doubles letters as the
  keyboard composes words; don't go back to one.
- Emulator tests use a separate AVD, `Minori_Test_API_37` (the same Android 17 image as `Medium_Phone_API_37.0`),
  because other sessions use the shared one.
- Disk images live in app-private storage. Catalog downloads come only from each project's own servers, checked
  against its published checksums.
- No signing key exists yet. CI signs with `MINORI_KEYSTORE_BASE64`, `MINORI_KEYSTORE_PASSWORD`, `MINORI_KEY_ALIAS` and
  `MINORI_KEY_PASSWORD` once the maintainer makes them; never replace that key afterwards.

## Exceptions to the guideline

- No footer on the main screen: the maintainer asked for it to go. The "A Wonderhoy Labs project, named for…" line
  is in Settings, under About.
- `reference/brand.md` has Minori's row before wonderhoy-labs/template does. The same change is the local branch
  `claude/add-minori`, based on the template's `main`, waiting to be pushed there.
