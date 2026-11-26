package com.eratw.port.modtesting

import com.eratw.port.modapi.ModUiElement
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.UiApi
import com.eratw.port.modapi.modUiImage

/**
 * 录制型界面贡献面（测试替身）。
 *
 * 口径与生产 `InstanceUiRegistry`（`:core-vm` 的 `ModContextImpl.kt:681-769`）**逐条对齐**，
 * 差别只在「点击派发落到哪一层」：
 *
 * - 交互项按 `labelKey` 登记，同键**后声明覆盖**；返回的 [Registration] 撤销时只撤自己那一笔
 *   （同键被覆盖后，旧 registration 的撤销不会误删新项）；
 * - 图片按 `resourceName` 登记，声明期复用 [modUiImage] 的构造校验（非法名 / 超长 altText /
 *   越界 maxSidePx 当场抛 [IllegalArgumentException]），同资源重复声明 = 后声明覆盖；
 * - 可见性谓词在**查询 / 命中时现求值**（不是注册时求一次）；谓词抛任意异常按「不可见 + WARN」
 *   降级，WARN 文案与生产逐字一致（`ModContextImpl.kt:764-766`），同时落 [warnings] 与 `onWarn`；
 * - 生产界面登记表的 `uiClick` **不对回调异常做兜底**（`ModContextImpl.kt:747-752`：命中即直接
 *   `onClick()`，异常原样冒泡）；隔离发生在宿主派发层 `ModUiSurface.dispatch` 的 `runCatching`
 *   里（`app/src/main/kotlin/com/eratw/port/android/ModUiSurface.kt:162-176`）。台架没有 app 壳，
 *   [click] 对齐的是**宿主派发层**：吞掉回调异常、记 [UiClickOutcome.failure] + WARN 后返回
 *   `handled = false`，让作者用例看到「点了但失败了」而不是炸掉整个台架。要验证生产登记表的
 *   裸行为请用 `:core-vm` 的 `ModContextImpl` 装配（对拍见 `RecordingUiProductionParityTest`）。
 *
 * ## 观察面（台架自有，不是作者 API）
 *
 * [elements] / [find] 是**诊断超集**：返回全部已登记项（含当前不可见的交互项），回答「我到底
 * 登记了什么」；[visibleKeys] 才是生产 `InstanceUiRegistry.registeredKeys()` 的同口径快照
 * （声明序、只含谓词现求值为真的项）。[clear] 清空全部登记与录制账本（同一 world 复跑用例时
 * 防串测）。
 *
 * @param packageId 本替身所属的包身份（WARN 文案与生产同口径点名它）。
 * @param onWarn 宿主日志面（谓词异常 / 点击隔离的 WARN 都经它落痕；[TestContext] 接到自己的日志表）。
 */
class RecordingUi(
    val packageId: String,
    private val onWarn: (String) -> Unit = {},
) : UiApi {

    /** 已登记元素的种类（交互项 / 图片）。 */
    enum class ElementKind {
        /** `ui.registerInteraction` 登记的文本交互项。 */
        INTERACTION,

        /** `ui.registerImage` 登记的图片元素。 */
        IMAGE,
    }

    /**
     * 一条已登记元素的只读快照（**台架观察面**，不是作者 API）。
     *
     * 谓词与回调原样回放，台架不替作者求值（现求值口径见 [visibleKeys] / [click]）。
     *
     * @property id 交互项的 `labelKey` 或图片的 `resourceName`（与生产登记表的键同口径）。
     * @property kind 登记入口（[ElementKind]）。
     * @property visiblePredicate 交互项的可见性谓词；`null` = 恒可见（图片元素恒 `null`）。
     * @property onClick 交互项的点击回调（图片元素恒 `null`）。
     * @property image 图片元素的声明（交互项恒 `null`）。
     */
    data class UiElementRecord(
        val id: String,
        val kind: ElementKind,
        val visiblePredicate: (() -> Boolean)? = null,
        val onClick: (() -> Unit)? = null,
        val image: ModUiElement.Image? = null,
    )

    /**
     * 一次模拟点击的结果（**台架观察面**，不是作者 API）。
     *
     * `handled = true` 只在「命中、当前可见、回调正常返回」时出现；回调抛异常时
     * `handled = false` 且 [failure] 非 `null`（宿主派发层隔离口径，见类型 KDoc）。
     */
    data class UiClickOutcome(
        val labelKey: String,
        val handled: Boolean,
        val failure: Throwable? = null,
    )

    /** 交互项载体（与生产 `InstanceUiRegistry.InteractionAction` 同形，只在类内可见）。 */
    private class InteractionAction(
        val labelKey: String,
        val onClick: () -> Unit,
        val visible: (() -> Boolean)?,
    )

    private val interactions = LinkedHashMap<String, InteractionAction>()
    private val imageMap = LinkedHashMap<String, ModUiElement.Image>()
    private val clickOutcomesLog = mutableListOf<UiClickOutcome>()
    private val warningsLog = mutableListOf<String>()

    override fun registerInteraction(
        labelKey: String,
        visible: (() -> Boolean)?,
        onClick: () -> Unit,
    ): Registration {
        // 与生产同口径：同键后声明覆盖，撤销只撤自己那一笔（旧 registration 不误删新项）。
        val action = InteractionAction(labelKey, onClick, visible)
        interactions[labelKey] = action
        return object : Registration {
            override fun unregister() {
                if (interactions[labelKey] === action) {
                    interactions.remove(labelKey)
                }
            }
        }
    }

    override fun registerImage(resourceName: String, altText: String, maxSidePx: Int?): Registration {
        // 与生产同口径：声明期就按 ModUiElement.Image 的构造校验拒非法名 / 超长 altText / 越界边长。
        val element = modUiImage(resourceName, altText, maxSidePx)
        imageMap[element.resourceName] = element
        return object : Registration {
            override fun unregister() {
                if (imageMap[element.resourceName] === element) {
                    imageMap.remove(element.resourceName)
                }
            }
        }
    }

    /** 已声明的图片元素（声明序；等价生产 `registeredImages()`）。 */
    val images: List<ModUiElement.Image>
        get() = if (imageMap.isEmpty()) emptyList() else imageMap.values.toList()

    /** 全部已登记元素（交互项先按登记序，图片再按登记序；含当前不可见项）。 */
    fun elements(): List<UiElementRecord> = buildList {
        for (action in interactions.values) {
            add(
                UiElementRecord(
                    id = action.labelKey,
                    kind = ElementKind.INTERACTION,
                    visiblePredicate = action.visible,
                    onClick = action.onClick,
                ),
            )
        }
        for (image in imageMap.values) {
            add(UiElementRecord(id = image.resourceName, kind = ElementKind.IMAGE, image = image))
        }
    }

    /** 按 id 找一条登记快照（先查交互项，再查图片；找不到返回 `null`）。 */
    fun find(id: String): UiElementRecord? =
        interactions[id]?.let { action ->
            UiElementRecord(
                id = action.labelKey,
                kind = ElementKind.INTERACTION,
                visiblePredicate = action.visible,
                onClick = action.onClick,
            )
        } ?: imageMap[id]?.let { image ->
            UiElementRecord(id = image.resourceName, kind = ElementKind.IMAGE, image = image)
        }

    /**
     * 当前可见交互项键（声明序）——与生产 `InstanceUiRegistry.registeredKeys()` 同口径：
     * 谓词现求值，`false` 或抛异常的项不在集合里。
     */
    fun visibleKeys(): Set<String> {
        val out = LinkedHashSet<String>()
        for (action in interactions.values) {
            if (isVisible(action)) out += action.labelKey
        }
        return out
    }

    /**
     * 模拟一次宿主派发点击（层意见类型 KDoc）：不存在 / 当前不可见 = 不命中、不跑回调；
     * 命中则跑回调，回调抛异常被隔离并记录（`handled = false` + [UiClickOutcome.failure] + WARN）。
     *
     * 每次调用都落一条 [clickOutcomes]（无论命中与否），作者可断言「点了没」「失败没」。
     */
    fun click(labelKey: String): UiClickOutcome {
        val action = interactions[labelKey]
        val outcome = when {
            action == null -> UiClickOutcome(labelKey, handled = false)
            !isVisible(action) -> UiClickOutcome(labelKey, handled = false)
            else -> runCatching { action.onClick() }.fold(
                onSuccess = { UiClickOutcome(labelKey, handled = true) },
                onFailure = { failure ->
                    warnClickFailure(action.labelKey, failure)
                    UiClickOutcome(labelKey, handled = false, failure = failure)
                },
            )
        }
        clickOutcomesLog += outcome
        return outcome
    }

    /** 全部模拟点击结果（发生序）。 */
    val clickOutcomes: List<UiClickOutcome> get() = clickOutcomesLog.toList()

    /** 最近一次模拟点击结果（没点过返回 `null`）。 */
    val lastClickOutcome: UiClickOutcome? get() = clickOutcomesLog.lastOrNull()

    /** 替身落下的 WARN（谓词异常 / 点击回调异常隔离；文案与生产 / 宿主派发层同一份）。 */
    val warnings: List<String> get() = warningsLog.toList()

    /** 清空全部登记与录制账本（同一 world 复跑用例时复位；不动其它上下文状态）。 */
    fun clear() {
        interactions.clear()
        imageMap.clear()
        clickOutcomesLog.clear()
        warningsLog.clear()
    }

    /** 现求值可见性：谓词缺省 = 可见；返回 `false` 或抛任意异常 = 不可见（异常落 WARN，不上抛）。 */
    private fun isVisible(action: InteractionAction): Boolean {
        val predicate = action.visible ?: return true
        return runCatching { predicate() }.getOrElse { failure ->
            // 文案逐字照生产 ModContextImpl.isActionVisible：对拍用例断言原文（不是前缀）。
            val message =
                "[ui][WARN] 交互项可见性谓词抛异常，按不可见降级 pkg=$packageId " +
                    "key=${action.labelKey} error=$failure"
            warningsLog += message
            onWarn(message)
            false
        }
    }

    /** 点击回调异常：对齐宿主派发层的日志文案（app 的 ModUiSurface.dispatch），隔离不上抛。 */
    private fun warnClickFailure(labelKey: String, failure: Throwable) {
        val message = "mod.ui 点击失败已隔离 pkg=$packageId key=$labelKey：" +
            "${failure.javaClass.name}: ${failure.message}"
        warningsLog += message
        onWarn(message)
    }
}
