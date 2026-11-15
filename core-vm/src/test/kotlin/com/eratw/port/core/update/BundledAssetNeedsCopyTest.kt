package com.eratw.port.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 随包/更新解包逐文件判据表（[UpdatePolicy.bundledAssetNeedsCopy]）的定向钉。
 *
 * CONTRACT-BOOTUNPACK：把既有的「更新档」[forceReplace] 与新补的「让位档」
 * [defersToDisk] 放进同一张表，随包解包由此复用解包器自己的档位机制，
 * 不再由更上层（LoadPipeline）用一条 if 把整轮解包短路。
 *
 * 全表穷举钉真值表（4 个布尔入参 = 16 组），另给四条具名用例说明每档判错的代价。
 * 反向证明：撤掉判据表里 `defersToDisk -> !targetPresent` 这一支 ⇒ 第 1、3 条判红。
 */
class BundledAssetNeedsCopyTest {

    private fun needs(
        forceReplace: Boolean,
        defersToDisk: Boolean,
        targetPresent: Boolean,
        shapeStale: Boolean,
    ): Boolean = UpdatePolicy.bundledAssetNeedsCopy(forceReplace, defersToDisk, targetPresent, shapeStale)

    @Test
    fun `让位档下已在位的资产不比长度也不看 sha 漂移一律不拷`() {
        // 盘上数据 23 版、APK 随包 22 版：新语料长度与旧语料不同，首启档会整树重写
        // 约 919 MiB 并把更新打回旧版。让位档是这条链的唯一根修。
        assertFalse(needs(forceReplace = false, defersToDisk = true, targetPresent = true, shapeStale = true))
        assertFalse(needs(forceReplace = false, defersToDisk = true, targetPresent = true, shapeStale = false))
    }

    @Test
    fun `让位档下目标缺失仍补拷不让玩家的手删变成永久缺文件`() {
        assertTrue(needs(forceReplace = false, defersToDisk = true, targetPresent = false, shapeStale = true))
        assertTrue(needs(forceReplace = false, defersToDisk = true, targetPresent = false, shapeStale = false))
    }

    @Test
    fun `更新档优先于让位档同长新立绘必须换掉`() {
        // 数据更新通道走 forceReplace = true；让位档不得把它拦成「已在位即跳过」。
        assertTrue(needs(forceReplace = true, defersToDisk = true, targetPresent = true, shapeStale = false))
        assertTrue(needs(forceReplace = true, defersToDisk = true, targetPresent = true, shapeStale = true))
        assertTrue(needs(forceReplace = true, defersToDisk = false, targetPresent = true, shapeStale = false))
    }

    @Test
    fun `两档都关时逐字回到首启档形状判据`() {
        // 老调用点（没传盘上版本）与换 APK / 同版重装：长度不符就重拷，符合就跳过。
        assertFalse(needs(forceReplace = false, defersToDisk = false, targetPresent = true, shapeStale = false))
        assertTrue(needs(forceReplace = false, defersToDisk = false, targetPresent = true, shapeStale = true))
        assertTrue(needs(forceReplace = false, defersToDisk = false, targetPresent = false, shapeStale = false))
    }

    @Test
    fun `四个入参的十六组真值表逐格钉死`() {
        // forceReplace > defersToDisk > 形状判据；shapeStale 只在两档都关时有意义。
        val table = listOf(
            // forceReplace, defersToDisk, targetPresent, shapeStale, expected
            listOf(false, false, false, false, true),
            listOf(false, false, false, true, true),
            listOf(false, false, true, false, false),
            listOf(false, false, true, true, true),
            listOf(false, true, false, false, true),
            listOf(false, true, false, true, true),
            listOf(false, true, true, false, false),
            listOf(false, true, true, true, false),
            listOf(true, false, false, false, true),
            listOf(true, false, false, true, true),
            listOf(true, false, true, false, true),
            listOf(true, false, true, true, true),
            listOf(true, true, false, false, true),
            listOf(true, true, false, true, true),
            listOf(true, true, true, false, true),
            listOf(true, true, true, true, true),
        )
        assertEquals(16, table.size, "真值表必须覆盖 16 组")
        for (row in table) {
            val force = row[0]
            val defer = row[1]
            val present = row[2]
            val stale = row[3]
            val expected = row[4]
            // kotlin.test.assertEquals 的 message 是第三参（与 JUnit 的首参顺序相反）。
            assertEquals(
                expected,
                needs(force, defer, present, stale),
                "force=$force defer=$defer present=$present stale=$stale",
            )
        }
    }
}
