package com.eratw.port.modtesting

import com.eratw.port.core.mod.ModResourceStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 台架资源面自测（task-32）：`TestWorld.resource` 落盘 → `TestContext.resources` 读回，
 * 且名字/上限语义与 `:core-vm` 的 `ModResourceStore` **逐字同源**（台架不另写一套）。
 */
class TestWorldResourcesTest {

    @Test
    fun resourceRoundTrip() {
        val world = TestSchema.world()
        try {
            val text = "台架资源：こんにちは"
            world.resourceText(TestSchema.PACKAGE_ID, "greetings/hello.txt", text)
            val resources = world.newContext(TestSchema.PACKAGE_ID).resources

            assertEquals(text, resources.open("greetings/hello.txt")?.toString(Charsets.UTF_8))
            assertTrue(resources.exists("greetings/hello.txt"))
            assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), resources.size("greetings/hello.txt"))
        } finally {
            world.close()
        }
    }

    @Test
    fun keysExistSizeAndMissingEntriesFollowEngineContract() {
        val world = TestSchema.world()
        try {
            world.resource(TestSchema.PACKAGE_ID, "b.txt", byteArrayOf(2))
            world.resource(TestSchema.PACKAGE_ID, "a/deep/c.bin", byteArrayOf(1, 2, 3))
            val resources = world.newContext(TestSchema.PACKAGE_ID).resources

            assertEquals(listOf("a/deep/c.bin", "b.txt"), resources.keys())
            assertTrue(resources.exists("b.txt"))
            assertEquals(3L, resources.size("a/deep/c.bin"))
            // 缺文件 = false / null，不抛（与引擎一致）。
            assertFalse(resources.exists("no/such.file"))
            assertNull(resources.open("no/such.file"))
            assertNull(resources.size("no/such.file"))
        } finally {
            world.close()
        }
    }

    @Test
    fun emptyResourceFaceBeforeAnyPlacementDoesNotThrow() {
        val world = TestSchema.world()
        try {
            val resources = world.newContext(TestSchema.PACKAGE_ID).resources
            assertTrue(resources.keys().isEmpty())
            assertFalse(resources.exists("anything.txt"))
            assertNull(resources.open("anything.txt"))
            assertTrue(world.resourceWarnings.isEmpty())
        } finally {
            world.close()
        }
    }

    @Test
    fun illegalNamesMatchEngineMessageExactly() {
        val engineRoot: File = Files.createTempDirectory("eratw-engine-parity").toFile()
        val world = TestSchema.world()
        try {
            val engine = ModResourceStore(engineRoot).apiFor(TestSchema.PACKAGE_ID)
            val resources = world.newContext(TestSchema.PACKAGE_ID).resources
            for (name in listOf("../x", "/abs", "a//b")) {
                val engineMessage = assertFailsWith<IllegalArgumentException> { engine.open(name) }.message
                assertEquals(engineMessage, assertFailsWith<IllegalArgumentException> { resources.open(name) }.message)
                assertEquals(
                    engineMessage,
                    assertFailsWith<IllegalArgumentException> {
                        world.resource(TestSchema.PACKAGE_ID, name, byteArrayOf(0))
                    }.message,
                )
            }
        } finally {
            world.close()
            engineRoot.deleteRecursively()
        }
    }

    @Test
    fun oversizedFileIsSkippedWithWarningNotThrown() {
        val world = TestSchema.world()
        try {
            val oversized = ByteArray((ModResourceStore.MAX_RESOURCE_FILE_BYTES + 1L).toInt())
            world.resource(TestSchema.PACKAGE_ID, "big.bin", oversized)
            world.resource(TestSchema.PACKAGE_ID, "small.txt", byteArrayOf(7))
            val resources = world.newContext(TestSchema.PACKAGE_ID).resources

            assertEquals(listOf("small.txt"), resources.keys())
            assertFalse(resources.exists("big.bin"))
            assertNull(resources.open("big.bin"))
            assertTrue(
                world.resourceWarnings.any { it.contains("超过单文件上限") },
                "预期引擎 WARN 被台架转发：${world.resourceWarnings}",
            )
        } finally {
            world.close()
        }
    }

    @Test
    fun packagesAreIsolated() {
        val world = TestSchema.world()
        try {
            world.resource("mod.a", "same.txt", "A".toByteArray(Charsets.UTF_8))
            world.resource("mod.b", "same.txt", "B".toByteArray(Charsets.UTF_8))
            val a = world.newContext("mod.a")
            val b = world.newContext("mod.b")

            assertEquals("A", a.resources.open("same.txt")?.toString(Charsets.UTF_8))
            assertEquals("B", b.resources.open("same.txt")?.toString(Charsets.UTF_8))
            // 越包名字被引擎名字校验层拒（不是靠目录遮蔽）。
            assertFailsWith<IllegalArgumentException> { a.resources.open("../mod.b.assets/same.txt") }
        } finally {
            world.close()
        }
    }
}
