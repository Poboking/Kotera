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
 * 包版本升级迁移通知（CONTRACT-MODDX-2 Lane DX2-A）的台架与宿主两层测试。
 *
 * 形状与 [SettingsChangedNotifyTest] 同族：台架层（[runMod] + [TestModSession.packageUpgrade]）
 * 断作者面——缺省空实现安全、覆写后 from/to 原样到达、回调序照生产口径在 `saveLoaded` 之后；
 * 宿主层（真 [ModRuntimeHost]）断多 mod 语义——per-mod 异常隔离、按包派发（未装载/未启用不达）、
 * timing scope 入账。记账与对账（保留键读写、首装不回调）在装配侧，由 app 层
 * `ModPackageUpgradeAssemblyTest` 对真装配线断言，本套不对替身断言。
 */
class PackageUpgradeNotifyTest {

    /** 录制生命周期各拍进 trace；onPackageUpgrade 可注入抛错与搬数据行为。 */
    private class UpgradeMod(
        private val trace: MutableList<String>,
        private val label: String,
        private val failOnEnable: Boolean = false,
        private val failOnUpgrade: Boolean = false,
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

        override fun onSaveLoaded() {
            trace += "$label:onSaveLoaded"
        }

        override fun onPackageUpgrade(fromVersion: String, toVersion: String) {
            trace += "$label:onPackageUpgrade($fromVersion->$toVersion)"
            if (failOnUpgrade) error("$label 故意在 onPackageUpgrade 抛异常")
        }
    }

    /** 完全走缺省实现的 mod（断「不覆写 = 零负担」；生命周期留痕，无空覆写块）。 */
    private class SilentMod(trace: MutableList<String>) : EraMod {
        private val recorded = trace

        override fun onLoad(context: ModContext) {
            recorded += "silent:onLoad"
        }

        override fun onEnable() {
            recorded += "silent:onEnable"
        }

        override fun onDisable() {
            recorded += "silent:onDisable"
        }

        override fun onUnload() {
            recorded += "silent:onUnload"
        }
    }

    /** 台架搬数据用：升级回调里把旧键改名到新键（作者迁移逻辑的最小形状）。 */
    private class MigratingMod(
        private val trace: MutableList<String>,
        private val oldKey: String,
        private val newKey: String,
    ) : EraMod {
        var context: ModContext? = null
            private set

        override fun onLoad(context: ModContext) {
            this.context = context
        }

        override fun onSaveLoaded() {
            trace += "onSaveLoaded"
        }

        override fun onPackageUpgrade(fromVersion: String, toVersion: String) {
            trace += "onPackageUpgrade($fromVersion->$toVersion)"
            val storage = context?.storage
            val value = storage?.get(oldKey)
            if (value != null) {
                storage?.put(newKey, value)
                storage?.remove(oldKey)
            }
        }

        override fun onEnable() {
            trace += "migrate:onEnable"
        }

        override fun onDisable() {
            trace += "migrate:onDisable"
        }

        override fun onUnload() {
            trace += "migrate:onUnload"
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

    // ------------------------------------------------------------------
    // 台架层：作者面（缺省安全 / from/to 原样 / 回调序 / 搬数据）
    // ------------------------------------------------------------------

    @Test
    fun `台架缺省空实现安全且覆写后fromTo原样到达`() {
        val world = TestWorld.ofManifests(ModManifest(packageId = TestWorld.DEFAULT_PACKAGE_ID))
        val trace = mutableListOf<String>()
        // 缺省空实现（完全不覆写）：不抛即通过，零负担。
        val silent = runMod(SilentMod(trace), world)
        silent.packageUpgrade("1.0", "1.1")
        silent.unload()

        // 覆写后：from/to 原样到达（宿主转发什么作者收什么，不做解析排序）。
        val session = runMod(UpgradeMod(trace, "m"), world)
        trace.clear()
        session.packageUpgrade("1.1", "2.0")
        assertEquals(listOf("m:onPackageUpgrade(1.1->2.0)"), trace)
        world.close()
    }

    @Test
    fun `台架回调序照生产口径saveLoaded在前且迁移可搬storage`() {
        val world = TestWorld.ofManifests(ModManifest(packageId = TestWorld.DEFAULT_PACKAGE_ID))
        val trace = mutableListOf<String>()
        val session = runMod(MigratingMod(trace, "old", "new"), world)
        session.context.storage.put("old", "v1")
        trace.clear()

        // 生产序：读档末尾先扇出 onSaveLoaded，再逐包派发 onPackageUpgrade。
        session.saveLoaded()
        session.packageUpgrade("1.0", "2.0")

        assertEquals(
            listOf("onSaveLoaded", "onPackageUpgrade(1.0->2.0)"),
            trace,
            "回调序必须照生产口径：onSaveLoaded 之后才派发升级迁移",
        )
        assertEquals("v1", session.context.storage.get("new"), "迁移可读旧键并搬到新键")
        assertEquals(null, session.context.storage.get("old"), "旧键搬走后不再可见")
        world.close()
    }

    // ------------------------------------------------------------------
    // 宿主层：多 mod 语义（真宿主，记账/对账在装配侧另测）
    // ------------------------------------------------------------------

    @Test
    fun `宿主版本变化回调到达且fromTo正确且只达本包`() {
        val logs = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = newHost(logs, "modA", "modB")
        host.install(
            listOf(
                "modA" to UpgradeMod(trace, "A"),
                "modB" to UpgradeMod(trace, "B"),
            ),
        )
        trace.clear()

        host.notifyPackageUpgrade("modA", "1.0", "2.0")

        assertEquals(
            listOf("A:onPackageUpgrade(1.0->2.0)"),
            trace,
            "只达目标包：from/to 原样转发，别的包不陪跑",
        )
        host.shutdown()
    }

    @Test
    fun `宿主一个mod抛异常不影响其余且入账固定scope`() {
        val logs = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = newHost(logs, "modA", "modB", "modC")
        host.install(
            listOf(
                "modA" to UpgradeMod(trace, "A"),
                "modB" to UpgradeMod(trace, "B", failOnUpgrade = true),
                "modC" to UpgradeMod(trace, "C"),
            ),
        )
        val ledger = ModTimingLedger()
        host.timing = ledger
        trace.clear()

        // 生产 = 读档末尾逐包派发；modB 抛错必须只隔离它自己，不二次抛、不拦住其余包。
        host.notifyPackageUpgrade("modA", "1.0", "2.0")
        host.notifyPackageUpgrade("modB", "1.0", "2.0")
        host.notifyPackageUpgrade("modC", "1.0", "2.0")

        assertEquals(
            listOf(
                "A:onPackageUpgrade(1.0->2.0)",
                "B:onPackageUpgrade(1.0->2.0)",
                "C:onPackageUpgrade(1.0->2.0)",
            ),
            trace,
            "modB 抛错必须被隔离：modA 与 modC 照常到达且顺序不乱",
        )
        assertTrue(
            logs.any { it.contains("onPackageUpgrade 异常已隔离") && it.contains("modB") },
            "modB 的失败必须留痕且只留它自己：$logs",
        )
        assertTrue(
            ledger.forPackage("modA").any { it.scope == "lifecycle.onPackageUpgrade" },
            "升级通知必须入账固定 scope lifecycle.onPackageUpgrade：${ledger.forPackage("modA")}",
        )
        assertTrue(
            host.failureOf("modB") == null,
            "通知失败不改启用/失败状态（与其它相位通知同口径）",
        )
        host.shutdown()
    }

    @Test
    fun `宿主升级通知未装载与未启用不达且收口后旁路`() {
        val logs = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = newHost(logs, "modOk", "modBad")
        host.install(
            listOf(
                "modOk" to UpgradeMod(trace, "Ok"),
                "modBad" to UpgradeMod(trace, "Bad", failOnEnable = true),
            ),
        )
        trace.clear()

        // 未装载的包：no-op 不抛（entries 按包过滤，包不在表 = 空派发）。
        host.notifyPackageUpgrade("ghost", "1.0", "2.0")
        // 未启用（onEnable 失败被回滚）的包：不兑现回调。
        host.notifyPackageUpgrade("modBad", "1.0", "2.0")
        assertEquals(emptyList<String>(), trace, "未装载与未启用的 mod 都不收升级回调")

        // 目标包已启用：只它到达。
        host.notifyPackageUpgrade("modOk", "1.0", "2.0")
        assertEquals(listOf("Ok:onPackageUpgrade(1.0->2.0)"), trace)

        // 收口之后不再兑现（幂等旁路）。
        host.shutdown()
        trace.clear()
        host.notifyPackageUpgrade("modOk", "1.0", "2.0")
        assertEquals(emptyList<String>(), trace)
    }
}
