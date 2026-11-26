package com.eratw.port.core.proc

import com.eratw.port.core.vars.VariableStore

/**
 * 「约会中／路人子」现场读数（diag-datingsync）：**只读**观察口，语义零改动。
 *
 * ## 领域边界（2026-11-19 兼容层解耦）
 * 本类是**通用读数机制**：「读哪个变量、用哪个元素号、取哪一位」由 [ForensicsLayout] 注入，
 * 取值算法本身不含任何具体 era 游戏的语料知识。eraTW 的 AutoConst 坐标集中在其宿主侧
 * `EratwForensicsLayout`；别的 era 游戏不注入（[ForensicsLayout.DISABLED]）⇒ 本口整段
 * isEmpty、零读取，jar 里不再携带 eraTW 语料坐标（业务零耦合铁律，era-arch-v61u.md §0）。
 *
 * ## 为什么要有这个口（历史取证，保留溯源）
 * 2026-11-01 两条玩家反馈的取证结论（判定链全文见 BOARD 的交 u981 一节）：
 *
 * 一、「我一个人去寺子屋，慧音却说"约会中吗？感情真好呢"」= **4.980 语料判式缺陷**。
 * 台词在 `口上・メッセージ関連/個人口上/067 Keine [慧音]/TWけね/M_KOJO_K67_イベント.ERB:2115`
 * （`IF LOCAL:1 && ARG == 7` 支内），唯一的 ARG=7 生产者 = `イベント関連/情事発覚.ERB:477`
 * 的 `KOJO_MESSAGE_SEND("EVENT",1,ARG,7,LOCAL:4)`，其闸 = `:475`
 * `IF CHK_DATENOW(CFLAG:(LOCAL:4):约会中)`；而 `CHK_DATENOW` 的本体
 * （`MOVEMENTS/物件関連/COMMON2.ERB:15-18`）是 `IF ARG == MAIN_MAP RETURNF 0`
 * `ELSE RETURNF 1` —— 即**只要某角色的 `约会中` 不等于 `MAIN_MAP` 就算"他正在约会"**。
 * `CFLAG:约会中` 又被语料自身当"现在位置的 MAPID"复用（`物件関連/SET_CMN.ERB:207` 的注释
 * 「現在はCFLAG:约会中は現在位置のMAPIDとして使われている」+ `EVENTCOMEND.ERB:165`
 * 每次外出都 `CFLAG:MASTER:约会中 = GET_MAPID(...)`），`MAIN_MAP` 是可被地图／GMF／QoL
 * 流程改写的 `#DIM SAVEDATA`（`Headers/DIM.ERH:245`）⇒ 独处也能为真。
 * 4.981 已在语料侧把同一行改成 `ARG == GET_HOME_MAP()`；引擎侧**不补**（ADR-005），
 * 本口只负责把判式用到的每个变量在玩家设备上带回来，等 4.981 升版后仍能对拍。
 *
 * 二、「遇到一个 npc，但一天结束没出现是否忘记她的选项」= 选项只存在于
 * `イベント関連/EVENTTURNEND.ERB:262-297`，前置是**两半闸**（`TALENT:RANDOM_CHARANUM:路人 == 1`
 * 与 `GETBIT(TFLAG:一日一回, 9)`），任一半为假就静跳过；`:287` 的 `路人 == 2` 还另走**无选项**
 * 的自动存档支。位由 `お出かけイベント/GO_OUT_EV01_モブ子エンカウント.ERB:112` 的
 * `SETBIT TFLAG:一日一回, 9` 置起——"没出选项"与"根本没遇到"在玩家侧不可分辨，
 * 所以需要把这两个读数随报告出网。
 *
 * 三、「起来时被约去约会 → 人消失，点归途变回来，直接到约会地点人仍失踪但显示还在约会」
 * （2026-11-01 邀请链 + 2026-11-14 起床邀请链）= 4.981 语料自身逻辑的复合表象：
 * 接受邀请（`情事発覚.ERB:678-690`）走 `SET_DATE`（`物件関連/SET_CMN.ERB:214`），
 * `:268` 无条件 `CFLAG:MASTER:约会中 = P_ID` ⇒「显示还在约会」；`:331-332` 两人
 * `CFLAG:現在位置 = ROAD_TO(P_ID) = 97 + MAPID*100`（`PLACE_拠点共通.ERB:74`）落进
 * 道中 MAPID 段被渲染过滤 ⇒「人消失」；`:238-243` 归途才把两人复位 ⇒「点归途变回来」；
 * 道中结束只由 `EVENTCOMEND2.ERB:62-77` 的 `TFLAG:约会道中` 倒计时或
 * `魔改内容/qol/qol_MAP.ERB:585-590`（按 `FLAG:约会的对象` 同步同行者）驱动。
 * 要判定这条链有没有真的落下去，必须带回的现场 = `TFLAG:约会道中`(229)、
 * `FLAG:约会的对象`(73)、MASTER 与对象的 `CFLAG:约会中/現在位置/同行`。
 *
 * ## 4.980 → 4.981 判式口径（双口径出数）
 * 4.980 的 `CHK_DATENOW`（`物件関連/COMMON2.ERB:15-18`）是 `ARG == MAIN_MAP`；
 * 4.981 已改成 `ARG == GET_HOME_MAP()`（`物件関連/COMMON2.ERB:11-18`），而
 * `GET_HOME_MAP()`（`物件関連/COMMON2.ERB:92-99`）= `GMF_GETBIT("幻想乡大地图")` 为真取
 * `GMF_HOME_MAP`，否则取 `MAIN_MAP`；`GMF_GETBIT("幻想乡大地图")` =
 * `GETBIT(GENSOKYO_MAP_FIX, 0)`（`DLC/GENSOKYO_MAP_FIX/幻想乡地图重修.ERB:414-419`）。
 * 本口**同时**出两个口径：`mismatchCount`（4.980 基准）与 `homeMismatchCount`（4.981 基准），
 * 等包升 4.981 后两条还能对拍。4.981 基准取不到时（`GENSOKYO_MAP_FIX` 未声明，
 * 或 bit0 开而 `GMF_HOME_MAP` 未声明）只放弃 4.981 口径，4.980 口径照出。
 *
 * ## 常量出处（语料坐标现由宿主布局注入，见 EratwForensicsLayout）
 * 下列 AutoConst 值曾硬编码于此，2026-11-19 外置；出处保留供溯源（改语料同步宿主布局）：
 * - `CFLAG_约会中 = 12`（`Headers/AutoConst_CFLAG.ERH:24`，别名表 `CSV/CFLAG.als:12`）
 * - `FLAG_约会的对象 = 73`（`Headers/AutoConst_FLAG.ERH:63`）
 * - `TFLAG_一日一回 = 199`（`Headers/AutoConst_TFLAG.ERH:124`）
 * - `TALENT_路人 = 300`（`Headers/AutoConst_Talent.ERH:249`）
 * - `CFLAG_現在位置 = 300`（`Headers/AutoConst_CFLAG.ERH:90`）
 * - `CFLAG_同行 = 320`（`Headers/AutoConst_CFLAG.ERH:105`）
 * - `TFLAG_约会道中 = 229`（`Headers/AutoConst_TFLAG.ERH:219-220`）
 * - `GETBIT` 的位算法 = `(值 shr 位) and 1`（spec: FunctionMethodCreator.cs:4766-4791）
 * - MASTER 的角色号走 [VariableStore] 的角色指针（spec: VariableEvaluator.cs:2728-2736，
 *   数组被禁时取 -1）——负号时该行读数降级为不可用，不抛。
 *
 * 全程只读、逐字段 `runCatching`：变量未声明（裸 store／语料没装）就当这一格没有，
 * 绝不抛、不改任何状态。隐私口径与 [com.eratw.port.core.fn.mapPoolDiagnostics] 一致：
 * 只出**数值与角色下标**，不取 NAME／CSTR 等任何字符串。
 */
data class DatingForensics(
    /** `MAIN_MAP`（`#DIM SAVEDATA`）——4.980 口径 `CHK_DATENOW` 的对照基准。 */
    val mainMap: Long?,
    /** `CHARANUM` 口径 = 角色表 count，给明细当分母。 */
    val charaNum: Int?,
    /** 满足 `CFLAG:no:约会中 != MAIN_MAP` 的角色数——4.980 语料眼里"正在约会"的人数（独处误报的分子）。 */
    val mismatchCount: Int?,
    /** 前 [ForensicsLayout.mismatchDetailLimit] 条 `no:值` 明细（与 [mismatchCount] 同生死）。 */
    val mismatchDetails: List<String>?,
    /** `GETBIT(GENSOKYO_MAP_FIX, 0)`——`GMF_GETBIT("幻想乡大地图")`，4.981 基准的开关位。 */
    val gmfMapBit: Long?,
    /** `GMF_HOME_MAP`（`#DIM SAVEDATA`）——GMF 开时的 4.981 家地图基准。 */
    val gmfHomeMap: Long?,
    /**
     * 4.981 `GET_HOME_MAP()` 的复算结果：`gmfMapBit == 1` 取 [gmfHomeMap]，否则取 [mainMap]。
     * 任一依赖读不到（开关位未声明、或开而 `GMF_HOME_MAP` 未声明）即 null——4.981 口径整段放弃。
     */
    val homeMap: Long?,
    /** 满足 `CFLAG:no:约会中 != 家MAP` 的角色数——4.981 `CHK_DATENOW` 眼里"正在约会"的人数。 */
    val homeMismatchCount: Int?,
    /** 前 [ForensicsLayout.mismatchDetailLimit] 条 `no:值` 明细（与 [homeMismatchCount] 同生死）。 */
    val homeMismatchDetails: List<String>?,
    /** `TFLAG:约会道中`——道中倒计时（>1000=按回合扣、0=已到达/未在道中）。 */
    val dateTravel: Long?,
    /** `CFLAG:MASTER:约会中`——主人公自己那份（= 现在位置 MAPID 的复用值）。 */
    val masterDate: Long?,
    /** `CFLAG:MASTER:現在位置`——道中时 = `ROAD_TO(P_ID)` = 97xx（渲染过滤段 ⇒「人消失」）。 */
    val masterPos: Long?,
    /** `CFLAG:MASTER:同行`——接受邀请时被 `情事発覚.ERB:682` 置 60 的那格。 */
    val masterTogether: Long?,
    /** `FLAG:约会的对象`——发情事暴露链传下去的对象号。 */
    val affairTarget: Long?,
    /** `CFLAG:(约会的对象):约会中`——对象为负时不读。 */
    val targetDate: Long?,
    /** `CFLAG:(约会的对象):現在位置`——「同行者没跟我走」的第一判据。 */
    val targetPos: Long?,
    /** `CFLAG:(约会的对象):同行`——`情事発覚.ERB:683` 应与 MASTER 同步置 60。 */
    val targetTogether: Long?,
    /** `RANDOM_CHARANUM`——路人子的角色号。 */
    val randomCharaNum: Long?,
    /** `TALENT:RANDOM_CHARANUM:路人`——0=不是路人，1=出选项，2=走无选项自动存档支。 */
    val mobTalent: Long?,
    /** `GETBIT(TFLAG:一日一回, 9)`——本日是否已结算过路人子（1=会问忘没忘）。 */
    val forgetBit: Long?,
) {
    /**
     * 语料侧的 `#DIM SAVEDATA` 基准（`MAIN_MAP` / `RANDOM_CHARANUM`）一个都没读到 =
     * 这行没有信息量（系统变量 `FLAG`/`CFLAG`/`TFLAG` 恒可读，会把空行填成一片 0），
     * 出网侧据此整行省略。宿主未注入布局（[ForensicsLayout.DISABLED]）也走这里。
     */
    val isEmpty: Boolean
        get() = mainMap == null && randomCharaNum == null

    /**
     * 单行渲染：缺的字段直接不出现，`明细` 空表时只留计数。
     * 标签由宿主注入（[labels]）；core 不含任何具体游戏的展示文案。
     */
    fun render(labels: ForensicsLabels): String = buildString {
        append(labels.title)
        var first = true
        fun part(text: String) {
            if (!first) append(' ')
            first = false
            append(text)
        }
        mainMap?.let { part("${labels.mainMap}=$it") }
        gmfMapBit?.let { part("${labels.gmfBit}=$it") }
        gmfHomeMap?.let { part("${labels.gmfHomeMap}=$it") }
        homeMap?.let { part("${labels.homeMap}=$it") }
        mismatchCount?.let { part("${labels.mismatch}=$it/${charaNum ?: 0}") }
        mismatchDetails?.takeIf { it.isNotEmpty() }?.let { part("${labels.detailPrefix}[${it.joinToString(",")}]") }
        homeMismatchCount?.let { part("${labels.homeMismatch}=$it/${charaNum ?: 0}") }
        homeMismatchDetails?.takeIf { it.isNotEmpty() }?.let {
            part("${labels.homeDetailPrefix}[${it.joinToString(",")}]")
        }
        dateTravel?.let { part("${labels.dateTravel}=$it") }
        masterDate?.let { part("${labels.masterDate}=$it") }
        masterPos?.let { part("${labels.masterPos}=$it") }
        masterTogether?.let { part("${labels.masterTogether}=$it") }
        affairTarget?.let { part("${labels.affairTarget}=$it") }
        targetDate?.let { part("${labels.targetDate}=$it") }
        targetPos?.let { part("${labels.targetPos}=$it") }
        targetTogether?.let { part("${labels.targetTogether}=$it") }
        randomCharaNum?.let { part("${labels.randomChara}=$it") }
        mobTalent?.let { part("${labels.mobTalent}=$it") }
        forgetBit?.let { part("${labels.forgetBit}=$it") }
    }

    /** 兼容重载：用中性默认标签（[ForensicsLabels.DEFAULT]），无 eraTW 文案。 */
    fun render(): String = render(ForensicsLabels.DEFAULT)

    companion object {
        /**
         * `CFLAG` 的 `現在位置` 元素号（eraTW `AutoConst_CFLAG.ERH:90` = 300）。
         *
         * **兼容锚，非通用知识**：保留仅为不打断既有装配点对 `DatingForensics.CFLAG_POSITION`
         * 的引用（ModDX-4 M4 witness frame 读数源）。新代码一律走 [ForensicsLayout.cflagPosition]
         * 注入；跨 era 复用时宿主提供自己的元素号。诊断本体已不再使用此常量。
         */
        @Deprecated(
            "改用 ForensicsLayout.cflagPosition 注入；此常量仅为 M4 witness 装配点兼容锚，将在其迁移后移除。",
            ReplaceWith("layout.cflagPosition"),
        )
        const val CFLAG_POSITION: Long = 300L
    }
}

/**
 * 从活 store 按注入的 [layout] 取「约会中／路人子」读数（口径见 [DatingForensics] 类头）。
 *
 * [layout] = [ForensicsLayout.DISABLED]（宿主非 eraTW）⇒ 整段 isEmpty、不读任何变量。
 * 崩溃线程上允许读到近似值（与 `GameHost` 既有诊断口同款取舍）；本函数**不持锁、不复制、
 * 不写任何变量**，逐格 `runCatching`，任一读不到只让那一格变 null。
 */
fun datingForensics(store: VariableStore, layout: ForensicsLayout): DatingForensics {
    fun read(name: String?, vararg indices: Long): Long? =
        if (name == null) null
        else runCatching { store.readInt(name, longArrayOf(*indices)) }.getOrNull()

    val mainMap = layout.mainMapVar?.let { read(it, layout.mainMapIndex) }
    val charaNum = runCatching { store.characters.count }.getOrNull()

    // 4.981 GET_HOME_MAP() 的复算：GETBIT 的位算法 = (值 shr 位) and 1。
    // 开关位读不到 ⇒ 4.981 口径整体无基准；bit 开而家地图基准未声明同理。
    val gmfMapBit = layout.gmfFixVar?.let { v ->
        read(v, 0L)?.let { (it shr layout.gmfMapBit) and 1L }
    }
    val gmfHomeMap = layout.gmfHomeMapVar?.let { read(it, 0L) }
    val homeMap = when (gmfMapBit) {
        null -> null
        0L -> mainMap
        else -> gmfHomeMap
    }

    // 判式逐字复刻语料：4.980 的 CHK_DATENOW(v) = (v != MAIN_MAP)；
    // 4.981 改成 (v != GET_HOME_MAP())。各基准读不到时只放弃该口径。
    fun mismatch(basis: Long?): Pair<Int, List<String>>? {
        val datingSlot = layout.cflagDating
        if (basis == null || charaNum == null || datingSlot == null) return null
        return runCatching {
            val details = ArrayList<String>()
            var count = 0
            for (no in 0 until charaNum) {
                val v = runCatching {
                    store.readInt(layout.cflagVar, longArrayOf(no.toLong(), datingSlot))
                }.getOrNull()
                if (v != null && v != basis) {
                    count++
                    if (details.size < layout.mismatchDetailLimit) details += "$no:$v"
                }
            }
            count to details
        }.getOrNull()
    }

    val mismatch = mismatch(mainMap)       // 4.980 口径
    val homeMismatch = mismatch(homeMap)   // 4.981 口径

    val master = runCatching { store.charaPointer(layout.masterVar) }.getOrNull()
    fun masterCflag(slot: Long?): Long? =
        if (master != null && master >= 0 && slot != null) read(layout.cflagVar, master, slot) else null
    val masterDate = masterCflag(layout.cflagDating)
    val masterPos = masterCflag(layout.cflagPosition)
    val masterTogether = masterCflag(layout.cflagTogether)

    val randomCharaNum = layout.randomCharaVar?.let { read(it, 0L) }
    val mobTalent = randomCharaNum?.let { n ->
        layout.talentMobgirl?.let { slot -> read(layout.talentVar, n, slot) }
    }
    val forgetBit = layout.tflagOncePerDay?.let { slot ->
        read(layout.tflagVar, slot)?.let { (it shr layout.forgetBit) and 1L }
    }
    val dateTravel = layout.tflagDateTravel?.let { read(layout.tflagVar, it) }
    val affairTarget = layout.flagAffairTarget?.let { read(layout.flagVar, it) }
    fun readTarget(elem: Long?): Long? =
        if (affairTarget != null && affairTarget >= 0 && elem != null)
            read(layout.cflagVar, affairTarget, elem) else null

    return DatingForensics(
        mainMap = mainMap,
        charaNum = charaNum,
        mismatchCount = mismatch?.first,
        mismatchDetails = mismatch?.second,
        gmfMapBit = gmfMapBit,
        gmfHomeMap = gmfHomeMap,
        homeMap = homeMap,
        homeMismatchCount = homeMismatch?.first,
        homeMismatchDetails = homeMismatch?.second,
        dateTravel = dateTravel,
        masterDate = masterDate,
        masterPos = masterPos,
        masterTogether = masterTogether,
        affairTarget = affairTarget,
        targetDate = readTarget(layout.cflagDating),
        targetPos = readTarget(layout.cflagPosition),
        targetTogether = readTarget(layout.cflagTogether),
        randomCharaNum = randomCharaNum,
        mobTalent = mobTalent,
        forgetBit = forgetBit,
    )
}

/**
 * 兼容重载：未注入布局 = [ForensicsLayout.DISABLED]，整段 isEmpty、零读取。
 * 供尚未迁移到宿主布局的调用点安全降级；新调用一律显式传 [layout]。
 */
fun datingForensics(store: VariableStore): DatingForensics = datingForensics(store, ForensicsLayout.DISABLED)
