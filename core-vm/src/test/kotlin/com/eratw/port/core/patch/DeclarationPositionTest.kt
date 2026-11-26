package com.eratw.port.core.patch

import com.eratw.port.core.vars.DimSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ModDX-3 DX3-C 定向钉：`RT2MANIFEST/7` 的 `declaration-positions` 可选段与冲突报告位置回显。
 *
 * 钉三件事：
 * - 位置表空 ⇒ 旧 /3 /4 /5 /6 编码字节逐字不变（金标 + 各版 magic 不升）；
 * - 带位置产物 encode → decode 逐条相等、写读写逐字节相同、段序固定；
 * - 冲突消息有位置含 文件:行号，无位置与旧文案逐字一致。
 *
 * 位置键口径 = 冲突报告里那条声明的人类可读原文（[DeclarationSite.declaration]），
 * 由作者显式填写，本文件不测任何反射造行号路径（该路径不存在）。
 */
class DeclarationPositionTest {

    private fun dim(name: String): DimSpec = DimSpec(name = name, isStr = false, dims = intArrayOf(1))

    private fun encodeLines(d: ModuleDescriptor): MutableList<String> =
        String(ManifestCodec.encode(d), Charsets.UTF_8).split("\n").dropLast(1).toMutableList()

    private fun toBytes(lines: List<String>): ByteArray =
        (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)

    private fun magicOf(d: ModuleDescriptor): String =
        String(ManifestCodec.encode(d), Charsets.UTF_8).lineSequence().first()

    /** 手工拼出的 /3 最小档金标；位置表必须让这份字节逐字不变。 */
    private fun legacyV3Golden(): String = listOf(
        "RT2MANIFEST/3",
        "@meta",
        "S6d6f642e656d707479", // hex("mod.empty")
        "S", // version = ""
        "/",
        "@requires", "I0", "/",
        "@load-after", "I0", "/",
        "@external-calls", "I0", "/",
        "@override-functions", "I0", "/",
        "@global-dims", "I0", "/",
        "@private-dims", "I0", "/",
        "@csv-patches", "I0", "/",
        "@csv-value-patches", "I0", "/",
        "@entity-refs", "I0", "/",
        "@component-schemas", "I0", "/",
        "@entity-archetypes", "I0", "/",
        "@relationship-schemas", "I0", "/",
        "@hooks", "I0", "/",
        "@interaction-hooks", "I0", "/",
        "@phase-jobs", "I0", "/",
        "@exported-macros", "I0", "/",
        "@artifact-sha256", "S", "/",
    ).joinToString("\n") + "\n"

    // -----------------------------------------------------------------------
    // 判据 1：空位置表 = 旧编码字节逐字不变
    // -----------------------------------------------------------------------
    @Test
    fun emptyPositionsLeaveV3BytesByteIdentical() {
        val d = ModuleDescriptor(packageId = "mod.empty", declarationPositions = emptyMap())
        val encoded = String(ManifestCodec.encode(d), Charsets.UTF_8)
        assertEquals(legacyV3Golden(), encoded)
        assertTrue(!encoded.contains("declaration-positions"))
        assertEquals(emptyMap(), ManifestCodec.decode(ManifestCodec.encode(d)).declarationPositions)
    }

    @Test
    fun emptyPositionsDoNotUpgradeV4V5V6() {
        val base = ModuleDescriptor(packageId = "mod.empty")
        assertEquals("RT2MANIFEST/4", magicOf(base.copy(displayName = "d")))
        assertEquals("RT2MANIFEST/5", magicOf(base.copy(settings = listOf(IntSettingDeclaration("k")))))
        assertEquals("RT2MANIFEST/6", magicOf(base.copy(minApi = 7)))

        // 显式写空表与缺省字段的字节逐字相同（四档全量覆盖）。
        for (d in listOf(
            base,
            base.copy(displayName = "d"),
            base.copy(settings = listOf(IntSettingDeclaration("k"))),
            base.copy(minApi = 7),
            base.copy(displayName = "d", settings = listOf(IntSettingDeclaration("k")), minApi = 7),
        )) {
            val empty = d.copy(declarationPositions = emptyMap())
            assertTrue(ManifestCodec.encode(d).contentEquals(ManifestCodec.encode(empty)))
        }
    }

    // -----------------------------------------------------------------------
    // 判据 2：带位置产物往返逐条相等
    // -----------------------------------------------------------------------
    @Test
    fun positionsRoundTripEntryByEntry() {
        val positions = mapOf(
            "global-dim MOD_STATE" to "Scripts/Main.ERB:12",
            "component-schema Education 的字段 mentorId" to "Schemas/Education.ERB:88",
            "csv-patch CFLAG:MyFlag#-1" to "Data/CFLAG.csv:3",
        )
        val d = ModuleDescriptor(packageId = "mod.pos", version = "1", declarationPositions = positions)
        val bytes = ManifestCodec.encode(d)
        assertEquals("RT2MANIFEST/7", String(bytes, Charsets.UTF_8).lineSequence().first())
        val back = ManifestCodec.decode(bytes)
        assertEquals(positions.size, back.declarationPositions.size)
        for ((k, v) in positions) assertEquals(v, back.declarationPositions[k])
        assertEquals(d.packageId, back.packageId)
        assertEquals(d.version, back.version)
        // 写→读→写逐字节相同（Map 写侧按键排序，读侧校验严格升序）。
        assertTrue(bytes.contentEquals(ManifestCodec.encode(back)))
    }

    @Test
    fun positionSegmentIsLastAndComposesWithSettingsAndMinApi() {
        val d = ModuleDescriptor(
            packageId = "mod.pos",
            declarationPositions = mapOf("global-dim X" to "A.ERB:1"), // 无 metadata / settings / min-api
        )
        val lines = encodeLines(d)
        val sections = lines.filter { it.startsWith("@") }.map { it.substring(1) }
        assertEquals("RT2MANIFEST/7", lines.first())
        assertEquals(21, sections.size)
        assertEquals("min-api", sections[sections.size - 2])
        assertEquals("declaration-positions", sections.last())
        assertTrue(sections.indexOf("settings") < sections.indexOf("min-api"))
        // /7 沿用 /5 /6 的「段恒在场、字段数可判定」口径：未声明也写计数 0 / 闸值 0。
        val back = ManifestCodec.decode(toBytes(lines))
        assertEquals(0, back.minApi)
        assertTrue(back.settings.isEmpty())
        assertEquals("", back.displayName)
    }

    // -----------------------------------------------------------------------
    // 判据 3：冲突消息有/无位置两档
    // -----------------------------------------------------------------------
    private fun conflictingPair(positionsA: Map<String, String>, positionsB: Map<String, String>) =
        DeclarationRegistry()
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.a",
                    globalDims = listOf(dim("MOD_STATE")),
                    declarationPositions = positionsA,
                ),
                "mod.a",
            )
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.b",
                    globalDims = listOf(dim("MOD_STATE")),
                    declarationPositions = positionsB,
                ),
                "mod.b",
            )

    @Test
    fun conflictMessageWithoutPositionIsByteIdentical() {
        val ex = assertFailsWith<DeclarationConflictException> {
            conflictingPair(emptyMap(), emptyMap()).freeze()
        }
        assertEquals(
            "声明事务冲突（整包拒绝，无部分注册）：\n" +
                "  - [global-dims] MOD_STATE：" +
                "global-dim MOD_STATE（mod.a @ mod.a） vs global-dim MOD_STATE（mod.b @ mod.b）" +
                " —— 同名全局变量已声明（Global×Global 重复；全局命名空间不允许多包同名）",
            ex.message,
        )
        assertTrue(ex.conflicts.all { it.first.position == null && it.second.position == null })
    }

    @Test
    fun conflictMessageWithPositionShowsFileLine() {
        val ex = assertFailsWith<DeclarationConflictException> {
            conflictingPair(
                mapOf("global-dim MOD_STATE" to "ModA.ERB:12"),
                mapOf("global-dim MOD_STATE" to "ModB.ERB:34"),
            ).freeze()
        }
        val m = ex.message!!
        assertTrue(m.contains("ModA.ERB:12"), m)
        assertTrue(m.contains("ModB.ERB:34"), m)
        assertTrue(m.contains("位置 ModA.ERB:12") && m.contains("位置 ModB.ERB:34"), m)
        // 位置之外的文案骨架不变（有位置只在 site 尾部追加，不动前缀 / 冲突键 / 原因）。
        assertTrue(m.startsWith("声明事务冲突（整包拒绝，无部分注册）：\n  - [global-dims] MOD_STATE："), m)
        assertTrue(m.contains(" —— 同名全局变量已声明（Global×Global 重复；全局命名空间不允许多包同名）"), m)
        assertEquals("ModA.ERB:12", ex.conflicts[0].first.position)
        assertEquals("ModB.ERB:34", ex.conflicts[0].second.position)
    }

    @Test
    fun declarationSiteToStringKeepsLegacyShapeWithoutPosition() {
        val plain = DeclarationSite("mod.a", "mod.a", "global-dim MOD_STATE")
        assertEquals("global-dim MOD_STATE（mod.a @ mod.a）", plain.toString())
    }

    // -----------------------------------------------------------------------
    // 坏档负控：新段缺席/乱序按 fail-closed 拒（同 min-api 段先例）
    // -----------------------------------------------------------------------
    @Test
    fun missingPositionSegmentIsRejected() {
        val lines = encodeLines(ModuleDescriptor("mod.pos", declarationPositions = mapOf("global-dim X" to "A.ERB:1")))
        val start = lines.indexOf("@declaration-positions")
        assertTrue(start >= 0)
        lines.subList(start, lines.size).clear() // 删掉整段（含段尾 /）
        assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(lines)) }
    }

    @Test
    fun outOfOrderPositionKeysAreRejected() {
        val lines = encodeLines(
            ModuleDescriptor(
                "mod.pos",
                declarationPositions = mapOf("a-key" to "A.ERB:1", "b-key" to "B.ERB:2"),
            ),
        )
        val start = lines.indexOf("@declaration-positions") + 2 // 跳过 @段名 与 I2 计数
        // 交换两条 (key, value) 对，读侧严格升序校验应变格式异常。
        val k1 = lines[start]
        val v1 = lines[start + 1]
        val k2 = lines[start + 2]
        val v2 = lines[start + 3]
        lines[start] = k2
        lines[start + 1] = v2
        lines[start + 2] = k1
        lines[start + 3] = v1
        assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(lines)) }
    }
}
