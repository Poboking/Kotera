package com.eratw.port.core.mod

import com.eratw.port.core.entity.EntityIdRemapTable
import com.eratw.port.core.host.ConsoleSink
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.host.InputOutcome
import com.eratw.port.modapi.DurableStory
import com.eratw.port.modapi.DurableStoryNode
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionRunResult
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.StringsApi
import com.eratw.port.modapi.StringsSpec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.CRC32

internal const val DURABLE_STORY_STORAGE_KEY: String = "__eratw.durable-story.v1"

interface DurableInteractionHost {
    val console: ConsoleSink
    val input: InputSink
}

internal class DurableInteractionRuntime(
    private val packageId: String,
    private val storage: () -> ModStorageStore,
    private val strings: () -> StringsApi,
    private val pipeline: () -> InteractionPipeline,
    private val host: DurableInteractionHost,
) {
    fun run(context: InteractionContext, story: DurableStory): InteractionRunResult {
        check(!running) { "同一包不能同时运行两段可挂起剧情" }
        validateStory(story)
        validateContext(context)
        running = true
        try {
            storage().putReservedKey(packageId, DURABLE_STORY_STORAGE_KEY, "")
            val checkpoint = Checkpoint(
                context,
                storage().dataVersionsByPackage[packageId] ?: 0,
                story,
                listOf(CursorFrame(-1, 0, 0)),
                emptyMap(),
            )
            return pipeline().run(context) { execute(checkpoint) }
        } finally {
            running = false
        }
    }

    fun resume(encoded: String, declaredDataVersion: Int? = null): InteractionRunResult? {
        if (encoded.isEmpty()) return null
        val checkpoint = decodeCheckpoint(encoded)
        val storedVersion = storage().dataVersionsByPackage[packageId] ?: 0
        require(checkpoint.dataVersion == storedVersion) {
            "剧情续体的数据版本不匹配：saved=${checkpoint.dataVersion}, current=$storedVersion"
        }
        require(declaredDataVersion == null || checkpoint.dataVersion == declaredDataVersion) {
            "剧情续体与当前 Mod 声明的数据版本不匹配：saved=${checkpoint.dataVersion}, declared=$declaredDataVersion"
        }
        check(!running) { "同一包不能同时运行两段可挂起剧情" }
        running = true
        try {
            return pipeline().run(checkpoint.context) { execute(checkpoint) }
        } finally {
            running = false
        }
    }

    private var running = false

    private fun execute(initial: Checkpoint): ModValue? {
        val nodesById = indexNodes(initial.story.nodes)
        val frames = ArrayDeque(initial.frames)
        val selections = initial.selections.toMutableMap()
        while (frames.isNotEmpty()) {
            val frame = frames.last()
            val sequence = sequenceOf(initial.story, frame, nodesById)
            if (frame.nextIndex !in sequence.indices) {
                frames.removeLast()
                continue
            }
            val node = sequence[frame.nextIndex]
            when (node) {
                is DurableStoryNode.Say -> {
                    host.console.printLine(strings().of(node.textKey))
                    frame.nextIndex++
                }
                is DurableStoryNode.Choice -> {
                    val previousSelection = selections[node.id]
                    if (previousSelection == null) {
                        host.console.printLine(strings().of(node.promptKey))
                        node.optionKeys.forEachIndexed { index, key ->
                            host.console.printButton(strings().of(key), index.toLong())
                        }
                        persist(initial.copy(frames = frames.toList(), selections = selections))
                        val request = InputRequest(type = InputRequest.InputType.INT)
                        host.console.publishFrame()
                        host.console.noteInputWait(request)
                        val outcome = host.input.waitInput(request)
                        host.console.holdFrame()
                        // durable 选择也是输入提交：必须取走本次 keySkip，不能留给下一次普通 INPUT/WAIT。
                        host.input.consumeMesSkip()
                        val choice = outcome.intValue
                        require(choice in node.optionKeys.indices) {
                            "可挂起剧情选择越界：node=${node.id}, choice=$choice"
                        }
                        selections[node.id] = choice.toInt()
                    } else {
                        require(previousSelection in node.optionKeys.indices) { "存档中的剧情选择越界：node=${node.id}" }
                    }
                    val selected = selections.getValue(node.id)
                    frame.nextIndex++
                    frames.addLast(CursorFrame(node.id, selected, 0))
                    persist(initial.copy(frames = frames.toList(), selections = selections))
                }
            }
        }
        storage().putReservedKey(packageId, DURABLE_STORY_STORAGE_KEY, "")
        return null
    }

    private fun persist(checkpoint: Checkpoint) {
        val encoded = encodeCheckpoint(checkpoint)
        require(encoded.length <= MAX_ENCODED_CHARS) { "剧情续体超过 $MAX_ENCODED_CHARS 个十六进制字符" }
        storage().putReservedKey(packageId, DURABLE_STORY_STORAGE_KEY, encoded)
    }

    private fun validateContext(context: InteractionContext) {
        require(context.targets.size <= MAX_TARGETS) { "剧情参与者数量非法" }
        require(context.metadata.size <= MAX_TARGETS) { "剧情元数据数量非法" }
    }
}

private data class Checkpoint(
    val context: InteractionContext,
    val dataVersion: Int,
    val story: DurableStory,
    val frames: List<CursorFrame>,
    val selections: Map<Int, Int>,
)

private data class CursorFrame(val parentChoiceId: Int, val branchIndex: Int, var nextIndex: Int)

internal fun remapDurableCheckpoint(encoded: String, remapTable: EntityIdRemapTable): String {
    val checkpoint = decodeCheckpoint(encoded)
    val context = checkpoint.context
    return encodeCheckpoint(
        checkpoint.copy(
            context = context.copy(
                actor = EntityId(remapTable.remap(context.actor.raw)),
                targets = context.targets.map { EntityId(remapTable.remap(it.raw)) },
                primaryTarget = context.primaryTarget?.let { EntityId(remapTable.remap(it.raw)) },
            ),
        ),
    )
}

private fun encodeCheckpoint(checkpoint: Checkpoint): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        out.write(MAGIC)
        out.writeInt(FORMAT_VERSION)
        out.writeInt(checkpoint.dataVersion)
        writeContext(out, checkpoint.context)
        writeStorySequence(out, checkpoint.story.nodes)
        out.writeInt(checkpoint.frames.size)
        checkpoint.frames.forEach {
            out.writeInt(it.parentChoiceId)
            out.writeInt(it.branchIndex)
            out.writeInt(it.nextIndex)
        }
        out.writeInt(checkpoint.selections.size)
        checkpoint.selections.toSortedMap().forEach { (node, choice) ->
            out.writeInt(node)
            out.writeInt(choice)
        }
    }
    val payload = bytes.toByteArray()
    val checksum = CRC32().apply { update(payload) }.value.toInt()
    DataOutputStream(bytes).use { it.writeInt(checksum) }
    return bytes.toByteArray().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun decodeCheckpoint(encoded: String): Checkpoint {
    require(encoded.length <= MAX_ENCODED_CHARS && encoded.length % 2 == 0) { "剧情续体长度非法" }
    val bytes = ByteArray(encoded.length / 2)
    for (index in bytes.indices) {
        val high = Character.digit(encoded[index * 2], 16)
        val low = Character.digit(encoded[index * 2 + 1], 16)
        require(high >= 0 && low >= 0) { "剧情续体不是合法十六进制" }
        bytes[index] = ((high shl 4) or low).toByte()
    }
    return try {
        decodeBytes(bytes)
    } catch (e: IOException) {
        throw IllegalArgumentException("剧情续体损坏或截断", e)
    }
}

private fun decodeBytes(bytes: ByteArray): Checkpoint {
    require(bytes.size >= MAGIC.size + Int.SIZE_BYTES * 2) { "剧情续体长度非法" }
    val payloadEnd = bytes.size - Int.SIZE_BYTES
    val payload = bytes.copyOf(payloadEnd)
    val checksumInput = DataInputStream(ByteArrayInputStream(bytes, payloadEnd, Int.SIZE_BYTES))
    val checksum = CRC32().apply { update(payload) }.value.toInt()
    require(checksumInput.readInt() == checksum) { "剧情续体校验和不匹配" }
    val input = DataInputStream(ByteArrayInputStream(payload))
    val magic = ByteArray(MAGIC.size)
    require(input.read(magic) == MAGIC.size && magic.contentEquals(MAGIC)) { "剧情续体魔数不匹配" }
    require(input.readInt() == FORMAT_VERSION) { "剧情续体格式版本不受支持" }
    val dataVersion = input.readInt().also { require(it >= 0) { "剧情数据版本非法" } }
    val context = readContext(input)
    var nextNodeId = 0
    var remainingNodes = MAX_NODES
    fun readNodes(depth: Int): List<DurableStoryNode> {
        require(depth <= MAX_DEPTH) { "剧情分支深度非法" }
        val count = input.readInt().also { require(it in 0..remainingNodes) { "剧情节点数量非法" } }
        remainingNodes -= count
        return List(count) {
            when (input.readUnsignedByte()) {
                1 -> DurableStoryNode.Say(nextNodeId++, input.readUTF())
                2 -> {
                    val prompt = input.readUTF()
                    val options = List(input.readInt().also { require(it in 2..MAX_CHOICES) { "剧情选项非法" } }) {
                        input.readUTF()
                    }
                    val branchCount = input.readInt().also { require(it == options.size) { "剧情分支数量非法" } }
                    val id = nextNodeId++
                    val branches = List(branchCount) { readNodes(depth + 1) }
                    DurableStoryNode.Choice(id, prompt, options, branches)
                }
                else -> throw IllegalArgumentException("剧情节点类型非法")
            }
        }
    }
    val story = DurableStory(readNodes(0))
    val frames = List(input.readInt().also { require(it in 1..MAX_FRAMES) { "剧情游标深度非法" } }) {
        CursorFrame(
            input.readInt(),
            input.readInt().also { require(it >= 0) { "剧情游标分支非法" } },
            input.readInt().also { require(it >= 0) { "剧情游标非法" } },
        )
    }
    require(frames.first().parentChoiceId == -1) { "剧情根游标非法" }
    val selections = List(input.readInt().also { require(it in 0..MAX_NODES) { "剧情选择日志非法" } }) {
        input.readInt() to input.readInt()
    }
    require(selections.map { it.first }.distinct().size == selections.size) { "剧情选择日志节点重复" }
    val selectionMap = selections.toMap()
    require(input.read() == -1) { "剧情续体存在多余字节" }
    validateStory(story)
    validateCursor(story, frames, selectionMap)
    return Checkpoint(context, dataVersion, story, frames, selectionMap)
}

private fun writeContext(out: DataOutputStream, context: InteractionContext) {
    out.writeUTF(context.interactionId)
    out.writeLong(context.actor.raw)
    out.writeInt(context.targets.size)
    context.targets.forEach { out.writeLong(it.raw) }
    out.writeBoolean(context.primaryTarget != null)
    context.primaryTarget?.let { out.writeLong(it.raw) }
    out.writeInt(context.metadata.size)
    context.metadata.toSortedMap().forEach { (key, value) ->
        out.writeUTF(key)
        out.writeUTF(value)
    }
}

private fun readContext(input: DataInputStream): InteractionContext {
    val interactionId = input.readUTF()
    val actor = EntityId(input.readLong())
    val targets = List(input.readInt().also { require(it in 0..MAX_TARGETS) { "剧情参与者数量非法" } }) {
        EntityId(input.readLong())
    }
    val primary = if (input.readBoolean()) EntityId(input.readLong()) else null
    val metadata = List(input.readInt().also { require(it in 0..MAX_TARGETS) { "剧情元数据数量非法" } }) {
        input.readUTF() to input.readUTF()
    }
    require(metadata.map { it.first }.distinct().size == metadata.size) { "剧情元数据键重复" }
    return InteractionContext(interactionId, actor, targets, primary, metadata.toMap())
}

private fun writeStorySequence(out: DataOutputStream, nodes: List<DurableStoryNode>) {
    out.writeInt(nodes.size)
    nodes.forEach { node ->
        when (node) {
            is DurableStoryNode.Say -> {
                out.writeByte(1)
                out.writeUTF(node.textKey)
            }
            is DurableStoryNode.Choice -> {
                out.writeByte(2)
                out.writeUTF(node.promptKey)
                out.writeInt(node.optionKeys.size)
                node.optionKeys.forEach { out.writeUTF(it) }
                out.writeInt(node.branches.size)
                node.branches.forEach { writeStorySequence(out, it) }
            }
        }
    }
}

private fun sequenceOf(
    story: DurableStory,
    frame: CursorFrame,
    nodesById: Map<Int, DurableStoryNode>,
): List<DurableStoryNode> = if (frame.parentChoiceId < 0) {
    story.nodes
} else {
    val node = nodesById[frame.parentChoiceId] as? DurableStoryNode.Choice
        ?: throw IllegalArgumentException("剧情恢复游标指向不存在的选择节点：${frame.parentChoiceId}")
    node.branches.getOrNull(frame.branchIndex)
        ?: throw IllegalArgumentException("剧情恢复游标指向不存在的分支：${frame.parentChoiceId}/${frame.branchIndex}")
}

private fun validateStory(story: DurableStory) {
    var count = 0
    fun visit(nodes: List<DurableStoryNode>, depth: Int) {
        require(depth <= MAX_DEPTH) { "剧情分支深度超过 $MAX_DEPTH" }
        nodes.forEach { node ->
            require(node.id == count) { "剧情节点 id 不连续：${node.id} != $count" }
            count++
            require(count <= MAX_NODES) { "剧情节点超过 $MAX_NODES" }
            when (node) {
                is DurableStoryNode.Say -> requireKey(node.textKey)
                is DurableStoryNode.Choice -> {
                    requireKey(node.promptKey)
                    require(node.optionKeys.size in 2..MAX_CHOICES) { "剧情选项数量非法" }
                    require(node.optionKeys.distinct().size == node.optionKeys.size) { "剧情选项文案键重复" }
                    node.optionKeys.forEach(::requireKey)
                    require(node.branches.size == node.optionKeys.size) { "剧情分支数量非法" }
                    node.branches.forEach { visit(it, depth + 1) }
                }
            }
        }
    }
    visit(story.nodes, 0)
}

private fun validateCursor(story: DurableStory, frames: List<CursorFrame>, selections: Map<Int, Int>) {
    val nodesById = indexNodes(story.nodes)
    val expectedSelections = mutableSetOf<Int>()
    require(frames.first().parentChoiceId == -1 && frames.first().branchIndex == 0) { "剧情根游标非法" }
    frames.forEachIndexed { index, frame ->
        val sequence = sequenceOf(story, frame, nodesById)
        require(frame.nextIndex in 0..sequence.size) { "剧情恢复游标越界" }
        sequence.take(frame.nextIndex).forEach { node ->
            if (node is DurableStoryNode.Choice) expectedSelections += node.id
        }
        if (index > 0) {
            val parent = frames[index - 1]
            val parentSequence = sequenceOf(story, parent, nodesById)
            val choiceIndex = parent.nextIndex - 1
            val choice = parentSequence.getOrNull(choiceIndex) as? DurableStoryNode.Choice
                ?: throw IllegalArgumentException("剧情恢复游标父帧未停在已选择节点：${frame.parentChoiceId}")
            require(choice.id == frame.parentChoiceId) { "剧情恢复游标不是当前父选择的分支" }
            require(selections[frame.parentChoiceId] == frame.branchIndex) { "剧情恢复分支与选择日志不一致" }
            require(frame.branchIndex in choice.branches.indices) { "剧情恢复游标指向不存在的分支" }
        }
    }
    val choiceIds = nodesById.values.filterIsInstance<DurableStoryNode.Choice>().associateBy { it.id }
    require(selections.keys == expectedSelections) {
        "剧情选择日志与已到达路径不一致：expected=$expectedSelections actual=${selections.keys}"
    }
    for ((nodeId, branchIndex) in selections) {
        val choice = choiceIds.getValue(nodeId)
        require(branchIndex in choice.branches.indices) { "存档中的剧情选择越界：node=$nodeId" }
    }
}

private fun indexNodes(nodes: List<DurableStoryNode>): Map<Int, DurableStoryNode> {
    val indexed = mutableMapOf<Int, DurableStoryNode>()
    fun visit(sequence: List<DurableStoryNode>) {
        sequence.forEach { node ->
            indexed[node.id] = node
            if (node is DurableStoryNode.Choice) node.branches.forEach(::visit)
        }
    }
    visit(nodes)
    return indexed
}

private fun requireKey(key: String) {
    require(key.length in 1..StringsSpec.MAX_KEY_LENGTH) { "剧情文案键长度非法" }
    require(key.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' || it == '-' }) {
        "剧情文案键只许小写字母/数字/_/./-"
    }
}

private val MAGIC = "ERATWDS1".toByteArray(Charsets.US_ASCII)
private const val FORMAT_VERSION = 1
private const val MAX_DEPTH = 32
private const val MAX_FRAMES = MAX_DEPTH + 1
private const val MAX_NODES = 4096
private const val MAX_CHOICES = 32
private const val MAX_TARGETS = 256
private const val MAX_ENCODED_CHARS = 65_535
