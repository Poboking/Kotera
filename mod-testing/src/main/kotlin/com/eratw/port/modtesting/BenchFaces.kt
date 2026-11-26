package com.eratw.port.modtesting

import com.eratw.port.modapi.ActionApi
import com.eratw.port.modapi.FunctionApi
import com.eratw.port.modapi.RosterProjectionApi

/**
 * 台架 face 注入口（ModDX4-B6）：把三个 fail-closed 桩面（[TestActions] / [TestRoster] /
 * [TestFunctions]）按面**可选**换成调用方自备实现，经 [TestWorld.newContext] / `runMod`
 * 的可选参数传入。
 *
 * ## 用法与缺省
 *
 * 三个字段互不强制：只给 [actions] = 只换 actions，[roster] / [functions] 维持桩。
 * 缺省构造（全 null）或干脆不传 = 三面全部维持桩，台架行为与注入口引入前**逐位一致**——
 * 桩仍是缺省语义，本类型只开「作者自备」这条路，不推翻 fail-closed 设计（假绿理由见
 * [UnsupportedFaces] 顶部 KDoc）。
 *
 * 传入的 face 实现就是作者用例里的被测面本体：台架只做委托换装，不包录制层、不代管生命周期。
 * 注册入口返回的 `Registration` 归调用方自管（生产的 `ModContextImpl.actions` 会把 Registration
 * 收进宿主 per-mod 收集器供 `onDisable` 撤销，台架装配没有宿主收集器——这是注入口与生产装配的
 * 已知差异边界）。
 *
 * ## 典型来源：core-vm 真实现
 *
 * actions 面的推荐实现 = core-vm `DecisionPipeline.of(...)` 建管线后取 `actionApi(packageId)`
 * 的包作用域 facade —— 与生产 `ModContextImpl.actions` 委托的是同一个真仲裁器：候选收集序 /
 * 约束短路 / 冷却 / 每日与每行动者限额 / 预留 / 优先级分层 + 加权抽取 / 淘汰报告全按生产语义跑
 * （示范见 mod-testing 测试源集的 `ModDx4BenchInjectTest`）。mod-testing 对 core-vm 走
 * `implementation` 不透传：作者的测试工程要复用真实现作注入面时需自行带上 `:core-vm` 依赖。
 */
class BenchFaces(
    /** 行动/事件统一仲裁面；缺省 null = 维持 [TestActions] 桩。 */
    val actions: ActionApi? = null,
    /** 角色槽投射面；缺省 null = 维持 [TestRoster] 桩。 */
    val roster: RosterProjectionApi? = null,
    /** ERB 可调函数面；缺省 null = 维持 [TestFunctions] 桩。 */
    val functions: FunctionApi? = null,
)
