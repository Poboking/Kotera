package com.eratw.port.core.nativeprov

/**
 * 原生 provider 配置链的解析结果（不可变值对象）：四类子系统各自的档位 + debug 开关。
 *
 * 优先级（ADR-010 §3 :220）：**系统性质（调试/CLI） > Gradle 属性/BuildConfig（打包档） >
 * 平台默认**；平台默认 = 全 [NativeProviderMode.OFF] + debug=false
 * （:221「首版全部缺省 off」）。
 *
 * 旋钮键面（ADR-010 §3 :213-217，[Companion.propertyKey] / [Companion.PROPERTY_DEBUG]）：
 * ```
 * eratw.native.image|audio|glyph|kernels = off|auto|force
 * eratw.native.debug = 0|1
 * ```
 * 四类旋钮相互独立、互不牵连（ADR-010 §2 :208）；`debug` 单列，只控计时/日志的投递，
 * 不参与档位语义。**不写进 `emuera.config`**（ADR-010 §3 :221——玩家不应能改语义无关的
 * 执行器选择，且该文件是存档兼容面）。
 *
 * ## 解析形态：纯函数，进程全局只在解析层读
 *
 * [Companion.parse] 吃 `Map`、返回本值对象，是纯函数——单测直接喂 map，不碰进程性质表。
 * 系统性质的唯一读取点在 [Companion.fromSystemProperties]（**解析层**入口）；下游
 * [NativeProviders] 只吃注入的本对象，不再回读进程全局——与
 * `RuntimeSnapshotAutoDump.enabled(raw)`（core/snapshot/RuntimeSnapshot.kt:680-683）
 * 的「性质即旋钮、测试喂值」同口径，满足 core 层禁静态可变全局的铁律。
 *
 * ## 非法档位显式报错
 *
 * 值不在合法域（trim 后；档位大小写不敏感）⇒ 抛 [IllegalArgumentException]，点名键、
 * 原文与合法域，**绝不静默回落 off**。理由：`force` 想用却拼错（如 `yes`）若被吞成 off，
 * A/B 验收拿到的是 Kotlin 路径的假数据（ADR-010 §2 :207 的 force 口径同样容不下静默）。
 * 空串与纯空白也不是合法档位，同样报错——「`-D键=`」表达不了任何一档。
 *
 * 未知键一律忽略（调用方可以传整张系统性质表/Gradle 属性表，解析只点名五个键）；
 * 代价是键拼错（如 `eratw.native.imags`）不会被此处抓到——A/B 验收前应先打印
 * 本对象的 [toString] 核对五键五值，键面拼写闸留给将来的宿主接线波（TODO(verify)，卡点=
 * BuildConfig/Gradle 属性接线属 app 面，契约 §3 明确不做）。
 */
data class NativeProvidersConfig(
    /**
     * 四类子系统各自的档位；键集恒为 [NativeKind.entries] 全集（[Companion.parse] 补默认，
     * 消费方取值免 null）。外部构造时建议走 [Companion.parse]/[Companion.DEFAULT]，
     * 直构造留给测试与将来的装配面。
     */
    val modes: Map<NativeKind, NativeProviderMode>,

    /**
     * 计时/日志开关（`eratw.native.debug`，ADR-010 §3 :217）；true 时 [NativeProviders]
     * 把每次选择（OFF 除外——零日志）的观察记录投给注入的 [NativeProviderDebugSink]。
     */
    val debug: Boolean,
) {
    /** 取某类子系统的档位（[modes] 键集恒全集，getValue 不会缺键）。 */
    fun mode(kind: NativeKind): NativeProviderMode = modes.getValue(kind)

    companion object {
        /** 旋钮键前缀（ADR-010 §3 :213-217 的 `eratw.native.*`）。 */
        const val PROPERTY_PREFIX: String = "eratw.native"

        /** debug 旋钮键（ADR-010 §3 :217，取值域 `0|1`）。 */
        const val PROPERTY_DEBUG: String = "$PROPERTY_PREFIX.debug"

        /** 某类子系统的旋钮键：`eratw.native.<key>`。 */
        fun propertyKey(kind: NativeKind): String = "$PROPERTY_PREFIX.${kind.key}"

        /** 平台默认：四类全 [NativeProviderMode.OFF]、debug 关（ADR-010 §3 :221）。 */
        val DEFAULT: NativeProvidersConfig = NativeProvidersConfig(
            modes = NativeKind.entries.associateWith { NativeProviderMode.OFF },
            debug = false,
        )

        /** 档位合法域（小写形态；解析时对原文 trim + 小写后比对，大小写不敏感）。 */
        private val LEGAL_MODES: Set<String> = setOf("off", "auto", "force")

        private const val LAYER_SYSTEM: String = "系统性质"

        private const val LAYER_BUILD: String = "构建档占位"

        /**
         * 解析配置（纯函数）。
         *
         * 优先级逐键生效（ADR-010 §3 :220）：[props]（系统性质视图）命中 > [buildDefaults]
         * （构建档占位视图）命中 > [DEFAULT]。两视图都只点名五个键，未知键忽略（见类 KDoc）。
         *
         * @param props 系统性质视图（通常来自 [Companion.fromSystemProperties] 的收集，
         *   单测直接喂 map——不碰进程全局）
         * @param buildDefaults 构建档占位视图（Gradle 属性/BuildConfig 打包档；本波不落
         *   BuildConfig 接线——契约「明确不做」，此参数是将来 app 面接线的占位入口）
         * @throws IllegalArgumentException 任一命中键的值不在合法域（档位 `off|auto|force`、
         *   debug `0|1`）；报错点名键、原文与来源层
         */
        fun parse(
            props: Map<String, String>,
            buildDefaults: Map<String, String> = emptyMap(),
        ): NativeProvidersConfig {
            val modes = NativeKind.entries.associateWith { kind ->
                val key = propertyKey(kind)
                val hit = resolveLayered(key, props, buildDefaults)
                if (hit == null) {
                    NativeProviderMode.OFF
                } else {
                    val (raw, layer) = hit
                    parseMode(raw, key, layer)
                }
            }
            val debugKey = PROPERTY_DEBUG
            val debugHit = resolveLayered(debugKey, props, buildDefaults)
            val debug = if (debugHit == null) {
                false
            } else {
                val (raw, layer) = debugHit
                parseDebug(raw, debugKey, layer)
            }
            return NativeProvidersConfig(modes, debug)
        }

        /**
         * 从进程系统性质收集五个旋钮键并解析（**解析层**唯一的 System.getProperty 读取点；
         * 下游 selection 层只吃注入值）。缺省性质缺席的键不进 [parse] 的 props 视图，
         * 交给下一层（[buildDefaults] / 默认）。
         *
         * 本函数无生产消费者（契约「明确不做」：任何 `eratw.native.*` 的宿主接线属
         * app 面）——它是将来宿主装配的入口占位，单测不调它（不依赖进程全局）。
         *
         * @throws IllegalArgumentException 同 [parse]
         */
        fun fromSystemProperties(buildDefaults: Map<String, String> = emptyMap()): NativeProvidersConfig {
            val props = buildMap {
                for (kind in NativeKind.entries) {
                    val key = propertyKey(kind)
                    System.getProperty(key)?.let { put(key, it) }
                }
                System.getProperty(PROPERTY_DEBUG)?.let { put(PROPERTY_DEBUG, it) }
            }
            return parse(props, buildDefaults)
        }

        /** 逐键定层：系统性质命中优先，其次构建档占位；值附层名，报错时能点名来源。 */
        private fun resolveLayered(
            key: String,
            props: Map<String, String>,
            buildDefaults: Map<String, String>,
        ): Pair<String, String>? =
            props[key]?.let { it to LAYER_SYSTEM }
                ?: buildDefaults[key]?.let { it to LAYER_BUILD }

        private fun parseMode(raw: String, key: String, layer: String): NativeProviderMode =
            when (val normalized = raw.trim().lowercase()) {
                "off" -> NativeProviderMode.OFF
                "auto" -> NativeProviderMode.AUTO
                "force" -> NativeProviderMode.FORCE
                else -> throw IllegalArgumentException(
                    "非法档位：$layer 的 $key=「$raw」（trim+小写后=「$normalized」），" +
                        "合法域 off|auto|force；非法档位显式报错、不静默回落 off（ADR-010 §2/§3）",
                )
            }

        private fun parseDebug(raw: String, key: String, layer: String): Boolean =
            when (val normalized = raw.trim()) {
                "0" -> false
                "1" -> true
                else -> throw IllegalArgumentException(
                    "非法档位：$layer 的 $key=「$raw」（trim 后=「$normalized」），合法域 0|1；" +
                        "非法值显式报错、不静默当 0（ADR-010 §3 :217）",
                )
            }
    }
}
