package com.eratw.port.core.proc

/**
 * 现场诊断口的**语料坐标布局**（领域无关注入面）。
 *
 * ## 为什么有这个类
 * [datingForensics] 的读数算法是通用的（读变量、按元素号取格、比基准、提位、数人数），
 * 但「读哪个变量、用哪个元素号、取哪一位」是**宿主语料的配置**，不是引擎知识。
 * 把坐标做成注入参数后：
 * - core 主源集不再出现任何具体 era 游戏的语料变量名或 AutoConst 数值（业务零耦合铁律）；
 * - 宿主不注入（[DISABLED]）⇒ 诊断口整段 isEmpty、零输出、零读取，对其他 era 游戏完全隐形；
 * - 多个 era 语料可各自提供自己的布局，共用同一套通用读数算法。
 *
 * ## 字段语义（全部是坐标，不含读数结果）
 * 每个字段对应 [DatingForensics] 的一个读数维度；`null` = 该维度在宿主语料里不存在，读数降级为 null。
 * 变量名（如 `MAIN_MAP`）与元素号（如 CFLAG 的 `约会中` 号）都由宿主按自己的语料头填写。
 *
 * @see DatingForensics 结果形状与渲染
 * @see datingForensics 通用读数算法
 */
data class ForensicsLayout(
    /** `#DIM SAVEDATA` 基准变量（4.980 `CHK_DATENOW` 对照基准，eraTW = MAIN_MAP）。 */
    val mainMapVar: String?,
    /** 主基准取值的 0 号元素下标（恒为 0，显式化避免猜）。 */
    val mainMapIndex: Long = 0L,
    /** 角色号基准变量（eraTW = RANDOM_CHARANUM），null = 无路人子语义。 */
    val randomCharaVar: String?,
    /** GMF 开关位所在变量（eraTW = GENSOKYO_MAP_FIX），null = 无 4.981 家地图口径。 */
    val gmfFixVar: String?,
    /** GMF 开时家地图基准变量（eraTW = GMF_HOME_MAP），null = 该口径放弃。 */
    val gmfHomeMapVar: String?,
    /** MASTER 角色指针变量名（一般 = MASTER）。 */
    val masterVar: String = "MASTER",
    /** `CFLAG` 变量名（era 系列通用系统变量，保留默认）。 */
    val cflagVar: String = "CFLAG",
    /** `TALENT` 变量名（era 系列通用系统变量）。 */
    val talentVar: String = "TALENT",
    /** `TFLAG` 变量名（era 系列通用系统变量）。 */
    val tflagVar: String = "TFLAG",
    /** `FLAG` 变量名（era 系列通用系统变量）。 */
    val flagVar: String = "FLAG",
    /** CFLAG 的「约会中」元素号。 */
    val cflagDating: Long?,
    /** CFLAG 的「現在位置」元素号。 */
    val cflagPosition: Long?,
    /** CFLAG 的「同行」元素号。 */
    val cflagTogether: Long?,
    /** FLAG 的「约会的对象」元素号。 */
    val flagAffairTarget: Long?,
    /** TFLAG 的「约会道中」元素号。 */
    val tflagDateTravel: Long?,
    /** TFLAG 的「一日一回」元素号。 */
    val tflagOncePerDay: Long?,
    /** TALENT 的「路人」元素号。 */
    val talentMobgirl: Long?,
    /** 忘记位在「一日一回」里的位号。 */
    val forgetBit: Int,
    /** 「幻想乡大地图」开关位在 gmfFixVar 里的位号。 */
    val gmfMapBit: Int,
    /** 明细最多几条（崩溃报告行长有限；通用展示参数，非语料坐标）。 */
    val mismatchDetailLimit: Int = 12,
) {
    companion object {
        /**
         * 禁用态：宿主未配置语料坐标时使用。
         * [datingForensics] 见此布局立即返回空结果（isEmpty=true，不读任何变量），
         * 保证非 eraTW 宿主零诊断、零读取、零 eraTW 知识。
         */
        val DISABLED = ForensicsLayout(
            mainMapVar = null,
            randomCharaVar = null,
            gmfFixVar = null,
            gmfHomeMapVar = null,
            cflagDating = null,
            cflagPosition = null,
            cflagTogether = null,
            flagAffairTarget = null,
            tflagDateTravel = null,
            tflagOncePerDay = null,
            talentMobgirl = null,
            forgetBit = 0,
            gmfMapBit = 0,
        )
    }
}
