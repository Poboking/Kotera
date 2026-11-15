package com.eratw.port.core.save

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.CRC32

/**
 * .rt2 sidecar 容器编解码 —— v6.1-u ABI-6 冻结契约（era-arch-v61u.md §8/§10）。
 *
 * `saveNN.sav` + `saveNN.sav.rt2` = 一个逻辑存档单元（SaveService 统管配对生命周期）。
 * 本文件只冻结**容器字节协议**（纯 ByteArray 进出，无文件 IO）；双文件 .part + fsync +
 * 定序 move + 崩溃补完、.bak 轮转、删除/复制/列表配对是 M4 SaveService 路次。
 *
 * 字节布局（大端）：
 * ```
 * i32  魔数 "ERT2" = 0x45525432
 * u16  版本 = 1
 * i64  boundUniqueCode      ┐
 * i64  boundScriptVersion   ├ 绑定三元组（读取时三重校验）
 * i32  boundSaveTextHash    ┘
 * i32 + bytes  注册表快照（PackageShortIdRegistry.encode 原样字节）
 * i32 + bytes  虚拟 NO 注册表段（VirtualCharaNoRegistry.encode 原样字节；global.rt2 恒空）
 * i32 + bytes  实体图载荷（EntityKernel 序列化，M4 定内部格式）
 * i32          分片数（≤ 4096）
 *   每片：i32 + UTF-8 packageId，i32 + bytes 载荷（≤ 32 MiB）
 * i32  CRC32（以上全部字节的校验和，尾部）
 * ```
 *
 * **v2（D-1，2026-11-08）**：新增「虚拟 NO 注册表段」。虚拟 NO ↔ EntityId 是每槽身份，
 * 读档必须先于实体图恢复（§16 第 6 点）。ABI-6 未冻结、无已发布存档 ⇒ **v1 直接拒载**，
 * 不做双版本读取面（同 [com.eratw.port.core.entity.EntityPayloadCodec] v1 的口径）。
 *
 * **v2 增补尾段（W1-DSTATE，2026-11-14）**：分片之后新增**决策态段**与**任务态段**
 * （各为 `i32 + bytes` 定长 blob；载荷 = `DecisionStateCodec` / `JobSchedulerStateCodec`
 * 的 VERSION 2 输出，容器层不解释内容）。写入纪律：两段**要么都写、要么都不写**——
 * 任一载荷非空才追加（决策段在前、任务段在后），两段全空 = 与增补前逐字节同形
 * （global.rt2 与既有回归的字节不变性由这条保证）。读取纪律：分片读完后**有剩余字节
 * 才读尾段**，剩余字节不足两段 / 容量越界 / 读完后仍有剩余 = 容器损坏当场拒
 * （[SidecarFormatException]，既有 fail-closed 口径）。**旧档（增补前写入）无尾段 ⇒
 * 两段取空缺省回落，不拒载**——决策态回落「未初始化 seed（宿主熵派生）+ 无动态实例」、
 * 任务态回落「无实例」（CONTRACT-RT2-DSTATE 判据 1）；FORMAT_VERSION 维持 2 不 bump，
 * v1 直接拒载的既有纪律不变。CRC32 覆盖含尾段在内的全部字节（尾部校验和照旧）。
 *
 * **孤儿分片透传**：未激活 Mod 的分片在解码后原样保留、存盘时原样回写——
 * 容器层不解释分片内容，激活与否由 SaveService 判定。
 *
 * **实体数据严禁寄生 .sav 的 emData/MapValue**（审计 19.19 + sidecar 裁定）。
 * .sav 文件结构保持 PC Emuera 兼容，但**完整扩展存档不保证 PC 可继续游戏**
 * （§8 兼容性矩阵，v6.2-c 起「100% 互通」表述作废）：扩展实体/组件/关系只有本引擎认识。
 */

/** 容器损坏 / 版本不符 / 容量越界 / CRC 失败。文案由宿主侧包成三语存档错误。 */
class SidecarFormatException(message: String) : RuntimeException(message)

/**
 * 绑定三元组：.rt2 与 .sav 的同一性凭据，读取时逐字段校验。
 *
 * 第三位 `saveTextHash` 的生产语义 = .sav 容器全字节 CRC32（槽档与 global 档同构）。
 * era-arch §8:221 的字面命名从未定义哈希对象；按绑定目的（兼容性矩阵「PC 往返 ⇒ 检测
 * 配对漂移 → 警告扩展状态滞后」），CRC32-of-bytes 是文本哈希的**严格加强**而非等价替换。
 * 裁定全文与语义钉子见 `FileSystemOps.savBytesHash` KDoc 与 `SidecarPairSaveTest`
 * 「绑定第三位内容敏感 / 存读同源」（TODO(verify) 2026-11-13 闭环）。
 */
data class SidecarBinding(
    val uniqueCode: Long,
    val scriptVersion: Long,
    val saveTextHash: Int,
)

/** 按 Package 分片的载荷（孤儿片原样透传，容器层不解释内容）。 */
class PackageChunk(val packageId: String, val payload: ByteArray)

/**
 * `.rt2` 容器的 W1-DSTATE 状态尾段（决策态 + 任务态，两段同进退；见 [SidecarSaveBundle]
 * 类 KDoc v2 增补节）。做成单参载体是 detekt LongParameterList 的源头修法（构造器保持
 * 六参以内），语义与两个独立 blob 参数完全等价。
 */
class SidecarStateSegments(
    /** 决策态段：`DecisionStateCodec` VERSION 2 输出原样字节；空 = 旧档无段（回落，不拒载）。 */
    val decisionState: ByteArray,
    /** 任务态段：`JobSchedulerStateCodec` VERSION 2 输出原样字节（会话并集快照）；空 = 旧档无段。 */
    val jobState: ByteArray,
) {

    companion object {
        /** 旧档同形：两段全空 = 增补前逐字节形状（encode 都不写，见 SidecarSaveBundle.encode）。 */
        val EMPTY = SidecarStateSegments(ByteArray(0), ByteArray(0))
    }
}

/** 一个 .rt2 的逻辑内容（编解码均为纯函数，无文件 IO）。 */
class SidecarSaveBundle(
    val binding: SidecarBinding,
    val registrySnapshot: ByteArray,
    val entityGraphPayload: ByteArray,
    val chunks: List<PackageChunk>,
    /**
     * 虚拟 NO 注册表段（[com.eratw.port.core.entity.VirtualCharaNoRegistry.encode] 原样字节）。
     * 空 = 无投射实体（base-only 档的常态）；global.rt2 恒空（虚拟 NO 是每槽身份，§7）。
     *
     * 放在构造器**末位**是为保持既有四处位置调用点原样编译；字节布局里它排在
     * 注册表之后、实体图之前（见类 KDoc）。
     */
    val virtualNoSnapshot: ByteArray = ByteArray(0),
    /**
     * W1-DSTATE 状态尾段（判据 1）：决策态段 + 任务态段，字节布局排在分片之后、CRC 之前，
     * 两段**要么都写要么都不写**（见类 KDoc v2 增补节与 [SidecarStateSegments]）。
     * 缺省 [SidecarStateSegments.EMPTY] = 增补前的旧档形状，既有构造点零改动。
     */
    val stateSegments: SidecarStateSegments = SidecarStateSegments.EMPTY,
) {
    /** 决策态段原样字节（观察面；空 = 旧档无段，读取侧回落「未初始化 seed + 无动态实例」）。 */
    val decisionStatePayload: ByteArray get() = stateSegments.decisionState

    /** 任务态段原样字节（观察面；空 = 旧档无段，读取侧回落「无实例」）。 */
    val jobStatePayload: ByteArray get() = stateSegments.jobState
    /** 读取侧绑定校验：三元组全等才允许应用本 sidecar。 */
    fun matchesBinding(other: SidecarBinding): Boolean = binding == other

    /** 编码为容器字节（含 CRC32 尾部）。容量越界当场拒绝。 */
    fun encode(): ByteArray {
        require(registrySnapshot.size <= MAX_REGISTRY_BYTES) {
            "注册表快照超容量: ${registrySnapshot.size}"
        }
        require(virtualNoSnapshot.size <= MAX_VIRTUAL_NO_BYTES) {
            "虚拟 NO 注册表段超容量: ${virtualNoSnapshot.size}"
        }
        require(entityGraphPayload.size <= MAX_GRAPH_BYTES) {
            "实体图载荷超容量: ${entityGraphPayload.size}"
        }
        require(chunks.size <= MAX_CHUNKS) { "分片数超容量: ${chunks.size}" }
        for (chunk in chunks) {
            require(chunk.payload.size <= MAX_CHUNK_BYTES) {
                "分片 [${chunk.packageId}] 载荷超容量: ${chunk.payload.size}"
            }
        }
        require(stateSegments.decisionState.size <= MAX_DECISION_STATE_BYTES) {
            "决策态段超容量: ${stateSegments.decisionState.size}"
        }
        require(stateSegments.jobState.size <= MAX_JOB_STATE_BYTES) {
            "任务态段超容量: ${stateSegments.jobState.size}"
        }
        val writeStateSegments =
            stateSegments.decisionState.isNotEmpty() || stateSegments.jobState.isNotEmpty()
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        out.writeInt(MAGIC)
        out.writeShort(FORMAT_VERSION)
        out.writeLong(binding.uniqueCode)
        out.writeLong(binding.scriptVersion)
        out.writeInt(binding.saveTextHash)
        out.writeInt(registrySnapshot.size)
        out.write(registrySnapshot)
        out.writeInt(virtualNoSnapshot.size)
        out.write(virtualNoSnapshot)
        out.writeInt(entityGraphPayload.size)
        out.write(entityGraphPayload)
        out.writeInt(chunks.size)
        writeChunks(out)
        // v2 增补尾段：要么都写（决策段在前、任务段在后），要么都不写（旧档同形）。
        if (writeStateSegments) {
            writeStateSegments(out)
        }
        out.flush()
        val body = buf.toByteArray()
        val crc = CRC32()
        crc.update(body)
        val withTrailer = ByteArrayOutputStream(body.size + 4)
        withTrailer.write(body)
        DataOutputStream(withTrailer).writeInt(crc.value.toInt())
        return withTrailer.toByteArray()
    }

    /** 分片序列化（encode 的主体拆出；字节序 = 名长 + UTF-8 名 + 载荷长 + 载荷，逐片）。 */
    private fun writeChunks(out: DataOutputStream) {
        for (chunk in chunks) {
            val nameBytes = chunk.packageId.toByteArray(Charsets.UTF_8)
            out.writeInt(nameBytes.size)
            out.write(nameBytes)
            out.writeInt(chunk.payload.size)
            out.write(chunk.payload)
        }
    }

    /** 状态尾段序列化（决策段在前、任务段在后；是否写出由 encode 的「都写或都不写」闸决定）。 */
    private fun writeStateSegments(out: DataOutputStream) {
        out.writeInt(stateSegments.decisionState.size)
        out.write(stateSegments.decisionState)
        out.writeInt(stateSegments.jobState.size)
        out.write(stateSegments.jobState)
    }

    companion object {
        /** 魔数 "ERT2"。 */
        const val MAGIC: Int = 0x45525432

        const val FORMAT_VERSION: Int = 2

        /** 实体图载荷上界 64 MiB。 */
        const val MAX_GRAPH_BYTES: Int = 64 * 1024 * 1024

        /** 注册表快照上界（32768 条 × 每条名长上限的宽松闸，取 8 MiB）。 */
        const val MAX_REGISTRY_BYTES: Int = 8 * 1024 * 1024

        /** 虚拟 NO 注册表段上界（2^20 条 × 12 字节 ≈ 12 MiB；取 16 MiB 与条目闸对齐）。 */
        const val MAX_VIRTUAL_NO_BYTES: Int = 16 * 1024 * 1024

        /** 决策态段上界（与虚拟 NO 注册表段同档取 16 MiB；codec 内另有 2^20 条目闸）。 */
        const val MAX_DECISION_STATE_BYTES: Int = 16 * 1024 * 1024

        /** 任务态段上界（同档取 16 MiB；codec 内另有 2^20 条目闸）。 */
        const val MAX_JOB_STATE_BYTES: Int = 16 * 1024 * 1024

        /** 分片数上界。 */
        const val MAX_CHUNKS: Int = 4096

        /** 单片载荷上界 32 MiB。 */
        const val MAX_CHUNK_BYTES: Int = 32 * 1024 * 1024

        /** 最小容器长度：头 26 + 四段长度字 16 + CRC 4。 */
        private const val MIN_BYTES: Int = 4 + 2 + 8 + 8 + 4 + 4 + 4 + 4 + 4 + 4

        /**
         * 解码并全程校验：长度/魔数/版本/容量闸/CRC32。
         * 任何损坏抛 [SidecarFormatException]——容器级损坏前置拦截，
         * 不进「先复位后解析」的官方语义路径（那是 .sav 变量层，ADR-005 不动）。
         */
        fun decode(bytes: ByteArray): SidecarSaveBundle {
            fun fail(msg: String): Nothing = throw SidecarFormatException(msg)
            if (bytes.size < MIN_BYTES) fail("长度不足: ${bytes.size}")
            val bodyLen = bytes.size - 4
            val expected = DataInputStream(ByteArrayInputStream(bytes, bodyLen, 4)).readInt()
            val crc = CRC32()
            crc.update(bytes, 0, bodyLen)
            if (crc.value.toInt() != expected) fail("CRC32 不符（容器损坏）")

            val input = DataInputStream(ByteArrayInputStream(bytes, 0, bodyLen))
            if (input.readInt() != MAGIC) fail("魔数不符")
            val version = input.readUnsignedShort()
            if (version != FORMAT_VERSION) fail("版本 $version 不支持（本引擎 v$FORMAT_VERSION）")
            val binding = SidecarBinding(input.readLong(), input.readLong(), input.readInt())

            fun readBlob(cap: Int, what: String): ByteArray {
                val len = input.readInt()
                if (len < 0 || len > cap) fail("$what 长度越界: $len")
                if (len > input.available()) fail("$what 长度超出剩余字节: $len")
                val data = ByteArray(len)
                input.readFully(data)
                return data
            }

            val registry = readBlob(MAX_REGISTRY_BYTES, "注册表快照")
            val virtualNo = readBlob(MAX_VIRTUAL_NO_BYTES, "虚拟 NO 注册表段")
            val graph = readBlob(MAX_GRAPH_BYTES, "实体图载荷")
            val chunkCount = input.readInt()
            if (chunkCount < 0 || chunkCount > MAX_CHUNKS) fail("分片数越界: $chunkCount")
            val chunks = ArrayList<PackageChunk>(chunkCount)
            repeat(chunkCount) {
                val nameLen = input.readInt()
                if (nameLen <= 0 || nameLen > input.available()) fail("分片名长越界: $nameLen")
                val nameBytes = ByteArray(nameLen)
                input.readFully(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                if (name.isEmpty()) fail("空分片名")
                chunks.add(PackageChunk(name, readBlob(MAX_CHUNK_BYTES, "分片 [$name]")))
            }
            // v2 增补尾段：分片后有剩余字节才读（旧档无段 = 空缺省回落，不拒载）；
            // 有剩余但读不满两段 / 读完后仍有剩余 = 容器损坏，既有 fail-closed 口径。
            var segments = SidecarStateSegments.EMPTY
            if (input.available() > 0) {
                val decision = readBlob(MAX_DECISION_STATE_BYTES, "决策态段")
                val jobState = readBlob(MAX_JOB_STATE_BYTES, "任务态段")
                if (input.available() != 0) fail("尾部多余字节")
                segments = SidecarStateSegments(decision, jobState)
            }
            return SidecarSaveBundle(binding, registry, graph, chunks, virtualNo, segments)
        }
    }
}