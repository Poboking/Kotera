package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RuntimeAux.clear] 的**资源收口**判据（SQLite 迁移 L1，`CONTRACT-SQLITE-MIGRATION` §2）：
 *
 * - `"sql"` 槽里的真实 `SqlSession` 持有 SQLite 连接，`clear()` 必须逐个 close
 *   （原版 `GlobalStatic.Reset()` 里的 `SqlManager.CloseAll()`，自改说明 :258-259）；
 * - 关闭**全部尝试**，普通失败留首个、其余仍要关；`OutOfMemoryError` 上抛；
 * - 先摘表再关闭 ⇒ 幂等，重入 close 不会二次关闭。
 */
class RuntimeAuxCloseTest {

    /** 可观测的关闭桩：记录 close 次数（不涉及任何业务语义）。 */
    private class Spy(private val onClose: () -> Unit = {}) : AutoCloseable {
        var closes: Int = 0
            private set

        override fun close() {
            closes += 1
            onClose()
        }
    }

    @Test
    fun `clear 关闭 AutoCloseable 槽并清空全部槽`() {
        val aux = RuntimeAux()
        val closeable = Spy()
        val plain = Any()
        aux.slot("xml") { closeable }
        aux.slot("map") { plain }

        aux.clear()

        assertEquals(1, closeable.closes, "AutoCloseable 槽必须被 close 一次")
        assertTrue(aux.slotNames().isEmpty(), "槽表必须清空: ${aux.slotNames()}")
        assertNull(aux.peek<Any>("xml"), "清空后 peek 不得再看到旧槽")
        assertNull(aux.peek<Any>("map"), "非 AutoCloseable 槽同样只丢弃")
    }

    @Test
    fun `clear 遇到失败仍尝试其余槽 并抛首个失败`() {
        val aux = RuntimeAux()
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        val badA = Spy { throw first }
        val badB = Spy { throw second }
        val good = Spy()
        aux.slot("badA") { badA }
        aux.slot("badB") { badB }
        aux.slot("good") { good }

        // 主异常是谁由 HashMap 迭代序决定（契约只钉「首个失败」不钉槽序），故按 Throwable 接。
        val thrown = assertFailsWith<Throwable> { aux.clear() }

        // 两个普通失败都必须报出：一个作主异常、另一个作 suppressed（HashMap 迭代序不作判据）。
        val reported = listOfNotNull(thrown.message) + thrown.suppressed.mapNotNull { it.message }
        assertEquals(setOf("first", "second"), reported.toSet(), "两个失败都必须被报告: $reported")
        assertEquals(1, badA.closes, "抛错的槽也只 close 一次")
        assertEquals(1, badB.closes, "第二个抛错的槽同样被尝试 close")
        assertEquals(1, good.closes, "失败不得阻止其余槽关闭")
        assertTrue(aux.slotNames().isEmpty(), "失败路径也必须先清空槽表")
    }

    @Test
    fun `clear 上抛 OutOfMemoryError 且仍尝试其余槽`() {
        val aux = RuntimeAux()
        val oom = OutOfMemoryError("boom")
        val fatal = Spy { throw oom }
        val good = Spy()
        aux.slot("fatal") { fatal }
        aux.slot("good") { good }

        val thrown = assertFailsWith<OutOfMemoryError> { aux.clear() }

        assertEquals("boom", thrown.message, "OOM 原样上抛，不折成普通 close 失败")
        assertEquals(1, good.closes, "OOM 之后其余槽仍要尝试关闭")
        assertTrue(aux.slotNames().isEmpty())
    }

    @Test
    fun `clear 幂等 无法二次关闭已清空的槽`() {
        val aux = RuntimeAux()
        val spy = Spy()
        aux.slot("one") { spy }

        aux.clear()
        aux.clear()

        assertEquals(1, spy.closes, "第二次 clear 无槽可关，不得二次 close")
        assertTrue(aux.slotNames().isEmpty())
    }
}
