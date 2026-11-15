package com.eratw.port.core.patch

import com.eratw.port.core.code.ErbcDecodeException
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.loader.MappedErbc
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 真实旧 Reader 五连测（M2-2 / ABI-4 Gate，蓝图 era-arch-v61u.md :64 与 :295）。
 *
 * 被测对象是被冻结的移植侧旧 Reader，不是被改对象：
 * - 字节路：[ErbcReader.probe]（只读文件头，结构上绝不构造 Program）与
 *   [ErbcReader.decode]（Decoder 的 header = parseHeader() 是初始化属性，
 *   ErbcReader.kt:341，先于 decodeProgram() :343）。
 * - 映射路：[MappedErbc.open]（MappedErbc.kt:82-100 在 open 阶段解析头，
 *   :99 parseHeader 先于 :100 构造实例；段正文与 Program 都还没碰）。
 *
 * 拒载锚（移植侧实树锚，非 C# 锚）：magic 闸 MappedErbc.kt:129-137 / ErbcReader.kt:569-574；
 * 版本白名单闸 MappedErbc.kt:139-146 / ErbcReader.kt:576-583。
 *
 * 五连测（同一份字节新旧 Reader 各读一次，避免「旧 Reader 拒一切」的假绿）：
 * 1. 新读 Module ✓
 * 2. 新读 Linked ✓
 * 3. 旧读真实 Legacy .erbc ✓
 * 4. 旧读 Module 在构造 Program 之前被 magic 闸拒
 * 5. 旧读新 ISA Linked(v3) 被版本白名单明确拒
 *
 * 机制：MODULE 用独立 magic ERBM 命中 magic 闸；LINKED 沿用 magic ERBC 但升 u16 version = 3
 * 命中版本白名单闸。REQUIRED_FEATURES 在本格式文本头偏移 8 之后，两条闸都在读到它之前拒载，
 * 因此它只是新 Reader 自己的 fail-closed 闸，绝不是旧 Reader 的拒载理由（由下方两条机制钉钉死）。
 */
class ModuleContainerLegacyReaderTest {

    @TempDir
    lateinit var tempDir: Path

    private val fx = ModuleContainerFixture

    private fun temp(name: String, bytes: ByteArray): Path {
        val p = tempDir.resolve(name)
        Files.write(p, bytes)
        return p
    }

    // ------------------------------------------------------------------
    // 链路 1 / 2：新 Reader 读新格式
    // ------------------------------------------------------------------

    @Test
    fun `链路1 新读 Module 成功`() {
        val c = ModuleContainerCodec.decode(fx.moduleBytes())
        assertEquals(ContainerRole.MODULE, c.role)
        assertEquals(ModuleContainerFormat.ISA_VERSION, c.isaVersion)
        assertTrue(c.pools != null, "MODULE 态载荷应为零基线四池")
    }

    @Test
    fun `链路2 新读 Linked 成功`() {
        val c = ModuleContainerCodec.decode(fx.linkedBytes())
        assertEquals(ContainerRole.LINKED_PROGRAM, c.role)
        assertTrue(c.linkedSegments != null, "LINKED 态载荷应为三段")
    }

    // ------------------------------------------------------------------
    // 链路 3：真实旧 Reader 读真实 legacy .erbc
    // ------------------------------------------------------------------

    @Test
    fun `链路3 旧读 Legacy 成功（字节路 probe-decode 与映射路 MappedErbc-open）`() {
        val bytes = fx.legacyErbcBytes()

        // 字节路：probe 只读文件头；decode 解出可执行 Program。
        val header = ErbcReader.probe(bytes)
        assertEquals(ErbcFormat.LATEST_VERSION, header.version)
        val program = ErbcReader.decode(bytes)
        assertTrue(program.functions.isNotEmpty(), "legacy 产物应解出至少一个函数")

        // 映射路：MappedErbc.open 在 open 阶段解析头，逐字同判据。
        val path = temp("legacy.erbc", bytes)
        val mapped = MappedErbc.open(path)
        try {
            assertEquals(ErbcFormat.LATEST_VERSION, mapped.header.version)
            assertEquals(bytes.size, mapped.byteSize)
        } finally {
            mapped.close()
        }
    }

    // ------------------------------------------------------------------
    // 链路 4：旧读 Module，magic 闸，构造 Program 之前
    // ------------------------------------------------------------------

    @Test
    fun `链路4 旧读 Module 在构造 Program 之前被 magic 闸拒`() {
        val bytes = fx.moduleBytes()
        // 同一份字节新 Reader 能读（排除「旧 Reader 见到啥都拒」的假绿）。
        assertEquals(ContainerRole.MODULE, ModuleContainerCodec.decode(bytes).role)

        // 字节路 probe：只读文件头，结构上不构造 Program；它抛 magic 闸 = 构造 Program 之前已拒。
        val p1 = assertFailsWith<ErbcDecodeException> { ErbcReader.probe(bytes) }
        assertEquals(0, p1.offset, "magic 闸在文件头偏移 0")
        assertTrue(p1.message!!.contains("magic 不符"))

        // 字节路 decode：即便走完整 decodeProgram 意图，也在 Decoder 初始化属性 parseHeader 当场拒。
        val p2 = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(bytes) }
        assertTrue(p2.message!!.contains("magic 不符"))

        // 映射路：MappedErbc.open 在 open 阶段 parseHeader 拒，段正文与 Program 都未构造。
        val path = temp("module.erbc", bytes)
        val p3 = assertFailsWith<ErbcDecodeException> { MappedErbc.open(path) }
        assertTrue(p3.message!!.contains("magic 不符"))
    }

    // ------------------------------------------------------------------
    // 链路 5：旧读 ERBC + v3 Linked，版本白名单明确拒
    // ------------------------------------------------------------------

    @Test
    fun `链路5 旧读新 ISA Linked v3 被版本白名单明确拒`() {
        val bytes = fx.linkedBytes()
        assertEquals(ContainerRole.LINKED_PROGRAM, ModuleContainerCodec.decode(bytes).role)

        val v1 = assertFailsWith<ErbcDecodeException> { ErbcReader.probe(bytes) }
        assertEquals(4, v1.offset, "版本白名单闸在文件头偏移 4")
        assertTrue(v1.message!!.contains("不支持的 .erbc 版本 3"))

        val v2 = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(bytes) }
        assertTrue(v2.message!!.contains("不支持的 .erbc 版本 3"))

        val path = temp("linked.erbc", bytes)
        val v3 = assertFailsWith<ErbcDecodeException> { MappedErbc.open(path) }
        assertTrue(v3.message!!.contains("不支持的 .erbc 版本 3"))
    }

    // ------------------------------------------------------------------
    // 机制钉：把「靠已严格校验字段拒载」与「REQUIRED_FEATURES 不是理由」钉死
    // ------------------------------------------------------------------

    @Test
    fun `机制钉 ERBM 换成 ERBC 后旧 Reader 改走版本闸而非 magic 闸`() {
        // Module 容器一旦不用独立 magic，就只剩 u16 version = 3 能挡旧 Reader —— 版本白名单闸。
        val bytes = fx.withMagic(fx.moduleBytes(), "ERBC")
        val ex = assertFailsWith<ErbcDecodeException> { ErbcReader.probe(bytes) }
        assertEquals(4, ex.offset)
        assertTrue(ex.message!!.contains("不支持的 .erbc 版本 3"))
        assertTrue(!ex.message!!.contains("magic 不符"))
    }

    @Test
    fun `机制钉 旧 Reader 在 REQUIRED_FEATURES 文本头之前就拒载`() {
        // 把 REQUIRED_FEATURES 改成新 Reader 会拒的值，但 ERBM magic 仍在：
        // 旧 Reader 照样 magic 闸拒，且报错与 REQUIRED_FEATURES 无关 —— 它根本没解析到偏移 8 之后。
        val bytes = fx.withHeaderLines(
            fx.moduleBytes(),
            listOf(fx.H_ROLE_MODULE, "REQUIRED_FEATURES 0x0", fx.H_ISA, fx.H_END),
        )
        // 佐证：同一份字节新 Reader 确实会因 REQUIRED_FEATURES 缺必需位而拒（说明值确实坏）。
        val newEx = assertFailsWith<ModuleContainerException> { ModuleContainerCodec.decode(bytes) }
        assertTrue(newEx.message!!.contains("REQUIRED_FEATURES"))

        val oldEx = assertFailsWith<ErbcDecodeException> { ErbcReader.probe(bytes) }
        assertTrue(oldEx.message!!.contains("magic 不符"))
        assertTrue(!oldEx.message!!.contains("REQUIRED_FEATURES"))
    }
}
