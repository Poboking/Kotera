package com.eratw.port.erbcc.compile

/**
 * 转发壳：实现已搬入 core-vm 的 `com.eratw.port.core.frontend.SignatureExport`
 * （R7-① override 融合方案：core-vm main 不得依赖 erbcc main，故签名渲染器下沉
 * core/frontend 作单一实现，erbcc 侧只留本别名壳，不许留第二份实现）。本仓内全部
 * 引用（CorpusCompile / ErbcArtifacts / BootMetaWriter / ErbModuleCompiler / 本包测试）
 * 经本别名转发到单一实现，`program.meta` 尾段 signatures 与 `.erbm` 容器签名段的字节
 * 形态零变化（规范串逐字同源，erbcc 全量产物 sha 不变）。
 */
typealias SignatureExport = com.eratw.port.core.frontend.SignatureExport
