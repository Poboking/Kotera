package com.eratw.port.modtesting

import com.eratw.port.core.mod.ModResourceStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 资源面录制装饰器自测：套壳只观测、不改语义。
 *
 * 断言口径（与 [RecordingResources] KDoc 对应）：
 * 1. 每次 `exists/size/open/keys` 都落一条 [RecordingResources.ResourceCall]（顺序、参数、结果、字节数）；
 * 2. 缺文件契约照生产：false / null / null，不抛、不记 failure；
 * 3. 非法名 IAE 与生产 `ModResourceStore` 逐字同文案，且失败调用先入账再原样上抛（套壳不吞异常）；
 * 4. 同一真实 store 的裸视图 vs 套壳视图，返回值逐字相等（纯观测）；
 * 5. 生产语义产生的 WARN / null（如超限资源）原样透传，套壳不新增告警、不把 null 改成异常。
 */
class RecordingResourcesTest {

    @Test
    fun `预置资源逐调用记录且读值保真`() {
        val world = TestSchema.world()
        try {
            val text = "台架资源：こんにちは"
            val bytes = text.toByteArray(Charsets.UTF_8)
            world.resourceText(TestSchema.PACKAGE_ID, FILE, text)
            val recorder = world.newContext(TestSchema.PACKAGE_ID).resourcesRecorder

            assertEquals(text, recorder.open(FILE)?.toString(Charsets.UTF_8))
            assertTrue(recorder.exists(FILE))
            assertEquals(bytes.size.toLong(), recorder.size(FILE))
            assertEquals(listOf(FILE), recorder.keys())

            assertEquals(
                listOf(
                    RecordingResources.ResourceCall(
                        access = RecordingResources.Access.OPEN,
                        name = FILE,
                        present = true,
                        byteCount = bytes.size,
                    ),
                    RecordingResources.ResourceCall(
                        access = RecordingResources.Access.EXISTS,
                        name = FILE,
                        present = true,
                    ),
                    RecordingResources.ResourceCall(
                        access = RecordingResources.Access.SIZE,
                        name = FILE,
                        present = true,
                        sizeBytes = bytes.size.toLong(),
                    ),
                    RecordingResources.ResourceCall(access = RecordingResources.Access.KEYS),
                ),
                recorder.calls,
            )
            assertEquals(4, recorder.callCount)
            assertEquals(1, recorder.count(RecordingResources.Access.OPEN))
            assertEquals(1, recorder.count(RecordingResources.Access.EXISTS, FILE))
        } finally {
            world.close()
        }
    }

    @Test
    fun `缺资源不抛且记录为不存在`() {
        val world = TestSchema.world()
        try {
            val recorder = world.newContext(TestSchema.PACKAGE_ID).resourcesRecorder
            val missing = "no/such.file"

            assertFalse(recorder.exists(missing))
            assertNull(recorder.open(missing))
            assertNull(recorder.size(missing))

            assertEquals(
                listOf(
                    RecordingResources.ResourceCall(
                        access = RecordingResources.Access.EXISTS,
                        name = missing,
                        present = false,
                    ),
                    RecordingResources.ResourceCall(
                        access = RecordingResources.Access.OPEN,
                        name = missing,
                        present = false,
                    ),
                    RecordingResources.ResourceCall(
                        access = RecordingResources.Access.SIZE,
                        name = missing,
                        present = false,
                    ),
                ),
                recorder.calls,
            )
            assertTrue(recorder.calls.all { call -> call.failure == null })
            // 缺文件不是异常：不产生引擎 WARN。
            assertTrue(world.resourceWarnings.isEmpty(), "缺文件不应产 WARN：${world.resourceWarnings}")
        } finally {
            world.close()
        }
    }

    @Test
    fun `非法名 IAE 与生产逐字一致且记录失败调用`() {
        val engineRoot: File = Files.createTempDirectory("eratw-resmock-parity").toFile()
        val world = TestSchema.world()
        try {
            val engine = ModResourceStore(engineRoot).apiFor(TestSchema.PACKAGE_ID)
            val recorder = world.newContext(TestSchema.PACKAGE_ID).resourcesRecorder

            for (name in listOf("../x", "/abs", "a//b")) {
                val engineMessage = assertFailsWith<IllegalArgumentException> { engine.open(name) }.message
                val wrappedMessage = assertFailsWith<IllegalArgumentException> { recorder.open(name) }.message
                assertEquals(engineMessage, wrappedMessage)
                assertEquals(RecordingResources.Access.OPEN, recorder.calls.last().access)
                assertEquals(name, recorder.calls.last().name)
                val failure = assertNotNull(recorder.calls.last().failure)
                assertEquals(engineMessage, failure.message)

                // exists/size 走同一名字校验层：文案同样逐字相等。
                val existsMessage = assertFailsWith<IllegalArgumentException> { recorder.exists(name) }.message
                assertEquals(engineMessage, existsMessage)
            }
        } finally {
            world.close()
            engineRoot.deleteRecursively()
        }
    }

    @Test
    fun `按入口与按名计数 reset 后归零`() {
        val world = TestSchema.world()
        try {
            world.resourceText(TestSchema.PACKAGE_ID, FILE, TEXT)
            val context = world.newContext(TestSchema.PACKAGE_ID)
            val recorder = context.resourcesRecorder

            recorder.exists(FILE)
            recorder.exists(FILE)
            recorder.open(FILE)
            recorder.keys()
            assertEquals(2, recorder.count(RecordingResources.Access.EXISTS))
            assertEquals(1, recorder.count(RecordingResources.Access.OPEN, FILE))
            assertEquals(0, recorder.count(RecordingResources.Access.SIZE))
            assertEquals(
                mapOf(
                    RecordingResources.Access.KEYS to 1,
                    RecordingResources.Access.EXISTS to 2,
                    RecordingResources.Access.OPEN to 1,
                ),
                recorder.counts(),
            )

            recorder.reset()
            assertEquals(0, recorder.callCount)
            assertTrue(recorder.calls.isEmpty())
            assertTrue(recorder.counts().isEmpty())
            assertEquals(0, recorder.count(RecordingResources.Access.EXISTS, FILE))
        } finally {
            world.close()
        }
    }

    @Test
    fun `套壳视图与裸视图返回值和 IAE 文案逐字相等`() {
        val world = TestSchema.world()
        try {
            world.resourceText(TestSchema.PACKAGE_ID, FILE, TEXT)
            val recorder = world.newContext(TestSchema.PACKAGE_ID).resourcesRecorder
            val bare = world.state.resources.apiFor(TestSchema.PACKAGE_ID)

            assertEquals(bare.exists(FILE), recorder.exists(FILE))
            assertEquals(bare.size(FILE), recorder.size(FILE))
            assertEquals(bare.keys(), recorder.keys())
            assertContentEquals(bare.open(FILE), recorder.open(FILE))

            // open 每次现读新副本：篡改套壳读到的 buffer 不影响下一次读（生产无缓存）。
            val stolen = assertNotNull(recorder.open(FILE))
            stolen.fill(0)
            assertContentEquals(TEXT.toByteArray(Charsets.UTF_8), assertNotNull(recorder.open(FILE)))

            // IAE 逐字透传：裸视图与套壳视图同一份文案，且套壳把失败调用记在账上。
            val bareMessage = assertFailsWith<IllegalArgumentException> { bare.open("../x") }.message
            val wrappedMessage = assertFailsWith<IllegalArgumentException> { recorder.open("../x") }.message
            assertEquals(bareMessage, wrappedMessage)
            assertEquals(bareMessage, recorder.calls.last().failure?.message)
        } finally {
            world.close()
        }
    }

    @Test
    fun `超限资源透传生产 null 并记录存在性`() {
        val world = TestSchema.world()
        try {
            val oversized = ByteArray((ModResourceStore.MAX_RESOURCE_FILE_BYTES + 1L).toInt())
            world.resource(TestSchema.PACKAGE_ID, BIG, oversized)
            val recorder = world.newContext(TestSchema.PACKAGE_ID).resourcesRecorder

            assertNull(recorder.open(BIG))
            assertEquals(
                RecordingResources.ResourceCall(
                    access = RecordingResources.Access.OPEN,
                    name = BIG,
                    present = false,
                ),
                recorder.calls.single(),
            )
            assertTrue(recorder.calls.single().failure == null)
            // WARN 由生产 store 原样产出（套壳只转发 sink，不另加告警）。
            assertTrue(
                world.resourceWarnings.any { warning -> warning.contains("超过单文件上限") },
                "预期生产 WARN 透传：${world.resourceWarnings}",
            )
        } finally {
            world.close()
        }
    }

    private companion object {
        const val FILE: String = "greetings/hello.txt"
        const val BIG: String = "big.bin"
        const val TEXT: String = "台架资源：こんにちは"
    }
}
