package com.eratw.port.core.mod

import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.vars.VariableStore
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
 * mod 音频面的**宿主接线**钉子（Lane AN，task-42）：`ModRuntimeHost.audioSink` 在 `onLoad`
 * 之前把本包播放适配器 adopt 到 `context.audio`；资源字节读取与降级链（非法名 / 缺文件 /
 * 超限 / 汇异常）全在 core 侧，未注入时显式 fail-closed。
 *
 * 反向证明（交付消息记录）：把 [adoptPerModState] 里 `host.audioSink?.let { … }` 那段摘掉
 * ⇒ 判据 1/7 立红（`context.audio` 退化成 fail-closed）。
 */
class ModAudioWiringTest {

    private fun withTempRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("eratw-mod-audio-wiring").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeAsset(root: File, packageId: String, name: String, bytes: ByteArray) {
        val file = File(root, packageId + ModResourceStore.ASSET_DIR_SUFFIX + "/" + name)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun writeAsset(root: File, packageId: String, name: String, text: String) =
        writeAsset(root, packageId, name, text.toByteArray())

    private fun newSnapshot(vararg packages: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        for (pkg in packages) {
            registry.addPackage(ModDeclarationAdapter.toDescriptor(modDeclarations(pkg, version = "1.0") {}))
        }
        return registry.freeze()
    }

    private fun newHost(vararg packages: String, logs: MutableList<String> = mutableListOf()): ModRuntimeHost =
        ModRuntimeHost(
            snapshot = newSnapshot(*packages),
            entityKernel = EntityKernel(
                schemas = emptyList(),
                relationshipSchemas = emptyList(),
                archetypes = emptyList(),
            ),
            nativeMethods = NativeMethodRegistry(),
            logSink = { message -> logs += message },
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

    /** 记录调用的假播放汇；`throwOnPlay` 打开时模拟宿主侧异常。 */
    private class FakeSink(private val throwOnPlay: Boolean = false) : ModAudioSink {
        val plays = mutableListOf<Played>()
        var stopCount: Int = 0

        class Played(val packageId: String, val bytes: ByteArray, val loop: Boolean, val volume: Float)

        override fun play(packageId: String, bytes: ByteArray, loop: Boolean, volume: Float): Boolean {
            if (throwOnPlay) error("boom")
            plays += Played(packageId, bytes, loop, volume)
            return true
        }

        override fun stop() {
            stopCount += 1
        }
    }

    @Test
    fun `注入音频汇后onLoad按名播放且透传loop与volume`() {
        withTempRoot { root ->
            writeAsset(root, "mod.a", "beep.ogg", "OGG!")
            val sink = FakeSink()
            val logs = mutableListOf<String>()
            val host = newHost("mod.a", logs = logs)
            host.resources = ModResourceStore(rt2Dir = root)
            host.audioSink = sink
            var accepted = false
            val report = host.install(
                listOf(
                    "mod.a" to ScriptedMod { context ->
                        accepted = context.audio.play("beep.ogg", loop = true, volume = 0.5f)
                    },
                ),
            )
            assertTrue(report.single().active, "注入音频面后 mod 必须安装成功：$report")
            assertTrue(accepted, "有资源且有汇时 play 必须返回 true；logs=$logs")
            val played = assertNotNull(sink.plays.singleOrNull(), "宿主汇必须收到且只收到一次播放")
            assertEquals("mod.a", played.packageId)
            assertEquals("OGG!", String(played.bytes))
            assertTrue(played.loop, "loop 必须透传")
            assertEquals(0.5f, played.volume)
        }
    }

    @Test
    fun `音量超出范围时按零到一夹取后透传`() {
        withTempRoot { root ->
            writeAsset(root, "mod.a", "loud.ogg", "x")
            val sink = FakeSink()
            val host = newHost("mod.a")
            host.resources = ModResourceStore(rt2Dir = root)
            host.audioSink = sink
            host.install(
                listOf(
                    "mod.a" to ScriptedMod { context -> context.audio.play("loud.ogg", volume = 9.0f) },
                ),
            )
            assertEquals(1.0f, sink.plays.single().volume)
        }
    }

    @Test
    fun `缺文件时WARN且不调用宿主汇`() {
        withTempRoot { root ->
            val sink = FakeSink()
            val logs = mutableListOf<String>()
            val host = newHost("mod.a", logs = logs)
            host.resources = ModResourceStore(rt2Dir = root)
            host.audioSink = sink
            var accepted = true
            host.install(
                listOf(
                    "mod.a" to ScriptedMod { context -> accepted = context.audio.play("nope.ogg") },
                ),
            )
            assertTrue(!accepted, "缺文件必须返回 false")
            assertTrue(sink.plays.isEmpty(), "缺文件不该调宿主汇")
            assertTrue(logs.any { it.contains("资源不存在或不可读") }, "必须留 WARN：$logs")
        }
    }

    @Test
    fun `非法资源名时WARN且不调用宿主汇`() {
        withTempRoot { root ->
            val sink = FakeSink()
            val logs = mutableListOf<String>()
            val host = newHost("mod.a", logs = logs)
            host.resources = ModResourceStore(rt2Dir = root)
            host.audioSink = sink
            var accepted = true
            host.install(
                listOf(
                    "mod.a" to ScriptedMod { context -> accepted = context.audio.play("../escape.ogg") },
                ),
            )
            assertTrue(!accepted, "非法名必须返回 false")
            assertTrue(sink.plays.isEmpty(), "非法名不该调宿主汇")
            assertTrue(logs.any { it.contains("资源名非法") }, "必须留 WARN：$logs")
        }
    }

    @Test
    fun `超单文件上限时WARN且不调用宿主汇`() {
        withTempRoot { root ->
            writeAsset(root, "mod.a", "big.ogg", "12345")
            val sink = FakeSink()
            val logs = mutableListOf<String>()
            val store = ModResourceStore(rt2Dir = root, maxFileBytes = 4L)
            val host = newHost("mod.a", logs = logs)
            host.resources = store
            host.audioSink = sink
            var accepted = true
            host.install(
                listOf(
                    "mod.a" to ScriptedMod { context -> accepted = context.audio.play("big.ogg") },
                ),
            )
            assertTrue(!accepted, "超限文件必须返回 false")
            assertTrue(sink.plays.isEmpty(), "超限文件不该调宿主汇")
        }
    }

    @Test
    fun `宿主汇抛异常时false不外泄且WARN`() {
        withTempRoot { root ->
            writeAsset(root, "mod.a", "beep.ogg", "x")
            val logs = mutableListOf<String>()
            val host = newHost("mod.a", logs = logs)
            host.resources = ModResourceStore(rt2Dir = root)
            host.audioSink = FakeSink(throwOnPlay = true)
            var accepted = true
            val report = host.install(
                listOf(
                    "mod.a" to ScriptedMod { context -> accepted = context.audio.play("beep.ogg") },
                ),
            )
            assertTrue(report.single().active, "汇异常不应砖掉 mod：$report")
            assertTrue(!accepted, "汇异常必须隔离成 false")
            assertTrue(logs.any { it.contains("宿主播放汇异常已隔离") }, "必须留 WARN：$logs")
        }
    }

    @Test
    fun `未注入音频汇时contextaudio显式fail_closed`() {
        withTempRoot {
            val host = newHost("mod.a")
            var failure: UnsupportedOperationException? = null
            val report = host.install(
                listOf(
                    "mod.a" to ScriptedMod { context ->
                        failure = try {
                            context.audio.play("beep.ogg")
                            null
                        } catch (t: UnsupportedOperationException) {
                            t
                        }
                    },
                ),
            )
            assertTrue(report.single().active, "音频面 fail-closed 不应连带 mod 装配失败：$report")
            val thrown = assertNotNull(failure, "未注入时 context.audio.play 必须显式抛")
            assertTrue(thrown.message.orEmpty().contains("音频面未装配"), "错误必须点名装配缺口：${thrown.message}")
            assertTrue(thrown.message.orEmpty().contains("mod.a"), "错误必须点名 packageId：${thrown.message}")
        }
    }

    @Test
    fun `stop转交宿主汇且重复play每次透传`() {
        withTempRoot { root ->
            writeAsset(root, "mod.a", "beep.ogg", "x")
            val sink = FakeSink()
            val host = newHost("mod.a")
            host.resources = ModResourceStore(rt2Dir = root)
            host.audioSink = sink
            host.install(
                listOf(
                    "mod.a" to ScriptedMod { context ->
                        context.audio.play("beep.ogg")
                        context.audio.play("beep.ogg", loop = true)
                        context.audio.stop()
                    },
                ),
            )
            assertEquals(2, sink.plays.size, "两次 play 各自要透传")
            assertEquals(1, sink.stopCount, "stop 必须转交宿主汇")
        }
    }

    @Test
    fun `会话槽play生效且uninstall后停声并拒播`() {
        withTempRoot { root ->
            writeAsset(root, "mod.a", "beep.ogg", "x")
            val store = VariableStore()
            val sink = FakeSink()
            val logs = mutableListOf<String>()
            ModAudioWiring.install(store, sink, ModResourceStore(rt2Dir = root), { message -> logs += message })
            assertTrue(ModAudioWiring.play(store, "mod.a", "beep.ogg", loop = false, volume = 0.25f), "槽在位时 play 必须受理")
            assertEquals("mod.a", sink.plays.single().packageId)
            assertEquals(0.25f, sink.plays.single().volume)
            ModAudioWiring.uninstall(store)
            assertEquals(1, sink.stopCount, "uninstall 必须先停声")
            assertTrue(!ModAudioWiring.play(store, "mod.a", "beep.ogg", loop = false, volume = 1f), "解绑后必须拒播")
            assertTrue(logs.any { it.contains("播放汇未装配") }, "拒播必须留 WARN：$logs")
        }
    }
}
