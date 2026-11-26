package com.eratw.port.modapi

/**
 * mod 自带资源面（RT2 资源注入**第一刀**；P1 工程契约，非 ABI）。
 *
 * 作者把自己的图片 / 音频 / 数据文件随包发布在设备侧
 * `mods/rt2/<packageId>.assets/` 目录（与既有 `<packageId>.manifest` / `.settings` /
 * `.erbm` 同一层目录约定），经本面按**名字**只读取用，不持有文件路径、不碰宿主文件系统
 * （§18.2 铁边界：mod 只见 facade，不见实现）。
 *
 * ## 名字语义（本面唯一的寻址方式）
 *
 * - 名字是以包资源目录为根的**相对路径**，只用 `/` 分隔段（如 `images/bg01.png`）。
 * - 只接受**已经是规范形态**的名字；本面**不做静默归一**（不 trim、不折叠大小写、不把
 *   `//` 合并、不剥前导 `/`）。非法名字当场抛 [IllegalArgumentException]（作者错误，
 *   不是运行时缺文件）：
 *   - 绝对路径（前导 `/`，或 Windows 盘符 / 冒号）；
 *   - 含反斜杠 `\`、控制字符（`0x00`–`0x1F` 与 `0x7F`）；
 *   - 任一段为空 / `.` / `..`（所以 `a//b`、`/a`、`a/`、`a/../b` 全部拒绝）。
 * - **大小写敏感是 API 面语义**：名字按磁盘原名逐字匹配，`keys()` 原样报告磁盘上的
 *   大小写。宿主文件系统是否大小写敏感（Android/Linux 敏感，Windows 开发机不敏感）
 *   不改变本面契约，本面不额外拦截变体大小写 —— 跨平台行为以 `keys()` 的返回值为准。
 *
 * ## 读语义
 *
 * - 只读，无写面（首刀不给 mod 落盘；资源随包发布、由安装包/导入链放进来）。
 * - 每次调用**重新读盘**，不缓存字节：`open` 返回的是新副本，调用方可以随意改，
 *   不影响磁盘与本面后续调用；运行期改文件（热重载）下一次调用即可见。
 * - 缺文件不是异常：`exists` 返回 `false`、`size` / `open` 返回 `null`。
 * - 坏包 / 坏文件不炸宿主：超出宿主上限的文件、不可读的目录只记 WARN 并被跳过，
 *   调用方看到的是「该名字不存在」；整包超总量上限时本面整体禁用（`keys()` 空表、
 *   其余入口 `null` / `false`），见宿主实现说明。
 * - 只在 VM 线程调用（与其余 [ModContext] 面同口径）；本面不提供线程安全承诺。
 *
 * ## 装配状态与边界（本刀不做的事）
 *
 * 宿主在 [EraMod.onLoad] 之前注入本包视图；**未注入时 [ModContext.resources] 是显式
 * fail-closed** 桩（抛 [UnsupportedOperationException]，不返回空表兜底 —— 静默空资源会让
 * 作者以为资源已打进包）。
 *
 * 本刀**只做「按名读取字节」的存储与注入面**，明确不做：
 * - 渲染 / 音频播放接线（图片解码、纹理上传、Sound 播放属后续 lane）；
 * - `.erbm` 包内嵌资源的抽取（内嵌资源需要先解包，属后续 lane）；
 * - 目录通配枚举之外的元数据面（无 mtime / MIME / 变化订阅）。
 */
interface ModResourceApi {
    /** 本视图所属的包身份（只读；资源面按包隔离，本视图看不到别的包的资源目录）。 */
    val packageId: String

    /**
     * 本包当前可用的资源名列表（`/` 分隔的相对路径，按名字升序）。
     *
     * 只列名字、不读字节：宿主实现可以 stat 文件大小来执行上限（元数据，不是内容）。
     * 包资源目录不存在 = 空表；坏目录 / 坏文件被跳过（宿主记 WARN）。
     */
    fun keys(): List<String>

    /**
     * 名字对应的资源是否存在且可用（在 [keys] 的列表里）。
     *
     * @throws IllegalArgumentException 名字非法（见类型 KDoc 的名字语义）。
     */
    fun exists(name: String): Boolean

    /**
     * 名字对应资源的字节数；不存在或不可用时 `null`。
     *
     * @throws IllegalArgumentException 名字非法（见类型 KDoc 的名字语义）。
     */
    fun size(name: String): Long?

    /**
     * 读该资源的完整字节（新副本，调用方可随意修改）；不存在或不可用时 `null`。
     *
     * 首刀不做流式 / 局部读：资源上限压得比较小，整读的直接语义更适合作者面；
     * 大文件应以离线预处理随包发布，不要指望运行期懒加载大资产。
     *
     * @throws IllegalArgumentException 名字非法（见类型 KDoc 的名字语义）。
     */
    fun open(name: String): ByteArray?
}
