package com.eratw.port.core.compile

import com.eratw.port.core.data.csv.UserKeywordTables
import com.eratw.port.core.vars.SystemLayout
import com.eratw.port.core.vars.VariableRegistry
import java.util.Locale

/**
 * 常量字符串下标（`ITEM:药` / `M_CFLAG:LOCAL:后宫居住标记`）的**编译期 Restructure 解析器**。
 *
 * ## 为什么需要它（spec: `VariableStrArgTerm.cs:57-69`）
 *
 * 原版 Emuera 的 `VariableStrArgTerm.Restructure` 在**装载期**就把常量字符串下标折成
 * `SingleLongTerm(GetIntValue(exm))`：
 *
 * ```csharp
 * strTerm = strTerm.Restructure(exm);
 * if (!(strTerm is SingleTerm)) return this;   // 非常量 ⇒ 保留运行期查表
 * return new SingleLongTerm(GetIntValue(exm)); // 常量   ⇒ 装载期折成整数常量
 * ```
 *
 * 也就是说，**编译/装载产物里存的是整数下标**，运行期不查关键字表；只有动态字符串下标
 * （`ITEM:GETSTR(...)`）才保留 `VariableStrArgTerm` 的运行期查表。
 *
 * 移植侧此前把常量下标也编成 [com.eratw.port.core.expr.KeywordIndexArg]，运行期每次求值都按名字
 * 查 `VariableStore.keywordToInteger`。这不是「错误时机不同」的小差异，而是**相位复刻缺失**：
 * 字节码把关键字名当成运行期输入，于是编译产物与设备盘上的 CSV/ERD 表形成耦合 —— 盘上表
 * 一旦与编译期不同源（旧版 / 半截 / 被系统清理），一条本来合法的常量下标就会在剧情里抛
 * `CodeEE("…csv 中未定义关键字 …")`。（2026-11-10 vivo V2551A 连报 5 次即此形态。）
 * `KeywordIndexArg` 为这个热点加的 memoize（曾占 44% 脚本时间）本质上也是在给这个相位
 * 缺失打补丁；折成整数后热点自然消失。
 *
 * ## 查表口径（不另造一份）
 *
 * - 系统关键字变量：直接调 [VariableRegistry.keywordIndex] —— 与运行期
 *   `VariableStore.keywordToInteger` 的兜底分支**同一个函数**，含 dim 位校验与表名选择；
 * - ERD 用户变量：用编译期 `UserKeywordTables.foldSnapshot` 的表，候选键由
 *   [UserKeywordTables.candidateTableKeys] 给出（与运行期 `install` 的键生成同口径）；
 * - `RELATION` 走运行期角色模板表（`chara*.csv` 角色名），编译期不折。
 *
 * ## 失败方向（保守，绝不改变既有报错）
 *
 * 查不到 / dim 不合法 / 候选表冲突 / 关键字为空 ⇒ 返回 null ⇒ 调用侧**保留原结点**，
 * 运行期照旧按原语义报错。不会把「运行期才报的未定义关键字」升级成装载/编译失败。
 * 仅动态下标（非 `StrLiteral`）由调用侧保持原样。
 */
class ConstantKeywordResolver(
    private val registry: VariableRegistry?,
    /** 编译期 `foldKeywordTables` = ERD 表 + 内置名表（键冲突时内置优先，与运行期同源）。 */
    private val keywordTables: Map<String, Map<String, Int>>,
) {
    /** 本次编译实际折成整数的常量下标数（manifest 读数；解析单线程串行，无需原子）。 */
    var foldedCount: Int = 0
        private set

    /**
     * @return 折成的整数下标；null = 不折（保留运行期查表/报错）。
     */
    fun resolve(varName: String, dim: Int, keyword: String): Long? {
        // 空关键字：运行期 `VariableStrArgTerm.GetIntValue` 抛 KeywordCanNotEmpty，
        // 用户变量分支也先抛「关键字不能为空」⇒ 保持运行期报错原样。
        if (keyword.isEmpty()) return null
        val upper = varName.uppercase(Locale.ROOT)
        // RELATION: 关键字是角色模板名（chara*.csv），不是静态 CSV 名字表 ⇒ 运行期查。
        if (upper == "RELATION") return null

        // 1) 系统关键字变量：复用运行期同一个查表函数（含 CDFLAG 的 byDim / dim 校验）。
        //    SystemLayout.keywords 的键即系统变量名（大写）；用户 #DIM 不允许重名
        //    （GameSession 的 systemNames 排除面、install 的同源口径），故这里无需再判用户变量。
        val reg = registry
        if (reg != null && SystemLayout.keywords.containsKey(upper)) {
            val v = runCatching { reg.keywordIndex(varName, dim, keyword).toLong() }.getOrNull()
            if (v != null) foldedCount++
            return v
        }

        // 2) ERD 用户变量：候选表键由 UserKeywordTables 按 install 的键口径统一给出。
        //    多个候选同时命中却给出不同下标 ⇒ 无法判定该用哪张 ⇒ 不折（宁可保留运行期语义）。
        val hits = UserKeywordTables.candidateTableKeys(upper, dim)
            .mapNotNull { key -> keywordTables[key]?.get(keyword) }
            .distinct()
        return if (hits.size == 1) {
            foldedCount++
            hits[0].toLong()
        } else {
            null
        }
    }
}
