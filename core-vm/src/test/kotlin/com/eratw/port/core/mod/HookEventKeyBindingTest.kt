package com.eratw.port.core.mod

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.modapi.HookDecl
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.ModCallArg
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.RuntimeScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 2026-11-26 B4：hook 目标键灌源改判「函数 ∪ 事件」并集后的**拼错闸哨兵**。
 *
 * 旧灌源 = `SymbolCatalog.bindings.keys`（只含非事件胜者）⇒ EventApi / EventDispatchRegistry
 * 两处 KDoc 一贯建议的「要拦截/替换事件，用 hooks 的 PREFIX + SkipOriginal」在生产装配下被
 * 拼错闸当场 IAE —— 槽建了、处理器绑不上（REVIEW-MOD-ARCH-20261123 B4）。并集后：
 *
 * - 事件键过拼错闸（本文件 ①③）；
 * - 拼错的键仍被拒并附最接近候选（②）；
 * - **并集 ≠ 放水**：过闸后是否真能绑定仍由 [HookPipeline.bind] 的「槽未声明」闸把守 ——
 *   Manifest.hooks 未声明该事件键的 hook 依旧 IAE，fail-closed 语义逐位不变（④）。
 */
class HookEventKeyBindingTest {

    private fun newKernel(): EntityKernel = EntityKernel(
        schemas = listOf(
            ComponentSchema(
                packageId = "demo",
                name = "Stats",
                fields = listOf(ComponentFieldSpec("hp", ComponentFieldType.INT)),
            ),
        ),
        relationshipSchemas = listOf(RelationshipSchema(name = "PARENT")),
        archetypes = listOf(EntityArchetype("Child", listOf("Stats"))),
    )

    /** 声明快照：声明一个**事件键**槽（EVENTFIRST/PREFIX）与一个普通函数键槽（COM300/PREFIX）。 */
    private fun newSnapshot(): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        registry.addPackage(
            ModDeclarationAdapter.toDescriptor(
                ModManifest(
                    packageId = "modA",
                    hooks = listOf(
                        HookDecl("EVENTFIRST", HookPhase.PREFIX),
                        HookDecl("COM300", HookPhase.PREFIX),
                    ),
                ),
            ),
        )
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) {
            action()
        }
    }

    private fun newContext(knownKeys: Set<String>): ModContextImpl {
        val logs = mutableListOf<String>()
        val ctx = ModContextImpl(
            packageId = "modA",
            snapshot = newSnapshot(),
            entityKernel = newKernel(),
            pkgShortId = 1,
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = FakeRuntime(),
            intVarView = { 0L },
            strVarView = { "" },
            registrationSink = { _: Registration -> },
        )
        // 生产灌源口径（app ModRuntimeAssembly.attach 的并集行）：bindings ∪ events。
        // 台架/单测直接写 internal 属性，与生产装配同一写入侧（归一在 setter 内完成）。
        ctx.knownFunctionKeys = knownKeys
        return ctx
    }

    @Test
    fun `事件键过拼错闸并绑定到已声明槽`() {
        val ctx = newContext(knownKeys = setOf("EVENTFIRST", "eventfirst", "COM300"))
        var hits = 0
        val registration = ctx.hooks.register("eventfirst", HookPhase.PREFIX) {
            hits++
            HookResult.Continue
        }
        // registrations() = **已声明槽**投影（EVENTFIRST+COM300 两个声明槽，绑定不改槽表）⇒
        // 计数恒 2；「绑定成功」用派发可观测面证明：前缀处理器真跑一次。
        assertEquals(2, ctx.hookPipeline.registrations().size, "投影 = 已声明槽快照（绑定不改槽表）")
        ctx.hookPipeline.dispatch("EVENTFIRST", emptyArray<ModCallArg>()) { ModValue.IntValue(0L) }
        assertEquals(1, hits, "声明槽上完成绑定（派发时前缀处理器真跑）")
        registration.unregister()
        ctx.hookPipeline.dispatch("EVENTFIRST", emptyArray<ModCallArg>()) { ModValue.IntValue(0L) }
        assertEquals(1, hits, "撤销后槽退回未绑定（派发不再进处理器，生命周期语义不变）")
    }

    @Test
    fun `拼错的事件键仍被拒并附最接近候选`() {
        val ctx = newContext(knownKeys = setOf("EVENTFIRST", "EVENTTRAIN"))
        val e = assertThrows<IllegalArgumentException> {
            ctx.hooks.register("EVENTFIST", HookPhase.PREFIX) { HookResult.Continue }
        }
        assertTrue(
            e.message!!.contains("EVENTFIRST"),
            "拼错键必须给出最接近候选（EVENTFIRST），实际：${e.message}",
        )
    }

    @Test
    fun `目录内但未声明槽的事件键_拼错闸放过_槽闸拒绝`() {
        val ctx = newContext(knownKeys = setOf("EVENTTRAIN"))
        val e = assertThrows<IllegalArgumentException> {
            ctx.hooks.register("EVENTTRAIN", HookPhase.PREFIX) { HookResult.Continue }
        }
        assertTrue(
            e.message!!.contains("槽未声明"),
            "并集 ≠ 放水：Manifest 未声明该事件键的 hook 仍被 HookPipeline.bind 拒，实际：${e.message}",
        )
    }

    @Test
    fun `空集口径不校验_台架与单测行为不变`() {
        val ctx = newContext(knownKeys = emptySet())
        // 无灌源（台架/单测缺省）= 闸不校验：任意键直接到「槽未声明」这道闸，不是拼错闸。
        val e = assertThrows<IllegalArgumentException> {
            ctx.hooks.register("ANYTHING", HookPhase.PREFIX) { HookResult.Continue }
        }
        assertTrue(e.message!!.contains("槽未声明"), "空集口径 = 拼错闸旁路，实际：${e.message}")
    }
}
