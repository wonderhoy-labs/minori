//! Working out what an image is and whether Minori can boot it.
//!
//! Android's Virtualization Framework runs crosvm without PC-style firmware, so a guest starts from a Linux kernel
//! and initrd that the host hands it. [`inspect`] finds them: in `/boot` of an ext4 root or boot partition, from
//! the image's own GRUB or Boot Loader Spec entries, or at the well-known paths of installer ISOs.

use crate::disk::{Disk, ImageFormat, SharedDisk, Slice};
use crate::fsdetect::{self, Filesystem};
use crate::iso;
use crate::partition::{self, RawPartition, Table};
use crate::{Error, Result};
use ext4_view::Ext4;
use serde::{Deserialize, Serialize};
use std::cell::RefCell;
use std::path::Path;
use std::rc::Rc;

pub use crate::fsdetect::Filesystem as FilesystemKind;

/// A CPU architecture a guest can be built for.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Arch {
	/// 64-bit Arm. The only kind a Pixel can run.
	Aarch64,
	/// 64-bit x86, as in most desktop ISOs. It can't run on a phone.
	X86_64,
	/// The evidence was missing or contradictory.
	Unknown,
}

/// What Minori can do with an image.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Verdict {
	/// A 64-bit Arm Linux kernel was found, so Minori can boot it.
	Bootable,
	/// It's for a PC or another CPU, so it can't run here.
	WrongArch,
	/// It's Windows, which Minori can't boot.
	Windows,
	/// No kernel was found. It can still be attached to a VM as a data disk.
	DataOnly,
}

/// One partition, with what's on it.
#[derive(Clone, Debug, Serialize)]
pub struct Partition {
	/// 1-based, as `/dev/vda1` counts.
	pub number: u32,
	/// Offset from the start of the disk, in bytes.
	pub start: u64,
	/// Size in bytes.
	pub size: u64,
	/// The GPT type GUID, or the MBR type byte in hex.
	pub type_id: String,
	/// The GPT partition GUID, which `root=PARTUUID=` uses.
	pub unique_id: Option<String>,
	/// The GPT partition name.
	pub name: String,
	/// The filesystem on it, if recognised.
	pub filesystem: Option<Filesystem>,
	/// The filesystem's label.
	pub label: Option<String>,
	/// The filesystem's UUID.
	pub uuid: Option<String>,
}

/// Where the kernel to boot lives.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum BootSource {
	/// In the ext4 filesystem on this partition.
	Partition {
		/// The partition's number.
		number: u32,
	},
	/// In an ext4 filesystem that fills the whole disk, with no partition table.
	Whole,
	/// In the ISO 9660 filesystem.
	Iso,
}

/// How to boot an image: which kernel and initrd, and the kernel command line.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct BootPlan {
	/// The filesystem the kernel and initrd are in.
	pub source: BootSource,
	/// The kernel's path in that filesystem.
	pub kernel: String,
	/// The initrd's path in that filesystem, if it has one.
	pub initrd: Option<String>,
	/// The command line to start the kernel with, ready for the console Minori gives a VM.
	pub cmdline: String,
	/// What the kernel is, in words, such as "Linux 6.12.48+deb13-arm64 from partition 1".
	pub description: String,
}

/// Everything [`inspect`] found out about an image.
#[derive(Clone, Debug, Serialize)]
pub struct Report {
	/// The container format.
	pub format: ImageFormat,
	/// The file's size on disk, in bytes.
	pub file_size: u64,
	/// The size of the disk a guest sees, in bytes.
	pub virtual_size: u64,
	/// `"gpt"`, `"mbr"`, or none.
	pub partition_table: Option<String>,
	/// The partitions, in table order.
	pub partitions: Vec<Partition>,
	/// The filesystem on the whole disk, for images without a partition table.
	pub filesystem: Option<Filesystem>,
	/// The CPU the image is for.
	pub arch: Arch,
	/// Why Minori thinks so, one fact per entry.
	pub evidence: Vec<String>,
	/// The operating system's name, from `/etc/os-release` or the ISO's volume name.
	pub os: Option<String>,
	/// How to boot it, if Minori found a kernel.
	pub boot: Option<BootPlan>,
	/// What Minori can do with it.
	pub verdict: Verdict,
	/// Anything worth telling the user, such as a filesystem Minori couldn't read.
	pub notes: Vec<String>,
}

/// The console devices every boot plan names. AVF's interactive console is the first UART (`ttyS0`) and its log
/// is a virtio console (`hvc0`). The last `console=` becomes `/dev/console`, where the login prompt appears.
pub const CONSOLE_ARGS: &str = "console=hvc0 console=ttyS0";

/// Inspects the image at `path`. Nothing is written.
pub fn inspect(path: &Path) -> Result<Report> {
	let file_size = std::fs::metadata(path)?.len();
	let disk = Disk::open(path)?;
	let format = disk.format();
	let virtual_size = disk.size();
	let disk: SharedDisk = Rc::new(RefCell::new(disk));
	let mut report = Report {
		format,
		file_size,
		virtual_size,
		partition_table: None,
		partitions: Vec::new(),
		filesystem: None,
		arch: Arch::Unknown,
		evidence: Vec::new(),
		os: None,
		boot: None,
		verdict: Verdict::DataOnly,
		notes: Vec::new(),
	};
	let mut facts = Facts::default();
	if format == ImageFormat::Iso {
		inspect_iso(&disk, &mut report, &mut facts)?;
	} else {
		inspect_disk(&disk, &mut report, &mut facts)?;
	}
	report.arch = facts.arch();
	report.evidence = facts.evidence;
	report.verdict = if facts.windows {
		Verdict::Windows
	} else if report.arch == Arch::X86_64 {
		Verdict::WrongArch
	} else if report.boot.is_some() {
		Verdict::Bootable
	} else {
		Verdict::DataOnly
	};
	if report.verdict == Verdict::WrongArch {
		report.boot = None;
	}
	Ok(report)
}

#[derive(Default)]
struct Facts {
	arm: u32,
	x86: u32,
	windows: bool,
	evidence: Vec<String>,
}

impl Facts {
	fn add(&mut self, arch: Arch, why: impl Into<String>) {
		match arch {
			Arch::Aarch64 => self.arm += 1,
			Arch::X86_64 => self.x86 += 1,
			Arch::Unknown => {}
		}
		self.evidence.push(why.into());
	}

	fn arch(&self) -> Arch {
		match (self.arm, self.x86) {
			(0, 0) => Arch::Unknown,
			(_, 0) => Arch::Aarch64,
			(0, _) => Arch::X86_64,
			(arm, x86) if arm > x86 => Arch::Aarch64,
			(arm, x86) if x86 > arm => Arch::X86_64,
			_ => Arch::Unknown,
		}
	}
}

fn inspect_disk(disk: &SharedDisk, report: &mut Report, facts: &mut Facts) -> Result<()> {
	let table = partition::read(&mut disk.borrow_mut())?;
	let (kind, raw) = match table {
		Table::Gpt(partitions) => (Some("gpt"), partitions),
		Table::Mbr(partitions) => (Some("mbr"), partitions),
		Table::None => (None, Vec::new()),
	};
	report.partition_table = kind.map(str::to_string);
	if raw.is_empty() {
		let size = report.virtual_size;
		let slice = Slice::new(disk.clone(), 0, size);
		report.filesystem = fsdetect::detect(&slice, size)?;
		if report.filesystem == Some(Filesystem::Ext4) {
			let found = read_ext4(Slice::new(disk.clone(), 0, size), facts, &mut report.notes, "the disk");
			if let Some(found) = found {
				report.os = found.os.clone();
				report.boot = plan_from(&[(BootSource::Whole, found)], None, None);
			}
		} else if report.filesystem == Some(Filesystem::Ntfs) {
			facts.windows = true;
			facts.add(Arch::Unknown, "The disk is NTFS, which is Windows");
		}
		return Ok(());
	}
	let mut ext4s: Vec<(BootSource, Ext4Facts)> = Vec::new();
	let mut root_partuuid = None;
	for part in &raw {
		let mut info = describe(part);
		let slice = Slice::new(disk.clone(), part.start, part.size);
		info.filesystem = fsdetect::detect(&slice, part.size).unwrap_or(None);
		match part.type_id.as_str() {
			partition::GPT_ROOT_AARCH64 => facts.add(Arch::Aarch64, "A partition has the 64-bit Arm root type"),
			partition::GPT_ROOT_X86_64 => facts.add(Arch::X86_64, "A partition has the x86-64 root type"),
			partition::GPT_MICROSOFT_BASIC | "07" if info.filesystem == Some(Filesystem::Ntfs) => {
				facts.windows = true;
				facts.add(Arch::Unknown, "A partition is NTFS, which is Windows");
			}
			_ => {}
		}
		match info.filesystem {
			Some(Filesystem::Ext4) => {
				let where_ = format!("partition {}", part.number);
				let slice = Slice::new(disk.clone(), part.start, part.size);
				if let Some(found) = read_ext4(slice, facts, &mut report.notes, &where_) {
					info.label = found.label.clone();
					info.uuid = found.uuid.clone();
					if found.os.is_some() {
						report.os = found.os.clone();
						root_partuuid = part.unique_id.clone();
					}
					ext4s.push((BootSource::Partition { number: part.number }, found));
				}
			}
			Some(Filesystem::Vfat) => {
				let slice = Slice::new(disk.clone(), part.start, part.size);
				read_esp(slice, facts, &mut info);
			}
			Some(Filesystem::Btrfs) => report.notes.push(format!(
				"Partition {} is Btrfs. Minori can't read Btrfs, so it looks for the kernel on a separate /boot.",
				part.number
			)),
			Some(Filesystem::Luks) => {
				report.notes.push(format!("Partition {} is encrypted, so Minori can't look inside it.", part.number))
			}
			_ => {}
		}
		report.partitions.push(info);
	}
	let root_fallback = root_partuuid.map(|uuid| format!("root=PARTUUID={uuid}"));
	report.boot = plan_from(&ext4s, root_fallback, report.os.as_deref());
	Ok(())
}

fn describe(part: &RawPartition) -> Partition {
	Partition {
		number: part.number,
		start: part.start,
		size: part.size,
		type_id: part.type_id.clone(),
		unique_id: part.unique_id.clone(),
		name: part.name.clone(),
		filesystem: None,
		label: None,
		uuid: None,
	}
}

/// What one ext4 filesystem told us.
#[derive(Default, Clone)]
struct Ext4Facts {
	label: Option<String>,
	uuid: Option<String>,
	os: Option<String>,
	/// (kernel, initrd, version), newest first.
	kernels: Vec<(String, Option<String>, String)>,
	/// Command-line arguments from the image's own boot loader configuration.
	args: Option<String>,
}

fn read_ext4(slice: Slice, facts: &mut Facts, notes: &mut Vec<String>, where_: &str) -> Option<Ext4Facts> {
	let fs = match Ext4::load(Box::new(slice)) {
		Ok(fs) => fs,
		Err(error) => {
			notes.push(format!("Minori couldn't read the ext4 filesystem on {where_}: {error}"));
			return None;
		}
	};
	let mut found = Ext4Facts {
		label: fs.label().to_str().ok().filter(|label| !label.is_empty()).map(str::to_string),
		uuid: Some(fs.uuid().to_string()),
		..Ext4Facts::default()
	};
	if let Ok(text) = fs.read_to_string("/etc/os-release").or_else(|_| fs.read_to_string("/usr/lib/os-release")) {
		found.os = os_release_name(&text);
	}
	for (path, arch) in [
		("/usr/lib/ld-linux-aarch64.so.1", Arch::Aarch64),
		("/lib/ld-linux-aarch64.so.1", Arch::Aarch64),
		("/usr/lib64/ld-linux-x86-64.so.2", Arch::X86_64),
		("/lib64/ld-linux-x86-64.so.2", Arch::X86_64),
	] {
		if fs.exists(path).unwrap_or(false) {
			facts.add(arch, format!("{where_} has {path}"));
			break;
		}
	}
	// A root filesystem keeps kernels in /boot; a separate boot partition keeps them at its top.
	for dir in ["/boot", "/"] {
		let kernels = find_kernels(&fs, dir);
		if !kernels.is_empty() {
			found.kernels = kernels;
			break;
		}
	}
	found.args = boot_loader_args(&fs);
	Some(found)
}

fn os_release_name(text: &str) -> Option<String> {
	let field = |key: &str| {
		text.lines()
			.find_map(|line| line.strip_prefix(key).and_then(|rest| rest.strip_prefix('=')))
			.map(|value| value.trim().trim_matches('"').to_string())
	};
	field("PRETTY_NAME").or_else(|| field("NAME"))
}

fn find_kernels(fs: &Ext4, dir: &str) -> Vec<(String, Option<String>, String)> {
	let Ok(entries) = fs.read_dir(dir) else {
		return Vec::new();
	};
	let names: Vec<String> = entries
		.filter_map(|entry| entry.ok())
		.filter_map(|entry| entry.file_name().as_str().ok().map(str::to_string))
		.collect();
	let join = |name: &str| if dir == "/" { format!("/{name}") } else { format!("{dir}/{name}") };
	let mut kernels: Vec<(String, Option<String>, String)> = names
		.iter()
		.filter_map(|name| {
			let version = name.strip_prefix("vmlinuz-").or_else(|| name.strip_prefix("vmlinux-"))?;
			if version.contains("rescue") {
				return None;
			}
			let initrd = [
				format!("initrd.img-{version}"),
				format!("initramfs-{version}.img"),
				format!("initrd-{version}"),
				format!("initramfs-{version}"),
			]
			.into_iter()
			.find(|candidate| names.contains(candidate))
			.map(|name| join(&name));
			Some((join(name), initrd, version.to_string()))
		})
		.collect();
	kernels.sort_by(|a, b| version_cmp(&b.2, &a.2));
	kernels
}

/// Compares version strings piece by piece, numbers as numbers.
fn version_cmp(a: &str, b: &str) -> std::cmp::Ordering {
	fn pieces(text: &str) -> Vec<(bool, String)> {
		let mut out: Vec<(bool, String)> = Vec::new();
		for ch in text.chars() {
			let digit = ch.is_ascii_digit();
			match out.last_mut() {
				Some((last_digit, piece)) if *last_digit == digit => piece.push(ch),
				_ => out.push((digit, ch.to_string())),
			}
		}
		out
	}
	for (x, y) in pieces(a).iter().zip(pieces(b).iter()) {
		let ordering = if x.0 && y.0 {
			x.1.parse::<u64>().unwrap_or(0).cmp(&y.1.parse::<u64>().unwrap_or(0))
		} else {
			x.1.cmp(&y.1)
		};
		if ordering.is_ne() {
			return ordering;
		}
	}
	a.len().cmp(&b.len())
}

/// The kernel arguments the image's own boot loader would use, from GRUB or a Boot Loader Spec entry, minus the
/// console settings, which Minori chooses.
fn boot_loader_args(fs: &Ext4) -> Option<String> {
	for dir in ["/boot/loader/entries", "/loader/entries"] {
		if let Ok(entries) = fs.read_dir(dir) {
			let mut files: Vec<String> = entries
				.filter_map(|entry| entry.ok())
				.filter_map(|entry| entry.path().to_str().ok().map(str::to_string))
				.filter(|path| path.ends_with(".conf") && !path.contains("rescue"))
				.collect();
			files.sort_by(|a, b| version_cmp(b, a));
			for file in files {
				if let Ok(text) = fs.read_to_string(file.as_str())
					&& let Some(options) = text.lines().find_map(|line| line.trim().strip_prefix("options "))
				{
					return Some(clean_args(options));
				}
			}
		}
	}
	for path in ["/boot/grub/grub.cfg", "/grub/grub.cfg", "/boot/grub2/grub.cfg", "/grub2/grub.cfg"] {
		if let Ok(text) = fs.read_to_string(path) {
			for line in text.lines() {
				let line = line.trim();
				let mut words = line.split_whitespace();
				if matches!(words.next(), Some("linux" | "linuxefi")) {
					words.next();
					let args: Vec<&str> = words.collect();
					if args.iter().any(|arg| arg.starts_with("root=")) {
						return Some(clean_args(&args.join(" ")));
					}
				}
			}
		}
	}
	None
}

fn clean_args(args: &str) -> String {
	args.split_whitespace()
		.filter(|arg| {
			!arg.contains('$')
				&& !arg.starts_with("console=")
				&& !arg.starts_with("earlyprintk")
				&& !arg.starts_with("earlycon")
				&& !arg.starts_with("BOOT_IMAGE=")
				&& *arg != "splash"
		})
		.collect::<Vec<_>>()
		.join(" ")
}

fn plan_from(ext4s: &[(BootSource, Ext4Facts)], root_fallback: Option<String>, os: Option<&str>) -> Option<BootPlan> {
	let (source, found) = ext4s.iter().find(|(_, found)| !found.kernels.is_empty())?;
	let (kernel, initrd, version) = found.kernels.first()?.clone();
	let args = ext4s
		.iter()
		.find_map(|(_, found)| found.args.clone())
		.filter(|args| args.contains("root="))
		.or_else(|| root_fallback.map(|root| format!("{root} ro")))
		.or_else(|| found.uuid.as_ref().map(|uuid| format!("root=UUID={uuid} ro")))
		.unwrap_or_else(|| "root=/dev/vda1 ro".to_string());
	let place = match source {
		BootSource::Partition { number } => format!("partition {number}"),
		BootSource::Whole => "the disk".to_string(),
		BootSource::Iso => "the ISO".to_string(),
	};
	let name = os.map(|os| format!("{os}, ")).unwrap_or_default();
	Some(BootPlan {
		source: source.clone(),
		kernel,
		initrd,
		cmdline: format!("{args} {CONSOLE_ARGS}"),
		description: format!("{name}Linux {version} from {place}"),
	})
}

fn read_esp(slice: Slice, facts: &mut Facts, info: &mut Partition) {
	let Ok(fs) = fatfs::FileSystem::new(slice, fatfs::FsOptions::new()) else {
		return;
	};
	let label = fs.volume_label();
	if !label.trim().is_empty() {
		info.label = Some(label.trim().to_string());
	}
	info.uuid = Some(format!("{:04X}-{:04X}", fs.volume_id() >> 16, fs.volume_id() & 0xffff));
	let root = fs.root_dir();
	for (path, arch) in [("EFI/BOOT/BOOTAA64.EFI", Arch::Aarch64), ("EFI/BOOT/BOOTX64.EFI", Arch::X86_64)] {
		if root.open_file(path).is_ok() {
			facts.add(arch, format!("The EFI system partition has {path}"));
		}
	}
	if root.open_file("EFI/Microsoft/Boot/bootmgfw.efi").is_ok() {
		facts.windows = true;
		facts.add(Arch::Unknown, "The EFI system partition has Windows Boot Manager");
	}
}

struct IsoKernel {
	kernel: &'static str,
	initrd: &'static str,
	args: &'static str,
	arch: Arch,
	what: &'static str,
}

const ISO_KERNELS: &[IsoKernel] = &[
	IsoKernel {
		kernel: "/install.a64/vmlinuz",
		initrd: "/install.a64/initrd.gz",
		args: "",
		arch: Arch::Aarch64,
		what: "Debian installer",
	},
	IsoKernel {
		kernel: "/boot/vmlinuz-virt",
		initrd: "/boot/initramfs-virt",
		args: "modules=loop,squashfs,sd-mod,usb-storage",
		arch: Arch::Unknown,
		what: "Alpine",
	},
	IsoKernel {
		kernel: "/boot/vmlinuz-lts",
		initrd: "/boot/initramfs-lts",
		args: "modules=loop,squashfs,sd-mod,usb-storage",
		arch: Arch::Unknown,
		what: "Alpine",
	},
	IsoKernel { kernel: "/casper/vmlinuz", initrd: "/casper/initrd", args: "---", arch: Arch::Unknown, what: "Ubuntu" },
	IsoKernel {
		kernel: "/images/pxeboot/vmlinuz",
		initrd: "/images/pxeboot/initrd.img",
		args: "inst.stage2=hd:LABEL={volume}",
		arch: Arch::Unknown,
		what: "Fedora installer",
	},
	IsoKernel {
		kernel: "/live/vmlinuz",
		initrd: "/live/initrd.img",
		args: "boot=live components",
		arch: Arch::Unknown,
		what: "Debian live",
	},
	IsoKernel {
		kernel: "/install.amd/vmlinuz",
		initrd: "/install.amd/initrd.gz",
		args: "",
		arch: Arch::X86_64,
		what: "Debian installer for PCs",
	},
];

fn inspect_iso(disk: &SharedDisk, report: &mut Report, facts: &mut Facts) -> Result<()> {
	let tree = iso::read_tree(&mut disk.borrow_mut())?;
	report.filesystem = Some(Filesystem::Iso9660);
	if !tree.volume_id.is_empty() {
		report.os = Some(tree.volume_id.clone());
	}
	for (path, arch) in [("/efi/boot/bootaa64.efi", Arch::Aarch64), ("/efi/boot/bootx64.efi", Arch::X86_64)] {
		if tree.find(path).is_some() {
			facts.add(arch, format!("The ISO has {path}"));
		}
	}
	if tree.find("/sources/install.wim").is_some() || tree.find("/sources/install.esd").is_some() {
		facts.windows = true;
		facts.add(Arch::Unknown, "The ISO has Windows setup files");
	}
	for candidate in ISO_KERNELS {
		let Some(kernel) = tree.find(candidate.kernel) else {
			continue;
		};
		if candidate.arch != Arch::Unknown {
			facts.add(candidate.arch, format!("The ISO has {}", candidate.kernel));
		}
		let header = tree.read(&mut disk.borrow_mut(), kernel)?;
		if let Ok(arch) = crate::boot::kernel_arch(&header)
			&& arch != Arch::Unknown
		{
			facts.add(arch, format!("{} is a {} kernel", candidate.kernel, arch_name(arch)));
		}
		let volume = tree.volume_id.replace(' ', "\\x20");
		let args = candidate.args.replace("{volume}", &volume);
		let args = if args.is_empty() { CONSOLE_ARGS.to_string() } else { format!("{args} {CONSOLE_ARGS}") };
		report.boot = Some(BootPlan {
			source: BootSource::Iso,
			kernel: candidate.kernel.to_string(),
			initrd: tree.find(candidate.initrd).map(|_| candidate.initrd.to_string()),
			cmdline: args,
			description: format!("The {} kernel on the ISO", candidate.what),
		});
		break;
	}
	if report.boot.is_none() && facts.arch() != Arch::X86_64 && !facts.windows {
		report.notes.push(
			"Minori didn't find a kernel it knows how to start on this ISO. You can attach it to a VM as a \
			 read-only disk, but AVF has no firmware to boot it by itself."
				.to_string(),
		);
	}
	Ok(())
}

pub(crate) fn arch_name(arch: Arch) -> &'static str {
	match arch {
		Arch::Aarch64 => "64-bit Arm",
		Arch::X86_64 => "x86-64",
		Arch::Unknown => "unknown",
	}
}

/// Opens the filesystem a [`BootPlan`] points at and reads one file from it.
pub(crate) fn read_boot_file(image: &Path, source: &BootSource, path: &str) -> Result<Vec<u8>> {
	let disk: SharedDisk = Rc::new(RefCell::new(Disk::open(image)?));
	match source {
		BootSource::Iso => {
			let tree = iso::read_tree(&mut disk.borrow_mut())?;
			let file = tree.find(path).cloned().ok_or_else(|| Error::format(format!("{path} isn't on the ISO")))?;
			tree.read(&mut disk.borrow_mut(), &file)
		}
		BootSource::Whole => {
			let size = disk.borrow().size();
			read_ext4_file(Slice::new(disk.clone(), 0, size), path)
		}
		BootSource::Partition { number } => {
			let table = partition::read(&mut disk.borrow_mut())?;
			let parts = match table {
				Table::Gpt(parts) | Table::Mbr(parts) => parts,
				Table::None => Vec::new(),
			};
			let part = parts
				.iter()
				.find(|part| part.number == *number)
				.ok_or_else(|| Error::format(format!("The image has no partition {number}")))?;
			read_ext4_file(Slice::new(disk.clone(), part.start, part.size), path)
		}
	}
}

fn read_ext4_file(slice: Slice, path: &str) -> Result<Vec<u8>> {
	let fs = Ext4::load(Box::new(slice)).map_err(|error| Error::format(error.to_string()))?;
	fs.read(path).map_err(|error| Error::format(format!("Couldn't read {path}: {error}")))
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn sorts_kernel_versions_numerically() {
		assert!(version_cmp("6.12.10-arm64", "6.12.9-arm64").is_gt());
		assert!(version_cmp("6.1.0", "6.12.0").is_lt());
	}

	#[test]
	fn drops_console_and_grub_variables() {
		let args = clean_args("root=PARTUUID=abc ro console=tty0 console=ttyS0,115200 $vt_handoff quiet");
		assert_eq!(args, "root=PARTUUID=abc ro quiet");
	}

	#[test]
	fn reads_os_release() {
		let text = "NAME=\"Debian GNU/Linux\"\nPRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"\n";
		assert_eq!(os_release_name(text).as_deref(), Some("Debian GNU/Linux 13 (trixie)"));
	}
}
