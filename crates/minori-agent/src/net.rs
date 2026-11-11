//! The guest's internet: a TUN device whose packets travel to the phone over the agent's connection, where
//! Minori's userspace NAT turns them into ordinary sockets. The guest needs no other network card.

use std::fs::{File, OpenOptions};
use std::io::{self, BufReader, Read, Write};
use std::os::fd::AsRawFd;
use std::os::unix::ffi::OsStrExt;
use std::path::Path;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;

/// The interface the guest's traffic leaves by.
pub(crate) const DEVICE: &str = "minori0";
/// The guest's address. The phone's NAT doesn't care which address its client uses; this one is Saki's tunnel's.
const ADDRESS: &str = "10.233.77.2/24";
/// Matches the NAT's own MTU.
const MTU: &str = "1280";
const NAMESERVERS: [&str; 2] = ["1.1.1.1", "9.9.9.9"];
const TUNSETIFF: libc::c_ulong = 0x4004_54ca;

static ACTIVE: AtomicBool = AtomicBool::new(false);

#[repr(C)]
struct IfReq {
	name: [libc::c_char; libc::IFNAMSIZ],
	flags: libc::c_short,
	_pad: [u8; 22],
}

fn open_tun() -> io::Result<File> {
	let file = OpenOptions::new().read(true).write(true).open("/dev/net/tun")?;
	let mut request =
		IfReq { name: [0; libc::IFNAMSIZ], flags: (libc::IFF_TUN | libc::IFF_NO_PI) as libc::c_short, _pad: [0; 22] };
	for (slot, byte) in request.name.iter_mut().zip(DEVICE.bytes()) {
		*slot = byte as libc::c_char;
	}
	// SAFETY: TUNSETIFF reads and writes an ifreq, which IfReq lays out as the kernel expects.
	if unsafe { libc::ioctl(file.as_raw_fd(), TUNSETIFF as _, &mut request) } < 0 {
		return Err(io::Error::last_os_error());
	}
	Ok(file)
}

fn run(program: &str, args: &[&str]) -> io::Result<()> {
	let status = Command::new(program).args(args).status()?;
	if status.success() { Ok(()) } else { Err(io::Error::other(format!("{program} {} failed", args.join(" ")))) }
}

fn configure() -> io::Result<()> {
	run("ip", &["link", "set", DEVICE, "mtu", MTU, "up"])?;
	run("ip", &["addr", "replace", ADDRESS, "dev", DEVICE])?;
	// A low metric, so this wins over a network card AVF might also have given the guest.
	run("ip", &["route", "replace", "default", "dev", DEVICE, "metric", "50"])?;
	set_dns()
}

const RESOLV_CONF: &str = "/etc/resolv.conf";
/// A copy of the guest's own resolv.conf while the agent's is in place.
const BACKUP: &str = "/etc/resolv.conf.minori-backup";
/// Where resolv.conf pointed, when it was a link, while the agent's is in place.
const BACKUP_LINK: &str = "/etc/resolv.conf.minori-link";

/// Points the guest's resolver at public DNS through the tunnel: through systemd-resolved when it runs, otherwise
/// by writing /etc/resolv.conf, keeping the old one to put back when the phone goes.
fn set_dns() -> io::Result<()> {
	let resolved = Command::new("systemctl").args(["is-active", "--quiet", "systemd-resolved"]).status();
	if resolved.map(|status| status.success()).unwrap_or(false) {
		let mut args = vec!["dns", DEVICE];
		args.extend(NAMESERVERS);
		run("resolvectl", &args)?;
		run("resolvectl", &["domain", DEVICE, "~."])?;
		return run("resolvectl", &["default-route", DEVICE, "true"]);
	}
	let path = Path::new(RESOLV_CONF);
	let (backup, backup_link) = (Path::new(BACKUP), Path::new(BACKUP_LINK));
	// A backup that's already there is the guest's own, left by an agent that didn't get to put it back.
	if !backup.exists() && !backup_link.exists() {
		if path.is_symlink() {
			std::fs::write(backup_link, std::fs::read_link(path)?.as_os_str().as_encoded_bytes())?;
		} else if path.exists() {
			std::fs::copy(path, backup)?;
		}
	}
	if path.is_symlink() {
		std::fs::remove_file(path)?;
	}
	let text: String = NAMESERVERS.iter().map(|server| format!("nameserver {server}\n")).collect();
	std::fs::write(path, format!("# Written by minori-agent while Minori gives this Linux internet.\n{text}"))
}

/// Puts back the resolv.conf that [`set_dns`] replaced. systemd-resolved forgets the device's servers by itself.
fn restore_dns() {
	let path = Path::new(RESOLV_CONF);
	let (backup, backup_link) = (Path::new(BACKUP), Path::new(BACKUP_LINK));
	if let Ok(target) = std::fs::read(backup_link) {
		let _ = std::fs::remove_file(path);
		let target = std::ffi::OsStr::from_bytes(&target);
		if std::os::unix::fs::symlink(target, path).is_ok() {
			let _ = std::fs::remove_file(backup_link);
		}
	} else if backup.exists() && std::fs::rename(backup, path).is_err() {
		let _ = std::fs::copy(backup, path).and_then(|_| std::fs::remove_file(backup));
	}
}

/// Carries packets between the TUN device and the phone, each a big-endian u16 length and its bytes, until the
/// phone closes the connection. Closing the device removes its address and route with it.
pub(crate) fn serve<R: Read + Send + 'static, W: Write + Send + 'static>(
	from_phone: BufReader<R>,
	mut to_phone: W,
) -> io::Result<()> {
	if ACTIVE.swap(true, Ordering::SeqCst) {
		return writeln!(to_phone, "ERR the guest's internet is already connected");
	}
	let result = (|| {
		let tun = match open_tun().and_then(|tun| configure().map(|()| tun)) {
			Ok(tun) => tun,
			Err(error) => return writeln!(to_phone, "ERR {error}"),
		};
		writeln!(to_phone, "OK")?;
		to_phone.flush()?;
		let mut tun_reader = tun.try_clone()?;
		let mut tun_writer = tun;
		let stop = std::sync::Arc::new(AtomicBool::new(false));
		let stopped = stop.clone();
		let up = thread::spawn(move || {
			// The length and the packet go out in one write, so neither waits on the other's acknowledgement.
			let mut buf = vec![0u8; 2 + 65_535];
			loop {
				// Wakes twice a second to notice the phone has gone, so the device is closed and can be made again.
				let mut poll = libc::pollfd { fd: tun_reader.as_raw_fd(), events: libc::POLLIN, revents: 0 };
				// SAFETY: poll reads one valid pollfd.
				let ready = unsafe { libc::poll(&mut poll, 1, 500) };
				if stopped.load(Ordering::SeqCst) {
					break;
				}
				if ready <= 0 {
					continue;
				}
				let Ok(n) = tun_reader.read(&mut buf[2..]) else { break };
				if n == 0 {
					break;
				}
				buf[..2].copy_from_slice(&(n as u16).to_be_bytes());
				if to_phone.write_all(&buf[..2 + n]).and_then(|()| to_phone.flush()).is_err() {
					break;
				}
			}
		});
		let mut from_phone = from_phone;
		let mut header = [0u8; 2];
		let mut buf = vec![0u8; 65_535];
		while from_phone.read_exact(&mut header).is_ok() {
			let len = u16::from_be_bytes(header) as usize;
			if from_phone.read_exact(&mut buf[..len]).is_err() {
				break;
			}
			let _ = tun_writer.write(&buf[..len]);
		}
		stop.store(true, Ordering::SeqCst);
		let _ = up.join();
		drop(tun_writer);
		restore_dns();
		Ok(())
	})();
	ACTIVE.store(false, Ordering::SeqCst);
	result
}
