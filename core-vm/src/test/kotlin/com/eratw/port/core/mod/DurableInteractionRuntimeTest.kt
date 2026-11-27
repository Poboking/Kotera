package com.eratw.port.core.mod

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityIdCodec
import com.eratw.port.core.entity.EntityIdRemapTable
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.ConsoleSink
import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.PackageChunk
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveService
import com.eratw.port.core.save.SidecarChunkGroups
import com.eratw.port.core.save.SidecarRestoreResult
import com.eratw.port.core.save.SidecarSaveBundle
import com.eratw.port.core.save.SidecarSaveWiring
import com.eratw.port.core.save.SidecarStoragePort
import com.eratw.port.core.vars.ModCsvValueBag
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.DurableStory
import com.eratw.port.modapi.DurableStoryNode
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.StringsApi
import com.eratw.port.modapi.durableStory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DurableInteractionRuntimeTest {
    private class PauseForSave : RuntimeException()

    private object EmptyProjector : ICharaSlotProjector {
        override fun project(entityId: Long): Int = -1
        override fun unproject(entityId: Long): Boolean = false
        override fun charIndexOf(entityId: Long): Int = -1
        override val rosterGeneration: Long get() = 0L
        override fun charIndexOfChecked(entityId: Long, expectedGeneration: Long): Int = -1
        override fun entityAt(charIndex: Int): Long = com.eratw.port.core.entity.EntityIdCodec.NULL
        override fun projectedEntities(): LongArray = LongArray(0)
        override fun syncFromCharaSlot(entityId: Long) = Unit
        override fun onCharacterRowsPermuted(oldToNewIndexMap: IntArray) = Unit
        override fun onCharacterRowDeleted(deletedIndex: Int) = Unit
        override fun onWorldReset() = Unit
    }

    private class ScriptedInput : InputSink {
        var values = emptyList<Long>()
        var index = 0
        var onSave: (Int) -> Unit = {}
        val requests = mutableListOf<InputRequest>()
        var mesSkip = false
        var mesSkipConsumptions = 0

        fun feed(values: List<Long>) {
            this.values = values
            index = 0
        }

        override fun readAnyKey(anyKey: Boolean, stopMesSkip: Boolean) = Unit

        override fun waitInput(request: InputRequest): InputOutcome {
            requests += request
            val current = index
            onSave(current)
            if (current >= values.size) throw PauseForSave()
            index++
            mesSkip = true
            return InputOutcome(intValue = values[current])
        }

        override fun consumeMesSkip(): Boolean {
            mesSkipConsumptions++
            val pending = mesSkip
            mesSkip = false
            return pending
        }
    }

    private class RecordingConsole : ConsoleSink {
        val lines = mutableListOf<String>()
        val buttons = mutableListOf<Pair<String, Long>>()
        val waits = mutableListOf<InputRequest>()
        val events = mutableListOf<String>()
        var publications = 0
        var holds = 0

        override fun print(text: String) { lines += text }
        override fun printLine(text: String) { lines += text; events += "line:$text" }
        override fun printC(text: String, alignRight: Boolean) = Unit
        override fun printSingleLine(text: String) = Unit
        override fun printButton(text: String, value: Long) { buttons += text to value; events += "button:$text:$value" }
        override fun printButton(text: String, value: String) = Unit
        override fun printButtonC(text: String, value: Long, alignRight: Boolean) = Unit
        override fun printButtonC(text: String, value: String, alignRight: Boolean) = Unit
        override fun printPlain(text: String) = Unit
        override fun printTemporaryLine(text: String) = Unit
        override fun printBar() = Unit
        override fun printCustomBar(text: String) = Unit
        override fun newLine() = Unit
        override fun deleteLine(count: Int) = Unit
        override fun clearText() = Unit
        override fun setForeColor(r: Int, g: Int, b: Int) = Unit
        override fun setBackColor(r: Int, g: Int, b: Int) = Unit
        override fun resetForeColor() = Unit
        override fun resetBackColor() = Unit
        override fun printHtml(text: String, option: Boolean) = Unit
        override fun addHtmlIsland(text: String) = Unit
        override fun clearHtmlIslands() = Unit
        override fun printError(text: String) = Unit
        override fun noteInputWait(request: InputRequest) { waits += request; events += "note" }
        override fun publishFrame() { publications++; events += "publish" }
        override fun holdFrame() { holds++; events += "hold" }
        override fun setFontStyle(mask: Int) = Unit
        override fun setFont(name: String) = Unit
        override fun setAlignment(kind: Alignment) = Unit
        override fun setRedraw(mode: Int) = Unit
    }

    private class Scheduler : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) = action()
    }

    private fun snapshot(packageId: String): DeclarationSnapshot = DeclarationRegistry().apply {
        addPackage(
            ModDeclarationAdapter.toDescriptor(
                ModManifest(
                    packageId = packageId,
                    interactionHooks = listOf(
                        InteractionHookDecl(InteractionPhase.PRE), InteractionHookDecl(InteractionPhase.MAIN),
                    ),
                ),
            ),
        )
    }.freeze()

    private fun kernel(): EntityKernel = EntityKernel(
        schemas = listOf(ComponentSchema("demo", "Stats", listOf(ComponentFieldSpec("hp", ComponentFieldType.INT)))),
        relationshipSchemas = listOf(RelationshipSchema(name = "PARENT")),
        archetypes = listOf(EntityArchetype("Child", listOf("Stats"))),
    )

    private class StoryMod(private val packageId: String, private val story: DurableStory) : EraMod {
        lateinit var context: ModContext
        override val apiVersion: Int get() = 1
        override val dataVersion: Int get() = 3
        override fun onLoad(context: ModContext) { this.context = context }
        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit

        fun run(interaction: InteractionContext = defaultContext()) = context.interactions.runDurable(interaction, story)

        private fun defaultContext() = InteractionContext(
            "scene.test", EntityId(8L), listOf(EntityId(9L)), EntityId(9L), mapOf("slot" to "one"),
        )
    }

    private inner class Session {
        val console = RecordingConsole()
        val input = ScriptedInput()
        val logs = mutableListOf<String>()
        val host = ModRuntimeHost(
            snapshot = snapshot("story.mod"),
            entityKernel = kernel(),
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = Scheduler(),
            decisionPipeline = DecisionPipeline.of(listOf("story.mod"), 1L),
        ).apply {
            durableInteractionHost = object : DurableInteractionHost {
                override val console: ConsoleSink = this@Session.console
                override val input: InputSink = this@Session.input
            }
        }
        var mod: StoryMod? = null

        fun install(story: DurableStory = sampleStory()): StoryMod {
            val installMod = StoryMod("story.mod", story)
            host.install(listOf("story.mod" to installMod))
            val context = installMod.context as ModContextImpl
            context.stringsApi = object : StringsApi {
                override fun declare(key: String, zh: String, en: String, ja: String) = Unit
                override fun of(key: String): String = "zh:$key"
                override fun has(key: String): Boolean = true
                override fun language(): String = "zh"
            }
            context.storage.put("author.key", "author.value")
            mod = installMod
            return installMod
        }

        fun restore(bytes: ByteArray, values: List<Long>) {
            val context = requireNotNull(mod).context as ModContextImpl
            context.rebindStorage(ModStorageStore.load(bytes, setOf("story.mod")))
            context.durableRuntime = null
            input.feed(values)
        }
    }

    @Test
    fun `一个函数两次选择 两次跨存储往返均从断点继续且不重放正文`() {
        val first = Session()
        val firstMod = first.install()
        assertFailsWith<PauseForSave> { firstMod.run() }
        assertEquals(listOf("zh:opening", "zh:first.prompt"), first.console.lines)
        assertEquals(1, first.console.publications)
        assertEquals(1, first.input.requests.size)

        val second = Session()
        val secondMod = second.install()
        second.restore(first.host.storage.encode(), listOf(1L))
        val syncContext = InteractionContext("scene.sync", EntityId(8L), emptyList(), null, emptyMap())
        assertTrue(secondMod.context.interactions.run(syncContext) { ModValue.of("sync") }.returnValue is ModValue)
        assertTrue(second.console.lines.isEmpty(), "既有同步 run 不应被恢复链触碰")
        assertFailsWith<PauseForSave> { (secondMod.context as ModContextImpl).resumeDurableInteraction() }
        assertEquals(listOf("zh:first.prompt", "zh:chosen.right", "zh:second.prompt"), second.console.lines)
        assertEquals(2, second.console.waits.size)

        val third = Session()
        val thirdMod = third.install()
        val secondStorage = (secondMod.context as ModContextImpl).activeStorageStore
        third.restore(secondStorage.encode(), listOf(1L))
        val result = (thirdMod.context as ModContextImpl).resumeDurableInteraction()
        assertNotNull(result)
        assertFalse(result.interrupted)
        assertEquals(listOf("zh:second.prompt", "zh:nested.1", "zh:after"), third.console.lines)
        assertEquals(1, third.console.holds)
        val context = thirdMod.context as ModContextImpl
        assertEquals("author.value", context.storage.get("author.key"))
        assertEquals("", context.activeStorageStore.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY))
        third.host.resumeDurableInteractions()
        assertEquals(3, third.console.lines.size, "空续体标记不得重复恢复")
        first.host.shutdown()
        second.host.shutdown()
        third.host.shutdown()
    }

    @Test
    fun `真实sav与rt2两次存读后仍从同一选择点继续`(@TempDir temp: Path) {
        val first = Session()
        first.install()
        assertFailsWith<PauseForSave> { first.requireMod().run() }

        var storage = first.host.storage
        val variables = VariableStore()
        var service = SaveService(kernel(), EmptyProjector, PackageShortIdRegistry())
        val warnings = mutableListOf<String>()
        val port = object : SidecarStoragePort {
            override fun captureChunks(csv: ModCsvValueBag) = SidecarChunkGroups(
                listOf(PackageChunk("story.mod", storage.encode())), emptyList(),
            )

            override fun captureRt2Bytes(
                binding: com.eratw.port.core.save.SidecarBinding,
                activeChunks: List<PackageChunk>,
                orphanChunks: List<PackageChunk>,
            ): ByteArray = service.captureBundle(binding, activeChunks, orphanChunks).encode()

            override fun restoreRt2(
                bytes: ByteArray,
                binding: com.eratw.port.core.save.SidecarBinding,
            ): SidecarRestoreResult {
                val bundle = SidecarSaveBundle.decode(bytes)
                return when (val outcome = service.restoreBundle(bundle, binding)) {
                    SaveService.RestoreOutcome.Applied -> {
                        storage = ModStorageStore.load(bundle.chunks.single().payload, setOf("story.mod"))
                        SidecarRestoreResult(true)
                    }
                    is SaveService.RestoreOutcome.BindingMismatch -> {
                        warn("binding mismatch")
                        SidecarRestoreResult(false)
                    }
                }
            }

            override fun warn(message: String) { warnings += message }
        }
        SidecarSaveWiring.install(variables, SidecarSaveWiring(port, temp))
        val files = FileSystemOps(variables, RootedSaveFileIo(temp), SaveConfig(systemSaveInBinary = true, zipSaveData = true))
        assertTrue(files.saveData(0, "first"))
        assertTrue(Files.exists(temp.resolve("save00.sav")))
        assertTrue(Files.exists(temp.resolve("save00.sav.rt2")))

        val second = Session()
        second.install()
        storage = second.host.storage
        files.loadData(0)
        second.restore(storage.encode(), listOf(1L))
        assertFailsWith<PauseForSave> { (second.requireMod().context as ModContextImpl).resumeDurableInteraction() }
        assertEquals(listOf("zh:first.prompt", "zh:chosen.right", "zh:second.prompt"), second.console.lines)
        storage = (second.requireMod().context as ModContextImpl).activeStorageStore
        assertTrue(files.saveData(1, "second"))

        val third = Session()
        third.install()
        storage = third.host.storage
        service = SaveService(kernel(), EmptyProjector, PackageShortIdRegistry())
        files.loadData(1)
        assertTrue(warnings.isEmpty(), warnings.joinToString("\n"))
        third.restore(storage.encode(), listOf(1L))
        assertNotNull((third.requireMod().context as ModContextImpl).resumeDurableInteraction())
        assertEquals(listOf("zh:second.prompt", "zh:nested.1", "zh:after"), third.console.lines)
        first.host.shutdown()
        second.host.shutdown()
        third.host.shutdown()
    }

    @Test
    fun `同一剧情与上下文产生逐字节确定续体`() {
        val first = Session()
        first.install()
        assertFailsWith<PauseForSave> {
            first.requireMod().run(
                InteractionContext(
                    "scene.deterministic", EntityId(8L), listOf(EntityId(9L), EntityId(10L)), EntityId(10L),
                    linkedMapOf("z" to "last", "a" to "first"),
                ),
            )
        }
        val second = Session()
        second.install()
        assertFailsWith<PauseForSave> {
            second.requireMod().run(
                InteractionContext(
                    "scene.deterministic", EntityId(8L), listOf(EntityId(9L), EntityId(10L)), EntityId(10L),
                    linkedMapOf("a" to "first", "z" to "last"),
                ),
            )
        }
        assertEquals(
            first.host.storage.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY),
            second.host.storage.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY),
        )
        first.host.shutdown()
        second.host.shutdown()
    }

    @Test
    fun `构建器各分支独立且运行只进入选中分支`() {
        val built = mutableListOf<String>()
        val story = durableStory {
            say("opening")
            choose("pick", listOf("left", "right")) { option ->
                built += option.toString()
                say("branch.$option")
            }
        }
        assertEquals(listOf("0", "1"), built)
        val choice = story.nodes[1] as DurableStoryNode.Choice
        assertEquals(
            listOf(0, 1, 2, 3),
            listOf(story.nodes[0].id, choice.id, choice.branches[0][0].id, choice.branches[1][0].id),
        )
        val session = Session()
        session.install(story)
        session.input.feed(listOf(1L))
        assertNotNull(session.requireMod().run())
        assertEquals(listOf("zh:opening", "zh:pick", "zh:branch.1"), session.console.lines)
        assertEquals(
            listOf("line:zh:pick", "button:zh:left:0", "button:zh:right:1", "publish", "note", "hold", "line:zh:branch.1"),
            session.console.events.takeLast(7),
        )
        session.host.shutdown()
    }

    @Test
    fun `恢复往返保留完整交互上下文并重新执行阶段钩子`() {
        val first = Session()
        val firstMod = first.install()
        val expected = InteractionContext(
            "scene.unicode", EntityId(8L), listOf(EntityId(9L), EntityId(10L)), EntityId(10L),
            mapOf("slot" to "一", "emoji" to "😀"),
        )
        assertFailsWith<PauseForSave> { firstMod.run(expected) }
        val second = Session()
        val secondMod = second.install()
        val observed = mutableListOf<InteractionContext>()
        secondMod.context.interactions.registerHook(InteractionPhase.MAIN) {
            observed += it.interaction
            InteractionResult.CONTINUE
        }
        second.restore(first.host.storage.encode(), listOf(0L, 0L))
        assertNotNull((secondMod.context as ModContextImpl).resumeDurableInteraction())
        assertEquals(listOf(expected), observed)
        first.host.shutdown()
        second.host.shutdown()
    }

    @Test
    fun `每个剧情选择提交只消费一次keySkip`() {
        val session = Session()
        val mod = session.install()
        session.input.feed(listOf(0L, 0L))
        assertNotNull(mod.run())
        assertEquals(2, session.input.mesSkipConsumptions)
        assertFalse(session.input.mesSkip)
        session.host.shutdown()
    }

    @Test
    fun `新局只清活跃包剧情续体并保留孤儿分片`() {
        val source = ModStorageStore()
        source.activate("story.mod")
        source.activate("retired.mod")
        source.putReservedKey("story.mod", DURABLE_STORY_STORAGE_KEY, "ACTIVE_SLOT")
        source.putReservedKey("retired.mod", DURABLE_STORY_STORAGE_KEY, "ORPHAN_SLOT")
        val store = ModStorageStore.load(
            ModStorageSectionCodec.encode(source.sectionsSnapshot()),
            setOf("story.mod"),
        )

        store.clearDurableInteractions()

        assertEquals("", store.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY))
        val orphan = store.sectionsSnapshot().single { it.packageId == "retired.mod" }
        assertEquals(ModValue.StrValue("ORPHAN_SLOT"), orphan.entries[DURABLE_STORY_STORAGE_KEY])
        assertTrue(orphan.orphan)
    }

    @Test
    fun `跨设备恢复用共享实体表重映射剧情上下文的全部实体引用`() {
        fun id(pkg: Int, generation: Int, slot: Int) = EntityIdCodec.encode(pkg, generation, slot)
        val actor = id(2, 3, 4)
        val target = id(2, 5, 6)
        val otherTarget = id(3, 7, 8)
        val first = Session()
        first.install()
        assertFailsWith<PauseForSave> {
            first.requireMod().run(
                InteractionContext(
                    "scene.remap", EntityId(actor), listOf(EntityId(target), EntityId(otherTarget)),
                    EntityId(target), mapOf("slot" to "one"),
                ),
            )
        }
        val originalBytes = first.host.storage.encode()
        val remapped = first.host.storage.remapDurableContexts(EntityIdRemapTable.of(mapOf(2 to 4)))
        assertTrue(originalBytes.contentEquals(first.host.storage.encode()), "重映射不得原地改写来源存档")

        val second = Session()
        val secondMod = second.install()
        val observed = mutableListOf<InteractionContext>()
        secondMod.context.interactions.registerHook(InteractionPhase.MAIN) {
            observed += it.interaction
            InteractionResult.CONTINUE
        }
        second.restore(remapped.encode(), listOf(0L, 0L))
        assertNotNull((secondMod.context as ModContextImpl).resumeDurableInteraction())
        val context = observed.single()
        assertEquals(id(4, 3, 4), context.actor.raw)
        assertEquals(listOf(id(4, 5, 6), otherTarget), context.targets.map { it.raw })
        assertEquals(id(4, 5, 6), context.primaryTarget?.raw)
        first.host.shutdown()
        second.host.shutdown()
    }

    @Test
    fun `跨设备重映射保留已清空剧情续体`() {
        val store = ModStorageStore()
        store.activate("story.mod")
        store.putReservedKey("story.mod", DURABLE_STORY_STORAGE_KEY, "")
        val before = store.encode()

        val remapped = store.remapDurableContexts(EntityIdRemapTable.of(mapOf(2 to 4)))

        assertEquals("", remapped.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY))
        assertTrue(before.contentEquals(remapped.encode()))
    }

    @Test
    fun `非字符串剧情保留键在恢复与跨设备重映射时失败关闭`() {
        val malformedBytes = ModStorageSectionCodec.encode(
            listOf(
                ModStorageSection(
                    "story.mod",
                    linkedMapOf(DURABLE_STORY_STORAGE_KEY to ModValue.IntValue(7)),
                    orphan = false,
                    dataVersion = 0,
                ),
            ),
        )
        val store = ModStorageStore.load(malformedBytes, setOf("story.mod"))
        assertFailsWith<IllegalArgumentException> { durableCheckpoint(store, "story.mod") }
        assertFailsWith<IllegalArgumentException> {
            store.remapDurableContexts(EntityIdRemapTable.of(mapOf(2 to 4)))
        }
        assertTrue(malformedBytes.contentEquals(store.encode()))

        val session = Session()
        session.install()
        session.restore(malformedBytes, emptyList())
        val context = session.requireMod().context as ModContextImpl
        assertFailsWith<IllegalArgumentException> { context.resumeDurableInteraction() }
        assertTrue(session.console.lines.isEmpty())
        session.host.shutdown()
    }

    @Test
    fun `损坏续体在跨设备重映射时失败且来源存储不变`() {
        val store = ModStorageStore()
        store.activate("story.mod")
        store.putReservedKey("story.mod", DURABLE_STORY_STORAGE_KEY, "00")
        val before = store.encode()
        assertFailsWith<IllegalArgumentException> {
            store.remapDurableContexts(EntityIdRemapTable.of(mapOf(2 to 4)))
        }
        assertTrue(before.contentEquals(store.encode()))
    }

    @Test
    fun `版本不匹配和损坏续体 fail closed 且原续体不丢`() {
        val session = Session()
        session.install()
        assertFailsWith<PauseForSave> { session.requireMod().run() }
        val context = session.requireMod().context as ModContextImpl
        val store = context.activeStorageStore
        val saved = store.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY)
        assertNotNull(saved)
        store.setDataVersion("story.mod", 4)
        assertFailsWith<IllegalArgumentException> { context.resumeDurableInteraction() }
        assertEquals(saved, store.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY))
        store.setDataVersion("story.mod", 3)
        assertFailsWith<IllegalArgumentException> { context.resumeDurableInteraction(declaredDataVersion = 2) }
        assertEquals(saved, store.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY))
        store.putReservedKey("story.mod", DURABLE_STORY_STORAGE_KEY, "00")
        session.host.resumeDurableInteractions()
        assertTrue(session.logs.single().contains("pkg=story.mod"))
        assertEquals("00", store.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY))
        store.putReservedKey("story.mod", DURABLE_STORY_STORAGE_KEY, saved.dropLast(2))
        assertFailsWith<IllegalArgumentException> { context.resumeDurableInteraction() }
        assertEquals(saved.dropLast(2), store.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY))
        val flipped = if (saved[0] == '0') "1" + saved.drop(1) else "0" + saved.drop(1)
        store.putReservedKey("story.mod", DURABLE_STORY_STORAGE_KEY, flipped)
        assertFailsWith<IllegalArgumentException> { context.resumeDurableInteraction() }
        assertEquals(flipped, store.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY))
        store.putReservedKey("story.mod", DURABLE_STORY_STORAGE_KEY, "0")
        assertFailsWith<IllegalArgumentException> { context.resumeDurableInteraction() }
        assertEquals("0", store.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY))
        session.host.shutdown()
    }

    @Test
    fun `CRC合法但跳过或预选选择的游标仍拒绝恢复`() {
        val session = Session()
        session.install()
        assertFailsWith<PauseForSave> { session.requireMod().run() }
        val context = session.requireMod().context as ModContextImpl
        val store = context.activeStorageStore
        val saved = requireNotNull(store.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY))
        val outputBefore = session.console.lines.toList()

        val skipped = rewriteInitialCursor(saved, nextIndex = 2, selections = emptyMap())
        store.putReservedKey("story.mod", DURABLE_STORY_STORAGE_KEY, skipped)
        assertFailsWith<IllegalArgumentException> { context.resumeDurableInteraction() }
        assertEquals(skipped, store.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY))
        assertEquals(outputBefore, session.console.lines, "拒绝恢复不得播放被跳过的选择或后续正文")

        val preselected = rewriteInitialCursor(saved, nextIndex = 0, selections = mapOf(1 to 1))
        store.putReservedKey("story.mod", DURABLE_STORY_STORAGE_KEY, preselected)
        assertFailsWith<IllegalArgumentException> { context.resumeDurableInteraction() }
        assertEquals(outputBefore, session.console.lines, "未到达的选择日志不得静默变成玩家输入")
        session.host.shutdown()
    }

    @Test
    fun `global快照排除槽档剧情续体且不丢普通全局存储`() {
        val store = ModStorageStore()
        store.activate("story.mod")
        store.apiFor("story.mod").put("shared", "GLOBAL")
        store.putReservedKey("story.mod", DURABLE_STORY_STORAGE_KEY, "SLOT")
        val sections = store.globalSectionsSnapshot()
        val section = sections.single()
        assertFalse(DURABLE_STORY_STORAGE_KEY in section.entries)
        assertEquals("GLOBAL", (section.entries["shared"] as ModValue.StrValue).value)
        assertEquals("SLOT", store.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY))
    }

    @Test
    fun `global恢复忽略旧档剧情键并保留当前槽续体`() {
        val stale = ModStorageStore()
        stale.activate("story.mod")
        stale.apiFor("story.mod").put("shared", "OLD_GLOBAL")
        stale.putReservedKey("story.mod", DURABLE_STORY_STORAGE_KEY, "STALE_SLOT")
        val slot = ModStorageStore()
        slot.activate("story.mod")
        slot.putReservedKey("story.mod", DURABLE_STORY_STORAGE_KEY, "CURRENT_SLOT")
        val bytes = ModStorageSectionCodec.encode(stale.sectionsSnapshot())
        val restored = ModStorageStore.loadGlobal(bytes, setOf("story.mod"), slot)
        assertEquals("CURRENT_SLOT", restored.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY))
        assertEquals("OLD_GLOBAL", restored.apiFor("story.mod").get("shared"))

        val noSlotCheckpoint = ModStorageStore().apply { activate("story.mod") }
        val cleared = ModStorageStore.loadGlobal(bytes, setOf("story.mod"), noSlotCheckpoint)
        assertNull(cleared.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY))
    }

    private fun rewriteInitialCursor(encoded: String, nextIndex: Int, selections: Map<Int, Int>): String {
        val bytes = ByteArray(encoded.length / 2) {
            encoded.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
        val prefix = bytes.copyOf(bytes.size - 24)
        val tail = ByteArrayOutputStream()
        DataOutputStream(tail).use { out ->
            out.writeInt(1)
            out.writeInt(-1)
            out.writeInt(0)
            out.writeInt(nextIndex)
            out.writeInt(selections.size)
            selections.toSortedMap().forEach { (node, choice) ->
                out.writeInt(node)
                out.writeInt(choice)
            }
        }
        val payload = prefix + tail.toByteArray()
        val checksum = ByteArrayOutputStream().also { bytesOut ->
            DataOutputStream(bytesOut).use { it.writeInt(CRC32().apply { update(payload) }.value.toInt()) }
        }.toByteArray()
        return (payload + checksum).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @Test
    fun `缺失续体不恢复 作者不能删改保留键 越界输入不推进游标`() {
        val session = Session()
        val mod = session.install()
        val context = mod.context as ModContextImpl
        assertNull(context.resumeDurableInteraction())
        assertFailsWith<IllegalArgumentException> { context.storage.remove(DURABLE_STORY_STORAGE_KEY) }
        session.input.feed(listOf(2L))
        assertFailsWith<IllegalArgumentException> { mod.run() }
        val saved = context.activeStorageStore.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY)
        assertNotNull(saved)
        session.input.feed(listOf(0L, 0L))
        assertNotNull(context.resumeDurableInteraction())
        assertTrue(context.activeStorageStore.apiFor("story.mod").get(DURABLE_STORY_STORAGE_KEY)!!.isEmpty())
        session.host.shutdown()
    }

    @Test
    fun `非持久 mod 不分配 durable runtime 且 runDurable 未装配时显式失败`() {
        val host = ModRuntimeHost(
            snapshot = snapshot("plain.mod"), entityKernel = kernel(), nativeMethods = NativeMethodRegistry(),
            logSink = {}, runtime = Scheduler(),
        )
        val mod = StoryMod("plain.mod", sampleStory())
        host.install(listOf("plain.mod" to mod))
        val context = mod.context as ModContextImpl
        assertEquals(null, context.durableRuntime)
        assertFailsWith<IllegalArgumentException> { mod.run() }
        assertEquals(null, context.durableRuntime)
        assertFalse(host.installedContexts.isEmpty())
        host.shutdown()
    }

    private fun Session.requireMod(): StoryMod = requireNotNull(mod)

    private fun sampleStory(): DurableStory = durableStory {
        say("opening")
        choose("first.prompt", listOf("left", "right")) { choice ->
            if (choice == 0) say("chosen.left") else say("chosen.right")
            choose("second.prompt", listOf("a", "b")) { nested -> say("nested.$nested") }
        }
        say("after")
    }
}
