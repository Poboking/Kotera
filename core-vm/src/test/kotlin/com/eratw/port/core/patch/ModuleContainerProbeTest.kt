package com.eratw.port.core.patch

import com.eratw.port.core.patch.ModuleContainerFormat.MAGIC_LINKED_TEXT
import com.eratw.port.core.patch.ModuleContainerFormat.MAGIC_MODULE_TEXT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 四池容器探测分流（CONTRACT-RT2-M2CODEC 裁定 2「探测真源」）：[ModuleContainerFormat.probe]
 * 的正控 / 负控矩阵。先探后读的分流面有两个消费方（`ModPatchLoader` 跳过 + `ModChainAssembly`
 * 收集），两者共用本函数；这里钉判据本身：
 *
 * - **正控**：ERBM v3 → MODULE；ERBC v3 → LINKED_PROGRAM（真实 [ModuleContainerCodec.encode]
 *   产物字节）。
 * - **负控（先探后读的负控锚，裁定 2）**：legacy `.patch` 文本头首行是 `erbc-patch <版本>`
 *   （`PatchHeader` 的 `FIELD_FORMAT`，[PatchHeaderCodec.encode] 固定首行）—— 首 4 字节为
 *   **小写** `erbc`，按字节比较永不可能等于大写 `ERBM`/`ERBC` ⇒ 探测恒 miss。本类用生产
 *   [PatchArtifacts] 落出的真实 legacy 补丁字节钉死这一条；同源负控还有 legacy `.erbc`
 *   （ERBC magic + v1/v2 版本白名单内）= 旧 Reader 产物，不是本格式 LINKED 态 ⇒ null。
 */
class ModuleContainerProbeTest {

    // ------------------------------------------------------------------
    // 正控：真实容器字节
    // ------------------------------------------------------------------

    @Test
    fun `正控 ERBM容器字节探测为MODULE`() {
        val bytes = ModuleContainerFixture.moduleBytes()
        assertEquals(MAGIC_MODULE_TEXT, String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(ContainerRole.MODULE, ModuleContainerFormat.probe(bytes))
    }

    @Test
    fun `正控 ERBC_v3容器字节探测为LINKED`() {
        val bytes = ModuleContainerFixture.linkedBytes()
        assertEquals(MAGIC_LINKED_TEXT, String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(ContainerRole.LINKED_PROGRAM, ModuleContainerFormat.probe(bytes))
    }

    @Test
    fun `正控 真Program载荷容器探测为MODULE且可decode`() {
        val bytes = ModuleContainerProgramFixture.containerBytes(
            "mod.probe",
            ModuleContainerProgramFixture.additiveProgram(),
        )
        assertEquals(ContainerRole.MODULE, ModuleContainerFormat.probe(bytes))
        val decoded = ModuleContainerCodec.decode(bytes)
        assertEquals("mod.probe", decoded.descriptor.packageId)
        assertTrue(decoded.pools!!.codeWords.isNotEmpty())
    }

    // ------------------------------------------------------------------
    // 负控：legacy 两形永不可能命中（先探后读的分流不会误跳真补丁）
    // ------------------------------------------------------------------

    @Test
    fun `负控 legacy补丁文本头首键erbc-patch小写_探测恒miss`() {
        val bytes = legacyPatchBytes()
        // 常量锚（裁定 2）：legacy 文本头首行固定 `erbc-patch <版本>`，首 4 字节小写 erbc。
        assertEquals(
            "erbc-patch",
            bytes.decodeToString(0, 10, throwOnInvalidSequence = true).substringBefore(' '),
        )
        assertNull(ModuleContainerFormat.probe(bytes), "legacy `.patch` 探测必须恒 miss")
    }

    @Test
    fun `负控 legacy_erbc_v2旧Reader产物探测miss`() {
        // 真实旧 Reader 产物（生产 ErbcWriter v2）：ERBC magic + version 1/2 不在容器域。
        val bytes = ModuleContainerFixture.legacyErbcBytes()
        assertEquals("ERBC", String(bytes, 0, 4, Charsets.US_ASCII))
        assertNull(ModuleContainerFormat.probe(bytes), "legacy `.erbc` 不是四池容器")
    }

    // ------------------------------------------------------------------
    // 负控：畸形与近形字节
    // ------------------------------------------------------------------

    @Test
    fun `负控 不足前缀8字节探测miss`() {
        val bytes = ModuleContainerFixture.moduleBytes()
        assertNull(ModuleContainerFormat.probe(bytes.copyOf(7)))
        assertNull(ModuleContainerFormat.probe(ByteArray(0)))
    }

    @Test
    fun `负控 容器版本与role不配套探测miss`() {
        // ERBM + version 2：magic 命中但版本不符（容器版本当前只认 3）。
        val bad = ModuleContainerFixture.withContainerVersion(ModuleContainerFixture.moduleBytes(), 2)
        assertNull(ModuleContainerFormat.probe(bad))
        // ERBC + version 2 = legacy .erbc 形（版本白名单内）——不是 LINKED 容器。
        val legacyShape = ModuleContainerFixture.withContainerVersion(ModuleContainerFixture.linkedBytes(), 2)
        assertNull(ModuleContainerFormat.probe(legacyShape))
    }

    @Test
    fun `负控 随机与近形字节探测miss`() {
        val module = ModuleContainerFixture.moduleBytes()
        assertNull(ModuleContainerFormat.probe(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        // 近形 magic：大小写与末位字母都算不命中（按字节比较，不做大小写归一）。
        assertNull(ModuleContainerFormat.probe(ModuleContainerFixture.withMagic(module, "ERBN")))
        assertNull(ModuleContainerFormat.probe(ModuleContainerFixture.withMagic(module, "erbm")))
    }

    // ------------------------------------------------------------------
    // 夹具：真实 legacy .patch 字节（生产 PatchHeader + PatchArtifacts 落形）
    // ------------------------------------------------------------------

    /** 最小真 legacy 补丁：15 字段规范头（首行 `erbc-patch 2`）+ 空载荷。 */
    private fun legacyPatchBytes(): ByteArray {
        val header = PatchHeader(
            baseInstructionCount = 0,
            baseFunctionCount = 0,
            baseStatementCount = 0,
            baseStringCount = 0,
            baseLongCount = 0,
            baseExprCount = 0,
            baseSha256 = PatchArtifacts.sha256Hex(ByteArray(0)),
            compiler = PatchArtifacts.compilerSummary(),
            patchFunctionCount = 0,
            patchStatementCount = 0,
            patchStringCount = 0,
            patchLongCount = 0,
            patchExprCount = 0,
            entryFunction = "ENTRY",
        )
        return PatchArtifacts.of(header, ModuleContainerFixture.legacyErbcBytes()).write()
    }
}
