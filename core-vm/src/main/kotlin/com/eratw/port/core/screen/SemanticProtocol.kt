package com.eratw.port.core.screen

/**
 * 语义拦截协议版本（HYB-1 前置装置）。
 *
 * ## 这是什么，不是什么
 *
 * 它是**引擎与界面之间的第二种契约**：原版【文本流 + 按钮值】之外，再给出一份
 * 「这一页由哪个 ERB 函数产生、在等什么、屏幕上哪些东西可点、每个可点对象提交什么值」
 * 的结构化描述。它**不替代**文本流，也不解释语气、不猜布局语义 —— 屏幕内容仍以原版
 * 文本为权威，本协议只是同一份事实的结构化投影。
 *
 * 判据（缺一不成立）：
 * 1. **兜底等价**：未适配的屏永远走原版文本流；本协议缺席时渲染结果逐字节不变。
 * 2. **无损**：归约后的 [SemanticScreen.canonicalText] 必须包含每一行原文与每一颗按钮的
 *    提交值（见 `SemanticProtocolTest` 的无损用例）。归约只做**分组**，不做裁剪。
 * 3. **确定性**：同一份页面原料必然得到同一 [SemanticScreen.fingerprint]（不依赖
 *    `hashCode`、不依赖地图/对象身份），否则无法对拍、无法做 ABI 指纹。
 * 4. **不隐藏等待点**：等待形态是协议的一等字段（[ScreenRequest]）。任何界面都不得
 *    让调用方看不出「这里会让出控制权」。
 *
 * ## 与既有接缝的关系
 *
 * 原料来自 [com.eratw.port.core.host.LineBufferConsoleSink]：`frameSnapshot()`（已发布帧的
 * 显示行）+ `publishedOptions()`（等待点冻结的可点集合）+ 等待请求。归约本身是纯函数
 * （`SemanticReducer`），不碰 VM、不碰 Android、不碰 Compose。
 *
 * 版本演进纪律：字段只增不改义；[SEMANTIC_PROTOCOL_VERSION] 变化时消费方必须显式比对，
 * 不许按「差不多」解析。
 */
const val SEMANTIC_PROTOCOL_VERSION: Int = 1

/**
 * 屏幕作用域：这一页是谁印出来的。
 *
 * @property functionKey 归一化函数键（大写 ROOT 口径，与 `activeVm.currentFunctionKey` 同源）。
 *   空串 = 未知（[UNSCOPED]）——归约照常进行，但接手方应当拒绝只认已知作用域的美化。
 * @property depth 同名函数的调用深度（递归/重入时为非 0）。仅用于把同键的两页区分开，
 *   不参与内容指纹。
 */
data class ScreenScope(
    val functionKey: String,
    val depth: Int = 0,
) {
    /** 作用域身份串：消费方拿它当「这一屏能不能被接管」的唯一键。 */
    val key: String get() = if (depth == 0) functionKey else "$functionKey#$depth"

    companion object {
        /** 未知作用域（作用域读数未接线时的显式取值，不是空指针）。 */
        val UNSCOPED: ScreenScope = ScreenScope("", 0)
    }
}

/** 等待形态：等什么、超时之后推不推进（协议副本，不直接暴露语句层枚举）。 */
enum class ScreenForm { VALUE, ENTER_KEY, ANY_KEY, VOID, ANY_VALUE, PRIMITIVE_MOUSE_KEY }

/** 取值通道：交付写 `RESULT`（整数）还是 `RESULTS`（字符串）。 */
enum class ScreenValueType { INT, STR }

/**
 * 一次等待请求的协议描述。
 *
 * @property defaultText 默认值文本；null = 本次等待**没有**默认值（原版挂钩：INT 无默认值时
 *   超时死等，见 `InputRequest` 的 KDoc）。空串是合法默认值，与 null 不同义。
 * @property timeLimitMs 限时毫秒；负数 = 不限时。
 * @property acceptsMouse 第 2 参/宿主接线是否接受鼠标事件（原版 `mouseInput`）。
 */
data class ScreenRequest(
    val form: ScreenForm,
    val valueType: ScreenValueType? = null,
    val oneInput: Boolean = false,
    val defaultText: String? = null,
    val timeLimitMs: Long = -1L,
    val acceptsMouse: Boolean = false,
) {
    /**
     * 本次等待是否**收值**。false 时任何界面控件都不应当提交（原版 Void 形态下键盘提交
     * 整体早退），`SemanticScreenHost.press` 据此直接拒绝。
     */
    val acceptsValue: Boolean get() = form == ScreenForm.VALUE || form == ScreenForm.ANY_VALUE

    internal fun canonical(): String = buildString {
        append(form.name).append('/')
        append(valueType?.name ?: "-").append('/')
        append(if (oneInput) "1" else "0").append('/')
        append(defaultText?.let { "d$it" } ?: "-").append('/')
        append(timeLimitMs).append('/')
        append(if (acceptsMouse) "1" else "0")
    }
}

/**
 * 一颗可点对象的协议引用。
 *
 * @property controlId 帧内稳定身份。`b<groupId>` = 引擎按钮身份（同一颗按钮折行/跨 `<br>`
 *   重放仍是同一个 id）；`v<提交值>` = 身份未知时的退化口径。**id 不是行号、不是下标**：
 *   行增删不会让玩家点到别的东西。
 * @property label 屏上印的字（已去 HTML 标签/已折空白）。
 * @property submitValue 点它要提交给输入队列的文本。**必须取按钮对象自己的值**，不许从
 *   标签里的 `[N]` 前缀反推：原版允许印 `[27]` 提交 `227`（地图 `MAIN_MAP*100+LOCAL`）。
 * @property ordinal 数值按钮的序号徽标（仅标签前缀与值一致且 0..999 时非 null；诊断/显示用，
 *   不参与提交）。
 * @property colorRgb 按钮打印时的显式前景色；null = 未设。
 * @property swatch 是否色块字形（引擎判定，见 `OptionVisual`）。
 */
data class SemanticControl(
    val controlId: String,
    val label: String,
    val submitValue: String,
    val ordinal: Int? = null,
    val colorRgb: Int? = null,
    val swatch: Boolean = false,
) {
    internal fun canonical(): String = buildString {
        append(controlId).append('=').append(label).append("->").append(submitValue)
        append('/').append(ordinal ?: "-")
        append('/').append(colorRgb?.let { Integer.toHexString(it) } ?: "-")
        append('/').append(if (swatch) "S" else "T")
    }
}

/**
 * 归约后的一个屏幕元素。
 *
 * 只有四种形态，刻意保守：**能确定性判定的才归约**，判不出的原样落 [SemanticRow]
 * （「行 + 该行可点对象」是无损底板）。不设「页签栏」「状态条」这类需要猜语义的形态 ——
 * 那种映射由美化包按作用域声明，不由引擎臆断。
 */
sealed interface SemanticElement {
    /**
     * 规范化形态：用于对拍与指纹。分隔符与转义口径见 [SemanticScreen.canonicalText]；
     * **必须无损**（原文与提交值都在里面）。
     */
    fun canonical(): String
}

/**
 * 一行文字（可带该行的可点对象）。**这是归约的无损底板**：任何没有被更具体形态认领的行
 * 都落在这里，原文与全部控件原样保留。
 */
data class SemanticRow(
    val text: String,
    val controls: List<SemanticControl>,
) : SemanticElement {
    override fun canonical(): String = "R|" + text + "|" + controls.joinToString(",") { it.canonical() }
}

/** 键值行（`名前　：　ホイホイ` 形态）。判据见 `SemanticReducer`；只是提示，不是权威。 */
data class SemanticKeyValue(
    val key: String,
    val value: String,
) : SemanticElement {
    override fun canonical(): String = "K|" + key + "|" + value
}

/**
 * 步进器（`体力 100 [－][＋]` 形态）：同一行上恰好一颗减键 + 一颗加键。
 *
 * @property label 去掉两颗键与末尾数值之后剩下的标签；可能为空。
 * @property value 末尾数值原文；判不出数值时为空串（此时不要当成 0 显示）。
 * @property decrease / increase 推一步的控件；缺一侧为 null（不会同时为 null）。
 */
data class SemanticStepper(
    val label: String,
    val value: String,
    val decrease: SemanticControl?,
    val increase: SemanticControl?,
) : SemanticElement {
    override fun canonical(): String = "S|" + label + "|" + value + "|" +
        (decrease?.canonical() ?: "-") + "|" + (increase?.canonical() ?: "-")
}

/**
 * 一组同线的按钮（不含步进器判据的那一族）。用于「页签组 / 选项组 / 指令组」这类
 * 引擎只知『它们是一组』而不知『它们是什么』的形态。
 */
data class SemanticButtonGroup(
    val label: String,
    val controls: List<SemanticControl>,
) : SemanticElement {
    override fun canonical(): String = "G|" + label + "|" + controls.joinToString(",") { it.canonical() }
}

/**
 * 一屏的结构化投影。
 *
 * @property epoch 世界世代：**只在内容变化时**推进（见 `SemanticScreenHost.offer`）。界面点按
 *   必须带自己看到的那一代，世代不符即拒绝 —— 这是「陈旧帧不许写回」的唯一保险。
 * @property controls 平铺的全部可点对象（= elements 里出现过的控件的并集，按首次出现序）。
 *   给「不做归约、只按值点」的简单界面用，与 [elements] 同一份事实。
 * @property fingerprint 内容指纹（含协议版本、作用域、等待请求、全部元素）。同指纹 = 同一屏。
 */
data class SemanticScreen(
    val scope: ScreenScope,
    val epoch: Long,
    val request: ScreenRequest,
    val elements: List<SemanticElement>,
    val controls: List<SemanticControl>,
    val fingerprint: String,
) {
    /** 协议版本不参与相等/指纹（指纹自带版本前缀），故用只读属性而非构造参数。 */
    val protocolVersion: Int get() = SEMANTIC_PROTOCOL_VERSION

    /**
     * 规范化全文：用于对拍与指纹。分隔符取不可打印控制字符，避免与语料正文撞。
     *
     * **无损判据的载体**：每一行原文、每一颗按钮的提交值都出现在这里。
     */
    fun canonicalText(): String = buildString {
        append("v").append(SEMANTIC_PROTOCOL_VERSION).append(SEP)
        append(scope.key).append(SEP)
        append(request.canonical()).append(SEP)
        for (element in elements) append(element.canonical()).append(SEP)
    }

    companion object {
        private const val SEP: Char = '\u001F'

        /**
         * 唯一构造入口：指纹由内容推导，调用方无法伪造（避免「指纹与内容分叉」这一类事故）。
         */
        fun of(
            scope: ScreenScope,
            epoch: Long,
            request: ScreenRequest,
            elements: List<SemanticElement>,
        ): SemanticScreen {
            val flat = ArrayList<SemanticControl>()
            val seen = HashSet<String>()
            for (element in elements) {
                for (control in controlsOf(element)) {
                    if (seen.add(control.controlId)) flat += control
                }
            }
            val draft = SemanticScreen(scope, epoch, request, elements, flat, "")
            return draft.copy(fingerprint = Fnv1a64.of(draft.canonicalText()))
        }

        private fun controlsOf(element: SemanticElement): List<SemanticControl> = when (element) {
            is SemanticRow -> element.controls
            is SemanticButtonGroup -> element.controls
            is SemanticStepper -> listOfNotNull(element.decrease, element.increase)
            is SemanticKeyValue -> emptyList()
        }
    }
}

/**
 * 确定性指纹（FNV-1a 64）。不用 `hashCode`：String.hashCode 是 32 位、碰撞概率不足以支撑
 * 「同指纹 = 同一屏」的判定，且历史上已出现过拿它当身份位的欠账（见语句层行身份位 KDoc）。
 */
internal object Fnv1a64 {
    /** 十六进制基数（`Long.parseUnsignedLong` / `toUnsignedString` 的 radix 参数）。 */
    private const val HEX_RADIX: Int = 16

    /** 指纹定宽：64 位十六进制 = 16 个字符，定宽才能直接当字符串比较/排序。 */
    private const val HEX_WIDTH: Int = 16

    /** FNV-1a 64 的 offset basis（0xcbf29ce484222325，超出 Long 有符号范围故用无符号解析）。 */
    private val OFFSET_BASIS: Long = java.lang.Long.parseUnsignedLong("cbf29ce484222325", HEX_RADIX)

    /** FNV-1a 64 的质数（0x100000001b3）。 */
    private const val PRIME: Long = 1099511628211L

    fun of(text: String): String {
        var hash = OFFSET_BASIS
        for (ch in text) {
            hash = hash xor ch.code.toLong()
            hash *= PRIME
        }
        return java.lang.Long.toUnsignedString(hash, HEX_RADIX).padStart(HEX_WIDTH, '0')
    }
}
