package com.eratw.port.core.proc

import com.eratw.port.core.config.ConfigStore

/**
 * 装载期启动快照（`CONTRACT-RT2-CFG16` + `CONTRACT-RT2-CFG16B` /
 * `CONTRACT-RT2-SPEC-RULING.md` §1 序 16）。
 *
 * ## 为什么需要它
 *
 * 原版配置是**进程级单实例**（`Program.cs:95` 一次 `LoadConfig` +
 * `ConfigData.cs:494` `SetConfig` 写同一实例）：View 排版、脚本量宽、控制台字体/行数
 * 与 `GETCONFIG` 读的都是同一份字号/条目。移植侧曾出现两处独立读盘 —— `GameHost`
 * 读一次管版面，`GameSession` 构造时又读一次（原始条目表 + 自建配置链）—— 两次读之间
 * 配置被改写就会让同一块屏幕的度量尺与条目表分叉（按钮网格 / `GETCONFIG("フォントサイズ")`
 * 派生列数与屏上对不上；字体名 / 日志行数 / `BUTTON WRAPPING` 也可能一处新一处旧）。
 *
 * A/L7 先收口了「度量两项」：`GameHost` 在会话构造前读一次字号/行高，既喂
 * `view.applySourceFontMetrics`，又经本快照传进 [GameSession]。本波（L9B）把**同一次
 * 读取**扩展成完整快照：链后 [ConfigStore]（已 `applyChainTo` 且已做 WindowX 回填）+
 * 原始条目表（三层链 + `_Replace.csv` 合并后的 `Map<String,String>`）+ 度量两项。
 * 会话直接消费，不再自行读盘、不再自建配置链。
 *
 * ## 缺省语义（零变化）
 *
 * [configStore] / [rawEntries] 缺省（null / 空表）= 今天的 headless / 装置 / 既有测试
 * 形态：[GameSession] 按自己的 `RootedBuiltinHost` 重放配置链（并在链后自行回填
 * `WindowX`），与接线前逐位一致。只有度量两项、没有 store 的半快照（A/L7 既有用例）
 * 同样走这条缺省回落。
 *
 * @property fontSize 生效字号（`Config.cs:431-435` 的「<8 抬到 8」已由 `EffectiveConfig` 施加）
 * @property lineHeight 生效行高（不足字号已抬到字号，同 `Config.cs:436-440`）
 * @property configStore 链后会话配置表（`GameHost` 单读的同一实例：`applyChainTo` 已把三层链 +
 *   `_Replace.csv` 重放进条目表，`ConfigMethods.backfillWindowWidth` 已按 View 实测版面宽回填
 *   `WindowX`）。非 null ⇒ [GameSession] 的条目表与 `effective` 都从这一份派生，**不再读盘**；
 *   null（缺省）= 会话按今天的方式自建链。
 * @property rawEntries 原始条目表（键 = 条目标签大写英文形 + 值 = 文件原文 `Trim` 后；
 *   `RootedBuiltinHost.configOverrides` 同形）。字体名 / 日志行数 / `BUTTON WRAPPING` /
 *   `SystemSaveInBinary` 这类**按条目原文消费**的读数取它 —— 与 [configStore] 同一次读盘，
 *   两条通道不可能分叉。
 */
data class BootViewMetrics(
    val fontSize: Int,
    val lineHeight: Int,
    val configStore: ConfigStore? = null,
    val rawEntries: Map<String, String> = emptyMap(),
)
