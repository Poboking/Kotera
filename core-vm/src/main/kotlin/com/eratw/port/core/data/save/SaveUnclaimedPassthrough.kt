package com.eratw.port.core.data.save

import com.eratw.port.core.vars.VariableStore

/**
 * 一条「当前游戏没声明」的**顶层**存档变量条目，保留它的**原始编码切片**。
 *
 * ## 为什么是原始切片而不是解析后的值
 *
 * [bytes] = 类型字节 + 键名 + 值，直接从读入的 payload 上截下来（[SaveFormat.read] 的
 * `entryWithTag` 在 `keepKey` 判 false 的分支里截取），落盘时原样直写
 * （[SaveFormat.SaveWriter.raw]）。**不解析、不重编码** —— 编码器一旦参与，
 * 「未知大数组只走标签、不建数组」的根修（`SaveUnknownKeySkipTest` / 2026-10-29 玩家档
 * `save39.sav` 的 306.8 MiB 声明量，见 `SaveFormat.read` 的 `keepKey` KDoc）就会被绕回去：
 * 未知键的 0 游程编码会被展开成整份数组再写回，读档预算与堆峰值都会退到修前。
 *
 * @property key 变量名（读侧已经读出的键；只用于写回时「此刻是否已声明」的闸，切片本身不重编码）
 * @property bytes 原始编码切片：从类型字节起到该条目值的最后一个字节（含）
 */
class UnclaimedSaveEntry(
    val key: String,
    val bytes: ByteArray,
)

/**
 * 角色块内的**相位**：条目位于块内 `Separator` 之前还是之后（L23）。
 *
 * 原版写侧 `CharacterData.SaveToStreamBinary` 先写系统项，若有用户 `#DIM CHARADATA`
 * 变量才 `writer.WriteSeparator()`（`CharacterData.cs:666-668`）再写用户项；读侧
 * `LoadFromStreamBinary` 用 `bool userDefineData` 记相位（`CharacterData.cs:692`），
 * `case EraSaveDataType.Separator:` 置位后（`:718-720`）只认
 * `UserDefinedCharaVariableToken`（`:702-707`）。⇒ 同一个键在哪个相位，决定它下次被
 * **重新声明**时落到系统查找分支还是用户查找分支；相位错 = 文件里有键、运行期读不到。
 */
enum class CharaPhase {
    /** `Separator` 之前：`IsSavedata && IsCharacterData && !IsGlobal` 的系统项。 */
    SYSTEM,

    /** `Separator` 之后：用户 `#DIM CHARADATA` 项。 */
    USER,
}

/**
 * 一个**角色块**里被 `keepKey` 拒绝的条目的原始编码切片（L17 / L23，ADR-005 例外第二波）。
 *
 * 与顶层切片（[UnclaimedSaveEntry]）同形：`bytes` = 类型字节 + 键名 + 值，从读入 payload
 * 上直接截取，不解析、不重编码；落盘时由 `SaveFormat.writePayload` /
 * `com.eratw.port.core.save.SaveCapture.emitNormal` 在**同一 `NO` 的角色块**里按 [phase]
 * 放回：SYSTEM 落 `Separator` 之前、USER 落 `Separator` 之后（块里没有 `Separator` 而存在
 * USER 切片时补一个，否则切片会被读成系统相位）。
 *
 * @property key 变量名（只用于写回时「此刻是否已声明」的闸，切片本身不重编码）
 * @property bytes 原始编码切片：类型字节起到该条目值的最后一个字节（含）
 * @property phase 读入时该条目在块内 `Separator` 的哪一侧（见 [CharaPhase]）
 */
class UnclaimedCharaEntry(
    val key: String,
    val bytes: ByteArray,
    val phase: CharaPhase,
)

/**
 * 一个**角色块**的未认领切片集合（L17 / L23）。
 *
 * @property no 该块内 `NO` 条目的数值（原版角色块的身份项）；null = 身份不可判定
 *   （`NO` 缺失 / 块内重复 / 非整数标量）。null 的块由 [SaveUnclaimedPassthrough.bucketByNo]
 *   整块丢弃并留痕 —— 绝不允许按块位置（pos）兜底：角色顺序/数量变化会把切片挂到别人身上。
 * @property entries 该块内未认领条目的原始切片，保持读入原序（相位由各条自带的 [UnclaimedCharaEntry.phase] 记）
 */
class UnclaimedCharaBlock(
    val no: Long?,
    val entries: List<UnclaimedCharaEntry>,
)

/**
 * 「读档 → 存档」之间携带未认领切片的槽（`store.aux`，Iron 律：状态不挂进程级单例）。
 *
 * ## 域
 *
 * 五个域：
 * - `normal`（`saveNN.sav` 顶层条目）—— L15；
 * - `global`（`global.sav` / `var_*.dat` 的顶层）—— L15；
 * - `char`（普通存档的角色块内条目，按该块 `NO` 分桶）—— L17 第二波；每条条目另带
 *   块内相位 [CharaPhase]（`Separator` 之前 = 系统项 / 之后 = 用户 `#DIM CHARADATA` 项）—— L23。
 * - `em normal` / `em global`（EM 段的 Map / Xml / DT 容器条目）—— L28 第三波；normal 与
 *   global 分槽，条目形状复用 [UnclaimedSaveEntry]（类型字节 + 容器名 + 值）。
 *
 * `chara_*.dat`（CHARVAR 文件）**不在本槽**：那处仍按原版「读完即丢」
 * （`VariableData.cs:4081-4160` 的丢弃分支）。CHARVAR 的文件类别是「角色档」而不是
 * 普通存档，角色块在那里是**追加**语义（`LoadChara` 不清空角色表），按 `NO` 回写会
 * 把上次读入的切片混进别人的角色档，故读侧只在 `NORMAL` 类别下捕获。
 *
 * ## 生命周期与失效闸
 *
 * - 设置点只有 [com.eratw.port.core.save.FileSystemOps] 的装载成功路径（`LOADDATA` / `LOADGLOBAL`）；
 *   每次成功装载都整体替换该域的切片表（空表也替换），所以重复读档不会累积。
 * - **normal 域的失效闸**：[normalSlices] 只在 `store.lastLoadNo` 仍等于装载时的存档号时才交出切片。
 *   `ResetData`（`VariableEvaluator.cs:1336-1346`）经 `SetDefaultValue`
 *   （`VariableData.cs:3511-3512`）把 `LastLoadNo` 复位成 `-1` ⇒ 回标题/新开一局后，
 *   上一份档的未认领键不会再被写进新局的存档。[emSlices] 的 normal 域共用这道闸 —— EM 段的
 *   未知容器同样是「上一份档的内容」，不接闸就会把旧档的钓鱼实例表带进新局（L28）。
 * - **global 域不设这道闸**：`GLOBAL` 值按原版设计跨局存活（`ResetData` 不调
 *   `SetDefaultGlobalValue`，后者只由 `RESETGLOBAL` 触发，`VariableData.cs:3347-3363`），
 *   故 global.sav 的未认领键随全局状态一起跨局保留；`RESETGLOBAL` 之后的清理
 *   属第二波接线（见交付报告 TODO）。[emSlices] 的 global 域同源。
 *
 * ## 写回闸
 *
 * [normalSlices] / [globalSlices] / [charaSlices] 会再滤掉**此刻已经声明**的键：读档时未知、后来被
 * 运行期声明（mod / ERH 补装）的变量，写侧 [com.eratw.port.core.save.SaveCapture.emitVariables]
 * 或 `CharaFileIO.emitCharacter` 已经按活值写过一条；不滤就会在档里出现两条同名条目
 * （原版按名索引，第二条会覆盖第一条）。
 *
 * EM 域的「此刻已声明」闸不在这里：判据是**当前文件类型的 `SAVE_*` / `GLOBAL_*` 名字表**
 * （`EmDataNames`），只有发射点（`SaveCapture.emitUnclaimedEm`，与 `EmDataPools.emitSnapshot`
 * 同一份表）能拿到它 —— 本对象只负责域与装载号（`lastLoadNo`）闸。
 */
class SaveUnclaimedPassthrough {

    private var normalEntries: List<UnclaimedSaveEntry> = emptyList()
    private var normalLoadNo: Long = NO_LOAD
    private var globalEntries: List<UnclaimedSaveEntry> = emptyList()
    private var charaEntries: Map<Long, List<UnclaimedCharaEntry>> = emptyMap()
    // L28：EM 段（Map/Xml/DT）未认领容器名的原始切片 —— normal 域与顶层变量共用装载号闸，
    // global 域跨局（与各自的顶层域同源理由，见类 KDoc）。
    private var emNormalEntries: List<UnclaimedSaveEntry> = emptyList()
    private var emGlobalEntries: List<UnclaimedSaveEntry> = emptyList()

    /**
     * 记录一次成功的普通档装载（[loadNo] = 该次 `LOADDATA` 的槽号）。
     *
     * @param charaBuckets 角色块内未认领切片，按该块 `NO` 分桶（L17），每条自带
     *   [UnclaimedCharaEntry.phase]（L23）；缺省空表 = 该档没有角色块未知键（零变化）。
     *   角色桶与 [normalSlices] 共用同一 [normalLoadNo] 失效闸。
     * @param emEntries EM 段（Map/Xml/DT）未认领容器名的原始切片（L28），保持读入原序；
     *   缺省空表 = 该档没有未知 EM 条目（零变化）。与 [normalSlices] 共用同一装载号闸。
     */
    fun captureNormal(
        loadNo: Long,
        entries: List<UnclaimedSaveEntry>,
        charaBuckets: Map<Long, List<UnclaimedCharaEntry>> = emptyMap(),
        emEntries: List<UnclaimedSaveEntry> = emptyList(),
    ) {
        normalEntries = entries.toList()
        charaEntries = if (charaBuckets.isEmpty()) emptyMap() else LinkedHashMap(charaBuckets)
        emNormalEntries = emEntries.toList()
        normalLoadNo = loadNo
    }

    /**
     * 记录一次成功的全局档装载。
     *
     * @param emEntries EM 段（Map/Xml/DT）未认领容器名的原始切片（L28，`GLOBAL_*` 侧的档）；
     *   缺省空表 = 零变化。global 域跨局，不设装载号闸。
     */
    fun captureGlobal(
        entries: List<UnclaimedSaveEntry>,
        emEntries: List<UnclaimedSaveEntry> = emptyList(),
    ) {
        globalEntries = entries.toList()
        emGlobalEntries = emEntries.toList()
    }

    /** 丢弃普通域的切片（下次 `LOADDATA` 开始复位变量池时调用）。 */
    fun clearNormal() {
        normalEntries = emptyList()
        charaEntries = emptyMap()
        emNormalEntries = emptyList()
        normalLoadNo = NO_LOAD
    }

    /** 丢弃全局域的切片（`LOADGLOBAL` 确认要重读时调用）。 */
    fun clearGlobal() {
        globalEntries = emptyList()
        emGlobalEntries = emptyList()
    }

    /**
     * 当前应当随普通档回写的切片：装载号仍匹配（[normalLoadNo] 闸）且键此刻仍未声明。
     * 顺序 = 读入时的原序。
     */
    fun normalSlices(store: VariableStore): List<UnclaimedSaveEntry> =
        if (normalLoadNo == store.lastLoadNo) writable(store, normalEntries) else emptyList()

    /** 当前应当随全局档回写的切片（global 域无装载号闸，见类 KDoc）。 */
    fun globalSlices(store: VariableStore): List<UnclaimedSaveEntry> = writable(store, globalEntries)

    /**
     * 当前应当随某一域 EM 段回写的切片（L28）。
     *
     * @param global true = global 域（跨局，无装载号闸）；false = normal 域
     *   （与 [normalSlices] 同一 `lastLoadNo` 闸）
     *
     * 「此刻是否已被本档的 `SAVE_*` / `GLOBAL_*` 名字表声明」的闸在发射点
     * （`SaveCapture.emitUnclaimedEm`，与 `EmDataPools.emitSnapshot` 同一份表）——
     * 本对象不知道档类型与 `VarExt` 名字表。
     */
    fun emSlices(store: VariableStore, global: Boolean): List<UnclaimedSaveEntry> = when {
        global -> emGlobalEntries
        normalLoadNo == store.lastLoadNo -> emNormalEntries
        else -> emptyList()
    }

    /**
     * 当前应当随普通档角色块回写的切片：装载号仍匹配（与 [normalSlices] 同一闸）且键此刻仍未声明。
     *
     * **只做「仍旧未声明」的过滤**，不判角色身份：写侧还要按当前角色表的 `NO` 列
     * 决定每块挂哪一桶（`SaveCapture.emitNormal` 的 `CharaSlicePlan`），桶键在当前表里
     * 不存在或重复的桶由那边丢弃并留痕 —— 本对象不持有 `NO` 列口径。
     */
    fun charaSlices(store: VariableStore): Map<Long, List<UnclaimedCharaEntry>> =
        if (normalLoadNo == store.lastLoadNo) writableChara(store) else emptyMap()

    /** 写回闸：跳过此刻已声明的键（查找口径与 `SaveData.applyEntry` 同源）。 */
    private fun writable(
        store: VariableStore,
        entries: List<UnclaimedSaveEntry>,
    ): List<UnclaimedSaveEntry> = entries.filter {
        store.registry.def(it.key) == null && store.userVar(it.key, null) == null
    }

    /**
     * 角色桶的写回闸：逐桶滤掉此刻已声明的键；整桶被滤空 ⇒ 该桶消失（写侧无块可挂，
     * 也无需留痕 —— 键已经被活值写过一次）。相位随条目原样保留（只滤键，不重排）。
     */
    private fun writableChara(store: VariableStore): Map<Long, List<UnclaimedCharaEntry>> {
        if (charaEntries.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Long, List<UnclaimedCharaEntry>>(charaEntries.size)
        for ((no, entries) in charaEntries) {
            val kept = entries.filter {
                store.registry.def(it.key) == null && store.userVar(it.key, null) == null
            }
            if (kept.isNotEmpty()) out[no] = kept
        }
        return out
    }

    companion object {

        /** aux 槽名（约定见 [com.eratw.port.core.host.RuntimeAux] 类 KDoc：能力家族前缀 + 小写）。 */
        const val AUX_SLOT: String = "save.unclaimed"

        /** 「没有装载过」哨兵，与 `VariableStore.lastLoadNo` 的初值同源（`VariableData.cs:2900`）。 */
        private const val NO_LOAD: Long = -1L

        /** 只查不建（未装配 = 宿主从没读过档、也没有切片可回写）。 */
        fun of(store: VariableStore): SaveUnclaimedPassthrough? =
            store.aux.peek<SaveUnclaimedPassthrough>(AUX_SLOT)

        /** 装配（只建一次；保留先装的那份，同 `SaveWiring.install` 的理由）。 */
        fun install(store: VariableStore): SaveUnclaimedPassthrough =
            store.aux.slot(AUX_SLOT) { SaveUnclaimedPassthrough() }

        /** 写侧现取：未装配 ⇒ 空表（零变化）。 */
        fun normalSlicesOf(store: VariableStore): List<UnclaimedSaveEntry> =
            of(store)?.normalSlices(store) ?: emptyList()

        /** 写侧现取：未装配 ⇒ 空表（零变化）。 */
        fun globalSlicesOf(store: VariableStore): List<UnclaimedSaveEntry> =
            of(store)?.globalSlices(store) ?: emptyList()

        /** 写侧现取角色桶：未装配 ⇒ 空表（零变化）。 */
        fun charaSlicesOf(store: VariableStore): Map<Long, List<UnclaimedCharaEntry>> =
            of(store)?.charaSlices(store) ?: emptyMap()

        /** 写侧现取 EM 段切片（L28）：未装配 ⇒ 空表（零变化）。 */
        fun emSlicesOf(store: VariableStore, global: Boolean): List<UnclaimedSaveEntry> =
            of(store)?.emSlices(store, global) ?: emptyList()

        /**
         * 把「按块捕获」的角色切片按 `NO` 分桶（L17：身份绑定，**不按块位置**）。
         * 每条切片自带 [UnclaimedCharaEntry.phase]（L23），分桶不按相位拆表 ——
         * 相位只在写侧决定落 `Separator` 的哪一侧。
         *
         * 丢弃规则（fail-closed，全部留痕给调用方）：
         * - 块内 `NO` 不可判定（缺失 / 重复 / 非整数标量，即 [UnclaimedCharaBlock.no] 为 null）
         *   ⇒ **整块**切片丢弃；
         * - 两个以上块解出同一个 `NO` ⇒ 该 `NO` 的**全部**切片丢弃（桶键撞车 = 身份歧义，
         *   挂给任何一块都可能挂错人）。
         *
         * @param rejected 被丢弃条目的键名（按原序追加；调用方拿去 `SaveDiagnostics.note`）
         * @return `NO` → 原始切片（插入序 = 首次出现的块序）
         */
        fun bucketByNo(
            blocks: List<UnclaimedCharaBlock>,
            rejected: MutableList<String>,
        ): Map<Long, List<UnclaimedCharaEntry>> {
            if (blocks.isEmpty()) return emptyMap()
            val grouped = LinkedHashMap<Long, MutableList<UnclaimedCharaEntry>>()
            val blockCount = HashMap<Long, Int>()
            val dropped = ArrayList<String>()
            for (block in blocks) {
                val no = block.no
                if (no == null) {
                    dropped.addAll(block.entries.map { it.key })
                    continue
                }
                grouped.getOrPut(no) { ArrayList(block.entries.size) }.addAll(block.entries)
                blockCount[no] = (blockCount[no] ?: 0) + 1
            }
            val out = LinkedHashMap<Long, List<UnclaimedCharaEntry>>(grouped.size)
            for ((no, entries) in grouped) {
                if ((blockCount[no] ?: 0) > 1) dropped.addAll(entries.map { it.key }) else out[no] = entries
            }
            rejected.addAll(dropped)
            return out
        }
    }
}
