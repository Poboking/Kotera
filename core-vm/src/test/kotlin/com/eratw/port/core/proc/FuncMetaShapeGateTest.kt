package com.eratw.port.core.proc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M13-MEM5 刀 2（[FuncMeta] 表示形态 + [FunctionTable] 查询表形态）的行为闸。
 *
 * 三条既有语义各钉死一条（与 ProcessRuntimeTest / EngineFunctionStubTest /
 * FuncKeyInternTest 的表级闸互补，这里只打 [FunctionTable] 门面）：
 * 1. 事件分桶 ONLY→PRI→NORMAL→LATER 与同桶装载序；
 * 2. 非事件函数**后装载者生效**；
 * 3. 名字大小写归一（IGNORE CASE）。
 *
 * 其余用例钉表示层不变量：position 压实**逐字节无损**（含不可压实的原文必须原样）、
 * equals/hashCode/toString/copy 与 `data class` 时代值语义相同（侧车往返对账与
 * SymbolLinkAudit「首差」文案都在消费这三件套）、标志位位域读值不变。
 */
class FuncMetaShapeGateTest {

    private fun meta(
        index: Int,
        key: String,
        position: String = "",
        defIndex: Int = index,
        isEvent: Boolean = false,
        isOnly: Boolean = false,
        isPri: Boolean = false,
        isLater: Boolean = false,
        isSingle: Boolean = false,
        isMethod: Boolean = false,
        returnsStr: Boolean = false,
        returnsFloat: Boolean = false,
    ) = FuncMeta(
        index = index,
        key = key,
        position = position,
        defIndex = defIndex,
        isEvent = isEvent,
        isOnly = isOnly,
        isPri = isPri,
        isLater = isLater,
        isSingle = isSingle,
        isMethod = isMethod,
        returnsStr = returnsStr,
        returnsFloat = returnsFloat,
    )

    // ---- 三条既有语义 ----

    @Test
    fun `事件分桶 ONLY-PRI-NORMAL-LATER 且同桶保装载序`() {
        val normal2 = meta(2, "EV", isEvent = true, defIndex = 2)
        val normal1 = meta(1, "EV", isEvent = true, defIndex = 1)
        val pri = meta(3, "EV", isEvent = true, isPri = true, defIndex = 3)
        val later = meta(4, "EV", isEvent = true, isLater = true, defIndex = 4)
        val only = meta(5, "EV", isEvent = true, isOnly = true, defIndex = 5)
        val table = FunctionTable(listOf(normal2, normal1, later, only, pri))
        // 外层 ONLY=0 → PRI=1 → NORMAL=2 → LATER=3；同桶内按 defIndex 升序（装载序）。
        // 并列加桶（4.981 LabelDictionary.cs:228-235 四个平行 if；:233 原注「#PRIかつ#LATERなら
        // 二重に登録する。eramakerの仕様」；1.824 同形 LabelDictionary.cs:90-105；在册 B23）：
        // #ONLY 除桶 0 外还落桶 2 ⇒ only 在序里出现两次。桶 2 那份在真 VM 上因 #ONLY 命中即
        // FinishEvent（ProcessState.cs:312-347）不可达，但投桶必须照 C# —— 否则 #PRI+#LATER
        // 同体会丢桶，PC 实测「这个函数会被调用2次」就对不上。
        assertEquals(
            listOf(only, pri, normal1, normal2, only, later),
            table.eventCallOrder("EV"),
            "桶序 + 同桶装载序 + 双桶成员重复出现",
        )
        assertEquals(
            listOf(5 to 0, 3 to 1, 1 to 2, 2 to 2, 5 to 2, 4 to 3),
            table.eventDispatchOrder("EV")?.map { it.first.index to it.second },
            "(成员, 桶) 逐位：只有 #ONLY 成员重复，其余各一次",
        )
        assertEquals(FunctionTable.TargetKind.EVENT, table.classify("ev"), "事件名 IGNORE CASE 分类")
    }

    /**
     * 装载序扰动（B23 收口判据②）：同一批成员换进 [FunctionTable] 的构造次序，
     * 展开序必须逐行不变 —— 序只由「外层桶序 + 内层 defIndex 装载序」决定，
     * 不靠 eventMembersByKey 那张 HashMap 的迭代次序碰运气。
     */
    @Test
    fun `装载序扰动_展开序只由桶序与defIndex决定`() {
        val pl = meta(1, "EV", isEvent = true, isPri = true, isLater = true, defIndex = 1)
        val pri = meta(2, "EV", isEvent = true, isPri = true, defIndex = 2)
        val norm = meta(3, "EV", isEvent = true, defIndex = 3)
        val later = meta(4, "EV", isEvent = true, isLater = true, defIndex = 4)
        val only = meta(5, "EV", isEvent = true, isOnly = true, defIndex = 5)
        val single = meta(6, "EV", isEvent = true, isPri = true, isLater = true, isSingle = true, defIndex = 6)
        val forward = listOf(pl, pri, norm, later, only, single)
        // 桶 0：only；桶 1：pl, pri, single；桶 2：norm, only（isPri 真 ⇒ 第四个 if 不进）；
        // 桶 3：pl, later, single
        val expected = listOf(
            5 to 0,
            1 to 1, 2 to 1, 6 to 1,
            3 to 2, 5 to 2,
            1 to 3, 4 to 3, 6 to 3,
        )
        val permutations = listOf(
            "正序" to forward,
            "逆序" to forward.reversed(),
            "乱序" to listOf(single, norm, only, pl, later, pri),
        )
        for ((label, input) in permutations) {
            val t = FunctionTable(input)
            assertEquals(
                expected,
                t.eventDispatchOrder("EV")?.map { it.first.index to it.second },
                "$label 建表的 (成员, 桶) 展开序必须与正序逐行一致",
            )
            assertEquals(
                expected.map { it.first },
                t.eventCallOrder("EV")?.map { it.index },
                "$label 建表的调用序投影同样逐行一致（双桶成员各出现两次）",
            )
        }
        assertEquals(9, FunctionTable(forward).eventCallOrder("EV")!!.size, "6 成员展开成 9 次调用")
    }

    @Test
    fun `非事件重名后装载者生效`() {
        val first = meta(1, "DUP", defIndex = 1)
        val second = meta(2, "DUP", defIndex = 2)
        val table = FunctionTable(listOf(first, second))
        assertSame(second, table.nonEvent("DUP"), "字典直接赋值语义：最后装载者留下（LabelDictionary.cs:60-68）")
        // 输入乱序也按 defIndex 定胜者（metas 先按 defIndex 稳定排序）。
        val table2 = FunctionTable(listOf(second, first))
        assertSame(second, table2.nonEvent("dup"))
    }

    @Test
    fun `名字解析按大写归一`() {
        val table = FunctionTable(listOf(meta(7, "SOMEFUNC", defIndex = 7)))
        assertSame(table.nonEvent("SOMEFUNC"), table.nonEvent("somefunc"))
        assertEquals(7, table.nonEvent("SoMeFuNc")?.index)
        assertTrue(table.contains("somefunc"))
        assertEquals(FunctionTable.TargetKind.NON_EVENT, table.classify("somefunc"))
        assertEquals(FunctionTable.TargetKind.NOT_FOUND, table.classify("nada"))
    }

    // ---- position 压实：无损面 ----

    @Test
    fun `position 压实逐字节无损且只压可反解析的原文`() {
        val packable = meta(1, "A", position = "ERB/ITEMS/ITEM_NO.ERB:1245")
        val leadingZero = meta(2, "B", position = "ERB/A.ERB:042")
        val noColon = meta(3, "C", position = "sigdefault.erb")
        val empty = meta(4, "D", position = "")
        val marker = meta(5, "E", position = "·event-entry·")
        val hugeLine = meta(6, "F", position = "ERB/A.ERB:99999999999")
        val table = FunctionTable(listOf(packable, leadingZero, noColon, empty, marker, hugeLine))
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), table.all().map { it.key })
        // 逐条读值与原文逐字节相同。
        assertEquals("ERB/ITEMS/ITEM_NO.ERB:1245", packable.position)
        assertEquals("ERB/A.ERB:042", leadingZero.position, "有前置零：重建会变短，必须不压")
        assertEquals("sigdefault.erb", noColon.position)
        assertEquals("", empty.position)
        assertEquals("·event-entry·", marker.position, "无冒号标记串保持原文（sourceFileOf 判前缀不变）")
        assertEquals("ERB/A.ERB:99999999999", hugeLine.position, "行号越 Int 域：原样保留")
        // 可压实的条目：压实确实发生（否则本用例只是空转）——经查询面反证 sourceFileOf 仍对。
        assertEquals("ERB/ITEMS/ITEM_NO.ERB", table.sourceFileOf("A"))
    }

    @Test
    fun `压实不影响等值与 sourceFileOf`() {
        val raw = meta(1, "FN", position = "ERB/MAIN.ERB:9")
        val twin = meta(1, "FN", position = "ERB/MAIN.ERB:9")
        val table = FunctionTable(listOf(raw))
        assertEquals(twin, raw, "压实前后 equals 值语义不变（SymbolLinkAudit a==b 对账口径）")
        assertEquals(twin.hashCode(), raw.hashCode())
        assertEquals("ERB/MAIN.ERB", table.sourceFileOf("FN"), "FN-05 反查源文件读值不变")
    }

    @Test
    fun `toString 与 copy 保持 data class 值语义`() {
        val m = meta(3, "K", position = "ERB/A.ERB:7")
        FunctionTable(listOf(m))
        assertEquals(
            "FuncMeta(index=3, key=K, name=K, isEvent=false, isMethod=false, isOnly=false, " +
                "isPri=false, isLater=false, isSingle=false, argIntCount=0, argStrCount=0, " +
                "localSize=0, localsSize=0, floatLocalSize=0, returnsStr=false, " +
                "returnsFloat=false, defIndex=3, " +
                "position=ERB/A.ERB:7)",
            m.toString(),
            "装载侧首差文案按 data class 渲染格式对账（SymbolLinkAudit.firstFunctionDiff）",
        )
        val linked = m.copy(index = 42)
        assertEquals(42, linked.index, "copy(index=) 定稿通道保留")
        assertEquals(m.key, linked.key)
        assertEquals(m.position, linked.position)
    }

    @Test
    fun `标志位位域读值与逐位语义不变`() {
        val flags = listOf(
            meta(1, "A", isEvent = true, isMethod = true, isOnly = true, isPri = true, isLater = true, isSingle = true, returnsStr = true),
            meta(2, "B", isMethod = true),
            meta(3, "C", isEvent = true, isSingle = true),
            meta(4, "D", returnsStr = true),
            meta(5, "E", isMethod = true, returnsFloat = true),
        )
        FunctionTable(flags)
        val a = flags[0]
        assertTrue(a.isEvent && a.isMethod && a.isOnly && a.isPri && a.isLater && a.isSingle && a.returnsStr)
        // 桶位是集合不是标量（4.981 LabelDictionary.cs:228-235 四平行 if；1.824 同形
        // LabelDictionary.cs:90-105；在册 B23）。四旗标全真只是位域直喂形态：
        // 装载期 #ONLY 会清 PRI/LATER/SINGLE（LogicalLineParser.cs:109-147），语料不可达。
        assertEquals(listOf(0, 1, 3), a.eventBuckets, "isPri 真 ⇒ 桶 2 不进；ONLY/PRI/LATER 并列")
        assertEquals(listOf(2), flags[1].eventBuckets, "无旗标成员只落普通桶（非事件成员该值不参与派发）")
        assertEquals(listOf(2), flags[2].eventBuckets, "isSingle 不改桶位集合（SINGLE 只截本桶，ProcessState.cs:336-340）")
        assertEquals(listOf(2), flags[3].eventBuckets)
        val priLater = meta(6, "F", isEvent = true, isPri = true, isLater = true)
        val onlyAlone = meta(7, "G", isEvent = true, isOnly = true)
        // 双旗标同体 = PC 装载期警告原文「同时使用#PRI和#LATER是重复的(这个函数会被调用2次)」
        assertEquals(listOf(1, 3), priLater.eventBuckets, "#PRI+#LATER 并列落桶 1 与 3")
        assertEquals(listOf(0, 2), onlyAlone.eventBuckets, "#ONLY 并列落桶 0 与 2（第四个 if 不看 isOnly）")
        assertEquals(FunctionTable.TargetKind.NON_EVENT, FunctionTable(flags).classify("d"))
        assertTrue(flags[4].returnsFloat && flags[4].isMethod)
        assertEquals(false, flags[4].returnsStr, "浮点为第三种 MethodType，与字符串位互斥")
        assertEquals(false, flags[0].returnsFloat, "returnsFloat 与会写进侧车 flags 的 7 位互不串位")
    }

    @Test
    fun `两次建表幂等 已压实的不再重压`() {
        val m = meta(1, "P", position = "ERB/A.ERB:42")
        FunctionTable(listOf(m))
        val first = m.position
        FunctionTable(listOf(m))
        assertEquals(first, m.position, "重复建表读值稳定")
        assertEquals(first, m.copy().position, "copy 出的新实例走原文面，值同")
    }

    @Test
    fun `byFunctionIndex 与下标面不受形态影响`() {
        val sparse = meta(10_000, "EVENTFIRST", isEvent = true, defIndex = 1)
        val table = FunctionTable(listOf(meta(0, "MAIN"), sparse))
        assertEquals(0, table.byFunctionIndex(0)?.index)
        assertEquals("EVENTFIRST", table.byFunctionIndex(10_000)?.key)
        assertEquals(null, table.byFunctionIndex(5))
        assertEquals(null, table.byFunctionIndex(-1))
        assertEquals(null, table.byFunctionIndex(10_001))
    }
}
