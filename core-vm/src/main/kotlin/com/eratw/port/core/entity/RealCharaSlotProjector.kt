package com.eratw.port.core.entity

import com.eratw.port.core.vars.VariableCode
import com.eratw.port.core.vars.VariableStore
import java.util.Locale

/**
 * 一条注水/脱水域声明：把角色行上的一个格子映射到 `__native_chara__` 组件字段。
 *
 * [variable] 是角色变量名（经 [VariableStore.readInt]/[readStr] 公共面按名解析）；
 * [indices] 是角色位**之外**的元素下标（0 维角色变量传空数组，1 维传单元素，
 * 多维按行主序）；[field] 是实体组件字段名（内核按大写归一查声明，未注册字段
 * 脱水时 fail-closed 抛出，不静默丢）。schema 形态归宿主声明侧
 * （era-arch-v61u.md §7 + RosterPinner KDoc「数据搬运归宿主」）：
 * 本类不做任何具体游戏列名的硬编码。
 */
class NativeCharaField(
    val variable: String,
    val indices: LongArray,
    val field: String,
    val isStr: Boolean = false,
) {
    init {
        require(variable.isNotBlank()) { "域声明的变量名不能为空" }
        require(field.isNotBlank()) { "域声明的组件字段名不能为空" }
    }
}

/**
 * 真实角色槽投射桥（M5-2；[ICharaSlotProjector] 的生产落点）。
 *
 * ## 管什么、不管什么
 *
 * - **管**：EntityId ↔ 原生角色行的投射映射生命周期（双向、幂等、平移、fail-closed
 *   钩子）+ 注水/脱水的**调用时序** + 退场的引擎已知指针清扫 + 行操作钩子接住
 *   VariableStore 五接点 + 原生 RELATION 格的真实读写（[RelationProjectionBridge]）+
 *   活动期行变更闸（[bindActivityGate]，M5-3：VM 执行中拒 [project]/[unproject]，
 *   判据语义 = 蓝图 §7 Mutation Boundary：投射/退场只允许在 VM 让出控制权的
 *   安全边界发生，不只查 depth==0——嵌套调用、事件回调、输入流程全在拒面）；
 * - **不管**：行数据的业务 schema（[fields] 域清单由宿主声明，全量逐域对拍归 M5-4）、
 *   会话装配（构造本类与 [EntityKernel]/[VirtualCharaNoRegistry] 并回填
 *   `store.charaSlotProjector` 归 C1/K3；闸 probe 的生产装配点接线同属装配层）。
 *
 * ## 与 RosterPinner 的关系
 *
 * MR-2b-1 的 fake 原型 [RosterPinner] 保持不动（原型是裁定锚）；本类是平行生产实现，
 * 映射重建（[onCharacterRowsPermuted]/[onCharacterRowDeleted] 的 fail-closed 校验、
 * [shiftAfter] 左移、[rewrite] 世代递增）与原型的形态逐条对应。unproject 不复用
 * RosterPinner 的删行路径：本类的退场删行走 [VariableStore.deleteCharacter]，映射平移
 * **由行删除钩子自然维护，本类不再手动平移**（严禁双删；RosterPinner 的桥不含钩子
 * 回环所以它必须手动 shiftAfter，本类不行）。
 *
 * ## 循环依赖破法（契约裁定 3）
 *
 * 本类持 [store]、store 又持本类（`charaSlotProjector` 钩子面）——装配序 =
 * 先建 store → 再建本类（构造拿到 store 完整引用）→ 最后回填
 * `store.charaSlotProjector = this`。store 侧为可空 var（与 writeProbe 同形），
 * 未回填前五个接点零调用，与本类无关的行操作不受影响。不为破循环改
 * [ICharaSlotProjector] 接口。
 *
 * ## 旁路面
 *
 * 本类不持静态状态、不碰全局；store 的 `charaSlotProjector == null` 时五个接点
 * 零调用零分配（热路径只多一次 null 比较）。`relations == null` 时本类不写任何
 * RELATION 格（纯 Base 旁路，与 RosterPinner 同口径）。
 *
 * @param store 变量池与角色表（行增删、NO/RELATION 列、指针变量的唯一操作面）
 * @param kernel 实体内核（投射资格与 `__native_chara__` 组件快照的读写）
 * @param virtualNos 虚拟 NO 注册表；null = 不写 NO 列（GETCHARA 线性扫描看不到投射行）
 * @param pkgs 包短 ID 注册表；非 null 时 [project] 前置校验实体 pkg 段有注册 backing
 *   （悬空身份拒投，fail-closed）
 * @param fields 注水/脱水域清单；空 = 只建行/维护映射/写 NO，不搬业务数据
 *   （全量逐域对拍归 M5-4，届时宿主按声明事务喂全量清单）
 */
class RealCharaSlotProjector(
    private val store: VariableStore,
    private val kernel: IEntityKernel,
    private val virtualNos: VirtualCharaNoRegistry? = null,
    private val pkgs: PackageShortIdRegistry? = null,
    private val fields: List<NativeCharaField> = emptyList(),
) : ICharaSlotProjector, RelationProjectionBridge {

    /**
     * 关系投影同步器；null = 不投射关系（纯 Base 旁路，与 RosterPinner 的
     * `relations` 参数同口径）。**构造后绑定**（[attachRelations]）：本类合体了
     * [RelationProjectionBridge] 面，而 [RelationProjectionSynchronizer] 构造又要
     * bridge 作实参 —— bridge→sync→projector 的环与循环依赖破法同解（装配序）。
     */
    private var relations: RelationProjectionSynchronizer? = null

    /**
     * 当前关系投影同步器(取证/诊断消费面;未接线 = null)。
     *
     * 生产消费点 = 装配层的页边界节拍读 [RelationProjectionSynchronizer.lastReport]
     * 把不干净的退场回写上日志面(`isClean` 为假即有改动没进图,不许静默)。
     * 只读投影,不改绑定状态;绑定只能走 [attachRelations]。
     */
    val relationSynchronizerOrNull: RelationProjectionSynchronizer? get() = relations

    /** 绑定关系投影时序面(幂等拒绝重绑;绑定后 project/unproject/钩子走同步器时序)。 */
    fun attachRelations(s: RelationProjectionSynchronizer) {
        require(relations == null) { "关系投影同步器重复绑定" }
        relations = s
    }

    /**
     * 活动期投射变更闸(M5-3,晚装端口):null = 未装配 = [project]/[unproject] 放行
     * (既有单测面零行为差)。probe 返回 true = VM 正在执行(活动调用栈),行变更当场拒。
     *
     * 判据语义 = era-arch-v61u.md §7 Mutation Boundary:「投射/退场引发的行增删只允许
     * 在 **VM 完全让出控制权**的安全边界执行(不只查 depth==0——嵌套调用、事件回调、
     * 输入流程、宿主持有的角色引用全部核对)」。生产 probe = 变量层帧活动
     * (`store.currentFunction != null`,装配点接线见 ModRuntimeAssembly):主 VM 入口帧、
     * callInto、jumpInto、嵌套 VM(deriveNestedVm 同 host)全部经
     * ProcessRuntime.onFunctionEnter/Exit 驱动 store.enterFunction/leaveFunction,
     * 输入等待(TINPUT park)帧挂着判活动;页边界 run 已返回、unwindScopes finally
     * 配对离场 ⇒ 放行。ADR-003 单线程:probe 读写同在 VM 线程,无可见性问题。
     *
     * 闸只挂行变更面([project]/[unproject]);脚本自身 SORTCHARA/DELCHARA 即时语义
     * 不动(ADR-005)——行操作钩子([onCharacterRowsPermuted]/[onCharacterRowDeleted])
     * 不经本闸,映射平移照旧。
     */
    @Volatile
    private var activityGate: (() -> Boolean)? = null

    /** 活动期变更闸是否已装配(取证读数;装配级用例据此钉「生产缝必接」)。 */
    val activityGateBound: Boolean get() = activityGate != null

    /**
     * 晚装活动期变更闸(照 [com.eratw.port.core.vars.VariableStore.bindCharaIndexResolver]
     * 晚装先例):已装配时拒绝重绑,当场抛而不静默替换——静默覆盖会让旧会话的闸形同虚设。
     * 生产装配点恒接一次;测试未绑 = 放行,既有用例零改动。
     */
    fun bindActivityGate(probe: () -> Boolean) {
        require(activityGate == null) { "活动期投射变更闸重复绑定:先解绑本会话探针再重绑" }
        activityGate = probe
    }

    /**
     * 收口解绑:只解 [probe] 自己那一份;重复解绑或传来别人的实例一律 no-op。
     * 与 [bindActivityGate] 成对,保证 shutdown 幂等且不误伤后一会话的绑定。
     */
    fun unbindActivityGate(probe: () -> Boolean) {
        if (activityGate === probe) activityGate = null
    }

    /**
     * 行变更面的闸检查:活动调用栈内当场抛,拒绝语义「点名操作与实体 + 指路安全边界」。
     * 未装配(缺省/既有单测面)或 probe 判非活动 = 放行,零分配(单 volatile 读)。
     */
    private fun assertOutsideActiveCallStack(operation: String, entityId: Long) {
        val gate = activityGate ?: return
        if (!gate()) return
        throw IllegalStateException(
            "活动调用栈内禁止投射变更:$operation(EntityId=${entityId.toString(16)})被拒——" +
                "VM 正在执行脚本帧,行增删只允许在 VM 完全让出控制权的页边界提交" +
                "(era-arch-v61u.md §7 Mutation Boundary;请改经 RuntimeScheduler.submit " +
                "到转移边界兑现,脚本自身 SORTCHARA/DELCHARA 即时语义不受影响)",
        )
    }

    private val entityToChara = HashMap<Long, Int>()
    private val charaToEntity = HashMap<Int, Long>()

    /** 行排列世代：只在 [rewrite]（排列/删除/退场删行）递增；project 尾增不走 rewrite。 */
    @Volatile
    override var rosterGeneration: Long = 0L
        private set

    // ------------------------------------------------------------------
    // ICharaSlotProjector
    // ------------------------------------------------------------------

    override fun project(entityId: Long): Int {
        assertOutsideActiveCallStack("project", entityId)
        entityToChara[entityId]?.let { return it } // 幂等：已投射返回当前行号
        if (!kernel.exists(entityId)) return -1
        pkgs?.let {
            if (it.packageIdOf(EntityIdCodec.pkgShortIdOf(entityId)) == null) return -1
        }
        // 走既有行新增路径（ADDVOIDCHARA 语义：伪角色尾部追加、整行清零）。
        val row = store.addPseudoCharacter()
        pour(entityId, row)
        // 投射行的原生 NO 列写虚拟 NO：GETCHARA 的线性扫描按 NO 列命中，无需新通道
        // （era-arch-v61u.md §7；无 registry 时不写，既有行为不变）。
        virtualNos?.let { store.writeInt(NO_VARIABLE, longArrayOf(row.toLong()), it.allocate(entityId).toLong()) }
        entityToChara[entityId] = row
        charaToEntity[row] = entityId
        relations?.onProjected(entityId, row)
        return row
    }

    override fun unproject(entityId: Long): Boolean {
        assertOutsideActiveCallStack("unproject", entityId)
        val row = entityToChara[entityId] ?: return false
        // fail-closed：删行后的映射平移靠 store 的行删除钩子回环（见类 KDoc 装配序）；
        // 未回填就 unproject 会让映射静默错位（串号），当场拒绝。
        require(store.charaSlotProjector === this) {
            "RealCharaSlotProjector 未绑定到 store.charaSlotProjector——先按装配序回填再退场"
        }
        // 时序铁律（与 RosterPinner 同构）：先脱水（活行 → 组件，拿未清残留的原始行），
        // 再关系回写 + 只清退场者自己的行与列，最后删行。三步顺序不可重排。
        syncFromCharaSlot(entityId)
        relations?.onUnprojecting(entityId, row)
        // 删行经 store.deleteCharacter：行删除钩子在此自然维护映射（本实体解映射 +
        // 其余投射行左移 + 世代递增），本方法**不做任何手动平移**（严禁双删）。
        store.deleteCharacter(row.toLong())
        // 引擎已知指针清扫（§7 引用审计诚实边界）：TARGET 全数组/ASSI/PLAYER 中
        // 等于 deletedPos 的置默认、大于者减 1。MASTER 不在名单（原版语义主人公
        // 恒在场）；RELATION 残留归 relations 的 scoped 清残留，这里一格不碰。
        sweepPointers(row)
        return true
    }

    override fun charIndexOf(entityId: Long): Int = entityToChara[entityId] ?: -1

    override fun charIndexOfChecked(entityId: Long, expectedGeneration: Long): Int =
        if (rosterGeneration != expectedGeneration) -1 else charIndexOf(entityId)

    override fun entityAt(charIndex: Int): Long = charaToEntity[charIndex] ?: EntityIdCodec.NULL

    override fun projectedEntities(): LongArray = entityToChara.keys.toLongArray().also { it.sort() }

    override fun syncFromCharaSlot(entityId: Long) {
        val row = entityToChara[entityId] ?: return
        capture(entityId, row)
    }

    override fun onCharacterRowsPermuted(oldToNewIndexMap: IntArray) {
        val newEntityToChara = HashMap<Long, Int>(entityToChara.size * 2)
        for ((entity, oldRow) in entityToChara) {
            val newRow = oldToNewIndexMap.getOrNull(oldRow)
            require(newRow != null && newRow >= 0) {
                "行排列钩子把已投射实体（EntityId=${entity.toString(16)}，行 $oldRow）映射到 -1" +
                    "——先 unproject 再排列，或修正排列表（fail-closed）"
            }
            require(!newEntityToChara.containsValue(newRow)) {
                "行排列钩子把两个已投射实体折到同一行 $newRow——排列表损坏，拒绝"
            }
            newEntityToChara[entity] = newRow
        }
        rewrite(newEntityToChara)
    }

    override fun onCharacterRowDeleted(deletedIndex: Int) {
        val entity = charaToEntity.remove(deletedIndex)
        if (entity != null) {
            entityToChara.remove(entity)
            // 行已被引擎删掉，格子无从读取：丢基线并计损失（ADR-005 即时语义，不假装无损）。
            relations?.forget(entity)
        }
        shiftAfter(deletedIndex)
    }

    // ------------------------------------------------------------------
    // RelationProjectionBridge（原生 RELATION 格的真实读写；度数不是布尔，§7）
    // ------------------------------------------------------------------

    /** 原生格默认值 = 新建角色的 RELATION 初值（spec: ConfigData.cs:174）。 */
    override val relationDefault: Long get() = store.config.relationDef

    override fun readCell(charIndex: Int, targetKey: Long): Long =
        relationColumn().get(charIndex, physicalColumn(targetKey))

    override fun writeCell(charIndex: Int, targetKey: Long, value: Long) {
        relationColumn().set(charIndex, physicalColumn(targetKey), value)
    }

    /**
     * 当前 roster 的全部行（原生 + 已投射），行号升序、列键 = 各行 NO 列值
     * （原生 = CSV NO、投射 = 注水写入的虚拟 NO——列键在投射期内稳定：
     * VirtualCharaNoRegistry 只在 retire 时换号，行排列/删除不动号，§7）。
     */
    override fun rosterRows(): List<RosterRow> {
        val noCol = store.characters.int0[VariableCode.lowIndex(VariableCode.NO_VAR)]
        return (0 until store.characters.count).map { row -> RosterRow(row, noCol.get(row, 0)) }
    }

    /** 行 → 图身份：生产 = 投射映射反查（原生锚注册表归 C1/K3 会话装配）。 */
    override fun graphIdentityOf(charIndex: Int): Long = entityAt(charIndex)

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun relationColumn() =
        store.characters.int1[VariableCode.lowIndex(VariableCode.RELATION)]

    /**
     * 列键 → 物理列下标（RELATION 的第二维）。
     *
     * 原生第二维就是**角色位置**：引擎求值器把 `RELATION:a:b` 的 b 直接当元素下标
     * （`VariableStore.keywordToInteger` 的 RELATION 分支只把名字翻成 NO，随后照常
     * 当下标用），而列键是**稳定身份**（原生 = NO 列值、投射 = 虚拟 NO）。二者只在
     * 「一行都没删过」时重合，所以每次必须按 NO 列反查**当前行号**，不能把列键直接
     * 当元素下标——虚拟 NO 是 10000 级，而 RELATION 的每行元素数只有 VARSIZE 给的
     * 百级（`CSV/VariableSize.csv` 的 `RELATION` 行），直接当下标必然越界。
     *
     * 列键不在 roster（基线漂移 / 行已被引擎删）⇒ 当场抛：静默写别人的格是把数据
     * 写到错误身份上，比抛错更糟。列键互异由 [rosterRows] 的调用方（同步器）保证；
     * 同一 NO 出现在两行（ADDCHARA 同 NO 重复）时取**首个**匹配行，与
     * [VariableStore.keywordToInteger] 的「先到者」口径同形。
     *
     * TODO(verify)：`RELATION:a:"角色名"` 关键字形态的语义是「元素下标 = CSV NO」，
     * 本桥按「元素下标 = 当前行号」写读——两者只在未删行时重合，删行后关键字形态
     * 会落到别的行。这是原生引擎自带的口径分歧（数值形态 `RELATION:a:b` 的 b 就是
     * 行号，是语料主用法），不属本桥可单方面抹平的差异。
     */
    private fun physicalColumn(targetKey: Long): Int {
        val noCol = store.characters.int0[VariableCode.lowIndex(VariableCode.NO_VAR)]
        for (row in 0 until store.characters.count) {
            if (noCol.get(row, 0) == targetKey) return row
        }
        throw IllegalArgumentException(
            "关系格列键 $targetKey 不在当前 roster（NO 列无匹配行）——拒绝按漂移键读写",
        )
    }

    /** 域注水：组件字段 → 活行格子（变量名/下标形状非法由 store 公共面当场抛 CodeEE）。 */
    private fun pour(entityId: Long, row: Int) {
        for (f in fields) {
            val indices = rowIndices(row, f.indices)
            if (f.isStr) store.writeStr(f.variable, indices, kernel.getStr(entityId, f.field))
            else store.writeInt(f.variable, indices, kernel.getInt(entityId, f.field))
        }
    }

    /** 域脱水：活行格子 → 组件字段（字段未注册 = 装配缺口，fail-closed 不静默丢）。 */
    private fun capture(entityId: Long, row: Int) {
        for (f in fields) {
            val indices = rowIndices(row, f.indices)
            val ok = if (f.isStr) kernel.setStr(entityId, f.field, store.readStr(f.variable, indices))
            else kernel.setInt(entityId, f.field, store.readInt(f.variable, indices))
            require(ok) {
                "脱水失败：组件字段 ${f.field.uppercase(Locale.ROOT)} 未注册或类型不符" +
                    "（EntityId=${entityId.toString(16)}，域 ${f.variable}${f.indices.toList()}）——" +
                    "声明事务缺字段，拒收而非静默丢"
            }
        }
    }

    private fun rowIndices(row: Int, elem: LongArray): LongArray {
        val out = LongArray(elem.size + 1)
        out[0] = row.toLong()
        elem.copyInto(out, 1)
        return out
    }

    /**
     * 引擎已知指针清扫：TARGET 全数组/ASSI/PLAYER 中等于 [deletedPos] 的置
     * [POINTER_DEFAULT]、大于者减 1（顺序照 MR-2b-1 fake 原型的 releaseRow 义务；
     * 逐元素读改写走 store 公共面）。变量禁用（尺寸 0）时与 [com.eratw.port.core.vars.VariableStore.charaPointer]
     * 同口径跳过。引擎删行不动指针值，删行前后读到的值相同，扫删除后的表与
     * 「按删除前值对照 deletedPos」逐位等价。
     */
    private fun sweepPointers(deletedPos: Int) {
        for (name in POINTER_VARIABLES) {
            val def = store.registry.def(name) ?: continue
            if (def.isForbid || def.dims.isEmpty()) continue
            val len = def.dims[0]
            for (i in 0 until len) {
                val idx = longArrayOf(i.toLong())
                val v = store.readInt(name, idx)
                if (v == deletedPos.toLong()) store.writeInt(name, idx, POINTER_DEFAULT)
                else if (v > deletedPos) store.writeInt(name, idx, v - 1)
            }
        }
    }

    /** 大于 [deletedIndex] 的行号左移 1（两向映射同步重建）。 */
    private fun shiftAfter(deletedIndex: Int) {
        rewrite(
            entityToChara.entries.associate { (entity, row) ->
                entity to if (row > deletedIndex) row - 1 else row
            },
        )
    }

    private fun rewrite(mapping: Map<Long, Int>) {
        // 行排列/删除唯一的映射重建出口 ⇒ 世代在此递增（§7：行排列即递增）。
        rosterGeneration++
        entityToChara.clear()
        charaToEntity.clear()
        for ((entity, row) in mapping) {
            entityToChara[entity] = row
            charaToEntity[row] = entity
        }
    }

    private companion object {
        /** NO 列（角色 0 维整数第 1 槽）：投射行写虚拟 NO，GETCHARA 线性扫描的命中键。 */
        const val NO_VARIABLE: String = "NO"

        /** 退场指针默认值（无指向；与 charaPointer 的禁用/缺位读数同口径）。 */
        const val POINTER_DEFAULT: Long = -1L

        /** 清扫名单（era-arch-v61u.md §7 引用审计：TARGET 全数组/ASSI/PLAYER）。 */
        val POINTER_VARIABLES: List<String> = listOf("TARGET", "ASSI", "PLAYER")
    }
}
