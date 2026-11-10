# Logos in the store

The store shows each project's own logo, so Debian looks like Debian. They're the projects' published logos, taken
from Wikimedia Commons at 330 pixels and saved as WebP, in
[`android/app/src/main/res/drawable-nodpi/`](../android/app/src/main/res/drawable-nodpi/). Nothing about them is drawn
or changed by Minori, apart from cropping Debian's to the swirl.

| File                 | Whose                         | Taken from                                                                                                       | Licence                                                    |
| -------------------- | ----------------------------- | ---------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------- |
| `logo_debian.webp`   | Debian, Software in the Public Interest | [Debian-OpenLogo.svg](https://commons.wikimedia.org/wiki/File:Debian-OpenLogo.svg), cropped to the swirl | Debian Open Use Logo: CC BY-SA 3.0 or LGPL 3. © 1999 Software in the Public Interest |
| `logo_ubuntu.webp`   | Ubuntu, Canonical             | [Logo-ubuntu cof-orange-hex.svg](https://commons.wikimedia.org/wiki/File:Logo-ubuntu_cof-orange-hex.svg)         | Public domain on Commons; a trademark of Canonical          |
| `logo_fedora.webp`   | Fedora, Red Hat               | [Fedora icon (2021).svg](https://commons.wikimedia.org/wiki/File:Fedora_icon_(2021).svg)                         | Public domain on Commons; a trademark of Red Hat            |
| `logo_kde.webp`      | KDE Plasma, KDE e.V.          | [Plasma coloured logo.svg](https://commons.wikimedia.org/wiki/File:Plasma_coloured_logo.svg)                     | LGPL; a trademark of KDE e.V.                               |
| `logo_chromium.webp` | Chromium, The Chromium Authors | [Chromium Material Icon.png](https://commons.wikimedia.org/wiki/File:Chromium_Material_Icon.png)                | CC BY 2.5, The Chromium Authors                             |

Termux has no file here: its item shows the icon of the Termux app installed on the phone, or a terminal symbol when
it isn't installed.

These are trademarks of their owners. Minori uses each only to say which software an item installs, and isn't
affiliated with or endorsed by any of them. They aren't covered by Minori's Apache 2.0 licence. To add an item to the
store, add its project's own published logo here the same way; if it has none that can be used, the item gets the
terminal symbol.
