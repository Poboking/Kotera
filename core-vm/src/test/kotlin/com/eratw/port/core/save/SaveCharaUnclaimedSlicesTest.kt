package com.eratw.port.core.save

import com.eratw.port.core.data.save.CharaFileIO
import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.data.save.SaveUnclaimedPassthrough
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L41（CONTRACT-RT2-SAVECHARA-CHARSLICES）：`SAVECHARA` 导出面消费普通存档的角色块切片。
 *
 * ## 锁什么
 *
 * - `LOADDATA` 之后同一会话内 `SAVECHARA`：选中角色块按**当前 `NO`** 从
 *   `SaveUnclaimedPassthrough.charaSlicesOf` 取桶，SYSTEM 切片落 `Separator` 之前、USER
 *   切片落之后（L23 的发射口 `CharaFileIO.emitCharacter` 原样复用）；
 * - 子集语义：未选中角色的块及其切片不写（原版 `FileMode.Create` 整档覆写 = 子集快照）；
 * - 身份 fail-closed：当前角色表里该 `NO` 出现多次 ⇒ 该角色不回写切片 + `SaveDiagnostics.note`，
 *   已知条目照常发射（不写坏档）；
 * - 零变化：槽未装配 / 装载号不匹配 / 无切片 ⇒ 输出逐字节不变；
 * - CHARVAR 自身的读侧捕获仍关闭（L38 裁定）：`LOADCHARA` 读进来的未知键不会因本波被回写；
 * - 失败面：取切片抛异常 ⇒ 不回写 + 留痕，编码结果仍可被 `LOADCHARA` 读回
 *   （`OutOfMemoryError` 上抛由 `charaExportSlicePlan` 的 `if (e is OutOfMemoryError) throw e`
 *   保证，无法在单测里确定性触发，见交付报告的 TODO(verify)）；
 * - 物化 `capture` 与流式 `encode` 逐字节一致。
 *
 * ## 证据锚
 *
 * 原版导出只遍历声明表、读侧未知键读完即丢（`CharacterData.cs:601-687` / `:689-898`；
 * `VariableEvaluator.SaveChara` `:2624-2661` 的 `FileMode.Create` 整档覆写）。本波是
 * ADR-005 例外第二波（普通存档角色块）的**消费面扩展**：`SAVECHARA` 把 L17/L23 已捕获的
 * 普通存档未知键按同一身份口径带进 chara 文件；CHARVAR 文件自身的未知键仍按原版丢弃。
 */
class SaveCharaUnclaimedSlicesTest {

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** `NO=5` 块系统相位的未知键。 */
    private val unknownSystem = "UNCLAIMED_EXPORT_SYSTEM_ZZZ"

    /** `NO=5` 块用户相位（`Separator` 之后）的未知键。 */
    private val unknownUser = "UNCLAIMED_EXPORT_USER_ZZZ"

    /** `NO=9` 块的未知键（用于「未选中 ⇒ 不写」判据）。 */
    private val unknownNine = "UNCLAIMED_EXPORT_NINE_ZZZ"

    /**
     * 普通存档夹具：块 0（`NO=5`）含系统相位 + 用户相位未知键；块 1（`NO=9`）含一个未知键。
     * 未知键不声明在任何 store 里 ⇒ 装载时必然进未认领切片槽。
     */
    private fun normalFixture(): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "normal-src",
            characters = listOf(
                listOf(
                    SaveEntry.IntValue("NO", 5),
                    SaveEntry.IntArray1("CFLAG", longArrayOf(1, 2, 3)),
                    SaveEntry.IntValue(unknownSystem, 77),
                    SaveEntry.Separator,
                    SaveEntry.StrValue(unknownUser, "user-phase"),
                ),
                listOf(
                    SaveEntry.IntValue("NO", 9),
                    SaveEntry.IntArray1("CFLAG", longArrayOf(9)),
                    SaveEntry.IntValue(unknownNine, 99),
                ),
            ),
            variables = emptyList(),
            emData = emptyList(),
            compressed = false,
        ),
    )

    /** CHARVAR 夹具：块内带一个未知键（`LOADCHARA` 读入后仍应丢弃，不回写）。 */
    private fun charvarFixture(): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.CHARVAR,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "chara-src",
            characters = listOf(
                listOf(
                    SaveEntry.IntValue("NO", 5),
                    SaveEntry.IntValue(unknownSystem, 7),
                ),
            ),
            variables = emptyList(),
            emData = emptyList(),
            compressed = false,
        ),
    )

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 内存 [SaveFileIo]（路径键与生产实现同为相对路径）。 */
    private class MemorySaveIo : SaveFileIo {
        val files = LinkedHashMap<String, ByteArray>()
        override fun exists(path: String): Boolean = files.containsKey(path)
        override fun read(path: String): ByteArray? = files[path]?.copyOf()
        override fun write(path: String, bytes: ByteArray): Boolean {
            files[path] = bytes.copyOf()
            return true
        }

        override fun delete(path: String): Boolean {
            files.remove(path)
            return true
        }
    }

    private fun config() = SaveConfig(systemSaveInBinary = true, zipSaveData = false, useSaveFolder = true)

    private fun storeWithCharacters(vararg nos: Long): VariableStore = VariableStore().also { s ->
        for (no in nos) s.registerTemplate(CharacterTemplate(no = no, name = "chara-$no", callname = "chara-$no"))
        for (no in nos) s.addCharacter(no)
    }

    /** 与生产语料同形的用户 `#DIM CHARADATA SAVEDATA` 声明（验「已知用户项之后才是 USER 切片」）。 */
    private fun storeWithUserCharaVar(vararg nos: Long): VariableStore =
        storeWithCharacters(*nos).also { s ->
            s.declareUserVar(
                DimParser.parse(
                    "#DIM",
                    "CHARADATA SAVEDATA CUS, 2",
                    isPrivate = false,
                    DimParseConfig(systemSaveInBinary = true),
                ),
            )
        }

    private fun payloadOf(bytes: ByteArray): ByteArray = assertNotNull(SaveFormat.sourcePayloadOf(bytes))

    /** 键名在 payload 里的出现次数（未知键不许重复累积）。 */
    private fun countUtf16(hay: ByteArray, needle: String): Int {
        val n = needle.toByteArray(Charsets.UTF_16LE)
        var count = 0
        var i = 0
        while (i <= hay.size - n.size) {
            var hit = true
            for (j in n.indices) {
                if (hay[i + j] != n[j]) {
                    hit = false
                    break
                }
            }
            if (hit) {
                count++
                i += n.size
            } else {
                i++
            }
        }
        return count
    }

    private fun indexOfUtf16(hay: ByteArray, needle: String): Int {
        val n = needle.toByteArray(Charsets.UTF_16LE)
        outer@ for (i in 0..hay.size - n.size) {
            for (j in n.indices) if (hay[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }

    private fun blockKeys(block: List<SaveEntry>): List<String> = block.map { it.key }

    private fun noOf(block: List<SaveEntry>): Long =
        (block.single { it is SaveEntry.IntValue && it.key == "NO" } as SaveEntry.IntValue).value

    private fun intOf(block: List<SaveEntry>, key: String): Long =
        (block.single { it is SaveEntry.IntValue && it.key == key } as SaveEntry.IntValue).value

    private fun strOf(block: List<SaveEntry>, key: String): String =
        (block.single { it is SaveEntry.StrValue && it.key == key } as SaveEntry.StrValue).value

    /** 生产 `SAVECHARA` 落盘路径：`FileSystemOps.saveChara` → `dat/chara_<name>.dat`。 */
    private fun saveCharaThroughHost(io: MemorySaveIo, store: VariableStore, nos: List<Long>, name: String): ByteArray {
        FileSystemOps(store, io, config()).saveChara(nos, name, "export")
        return io.files.getValue(config().charaPath(name))
    }

    // ------------------------------------------------------------------
    // 1. 主判据：LOADDATA → SAVECHARA 选中角色，未知键按相位回写
    // ------------------------------------------------------------------

    @Test
    fun `LOADDATA 后 SAVECHARA 选中角色_未知键按相位回写`() {
        val io = MemorySaveIo()
        io.files[SLOT_PATH] = normalFixture()
        val store = storeWithUserCharaVar(5, 9)
        FileSystemOps(store, io, config()).loadData(0)
        SaveDiagnostics.clear()

        val bytes = saveCharaThroughHost(io, store, listOf(0L), "PHASE")
        val block = SaveFormat.read(bytes).characters.single()
        val keys = blockKeys(block)
        val sepAt = keys.indexOf("")
        val sysAt = keys.indexOf(unknownSystem)
        val userAt = keys.indexOf(unknownUser)

        assertTrue(sysAt >= 0, "系统相位未知键必须回写：$keys")
        assertTrue(userAt >= 0, "用户相位未知键必须回写：$keys")
        assertTrue(sepAt >= 0, "有用户 CHARADATA 声明 ⇒ 块里必须有 Separator：$keys")
        assertTrue(sysAt < sepAt, "SYSTEM 切片必须落 Separator 之前：$keys")
        assertTrue(userAt > sepAt, "USER 切片必须落 Separator 之后：$keys")
        assertTrue(keys.indexOf("NO") < sysAt, "已知系统项在切片之前：$keys")
        assertEquals(77L, intOf(block, unknownSystem), "系统相位切片值原样回写")
        assertEquals("user-phase", strOf(block, unknownUser), "用户相位切片值原样回写")
        assertEquals(1, countUtf16(payloadOf(bytes), unknownSystem), "$unknownSystem 只写一次")
        assertEquals(1, countUtf16(payloadOf(bytes), unknownUser), "$unknownUser 只写一次")

        // 已知条目与身份不变，产物仍可被 LOADCHARA 读回（RESULT=1 语义不变）。
        assertEquals(5L, noOf(block), "选中角色的 NO 不变")
        assertEquals(listOf(1L, 2L, 3L), cflagOf(block).take(3), "已知系统数组前 3 项不变")
        val target = storeWithCharacters()
        assertTrue(CharaFileIO.load(bytes, target), "产物仍能被 LOADCHARA 读回")
        assertEquals(1, target.characters.count, "LOADCHARA 追加一个角色")
        assertFalse(unknownNine in keys, "未选中角色的切片不许混进来：$keys")
    }

    // ------------------------------------------------------------------
    // 2. 未选中角色 ⇒ 不写
    // ------------------------------------------------------------------

    @Test
    fun `未选中角色的切片不写入`() {
        val io = MemorySaveIo()
        io.files[SLOT_PATH] = normalFixture()
        val store = storeWithUserCharaVar(5, 9)
        FileSystemOps(store, io, config()).loadData(0)

        // 只选 1 号（NO=9）：NO=5 的两条切片都不许出现在产物里。
        val bytes = saveCharaThroughHost(io, store, listOf(1L), "ONLY9")
        val block = SaveFormat.read(bytes).characters.single()
        val keys = blockKeys(block)
        assertEquals(9L, noOf(block), "只有被选中的角色块")
        assertTrue(unknownNine in keys, "选中角色的切片照常回写：$keys")
        assertFalse(unknownSystem in keys, "未选中角色的系统相位切片不许写：$keys")
        assertFalse(unknownUser in keys, "未选中角色的用户相位切片不许写：$keys")
        val payload = payloadOf(bytes)
        assertEquals(-1, indexOfUtf16(payload, unknownSystem), "$unknownSystem 不许出现在产物里")
        assertEquals(-1, indexOfUtf16(payload, unknownUser), "$unknownUser 不许出现在产物里")
        assertEquals(1, countUtf16(payload, unknownNine), "$unknownNine 只写一次")
    }

    // ------------------------------------------------------------------
    // 3. 子集 SAVECHARA：写回文件不含未选角色（原版 FileMode.Create 覆写）
    // ------------------------------------------------------------------

    @Test
    fun `子集 SAVECHARA 只写选中角色块`() {
        val io = MemorySaveIo()
        io.files[SLOT_PATH] = normalFixture()
        val store = storeWithUserCharaVar(5, 9)
        FileSystemOps(store, io, config()).loadData(0)

        val bytes = saveCharaThroughHost(io, store, listOf(0L), "SUBSET")
        val blocks = SaveFormat.read(bytes).characters
        assertEquals(1, blocks.size, "子集导出 = 只有被选中的块")
        assertEquals(5L, noOf(blocks.single()), "块身份 = 选中的 NO=5")
        assertTrue(unknownSystem in blockKeys(blocks.single()), "选中角色的切片在")
        assertFalse(unknownNine in blockKeys(blocks.single()), "未选中角色的切片不在")

        val target = storeWithCharacters()
        assertTrue(CharaFileIO.load(bytes, target), "子集档仍可装载")
        assertEquals(1, target.characters.count, "装载后只有选中的角色")
        assertEquals(5L, target.readInt("NO", longArrayOf(0)), "装载后的 NO = 5")
    }

    // ------------------------------------------------------------------
    // 4. 身份 fail-closed：当前表 NO 不唯一
    // ------------------------------------------------------------------

    @Test
    fun `当前表 NO 不唯一_该角色切片不写并留痕`() {
        val io = MemorySaveIo()
        io.files[SLOT_PATH] = normalFixture()
        val store = storeWithUserCharaVar(5, 9)
        FileSystemOps(store, io, config()).loadData(0)
        // 追加一个同 NO=5 的角色：身份歧义（宁可丢也不挂错人）。
        store.addCharacter(5L)
        SaveDiagnostics.clear()

        val bytes = saveCharaThroughHost(io, store, listOf(0L), "DUP")
        val block = SaveFormat.read(bytes).characters.single()
        val keys = blockKeys(block)
        assertEquals(5L, noOf(block), "已知身份条目照常写出")
        assertFalse(unknownSystem in keys, "NO 不唯一 ⇒ 系统相位切片不写：$keys")
        assertFalse(unknownUser in keys, "NO 不唯一 ⇒ 用户相位切片不写：$keys")
        val payload = payloadOf(bytes)
        assertEquals(-1, indexOfUtf16(payload, unknownSystem), "$unknownSystem 不许写")
        assertEquals(-1, indexOfUtf16(payload, unknownUser), "$unknownUser 不许写")
        // 不写坏档：产物仍可装载。
        assertTrue(CharaFileIO.load(bytes, storeWithCharacters()), "产物仍是合法 chara 档")

        val log = SaveDiagnostics.snapshot().joinToString("\n")
        assertTrue(log.contains("角色块未认领切片丢弃"), "必须留痕：$log")
        assertTrue(log.contains("NO=5"), "留痕要带 NO：$log")
        assertTrue(log.contains(unknownSystem), "留痕要带键名：$log")
    }

    // ------------------------------------------------------------------
    // 5. 零变化：槽未装配 / 装载号不匹配 ⇒ 逐字节不变
    // ------------------------------------------------------------------

    @Test
    fun `槽未装配或装载号不匹配_SAVECHARA 输出逐字节不变`() {
        val store = storeWithCharacters(5, 9)
        val baseline = CharaFileIO.encode(store, listOf(0L, 1L), "zero-change")

        // 槽未装配（宿主从没读过档）。
        SaveUnclaimedPassthrough.install(store)
        assertTrue(
            baseline.contentEquals(CharaFileIO.encode(store, listOf(0L, 1L), "zero-change")),
            "空槽必须逐字节同基线",
        )

        // 槽里有桶但装载号不匹配（= 回标题/新局后上一份档的切片失效，§10.1）。
        val captured = SaveFormat.read(normalFixture()) { it == "NO" || it == "CFLAG" }
        val buckets = captured.unclaimedCharacterVariables
        assertTrue(buckets.isNotEmpty(), "夹具应产出角色桶")
        assertNotNull(SaveUnclaimedPassthrough.of(store)).captureNormal(
            loadNo = store.lastLoadNo + 1,
            entries = emptyList(),
            charaBuckets = buckets,
        )
        assertTrue(
            baseline.contentEquals(CharaFileIO.encode(store, listOf(0L, 1L), "zero-change")),
            "装载号不匹配必须逐字节同基线",
        )
        val payload = payloadOf(CharaFileIO.encode(store, listOf(0L, 1L), "zero-change"))
        for (key in listOf(unknownSystem, unknownUser, unknownNine)) {
            assertEquals(-1, indexOfUtf16(payload, key), "$key 不许因失效的槽泄漏")
        }
    }

    // ------------------------------------------------------------------
    // 6. 物化 capture 与流式 encode 一致
    // ------------------------------------------------------------------

    @Test
    fun `物化 capture 与流式 encode 逐字节一致_含切片`() {
        val io = MemorySaveIo()
        io.files[SLOT_PATH] = normalFixture()
        val store = storeWithUserCharaVar(5, 9)
        FileSystemOps(store, io, config()).loadData(0)

        val streamed = CharaFileIO.encode(store, listOf(0L, 1L), "same", compressed = false)
        val captured = CharaFileIO.capture(store, listOf(0L, 1L), "same", compressed = false)
        val materialized = SaveFormat.write(captured)

        assertTrue(streamed.contentEquals(materialized), "物化入口与流式入口必须逐字节一致")
        val block = captured.characters.first()
        assertTrue(unknownSystem in blockKeys(block), "物化产物里切片在（capture 读回为普通条目）")
        assertTrue(unknownUser in blockKeys(block), "物化产物里用户相位切片在")
        for (key in listOf(unknownSystem, unknownUser, unknownNine)) {
            assertEquals(1, countUtf16(payloadOf(streamed), key), "$key 在两条入口的产物里都只出现一次")
        }
    }

    // ------------------------------------------------------------------
    // 7. CHARVAR 自身读侧捕获仍关闭（L38）：LOADCHARA 的未知键不回写
    // ------------------------------------------------------------------

    @Test
    fun `LOADCHARA 读入的未知键仍不回写_CHARVAR 读侧捕获仍关闭`() {
        val store = storeWithCharacters()
        assertTrue(CharaFileIO.load(charvarFixture(), store), "LOADCHARA 仍成功")
        assertEquals(1, store.characters.count, "追加了一个角色")
        assertNull(SaveUnclaimedPassthrough.of(store), "CHARVAR 装载不存任何切片槽（L38 裁定）")

        val out = CharaFileIO.encode(store, listOf(0L), "re-export")
        assertEquals(
            -1,
            indexOfUtf16(payloadOf(out), unknownSystem),
            "CHARVAR 读侧捕获仍关闭 ⇒ LOADCHARA 读进来的未知键不许回写",
        )
    }

    // ------------------------------------------------------------------
    // 8. 失败面：取切片抛异常 ⇒ 不回写 + 留痕，不写坏档
    // ------------------------------------------------------------------

    @Test
    fun `取切片异常_不回写并留痕_产物仍可装载`() {
        val store = storeWithCharacters(5)
        // RuntimeAux 的槽一旦创建即固定类型、取错类型抛 ClassCastException
        // （RuntimeAux 类 KDoc）；用它模拟「取切片异常」这条失败面。
        store.aux.slot(SaveUnclaimedPassthrough.AUX_SLOT) { "broken-slot" }
        SaveDiagnostics.clear()

        val bytes = CharaFileIO.encode(store, listOf(0L), "broken-slot")
        val log = SaveDiagnostics.snapshot().joinToString("\n")
        assertTrue(log.contains("角色块未认领切片丢弃"), "取切片异常必须留痕：$log")
        assertTrue(log.contains("ClassCastException"), "留痕要带异常类型：$log")
        assertTrue(CharaFileIO.load(bytes, storeWithCharacters()), "失败面不许写出坏档")
        assertEquals(5L, noOf(SaveFormat.read(bytes).characters.single()), "已知条目照常发射")
    }

    private fun cflagOf(block: List<SaveEntry>): List<Long> =
        (block.single { it is SaveEntry.IntArray1 && it.key == "CFLAG" } as SaveEntry.IntArray1).values.toList()

    private companion object {
        /** 生产配置 `UseSaveFolder=true` 下的槽位路径。 */
        const val SLOT_PATH = "sav/save00.sav"
    }
}
