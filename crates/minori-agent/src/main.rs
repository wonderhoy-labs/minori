//! `minori-agent` runs inside a guest. Minori on the phone opens a vsock connection to it, says which guest port it
//! wants, and the agent connects that port on the guest's loopback and passes bytes both ways. That's how a port
//! published in Minori reaches a service in the VM, with no network between them.
//!
//! The protocol is one line from the phone, then either a reply line or a byte stream:
//!
//! - `HELLO` → `OK minori-agent <version>`
//! - `PORTS` → `OK [22,5900]`, the TCP ports something in the guest listens on
//! - `CONNECT <port>` → `OK`, then the connection's bytes, or `ERR <reason>`
//! - `UDP <port>` → `OK`, then datagrams both ways, each a big-endian u16 length and its bytes
//! - `NET` → `OK`, then the guest's IP packets both ways, framed like `UDP`, through the TUN device `minori0`
//! - `POWEROFF` → `OK`, then the guest shuts down
//!
//! It listens on vsock port 5741. `--tcp <address>` listens on TCP instead, for testing without vsock.

#[cfg(target_os = "linux")]
mod net;

use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::thread;

/// The vsock port Minori connects to.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
const VSOCK_PORT: u32 = 5741;
const MAX_LINE: usize = 256;

fn main() {
	let args: Vec<String> = std::env::args().skip(1).collect();
	let result = match args.iter().map(String::as_str).collect::<Vec<_>>().as_slice() {
		["--version"] => {
			println!("minori-agent {}", env!("CARGO_PKG_VERSION"));
			Ok(())
		}
		["--tcp", address] => serve_tcp(address),
		[] => serve_vsock(),
		_ => {
			eprintln!("Usage: minori-agent [--tcp <address>]");
			std::process::exit(2);
		}
	};
	if let Err(error) = result {
		eprintln!("minori-agent: {error}");
		std::process::exit(1);
	}
}

fn serve_tcp(address: &str) -> io::Result<()> {
	let listener = TcpListener::bind(address)?;
	eprintln!("minori-agent: listening on {address}");
	for stream in listener.incoming() {
		let stream = stream?;
		thread::spawn(move || {
			let reader = stream.try_clone();
			if let Ok(reader) = reader {
				let _ = handle(reader, stream);
			}
		});
	}
	Ok(())
}

#[cfg(target_os = "linux")]
fn serve_vsock() -> io::Result<()> {
	use std::fs::File;
	use std::os::fd::FromRawFd;
	// SAFETY: plain socket calls with a correctly sized sockaddr_vm; every result is checked.
	let listener = unsafe {
		let fd = libc::socket(libc::AF_VSOCK, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0);
		if fd < 0 {
			return Err(io::Error::last_os_error());
		}
		let mut address: libc::sockaddr_vm = std::mem::zeroed();
		address.svm_family = libc::AF_VSOCK as libc::sa_family_t;
		address.svm_port = VSOCK_PORT;
		address.svm_cid = libc::VMADDR_CID_ANY;
		let size = std::mem::size_of::<libc::sockaddr_vm>() as libc::socklen_t;
		if libc::bind(fd, (&raw const address).cast(), size) < 0 || libc::listen(fd, 64) < 0 {
			let error = io::Error::last_os_error();
			libc::close(fd);
			return Err(error);
		}
		fd
	};
	eprintln!("minori-agent: listening on vsock port {VSOCK_PORT}");
	loop {
		// SAFETY: accept on our listening socket; a new descriptor is owned by the File below.
		let fd = unsafe { libc::accept4(listener, std::ptr::null_mut(), std::ptr::null_mut(), libc::SOCK_CLOEXEC) };
		if fd < 0 {
			let error = io::Error::last_os_error();
			if error.kind() == io::ErrorKind::Interrupted {
				continue;
			}
			return Err(error);
		}
		// SAFETY: fd was just returned by accept and nothing else owns it.
		let stream = unsafe { File::from_raw_fd(fd) };
		thread::spawn(move || {
			if let Ok(reader) = stream.try_clone() {
				let _ = handle(reader, stream);
			}
		});
	}
}

#[cfg(not(target_os = "linux"))]
fn serve_vsock() -> io::Result<()> {
	Err(io::Error::new(io::ErrorKind::Unsupported, "vsock only exists on Linux; use --tcp"))
}

/// Answers one connection from the phone.
fn handle<R: Read + Send + 'static, W: Write + Send + 'static>(reader: R, mut writer: W) -> io::Result<()> {
	let mut reader = BufReader::new(reader);
	let mut line = String::new();
	(&mut reader).take(MAX_LINE as u64).read_line(&mut line)?;
	let line = line.trim();
	match line.split_once(' ').unwrap_or((line, "")) {
		("HELLO", _) => writeln!(writer, "OK minori-agent {}", env!("CARGO_PKG_VERSION")),
		("PORTS", _) => {
			let ports = listening_ports();
			let list: Vec<String> = ports.iter().map(u16::to_string).collect();
			writeln!(writer, "OK [{}]", list.join(","))
		}
		("NET", _) => {
			#[cfg(target_os = "linux")]
			{
				net::serve(reader, writer)
			}
			#[cfg(not(target_os = "linux"))]
			{
				writeln!(writer, "ERR the guest's internet needs Linux")
			}
		}
		("POWEROFF", _) => {
			writeln!(writer, "OK")?;
			writer.flush()?;
			let _ = std::process::Command::new("systemctl")
				.arg("poweroff")
				.status()
				.or_else(|_| std::process::Command::new("poweroff").status());
			Ok(())
		}
		("UDP", port) => {
			let Ok(port) = port.trim().parse::<u16>() else {
				return writeln!(writer, "ERR bad port");
			};
			let socket = std::net::UdpSocket::bind("127.0.0.1:0")?;
			socket.connect(("127.0.0.1", port))?;
			writeln!(writer, "OK")?;
			writer.flush()?;
			udp(reader, writer, socket)
		}
		("CONNECT", port) => {
			let Ok(port) = port.trim().parse::<u16>() else {
				return writeln!(writer, "ERR bad port");
			};
			match TcpStream::connect(("127.0.0.1", port)).or_else(|_| TcpStream::connect(("::1", port))) {
				Ok(service) => {
					writeln!(writer, "OK")?;
					writer.flush()?;
					splice(reader, writer, service)
				}
				Err(error) => writeln!(writer, "ERR {error}"),
			}
		}
		_ => writeln!(writer, "ERR unknown request"),
	}
}

/// Copies bytes both ways until both directions have closed.
fn splice<R: Read + Send + 'static, W: Write + Send + 'static>(
	mut from_phone: BufReader<R>,
	mut to_phone: W,
	service: TcpStream,
) -> io::Result<()> {
	let mut to_service = service.try_clone()?;
	let mut from_service = service;
	let upstream = thread::spawn(move || {
		let _ = io::copy(&mut from_phone, &mut to_service);
		let _ = to_service.shutdown(Shutdown::Write);
	});
	let _ = io::copy(&mut from_service, &mut to_phone);
	let _ = from_service.shutdown(Shutdown::Read);
	let _ = upstream.join();
	Ok(())
}

/// Carries datagrams as a u16 big-endian length and the bytes, both ways, until the phone closes the stream.
fn udp<R: Read + Send + 'static, W: Write + Send + 'static>(
	mut from_phone: BufReader<R>,
	mut to_phone: W,
	socket: std::net::UdpSocket,
) -> io::Result<()> {
	let replies = socket.try_clone()?;
	thread::spawn(move || {
		let mut buf = vec![0u8; 65_535];
		while let Ok(n) = replies.recv(&mut buf) {
			let header = (n as u16).to_be_bytes();
			if to_phone
				.write_all(&header)
				.and_then(|()| to_phone.write_all(&buf[..n]))
				.and_then(|()| to_phone.flush())
				.is_err()
			{
				break;
			}
		}
	});
	let mut header = [0u8; 2];
	let mut buf = vec![0u8; 65_535];
	while from_phone.read_exact(&mut header).is_ok() {
		let len = u16::from_be_bytes(header) as usize;
		from_phone.read_exact(&mut buf[..len])?;
		let _ = socket.send(&buf[..len]);
	}
	Ok(())
}

/// TCP ports in the LISTEN state, from `/proc/net/tcp` and `/proc/net/tcp6`.
fn listening_ports() -> Vec<u16> {
	let mut ports = Vec::new();
	for path in ["/proc/net/tcp", "/proc/net/tcp6"] {
		if let Ok(text) = std::fs::read_to_string(path) {
			ports.extend(parse_listening(&text));
		}
	}
	ports.sort_unstable();
	ports.dedup();
	ports
}

fn parse_listening(text: &str) -> Vec<u16> {
	text.lines()
		.skip(1)
		.filter_map(|line| {
			let mut fields = line.split_whitespace();
			let local = fields.nth(1)?;
			let state = fields.nth(1)?;
			if state != "0A" {
				return None;
			}
			let port = local.rsplit(':').next()?;
			u16::from_str_radix(port, 16).ok()
		})
		.collect()
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn reads_listening_ports_from_proc() {
		let text = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n\
		   0: 00000000:0016 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 1 1\n\
		   1: 0100007F:1F90 0100007F:9C40 01 00000000:00000000 00:00000000 00000000     0        0 2 1\n";
		assert_eq!(parse_listening(text), vec![22]);
	}

	#[test]
	fn connects_a_port_through_tcp_mode() {
		let service = TcpListener::bind("127.0.0.1:0").unwrap();
		let port = service.local_addr().unwrap().port();
		thread::spawn(move || {
			let (mut stream, _) = service.accept().unwrap();
			let mut buf = [0u8; 4];
			stream.read_exact(&mut buf).unwrap();
			stream.write_all(&buf.map(|byte| byte.to_ascii_uppercase())).unwrap();
		});
		let agent = TcpListener::bind("127.0.0.1:0").unwrap();
		let agent_address = agent.local_addr().unwrap();
		thread::spawn(move || {
			let (stream, _) = agent.accept().unwrap();
			let reader = stream.try_clone().unwrap();
			handle(reader, stream).unwrap();
		});
		let mut phone = TcpStream::connect(agent_address).unwrap();
		writeln!(phone, "CONNECT {port}").unwrap();
		let mut reply = [0u8; 3];
		phone.read_exact(&mut reply).unwrap();
		assert_eq!(&reply, b"OK\n");
		phone.write_all(b"ruii").unwrap();
		let mut echoed = [0u8; 4];
		phone.read_exact(&mut echoed).unwrap();
		assert_eq!(&echoed, b"RUII");
	}
}
