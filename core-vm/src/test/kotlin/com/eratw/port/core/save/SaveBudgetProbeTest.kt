package com.eratw.port.core.save

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 空间预算门的「未知不是不足」判据（bug① SAVEGLOBAL 真机开局即失败，0.2.48 现场）。
 *
 * ## 为什么要有这一组
 *
 * `SaveTransaction.execute` 第 1 步是空间预算门，闸的读数来自
 * [SaveTransactionIo.availableBytes]。生产实现原本用 `java.nio.file.Files.getFileStore`，
 * 而 Android 的 libcore 把它整段换成 `throw SecurityException`
 * （AOSP platform/libcore `ojluni/src/main/java/sun/nio/fs/UnixFileSystemProvider.java:373-389`），
 * android.jar 里签名照旧在、桌面 JVM 更能正常返回 —— 于是这条异常从计量源一路穿透
 * `execute`，被 SAVEGLOBAL 的收敛壳折成 `FileSystemOps.MES_SAVE_GLOBAL` 那一句，
 * 玩家开局即止。
 *
 * ## 钉哪三条（断言基准 = SaveTransaction.kt 的空间预算门、SaveTransactionIo.kt 末段、
 * [SaveDiagnostics] 的环形缓冲）
 *
 * 1. 计量源抛异常（真机形态）：事务照常提交，且 [SaveDiagnostics] 留痕 —— 不是静默放行；
 * 2. 计量源给未知哨兵 [SaveTransactionIo.AVAILABLE_UNKNOWN]：同样放行 + 留痕；
 * 3. 计量源确知不足：照旧 `Rejected` 且一个字节都不写 —— 证明闸没被上面两条架空。
 *
 * 「core-vm 主源集不许出现 getFileStore」的源码级机械闸不在这里：按 2026-11-19 的分工，
 * 它归 savedata lane 的卷空间源码哨兵独占（覆盖 core-vm + platform、抹注释与字符串），
 * 那道闸公开分仓后随壳层真源一起迁到壳层仓（eratw-pocket 的 com.eratw.port.sentinel），
 * 本 lane 不重复造第二套判据。
 */
class SaveBudgetProbeTest {

    private val fixture = SaveTransactionFixture

    @BeforeTest
    fun clearBefore() = SaveDiagnostics.clear()

    @AfterTest
    fun clearAfter() = SaveDiagnostics.clear()

    @Test
    fun `空间探测抛异常时事务照常提交并留痕`() {
        val io = fixture.seededIo()
        // 真机形态：Android 的 getFileStore 无条件抛 SecurityException。
        io.availableFailure = SecurityException("getFileStore")
        val tx = SaveTransaction(io, fixture.JOURNAL_PATH)
        val result = tx.execute(fixture.request())
        assertEquals(SaveTransaction.ExecuteResult.Committed, result)
        assertEquals(fixture.SAV_NEW.asList(), io.disk[fixture.SAV_TARGET]!!.asList())
        assertEquals(fixture.RT2_NEW.asList(), io.disk[fixture.RT2_TARGET]!!.asList())
        assertTrue(SaveTransaction.EV_BUDGET_OK in tx.trace, "预算门必须放行：${tx.trace}")
        val diag = SaveDiagnostics.snapshot()
        assertTrue(
            diag.any { "空间探测抛" in it && "SecurityException" in it },
            "空间探测异常必须留痕（否则真机只剩一句无归因的存档文案）：$diag",
        )
    }

    @Test
    fun `空间探测未知哨兵时事务照常提交并留痕`() {
        val io = fixture.seededIo(freeBytes = SaveTransactionIo.AVAILABLE_UNKNOWN)
        val tx = SaveTransaction(io, fixture.JOURNAL_PATH)
        assertEquals(SaveTransaction.ExecuteResult.Committed, tx.execute(fixture.request()))
        assertEquals(fixture.SAV_NEW.asList(), io.disk[fixture.SAV_TARGET]!!.asList())
        val diag = SaveDiagnostics.snapshot()
        assertTrue(diag.any { "按未知放行" in it }, "未知读数必须留痕：$diag")
    }

    @Test
    fun `空间确知不足时仍在任何写入之前拒绝`() {
        val io = fixture.seededIo(freeBytes = 8L)
        val tx = SaveTransaction(io, fixture.JOURNAL_PATH)
        val result = assertIs<SaveTransaction.ExecuteResult.Rejected>(tx.execute(fixture.request()))
        assertTrue("空间预算不足" in result.reason, result.reason)
        assertNull(io.disk[fixture.SAV_PART], "拒绝必须发生在写第一份 .part 之前")
        assertTrue(SaveTransaction.EV_BUDGET_OK !in tx.trace, "拒绝时不该记放行事件：${tx.trace}")
        // 判定门只探一次读数，拒绝发生在任何 I/O 之前：零写、零读、零换名。
        assertEquals(listOf("AVAILABLE"), io.ops)
    }
}
