package com.eratw.port.core.mod

import com.eratw.port.core.patch.BuiltinPhases

/**
 * 回合边界相位时钟（§20.2 宿主接线；纯 JVM，状态挂实例，无静态可变全局）。
 *
 * 唯一游戏日权威仍是 `DAY`（VariableCode.kt:34 的 `gInt1[0]`：ERB 脚本推进、节拍只读）。
 * 官方 C# 侧没有「游戏相位」这一概念 —— 日始/日终/动作点是移植侧为 mod 抽象出的扩展，
 * 因此「谁决定 phase」必须由宿主显式回答（旧「空相位是裁定」的反向证明见
 * `ModRuntimeAssemblyWireTest` 中断言任务照跑的用例）：本类只从权威时间源 `DAY`
 * 推导日级相位，不读 `TIME`——TIME 相位键由宿主另行注入（ModDX-4 M5P：`TimePhaseKey`，
 * `time.<N>` 追加进 plan）。
 *
 * 相邻两个回合边界之间 DAY 的变化翻译成内建相位集合：
 *
 * - 本会话首个边界 ⇒ 日始 `DAY_START`；
 * - DAY 前进（跨日）⇒ 日终 `DAY_END` + 日始 `DAY_START`；
 * - DAY 不变（同一日内多次边界）⇒ 动作点 `ACTION`；
 * - DAY 回退（新局复位 / 读档回退）⇒ 新时间线只开日始 `DAY_START`，不补上一时间线的日终。
 *
 * 相位选择只依赖「水位日」与当前日的大小关系，同一输入可重放（确定性）。
 * 日内的时辰/回合内计时器不在本类职责内——`TIME` 相位键已由宿主接线（ModDX-4 M5P），
 * 本类仍只答「日级相位是什么」。
 * TODO(verify) 正式相位语义（日终/日始的先后、跨日是否漏过一次日终）待与 ERB 回合边界
 * 脚本（eraTW 的 `EVENTFIRST` / `EVENTEND` 调用序）对拍确认，本类只保证「每边界至少
 * 一个可观测相位、日变必给日终」。
 */
class TurnPhaseClock {
    /** 上一次边界观察到的 DAY；null = 本时间线尚未有边界。 */
    private var lastDay: Long? = null

    /** 新局 / 读档：时间线重开，下一个边界重新从日始开始。 */
    fun reset() {
        lastDay = null
    }

    /** 给一个边界的游戏日算相位计划（调用即推进水位；同一日重复调用返回 ACTION）。 */
    fun plan(day: Long): List<String> {
        val previous = lastDay
        lastDay = day
        return when {
            previous == null -> listOf(BuiltinPhases.DAY_START)
            day > previous -> listOf(BuiltinPhases.DAY_END, BuiltinPhases.DAY_START)
            day == previous -> listOf(BuiltinPhases.ACTION)
            else -> listOf(BuiltinPhases.DAY_START)
        }
    }
}
