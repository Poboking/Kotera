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
 * 断言的是**观察口自己的形状**，不是语料行为：
 * - 4.980 判式 `CFLAG:no:约会中 != MAIN_MAP` 逐字复刻 `情事発覚.ERB:475` +
 *   `物件関連/COMMON2.ERB:15-18`（取证见 BOARD），所以「MAIN_MAP=1、无人真约会 ⇒ 全员计入」
 *   这条用例是**故意**复刻官方的过判（ADR-005 不修）。
 * - 4.981 判式 `CFLAG:no:约会中 != GET_HOME_MAP()` 逐字复刻 4.981 `物件関連/COMMON2.ERB:11-18`
 *   与 `:92-99`（`GMF_GETBIT("幻想乡大地图")` 开→`GMF_HOME_MAP`、关→`MAIN_MAP`），
 *   「同一份 CFLAG 两口径出不同计数」的反向证明锁住"基准没跟语料走"这类漂移。
 * - 道中/同行/现在位置字段服务 2026-11-14 反馈「起床被约去约会 → 人消失」（见类头第三节）。
 * 裸 store 无 CSV：全部走数字元素号（12 / 300 / 320 / 73 / 229 / 199 / 300），出处为 AutoConst 头。
 */
class DatingForensicsTest {

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
        writeInt("CFLAG", longArrayOf(no.toLong(), DatingForensics.CFLAG_DATING), value)

    @Test
    fun `裸 store 不抛——语料侧 #DIM 缺席即整行无信息量`() {
        val f = datingForensics(store())
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
        s.writeInt("FLAG", longArrayOf(DatingForensics.FLAG_AFFAIR_TARGET), 67L)
        val f = datingForensics(s)
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
        val f = datingForensics(s)
        assertEquals(3, f.mismatchCount)
        assertEquals(listOf("0:0", "1:0", "3:0"), f.mismatchDetails)
        assertFalse(f.isEmpty)
    }

    @Test
    fun `明细截断到 12 条而计数不截断`() {
        val s = store().declareMainMap(9L)
        repeat(20) { s.characters.addBare() }
        val f = datingForensics(s)
        assertEquals(20, f.mismatchCount)
        assertEquals(DatingForensics.MISMATCH_DETAIL_LIMIT, f.mismatchDetails?.size)
        assertEquals("0:0", f.mismatchDetails?.first())
        assertEquals("11:0", f.mismatchDetails?.last())
    }

    @Test
    fun `4981 口径 GMF 关——家MAP等于MAIN_MAP、两口径同数`() {
        val s = store().declareMainMap(0L).declareGmf(0L, 3L)
        repeat(2) { s.characters.addBare() }
        s.dating(0, 5L)
        val f = datingForensics(s)
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
        // chara0 约会中=0（==MAIN_MAP、≠家MAP）⇒ 4.981 计入而 4.980 不计；
        // chara1 约会中=3（==家MAP、≠MAIN_MAP）⇒ 4.980 计入而 4.981 不计——
        // 两口径在同一现场各计一条、明细互斥（反向证明：摘掉 GMF 基准即退化同数）
        s.dating(0, 0L)
        s.dating(1, 3L)
        val f = datingForensics(s)
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
        val f = datingForensics(s)
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
        // MASTER 道中形态（SET_CMN.ERB:268/331/情事発覚.ERB:682）：现在位置=ROAD_TO=9701、同行=60
        s.writeInt("CFLAG", longArrayOf(0, DatingForensics.CFLAG_POSITION), 9701L)
        s.writeInt("CFLAG", longArrayOf(0, DatingForensics.CFLAG_TOGETHER), 60L)
        s.writeInt("CFLAG", longArrayOf(0, DatingForensics.CFLAG_DATING), 1L)
        s.writeInt("TFLAG", longArrayOf(DatingForensics.TFLAG_DATE_TRAVEL), 250L)
        s.writeInt("FLAG", longArrayOf(DatingForensics.FLAG_AFFAIR_TARGET), 1L)
        // 对象（chara1）应与 MASTER 同步（SET_CMN.ERB:332/情事発覚.ERB:683）
        s.writeInt("CFLAG", longArrayOf(1, DatingForensics.CFLAG_POSITION), 9701L)
        s.writeInt("CFLAG", longArrayOf(1, DatingForensics.CFLAG_TOGETHER), 60L)
        s.writeInt("CFLAG", longArrayOf(1, DatingForensics.CFLAG_DATING), 0L)
        val f = datingForensics(s)
        assertEquals(9701L, f.masterPos)
        assertEquals(60L, f.masterTogether)
        assertEquals(250L, f.dateTravel)
        assertEquals(1L, f.affairTarget)
        assertEquals(0L, f.targetDate)
        assertEquals(9701L, f.targetPos)
        assertEquals(60L, f.targetTogether)
        // 渲染行：这些字段就是玩家下次报告里要找的定性现场
        val line = datingForensics(s).render()
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
        s.writeInt("FLAG", longArrayOf(DatingForensics.FLAG_AFFAIR_TARGET), -1L)
        val f = datingForensics(s)
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
        s.writeInt("CFLAG", longArrayOf(1, DatingForensics.CFLAG_POSITION), 97L)
        s.writeInt("MASTER", longArrayOf(0), -1L)
        assertNull(datingForensics(s).masterDate, "MASTER=-1（无主人/数组禁用）时不读 CFLAG")
        assertNull(datingForensics(s).masterPos)
        s.writeInt("MASTER", longArrayOf(0), 1L)
        assertEquals(5L, datingForensics(s).masterDate)
        assertEquals(97L, datingForensics(s).masterPos)
    }

    @Test
    fun `路人子双半闸——RANDOM_CHARANUM 驱动 TALENT 与 bit9`() {
        val s = store().declareMainMap(0L).declareRandomCharaNum(2L)
        repeat(3) { s.characters.addBare() }
        s.writeInt("TALENT", longArrayOf(2, DatingForensics.TALENT_MOBGIRL), 1L)
        s.writeInt("TFLAG", longArrayOf(DatingForensics.TFLAG_ONCE_PER_DAY), 1L shl DatingForensics.FORGET_BIT)
        val f = datingForensics(s)
        assertEquals(2L, f.randomCharaNum)
        assertEquals(1L, f.mobTalent)
        assertEquals(1L, f.forgetBit)
        // bit8 不算：GETBIT 只取第 9 位（spec: FunctionMethodCreator.cs:4766-4791 的 shr/and 式）
        s.writeInt("TFLAG", longArrayOf(DatingForensics.TFLAG_ONCE_PER_DAY), 1L shl 8)
        assertEquals(0L, datingForensics(s).forgetBit)
    }

    @Test
    fun `路人号越界时天赋一格读不到——不抛、其余照旧`() {
        val s = store().declareRandomCharaNum(500L)
        repeat(2) { s.characters.addBare() }
        val f = datingForensics(s)
        assertEquals(500L, f.randomCharaNum)
        assertNull(f.mobTalent)
    }

    @Test
    fun `render 单行——缺的字段不出现`() {
        val s = store().declareMainMap(1L).declareGmf(1L, 1L)
        repeat(2) { s.characters.addBare() }
        s.dating(0, 3L)
        s.writeInt("MASTER", longArrayOf(0), 0L)
        val full = datingForensics(s).render()
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
        ).render()
        assertEquals("约会现场：路人号=7 路人天赋=2 忘记位=0", sparse)
    }
}
