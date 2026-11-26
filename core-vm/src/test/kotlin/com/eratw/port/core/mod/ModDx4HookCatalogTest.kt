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
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.HookDecl
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modtesting.TestWorld
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * hook 可挂函数键发现面（[HookApi.knownFunctionKeys] + [HookApi.register] 拼错闸，MODDX4-B3）的
 * 生产语义闸，与 events 侧 EventApiQueryTest / EventDispatchRegistryKnownKeysTest 完全同构。
 *
 * 钉住的口径：
 * - 真源 = 链接期 `SymbolCatalog.bindings` 键集；宿主 `install` 逐 context 归一透传（onLoad 之前）；
 * - 拼错闸：目录非空时 `hooks.register` 对目录外键当场抛 IAE（原始名入文案 + 最近 3 候选，按
 *   编辑距离 → 名字序）；目录内但槽未声明仍走 `HookPipeline.bind` 的既有「槽未声明」IAE（那是
 *   漏声明不是拼错，不带候选）；
 * - 空集（缺省 / 台架 / 未灌源）= 不校验 + 查询面空集（fail-safe，先例照 events knownEventKeys）；
 * - 闸只拦注册：派发路径（FunctionHookWiring.dispatchCall）语义零改动；
 * - 台架（RecordingHooks.adoptKnownFunctionKeys）与生产的拒绝文案逐字一致（对拍）。
 */
class ModDx4HookCatalogTest {

    // ---- 生产 face（ModContextImpl.hooks）：拼错闸 ----

    @Test
    fun `拼错闸 目录外键当场拒绝并给最近候选`() {
        val context = newUnassembledContext()
        context.knownFunctionKeys = setOf("COM300", "COM310", "EVENTCOM")

        val failure = assertThrows<IllegalArgumentException> {
            context.hooks.register("COM30", HookPhase.PREFIX) { HookResult.Continue }
        }
        assertEquals(
            "未知函数键 \"COM30\"（来自 demo）：链接期 SymbolCatalog 函数目录里没有这个函数，" +
                "拼错的 hook 永远不会被派发，故绑定当场拒绝（不静默失效）" +
                "；最接近的候选：COM300, COM310, EVENTCOM",
            failure.message,
        )
    }

    @Test
    fun `拼错闸 候选至多三个按编辑距离再名字排序`() {
        val context = newUnassembledContext()
        // 与 COM30 的编辑距离：COM300/COM310/XOM30 都是 1，EVENTCOM ≥ 3 ⇒ 三个近候选占满上限，
        // 远候选不进文案（反向证明排序敏感性：若按名字序，EVENTCOM 会挤掉 XOM30）。
        context.knownFunctionKeys = setOf("COM300", "COM310", "EVENTCOM", "XOM30")

        val failure = assertThrows<IllegalArgumentException> {
            context.hooks.register("COM30", HookPhase.PREFIX) { HookResult.Continue }
        }
        assertTrue(
            failure.message!!.contains("最接近的候选：COM300, COM310, XOM30"),
            "候选序 =（编辑距离，名字）：${failure.message}",
        )
        assertFalse(failure.message!!.contains("EVENTCOM"), "远候选不得进文案：${failure.message}")
    }

    @Test
    fun `拼错闸 归一后命中_大小写与空白不误伤`() {
        val context = newUnassembledContext()
        context.knownFunctionKeys = setOf("COM300")

        // 目录键归一后命中（trim + 大写 ROOT，与 HookPipeline.canonical 同口径）→ 放行，
        // 落到既有 bind：快照已声明 COM300/PREFIX 槽 ⇒ 绑定成功。
        val registration = context.hooks.register("  com300  ", HookPhase.PREFIX) { HookResult.Continue }
        registration.unregister()
    }

    @Test
    fun `目录内但槽未声明 仍走既有槽未声明文案_不带候选`() {
        val context = newUnassembledContext()
        context.knownFunctionKeys = setOf("COM300", "COM301")

        val failure = assertThrows<IllegalArgumentException> {
            context.hooks.register("COM301", HookPhase.PREFIX) { HookResult.Continue }
        }
        // 拼错闸放行（键在目录里），槽表里没有 ⇒ HookPipeline.bind 的既有文案原样，不加候选。
        assertEquals(
            "hook 槽未声明：COM301/PREFIX/demo（先经 Manifest 声明）",
            failure.message,
        )
    }

    @Test
    fun `关闸空集恢复旧行为_无候选`() {
        val context = newUnassembledContext()
        assertTrue(context.hooks.knownFunctionKeys().isEmpty(), "未灌源 = 空集（fail-safe 不猜）")

        val failure = assertThrows<IllegalArgumentException> {
            context.hooks.register("COM30", HookPhase.PREFIX) { HookResult.Continue }
        }
        assertTrue(failure.message!!.contains("hook 槽未声明"), failure.message)
        assertFalse(failure.message!!.contains("未知函数键"), "关闸后不得出现拼错闸文案：${failure.message}")
        assertFalse(failure.message!!.contains("候选"), "关闸后不得给候选：${failure.message}")
    }

    // ---- 生产 face：只读快照 ----

    @Test
    fun `knownFunctionKeys 字典序稳定排序_独立副本`() {
        val context = newUnassembledContext()
        context.knownFunctionKeys = setOf("zeta", "ALPHA", "  beta ")

        // Set 的相等断言不钉序，用迭代序钉「字典序稳定排序」。
        assertEquals(listOf("ALPHA", "BETA", "ZETA"), context.hooks.knownFunctionKeys().toList())
        assertEquals(context.hooks.knownFunctionKeys(), context.hooks.knownFunctionKeys())

        // 调用方拿到的是独立副本：就地清空它不影响引擎状态与后续调用。
        val first = context.hooks.knownFunctionKeys()
        (first as MutableSet<*>).clear()
        assertEquals(listOf("ALPHA", "BETA", "ZETA"), context.hooks.knownFunctionKeys().toList())
        assertNotSame(first, context.hooks.knownFunctionKeys())
    }

    // ---- 宿主透传（ModRuntimeHost.install）：灌缝时序与归一 ----

    @Test
    fun `宿主 install 透传函数目录_归一后 onLoad 前就位`() {
        val logs = mutableListOf<String>()
        val host = newHost(logs, "modA")
        host.knownFunctionKeys = setOf(" com300 ", "EVENTCOM")

        host.install(listOf("modA" to NoopMod("modA")))

        val context = host.installedContexts.single()
        // 灌入的键逐个归一（trim + 大写）：宿主透传是归一点，目录键此后必为 canonical 形态。
        assertEquals(setOf("COM300", "EVENTCOM"), context.hooks.knownFunctionKeys())
        // 闸在 onLoad 之前就位（作者在 onLoad 里就可能 register）⇒ 装配完成后即生效。
        val failure = assertThrows<IllegalArgumentException> {
            context.hooks.register("EVENTCOMX", HookPhase.PREFIX) { HookResult.Continue }
        }
        assertTrue(failure.message!!.contains("未知函数键"), failure.message)
    }

    @Test
    fun `宿主未灌源 install 后不校验_旧行为逐位不变`() {
        val logs = mutableListOf<String>()
        val host = newHost(logs, "modA")

        host.install(listOf("modA" to NoopMod("modA")))

        val context = host.installedContexts.single()
        assertTrue(context.hooks.knownFunctionKeys().isEmpty())
        val failure = assertThrows<IllegalArgumentException> {
            context.hooks.register("COM30", HookPhase.PREFIX) { HookResult.Continue }
        }
        assertTrue(failure.message!!.contains("hook 槽未声明"), failure.message)
        assertFalse(failure.message!!.contains("未知函数键"), failure.message)
    }

    // ---- 闸边界：只拦注册，不碰运行期派发 ----

    @Test
    fun `闸只拦注册_派发旁路与命中语义零改动`() {
        val logs = mutableListOf<String>()
        val context = newUnassembledContext()
        context.knownFunctionKeys = setOf("COM300")
        // 闸放行目录内键：处理器照常绑定。
        var hooked = false
        context.hooks.register("com300", HookPhase.PREFIX) {
            hooked = true
            HookResult.Continue
        }

        val store = VariableStore()
        FunctionHookWiring.install(store, context.hookPipeline, logs::add)
        // 目录外的键派发照旧旁路（闸不进运行期）：无槽 ⇒ 原函数原样返回。
        val bypassed =
            FunctionHookWiring.dispatchCall(store, "COM30", longArrayOf(1), arrayOf(), strRet = false) {
                _, _ -> ModValue.IntValue(42L)
            }.let { (it as ModValue.IntValue).value }
        assertEquals(42L, bypassed)
        assertFalse(hooked)
        // 目录内的键派发照旧命中：绑定的 PREFIX 处理器跑过，原函数返回值投影不变。
        val dispatched =
            FunctionHookWiring.dispatchCall(store, "COM300", longArrayOf(1), arrayOf(), strRet = false) {
                _, _ -> ModValue.IntValue(42L)
            }.let { (it as ModValue.IntValue).value }
        assertEquals(42L, dispatched)
        assertTrue(hooked)
    }

    // ---- 台架对拍（RecordingHooks.adoptKnownFunctionKeys）：文案逐字一致 ----

    @Test
    fun `台架与生产拼错文案逐字一致且候选一致`() {
        val known = setOf("COM300", "COM310", "EVENTCOM", "XOM30")

        val prodError = assertThrows<IllegalArgumentException> {
            newUnassembledContext("mod.a").apply { knownFunctionKeys = known }
                .hooks.register("COM30", HookPhase.PREFIX) { HookResult.Continue }
        }
        val benchWorld = TestWorld.ofManifests()
        val bench = benchWorld.newContext("mod.a")
        bench.hooksRecorder.adoptKnownFunctionKeys(known)
        val benchError = assertThrows<IllegalArgumentException> {
            bench.hooks.register("COM30", HookPhase.PREFIX) { HookResult.Continue }
        }

        assertEquals(prodError.message, benchError.message, "两侧拒绝文案逐字一致")
        assertTrue(
            prodError.message.orEmpty().contains("最接近的候选：COM300, COM310, XOM30"),
            "最近 3 候选按编辑距离 → 名字序：$prodError",
        )
        // 拒绝后台架表里没有该绑定：合法键不受闸误伤，照常注册。
        assertTrue(bench.hooksRecorder.calls.isEmpty())
    }

    // ---- 夹具（照 HookApiQueryTest / ModRuntimeHostTest 同形，未走 app 装配）----

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

    /** 快照声明 COM300/PREFIX 槽（正控绑定的落点）；未走宿主装配 ⇒ events/hook 共享面均为直连形态。 */
    private fun newSnapshot(vararg packages: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        for (pkg in packages) {
            registry.addPackage(
                ModDeclarationAdapter.toDescriptor(
                    ModManifest(
                        packageId = pkg,
                        hooks = listOf(HookDecl("COM300", HookPhase.PREFIX)),
                    ),
                ),
            )
        }
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true

        override fun submit(action: () -> Unit) {
            action()
        }
    }

    private class NoopMod(private val label: String) : EraMod {
        override fun onLoad(context: ModContext) = Unit
        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
        override fun toString(): String = label
    }

    private fun newUnassembledContext(packageId: String = "demo"): ModContextImpl = ModContextImpl(
        packageId = packageId,
        snapshot = newSnapshot(packageId),
        entityKernel = newKernel(),
        pkgShortId = 1,
        nativeMethods = NativeMethodRegistry(),
        logSink = {},
        runtime = FakeRuntime(),
    )

    private fun newHost(logs: MutableList<String>, vararg packages: String): ModRuntimeHost = ModRuntimeHost(
        snapshot = newSnapshot(*packages),
        entityKernel = newKernel(),
        nativeMethods = NativeMethodRegistry(),
        logSink = { logs += it },
        runtime = FakeRuntime(),
    )
}
