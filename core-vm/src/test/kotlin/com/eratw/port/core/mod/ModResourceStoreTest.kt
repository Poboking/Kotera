package com.eratw.port.core.mod

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * mod 资源存储第一刀的钉子（task-24）：名字语义 / 隔离 / 上限 / 惰性 / 坏包不炸。
 *
 * 夹具一律 `Files.createTempDirectory(...).toFile()` + `finally { deleteRecursively() }`，
 * 不碰真实游戏目录；每个用例自建 `<pkg>.assets/` 子目录。
 *
 * 反向证明（交付消息记录）：把 `checkName` 的 `..` 段校验摘掉 ⇒ 穿越用例红；把
 * `ModRuntimeHost.install` 的 `resources?.let { … }` 摘掉 ⇒ 装配用例（另一测试类）红。
 */
class ModResourceStoreTest {

    private fun withTempRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("eratw-mod-resources").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assetDir(root: File, packageId: String): File =
        File(root, packageId + ModResourceStore.ASSET_DIR_SUFFIX).also { it.mkdirs() }

    private fun writeFile(dir: File, name: String, text: String): File {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    private fun storeWithWarnings(
        root: File,
        maxFileBytes: Long = ModResourceStore.MAX_RESOURCE_FILE_BYTES,
        maxTotalBytes: Long = ModResourceStore.MAX_RESOURCE_TOTAL_BYTES,
    ): Pair<ModResourceStore, MutableList<String>> {
        val warnings = mutableListOf<String>()
        val store = ModResourceStore(
            rt2Dir = root,
            logSink = { warnings += it },
            maxFileBytes = maxFileBytes,
            maxTotalBytes = maxTotalBytes,
        )
        return store to warnings
    }

    // ---------------- 名字语义：严格拒绝、不静默归一 ----------------

    @Test
    fun `非法资源名在三个入口都抛且不静默归一`() {
        withTempRoot { root ->
            assetDir(root, "mod.a")
            val api = ModResourceStore(root).apiFor("mod.a")
            val invalid = listOf(
                "../x", // 上跳
                "/abs", // 绝对路径（前导斜杠）
                "a/../b", // 中间上跳
                "a//b", // 空段（重复斜杠）
                "/a", // 前导斜杠
                "a/", // 尾随斜杠
                "a\\b", // 反斜杠分隔
                "C:/x", // Windows 盘符
                "a\u0000b", // 控制字符
                "..",
                ".",
                "",
                "a/./b", // 点段
                "a\\..\\b", // 反斜杠 + 上跳
                "a:b", // 冒号（盘符 / ADS 形态）
            )
            for (name in invalid) {
                assertFailsWith<IllegalArgumentException>("open 必须拒绝非法名 [$name]") { api.open(name) }
                assertFailsWith<IllegalArgumentException>("exists 必须拒绝非法名 [$name]") { api.exists(name) }
                assertFailsWith<IllegalArgumentException>("size 必须拒绝非法名 [$name]") { api.size(name) }
            }
            // 结论：前导 / 与重复 // 不做「归一后放行」，直接按非法名拒绝。
        }
    }

    // ---------------- 缺文件 / 缺目录不是异常 ----------------

    @Test
    fun `缺文件与缺包目录都是null而非异常`() {
        withTempRoot { root ->
            val missingPkg = ModResourceStore(root).apiFor("mod.a")
            assertEquals(emptyList<String>(), missingPkg.keys(), "包目录不存在 = 空资源面，不告警不崩")
            assertFalse(missingPkg.exists("x.bin"))
            assertNull(missingPkg.size("x.bin"))
            assertNull(missingPkg.open("x.bin"))

            assetDir(root, "mod.b")
            val present = ModResourceStore(root).apiFor("mod.b")
            assertEquals(emptyList<String>(), present.keys())
            assertFalse(present.exists("nope.bin"))
            assertNull(present.size("nope.bin"))
            assertNull(present.open("nope.bin"))
        }
    }

    // ---------------- 跨包隔离 ----------------

    @Test
    fun `跨包隔离同名资源各读各的且看不到对方目录`() {
        withTempRoot { root ->
            writeFile(assetDir(root, "mod.a"), "same.txt", "A")
            writeFile(assetDir(root, "mod.b"), "same.txt", "B")
            val store = ModResourceStore(root)
            val apiA = store.apiFor("mod.a")
            val apiB = store.apiFor("mod.b")
            assertEquals(listOf("same.txt"), apiA.keys())
            assertEquals("A", String(apiA.open("same.txt")!!))
            assertEquals("B", String(apiB.open("same.txt")!!))
            // A 包视图无法用任何名字形态读到 B 包目录（名字校验层先拒）。
            val escape = "../mod.b" + ModResourceStore.ASSET_DIR_SUFFIX + "/same.txt"
            assertFailsWith<IllegalArgumentException> { apiA.open(escape) }
            // packageId 本身也是目录派生入参：非法 packageId 不得拼出包外目录。
            assertFailsWith<IllegalArgumentException> { store.apiFor("../mod.b") }
            assertFailsWith<IllegalArgumentException> { store.apiFor("mod/b") }
        }
    }

    @Test
    fun `非法packageId在apiFor被拒`() {
        withTempRoot { root ->
            val store = ModResourceStore(root)
            for (pkg in listOf("", "..", "../mod.b", "a/b", "a\\b", "a b", "-lead", ".hidden")) {
                assertFailsWith<IllegalArgumentException>("apiFor 必须拒绝 [$pkg]") { store.apiFor(pkg) }
            }
        }
    }

    // ---------------- 上限 ----------------

    @Test
    fun `单文件超限只跳过该文件并WARN`() {
        withTempRoot { root ->
            val dir = assetDir(root, "mod.a")
            writeFile(dir, "ok.txt", "abc")
            writeFile(dir, "big.bin", "12345")
            val (store, warnings) = storeWithWarnings(root, maxFileBytes = 4L, maxTotalBytes = 1024L)
            val api = store.apiFor("mod.a")
            assertEquals(listOf("ok.txt"), api.keys(), "超单文件上限者从列表里消失，其余照常可用")
            assertTrue(api.exists("ok.txt"))
            assertFalse(api.exists("big.bin"))
            assertNull(api.size("big.bin"))
            assertNull(api.open("big.bin"))
            assertTrue(warnings.any { it.contains("单文件上限") }, "应有单文件超限 WARN：$warnings")
        }
    }

    @Test
    fun `总量超限整包资源面禁用并WARN`() {
        withTempRoot { root ->
            val dir = assetDir(root, "mod.a")
            writeFile(dir, "a.txt", "aaaa")
            writeFile(dir, "b.txt", "bbbb")
            writeFile(dir, "c.txt", "cc")
            val (store, warnings) = storeWithWarnings(root, maxFileBytes = 64L, maxTotalBytes = 8L)
            val api = store.apiFor("mod.a")
            // 裁决：总量超限 = 整包禁用（不返回按名序截断的部分集），避免 mod 行为取决于遍历序。
            assertEquals(emptyList<String>(), api.keys())
            assertNull(api.open("a.txt"))
            assertNull(api.size("a.txt"))
            assertFalse(api.exists("a.txt"))
            assertTrue(warnings.any { it.contains("总量超过上限") }, "应有总量超限 WARN：$warnings")
        }
    }

    // ---------------- 名字排序 / 大小写结论 ----------------

    @Test
    fun `keys按名升序且保留磁盘原名大小写`() {
        withTempRoot { root ->
            val dir = assetDir(root, "mod.a")
            writeFile(dir, "b.txt", "b")
            writeFile(dir, "a/Z.TXT", "upper")
            writeFile(dir, "a/deep/c.bin", "deep")
            val api = ModResourceStore(root).apiFor("mod.a")
            assertEquals(listOf("a/Z.TXT", "a/deep/c.bin", "b.txt"), api.keys())
            assertTrue(api.exists("a/Z.TXT"))
            assertEquals("upper", String(api.open("a/Z.TXT")!!))
            // 结论：keys 不做大小写折叠，只报告磁盘原名；跨大小写是否命中由宿主 FS 决定。
            assertFalse(api.keys().contains("a/z.txt"), "keys 不折叠大小写")
        }
    }

    // ---------------- 惰性 / 不缓存 ----------------

    @Test
    fun `构造与apiFor惰性且不缓存字节`() {
        withTempRoot { root ->
            val store = ModResourceStore(root)
            val api = store.apiFor("mod.a")
            val dir = File(root, "mod.a" + ModResourceStore.ASSET_DIR_SUFFIX)
            assertFalse(dir.exists(), "构造 store / 取视图都不得建目录")
            assertEquals(emptyList<String>(), api.keys())

            dir.mkdirs()
            writeFile(dir, "late.txt", "v1")
            assertEquals(listOf("late.txt"), api.keys(), "创建后下一次调用立即可见（惰性走盘）")
            assertEquals("v1", String(api.open("late.txt")!!))

            writeFile(dir, "late.txt", "v2")
            assertEquals("v2", String(api.open("late.txt")!!), "无字节缓存：改写后下一次读取看到新内容")
        }
    }

    @Test
    fun `open返回副本改动不回写磁盘`() {
        withTempRoot { root ->
            writeFile(assetDir(root, "mod.a"), "data.bin", "abc")
            val api = ModResourceStore(root).apiFor("mod.a")
            val first = api.open("data.bin")!!
            first[0] = 'Z'.code.toByte()
            assertEquals("abc", String(api.open("data.bin")!!), "open 返回新副本，调用方改动不影响磁盘")
        }
    }

    @Test
    fun `空文件可读且size为零`() {
        withTempRoot { root ->
            writeFile(assetDir(root, "mod.a"), "empty.bin", "")
            val api = ModResourceStore(root).apiFor("mod.a")
            assertEquals(listOf("empty.bin"), api.keys())
            assertEquals(0L, api.size("empty.bin"))
            assertTrue(api.exists("empty.bin"))
            assertEquals(0, api.open("empty.bin")!!.size)
        }
    }

    // ---------------- 坏包不炸宿主 ----------------

    @Test
    fun `坏包目录不炸且打WARN`() {
        withTempRoot { root ->
            // 同名路径是个普通文件而不是目录（坏安装包 / 导入失败残留）。
            File(root, "mod.a" + ModResourceStore.ASSET_DIR_SUFFIX).writeText("not a dir")
            val (store, warnings) = storeWithWarnings(root)
            val api = store.apiFor("mod.a")
            assertEquals(emptyList<String>(), api.keys())
            assertNull(api.open("x.txt"))
            assertFalse(api.exists("x.txt"))
            assertTrue(warnings.any { it.contains("不是目录") }, "坏目录应 WARN 而不抛：$warnings")
        }
    }
}
