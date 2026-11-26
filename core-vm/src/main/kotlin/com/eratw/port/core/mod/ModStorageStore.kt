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
 * ## 接线状态（分片已在生产存档路径上）
 *
 * 整表 [encode] 的直接调用点今天只在测试/台架；但**分片编码已在生产存档路径上**：存档抓取
 * （ModSidecarStoragePort.captureChunks）对 [sectionsSnapshot] 逐段走 [ModStorageSectionCodec.encode]，
 * 字节随 `.rt2` 容器落盘（「把段交还给 mod 侧」的余量按 era-arch §23.4 另计）。因此「容器里每段
 * **按构造可编码**」是生产不变式（M2-BUG-04）：写入口（[BoundStorage.put] / [putReservedKey]）在
 * 落写前过 [ModStorageSectionCodec.requireEncodableText] 入档闸，解码方向天然满足（u16 前缀定界 +
 * 严格 UTF-8 解码）⇒ 存档抓取与足迹诊断的编码不可能因作者数据抛 IAE。
 *
 * ## 为什么 remove 不写 ABSENT 墓碑
 *
 * 格式侧有 tag 0（[ModValue] 缺位）表达「显式不存在」，但 [StorageApi] 契约里没有对应操作：
 * 凭空造墓碑会让 [StorageApi.keys] 与 [StorageApi.get] 的口径都需要额外解释（删过的键算不算在
 * 键集合里？），而 mod 侧看不出任何差别。**不发明契约没有的语义。** 解码方向仍然无损保留墓碑
 * （见 ModStorageStoreTest 的最后一条），所以真有需要时不必改格式。
 *
 * ## 数据版本（每包 dataVersion）
 *
 * 容器同时记录每包的数据结构版本（[dataVersionsByPackage] / [setDataVersion]），随 [encode] 落进段尾、
 * 由 [load] 恢复。它只影响读档迁移判断（`EraMod.onMigrate`），**不进** mod 侧 [StorageApi] 视图：
 * 作者读写数据的口径一字不变，版本是宿主与侧车之间的元数据。
 */
class ModStorageStore {

    /**
     * internal 而非 private：同文件的 `ModStorageStore.release` 扩展要整段摘掉某包（含版本与激活标记），
     * 而类内函数数已抵 detekt `TooManyFunctions` 阈值（见 [dataVersionsByPackage] 的同款理由），
     * 不能再长一个成员函数。core-vm 之外看不到这两个字段。
     */
    internal val sections = LinkedHashMap<String, MutableMap<String, ModValue?>>()
    internal val activePackages = HashSet<String>()

    /**
     * 每包的数据结构版本（`EraMod.dataVersion` 的落点，随 `.rt2` 侧车存盘）。
     *
     * 语义边界：**null = 容器里没有该包的版本记录**（新局 / 该包在档里没有分段 ⇒ 直接按 declared 写，
     * 不迁移）；有记录时哪怕值为 0 也是「旧档明确记录到 0」的口径（MST1 v1 段解码补 0）。
     * 两者不能混：把旧档 0 当成「无记录」会让作者从 0 起递增的迁移永远不触发。
     *
     * internal 可变视图只给宿主装配登记用，写侧唯一入口是同文件的 `ModStorageStore.setDataVersion`
     * 扩展（校验 version 与段存在性）；mod 侧 [StorageApi] 看不到它。用属性而非成员函数暴露，避免
     * 占用类内 detekt `TooManyFunctions` 名额。
     */
    internal val dataVersionsByPackage = LinkedHashMap<String, Int>()

    /**
     * 阈值 WARN 的去重门（每包每会话一次）：键 = packageId，见 [noteStorageFootprint]。
     *
     * 特意**不**放 `store.aux`：会话收口清点闸（ModSessionTeardownInventoryTest）逐槽登记 aux，多一个
     * 未登记的槽就红；而本集合随容器实例走、会话级装配本来就是「一容器一会话」，语义与 aux 槽等价。
     * 用属性而非成员函数暴露/清点，避免占用类内 detekt `TooManyFunctions` 名额。
     */
    internal val warnedFootprintPackages = HashSet<String>()

    /**
     * 阈值 WARN 的出口（生产 = [ModRuntimeHost] 的 `logSink`，经 [bindStorageFootprintWarningSink] 绑定）。
     *
     * null = 台架/未接线形态：写入照常、不告警也不标记（[noteStorageFootprint] 明确不抛）。
     */
    internal var footprintWarningSink: ((String) -> Unit)? = null

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
        ModStorageSection(
            packageId,
            LinkedHashMap(values),
            orphan = packageId !in activePackages,
            dataVersion = dataVersionsByPackage[packageId] ?: 0,
        )
    }

    /** 编码整段存储（激活与非激活段一起写 —— 非激活段必须随档走）。 */
    fun encode(): ByteArray = ModStorageSectionCodec.encode(sectionsSnapshot())

    /** 某包当前条目数（**含** ABSENT 墓碑位，因为墓碑占位且要随档走）；诊断与「禁用后数据仍在」的断言用。 */
    fun sizeOf(packageId: String): Int = sections[packageId]?.size ?: -1

    private fun mutableValuesOf(packageId: String): MutableMap<String, ModValue?> =
        requireNotNull(sections[packageId]) { "容器不含包 $packageId 的段" }

    /**
     * 绑定到单个包的 StorageApi 视图：越不出自己的段，未激活即拒。
     *
     * ## 宿主保留前缀（ModDX-4 BS，裁决 = 方案 A「宿主专用门」）
     *
     * 宿主保留前缀 [MOD_STORAGE_RESERVED_KEY_PREFIX]（ModDX2-A，常量单源居 ModRuntimeHost.kt:20，
     * 此处只读复用）下的键与普通键共存于同一份段，两侧口径：
     *
 * - 读侧（[get] / [keys]）**不隐藏**保留键 —— 「keys 报的键与 get 读得到的一一对应」口径不变，
 *   保留键同样守这条口径（读无破坏力；隐藏保留键会造出「读得到但 keys 不报」的第二套键集合
 *   口径，与单口径原则冲突）。
 * - 写侧（[put] / [remove]）对保留前缀键 fail-closed 拒（判据 1）：保留命名空间归宿主记账
 *   专用，作者写保留键按**非法入参**当场暴露（require / IllegalArgumentException，与
 *   [ModStorageStore.activate] 的空白 packageId 同形态；未激活才是状态违例走 check /
 *   IllegalStateException），不许静默覆盖宿主记账。唯一宿主写入口 = [putReservedKey]
 *   （双向门：那里对非保留前缀键同样拒 —— 命名空间只有一扇门）。
 * - 写侧还有**入档闸**（[put] / [putReservedKey] 共用，M2-BUG-04）：键/值必须能被 MST1 段格式
 *   编码（合法 UTF-16 且 UTF-8 ≤ 65535 字节），判据单源 = [ModStorageSectionCodec] 的编码器，
 *   拒在落写之前 —— 容器里每段「按构造可编码」，存档抓取与足迹诊断的编码不可能因段内容抛。
     */
    private inner class BoundStorage(private val packageId: String) : StorageApi {

        override fun put(key: String, value: String) {
            requireActive("put($key)")
            requireNotReserved("put($key)", key)
            // 入档闸在落写之前（M2-BUG-04）：键/值必须能被 MST1 段格式编码。修复前判据只在编码期
            // 兑现 —— 超限/非法文本先落写、再经足迹诊断旁路抛 IAE（写已落地却报错 + 毒条目令本包
            // 后续每次 put 连锁抛 + 存档抓取整链失败）。判据单源 = codec 的同一编码器。
            ModStorageSectionCodec.requireEncodableText("storage.put 的键", key)
            ModStorageSectionCodec.requireEncodableText("storage.put 的值", value)
            mutableValuesOf(packageId)[key] = ModValue.StrValue(value)
            // 写入完成之后的只读旁路：越限上屏一次 WARN，不参与写入语义（见 noteStorageFootprint）。
            noteStorageFootprint(packageId)
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
            requireNotReserved("remove($key)", key)
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

        /** 作者面禁入保留命名空间：与 [requireActive] 相反方向的门（保留键只从 [putReservedKey] 进）。 */
        private fun requireNotReserved(operation: String, key: String) {
            require(!key.startsWith(MOD_STORAGE_RESERVED_KEY_PREFIX)) {
                "storage.$operation 拒写保留前缀键 $key" +
                    "（命名空间 $MOD_STORAGE_RESERVED_KEY_PREFIX 归宿主记账专用，作者面 fail-closed）"
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
                store.dataVersionsByPackage[section.packageId] = section.dataVersion
                if (section.packageId in knownPackages) store.activePackages += section.packageId
            }
            return store
        }
    }
}

/**
 * 宿主专用保留键写入口（ModDX-4 BS 组长裁决方案 A）：保留命名空间 [MOD_STORAGE_RESERVED_KEY_PREFIX]
 * 的**唯一写门**，宿主装配侧（app 侧新局记账 / 读档对账回写 pkgVersion）经本面写入；作者视图
 * [ModStorageStore.apiFor] 的写侧对保留前缀 fail-closed 拒，两半合起来 = 双向 fail-closed、
 * 保留命名空间只有这一扇门。
 *
 * ## 双向门（键形状）
 *
 * 键**必须**带保留前缀，非保留前缀键同样拒（require / IllegalArgumentException）：保留门不收
 * 普通件，防止任何持有 store 引用的代码借本面绕开作者视图的普通写口径（比如把宿主记账键当
 * 普通数据写、或对未声明语义的键建立第二写径）。普通键写走 [ModStorageStore.apiFor]。
 *
 * ## 状态与异常形态
 *
 * - 包未激活：`check` / IllegalStateException，与视图侧 requireActive 同口径 —— 宿主记账与对账
 *   流程本就只对 isActive 的包写（app 侧守卫在前），违反即编程错误；
 * - 容器不含该包的段：requireNotNull / IllegalArgumentException，与类内 `mutableValuesOf` 同形
 *   —— 记账必须落在真实段上，**不凭空建段**（建段是 [ModStorageStore.activate] 的显式语义）；
 * - 键形状违规：require / IllegalArgumentException（非法入参，非状态违例）。
 *
 * 放成文件级扩展而非成员：类内函数数已抵 detekt `TooManyFunctions` 阈值上沿（与 [setDataVersion]
 * 同一取舍）；public 是 app 装配侧跨模块调用的必需（internal 跨模块不可见），可见面仍收在
 * 「持有 store 引用 + 保留前缀键」这一格 —— 作者拿不到 store 引用，store 只归宿主持有。
 */
fun ModStorageStore.putReservedKey(packageId: String, key: String, value: String) {
    require(key.startsWith(MOD_STORAGE_RESERVED_KEY_PREFIX)) {
        "putReservedKey 只收保留前缀（$MOD_STORAGE_RESERVED_KEY_PREFIX）键，普通键走 apiFor 作者面：$key"
    }
    check(packageId in activePackages) {
        "包 $packageId 未激活，putReservedKey($key) 被拒（fail-closed：宿主记账只落激活段）"
    }
    // 入档闸在落写之前（M2-BUG-04）：与作者面 put 同闸同判据 —— 保留键与普通键同落一份 MST1 段。
    ModStorageSectionCodec.requireEncodableText("putReservedKey 的键", key)
    ModStorageSectionCodec.requireEncodableText("putReservedKey 的值", value)
    requireNotNull(sections[packageId]) { "容器不含包 $packageId 的段" }[key] = ModValue.StrValue(value)
    // 宿主保留键也计入同一份段的足迹：与作者面 put 同一条只读旁路（保留键同样占盘上字节）。
    noteStorageFootprint(packageId)
}

/**
 * 记录某包的数据版本（首装落 declared、迁移成功后落新版本）。
 *
 * 放成文件级扩展而非成员：类内函数数已抵 detekt `TooManyFunctions` 阈值，写侧登记不占名额。
 * fail-closed：容器里没有该包的段就拒不登记 —— 版本必须绑着真实数据段走，否则会编出
 * 「有版本没数据」的幽灵记录。负版本是格式侧就禁止的，这里同样拒。
 */
internal fun ModStorageStore.setDataVersion(packageId: String, version: Int) {
    require(version >= 0) { "dataVersion 不能为负：$version" }
    require(packageId in packages()) { "容器不含包 $packageId 的段，不能单独登记数据版本" }
    dataVersionsByPackage[packageId] = version
}

/**
 * 释放某包的整段存档数据 —— 卸载 / 换版本后的回收入口（地基，不含持久化清单）。
 *
 * ## 与既有语义的边界
 *
 * - [ModStorageStore.deactivate] 是「本会话不启用，数据整段留下」；本函数是「把这段数据从会话容器里
 *   整段删掉」，**激活段与孤儿段一视同仁**。两者不互相替代：停用 / 坏包保留的孤儿段默认仍保留，
 *   只有显式调用本函数才回收；
 * - 只动**内存容器**：不读写任何文件、不碰 `.rt2` 侧车，也不裁决盘上包该不该留。盘上回收由调用方
 *   负责（显式卸载清单 + captureChunks 跳过），本笔只提供「容器里删得掉」这一块地基；
 * - [ModStorageStore.sectionsSnapshot] 仍是纯读；[BoundStorage.remove] 仍是删单键，原样保留。
 *
 * ## 幂等与返回值
 *
 * 容器里没有该包时返回 `false`、**不抛**（卸载链可能重入 / 对账后重复调用）；返回值只表示本次是否
 * 真删掉了一段，不表示调用成败。`packageId` 为空串属非法入参（与 [ModStorageStore.activate] 同口径）。
 *
 * ## 连版本记录与激活标记一起丢
 *
 * 释放 = 整包遗忘：段、[ModStorageStore.dataVersionsByPackage] 里的版本记录、激活标记三样一起摘。
 * 版本必须绑真实段走（见 [setDataVersion]），否则重装后 [ModStorageStore.sectionsSnapshot] 会把旧版本号
 * 当成「档里已有记录」，跳过 `EraMod.onMigrate` 的首次迁移门；留下激活标记则会出现「isActive 真、
 * packages 无此包」的自相矛盾态。之后再 [ModStorageStore.activate] / [ModStorageStore.apiFor] 即从零开始。
 *
 * 例：`store.release("mod.old")` 后 `store.encode()` 不再含该包段，解码段数与字节数同步变少。
 */
fun ModStorageStore.release(packageId: String): Boolean {
    require(packageId.isNotBlank()) { "packageId 不能为空" }
    if (sections.remove(packageId) == null) return false
    activePackages -= packageId
    dataVersionsByPackage.remove(packageId)
    return true
}

