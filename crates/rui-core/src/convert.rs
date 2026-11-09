//! Making VM disks: qcow2 to raw, sparse copies, and growing.
//!
//! Rui keeps every VM disk as a raw, sparse file. crosvm reads raw disks fastest, a raw disk can grow with a
//! single `truncate`, and sparse files only take the space the guest has written.

use crate::disk::{Disk, ImageFormat};
use crate::progress::{Progress, Throttle};
use crate::{Error, Result};
use sha2::{Digest, Sha256};
use std::fs::{File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::unix::fs::FileExt;
use std::path::Path;

const CHUNK: usize = 1 << 20;

/// Writes `src` to `dst` as a raw sparse disk. qcow2 images are converted, raw images and ISOs are copied. Zero
/// blocks are skipped instead of written. Returns the disk's size in bytes. `dst` is removed if this fails.
pub fn to_raw(src: &Path, dst: &Path, progress: &mut dyn Progress) -> Result<u64> {
	let result = to_raw_inner(src, dst, progress);
	if result.is_err() {
		let _ = std::fs::remove_file(dst);
	}
	result
}

fn to_raw_inner(src: &Path, dst: &Path, progress: &mut dyn Progress) -> Result<u64> {
	let mut disk = Disk::open(src)?;
	let size = disk.size();
	let out = OpenOptions::new().write(true).create(true).truncate(true).open(dst)?;
	let mut throttle = Throttle::new(progress);
	if disk.format() == ImageFormat::Qcow2 {
		let image = disk.qcow2().ok_or_else(|| Error::format("qcow2 image went missing"))?;
		let cluster = image.cluster_size();
		let mut buf = vec![0u8; cluster as usize];
		let clusters = size.div_ceil(cluster);
		for index in 0..clusters {
			if image.read_cluster(index, &mut buf)? && buf.iter().any(|byte| *byte != 0) {
				let offset = index * cluster;
				let take = (size - offset).min(cluster) as usize;
				out.write_all_at(&buf[..take], offset)?;
			}
			throttle.report(((index + 1) * cluster).min(size), size)?;
		}
	} else {
		let mut buf = vec![0u8; CHUNK];
		let mut offset = 0u64;
		while offset < size {
			let take = (size - offset).min(CHUNK as u64) as usize;
			disk.read_at(offset, &mut buf[..take])?;
			if buf[..take].iter().any(|byte| *byte != 0) {
				out.write_all_at(&buf[..take], offset)?;
			}
			offset += take as u64;
			throttle.report(offset, size)?;
		}
	}
	out.set_len(size)?;
	out.sync_all()?;
	Ok(size)
}

/// Grows a raw disk to `new_size` bytes. Shrinking is refused, because it would cut off whatever the guest keeps
/// at the end. The guest still has to grow its partition and filesystem; cloud images do that on boot.
pub fn grow(path: &Path, new_size: u64) -> Result<()> {
	let file = OpenOptions::new().write(true).open(path)?;
	let format = ImageFormat::detect(&file)?;
	if format != ImageFormat::Raw {
		return Err(Error::unsupported("Only raw disks can be resized"));
	}
	let current = file.metadata()?.len();
	if new_size < current {
		return Err(Error::unsupported("Disks can only grow. Shrinking would cut off the end of the guest's data."));
	}
	file.set_len(new_size)?;
	Ok(())
}

/// Creates an empty raw disk of `size` bytes. It takes no space until the guest writes to it.
pub fn create_blank(path: &Path, size: u64) -> Result<()> {
	let file = OpenOptions::new().write(true).create_new(true).open(path)?;
	file.set_len(size)?;
	Ok(())
}

/// Copies `src` into `dst`, reporting progress and returning the SHA-256 of what was copied, as hex. Used for
/// images coming in from elsewhere, so a damaged download is noticed. `dst` is removed if this fails.
pub fn copy_with_sha256(src: &mut dyn Read, total: u64, dst: &Path, progress: &mut dyn Progress) -> Result<String> {
	let result = (|| {
		let mut out = File::create(dst)?;
		let mut hasher = Sha256::new();
		let mut buf = vec![0u8; CHUNK];
		let mut done = 0u64;
		let mut throttle = Throttle::new(progress);
		loop {
			let n = match src.read(&mut buf) {
				Ok(0) => break,
				Ok(n) => n,
				Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
				Err(error) => return Err(error.into()),
			};
			hasher.update(&buf[..n]);
			out.write_all(&buf[..n])?;
			done += n as u64;
			throttle.report(done, total.max(done))?;
		}
		out.sync_all()?;
		Ok(hex::encode(hasher.finalize()))
	})();
	if result.is_err() {
		let _ = std::fs::remove_file(dst);
	}
	result
}

/// The SHA-256 of a file, as hex.
pub fn sha256_file(path: &Path, progress: &mut dyn Progress) -> Result<String> {
	let mut file = File::open(path)?;
	let total = file.metadata()?.len();
	let mut hasher = Sha256::new();
	let mut buf = vec![0u8; CHUNK];
	let mut done = 0u64;
	let mut throttle = Throttle::new(progress);
	loop {
		let n = file.read(&mut buf)?;
		if n == 0 {
			break;
		}
		hasher.update(&buf[..n]);
		done += n as u64;
		throttle.report(done, total)?;
	}
	Ok(hex::encode(hasher.finalize()))
}

/// How much of a file's size is really on disk, in bytes. Sparse disks take less than their size.
pub fn allocated_bytes(path: &Path) -> Result<u64> {
	use std::os::unix::fs::MetadataExt;
	Ok(std::fs::metadata(path)?.blocks() * 512)
}

#[cfg(test)]
mod tests {
	use super::*;
	use crate::progress::Quiet;

	#[test]
	fn copies_raw_disks_sparsely() {
		let dir = std::env::temp_dir().join(format!("rui-convert-{}", std::process::id()));
		std::fs::create_dir_all(&dir).unwrap();
		let src = dir.join("src.img");
		let dst = dir.join("dst.img");
		let file = File::create(&src).unwrap();
		file.set_len(8 << 20).unwrap();
		file.write_all_at(b"hello", 3 << 20).unwrap();
		let size = to_raw(&src, &dst, &mut Quiet).unwrap();
		assert_eq!(size, 8 << 20);
		let mut back = [0u8; 5];
		File::open(&dst).unwrap().read_exact_at(&mut back, 3 << 20).unwrap();
		assert_eq!(&back, b"hello");
		// APFS fills small holes in, so only Linux, which the phone runs, is held to it.
		if cfg!(target_os = "linux") {
			assert!(allocated_bytes(&dst).unwrap() < 4 << 20);
		}
		grow(&dst, 16 << 20).unwrap();
		assert_eq!(std::fs::metadata(&dst).unwrap().len(), 16 << 20);
		assert!(grow(&dst, 1 << 20).is_err());
		std::fs::remove_dir_all(&dir).unwrap();
	}

	#[test]
	fn cancelling_removes_the_half_written_disk() {
		let dir = std::env::temp_dir().join(format!("rui-cancel-{}", std::process::id()));
		std::fs::create_dir_all(&dir).unwrap();
		let src = dir.join("src.img");
		let dst = dir.join("dst.img");
		File::create(&src).unwrap().set_len(64 << 20).unwrap();
		let result = to_raw(&src, &dst, &mut |_done, _total| false);
		assert!(matches!(result, Err(Error::Cancelled)));
		assert!(!dst.exists());
		std::fs::remove_dir_all(&dir).unwrap();
	}
}
