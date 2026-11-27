package com.eratw.port.modapi

private const val DURABLE_STORY_MAX_CHOICES = 32
private const val DURABLE_STORY_MAX_DEPTH = 32

/**
 * 可序列化的剧情节点。由 [durableStory] 构建，作者不直接构造。
 *
 * 节点只保存文案键与子序列，不保存 lambda、线程、协程或任意 Kotlin 对象；引擎据此在
 * INPUT/WAIT 边界恢复。文本与选项在呈现时经 [StringsApi] 按当前语言解析。
 */
sealed interface DurableStoryNode {
    val id: Int

    data class Say(override val id: Int, val textKey: String) : DurableStoryNode

    data class Choice(
        override val id: Int,
        val promptKey: String,
        val optionKeys: List<String>,
        val branches: List<List<DurableStoryNode>>,
    ) : DurableStoryNode
}

/** 一段可挂起、可随存档恢复的 Mod 剧情。 */
class DurableStory(val nodes: List<DurableStoryNode>)

/**
 * 线性剧情构建器。[choose] 会为每个候选分支调用一次 [branch]，由引擎随后只执行玩家选中的
 * 分支。分支构建期间不得产生业务副作用；需要世界状态变更时使用引擎的既有 Action/Mod API。
 */
class DurableStoryBuilder internal constructor() {
    private val nodes = ArrayList<DurableStoryNode>()
    private var nextId = 0
    private var depth = 0

    fun say(textKey: String) {
        requireKey(textKey, "剧情文案键")
        nodes += DurableStoryNode.Say(nextId++, textKey)
    }

    fun choose(
        promptKey: String,
        optionKeys: List<String>,
        branch: DurableStoryBuilder.(Int) -> Unit,
    ) {
        requireKey(promptKey, "选择提示键")
        require(optionKeys.size in 2..DURABLE_STORY_MAX_CHOICES) {
            "选择项数量须为 2..$DURABLE_STORY_MAX_CHOICES：${optionKeys.size}"
        }
        require(optionKeys.distinct().size == optionKeys.size) { "选择项文案键不得重复" }
        optionKeys.forEach { requireKey(it, "选择项文案键") }
        require(depth < DURABLE_STORY_MAX_DEPTH) { "剧情分支深度超过 $DURABLE_STORY_MAX_DEPTH" }
        val id = nextId++
        val branches = optionKeys.indices.map { option ->
            depth++
            try {
                val child = DurableStoryBuilder()
                child.depth = depth
                child.branch(option)
                child.nodes.toList()
            } finally {
                depth--
            }
        }
        nodes += DurableStoryNode.Choice(id, promptKey, optionKeys.toList(), branches)
    }

    internal fun build(): DurableStory {
        var nextId = 0
        fun normalize(nodes: List<DurableStoryNode>): List<DurableStoryNode> = nodes.map { node ->
            when (node) {
                is DurableStoryNode.Say -> DurableStoryNode.Say(nextId++, node.textKey)
                is DurableStoryNode.Choice -> DurableStoryNode.Choice(
                    nextId++, node.promptKey, node.optionKeys, node.branches.map(::normalize),
                )
            }
        }
        return DurableStory(normalize(nodes))
    }

    private fun requireKey(key: String, label: String) {
        require(key.length in 1..StringsSpec.MAX_KEY_LENGTH) { "$label 长度非法：${key.length}" }
        require(key.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' || it == '-' }) {
            "$label 只许小写字母/数字/_/./-：$key"
        }
    }
}

/** 在一个函数内构建线性剧情；构建结果只含可序列化节点。 */
fun durableStory(build: DurableStoryBuilder.() -> Unit): DurableStory =
    DurableStoryBuilder().apply(build).build()
