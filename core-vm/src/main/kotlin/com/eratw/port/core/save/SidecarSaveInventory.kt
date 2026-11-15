package com.eratw.port.core.save

/**
 * 逻辑存档单元（本体 + 伴生件）的**唯一形状知识来源**（RT2 MODGC 裁定 1）。
 *
 * 权威 = `era-arch-v61u.md:204`（`saveNN.sav` 与 `saveNN.sav.rt2` 是一个逻辑存档）
 * + `:222`（删除/复制/列表/CHKDATA 全配对）。本对象只描述文件名形状，不碰磁盘、
 * 不做删除决策；删除/随迁/注册表扫描各自按同一份清单执行。
 *
 * 消费面：
 * - [FileSystemOps] 的 DELDATA 成对清理；
 * - [SaveFolderMigration] 的配对随迁、孤儿 GC（后缀表从本清单反推）；
 * - `app.ModRuntimeAssembly.loadRegistryUnion` 的磁盘 `*.sav.rt2` 快照扫描
 *   （regbin TODO ③ 的同源面）。
 *
 * 顺序 = M4-3 删除相位的清序（journal 先失效，两成员残件随后，容器收尾）；
 * 对顺序不敏感的消费方沿用同一顺序也无害。
 */
object SidecarSaveInventory {

    /** 容器成员本体名之后的后缀：`saveNN.sav` → `saveNN.sav.rt2`。 */
    const val CONTAINER_SUFFIX: String = ".rt2"

    /** [companionPaths] 的探针本体名：只为从唯一清单反推后缀表，无其他语义。 */
    private const val PROBE_BODY: String = "eratw-probe-body"

    /** 一条逻辑存档单元的全部伴生件（顺序 = DELDATA 清序）。 */
    fun companionPaths(body: String): List<String> = listOf(
        "$body.journal",
        "$body.part",
        "$body.bak",
        "$body$CONTAINER_SUFFIX.part",
        "$body$CONTAINER_SUFFIX.bak",
        containerPath(body),
    )

    /**
     * 伴生件后缀表：从 [companionPaths] 反推，**不复制第二份后缀知识**；
     * 先长后短 ⇒ `.rt2.part`、`.rt2.bak` 永远先于 `.part`、`.bak` 命中。
     */
    private val companionSuffixes: List<String> =
        companionPaths(PROBE_BODY)
            .map { it.removePrefix(PROBE_BODY) }
            .sortedByDescending { it.length }

    /**
     * 名字若是某逻辑存档单元的伴生件，给出其**本体名**；不是伴生件形状 ⇒ null。
     * 例：`save00.sav.rt2.bak` → `save00.sav`、`global.sav.journal` → `global.sav`；
     * `notes.sav.bak` → `notes.sav`（是否本体形状由 [isLogicalSaveBody] 另行把关）。
     */
    fun companionBodyOf(name: String): String? {
        for (suffix in companionSuffixes) {
            if (name.length > suffix.length && name.endsWith(suffix)) return name.removeSuffix(suffix)
        }
        return null
    }

    /**
     * 逻辑存档本体形状：`global.sav` 精确名（Config.cs:562 的 `File.Exists` 口径）
     * 或 `save` 打头的 `*.sav`（Config.cs:563 的 `save*.sav` 通配）。
     */
    fun isLogicalSaveBody(name: String): Boolean =
        name == SaveFolderMigration.GLOBAL_FILE || SaveFolderMigration.matchesSavePattern(name)

    /** 本体名 → 容器成员名（`save00.sav` → `save00.sav.rt2`）。 */
    fun containerPath(body: String): String = body + CONTAINER_SUFFIX
}
