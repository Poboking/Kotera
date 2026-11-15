package com.eratw.port.core.config

/**
 * 平台出厂缺省覆盖（CONFIG-OVERLAY，2026-11-09）：把「桌面口径的引擎缺省」在**壳层**改写成
 * 「本平台口径的出厂缺省」，同时保证**玩家显式写过的键不受影响**。
 *
 * ## 为什么需要这层
 *
 * `ConfigSpec` 的缺省值是上游 Emuera 的**桌面**标定（例：`InfiniteLoopAlertTime` = 5000 ms，
 * `ConfigData.cs:75`；`ConfigSpec.kt` 的同一行）。安卓设备的解释器吞吐本来就低于桌面
 * （ADR-010 已判定不重写解释器 ⇒ 这是结构性事实，不是可修的缺陷），于是同一条合法长链在
 * 手机上会反复越过那个**墙钟**阈值、把玩家推进「是否中止」弹框。这类「桌面标定的时间量」
 * 需要按平台重新标定。
 *
 * ## 分层
 *
 * - **本文件是机制**：只负责「把给定表逐项 assign 进 [ConfigStore]」，不做任何判断，
 *   不知道哪个键该被覆盖、也不读文件。
 * - **策略在壳层**：谁传这张表谁负责举证（见 `app/...` 的平台出厂缺省对象）。
 *   壳层必须自己做「玩家有没有显式写过这个键」的判定 —— 机制层不猜。
 * - **装置/golden 不传** ⇒ 逐字节零行为变化（`GameSession(configOverlay = emptyMap())` 缺省）。
 *
 * ## 落点为什么在 `GameSession` 的三层链之后
 *
 * 原版配置读取顺序是 `_default.config` → `emuera.config` → `_fixed.config`（`ConfigData.cs:479-494`），
 * 再叠 `_Replace.csv`（`Process.cs:240-270`）。平台缺省必须落在这三层**之后**、`EffectiveConfig.of`
 * **之前**：落前面会被文件值盖掉（原版方向），落后面则 `VmConfig.infiniteLoopAlertTimeMs`
 * 这类「装配期读一次」的字段已经读完了（`GameSession.kt` 的 VmConfig 构造）。
 *
 * ## 被 `fixed.config` 钉住的条目
 *
 * `ConfigItem.assign` 对 fixed 条目返回 false（`ConfigItem.kt` 的首句守卫，原版口径）。
 * 本函数**不强行解钉**，只把实际生效的键如实返回，供调用方记录读数。
 */
object ConfigOverlay {

    /**
     * 把 [overlay] 逐项写进 [store]，返回**真正写进去**的键（升序，便于日志与断言逐字比对）。
     *
     * 写不进去只有一种原因：该条目被 `fixed.config` 钉住（[ConfigItem.assign] 返回 false）。
     * 未知键不会走到这里 —— [overlay] 的类型是 [ConfigKey] 枚举，条目表里必然存在。
     */
    fun applyTo(store: ConfigStore, overlay: Map<ConfigKey, ConfigValue>): List<ConfigKey> {
        if (overlay.isEmpty()) return emptyList()
        val written = ArrayList<ConfigKey>(overlay.size)
        for (key in overlay.keys.sortedBy { it.ordinal }) {
            if (store.itemOf(key).assign(overlay.getValue(key))) written += key
        }
        return written
    }
}
