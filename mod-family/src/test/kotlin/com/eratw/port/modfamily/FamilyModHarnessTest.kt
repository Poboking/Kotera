package com.eratw.port.modfamily

import com.eratw.port.modapi.create
import com.eratw.port.modapi.get
import com.eratw.port.modapi.getInt
import com.eratw.port.modapi.has
import com.eratw.port.modfamily.generated.FamilyKeys
import com.eratw.port.modtesting.TestWorld
import com.eratw.port.modtesting.runMod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 作者可以不启引擎验业务逻辑的示范：同一条「逐日成长」业务，这次只用 `:mod-testing`。
 *
 * 与 [FamilyModTest] 的区别：那边把 mod 装进真的 `ModRuntimeHost`（依赖 `:core-vm`，验的是
 * 「宿主接线 + 引擎语义」）；本用例只装进内存台架（main 依赖链仍只有 `:mod-api`），验的是
 * 「业务逻辑本身」。两条都保留：前者防宿主回归，后者给作者一条秒级反馈路径。
 */
class FamilyModHarnessTest {

    @Test
    fun growthJobAdvancesEveryAdoptedChildWithoutEngine() {
        val world = TestWorld.of(FamilyDeclarations)
        val mod = FamilyMod()
        val session = runMod(mod, world, FamilyDeclarations)
        // 载体新读值路径（成长量/称号）每次现读设置；台架按声明默认值预置，保住原用例语义。
        session.context.settingsRecorder.declare(FamilyDeclarations.GROWTH_PER_DAY_KEY, "1")
        session.context.settingsRecorder.declare(FamilyDeclarations.SHOW_TITLES_KEY, "true")
        val father = world.entities.create(FamilyKeys.archetype)
        val child = mod.adoptChild("甲", father)
        val second = mod.adoptChild("乙", father = null)
        assertTrue(world.relationships.has(father, FamilyDeclarations.PARENT, child))

        repeat(TICKS) { session.context.fireJob(FamilyDeclarations.GROWTH) }

        assertEquals(TICKS.toLong(), mod.state.growthTicks)
        assertEquals(TICKS.toLong(), world.entities.getInt(child, FamilyKeys.age))
        assertEquals(TICKS.toLong(), world.entities.getInt(second, FamilyKeys.age))
        assertEquals(2, mod.state.registeredChildren)
    }

    @Test
    fun disabledModPersistsTickCountAndReloadsIt() {
        val world = TestWorld.of(FamilyDeclarations)
        val mod = FamilyMod()
        val session = runMod(mod, world, FamilyDeclarations)
        session.context.settingsRecorder.declare(FamilyDeclarations.GROWTH_PER_DAY_KEY, "1")
        session.context.settingsRecorder.declare(FamilyDeclarations.SHOW_TITLES_KEY, "true")
        mod.adoptChild("甲", father = null)
        repeat(TICKS) { session.context.fireJob(FamilyDeclarations.GROWTH) }

        session.disable()
        assertEquals(TICKS.toString(), session.context.storage.get(FamilyDeclarations.GROWTH_TICKS))

        // 同一世界换新实例：禁用时写回的计数要在 onLoad 时读回来（§18.6 存读档对账）。
        val reloaded = FamilyMod()
        val reloadedSession = runMod(reloaded, world, FamilyDeclarations)
        assertEquals(TICKS.toLong(), reloaded.state.growthTicks)
        reloadedSession.unload()
    }

    private companion object {
        const val TICKS: Int = 3
    }
}
