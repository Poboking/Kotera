package com.eratw.port.modtesting

import com.eratw.port.core.mod.ModSettingsSnapshot
import com.eratw.port.core.mod.apiFor
import com.eratw.port.core.patch.SettingDeclaration
import com.eratw.port.modapi.SettingsApi

/**
 * 设置面（测试替身）：**读侧直接复用生产逐包实现**，台架不另写一套语义。
 *
 * [declarations] 来自 manifest 的 `settings` 段（[TestWorld] 经 `ModDeclarationAdapter` 翻译，
 * 与生产装载同一落点）；非空时构造生产 [ModSettingsSnapshot] 并走其公开工厂
 * [ModSettingsSnapshot.apiFor]，于是下面三条与真机逐字一致：
 * - 类型不符（如对 STR 声明调 `int()`）抛同一条 `IllegalArgumentException`（含「类型不符」文案）；
 * - 坏值（INT 越界、ENUM 不在候选、BOOL 非 `true|false`、STR 超长/含换行）静默回落**声明默认值**；
 * - 声明了但未落值 ⇒ 读 `defaultText()`；未声明的键 ⇒ 生产的「设置项未声明」拒绝（不可读）。
 *
 * 台架便利面（生产 v1 有意没有写面，故保留在这里）：
 * - [declare] / [change] 往持久化值表写文本，**写入不校验**（作者要验「坏值回落」必须能塞坏值）；
 * - manifest 没有 `settings` 段时（只测别的面的用例）读侧退回「键 → 文本」表的老便利语义，
 *   未命中的拒绝文案保持台架原文 —— 这条路径在生产上没有对应面，属台架专属造数据能力。
 */
class RecordingSettings(
    override val packageId: String,
    initial: Map<String, String> = emptyMap(),
    declarations: List<SettingDeclaration> = emptyList(),
) : SettingsApi {

    private val values = LinkedHashMap<String, String>(initial)

    /**
     * 生产逐包设置面（声明表非空时）：快照持有 [values] 引用，故 [declare]/[change] 写入后立即可读。
     * 声明表为空（manifest 未声明设置）时保持 `null`，读侧走便利路径。
     */
    private val declared: SettingsApi? =
        if (declarations.isEmpty()) null else ModSettingsSnapshot(packageId, declarations, values).apiFor()

    override val keys: Set<String> get() = declared?.keys ?: values.keys.toSet()

    override fun has(key: String): Boolean = declared?.has(key) ?: values.containsKey(key)

    override fun int(key: String): Long {
        declared?.let { return it.int(key) }
        val text = convenience(key)
        return text.toLongOrNull()
            ?: throw IllegalArgumentException("设置项 $packageId.$key 不是整数原文：$text")
    }

    override fun str(key: String): String = declared?.str(key) ?: convenience(key)

    override fun bool(key: String): Boolean {
        declared?.let { return it.bool(key) }
        val text = convenience(key)
        return text.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("设置项 $packageId.$key 不是布尔原文：$text")
    }

    override fun enumName(key: String): String = declared?.enumName(key) ?: convenience(key)

    /** 用例侧声明一个键值（测试替身专用；不写盘、不进存档；对已声明键 = 塞持久化值）。 */
    fun declare(key: String, value: String) {
        values[key] = value
    }

    /**
     * 用例侧模拟「玩家在设置页写入」：把 [key] 落成新值（对 mod 而言 = 已持久化）并返回键名。
     *
     * 返回键名是刻意设计：作者按写入序把各次返回值拼成 `changedKeys` 列表传给
     * [TestModSession.settingsChanged]，写入序与回调键序天然一致，不会两边各抄一份漂移。
     * 与 [declare] 的分工：`declare` 是「替身造数据」，[change] 是「模拟玩家写入后触达 mod」
     * 的那半步（写完必须自己走一次 `settingsChanged` 才有回调，替身不会自动派发）。
     */
    fun change(key: String, value: String): String {
        values[key] = value
        return key
    }

    /** 清空持久化值（多次用例复用同一 world 时复位）；manifest 派生的声明清单保留。 */
    fun clear() {
        values.clear()
    }

    /** 无声明表时的老便利路径：只认 [values] 里写过的键，未命中的拒绝文案保持台架原文。 */
    private fun convenience(key: String): String = values[key]
        ?: throw IllegalArgumentException(
            "设置项未声明：$packageId.$key（测试替身只认 [RecordingSettings] 里声明过的键）",
        )
}
