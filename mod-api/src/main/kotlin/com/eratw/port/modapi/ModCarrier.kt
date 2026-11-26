package com.eratw.port.modapi

/**
 * 标记一个**内置载体**的声明 object：`@ModCarrier` 打在 object 上，该 object 实现
 * [ModCarrierDeclaration]，构建期扫描 `mod-*` 模块的 main 源集，把所有打了这个注解的
 * object 收进唯一一份 `ModRegistry`。
 *
 * 引入动机（2026-11-16）：以前新增一个 Kotlin 载体要在**两个**地方各抄一行 ——
 * 构建期资产生产者（`tools/modgen/.../BuiltinManifestBuild.carriers`）与设备侧装配
 * （`app/.../ModCarrierRegistry.providers()`）。漏一处不报错、不失败，只是那个 mod
 * **静默不生效**，是最难查的一类坑。有了注解，登记面只剩「声明 object 自己」一处。
 *
 * 保留策略是 [AnnotationRetention.SOURCE]：注册表在**构建期**生成，运行期不需要反射，
 * 也就不需要在 dex 里留注解 —— 设备端零反射是有意为之（见架构文档 §18.1）。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class ModCarrier

/**
 * 内置载体的**声明对象**契约：一份声明（[manifest]）+ 一个能造出 [EraMod] 实例的工厂
 * （[newMod]）。
 *
 * 为什么把 `newMod()` 放在声明对象上，而不是让构建期去扫「哪个 class 实现了 EraMod」：
 * 扫类名得靠字符串拼 FQN，改名后就静默失效；而 `override fun newMod(): EraMod = XMod()`
 * 是普通的 Kotlin 代码，**改名/改签名编译不过**，错不了。
 *
 * 声明对象通常长这样：
 * ```
 * @ModCarrier
 * object FamilyDeclarations : ModCarrierDeclaration {
 *     override val packageId: String = "eratw.family"
 *     override val manifest: ModManifest = modDeclarations(packageId) { ... }
 *     override fun newMod(): EraMod = FamilyMod()
 * }
 * ```
 */
interface ModCarrierDeclaration {
    /** 包标识，同时是资产文件名主干与 `ModContext.packageId`。 */
    val packageId: String

    /** 本载体的全部声明（组件/原型/关系/任务/hook…）。 */
    val manifest: ModManifest

    /** 造一个新的 [EraMod] 实例。每次装配都要新实例（`onLoad` 会往里塞状态）。 */
    fun newMod(): EraMod

    /**
     * 本载体声明自己需要的作者面契约版本，缺省 = 编译这套 SDK 时的 [ModApi.VERSION]。
     *
     * 声明对象与 [newMod] 造的实例必须给同一个值（构建期构建器与设备侧装配读的都是声明对象，
     * 装载期跑的是 [EraMod.apiVersion]）；本属性主要是给构建期/诊断面一个不造实例就能读的入口。
     * 语义与装载期规则见 [ModApi] 与 [EraMod.apiVersion]：`> ModApi.VERSION` 时只拒该 mod。
     */
    val apiVersion: Int get() = ModApi.VERSION
}
