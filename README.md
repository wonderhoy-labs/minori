<h1 align="center">Template</h1>

<p align="center">The starting point for a new Wonderhoy Labs project.</p>

<p align="center">
  <a href="https://github.com/wonderhoy-labs/template/actions/workflows/ci.yml"
    ><img src="https://github.com/wonderhoy-labs/template/actions/workflows/ci.yml/badge.svg" alt="CI"></a
  >
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License: Apache 2.0"></a>
  <a href="#license-and-credits"
    ><img
      src="https://img.shields.io/badge/Hatsune%20Miku-VIRTUAL%20SINGER-33CCBB"
      alt="Named for Hatsune Miku of VIRTUAL SINGER"
    ></a
  >
</p>

Every Wonderhoy Labs project starts here. The template has one of everything a project might have, already
following the [Wonderhoy Labs guideline](.claude/skills/wonderhoy-unification/SKILL.md): an Android app in Jetpack
Compose and Material 3, a web app in React and mdui, a Rust command-line tool, CI that builds all three for every
platform, and the skill that keeps coding agents on the same page.

Keep the parts your project needs and delete the rest.

## What you need

- Node 24 or newer, and pnpm (`corepack enable` installs the version in `package.json`)
- For the Android app: Android Studio, or JDK 17 and the Android SDK with platform 37
- For Rust: a stable toolchain from [rustup](https://rustup.rs)

## Use it

### Start a project

1. Click **Use this template** on GitHub, and name the repository after a Project SEKAI character whose name
   isn't taken. The list is in [the brand notes](.claude/skills/wonderhoy-unification/reference/brand.md).
2. In `package.json`, set `name` to the lowercase id, and fill in the `wonderhoy` block: the name, a one-line
   tagline, the character, their unit and their image colour from [Sekaipedia](https://www.sekaipedia.org).
3. Rename `template` to the id everywhere: the Android namespace and its folders, the crate, the web package,
   `TEMPLATE_` in the signing variables, and the `title` in `.github/workflows/ci.yml`.
4. Run `pnpm install` and `pnpm theme`. The colours, the header and the footer follow.
5. Delete what the project doesn't have (`android/`, `web/`, `crates/`) and its jobs in `ci.yml`.
6. Add the project's logo as `docs/logo.webp`, and its launcher icon and favicon.
7. Rewrite this README and `AGENTS.md` for the project. `pnpm unify` says what's still missing.

### Check it

```bash
pnpm unify
```

## What has been tested

The template's own CI builds and tests all three parts on every push. The web app has been opened in a browser,
and the Android app built with `assembleRelease`. Neither has been run on a phone.

## How it works

### Colours

`tools/theme.ts` feeds the character's image colour to Material's colour utilities, and writes the light and dark
schemes for mdui (`web/src/ui/theme.css`) and Compose (`android/.../ui/Theme.kt`). Change the colour in
`package.json` and run `pnpm theme`. Nothing else needs editing.

### The guideline

`tools/unify.ts` checks the layout, versions, README, colours and license against the guideline. CI runs it, with
`--remote` to also compare the shared files with this repository.

### CI

`.github/workflows/ci.yml` calls the shared workflows in [wonderhoy-labs/.github](https://github.com/wonderhoy-labs/.github):
checks, Rust on every OS and CPU, the Android APK, the web app on GitHub Pages, and a release for every `v*` tag.

## Security and privacy

The template collects nothing and talks to nothing. The apps you build from it are yours to describe here.

## Troubleshooting

**`pnpm unify` says a generated file is out of date.** Run `pnpm theme` and commit the result.

**Spotless fails on Kotlin.** Run `./gradlew spotlessApply` in `android/`. It formats with tabs, from the
`.editorconfig` at the top.

## Known issues

- The template has no logo or launcher icon, so the Android app shows the system's default icon.

## Development

| Path                                    | What                                               |
| --------------------------------------- | -------------------------------------------------- |
| `android/`                              | The Android app                                    |
| `web/`                                  | The web app                                        |
| `crates/template/`                      | The command-line tool                              |
| `tools/`                                | `theme.ts` and `unify.ts`, shared by every project |
| `.claude/skills/wonderhoy-unification/` | The guideline                                      |

```bash
pnpm install
pnpm format
pnpm lint
```

## License and credits

Template is a [Wonderhoy Labs](https://github.com/wonderhoy-labs) project, released under the
[Apache License 2.0](LICENSE).

It stands on the work of these projects:
- [mdui](https://github.com/zdhxiong/mdui) (MIT), for the web app's Material 3 components
- [Material Color Utilities](https://github.com/material-foundation/material-color-utilities) (Apache 2.0), which
  makes the colours
- [React](https://github.com/facebook/react) (MIT) and [Vite](https://github.com/vitejs/vite) (MIT)
- [Roboto Flex](https://github.com/googlefonts/roboto-flex) (OFL 1.1), through
  [Fontsource](https://github.com/fontsource/fontsource)
- [Jetpack Compose and AndroidX](https://developer.android.com/jetpack) (Apache 2.0), for the Android app
- [Biome](https://github.com/biomejs/biome) (MIT or Apache 2.0) and [Spotless](https://github.com/diffplug/spotless)
  (Apache 2.0), which keep everything formatted

Template is named for Hatsune Miku of VIRTUAL SINGER, from Project SEKAI COLORFUL STAGE! feat. Hatsune Miku.
Wonderhoy Labs is a fan project, not affiliated with SEGA, Colorful Palette or Crypton Future Media, who own
Project SEKAI and its characters. The Apache License doesn't cover the Template name.
