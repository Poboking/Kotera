package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `pack-mod --code` 的代码产物面：写不写条目、字节是否原样、内容校验的每条拒绝判据。
 *
 * 内层 `.mod` 包用 [ZipOutputStream] 现造（`classes.dex` + `manifest.json`），不引新依赖；
 * 外层一律走真实 `pack-mod` CLI（[runPack]），断言的是 zip 里真实读到的东西与退出码/stderr。
 * 省缺 `--code` 的「逐字节不变」由既有 `PackModTest` 与 app 侧冻结夹具兜底，这里只钉「没有条目」。
 */
class PackModCodeArtifactTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `code param writes root entry with verbatim bytes`() {
        val module = packableModule(tempDir)
        val code = writeCodeMod(tempDir.resolve("author/app.mod"))
        val withCode = outZip(tempDir, "out/with-code.zip")
        val run = packCodeArgs(module, code, withCode)
        assertEquals(0, run.exit, "out=${run.out} err=${run.err}")

        val names = entryNames(withCode)
        assertTrue(CODE_MOD_ENTRY in names, "names=$names")
        assertContentEquals(Files.readAllBytes(code), entryBytes(withCode, CODE_MOD_ENTRY))

        // 原有的源/资源条目集合与不给 --code 时完全一致（只是多一条 code.mod）。
        val plain = outZip(tempDir, "out/plain.zip")
        val plainRun = runPack(module.toString(), "--out", plain.toString())
        assertEquals(0, plainRun.exit, "out=${plainRun.out} err=${plainRun.err}")
        val plainNames = entryNames(plain)
        assertEquals(plainNames, names.filter { it != CODE_MOD_ENTRY })

        // 摘要只在给了 --code 时多一行，点名条目名/字节数/packageId。
        val summary = "pack-mod: 代码产物 $CODE_MOD_ENTRY（${Files.size(code)} 字节，packageId=$TEST_PACKAGE_ID）"
        assertTrue(summary in run.out, "expected '$summary' in out=${run.out}")
    }

    @Test
    fun `without code param no code entry and no extra summary`() {
        val module = packableModule(tempDir)
        val zip = outZip(tempDir)
        val run = packOk(module, zip)
        assertFalse(CODE_MOD_ENTRY in entryNames(zip), "names=${entryNames(zip)}")
        assertFalse("代码产物" in run.out, "out=${run.out}")
    }

    @Test
    fun `code file missing is usage error`() {
        val module = packableModule(tempDir)
        val run = runPack(
            module.toString(),
            "--out",
            outZip(tempDir).toString(),
            "--code",
            tempDir.resolve("nope.mod").toString(),
        )
        assertEquals(2, run.exit, "out=${run.out} err=${run.err}")
        assertTrue("--code" in run.err, "err=${run.err}")
    }

    @Test
    fun `code file not a zip is refused`() {
        val module = packableModule(tempDir)
        val code = tempDir.resolve("plain.mod")
        Files.writeString(code, "this is not a zip payload")
        val run = packWithCode(module, code)
        assertEquals(1, run.exit, "out=${run.out} err=${run.err}")
        assertTrue("zip" in run.err, "err=${run.err}")
    }

    @Test
    fun `inner zip without classes dex is refused`() {
        val module = packableModule(tempDir)
        val code = writeCodeMod(tempDir.resolve("author/nodex.mod"), dex = null)
        val run = packWithCode(module, code)
        assertEquals(1, run.exit, "out=${run.out} err=${run.err}")
        assertTrue("classes.dex" in run.err, "err=${run.err}")
    }

    @Test
    fun `inner manifest packageId mismatch is refused naming both sides`() {
        val module = packableModule(tempDir)
        val code = writeCodeMod(tempDir.resolve("author/other.mod"), manifest = manifestOf("eratw.other"))
        val run = packWithCode(module, code)
        assertEquals(1, run.exit, "out=${run.out} err=${run.err}")
        assertTrue("eratw.other" in run.err, "err=${run.err}")
        assertTrue(TEST_PACKAGE_ID in run.err, "err=${run.err}")
    }

    @Test
    fun `inner manifest broken json is refused`() {
        val module = packableModule(tempDir)
        val code = writeCodeMod(tempDir.resolve("author/broken.mod"), manifest = "{".toByteArray(Charsets.UTF_8))
        val run = packWithCode(module, code)
        assertEquals(1, run.exit, "out=${run.out} err=${run.err}")
        assertTrue("manifest.json" in run.err, "err=${run.err}")
    }

    @Test
    fun `oversized code file is refused naming actual size and limit`() {
        val module = packableModule(tempDir)
        val code = writeOversizedCodeMod(tempDir.resolve("author/huge.mod"))
        assertTrue(Files.size(code) > MAX_CODE_MOD_BYTES, "fixture must exceed the limit: ${Files.size(code)}")
        val run = packWithCode(module, code)
        assertEquals(1, run.exit, "out=${run.out} err=${run.err}")
        assertTrue("超过上限" in run.err, "err=${run.err}")
        assertTrue("$MAX_CODE_MOD_BYTES" in run.err, "err=${run.err}")
    }

    @Test
    fun `code without pack meta packageId is refused naming packageId`() {
        // 模块有 ERB 源但没有 @ModCarrier 自述身份 ⇒ eratw-pack.json 不会写 packageId，
        // 代码包的运行期身份无从声明 ⇒ 拒包（退出码 1），文案点名 packageId 与「代码包必须声明包名」。
        val module = moduleWithoutCarrier()
        val code = writeCodeMod(tempDir.resolve("author/valid.mod"))
        val run = packWithCode(module, code)
        assertEquals(1, run.exit, "out=${run.out} err=${run.err}")
        assertTrue("代码包必须声明包名" in run.err, "err=${run.err}")
        assertTrue("packageId" in run.err, "err=${run.err}")
        assertFalse(Files.exists(outZip(tempDir)), "拒包不得写出产物")
    }

    @Test
    fun `module without pack meta still packs when code param absent`() {
        // 同一份模块不带 --code：字节行为与改造前完全一致（无 eratw-pack.json、无 code.mod）。
        val module = moduleWithoutCarrier()
        val zip = outZip(tempDir)
        packOk(module, zip)
        val names = entryNames(zip)
        assertFalse(PACK_META_ENTRY_NAME in names, "names=$names")
        assertFalse(CODE_MOD_ENTRY in names, "names=$names")
    }

    @Test
    fun `code constants stay in sync with app source`() {
        val appSource = repoRoot().resolve(APP_CODE_ARTIFACTS_SOURCE)
        assumeTrue(
            Files.isRegularFile(appSource),
            "app 侧 $APP_CODE_ARTIFACTS_SOURCE 尚未落盘（另一 lane 在建，TODO(verify)）：哨兵暂跳过",
        )
        val text = Files.readString(appSource)
        assertEquals(CODE_MOD_ENTRY, stringConstOf(text, "CODE_MOD_ENTRY"))
        assertEquals(MAX_CODE_MOD_BYTES, productConstOf(text, "MAX_CODE_MOD_BYTES"))
    }

    @Test
    fun `empty classes dex entry is refused naming the empty entry branch`() {
        val module = packableModule(tempDir)
        // 零字节 classes.dex：条目在但内容为空 ⇒ readDexState 走 EMPTY，不是 MISSING。
        val code = writeCodeMod(tempDir.resolve("author/empty-dex.mod"), dex = ByteArray(0))
        val run = packWithCode(module, code)
        assertEquals(1, run.exit, "out=${run.out} err=${run.err}")
        assertTrue("空条目" in run.err, "err=${run.err}")
        assertTrue("classes.dex" in run.err, "err=${run.err}")
        // 与「缺条目」分支可区分：缺条目走 NO_CLASSES_DEX_REASON，不带「空条目」字样。
        assertFalse("空条目" in (writeCodeMod(tempDir.resolve("author/nodex-again.mod"), dex = null)
            .let { packWithCode(module, it) }).err, "missing-dex branch must not hit the empty-dex message")
    }

    @Test
    fun `oversized inner manifest is refused naming manifest and limit`() {
        val module = packableModule(tempDir)
        // 4097 字节：恰好越过上限 1 字节（readCapped 是严格 > 判据，4096 可过）。
        val oversized = ByteArray(MAX_CODE_MOD_MANIFEST_BYTES + 1) { ' '.code.toByte() }
        val code = writeCodeMod(tempDir.resolve("author/huge-manifest.mod"), manifest = oversized)
        val run = packWithCode(module, code)
        assertEquals(1, run.exit, "out=${run.out} err=${run.err}")
        assertTrue("manifest.json" in run.err, "err=${run.err}")
        assertTrue("超过" in run.err, "err=${run.err}")
        assertTrue("$MAX_CODE_MOD_MANIFEST_BYTES" in run.err, "err=${run.err}")
    }

    @Test
    fun `inner manifest exactly at limit is accepted and bytes are verbatim`() {
        val module = packableModule(tempDir)
        // 边界：恰好 4096 字节合法 JSON（尾随空格补齐）。readCapped 只在 total > 上限时拒，
        // 所以这一条必须过；若实现把 `>` 写成 `>=`，本用例立刻红。
        val base = manifestOf(TEST_PACKAGE_ID)
        assertTrue(base.size <= MAX_CODE_MOD_MANIFEST_BYTES, "fixture base too large: ${base.size}")
        val padded = base + ByteArray(MAX_CODE_MOD_MANIFEST_BYTES - base.size) { ' '.code.toByte() }
        assertEquals(MAX_CODE_MOD_MANIFEST_BYTES, padded.size)
        val code = writeCodeMod(tempDir.resolve("author/edge-manifest.mod"), manifest = padded)
        val zip = outZip(tempDir)
        val run = packCodeArgs(module, code, zip)
        assertEquals(0, run.exit, "out=${run.out} err=${run.err}")
        // code.mod 原样拷贝的是作者编译好的内层 .mod 包（不是里面的 manifest.json 正文）。
        assertContentEquals(Files.readAllBytes(code), entryBytes(zip, CODE_MOD_ENTRY))
    }

    @Test
    fun `code without entrypoint is usage error exit 2`() {
        val module = packableModule(tempDir)
        val code = writeCodeMod(tempDir.resolve("author/ok.mod"))
        val run = runPack(module.toString(), "--out", outZip(tempDir).toString(), "--code", code.toString())
        assertEquals(2, run.exit, "out=${run.out} err=${run.err}")
        assertTrue("需要同时给" in run.err, "err=${run.err}")
        assertTrue(OPTION_ENTRYPOINT in run.err, "err=${run.err}")
        assertFalse(Files.exists(outZip(tempDir)), "用法错不得写出产物")
    }

    @Test
    fun `entrypoint without code is usage error exit 2`() {
        val module = packableModule(tempDir)
        val run = runPack(module.toString(), "--out", outZip(tempDir).toString(), "--entrypoint", TEST_ENTRYPOINT)
        assertEquals(2, run.exit, "out=${run.out} err=${run.err}")
        assertTrue("只有给了" in run.err, "err=${run.err}")
        assertTrue(OPTION_ENTRYPOINT in run.err, "err=${run.err}")
    }

    @Test
    fun `invalid entrypoint shape is usage error naming the shape`() {
        val module = packableModule(tempDir)
        val code = writeCodeMod(tempDir.resolve("author/ok.mod"))
        val run = runPack(
            module.toString(),
            "--out",
            outZip(tempDir).toString(),
            "--code",
            code.toString(),
            "--entrypoint",
            "eratw.mymod.badname",
        )
        assertEquals(2, run.exit, "out=${run.out} err=${run.err}")
        assertTrue("小写包名" in run.err, "err=${run.err}")
        assertTrue("eratw.mymod.badname" in run.err, "err=${run.err}")
    }

    @Test
    fun `inner manifest without entrypoint is refused exit 1`() {
        val module = packableModule(tempDir)
        val manifest = """{"packageId":"$TEST_PACKAGE_ID"}""".toByteArray(Charsets.UTF_8)
        val code = writeCodeMod(tempDir.resolve("author/no-entry.mod"), manifest = manifest)
        val run = packWithCode(module, code)
        assertEquals(1, run.exit, "out=${run.out} err=${run.err}")
        assertTrue("entrypoint" in run.err, "err=${run.err}")
        assertTrue("缺字符串键" in run.err, "err=${run.err}")
        assertFalse(Files.exists(outZip(tempDir)), "拒包不得写出产物")
    }

    @Test
    fun `inner manifest entrypoint mismatch is refused naming both sides`() {
        val module = packableModule(tempDir)
        val code = writeCodeMod(
            tempDir.resolve("author/other-entry.mod"),
            manifest = manifestOf(TEST_PACKAGE_ID, entrypoint = "eratw.mymod.OtherMod"),
        )
        val run = packWithCode(module, code)
        assertEquals(1, run.exit, "out=${run.out} err=${run.err}")
        assertTrue("eratw.mymod.OtherMod" in run.err, "err=${run.err}")
        assertTrue(TEST_ENTRYPOINT in run.err, "err=${run.err}")
        assertTrue("--entrypoint" in run.err, "err=${run.err}")
    }

    @Test
    fun `inner manifest entrypoint with invalid shape is refused`() {
        val module = packableModule(tempDir)
        val code = writeCodeMod(
            tempDir.resolve("author/bad-entry.mod"),
            manifest = manifestOf(TEST_PACKAGE_ID, entrypoint = "eratw.mymod.badname"),
        )
        val run = packWithCode(module, code)
        assertEquals(1, run.exit, "out=${run.out} err=${run.err}")
        assertTrue("形态非法" in run.err, "err=${run.err}")
        assertTrue("eratw.mymod.badname" in run.err, "err=${run.err}")
    }

    @Test
    fun `inner manifest apiVersion must be a json number`() {
        val module = packableModule(tempDir)
        val manifest = (
            """{"packageId":"$TEST_PACKAGE_ID","entrypoint":"$TEST_ENTRYPOINT","apiVersion":"1"}"""
            ).toByteArray(Charsets.UTF_8)
        val code = writeCodeMod(tempDir.resolve("author/bad-api.mod"), manifest = manifest)
        val run = packWithCode(module, code)
        assertEquals(1, run.exit, "out=${run.out} err=${run.err}")
        assertTrue("apiVersion" in run.err, "err=${run.err}")
        assertTrue("JSON 数字" in run.err, "err=${run.err}")
    }

    @Test
    fun `inner manifest writer orders fields deterministically`() {
        assertEquals(
            """{"packageId":"eratw.mymod","entrypoint":"eratw.mymod.MyMod"}""",
            encodeInnerManifest(TEST_PACKAGE_ID, TEST_ENTRYPOINT),
        )
        assertEquals(
            """{"packageId":"eratw.mymod","entrypoint":"eratw.mymod.MyMod","apiVersion":1}""",
            encodeInnerManifest(TEST_PACKAGE_ID, TEST_ENTRYPOINT, apiVersion = 1),
        )
    }

    @Test
    fun `valid manifest with apiVersion still packs`() {
        val module = packableModule(tempDir)
        val manifest = encodeInnerManifest(TEST_PACKAGE_ID, TEST_ENTRYPOINT, apiVersion = 1)
        val code = writeCodeMod(
            tempDir.resolve("author/api-version.mod"),
            // 合法可选键：数值类型必须放行（周期 7 只判类型）。
            manifest = manifest.toByteArray(Charsets.UTF_8),
        )
        val zip = outZip(tempDir)
        val run = packCodeArgs(module, code, zip)
        assertEquals(0, run.exit, "out=${run.out} err=${run.err}")
        assertTrue(CODE_MOD_ENTRY in entryNames(zip), "names=${entryNames(zip)}")
    }

    /** 干净模块 + `--code`/`--entrypoint`，显式指定产物路径（长参数列表收在一处，避免行宽超限）。 */
    private fun packCodeArgs(module: Path, code: Path, zip: Path = outZip(tempDir)): PackRun =
        runPack(
            module.toString(),
            "--out",
            zip.toString(),
            "--code",
            code.toString(),
            "--entrypoint",
            TEST_ENTRYPOINT,
        )

    /** 外层 CLI 断言：干净模块 + 只给 `--code`（out 走缺省相对路径根下的唯一 zip）。 */
    private fun packWithCode(module: Path, code: Path): PackRun =
        packCodeArgs(module, code)

    /** 手写老式模块：只有 ERB 源、没有 @ModCarrier/元数据/schema（check-mod 零 ERROR）。 */
    private fun moduleWithoutCarrier(): Path {
        val module = tempDir.resolve("mod-bare")
        writeModuleFile(module, TEST_SOURCE, "@SYSTEM\nPRINTL hello\n")
        return module
    }

    /** 现造一个内层 `.mod` 包（[dex] / [manifest] 传 null = 故意缺该条目）。 */
    private fun writeCodeMod(
        target: Path,
        dex: ByteArray? = FAKE_DEX,
        manifest: ByteArray? = manifestOf(TEST_PACKAGE_ID),
    ): Path {
        Files.createDirectories(target.parent)
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            dex?.let { bytes -> writeEntry(zip, "classes.dex", bytes) }
            manifest?.let { bytes -> writeEntry(zip, "manifest.json", bytes) }
        }
        return target
    }

    /** 4 MiB + 1 字节的 `classes.dex`（STORED 不压缩，保证整包文件真超上限；尺寸闸先于解压）。 */
    private fun writeOversizedCodeMod(target: Path): Path {
        val payload = ByteArray((MAX_CODE_MOD_BYTES + 1).toInt())
        val crc = CRC32().apply { update(payload) }
        val dexEntry = ZipEntry("classes.dex").apply {
            method = ZipEntry.STORED
            size = payload.size.toLong()
            compressedSize = payload.size.toLong()
            this.crc = crc.value
        }
        Files.createDirectories(target.parent)
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            zip.putNextEntry(dexEntry)
            zip.write(payload)
            zip.closeEntry()
            writeEntry(zip, "manifest.json", manifestOf(TEST_PACKAGE_ID))
        }
        return target
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    /** 内层清单：走 main 侧 writer（字段定序 packageId → entrypoint → 可选 apiVersion），顺带测写入面。 */
    private fun manifestOf(packageId: String, entrypoint: String = TEST_ENTRYPOINT): ByteArray =
        encodeInnerManifest(packageId, entrypoint).toByteArray(Charsets.UTF_8)

    private fun stringConstOf(text: String, name: String): String =
        assertNotNull(
            Regex("""const val $name(?:: String)?\s*=\s*"([^"]*)"""").find(text),
            "app 源码里找不到常量定义：$name",
        ).groupValues[1]

    /** 数值常量按 RHS 里的数字连乘还原（容忍 `4L * 1024L * 1024L` / `4 * 1024 * 1024` / 单个大数三种写法）。 */
    private fun productConstOf(text: String, name: String): Long {
        val rhs = assertNotNull(
            Regex("""const val $name(?:: Long)?\s*=\s*([^\r\n]+)""").find(text),
            "app 源码里找不到常量定义：$name",
        ).groupValues[1]
        val numbers = Regex("""(\d+)L?""").findAll(rhs).map { it.groupValues[1].toLong() }.toList()
        assertTrue(numbers.isNotEmpty(), "常量 $name 的右值里没有数字：$rhs")
        return numbers.reduce { acc, value -> acc * value }
    }

    private companion object {
        /** 待比对真源：app 侧代码产物导入端（只读；与 `PackModLimitsSentinelTest` 同口径）。 */
        const val APP_CODE_ARTIFACTS_SOURCE =
            "app/src/main/kotlin/com/eratw/port/android/ModImportCodeArtifacts.kt"

        /** 只要非空即可：本校验不解析 dex 内容（dex ABI 闸在 app 侧 ModDexGate）。 */
        val FAKE_DEX = byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00)
    }
}
