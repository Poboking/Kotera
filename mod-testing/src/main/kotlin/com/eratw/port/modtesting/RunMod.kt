package com.eratw.port.modtesting

import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModCarrierDeclaration

/** 一个已装进台架的 mod 会话：`onLoad` + `onEnable` 已跑完，可以开始断言业务行为。 */
class TestModSession(val mod: EraMod, val context: TestContext) {

    /**
     * 走 `onDisable` 生命周期（作者应断言禁用后数据已写回 `context.storage`）。
     *
     * **先代撤后回调**（A3 代撤对齐，2026-11-26；与生产同序）：本包经七面（events / hooks /
     * phases / jobs / interactions / actions / ui）返回的 [Registration] 在 `onDisable` **之前**
     * 全部代撤 —— 消 MOD-017/018 两世界差异（旧形态只裸调 onDisable，作者不自持返回值时
     * 台架禁用后处理器仍命中、装进生产却是先撤销后回调）。作者自持的返回值再 unregister 是
     * 幂等 no-op，不冲突；`onDisable` 里新注册的项**不**在本次代撤范围（与生产同口径）。
     */
    fun disable() {
        context.revokeTrackedRegistrations()
        mod.onDisable()
    }

    /** 走 `onUnload` 生命周期（作者应断言实例级可变集合已清）。 */
    fun unload() {
        mod.onUnload()
    }

    /** 走 `onNewGame` 通知。 */
    fun newGame() {
        mod.onNewGame()
    }

    /** 走 `onSaveLoaded` 通知。 */
    fun saveLoaded() {
        mod.onSaveLoaded()
    }

    /**
     * 走 `onPackageUpgrade` 通知（ModDX2-A：本包版本升级迁移通知的台架面）。
     *
     * 生产缝的记账与对账在宿主（恢复存档后比较 per-package 保留键与当前 manifest 版本，不等才
     * 派发并回写；回调序严格在 `saveLoaded` 之后），台架没有宿主：作者要测迁移逻辑，就把
     * 「档内记账的旧版本」当 `fromVersion` 直调进来——`fromVersion`/`toVersion` 原样转发，
     * 回调里 `context.storage` 已可用，搬数据断言与生产一致。
     */
    fun packageUpgrade(fromVersion: String, toVersion: String) {
        mod.onPackageUpgrade(fromVersion, toVersion)
    }

    /**
     * 走 `onSettingsChanged` 通知（键序 = 传入序，照生产原样转发）。
     *
     * 生产缝的触发顺序是「玩家写入成功、快照已更新之后才回调」：作者先用
     * `context.settingsRecorder` 的写入面（[RecordingSettings.change]）落新值，再把键列传进来 ——
     * 回调里 `context.settings` 读到的就是新值，与真引擎一致。
     */
    fun settingsChanged(changedKeys: List<String>) {
        mod.onSettingsChanged(changedKeys)
    }
}

/**
 * 装配一个 mod：`onLoad(context)` → `onEnable()`，返回 [TestModSession]。
 *
 * 这是作者单测的入口：不启引擎。除资源面外只要 `:mod-testing` + `:mod-api`；资源面直接复用
 * `:core-vm` 的 `ModResourceStore` 真实现（唯一依赖例外，见 `mod-testing/build.gradle.kts`）。
 *
 * [faces] 是三个 fail-closed 桩面（roster / actions / functions）的可选注入口（ModDX4-B6，
 * 见 [BenchFaces]）：缺省不注 = 桩面行为与注入口引入前逐位一致。要复用 `:core-vm` 真实现
 * （如 `DecisionPipeline.actionApi` 的包作用域 facade）作注入面，作者的测试工程需自行带上
 * `:core-vm` 依赖（mod-testing 对它走 `implementation`，不透传）。
 */
fun runMod(
    mod: EraMod,
    world: TestWorld,
    declaration: ModCarrierDeclaration,
    faces: BenchFaces = BenchFaces(),
): TestModSession = runMod(mod, world, declaration.packageId, faces)

/** [runMod] 的包身份重载（没有载体对象、只想手工指定 packageId 时用）。 */
fun runMod(
    mod: EraMod,
    world: TestWorld,
    packageId: String = TestWorld.DEFAULT_PACKAGE_ID,
    faces: BenchFaces = BenchFaces(),
): TestModSession {
    val context = world.newContext(packageId, faces)
    mod.onLoad(context)
    mod.onEnable()
    return TestModSession(mod, context)
}
