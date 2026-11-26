package com.eratw.port.core.mod

import com.eratw.port.modapi.ModServiceRegistry

/**
 * 会话级服务注册表实现（RT2 P1 工程契约的引擎侧半边）。
 *
 * 一个会话一份，由 [ModRuntimeHost.services] 持有；每个 [ModContextImpl] 装载时经
 * [ModContextImpl.adoptServices] 换成「本包身份 + 这一份表」的薄适配器（作者面 [ModServiceRegistry]
 * 的 `publish(owner)` 由适配器校验必须等于本包 packageId）。查找方与发布方必须落在同一份表上
 * 才可能跨 mod 命中。
 *
 * 线程口径同 [EventDispatchRegistry]：注册 / 查找 / 撤销都在 VM 线程同步调用，不加锁。
 *
 * 撤销面（[revokePackage]）只对宿主开放、不进作者面：单个作者无从「撤销别人发布的同类型服务」，
 * 卸载与安装失败回滚由宿主统一调。
 */
class ModServiceRegistryImpl {

    /** 单条发布记录：哪个包发的、发了什么对象。 */
    internal class Entry(val owner: String, val service: Any)

    /** 接口类型 → 该类型的发布记录（同包同类型已就地替换，故每类型 owner 唯一）。 */
    private val byType = LinkedHashMap<Class<*>, MutableList<Entry>>()

    /** 发布 [owner] 包的服务；同包同类型重复发布 = 替换旧绑定（幂等重注册）。 */
    fun <T : Any> publish(type: Class<T>, service: T, owner: String) {
        require(owner.isNotBlank()) { "服务发布的 owner（packageId）不得为空" }
        val entries = byType.getOrPut(type) { ArrayList(1) }
        val existing = entries.indexOfFirst { it.owner == owner }
        if (existing >= 0) {
            entries[existing] = Entry(owner, service)
        } else {
            entries += Entry(owner, service)
        }
    }

    /**
     * 按接口类型查找。
     *
     * 无人发布 ⇒ null（契约边界 2：不等待、不抛错）；多包发布 ⇒ [IllegalStateException] 点名双方
     * packageId（契约边界 3：不静默取先注册的）。
     */
    fun <T : Any> find(type: Class<T>): T? {
        val entries = byType[type]
        return when {
            entries == null -> null
            entries.size > 1 -> throw IllegalStateException(conflictMessage(type, entries))
            else -> type.cast(entries.single().service)
        }
    }

    /** 该类型的发布方 packageId（按发布序）；无人发布返回空表。 */
    fun <T : Any> owners(type: Class<T>): List<String> = byType[type].orEmpty().map { it.owner }

    /** 已发布类型的全限定名集合（按首次发布序）。 */
    fun publishedTypes(): Set<String> = byType.keys.mapTo(LinkedHashSet()) { it.name }

    /** 诊断只读：本包已发布的服务条数（同类型替换不增条；未知包 0）。 */
    internal fun publishedCountOf(packageId: String): Int {
        var count = 0
        for (entries in byType.values) {
            for (entry in entries) {
                if (entry.owner == packageId) count++
            }
        }
        return count
    }

    /**
     * 撤销 [packageId] 发布的全部服务（卸载 / 安装失败回滚 / 读档拒载）。
     *
     * 幂等：重复调用或该包没发过服务都是 no-op；类型下最后一条被摘掉时连类型键一起清掉
     * （[publishedTypes] 不留空壳）。
     */
    fun revokePackage(packageId: String) {
        val types = byType.values.iterator()
        while (types.hasNext()) {
            val entries = types.next()
            entries.removeAll { it.owner == packageId }
            if (entries.isEmpty()) types.remove()
        }
    }

    /**
     * 「共享表 + 本包身份」的作者面适配器（生产 [ModContextImpl.adoptServices] 用的同一构造）。
     *
     * 公开它只是给出宿主与台架都需要的那一个装配入口：`publish` 的 owner 门禁、同包同类型
     * 替换、多包冲突点名、撤销全在 [ModServiceRegistryImpl] 与适配器里，不存在第二套语义。
     * 撤销面仍只对宿主开放（[revokePackage]），作者面拿不到。
     */
    fun forPackage(packageId: String): ModServiceRegistry = PackageServiceRegistry(this, packageId)

    /** 多包冲突的错误文案：点名双方，便于作者一眼看出该改哪个 Manifest 的 loadAfter。 */
    private fun conflictMessage(type: Class<*>, entries: List<Entry>): String {
        val owners = entries.joinToString("、") { it.owner }
        return "服务类型 ${type.name} 被多个 mod 发布（$owners）：同类型只许一个发布方，" +
            "请改掉其中一个的发布或经 Manifest loadAfter 消解装载顺序"
    }
}

/**
 * 作者面 [ModServiceRegistry] → 会话级 [ModServiceRegistryImpl] 的薄适配器：`publish` 校验 [owner]
 * 必须是本包 packageId（作者只能替自己发布，冒名会污染 owners 观测面、还能让他包卸载时误摘别人的条目），
 * 查找/观测面直通共享表。
 *
 * 撤销不走作者面：卸载 / 安装失败回滚由宿主调 [ModServiceRegistryImpl.revokePackage]，
 * 作者没有「撤销单个服务」的入口（半撤销会让消费方拿着悬空引用，比整包撤销更难排查）。
 */
internal class PackageServiceRegistry(
    private val registry: ModServiceRegistryImpl,
    private val packageId: String,
) : ModServiceRegistry {
    override fun <T : Any> publish(type: Class<T>, service: T, owner: String) {
        require(owner == packageId) {
            "服务发布 owner 必须是本包 packageId（本包 $packageId，实际传入 $owner）：不允许冒名发布"
        }
        registry.publish(type, service, packageId)
    }

    override fun <T : Any> find(type: Class<T>): T? = registry.find(type)

    override fun <T : Any> owners(type: Class<T>): List<String> = registry.owners(type)

    override fun publishedTypes(): Set<String> = registry.publishedTypes()
}

/**
 * 装配跨 mod 服务注册表（RT2 P1 工程契约；[ModRuntimeHost.install] 在 `onLoad` 之前调用）。
 *
 * 为什么必须共享一份：查找方与发布方落在同一份表上才可能跨 mod 命中；每个 context 各持一份时
 * 服务只在本包可见（等于没接线）。这里只把「共享表 + 本包身份」组成作者面适配器，撤销面
 * （[ModServiceRegistryImpl.revokePackage]）留给宿主。
 *
 * 为什么是文件级 extension：宿主类 [ModContextImpl] 的成员函数数已抵 detekt `TooManyFunctions`
 * 阈值（再加成员函数就判红），装配入口放外面才不撑爆；它只写 context 的 internal 装配字段，
 * 不引入第二份状态。
 */
internal fun ModContextImpl.adoptServices(registry: ModServiceRegistryImpl) {
    servicesAdapter = PackageServiceRegistry(registry, packageId)
}

/**
 * 未装配服务注册面的显式 fail-closed 桩：[ModContextImpl] 在宿主 adopt 之前用它顶位。
 *
 * 四个入口一律抛 [UnsupportedOperationException] 并点名 packageId，不返回静默空注册表 ——
 * 空表会让作者以为「服务没发出去是别的原因」，也掩盖了装配缺失。属性本身可读（与 [ModContextImpl.rng]
 * / [ModContextImpl.settings] 的 fail-closed 桩同形状）。
 */
internal class FailClosedServices(private val packageId: String) : ModServiceRegistry {

    private fun fail(): Nothing = throw UnsupportedOperationException(
        "服务注册面未装配（$packageId）：宿主应在 onLoad 之前注入会话级 ModServiceRegistry",
    )

    override fun <T : Any> publish(type: Class<T>, service: T, owner: String): Unit = fail()

    override fun <T : Any> find(type: Class<T>): T? = fail()

    override fun <T : Any> owners(type: Class<T>): List<String> = fail()

    override fun publishedTypes(): Set<String> = fail()
}
