package com.eratw.port.vmdbg

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eratw.port.core.sql.SqlDriver
import com.eratw.port.sqlite.AndroidXSqliteDriver
import java.nio.file.Path

/**
 * vmdbg 的桌面 SQLite 宿主（CONTRACT-SQLITE-MIGRATION W2）。
 *
 * 已用真实 SQLite 取代自研 SQL 解析器与手解 B-tree reader：桌面调试必须与真机跑**同一个**
 * SQL 引擎口径，否则 `sweep`/`call` 在桌面绿、真机红（或反之）都没有判据价值。因此这里返回的
 * 是 [:sqlite-adapter] 的 [AndroidXSqliteDriver]，底层驱动用 `sqlite-bundled-jvm`
 * （2.5.2 / SQLite 3.46.0），与 Android 侧 `sqlite-bundled` 同版本、同 native 实现。
 *
 * ## 两个根的分工
 *
 * - [seedRoot]：只读种子根（通常 = `--game` 指向的游戏根）。库文件不存在时由适配器从这里
 *   首次拷种，之后桌面调试与真机一样从稳定路径打开。
 * - [writeRoot]：可写沙箱根（vmdbg 的 work 目录）。所有落盘写入只发生在这里，游戏语料一个
 *   字节都不动 —— 与 [SandboxedSaveFileIo] 同一条「语料只读」铁律。
 *
 * 路径解析、出根拒绝、`Data Source=` 原串归一、`:memory:` 直通等硬语义全部由适配器
 * （`sqlite-adapter` 的路径布局层）单点实现，本工厂**不重复判断**：这里只负责把 JVM native
 * 驱动和两个根装进同一个 [SqlDriver]，避免桌面与 Android 各自长出一套路径策略。
 *
 * ## 边界
 *
 * - 本工厂只产驱动；把它注入 `GameSession` 属于 W4 的接线（Assembler），本文件不装配会话。
 * - 连接/语句的线程口径沿 [SqlDriver] 契约：只在创建它的 VM 线程使用。
 * - 驱动构造失败、库文件不可写等异常不在这里吞成静默内存库：`SqlDriver?` 为 null 时调用方
 *   必须让 `SQL_*` 抛 CodeEE；伪造 `:memory:` 兜底会把真实持久化缺陷掩盖成「跑完了」。
 */
object DesktopSqliteHost {

    /**
     * 建一个桌面 [SqlDriver]。
     *
     * 例：`DesktopSqliteHost.create(gameRoot, workDir.resolve("sqlite"))`。
     * 每次调用新建一个驱动实例（BundledSQLiteDriver 无全局状态，便于 sweep 逐进程隔离）。
     *
     * @throws IllegalArgumentException 适配器在打开阶段拒绝出根路径时（见 `sqlite-adapter`）；
     *   本工厂不预先校验路径，避免与适配器口径分叉。
     */
    fun create(seedRoot: Path, writeRoot: Path): SqlDriver =
        AndroidXSqliteDriver(BundledSQLiteDriver(), seedRoot, writeRoot)
}
