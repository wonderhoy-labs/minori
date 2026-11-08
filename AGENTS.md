# AGENTS.md

Read the Wonderhoy Labs unification skill, [.claude/skills/wonderhoy-unification/SKILL.md](.claude/skills/wonderhoy-unification/SKILL.md),
before changing anything. It's the same in every Wonderhoy Labs repository. This file only adds what's particular
to this one.

## What this is

The template every new Wonderhoy Labs project starts from. It has one of everything a project might need: an
Android app, a web app, a Rust command-line tool, the shared tools, CI and the unification skill. A new project
keeps the parts it needs and deletes the rest.

When the guideline changes, it changes here first. Then the shared files are copied into every other
repository in the same change (the list is in the skill, under "Changing the guideline").

## Layout

| Path                                    | What                                                                     |
| --------------------------------------- | ------------------------------------------------------------------------ |
| `android/`                              | A one-screen Compose app with the shared `BrandHeader` and `BrandFooter` |
| `web/`                                  | A one-page React and mdui app with the shared `AppBar` and `Footer`      |
| `crates/template/`                      | A command-line tool that prints its version                              |
| `tools/theme.ts`                        | Makes the colour scheme from `package.json`                              |
| `tools/unify.ts`                        | Checks a repository against the guideline                                |
| `.claude/skills/wonderhoy-unification/` | The guideline itself                                                     |

## Build, test and format

```bash
pnpm install
pnpm format && pnpm lint && pnpm typecheck && pnpm unify
pnpm --filter ./web test && pnpm --filter ./web build
cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
cd android && ./gradlew spotlessApply :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

## Things to know

- The template has no logo, launcher icon or favicon on purpose. A project adds the maintainer's own art.
  Never make one.
- It's named for Hatsune Miku, who appears in every unit's SEKAI. A new project replaces her with its own
  character.
- Its CI exercises every shared workflow, so a change to wonderhoy-labs/.github should keep this repository green.

## Exceptions to the guideline

- No `docs/logo.webp`, and so no logo at the top of the README. `pnpm unify` allows this only here.
