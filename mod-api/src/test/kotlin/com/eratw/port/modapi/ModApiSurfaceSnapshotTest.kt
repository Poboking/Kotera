package com.eratw.port.modapi

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 作者面快照闸（正控 5 例 + 负控 4 例）。
 *
 * 三条断言与 fail 的对应关系：
 * a) 反射面 == 入库快照（checkSurface，红消息带 diff + 处理顺序）；
 * b) 快照头 v<N> == ModApi.VERSION（checkHeaderVersion）；
 * c) ModApi.kt KDoc「当前取值」表存在 `<N>` = 段（checkKdocVersion，正则与 check-mod 同源）。
 *
 * 刷新入口：`-Deratw.refreshApiSurface=true` 时 snapshotText() 会先写回快照再返回；
 * 未置位时本闸纯只读，不产生任何工作树改动。
 */
class ModApiSurfaceSnapshotTest {

    @Test
    fun `断言 a 反射面与入库快照逐行一致`() {
        val current = ModApiSurfaceSupport.snapshotText()
        val stored = ModApiSurfaceSupport.readSnapshotNormalized()
        val problem = ModApiSurfaceGate.checkSurface(stored, current)
        assertNull(problem, problem.orEmpty())
    }

    @Test
    fun `断言 b 快照头版本号等于 ModApi VERSION`() {
        val stored = ModApiSurfaceSupport.readSnapshotNormalized()
        val problem = ModApiSurfaceGate.checkHeaderVersion(stored, ModApi.VERSION)
        assertNull(problem, problem.orEmpty())
        assertEquals(ModApiSurfaceGate.headerLine(ModApi.VERSION), stored.lineSequence().first())
    }

    @Test
    fun `断言 c KDoc 当前取值表登记了当前 VERSION 段`() {
        val source = ModApiSurfaceSupport.readModApiSource()
        val problem = ModApiSurfaceGate.checkKdocVersion(source, ModApi.VERSION)
        assertNull(problem, problem.orEmpty())
    }

    @Test
    fun `ModApiKt 源码的 VERSION 常量文本与运行时值一致`() {
        val source = ModApiSurfaceSupport.readModApiSource()
        val declared = ModApiSurfaceGate.versionConstNumber(source)
        assertNotNull(declared, "ModApi.kt 里找不到 const val VERSION: Int = <n>")
        assertEquals(ModApi.VERSION, declared)
    }

    @Test
    fun `反射面渲染确定且包含关键类型`() {
        val first = ModApiSurfaceSupport.renderSurface()
        val second = ModApiSurfaceSupport.renderSurface()
        assertEquals(first, second, "同一进程两次反射渲染必须逐字相同")
        val lines = first.lines()
        assertTrue(lines.contains("com.eratw.port.modapi.ModApi | object"), "缺 ModApi 类型行")
        assertTrue(lines.contains("com.eratw.port.modapi.ModContext | interface"), "缺 ModContext 类型行")
        assertTrue(
            lines.contains("com.eratw.port.modapi.ModApi | static field VERSION: int = " + ModApi.VERSION),
            "缺 ModApi.VERSION 字段行"
        )
    }

    @Test
    fun `刷新写回幂等且保留 CRLF`() {
        val path = Files.createTempFile("mod-api-surface", ".txt")
        try {
            Files.writeString(path, "// surface v0\r\nOLD | class\r\n")
            val changed = ModApiSurfaceSupport.writeSnapshotIfChangedAt("// surface v0\nA | class\n", path)
            assertTrue(changed, "首次写回应报「已变更」")
            assertEquals("// surface v0\r\nA | class\r\n", Files.readString(path))
            val again = ModApiSurfaceSupport.writeSnapshotIfChangedAt("// surface v0\nA | class\n", path)
            assertFalse(again, "内容相同必须幂等：二次写回不得落盘")
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `负控 快照头版本号落后 VERSION 必须报红`() {
        val rendered = ModApiSurfaceSupport.renderSurface()
        val stale = rendered.replaceFirst(
            ModApiSurfaceGate.headerLine(ModApi.VERSION),
            ModApiSurfaceGate.headerLine(ModApi.VERSION - 1)
        )
        val problem = ModApiSurfaceGate.checkHeaderVersion(stale, ModApi.VERSION)
        assertNotNull(problem, "头部版本不一致必须被抓到")
        assertTrue(problem.contains(ModApiSurfaceGate.headerLine(ModApi.VERSION)), problem)
    }

    @Test
    fun `负控 快照少一行而反射面未变必须报红并打印 diff`() {
        val rendered = ModApiSurfaceSupport.renderSurface()
        val broken = dropFirstMemberLine(rendered)
        val problem = ModApiSurfaceGate.checkSurface(broken, rendered)
        assertNotNull(problem, "快照少一行必须被面比对抓到")
        assertTrue(problem.contains("+ "), "红消息必须打印反射面多出的行：\n" + problem)
        assertTrue(problem.contains("bump ModApi.VERSION"), "红消息必须提示 bump VERSION：\n" + problem)
    }

    @Test
    fun `负控 KDoc 版本表缺当前 VERSION 段必须报红`() {
        val source = ModApiSurfaceSupport.readModApiSource()
        val broken = dropKdocVersionSegment(source, ModApi.VERSION)
        val problem = ModApiSurfaceGate.checkKdocVersion(broken, ModApi.VERSION)
        assertNotNull(problem, "KDoc 表缺段必须被版本表断言抓到")
        assertTrue(problem.contains("`" + ModApi.VERSION + "`"), problem)
    }

    private fun dropFirstMemberLine(text: String): String {
        val lines = text.lines()
        val index = lines.indexOfFirst { line -> memberLinePattern.matches(line) }
        if (index < 0) {
            fail("快照里找不到成员行，负控构造失效")
        }
        return (lines.take(index) + lines.drop(index + 1)).joinToString("\n")
    }

    private fun dropKdocVersionSegment(text: String, version: Int): String =
        text.lines().filterNot { line ->
            ModApiSurfaceGate.cleanKdocLine(line).startsWith("`" + version + "` =")
        }.joinToString("\n")

    private companion object {
        private val memberLinePattern = Regex("""^.+ \| (static )?(fun|val|field) .*$""")
    }
}
