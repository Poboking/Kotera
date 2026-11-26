package com.eratw.port.modgen

import com.eratw.port.modapi.ModApi
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
 * 包级元数据的可选 min-api 键（pack-mod 写侧）。
 *
 * ## 线格式不变量（本文件的头号断言）
 *
 * 作者**没声明** min-api 时，`eratw-pack.json` 必须与改造前逐字节相同——空串是 CF/CJ 波冻结
 * 夹具（`AuthorClosedLoopSmokeTest` 的 Base64、app 侧 `ModImportAuthorPackEndToEndTest` 的字面
 * 量）锁住的东西。因此 `--min-api` 省缺 / `0` / 没包元数据三种情况一条键都不许多写，摘要多出
 * 的 min-api 行也只在该键**有落盘位置**时才打。
 *
 * ## 写侧只搬运、不判闸
 *
 * `--min-api N` 原样写进元数据；N > 当前 `ModApi.VERSION` 只 WARN 照打（给未来引擎发版用），
 * 真正拒载在装配期（`ModChainAssembly`）。非 0 非整数 / 负数 / 给两次是用法错（退出码 2）。
 */
class PackModMinApiTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `声明minApi_写可选键且摘要行点名`() {
        val module = packableModule(tmp)
        val zip = outZip(tmp)

        val run = runPack(module.toString(), "--out", zip.toString(), "--min-api", "12")

        assertEquals(0, run.exit, "声明合法 min-api 必须放行：out=${run.out} err=${run.err}")
        assertContentEquals(
            "{\"schema\":1,\"packageId\":\"$TEST_PACKAGE_ID\",\"version\":\"0.1\",\"minApi\":12}"
                .toByteArray(Charsets.UTF_8),
            entryBytes(zip, PACK_META_ENTRY_NAME),
            "minApi 追加在 version 之后（固定键序的最后一个键）",
        )
        // 既有摘要行逐字不变（既有断言依赖它），min-api 另起一行。
        assertTrue(
            run.out.contains("pack-mod: 元数据 $PACK_META_ENTRY_NAME（packageId=$TEST_PACKAGE_ID / version=0.1）"),
            run.out,
        )
        assertTrue(run.out.contains("pack-mod: min-api 12"), run.out)
        assertTrue(run.out.contains("装配期 modApi 低于该值的引擎整链拒载"), run.out)
    }

    @Test
    fun `未声明minApi_元数据字节与改造前逐字相同`() {
        val module = packableModule(tmp)
        val zip = outZip(tmp)

        val run = packOk(module, zip)

        assertContentEquals(
            "{\"schema\":1,\"packageId\":\"$TEST_PACKAGE_ID\",\"version\":\"0.1\"}".toByteArray(Charsets.UTF_8),
            entryBytes(zip, PACK_META_ENTRY_NAME),
            "线格式不变量：不声明 min-api 时元数据必须与冻结夹具逐字节相同",
        )
        assertFalse(run.out.contains("pack-mod: min-api"), "没声明就不该多打 min-api 摘要行：${run.out}")
        assertFalse(PackModMeta.json(TEST_PACKAGE_ID, "0.1").toString(Charsets.UTF_8).contains("minApi"))
    }

    @Test
    fun `minApi为0_等同未声明且两次打包字节相同`() {
        val module = packableModule(tmp)
        val omitted = outZip(tmp, "out/omitted.zip")
        val explicitZero = outZip(tmp, "out/zero.zip")

        val runOmitted = packOk(module, omitted)
        val runZero = runPack(module.toString(), "--out", explicitZero.toString(), "--min-api", "0")
        assertEquals(0, runZero.exit, "--min-api 0 = 不设闸，不是用法错：err=${runZero.err}")

        assertEquals(sha256Of(runOmitted), sha256Of(runZero), "0 与省缺必须产出完全相同的包字节")
        assertContentEquals(
            entryBytes(omitted, PACK_META_ENTRY_NAME),
            entryBytes(explicitZero, PACK_META_ENTRY_NAME),
            "0 不写 minApi 键",
        )
        assertFalse(runZero.out.contains("pack-mod: min-api"), "0 不设闸 ⇒ 不打 min-api 摘要行：${runZero.out}")
    }

    @Test
    fun `minApi高于当前引擎_照写并WARN不拒`() {
        val module = packableModule(tmp)
        val zip = outZip(tmp)
        val future = ModApi.VERSION + 1

        val run = runPack(module.toString(), "--out", zip.toString(), "--min-api", future.toString())

        assertEquals(0, run.exit, "写侧不判闸，高于当前引擎只 WARN：err=${run.err}")
        assertContentEquals(
            "{\"schema\":1,\"packageId\":\"$TEST_PACKAGE_ID\",\"version\":\"0.1\",\"minApi\":$future}"
                .toByteArray(Charsets.UTF_8),
            entryBytes(zip, PACK_META_ENTRY_NAME),
        )
        assertTrue(run.out.contains("高于当前引擎的 ModApi.VERSION=${ModApi.VERSION}"), run.out)
        assertTrue(run.out.contains("本机装配期会整链拒载"), run.out)
    }

    @Test
    fun `minApi非法_用法错exit2且不产出包`() {
        val module = packableModule(tmp)
        val zip = outZip(tmp)

        val cases = listOf(
            listOf("--min-api"),
            listOf("--min-api", "abc"),
            listOf("--min-api", "-1"),
            listOf("--min-api", "3", "--min-api", "4"),
        )
        for (extra in cases) {
            val run = runPack(module.toString(), "--out", zip.toString(), *extra.toTypedArray())
            assertEquals(2, run.exit, "用法错必须是 exit 2：args=$extra out=${run.out} err=${run.err}")
            assertTrue(run.err.contains("用法: modgen pack-mod"), "用法错要打用法：err=${run.err}")
            assertFalse(Files.exists(zip), "用法错不产出包：args=$extra")
        }
        assertTrue(
            runPack(module.toString(), "--out", zip.toString(), "--min-api", "abc").err
                .contains("--min-api <N> 取值必须是 >= 0 的整数"),
        )
        assertTrue(
            runPack(module.toString(), "--out", zip.toString(), "--min-api", "3", "--min-api", "4").err
                .contains("--min-api 只能给一次"),
        )
    }

    @Test
    fun `没有包元数据时声明minApi_打WARN且不写键`() {
        // 没有任何 @ModCarrier 的裸模块：打包器自述身份抽不到 ⇒ 不写元数据条目，min-api 无落盘位置。
        val module = Files.createDirectories(tmp.resolve("bare-mod"))
        writeModuleFile(module, "main.ERB", "@SYSTEM\nPRINTL hello\n")
        val zip = outZip(tmp)

        val run = runPack(module.toString(), "--out", zip.toString(), "--min-api", "5")

        assertEquals(0, run.exit, "缺元数据只是 WARN，不拒打包：out=${run.out} err=${run.err}")
        assertTrue(run.out.contains("声明了 --min-api 但本次不写包元数据"), "必须 WARN 说 min-api 被忽略：${run.out}")
        assertFalse(PACK_META_ENTRY_NAME in entryNames(zip), "没有自述身份就不写元数据：${entryNames(zip)}")
        assertEquals(listOf("main.ERB"), entryNames(zip), "裸模块只打源文件")
    }

    @Test
    fun `JSON追加键_只在正数时追加且缺省参数字节不变`() {
        val legacy = "{\"schema\":1,\"packageId\":\"$TEST_PACKAGE_ID\",\"version\":\"1.0\"}"
        assertEquals(legacy, PackModMeta.json(TEST_PACKAGE_ID, "1.0").toString(Charsets.UTF_8))
        assertEquals(legacy, PackModMeta.json(TEST_PACKAGE_ID, "1.0", 0).toString(Charsets.UTF_8))
        assertEquals(
            "{\"schema\":1,\"packageId\":\"$TEST_PACKAGE_ID\",\"version\":\"1.0\",\"minApi\":3}",
            PackModMeta.json(TEST_PACKAGE_ID, "1.0", 3).toString(Charsets.UTF_8),
        )
        val negative = assertFailsWith<IllegalArgumentException> {
            PackModMeta.json(TEST_PACKAGE_ID, "1.0", -1)
        }
        assertTrue(
            negative.message.orEmpty().contains("min-api 不能为负"),
            "负值只能由调用方 bug 传进来，必须在编码器口就拒（不写出非法 JSON）：${negative.message}",
        )
    }
}
