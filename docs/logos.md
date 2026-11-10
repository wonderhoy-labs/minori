# Logos in the store

The store shows each project's own logo, so Debian looks like Debian. Minori draws none of them: every file is the
project's published mark, converted from SVG to an Android vector drawable with its paths and colours unchanged.
They're in [`android/app/src/main/res/drawable/`](../android/app/src/main/res/drawable/).

| File                | Whose               | Taken from                                                                                                                                                        | Licence of the file                                                            |
| ------------------- | ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| `logo_debian.xml`   | Debian              | [Simple Icons](https://simpleicons.org) 16.37.0, `debian.svg`                                                                                                     | CC0 for the file; Debian's open use logo is CC BY-SA 3.0 or LGPL 3             |
| `logo_ubuntu.xml`   | Ubuntu, Canonical   | Simple Icons 16.37.0, `ubuntu.svg`                                                                                                                                | CC0 for the file                                                               |
| `logo_fedora.xml`   | Fedora, Red Hat     | Simple Icons 16.37.0, `fedora.svg`                                                                                                                                | CC0 for the file; see [Fedora's brand page](https://docs.fedoraproject.org/en-US/project/brand/) |
| `logo_kde.xml`      | KDE Plasma, KDE e.V. | Simple Icons 16.37.0, `kdeplasma.svg`                                                                                                                             | CC0 for the file                                                               |
| `logo_chromium.xml` | Chromium            | [`chrome/app/theme/chromium/product_logo.svg`](https://chromium.googlesource.com/chromium/src/+/main/chrome/app/theme/chromium/product_logo.svg) in Chromium's source | BSD 3-Clause, with Chromium                                                    |

Termux has no file here: its item shows the icon of the Termux app installed on the phone, or a terminal symbol when
it isn't installed.

These are trademarks of their owners. Minori uses each only to say which software an item installs, which is what
those projects' trademark rules allow, and isn't affiliated with or endorsed by any of them. They aren't covered by
Minori's Apache 2.0 licence. To add an item to the store, add its project's own published logo here the same way; if
it has none that can be used, the item gets the terminal symbol.
