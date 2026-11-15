package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compat.EngineFamily
import com.eratw.port.core.compat.EngineProfileData

/**
 * 启动期命令面审计（ENGC-3 B4）：产物里出现的语句代码名，是否都在目标引擎档位的
 * 命令/方法表里。不在 ⇒ 硬拒（D14）。
 *
 * ## 为什么这一步必须存在
 *
 * 原版按**目标引擎自己的**命令字典解析每一行：`IdentifierDictionary.GetFunctionIdentifier`
 * （`reference/emuera-cs/MinorShift.Emuera/IdentifierDictionary.cs:497-513`）查不到名字时
 * 这行退化成赋值解析，再失败就是 `InvalidLine`（`...Script.Parser/LogicalLineParser.cs:531-534`
 * 的 `CanNotInterpretedLine`「无法解析的行」），装载器把它记成硬错误
 * （`...Script.Loader/ErbLoader.cs:469-473` 置 `noError = false`）⇒ 游戏根本起不来。
 *
 * 本移植的编译器只有一套超集命令表（317 个 `FunctionCode`），语料能编过并不代表目标引擎
 * 认得；所以「档位配错」必须在装配期被抓住，而不是等玩家玩到某条命令时才发现。
 *
 * ## 合法集合 = 命令表 ∪ 方法表 ∪ 端口扩展 ∪ 结构码
 *
 * - **命令表** [EngineProfileData.commandsOf]：目标引擎的 instruction 字典。
 * - **方法表** [EngineProfileData.functionsOf]：原版把方法字典并进命令字典
 *   （`...GameProc.Function/FunctionIdentifier.cs:4135-4142` 逐条 `funcDic.Add`），所以
 *   snake 的函数表项（如 `SEQUENCEINPUT`、`DISABLE_INPUT_MACRO`）在语句首也能合法出现。
 * - **端口扩展** [EngineProfileData.PORT_EXTENSIONS]：与 [com.eratw.port.core.fn.EngineSurface]
 *   的声明同一并集口径。当前这批名字都不是 `FunctionCode`，不会出现在语句头（`Statement.code`
 *   是枚举），列在这里是让审计面的合法集与运行期函数面**同源**，避免将来加一条就分叉。
 * - **结构码** `__NULL__`/`SET`/`METHOD`：不是任何引擎的「命令」，而是本移植记录布局的
 *   语句分类 —— 函数标签/`#` 预处理行读作 `__NULL__`（`Statement.kt` 的 `SharpStmt` 等），
 *   赋值行读作 `SET`（`AssignStmt.code`；原版 `SETFunction` 亦不走字典，见
 *   `LogicalLineParser.cs:401/:556`），`obj:method` 读作 `METHOD`。
 *
 * ## 语句面全覆盖档的提前返回
 *
 * 4.981 语料就是按 snake 命令表写的，且 snake 档在 input yaml 里**声明**
 * `full_statement_coverage: true` —— 即它的合法集覆盖全部 `FunctionCode`
 * （`CompatCommandAuditTest` 遍历所有该声明为真的档逐个枚举钉住）⇒ 扫描恒为空集，
 * 直接短路省一趟 171.7 万条记录读。
 *
 * 短路认的是 [EngineProfiles.Target.fullStatementCoverage] 这一**数据事实**，不是家族名
 * （CONTRACT-ENGCPL §3.1 E2）：谁声明全覆盖谁短路，加一档不改这里。
 * **这不是「多一档豁免」**：声明为真的档若合法集缺任何一条枚举，那条枚举级用例立刻红；
 * 缺省档若没声明，`CompatCommandAuditTest` 的「缺省档必须声明全覆盖」用例立刻红
 * （否则无印章启动会白扫一趟，违反 I1）。
 */
internal object CompatCommandAudit {

    /** 命中一条：语句代码名 + `.erbc` 记录里的源行号。 */
    data class Hit(val command: String, val line: Int)

    /** 明细上限（其余折叠为计数）：错误屏高度有限，玩家只需据此判断「档位配错了」。 */
    const val MAX_DETAILS: Int = 8

    private val STRUCTURAL: Set<String> = setOf("__NULL__", "SET", "METHOD")

    /**
     * 本档的语句头合法集（大写口径）。同一家族重复调用会重建集合（几百个字符串），
     * 但调用点是会话装配期一次，不设缓存 —— 缓存就得管生命周期，不值得。
     */
    fun allowedNames(family: EngineFamily): Set<String> =
        (EngineProfileData.commandsOf(family) +
            EngineProfileData.functionsOf(family) +
            EngineProfileData.PORT_EXTENSIONS +
            STRUCTURAL)
            .mapTo(HashSet()) { it.uppercase() }

    /**
     * 扫一遍 [program] 的语句头，返回目标档位**不认**的代码名（按语句池下标升序）。
     *
     * 不物化语句（见 [Program.scanStatementHeaders]），成本 = 一趟记录头字节读。
     */
    fun scan(program: Program, family: EngineFamily): List<Hit> {
        // 见类 KDoc「语句面全覆盖档的提前返回」：该档声明合法集覆盖全部枚举，扫描结果恒为空。
        if (EngineProfiles.targetOf(family)?.fullStatementCoverage == true) return emptyList()
        val allowed = allowedNames(family)
        val hits = ArrayList<Hit>()
        program.scanStatementHeaders { name, line ->
            if (name !in allowed) hits.add(Hit(name, line))
        }
        return hits
    }
}

/**
 * 产物与目标引擎档位不匹配（ENGC-3 B4；D14 硬拒）：语句里出现了目标引擎没有的命令。
 *
 * 消息上屏走既有装载失败面（`GameHost` 的 `describe` → 「启动失败：…」），不新增
 * UI 文案表条目（D7）；详细命中在 [hits] 里，消息只列前
 * [CompatCommandAudit.MAX_DETAILS] 条。
 */
internal class CompatProfileMismatchException(
    val family: EngineFamily,
    val hits: List<CompatCommandAudit.Hit>,
) : RuntimeException(buildMessage(family, hits)) {

    private companion object {
        fun buildMessage(family: EngineFamily, hits: List<CompatCommandAudit.Hit>): String {
            val target = EngineProfiles.targetOf(family)
            val label = if (target != null) {
                "${target.engine} ${target.version}（${target.profileId}）"
            } else {
                family.name
            }
            val shown = hits.take(CompatCommandAudit.MAX_DETAILS)
            return buildString {
                append("产物包含目标引擎不支持的命令（目标档：$label）：共 ${hits.size} 处")
                for (h in shown) append("\n  第 ${h.line} 行 ${h.command}")
                val rest = hits.size - shown.size
                if (rest > 0) append("\n  另有 $rest 处")
                append("\n请确认游戏与引擎档位是否匹配，或联系打包者。")
            }
        }
    }
}
