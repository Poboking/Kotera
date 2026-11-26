package com.eratw.port.modtesting

import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModContextImpl
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 台架 UI 替身 ↔ 生产界面登记表的语义对拍（同一登记脚本跑两边，断言观测面一致）。
 *
 * 对拍面 = 登记表本身（生产 `ModContextImpl` 的 `InstanceUiRegistry`）：可见键快照
 * （`registeredKeys` ↔ [RecordingUi.visibleKeys]）、点击命中结果（`uiClick` ↔
 * [RecordingUi.click] 的 `handled`）、谓词异常 WARN 原文。
 *
 * 刻意**不对拍**的一层是回调异常：生产 `uiClick` 命中后直接 `onClick()`，异常原样冒泡
 * （`ModContextImpl.kt:747-752`）；隔离在宿主派发层 `ModUiSurface.dispatch`
 * （`app/src/main/kotlin/com/eratw/port/android/ModUiSurface.kt:162-176`）。台架无 app 壳，
 * `click` 模拟的是宿主派发层，故这里把「生产上抛 vs 台架隔离」作为**层映射**断言写死，
 * 防止作者误以为生产 `uiClick` 自己会吞异常。
 */
class RecordingUiProductionParityTest {

    @Test
    fun `可见键快照与生产 registeredKeys 逐项一致`() {
        val prod = productionContext()
        val bench = RecordingUi(PACKAGE_ID)
        var shown = true
        prod.ui.registerInteraction("ALWAYS") { }
        bench.registerInteraction("ALWAYS") { }
        prod.ui.registerInteraction("TOGGLE", visible = { shown }) { }
        bench.registerInteraction("TOGGLE", visible = { shown }) { }

        assertEquals(prod.uiRegisteredKeys(), bench.visibleKeys())
        shown = false
        assertEquals(prod.uiRegisteredKeys(), bench.visibleKeys())
        shown = true
        assertEquals(prod.uiRegisteredKeys(), bench.visibleKeys())
    }

    @Test
    fun `点击命中结果与生产 uiClick 一致`() {
        val prod = productionContext()
        val bench = RecordingUi(PACKAGE_ID)
        var prodHits = 0
        var benchHits = 0
        prod.ui.registerInteraction("HIT") { prodHits++ }
        bench.registerInteraction("HIT") { benchHits++ }
        prod.ui.registerInteraction("HIDDEN", visible = { false }) { }
        bench.registerInteraction("HIDDEN", visible = { false }) { }

        assertEquals(prod.uiClick("HIT"), bench.click("HIT").handled)
        assertEquals(prod.uiClick("HIDDEN"), bench.click("HIDDEN").handled)
        assertEquals(prod.uiClick("NO_SUCH_KEY"), bench.click("NO_SUCH_KEY").handled)
        assertEquals(prodHits, benchHits)
    }

    @Test
    fun `谓词异常降级 WARN 与生产日志逐字相等`() {
        val prod = productionContext()
        var benchWarnings = mutableListOf<String>()
        val bench = RecordingUi(PACKAGE_ID) { message -> benchWarnings += message }
        val throwing = { error("boom") }
        prod.ui.registerInteraction("BROKEN", visible = throwing) { }
        bench.registerInteraction("BROKEN", visible = throwing) { }

        assertEquals(emptySet(), prod.uiRegisteredKeys())
        assertEquals(emptySet(), bench.visibleKeys())
        assertEquals(prod.logs.single(), benchWarnings.single())
        assertEquals(prod.logs.single(), bench.warnings.single())
    }

    @Test
    fun `生产 uiClick 不上抛兜底而台架按宿主派发层隔离回调异常`() {
        val prod = productionContext()
        prod.ui.registerInteraction("BOOM") { error("boom") }
        // 生产登记表层：异常原样冒泡（隔离责任在宿主派发层，不在 uiClick）。
        assertFailsWith<IllegalStateException> { prod.uiClick("BOOM") }

        val bench = RecordingUi(PACKAGE_ID)
        bench.registerInteraction("BOOM") { error("boom") }
        val outcome = bench.click("BOOM")
        assertFalse(outcome.handled)
        assertEquals("java.lang.IllegalStateException: boom", outcome.failure?.toString())
        assertTrue(
            bench.warnings.single().startsWith("mod.ui 点击失败已隔离"),
            "台架应对齐宿主派发层文案：${bench.warnings}",
        )
    }

    /** 生产上下文：只需声明快照 + 空内核 + 日志 sink，界面试探全在 `ui` 与 `uiRegisteredKeys`。 */
    private class ProductionContext(val context: ModContextImpl, val logs: MutableList<String>) {
        val ui get() = context.ui
        fun uiRegisteredKeys(): Set<String> = context.uiRegisteredKeys()
        fun uiClick(labelKey: String): Boolean = context.uiClick(labelKey)
    }

    private fun productionContext(): ProductionContext {
        val logs = mutableListOf<String>()
        val snapshot: DeclarationSnapshot = snapshotOf(PACKAGE_ID)
        val context = ModContextImpl(
            packageId = PACKAGE_ID,
            snapshot = snapshot,
            entityKernel = EntityKernel(
                schemas = emptyList(),
                relationshipSchemas = emptyList(),
                archetypes = emptyList(),
            ),
            pkgShortId = 1,
            nativeMethods = NativeMethodRegistry(),
            logSink = { message -> logs += message },
            runtime = FakeRuntime,
        )
        return ProductionContext(context, logs)
    }
}

/** 生产上下文所用包身份（与台架 `RecordingUi(packageId)` 同名，WARN 文案才会逐字相等）。 */
private const val PACKAGE_ID: String = "test.parity"

/** 单线程调度器：提交即执行（生产构造只要求接口实现）。 */
private object FakeRuntime : RuntimeScheduler {
    override val isOnMainThread: Boolean get() = true
    override fun submit(action: () -> Unit) = action()
}

/** 只含 packageId 的冻结声明快照（生产构造期只校验「快照含本包」）。 */
private fun snapshotOf(packageId: String): DeclarationSnapshot {
    val registry = DeclarationRegistry()
    registry.addPackage(ModDeclarationAdapter.toDescriptor(ModManifest(packageId = packageId)))
    return registry.freeze()
}
