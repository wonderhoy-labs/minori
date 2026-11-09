//! A reader for qcow2 images, enough to inspect them and convert them to raw disks.
//!
//! It handles versions 2 and 3, zlib and zstd compressed clusters and zero clusters. It refuses backing files,
//! encryption and extended L2 entries, which cloud images don't use.

use crate::{Error, Result};
use std::collections::HashMap;
use std::fs::File;
use std::io::{self, Read};
use std::os::unix::fs::FileExt;
use std::rc::Rc;

pub(crate) const MAGIC: &[u8; 4] = b"QFI\xfb";

const L2_CACHE_TABLES: usize = 64;
const INCOMPATIBLE_COMPRESSION_TYPE: u64 = 1 << 3;
const INCOMPATIBLE_EXTENDED_L2: u64 = 1 << 4;
const OFFSET_MASK: u64 = 0x00ff_ffff_ffff_fe00;
const L2_COMPRESSED: u64 = 1 << 62;
const L2_ZERO: u64 = 1;

#[derive(Clone, Copy, PartialEq, Eq)]
enum Compression {
	Zlib,
	Zstd,
}

/// Where one guest cluster's data lives.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Cluster {
	/// Unallocated or explicitly zero: reads as zeros.
	Zero,
	/// Stored as is at this offset in the file.
	Raw(u64),
	/// Compressed, at this offset in the file, at most this many bytes long.
	Compressed(u64, u64),
}

pub(crate) struct Qcow2 {
	file: File,
	cluster_bits: u32,
	size: u64,
	l1: Vec<u64>,
	l2_cache: HashMap<u64, Rc<Vec<u64>>>,
	compression: Compression,
	scratch: Vec<u8>,
	cached: Option<(u64, Vec<u8>)>,
}

fn be32(bytes: &[u8], at: usize) -> u32 {
	u32::from_be_bytes(bytes[at..at + 4].try_into().unwrap_or_default())
}

fn be64(bytes: &[u8], at: usize) -> u64 {
	u64::from_be_bytes(bytes[at..at + 8].try_into().unwrap_or_default())
}

impl Qcow2 {
	pub(crate) fn open(file: File) -> Result<Self> {
		let mut header = [0u8; 112];
		file.read_exact_at(&mut header[..72], 0)?;
		if &header[..4] != MAGIC {
			return Err(Error::format("This isn't a qcow2 image"));
		}
		let version = be32(&header, 4);
		if !(2..=3).contains(&version) {
			return Err(Error::unsupported(format!("qcow2 version {version} isn't supported")));
		}
		let mut compression = Compression::Zlib;
		if version == 3 {
			// Version 3 headers are at least 104 bytes; read what's there.
			let _ = file.read_exact_at(&mut header[72..112], 72);
			let incompatible = be64(&header, 72);
			if incompatible & INCOMPATIBLE_EXTENDED_L2 != 0 {
				return Err(Error::unsupported("qcow2 images with extended L2 entries aren't supported"));
			}
			if incompatible & INCOMPATIBLE_COMPRESSION_TYPE != 0 {
				compression = match header[104] {
					0 => Compression::Zlib,
					1 => Compression::Zstd,
					other => return Err(Error::unsupported(format!("qcow2 compression type {other}"))),
				};
			}
			if incompatible & !(INCOMPATIBLE_COMPRESSION_TYPE | 1) != 0 {
				return Err(Error::unsupported("This qcow2 image uses features Minori doesn't know"));
			}
		}
		if be64(&header, 8) != 0 {
			return Err(Error::unsupported(
				"This qcow2 image depends on a backing file. Convert it to a standalone image first.",
			));
		}
		if be32(&header, 32) != 0 {
			return Err(Error::unsupported("Encrypted qcow2 images aren't supported"));
		}
		let cluster_bits = be32(&header, 20);
		if !(9..=21).contains(&cluster_bits) {
			return Err(Error::format("The qcow2 cluster size is out of range"));
		}
		let size = be64(&header, 24);
		let l1_size = be32(&header, 36) as usize;
		let l1_offset = be64(&header, 40);
		let mut raw = vec![0u8; l1_size * 8];
		file.read_exact_at(&mut raw, l1_offset)?;
		let l1 = raw.as_chunks::<8>().0.iter().map(|chunk| u64::from_be_bytes(*chunk)).collect();
		Ok(Qcow2 {
			file,
			cluster_bits,
			size,
			l1,
			l2_cache: HashMap::new(),
			compression,
			scratch: Vec::new(),
			cached: None,
		})
	}

	pub(crate) fn size(&self) -> u64 {
		self.size
	}

	pub(crate) fn cluster_size(&self) -> u64 {
		1 << self.cluster_bits
	}

	fn l2_table(&mut self, offset: u64) -> Result<Rc<Vec<u64>>> {
		if let Some(table) = self.l2_cache.get(&offset) {
			return Ok(table.clone());
		}
		let mut raw = vec![0u8; self.cluster_size() as usize];
		self.file.read_exact_at(&mut raw, offset)?;
		let table = Rc::new(raw.as_chunks::<8>().0.iter().map(|chunk| u64::from_be_bytes(*chunk)).collect::<Vec<_>>());
		if self.l2_cache.len() >= L2_CACHE_TABLES {
			self.l2_cache.clear();
		}
		self.l2_cache.insert(offset, table.clone());
		Ok(table)
	}

	pub(crate) fn locate(&mut self, guest_cluster: u64) -> Result<Cluster> {
		let entries_per_l2 = self.cluster_size() / 8;
		let l1_index = (guest_cluster / entries_per_l2) as usize;
		let l2_index = (guest_cluster % entries_per_l2) as usize;
		let Some(&l1_entry) = self.l1.get(l1_index) else {
			return Ok(Cluster::Zero);
		};
		let l2_offset = l1_entry & OFFSET_MASK;
		if l2_offset == 0 {
			return Ok(Cluster::Zero);
		}
		let table = self.l2_table(l2_offset)?;
		let entry = table.get(l2_index).copied().unwrap_or(0);
		if entry & L2_COMPRESSED != 0 {
			let size_bits = 62 - (self.cluster_bits - 8);
			let offset = entry & ((1 << size_bits) - 1);
			let sectors = (entry & ((1 << 62) - 1)) >> size_bits;
			let length = (sectors + 1) * 512 - (offset & 511);
			return Ok(Cluster::Compressed(offset, length));
		}
		let offset = entry & OFFSET_MASK;
		if entry & L2_ZERO != 0 || offset == 0 {
			return Ok(Cluster::Zero);
		}
		Ok(Cluster::Raw(offset))
	}

	/// Reads one whole guest cluster into `out`, which must be one cluster long. Returns false, leaving `out`
	/// alone, if the cluster reads as zeros.
	pub(crate) fn read_cluster(&mut self, guest_cluster: u64, out: &mut [u8]) -> Result<bool> {
		match self.locate(guest_cluster)? {
			Cluster::Zero => Ok(false),
			Cluster::Raw(offset) => {
				read_padded(&self.file, offset, out)?;
				Ok(true)
			}
			Cluster::Compressed(offset, length) => {
				self.scratch.resize(length as usize, 0);
				let got = read_padded(&self.file, offset, &mut self.scratch)?;
				let input = &self.scratch[..got];
				let filled = match self.compression {
					Compression::Zlib => fill(&mut flate2::read::DeflateDecoder::new(input), out)?,
					Compression::Zstd => {
						let mut decoder = ruzstd::decoding::StreamingDecoder::new(input)
							.map_err(|error| Error::format(format!("A zstd cluster is damaged: {error}")))?;
						fill(&mut decoder, out)?
					}
				};
				out[filled..].fill(0);
				Ok(true)
			}
		}
	}

	pub(crate) fn read_at(&mut self, mut offset: u64, mut buf: &mut [u8]) -> Result<()> {
		let cluster_size = self.cluster_size();
		while !buf.is_empty() {
			if offset >= self.size {
				buf.fill(0);
				return Ok(());
			}
			let cluster = offset / cluster_size;
			let within = (offset % cluster_size) as usize;
			let take = buf.len().min(cluster_size as usize - within);
			match self.locate(cluster)? {
				Cluster::Zero => buf[..take].fill(0),
				Cluster::Raw(host) => {
					read_padded(&self.file, host + within as u64, &mut buf[..take])?;
				}
				Cluster::Compressed(..) => {
					if self.cached.as_ref().map(|(index, _)| *index) != Some(cluster) {
						let mut data = vec![0u8; cluster_size as usize];
						self.read_cluster(cluster, &mut data)?;
						self.cached = Some((cluster, data));
					}
					if let Some((_, data)) = &self.cached {
						buf[..take].copy_from_slice(&data[within..within + take]);
					}
				}
			}
			offset += take as u64;
			buf = &mut buf[take..];
		}
		Ok(())
	}
}

/// Reads as much as the file has, zero-filling past its end, and returns how many bytes were real.
fn read_padded(file: &File, offset: u64, buf: &mut [u8]) -> io::Result<usize> {
	let mut done = 0;
	while done < buf.len() {
		match file.read_at(&mut buf[done..], offset + done as u64) {
			Ok(0) => break,
			Ok(n) => done += n,
			Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
			Err(error) => return Err(error),
		}
	}
	buf[done..].fill(0);
	Ok(done)
}

fn fill(reader: &mut dyn Read, out: &mut [u8]) -> Result<usize> {
	let mut done = 0;
	while done < out.len() {
		match reader.read(&mut out[done..]) {
			Ok(0) => break,
			Ok(n) => done += n,
			Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
			Err(error) => return Err(Error::format(format!("A compressed cluster is damaged: {error}"))),
		}
	}
	Ok(done)
}
