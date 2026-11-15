package com.eratw.port.erbcc.compile

import com.eratw.port.core.code.FunctionSlice

/**
 * 进程内函数级编译缓存（MODERN-R11-R4B2；R4-B §3.3-§3.5）。
 *
 * 每次 CorpusCompile.compile(...) 新建一个实例，生命周期 = 单次编译，不跨编译共享可变状态
 * （AGENTS 铁律 4）。键由 [key] 组装，命中返回当初真编译留下的 [FunctionSlice]，调用方
 * 用 ErbProgramCompiler.adoptFunction 重放字节；未命中则真编译后 [store]。
 *
 * [hits] / [misses] 只在开态暴露，闸必须断言 hits > 0 —— 缓存永不命中也能产物相同，
 * 只测产物相同会放过空转。
 */
class FunctionCompileCache {
    private val entries = HashMap<String, FunctionSlice>()

    var hits: Int = 0
        private set
    var misses: Int = 0
        private set

    /**
     * 键 = 环境指纹（R4-B §3.2 八项）+ 函数体原文 + 非名字 FuncSig。
     *
     * 环境指纹必须进键：只留函数源文本会让「函数体不变、只改全局环境」的两次编译错命中，
     * 命中重放出旧环境的指令流 ⇒ 产物字节漂移。这是反向证明 A 的靶子。
     */
    fun key(env: CompileEnvFingerprint, bodyText: String, sigText: String): String =
        env.digest(bodyText.toByteArray(Charsets.UTF_8), sigText)

    /** 查键；命中/未命中各自累计计数（防空转闸读 [stats]）。 */
    fun lookup(key: String): FunctionSlice? {
        val cached = entries[key]
        if (cached != null) hits++ else misses++
        return cached
    }

    /** 未命中真编译后存切片。 */
    fun store(key: String, slice: FunctionSlice) {
        entries[key] = slice
    }

    /** 仅在开态打印/暴露的读数面（不进 manifest / 四件产物）。 */
    fun stats(): String = "funcCache: hits=$hits misses=$misses"
}
