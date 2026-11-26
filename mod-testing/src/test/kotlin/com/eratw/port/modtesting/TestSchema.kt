package com.eratw.port.modtesting

import com.eratw.port.modapi.ArchetypeKey
import com.eratw.port.modapi.ComponentKey
import com.eratw.port.modapi.FieldKey
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.FunctionKey
import com.eratw.port.modapi.HookOptions
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.JobKey
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RelationKey
import com.eratw.port.modapi.intKey
import com.eratw.port.modapi.modDeclarations
import com.eratw.port.modapi.refKey
import com.eratw.port.modapi.strKey

/**
 * 台架自测的共享 schema 夹具：一份最小但覆盖全部能力面的 manifest。
 *
 * 关系面刻意摆出三档语义：`test.knows` 有向去重、`test.mentors` autoInverse + 不级联、
 * `test.mentioned` 无向。作者照它就能写出「我不知道生产语义是什么」的对照式用例。
 */
internal object TestSchema {

    const val PACKAGE_ID: String = "test.harness"

    val COMPONENT: ComponentKey = ComponentKey("test.npc.component")
    val ARCHETYPE: ArchetypeKey = ArchetypeKey("test.npc")

    val AGE: FieldKey<Long> = intKey("age")
    val NAME: FieldKey<String> = strKey("name")
    val MENTOR: FieldKey<EntityId> = refKey("test.mentor")

    val KNOWS: RelationKey = RelationKey("test.knows")
    val MENTORS: RelationKey = RelationKey("test.mentors")
    val MENTORED_BY: RelationKey = RelationKey("test.mentored_by")
    val MENTIONED: RelationKey = RelationKey("test.mentioned")

    val TICK: JobKey = JobKey("test.tick")
    val PING: FunctionKey = FunctionKey("TEST.PING")

    /** 钩子/交互自测的目标函数键（PREFIX 与 POSTFIX 各声明一条槽）。 */
    val HOOKED: FunctionKey = FunctionKey("TEST.HOOKED")

    /** 对端包的包名（跨 mod 装配：priority 比本包小 => 先跑）。 */
    const val PEER_PACKAGE_ID: String = "test.peer"

    fun manifest(): ModManifest = modDeclarations(PACKAGE_ID, version = "0.1") {
        displayName("台架自测")
        component(COMPONENT) {
            int(AGE)
            str(NAME)
            ref(MENTOR)
        }
        archetype(ARCHETYPE) {
            uses(COMPONENT)
        }
        relation(KNOWS)
        relation(MENTORS, inverse = MENTORED_BY, cascadeOnDestroy = false)
        relation(MENTORED_BY, cascadeOnDestroy = false)
        // 无向关系：两端任一方向加边都算同一条边。
        relation(MENTIONED, directed = false)
        job(TICK, everyDays = 1L)
        hook(PING, HookPhase.PREFIX)
        // 本包 PREFIX 槽排在 peer 之后（priority 10 > 5），但被授权改实参。
        hook(HOOKED, HookPhase.PREFIX, HookOptions(priority = 10, mayMutateArgs = true))
        hook(HOOKED, HookPhase.POSTFIX, HookOptions(priority = 0))
        interactionHook(InteractionPhase.PRECONDITION)
        interactionHook(InteractionPhase.PRE)
        interactionHook(InteractionPhase.MAIN)
        interactionHook(InteractionPhase.POST)
        interactionHook(InteractionPhase.SETTLEMENT)
    }

    /** 对端包：PREFIX 槽 priority 5 且**未**授权改实参（验证隔离）。 */
    fun peerManifest(): ModManifest = modDeclarations(PEER_PACKAGE_ID, version = "0.1") {
        displayName("对端")
        hook(HOOKED, HookPhase.PREFIX, HookOptions(priority = 5))
    }

    fun world(): TestWorld = TestWorld.ofManifests(manifest())

    /** 本包 + 对端包合并 schema 的台架世界（跨 mod 事件/钩子/交互排序用）。 */
    fun worldWithPeer(): TestWorld = TestWorld.ofManifests(manifest(), peerManifest())
}
