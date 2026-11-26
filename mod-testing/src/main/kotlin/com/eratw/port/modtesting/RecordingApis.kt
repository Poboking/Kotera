package com.eratw.port.modtesting

import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.JobApi
import com.eratw.port.modapi.JobContext
import com.eratw.port.modapi.JobHandler
import com.eratw.port.modapi.PhaseApi
import com.eratw.port.modapi.PhaseContext
import com.eratw.port.modapi.PhaseHandler
import com.eratw.port.modapi.Registration

/**
 * 录制型任务台架：`bind` 校验「槽已声明 + 同槽不重复绑定」，[fire] 手动触发一拍。
 *
 * 作者用例不需要真等游戏日：`fireJob` 等价于「游戏日到达 + 相位匹配」派发一次。
 * 事件 / 函数钩子 / 交互钩子的录制夹具已拆到独立文件（RecordingEvents / RecordingHooks /
 * RecordingInteractions），本文件只留任务与相位两个「按槽绑定一次」的面。
 */
internal class TestJobs(
    private val packageId: String,
    private val schema: MemorySchema,
) : JobApi {

    private val handlers = LinkedHashMap<String, JobHandler>()

    override fun bind(jobId: String, handler: JobHandler): Registration {
        val key = normalize(jobId)
        require(key in schema.jobs) { "任务槽未声明: $jobId（先在 manifest 里 job(...) 声明）" }
        require(handlers.putIfAbsent(key, handler) == null) { "任务槽已绑定: $jobId" }
        return object : Registration {
            override fun unregister() {
                handlers.remove(key)
            }
        }
    }

    /** 已绑定的任务槽（供用例断言 `onEnable` 真的注册了任务处理器）。 */
    val boundJobIds: Set<String> get() = handlers.keys.toSet()

    /** 手动触发一个任务槽；未绑定返回 false。 */
    fun fire(jobId: String, time: GameTime): Boolean {
        val key = normalize(jobId)
        val handler = handlers[key] ?: return false
        handler.run(JobContext(packageId, key, time))
        return true
    }
}

/**
 * 录制型相位台架：只接受已声明相位（内置三相位恒可用），同包重复订阅 = `require` 抛。
 *
 * [fire] 返回实际派发的处理器数（没订阅返回 0，不抛）。
 */
internal class TestPhases(
    private val packageId: String,
    private val schema: MemorySchema,
) : PhaseApi {

    private data class Binding(val phase: String, val handler: PhaseHandler)

    private val bindings = mutableListOf<Binding>()

    override fun subscribe(phase: String, handler: PhaseHandler): Registration {
        val key = normalize(phase)
        require(key in schema.phases) { "相位未声明: $phase" }
        require(bindings.none { binding -> binding.phase == key }) { "同一包对相位重复订阅: $phase" }
        val binding = Binding(key, handler)
        bindings += binding
        return object : Registration {
            override fun unregister() {
                bindings.remove(binding)
            }
        }
    }

    /** 手动派发一个相位，返回本次派发的处理器数。 */
    fun fire(phase: String, time: GameTime): Int {
        val key = normalize(phase)
        var dispatched = 0
        for (binding in bindings.filter { item -> item.phase == key }) {
            binding.handler.onPhase(PhaseContext(packageId, key, time))
            dispatched += 1
        }
        return dispatched
    }
}
