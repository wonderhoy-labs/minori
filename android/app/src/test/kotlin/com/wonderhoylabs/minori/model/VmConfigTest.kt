package com.wonderhoylabs.minori.model

import com.wonderhoylabs.minori.core.MinoriJson
import org.junit.Assert.assertEquals
import org.junit.Test

class VmConfigTest {
	@Test
	fun roundTripsThroughJson() {
		val vm =
			VmConfig(
				name = "Debian",
				disks = listOf(VmDisk(file = "root.img", label = "System disk", role = DiskRole.ROOT)),
				network = NetworkSettings(
					forwards = listOf(PortForward(hostPort = 2222, guestPort = 22, deny = listOf("10.0.0.0/8"))),
				),
			)
		val json = MinoriJson.encodeToString(VmConfig.serializer(), vm)
		assertEquals(vm, MinoriJson.decodeFromString(VmConfig.serializer(), json))
	}

	@Test
	fun readsConfigsFromNewerVersionsWithExtraFields() {
		val json = """{"id":"a","name":"Old","createdAt":1,"somethingNew":true,"backend":"avf"}"""
		assertEquals("Old", MinoriJson.decodeFromString(VmConfig.serializer(), json).name)
	}

	@Test
	fun readsRuiCoresInspectReport() {
		val report =
			"""{"format":"qcow2","file_size":1,"virtual_size":2,"partition_table":"gpt","partitions":[],"arch":"aarch64",
			|"evidence":[],"os":"Debian","boot":{"source":{"kind":"partition","number":1},"kernel":"/boot/vmlinuz",
			|"initrd":null,"cmdline":"root=/dev/vda1","description":"Linux"},"verdict":"bootable","notes":[]}
			""".trimMargin()
		val parsed = MinoriJson.decodeFromString(ImageReport.serializer(), report)
		assertEquals(1, parsed.boot?.source?.number)
		assertEquals(true, parsed.bootable)
	}
}
