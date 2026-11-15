package com.eratw.port.core.patch

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.vars.DimSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M1-3 载体钉（CONTRACT-RT2-M1-3 §3.2）：
 * - 八域逐域往返无损（含枚举、可空值、空表、满表、重复键）；
 * - 确定性：两次 write 逐字节相同、write 到 read 到 write 逐字节相同；
 * - 坏档负控：缺段 / 重复键 / 乱序 / 非法枚举 / 截断 / 尾部多余字节逐条拒绝且消息可定位，
 *   绝不返回半成品描述符；
 * - 不做大小写归一猜测：关键字与枚举名按原文精确匹配。
 *
 * DimSpec 是普通 class（UserVariables.kt:43）不是 data class，ModuleDescriptor 的
 * 默认 equals 对 List 与 Map 里的 DimSpec 走引用比较 ⇒ 逐字段核对，见 [assertDescriptorEquals]。
 */
class ManifestCodecTest {

    private fun dim(
        name: String,
        isStr: Boolean = false,
        dims: IntArray = intArrayOf(7),
        isConst: Boolean = false,
        isRef: Boolean = false,
        isGlobal: Boolean = true,
        isSavedata: Boolean = false,
        isCharaData: Boolean = false,
        isStatic: Boolean = true,
        isPrivate: Boolean = false,
        defaultsInt: LongArray? = null,
        defaultsStr: List<String>? = null,
        defaultsFloat: Double? = null,
        isFloat: Boolean = false,
    ): DimSpec = DimSpec(
        name = name,
        isStr = isStr,
        dims = dims,
        isConst = isConst,
        isRef = isRef,
        isGlobal = isGlobal,
        isSavedata = isSavedata,
        isCharaData = isCharaData,
        isStatic = isStatic,
        isPrivate = isPrivate,
        defaultsInt = defaultsInt,
        defaultsStr = defaultsStr,
        defaultsFloat = defaultsFloat,
        isFloat = isFloat,
    )

    private fun base(): ModuleDescriptor = ModuleDescriptor(packageId = "mod.empty")

    /** 满表：八域全量 + 附加字段 + 枚举值 + 可空值 + 含特殊字符的字符串。 */
    private fun fullDescriptor(): ModuleDescriptor = ModuleDescriptor(
        packageId = "mod.full",
        version = "1.2.3",
        requires = listOf("mod.base", "mod.util"),
        loadAfter = listOf("mod.a", "mod.b"),
        externalCalls = listOf("FOO", "BAR_BAZ"),
        overrideFunctions = setOf("EVENTTURNEND", "COMMON_MAIN"),
        exportedMacros = mapOf("MAX_HP" to "999", "GREETING" to "hello world"),
        artifactSha256 = "a".repeat(64),
        globalDims = listOf(
            dim("G_STATE"),
            dim(
                "G_NAMES", isStr = true, dims = intArrayOf(3, 4), isSavedata = true,
                defaultsStr = listOf("x", "y", "含空格 与换行\n符"),
            ),
            dim("G_FLOAT", isFloat = true, dims = intArrayOf(5), defaultsFloat = 1.5, isStatic = false),
            dim("G_CONST", isConst = true, dims = intArrayOf(2), defaultsInt = longArrayOf(10L, -20L)),
        ),
        privateDims = mapOf(
            "@FUNC_A" to listOf(dim("TMP", isPrivate = true, dims = intArrayOf(2, 2)), dim("TMP2", isStr = true)),
            "@FUNC_B" to listOf(dim("LOCAL", isRef = true, dims = intArrayOf(0))),
        ),
        csvPatches = listOf(
            CsvKeywordPatch("CFLAG", -1, "MyFlag"),
            CsvKeywordPatch("TALENT", 7, "AliasA", isAlias = true),
            CsvKeywordPatch("CFLAG", 5, "MYFLAG"),
        ),
        csvValuePatches = listOf(
            CsvValuePatch("CFLAG", "NO:12", "初期值", CsvPatchOp.ADD, "1"),
            CsvValuePatch("CFLAG", "NO:12", "备注", CsvPatchOp.REPLACE, "含空格与|竖线"),
            CsvValuePatch("TALENT", "NO:1", "削除", CsvPatchOp.REMOVE, null),
        ),
        entityRefs = setOf("LAST_STUDENT_ID", "G_STATE"),
        componentSchemas = listOf(
            ComponentSchema(
                "mod.full", "Education",
                listOf(
                    ComponentFieldSpec("mentorId", ComponentFieldType.INT, isEntityRef = true),
                    ComponentFieldSpec("note", ComponentFieldType.STR),
                ),
            ),
            ComponentSchema("mod.full", "Health"),
        ),
        entityArchetypes = listOf(
            EntityArchetype("Student", listOf("Education", "Health")),
            EntityArchetype("Empty"),
        ),
        relationshipSchemas = listOf(
            RelationshipSchema("PARENT", autoInverse = true, inverseName = "CHILD"),
            RelationshipSchema("CHILD"),
            RelationshipSchema("PAIR", directed = false, dedupeEdges = false, cascadeOnDestroy = false),
        ),
        hookDeclarations = listOf(
            HookDeclaration("SYSTEM_TITLE", HookPhase.PREFIX, priority = 3, mayMutateArgs = true),
            HookDeclaration("EVENTTURNEND", HookPhase.POSTFIX, runOnFailure = true),
        ),
        interactionHookDeclarations = listOf(
            InteractionHookDeclaration(InteractionPhase.PRE, priority = 2),
            InteractionHookDeclaration(InteractionPhase.SETTLEMENT),
        ),
        phaseDeclarations = listOf(
            PhaseDeclaration("MORNING_PHASE", priority = 1),
            PhaseDeclaration("NIGHT_PHASE"),
        ),
        jobDeclarations = listOf(
            JobDeclaration("DAILY_JOB", "DAY_START", firstDay = 0L, everyDays = 1L, priority = 4),
            JobDeclaration("ONE_SHOT", "DAY_END", firstDay = 10L, everyDays = null),
        ),
        jobTemplateDeclarations = listOf(
            JobTemplateDeclaration("APPOINTMENT", "DAY_END", priority = 2),
            JobTemplateDeclaration("GIFT_REPLAY", "DAY_START", catchUp = JobCatchUp.REPLAY, maxCatchUp = 3L),
        ),
    )

    // -----------------------------------------------------------------------
    // 逐字段核对（DimSpec 非 data class，须逐项）
    // -----------------------------------------------------------------------
    private fun assertDimSpecEquals(e: DimSpec, a: DimSpec) {
        assertEquals(e.name, a.name)
        assertEquals(e.isStr, a.isStr)
        assertEquals(e.dims.toList(), a.dims.toList())
        assertEquals(e.isConst, a.isConst)
        assertEquals(e.isRef, a.isRef)
        assertEquals(e.isGlobal, a.isGlobal)
        assertEquals(e.isSavedata, a.isSavedata)
        assertEquals(e.isCharaData, a.isCharaData)
        assertEquals(e.isStatic, a.isStatic)
        assertEquals(e.isPrivate, a.isPrivate)
        assertEquals(e.defaultsInt?.toList(), a.defaultsInt?.toList())
        assertEquals(e.defaultsStr, a.defaultsStr)
        assertEquals(e.defaultsFloat?.toRawBits(), a.defaultsFloat?.toRawBits())
        assertEquals(e.isFloat, a.isFloat)
    }

    private fun assertDescriptorEquals(e: ModuleDescriptor, a: ModuleDescriptor) {
        assertEquals(e.packageId, a.packageId)
        assertEquals(e.version, a.version)
        assertEquals(e.requires, a.requires)
        assertEquals(e.loadAfter, a.loadAfter)
        assertEquals(e.externalCalls, a.externalCalls)
        assertEquals(e.overrideFunctions, a.overrideFunctions)
        assertEquals(e.exportedMacros, a.exportedMacros)
        assertEquals(e.artifactSha256, a.artifactSha256)

        assertEquals(e.globalDims.size, a.globalDims.size)
        for (i in e.globalDims.indices) assertDimSpecEquals(e.globalDims[i], a.globalDims[i])

        assertEquals(e.privateDims.keys, a.privateDims.keys)
        for (k in e.privateDims.keys) {
            val el = e.privateDims.getValue(k)
            val al = a.privateDims.getValue(k)
            assertEquals(el.size, al.size)
            for (i in el.indices) assertDimSpecEquals(el[i], al[i])
        }

        assertEquals(e.csvPatches, a.csvPatches)
        assertEquals(e.csvValuePatches, a.csvValuePatches)
        assertEquals(e.entityRefs, a.entityRefs)
        assertEquals(e.componentSchemas, a.componentSchemas)
        assertEquals(e.entityArchetypes, a.entityArchetypes)
        assertEquals(e.relationshipSchemas, a.relationshipSchemas)

        // /2 新增（/3 续）：§20/§21.1 五类声明逐字段往返（data class equals 逐项）
        assertEquals(e.hookDeclarations, a.hookDeclarations)
        assertEquals(e.interactionHookDeclarations, a.interactionHookDeclarations)
        assertEquals(e.phaseDeclarations, a.phaseDeclarations)
        assertEquals(e.jobDeclarations, a.jobDeclarations)
        assertEquals(e.jobTemplateDeclarations, a.jobTemplateDeclarations)
        // M5-4 刀 1（/3 续）：chara-field-routes 声明逐条往返（component-schemas 段 B1 子类型）。
        assertEquals(e.charaRoutes, a.charaRoutes)
    }

    // -----------------------------------------------------------------------
    // 篡改夹具：把合法字节流拆成行，改完再拼回
    // -----------------------------------------------------------------------
    private fun encodeLines(d: ModuleDescriptor): MutableList<String> =
        String(ManifestCodec.encode(d), Charsets.UTF_8).split("\n").dropLast(1).toMutableList()

    private fun toBytes(lines: List<String>): ByteArray =
        (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)

    private fun sectionStart(lines: List<String>, name: String): Int {
        val i = lines.indexOf("@$name")
        check(i >= 0) { "夹具里找不到段 @$name" }
        return i
    }

    private fun sectionEnd(lines: List<String>, start: Int): Int {
        for (i in start until lines.size) {
            if (lines[i] == "/") return i
        }
        error("夹具里找不到段尾 /")
    }

    private fun hex(s: String): String {
        val chars = "0123456789abcdef"
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xff
            sb.append(chars[v ushr 4]).append(chars[v and 0x0f])
        }
        return sb.toString()
    }

    // -----------------------------------------------------------------------
    // 判据 1：八域逐域往返
    // -----------------------------------------------------------------------
    @Test
    fun allEightDomainsRoundTrip() {
        val d = fullDescriptor()
        val back = ManifestCodec.decode(ManifestCodec.encode(d))
        assertDescriptorEquals(d, back)
    }

    @Test
    fun eachOfEightDomainsSurvivesRoundTrip() {
        val cases = listOf(
            base().copy(globalDims = listOf(dim("X"))),
            base().copy(privateDims = mapOf("F" to listOf(dim("P")))),
            base().copy(csvPatches = listOf(CsvKeywordPatch("CFLAG", -1, "K"))),
            base().copy(csvValuePatches = listOf(CsvValuePatch("CFLAG", "r", "c", CsvPatchOp.ADD, "v"))),
            base().copy(entityRefs = setOf("R")),
            base().copy(componentSchemas = listOf(ComponentSchema("mod.empty", "S"))),
            base().copy(entityArchetypes = listOf(EntityArchetype("A"))),
            base().copy(relationshipSchemas = listOf(RelationshipSchema("REL"))),
        )
        assertEquals(8, cases.size)
        for (d in cases) {
            assertDescriptorEquals(d, ManifestCodec.decode(ManifestCodec.encode(d)))
        }
    }

    @Test
    fun emptyDomainsRoundTrip() {
        val d = base()
        assertDescriptorEquals(d, ManifestCodec.decode(ManifestCodec.encode(d)))
    }

    @Test
    fun doubleRawBitsArePreservedBitExactly() {
        for (v in listOf(Double.NaN, -0.0, 0.0, 1.5, Double.MAX_VALUE, Double.MIN_VALUE)) {
            val d = base().copy(
                globalDims = listOf(
                    DimSpec(name = "F", isStr = false, dims = intArrayOf(1), defaultsFloat = v, isFloat = true),
                ),
            )
            val back = ManifestCodec.decode(ManifestCodec.encode(d))
            assertEquals(v.toRawBits(), back.globalDims.single().defaultsFloat!!.toRawBits())
        }
    }

    @Test
    fun noCaseFoldingAndExactKeywordPreservation() {
        val d = base().copy(
            csvPatches = listOf(
                CsvKeywordPatch("CFLAG", 1, "MyFlag"),
                CsvKeywordPatch("CFLAG", 2, "MYFLAG"),
                CsvKeywordPatch("CFLAG", 3, "myflag"),
            ),
        )
        val back = ManifestCodec.decode(ManifestCodec.encode(d))
        assertEquals(d.csvPatches, back.csvPatches)
        assertEquals(setOf("MyFlag", "MYFLAG", "myflag"), back.csvPatches.map { it.keyword }.toSet())
    }

    // -----------------------------------------------------------------------
    // 判据 6：确定性
    // -----------------------------------------------------------------------
    @Test
    fun determinismTwoWritesAreByteIdentical() {
        val d = fullDescriptor()
        val b1 = ManifestCodec.encode(d)
        val b2 = ManifestCodec.encode(d)
        assertTrue(b1.contentEquals(b2))
    }

    @Test
    fun writeReadWriteIsByteIdentical() {
        val d = fullDescriptor()
        val b1 = ManifestCodec.encode(d)
        val back = ManifestCodec.decode(b1)
        val b2 = ManifestCodec.encode(back)
        assertTrue(b1.contentEquals(b2))
    }

    // -----------------------------------------------------------------------
    // 判据 5：坏档负控（逐条拒绝且消息可定位，不返回半成品）
    // -----------------------------------------------------------------------
    @Test
    fun missingSectionIsRejected() {
        val l = encodeLines(fullDescriptor())
        val s = sectionStart(l, "global-dims")
        val e = sectionEnd(l, s)
        l.subList(s, e + 1).clear()
        val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
        assertTrue(ex.message!!.contains("global-dims") || ex.message!!.contains("private-dims"))
    }

    @Test
    fun duplicateSectionIsRejected() {
        val l = encodeLines(fullDescriptor())
        val s = sectionStart(l, "meta")
        val e = sectionEnd(l, s)
        val block = l.subList(s, e + 1).toList()
        l.addAll(e + 1, block)
        val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
        assertTrue(ex.message!!.contains("重复"))
    }

    @Test
    fun reorderedSectionsAreRejected() {
        val l = encodeLines(fullDescriptor())
        val a = sectionStart(l, "requires")
        val b = sectionStart(l, "load-after")
        val t = l[a]
        l[a] = l[b]
        l[b] = t
        val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
        assertTrue(ex.message!!.contains("requires") || ex.message!!.contains("load-after"))
    }

    @Test
    fun illegalEnumValueIsRejected() {
        val l = encodeLines(fullDescriptor())
        val idx = l.indexOfFirst { it.startsWith("E") }
        assertTrue(idx >= 0)
        l[idx] = "E" + hex("NOPE")
        assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }

        // 小写 add 不能当 ADD 用（不做大小写归一猜测）。
        val l2 = encodeLines(fullDescriptor())
        l2[idx] = "E" + hex("add")
        val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l2)) }
        assertTrue(ex.message!!.contains("非法枚举") || ex.message!!.contains("add"))
    }

    @Test
    fun truncatedManifestIsRejected() {
        val full = ManifestCodec.encode(fullDescriptor())
        assertFailsWith<ManifestFormatException> { ManifestCodec.decode(full.copyOf(full.size - 3)) }
        assertFailsWith<ManifestFormatException> { ManifestCodec.decode(ByteArray(0)) }
    }

    @Test
    fun trailingBytesAreRejected() {
        val full = ManifestCodec.encode(fullDescriptor())
        val extra = full + "GARBAGE\n".toByteArray(Charsets.UTF_8)
        val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(extra) }
        assertTrue(ex.message!!.contains("多余"))
        // 尾部补字节但没有 LF 收口，同样拒绝。
        val noNewline = full + "XYZ".toByteArray(Charsets.UTF_8)
        assertFailsWith<ManifestFormatException> { ManifestCodec.decode(noNewline) }
    }

    @Test
    fun unknownSegmentIsRejected() {
        val l = encodeLines(fullDescriptor())
        l.add(1, "@evil")
        l.add(2, "/")
        val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
        assertTrue(ex.message!!.contains("@evil") || ex.message!!.contains("meta"))
    }

    /** 反向证明 ③ 的对应用例：注入未知 tag 行，必须拒绝，不许「跳过未知键继续」。 */
    @Test
    fun unknownTokenTagIsRejected() {
        val l = encodeLines(fullDescriptor())
        val idx = l.indexOfFirst { it.startsWith("S") }
        l.add(idx, "Qunknown")
        val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
        assertTrue(ex.message!!.contains("S"))
    }

    @Test
    fun duplicateAndUnsortedSetKeysAreRejected() {
        val l = encodeLines(fullDescriptor())
        val s = sectionStart(l, "override-functions")
        l[s + 3] = l[s + 2]
        assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }

        val l2 = encodeLines(fullDescriptor())
        val s2 = sectionStart(l2, "override-functions")
        l2[s2 + 2] = "S" + hex("ZZZ")
        l2[s2 + 3] = "S" + hex("AAA")
        assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l2)) }
    }

    @Test
    fun duplicateMapKeysAreRejected() {
        val l = encodeLines(fullDescriptor())
        val s = sectionStart(l, "exported-macros")
        l[s + 4] = l[s + 2]
        assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
    }

    @Test
    fun malformedUtf8AndCrLineEndingAreRejected() {
        val arr = ManifestCodec.encode(fullDescriptor())
        arr[arr.size / 2] = 0xFF.toByte()
        assertFailsWith<ManifestFormatException> { ManifestCodec.decode(arr) }

        val text = String(ManifestCodec.encode(fullDescriptor()), Charsets.UTF_8)
        val crlf = text.replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
        assertFailsWith<ManifestFormatException> { ManifestCodec.decode(crlf) }
    }

    // -----------------------------------------------------------------------
    // /2 新增夹具：18 段清单、四类声明描述符、旧 /1 档构造器
    // -----------------------------------------------------------------------
    private val ALL_SECTIONS = listOf(
        "meta", "requires", "load-after", "external-calls", "override-functions",
        "global-dims", "private-dims", "csv-patches", "csv-value-patches", "entity-refs",
        "component-schemas", "entity-archetypes", "relationship-schemas",
        "hooks", "interaction-hooks", "phase-jobs",
        "exported-macros", "artifact-sha256",
    )

    /** 只带四类声明的精简描述符（八域为空，便于 ManifestLoader.load 无冲突贯通）。 */
    private fun declarationDescriptor(): ModuleDescriptor = ModuleDescriptor(
        packageId = "mod.decl",
        hookDeclarations = listOf(
            HookDeclaration("ON_TURN", HookPhase.PREFIX, priority = 1, mayMutateArgs = true),
            HookDeclaration("ON_TURN", HookPhase.POSTFIX, priority = 2, runOnFailure = true),
        ),
        interactionHookDeclarations = listOf(
            InteractionHookDeclaration(InteractionPhase.PRECONDITION),
            InteractionHookDeclaration(InteractionPhase.SETTLEMENT, priority = 5),
        ),
        phaseDeclarations = listOf(
            PhaseDeclaration("DAWN", priority = 0),
            PhaseDeclaration("DUSK"),
        ),
        jobDeclarations = listOf(
            JobDeclaration("J1", "DAWN", firstDay = 0L, everyDays = 3L, priority = 1),
            JobDeclaration("J2", "DAY_END", firstDay = 7L, everyDays = null, priority = 2),
        ),
        jobTemplateDeclarations = listOf(
            JobTemplateDeclaration("T_MISS_ONCE", "DAWN"),
            JobTemplateDeclaration("T_REPLAY", "DAY_END", catchUp = JobCatchUp.REPLAY, maxCatchUp = 2L),
        ),
    )

    /** 由 /3 档裁出一份合法的旧 /1 15 段档（换 magic、删去三个新增段）。 */
    private fun legacyV1Bytes(d: ModuleDescriptor = base()): ByteArray {
        val l = encodeLines(d)
        l[0] = "RT2MANIFEST/1"
        for (name in listOf("hooks", "interaction-hooks", "phase-jobs")) {
            val s = sectionStart(l, name)
            val e = sectionEnd(l, s)
            l.subList(s, e + 1).clear()
        }
        return toBytes(l)
    }

    // -----------------------------------------------------------------------
    // 判据 1/2：四类声明往返、确定性、声明序保真
    // -----------------------------------------------------------------------
    @Test
    fun fourDeclarationKindsRoundTripIsByteExact() {
        val d = declarationDescriptor()
        val b1 = ManifestCodec.encode(d)
        val b2 = ManifestCodec.encode(d)
        assertTrue(b1.contentEquals(b2))
        val back = ManifestCodec.decode(b1)
        assertDescriptorEquals(d, back)
        assertTrue(b1.contentEquals(ManifestCodec.encode(back)))
        assertDescriptorEquals(base(), ManifestCodec.decode(ManifestCodec.encode(base())))
    }

    @Test
    fun declarationOrderIsPreservedNotSorted() {
        val d = base().copy(
            hookDeclarations = listOf(
                HookDeclaration("ZZZ", HookPhase.PREFIX, priority = 0),
                HookDeclaration("AAA", HookPhase.POSTFIX, priority = 0),
            ),
            phaseDeclarations = listOf(
                PhaseDeclaration("Z_PHASE"),
                PhaseDeclaration("A_PHASE"),
            ),
        )
        val back = ManifestCodec.decode(ManifestCodec.encode(d))
        assertEquals(d.hookDeclarations, back.hookDeclarations)
        assertEquals(d.phaseDeclarations, back.phaseDeclarations)
    }

    @Test
    fun allEnumValuesNullableAndUnicodeRoundTrip() {
        val d = base().copy(
            hookDeclarations = enumValues<HookPhase>().mapIndexed { i, p ->
                HookDeclaration("目标键_$i 😀", p, priority = i, mayMutateReturn = true)
            },
            interactionHookDeclarations = enumValues<InteractionPhase>().mapIndexed { i, p ->
                InteractionHookDeclaration(p, priority = i)
            },
            phaseDeclarations = listOf(PhaseDeclaration("相位_中文 🌙", priority = 3)),
            jobDeclarations = listOf(
                JobDeclaration("任务🚀", "相位_中文 🌙", firstDay = 0L, everyDays = null, priority = 0),
                JobDeclaration("周期任务", "DAY_END", firstDay = 9L, everyDays = 2L, priority = 4),
            ),
        )
        val back = ManifestCodec.decode(ManifestCodec.encode(d))
        assertDescriptorEquals(d, back)
        assertEquals(enumValues<HookPhase>().toList(), back.hookDeclarations.map { it.phase })
        assertEquals(enumValues<InteractionPhase>().toList(), back.interactionHookDeclarations.map { it.phase })
        assertEquals(null, back.jobDeclarations[0].everyDays)
        assertEquals(2L, back.jobDeclarations[1].everyDays)
    }

    @Test
    fun phaseJobsMixedDiscriminantRoundTrip() {
        val d = base().copy(
            phaseDeclarations = listOf(PhaseDeclaration("PHASE_A", priority = 1), PhaseDeclaration("PHASE_B")),
            jobDeclarations = listOf(
                JobDeclaration("JOB_A", "DAY_START", firstDay = 0L, everyDays = 5L),
                JobDeclaration("JOB_B", "DAY_END", firstDay = 3L, everyDays = null),
            ),
            jobTemplateDeclarations = listOf(
                JobTemplateDeclaration("TPL_A", "DAY_START"),
                JobTemplateDeclaration("TPL_B", "PHASE_A", catchUp = JobCatchUp.MISS_SKIP),
            ),
        )
        val l = encodeLines(d)
        val s = sectionStart(l, "phase-jobs")
        val body = l.subList(s + 1, sectionEnd(l, s))
        assertTrue(body.any { it == "B0" } && body.any { it == "B1" } && body.any { it == "B2" })
        val back = ManifestCodec.decode(toBytes(l))
        assertDescriptorEquals(d, back)
        assertEquals(d.phaseDeclarations, back.phaseDeclarations)
        assertEquals(d.jobDeclarations, back.jobDeclarations)
        assertEquals(d.jobTemplateDeclarations, back.jobTemplateDeclarations)
    }

    // -----------------------------------------------------------------------
    // 判据 4：旧 /1 明示拒载 + 版本只产 /2
    // -----------------------------------------------------------------------
    @Test
    fun legacyVersionOneIsRejectedWithMigrationHint() {
        val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(legacyV1Bytes()) }
        val m = ex.message!!
        assertTrue(m.contains("RT2MANIFEST/1 已拒载"))
        assertTrue(m.contains("请用 /3 编码器重出 Manifest"))
        assertTrue(m.contains("phase-jobs") && m.contains("B2"))
        assertTrue(m.contains("RT2MANIFEST/3"))

        // /2 档同样明示拒载（不双版本读取）。
        val v2 = encodeLines(base())
        v2[0] = "RT2MANIFEST/2"
        val ex2 = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(v2)) }
        assertTrue(ex2.message!!.contains("RT2MANIFEST/2 已拒载"))
        assertTrue(ex2.message!!.contains("请用 /3 编码器重出 Manifest"))

        val unknown = encodeLines(base())
        unknown[0] = "RT2MANIFEST/9"
        assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(unknown)) }
    }

    @Test
    fun encodeEmitsOnlyVersionThree() {
        val l = encodeLines(fullDescriptor())
        assertEquals("RT2MANIFEST/3", l[0])
        assertEquals(ALL_SECTIONS.size, l.count { it.startsWith("@") })
        assertEquals(ALL_SECTIONS, l.filter { it.startsWith("@") }.map { it.substring(1) })
    }

    // -----------------------------------------------------------------------
    // 判据 3：18 段必选 / 乱序 / 重复 / 未知段 / 空声明显式段
    // -----------------------------------------------------------------------
    @Test
    fun everyOneOfEighteenSectionsIsRequired() {
        assertEquals(18, ALL_SECTIONS.size)
        for (name in ALL_SECTIONS) {
            val l = encodeLines(fullDescriptor())
            val s = sectionStart(l, name)
            val e = sectionEnd(l, s)
            l.subList(s, e + 1).clear()
            assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
        }
    }

    @Test
    fun newDeclarationSectionsMissingAreRejected() {
        for (name in listOf("hooks", "interaction-hooks", "phase-jobs")) {
            val l = encodeLines(fullDescriptor())
            val s = sectionStart(l, name)
            val e = sectionEnd(l, s)
            l.subList(s, e + 1).clear()
            val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
            assertTrue(ex.message!!.contains(name) || ex.message!!.contains("期望段"))
        }
    }

    @Test
    fun reorderedAndDuplicateAndUnknownSectionsAreRejected() {
        val l = encodeLines(fullDescriptor())
        val a = sectionStart(l, "hooks")
        val b = sectionStart(l, "interaction-hooks")
        val t = l[a]
        l[a] = l[b]
        l[b] = t
        assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }

        val l2 = encodeLines(fullDescriptor())
        val s = sectionStart(l2, "phase-jobs")
        val e = sectionEnd(l2, s)
        val block = l2.subList(s, e + 1).toList()
        l2.addAll(e + 1, block)
        val ex2 = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l2)) }
        assertTrue(ex2.message!!.contains("重复"))

        val l3 = encodeLines(fullDescriptor())
        l3.add(sectionStart(l3, "hooks"), "@evil-new")
        assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l3)) }
    }

    @Test
    fun emptyDeclarationSectionsAreExplicitlyWritten() {
        val l = encodeLines(base())
        for (name in listOf("hooks", "interaction-hooks", "phase-jobs")) {
            val s = sectionStart(l, name)
            assertEquals("I0", l[s + 1])
            assertEquals("/", l[s + 2])
        }
        assertDescriptorEquals(base(), ManifestCodec.decode(toBytes(l)))
    }

    // -----------------------------------------------------------------------
    // 判据 5：构造期不变量必须经 guarded 归一成格式异常
    // -----------------------------------------------------------------------
    @Test
    fun constructionInvariantViolationsAreRejectedAsFormatException() {
        run {
            val l = encodeLines(declarationDescriptor())
            val s = sectionStart(l, "hooks")
            l[s + 4] = "I-1"
            assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
        }
        run {
            val l = encodeLines(declarationDescriptor())
            val s = sectionStart(l, "hooks")
            l[s + 2] = "S"
            assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
        }
        run {
            val l = encodeLines(declarationDescriptor())
            val s = sectionStart(l, "phase-jobs")
            val phaseTag = (s + 1 until l.size).first { l[it] == "B0" }
            l[phaseTag + 1] = "S"
            assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
        }
        run {
            val l = encodeLines(declarationDescriptor())
            val s = sectionStart(l, "phase-jobs")
            val jobTag = (s + 1 until l.size).first { l[it] == "B1" }
            l[jobTag + 3] = "I-1"
            assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
        }
        run {
            val l = encodeLines(declarationDescriptor())
            val s = sectionStart(l, "phase-jobs")
            val jobTag = (s + 1 until l.size).first { l[it] == "B1" }
            l[jobTag + 4] = "I0"
            assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
        }
    }

    @Test
    fun malformedPhaseJobsDiscriminantAndTagAreRejected() {
        run {
            val l = encodeLines(declarationDescriptor())
            val s = sectionStart(l, "phase-jobs")
            val tag = (s + 1 until l.size).first { l[it] == "B0" || l[it] == "B1" }
            l[tag] = "B9"
            val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
            assertTrue(ex.message!!.contains("B0") && ex.message!!.contains("B1") && ex.message!!.contains("B2"))
        }
        run {
            val l = encodeLines(declarationDescriptor())
            val s = sectionStart(l, "phase-jobs")
            val jobTag = (s + 1 until l.size).first { l[it] == "B1" }
            l[jobTag + 4] = "S" + hex("x")
            assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
        }
        run {
            val full = ManifestCodec.encode(declarationDescriptor())
            assertFailsWith<ManifestFormatException> { ManifestCodec.decode(full.copyOf(full.size - 1)) }
            assertFailsWith<ManifestFormatException> {
                ManifestCodec.decode(full + "TAIL\n".toByteArray(Charsets.UTF_8))
            }
        }
    }

    @Test
    fun illegalEnumsHexUtf8CrAndCountInNewSectionsAreRejected() {
        run {
            val l = encodeLines(declarationDescriptor())
            val s = sectionStart(l, "hooks")
            l[s + 3] = "E" + hex("prefix")
            val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
            assertTrue(ex.message!!.contains("非法枚举") || ex.message!!.contains("prefix"))
        }
        run {
            val l = encodeLines(declarationDescriptor())
            val s = sectionStart(l, "interaction-hooks")
            l[s + 2] = "E" + hex("NOPE")
            assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
        }
        run {
            val l = encodeLines(declarationDescriptor())
            val s = sectionStart(l, "hooks")
            l[s + 3] = "EZZ"
            assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
        }
        run {
            val arr = ManifestCodec.encode(declarationDescriptor())
            arr[arr.size / 2] = 0xFF.toByte()
            assertFailsWith<ManifestFormatException> { ManifestCodec.decode(arr) }
        }
        run {
            val text = String(ManifestCodec.encode(declarationDescriptor()), Charsets.UTF_8)
            assertFailsWith<ManifestFormatException> {
                ManifestCodec.decode(text.replace("\n", "\r\n").toByteArray(Charsets.UTF_8))
            }
        }
        run {
            val l = encodeLines(declarationDescriptor())
            val s = sectionStart(l, "hooks")
            l[s + 1] = "I" + Long.MAX_VALUE.toString()
            assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(l)) }
        }
    }

    // -----------------------------------------------------------------------
    // 判据 7：ManifestLoader.load 不改一行即可吃到四类声明
    // -----------------------------------------------------------------------
    @Test
    fun manifestLoaderSeesFourDeclarationKinds() {
        val d = declarationDescriptor()
        val snapshot = ManifestLoader.load(listOf(ManifestSource("m", ManifestCodec.encode(d))))
        assertEquals(d.hookDeclarations, snapshot.hookDeclarations.map { it.declaration })
        assertEquals(d.interactionHookDeclarations, snapshot.interactionHookDeclarations.map { it.declaration })
        assertEquals(d.phaseDeclarations, snapshot.phaseDeclarations.map { it.declaration })
        assertEquals(d.jobDeclarations, snapshot.jobDeclarations.map { it.declaration })
    }
}
