package com.eratw.port.core.diag

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.err.erbDisplayLine
import com.eratw.port.core.stmt.StmtParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.ZoneOffset

/**
 * [ErrorReport] 定向单测（方案① 报错收集）：钉住分节结构、脚本位置提取、
 * 截断口径与文件名规则。报告格式是「玩家 → 开发者」的契约，格式漂移即返工。
 */
class ErrorReportTest {

    // ---- 渲染：分节齐全 -------------------------------------------------

    @Test
    fun 渲染包含类型时间设备错误与结尾栏() {
        val report = ErrorReport(
            kind = "脚本运行报错",
            timestampText = "2026-10-27 12:34:56",
            headerLines = listOf("app 版本：0.1.0-skeleton (1)", "设备：vivo V2199A"),
            errorType = "com.eratw.port.core.err.CodeEE",
            errorMessage = "变量 CFLAG 的第 1 参数 200 越界",
            scriptLocation = "UPDATE_AND_SELECTION_OF_KOJO_MODDING 行358",
        )
        val text = report.render()
        assertTrue(text.contains("报告类型：脚本运行报错"))
        assertTrue(text.contains("报告时间：2026-10-27 12:34:56"))
        assertTrue(text.contains("app 版本：0.1.0-skeleton (1)"))
        assertTrue(text.contains("设备：vivo V2199A"))
        assertTrue(text.contains("类型：com.eratw.port.core.err.CodeEE"))
        assertTrue(text.contains("信息：变量 CFLAG 的第 1 参数 200 越界"))
        assertTrue(text.contains("脚本位置：UPDATE_AND_SELECTION_OF_KOJO_MODDING 行358"))
        assertTrue(text.contains("报告结束"))
        assertTrue(text.contains("不含存档数据与变量取值"))
    }

    @Test
    fun 空小节不产出空标题也不出现null字样() {
        val text = ErrorReport(
            kind = "应用崩溃",
            timestampText = "2026-10-27 00:00:00",
            errorType = "java.lang.OutOfMemoryError",
            errorMessage = "",
        ).render()
        assertFalse(text.contains("[ERB 调用栈与 VM 现场]"))
        assertFalse(text.contains("[屏上最后的内容"))
        assertFalse(text.contains("[补充现场]"))
        assertFalse(text.contains("null"))
    }

    // ---- 截断口径：尾部保留 ---------------------------------------------

    @Test
    fun 屏上原文超限只保留最后N行且单行超长截断() {
        val tail = (1..(ErrorReport.CONSOLE_TAIL_MAX_LINES + 30)).map { "L$it" }
        val longLine = "很".repeat(ErrorReport.LINE_MAX_CHARS + 100)
        val text = ErrorReport(
            kind = "脚本运行报错",
            timestampText = "t",
            errorType = "E",
            errorMessage = "",
            consoleTailLines = tail + listOf(longLine),
        ).render()
        // 尾部保留：最后一行（超长行截断后的残段）在，第一行不在。
        assertTrue(text.contains("（超长截断）"))
        assertFalse(text.contains("\nL1\n"))
        val keptCount = tail.count { text.contains("\n$it\n") }
        assertEquals(ErrorReport.CONSOLE_TAIL_MAX_LINES - 1, keptCount)
        // 长行不整行进报告。
        assertFalse(text.contains("很".repeat(ErrorReport.LINE_MAX_CHARS + 100)))
        val clippedCount = Regex("很").findAll(text).count()
        assertEquals(ErrorReport.LINE_MAX_CHARS, clippedCount)
    }

    @Test
    fun 调用栈与补充现场各自按上限尾部保留() {
        val stack = (1..(ErrorReport.STACK_MAX_LINES + 5)).map { "#$it fn$it" }
        val extra = (1..(ErrorReport.EXTRA_MAX_LINES + 5)).map { "x$it" }
        val text = ErrorReport(
            kind = "启动失败",
            timestampText = "t",
            errorType = "E",
            errorMessage = "",
            vmStackLines = stack,
            extraSectionLines = extra,
        ).render()
        assertFalse(text.contains("\n#1 "))
        assertTrue(text.contains("\n#${ErrorReport.STACK_MAX_LINES + 5} fn${ErrorReport.STACK_MAX_LINES + 5}"))
        assertFalse(text.contains("\nx1\n"))
        assertTrue(text.contains("\nx${ErrorReport.EXTRA_MAX_LINES + 5}"))
    }

    // ---- 多物理行读数（2026-11-17 lane-g-mem）：内嵌换行不许被当成一行 400 字剪 ----

    /**
     * 真机量级的合成读数：**四键（节点块/逐出/规范/串池）都在尾部**、整串 > [ErrorReport.LINE_MAX_CHARS]。
     *
     * 数字取自 2026-11-17 真机三份 OOM 报告（`1794882330035-eratw-report-20261116-114529.txt:181`
     * 那行的形态：装载段 + 运行段 + 行缓冲段 + 图形段 + 代码缓存段），长度按 425-435 字量级复现。
     * 不用真 census 现算：本用例证的是「断行 + 单行闸」这条缝，与度量源字段表无关。
     */
    private fun deviceShapedCensusLine(): String =
        "Java 堆结构账面（估算口径，非 dominator tree；对象头/对齐不计=低估，串按 UTF-16=高估串本体）" +
            " ｜装载：函数 119163 指令 5095619 全局槽 0 表达式 357793 语句 1940630" +
            " 物化语句 41887 物化函数 6920 扁平片驻/建 0/0" +
            " ｜指令流常驻 0MiB 行表 0MiB 实参索引 1MiB 串池 18405 条/约 0MiB" +
            " ｜运行：全局整数(声明) 15MiB 全局整数(实占) 15MiB 全局串格 0MiB 角色 168/容 193" +
            " 整数(容量行) 40MiB CSTR 0MiB 每角色整数 220096B 容量冗余(已含上两列) 5MiB 驻留串表 81959 条" +
            " ｜行缓冲：显示 1500 逻辑 74516 段 2910 待发缓冲 空" +
            " ｜图形 Java：正号段像素 70MiB 负号段 CSV 22MiB" +
            " ｜代码缓存：节点块 256 逐出 16 规范 131072 串池 4096"

    /** 四个键名（2026-11-15 补账的代码缓存段；真机上恒被 400 字闸切掉，正是本笔要修的缺口）。 */
    private val codeCacheKeys = listOf("｜代码缓存：节点块 256", " 逐出 16", " 规范 131072", " 串池 4096")

    /**
     * 把一条账面按**段界 `｜`** 断成多物理行，断点贪心装行（每行不超 [budget]）。
     *
     * 这是度量源侧 `LiveSetCensus.Census.renderReportLines` 的等价极小实现：测试不去拼真 census
     * （那会把用例耦合到 29 个字段上），只复现「一条逻辑读数 → 多物理行」这个形状。
     */
    private fun censusLineBrokenAtSections(line: String, budget: Int = 320): List<String> {
        val segs = line.split(" ｜").let { listOf(it.first()) + it.drop(1).map { s -> " ｜$s" } }
        val out = ArrayList<String>()
        var cur = ""
        for (s in segs) {
            if (cur.isNotEmpty() && cur.length + s.length > budget) {
                out.add(cur)
                cur = "Java 堆结构账面（续）"
            }
            cur += s
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /** 报告里 `[补充现场]` 一节的正文行（节标题后到下一个分隔栏 / 空行为止）。 */
    private fun extraSectionOf(text: String): List<String> =
        text.lines().let { lines ->
            val start = lines.indexOfFirst { it == "[补充现场]" }
            if (start < 0) return emptyList()
            lines.drop(start + 1)
                .takeWhile { l -> l.isNotEmpty() && !l.all { it == '-' } && !l.all { it == '=' } }
        }

    @Test
    fun 多物理行读数的四个键都进报告且每行不超单行闸() {
        val line = deviceShapedCensusLine()
        assertTrue(
            line.length > ErrorReport.LINE_MAX_CHARS,
            "合成串必须比单行闸长，否则这条缝根本没被测到：${line.length}",
        )
        // 度量源侧（GameHost.memoryCensusReportLine 同款）把一条逻辑读数断成多物理行：
        // 断点就是账面自己的段界 `｜`，续行仍以 `Java 堆结构账面` 打头（保 triage 的 startswith）。
        val broken = censusLineBrokenAtSections(line)
        assertEquals(2, broken.size, "合成串按段应断成两行：$broken")
        val text = ErrorReport(
            kind = "应用崩溃",
            timestampText = "t",
            errorType = "java.lang.OutOfMemoryError",
            errorMessage = "",
            extraSectionLines = listOf(broken.joinToString("\n")),
        ).render()
        // 四个键一个不少。
        for (k in codeCacheKeys) assertTrue(k in text, "读数没进报告：`$k`")
        // 每一读数行都不超单行闸（截断标记不出现 = 没有任何一行被剪）。
        val section = extraSectionOf(text)
        assertEquals(2, section.size, "补充现场应当正好两行读数：$section")
        for (l in section) {
            assertTrue(
                l.length <= ErrorReport.LINE_MAX_CHARS,
                "单行超闸会被剪尾（长度 ${l.length}）：$l",
            )
            assertFalse("超长截断" in l, "读数行被单行闸剪了：$l")
        }
    }

    /**
     * 反向证明：**同一条读数整段当一行**进去，尾部四键全丢 —— 这正是 2026-11-17 真机的样子
     * （`代码缓存：节点块 …（超长截断）`）。
     *
     * 这条与上一条共用同一份合成串：差别只在「有没有先摊平成物理行」。
     * 若哪天有人把 `extraSectionLines` 的摊平去掉、或把度量源改回 `render()` 单行，上一条红。
     */
    @Test
    fun 反向_整段当一行进报告则尾部四键被单行闸剪掉() {
        val line = deviceShapedCensusLine()
        val text = ErrorReport(
            kind = "应用崩溃",
            timestampText = "t",
            errorType = "java.lang.OutOfMemoryError",
            errorMessage = "",
            extraSectionLines = listOf(line),
        ).render()
        assertTrue("（超长截断）" in text, "整段一行必被单行闸剪：本用例的前提失效了")
        assertTrue("｜装载：函数 " in text, "被剪的必须是尾部，不是头部")
        val surviving = codeCacheKeys.count { it in text }
        assertTrue(
            surviving < codeCacheKeys.size,
            "整段一行时尾部四键应当有键丢失，否则单行闸没咬到、本用例证不到东西（存活 $surviving/${codeCacheKeys.size}）",
        )
    }

    // ---- 度量源读数单独成节（2026-11-17 lane-k）：堆账面别再被补充现场的尾窗挤掉 ----

    /** `devtools/triage_reports.py:154` 的 `HEAP_CENSUS_REPORT_PREFIX`，逐字复制，改一处即红。 */
    private val HEAP_CENSUS_PREFIX = "Java 堆结构账面"

    /**
     * 真机满档形状的填充：装载轨迹 25 行 + 冻结现场 31 行 + 心跳/留痕 44 行 = 100 行，
     * 全部走 [ErrorReport.extraSectionLines]（这一节按 [ErrorReport.EXTRA_MAX_LINES] 尾部保留）。
     * 行数取「远超 80」是因为缺陷的形状就是「叠加过窗」，具体数字不影响判据。
     */
    private fun crowdOutFillers(): List<String> =
        (1..25).map { "load-trace $it ERB/MAIN.ERB" } +
            (1..31).map { "stall-scene $it" } +
            (1..44).map { "heartbeat $it" }

    /** 报告里 `[度量源读数]` 一节的正文行（判法与 [extraSectionOf] 同形）。 */
    private fun metricSectionOf(text: String): List<String> =
        text.lines().let { lines ->
            val start = lines.indexOfFirst { it.startsWith("[度量源读数]") }
            if (start < 0) return emptyList()
            lines.drop(start + 1)
                .takeWhile { l -> l.isNotEmpty() && !l.all { it == '-' } && !l.all { it == '=' } }
        }

    @Test
    fun 度量源读数单独成节_满档现场行不再挤掉堆账面整段() {
        val broken = censusLineBrokenAtSections(deviceShapedCensusLine())
        val fillers = crowdOutFillers()
        assertTrue(fillers.size > ErrorReport.EXTRA_MAX_LINES, "填充必须超过补充现场窗口，否则测不到挤压")
        val text = ErrorReport(
            kind = "应用崩溃",
            timestampText = "t",
            errorType = "java.lang.OutOfMemoryError",
            errorMessage = "",
            extraSectionLines = fillers,
            metricSectionLines = broken,
        ).render()
        // 1) 整段读数一条不少：段 5「代码缓存」四键全在（0.2.46 真机报告正是缺这一段）。
        for (k in codeCacheKeys) assertTrue(k in text, "读数没进报告：`$k`")
        // 2) 逐行仍守单行闸：没有任何一行被「超长截断」剪尾（不许靠抬 400 字闸换存活）。
        val section = metricSectionOf(text)
        assertEquals(broken.size, section.size, "度量源读数节应当正好 ${broken.size} 行：$section")
        for (l in section) {
            assertTrue(
                l.length <= ErrorReport.LINE_MAX_CHARS,
                "单行超闸会被剪尾（长度 ${l.length}）：$l",
            )
            assertFalse("超长截断" in l, "读数行被单行闸剪了：$l")
        }
        // 3) triage 判据仍成立：每一行都以 Java 堆结构账面 打头（续行同前缀）。
        for (l in section) {
            assertTrue(
                l.startsWith(HEAP_CENSUS_PREFIX),
                "行首不是账面前缀，triage_reports.py:435 的 startswith 会漏判：$l",
            )
        }
        // 4) 提前：它在 [补充现场] 之前（现场行再多也压不到它）。
        val metricAt = text.lines().indexOfFirst { it.startsWith("[度量源读数]") }
        val extraAt = text.lines().indexOfFirst { it == "[补充现场]" }
        assertTrue(metricAt in 0 until extraAt, "度量源读数节必须排在补充现场之前：metric=$metricAt extra=$extraAt")
        // 5) 补充现场自己的窗口口径没被改（仍是 80 行尾部保留）。
        assertEquals(ErrorReport.EXTRA_MAX_LINES, extraSectionOf(text).size, "补充现场窗口口径不许顺带改动")
    }

    /**
     * 反向证明：**同一批账面行只走 [ErrorReport.extraSectionLines]**（即接线前的老路），
     * 100 行填充就把它们整段挤出 80 行尾窗 —— 这就是 0.2.46 真机报告「账面止于图形段、
     * 没有代码缓存段」的机制。
     *
     * 若哪天有人把 `metricSectionLines` 并回补充现场（或把它也塞进那个尾窗），
     * 上一条用例红；本用例红的方式则是「读数居然还在」，说明窗口/位置又被改回去了。
     */
    @Test
    fun 反向_账面只走补充现场时被满档尾窗整段挤掉() {
        val broken = censusLineBrokenAtSections(deviceShapedCensusLine())
        val text = ErrorReport(
            kind = "应用崩溃",
            timestampText = "t",
            errorType = "java.lang.OutOfMemoryError",
            errorMessage = "",
            extraSectionLines = broken + crowdOutFillers(),
        ).render()
        val survivors = codeCacheKeys.count { it in text }
        assertEquals(0, survivors, "老路上四键应当一个不剩（存活 $survivors/${codeCacheKeys.size}）")
        assertFalse(
            text.lines().any { it.startsWith(HEAP_CENSUS_PREFIX) },
            "老路上账面行整段被挤出报告：这正是本笔要修的形状",
        )
        // 填充的尾部行照旧在（证明窗口本身没坏，被剪的是账面）。
        assertTrue(text.contains("\nheartbeat 44"))
    }


    @Test
    fun 构建标识与产物指纹成节且指纹只取前12位() {
        val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val text = ErrorReport(
            kind = "脚本运行报错",
            timestampText = "2026-10-28 09:00:00",
            headerLines = listOf("app 版本：0.2.0 (versionCode 2)"),
            buildStamp = "deadbeef1234",
            programSha256 = sha,
            channelStamp = "v1;endpoint=eratw-android.asia;token=on;mirrors=off",
            errorType = "E",
            errorMessage = "",
        ).render()
        assertTrue(text.contains("[构建与产物标识]"))
        assertTrue(text.contains("构建标识：deadbeef1234"))
        assertTrue(text.contains("产物指纹：${sha.take(ErrorReport.PROGRAM_SHA_HEAD_CHARS)}"))
        // OBS2-L7 接线：上报通道读数进节，装机后直读「报不报、往哪个 host 报」。
        assertTrue(text.contains("上报通道：v1;endpoint=eratw-android.asia;token=on;mirrors=off"))
        // 只展示前 12 位：完整 64 位串不得出现在报告里。
        assertFalse(text.contains(sha))
        // 隐私口径不放松：新节不引入变量取值/存档内容，说明行原样在。
        assertTrue(text.contains("不含存档数据与变量取值"))
    }

    @Test
    fun 标识缺失降级为未知且报告照常渲染() {
        val text = ErrorReport(
            kind = "应用崩溃",
            timestampText = "2026-10-28 09:00:00",
            buildStamp = null,
            programSha256 = "   ",
            errorType = "java.lang.OutOfMemoryError",
            errorMessage = "",
        ).render()
        assertTrue(text.contains("构建标识：${ErrorReport.UNKNOWN_MARK}"))
        assertTrue(text.contains("产物指纹：${ErrorReport.UNKNOWN_MARK}"))
        assertTrue(text.contains("上报通道：${ErrorReport.UNKNOWN_MARK}"), "L4 之前的包没接 stamp，照常渲染成未知")
        assertTrue(text.contains("报告结束"))
    }

    @Test
    fun 新节不挡住上报端点校验的标记行() {
        // tools/report-endpoint/worker.js 的 REPORT_MARKER 判据是「正文包含该串」；
        // 标记行必须仍在报告最前（第 1 行分隔栏之后的第 2 行），新增小节一律排在它后面。
        val text = ErrorReport(
            kind = "脚本运行报错",
            timestampText = "t",
            headerLines = listOf("app 版本：0.2.0 (versionCode 2)"),
            buildStamp = "abc123",
            programSha256 = "0123456789abcdef0123456789abcdef",
            errorType = "E",
            errorMessage = "",
        ).render()
        val lines = text.lines()
        assertEquals("=".repeat(64), lines[0])
        assertEquals("eraTW 安卓移植版 错误报告", lines[1])
        assertTrue(lines.indexOfFirst { it.startsWith("[构建与产物标识]") } > 1)
    }

    // ---- scriptLocationOf：因果链提取 ------------------------------------

    /**
     * 「行N」必须是 **1 基**（与原版屏上/报告对齐；组长裁定 2026-11-04）。
     *
     * 钉法：喂的是内部 0 基下标 369（= `Program.lines` 的存值 = 物理第 370 行），
     * 报告要写「行370」。旧实现直接把 0 基下标打出去，读成「行369」——那比原版少一行，
     * 也正是端点上 `GMF_METHOD_RESTORE 行89` 实际指向 `幻想乡地图重修.ERB:90` 的原因。
     * 归一只有一个入口 [CodeEE.displayLine]，本函数不许再加一。
     */
    @Test
    fun 脚本位置从因果链内层CodeEE提取() {
        val inner = CodeEE("越界", funcName = "GET_TALENTNAME", line = 369, pc = 682671)
        val outer = IllegalStateException("装载失败", inner)
        assertEquals("GET_TALENTNAME 行370", ErrorReport.scriptLocationOf(outer))
        // 键面不动：字段仍是那条 0 基下标（产物行号表 / pc↔行号对照用的就是它）。
        assertEquals(369, inner.line)
    }

    /**
     * 单一归一点自证：[CodeEE.displayLine] 是全工程唯一一处 0 基 → 1 基的换算，
     * 报告这一侧只是搬运。负数（无位置）必须原样为负，否则「没有位置」会被伪造成「第 0 行」。
     */
    @Test
    fun 行号归一只在displayLine一处且无位置仍为负() {
        assertEquals(1, CodeEE("首行", line = 0).displayLine, "0 基下标 0（物理第 1 行）→ 行1")
        assertEquals(70, CodeEE("越界", line = 69).displayLine, "端点最大簇的下标 69 → 行70")
        assertEquals(-1, CodeEE("空位置").displayLine, "line 缺省 -1 ⇒ 不许折成 0")
        assertEquals(-1, CodeEE("空位置", line = -1, pc = 4).displayLine)
        // 有名无行 → 只出名，不补一个假行号；有行无名 → 只出行号。
        assertEquals("ONLY_NAME", ErrorReport.scriptLocationOf(CodeEE("x", funcName = "ONLY_NAME")))
        assertEquals("行1", ErrorReport.scriptLocationOf(CodeEE("x", line = 0)))
    }

    /**
     * 解析失败文案（`core/stmt/ParseSupport.kt` 的「语句解析错误(行:N,列:M)」前缀）与
     * 运行期错误面**同一条**归一：两边都只经 [com.eratw.port.core.err.erbDisplayLine] 一次。
     *
     * 钉的三个数是 2026-11-05 用真语料对拍出来的（4.981 根 `eraTW4.981-261017-EAGLOV3/ERB`、
     * 4.980 根 `eratw-画蛇添足版eratw4.980/ERB`，逐行核对见交付报告的普查表）：
     * - 下标 89 → `幻想乡地图重修.ERB` 物理 90 行 `FOR LOCAL, 0, CHARANUM`（屏上旧读「行89」）
     * - 下标 625 → `SOURCE_EXTRA.ERB` 物理 626 行 `DOWNBASE:ARG:体力 = TOINT(POWER(...))`
     * - 下标 256 → `ResourceManager.ERB` 物理 257 行 `THROW 資源不足`
     */
    @Test
    fun 解析失败文案与运行期行号同一条归一() {
        val parse = StmtParseException(line = 89, col = 3, statementName = "FOR", message = "参数不足")
        assertEquals(90, parse.displayLine, "解析面 0 基 89 → 1 基 90")
        assertTrue(parse.message!!.startsWith("语句解析错误(行:90,列:3)FOR"), "前缀必须带 1 基行号：${parse.message}")
        // 键面仍是 0 基下标（erbcc 的降级清单按它记账）。
        assertEquals(89, parse.line)
        // 同一句脚本从两条路读出来必须同一个数：运行期面走 CodeEE.displayLine。
        assertEquals(parse.displayLine, CodeEE("x", funcName = "FOR", line = parse.line).displayLine)
        // 无位置：解析面给 -1 时文案与属性都不许折成 0 行。
        assertEquals(-1, StmtParseException(-1, 0, "", "无位置").displayLine)
        assertTrue(StmtParseException(-1, 0, "", "无位置").message!!.contains("行:-1"))
    }

    /**
     * 普查里那三条真语料对拍读数（组长裁定 2026-11-04：屏上/报告一律 1 基）。
     * 这里只钉「下标 → 显示行」的算术，语料本体那侧由上面 KDoc 登记的三个现场行号保证。
     */
    @Test
    fun 普查三条对拍读数的换算() {
        for ((fed, shown) in listOf(89 to 90, 625 to 626, 256 to 257)) {
            assertEquals(shown, erbDisplayLine(fed), "0 基下标 $fed → 行$shown")
            assertEquals(shown, CodeEE("x", line = fed).displayLine)
        }
        // 首行与越界哨兵。
        assertEquals(1, erbDisplayLine(0))
        assertEquals(-1, erbDisplayLine(-1))
    }

    @Test
    fun 无位置信息的异常返回null() {
        assertNull(ErrorReport.scriptLocationOf(IllegalStateException("纯 Java 错")))
        // CodeEE 但既无名又无行 → 不伪造位置。
        assertNull(ErrorReport.scriptLocationOf(CodeEE("空位置")))
        assertNull(ErrorReport.scriptLocationOf(null))
    }

    // ---- scriptLocationOf：文件段（MODERN-R7-F1）------------------------

    /**
     * 报告正文的「函数名 文件 行N」：文件段来自 [CodeEE.scriptFile]（语句层捕获点盖章的
     * 源文件显示名，非绝对路径），空格连接、缺哪段省哪段。
     */
    @Test
    fun 脚本位置带文件段时按函数文件行号拼接() {
        assertEquals(
            "F ERB/X.ERB 行370",
            ErrorReport.scriptLocationOf(CodeEE("x", funcName = "F", line = 369, scriptFile = "ERB/X.ERB")),
        )
        // 只有文件 + 行（无函数名）
        assertEquals(
            "ERB/X.ERB 行1",
            ErrorReport.scriptLocationOf(CodeEE("x", line = 0, scriptFile = "ERB/X.ERB")),
        )
        // 只有函数 + 文件（无行号，不补假行号）
        assertEquals(
            "F ERB/X.ERB",
            ErrorReport.scriptLocationOf(CodeEE("x", funcName = "F", scriptFile = "ERB/X.ERB")),
        )
        // 因果链内层同样读文件段
        assertEquals(
            "GET_TALENTNAME ERB/SUB/A.ERB 行370",
            ErrorReport.scriptLocationOf(
                IllegalStateException("装载失败", CodeEE("越界", funcName = "GET_TALENTNAME", line = 369, scriptFile = "ERB/SUB/A.ERB")),
            ),
        )
    }

    /**
     * 无文件时输出逐字节不变（旧三例原样复钉），且同一错误过两次同串（不因重复取用漂移）。
     */
    @Test
    fun 无文件时输出与旧串逐字相同且可重复取用() {
        val noFile = CodeEE("越界", funcName = "GET_TALENTNAME", line = 369, pc = 682671)
        assertEquals("GET_TALENTNAME 行370", ErrorReport.scriptLocationOf(noFile))
        assertEquals("ONLY_NAME", ErrorReport.scriptLocationOf(CodeEE("x", funcName = "ONLY_NAME")))
        assertEquals("行1", ErrorReport.scriptLocationOf(CodeEE("x", line = 0)))
        val withFile = CodeEE("x", funcName = "F", line = 369, scriptFile = "ERB/X.ERB")
        assertEquals(ErrorReport.scriptLocationOf(withFile), ErrorReport.scriptLocationOf(withFile))
        assertEquals("F ERB/X.ERB 行370", ErrorReport.scriptLocationOf(withFile))
        // scriptFile 参与报告正文，不参与 0 基行号（键面仍是 369）。
        assertEquals(369, withFile.line)
    }

    // ---- rootCauseOf：因果链最深层 --------------------------------------

    @Test
    fun 根因取因果链最深层的类与消息() {
        val inner = NoSuchMethodError("android.icu.util.ULocale")
        val mid = ExceptionInInitializerError(inner)
        val outer = RuntimeException("wrapper", mid)
        assertEquals("java.lang.NoSuchMethodError：android.icu.util.ULocale", ErrorReport.rootCauseOf(outer))
    }

    @Test
    fun 无因果链时根因为null且不重复类型行() {
        assertNull(ErrorReport.rootCauseOf(IllegalStateException("裸异常")))
        assertNull(ErrorReport.rootCauseOf(null))
    }

    @Test
    fun 自引用与回环cause不无限走() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b) // a→b→a 回环
        val root = ErrorReport.rootCauseOf(a)
        // 回环里最远走到 b 即停；不死循环、不抛
        assertEquals("java.lang.RuntimeException：b", root)
    }

    @Test
    fun 报告渲染根因行与异常栈节() {
        val report = ErrorReport(
            kind = "应用崩溃",
            timestampText = "2026-10-30 20:42:38",
            errorType = "java.lang.ExceptionInInitializerError",
            errorMessage = "",
            rootCause = ErrorReport.rootCauseOf(
                ExceptionInInitializerError(NoSuchMethodError("boot chart failure")),
            ),
            errorStackLines = listOf(
                "--- 异常：java.lang.ExceptionInInitializerError ---",
                "  at com.eratw.port.android.SettingsActivity.<clinit>",
                "--- 第1 层：java.lang.NoSuchMethodError：boot chart failure ---",
                "  at core.config.ConfigSpecTable.<clinit>",
            ),
        ).render()
        assertTrue(report.contains("根因：java.lang.NoSuchMethodError：boot chart failure"), "根因行必须上屏（vivo 排障缺口）")
        assertTrue(report.contains("[异常栈（含因果链）]"))
        assertTrue(report.contains("  at core.config.ConfigSpecTable.<clinit>"))
    }

    @Test
    fun 异常栈超限尾部保留最深层() {
        val lines = (1..(ErrorReport.ERROR_STACK_MAX_LINES + 40)).map { "帧$it" }
        val rendered = ErrorReport(
            kind = "应用崩溃", timestampText = "t",
            errorType = "X", errorMessage = "", errorStackLines = lines,
        ).render()
        assertTrue(rendered.contains("帧${ErrorReport.ERROR_STACK_MAX_LINES + 40}"), "超限切头保尾（最深层是根因）")
        assertFalse(rendered.contains("帧1\n"), "前 40 行应被截掉")
        assertTrue(!ErrorReport(
            kind = "启动失败", timestampText = "t", errorType = "X", errorMessage = "",
        ).render().contains("[异常栈"), "空 errorStackLines 不产出空节")
    }

    // ---- 文件名 ---------------------------------------------------------

    @Test
    fun 文件名按UTC格式化为固定形状() {
        // 2026-10-27T12:34:56Z = 1793104496000 ms。
        assertEquals(
            "eratw-report-20261027-123456.txt",
            ErrorReport.fileNameAt(1_793_104_496_000L, ZoneOffset.UTC),
        )
    }

    // ---- 系统取证（2026-10-31：14 条「上次异常退出但没 throwable」的唯一判词来源）----

    @Test
    fun 系统取证成节且排在错误之前() {
        val rendered = ErrorReport(
            kind = "上次异常退出",
            timestampText = "t",
            errorType = "（无异常对象，见补充现场）",
            errorMessage = "",
            exitForensicsLines = listOf(
                "本轮足迹：PSS=500MiB RSS=625MiB",
                "退出#0 2026-10-31 10:00:00（5分钟前） 原因=LOW_MEMORY(3)",
                "判读：系统按内存压力回收（lmkd）",
            ),
        ).render()
        assertTrue(rendered.contains("[系统取证：足迹与上次退出]"))
        assertTrue(rendered.contains("原因=LOW_MEMORY(3)"))
        assertTrue(rendered.contains("判读：系统按内存压力回收"))
        assertTrue(
            rendered.indexOf("[系统取证") < rendered.indexOf("[错误]"),
            "取证节要在错误节之前（没有 throwable 的那一类，取证就是全篇最要紧的一段）",
        )
    }

    @Test
    fun 取证缺省时不产出空节且超限时尾部保留() {
        assertFalse(
            ErrorReport(kind = "应用崩溃", timestampText = "t", errorType = "X", errorMessage = "").render()
                .contains("[系统取证"),
            "宿主一行都没采到就不该有空标题",
        )
        val lines = (1..(ErrorReport.EXIT_FORENSICS_MAX_LINES + 6)).map { "取证$it" }
        val rendered = ErrorReport(
            kind = "应用崩溃", timestampText = "t", errorType = "X", errorMessage = "",
            exitForensicsLines = lines,
        ).render()
        assertTrue(rendered.contains("取证${ErrorReport.EXIT_FORENSICS_MAX_LINES + 6}"))
        assertFalse(rendered.contains("\n取证1\n"))
    }
}
