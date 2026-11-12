# Roadmap

Minori was planned in five parts. All five shipped together in 0.1.0; this says how far each one got.

| Part | What                                                                           | State |
| ---- | ------------------------------------------------------------------------------ | ----- |
| 1    | The hypervisor: setup, VMs, disks, ports, console, SSH, VNC, import and export | Released. Runs on a Pixel 10 Pro under AVF; guests reach the internet through the phone |
| 2    | Distro catalog                                                                 | Released: Debian 13, Ubuntu 26.04 and 24.04, Fedora 43 from official URLs with checksums. All ran on the Pixel |
| 3    | Desktop and app templates                                                      | Released: KDE Plasma, Xfce and GNOME, and ten apps, on Debian 13. KDE ran on the Pixel; Xfce, GNOME and GIMP in Debian 13 containers |
| 4    | Asahi and Airi                                                                 | Released: the helper, library, Airi setup and `minori://vm/<id>/app/<desktop id>`. Asahi picks machines, the Pixel's screen and single apps. See [asahi-integration.md](asahi-integration.md) |
| 5    | Termux backend                                                                 | Released. Debian, Ubuntu, Alpine, Fedora and Arch Linux ARM ran on the Pixel, and an Xfce desktop in Ubuntu |

## Next, in order

1. **Try the rest on a phone**: the app items, SSH and RDP apps on the phone itself, auto-start at boot.
2. **Fedora 44 and later.** Their cloud images keep the kernel only in the Btrfs root, so Minori needs to read Btrfs
   as it reads ext4.
3. **ISO installs.** Minori knows where Debian, Ubuntu, Fedora and Alpine ISOs keep their installer kernels (only
   Alpine's has been booted, under QEMU). After an install, Minori should notice the installed system and switch the
   boot disk to it.
4. **Arch Linux.** Arch Linux ARM ships a root filesystem archive, so Minori needs to build an ext4 disk from it.
5. **Termux, the rest.** Published ports for Termux machines.

## Not planned

- Windows guests. AVF has no firmware, TPM or display that Windows could boot with on this hardware, and Microsoft
  offers no image Minori could fetch.
- x86-64 guests. A phone is 64-bit Arm, and Minori doesn't emulate other CPUs.
- Anything for hiding traffic or getting around network rules.
