package com.eratw.port.modgen

import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.CharaRouteDecl
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.CsvKeywordDecl
import com.eratw.port.modapi.CsvValueDecl
import com.eratw.port.modapi.GlobalDimDecl
import com.eratw.port.modapi.HookDecl
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.JobTemplateDecl
import com.eratw.port.modapi.PhaseDecl
import com.eratw.port.modapi.PrivateDimDecl
import com.eratw.port.modapi.RelationshipDecl
import com.eratw.port.modapi.SettingDecl
import com.eratw.port.modapi.WriteGrantDecl

/**
 * 生成器侧的 Manifest 声明源：与 [com.eratw.port.modapi.ModManifest] 逐节对应，
 * 18 节全部可声明（P1-6 ③ 已补上 `external-calls` / `override-functions` / `private-dims` /
 * `exported-macros` / `artifact-sha256` 五节）。缺省值 = 该节写空，与补前字节一致。
 *
 * 角色变量路由（M5-4 刀 1）不占新节：[charaRoutes] 走 component-schemas 段内判别 tag
 * 子类型 B1（段数 18 不变，phase-jobs B0/B1/B2 先例），无路由时该节字节与旧档逐位一致。
 */
data class ManifestSource(
    val packageId: String,
    val version: String = "",
    val requires: List<String> = emptyList(),
    val loadAfter: List<String> = emptyList(),
    val globalDims: List<GlobalDimDecl> = emptyList(),
    val csvKeywords: List<CsvKeywordDecl> = emptyList(),
    val csvValues: List<CsvValueDecl> = emptyList(),
    val entityRefs: Set<String> = emptySet(),
    val components: List<ComponentDecl> = emptyList(),
    val archetypes: List<ArchetypeDecl> = emptyList(),
    val relationships: List<RelationshipDecl> = emptyList(),
    val hooks: List<HookDecl> = emptyList(),
    val interactionHooks: List<InteractionHookDecl> = emptyList(),
    val phases: List<PhaseDecl> = emptyList(),
    val jobs: List<JobDecl> = emptyList(),
    val jobTemplates: List<JobTemplateDecl> = emptyList(),
    /** 跨包导入序（列表序 = 导入哨兵下标序，有语义，不得排序）。 */
    val externalCalls: List<String> = emptyList(),
    /** 显式覆盖声明（MODERN 链接档的 `HAS_OVERRIDES` 判据）。 */
    val overrideFunctions: Set<String> = emptySet(),
    /** 函数私有 `#DIM`：函数键 → 局部变量表。 */
    val privateDims: Map<String, List<PrivateDimDecl>> = emptyMap(),
    /** 导出的 `.ERH` `#DEFINE` 宏：宏名 → 展开文本。 */
    val exportedMacros: Map<String, String> = emptyMap(),
    /** 产物 `.erbm` 的 sha256（64 位小写十六进制；空串 = 未提供）。 */
    val artifactSha256: String = "",
    /** 角色变量路由声明（M5-4 刀 1，chara-field-routes 域；缺省空 = 无路由，字节与旧档一致）。 */
    val charaRoutes: List<CharaRouteDecl> = emptyList(),
    /** 玩家可见的显示名（`RT2MANIFEST/4` 元数据域；空串 = 不写，仍出 `/3`）。 */
    val displayName: String = "",
    /** 作者 / 维护者（纯展示；空串 = 不写）。 */
    val author: String = "",
    /** 一句话说明（纯展示；空串 = 不写）。 */
    val description: String = "",
    /** per-mod 设置项声明（`RT2MANIFEST/5` 的 settings 段；空表 = 不写，仍出 `/3` 或 `/4`）。 */
    val settings: List<SettingDecl> = emptyList(),
    /**
     * 作者显式声明的逐条声明源位置（`RT2MANIFEST/7` 的 `declaration-positions` 可选段）。
     * 键 = 冲突报告里那条声明的人类可读原文（如 `global-dim MOD_STATE`），值 = 作者填的
     * `文件:行号`。**可选、不强制**：空表 = 旧编码字节逐字不变；不猜、不从 Kotlin 反射造行号。
     *
     * 本字段是写侧登记入口；要一路落到产物字节还需 `ModManifest` 与
     * [com.eratw.port.core.mod.ModDeclarationAdapter] 同步透传（见交付消息「需组长接线」）。
     * core 侧编解码与冲突诊断已就位，可先用 [com.eratw.port.core.patch.ModuleDescriptor.declarationPositions]
     * 直测往返。
     */
    val declarationPositions: Map<String, String> = emptyMap(),
    /**
     * 写授权声明（`RT2MANIFEST/8` 的 `write-grants` 可选段，MODDX4-B1 组长接线）：
     * 空表 = 不写段，产物字节与 /3–/7 逐字相同。语义与 fail-closed 口径见
     * [com.eratw.port.modapi.ModManifest.writeGrants]。
     */
    val writeGrants: List<WriteGrantDecl> = emptyList(),
)
