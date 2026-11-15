package com.eratw.port.core.patch

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.vars.DimSpec
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Manifest 载体格式错误。任一坏档一律抛本异常（消息带行号与定位信息），
 * 解码器绝不返回半成品描述符，装载侧据此整包拒绝。
 */
class ManifestFormatException(message: String) : IllegalArgumentException(message)

// ---------------------------------------------------------------------------
// 线格式的小工具（文件级私有，Writer 与 Reader 共用同一份编码真源）
//
// 每行恰好一个 token，首字符是 tag，其余是 payload：
//   S 加 UTF-8 字节的小写 hex        = 字符串
//   Z                                 = 可空字段的 null
//   I 加十进制字面量                  = 整数（可负，csv 下标 -1 用得上）
//   F 加 16 位小写 hex                = Double 的 IEEE-754 原始位
//   B0 / B1                           = 布尔
//   E 加枚举名 UTF-8 字节的小写 hex   = 枚举（读侧按白名单精确匹配，不做大小写归一）
//   @加段名                           = 段开始
//   /                                 = 段结束
// 首行是固定字面量 RT2MANIFEST/3。段顺序、段内字段顺序全部写死，读侧逐项严格期望。
// 旧版 RT2MANIFEST/1 与 RT2MANIFEST/2 在解码首行即明示拒载并给迁移提示，不静默兼容、不猜缺段。
// ---------------------------------------------------------------------------

private val HEX_CHARS = "0123456789abcdef".toCharArray()

private fun hexOf(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xff
        sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0f])
    }
    return sb.toString()
}

private fun hexNibble(c: Char, where: String): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    else -> throw ManifestFormatException(
        "$where：hex 字符「$c」非法（只允许小写 0-9a-f，不做大小写归一）",
    )
}

private fun bytesOfHex(s: String, where: String): ByteArray {
    if (s.length % 2 != 0) {
        throw ManifestFormatException("$where：hex 长度必须为偶数，实际 ${s.length}（「$s」）")
    }
    val out = ByteArray(s.length / 2)
    var i = 0
    while (i < out.size) {
        out[i] = ((hexNibble(s[2 * i], where) shl 4) or hexNibble(s[2 * i + 1], where)).toByte()
        i++
    }
    return out
}

/** 严格 UTF-8 解码：非法字节序列即坏档，不用默认替换字符掩盖。 */
private fun utf8Strict(bytes: ByteArray): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        throw ManifestFormatException("Manifest 含非法 UTF-8 字节序列（坏档）")
    }
}

private val CSV_OPS: Map<String, CsvPatchOp> = enumValues<CsvPatchOp>().associateBy { it.name }
private val FIELD_TYPES: Map<String, ComponentFieldType> =
    enumValues<ComponentFieldType>().associateBy { it.name }
private val HOOK_PHASES: Map<String, HookPhase> = enumValues<HookPhase>().associateBy { it.name }
private val INTERACTION_PHASES: Map<String, InteractionPhase> =
    enumValues<InteractionPhase>().associateBy { it.name }
private val JOB_CATCH_UPS: Map<String, JobCatchUp> = enumValues<JobCatchUp>().associateBy { it.name }

/**
 * 声明事务的确定性 Manifest 载体（era-arch-v61u.md §5 / §12.1 / §20.4；RT2 自研文件形态，
 * C# 原版无对应物，权威 = 蓝图 §5 与 §20.4 加 DeclarationRegistry.kt 的 DeclarationDomain
 * 加 ExtensionDeclarations.kt 的构造期约束，见交付 TODO(verify) 卡点）。
 *
 * 格式版本 RT2MANIFEST/3：共 18 个必选段，覆盖 [ModuleDescriptor] 全部字段。
 * 原 13 个内容段（meta 至 relationship-schemas）加新增 3 段 hooks、interaction-hooks、
 * phase-jobs（承载 §20/§21.1 五类声明）加末尾 2 个元数据段 exported-macros、artifact-sha256。
 * 旧档 RT2MANIFEST/1 与 RT2MANIFEST/2 在解码首行即明示拒载并给迁移提示，不静默兼容、
 * 不猜缺段、不双版本各写解析；编码只产 RT2MANIFEST/3。
 *
 * phase-jobs 段用判别 tag（B0 = Phase，B1 = Job，B2 = JobTemplate）同时承载
 * [PhaseDeclaration] / [JobDeclaration] / [JobTemplateDeclaration]；五类声明每条构造都包在
 * Reader.guarded 里，构造期不变量失败归一成带行号的格式异常。声明列表按声明序原样往返、
 * 禁止按 key 排序 —— §20.1 的确定性执行序是 priority 升序加声明序，排序会破坏语义；
 * 这与 Set 与 Map 写侧按键排序的口径不同（后者只定字节序，声明序无执行语义）。
 *
 * component-schemas 段用同一判别 tag 技术承载组件 schema 与角色变量路由（M5-4 刀 1，
 * RECON-RT2-M54 裁定 R1 方案 B）：段数 18 不变，段内每条目按首 token 分流 ——
 * `S` 行 = 裸组件 schema（**缺省形**，与旧档逐字节同形，写侧对无路由描述符不落任何
 * tag 行，保证既有资产零重钉）；`B0` = 裸组件 schema 的显式 tag 形（读侧接受，写侧不产）；
 * `B1` = [CharaRouteDeclaration]（B1 留给路由子类型，与 phase-jobs 的 B1=Job 无关，
 * tag 按段隔离）。旧解码器读含 B1 条目的新档会在「期望字符串 token(S)」处明示拒载
 * （manifest 全部由同仓 modgen 产出、随包投递，不存在旧解码器读新档的野生产物）；
 * 新解码器读旧档走缺省形，往返逐字节等价（判据 = ManifestProdChainTest「无路由 manifest
 * 往返逐字节不变」用例）。
 *
 * 确定性口径：段顺序与段内字段顺序写死；Set 与 Map 在写侧按原文自然序排序后落盘
 * （不做大小写折叠，排序只定字节序不改字符）；字符串、枚举名走 UTF-8 字节的小写 hex，
 * Double 走原始位的小写 hex；读侧严格校验 Set 与 Map 的键已按严格升序且无重复。
 * 因此同一 descriptor 两次 write 逐字节相同，write 到 read 到 write 亦逐字节相同。
 *
 * fail-closed 口径：未知段 / 未知 token tag / 段乱序 / 段重复 / 缺段 / 截断 /
 * 尾部多余字节 / 非法枚举值 / 非法判别 tag / 非法 hex / 非法 UTF-8 / CR 行尾 /
 * 未以 LF 收口 / Set 与 Map 键未排序或重复 / 构造期不变量不符，全部抛
 * [ManifestFormatException]，消息点名行号与期望 vs 实际；解码链任一步失败都不返回半成品。
 */
object ManifestCodec {

    private const val MAGIC = "RT2MANIFEST/3"

    /** 顶层段顺序（固定；读侧逐项严格期望，乱序或缺段即拒）。 */
    private val SECTIONS = listOf(
        "meta",
        "requires",
        "load-after",
        "external-calls",
        "override-functions",
        "global-dims",
        "private-dims",
        "csv-patches",
        "csv-value-patches",
        "entity-refs",
        "component-schemas",
        "entity-archetypes",
        "relationship-schemas",
        "hooks",
        "interaction-hooks",
        "phase-jobs",
        "exported-macros",
        "artifact-sha256",
    )

    /** 把一个描述符编码成确定性 Manifest 字节流。 */
    fun encode(d: ModuleDescriptor): ByteArray {
        val w = Writer()
        w.line(MAGIC)

        w.head("meta")
        w.str(d.packageId)
        w.str(d.version)
        w.tail()

        w.head("requires")
        w.strList(d.requires)
        w.tail()

        w.head("load-after")
        w.strList(d.loadAfter)
        w.tail()

        w.head("external-calls")
        w.strList(d.externalCalls)
        w.tail()

        w.head("override-functions")
        w.strSet(d.overrideFunctions)
        w.tail()

        w.head("global-dims")
        w.list(d.globalDims) { w.dimSpec(it) }
        w.tail()

        w.head("private-dims")
        w.privateDims(d.privateDims)
        w.tail()

        w.head("csv-patches")
        w.list(d.csvPatches) { w.csvKeyword(it) }
        w.tail()

        w.head("csv-value-patches")
        w.list(d.csvValuePatches) { w.csvValue(it) }
        w.tail()

        w.head("entity-refs")
        w.strSet(d.entityRefs)
        w.tail()

        w.head("component-schemas")
        // 判别 tag 子类型（M5-4 刀 1）：裸 schema 走缺省形（不落 tag 行，旧档字节逐位一致），
        // 路由声明走 B1 行；计数 = 两者之和，读侧按首 token 分流（见类 KDoc）。
        w.long((d.componentSchemas.size + d.charaRoutes.size).toLong())
        for (s in d.componentSchemas) w.componentSchema(s)
        for (r in d.charaRoutes) w.charaRoute(r)
        w.tail()

        w.head("entity-archetypes")
        w.list(d.entityArchetypes) { w.archetype(it) }
        w.tail()

        w.head("relationship-schemas")
        w.list(d.relationshipSchemas) { w.relationship(it) }
        w.tail()

        w.head("hooks")
        w.list(d.hookDeclarations) { w.hook(it) }
        w.tail()

        w.head("interaction-hooks")
        w.list(d.interactionHookDeclarations) { w.interactionHook(it) }
        w.tail()

        w.head("phase-jobs")
        w.phaseJobs(d.phaseDeclarations, d.jobDeclarations, d.jobTemplateDeclarations)
        w.tail()

        w.head("exported-macros")
        w.strMap(d.exportedMacros)
        w.tail()

        w.head("artifact-sha256")
        w.str(d.artifactSha256)
        w.tail()

        return w.bytes()
    }

    /** 把 Manifest 字节流解码成描述符；任一坏档抛 [ManifestFormatException]，不返回半成品。 */
    fun decode(bytes: ByteArray): ModuleDescriptor = Reader(bytes).decodeAll()

    // -----------------------------------------------------------------------
    // 写侧
    // -----------------------------------------------------------------------
    private class Writer {
        private val sb = StringBuilder()

        fun line(s: String) {
            sb.append(s).append('\n')
        }

        fun head(name: String) = line("@$name")

        fun tail() = line("/")

        fun str(v: String) = line("S" + hexOf(v.toByteArray(StandardCharsets.UTF_8)))

        fun nstr(v: String?) = if (v == null) line("Z") else str(v)

        fun long(v: Long) = line("I" + v.toString())

        fun int(v: Int) = long(v.toLong())

        fun bool(v: Boolean) = line(if (v) "B1" else "B0")

        fun nlong(v: Long?) = if (v == null) line("Z") else long(v)

        fun nfloat(v: Double?) =
            if (v == null) {
                line("Z")
            } else {
                line("F" + java.lang.Long.toHexString(v.toRawBits()).padStart(16, '0'))
            }

        fun enum(v: Enum<*>) = line("E" + hexOf(v.name.toByteArray(StandardCharsets.UTF_8)))

        fun <T> list(items: List<T>, body: (T) -> Unit) {
            long(items.size.toLong())
            for (x in items) body(x)
        }

        fun strList(items: List<String>) {
            long(items.size.toLong())
            for (x in items) str(x)
        }

        /** Set 落盘前按原文自然序排序（不折叠大小写），保证确定性且读侧可校验无重复。 */
        fun strSet(items: Set<String>) {
            val sorted = items.sorted()
            long(sorted.size.toLong())
            for (x in sorted) str(x)
        }

        /** Map 落盘前按键自然序排序（不折叠大小写）。 */
        fun strMap(items: Map<String, String>) {
            val sorted = items.entries.sortedBy { it.key }
            long(sorted.size.toLong())
            for ((k, v) in sorted) {
                str(k)
                str(v)
            }
        }

        fun privateDims(items: Map<String, List<DimSpec>>) {
            val sorted = items.entries.sortedBy { it.key }
            long(sorted.size.toLong())
            for ((k, v) in sorted) {
                str(k)
                long(v.size.toLong())
                for (d in v) dimSpec(d)
            }
        }

        fun dimSpec(d: DimSpec) {
            str(d.name)
            bool(d.isStr)
            long(d.dims.size.toLong())
            for (x in d.dims) long(x.toLong())
            bool(d.isConst)
            bool(d.isRef)
            bool(d.isGlobal)
            bool(d.isSavedata)
            bool(d.isCharaData)
            bool(d.isStatic)
            bool(d.isPrivate)
            val di = d.defaultsInt
            if (di == null) {
                line("Z")
            } else {
                long(di.size.toLong())
                for (x in di) long(x)
            }
            val ds = d.defaultsStr
            if (ds == null) {
                line("Z")
            } else {
                long(ds.size.toLong())
                for (x in ds) str(x)
            }
            nfloat(d.defaultsFloat)
            bool(d.isFloat)
        }

        fun csvKeyword(p: CsvKeywordPatch) {
            str(p.tableName)
            int(p.index)
            str(p.keyword)
            bool(p.isAlias)
        }

        fun csvValue(p: CsvValuePatch) {
            str(p.table)
            str(p.row)
            str(p.column)
            enum(p.op)
            nstr(p.value)
        }

        fun componentSchema(s: ComponentSchema) {
            str(s.packageId)
            str(s.name)
            long(s.fields.size.toLong())
            for (f in s.fields) {
                str(f.name)
                enum(f.type)
                bool(f.isEntityRef)
            }
        }

        /**
         * component-schemas 段的路由子类型行（B1 = [CharaRouteDeclaration]，M5-4 刀 1）。
         * tag 行在前，体 = 变量族原文 + 格下标清单（声明序保真，构造期已拒负值与重复；
         * 空 = 全族全跨度）。裸 schema 的缺省形不落 tag 行（见类 KDoc 字节兼容口径）。
         */
        fun charaRoute(r: CharaRouteDeclaration) {
            line("B1")
            str(r.variable)
            long(r.cells.size.toLong())
            for (c in r.cells) long(c.toLong())
        }

        fun archetype(a: EntityArchetype) {
            str(a.name)
            long(a.components.size.toLong())
            for (c in a.components) str(c)
        }

        fun relationship(r: RelationshipSchema) {
            str(r.name)
            bool(r.directed)
            bool(r.dedupeEdges)
            bool(r.cascadeOnDestroy)
            bool(r.autoInverse)
            nstr(r.inverseName)
        }

        fun hook(h: HookDeclaration) {
            str(h.targetKey)
            enum(h.phase)
            int(h.priority)
            bool(h.mayMutateArgs)
            bool(h.mayMutateReturn)
            bool(h.runOnFailure)
        }

        fun interactionHook(h: InteractionHookDeclaration) {
            enum(h.phase)
            int(h.priority)
        }

        /**
         * phase-jobs 段：按 B0 Phase、B1 Job、B2 JobTemplate 三组写，各自按声明序保真。
         * 判别 tag 直接用字面 B0/B1/B2（B0/B1 与旧 /2 档逐字节同形，B2 为本版新增）。
         */
        fun phaseJobs(
            phases: List<PhaseDeclaration>,
            jobs: List<JobDeclaration>,
            templates: List<JobTemplateDeclaration>,
        ) {
            long((phases.size + jobs.size + templates.size).toLong())
            for (p in phases) {
                line("B0")
                str(p.name)
                int(p.priority)
            }
            for (j in jobs) {
                line("B1")
                str(j.jobId)
                str(j.phase)
                long(j.firstDay)
                nlong(j.everyDays)
                int(j.priority)
            }
            for (t in templates) {
                line("B2")
                str(t.templateKey)
                str(t.phase)
                int(t.priority)
                enum(t.catchUp)
                nlong(t.maxCatchUp)
            }
        }

        fun bytes(): ByteArray = sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    // -----------------------------------------------------------------------
    // 读侧
    // -----------------------------------------------------------------------
    private class Reader(bytes: ByteArray) {

        private val text: String = utf8Strict(bytes)
        private val lines: List<String>
        private var index = 0
        private var lastLineNo = 0

        init {
            if (text.contains('\r')) {
                throw ManifestFormatException("Manifest 含 CR（只接受 LF 行尾，不做归一）")
            }
            if (!text.endsWith("\n")) {
                throw ManifestFormatException("Manifest 未以 LF 收口（截断或尾部多余字节）")
            }
            lines = text.split("\n").dropLast(1)
        }

        private fun next(): String {
            if (index >= lines.size) {
                throw ManifestFormatException("第 ${index + 1} 行：Manifest 在声明结束前截断（缺 token）")
            }
            lastLineNo = index + 1
            return lines[index++]
        }

        /**
         * 预览下一 token（不消费）：component-schemas 段的判别分流用 —— 裸 schema 的缺省形
         * 没有 tag 行，读侧必须先看首 token 是 S（缺省裸 schema）还是 B0/B1（显式 tag）。
         */
        private fun peekToken(): String {
            if (index >= lines.size) {
                throw ManifestFormatException("第 ${index + 1} 行：Manifest 在声明结束前截断（缺 token）")
            }
            return lines[index]
        }

        private fun err(msg: String): Nothing =
            throw ManifestFormatException("第 $lastLineNo 行：$msg")

        private fun errAt(line: Int, msg: String): Nothing =
            throw ManifestFormatException("第 $line 行：$msg")

        /** 数据类构造期不变量失败也归一成可定位的格式异常（带结构起始行）。 */
        private fun <T> guarded(where: String, block: () -> T): T {
            val at = lastLineNo
            return try {
                block()
            } catch (e: IllegalArgumentException) {
                throw ManifestFormatException("第 $at 行附近构造 $where 失败（坏档）：${e.message}")
            }
        }

        private fun parseDec(s: String): Long {
            if (s.isEmpty()) err("整数字面量为空")
            val neg = s[0] == '-'
            val digits = if (neg) s.substring(1) else s
            if (digits.isEmpty() || !digits.all { it in '0'..'9' }) {
                err("整数字面量非法：「$s」（只接受十进制，无加号 / 无 0x / 无前导 0）")
            }
            if (digits.length > 1 && digits[0] == '0') err("整数字面量含前导 0：「$s」")
            val mag = digits.toLongOrNull() ?: err("整数越出 Long 范围：「$s」")
            return if (neg) -mag else mag
        }

        private fun expectLong(): Long {
            val line = next()
            if (line.isEmpty() || line[0] != 'I') err("期望整数 token(I)，实际「$line」")
            return parseDec(line.substring(1))
        }

        private fun countOf(v: Long): Int {
            if (v < 0 || v > Int.MAX_VALUE) err("计数越界（须 0..${Int.MAX_VALUE}）：$v")
            return v.toInt()
        }

        private fun expectCount(): Int = countOf(expectLong())

        private fun expectInt(): Int {
            val v = expectLong()
            if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) err("整数越出 Int 范围：$v")
            return v.toInt()
        }

        private fun expectStr(): String {
            val line = next()
            if (line.isEmpty() || line[0] != 'S') err("期望字符串 token(S)，实际「$line」")
            return utf8Strict(bytesOfHex(line.substring(1), "字符串"))
        }

        private fun expectNStr(): String? {
            val line = next()
            return when {
                line == "Z" -> null
                line.isNotEmpty() && line[0] == 'S' ->
                    utf8Strict(bytesOfHex(line.substring(1), "可空字符串"))
                else -> err("期望可空字符串 token(S 或 Z)，实际「$line」")
            }
        }

        private fun expectBool(): Boolean = when (val line = next()) {
            "B0" -> false
            "B1" -> true
            else -> err("期望布尔 token(B0 或 B1)，实际「$line」")
        }

        private fun expectNFloat(): Double? {
            val line = next()
            if (line == "Z") return null
            if (line.isEmpty() || line[0] != 'F') err("期望可空浮点 token(F 或 Z)，实际「$line」")
            val hex = line.substring(1)
            if (hex.length != 16) err("浮点 token 必须是 16 位小写 hex，实际 ${hex.length} 位（「$hex」）")
            if (!hex.all { it in '0'..'9' || it in 'a'..'f' }) {
                err("浮点 token 只允许小写 hex（不做大小写归一）：「$hex」")
            }
            val bits = try {
                java.lang.Long.parseUnsignedLong(hex, 16)
            } catch (e: NumberFormatException) {
                err("浮点 token hex 非法：「$hex」")
            }
            return Double.fromBits(bits)
        }

        private fun <T : Enum<T>> expectEnum(allowed: Map<String, T>, label: String): T {
            val line = next()
            if (line.isEmpty() || line[0] != 'E') err("期望枚举 token(E)，实际「$line」")
            val name = utf8Strict(bytesOfHex(line.substring(1), "枚举 $label"))
            return allowed[name] ?: err(
                "非法枚举值「$name」（$label 合法值：${allowed.keys.sorted().joinToString(",")}；" +
                    "精确匹配，不做大小写归一）",
            )
        }

        /** 可空的长整型数组：Z = null，否则 I计数 加 count 个 I。 */
        private fun expectNLongArray(): LongArray? {
            val line = next()
            if (line == "Z") return null
            if (line.isEmpty() || line[0] != 'I') err("期望可空长整型数组(I计数 或 Z)，实际「$line」")
            val n = countOf(parseDec(line.substring(1)))
            return LongArray(n) { expectLong() }
        }

        /** 可空长整型：Z = null，否则 I 加十进制（复用 parseDec，不引入新 token）。 */
        private fun expectNLong(): Long? {
            val line = next()
            if (line == "Z") return null
            if (line.isEmpty() || line[0] != 'I') err("期望可空长整型(I 或 Z)，实际「$line」")
            return parseDec(line.substring(1))
        }

        /** 可空的字符串表：Z = null，否则 I计数 加 count 个 S。 */
        private fun expectNStrList(): List<String>? {
            val line = next()
            if (line == "Z") return null
            if (line.isEmpty() || line[0] != 'I') err("期望可空字符串表(I计数 或 Z)，实际「$line」")
            val n = countOf(parseDec(line.substring(1)))
            return List(n) { expectStr() }
        }

        private fun expectStrList(): List<String> = List(expectCount()) { expectStr() }

        private fun expectStrSet(): Set<String> {
            val n = expectCount()
            val out = LinkedHashSet<String>(n)
            var prev: String? = null
            for (i in 0 until n) {
                val k = expectStr()
                if (prev != null && prev!! >= k) {
                    err("Set 键未按严格升序或重复：「$k」（写侧须排序去重；不做大小写归一）")
                }
                out.add(k)
                prev = k
            }
            return out
        }

        private fun expectStrMap(): Map<String, String> {
            val n = expectCount()
            val out = LinkedHashMap<String, String>(n)
            var prev: String? = null
            for (i in 0 until n) {
                val k = expectStr()
                if (prev != null && prev!! >= k) {
                    err("Map 键未按严格升序或重复：「$k」（写侧须排序去重；不做大小写归一）")
                }
                val v = expectStr()
                out[k] = v
                prev = k
            }
            return out
        }

        private fun expectPrivateDims(): Map<String, List<DimSpec>> {
            val n = expectCount()
            val out = LinkedHashMap<String, List<DimSpec>>(n)
            var prev: String? = null
            for (i in 0 until n) {
                val k = expectStr()
                if (prev != null && prev!! >= k) {
                    err("private-dims 键未按严格升序或重复：「$k」（写侧须排序去重；不做大小写归一）")
                }
                val count = expectCount()
                val dims = List(count) { expectDimSpec() }
                out[k] = dims
                prev = k
            }
            return out
        }

        private fun expectDimSpec(): DimSpec {
            val name = expectStr()
            val isStr = expectBool()
            val dimCount = expectCount()
            val dims = IntArray(dimCount) { expectInt() }
            val isConst = expectBool()
            val isRef = expectBool()
            val isGlobal = expectBool()
            val isSavedata = expectBool()
            val isCharaData = expectBool()
            val isStatic = expectBool()
            val isPrivate = expectBool()
            val defaultsInt = expectNLongArray()
            val defaultsStr = expectNStrList()
            val defaultsFloat = expectNFloat()
            val isFloat = expectBool()
            return guarded("DimSpec") {
                DimSpec(
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
            }
        }

        private fun expectCsvKeyword(): CsvKeywordPatch {
            val tableName = expectStr()
            val index = expectInt()
            val keyword = expectStr()
            val isAlias = expectBool()
            return guarded("CsvKeywordPatch") {
                CsvKeywordPatch(tableName, index, keyword, isAlias)
            }
        }

        private fun expectCsvValue(): CsvValuePatch {
            val table = expectStr()
            val row = expectStr()
            val column = expectStr()
            val op = expectEnum(CSV_OPS, "CsvPatchOp")
            val value = expectNStr()
            return guarded("CsvValuePatch") {
                CsvValuePatch(table, row, column, op, value)
            }
        }

        /** 裸组件 schema 体（tag 分流已由 [expectComponentSchemaOrRoute] 处理）。 */
        private fun expectComponentSchemaBody(): ComponentSchema {
            val packageId = expectStr()
            val name = expectStr()
            val fieldCount = expectCount()
            val fields = List(fieldCount) {
                val fName = expectStr()
                val fType = expectEnum(FIELD_TYPES, "ComponentFieldType")
                val fIsEntityRef = expectBool()
                guarded("ComponentFieldSpec") {
                    ComponentFieldSpec(fName, fType, fIsEntityRef)
                }
            }
            return guarded("ComponentSchema") {
                ComponentSchema(packageId, name, fields)
            }
        }

        /**
         * component-schemas 段（M5-4 刀 1）：计数 = 裸 schema 与路由声明之和，逐条目按
         * 首 token 分流（判别规则见 [expectComponentSchemaOrRoute] 与类 KDoc）。
         */
        private fun expectComponentSchemasSection(): Pair<List<ComponentSchema>, List<CharaRouteDeclaration>> {
            val n = expectCount()
            val schemas = ArrayList<ComponentSchema>(n)
            val routes = ArrayList<CharaRouteDeclaration>()
            for (i in 0 until n) {
                val (schema, route) = expectComponentSchemaOrRoute()
                if (schema != null) schemas += schema
                if (route != null) routes += route
            }
            return Pair(schemas, routes)
        }

        /**
         * component-schemas 段的条目分流（M5-4 刀 1）：首 token 判别 —— `B1` = 路由声明
         * （消费 tag 后读体）、`B0` = 显式 tag 的裸 schema（消费 tag 后读体）、其余以 `S`
         * 开头 = 缺省形的裸 schema（不消费，直接读体）；其余 token 明示合法三形后拒绝。
         * 每条目恰是两种子类型之一（schema 与 route 恰一非空，不双读）。
         */
        private fun expectComponentSchemaOrRoute(): Pair<ComponentSchema?, CharaRouteDeclaration?> {
            return when (val head = peekToken()) {
                "B1" -> {
                    next()
                    Pair(null, expectCharaRouteBody())
                }
                "B0" -> {
                    next()
                    Pair(expectComponentSchemaBody(), null)
                }
                else -> {
                    if (head.isEmpty() || head[0] != 'S') {
                        err(
                            "component-schemas 判别 tag 非法：「$head」（合法：缺省裸 schema 的 S 行、" +
                                "显式 B0、路由声明 B1；精确匹配，不做大小写归一）",
                        )
                    }
                    Pair(expectComponentSchemaBody(), null)
                }
            }
        }

        /** 路由声明体（B1 tag 已消费）：变量族原文 + 格下标清单（空 = 全族全跨度）。 */
        private fun expectCharaRouteBody(): CharaRouteDeclaration {
            val variable = expectStr()
            val cellCount = expectCount()
            val cells = List(cellCount) { expectInt() }
            return guarded("CharaRouteDeclaration") {
                CharaRouteDeclaration(variable, cells)
            }
        }

        private fun expectArchetype(): EntityArchetype {
            val name = expectStr()
            val componentCount = expectCount()
            val components = List(componentCount) { expectStr() }
            return guarded("EntityArchetype") {
                EntityArchetype(name, components)
            }
        }

        private fun expectRelationship(): RelationshipSchema {
            val name = expectStr()
            val directed = expectBool()
            val dedupeEdges = expectBool()
            val cascadeOnDestroy = expectBool()
            val autoInverse = expectBool()
            val inverseName = expectNStr()
            return guarded("RelationshipSchema") {
                RelationshipSchema(name, directed, dedupeEdges, cascadeOnDestroy, autoInverse, inverseName)
            }
        }

        private fun expectHook(): HookDeclaration {
            val targetKey = expectStr()
            val phase = expectEnum(HOOK_PHASES, "HookPhase")
            val priority = expectInt()
            val mayMutateArgs = expectBool()
            val mayMutateReturn = expectBool()
            val runOnFailure = expectBool()
            return guarded("HookDeclaration") {
                HookDeclaration(targetKey, phase, priority, mayMutateArgs, mayMutateReturn, runOnFailure)
            }
        }

        private fun expectInteractionHook(): InteractionHookDeclaration {
            val phase = expectEnum(INTERACTION_PHASES, "InteractionPhase")
            val priority = expectInt()
            return guarded("InteractionHookDeclaration") {
                InteractionHookDeclaration(phase, priority)
            }
        }

        /**
         * phase-jobs 段：逐条按判别 tag 分流（B0 Phase / B1 Job / B2 JobTemplate），
         * 各自按声明序保真；非法判别 tag 明示合法三值后拒绝。
         */
        private fun expectPhaseJobs():
            Triple<List<PhaseDeclaration>, List<JobDeclaration>, List<JobTemplateDeclaration>> {
            val n = expectCount()
            val phases = ArrayList<PhaseDeclaration>()
            val jobs = ArrayList<JobDeclaration>()
            val templates = ArrayList<JobTemplateDeclaration>()
            for (i in 0 until n) {
                when (val tag = next()) {
                    "B0" -> {
                        val name = expectStr()
                        val priority = expectInt()
                        phases += guarded("PhaseDeclaration") {
                            PhaseDeclaration(name, priority)
                        }
                    }
                    "B1" -> {
                        val jobId = expectStr()
                        val phase = expectStr()
                        val firstDay = expectLong()
                        val everyDays = expectNLong()
                        val priority = expectInt()
                        jobs += guarded("JobDeclaration") {
                            JobDeclaration(jobId, phase, firstDay, everyDays, priority)
                        }
                    }
                    "B2" -> {
                        val templateKey = expectStr()
                        val phase = expectStr()
                        val priority = expectInt()
                        val catchUp = expectEnum(JOB_CATCH_UPS, "JobCatchUp")
                        val maxCatchUp = expectNLong()
                        templates += guarded("JobTemplateDeclaration") {
                            JobTemplateDeclaration(templateKey, phase, priority, catchUp, maxCatchUp)
                        }
                    }
                    else -> err(
                        "phase-jobs 判别 tag 非法：「$tag」（合法 tag 只有 B0/B1/B2；" +
                            "精确匹配，不做大小写归一）",
                    )
                }
            }
            return Triple(phases, jobs, templates)
        }

        fun decodeAll(): ModuleDescriptor {
            val magic = next()
            if (magic == "RT2MANIFEST/1" || magic == "RT2MANIFEST/2") {
                err(
                    "$magic 已拒载：本解码器只接受 RT2MANIFEST/3。" +
                        "请用 /3 编码器重出 Manifest，phase-jobs 段可含 B2 子类型（JobTemplate）；" +
                        "旧版不静默兼容、不猜缺段、不按旧段表补空声明",
                )
            }
            if (magic != MAGIC) err("首行必须为 $MAGIC，实际「$magic」")

            var packageId = ""
            var version = ""
            var requires: List<String> = emptyList()
            var loadAfter: List<String> = emptyList()
            var externalCalls: List<String> = emptyList()
            var overrideFunctions: Set<String> = emptySet()
            var globalDims: List<DimSpec> = emptyList()
            var privateDims: Map<String, List<DimSpec>> = emptyMap()
            var csvPatches: List<CsvKeywordPatch> = emptyList()
            var csvValuePatches: List<CsvValuePatch> = emptyList()
            var entityRefs: Set<String> = emptySet()
            var componentSchemas: List<ComponentSchema> = emptyList()
            var charaRoutes: List<CharaRouteDeclaration> = emptyList()
            var entityArchetypes: List<EntityArchetype> = emptyList()
            var relationshipSchemas: List<RelationshipSchema> = emptyList()
            var hookDeclarations: List<HookDeclaration> = emptyList()
            var interactionHookDeclarations: List<InteractionHookDeclaration> = emptyList()
            var phaseDeclarations: List<PhaseDeclaration> = emptyList()
            var jobDeclarations: List<JobDeclaration> = emptyList()
            var jobTemplateDeclarations: List<JobTemplateDeclaration> = emptyList()
            var exportedMacros: Map<String, String> = emptyMap()
            var artifactSha256 = ""

            val seen = HashSet<String>()
            for (name in SECTIONS) {
                val head = next()
                val headName = if (head.startsWith("@")) head.substring(1) else null
                if (headName != null && seen.contains(headName)) {
                    err("段 $head 重复出现（重复键整包拒绝）")
                }
                if (head != "@$name") {
                    err(
                        "期望段 @$name，实际「$head」（乱序 / 缺段 / 未知段 / 重复键；" +
                            "段名精确匹配，不做大小写归一）",
                    )
                }
                seen.add(name)
                when (name) {
                    "meta" -> {
                        packageId = expectStr()
                        version = expectStr()
                    }
                    "requires" -> requires = expectStrList()
                    "load-after" -> loadAfter = expectStrList()
                    "external-calls" -> externalCalls = expectStrList()
                    "override-functions" -> overrideFunctions = expectStrSet()
                    "global-dims" -> globalDims = List(expectCount()) { expectDimSpec() }
                    "private-dims" -> privateDims = expectPrivateDims()
                    "csv-patches" -> csvPatches = List(expectCount()) { expectCsvKeyword() }
                    "csv-value-patches" -> csvValuePatches = List(expectCount()) { expectCsvValue() }
                    "entity-refs" -> entityRefs = expectStrSet()
                    "component-schemas" -> {
                        val (schemas, routes) = expectComponentSchemasSection()
                        componentSchemas = schemas
                        charaRoutes = routes
                    }
                    "entity-archetypes" -> entityArchetypes = List(expectCount()) { expectArchetype() }
                    "relationship-schemas" -> relationshipSchemas = List(expectCount()) { expectRelationship() }
                    "hooks" -> hookDeclarations = List(expectCount()) { expectHook() }
                    "interaction-hooks" -> interactionHookDeclarations =
                        List(expectCount()) { expectInteractionHook() }
                    "phase-jobs" -> {
                        val (phases, jobs, templates) = expectPhaseJobs()
                        phaseDeclarations = phases
                        jobDeclarations = jobs
                        jobTemplateDeclarations = templates
                    }
                    "exported-macros" -> exportedMacros = expectStrMap()
                    "artifact-sha256" -> artifactSha256 = expectStr()
                    else -> err("未知段「$name」（fail-closed，不跳过）")
                }
                val tail = next()
                if (tail != "/") err("期望段尾 /，实际「$tail」")
            }

            if (index != lines.size) {
                errAt(
                    index + 1,
                    "段结束后仍有 ${lines.size - index} 行多余内容（尾部多余字节），" +
                        "首行实际「${lines[index]}」",
                )
            }

            return guarded("ModuleDescriptor") {
                ModuleDescriptor(
                    packageId = packageId,
                    version = version,
                    requires = requires,
                    loadAfter = loadAfter,
                    externalCalls = externalCalls,
                    overrideFunctions = overrideFunctions,
                    globalDims = globalDims,
                    privateDims = privateDims,
                    csvPatches = csvPatches,
                    csvValuePatches = csvValuePatches,
                    entityRefs = entityRefs,
                    componentSchemas = componentSchemas,
                    entityArchetypes = entityArchetypes,
                    relationshipSchemas = relationshipSchemas,
                    hookDeclarations = hookDeclarations,
                    interactionHookDeclarations = interactionHookDeclarations,
                    phaseDeclarations = phaseDeclarations,
                    jobDeclarations = jobDeclarations,
                    jobTemplateDeclarations = jobTemplateDeclarations,
                    exportedMacros = exportedMacros,
                    artifactSha256 = artifactSha256,
                    charaRoutes = charaRoutes,
                )
            }
        }
    }
}
