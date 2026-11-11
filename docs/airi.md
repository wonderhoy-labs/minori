# Minori and Airi

[Airi](https://github.com/wonderhoy-labs/airi) shows Linux apps as Android apps, each in a window of its own and on the
launcher. It always knew Android's own terminal, whose agent it reaches on the phone's port 47821. From Minori 0.4.0
and Airi 0.4.0, it also knows Minori's machines, and reaches them through Minori, so Android's terminal and any number
of machines work at the same time. Minori 0.2 published a machine's port 47821 on the phone instead, so only one of
them could; Minori 0.4.0 removes that forward from machines it set up and marks them as set up instead.

This is Minori's side of the contract. Airi's is in its `docs/protocol.md`.

## Which machines

Airi's agent can run in:
- a virtual machine with Minori's guest agent (`agent` in its settings), where it listens on the guest's port 47821;
- a Termux userland with glibc (Debian, Ubuntu, Arch Linux or Fedora, not Alpine). Termux shares the phone's loopback,
  so each machine's agent listens on a port of its own there, from 30000 to 39999, worked out from the machine's ID.

## The provider

`content://com.wonderhoylabs.minori.airi`, served by `service/AiriProvider.kt`. Only Airi (`com.wonderhoylabs.airi`, as
Android reports the caller) and `adb`'s shell and root may use it; anything else gets a `SecurityException`.

| What                          | How                                                                                                                                                           |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| List the machines             | Query `machines`. Rows of `id`, `state` (`stopped`, `starting`, `running`, `stopping` or `error`, as in the helper's `list`), `airi` (1 once set up) and `name`, in that order |
| Reach the agent in a machine  | Open `machines/<id>/agent` with mode `rw`. The result is one end of a Unix stream socket, and Minori connects the other end to the agent. It fails with `FileNotFoundException` if the machine isn't set up, isn't running, or its agent doesn't answer |

```bash
adb shell content query --uri content://com.wonderhoylabs.minori.airi/machines
```

Airi speaks its own protocol over the socket, starting with its handshake, so the agent still only answers the Airi
that holds its token. Minori copies bytes both ways and nothing else.

## Intents

| Action                                            | Extras                       | What Minori does                                                                                                     |
| ------------------------------------------------- | ---------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| `com.wonderhoylabs.minori.action.START_FOR_AIRI`  | `machine`: the ID            | Starts a machine Airi is set up in, and closes again without showing anything. It's an activity because only an app on screen may start the service that keeps machines running |
| `com.wonderhoylabs.minori.action.SET_UP_AIRI`     | `machine`, `token` (64 hex)  | Opens the machine's page with Airi's setup filled in. Nothing happens until the user taps Install                   |

`minori://vm/<id or name>/app/<desktop file ID>` starts the machine, since Minori is the app on screen then, and hands
over to Airi's `airi://minori/<id>/<desktop file ID>`, which waits for the agent. With an Airi older than 0.4.0 it says
to update Airi instead.

## Setting it up

The machine's page has "Apps on your launcher". It installs Airi's agent, taken from the installed Airi app so the two
always match, over SSH:
- in a virtual machine, with Airi's own `install.sh`, which makes a systemd user service;
- in a Termux userland, which has no systemd, Minori installs the agent's libraries with the distribution's package
  manager, the agent in `~/.local/bin`, the token in `~/.config/airi/token`, and its `minori-airi` loop
  (`android/app/src/main/assets/minori-airi.sh`). Minori starts the loop with the machine. It runs the agent on the
  machine's port, again after Airi upgrades it, and ends once the machine's SSH server is gone; stopping the machine
  stops both.

The token comes from Airi: **Set up in Minori** in Airi sends `SET_UP_AIRI`, or the user pastes the command Airi's own
setup copies.
