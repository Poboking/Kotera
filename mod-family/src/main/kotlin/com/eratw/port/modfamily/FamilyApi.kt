package com.eratw.port.modfamily

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.get
import com.eratw.port.modapi.put
import com.eratw.port.modapi.remove

/**
 * 家族载体的**作者面**（ADR-015 S3：手动指定继任者）。
 *
 * 接线前 `family.successionMode = manual` 只能靠调用方自己算好 `incumbent` 再传给
 * [FamilyMod.chooseSuccessor]：玩家/作者既看不到「有哪些合法候选」，也没有地方把「我要谁」
 * 持久下来（设置通道只读、候选在实体段）。本面补这两件事：
 *
 * - [candidates]：给出一份**稳定序**的合法候选名单（在册、实体存在、未退场）；
 * - [chooseSuccessor] / [pendingSuccessor] / [clearSuccessor]：指定、读回、取消「下一任家督」。
 *
 * ## 与既有 eldest/youngest 的关系
 *
 * 本面**不改变任何既有语义**：[FamilyMod.advanceOneDay] 末尾的继任清扫在 eldest/youngest
 * 模式下仍然按年龄自动选人；只有 manual 模式下、且本面显式指定过人选时，日拍才按该人选结算
 * （见 [com.eratw.port.modfamily.succeedPendingManualHeir]）。未指定时 manual 保持既有的
 * 「整拍零写入」行为。
 *
 * ## 持久口径
 *
 * 选择写进 mod 自己的存储段 [FamilyDeclarations.MANUAL_HEIR]（十进制 [EntityId.raw]），随存档
 * 走、按包隔离、读档后自动读回 ⇒ 不需要改 manifest 资产，也不依赖宿主设置页。
 *
 * ## 线程
 *
 * 与其余 mod 面一致，只在 VM 线程调用；无状态（每次取新建），不缓存任何选择。
 *
 * @param mod 所属载体实例（由 [FamilyMod.api] 提供，未 `onLoad` 前调用会抛
 *   `UninitializedPropertyAccessException`，与载体其余方法一致）。
 */
class FamilyApi internal constructor(private val mod: FamilyMod) {

    /**
     * 合法继任候选：在册子嗣里**实体仍存在**且**未退场**（`state != RETIRED`）的成员。
     *
     * 稳定序 = 按 [EntityId.raw] 升序。刻意不用名册插入序/Map 迭代序：`children` 的真源是
     * [FamilyDeclarations.CHILD_IDS]（登记/摘除会改写它，读档时重建），任何依赖插入序的 UI
     * 选择面都会在「读档后、死亡摘除后」跳位；按 raw 排序与名册写入历史无关，同世界重复调用
     * 结果逐位一致。
     *
     * 已退场者不出现（与听 [FamilyMod.chooseSuccessor] 的候选池同口径），悬空 id 也不出现。
     */
    fun candidates(): List<EntityId> {
        val view = mod.successionView
        return view.children
            .filter { view.entities.exists(it) }
            .filter { view.entities.stateOf(it) != EntityState.RETIRED }
            .distinct()
            .sortedBy { it.raw }
    }

    /**
     * 只读显示名投影：给定 [ids]，返回其中**确有可用显示名**的 `EntityId → 名字`。
     *
     * ## 名字来源 = 既有 `title`，不是新字段
     *
     * family 子嗣实体只有 `age` / `title` / `father` 三字段，没有独立姓名列；[FamilyMod.adoptChild]
     * 用 `title` 写入人物显示串，继任时新家督继承死者 `title`，所以它是本载体里已存在的名字来源。
     * 角色变量里的 NAME / CALLNAME 属于 0 维族，mod 路由层按设计拒读（见 `ModKeys` 的
     * CharaVar KDoc：v1 只支持 CSV 补丁），因此本面**不依赖 core-vm 语义**，也不新增存储键、
     * 不改 manifest 资产 —— 纯投影，调一次读一次，不缓存。
     *
     * ## 归一化（只影响返回值，绝不写回）
     *
     * - 去首尾空白、内部连续空白（含制表/换行/全角空格）折成一个半角空格；
     * - 归一后为空（`showTitles` 关着时 [FamilyMod.adoptChild] 写空串）⇒ 该 id **不进映射**，
     *   调用方按约定回落 `#raw`；
     * - 超过 [DISPLAY_NAME_MAX_CHARS] 个 UTF-16 字符则截断并补省略号（候选行是单行 UI，
     *   超长标题会撑破列表）；裁剪点避开代理对，不劈开星位/绘文字。
     *
     * 不存在的实体与 [EntityId.NULL] 直接跳过（与 [candidates] 的「悬空 id 不出现」同口径）。
     * 返回 [LinkedHashMap]：迭代序 = [ids] 传入序，方便调用方逐条对齐。
     */
    fun displayNames(ids: Collection<EntityId>): Map<EntityId, String> {
        if (ids.isEmpty()) return emptyMap()
        val view = mod.successionView
        val names = LinkedHashMap<EntityId, String>()
        for (id in ids) {
            val name = displayNameOf(view, id)
            if (name != null) names[id] = name
        }
        return names
    }

    /**
     * 单个实体的显示名：不存在 / [EntityId.NULL] ⇒ `null`；否则取既有 `title` 并归一化。
     * 与 [candidates] 的「悬空 id 不出现」同口径，只是返回 null 而不是过滤列表。
     */
    private fun displayNameOf(view: SuccessionView, id: EntityId): String? {
        if (id == EntityId.NULL || !view.entities.exists(id)) return null
        return normalizeDisplayName(view.family.of(id).title)
    }

    /**
     * 显式指定「下一任家督」。
     *
     * 三条拒绝（前两条就地返回 `false` 且**不写盘**；第三条不吞失败，由设置面读值那步自己抛）：
     * 1. [successor] 不在在册名单里 / 实体已不存在 / 已退场 —— 非法候选；
     * 2. 当前 `family.successionMode` 不是 [FamilyDeclarations.SUCCESSION_MANUAL] —— 手动继任是
     *    manual 模式的专用语义，eldest/youngest 下指选人不会生效，静默存下来只会变成幽灵状态；
     * 3. 设置面未装配时由 [com.eratw.port.modapi.SettingsApi] 自己抛（不吞失败）。
     *
     * **幂等**：已存值就是要指定的人 ⇒ 返回 `true` 且不重写存储（重复点选不产生写盘）。
     *
     * @return `true` = 该指定已生效（写入成功或已是当前选择）；`false` = 被拒绝，未发生任何写入。
     */
    fun chooseSuccessor(successor: EntityId): Boolean {
        val view = mod.successionView
        val accepted = selectableHeir(view, successor) &&
            view.settings.enumName(FamilyDeclarations.SUCCESSION_MODE_KEY) ==
            FamilyDeclarations.SUCCESSION_MANUAL
        if (accepted && pendingManualSuccessor(view) != successor) {
            view.storage.put(FamilyDeclarations.MANUAL_HEIR, successor.raw.toString())
        }
        return accepted
    }

    /** 候选口径唯一一份：[FamilyApi.candidates] 与 [chooseSuccessor] 的接受面共用。 */
    private fun selectableHeir(view: SuccessionView, successor: EntityId): Boolean =
        view.children.any { it == successor } &&
            view.entities.exists(successor) &&
            view.entities.stateOf(successor) != EntityState.RETIRED

    /**
     * 读回当前指定的「下一任家督」；未指定 / 已结算 / 存储里是脏值时返回 `null`（脏值不抛，
     * 与 [FamilyMod] 读名册同口径：读档不该被一条坏数据弄崩）。
     */
    fun pendingSuccessor(): EntityId? = pendingManualSuccessor(mod.successionView)

    /**
     * 取消当前指定（玩家改主意）。
     *
     * @return `true` = 本次真的删掉了键；`false` = 本来就没有指定（零写入）。
     */
    fun clearSuccessor(): Boolean = clearPendingManualSuccessor(mod.successionView)
}

/** 显示名上限（UTF-16 字符）：候选行单行渲染，超长标题会撑破列表。 */
private const val DISPLAY_NAME_MAX_CHARS: Int = 24

/** 内部空白折叠口径：制表 / 换行 / 半角空格 / 全角空格（U+3000）/ 不换行空格都并成一个半角空格。 */
private val DISPLAY_NAME_WHITESPACE = Regex("[\\s\\u3000\\u00A0]+")

/**
 * [FamilyApi.displayNames] 的归一化唯一口径：去首尾空白 → 折叠内部空白 → 空串返回 `null` →
 * 超长截断到 [DISPLAY_NAME_MAX_CHARS] 并补省略号。
 *
 * 首尾剥离走 Kotlin `trim`：其谓词是 `isWhitespace() || isSpaceChar()`，覆盖比
 * [DISPLAY_NAME_WHITESPACE] 更宽（全角空格 / 不换行空格在首尾同样剥掉，不止 ASCII——哨兵用例
 * displayNamesTrimFullWidthAndNbspWhitespaceAtEdges 钉住），故「去首尾」与「折叠内部」谁先谁后同效。
 *
 * 只读：结果不回写实体，`title` 字段保持作者写入的原样（归一化是展示层投影，不是数据迁移）。
 * 裁剪点若落在代理对的高位字符上就再退一格，避免把一个星位/绘文字劈成两半。
 */
internal fun normalizeDisplayName(raw: String): String? {
    val collapsed = raw.trim().replace(DISPLAY_NAME_WHITESPACE, " ")
    if (collapsed.isEmpty()) return null
    return truncateDisplayName(collapsed)
}

/** 超长截断：留一格给省略号；裁剪点若落在代理对的高位字符上就再退一格，避免劈开星位/绘文字。 */
private fun truncateDisplayName(name: String): String {
    if (name.length <= DISPLAY_NAME_MAX_CHARS) return name
    val kept = name.take(DISPLAY_NAME_MAX_CHARS - 1)
    val safe = if (kept.lastOrNull()?.isHighSurrogate() == true) kept.dropLast(1) else kept
    return safe + "…"
}

/**
 * 读 [FamilyDeclarations.MANUAL_HEIR] 并解析成 [EntityId]；缺键 / 空串 / 非十进制原文 ⇒ `null`。
 *
 * 文件级而不是 [FamilyApi] 成员：日拍清扫（[succeedPendingManualHeir]）与设置面都要读同一个键，
 * 解析口径只此一份（[FamilyMod] 的成员面已贴近 detekt 闸，故落在文件级）。
 */
internal fun pendingManualSuccessor(view: SuccessionView): EntityId? =
    view.storage.get(FamilyDeclarations.MANUAL_HEIR)?.trim()?.toLongOrNull()?.let(::EntityId)

/** 清掉显式指定（[FamilyApi.clearSuccessor] 与结算成功后的收口共用）。 */
internal fun clearPendingManualSuccessor(view: SuccessionView): Boolean =
    view.storage.remove(FamilyDeclarations.MANUAL_HEIR)

/**
 * manual 模式的日拍结算：**只有**存在显式指定、且该人确实是某位死者（[settleable] 里的家督位）
 * 的直系在册候选时，才按既有写回口径结算一支家族并清掉指定；否则整拍零写入。
 *
 * 为什么不是「一死就自动按指定人继任任何家族」：显式指定的是**一个人**，不是「所有家督位的
 * 通用继承者」；候选关系（`father` 指向死者）是唯一可信的归属判据，[lineageCandidates] 就是
 * 既有选择面用的同一份过滤，不另写第二条。
 *
 * 结算失败/该主人选已退场时**保留指定、不写盘**：让玩家/作者看到「指定还在但没生效」，
 * 比静默丢弃更可诊断（[FamilyApi] 的 KDoc 已写明脏值不抛）。
 */
internal fun succeedPendingManualHeir(
    mod: FamilyMod,
    view: SuccessionView,
    settleable: List<EntityId>,
) {
    val chosen = pendingManualSuccessor(view) ?: return
    for (head in settleable) {
        val isCandidate = lineageCandidates(view.children, head, view.entities, view.family)
            .any { it.id == chosen.raw.toString() }
        if (!isCandidate) continue
        val showTitles = view.settings.bool(FamilyDeclarations.SHOW_TITLES_KEY)
        view.settleSuccession(mod, FamilyDeclarations.SUCCESSION_MANUAL, showTitles, head, chosen)
        clearPendingManualSuccessor(view)
        return
    }
}
