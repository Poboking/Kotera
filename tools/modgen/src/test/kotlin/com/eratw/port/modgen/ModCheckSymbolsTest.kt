package com.eratw.port.modgen

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

/**
 * 类型化键符号表的解析纪律：别名链（含环）必须终止。环是破损源码的常态形态（`val A:
 * ArchetypeKey = A` 或 A↔B 互指，编不过的模块正是 check-mod 的目标输入），解析遇环按
 * 「当次不可解析」返回 null，不允许 StackOverflowError 把整个 check-mod 进程带崩。
 *
 * 环可达性：ModCheckDeclarations.checkSchemaNames 对载体文件里每个 `component(...)` /
 * `archetype(...)` 实参调 [Symbols.resolve]，实参命中自指/互指别名即进入递归。
 */
class ModCheckSymbolsTest {

    @TempDir
    lateinit var tempDir: Path

    /** 单文件夹具：raw 供生成物比对、code 走统一剥注释口径（与 ModCheckSources.scan 同形）。 */
    private fun source(vararg lines: String): SourceFile {
        val raw = lines.joinToString("\n")
        return SourceFile(Path.of("Test.kt"), "Test.kt", raw, ModCheckSources.stripComments(raw))
    }

    @Test
    fun `self alias resolves to null instead of stack overflow`() {
        val symbols = ModCheckSymbols.build(listOf(source("val A: ArchetypeKey = A")))
        assertNull(symbols.resolve("A"))
    }

    @Test
    fun `mutual alias cycle resolves to null instead of stack overflow`() {
        val symbols = ModCheckSymbols.build(
            listOf(source("val A: ArchetypeKey = B", "val B: ArchetypeKey = A")),
        )
        assertNull(symbols.resolve("A"))
        assertNull(symbols.resolve("B"))
    }

    /**
     * 环不只发生在纯符号表调用上：声明文件里 `archetype(...)` 的实参命中环时，整份 check-mod
     * 必须跑得完，并把该实参按「当次不可解析」丢进既有诊断面 —— 这里锁的是 [ModCheck.check]
     * 端到端不炸进程，而不只是 [Symbols.resolve] 的返回值。
     */
    @Test
    fun `mutual alias cycle in module declarations lands on readable finding`() {
        val module = scaffoldModule(tempDir)
        replaceOnce(
            declarationsFile(module),
            "        val ARCHETYPE: ArchetypeKey = ArchetypeKey(\"eratw.mymod.actor\")",
            "        val ARCHETYPE: ArchetypeKey = OTHER\n        val OTHER: ArchetypeKey = ARCHETYPE",
        )
        assertHasError(findingsOf(module), "未在 Declarations 的 archetype")
    }

    @Test
    fun `alias chain terminates at object literal`() {
        val symbols = ModCheckSymbols.build(
            listOf(
                source(
                    "object MemoryKeys {",
                    "    val archetype: ArchetypeKey = ArchetypeKey(\"memory.fact\")",
                    "}",
                ),
                source("val FACT: ArchetypeKey = MemoryKeys.archetype"),
            ),
        )
        assertEquals("memory.fact", symbols.resolve("FACT"))
        assertEquals("memory.fact", symbols.resolve("MemoryKeys.archetype"))
    }

    @Test
    fun `exact literal resolves without alias hop`() {
        val symbols = ModCheckSymbols.build(
            listOf(source("val ACTOR: ArchetypeKey = ArchetypeKey(\"template.matrix.actor\")")),
        )
        assertEquals("template.matrix.actor", symbols.resolve("ACTOR"))
    }

    @Test
    fun `unique simple name falls back through literals`() {
        val symbols = ModCheckSymbols.build(
            listOf(
                source(
                    "object MyKeys {",
                    "    val component: ComponentKey = ComponentKey(\"my.thing\")",
                    "}",
                ),
            ),
        )
        assertEquals("my.thing", symbols.resolve("component"))
    }

    @Test
    fun `ambiguous simple name stays unresolved`() {
        val symbols = ModCheckSymbols.build(
            listOf(
                source("object A {", "    val component: ComponentKey = ComponentKey(\"a.thing\")", "}"),
                source("object B {", "    val component: ComponentKey = ComponentKey(\"b.thing\")", "}"),
            ),
        )
        assertNull(symbols.resolve("component"))
    }

    @Test
    fun `alias to unknown target resolves to null`() {
        val symbols = ModCheckSymbols.build(
            listOf(source("val FACT: ArchetypeKey = Missing.archetype")),
        )
        assertNull(symbols.resolve("FACT"))
    }
}
