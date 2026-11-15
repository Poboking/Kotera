package com.eratw.port.modapi

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * :mod-api 侧 ABI-12 契约形状闸（era-arch-v61u.md §21.3 / §21.6）。
 *
 * 本模块零 core 依赖，故本类只钉 facade 侧**有逻辑**的面：值类型边界（本契约裁定 A/B）、
 * 决策原因码词表、[CancellationToken] 语义、[ActionApi] 的可实现性（测试内 fake
 * implements）、上下文形状。
 *
 * 引擎侧权威实现 `com.eratw.port.core.mod.DecisionPipeline` 的语义由 :core-vm 的
 * DecisionPipelineTest 承担；两处的 reason code 取值必须逐字一致，本类钉 mod 作者看到的
 * 词表形态（纯 ASCII 大写蛇形、无本地化、无前缀）。
 */
class ActionApiContractTest {

    private fun candidate(
        providerPackage: String = "pkg.a",
        candidateKey: String = "k",
        priority: Int = 0,
        weight: Int = 1,
        cooldownKey: String? = null,
        cooldownDays: Int? = null,
        perDay: Int? = null,
        perActorPerDay: Int? = null,
        handlerKey: String = candidateKey,
    ): ActionCandidate = ActionCandidate(
        providerPackage = providerPackage,
        candidateKey = candidateKey,
        actor = EntityId(1L),
        priority = priority,
        weight = weight,
        cooldownKey = cooldownKey,
        cooldownDays = cooldownDays,
        perDay = perDay,
        perActorPerDay = perActorPerDay,
        handlerKey = handlerKey,
    )

    @Test
    fun `ReservationKey 非空校验与值等价`() {
        assertThrows<IllegalArgumentException> { ReservationKey("", "k") }
        assertThrows<IllegalArgumentException> { ReservationKey("kind", "") }
        assertThrows<IllegalArgumentException> { ReservationKey(" ", "k") }
        assertThrows<IllegalArgumentException> { ReservationKey("kind", "  ") }
        val claim = ReservationKey("chara", "42")
        assertEquals(ReservationKey("chara", "42"), claim)
        assertEquals(ReservationKey("chara", "42").hashCode(), claim.hashCode())
        assertEquals("chara", claim.kind)
        assertEquals("42", claim.key)
    }

    @Test
    fun `ActionCandidate 边界校验`() {
        assertThrows<IllegalArgumentException> { candidate(providerPackage = "") }
        assertThrows<IllegalArgumentException> { candidate(candidateKey = " ") }
        assertThrows<IllegalArgumentException> { candidate(handlerKey = "") }
        assertThrows<IllegalArgumentException> { candidate(priority = -1) }
        assertThrows<IllegalArgumentException> { candidate(weight = 0) }
        assertThrows<IllegalArgumentException> { candidate(weight = -3) }
        // 裁定 A：冷却键与 TTL 必须同生共死（半套声明即拒绝），TTL 必须为正。
        assertThrows<IllegalArgumentException> { candidate(cooldownKey = "c", cooldownDays = null) }
        assertThrows<IllegalArgumentException> { candidate(cooldownKey = null, cooldownDays = 2) }
        assertThrows<IllegalArgumentException> { candidate(cooldownKey = " ", cooldownDays = 2) }
        assertThrows<IllegalArgumentException> { candidate(cooldownKey = "c", cooldownDays = 0) }
        // 裁定 B：限额非空时必须为正。
        assertThrows<IllegalArgumentException> { candidate(perDay = 0) }
        assertThrows<IllegalArgumentException> { candidate(perActorPerDay = -1) }
        val ok = candidate(cooldownKey = "c", cooldownDays = 3, perDay = 1, perActorPerDay = 2)
        assertEquals("c", ok.cooldownKey)
        assertEquals(3, ok.cooldownDays)
        assertEquals(1, ok.perDay)
        assertEquals(2, ok.perActorPerDay)
    }

    @Test
    fun `ActionCandidate 缺省值`() {
        val value = candidate()
        assertEquals(0, value.priority)
        assertEquals(1, value.weight)
        assertTrue(value.constraints.isEmpty())
        assertNull(value.cooldownKey)
        assertNull(value.cooldownDays)
        assertNull(value.perDay)
        assertNull(value.perActorPerDay)
        assertTrue(value.reservationClaims.isEmpty())
        assertNull(value.payloadRef)
        assertEquals(EntityId(1L), value.actor)
    }

    @Test
    fun `决策原因码词表取值`() {
        // §21.6 决策面必须给出的淘汰/中断原因码，顺序取架构原文列举序。
        val vocabulary = listOf(
            "CONSTRAINT_FALSE",
            "COOLDOWN",
            "LIMIT_DAY",
            "LIMIT_ACTOR",
            "RESERVATION_CONFLICT",
            "PRIORITY_LOST",
            "HANDLER_FAILED",
            "CANCELED",
        )
        assertEquals(vocabulary.size, vocabulary.distinct().size)
        for (code in vocabulary) {
            // mod 作者按字符串 switch：必须纯 ASCII 大写蛇形，无点号/空格/本地化/前缀。
            assertTrue(
                Regex("[A-Z][A-Z_]*").matches(code),
                "原因码必须是大写蛇形标识：$code",
            )
            assertEquals(code.uppercase(), code)
        }
        assertEquals("CANCELED", vocabulary.last())
    }

    @Test
    fun `CancellationToken 语义`() {
        val token = CancellationToken()
        assertFalse(token.isCancelled)
        token.throwIfCancelled() // 未取消：什么都不做
        token.requestCancel()
        assertTrue(token.isCancelled)
        token.requestCancel() // 幂等
        assertTrue(token.isCancelled)
        assertThrows<CancellationException> { token.throwIfCancelled() }
    }

    @Test
    fun `ActionApi 可实现性与注册撤销`() {
        val log = mutableListOf<String>()
        val fake = object : ActionApi {
            override fun provide(domain: String, provider: ActionProvider): Registration {
                log += "provide:$domain"
                return object : Registration {
                    override fun unregister() {
                        log += "unprovide:$domain"
                    }
                }
            }

            override fun on(handlerKey: String, handler: ActionHandler): Registration {
                log += "on:$handlerKey"
                return object : Registration {
                    override fun unregister() {
                        log += "off:$handlerKey"
                    }
                }
            }

            override fun constraint(key: String, predicate: ActionConstraint): Registration {
                log += "constraint:$key"
                return object : Registration {
                    override fun unregister() {
                        log += "unconstraint:$key"
                    }
                }
            }
        }
        val provider = fake.provide("ACTION") { emptyList() }
        val handler = fake.on("h") { _ -> }
        val constraint = fake.constraint("c") { true }
        assertEquals(listOf("provide:ACTION", "on:h", "constraint:c"), log)
        provider.unregister()
        handler.unregister()
        constraint.unregister()
        assertEquals(
            listOf(
                "provide:ACTION",
                "on:h",
                "constraint:c",
                "unprovide:ACTION",
                "off:h",
                "unconstraint:c",
            ),
            log,
        )
    }

    @Test
    fun `选择上下文与执行上下文形状`() {
        assertThrows<IllegalArgumentException> { ActionSelectionContext(domain = "  ", time = GameTime(1L)) }
        val selected = ActionSelectionContext(
            domain = "ACTION",
            time = GameTime(3L, "DAY_START"),
            actor = EntityId(7L),
            metadata = mapOf("k" to "v"),
        )
        assertEquals("ACTION", selected.domain)
        assertEquals(3L, selected.time.day)
        assertEquals("DAY_START", selected.time.phase)
        assertEquals(EntityId(7L), selected.actor)
        assertEquals(mapOf("k" to "v"), selected.metadata)
        val bare = ActionSelectionContext(domain = "EVENT", time = GameTime(0L))
        assertNull(bare.actor)
        assertTrue(bare.metadata.isEmpty())

        val value = candidate()
        val constraintContext = ActionConstraintContext("pkg.a", value, selected.time)
        assertEquals("pkg.a", constraintContext.packageId)
        assertSame(value, constraintContext.candidate)
        assertEquals(3L, constraintContext.time.day)

        val token = CancellationToken()
        val actionContext = ActionContext("pkg.a", value, selected.time, token)
        assertEquals("pkg.a", actionContext.packageId)
        assertSame(value, actionContext.candidate)
        assertSame(token, actionContext.cancellation)
        assertFalse(actionContext.cancellation.isCancelled)
    }
}