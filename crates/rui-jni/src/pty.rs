//! Pseudo-terminals, for consoles that run a local program, like the demo backend's shell and proot guests.

use std::ffi::CString;

pub(crate) fn spawn(
	program: &str,
	args: &[String],
	environment: &[String],
	cwd: &str,
	rows: u16,
	cols: u16,
) -> Result<(i32, i32), String> {
	let cstring = |text: &str| CString::new(text).map_err(|_| format!("\"{text}\" has a NUL byte"));
	let program_c = cstring(program)?;
	let mut argv_c = vec![program_c.clone()];
	for arg in args {
		argv_c.push(cstring(arg)?);
	}
	let env_c: Vec<CString> = environment.iter().map(|item| cstring(item)).collect::<Result<_, _>>()?;
	let cwd_c = cstring(cwd)?;
	// Everything the child needs is prepared before fork, because after it only async-signal-safe calls are allowed.
	let mut argv: Vec<*const libc::c_char> = argv_c.iter().map(|arg| arg.as_ptr()).collect();
	argv.push(std::ptr::null());
	let mut envp: Vec<*const libc::c_char> = env_c.iter().map(|item| item.as_ptr()).collect();
	envp.push(std::ptr::null());
	let size = libc::winsize { ws_row: rows.max(1), ws_col: cols.max(1), ws_xpixel: 0, ws_ypixel: 0 };
	let mut master: libc::c_int = -1;
	// SAFETY: forkpty is given valid pointers; the child only calls chdir, execve and _exit before replacing itself.
	let pid = unsafe {
		libc::forkpty(&mut master, std::ptr::null_mut(), std::ptr::null::<libc::termios>() as _, &raw const size as _)
	};
	if pid < 0 {
		return Err(format!("Couldn't open a terminal: {}", std::io::Error::last_os_error()));
	}
	if pid == 0 {
		// SAFETY: in the child, with pointers prepared before fork.
		unsafe {
			libc::chdir(cwd_c.as_ptr());
			libc::execve(program_c.as_ptr(), argv.as_ptr(), envp.as_ptr());
			libc::_exit(127);
		}
	}
	Ok((master, pid))
}

pub(crate) fn resize(fd: i32, rows: u16, cols: u16) {
	let size = libc::winsize { ws_row: rows.max(1), ws_col: cols.max(1), ws_xpixel: 0, ws_ypixel: 0 };
	// SAFETY: TIOCSWINSZ reads a winsize; a bad descriptor just fails.
	unsafe {
		libc::ioctl(fd, libc::TIOCSWINSZ, &size);
	}
}

pub(crate) fn wait(pid: i32) -> i32 {
	let mut status: libc::c_int = 0;
	loop {
		// SAFETY: waitpid writes the status into a valid int.
		let result = unsafe { libc::waitpid(pid, &mut status, 0) };
		if result == pid {
			break;
		}
		if result < 0 && std::io::Error::last_os_error().raw_os_error() != Some(libc::EINTR) {
			return -1;
		}
	}
	if libc::WIFEXITED(status) {
		libc::WEXITSTATUS(status)
	} else if libc::WIFSIGNALED(status) {
		128 + libc::WTERMSIG(status)
	} else {
		-1
	}
}

pub(crate) fn kill(pid: i32, signal: i32) -> bool {
	// SAFETY: kill takes plain integers.
	unsafe { libc::kill(pid, signal) == 0 }
}

#[cfg(test)]
mod tests {
	use super::*;
	use std::io::Read;
	use std::os::fd::FromRawFd;

	#[test]
	fn runs_a_program_on_a_terminal() {
		let (fd, pid) = spawn("/bin/sh", &["-c".into(), "echo rui $((6 * 7))".into()], &[], "/", 24, 80).unwrap();
		// SAFETY: the descriptor is ours and is closed when the file drops.
		let mut terminal = unsafe { std::fs::File::from_raw_fd(fd) };
		let mut output = Vec::new();
		let mut buf = [0u8; 256];
		while let Ok(n) = terminal.read(&mut buf) {
			if n == 0 {
				break;
			}
			output.extend_from_slice(&buf[..n]);
			if String::from_utf8_lossy(&output).contains("rui 42") {
				break;
			}
		}
		assert!(String::from_utf8_lossy(&output).contains("rui 42"));
		assert_eq!(wait(pid), 0);
	}
}
