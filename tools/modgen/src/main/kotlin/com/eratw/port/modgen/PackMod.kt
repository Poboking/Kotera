package com.eratw.port.modgen

import com.eratw.port.modapi.ModApi
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * `pack-mod` 子命令：把「ERB 源 + 包资源」打成可分发的**确定性** zip。
 *
 * ## 合同（与 app 导入端逐条对齐）
 *
 * - **先跑既有自检**：`ModCheck.check` 全套（schema / 字段键 / 三面一致 / images / audio / strings /
 *   requires / settings / resources）。有任何 ERROR 就拒绝打包（退出码 1）并把 findings 原样打印；
 *   只有 WARN 时允许打包，warning 也原样打印。
 * - **源**：模块目录下递归的全部 `.ERB`/`.ERH`（排除 `build/`、`.git/`），zip 内路径 = 相对模块根的
 *   `/` 分隔路径 —— 导入端 `ModZip.unpack` 只留这两类扩展名（`ModImportController.kt:1016-1017`）。
 * - **资源**：`<packageId>.assets/` 下的文件，zip 内路径 = `<packageId>.assets/<相对路径>` —— 导入端
 *   `ModImportAssets.stage` 只认 `<packageId>.assets/` 前缀（`ModImportAssets.kt:179`），前缀不符会跳过
 *   并 WARN。`packageId` 取模块源码里的 `const val PACKAGE_ID` 字面量（[ModCheckResources.packageIds]
 *   是同一单源）；**导入端最终以编译产物 packageId 为准**（`packageIdFor`，`ModImportController.kt:843`），
 *   打包只是给资源目录加前缀、不改名。
 * - **资源阈值**：单文件与总量上限直接引用 [ModResourceLimits]（构建期与运行期同一份常量的哨兵测试兜底），
 *   本文件不复制字面量。超限在 check-mod 阶段已判 ERROR，因此这里天然拒绝。
 * - **导入端闸（本波补齐）**：条目数 / 单条目解压量 / 整包解压量在收集阶段判，压缩比在**成包后**判
 *   （分母是 zip 字节数，成包才知道）。常量全部出自 [PackLimits]，由 `PackModLimitsSentinelTest`
 *   读 app 侧 `ModZipGuard.kt` 源码文本比对兜底；超限走 [PackPlan.unsafe] 通道 ⇒ 退出码 1，
 *   stderr 点名条目、判据与实际值 vs 上限。压缩比在成包后判的代价只是「拒绝时已经算过一遍 zip」，
 *   因为整份字节本就在内存里、超限时根本不会调 [PackModZip.write]，**不会留下半成品包**。
 * - **确定性**：条目按路径字典序、时间戳固定 1980-01-01、不写目录条目 ⇒ 同输入两次打包字节完全相同，
 *   摘要打印整份字节的 sha256 供对拍（细节见 [PackModZip]）。
 * - **包级元数据**：能解析出模块自述身份（`@ModCarrier` 的 `const val PACKAGE_ID` 与
 *   `modDeclarations(..., version = …)`）时，额外写一条 zip 根固定名条目 [PACK_META_ENTRY_NAME]
 *   （内容见 [PackModMeta]）；取不到 / 版本形态非法 ⇒ 不写该条目并打 WARN（导入端按 `"1"` 回落，
 *   与老包逐字一致）。元数据条目不计入摘要的「条目 N（源 X / 资源 Y）」，另起一行单报；
 *   但**计入导入端预算**（guard 数整包全部条目，512 模块条目 + 1 元数据必须被拒）。
 * - **min-api（作者声明的引擎下限）**：`--min-api <N>`（N ≥ 1）把作者要求的最低 mod-api 版本写进
 *   元数据条目的可选 `minApi` 键；省缺或 0 = 不设闸 ⇒ **不写键**，条目字节与加字段前逐字相同
 *   （线格式不变量，冻结夹具对拍）。导入端只搬运这个数（落到 manifest `/6` 的 `min-api` 段），
 *   真正的闸在装配期（`ModChainAssembly`：任一包 minApi > `ModApi.VERSION` ⇒ 整链拒载）；
 *   打包时不拦 > 当前 `ModApi.VERSION` 的取值，只打 WARN 提醒「这包只能在更新引擎上跑」：
 *   作者面向未来引擎发版是合法意图，这类包在旧引擎上「必然被拒载」是玩家侧装配期就能看到的事实，
 *   不属作者当下就能修的「必然失效」打包错误；报 ERROR 会逼作者为过闸把下限改小或删掉，
 *   反而让旧引擎上的玩家从「清楚地被拒载」退化成「装上后才在运行期错」。
 *   元数据条目不写（身份/版本取不到）时 `--min-api` 没有落盘位置 ⇒ 打 WARN 说明被忽略。
 * - **旁挂资源树（ADR-014 S3）**：`--resources <dir>`（作者自述目录名，非路径）把游戏根相对的
 *   `resources/mod/<dir>/…`（图/文本）与 `sound/mod/<dir>/…`（音频）打进 zip，条目路径保持游戏根
 *   一致；两棵树都**存在**时元数据追加 `"resources":"<dir>"`（在 `minApi` 之后），都不存在则与
 *   未加参数逐字节相同。名字/扩展名/限额判据在 [PackModResources]（复用导入端同源谓词，不复制）；
 *   超限或名字非法走 [PackUnsafe] 通道 ⇒ 退出码 1。
 * - **编译期 surface（ADR-013 D2）**：`--surface <N>`（N ≥ 0，surface 从 0 起）把作者声明的
 *   「本包编译于哪个 mod-api surface」写进元数据可选 `compiledSurface` 键（在 `resources` 之后，
 *   是最后一个可选键）；省缺 ⇒ **不写键**，条目字节与加字段前逐字相同（线格式不变量，冻结夹具
 *   对拍）。导入端只解析、**不在这里判**：真闸在 app 侧 `ModDexGate.verdict`（任何 dex 代码
 *   执行之前判 `compiledSurface > 引擎 API` 即拒）。不拦 > 当前 `ModApi.VERSION` 的取值（给未来
 *   引擎发版是合法意图，与 `--min-api` 同口径），元数据不写时该参数无落盘位置 ⇒ 打 WARN。
 * - **代码产物 code.mod（ADR-013 D1，打包端）**：`--code <file>` 把作者编译好的 `.mod` 包
 *   （一个含 `classes.dex` + `manifest.json` 的 zip）整份字节作为 zip **根级固定名**条目
 *   [CODE_MOD_ENTRY]（`code.mod`）打进包；导入端只认这一个名字，不做模糊匹配。省缺 ⇒
 *   **不写条目**，包字节与加该参数前逐字相同（冻结夹具对拍）。校验全部在 [PackModCode]：
 *   大小 ≤ [MAX_CODE_MOD_BYTES]、合法 zip、含非空 `classes.dex` 与 ≤
 *   [MAX_CODE_MOD_MANIFEST_BYTES] 字节的 `manifest.json`、manifest.packageId 过形态闸且等于
 *   模块 `const val PACKAGE_ID`。内容不合格 ⇒ 退出码 1（理由点名判据与实测值）；`--code`
 *   路径不存在 / 不是普通文件 ⇒ 退出码 2（用法错，与 `--out` 路径问题同档）。
 *   条目计入导入端的条目数/整包体积预算（[PackModZip.collect] 的 `generated` 账本），
 *   但不计入摘要的「条目 N（源 X / 资源 Y）」——那是模块素材的账，代码产物另起一行单报。
 * - **安全**：`--out` 落在模块目录内即拒绝（避免把产物或半截文件写进模块自身上下文）；
 *   条目名逐条过 [PackModZip.isSafeEntryName]（无 `..`、无绝对路径、无反斜杠、无控制字符），
 *   违反即拒绝并在 stderr 点名。
 *
 * 退出码：0 打包成功；1 已拒绝（check-mod ERROR / 条目名非法 / 导入端限额超限 / 没有 ERB 源）；
 * 2 用法或 I/O 错误。
 */
internal object PackMod {

    /** 成功。 */
    private const val EXIT_OK = 0

    /** 已拒绝：自检或输入面不允许出包。 */
    private const val EXIT_REFUSED = 1

    /** 用法 / I/O 错误。 */
    private const val EXIT_USAGE = 2

    /** findings 逐条打印复用 check-mod 的既有格式（[ModCheck.format]）。 */
    private const val CHECK_SUMMARY_PATTERN = "pack-mod: check-mod %d error(s), %d warning(s)"

    private const val NO_PACKAGE_ID_WARN =
        "pack-mod: WARN: 源码里没有 const val PACKAGE_ID，无法判定资源前缀（只打包 ERB 源）"

    /** 元数据摘要在「条目 N」之后另起一行；条目计数仍只描述模块内容（源/资源）。 */
    private const val PACK_META_SUMMARY = "pack-mod: 元数据 %s（packageId=%s / version=%s）"

    /** 取不到自述身份：不写元数据条目（导入端按 "1" 回落，老包行为不变）。 */
    private const val NO_PACK_META_WARN =
        "pack-mod: WARN: 源码里没有可解析的 @ModCarrier 自述身份，不写包元数据（导入端按 version \"1\" 读）"

    /** 版本形态落在导入端形态闸外：写了也只会被回落，故不写并提示作者修声明（不回显原文，防控制字符）。 */
    private const val NO_PACK_META_VERSION_WARN =
        "pack-mod: WARN: manifest.version 形态非法（空 / 超长 / 含控制字符），不写包元数据（导入端按 version \"1\" 读）"

    /** 声明了 --min-api 却没有元数据条目可写：这个数没有落盘位置，明说被忽略而不是静默丢。 */
    private const val NO_PACK_META_MIN_API_WARN =
        "pack-mod: WARN: 声明了 --min-api 但本次不写包元数据，min-api 没有落盘位置、被忽略（导入端按不设闸读）"

    /** 声明了 --surface 却没有元数据条目可写：这个数没有落盘位置，明说被忽略而不是静默丢。 */
    private const val NO_PACK_META_SURFACE_WARN =
        "pack-mod: WARN: 声明了 --surface 但本次不写包元数据，compiledSurface 没有落盘位置、被忽略（导入端按未声明读）"

    /** 声明了 --resources 但两棵树都不存在：按未声明处理，包字节与不加参数完全相同。 */
    private const val RESOURCES_ABSENT_WARN =
        "pack-mod: WARN: 声明了 --resources %s，但 resources/mod/%s/ 与 sound/mod/%s/ 都不存在，" +
            "按未声明处理（不写 resources 字段、包内容与不加参数逐字相同）"

    /** 资源树存在却写不出元数据：resources 字段没有落盘位置，导入端定位不到资源，只能拒绝。 */
    private const val RESOURCES_WITHOUT_META =
        "声明了 --resources 且资源树存在，但本次不写包元数据（取不到 @ModCarrier 自述身份 / version 形态非法）；" +
            "resources 字段没有落盘位置，导入端无法定位资源"

    /**
     * `--code` 但源码里没有可落盘的 `eratw-pack.json` 自述包名：代码包的**运行期身份**只能由
     * 元数据声明（导入端缺失时回退内层 manifest），打包器在这里拒包，而不是出一个「导入端无从
     * 知道代码属于哪个包名」的包（ADM：代码包身份一致化）。
     */
    private const val CODE_WITHOUT_PACKAGE_ID =
        "代码包必须声明包名：本模块没有可解析的 @ModCarrier packageId 或版本形态非法，" +
            "eratw-pack.json 不会写 packageId，导入端无法确定代码的运行期身份" +
            "（修好 @ModCarrier 声明，或去掉 --code）"

    /** 真的写了 resources 字段时的一行摘要（点名两棵树的落点，便于作者核对）。 */
    private const val RESOURCE_TREES_SUMMARY =
        "pack-mod: 资源目录 %s（resources/mod/%s/ + sound/mod/%s/，zip 内路径保持游戏根一致）"

    /** 元数据 min-api 声明行（只在真的写了键时出）；导入端只搬运，闸在装配期。 */
    private const val MIN_API_SUMMARY =
        "pack-mod: min-api %d（装配期 modApi 低于该值的引擎整链拒载；导入端只搬运，不在导入时判）"

    /** 元数据 compiled-surface 声明行（只在真的写了键时出）；ABI 闸在 app 侧 dex 装载之前判。 */
    private const val SURFACE_SUMMARY =
        "pack-mod: compiled-surface %d（本包编译于该 mod-api surface；引擎 API 更旧的导入端在 dex 装载前拒载）"

    /** 代码产物摘要行（只在给了 `--code` 时出；条目 Contents 与导入端同名字段对齐）。 */
    private const val CODE_MOD_SUMMARY = "pack-mod: 代码产物 %s（%d 字节，packageId=%s）"

    /** 作者声明的下限高于本机引擎：允许出包（可能是给未来引擎发版），但必须提醒本机装不上。 */
    private const val MIN_API_ABOVE_ENGINE_WARN =
        "pack-mod: WARN: --min-api %d 高于当前引擎的 ModApi.VERSION=%d，本机装配期会整链拒载（给未来引擎发版才这样写）"

    /** CLI 入口：返回进程退出码。 */
    fun runCli(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int =
        try {
            dispatch(args, out, err)
        } catch (error: IllegalArgumentException) {
            err.println(ERROR_PREFIX + (error.message ?: "参数非法"))
            printPackUsage(err)
            EXIT_USAGE
        } catch (error: IOException) {
            err.println(ERROR_PREFIX + "I/O 失败：" + error.message)
            EXIT_USAGE
        }

    private fun dispatch(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        val options = PackOptionParser.parse(args, err)
        return if (options == null) EXIT_USAGE else pack(options, out, err)
    }

    /** 校验路径 ⇒ 跑 check-mod ⇒ 打印 findings ⇒ 决定打包或拒绝。 */
    private fun pack(options: PackOptions, out: PrintStream, err: PrintStream): Int {
        val moduleRoot = options.projectDir.toPath().toAbsolutePath().normalize()
        val output = options.output.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(moduleRoot)) { "模块目录不存在：${options.projectDir}" }
        require(!output.startsWith(moduleRoot)) { "--out 不能落在模块目录内：$output" }
        require(!Files.isDirectory(output)) { "--out 指向的是目录：$output" }
        // --code 的路径问题属用法错（退出码 2）：内容校验在 writePackage 里做（那里才有模块 PACKAGE_ID），
        // 不合格走 refuse（退出码 1），两条路分开，作者能看出是「路径给错」还是「产物内容不合格」。
        options.artifacts.code?.let { file ->
            require(Files.isRegularFile(file.toPath())) { "$OPTION_CODE 不是普通文件：$file" }
        }
        // 目录名形态是「已拒绝」而不是「用法错」：作者给了名字但落不进导入端合同（ADR-014 D4），
        // 与资源树超限/名字非法同一档（退出码 1），消息点名判据。
        options.resources?.let { dir -> PackModResources.dirProblem(dir)?.let { return refuse(err, it) } }
        // 旁挂资源面在 [writePackage] 里走 PackModResources 的 PackUnsafe 通道（文案/退出码是既有
        // 合同），check-mod 那一面（ModCheckMediaResources）只在作者自检路径产出，避免重复报错。
        val findings = ModCheck.check(options.projectDir, options.schemaFiles, includeMediaResources = false)
        findings.forEach { out.println(ModCheck.format(it)) }
        val errors = findings.count { it.severity == Severity.ERROR }
        out.println(CHECK_SUMMARY_PATTERN.format(errors, findings.size - errors))
        if (options.minApi > ModApi.VERSION) {
            out.println(MIN_API_ABOVE_ENGINE_WARN.format(options.minApi, ModApi.VERSION))
        }
        return if (errors == 0) {
            writePackage(moduleRoot, output, options, out, err)
        } else {
            refuse(err, "check-mod 有 $errors 个 ERROR，修完再打")
        }
    }

    /**
     * 收集 ⇒ 安全/预算闸 ⇒ 空源闸 ⇒ 成包 ⇒ 压缩比闸 ⇒ 写盘（含包元数据条目）并打印摘要。
     * 两个 unsafe 相位共用同一条文案形态（`- 条目：原因；建议改法`，见 [PackUnsafe]）。
     */
    private fun writePackage(
        moduleRoot: Path,
        output: Path,
        options: PackOptions,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        // 一次扫描供两处消费：资源前缀候选 + 包级元数据；不重复解析源码。
        val sources = ModCheckSources.scan(moduleRoot, mutableListOf())
        val packageIds = ModCheckResources.packageIds(sources)
        if (packageIds.isEmpty()) out.println(NO_PACKAGE_ID_WARN)
        val meta = ModCheckDeclarations.declaredPackMeta(sources)
        // 裁决 5：`--code` 必须有可落盘的 eratw-pack.json 自述包名（meta 取不到 / version 形态
        // 非法都会导致元数据整条不写，代码包就丢了运行期身份）。不带 --code 的包字节行为不变。
        val packMetaWritable = meta != null && PackModMeta.versionAccepted(meta.version)
        // --code 校验放在这里：身份要等源码扫描出 packageId 才能比对；失败按「已拒绝」档（退出码 1）。
        val code = codeArtifactOf(options.artifacts.code, options.artifacts.entrypoint, packageIds)
        val resources = resourceStateOf(moduleRoot, options.resources, meta, out)
        if (resources is ResourceState.Rejected) return refuse(err, resources.reason)
        val declaredResources = (resources as? ResourceState.Active)?.dir
        val codeEntry = (code as? CodeArtifact.Ready)?.entry
        // 元数据条目先算出来：guard 的条目数/体积账本数整包所有条目，预算闸必须看到它。
        // 代码产物条目同样走 generated 通道：导入端预算数它，摘要的「条目 N」不数它。
        val generated = metaEntriesOf(meta, options.minApi, declaredResources, options.artifacts.compiledSurface, out) +
            listOfNotNull(codeEntry)
        val plan = PackModZip.collect(moduleRoot, packageIds, generated, declaredResources)
        val draft = PackageDraft(plan, generated, declaredResources, codeEntry, packageIds.firstOrNull())
        return when {
            // 最先判：没有可落盘自述包名的模块不许出代码包（错误消息点名 packageId）。
            options.artifacts.code != null && !packMetaWritable -> refuse(err, CODE_WITHOUT_PACKAGE_ID)
            code is CodeArtifact.Refused -> refuse(err, code.reason)
            plan.unsafe.isNotEmpty() -> refuse(err, unsafeRefusal(COLLECT_GATE_PREFIX, plan.unsafe))
            plan.sourceCount == 0 -> refuse(err, "模块目录下没有 .ERB/.ERH 源：$moduleRoot")
            else -> emitPackage(draft, output, out, err)
        }
    }

    /** `--code` 的判定：未给 / 内容合格（条目已就绪）/ 内容不合格；不打印，出口由 [writePackage] 决定。 */
    private fun codeArtifactOf(file: File?, entrypoint: String?, packageIds: List<String>): CodeArtifact = when (file) {
        null -> CodeArtifact.None
        else -> PackModCode.problemOf(file, packageIds.firstOrNull(), entrypoint)
            ?.let { CodeArtifact.Refused(it) }
            ?: CodeArtifact.Ready(PackModCode.entryOf(file))
    }

    /**
     * `--resources` 的判定：未声明 / 树不存在（退化成未声明并打 WARN）/ 拒绝 / 生效。
     * 「存在树」才写字段：都不存在 ⇒ 包字节与不加参数逐字相同（线格式不变量）。
     */
    private fun resourceStateOf(
        moduleRoot: Path,
        dir: String?,
        meta: DeclaredPackMeta?,
        out: PrintStream,
    ): ResourceState = when {
        dir == null -> ResourceState.None
        !PackModResources.hasTree(moduleRoot, dir) -> {
            out.println(RESOURCES_ABSENT_WARN.format(dir, dir, dir))
            ResourceState.None
        }
        meta == null || !PackModMeta.versionAccepted(meta.version) -> ResourceState.Rejected(RESOURCES_WITHOUT_META)
        else -> ResourceState.Active(dir)
    }

    /** 判定全部落定后的成包相位：预算闸 ⇒ 写盘 ⇒ 摘要；两个 unsafe 相位共用同一条拒绝形态。 */
    private fun emitPackage(draft: PackageDraft, output: Path, out: PrintStream, err: PrintStream): Int {
        val build = PackModZip.build(draft.plan.entries, draft.generated)
        if (build.unsafe.isNotEmpty()) return refuse(err, unsafeRefusal(BUILD_GATE_PREFIX, build.unsafe))
        val result = PackModZip.write(output, build.bytes)
        if (draft.declaredResources != null) {
            val summary = RESOURCE_TREES_SUMMARY.format(
                draft.declaredResources,
                draft.declaredResources,
                draft.declaredResources,
            )
            out.println(summary)
        }
        if (draft.codeEntry != null && draft.packageId != null) {
            out.println(CODE_MOD_SUMMARY.format(CODE_MOD_ENTRY, draft.codeEntry.bytes.size.toLong(), draft.packageId))
        }
        printSummary(draft.plan, result, out)
        return EXIT_OK
    }

    /**
     * 包元数据条目（0 或 1 条）：取不到自述身份 / 版本形态非法 ⇒ 不写条目并打 WARN。
     * 导入端在本条目缺失时按 `"1"` 回落，故这里的降级永远安全，只是读不到作者版本号。
     *
     * @param meta 已解析出的自述身份（null = 取不到）
     * @param minApi 作者声明的引擎下限；0 = 不设闸 ⇒ 条目里不写 `minApi` 键（字节与老包逐字相同）
     * @param resources 已确认存在的资源目录名；null = 不写 `resources` 字段（字节与老包逐字相同）
     * @param compiledSurface 作者声明的编译期 mod-api surface；null = 不写 `compiledSurface` 键
     *   （字节与老包逐字相同）。写在 `resources` 之后。
     */
    private fun metaEntriesOf(
        meta: DeclaredPackMeta?,
        minApi: Int,
        resources: String?,
        compiledSurface: Int?,
        out: PrintStream,
    ): List<GeneratedEntry> = when {
        meta == null -> {
            out.println(NO_PACK_META_WARN)
            if (minApi > 0) out.println(NO_PACK_META_MIN_API_WARN)
            if (compiledSurface != null) out.println(NO_PACK_META_SURFACE_WARN)
            emptyList()
        }
        !PackModMeta.versionAccepted(meta.version) -> {
            out.println(NO_PACK_META_VERSION_WARN)
            if (minApi > 0) out.println(NO_PACK_META_MIN_API_WARN)
            if (compiledSurface != null) out.println(NO_PACK_META_SURFACE_WARN)
            emptyList()
        }
        else -> {
            out.println(PACK_META_SUMMARY.format(PACK_META_ENTRY_NAME, meta.packageId, meta.version))
            if (minApi > 0) out.println(MIN_API_SUMMARY.format(minApi))
            if (compiledSurface != null) out.println(SURFACE_SUMMARY.format(compiledSurface))
            listOf(
                GeneratedEntry(
                    PACK_META_ENTRY_NAME,
                    PackModMeta.json(meta.packageId, meta.version, minApi, resources, compiledSurface),
                ),
            )
        }
    }

    /**
     * 摘要：条目数 / 源 / 资源 / 跳过及原因 / 输出路径 / sha256（后两项供两次打包对拍）。
     * 包元数据条目不计入「条目 N」——它是打包器的自述，不属于模块素材，另有独立摘要行。
     */
    private fun printSummary(plan: PackPlan, result: PackedZip, out: PrintStream) {
        out.println("pack-mod: 条目 ${plan.entries.size}（源 ${plan.sourceCount} / 资源 ${plan.resourceCount}）")
        out.println("pack-mod: 跳过 ${plan.skipped.size}" + skipReasonSummary(plan.skipped))
        out.println("pack-mod: 输出 ${result.output}")
        out.println("pack-mod: sha256 ${result.sha256Hex}")
    }

    /** 统一拒绝出口：stderr 打原因，退出码 1。 */
    private fun refuse(err: PrintStream, reason: String): Int {
        err.println(ERROR_PREFIX + "拒绝打包：" + reason)
        return EXIT_REFUSED
    }
}

/** 跳过原因聚合，如 `（构建输出目录（build/） 2 项）`；无跳过写 `（无）`。 */
private fun skipReasonSummary(skips: List<PackSkip>): String {
    if (skips.isEmpty()) return "（无）"
    val counts = skips.groupingBy { it.reason }.eachCount().toSortedMap()
    return "（" + counts.entries.joinToString("；") { "${it.key} ${it.value} 项" } + "）"
}

/** 收集相位 unsafe 的拒绝文案前缀（条目名 / 预算类判据）。 */
private const val COLLECT_GATE_PREFIX = "下列条目或整包指标过不了导入端闸，拒绝打包："

/** 成包相位 unsafe 的拒绝文案前缀（压缩比只有成包后才知道）。 */
private const val BUILD_GATE_PREFIX = "成包后的压缩比过不了导入端闸（疑似压缩炸弹），拒绝打包："

/**
 * unsafe 通道的正文：`- 条目：原因；建议改法` 逐条列出，由 [PackMod] 的 `refuse` 统一加前缀。
 * 放在对象外只是为了让 [PackMod] 的函数数留在 detekt 的 `TooManyFunctions` 阈值内。
 */
private fun unsafeRefusal(prefix: String, unsafe: List<PackUnsafe>): String =
    prefix + "\n" + unsafe.joinToString("\n") { "  - ${it.name}：${it.reason}；建议${it.hint}" }

/** `--code` 的三种结局：未给 / 内容合格（条目已就绪）/ 内容不合格（拒绝理由）。 */
private sealed interface CodeArtifact {
    /** 参数没给：不写 `code.mod` 条目（包字节与不加参数逐字相同）。 */
    object None : CodeArtifact

    /** 内容校验通过：整份字节作为根级条目。 */
    class Ready(val entry: GeneratedEntry) : CodeArtifact

    /** 内容校验不过：按「已拒绝」档退出（退出码 1），理由点名判据。 */
    class Refused(val reason: String) : CodeArtifact
}

/** `--resources` 的四种结局：未声明 / 树不存在（已退化并打 WARN）/ 拒绝 / 生效。 */
private sealed interface ResourceState {
    /** 没给参数，或树不存在：都按「未声明」写包（线格式不变量）。 */
    object None : ResourceState

    /** 两棵树存在且元数据可挂：写 `resources` 字段并带上旁挂树。 */
    class Active(val dir: String) : ResourceState

    /** 树在但元数据缺/版本非法：拒绝（退出码 1），理由点名判据。 */
    class Rejected(val reason: String) : ResourceState
}

/**
 * 成包所需的全部已定稿输入（资源/代码产物判定完后）：收集计划、generated 条目、已生效资源目录名、
 * 代码产物条目与模块身份。单独成类只是为了让 [PackMod.emitPackage] 的形参数留在 detekt 阈值内。
 */
private class PackageDraft(
    val plan: PackPlan,
    val generated: List<GeneratedEntry>,
    val declaredResources: String?,
    val codeEntry: GeneratedEntry?,
    val packageId: String?,
)
