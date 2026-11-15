package com.eratw.port.core.save

import com.eratw.port.core.data.save.CharaFileIO
import com.eratw.port.core.data.save.CharaPhase
import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * L23（CONTRACT-RT2-UNCLAIMED-B2-PHASE）：角色块未认领切片的**相位**保真。
 *
 * ## 锁什么
 *
 * - 读侧 [SaveFormat.read] 以块内 `Separator`（0xFD）为界给每条未认领切片记
 *   [CharaPhase]：`Separator` 之前 = [CharaPhase.SYSTEM]（系统角色项）、之后 =
 *   [CharaPhase.USER]（用户 `#DIM CHARADATA` 项）；
 * - 写侧 [SaveFormat.writePayload] 与 `SaveCapture.emitNormal` / `CharaFileIO.emitCharacter`
 *   按相位放回：系统切片落 `Separator` 之前、用户切片落之后；块里没有 `Separator` 但存在
 *   用户相位切片 ⇒ **补一个 `Separator`**，读回仍落用户分支（不是系统相位）；
 * - 已知条目的字节/顺序不动（canonical 两相位夹具 `write(read(f)) == f` 逐字节不变）；
 * - 无切片档逐字节不变；物化入口与流式入口逐字节一致；`CHARVAR` 仍不捕获；
 * - 身份绑定的 fail-closed 规则沿用 L17（`NO` 分桶），本波不改。
 *
 * ## 证据锚
 *
 * 写侧 `CharacterData.SaveToStreamBinary`：系统项 `:601-663`，`if (UserDefCVarDataList.Count != 0)`
 * 才 `writer.WriteSeparator()`（`:666-668`）再写用户项，最后 `writer.WriteEOC()`（`:686`）；
 * 读侧 `LoadFromStreamBinary`：`bool userDefineData = false`（`:692`），
 * `case EraSaveDataType.Separator: userDefineData = true`（`:718-720`），之后只认
 * `UserDefinedCharaVariableToken`（`:702-707`）。
 */
class SaveUnclaimedCharaPhaseTest {

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 系统相位（`Separator` 之前）的未知键。 */
    private val unknownSystem = "UNCLAIMED_PHASE_SYSTEM_ZZZ"

    /** 用户相位（`Separator` 之后）的未知键。 */
    private val unknownUser = "UNCLAIMED_PHASE_USER_ZZZ"

    private fun knownPredicate(): (String) -> Boolean =
        { it == "NO" || it == "CFLAG" || it == "MONEY" }

    /**
     * 块 0（NO=5）：已知系统项 → 系统相位未知键 → `Separator` → 用户相位未知键；
     * 块 1（NO=9）：干净块（无未知键，也不带 `Separator`）。
     */
    private fun twoPhaseFixture(): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "chara-phase",
            characters = listOf(
                listOf(
                    SaveEntry.IntValue("NO", 5),
                    SaveEntry.IntArray1("CFLAG", longArrayOf(1, 0, 3)),
                    SaveEntry.IntValue(unknownSystem, 11),
                    SaveEntry.Separator,
                    SaveEntry.StrValue(unknownUser, "user-phase"),
                ),
                listOf(
                    SaveEntry.IntValue("NO", 9),
                    SaveEntry.IntArray1("CFLAG", longArrayOf(2)),
                ),
            ),
            variables = listOf(SaveEntry.IntValue("MONEY", 7)),
            emData = emptyList(),
            compressed = false,
        ),
    )

    /** 只有用户相位未知键的块（当前声明表没有用户角色变量时用来验 `Separator` 合成）。 */
    private fun userOnlyFixture(): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "user-only",
            characters = listOf(
                listOf(
                    SaveEntry.IntValue("NO", 5),
                    SaveEntry.IntArray1("CFLAG", longArrayOf(1)),
                    SaveEntry.Separator,
                    SaveEntry.StrValue(unknownUser, "only-user"),
                ),
            ),
            variables = emptyList(),
            emData = emptyList(),
            compressed = false,
        ),
    )

    /** 无任何未认领键、但块里带 `Separator` 的干净档（零变化判据）。 */
    private fun cleanWithSeparatorFixture(): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "clean-phase",
            characters = listOf(
                listOf(
                    SaveEntry.IntValue("NO", 5),
                    SaveEntry.IntArray1("CFLAG", longArrayOf(1, 2)),
                    SaveEntry.Separator,
                ),
            ),
            variables = listOf(SaveEntry.IntValue("MONEY", 1)),
            emData = emptyList(),
            compressed = false,
        ),
    )

    /** CHARVAR 夹具：用户段里带未知键（仍按原版丢弃，不捕获）。 */
    private fun charvarFixture(): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.CHARVAR,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "chara-file-phase",
            characters = listOf(
                listOf(
                    SaveEntry.IntValue("NO", 5),
                    SaveEntry.Separator,
                    SaveEntry.StrValue(unknownUser, "drop-me"),
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

    /** 每块带用户 `#DIM CHARADATA` 声明（用来验「系统切片在 Separator 前」的生产路形状）。 */
    private fun storeWithUserCharaVars(vararg nos: Long): VariableStore =
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

    /** UTF-16LE 子串出现次数（键名在 payload 里的存在性/重复性判据）。 */
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

    /**
     * 块内条目序（文件原序）。`Separator` 在 [SaveData.characters] 里是 [SaveEntry.Separator]、
     * 键名恒为空串 —— 断言 `Separator` 与两条未知键的相对次序就够了，不需要另写字节扫描器。
     */
    private fun keysOf(bytes: ByteArray, block: Int): List<String> =
        SaveFormat.read(bytes).characters[block].map { it.key }

    private fun phaseOf(bytes: ByteArray, no: Long, known: (String) -> Boolean): List<CharaPhase> {
        val filtered = SaveFormat.read(bytes, known)
        return filtered.unclaimedCharacterVariables.getValue(no).map { it.phase }
    }

    /** 生产口径的「此刻已声明」谓词（与 `FileSystemOps.isKnownSaveKey` 同源）。 */
    private fun knownForStore(store: VariableStore): (String) -> Boolean =
        { key -> store.registry.def(key) != null || store.userVar(key, null) != null }

    // ------------------------------------------------------------------
    // 1. 读侧：以块内 Separator 为界记相位
    // ------------------------------------------------------------------

    @Test
    fun `读侧按块内 Separator 记相位_系统项在前用户项在后`() {
        val data = SaveFormat.read(twoPhaseFixture(), knownPredicate())
        val slices = data.unclaimedCharacterVariables.getValue(5L)
        assertEquals(listOf(unknownSystem, unknownUser), slices.map { it.key }, "NO=5 的两条切片按原序")
        assertEquals(
            listOf(CharaPhase.SYSTEM, CharaPhase.USER),
            slices.map { it.phase },
            "Separator 之前的条目记 SYSTEM、之后的记 USER",
        )
        assertTrue(data.unclaimedCharacterVariables[9L] == null, "干净块不产出桶")
        assertEquals(
            listOf(unknownSystem, unknownUser),
            data.droppedCharacterVariables,
            "上屏报告面的键名清单语义不变",
        )
        assertTrue(data.unclaimedCharacterDroppedKeys.isEmpty(), "身份可判定时不许有丢弃留痕")
    }

    // ------------------------------------------------------------------
    // 2. 写侧（SaveData 回放 = writePayload）：按相位放回、canonical 夹具逐字节不变
    // ------------------------------------------------------------------

    @Test
    fun `writePayload 按相位回写_系统在 Separator 前_用户在 Separator 后`() {
        val original = twoPhaseFixture()
        val data = SaveFormat.read(original, knownPredicate())
        val rewritten = SaveFormat.write(data)
        // 夹具本身就是相位 canonical 的：若写侧把切片一律堆到块尾，payload 会变（切片跨过 Separator）。
        assertTrue(original.contentEquals(rewritten), "canonical 两相位夹具必须逐字节不变（含容器头）")

        assertEquals(
            listOf("NO", "CFLAG", unknownSystem, "", unknownUser),
            keysOf(rewritten, 0),
            "块 0 条目序：系统切片落 Separator 之前、用户切片落之后",
        )
        assertEquals(listOf("NO", "CFLAG"), keysOf(rewritten, 1), "块 1 不变")
        assertEquals(
            listOf(CharaPhase.SYSTEM, CharaPhase.USER),
            phaseOf(rewritten, 5L, knownPredicate()),
            "重写档读回仍是同样的相位",
        )
    }

    @Test
    fun `writePayload 块里没有 Separator 的合成_用户相位切片仍读回用户分支`() {
        val data = SaveFormat.read(twoPhaseFixture(), knownPredicate())
        // 模拟「当前声明表没有用户角色变量」：块里不带 Separator，但槽里仍有用户相位切片。
        val blocks = data.characters.map { block -> block.filter { it !is SaveEntry.Separator } }
        val bare = SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = data.uniqueCode,
            scriptVersion = data.scriptVersion,
            text = data.text,
            characters = blocks,
            variables = data.variables,
            emData = emptyList(),
            compressed = false,
        )
        bare.unclaimedCharacterVariables = data.unclaimedCharacterVariables
        val out = SaveFormat.write(bare)

        val keys = keysOf(out, 0)
        assertEquals(
            listOf("NO", "CFLAG", unknownSystem, "", unknownUser),
            keys,
            "没有 Separator 的块：系统切片在块尾之前、用户切片前补一个 Separator",
        )
        assertEquals(
            listOf(CharaPhase.SYSTEM, CharaPhase.USER),
            phaseOf(out, 5L, knownPredicate()),
            "补出来的 Separator 让用户相位切片读回仍落用户分支（不是 SYSTEM）",
        )
    }

    // ------------------------------------------------------------------
    // 3. 生产路：系统切片在已知系统项之后、Separator 之前
    // ------------------------------------------------------------------

    @Test
    fun `生产路系统相位切片回写在 Separator 之前_用户相位切片在其后`() {
        val io = MemorySaveIo()
        io.files[SLOT_PATH] = twoPhaseFixture()
        val store = storeWithUserCharaVars(5, 9)
        val fs = FileSystemOps(store, io, config())
        fs.loadData(0)
        assertTrue(fs.saveData(0, "phase"), "再存档应成功")

        val saved = io.files.getValue(SLOT_PATH)
        val block0 = keysOf(saved, 0)
        val sepAt = block0.indexOf("")
        val sysAt = block0.indexOf(unknownSystem)
        val userAt = block0.indexOf(unknownUser)
        assertTrue(sysAt >= 0 && userAt >= 0, "两条切片都要回写：$block0")
        assertTrue(sepAt >= 0, "用户 CHARADATA 声明存在 ⇒ 块里必须有 Separator：$block0")
        assertTrue(sysAt < sepAt, "系统相位切片必须在 Separator 之前：$block0")
        assertTrue(userAt > sepAt, "用户相位切片必须在 Separator 之后：$block0")

        val payload = payloadOf(saved)
        assertEquals(1, countUtf16(payload, unknownSystem), "$unknownSystem 只写一次")
        assertEquals(1, countUtf16(payload, unknownUser), "$unknownUser 只写一次")
        assertEquals(
            listOf(CharaPhase.SYSTEM, CharaPhase.USER),
            phaseOf(saved, 5L, knownForStore(store)),
            "产物读回相位不变",
        )
    }

    // ------------------------------------------------------------------
    // 4. 生产路：没有用户角色变量时补 Separator（用户相位切片不降级成系统相位）
    // ------------------------------------------------------------------

    @Test
    fun `当前无用户角色变量时用户相位切片_补 Separator 且读回落用户分支`() {
        val io = MemorySaveIo()
        io.files[SLOT_PATH] = userOnlyFixture()
        // 刻意不声明任何 CHARADATA 变量：emitCharacter 本来不会写 Separator。
        val store = storeWithCharacters(5)
        val fs = FileSystemOps(store, io, config())
        fs.loadData(0)
        assertTrue(fs.saveData(0, "synth"), "再存档应成功")

        val saved = io.files.getValue(SLOT_PATH)
        val keys = keysOf(saved, 0)
        val sepAt = keys.indexOf("")
        val userAt = keys.indexOf(unknownUser)
        assertTrue(sepAt >= 0, "没有用户角色变量时也要补一个 Separator：$keys")
        assertTrue(userAt > sepAt, "用户相位切片必须落在补出来的 Separator 之后：$keys")
        assertEquals(1, keys.count { it == unknownUser }, "切片只写一次：$keys")
        assertEquals(
            listOf(CharaPhase.USER),
            phaseOf(saved, 5L, knownForStore(store)),
            "补出来的 Separator 让切片读回落用户分支；不补会被读成 SYSTEM",
        )
        assertEquals(1, countUtf16(payloadOf(saved), unknownUser), "切片只写一次")
    }

    // ------------------------------------------------------------------
    // 5. 零变化：无切片档逐字节不变（含带 Separator 的块）
    // ------------------------------------------------------------------

    @Test
    fun `无角色块未认领键_输出逐字节不变_含带 Separator 的块`() {
        val clean = cleanWithSeparatorFixture()
        val data = SaveFormat.read(clean, knownPredicate())
        assertTrue(data.unclaimedCharacterVariables.isEmpty(), "无未知键时角色桶为空")
        assertTrue(clean.contentEquals(SaveFormat.write(data)), "无未知键必须逐字节不变（含容器头）")
        assertTrue(clean.contentEquals(SaveFormat.write(SaveFormat.read(clean))), "无过滤器路径同样不变")
    }

    // ------------------------------------------------------------------
    // 6. 物化入口与流式入口逐字节一致（含相位）
    // ------------------------------------------------------------------

    @Test
    fun `物化入口与流式入口逐字节一致_含相位`() {
        val io = MemorySaveIo()
        io.files[SLOT_PATH] = twoPhaseFixture()
        val store = storeWithUserCharaVars(5, 9)
        FileSystemOps(store, io, config()).loadData(0)

        val out = ByteArrayOutputStream()
        SaveFormat.writeStreaming(compressed = false, out) { w ->
            SaveCapture.emitNormal(store, "materialize-phase", w)
        }
        val streamed = out.toByteArray()
        val materialized = SaveFormat.write(SaveCapture.normal(store, "materialize-phase", compressed = false))

        assertTrue(streamed.contentEquals(materialized), "物化入口与流式入口必须逐字节一致")
        val captured = SaveCapture.normal(store, "materialize-phase", compressed = false)
        assertEquals(
            listOf(CharaPhase.SYSTEM, CharaPhase.USER),
            captured.unclaimedCharacterVariables.getValue(5L).map { it.phase },
            "物化读回同样保留相位",
        )
        for (key in listOf(unknownSystem, unknownUser)) {
            assertEquals(1, countUtf16(payloadOf(streamed), key), "$key 在两条入口的产物里都只出现一次")
        }
    }

    // ------------------------------------------------------------------
    // 7. CHARVAR 仍不捕获（含用户相位条目）
    // ------------------------------------------------------------------

    @Test
    fun `CHARVAR 角色档不捕获角色块未知键_含用户相位条目`() {
        val charvar = charvarFixture()
        val data = SaveFormat.read(charvar, knownPredicate())
        assertTrue(data.unclaimedCharacterVariables.isEmpty(), "CHARVAR 不捕获（追加角色语义，按 NO 回写会串档）")
        assertTrue(data.unclaimedCharacterDroppedKeys.isEmpty(), "CHARVAR 不留身份留痕")
        assertEquals(listOf(unknownUser), data.droppedCharacterVariables, "键名照旧进报告面")

        val rewritten = SaveFormat.write(data)
        assertEquals(-1, indexOfUtf16(payloadOf(rewritten), unknownUser), "CHARVAR 未知键仍按原版消失")

        // 生产写侧入口同样不带切片：编码 → 装载仍成功，且产物含用户段但没有未知键。
        val source = storeWithCharacters(5)
        val encoded = CharaFileIO.encode(source, listOf(0), "chara")
        val target = storeWithCharacters()
        assertTrue(CharaFileIO.load(encoded, target), "LOADCHARA 仍成功")
        assertEquals(1, target.characters.count, "角色被追加进目标池")
        assertEquals(-1, indexOfUtf16(payloadOf(encoded), unknownUser), "CHARVAR 不写回未认领键")
    }

    private companion object {
        /** 生产配置 `UseSaveFolder=true` 下的槽位路径。 */
        const val SLOT_PATH = "sav/save00.sav"
    }
}
