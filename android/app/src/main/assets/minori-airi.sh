#!/bin/sh
# Keeps Airi's agent running in a Termux machine, which has no systemd to do it. Minori installs this next to the agent
# and starts it with the machine: `minori-airi <agent port> <SSH port>`. It runs the agent again when the agent exits,
# as it does after Airi upgrades it, and stops once the machine's SSH server is gone, which is how Minori stops a
# Termux machine.
agent_port=${1:?agent port}
ssh_port=${2:?SSH port}
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/airi-runtime}"
mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR"

# Only one of these at a time, however often Minori starts it.
exec 9>"$XDG_RUNTIME_DIR/minori-airi.lock"
flock -n 9 || exit 0

sshd_running() {
	for cmdline in /proc/[0-9]*/cmdline; do
		case "$(tr '\0' ' ' <"$cmdline" 2>/dev/null)" in
		*"sshd -D -p $ssh_port "*) return 0 ;;
		esac
	done
	return 1
}

# The machine's SSH server may still be starting.
sleep 3
while sshd_running; do
	"$HOME/.local/bin/airi-agent" serve --port "$agent_port"
	sleep 1
done
