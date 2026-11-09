//! A small ISO 9660 reader, with Rock Ridge names, for finding an installer's kernel and boot files.

use crate::disk::Disk;
use crate::{Error, Result};

const SECTOR: u64 = 2048;
const MAX_FILES: usize = 20_000;
const MAX_DEPTH: usize = 6;

#[derive(Clone, Debug)]
pub(crate) struct IsoFile {
	/// Lowercase, starting with `/`.
	pub path: String,
	pub lba: u32,
	pub size: u32,
	pub dir: bool,
}

pub(crate) struct IsoTree {
	pub volume_id: String,
	pub files: Vec<IsoFile>,
}

impl IsoTree {
	pub(crate) fn find(&self, path: &str) -> Option<&IsoFile> {
		self.files.iter().find(|file| !file.dir && file.path == path)
	}

	pub(crate) fn read(&self, disk: &mut Disk, file: &IsoFile) -> Result<Vec<u8>> {
		let mut data = vec![0u8; file.size as usize];
		disk.read_at(u64::from(file.lba) * SECTOR, &mut data)?;
		Ok(data)
	}
}

fn le32(bytes: &[u8], at: usize) -> u32 {
	u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap_or_default())
}

pub(crate) fn read_tree(disk: &mut Disk) -> Result<IsoTree> {
	let mut pvd = [0u8; SECTOR as usize];
	disk.read_at(16 * SECTOR, &mut pvd)?;
	if pvd[0] != 1 || &pvd[1..6] != b"CD001" {
		return Err(Error::format("This ISO has no primary volume descriptor"));
	}
	let volume_id = String::from_utf8_lossy(&pvd[40..72]).trim().to_string();
	let root = &pvd[156..190];
	let mut files = Vec::new();
	walk(disk, le32(root, 2), le32(root, 10), "", 0, &mut files)?;
	Ok(IsoTree { volume_id, files })
}

fn walk(disk: &mut Disk, lba: u32, size: u32, prefix: &str, depth: usize, files: &mut Vec<IsoFile>) -> Result<()> {
	if depth > MAX_DEPTH || size > 16 << 20 {
		return Ok(());
	}
	let mut data = vec![0u8; size as usize];
	disk.read_at(u64::from(lba) * SECTOR, &mut data)?;
	let mut at = 0usize;
	let mut children = Vec::new();
	while at < data.len() && files.len() < MAX_FILES {
		let len = data[at] as usize;
		if len == 0 {
			// Records never cross a sector boundary; skip to the next one.
			at = (at / SECTOR as usize + 1) * SECTOR as usize;
			continue;
		}
		if at + len > data.len() || len < 34 {
			break;
		}
		let record = &data[at..at + len];
		at += len;
		let name_len = record[32] as usize;
		if 33 + name_len > record.len() {
			continue;
		}
		let raw_name = &record[33..33 + name_len];
		if raw_name == [0] || raw_name == [1] {
			continue;
		}
		let dir = record[25] & 2 != 0;
		let name = rock_ridge_name(record, name_len).unwrap_or_else(|| plain_name(raw_name));
		let path = format!("{prefix}/{}", name.to_lowercase());
		let file = IsoFile { path, lba: le32(record, 2), size: le32(record, 10), dir };
		if dir {
			children.push(file.clone());
		}
		files.push(file);
	}
	for child in children {
		walk(disk, child.lba, child.size, &child.path, depth + 1, files)?;
	}
	Ok(())
}

fn plain_name(raw: &[u8]) -> String {
	let name = String::from_utf8_lossy(raw);
	let name = name.split(';').next().unwrap_or("");
	name.trim_end_matches('.').to_string()
}

/// The Rock Ridge `NM` entry in a record's system use area, if there is one.
fn rock_ridge_name(record: &[u8], name_len: usize) -> Option<String> {
	let mut at = 33 + name_len;
	if at % 2 == 1 {
		at += 1;
	}
	let mut name = String::new();
	while at + 4 <= record.len() {
		let len = record[at + 2] as usize;
		if len < 4 || at + len > record.len() {
			break;
		}
		if &record[at..at + 2] == b"NM" && len > 5 {
			name.push_str(&String::from_utf8_lossy(&record[at + 5..at + len]));
		}
		at += len;
	}
	if name.is_empty() { None } else { Some(name) }
}
