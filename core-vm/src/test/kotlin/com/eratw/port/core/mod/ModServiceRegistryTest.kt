package com.eratw.port.core.mod

import com.eratw.port.modapi.ModServiceRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 服务注册表实现闸（RT2 P1 跨 mod Kotlin 服务面）：发布 / 查找 / 多包冲突 / 撤销四条契约边界，
 * 以及未装配时的 fail-closed 形状。
 */
class ModServiceRegistryTest {

    /** 作者面被测接口：消费方只认这个类型，实现类不可见。 */
    private interface Greeter {
        fun greet(name: String): String
    }

    private class GreeterImpl(private val prefix: String) : Greeter {
        override fun greet(name: String): String = "$prefix$name"
    }

    @Test
    fun `发布后按接口类型查到同一实例且 owners 与 publishedTypes 可观测`() {
        val registry = ModServiceRegistryImpl()
        val impl = GreeterImpl("hello ")
        registry.publish(Greeter::class.java, impl, "modA")

        assertSame(impl, registry.find(Greeter::class.java))
        assertEquals(listOf("modA"), registry.owners(Greeter::class.java))
        assertEquals(setOf(Greeter::class.java.name), registry.publishedTypes())
    }

    @Test
    fun `无人发布时 find 返回 null 而不是抛错`() {
        val registry = ModServiceRegistryImpl()
        assertNull(registry.find(Greeter::class.java))
        assertTrue(registry.owners(Greeter::class.java).isEmpty())
        assertTrue(registry.publishedTypes().isEmpty())
    }

    @Test
    fun `同包重复发布同类型是替换不是叠加`() {
        val registry = ModServiceRegistryImpl()
        val first = GreeterImpl("first ")
        val second = GreeterImpl("second ")
        registry.publish(Greeter::class.java, first, "modA")
        registry.publish(Greeter::class.java, second, "modA")

        // 仍然是单发布方：不因幂等重注册退化成「多包冲突」。
        assertSame(second, registry.find(Greeter::class.java))
        assertEquals(listOf("modA"), registry.owners(Greeter::class.java))
    }

    @Test
    fun `跨包发布同类型后 find 抛错并点名双方（不静默取先注册的）`() {
        val registry = ModServiceRegistryImpl()
        registry.publish(Greeter::class.java, GreeterImpl("a "), "modA")
        registry.publish(Greeter::class.java, GreeterImpl("b "), "modB")

        assertEquals(listOf("modA", "modB"), registry.owners(Greeter::class.java))
        val failure = assertThrows<IllegalStateException> { registry.find(Greeter::class.java) }
        assertTrue(failure.message.orEmpty().contains("modA"))
        assertTrue(failure.message.orEmpty().contains("modB"))
    }

    @Test
    fun `revokePackage 只摘该包且清空类型键且幂等`() {
        val registry = ModServiceRegistryImpl()
        registry.publish(Greeter::class.java, GreeterImpl("a "), "modA")
        registry.publish(CharSequence::class.java, "other", "modB")

        registry.revokePackage("modA")
        assertNull(registry.find(Greeter::class.java))
        assertEquals(emptyList(), registry.owners(Greeter::class.java))
        // 空类型键已清掉：publishedTypes 不留空壳，他包发布面不受影响。
        assertEquals(setOf(CharSequence::class.java.name), registry.publishedTypes())
        assertEquals("other", registry.find(CharSequence::class.java))

        // 幂等：重复撤销同一包是 no-op，不会误伤他包。
        registry.revokePackage("modA")
        assertEquals("other", registry.find(CharSequence::class.java))

        registry.revokePackage("modB")
        assertTrue(registry.publishedTypes().isEmpty())
    }

    @Test
    fun `owners 按发布序返回且重复发布不改变顺序`() {
        val registry = ModServiceRegistryImpl()
        registry.publish(Greeter::class.java, GreeterImpl("a "), "modA")
        registry.publish(Greeter::class.java, GreeterImpl("b "), "modB")
        registry.publish(Greeter::class.java, GreeterImpl("c "), "modC")
        assertEquals(listOf("modA", "modB", "modC"), registry.owners(Greeter::class.java))

        // 幂等重注册是就地替换：owners 顺序不变，不会把 modA 挪到队尾。
        registry.publish(Greeter::class.java, GreeterImpl("a2 "), "modA")
        assertEquals(listOf("modA", "modB", "modC"), registry.owners(Greeter::class.java))
    }

    @Test
    fun `作者面适配器只许用自己的 packageId 发布（冒名被拒）`() {
        val registry = ModServiceRegistryImpl()
        val adapter: ModServiceRegistry = PackageServiceRegistry(registry, "modA")
        val impl = GreeterImpl("own ")
        adapter.publish(Greeter::class.java, impl, "modA")
        assertSame(impl, registry.find(Greeter::class.java))
        assertEquals(listOf("modA"), registry.owners(Greeter::class.java))

        val failure = assertThrows<IllegalArgumentException> {
            adapter.publish(Greeter::class.java, GreeterImpl("spoof "), "modB")
        }
        assertTrue(failure.message.orEmpty().contains("modA"))
        assertTrue(failure.message.orEmpty().contains("modB"))
    }

    @Test
    fun `未装配的 fail-closed 桩四个入口一律抛错并点名 packageId`() {
        val failClosed: ModServiceRegistry = FailClosedServices("modX")
        val operations: List<() -> Any?> = listOf(
            { failClosed.find(Greeter::class.java) },
            { failClosed.publish(Greeter::class.java, GreeterImpl("x "), "modX") },
            { failClosed.owners(Greeter::class.java) },
            { failClosed.publishedTypes() },
        )
        for (operation in operations) {
            val failure = assertThrows<UnsupportedOperationException> { operation() }
            assertTrue(
                failure.message.orEmpty().contains("modX"),
                "fail-closed 文案必须点名 packageId：${failure.message}",
            )
        }
    }
}
