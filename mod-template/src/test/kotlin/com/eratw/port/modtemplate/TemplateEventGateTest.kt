package com.eratw.port.modtemplate

import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modtesting.TestWorld
import com.eratw.port.modtesting.runMod
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 事件存在性闸的示例面哨兵（生产形状）。
 *
 * 生产会话把链接期 `SymbolCatalog.events` 灌成已知事件集（eraTW 语料定义了 EVENTFIRST /
 * EVENTTRAIN / EVENTLOAD / EVENTCOM / EVENTEND / EVENTCOMEND 等 ERB 事件函数，集合恒非空；
 * 事件名全集 = 引擎 `Symbols.EVENT_LABEL_NAMES` 固定标号表：1.824 基线九个，蛇版 4.981 另有
 * BEFORE_THROW / BEFORE_ERROR（在册 B24，未进表前不属已知事件）），`events.register` 对不在集内
 * 的键当场抛 [IllegalArgumentException]（附最接近候选）。
 *
 * 本文件钉住的事实基础：模板两个示范 mod 自造的事件键（[TemplateMod.EVENT_DAY_TICK] /
 * [TemplateCapabilityDeclarations.PULSE_EVENT]）在**生产形状**（已知事件集已灌入）下注册即被拒
 * —— 症状是 onEnable 抛 IAE、mod 装载失败围栏逐包回滚禁用。台架缺省不灌事件集
 * （`RecordingEvents` 空集不校验），所以其余模板用例照常全绿：这两个示范键只能活在台架，
 * KDoc 已在示范点与常量处逐处警告。
 */
class TemplateEventGateTest {

    /** eraTW 语料形状的最小已知事件集（真会话里来自链接期目录，键按大写归一）。 */
    private val productionShape = setOf(
        "EVENTFIRST", "EVENTTRAIN", "EVENTLOAD", "EVENTCOM", "EVENTEND",
    )

    @Test
    fun capabilityDemoKeyIsRejectedUnderProductionShapedEventTable() {
        val world = TestWorld.of(TemplateCapabilityDeclarations)
        val probe = world.newContext(TemplateCapabilityDeclarations.PACKAGE_ID)
        // 同一 TestWorld 的多个 context 共享一张事件表：经旁路 context 灌入生产形状再验证注册。
        probe.eventsRecorder.adoptKnownEvents(productionShape)
        val error = assertFailsWith<IllegalArgumentException> {
            probe.events.register(
                TemplateCapabilityDeclarations.PULSE_EVENT,
                EventBucket.NORMAL,
            ) { EventResult.CONTINUE }
        }
        assertTrue(
            error.message!!.contains(TemplateCapabilityDeclarations.PULSE_EVENT),
            "拒绝消息应点名被拒的键（生产口径附最接近候选，对拍由 mod-testing 的 RecordingFixturesTest 钉）",
        )
    }

    @Test
    fun templateModOnEnableFailsUnderProductionShapedEventTable() {
        val world = TestWorld.of(TemplateDeclarations, TemplateCapabilityDeclarations)
        // 生产顺序：宿主 install 先灌事件名集合、再逐 mod 跑 onLoad/onEnable（ModRuntimeHost.install）。
        world.newContext(TemplateCapabilityDeclarations.PACKAGE_ID)
            .eventsRecorder.adoptKnownEvents(productionShape)
        // runMod = onLoad + onEnable 的台架原样（宿主 install 的同形路径）；示范事件注册应是
        // onEnable 里第一个被闸拒掉的步骤（jobs.bind 在它之前且槽已声明）。
        val error = assertFailsWith<IllegalArgumentException> {
            runMod(TemplateMod(), world, TemplateDeclarations)
        }
        assertTrue(
            error.message!!.contains(TemplateMod.EVENT_DAY_TICK),
            "onEnable 的示范事件注册应是被拒的那一步（消息点名 ${TemplateMod.EVENT_DAY_TICK}）",
        )
    }
}
