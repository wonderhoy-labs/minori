# Minori and Android's Virtualization Framework

What Minori relies on in AVF, what was checked and how, and what hasn't been seen to work on a phone yet. Everything
AVF-specific in the app is in `android/app/src/main/kotlin/com/wonderhoylabs/minori/backend/AvfHypervisor.kt`.

## The API

AVF's Java API is `android.system.virtualmachine`, in the `com.android.virt` module's
`framework-virtualization.jar`. It is `@SystemApi`, so it isn't in the public SDK. Minori compiles against stubs in
`android/avf-stubs` (`compileOnly`), whose signatures and constant values were copied from the jar on Android 17
(`CE2A.260420.051.A2`, pulled from the emulator and read with `dexdump`).

The jar marks each member with its hidden API list. On Android 17:

| Member                                                                                            | List             |
| ------------------------------------------------------------------------------------------------- | ---------------- |
| `VirtualMachineManager.create`, `get`, `getOrCreate`, `delete`, `getCapabilities`                 | SDK (allowed)    |
| `VirtualMachineConfig.Builder(Context)`, `setMemoryBytes`, `setCpuTopology`, `setProtectedVm`, `setDebugLevel`, `setVmOutputCaptured` | SDK (allowed)    |
| `VirtualMachine.run`, `stop`, `connectVsock`, `getConsoleOutput`, `getLogOutput`, `setCallback`   | SDK (allowed)    |
| `VirtualMachineConfig.Builder.setCustomImageConfig`, `setVmConsoleInputSupported`, `setConsoleInputDevice` | blocked |
| All of `VirtualMachineCustomImageConfig`                                                          | blocked          |
| `VirtualMachine.getConsoleInput`                                                                  | blocked          |

The allowed part only starts Microdroid payloads. Booting your own kernel and disks, which is the whole point of
Minori, needs the blocked part, as Android's own Terminal app uses it. Minori calls
`HiddenApiBypass.addHiddenApiExemptions("Landroid/system/virtualmachine/")` (LSPosed's library) at startup, which
exempts only that package and only in Minori's process, then checks that `VirtualMachineCustomImageConfig$Builder`
really is reachable. On the Android 17 emulator image it is: Setup shows "Minori can boot your own kernels: Yes".

## Permissions

| Permission                                      | Protection level                       | Why Minori needs it                                   |
| ----------------------------------------------- | -------------------------------------- | -------------------------------------------------- |
| `android.permission.MANAGE_VIRTUAL_MACHINE`     | `signature\|development\|preinstalled` | To use `VirtualMachineManager` at all              |
| `android.permission.USE_CUSTOM_VIRTUAL_MACHINE` | `signature\|development`               | To boot a kernel and disks that aren't Microdroid  |

Both are `development` permissions, so `pm grant` from adb, or from Shizuku's shell-user process, can grant them.
Minori does both: Setup grants them through a Shizuku user service, or shows the two `adb shell pm grant` commands.

Granting `MANAGE_VIRTUAL_MACHINE` changes Minori's supplementary groups, so Android kills Minori at that moment
(`ActivityManager: Killing … permission grant or revoke changed gids`). Minori grants it last. The Shizuku path runs
the grants in a detached shell that reopens Minori on its Setup screen afterwards, so the user sees the result.

## How Minori builds a VM

- **Kernel and initrd.** crosvm on Android starts a Linux kernel directly; there is no PC firmware. `minori-core`
  finds the guest's newest kernel in `/boot` of its ext4 root or boot partition (or at known paths on installer
  ISOs), unpacks gzip and EFI zboot kernels to a raw arm64 `Image`, checks the `ARM\x64` magic, and writes
  `boot/kernel` and `boot/initrd` beside the VM's disks.
- **Command line.** The image's own GRUB or Boot Loader Spec arguments when it has them, otherwise
  `root=PARTUUID=…` from GPT, plus `console=hvc0 console=ttyS0`. AVF's interactive console is the first UART
  (`ttyS0`) and its log is a virtio console (`hvc0`); the last `console=` gets the login prompt.
- **Disks.** Raw, sparse files, the boot disk first so it's `/dev/vda`. qcow2 is converted when imported (Minori's
  converter's output was byte-identical to `qemu-img convert` for Debian 13's cloud image). ISOs and the cloud-init
  seed are attached read-only.
- **Config.** `setProtectedVm(false)`, `setDebugLevel(DEBUG_LEVEL_FULL)` (AVF only hands a debuggable VM's console
  to its app), `setVmOutputCaptured(true)`, `setVmConsoleInputSupported(true)`, and `CPU_TOPOLOGY_ONE_CPU` or
  `CPU_TOPOLOGY_MATCH_HOST`. AVF's Java API has no other CPU counts, so neither does Minori.
- **Instance.** An unprotected custom VM keeps nothing in its AVF instance, so Minori deletes and recreates it with the
  current settings at every start, under the name `minori-<id>`.

## Networking

- **Published ports** go to a listening socket in Minori's own process, then over vsock (port 5741) to `minori-agent` in
  the guest, which connects to the guest port on its loopback. No root, no iptables, and no network between the
  phone and the guest. See [agent.md](agent.md).
- **Internet for the guest** is `useNetwork(true)`, AVF's own tethered network. Whether an app other than the
  Terminal may use it depends on the Android build; Minori offers the switch and says so.

## Seen to work, and not

| Part                                                                 | Status                                                                        |
| -------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| Permission definitions, `pm grant`, the kill on grant                | Seen on the Android 17 emulator                                               |
| Hidden API exemption for `android.system.virtualmachine`             | Seen on the Android 17 emulator (user build)                                  |
| Kernel extraction, command line and seed for Debian 13               | Booted under QEMU's `virt` machine with `console=ttyAMA0`, not under AVF       |
| `minori-agent` over TCP                                                 | Seen in a real Debian 13 arm64 guest under QEMU                               |
| `VirtualMachineManager.create` and `run` with a custom image         | **Never run.** No device with AVF was available.                               |
| Console over AVF's UART, vsock to `minori-agent`, `useNetwork`          | **Never run.**                                                                 |
