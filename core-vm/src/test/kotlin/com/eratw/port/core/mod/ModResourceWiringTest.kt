package com.eratw.port.core.mod

import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.modDeclarations
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * mod 资源面的**宿主装配**钉子（task-24）：`ModRuntimeHost.resources` 在 `onLoad` 之前
 * adopt 到每个 context，未注入时 `context.resources` 显式 fail-closed。
 *
 * 反向证明（交付消息记录）：把 [ModRuntimeHost.install] 里
 * `resources?.let { context.resourceApi = it.apiFor(packageId) }` 摘掉 ⇒ 判据 1/3 立红
 * （退化成 fail-closed）；把 [ModContext.resources] 的接口缺省实现改成返回空表 ⇒ 判据 2 红。
 */
class ModResourceWiringTest {

    private fun withTempRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("eratw-mod-res-wiring").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeAsset(root: File, packageId: String, name: String, text: String) {
        val file = File(root, packageId + ModResourceStore.ASSET_DIR_SUFFIX + "/" + name)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun newSnapshot(vararg packages: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        for (pkg in packages) {
            registry.addPackage(ModDeclarationAdapter.toDescriptor(modDeclarations(pkg, version = "1.0") {}))
        }
        return registry.freeze()
    }

    private fun newHost(vararg packages: String): ModRuntimeHost = ModRuntimeHost(
        snapshot = newSnapshot(*packages),
        entityKernel = EntityKernel(
            schemas = emptyList(),
            relationshipSchemas = emptyList(),
            archetypes = emptyList(),
        ),
        nativeMethods = NativeMethodRegistry(),
        logSink = {},
        runtime = FakeRuntime(),
    )

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true

        override fun submit(action: () -> Unit) = action()
    }

    private class ScriptedMod(private val script: (ModContext) -> Unit) : EraMod {
        override fun onLoad(context: ModContext) = script(context)

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    @Test
    fun `注入资源存储后onLoad按名读到本包资源`() {
        withTempRoot { root ->
            writeAsset(root, "mod.a", "hello.txt", "hi")
            val host = newHost("mod.a")
            host.resources = ModResourceStore(rt2Dir = root)
            var keys: List<String> = emptyList()
            var content: String? = null
            var packageId: String? = null
            val report = host.install(
                listOf(
                    "mod.a" to ScriptedMod { context ->
                        keys = context.resources.keys()
                        content = context.resources.open("hello.txt")?.let { String(it) }
                        packageId = context.resources.packageId
                    },
                ),
            )
            assertTrue(report.single().active, "注入资源后 mod 必须安装成功：$report")
            assertEquals(listOf("hello.txt"), keys)
            assertEquals("hi", content)
            assertEquals("mod.a", packageId)
        }
    }

    @Test
    fun `两个包各自只读到自己的资源`() {
        withTempRoot { root ->
            writeAsset(root, "mod.a", "id.txt", "A")
            writeAsset(root, "mod.b", "id.txt", "B")
            val host = newHost("mod.a", "mod.b")
            host.resources = ModResourceStore(rt2Dir = root)
            val reads = mutableMapOf<String, String>()
            val report = host.install(
                listOf(
                    "mod.a" to ScriptedMod { reads[it.packageId] = String(it.resources.open("id.txt")!!) },
                    "mod.b" to ScriptedMod { reads[it.packageId] = String(it.resources.open("id.txt")!!) },
                ),
            )
            assertTrue(report.all { it.active }, "两包都应安装成功：$report")
            assertEquals(mapOf("mod.a" to "A", "mod.b" to "B"), reads)
        }
    }

    @Test
    fun `未注入资源存储时contextresources显式fail_closed`() {
        withTempRoot {
            val host = newHost("mod.a")
            var failure: UnsupportedOperationException? = null
            val report = host.install(
                listOf(
                    "mod.a" to ScriptedMod { context ->
                        failure = try {
                            context.resources.keys()
                            null
                        } catch (t: UnsupportedOperationException) {
                            t
                        }
                    },
                ),
            )
            assertTrue(report.single().active, "资源面 fail-closed 不应连带 mod 装配失败：$report")
            val thrown = assertNotNull(failure, "未注入时 context.resources.keys() 必须显式抛")
            assertTrue(thrown.message.orEmpty().contains("资源面未装配"), "错误必须点名装配缺口：${thrown.message}")
            assertTrue(thrown.message.orEmpty().contains("mod.a"), "错误必须点名 packageId：${thrown.message}")
        }
    }

    @Test
    fun `注入存储但包目录缺失时资源面为空且不崩`() {
        withTempRoot { root ->
            val host = newHost("mod.a")
            host.resources = ModResourceStore(rt2Dir = root)
            var keys: List<String>? = null
            var content: ByteArray? = byteArrayOf(1)
            val report = host.install(
                listOf(
                    "mod.a" to ScriptedMod { context ->
                        keys = context.resources.keys()
                        content = context.resources.open("nope.txt")
                    },
                ),
            )
            assertTrue(report.single().active, "缺资源目录 = 空资源面，不应影响装载：$report")
            assertEquals(emptyList<String>(), keys)
            assertEquals(null, content)
        }
    }
}
