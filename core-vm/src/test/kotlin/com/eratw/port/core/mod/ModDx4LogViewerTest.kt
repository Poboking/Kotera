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
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 宿主按包日志回看聚合缝（MODDX4-G8）：`ModRuntimeHost.recentLogs(packageId)` 按包归属取
 * G2 每包有界环（[ModContextImpl.recentLogLines]）的只读快照。
 *
 * 断言口径（契约 §3 反向证明）：有日志的包 = emit 已发出的行、旧到新、包间隔离；
 * 无日志包 / 未装载包 / 未知包 / 空白包 ⇒ 空表不抛（查询空集语义）；
 * onLoad 失败被回滚的包其已发日志照样可回看（报障现场正是回看的用途）；
 * shutdown（生命周期收口）后环是只读历史快照、重复读数稳定，core 层不崩不造假。
 */
class ModDx4LogViewerTest {

    private fun newSnapshot(vararg packages: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        for (pkg in packages) {
            registry.addPackage(
                ModDeclarationAdapter.toDescriptor(
                    ModManifest(packageId = pkg, hooks = listOf(HookDecl("COM300", HookPhase.PREFIX))),
                ),
            )
        }
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) = action()
    }

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

    private fun newHost(logs: MutableList<String>, vararg packages: String): ModRuntimeHost =
        ModRuntimeHost(
            snapshot = newSnapshot(*packages),
            entityKernel = newKernel(),
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = FakeRuntime(),
        )

    /** 在 onLoad 里按序发 [messages]、可选拍后抛错的夹具 mod。 */
    private class LoggingMod(
        private val messages: List<String>,
        private val failOnLoad: Boolean = false,
    ) : EraMod {
        override fun onLoad(context: ModContext) {
            for (message in messages) context.logger.info(message)
            if (failOnLoad) error("onLoad 故意失败")
        }

        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    @Test
    fun `按包过滤_时序保持_包间隔离`() {
        val logs = mutableListOf<String>()
        val host = newHost(logs, "modA", "modB")
        host.install(
            listOf(
                "modA" to LoggingMod(listOf("a-one", "a-two", "a-three")),
                "modB" to LoggingMod(listOf("b-one")),
            ),
        )
        assertEquals(
            listOf("[modA][INFO] a-one", "[modA][INFO] a-two", "[modA][INFO] a-three"),
            host.recentLogs("modA"),
            "modA 的环：emit 已发出的行、旧到新",
        )
        assertEquals(
            listOf("[modB][INFO] b-one"),
            host.recentLogs("modB"),
            "modB 的环：包间隔离，不混入 modA 的行",
        )
    }

    @Test
    fun `未知_未装载_空白包返回空表不抛`() {
        val logs = mutableListOf<String>()
        val host = newHost(logs, "modA")
        assertEquals(emptyList(), host.recentLogs("nope"), "快照里没有的包 = 空表")
        assertEquals(emptyList(), host.recentLogs(""), "空白包名 = 空表")
        assertEquals(emptyList(), host.recentLogs("   "), "纯空白包名 = 空表")
        // 会话根本没跑过 install：快照为空也照答空表（设置页在会话前打开的口径）。
        assertEquals(emptyList(), host.recentLogs("modA"), "未装载的包 = 空表")
    }

    @Test
    fun `装载成功但没发过日志的包返回空表`() {
        val logs = mutableListOf<String>()
        val host = newHost(logs, "modA")
        host.install(listOf("modA" to LoggingMod(emptyList())))
        assertEquals(emptyList(), host.recentLogs("modA"), "有 entry 没日志 = 空表（不造假数据）")
    }

    @Test
    fun `onLoad 失败被回滚的包其已发日志仍可回看`() {
        val logs = mutableListOf<String>()
        val host = newHost(logs, "modA")
        val report = host.install(listOf("modA" to LoggingMod(listOf("before boom"), failOnLoad = true)))
        assertEquals(false, report.single().active, "夹具自检：该包确实装载失败")
        val recent = host.recentLogs("modA")
        assertTrue(
            "[modA][INFO] before boom" in recent,
            "失败前已发出的日志在环里：$recent",
        )
        assertTrue(
            recent.last().startsWith("[modA][ERROR] onLoad 或 onEnable 失败"),
            "宿主隔离 ERROR 也经 logger 进环（报障现场完整）：${recent.last()}",
        )
    }

    @Test
    fun `shutdown 后环是只读历史快照_重复读数稳定不崩`() {
        val logs = mutableListOf<String>()
        val host = newHost(logs, "modA")
        host.install(listOf("modA" to LoggingMod(listOf("alive"))))
        host.shutdown()
        val first = host.recentLogs("modA")
        assertEquals(listOf("[modA][INFO] alive"), first, "生命周期收口不抹历史：快照仍可读")
        assertEquals(first, host.recentLogs("modA"), "重复读数稳定（只读副本语义）")
    }
}
