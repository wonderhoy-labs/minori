# Roadmap

Minori was planned in five parts. All five shipped together in 0.1.0; this says how far each one got.

| Part | What                                                                           | State |
| ---- | ------------------------------------------------------------------------------ | ----- |
| 1    | The hypervisor: setup, VMs, disks, ports, console, SSH, VNC, import and export | Released. Runs on a Pixel 10 Pro under AVF; guests reach the internet through the phone |
| 2    | Distro catalog                                                                 | Released: Debian 13, Ubuntu 24.04, Fedora 43 from official URLs with checksums. Debian ran on the Pixel; Ubuntu and Fedora never ran |
| 3    | Desktop and app templates                                                      | Released: KDE Plasma and Chromium, on Debian 13. KDE ran on the Pixel |
| 4    | Asahi and Airi                                                                 | Minori's helper, library and Airi setup released; Asahi's Minori source built and tested on the emulator, not released yet. See [asahi-integration.md](asahi-integration.md) |
| 5    | Termux backend                                                                 | Released. Debian tested end to end on the emulator; other distributions not run |

## Next, in order

1. **Asahi's other sources**: the Pixel's own screen, and one Linux app from Airi or a Minori machine.
2. **More of the store**: XFCE and GNOME on Debian 13, and single desktop apps such as Firefox, LibreOffice and GIMP.
3. **Try the rest on a phone**: Ubuntu and Fedora, SSH and RDP apps on the phone itself, auto-start at boot.
4. **ISO installs.** Minori knows where Debian, Ubuntu, Fedora and Alpine ISOs keep their installer kernels (only
   Alpine's has been booted, under QEMU). After an install, Minori should notice the installed system and switch the
   boot disk to it.
5. **Arch Linux.** Arch Linux ARM ships a root filesystem archive, so Minori needs to build an ext4 disk from it.
6. **Termux, the rest.** Try Ubuntu, Arch, Alpine and Fedora userlands, and published ports for them.

## Not planned

- Windows guests. AVF has no firmware, TPM or display that Windows could boot with on this hardware, and Microsoft
  offers no image Minori could fetch.
- x86-64 guests. A phone is 64-bit Arm, and Minori doesn't emulate other CPUs.
- Anything for hiding traffic or getting around network rules.
