package com.eratw.port.core.proc

import com.eratw.port.core.vars.VariableStore

/**
 * 「约会中／路人子」现场读数（diag-datingsync）：**只读**观察口，语义零改动。
 *
 * ## 为什么要有这个口
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
 * ## 常量出处（随包语料 4.980/4.981 的 AutoConst 头，只读事实；改语料必须同步这里）
 * - `CFLAG_约会中 = 12`（`Headers/AutoConst_CFLAG.ERH:24`，别名表 `CSV/CFLAG.als:12`）
 * - `FLAG_约会的对象 = 73`（`Headers/AutoConst_FLAG.ERH:63`，`情事発覚.ERB` 用它传对象号）
 * - `TFLAG_一日一回 = 199`（`Headers/AutoConst_TFLAG.ERH:124`）
 * - `TALENT_路人 = 300`（`Headers/AutoConst_Talent.ERH:249`）
 * - `CFLAG_現在位置 = 300`（`Headers/AutoConst_CFLAG.ERH:90`，`SET_CMN.ERB:331-332` 道中写入 97xx）
 * - `CFLAG_同行 = 320`（`Headers/AutoConst_CFLAG.ERH:105`，`情事発覚.ERB:682-683` 接受邀请时置 60）
 * - `TFLAG_约会道中 = TFLAG_デート道中 = 229`（`Headers/AutoConst_TFLAG.ERH:219-220`，
 *   `SET_CMN.ERB:336` 写入、`EVENTCOMEND2.ERB:62-77` 倒计时）
 * - `GENSOKYO_MAP_FIX`（`ERB/DLC/DLC.ERH:139`）与 `GMF_HOME_MAP`
 *   （`ERB/DLC/GENSOKYO_MAP_FIX/GENSOKYO_MAP_FIX.ERH:2`）都是 `#DIM SAVEDATA`
 * - 位号 9 = `EVENTTURNEND.ERB:262` 的 `GETBIT(TFLAG:一日一回, 9)` 与 SETBIT 侧同源
 * - `GETBIT` 的位算法 = `(值 shr 位) and 1`，与本包 `PureMethods` 的 GETBIT 同式
 *   （spec: FunctionMethodCreator.cs:4766-4791）
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
    /** 前 [MISMATCH_DETAIL_LIMIT] 条 `no:值` 明细（与 [mismatchCount] 同生死）。 */
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
    /** 前 [MISMATCH_DETAIL_LIMIT] 条 `no:值` 明细（与 [homeMismatchCount] 同生死）。 */
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
     * 出网侧据此整行省略。
     */
    val isEmpty: Boolean
        get() = mainMap == null && randomCharaNum == null

    /** 单行渲染：缺的字段直接不出现，`明细` 空表时只留计数。 */
    fun render(): String = buildString {
        append("约会现场：")
        var first = true
        fun part(text: String) {
            if (!first) append(' ')
            first = false
            append(text)
        }
        mainMap?.let { part("MAIN_MAP=$it") }
        gmfMapBit?.let { part("GMF位=$it") }
        gmfHomeMap?.let { part("GMF家MAP=$it") }
        homeMap?.let { part("家MAP=$it") }
        mismatchCount?.let { part("约会中≠MAIN_MAP=$it/${charaNum ?: 0}") }
        mismatchDetails?.takeIf { it.isNotEmpty() }?.let { part("明细[${it.joinToString(",")}]") }
        homeMismatchCount?.let { part("约会中≠家MAP=$it/${charaNum ?: 0}") }
        homeMismatchDetails?.takeIf { it.isNotEmpty() }?.let { part("家明细[${it.joinToString(",")}]") }
        dateTravel?.let { part("约会道中=$it") }
        masterDate?.let { part("MASTER约会中=$it") }
        masterPos?.let { part("MASTER现在位置=$it") }
        masterTogether?.let { part("MASTER同行=$it") }
        affairTarget?.let { part("约会的对象=$it") }
        targetDate?.let { part("对象约会中=$it") }
        targetPos?.let { part("对象现在位置=$it") }
        targetTogether?.let { part("对象同行=$it") }
        randomCharaNum?.let { part("路人号=$it") }
        mobTalent?.let { part("路人天赋=$it") }
        forgetBit?.let { part("忘记位=$it") }
    }

    companion object {
        /** `CFLAG` 的 `约会中` 元素号（`AutoConst_CFLAG.ERH:24`）。 */
        const val CFLAG_DATING: Long = 12L

        /** `CFLAG` 的 `現在位置` 元素号（`AutoConst_CFLAG.ERH:90`）。 */
        const val CFLAG_POSITION: Long = 300L

        /** `CFLAG` 的 `同行` 元素号（`AutoConst_CFLAG.ERH:105`）。 */
        const val CFLAG_TOGETHER: Long = 320L

        /** `FLAG` 的 `约会的对象` 元素号（`AutoConst_FLAG.ERH:63`）。 */
        const val FLAG_AFFAIR_TARGET: Long = 73L

        /** `TFLAG` 的 `约会道中` 元素号（`AutoConst_TFLAG.ERH:219-220`，`デート道中` 同号）。 */
        const val TFLAG_DATE_TRAVEL: Long = 229L

        /** `TFLAG` 的 `一日一回` 元素号（`AutoConst_TFLAG.ERH:124`）。 */
        const val TFLAG_ONCE_PER_DAY: Long = 199L

        /** `TALENT` 的 `路人` 元素号（`AutoConst_Talent.ERH:249`）。 */
        const val TALENT_MOBGIRL: Long = 300L

        /** 路人子结算位在 `TFLAG:一日一回` 里的位号（`EVENTTURNEND.ERB:262`）。 */
        const val FORGET_BIT: Int = 9

        /** `GENSOKYO_MAP_FIX` 里"幻想乡大地图"的位号（`幻想乡地图重修.ERB:417`）。 */
        const val GMF_MAP_BIT: Int = 0

        /** 明细最多几条——崩溃报告行长有限，12 条足够看出是不是"整池都脏"。 */
        const val MISMATCH_DETAIL_LIMIT: Int = 12
    }
}

/**
 * 从活 store 取「约会中／路人子」读数（口径见 [DatingForensics] 类头）。
 *
 * 崩溃线程上允许读到近似值（与 `GameHost` 既有诊断口同款取舍）；本函数**不持锁、不复制、
 * 不写任何变量**，逐格 `runCatching`，任一读不到只让那一格变 null。
 */
fun datingForensics(store: VariableStore): DatingForensics {
    fun read(name: String, vararg indices: Long): Long? =
        runCatching { store.readInt(name, longArrayOf(*indices)) }.getOrNull()

    val mainMap = read("MAIN_MAP", 0L)
    val charaNum = runCatching { store.characters.count }.getOrNull()

    // 4.981 GET_HOME_MAP()（COMMON2.ERB:92-99）的复算：GETBIT 的位算法 = (值 shr 位) and 1。
    // 开关位读不到 ⇒ 4.981 口径整体无基准；bit0 开而 GMF_HOME_MAP 未声明同理。
    val gmfMapBit = read("GENSOKYO_MAP_FIX", 0L)?.let { (it shr DatingForensics.GMF_MAP_BIT) and 1L }
    val gmfHomeMap = read("GMF_HOME_MAP", 0L)
    val homeMap = when (gmfMapBit) {
        null -> null
        0L -> mainMap
        else -> gmfHomeMap
    }

    // 判式逐字复刻语料：`情事発覚.ERB:474-475` 的 FOR 1..CHARANUM + CHK_DATENOW。
    // 4.980 的 CHK_DATENOW(v) = (v != MAIN_MAP)；4.981 改成 (v != GET_HOME_MAP())。
    // 各基准读不到时只放弃该口径（无基准则计数无意义）。
    fun mismatch(basis: Long?): Pair<Int, List<String>>? {
        if (basis == null || charaNum == null) return null
        return runCatching {
            val details = ArrayList<String>()
            var count = 0
            for (no in 0 until charaNum) {
                val v = runCatching { store.readInt("CFLAG", longArrayOf(no.toLong(), DatingForensics.CFLAG_DATING)) }.getOrNull()
                if (v != null && v != basis) {
                    count++
                    if (details.size < DatingForensics.MISMATCH_DETAIL_LIMIT) details += "$no:$v"
                }
            }
            count to details
        }.getOrNull()
    }

    val mismatch = mismatch(mainMap) // 4.980 口径
    val homeMismatch = mismatch(homeMap) // 4.981 口径

    val master = runCatching { store.charaPointer("MASTER") }.getOrNull()
    val masterDate = if (master != null && master >= 0) read("CFLAG", master, DatingForensics.CFLAG_DATING) else null
    val masterPos = if (master != null && master >= 0) read("CFLAG", master, DatingForensics.CFLAG_POSITION) else null
    val masterTogether =
        if (master != null && master >= 0) read("CFLAG", master, DatingForensics.CFLAG_TOGETHER) else null

    val randomCharaNum = read("RANDOM_CHARANUM", 0L)
    val mobTalent = randomCharaNum?.let { read("TALENT", it, DatingForensics.TALENT_MOBGIRL) }
    val forgetBit = read("TFLAG", DatingForensics.TFLAG_ONCE_PER_DAY)?.let {
        (it shr DatingForensics.FORGET_BIT) and 1L
    }

    val dateTravel = read("TFLAG", DatingForensics.TFLAG_DATE_TRAVEL)

    val affairTarget = read("FLAG", DatingForensics.FLAG_AFFAIR_TARGET)
    fun readTarget(elem: Long): Long? =
        if (affairTarget != null && affairTarget >= 0) read("CFLAG", affairTarget, elem) else null

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
        targetDate = readTarget(DatingForensics.CFLAG_DATING),
        targetPos = readTarget(DatingForensics.CFLAG_POSITION),
        targetTogether = readTarget(DatingForensics.CFLAG_TOGETHER),
        randomCharaNum = randomCharaNum,
        mobTalent = mobTalent,
        forgetBit = forgetBit,
    )
}
