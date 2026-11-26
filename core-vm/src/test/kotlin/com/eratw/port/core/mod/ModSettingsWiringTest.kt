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
import com.eratw.port.core.patch.IntSettingDeclaration
import com.eratw.port.core.patch.SettingDeclaration
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.modDeclarations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * per-mod 设置面的运行期钉（RT2 per-mod 配置，契约判据「改完 → 下次 attach 读到新值；
 * 没改 → 默认值；未注入 → fail-closed」）：
 *
 * 1. **注入 → 读到**：`host.settings` 里按包放的 [ModSettingsSnapshot]（声明来自 manifest
 *    的 `settings` 段，值来自设备侧 `<pkg>.settings` 文件）在 `onLoad` 前装进
 *    `context.settings`，读到的是持久化值；
 * 2. **未改 → 默认值**：持久化表缺键 ⇒ 回落作者声明的默认值；
 * 3. **坏值 → 默认值 + 不崩**：越界 / 非 true,false / 候选外 / 超长 ⇒ 回落默认（配置面不是
 *    存档数据面，坏值不抛）；
 * 4. **未注入 → fail-closed**：宿主没注入快照时 `context.settings` 抛
 *    [UnsupportedOperationException]，**不**静默返回声明默认值（否则作者以为设置生效了）；
 * 5. **只有声明过的键可读**：未声明键 / 类型不符 getter 抛 [IllegalArgumentException]，
 *    不隐式转换、不返回 null。
 *
 * 反向证明在交付消息记录：把 [ModRuntimeHost.install] 里的 `settings[packageId]?.let {
 * context.adoptSettings(it) }` 摘掉 ⇒ 判据 1 用例立红（退化成 fail-closed）；把
 * `ModSettingsSnapshot.resolveText` 的合法性校验去掉 ⇒ 判据 3 立红。
 */
class ModSettingsWiringTest {

    private fun newKernel(): EntityKernel = EntityKernel(
        schemas = listOf(
            ComponentSchema(
                packageId = "mod.a",
                name = "Stats",
                fields = listOf(ComponentFieldSpec("hp", ComponentFieldType.INT)),
            ),
        ),
        relationshipSchemas = listOf(RelationshipSchema(name = "PARENT")),
        archetypes = listOf(EntityArchetype("Child", listOf("Stats"))),
    )

    private fun manifestOf(packageId: String): ModManifest = modDeclarations(packageId, version = "1.0") {
        intSetting(key = "cfg.volume", default = 5L, range = 0L..10L, title = "音量")
        strSetting(key = "cfg.label", default = "默认标签", title = "标签")
        boolSetting(key = "cfg.auto", default = true, title = "自动")
        enumSetting(key = "cfg.mode", default = "b", candidates = listOf("a", "b", "c"), title = "模式")
    }

    /** 声明清单取自 manifest 的 `settings` 段（与 app 侧 `ModSettingsStore.snapshotsFor` 同口径）。 */
    private fun declarationsOf(manifest: ModManifest): List<SettingDeclaration> =
        ModDeclarationAdapter.toDescriptor(manifest).settings

    private fun newSnapshot(packageId: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        registry.addPackage(ModDeclarationAdapter.toDescriptor(manifestOf(packageId)))
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) = action()
    }

    private fun newSnapshot(vararg packages: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        for (pkg in packages) {
            registry.addPackage(ModDeclarationAdapter.toDescriptor(manifestOf(pkg)))
        }
        return registry.freeze()
    }

    /** 在 onLoad 里按脚本读设置面的测试 mod（记录观察值，异常原样上抛给宿主回滚）。 */
    private class ScriptedMod(private val script: (ModContext) -> Unit) : EraMod {
        override fun onLoad(context: ModContext) = script(context)
        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    private fun newHost(vararg packages: String): ModRuntimeHost = ModRuntimeHost(
        snapshot = newSnapshot(*packages),
        entityKernel = newKernel(),
        nativeMethods = NativeMethodRegistry(),
        logSink = {},
        runtime = FakeRuntime(),
    )

    private fun snapshotFor(packageId: String, persisted: Map<String, String>): ModSettingsSnapshot =
        ModSettingsSnapshot(packageId, declarationsOf(manifestOf(packageId)), persisted)

    // ---------------- 判据 1：注入 → onLoad 读到持久化值 ----------------

    @Test
    fun `注入快照后onLoad读到持久化值`() {
        val host = newHost("mod.a")
        host.settings = mapOf(
            "mod.a" to snapshotFor(
                "mod.a",
                mapOf(
                    "cfg.volume" to "7",
                    "cfg.label" to "改过的标签",
                    "cfg.auto" to "false",
                    "cfg.mode" to "c",
                ),
            ),
        )
        var read: List<Any> = emptyList()
        val report = host.install(
            listOf(
                "mod.a" to ScriptedMod { context ->
                    read = listOf(
                        context.settings.int("cfg.volume"),
                        context.settings.str("cfg.label"),
                        context.settings.bool("cfg.auto"),
                        context.settings.enumName("cfg.mode"),
                    )
                },
            ),
        )
        assertTrue(report.single().active, "夹具前置：注入后 mod 必须安装成功")
        assertEquals(listOf<Any>(7L, "改过的标签", false, "c"), read)
    }

    @Test
    fun `按包隔离每包只读自己的快照`() {
        val host = newHost("mod.a", "mod.b")
        host.settings = mapOf(
            "mod.a" to snapshotFor("mod.a", mapOf("cfg.volume" to "9")),
            "mod.b" to snapshotFor("mod.b", mapOf("cfg.volume" to "2")),
        )
        val reads = mutableMapOf<String, Long>()
        val report = host.install(
            listOf(
                "mod.a" to ScriptedMod { reads[it.packageId] = it.settings.int("cfg.volume") },
                "mod.b" to ScriptedMod { reads[it.packageId] = it.settings.int("cfg.volume") },
            ),
        )
        assertTrue(report.all { it.active }, "两包都应安装成功：$report")
        assertEquals(mapOf("mod.a" to 9L, "mod.b" to 2L), reads)
    }

    // ---------------- 判据 2：没改 → 声明默认值 ----------------

    @Test
    fun `持久化表缺键时回落声明默认值`() {
        val host = newHost("mod.a")
        host.settings = mapOf("mod.a" to snapshotFor("mod.a", emptyMap()))
        var read: List<Any> = emptyList()
        host.install(
            listOf(
                "mod.a" to ScriptedMod { context ->
                    read = listOf(
                        context.settings.int("cfg.volume"),
                        context.settings.str("cfg.label"),
                        context.settings.bool("cfg.auto"),
                        context.settings.enumName("cfg.mode"),
                    )
                },
            ),
        )
        assertEquals(listOf<Any>(5L, "默认标签", true, "b"), read)
    }

    // ---------------- 判据 3：坏值 → 默认值 + 不崩 ----------------

    @Test
    fun `坏持久值逐类回落默认值且不影响装上`() {
        val host = newHost("mod.a")
        host.settings = mapOf(
            "mod.a" to snapshotFor(
                "mod.a",
                mapOf(
                    "cfg.volume" to "99", // 越界（max = 10）
                    "cfg.label" to "x".repeat(600), // 超长（> MAX_TEXT_LENGTH）
                    "cfg.auto" to "yes", // 不是 true/false
                    "cfg.mode" to "zzz", // 不在候选
                ),
            ),
        )
        var read: List<Any> = emptyList()
        val report = host.install(
            listOf(
                "mod.a" to ScriptedMod { context ->
                    read = listOf(
                        context.settings.int("cfg.volume"),
                        context.settings.str("cfg.label"),
                        context.settings.bool("cfg.auto"),
                        context.settings.enumName("cfg.mode"),
                    )
                },
            ),
        )
        assertTrue(report.single().active, "坏值必须回落默认而不是让 mod 装不上：$report")
        assertEquals(listOf<Any>(5L, "默认标签", true, "b"), read)
    }

    // ---------------- 判据 4：未注入 → fail-closed ----------------

    @Test
    fun `未注入快照时读取抛UnsupportedOperationException不返回默认值`() {
        val host = newHost("mod.a")
        var thrown: Throwable? = null
        val report = host.install(
            listOf(
                "mod.a" to ScriptedMod { context ->
                    try {
                        context.settings.int("cfg.volume")
                    } catch (t: Throwable) {
                        thrown = t
                        throw t
                    }
                },
            ),
        )
        val ex = thrown
        assertTrue(ex is UnsupportedOperationException, "必须是显式 fail-closed：$ex")
        assertTrue("mod.a" in ex.message.orEmpty(), "错误话术必须点名包：${ex.message}")
        assertTrue("不提供静默默认值" in ex.message.orEmpty(), "必须拒绝静默默认值：${ex.message}")
        assertTrue(!report.single().active, "未注入时 onLoad 抛错 ⇒ 该 mod 必须回滚而不是半装")
    }

    @Test
    fun `未注入时keys与has同样fail_closed`() {
        val host = newHost("mod.a")
        val thrown = mutableListOf<Throwable>()
        host.install(
            listOf(
                "mod.a" to ScriptedMod { context ->
                    thrown += assertFailsWith<UnsupportedOperationException> { context.settings.keys }
                    thrown += assertFailsWith<UnsupportedOperationException> { context.settings.has("cfg.volume") }
                },
            ),
        )
        assertEquals(2, thrown.size)
    }

    // ---------------- 判据 5：只有声明过的键可读 ----------------

    @Test
    fun `未声明键读取抛错并点名包与键`() {
        val host = newHost("mod.a")
        host.settings = mapOf("mod.a" to snapshotFor("mod.a", emptyMap()))
        var thrown: Throwable? = null
        val report = host.install(
            listOf(
                "mod.a" to ScriptedMod { context ->
                    try {
                        context.settings.int("cfg.nope")
                    } catch (t: Throwable) {
                        thrown = t
                        throw t
                    }
                },
            ),
        )
        val ex = thrown
        assertTrue(ex is IllegalArgumentException, "未声明键必须是调用方错误：$ex")
        assertTrue("mod.a" in ex.message.orEmpty() && "cfg.nope" in ex.message.orEmpty(), "${ex.message}")
        assertTrue(!report.single().active)
    }

    @Test
    fun `类型不符的getter抛错不做隐式转换`() {
        val host = newHost("mod.a")
        host.settings = mapOf("mod.a" to snapshotFor("mod.a", mapOf("cfg.volume" to "3")))
        var thrown: Throwable? = null
        host.install(
            listOf(
                "mod.a" to ScriptedMod { context ->
                    try {
                        context.settings.str("cfg.volume")
                    } catch (t: Throwable) {
                        thrown = t
                        throw t
                    }
                },
            ),
        )
        val ex = thrown
        assertTrue(ex is IllegalArgumentException, "类型不符必须抛：$ex")
        assertTrue("类型不符" in ex.message.orEmpty(), "${ex.message}")
    }

    @Test
    fun `keys与has只报声明过的键且保持声明序`() {
        val host = newHost("mod.a")
        host.settings = mapOf("mod.a" to snapshotFor("mod.a", mapOf("cfg.nope" to "1")))
        var keys: List<String> = emptyList()
        var declared = false
        var undeclared = true
        host.install(
            listOf(
                "mod.a" to ScriptedMod { context ->
                    keys = context.settings.keys.toList()
                    declared = context.settings.has("cfg.volume")
                    undeclared = context.settings.has("cfg.nope")
                },
            ),
        )
        assertEquals(listOf("cfg.volume", "cfg.label", "cfg.auto", "cfg.mode"), keys)
        assertTrue(declared)
        assertTrue(!undeclared, "没声明的键不属于本包设置面")
    }

    @Test
    fun `没有设置声明的包任何键都算未声明`() {
        val plain = ModManifest(packageId = "mod.plain")
        val declarations = ModDeclarationAdapter.toDescriptor(plain).settings
        assertTrue(declarations.isEmpty(), "三版四版包（无声明）不产生设置声明")
        val snapshot = ModSettingsSnapshot("mod.plain", declarations, mapOf("cfg.volume" to "1"))
        assertEquals(emptyList(), snapshot.declaredKeys)
        assertTrue(!snapshot.declares("cfg.volume"))
        assertFailsWith<IllegalArgumentException> { snapshot.resolveText("cfg.volume") }
        val host = newHost("mod.plain")
        host.settings = mapOf("mod.plain" to snapshot)
        var thrown: Throwable? = null
        host.install(
            listOf(
                "mod.plain" to ScriptedMod { context ->
                    try {
                        context.settings.int("cfg.volume")
                    } catch (t: Throwable) {
                        thrown = t
                        throw t
                    }
                },
            ),
        )
        assertTrue(thrown is IllegalArgumentException, "无声明包读任何键都必须拒：$thrown")
    }
}
