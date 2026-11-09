//! `minori`: the image tools from Minori's app, on a computer. Prepare and check images before copying them to a phone.

use minori_core::progress::Quiet;
use minori_core::{archive, boot, convert, inspect, seed};
use std::path::{Path, PathBuf};
use std::process::ExitCode;

const USAGE: &str = "\
Usage:
  minori inspect <image>                 Say what an image is and whether Minori can boot it, as JSON
  minori convert <image> <disk.img>      Write a raw, sparse disk from a qcow2, raw or ISO image
  minori boot <image> <out-dir>          Extract the image's kernel and initrd, and print the command line
  minori seed <seed.img> <hostname> <user> [password]
                                      Write a cloud-init seed disk
  minori export <out.minorivm> <vm.json> <disk>...
                                      Pack a VM's settings and disks into a .minorivm archive
  minori import <in.minorivm> <out-dir>     Unpack and check a .minorivm archive, printing its manifest
  minori --version";

fn main() -> ExitCode {
	let args: Vec<String> = std::env::args().skip(1).collect();
	let words: Vec<&str> = args.iter().map(String::as_str).collect();
	match run(&words) {
		Ok(()) => ExitCode::SUCCESS,
		Err(message) => {
			eprintln!("{message}");
			ExitCode::FAILURE
		}
	}
}

fn run(args: &[&str]) -> Result<(), String> {
	let fail = |error: minori_core::Error| error.to_string();
	match args {
		["--version"] => println!("minori {}", env!("CARGO_PKG_VERSION")),
		["inspect", image] => {
			let report = inspect::inspect(Path::new(image)).map_err(fail)?;
			println!("{}", serde_json::to_string_pretty(&report).map_err(|error| error.to_string())?);
		}
		["convert", image, disk] => {
			let size = convert::to_raw(Path::new(image), Path::new(disk), &mut Quiet).map_err(fail)?;
			println!("Wrote {disk}, {size} bytes");
		}
		["boot", image, out] => {
			let report = inspect::inspect(Path::new(image)).map_err(fail)?;
			let plan = report.boot.ok_or("Minori found no kernel it can boot in this image")?;
			let files = boot::extract(Path::new(image), &plan, Path::new(out)).map_err(fail)?;
			println!("{}", plan.description);
			println!("kernel:  {}", files.kernel.display());
			if let Some(initrd) = &files.initrd {
				println!("initrd:  {}", initrd.display());
			}
			println!("cmdline: {}", files.cmdline);
		}
		["seed", path, hostname, user, rest @ ..] => {
			let config = seed::CloudConfig {
				hostname: hostname.to_string(),
				user: user.to_string(),
				password: rest.first().map(|password| password.to_string()),
				packages: vec!["openssh-server".to_string()],
				..seed::CloudConfig::default()
			};
			seed::write_nocloud(Path::new(path), &config.meta_data(hostname), &config.user_data(), None)
				.map_err(fail)?;
			println!("Wrote {path}");
		}
		["export", out, vm, disks @ ..] if !disks.is_empty() => {
			let text = std::fs::read_to_string(vm).map_err(|error| format!("{vm}: {error}"))?;
			let vm: serde_json::Value = serde_json::from_str(&text).map_err(|error| format!("{vm}: {error}"))?;
			let disks: Vec<(String, PathBuf)> = disks
				.iter()
				.map(|disk| {
					let path = PathBuf::from(disk);
					let name = path.file_name().map(|name| name.to_string_lossy().to_string()).unwrap_or_default();
					(name, path)
				})
				.collect();
			archive::export(&vm, &disks, Path::new(out), &mut Quiet).map_err(fail)?;
			println!("Wrote {out}");
		}
		["import", archive_path, out] => {
			let manifest = archive::import(Path::new(archive_path), Path::new(out), &mut Quiet).map_err(fail)?;
			println!("{}", serde_json::to_string_pretty(&manifest).map_err(|error| error.to_string())?);
		}
		_ => return Err(USAGE.to_string()),
	}
	Ok(())
}
