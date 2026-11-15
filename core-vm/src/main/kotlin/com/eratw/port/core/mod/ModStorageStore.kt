package com.eratw.port.core.mod

import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.StorageApi

/**
 * 会话级 mod 存储容器：把 [ModContextImpl.storage] 的键值落到 [ModStorageSectionCodec] 的分段格式，
 * 并让「禁用 → 重新启用」在语义上闭合（P1-4 ①，era-arch-v61u.md §18.6 / §8）。
 *
 * ## 为什么不是「一个 mod 一张 map」
 *
 * 旧形态是每个 context 一份私有 LinkedHashMap：数据与 mod 实例同生共死，宿主拿不到整体，
 * 也没有「包被禁用但它的数据必须留下」的位置。本容器把所有权收到会话一处 —— mod 只见自己那一段
 * （[apiFor] 返回的绑定视图），宿主持有全表，可 [encode] 也可经 [Companion.load] 恢复。
 *
 * ## 禁用不等于删除（本容器唯一的语义主张）
 *
 * [deactivate] 只把包移出激活集，**一个条目都不动**；重新 [activate] 后原值原样回来。读档时不在
 * [Companion.load] 已知包集合里的段整段保留为非激活段，且再编码逐字节不变（codec 的孤儿口径）
 * ⇒ 「装/卸别的 mod 也不会弄丢被禁 mod 的数据」这句承诺从此有实现根据，而不只是 KDoc。
 *
 * ## 未接线边界（诚实登记，不是完成态）
 *
 * [encode] 的字节今天**没有生产调用点**：把字节写进 `.rt2` 属 SaveService 生产构造点
 * （车次表 C 车），把段交还给 mod 侧属 D 车余量（era-arch §23.4）。本容器只保证**语义层可往返**，
 * 不代表**盘上已往返** —— 这两者的差别正是 §12.3 记的那类失效模式。
 *
 * ## 为什么 remove 不写 ABSENT 墓碑
 *
 * 格式侧有 tag 0（[ModValue] 缺位）表达「显式不存在」，但 [StorageApi] 契约里没有对应操作：
 * 凭空造墓碑会让 [StorageApi.keys] 与 [StorageApi.get] 的口径都需要额外解释（删过的键算不算在
 * 键集合里？），而 mod 侧看不出任何差别。**不发明契约没有的语义。** 解码方向仍然无损保留墓碑
 * （见 ModStorageStoreTest 的最后一条），所以真有需要时不必改格式。
 */
class ModStorageStore {

    private val sections = LinkedHashMap<String, MutableMap<String, ModValue?>>()
    private val activePackages = HashSet<String>()

    /** 声明「本会话这个包是启用的」；幂等，且不清空既有数据（重启用与首装走同一入口）。 */
    fun activate(packageId: String) {
        require(packageId.isNotBlank()) { "packageId 不能为空" }
        sections.getOrPut(packageId) { LinkedHashMap() }
        activePackages += packageId
    }

    /** 声明「本会话这个包不启用」：**只移出激活集，数据整段留下**，后续读写一律 fail-closed。 */
    fun deactivate(packageId: String) {
        activePackages -= packageId
    }

    fun isActive(packageId: String): Boolean = packageId in activePackages

    /** 容器持有其数据的全部包（含被禁用的）。顺序 = 首次登记序，仅用于诊断，编码时会重排。 */
    fun packages(): Set<String> = LinkedHashSet(sections.keys)

    /** 有数据但未激活的包（禁用中，或读档时该 mod 没装）。 */
    fun inactivePackages(): Set<String> =
        sections.keys.filterTo(LinkedHashSet()) { it !in activePackages }

    /**
     * 取某包的 mod 侧视图。首次索取时顺带登记该包（等价于旧「每 context 一份内存」的缺省形态），
     * 但**已存在却未激活**的包不会被这里悄悄启用 —— 那正是禁用要拦住的东西。
     */
    fun apiFor(packageId: String): StorageApi {
        if (packageId !in sections) activate(packageId)
        return BoundStorage(packageId)
    }

    /** 全表不可变快照；`orphan` = 未激活（[ModStorageSectionCodec] 的口径：派生元数据，不落格式）。 */
    fun sectionsSnapshot(): List<ModStorageSection> = sections.map { (packageId, values) ->
        ModStorageSection(packageId, LinkedHashMap(values), orphan = packageId !in activePackages)
    }

    /** 编码整段存储（激活与非激活段一起写 —— 非激活段必须随档走）。 */
    fun encode(): ByteArray = ModStorageSectionCodec.encode(sectionsSnapshot())

    /** 某包当前条目数（**含** ABSENT 墓碑位，因为墓碑占位且要随档走）；诊断与「禁用后数据仍在」的断言用。 */
    fun sizeOf(packageId: String): Int = sections[packageId]?.size ?: -1

    private fun mutableValuesOf(packageId: String): MutableMap<String, ModValue?> =
        requireNotNull(sections[packageId]) { "容器不含包 $packageId 的段" }

    /** 绑定到单个包的 StorageApi 视图：越不出自己的段，未激活即拒。 */
    private inner class BoundStorage(private val packageId: String) : StorageApi {

        override fun put(key: String, value: String) {
            requireActive("put($key)")
            mutableValuesOf(packageId)[key] = ModValue.StrValue(value)
        }

        override fun get(key: String): String? {
            requireActive("get($key)")
            return (mutableValuesOf(packageId)[key] as? ModValue.StrValue)?.value
        }

        /**
         * 键存在且**不是 ABSENT 墓碑**才算删除成功。墓碑在本视图里当作不存在
         * （与 [get] / [keys] 同口径），因此这里返回 false 且**不动它** —— 它在格式上仍占一位，
         * 再编码逐字节不变。用 `remove` 的返回值判存在会把墓碑误判成「存在」，故先显式取值。
         */
        override fun remove(key: String): Boolean {
            requireActive("remove($key)")
            val values = mutableValuesOf(packageId)
            if (values[key] == null) return false
            values.remove(key)
            return true
        }

        /** 快照，且**滤掉 ABSENT 墓碑**：`keys()` 报的键必须和 `get()` 读得到的一一对应。 */
        override fun keys(): Set<String> {
            requireActive("keys()")
            return LinkedHashSet(mutableValuesOf(packageId).filterValues { it != null }.keys)
        }

        private fun requireActive(operation: String) {
            check(packageId in activePackages) {
                "包 $packageId 未激活，storage.$operation 被拒" +
                    "（fail-closed：禁用的 mod 不得读写自己的段，也不得被静默启用）"
            }
        }
    }

    companion object {

        /**
         * 从 [encode] 的字节重建容器 —— 读档侧唯一的恢复入口。
         *
         * [knownPackages] 里有段的包被启用；**没有的包整段保留为非激活段**（原值、原字节语义不变），
         * 等该 mod 重新出现时由 [activate] 接回。空字节数组 = 全新会话（合法，空容器）。
         */
        fun load(bytes: ByteArray, knownPackages: Set<String>): ModStorageStore {
            val store = ModStorageStore()
            for (section in ModStorageSectionCodec.decode(bytes, knownPackages)) {
                store.sections[section.packageId] = LinkedHashMap(section.entries)
                if (section.packageId in knownPackages) store.activePackages += section.packageId
            }
            return store
        }
    }
}
