package com.eratw.port.vmdbg

import com.eratw.port.erbcc.compile.ErbcCompilerDigest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 产物新鲜度闸（CONTRACT-DBG-TOOLING §4）。vmdbg 此前对 erbc 产物的 compiler-digest
 * 完全不校验（全 main 源集零判定点，探查实证）——喂一份旧编译器产物会静默装载并跑完
 * sweep，结论全部作废且没有任何暴露点。规则：
 *
 * - 能定位到编译器源码树（`--source-root` 显式指定，或 [ErbcCompilerDigest.locateRepoRoot]
 *   从产物目录/工作目录/class 位置向上找到 MARKER）→ **必须**重算比对，不一致 / 清单缺
 *   `compiler-digest` 行都直接 [CliError] 拒载（口径同 erbcc check 的 COMPILER_STALE 三形态，
 *   tools/erbcc/.../ArtifactFreshness.kt:270-313），错误信息带重编命令与豁免开关；
 * - 定位不到源码树（桌面产物目录 + 无 cwd 可归）→ 静默放行（vmdbg 是判读工具不是出包闸；
 *   要强判请显式传 --source-root）；
 * - `--no-digest-check` 显式豁免（对拍旧产物这类故意场景）。
 *
 * 全过程**不打 stdout**：多个子命令的 stdout 是行协议（status=/message=...），混入一行就会
 * 破坏下游解析（VmdbgCliTest 实测踩过）；成功是常态，无需可见性，错误走 CliError（err 因果链）。
 *
 * digest 重算按 repoRoot 缓存（[digestCache]）：同进程多条 recheck / 测试套件只算一次
 * （两棵树几百个 .kt 的 SHA-256 是秒级开销，不该乘以条数）。
 */
internal object ArtifactFreshnessGate {

    /** 重编命令（错误信息指路用；与 erbcc check / buildSrc 闸同一句口径）。 */
    private const val RECOMPILE_HINT = "./gradlew :tools:erbcc:compileCorpus"

    /** repoRoot → digest 的进程级缓存（见类 KDoc 的开销论证）。 */
    private val digestCache = ConcurrentHashMap<Path, String>()

    fun check(erbcDir: Path, a: Args) {
        if (!a.flag("--no-digest-check")) checkAgainstCurrentTree(erbcDir, a)
    }

    /**
     * 定位源码树 → 重算 digest → 出 STALE 结论。定位不到源码树 = 判据不在
     * （判读工具不硬拒，要强判给 --source-root），静默放行。
     */
    private fun checkAgainstCurrentTree(erbcDir: Path, a: Args) {
        val repoRoot = a.value("--source-root")?.let { Path.of(it) }
            ?: ErbcCompilerDigest.locateRepoRoot(erbcDir)
        val stale = repoRoot?.let { root -> staleReason(erbcDir, currentDigest(root)) }
        if (stale != null) throw CliError(stale)
    }

    /** 当前源码树 digest（repoRoot 级缓存：两棵树几百个 .kt 的 SHA-256 是秒级开销，不乘以条数）。 */
    private fun currentDigest(repoRoot: Path): String = try {
        digestCache.computeIfAbsent(repoRoot.toAbsolutePath().normalize()) { root ->
            ErbcCompilerDigest.compute(root).digest
        }
    } catch (e: IllegalStateException) {
        throw CliError("compiler-digest 无法重算（${e.message}）→ 不能证明产物出自当前编译器", e)
    }

    /**
     * 产物与当前源码树的 STALE 结论（null = 新鲜）：三种形态——清单缺失 / 清单没有
     * compiler-digest 行（旧格式产物）/ digest 不等。
     */
    private fun staleReason(erbcDir: Path, current: String): String? {
        val manifestPath = erbcDir.resolve("manifest.txt")
        val declared = runCatching {
            Files.readAllLines(manifestPath, Charsets.UTF_8)
                .firstOrNull { it.startsWith("compiler-digest ") }
                ?.removePrefix("compiler-digest ")?.trim()
        }.getOrDefault("")
        return when {
            !Files.isRegularFile(manifestPath) ->
                "产物清单缺失: $manifestPath（无 compiler-digest 判据）→ 请用当前编译器重编：$RECOMPILE_HINT"
            declared.isNullOrEmpty() ->
                "产物由旧编译器产出（清单没有 compiler-digest 行，是旧格式/旧编译器产物），必须重编：" +
                    "当前源码树 $current → $RECOMPILE_HINT"
            !declared.equals(current, ignoreCase = true) ->
                "产物由旧编译器产出，必须重编：清单 compiler-digest $declared，当前源码树 $current " +
                    "→ $RECOMPILE_HINT（重编后再跑；对拍旧产物请显式 --no-digest-check）"
            else -> null
        }
    }
}
