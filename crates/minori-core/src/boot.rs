//! Pulling a kernel and initrd out of an image so AVF can start it.
//!
//! crosvm loads an uncompressed arm64 `Image`. Distributions ship kernels gzip-compressed (Debian, Ubuntu) or as
//! EFI zboot images (Fedora), so [`extract`] unpacks both and checks the result really is a 64-bit Arm kernel.

use crate::inspect::{self, Arch, BootPlan};
use crate::{Error, Result};
use flate2::read::GzDecoder;
use serde::Serialize;
use std::io::Read;
use std::path::{Path, PathBuf};

const ARM64_MAGIC: &[u8; 4] = b"ARM\x64";
const MAX_KERNEL: u64 = 256 << 20;

/// A kernel and initrd written out, ready to hand to AVF.
#[derive(Clone, Debug, Serialize)]
pub struct BootFiles {
	/// The uncompressed kernel.
	pub kernel: PathBuf,
	/// The initrd, as the image had it. Kernels unpack their own initrd.
	pub initrd: Option<PathBuf>,
	/// The kernel command line from the plan.
	pub cmdline: String,
}

/// Reads the plan's kernel and initrd from `image` and writes them into `out_dir` as `kernel` and `initrd`.
pub fn extract(image: &Path, plan: &BootPlan, out_dir: &Path) -> Result<BootFiles> {
	std::fs::create_dir_all(out_dir)?;
	let raw = inspect::read_boot_file(image, &plan.source, &plan.kernel)?;
	let kernel = decompress_kernel(raw)?;
	match kernel_arch(&kernel)? {
		Arch::Aarch64 => {}
		Arch::X86_64 => return Err(Error::unsupported("This is an x86-64 kernel. A phone can only run 64-bit Arm.")),
		Arch::Unknown => return Err(Error::unsupported("Minori doesn't recognise this kernel as 64-bit Arm")),
	}
	let kernel_path = out_dir.join("kernel");
	write_replacing(&kernel_path, &kernel)?;
	let initrd = match &plan.initrd {
		Some(path) => {
			let data = inspect::read_boot_file(image, &plan.source, path)?;
			let initrd_path = out_dir.join("initrd");
			write_replacing(&initrd_path, &data)?;
			Some(initrd_path)
		}
		None => None,
	};
	Ok(BootFiles { kernel: kernel_path, initrd, cmdline: plan.cmdline.clone() })
}

fn write_replacing(path: &Path, data: &[u8]) -> Result<()> {
	let temporary = path.with_extension("part");
	std::fs::write(&temporary, data)?;
	std::fs::rename(&temporary, path)?;
	Ok(())
}

/// Unpacks a gzip or EFI zboot kernel, or the kernel inside a systemd-stub EFI program. An uncompressed kernel comes
/// back as it was.
pub fn decompress_kernel(data: Vec<u8>) -> Result<Vec<u8>> {
	unwrap_kernel(data, 0)
}

/// How many wrappers deep a kernel may be, such as Ubuntu 26.04's: gzip, then a stub's `.linux`, then zboot.
const MAX_WRAPPERS: u32 = 4;

fn unwrap_kernel(data: Vec<u8>, depth: u32) -> Result<Vec<u8>> {
	if depth >= MAX_WRAPPERS {
		return Ok(data);
	}
	if data.starts_with(&[0x1f, 0x8b]) {
		return unwrap_kernel(gunzip(&data)?, depth + 1);
	}
	// A systemd-stub EFI program, which carries the kernel in its .linux section next to device trees and the like.
	if let Some(linux) = pe_section(&data, b".linux") {
		return unwrap_kernel(linux.to_vec(), depth + 1);
	}
	// An EFI zboot image: a small PE stub carrying the compressed kernel, described at fixed offsets.
	if data.len() > 0x40 && &data[..2] == b"MZ" && &data[4..8] == b"zimg" {
		let offset = u32::from_le_bytes(data[8..12].try_into().unwrap_or_default()) as usize;
		let size = u32::from_le_bytes(data[12..16].try_into().unwrap_or_default()) as usize;
		let kind = String::from_utf8_lossy(&data[0x18..0x38]).trim_end_matches('\0').to_string();
		let payload = data
			.get(offset..offset.saturating_add(size))
			.ok_or_else(|| Error::format("The zboot kernel's payload is out of range"))?;
		return match kind.as_str() {
			"gzip" => gunzip(payload),
			"zstd" | "zstd22" => {
				let mut out = Vec::new();
				let decoder = ruzstd::decoding::StreamingDecoder::new(payload)
					.map_err(|error| Error::format(format!("The zboot kernel is damaged: {error}")))?;
				decoder.take(MAX_KERNEL).read_to_end(&mut out)?;
				Ok(out)
			}
			other => Err(Error::unsupported(format!("Kernels compressed with {other} aren't supported yet"))),
		};
	}
	Ok(data)
}

/// The contents of the section called [name] in a PE (EFI) program, if [data] is one and has it.
fn pe_section<'a>(data: &'a [u8], name: &[u8]) -> Option<&'a [u8]> {
	let u16_at = |at: usize| data.get(at..at + 2).map(|b| u16::from_le_bytes([b[0], b[1]]) as usize);
	let u32_at = |at: usize| data.get(at..at + 4).map(|b| u32::from_le_bytes([b[0], b[1], b[2], b[3]]) as usize);
	if !data.starts_with(b"MZ") {
		return None;
	}
	let pe = u32_at(0x3c)?;
	if data.get(pe..pe + 4)? != b"PE\0\0" {
		return None;
	}
	let sections = u16_at(pe + 6)?;
	let table = pe + 24 + u16_at(pe + 20)?;
	(0..sections).find_map(|index| {
		let entry = table + index * 40;
		let raw = data.get(entry..entry + 8)?;
		let length = raw.iter().position(|&byte| byte == 0).unwrap_or(8);
		if &raw[..length] != name {
			return None;
		}
		let (size, start) = (u32_at(entry + 16)?, u32_at(entry + 20)?);
		data.get(start..start.checked_add(size)?)
	})
}

fn gunzip(data: &[u8]) -> Result<Vec<u8>> {
	let mut out = Vec::new();
	GzDecoder::new(data)
		.take(MAX_KERNEL)
		.read_to_end(&mut out)
		.map_err(|error| Error::format(format!("The kernel's compression is damaged: {error}")))?;
	Ok(out)
}

/// Says which CPU a kernel image is for, from its header. Compressed kernels are unpacked as far as needed.
pub fn kernel_arch(data: &[u8]) -> Result<Arch> {
	let head: Vec<u8> = if data.starts_with(&[0x1f, 0x8b]) {
		let mut out = Vec::new();
		let _ = GzDecoder::new(data).take(0x400).read_to_end(&mut out);
		out
	} else if data.len() > 8 && &data[..2] == b"MZ" && &data[4..8] == b"zimg" {
		// zboot exists only for EFI on Arm and RISC-V; its PE header names the machine.
		return Ok(pe_machine(data));
	} else {
		data[..data.len().min(0x400)].to_vec()
	};
	if head.len() >= 0x3c && &head[0x38..0x3c] == ARM64_MAGIC {
		return Ok(Arch::Aarch64);
	}
	if head.len() >= 0x206 && &head[0x202..0x206] == b"HdrS" {
		return Ok(Arch::X86_64);
	}
	if head.len() >= 0x40 && &head[..2] == b"MZ" {
		return Ok(pe_machine(&head));
	}
	Ok(Arch::Unknown)
}

fn pe_machine(data: &[u8]) -> Arch {
	let Some(at) = data.get(0x3c..0x40).map(|bytes| u32::from_le_bytes(bytes.try_into().unwrap_or_default()) as usize)
	else {
		return Arch::Unknown;
	};
	match data.get(at..at + 6) {
		Some([b'P', b'E', 0, 0, low, high]) => match u16::from_le_bytes([*low, *high]) {
			0xaa64 => Arch::Aarch64,
			0x8664 => Arch::X86_64,
			_ => Arch::Unknown,
		},
		_ => Arch::Unknown,
	}
}

#[cfg(test)]
mod tests {
	use super::*;
	use flate2::Compression;
	use flate2::write::GzEncoder;
	use std::io::Write;

	fn fake_arm64_kernel() -> Vec<u8> {
		let mut kernel = vec![0u8; 4096];
		kernel[0x38..0x3c].copy_from_slice(ARM64_MAGIC);
		kernel
	}

	#[test]
	fn recognises_and_unpacks_a_gzip_arm64_kernel() {
		let kernel = fake_arm64_kernel();
		let mut encoder = GzEncoder::new(Vec::new(), Compression::fast());
		encoder.write_all(&kernel).unwrap();
		let packed = encoder.finish().unwrap();
		assert_eq!(kernel_arch(&packed).unwrap(), Arch::Aarch64);
		assert_eq!(decompress_kernel(packed).unwrap(), kernel);
	}

	/// A minimal PE program with one section, the way systemd-stub carries a kernel in `.linux`.
	fn stub_carrying(name: &[u8], payload: &[u8]) -> Vec<u8> {
		let pe = 0x40usize;
		let table = pe + 24;
		let start = table + 40;
		let mut stub = vec![0u8; start];
		stub[..2].copy_from_slice(b"MZ");
		stub[0x3c..0x40].copy_from_slice(&(pe as u32).to_le_bytes());
		stub[pe..pe + 4].copy_from_slice(b"PE\0\0");
		stub[pe + 4..pe + 6].copy_from_slice(&0xaa64u16.to_le_bytes());
		stub[pe + 6..pe + 8].copy_from_slice(&1u16.to_le_bytes());
		stub[table..table + name.len()].copy_from_slice(name);
		stub[table + 16..table + 20].copy_from_slice(&(payload.len() as u32).to_le_bytes());
		stub[table + 20..table + 24].copy_from_slice(&(start as u32).to_le_bytes());
		stub.extend_from_slice(payload);
		stub
	}

	#[test]
	fn unpacks_a_gzipped_stub_carrying_the_kernel() {
		// Ubuntu 26.04: vmlinuz is gzip around a systemd-stub program whose .linux section is the kernel.
		let kernel = fake_arm64_kernel();
		let stub = stub_carrying(b".linux", &kernel);
		let mut encoder = GzEncoder::new(Vec::new(), Compression::fast());
		encoder.write_all(&stub).unwrap();
		assert_eq!(decompress_kernel(encoder.finish().unwrap()).unwrap(), kernel);
	}

	#[test]
	fn leaves_a_pe_without_a_linux_section_alone() {
		let stub = stub_carrying(b".text", b"code");
		assert_eq!(decompress_kernel(stub.clone()).unwrap(), stub);
	}

	#[test]
	fn recognises_an_x86_kernel() {
		let mut kernel = vec![0u8; 4096];
		kernel[0x202..0x206].copy_from_slice(b"HdrS");
		assert_eq!(kernel_arch(&kernel).unwrap(), Arch::X86_64);
	}
}
