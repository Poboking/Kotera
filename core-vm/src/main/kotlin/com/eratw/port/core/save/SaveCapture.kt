package com.eratw.port.core.save

import com.eratw.port.core.data.save.CharaFileIO
import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.data.save.SaveUnclaimedPassthrough
import com.eratw.port.core.data.save.UnclaimedCharaEntry
import com.eratw.port.core.data.save.UnclaimedSaveEntry
import com.eratw.port.core.fn.EmDataNames
import com.eratw.port.core.fn.EmDataPools
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.Storage
import com.eratw.port.core.vars.UserVar
import com.eratw.port.core.vars.VarDef
import com.eratw.port.core.vars.VarType
import com.eratw.port.core.vars.VariableCode
import com.eratw.port.core.vars.VariableStore

/**
 * 变量池 → 存档（原版 `VariableEvaluator.SaveToStreamBinary` / `SaveGlobalToStreamBinary`
 * 的抓取侧）。
 *
 * 进档的筛选口径**只认 `VarDef.isSavedata` 位**，不按名字猜（VariableToken.cs:88-154 的位运算
 * 已在 `VariableCode.isSavedata` 复刻）：
 * - 普通档：`isSavedata && !isCharacterData && !isGlobal`（VariableData.cs:3980-3997）
 *   + 非角色非全局的用户 `#DIM SAVEDATA` 静态变量；
 * - 全局档：`isSavedata && isCharacterData == false && isGlobal`（VariableData.cs:3961-3978）
 *   + 用户 `#DIM GLOBAL SAVEDATA` 变量（VariableData.cs:3974-3977）；
 * - 角色块：直接复用 [CharaFileIO] 的发射口（同一份
 *   `isSavedata && isCharacterData && !isGlobal` 口径，CharacterData.cs:513-558），
 *   避免第二套角色字段清单。
 *
 * ## 两条入口、一处真源
 *
 * - [emitNormal] / [emitGlobal] 是**生产存档路**（SAVE-STREAM 波）：逐条目直写
 *   [SaveFormat.SaveWriter]，列走零拷贝视图（`VariableStore.systemIntColumn`），
 *   不构造 `SaveEntry`、不复制数组 —— 与原版 `writer.WriteWithKey(key, value.GetArray())`
 *   （VariableData.cs:3987）同形，`GetArray()` 返回的正是活引用（:76 / :168 / :268）。
 * - [normal] / [global] 是**物化入口**（文本格式回落、运行期快照、测试参照），实现方式是
 *   「发射到字节 → 用同一个解码器读回」：段序与字段因此**只可能**与生产路逐字节一致，
 *   不存在两份手写实现慢慢分叉的余地。
 *
 * ## 已知边界：没注册的变量不写
 *
 * 抓取面完全由 [VariableStore] 的注册表决定（`registry.allInVarTokenDicOrder()` +
 * `savedUserVarList()`），与 `varTokenDic`（VariableData.cs:3982）同口径。因此**必须已经注册**才会进档：
 * 用户 `#DIM SAVEDATA` 变量依赖启动期 ERH 装载（`SystemVariableBootstrap.install`，
 * `GameSession.kt:207-212`）。只建 CSV 注册表、不装 ERH 声明时，eraTW 真档的 540 条全局
 * 条目里只有 63 条系统变量可写，其余 477 条**静默不进档** —— 这不是本对象的 bug，
 * 而是「变量池不知道这些变量存在」的必然结果（读侧 `applyTo` 对未知键同样是丢弃）。
 * 装置侧的最小正确做法见 `SaveRoundTripTest.realStore`。
 *
 * **例外（L15 / L17 / L23 / L28 / ADR-005 例外）**：读档时被丢弃的未认领条目不再永久消失 ——
 * `FileSystemOps` 在读档成功时把它们存进 `SaveUnclaimedPassthrough`（`store.aux`），
 * [emitNormal] 在**普通档**的已知条目之后原样回写：
 * - 顶层 normal/global 变量：变量段末尾、`EOF` 之前（L15）；
 * - 角色块内条目：**同一 `NO`** 的角色块里按读入相位放回 —— 系统项切片落 `Separator`
 *   之前、用户 `#DIM CHARADATA` 项切片落之后（L17 的身份绑定 + L23 的相位保真；
 *   按块内 `NO` 绑定，不按块位置 —— 角色顺序/数量变化时位置会挂错人）。
 * - EM 段（Map / Xml / DT）容器条目：EM 段末尾、`EOF` 之前（L28；按本档声明表过滤，
 *   见 `emitUnclaimedEm`）。写侧「此刻已声明」的闸用**本档自己的** `SAVE_*` / `GLOBAL_*`
 *   名字表，与 `EmDataPools.emitSnapshot` 同源。
 */
object SaveCapture {

    /** 物化入口的初始缓冲（16 KiB）：与 SaveFormat.encodePayload 同量级，够摊薄扩容。 */
    private const val BYTE_SINK_CAPACITY = 1 shl 16

    /** 三维用户变量的 `dims.size`（0/1/2 已被 detekt 的 ignoreNumbers 豁免，3 没有）。 */
    private const val DIMS_3D = 3

    /**
     * 普通存档（`save{no:00}.sav`）的**物化**形态。
     *
     * @param text 存档描述文本（`SAVEDATA_TEXT`；`SAVEDATA` 语句直接传参，UI 存档流程自己拼时间戳，
     *   Process.cs:2573/1327-1349）
     * @param compressed 二进制分支是否 GZip（`SystemSaveInBinary && ZipSaveData`，EraBinaryDataWriter.cs:22-32）
     * @param emData 扩展段名字表（`SAVE_MAPS/SAVE_XMLS/SAVE_DTS`）；空表 = 不写扩展段
     */
    fun normal(
        store: VariableStore,
        text: String,
        compressed: Boolean = true,
        emData: EmDataNames = EmDataNames.EMPTY,
        sink: com.eratw.port.core.vars.ModCsvValueBagSink? = null,
    ): SaveData = materialize(store, compressed, emData) { w -> emitNormal(store, text, w, emData, sink) }

    /**
     * 全局存档（`global.sav`）的物化形态。描述文本恒空串
     * （`VariableEvaluator.cs:2488` `WriteString("")`）。
     *
     * @param emData 扩展段名字表（`GLOBAL_MAPS/GLOBAL_XMLS/GLOBAL_DTS`）；空表 = 不写扩展段
     */
    fun global(
        store: VariableStore,
        compressed: Boolean = true,
        emData: EmDataNames = EmDataNames.EMPTY,
    ): SaveData = materialize(store, compressed, emData) { w -> emitGlobal(store, w, emData) }

    /**
     * 普通存档的**流式发射**（生产路）：段序 = `VariableEvaluator.SaveToStreamBinary`
     * （:2591-2606）—— 头四个字段 → 角色数 + 每个角色块（`EOC`）→ 变量段（`EOF`）
     * → EM 段（`EOF`）。
     */
    internal fun emitNormal(
        store: VariableStore,
        text: String,
        writer: SaveFormat.SaveWriter,
        emData: EmDataNames = EmDataNames.EMPTY,
        sink: com.eratw.port.core.vars.ModCsvValueBagSink? = null,
    ) {
        val gb = store.registry.gameBase
        writer.fileType(SaveFileKind.NORMAL)
        writer.i64(gb.gameCode)
        writer.i64(gb.version)
        writer.str(text)
        val count = store.characters.count
        writer.count(count)
        // L17 / L23（ADR-005 例外第二波）：角色块未认领切片，按当前角色表的 `NO` 列决定挂哪一桶，
        // 并按各条自带的相位交给 `CharaFileIO.emitCharacter` 落在 `Separator` 的对应一侧。
        // 计划在任何字节写出前成型 ⇒ 取计划失败只是「不回写切片」，不影响已知条目的发射。
        val charaPlan = charaSlicePlan(store)
        for (pos in 0 until count) {
            val slices = charaPlan.slicesFor(store, pos)
            CharaFileIO.emitCharacter(store, pos, writer, sink?.plan, slices)
            sink?.capture(store, pos)
            writer.eoc()
        }
        charaPlan.noteDropped()
        emitVariables(store, global = false, writer)
        // L15（ADR-005 例外）：顶层未认领键的原始切片，已知变量之后、EOF 之前。
        emitUnclaimedSlices(store, global = false, writer)
        writer.eof()
        // EM 扩展段（SaveEMDataToStreamBinary，VariableData.cs:3911-3959）
        EmDataPools.emitSnapshot(store, emData, writer)
        // L28（ADR-005 例外第三波）：EM 段未认领容器名的原始切片，已知 EM 条目之后、
        // 本段 EOF 之前（与 SaveFormat.writePayload 同一个插入点）。
        emitUnclaimedEm(store, global = false, emData, writer)
        writer.eof()
    }

    /**
     * 全局存档的流式发射：`SaveGlobalToStreamBinary`（`VariableEvaluator.cs:2483-2493`）
     * 没有角色数、没有角色块，头字段之后直接是变量段 + EM 段。
     */
    internal fun emitGlobal(
        store: VariableStore,
        writer: SaveFormat.SaveWriter,
        emData: EmDataNames = EmDataNames.EMPTY,
    ) {
        val gb = store.registry.gameBase
        writer.fileType(SaveFileKind.GLOBAL)
        writer.i64(gb.gameCode)
        writer.i64(gb.version)
        writer.str("")
        emitVariables(store, global = true, writer)
        // L15（ADR-005 例外）：顶层全局未认领键的原始切片，已知变量之后、EOF 之前。
        emitUnclaimedSlices(store, global = true, writer)
        writer.eof()
        EmDataPools.emitSnapshot(store, emData, writer)
        // L28（ADR-005 例外第三波）：全局档 EM 段未认领容器名的原始切片（全局域，跨局）。
        emitUnclaimedEm(store, global = true, emData, writer)
        writer.eof()
    }

    /**
     * 物化入口的唯一实现：发射 → 同一个解码器读回。
     *
     * 为什么舍得这一趟编解码：这条入口只在文本格式回落（`SystemSaveInBinary=false`，
     * 不是 eratw 生产口径）、运行期快照与测试参照里用，不在 `SAVEDATA` 的热路上；
     * 换来的保证是「物化入口与流式入口的段序/字段**逐字节同源**」，而不是靠两份代码对账。
     *
     * 读回用与生产读档**同一个**「已知键」谓词（`registry.def` / `userVar`，与
     * `FileSystemOps.isKnownSaveKey` 同源）：发射段尾的未认领原始切片因此落进
     * [SaveData.unclaimedVariables]（原始字节），而不会在 `variables` 里被解析成普通条目
     * 再重编码 —— 物化路与流式路的未认领切片逐字节一致（L15）。
     *
     * EM 段同理（L28）：读回的 EM 接受集 = 本次发射用的 [emData] 名字表。发射时
     * `emitSnapshot` 只写这些名字、`emitUnclaimedEm` 只写不在表里的原始切片，所以读回后
     * [SaveData.emData] 恰是已知条目、[SaveData.unclaimedEmData] 恰是切片 —— 与流式产物
     * 逐字节同源，且不会把切片解析成值对象再重编码。
     */
    private fun materialize(
        store: VariableStore,
        compressed: Boolean,
        emData: EmDataNames,
        body: (SaveFormat.SaveWriter) -> Unit,
    ): SaveData {
        val baos = java.io.ByteArrayOutputStream(BYTE_SINK_CAPACITY)
        SaveFormat.writeStreaming(compressed, baos) { w -> body(w) }
        val declaredEm = emNameSet(emData)
        return SaveFormat.read(
            baos.toByteArray(),
            keepKey = { key -> store.registry.def(key) != null || store.userVar(key, null) != null },
            emKeepKey = { key -> key in declaredEm },
        )
    }

    // ------------------------------------------------------------------
    // 变量段
    // ------------------------------------------------------------------

    /**
     * 变量段发射：先系统变量（`registry.allInVarTokenDicOrder()` = C# `varTokenDic` 的 Add 序，
     * VariableData.cs:3014-3179），再用户 `#DIM SAVEDATA` 变量 —— 两条循环各一次，顺序固定。
     *
     * 条目序必须对齐 PC 的字典枚举序（:3982 的 foreach；specs 里的 save-format 文档 §5.3 第 3 条）：
     * 未改档靠 sourceFile 原字节短路不受影响，改档后整档重写时只有用本序才与 PC 字节一致。
     * 注意不是 `registry.all()` 的注册序 —— 那是按存储形态分组的构造序。
     */
    internal fun emitVariables(store: VariableStore, global: Boolean, writer: SaveFormat.SaveWriter) {
        for (def in store.registry.allInVarTokenDicOrder()) {
            if (!def.isSavedata || def.isCharacterData) continue
            if (def.isGlobal != global) continue
            emitDef(store, def, writer)
        }
        for (uv in store.savedUserVarList()) {
            val def = uv.def
            if (def.isCharacterData) continue
            if (def.isGlobal != global) continue
            emitUserVar(uv, writer)
        }
    }

    /**
     * 单个系统变量 → 条目。
     *
     * 整列走**零拷贝视图**（[VariableStore.systemIntColumn] / [VariableStore.systemStrColumn]）；
     * 视图拒绝（禁止存档位 / 同名遮蔽 / 列长与维度乘积不符）才退回逐元素按名读法。
     * 不可存档的存储形态（PSEUDO / LOCAL / CSV 常量）**不产出条目**（原实现返回 null 同义）。
     *
     * 性能账（为什么必须走视图）：`TA`/`TB` 出厂 100×100×100 ⇒ 逐元素形态是每次存档
     * 200 万轮「按名解析 + 新建下标数组」；整列复制形态则是每次存档 16.7 MiB（全局声明列）
     * 加每角色 208 KiB 的瞬时分配 —— 后者正是 2026-11 玩家「一天玩 38 分钟、存档三次都失败」
     * 的堆尖峰来源。
     */
    private fun emitDef(store: VariableStore, def: VarDef, writer: SaveFormat.SaveWriter) {
        when (def.storage) {
            Storage.G_INT1 -> {
                val col = store.systemIntColumn(def)
                if (col != null) {
                    writer.int1(def.name, col.length, col::get)
                } else {
                    writer.int1(def.name, def.dims[0]) { store.readInt(def.name, longArrayOf(it.toLong())) }
                }
            }

            Storage.G_INT2 -> {
                val dim0 = def.dims[0]
                val dim1 = def.dims[1]
                val col = store.systemIntColumn(def)
                if (col != null) {
                    writer.int2(def.name, dim0, dim1, col::get)
                } else {
                    writer.int2(def.name, dim0, dim1) { k ->
                        store.readInt(def.name, longArrayOf((k / dim1).toLong(), (k % dim1).toLong()))
                    }
                }
            }

            Storage.G_INT3 -> {
                val d0 = def.dims[0]
                val d1 = def.dims[1]
                val d2 = def.dims[2]
                val col = store.systemIntColumn(def)
                if (col != null) {
                    writer.int3(def.name, d0, d1, d2, col::get)
                } else {
                    writer.int3(def.name, d0, d1, d2) { k ->
                        store.readInt(
                            def.name,
                            longArrayOf(
                                (k / (d1 * d2)).toLong(),
                                ((k / d2) % d1).toLong(),
                                (k % d2).toLong(),
                            ),
                        )
                    }
                }
            }

            Storage.G_STR0 -> writer.strValue(def.name, store.readStr(def.name, longArrayOf()))

            Storage.G_STR1 -> {
                val col = store.systemStrColumn(def)
                if (col != null) {
                    writer.str1(def.name, col.length, col::get)
                } else {
                    writer.str1(def.name, def.dims[0]) { store.readStr(def.name, longArrayOf(it.toLong())) }
                }
            }

            else -> Unit
        }
    }


    /**
     * 用户 `#DIM SAVEDATA` 变量 → 条目（按类型三分，控制流压平以便各分支独立可读）。
     *
     * 与旧实现的差别只在「不预先生成数组」：`readInt` / `readStr` / `readElem` 逐元素读，
     * 编码器边读边写。
     */
    private fun emitUserVar(uv: UserVar, writer: SaveFormat.SaveWriter) {
        when (uv.def.type) {
            VarType.FLOAT, VarType.INT -> emitUserNumericVar(uv, writer)
            else -> emitUserStrVar(uv, writer)
        }
    }

    /** 浮点 / 整数用户变量：浮点只有 1 维（`#DIMF`），整数按 0/1/2/3 维分派。 */
    private fun emitUserNumericVar(uv: UserVar, writer: SaveFormat.SaveWriter) {
        val def = uv.def
        if (def.type == VarType.FLOAT) {
            // `#DIMF` 在存储层被钉成 **1 维**（VariableStore.checkFloatDimShape，
            // variables 规约里的 float_vars.dimf），故只有 1 维形态可达；
            // 0/2/3 维的 DimSpec 在 declareUserVar 就被拒，这里不写不可达分支。
            if (def.dims.size == 1) {
                (uv as? VariableStore.FloatUserVar)?.let { fuv ->
                    writer.float1(def.name, def.dims[0]) { fuv.readElem(it) }
                }
            }
            return
        }
        val dims = def.dims
        val d0 = dims.getOrNull(0) ?: 1
        val d1 = dims.getOrNull(1) ?: 1
        val d2 = dims.getOrNull(2) ?: 1
        when (dims.size) {
            0 -> writer.intValue(def.name, uv.readInt(0, 0))
            1 -> writer.int1(def.name, d0) { uv.readInt(0, it) }
            2 -> writer.int2(def.name, d0, d1) { uv.readInt(0, it) }
            DIMS_3D -> writer.int3(def.name, d0, d1, d2) { uv.readInt(0, it) }
        }
    }

    private fun emitUserStrVar(uv: UserVar, writer: SaveFormat.SaveWriter) {
        val def = uv.def
        val dims = def.dims
        val d0 = dims.getOrNull(0) ?: 1
        val d1 = dims.getOrNull(1) ?: 1
        val d2 = dims.getOrNull(2) ?: 1
        when (dims.size) {
            0 -> writer.strValue(def.name, uv.readStr(0, 0))
            1 -> writer.str1(def.name, d0) { uv.readStr(0, it) }
            2 -> writer.str2(def.name, d0, d1) { uv.readStr(0, it) }
            DIMS_3D -> writer.str3(def.name, d0, d1, d2) { uv.readStr(0, it) }
        }
    }
}

/**
 * 顶层未认领键的原始切片（L15 / ADR-005 例外）—— 接在 `SaveCapture.emitVariables` 之后、
 * `EOF` 之前（`SaveCapture.emitNormal` / `emitGlobal` 的唯一调用点）。
 *
 * 切片来自 [SaveUnclaimedPassthrough]（`LOADDATA` / `LOADGLOBAL` 成功时存槽），
 * 原样直写、不解析不重编码：未知大数组的 0 游程编码不会被展开（读侧 306.8 MiB
 * 声明量的根修不回退，见 `SaveUnknownKeySkipTest`）。无装载过的档 = 空表 = 零变化。
 *
 * **失败面**：筛选切片抛异常 ⇒ 丢弃全部切片 + 留痕（[SaveDiagnostics.note]），
 * 照常写出已知变量 —— 与改动前的成功/失败语义一致，不半截写入；`OutOfMemoryError`
 * 是 `Error`，原样上抛给 `FileSystemOps.saveData` 的求援重试链。
 *
 * 放在 object 外：`SaveCapture` 的函数数已在 detekt `TooManyFunctions` 阈值上（11），
 * 再添一个成员会让整条规则对既有对象报红；拓扑上它也只是「按域的切片选择 + 直写」。
 */
private fun emitUnclaimedSlices(
    store: VariableStore,
    global: Boolean,
    writer: SaveFormat.SaveWriter,
) {
    val slices = runCatching {
        if (global) {
            SaveUnclaimedPassthrough.globalSlicesOf(store)
        } else {
            SaveUnclaimedPassthrough.normalSlicesOf(store)
        }
    }.getOrElse { e ->
        if (e is OutOfMemoryError) throw e
        SaveDiagnostics.note(
            if (global) "SAVEGLOBAL" else "SAVEDATA",
            "unclaimed",
            "未认领键切片丢弃：${e.javaClass.simpleName}: ${e.message}",
        )
        return
    }
    for (u in slices) writer.raw(u.bytes)
}

/**
 * EM 段未认领容器名的原始切片（L28 / ADR-005 例外第三波）—— 接在
 * `EmDataPools.emitSnapshot` 之后、EM 段的 `EOF` 之前（`SaveCapture.emitNormal` /
 * `emitGlobal` 与 `SaveFormat.writePayload` 的唯一插入点）。
 *
 * ## 写回闸 = **本档自己的声明表**
 *
 * 判据只能用 [declared]（也就是 `emitSnapshot` 刚刚遍历的那张表：[SaveCapture.emitNormal]
 * 传 `SAVE_*`、[SaveCapture.emitGlobal] 传 `GLOBAL_*`）。理由是双写与丢数据各只有一边：
 * - 名字此刻在本档声明表里 ⇒ `emitSnapshot` 已按**活值**写过一条（键由活池拥有），
 *   再写切片就是同名两条（原版按名索引、后者胜，但文件里多一条垃圾）；
 * - 名字只出现在**另一域**的表里（读侧接受集是 `Save* ∪ Global*` 的并集，
 *   `VariableData.cs:4099/4109/4119`）⇒ 本档的 `emitSnapshot` 不会写它；此时按并集
 *   过滤等于把这条容器从本档删掉（数据丢），按本档表过滤则原样保留。
 * 原版对「读到的名字与写侧表不符」本来就是这样：读面接受并集、写面只认自己那一半。
 *
 * **失败面**：取切片抛异常 ⇒ 丢弃全部 EM 切片 + 留痕（[SaveDiagnostics.note]），
 * 已知 EM 条目照常发射；`OutOfMemoryError` 原样上抛给 `FileSystemOps.saveData` 的求援重试链。
 * 与 [emitUnclaimedSlices] 同一条口径（切片表在写任何字节之前取好，不留半截）。
 */
private fun emitUnclaimedEm(
    store: VariableStore,
    global: Boolean,
    declared: EmDataNames,
    writer: SaveFormat.SaveWriter,
) {
    val slices = runCatching {
        SaveUnclaimedPassthrough.emSlicesOf(store, global)
    }.getOrElse { e ->
        if (e is OutOfMemoryError) throw e
        SaveDiagnostics.note(
            if (global) "SAVEGLOBAL" else "SAVEDATA",
            "unclaimed",
            "EM 段未认领切片丢弃：${e.javaClass.simpleName}: ${e.message}",
        )
        return
    }
    if (slices.isEmpty()) return
    val declaredNames = emNameSet(declared)
    for (u in slices) {
        if (u.key in declaredNames) continue
        writer.raw(u.bytes)
    }
}

/**
 * `EmDataNames` 三张表的并集（只读判据）：EM 条目名跨 Map / Xml / DT **按类型分表**，
 * 但同名条目在写侧只可能属于其中一类（`SaveEntry` 的类型字节决定），故并集足够。
 * 空表 ⇒ 空集（零分配，干净档一个字节都不多花）。
 */
private fun emNameSet(names: EmDataNames): Set<String> {
    val size = names.maps.size + names.xmls.size + names.dts.size
    if (size == 0) return emptySet()
    val out = HashSet<String>(size * 2)
    out.addAll(names.maps)
    out.addAll(names.xmls)
    out.addAll(names.dts)
    return out
}

/**
 * 角色块未认领切片的**写侧计划**（L17 / L23，ADR-005 例外第二波）：`NO` → 该块应回写的原始切片。
 *
 * ## 为什么要有「计划」这一层，而不是在发射循环里直接查槽
 *
 * 槽里的桶以**读档时**的角色 `NO` 为键，而发射循环按**当前**角色表逐位置走。两者的对账只有
 * 一处可以做对：当前角色表的 `NO` 列。计划在发射前一次性抓好 [currentNoCounts]（当前表里
 * 每个桶键出现几次），于是：
 * - `NO` 在当前表里恰好一次 ⇒ 该块回写自己的桶（身份唯一）；
 * - 一次都没有（角色被删/被替换）⇒ 桶丢弃 + 留痕；
 * - 出现多次（当前表自身 `NO` 重复，身份歧义）⇒ 桶丢弃 + 留痕，宁可丢也不挂错人。
 *
 * 桶里的每条切片自带块内相位（[UnclaimedCharaEntry.phase]）—— 放置由
 * `CharaFileIO.emitCharacter` 按相位做（SYSTEM 落 `Separator` 之前、USER 落之后，
 * 缺 `Separator` 时补一个），本计划只负责「哪一桶挂哪个块」。
 *
 * `used` 记录实际交出过的桶；发射结束后还没被用到的桶统一留痕（[noteDropped]）——
 * 这样「桶不匹配」的留痕不依赖发射循环里的分支，删角色/改顺序两种形态走同一条路。
 */
private class CharaSlicePlan(
    private val buckets: Map<Long, List<UnclaimedCharaEntry>>,
    private val currentNoCounts: Map<Long, Int>,
) {

    private val used = HashSet<Long>()

    /** 当前角色 [pos] 的块应回写的切片：身份唯一才交出，随后记入 [used]。 */
    fun slicesFor(store: VariableStore, pos: Int): List<UnclaimedCharaEntry> {
        val no = charaNoAt(store, pos)
        val slices = if ((currentNoCounts[no] ?: 0) == 1) buckets[no] else null
        if (slices != null) used.add(no)
        return slices ?: emptyList()
    }

    /** 发射结束后仍没被用到的桶 = 当前角色表里找不到唯一身份 ⇒ 丢弃并留痕。 */
    fun noteDropped() {
        for ((no, entries) in buckets) {
            if (used.contains(no)) continue
            val occurrences = currentNoCounts[no] ?: 0
            val why = if (occurrences == 0) {
                "当前角色表里没有该 NO（角色被删/被替换）"
            } else {
                "当前角色表里该 NO 出现 $occurrences 次（身份歧义）"
            }
            SaveDiagnostics.note(
                "SAVEDATA",
                "unclaimed",
                "角色块未认领切片丢弃（$why）：NO=$no 键=${entries.joinToString(",") { it.key }}",
            )
        }
    }

    companion object {

        /** 空计划（未装配 / 无角色块未知键 / 取计划失败）：零成本、零留痕。 */
        val EMPTY: CharaSlicePlan = CharaSlicePlan(emptyMap(), emptyMap())
    }
}

/**
 * 当前角色 [pos] 的**身份号**：与 `CharaFileIO.emitSystemCharaEntry` 写 `NO` 条目读的是**同一列**
 * （`characters.int0[lowIndex(NO_VAR)]`，`SystemLayout.charaInt0D` 第 2 项）。
 * 走列而不是按名 `readInt`：发射侧与对账侧必须同源，否则「写进档的 NO」与「找桶用的 NO」
 * 可能被同名遮蔽/禁止存档位之类的闸分叉。
 */
private fun charaNoAt(store: VariableStore, pos: Int): Long =
    store.characters.int0[VariableCode.lowIndex(VariableCode.NO_VAR)].get(pos, 0)

/**
 * 抓取写侧计划（L17）：槽里的角色桶 ∩ 当前角色表的 `NO`。
 *
 * 失败面与 [emitUnclaimedSlices] 同口径：取计划抛异常 ⇒ 空计划 + 留痕，已知条目照常发射
 * （不半截写入）；`OutOfMemoryError` 原样上抛给 `FileSystemOps.saveData` 的求援重试链。
 */
private fun charaSlicePlan(store: VariableStore): CharaSlicePlan {
    val buckets = runCatching { SaveUnclaimedPassthrough.charaSlicesOf(store) }.getOrElse { e ->
        if (e is OutOfMemoryError) throw e
        SaveDiagnostics.note(
            "SAVEDATA",
            "unclaimed",
            "角色块未认领切片丢弃：${e.javaClass.simpleName}: ${e.message}",
        )
        emptyMap()
    }
    if (buckets.isEmpty()) return CharaSlicePlan.EMPTY
    val counts = HashMap<Long, Int>(buckets.size * 2)
    val noColumn = store.characters.int0[VariableCode.lowIndex(VariableCode.NO_VAR)]
    for (pos in 0 until store.characters.count) {
        val no = noColumn.get(pos, 0)
        if (buckets.containsKey(no)) counts[no] = (counts[no] ?: 0) + 1
    }
    return CharaSlicePlan(buckets, counts)
}
