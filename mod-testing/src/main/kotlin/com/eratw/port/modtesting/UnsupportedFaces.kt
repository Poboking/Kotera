package com.eratw.port.modtesting

import com.eratw.port.modapi.ActionApi
import com.eratw.port.modapi.ActionConstraint
import com.eratw.port.modapi.ActionHandler
import com.eratw.port.modapi.ActionProvider
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.FunctionApi
import com.eratw.port.modapi.ModFunction
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.RosterProjectionApi

/**
 * 台架未覆盖的能力面统一 fail-closed：**抛 `UnsupportedOperationException`，不返回假值**。
 *
 * 为什么不通通给空实现：空实现会让作者用例在台架上「绿」，但同一份 mod 装进真引擎却因为面没接线
 * 而崩——这正是本台架要消灭的假绿。作者真需要这些面时，用 `:core-vm` 的宿主级装配（照
 * `mod-family` 既有用例），别在台架上偷看引擎内部。
 *
 * ModDX4-B6 起另有第三条路（不推翻 fail-closed 缺省）：经 [BenchFaces]（`runMod` /
 * [TestWorld.newContext] 的可选参数）把某个面换成作者自备实现；不注 = 本文件桩行为逐位不变。
 *
 * 界面贡献面（`roster` 之外的 `ui`）不在本文件：它已从 fail-closed 升级为录制型替身
 * [RecordingUi]（生产登记表本就支持交互项与可见性谓词，台架此前是缺口），见该类型 KDoc。
 */
internal fun unsupported(feature: String): Nothing = throw UnsupportedOperationException(
    "mod-testing 台架未实现 $feature（不在实体/关系/查询/存储最小闭环内）；" +
        "需要该能力面请改用 :core-vm 宿主级装配",
)

/** 角色槽投射面桩（缺省桩；可经 [BenchFaces.roster] 注入自备实现。投射是真内核职责，台架不伪造行号）。 */
internal class TestRoster : RosterProjectionApi {

    override fun project(id: EntityId): Int = unsupported("roster.project")

    override fun unproject(id: EntityId): Boolean = unsupported("roster.unproject")

    override val rosterGeneration: Long get() = unsupported("roster.rosterGeneration")

    override fun charIndexOfChecked(id: EntityId, expectedGeneration: Long): Int =
        unsupported("roster.charIndexOfChecked")
}

/** 决策/仲裁面桩（缺省桩；可经 [BenchFaces.actions] 注入自备实现，典型来源 = core-vm 真仲裁器）。 */
internal class TestActions : ActionApi {

    override fun provide(domain: String, provider: ActionProvider): Registration =
        unsupported("actions.provide")

    override fun on(handlerKey: String, handler: ActionHandler): Registration = unsupported("actions.on")

    override fun constraint(key: String, predicate: ActionConstraint): Registration =
        unsupported("actions.constraint")
}

/** ERB 可调函数面桩（缺省桩；可经 [BenchFaces.functions] 注入自备实现）。 */
internal class TestFunctions : FunctionApi {

    override fun register(name: String, function: ModFunction) = unsupported("functions.register")

    override fun override(name: String, function: ModFunction) = unsupported("functions.override")

    override fun has(name: String): Boolean = unsupported("functions.has")
}

