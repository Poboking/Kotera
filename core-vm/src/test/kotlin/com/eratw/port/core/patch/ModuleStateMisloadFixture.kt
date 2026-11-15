package com.eratw.port.core.patch

/**
 * M0-3 Module 态误加载负控夹具（测试专用，确定性、纯 JVM）。
 *
 * 本夹具只构造字节，绝不改被测对象：正控字节直接取 [ModuleContainerFixture] 的
 * moduleBytes / linkedBytes / legacyErbcBytes；畸形变体在正控字节上定点改坏一个字段，
 * 交给新旧两个 Reader 去读，装置只观察它们的既有行为。
 *
 * 与 M2-2 五连测的分工：那份测同一份合法字节的「链路 1 到 5 加两条机制钉」共 7 例；
 * 本份只补 ABI-4 Gate 负控半边里那些未覆盖的畸形变体与多入口一致性面，不复制那 7 例。
 *
 * 语义权威（RT2 自研 ERBM 容器，原版无 Module 容器，无 C# 锚，TODO(verify) 见交付）：
 * era-arch-v61u.md 2 节（:62 头部强制字段 fail-closed、:64 拒载机制、:65 未解析模块永不进 VM）、
 * 10 节 ABI-4 Gate（:295 真实旧 Reader 二进制五连负控）、11 节（:307 硬错误负控）、
 * 12.1 节（:359 缺口归属）、17 节（:603 M0-3 判据）。移植侧实树锚见测试类 KDoc 与交付证据，
 * 旧 Reader 拒载锚照实写移植侧实树锚（非 C# 锚），禁止脑补。
 */
internal object ModuleStateMisloadFixture {

    private val base = ModuleContainerFixture

    /** 合法 MODULE 态字节（ERBM magic、containerVersion 3）。 */
    fun moduleBytes(): ByteArray = base.moduleBytes()

    /** 合法 LINKED 态字节（ERBC magic、containerVersion 3，即新 major v3）。 */
    fun linkedBytes(): ByteArray = base.linkedBytes()

    /** 真实 legacy v2 .erbc 产物（生产 CodeBuilder 加 ErbcWriter.encode 生成，不是手搓 magic）。 */
    fun legacyErbcBytes(): ByteArray = base.legacyErbcBytes()

    // ---------- 字节定点变异（只改一个字段；magic 不变者保持 ERBM） ----------

    /** 截断到 [n] 字节。 */
    fun truncate(bytes: ByteArray, n: Int): ByteArray = bytes.copyOf(n)

    /** 改 magic 的单个字节。 */
    fun withMagicByte(bytes: ByteArray, index: Int, value: Int): ByteArray {
        val out = bytes.copyOf()
        out[index] = value.toByte()
        return out
    }

    /** 改 [6..7] 的 u16 headerLen（不动实际头文本与载荷）。 */
    fun withHeaderLen(bytes: ByteArray, len: Int): ByteArray {
        val out = bytes.copyOf()
        out[6] = (len and 0xFF).toByte()
        out[7] = ((len shr 8) and 0xFF).toByte()
        return out
    }

    /** 重建文本头四行（magic 与 containerVersion 原样保留，headerLen 重算）。 */
    fun withHeaderLines(bytes: ByteArray, lines: List<String>): ByteArray =
        base.withHeaderLines(bytes, lines)

    /**
     * [newReason] = 新 Reader（[ModuleContainerCodec.decode]）拒绝文案应含的子串；
     * [oldReason] = 旧 Reader（ErbcReader / MappedErbc）三入口拒绝文案应含的子串；
     * [oldOffset] = 旧 Reader 拒绝偏移。
     */
    data class MisloadVariant(
        val id: String,
        val bytes: ByteArray,
        val newReason: String,
        val oldReason: String,
        val oldOffset: Int = 0,
    )

    /**
     * 判据 2 畸形变体矩阵，逐变体命名稳定，便于失败信息定位。
     *
     * 所有变体都从合法 MODULE 字节出发：magic 保持 ERBM 的变体，旧 Reader 走 magic 闸
     * （偏移 0）；截断到 7 字节的变体连 .erbc 文件头 10 字节都不足，旧 Reader 走文件头
     * 长度闸（偏移 0，同样在 parseHeader 内、构造 Program 之前）。两类都落在文件头阶段。
     */
    fun moduleVariants(): List<MisloadVariant> {
        val m = moduleBytes()
        val out = mutableListOf<MisloadVariant>()

        // 截断前缀：8 字节前缀都没凑齐 ⇒ 新 Reader 长度闸、旧 Reader 文件头长度闸。
        out.add(MisloadVariant("truncated-prefix-7", truncate(m, 7), "[长度]", "文件不足文件头长度"))
        // 截断文本头：magic 与 version 与 headerLen 完整，头文本不足 ⇒ 新 Reader headerLen 越界闸。
        out.add(MisloadVariant("truncated-header", truncate(m, 12), "[headerLen]", "magic 不符"))

        // magic 大小写与近形字节（都不是 ERBC，也不是合法容器 magic ERBM）。
        out.add(MisloadVariant("magic-lower-erbm", base.withMagic(m, "erbm"), "[magic]", "magic 不符"))
        out.add(MisloadVariant("magic-near-ERBN", base.withMagic(m, "ERBN"), "[magic]", "magic 不符"))
        out.add(MisloadVariant("magic-near-ERBL", base.withMagic(m, "ERBL"), "[magic]", "magic 不符"))
        out.add(MisloadVariant("magic-nul-4th", withMagicByte(m, 3, 0x00), "[magic]", "magic 不符"))

        // 头长度字段越界。
        out.add(MisloadVariant("headerlen-overflow", withHeaderLen(m, 0xFFFF), "[headerLen]", "magic 不符"))

        // CONTAINER_ROLE 与 magic 推出的角色矛盾（ERBM 却声明 LINKED_PROGRAM）。
        out.add(
            MisloadVariant(
                "role-conflict",
                withHeaderLines(m, listOf(base.H_ROLE_LINKED, base.H_FEATURES, base.H_ISA, base.H_END)),
                "不一致", "magic 不符",
            )
        )

        // REQUIRED_FEATURES 未知位 / 缺必需位 / 非 0x 前缀。
        out.add(
            MisloadVariant(
                "features-unknown-bit",
                withHeaderLines(m, listOf(base.H_ROLE_MODULE, "REQUIRED_FEATURES 0x8", base.H_ISA, base.H_END)),
                "未知位", "magic 不符",
            )
        )
        out.add(
            MisloadVariant(
                "features-missing-bit",
                withHeaderLines(m, listOf(base.H_ROLE_MODULE, "REQUIRED_FEATURES 0x3", base.H_ISA, base.H_END)),
                "缺必需位", "magic 不符",
            )
        )
        out.add(
            MisloadVariant(
                "features-no-hex-prefix",
                withHeaderLines(m, listOf(base.H_ROLE_MODULE, "REQUIRED_FEATURES 7", base.H_ISA, base.H_END)),
                "0x 前缀", "magic 不符",
            )
        )

        // ISA_VERSION 非十进制 / 越界。
        out.add(
            MisloadVariant(
                "isa-nondecimal",
                withHeaderLines(m, listOf(base.H_ROLE_MODULE, base.H_FEATURES, "ISA_VERSION 0x3", base.H_END)),
                "不是十进制整数", "magic 不符",
            )
        )
        out.add(
            MisloadVariant(
                "isa-out-of-range",
                withHeaderLines(m, listOf(base.H_ROLE_MODULE, base.H_FEATURES, "ISA_VERSION 2", base.H_END)),
                "越界", "magic 不符",
            )
        )

        // 缺 --- 终止行 / 终止行写成别的值。
        out.add(
            MisloadVariant(
                "no-terminator",
                withHeaderLines(m, listOf(base.H_ROLE_MODULE, base.H_FEATURES, base.H_ISA)),
                "终止", "magic 不符",
            )
        )
        out.add(
            MisloadVariant(
                "bad-terminator",
                withHeaderLines(m, listOf(base.H_ROLE_MODULE, base.H_FEATURES, base.H_ISA, "END")),
                "缺终止行", "magic 不符",
            )
        )

        return out
    }
}
