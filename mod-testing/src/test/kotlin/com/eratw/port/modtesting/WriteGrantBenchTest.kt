package com.eratw.port.modtesting

import com.eratw.port.modapi.DimType
import com.eratw.port.modapi.ModManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ModDX4-B1 台架写面定向钉：`TestContext.gameState` 的受控写（授权闸镜像 + 经 [runtime] 提交）。
 *
 * 生产语义链 = 授权闸当场判（IAE 文案逐字对齐）→ `RuntimeScheduler.submit` → 转移边界兑现；
 * 台架的 `TestRuntime` 是同步内联，写立即生效 —— 本文件锁「台架与生产同授权判定、同失败文案」，
 * 排队时序（边界前读不到、边界后读得到）由 core-vm `ModDx4WriteGrantGateTest` 的
 * BoundaryScheduler 用例锁。
 */
class WriteGrantBenchTest {

    private fun world(): TestWorld = TestWorld.ofManifests(
        ModManifest(
            packageId = "test.mod",
            globalDims = listOf(
                com.eratw.port.modapi.GlobalDimDecl("MOD_STATE", DimType.INT),
                com.eratw.port.modapi.GlobalDimDecl("MATRIX", DimType.INT, dims = listOf(10, 10)),
            ),
            writeGrants = listOf(
                com.eratw.port.modapi.WriteGrantDecl("DAY", DimType.INT),
                com.eratw.port.modapi.WriteGrantDecl("PLACE", DimType.STR),
            ),
        ),
        ModManifest(packageId = "other.mod"),
    )

    @Test
    fun grantedKeyWriteIsAppliedImmediatelyOnBench() {
        val world = world()
        val ctx = world.newContext("test.mod")
        ctx.gameState.setIntVar("DAY", 42L)
        assertEquals(42L, ctx.gameState.getIntVar("DAY"))
        assertTrue(ctx.gameState.hasIntVar("DAY"))
        ctx.gameState.setStrVar("place", "神社")
        assertEquals("神社", ctx.gameState.getStrVar("place"))
    }

    @Test
    fun undeclaredKeyWriteThrowsIaeWithProductionWording() {
        val world = world()
        val ctx = world.newContext("test.mod")
        val ex = assertFailsWith<IllegalArgumentException> { ctx.gameState.setIntVar("NOT_GRANTED", 1L) }
        assertTrue(ex.message!!.contains("全局变量写未授权：test.mod/NOT_GRANTED"), ex.message)
        // 未写进去（fail-closed，不静默吞）。
        assertEquals(0L, ctx.gameState.getIntVar("NOT_GRANTED"))
    }

    @Test
    fun grantTypeMismatchThrowsIaeWithoutImplicitConversion() {
        val world = world()
        val ctx = world.newContext("test.mod")
        val ex = assertFailsWith<IllegalArgumentException> { ctx.gameState.setStrVar("DAY", "x") }
        assertTrue(ex.message!!.contains("全局变量写类型不符：test.mod/DAY"), ex.message)
        val strEx = assertFailsWith<IllegalArgumentException> { ctx.gameState.setIntVar("PLACE", 1L) }
        assertTrue(strEx.message!!.contains("全局变量写类型不符：test.mod/PLACE"), strEx.message)
    }

    @Test
    fun ownGlobalDimsAreExemptAndUnaddressableOwnDimIsRejected() {
        val world = world()
        val ctx = world.newContext("test.mod")
        // 自家声明变量免授权。
        ctx.gameState.setIntVar("MOD_STATE", 7L)
        assertEquals(7L, ctx.gameState.getIntVar("MOD_STATE"))
        // 2 维以上自家变量不可寻址（专属文案，与「未授权」可区分）。
        val ex = assertFailsWith<IllegalArgumentException> { ctx.gameState.setIntVar("MATRIX", 1L) }
        assertTrue(ex.message!!.contains("全局变量写不可寻址：test.mod/MATRIX"), ex.message)
    }

    @Test
    fun otherPackageGrantIsNotVisibleAcrossPackages() {
        val world = world()
        val ctx = world.newContext("other.mod")
        val ex = assertFailsWith<IllegalArgumentException> { ctx.gameState.setIntVar("DAY", 1L) }
        assertTrue(ex.message!!.contains("全局变量写未授权：other.mod/DAY"), ex.message)
    }

    @Test
    fun arrangeHelpersBypassTheGate() {
        val world = world()
        val ctx = world.newContext("test.mod")
        // 预置通道（arrange 手段）不经授权闸：未授权键也能预置前置条件。
        ctx.setGameIntVar("NOT_GRANTED", 99L)
        assertEquals(99L, ctx.gameState.getIntVar("NOT_GRANTED"))
    }
}
