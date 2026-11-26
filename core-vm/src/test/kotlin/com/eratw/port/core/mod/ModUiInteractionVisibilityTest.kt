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
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.HookDecl
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DX-F1 交互项可见性谓词闸：`registerInteraction` 的 `visible` 谓词在宿主查询/命中时**现求值**。
 *
 * 钉住五件事：
 * 1. 缺省两参调用（尾随 lambda，不传谓词）= 恒可见 —— 既有调用零改动语义；
 * 2. 谓词真 = 在快照里且点击命中；谓词假 = 不在快照里且点击不命中（回调不跑）；
 * 3. 谓词抛异常按「不可见 + WARN」降级：不崩、落一条 WARN 到宿主日志面；
 * 4. unregister 之后谓词不再被求值（撤销即彻底退出判定面）；
 * 5. 同一项随谓词翻转反复显隐（求值时机是查询/命中瞬间，不是注册瞬间）；
 *    宿主只读快照（ModUiHostQuery）透出的就是过滤后的集合，未知包返回空表。
 */
class ModUiInteractionVisibilityTest {

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

    private fun newSnapshot(vararg packages: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        for (pkg in packages) {
            registry.addPackage(
                ModDeclarationAdapter.toDescriptor(
                    ModManifest(
                        packageId = pkg,
                        hooks = listOf(HookDecl("COM300", HookPhase.PREFIX)),
                        interactionHooks = listOf(InteractionHookDecl(InteractionPhase.PRE)),
                        jobs = listOf(JobDecl("j1", phase = "DAY_END", firstDay = 0, everyDays = 1)),
                    ),
                ),
            )
        }
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) = action()
    }

    private fun newContext(
        packageId: String,
        logs: MutableList<String> = mutableListOf(),
    ): ModContextImpl = ModContextImpl(
        packageId = packageId,
        snapshot = newSnapshot(packageId),
        entityKernel = newKernel(),
        pkgShortId = 1,
        nativeMethods = NativeMethodRegistry(),
        logSink = { logs += it },
        runtime = FakeRuntime(),
    )

    private fun newHost(
        vararg packages: String,
        logSink: (String) -> Unit = {},
    ): ModRuntimeHost = ModRuntimeHost(
        snapshot = newSnapshot(*packages),
        entityKernel = newKernel(),
        nativeMethods = NativeMethodRegistry(),
        logSink = logSink,
        runtime = FakeRuntime(),
    )

    @Test
    fun `缺省两参尾随 lambda 调用恒可见且点击命中（既有调用零改动语义）`() {
        val ctx = newContext("modA")
        var hits = 0
        // 既有调用形态：只传 labelKey + 尾随 lambda，不传谓词 —— 必须原样编译、原样恒可见。
        ctx.ui.registerInteraction("BTN") { hits++ }

        assertEquals(setOf("BTN"), ctx.uiRegisteredKeys())
        assertTrue(ctx.uiClick("BTN"))
        assertEquals(1, hits)
    }

    @Test
    fun `谓词真在快照且点击命中，假不在快照且点击不命中`() {
        val ctx = newContext("modA")
        var hits = 0
        var shown = true
        ctx.ui.registerInteraction("BTN", visible = { shown }) { hits++ }

        // 真分支：在快照里、点击命中。
        assertEquals(setOf("BTN"), ctx.uiRegisteredKeys())
        assertTrue(ctx.uiClick("BTN"))
        assertEquals(1, hits)
        // 假分支：不在快照里、点击不命中、回调不跑。
        shown = false
        assertEquals(emptySet(), ctx.uiRegisteredKeys())
        assertFalse(ctx.uiClick("BTN"))
        assertEquals(1, hits)
    }

    @Test
    fun `谓词抛异常按不可见降级不崩且落 WARN`() {
        val logs = mutableListOf<String>()
        val ctx = newContext("modA", logs)
        var hits = 0
        ctx.ui.registerInteraction("BTN", visible = { throw IllegalStateException("boom") }) { hits++ }

        // 不崩：查询与命中都按「不可见」降级，坏谓词不砖 UI 面。
        assertEquals(emptySet(), ctx.uiRegisteredKeys())
        assertFalse(ctx.uiClick("BTN"))
        assertEquals(0, hits)
        // WARN 落宿主日志面（构造灌入的 logSink），点名包与键与异常，可取证。
        // 每次现求值各落一条（快照一次 + 命中一次），不去重、不攒静态状态。
        assertEquals(2, logs.size)
        for (log in logs) {
            assertTrue(log.contains("[WARN]"))
            assertTrue(log.contains("pkg=modA"))
            assertTrue(log.contains("key=BTN"))
            assertTrue(log.contains("boom"))
        }
    }

    @Test
    fun `unregister 后谓词不再被求值`() {
        val ctx = newContext("modA")
        var evals = 0
        val reg = ctx.ui.registerInteraction("BTN", visible = { evals++; true }) { }

        assertTrue(ctx.uiRegisteredKeys().contains("BTN"))
        val before = evals
        reg.unregister()

        // 撤销后快照与命中都不再触达谓词：evals 冻结在撤销前的值。
        assertEquals(emptySet(), ctx.uiRegisteredKeys())
        assertFalse(ctx.uiClick("BTN"))
        assertEquals(before, evals)
    }

    @Test
    fun `同一项随谓词翻转反复显隐且宿主快照过滤未知包返回空表`() {
        val logs = mutableListOf<String>()
        val host = newHost("modA", logSink = { logs += it })
        var gold = 0L
        val mod = object : EraMod {
            override fun onLoad(context: ModContext) {
                // 作者视角：谓词按会话状态现求值（这里用局部变量模拟 gameState 读数）。
                context.ui.registerInteraction("ENTRY", visible = { gold > 0 }) { }
                context.ui.registerInteraction("ALWAYS") { }
            }

            override fun onEnable() {}
            override fun onDisable() {}
            override fun onUnload() {}
        }

        host.runAll(listOf("modA" to mod), duringRun = {
            // gold=0：ENTRY 隐藏，ALWAYS 恒可见；宿主快照只透出过滤后的集合。
            assertEquals(setOf("ALWAYS"), host.uiInteractionKeysOf("modA"))
            gold = 5L
            // gold>0：同一项翻转后重新可见（现求值，不是注册时定死）。
            assertEquals(setOf("ALWAYS", "ENTRY"), host.uiInteractionKeysOf("modA"))
            // 未知 / 未安装的包返回空表、不抛错（查询面不制造状态）。
            assertEquals(emptySet(), host.uiInteractionKeysOf("nope"))
        })

        // runAll 收尾即 onDisable：注册项经 track 收进宿主收集器被整体撤销（既有生命周期口径），
        // 快照随之清空 —— 这里只确认查询面与登记表一致，不另断言谓词行为（已在 duringRun 内钉住）。
        assertEquals(emptySet(), host.uiInteractionKeysOf("modA"))
    }
}
