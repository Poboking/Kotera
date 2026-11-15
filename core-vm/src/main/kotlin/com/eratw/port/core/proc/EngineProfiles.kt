package com.eratw.port.core.proc

import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.compat.EngineFamily
import com.eratw.port.core.compat.EngineProfileData
import com.eratw.port.core.compat.TargetSpec

/**
 * 引擎档位的**装配面**（ENGC-2）：把「哪家引擎 + 哪版语料」翻成一份
 * [RuntimeCompatibilityProfile]。
 *
 * ## 事实出处（一个事实一个出处，I2 / CONTRACT-ENGCPL G1）
 *
 * 引擎/档位事实只写一份**手写权威** `specs/engine-profiles.input.yaml`，再由
 * `gen_engine_profiles.py --emit-kotlin` 在**构建期**投影成 [EngineProfileData] 的常量。
 * 本文件只是「怎么解析」这一层，不手抄任何引擎名、版本串、函数名、命令名或方言字面量：
 *
 * 1. **名单 / 方言值**：`input yaml` 的 `engines:` / `dialect:` 段 → [EngineProfileData] 的
 *    `functionsOf` / `commandsOf` / `PORT_EXTENSIONS` / `DIALECT` /
 *    [EngineProfileData.RELATION_ROUTED_KEYWORDS] /
 *    [EngineProfileData.RELATION_GATED_KEYWORDS]。要改语义一律改 yaml + 重生成，
 *    不在这里加名字。
 * 2. **档位表 [TARGETS]**：`input yaml` 的 `targets:` 段 → [EngineProfileData.TARGETS] 的
 *    **逐字段投影**（[Target] 只把 `corpusVersionId` 这一串收口成 [CorpusVersion]）。
 *    它同时是四处消费的解析依据（谁消费本表）：
 *    - **运行期门控**：`app` 的 `GameHost` 读 `assets/engine-profile.json` → [EngineStamp]
 *      → [profileFor] 装档案；函数面据此建 `EngineSurface`（无印章 ⇒ 不建 ⇒ eraTW 零干扰）。
 *    - **B4 命令审计**：[CompatProfileMismatchException] 用 [targetOf] 把家族名渲染成
 *      人类可读的目标档标签；`scan` 的提前返回吃 [Target.fullStatementCoverage] 这一
 *      **数据事实**（不再认家族名）；合法集另经 [legalStatementNames] 委托
 *      `CompatCommandAudit.allowedNames`（同一委托，两侧不各抄一份）。
 *    - **erbcc 编译期**：`tools/erbcc` 的 `EngineProfileView` 经 [target] 按 profileId 查本表，
 *      据此注入函数面视图与行首合法集（`--engine-profile` 通道）。
 *    - **python 出包侧**：`devtools/engine_compat.py` 与 Kotlin **同读那一份 input yaml**
 *      （构建期）；本表不再有第二份 python 镜像，也不再有任何「读另一侧源码文本对拍」的
 *      用例（CONTRACT-ENGCPL §5 明禁）。
 *
 * 符号 / 方言的装配差异一律不落在这里：函数面差异 = 消费点对 [EngineProfileData] 集合的
 * 交集查询（[EngineFamily] 决定查哪一份），不复制名单。
 *
 * ## 符号解析为什么不随档位改
 *
 * 三档在 ENGC-2 都声明 [ResolverPolicy.LEGACY_LAST_WINS]：函数面的差异（某引擎没有某个
 * 内建名）由 ENGC-3 的**可见性门控**表达，不是「同名函数谁赢」的符号解析问题。
 * `MODERN` 档是 Mod 架构的独立档位，`modern()` 工厂仍单独提供。
 *
 * ## corpusVersion 的口径
 *
 * [RuntimeCompatibilityProfile.corpusVersion] 声明的是**本移植装载管线对准的语料版本**
 * （4.980 / 4.981）。生成物里的 `corpusVersionId` 是字符串（生成物不得 import `core.proc`），
 * 由 [TARGETS] 在这里用 [CorpusVersion.valueOf] 收口**一次**：未知 id 会在 [EngineProfiles]
 * 类初始化（装配期）就抛，**不静默回落**。
 */
object EngineProfiles {

    /**
     * 构建期印章里一个合法档位：`profile` 标识 + 配套的 `engine` / `version` 字符串
     * + 解析后的家族与语料版本 + 语句面全覆盖声明。
     *
     * 五元组（[profileId] / [engine] / [version] / [family] / [corpusVersion]）逐字段等于
     * [TargetSpec] 的同名项（`corpusVersionId` 经 [CorpusVersion.valueOf] 收口），
     * 是 B6 出包脚本与 [EngineStamp] 的对拍对象。
     */
    data class Target(
        val profileId: String,
        val engine: String,
        val version: String,
        val family: EngineFamily,
        val corpusVersion: CorpusVersion,
        /**
         * 本档语句头合法集是否覆盖**全部** `FunctionCode` 枚举名（数据事实，来自 input yaml 的
         * `full_statement_coverage`）。`true` 是 `CompatCommandAudit.scan` 提前返回的唯一依据，
         * 由 `CompatCommandAuditTest` 的枚举级用例担保其等价。
         */
        val fullStatementCoverage: Boolean,
    )

    /** 三个受支持的印章档位（顺序 = 生成物顺序，只影响报错时的并列顺序）。 */
    val TARGETS: List<Target> = EngineProfileData.TARGETS.map { it.toTarget() }

    /**
     * 无印章 / 无 `--engine-profile` 时的目标档
     * （input yaml `targets.<id>.default: true` 恰一条）。
     */
    val DEFAULT_TARGET: Target =
        TARGETS.firstOrNull { it.profileId == EngineProfileData.DEFAULT_TARGET_PROFILE_ID }
            ?: error(
                "生成物 EngineProfileData.TARGETS 里没有缺省档" +
                    " '${EngineProfileData.DEFAULT_TARGET_PROFILE_ID}'" +
                    "（档位表：${TARGETS.map { it.profileId }}）—— 重跑 gen_engine_profiles.py",
            )

    /** 按印章里的 `profile` 标识查表；未知标识返回 null（调用方按非法印章处理）。 */
    fun target(profileId: String): Target? = TARGETS.firstOrNull { it.profileId == profileId }

    /** 按引擎家族查其目标档（档案构造与审计报错标签用）；家族未登记档位返回 null。 */
    fun targetOf(family: EngineFamily): Target? = TARGETS.firstOrNull { it.family == family }

    /**
     * 本档的**语句头合法集**（大写口径）= 命令集 ∪ 函数集 ∪ PORT_EXTENSIONS ∪ 结构码
     * `{__NULL__, SET, METHOD}`。
     *
     * B6 公开门面：erbcc `--engine-profile` 的 scan 行首过滤与命令面缺口清单必须用
     * 这一份（`CompatCommandAudit.allowedNames` 的唯一委托，不复制名单 —— I2 一个事实
     * 一个出处；运行期 B4 审计与编译期 erbcc 扫描从此同源，名单漂移两侧同时可见）。
     */
    fun legalStatementNames(family: EngineFamily): Set<String> =
        CompatCommandAudit.allowedNames(family)

    /**
     * 家族 + 语料版本 → 档案。家族的三条 `when` 分支由生成物提供（加家族先改生成器与
     * `CompatFacets.kt`，这里不兜底），因此 `DIALECT` 缺项会是装配期的
     * [NoSuchElementException]，不是静默默认。
     *
     * [corpusVersion] 缺省取 [DEFAULT_TARGET] 声明的语料版本（缺省档 = 无印章路径的档）；
     * 显式传入时行为与改动前逐字段相同。
     */
    fun profileFor(
        family: EngineFamily,
        corpusVersion: CorpusVersion = DEFAULT_TARGET.corpusVersion,
    ): RuntimeCompatibilityProfile {
        val dialect = EngineProfileData.DIALECT.getValue(family)
        return RuntimeCompatibilityProfile(
            corpusVersion = corpusVersion,
            functionResolution = ResolverPolicy.LEGACY_LAST_WINS,
            engineFamily = family,
            stringCompare = dialect.stringCompare,
            nicknameKeywords = dialect.nicknameKeywords,
            relationMastername = dialect.relationMastername,
        )
    }
}

/**
 * 生成物的一条档位 → 装配面的 [EngineProfiles.Target]。
 *
 * `corpusVersionId` 是生成物里的字符串（生成物不得 import `core.proc`），收口点只此一处：
 * 未知 id 直接抛（生成物坏 = 装配期就炸，不静默回落缺省档）。
 */
private fun TargetSpec.toTarget(): EngineProfiles.Target {
    val corpus = try {
        CorpusVersion.valueOf(corpusVersionId)
    } catch (e: IllegalArgumentException) {
        error(
            "生成物 EngineProfileData.TARGETS 的档位 '$profileId' 声明了未知语料版本" +
                " '$corpusVersionId'（CorpusVersion 只有 " +
                "${CorpusVersion.values().joinToString()}）—— 重跑 gen_engine_profiles.py：$e",
        )
    }
    return EngineProfiles.Target(
        profileId = profileId,
        engine = engine,
        version = version,
        family = family,
        corpusVersion = corpus,
        fullStatementCoverage = fullStatementCoverage,
    )
}
