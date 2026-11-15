package com.eratw.port.core.data.save

import com.eratw.port.core.fn.SaveFileKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream

/**
 * Emuera 二进制存档编解码（`EraBinaryDataReader1808` / `EraBinaryDataWriter` 的 Kotlin 复刻）。
 *
 * ## 实测结构（`<sample-backup>/sav-20261018-110513/save00.sav`，60151 字节）
 *
 * | 偏移 | 内容 | 实测值 | 证据 |
 * |---|---|---|---|
 * | 0 | UInt64 魔数 | 743174590507926921（GZip 分支） | EraBinaryDataWriter.cs:37-44 |
 * | 8 | UInt32 版本 | 1808 | :45 |
 * | 12 | UInt32 校验表条数 | 0 | :46 |
 * | 16 | GZip 成员 | `1f 8b 08 00 00 00 00 00 00 0a` | 实测（.NET 8 的 OS 字节为 0x0A） |
 *
 * 解压后 payload = 571185 字节：
 *
 * | payload 偏移 | 内容 | 实测值 |
 * |---|---|---|
 * | 0 | FileType | 0 = Normal（EraSaveFileType.cs:3-9） |
 * | 1 | Int64 UniqueCode | 7153 |
 * | 9 | Int64 ScriptVersion | 0 |
 * | 17 | String saveDataText | 7-bit 前缀 `BC 01` = **188 字节**（94 个 UTF-16 码元），文本以 `CH20260326 ` 结尾 |
 * | 207 | Int64 角色数 | 168 |
 * | 215 | 角色 0 块 | `00 0C "ISASSI" 00`、`00 04 "NO" 00`、`01 08 "BASE"` + 100 元素… |
 * | 526683 | 全局变量块 | 540 个条目（DAY/MONEY/ITEM/… 直到 受付水子画像文字列） |
 * | 566136 | EOF (0xFF) | 全局块结束 |
 * | 566137 | EM 块 | `DT_UFUFU_LOG`、`DT_UFUFU_M_LOG` 两个 DataTable |
 * | 571184 | 第二个 EOF | 文件结束（571185 = payload 长度） |
 *
 * 关键坑（已实测）：
 * 1. **字符串长度前缀是「字节数」不是「字符数」**（.NET `BinaryWriter.Write(string)` 的
 *    7-bit 变长前缀），UTF-16LE 下 `188 = 94 字符`。按字符数解析会立刻错位。
 * 2. 字符串数组的 null 与 "" 写出来完全相同（EraBinaryDataWriter.cs:352），读回一律为 null。
 * 3. 全局块里的 `RANDDATA`（625 元素）**确实**出现在 saveXX.sav 里（实测偏移 564xxx）；
 *    spec §2.2 说它只进 global.sav —— 见 save-format.md 的「实测勘误」。
 * 4. **浮点段与整数段不同构**（4.981 的 0x04–0x07，EraBinaryDataWriter.cs:132-170）：
 *    标量是裸的 8 字节小端 IEEE-754，数组是「Int32 长度/维度 + 全密集 8 字节 double」——
 *    既没有 `0xF0/0xE0` 那一族 0 游程标记，**也没有结尾的 `0xFF`**，全零元素照常逐个落盘。
 *    拿整数那套游程编码去写浮点，PC 侧读回来整段错位；反过来按游程解浮点会把数据的
 *    某个字节当标签吃掉。判定依据：写侧 `writer.Write(arr[i])` 逐元素直发（:144-145 /
 *    :154-156 / :166-169），读侧 `ReadDouble()` 定宽取 8 字节（EraBinaryDataReader.cs:283-285）。
 *
 * ## 逐字节幂等
 *
 * [write] 在**内容未被修改**时直接回写原文件字节（[SaveData.sourceFile]），因为 .NET 的
 * DeflateStream 输出无法用 `java.util.zip.Deflater` 逐字节复现（实测 `global.sav`：
 * 同一 payload 的 zlib level 6 输出 508 字节，.NET 为 510 字节）。判断「未修改」用的是
 * [encodePayload] 重新编码的结果与读入时的 payload 做**逐字节比较**——编码器一旦有偏差
 * 就会退回重新压缩，测试随即失败。改动过的存档用 `Deflater` + 与原版相同的 GZip 头
 * （`1f 8b 08 00 00000000 00 0a`）重新压缩，.NET 侧可正常读取。
 *
 * ## 未认领键的原始切片（L15 / L17 / L23 / L28 / ADR-005 例外）
 *
 * `read(bytes, keepKey)` 丢掉的未知条目会以原始字节截进 [SaveData.unclaimedVariables]
 * （顶层）与 [SaveData.unclaimedCharacterVariables]（角色块，按块内 `NO` 分桶；每条另带
 * 块内相位 `SYSTEM`（`Separator` 之前）/ `USER`（之后）），`write` 时分别在**变量段的 `EOF`
 * 之前**与**原块内对应的相位位置**（系统切片落 `Separator` 之前、用户切片落之后）原样回写。
 * 因此「带未知键的档」重写后看起来不再是原字节（顶层切片被挪到段尾），但切片本身逐字节不变、
 * 相位与读入时相同；无未知键的档仍走上面的幂等短路、输出逐字节不变。
 *
 * EM 段（Map / Xml / DT）另有一层：`read(bytes, keepKey, emKeepKey)` 在给了 EM 接受谓词时，
 * 被拒的容器名同样保留「类型字节 + 容器名 + 值」的原始切片（[SaveData.unclaimedEmData]），
 * 写侧在 EM 段的 `EOF` 之前回写；不给谓词的旧签名逐字不变。
 */
object SaveFormat {
    /** 未压缩分支魔数（EraBinaryDataWriter.cs:43）。 */
    const val MAGIC_PLAIN: Long = 727905341820519817L

    /** GZip 分支魔数（EraBinaryDataWriter.cs:39）。 */
    const val MAGIC_GZIP: Long = 743174590507926921L

    /** 唯一支持的版本（EraBinaryDataReader.cs:820-824）。 */
    const val VERSION: Int = 1808

    /** 标签字节（EraBinaryDataWriter.cs:68-81 / EraSaveDataType.cs:3-19）。 */
    private const val TAG_SEPARATOR = 0xFD
    private const val TAG_EOC = 0xFE
    private const val TAG_EOF = 0xFF
    private const val TAG_INT_ARRAY3 = 3
    private const val TAG_STR = 16
    private const val TAG_STR_ARRAY = 17
    private const val TAG_STR_ARRAY2D = 18
    private const val TAG_STR_ARRAY3D = 19
    private const val TAG_MAP = 32
    private const val TAG_XML = 33
    private const val TAG_DT = 34

    /** 浮点四型标签（fork 新增，EraBinaryDataReader.cs:21-34 的 `Float/FloatArray/FloatArray2D/FloatArray3D`）。 */
    private const val TAG_FLOAT = 0x04
    private const val TAG_FLOAT_ARRAY = 0x05
    private const val TAG_FLOAT_ARRAY2D = 0x06
    private const val TAG_FLOAT_ARRAY3D = 0x07

    /** `double` 每元素 8 字节（与 [ELEMENT_BYTES] 同宽，但编码规则完全不同，见类 KDoc 的坑 4）。 */
    private const val DOUBLE_BYTES = 8

    /** GZip 头（实测 .NET 8：MTIME=0、XFL=0、OS=0x0A）。 */
    private val GZIP_HEADER = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0, 0, 0, 0, 0, 0x0a)

    /**
     * 角色块的身份键（L17）：原版 `CharacterData` 的 `NO`（`SystemLayout.charaInt0D` 第 2 项，
     * `VariableCode.NO_VAR`），块内恒为整数标量条目（真档实测块首 `ISASSI/NO`）。
     * 未认领切片的桶键取它的**数值**，不取块位置 —— 角色顺序/数量变化时位置会挂错人。
     */
    private const val CHARA_NO_KEY = "NO"

    // ==================================================================
    // 读
    // ==================================================================

    /**
     * 解析完整存档。
     *
     * @param keepKey 只保留哪些键的**值**（按变量名；大小写敏感与否由调用方决定）。
     *   `null` = 全部保留（旧口径）。给定时，谓词判 false 的条目**只走标签不建数组**
     *   （见 [skipValue]），也不计入 [Cursor.claim] 的分配预算 —— 旧版本存档里已删除的
     *   大数组（4.956 的 `MOE_RUMIA_COM_RECORD`：162 个角色各 10 万项）在新游戏里
     *   本来就被丢弃，按它建数组既白花上百 MiB，又会被预算闸误判成坏档
     *   （2026-10-29 玩家档 save39.sav 实测：全量声明 306.8 MiB > 256 MiB 上限）。
     *   只读装置与逐字节回写（[write]）用 `null`，行为不变。
     *
     * **未认领条目的原始切片**（L15 / L17 / L23 / L28 / ADR-005 例外）：`keepKey` 判 false 的条目
     * 在丢弃值的同时把「类型字节 + 键名 + 值」的原始字节截进 [SaveData.unclaimedVariables]
     * （顶层）或按块内 `NO` 分桶的 [SaveData.unclaimedCharacterVariables]（普通存档的角色块，
     * L17 第二波；每条另记块内相位 `Separator` 前/后，L23），不解析、不重编码，写侧在已知
     * 条目之后、段尾标记之前按原相位原样回写（见 [UnclaimedSaveEntry] / [UnclaimedCharaBlock]）。
     * `keepKey = null` 时不筛键，两个字段恒空（未知键就是普通条目）；`CHARVAR` 角色档不捕获。
     *
     * **EM 段未认领条目**（L28）：见三参重载 [read]（`emKeepKey`）。本二参重载**不**筛 EM 段，
     * 行为与 L28 之前逐字相同（全部解析进 [SaveData.emData]，由 `EmDataPools.restore` 按接受集丢弃）。
     *
     * @throws SaveFormatException 魔数/版本不符、payload 截断、标签非法
     */
    fun read(bytes: ByteArray, keepKey: ((String) -> Boolean)? = null): SaveData =
        decode(bytes, keepKey, emKeepKey = null)

    /**
     * L28 重载：除 [keepKey] 之外再给 **EM 段（`Map/Xml/DT`）接受集谓词** [emKeepKey]。
     *
     * @param emKeepKey 判 true 的容器名照常解析进 [SaveData.emData]；判 false 的条目
     *   **只走标签、不建值对象**，原始编码切片按原序进 [SaveData.unclaimedEmData]。
     *   `null` = 不过滤（与二参重载等价）。只有 `NORMAL` / `GLOBAL` 类别有 EM 段。
     *
     * **为什么是三参重载而不是给上面那个加第三个缺省参数**：Kotlin 的尾随 lambda 永远绑定到
     * **最后一个**形参。加参会把全仓 `read(bytes) { 变量谓词 }` 的谓词悄悄挪到 `emKeepKey`
     * 上（编译照样过），实测立刻打红 6 例（`SaveUnknownKeySkipTest` 3 例、
     * `SaveUnclaimedNoWriteBackTest` 1 例、`RestoreReportUnclaimedTest` 1 例、
     * `SaveUpgradeSemanticsTest` 1 例）；
     * 重载让旧调用点的绑定与语义都不变，也不要求改别人 lane 的文件（如 `CharaFileIO`）。
     *
     * @throws SaveFormatException 同 [read]
     */
    fun read(
        bytes: ByteArray,
        keepKey: ((String) -> Boolean)?,
        emKeepKey: ((String) -> Boolean)?,
    ): SaveData = decode(bytes, keepKey, emKeepKey)

    /** [read] 的唯一实现（两个重载只差「EM 段筛不筛」）。 */
    private fun decode(
        bytes: ByteArray,
        keepKey: ((String) -> Boolean)?,
        emKeepKey: ((String) -> Boolean)?,
    ): SaveData {
        val head = readContainer(bytes)
        val c = Cursor(head.payload)
        val typeCode = c.u8()
        val fileType = SaveFileKind.values().getOrNull(typeCode)
            ?: throw SaveFormatException("非法的存档类别字节 $typeCode（期望 0..3）")
        val uniqueCode = c.i64()
        val scriptVersion = c.i64()
        val text = c.readString()

        val characters: List<List<SaveEntry>>
        val droppedChara = ArrayList<String>()
        // L17（ADR-005 例外第二波）：普通存档的角色块未认领条目按块捕获（随后按块内 `NO` 分桶）。
        // CHARVAR（角色档）与 `keepKey == null`（全量读）不捕获：前者是「追加角色」语义，
        // 后者没有「未认领」概念（未知键就是普通条目）。
        val chara = readCharacters(
            c, fileType, keepKey, droppedChara,
            capture = fileType == SaveFileKind.NORMAL && keepKey != null,
        )
        characters = chara.blocks
        val charaBuckets = chara.buckets
        val charaRejected = chara.rejected

        val variables: List<SaveEntry>
        val droppedTop = ArrayList<String>()
        // L15（ADR-005 例外）：顶层 normal/global 域的未认领条目保留原始编码切片（原序）。
        // char 域（readCharacterBlock）与 EM 段（readEntries 的 dropped=null 调用）刻意不传本表。
        val unclaimedTop = ArrayList<UnclaimedSaveEntry>()
        if (fileType != SaveFileKind.CHARVAR) {
            variables = readEntries(c, keepKey, droppedTop, unclaimedTop)
        } else {
            variables = emptyList()
        }

        val emData: List<SaveEntry>
        // L28（ADR-005 例外第三波）：EM 段的未认领容器名保留原始编码切片（原序）。
        // 惰性构造 —— `emKeepKey == null`（二参重载）时不分配、不链外，行为与改动前逐字相同。
        val emCapture = emKeepKey?.let { EmCapture(it, ArrayList()) }
        if ((fileType == SaveFileKind.NORMAL || fileType == SaveFileKind.GLOBAL) && c.hasMore()) {
            // EM 段由各扩展池消费，不属「变量未声明」面，丢弃的键不进报告。
            emData = if (emCapture == null) {
                readEntries(c, keepKey, null)
            } else {
                readEntries(c, keepKey, null, em = emCapture)
            }
        } else {
            emData = emptyList()
        }

        return SaveData(
            fileType = fileType,
            uniqueCode = uniqueCode,
            scriptVersion = scriptVersion,
            text = text,
            characters = characters,
            variables = variables,
            emData = emData,
            compressed = head.gzip,
            version = head.version,
            droppedVariables = droppedTop,
            droppedCharacterVariables = droppedChara,
            sourceFile = bytes.copyOf(),
            sourcePayload = head.payload,
        ).also {
            it.unclaimedVariables = unclaimedTop
            it.unclaimedCharacterVariables = charaBuckets
            it.unclaimedCharacterDroppedKeys = charaRejected
            if (emCapture != null) it.unclaimedEmData = emCapture.slices
        }
    }

    /**
     * 测试/诊断用：只解出容器正文（GZip 解压后的 payload），不解析条目。
     * 与 [encodePayload] 逐字节比较即可判定「编码器是否与原文件同构」。
     */
    fun sourcePayloadOf(bytes: ByteArray): ByteArray? = try {
        readContainer(bytes).payload
    } catch (_: SaveFormatException) {
        null
    }

    /**
     * 「这份字节是不是**本层能按二进制读的容器**」——原版的判据就是 [CreateReader]
     * 返回非 null：魔数命中（`EraBinaryDataReader.cs:795-798`）**且**版本恰为 1808
     * （`:820-824`）；两者任一不满足即返回 null，调用方随后**按文本再读一次**
     * （`VariableEvaluator.cs:2176-2178` 的 CheckData 回落、`:2697-2705` 的 LoadFrom 回落）。
     *
     * **解压也在 CreateReader 的 try 体内**（GZip 解压 `:810`，整个方法 `:826-828` 的
     * `catch { return null }` 一把吞）：头完好、正文坏掉的档，原版拿到的是 null 而不是异常
     * ⇒ `LOADCHARA` 静默 `RESULT = 0`（`VariableEvaluator.cs:2299-2303`）。所以本函数必须
     * 把「解压失败」判成「不是容器」；只看头 16 字节会把坏档漏判成容器，让 [read] 把
     * [SaveFormatException] 抛穿语句层、整局打死（总账 SAVE-13，2026-10-23 用真档合成复现）。
     */
    fun isBinaryContainer(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        val magic = readU64(bytes, 0)
        if (magic != MAGIC_PLAIN && magic != MAGIC_GZIP) return false
        if (readU32(bytes, 8).toInt() != VERSION) return false
        return try {
            readContainer(bytes)
            true
        } catch (_: SaveFormatException) {
            false
        }
    }

    /**
     * 容器**壳**判定：长度 ≥ 16、魔数命中、容器版本恰为 1808、校验表条数未越界
     * （`EraBinaryDataReader.cs:790-806` 的解压前判定）。**不解压正文**，因此开销是常数级。
     *
     * 与 [isBinaryContainer] 的分工：
     * - 本函数 = 原版 `CreateReader` 在 `gZipStream.CopyTo` 之前的判据，供读档分派
     *   （[com.eratw.port.core.save.SaveCodec.read]）选「二进制还是文本」；
     * - [isBinaryContainer] = 壳 + 解压全文都过，供 `LOADCHARA` 那类「坏壳要静默当不存在」
     *   的路径使用（`VariableEvaluator.cs:2300`）。
     *
     * 读档路径**不能**再用 [isBinaryContainer] 做分派：那等于每次读档先解压一遍判容器、
     * 再解压一遍解析正文，正文大时是纯粹的重复开销。
     */
    fun isContainerShell(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        val magic = readU64(bytes, 0)
        if (magic != MAGIC_PLAIN && magic != MAGIC_GZIP) return false
        if (readU32(bytes, 8).toInt() != VERSION) return false
        return try {
            containerBodyOffset(bytes)
            true
        } catch (_: SaveFormatException) {
            false
        }
    }

    /**
     * 完整头探测：**整份解压**后读头，与原版 `CreateReader` 的
     * `gZipStream.CopyTo`（`EraBinaryDataReader.cs:807-815`）同口径 ——
     * GZip 尾部损坏 / 截断都会判 null。
     *
     * 读档前置校验（`SaveDataProbe.checkBytes` 的缺省路径）与测试用它：原版 `LOADDATA`
     * 先 `CheckData` 再 `LoadFrom`，坏档在**复位变量池之前**就被拦住（`Process.cs:1853-1856`），
     * 这里不能放宽，否则坏档会把玩家当前局的变量清成默认值。
     *
     * **存档列表不要用它**：语料的存档列表按格子逐条调 `CHKDATA`，用本函数等于每次整份解压
     * （2026-10-31 玩家「多创几个档后加载变慢」）；列表走 [peekHeader]。
     *
     * @return null = 不是 Emuera 存档（魔数不符且文本头也解析不出 / 魔数命中但头被截断 /
     *   版本非 1808 / GZip 正文损坏）—— 与原版 `CreateReader` 返回 null 同口径
     *   （`EraBinaryDataReader.cs:796-828`）。
     *   注意「魔数命中但版本非 1808」这一支在原版里会**再按文本读一次**
     *   （`VariableEvaluator.cs:2176-2178`）：那种文件的前两行不可能解析成数字，
     *   文本分支随即抛 FileEE 并落成 `ETC_ERROR`，与本层给 null 后由
     *   [com.eratw.port.core.save.SaveDataProbe] 给的 `ETC_ERROR` 状态码一致，故不重复探测。
     */
    fun peek(bytes: ByteArray): SaveHeader? {
        // 不足 16 字节仍可能是合法文本档（原版 CreateReader 失败后按文本回落，
        // VariableEvaluator.cs:2176-2178）：只有「魔数命中但头被截断」才判 null。
        val magic = if (bytes.size >= 8) readU64(bytes, 0) else -1L
        if (magic != MAGIC_PLAIN && magic != MAGIC_GZIP) {
            return peekText(bytes)
        }
        if (bytes.size < 16) return null
        val version = readU32(bytes, 8).toInt()
        if (version != VERSION) return null
        return try {
            val head = readContainer(bytes)
            parseBinaryHeader(head.payload, 0, gzip = head.gzip, version = version)
        } catch (_: SaveFormatException) {
            null
        }
    }

    /**
     * 头字段**快探测**：只解压到头字段够用为止，不校验 GZip 正文与尾部。
     * 专供存档列表渲染（`CHKDATA` / `CHKCHARADATA`），**不用于读档前置校验**。
     *
     * ## 性能（2026-10-31 玩家「多创几个档后加载变慢」）
     *
     * 语料的存档列表每页渲染 40 格，且每次 `WHILE` 重绘都重调一遍 `CHKDATA`
     * （`隨時操作存檔.ERB`）。PC 原版 `CreateReader` 用 `gZipStream.CopyTo` 把整份正文
     * 解进内存（`EraBinaryDataReader.cs:807-815`），只为了读前面 200 来字节的头；
     * 移植侧此前照搬，于是**已填的档越多、列表越慢**（40 格 × 每份数百 KB~数 MB 解压），
     * 真机上还会叠 GC。本函数解到头字段齐了就停，正文一个字都不碰。
     *
     * 与 [peek] 的差异只在「头完好、GZip 正文尾部损坏 / 截断」的档：本函数仍报出可读的头
     * （列表里看得到描述），真正 `LOADDATA` 时仍由 [peek] 的完整校验在复位变量池**之前**
     * 拒绝（`存档数据已损坏`）。有效存档的读数与状态码逐项不变。
     */
    fun peekHeader(bytes: ByteArray): SaveHeader? {
        val magic = if (bytes.size >= 8) readU64(bytes, 0) else -1L
        if (magic != MAGIC_PLAIN && magic != MAGIC_GZIP) {
            return peekText(bytes)
        }
        if (bytes.size < 16) return null
        val version = readU32(bytes, 8).toInt()
        if (version != VERSION) return null
        val gzip = magic == MAGIC_GZIP
        return try {
            val bodyOff = containerBodyOffset(bytes)
            if (!gzip) {
                // 未压缩容器不复制正文：游标从正文起点直接读头。
                parseBinaryHeader(bytes, bodyOff, gzip = false, version = version)
            } else {
                val (head, overCap) = peekGzipHeader(bytes, bodyOff, version)
                if (!overCap) {
                    head
                } else {
                    // 头前缀被描述文本撑满（超长 SAVEDATA_TEXT）：退回全量解压，口径与旧实现一致。
                    val full = readContainer(bytes)
                    parseBinaryHeader(full.payload, 0, gzip = true, version = version)
                }
            }
        } catch (_: SaveFormatException) {
            null
        }
    }

    /** 头前缀的字节上限：正常存档的描述文本只有数百字节；超出即回落全量解压。 */
    private const val PEEK_PREFIX_BYTES = 16 * 1024

    /** 从二进制正文的 [start] 处读类别 / 存档码 / 脚本版本 / 描述文本；类别非法给 null。 */
    private fun parseBinaryHeader(payload: ByteArray, start: Int, gzip: Boolean, version: Int): SaveHeader? {
        val c = Cursor(payload, start)
        val typeCode = c.u8()
        val fileType = SaveFileKind.values().getOrNull(typeCode) ?: return null
        val uniqueCode = c.i64()
        val scriptVersion = c.i64()
        val text = c.readString()
        return SaveHeader(
            binary = true, gzip = gzip, version = version, fileType = fileType,
            uniqueCode = uniqueCode, scriptVersion = scriptVersion, text = text,
        )
    }

    /**
     * 只解压 GZip 正文到**头字段够用为止**，返回 `(头, 是否撞上限)`。
     *
     * - `(头, false)`：头已完整解析（正常档；不会继续读正文，也就不校验流尾 CRC/ISIZE）；
     * - `(null, false)`：流提前结束 / 类别非法，头判定为 null；
     * - `(null, true)`：解满 [PEEK_PREFIX_BYTES] 仍没解析出完整头（超长描述文本），
     *   调用方回落 [readContainer] 的全量解压。
     *
     * 每读一小段就试解析，而不是固定读满前缀再解析：小档的头通常在第一次 `read` 之后
     * 就齐了，这样也不会为了读头把整个小档解完（那等于又回到旧开销），
     * 更不会碰 GZip 尾部的 CRC 校验。
     */
    private fun peekGzipHeader(bytes: ByteArray, off: Int, version: Int): Pair<SaveHeader?, Boolean> = try {
        GZIPInputStream(ByteArrayInputStream(bytes, off, bytes.size - off)).use { ins ->
            val out = ByteArrayOutputStream(512)
            val buf = ByteArray(512)
            while (out.size() < PEEK_PREFIX_BYTES) {
                try {
                    return@use parseBinaryHeader(out.toByteArray(), 0, gzip = true, version = version) to false
                } catch (_: SaveFormatException) {
                    // 头字段还没读全（空缓冲 / 描述文本被切在段尾），继续解下一段。
                }
                val n = ins.read(buf)
                if (n < 0) return@use null to false
                out.write(buf, 0, n)
            }
            null to true
        }
    } catch (e: Exception) {
        throw SaveFormatException("GZip 头解压失败：${e.message}")
    }

    /**
     * 文本格式头（`EraDataWriter` 前三行：UniqueCode / ScriptVersion / saveDataText）。
     * spec: VariableEvaluator.cs:2409-2414。编码按 BOM 探测，缺省 UTF-8（Config.cs:19）。
     */
    private fun peekText(bytes: ByteArray): SaveHeader? {
        val text = decodeTextFile(bytes) ?: return null
        val lines = text.split('\n')
        if (lines.size < 3) return null
        val code = lines[0].trim().toLongOrNull() ?: return null
        val ver = lines[1].trim().toLongOrNull() ?: return null
        return SaveHeader(
            binary = false, gzip = false, version = 0, fileType = SaveFileKind.NORMAL,
            uniqueCode = code, scriptVersion = ver, text = lines[2].trimEnd('\r'),
        )
    }

    private fun decodeTextFile(bytes: ByteArray): String? = try {
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            else -> {
                val utf8 = String(bytes, Charsets.UTF_8)
                if (utf8.indexOf('\uFFFD') >= 0) {
                    // Config.SaveEncode 可被用户改成 Shift-JIS；JVM 与 Android 都有 Shift_JIS。
                    runCatching { String(bytes, charset("Shift_JIS")) }.getOrDefault(utf8)
                } else {
                    utf8
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    // ==================================================================
    // 写
    // ==================================================================

    /**
     * 序列化。内容未改动时返回原文件字节（见类 KDoc 的「逐字节幂等」）。
     *
     * **容器版本恒写 [VERSION]**：原版 `WriteHeader` 写的是字面量 `1808u`
     * （`EraBinaryDataWriter.cs:45`），与 `gamebase.ScriptVersion`（存档**内容**里的脚本版本，
     * 是另一个字段）无关。移植侧曾经写 `data.version`，于是
     * `SaveData(version = 其它)` 能产出一个自己（和原版 `CreateReader`，
     * `EraBinaryDataReader.cs:820-824`）都读不回来的文件 —— 已按原版收口。
     */
    fun write(data: SaveData): ByteArray {
        val out = ByteArrayOutputStream()
        writeTo(data, out)
        return out.toByteArray()
    }

    /**
     * 流式序列化（OOM-B 波，2026-11-08）：字节直接写进 [out]，不再物化
     * 「gzip 副本 + 容器 BAOS + 终拷贝」三份中间数组 —— 旧 [write] 的峰值四份并存
     * （payload / gzip / 容器 / toByteArray）在堆贴顶时就是压垮存档的最后一根草
     * （`FileSystemOps.saveData` 的求援重试链 KDoc 有账）。
     *
     * **payload 仍物化**：逐字节幂等短路（类 KDoc）要拿它与 `sourcePayload` 全量比对，
     * 这一份是判据本身、省不掉；省的是它之后的三份拷贝。
     *
     * 字节口径与 [write] 逐字相同：同一个 16 字节头 + 同壳 GZip（[GZIP_HEADER] +
     * raw deflate + CRC32 + ISIZE，头字段 MTIME=0/XFL=0/OS=0x0A 与 .NET 8 同壳）；
     * 幂等命中时写 `sourceFile` 原字节。`deflate 数据与 .NET 不保证逐字节相同`的既有
     * 口径不变（见 [gzipTo]）—— 本函数与 [write] 用同一个 `Deflater` 同参数，产物逐字节一致。
     */
    fun writeTo(data: SaveData, out: java.io.OutputStream) {
        val payload = encodePayload(data)
        val src = data.sourceFile
        val srcPayload = data.sourcePayload
        if (src != null && srcPayload != null && payload.contentEquals(srcPayload)) {
            out.write(src)
            return
        }
        writeU64(out, if (data.compressed) MAGIC_GZIP else MAGIC_PLAIN)
        writeU32(out, VERSION.toLong())
        writeU32(out, 0L)
        if (data.compressed) gzipTo(payload, out) else out.write(payload)
    }

    /** 只编码 payload（不含 16 字节头 / GZip 外壳）。测试用，也是幂等判据的比较对象。 */
    fun encodePayload(data: SaveData): ByteArray {
        val baos = ByteArrayOutputStream(1 shl 16)
        writePayload(SaveWriter(Out(baos)), data)
        return baos.toByteArray()
    }

    /**
     * payload 的**唯一**发射器：`SaveData` 回放（[encodePayload] / [writeTo]）与
     * `SaveCapture.emit*` 的流式入口共用同一段字节序列，两条入口不可能分叉。
     *
     * 段序 = `VariableEvaluator.SaveToStreamBinary`（:2591-2606）：头四个字段 → 角色块
     * （每块条目 + [SaveData.unclaimedCharacterVariables] 里**同一 `NO`** 的原始切片 + `EOC`，
     * CHARVAR 末尾再补一个 `EOF`）→ 变量段 → [SaveData.unclaimedVariables]
     * 原始切片 → `EOF` → EM 段 → [SaveData.unclaimedEmData] 原始切片 → `EOF`。
     * 未认领切片的三个插入点（角色块 = L17/L23；变量段的 `EOF` 前 = L15；EM 段的 `EOF` 前 = L28）
     * 都只**追加**原始字节：已知条目的字节与顺序完全不动；三个载体都为空时与改动前逐字节相同。
     * 角色块切片按 [UnclaimedCharaEntry.phase] 放置：SYSTEM 落 `Separator` 之前、USER 落之后。
     */
    private fun writePayload(w: SaveWriter, data: SaveData) {
        w.fileType(data.fileType)
        w.i64(data.uniqueCode)
        w.i64(data.scriptVersion)
        w.str(data.text)

        if (data.fileType == SaveFileKind.NORMAL || data.fileType == SaveFileKind.CHARVAR) {
            w.count(data.characters.size)
            for (block in data.characters) {
                // L17 / L23：同一 `NO` 的未认领切片按相位插回块内，`EOC` 之前。
                writeCharaBlock(w, block, data.unclaimedCharacterVariables)
                w.eoc()
            }
            // 角色存档（CHARVAR）在最后一个角色块后还有 EOF：SaveChara 末尾 WriteEOF()
            // （VariableEvaluator.cs:2270）。LoadChara 只读 num 个角色、不消费它（:2308-2314），
            // 但 PC 产出的 chara 文件确实带这一字节，补上才能 write(read(f))==f。
            if (data.fileType == SaveFileKind.CHARVAR) w.eof()
        }
        if (data.fileType != SaveFileKind.CHARVAR) {
            for (e in data.variables) w.entry(e)
            // L15：顶层未认领条目的原始编码切片，已知变量之后、EOF 之前（本段唯一插入点）。
            for (u in data.unclaimedVariables) w.raw(u.bytes)
            w.eof()
        }
        if (data.fileType == SaveFileKind.NORMAL || data.fileType == SaveFileKind.GLOBAL) {
            for (e in data.emData) w.entry(e)
            // L28（ADR-005 例外第三波）：EM 段未认领容器名的原始编码切片，
            // 已知 EM 条目之后、本段 EOF 之前（EM 段唯一插入点）。
            for (u in data.unclaimedEmData) w.raw(u.bytes)
            w.eof()
        }
    }

    /**
     * 一个角色块的全部条目 + 未认领切片回写点（L17 / L23，ADR-005 例外第二波）。
     *
     * 绑定键 = **该块内 `NO` 条目的数值**（[charaNoOf]）；桶缺失（当前 store 已没有这个
     * 角色 / 该块身份不可判定）就只写已知条目 —— 这里没有「按块位置找桶」这条路。
     *
     * 相位放置（原版 `CharacterData.SaveToStreamBinary`，CharacterData.cs:601-687）：
     * - SYSTEM 切片落在块内 `Separator` **之前**（块里没有 `Separator` 时即块尾，与 L17 相同）；
     * - USER 切片落在 `Separator` **之后**（已知用户条目之后）；块里没有 `Separator` 而存在
     *   USER 切片 ⇒ **补一个 `Separator`** 再写 —— 否则读侧 `LoadFromStreamBinary` 会把它们
     *   当系统项（`CharacterData.cs:702-720`），同名变量将来被重新声明时落错查找分支而被丢。
     *
     * 空表零成本：`unclaimedCharacterVariables` 缺省空 ⇒ 每个块只多一次 `isEmpty()` 判断，
     * 无未知键的档与 L15 逐字节相同。
     */
    private fun writeCharaBlock(
        w: SaveWriter,
        block: List<SaveEntry>,
        buckets: Map<Long, List<UnclaimedCharaEntry>>,
    ) {
        val bucket = if (buckets.isEmpty()) emptyList() else charaNoOf(block)?.let { buckets[it] } ?: emptyList()
        val system = bucket.filter { it.phase == CharaPhase.SYSTEM }
        val user = bucket.filter { it.phase == CharaPhase.USER }
        var systemWritten = false
        var hasSeparator = false
        for (e in block) {
            if (!systemWritten && e is SaveEntry.Separator) {
                for (u in system) w.raw(u.bytes)
                systemWritten = true
            }
            if (e is SaveEntry.Separator) hasSeparator = true
            w.entry(e)
        }
        // 没有 Separator 的块：系统切片落在块尾（= EOC 之前），与 L17 的位置相同。
        if (!systemWritten) for (u in system) w.raw(u.bytes)
        if (user.isEmpty()) return
        // 块里没有 Separator 而存在用户相位切片 ⇒ 补一个，否则会被读成系统相位。
        if (!hasSeparator) w.separator()
        for (u in user) w.raw(u.bytes)
    }

    // ==================================================================
    // payload 读取细节
    // ==================================================================

    /** [read] 的角色段产物：块列表 + L17 分桶结果（未捕获时三个字段都空）。 */
    private class CharaSection(
        val blocks: List<List<SaveEntry>>,
        val buckets: Map<Long, List<UnclaimedCharaEntry>>,
        val rejected: List<String>,
    )

    /**
     * EM 段未认领切片的捕获参数（L28）：接受集谓词 + 原始切片出口（保持读入原序）。
     *
     * 打成一包是为了让 [readEntries] 的形参表停在 detekt `LongParameterList` 阈值以下；
     * 拓扑上它只是「EM 段的两个配套值」，与 [CharaSection] 的产物聚合同一手法。
     *
     * @property keep 容器名是否在当前装载接受集里（`Save* ∪ Global*`）
     * @property slices 被拒条目的原始编码切片出口
     */
    private class EmCapture(
        val keep: (String) -> Boolean,
        val slices: MutableList<UnclaimedSaveEntry>,
    )

    /**
     * 读角色段（`FileType=NORMAL/CHARVAR` 的「角色数 + 每块条目 + EOC」）。
     *
     * @param capture 是否捕获未认领切片的原始编码并按块内 `NO` 分桶（L17）。只有普通存档且给了
     *   `keepKey` 时为 true：CHARVAR 是「追加角色」语义（按 `NO` 回写会串进别人的角色档），
     *   全量读则没有「未认领」概念（未知键就是普通条目）。
     */
    private fun readCharacters(
        c: Cursor,
        fileType: SaveFileKind,
        keepKey: ((String) -> Boolean)?,
        dropped: MutableList<String>,
        capture: Boolean,
    ): CharaSection {
        if (fileType != SaveFileKind.NORMAL && fileType != SaveFileKind.CHARVAR) {
            return CharaSection(emptyList(), emptyMap(), emptyList())
        }
        val n = c.i64()
        if (n < 0 || n > MAX_CHARA) throw SaveFormatException("角色数异常：$n")
        val captured = if (capture) ArrayList<UnclaimedCharaBlock>() else null
        val blocks = List(n.toInt()) { readCharacterBlock(c, keepKey, dropped, captured) }
        val rejected = ArrayList<String>()
        val buckets = if (captured == null) {
            emptyMap()
        } else {
            SaveUnclaimedPassthrough.bucketByNo(captured, rejected)
        }
        return CharaSection(blocks, buckets, rejected)
    }

    private fun readCharacterBlock(
        c: Cursor,
        keepKey: ((String) -> Boolean)?,
        dropped: MutableList<String>?,
        charaBlocks: MutableList<UnclaimedCharaBlock>? = null,
    ): List<SaveEntry> {
        val list = ArrayList<SaveEntry>()
        // L17：本块内未认领条目的原始切片（惰性分配 —— 干净档一个字节都不链外）。
        var slices: MutableList<UnclaimedCharaEntry>? = null
        // L23：块内相位 —— 原版 `CharacterData.LoadFromStreamBinary` 的 `bool userDefineData`
        // （CharacterData.cs:692），`case Separator:` 之后置 true（:718-720）。
        var phase = CharaPhase.SYSTEM
        while (true) {
            if (!c.hasMore()) throw SaveFormatException("角色块在 EOC 前截断（偏移 ${c.pos}）")
            // 类型字节的位置：原始切片从这里开始（与 readEntries 的 L15 捕获同口径）。
            val entryStart = c.pos
            val tag = c.u8()
            when (tag) {
                TAG_EOC, TAG_EOF -> {
                    // 原版对缺失 EOC 的旧档宽容（CharacterData.cs:672-674），照读；两种收尾同口径。
                    finishCharaBlock(charaBlocks, list, slices)
                    return list
                }

                TAG_SEPARATOR -> {
                    list.add(SaveEntry.Separator)
                    phase = CharaPhase.USER
                }

                else -> {
                    val droppedBefore = dropped?.size ?: 0
                    val entry = c.entryWithTag(tag, keepKey, dropped)
                    if (entry != null) {
                        list.add(entry)
                    } else if (charaBlocks != null) {
                        val key = charaSliceKey(dropped, droppedBefore)
                        slices = appendCharaSlice(c, entryStart, key, slices, phase)
                    }
                }
            }
        }
    }

    /** 块收尾：本块有未认领切片才登记（无切片的块不占内存、不参与分桶）。 */
    private fun finishCharaBlock(
        charaBlocks: MutableList<UnclaimedCharaBlock>?,
        entries: List<SaveEntry>,
        slices: List<UnclaimedCharaEntry>?,
    ) {
        val captured = slices ?: return
        if (charaBlocks == null || captured.isEmpty()) return
        charaBlocks.add(UnclaimedCharaBlock(charaNoOf(entries), captured))
    }

    /**
     * 把一个被 `keepKey` 拒绝的条目截进本块的切片桶（L17），并记下当时的块内相位（L23）。
     *
     * `entryWithTag` 只有 keepKey 拒绝这一条 null 出口，[key] 是该出口刚追加进 dropped 的键名
     * （null = 本次实际没有丢弃任何条目）；切片 = 类型字节（[entryStart]）到值末尾（`c.pos`），
     * 不解析、不重编码。
     */
    private fun appendCharaSlice(
        c: Cursor,
        entryStart: Int,
        key: String?,
        existing: MutableList<UnclaimedCharaEntry>?,
        phase: CharaPhase,
    ): MutableList<UnclaimedCharaEntry>? {
        if (key == null) return existing
        val bucket = existing ?: ArrayList<UnclaimedCharaEntry>()
        bucket.add(UnclaimedCharaEntry(key, c.slice(entryStart, c.pos), phase))
        return bucket
    }

    /** 本次 `entryWithTag` 是否刚丢弃了一条（[before] 之后新增的键名；没有则 null）。 */
    private fun charaSliceKey(dropped: MutableList<String>?, before: Int): String? =
        if (dropped != null && dropped.size > before) dropped[before] else null

    /**
     * 角色块的身份键 `NO`（L17）：块内**恰好一条** `NO` 才算得出，其余一律 null（fail-closed）。
     *
     * 判 null 的三种形态：块内没有 `NO`（旧档/被 `keepKey` 拒绝）、不止一条 `NO`（重复条目）、
     * 唯一那条不是整数标量（`NO` 在二进制档里恒为 `EraSaveDataType.Int`，见
     * `CharaFileIO.emitSystemCharaEntry` 的 C_INT0 分支与真档实测「块首 27 条系统条目」）。
     * 调用方拿到 null 就**整块丢弃切片** —— 绝不按块位置兜底：角色顺序/数量变化会把
     * 切片挂到别人身上。
     */
    private fun charaNoOf(block: List<SaveEntry>): Long? {
        val noEntries = block.filter { it.key == CHARA_NO_KEY }
        if (noEntries.size != 1) return null
        return (noEntries[0] as? SaveEntry.IntValue)?.value
    }

    /**
     * 读条目直到 EOF（消费 EOF 本身）。
     *
     * **EOC(254) 与 Separator(253) 在这里都非法**：变量块与 EM 块的读取循环是
     * `VariableData.LoadVariableBinary`（VariableData.cs:4081，经 `LoadFromStreamBinary` :3999-4004
     * 的 `while (LoadVariableBinary(...))` 同时驱动变量段与 EM 段），它的 switch 只有
     * Map/Xml/DT/EOF/Int/Str/六种数组共十二个分支（:4093-4206），253 与 254 都落到
     * `default:` 抛 `FileEE(AbnormalData)`（VariableData.cs:4207-4208）。
     * 4.981 的 fork 在同一个 switch 里又加了四个浮点分支（fork-src VariableData.cs:1486-1554），
     * `default:` 紧随其后（:1555-1556）⇒ 浮点标签合法、253/254 依旧非法，本层口径不变。
     * 两个标签只在角色块里合法（`CharacterData.LoadFromStreamBinary` 的
     * `case Separator:` CharacterData.cs:591、`case EOC: case EOF: return;` :672-674），
     * 写侧也只在那里出现：`WriteSeparator()` 全仓唯一调用点是 CharacterData.cs:548，
     * 变量/EM 段的写侧 `SaveToStreamBinary`（VariableData.cs:3980-3997）只用 `WriteWithKey`，
     * 既不写 253 也不写 254 —— 所以真档不会命中这两条，命中即损坏档。
     * 原版 `LOADDATA` 之后的装载段没有 catch（VariableEvaluator.cs:2687-2707 + :2609-2642），
     * 移植侧同口径抛 [SaveFormatException]，由
     * [com.eratw.port.core.save.FileSystemOps] 落成 `CodeEE`。
     *
     * **读到 payload 末尾仍没见到 EOF 同样是坏档**：原版 `LoadVariableBinary`
     * （VariableData.cs:4081-4208）只在读到 `EraSaveDataType.EOF` 时返回 false 结束循环，
     * 底层 `BinaryReader` 越界会抛 `EndOfStreamException`；不存在「无声结束」这条路。
     * 早期移植版 `if (!c.hasMore()) return list` 会把截断档读成「变量只装了一半」的合法结果，
     * 现与 [readCharacterBlock] 同口径改为抛 [SaveFormatException]。
     *
     * @param unclaimed 顶层变量段的未认领切片出口（L15）；null = 该段不捕获
     * @param em EM 段（`Map/Xml/DT`）的未认领切片出口（L28，见 [EmCapture]）；
     *   只对 EM 段传值，变量段传 null ⇒ EM 标签在那里照旧全部解析
     */
    private fun readEntries(
        c: Cursor,
        keepKey: ((String) -> Boolean)?,
        dropped: MutableList<String>?,
        unclaimed: MutableList<UnclaimedSaveEntry>? = null,
        em: EmCapture? = null,
    ): List<SaveEntry> {
        val list = ArrayList<SaveEntry>()
        while (true) {
            if (!c.hasMore()) {
                throw SaveFormatException("变量块/EM 块在 EOF 前截断（偏移 ${c.pos}）")
            }
            // 类型字节的位置：未认领条目的原始切片从这里开始（L15 / L28）。
            val entryStart = c.pos
            val tag = c.u8()
            if (tag == TAG_EOF) return list
            if (tag == TAG_SEPARATOR || tag == TAG_EOC) {
                throw SaveFormatException(
                    "变量块/EM 块里出现 ${if (tag == TAG_EOC) "EOC(0xFE)" else "Separator(0xFD)"}" +
                        "（偏移 ${c.pos - 1}）—— 原版 VariableData.cs:4207-4208 抛 AbnormalData",
                )
            }
            // L28：EM 段被接受集拒绝的容器名 —— 只走标签、截原始切片，不进下面的解析分支。
            if (captureRejectedEm(c, tag, entryStart, em)) continue
            val droppedBefore = dropped?.size ?: 0
            val entry = c.entryWithTag(tag, keepKey, dropped)
            if (entry != null) {
                list.add(entry)
            } else if (unclaimed != null && dropped != null && dropped.size > droppedBefore) {
                // entryWithTag 只有 keepKey 拒绝这一条 null 出口，键名刚刚追加进 dropped；
                // 切片 = 类型字节（entryStart）到值末尾（c.pos），不解析、不重编码（L15）。
                unclaimed.add(
                    UnclaimedSaveEntry(dropped[droppedBefore], c.slice(entryStart, c.pos)),
                )
            }
        }
    }

    /**
     * L28：EM 段条目若被接受集拒绝，把「类型字节 + 容器名 + 值」截进 [EmCapture.slices]。
     *
     * @return true = 本条已作为未认领切片消费（调用方 `continue`，不再解析值）
     *
     * 与 [entryWithTag] 分开的理由：那道口子的形参表被 detekt 的 `CyclomaticComplexMethod` /
     * `LongMethod` baseline 以**完整签名**冻结，加参会让旧基线失配；这里用 [Cursor.peekString]
     * 只读地取容器名再判，判过之后 `entryWithTag` 照旧从同一位置重读。
     */
    private fun captureRejectedEm(c: Cursor, tag: Int, entryStart: Int, em: EmCapture?): Boolean {
        if (em == null || !isEmTag(tag)) return false
        val key = c.peekString()
        val rejected = !em.keep(key)
        if (rejected) {
            c.readString()
            c.skipValue(tag, key)
            em.slices.add(UnclaimedSaveEntry(key, c.slice(entryStart, c.pos)))
        }
        return rejected
    }

    /** EM 段（Map / Xml / DataTable）的三种标签：`EraSaveDataType` 的 32 / 33 / 34。 */
    private fun isEmTag(tag: Int): Boolean = tag == TAG_MAP || tag == TAG_XML || tag == TAG_DT

    /**
     * 多维数组的元素总数：**逐维**做溢出安全乘法，同时过三道闸 ——
     * 元素数 ≤ [MAX_ARRAY_ELEMENTS]（与 readLength 同口径）、单条目分配字节 ≤ [MAX_ENTRY_BYTES]、
     * 本次读档累计分配 ≤ [MAX_TOTAL_BYTES]（经 [Cursor.claim]）。
     *
     * 原版是 `new long[num, num2]` / `new string[num, num2, num3]`
     * （`EraBinaryDataReader.cs:177-292` / `:294-` 3D，以及字符串数组的同名分支），由 .NET
     * 按维度分配、不做 32 位预乘；移植侧若照抄 `d0 * d1 * d2` 的 Int 乘法，大维度会在溢出后
     * 变成负数 ⇒ `NegativeArraySizeException`，或变成一个偏小的正数 ⇒ 后续 `a[i * d1 + j]`
     * 抛 `ArrayIndexOutOfBoundsException`。两处都不是 [SaveFormatException]。
     *
     * 只挡 `Int.MAX_VALUE` 也不够：60 字节的坏档即可声明 `20000 × 20000`（4 亿元素，乘积没过
     * Int 上界）⇒ `LongArray(4e8)` 要 3.2 GB，读档直接 OOM；`LongArray(2_000_000_000)` 是 16 GB，
     * `readLength` 放行后的单维大数同样会掀翻堆（`OutOfMemoryError` 是 `Error`，读档的 catch 接不住）。
     * 每乘一维之前先判 `total > 上限 / d`，Long 域不会溢出。
     *
     * 不拿 `payload.size` 当元素数上界：二进制数组是 0 游程压缩的（`0xF0` / `0xE0` 一类标记
     * + 变长整数，EraBinaryDataWriter.cs:203-332），**几字节的 run 可以展开成上亿个 0**
     * —— 合法大数组的编码长度与水合长度无关，用 payload 长度卡会误杀真档。
     * 能真正约束内存的是「解压后 payload 上限 + 单条目/累计分配预算」这两道闸。
     */
    private fun Cursor.dimProduct(key: String, what: String, width: Int, vararg dims: Int): Int {
        var total = 1L
        for (d in dims) {
            if (d < 0) throw SaveFormatException("$what $key 的数组维度为负：$d")
            // 先比后乘：`dims` 最多 3 个 Int，朴素相乘会溢出 Long（1e8^3 = 1e24 > Long 上界）
            if (d > 0) {
                if (total > MAX_ARRAY_ELEMENTS.toLong() / d) {
                    throw SaveFormatException(
                        "$what $key 的数组元素数超过上限 $MAX_ARRAY_ELEMENTS" +
                            "（维度 ${dims.joinToString("x")}）",
                    )
                }
                if (total > MAX_ENTRY_BYTES / width / d) {
                    throw SaveFormatException(
                        "$what $key 声明维度 ${dims.joinToString("×")}，元素数超过单条目上限" +
                            "（$MAX_ENTRY_BYTES 字节 ÷ 每元素 $width 字节）",
                    )
                }
            }
            total *= d
        }
        return claim(key, what, total, width)
    }

    /**
     * 零游程跳转后的下标：Long 域相加，再判 `0 <= v < limit`（[allowEnd] 时放宽到 `<= limit`）。
     *
     * 原版读侧对 `0xE0/0xF0/0xF1/0xF2` 直接做 `i += num` / `j += num`
     * （`EraBinaryDataReader.cs:125-137`、`:204-245`、`:320-` 3D），负值或超限跳转由 .NET
     * 多维数组的下标检查兜住；移植侧的一维线性下标 `i * d1 + j` 却可能把越界组合算回数组
     * 内部 ⇒ 静默错写。跳转一律在 Long 域判界，`readVarInt` 的 64 位值也先加再收窄，
     * 不会像 `.toInt()` 那样把超大跳转截成小正数。
     *
     * **跳过类标签严格 `< limit`**：`0xF0/F1/F2` 只在「后面紧跟一个非零值」时才被写出
     * （`EraBinaryDataWriter.cs:242-254` / `:291-309`），落点必然是一个真实的值位置。
     * **行尾/平面尾标签 `0xE0/E1` 允许 `== limit`**：写侧对最后一个非全零行/平面也会写
     * 行尾（`EraBinaryDataWriter.cs:256-266` / `:321-329`），读侧此时 `i == d0`（`j == d1`）
     * 是合法状态，随后应当直接见到 EOF。
     */
    private fun jumpIndex(
        key: String,
        kind: String,
        what: String,
        base: Int,
        delta: Long,
        limit: Int,
        allowEnd: Boolean = false,
    ): Int {
        val v = base.toLong() + delta
        val max = if (allowEnd) limit else limit - 1
        if (v < 0 || v > max) {
            throw SaveFormatException("$kind $key 的${what}跳转到 $v，超出 0..$max")
        }
        return v.toInt()
    }

    /**
     * 读一条变量记录；[keepKey] 判 false 时返回 null（值已按 [skipValue] 走完、不建数组）。
     * 被丢弃的键名追加进 [dropped]（M1-2 的「未声明键」报告；null = 该段不报告）。
     *
     * 键名本身总是读掉：未知变量与已知变量在流里的形状完全相同，只有值要不要留下不同。
     */
    private fun Cursor.entryWithTag(
        tag: Int,
        keepKey: ((String) -> Boolean)? = null,
        dropped: MutableList<String>? = null,
    ): SaveEntry? {
        val key = readString()
        // 扩展段（Map/Xml/DT）的键**不是变量名**：它们是 `SAVEMAP`/`SAVEXML`/`SAVEDATA`
        // 声明的名字，走 ConstantData 的表而不是变量注册表 ⇒ 用「变量名是否已知」筛会误删
        // （SaveEmPoolTest / SaveRoundTripAuditTest 实证）。它们本来也不按存档尺寸建大数组，
        // 不参与那条预算，故一律保留；EM 名字的接受集筛选在 [readEntries] 里做（L28）。
        val variableEntry = tag != TAG_MAP && tag != TAG_XML && tag != TAG_DT
        if (keepKey != null && variableEntry && !keepKey(key)) {
            dropped?.add(key)
            skipValue(tag, key)
            return null
        }
        return when (tag) {
            0 -> SaveEntry.IntValue(key, readVarInt())
            1 -> {
                val n = claim(key, "Int1D", readLength().toLong(), ELEMENT_BYTES)
                val a = LongArray(n)
                var i = 0
                while (true) {
                    val b = u8()
                    if (b == TAG_EOF) break
                    if (b == 0xF0) {
                        i = jumpIndex(key, "Int1D", "下标", i, readVarInt(), n)
                        continue
                    }
                    if (i < 0 || i >= n) throw SaveFormatException("Int1D $key 的元素数超过声明的 $n")
                    a[i++] = intValue(b)
                }
                SaveEntry.IntArray1(key, a)
            }
            2 -> {
                val d0 = readLength(); val d1 = readLength()
                val a = LongArray(dimProduct(key, "Int2D", ELEMENT_BYTES, d0, d1))
                var i = 0; var j = 0
                while (true) {
                    val b = u8()
                    if (b == TAG_EOF) break
                    when (b) {
                        0xF1 -> {
                            i = jumpIndex(key, "Int2D", "行下标", i, readVarInt(), d0)
                            j = 0
                        }

                        0xE0 -> {
                            i = jumpIndex(key, "Int2D", "行下标", i, 1L, d0, allowEnd = true)
                            j = 0
                        }

                        0xF0 -> j = jumpIndex(key, "Int2D", "列下标", j, readVarInt(), d1)
                        else -> {
                            if (i < 0 || i >= d0 || j < 0 || j >= d1) throw SaveFormatException("Int2D $key 越界")
                            a[i * d1 + j] = intValue(b); j++
                        }
                    }
                }
                SaveEntry.IntArray2(key, d0, d1, a)
            }
            3 -> {
                val d0 = readLength(); val d1 = readLength(); val d2 = readLength()
                val a = LongArray(dimProduct(key, "Int3D", ELEMENT_BYTES, d0, d1, d2))
                var i = 0; var j = 0; var k = 0
                while (true) {
                    val b = u8()
                    if (b == TAG_EOF) break
                    when (b) {
                        0xF2 -> {
                            i = jumpIndex(key, "Int3D", "平面下标", i, readVarInt(), d0)
                            j = 0; k = 0
                        }

                        0xE1 -> {
                            i = jumpIndex(key, "Int3D", "平面下标", i, 1L, d0, allowEnd = true)
                            j = 0; k = 0
                        }

                        0xF1 -> {
                            j = jumpIndex(key, "Int3D", "行下标", j, readVarInt(), d1)
                            k = 0
                        }

                        0xE0 -> {
                            j = jumpIndex(key, "Int3D", "行下标", j, 1L, d1, allowEnd = true)
                            k = 0
                        }

                        0xF0 -> k = jumpIndex(key, "Int3D", "列下标", k, readVarInt(), d2)
                        else -> {
                            if (i < 0 || i >= d0 || j < 0 || j >= d1 || k < 0 || k >= d2) {
                                throw SaveFormatException("Int3D $key 越界")
                            }
                            a[(i * d1 + j) * d2 + k] = intValue(b); k++
                        }
                    }
                }
                SaveEntry.IntArray3(key, d0, d1, d2, a)
            }
            // ---- 浮点四型：定宽密集，无游程、无 `0xFF` 终止符（EraBinaryDataWriter.cs:132-170）----
            TAG_FLOAT -> SaveEntry.FloatValue(key, readDouble())
            TAG_FLOAT_ARRAY -> {
                val n = claim(key, "Float1D", readLength().toLong(), DOUBLE_BYTES)
                SaveEntry.FloatArray1(key, DoubleArray(n) { readDouble() })
            }
            TAG_FLOAT_ARRAY2D -> {
                val d0 = readLength(); val d1 = readLength()
                val a = DoubleArray(dimProduct(key, "Float2D", DOUBLE_BYTES, d0, d1)) { readDouble() }
                SaveEntry.FloatArray2(key, d0, d1, a)
            }
            TAG_FLOAT_ARRAY3D -> {
                val d0 = readLength(); val d1 = readLength(); val d2 = readLength()
                val a = DoubleArray(dimProduct(key, "Float3D", DOUBLE_BYTES, d0, d1, d2)) { readDouble() }
                SaveEntry.FloatArray3(key, d0, d1, d2, a)
            }
            16 -> SaveEntry.StrValue(key, readString())
            17 -> {
                val n = claim(key, "Str1D", readLength().toLong(), REF_BYTES)
                val a = arrayOfNulls<String>(n)
                var i = 0
                while (true) {
                    val b = u8()
                    if (b == TAG_EOF) break
                    when (b) {
                        0xF0 -> i = jumpIndex(key, "Str1D", "下标", i, readVarInt(), n)
                        0xD8 -> {
                            if (i < 0 || i >= n) throw SaveFormatException("Str1D $key 的元素数超过声明的 $n")
                            a[i++] = readString()
                        }
                        else -> throw SaveFormatException("Str1D $key 的非法标签 $b")
                    }
                }
                SaveEntry.StrArray1(key, a)
            }
            18 -> {
                val d0 = readLength(); val d1 = readLength()
                val a = arrayOfNulls<String>(dimProduct(key, "Str2D", REF_BYTES, d0, d1))
                var i = 0; var j = 0
                while (true) {
                    val b = u8()
                    if (b == TAG_EOF) break
                    when (b) {
                        0xF1 -> {
                            i = jumpIndex(key, "Str2D", "行下标", i, readVarInt(), d0)
                            j = 0
                        }

                        0xE0 -> {
                            i = jumpIndex(key, "Str2D", "行下标", i, 1L, d0, allowEnd = true)
                            j = 0
                        }

                        0xF0 -> j = jumpIndex(key, "Str2D", "列下标", j, readVarInt(), d1)
                        0xD8 -> {
                            if (i < 0 || i >= d0 || j < 0 || j >= d1) throw SaveFormatException("Str2D $key 越界")
                            a[i * d1 + j] = readString(); j++
                        }
                        else -> throw SaveFormatException("Str2D $key 的非法标签 $b")
                    }
                }
                SaveEntry.StrArray2(key, d0, d1, a)
            }
            19 -> {
                val d0 = readLength(); val d1 = readLength(); val d2 = readLength()
                val a = arrayOfNulls<String>(dimProduct(key, "Str3D", REF_BYTES, d0, d1, d2))
                var i = 0; var j = 0; var k = 0
                while (true) {
                    val b = u8()
                    if (b == TAG_EOF) break
                    when (b) {
                        0xF2 -> {
                            i = jumpIndex(key, "Str3D", "平面下标", i, readVarInt(), d0)
                            j = 0; k = 0
                        }

                        0xE1 -> {
                            i = jumpIndex(key, "Str3D", "平面下标", i, 1L, d0, allowEnd = true)
                            j = 0; k = 0
                        }

                        0xF1 -> {
                            j = jumpIndex(key, "Str3D", "行下标", j, readVarInt(), d1)
                            k = 0
                        }

                        0xE0 -> {
                            j = jumpIndex(key, "Str3D", "行下标", j, 1L, d1, allowEnd = true)
                            k = 0
                        }

                        0xF0 -> k = jumpIndex(key, "Str3D", "列下标", k, readVarInt(), d2)
                        0xD8 -> {
                            if (i < 0 || i >= d0 || j < 0 || j >= d1 || k < 0 || k >= d2) {
                                throw SaveFormatException("Str3D $key 越界")
                            }
                            a[(i * d1 + j) * d2 + k] = readString(); k++
                        }
                        else -> throw SaveFormatException("Str3D $key 的非法标签 $b")
                    }
                }
                SaveEntry.StrArray3(key, d0, d1, d2, a)
            }
            TAG_MAP -> {
                val n = claim(key, "Map", readLength().toLong(), REF_BYTES)
                val m = ArrayList<Pair<String, String>>(n)
                repeat(n) { m.add(readString() to readString()) }
                SaveEntry.MapValue(key, m)
            }
            TAG_XML -> SaveEntry.XmlValue(key, readString())
            TAG_DT -> SaveEntry.DataTableValue(key, readString(), readString())
            else -> throw SaveFormatException("未知的条目类型字节 $tag（变量 $key，偏移 ${pos - 1}）")
        }
    }

    /**
     * 只走标签、不建数组地跳过一条「当前游戏里已不存在」的变量值。
     *
     * 原版读到未声明变量时也把值读完即丢，但它是先按**存档声明的尺寸**建临时数组再扔
     * （`VariableData.cs` 的 `ReadStrArray(null, needInit: true)` 一族）：4.956 时代
     * 每角色 10 万项的 `MOE_RUMIA_COM_RECORD` × 162 个角色，读完就是上百 MiB 的垃圾。
     * 在 512 MiB 机型上这一步足以把读档打成 OOM，而且这些字节还会被 [Cursor.claim]
     * 计入累计分配预算，使整份档在 256 MiB 闸前被误判成坏档（2026-10-29 真机实测）。
     *
     * 标签非法仍抛 [SaveFormatException]：跳过的条目同样是档的一部分，
     * 「键不认识」不能成为放过坏档的理由。
     */
    private fun Cursor.skipValue(tag: Int, key: String) {
        when (tag) {
            0 -> readVarInt()
            1 -> {
                readLength(); skipArray(key, str = false)
            }

            2 -> {
                readLength(); readLength(); skipArray(key, str = false)
            }

            3 -> {
                readLength(); readLength(); readLength(); skipArray(key, str = false)
            }

            TAG_FLOAT -> readDouble()
            TAG_FLOAT_ARRAY -> skipDoubles(key, readLength())
            TAG_FLOAT_ARRAY2D -> {
                val d0 = readLength(); val d1 = readLength(); skipDoubles(key, d0, d1)
            }

            TAG_FLOAT_ARRAY3D -> {
                val d0 = readLength(); val d1 = readLength(); val d2 = readLength()
                skipDoubles(key, d0, d1, d2)
            }

            16 -> readString()
            17 -> {
                readLength(); skipArray(key, str = true)
            }

            18 -> {
                readLength(); readLength(); skipArray(key, str = true)
            }

            19 -> {
                readLength(); readLength(); readLength(); skipArray(key, str = true)
            }

            TAG_MAP -> {
                val n = readLength()
                repeat(n) { readString(); readString() }
            }

            TAG_XML -> readString()
            TAG_DT -> {
                readString(); readString()
            }

            else -> throw SaveFormatException("未知的条目类型字节 $tag（变量 $key，偏移 ${pos - 1}）")
        }
    }

    /**
     * 数组值的纯游标推进：标签集与各 [entryWithTag] 分支逐一对应
     * （`0xF0/0xF1/0xF2` 带变长计数、`0xE0/0xE1` 无载荷、`0xD8` + 字符串、其余是值）。
     * 维度已在调用处由 [readLength] 消费并判界，这里不再需要它们。
     */
    private fun Cursor.skipArray(key: String, str: Boolean) {
        while (true) {
            val b = u8()
            when (b) {
                TAG_EOF -> return
                0xF0, 0xF1, 0xF2 -> readVarInt()
                0xE0, 0xE1 -> Unit
                0xD8 -> if (str) readString() else {
                    throw SaveFormatException("Int 数组 $key 出现字符串标签 0xD8（偏移 ${pos - 1}）")
                }

                else -> if (str) {
                    throw SaveFormatException("Str 数组 $key 的非法标签 $b（偏移 ${pos - 1}）")
                } else {
                    intValue(b)
                }
            }
        }
    }

    // ==================================================================
    // 容器（16 字节头 + GZip）
    // ==================================================================

    private class Container(val gzip: Boolean, val version: Int, val payload: ByteArray)

    private fun readContainer(bytes: ByteArray): Container {
        if (bytes.size < 16) throw SaveFormatException("文件长度 ${bytes.size} < 16")
        val magic = readU64(bytes, 0)
        if (magic != MAGIC_PLAIN && magic != MAGIC_GZIP) {
            throw SaveFormatException("魔数不符：$magic（不是 Emuera 二进制存档）")
        }
        val version = readU32(bytes, 8).toInt()
        if (version != VERSION) throw SaveFormatException("不支持的存档版本 $version（只认 1808）")
        val bodyOff = containerBodyOffset(bytes)
        val payload = if (magic == MAGIC_GZIP) {
            gunzip(bytes, bodyOff)
        } else {
            bytes.copyOfRange(bodyOff, bytes.size)
        }
        return Container(magic == MAGIC_GZIP, version, payload)
    }

    /**
     * 容器头之后的正文起点 = 16 + 校验表条数 × 4（`EraBinaryDataReader.cs:801-806`
     * 读条数后逐格读走，`data` 数组再无消费点）。[readContainer] 与 [peekHeader] 共用，
     * 保证两边的越界口径一致。
     */
    private fun containerBodyOffset(bytes: ByteArray): Int {
        val tableCount = readU32(bytes, 12)
        if (tableCount > 1_000_000L) throw SaveFormatException("校验表条数异常：$tableCount")
        val bodyOff = 16 + tableCount.toInt() * 4
        if (bodyOff > bytes.size) throw SaveFormatException("校验表越界")
        return bodyOff
    }

    /**
     * GZip 解压，**带上限分块读**。
     *
     * 原版 `EraBinaryDataReader.CreateReader` 把 `GZipStream` 交给 `BinaryReader` 一路读
     * （`EraBinaryDataReader.cs:807-815`），没有任何长度闸：几十 KB 的压缩体可以解出数 GB，
     * `OutOfMemoryError` 是 `Error`，读档链的 catch 接不住。移植侧按 [gunzipLimit] 边读边计数，
     * 超限即抛 [SaveFormatException]（坏档 ⇒ 与原版「读档失败」同一条产品路径）。
     * `CHKDATA` / `CHKCHARADATA` 的 [peekHeader] 只解到头字段够用（[peekGzipHeader]），不走这道闸 ——
     * 它本来就不该为读几个头字段把整份正文解出来。
     *
     * @param maxBytes 只给测试注入更小的预算用；生产调用一律用 [gunzipLimit]
     */
    internal fun gunzip(bytes: ByteArray, off: Int, maxBytes: Long = gunzipLimit(bytes.size)): ByteArray = try {
        GZIPInputStream(ByteArrayInputStream(bytes, off, bytes.size - off)).use { ins ->
            // 预分配按「压缩体 ×4」的乐观估计，但夹在 64 B..1 MiB：真档约 7 倍压缩，
            // 而大文件的初始缓冲不该一次吃满（ByteArrayOutputStream 会立刻分配）。
            val hint = ((bytes.size - off).toLong() * 4).coerceIn(64L, 1L shl 20).toInt()
            val out = ByteArrayOutputStream(hint)
            val buf = ByteArray(DEFLATE_BUFFER_BYTES)
            var total = 0L
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    throw SaveFormatException(
                        "GZip 解压后超过上限 $maxBytes 字节（压缩体 ${bytes.size - off} 字节，疑似解压炸弹）",
                    )
                }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    } catch (e: SaveFormatException) {
        throw e
    } catch (e: Exception) {
        throw SaveFormatException("GZip 解压失败：${e.message}")
    }

    /**
     * 解压体积上限：`min(64 MiB, max(1 MiB, 文件字节 × 256))`。
     *
     * 实测正常档的压缩比 ≈ 9.5×（60151 B 解出 571185 B），256× 留出两个数量级余量；
     * 64 MiB 是单份存档正文的堆安全上限（见 [MAX_ARRAY_ELEMENTS] 的同一条理由）。
     */
    private fun gunzipLimit(compressedBytes: Int): Long {
        val scaled = compressedBytes.toLong() * 256L
        return minOf(GZIP_MAX_BYTES, maxOf(GZIP_MIN_BYTES, scaled))
    }

    /** GZip 解压上限的上下界（理由见 [gunzipLimit]）。 */
    private const val GZIP_MIN_BYTES = 1L * 1024 * 1024
    private const val GZIP_MAX_BYTES = 64L * 1024 * 1024

    /**
     * 与 .NET 8 `GZipStream` 同壳的压缩（头 10 字节 + raw deflate + CRC32 + ISIZE）。
     * 头字段实测：MTIME=0 / XFL=0 / OS=0x0A；deflate 数据本身与 .NET 不保证逐字节相同。
     *
     * 流式本体（OOM-B，2026-11-08）：头 / deflate / 尾直写 [out]，不物化压缩副本。
     */
    private fun gzipTo(payload: ByteArray, out: java.io.OutputStream) {
        out.write(GZIP_HEADER)
        val def = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            def.setInput(payload)
            def.finish()
            val buf = ByteArray(DEFLATE_BUFFER_BYTES)
            while (!def.finished()) {
                val n = def.deflate(buf)
                if (n > 0) out.write(buf, 0, n)
            }
        } finally {
            def.end()
        }
        val crc = CRC32().apply { update(payload) }.value
        writeU32(out, crc)
        writeU32(out, payload.size.toLong() and U32_MASK)
    }

    // ==================================================================
    // 低层游标 / 写入器
    // ==================================================================

    private const val MAX_CHARA = 1_000_000

    /**
     * 单条数组的元素上限（也用于 `readLength` 的单维长度 / Map 条数判定）。
     *
     * 取 16,000,000 而不是 `Int.MAX_VALUE`：`LongArray` 一项 8 B（16M 项 = 128 MiB），
     * 字符串数组一项 4~8 B；更大的声明只会把堆打碎，`OutOfMemoryError` 是 `Error`，
     * 读档链的 `catch` 接不住。实测存档正文整份才 571 KB（`SaveFormat` 类 KDoc 的表），
     * 真实数组与 16M 差着两个数量级。原版没有这一层（`EraBinaryDataReader.cs:106-109`
     * 直接 `new long[num]`），属移植侧的堆安全约束。
     */
    private const val MAX_ARRAY_ELEMENTS = 16_000_000

    /**
     * 解压后 payload 上限（256 MiB）。原版 `GZipStream` 由 .NET 按需扩容、没有闸
     * （`EraBinaryDataReader.cs:807-815`）；移植侧不给上限的话，一个几百 KB 的
     * GZip 炸弹就能把进程打穿。这个值取 [MAX_TOTAL_BYTES] 同量级：真实样本解压后
     * 是 571,185 字节（见类 KDoc），留 400 倍余量。
     */
    private const val MAX_PAYLOAD_BYTES: Long = 256L * 1024 * 1024

    /**
     * 单条目数组的分配预算（64 MiB ≈ 838 万个 Long）。只对**声明**的维度生效，
     * 不引用 [MAX_PAYLOAD_BYTES] 作元素数上界（0 游程压缩让两者无关，见 [dimProduct]）。
     * 参照：eratw 的系统数组最大是 TA/TB 的 (100,100,100) = 100 万元素 = 8 MiB
     * （specs 的 variables.yaml 里 `multi_dim_arrays` 一节），此处留 8 倍余量。
     */
    private const val MAX_ENTRY_BYTES: Long = 64L * 1024 * 1024

    /**
     * 一次读档的累计分配预算（256 MiB）。单个条目 64 MiB 的上限挡不住「很多条目各要
     * 几十 MB」：解压上限是 256 MiB，一个条目头只要几字节 ⇒ 同一个小文件能声明出
     * 上千个巨型数组。累计预算把整次解码的分配钉在 [MAX_PAYLOAD_BYTES] 同量级。
     * 参照：6 个实机样本里最大的 save00.sav 解压后 571 KB，解码出的数组约 7 MiB。
     */
    private const val MAX_TOTAL_BYTES: Long = 256L * 1024 * 1024

    /** `LongArray` 每元素 8 字节。 */
    private const val ELEMENT_BYTES = 8

    /**
     * 字符串数组按 8 字节/引用估算（64 位 JVM / ART 的压缩引用是 4 字节，
     * 这里取**宽**的一侧：预算只该偏保守）。
     */
    private const val REF_BYTES = 8

    /**
     * 正文游标。[start] 让未压缩容器跳过容器头与校验表直接读正文，避免为读头再复制一份
     * 整段 payload（[peekHeader] 的常数级开销靠它）。
     */
    private class Cursor(val b: ByteArray, start: Int = 0) {
        var pos = start

        /** 已申报的分配字节数（[claim] 累加）。 */
        private var allocated = 0L

        /**
         * 分配预算：声明 [elements] 个元素、每个 [width] 字节。
         *
         * 原版 `.NET` 直接 `new long[n]`，坏档的大维度由 OOM 兜底（进程级崩溃）；
         * 移植侧要落成可诊断的 [SaveFormatException]（[com.eratw.port.core.save.FileSystemOps]
         * 会转成 `CodeEE`），所以先按字节算账再分配。
         *
         * @return 元素数（已校验在 Int 范围内）
         */
        fun claim(key: String, what: String, elements: Long, width: Int): Int {
            if (elements < 0 || elements > Int.MAX_VALUE) {
                throw SaveFormatException("$what $key 的元素数 $elements 超出可分配上限（偏移 $pos）")
            }
            val bytes = elements * width
            if (bytes > MAX_ENTRY_BYTES) {
                throw SaveFormatException(
                    "$what $key 声明 $elements 个元素 ≈ $bytes 字节，" +
                        "超过单条目上限 $MAX_ENTRY_BYTES 字节（偏移 $pos）",
                )
            }
            allocated += bytes
            if (allocated > MAX_TOTAL_BYTES) {
                throw SaveFormatException(
                    "$what $key 使本次读档的累计分配超过上限 $MAX_TOTAL_BYTES 字节（偏移 $pos）",
                )
            }
            return elements.toInt()
        }

        fun hasMore(): Boolean = pos < b.size

        fun u8(): Int {
            if (pos >= b.size) throw SaveFormatException("payload 截断（偏移 $pos）")
            return b[pos++].toInt() and 0xFF
        }

        fun i16(): Int {
            need(2)
            val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            return v.toShort().toInt()
        }

        fun i32(): Int {
            need(4)
            var v = 0
            for (i in 0 until 4) v = v or ((b[pos + i].toInt() and 0xFF) shl (8 * i))
            pos += 4
            return v
        }

        fun i64(): Long {
            need(8)
            var v = 0L
            for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 8
            return v
        }

        /**
         * 定宽 8 字节小端 IEEE-754（.NET `BinaryReader.ReadDouble`，EraBinaryDataReader.cs:170/:283-285）。
         * 用 [Double.fromBits] 按原始位还原，不做任何十进制/字符串中转 ⇒ NaN 的尾数、
         * `-0.0` 的符号位、次正规数都逐位保留。
         */
        fun readDouble(): Double {
            need(8)
            var v = 0L
            for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 8
            return Double.fromBits(v)
        }

        /** `m_WriteInt` 的对称读法（EraBinaryDataWriter.cs:175-196）。 */
        fun readVarInt(): Long {
            val first = u8()
            return when {
                first <= 207 -> first.toLong()
                first == 208 -> i16().toLong()
                first == 209 -> i32().toLong()
                first == 210 -> i64()
                else -> throw SaveFormatException("非法变长整数标签 $first（偏移 ${pos - 1}）")
            }
        }

        fun intValue(tag: Int): Long = when {
            tag <= 207 -> tag.toLong()
            tag == 208 -> i16().toLong()
            tag == 209 -> i32().toLong()
            tag == 210 -> i64()
            else -> throw SaveFormatException("非法整数值标签 $tag（偏移 ${pos - 1}）")
        }

        /** 7-bit 字节长度 + UTF-16LE 原始码元。 */
        fun readString(): String {
            val byteLen = readS7()
            if (byteLen < 0 || byteLen > b.size - pos) {
                throw SaveFormatException("字符串长度 $byteLen 越界（偏移 $pos，剩余 ${b.size - pos}）")
            }
            if ((byteLen and 1) != 0) throw SaveFormatException("UTF-16 字符串字节数为奇数：$byteLen")
            val chars = CharArray(byteLen / 2)
            for (i in chars.indices) {
                val lo = b[pos + 2 * i].toInt() and 0xFF
                val hi = b[pos + 2 * i + 1].toInt() and 0xFF
                chars[i] = (lo or (hi shl 8)).toChar()
            }
            pos += byteLen
            return String(chars)
        }

        /**
         * 只读地取一个字符串再退回游标（L28：EM 段先判接受集、再决定「解析值」还是「截切片」）。
         *
         * 长度前缀与 UTF-16 码元在 [readString] 里已做校验；退回后由 [entryWithTag] / [skipValue]
         * 从同一位置重读。只用于 EM 段的容器名（每档几条、名字短），不做通用服务。
         */
        fun peekString(): String {
            val saved = pos
            val s = readString()
            pos = saved
            return s
        }

        private fun readS7(): Int {
            var n = 0
            var shift = 0
            while (true) {
                val x = u8()
                n = n or ((x and 0x7F) shl shift)
                if ((x and 0x80) == 0) return n
                shift += 7
                if (shift > 28) throw SaveFormatException("7-bit 长度前缀过长")
            }
        }

        fun readLength(): Int {
            val n = i32()
            if (n < 0 || n > MAX_ARRAY_ELEMENTS) {
                throw SaveFormatException("数组长度异常：$n（偏移 ${pos - 4}，上限 $MAX_ARRAY_ELEMENTS）")
            }
            return n
        }

        /**
         * 密集 double 段的纯跳过：按各维乘积定宽推进，不分配、不逐元素解析。
         *
         * 与 [skipArray] 的区别来自编码本身 —— 浮点数组没有 `0xF0` 游程标记也没有 `0xFF`
         * 终止符（EraBinaryDataWriter.cs:138-170），元素数完全由维度前缀决定，所以可以直接寻址。
         * 维度取自 [readLength]，已在 `0..MAX_ARRAY_ELEMENTS` 内。
         */
        fun skipDoubles(key: String, vararg dims: Int) {
            var total = 1L
            for (d in dims) {
                // 先比后乘：三个 16M 维相乘是 4.1e21，会翻过 Long 上界（9.2e18）
                if (d > 0 && total > Long.MAX_VALUE / d) {
                    throw SaveFormatException(
                        "Float $key 的维度乘积超过可寻址上限（维度 ${dims.joinToString("×")}，偏移 $pos）",
                    )
                }
                total *= d
            }
            val remaining = (b.size - pos).toLong()
            // 定宽段的长度只受正文剩余字节约束：这里给的是**跳过**，不建数组，
            // 所以既不能超剩余字节，也无需过 [MAX_ENTRY_BYTES] 那道分配闸。
            if (total > remaining / DOUBLE_BYTES) {
                throw SaveFormatException(
                    "Float $key 声明 $total 个 double，正文只剩 $remaining 字节（偏移 $pos）",
                )
            }
            pos += (total * DOUBLE_BYTES).toInt()
        }

        private fun need(n: Int) {
            if (pos + n > b.size) throw SaveFormatException("payload 截断（偏移 $pos 需要 $n 字节）")
        }

        /**
         * 取 payload 的 `[from, to)` 原始字节（L15：未认领条目的编码切片）。
         * 只做字节拷贝，不解析、不重编码 —— 写侧原样直写即可逐字节保真。
         */
        fun slice(from: Int, to: Int): ByteArray = b.copyOfRange(from, to)
    }

    internal class Out(private val sink: java.io.OutputStream) {

        fun u8(v: Int) = sink.write(v and 0xFF)

        /**
         * 原样直写一段已编码字节（L15：未认领条目的原始切片）。
         * 调用方交进来的就是 read 截下的字节，这里不解析、不重排。
         */
        fun raw(b: ByteArray) = sink.write(b)

        fun i32(v: Int) {
            for (i in 0 until 4) sink.write((v ushr (8 * i)) and 0xFF)
        }

        fun i64(v: Long) {
            for (i in 0 until 8) sink.write(((v ushr (8 * i)) and 0xFF).toInt())
        }

        fun varInt(v: Long) {
            when {
                v in 0..207 -> u8(v.toInt())
                v in Short.MIN_VALUE..Short.MAX_VALUE -> {
                    u8(208)
                    val s = v.toInt()
                    sink.write(s and 0xFF); sink.write((s ushr 8) and 0xFF)
                }
                v in Int.MIN_VALUE..Int.MAX_VALUE -> {
                    u8(209)
                    i32(v.toInt())
                }
                else -> {
                    u8(210)
                    i64(v)
                }
            }
        }

        /**
         * 定宽 8 字节小端 IEEE-754 —— 与 .NET `BinaryWriter.Write(double)` 同形。
         * 浮点**不共享**整数的变长整数与 0 游程编码：写侧每个元素都是一条
         * `writer.Write(arr[i])`（EraBinaryDataWriter.cs:136/:145/:156/:169），零值也照写。
         * 走 [Double.toRawBits] 而非十进制文本，NaN 尾数与 `-0.0` 的符号位才可能逐位往返。
         */
        fun dbl(v: Double) {
            val bits = v.toRawBits()
            for (i in 0 until 8) sink.write(((bits ushr (8 * i)) and 0xFF).toInt())
        }

        fun str(s: String) {
            val byteLen = s.length * 2
            var n = byteLen
            while (true) {
                val x = n and 0x7F
                n = n ushr 7
                if (n != 0) sink.write(x or 0x80) else { sink.write(x); break }
            }
            for (ch in s) {
                val c = ch.code
                sink.write(c and 0xFF)
                sink.write((c ushr 8) and 0xFF)
            }
        }

        /** 已物化的条目回放（`SaveData` 入口）。字段顺序与访问器版逐字相同。 */
        fun entry(e: SaveEntry) {
            when (e) {
                is SaveEntry.IntValue -> { u8(0); str(e.key); varInt(e.value) }
                is SaveEntry.IntArray1 -> { u8(1); str(e.key); int1(e.values.size) { e.values[it] } }
                is SaveEntry.IntArray2 -> { u8(2); str(e.key); int2(e.dim0, e.dim1) { e.values[it] } }
                is SaveEntry.IntArray3 -> { u8(3); str(e.key); int3(e.dim0, e.dim1, e.dim2) { e.values[it] } }
                is SaveEntry.FloatValue -> { u8(TAG_FLOAT); str(e.key); dbl(e.value) }
                is SaveEntry.FloatArray1 -> {
                    u8(TAG_FLOAT_ARRAY); str(e.key); i32(e.values.size)
                    for (v in e.values) dbl(v)
                }
                is SaveEntry.FloatArray2 -> {
                    u8(TAG_FLOAT_ARRAY2D); str(e.key); i32(e.dim0); i32(e.dim1)
                    for (v in e.values) dbl(v)
                }
                is SaveEntry.FloatArray3 -> {
                    u8(TAG_FLOAT_ARRAY3D); str(e.key); i32(e.dim0); i32(e.dim1); i32(e.dim2)
                    for (v in e.values) dbl(v)
                }
                is SaveEntry.StrValue -> { u8(16); str(e.key); str(e.value) }
                is SaveEntry.StrArray1 -> { u8(17); str(e.key); str1(e.values.size) { e.values[it] } }
                is SaveEntry.StrArray2 -> { u8(18); str(e.key); str2(e.dim0, e.dim1) { e.values[it] } }
                is SaveEntry.StrArray3 -> { u8(19); str(e.key); str3(e.dim0, e.dim1, e.dim2) { e.values[it] } }
                is SaveEntry.MapValue -> {
                    u8(TAG_MAP); str(e.key); i32(e.entries.size)
                    for ((k, v) in e.entries) { str(k); str(v) }
                }
                is SaveEntry.XmlValue -> { u8(TAG_XML); str(e.key); str(e.xml) }
                is SaveEntry.DataTableValue -> { u8(TAG_DT); str(e.key); str(e.schemaXml); str(e.dataXml) }
                SaveEntry.Separator -> u8(TAG_SEPARATOR)
            }
        }

        /**
         * 0 游程压缩的 1D 整数数组（EraBinaryDataWriter.cs:203-223），**访问器版**。
         *
         * 入参是 `(Int) -> Long` 而不是 `LongArray`：调用方交活列的 `get`，本函数边读边写。
         * 这正是原版 `WriteWithKey(key, value.GetArray())`（VariableData.cs:3987）的形态 ——
         * 写侧从不需要一份列副本（`GetArray()` 直接 `return array`，:76/:168/:268）。
         */
        fun int1(size: Int, get: (Int) -> Long) {
            i32(size)
            var run = 0
            for (i in 0 until size) {
                val v = get(i)
                if (v == 0L) { run++; continue }
                if (run > 0) { u8(0xF0); varInt(run.toLong()); run = 0 }
                varInt(v)
            }
            u8(TAG_EOF)
        }

        fun int2(dim0: Int, dim1: Int, get: (Int) -> Long) {
            i32(dim0); i32(dim1)
            var run = 0; var zeroRows = 0
            for (i in 0 until dim0) {
                for (j in 0 until dim1) {
                    val v = get(i * dim1 + j)
                    if (v == 0L) { run++; continue }
                    if (zeroRows > 0) { u8(0xF1); varInt(zeroRows.toLong()); zeroRows = 0 }
                    if (run > 0) { u8(0xF0); varInt(run.toLong()); run = 0 }
                    varInt(v)
                }
                if (run == dim1) zeroRows++ else u8(0xE0)
                run = 0
            }
            u8(TAG_EOF)
        }

        fun int3(dim0: Int, dim1: Int, dim2: Int, get: (Int) -> Long) {
            i32(dim0); i32(dim1); i32(dim2)
            val st = RunLengthState()
            for (i in 0 until dim0) {
                for (j in 0 until dim1) {
                    int3Row(dim2, (i * dim1 + j) * dim2, get, st)
                    if (st.run == dim2) st.zeroRows++ else u8(0xE0)
                    st.run = 0
                }
                if (st.zeroRows == dim1) st.zeroPlanes++ else u8(0xE1)
                st.zeroRows = 0
            }
            u8(TAG_EOF)
        }

        /** [int3] 的一行（3 级嵌套拆到本函数，字节序不变）。 */
        private fun int3Row(dim2: Int, base: Int, get: (Int) -> Long, st: RunLengthState) {
            for (k in 0 until dim2) {
                val v = get(base + k)
                if (v == 0L) { st.run++; continue }
                if (st.zeroPlanes > 0) { u8(0xF2); varInt(st.zeroPlanes.toLong()); st.zeroPlanes = 0 }
                if (st.zeroRows > 0) { u8(0xF1); varInt(st.zeroRows.toLong()); st.zeroRows = 0 }
                if (st.run > 0) { u8(0xF0); varInt(st.run.toLong()); st.run = 0 }
                varInt(v)
            }
        }

        fun str1(size: Int, get: (Int) -> String?) {
            i32(size)
            var run = 0
            for (i in 0 until size) {
                val s = get(i)
                if (s.isNullOrEmpty()) { run++; continue }
                if (run > 0) { u8(0xF0); varInt(run.toLong()); run = 0 }
                u8(0xD8); str(s)
            }
            u8(TAG_EOF)
        }

        fun str2(dim0: Int, dim1: Int, get: (Int) -> String?) {
            i32(dim0); i32(dim1)
            var run = 0; var zeroRows = 0
            for (i in 0 until dim0) {
                for (j in 0 until dim1) {
                    val s = get(i * dim1 + j)
                    if (s.isNullOrEmpty()) { run++; continue }
                    if (zeroRows > 0) { u8(0xF1); varInt(zeroRows.toLong()); zeroRows = 0 }
                    if (run > 0) { u8(0xF0); varInt(run.toLong()); run = 0 }
                    u8(0xD8); str(s)
                }
                if (run == dim1) zeroRows++ else u8(0xE0)
                run = 0
            }
            u8(TAG_EOF)
        }

        fun str3(dim0: Int, dim1: Int, dim2: Int, get: (Int) -> String?) {
            i32(dim0); i32(dim1); i32(dim2)
            val st = RunLengthState()
            for (i in 0 until dim0) {
                for (j in 0 until dim1) {
                    str3Row(dim2, (i * dim1 + j) * dim2, get, st)
                    if (st.run == dim2) st.zeroRows++ else u8(0xE0)
                    st.run = 0
                }
                if (st.zeroRows == dim1) st.zeroPlanes++ else u8(0xE1)
                st.zeroRows = 0
            }
            u8(TAG_EOF)
        }

        /** [str3] 的一行（3 级嵌套拆到本函数，字节序不变）。 */
        private fun str3Row(dim2: Int, base: Int, get: (Int) -> String?, st: RunLengthState) {
            for (k in 0 until dim2) {
                val s = get(base + k)
                if (s.isNullOrEmpty()) { st.run++; continue }
                if (st.zeroPlanes > 0) { u8(0xF2); varInt(st.zeroPlanes.toLong()); st.zeroPlanes = 0 }
                if (st.zeroRows > 0) { u8(0xF1); varInt(st.zeroRows.toLong()); st.zeroRows = 0 }
                if (st.run > 0) { u8(0xF0); varInt(st.run.toLong()); st.run = 0 }
                u8(0xD8); str(s)
            }
        }

        /** 3 维数组的零游程状态（`int3` / `str3` 共用；一次发射内有效）。 */
        private class RunLengthState {
            var run = 0
            var zeroRows = 0
            var zeroPlanes = 0
        }
    }

    /**
     * 存档写侧的**条目发射口**（SAVE-STREAM 波，2026-11-13）：`SaveCapture` / `CharaFileIO`
     * 只认这个面。于是「payload 怎么排字节」只有一份实现（[Out.entry] 与下面的方法逐字同源），
     * 调用方拿不到编码细节，也就造不出第二种排法。
     *
     * 数组条目全部走访问器（`(Int) -> T`）：不要求调用方先把活列物化成
     * `LongArray` / `Array<String?>`。段级标记（角色数 / `EOC` / `EOF`）与头字段也在这里。
     */
    internal class SaveWriter(private val o: Out) {

        fun fileType(kind: SaveFileKind) = o.u8(kind.ordinal)

        fun i64(v: Long) = o.i64(v)

        fun str(s: String) = o.str(s)

        /** 角色数（原版 `WriteInt64(CharacterList.Count)`，VariableEvaluator.cs:2598）。 */
        fun count(n: Int) = o.i64(n.toLong())

        fun eoc() = o.u8(TAG_EOC)

        fun eof() = o.u8(TAG_EOF)

        fun intValue(key: String, value: Long) { o.u8(0); o.str(key); o.varInt(value) }

        fun int1(key: String, size: Int, get: (Int) -> Long) { o.u8(1); o.str(key); o.int1(size, get) }

        fun int2(key: String, dim0: Int, dim1: Int, get: (Int) -> Long) {
            o.u8(2); o.str(key); o.int2(dim0, dim1, get)
        }

        fun int3(key: String, dim0: Int, dim1: Int, dim2: Int, get: (Int) -> Long) {
            o.u8(TAG_INT_ARRAY3); o.str(key); o.int3(dim0, dim1, dim2, get)
        }

        fun floatValue(key: String, value: Double) { o.u8(TAG_FLOAT); o.str(key); o.dbl(value) }

        fun float1(key: String, size: Int, get: (Int) -> Double) {
            o.u8(TAG_FLOAT_ARRAY); o.str(key); o.i32(size)
            for (i in 0 until size) o.dbl(get(i))
        }

        fun float2(key: String, dim0: Int, dim1: Int, get: (Int) -> Double) {
            o.u8(TAG_FLOAT_ARRAY2D); o.str(key); o.i32(dim0); o.i32(dim1)
            for (i in 0 until dim0 * dim1) o.dbl(get(i))
        }

        fun float3(key: String, dim0: Int, dim1: Int, dim2: Int, get: (Int) -> Double) {
            o.u8(TAG_FLOAT_ARRAY3D); o.str(key); o.i32(dim0); o.i32(dim1); o.i32(dim2)
            for (i in 0 until dim0 * dim1 * dim2) o.dbl(get(i))
        }

        /** null 值写空串（EraBinaryDataWriter.cs:334-344）。 */
        fun strValue(key: String, value: String?) { o.u8(TAG_STR); o.str(key); o.str(value ?: "") }

        fun str1(key: String, size: Int, get: (Int) -> String?) { o.u8(TAG_STR_ARRAY); o.str(key); o.str1(size, get) }

        fun str2(key: String, dim0: Int, dim1: Int, get: (Int) -> String?) {
            o.u8(TAG_STR_ARRAY2D); o.str(key); o.str2(dim0, dim1, get)
        }

        fun str3(key: String, dim0: Int, dim1: Int, dim2: Int, get: (Int) -> String?) {
            o.u8(TAG_STR_ARRAY3D); o.str(key); o.str3(dim0, dim1, dim2, get)
        }

        /**
         * MAP 条目：直接遍历**活表**（插入序即落盘序；原版 .NET Dictionary 无删除时同序）。
         * 旧实现先 `m.map { it.key to it.value }` 复制成 `List<Pair>`，又是一笔与表同大的分配。
         */
        fun mapValue(key: String, entries: Map<String, String>) {
            o.u8(TAG_MAP); o.str(key); o.i32(entries.size)
            for ((k, v) in entries) { o.str(k); o.str(v) }
        }

        fun xmlValue(key: String, xml: String) { o.u8(TAG_XML); o.str(key); o.str(xml) }

        fun dataTableValue(key: String, schemaXml: String, dataXml: String) {
            o.u8(TAG_DT); o.str(key); o.str(schemaXml); o.str(dataXml)
        }

        fun separator() = o.u8(TAG_SEPARATOR)

        /** 已物化条目回放（`SaveData` 入口）。 */
        fun entry(e: SaveEntry) = o.entry(e)

        /** 未认领条目的原始编码切片原样直写（L15：已知变量之后、`EOF` 之前）。 */
        fun raw(bytes: ByteArray) = o.raw(bytes)
    }

    /**
     * **流式容器写口**（SAVE-STREAM 波）：16 字节头 → payload 逐条目直写 [out]。
     *
     * 与 [writeTo] 的差别只有一处：payload 不在堆里成型。`writeTo` 的 [encodePayload] 会物化
     * 一整份 `ByteArray`（逐字节幂等判据 `sourcePayload` 全量比对要用它），而 `SAVEDATA`
     * 从变量池现抓的档没有 `sourceFile` / `sourcePayload`，那份物化纯属白花 ——
     * 玩家机上就是它在 88%~93% 水位时撑爆最后一次分配（`SaveCapture` 的池级复制同一笔）。
     *
     * 压缩壳与 [gzipTo] 逐字节同源：同壳头 [GZIP_HEADER]、raw deflate、收尾 CRC32 + ISIZE；
     * [GzipSink] 只把「一次性 setInput 整份 payload」换成「边写边 setInput」，deflate 参数与
     * 输出缓冲宽度不变、且**不中途 flush** ⇒ 输出只取决于输入字节序列。
     *
     * @param body 发射 payload 的内容（头四个字段之后的部分由调用方按段序发）
     */
    internal fun writeStreaming(
        compressed: Boolean,
        out: java.io.OutputStream,
        body: (SaveWriter) -> Unit,
    ) {
        writeU64(out, if (compressed) MAGIC_GZIP else MAGIC_PLAIN)
        writeU32(out, VERSION.toLong())
        writeU32(out, 0L)
        if (compressed) {
            val gz = GzipSink(out)
            try {
                val buffered = java.io.BufferedOutputStream(gz, STREAM_BUFFER_BYTES)
                body(SaveWriter(Out(buffered)))
                buffered.flush()
                gz.finish()
            } finally {
                // 成功时是幂等空操作（finish() 已置 ended）；异常时释放 native deflate 状态
                // 且**不补** CRC/ISIZE。没有这一句，每次「抓取/编码失败」都会漏掉一个
                // Deflater 的 native 缓冲，而失败恰恰是玩家机上会连着发生几次的事
                // （SAVEDATA 连试三次）；用 finally 而不是 catch，异常照原样上抛。
                gz.abort()
            }
        } else {
            val buffered = java.io.BufferedOutputStream(out, STREAM_BUFFER_BYTES)
            body(SaveWriter(Out(buffered)))
            buffered.flush()
        }
    }

    /**
     * 增量 GZip 写口：raw deflate 边进边出，CRC32 与 ISIZE 随写累计。
     *
     * 字节口径必须与 [gzipTo] 一致（同 [GZIP_HEADER]、同 `Deflater(DEFAULT_COMPRESSION, true)`、
     * 同 64 KiB 输出缓冲、不调 flush）：deflate 的输出只取决于输入字节序列，与 `setInput`
     * 的切分无关。`SaveStreamByteIdentityTest` 用「流式 vs 物化」逐字节对拍钉住这件事。
     */
    private class GzipSink(private val out: java.io.OutputStream) : java.io.OutputStream() {

        private val def = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
        private val crc = java.util.zip.CRC32()
        private val buf = ByteArray(DEFLATE_BUFFER_BYTES)
        private val one = ByteArray(1)
        private var size = 0L
        private var ended = false

        init {
            out.write(GZIP_HEADER)
        }

        override fun write(b: Int) {
            one[0] = b.toByte()
            write(one, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            crc.update(b, off, len)
            size += len
            def.setInput(b, off, len)
            drain()
        }

        private fun drain() {
            while (!def.needsInput()) {
                val n = def.deflate(buf)
                if (n > 0) out.write(buf, 0, n)
            }
        }

        /** 正常收尾：写完 deflate 尾巴 + CRC32 + ISIZE，然后释放 native 状态。 */
        fun finish() {
            if (ended) return
            ended = true
            def.finish()
            while (!def.finished()) {
                val n = def.deflate(buf)
                if (n > 0) out.write(buf, 0, n)
            }
            writeU32(out, crc.value)
            writeU32(out, size and U32_MASK)
            def.end()
        }

        /**
         * 异常收尾（**不**写 CRC/ISIZE，产物已作废）：只把 native deflate 状态还给 zlib。
         *
         * 与 [finish] 分开的理由：异常路径绝不能再往流里补一串看起来像合法尾巴的字节 ——
         * 那会让「写了一半的档」看起来能读。这里只做释放，且与 [finish] 一样幂等。
         */
        fun abort() {
            if (ended) return
            ended = true
            def.end()
        }
    }

    /** 流式写的中间缓冲宽度（8 KiB）：够摊薄逐字节 `write`，又不与 payload 同量级。 */
    private const val STREAM_BUFFER_BYTES = 1 shl 13

    /** deflate 输出缓冲（64 KiB）：与 gzipTo 用同一宽度，流式与一次性产物才可比。 */
    private const val DEFLATE_BUFFER_BYTES = 1 shl 16

    /** UInt32 掩码（CRC32 与 ISIZE 都按无符号 32 位落盘）。 */
    private const val U32_MASK = 0xFFFF_FFFFL
    // ==================================================================
    // 定长整数
    // ==================================================================

    private fun readU32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    /** 写 32 位（OOM-B：收 `OutputStream` 而非 `ByteArrayOutputStream`，流式口与旧口共用）。 */
    private fun writeU32(out: java.io.OutputStream, v: Long) {
        for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xFF).toInt())
    }

    /** 写 64 位（同 [writeU32] 的流式口）。 */
    private fun writeU64(out: java.io.OutputStream, v: Long) {
        for (i in 0 until 8) out.write(((v ushr (8 * i)) and 0xFF).toInt())
    }
}

/** 存档头（[SaveFormat.peek] / [SaveFormat.peekHeader] 的结果）。 */
data class SaveHeader(
    val binary: Boolean,
    val gzip: Boolean,
    val version: Int,
    val fileType: SaveFileKind,
    val uniqueCode: Long,
    val scriptVersion: Long,
    val text: String,
)

/** 存档解析/写入失败（不是 Emuera 存档、截断、标签非法等）。 */
class SaveFormatException(message: String) : RuntimeException(message)
