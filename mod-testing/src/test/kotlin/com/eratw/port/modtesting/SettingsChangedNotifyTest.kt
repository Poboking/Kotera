package com.eratw.port.modtesting

import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.mod.ModTimingLedger
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 设置变更热通知（CONTRACT-MODDX-1 Lane DX-A1）的台架与宿主两层测试。
 *
 * 台架层（[runMod] + [TestModSession.settingsChanged]）：回调到达、键序保持、回调里读到
 * 已持久化的新值。宿主层（真 [ModRuntimeHost]）：per-mod 异常隔离、按包派发（未装载/未启用
 * 不达）—— 这两条是多 mod 宿主语义，台架是单 mod 直调、没有宿主扇出，故直接对真宿主断言
 * （mod-testing test 源集本就见 `:core-vm`，与资源面同源纪律一致：宿主语义不对替身断言，
 * 避免台架假绿）。
 */
class SettingsChangedNotifyTest {

    /** 录制生命周期各拍进 trace；onSettingsChanged 额外按需读一个设置值（证明快照已更新）。 */
    private class WatchMod(
        private val trace: MutableList<String>,
        private val label: String,
        private val failOnEnable: Boolean = false,
        private val failOnSettingsChanged: Boolean = false,
        private val volumeKey: String? = null,
    ) : EraMod {
        var context: ModContext? = null
            private set

        override fun onLoad(context: ModContext) {
            this.context = context
            trace += "$label:onLoad"
        }

        override fun onEnable() {
            trace += "$label:onEnable"
            if (failOnEnable) error("$label 故意在 onEnable 抛异常")
        }

        override fun onDisable() {
            trace += "$label:onDisable"
        }

        override fun onUnload() {
            trace += "$label:onUnload"
        }

        override fun onSettingsChanged(changedKeys: List<String>) {
            // 只在显式给了键时读（宿主级用例未注入设置快照，context.settings 是 fail-closed 桩）。
            val volumeRead = volumeKey?.let { context?.settings?.str(it) }
            trace += "$label:onSettingsChanged=$changedKeys" + (volumeRead?.let { ",vol=$it" } ?: "")
            if (failOnSettingsChanged) error("$label 故意在 onSettingsChanged 抛异常")
        }
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true

        override fun submit(action: () -> Unit) {
            action()
        }
    }

    private fun newSnapshot(vararg packages: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        for (pkg in packages) {
            registry.addPackage(ModDeclarationAdapter.toDescriptor(ModManifest(packageId = pkg)))
        }
        return registry.freeze()
    }

    private fun newHost(logs: MutableList<String>, vararg packages: String): ModRuntimeHost =
        ModRuntimeHost(
            snapshot = newSnapshot(*packages),
            entityKernel = EntityKernel(
                schemas = emptyList(),
                relationshipSchemas = emptyList(),
                archetypes = emptyList(),
            ),
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = FakeRuntime(),
        )

    @Test
    fun `台架设置变更通知_回调到达且键序保持且回调里读到已持久化的新值`() {
        val world = TestWorld.ofManifests(ModManifest(packageId = TestWorld.DEFAULT_PACKAGE_ID))
        val trace = mutableListOf<String>()
        val session = runMod(WatchMod(trace, "m", volumeKey = "volume"), world)
        trace.clear()

        // 生产缝的顺序：玩家写入成功（值已持久化）之后才派发回调 —— change 先落新值，
        // 并按写入序返回键名，写入序与回调键序天然一致。
        val changedKeys = listOf(
            session.context.settingsRecorder.change("volume", "5"),
            session.context.settingsRecorder.change("subtitle", "on"),
        )
        session.settingsChanged(changedKeys)

        assertEquals(
            listOf("m:onSettingsChanged=[volume, subtitle],vol=5"),
            trace,
            "回调必须按传入键序到达，且回调里 context.settings 已读到写入后的新值",
        )
        world.close()
    }

    @Test
    fun `宿主设置变更通知_一个 mod 抛异常不影响其余 mod 且入账固定 scope`() {
        val logs = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = newHost(logs, "modA", "modB", "modC")
        host.install(
            listOf(
                "modA" to WatchMod(trace, "A"),
                "modB" to WatchMod(trace, "B", failOnSettingsChanged = true),
                "modC" to WatchMod(trace, "C"),
            ),
        )
        val ledger = ModTimingLedger()
        host.timing = ledger
        trace.clear()

        // 生产 = 设置页按包逐个派发；modB 抛错必须只隔离它自己，不二次抛、不拦住其余 mod。
        host.notifySettingsChanged("modA", listOf("alpha"))
        host.notifySettingsChanged("modB", listOf("beta"))
        host.notifySettingsChanged("modC", listOf("gamma", "delta"))

        assertEquals(
            listOf(
                "A:onSettingsChanged=[alpha]",
                "B:onSettingsChanged=[beta]",
                "C:onSettingsChanged=[gamma, delta]",
            ),
            trace,
            "modB 抛错必须被隔离：modA 与 modC 照常到达且顺序不乱",
        )
        assertTrue(
            logs.any { it.contains("onSettingsChanged 异常已隔离") && it.contains("modB") },
            "modB 的失败必须留痕且只留它自己：$logs",
        )
        assertTrue(
            ledger.forPackage("modA").any { it.scope == "lifecycle.onSettingsChanged" },
            "设置变更通知必须入账固定 scope lifecycle.onSettingsChanged：${ledger.forPackage("modA")}",
        )
        host.shutdown()
    }

    @Test
    fun `宿主设置变更通知只达该包_未装载与未启用 mod 不收回调`() {
        val logs = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = newHost(logs, "modOk", "modBad")
        host.install(
            listOf(
                "modOk" to WatchMod(trace, "Ok"),
                "modBad" to WatchMod(trace, "Bad", failOnEnable = true),
            ),
        )
        trace.clear()

        // 未装载的包：no-op 不抛（entries 按包过滤，包不在表 = 空派发）。
        host.notifySettingsChanged("ghost", listOf("k"))
        // 未启用（onEnable 失败被回滚）的包：不兑现回调。
        host.notifySettingsChanged("modBad", listOf("k"))
        assertEquals(emptyList<String>(), trace, "未装载与未启用的 mod 都不收设置变更回调")

        // 目标包已启用：只它到达，别的包不陪跑。
        host.notifySettingsChanged("modOk", listOf("k"))
        assertEquals(listOf("Ok:onSettingsChanged=[k]"), trace)

        // 收口之后不再兑现（幂等旁路）。
        host.shutdown()
        trace.clear()
        host.notifySettingsChanged("modOk", listOf("k"))
        assertEquals(emptyList<String>(), trace)
    }
}
