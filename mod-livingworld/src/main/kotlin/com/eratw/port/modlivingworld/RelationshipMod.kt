package com.eratw.port.modlivingworld

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.add
import com.eratw.port.modapi.has
import com.eratw.port.modapi.setStr
import com.eratw.port.modapi.setValue
import com.eratw.port.modapi.valueOf
import com.eratw.port.modlivingworld.generated.BondFacade
import com.eratw.port.modlivingworld.generated.BondKeys

/**
 * 关系 Mod 入口（§21.9 判据 4「双向独立关系数据」的落点）。
 *
 * 只经 mod-api 公开面表达（[ModContext.relationships] / [ModContext.entities]），无静态
 * 可变全局（铁律 4）；好感与备忘都是内核侧数据（边值 + 字段列），禁用不删（判据 6）。
 *
 * ## 与其他包的协作（Living World 的组合点）
 *
 * 好感边型由本包声明（[RelationshipDeclarations.AFFINITY]），但边型名是全局
 * 命名空间：事件 Mod 的候选约束直接读本包边型给好感计数（跨包读数据不跨包冒名 —— kind
 * 是数据面不是注册面，注册面隔离的是 provider/handler/constraint）。跨设备重映射按
 * schema 改写边端点（§7 持久实体引用三面），禁用本包不清边。
 *
 * 备忘字段的读写走生成物 [BondFacade]：字段名与类型由生成物一处提供。
 */
class RelationshipMod : EraMod {

    private lateinit var context: ModContext

    /** 类型化读写面（onLoad 装配）。 */
    private lateinit var bond: BondFacade

    override fun onLoad(context: ModContext) {
        this.context = context
        this.bond = BondFacade(context.entities)
    }

    override fun onEnable() {
        // 本包无运行期注册项：关系数据面全部随内核（边/列），不注册 provider/handler。
    }

    override fun onDisable() {
        // 无自有计数要落盘；边与字段在内核侧，禁用不清（判据 6）。
    }

    override fun onUnload() {
        // 无实例级可清理状态。
    }

    /**
     * 写单向好感：`from --affinity--> to` 边不存在则建（去重语义兜底重复写），边值 =
     * [value]。反向边不受影响 —— 「双向独立」的业务读数在 [affinityOf] 的方向参数里。
     */
    fun setAffinity(from: EntityId, to: EntityId, value: Long): Boolean {
        val added = context.relationships.add(
            from, RelationshipDeclarations.AFFINITY, to,
        )
        if (!added && !context.relationships.has(
                from, RelationshipDeclarations.AFFINITY, to,
            )
        ) {
            return false
        }
        return context.relationships.setValue(
            from, RelationshipDeclarations.AFFINITY, to, value,
        )
    }

    /** 读单向好感（无边返回 0；方向参数即「双向独立」的读数面）。 */
    fun affinityOf(from: EntityId, to: EntityId): Long = context.relationships.valueOf(
        from, RelationshipDeclarations.AFFINITY, to,
    )

    /**
     * 写一对实体的关系备忘（判据 4「各自字段」半边的写路径）。
     *
     * @return false = 写不进去（实体不存在/字段未注册，引擎写面失败哨兵），不静默成功。
     */
    fun recordBondNotes(entity: EntityId, notes: String): Boolean =
        // View 的 setter 丢弃写面返回值（生成物口径：View 是便利面，不复制引擎哨兵）；
        // 本方法契约要保留 Bool 语义，故走同一批生成物 Key 的类型化写面扩展。
        context.entities.setStr(entity, BondKeys.bondNotes, notes)

    /** 读关系备忘（未写返回空串）。 */
    fun bondNotesOf(entity: EntityId): String = bond.of(entity).bondNotes
}
