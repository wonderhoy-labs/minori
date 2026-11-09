//! Opening a disk image as one address space, whatever its format, and reading parts of it.

use crate::qcow2::{self, Qcow2};
use crate::{Error, Result};
use serde::{Deserialize, Serialize};
use std::cell::RefCell;
use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::os::unix::fs::FileExt;
use std::path::Path;
use std::rc::Rc;

/// The container format of an image file.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ImageFormat {
	/// A plain disk, byte for byte. `.img` and `.raw` files.
	Raw,
	/// QEMU's copy-on-write format. Most cloud images are qcow2.
	Qcow2,
	/// An ISO 9660 CD or DVD image, such as an installer.
	Iso,
}

impl ImageFormat {
	/// Works out an image's format from its first bytes, read from the file.
	pub fn detect(file: &File) -> Result<Self> {
		let mut magic = [0u8; 4];
		if file.read_exact_at(&mut magic, 0).is_ok() && &magic == qcow2::MAGIC {
			return Ok(ImageFormat::Qcow2);
		}
		let mut iso = [0u8; 5];
		if file.read_exact_at(&mut iso, 0x8001).is_ok() && &iso == b"CD001" {
			return Ok(ImageFormat::Iso);
		}
		Ok(ImageFormat::Raw)
	}
}

enum Backing {
	File(File),
	Qcow2(Box<Qcow2>),
}

/// An open disk image, read as the disk a guest would see.
pub struct Disk {
	backing: Backing,
	format: ImageFormat,
	size: u64,
}

impl Disk {
	/// Opens an image, detecting its format.
	pub fn open(path: &Path) -> Result<Self> {
		let file = File::open(path)?;
		let format = ImageFormat::detect(&file)?;
		match format {
			ImageFormat::Qcow2 => {
				let image = Qcow2::open(file)?;
				let size = image.size();
				Ok(Disk { backing: Backing::Qcow2(Box::new(image)), format, size })
			}
			_ => {
				let size = file.metadata()?.len();
				Ok(Disk { backing: Backing::File(file), format, size })
			}
		}
	}

	/// The image's container format.
	pub fn format(&self) -> ImageFormat {
		self.format
	}

	/// The size of the disk the guest sees, in bytes.
	pub fn size(&self) -> u64 {
		self.size
	}

	/// Fills `buf` from `offset`. Reading past the end gives zeros.
	pub fn read_at(&mut self, offset: u64, buf: &mut [u8]) -> Result<()> {
		match &mut self.backing {
			Backing::Qcow2(image) => image.read_at(offset, buf),
			Backing::File(file) => {
				let mut done = 0;
				while done < buf.len() {
					match file.read_at(&mut buf[done..], offset + done as u64) {
						Ok(0) => break,
						Ok(n) => done += n,
						Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
						Err(error) => return Err(error.into()),
					}
				}
				buf[done..].fill(0);
				Ok(())
			}
		}
	}

	pub(crate) fn qcow2(&mut self) -> Option<&mut Qcow2> {
		match &mut self.backing {
			Backing::Qcow2(image) => Some(image),
			Backing::File(_) => None,
		}
	}
}

/// A disk shared by the readers of its partitions.
pub(crate) type SharedDisk = Rc<RefCell<Disk>>;

/// A window onto part of a disk, readable with `Read` and `Seek`, and by ext4-view. Writes fail, so a filesystem
/// library can never change an image it's only inspecting.
pub(crate) struct Slice {
	disk: SharedDisk,
	start: u64,
	len: u64,
	pos: u64,
}

impl Slice {
	pub(crate) fn new(disk: SharedDisk, start: u64, len: u64) -> Self {
		Slice { disk, start, len, pos: 0 }
	}

	pub(crate) fn read_at(&self, offset: u64, buf: &mut [u8]) -> Result<()> {
		if offset.saturating_add(buf.len() as u64) > self.len {
			return Err(Error::format("A read went past the end of a partition"));
		}
		self.disk.borrow_mut().read_at(self.start + offset, buf)
	}
}

impl Read for Slice {
	fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
		let left = self.len.saturating_sub(self.pos);
		let take = (buf.len() as u64).min(left) as usize;
		if take == 0 {
			return Ok(0);
		}
		self.disk.borrow_mut().read_at(self.start + self.pos, &mut buf[..take]).map_err(io::Error::other)?;
		self.pos += take as u64;
		Ok(take)
	}
}

impl Seek for Slice {
	fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
		let next = match pos {
			SeekFrom::Start(offset) => Some(offset),
			SeekFrom::End(delta) => self.len.checked_add_signed(delta),
			SeekFrom::Current(delta) => self.pos.checked_add_signed(delta),
		};
		self.pos = next.ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "seek out of range"))?;
		Ok(self.pos)
	}
}

impl Write for Slice {
	fn write(&mut self, _buf: &[u8]) -> io::Result<usize> {
		Err(io::Error::new(io::ErrorKind::PermissionDenied, "Rui only reads images it inspects"))
	}

	fn flush(&mut self) -> io::Result<()> {
		Ok(())
	}
}

impl ext4_view::Ext4Read for Slice {
	fn read(
		&mut self,
		start_byte: u64,
		dst: &mut [u8],
	) -> std::result::Result<(), Box<dyn std::error::Error + Send + Sync + 'static>> {
		self.read_at(start_byte, dst).map_err(|error| error.to_string().into())
	}
}
