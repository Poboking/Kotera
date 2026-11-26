package com.eratw.port.core.mod

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.StringsSpec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * mod 自带三语文案面的运行期钉（RT2 多语言文案）：
 *
 * 1. **按语言取**：ZH/EN/JA 各自落位，未知语言码回落 ZH；
 * 2. **回退链**：未声明键原样返回键（不空串、不抛），[com.eratw.port.modapi.StringsApi.has] 为 false；
 * 3. **声明期校验**：坏键（大写/空格/超长）与空白/超长原文当场抛 [IllegalArgumentException]；
 * 4. **重复声明后值覆盖**：同一键再声明后取到新值（不累积、不抛）；
 * 5. **跨包隔离**：A 包声明不污染 B 包，同一键两包各取自己那份；
 * 6. **卸载即清空**：`shutdown` 后本包文案消失（渲染侧不许再解析到停用包的键）；
 * 7. **未装配 fail-closed**：没有 adopt 的 [ModContextImpl] 读 `context.strings` 抛
 *    [UnsupportedOperationException]，不返回键本身兜底；
 * 8. **语言随壳层切换**：改 [ModRuntimeHost.language] 后作者 `of()`/`language()` 立刻取到新语言。
 *
 * 反向证明（交付消息记录）：把 `ModRuntimeAdopt.kt` 里的 `context.adoptStrings(...)` 摘掉 ⇒
 * 判据 1/2/8 用例立红（退化成 fail-closed）；把 `revokeEntry` 里的 `strings.clear(...)` 摘掉 ⇒
 * 判据 6 立红。
 */
class ModStringsWiringTest {

    private fun newKernel(): EntityKernel = EntityKernel(
        schemas = listOf(
            ComponentSchema(
                packageId = "demo",
                name = "Stats",
                fields = listOf(ComponentFieldSpec("hp", ComponentFieldType.INT)),
            ),
        ),
        relationshipSchemas = listOf(RelationshipSchema(name = "PARENT")),
        archetypes = listOf(EntityArchetype("Child", listOf("Stats"))),
    )

    private fun newSnapshot(vararg packages: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        for (pkg in packages) {
            registry.addPackage(ModDeclarationAdapter.toDescriptor(ModManifest(packageId = pkg)))
        }
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) = action()
    }

    /** 在 onLoad 里按脚本执行的测试 mod（记录观察值；异常原样上抛给宿主回滚）。 */
    private class ScriptedMod(private val script: (ModContext) -> Unit) : EraMod {
        override fun onLoad(context: ModContext) = script(context)
        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    private fun newHost(vararg packages: String): ModRuntimeHost = ModRuntimeHost(
        snapshot = newSnapshot(*packages),
        entityKernel = newKernel(),
        nativeMethods = NativeMethodRegistry(),
        logSink = {},
        runtime = FakeRuntime(),
    )

    /** 直接构造未装配 context（不走 host.install）——用于钉 fail-closed 缺省 getter。 */
    private fun newBareContext(packageId: String): ModContextImpl = ModContextImpl(
        packageId = packageId,
        snapshot = newSnapshot(packageId),
        entityKernel = newKernel(),
        pkgShortId = 1,
        nativeMethods = NativeMethodRegistry(),
        logSink = {},
        runtime = FakeRuntime(),
    )

    // ---------------- 判据 1：按语言取 + 未知语言回落 ZH ----------------

    @Test
    fun `三语各自落位且未知语言码回落zh`() {
        val registry = ModStringsRegistry()
        assertTrue(registry.declare("mod.a", "btn.hello", "你好", "Hello", "こんにちは"))
        assertEquals("你好", registry.resolve("mod.a", "btn.hello", "zh"))
        assertEquals("Hello", registry.resolve("mod.a", "btn.hello", "en"))
        assertEquals("こんにちは", registry.resolve("mod.a", "btn.hello", "ja"))
        assertEquals("你好", registry.resolve("mod.a", "btn.hello", "fr"))
    }

    // ---------------- 判据 2：回退链（未声明键原样返回键） ----------------

    @Test
    fun `未声明键原样返回键且has为false`() {
        val host = newHost("mod.a")
        var observed: ModContext? = null
        host.install(listOf("mod.a" to ScriptedMod { context -> observed = context }))
        val context = assertNotNull(observed)
        assertEquals("btn.ghost", context.strings.of("btn.ghost"))
        assertFalse(context.strings.has("btn.ghost"))
        assertTrue(host.strings.isEmpty(), "未声明任何文案时会话表应为空")
        host.shutdown()
    }

    // ---------------- 判据 3：声明期校验 ----------------

    @Test
    fun `坏键与空白原文在声明期抛`() {
        val registry = ModStringsRegistry()
        assertFailsWith<IllegalArgumentException>("大写键") {
            registry.declare("mod.a", "Btn.Hello", "你好", "Hello", "こんにちは")
        }
        assertFailsWith<IllegalArgumentException>("空格键") {
            registry.declare("mod.a", "btn hello", "你好", "Hello", "こんにちは")
        }
        assertFailsWith<IllegalArgumentException>("空键") {
            registry.declare("mod.a", "", "你好", "Hello", "こんにちは")
        }
        assertFailsWith<IllegalArgumentException>("超长键") {
            registry.declare("mod.a", "k".repeat(StringsSpec.MAX_KEY_LENGTH + 1), "你好", "Hello", "こんにちは")
        }
        assertFailsWith<IllegalArgumentException>("空白原文") {
            registry.declare("mod.a", "btn.hello", "  ", "Hello", "こんにちは")
        }
        assertFailsWith<IllegalArgumentException>("超长原文") {
            val long = "x".repeat(StringsSpec.MAX_TEXT_LENGTH + 1)
            registry.declare("mod.a", "btn.hello", "你好", long, "こんにちは")
        }
        assertTrue(registry.isEmpty(), "声明期被拒的键不许落表")
    }

    // ---------------- 判据 4：重复声明后值覆盖 ----------------

    @Test
    fun `重复声明后值覆盖且返回false`() {
        val registry = ModStringsRegistry()
        assertTrue(registry.declare("mod.a", "btn.hello", "你好", "Hello", "こんにちは"))
        assertFalse(registry.declare("mod.a", "btn.hello", "您好", "Hi", "やあ"))
        assertEquals("您好", registry.resolve("mod.a", "btn.hello", "zh"))
        assertEquals("Hi", registry.resolve("mod.a", "btn.hello", "en"))
        assertEquals(1, registry.keys("mod.a").size)
    }

    // ---------------- 判据 5：跨包隔离 ----------------

    @Test
    fun `同一键两包各取自己那份`() {
        val registry = ModStringsRegistry()
        registry.declare("mod.a", "btn.hello", "A 的你好", "A hello", "A こんにちは")
        registry.declare("mod.b", "btn.hello", "B 的你好", "B hello", "B こんにちは")
        assertEquals("A 的你好", registry.resolve("mod.a", "btn.hello", "zh"))
        assertEquals("B hello", registry.resolve("mod.b", "btn.hello", "en"))
        assertEquals(setOf("btn.hello"), registry.keys("mod.a"))
        assertNull(registry.resolve("mod.c", "btn.hello", "zh"), "未声明的包解析为 null")
        assertEquals(2, registry.packageCount())
    }

    // ---------------- 判据 6：卸载即清空 ----------------

    @Test
    fun `shutdown后本包文案清空`() {
        val host = newHost("mod.a", "mod.b")
        var observed: ModContext? = null
        host.install(
            listOf(
                "mod.a" to ScriptedMod { context ->
                    observed = context
                    context.strings.declare("btn.hello", "你好", "Hello", "こんにちは")
                },
                "mod.b" to ScriptedMod { context ->
                    context.strings.declare("btn.hello", "B 你好", "B hello", "B こんにちは")
                },
            ),
        )
        assertEquals("Hello", host.strings.resolve("mod.a", "btn.hello", "en"))
        assertEquals(2, host.strings.packageCount())
        host.shutdown()
        assertTrue(host.strings.isEmpty(), "全部卸载后文案表应为空")
        val context = assertNotNull(observed)
        assertFalse(context.strings.has("btn.hello"), "卸载后作者读侧也不再命中")
        assertEquals("btn.hello", context.strings.of("btn.hello"), "卸载后回落键本身")
    }

    // ---------------- 判据 7：未装配 fail-closed ----------------

    @Test
    fun `未装配时读文案面抛UnsupportedOperation`() {
        val context = newBareContext("mod.raw")
        assertFailsWith<UnsupportedOperationException> { context.strings.of("btn.hello") }
        assertFailsWith<UnsupportedOperationException> { context.strings.has("btn.hello") }
        assertFailsWith<UnsupportedOperationException> { context.strings.language() }
        assertFailsWith<UnsupportedOperationException> {
            context.strings.declare("btn.hello", "你好", "Hello", "こんにちは")
        }
    }

    // ---------------- 判据 8：语言随壳层切换 ----------------

    @Test
    fun `改host语言后作者取到新语言`() {
        val host = newHost("mod.a")
        var observed: ModContext? = null
        host.install(
            listOf(
                "mod.a" to ScriptedMod { context ->
                    observed = context
                    context.strings.declare("btn.hello", "你好", "Hello", "こんにちは")
                },
            ),
        )
        val context = assertNotNull(observed)
        assertEquals("zh", context.strings.language(), "缺省语言码")
        assertEquals("你好", context.strings.of("btn.hello"))
        host.language = "ja"
        assertEquals("ja", context.strings.language())
        assertEquals("こんにちは", context.strings.of("btn.hello"))
        host.language = "en"
        assertEquals("Hello", context.strings.of("btn.hello"))
        host.shutdown()
    }
}
