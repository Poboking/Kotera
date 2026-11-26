package com.eratw.port.core.mod

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.patch.DeclaredHook
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.patch.HookDeclaration
import com.eratw.port.core.patch.HookPhase
import com.eratw.port.modapi.HookApi
import com.eratw.port.modapi.HookHandler
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.RuntimeScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.eratw.port.modapi.HookPhase as ModHookPhase

/**
 * 函数 hook 只读查询面（[HookApi.registrations]，MODDX2 Lane G3）的语义闸。
 *
 * 钉住的口径：
 * - 投影序 = functionKey 字典序 → priority 升序 → 声明序，与 `HookPipeline` 槽表的确定性序
 *   （同函数内 priority 升序 + 声明序）同源同口径；
 * - 投影源是槽表本身：声明了但未绑定的槽也在投影里，绑定动作不改变投影；
 * - phase 不带「PREFIX 先于 POSTFIX」的偏置，同函数内先后只由 priority / 声明序决定；
 * - 缺省实现显式 fail-closed 抛 UOE（UOE = 宿主未接线，不是没钩子）；
 * - 未装配 context 走快照槽表的诚实答案（空快照 = 空表、不抛），adopt 共享引擎后跟随共享槽表。
 */
class HookApiQueryTest {

    private fun decl(
        pkg: String,
        target: String = "COM300",
        phase: HookPhase = HookPhase.PREFIX,
        priority: Int = 0,
    ): DeclaredHook = DeclaredHook(
        packageId = pkg,
        declaration = HookDeclaration(
            targetKey = target,
            phase = phase,
            priority = priority,
            mayMutateArgs = false,
            mayMutateReturn = false,
            runOnFailure = false,
        ),
    )

    @Test
    fun `注册后投影序确定 functionKey字典序_priority_声明序`() {
        val pipeline = HookPipeline.of(
            listOf(
                decl("pkgA", target = "COM300", priority = 5),
                // COM100 的 priority 故意比 COM300 大：字典序压过 priority，投影序第一段必须是
                // functionKey（只按 priority 排会把 COM100 沉底 —— 反向证明的敏感性钉在这里）。
                decl("pkgB", target = "COM100", priority = 9),
                decl("pkgA", target = "COM300", priority = 1),
                decl("pkgB", target = "COM300", phase = HookPhase.POSTFIX, priority = 1),
            ),
        )
        val api = HookApiAdapter(pipeline, "pkgA")
        // 注册只往已声明槽上绑处理器：绑定动作本身不改槽表，投影序仍由槽表决定。
        api.register("COM300", ModHookPhase.PREFIX) { HookResult.Continue }

        val expected = listOf(
            Triple("COM100", 9, "pkgB"),
            Triple("COM300", 1, "pkgA"), // priority 1：pkgA 的槽先声明
            Triple("COM300", 1, "pkgB"), // priority 1：pkgB 的 POSTFIX 槽后声明
            Triple("COM300", 5, "pkgA"), // priority 5 的槽未绑定，仍在投影里
        )
        assertEquals(
            expected,
            api.registrations().map { Triple(it.functionKey, it.priority, it.packageId) },
        )
        // 同一实例连续两次读一致（排序不依赖 HashMap 遍历序）。
        assertEquals(api.registrations(), api.registrations())
    }

    @Test
    fun `prefix_postfix 区分 kind各归其位_序不带相位偏置`() {
        val pipeline = HookPipeline.of(
            listOf(
                decl("pkgA", target = "COM300", phase = HookPhase.POSTFIX, priority = 1),
                decl("pkgA", target = "COM300", phase = HookPhase.PREFIX, priority = 1),
                decl("pkgA", target = "COM300", phase = HookPhase.POSTFIX, priority = 0),
            ),
        )
        val rows = HookApiAdapter(pipeline, "pkgA").registrations()

        // phase 不参与排序：同函数内先后只由 priority（再声明序）决定 —— POSTFIX priority 0
        // 排在 priority 1 的 PREFIX 之前；同 priority 段内按声明序（POSTFIX 先声明）。
        assertEquals(
            listOf(
                ModHookPhase.POSTFIX to 0,
                ModHookPhase.POSTFIX to 1,
                ModHookPhase.PREFIX to 1,
            ),
            rows.map { it.kind to it.priority },
        )
        assertTrue(rows.all { it.functionKey == "COM300" && it.packageId == "pkgA" })
    }

    @Test
    fun `HookApi 缺省实现 registrations 显式 fail_closed`() {
        val impl = object : HookApi {
            override fun register(
                targetKey: String,
                phase: ModHookPhase,
                handler: HookHandler,
            ): Registration = error("本用例只测缺省实现，不注册")
        }

        val failure = assertThrows<UnsupportedOperationException> { impl.registrations() }
        assertTrue(failure.message!!.contains("hooks.registrations()"))
        assertTrue(failure.message!!.contains("未接线"))
    }

    @Test
    fun `两 mod 同函数投影齐且带各自 packageId`() {
        val pipeline = HookPipeline.of(
            listOf(
                decl("mod.alpha", target = "COM300", priority = 5),
                decl("mod.beta", target = "COM300", priority = 5),
            ),
        )
        val alphaApi = HookApiAdapter(pipeline, "mod.alpha")
        val betaApi = HookApiAdapter(pipeline, "mod.beta")
        alphaApi.register("COM300", ModHookPhase.PREFIX) { HookResult.Continue }
        betaApi.register("COM300", ModHookPhase.PREFIX) { HookResult.Continue }

        // 两个包各自的面看到的都是同一张共享槽表的全量投影（跨 mod 自查「谁在钩同一函数」）：
        // 同 priority → 声明序，mod.alpha 先声明在前。
        assertEquals(listOf("mod.alpha", "mod.beta"), alphaApi.registrations().map { it.packageId })
        assertEquals(alphaApi.registrations(), betaApi.registrations())
    }

    @Test
    fun `未装配 context 投影走快照槽表_adopt共享引擎后跟随`() {
        val context = newUnassembledContext()
        // 直连构造的 context：hookPipeline 构造期由冻结快照建成（本快照无 hook 声明 → 空表，不抛）。
        assertTrue(context.hooks.registrations().isEmpty())

        // adopt 换全链共享引擎后，投影自动跟随共享槽表（含别的包的槽 —— 跨 mod 自查的生产形态）。
        context.adoptHookPipeline(HookPipeline.of(listOf(decl("other.pkg", target = "COM300"))))
        val rows = context.hooks.registrations()
        assertEquals(1, rows.size)
        assertEquals("COM300", rows[0].functionKey)
        assertEquals("other.pkg", rows[0].packageId)
    }

    // ---- 直连构造 context 的最小依赖（照 EventApiQueryTest 同形，未走宿主装配）----

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

    private fun newSnapshot(packageId: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        registry.addPackage(ModDeclarationAdapter.toDescriptor(ModManifest(packageId = packageId)))
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true

        override fun submit(action: () -> Unit) {
            action()
        }
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
}
