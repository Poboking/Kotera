package com.eratw.port.modlivingworld

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityRefFieldCmp
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.HasRelation
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.queryPlan

/**
 * Living World 记忆 Mod 入口（§21.9 判据 3「知识不自动传播」的落点；模型形状照 M91
 * `eratw.memory` 先例，最小化：见 [MemoryDeclarations] 头 KDoc 的取径说明）。
 *
 * ## 传播闸在哪里
 *
 * 「A 亲历的事实不自动成为 B 的知识」**不是一条 if 判断，而是没有那条边**：知识可达性
 * 只由 KNOWS 边决定（[knowledgeOf] 按入边圈定），引擎与全库没有任何代码会替 B 建边 ——
 * B 只能经显式 [teach] 拿到 HEARSAY 副本。负向证明（摘传播闸 ⇒ 自动传播红）由组合测试
 * 的反向用例承担。
 */
class MemoryMod : EraMod {

    private lateinit var context: ModContext

    override fun onLoad(context: ModContext) {
        this.context = context
    }

    override fun onEnable() {
        // 无运行期注册项：记忆本体全在内核侧（实体 + 边），无任务、无仲裁参与。
    }

    override fun onDisable() {
        // 无自有计数；fact 实体与 KNOWS 边在内核侧，禁用不清（判据 6）。
    }

    override fun onUnload() {
        // 无实例级可清理状态。
    }

    /**
     * 记录一条亲历知识：建 fact 实体（**第一步**，槽耗尽是唯一失败点）→ 写字段 →
     * 挂 `witness --KNOWS--> fact` 边（边值 = [Provenance.FIRSTHAND].code）。
     *
     * @throws IllegalArgumentException witness 不存在（fail-closed 于第一笔变更之前）。
     * @return 新 fact 的稳定身份。
     */
    fun recordWitness(witness: EntityId, about: EntityId, predicate: String, occurredAt: Long): EntityId {
        require(context.entities.exists(witness)) { "witness 不存在：$witness（不落无主知识）" }
        val fact = context.entities.create(MemoryDeclarations.ARCHETYPE_FACT)
        context.entities.setRef(fact, MemoryDeclarations.FIELD_SUBJECT, about)
        context.entities.setStr(fact, MemoryDeclarations.FIELD_PREDICATE, predicate)
        context.entities.setInt(fact, MemoryDeclarations.FIELD_OCCURRED_AT, occurredAt)
        context.relationships.add(witness, MemoryDeclarations.RELATION_KNOWS, fact)
        context.relationships.setValue(
            witness, MemoryDeclarations.RELATION_KNOWS, fact, Provenance.FIRSTHAND.code,
        )
        return fact
    }

    /**
     * 显式传播（判据 3 的**唯一**通道）：给 [learner] 挂自己的 KNOWS 边，边值 =
     * [provenance].code（缺省 HEARSAY —— 听来的）。fact 字段上的首录语义不受影响。
     *
     * @return false = fact 不存在（含已遗忘）或挂边失败；不静默成功。
     */
    fun teach(learner: EntityId, fact: EntityId, provenance: Provenance = Provenance.HEARSAY): Boolean =
        context.entities.exists(fact) &&
            context.relationships.add(learner, MemoryDeclarations.RELATION_KNOWS, fact) &&
            context.relationships.setValue(
                learner, MemoryDeclarations.RELATION_KNOWS, fact, provenance.code,
            )

    /** [owner] 的知识集 = KNOWS 入边指向的 fact（EntityId 升序快照）。 */
    fun knowledgeOf(owner: EntityId): List<EntityId> = context.query.query(
        queryPlan {
            archetype(MemoryDeclarations.ARCHETYPE_FACT)
            where(HasRelation(MemoryDeclarations.RELATION_KNOWS, outgoing = false, other = owner))
        },
    )

    /** [owner] 持有 [fact] 的认知来源（读边值）；无边（不知道）返回 null。 */
    fun provenanceOf(owner: EntityId, fact: EntityId): Provenance? {
        val code = context.relationships.valueOf(owner, MemoryDeclarations.RELATION_KNOWS, fact)
        return Provenance.entries.firstOrNull { it.code == code }
    }
}
