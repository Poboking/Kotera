package com.eratw.port.core.mod

import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.patch.WriteGrantDeclaration
import com.eratw.port.core.vars.VarDef
import com.eratw.port.core.vars.VarType
import com.eratw.port.modapi.RuntimeScheduler

/**
 * 原版全局变量受控写的**授权闸**（ModDX4-B1：ERB↔Kotlin 状态桥二期写面）。
 *
 * 会话级一份，宿主在 [com.eratw.port.android.ModRuntimeAssembly.attach] 装配相位构造：
 * 声明面真源 = 各包 manifest `write-grants` 段（[WriteGrantDeclaration]）∪ 声明事务快照里
 * **本包自家**的 global-dims（[DeclarationSnapshot.globalDims]，[DeclaredGlobalDim.packageId]
 * 归属判包）。授权语义 fail-closed：
 *
 * - 本包 `global-dims` 声明的变量：免授权（自家变量写自家），但**不可寻址形态**（2 维以上）
 *   仍拒 —— 本写面与读侧同口径只达 0 维标量与 1 维非角色数组的裸引用格；
 * - `write-grants` 声明的键：授权类型必须与写通道一致（INT 授权只放整型写、STR 只放字符串写）；
 * - 其余键：[IllegalArgumentException]（文案对齐 `ModSettingsSnapshot` 的未声明键口径，
 *   点名 packageId 与键）。
 *
 * ## 装载相位校验（坏 grant 整包拒）
 *
 * [build] 对每条 grant 对 `defOf`（生产 = `VariableStore.registry::def`）做**声明期校验**：
 * 键不存在 / 类型不符（含浮点，本写面只有 INT/STR 两通道）/ 常量 / 角色变量 / 2 维以上
 * 不可寻址形态，任一命中即抛 [IllegalArgumentException] —— 生产调用点把它交给 attach 的
 * 既有装配失败链（与坏档 [com.eratw.port.core.patch.ManifestFormatException] 同一 fail-closed
 * 口径：装载相位拒绝，不返回半装配会话，无静默降级）。
 *
 * 状态全挂实例（两张不可变 Map），无静态可变全局；authorize 是纯查询，线程口径与调用方
 * （mod 的 [RuntimeScheduler] 提交链）一致。
 *
 * public 而非 internal：宿主（:app）在装配相位要构造 [ModDx4WriteApplier] 并登记进
 * [ModRuntimeHost.gameStateWriteApplier]（跨模块注入缝，与 `ModResourceStore` 同口径）；
 * 授权判定本体（[authorize] / [build] 的校验链）保持 internal，只在本模块消费。
 */
class ModDx4WriteGate private constructor(
    /** packageId →（大写归一 grant 键 → 授权类型位）。仅装载期校验通过的键在场。 */
    private val grantsByPackage: Map<String, Map<String, Boolean>>,
    /** packageId →（大写归一自家 global-dim 键 → 授权条目）。含不可寻址形态（authorize 给专属文案）。 */
    private val ownDimsByPackage: Map<String, Map<String, OwnDimEntry>>,
) {

    /** 本包自家 global-dim 的授权条目：类型位 + 本写面可否按名寻址（0 维 / 1 维非角色 = 可）。 */
    internal class OwnDimEntry(val isStr: Boolean, val addressable: Boolean)

    /**
     * 授权判定（写通道的调用点闸）：放行 = 本包自家可寻址变量或类型一致的 grant；
     * 其余当场抛 [IllegalArgumentException]，不静默、不进队列。
     */
    internal fun authorize(packageId: String, name: String, wantStr: Boolean) {
        val own = ownDimsByPackage[packageId]?.get(canonicalKey(name))
        if (own != null) {
            if (!own.addressable) {
                throw IllegalArgumentException(
                    "全局变量写不可寻址：$packageId/$name（本包声明的变量是 2 维以上形态，" +
                        "本写面与读侧同口径只达 0 维标量与 1 维非角色数组的裸引用格）",
                )
            }
            requireType(own.isStr, wantStr, packageId, name, "本包 global-dims 声明")
            return
        }
        val granted = grantsByPackage[packageId]?.get(canonicalKey(name))
        if (granted == null) {
            throw IllegalArgumentException(
                "全局变量写未授权：$packageId/$name（write-grants 未声明该键；" +
                    "未声明的键不可写 —— fail-closed，不静默吞写）",
            )
        }
        requireType(granted, wantStr, packageId, name, "write-grants 声明")
    }

    /** 类型一致性判定：INT 授权只走整型写、STR 只走字符串写，不隐式转（口径同设置面）。 */
    private fun requireType(grantedIsStr: Boolean, wantStr: Boolean, packageId: String, name: String, via: String) {
        if (grantedIsStr != wantStr) {
            throw IllegalArgumentException(
                "全局变量写类型不符：$packageId/$name（$via 为 " +
                    "${if (grantedIsStr) "STR" else "INT"}，本写入口是 " +
                    "${if (wantStr) "STR" else "INT"} 通道；不隐式转换）",
            )
        }
    }

    /** 键的大写归一（与变量层寻址同口径：IGNORE CASE）。 */
    private fun canonicalKey(name: String): String = name.trim().uppercase(java.util.Locale.ROOT)

    companion object {

        /**
         * 装配相位构造点（生产唯一入口）：声明面真源 → 授权表，沿途做坏 grant 校验。
         *
         * [grantsByPackage] = packageId → 该包 manifest `write-grants` 段原文（解码产物）；
         * [defOf] = 键 → 引擎变量定义（生产 = `VariableStore.registry::def`，大小写不敏感），
         * 校验的键存在性/类型权威。校验失败抛 [IllegalArgumentException]（消息由
         * [grantRejectionReason] 逐条点名 packageId 与键、期望 vs 实际），由调用方折进
         * 装载相位的整链拒绝链。
         *
         * public：:app 装配侧（attach）与导入链校验段都要调（跨模块构造点）。
         */
        fun build(
            snapshot: DeclarationSnapshot,
            grantsByPackage: Map<String, List<WriteGrantDeclaration>>,
            defOf: (String) -> VarDef?,
        ): ModDx4WriteGate {
            // ---- 自家 global-dims：声明事务快照按 packageId 归属分组；类型位与寻址形态就地判定 ----
            val ownDims = LinkedHashMap<String, Map<String, OwnDimEntry>>()
            for ((_, declared) in snapshot.globalDims) {
                val entry = OwnDimEntry(
                    isStr = declared.spec.isStr,
                    // 0 维标量与 1 维非角色数组可按名寻址（与 GameStateApi 读侧同口径）；
                    // global-dims 域不含角色变量，2 维以上不可寻址。
                    addressable = declared.spec.dims.size <= 1,
                )
                val key = declared.spec.name.trim().uppercase(java.util.Locale.ROOT)
                ownDims[declared.packageId] =
                    (ownDims[declared.packageId] ?: emptyMap()) + (key to entry)
            }
            // ---- write-grants：逐条装载期校验，坏 grant 整链拒（fail-closed，不静默降级）----
            val grants = LinkedHashMap<String, Map<String, Boolean>>()
            for ((packageId, packageGrants) in grantsByPackage) {
                if (packageGrants.isEmpty()) continue
                val table = LinkedHashMap<String, Boolean>()
                for (grant in packageGrants) {
                    table[grant.canonicalName] = validatedGrantIsStr(packageId, grant, defOf)
                }
                grants[packageId] = table
            }
            return ModDx4WriteGate(grants, ownDims)
        }

        /**
         * 单条 grant 的装载期校验（键存在性 / 类型 / 角色 / 寻址形态 / 常量，逐类点名原因）。
         * 合法返回授权类型位；非法抛 [IllegalArgumentException]（消息点名 packageId 与键、
         * 期望 vs 实际 —— 调用方不吞、不降级，折进装载相位的整链拒绝链）。
         *
         * 判据本体在 [grantRejections]（懒链逐条求值、首个命中即拒绝原因，单一出口构成
         * detekt ThrowsCount/ReturnCount 合规形）；本函数只负责「有原因就抛」这一收口。
         */
        private fun validatedGrantIsStr(
            packageId: String,
            grant: WriteGrantDeclaration,
            defOf: (String) -> VarDef?,
        ): Boolean {
            val reason = grantRejections(packageId, grant, defOf)
            if (reason != null) throw IllegalArgumentException(reason)
            // 校验通过 ⇒ 引擎类型（INT/STR）与授权类型位一致，授权位即引擎类型位。
            return grant.isStr
        }

        /**
         * 逐条判据的懒链：每条是惰性闭包，首个非 null 文案即拒绝原因（前面的闭包命中后
         * 后面的不再求值）；全部放行返回 null。判据清单与 app 导入链校验段同清单
         * （两侧对齐，改一处须同步另一处）。
         */
        private fun grantRejections(
            packageId: String,
            grant: WriteGrantDeclaration,
            defOf: (String) -> VarDef?,
        ): String? {
            val def = defOf(grant.name)
            val checks = listOf<() -> String?>(
                {
                    if (def == null) {
                        "write-grants 键不存在：$packageId/${grant.name}" +
                            "（引擎变量表里没有这个名字；坏 grant 装载期整链拒）"
                    } else {
                        null
                    }
                },
                {
                    if (checkNotNull(def).isCharacterData) {
                        "write-grants 键是角色变量：$packageId/${grant.name}" +
                            "（角色变量写不在写面 v1；坏 grant 装载期整链拒）"
                    } else {
                        null
                    }
                },
                {
                    val argCount = checkNotNull(def).argCount
                    if (argCount > 1) {
                        "write-grants 键不可按名寻址：$packageId/${grant.name}" +
                            "（$argCount 个下标的变量，本写面只达 0 维标量与 1 维非角色数组；" +
                            "坏 grant 装载期整链拒）"
                    } else {
                        null
                    }
                },
                {
                    if (checkNotNull(def).isConst) {
                        "write-grants 键是常量：$packageId/${grant.name}" +
                            "（CSV/GAMEBASE 常量与 #DIM CONST 不可写；坏 grant 装载期整链拒）"
                    } else {
                        null
                    }
                },
                {
                    if (checkNotNull(def).type == VarType.FLOAT) {
                        "write-grants 键类型不符：$packageId/${grant.name}" +
                            "（引擎定义是 FLOAT，本写面只有 INT/STR 两通道；坏 grant 装载期整链拒）"
                    } else {
                        null
                    }
                },
                {
                    if ((checkNotNull(def).type == VarType.STR) != grant.isStr) {
                        "write-grants 键类型不符：$packageId/${grant.name}" +
                            "（引擎定义是 ${checkNotNull(def).type}，授权写的是 " +
                            "${if (grant.isStr) "STR" else "INT"}；不隐式转换，坏 grant 装载期整链拒）"
                    } else {
                        null
                    }
                },
            )
            return checks.firstNotNullOfOrNull { check -> check() }
        }
    }
}

/**
 * 会话级写落库面：授权闸 + **转移边界兑现**的两个 apply 闭包（生产 = `VariableStore` 的
 * `writeInt` / `writeStr` 按名寻址形态）。
 *
 * 宿主在装配相位构造一份，[ModRuntimeHost.gameStateWriteApplier] 登记后由 install 在
 * onLoad 之前按包扇出（[writeFor] 绑包身份 + 该 context 的 [RuntimeScheduler]）。
 *
 * public 而非 internal：:app 装配侧要构造它并登记进宿主（跨模块注入缝，与
 * `ModResourceStore` 同口径）；[writeFor] 保持 internal（扇出只在本模块 install 内发生）。
 */
class ModDx4WriteApplier(
    val gate: ModDx4WriteGate,
    /** 整型写落库（转移边界兑现点执行；生产 = `VariableStore.writeInt` 按名寻址形态）。 */
    val applyInt: (String, Long) -> Unit,
    /** 字符串写落库（同上）。 */
    val applyStr: (String, String) -> Unit,
) {
    /** 给一个 mod 包发它的写 facade（绑定包身份与该 context 的调度器；onLoad 之前调用）。 */
    internal fun writeFor(packageId: String, scheduler: RuntimeScheduler): ModDx4GameStateWrite =
        ModDx4GameStateWrite(packageId, gate, scheduler, applyInt, applyStr)
}

/**
 * 一个 mod 包的受控写 facade（[com.eratw.port.modapi.GameStateApi] 两个 setter 的生产供给）。
 *
 * 语义链固定两步：① [ModDx4WriteGate.authorize] 在**调用点当场判**（fail-fast：异常栈指到
 * 作者代码；排进队列再判会让失败被边界的单动作隔离吞成日志行，违背「未声明键写抛 IAE」契约）；
 * ② 通过后经该 context 的 [RuntimeScheduler.submit] **排队**到转移边界 —— API 层禁止即时写
 * （§18.5：改游戏状态一律经主执行调度器，兑现点 = 页边界 drain；提交先兑现、再推进节拍）。
 */
internal class ModDx4GameStateWrite(
    private val packageId: String,
    private val gate: ModDx4WriteGate,
    private val scheduler: RuntimeScheduler,
    private val applyInt: (String, Long) -> Unit,
    private val applyStr: (String, String) -> Unit,
) {

    /** 整型写：授权闸（当场）→ 排队（转移边界兑现）。 */
    fun enqueueInt(name: String, value: Long) {
        gate.authorize(packageId, name, wantStr = false)
        scheduler.submit { applyInt(name, value) }
    }

    /** 字符串写：授权闸（当场）→ 排队（转移边界兑现）。 */
    fun enqueueStr(name: String, value: String) {
        gate.authorize(packageId, name, wantStr = true)
        scheduler.submit { applyStr(name, value) }
    }
}

/**
 * 给一个已装配 context 注入受控写面（ModDX4-B1；`GameStateApi.setIntVar` / `setStrVar` 的
 * 生产供给接线）。
 *
 * 文件级 extension 而非 [ModContextImpl] 成员函数：该类成员函数数已抵 detekt `TooManyFunctions`
 * 阈值（与本文件其余 adopt/登记缝同口径）。internal：装配细节，不进 mod-api 公开 ABI；
 * 只许宿主在 `onLoad` 之前调用（作者在 onLoad 里就可能提交写）。未调用（旧装配路径 / 台架
 * 直连构造）时 [com.eratw.port.modapi.GameStateApi] 的两个 setter 走缺省 fail-closed UOE。
 */
internal fun ModContextImpl.adoptGameStateWrite(write: ModDx4GameStateWrite) {
    gameStateWrite = write
}

/**
 * onLoad 之前的逐 context 供给就位（install 内的提取段，ModDX4-B1 顺带把受控写面收进同一缝）：
 * 随机派生种子（§21.6）→ 耗时账本（§18.5）→ 单次耗时预算（§18.5）→ 设置快照（RT2 per-mod）
 * → 受控写面（ModDX4-B1）。全部都是「作者在 onLoad/onEnable 里就可能消费」的面，任何一项
 * 缺席都会漏记或拒接；段内顺序无关，共同约束只有一条：先于 onLoad。
 *
 * 落在本文件而非宿主类内：宿主类内函数数与 install 的长度都已到 detekt 阈值上沿（收拢这段
 * 正是为给受控写面腾出合规空间）；与 [adoptGameStateWrite] 同属「宿主 → context 的 onLoad
 * 前注入缝」，同处一文件便于对照时序。
 */
internal fun adoptPreLoadFaces(
    host: ModRuntimeHost,
    context: ModContextImpl,
    packageId: String,
    randomSeed: Long,
) {
    // §21.6：确定性随机面 = 决策种子 + packageId 的**独立派生流**（不碰引擎那条
    // 被黄金基准校准过的 SFMT19937）。读档采纳档内种子后由 [ModRuntimeHost.adoptRandomSeed]
    // 换种子重放。
    context.adoptRandomSeed(randomSeed)
    // §18.5 扩展：耗时账本与单次耗时预算同样必须在 onLoad 之前就位，否则生命周期的
    // 第一段就漏记（账本）/ 第一次调用就漏计（预算扇出到共享 hook 引擎与交互管线）。
    host.timing?.let { context.adoptTimingLedger(it) }
    host.handlerBudget?.let { context.adoptHandlerBudget(it) }
    adoptPerModState(host, context, packageId)
    adoptGameStateWriteIfBound(host, context, packageId)
}

/**
 * install 的写面扇出入口：宿主登记了 [ModRuntimeHost.gameStateWriteApplier] 时，给该 context
 * 发它自己的写 facade（绑包身份 + 该 context 的调度器，onLoad 之前）。
 *
 * 未登记（旧装配路径 / 台架）时不做任何事 —— 写 setter 走缺省 fail-closed UOE，不静默吞写。
 * 文件级而非宿主成员函数：宿主类函数数已抵 detekt `TooManyFunctions` 阈值（同 [notifyEntries]
 * 等既有文件级挪出口径），install 的函数长度也已在上沿。
 */
internal fun adoptGameStateWriteIfBound(host: ModRuntimeHost, context: ModContextImpl, packageId: String) {
    host.gameStateWriteApplier?.let { applier ->
        context.adoptGameStateWrite(applier.writeFor(packageId, context.runtime))
    }
}
