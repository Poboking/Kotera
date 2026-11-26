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
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.modapi.BoolSettingDecl
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.EnumSettingDecl
import com.eratw.port.modapi.IntSettingDecl
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.SettingKind
import com.eratw.port.modapi.StrSettingDecl
import com.eratw.port.modapi.modDeclarations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 宿主设置声明读数缝的定向钉（MODDX2-G1）：[ModRuntimeHost.declaredSettings] 从 install 时
 * 已登记的声明投影（[ModRuntimeHost.settings] 里的 `ModSettingsSnapshot`）反向翻译回作者面
 * `SettingDecl` 形态，不另建第二张登记表。
 *
 * 四条判据：
 * 1. 四型（int/str/bool/enum）声明注入后按声明序可查，字段逐项还原（键/默认值/界/候选/标题/说明）；
 * 2. 未登记 / 未装载的包空表不抛 —— 且**真源是登记表而非冻结快照**（快照在
 *    [com.eratw.port.core.patch.DeclarationSnapshot] 里本来就不带 settings，登记表未注入时
 *    读数缝如实返回空，不从快照猜）；
 * 3. 未声明设置项的 mod 空表（生产口径：装配侧对每个启用的包都产快照，即使没有声明）；
 * 4. 重复键由**既有三道闸**在登记之前逐级拒（作者面 manifest 构造闸 → core descriptor 构造闸
 *    → 登记表快照构造闸），读数缝不自设闸、也不放大闸面 —— 重复键根本到不了读数缝。
 *
 * 反向证明在交付消息记录：把 [ModRuntimeHost.declaredSettings] 的投影源从
 * `settings[packageId]?.declarations` 换成 `snapshot` 侧 ⇒ 判据 1 立红（冻结快照不带
 * settings，查出来的恒为空表）；把 `ModDeclarationAdapter.toSettingDecl` 的四型映射摘掉
 * ⇒ 判据 1 的字段断言立红。
 */
class ModRuntimeHostDeclaredSettingsTest {

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

    /** 四型声明齐全的 manifest（作者面 DSL 原文，声明序 = int → str → bool → enum）。 */
    private fun manifestOf(packageId: String): ModManifest = modDeclarations(packageId, version = "1.0") {
        intSetting(key = "s.int", default = 1L, range = 0L..2L, title = "整数", description = "整数说明")
        strSetting(key = "s.str", default = "原文", title = "字符串")
        boolSetting(key = "s.bool", default = true, title = "布尔")
        enumSetting(key = "s.enum", default = "b", candidates = listOf("a", "b", "c"), title = "枚举")
    }

    /** 一个不声明任何设置项的 mod（settings 段缺省 = 空清单）。 */
    private fun bareManifestOf(packageId: String): ModManifest =
        modDeclarations(packageId, version = "1.0") {
            // 故意留空：本用例的判据就是「没声明任何设置项」的 mod。
        }

    private fun snapshotOf(vararg manifests: ModManifest): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        for (manifest in manifests) {
            registry.addPackage(ModDeclarationAdapter.toDescriptor(manifest))
        }
        return registry.freeze()
    }

    private fun newHost(snapshot: DeclarationSnapshot): ModRuntimeHost = ModRuntimeHost(
        snapshot = snapshot,
        entityKernel = newKernel(),
        nativeMethods = NativeMethodRegistry(),
        logSink = {},
        runtime = FakeRuntime(),
    )

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) = action()
    }

    private class BareMod : EraMod {
        override fun onLoad(context: ModContext) = Unit
        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    // ---------------- 判据 1：四型声明注入后按声明序投影回作者面 ----------------

    @Test
    fun `四型声明经读数缝按声明序投影回作者面`() {
        val host = newHost(snapshotOf(manifestOf("mod.a")))
        // 登记面照生产口径构造：app 侧从 manifest 的 settings 段解出声明注入（ModSettingsStore）。
        host.settings = mapOf(
            "mod.a" to ModSettingsSnapshot(
                "mod.a",
                ModDeclarationAdapter.toDescriptor(manifestOf("mod.a")).settings,
            ),
        )
        val report = host.install(listOf("mod.a" to BareMod()))
        assertTrue(report.single().active, "夹具前置：注入后 mod 必须安装成功")

        val queried = host.declaredSettings("mod.a")
        assertEquals(listOf("s.int", "s.str", "s.bool", "s.enum"), queried.map { it.key }, "声明序保持")

        val intDecl = queried.filterIsInstance<IntSettingDecl>().single()
        assertEquals(SettingKind.INT, intDecl.kind)
        assertEquals(1L, intDecl.default)
        assertEquals(0L, intDecl.min)
        assertEquals(2L, intDecl.max)
        assertEquals("整数", intDecl.title)
        assertEquals("整数说明", intDecl.description)

        val strDecl = queried.filterIsInstance<StrSettingDecl>().single()
        assertEquals(SettingKind.STR, strDecl.kind)
        assertEquals("原文", strDecl.default)
        assertEquals("字符串", strDecl.title)

        val boolDecl = queried.filterIsInstance<BoolSettingDecl>().single()
        assertEquals(SettingKind.BOOL, boolDecl.kind)
        assertTrue(boolDecl.default)

        val enumDecl = queried.filterIsInstance<EnumSettingDecl>().single()
        assertEquals(SettingKind.ENUM, enumDecl.kind)
        assertEquals("b", enumDecl.default)
        assertEquals(listOf("a", "b", "c"), enumDecl.candidates)
    }

    // ---------------- 判据 2：未登记 / 未装载的包空表不抛 ----------------

    @Test
    fun `未登记或未装载的包返回空表不抛`() {
        val host = newHost(snapshotOf(manifestOf("mod.a")))
        // 登记表未注入（台架形态）：即使冻结快照里装着 mod.a 的 manifest 声明，读数缝也如实返回
        // 空表 —— 真源是登记表，不是冻结快照（快照本就不带 settings，见取证结论）。
        val report = host.install(listOf("mod.a" to BareMod()))
        assertTrue(report.single().active, "夹具前置：mod 必须安装成功")
        assertTrue(host.declaredSettings("mod.a").isEmpty(), "登记表未注入 = 空集，不从快照猜")
        assertTrue(host.declaredSettings("mod.never").isEmpty(), "未装载未登记的包 = 空表不抛")
    }

    // ---------------- 判据 3：未声明设置项的 mod 空表 ----------------

    @Test
    fun `未声明设置项的mod返回空表`() {
        val host = newHost(snapshotOf(bareManifestOf("mod.quiet")))
        // 生产口径：装配侧对每个启用的包都产快照，即使没声明任何设置项（ModSettingsStore）。
        host.settings = mapOf("mod.quiet" to ModSettingsSnapshot("mod.quiet", emptyList()))
        val report = host.install(listOf("mod.quiet" to BareMod()))
        assertTrue(report.single().active, "夹具前置：mod 必须安装成功")
        assertTrue(host.declaredSettings("mod.quiet").isEmpty())
    }

    // ---------------- 判据 4：重复键由既有闸拒，读数缝不自设闸 ----------------

    @Test
    fun `重复键由既有三道闸在登记之前逐级拒绝`() {
        // 闸一（作者面）：ModManifest 构造期（mod-api ModDeclarations.kt 的「设置项键在本包内
        // 必须唯一」）—— 重复键的声明清单在 DSL build 收口时就拒，manifest 根本不存在。
        assertFailsWith<IllegalArgumentException> {
            modDeclarations("mod.dup", version = "1.0") {
                intSetting(key = "dup.key", default = 1L)
                intSetting(key = "dup.key", default = 2L)
            }
        }

        // 闸二（core 声明事务形态）：ModuleDescriptor 构造期（「settings 段设置项键在本包内
        // 必须唯一」）—— 即便绕过闸一直接构造 core 形态，descriptor 也拒。
        val duplicateDeclarations = listOf(
            IntSettingDeclaration(key = "dup.key", default = 1L),
            IntSettingDeclaration(key = "dup.key", default = 2L),
        )
        assertFailsWith<IllegalArgumentException> {
            ModuleDescriptor(packageId = "mod.dup", settings = duplicateDeclarations)
        }

        // 闸三（登记面）：ModSettingsSnapshot 构造期（「设置快照的声明键重复」）—— 登记表
        // 注入前最后一道。三道闸全在登记之前，读数缝无须自设闸（如实登记既有行为，不新造）。
        assertFailsWith<IllegalArgumentException> {
            ModSettingsSnapshot("mod.dup", duplicateDeclarations)
        }
    }
}
