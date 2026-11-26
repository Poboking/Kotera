package com.eratw.port.modapi

/**
 * mod 侧随机数面（§21.6，**P1 工程契约，非 ABI**）。
 *
 * ## 与 ERB 的 `RAND` 是两条独立流
 * `context.rng` **不消费**原版随机流：ERB 的 `RAND` / `RANDOMIZE` / `DUMPRAND` / `RANDDATA` 与
 * STRDATA/PRINTDATA 的组选共用引擎那一条 SFMT19937，其消费次数被黄金基准逐位校准过
 * （`GoldenPipeline.kt` 的 DATA 组选种子是校准值）。mod 若共用同一条流，每抽一次就会让后续
 * `RAND` 序号与组选结果漂移 —— 既破坏复刻对拍，也让「同一存档 + 同一操作 = 同一结果」失效。
 * 因此本面走**派生流**：种子由宿主用「本次会话决策种子 + packageId」经 SHA-256 派生成独立
 * SFMT19937 实例，mod 之间、mod 与引擎之间互不干扰。
 *
 * ## 确定性口径（v1）
 * - 同一个决策种子 + 同一个 `packageId` ⇒ 同一串抽数（跨进程、跨设备一致）。
 * - 不同 `packageId` ⇒ 不同序列（不需要 mod 之间相互错开）。
 * - 读档后宿主会把派生种子换成档内种子并**从头重放**（不续接读档瞬间的流位置）：
 *   同一存档 + 同一操作序列 ⇒ 同一结果。mod 若需要「跨档累计」的随机结果，请把结果写进
 *   [StorageApi] 或实体字段，不要指望流位置本身可续。
 *
 * ## 线程模型
 * 与其它 api 一致：只在 VM 线程（`context.runtime.isOnMainThread == true` 时）调用。
 * 本面不做加锁；跨线程抽数属编程错误。
 */
interface RandomApi {
    /**
     * `[0, bound)` 的整数。
     *
     * @throws IllegalArgumentException `bound <= 0`（fail-closed，不返回 0 兜底）。
     */
    fun nextInt(bound: Int): Int

    /**
     * `[minInclusive, maxExclusive)` 的整数。
     *
     * @throws IllegalArgumentException `maxExclusive <= minInclusive`。
     */
    fun nextInt(minInclusive: Int, maxExclusive: Int): Int

    /**
     * 百分比判定：`nextInt(100) < percent`。用整数比较而非浮点，避免边界抖动。
     *
     * @param percent 取值 `[0, 100]`；0 恒 false、100 恒 true。
     * @throws IllegalArgumentException `percent` 越界（fail-closed）。
     */
    fun chance(percent: Int): Boolean

    /** `[0.0, 1.0)` 的实数（与引擎 `nextDouble` 同口径）。 */
    fun nextDouble(): Double

    /**
     * `0 until size` 的随机排列（Fisher–Yates，抽 `size - 1` 次）。
     *
     * @throws IllegalArgumentException `size < 0`。
     */
    fun permutationIndices(size: Int): IntArray
}
