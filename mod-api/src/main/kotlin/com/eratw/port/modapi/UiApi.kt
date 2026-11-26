package com.eratw.port.modapi

/**
 * 界面贡献能力面（RT2 Kotlin Mod SDK，era-arch-v61u.md §18.3 / §6.1 互动贡献 + render）。
 *
 * mod 经本 API 贡献菜单项/交互项，不自绘绕过引擎渲染管线（一个运行时，§9）。贡献项的点击
 * 回调经引擎输入状态机派发；mod 改游戏状态仍须经 [RuntimeScheduler.submit]（§18.5）。
 *
 * ## 文案纪律（AGENTS.md §6）
 *
 * 玩家可见文案**禁止内联字面量**：[labelKey] 不是文案本身。两条合规路：
 *
 * - 用 [ModContext.strings] 在 `onLoad` 里声明本包三语文案，[labelKey] 指向本包键 ——
 *   浮层按当前设置语言显示作者文案（推荐，第三方 mod 唯一可行路）；
 * - [labelKey] 指向宿主文案表的既有键（宿主装配方决定口径，mod 侧不该依赖）。
 *
 * 宿主解析顺序 = 本包文案 → 宿主既有呈现（`PackageId / 键` 技术标识）。未知键不崩、
 * 不显示空串，回落可见的技术标识。
 *
 * ## 接线状态（诚实边界，K1 只冻结契约）
 *
 * §6.1 互动贡献点与 render 的接线是 **K3 脏活**。本接口在 K1 冻结 mod 侧贡献契约形状；
 * 生产适配器（贡献项进引擎显示列表/命中区）待 K3。
 *
 * clean 语义（§18.7）：取干净贡献语义，不复刻官方 UI 怪癖；ADR-005 仍约束 vanilla 渲染面。
 */
interface UiApi {
    /**
     * 贡献一个交互项。[labelKey] 指向本包 [StringsApi] 声明的文案键（或宿主表键，见类型
     * KDoc 的文案纪律）；[onClick] 是点击回调（经引擎输入状态机派发）。
     * 返回的 [Registration] 可在 [EraMod.onDisable] 时撤销。
     *
     * ## 可见性谓词（DX-F1）
     *
     * [visible] 缺省 `null` = **恒可见**（既有两参调用零改动）。传入谓词后，宿主在**查询/命中
     * 时现求值**（不是注册时求一次）：mod 作者可按 [ModContext.gameState] / [ModContext.settings]
     * 动态显隐交互项（例如某变量归零后收起入口），同一项可随状态变化反复显隐。
     *
     * - 谓词返回 `false` 的项**不出现在宿主可读快照**、点击**不命中**（不是置灰，是直接不在面上）；
     * - 谓词抛异常按「不可见 + 记 WARN」降级：不崩、不让坏谓词砖掉 UI 面（与图片渲染降级同口径，
     *   WARN 走宿主日志面，非玩家可见文案）；
     * - [Registration.unregister] 之后谓词不再被求值。
     *
     * 参数序说明：[visible] 刻意放在 [onClick] 之前（onClick 保持末位），保证既有尾随 lambda
     * 写法 `registerInteraction("KEY") { ... }` 不用改（尾随 lambda 绑定末参）。
     *
     * @param visible 可见性谓词；每次宿主查询/点击现求值。`null` = 恒可见。
     */
    fun registerInteraction(
        labelKey: String,
        visible: (() -> Boolean)? = null,
        onClick: () -> Unit,
    ): Registration

    /**
     * 贡献一张**图片元素**（RT2 P1 资源注入第二刀：作者声明 → 宿主浮层渲染）。
     *
     * 与 [registerInteraction] 的文本项并列：本入口注册的是「本包资源目录里的一张图」，
     * 由宿主按 [ModUiElement.Image] 元素模型渲染；mod 仍不自绘、不绕过引擎渲染管线。
     *
     * ## 资源来源与名字规则
     *
     * [resourceName] 是本包资源根（`<packageId>.assets/`）下的 `/` 分隔相对路径，名字语义与
     * [ModResourceApi] 同口径：禁前导/尾随 `/`、禁 `//` 空段、禁 `.` 与 `..` 段、禁 `\`、
     * 禁冒号与控制字符；不归一化，非法名当场拒。建议 PNG/JPEG/WebP，边长 ≤
     * [ModUiElement.Image.SUGGESTED_SIDE_PX]，超过 [ModUiElement.Image.MAX_SIDE_PX] 的声明会被拒。
     *
     * ## 声明期校验（fail-fast，不把坏声明拖到渲染期）
     *
     * 本入口**复用** [ModUiElement.Image] 的构造校验（[modUiImage] 同一份规则）：非法
     * [resourceName]、[altText] 超过 [ModUiElement.Image.MAX_ALT_TEXT_CHARS]、[maxSidePx] 不在
     * `1..MAX_SIDE_PX` 都当场抛 [IllegalArgumentException]；异常让 onLoad/onEnable 失败时，
     * 宿主按既有错误围栏回滚该 mod 的操作，不会留下半注册的图片元素。
     *
     * ## 渲染期降级（声明合法 ≠ 图一定可用）
     *
     * 文件缺失、零字节、容器格式不支持、字节超单文件上限、解码失败或解码期内存不足时，渲染
     * 侧一律降级为 [altText] 替代文本或占位框并记 WARN，**绝不**把异常抛进 UI 线程；[altText]
     * 因此应写成「这张图是什么」，而不是装饰性文案。
     *
     * @param resourceName 本包资源目录下的相对路径（见上）；为空或含非法段即抛。
     * @param altText 替代文本；缺省空串 = 降级时用通用占位框。不计入玩家可见内联文案纪律
     *   （它不是菜单键，是异常路径的可读说明）。
     * @param maxSidePx 期望最大边长（像素，2 的幂采样后仍可能略超，渲染侧再精确缩放）；
     *   `null` = 交给渲染侧缺省策略。
     * @return 可撤销的 [Registration]：在 [EraMod.onDisable] 或不再需要时 [Registration.unregister]，
     *   撤销后宿主浮层的下一轮快照即不再包含它。
     * @throws IllegalArgumentException 声明参数非法（见「声明期校验」）。
     */
    fun registerImage(
        resourceName: String,
        altText: String = "",
        maxSidePx: Int? = null,
    ): Registration
}