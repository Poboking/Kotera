package com.eratw.port.core.mod

/**
 * 版本协商的失败类型（Lane E：API/ABI 版本 + 每 mod 数据版本）。
 *
 * 三个类型都继承 [IllegalStateException]，并且**消息本身就是玩家/作者可读的失败原因**：
 * 装配侧把它们按既有 `mod.install pkg=… active=false failure=<简单类名>: <消息>` 行打到日志面，
 * [com.eratw.port.android.ModManageDiagnostics] 只解析这条通道、不改格式。
 */
class ModApiVersionMismatchException(
    packageId: String,
    requiredApiVersion: Int,
    kernelApiVersion: Int,
) : IllegalStateException(
    "mod $packageId 需要 mod API v$requiredApiVersion，本体内核只到 v$kernelApiVersion",
)

/** 存档数据版本高于 mod 声明（用户装了旧版 mod 打开新档）：拒绝该 mod，不静默降级写坏新版数据。 */
class ModDataVersionDowngradeException(
    packageId: String,
    storedDataVersion: Int,
    declaredDataVersion: Int,
) : IllegalStateException(
    "mod $packageId 的数据版本降级：存档记录 v$storedDataVersion，mod 只声明到 v$declaredDataVersion" +
        "（拒绝装载，避免旧版 mod 写坏新版数据）",
)

/** [com.eratw.port.modapi.EraMod.onMigrate] 抛异常：per-mod 隔离后作为该 mod 的失败原因。 */
class ModDataMigrationException(
    packageId: String,
    fromDataVersion: Int,
    toDataVersion: Int,
    cause: Throwable,
) : IllegalStateException(
    "mod $packageId 数据迁移 v$fromDataVersion→v$toDataVersion 失败：" +
        "${cause::class.java.simpleName}: ${cause.message}",
    cause,
)
