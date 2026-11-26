package com.eratw.port.core.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ModDX4-B1 线格式定向钉：`RT2MANIFEST/8` 的 `write-grants` 可选段（受控写授权）。
 *
 * 钉三件事（口径照 `DeclarationPositionTest`（`RT2MANIFEST/7` 先例）逐条复刻）：
 * - **反向证明（硬闸）**：授权表空 ⇒ 旧 /3 /4 /5 /6 /7 编码字节逐字不变（金标 + 各版 magic
 *   不升 + 显式空表与缺省字段字节相同）；
 * - 带授权产物 encode → decode 逐条相等、写读写逐字节相同、段序固定（末段追加）；
 * - 构造期 fail-closed：大写归一后重复键 / 空键整包拒（decode 期经 guarded 归一成带行号的
 *   格式异常）。
 */
class ModDx4WriteGrantManifestTest {

    private fun encodeLines(d: ModuleDescriptor): MutableList<String> =
        String(ManifestCodec.encode(d), Charsets.UTF_8).split("\n").dropLast(1).toMutableList()

    private fun toBytes(lines: List<String>): ByteArray =
        (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)

    private fun magicOf(d: ModuleDescriptor): String =
        String(ManifestCodec.encode(d), Charsets.UTF_8).lineSequence().first()

    private fun grant(name: String, isStr: Boolean = false): WriteGrantDeclaration =
        WriteGrantDeclaration(name, isStr)

    /** 手工拼出的 /3 最小档金标（与 DeclarationPositionTest 同一份字节）；授权表空必须逐字不变。 */
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
    // 反向证明①（契约判据）：空授权表 = 旧编码字节逐字不变
    // -----------------------------------------------------------------------
    @Test
    fun emptyGrantsLeaveV3BytesByteIdentical() {
        val d = ModuleDescriptor(packageId = "mod.empty", writeGrants = emptyList())
        val encoded = String(ManifestCodec.encode(d), Charsets.UTF_8)
        assertEquals(legacyV3Golden(), encoded)
        assertTrue(!encoded.contains("write-grants"))
        assertEquals(emptyList(), ManifestCodec.decode(ManifestCodec.encode(d)).writeGrants)
    }

    @Test
    fun emptyGrantsDoNotUpgradeV4ToV7() {
        val base = ModuleDescriptor(packageId = "mod.empty")
        assertEquals("RT2MANIFEST/4", magicOf(base.copy(displayName = "d")))
        assertEquals("RT2MANIFEST/5", magicOf(base.copy(settings = listOf(IntSettingDeclaration("k")))))
        assertEquals("RT2MANIFEST/6", magicOf(base.copy(minApi = 7)))
        assertEquals(
            "RT2MANIFEST/7",
            magicOf(base.copy(declarationPositions = mapOf("global-dim X" to "A.ERB:1"))),
        )

        // 显式写空表与缺省字段的字节逐字相同（/3–/7 五档全量覆盖）。
        for (d in listOf(
            base,
            base.copy(displayName = "d"),
            base.copy(settings = listOf(IntSettingDeclaration("k"))),
            base.copy(minApi = 7),
            base.copy(declarationPositions = mapOf("global-dim X" to "A.ERB:1")),
            base.copy(
                displayName = "d",
                settings = listOf(IntSettingDeclaration("k")),
                minApi = 7,
                declarationPositions = mapOf("global-dim X" to "A.ERB:1"),
            ),
        )) {
            val empty = d.copy(writeGrants = emptyList())
            assertTrue(ManifestCodec.encode(d).contentEquals(ManifestCodec.encode(empty)))
        }
    }

    // -----------------------------------------------------------------------
    // 带授权产物：往返逐条相等、段序固定
    // -----------------------------------------------------------------------
    @Test
    fun grantsRoundTripEntryByEntry() {
        val grants = listOf(grant("DAY"), grant("PLACE", isStr = true), grant("MONEY"))
        val d = ModuleDescriptor(packageId = "mod.grant", version = "1", writeGrants = grants)
        val bytes = ManifestCodec.encode(d)
        assertEquals("RT2MANIFEST/8", String(bytes, Charsets.UTF_8).lineSequence().first())
        val back = ManifestCodec.decode(bytes)
        assertEquals(grants, back.writeGrants)
        assertEquals(d.packageId, back.packageId)
        // 写→读→写逐字节相同（授权清单按声明序保真，读侧不排序）。
        assertTrue(bytes.contentEquals(ManifestCodec.encode(back)))
    }

    @Test
    fun grantSegmentIsLastAndComposesWithEarlierOptionalSections() {
        val d = ModuleDescriptor(
            packageId = "mod.grant",
            writeGrants = listOf(grant("DAY")),
        )
        val lines = encodeLines(d)
        val sections = lines.filter { it.startsWith("@") }.map { it.substring(1) }
        assertEquals("RT2MANIFEST/8", lines.first())
        assertEquals(22, sections.size)
        assertEquals("declaration-positions", sections[sections.size - 2])
        assertEquals("write-grants", sections.last())
        assertTrue(sections.indexOf("min-api") < sections.indexOf("declaration-positions"))
        // /8 沿用 /5 /6 /7 的「段恒在场、字段数可判定」口径：未声明也写计数 0 / 闸值 0 / 空表。
        val back = ManifestCodec.decode(toBytes(lines))
        assertEquals(0, back.minApi)
        assertTrue(back.settings.isEmpty())
        assertTrue(back.declarationPositions.isEmpty())
        assertEquals("", back.displayName)
        assertEquals(1, back.writeGrants.size)
    }

    // -----------------------------------------------------------------------
    // 构造期 / 解码期 fail-closed
    // -----------------------------------------------------------------------
    @Test
    fun duplicateGrantKeysRejectedCanonicalUppercase() {
        val ex = assertFailsWith<IllegalArgumentException> {
            ModuleDescriptor(packageId = "mod.grant", writeGrants = listOf(grant("DAY"), grant("day")))
        }
        assertTrue(ex.message!!.contains("write-grants 键在本包内必须唯一"))
    }

    @Test
    fun blankGrantKeyRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            ModuleDescriptor(packageId = "mod.grant", writeGrants = listOf(grant("  ")))
        }
    }

    @Test
    fun decodeNormalizesConstructionFailuresToLocatedFormatException() {
        // 手工拼一条空键 grant 的 /8 字节：构造期不变量失败经 Reader.guarded 归一成带行号的格式异常。
        val lines = mutableListOf(
            "RT2MANIFEST/8",
            "@meta", "S6d6f642e656d707479", "S", "S", "S", "S", "/",
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
            "@settings", "I0", "/",
            "@min-api", "I0", "/",
            "@declaration-positions", "I0", "/",
            "@write-grants", "I1", "S", "B0", "/",
        )
        val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(lines)) }
        assertTrue(ex.message!!.contains("构造 WriteGrantDeclaration 失败"), ex.message)
    }
}
