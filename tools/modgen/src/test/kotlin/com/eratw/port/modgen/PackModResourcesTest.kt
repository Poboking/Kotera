package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `pack-mod --resources <dir>`（ADR-014 切片 S3）：两棵旁挂资源树进包 + `resources` 元数据字段。
 *
 * 断言的都是真实读回的 zip 条目/元数据字节与退出码；限额本体从 [PackModResources] 常量取，
 * 不复制字面量（阈值漂移由该文件自己的常量为单源）。
 */
class PackModResourcesTest {

    @TempDir
    lateinit var tmp: Path

    /** 在模块根下写一棵旁挂树的文件（内容大小可控，用于限额用例）。 */
    private fun writeTreeFile(module: Path, treeRoot: String, dir: String, relative: String, bytes: Int = 8) {
        val target = module.resolve("$treeRoot/$MOD_SEGMENT/$dir/$relative")
        Files.createDirectories(target.parent)
        Files.write(target, ByteArray(bytes) { 'a'.code.toByte() })
    }

    private fun packWith(module: Path, zip: Path, vararg extra: String): PackRun =
        runPack(module.toString(), "--out", zip.toString(), *extra)

    @Test
    fun `两棵树都打进包_字段紧跟minApi`() {
        val module = packableModule(tmp)
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "a.png")
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "sub/b.txt")
        writeTreeFile(module, PackModResources.SOUND_ROOT, "demo", "se.mp3")
        val zip = outZip(tmp)

        val run = packWith(module, zip, "--min-api", "3", "--resources", "demo")

        assertEquals(0, run.exit, "out=${run.out} err=${run.err}")
        val names = entryNames(zip)
        assertTrue("resources/mod/demo/a.png" in names, "names=$names")
        assertTrue("resources/mod/demo/sub/b.txt" in names, "names=$names")
        assertTrue("sound/mod/demo/se.mp3" in names, "names=$names")
        assertEquals(names.sorted(), names, "条目必须字典序：$names")
        assertContentEquals(
            "{\"schema\":1,\"packageId\":\"$TEST_PACKAGE_ID\",\"version\":\"0.1\",\"minApi\":3,\"resources\":\"demo\"}"
                .toByteArray(Charsets.UTF_8),
            entryBytes(zip, PACK_META_ENTRY_NAME),
            "resources 必须追加在 minApi 之后（既有键序不变）",
        )
        assertTrue(run.out.contains("pack-mod: 资源目录 demo（resources/mod/demo/ + sound/mod/demo/"), run.out)
        // 源 1 + 既有资源 1 + 旁挂 3 = 5；旁挂条目计入「资源」账本与导入端预算。
        assertTrue(run.out.contains("pack-mod: 条目 5（源 1 / 资源 4）"), run.out)
    }

    @Test
    fun `没有minApi时字段紧跟version`() {
        val module = packableModule(tmp)
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "a.png")
        val zip = outZip(tmp)

        val run = packWith(module, zip, "--resources", "demo")

        assertEquals(0, run.exit, "out=${run.out} err=${run.err}")
        assertContentEquals(
            "{\"schema\":1,\"packageId\":\"$TEST_PACKAGE_ID\",\"version\":\"0.1\",\"resources\":\"demo\"}"
                .toByteArray(Charsets.UTF_8),
            entryBytes(zip, PACK_META_ENTRY_NAME),
        )
        assertFalse(entryBytes(zip, PACK_META_ENTRY_NAME).toString(Charsets.UTF_8).contains("minApi"))
    }

    @Test
    fun `两棵树都不存在_包字节与不加参数逐字相同`() {
        val module = packableModule(tmp)
        val without = outZip(tmp, "out/without.zip")
        val with = outZip(tmp, "out/with.zip")

        val baseline = packOk(module, without)
        val run = packWith(module, with, "--resources", "demo")

        assertEquals(0, run.exit, "两树都不存在不是错误：out=${run.out} err=${run.err}")
        assertEquals(sha256Of(baseline), sha256Of(run), "两树都不存在 ⇒ 字节与未声明完全相同（线格式不变量）")
        assertFalse(entryBytes(with, PACK_META_ENTRY_NAME).toString(Charsets.UTF_8).contains("resources"))
        assertTrue(run.out.contains("按未声明处理"), run.out)
    }

    @Test
    fun `非法目录名_拒绝exit1并点名判据`() {
        val module = packableModule(tmp)
        val cases = listOf(
            "a/b", "a\\b", "..", ".", "a:b",
            "x".repeat(MAX_MEDIA_DIR_CHARS + 1), "", "\u0001x",
        )
        cases.forEachIndexed { index, dir ->
            val zip = outZip(tmp, "out/bad-$index.zip")
            val run = packWith(module, zip, "--resources", dir)
            assertEquals(1, run.exit, "dir=<$dir> 必须走「已拒绝」档：out=${run.out} err=${run.err}")
            assertTrue(run.err.contains("拒绝打包：--resources 取值"), "dir=<$dir> err=${run.err}")
            assertFalse(Files.exists(zip), "拒绝时不产出包：dir=<$dir>")
        }
    }

    @Test
    fun `扩展名白名单_资源树禁csv音频禁flac`() {
        val csvModule = packableModule(tmp.resolve("csv"))
        writeTreeFile(csvModule, PackModResources.RESOURCES_ROOT, "demo", "data.csv")
        val csvZip = outZip(tmp, "out/csv.zip")
        val csvRun = packWith(csvModule, csvZip, "--resources", "demo")
        assertEquals(1, csvRun.exit, "csv 必须拒绝（ResourceCsvHost 会误扫名表）：out=${csvRun.out} err=${csvRun.err}")
        assertTrue(csvRun.err.contains("resources/mod/demo/data.csv"), "必须点名文件：${csvRun.err}")
        assertTrue(csvRun.err.contains("不在白名单"), "必须点名判据：${csvRun.err}")

        val audioModule = packableModule(tmp.resolve("audio"))
        writeTreeFile(audioModule, PackModResources.SOUND_ROOT, "demo", "x.flac")
        val audioZip = outZip(tmp, "out/audio.zip")
        val audioRun = packWith(audioModule, audioZip, "--resources", "demo")
        assertEquals(1, audioRun.exit, "flac 不在音频白名单：out=${audioRun.out} err=${audioRun.err}")
        assertTrue(audioRun.err.contains("sound/mod/demo/x.flac"), "必须点名文件：${audioRun.err}")
    }

    @Test
    fun `单文件超限_图文与音频分别拒绝`() {
        val imageModule = packableModule(tmp.resolve("image"))
        val imageBytes = (ModResourceLimits.MAX_RESOURCE_FILE_BYTES + 1).toInt()
        writeTreeFile(imageModule, PackModResources.RESOURCES_ROOT, "demo", "big.png", imageBytes)
        val imageZip = outZip(tmp, "out/image.zip")
        val imageRun = packWith(imageModule, imageZip, "--resources", "demo")
        assertEquals(1, imageRun.exit, "out=${imageRun.out} err=${imageRun.err}")
        assertTrue(imageRun.err.contains("上限被超过"), "err=${imageRun.err}")

        val audioModule = packableModule(tmp.resolve("audio"))
        val audioBytes = (PackModResources.MAX_AUDIO_FILE_BYTES + 1).toInt()
        writeTreeFile(audioModule, PackModResources.SOUND_ROOT, "demo", "big.mp3", audioBytes)
        val audioZip = outZip(tmp, "out/audio.zip")
        val audioRun = packWith(audioModule, audioZip, "--resources", "demo")
        assertEquals(1, audioRun.exit, "out=${audioRun.out} err=${audioRun.err}")
        assertTrue(audioRun.err.contains("上限被超过"), "err=${audioRun.err}")
    }

    @Test
    fun `单棵树总量超限_拒绝`() {
        val module = packableModule(tmp)
        // 每个文件恰好顶到单文件上限（4 MiB），5 个 ⇒ 20 MiB > 单包 16 MiB。
        val chunk = ModResourceLimits.MAX_RESOURCE_FILE_BYTES.toInt()
        repeat(5) { index ->
            writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "chunk-$index.png", chunk)
        }
        val zip = outZip(tmp)
        val run = packWith(module, zip, "--resources", "demo")
        assertEquals(1, run.exit, "out=${run.out} err=${run.err}")
        assertTrue(run.err.contains("该树合计"), "必须点名总量判据：${run.err}")
        assertFalse(Files.exists(zip))
    }

    @Test
    fun `确定性_两次打包逐字节相同且条目字典序`() {
        val module = packableModule(tmp)
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "zeta.png")
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "alpha.txt")
        writeTreeFile(module, PackModResources.SOUND_ROOT, "demo", "se.ogg")
        val first = outZip(tmp, "out/first.zip")
        val second = outZip(tmp, "out/second.zip")

        val runFirst = packWith(module, first, "--resources", "demo")
        val runSecond = packWith(module, second, "--resources", "demo")

        assertEquals(0, runFirst.exit, "out=${runFirst.out} err=${runFirst.err}")
        assertEquals(0, runSecond.exit, "out=${runSecond.out} err=${runSecond.err}")
        assertContentEquals(Files.readAllBytes(first), Files.readAllBytes(second))
        assertEquals(sha256Of(runFirst), sha256Of(runSecond))
        val names = entryNames(first)
        assertEquals(names.sorted(), names, "entries must stay lexical: $names")
    }

    @Test
    fun `目录名收紧到包身份ASCII_非ASCII空格与前导符号拒绝`() {
        val module = packableModule(tmp)
        // ERB 源码是 Shift-JIS：中文/emoji/空格目录名作者在 GCREATEFROMFILE 等字面量里写不出来 ⇒
        // 放行只会给出用不了的目录名，故按包身份字符集收紧（组长裁定，2026-11-18）。
        val bad = listOf("我的图", "a b", "-lead", ".lead", "lead\t")
        bad.forEachIndexed { index, dir ->
            val zip = outZip(tmp, "out/ascii-bad-$index.zip")
            val run = packWith(module, zip, "--resources", dir)
            assertEquals(1, run.exit, "dir=<$dir> 必须拒绝：out=${run.out} err=${run.err}")
            assertTrue(run.err.contains("拒绝打包：--resources 取值"), "dir=<$dir> err=${run.err}")
            assertFalse(Files.exists(zip), "拒绝时不产出包：dir=<$dir>")
        }

        val legalModule = packableModule(tmp.resolve("legal"))
        writeTreeFile(legalModule, PackModResources.RESOURCES_ROOT, "a.b-c_1", "a.txt")
        val legalZip = outZip(tmp, "out/ascii-legal.zip")
        val legalRun = packWith(legalModule, legalZip, "--resources", "a.b-c_1")
        assertEquals(0, legalRun.exit, "a.b-c_1 合法：out=${legalRun.out} err=${legalRun.err}")
        assertTrue("resources/mod/a.b-c_1/a.txt" in entryNames(legalZip), "names=${entryNames(legalZip)}")
        assertTrue(
            entryBytes(legalZip, PACK_META_ENTRY_NAME).toString(Charsets.UTF_8).contains("\"resources\":\"a.b-c_1\""),
            "合法目录名原样写进元数据",
        )
    }

    @Test
    fun `resources给两次_用法错exit2`() {
        val module = packableModule(tmp)
        val zip = outZip(tmp)
        val run = packWith(module, zip, "--resources", "a", "--resources", "b")
        assertEquals(2, run.exit, "重复选项是用法错：out=${run.out} err=${run.err}")
        assertTrue(run.err.contains("--resources 只能给一次"), "err=${run.err}")
        assertFalse(Files.exists(zip))
    }

    @Test
    fun `资源树存在但没有包元数据_拒绝exit1`() {
        val bare = Files.createDirectories(tmp.resolve("bare-mod"))
        writeModuleFile(bare, "main.ERB", "@SYSTEM\nPRINTL hello\n")
        writeTreeFile(bare, PackModResources.RESOURCES_ROOT, "demo", "a.png")
        val zip = outZip(tmp)
        val run = packWith(bare, zip, "--resources", "demo")
        assertEquals(1, run.exit, "没有 resources 字段落盘位置 ⇒ 拒绝而不是静默丢资源：out=${run.out} err=${run.err}")
        assertTrue(run.err.contains("resources 字段没有落盘位置"), "err=${run.err}")
        assertFalse(Files.exists(zip))
    }

    @Test
    fun `旁挂树文件不被重复记为构建面跳过`() {
        val module = packableModule(tmp)
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "a.png")
        writeTreeFile(module, PackModResources.SOUND_ROOT, "demo", "se.mp3")

        val plan = PackModZip.collect(module, listOf(TEST_PACKAGE_ID), emptyList(), "demo")

        assertTrue(plan.unsafe.isEmpty(), "合法树不该有 unsafe：${plan.unsafe.map { it.name }}")
        assertTrue(plan.skipped.none { it.label.startsWith("resources/") || it.label.startsWith("sound/") })
        assertEquals(3, plan.resourceCount, "既有 README + 旁挂 2 条都算资源：${plan.entries.map { it.name }}")
    }

    @Test
    fun `JSON字段_缺省字节不变且追加顺序固定`() {
        val legacy = "{\"schema\":1,\"packageId\":\"$TEST_PACKAGE_ID\",\"version\":\"1.0\"}"
        assertEquals(legacy, PackModMeta.json(TEST_PACKAGE_ID, "1.0").toString(Charsets.UTF_8))
        assertEquals(legacy, PackModMeta.json(TEST_PACKAGE_ID, "1.0", 0, null).toString(Charsets.UTF_8))
        assertEquals(
            "{\"schema\":1,\"packageId\":\"$TEST_PACKAGE_ID\",\"version\":\"1.0\",\"resources\":\"demo\"}",
            PackModMeta.json(TEST_PACKAGE_ID, "1.0", 0, "demo").toString(Charsets.UTF_8),
        )
        assertEquals(
            "{\"schema\":1,\"packageId\":\"$TEST_PACKAGE_ID\",\"version\":\"1.0\",\"minApi\":3,\"resources\":\"demo\"}",
            PackModMeta.json(TEST_PACKAGE_ID, "1.0", 3, "demo").toString(Charsets.UTF_8),
            "resources 必须在 minApi 之后",
        )
    }

    // ------------------------------------------------------------------
    // 5. 编译期 surface（ADR-013 D2 闸 2，切片 S3）：`--surface <N>` → 元数据 `compiledSurface`
    // ------------------------------------------------------------------

    @Test
    fun `不给surface_元数据与整包字节和改造前逐字节相同`() {
        val module = packableModule(tmp)
        val without = outZip(tmp, "out/no-surface.zip")
        val explicitNone = outZip(tmp, "out/explicit-none.zip")

        val baseline = packOk(module, without)
        val run = packWith(module, explicitNone)

        assertEquals(0, run.exit, "out=${run.out} err=${run.err}")
        assertEquals(sha256Of(baseline), sha256Of(run), "不给 --surface ⇒ 整包字节与今天完全相同")
        assertContentEquals(
            "{\"schema\":1,\"packageId\":\"$TEST_PACKAGE_ID\",\"version\":\"0.1\"}"
                .toByteArray(Charsets.UTF_8),
            entryBytes(explicitNone, PACK_META_ENTRY_NAME),
            "缺省元数据不许出现 compiledSurface：${entryBytes(explicitNone, PACK_META_ENTRY_NAME)}",
        )
        assertFalse(run.out.contains("compiled-surface"), "没有声明就不该有 summary 行：${run.out}")
    }

    @Test
    fun `surface写在最后_紧跟resources之后`() {
        val module = packableModule(tmp)
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "a.png")
        val zip = outZip(tmp)

        val run = packWith(module, zip, "--min-api", "3", "--resources", "demo", "--surface", "18")

        assertEquals(0, run.exit, "out=${run.out} err=${run.err}")
        assertContentEquals(
            (
                "{\"schema\":1,\"packageId\":\"$TEST_PACKAGE_ID\",\"version\":\"0.1\"," +
                    "\"minApi\":3,\"resources\":\"demo\",\"compiledSurface\":18}"
                ).toByteArray(Charsets.UTF_8),
            entryBytes(zip, PACK_META_ENTRY_NAME),
            "compiledSurface 必须追加在 resources 之后、闭合括号之前：${entryBytes(zip, PACK_META_ENTRY_NAME)}",
        )
        assertTrue(run.out.contains("pack-mod: compiled-surface 18"), "必须打声明行：${run.out}")
    }

    @Test
    fun `surface与minApi为0_只写compiledSurface`() {
        val module = packableModule(tmp)
        val zip = outZip(tmp)
        val run = packWith(module, zip, "--min-api", "0", "--surface", "0")

        assertEquals(0, run.exit, "out=${run.out} err=${run.err}")
        assertContentEquals(
            "{\"schema\":1,\"packageId\":\"$TEST_PACKAGE_ID\",\"version\":\"0.1\",\"compiledSurface\":0}"
                .toByteArray(Charsets.UTF_8),
            entryBytes(zip, PACK_META_ENTRY_NAME),
            "minApi=0 照旧省略；surface=0 是合法声明（surface 从 0 起）",
        )
    }

    @Test
    fun `非法surface取值_用法错exit2且点名判据`() {
        val module = packableModule(tmp)
        val bad = listOf("-1", "${PACK_META_MAX_COMPILED_SURFACE + 1}", "abc", "18.0", "")
        bad.forEachIndexed { index, value ->
            val zip = outZip(tmp, "out/bad-surface-$index.zip")
            val run = packWith(module, zip, "--surface", value)
            assertEquals(2, run.exit, "surface=<$value> 是用法错：out=${run.out} err=${run.err}")
            assertTrue(run.err.contains("--surface <N> 取值必须是 0.."), "err=${run.err}")
            assertFalse(Files.exists(zip), "用法错不产出包：surface=<$value>")
        }
    }

    @Test
    fun `surface给两次_用法错exit2`() {
        val module = packableModule(tmp)
        val zip = outZip(tmp)
        val run = packWith(module, zip, "--surface", "1", "--surface", "2")

        assertEquals(2, run.exit, "重复选项是用法错：out=${run.out} err=${run.err}")
        assertTrue(run.err.contains("--surface 只能给一次"), "err=${run.err}")
        assertFalse(Files.exists(zip))
    }

    @Test
    fun `没有包元数据_声明surface打WARN且照常出包`() {
        val bare = Files.createDirectories(tmp.resolve("bare-surface-mod"))
        writeModuleFile(bare, "main.ERB", "@SYSTEM\nPRINTL hello\n")
        val zip = outZip(tmp)

        val run = packWith(bare, zip, "--surface", "18")

        assertEquals(0, run.exit, "没有元数据条目不是错误：out=${run.out} err=${run.err}")
        assertTrue(run.out.contains("compiledSurface 没有落盘位置"), "必须明说被忽略：${run.out}")
        assertFalse(
            PACK_META_ENTRY_NAME in entryNames(zip),
            "没有元数据条目 ⇒ 包里不该凭空出现 eratw-pack.json：${entryNames(zip)}",
        )
    }

    @Test
    fun `元数据JSON_surface只在声明时出现且上限边界固定`() {
        val legacy = "{\"schema\":1,\"packageId\":\"$TEST_PACKAGE_ID\",\"version\":\"1.0\"}"
        assertEquals(legacy, PackModMeta.json(TEST_PACKAGE_ID, "1.0").toString(Charsets.UTF_8))
        assertEquals(
            "{\"schema\":1,\"packageId\":\"$TEST_PACKAGE_ID\",\"version\":\"1.0\",\"compiledSurface\":0}",
            PackModMeta.json(TEST_PACKAGE_ID, "1.0", 0, null, 0).toString(Charsets.UTF_8),
        )
        assertEquals(
            "{\"schema\":1,\"packageId\":\"$TEST_PACKAGE_ID\",\"version\":\"1.0\",\"minApi\":3," +
                "\"compiledSurface\":$PACK_META_MAX_COMPILED_SURFACE}",
            PackModMeta.json(TEST_PACKAGE_ID, "1.0", 3, null, PACK_META_MAX_COMPILED_SURFACE)
                .toString(Charsets.UTF_8),
        )

        assertFalse(PackModMeta.compiledSurfaceAccepted(-1))
        assertTrue(PackModMeta.compiledSurfaceAccepted(0))
        assertTrue(PackModMeta.compiledSurfaceAccepted(PACK_META_MAX_COMPILED_SURFACE))
        assertFalse(PackModMeta.compiledSurfaceAccepted(PACK_META_MAX_COMPILED_SURFACE + 1))
        assertFailsWith<IllegalArgumentException>("超上限不许静默写出坏值") {
            PackModMeta.json(TEST_PACKAGE_ID, "1.0", 0, null, -1)
        }
    }

    /** 两棵树共同的二级前缀（与 [PackModResources.MOD_SEGMENT] 同值；用例里只为可读）。 */
    private companion object {
        const val MOD_SEGMENT = "mod"
    }
}
