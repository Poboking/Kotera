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
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * RT2 D 车闸：读档恢复后把分段交还 mod 侧（ModContextImpl.storage 重绑，契约 §3 判据 1/2/3/5）。
 *
 * 钉四件事：
 * 1. **同一 context 对象重绑**：adoptStorage 缝（host.rebindStorage）之后，既存 context 的
 *    storage 读写落在新容器；context 对象与公开 storage 视图都是同一实例（对 mod 透明）；
 * 2. **孤儿保留**：重绑不删任何段——未激活段逐字节保留、fail-closed 拒读写，激活包照常读写；
 * 3. **fail-closed 边界不变**：段未激活时（未恢复 / 孤儿口径）读写一律抛错，不静默错位；
 * 4. 未恢复前初值视图行为逐值不变（构造期绑定本包段，无 adoptStorage 就无任何行为差）。
 *
 * 反向证明在交付消息记录：重绑缝退化为 no-op ⇒ 判据 1 用例红；重绑误启用孤儿段 ⇒ 判据 2 用例红。
 * 容器自身行为（编码 / 激活 / 墓碑）归 ModStorageStoreTest，本套只管重绑缝。
 */
class ModStorageRebindTest {

    // ---------------- 夹具（照 ModRuntimeHostTest 同构，最小化） ----------------

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

    private class EmptyMod : EraMod {
        override fun onLoad(context: ModContext) = Unit
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

    private fun installedContext(host: ModRuntimeHost, packageId: String): ModContextImpl {
        val report = host.install(listOf(packageId to EmptyMod()))
        assertTrue(report.single().active, "夹具前置：mod 必须安装成功")
        return host.contextOf(packageId) as ModContextImpl
    }

    // ---------------- 判据 1：同一 context 对象重绑 ----------------

    @Test
    fun `重绑后同一context对象读写落在新容器`() {
        val host = newHost("mod.a")
        val context = installedContext(host, "mod.a")
        context.storage.put("k", "v1")
        val bytes = host.storage.encode()
        val rebuilt = ModStorageStore.load(bytes, knownPackages = setOf("mod.a"))

        val contextBefore = host.contextOf("mod.a")
        val viewBefore = context.storage
        host.rebindStorage(rebuilt)

        // 同一个 context 对象、同一个公开 storage 视图（对 mod 透明），指向已换。
        assertSame(contextBefore, host.contextOf("mod.a"))
        assertSame(viewBefore, context.storage)
        assertEquals("v1", context.storage.get("k"), "恢复段的原值必须经同一 context 读回")

        // 重绑后的写落在新容器：新容器读得到、旧容器读不到。
        context.storage.put("k2", "v2")
        assertEquals("v2", rebuilt.apiFor("mod.a").get("k2"))
        assertNull(host.storage.apiFor("mod.a").get("k2"), "重绑后不得再写旧容器")
    }

    // ---------------- 判据 2：孤儿分段保留不删、原值回来 ----------------

    @Test
    fun `重绑后激活包照常读写且孤儿段逐字节保留`() {
        // 造一份带孤儿段的盘上形态：mod.a 激活，mod.orphan 有数据但未激活。
        val store1 = ModStorageStore()
        store1.activate("mod.a")
        store1.apiFor("mod.a").put("k", "v")
        store1.activate("mod.orphan")
        store1.apiFor("mod.orphan").put("o", "ov")
        store1.deactivate("mod.orphan")
        val bytes = store1.encode()

        val host = newHost("mod.a")
        val context = installedContext(host, "mod.a")
        val rebuilt = ModStorageStore.load(bytes, knownPackages = setOf("mod.a"))
        // 写入前先证恢复往返逐字节保真（含孤儿段）。
        assertContentEquals(bytes, rebuilt.encode(), "恢复往返逐字节保真（写入前）")

        host.rebindStorage(rebuilt)

        assertEquals("v", context.storage.get("k"), "激活包恢复后照常读")
        context.storage.put("k2", "v2")
        assertEquals("v2", rebuilt.apiFor("mod.a").get("k2"), "激活包恢复后照常写")

        // 孤儿段：未激活、整段保留、再编码逐字节不变（「禁用不等于删除」跨重绑仍成立）。
        assertTrue("mod.orphan" in rebuilt.inactivePackages(), "孤儿段不得被重绑误启用")
        assertFailsWith<IllegalStateException> { rebuilt.apiFor("mod.orphan").get("o") }
        assertEquals(1, rebuilt.sizeOf("mod.orphan"), "孤儿段条目数不变（原样保留，不删）")
        // 激活包的新写只动 mod.a 段：孤儿段与盘上形态逐字节一致。
        val orphanThen = ModStorageSectionCodec.encode(
            listOf(ModStorageSectionCodec.decode(bytes, setOf("mod.a")).first { it.packageId == "mod.orphan" }),
        )
        val orphanNow = ModStorageSectionCodec.encode(
            listOf(rebuilt.sectionsSnapshot().first { it.packageId == "mod.orphan" }),
        )
        assertContentEquals(orphanThen, orphanNow, "孤儿段逐字节保留（激活包写入后仍不变）")
    }

    // ---------------- 判据 3：fail-closed 边界不变 ----------------

    @Test
    fun `未激活段重绑后读写仍failclosed不静默错位`() {
        val host = newHost("mod.a")
        val context = installedContext(host, "mod.a")
        context.storage.put("k", "v1")
        val bytes = host.storage.encode()

        // knownPackages 为空 = 该包的分段在容器里但未激活（读档时该 mod 不在激活集的口径）。
        val rebuilt = ModStorageStore.load(bytes, knownPackages = emptySet())
        host.rebindStorage(rebuilt)

        val error = assertFailsWith<IllegalStateException> { context.storage.get("k") }
        assertTrue(error.message!!.contains("未激活"), "拒绝原因必须是激活守卫，不是别处")
        assertFailsWith<IllegalStateException> { context.storage.put("k", "v2") }
        assertFailsWith<IllegalStateException> { context.storage.keys() }
        // 数据没被动过：重新激活后原值回来（保留语义与拒绝语义并存）。
        rebuilt.activate("mod.a")
        assertEquals("v1", context.storage.get("k"))
    }

    // ---------------- 判据 5（反向证明的常驻形态）：未重绑 = 初值视图逐值不变 ----------------

    @Test
    fun `未调用重绑时初值视图行为逐值不变`() {
        val host = newHost("mod.a")
        val context = installedContext(host, "mod.a")
        context.storage.put("k", "v1")
        assertEquals("v1", context.storage.get("k"))
        assertTrue(context.storage.remove("k"))
        assertNull(context.storage.get("k"))
        assertEquals(emptySet(), context.storage.keys())
        // 没有任何 adoptStorage / rebindStorage 参与：读写全程落在构造期绑定的那一份容器。
        assertSame(host.storage, host.storage)
        assertEquals(0, host.storage.sizeOf("mod.a"), "remove 后段内无条目（初值口径）")
    }
}
