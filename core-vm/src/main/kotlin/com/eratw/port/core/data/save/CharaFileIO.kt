package com.eratw.port.core.data.save

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.SaveDiagnostics
import com.eratw.port.core.vars.VarType
import com.eratw.port.core.vars.VariableCode
import com.eratw.port.core.vars.VariableStore

/**
 * 角色存档文件（`{DatDir}chara_<name>.dat`）的双向往返内核。
 *
 * 对齐原版 `VariableEvaluator.SaveChara` / `LoadChara`（VariableEvaluator.cs:2248-2330）：
 * - write：`header(魔数,1808,0) | FileType=CharVar | UniqueCode | ScriptVersion | text
 *   | 角色数 | 每个角色（SaveToStreamBinary 条目… + EOC）| EOF`。
 *   每个角色的条目集 = `varData.GetVarTokenDic()` 里
 *   `IsSavedata && IsCharacterData && !IsGlobal` 的项（CharacterData.cs:513-545），
 *   但**逐条落盘前还要过 `EraBinaryDataWriter.WriteWithKey` 的类型分派**
 *   （EraBinaryDataWriter.cs:83-173）：值为 `null` 的 0 维字符串格**整条不落盘**
 *   （类型字节和键都不写，总账 SAVE-04，见 [isNullInOriginal]）。
 *   系统变量写完后若有用户自定义角色变量则先写 Separator 再逐个写（:546-556）。
 * - read：类别必须是 CharVar、UniqueCode 相符、CheckVersion 通过，才把角色**追加**进角色表
 *   （不清空），成功置 `RESULT=1`（:2300-2317）。
 *
 * 字节布局与容器头由 [SaveFormat] 负责；本对象只做「变量池 ↔ 角色块」的映射。
 * CHARVAR 的条目顺序 = [com.eratw.port.core.vars.VariableRegistry.allInVarTokenDicOrder]
 * 的 varTokenDic 字典枚举序（已含角色段序，对齐 VariableData.cs:3074-3102），与原版
 * CharacterData.cs:515 的 GetVarTokenDic 遍历序同构；XML/键名回读不依赖顺序。
 */
object CharaFileIO {

    /** 物化入口的初始缓冲（16 KiB）：与 SaveFormat.encodePayload 同量级。 */
    private const val BYTE_SINK_CAPACITY = 1 shl 16

    /** `chara_` 文件名前缀（VariableEvaluator.cs:2044-2047）。 */
    const val PREFIX = "chara_"

    /** 角色存档文件名：`chara_<savename>.dat`。spec: VariableEvaluator.cs:2044-2047 */
    fun fileNameOf(savename: String): String = "$PREFIX$savename.dat"

    /**
     * 原版「按值决定写不写」的角色 0 维字符串列（= `dataString` 里 `strCount = 2`
     * 之外的两格，CharacterData.cs:25 / :58 / :134-135）。判读见 [isNullInOriginal]。
     */
    private val NULLABLE_CHARA_STR0 = setOf("NICKNAME", "MASTERNAME")

    /**
     * 把 [nos] 指定的角色打包成 CHARVAR 存档对象（未落盘）。spec: VariableEvaluator.cs:2248-2283
     *
     * 参数校验完全按原版 `SAVECHARA` 指令的**逐元素顺序**（FunctionIdentifier.cs:1876-1896），
     * 且对每个元素一次性跑完全部检查再进下一个：
     * 1. 负值 → [CodeEE]（toUInt32inArg，:4320-4325，消息 `ArgIsNegative`：
     *    lang/emuera-zhs.xml:913「{0}函数: 第{1}个参数被是负值({2})」）；
     * 2. 超过 Int.MaxValue → [CodeEE]（:4326-4329，消息 `ArgIsTooLarge`：:915）；
     * 3. ≥ CHARANUM → [CodeEE]（OoRSavecharaArg，:1884-1887，消息：:524）；
     * 4. 与前面元素重复 → [CodeEE]（DuplicateCharaNo，:1888-1894，消息：:525）。
     * 实参序从 3 起算（前两个实参是文件名与描述，:1877-1884），故角色号是第 `i + 3` 个参数。
     * 全部通过才调 `SaveChara`（:1895-1896）。
     *
     * @throws CodeEE 按上述顺序逐元素校验失败
     */
    fun capture(
        store: VariableStore,
        nos: List<Long>,
        text: String,
        compressed: Boolean = true,
        plan: com.eratw.port.core.vars.ModCsvInjectionPlan? = null,
    ): SaveData {
        val baos = java.io.ByteArrayOutputStream(BYTE_SINK_CAPACITY)
        SaveFormat.writeStreaming(compressed, baos) { w -> emitCapture(store, nos, text, w, plan) }
        return SaveFormat.read(baos.toByteArray())
    }

    /**
     * CHARVAR 存档的**流式发射**（SAVE-STREAM 波，生产写盘路）：`SaveChara`
     * （VariableEvaluator.cs:2248-2283）的段序 —— 头四字段 → 角色数 → 每块条目 + `EOC`
     * → 末尾补一个 `EOF`（:2270 的 `WriteEOF()`）。
     *
     * 校验（负值 / 过大 / 超登记范围 / 重复）与原版逐元素同序，抛 [CodeEE] 即原版语句层
     * 打印错误行的路径；**全部通过才动编码器**，与旧实现「先全验后抓取」同序。
     *
     * L41（CONTRACT-RT2-SAVECHARA-CHARSLICES）：写每个选中角色块时消费普通存档角色块的
     * 未认领切片（L17 / L23 的 `SaveUnclaimedPassthrough.charaSlicesOf`，按该角色**当前
     * `NO`** 对账；当前表里 `NO` 不唯一 ⇒ 该角色不回写 + 留痕）。计划在**任何字节写出前**
     * 成型 ⇒ 取切片失败只是不回写切片，已知条目照常发射。CHARVAR 自身的读侧捕获仍关闭
     * （L38 裁定），所以 `LOADCHARA` 读进来的未知键不会出现在这里。
     */
    internal fun emitCapture(
        store: VariableStore,
        nos: List<Long>,
        text: String,
        writer: SaveFormat.SaveWriter,
        plan: com.eratw.port.core.vars.ModCsvInjectionPlan? = null,
    ) {
        val gb = store.registry.gameBase
        badCharaArgMessage(nos, store.characters.count.toLong())?.let { throw CodeEE(it) }
        // L41：导出面切片计划；错误面 = 空计划 + 留痕（与 SaveCapture.emitNormal 的
        // charaSlicePlan 同一口径：宁可丢切片，也不让 SAVECHARA 因旁路数据失败）。
        val slicePlan = charaExportSlicePlan(store)
        writer.fileType(SaveFileKind.CHARVAR)
        writer.i64(gb.gameCode)
        writer.i64(gb.version)
        writer.str(text)
        writer.count(nos.size)
        for (pos in nos) {
            val ipos = pos.toInt()
            emitCharacter(store, ipos, writer, plan, slicePlan.slicesFor(store, ipos))
            writer.eoc()
        }
        writer.eof()
    }

    /**
     * 物化形态（[capture] 的实现）：发射到字节 → 用同一个解码器读回。
     *
     * 理由与 `SaveCapture.normal` 同一条：物化入口只在测试与诊断面用，换来的保证是
     * 「物化入口与流式入口段序逐字节同源」，不靠两份手写实现互相对账。
     */

    /**
     * `SAVECHARA` 实参校验（原版 `FunctionIdentifier.cs:1876-1896` 的逐元素判序）：
     * 负值 → 过大 → 超登记范围 → 重复；返回 null = 全部通过。
     *
     * 返回文案（而不是就地抛）有两个理由：四条分支的判序与文案仍是**独立**的四条，
     * 不会退化成一句「参数非法」；同时整个 `SAVECHARA` 入口只留一个 `throw CodeEE`
     * （detekt ThrowsCount 口径），错误出口因此只有一处。
     */
    private fun badCharaArgMessage(nos: List<Long>, count: Long): String? {
        val seen = HashSet<Long>()
        for ((i, no) in nos.withIndex()) {
            val argnum = i + 3 // 原版实参序 = (文件名, 描述, 角色号…)：第 3 个起是角色号
            val message = when {
                no < 0 -> "SAVECHARA函数: 第${argnum}个参数被是负值($no)"
                no > Int.MAX_VALUE -> "SAVECHARA函数: 第${argnum}个参数($no)过大"
                no >= count -> "SAVECHARA第${argnum}参数的值超出角色登记编号的范围"
                !seen.add(no) -> "同一个角色登记编号($no)被多次指定"
                else -> null
            }
            if (message != null) return message
        }
        return null
    }

    /** [capture] + 流式容器写口：直接得到可写盘的字节（生产 `SAVECHARA` 走这里）。 */
    fun encode(
        store: VariableStore,
        nos: List<Long>,
        text: String,
        compressed: Boolean = true,
        plan: com.eratw.port.core.vars.ModCsvInjectionPlan? = null,
    ): ByteArray {
        val baos = java.io.ByteArrayOutputStream(BYTE_SINK_CAPACITY)
        SaveFormat.writeStreaming(compressed, baos) { w -> emitCapture(store, nos, text, w, plan) }
        return baos.toByteArray()
    }

    /**
     * 把角色存档字节还原进变量池（**追加**角色，不清空既有角色）。
     *
     * @param scriptVersionDefined `GameBase.ScriptVersionDefined`（GameBase.cs:25），
     *   由调用方从 [com.eratw.port.core.save.SaveWiring] 交出；null = 走近似口径，
     *   见 [GameBaseGate.checkVersion]。
     * @return true = 版本检查通过并已写入（原版置 `RESULT=1`，spec: VariableEvaluator.cs:2316）；
     *   false = **不是二进制容器** / 类别不符 / UniqueCode 不符 / 版本不符
     *   （原版这四条同一条 `if` 里静默 `return`、`RESULT` 保持 0，:2299-2303 + :2305）。
     *
     * 「不是二进制容器」这一条 M10-SAVE 已按 :2300 收口：`CreateReader` 返回 null
     * （文本档、魔数不符、容器版本非 1808、文件短于 16 字节 `EraBinaryDataReader.cs:790-794`）
     * 时原版**不抛**，移植侧此前直接调 [SaveFormat.read] 会多抛一层 [SaveFormatException]
     * （经 `LOADCHARA` 冒成 `CodeEE`）。容器判定用 [SaveFormat.isBinaryContainer]（壳 +
     * **解压全文**都过）；[com.eratw.port.core.save.SaveCodec.isBinary] 2026-10-31 起只看壳，
     * 两者不再是同一个口径 —— 这里要的是「解压失败也当不存在」，读档分派要的只是
     * 「按二进制还是文本读」。
     * `CHKCHARADATA` 侧的类别/游戏码/版本比对已同步走 [GameBaseGate]（组长接线），两侧不再各写一份近似；
     * 它按 [SaveFormat.peek] 判容器（只看头字段，不校验正文尾部），文本档由 peek 的双格式解析承担。
     */
    fun load(bytes: ByteArray, store: VariableStore, scriptVersionDefined: Boolean? = null): Boolean {
        if (!SaveFormat.isBinaryContainer(bytes)) return false // :2300（CreateReader == null）
        // 容器壳完好但 payload 损坏：原版 LoadChara 只包 try/finally（VariableEvaluator.cs:2295-2329），
        // CharacterData.LoadFromStreamBinary 的 default 分支抛 FileEE(AbnormalData)（CharacterData.cs:670-671）
        // 冒泡到 DoScript 顶层 catch（Process.cs:462-476）受控进 Error 态。移植侧 SaveFormatException
        // 不是 CodeEE，会裸穿语句层（ProcessRuntime 只盖章 CodeEE/ExprEvalException）⇒ 收敛成 CodeEE
        // 走同一受控失败面（2026-10-26 fixcore 审查 save-B2；文本档/魔数不符/壳损坏三条已由
        // isBinaryContainer 收口，只剩这一角）。
        val data = try {
            // 与 LOADDATA 同一条「不存在的变量不建数组」口径（见 SaveFormat.read 的 keepKey）：
            // 角色档同样可能来自旧版本，已删除的角色变量按存档尺寸建数组既白花堆也可能顶穿预算。
            SaveFormat.read(bytes) { k -> store.registry.def(k) != null || store.userVar(k, null) != null }
        } catch (e: SaveFormatException) {
            throw CodeEE(e.message ?: "存档数据异常")
        }
        if (data.fileType != SaveFileKind.CHARVAR) return false // :2300
        val gb = store.registry.gameBase
        if (!GameBaseGate.uniqueCodeEqualTo(gb, data.uniqueCode)) return false // :2300
        if (!GameBaseGate.checkVersion(gb, data.scriptVersion, scriptVersionDefined)) return false // :2305
        writeBack(data, store)
        return true
    }

    // ------------------------------------------------------------------
    // 单个角色 → 条目块
    // ------------------------------------------------------------------
    /**
     * 单个角色的条目块发射（`CharacterData.SaveToStreamBinary`，CharacterData.cs:513-558）。
     *
     * 与旧 `captureCharacter` 的唯一差别：不建 `List<SaveEntry>`、不建数组 —— 每个条目
     * 直接写进 [writer]，元素的读法仍是活列访问器（`c.int1[i].get(pos, e)`），与原版
     * `writer.WriteWithKey(key, value.GetArrayChara(charano))` 同形。
     *
     * 条目序不可动：系统角色变量走 `allInVarTokenDicOrder()`（= `varTokenDic` Add 序，
     * CharacterData.cs:515 的遍历），用户自定义角色变量在一个 `Separator` 之后逐个写
     * （CharacterData.cs:546-556 / :666-684）。
     *
     * @param slices 该块未认领条目的原始切片（L17 / L23；缺省空 = 零变化）。按
     *   [UnclaimedCharaEntry.phase] 放置：SYSTEM 落 `Separator` **之前**（与系统项同段），
     *   USER 落 `Separator` **之后**（与用户项同段）；当前声明表没有用户角色变量但存在 USER
     *   切片 ⇒ **补一个 `Separator`** 再写（否则读侧会按系统相位认它们，见
     *   `CharacterData.cs:702-720`）。普通存档由 `SaveCapture.emitNormal` 的
     *   `charaSlicePlan` 传入；`SAVECHARA` 自 L41 起由 [emitCapture] 按选中角色的当前
     *   `NO` 传入（`charaSlicesOf`）。CHARVAR 自身的读侧捕获仍关闭（L38），`LOADCHARA`
     *   读进来的未知键不会进这里。
     */
    internal fun emitCharacter(
        store: VariableStore,
        pos: Int,
        writer: SaveFormat.SaveWriter,
        plan: com.eratw.port.core.vars.ModCsvInjectionPlan? = null,
        slices: List<UnclaimedCharaEntry> = emptyList(),
    ) {
        for (def in store.registry.allInVarTokenDicOrder()) {
            if (!def.isSavedata || !def.isCharacterData || def.isGlobal) continue
            if (isNullInOriginal(store, def, pos)) continue
            emitSystemCharaEntry(store, def, pos, writer, plan)
        }
        val systemSlices = slices.filter { it.phase == CharaPhase.SYSTEM }
        val userSlices = slices.filter { it.phase == CharaPhase.USER }
        // 系统相位切片：最后一个系统项之后、Separator 之前（原版的位置就是系统段内）。
        for (u in systemSlices) writer.raw(u.bytes)
        val userChara = store.savedUserVarList().filter {
            it.def.isSavedata && it.def.isCharacterData && !it.def.isGlobal
        }
        emitUserCharaSegment(writer, userChara.isNotEmpty(), userSlices) { w ->
            for (uv in userChara) emitUserCharaEntry(uv, pos, w)
        }
    }

    /**
     * 原版在这一格**根本不会落出条目**时为 true（总账 SAVE-04）。
     *
     * 判据不是「`IsSavedata` 为假」，也不是「写侧清单里硬缺这两列」——
     * `NICKNAME`/`MASTERNAME` 都带 `__SAVE_EXTENDED__`，`VariableToken` 构造里
     * `(Code & __SAVE_EXTENDED__) == __SAVE_EXTENDED__` 这一支直接置 `IsSavedata = true`
     * （VariableToken.cs:88-96；码值 VariableCode.cs:17/:132-133，移植侧
     * [com.eratw.port.core.vars.VariableCode] 同值），`CharacterData.SaveToStreamBinary`
     * 的遍历也确实把它们交给了 writer（CharacterData.cs:515-529）。
     *
     * 真正把它们挡在档外的是 writer：`EraBinaryDataWriter.WriteWithKey(string key, object v)`
     * 全程用 `v is long` / `v is string` … 的**运行时类型匹配**分派，末尾没有 `else`
     * （EraBinaryDataWriter.cs:83-173）。`dataString` 是 `new string[4]`
     * （CharacterData.cs:58），`CharacterTemplate.Nickname`/`.Mastername` 是普通引用字段、
     * 只有 CSV 里出现 `NICKNAME`/`あだ名`、`主人の呼び方`/`MASTERNAME` 行才被赋值
     * （CharacterTemplate.cs:17/:19；ConstantData.cs:1591-1598），ADDCHARA 时原样拷进
     * `dataString[2]`/`dataString[3]`（CharacterData.cs:134-135）——**没赋过值就是 null**，
     * 而 `null is string` 为假 ⇒ 类型字节、键、值三段一个字节都不写。
     * 于是原版只在「这一格被赋过值」的角色块里出现这两列。真档实测
     * （`sav/save00.sav` 逐条目解析，168 个角色块）：块 0 的分隔符之前恒 **27** 条系统条目
     * （ISASSI/NO + 21 个角色 1 维整数 + NAME/CALLNAME + CSTR + CDFLAG），里面**没有**
     * `NICKNAME`；`MASTERNAME` 只在块 26 / 61 出现（八云紫 / 藤原妹红，值 = 昴），其余 166 块没有。
     * 同一读数已钉在 `core/save/SaveFormatAuditTest` 的角色块一节。
     *
     * `NAME`/`CALLNAME` 不在此列：原版的**位置化正文**（`strCount = 2`，CharacterData.cs:25
     * 与 :351-356 的两次 `for (i < 2)`）把它们当「一定存在」的两格，写侧也只有这两格是
     * 无条件落盘的；移植侧因此对这两格保持无条件写。
     *
     * 移植侧的表示（**已知偏离**）：字符串走驻留池、下标 0 恒为空串，「未赋值」与「空串」
     * 在运行期语义上等价（StringTable 类文档），没有 null 位可查。所以这里按
     * 「值为空串」判原版的 null。偏离只在存档结构上可见，且不丢信息：
     * - 空串条目被省掉 → 读侧该格仍是池 0 = 空串（`SaveData.applyTo` 按名字查条目、
     *   缺项不动；角色位由 `CharacterTable.addBare` → `resetChar` 先清零，
     *   对齐原版 `new CharacterData(constant, varData)` 后 `LoadFromStreamBinary`
     *   只填文件里出现的键，VariableEvaluator.cs:2311-2312 + CharacterData.cs:560-613）；
     * - 被赋过值的格（含 CSV 真给了 `あだ名` 的角色）非空 ⇒ 照常写出，与原版一致。
     */
    private fun isNullInOriginal(store: VariableStore, def: com.eratw.port.core.vars.VarDef, pos: Int): Boolean {
        if (def.storage != com.eratw.port.core.vars.Storage.C_STR0) return false
        if (def.name.uppercase() !in NULLABLE_CHARA_STR0) return false
        return store.pool.lookup(store.characters.str0[def.index].get(pos, 0)).isEmpty()
    }

    /** 系统角色变量：直接读列存储（绕开 indices 口径，避免角色位置混入元素下标）。 */
    private fun emitSystemCharaEntry(
        store: VariableStore,
        def: com.eratw.port.core.vars.VarDef,
        pos: Int,
        writer: SaveFormat.SaveWriter,
        plan: com.eratw.port.core.vars.ModCsvInjectionPlan?,
    ) {
        val c = store.characters
        when (def.storage) {
            com.eratw.port.core.vars.Storage.C_INT0 ->
                writer.intValue(def.name, c.int0[def.index].get(pos, 0))
            com.eratw.port.core.vars.Storage.C_INT1 -> {
                val length = plan?.baseLength(def.name, def.storage, def.dims)
                    ?.coerceIn(0, c.int1[def.index].blockSize)
                    ?: c.int1[def.index].blockSize
                writer.int1(def.name, length) { e -> c.int1[def.index].get(pos, e) }
            }
            com.eratw.port.core.vars.Storage.C_INT2 -> {
                val dims = plan?.baseDimsOf(def.name, def.dims) ?: def.dims
                writer.int2(def.name, dims[0], dims[1]) { e -> c.int2[def.index].get(pos, e) }
            }
            com.eratw.port.core.vars.Storage.C_STR0 ->
                writer.strValue(def.name, store.pool.lookup(c.str0[def.index].get(pos, 0)))
            com.eratw.port.core.vars.Storage.C_STR1 -> {
                val length = plan?.baseLength(def.name, def.storage, def.dims)
                    ?.coerceIn(0, c.str1[def.index].blockSize)
                    ?: c.str1[def.index].blockSize
                writer.str1(def.name, length) { e -> store.pool.lookup(c.str1[def.index].get(pos, e)) }
            }
            else -> error("非角色变量存储 $def")
        }
    }

    /** 用户自定义角色变量：走 [UserVar.readInt]/readStr（elem 为行主序扁平下标）。 */
    private fun emitUserCharaEntry(
        uv: com.eratw.port.core.vars.UserVar,
        pos: Int,
        writer: SaveFormat.SaveWriter,
    ) {
        val def = uv.def
        if (def.type == VarType.INT) {
            when (def.dims.size) {
                0 -> writer.intValue(def.name, uv.readInt(pos, 0))
                1 -> writer.int1(def.name, def.dims[0]) { e -> uv.readInt(pos, e) }
                2 -> writer.int2(def.name, def.dims[0], def.dims[1]) { e -> uv.readInt(pos, e) }
                else -> error("角色变量 ${def.name} 的维度 ${def.dims.size} 不受支持")
            }
        } else {
            when (def.dims.size) {
                0 -> writer.strValue(def.name, uv.readStr(pos, 0))
                1 -> writer.str1(def.name, def.dims[0]) { e -> uv.readStr(pos, e) }
                2 -> writer.str2(def.name, def.dims[0], def.dims[1]) { e -> uv.readStr(pos, e) }
                else -> error("角色变量 ${def.name} 的维度 ${def.dims.size} 不受支持")
            }
        }
    }

    // ------------------------------------------------------------------
    // 校验：口径本体在 [GameBaseGate]（GameBase.cs:60-80），本层不再各写一份近似。
    // ------------------------------------------------------------------
}

/**
 * `LOADCHARA` 的**写回段**（:2315 的 `CharacterData` 还原进角色表）。
 *
 * 收敛口径（2026-11-18 stab-b 修复批）：原版 `LoadChara` 对还原段只包 `try/finally`
 * **不 catch**（VariableEvaluator.cs:2295-2329），任何异常冒泡到 `DoScript` 顶层
 * `catch (Exception)`（Process.cs:462-476）→ `handleExceptionInSystemProc` 红字上屏、
 * 游戏继续。移植侧本段若裸抛非 [CodeEE] 异常（applyTo 分配、列写回、needInit 尾段
 * 清零等任何运行时回归 / OOM），会穿透语句层（`ProcessRuntime.executeCore` 只盖章
 * CodeEE / ExprEvalException）→ VM 线程死 → 停机失败屏 —— 与原版「红字继续」结局不符。
 * 因此这里把**非 CodeEE** 异常收敛成带官方文案的 [CodeEE] 上抛（cause 经 initCause
 * 挂进因果链，不吞不静默）；[CodeEE] 本身（applyTo 的角色行预算闸等）原样透传，
 * 不二次包装 —— 预算文案是玩家可读的具体原因，包一层通用文案只会丢信息。
 *
 * 「带语句位置信息」由既有盖章管线完成：本函数抛出的 [CodeEE] 无位置字段
 * （funcName == null / line < 0 / pc < 0），`LOADCHARA` 语句执行体里穿出时由
 * `ProcessRuntime.stampPosition`（ProcessRuntime.kt:3888）按当前帧补函数/行/pc ——
 * 与 `FileSystemOps.loadData` 写回段（FileSystemOps.kt:746-765，save-B2）同一形状；
 * 文案同样取 [FileSystemOps.MES_LOAD_UNEXPECTED]（官方 `Lang.Error.UnexpectedErrorInLoaddata`，
 * lang/emuera.zhs.xml:704）：原版 LoadChara 没有 catch、无对应 Lang 条目，取同族
 * 官方文案是移植自有面的自立判据（ADR-005，无原版锚）。
 *
 * 放在 object 外：`CharaFileIO` 的对象成员数已顶到 detekt `TooManyFunctions` 阈值（11），
 * 拓扑上它也只是「写回段收敛」这一段（与 [emitUserCharaSegment] 同一理由）。
 * internal = 直测缝：收敛段需要「applyTo 抛非 CodeEE」的确定性输入才能验收，
 * 读侧产物字节无法构造（read 侧维度与 values 一致性在解码时已判死），故把写回段
 * 拆成独立函数供测试直接驱动（生产路径唯一调用者 = [CharaFileIO.load]）。
 */
@Suppress("TooGenericExceptionCaught")
internal fun writeBack(data: SaveData, store: VariableStore) {
    try {
        // 追加角色块，:2315
        data.applyTo(store, restoreCharacters = true, setLastLoad = false)
    } catch (e: CodeEE) {
        // 已是受控错误（角色行预算闸等）：原样上抛，不二次包装。
        throw e
    } catch (e: Throwable) {
        // @Suppress 理由：这里**刻意**接住 Throwable（含 OOM）—— 收敛口径要求写回段
        // 「任何非 CodeEE 异常」都转受控错误走红字（与 loadData 写回段同式），根因经
        // initCause 进因果链，不吞不静默（吞掉装没事 = 掩盖，禁止）。
        throw CodeEE(FileSystemOps.MES_LOAD_UNEXPECTED).apply { initCause(e) }
    }
}

/**
 * 用户 `#DIM CHARADATA` 段（`Separator` + 已知用户项 + 用户相位切片）。
 *
 * 段本身只在「有用户角色变量**或**有用户相位切片」时写出：后者是 L23 的合成条件 ——
 * 没有声明表也要补一个 `Separator`，否则用户相位切片会被读侧当系统项
 * （`CharacterData.cs:702-720`）。
 *
 * 放在 object 外：`CharaFileIO` 的对象成员数已顶到 detekt `TooManyFunctions` 阈值（11），
 * 拓扑上它也只是「按相位写一段」。
 */
private fun emitUserCharaSegment(
    writer: SaveFormat.SaveWriter,
    hasKnown: Boolean,
    userSlices: List<UnclaimedCharaEntry>,
    emitKnown: (SaveFormat.SaveWriter) -> Unit,
) {
    if (!hasKnown && userSlices.isEmpty()) return
    writer.separator()
    emitKnown(writer)
    for (u in userSlices) writer.raw(u.bytes)
}

/**
 * `SAVECHARA` 导出面的角色块未认领切片计划（L41，CONTRACT-RT2-SAVECHARA-CHARSLICES）。
 *
 * ## 为什么要有计划这一层
 *
 * 槽里的桶以**读普通存档时**的角色 `NO` 为键，而 `SAVECHARA` 的实参是**当前角色表的
 * 位置**子集。对账只有一处能做对：当前角色表的 `NO` 列（与 `SaveCapture.charaSlicePlan`
 * 同源）。计划在发射前一次性抓好 `currentNoCounts`：
 * - 该 `NO` 在当前表里恰好一次 ⇒ 选中块回写自己的桶（身份唯一）；
 * - 出现多次（当前表自身 `NO` 重复，含 `ADDVOIDCHARA` 的 `NO=0` 与 `LOADCHARA` 追加的
 *   同 `NO` 块）⇒ 该角色的桶**不写** + `SaveDiagnostics.note`（宁可丢也不挂错人）；
 * - 未被选中角色的桶**不写也不留痕**：子集 `SAVECHARA` 本身就是「文件 = 选中角色的快照」
 *   （原版 `FileMode.Create` 整档覆写，未选中的块不在文件里），不是丢弃。
 *
 * 每条切片自带块内相位（[UnclaimedCharaEntry.phase]），放置由 [CharaFileIO.emitCharacter]
 * 按相位做（SYSTEM 落 `Separator` 之前、USER 落之后，缺 `Separator` 时补一个）。
 *
 * 放在 object 外：`CharaFileIO` 的对象成员数已顶到 detekt `TooManyFunctions` 阈值（11）。
 */
private class CharaExportSlicePlan(
    private val buckets: Map<Long, List<UnclaimedCharaEntry>>,
    private val currentNoCounts: Map<Long, Int>,
) {

    /** 已留痕过的 `NO`（同一次导出里只留一条，不随选中次数刷屏）。 */
    private val noted = HashSet<Long>()

    /** 选中位置 [pos] 应回写的切片：当前 `NO` 唯一才交出，否则空表 + 留痕一次。 */
    fun slicesFor(store: VariableStore, pos: Int): List<UnclaimedCharaEntry> {
        val no = charaNoAt(store, pos)
        val entries = buckets[no] ?: return emptyList()
        return if (currentNoCounts[no] == 1) entries else ambiguous(no, entries)
    }

    /** 身份歧义：不挂给任何块（fail-closed），按 `NO` 去重留痕。 */
    private fun ambiguous(no: Long, entries: List<UnclaimedCharaEntry>): List<UnclaimedCharaEntry> {
        if (noted.add(no)) {
            SaveDiagnostics.note(
                OP,
                "unclaimed",
                "角色块未认领切片丢弃（当前角色表里该 NO 出现 ${currentNoCounts[no]} 次（身份歧义））：" +
                    "NO=$no 键=${entries.joinToString(",") { it.key }}",
            )
        }
        return emptyList()
    }

    companion object {

        /** 留痕的语句名（`SaveDiagnostics.note` 的 `op`）。 */
        const val OP: String = "SAVECHARA"

        /** 空计划（无切片 / 槽未装配 / 装载号不匹配）：零成本、零留痕。 */
        val EMPTY: CharaExportSlicePlan = CharaExportSlicePlan(emptyMap(), emptyMap())
    }
}

/**
 * 抓取 `SAVECHARA` 的切片计划（L41）：槽里的角色桶 ∩ 当前角色表的 `NO`。
 *
 * 失败面与 `SaveCapture.charaSlicePlan` 同口径：取切片抛异常 ⇒ 空计划 + 留痕，已知条目
 * 照常发射（不半截写入）；`OutOfMemoryError` 原样上抛给 `FileSystemOps.saveChara` 的
 * 落盘失败面。
 */
private fun charaExportSlicePlan(store: VariableStore): CharaExportSlicePlan =
    runCatching { buildCharaExportSlicePlan(store) }.getOrElse { e ->
        if (e is OutOfMemoryError) throw e
        SaveDiagnostics.note(
            CharaExportSlicePlan.OP,
            "unclaimed",
            "角色块未认领切片丢弃：${e.javaClass.simpleName}: ${e.message}",
        )
        CharaExportSlicePlan.EMPTY
    }

/** [charaExportSlicePlan] 的本体（拆出来让失败面只有一个 `runCatching`）。 */
private fun buildCharaExportSlicePlan(store: VariableStore): CharaExportSlicePlan {
    val buckets = SaveUnclaimedPassthrough.charaSlicesOf(store)
    if (buckets.isEmpty()) return CharaExportSlicePlan.EMPTY
    val counts = HashMap<Long, Int>(buckets.size * 2)
    val noColumn = store.characters.int0[VariableCode.lowIndex(VariableCode.NO_VAR)]
    for (pos in 0 until store.characters.count) {
        val no = noColumn.get(pos, 0)
        if (buckets.containsKey(no)) counts[no] = (counts[no] ?: 0) + 1
    }
    return CharaExportSlicePlan(buckets, counts)
}

/**
 * 当前角色 [pos] 的身份号：与 [CharaFileIO.emitSystemCharaEntry] 写 `NO` 条目读的是
 * **同一列**（`characters.int0` 的 `NO` 列）。发射侧与对账侧必须同源 —— 与
 * `SaveCapture.charaNoAt` 同一条理由。
 */
private fun charaNoAt(store: VariableStore, pos: Int): Long =
    store.characters.int0[VariableCode.lowIndex(VariableCode.NO_VAR)].get(pos, 0)
