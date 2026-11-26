package com.eratw.port.core.patch

import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.modapi.modDeclarations
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * per-mod 设置项的线格式钉（RT2 per-mod 配置，`/5`）：
 *
 * 1. **只在真的声明了设置项时才升 `/5`**：无设置项 + 无展示元数据仍 `/3`，无设置项 +
 *    有元数据仍 `/4`（既有资产零变化的唯一保证）；
 * 2. **`/5` 四类设置项往返逐字段保真**，声明序 = 落地序（设置页渲染序有语义，不排序）；
 * 3. **解码器同时接受 `/3` `/4` `/5`**：老玩家已导入的包与现有资产不会被新解码器砸掉；
 * 4. **严格性不放松**：`/5` 缺 settings 段 / 计数不符 / 未知类型 / 默认值越界 /
 *    尾部多余字节一律 [ManifestFormatException]，绝不返回半成品描述符；
 * 5. **声明键唯一**在装配期（[com.eratw.port.modapi.ModManifest] init）就拒载。
 *
 * 反向证明在交付消息记录：把 `encode` 的 `/5` 分支改回 `/4` / 把 settings 段写空 ⇒
 * 判据 1、2 用例立红；把 `expectSettings` 的计数校验退化成 `repeat(0)` ⇒ 判据 4 立红。
 */
class ManifestSettingsFormatTest {

    private fun richDescriptor(): ModuleDescriptor = ModDeclarationAdapter.toDescriptor(
        modDeclarations("mod.cfg", version = "1.4.0") {
            displayName("配置演示")
            intSetting(
                key = "cfg.volume",
                default = 5L,
                range = 0L..10L,
                title = "音量",
                description = "0 到 10",
            )
            strSetting(key = "cfg.label", default = "默认标签", title = "标签")
            boolSetting(key = "cfg.auto", default = true, title = "自动")
            enumSetting(
                key = "cfg.mode",
                default = "b",
                candidates = listOf("a", "b", "c"),
                title = "模式",
                description = "三选一",
            )
        },
    )

    private fun encodeText(d: ModuleDescriptor): String =
        String(ManifestCodec.encode(d), StandardCharsets.UTF_8)

    private fun lines(d: ModuleDescriptor): MutableList<String> =
        encodeText(d).split("\n").toMutableList()

    private fun bytesOf(lines: List<String>): ByteArray =
        lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8)

    private fun String.firstLine(): String = substringBefore('\n')

    // ---------------- 判据 1：版本头只在声明设置项时升 /5 ----------------

    @Test
    fun `没有设置项时仍写三版或四版且不出现settings段`() {
        val plain = ModuleDescriptor(packageId = "mod.plain")
        val plainText = encodeText(plain)
        assertEquals("RT2MANIFEST/3", plainText.firstLine())
        assertFalse("@settings" in plainText, "无声明不应写 settings 段")

        val meta = ModuleDescriptor(packageId = "mod.meta", displayName = "带元数据")
        val metaText = encodeText(meta)
        assertEquals("RT2MANIFEST/4", metaText.firstLine())
        assertFalse("@settings" in metaText, "无声明不应写 settings 段")
    }

    @Test
    fun `声明了设置项的包写五版且settings段在末位`() {
        val text = encodeText(richDescriptor())
        assertEquals("RT2MANIFEST/5", text.firstLine())

        val sectionLines = text.split("\n")
        val settingsAt = sectionLines.indexOf("@settings")
        assertTrue(settingsAt > 0, "settings 段必须存在")
        assertEquals(1, sectionLines.count { it == "@settings" }, "settings 段只能出现一次")
        // settings 段之后只剩内容行与段尾 /（settings 是最后一段）。
        val tail = sectionLines.drop(settingsAt)
        assertEquals("/", tail[tail.size - 2], "settings 段必须以段尾 / 收口")
        assertEquals("", tail.last(), "整档必须以 LF 收口")
    }

    // ---------------- 判据 2：/5 四类设置项往返保真 ----------------

    @Test
    fun `五版四类设置项往返逐字段保真且声明序不变`() {
        val original = richDescriptor()
        val decoded = ManifestCodec.decode(ManifestCodec.encode(original))

        assertEquals("mod.cfg", decoded.packageId)
        assertEquals("1.4.0", decoded.version)
        assertEquals("配置演示", decoded.displayName)
        assertEquals(original.settings, decoded.settings, "设置声明必须逐字段往返（含标题/说明）")
        assertEquals(
            listOf("cfg.volume", "cfg.label", "cfg.auto", "cfg.mode"),
            decoded.settings.map { it.key },
            "声明序 = 设置页渲染序，编解码不得重排",
        )
        assertEquals(
            listOf(SettingType.INT, SettingType.STR, SettingType.BOOL, SettingType.ENUM),
            decoded.settings.map { it.type },
        )

        val volume = decoded.settings[0] as IntSettingDeclaration
        assertEquals(5L, volume.default)
        assertEquals(0L, volume.min)
        assertEquals(10L, volume.max)
        val mode = decoded.settings[3] as EnumSettingDeclaration
        assertEquals("b", mode.default)
        assertEquals(listOf("a", "b", "c"), mode.candidates)
    }

    @Test
    fun `可空区间与空候选边界往返保真`() {
        val d = ModuleDescriptor(
            packageId = "mod.edge",
            displayName = "边界",
            settings = listOf(
                IntSettingDeclaration("i.none", default = -7L, min = null, max = null),
                IntSettingDeclaration("i.minonly", default = 0L, min = -100L, max = null),
                StrSettingDeclaration("s.empty", default = ""),
                BoolSettingDeclaration("b.false", default = false),
                EnumSettingDeclaration("e.one", default = "only", candidates = listOf("only")),
            ),
        )
        assertEquals(d.settings, ManifestCodec.decode(ManifestCodec.encode(d)).settings)
    }

    @Test
    fun `五版输出确定性`() {
        val d = richDescriptor()
        assertContentEquals(ManifestCodec.encode(d), ManifestCodec.encode(d))
        val once = ManifestCodec.encode(d)
        assertEquals(
            String(once, StandardCharsets.UTF_8),
            String(ManifestCodec.encode(ManifestCodec.decode(once)), StandardCharsets.UTF_8),
            "encode→decode→encode 必须逐字节相同（坏档/漂移早发现）",
        )
    }

    // ---------------- 判据 3：解码器同时接受 /3 /4 /5 ----------------

    @Test
    fun `解码器仍然接受三版与四版`() {
        val v3 = ManifestCodec.decode(ManifestCodec.encode(ModuleDescriptor(packageId = "mod.v3")))
        assertEquals("mod.v3", v3.packageId)
        assertEquals(emptyList(), v3.settings)

        val v4 = ManifestCodec.decode(
            ManifestCodec.encode(
                ModuleDescriptor(packageId = "mod.v4", displayName = "四版", author = "作者"),
            ),
        )
        assertEquals("mod.v4", v4.packageId)
        assertEquals("四版", v4.displayName)
        assertEquals("作者", v4.author)
        assertEquals(emptyList(), v4.settings)
    }

    // ---------------- 判据 4：/5 严格性（逐条负控） ----------------

    private fun settingsHeadIndex(lines: List<String>): Int {
        val at = lines.indexOf("@settings")
        assertTrue(at >= 0, "夹具前置：必须有 settings 段")
        return at
    }

    @Test
    fun `五版缺settings段整档拒载`() {
        val lines = lines(richDescriptor())
        val at = settingsHeadIndex(lines)
        // 只摘掉 @settings 段头：解码器在第 19 段位置读到上一段的段尾 /，按「期望段 @settings，
        // 实际 /」拒载（而不是把设置项静默当空——那会让声明了设置的 /5 包被读成无设置项）。
        val stripped = lines.filterIndexed { i, _ -> i != at }
        val ex = assertFailsWith<ManifestFormatException> {
            ManifestCodec.decode(bytesOf(stripped))
        }
        assertTrue("settings" in ex.message.orEmpty(), "错误消息必须点名缺失段：${ex.message}")
        // 反证夹具本身没被改坏：同一份字节带着 settings 段可解。
        ManifestCodec.decode(bytesOf(lines))
    }

    @Test
    fun `五版设置条目计数不符拒载`() {
        val lines = lines(richDescriptor())
        val at = settingsHeadIndex(lines)
        assertEquals("I4", lines[at + 1], "夹具前置：四条声明")
        lines[at + 1] = "I3"
        val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(bytesOf(lines)) }
        assertTrue(ex.message.orEmpty().isNotBlank(), "计数不符必须给可定位错误")
    }

    @Test
    fun `五版未知设置类型拒载`() {
        val lines = lines(richDescriptor())
        val at = settingsHeadIndex(lines)
        val typeLine = at + 2
        assertEquals("E494e54", lines[typeLine], "夹具前置：首条为 INT")
        lines[typeLine] = "E464c4f4154" // FLOAT：未注册的类型名
        val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(bytesOf(lines)) }
        assertTrue("SettingType" in ex.message.orEmpty(), "错误必须点名枚举：${ex.message}")
    }

    @Test
    fun `五版int默认值越界拒载`() {
        val lines = lines(richDescriptor())
        val at = settingsHeadIndex(lines)
        // 首条 INT 的专属体 = 默认值 / min / max 三行（紧跟键、标题、说明之后）。
        val defaultAt = at + 6
        assertEquals("I5", lines[defaultAt], "夹具前置：默认值 5")
        lines[defaultAt] = "I99"
        val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(bytesOf(lines)) }
        assertTrue(
            "IntSettingDeclaration" in ex.message.orEmpty(),
            "越界默认值必须在构造期拒载：${ex.message}",
        )
    }

    @Test
    fun `五版enum默认值不在候选里拒载`() {
        val lines = lines(richDescriptor())
        val at = settingsHeadIndex(lines)
        // 最后一条 ENUM 的默认值 = "b" 的 hex 行；在 settings 段内找唯一一次。
        val defaultAt = (at until lines.size).first { lines[it] == "S62" }
        lines[defaultAt] = "S7a" // "z" 不在候选 a/b/c 里
        val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(bytesOf(lines)) }
        assertTrue(
            "EnumSettingDeclaration" in ex.message.orEmpty(),
            "候选外默认值必须在构造期拒载：${ex.message}",
        )
    }

    @Test
    fun `五版尾部多余字节拒载`() {
        val lines = lines(richDescriptor())
        lines.add("I1")
        val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(bytesOf(lines)) }
        assertTrue("多余" in ex.message.orEmpty(), "尾部多余内容必须拒载：${ex.message}")
    }

    @Test
    fun `五版缺段尾拒载`() {
        val lines = lines(richDescriptor())
        val tailAt = lines.size - 2
        assertEquals("/", lines[tailAt])
        lines[tailAt] = "S00"
        assertFailsWith<ManifestFormatException> { ManifestCodec.decode(bytesOf(lines)) }
    }

    // ---------------- 判据 5：声明层校验（键唯一 / 类型安全） ----------------

    @Test
    fun `重复设置键在声明期就拒载`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            modDeclarations("mod.dup") {
                intSetting("cfg.same")
                boolSetting("cfg.same")
            }
        }
        assertTrue("唯一" in ex.message.orEmpty(), "重复键必须点名唯一性：${ex.message}")
    }

    @Test
    fun `int声明区间与默认值自洽校验`() {
        assertFailsWith<IllegalArgumentException> {
            IntSettingDeclaration("bad.range", default = 0L, min = 10L, max = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            IntSettingDeclaration("bad.default", default = 11L, min = 0L, max = 10L)
        }
        assertFailsWith<IllegalArgumentException> {
            EnumSettingDeclaration("bad.enum", default = "z", candidates = listOf("a", "b"))
        }
        assertFailsWith<IllegalArgumentException> {
            EnumSettingDeclaration("bad.empty", default = "a", candidates = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            StrSettingDeclaration("bad.newline", default = "a\nb")
        }
    }
}
