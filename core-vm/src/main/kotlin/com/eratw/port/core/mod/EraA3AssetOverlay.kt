package com.eratw.port.core.mod

import java.io.File

/**
 * 覆盖优先级的**语义冻结 + 聚合读面**（ERA-A3 方案 B / 二期 A+B）。
 *
 * ## 选位理由
 *
 * 与 [ModDx3AssetMap] 同包（`:core-vm` 的 `core.mod`）：三处消费面（CSV 资源表 / BGM 路径 /
 * mod 资源存储）都在 core-vm，聚合结果必须是 core 类型，app 侧只负责把链接序作为入参喂进来，
 * 不在这里写任何装配逻辑（GameHost / ModRuntimeAssembly 是禁触面）。
 *
 * ## 一、逐包侧车（不是全局单表）
 *
 * 声明落在**每包自己的** `mods/rt2/<packageId>.assetmap`（[ModDx3AssetMap]），不建一张跨包全局表：
 * - 作者声明局部化，导入期可按包独立校验，坏一包不影响其它包（fail-closed 的粒度）；
 * - 卸载/替换按包原子生效（全局表要在每次装卸时全量重写，且与「按包落盘」的既有合同冲突）；
 * - 跨包冲突不在写入期裁决，留到读取期按链接序定胜者（见二），避免两张表互写。
 *
 * ## 二、LinkOrder 定胜者：后者胜（last-wins）
 *
 * 聚合按**链接序**遍历，同官方名后到的包覆盖先到的包。与既有语义逐字同源：
 * `ModuleLinkOrder.kt:38-39` 冻结「链接序在 last-wins 语义下后者胜：列表靠前 = 先链接 =
 * 覆盖优先级更低」，本聚合不得另立一套「配置靠前优先」。链接序本身由 `ModuleLinkOrder.plan`
 * 按依赖约束 / 显式排序配置 / packageId 规范序产出（`ModuleLinkOrder.kt:92-98`），
 * **文件扫描序与目录枚举序不参与**（`:70-72`）。
 *
 * ## 三、与官方素材包的优先级：mod 覆盖 > 官方素材包 > 内置
 *
 * 组长建议裁决（待正式裁定，先按此冻结语义实现）：
 * - 消费面查到映射 ⇒ 取 mod 资源路径（`mods/rt2/<pkg>.assets/<modName>`）；
 * - 查不到映射 ⇒ 走**原样**既有路径，官方素材包重导覆盖同名文件的既有行为逐字不变
 *   （`ResourcePackStore.install` 报告 `overwrittenFiles`，见 RECON §1.1）；内置是最底层兜底。
 * 即映射只做「前置一层覆盖查询」，绝不改动官方/内置两条既有消费链。
 *
 * ## 四、fail-closed
 *
 * 坏侧车（[ModDx3AssetMap.Sidecar.ok] = false）整包**跳过、不采信**，该包声明的覆盖全部不生效，
 * 消费面回落既有官方行为；不静默吞错（读侧车时已由 [ModDx3AssetMap.read] 经 logSink 留 WARN），
 * 也不崩。坏侧车不会被部分采信成「只覆盖一半」。
 */
object EraA3AssetOverlay {

    /** 一条覆盖解析结果：胜出包 + 该包资源名；[resourcePath] 是相对 gameRoot 的盘上路径。 */
    class Target(val packageId: String, val modName: String) {
        /** 消费面据此读字节：与 [ModResourceStore.ASSET_DIR_SUFFIX] 同源拼接。 */
        val resourcePath: String
            get() = RT2_PREFIX + packageId + ModResourceStore.ASSET_DIR_SUFFIX + "/" + modName
    }

    /**
     * 聚合后的只读查表。[EMPTY] = 无覆盖（映射关掉的反向证明锚：传它就该拿到旧资源名）。
     *
     * 类名取 AssetMap（不叫 Map）：与 kotlin.collections.Map 同名会让本文件内的
     * `Map<String, Target>` 参数类型被解析成这个嵌套类、触发「No type arguments expected」。
     */
    class AssetMap internal constructor(
        private val sprites: Map<String, Target>,
        private val bgmExact: Map<String, Target>,
        private val bgmFolded: Map<String, Target>,
    ) {
        /**
         * CSV 资源名 → 覆盖目标；名字先按 `ResourceContents.parseRow` 的口径大写再查
         * （`tokens[0].trim().uppercase()`，见 `ResourceContents.kt:215`）。
         */
        fun sprite(officialName: String): Target? = sprites[officialName.uppercase()]

        /**
         * BGM 相对名 → 覆盖目标。先精确命中，再按大小写不敏感回落 —— 与
         * `SoundResourcePaths.resolve` 的两级查找（`:47-48`）同形，避免侧车写了 `x.mp3`、
         * 语料调用 `X.MP3` 时漏覆盖。
         */
        fun sound(name: String): Target? = bgmExact[name] ?: bgmFolded[name.lowercase()]

        companion object {
            /** 空表：映射关掉；传它时三消费面行为逐字等同改造前。 */
            val EMPTY: AssetMap = AssetMap(emptyMap(), emptyMap(), emptyMap())
        }
    }

    /**
     * 按链接序聚合逐包侧车；[read] 注入读侧车纯函数（生产 = [ModDx3AssetMap.read] 绑定 rt2Dir，
     * 单测 = 内存表），便于 core 侧不碰 IO 也能钉死优先级语义。
     */
    fun collect(
        packageIdsInLinkOrder: List<String>,
        read: (String) -> ModDx3AssetMap.Sidecar,
    ): AssetMap {
        val sprites = HashMap<String, Target>()
        val bgmExact = HashMap<String, Target>()
        val bgmFolded = HashMap<String, Target>()
        for (packageId in packageIdsInLinkOrder) {
            val sidecar = read(packageId)
            // fail-closed：坏档整包跳过（read 已留 WARN），不部分采信。
            if (!sidecar.ok) continue
            for (entry in sidecar.entries) {
                val target = Target(packageId, entry.modName)
                when (entry.kind) {
                    ModDx3AssetMap.Kind.SPRITE -> sprites[entry.officialName.uppercase()] = target
                    ModDx3AssetMap.Kind.BGM -> {
                        bgmExact[entry.officialName] = target
                        bgmFolded[entry.officialName.lowercase()] = target
                    }
                }
            }
        }
        return AssetMap(sprites, bgmExact, bgmFolded)
    }

    /** 生产便捷入口：绑定 `mods/rt2/` 目录读全部包侧车。 */
    fun collect(
        rt2Dir: File,
        packageIdsInLinkOrder: List<String>,
        logSink: (String) -> Unit = {},
    ): AssetMap = collect(packageIdsInLinkOrder) { packageId ->
        ModDx3AssetMap.read(rt2Dir, packageId, logSink)
    }

    private const val RT2_PREFIX = "mods/rt2/"
}
