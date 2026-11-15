package com.eratw.port.erbcc.compile

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * 函数级编译缓存的**环境指纹**：把 R4-B §3.2 的八项输入压成一个 SHA-256 digest。
 *
 * 八项分两处进入 [digest]：本实例持有的六项是整次 compile 共享的全局环境
 * （lexerConfig、consts、dim 布局、compileConfig、符号表、编译器源码指纹），
 * 调用方在每次 compileOne 追加两项 per-function 输入（函数体原文、非名字 FuncSig）。
 * 缺一即产错字节：环境变了而键不变，命中会重放出旧环境下的指令流。
 *
 * 纯函数：本类只持不可变字段，无静态可变全局（AGENTS 铁律 4）。序列化一律确定：
 * Map 按 key 排序、集合按自然序、字节用长度前缀串接，杜绝 HashMap 迭代序让同一环境
 * 两次算出不同 digest 的空转形态。
 */
class CompileEnvFingerprint(
    private val lexerConfig: String,
    private val consts: String,
    private val dimLayout: String,
    private val compileConfig: String,
    private val symbolTables: String,
    private val compilerDigest: String,
) {

    /**
     * 六项全局环境在单次 compile 内恒定，构造时先压成定长 32 字节前缀。
     * 否则每个函数都要重新 UTF-8 编码并哈希一次庞大的符号表串（canonicalSet(known) 等），
     * 全量语料下 digest 会退化为纯 CPU 空转（R11-R4B2 性能修正）。环境任一项变化都会让
     * 前缀变化：八项输入仍全覆盖、Map 按 key / 集合按自然序仍确定、纯函数语义不变。
     */
    private val envPrefix: ByteArray = run {
        val md = MessageDigest.getInstance("SHA-256")
        feed(md, lexerConfig)
        feed(md, consts)
        feed(md, dimLayout)
        feed(md, compileConfig)
        feed(md, symbolTables)
        feed(md, compilerDigest)
        md.digest()
    }

    /**
     * 六项全局环境（定长前缀）+ 两项 per-function 输入一起压成 digest。
     * [functionBodyBytes] 是函数体语句原文（不含函数头行，头行含名字会让不同函数永不命中）；
     * [funcSigKey] 是 FuncSig 的非名字字段（arity / returnsStr / returnsFloat / paramRefs）。
     */
    fun digest(functionBodyBytes: ByteArray, funcSigKey: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(envPrefix)
        feed(md, functionBodyBytes)
        feed(md, funcSigKey.toByteArray(Charsets.UTF_8))
        return hex(md.digest())
    }

    companion object {
        /** Map 按 key 排序（不许迭代序进指纹）。 */
        fun canonicalMap(m: Map<String, *>): String =
            m.entries.sortedBy { it.key }.joinToString("\u0000") { "${it.key}=${it.value}" }

        /** 集合按自然序。 */
        fun canonicalSet(s: Collection<String>): String = s.sorted().joinToString("\u0000")

        private fun feed(md: MessageDigest, part: String) = feed(md, part.toByteArray(Charsets.UTF_8))

        private fun feed(md: MessageDigest, part: ByteArray) {
            md.update(ByteBuffer.allocate(4).putInt(part.size).array())
            md.update(part)
        }

        private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            for (b in bytes) {
                val v = b.toInt() and 0xff
                append("0123456789abcdef"[v ushr 4])
                append("0123456789abcdef"[v and 0x0f])
            }
        }
    }
}
