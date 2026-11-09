# Roadmap

Rui is planned in versions, each shippable. This says what each one is and how much of it exists.

| Version | What                                               | State                                                                                       |
| ------- | -------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| 0.1.0   | The hypervisor: setup, VMs, disks, ports, console, SSH, VNC, import and export | Built. Tested on the emulator with the demo backend and a QEMU guest; AVF itself never run |
| 0.2.0   | Distro catalog                                     | Built: Debian 13, Ubuntu 24.04, Fedora 43 from official URLs with checksums; Arch and Windows explained. Downloads never run in the app |
| 0.3.0   | Desktop and app templates                          | Built as first-boot provisioners (KDE Plasma, Chromium). Never run                          |
| 0.4.0   | Asahi and Airi                                     | Rui's helper and links built and tested; Asahi's side not built. See [asahi-integration.md](asahi-integration.md) |
| 0.5.0   | Termux backend                                     | Built behind the same interface. Debian tested end to end on the emulator; other distributions not run |

## Next, in order

1. **Run it on a phone with AVF.** Boot the Debian template on a Pixel, check the console on AVF's UART, the agent
   over vsock, published ports and `useNetwork`, and write down what Android 17 allows.
2. **Guest internet without AVF's network.** `rui-agent` can carry packets over vsock from a TUN device in the
   guest to a userspace NAT in the app. `saki-core`'s NAT, released as a library, already does this job for Saki.
3. **The TypeScript library and Asahi's source picker**, as [asahi-integration.md](asahi-integration.md) describes.
4. **ISO installs.** Rui knows where Debian, Ubuntu, Fedora and Alpine ISOs keep their installer kernels (only
   Alpine's has been booted, under QEMU). After an install, Rui should notice the installed system and switch the
   boot disk to it.
5. **Arch Linux.** Arch Linux ARM ships a root filesystem archive, so Rui needs to build an ext4 disk from it.
6. **Single Linux apps from Rui VMs**, with an Airi-style agent, once the rest works on a phone.
7. **Termux, the rest.** Try Ubuntu, Arch, Alpine and Fedora userlands, and published ports for them.

## Not planned

- Windows guests. AVF has no firmware, TPM or display that Windows could boot with on this hardware, and Microsoft
  offers no image Rui could fetch.
- x86-64 guests. A phone is 64-bit Arm, and Rui doesn't emulate other CPUs.
- Anything for hiding traffic or getting around network rules.
