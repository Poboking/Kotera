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
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.ModApi
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.RuntimeScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 版本协商闸（Lane E）：mod API 契约版本装载期拒绝、每 mod 数据版本持久化 + 迁移回调。
 *
 * 分三块：
 * A. 契约版本门：`apiVersion > ModApi.VERSION` 只拒该 mod，其余照常；等于/低于放行。
 * B. 数据版本门与迁移回调：预置容器（等价读档前容器）时严格 onLoad → onMigrate → onEnable；
 *    无记录不迁移；迁移失败/降级 per-mod 隔离；读档缝（rebindStorage）恢复后核对并迁移。
 * C. 容器承载：dataVersion 随 encode/load 往返、非法登记 fail-fast。
 *
 * 时序偏离（诚实欠账，见 ModRuntimeHost.rebindStorage 的 KDoc）：生产读档缝的侧车恢复在首轮
 * onLoad/onEnable 之后，该缝只能保证 onMigrate 早于 onSaveLoaded；装载期即可预置容器的路径
 * 严格满足「onLoad 之后、onEnable 之前」，本套 A/B 两路都覆盖。
 */
class ModVersionNegotiationTest {

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

        override fun submit(action: () -> Unit) {
            action()
        }
    }

    /**
     * 可覆写契约版本/数据版本、可注入迁移行为的测试 mod；trace 记录生命周期与迁移调用，
     * 便于断言精确调用窗口（尤其是 onMigrate 落在 onLoad 与 onEnable 之间）。
     */
    private class VersionedMod(
        private val trace: MutableList<String>,
        private val label: String,
        override val apiVersion: Int = ModApi.VERSION,
        override val dataVersion: Int = 0,
        private val migrateThrows: Boolean = false,
        private val migrateBody: ((ModContext?, Int, Int) -> Unit)? = null,
    ) : EraMod {
        var context: ModContext? = null
            private set

        override fun onLoad(context: ModContext) {
            this.context = context
            trace += "$label:onLoad"
        }

        override fun onEnable() {
            trace += "$label:onEnable"
        }

        override fun onDisable() {
            trace += "$label:onDisable"
        }

        override fun onUnload() {
            trace += "$label:onUnload"
        }

        override fun onMigrate(fromDataVersion: Int, toDataVersion: Int) {
            trace += "$label:onMigrate($fromDataVersion->$toDataVersion)"
            migrateBody?.invoke(context, fromDataVersion, toDataVersion)
            if (migrateThrows) error("$label 故意迁移失败")
        }
    }

    private fun newHost(logs: MutableList<String>, vararg packages: String): ModRuntimeHost =
        newHostWith(logs, ModStorageStore(), *packages)

    private fun newHostWith(
        logs: MutableList<String>,
        storage: ModStorageStore,
        vararg packages: String,
    ): ModRuntimeHost = ModRuntimeHost(
        snapshot = newSnapshot(*packages),
        entityKernel = newKernel(),
        nativeMethods = NativeMethodRegistry(),
        logSink = { logs += it },
        runtime = FakeRuntime(),
        storage = storage,
    )

    /** 等价「读档恢复出来的容器」：段带 dataVersion 且包处于激活态（knownPackages 含它）。 */
    private fun restoredStore(
        packageId: String,
        dataVersion: Int,
        entries: Map<String, ModValue?> = emptyMap(),
    ): ModStorageStore = ModStorageStore.load(
        ModStorageSectionCodec.encode(listOf(ModStorageSection(packageId, entries, dataVersion = dataVersion))),
        knownPackages = setOf(packageId),
    )

    // ------------------------------------------------------------------
    // A. 契约版本门（ModApi.VERSION）
    // ------------------------------------------------------------------

    @Test
    fun `契约版本高于内核的 mod 只拒它自己其余照常装载`() {
        val trace = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val host = newHostWith(logs, ModStorageStore(), "modOld", "modNew")
        val report = host.install(
            listOf(
                "modOld" to VersionedMod(trace, "old"),
                "modNew" to VersionedMod(trace, "new", apiVersion = ModApi.VERSION + 1),
            ),
        )

        assertTrue(report[0].active, "旧契约 mod 照常装载")
        assertNull(report[0].failure)
        assertFalse(report[1].active, "需要更新契约的 mod 必须被拒")
        assertIs<ModApiVersionMismatchException>(report[1].failure)
        val message = assertNotNull(report[1].failure?.message)
        assertTrue(message.contains("需要 mod API v${ModApi.VERSION + 1}"), "原因必须说清需要哪版：$message")
        assertTrue(message.contains("只到 v${ModApi.VERSION}"), "原因必须说清内核到哪版：$message")
        assertEquals(listOf("old:onLoad", "old:onEnable"), trace, "被拒 mod 不得进入生命周期")
        assertFalse(host.storage.isActive("modNew"), "被拒 mod 不得留下激活段")
        assertTrue(logs.any { it.contains("modNew") && it.contains("版本协商失败") }, "失败必须留痕：$logs")
    }

    @Test
    fun `契约版本等于或低于内核都正常装载`() {
        val trace = mutableListOf<String>()
        val host = newHost(mutableListOf(), "modSame", "modLower")
        val report = host.install(
            listOf(
                "modSame" to VersionedMod(trace, "same"),
                "modLower" to VersionedMod(trace, "lower", apiVersion = 1),
            ),
        )

        assertTrue(report.all { it.active }, "apiVersion <= VERSION 一律放行")
        assertEquals(ModApi.VERSION, VersionedMod(mutableListOf(), "x").apiVersion, "默认值 = 编译期常量")
        assertTrue(trace.contains("same:onEnable") && trace.contains("lower:onEnable"))
    }

    // ------------------------------------------------------------------
    // B. 数据版本门与迁移回调
    // ------------------------------------------------------------------

    @Test
    fun `预置低版本时 onLoad 之后 onEnable 之前迁移并写回声明版本`() {
        val trace = mutableListOf<String>()
        val host = newHostWith(mutableListOf(), restoredStore("modA", 1, mapOf("k" to ModValue.StrValue("v1"))), "modA")
        val mod = VersionedMod(
            trace,
            "a",
            dataVersion = 2,
            migrateBody = { ctx, _, _ -> ctx?.storage?.put("migrated", "yes") },
        )
        val report = host.install(listOf("modA" to mod))

        assertTrue(report.single().active)
        assertEquals(
            listOf("a:onLoad", "a:onMigrate(1->2)", "a:onEnable"),
            trace,
            "迁移窗口 = onLoad 之后、onEnable 之前",
        )
        assertEquals(2, host.storage.dataVersionsByPackage["modA"], "迁移成功后侧车记录必须升到 declared")
        assertEquals("v1", mod.context?.storage?.get("k"), "迁移期间旧数据照常可读")
        assertEquals("yes", mod.context?.storage?.get("migrated"), "迁移写入落在本包段")
    }

    @Test
    fun `无侧车数据版本时直接落声明版本且不触发迁移`() {
        val trace = mutableListOf<String>()
        val host = newHostWith(mutableListOf(), ModStorageStore(), "modA")
        val mod = VersionedMod(trace, "a", dataVersion = 3)
        val report = host.install(listOf("modA" to mod))

        assertTrue(report.single().active)
        assertEquals(listOf("a:onLoad", "a:onEnable"), trace, "新局不得触发 onMigrate")
        assertEquals(3, host.storage.dataVersionsByPackage["modA"], "新局直接把 declared 写进容器")
    }

    @Test
    fun `读档缝恢复落后版本触发迁移并写回声明版本`() {
        val trace = mutableListOf<String>()
        val host = newHostWith(mutableListOf(), ModStorageStore(), "modA")
        val mod = VersionedMod(trace, "a", dataVersion = 2)
        host.install(listOf("modA" to mod))
        assertEquals(listOf("a:onLoad", "a:onEnable"), trace)

        val restored = restoredStore("modA", 1, mapOf("k" to ModValue.StrValue("old")))
        host.rebindStorage(restored)

        assertEquals(listOf("a:onLoad", "a:onEnable", "a:onMigrate(1->2)"), trace, "读档缝补齐迁移调用")
        assertEquals(2, restored.dataVersionsByPackage["modA"], "迁移成功后新容器版本必须升到 declared")
        assertEquals("old", mod.context?.storage?.get("k"), "重绑后旧数据经同一 context 可读")
    }

    @Test
    fun `读档缝恢复相同版本不触发迁移`() {
        val trace = mutableListOf<String>()
        val host = newHostWith(mutableListOf(), ModStorageStore(), "modA")
        host.install(listOf("modA" to VersionedMod(trace, "a", dataVersion = 2)))

        host.rebindStorage(restoredStore("modA", 2))

        assertEquals(listOf("a:onLoad", "a:onEnable"), trace, "版本一致不得调 onMigrate")
    }

    @Test
    fun `预置高版本时装载期拒该 mod 且不进生命周期`() {
        val trace = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val host = newHostWith(logs, restoredStore("modA", 3, mapOf("k" to ModValue.StrValue("new"))), "modA")
        val report = host.install(listOf("modA" to VersionedMod(trace, "a", dataVersion = 2)))

        assertFalse(report.single().active, "存档数据版本高于 mod 声明 ⇒ 拒绝，不许静默降级")
        assertIs<ModDataVersionDowngradeException>(report.single().failure)
        assertTrue(report.single().failure?.message?.contains("降级") == true)
        assertTrue(trace.isEmpty(), "被拒 mod 不得进入生命周期（连 onLoad 都不跑）")
        assertFalse(host.storage.isActive("modA"), "被拒 mod 的段退回非激活，数据不删不写")
        assertEquals(3, host.storage.dataVersionsByPackage["modA"], "拒绝时不得篡改侧车版本号")
    }

    @Test
    fun `迁移抛异常只失败该 mod 其余照常且旧版本号保留`() {
        val trace = mutableListOf<String>()
        val host = newHostWith(mutableListOf(), restoredStore("modA", 1), "modA", "modB")
        val failing = VersionedMod(trace, "a", dataVersion = 2, migrateThrows = true)
        val report = host.install(
            listOf(
                "modA" to failing,
                "modB" to VersionedMod(trace, "b", dataVersion = 5),
            ),
        )

        assertFalse(report[0].active, "迁移失败的 mod 必须失败")
        assertIs<ModDataMigrationException>(report[0].failure)
        assertTrue(trace.contains("a:onMigrate(1->2)"))
        assertFalse(trace.contains("a:onEnable"), "迁移失败不得进入 onEnable")
        assertTrue(report[1].active, "其余 mod 照常装载（per-mod 隔离）")
        assertEquals(listOf("a:onLoad", "a:onMigrate(1->2)", "b:onLoad", "b:onEnable"), trace)
        assertEquals(1, host.storage.dataVersionsByPackage["modA"], "失败不得写回新版本号，下次读档重试")
        assertEquals(5, host.storage.dataVersionsByPackage["modB"])
    }

    @Test
    fun `读档缝降级拒载打 canonical 失败行并停用该包`() {
        val trace = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val host = newHostWith(logs, ModStorageStore(), "modA")
        host.install(listOf("modA" to VersionedMod(trace, "a", dataVersion = 2)))
        assertEquals(listOf("a:onLoad", "a:onEnable"), trace)

        val restored = restoredStore("modA", 3, mapOf("k" to ModValue.StrValue("new")))
        host.rebindStorage(restored)

        assertFalse(trace.contains("a:onMigrate(3->2)"), "降级绝不调 onMigrate")
        assertNotNull(host.failureOf("modA"), "降级必须记录失败")
        assertFalse(restored.isActive("modA"), "拒载后该包段退回非激活（保住数据、不静默改写）")
        val canonical = "mod.install pkg=modA active=false failure=ModDataVersionDowngradeException: "
        assertTrue(
            logs.any { it.startsWith(canonical) },
            "原因必须经既有 mod.install 失败行上屏（诊断面按它解析），实得 $logs",
        )
    }

    // ------------------------------------------------------------------
    // C. 容器承载：dataVersion 持久化与非法登记
    // ------------------------------------------------------------------

    @Test
    fun `数据版本随容器编码解码往返`() {
        val store = ModStorageStore()
        store.activate("p")
        store.apiFor("p").put("k", "v")
        store.setDataVersion("p", 7)

        val restored = ModStorageStore.load(store.encode(), knownPackages = setOf("p"))

        assertEquals(7, restored.dataVersionsByPackage["p"], "dataVersion 必须随侧车往返")
        assertContentEquals(store.encode(), restored.encode(), "往返后逐字节稳定")
    }

    @Test
    fun `非法数据版本登记立即拒绝`() {
        val store = ModStorageStore()
        store.activate("p")

        assertThrows<IllegalArgumentException> { store.setDataVersion("p", -1) }
        assertThrows<IllegalArgumentException> { store.setDataVersion("absent", 1) }
        assertNull(store.dataVersionsByPackage["p"], "未被登记时保持无记录（与旧档明确的 0 区分）")
        assertEquals(0, ModStorageSection("p").dataVersion)
    }
}
