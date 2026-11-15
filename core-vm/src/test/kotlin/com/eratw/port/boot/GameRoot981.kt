package com.eratw.port.boot

import com.eratw.port.core.compat.EngineProfileData
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 4.981 游戏根发现（测试装置，不进发布代码）。
 *
 * 口径与 `RealCorpus` 同形，但**必须锁 4.981**：本波的判据（COMF641 卡牌常量族、
 * QOL 物品库 1200 槽、AI 对话补丁的 CALLSHARP）都是 4.981 才有的文件，指到 4.980
 * 会得到一组「文件不存在」的空转判据。
 *
 * 根目录名与认根标记**都不在本文件写字面量**（CONTRACT-ENGCPL E7）：吃 Kotlin 投影
 * `EngineProfileData.CORPORA["V4_981"]`（由 `specs/engine-profiles.input.yaml` 经
 * `devtools/gen_engine_profiles.py` 生成）。运行期/测试期都**不读 yaml**（I3：只读生成物
 * 常量）；换根 = 改 input yaml + 重生成，本文件与两处 gradle 脚本都不用动。
 *
 * 优先级（corpusroot 波修正，2026-11-05）：**环境变量显式给出即权威** ——
 * `ERATW_GAMEDIR` / `ERATW_CORPUS`（取父目录）任一给了，就只在给出的候选里找 4.981 标记；
 * 给出的根不含标记（例如闸正按 4.980 收口）⇒ 返回 null 由调用方 `assumeTrue` 可见跳过，
 * **不再**回落到硬编码共享根（旧行为会把「闸喂 4.980」伪装成「4.981 判据在跑」，
 * 正是「哪个闸吃哪个根」失去判据的一条暗通道）。两个变量都没给时才走候选链：
 * worktree 同级目录 → 共享根绝对路径。全部落空返回 null。
 */
object GameRoot981 {

    /** 本装置锁定的语料版本 id（生成物 `CORPORA` 的键）。 */
    private const val CORPUS_ID = "V4_981"

    private val corpus = EngineProfileData.CORPORA[CORPUS_ID]
        ?: error(
            "生成物 EngineProfileData.CORPORA 没有 '$CORPUS_ID'（已有 ${EngineProfileData.CORPORA.keys}）：" +
                "4.981 语料门失去身份来源，先重生成 specs/engine-profiles.input.yaml 的 corpora 段",
        )

    /** 4.981 打包根目录名（= 生成物 `corpora.V4_981.root_dir`）。 */
    val DIR: String = corpus.rootDir

    /**
     * 4.981 认根标记（= 生成物 `corpora.V4_981.marker_file`）。为空即生成物退化成
     * 「只认目录名」，本装置会失去验明正身的能力 ⇒ 显式失败，不静默放行。
     */
    private val MARKER: String = corpus.markerFile ?: error(
        "生成物 corpora.V4_981.marker_file 为空：4.981 语料门没有认根标记（换语料请同步给 marker_file）",
    )

    private fun hasMarker(p: Path): Boolean = Files.isRegularFile(p.resolve(MARKER))

    fun load(): Path? {
        val explicit = LinkedHashSet<Path>()
        System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { explicit.add(Paths.get(it)) }
        System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() }?.let { c ->
            Paths.get(c).toAbsolutePath().normalize().parent?.let { explicit.add(it) }
        }
        if (explicit.isNotEmpty()) {
            return explicit.firstOrNull { hasMarker(it) }
        }
        val candidates = LinkedHashSet<Path>()
        val cwd = Paths.get("").toAbsolutePath().normalize()
        val repoRoot = cwd.parent?.parent ?: cwd
        repoRoot.parent?.let { up -> candidates.add(up.resolve(DIR)) }
        return candidates.firstOrNull { hasMarker(it) }
    }

    /** 语料 ERB 子树（`<游戏根>/ERB`）。 */
    fun erb(): Path? = load()?.resolve("ERB")

    /** 数据 CSV 子树（`<游戏根>/CSV`）—— 越界类判据要用它取 `VariableSize.csv`。 */
    fun csv(): Path? = load()?.resolve("CSV")
}
