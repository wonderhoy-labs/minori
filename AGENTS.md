# AGENTS.md

Read the Wonderhoy Labs unification skill, [.claude/skills/wonderhoy-unification/SKILL.md](.claude/skills/wonderhoy-unification/SKILL.md),
before changing anything. It's the same in every Wonderhoy Labs repository. This file only adds what's particular
to Rui.

## What Rui is

A hypervisor app for Pixel phones: create, start, stop, edit, import and export virtual machines that run your
own 64-bit Arm Linux through Android's Virtualization Framework (AVF), with published ports and a firewall, and a
console, SSH and VNC one tap away. A Termux backend keeps proot userlands on phones without AVF, and a debug-only
demo backend stands in for a hypervisor on the emulator. Named for Kamishiro Rui of Wonderlands×Showtime. The name
and logo are placeholders: the maintainer plans to rebrand, so keep the name in the brand files, not scattered.

## Layout

| Path                  | What                                                                                                     |
| --------------------- | -------------------------------------------------------------------------------------------------------- |
| `crates/rui-core/`    | The library: image inspection, qcow2 and sparse copies, kernel extraction, `.ruivm` archives, cloud-init seeds |
| `crates/rui/`         | A command-line tool with the same image tools, for preparing images on a computer                       |
| `crates/rui-jni/`     | `rui-core` and pseudo-terminals for the app (`com.wonderhoylabs.rui.core.Native`)                        |
| `crates/rui-agent/`   | The guest agent: vsock to guest ports, UDP, port list, poweroff. Static aarch64 musl                     |
| `android/app/`        | The Compose app. `backend/` is the hypervisors, `service/` the runtime, `net/` ports and the firewall    |
| `android/avf-stubs/`  | Compile-only stubs of `android.system.virtualmachine`, copied from Android 17's jar                      |
| `tools/rui-client.ts` | A stand-in for Asahi that tests the helper socket                                                        |
| `docs/`               | `avf.md`, `agent.md`, `archive.md`, `asahi-integration.md`, `roadmap.md`, screenshots                    |

## Build, test and format

```bash
pnpm install && pnpm unify && pnpm lint && pnpm typecheck
cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
cd android && ./gradlew spotlessApply :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

The Android build runs `cargo ndk` for `rui-jni` and builds `rui-agent` for `aarch64-unknown-linux-musl` with
`rust-lld`, so it needs those Rust targets (`rustup target add aarch64-linux-android armv7-linux-androideabi
x86_64-linux-android aarch64-unknown-linux-musl`). `-Prui.abis=arm64-v8a` builds the app for one ABI.

## Things to know

- **AVF can't run on the emulator** on a Mac: there's no nested virtualization, so no `/dev/kvm` and no
  `android.software.virtualization_framework`. Test UI and flows on the emulator with the demo backend; only a
  phone can test the AVF backend. Never claim a guest booted under AVF unless it did, on a named device.
- **Custom kernels are hidden API on Android 17.** Rui exempts `Landroid/system/virtualmachine/` for its own
  process with LSPosed's HiddenApiBypass. Everything AVF-specific is in `backend/AvfHypervisor.kt`;
  [docs/avf.md](docs/avf.md) has the API, the permissions and what was checked.
- **Granting `MANAGE_VIRTUAL_MACHINE` kills Rui** (it changes the app's gids). Grant it last; the Shizuku path
  reopens Rui on its setup screen afterwards.
- **Real guests for testing.** Debug builds take `--es dev_guest_console host:port` and `--es dev_guest_agent
  host:port`, kept until changed, which point the demo backend at a TCP serial console and a `rui-agent --tcp`. A
  Debian 13 arm64 cloud image under QEMU in Docker, with `-serial tcp::4555,server=on,wait=off` and a hostfwd to
  the agent, plus `adb reverse`, gives the emulator a real guest for the console, SSH, published ports, the
  firewall and VNC. QEMU's UART is `ttyAMA0`, not AVF's `ttyS0`; say which you used.
- **Never compile anything inside a guest on a phone.** It froze Android's Terminal VM for Airi. Cross-compile
  `rui-agent` on the computer; the app carries it.
- **Loopback means 127.0.0.1.** `InetAddress.getLoopbackAddress()` is `::1` on Android; published ports bind
  `127.0.0.1`, and the firewall treats `::1` as `127.0.0.1`.
- **Terminal input is `TYPE_NULL`** (`terminal/TerminalInput.kt`). A hidden text field doubles letters as the
  keyboard composes words; don't go back to one.
- Emulator tests use a separate AVD, `Rui_Test_API_37` (the same Android 17 image as `Medium_Phone_API_37.0`),
  because other sessions use the shared one.
- Disk images live in app-private storage. Catalog downloads come only from each project's own servers, checked
  against its published checksums.
- No signing key exists yet. CI signs with `RUI_KEYSTORE_BASE64`, `RUI_KEYSTORE_PASSWORD`, `RUI_KEY_ALIAS` and
  `RUI_KEY_PASSWORD` once the maintainer makes them; never replace that key afterwards.

## Exceptions to the guideline

- No `docs/logo.webp` yet, and so no logo at the top of the README. The maintainer adds it with the rebrand.
- `tools/unify.ts`, `SKILL.md` and `reference/brand.md` carry a change that isn't in wonderhoy-labs/template yet:
  a project can wait for its logo (the line above), and Rui is in the brand table. The same change is the local
  branch `claude/logo-waiver`, based on the template's `main`, waiting to be pushed there.
