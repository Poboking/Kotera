package com.eratw.port.core.patch

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.OpcodeRelocation
import com.eratw.port.core.code.PoolRef
import java.io.ByteArrayOutputStream

/**
 * ERBM Module 容器读写（RT2 自研格式，M2-2；关闭 era-arch-v61u.md 12.1 缺口「Module 容器读写不存在」）。
 *
 * 本格式是移植侧 RT2 自研的二进制 Module 容器，Emuera C# 原版没有对应物 —— 无 C# 锚，
 * 语义权威 = 蓝图 era-arch-v61u.md 2 节（:60-70）、10 节 ABI-4 Gate（:295）、12.1 节（:359），
 * 以及 specs 下的 erbc-format.md、patch-format.yaml、isa.yaml。字节级布局由本文件自定，
 * 逐项写进 KDoc；无锚的语义结论见交付报告 TODO(verify) 与卡点。
 *
 * ## 为什么旧 Reader 一定会拒载（ABI-4 Gate 的唯一机制，蓝图 :64 钉死）
 *
 * 旧 Reader 在读任何段正文之前先验文件头：`MappedErbc.parseHeader`（MappedErbc.kt:129-146）
 * 与 `ErbcReader.Decoder.parseHeader`（ErbcReader.kt:569-583）逐字同判据 —— 先比 magic 四字节
 * 必须是 45 52 42 43（ERBC），再比 u16 version 必须落在 v1/v2 白名单
 * （`ErbcFormat.sectionCountFor` ErbcFormat.kt:95-99，v3 返回 -1）。两者都是**已经严格校验**的
 * 头部字段，且都在构造 Program 之前：字节路 `Decoder.header = parseHeader()`（ErbcReader.kt:341）
 * 是类初始化属性，先于 `decodeProgram()`（:343）；映射路 `MappedErbc.open`（MappedErbc.kt:82-100）
 * 在 `open` 阶段解析头，段正文与 Program 都还没碰。于是：
 *
 * - MODULE 态用独立 magic `ERBM`（45 52 42 4D）：旧 Reader 第 4 字节 4D 不等于 43，
 *   在 magic 闸（MappedErbc.kt:129-137 / ErbcReader.kt:569-574）当场抛「magic 不符」。
 * - LINKED_PROGRAM 态沿用 magic `ERBC`（45 52 42 43）、把 u16 version 升到 3：magic 闸通过，
 *   版本白名单闸（MappedErbc.kt:139-146 / ErbcReader.kt:576-583）当场抛「不支持的 .erbc 版本 3」。
 *
 * 即「Module 用独立 magic、Linked 升 major v3」分别命中旧 Reader 两个不同且已严格校验的字段，
 * 与 REQUIRED_FEATURES 无关 —— REQUIRED_FEATURES 位于本格式的文本头偏移 8 之后，旧 Reader
 * 在上述两闸已经拒载，根本读不到它；它是**新 Reader 自己**的 fail-closed 闸，不是旧 Reader 的
 * 拒载理由（蓝图 :64 末句、CONTRACT-RT2-M2-2 3.2 注）。
 *
 * ## 物理布局（小端，纯 JVM）
 *
 * 偏移 0  : magic[4]           ERBM（MODULE）或 ERBC（LINKED_PROGRAM）
 * 偏移 4  : u16 containerVersion  MODULE/LINKED 各取 [containerVersionFor]，默认都是 3
 * 偏移 6  : u16 headerLen        文本头字节数
 * 偏移 8  : headerLen 字节的文本头（UTF-8、LF、逐行 key value、末行 --- 终止）
 * 之后    : 载荷二进制（按 CONTAINER_ROLE 分流，见 [writePayload] / [readModulePayload]）
 *
 * 文本头四行顺序固定、缺一/乱序/重复/多字段/未知键即拒：
 * CONTAINER_ROLE MODULE 或 LINKED_PROGRAM
 * REQUIRED_FEATURES 0x7
 * ISA_VERSION 3
 * ---
 *
 * ## 载荷（蓝图 :65 MODULE 态 / :66 LINKED 态）
 *
 * MODULE 态 = 零基线四池 + imports + callTargets + relocations + exports + descriptor：
 * descriptor（Manifest 核心字段的可序列化子集）、imports（外部符号名表）、
 * callTargets（编译产物 _CALL.a 原始值，含 super 与外部导入哨兵）、
 * relocations（逐操作数语义的重定位项）、exports（函数签名/宏/变量声明）、
 * 零基线四池（long/string/expr/statement 四计数 + 六基线全 0 标记 + 指令流 + 池字节块）。
 * 池内容以原始字节块原样保真，容器不解释 Expr/Statement 树 —— 真正构造 Program 并喂给
 * ModuleLinker.link 属 M2-2 后的 mod 链装配（CONTRACT-RT2-M2-2 3.1 边界），写进「需组长接线」。
 *
 * LINKED 态 = 三段（symbol-catalog / dispatch / event-registry）原样保真 + 两条残留自检
 * （导入表空、重定位表空，蓝图 :66 链接器出口自检：不许残留负导入下标或 super 哨兵）。
 *
 * ## 读侧三道闸（fail-closed，逐条拒绝且消息点名）
 *
 * 1. 头部三强制字段 CONTAINER_ROLE / REQUIRED_FEATURES / ISA_VERSION（缺一/乱序/未知位/越界）。
 * 2. 重定位机器闸：每条 relocations 的 opcode 与声明类别，逐项核对**唯一真源**
 *    [OpcodeRelocation.of]（OpcodeRelocation.kt:224）与 [com.eratw.port.core.code.OperandRelocation.at]
 *    （:53），不另写第二份分类表；未登记 opcode、类别冲突即拒（新增 opcode 忘改表必红）。
 * 3. imports 封顶/哨兵闸：callTargets 的外部导入哨兵解码后 extIdx 必须不越
 *    [CallSentinels.IMPORT_INDEX_MAX]（ModuleDescriptor.kt:224），复用契约层既有判据，不重造。
 *
 * 同一模型两次 [encode] 逐字节相同，encode 后 decode 再 encode 逐字节相同（幂等）。
 */
object ModuleContainerFormat {

    /** MODULE 态独立 magic（第 4 字节 M = 0x4D，区别于 ERBC 的 C = 0x43）。反向证明 ① 改此值。 */
    const val MAGIC_MODULE_TEXT = "ERBM"

    /** LINKED 态沿用 ERBC magic，靠 u16 version = 3 触发旧 Reader 版本白名单拒载。 */
    const val MAGIC_LINKED_TEXT = "ERBC"

    /** MODULE 态容器版本；旧 Reader 在 magic 闸先拒，读不到本值。 */
    const val MODULE_CONTAINER_VERSION = 3

    /** LINKED 态容器版本 = 新 major v3。反向证明 ③ 把此值退回 2，五连测 #5 必红。 */
    const val LINKED_CONTAINER_VERSION = 3

    /** 当前 ISA 版本（DIRECT_CALL_FLAG bit27 那套，蓝图 :67）；ISA_VERSION 头字段只认此值。 */
    const val ISA_VERSION = 3

    /** 固定前缀：magic(4) + containerVersion(2) + headerLen(2)。 */
    const val PREFIX_BYTES = 8

    /** magic 文本字节数（前缀头四字节）。 */
    const val MAGIC_BYTES = 4

    /** containerVersion u16 在固定前缀里的起点（紧接 magic）。 */
    const val VERSION_OFFSET = MAGIC_BYTES

    /** u16 低/高两字节的无符号掩码与高位移位宽（小端）。 */
    const val BYTE_MASK = 0xFF
    const val BYTE_SHIFT = 8

    /** 文本头终止行。 */
    const val TERMINATOR = "---"

    /** role 到 magic 文本。 */
    fun magicTextFor(role: ContainerRole): String = when (role) {
        ContainerRole.MODULE -> MAGIC_MODULE_TEXT
        ContainerRole.LINKED_PROGRAM -> MAGIC_LINKED_TEXT
    }

    /** role 到 u16 容器版本。 */
    fun containerVersionFor(role: ContainerRole): Int = when (role) {
        ContainerRole.MODULE -> MODULE_CONTAINER_VERSION
        ContainerRole.LINKED_PROGRAM -> LINKED_CONTAINER_VERSION
    }

    /** magic 文本到期望 role；其他值一律 null（magic 不符即拒）。 */
    fun roleForMagic(text: String): ContainerRole? = when (text) {
        MAGIC_MODULE_TEXT -> ContainerRole.MODULE
        MAGIC_LINKED_TEXT -> ContainerRole.LINKED_PROGRAM
        else -> null
    }

    /**
     * 先探后读的容器探测（CONTRACT-RT2-M2CODEC 裁定 2；格式唯一真源的分流入口）。
     *
     * 输入 = 文件最前 [PREFIX_BYTES] 字节（magic 4 + containerVersion 2 + headerLen 2；
     * 实际只比对前 6 字节，8 字节下界保证畸形短文件连前缀都凑不齐时直接判非容器）。
     * 返回 [ContainerRole.MODULE] / [ContainerRole.LINKED_PROGRAM]；非容器字节返回 null。
     *
     * 判据两条（与 [ModuleContainerCodec.decode] 的读侧闸同源，不另立第二份口径）：
     * 1. magic 必须是 [MAGIC_MODULE_TEXT] / [MAGIC_LINKED_TEXT]（[roleForMagic]）；
     * 2. containerVersion 必须与 role 配套（[containerVersionFor]，当前都是 3）——
     *    这条把旧 Reader 的 legacy `.erbc`（ERBC magic + v1/v2）排除在容器域外：
     *    ERBC v1/v2 是旧 Reader 产物，不是本格式的 LINKED 态，探测必须返回 null。
     *
     * 消费方两处：`ModPatchLoader.load` 对命中件跳过 legacy 解析（容器件归 Mod 链管）；
     * `ModChainAssembly` 收集面只收命中件进链装配。两处共用本函数，不复制判据。
     */
    fun probe(bytes: ByteArray): ContainerRole? {
        // 单一出口：短文件 / magic 不符 / 版本不配套三条 miss 都汇到末尾的 null。
        // 常量化前缀字面量，判据仍与 decode 的读侧闸同源（不另立第二份口径）。
        if (bytes.size >= PREFIX_BYTES) {
            val magic = String(bytes, 0, MAGIC_BYTES, Charsets.US_ASCII)
            val role = roleForMagic(magic)
            if (role != null) {
                val low = bytes[VERSION_OFFSET].toInt() and BYTE_MASK
                val high = bytes[VERSION_OFFSET + 1].toInt() and BYTE_MASK
                val version = low or (high shl BYTE_SHIFT)
                if (version == containerVersionFor(role)) return role
            }
        }
        return null
    }
}

/** 容器角色（并入本文件，CONTRACT-RT2-M2-2 1 节允许 ModuleContainerRole.kt 并入并说明）。 */
enum class ContainerRole(val wire: String) {
    /** 未链接模块：导入表 + 重定位表 + 负下标哨兵。 */
    MODULE("MODULE"),

    /** 已链接程序：导入表空、重定位表空、三段（LSC/dispatch/event-registry）。 */
    LINKED_PROGRAM("LINKED_PROGRAM"),
    ;

    companion object {
        fun fromWire(s: String): ContainerRole? = values().firstOrNull { it.wire == s }
    }
}

/** REQUIRED_FEATURES 位集（蓝图 :62：IMPORT_RELOCATION / DISPATCH_ISA / SYMBOL_CATALOG…）。 */
object RequiredFeatures {
    const val IMPORT_RELOCATION = 0x1
    const val DISPATCH_ISA = 0x2
    const val SYMBOL_CATALOG = 0x4

    /** 已定义的全部位；出现表外位 = 未知位，fail-closed 拒载。 */
    const val KNOWN_MASK = IMPORT_RELOCATION or DISPATCH_ISA or SYMBOL_CATALOG

    /** 必需位（当前三位全必需，蓝图 :62 三特性齐备）。 */
    const val REQUIRED_MASK = KNOWN_MASK

    /** 位集的可读名（报错文案用）。 */
    fun names(bits: Int): String {
        val parts = mutableListOf<String>()
        if (bits and IMPORT_RELOCATION != 0) parts.add("IMPORT_RELOCATION")
        if (bits and DISPATCH_ISA != 0) parts.add("DISPATCH_ISA")
        if (bits and SYMBOL_CATALOG != 0) parts.add("SYMBOL_CATALOG")
        val unknown = bits and KNOWN_MASK.inv()
        if (unknown != 0) parts.add("UNKNOWN(0x${unknown.toString(16)})")
        return parts.joinToString("|").ifEmpty { "NONE" }
    }
}

/** 容器解码失败（畸形字节 / 版本不符 / 头字段 fail-closed / 机器闸 / 哨兵闸）。 */
class ModuleContainerException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// ======================================================================
// 数据模型（写侧输入 = 读侧产出，逐项相等/幂等由 ModuleContainerTest 钉）
// ======================================================================

/** Manifest 核心字段的可序列化子集（对应 ModuleDescriptor.kt:124-153 的标量字段）。 */
data class ContainerDescriptor(
    val packageId: String,
    val version: String = "",
    val requires: List<String> = emptyList(),
    val loadAfter: List<String> = emptyList(),
    val artifactSha256: String = "",
) {
    /** M2-2 后装配层据此构造完整 ModuleDescriptor（复杂 DimSpec/ComponentSchema 等留缺省）。 */
    fun toModuleDescriptor(externalCalls: List<String>): ModuleDescriptor = ModuleDescriptor(
        packageId = packageId,
        version = version,
        requires = requires,
        loadAfter = loadAfter,
        externalCalls = externalCalls,
        artifactSha256 = artifactSha256,
    )
}

/** 一条重定位项：指令 pc、操作码、操作数槽(a=0/b=1/c=2)、写侧声明的类别。 */
data class RelocationEntry(
    val pc: Int,
    val opcode: Int,
    val slot: Int,
    val category: PoolRef,
)

/** 一条导出项：kind（函数签名/宏/变量声明的类别码）、名字、签名。 */
data class ExportMeta(
    val kind: Int,
    val name: String,
    val signature: String,
)

/** 零基线四池：四计数 + 六基线全 0 标记 + 指令流 + 池字节块（原样保真，容器不解释树）。 */
data class ZeroBasePools(
    val longCount: Int,
    val stringCount: Int,
    val exprCount: Int,
    val statementCount: Int,
    /** 必须 = 0（零基线；LinkableModule.validate 要求六基线全 0）。 */
    val baseMarker: Int,
    val codeWords: List<Int>,
    val poolBlob: List<Byte>,
)

/** LINKED 态三段（原样保真）。 */
data class LinkedSegments(
    val symbolCatalog: List<Byte>,
    val dispatch: List<Byte>,
    val eventRegistry: List<Byte>,
)

/** 容器读写产出的完整模型；MODULE 态 [pools] 非空，LINKED 态 [linkedSegments] 非空。 */
data class ModuleContainer(
    val role: ContainerRole,
    val requiredFeatures: Int,
    val isaVersion: Int,
    val descriptor: ContainerDescriptor,
    val imports: List<String>,
    val callTargets: List<Int>,
    val relocations: List<RelocationEntry>,
    val exports: List<ExportMeta>,
    val pools: ZeroBasePools? = null,
    val linkedSegments: LinkedSegments? = null,
)

// ======================================================================
// 编解码入口
// ======================================================================

object ModuleContainerCodec {

    /** 生产写入：先 fail-closed 校验模型（全或无，不写出残次品），再序列化。 */
    fun encode(model: ModuleContainer): ByteArray {
        validateForWrite(model)
        return writeBytes(model)
    }

    /** 生产读取：解析 + 全套读侧 fail-closed 校验，失败抛 [ModuleContainerException]。 */
    fun decode(bytes: ByteArray): ModuleContainer {
        if (bytes.size < ModuleContainerFormat.PREFIX_BYTES) {
            fail("[长度]", "容器不足前缀 ${ModuleContainerFormat.PREFIX_BYTES} 字节，实为 ${bytes.size}")
        }
        val cur = Cursor(bytes)
        val magic = String(cur.bytes(4), Charsets.US_ASCII)
        val expectedRoleByMagic = ModuleContainerFormat.roleForMagic(magic)
            ?: fail(
                "[magic]",
                "不符：期望 ${ModuleContainerFormat.MAGIC_MODULE_TEXT} 或 " +
                    "${ModuleContainerFormat.MAGIC_LINKED_TEXT}，实为 [${
                        bytes.take(4).joinToString(" ") { (it.toInt() and 0xFF).toString(16) }
                    }]",
            )
        val version = cur.u16()
        val headerLen = cur.u16()
        if (headerLen > cur.remainingCount()) {
            fail("[headerLen]", "$headerLen 越过剩余字节 ${cur.remainingCount()}")
        }
        val headerBytes = cur.bytes(headerLen)
        val header = parseHeader(headerBytes, expectedRoleByMagic, version)
        val payload = cur.remaining()

        return when (header.role) {
            ContainerRole.MODULE -> readModulePayload(header, payload)
            ContainerRole.LINKED_PROGRAM -> readLinkedPayload(header, payload)
        }
    }

    /**
     * 仅序列化、不做语义校验 —— 供测试夹具构造畸形字节（负控），同 module 可见。
     * 生产路径一律走 [encode]（先校验再写）。
     */
    internal fun writeBytes(model: ModuleContainer): ByteArray {
        val headerBytes = buildHeaderBytes(model)
        val payload = writePayload(model)
        val out = Sink()
        out.bytes(ModuleContainerFormat.magicTextFor(model.role).toByteArray(Charsets.US_ASCII))
        out.u16(ModuleContainerFormat.containerVersionFor(model.role))
        out.u16(headerBytes.size)
        out.bytes(headerBytes)
        out.bytes(payload)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // 写侧
    // ------------------------------------------------------------------

    private fun buildHeaderBytes(model: ModuleContainer): ByteArray {
        val text = buildString {
            append("CONTAINER_ROLE ").append(model.role.wire).append('\n')
            append("REQUIRED_FEATURES 0x").append(model.requiredFeatures.toString(16)).append('\n')
            append("ISA_VERSION ").append(model.isaVersion).append('\n')
            append(ModuleContainerFormat.TERMINATOR).append('\n')
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun writePayload(model: ModuleContainer): ByteArray {
        val out = Sink()
        // descriptor（固定序：标量字段逐一）
        out.str(model.descriptor.packageId)
        out.str(model.descriptor.version)
        out.u32(model.descriptor.requires.size)
        model.descriptor.requires.forEach { out.str(it) }
        out.u32(model.descriptor.loadAfter.size)
        model.descriptor.loadAfter.forEach { out.str(it) }
        out.str(model.descriptor.artifactSha256)
        // imports
        out.u32(model.imports.size)
        model.imports.forEach { out.str(it) }
        // callTargets
        out.u32(model.callTargets.size)
        model.callTargets.forEach { out.i32(it) }
        // relocations
        out.u32(model.relocations.size)
        model.relocations.forEach {
            out.i32(it.pc)
            out.u16(it.opcode)
            out.u8(it.slot)
            out.u8(it.category.ordinal)
        }
        // exports
        out.u32(model.exports.size)
        model.exports.forEach {
            out.u8(it.kind)
            out.str(it.name)
            out.str(it.signature)
        }
        // 载荷主体按 role 分流
        when (model.role) {
            ContainerRole.MODULE -> {
                val p = model.pools
                if (p != null) {
                    out.u32(p.longCount)
                    out.u32(p.stringCount)
                    out.u32(p.exprCount)
                    out.u32(p.statementCount)
                    out.u32(p.baseMarker)
                    out.u32(p.codeWords.size)
                    p.codeWords.forEach { out.i32(it) }
                    out.u32(p.poolBlob.size)
                    p.poolBlob.forEach { out.u8(it.toInt() and 0xFF) }
                }
            }
            ContainerRole.LINKED_PROGRAM -> {
                val s = model.linkedSegments
                // 链接器出口自检字段：导入/重定位残留计数，恒 0（蓝图 :66）。
                out.u32(0)
                out.u32(0)
                if (s != null) {
                    out.blob(s.symbolCatalog)
                    out.blob(s.dispatch)
                    out.blob(s.eventRegistry)
                }
            }
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // 读侧
    // ------------------------------------------------------------------

    private class ParsedHeader(val role: ContainerRole, val requiredFeatures: Int, val isaVersion: Int)

    /** 文本头四行固定顺序 fail-closed 解析；缺一/乱序/重复/未知键/非法值/未知位/越界逐条拒。 */
    private fun parseHeader(bytes: ByteArray, expectedRoleByMagic: ContainerRole, version: Int): ParsedHeader {
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            fail("[头部]", "不是合法 UTF-8", e)
        }
        if (text.contains('\r')) {
            fail("[头部]", "出现 CR（只认 LF 换行）")
        }
        val lines = text.split('\n').toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        if (lines.size != 4) {
            fail("[头部字段]", "应有 3 个强制字段加 1 行终止 ${ModuleContainerFormat.TERMINATOR}，实为 ${lines.size} 行")
        }
        val expectedKeys = listOf("CONTAINER_ROLE", "REQUIRED_FEATURES", "ISA_VERSION")
        for (i in 0..2) {
            val kv = lines[i].split(' ', limit = 2)
            if (kv.size != 2) {
                fail("[${expectedKeys[i]}]", "第 ${i + 1} 行不是 key value 形：${lines[i]}")
            }
            if (kv[0] != expectedKeys[i]) {
                fail(
                    "[${expectedKeys[i]}]",
                    "第 ${i + 1} 行字段应为 ${expectedKeys[i]}，实为 ${kv[0]}（缺一/乱序/重复/未知键）",
                )
            }
        }
        if (lines[3] != ModuleContainerFormat.TERMINATOR) {
            fail("[头部]", "缺终止行 ${ModuleContainerFormat.TERMINATOR}，实为 ${lines[3]}")
        }

        // CONTAINER_ROLE：非法值即拒；与 magic 推出的角色一致性（弱校验，仅防伪造，不改反向证明结论）。
        val roleWire = lines[0].split(' ', limit = 2)[1]
        val role = ContainerRole.fromWire(roleWire)
            ?: fail("[CONTAINER_ROLE]", "非法值 '$roleWire'（只认 MODULE / LINKED_PROGRAM）")

        // version 与 role 配套（§3.3 版本不符即拒）。
        val expectedVersion = ModuleContainerFormat.containerVersionFor(role)
        if (version != expectedVersion) {
            fail("[版本]", "containerVersion=$version 与 CONTAINER_ROLE=$role 期望的 $expectedVersion 不符")
        }

        // REQUIRED_FEATURES：未知位 / 缺必需位 fail-closed 闸（新 Reader 自己的闸）。
        val featureText = lines[1].split(' ', limit = 2)[1]
        val features = parseFeatures(featureText)
        validateRequiredFeatures(features)

        // ISA_VERSION：缺失 / 越界。
        val isaText = lines[2].split(' ', limit = 2)[1]
        val isaVersion = isaText.toIntOrNull()
            ?: fail("[ISA_VERSION]", "不是十进制整数：'$isaText'")
        if (isaVersion != ModuleContainerFormat.ISA_VERSION) {
            fail("[ISA_VERSION]", "$isaVersion 越界（只认 ${ModuleContainerFormat.ISA_VERSION}）")
        }
        // magic 与 role 一致性：生产 encode 按 role 写对应 magic；不一致即伪造，拒。
        if (role != expectedRoleByMagic) {
            fail("[CONTAINER_ROLE]", "$role 与 magic 推出的 $expectedRoleByMagic 不一致")
        }
        return ParsedHeader(role, features, isaVersion)
    }

    private fun parseFeatures(text: String): Int {
        if (!text.startsWith("0x") && !text.startsWith("0X")) {
            fail("[REQUIRED_FEATURES]", "必须是 0x 前缀十六进制位集：'$text'")
        }
        val hex = text.substring(2)
        if (hex.isEmpty() || hex.any { it !in "0123456789abcdefABCDEF" }) {
            fail("[REQUIRED_FEATURES]", "非法十六进制：'$text'")
        }
        return hex.toLong(16).let {
            if (it > Int.MAX_VALUE) fail("[REQUIRED_FEATURES]", "位集越 Int 域：'$text'")
            it.toInt()
        }
    }

    private fun readCommon(cur: Cursor): Common {
        val packageId = cur.str()
        val version = cur.str()
        val requires = cur.strList()
        val loadAfter = cur.strList()
        val artifactSha256 = cur.str()
        val descriptor = ContainerDescriptor(packageId, version, requires, loadAfter, artifactSha256)
        val imports = cur.strList()
        if (imports.size > CallSentinels.IMPORT_INDEX_MAX + 1) {
            fail("[imports]", "导入表 ${imports.size} 项越 IMPORT_INDEX_MAX=${CallSentinels.IMPORT_INDEX_MAX}")
        }
        val callTargetCount = cur.count("callTargets", minBytesPerItem = 4)
        val callTargets = List(callTargetCount) { cur.i32() }
        val relocCount = cur.count("relocations", minBytesPerItem = 8)
        val relocations = List(relocCount) {
            val pc = cur.i32()
            val opcode = cur.u16()
            val slot = cur.u8()
            val categoryOrdinal = cur.u8()
            val category = PoolRef.values().getOrElse(categoryOrdinal) {
                fail("[relocations]", "类别序号 $categoryOrdinal 越 PoolRef 域")
            }
            RelocationEntry(pc, opcode, slot, category)
        }
        val exportCount = cur.count("exports")
        val exports = List(exportCount) {
            ExportMeta(cur.u8(), cur.str(), cur.str())
        }
        return Common(descriptor, imports, callTargets, relocations, exports)
    }

    private data class Common(
        val descriptor: ContainerDescriptor,
        val imports: List<String>,
        val callTargets: List<Int>,
        val relocations: List<RelocationEntry>,
        val exports: List<ExportMeta>,
    )

    private fun readModulePayload(header: ParsedHeader, payload: ByteArray): ModuleContainer {
        val cur = Cursor(payload)
        val c = readCommon(cur)
        // 三段闸：callTargets 哨兵/封顶 + relocations 机器表核对。
        validateCallTargets(c.callTargets, c.imports.size)
        validateRelocations(c.relocations)
        val longCount = cur.u32()
        val stringCount = cur.u32()
        val exprCount = cur.u32()
        val statementCount = cur.u32()
        val baseMarker = cur.i32()
        if (baseMarker != 0) {
            fail("[零基线]", "baseMarker=$baseMarker 非 0，Module 态只允许零基线四池")
        }
        val codeWordCount = cur.count("codeWords", minBytesPerItem = 4)
        val codeWords = List(codeWordCount) { cur.i32() }
        val poolBlobLen = cur.count("poolBlob")
        val poolBlob = cur.bytes(poolBlobLen).toList()
        val pools = ZeroBasePools(
            longCount, stringCount, exprCount, statementCount, baseMarker, codeWords, poolBlob,
        )
        return ModuleContainer(
            role = header.role,
            requiredFeatures = header.requiredFeatures,
            isaVersion = header.isaVersion,
            descriptor = c.descriptor,
            imports = c.imports,
            callTargets = c.callTargets,
            relocations = c.relocations,
            exports = c.exports,
            pools = pools,
        )
    }

    private fun readLinkedPayload(header: ParsedHeader, payload: ByteArray): ModuleContainer {
        val cur = Cursor(payload)
        val c = readCommon(cur)
        // 链接器出口自检（蓝图 :66）：导入表空、重定位表空，不许残留负导入下标或 super 哨兵。
        if (c.imports.isNotEmpty() || c.callTargets.isNotEmpty() || c.relocations.isNotEmpty()) {
            fail("[LINKED_PROGRAM]", "残留导入/重定位/哨兵：Linked 态三段前不许有任何导入表或重定位表项")
        }
        val importResidue = cur.u32()
        val relocResidue = cur.u32()
        if (importResidue != 0 || relocResidue != 0) {
            fail("[LINKED_PROGRAM]", "出口自检字段非零（importResidue=$importResidue relocResidue=$relocResidue）")
        }
        val linkedSegments = LinkedSegments(cur.blob(), cur.blob(), cur.blob())
        return ModuleContainer(
            role = header.role,
            requiredFeatures = header.requiredFeatures,
            isaVersion = header.isaVersion,
            descriptor = c.descriptor,
            imports = c.imports,
            callTargets = c.callTargets,
            relocations = c.relocations,
            exports = c.exports,
            linkedSegments = linkedSegments,
        )
    }

    // ------------------------------------------------------------------
    // fail-closed 判据（写侧 encode 与读侧 decode 共用同一份，不另写第二份机器表）
    // ------------------------------------------------------------------

    private fun validateForWrite(model: ModuleContainer) {
        if (model.isaVersion != ModuleContainerFormat.ISA_VERSION) {
            fail("[ISA_VERSION]", "写侧 ${model.isaVersion} 越界")
        }
        validateRequiredFeatures(model.requiredFeatures)
        when (model.role) {
            ContainerRole.MODULE -> {
                val p = model.pools
                if (p == null || model.linkedSegments != null) fail("[MODULE]", "载荷应为零基线四池且无 Linked 三段")
                if (p.baseMarker != 0) fail("[零基线]", "写侧 baseMarker=${p.baseMarker} 非 0")
                validateCallTargets(model.callTargets, model.imports.size)
                validateRelocations(model.relocations)
            }
            ContainerRole.LINKED_PROGRAM -> {
                if (model.linkedSegments == null || model.pools != null) fail("[LINKED_PROGRAM]", "载荷应为三段且无零基线四池")
                if (model.imports.isNotEmpty() || model.callTargets.isNotEmpty() || model.relocations.isNotEmpty()) {
                    fail("[LINKED_PROGRAM]", "写侧残留导入/重定位/哨兵")
                }
            }
        }
    }

    /** REQUIRED_FEATURES fail-closed 闸。反向证明 ② 把本函数体改恒过，对应负控必红。 */
    private fun validateRequiredFeatures(bits: Int) {
        val unknown = bits and RequiredFeatures.KNOWN_MASK.inv()
        if (unknown != 0) {
            fail(
                "[REQUIRED_FEATURES]",
                "未知位 0x${unknown.toString(16)}（已知位 ${RequiredFeatures.names(RequiredFeatures.KNOWN_MASK)}）",
            )
        }
        val missing = RequiredFeatures.REQUIRED_MASK and bits.inv()
        if (missing != 0) {
            fail(
                "[REQUIRED_FEATURES]",
                "缺必需位 0x${missing.toString(16)}（实为 ${RequiredFeatures.names(bits)}）",
            )
        }
    }

    /**
     * 重定位机器闸：唯一真源 [OpcodeRelocation.of]（OpcodeRelocation.kt:224，表按 0..MAX_OPCODE
     * 稠密建满，未登记 opcode 在场即抛）+ [com.eratw.port.core.code.OperandRelocation.at]（:53）。
     * 逐项核对声明类别与机器表；不复制分类表。反向证明 ④ 把本函数体改恒过，机器闸用例必红。
     */
    private fun validateRelocations(relocations: List<RelocationEntry>) {
        for ((i, r) in relocations.withIndex()) {
            val relocation = try {
                OpcodeRelocation.of(r.opcode)
            } catch (e: RuntimeException) {
                fail("[relocations[$i]]", "opcode=${r.opcode} 未在 OpcodeRelocation 机器表登记：${e.message}")
            }
            val expected = try {
                relocation.at(r.slot)
            } catch (e: RuntimeException) {
                fail("[relocations[$i]]", "操作数槽 ${r.slot} 非法：${e.message}")
            }
            if (r.category != expected) {
                fail(
                    "[relocations[$i]]",
                    "声明类别 ${r.category} 与 OpcodeRelocation 机器表" +
                        "（${Opcode.nameOf(r.opcode)} 槽 ${r.slot} = $expected）冲突",
                )
            }
        }
    }

    /**
     * imports 封顶/哨兵闸：复用 [CallSentinels]（ModuleDescriptor.kt:213-249）的既有判据。
     * _CALL.a 的外部导入哨兵解码后 extIdx 必须不越 IMPORT_INDEX_MAX；越界的负下标会让
     * -(extIdx+1) 回绕撞 CALL_SUPER_SENTINEL（ModuleDescriptor.kt:218-224 的立项理由）。
     */
    private fun validateCallTargets(callTargets: List<Int>, importCount: Int) {
        for ((i, a) in callTargets.withIndex()) {
            if (a >= 0) continue // 模块内下标
            if (a == CallSentinels.CALL_SUPER_SENTINEL || a == CallSentinels.TRY_CALL_SUPER_SENTINEL) continue // 合法 super
            val extIdx = CallSentinels.externalIndexOf(a)
            if (extIdx > CallSentinels.IMPORT_INDEX_MAX) {
                fail(
                    "[imports]",
                    "callTargets[$i] _CALL.a=$a 解出 extIdx=$extIdx 越 " +
                        "IMPORT_INDEX_MAX=${CallSentinels.IMPORT_INDEX_MAX}" +
                        "（-(extIdx+1) 回绕撞 CALL_SUPER 哨兵风险）",
                )
            }
            if (extIdx >= importCount) {
                fail("[imports]", "callTargets[$i] 外部引用 extIdx=$extIdx 越出 imports 表($importCount 项)")
            }
        }
    }

    private fun fail(field: String, message: String, cause: Throwable? = null): Nothing =
        throw ModuleContainerException("$field $message", cause)
}

// ======================================================================
// 字节原语（小端，纯 JVM；判界一律 ModuleContainerException，不返回脏值）
// ======================================================================

private class Sink {
    private val out = ByteArrayOutputStream()

    fun u8(v: Int) { out.write(v and 0xFF) }
    fun u16(v: Int) { u8(v); u8(v shr 8) }
    fun u32(v: Int) { u8(v); u8(v shr 8); u8(v shr 16); u8(v shr 24) }
    fun i32(v: Int) = u32(v)
    fun bytes(b: ByteArray) { out.write(b) }
    fun str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        u16(b.size)
        out.write(b)
    }
    fun blob(data: List<Byte>) {
        u32(data.size)
        data.forEach { u8(it.toInt() and 0xFF) }
    }
    fun toByteArray(): ByteArray = out.toByteArray()
}

private class Cursor(private val b: ByteArray) {
    var pos: Int = 0
        private set

    fun remainingCount(): Int = b.size - pos

    private fun need(n: Int, what: String) {
        if (n < 0 || pos.toLong() + n.toLong() > b.size.toLong()) {
            throw ModuleContainerException("[$what] 偏移 $pos 需要 $n 字节，总长 ${b.size}（剩余 ${b.size - pos}）")
        }
    }

    fun u8(): Int {
        need(1, "u8")
        return b[pos++].toInt() and 0xFF
    }
    fun u16(): Int {
        val lo = u8()
        val hi = u8()
        return lo or (hi shl 8)
    }
    fun u32(): Int = i32()
    fun i32(): Int {
        val a = u8()
        val c = u8()
        val d = u8()
        val e = u8()
        return a or (c shl 8) or (d shl 16) or (e shl 24)
    }
    fun bytes(n: Int): ByteArray {
        need(n, "bytes")
        val r = b.copyOfRange(pos, pos + n)
        pos += n
        return r
    }
    fun str(): String {
        val n = u16()
        return String(bytes(n), Charsets.UTF_8)
    }
    fun strList(): List<String> {
        val n = count("strList", minBytesPerItem = 2)
        return List(n) { str() }
    }
    fun blob(): List<Byte> {
        val n = count("blob")
        return bytes(n).toList()
    }
    /** 计数类字段：按最小项宽卡分配上界，畸形计数不会触发大数组分配。 */
    fun count(what: String, minBytesPerItem: Int = 1): Int {
        val v = u32()
        if (v < 0) throw ModuleContainerException("[$what] 计数越界(u32=$v)")
        if (minBytesPerItem > 1) {
            val need = v.toLong() * minBytesPerItem
            if (need > (b.size - pos).toLong()) {
                throw ModuleContainerException("[$what] 计数($v)至少需 $need 字节，剩余 ${b.size - pos}")
            }
        } else if (v > b.size - pos) {
            throw ModuleContainerException("[$what] 计数($v)大于剩余字节 ${b.size - pos}")
        }
        return v
    }
    fun remaining(): ByteArray = bytes(b.size - pos)
}
