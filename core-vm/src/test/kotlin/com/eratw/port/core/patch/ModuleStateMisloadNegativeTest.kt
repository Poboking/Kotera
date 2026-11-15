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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M0-3 Module 态误加载负控矩阵（ABI-4 Gate 负控半边；生产代码零改动的观察装置）。
 *
 * 被测对象是被冻结的移植侧旧 Reader，本类一个字节都不改它：
 * - 字节路 [ErbcReader.probe]（ErbcReader.kt:135，只取 Decoder.header，而 ErbcReader.kt:341
 *   的 parseHeader 是初始化属性）与 [ErbcReader.decode]（ErbcReader.kt:145-146，Decoder 构造
 *   先跑 init 属性、再进 decodeProgram ErbcReader.kt:343）；
 * - 映射路 [MappedErbc.open]（MappedErbc.kt:82-108，parseHeader 在 :99、构造实例在 :100，
 *   段正文与 Program 都还没碰）；
 * - 两道闸现场复核实树行号：magic 闸 MappedErbc.kt:129-137 / ErbcReader.kt:569-574（偏移 0，
 *   文案 magic 不符）；版本白名单闸 MappedErbc.kt:139-146 / ErbcReader.kt:577-583（偏移 4，
 *   文案 不支持的 .erbc 版本 3 与 不做降级解释）；文件头长度闸 MappedErbc.kt:122-124 /
 *   ErbcReader.kt:562-564。两处 parseHeader 判定与消息逐字同判据（MappedErbc 类 KDoc 自述），
 *   故同一份字节三入口异常消息逐字相等 —— 判据 1 的一致性对拍就钉在这一点。
 *
 * 语义锚（RT2 自研 ERBM 容器，原版无 Module 容器，无 C# 锚，TODO(verify) 见交付）：
 * - 蓝图 era-arch-v61u.md 2 节 :62（头部强制字段 fail-closed）、:64（拒载落在已严格校验的
 *   magic 与版本白名单，Module 用独立 magic ERBM、Linked 升 major v3；五连测口径）、
 *   10 节 :295（ABI-4 Gate = 真实旧 Reader 二进制五连负控）、11 节 :307（Module 态被旧 Reader
 *   误加载 = 硬错误负控）、12.1 节 :359（缺口归属 M2-2 与 M0-3）、17 节 :603（M0-3 判据）。
 * - 移植侧规约 specs 下的 erbc-format.md :30-31（禁止静默降级，解码期抛 ErbcDecodeException）、
 *   :53（magic 不符即拒）、:54（版本非 1 或 2 不做向前兼容解释）、:39-45（文件头布局）；
 *   specs 下的 patch-format.yaml :259-262（缺终止行拒绝）、:276-277 与 :294-295（不返回半成品
 *   对象、禁止半装载）为同一文本头终止行与全或无口径。
 * - 旧 Reader 拒载锚照实写移植侧实树锚（非 C# 锚），禁止脑补。
 *
 * 与 M2-2 五连测（ModuleContainerLegacyReaderTest 7 例）不重复：那 7 例是同一份合法字节的
 * 链路 1 到 5 各一次加两条机制钉；本类只补 ABI-4 Gate 负控半边里它们未覆盖的面 ——
 * 三入口文案逐字一致性对拍、畸形变体矩阵、不返回与不降级的可操作断言、Legacy 逐字节不变收拒
 * 成对、两角色偏移文案交叉对拍、闸常量未放宽的机制钉。逐条说明见交付。
 *
 * 反向证明（组长验收时临时变异旧 Reader，worker 不自己动被测对象）：
 * - magic 闸放宽 ⇒ 判据 1 与判据 2 的 magic 不符与偏移 0 断言、判据 5 的 Module 侧必红；
 * - 版本白名单加 v3 ⇒ 判据 5 的 Linked 侧、判据 3 与关态机制钉的 sectionCountFor(3) 必红；
 * - 异常改吞（返回空 Program 或 null）⇒ 判据 3 的 runCatching 与判据 2 的 capture 必红；
 * - 改容器 magic 为 ERBC 或把 Linked 退回 v2 ⇒ 关态机制钉必红。
 * 变异与恢复只在组长验收阶段发生，恢复后逐字节 sha256 对齐。
 */
class ModuleStateMisloadNegativeTest {

    @TempDir
    lateinit var tempDir: Path

    private val fx = ModuleStateMisloadFixture

    /** 每个变体写独立文件（Windows 上映射存活期间同名文件不可重写，见 MappedErbc 类 KDoc）。 */
    private var fileSeq = 0

    private fun tempPath(bytes: ByteArray): Path {
        val p = tempDir.resolve("misload-${fileSeq++}.erbc")
        Files.write(p, bytes)
        return p
    }

    /** 异常只可能来自 parseHeader 文件头阶段的三条闸（由此证「构造 Program 之前」）。 */
    private val headerPhaseMarkers = listOf("magic 不符", "不支持的 .erbc 版本", "文件不足文件头长度")

    /** 段正文与 Program 构造阶段的关键词；异常文案里出现任一即说明已越过文件头。 */
    private val programPhaseMarkers = listOf(
        "段 ", "段表", "函数", "指令", "CODE", "字符串表", "LINE_TABLE", "行号表",
        "表达式", "实参", "Program", "FUNCTION_TABLE",
    )

    /**
     * 单入口捕获：正常返回（被当成合法产物装载）或抛非 ErbcDecodeException 都判红，
     * 异常类型精确钉到 ErbcDecodeException。
     */
    private fun captureOne(tag: String, entry: String, block: () -> Any?): ErbcDecodeException {
        val thrown = try {
            block()
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue(thrown != null, "$tag/$entry 必须抛异常，不得正常返回被当成合法产物装载")
        assertTrue(
            thrown is ErbcDecodeException,
            "$tag/$entry 异常类型应为 ErbcDecodeException，实为 ${thrown!!.javaClass.name}: ${thrown.message}",
        )
        return thrown as ErbcDecodeException
    }

    /** 三入口逐入口捕获，顺序恒为 probe / decode / open。 */
    private fun captureOld(tag: String, bytes: ByteArray): List<ErbcDecodeException> = listOf(
        captureOne(tag, "probe") { ErbcReader.probe(bytes) },
        captureOne(tag, "decode") { ErbcReader.decode(bytes) },
        captureOne(tag, "open") { MappedErbc.open(tempPath(bytes)) },
    )

    /** 逐入口钉：偏移、可定位前缀、文案、构造 Program 之前（文件头阶段且不含段正文关键词）。 */
    private fun assertHeaderRejects(
        tag: String,
        exs: List<ErbcDecodeException>,
        expectedOffset: Int,
        vararg expectedParts: String,
    ) {
        val entries = listOf("probe", "decode", "open")
        for ((i, ex) in exs.withIndex()) {
            val entry = "$tag/${entries[i]}"
            val msg = ex.message!!
            assertEquals(expectedOffset, ex.offset, "$entry 拒绝偏移")
            assertTrue(msg.startsWith("偏移 $expectedOffset:"), "$entry 异常须带可定位偏移前缀，实为 $msg")
            for (part in expectedParts) {
                assertTrue(msg.contains(part), "$entry 文案须含 $part，实为 $msg")
            }
            assertTrue(
                headerPhaseMarkers.any { msg.contains(it) },
                "$entry 异常须来自 parseHeader 文件头阶段（magic/版本/长度闸），实为 $msg",
            )
            assertTrue(
                programPhaseMarkers.none { msg.contains(it) },
                "$entry 异常文案不得含段正文或 Program 构造阶段关键词（即构造 Program 之前已拒），实为 $msg",
            )
        }
    }

    /** 新 Reader 必须明确拒畸形变体，并带可定位理由；正常返回即判红。 */
    private fun assertNewRejects(v: ModuleStateMisloadFixture.MisloadVariant) {
        val thrown = try {
            ModuleContainerCodec.decode(v.bytes)
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue(thrown != null, "${v.id} 新 Reader 应拒绝畸形变体，却正常返回了一个容器模型")
        assertTrue(
            thrown is ModuleContainerException,
            "${v.id} 新 Reader 异常类型应为 ModuleContainerException，实为 ${thrown!!.javaClass.name}",
        )
        assertTrue(
            thrown.message!!.contains(v.newReason),
            "${v.id} 新 Reader 拒绝理由应含 ${v.newReason}，实为 ${thrown.message}",
        )
    }

    // ------------------------------------------------------------------
    // 判据 1：多入口一致性
    // ------------------------------------------------------------------

    @Test
    fun `判据1 同一份Module态字节三入口逐入口硬拒且偏移文案一致性对拍`() {
        val bytes = fx.moduleBytes()
        // 同一份字节新 Reader 能读（排除「旧 Reader 见到啥都拒」的假绿，与 M2-2 链路1 同为正控）。
        assertEquals(ContainerRole.MODULE, ModuleContainerCodec.decode(bytes).role)

        val exs = captureOld("判据1/Module", bytes)
        assertHeaderRejects("判据1/Module", exs, 0, "magic 不符", "45 52 42 43", "45 52 42 4d")

        // 一致性对拍：三入口异常类型相同、偏移全等、文案逐字相同
        // （两处 parseHeader 判定与消息逐字同判据，M2-2 链路 4 只各断言一次 contains，未做交叉对拍）。
        assertTrue(exs.all { it.javaClass == ErbcDecodeException::class.java }, "三入口异常类型一致")
        assertEquals(setOf(0), exs.map { it.offset }.toSet(), "三入口拒绝偏移一致且都为 0")
        assertEquals(exs[0].message, exs[1].message, "probe 与 decode 拒绝文案逐字一致")
        assertEquals(exs[1].message, exs[2].message, "decode 与 open 拒绝文案逐字一致")
    }

    // ------------------------------------------------------------------
    // 判据 2：畸形变体矩阵
    // ------------------------------------------------------------------

    @Test
    fun `判据2 畸形变体矩阵逐变体新Reader明确拒且旧Reader三入口构造Program之前拒`() {
        val variants = fx.moduleVariants()
        assertTrue(variants.size >= 15, "矩阵变体数应覆盖全部畸形面，实为 ${variants.size}")
        for (v in variants) {
            // 新 Reader 明确拒（带可定位理由），不是悄悄降级成别的模型。
            assertNewRejects(v)
            // 旧 Reader 三入口都在构造 Program 之前硬拒，理由与偏移符合该变体相位；
            // 任一变体被当成 Legacy 正常装载，captureOne 会带变体 id 判红。
            val exs = captureOld("判据2/${v.id}", v.bytes)
            assertHeaderRejects("判据2/${v.id}", exs, v.oldOffset, v.oldReason)
        }
    }

    // ------------------------------------------------------------------
    // 判据 3：不降级不吞异常
    // ------------------------------------------------------------------

    @Test
    fun `判据3 不降级不吞异常三入口抛可定位异常不返回null空Program不落入低版本兼容分支`() {
        val bytes = fx.moduleBytes()
        val results = listOf(
            "probe" to runCatching { ErbcReader.probe(bytes) as Any },
            "decode" to runCatching { ErbcReader.decode(bytes) as Any },
            "open" to runCatching { MappedErbc.open(tempPath(bytes)) as Any },
        )
        // 不返回 null、不返回空 Program、不产出半装载对象：三入口必须全部 failure。
        for ((entry, r) in results) {
            assertTrue(
                r.isFailure,
                "判据3/$entry 必须抛异常，不得正常返回（含返回 null 或空 Program 或被吞异常）",
            )
            assertNull(
                r.getOrNull(),
                "判据3/$entry failure 无成功值，未流出任何 ErbcHeader / Program / MappedErbc",
            )
        }
        // 异常类型与偏移逐项钉。
        val exs = results.map { (entry, r) ->
            val t = r.exceptionOrNull()!!
            assertTrue(
                t is ErbcDecodeException,
                "判据3/$entry 异常类型应为 ErbcDecodeException，实为 ${t.javaClass.name}: ${t.message}",
            )
            t as ErbcDecodeException
        }
        assertHeaderRejects("判据3", exs, 0, "magic 不符")

        // 不落入 ErbcFormat 低版本兼容分支：v3 无白名单项、只有 v1 与 v2 两个合法版本，
        // 异常文案也不得含兼容 / 降级解释这类放行词。
        assertEquals(-1, ErbcFormat.sectionCountFor(3), "v3 不在版本白名单，无低版本兼容分支")
        assertEquals(ErbcFormat.SECTION_COUNT_V1, ErbcFormat.sectionCountFor(1))
        assertEquals(ErbcFormat.SECTION_COUNT_V2, ErbcFormat.sectionCountFor(2))
        assertEquals("ERBC", ErbcFormat.MAGIC_TEXT, "ERBM 不是合法 magic，不会走兼容解释")
        for ((i, ex) in exs.withIndex()) {
            assertFalse(ex.message!!.contains("兼容"), "判据3 入口$i 文案不得含兼容放行词")
            assertFalse(ex.message!!.contains("降级解释"), "判据3 入口$i 文案不得含降级放行词")
        }
    }

    // ------------------------------------------------------------------
    // 判据 4：Legacy 反向对照
    // ------------------------------------------------------------------

    @Test
    fun `判据4 Legacy反向对照真实产物同批正常装载且逐字节不变收拒成对`() {
        val variants = fx.moduleVariants()

        // 同批：先逐个确认变体被旧 Reader 拒（布尔统计，不重复判据 2 的逐条理由钉）。
        var rejected = 0
        for (v in variants) {
            val exs = captureOld("判据4/${v.id}", v.bytes)
            assertTrue(exs.all { it.offset == v.oldOffset }, "${v.id} 变体应被旧 Reader 拒")
            rejected++
        }

        // 同一批里真实 Legacy 产物必须仍被三入口正常装载。
        val legacy = fx.legacyErbcBytes()
        val before = legacy.copyOf()
        val path = tempPath(legacy)
        assertTrue(Files.readAllBytes(path).contentEquals(before), "写盘无损，逐字节等于源产物")

        val header = ErbcReader.probe(legacy)
        assertEquals(ErbcFormat.LATEST_VERSION, header.version, "Legacy probe 版本")
        val program = ErbcReader.decode(legacy)
        assertTrue(program.functions.isNotEmpty(), "Legacy decode 解出至少一个函数")
        val mapped = MappedErbc.open(path)
        try {
            assertEquals(ErbcFormat.LATEST_VERSION, mapped.header.version)
            assertEquals(legacy.size, mapped.byteSize)
        } finally {
            mapped.close()
        }

        // 逐字节不变：入参数组与写盘文件都未被装置改动。
        assertTrue(legacy.contentEquals(before), "Legacy 入参字节逐字不变")
        assertTrue(Files.readAllBytes(path).contentEquals(before), "装载后写盘文件逐字不变")

        // 收拒成对：变体全被拒、同批 Legacy 全被收，负控不是靠「把所有东西都拒掉」蒙混。
        assertEquals(variants.size, rejected, "全部变体都被拒，无漏网")
        assertTrue(program.functions.isNotEmpty(), "同批 Legacy 仍被收（与全拒相反）")
    }

    // ------------------------------------------------------------------
    // 判据 5：两角色可区分
    // ------------------------------------------------------------------

    @Test
    fun `判据5 两角色可区分Module走magic闸Linked走版本白名单闸文案偏移不串`() {
        val moduleExs = captureOld("判据5/Module", fx.moduleBytes())
        assertHeaderRejects("判据5/Module", moduleExs, 0, "magic 不符")
        val linkedExs = captureOld("判据5/Linked-v3", fx.linkedBytes())
        assertHeaderRejects("判据5/Linked-v3", linkedExs, 4, "不支持的 .erbc 版本 3", "不做降级解释")

        // 可分辨：偏移不同、文案口径互斥、Linked 的 v3 不在版本白名单。
        for (m in moduleExs) {
            assertFalse(m.message!!.contains("不支持的 .erbc 版本"), "Module 走 magic 闸，不得串到版本闸")
            assertFalse(m.message!!.contains("不做降级解释"), "Module 文案不得含版本闸放行词")
        }
        for (l in linkedExs) {
            assertFalse(l.message!!.contains("magic 不符"), "Linked v3 走版本闸，不得串到 magic 闸")
            assertTrue(l.message!!.contains("v1/v2"), "Linked v3 文案点明白名单只认 v1 与 v2")
        }
        assertNotEquals(moduleExs[0].offset, linkedExs[0].offset, "两角色拒绝偏移可分辨（0 与 4）")
        assertEquals(-1, ErbcFormat.sectionCountFor(3), "Linked v3 触发版本白名单闸，非被兼容放行")

        // 两种角色各自的 magic 事实：Module 用独立 ERBM、Linked 沿用 ERBC。
        assertTrue(
            fx.moduleBytes().copyOfRange(0, 4).contentEquals("ERBM".toByteArray(Charsets.US_ASCII)),
            "Module 用独立 ERBM magic",
        )
        assertTrue(
            fx.linkedBytes().copyOfRange(0, 4).contentEquals("ERBC".toByteArray(Charsets.US_ASCII)),
            "Linked 沿用 ERBC magic",
        )
    }

    // ------------------------------------------------------------------
    // 判据 6 / 关态机制钉：装置只观察，被测对象两道闸常量未被放宽
    // ------------------------------------------------------------------

    @Test
    fun `关态机制钉 旧Reader两道闸常量与容器magic未被放宽装置只观察`() {
        assertEquals("ERBC", ErbcFormat.MAGIC_TEXT)
        assertEquals('C'.code, ErbcFormat.MAGIC_3)
        assertEquals(-1, ErbcFormat.sectionCountFor(3), "版本白名单未加 v3")
        assertEquals(ErbcFormat.SECTION_COUNT_V1, ErbcFormat.sectionCountFor(1))
        assertEquals(ErbcFormat.SECTION_COUNT_V2, ErbcFormat.sectionCountFor(2))
        assertEquals(ErbcFormat.HEADER_BYTES, 10, ".erbc 文件头长度未放宽")
        assertEquals("ERBM", ModuleContainerFormat.MAGIC_MODULE_TEXT, "容器 magic 未改成 ERBC 来绕过 magic 闸")
        assertEquals("ERBC", ModuleContainerFormat.MAGIC_LINKED_TEXT)
        assertEquals(3, ModuleContainerFormat.LINKED_CONTAINER_VERSION, "Linked 未退回 v2 让旧 Reader 收")
        assertEquals(3, ModuleContainerFormat.MODULE_CONTAINER_VERSION)
        assertEquals(3, ModuleContainerFormat.ISA_VERSION)
    }
}
