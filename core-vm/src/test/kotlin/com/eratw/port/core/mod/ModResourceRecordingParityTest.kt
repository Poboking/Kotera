package com.eratw.port.core.mod

import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModResourceApi
import com.eratw.port.modtesting.TestWorld
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 台架资源面（`context.resources`）× 生产资源面的**语义对拍**（Lane CP / task-87）。
 *
 * 左边 = 生产 `ModResourceStore`（真宿主实现，`core-vm/src/main/kotlin/com/eratw/port/core/mod/
 * ModResourceStore.kt`）；右边 = `:mod-testing` 的台架（`mod-testing/src/main/kotlin/com/eratw/
 * port/modtesting/TestWorld.kt` + `TestWorldState.kt` + `TestResourceFiles.kt`）。台架不重写名字 /
 * 上限语义，直接复用同一个 `ModResourceStore`；本用例锁的是**接线**：根目录布局、WARN 汇、
 * 默认上限、投放不产 WARN、close 后按新根重建 —— 这些一旦漂移，作者单测的读数就与真机不同。
 *
 * ## 语义对照表（引擎 vs 台架）
 *
 * 逐条格式：`- 语义：引擎 = …；台架 = …；判定 = …`。
 *
 * - 名字校验：引擎 `apiFor`/`requireName` 只收规范 `/` 相对名，四入口非法名当场抛 IAE
 *   （`ModResourceStore.kt:207-215`、`:92-135`）；台架投放先经静默 `resourceValidator.exists`
 *   走同一 `requireName`（`TestResourceFiles.kt:29-31`）；一致 = 是（IAE 文案同源逐字）。
 * - packageId 校验：引擎 `PACKAGE_ID_PATTERN` require（`ModResourceStore.kt:61-72`）；台架投放口
 *   同一 store 的 `apiFor`（`TestResourceFiles.kt:31`）；一致 = 是。
 * - 空面：引擎包目录不存在 ⇒ 空表 + 三入口 false/null，不告警（`ModResourceStore.kt:137-144`）；
 *   台架未投放的包同一实现、同一根布局（`TestWorldState.kt` 的 `newResourceStore`）；一致 = 是。
 * - 缺包：引擎同空面，包身份独立、跨包隔离（`ModResourceStore.kt:95-99`）；台架按 packageId
 *   分目录（`TestWorld.kt` 的 `resource`）；一致 = 是。
 * - 超大（单文件）：引擎 >`MAX_RESOURCE_FILE_BYTES`（4 MiB）跳过并 WARN（`ModResourceStore.kt:76`、
 *   `:158-166`）；台架不注入上限 ⇒ 必须与默认值同档（本用例锁常量）；一致 = 是（WARN 逐字）。
 * - 超总量：引擎 >`MAX_RESOURCE_TOTAL_BYTES`（16 MiB）整包禁用 + 一条 WARN
 *   （`ModResourceStore.kt:79`、`:167-176`）；台架默认上限不可注入 ⇒ 一致性靠本用例锁；
 *   一致 = 是（WARN 逐字）。
 * - 坏 IO / 坏目录：引擎 `listFiles()==null` WARN 跳过（`ModResourceStore.kt:186-190`）、`open`
 *   读失败 WARN 返 null（`:123-126`）；台架公开写面只 `mkdirs`/`writeBytes`，造不出坏目录或读失败；
 *   一致 = 同一实现、台架不可观测 ⇒ 无漂移面，TODO(verify) 见下。
 * - 重复读：引擎无缓存，每次调用重新读盘（`ModResourceStore.kt:101`、`:146-155`）；台架同一实例
 *   同一根 ⇒ 热改后立即可见（`TestResourceFiles.kt` KDoc 末句）；一致 = 是。
 * - 大小读数：引擎 `size` = 盘上字节数，空文件 0L，恰达上限仍接受（`ModResourceStore.kt:74-79`）；
 *   台架同一 `size()` 实现、默认上限同档；一致 = 是。
 * - open 副本：引擎每次 `readBytes` 新副本，改返回值不影响盘上（`ModResourceStore.kt:113-121`）；
 *   台架同一实现；一致 = 是。
 * - WARN 产生时机：引擎只有读入口（`keys/exists/size/open`）触发 scan 才 WARN；台架投放走静默
 *   校验口 ⇒ 投放本身零 WARN，读一次产一条（`TestResourceFiles.kt:16-19`、`TestWorldState.kt` 的
 *   `resourceValidator`）；一致 = 是（本用例锁的正是这条接线）。
 * - close 语义：引擎侧无对应（`mod-testing` 专属）；台架 `closeResources` 删根 + 丢缓存 + 清 WARN，
 *   重开按新根重建（`TestWorldState.kt` 的 `closeResources`）；一致 = 是（对齐「空面必须可解释」裁决）。
 *
 * 坏 IO 行说明（TODO(verify)）：台架要复现引擎的坏目录 WARN，得先造一个 `listFiles()==null` 的目录
 * （无读权限或文件占位），Windows 上 `setReadable(false)` 对目录常 no-op，用例会平台相关地假绿/假红，
 * 故不硬造；该路径已由 `ModResourceStoreTest` 直接对引擎断言。
 *
 * 大小写说明（TODO(verify)）：`ModResourceApi` 契约是「按磁盘原名逐字匹配、`keys()` 原样报告磁盘大小写」，
 * 但**大小写敏感本身属于宿主文件系统属性**（Android/Linux 敏感、Windows 开发机不敏感），本机
 * HOMEJIA-MAYBE 是 Windows：`a/` 与 `A/` 会落到同一目录，故用例只锁「文件名原名保留」与两面同值，
 * 「同名不同大小写可共存」留真机 / Linux CI 验。
 *
 * 不覆盖：`ModContextImpl.resources` 的装配（`ModRuntimeHost.kt:225-264` 注入点，属宿主装配面，
 * 本 lane 按 task-87 口径直接对拍真 store）、Android 侧文件系统大小写/编码差异（以 `keys()` 返回为准）。
 */
class ModResourceRecordingParityTest {

    private lateinit var prodRoot: File
    private lateinit var prodWarnings: MutableList<String>
    private lateinit var production: ModResourceStore
    private lateinit var world: TestWorld

    @BeforeTest
    fun setUp() {
        prodRoot = Files.createTempDirectory("mod-res-parity-prod").toFile()
        prodWarnings = mutableListOf()
        production = ModResourceStore(prodRoot, { message -> prodWarnings += message })
        world = TestWorld.ofManifests(ModManifest(packageId = PACKAGE_ID))
    }

    @AfterTest
    fun tearDown() {
        world.close()
        prodRoot.deleteRecursively()
    }

    @Test
    fun `非法名在四个读入口与投放口两面同抛IAE且文案逐字一致`() {
        val prodApi = production.apiFor(PACKAGE_ID)
        val benchApi = world.newContext(PACKAGE_ID).resources
        for (name in BAD_NAMES) {
            val prodMessages = listOf(
                failureMessage { prodApi.exists(name) },
                failureMessage { prodApi.size(name) },
                failureMessage { prodApi.open(name) },
            )
            val benchMessages = listOf(
                failureMessage { benchApi.exists(name) },
                failureMessage { benchApi.size(name) },
                failureMessage { benchApi.open(name) },
            )
            assertEquals(prodMessages, benchMessages, "非法名 [$name] 的读入口必须同文案")
            assertEquals(failureMessage { world.resource(PACKAGE_ID, name, BYTES) }, prodMessages.first(), "投放口同文案")
        }
        assertEquals(
            failureMessage { production.apiFor(BAD_PACKAGE_ID) },
            failureMessage { world.resource(BAD_PACKAGE_ID, "a.txt", BYTES) },
            "非法 packageId 两面必须同文案",
        )
        assertInstallSilent()
    }

    @Test
    fun `合法名嵌套大小写与空文件两面读数一致`() {
        INSTALLED.forEach { (name, bytes) -> installBoth(name, bytes) }
        assertInstallSilent()
        assertReadParity(INSTALLED.keys.toList() + MISSING_NAME)
        assertEquals(INSTALLED.keys.sorted(), world.newContext(PACKAGE_ID).resources.keys(), "keys 按名升序")
        assertEquals(0L, world.newContext(PACKAGE_ID).resources.size(EMPTY_NAME), "空文件大小读数为 0")
    }

    @Test
    fun `空资源面与缺包目录两面同为无告警空面`() {
        assertReadParity(listOf("x.png", "a/b.png"))
        assertReadParity(listOf("x.png"), packageId = NEVER_INSTALLED)
        assertEquals(emptyList<String>(), world.newContext(PACKAGE_ID).resources.keys())
        assertEquals(emptyList<String>(), world.newContext(NEVER_INSTALLED).resources.keys())
        assertInstallSilent()
    }

    @Test
    fun `缺文件两面都返false与null且不抛不告警`() {
        installBoth(SMALL_NAME, SMALL_BYTES)
        assertInstallSilent()
        val prodApi = production.apiFor(PACKAGE_ID)
        val benchApi = world.newContext(PACKAGE_ID).resources
        assertFalse(prodApi.exists(MISSING_NAME))
        assertFalse(benchApi.exists(MISSING_NAME))
        assertNull(prodApi.size(MISSING_NAME))
        assertNull(benchApi.size(MISSING_NAME))
        assertNull(prodApi.open(MISSING_NAME))
        assertNull(benchApi.open(MISSING_NAME))
        assertEquals(prodWarnings, world.resourceWarnings, "缺文件不得产 WARN")
        assertTrue(world.resourceWarnings.isEmpty(), "缺文件不是异常：${world.resourceWarnings}")
    }

    @Test
    fun `单文件超限两面跳过同一文件并逐字同WARN`() {
        installBoth(SMALL_NAME, SMALL_BYTES)
        installBoth(OVERSIZED_NAME, ByteArray(ModResourceStore.MAX_RESOURCE_FILE_BYTES.toInt() + 1))
        assertInstallSilent()
        assertReadParity(listOf(OVERSIZED_NAME, SMALL_NAME, MISSING_NAME))
        val expected = "[$PACKAGE_ID][WARN] mod.resources 资源文件超过单文件上限，跳过：$OVERSIZED_NAME" +
            "（${ModResourceStore.MAX_RESOURCE_FILE_BYTES + 1} > ${ModResourceStore.MAX_RESOURCE_FILE_BYTES}）"
        assertEquals(listOf(expected), world.resourceWarnings.distinct(), "超限文件 WARN 逐字")
        val benchApi = world.newContext(PACKAGE_ID).resources
        assertFalse(benchApi.keys().contains(OVERSIZED_NAME), "超限文件不得进 keys")
        assertNull(benchApi.open(OVERSIZED_NAME), "超限文件按不存在处理")
    }

    @Test
    fun `整包超总量两面同样整包禁用并逐字同WARN`() {
        TOTAL_CAP_NAMES.forEach { installBoth(it, ByteArray(TOTAL_CAP_CHUNK.toInt())) }
        assertInstallSilent()
        assertReadParity(TOTAL_CAP_NAMES + MISSING_NAME)
        val expected = "[$PACKAGE_ID][WARN] mod.resources 包资源总量超过上限，整包资源面禁用：$PACKAGE_ID" +
            "（累计 $TOTAL_CAP_TOTAL > ${ModResourceStore.MAX_RESOURCE_TOTAL_BYTES}，" +
            "已计入 ${TOTAL_CAP_NAMES.size - 1} 个文件）"
        assertEquals(listOf(expected), world.resourceWarnings.distinct(), "总量超限 WARN 逐字")
        val benchApi = world.newContext(PACKAGE_ID).resources
        assertEquals(emptyList<String>(), benchApi.keys(), "整包禁用后 keys 为空")
        assertNull(benchApi.open(TOTAL_CAP_NAMES.first()), "整包禁用后 open 为 null")
    }

    @Test
    fun `重复读热改与open副本两面一致`() {
        installBoth(HOT_NAME, V1_BYTES)
        assertInstallSilent()
        assertReadParity(listOf(HOT_NAME))
        installBoth(HOT_NAME, V2_BYTES)
        assertReadParity(listOf(HOT_NAME))
        val prodCopy = production.apiFor(PACKAGE_ID).open(HOT_NAME)
        val benchCopy = world.newContext(PACKAGE_ID).resources.open(HOT_NAME)
        assertContentEquals(prodCopy, benchCopy, "两面读到的字节必须逐字一致")
        prodCopy?.set(0, MUTATED_BYTE)
        benchCopy?.set(0, MUTATED_BYTE)
        assertReadParity(listOf(HOT_NAME))
        assertContentEquals(V2_BYTES, world.newContext(PACKAGE_ID).resources.open(HOT_NAME), "open 返回副本")
    }

    @Test
    fun `投放资源本身两面都不产WARN只有读才产`() {
        installBoth(OVERSIZED_NAME, ByteArray(ModResourceStore.MAX_RESOURCE_FILE_BYTES.toInt() + 1))
        installBoth(SMALL_NAME, SMALL_BYTES)
        assertInstallSilent()
        assertEquals(
            production.apiFor(PACKAGE_ID).keys(),
            world.newContext(PACKAGE_ID).resources.keys(),
            "读一次两面结果一致",
        )
        assertEquals(prodWarnings, world.resourceWarnings, "读一次后两面各产同一条 WARN")
        assertEquals(1, world.resourceWarnings.size, "scan 一次产一条 WARN：${world.resourceWarnings}")
    }

    @Test
    fun `close后重用资源面按新根重建且与新会话同口径`() {
        installBoth("old.txt", V1_BYTES)
        assertEquals(listOf("old.txt"), world.newContext(PACKAGE_ID).resources.keys())
        world.close()
        assertTrue(world.resourceWarnings.isEmpty(), "close 必须清掉上一会话的 WARN")
        assertTrue(prodWarnings.isEmpty(), "生产侧未读过资源，不应有 WARN")
        world.resource(PACKAGE_ID, "new.txt", V2_BYTES)
        val benchApi = world.newContext(PACKAGE_ID).resources
        assertEquals(listOf("new.txt"), benchApi.keys(), "close 后必须读新根，不得读已删旧根")
        assertFalse(benchApi.exists("old.txt"), "已删旧根的资源不得再可见")
        val freshRoot = Files.createTempDirectory("mod-res-parity-fresh").toFile()
        try {
            installProduction(freshRoot, PACKAGE_ID, "new.txt", V2_BYTES)
            val freshWarnings = mutableListOf<String>()
            val freshApi = ModResourceStore(freshRoot, { message -> freshWarnings += message }).apiFor(PACKAGE_ID)
            assertEquals(freshApi.keys(), benchApi.keys(), "台架 close 后重开应与全新会话同口径")
            assertEquals(freshWarnings, world.resourceWarnings, "WARN 汇同样按新会话重算")
        } finally {
            freshRoot.deleteRecursively()
        }
    }

    @Test
    fun `大小读数在空文件与单文件上限边界两面一致`() {
        installBoth(EMPTY_NAME, ByteArray(0))
        installBoth(EXACT_CAP_NAME, ByteArray(ModResourceStore.MAX_RESOURCE_FILE_BYTES.toInt()))
        assertInstallSilent()
        assertReadParity(listOf(EMPTY_NAME, EXACT_CAP_NAME, OVER_CAP_NAME))
        val benchApi = world.newContext(PACKAGE_ID).resources
        assertEquals(0L, benchApi.size(EMPTY_NAME))
        assertEquals(
            ModResourceStore.MAX_RESOURCE_FILE_BYTES,
            benchApi.size(EXACT_CAP_NAME),
            "恰好达上限必须接受（判据是 > 而非 >=）",
        )
    }

    @Test
    fun `跨包隔离同名资源各读各的且越包名两面同拒`() {
        installBoth("same.txt", V1_BYTES, packageId = PKG_A)
        installBoth("same.txt", V2_BYTES, packageId = PKG_B)
        assertInstallSilent()
        assertReadParity(listOf("same.txt", "../$PKG_B.assets/same.txt"), packageId = PKG_A)
        assertContentEquals(V1_BYTES, production.apiFor(PKG_A).open("same.txt"))
        assertContentEquals(V2_BYTES, production.apiFor(PKG_B).open("same.txt"))
        assertContentEquals(V1_BYTES, world.newContext(PKG_A).resources.open("same.txt"))
        assertContentEquals(V2_BYTES, world.newContext(PKG_B).resources.open("same.txt"))
    }

    /** 投放同一份资源到两面：生产侧直接按引擎布局落盘（= 宿主安装链口径，不经 API、不产 WARN）。 */
    private fun installBoth(name: String, bytes: ByteArray, packageId: String = PACKAGE_ID) {
        installProduction(prodRoot, packageId, name, bytes)
        world.resource(packageId, name, bytes)
    }

    private fun installProduction(root: File, packageId: String, name: String, bytes: ByteArray) {
        val target = File(File(root, packageId + ModResourceStore.ASSET_DIR_SUFFIX), name)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }

    /** 同一读调用序列跑两面：逐行比对结果，并比对 WARN 整列表（含重复次数 —— scan 每次重打）。 */
    private fun assertReadParity(names: List<String>, packageId: String = PACKAGE_ID) {
        val prodLines = observe(production.apiFor(packageId), names)
        val benchLines = observe(world.newContext(packageId).resources, names)
        assertEquals(prodLines, benchLines, "同一读调用序列两面必须逐行一致")
        assertEquals(prodWarnings, world.resourceWarnings, "WARN 整列表必须逐字一致（含条目与重复次数）")
    }

    private fun observe(api: ModResourceApi, names: List<String>): List<String> {
        val lines = mutableListOf("keys=${api.keys()}")
        for (name in names) {
            lines += "exists[$name]=${render { api.exists(name) }}"
            lines += "size[$name]=${render { api.size(name) }}"
            lines += "open[$name]=${openDigest(api, name)}"
        }
        return lines
    }

    /** 只摘 `size:contentHashCode`，避免把 4 MiB 内容整串拼进断言消息。 */
    private fun openDigest(api: ModResourceApi, name: String): String =
        render { api.open(name)?.let { bytes -> "${bytes.size}:${bytes.contentHashCode()}" } }

    private inline fun render(block: () -> Any?): String = runCatching(block).fold(
        onSuccess = { value -> "ok:$value" },
        onFailure = { failure -> "throw:${failure::class.simpleName}:${failure.message}" },
    )

    private fun failureMessage(block: () -> Unit): String? =
        assertFailsWith<IllegalArgumentException> { block() }.message

    /** 投放（含超限文件）本身零 WARN；这是台架 WARN 纯净性的锁，破了先红。 */
    private fun assertInstallSilent() {
        assertTrue(prodWarnings.isEmpty(), "生产投放不经 API，不得产 WARN：$prodWarnings")
        assertTrue(world.resourceWarnings.isEmpty(), "台架投放不得产 WARN：${world.resourceWarnings}")
    }

    private companion object {

        /** 与台架 `TestWorld.DEFAULT_PACKAGE_ID` 解耦的独立包名（本文件直接装 store）。 */
        const val PACKAGE_ID: String = "parity.resource"
        const val PKG_A: String = "parity.resource.a"
        const val PKG_B: String = "parity.resource.b"
        const val BAD_PACKAGE_ID: String = "bad pkg"
        const val NEVER_INSTALLED: String = "never.installed"

        const val SMALL_NAME: String = "small.txt"
        const val OVERSIZED_NAME: String = "big.bin"
        const val EMPTY_NAME: String = "empty.bin"
        const val EXACT_CAP_NAME: String = "exact.bin"
        const val OVER_CAP_NAME: String = "over.bin"
        const val MISSING_NAME: String = "missing.bin"
        const val HOT_NAME: String = "v.txt"

        val BYTES: ByteArray = byteArrayOf(2, 1)
        val SMALL_BYTES: ByteArray = byteArrayOf(2, 1)
        val V1_BYTES: ByteArray = byteArrayOf(2, 1)
        val V2_BYTES: ByteArray = byteArrayOf(2, 1, 2, 1)

        const val MUTATED_BYTE: Byte = 9

        /** 覆盖「非法名」全部拒绝面：绝对路径 / 尾斜杠 / 反斜杠 / 空段 / 点段 / `..` 段 / 盘符 / 控制字符 / 空名 / 冒号。 */
        val BAD_NAMES: List<String> = listOf(
            "/abs.txt",
            "a/",
            "a//b",
            "a/./b",
            "a/../b",
            "a\\b.txt",
            "C:/x.txt",
            "a\u0001b.txt",
            "",
            "a:b.txt",
        )

        /** 合法名矩阵：嵌套 / 文件名大小写保留 / 空格与日文 / 空文件。 */
        val INSTALLED: Map<String, ByteArray> = linkedMapOf(
            "b.txt" to byteArrayOf(2, 1),
            "a/deep/c.bin" to byteArrayOf(2, 1, 2, 1),
            "casing/UPPER.TXT" to byteArrayOf(2, 1, 2, 1, 2),
            "空 格/日文 名.txt" to byteArrayOf(2, 1, 2, 1, 2, 1),
            EMPTY_NAME to ByteArray(0),
        )

        /** 5 个等大文件：累计 18_350_080 > 16_777_216（等大 ⇒ WARN 文案的累计/已计入数与枚举序无关）。 */
        val TOTAL_CAP_NAMES: List<String> = listOf("f0.bin", "f1.bin", "f2.bin", "f3.bin", "f4.bin")
        const val TOTAL_CAP_CHUNK: Long = 3_670_016L
        const val TOTAL_CAP_TOTAL: Long = 18_350_080L
    }
}
