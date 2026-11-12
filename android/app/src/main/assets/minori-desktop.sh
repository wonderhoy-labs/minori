#!/bin/sh
# Keeps a desktop running in a Termux machine, which has no systemd to do it: one Xfce session in TigerVNC, which
# Minori's viewer and Asahi draw over VNC, and xrdp in front of the same session for Remote Desktop apps. Minori
# installs this when it adds a desktop to the machine, and starts it with the machine:
# `minori-desktop <VNC port> <RDP port> <SSH port>`. It stops once the machine's SSH server is gone, which is how
# Minori stops a Termux machine.
#
# Termux shares the phone's loopback and abstract sockets with every other Termux machine, so the ports and the X
# display number are the machine's own, and both servers listen on the phone's loopback only.
vnc_port=${1:?VNC port}
rdp_port=${2:?RDP port}
ssh_port=${3:?SSH port}
display=$((vnc_port % 10000 + 100))
dir=/root/.config/minori-desktop

mkdir -p /tmp/.X11-unix /run/xrdp && chmod 1777 /tmp/.X11-unix

# Only one of these at a time, however often Minori starts it. What it starts doesn't inherit the lock, or a server
# outliving a killed loop would keep every later loop from running.
exec 9>"$dir/loop.lock"
flock -n 9 || exit 0

sshd_running() {
	for cmdline in /proc/[0-9]*/cmdline; do
		case "$(tr '\0' ' ' <"$cmdline" 2>/dev/null)" in
		*"sshd -D -p $ssh_port "*) return 0 ;;
		esac
	done
	return 1
}

# Whether the process this loop started as [name] still runs, and is still that program: Android can kill the loop
# and leave its servers behind, and a pid file can outlive its process.
alive() {
	pid=$(cat "$dir/$1.pid" 2>/dev/null) && [ -n "$pid" ] || return 1
	command=$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null)
	case "$1:$command" in
	# The session's process starts as dbus-launch and becomes startxfce4, then xfce4-session.
	vnc:*Xtigervnc* | xrdp:*xrdp* | session:*dbus-launch* | session:*startxfce4* | session:*xfce4-session*) return 0 ;;
	esac
	return 1
}

stop() {
	for name in session xrdp vnc; do
		# The session is its own process group, from setsid, and all of Xfce goes with it.
		if alive "$name"; then
			kill -- "-$pid" 2>/dev/null || kill "$pid"
		fi
		rm -f "$dir/$name.pid"
	done
}

# Android stops Termux's processes past 32 for the whole app, so Xfce leaves out the agents it would start for each
# session, which nothing here needs.
session_settings=/root/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-session.xml
if [ ! -e "$session_settings" ]; then
	mkdir -p "${session_settings%/*}"
	cat >"$session_settings" <<-'XML'
		<?xml version="1.0" encoding="UTF-8"?>
		<channel name="xfce4-session" version="1.0">
		  <property name="startup" type="empty">
		    <property name="ssh-agent" type="empty"><property name="enabled" type="bool" value="false"/></property>
		    <property name="gpg-agent" type="empty"><property name="enabled" type="bool" value="false"/></property>
		  </property>
		</channel>
	XML
fi

# What a loop that was killed left running would hold the ports and the display, so it goes first.
stop

# The machine's SSH server may still be starting.
sleep 3
while sshd_running; do
	if ! alive vnc; then
		rm -f "/tmp/.X$display-lock" "/tmp/.X11-unix/X$display"
		Xtigervnc ":$display" -rfbport "$vnc_port" -localhost -SecurityTypes VncAuth \
			-PasswordFile "$dir/passwd" -geometry 1600x900 -depth 24 -desktop "$(hostname)" -AlwaysShared \
			>"$dir/vnc.log" 2>&1 9>&- &
		echo $! >"$dir/vnc.pid"
		sleep 2
	fi
	# Logging out of the desktop ends the session; this brings up a new one.
	if ! alive session; then
		DISPLAY=":$display" setsid dbus-launch --exit-with-session startxfce4 >"$dir/session.log" 2>&1 9>&- &
		echo $! >"$dir/session.pid"
	fi
	if ! alive xrdp; then
		xrdp --nodaemon --config "$dir/xrdp.ini" >"$dir/xrdp.log" 2>&1 9>&- &
		echo $! >"$dir/xrdp.pid"
	fi
	sleep 3
done
stop
