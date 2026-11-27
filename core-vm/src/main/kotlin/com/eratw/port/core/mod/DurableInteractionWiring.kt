package com.eratw.port.core.mod

import com.eratw.port.core.entity.EntityIdRemapTable
import com.eratw.port.modapi.DurableStory
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionRunResult
import com.eratw.port.modapi.ModValue

internal fun ModContextImpl.runDurableInteraction(
    context: InteractionContext,
    story: DurableStory,
): InteractionRunResult = durableRuntime().run(context, story)

internal fun ModContextImpl.resumeDurableInteraction(declaredDataVersion: Int? = null): InteractionRunResult? {
    val saved = durableCheckpoint(activeStorageStore, packageId) ?: return null
    if (saved.isEmpty()) return null
    return durableRuntime().resume(saved, declaredDataVersion)
}

internal fun durableCheckpoint(section: ModStorageSection): String? =
    decodeDurableCheckpointValue(section.entries[DURABLE_STORY_STORAGE_KEY])

internal fun durableCheckpoint(store: ModStorageStore, packageId: String): String? =
    decodeDurableCheckpointValue(store.sections[packageId]?.get(DURABLE_STORY_STORAGE_KEY))

private fun decodeDurableCheckpointValue(value: ModValue?): String? {
    if (value == null) return null
    require(value is ModValue.StrValue) { "剧情续体保留键类型非法：$value" }
    return value.value
}

/** 跨设备恢复：剧情上下文与实体图共用同一张重映射表；恒等表不重建容器。 */
fun ModStorageStore.remapDurableContexts(remapTable: EntityIdRemapTable): ModStorageStore {
    if (remapTable.isEmpty) return this
    var changed = false
    val remapped = sectionsSnapshot().map { section ->
        val checkpoint = durableCheckpoint(section)
        if (checkpoint.isNullOrEmpty()) {
            section
        } else {
            changed = true
            val remappedCheckpoint = ModValue.StrValue(remapDurableCheckpoint(checkpoint, remapTable))
            ModStorageSection(
                section.packageId,
                section.entries + (DURABLE_STORY_STORAGE_KEY to remappedCheckpoint),
                section.orphan,
                section.dataVersion,
            )
        }
    }
    if (!changed) return this
    return ModStorageStore.load(ModStorageSectionCodec.encode(remapped), activePackages)
}

/** 新局建立新时间线：只清活跃包的槽档剧情续体，孤儿分片仍原样继承。 */
fun ModStorageStore.clearDurableInteractions() {
    for (section in sectionsSnapshot()) {
        if (!section.orphan) putReservedKey(section.packageId, DURABLE_STORY_STORAGE_KEY, "")
    }
}

/** GLOBAL 档不持有槽档剧情续体；槽档 checkpoint 仍随 saveNN.rt2 往返。 */
fun ModStorageStore.globalSectionsSnapshot(): List<ModStorageSection> = sectionsSnapshot().map { section ->
    ModStorageSection(
        section.packageId,
        section.entries.filterKeys { it != DURABLE_STORY_STORAGE_KEY },
        section.orphan,
        section.dataVersion,
    )
}

/** 忽略旧 GLOBAL 档误带的剧情键，并以当前槽档 checkpoint 为准。 */
fun ModStorageStore.Companion.loadGlobal(
    bytes: ByteArray,
    knownPackages: Set<String>,
    slotStore: ModStorageStore,
): ModStorageStore {
    val restored = load(bytes, knownPackages)
    restored.sections.values.forEach { it.remove(DURABLE_STORY_STORAGE_KEY) }
    for ((packageId, values) in slotStore.sections) {
        if (DURABLE_STORY_STORAGE_KEY !in values) continue
        restored.sections.getOrPut(packageId) { LinkedHashMap() }[DURABLE_STORY_STORAGE_KEY] =
            values.getValue(DURABLE_STORY_STORAGE_KEY)
    }
    return restored
}

private fun ModContextImpl.durableRuntime(): DurableInteractionRuntime {
    durableRuntime?.let { return it }
    val host = requireNotNull(durableInteractionHost) {
        "可挂起剧情运行时未装配（缺少引擎 INPUT/WAIT 与 ConsoleSink）"
    }
    return DurableInteractionRuntime(
        packageId,
        { activeStorageStore },
        { strings },
        { interactionPipeline },
        host,
    ).also { durableRuntime = it }
}
