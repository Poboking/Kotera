package com.eratw.port.core.entity

import com.eratw.port.modapi.EntityId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * M4-5 确定性派生（era-arch-v61u.md §7 / §16，K5「子嗣 / ENTITY_RAND 遗传派生」的地基）。
 *
 * ## 为什么不钉字面 golden
 *
 * 派生值是**每次现算、不落盘**的：改混合函数不会让任何已存存档变形，只会让全体派生值一致地换一批。
 * 于是字面 golden 的净效果 = 把「改进混合」变成一次改测试的仪式，而真正的风险（跨调用不稳定、
 * 按位置而非按身份取键）property 测试已经钉死。**这里因此只留两类判据：性质 + 一个外部真源。**
 *
 * ## 外部真源
 *
 * [DeterministicDeriver.fnv1a64] 对拍 FNV-1a 64 的**公布测试向量**（空串 / "a" / "foobar"）。
 * 这条独立于本实现，是唯一能证明「字符串吸收没写错」而不是「和实现一起错」的判据，
 * 所以该函数被放开成 internal 专供此用。
 */
class DeterministicDeriverTest {

    private fun id(pkg: Int, generation: Int, slot: Int): EntityId =
        EntityId(EntityIdCodec.encode(pkg, generation, slot))

    // ------------------------------------------------------------------
    // 独立判据：字符串吸收对拍公布向量
    // ------------------------------------------------------------------

    @Test
    fun fnv1a64MatchesPublishedTestVectors() {
        fun expected(hex: String): Long = hex.toULong(16).toLong()
        assertEquals(expected("cbf29ce484222325"), DeterministicDeriver.fnv1a64(""), "FNV-1a 64 offset basis")
        assertEquals(expected("af63dc4c8601ec8c"), DeterministicDeriver.fnv1a64("a"))
        assertEquals(expected("85944171f73967e8"), DeterministicDeriver.fnv1a64("foobar"))
    }

    /**
     * 钉「显式 UTF-8、不是默认字符集、不是 UTF-16 码元」。
     * 期望值在测试里用**另一份循环**算，并显式 `Charsets.UTF_8`：生产侧哪天漂到默认字符集
     * （Windows CI 上是 Cp1252 ⇒ 「后宫」2 字符变 2 字节），这条立刻红，而公布向量那三条仍绿。
     */
    @Test
    fun multibytePurposeIsHashedOverUtf8BytesNotOverCodeUnits() {
        fun referenceFnv(text: String): Long {
            var hash = "cbf29ce484222325".toULong(16).toLong()
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toInt() and 0xFF).toLong()
                hash *= "100000001b3".toULong(16).toLong()
            }
            return hash
        }
        for (sample in listOf("后宫", "后宫居住标记", "🐉", "a", "家庭\tnull")) {
            assertEquals(referenceFnv(sample), DeterministicDeriver.fnv1a64(sample), "样本「$sample」")
        }
    }

    // ------------------------------------------------------------------
    // 判据 1：稳定（跨加载逐位复现的性质基础）
    // ------------------------------------------------------------------

    @Test
    fun sameArgumentsAlwaysYieldTheSameValue() {
        val entity = id(pkg = 7, generation = 3, slot = 42)
        val first = DeterministicDeriver.derive(entity, "family.hair", ordinal = 5L, salt = 11L)
        repeat(1_000) {
            assertEquals(first, DeterministicDeriver.derive(entity, "family.hair", ordinal = 5L, salt = 11L))
        }
    }

    @Test
    fun derivedValueDependsOnNothingButItsFourArguments() {
        val entity = id(1, 0, 1)
        val baseline = DeterministicDeriver.derive(entity, "p", 0L, 0L)
        // 调用顺序、次数、谁先取都不许挪动结果（有状态随机流就是在这里翻车的）。
        DeterministicDeriver.derive(id(2, 0, 2), "other", 9L, 9L)
        repeat(50) { DeterministicDeriver.derive(entity, "p", ordinal = it.toLong()) }
        assertEquals(baseline, DeterministicDeriver.derive(entity, "p", 0L, 0L))
    }

    @Test
    fun partialSequenceIsStableWithoutTakingEarlierOrdinals() {
        val entity = id(3, 1, 77)
        val thirdAlone = DeterministicDeriver.derive(entity, "family.talent", ordinal = 2L)
        val fullSequence = (0L..2L).map { DeterministicDeriver.derive(entity, "family.talent", ordinal = it) }
        assertEquals(thirdAlone, fullSequence.last(), "取第 3 个不得依赖先取过第 1、2 个")
    }

    // ------------------------------------------------------------------
    // 判据 2：按身份不按位置（本单真正的 bug 面）
    // ------------------------------------------------------------------

    /**
     * 核心反向证明：若混合只用 `handleSlot`（低位），代次不同的同一槽位会拿到同一个值 ⇒ 串号。
     * 这里钉的是**全位域参与混合**，所以仅差 generation 的两个身份必须给出不同值。
     */
    @Test
    fun slotReuseWithDifferentGenerationNeverReusesTheOldValue() {
        val slot = 500
        val original = id(pkg = 9, generation = 0, slot = slot)
        val reused = id(pkg = 9, generation = 1, slot = slot)
        assertEquals(original.raw and 0xFFFFFFFFL, reused.raw and 0xFFFFFFFFL, "夹具自检：slot 必须相同")
        assertNotEquals(
            DeterministicDeriver.derive(original, "family.face"),
            DeterministicDeriver.derive(reused, "family.face"),
            "同槽不同代是「上一个孩子的脸发给下一个孩子」的形状，必须不同值",
        )
    }

    @Test
    fun differentPackagesSameSlotAndGenerationAreDifferent() {
        assertNotEquals(
            DeterministicDeriver.derive(id(pkg = 2, generation = 0, slot = 5), "k"),
            DeterministicDeriver.derive(id(pkg = 3, generation = 0, slot = 5), "k"),
        )
    }

    @Test
    fun distinctEntitiesDoNotCollideOverOneHundredThousandDraws() {
        val seen = HashSet<Long>(131_072)
        for (slot in 0 until 100_000) {
            assertTrueDistinct(seen, DeterministicDeriver.derive(id(pkg = 1, generation = 0, slot = slot), "x"))
        }
        // 2^-64 空间里 10 万个样本的期望碰撞数约 2.7e-10，红即意味着混合塌了。
        assertEquals(100_000, seen.size)
    }

    private fun assertTrueDistinct(seen: MutableSet<Long>, value: Long) {
        kotlin.test.assertTrue(seen.add(value), "派生值碰撞：$value")
    }

    // ------------------------------------------------------------------
    // 判据 3：三个键各自独立生效
    // ------------------------------------------------------------------

    @Test
    fun purposeSaltAndOrdinalEachMoveTheValueIndependently() {
        val entity = id(4, 2, 9)
        val base = DeterministicDeriver.derive(entity, "family.hair", 0L, 0L)
        assertNotEquals(base, DeterministicDeriver.derive(entity, "family.eyes", 0L, 0L), "用途必须分开")
        assertNotEquals(base, DeterministicDeriver.derive(entity, "family.hair", 1L, 0L), "序号必须分开")
        assertNotEquals(base, DeterministicDeriver.derive(entity, "family.hair", 0L, 1L), "盐必须分开")
    }

    @Test
    fun purposeLabelsAreNotConfusedByCaseOrSeparators() {
        val entity = id(4, 0, 1)
        val keys = listOf("a.b", "A.B", "a.B", "ab", "a_b", "a..b")
        val values = keys.map { DeterministicDeriver.derive(entity, it) }
        assertEquals(keys.size, values.distinct().size, "相近标签撞值 = 两个业务特征共用一个随机数")
    }

    @Test
    fun valuesLookBalancedAcrossSignAndBits() {
        var positives = 0
        var negatives = 0
        var setBits = 0L
        val sample = 20_000
        for (i in 0 until sample) {
            val v = DeterministicDeriver.derive(id(pkg = 1 + (i % 200), generation = i % 7, slot = i), "probe")
            if (v >= 0L) positives++ else negatives++
            setBits += v.countOneBits()
        }
        // 只判「没塌到一边」，不判严格分布：派生特征不是密码学原语，也不宣称是。
        kotlin.test.assertTrue(positives in sample / 3..sample * 2 / 3, "符号失衡：+$positives / -$negatives")
        val density = setBits.toDouble() / (sample * 64)
        kotlin.test.assertTrue(density in 0.40..0.60, "位密度偏离 0.5 过远：$density")
    }

    // ------------------------------------------------------------------
    // 判据 4：入口 fail-closed
    // ------------------------------------------------------------------

    @Test
    fun nullEntityAndEmptyPurposeAndNegativeOrdinalAreRejected() {
        assertFailsWith<IllegalArgumentException> { DeterministicDeriver.derive(EntityId.NULL, "p") }
        assertFailsWith<IllegalArgumentException> {
            DeterministicDeriver.derive(id(1, 0, 1), "")
        }
        assertFailsWith<IllegalArgumentException> {
            DeterministicDeriver.derive(id(1, 0, 1), "p", ordinal = -1L)
        }
    }
}
