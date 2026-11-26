package com.eratw.port.modtesting

import com.eratw.port.modapi.ModResourceApi

/**
 * 资源面**录制装饰器**（测试替身）：只观测、不改写。
 *
 * 底层委托生产同一实现（[TestWorld] 里是 `:core-vm` 的 `ModResourceStore`，根 =
 * `mods/rt2/<packageId>.assets/`），本类**逐调用透传**：`keys` / `exists` / `size` / `open` 的
 * 返回值原样返回，非法名的 [IllegalArgumentException] 原样上抛（不 catch 改文案、不吞异常、
 * 不改语义）；唯一副作用是把每次调用记进 [calls]。
 *
 * 因此「缺文件 = `exists` false、`size` / `open` null，不抛」等契约（`mod-api` 的
 * `ModResourceApi.kt:29`、`ModResourceStore.kt:103-115`）与真引擎逐字同源，台架不另写一套；
 * 本类只回答作者关心的「我读了哪些名字、读了几个、哪些入口被调用过」。
 *
 * 纯观测由 `RecordingResourcesTest` 的「套壳 vs 不套壳」用例锁住：同一批名字经装饰器与
 * 直接调用生产视图，返回值与 IAE 文案逐字相等。
 *
 * @param delegate 被装饰的资源视图（生产 `ModResourceApi`）。
 */
class RecordingResources(private val delegate: ModResourceApi) : ModResourceApi {

    /** 资源面的四个读入口。 */
    enum class Access {
        /** [keys]：列本包资源名快照。 */
        KEYS,

        /** [exists]：名字是否存在且可用。 */
        EXISTS,

        /** [size]：名字对应字节数。 */
        SIZE,

        /** [open]：按名读完整字节。 */
        OPEN,
    }

    /**
     * 一次资源读调用（**台架观察面**，不是作者 API）。
     *
     * @property access 被调用的入口（[Access]）。
     * @property name 传入的名字；[Access.KEYS] 恒 `null`。
     * @property present 该入口的「可用性」读数：`exists` 的布尔结果；`size` / `open` 是否非
     *   `null`；[Access.KEYS] 恒 `null`。抛 IAE 的调用也记 `null`（看 [failure]）。
     * @property byteCount [Access.OPEN] 读到的字节数（缺文件 / 失败 / 其它入口为 `null`）。
     * @property sizeBytes [Access.SIZE] 读到的字节数（缺文件 / 失败 / 其它入口为 `null`）。
     * @property failure 该调用抛出的名字校验异常（原样上抛了；台架仅留档）。
     */
    data class ResourceCall(
        val access: Access,
        val name: String? = null,
        val present: Boolean? = null,
        val byteCount: Int? = null,
        val sizeBytes: Long? = null,
        val failure: IllegalArgumentException? = null,
    )

    private val callsLog = mutableListOf<ResourceCall>()

    override val packageId: String get() = delegate.packageId

    /** 全部调用（发生序）。 */
    val calls: List<ResourceCall> get() = callsLog.toList()

    /** 调用总次数。 */
    val callCount: Int get() = callsLog.size

    /** 某入口被调用次数。 */
    fun count(access: Access): Int = callsLog.count { call -> call.access == access }

    /** 某入口针对某个名字被调用次数（`KEYS` 无名字，恒 0）。 */
    fun count(access: Access, name: String): Int =
        callsLog.count { call -> call.access == access && call.name == name }

    /** 各入口调用次数（键序 = [Access] 声明序；只含被调用过的入口）。 */
    fun counts(): Map<Access, Int> = Access.entries.mapNotNull { access ->
        val times = count(access)
        if (times == 0) null else access to times
    }.toMap()

    override fun keys(): List<String> {
        val names = delegate.keys()
        callsLog += ResourceCall(Access.KEYS)
        return names
    }

    override fun exists(name: String): Boolean {
        val present = observe(Access.EXISTS, name) { delegate.exists(name) }
        callsLog += ResourceCall(Access.EXISTS, name, present = present)
        return present
    }

    override fun size(name: String): Long? {
        val sizeBytes = observe(Access.SIZE, name) { delegate.size(name) }
        callsLog += ResourceCall(Access.SIZE, name, present = sizeBytes != null, sizeBytes = sizeBytes)
        return sizeBytes
    }

    override fun open(name: String): ByteArray? {
        val bytes = observe(Access.OPEN, name) { delegate.open(name) }
        callsLog += ResourceCall(Access.OPEN, name, present = bytes != null, byteCount = bytes?.size)
        return bytes
    }

    /** 清空录制账本（只清台架观察面，不碰底层存储）。 */
    fun reset() {
        callsLog.clear()
    }

    /** 先记下将要失败的调用，再**原样**上抛：异常对象与文案都不改写（纯观测）。 */
    private inline fun <T> observe(access: Access, name: String, block: () -> T): T =
        try {
            block()
        } catch (failure: IllegalArgumentException) {
            callsLog += ResourceCall(access, name, failure = failure)
            throw failure
        }
}
