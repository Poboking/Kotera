package com.eratw.port.core.mod

/**
 * `install` 的逐 mod 面注入收拢（设置快照 + RT2 资源面 + 自带三语文案面 + mod 音频面）。
 *
 * 四者都必须在 `onLoad` 之前就位：作者在初始化里就可能读设置 / 取资源 / 声明文案 / 放声音。
 * 未注入者保持各自的 fail-closed 桩（[FailClosedSettingsApi] / [FailClosedResourceApi] /
 * `FailClosedStringsApi` / [FailClosedAudioApi]），不静默兜底。
 *
 * 为什么是文件级函数而不是 [ModRuntimeHost] 成员：本刀按 detekt 类内函数上限与
 * `ModContextImpl` 的既有口径约束，不往这两个类加成员（结构性 ID 不重刷）。也单独成文件
 * 而非追加在 `ModRuntimeHost.kt` 末尾：该文件的文件级函数数同样已贴着上限（同样口径）。
 */
internal fun adoptPerModState(host: ModRuntimeHost, context: ModContextImpl, packageId: String) {
    host.settings[packageId]?.let { context.adoptSettings(it) }
    host.resources?.let { context.resourceApi = it.apiFor(packageId) }
    // 自带三语文案面：会话级表 + 语言提供者（`{ host.language }`）在 onLoad 之前绑上，
    // 作者在初始化里就能 declare / of。表本身恒存在（空表近零成本），不需要 null 判断。
    context.adoptStrings(host.strings) { host.language }
    // 音频面：宿主没装 sink（无 Android 播放器 / 测试台架）时保持 fail-closed 桩，
    // 作者调 play() 会拿到明确的 UOE 而不是静默 false。
    host.audioSink?.let { sink ->
        context.audioApi = PackageAudioApi(
            packageId = packageId,
            resources = context.resources,
            sink = sink,
            warn = { message -> context.logger.warn(message) },
        )
    }
}
