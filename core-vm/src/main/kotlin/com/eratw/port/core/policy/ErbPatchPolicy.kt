package com.eratw.port.core.policy

/**
 * ERB 补丁**整体禁载** —— 引擎侧通用能力（PLUGIN-POLICY 第二轮，2026-11-07，主人指令）。
 *
 * ## 能力定义（引擎只提供机制，不认任何一个具体补丁）
 *
 * 给定一张 [Entry] 清单，引擎在**编译期**把清单命中的 `.ERB` 脚本整体排除在程序之外：
 * 该文件一个函数体、一条语句（含 `CALLSHARP`、`PRINTFORML`、事件成员、变量回写）都不
 * 进 `.erbc`。这与「按 CALLSHARP 方法名打缺席位」是两代能力 —— 后者只让某一句空转，
 * 前者让整个补丁从执行面上消失（补丁挂进 `@EVENTCOMEND` 的那个成员也不再产生任何行为）。
 *
 * 配置数据见 [DisabledErbPatchCatalog]（唯一真相）；`erbcc`（编译 PC 产物）与 `app`
 * （运行期宿主 + 设置页）都依赖 `:core-vm`，共用同一张表，不许各写一份。
 * 判定函数是纯函数、清单可注入（[isExcluded] 的 `entries` 形参），所以「换一个补丁、
 * 加一个目录、临时关掉整张表」都不需要改引擎代码；空清单 ⇒ 恒 false，零行为差。
 *
 * ## 三条通用例外（不是为某个补丁开的特例，是语料完整性约束）
 *
 * 1. **`.ERH` 变量声明头不排除**：ERH 装载先于 ERB、是全局符号面。4.981 的
 *    `AI对话开关` / `AI对话补丁` / `口上输出信息` / `COUNTER输出信息` 被主语料
 *    `SHOP関連/OPTION_COM_FILTER.ERB:76-88`、`コマンド関連/USERCOM.ERB:35-40`、
 *    `DLC/DLC注册/Addition_DLC_70.ERB:7-33`、`DLC/实用设置补丁/实用设置.ERB:491`、
 *    `ステータス計算関連/COUNTER/COUNTER_SELECT.ERB:35-37`、
 *    `口上・メッセージ関連/KOJO_MESSAGE.ERB:262` 静态引用；把 ERH 一起删会让全量编译
 *    直接判红。所以规则统一为「只对 `.ERB` 生效」。
 * 2. **标签定义仍保留为空体函数**：[com.eratw.port.core.frontend.LabelTable] 独立扫全量
 *    ERB 登记 `@` 标签，禁载只发生在切片阶段。被禁载补丁的标签因此仍在函数表里，
 *    编译成零语句空体 —— 目录外的静态 `CALL` 才能解析通过（实形：
 *    `KOJO_MESSAGE.ERB:263 CALL AI_MESSAGE(KOJO_NAME, LOCAL, LOCALS)`，该函数定义在
 *    `DLC/AI对话补丁/参数返回.ERB:543`）。若不保留，玩家把 `AI对话开关` 点成非 0 时
 *    会撞「未定义函数」，比原先的缺插件报错更糟。空体 = 补丁整体不生效，且不会误伤
 *    其它 10+ 个同名 `@EVENTCOMEND` 成员（事件组按定义逐条挂链，不按函数名一刀切）。
 * 3. **源文件清单仍登记被排除的脚本**：`sourceFiles` / freshness 对账按「磁盘上有什么」
 *    走（同 CMP-64 二进制跳过的「登记但不解析」口径），避免产物与语料摘要对不上。
 *    禁载的可见性由 manifest 的 `disabled-erb` 段承担，不靠从清单里抹掉文件。
 *
 * ## 与运行期缺席位的关系
 *
 * 编译期整体禁载是主手段；运行期 `NativeMethodRegistry.markPolicyAbsent`（app
 * `GameHost.wireCorpusNativeMethods`）是**纵深防御**：旧 `.erbc`（编译器升级前产出）、
 * 玩家导入的 mod、或后续语料新增的同名 `CALLSHARP` 仍应静默空转而不是抛 CodeEE。
 * 两层共用 [Entry.nativeMethodNames]，目录外未知名字照旧走官方缺插件形态（ADR-005 收窄不废除）。
 */
object ErbPatchPolicy {

    /** 一条禁载条目：一个稳定 id + 若干相对 ERB 根的路径规则。 */
    class Entry(
        /** 稳定 id（设置页按它映射三语文案键；改动即文案迁移）。 */
        val id: String,
        /**
         * 相对 `ERB/` 根的路径规则，`/` 分隔、大小写不敏感：
         * - 以 `/` 结尾 = 目录前缀（**目录边界**，`"DLC/X/"` 不误伤 `"DLC/XY/a.ERB"`）；
         * - 不以 `/` 结尾 = 精确文件相对路径。
         */
        val erbPathPrefixes: List<String>,
        /**
         * 该补丁用过的 CALLSHARP 方法名（运行期缺席位纵深防御用）。
         * 允许为空：只做 ERB 整体禁载、不涉及本机方法的补丁不必填。
         */
        val nativeMethodNames: List<String> = emptyList(),
    ) {
        init {
            require(id.isNotBlank()) { "禁载条目 id 不得为空" }
            require(erbPathPrefixes.isNotEmpty()) { "禁载条目 $id 至少要有一条路径规则" }
            erbPathPrefixes.forEach { raw ->
                require(raw.isNotBlank() && !raw.startsWith("/") && !raw.contains('\\')) {
                    "禁载条目 $id 的路径规则必须是相对 ERB 根、'/' 分隔：$raw"
                }
                require(!raw.contains("..")) { "禁载条目 $id 的路径规则不许含 '..': $raw" }
            }
        }
    }

    /** 路径归一：`\` → `/`，去掉前导 `./` 与 `/`。用于所有判定与留痕。 */
    fun normalize(relPath: String): String =
        relPath.replace('\\', '/').removePrefix("./").trimStart('/')

    /** 只对脚本生效；`.ERH` 变量头一律保留（见类 KDoc 例外 1）。 */
    fun isErbScript(relPath: String): Boolean = normalize(relPath).endsWith(".erb", ignoreCase = true)

    /**
     * 命中该路径的禁载条目；未命中 / 路径是 ERH / 清单为空 ⇒ null。
     *
     * @param entries 可注入清单（测试与第二套语料配置用），缺省 = [DisabledErbPatchCatalog.all]。
     */
    fun ownerOf(
        relPath: String,
        entries: List<Entry> = DisabledErbPatchCatalog.all,
    ): Entry? {
        if (entries.isEmpty() || !isErbScript(relPath)) return null
        val rel = normalize(relPath)
        return entries.firstOrNull { e ->
            e.erbPathPrefixes.any { rule ->
                val r = normalize(rule)
                if (r.endsWith("/")) rel.startsWith(r, ignoreCase = true)
                else rel.equals(r, ignoreCase = true)
            }
        }
    }

    /** [ownerOf] 的布尔形（编译期切片判定的唯一入口）。 */
    fun isExcluded(
        relPath: String,
        entries: List<Entry> = DisabledErbPatchCatalog.all,
    ): Boolean = ownerOf(relPath, entries) != null

    /** 全清单的 CALLSHARP 名字（运行期缺席位用；保留目录序、不去重，注册表自行归一）。 */
    fun absentNativeMethodNames(
        entries: List<Entry> = DisabledErbPatchCatalog.all,
    ): List<String> = entries.flatMap { it.nativeMethodNames }
}

/**
 * 禁载**配置数据**（唯一真相）。这里是数据不是机制：新增补丁只需加一条 [ErbPatchPolicy.Entry]
 * 并补语料证据，引擎侧 [ErbPatchPolicy] / erbcc / app 三处代码一行都不用改。
 *
 * 纪律（AGENTS.md §6）：新条目必须给出语料落点（`文件:行号`）与「为什么本版装不上」的证据；
 * 装得上的补丁不许进这张表；本版不落盘、不开运行期开关（主人裁定），要启用只能改代码重新出包。
 */
object DisabledErbPatchCatalog {

    /**
     * 4.981 AI对话补丁（`ERB/DLC/AI对话补丁/`，三个 `.ERB` + 一个 `.ERH`）。
     *
     * - 脚本：`对话调用.erb`（`@CHAT_ACTION` / `@AI对话调用可否检测` / `@EVENTCOMEND` /
     *   `@GET_CURRENT_ACTION_INFO`）、`参数返回.ERB`（`@AI_MESSAGE` 等 15 个函数）、`人设.erb`
     *   （4 个 `M_KOJO_AI_INFO_*` 口上标签）；
     * - 变量头 `AI返回参数.ERH` 按通用规则保留（见 [ErbPatchPolicy] 例外 1）；
     * - `对话调用.erb:35-36` 是全语料仅有的可达 CALLSHARP（`ModelSet` / `Chat`；`:54` 的
     *   9 参 `Chat` 变体被 `[SKIPSTART]` 包住不编译，ErbLoader.cs:163-166）。原版由发行包
     *   `plugins/ChatCallSharp.dll`（进程级 .NET 插件）提供，本版结构性装不了；其语料还烧死了
     *   补丁作者的第三方 API key（`AI返回参数.ERH:6-8`），产品侧也不应替玩家外发游戏状态。
     * - 触发链：DLC 默认启用、指令列表常驻 `[AI对话:关闭]` 按钮
     *   （`OPTION_COM_FILTER.ERB:76-88`，点一下 `AI对话开关` 0→1），门槛蹭多系统共用的
     *   `TFLAG:100`（`FUNC_IRAI.ERB:163`）⇒ 不整体禁载时全体玩家每次装载吃两句英文警告、
     *   误触就吃 CodeEE（上报簇 `9bcc9efd`）。
     */
    val AI_CHAT: ErbPatchPolicy.Entry = ErbPatchPolicy.Entry(
        id = "ai-chat",
        erbPathPrefixes = listOf("DLC/AI对话补丁/"),
        nativeMethodNames = listOf("ModelSet", "Chat"),
    )

    /** 全目录（编译期、运行期、设置页各消费一次；顺序即设置页列出序）。 */
    val all: List<ErbPatchPolicy.Entry> = listOf(AI_CHAT)
}
