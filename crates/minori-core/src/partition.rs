//! GPT and MBR partition tables.

use crate::Result;
use crate::disk::Disk;

const SECTOR: u64 = 512;

/// One partition, as the table describes it.
#[derive(Clone, Debug)]
pub(crate) struct RawPartition {
	/// 1-based, as Linux numbers them (`/dev/vda1`).
	pub number: u32,
	pub start: u64,
	pub size: u64,
	/// GPT: the type GUID. MBR: the type byte as two hex digits.
	pub type_id: String,
	/// GPT only: the partition's own GUID, which `root=PARTUUID=` names.
	pub unique_id: Option<String>,
	pub name: String,
}

pub(crate) enum Table {
	Gpt(Vec<RawPartition>),
	Mbr(Vec<RawPartition>),
	None,
}

#[cfg(test)]
pub(crate) const GPT_ESP: &str = "c12a7328-f81f-11d2-ba4b-00a0c93ec93b";
pub(crate) const GPT_ROOT_AARCH64: &str = "b921b045-1df0-41c3-af44-4c6f280d3fae";
pub(crate) const GPT_ROOT_X86_64: &str = "4f68bce3-e8cd-4db1-96e7-fbcaf984b709";
pub(crate) const GPT_MICROSOFT_BASIC: &str = "ebd0a0a2-b9e5-4433-87c0-68b6b72699c7";

fn le16(bytes: &[u8], at: usize) -> u16 {
	u16::from_le_bytes([bytes[at], bytes[at + 1]])
}

fn le32(bytes: &[u8], at: usize) -> u32 {
	u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap_or_default())
}

fn le64(bytes: &[u8], at: usize) -> u64 {
	u64::from_le_bytes(bytes[at..at + 8].try_into().unwrap_or_default())
}

/// Formats a GUID stored in GPT's mixed-endian layout.
pub(crate) fn guid(bytes: &[u8]) -> String {
	format!(
		"{:08x}-{:04x}-{:04x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
		le32(bytes, 0),
		le16(bytes, 4),
		le16(bytes, 6),
		bytes[8],
		bytes[9],
		bytes[10],
		bytes[11],
		bytes[12],
		bytes[13],
		bytes[14],
		bytes[15]
	)
}

pub(crate) fn read(disk: &mut Disk) -> Result<Table> {
	let mut sector = [0u8; SECTOR as usize];
	disk.read_at(SECTOR, &mut sector)?;
	if &sector[..8] == b"EFI PART" {
		return read_gpt(disk, &sector).map(Table::Gpt);
	}
	disk.read_at(0, &mut sector)?;
	if sector[510] != 0x55 || sector[511] != 0xaa {
		return Ok(Table::None);
	}
	// A FAT or NTFS boot sector also ends in 55 AA, but has no partition entries that make sense.
	if &sector[3..11] == b"NTFS    " || &sector[0x36..0x39] == b"FAT" || &sector[0x52..0x55] == b"FAT" {
		return Ok(Table::None);
	}
	let mut partitions = Vec::new();
	for index in 0..4 {
		let entry = &sector[446 + index * 16..446 + (index + 1) * 16];
		let kind = entry[4];
		let start = u64::from(le32(entry, 8)) * SECTOR;
		let size = u64::from(le32(entry, 12)) * SECTOR;
		if kind == 0 || size == 0 {
			continue;
		}
		partitions.push(RawPartition {
			number: index as u32 + 1,
			start,
			size,
			type_id: format!("{kind:02x}"),
			unique_id: None,
			name: String::new(),
		});
	}
	if partitions.is_empty() { Ok(Table::None) } else { Ok(Table::Mbr(partitions)) }
}

fn read_gpt(disk: &mut Disk, header: &[u8]) -> Result<Vec<RawPartition>> {
	let entries_lba = le64(header, 72);
	let count = le32(header, 80).min(256) as usize;
	let entry_size = le32(header, 84).clamp(128, 512) as usize;
	let mut raw = vec![0u8; count * entry_size];
	disk.read_at(entries_lba * SECTOR, &mut raw)?;
	let mut partitions = Vec::new();
	for (index, entry) in raw.chunks_exact(entry_size).enumerate() {
		if entry[..16].iter().all(|byte| *byte == 0) {
			continue;
		}
		let first = le64(entry, 32);
		let last = le64(entry, 40);
		if last < first {
			continue;
		}
		let name: Vec<u16> = entry[56..128]
			.as_chunks::<2>()
			.0
			.iter()
			.map(|pair| u16::from_le_bytes(*pair))
			.take_while(|unit| *unit != 0)
			.collect();
		partitions.push(RawPartition {
			number: index as u32 + 1,
			start: first * SECTOR,
			size: (last - first + 1) * SECTOR,
			type_id: guid(&entry[..16]),
			unique_id: Some(guid(&entry[16..32])),
			name: String::from_utf16_lossy(&name),
		});
	}
	Ok(partitions)
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn formats_mixed_endian_guids() {
		// The EFI system partition's type GUID as GPT stores it.
		let bytes = [0x28, 0x73, 0x2a, 0xc1, 0x1f, 0xf8, 0xd2, 0x11, 0xba, 0x4b, 0x00, 0xa0, 0xc9, 0x3e, 0xc9, 0x3b];
		assert_eq!(guid(&bytes), GPT_ESP);
	}
}
