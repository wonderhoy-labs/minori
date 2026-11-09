package com.wonderhoylabs.minori.core

import java.io.IOException

/** Told how a long native operation is going. Return false to cancel it. */
fun interface NativeProgress {
	fun update(done: Long, total: Long): Boolean
}

/**
 * minori-core's image tools and the pseudo-terminal helpers, from `librui_jni.so`. Every call blocks, so make them
 * on a background thread. Failures throw [IOException] with a message meant for the user.
 */
object Native {
	init {
		System.loadLibrary("minori_jni")
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

	/** Starts a NAT for a guest's internet. Returns a handle for the calls below. */
	@Throws(IOException::class)
	external fun natStart(): Long

	external fun natInput(
		handle: Long,
		packet: ByteArray,
		length: Int,
	)

	/** Blocks until there's a packet for the guest; returns its length, or -1 once stopped. */
	external fun natOutput(
		handle: Long,
		buffer: ByteArray,
	): Int

	external fun natStop(handle: Long)

	external fun natFree(handle: Long)

	external fun ptyKill(pid: Int, signal: Int): Boolean
}
