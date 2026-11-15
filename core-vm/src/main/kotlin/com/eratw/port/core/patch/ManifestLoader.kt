package com.eratw.port.core.patch

/**
 * 一份待装载的 Manifest 来源。
 *
 * [name] 会原样作为 [DeclarationSite.source] 进入冲突报告（文件名 / 容器标识），
 * [bytes] 是 [ManifestCodec] 产出的载体字节流。
 */
class ManifestSource(val name: String, val bytes: ByteArray)

/**
 * 声明事务的装载器 —— `DeclarationRegistry(` 在 main 内的**首个生产构造点**
 * （era-arch-v61u.md §12.1「声明事务无载体」缺口，根因层 = 装载/编译相位）。
 *
 * 接线形态：磁盘 Manifest 字节流 → [ManifestCodec.decode] → [DeclarationRegistry]
 * → [DeclarationRegistry.addPackage] → [DeclarationRegistry.freeze] → 冻结快照
 * [DeclarationSnapshot]。本装载器不重造任何一域的声明语义，只把描述符从载体喂进
 * 既有事务；快照的不可变性、双边冲突、CSV 关键字两阶段分配全部沿用核心纪律。
 *
 * fail-closed（整包拒绝，不返回半成品）：
 * - 先把**全部** Manifest 解码完毕，任一坏档（[ManifestFormatException]）在构造
 *   registry 之前就整体抛出，绝不返回半装的状态；
 * - 全部解码成功后才构造唯一一个 registry，按入参顺序（= 链接序）addPackage；
 * - [DeclarationRegistry.freeze] 期的任一冲突（[DeclarationConflictException]）
 *   同样整体抛出，不返回快照。
 *
 * 纯 JVM、无状态：本对象是单例但不持有任何可变全局（禁静态可变全局），
 * 每次 [load] 都新建独立的 registry 与快照，两次装载互不共享可变状态。
 */
object ManifestLoader {

    /**
     * 把若干 Manifest 装成一张冻结的声明快照。
     *
     * @param manifests 按链接序排列的来源；重复 packageId 或声明冲突由核心整包拒绝。
     * @param builtinVariableNames 引擎内置变量名（撞名拒绝），透传给核心。
     * @param csvTableCapacities 可打补丁表的容量上界（未列出的表拒绝补丁），透传给核心。
     * @param baseCsvKeywords 引擎已装载的关键字表（base 命名位），透传给核心。
     * @param onCommit 提交后统一清刷入口，只在成功提交后触发一次，透传给核心。
     */
    fun load(
        manifests: List<ManifestSource>,
        builtinVariableNames: Set<String> = emptySet(),
        csvTableCapacities: Map<String, Int> = emptyMap(),
        baseCsvKeywords: Map<String, Map<String, Int>> = emptyMap(),
        onCommit: (DeclarationSnapshot) -> Unit = {},
    ): DeclarationSnapshot {
        // 阶段一：全部解码。任一坏档在此整体抛出，registry 尚未构造（不半装）。
        val decoded = ArrayList<Pair<String, ModuleDescriptor>>(manifests.size)
        for (src in manifests) {
            decoded += src.name to ManifestCodec.decode(src.bytes)
        }
        // 阶段二：唯一的生产构造点。
        val registry = DeclarationRegistry(
            builtinVariableNames = builtinVariableNames,
            csvTableCapacities = csvTableCapacities,
            baseCsvKeywords = baseCsvKeywords,
            onCommit = onCommit,
        )
        for ((name, descriptor) in decoded) {
            registry.addPackage(descriptor, name)
        }
        // 阶段三：原子提交并冻结；冲突在此整包拒绝。
        return registry.freeze()
    }
}
