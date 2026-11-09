//! The guest's internet: saki-core's userspace NAT, fed the packets minori-agent carries out of the guest's TUN
//! device. Every TCP connection and UDP flow ends here and continues as an ordinary socket of the app, so the guest
//! reaches whatever the phone can, with no root and no AVF network card.

use bytes::Bytes;
use saki_core::nat::{Nat, NatConfig, NatInput};
use std::net::Ipv4Addr;
use std::sync::{Arc, Mutex};
use tokio::runtime::Runtime;
use tokio::sync::mpsc;

const WORKER_THREADS: usize = 2;

pub(crate) struct Engine {
	runtime: Runtime,
	nat: Mutex<Option<Nat>>,
	input: NatInput,
	output: Mutex<mpsc::Receiver<Bytes>>,
}

impl Engine {
	pub(crate) fn start() -> std::io::Result<Self> {
		let runtime = tokio::runtime::Builder::new_multi_thread()
			.worker_threads(WORKER_THREADS)
			.thread_name("minori-nat")
			.enable_all()
			.build()?;
		// The guest may reach the phone's networks, as any device on them could, but never the phone's own loopback,
		// where other apps' services listen.
		let config = NatConfig::new(Ipv4Addr::new(1, 1, 1, 1));
		let mut nat = runtime.block_on(async { Nat::start(Arc::new(config)) });
		let input = nat.input();
		let output = nat.take_output().ok_or_else(|| std::io::Error::other("the NAT has no output"))?;
		Ok(Engine { runtime, nat: Mutex::new(Some(nat)), input, output: Mutex::new(output) })
	}

	pub(crate) fn input(&self, packet: Vec<u8>) {
		self.input.send(Bytes::from(packet));
	}

	/// Waits for the next packet for the guest, or None once stopped.
	pub(crate) fn output(&self) -> Option<Bytes> {
		let mut output = self.output.lock().ok()?;
		self.runtime.block_on(output.recv())
	}

	/// Ends every connection. A thread waiting in [`Engine::output`] gets None.
	pub(crate) fn stop(&self) {
		if let Ok(mut nat) = self.nat.lock() {
			nat.take();
		}
	}
}
