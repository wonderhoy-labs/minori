//! The parts of Minori that work on disk images and VM files, shared by the Android app (through `minori-jni`) and by
//! anything else that wants them.
//!
//! - [`inspect`] says what an image is: its format, partitions, filesystems, CPU architecture, the OS on it, and
//!   whether Minori can boot it.
//! - [`boot`] pulls a bootable kernel and initrd out of an image, because Android's Virtualization Framework starts
//!   Linux from a kernel rather than from firmware.
//! - [`convert`] turns qcow2 into raw disks, copies images sparsely and grows them.
//! - [`archive`] writes and reads `.minorivm` archives, the lossless export format.
//! - [`seed`] builds cloud-init NoCloud seed disks, so a cloud image boots with a user, a hostname and SSH.
//!
//! Every function works on paths and plain values, never on Android types.

#![warn(missing_docs)]

pub mod archive;
pub mod boot;
pub mod convert;
pub mod disk;
mod error;
mod fsdetect;
pub mod inspect;
mod iso;
mod partition;
pub mod progress;
mod qcow2;
pub mod seed;

pub use error::{Error, Result};
pub use progress::Progress;
