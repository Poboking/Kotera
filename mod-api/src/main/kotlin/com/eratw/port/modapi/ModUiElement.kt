package com.eratw.port.modapi

/**
 * 浮层元素模型（RT2 资源注入**渲染面第一刀**的契约侧）。
 *
 * 与 [UiApi.registerInteraction] 的「文本交互项」并列：mod 把**结构化元素**（当前只有图片）
 * 交给宿主浮层渲染，而不是自绘、也不是把资源名糊进 labelKey 里让壳层猜。元素只描述意图
 * （引用哪个资源名、替代文本、期望最大边长），**不携带字节、文件路径或 Bitmap** —— 字节经
 * [ModContext.resources] 的 [ModResourceApi] 按名读取，解码 / 缩放 / 占位由宿主 UI 壳负责
 * （§18.2 铁边界：mod 只见 facade，不见宿主实现）。
 *
 * ## 名字语义（与 [ModResourceApi] 同一口径）
 *
 * [ModUiElement.Image.resourceName] 是以包资源目录 `mods/rt2/<packageId>.assets/` 为根的
 * **相对路径**，只用 `/` 分隔段（如 `images/bg01.png`）。**不做静默归一**：绝对路径、
 * 反斜杠、空段、`.`/`..` 段、控制字符与冒号一律非法，构造时即抛 [IllegalArgumentException]
 * （作者错误在装载期暴露，不留到渲染期才发现读不到）。
 *
 * ## 降级纪律（宿主承诺，作者不用自己兜）
 *
 * 缺资源 / 空文件 / 超单文件上限 / 非支持格式 / 解码失败 / 解码期内存不足 —— 宿主**一律不崩**
 * 设置页与游戏面：渲染 [ModUiElement.Image.altText] 或占位框并记 WARN。本模型不提供
 * 「保证渲染成功」的语义，作者不应依赖图片一定上屏。
 *
 * ## 尺寸建议
 *
 * 浮层是**覆盖层**、不是主画面：图片应离线处理到 [ModUiElement.Image.SUGGESTED_SIDE_PX] 以内
 * 随包发布。资源面单文件 4 MiB 是**硬闸**，不是目标尺寸；超大图即便侥幸进包也会拖慢解码。
 * [ModUiElement.Image.maxSidePx] 是渲染侧上限提示（缩放后不超过此边长），不是质量承诺。
 */
sealed interface ModUiElement {

    /**
     * 图片元素：按**名字**引用本包资源面里的图片字节。
     *
     * @property resourceName 包资源目录为根的相对路径（名字语义见类型 KDoc）。
     * @property altText 渲染失败时的替代文本（同时作无障碍描述）；空串 = 只渲染占位框。
     * @property maxSidePx 渲染侧期望的最大边长（像素）；null = 不给上限（仍受宿主默认策略约束）。
     *   非 null 时必须落在 `1..[MAX_SIDE_PX]`。
     */
    data class Image(
        val resourceName: String,
        val altText: String = "",
        val maxSidePx: Int? = null,
    ) : ModUiElement {

        init {
            require(isValidModUiResourceName(resourceName)) {
                "浮层图片资源名非法：[$resourceName]（只许 / 分隔的相对路径；禁前导/尾随斜杠、" +
                    "绝对路径与盘符、反斜杠、空段、点段与 .. 段、控制字符）"
            }
            require(altText.length <= MAX_ALT_TEXT_CHARS) {
                "浮层图片替代文本超长：${altText.length} > $MAX_ALT_TEXT_CHARS"
            }
            require(maxSidePx == null || maxSidePx in 1..MAX_SIDE_PX) {
                "浮层图片 maxSidePx 越界：[$maxSidePx]（允许 1..$MAX_SIDE_PX 或 null）"
            }
        }

        companion object {
            /** 替代文本字符数上限（防作者塞长文撑爆浮层布局）。 */
            const val MAX_ALT_TEXT_CHARS: Int = 200

            /** 渲染侧最大边长上限（像素）：再大也不按作者要求放大到浮层里。 */
            const val MAX_SIDE_PX: Int = 4096

            /** 建议随包发布的边长（像素）：浮层是覆盖层，超出即浪费内存与解码时间。 */
            const val SUGGESTED_SIDE_PX: Int = 512
        }
    }
}

/**
 * 图片元素构造入口（DSL 糖；与 [ModUiElement.Image] 构造器同一套校验）。
 *
 * 只做「意图声明」，不读资源、不碰磁盘：本包资源目录里没有该名字时，宿主渲染替代文本
 * 或占位框并记 WARN，不会抛异常。
 */
fun modUiImage(
    resourceName: String,
    altText: String = "",
    maxSidePx: Int? = null,
): ModUiElement.Image = ModUiElement.Image(resourceName = resourceName, altText = altText, maxSidePx = maxSidePx)

/** 名字合法性：`/` 分隔的相对路径，禁一切可逃逸 / 跨平台的写法（与资源面同口径，不归一）。 */
private fun isValidModUiResourceName(name: String): Boolean =
    hasPortableRelativeShape(name) && name.split('/').all { segment -> isPortableNameSegment(segment) }

/** 整体形状：非空、无反斜杠、无前导/尾随斜杠（不是 if 条件，规则不拦长布尔链）。 */
private fun hasPortableRelativeShape(name: String): Boolean =
    name.isNotEmpty() && !name.contains('\\') && !name.startsWith('/') && !name.endsWith('/')

/** 单段形状：非空、不是点段、字符全部可打印。 */
private fun isPortableNameSegment(segment: String): Boolean =
    segment.isNotEmpty() && segment != "." && segment != ".." && segment.all { isAllowedModUiNameChar(it) }

/** 可打印、非 DEL、非冒号：冒号放开会引入 Windows 盘符与备用数据流写法。 */
private fun isAllowedModUiNameChar(ch: Char): Boolean =
    ch.code >= FIRST_PRINTABLE_CHAR_CODE && ch.code != DELETE_CHAR_CODE && ch != ':'

/** 名字里第一个可打印字符的码位（0x20 = 空格）：更小的都是控制字符。 */
private const val FIRST_PRINTABLE_CHAR_CODE = 0x20

/** DEL 控制字符码位（0x7F）：可打印区之后、但同样不可作为资源名。 */
private const val DELETE_CHAR_CODE = 0x7F
