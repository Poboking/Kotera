package com.eratw.port.core.proc

import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [datingForensics] 的定向单测（diag-datingsync）。
 *
 * 2026-11-19 兼容层解耦：坐标不再是 core 常量，改由 [ForensicsLayout] 注入。
 * 本测试用 [tw]（夹带 eraTW AutoConst 数值的测试布局，test 源集不受业务词门禁约束）
 * 复跑原全部断言——**断言逐字不变**，证明外置坐标后读数语义逐格等价。
 * 另加 [未注入布局] 一例，钉住「宿主非 eraTW ⇒ 整段 isEmpty、零读取」的解耦判据。
 *
 * 断言的是**观察口自己的形状**，不是语料行为：
 * - 4.980 判式 `CFLAG:no:约会中 != MAIN_MAP` 逐字复刻官方过判（ADR-005 不修）；
 * - 4.981 判式 `CFLAG:no:约会中 != GET_HOME_MAP()` 双口径反向证明锁住基准漂移。
 */
class DatingForensicsTest {

    /** eraTW 语料坐标夹具（数值取 AutoConst 头；等价于 app 侧 EratwForensicsLayout.ERATW）。 */
    private val tw = ForensicsLayout(
        mainMapVar = "MAIN_MAP",
        mainMapIndex = 0L,
        randomCharaVar = "RANDOM_CHARANUM",
        gmfFixVar = "GENSOKYO_MAP_FIX",
        gmfHomeMapVar = "GMF_HOME_MAP",
        masterVar = "MASTER",
        cflagVar = "CFLAG",
        talentVar = "TALENT",
        tflagVar = "TFLAG",
        flagVar = "FLAG",
        cflagDating = 12L,
        cflagPosition = 300L,
        cflagTogether = 320L,
        flagAffairTarget = 73L,
        tflagDateTravel = 229L,
        tflagOncePerDay = 199L,
        talentMobgirl = 300L,
        forgetBit = 9,
        gmfMapBit = 0,
        mismatchDetailLimit = 12,
    )

    /** eraTW 中文标签夹具（等价于 app 侧 EratwForensicsLayout 的 ERATW_LABELS）。 */
    private val twLabels = ForensicsLabels(
        title = "约会现场：",
        mainMap = "MAIN_MAP",
        gmfBit = "GMF位",
        gmfHomeMap = "GMF家MAP",
        homeMap = "家MAP",
        mismatch = "约会中≠MAIN_MAP",
        homeMismatch = "约会中≠家MAP",
        detailPrefix = "明细",
        homeDetailPrefix = "家明细",
        dateTravel = "约会道中",
        masterDate = "MASTER约会中",
        masterPos = "MASTER现在位置",
        masterTogether = "MASTER同行",
        affairTarget = "约会的对象",
        targetDate = "对象约会中",
        targetPos = "对象现在位置",
        targetTogether = "对象同行",
        randomChara = "路人号",
        mobTalent = "路人天赋",
        forgetBit = "忘记位",
    )

    private fun store(): VariableStore = VariableStore()

    private fun VariableStore.declareSavedata(name: String, value: Long): VariableStore = apply {
        declareUserVar(DimParser.parse("#DIM", "SAVEDATA $name", false))
        writeInt(name, longArrayOf(0), value)
    }

    private fun VariableStore.declareMainMap(value: Long): VariableStore =
        declareSavedata("MAIN_MAP", value)

    private fun VariableStore.declareRandomCharaNum(value: Long): VariableStore =
        declareSavedata("RANDOM_CHARANUM", value)

    /** `GENSOKYO_MAP_FIX` bit0 + 可选的 `GMF_HOME_MAP`（null = 不声明，模拟 DLC 头缺席）。 */
    private fun VariableStore.declareGmf(bit0: Long, homeMap: Long?): VariableStore = apply {
        declareSavedata("GENSOKYO_MAP_FIX", bit0)
        if (homeMap != null) declareSavedata("GMF_HOME_MAP", homeMap)
    }

    private fun VariableStore.dating(no: Int, value: Long) =
        writeInt("CFLAG", longArrayOf(no.toLong(), tw.cflagDating!!), value)

    @Test
    fun `未注入布局——整段 isEmpty 且零读取、不抛`() {
        val s = store().declareMainMap(1L).declareRandomCharaNum(2L)
        repeat(3) { s.characters.addBare() }
        s.dating(0, 5L)
        s.writeInt("FLAG", longArrayOf(tw.flagAffairTarget!!), 67L)
        val f = datingForensics(s, ForensicsLayout.DISABLED)
        assertTrue(f.isEmpty, "宿主未注入坐标 ⇒ 判定为不可用，即便 store 里有 eraTW 变量也不读")
        assertNull(f.mainMap)
        assertNull(f.randomCharaNum)
        assertNull(f.affairTarget)
        assertNull(f.mismatchCount)
        assertNull(f.masterDate)
        // 兼容重载（单参）同义：未注入 = DISABLED
        assertTrue(datingForensics(s).isEmpty)
    }

    @Test
    fun `裸 store 不抛——语料侧 #DIM 缺席即整行无信息量`() {
        val f = datingForensics(store(), tw)
        assertTrue(f.isEmpty, "MAIN_MAP 与 RANDOM_CHARANUM 都没声明 ⇒ 判定为不可用")
        assertNull(f.mainMap)
        assertNull(f.mismatchCount)
        assertNull(f.gmfMapBit)
        assertNull(f.gmfHomeMap)
        assertNull(f.homeMap)
        assertNull(f.homeMismatchCount)
        // 系统变量恒可读（尺寸走 VariableSizes 缺省表），它们不该被算作"有读数"
        assertEquals(0L, f.affairTarget)
        assertEquals(0L, f.forgetBit)
        assertEquals(0L, f.dateTravel)
        // MASTER 指针本身是 0，但角色表空 ⇒ CFLAG:0 越界，这一格照样降级不抛
        assertNull(f.masterDate)
        assertNull(f.masterPos)
        assertNull(f.masterTogether)
        // 约会的对象=0 但角色表空 ⇒ 对象读数同样越界降级
        assertNull(f.targetDate)
        assertNull(f.targetPos)
        assertNull(f.targetTogether)
    }

    @Test
    fun `MAIN_MAP 未声明时明细整段放弃——其余字段照读`() {
        val s = store()
        repeat(3) { s.characters.addBare() }
        s.writeInt("FLAG", longArrayOf(tw.flagAffairTarget!!), 67L)
        val f = datingForensics(s, tw)
        assertNull(f.mismatchCount)
        assertNull(f.mismatchDetails)
        assertNull(f.homeMismatchCount, "MAIN_MAP 缺席 ⇒ 4.981 口径的关分支也没有基准")
        assertEquals(3, f.charaNum)
        assertEquals(67L, f.affairTarget)
    }

    @Test
    fun `独处误报形态——MAIN_MAP 非 0 时空角色全被判成约会中`() {
        val s = store().declareMainMap(1L)
        repeat(4) { s.characters.addBare() }
        s.dating(2, 1L) // 唯一"确实没被误判"的角色：值正等于 MAIN_MAP
        val f = datingForensics(s, tw)
        assertEquals(3, f.mismatchCount)
        assertEquals(listOf("0:0", "1:0", "3:0"), f.mismatchDetails)
        assertFalse(f.isEmpty)
    }

    @Test
    fun `明细截断到 12 条而计数不截断`() {
        val s = store().declareMainMap(9L)
        repeat(20) { s.characters.addBare() }
        val f = datingForensics(s, tw)
        assertEquals(20, f.mismatchCount)
        assertEquals(tw.mismatchDetailLimit, f.mismatchDetails?.size)
        assertEquals("0:0", f.mismatchDetails?.first())
        assertEquals("11:0", f.mismatchDetails?.last())
    }

    @Test
    fun `4981 口径 GMF 关——家MAP等于MAIN_MAP、两口径同数`() {
        val s = store().declareMainMap(0L).declareGmf(0L, 3L)
        repeat(2) { s.characters.addBare() }
        s.dating(0, 5L)
        val f = datingForensics(s, tw)
        assertEquals(0L, f.gmfMapBit)
        assertEquals(3L, f.gmfHomeMap)
        assertEquals(0L, f.homeMap, "GMF 关 ⇒ GET_HOME_MAP() 走 MAIN_MAP 分支（COMMON2.ERB:97-98）")
        assertEquals(1, f.homeMismatchCount)
        assertEquals(listOf("0:5"), f.homeMismatchDetails)
        assertEquals(f.mismatchCount, f.homeMismatchCount, "基准相同 ⇒ 两口径必然同数")
    }

    @Test
    fun `4981 口径 GMF 开——家MAP取GMF_HOME_MAP、两口径分叉`() {
        val s = store().declareMainMap(0L).declareGmf(1L, 3L)
        repeat(2) { s.characters.addBare() }
        s.dating(0, 0L)
        s.dating(1, 3L)
        val f = datingForensics(s, tw)
        assertEquals(1L, f.gmfMapBit)
        assertEquals(3L, f.homeMap)
        assertEquals(1, f.homeMismatchCount)
        assertEquals(listOf("0:0"), f.homeMismatchDetails)
        assertEquals(1, f.mismatchCount, "4.980 口径基准 MAIN_MAP=0 ⇒ 计的是 chara1")
        assertEquals(listOf("1:3"), f.mismatchDetails)
    }

    @Test
    fun `GMF 开而 GMF_HOME_MAP 未声明——4981 口径整段放弃、4980 照出`() {
        val s = store().declareMainMap(0L).declareGmf(1L, null)
        repeat(1) { s.characters.addBare() }
        val f = datingForensics(s, tw)
        assertEquals(1L, f.gmfMapBit)
        assertNull(f.gmfHomeMap)
        assertNull(f.homeMap)
        assertNull(f.homeMismatchCount)
        assertNull(f.homeMismatchDetails)
        assertEquals(0, f.mismatchCount, "4.980 口径不受 4.981 基准缺席影响")
    }

    @Test
    fun `本条反馈现场——道中 97xx、同行 60、对象读数同步带回`() {
        val s = store().declareMainMap(0L)
        repeat(3) { s.characters.addBare() }
        s.writeInt("MASTER", longArrayOf(0), 0L)
        s.writeInt("CFLAG", longArrayOf(0, tw.cflagPosition!!), 9701L)
        s.writeInt("CFLAG", longArrayOf(0, tw.cflagTogether!!), 60L)
        s.writeInt("CFLAG", longArrayOf(0, tw.cflagDating!!), 1L)
        s.writeInt("TFLAG", longArrayOf(tw.tflagDateTravel!!), 250L)
        s.writeInt("FLAG", longArrayOf(tw.flagAffairTarget!!), 1L)
        s.writeInt("CFLAG", longArrayOf(1, tw.cflagPosition!!), 9701L)
        s.writeInt("CFLAG", longArrayOf(1, tw.cflagTogether!!), 60L)
        s.writeInt("CFLAG", longArrayOf(1, tw.cflagDating!!), 0L)
        val f = datingForensics(s, tw)
        assertEquals(9701L, f.masterPos)
        assertEquals(60L, f.masterTogether)
        assertEquals(250L, f.dateTravel)
        assertEquals(1L, f.affairTarget)
        assertEquals(0L, f.targetDate)
        assertEquals(9701L, f.targetPos)
        assertEquals(60L, f.targetTogether)
        val line = datingForensics(s, tw).render(twLabels)
        assertTrue("MASTER现在位置=9701" in line)
        assertTrue("对象现在位置=9701" in line)
        assertTrue("MASTER同行=60" in line)
        assertTrue("对象同行=60" in line)
        assertTrue("约会道中=250" in line)
    }

    @Test
    fun `约会的对象为负——对象读数一格不读、不抛`() {
        val s = store().declareMainMap(0L)
        repeat(2) { s.characters.addBare() }
        s.writeInt("FLAG", longArrayOf(tw.flagAffairTarget!!), -1L)
        val f = datingForensics(s, tw)
        assertEquals(-1L, f.affairTarget)
        assertNull(f.targetDate)
        assertNull(f.targetPos)
        assertNull(f.targetTogether)
    }

    @Test
    fun `MASTER 取角色指针——负指针降级、正指针按号读`() {
        val s = store().declareMainMap(0L)
        repeat(3) { s.characters.addBare() }
        s.dating(1, 5L)
        s.writeInt("CFLAG", longArrayOf(1, tw.cflagPosition!!), 97L)
        s.writeInt("MASTER", longArrayOf(0), -1L)
        assertNull(datingForensics(s, tw).masterDate, "MASTER=-1（无主人/数组禁用）时不读 CFLAG")
        assertNull(datingForensics(s, tw).masterPos)
        s.writeInt("MASTER", longArrayOf(0), 1L)
        assertEquals(5L, datingForensics(s, tw).masterDate)
        assertEquals(97L, datingForensics(s, tw).masterPos)
    }

    @Test
    fun `路人子双半闸——RANDOM_CHARANUM 驱动 TALENT 与 bit9`() {
        val s = store().declareMainMap(0L).declareRandomCharaNum(2L)
        repeat(3) { s.characters.addBare() }
        s.writeInt("TALENT", longArrayOf(2, tw.talentMobgirl!!), 1L)
        s.writeInt("TFLAG", longArrayOf(tw.tflagOncePerDay!!), 1L shl tw.forgetBit)
        val f = datingForensics(s, tw)
        assertEquals(2L, f.randomCharaNum)
        assertEquals(1L, f.mobTalent)
        assertEquals(1L, f.forgetBit)
        s.writeInt("TFLAG", longArrayOf(tw.tflagOncePerDay!!), 1L shl 8)
        assertEquals(0L, datingForensics(s, tw).forgetBit)
    }

    @Test
    fun `路人号越界时天赋一格读不到——不抛、其余照旧`() {
        val s = store().declareRandomCharaNum(500L)
        repeat(2) { s.characters.addBare() }
        val f = datingForensics(s, tw)
        assertEquals(500L, f.randomCharaNum)
        assertNull(f.mobTalent)
    }

    @Test
    fun `render 单行——缺的字段不出现`() {
        val s = store().declareMainMap(1L).declareGmf(1L, 1L)
        repeat(2) { s.characters.addBare() }
        s.dating(0, 3L)
        s.writeInt("MASTER", longArrayOf(0), 0L)
        val full = datingForensics(s, tw).render(twLabels)
        assertEquals(
            "约会现场：MAIN_MAP=1 GMF位=1 GMF家MAP=1 家MAP=1 " +
                "约会中≠MAIN_MAP=2/2 明细[0:3,1:0] 约会中≠家MAP=2/2 家明细[0:3,1:0] " +
                "约会道中=0 MASTER约会中=3 MASTER现在位置=0 MASTER同行=0 " +
                "约会的对象=0 对象约会中=3 对象现在位置=0 对象同行=0 忘记位=0",
            full,
        )

        val sparse = DatingForensics(
            mainMap = null,
            charaNum = 0,
            mismatchCount = null,
            mismatchDetails = null,
            gmfMapBit = null,
            gmfHomeMap = null,
            homeMap = null,
            homeMismatchCount = null,
            homeMismatchDetails = null,
            dateTravel = null,
            masterDate = null,
            masterPos = null,
            masterTogether = null,
            affairTarget = null,
            targetDate = null,
            targetPos = null,
            targetTogether = null,
            randomCharaNum = 7L,
            mobTalent = 2L,
            forgetBit = 0L,
        ).render(twLabels)
        assertEquals("约会现场：路人号=7 路人天赋=2 忘记位=0", sparse)
    }

    @Test
    fun `render 无参默认标签中性——core 不含 eraTW 中文文案`() {
        val s = store().declareMainMap(1L)
        repeat(2) { s.characters.addBare() }
        s.dating(0, 3L)
        val neutral = datingForensics(s, tw).render()
        assertTrue(neutral.startsWith("field:"), "无参渲染用中性默认标签，非 eraTW 文案")
        assertFalse("约会" in neutral, "core 默认渲染不得含 eraTW 中文业务词")
        assertFalse("MAIN_MAP" in neutral, "core 默认渲染不得含 eraTW 语料变量名")
        assertTrue("mainMap=1" in neutral)
    }
}
