//! cloud-init NoCloud seed disks.
//!
//! Cloud images boot with no user and no password, and wait for cloud-init to set them up. cloud-init's NoCloud
//! source reads its settings from a small disk labelled `CIDATA`. [`write_nocloud`] makes one, and
//! [`CloudConfig`] writes the settings Minori uses.

use crate::{Error, Result};
use serde::{Deserialize, Serialize};
use std::fs::OpenOptions;
use std::io::Write;
use std::path::Path;

const SEED_SIZE: u64 = 4 << 20;

/// A file for cloud-init to write into the guest on first boot.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct GuestFile {
	/// Where it goes in the guest.
	pub path: String,
	/// Its contents, as text.
	pub content: String,
	/// Octal permissions, like `0755`.
	pub permissions: String,
	/// `"b64"` if `content` is base64, for binaries. Empty for plain text.
	pub encoding: String,
}

/// The first-boot settings for a cloud image.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct CloudConfig {
	/// The guest's hostname.
	pub hostname: String,
	/// The user to create. It can use `sudo` without a password.
	pub user: String,
	/// A password for that user, if logging in with one should work.
	pub password: Option<String>,
	/// Public keys allowed to log in as that user over SSH.
	pub ssh_keys: Vec<String>,
	/// Packages to install on first boot.
	pub packages: Vec<String>,
	/// Files to write on first boot.
	pub files: Vec<GuestFile>,
	/// Shell commands to run once, at the end of first boot.
	pub run: Vec<String>,
}

fn quote(text: &str) -> String {
	// A JSON string is a valid YAML scalar, with every character escaped that YAML would care about.
	serde_json::to_string(text).unwrap_or_else(|_| "\"\"".to_string())
}

impl CloudConfig {
	/// The `meta-data` file. A new `instance_id` makes cloud-init run again on an existing disk.
	pub fn meta_data(&self, instance_id: &str) -> String {
		format!("instance-id: {}\nlocal-hostname: {}\n", quote(instance_id), quote(&self.hostname))
	}

	/// The `user-data` file, as `#cloud-config` YAML.
	pub fn user_data(&self) -> String {
		let mut out = String::from("#cloud-config\n");
		out.push_str(&format!("hostname: {}\n", quote(&self.hostname)));
		out.push_str("users:\n");
		out.push_str(&format!("  - name: {}\n", quote(&self.user)));
		out.push_str("    groups: [sudo]\n");
		out.push_str("    shell: /bin/bash\n");
		out.push_str("    sudo: \"ALL=(ALL) NOPASSWD:ALL\"\n");
		out.push_str(&format!("    lock_passwd: {}\n", self.password.is_none()));
		if !self.ssh_keys.is_empty() {
			out.push_str("    ssh_authorized_keys:\n");
			for key in &self.ssh_keys {
				out.push_str(&format!("      - {}\n", quote(key.trim())));
			}
		}
		if let Some(password) = &self.password {
			out.push_str("ssh_pwauth: true\n");
			out.push_str("chpasswd:\n  expire: false\n  users:\n");
			out.push_str(&format!(
				"    - name: {}\n      password: {}\n      type: text\n",
				quote(&self.user),
				quote(password)
			));
		} else {
			out.push_str("ssh_pwauth: false\n");
		}
		if !self.packages.is_empty() {
			out.push_str("package_update: true\npackages:\n");
			for package in &self.packages {
				out.push_str(&format!("  - {}\n", quote(package)));
			}
		}
		if !self.files.is_empty() {
			out.push_str("write_files:\n");
			for file in &self.files {
				out.push_str(&format!("  - path: {}\n", quote(&file.path)));
				out.push_str(&format!("    permissions: {}\n", quote(&file.permissions)));
				if !file.encoding.is_empty() {
					out.push_str(&format!("    encoding: {}\n", quote(&file.encoding)));
				}
				out.push_str(&format!("    content: {}\n", quote(&file.content)));
			}
		}
		if !self.run.is_empty() {
			out.push_str("runcmd:\n");
			for command in &self.run {
				out.push_str(&format!("  - {}\n", quote(command)));
			}
		}
		out
	}
}

/// Writes a NoCloud seed disk to `path`: a small FAT volume labelled `CIDATA` holding `meta-data`, `user-data` and,
/// if given, `network-config`. Attach it to the VM as a read-only disk.
pub fn write_nocloud(path: &Path, meta_data: &str, user_data: &str, network_config: Option<&str>) -> Result<()> {
	let total = meta_data.len() + user_data.len() + network_config.map_or(0, str::len);
	let size = SEED_SIZE.max((total as u64 + (1 << 20)).next_power_of_two());
	let mut file = OpenOptions::new().read(true).write(true).create(true).truncate(true).open(path)?;
	file.set_len(size)?;
	let options = fatfs::FormatVolumeOptions::new().volume_label(*b"CIDATA     ");
	fatfs::format_volume(&mut file, options)
		.map_err(|error| Error::format(format!("Couldn't format the seed: {error}")))?;
	{
		let fs = fatfs::FileSystem::new(&mut file, fatfs::FsOptions::new())?;
		let mut files = vec![("meta-data", meta_data), ("user-data", user_data)];
		if let Some(network) = network_config {
			files.push(("network-config", network));
		}
		for (name, content) in files {
			let mut out = fs.root_dir().create_file(name)?;
			out.truncate()?;
			out.write_all(content.as_bytes())?;
			out.flush()?;
		}
		fs.unmount()?;
	}
	file.sync_all()?;
	Ok(())
}

#[cfg(test)]
mod tests {
	use super::*;
	use std::io::Read;

	#[test]
	fn writes_a_readable_seed() {
		let config = CloudConfig {
			hostname: "debian".into(),
			user: "user".into(),
			password: Some("p\"w: 1".into()),
			ssh_keys: vec!["ssh-ed25519 AAAA test".into()],
			packages: vec!["openssh-server".into()],
			..CloudConfig::default()
		};
		let user_data = config.user_data();
		assert!(user_data.starts_with("#cloud-config\n"));
		assert!(user_data.contains("password: \"p\\\"w: 1\""));
		let path = std::env::temp_dir().join(format!("minori-seed-{}.img", std::process::id()));
		write_nocloud(&path, &config.meta_data("minori-1"), &user_data, None).unwrap();
		let mut file = OpenOptions::new().read(true).write(true).open(&path).unwrap();
		let fs = fatfs::FileSystem::new(&mut file, fatfs::FsOptions::new()).unwrap();
		assert_eq!(fs.volume_label(), "CIDATA");
		let mut text = String::new();
		fs.root_dir().open_file("user-data").unwrap().read_to_string(&mut text).unwrap();
		assert_eq!(text, user_data);
		drop(fs);
		std::fs::remove_file(&path).unwrap();
	}
}
