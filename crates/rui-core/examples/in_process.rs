//! Makes a blank VM disk and a cloud-init seed, packs both into a `.ruivm` archive, and unpacks it again, all in a
//! temporary folder. Run it with `cargo run -p rui-core --example in_process`.

use rui_core::progress::Quiet;
use rui_core::{archive, convert, inspect, seed};

fn main() -> Result<(), rui_core::Error> {
	let dir = std::env::temp_dir().join(format!("rui-example-{}", std::process::id()));
	std::fs::create_dir_all(&dir)?;

	let disk = dir.join("data.img");
	convert::create_blank(&disk, 64 << 20)?;

	let config = seed::CloudConfig {
		hostname: "example".into(),
		user: "user".into(),
		password: Some("change me".into()),
		..seed::CloudConfig::default()
	};
	let seed_disk = dir.join("seed.img");
	seed::write_nocloud(&seed_disk, &config.meta_data("example-1"), &config.user_data(), None)?;
	let report = inspect::inspect(&seed_disk)?;
	println!("The seed is {:?} with {:?}", report.format, report.filesystem);

	let vm = serde_json::json!({ "name": "Example" });
	let packed = dir.join("example.ruivm");
	archive::export(&vm, &[("data.img".into(), disk), ("seed.img".into(), seed_disk)], &packed, &mut Quiet)?;
	let manifest = archive::import(&packed, &dir.join("unpacked"), &mut Quiet)?;
	println!("Unpacked {} disks for {}", manifest.disks.len(), manifest.vm["name"]);

	std::fs::remove_dir_all(&dir)?;
	Ok(())
}
