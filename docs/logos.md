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
| `logo_xfce.webp`        | Xfce                          | [Xfce logo.svg](https://commons.wikimedia.org/wiki/File:Xfce_logo.svg)                                           | LGPL, the Xfce Team                                         |
| `logo_gnome.webp`       | GNOME, The GNOME Foundation   | [Gnomelogo-footprint.svg](https://commons.wikimedia.org/wiki/File:Gnomelogo-footprint.svg)                       | LGPL; a trademark of The GNOME Foundation                   |
| `logo_firefox.webp`     | Firefox, Mozilla              | [Firefox logo, 2019.svg](https://commons.wikimedia.org/wiki/File:Firefox_logo,_2019.svg)                         | MPL 2.0, Mozilla Corporation; a trademark of Mozilla        |
| `logo_libreoffice.webp` | LibreOffice, The Document Foundation | [LibreOffice icon 3.3.1 48 px.svg](https://commons.wikimedia.org/wiki/File:LibreOffice_icon_3.3.1_48_px.svg) | CC BY-SA 3.0, The Document Foundation                    |
| `logo_gimp.webp`        | GIMP                          | [The GIMP icon - gnome.svg](https://commons.wikimedia.org/wiki/File:The_GIMP_icon_-_gnome.svg)                   | GPL, Tuomas Kuosmanen                                       |
| `logo_inkscape.webp`    | Inkscape                      | [Inkscape Logo.svg](https://commons.wikimedia.org/wiki/File:Inkscape_Logo.svg)                                   | CC BY-SA 3.0, Andrew Michael Fitzsimon                      |
| `logo_krita.webp`       | Krita, KDE                    | [Calligrakrita-base.svg](https://commons.wikimedia.org/wiki/File:Calligrakrita-base.svg)                         | CC BY-SA 4.0, Wolthera van Hövell tot Westerflier and Timothée Giet |
| `logo_thunderbird.webp` | Thunderbird, MZLA Technologies | [Thunderbird 2023 icon.svg](https://commons.wikimedia.org/wiki/File:Thunderbird_2023_icon.svg)                 | MPL 2.0, Jon Hicks and MZLA Technologies Corporation        |
| `logo_vlc.webp`         | VLC, VideoLAN                 | [VLC Icon.svg](https://commons.wikimedia.org/wiki/File:VLC_Icon.svg)                                             | GPL, Richard C. G. Øiestad                                  |
| `logo_blender.webp`     | Blender, the Blender Foundation | [Blender logo no text.svg](https://commons.wikimedia.org/wiki/File:Blender_logo_no_text.svg)                   | Public domain on Commons; a trademark of the Blender Foundation |

Termux has no file here: its item shows the icon of the Termux app installed on the phone, or a terminal symbol when
it isn't installed.

These are trademarks of their owners. Minori uses each only to say which software an item installs, and isn't
affiliated with or endorsed by any of them. They aren't covered by Minori's Apache 2.0 licence. To add an item to the
store, add its project's own published logo here the same way; if it has none that can be used, the item gets the
terminal symbol.
