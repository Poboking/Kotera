package com.eratw.port.core.entity

import com.eratw.port.modapi.EntityId
import java.nio.charset.StandardCharsets

/**
 * 按实体身份派生确定性整数（M4-5，era-arch-v61u.md §7 / §16；K5「子嗣 / ENTITY_RAND 遗传派生」的引擎侧地基）。
 *
 * ## 它解决的具体缺陷
 *
 * 生成式属性（遗传、外貌种子、初始天赋点数）今天只有两条路，两条都错：
 * ① 取真随机 ⇒ 同一实体每次读档拿到不同的值，玩家 SL 一次就重掷一次命运，回归测试也无法比；
 * ② 自己拿 `handleSlot` 当键 ⇒ **槽位复用即串号**（`EntityKernel` 在 destroy 时把 generation +1、
 *   槽位回收再用，slot 相同而身份已换人，见 §7 的 EntityId 位域）。
 * 本函数把「身份」而不是「位置」当键：`raw` 的位域里本来就含 pkgShortId + generation + handleSlot，
 * 所以槽位复用一定改值，而同一实体跨存读、跨进程、跨设备**逐位复现**。
 *
 * ## 为什么是一个纯函数而不是一个随机流
 *
 * `Sfmt19937` 是有状态的序列：第 n 次取值依赖前 n-1 次。派生特征必须与**调用顺序无关**
 * （读档顺序、mod 装载顺序、诊断打印一次都会挪动游标），所以这里给的是纯函数：
 * 任意 (实体, 用途, 序号, 盐) → Long，可乱序取、可只取第 7 个、可重复取，结果恒定。
 * **不引入第二套 RNG、不碰 `Sfmt19937` 的状态、不用 `kotlin.random`（铁律 4）。**
 *
 * ## 边界（诚实登记）
 *
 * 只交 64 位混合整数这一个原语。**不提供** `nextInt(bound)` 这类有界形态：
 * 无界到有限的映射必然带取模偏置或用拒绝采样，那属于「派生出来的数值怎么用」的业务决定
 * （家族要的 0..99 天赋点和遗传学要的有符号差值不是一回事），由调用方按自己的口径映射并自负其责。
 * 同理**不提供**浮点：定点/浮点的舍入差异会破坏跨平台逐位复现（§22.3 同源纪律）。
 * ERB 侧 `ENTITY_RAND(entityId, salt)` 内建函数已落（M4-5，EntityMethods.kt 固定
 * purpose = "erb"、ordinal = 0 的两参面）；Kotlin mod 要用途分域/一串取值仍走本对象全参面。
 *
 * 算法是 SplitMix64 的 finalizer（Steele/Lea/Flores 版本）加 FNV-1a 64 的字符串吸收，
 * 全程 64 位整数环绕运算，无浮点、无默认字符集、无平台相关行为。
 */
object DeterministicDeriver {

    /** 黄金比例常数 0x9E3779B97F4A7C15（SplitMix64 原语用）。 */
    private const val GOLDEN: Long = -7046029254386353131L

    private const val FNV_OFFSET: Long = -3750763034362895579L
    private const val FNV_PRIME: Long = 1099511628211L

    /**
     * 派生一个 64 位整数。
     *
     * @param entity 实体身份；**取全位域**，generation 参与混合是「槽复用不串号」的根据。
     *   [EntityId.NULL] 不被特殊豁免 —— 若调用方拿 NULL 来派生，它会稳定地得到某个值，
     *   这是调用 bug，因此这里显式拒（fail-closed，见 [require]）。
     * @param purpose 用途标签（如 `family.hair`）。不同用途必须互不相关；同一用途同一实体恒定。
     *   按 **UTF-8 显式编码**参与混合：不用 [String.hashCode]，因为它只有 32 位且平台间碰撞更多。
     * @param ordinal 同一 (实体, 用途) 内的第几个取值（0 起）⇒ 一次派生一串彼此无关的值，
     *   且**取第 3 个不依赖有没有人取过第 1、2 个**。
     * @param salt 会话/存档级盐，用于「同一实体在两个存档里应不同」这类口径；默认 0 = 不加盐。
     */
    fun derive(entity: EntityId, purpose: String, ordinal: Long = 0L, salt: Long = 0L): Long {
        require(!entity.isNull) { "不得用 EntityId.NULL 派生特征值（调用侧的实体身份没拿到，fail-closed）" }
        require(purpose.isNotEmpty()) { "purpose 不能为空串：派生键缺一段，不同实体会互相撞值" }
        require(ordinal >= 0L) { "ordinal 不能为负：$ordinal" }

        var state = entity.raw + GOLDEN
        state = avalanche(state xor fnv1a64(purpose))
        state = avalanche(state + ordinal * GOLDEN)
        state = avalanche(state + salt * GOLDEN)
        return state
    }

    /** SplitMix64 finalizer：两轮乘 + 移位异或，把局部位差扩散到全字（avalanche）。 */
    private fun avalanche(input: Long): Long {
        var z = input
        z = (z xor (z ushr 30)) * -4658895280548449495L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }

    /**
     * FNV-1a 64 over UTF-8 bytes。空串返回 offset basis（但 [derive] 已在入口拒空串）。
     * internal 只为让单拿**公布测试向量**对拍它（独立于本实现的判据），不是给业务用的口子。
     */
    internal fun fnv1a64(text: String): Long {
        var hash = FNV_OFFSET
        for (byte in text.toByteArray(StandardCharsets.UTF_8)) {
            hash = hash xor (byte.toInt() and 0xFF).toLong()
            hash *= FNV_PRIME
        }
        return hash
    }
}
