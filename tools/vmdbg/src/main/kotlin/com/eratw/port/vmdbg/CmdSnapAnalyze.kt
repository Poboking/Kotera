package com.eratw.port.vmdbg

import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.snapshot.RuntimeSnapshot
import com.eratw.port.core.snapshot.RuntimeSnapshot.Value
import com.eratw.port.core.snapshot.RuntimeSnapshotDiff
import com.eratw.port.core.snapshot.RuntimeSnapshotSections
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * `snapshot validate|expand|diff` 的 CLI 薄壳（MODERN-T3 R1 第二波）。
 *
 * 本对象只做三件事：读文件、拼输出、给退出码；解析 / 校验 / 展开 / 对比全在 [SnapSidecar]
 * （纯层，可离线单测）。**离线铁律**：不调 [Assembler.assemble]、不读 `--erbc/--game`、
 * 不建沙箱 —— 有一条坏快照要诊断时，不该先被游戏根或语料装载挡住。
 *
 * ## 退出码（CONTRACT-MODERN-T3-R1 §3.2）
 *
 * | 码 | 含义 |
 * |---|---|
 * | 0 | PASS（diff：报告已产出；`same=true/false` 均 0） |
 * | 1 | 用法错误（[CliError] 由 [Cli.run] 折回） |
 * | 2 | MISSING：快照主文件不存在 |
 * | 3 | INVALID：sidecar 不可解析 / schema 违规，或二进制容器版本 ≠ 1808 |
 *
 * ## sidecar 缺席
 *
 * `validate` / `expand` 不判 INVALID（`restore` 对无 sidecar 的快照仍能还原变量面，
 * CmdSnap.kt:141-154）：报 warning、照常输出主文件事实。`diff` 允许某一侧缺席。
 */
object CmdSnapAnalyze {

    /** 退出码（与 Main.kt usage 的 §3.2 表同源）。 */
    const val EXIT_PASS = 0
    const val EXIT_MISSING = 2
    const val EXIT_INVALID = 3

    /** sidecar 读取三态：缺席 / 可解析 / 损坏。 */
    private sealed class SidecarRead {
        data object Absent : SidecarRead()
        data class Parsed(val doc: SnapSidecar.JsonValue.Obj) : SidecarRead()
        data class Broken(val message: String) : SidecarRead()
    }

    /** `snapshot validate [<快照>]`。 */
    fun runValidate(a: Args, out: PrintStream): Int {
        val main = mainPath(a, 2)
        val bytes = readMain(main, out) ?: return EXIT_MISSING
        if (RuntimeSnapshot.isSnapshot(bytes)) return runV1Validate(main, bytes, out)
        val probe = SnapSidecar.detectContainer(bytes)
        val sidePath = CmdSnap.sidecarPath(main)
        val side = readSidecar(sidePath)

        var invalid = bytes.isEmpty() || probe.problems.isNotEmpty()
        val body = ArrayList<String>()
        body += "main=$main"
        body += "mainBytes=${bytes.size}"
        body += "mainSha256=${SnapSidecar.sha256Hex(bytes)}"
        body += "container=${SnapSidecar.containerLabel(probe)}"
        probe.version?.let { body += "version=$it" }
        body += "sidecar=${if (side == SidecarRead.Absent) "absent" else sidePath.toString()}"
        if (bytes.isEmpty()) body += "problem=主文件为空（0 字节）"
        for (p in probe.problems) body += "problem=$p"
        for (w in probe.warnings) body += "warning=$w"

        when (side) {
            SidecarRead.Absent ->
                body += "warning=sidecar 缺席（变量面仍可由 restore 还原，CmdSnap.kt:141-154）"

            is SidecarRead.Broken -> {
                invalid = true
                body += "problem=sidecar 不可解析: ${side.message}"
            }

            is SidecarRead.Parsed -> {
                val v = SnapSidecar.validate(side.doc)
                if (!v.ok) invalid = true
                body += "format=${v.format ?: "?"}"
                body += "createdAt=${v.createdAt ?: ""}"
                body += "commit=${v.commit ?: ""}"
                body += "randSeed=${v.randSeed ?: "?"}"
                body += "funcDepth=${v.funcDepth ?: "?"}"
                body += "rngState.len=${v.rngLen}"
                body += "consoleTail.len=${v.consoleTailLen}"
                body += "funcStack.len=${v.funcStackLen}"
                for (p in v.problems) body += "problem=$p"
                for (w in v.warnings) body += "warning=$w"
            }
        }
        out.println("status=${statusOf(invalid)}")
        for (line in body) out.println(line)
        return if (invalid) EXIT_INVALID else EXIT_PASS
    }

    /** `snapshot expand [<快照>] [--out <文件>]`。 */
    fun runExpand(a: Args, out: PrintStream): Int {
        val main = mainPath(a, 2)
        val bytes = readMain(main, out) ?: return EXIT_MISSING
        if (RuntimeSnapshot.isSnapshot(bytes)) return runV1Expand(a, main, bytes, out)
        val probe = SnapSidecar.detectContainer(bytes)
        val sidePath = CmdSnap.sidecarPath(main)
        val side = readSidecar(sidePath)
        val info = SnapSidecar.MainInfo(main.toString(), bytes.size.toLong(), SnapSidecar.sha256Hex(bytes), probe)

        val lines: List<String> = when (side) {
            is SidecarRead.Parsed -> SnapSidecar.expand(side.doc, sidePath.toString(), info)

            SidecarRead.Absent -> mainFacts(
                "PASS",
                info,
                "sidecar=absent",
                listOf("vars=not-expanded(offline; use restore)") +
                    probe.problems.map { "problem=$it" } +
                    probe.warnings.map { "warning=$it" } +
                    "warning=sidecar 缺席（只展开主文件事实；变量面仍需 restore）",
            )

            is SidecarRead.Broken -> mainFacts(
                "INVALID",
                info,
                "sidecar=$sidePath",
                listOf("vars=not-expanded(offline; use restore)") +
                    probe.problems.map { "problem=$it" } +
                    probe.warnings.map { "warning=$it" } +
                    "problem=sidecar 不可解析: ${side.message}",
            )
        }
        val status = lines.first()
        val text = lines.joinToString("\n")
        val rawOut = a.value("--out")
        if (rawOut == null) {
            out.println(text)
        } else {
            val target = Path.of(rawOut).toAbsolutePath().normalize()
            target.parent?.let { Files.createDirectories(it) }
            Files.writeString(target, text + "\n", Charsets.UTF_8)
            out.println("$status out=$target")
        }
        return if (status == "status=PASS") EXIT_PASS else EXIT_INVALID
    }

    /** `snapshot diff <快照a> <快照b>`。 */
    fun runDiff(a: Args, out: PrintStream): Int {
        val aRaw = a.positional.getOrNull(2)
            ?: throw CliError("diff 需要两个快照路径: snapshot diff <快照a> <快照b>")
        val bRaw = a.positional.getOrNull(3)
            ?: throw CliError("diff 缺少第二个快照路径: snapshot diff <快照a> <快照b>")
        val aMainPath = Path.of(aRaw).toAbsolutePath().normalize()
        val bMainPath = Path.of(bRaw).toAbsolutePath().normalize()
        val aBytes = readMain(aMainPath, out) ?: return EXIT_MISSING
        val bBytes = readMain(bMainPath, out) ?: return EXIT_MISSING

        if (RuntimeSnapshot.isSnapshot(aBytes) && RuntimeSnapshot.isSnapshot(bBytes)) {
            return runV1Diff(aMainPath, aBytes, bMainPath, bBytes, out)
        }

        val aSide = readSidecar(CmdSnap.sidecarPath(aMainPath))
        val bSide = readSidecar(CmdSnap.sidecarPath(bMainPath))
        val aInfo = SnapSidecar.MainInfo(
            aMainPath.toString(),
            aBytes.size.toLong(),
            SnapSidecar.sha256Hex(aBytes),
            SnapSidecar.detectContainer(aBytes),
        )
        val bInfo = SnapSidecar.MainInfo(
            bMainPath.toString(),
            bBytes.size.toLong(),
            SnapSidecar.sha256Hex(bBytes),
            SnapSidecar.detectContainer(bBytes),
        )
        val lines = ArrayList<String>()
        lines += SnapSidecar.diff(
            (aSide as? SidecarRead.Parsed)?.doc,
            (bSide as? SidecarRead.Parsed)?.doc,
            aInfo,
            bInfo,
        )
        for ((label, side) in listOf("a" to aSide, "b" to bSide)) {
            if (side is SidecarRead.Broken) lines += "problem=sidecar.$label 不可解析: ${side.message}"
        }
        for ((label, probe) in listOf("a" to aInfo.probe, "b" to bInfo.probe)) {
            for (w in probe.warnings) lines += "warning=main.$label $w"
        }
        // status 行由 [SnapSidecar.diff] 放在首行；sidecar 不可解析只有 CLI 知道，覆写首行。
        val broken = aSide is SidecarRead.Broken || bSide is SidecarRead.Broken
        val status = if (broken) "status=INVALID" else lines[0]
        out.println(status)
        for (i in 1 until lines.size) out.println(lines[i])
        return if (status == "status=PASS") EXIT_PASS else EXIT_INVALID
    }

    // ------------------------------------------------------------------
    // IO 辅助
    // ------------------------------------------------------------------

    /** 位置参数取快照主文件路径；缺省 = `<work>/snapshot.dbg`（与 `snapshot` 抓取同口径）。 */
    private fun mainPath(a: Args, index: Int): Path =
        Path.of(a.positional.getOrNull(index) ?: CmdSnap.defaultSnapPath(a)).toAbsolutePath().normalize()

    /** 读主文件；不存在 → 打印 MISSING 并返回 null（调用方折成退出码 2）。 */
    private fun readMain(main: Path, out: PrintStream): ByteArray? {
        if (!Files.isRegularFile(main)) {
            out.println("status=MISSING")
            out.println("main=$main")
            out.println("reason=快照主文件不存在")
            return null
        }
        return Files.readAllBytes(main)
    }

    /** 读 sidecar（单行 JSON）；缺席 = [SidecarRead.Absent]，解析失败 = [SidecarRead.Broken]。 */
    private fun readSidecar(path: Path): SidecarRead {
        if (!Files.isRegularFile(path)) return SidecarRead.Absent
        return try {
            val line = Files.readString(path, Charsets.UTF_8).lineSequence().firstOrNull() ?: ""
            SidecarRead.Parsed(SnapSidecar.parse(line))
        } catch (e: SnapSidecar.SnapFormatException) {
            SidecarRead.Broken(e.message ?: "格式错误")
        } catch (e: Exception) {
            SidecarRead.Broken("读取失败: ${e.message}")
        }
    }

    /** sidecar 缺席 / 损坏时的 `expand` 输出（主文件事实照给，变量面不展开）。 */
    private fun mainFacts(
        status: String,
        info: SnapSidecar.MainInfo,
        sidecarLine: String,
        extras: List<String>,
    ): List<String> {
        val lines = ArrayList<String>()
        lines += "status=$status"
        lines += "main=${info.path}"
        lines += "mainBytes=${info.bytes}"
        lines += "mainSha256=${info.sha256}"
        lines += "container=${SnapSidecar.containerLabel(info.probe)}"
        lines += sidecarLine
        lines.addAll(extras)
        return lines
    }

    // ------------------------------------------------------------------
    // v1 容器分支（契约 S1 / S2）
    //
    // 分流点只在「读到主文件字节之后」：命不中 v1 魔数的字节继续走上面的 v0
    // sidecar 路径，那一段源码与输出逐字未动。命中的走容器解码，坏档一律折成
    // status=INVALID 加退出码 3，绝不把解码异常抛到 CLI 之外。
    // ------------------------------------------------------------------

    private fun runV1Validate(main: Path, bytes: ByteArray, out: PrintStream): Int {
        val facts = ArrayList<String>()
        facts += "main=$main"
        facts += "mainBytes=${bytes.size}"
        facts += "mainSha256=${SnapSidecar.sha256Hex(bytes)}"
        facts += "container=rtsnap1"
        facts += "version=${RuntimeSnapshot.VERSION}"
        return try {
            val snap = RuntimeSnapshot.decode(bytes)
            facts += "buildStamp=${flatten(snap.header.buildStamp)}"
            facts += "createdAt=${flatten(snap.header.createdAt)}"
            facts += "saveIdentity=${flatten(snap.header.saveIdentity)}"
            facts += "truncated=${snap.truncated}"
            facts += "sections=${snap.sectionNames().joinToString(",")}"
            for (section in snap.sections) facts += "section.${section.name}.fields=${section.fields.size}"
            for (known in RuntimeSnapshotSections.KNOWN_SECTIONS) {
                if (snap.section(known) == null) facts += "section.$known=absent"
            }
            out.println("status=PASS")
            for (line in facts) out.println(line)
            EXIT_PASS
        } catch (e: RuntimeSnapshot.SnapshotFormatException) {
            out.println("status=INVALID")
            for (line in facts) out.println(line)
            out.println("problem=${flatten(e.message ?: "快照不可解析")}")
            EXIT_INVALID
        }
    }

    private fun runV1Expand(a: Args, main: Path, bytes: ByteArray, out: PrintStream): Int {
        val lines = ArrayList<String>()
        return try {
            val snap = RuntimeSnapshot.decode(bytes)
            lines += "status=PASS"
            lines += "container=rtsnap1"
            lines += "version=${RuntimeSnapshot.VERSION}"
            lines += "main=$main"
            lines += "mainBytes=${bytes.size}"
            lines += "mainSha256=${SnapSidecar.sha256Hex(bytes)}"
            lines += "buildStamp=${flatten(snap.header.buildStamp)}"
            lines += "createdAt=${flatten(snap.header.createdAt)}"
            lines += "saveIdentity=${flatten(snap.header.saveIdentity)}"
            lines += "truncated=${snap.truncated}"
            for ((key, value) in snap.header.projectIdentity) lines += "project.$key=${flatten(value)}"
            for ((key, value) in snap.header.extra) lines += "header.$key=${flatten(value)}"
            lines += "sections=${snap.sectionNames().joinToString(",")}"
            for (section in snap.sections) {
                lines += "--- section ${section.name} (${section.fields.size} fields) ---"
                for ((key, value) in section.fields) {
                    lines += "${section.name}.$key=${renderValue(value)}"
                    // 变量节按存档编码器展开：只探存档头，字节块不整段倒到屏上。
                    if (section.name == RuntimeSnapshotSections.VARS && key == "bytes" && value is Value.Blob) {
                        val head = runCatching { SaveFormat.peek(value.bytes) }.getOrNull()
                        if (head != null) {
                            lines += "${section.name}.bytes.saveHeader=binary=${head.binary} gzip=${head.gzip} " +
                                "version=${head.version} fileType=${head.fileType} uniqueCode=${head.uniqueCode} " +
                                "scriptVersion=${head.scriptVersion} text=${flatten(head.text)}"
                        }
                    }
                }
            }
            for (known in RuntimeSnapshotSections.KNOWN_SECTIONS) {
                if (snap.section(known) == null) lines += "section.$known=absent"
            }
            writeExpandOut(a, lines, out)
            EXIT_PASS
        } catch (e: RuntimeSnapshot.SnapshotFormatException) {
            lines.clear()
            lines += "status=INVALID"
            lines += "container=rtsnap1"
            lines += "main=$main"
            lines += "problem=${flatten(e.message ?: "快照不可解析")}"
            writeExpandOut(a, lines, out)
            EXIT_INVALID
        }
    }

    private fun runV1Diff(
        aMain: Path,
        aBytes: ByteArray,
        bMain: Path,
        bBytes: ByteArray,
        out: PrintStream,
    ): Int {
        val a = try {
            RuntimeSnapshot.decode(aBytes)
        } catch (e: RuntimeSnapshot.SnapshotFormatException) {
            out.println("status=INVALID")
            out.println("main.a=$aMain")
            out.println("problem.a=${flatten(e.message ?: "快照不可解析")}")
            return EXIT_INVALID
        }
        val b = try {
            RuntimeSnapshot.decode(bBytes)
        } catch (e: RuntimeSnapshot.SnapshotFormatException) {
            out.println("status=INVALID")
            out.println("main.b=$bMain")
            out.println("problem.b=${flatten(e.message ?: "快照不可解析")}")
            return EXIT_INVALID
        }
        val result = RuntimeSnapshotDiff.diff(a, b)
        out.println("status=PASS")
        out.println("container=rtsnap1")
        out.println("main.a=$aMain")
        out.println("main.b=$bMain")
        if (result.same) {
            out.println("一致")
        } else {
            val first = result.first!!
            out.println("first.section=${first.section}")
            out.println("first.path=${first.path}")
            out.println("first.a=${flatten(first.a)}")
            out.println("first.b=${flatten(first.b)}")
            for ((index, d) in result.divergences.withIndex()) {
                out.println("diff[$index] section=${d.section} path=${d.path} a=${flatten(d.a)} b=${flatten(d.b)}")
            }
        }
        out.println("same=${result.same}")
        return EXIT_PASS
    }

    /** v1 字段值的行式展示：字节块只给长度与摘要，文本与列表给可定位短串。 */
    private fun renderValue(value: Value): String = when (value) {
        is Value.Text -> flatten(value.value)
        is Value.Int64 -> value.value.toString()
        is Value.Bool -> value.value.toString()
        is Value.TextList -> "textList(len=${value.items.size})[" + value.items.joinToString("|") { flatten(it) } + "]"
        is Value.IntList -> "intList(len=${value.items.size})[" + value.items.joinToString(",") + "]"
        is Value.Blob -> "bytes=${value.bytes.size} sha256=${SnapSidecar.sha256Hex(value.bytes).take(16)}…"
    }

    /** 行式输出里把回车折成空格、换行折成字面转义，避免一条诊断被折成多行。 */
    private fun flatten(raw: String): String =
        raw.replace("\r", " ").replace("\n", "\\n").replace("\t", " ")

    /** expand 的 --out 落盘（与 v0 同口径：首行状态加单行回执）。 */
    private fun writeExpandOut(a: Args, lines: List<String>, out: PrintStream) {
        val status = lines.first()
        val text = lines.joinToString("\n")
        val rawOut = a.value("--out")
        if (rawOut == null) {
            out.println(text)
        } else {
            val target = Path.of(rawOut).toAbsolutePath().normalize()
            target.parent?.let { Files.createDirectories(it) }
            Files.writeString(target, text + "\n", Charsets.UTF_8)
            out.println("$status out=$target")
        }
    }

    private fun statusOf(invalid: Boolean): String = if (invalid) "INVALID" else "PASS"
}
