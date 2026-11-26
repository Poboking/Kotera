package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path

/**
 * `check-mod` 的旁挂资源树面（ADR-014 D2/D7）：`resources/mod/<dir>/…` 与 `sound/mod/<dir>/…`。
 *
 * 目标：作者在本机跑 `check-mod` 就能知道「这棵树打不进包 / 打进去也用不了」，
 * 而不是等到 `pack-mod` 或导入端才炸。
 *
 * 判据**单一真源**全在 [PackModResources]：目录名谓词 [isMediaDirName]（经
 * [PackModResources.dirProblem]）、两树的扩展名白名单与单文件/单包上限
 * （[PackModResources.IMAGE_TEXT_TREE]、[PackModResources.AUDIO_TREE]）、两树合计上限
 * [PackModResources.MAX_TREES_TOTAL_BYTES]、单条目拒收原因 [PackModResources.entryProblem]、
 * 遍历口径 [PackModResources.walkFiles]。本文件不复制任何阈值或白名单字面量。
 *
 * 严重度口径（与 [ModCheckStrings] 一致：必然失效 ⇒ ERROR；可改进/接近上限 ⇒ WARN）：
 * - 目录名非法（非 ASCII、路径分隔符、超长…）= ERROR：ERB 源码是 Shift-JIS，作者连
 *   `mod/<dir>/…` 这样的字面量都写不出来，放行等于给一个用不了的目录名；
 * - 声明了 `<dir>` 但两树都不在 / 实际目录与声明不一致 = ERROR：pack-mod 只收与
 *   `--resources` 同名的目录，其余情形资源会被静默丢包；
 * - 树存在但空 / 扩展名越界 / 单文件超限 / 单树合计超限 / 两树合计超限 = ERROR
 *   （pack-mod 用同一判据拒包，这里只是提前到作者本机）；
 * - 用量 >80% 但未超限 = WARN（预览用，不阻断）；
 * - 没给 `--resources` 时按实际目录做审计，并 WARN 提示「没声明就不会被 pack 收」。
 *
 * 注意：`pack-mod` 自己走 [PackModResources.collect] 的 [PackUnsafe] 通道（文案与退出码是
 * 既有合同），故 [ModCheck.check] 由 pack 调用时传 `includeMediaResources = false`，
 * 本面只在 `check-mod` CLI 面产出 Finding，pack 的 stdout/stderr 逐字不变。
 */
internal object ModCheckMediaResources {

    /** 「接近上限」WARN 的触发线（百分比）；真正上限一律取 [PackModResources] 的真源值。 */
    private const val NEAR_LIMIT_PERCENT = 80L

    /** 百分比换算分母。 */
    private const val PERCENT = 100L

    /**
     * 跑旁挂资源面：`declaredDir` = 包元数据 `resources` 声明值（`pack-mod --resources` 同一取值），
     * null = 作者没声明，此时按模块里实际存在的目录做审计。
     */
    fun check(root: Path, declaredDir: String?, findings: MutableList<Finding>) {
        val imageDirs = childDirs(root, PackModResources.RESOURCES_ROOT)
        val audioDirs = childDirs(root, PackModResources.SOUND_ROOT)
        val actualDirs = (imageDirs + audioDirs).distinct().sorted()
        if (declaredDir == null) {
            undeclaredNotes(imageDirs, audioDirs, findings)
            actualDirs.forEach { dir -> auditDir(root, dir, findings) }
        } else {
            declaredProblems(declaredDir, actualDirs, findings)
            if (isMediaDirName(declaredDir)) auditDir(root, declaredDir, findings)
        }
    }

    /** 声明了 `<dir>` 时：目录名合法性、两树是否存在、实际目录是否与声明同名。 */
    private fun declaredProblems(
        declared: String,
        actualDirs: List<String>,
        findings: MutableList<Finding>,
    ) {
        val dirLabel = "${PackModResources.RESOURCES_ROOT}/${PackModResources.MOD_SEGMENT}/$declared"
        val problem = PackModResources.dirProblem(declared)
        if (problem != null) {
            findings += Finding(
                severity = Severity.ERROR,
                file = dirLabel,
                line = null,
                message = "包元数据 resources 声明「$declared」不合法：$problem —— ERB 源码是 Shift-JIS，" +
                    "这种目录名连字面量都写不进 ERB，运行期更取不到；改成 ASCII 目录名",
            )
            return
        }
        if (declared in actualDirs) {
            actualDirs.filter { it != declared }.forEach { extra ->
                findings += Finding(
                    severity = Severity.WARN,
                    file = "${PackModResources.RESOURCES_ROOT}/${PackModResources.MOD_SEGMENT}/$extra",
                    line = null,
                    message = "模块里还有旁挂目录「$extra」，但包元数据 resources 只声明了「$declared」：" +
                        "pack-mod 只收「$declared」，「$extra」不会进包（临时目录请移出模块根，或改名/改声明）",
                )
            }
            return
        }
        val message = if (actualDirs.isEmpty()) {
            "包元数据 resources 声明「$declared」，但 resources/mod/$declared/ 与 sound/mod/$declared/ 都不存在：" +
                "pack-mod 会按未声明处理并继续出包（不写 resources 字段），资源全部静默丢失"
        } else {
            "包元数据 resources 声明「$declared」，但模块里实际的旁挂目录是「${actualDirs.joinToString("、")}」：" +
                "两侧不一致，pack-mod 只收与 --resources 同名的 $declared，实际目录一个都不会进包（资源静默丢失）"
        }
        findings += Finding(severity = Severity.ERROR, file = dirLabel, line = null, message = message)
    }

    /** 没声明 `<dir>` 时：提示「树不会被收」，并提示两树目录名不一致会丢一棵。 */
    private fun undeclaredNotes(
        imageDirs: List<String>,
        audioDirs: List<String>,
        findings: MutableList<Finding>,
    ) {
        val actualDirs = (imageDirs + audioDirs).distinct().sorted()
        if (actualDirs.isEmpty()) return
        val label = "${PackModResources.RESOURCES_ROOT}/${PackModResources.MOD_SEGMENT}"
        findings += Finding(
            severity = Severity.WARN,
            file = label,
            line = null,
            message = "模块里有旁挂资源树（${actualDirs.joinToString("、")}），但 check-mod 没给 --resources <dir>：" +
                "pack-mod 只有在 --resources 与目录名一致时才会收树，也不会写包元数据 resources 字段",
        )
        val disjoint = imageDirs.isNotEmpty() && audioDirs.isNotEmpty() && imageDirs.none { it in audioDirs }
        if (disjoint) {
            findings += Finding(
                severity = Severity.WARN,
                file = label,
                line = null,
                message = "两棵资源树的目录名不一致：resources 下是「${imageDirs.joinToString("、")}」，" +
                    "sound 下是「${audioDirs.joinToString("、")}」—— pack-mod 一次只能按一个 <dir> 打包，" +
                    "另一棵不会进包",
            )
        }
    }

    /** 审计一个 `<dir>`：目录名合法性 + 两树各自限额 + 两树合计限额。 */
    private fun auditDir(root: Path, dir: String, findings: MutableList<Finding>) {
        val dirLabel = "${PackModResources.RESOURCES_ROOT}/${PackModResources.MOD_SEGMENT}/$dir"
        val problem = PackModResources.dirProblem(dir)
        if (problem != null) {
            findings += Finding(
                severity = Severity.ERROR,
                file = dirLabel,
                line = null,
                message = "旁挂资源目录名「$dir」不合法：$problem —— 非 ASCII 目录名 ERB 源码（Shift-JIS）" +
                    "写不出字面量，导入端也会硬拒；把目录名改成 ASCII",
            )
        }
        val specs = listOf(PackModResources.IMAGE_TEXT_TREE, PackModResources.AUDIO_TREE)
        val combined = specs.sumOf { spec -> auditTree(root, dir, spec, findings) }
        val treesLabel = "${PackModResources.TREES_LABEL}/$dir/"
        val treesMax = PackModResources.MAX_TREES_TOTAL_BYTES
        when {
            combined > treesMax -> findings += Finding(
                severity = Severity.ERROR,
                file = treesLabel,
                line = null,
                message = "两棵资源树合计超限：$treesLabel 合计 $combined 字节" +
                    "（${combined / PackLimits.BYTES_PER_MIB} MiB）超过上限 $treesMax 字节（48 MiB）",
            )
            isNear(combined, treesMax) -> findings += Finding(
                severity = Severity.WARN,
                file = treesLabel,
                line = null,
                message = "两棵资源树合计接近上限：$treesLabel 合计 $combined 字节" +
                    "（${percent(combined, treesMax)}%），上限 $treesMax 字节（48 MiB）",
            )
        }
    }

    /** 审计一棵树；返回该树全部普通文件的字节数（含被拒文件，与 pack 侧预算分母同口径）。 */
    private fun auditTree(
        root: Path,
        dir: String,
        spec: PackModResources.TreeSpec,
        findings: MutableList<Finding>,
    ): Long {
        val treeRoot = root.resolve(spec.rootName).resolve(PackModResources.MOD_SEGMENT).resolve(dir)
        if (!Files.isDirectory(treeRoot)) return 0L
        val label = "${spec.rootName}/${PackModResources.MOD_SEGMENT}/$dir/"
        val files = PackModResources.walkFiles(treeRoot)
        if (files.isEmpty()) {
            findings += Finding(
                severity = Severity.ERROR,
                file = label,
                line = null,
                message = "旁挂资源树「$label」存在但一个文件都没有：空树打包后没有任何内容，" +
                    "ERB 里对 mod/$dir/… 的引用会全部落空（多半是漏拷文件）",
            )
        }
        var total = 0L
        files.forEach { path ->
            val relative = ModCheckSources.relativeLabel(treeRoot, path)
            total += Files.size(path)
            auditFile(spec, path, relative, label, findings)
        }
        totalLimit(spec, label, total, findings)
        return total
    }

    /** 审计单个文件：先走打包端同源判据 [PackModResources.entryProblem]，合规再看是否接近上限。 */
    private fun auditFile(
        spec: PackModResources.TreeSpec,
        path: Path,
        relative: String,
        treeLabel: String,
        findings: MutableList<Finding>,
    ) {
        val name = treeLabel + relative
        val bytes = Files.size(path)
        val problem = PackModResources.entryProblem(name, relative, path, spec)
        if (problem != null) {
            findings += Finding(
                severity = Severity.ERROR,
                file = name,
                line = null,
                message = "旁挂资源 $name 不合打包判据：$problem（判据与 pack-mod 同源）",
            )
            return
        }
        if (!isNear(bytes, spec.maxFileBytes)) return
        findings += Finding(
            severity = Severity.WARN,
            file = name,
            line = null,
            message = "资源文件接近单文件上限：$name 实测 $bytes 字节" +
                "（${percent(bytes, spec.maxFileBytes)}%），上限 ${spec.maxFileBytes} 字节" +
                "（${spec.maxFileBytes / PackLimits.BYTES_PER_MIB} MiB）",
        )
    }

    /** 单树合计限额：超限 ERROR，>80% WARN。 */
    private fun totalLimit(
        spec: PackModResources.TreeSpec,
        label: String,
        total: Long,
        findings: MutableList<Finding>,
    ) {
        val max = spec.maxTotalBytes
        when {
            total > max -> findings += Finding(
                severity = Severity.ERROR,
                file = label,
                line = null,
                message = "旁挂资源树合计超限：$label 合计 $total 字节" +
                    "（${total / PackLimits.BYTES_PER_MIB} MiB）超过上限 $max 字节" +
                    "（${max / PackLimits.BYTES_PER_MIB} MiB）",
            )
            isNear(total, max) -> findings += Finding(
                severity = Severity.WARN,
                file = label,
                line = null,
                message = "旁挂资源树接近单包上限：$label 合计 $total 字节" +
                    "（${percent(total, max)}%），上限 $max 字节（${max / PackLimits.BYTES_PER_MIB} MiB）",
            )
        }
    }

    /** `<rootName>/mod/` 下的子目录名（排序；不递归，忽略同名文件）。 */
    private fun childDirs(root: Path, rootName: String): List<String> {
        val modDir = root.resolve(rootName).resolve(PackModResources.MOD_SEGMENT)
        if (!Files.isDirectory(modDir)) return emptyList()
        val stream = Files.list(modDir)
        return try {
            stream.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.sorted().toList()
        } finally {
            stream.close()
        }
    }

    /** 是否 > [NEAR_LIMIT_PERCENT]（严格大于，等于 80% 不算接近）。 */
    private fun isNear(bytes: Long, limit: Long): Boolean =
        limit > 0L && bytes * PERCENT > limit * NEAR_LIMIT_PERCENT

    /** 用量百分比（向下取整，只用于消息里的直观读数）。 */
    private fun percent(bytes: Long, limit: Long): Long = if (limit > 0L) bytes * PERCENT / limit else 0L
}
