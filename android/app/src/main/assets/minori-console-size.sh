# Installed by Minori. A serial console can't tell the guest how big the terminal is, so this asks the terminal
# itself, as xterm's resize does, when you log in on one. Run resize-console after turning the phone.
minori_console_size() {
	case "$(tty 2>/dev/null)" in /dev/ttyS*|/dev/hvc*|/dev/ttyAMA*) ;; *) return 0 ;; esac
	minori_saved=$(stty -g 2>/dev/null) || return 0
	stty raw -echo min 0 time 5
	printf '\033[18t' >/dev/tty
	rui_reply=""
	while rui_c=$(dd bs=1 count=1 2>/dev/null </dev/tty) && [ -n "$rui_c" ]; do
		rui_reply="$rui_reply$rui_c"
		[ "$rui_c" = t ] && break
	done
	stty "$minori_saved"
	rui_reply=${rui_reply#*[}
	rui_reply=${rui_reply%t}
	rui_rows=$(echo "$rui_reply" | cut -d';' -f2)
	rui_cols=$(echo "$rui_reply" | cut -d';' -f3)
	[ -n "$rui_rows" ] && [ -n "$rui_cols" ] && stty rows "$rui_rows" cols "$rui_cols"
	unset minori_saved rui_reply rui_c rui_rows rui_cols
}
minori_console_size
alias resize-console=minori_console_size
