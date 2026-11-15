package com.eratw.port.core.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M2-1 链接序钉（2026-11-12 B' 裁决后口径）。
 *
 * 链接序是链接规划层按「依赖约束 → 显式排序配置 → `packageId` 规范兜底」算出的规范顺序，
 * 与输入 List 的枚举顺序无关。硬依赖缺一拒绝、软提示可缺、依赖环整组拒绝；
 * ABI-8 缓存指纹要求**同一模块集合 + 同一声明 + 同一 LinkOrder** ⇒ 同一条序。
 *
 * 特别注意 [partialOrderDagIsPermutationInvariant]：只有「依赖边把全序钉死」的 DAG
 * 测试会掩盖 tie-break 的不确定性，必须补一条「有依赖边但仍是部分序」的 DAG。
 *
 * ## 2026-11-12 二审补充
 *
 * - [kahnReadyPriorityIsNotMinimalAdjustmentOfFullOrder] 冻结 [LinkOrder] 的语义：它是
 *   **就绪模块优先级**，不承诺对玩家完整排列做最小调整；反例必须逐条钉住。
 * - 排列不变性不再只挑 4 种手写排列：3/4 模块走 [PermutationFixtures.all] 穷举 6/24 种，
 *   大集合走固定种子洗牌；无依赖、非全序 DAG、显式排序配置三种形状各自穷举。
 */
class ModuleLinkOrderTest {

    private fun pkg(
        id: String,
        version: String = "",
        requires: List<String> = emptyList(),
        loadAfter: List<String> = emptyList(),
    ): ModuleDescriptor = ModuleDescriptor(
        packageId = id,
        version = version,
        requires = requires,
        loadAfter = loadAfter,
    )

    private fun ids(list: List<ModuleDescriptor>): List<String> = list.map { it.packageId }

    private fun pkgA(): ModuleDescriptor = pkg("a")
    private fun pkgM(): ModuleDescriptor = pkg("m")
    private fun pkgZ(): ModuleDescriptor = pkg("z")

    @Test
    fun hardDependenciesComeFirstRegardlessOfInputOrder() {
        val a = pkg("a")
        val b = pkg("b", requires = listOf("a"))
        val c = pkg("c", requires = listOf("b", "a"))
        // 反序输入也必须得到 a → b → c。
        assertEquals(listOf("a", "b", "c"), ids(ModuleLinkOrder.plan(listOf(c, b, a))))
        assertEquals(listOf("a", "b", "c"), ids(ModuleLinkOrder.plan(listOf(a, b, c))))
    }

    @Test
    fun missingLoadAfterTargetIsIgnored() {
        // b 在 a 后；c 的 loadAfter 指向不存在的包 ⇒ 忽略，不产生约束也不拒绝。
        // 其余模块走规范序：a → b（约束），c 无约束且按 packageId 排在 b 之后。
        val a = pkg("a")
        val b = pkg("b", loadAfter = listOf("a"))
        val c = pkg("c", loadAfter = listOf("missing"))
        assertEquals(listOf("a", "b", "c"), ids(ModuleLinkOrder.plan(listOf(b, a, c))))
        assertEquals(listOf("a", "b", "c"), ids(ModuleLinkOrder.plan(listOf(c, b, a))))
    }

    @Test
    fun explicitLoadAfterStillWinsOverCanonicalOrder() {
        // a 的 loadAfter 指向 z：即便 a 的 packageId 字典序更小，z 也必须先链接。
        val a = pkg("a", loadAfter = listOf("z"))
        val z = pkg("z")
        assertEquals(listOf("z", "a"), ids(ModuleLinkOrder.plan(listOf(a, z))))
        assertEquals(listOf("z", "a"), ids(ModuleLinkOrder.plan(listOf(z, a))))
    }

    @Test
    fun independentPackagesUseCanonicalOrder() {
        // 输入序不再是语义：无约束包按 packageId 升序兜底。
        val list = listOf(pkg("z"), pkg("a"), pkg("m"))
        assertEquals(listOf("a", "m", "z"), ids(ModuleLinkOrder.plan(list)))
        // 任意输入排列 ⇒ 同一条链接序（集合确定性，ABI-8 指纹前提）。
        assertEquals(listOf("a", "m", "z"), ids(ModuleLinkOrder.plan(listOf(pkgA(), pkgM(), pkgZ()))))
        assertEquals(listOf("a", "m", "z"), ids(ModuleLinkOrder.plan(listOf(pkgM(), pkgZ(), pkgA()))))
        assertEquals(listOf("a", "m", "z"), ids(ModuleLinkOrder.plan(listOf(pkgZ(), pkgM(), pkgA()))))
        // 重复调用结果逐项相同。
        assertEquals(ids(ModuleLinkOrder.plan(list)), ids(ModuleLinkOrder.plan(list)))
    }

    @Test
    fun preferredPackageIdsDefineExplicitOrder() {
        // 玩家 / 组合管理器显式排序：完整有序列表，不靠输入 List 的偶然排列。
        val order = LinkOrder(preferredPackageIds = listOf("z", "m", "a"))
        assertEquals(listOf("z", "m", "a"), ids(ModuleLinkOrder.plan(listOf(pkgZ(), pkgA(), pkgM()), order)))
        assertEquals(listOf("z", "m", "a"), ids(ModuleLinkOrder.plan(listOf(pkgM(), pkgZ(), pkgA()), order)))
        assertEquals(listOf("z", "m", "a"), ids(ModuleLinkOrder.plan(listOf(pkgA(), pkgM(), pkgZ()), order)))
        // 缺省配置仍走规范序（显式配置不污染默认行为）。
        assertEquals(listOf("a", "m", "z"), ids(ModuleLinkOrder.plan(listOf(pkgZ(), pkgA(), pkgM()))))
    }

    @Test
    fun preferredUnknownIdsAreIgnoredAndUnlistedFallBackToCanonicalOrder() {
        // 配置里的 ghost 已被卸载/禁用 ⇒ 忽略；只列了 m ⇒ m 先，其余按 packageId 升序。
        val order = LinkOrder(preferredPackageIds = listOf("ghost", "m"))
        assertEquals(listOf("m", "a", "z"), ids(ModuleLinkOrder.plan(listOf(pkgZ(), pkgA(), pkgM()), order)))
        assertEquals(listOf("m", "a", "z"), ids(ModuleLinkOrder.plan(listOf(pkgA(), pkgM(), pkgZ()), order)))
    }

    @Test
    fun preferredDuplicateIdsRejectWholeGroup() {
        val e = assertFailsWith<LinkOrderException> {
            ModuleLinkOrder.plan(listOf(pkgA(), pkgM()), LinkOrder(listOf("a", "a")))
        }
        assertTrue(e.message!!.contains("重复"), e.message)
        assertTrue(e.message!!.contains("[a]"), e.message)
        // 空组合也不接受病态配置（fail-closed，不静默去重）。
        assertFailsWith<LinkOrderException> {
            ModuleLinkOrder.plan(emptyList(), LinkOrder(listOf("a", "a")))
        }
    }

    @Test
    fun dependencyEdgesOutrankPreferredOrder() {
        // a 硬依赖 c ⇒ c 必须先于 a，哪怕显式配置把 a 放最前（排序偏好不制造依赖环）。
        // 对照无配置：规范兜底给 [b,c,a]；显式配置给 [c,a,b] ⇒ 偏好确实改变「同时就绪」的相对序。
        val a = pkg("a", requires = listOf("c"))
        val b = pkg("b")
        val c = pkg("c")
        assertEquals(listOf("b", "c", "a"), ids(ModuleLinkOrder.plan(listOf(a, b, c))))
        val order = LinkOrder(preferredPackageIds = listOf("a", "c", "b"))
        assertEquals(listOf("c", "a", "b"), ids(ModuleLinkOrder.plan(listOf(a, b, c), order)))
        assertEquals(listOf("c", "a", "b"), ids(ModuleLinkOrder.plan(listOf(b, c, a), order)))
    }

    @Test
    fun partialOrderDagIsPermutationInvariant() {
        // a → c，b 独立：有依赖边但**不是全序**（a 与 b 之间无约束）。
        // 只有全序 DAG 的测试会掩盖 tie-break 的不确定性，这条专钉规范兜底。
        val a = pkg("a")
        val b = pkg("b")
        val c = pkg("c", requires = listOf("a"))
        val expected = listOf("a", "b", "c")
        assertEquals(expected, ids(ModuleLinkOrder.plan(listOf(a, b, c))))
        assertEquals(expected, ids(ModuleLinkOrder.plan(listOf(c, b, a))))
        assertEquals(expected, ids(ModuleLinkOrder.plan(listOf(b, c, a))))
        assertEquals(expected, ids(ModuleLinkOrder.plan(listOf(b, a, c))))
    }

    @Test
    fun missingHardDependencyRejectsWholeGroup() {
        val e = assertFailsWith<LinkOrderException> {
            ModuleLinkOrder.plan(listOf(pkg("b", requires = listOf("a"))))
        }
        assertTrue(e.message!!.contains("缺少硬依赖"), e.message)
        assertTrue(e.message!!.contains("[a]"), e.message)
    }

    @Test
    fun cyclesAndDuplicatesAreRejected() {
        val cycle = assertFailsWith<LinkOrderException> {
            ModuleLinkOrder.plan(
                listOf(
                    pkg("a", requires = listOf("b")),
                    pkg("b", requires = listOf("a")),
                ),
            )
        }
        assertTrue(cycle.message!!.contains("依赖环"), cycle.message)
        assertTrue(cycle.message!!.contains("a") && cycle.message!!.contains("b"))

        assertFailsWith<LinkOrderException> {
            ModuleLinkOrder.plan(listOf(pkg("a", requires = listOf("a"))))
        }
        assertFailsWith<LinkOrderException> {
            ModuleLinkOrder.plan(listOf(pkg("a"), pkg("a")))
        }
    }

    @Test
    fun mixedHardAndSoftEdgesAreDeduplicated() {
        // 同一条边同时出现在 requires 与 loadAfter 里：只算一次，不会让 b 永远不就绪。
        val a = pkg("a")
        val b = pkg("b", requires = listOf("a"), loadAfter = listOf("a"))
        assertEquals(listOf("a", "b"), ids(ModuleLinkOrder.plan(listOf(b, a))))
        assertTrue(ModuleLinkOrder.plan(emptyList()).isEmpty())
    }

    /**
     * 语义冻结（二审点名）：[LinkOrder.preferredPackageIds] 是就绪队列的比较键，**不是**
     * 对玩家完整排列做最小调整。配置 `[a,b,c,d]` 且 a 硬依赖 d ⇒ 初始就绪只有 `{b,c,d}`，
     * a 不在候选内 ⇒ 输出 `[b,c,d,a]`；`[d,a,b,c]` 是另一种排序政策，本实现明确不采用。
     */
    @Test
    fun kahnReadyPriorityIsNotMinimalAdjustmentOfFullOrder() {
        val modules = listOf(
            pkg("a", requires = listOf("d")),
            pkg("b"),
            pkg("c"),
            pkg("d"),
        )
        val order = LinkOrder(listOf("a", "b", "c", "d"))
        val actual = ids(ModuleLinkOrder.plan(modules, order))
        assertEquals(listOf("b", "c", "d", "a"), actual)
        assertTrue(
            actual != listOf("d", "a", "b", "c"),
            "不采用『尽量保留玩家完整顺序』政策",
        )
        // 同一配置下任意输入排列仍必须收敛到同一条序（就绪优先级不依赖输入下标）。
        for (permutation in PermutationFixtures.all(modules)) {
            assertEquals(
                actual,
                ids(ModuleLinkOrder.plan(permutation, order)),
                "输入 ${permutation.map { it.packageId }} 也必须收敛到就绪优先级序",
            )
        }
        // 行为可控而非功能残缺：把前置依赖 d 排到 a 前面，即可得到 [d,a,b,c]。
        val prerequisiteFirst = LinkOrder(listOf("d", "a", "b", "c"))
        assertEquals(
            listOf("d", "a", "b", "c"),
            ids(ModuleLinkOrder.plan(modules.asReversed(), prerequisiteFirst)),
        )
    }

    /** 3 / 4 模块无依赖拓扑：穷举全部 6 / 24 种输入排列，逐条收敛到 packageId 规范序。 */
    @Test
    fun allPermutationsOfIndependentPackagesUseCanonicalOrder() {
        for (canonical in listOf(listOf("a", "b", "c"), listOf("a", "b", "c", "d"))) {
            val modules = canonical.map { pkg(it) }
            val permutations = PermutationFixtures.all(modules)
            assertEquals(PermutationFixtures.factorial(canonical.size), permutations.size)
            for (permutation in permutations) {
                assertEquals(
                    canonical,
                    ids(ModuleLinkOrder.plan(permutation)),
                    "输入 ${permutation.map { it.packageId }} 必须收敛到规范序",
                )
            }
        }
    }

    /** 非全序 DAG（a → c，b / d 独立）穷举 24 种排列，依赖边没钉死的部分也排列不变。 */
    @Test
    fun partialOrderDagAllPermutationsUseCanonicalOrder() {
        val modules = listOf(
            pkg("a"),
            pkg("b"),
            pkg("c", requires = listOf("a")),
            pkg("d"),
        )
        val expected = listOf("a", "b", "c", "d")
        val permutations = PermutationFixtures.all(modules)
        assertEquals(PermutationFixtures.factorial(modules.size), permutations.size)
        for (permutation in permutations) {
            assertEquals(
                expected,
                ids(ModuleLinkOrder.plan(permutation)),
                "部分序 DAG 输入 ${permutation.map { it.packageId }} 必须收敛到规范序",
            )
        }
    }

    /** 显式排序配置同样穷举 24 种输入排列：配置有意改序，但同一配置与输入排列无关。 */
    @Test
    fun explicitOrderAllPermutationsUseCanonicalOrder() {
        val modules = listOf(pkg("a"), pkg("b"), pkg("c"), pkg("d"))
        val order = LinkOrder(listOf("d", "b", "a", "c"))
        val expected = listOf("d", "b", "a", "c")
        for (permutation in PermutationFixtures.all(modules)) {
            assertEquals(
                expected,
                ids(ModuleLinkOrder.plan(permutation, order)),
                "显式配置下输入 ${permutation.map { it.packageId }} 必须收敛到同一条序",
            )
        }
        assertEquals(
            listOf("a", "b", "c", "d"),
            ids(ModuleLinkOrder.plan(modules)),
            "缺省配置仍走规范序，不被显式配置污染",
        )
    }

    /** n 较大时不穷举 n!，改用固定种子洗牌覆盖多种就绪节点出现顺序。 */
    @Test
    fun largerSetSeededPermutationsUseCanonicalOrder() {
        val modules = listOf(
            pkg("m1"),
            pkg("m2"),
            pkg("m3"),
            pkg("m4", requires = listOf("m2")),
            pkg("m5"),
            pkg("m6"),
            pkg("m7"),
        )
        val canonical = modules.map { it.packageId }.sorted()
        val shuffles = PermutationFixtures.seeded(modules)
        assertEquals(PermutationFixtures.DEFAULT_SHUFFLE_COUNT, shuffles.size)
        for (permutation in shuffles) {
            assertEquals(
                canonical,
                ids(ModuleLinkOrder.plan(permutation)),
                "大集合输入 ${permutation.map { it.packageId }} 必须收敛到规范序",
            )
        }
    }

    // ------------------------------------------------------------------
    // P1-3：requires 版本约束（pkg@[min,max]，语法权威见 ModuleDescriptor 类 KDoc）
    // ------------------------------------------------------------------

    /** 区间内通过：链接序与裸 requires（无约束）逐位一致（契约判据 2）。 */
    @Test
    fun inRangeConstraintPassesWithIdenticalLinkOrder() {
        val constrained = listOf(
            pkg("a", version = "1.5.0"),
            pkg("b", requires = listOf("a@[1.0.0,2.0.0]")),
        )
        val bare = listOf(pkg("a", version = "1.5.0"), pkg("b", requires = listOf("a")))
        assertEquals(listOf("a", "b"), ids(ModuleLinkOrder.plan(constrained)))
        assertEquals(
            ids(ModuleLinkOrder.plan(bare)),
            ids(ModuleLinkOrder.plan(constrained)),
            "区间内通过与裸 requires 的链接序必须逐位一致",
        )
    }

    /** 双闭区间：两端点都被接受（恰为 min / 恰为 max 都在区间内）。 */
    @Test
    fun closedIntervalIncludesBothEndpoints() {
        val atMin = listOf(pkg("a", version = "1.2.0"), pkg("b", requires = listOf("a@[1.2.0,2.0.0]")))
        val atMax = listOf(pkg("a", version = "2.0.0"), pkg("b", requires = listOf("a@[1.2.0,2.0.0]")))
        assertEquals(listOf("a", "b"), ids(ModuleLinkOrder.plan(atMin)))
        assertEquals(listOf("a", "b"), ids(ModuleLinkOrder.plan(atMax)))
    }

    /** 低于 min ⇒ 整组拒绝，消息四元组：依赖方包 / 目标包 / 声明区间 / 实际串。 */
    @Test
    fun belowMinRejectsWithQuadrupleMessage() {
        val e = assertFailsWith<LinkOrderException> {
            ModuleLinkOrder.plan(
                listOf(pkg("a", version = "1.1.0"), pkg("b", requires = listOf("a@[1.2.0,2.0.0]"))),
            )
        }
        assertTrue(e.message!!.contains("包 [b]"), e.message)
        assertTrue(e.message!!.contains("硬依赖 [a]"), e.message)
        assertTrue(e.message!!.contains("版本区间 [1.2.0,2.0.0]"), e.message)
        assertTrue(e.message!!.contains("实际 [1.1.0]"), e.message)
        assertTrue(e.message!!.contains("版本越界"), e.message)
    }

    /** 高于 max ⇒ 同族拒绝（双闭区间上端越界同样点名实际串）。 */
    @Test
    fun aboveMaxRejects() {
        val e = assertFailsWith<LinkOrderException> {
            ModuleLinkOrder.plan(
                listOf(pkg("a", version = "2.0.1"), pkg("b", requires = listOf("a@[1.2.0,2.0.0]"))),
            )
        }
        assertTrue(e.message!!.contains("实际 [2.0.1]"), e.message)
        assertTrue(e.message!!.contains("版本越界"), e.message)
    }

    /** min == max 钉死单版本：恰等通过，偏差越界。 */
    @Test
    fun minEqualsMaxPinsExactVersion() {
        val pinned = listOf(pkg("a", version = "1.2"), pkg("b", requires = listOf("a@[1.2,1.2]")))
        assertEquals(listOf("a", "b"), ids(ModuleLinkOrder.plan(pinned)))
        val e = assertFailsWith<LinkOrderException> {
            ModuleLinkOrder.plan(
                listOf(pkg("a", version = "1.3"), pkg("b", requires = listOf("a@[1.2,1.2]"))),
            )
        }
        assertTrue(e.message!!.contains("版本越界"), e.message)
    }

    /** 缺段补 0 + 点分段全数值：1.2 == 1.2.0；1.2 < 1.10（字典序会判成 1.2 > 1.10）。 */
    @Test
    fun missingSegmentsAreZeroPaddedAndCompareIsNumeric() {
        // 1.2.0 == 1.2（缺段补 0）：区间 [1.2,1.2] 接受 version "1.2.0"。
        val padded = listOf(pkg("a", version = "1.2.0"), pkg("b", requires = listOf("a@[1.2,1.2]")))
        assertEquals(listOf("a", "b"), ids(ModuleLinkOrder.plan(padded)))
        // 1.2 < 1.10：version 1.2 低于 min 1.10 ⇒ 越界拒绝。若比较器退化为字符串
        // 字典序，"1.2" > "1.10" 会被误判区间内（不抛）——本断言必红（反向证明 ②）。
        val e = assertFailsWith<LinkOrderException> {
            ModuleLinkOrder.plan(
                listOf(pkg("a", version = "1.2"), pkg("b", requires = listOf("a@[1.10,2.0]"))),
            )
        }
        assertTrue(e.message!!.contains("版本越界"), e.message)
    }

    /** 被依赖方 version 为空串 / 不可解析 + 对方有约束 ⇒ 链接期拒绝并点名实际串。 */
    @Test
    fun missingOrUnparsableTargetVersionRejectsAndNamesActual() {
        // version 缺省空串：四元组里实际串回显为空（"实际 []"）。
        val emptyVersion = assertFailsWith<LinkOrderException> {
            ModuleLinkOrder.plan(listOf(pkg("a"), pkg("b", requires = listOf("a@[1.0,2.0]"))))
        }
        assertTrue(emptyVersion.message!!.contains("实际 []"), emptyVersion.message)
        assertTrue(emptyVersion.message!!.contains("为空或不可解析"), emptyVersion.message)
        // 不可解析串原样点名（不做全局 version 强校验：无约束时不许拒）。
        val garbage = assertFailsWith<LinkOrderException> {
            ModuleLinkOrder.plan(
                listOf(pkg("a", version = "v3-beta"), pkg("b", requires = listOf("a@[1.0,2.0]"))),
            )
        }
        assertTrue(garbage.message!!.contains("实际 [v3-beta]"), garbage.message)
        // 对照：同样的垃圾 version，无约束 = 既有行为，不拒。
        assertEquals(
            listOf("a", "b"),
            ids(ModuleLinkOrder.plan(listOf(pkg("a", version = "v3-beta"), pkg("b")))),
        )
    }

    /** 非法语法构造期拒（fail-closed，含原条目串）：前导零 / 非数字 / 空段 / 形状残缺 / min>max。 */
    @Test
    fun illegalConstraintSyntaxFailsAtConstruction() {
        // 前导零（单 0 合法）。
        val leadingZero = assertFailsWith<IllegalArgumentException> {
            ModuleDescriptor(packageId = "b", requires = listOf("a@[01.0,2.0]"))
        }
        assertTrue(leadingZero.message!!.contains("a@[01.0,2.0]"), leadingZero.message)
        // 非数字。
        assertFailsWith<IllegalArgumentException> {
            ModuleDescriptor(packageId = "b", requires = listOf("a@[x,2.0]"))
        }
        // 空段（1..0）。
        assertFailsWith<IllegalArgumentException> {
            ModuleDescriptor(packageId = "b", requires = listOf("a@[1..0,2.0]"))
        }
        // 区间形状残缺：缺右括号 / 裸 @ / 空区间。
        assertFailsWith<IllegalArgumentException> {
            ModuleDescriptor(packageId = "b", requires = listOf("a@[1,2"))
        }
        assertFailsWith<IllegalArgumentException> {
            ModuleDescriptor(packageId = "b", requires = listOf("a@"))
        }
        assertFailsWith<IllegalArgumentException> {
            ModuleDescriptor(packageId = "b", requires = listOf("a@[]"))
        }
        // min > max 构造期判死。
        val inverted = assertFailsWith<IllegalArgumentException> {
            ModuleDescriptor(packageId = "b", requires = listOf("a@[2.0,1.0]"))
        }
        assertTrue(inverted.message!!.contains("下端大于上端"), inverted.message)
        // 合法面不受影响：单 0、裸包名混排照旧。
        ModuleDescriptor(packageId = "b", requires = listOf("a@[0,0]", "a", "c@[1.2.0,2.0.0]"))
    }
}
