package com.eratw.port.core.patch

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.OpcodeRelocation
import com.eratw.port.core.code.PoolRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [ModuleContainerCodec] 写读往返 / 确定性 / 幂等，以及三道读侧 fail-closed 闸的单测（M2-2）。
 *
 * 权威（RT2 自研格式，无 C# 锚，TODO(verify) 见交付报告）：
 * 蓝图 era-arch-v61u.md 2 节（:60-70）、12.1 节（:359），specs 下的 erbc-format.md
 * 与 patch-format.yaml 的头字段固定序口径。
 *
 * 三道闸逐条钉：
 * 1. 头部三强制字段（CONTAINER_ROLE / REQUIRED_FEATURES / ISA_VERSION）缺一 / 乱序 / 重复 /
 *    未知键 / 非法值 / 未知位 / 缺必需位 / 越界 / CR / 缺终止行 / magic / 版本不配套，
 *    每条都断言错误文案能定位到字段。
 * 2. 重定位机器闸：类别冲突、未登记 opcode、非法操作数槽，必须命中
 *    [OpcodeRelocation.of] 与 OperandRelocation.at 这同一份机器表，不另写分类表。
 * 3. imports 封顶 / 哨兵闸：外部下标越 IMPORT_INDEX_MAX、越 imports 表，必须拒。
 *
 * 负控字节一律经 ModuleContainerFixture.raw（跳过写侧校验）构造，确保测的是读侧闸本身，
 * 不被写侧校验提前拦下造成「假绿」。
 */
class ModuleContainerTest {

    private val fx = ModuleContainerFixture

    /** decode 必须抛 ModuleContainerException，且文案逐片命中（能定位字段）。 */
    private fun assertRejected(bytes: ByteArray, vararg fragments: String): ModuleContainerException {
        val ex = assertFailsWith<ModuleContainerException> { ModuleContainerCodec.decode(bytes) }
        for (f in fragments) {
            assertTrue(ex.message!!.contains(f), "错误文案应含 [$f]，实为：${ex.message}")
        }
        return ex
    }

    // ==================================================================
    // 正向：往返 / 确定性 / 幂等 / 写侧先校验 / 机器表同源
    // ==================================================================

    @Test
    fun `MODULE 写读往返逐字段相等`() {
        val m = fx.moduleModel()
        val back = ModuleContainerCodec.decode(ModuleContainerCodec.encode(m))
        assertEquals(m, back)
    }

    @Test
    fun `LINKED 写读往返逐字段相等`() {
        val m = fx.linkedModel()
        val back = ModuleContainerCodec.decode(ModuleContainerCodec.encode(m))
        assertEquals(m, back)
    }

    @Test
    fun `同一模型两次 encode 逐字节相同（确定性）`() {
        for (m in listOf(fx.moduleModel(), fx.linkedModel())) {
            assertContentEquals(ModuleContainerCodec.encode(m), ModuleContainerCodec.encode(m))
        }
    }

    @Test
    fun `encode-decode-encode 逐字节相同（幂等）`() {
        for (m in listOf(fx.moduleModel(), fx.linkedModel())) {
            val once = ModuleContainerCodec.encode(m)
            val twice = ModuleContainerCodec.encode(ModuleContainerCodec.decode(once))
            assertContentEquals(once, twice)
        }
    }

    @Test
    fun `写侧 encode 先校验 ISA_VERSION 越界模型拒写`() {
        val bad = fx.moduleModel().copy(isaVersion = 99)
        val ex = assertFailsWith<ModuleContainerException> { ModuleContainerCodec.encode(bad) }
        assertTrue(ex.message!!.contains("ISA_VERSION"))
    }

    @Test
    fun `合法重定位逐项命中 OpcodeRelocation 唯一真源`() {
        val m = fx.moduleModel()
        for (r in m.relocations) {
            val expected = OpcodeRelocation.of(r.opcode).at(r.slot)
            assertEquals(expected, r.category, "opcode=${Opcode.nameOf(r.opcode)} 槽 ${r.slot}")
        }
    }

    // ==================================================================
    // 头部三强制字段 fail-closed
    // ==================================================================

    @Test
    fun `CONTAINER_ROLE 缺失即拒`() {
        val bytes = fx.withHeaderLines(fx.rawModule(), listOf(fx.H_FEATURES, fx.H_ISA, fx.H_END))
        assertRejected(bytes, "头部字段")
    }

    @Test
    fun `CONTAINER_ROLE 乱序即拒且文案定位字段`() {
        val bytes = fx.withHeaderLines(
            fx.rawModule(), listOf(fx.H_FEATURES, fx.H_ROLE_MODULE, fx.H_ISA, fx.H_END),
        )
        assertRejected(bytes, "CONTAINER_ROLE")
    }

    @Test
    fun `CONTAINER_ROLE 非法值即拒`() {
        val bytes = fx.withHeaderLines(
            fx.rawModule(), listOf("CONTAINER_ROLE BOGUS", fx.H_FEATURES, fx.H_ISA, fx.H_END),
        )
        assertRejected(bytes, "CONTAINER_ROLE", "非法值")
    }

    @Test
    fun `CONTAINER_ROLE 重复即拒且文案定位被顶掉的字段`() {
        val bytes = fx.withHeaderLines(
            fx.rawModule(), listOf(fx.H_ROLE_MODULE, fx.H_ROLE_MODULE, fx.H_ISA, fx.H_END),
        )
        assertRejected(bytes, "REQUIRED_FEATURES")
    }

    @Test
    fun `未知键即拒且文案定位字段`() {
        val bytes = fx.withHeaderLines(
            fx.rawModule(), listOf("BOGUS x", fx.H_FEATURES, fx.H_ISA, fx.H_END),
        )
        assertRejected(bytes, "CONTAINER_ROLE")
    }

    @Test
    fun `REQUIRED_FEATURES 未知位即拒`() {
        val bytes = fx.withHeaderLines(
            fx.rawModule(), listOf(fx.H_ROLE_MODULE, "REQUIRED_FEATURES 0x8", fx.H_ISA, fx.H_END),
        )
        assertRejected(bytes, "REQUIRED_FEATURES", "未知位")
    }

    @Test
    fun `REQUIRED_FEATURES 缺必需位即拒`() {
        val bytes = fx.withHeaderLines(
            fx.rawModule(), listOf(fx.H_ROLE_MODULE, "REQUIRED_FEATURES 0x3", fx.H_ISA, fx.H_END),
        )
        assertRejected(bytes, "REQUIRED_FEATURES", "缺必需位")
    }

    @Test
    fun `REQUIRED_FEATURES 非 0x 前缀即拒`() {
        val bytes = fx.withHeaderLines(
            fx.rawModule(), listOf(fx.H_ROLE_MODULE, "REQUIRED_FEATURES 7", fx.H_ISA, fx.H_END),
        )
        assertRejected(bytes, "REQUIRED_FEATURES", "0x")
    }

    @Test
    fun `REQUIRED_FEATURES 非法十六进制即拒`() {
        val bytes = fx.withHeaderLines(
            fx.rawModule(), listOf(fx.H_ROLE_MODULE, "REQUIRED_FEATURES 0xZZ", fx.H_ISA, fx.H_END),
        )
        assertRejected(bytes, "REQUIRED_FEATURES", "非法十六进制")
    }

    @Test
    fun `ISA_VERSION 缺失即拒`() {
        val bytes = fx.withHeaderLines(
            fx.rawModule(), listOf(fx.H_ROLE_MODULE, fx.H_FEATURES, fx.H_END),
        )
        assertRejected(bytes, "头部字段")
    }

    @Test
    fun `ISA_VERSION 越界即拒`() {
        val bytes = fx.withHeaderLines(
            fx.rawModule(), listOf(fx.H_ROLE_MODULE, fx.H_FEATURES, "ISA_VERSION 2", fx.H_END),
        )
        assertRejected(bytes, "ISA_VERSION", "越界")
    }

    @Test
    fun `ISA_VERSION 非整数即拒`() {
        val bytes = fx.withHeaderLines(
            fx.rawModule(), listOf(fx.H_ROLE_MODULE, fx.H_FEATURES, "ISA_VERSION abc", fx.H_END),
        )
        assertRejected(bytes, "ISA_VERSION")
    }

    @Test
    fun `缺终止行即拒`() {
        val bytes = fx.withHeaderLines(
            fx.rawModule(), listOf(fx.H_ROLE_MODULE, fx.H_FEATURES, fx.H_ISA, "END"),
        )
        assertRejected(bytes, "终止")
    }

    @Test
    fun `CR 换行即拒`() {
        val bytes = fx.withHeaderText(
            fx.rawModule(),
            "CONTAINER_ROLE MODULE\r\nREQUIRED_FEATURES 0x7\r\nISA_VERSION 3\r\n---\r\n",
        )
        assertRejected(bytes, "CR")
    }

    @Test
    fun `magic 不符即拒`() {
        val bytes = fx.withMagic(fx.rawModule(), "XXXX")
        assertRejected(bytes, "magic")
    }

    @Test
    fun `containerVersion 与 role 不配套即拒`() {
        val bytes = fx.withContainerVersion(fx.rawModule(), 99)
        assertRejected(bytes, "版本")
    }

    // ==================================================================
    // 重定位机器闸（反向证明 ④ 改恒过，这几条必红）
    // ==================================================================

    @Test
    fun `重定位类别与 OpcodeRelocation 机器表冲突即拒`() {
        val bad = fx.moduleModel().copy(
            relocations = listOf(
                RelocationEntry(pc = 0, opcode = Opcode._CALL, slot = 0, category = PoolRef.STRING),
            ),
        )
        assertRejected(fx.raw(bad), "relocations[0]", "冲突")
    }

    @Test
    fun `重定位引用未登记 opcode 即拒`() {
        val bad = fx.moduleModel().copy(
            relocations = listOf(
                RelocationEntry(
                    pc = 0,
                    opcode = Opcode.MAX_OPCODE + 1,
                    slot = 0,
                    category = PoolRef.NONE,
                ),
            ),
        )
        assertRejected(fx.raw(bad), "relocations[0]", "未在 OpcodeRelocation")
    }

    @Test
    fun `重定位非法操作数槽即拒`() {
        val bad = fx.moduleModel().copy(
            relocations = listOf(
                RelocationEntry(pc = 0, opcode = Opcode._CALL, slot = 5, category = PoolRef.FUNCTION),
            ),
        )
        assertRejected(fx.raw(bad), "relocations[0]", "操作数槽")
    }

    // ==================================================================
    // imports 封顶 / 哨兵闸
    // ==================================================================

    @Test
    fun `外部导入下标越 IMPORT_INDEX_MAX 即拒`() {
        val tooBig = -(CallSentinels.IMPORT_INDEX_MAX + 2)
        val bad = fx.moduleModel().copy(callTargets = listOf(tooBig))
        assertRejected(fx.raw(bad), "imports", "IMPORT_INDEX_MAX")
    }

    @Test
    fun `_CALL_a 极负下标回绕撞哨兵风险被封顶闸拒`() {
        // 贴近 Int.MIN_VALUE 的负下标：externalIndexOf 会解出极大 extIdx，
        // 若不封顶，-(extIdx+1) 回绕即撞 CALL_SUPER_SENTINEL（ModuleDescriptor.kt:218-224）。
        val wrapRisk = Int.MIN_VALUE + 5 // 非 CALL_SUPER 也非 TRY_CALL_SUPER
        val bad = fx.moduleModel().copy(callTargets = listOf(wrapRisk))
        assertRejected(fx.raw(bad), "imports", "IMPORT_INDEX_MAX")
    }

    @Test
    fun `外部导入下标越 imports 表即拒`() {
        val bad = fx.moduleModel().copy(callTargets = listOf(CallSentinels.external(5)))
        assertRejected(fx.raw(bad), "imports", "越出 imports 表")
    }

    // ==================================================================
    // LINKED 出口自检 / 零基线
    // ==================================================================

    @Test
    fun `LINKED 态残留导入表即拒`() {
        val bad = fx.linkedModel().copy(imports = listOf("leak"))
        assertRejected(fx.raw(bad), "LINKED_PROGRAM", "残留")
    }

    @Test
    fun `零基线 baseMarker 非 0 即拒`() {
        val m = fx.moduleModel()
        val bad = m.copy(pools = m.pools!!.copy(baseMarker = 1))
        assertRejected(fx.raw(bad), "零基线")
    }
}
