package com.eratw.port.core.mod

import com.eratw.port.core.rand.Sfmt19937
import com.eratw.port.modapi.RandomApi
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * [RandomApi] 的生产实现：**独立派生流**（不消费引擎那一条 SFMT19937）。
 *
 * 为什么另起一条：引擎的随机流（`RAND` / `RANDOMIZE` / `DUMPRAND` / `RANDDATA`、STRDATA 与
 * PRINTDATA 的组选）每一次消费都被黄金基准逐位校准（`GoldenPipeline` 的 DATA 组选种子是校准值）。
 * mod 抽数若共用同一条流，抽一次就让后续 `RAND` 序号与组选结果漂移，既破坏复刻对拍，也让
 * 「同存档 + 同操作 = 同结果」失效。故此处按「决策种子 + packageId」派生出**每个 mod 自己的**
 * SFMT19937 实例（判例同 `DecisionPipeline.Sha256DecisionRandom`）。
 *
 * 派生口径（v1，改动即破坏可复现性，勿轻改）：
 * 材料 = `"eratw.mod.rng" ␟ <决策种子十进制> ␟ <packageId>`（U+001F 分隔、UTF-8）；
 * SHA-256 取前 8 字节按大端拼 [Long]，作为 SFMT19937 种子（引擎侧仍按规范截 uint32）。
 *
 * 读档语义：宿主采纳档内决策种子后换种子**从头重放**（不续接流位置）。同存档 + 同操作序列
 * ⇒ 同结果；需要跨档累计的随机结果请落 [com.eratw.port.modapi.StorageApi] 或实体字段。
 */
class ModRandomApi(
    decisionSeed: Long,
    packageId: String,
) : RandomApi {

    private val provider = Sfmt19937(deriveSeed(decisionSeed, packageId))

    override fun nextInt(bound: Int): Int {
        require(bound > 0) { "nextInt(bound) 需要 bound > 0，实参=$bound（fail-closed，不返回 0 兜底）" }
        return provider.next(bound.toLong()).toInt()
    }

    override fun nextInt(minInclusive: Int, maxExclusive: Int): Int {
        require(maxExclusive > minInclusive) {
            "nextInt(min, max) 需要 max > min，实参=[$minInclusive, $maxExclusive)（fail-closed）"
        }
        return minInclusive + nextInt(maxExclusive - minInclusive)
    }

    override fun chance(percent: Int): Boolean {
        require(percent in PERCENT_RANGE) { "chance(percent) 需要 0..100，实参=$percent（fail-closed）" }
        // 端点不消耗随机流（抽数次数本身也是可观测行为，别让 chance(0) 悄悄推流）。
        return when {
            percent <= 0 -> false
            percent >= PERCENT_SCALE -> true
            else -> nextInt(PERCENT_SCALE) < percent
        }
    }

    override fun nextDouble(): Double = provider.nextDouble()

    override fun permutationIndices(size: Int): IntArray {
        require(size >= 0) { "permutationIndices(size) 需要 size >= 0，实参=$size（fail-closed）" }
        val result = IntArray(size) { it }
        for (i in size - 1 downTo 1) {
            // 与 Fisher–Yates 的经典写法一致：j ∈ [0, i]，抽 i 次即可保证均匀。
            val j = nextInt(i + 1)
            val tmp = result[i]
            result[i] = result[j]
            result[j] = tmp
        }
        return result
    }

    companion object {
        private const val DERIVATION_DOMAIN = "eratw.mod.rng"
        private const val DERIVATION_ALGORITHM = "SHA-256"
        private const val SEPARATOR = '\u001F'

        /** `chance` 的百分比刻度：`nextInt(PERCENT_SCALE) < percent`。 */
        private const val PERCENT_SCALE = 100

        /** `chance` 的合法百分比区间。 */
        private val PERCENT_RANGE = 0..PERCENT_SCALE

        /** 派生种子取摘要的前 8 字节（大端拼 [Long]）。 */
        private const val SEED_BYTES = 8

        /** 拼 Long 时每字节占 8 位。 */
        private const val BYTE_BITS = 8

        /** 无符号字节掩码（Kotlin 的 [Byte] 有符号，必须按位与成 0..255）。 */
        private const val BYTE_MASK = 0xFFL

        /** 派生材料 → SFMT19937 种子（可见以便测试钉住口径）。 */
        internal fun deriveSeed(decisionSeed: Long, packageId: String): Long {
            val material =
                "$DERIVATION_DOMAIN$SEPARATOR$decisionSeed$SEPARATOR$packageId"
                    .toByteArray(StandardCharsets.UTF_8)
            val digest = MessageDigest.getInstance(DERIVATION_ALGORITHM).digest(material)
            var seed = 0L
            for (index in 0 until SEED_BYTES) {
                seed = (seed shl BYTE_BITS) or (digest[index].toLong() and BYTE_MASK)
            }
            return seed
        }
    }
}
