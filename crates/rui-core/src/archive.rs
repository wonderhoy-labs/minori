//! `.ruivm` archives: a whole VM in one file, for backups and moving VMs between phones.
//!
//! An archive is a gzip-compressed tar holding, in this order:
//!
//! 1. `manifest.json`: a [`Manifest`], with the VM's own settings as Rui stores them, and its disks' names and
//!    sizes.
//! 2. `disks/<name>`: each disk, byte for byte. Zero blocks compress to almost nothing.
//! 3. `SHA256SUMS`: a SHA-256 for every disk, in the format `sha256sum` writes, so `sha256sum -c` checks an
//!    unpacked archive too.
//!
//! Any tar can unpack one (`tar -xzf vm.ruivm`). [`import`] checks every checksum before keeping anything.

use crate::progress::{Progress, Throttle};
use crate::{Error, Result};
use flate2::Compression;
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::io::{self, BufReader, BufWriter, Read, Write};
use std::os::unix::fs::FileExt;
use std::path::{Path, PathBuf};

/// The file extension Rui gives archives.
pub const EXTENSION: &str = "ruivm";
/// The `format` every manifest carries.
pub const FORMAT: &str = "rui-vm";
/// The newest manifest version this crate writes and reads.
pub const VERSION: u32 = 1;

const CHUNK: usize = 1 << 20;

/// What an archive holds.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Manifest {
	/// Always [`FORMAT`].
	pub format: String,
	/// The manifest version, [`VERSION`] for archives this crate writes.
	pub version: u32,
	/// The VM's settings, exactly as the app stored them. `rui-core` doesn't look inside.
	pub vm: serde_json::Value,
	/// The disks, in the order they're stored.
	pub disks: Vec<ManifestDisk>,
}

/// One disk in a [`Manifest`].
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ManifestDisk {
	/// The file name under `disks/`.
	pub file: String,
	/// The disk's size in bytes.
	pub size: u64,
}

/// Writes an archive of a VM to `out`: its settings `vm`, and `disks` as (name in the archive, file) pairs.
/// `out` is removed if this fails.
pub fn export(
	vm: &serde_json::Value,
	disks: &[(String, PathBuf)],
	out: &Path,
	progress: &mut dyn Progress,
) -> Result<()> {
	let result = export_inner(vm, disks, out, progress);
	if result.is_err() {
		let _ = std::fs::remove_file(out);
	}
	result
}

fn export_inner(
	vm: &serde_json::Value,
	disks: &[(String, PathBuf)],
	out: &Path,
	progress: &mut dyn Progress,
) -> Result<()> {
	let mut manifest = Manifest { format: FORMAT.to_string(), version: VERSION, vm: vm.clone(), disks: Vec::new() };
	for (name, path) in disks {
		check_name(name)?;
		manifest.disks.push(ManifestDisk { file: name.clone(), size: std::fs::metadata(path)?.len() });
	}
	let total: u64 = manifest.disks.iter().map(|disk| disk.size).sum();
	let file = BufWriter::with_capacity(CHUNK, File::create(out)?);
	let mut tar = tar::Builder::new(GzEncoder::new(file, Compression::fast()));
	let json = serde_json::to_vec_pretty(&manifest).map_err(|error| Error::format(error.to_string()))?;
	append_bytes(&mut tar, "manifest.json", &json)?;
	let mut throttle = Throttle::new(progress);
	let mut done = 0u64;
	let mut sums = String::new();
	for (disk, (_, path)) in manifest.disks.iter().zip(disks) {
		let mut header = tar::Header::new_gnu();
		header.set_size(disk.size);
		header.set_mode(0o644);
		header.set_cksum();
		let mut reader = HashingReader {
			inner: BufReader::with_capacity(CHUNK, File::open(path)?),
			hasher: Sha256::new(),
			done: &mut done,
			total,
			throttle: &mut throttle,
			cancelled: false,
		};
		let appended = tar.append_data(&mut header, format!("disks/{}", disk.file), &mut reader);
		if reader.cancelled {
			return Err(Error::Cancelled);
		}
		appended?;
		sums.push_str(&format!("{}  disks/{}\n", hex::encode(reader.hasher.finalize()), disk.file));
	}
	append_bytes(&mut tar, "SHA256SUMS", sums.as_bytes())?;
	let encoder = tar.into_inner()?;
	let mut file = encoder.finish()?;
	file.flush()?;
	file.into_inner().map_err(|error| error.into_error())?.sync_all()?;
	Ok(())
}

fn append_bytes<W: Write>(tar: &mut tar::Builder<W>, name: &str, data: &[u8]) -> Result<()> {
	let mut header = tar::Header::new_gnu();
	header.set_size(data.len() as u64);
	header.set_mode(0o644);
	header.set_cksum();
	tar.append_data(&mut header, name, data)?;
	Ok(())
}

struct HashingReader<'a, 'b, R> {
	inner: R,
	hasher: Sha256,
	done: &'a mut u64,
	total: u64,
	throttle: &'a mut Throttle<'b>,
	cancelled: bool,
}

impl<R: Read> Read for HashingReader<'_, '_, R> {
	fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
		let n = self.inner.read(buf)?;
		self.hasher.update(&buf[..n]);
		*self.done += n as u64;
		if self.throttle.report(*self.done, self.total).is_err() {
			self.cancelled = true;
			return Err(io::Error::other("cancelled"));
		}
		Ok(n)
	}
}

/// Disk names are plain file names, so an archive can't write outside the folder it's unpacked into.
fn check_name(name: &str) -> Result<()> {
	if name.is_empty() || name.contains('/') || name.contains('\\') || name.starts_with('.') || name.len() > 100 {
		return Err(Error::format(format!("\"{name}\" isn't a valid disk name")));
	}
	Ok(())
}

/// Reads only the manifest of an archive, without unpacking any disk.
pub fn read_manifest(archive: &Path) -> Result<Manifest> {
	let mut tar = tar::Archive::new(GzDecoder::new(BufReader::new(File::open(archive)?)));
	let mut entries = tar.entries()?;
	let mut first = entries.next().ok_or_else(|| Error::format("The archive is empty"))??;
	if first.path()?.to_str() != Some("manifest.json") {
		return Err(Error::format("This isn't a Rui archive: it doesn't start with manifest.json"));
	}
	parse_manifest(&mut first)
}

fn parse_manifest(reader: &mut dyn Read) -> Result<Manifest> {
	let mut json = Vec::new();
	reader.take(1 << 20).read_to_end(&mut json)?;
	let manifest: Manifest =
		serde_json::from_slice(&json).map_err(|error| Error::format(format!("The manifest is damaged: {error}")))?;
	if manifest.format != FORMAT {
		return Err(Error::format("This isn't a Rui archive"));
	}
	if manifest.version > VERSION {
		return Err(Error::unsupported("This archive comes from a newer Rui. Update Rui to import it."));
	}
	for disk in &manifest.disks {
		check_name(&disk.file)?;
	}
	Ok(manifest)
}

/// Unpacks an archive's disks into `dest_dir`, checking each against `SHA256SUMS`, and returns its manifest.
/// Disks are written sparsely. If anything fails, nothing is left behind.
pub fn import(archive: &Path, dest_dir: &Path, progress: &mut dyn Progress) -> Result<Manifest> {
	std::fs::create_dir_all(dest_dir)?;
	let mut written: Vec<PathBuf> = Vec::new();
	let result = import_inner(archive, dest_dir, progress, &mut written);
	if result.is_err() {
		for path in written {
			let _ = std::fs::remove_file(path);
		}
	}
	result
}

fn import_inner(
	archive: &Path,
	dest_dir: &Path,
	progress: &mut dyn Progress,
	written: &mut Vec<PathBuf>,
) -> Result<Manifest> {
	let file = File::open(archive)?;
	let total = file.metadata()?.len();
	let counter = std::rc::Rc::new(std::cell::Cell::new(0u64));
	let reader = CountingReader { inner: BufReader::with_capacity(CHUNK, file), count: counter.clone() };
	let mut tar = tar::Archive::new(GzDecoder::new(reader));
	let mut throttle = Throttle::new(progress);
	let mut manifest: Option<Manifest> = None;
	let mut hashes: HashMap<String, String> = HashMap::new();
	let mut sums: Option<String> = None;
	for entry in tar.entries()? {
		let mut entry = entry?;
		let path = entry.path()?.to_string_lossy().to_string();
		if path == "manifest.json" {
			manifest = Some(parse_manifest(&mut entry)?);
			continue;
		}
		if path == "SHA256SUMS" {
			let mut text = String::new();
			(&mut entry).take(1 << 20).read_to_string(&mut text)?;
			sums = Some(text);
			continue;
		}
		let Some(name) = path.strip_prefix("disks/") else {
			continue;
		};
		let manifest =
			manifest.as_ref().ok_or_else(|| Error::format("The archive's disks come before its manifest"))?;
		let disk = manifest
			.disks
			.iter()
			.find(|disk| disk.file == name)
			.ok_or_else(|| Error::format(format!("The archive has a disk its manifest doesn't list: {name}")))?;
		let part = dest_dir.join(format!("{}.part", disk.file));
		written.push(part.clone());
		let out = OpenOptions::new().write(true).create(true).truncate(true).open(&part)?;
		let mut hasher = Sha256::new();
		let mut buf = vec![0u8; CHUNK];
		let mut offset = 0u64;
		loop {
			let n = entry.read(&mut buf)?;
			if n == 0 {
				break;
			}
			hasher.update(&buf[..n]);
			if buf[..n].iter().any(|byte| *byte != 0) {
				out.write_all_at(&buf[..n], offset)?;
			}
			offset += n as u64;
			throttle.report(counter.get().min(total), total)?;
		}
		if offset != disk.size {
			return Err(Error::format(format!("{} is cut short", disk.file)));
		}
		out.set_len(disk.size)?;
		out.sync_all()?;
		hashes.insert(format!("disks/{}", disk.file), hex::encode(hasher.finalize()));
	}
	let manifest = manifest.ok_or_else(|| Error::format("This isn't a Rui archive: it has no manifest.json"))?;
	let sums = sums.ok_or_else(|| Error::format("The archive has no SHA256SUMS, so its disks can't be checked"))?;
	let expected: HashMap<&str, &str> =
		sums.lines().filter_map(|line| line.split_once("  ").map(|(hash, name)| (name.trim(), hash.trim()))).collect();
	let mut finals = Vec::new();
	for disk in &manifest.disks {
		let key = format!("disks/{}", disk.file);
		let actual = hashes.get(&key).ok_or_else(|| Error::format(format!("The archive is missing {}", disk.file)))?;
		if expected.get(key.as_str()) != Some(&actual.as_str()) {
			return Err(Error::Checksum { name: disk.file.clone() });
		}
		finals.push((dest_dir.join(format!("{}.part", disk.file)), dest_dir.join(&disk.file)));
	}
	for (part, last) in finals {
		std::fs::rename(&part, &last)?;
		written.retain(|path| path != &part);
		written.push(last);
	}
	throttle.report(total, total)?;
	Ok(manifest)
}

struct CountingReader<R> {
	inner: R,
	count: std::rc::Rc<std::cell::Cell<u64>>,
}

impl<R: Read> Read for CountingReader<R> {
	fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
		let n = self.inner.read(buf)?;
		self.count.set(self.count.get() + n as u64);
		Ok(n)
	}
}

#[cfg(test)]
mod tests {
	use super::*;
	use crate::progress::Quiet;

	fn scratch(name: &str) -> PathBuf {
		let dir = std::env::temp_dir().join(format!("rui-archive-{name}-{}", std::process::id()));
		let _ = std::fs::remove_dir_all(&dir);
		std::fs::create_dir_all(&dir).unwrap();
		dir
	}

	#[test]
	fn round_trips_a_vm() {
		let dir = scratch("round");
		let disk = dir.join("root.img");
		let file = File::create(&disk).unwrap();
		file.set_len(32 << 20).unwrap();
		file.write_all_at(b"guest data", 20 << 20).unwrap();
		let vm = serde_json::json!({ "name": "Test", "memoryMib": 2048 });
		let out = dir.join("test.ruivm");
		export(&vm, &[("root.img".into(), disk.clone())], &out, &mut Quiet).unwrap();
		assert!(std::fs::metadata(&out).unwrap().len() < 1 << 20);
		assert_eq!(read_manifest(&out).unwrap().vm, vm);
		let dest = dir.join("imported");
		let manifest = import(&out, &dest, &mut Quiet).unwrap();
		assert_eq!(manifest.disks, vec![ManifestDisk { file: "root.img".into(), size: 32 << 20 }]);
		let mut back = [0u8; 10];
		File::open(dest.join("root.img")).unwrap().read_exact_at(&mut back, 20 << 20).unwrap();
		assert_eq!(&back, b"guest data");
		std::fs::remove_dir_all(&dir).unwrap();
	}

	#[test]
	fn refuses_a_damaged_disk() {
		let dir = scratch("damaged");
		let disk = dir.join("root.img");
		std::fs::write(&disk, b"original").unwrap();
		let out = dir.join("test.ruivm");
		export(&serde_json::json!({}), &[("root.img".into(), disk)], &out, &mut Quiet).unwrap();
		// Rewrite the archive with the same manifest but different disk bytes and the old checksums.
		let mut sums = String::new();
		let mut tar = tar::Archive::new(GzDecoder::new(File::open(&out).unwrap()));
		let mut manifest = Vec::new();
		for entry in tar.entries().unwrap() {
			let mut entry = entry.unwrap();
			let path = entry.path().unwrap().to_string_lossy().to_string();
			if path == "SHA256SUMS" {
				entry.read_to_string(&mut sums).unwrap();
			} else if path == "manifest.json" {
				entry.read_to_end(&mut manifest).unwrap();
			}
		}
		let bad = dir.join("bad.ruivm");
		let mut builder = tar::Builder::new(GzEncoder::new(File::create(&bad).unwrap(), Compression::fast()));
		append_bytes(&mut builder, "manifest.json", &manifest).unwrap();
		append_bytes(&mut builder, "disks/root.img", b"tampered").unwrap();
		append_bytes(&mut builder, "SHA256SUMS", sums.as_bytes()).unwrap();
		builder.into_inner().unwrap().finish().unwrap();
		let dest = dir.join("imported");
		assert!(matches!(import(&bad, &dest, &mut Quiet), Err(Error::Checksum { .. })));
		assert_eq!(std::fs::read_dir(&dest).unwrap().count(), 0);
		std::fs::remove_dir_all(&dir).unwrap();
	}

	#[test]
	fn refuses_names_that_leave_the_folder() {
		assert!(check_name("../evil").is_err());
		assert!(check_name("root.img").is_ok());
	}
}
