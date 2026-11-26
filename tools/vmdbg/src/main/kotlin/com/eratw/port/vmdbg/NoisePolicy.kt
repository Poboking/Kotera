package com.eratw.port.vmdbg

/**
 * sweep 已知噪声判定（CONTRACT-DBG-TOOLING §4：summary 分层）。
 *
 * 背景：历轮全量 sweep（11 万调用/轮）的失败 TOP 长期被少数几类**结构性噪声**占据——
 * 它们是 sweep 驱动方式（按函数表裸跑 + 伪造空实参 + 零角色状态）的必然产物，
 * 不是语料或引擎的虫，每轮都要人肉从 TOP 榜里剔除。本判定把它们单独分层：
 * summary 里「已知噪声」只累计计数不占 TOP 榜位，fresh 线索一目了然。
 *
 * 三族模式的文案骨架取自 core-vm 的 verbatim 报错文案（数字段用 `\d+` 通配，
 * 引号/括号按照抄纪律保持半角；匹配对象是 sweep 记录的原始 msg，不是归一化后的）：
 * - 变量下标越界族（伪造实参命中数组但下标越界）：`LangKey.OoRVarArg` /
 *   `LangKey.OoRCharaVarArg`（VarErrorText.kt:44/:51）；
 * - REF 形参无法伪造，未绑定即抛：`LangKey.EmptyRefVar`（VarErrorText.kt:62）；
 * - #FUNCTION(S) 方法按口径拒绝裸 CALL：`ProcessRuntime.methodCallError`
 *   （ProcessRuntime.kt:3599；模板含官方拼写错误 FUCNTION，ADR-005 原样）。
 *
 * 判定只认 `cls == "CodeEE"`（三族都是 CodeEE；STEP_LIMIT/WALL_LIMIT 等有界重负载
 * 不在噪声层——它们本来就是真线索重点桶）。模式命中用 [Regex.containsMatchIn]：
 * CodeEE 文案前后可能带调用栈上下文补充。
 *
 * 扩展纪律：新噪声模式必须先带「它为何是驱动方式的必然产物而非虫」的论证再进列表，
 * 且要有 sweep 实测数据佐证（msg 样例进单测）；不许拿「不想看」当理由把真线索拉黑。
 */
object NoisePolicy {

    /** 已知噪声的 msg 模式（正则，匹配 sweep 记录的原始 msg）。 */
    val PATTERNS: List<Regex> = listOf(
        // LangKey.OoRVarArg zhs：数组变量"{0}"的第{1}参数({2})超出数组范围
        Regex("数组变量\"[^\"]+\"的第\\d+参数\\(\\d+\\)超出数组范围"),
        // LangKey.OoRCharaVarArg zhs（「第{1}引数」官方误译作「第{1}个变量」，原样）：
        // 角色数组变量"{0}"的第{1}个变量({2})超出角色登记编号的最大范围
        Regex("角色数组变量\"[^\"]+\"的第\\d+个变量\\(\\d+\\)超出角色登记编号的最大范围"),
        // LangKey.EmptyRefVar zhs：引用类型变量"{0}"缺少引用对象
        Regex("引用类型变量\"[^\"]+\"缺少引用对象"),
        // ProcessRuntime.methodCallError zhs：对标记为#FUCNTION(S)的函数"@{0}"({1}:第{2}行)进行CALL调用
        Regex("对标记为#FUCNTION\\(S\\)的函数\"@[^\"]+\"\\([^)]*第\\d+行\\)进行CALL调用"),
    )

    /**
     * 该失败记录是否为已知噪声。[cls] 是 sweep 记录的异常类名（CodeEE 及其子类），
     * [msg] 是原始异常消息（未归一化）。
     */
    fun isKnownNoise(cls: String, msg: String): Boolean =
        cls == "CodeEE" && PATTERNS.any { it.containsMatchIn(msg) }
}
