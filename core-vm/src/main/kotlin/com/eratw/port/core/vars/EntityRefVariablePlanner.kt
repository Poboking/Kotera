package com.eratw.port.core.vars

import com.eratw.port.core.entity.EntityIdRemapTable
import com.eratw.port.core.patch.DeclaredEntityRef
import com.eratw.port.core.patch.EntityRefScope
import java.util.Locale

/** 专用实体引用槽；CHARA 的 index = 角色位 × 实际发射块长 + 块内扁平下标。 */
data class EntityRefVariableSlot(
    val scope: EntityRefScope,
    val key: String,
    val index: Int,
    val old: Long,
)

/** 已预检的原子变更项；old 用于提交前再次证明变量池未被并发改动。 */
data class EntityRefVariableChange(
    val scope: EntityRefScope,
    val key: String,
    val index: Int,
    val old: Long,
    val new: Long,
)

/**
 * entity-ref 第三遍历面：只枚举冻结声明 ∩ 已注册 ∩ INT ∩ SAVEDATA ∩ 实际发射。
 * normal/global 以 SaveCapture 的注册表/savedUserVarList 为真源；chara 以活角色位与
 * CharaFileIO 的 C_INT 系列与用户 CHARADATA 发射形状为真源。私有作用域永不进入本计划。
 */
object EntityRefVariablePlanner {

    fun enumerate(
        store: VariableStore,
        declarations: Collection<DeclaredEntityRef>,
        scopes: Set<EntityRefScope> = EntityRefScope.entries.toSet(),
        csvPlan: ModCsvInjectionPlan? = null,
    ): List<EntityRefVariableSlot> {
        val slots = ArrayList<EntityRefVariableSlot>()
        val seen = HashSet<Triple<EntityRefScope, String, Int>>()
        for (declaration in declarations.sortedWith(compareBy({ it.packageId }, { it.key }, { it.scope }))) {
            if (declaration.scope !in scopes) continue
            val target = resolve(store, declaration, csvPlan) ?: continue
            val positions = if (target.scope == EntityRefScope.CHARA) {
                0 until store.characters.count
            } else {
                0..0
            }
            if (target.scope == EntityRefScope.CHARA) {
                require(store.characters.count.toLong() * target.blockSize <= Int.MAX_VALUE) {
                    "entity-ref CHARA 扁平槽容量溢出：${target.def.name}"
                }
            }
            for (pos in positions) {
                for (elem in 0 until target.blockSize) {
                    val index = if (target.scope == EntityRefScope.CHARA) pos * target.blockSize + elem else elem
                    require(index >= 0) { "entity-ref 槽索引溢出：${target.def.name}" }
                    if (!seen.add(Triple(target.scope, target.def.name, index))) continue
                    slots += EntityRefVariableSlot(
                        target.scope,
                        target.def.name,
                        index,
                        read(store, target.uv, target.def, target.scope, pos, elem),
                    )
                }
            }
        }
        return slots
    }

    fun plan(
        store: VariableStore,
        declarations: Collection<DeclaredEntityRef>,
        table: EntityIdRemapTable,
        scopes: Set<EntityRefScope> = EntityRefScope.entries.toSet(),
        csvPlan: ModCsvInjectionPlan? = null,
        strict: Boolean = false,
    ): List<EntityRefVariableChange> {
        if (strict) {
            for (declaration in declarations) {
                if (declaration.scope in scopes) {
                    requireNotNull(resolve(store, declaration, csvPlan)) {
                        "entity-ref 声明槽无法证明安全：${declaration.packageId}/${declaration.key}/${declaration.scope}"
                    }
                }
            }
        }
        return enumerate(store, declarations, scopes, csvPlan).map { slot ->
            EntityRefVariableChange(slot.scope, slot.key, slot.index, slot.old, table.remap(slot.old))
        }
    }

    private data class ResolvedTarget(
        val uv: UserVar?,
        val def: VarDef,
        val scope: EntityRefScope,
        val blockSize: Int,
    )

    private fun resolve(
        store: VariableStore,
        declaration: DeclaredEntityRef,
        csvPlan: ModCsvInjectionPlan?,
    ): ResolvedTarget? {
        val key = declaration.key.uppercase(Locale.ROOT)
        val uv = store.userVar(key, null)
        val def = uv?.def ?: store.registry.def(key) ?: return null
        if (def.type != VarType.INT || !def.isSavedata || def.isConst) return null
        val actualScope = when {
            def.isCharacterData -> EntityRefScope.CHARA
            def.isGlobal -> EntityRefScope.GLOBAL
            else -> EntityRefScope.NORMAL
        }
        if (actualScope != declaration.scope) return null
        if (uv != null && uv !in store.savedUserVarList()) return null
        val blockSize = when {
            uv != null -> userBlockSize(def, actualScope)
            actualScope == EntityRefScope.CHARA -> charaSystemBlockSize(store, def, csvPlan)
            else -> normalSystemBlockSize(store, def)
        }
        if (blockSize < 0) return null
        return ResolvedTarget(uv, def, actualScope, blockSize)
    }

    /** 先验证全部 old，再写全部 new；预检失败时变量池零写入。 */
    fun apply(
        store: VariableStore,
        changes: List<EntityRefVariableChange>,
        csvPlan: ModCsvInjectionPlan? = null,
    ) {
        data class WriteTarget(
            val change: EntityRefVariableChange,
            val uv: UserVar?,
            val def: VarDef,
            val pos: Int,
            val elem: Int,
        )
        val targets = changes.map { change ->
            val key = change.key.uppercase(Locale.ROOT)
            val uv = store.userVar(key, null)
            val def = uv?.def ?: store.registry.def(key)
                ?: throw IllegalArgumentException("entity-ref 变更目标未注册：$key")
            val actualScope = when {
                def.isCharacterData -> EntityRefScope.CHARA
                def.isGlobal -> EntityRefScope.GLOBAL
                else -> EntityRefScope.NORMAL
            }
            require(actualScope == change.scope && def.type == VarType.INT && def.isSavedata && !def.isConst) {
                "entity-ref 变更目标作用域/类型/SAVEDATA 不一致：$key"
            }
            require(uv == null || uv in store.savedUserVarList()) {
                "entity-ref 变更目标不是实际 SAVEDATA 发射槽：$key"
            }
            val blockSize = when {
                uv != null -> userBlockSize(def, actualScope)
                actualScope == EntityRefScope.CHARA -> charaSystemBlockSize(store, def, csvPlan)
                else -> normalSystemBlockSize(store, def)
            }
            require(blockSize > 0) { "entity-ref 变更目标没有实际发射容量：$key" }
            val pos = if (actualScope == EntityRefScope.CHARA) change.index / blockSize else 0
            val elem = if (actualScope == EntityRefScope.CHARA) change.index % blockSize else change.index
            require(blockSize > 0 && elem in 0 until blockSize) { "entity-ref 变更索引越界：$key[${change.index}]" }
            if (actualScope == EntityRefScope.CHARA) {
                require(pos in 0 until store.characters.count) { "entity-ref 变更角色位越界：$key[${change.index}]" }
            }
            WriteTarget(change, uv, def, pos, elem)
        }
        require(targets.map { Triple(it.change.scope, it.change.key, it.change.index) }.toSet().size == targets.size) {
            "entity-ref 变更目标重复"
        }
        for (target in targets) {
            val actual = read(store, target.uv, target.def, target.change.scope, target.pos, target.elem)
            require(actual == target.change.old) {
                "entity-ref 变更基线漂移：${target.change.key}[${target.change.index}] expected=${target.change.old} actual=$actual"
            }
        }
        for (target in targets) write(store, target.uv, target.def, target.pos, target.elem, target.change.new)
    }

    private fun userBlockSize(def: VarDef, scope: EntityRefScope): Int {
        if (scope == EntityRefScope.CHARA) {
            if (def.dims.size > 2) return -1
        } else if (def.dims.size > 3) {
            return -1
        }
        return dimProduct(def.dims)
    }

    private fun normalSystemBlockSize(store: VariableStore, def: VarDef): Int = when (def.storage) {
        Storage.G_INT1, Storage.G_INT2, Storage.G_INT3 ->
            store.systemIntColumn(def)?.length ?: dimProduct(def.dims)
        else -> -1
    }

    private fun charaSystemBlockSize(
        store: VariableStore,
        def: VarDef,
        csvPlan: ModCsvInjectionPlan?,
    ): Int {
        val c = store.characters
        return when (def.storage) {
            Storage.C_INT0 -> 1
            Storage.C_INT1 -> csvPlan?.baseLength(def.name, def.storage, def.dims)
                ?.coerceIn(0, c.int1[def.index].blockSize)
                ?: c.int1[def.index].blockSize
            Storage.C_INT2 -> {
                val dims = csvPlan?.baseDimsOf(def.name, def.dims) ?: def.dims
                val size = dimProduct(dims)
                require(size <= c.int2[def.index].blockSize) { "C_INT2 ${def.name} 发射长度超过实际列容量" }
                size
            }
            else -> -1
        }
    }

    private fun dimProduct(dims: IntArray): Int {
        var product = 1L
        for (dim in dims) {
            require(dim >= 0) { "entity-ref 变量维度为负：$dim" }
            product *= dim
            require(product <= Int.MAX_VALUE) { "entity-ref 变量容量溢出" }
        }
        return product.toInt()
    }

    private fun read(
        store: VariableStore,
        uv: UserVar?,
        def: VarDef,
        scope: EntityRefScope,
        pos: Int,
        elem: Int,
    ): Long {
        if (uv != null) return uv.readInt(pos, elem)
        return if (scope == EntityRefScope.CHARA) {
            val c = store.characters
            when (def.storage) {
                Storage.C_INT0 -> c.int0[def.index].get(pos, elem)
                Storage.C_INT1 -> c.int1[def.index].get(pos, elem)
                else -> c.int2[def.index].get(pos, elem)
            }
        } else {
            store.readInt(def.name, flatIndices(def.dims, elem))
        }
    }

    private fun write(
        store: VariableStore,
        uv: UserVar?,
        def: VarDef,
        pos: Int,
        elem: Int,
        value: Long,
    ) {
        if (uv != null) {
            uv.writeInt(pos, elem, value)
            return
        }
        val c = store.characters
        when (def.storage) {
            Storage.C_INT0 -> c.int0[def.index].set(pos, elem, value)
            Storage.C_INT1 -> c.int1[def.index].set(pos, elem, value)
            Storage.C_INT2 -> c.int2[def.index].set(pos, elem, value)
            Storage.G_INT1, Storage.G_INT2, Storage.G_INT3 ->
                store.writeInt(def.name, flatIndices(def.dims, elem), value)
            else -> throw IllegalArgumentException("entity-ref 存储不支持写入：${def.storage}")
        }
    }

    private fun flatIndices(dims: IntArray, flat: Int): LongArray {
        val out = LongArray(dims.size)
        var rest = flat
        for (i in dims.indices.reversed()) {
            require(dims[i] > 0) { "entity-ref 变量存在零维度：${dims.toList()}" }
            out[i] = (rest % dims[i]).toLong()
            rest /= dims[i]
        }
        require(rest == 0) { "entity-ref 扁平下标越界：$flat/${dims.toList()}" }
        return out
    }
}
