package com.eratw.port.core.mod

import com.eratw.port.core.rand.Sfmt19937
import com.eratw.port.core.vars.RandProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// detekt MagicNumber 防线：非平凡字面量一律落具名常量（ignoreConstantDeclaration 豁免）。
private const val SEED_DIST = 20261119L
private const val WEIGHT_HEAVY = 3
private const val DIST_ROUNDS = 10_000
private const val DIST_FIRST_MIN = 0.23
private const val DIST_FIRST_MAX = 0.27
private const val DIST_SECOND_MIN = 0.73
private const val DIST_SECOND_MAX = 0.77
private const val SEED_CYCLE = 42L
private const val CYCLE_POOL_SIZE = 10
private const val CYCLE_ROUNDS = 3
private const val SEED_RESET = 7L
private const val SEED_UNKNOWN = 11L
private const val SEED_WEIGHT = 13L
private const val SEED_DUP = 17L
private const val SEED_PLACEHOLDER = 19L
private const val SEED_CROSS = 23L
private const val CROSS_PICKS = 3
private const val SEED_DETERMINISM = 20260226L
private const val SEED_DETERMINISM_OTHER = 20260227L
private const val DET_POOL_SIZE = 8
private const val DET_WEIGHT_STEP = 3
private const val DET_PICKS = 40
private const val CURSOR_DRAW_FIRST_SEGMENT = 0L
private const val CURSOR_DRAW_MID_SEGMENT = 2L
private const val CURSOR_DRAW_LAST_SEGMENT = 4L
private const val CURSOR_DRAW_AFTER_REFILL = 9L
private const val BOUNDS_DRAW_OVERFLOW = 10L
private const val BOUNDS_DRAW_NEGATIVE = -1L
private const val CURSOR_W_A = 2
private const val CURSOR_W_B = 3
private const val CURSOR_W_C = 5
private const val BOUNDS_W_A = 4
private const val BOUNDS_W_B = 6

/**
 * 逐点脚本随机缝：按预设顺序吐 draw，用尽即抛——游标/越界用例不静默续数、不循环。
 */
private class ScriptedRand(vararg draws: Long) : RandProvider {
    private val queue = ArrayDeque(draws.toList())

    override fun next(bound: Long): Long =
        queue.removeFirstOrNull() ?: error("脚本随机缝已用尽（bound=$bound）：用例供给不足")
}

/**
 * 词库插排原语定向钉（ModDX-4 Lane M3-1，契约判据 1–6）。
 *
 * 判据对号：
 * - 判据 1（槽→加权池；weight≤0 / 重复 text / 空白 text、slot 拒）：`non positive weight...`、
 *   `duplicate text...`
 * - 判据 2（加权抽取 + 注入式随机源）：`weighted distribution...`；同 seed 确定性与随机缝
 *   细账在 [LexiconRegistryRandomSeamTest]
 * - 判据 3（防重复抽取 + 抽尽重置）：`anti repeat deck...`、`exhausted deck resets...`
 * - 判据 4（未知 slot 空集不抛）：`unknown slot returns empty...`
 * - 判据 5（纯 JVM）：依赖面只有 core-vm 的 [Sfmt19937] / [RandProvider]，无 Android API、
 *   无 kotlin.random
 *
 * 大样本分布口径裁定：防重复抽取（判据 3）下每轮每条恰抽一次，「每次抽取的边缘分布」恒等
 * 比例、不随权重；权重只作用于**满牌堆轮首抽**的选条概率。故分布比例钉在轮首抽
 * （P(A) = 1/(1+3) = 0.25）与轮次抽（不放回的补位镜像 0.75），并顺带钉「每轮 A 恰一次」。
 *
 * 随机缝同源（判据 2）：确定性用例直接注入 [Sfmt19937]（生产缝 ModRandomApi.kt:3/:29 同类）；
 * 逐点控制 draw 的用例用 [ScriptedRand]。反向证明（契约判据 6：防重复摘除 → 重复抽取变红，
 * 恢复 sha 一致复绿）按契约在源码临时变异上执行（摘 [LexiconRegistry.drawWeighted] 的摘牌行），
 * 证据入交付消息，不落本文件。
 */
class LexiconRegistryTest {

    @Test
    fun `weighted distribution follows weights on full deck draws`() {
        val registry = LexiconRegistry(Sfmt19937(SEED_DIST))
        registry.define(
            "greet",
            listOf(
                LexiconEntry(text = "A", weight = 1),
                LexiconEntry(text = "B", weight = WEIGHT_HEAVY),
            ),
        )
        var firstA = 0
        var secondA = 0
        repeat(DIST_ROUNDS) {
            if (requireNotNull(registry.pick("greet")) == "A") firstA++
            if (requireNotNull(registry.pick("greet")) == "A") secondA++
        }
        val firstFraction = firstA.toDouble() / DIST_ROUNDS
        val secondFraction = secondA.toDouble() / DIST_ROUNDS
        assertTrue(
            firstFraction in DIST_FIRST_MIN..DIST_FIRST_MAX,
            "轮首抽 A 占比应≈0.25（权重 1:3），实际 $firstFraction",
        )
        assertTrue(
            secondFraction in DIST_SECOND_MIN..DIST_SECOND_MAX,
            "轮次抽 A 占比应≈0.75（轮首未抽走 A 则必落次抽），实际 $secondFraction",
        )
        assertEquals(DIST_ROUNDS, firstA + secondA, "每轮 A 恰被抽一次（防重复的完备性）")
    }

    @Test
    fun `anti repeat deck draws all distinct within each round`() {
        val registry = LexiconRegistry(Sfmt19937(SEED_CYCLE))
        val texts = (1..CYCLE_POOL_SIZE).map { "t$it" }
        registry.define(
            "deck",
            texts.mapIndexed { index, text -> LexiconEntry(text = text, weight = index + 1) },
        )
        val expected = texts.toSet()
        repeat(CYCLE_ROUNDS) { round ->
            val drawn = List(CYCLE_POOL_SIZE) { requireNotNull(registry.pick("deck")) }
            assertEquals(expected, drawn.toSet(), "round $round 轮内出现重复抽取或缺条")
        }
    }

    @Test
    fun `exhausted deck resets to full stack`() {
        // 单条池：每抽必耗尽 → 抽尽瞬间整栈恢复 → 连抽同文（不存在「空牌堆」中间态）
        val solo = LexiconRegistry(Sfmt19937(SEED_RESET))
        solo.define("solo", listOf(LexiconEntry(text = "only", weight = 2)))
        assertEquals("only", solo.pick("solo"))
        assertEquals("only", solo.pick("solo"))
        assertEquals("only", solo.pick("solo"))
        // 两条池：第 3、4 抽构成新的一轮（抽尽后下一抽即新轮首抽）
        val pair = LexiconRegistry(Sfmt19937(SEED_RESET))
        pair.define(
            "pair",
            listOf(LexiconEntry(text = "x", weight = 1), LexiconEntry(text = "y", weight = 1)),
        )
        val firstRound = listOfNotNull(pair.pick("pair"), pair.pick("pair"))
        val secondRound = listOfNotNull(pair.pick("pair"), pair.pick("pair"))
        assertEquals(setOf("x", "y"), firstRound.toSet())
        assertEquals(setOf("x", "y"), secondRound.toSet())
    }

    @Test
    fun `unknown slot returns empty set without throwing`() {
        val registry = LexiconRegistry(Sfmt19937(SEED_UNKNOWN))
        assertNull(registry.pick("never-defined"))
        assertTrue(registry.entries("never-defined").isEmpty())
    }

    @Test
    fun `non positive weight entry rejected fail closed`() {
        // weight 闸挂条目构造单点：define 无从收到坏权重条目（判据 1 fail-closed）
        assertThrows<IllegalArgumentException> { LexiconEntry(text = "w", weight = 0) }
        assertThrows<IllegalArgumentException> { LexiconEntry(text = "w", weight = -1) }
        // 拒在构造期，不牵连既有池：合法池照常抽取
        val registry = LexiconRegistry(Sfmt19937(SEED_WEIGHT))
        registry.define("ok", listOf(LexiconEntry(text = "good", weight = 2)))
        assertEquals("good", requireNotNull(registry.pick("ok")))
    }

    @Test
    fun `duplicate text rejected batch and cross define`() {
        val registry = LexiconRegistry(Sfmt19937(SEED_DUP))
        // 批内重复：整批不落、新槽不注册（define 原子性）
        assertThrows<IllegalArgumentException> {
            registry.define(
                "dup",
                listOf(LexiconEntry(text = "dup", weight = 1), LexiconEntry(text = "dup", weight = 2)),
            )
        }
        assertTrue(registry.entries("dup").isEmpty())
        registry.define("dup", listOf(LexiconEntry(text = "dup", weight = 1)))
        // 跨 define 同槽重复：不覆盖不跳过，原池原样
        assertThrows<IllegalArgumentException> {
            registry.define("dup", listOf(LexiconEntry(text = "dup", weight = 2)))
        }
        assertEquals(listOf("dup"), registry.entries("dup"))
        // 空白 text / 空白 slot 拒（fail-closed）
        assertThrows<IllegalArgumentException> { LexiconEntry(text = "   ", weight = 1) }
        assertThrows<IllegalArgumentException> {
            registry.define("   ", listOf(LexiconEntry(text = "x", weight = 1)))
        }
    }

    @Test
    fun `empty pool placeholder behaves as empty set then fills`() {
        val registry = LexiconRegistry(Sfmt19937(SEED_PLACEHOLDER))
        registry.define("later", emptyList()) // 空批 = 空池占位
        assertNull(registry.pick("later"))
        assertTrue(registry.entries("later").isEmpty())
        registry.define("later", listOf(LexiconEntry(text = "filled", weight = 1)))
        assertEquals("filled", requireNotNull(registry.pick("later")))
        // 已有槽 + 空批 = no-op：词条不动、不抛
        registry.define("later", emptyList())
        assertEquals(listOf("filled"), registry.entries("later"))
    }

    @Test
    fun `cross slot same text allowed and define rebuilds deck`() {
        val registry = LexiconRegistry(Sfmt19937(SEED_CROSS))
        registry.define("s1", listOf(LexiconEntry(text = "same", weight = 1)))
        registry.define("s2", listOf(LexiconEntry(text = "same", weight = 1))) // 跨槽同名允许
        assertEquals("same", requireNotNull(registry.pick("s1")))
        assertEquals("same", requireNotNull(registry.pick("s1"))) // 单条池抽尽重置后再抽
        assertEquals("same", requireNotNull(registry.pick("s2")))
        // define 追加即重置该槽牌堆：旧轮作废、重开新轮（s1 池 {same} → {same, extra}）
        registry.define("s1", listOf(LexiconEntry(text = "extra", weight = 1)))
        val drawn = List(CROSS_PICKS) { requireNotNull(registry.pick("s1")) }
        assertEquals(setOf("same", "extra"), drawn.toSet(), "追加后旧轮作废，新轮覆盖全池")
    }
}

/**
 * 词库随机缝细账（契约判据 2、6）：逐点控制 draw 的脚本缝用例 + 同 seed 确定性。
 *
 * 生产缝 = Sfmt19937（同源裁定见 [LexiconRegistry] 类 KDoc 与 ModRandomApi.kt:3/:29）。
 */
class LexiconRegistryRandomSeamTest {

    @Test
    fun `cumulative cursor matches decision pipeline arbitration`() {
        // 池 A(w2) B(w3) C(w5)，牌堆序 = 落表序；首个 draw < cumulative 的条目胜出
        //（DecisionPipeline.kt:557-579 同款口径）
        val registry = LexiconRegistry(
            ScriptedRand(
                CURSOR_DRAW_FIRST_SEGMENT,
                CURSOR_DRAW_MID_SEGMENT,
                CURSOR_DRAW_LAST_SEGMENT,
                CURSOR_DRAW_AFTER_REFILL,
            ),
        )
        registry.define(
            "cur",
            listOf(
                LexiconEntry(text = "A", weight = CURSOR_W_A),
                LexiconEntry(text = "B", weight = CURSOR_W_B),
                LexiconEntry(text = "C", weight = CURSOR_W_C),
            ),
        )
        assertEquals("A", registry.pick("cur")) // total=10，draw=0 落 A 段 [0,2)
        assertEquals("B", registry.pick("cur")) // total=8，draw=2 落 B 段 [2,5)
        assertEquals("C", registry.pick("cur")) // total=5，draw=4 落 C 段 [4,5)，抽尽
        assertEquals("C", registry.pick("cur")) // 整栈重置 total=10，draw=9 落 C 段 [5,10)
    }

    @Test
    fun `out of range draw fails closed`() {
        val overflow = LexiconRegistry(ScriptedRand(BOUNDS_DRAW_OVERFLOW))
        overflow.define(
            "oor",
            listOf(LexiconEntry(text = "A", weight = BOUNDS_W_A), LexiconEntry(text = "B", weight = BOUNDS_W_B)),
        )
        assertThrows<IllegalStateException> { overflow.pick("oor") }
        val negative = LexiconRegistry(ScriptedRand(BOUNDS_DRAW_NEGATIVE))
        negative.define("oor2", listOf(LexiconEntry(text = "A", weight = BOUNDS_W_A)))
        assertThrows<IllegalStateException> { negative.pick("oor2") }
    }

    @Test
    fun `same seed reproduces identical pick sequence`() {
        val a = seededRegistry(SEED_DETERMINISM)
        val b = seededRegistry(SEED_DETERMINISM)
        val seqA = List(DET_PICKS) { requireNotNull(a.pick("seq")) }
        val seqB = List(DET_PICKS) { requireNotNull(b.pick("seq")) }
        assertEquals(seqA, seqB)
        val other = seededRegistry(SEED_DETERMINISM_OTHER)
        val seqOther = List(DET_PICKS) { requireNotNull(other.pick("seq")) }
        assertTrue(seqA != seqOther, "不同 seed 不应产出完全相同序列（防 seed 无效退化）")
    }

    private fun seededRegistry(seed: Long): LexiconRegistry {
        val registry = LexiconRegistry(Sfmt19937(seed))
        registry.define(
            "seq",
            (1..DET_POOL_SIZE).map { index -> LexiconEntry(text = "e$index", weight = index * DET_WEIGHT_STEP) },
        )
        return registry
    }
}
