//! Recognising filesystems by their superblocks.

use crate::Result;
use crate::disk::Slice;
use serde::Serialize;

/// A filesystem Minori can recognise.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Filesystem {
	/// ext2, ext3 or ext4. Minori can read these.
	Ext4,
	/// FAT12, FAT16 or FAT32, usually an EFI system partition. Minori can read these.
	Vfat,
	/// Btrfs, which Fedora uses for its root.
	Btrfs,
	/// XFS.
	Xfs,
	/// NTFS, which means Windows.
	Ntfs,
	/// Linux swap.
	Swap,
	/// An encrypted LUKS volume.
	Luks,
	/// ISO 9660.
	Iso9660,
}

pub(crate) fn detect(slice: &Slice, len: u64) -> Result<Option<Filesystem>> {
	let mut head = vec![0u8; 4096.min(len) as usize];
	slice.read_at(0, &mut head)?;
	if head.len() >= 1082 && head[1080] == 0x53 && head[1081] == 0xef {
		return Ok(Some(Filesystem::Ext4));
	}
	if head.len() >= 512 {
		if &head[3..11] == b"NTFS    " {
			return Ok(Some(Filesystem::Ntfs));
		}
		if head[510] == 0x55 && head[511] == 0xaa && (&head[0x36..0x39] == b"FAT" || &head[0x52..0x55] == b"FAT") {
			return Ok(Some(Filesystem::Vfat));
		}
		if &head[..4] == b"XFSB" {
			return Ok(Some(Filesystem::Xfs));
		}
		if &head[..6] == b"LUKS\xba\xbe" {
			return Ok(Some(Filesystem::Luks));
		}
	}
	if head.len() >= 4096 && &head[4086..4096] == b"SWAPSPACE2" {
		return Ok(Some(Filesystem::Swap));
	}
	if len > 0x10048 {
		let mut magic = [0u8; 8];
		slice.read_at(0x10040, &mut magic)?;
		if &magic == b"_BHRfS_M" {
			return Ok(Some(Filesystem::Btrfs));
		}
	}
	if len > 0x8006 {
		let mut magic = [0u8; 5];
		slice.read_at(0x8001, &mut magic)?;
		if &magic == b"CD001" {
			return Ok(Some(Filesystem::Iso9660));
		}
	}
	Ok(None)
}
