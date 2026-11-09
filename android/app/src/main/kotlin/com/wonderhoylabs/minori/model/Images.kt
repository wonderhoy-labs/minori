package com.wonderhoylabs.minori.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One partition, from minori-core's inspect. */
@Serializable
data class PartitionInfo(
	val number: Int,
	val size: Long,
	val filesystem: String? = null,
	val label: String? = null,
	val name: String = "",
)

/** minori-core's inspect report, the parts the app uses. */
@Serializable
data class ImageReport(
	val format: String,
	@SerialName("file_size") val fileSize: Long,
	@SerialName("virtual_size") val virtualSize: Long,
	@SerialName("partition_table") val partitionTable: String? = null,
	val partitions: List<PartitionInfo> = emptyList(),
	val filesystem: String? = null,
	val arch: String,
	val evidence: List<String> = emptyList(),
	val os: String? = null,
	val boot: BootPlan? = null,
	val verdict: String,
	val notes: List<String> = emptyList(),
) {
	val bootable: Boolean get() = verdict == "bootable"
}

/** An image the user brought in, kept until they delete it. */
@Serializable
data class ImageRecord(
	val id: String,
	val name: String,
	val file: String,
	val size: Long,
	val sha256: String,
	val addedAt: Long,
	val report: ImageReport? = null,
	/** Where it came from: a file the user picked, or a catalog download. */
	val source: String = "",
)
