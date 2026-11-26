package com.eratw.port.core.mod

import com.eratw.port.core.patch.BoolSettingDeclaration
import com.eratw.port.core.patch.EnumSettingDeclaration
import com.eratw.port.core.patch.IntSettingDeclaration
import com.eratw.port.core.patch.SettingDeclaration
import com.eratw.port.core.patch.StrSettingDeclaration
import com.eratw.port.modapi.SettingsApi

/**
 * 一个 mod 包的设置快照：**声明清单**（Manifest `settings` 段的翻译结果）加
 * **已持久化的值文本**（设备侧按包配置文件读出来的原始 `key → value`）。
 *
 * 宿主在 `install` 之前构造本对象（app 侧 `ModSettingsStore` 读文件 + 解 manifest 声明），
 * 经 `ModRuntimeHost.settings` 注入；core 侧只认这个不可变快照，**不读文件、不碰 Android
 * API**（铁律：非主线程不直接读 SharedPreferences / 不做文件 IO）。
 *
 * 值解析口径（唯一一份，反序列化与读取共用）：
 * - 持久化文本对声明类型合法（INT 可解析且在 [IntSettingDeclaration.min] / `max` 内、
 *   BOOL 是 `true` / `false`、ENUM 在候选里、STR 无换行且不超长）⇒ 用它；
 * - 缺键 / 坏值 / 越界 / 类型对不上 ⇒ 回退 [SettingDeclaration.defaultText]。
 *   损坏永不抛、永不崩，只静默回退到默认值（这是配置面，不是存档数据面）。
 */
class ModSettingsSnapshot(
    val packageId: String,
    val declarations: List<SettingDeclaration>,
    val persistedValues: Map<String, String> = emptyMap(),
) {
    init {
        require(packageId.isNotBlank()) { "设置快照的 packageId 不能为空" }
        require(declarations.map { it.key }.toSet().size == declarations.size) {
            "设置快照的声明键重复：${declarations.map { it.key }}"
        }
    }

    /** 声明序（= 设置页渲染序）。 */
    private val ordered: List<SettingDeclaration> = declarations

    /** 键 → 声明（`has` / 类型判定用）。 */
    private val byKey: Map<String, SettingDeclaration> = declarations.associateBy { it.key }

    /** 本包声明的全部键（声明序）。 */
    val declaredKeys: List<String> get() = ordered.map { it.key }

    /** [key] 是否声明过。 */
    fun declares(key: String): Boolean = key in byKey

    /** 取声明；未声明即抛（fail-closed，不返回 null 让调用方猜）。 */
    fun declarationOf(key: String): SettingDeclaration =
        byKey[key] ?: throw IllegalArgumentException("设置项未声明：$packageId/$key（未声明的键不可读）")

    /**
     * 解析出 [key] 的**生效文本**：持久化值合法则返回它，否则回退默认值。
     *
     * 未声明仍抛 [IllegalArgumentException]（未声明与「坏值」是两种不同失败：后者回退，
     * 前者是作者/玩家面不一致，必须上抛）。
     */
    fun resolveText(key: String): String {
        val decl = declarationOf(key)
        val raw = persistedValues[key]
        return if (raw != null && isValidText(decl, raw)) raw else decl.defaultText()
    }

    /** 类型化读取的公共入口：声明类型必须匹配，否则抛（不隐式转）。 */
    fun <T : SettingDeclaration> declarationAs(key: String, expected: Class<T>): T {
        val decl = declarationOf(key)
        if (!expected.isInstance(decl)) {
            throw IllegalArgumentException(
                "设置项类型不符：$packageId/$key 声明为 ${decl.type}，" +
                    "读取入口只接受与声明类型匹配的 getter（不隐式转换）",
            )
        }
        return expected.cast(decl)
    }

    /** 单个持久化值是否对声明类型合法（唯一解析口径，见类 KDoc）。 */
    private fun isValidText(decl: SettingDeclaration, raw: String): Boolean = when (decl) {
        is IntSettingDeclaration -> {
            val parsed = raw.toLongOrNull()
            parsed != null &&
                (decl.min == null || parsed >= decl.min) &&
                (decl.max == null || parsed <= decl.max)
        }
        is StrSettingDeclaration ->
            raw.length <= SettingDeclaration.MAX_TEXT_LENGTH && '\n' !in raw && '\r' !in raw
        is BoolSettingDeclaration -> raw == "true" || raw == "false"
        is EnumSettingDeclaration -> raw in decl.candidates
    }
}

/**
 * 注入快照后的只读访问面实现（[SettingsApi] 的宿主装配形态；不进 mod-api 公开 ABI）。
 *
 * 与 [FailClosedSettingsApi] 成对：未注入时用后者明示拒绝，注入后本类只做「声明校验 +
 * 类型化读取 + 默认回退」，没有第二份值缓存（值真源永远在 [ModSettingsSnapshot]）。
 */
internal class PackageSettingsApi(private val snapshot: ModSettingsSnapshot) : SettingsApi {

    override val packageId: String get() = snapshot.packageId

    override val keys: Set<String> get() = LinkedHashSet(snapshot.declaredKeys)

    override fun has(key: String): Boolean = snapshot.declares(key)

    override fun int(key: String): Long {
        snapshot.declarationAs(key, IntSettingDeclaration::class.java)
        return snapshot.resolveText(key).toLong()
    }

    override fun str(key: String): String {
        snapshot.declarationAs(key, StrSettingDeclaration::class.java)
        return snapshot.resolveText(key)
    }

    override fun bool(key: String): Boolean {
        snapshot.declarationAs(key, BoolSettingDeclaration::class.java)
        return snapshot.resolveText(key) == "true"
    }

    override fun enumName(key: String): String {
        snapshot.declarationAs(key, EnumSettingDeclaration::class.java)
        return snapshot.resolveText(key)
    }
}

/**
 * 公开的逐包设置面构造入口（生产 `ModContextImpl.settings` 装配用的同一实现）。
 *
 * 宿主与台架需要的只是「快照 → 作者面」这一个构造点：类型协商
 * （[ModSettingsSnapshot.declarationAs]）、坏值/缺值回落声明默认值
 * （[ModSettingsSnapshot.resolveText]）、未声明键拒绝全在快照里，这里不含第二套解析语义。
 */
fun ModSettingsSnapshot.apiFor(): SettingsApi = PackageSettingsApi(this)

/**
 * 未注入设置快照时的显式 fail-closed 桩（照 `FailClosedRandomApi` / `FailClosedTimingApi` 口径）。
 *
 * **不返回声明默认值**：宿主没注入 = 玩家改的值根本没被读到，静默默认值只会让作者以为
 * 设置生效了（本面存在的意义就是消灭这种失真）。
 */
internal class FailClosedSettingsApi(private val pkgId: String) : SettingsApi {

    override val packageId: String get() = pkgId

    override val keys: Set<String>
        get() = fail("settings.keys")

    override fun has(key: String): Boolean = fail("settings.has")

    override fun int(key: String): Long = fail("settings.int")

    override fun str(key: String): String = fail("settings.str")

    override fun bool(key: String): Boolean = fail("settings.bool")

    override fun enumName(key: String): String = fail("settings.enumName")

    private fun fail(call: String): Nothing {
        throw UnsupportedOperationException(
            "设置面未装配：$packageId（宿主未在 onLoad 前注入按包设置快照）；" +
                "$call 不提供静默默认值兜底",
        )
    }
}
