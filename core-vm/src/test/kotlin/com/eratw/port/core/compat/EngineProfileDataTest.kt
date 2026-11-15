package com.eratw.port.core.compat

import com.eratw.port.core.fn.Builtins
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * B1 数据生成阶段闸：只对拍注册名字与方言声明，不宣称编译门控或运行期行为已接线。
 * YAML 仅在测试侧读取；不依赖共享 reference、游戏语料或 YAML 库。
 */
class EngineProfileDataTest {
    private val engineIds = listOf("v24", "em_ee", "snake", "cs_reference")
    private val surfaces = listOf("functions", "commands")
    private val familyById = mapOf(
        "snake" to EngineFamily.SNAKE_4981,
        "em_ee" to EngineFamily.EM_EE_1824_V22,
        "v24" to EngineFamily.V24_1824,
    )
    private val canonicalName = Regex("[A-Z_][A-Z0-9_]*")
    private val specText by lazy { specFile().readText(Charsets.UTF_8) }
    private val spec by lazy { parseSpec(specText) }

    @Test
    fun `B1 数据生成 snake 函数全集逐名及计数对拍`() = assertSurface(
        "snake", "functions", EngineProfileData.SNAKE_FUNCTIONS,
        EngineProfileData.functionsOf(EngineFamily.SNAKE_4981),
    )

    @Test
    fun `B1 数据生成 snake 命令全集逐名及计数对拍`() = assertSurface(
        "snake", "commands", EngineProfileData.SNAKE_COMMANDS,
        EngineProfileData.commandsOf(EngineFamily.SNAKE_4981),
    )

    @Test
    fun `B1 数据生成 em_ee 函数全集逐名及计数对拍`() = assertSurface(
        "em_ee", "functions", EngineProfileData.EM_EE_FUNCTIONS,
        EngineProfileData.functionsOf(EngineFamily.EM_EE_1824_V22),
    )

    @Test
    fun `B1 数据生成 em_ee 命令全集逐名及计数对拍`() = assertSurface(
        "em_ee", "commands", EngineProfileData.EM_EE_COMMANDS,
        EngineProfileData.commandsOf(EngineFamily.EM_EE_1824_V22),
    )

    @Test
    fun `B1 数据生成 v24 函数全集逐名及计数对拍`() = assertSurface(
        "v24", "functions", EngineProfileData.V24_FUNCTIONS,
        EngineProfileData.functionsOf(EngineFamily.V24_1824),
    )

    @Test
    fun `B1 数据生成 v24 命令全集逐名及计数对拍`() = assertSurface(
        "v24", "commands", EngineProfileData.V24_COMMANDS,
        EngineProfileData.commandsOf(EngineFamily.V24_1824),
    )

    @Test
    fun `B1 数据结构 cs_reference 必须参与核账但不是可选引擎`() {
        assertEquals(familyById.values.toSet(), EngineFamily.values().toSet())
        assertEquals(engineIds.toSet(), spec.profiles.keys)
        for (surface in surfaces) {
            val reference = spec.surface("cs_reference", surface)
            assertTrue(reference.names.isNotEmpty(), "cs_reference.$surface 不得省略或置空")
            assertEquals(reference.count, reference.names.size, "meta.counts.cs_reference.$surface")
        }
    }

    @Test
    fun `B5 方言数据 三个已证据 facet 与 YAML 逐档一致`() {
        val expected = familyById.map { (id, family) -> family to spec.dialects.getValue(id) }.toMap()
        assertEquals(expected, EngineProfileData.DIALECT, "DIALECT 不得缺档、多档或串档")
    }

    @Test
    fun `B5 方言数据 v24 是 legacy 加 with 加 included 而 em 与 snake 各差一档`() {
        // spec dialect.string_compare_ge_le 的 C# 锚：
        // reference/Emuera1824+v24/src1824/Emuera/GameData/Expression/OperatorMethod.cs:487,518
        // reference/emuera-em/Emuera/Runtime/Script/Statements/Expression/OperatorMethod.cs:478,509
        // reference/emuera-skia-4.981/Emuera/Runtime/Script/Statements/Expression/OperatorMethod.cs:547,578
        // spec dialect.getnum_nickname_mastername 的 C# 锚：
        // reference/Emuera1824+v24/src1824/Emuera/GameData/ConstantData.cs:876-877
        // reference/emuera-em/Emuera/Runtime/Script/Data/ConstantData.cs:1075-1076
        // reference/emuera-skia-4.981/Emuera/Runtime/Script/Data/ConstantData.cs:1454-1455
        // spec dialect.relation_mastername 的 C# 锚（relationDic 建表循环）：
        // reference/Emuera1824+v24/src1824/Emuera/GameData/ConstantData.cs:640-641
        // reference/emuera-em/Emuera/Runtime/Script/Data/ConstantData.cs:711-712
        // reference/emuera-skia-4.981/Emuera/Runtime/Script/Data/ConstantData.cs:854-863（无 Mastername）
        // EM 的 included/name-only 组合是本 facet 的存在理由：MASTERNAME 只在 RELATION 面可达。
        val fixedName = EngineDialect(
            StringCompareDialect.FIXED, NicknameKeywordPolicy.NAME_CALLNAME_ONLY,
            RelationMasternamePolicy.EXCLUDED,
        )
        val expected = mapOf(
            EngineFamily.V24_1824 to EngineDialect(
                StringCompareDialect.LEGACY_BOTH_LT, NicknameKeywordPolicy.WITH_NICKNAME_MASTERNAME,
                RelationMasternamePolicy.INCLUDED,
            ),
            EngineFamily.EM_EE_1824_V22 to fixedName.copy(
                relationMastername = RelationMasternamePolicy.INCLUDED,
            ),
            EngineFamily.SNAKE_4981 to fixedName,
        )
        assertEquals(expected, EngineProfileData.DIALECT, "独立证据钉：不能随生成器一起错改")
        assertEquals(expected, familyById.map { (id, family) -> family to spec.dialects.getValue(id) }.toMap())
    }

    @Test
    fun `ENGCPL targets 生成物恰三条且缺省档唯一 snake_4981`() {
        val yamlTargets = parseTargets(specText)
        assertEquals(3, EngineProfileData.TARGETS.size, "TARGETS 应恰三条：$yamlTargets")
        assertEquals(
            listOf("snake_4981", "em_ee_1824_v22", "v24_1824"),
            EngineProfileData.TARGETS.map { it.profileId },
            "档位顺序即报错并列顺序（CONTRACT-ENGCPL §2 G4），不得变",
        )
        assertEquals("snake_4981", EngineProfileData.DEFAULT_TARGET_PROFILE_ID)
        assertEquals(
            listOf("snake_4981"), yamlTargets.filter { it.isDefault }.map { it.profile },
            "yaml targets 里 default: true 必须恰一条",
        )
        assertEquals(EngineProfileData.DEFAULT_TARGET_PROFILE_ID, yamlTargets.single { it.isDefault }.profile)
    }

    @Test
    fun `ENGCPL targets 三串逐字等于 yaml 且 family 与语料可解析`() {
        val yamlTargets = parseTargets(specText)
        assertEquals(yamlTargets.map { it.profile }, EngineProfileData.TARGETS.map { it.profileId })
        for ((index, target) in EngineProfileData.TARGETS.withIndex()) {
            val row = yamlTargets[index]
            assertEquals(row.profile, target.profileId, "targets[$index].profileId")
            assertEquals(row.engine, target.engine, "targets.${row.profile}.engine")
            assertEquals(row.version, target.version, "targets.${row.profile}.stamp_version")
            assertEquals(row.family, target.family.name, "targets.${row.profile}.family")
            assertEquals(row.corpus, target.corpusVersionId, "targets.${row.profile}.corpus")
            assertEquals(
                row.fullStatementCoverage, target.fullStatementCoverage,
                "targets.${row.profile}.full_statement_coverage",
            )
            // family 可解析：枚举名能 valueOf 回同一个值（生成物不 import proc，故用名字核对）。
            assertEquals(target.family, EngineFamily.valueOf(target.family.name))
            // corpusVersionId 可解析：必须是 proc 侧 CorpusVersion 认识的名字（收口点）。
            assertEquals(
                target.corpusVersionId,
                com.eratw.port.core.proc.CorpusVersion.valueOf(target.corpusVersionId).name,
                "corpusVersionId 必须能被 CorpusVersion.valueOf 解析：${target.corpusVersionId}",
            )
        }
    }

    @Test
    fun `ENGCPL corpora 段含三档且缺省档不落在退役语料上`() {
        val corpora = parseCorpora(specText)
        // 2026-11-14 语料换代 261017→261111：V4_981 指向 261111，261017 转退役留档
        //（golden 基准采集根、回归对拍根仍指 261017），id 只区分内容日期、引擎语义同属 4.981。
        assertEquals(setOf("V4_981", "V4_981_261017", "V4_980"), corpora.keys)
        assertTrue(
            EngineProfileData.TARGETS.all { it.corpusVersionId in corpora },
            "每条 target 的 corpusVersionId 必须能在 corpora 段解析：$corpora",
        )
        val defaultCorpus = EngineProfileData.TARGETS
            .single { it.profileId == EngineProfileData.DEFAULT_TARGET_PROFILE_ID }.corpusVersionId
        assertFalse(corpora.getValue(defaultCorpus).retired, "缺省档不得落在退役语料上")
        assertTrue(corpora.getValue("V4_980").retired, "V4_980 已退役")
        assertTrue(corpora.getValue("V4_981_261017").retired, "V4_981_261017 已退役（语料换代留档）")
        assertEquals(null, corpora.getValue("V4_980").markerFile, "退役档没有认根标记")
        assertEquals(
            "ERB/DLC/AI对话补丁/对话调用.erb",
            corpora.getValue("V4_981").markerFile,
            "V4_981 的认根标记是 DLC 对话补丁文件",
        )
        // 生成物 CORPORA 与生成物 yaml 逐字段相等（防生成器漏字段/串值）。
        assertEquals(
            corpora.mapValues { (id, row) -> CorpusSpec(id, row.rootDir, row.markerFile, row.retired) },
            EngineProfileData.CORPORA,
            "CORPORA 必须逐字段等于 yaml corpora 段",
        )
    }

    @Test
    fun `ENGCPL 缺省档必须声明语句面全覆盖`() {
        // 短路依据是数据事实而非家族名：无印章/无 flag 的出包产物都走缺省档，
        // 它若不声明全覆盖，启动期就要多扫一趟 171.7 万条语句记录（I1 零干扰）。
        val declared = EngineProfileData.TARGETS.filter { it.fullStatementCoverage }.map { it.profileId }
        assertEquals(
            listOf(EngineProfileData.DEFAULT_TARGET_PROFILE_ID), declared,
            "声明 full_statement_coverage 的目标档必须恰是缺省档：$declared",
        )
    }

    @Test
    fun `ENGCPL 关系关键字名单与 yaml 逐名一致`() {
        val (routed, gated) = parseKeywords(specText)
        assertEquals(listOf("NAME", "CALLNAME", "RELATION"), EngineProfileData.RELATION_ROUTED_KEYWORDS)
        assertEquals(listOf("NICKNAME", "MASTERNAME"), EngineProfileData.RELATION_GATED_KEYWORDS)
        assertEquals(routed, EngineProfileData.RELATION_ROUTED_KEYWORDS)
        assertEquals(gated, EngineProfileData.RELATION_GATED_KEYWORDS)
        // 名单不许与方言开关自相矛盾：gated 尾名正是 getnum_nickname_mastername 的门控对象。
        assertEquals(EngineProfileData.RELATION_GATED_KEYWORDS.size, 2)
        assertTrue(
            EngineProfileData.RELATION_ROUTED_KEYWORDS.none { it in EngineProfileData.RELATION_GATED_KEYWORDS },
            "routed 与 gated 不得重叠：同一关键字不能既无条件路由又受门控",
        )
    }

    @Test
    fun `B1 注册名分类 扩展恰为端口实注册减四树函数且不撞任何命令`() {
        assertExtensions(Builtins.registry.names(), EngineProfileData.PORT_EXTENSIONS, spec)
    }

    @Test
    fun `B1 注册名分类 SETANIMETIMER 跨面及 BITMAP_CACHE_ENABLE 不是端口扩展`() {
        // EM 注册：reference/emuera-em/Emuera/Runtime/Script/Statements/Function/Creator.cs:211,347。
        // snake 命令：reference/emuera-skia-4.981/Emuera/Runtime/Script/Statements/FunctionIdentifier.cs:474。
        // snake 函数无这两名：该树 Function/Creator.cs 全注册表，spec profiles.snake.functions 对拍。
        for (name in listOf("SETANIMETIMER", "BITMAP_CACHE_ENABLE")) {
            assertTrue(name in spec.surface("em_ee", "functions").names, "EM 函数缺 $name")
            assertTrue(name in EngineProfileData.EM_EE_FUNCTIONS, "生成的 EM 函数缺 $name")
            assertFalse(name in spec.surface("snake", "functions").names, "snake 函数错收 $name")
            assertFalse(name in EngineProfileData.SNAKE_FUNCTIONS, "生成的 snake 函数错收 $name")
            assertTrue(name in Builtins.registry.names(), "端口注册表缺 $name")
            assertFalse(name in EngineProfileData.PORT_EXTENSIONS, "$name 不得仅因 snake 无函数就被称为扩展")
        }
        assertTrue("SETANIMETIMER" in spec.surface("snake", "commands").names)
        assertTrue("SETANIMETIMER" in EngineProfileData.SNAKE_COMMANDS)
        // SPRITEANIMEFRAME 的分类留给全量差集审计，不在这里预设结论。
    }

    @Test
    fun `B1 提取器负例 缺必需段包括 cs_reference 必须明确失败`() {
        val paths = listOf(listOf("meta", "counts"), listOf("profiles"), listOf("dialect")) +
            engineIds.flatMap { id ->
                listOf(listOf("profiles", id), listOf("meta", "counts", id)) +
                    surfaces.map { listOf("profiles", id, it) }
            }
        for (path in paths) {
            assertMalformed(rewriteSpec(path) { emptyList() }, path.joinToString("."))
        }
    }

    @Test
    fun `B1 提取器负例 八个名字段空段不得退化为空集合`() {
        for (id in engineIds) for (surface in surfaces) {
            val path = listOf("profiles", id, surface)
            assertMalformed(rewriteSpec(path) { it.take(1) }, path.joinToString("."))
        }
    }

    @Test
    fun `B1 提取器负例 重复名字不得被 toSet 吞掉`() {
        for (id in engineIds) for (surface in surfaces) {
            val path = listOf("profiles", id, surface)
            assertMalformed(rewriteSpec(path) { it + it[1] }, "${path.joinToString(".")} 重复名字")
        }
    }

    @Test
    fun `B1 提取器负例 名字与列表格式必须规范而非宽松过滤`() {
        val malformedRows = listOf(
            "      - abs", "      - NAME WITH SPACE", "      - NAME-DASH", "      - 123NAME",
            "      - 'ABS'", "      - 非ASCII", "      - ", "     - ABS", "      - ABS # comment",
        )
        for (id in engineIds) for (surface in surfaces) {
            val path = listOf("profiles", id, surface)
            for (row in malformedRows) {
                assertMalformed(rewriteSpec(path) { it.take(1) + row + it.drop(2) }, path.joinToString("."))
            }
            assertMalformed(rewriteSpec(path) { listOf("    $surface: []") }, path.joinToString("."))
        }
    }

    @Test
    fun `B1 提取器负例 counts 必填正整数且与全部八个名字段相等`() {
        for (id in engineIds) for (surface in surfaces) {
            val path = listOf("meta", "counts", id, surface)
            assertMalformed(rewriteSpec(path) { emptyList() }, "meta.counts.$id")
            val badCounts = listOf("0", "-1", "two", "'2'", "2147483648", (spec.surface(id, surface).count + 1).toString())
            for (count in badCounts) {
                assertMalformed(rewriteSpec(path) { listOf("      $surface: $count") }, path.joinToString("."))
            }
        }
    }

    @Test
    fun `B1 提取器负例 重复段与重复标量不得采用先到或后到覆盖`() {
        val paths = listOf(
            listOf("schema"), listOf("profiles"), listOf("profiles", "cs_reference"),
            listOf("meta", "counts", "v24", "functions"),
            listOf("dialect", "string_compare_ge_le", "values", "v24"),
            listOf("dialect", "relation_mastername", "values", "em_ee"),
        ) + engineIds.flatMap { id -> surfaces.map { listOf("profiles", id, it) } }
        for (path in paths) {
            assertMalformed(rewriteSpec(path) { it + it }, path.joinToString("."))
        }
    }

    @Test
    fun `B5 提取器负例 三 facet 缺失空值或未知值包括参考树均失败`() {
        for (facet in listOf("string_compare_ge_le", "getnum_nickname_mastername", "relation_mastername")) {
            val facetPath = listOf("dialect", facet)
            assertMalformed(rewriteSpec(facetPath) { emptyList() }, facetPath.joinToString("."))
            val valuesPath = facetPath + "values"
            assertMalformed(rewriteSpec(valuesPath) { it.take(1) }, valuesPath.joinToString("."))
            for (id in engineIds) {
                val path = valuesPath + id
                assertMalformed(rewriteSpec(path) { emptyList() }, valuesPath.joinToString("."))
                for (value in listOf("", "unknown", "'true'")) {
                    assertMalformed(rewriteSpec(path) { listOf("      $id: $value") }, path.joinToString("."))
                }
            }
        }
    }

    @Test
    fun `B1 对拍器反向证明 删名加名与同数量换名全部失败`() {
        for (id in engineIds) for (surface in surfaces) {
            val names = spec.surface(id, surface).names
            val alien = "B1_TEST_UNREGISTERED_NAME"
            assertFalse(alien in names, "反例名字必须不在基准集合")
            assertNames("正向 $id.$surface", names, names.toList().reversed().toSet())
            for (changed in listOf(names - names.first(), names + alien, names - names.first() + alien)) {
                assertFailsWith<AssertionError> { assertNames("反向 $id.$surface", names, changed) }
            }
        }
    }

    @Test
    fun `B1 扩展闸反向证明 漏报误报重名未注册及跨面名不能放行`() {
        val functions = spec.allNames("functions")
        val commands = spec.allNames("commands")
        val extension = "B1_TEST_PORT_EXTENSION"
        val second = "B1_TEST_OTHER_EXTENSION"
        assertTrue(setOf(extension, second).intersect(functions + commands).isEmpty(), "反例须与四树不重名")
        val registered = functions + extension
        assertExtensions(registered, setOf(extension), spec)
        assertFailsWith<AssertionError> { assertExtensions(registered, emptySet(), spec) }
        assertFailsWith<AssertionError> { assertExtensions(registered + second, setOf(extension), spec) }
        assertFailsWith<AssertionError> { assertExtensions(registered, setOf(extension, second), spec) }
        assertFailsWith<AssertionError> { assertExtensions(registered, setOf(extension, functions.first()), spec) }
        assertFailsWith<AssertionError> { assertExtensions(registered + "lowercase", setOf(extension, "lowercase"), spec) }
        val commandOnly = (commands - functions).firstOrNull() ?: fail("反向证明需要一个四树纯命令名")
        // 不把跨面差异从等式右侧扣掉，也不把它包装为扩展；逐引擎、逐名报红等审计裁定。
        val crossFace = assertFailsWith<AssertionError> {
            assertExtensions(registered + commandOnly, setOf(extension), spec)
        }
        assertTrue(crossFace.message.orEmpty().contains("跨面"))
        assertTrue(crossFace.message.orEmpty().contains(commandOnly))
        assertFailsWith<AssertionError> {
            assertExtensions(registered + commandOnly, setOf(extension, commandOnly), spec)
        }
    }

    private fun assertSurface(id: String, surface: String, generated: Set<String>, selected: Set<String>) {
        val expected = spec.surface(id, surface)
        assertNames("$id.$surface 生成常量", expected.names, generated)
        assertEquals(expected.count, generated.size, "meta.counts.$id.$surface")
        assertNames("$id.$surface family 分派", expected.names, selected)
        assertEquals(expected.count, selected.size, "meta.counts.$id.$surface family 分派")
    }

    private fun assertNames(label: String, expected: Set<String>, actual: Set<String>) {
        assertTrue(actual.isNotEmpty(), "$label 空集合")
        assertTrue(actual.all(canonicalName::matches), "$label 不规范名字：${actual.filterNot(canonicalName::matches)}")
        assertEquals(expected, actual, "$label 缺名=${(expected - actual).sorted()}；多名=${(actual - expected).sorted()}")
    }

    private fun assertExtensions(registered: Set<String>, extensions: Set<String>, data: Spec) {
        assertTrue(extensions.isNotEmpty(), "PORT_EXTENSIONS 必须非空；待审计不能用空表放行")
        assertTrue(extensions.all(canonicalName::matches), "PORT_EXTENSIONS 含不规范名字")
        assertTrue((extensions - registered).isEmpty(), "扩展未实际注册：${(extensions - registered).sorted()}")
        val clashes = data.profiles.flatMap { (id, entries) ->
            entries.mapNotNull { (surface, entry) ->
                (extensions intersect entry.names).takeIf { it.isNotEmpty() }?.let { "$id.$surface=${it.sorted()}" }
            }
        }
        assertTrue(clashes.isEmpty(), "扩展与四树函数或命令重名：${clashes.joinToString("；")}")
        val difference = registered - data.allNames("functions")
        val crossFace = engineIds.mapNotNull { id ->
            (difference intersect data.surface(id, "commands").names).takeIf { it.isNotEmpty() }
                ?.let { "$id.commands=${it.sorted()}" }
        }
        assertTrue(crossFace.isEmpty(), "端口差集存在跨面名，须单独审计，禁止吞掉：${crossFace.joinToString("；")}")
        assertNames("PORT_EXTENSIONS = 实注册减四树函数", difference, extensions)
    }

    private data class Surface(val names: Set<String>, val count: Int)
    private data class Spec(
        val profiles: Map<String, Map<String, Surface>>,
        val dialects: Map<String, EngineDialect>,
    ) {
        fun surface(id: String, kind: String): Surface = profiles.getValue(id).getValue(kind)
        fun allNames(kind: String): Set<String> = profiles.values.flatMap { it.getValue(kind).names }.toSet()
    }

    private fun specFile(): File {
        val working = File(System.getProperty("user.dir")).absoluteFile
        val candidates = listOf(
            "../specs/engine-profiles.yaml", "specs/engine-profiles.yaml",
            "eratw-android/specs/engine-profiles.yaml", "../../specs/engine-profiles.yaml",
        ).map { working.resolve(it).normalize() }
        return candidates.firstOrNull { it.isFile }
            ?: fail("缺少 specs/engine-profiles.yaml；尝试：${candidates.joinToString { it.absolutePath }}")
    }

    /**
     * 只提取本生成格式：两空格层级、裸大写名字序列、counts 正整数及三个 facet 的裸标量。
     * 不解释其余元数据，不支持通用 YAML（引号名字、流式列表、别名、多行标量等均不接受）。
     * 先验重再构造 Set；缺段、空段和格式漂移必须 fail，不能过滤后拿空集合继续比较。
     */
    private fun parseSpec(text: String): Spec {
        val root = yamlRoot(text)
        assertEquals("'eratw.engine-profiles/v1'", root.scalar("schema"), "schema 不支持")
        val profiles = root.section("profiles")
        val counts = root.section("meta").section("counts")
        val parsed = engineIds.associateWith { id ->
            val profile = profiles.section(id)
            val countMap = counts.section(id).scalars()
            assertEquals(surfaces.toSet(), countMap.keys, "meta.counts.$id 必須恰含 functions 和 commands")
            surfaces.associateWith { surface ->
                val path = "meta.counts.$id.$surface"
                val raw = countMap.getValue(surface)
                val count = raw.takeIf { Regex("[1-9][0-9]*").matches(it) }?.toIntOrNull()
                    ?: fail("$path 不是正 Int：$raw")
                val block = profile.section(surface)
                val names = linkedSetOf<String>()
                for (row in block.rows) {
                    val match = Regex(" {${block.indent}}- ([A-Z_][A-Z0-9_]*)").matchEntire(row.text)
                        ?: fail("${block.path} 不规范名字或列表格式，第 ${row.number} 行：${row.text}")
                    val name = match.groupValues[1]
                    assertTrue(names.add(name), "${block.path} 重复名字 $name，第 ${row.number} 行")
                }
                assertTrue(names.isNotEmpty(), "${block.path} 空名字段")
                assertEquals(count, names.size, "$path 与 profiles.$id.$surface 不符")
                Surface(names, count)
            }
        }
        val dialect = root.section("dialect")
        fun values(facet: String): Map<String, String> {
            val block = dialect.section(facet).section("values")
            return block.scalars().also {
                assertEquals(engineIds.toSet(), it.keys, "${block.path} 必须包含四树（cs_reference 不可省）")
            }
        }
        val compare = values("string_compare_ge_le")
        val nickname = values("getnum_nickname_mastername")
        val relation = values("relation_mastername")
        val dialects = engineIds.associateWith { id ->
            EngineDialect(
                when (val value = compare.getValue(id)) {
                    "bug" -> StringCompareDialect.LEGACY_BOTH_LT
                    "fixed" -> StringCompareDialect.FIXED
                    else -> fail("dialect.string_compare_ge_le.values.$id 未知值：$value")
                },
                when (val value = nickname.getValue(id)) {
                    "true" -> NicknameKeywordPolicy.WITH_NICKNAME_MASTERNAME
                    "false" -> NicknameKeywordPolicy.NAME_CALLNAME_ONLY
                    else -> fail("dialect.getnum_nickname_mastername.values.$id 未知值：$value")
                },
                when (val value = relation.getValue(id)) {
                    "true" -> RelationMasternamePolicy.INCLUDED
                    "false" -> RelationMasternamePolicy.EXCLUDED
                    else -> fail("dialect.relation_mastername.values.$id 未知值：$value")
                },
            )
        }
        return Spec(parsed, dialects)
    }

    private data class Row(val number: Int, val text: String) {
        val indent: Int get() = text.takeWhile { it == ' ' }.length
    }

    private data class YamlTarget(
        val profile: String,
        val engine: String,
        val version: String,
        val family: String,
        val corpus: String,
        val isDefault: Boolean,
        val fullStatementCoverage: Boolean,
    )

    private data class YamlCorpus(
        val key: String,
        val rootDir: String,
        val markerFile: String?,
        val retired: Boolean,
    )

    /** 生成格式里的单引号标量 → 裸串（本段的五个值都不含内嵌引号，故不做双写还原）。 */
    private fun unquote(raw: String): String =
        if (raw.length >= 2 && raw.first() == '\'' && raw.last() == '\'') {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }

    private fun field(fields: Map<String, String>, path: String, key: String): String =
        unquote(fields[key] ?: fail("$path.$key 缺字段"))

    /**
     * 只解析生成物的 `targets:` 段（块格式、两空格层级、字段名照 schema 定稿）。
     * 与 [parseSpec] 一样不支持通用 YAML；形状漂移必须 fail，不能过滤后拿空列表继续比较。
     */
    private fun parseTargets(text: String): List<YamlTarget> {
        val block = yamlRoot(text).section("targets")
        return block.rows.filter { it.indent == block.indent }.map { row ->
            val id = row.text.trim().removeSuffix(":")
            val fields = block.section(id).scalars()
            val path = "targets.$id"
            YamlTarget(
                profile = id,
                engine = field(fields, path, "engine"),
                version = field(fields, path, "stamp_version"),
                family = field(fields, path, "family"),
                corpus = field(fields, path, "corpus"),
                // default 可省（省 = false），显式写出才要求是 true/false。
                isDefault = when (val value = fields["default"]?.let { unquote(it) }) {
                    null, "false" -> false
                    "true" -> true
                    else -> fail("$path.default 不是 true/false：$value")
                },
                fullStatementCoverage =
                    when (val value = fields["full_statement_coverage"]?.let { unquote(it) }) {
                        null, "false" -> false
                        "true" -> true
                        else -> fail("$path.full_statement_coverage 不是 true/false：$value")
                    },
            )
        }
    }

    /** 只解析生成物的 `corpora:` 段；`marker_file: null` 还原成 null。 */
    private fun parseCorpora(text: String): Map<String, YamlCorpus> {
        val block = yamlRoot(text).section("corpora")
        return block.rows.filter { it.indent == block.indent }.associate { row ->
            val id = row.text.trim().removeSuffix(":")
            val fields = block.section(id).scalars()
            val path = "corpora.$id"
            val marker = field(fields, path, "marker_file").takeUnless { it == "null" }
            id to YamlCorpus(
                key = id,
                rootDir = field(fields, path, "root_dir"),
                markerFile = marker,
                retired = when (val value = field(fields, path, "retired")) {
                    "true" -> true
                    "false" -> false
                    else -> fail("$path.retired 不是 true/false：$value")
                },
            )
        }
    }

    /** 只解析 `dialect.getnum_nickname_mastername.keywords` 的两条名单。 */
    private fun parseKeywords(text: String): Pair<List<String>, List<String>> {
        val block = yamlRoot(text)
            .section("dialect")
            .section("getnum_nickname_mastername")
            .section("keywords")
        fun names(side: String): List<String> = block.section(side).rows.map { row ->
            val item = row.text.trim()
            if (!item.startsWith("- ")) fail("keywords.$side 不是列表项：$item")
            item.removePrefix("- ")
        }
        return names("routed") to names("gated")
    }

    private class YamlBlock(val path: String, val indent: Int, val rows: List<Row>) {
        fun entry(key: String): List<Row> {
            val prefix = " ".repeat(indent) + "$key:"
            val starts = rows.indices.filter { rows[it].indent == indent && rows[it].text.startsWith(prefix) }
            assertEquals(1, starts.size, "$path.$key 缺段或重复段（出现 ${starts.size} 次）")
            val start = starts.single()
            val end = (start + 1 until rows.size).firstOrNull { rows[it].indent <= indent } ?: rows.size
            return rows.subList(start, end)
        }

        fun section(key: String): YamlBlock {
            val entry = entry(key)
            assertEquals(" ".repeat(indent) + "$key:", entry.first().text, "$path.$key 只支持块格式")
            assertTrue(entry.size > 1, "$path.$key 空段")
            return YamlBlock("$path.$key", indent + 2, entry.drop(1))
        }

        fun scalar(key: String): String {
            val entry = entry(key)
            assertEquals(1, entry.size, "$path.$key 只支持单行标量")
            val prefix = " ".repeat(indent) + "$key: "
            val text = entry.single().text
            assertTrue(text.startsWith(prefix) && text.length > prefix.length, "$path.$key 缺标量或格式错误")
            return text.removePrefix(prefix)
        }

        fun scalars(): Map<String, String> {
            val result = linkedMapOf<String, String>()
            for (row in rows) {
                val match = Regex(" {${indent}}([a-z_][a-z0-9_]*): (.*)").matchEntire(row.text)
                    ?: fail("$path 不支持的标量格式，第 ${row.number} 行：${row.text}")
                val (key, value) = match.destructured
                assertTrue(value.isNotEmpty(), "$path.$key 空标量")
                assertFalse(key in result, "$path.$key 重复标量")
                result[key] = value
            }
            assertTrue(result.isNotEmpty(), "$path 空标量段")
            return result
        }
    }

    private fun yamlRoot(text: String): YamlBlock = YamlBlock(
        "spec", 0, text.lineSequence().mapIndexed { i, line -> Row(i + 1, line) }.filter { it.text.isNotBlank() }.toList(),
    )

    private fun assertMalformed(text: String, diagnostic: String) {
        val error = assertFailsWith<AssertionError> { parseSpec(text) }
        assertTrue(error.message.orEmpty().contains(diagnostic), "预期定位 $diagnostic，实际：${error.message}")
    }

    /** 只在内存中破坏真实 spec；不写 specs，不依赖固定行号、集合数量或排序。 */
    private fun rewriteSpec(path: List<String>, replace: (List<String>) -> List<String>): String {
        val parent = path.dropLast(1).fold(yamlRoot(specText)) { block, key -> block.section(key) }
        val entry = parent.entry(path.last())
        val original = specText.lines()
        return (original.take(entry.first().number - 1) + replace(entry.map { it.text }) +
            original.drop(entry.last().number)).joinToString("\n")
    }
}
