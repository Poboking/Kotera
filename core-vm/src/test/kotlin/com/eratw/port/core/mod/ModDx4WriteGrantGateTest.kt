package com.eratw.port.core.mod

import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.WriteGrantDeclaration
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.Storage
import com.eratw.port.core.vars.VarDef
import com.eratw.port.core.vars.VarType
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.DimType
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.GlobalDimDecl
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModApi
import com.eratw.port.modapi.RuntimeScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ModDX4-B1 受控写面定向钉（授权闸 + 转移边界排队）：
 *
 * - **反向证明②（契约判据）**：坏 grant（键不存在 / 类型不符 / 角色变量 / 不可寻址 / 常量）
 *   在 [ModDx4WriteGate.build] 装载期校验整链拒 —— 生产调用点把 IAE 交给 attach 的装配失败链；
 * - **反向证明③**：未声明键运行期写当场 IAE（调用点 fail-fast，文案对齐设置面口径）；
 * - 排队时序：边界前读不到、边界后读得到（[ModDx4GameStateWrite] 经 [RuntimeScheduler] FIFO，
 *   生产兑现点 = 页边界 drain 的同形先例）；
 * - 自家 global-dims 免授权；类型不符不隐式转。
 */
class ModDx4WriteGrantGateTest {

    /** 手工变量定义表（gate 的 defOf 权威；不需要真 VariableStore）。 */
    private class FakeDefs {
        val defs = LinkedHashMap<String, VarDef>()

        fun int(name: String, dims: IntArray = intArrayOf(), isConst: Boolean = false): FakeDefs {
            defs[name.uppercase()] = VarDef(
                name = name,
                code = 0,
                type = VarType.INT,
                storage = if (isConst) Storage.CSV_CONST else Storage.G_INT1,
                index = 0,
                dims = dims,
                isConst = isConst,
                isSavedata = true,
                isGlobal = true,
            )
            return this
        }

        fun str(name: String): FakeDefs {
            defs[name.uppercase()] = VarDef(
                name = name,
                code = 0,
                type = VarType.STR,
                storage = Storage.G_STR0,
                index = 0,
                dims = intArrayOf(),
                isConst = false,
                isSavedata = true,
                isGlobal = true,
            )
            return this
        }

        fun float(name: String): FakeDefs {
            defs[name.uppercase()] = VarDef(
                name = name,
                code = 0,
                type = VarType.FLOAT,
                storage = Storage.G_INT1,
                index = 0,
                dims = intArrayOf(),
                isConst = false,
                isSavedata = true,
                isGlobal = true,
            )
            return this
        }

        fun chara(name: String): FakeDefs {
            defs[name.uppercase()] = VarDef(
                name = name,
                code = 0,
                type = VarType.INT,
                storage = Storage.C_INT1,
                index = 0,
                dims = intArrayOf(1),
                isConst = false,
                isSavedata = false,
                isGlobal = false,
            )
            return this
        }

        val lookup: (String) -> VarDef? = { name -> defs[name.trim().uppercase()] }
    }

    private fun emptySnapshot(): com.eratw.port.core.patch.DeclarationSnapshot =
        DeclarationRegistry().freeze()

    private fun snapshotWithOwnDim(packageId: String, name: String, dims: List<Int> = emptyList()):
        com.eratw.port.core.patch.DeclarationSnapshot {
        val registry = DeclarationRegistry()
        registry.addPackage(
            ModDeclarationAdapter.toDescriptor(
                ModManifest(
                    packageId = packageId,
                    globalDims = listOf(GlobalDimDecl(name, DimType.INT, dims)),
                ),
            ),
            packageId,
        )
        return registry.freeze()
    }

    // -----------------------------------------------------------------------
    // 反向证明②：坏 grant 装载期校验整链拒
    // -----------------------------------------------------------------------
    @Test
    fun nonexistentGrantKeyRejectedAtBuild() {
        val defs = FakeDefs().int("DAY")
        val ex = assertThrows<IllegalArgumentException> {
            ModDx4WriteGate.build(
                emptySnapshot(),
                mapOf("test.mod" to listOf(WriteGrantDeclaration("NOT_THERE"))),
                defs.lookup,
            )
        }
        assertTrue(ex.message!!.contains("write-grants 键不存在：test.mod/NOT_THERE"), ex.message)
    }

    @Test
    fun typeMismatchGrantRejectedAtBuild() {
        val defs = FakeDefs().int("DAY").str("PLACE")
        val intGrantOnStr = assertThrows<IllegalArgumentException> {
            ModDx4WriteGate.build(
                emptySnapshot(),
                mapOf("test.mod" to listOf(WriteGrantDeclaration("PLACE", isStr = false))),
                defs.lookup,
            )
        }
        assertTrue(intGrantOnStr.message!!.contains("类型不符"), intGrantOnStr.message)
        val strGrantOnInt = assertThrows<IllegalArgumentException> {
            ModDx4WriteGate.build(
                emptySnapshot(),
                mapOf("test.mod" to listOf(WriteGrantDeclaration("DAY", isStr = true))),
                defs.lookup,
            )
        }
        assertTrue(strGrantOnInt.message!!.contains("类型不符"), strGrantOnInt.message)
    }

    @Test
    fun floatConstCharaAndUnaddressableGrantKeysRejectedAtBuild() {
        val defs = FakeDefs().float("FPS").int("GAMEBASE_AUTHOR", isConst = true)
            .chara("ABL").int("TRAIN", dims = intArrayOf(10, 10))
        for (bad in listOf("FPS", "GAMEBASE_AUTHOR", "ABL", "TRAIN")) {
            val ex = assertThrows<IllegalArgumentException> {
                ModDx4WriteGate.build(
                    emptySnapshot(),
                    mapOf("test.mod" to listOf(WriteGrantDeclaration(bad))),
                    defs.lookup,
                )
            }
            assertTrue(
                ex.message!!.contains("整链拒"),
                "$bad: ${ex.message}",
            )
        }
    }

    // -----------------------------------------------------------------------
    // 反向证明③：未声明键运行期写当场 IAE（调用点 fail-fast）
    // -----------------------------------------------------------------------
    @Test
    fun undeclaredKeyWriteThrowsIaeAtCallSite() {
        val gate = ModDx4WriteGate.build(
            emptySnapshot(),
            mapOf("test.mod" to listOf(WriteGrantDeclaration("DAY"))),
            FakeDefs().int("DAY").int("MONEY").lookup,
        )
        val ex = assertThrows<IllegalArgumentException> { gate.authorize("test.mod", "MONEY", wantStr = false) }
        assertTrue(ex.message!!.contains("全局变量写未授权：test.mod/MONEY"), ex.message)
        // 授权键放行。
        gate.authorize("test.mod", "DAY", wantStr = false)
    }

    @Test
    fun grantTypeMismatchRejectedAtAuthorizeWithoutImplicitConversion() {
        val gate = ModDx4WriteGate.build(
            emptySnapshot(),
            mapOf("test.mod" to listOf(WriteGrantDeclaration("DAY"))),
            FakeDefs().int("DAY").lookup,
        )
        val ex = assertThrows<IllegalArgumentException> { gate.authorize("test.mod", "day", wantStr = true) }
        assertTrue(ex.message!!.contains("全局变量写类型不符：test.mod/day"), ex.message)
        // 大小写归一寻址：授权键按原文写放行（IGNORE CASE 口径）。
        gate.authorize("test.mod", "day", wantStr = false)
    }

    @Test
    fun ownGlobalDimsAreExemptButUnaddressableOwnDimIsRejected() {
        val gate = ModDx4WriteGate.build(
            snapshotWithOwnDim("test.mod", "MOD_STATE"),
            emptyMap(),
            FakeDefs().lookup,
        )
        // 自家声明变量免授权。
        gate.authorize("test.mod", "mod_state", wantStr = false)
        // 别的包的自家变量不可写（包隔离）。
        val foreign = assertThrows<IllegalArgumentException> {
            gate.authorize("other.mod", "MOD_STATE", wantStr = false)
        }
        assertTrue(foreign.message!!.contains("全局变量写未授权：other.mod/MOD_STATE"), foreign.message)
        // 2 维以上自家变量不可寻址（专属文案，与「未授权」可区分）。
        val wide = ModDx4WriteGate.build(
            snapshotWithOwnDim("test.mod", "MATRIX", dims = listOf(10, 10)),
            emptyMap(),
            FakeDefs().lookup,
        )
        val ex = assertThrows<IllegalArgumentException> { wide.authorize("test.mod", "MATRIX", wantStr = false) }
        assertTrue(ex.message!!.contains("全局变量写不可寻址：test.mod/MATRIX"), ex.message)
    }

    // -----------------------------------------------------------------------
    // 排队时序：边界前读不到、边界后读得到（真 VariableStore + 宿主 install 全链）
    // -----------------------------------------------------------------------
    private class BoundaryScheduler : RuntimeScheduler {
        private val pending = ArrayDeque<() -> Unit>()

        override val isOnMainThread: Boolean get() = true

        override fun submit(action: () -> Unit) {
            pending.add(action)
        }

        fun pendingCount(): Int = pending.size

        fun drain() {
            while (pending.isNotEmpty()) pending.removeFirst().invoke()
        }
    }

    private class WriteOnLoadMod(private val writes: (ModContext) -> Unit) : EraMod {
        override fun onLoad(context: ModContext) {
            writes(context)
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    /** 按 DAY grant + 真存储装配宿主（排队时序用例的公共底座）。 */
    private fun newHost(
        snapshot: com.eratw.port.core.patch.DeclarationSnapshot,
        store: VariableStore,
        scheduler: BoundaryScheduler,
        onLoadHook: (ModContext) -> Unit,
    ): ModRuntimeHost {
        val gate = ModDx4WriteGate.build(
            snapshot,
            mapOf("test.mod" to listOf(WriteGrantDeclaration("DAY"))),
            defOf = { name -> store.registry.def(name) },
        )
        val applier = ModDx4WriteApplier(
            gate = gate,
            applyInt = { name, value -> writeInt(store, name, value) },
            applyStr = { name, value -> writeStr(store, name, value) },
        )
        val host = ModRuntimeHost(
            snapshot = snapshot,
            entityKernel = com.eratw.port.core.entity.EntityKernel(
                schemas = emptyList(),
                relationshipSchemas = emptyList(),
                archetypes = emptyList(),
            ),
            nativeMethods = com.eratw.port.core.fn.NativeMethodRegistry(),
            logSink = {},
            runtime = scheduler,
        )
        host.gameStateWriteApplier = applier
        val report = host.install(listOf("test.mod" to WriteOnLoadMod(onLoadHook)))
        assertTrue(report.single().active, "install 应成功：${report.single().failure}")
        return host
    }

    private fun writeInt(store: VariableStore, name: String, value: Long) {
        val def = checkNotNull(store.registry.def(name))
        val indices = if (def.argCount == 0) LongArray(0) else longArrayOf(0L)
        store.writeInt(name, indices, value)
    }

    private fun writeStr(store: VariableStore, name: String, value: String) {
        val def = checkNotNull(store.registry.def(name))
        val indices = if (def.argCount == 0) LongArray(0) else longArrayOf(0L)
        store.writeStr(name, indices, value)
    }

    private fun dayIndicesOf(store: VariableStore): LongArray =
        if (checkNotNull(store.registry.def("DAY")).argCount == 0) LongArray(0) else longArrayOf(0L)

    @Test
    fun queuedWriteIsInvisibleBeforeBoundaryAndVisibleAfter() {
        val store = VariableStore()
        val scheduler = BoundaryScheduler()
        newHost(snapshotWithOwnDim("test.mod", "MOD_STATE"), store, scheduler) { ctx ->
            ctx.gameState.setIntVar("DAY", 42L)
        }
        // 边界前：队列挂着一笔，存储未变。
        assertEquals(1, scheduler.pendingCount())
        val dayIndices = dayIndicesOf(store)
        assertEquals(0L, store.readInt("DAY", dayIndices))
        // 边界（drain = 生产 onTurnBoundary 第①步的同形）：写兑现。
        scheduler.drain()
        assertEquals(42L, store.readInt("DAY", dayIndices))
    }

    @Test
    fun undeclaredKeyWriteThrowsIaeAtCallSiteWithoutQueueing() {
        val store = VariableStore()
        val scheduler = BoundaryScheduler()
        var sawIae: IllegalArgumentException? = null
        newHost(snapshotWithOwnDim("test.mod", "MOD_STATE"), store, scheduler) { ctx ->
            try {
                ctx.gameState.setIntVar("NOT_GRANTED", 1L)
            } catch (e: IllegalArgumentException) {
                sawIae = e
            }
        }
        // 未声明键：调用点当场 IAE（fail-fast），不进队列、不落库。
        assertTrue(sawIae!!.message!!.contains("全局变量写未授权：test.mod/NOT_GRANTED"), sawIae!!.message)
        assertEquals(0, scheduler.pendingCount())
    }
}
