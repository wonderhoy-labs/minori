//! What Minori's Android app needs from Rust: `minori-core`'s image tools, and pseudo-terminals for local consoles.
//!
//! The Kotlin side is `com.wonderhoylabs.minori.core.Native`. Every function either returns its result, usually as
//! JSON, or throws `java.io.IOException` with a message fit to show the user. Long operations take a
//! `NativeProgress`, whose `update(done, total)` returns false to cancel.

#![allow(non_snake_case)]

mod pty;

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong, jlongArray, jstring};
use minori_core::progress::Progress;
use minori_core::{archive, boot, convert, inspect, seed};
use std::path::{Path, PathBuf};

struct JavaProgress<'a, 'b> {
	env: &'a mut JNIEnv<'b>,
	callback: &'a JObject<'b>,
}

impl Progress for JavaProgress<'_, '_> {
	fn update(&mut self, done: u64, total: u64) -> bool {
		if self.callback.is_null() {
			return true;
		}
		let result = self.env.call_method(
			self.callback,
			"update",
			"(JJ)Z",
			&[JValue::Long(done as i64), JValue::Long(total as i64)],
		);
		// An error means a Java exception is pending, so stop and let it surface.
		result.and_then(|value| value.z()).unwrap_or_default()
	}
}

fn string(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
	if value.is_null() {
		return Err("A required text value was missing".to_string());
	}
	env.get_string(value).map(String::from).map_err(|error| error.to_string())
}

fn optional_string(env: &mut JNIEnv, value: &JString) -> Result<Option<String>, String> {
	if value.is_null() { Ok(None) } else { string(env, value).map(Some) }
}

fn throw(env: &mut JNIEnv, message: &str) {
	if env.exception_check().unwrap_or(false) {
		return;
	}
	let _ = env.throw_new("java/io/IOException", message);
}

fn to_jstring(env: &mut JNIEnv, text: &str) -> jstring {
	match env.new_string(text) {
		Ok(value) => value.into_raw(),
		Err(error) => {
			throw(env, &error.to_string());
			std::ptr::null_mut()
		}
	}
}

fn json<T: serde::Serialize>(value: &T) -> Result<String, String> {
	serde_json::to_string(value).map_err(|error| error.to_string())
}

fn core_error(error: minori_core::Error) -> String {
	error.to_string()
}

/// Returns `inspect::inspect`'s report as JSON.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wonderhoylabs_minori_core_Native_inspect<'a>(
	mut env: JNIEnv<'a>,
	_class: JClass<'a>,
	path: JString<'a>,
) -> jstring {
	let result = string(&mut env, &path)
		.and_then(|path| inspect::inspect(Path::new(&path)).map_err(core_error))
		.and_then(|report| json(&report));
	match result {
		Ok(text) => to_jstring(&mut env, &text),
		Err(message) => {
			throw(&mut env, &message);
			std::ptr::null_mut()
		}
	}
}

/// Writes a raw sparse disk from any image, returning its size.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wonderhoylabs_minori_core_Native_toRaw<'a>(
	mut env: JNIEnv<'a>,
	_class: JClass<'a>,
	src: JString<'a>,
	dst: JString<'a>,
	callback: JObject<'a>,
) -> jlong {
	let paths = string(&mut env, &src).and_then(|src| Ok((src, string(&mut env, &dst)?)));
	let result = paths.and_then(|(src, dst)| {
		let mut progress = JavaProgress { env: &mut env, callback: &callback };
		convert::to_raw(Path::new(&src), Path::new(&dst), &mut progress).map_err(core_error)
	});
	match result {
		Ok(size) => size as jlong,
		Err(message) => {
			throw(&mut env, &message);
			-1
		}
	}
}

/// Extracts the kernel and initrd a boot plan (JSON) names, returning the written files as JSON.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wonderhoylabs_minori_core_Native_extractBoot<'a>(
	mut env: JNIEnv<'a>,
	_class: JClass<'a>,
	image: JString<'a>,
	plan: JString<'a>,
	out_dir: JString<'a>,
) -> jstring {
	let result = (|| {
		let image = string(&mut env, &image)?;
		let plan: inspect::BootPlan =
			serde_json::from_str(&string(&mut env, &plan)?).map_err(|error| error.to_string())?;
		let out_dir = string(&mut env, &out_dir)?;
		let files = boot::extract(Path::new(&image), &plan, Path::new(&out_dir)).map_err(core_error)?;
		json(&files)
	})();
	match result {
		Ok(text) => to_jstring(&mut env, &text),
		Err(message) => {
			throw(&mut env, &message);
			std::ptr::null_mut()
		}
	}
}

/// Grows a raw disk.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wonderhoylabs_minori_core_Native_grow<'a>(
	mut env: JNIEnv<'a>,
	_class: JClass<'a>,
	path: JString<'a>,
	size: jlong,
) {
	let result =
		string(&mut env, &path).and_then(|path| convert::grow(Path::new(&path), size as u64).map_err(core_error));
	if let Err(message) = result {
		throw(&mut env, &message);
	}
}

/// Creates an empty sparse disk.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wonderhoylabs_minori_core_Native_createBlank<'a>(
	mut env: JNIEnv<'a>,
	_class: JClass<'a>,
	path: JString<'a>,
	size: jlong,
) {
	let result = string(&mut env, &path)
		.and_then(|path| convert::create_blank(Path::new(&path), size as u64).map_err(core_error));
	if let Err(message) = result {
		throw(&mut env, &message);
	}
}

/// The SHA-256 of a file, as hex.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wonderhoylabs_minori_core_Native_sha256<'a>(
	mut env: JNIEnv<'a>,
	_class: JClass<'a>,
	path: JString<'a>,
	callback: JObject<'a>,
) -> jstring {
	let result = string(&mut env, &path).and_then(|path| {
		let mut progress = JavaProgress { env: &mut env, callback: &callback };
		convert::sha256_file(Path::new(&path), &mut progress).map_err(core_error)
	});
	match result {
		Ok(text) => to_jstring(&mut env, &text),
		Err(message) => {
			throw(&mut env, &message);
			std::ptr::null_mut()
		}
	}
}

/// How many bytes of a file are really on disk.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wonderhoylabs_minori_core_Native_allocatedBytes<'a>(
	mut env: JNIEnv<'a>,
	_class: JClass<'a>,
	path: JString<'a>,
) -> jlong {
	match string(&mut env, &path).and_then(|path| convert::allocated_bytes(Path::new(&path)).map_err(core_error)) {
		Ok(bytes) => bytes as jlong,
		Err(_) => -1,
	}
}

#[derive(serde::Deserialize)]
struct ExportDisk {
	name: String,
	path: PathBuf,
}

/// Writes a `.minorivm` archive into a file descriptor. `disks` is a JSON list of `{"name", "path"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wonderhoylabs_minori_core_Native_exportArchive<'a>(
	mut env: JNIEnv<'a>,
	_class: JClass<'a>,
	vm: JString<'a>,
	disks: JString<'a>,
	out_fd: jint,
	callback: JObject<'a>,
) {
	let inputs = (|| {
		let vm: serde_json::Value = serde_json::from_str(&string(&mut env, &vm)?).map_err(|error| error.to_string())?;
		let disks: Vec<ExportDisk> =
			serde_json::from_str(&string(&mut env, &disks)?).map_err(|error| error.to_string())?;
		Ok::<_, String>((vm, disks))
	})();
	let result = inputs.and_then(|(vm, disks)| {
		let pairs: Vec<(String, PathBuf)> = disks.into_iter().map(|disk| (disk.name, disk.path)).collect();
		let out = std::io::BufWriter::with_capacity(1 << 20, owned_fd(out_fd)?);
		let mut progress = JavaProgress { env: &mut env, callback: &callback };
		let out = archive::export_to(&vm, &pairs, out, &mut progress).map_err(core_error)?;
		out.into_inner().map_err(|error| error.to_string())?.sync_all().map_err(|error| error.to_string())
	});
	if let Err(message) = result {
		throw(&mut env, &message);
	}
}

/// Unpacks a `.minorivm` archive from a file descriptor into a folder, returning its manifest as JSON.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wonderhoylabs_minori_core_Native_importArchive<'a>(
	mut env: JNIEnv<'a>,
	_class: JClass<'a>,
	in_fd: jint,
	total: jlong,
	dest: JString<'a>,
	callback: JObject<'a>,
) -> jstring {
	let result = string(&mut env, &dest).and_then(|dest| {
		let file = owned_fd(in_fd)?;
		let mut progress = JavaProgress { env: &mut env, callback: &callback };
		archive::import_from(file, total.max(0) as u64, Path::new(&dest), &mut progress).map_err(core_error)
	});
	match result.and_then(|manifest| json(&manifest)) {
		Ok(text) => to_jstring(&mut env, &text),
		Err(message) => {
			throw(&mut env, &message);
			std::ptr::null_mut()
		}
	}
}

/// Reads an archive's manifest without unpacking it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wonderhoylabs_minori_core_Native_readManifest<'a>(
	mut env: JNIEnv<'a>,
	_class: JClass<'a>,
	archive_path: JString<'a>,
) -> jstring {
	let result = string(&mut env, &archive_path)
		.and_then(|path| archive::read_manifest(Path::new(&path)).map_err(core_error))
		.and_then(|manifest| json(&manifest));
	match result {
		Ok(text) => to_jstring(&mut env, &text),
		Err(message) => {
			throw(&mut env, &message);
			std::ptr::null_mut()
		}
	}
}

/// Writes a cloud-init seed disk from a `seed::CloudConfig` as JSON. Returns the user-data it wrote.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wonderhoylabs_minori_core_Native_writeSeed<'a>(
	mut env: JNIEnv<'a>,
	_class: JClass<'a>,
	path: JString<'a>,
	config: JString<'a>,
	instance_id: JString<'a>,
	network_config: JString<'a>,
) -> jstring {
	let result = (|| {
		let path = string(&mut env, &path)?;
		let config: seed::CloudConfig =
			serde_json::from_str(&string(&mut env, &config)?).map_err(|error| error.to_string())?;
		let instance_id = string(&mut env, &instance_id)?;
		let network = optional_string(&mut env, &network_config)?;
		let user_data = config.user_data();
		seed::write_nocloud(Path::new(&path), &config.meta_data(&instance_id), &user_data, network.as_deref())
			.map_err(core_error)?;
		Ok::<_, String>(user_data)
	})();
	match result {
		Ok(text) => to_jstring(&mut env, &text),
		Err(message) => {
			throw(&mut env, &message);
			std::ptr::null_mut()
		}
	}
}

/// Starts `program` with `args` and `environment` (`NAME=value`) on a new pseudo-terminal. Returns the
/// terminal's file descriptor and the process ID.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wonderhoylabs_minori_core_Native_ptySpawn<'a>(
	mut env: JNIEnv<'a>,
	_class: JClass<'a>,
	program: JString<'a>,
	args: jni::objects::JObjectArray<'a>,
	environment: jni::objects::JObjectArray<'a>,
	cwd: JString<'a>,
	rows: jint,
	cols: jint,
) -> jlongArray {
	let result = (|| {
		let program = string(&mut env, &program)?;
		let args = strings(&mut env, &args)?;
		let environment = strings(&mut env, &environment)?;
		let cwd = string(&mut env, &cwd)?;
		pty::spawn(&program, &args, &environment, &cwd, rows as u16, cols as u16)
	})();
	match result {
		Ok((fd, pid)) => match env.new_long_array(2) {
			Ok(array) => {
				let _ = env.set_long_array_region(&array, 0, &[i64::from(fd), i64::from(pid)]);
				array.into_raw()
			}
			Err(error) => {
				throw(&mut env, &error.to_string());
				std::ptr::null_mut()
			}
		},
		Err(message) => {
			throw(&mut env, &message);
			std::ptr::null_mut()
		}
	}
}

/// Duplicates a descriptor Kotlin owns, so Rust can close its copy without closing Kotlin's.
fn owned_fd(fd: jint) -> Result<std::fs::File, String> {
	use std::os::fd::FromRawFd;
	// SAFETY: dup returns a new descriptor or -1; a new one is owned by the File alone.
	let copy = unsafe { libc::dup(fd) };
	if copy < 0 {
		return Err(std::io::Error::last_os_error().to_string());
	}
	// SAFETY: copy is a fresh descriptor nothing else owns.
	Ok(unsafe { std::fs::File::from_raw_fd(copy) })
}

fn strings(env: &mut JNIEnv, array: &jni::objects::JObjectArray) -> Result<Vec<String>, String> {
	let length = env.get_array_length(array).map_err(|error| error.to_string())?;
	let mut out = Vec::with_capacity(length as usize);
	for index in 0..length {
		let item = env.get_object_array_element(array, index).map_err(|error| error.to_string())?;
		out.push(string(env, &JString::from(item))?);
	}
	Ok(out)
}

/// Tells a pseudo-terminal its new size.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wonderhoylabs_minori_core_Native_ptyResize<'a>(
	_env: JNIEnv<'a>,
	_class: JClass<'a>,
	fd: jint,
	rows: jint,
	cols: jint,
) {
	pty::resize(fd, rows as u16, cols as u16);
}

/// Waits for a process to end and returns its exit status, or 128 plus the signal that ended it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wonderhoylabs_minori_core_Native_ptyWait<'a>(
	_env: JNIEnv<'a>,
	_class: JClass<'a>,
	pid: jint,
) -> jint {
	pty::wait(pid)
}

/// Sends a signal to a process, such as 1 (hang up) when a console closes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wonderhoylabs_minori_core_Native_ptyKill<'a>(
	_env: JNIEnv<'a>,
	_class: JClass<'a>,
	pid: jint,
	signal: jint,
) -> jboolean {
	u8::from(pty::kill(pid, signal))
}
