package com.eratw.port.core.screen

/**
 * 归约的原料行：显式帧里的一行 + 该行冻结的可点对象。
 *
 * @property serial 行的单调序号（`DisplayLine.serial`，不随删除复用）。界面侧的排序锚点。
 * @property text 该行可见原文（已由渲染层去 HTML 标签/折空白）。
 */
data class SemanticSourceLine(
    val serial: Long,
    val text: String,
    val controls: List<SemanticControl> = emptyList(),
)

/**
 * 归约的输入：一页的完整原料。
 *
 * @property epoch 由调用方填 0 即可 —— 世代由 `SemanticScreenHost` 在发布时统一盖章，
 *   归约本身不产生世代（否则「同内容同指纹」与「新一页」两件事会被混为一谈）。
 */
data class SemanticPage(
    val scope: ScreenScope,
    val request: ScreenRequest,
    val lines: List<SemanticSourceLine>,
    val epoch: Long = 0L,
)

/**
 * 页面归约器（纯函数，HYB-1 前置装置）。
 *
 * ## 三条纪律
 *
 * 1. **只做确定性判定**。目前只认两种结构：同一行上的「减/加键对」→ [SemanticStepper]，
 *    无按钮行的「键：值」分隔 → [SemanticKeyValue]。其余一律 [SemanticRow]（行 + 控件）。
 *    页签栏、状态条、地图网格这类**需要知道语料语义**的形态，引擎不猜 —— 由美化包按
 *    [ScreenScope.key] 声明，协议只负责把原料无损交出去。
 * 2. **无损**。归约后的 `canonicalText()` 必须包含每行原文；[SemanticRow] 保留全部控件。
 *    判错的代价只是「少分了一组」，不是「看不见了」。
 * 3. **确定性**。不读时钟、不读随机、不读对象身份；同输入同输出同指纹。
 *
 * 判据（测试钉住）：`SemanticScreenProtocolTest` 的四类归约 + 无损 + 确定性用例。
 */
object SemanticReducer {

    /** 减号族字形：只认**整颗按钮标签就是它**的（`－` 与 `- 1` 不是一回事）。 */
    private val DECREASE_LABELS = setOf("－", "-", "−", "–", "＜", "<", "◀", "▼", "←", "≪", "＜＜")

    /** 加号族字形（同上，整颗标签匹配）。 */
    private val INCREASE_LABELS = setOf("＋", "+", "﹢", "＞", ">", "▶", "▲", "→", "≫", "＞＞")

    /** 键值行的键长上限（字符数）：更长的「键」几乎一定是正文误判。 */
    private const val KEY_MAX_CHARS = 32

    /** 键值行的键宽上限（半角格）：`名前` = 4 格，16 格已能挡住整句正文。 */
    private const val KEY_MAX_CELLS = 16

    /** 步进器必须**恰好**两颗控件（见 [stepperOf] 的 KDoc）。 */
    private const val STEPPER_CONTROL_COUNT = 2

    /**
     * 键值行：`键：值`（全角/半角冒号都认）。
     *
     * 空白类显式写 `[\s\u3000]`：Java 正则的 `\s` **不含** 全角空格 U+3000，而语料里的
     * 键值对齐几乎全是全角空格（`名前　　ホイホイ`）。用 `\s` 会在真语料上整族漏判，
     * 只在小写测试里过 —— 这正是本类测试要拿全角空格当主用例的理由。
     */
    private val COLON_SEPARATED = Regex("^(.{1,32}?)[\\s\\u3000]*[:：][\\s\\u3000]*(.+)$")

    /** 键值行：`键　　值`（两格以上空白，含全角空格）。语料里最常见的形态。 */
    private val SPACE_SEPARATED = Regex("^(\\S.{0,31}?)[\\s\\u3000]{2,}(\\S.*)$")

    /** 末尾数值 token（步进器的当前值）。 */
    private val TRAILING_NUMBER = Regex("^(.*?)[\\s\\u3000]*([-+]?\\d+)[\\s\\u3000]*$")

    /**
     * 归约一页。空行（无文、无控件）被丢弃：它不携带任何信息，视觉留白由界面自己决定，
     * 而原版文本流仍是留白的权威来源。
     */
    fun reduce(page: SemanticPage): SemanticScreen {
        val elements = ArrayList<SemanticElement>(page.lines.size)
        for (line in page.lines) {
            reduceLine(line)?.let { elements += it }
        }
        return SemanticScreen.of(page.scope, page.epoch, page.request, elements)
    }

    /**
     * 单行归约：**一个 when 表达式、一条 return**。
     *
     * 形态优先级 = 越具体越靠前：步进器（需要两颗特定键）先于键值行（需要分隔符）先于
     * 普通行（兜底）。写成 when 而不是连续早退，是因为 detekt 的 ReturnCount 上限是 2，
     * 而这里原本有五个分支 —— 顺手把「优先级」写成了结构，比注释更可靠。
     */
    private fun reduceLine(line: SemanticSourceLine): SemanticElement? {
        val text = line.text.trimEnd()
        return when {
            text.isBlank() && line.controls.isEmpty() -> null
            else -> stepperOf(line, text) ?: nonStepperOf(line, text)
        }
    }

    private fun nonStepperOf(line: SemanticSourceLine, text: String): SemanticElement =
        if (line.controls.isEmpty()) {
            keyValueOf(text) ?: SemanticRow(text, emptyList())
        } else {
            // 同线多控件但不成「减/加对」：不猜它是页签还是指令组，原样交出去。
            SemanticRow(text, line.controls)
        }

    /**
     * 步进器判定：**恰好两颗**控件、一颗整标签属于减号族、一颗属于加号族。
     *
     * 为什么要求「恰两颗」：多一颗就不是步进器（例如 `[−] [100] [＋] [确定]` 说明这是
     * 数值编辑区而不是步进器，界面对它的处理不同）。判不准就不判，落 [SemanticRow]。
     */
    private fun stepperOf(line: SemanticSourceLine, text: String): SemanticStepper? =
        if (line.controls.size != STEPPER_CONTROL_COUNT) {
            null
        } else {
            stepperOfPair(text, line.controls[0], line.controls[1])
        }

    private fun stepperOfPair(
        text: String,
        first: SemanticControl,
        second: SemanticControl,
    ): SemanticStepper? {
        val decrease: SemanticControl
        val increase: SemanticControl
        when {
            isDecrease(first) && isIncrease(second) -> {
                decrease = first
                increase = second
            }
            isIncrease(first) && isDecrease(second) -> {
                decrease = second
                increase = first
            }
            else -> return null
        }
        val remainder = stripControls(text, listOf(decrease, increase))
        val match = TRAILING_NUMBER.matchEntire(remainder)
        return SemanticStepper(
            label = match?.groupValues?.get(1)?.trim().orEmpty(),
            value = match?.groupValues?.get(2)?.trim().orEmpty(),
            decrease = decrease,
            increase = increase,
        )
    }

    private fun isDecrease(control: SemanticControl): Boolean =
        control.label.trim() in DECREASE_LABELS

    private fun isIncrease(control: SemanticControl): Boolean =
        control.label.trim() in INCREASE_LABELS

    /**
     * 键值行判定：先认冒号，再认两格以上空白。
     *
     * 两条附加约束，专防误判：键里不许出现「：」（否则 `标题：副标题：值` 会被切错）、
     * 键长 ≤ [KEY_MAX_CHARS]。判错也只是分组错，原文仍在 [SemanticKeyValue] 的两个字段里。
     */
    private fun keyValueOf(text: String): SemanticKeyValue? = colonKeyValue(text) ?: spaceKeyValue(text)

    private fun colonKeyValue(text: String): SemanticKeyValue? =
        COLON_SEPARATED.matchEntire(text)?.let { match ->
            match.groupValues[1].trim()
                .takeIf { it.isNotEmpty() && it.length <= KEY_MAX_CHARS }
                ?.let { key -> SemanticKeyValue(key, match.groupValues[2].trim()) }
        }

    private fun spaceKeyValue(text: String): SemanticKeyValue? =
        SPACE_SEPARATED.matchEntire(text)?.let { match ->
            match.groupValues[1].trim()
                .takeIf { it.isNotEmpty() && it.toHalfWidthCells() <= KEY_MAX_CELLS }
                ?.let { key -> SemanticKeyValue(key, match.groupValues[2].trim()) }
        }
}

/**
 * 从整行原文里抠掉控件标签，得到「标签 + 数值」的剩余部分。
 *
 * 只删**一次**每个标签：语料里出现过 `[＋] ＋ 3` 这种标签与正文同字形的排法，全删会把
 * 正文吃掉。删不掉（按钮标签是 HTML 片段等）时返回原文，后果只是数值判不出（value 空串），
 * 不会丢字。
 *
 * 放文件级而非 object 内：`SemanticReducer` 的成员数已顶到 detekt `TooManyFunctions` 的
 * object 阈值（11），这类无状态纯函数挪出来才不撑爆 —— 与仓内 `ModRuntimeHost` 的同族取舍一致。
 */
private fun stripControls(text: String, controls: List<SemanticControl>): String {
    var rest = text
    for (control in controls) {
        val label = control.label.trim()
        if (label.isEmpty()) continue
        val at = rest.indexOf(label)
        if (at >= 0) rest = rest.removeRange(at, at + label.length)
    }
    return rest.trim()
}

/** 半角字符码位上界（ASCII）。宽度判定：`< 它` 半角 1 格，否则全角 2 格。 */
private const val ASCII_MAX_CELL_CODE = 0x80

/** 半角格宽度（全角算 2）。键长判据用它而不是 `length`：`名前` 是 2 个字符但占 4 格。 */
private fun String.toHalfWidthCells(): Int {
    var cells = 0
    for (ch in this) cells += if (ch.code < ASCII_MAX_CELL_CODE) 1 else 2
    return cells
}
