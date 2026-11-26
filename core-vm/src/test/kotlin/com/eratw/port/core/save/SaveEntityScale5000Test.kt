package com.eratw.port.core.save

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityIdCodec
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.EntityLifecycle
import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.mod.KernelEntityApi
import com.eratw.port.modapi.EntityId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M4-3 判据「5000 实体基准」的规模验证面（CONTRACT-RT2-C-RES2 §3-a）。
 *
 * 钉的是「跨包实体图经 [SaveService.captureBundle] → 容器字节 → 新内核 [SaveService.restoreBundle]
 * 之后逐值无损」：
 * - ≥5000 实体、跨 ≥2 包：mod.alpha 与 mod.beta 的 pkgShortId 分别是 2 与 3，用
 *   [EntityIdCodec.pkgShortIdOf] 直读位域证明「pkgShortId 不同」；
 * - ≥3 类组件（Vitals / Links / Notes），其中 Links 的 linkRef 字段声明 `isEntityRef = true`，
 *   写与读全程走 [KernelEntityApi.setRef] 与 [KernelEntityApi.getRef]（mod-api 类型化引用口，
 *   底下同一张 INT 列）；
 * - 恢复后**每实体每组件逐字段**对拍：全部 5200 实体全量循环比对（含 EntityRef 的位域值），
 *   并对关系边的出边、入边端点与边值全量对拍 —— 不许抽样，本文件就是那条不许的执行面；
 * - 孤儿片与激活片经容器字节逐字节透传（载荷含 0x00、0xFF、0x0D、0x0A 等会被文本层改写的字节）；
 * - 注册表快照随档：短 ID、孤儿锁定与分配水位一并还原。
 *
 * 装置先例 = SaveServiceTest 的 fixture()（空投射器形状同 SidecarPairSaveTest.EmptyProjector）。
 * 数据全部由纯算术公式确定性生成（核心层禁 kotlin.random，AGENTS §2 铁律 4，测试代码同适用）。
 * 耗时与实体数读数只记账（println 取证面），不设耗时硬门槛（契约 §3-a：只记账）。
 */
class SaveEntityScale5000Test {

    /** mod.alpha 的实体数；与 betaCount 合计 5200 ≥ 5000（契约 §3-a 下限）。 */
    private val alphaCount = 2600

    /** mod.beta 的实体数（与 alpha 不同包 ⇒ pkgShortId 位域段不同）。 */
    private val betaCount = 2600

    /** 空投射器：基准面不管角色槽（脱水序判据归 SaveServiceTest），projectedEntities 恒空。 */
    private object EmptyScaleProjector : ICharaSlotProjector {
        override fun project(entityId: Long): Int = -1
        override fun unproject(entityId: Long): Boolean = false
        override fun charIndexOf(entityId: Long): Int = -1
        override val rosterGeneration: Long get() = 0L
        override fun charIndexOfChecked(entityId: Long, expectedGeneration: Long): Int = -1
        override fun entityAt(charIndex: Int): Long = EntityIdCodec.NULL
        override fun projectedEntities(): LongArray = LongArray(0)
        override fun syncFromCharaSlot(entityId: Long) = Unit
        override fun onCharacterRowsPermuted(oldToNewIndexMap: IntArray) = Unit
        override fun onCharacterRowDeleted(deletedIndex: Int) = Unit
        override fun onWorldReset() = Unit
    }

    private class ScaleFixture(
        val kernel: EntityKernel,
        val reg: PackageShortIdRegistry,
        val svc: SaveService,
        val apiAlpha: KernelEntityApi,
        val apiBeta: KernelEntityApi,
    )

    private fun fixture(): ScaleFixture {
        val schemas = listOf(
            ComponentSchema(
                "mod.alpha",
                "Vitals",
                listOf(
                    ComponentFieldSpec("level"),
                    ComponentFieldSpec("hp"),
                    ComponentFieldSpec("name", ComponentFieldType.STR),
                ),
            ),
            ComponentSchema(
                "mod.alpha",
                "Links",
                listOf(
                    ComponentFieldSpec("linkRef", ComponentFieldType.INT, isEntityRef = true),
                    ComponentFieldSpec("rank"),
                ),
            ),
            ComponentSchema(
                "mod.beta",
                "Notes",
                listOf(
                    ComponentFieldSpec("memo", ComponentFieldType.STR),
                    ComponentFieldSpec("stamp"),
                ),
            ),
        )
        val relations = listOf(RelationshipSchema("LINK"))
        val archetypes = listOf(EntityArchetype("SUBJECT", listOf("Vitals", "Links", "Notes")))
        val kernel = EntityKernel(schemas, relations, archetypes, 8192)
        val reg = PackageShortIdRegistry()
        val alphaShortId = reg.allocate("mod.alpha") // 2（1 号留给内核包）
        val betaShortId = reg.allocate("mod.beta") // 3
        val orphanShortId = reg.allocate("legacy.mod") // 4
        reg.lock(orphanShortId) // 孤儿片仍在盘上 ⇒ 短 ID 锁定（SaveServiceTest.fixture 同口径）
        return ScaleFixture(
            kernel,
            reg,
            SaveService(kernel, EmptyScaleProjector, reg),
            KernelEntityApi(kernel, alphaShortId),
            KernelEntityApi(kernel, betaShortId),
        )
    }

    /** 纯算术确定性字段值；Vitals/Links/Notes 三类组件在**每个**实体上都齐。 */
    private fun expectedLevel(i: Int): Long = (i * 31 % 1000) + 1L

    private fun expectedHp(i: Int): Long = (i * 7 % 500) + 1L

    private fun expectedRank(i: Int): Long = (i % 10) + 1L

    private fun expectedStamp(i: Int): Long = (i * 13 % 97) + 1L

    private fun expectedName(i: Int): String = "entity-$i"

    private fun expectedMemo(i: Int): String = "记录-$i"

    /** 建 alpha（包 2）与 beta（包 3）实体，写全字段、EntityRef 与关系边；返回两组 EntityId。 */
    private fun buildEntities(f: ScaleFixture): Pair<LongArray, LongArray> {
        val alphaIds = LongArray(alphaCount)
        for (i in 0 until alphaCount) {
            val id = f.kernel.create(2, "SUBJECT")
            val eid = EntityId(id)
            assertTrue(f.apiAlpha.setInt(eid, "level", expectedLevel(i)))
            assertTrue(f.apiAlpha.setInt(eid, "hp", expectedHp(i)))
            assertTrue(f.apiAlpha.setStr(eid, "name", expectedName(i)))
            assertTrue(f.apiAlpha.setInt(eid, "rank", expectedRank(i)))
            assertTrue(f.apiAlpha.setInt(eid, "stamp", expectedStamp(i)))
            assertTrue(f.apiAlpha.setStr(eid, "memo", expectedMemo(i)))
            alphaIds[i] = id
        }
        // 链内 EntityRef（首者 NULL）：走 setRef 写 —— 类型化引用口是契约 §3-a 点名的访问面。
        for (i in 1 until alphaCount) {
            assertTrue(f.apiAlpha.setRef(EntityId(alphaIds[i]), "linkRef", EntityId(alphaIds[i - 1])))
        }
        val betaIds = LongArray(betaCount)
        for (j in 0 until betaCount) {
            val id = f.kernel.create(3, "SUBJECT")
            val eid = EntityId(id)
            assertTrue(f.apiBeta.setInt(eid, "level", expectedLevel(j)))
            assertTrue(f.apiBeta.setInt(eid, "hp", expectedHp(j)))
            assertTrue(f.apiBeta.setStr(eid, "name", expectedName(j)))
            assertTrue(f.apiBeta.setInt(eid, "rank", expectedRank(j)))
            assertTrue(f.apiBeta.setInt(eid, "stamp", expectedStamp(j)))
            assertTrue(f.apiBeta.setStr(eid, "memo", expectedMemo(j)))
            betaIds[j] = id
        }
        // 跨包 EntityRef：beta 的 linkRef 指向 alpha —— 引用位域里带着**别的包**的 pkg 段。
        for (j in 0 until betaCount) {
            assertTrue(f.apiBeta.setRef(EntityId(betaIds[j]), "linkRef", EntityId(alphaIds[j % alphaCount])))
        }
        // 关系边：alpha 链边（值随边持久化）+ beta→alpha 跨包边。先链后跨 ⇒ 入边序可推。
        for (i in 1 until alphaCount) {
            assertTrue(f.kernel.addRelation(alphaIds[i], "LINK", alphaIds[i - 1]))
            assertTrue(f.kernel.setRelationValue(alphaIds[i], "LINK", alphaIds[i - 1], i * 3L + 1))
        }
        for (j in 0 until betaCount) {
            assertTrue(f.kernel.addRelation(betaIds[j], "LINK", alphaIds[j % alphaCount]))
            assertTrue(f.kernel.setRelationValue(betaIds[j], "LINK", alphaIds[j % alphaCount], j * 5L + 2))
        }
        return alphaIds to betaIds
    }

    /** 逐字段对拍一个实体的三类组件（Vitals/Links/Notes 的标量字段 + 三态）。 */
    private fun compareScalarFields(kernel: EntityKernel, id: Long, i: Int, tag: String) {
        assertEquals(EntityLifecycle.ACTIVE, kernel.stateOf(id), "$tag stateOf")
        assertEquals(expectedLevel(i), kernel.getInt(id, "level"), "$tag level")
        assertEquals(expectedHp(i), kernel.getInt(id, "hp"), "$tag hp")
        assertEquals(expectedName(i), kernel.getStr(id, "name"), "$tag name")
        assertEquals(expectedRank(i), kernel.getInt(id, "rank"), "$tag rank")
        assertEquals(expectedStamp(i), kernel.getInt(id, "stamp"), "$tag stamp")
        assertEquals(expectedMemo(i), kernel.getStr(id, "memo"), "$tag memo")
    }

    /** capture→容器字节→新内核 restore 一次往返的产物（读数记账面 + 对拍面）。 */
    private class RoundTripResult(
        val decoded: SidecarSaveBundle,
        val dst: ScaleFixture,
        val containerByteSize: Int,
        val captureMs: Long,
        val restoreMs: Long,
    )

    /** captureBundle → encode → decode → 新内核/新服务 restoreBundle（读档后现场），带回读数。 */
    private fun captureThroughContainerBytes(
        src: ScaleFixture,
        binding: SidecarBinding,
        activeBytes: ByteArray,
        orphanBytes: ByteArray,
    ): RoundTripResult {
        val captureStart = System.nanoTime()
        val bundle = src.svc.captureBundle(
            binding,
            activeChunks = listOf(PackageChunk("mod.alpha", activeBytes)),
            orphanChunks = listOf(PackageChunk("legacy.mod", orphanBytes)),
        )
        val containerBytes = bundle.encode()
        val captureMs = (System.nanoTime() - captureStart) / 1_000_000

        // 容器字节往返 + 新内核/新服务 = 「读档后」现场（不是同一个内核里自导自演）。
        val decoded = SidecarSaveBundle.decode(containerBytes)
        val dst = fixture()
        val restoreStart = System.nanoTime()
        assertEquals(SaveService.RestoreOutcome.Applied, dst.svc.restoreBundle(decoded, binding))
        val restoreMs = (System.nanoTime() - restoreStart) / 1_000_000
        return RoundTripResult(decoded, dst, containerBytes.size, captureMs, restoreMs)
    }

    /** 全量对拍 mod.alpha 实体：标量字段 + 链内 EntityRef + 链边（出/入端点与值）。 */
    private fun compareAlphaEntities(dst: ScaleFixture, alphaIds: LongArray, betaIds: LongArray) {
        for (i in alphaIds.indices) {
            val id = alphaIds[i]
            assertTrue(dst.kernel.exists(id), "alpha i=$i 恢复后存在（全位域比对）")
            compareScalarFields(dst.kernel, id, i, "alpha i=$i")
            val expectedRef = if (i == 0) EntityId.NULL else EntityId(alphaIds[i - 1])
            assertEquals(expectedRef, dst.apiAlpha.getRef(EntityId(id), "linkRef"), "alpha i=$i 的 linkRef")
            val expectedOut = if (i == 0) LongArray(0) else longArrayOf(alphaIds[i - 1])
            assertContentEquals(
                expectedOut,
                dst.kernel.queryRelations(id, "LINK", outgoing = true),
                "alpha i=$i 出边",
            )
            // 入边序 = 插入序：链后继（若有）在前，beta 跨包边在后（j=i 唯一命中 alpha[i]）。
            val expectedIn = ArrayList<Long>(2)
            if (i + 1 < alphaCount) expectedIn.add(alphaIds[i + 1])
            expectedIn.add(betaIds[i])
            assertContentEquals(
                expectedIn.toLongArray(),
                dst.kernel.queryRelations(id, "LINK", outgoing = false),
                "alpha i=$i 入边",
            )
            if (i > 0) {
                assertEquals(
                    i * 3L + 1,
                    dst.kernel.getRelationValue(id, "LINK", alphaIds[i - 1]),
                    "alpha i=$i 链边值",
                )
            }
        }
    }

    /** 全量对拍 mod.beta 实体：标量字段 + 跨包 EntityRef + 跨包边（出/入端点与值）。 */
    private fun compareBetaEntities(dst: ScaleFixture, alphaIds: LongArray, betaIds: LongArray) {
        for (j in betaIds.indices) {
            val id = betaIds[j]
            assertTrue(dst.kernel.exists(id), "beta j=$j 恢复后存在（全位域比对）")
            compareScalarFields(dst.kernel, id, j, "beta j=$j")
            // 跨包 EntityRef：引用位域里带着 mod.alpha 的 pkg 段，恢复后逐位同值。
            assertEquals(
                EntityId(alphaIds[j % alphaCount]),
                dst.apiBeta.getRef(EntityId(id), "linkRef"),
                "beta j=$j 的 linkRef",
            )
            assertContentEquals(
                longArrayOf(alphaIds[j % alphaCount]),
                dst.kernel.queryRelations(id, "LINK", outgoing = true),
                "beta j=$j 出边",
            )
            assertContentEquals(
                LongArray(0),
                dst.kernel.queryRelations(id, "LINK", outgoing = false),
                "beta j=$j 入边",
            )
            assertEquals(
                j * 5L + 2,
                dst.kernel.getRelationValue(id, "LINK", alphaIds[j % alphaCount]),
                "beta j=$j 跨包边值",
            )
        }
    }

    /** 分片序与两组载荷逐字节透传 + 注册表短 ID/孤儿锁随档（契约 §3-a 的透传断言面）。 */
    private fun assertChunksAndRegistry(
        decoded: SidecarSaveBundle,
        dst: ScaleFixture,
        activeBytes: ByteArray,
        orphanBytes: ByteArray,
    ) {
        // 分片序 = 激活组在前、孤儿组在后；两组载荷逐字节透传。
        assertEquals(listOf("mod.alpha", "legacy.mod"), decoded.chunks.map { it.packageId }, "分片序")
        assertContentEquals(activeBytes, decoded.chunks[0].payload, "激活片逐字节透传")
        assertContentEquals(orphanBytes, decoded.chunks[1].payload, "孤儿片逐字节透传")
        // 注册表快照随档：短 ID、孤儿锁定一并还原。
        assertEquals(2, dst.reg.shortIdOf("mod.alpha"))
        assertEquals(3, dst.reg.shortIdOf("mod.beta"))
        assertEquals(4, dst.reg.shortIdOf("legacy.mod"))
        assertTrue(dst.reg.isLocked(4), "孤儿片随档 ⇒ 短 ID 锁定一并还原")
    }

    @Test
    fun `5200实体跨两包capture经容器字节restore后逐实体逐组件逐字段对拍`() {
        val src = fixture()
        val (alphaIds, betaIds) = buildEntities(src)
        val total = alphaIds.size + betaIds.size
        // 跨包判据直接落地：两包实体的 pkg 位域段不同（2 vs 3）。
        assertEquals(2, EntityIdCodec.pkgShortIdOf(alphaIds[0]), "mod.alpha 的 pkg 段")
        assertEquals(3, EntityIdCodec.pkgShortIdOf(betaIds[0]), "mod.beta 的 pkg 段")
        assertEquals(total, src.kernel.liveCount)
        assertEquals(total, src.kernel.query("SUBJECT", 0).size)

        // 孤儿片载荷带文本层会改写的控制字节（0x00/0xFF/0x0D/0x0A/0x1A），透传判据的试金石。
        val orphanBytes = byteArrayOf(0, -1, 127, -128, 13, 10, 26, 32, 7, 0, 9, -56)
        val activeBytes = byteArrayOf(1, 2, 3, 4, 5, -3, -4, 0)
        val binding = SidecarBinding(uniqueCode = 0x514CL, scriptVersion = 9L, saveTextHash = 4242)

        val round = captureThroughContainerBytes(src, binding, activeBytes, orphanBytes)

        // 身份面：全体 EntityId 数组逐位一致（pkg + generation + slot 全域）。
        assertContentEquals(
            src.kernel.query("SUBJECT", 0),
            round.dst.kernel.query("SUBJECT", 0),
            "全体 EntityId 数组逐位一致（含跨包）",
        )
        assertEquals(total, round.dst.kernel.liveCount)

        // ---- 全量对拍：每实体每组件逐字段（5200 实体 × 6 标量字段 + EntityRef + 边）----
        compareAlphaEntities(round.dst, alphaIds, betaIds)
        compareBetaEntities(round.dst, alphaIds, betaIds)

        assertChunksAndRegistry(round.decoded, round.dst, activeBytes, orphanBytes)

        // 耗时与实体数读数只记账（不设耗时硬门槛，契约 §3-a）。
        println(
            "C-RES2 基准读数: entities=$total packages=2 components=3 entityRefFields=1 " +
                "captureBytes=${round.containerByteSize} " +
                "captureMs=${round.captureMs} restoreMs=${round.restoreMs}",
        )
    }

    @Test
    fun `restore后注册表并集孤儿锁定与分配水位随档`() {
        val src = fixture()
        val e = src.kernel.create(2, "SUBJECT")
        assertTrue(src.apiAlpha.setInt(EntityId(e), "level", 5L))
        val binding = SidecarBinding(uniqueCode = 1L, scriptVersion = 1L, saveTextHash = 1)
        val bundle = src.svc.captureBundle(
            binding,
            orphanChunks = listOf(PackageChunk("legacy.mod", byteArrayOf(42, 0, 7))),
        )

        val dst = fixture()
        assertEquals(
            SaveService.RestoreOutcome.Applied,
            dst.svc.restoreBundle(SidecarSaveBundle.decode(bundle.encode()), binding),
        )
        assertEquals(4, dst.reg.count, "内核包 + alpha + beta + legacy")
        assertEquals(2, dst.reg.shortIdOf("mod.alpha"))
        assertEquals(3, dst.reg.shortIdOf("mod.beta"))
        assertEquals(4, dst.reg.shortIdOf("legacy.mod"))
        assertTrue(dst.reg.isLocked(4), "孤儿片仍在盘上 ⇒ 锁定旗标随快照并入")
        assertEquals(5, dst.reg.allocate("new.mod"), "nextId 水位随档：新分配不得撞已有短 ID")
        assertEquals(1, dst.kernel.liveCount, "实体图原样恢复")
        assertEquals(5L, dst.kernel.getInt(e, "level"), "字段值原样恢复")
    }
}
