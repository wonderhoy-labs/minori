package com.wonderhoylabs.rui.core

import java.io.IOException

/** Told how a long native operation is going. Return false to cancel it. */
fun interface NativeProgress {
	fun update(done: Long, total: Long): Boolean
}

/**
 * rui-core's image tools and the pseudo-terminal helpers, from `librui_jni.so`. Every call blocks, so make them
 * on a background thread. Failures throw [IOException] with a message meant for the user.
 */
object Native {
	init {
		System.loadLibrary("rui_jni")
	}

	@Throws(IOException::class)
	external fun inspect(path: String): String

	@Throws(IOException::class)
	external fun toRaw(src: String, dst: String, progress: NativeProgress?): Long

	@Throws(IOException::class)
	external fun extractBoot(image: String, planJson: String, outDir: String): String

	@Throws(IOException::class)
	external fun grow(path: String, size: Long)

	@Throws(IOException::class)
	external fun createBlank(path: String, size: Long)

	@Throws(IOException::class)
	external fun sha256(path: String, progress: NativeProgress?): String

	external fun allocatedBytes(path: String): Long

	@Throws(IOException::class)
	external fun exportArchive(vmJson: String, disksJson: String, outFd: Int, progress: NativeProgress?)

	@Throws(IOException::class)
	external fun importArchive(inFd: Int, total: Long, destDir: String, progress: NativeProgress?): String

	@Throws(IOException::class)
	external fun readManifest(archive: String): String

	@Throws(IOException::class)
	external fun writeSeed(path: String, configJson: String, instanceId: String, networkConfig: String?): String

	@Throws(IOException::class)
	external fun ptySpawn(
		program: String,
		args: Array<String>,
		environment: Array<String>,
		cwd: String,
		rows: Int,
		cols: Int,
	): LongArray

	external fun ptyResize(fd: Int, rows: Int, cols: Int)

	external fun ptyWait(pid: Int): Int

	external fun ptyKill(pid: Int, signal: Int): Boolean
}
