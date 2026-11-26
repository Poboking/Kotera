package com.eratw.port.core.mod

import com.eratw.port.core.vars.RandProvider

/**
 * 词库条目：`{text, weight}`。
 *
 * 校验挂构造（fail-closed 单点）：weight ≥ 1（0/负权重拒）、text 非空白——[LexiconRegistry.define]
 * 无法绕过这两个闸，不存在「构造了坏条目但还没被拒」的中间态。
 */
data class LexiconEntry(val text: String, val weight: Int) {
    init {
        require(weight >= 1) { "词条 weight 必须 ≥ 1，实参=$weight（0/负权重 fail-closed 拒）" }
        require(text.isNotBlank()) { "词条 text 不能空白（fail-closed）" }
    }
}

/**
 * 词库插排原语（ModDX-4 Lane M3-1，core 机制第一刀）：槽 → 加权池 + 防重复抽取
 * （Anti-Repeat Deck）。
 *
 * 范围裁定（契约 Lane M3-1，2026-11-19 冻结）：本类只落 **core 纯机制**——per-slot 词条池、
 * 加权不放回抽取、抽尽整栈重置。作者面 API、词条数据装载（ADR-014 资源轨）、百分号嵌套
 * 快速解析、Builtins 指令全部留给后续波；本类不依赖 mod-api，也不进任何 Builtins 注册面。
 *
 * **移植侧扩展，无 C# 对应物**（era 原版与 1.824 反编译基线均无 mod 词库池概念；口径判例同
 * GameTickDriver.kt:144）：语义权威 = 本波契约判据 1–5，非反编译锚。
 *
 * 随机源同源裁定（判据 2）：[ModRandomApi]（mod 随机面 §21.6 生产实现）的底层随机缝是
 * `com.eratw.port.core.rand.Sfmt19937`（ModRandomApi.kt:3/:29），本类与之同源——只经
 * [RandProvider.next] 单缝消费注入实例（生产装配传 Sfmt19937 即同源；§21.6 决策种子 →
 * per-mod 派生流的装配归后续波）。禁 kotlin.random（AGENTS 铁律 4）。构造注入 ⇒
 * 同 seed 同序列可测。
 *
 * 抽取语义（判据 3，Anti-Repeat Deck）：
 * - 轮内不放回：同轮 n 条池前 n 抽两两不同。权重只决定轮内各条被抽走的**先后概率**，不改变
 *   「每轮每条恰好被抽一次」——这是防重复抽取，不是禁重复；
 * - 抽尽整栈重置：抽走最后一条的瞬间，牌堆整栈恢复为完整池（含全部权重），下一抽即新轮首抽；
 *   跨轮允许重复。不存在「抽尽后空牌堆」的中间态（deck 空 ⇔ 池空）；
 * - 牌堆顺序 = 条目落表顺序：不放回经加权抽取完成，牌堆顺序只影响累计游标走向，不另行洗牌；
 * - [define] 追加词条即重置该槽牌堆：池变了，旧轮作废、重开新轮。
 *
 * 去重口径裁定（判据 1）：**同槽内 text 全局唯一**，跨 [define] 调用也查重；重复即抛
 * [IllegalArgumentException]（fail-closed，不静默覆盖、不静默跳过——静默覆盖会让两条来源
 * 不同的词条无声丢一条，作者无从察觉）。跨槽同名 text 允许（槽之间天然隔离）。批量
 * [define] 原子：批内任何一条违规，整批不落、牌堆不动。
 *
 * 空集语义（判据 4，与 GameStateApi.hasIntVar 同风格）：未知 slot / 空池不抛——[entries]
 * 返回空表、[pick] 返回 `null`（无从抽取，不消费随机流）。
 *
 * 纯 JVM（判据 5）：无 Android API、无 kotlin.random；全部状态挂实例（pools / items /
 * texts / deck 均为实例字段，无静态可变全局）。非线程安全：与 core 其余可变状态同口径，
 * 引擎只在单 VM 线程写。
 */
class LexiconRegistry(private val rng: RandProvider) {

    private val pools = LinkedHashMap<String, LexPool>()

    /**
     * 向 [slot] 追加词条（批量；空列表 = 空池占位，slot 先注册、词条后补；对已有槽是
     * no-op——词条不动、牌堆不重置）。
     *
     * 校验先整批后落表（原子性：批内任何一条违规，整批不落、新槽不注册、已有槽牌堆不动）；
     * 落表成功即重置该槽牌堆（见类 KDoc 抽取语义）。
     */
    fun define(slot: String, entries: List<LexiconEntry>) {
        require(slot.isNotBlank()) { "词库 slot 不能空白（fail-closed）" }
        if (entries.isEmpty()) {
            pools.getOrPut(slot) { LexPool() } // 空批 = 空池占位
            return
        }
        // 校验先于任何落表：批内任何一条违规，整批拒，不留「空池占位已注册」的半注册态。
        val existingTexts = pools[slot]?.texts ?: emptySet()
        val batchTexts = HashSet<String>(entries.size)
        for (entry in entries) {
            require(batchTexts.add(entry.text)) {
                "词库批内 text 重复：slot=$slot text=${entry.text}（fail-closed，不静默去重）"
            }
            require(entry.text !in existingTexts) {
                "词库同槽 text 重复：slot=$slot text=${entry.text}（fail-closed，不覆盖不跳过）"
            }
        }
        val pool = pools.getOrPut(slot) { LexPool() }
        pool.items.addAll(entries)
        pool.texts.addAll(batchTexts)
        pool.rebuildDeck()
    }

    /**
     * 加权不放回抽取一条 text；未知 slot / 空池返回 `null`（不抛、不消费随机流）。
     *
     * 累计游标口径与 [DecisionPipeline] 的加权仲裁一致（DecisionPipeline.kt:557-579）：
     * draw ∈ [0, totalWeight)，沿落表序累加 weight、首个 `draw < cumulative` 的条目胜出。
     */
    fun pick(slot: String): String? {
        val pool = pools[slot]
        if (pool == null || pool.items.isEmpty()) return null
        return drawWeighted(pool)
    }

    /**
     * 满牌堆单抽：消费一次 [rng.next] 按累计游标取条、摘牌，摘空即整栈重置。
     *
     * 随机源缝越界 / 游标未命中按 fail-closed 抛 [IllegalStateException]——拒静默钳制，
     * 钳制会把随机源违约藏进「看似正常」的抽取序列。
     */
    private fun drawWeighted(pool: LexPool): String {
        val deck = pool.deck
        val totalWeight = deck.sumOf { it.weight.toLong() }
        val draw = rng.next(totalWeight)
        if (draw < 0L || draw >= totalWeight) {
            error("词库随机源越界：draw=$draw 不在 0..${totalWeight - 1}（fail-closed）")
        }
        var cumulative = 0L
        var chosenIndex = -1
        for (index in deck.indices) {
            cumulative += deck[index].weight.toLong()
            if (draw < cumulative) {
                chosenIndex = index
                break
            }
        }
        if (chosenIndex < 0) {
            error("词库抽取未命中任何词条：draw=$draw total=$totalWeight（fail-closed，随机源缝违约）")
        }
        val chosen = deck.removeAt(chosenIndex)
        if (deck.isEmpty()) deck.addAll(pool.items) // 抽尽整栈重置：下一抽即新轮首抽
        return chosen.text
    }

    /** 槽内全部 text（落表顺序快照，调用方可自由持有）；未知 slot 返回空表。 */
    fun entries(slot: String): List<String> =
        pools[slot]?.items?.map { it.text } ?: emptyList()

    /** 单槽词条池：items = 完整池（落表序），texts = 查重集，deck = 未抽走的剩余条目。 */
    private class LexPool {
        val items = mutableListOf<LexiconEntry>()
        val texts = mutableSetOf<String>()
        val deck = mutableListOf<LexiconEntry>()

        /** 牌堆整栈恢复为完整池（抽尽重置 / define 追加共用同一口径）。 */
        fun rebuildDeck() {
            deck.clear()
            deck.addAll(items)
        }
    }
}
