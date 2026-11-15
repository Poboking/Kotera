package com.eratw.port.core.patch

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M5-4 刀 1 声明面钉（CONTRACT-RT2-M54IMPL1 判据 1/2/5/6；RECON-RT2-M54 §2 Q1 方案 B /
 * §2 Q2 派生表 / §2 Q3 两段式）：
 * - 拒载名单四类逐类 fail-closed：RELATION / NO / CDFLAG / 0 维族（含重名 = 第五道闸）；
 * - freeze 派生：合成 `__native_chara__` 镜像 schema（同时注册进 componentSchemas）+ 逐格
 *   NativeCharaField 进快照路由域；派生确定性 = 同输入两次 freeze 同构，且输出定序与声明序
 *   无关（注入乱序不改派生 —— 反向证明②的攻击面）；
 * - 零变化口径：无路由 ⇒ 路由域空、镜像不派生、componentSchemas 不含镜像；
 * - 诚实边界：用户变量显式格放行（isStr/镜像类型占位 INT，attach 终审）、全跨度 freeze 拒
 *   （尺寸只在运行期存在，不猜）。
 */
class CharaFieldRouteFreezeTest {

    private fun registry(): DeclarationRegistry = DeclarationRegistry()

    private fun routed(vararg routes: CharaRouteDeclaration, packageId: String = "mod.a"): ModuleDescriptor =
        ModuleDescriptor(packageId = packageId, charaRoutes = routes.toList())

    private fun freezeExpectConflict(
        vararg packages: ModuleDescriptor,
        key: String,
    ): DeclarationConflict {
        val builder = registry()
        for ((i, p) in packages.withIndex()) builder.addPackage(p, source = "src${i}.erbc")
        val e = assertFailsWith<DeclarationConflictException> { builder.freeze() }
        return e.conflicts.single {
            it.domain == DeclarationDomain.CHARA_ROUTE && it.key == key
        }
    }

    /**
     * 路由域的可比较形状（NativeCharaField 不是 data class，equals 是引用比较 ——
     * 逐字段展开成可值比较的行）。
     */
    private fun domainShape(d: CharaFieldRouteDomain): List<List<Any>> = d.routes.map {
        listOf(it.packageId, it.field.variable, it.field.indices.toList(), it.field.field, it.field.isStr)
    }

    // ------------------------------------------------------------------
    // 判据 1：拒载名单四类（+ 0 维族 v1 不开放）逐类 fail-closed
    // ------------------------------------------------------------------

    @Test
    fun relationRouteIsRejectedBecauseRelationBridgeOwnsTheColumn() {
        val c = freezeExpectConflict(routed(CharaRouteDeclaration("RELATION")), key = "RELATION")
        assertTrue(c.reason.contains("关系桥"), c.reason)
        assertEquals(c.first, c.second, "拒载名单是自冲突（无双边）")
    }

    @Test
    fun noRouteIsRejectedBecauseIdentityColumnIsEngineOwned() {
        val c = freezeExpectConflict(routed(CharaRouteDeclaration("NO")), key = "NO")
        assertTrue(c.reason.contains("身份列"), c.reason)
    }

    @Test
    fun cdflagRouteIsRejectedBecauseFlatCellSemanticsIsUndefined() {
        val c = freezeExpectConflict(
            routed(CharaRouteDeclaration("CDFLAG", listOf(1, 2))),
            key = "CDFLAG",
        )
        assertTrue(c.reason.contains("M1-4B"), c.reason)
    }

    @Test
    fun zeroDimFamiliesAreRejectedAsV1Boundary() {
        // 0 维族 = charaStr0D 四族 + charaInt0D 的 ISASSI（RECON TODO-2 在案）；NO 的
        // 身份列分支优先命中，但它也在 0 维族清单里（RECON §2 Q2 行 5/6 合并口径）。
        for (family in listOf("NAME", "CALLNAME", "NICKNAME", "MASTERNAME", "ISASSI")) {
            val c = freezeExpectConflict(routed(CharaRouteDeclaration(family)), key = family)
            assertTrue(c.reason.contains("0 维"), "族 $family 应按 0 维族拒载：${c.reason}")
        }
    }

    @Test
    fun rejectionIsCaseInsensitiveLikeNamePolicy() {
        freezeExpectConflict(routed(CharaRouteDeclaration("relation")), key = "RELATION")
    }

    @Test
    fun duplicateFamilyAcrossPackagesIsRejectedWithBothSides() {
        val c = freezeExpectConflict(
            routed(CharaRouteDeclaration("CFLAG"), packageId = "mod.a"),
            routed(CharaRouteDeclaration("cflag", listOf(3)), packageId = "mod.b"),
            key = "CFLAG",
        )
        assertEquals("mod.a", c.first.packageId)
        assertEquals("mod.b", c.second.packageId)
        assertTrue(c.reason.contains("同变量族"), c.reason)
    }

    @Test
    fun duplicateFamilyWithinOnePackageIsRejected() {
        freezeExpectConflict(
            routed(CharaRouteDeclaration("ABL"), CharaRouteDeclaration("abl", listOf(1))),
            key = "ABL",
        )
    }

    // ------------------------------------------------------------------
    // 判据 2：freeze 派生 —— 逐格行 + 镜像 schema + 确定性
    // ------------------------------------------------------------------

    @Test
    fun fullSpanSystemFamilyDerivesPerCellRowsAndMirrorSchema() {
        val snap = registry().addPackage(routed(CharaRouteDeclaration("CFLAG"))).freeze()
        val domain = snap.charaFieldRoutes
        assertTrue(!domain.isEmpty())
        val mirror = assertNotNull(domain.mirrorSchema)
        assertEquals(MIRROR_SCHEMA_NAME, mirror.schema.name)
        // 镜像同时注册进 componentSchemas（EntityKernel 字段注册面唯一真源，RECON §1.2）。
        assertEquals(mirror.schema, snap.componentSchema(MIRROR_SCHEMA_NAME))
        // 出厂尺寸 CFLAG = 1000（VariableSizes.applyDefaults），逐格行 1000 条。
        assertEquals(1000, domain.routes.size)
        assertEquals(1000, mirror.schema.fields.size)
        val first = domain.routes.first()
        assertEquals("mod.a", first.packageId)
        assertEquals("CFLAG", first.field.variable)
        assertEquals(listOf(0L), first.field.indices.toList())
        assertEquals("CFLAG:0", first.field.field)
        assertEquals(false, first.field.isStr)
        assertEquals(999L, domain.routes[999].field.indices.single(), "0 基格：第 1000 行下标 999")
        assertEquals("CFLAG:999", domain.routes[999].field.field)
        // 镜像字段全 INT、非 entityRef（行格子不是 EntityId，实体身份走图边）。
        assertTrue(mirror.schema.fields.all { it.type == ComponentFieldType.INT && !it.isEntityRef })
        assertEquals("CFLAG:0", mirror.schema.fields.first().name)
        assertEquals("CFLAG:999", mirror.schema.fields.last().name)
    }

    @Test
    fun strFamilyDerivesStrRowsAndStrMirrorFields() {
        val snap = registry().addPackage(routed(CharaRouteDeclaration("CSTR"))).freeze()
        val domain = snap.charaFieldRoutes
        assertEquals(100, domain.routes.size, "CSTR 出厂尺寸 = 100")
        assertTrue(domain.routes.all { it.field.isStr })
        val mirror = assertNotNull(domain.mirrorSchema)
        assertTrue(mirror.schema.fields.all { it.type == ComponentFieldType.STR && !it.isEntityRef })
    }

    @Test
    fun explicitCellsDeriveSortedRowsAndFactoryBoundIsChecked() {
        val snap = registry()
            .addPackage(
                routed(CharaRouteDeclaration("TALENT", listOf(5, 2)), CharaRouteDeclaration("ABL", listOf(0, 1))),
            )
            .freeze()
        val routes = snap.charaFieldRoutes.routes
        // 输出按（族名字典序，族内格号升序）定序 —— 与声明序无关（派生确定性判据的强形态）。
        assertEquals(
            listOf("ABL:0", "ABL:1", "TALENT:2", "TALENT:5"),
            routes.map { it.field.field },
        )
        // 声明序 (5,2) 派生成升序 (2,5)，格下标各自落行。
        assertEquals(2L, routes[2].field.indices.single())
        assertEquals(5L, routes[3].field.indices.single())
        // TALENT 出厂尺寸 1000：下标 1000 越界（freeze 期只查声明侧显式下标，RECON §2 Q3）。
        val c = freezeExpectConflict(routed(CharaRouteDeclaration("TALENT", listOf(1000))), key = "TALENT")
        assertTrue(c.reason.contains("出厂尺寸"), c.reason)
        // 边界内最大下标合法：CFLAG 999。
        val ok = registry().addPackage(routed(CharaRouteDeclaration("CFLAG", listOf(999)))).freeze()
        assertEquals(listOf("CFLAG:999"), ok.charaFieldRoutes.routes.map { it.field.field })
    }

    @Test
    fun userVariableExplicitCellsPassWithIntPlaceholderAndFullSpanIsRejected() {
        // 用户角色变量（非 SystemLayout 注册名）：显式格放行，isStr/镜像类型占位 INT
        // （attach 期以 def 类型终审 —— RECON §2 Q2/Q3 两段式，刀 2 面）。
        val snap = registry().addPackage(routed(CharaRouteDeclaration("M_MOOD", listOf(3)))).freeze()
        val row = snap.charaFieldRoutes.routes.single().field
        assertEquals("M_MOOD", row.variable)
        assertEquals("M_MOOD:3", row.field)
        assertEquals(false, row.isStr, "freeze 期用户变量类型不可知，占位 INT（attach 终审）")
        assertEquals(
            ComponentFieldType.INT,
            assertNotNull(snap.charaFieldRoutes.mirrorSchema).schema.fields.single().type,
        )
        // 全跨度派生需要出厂尺寸；用户变量尺寸只在运行期存在 ⇒ freeze 拒（不猜）。
        val c = freezeExpectConflict(routed(CharaRouteDeclaration("M_MOOD")), key = "M_MOOD")
        assertTrue(c.reason.contains("全跨度"), c.reason)
    }

    @Test
    fun freezeDerivationIsDeterministicForIdenticalInput() {
        val d = routed(CharaRouteDeclaration("CFLAG", listOf(7, 3)), CharaRouteDeclaration("CSTR", listOf(1)))
        val s1 = registry().addPackage(d, source = "x.erbc").freeze()
        val s2 = DeclarationRegistry().addPackage(d, source = "x.erbc").freeze()
        assertEquals(domainShape(s1.charaFieldRoutes), domainShape(s2.charaFieldRoutes), "同输入两次 freeze 路由域同构")
        assertEquals(
            s1.componentSchemas[NamePolicy.canonical(MIRROR_SCHEMA_NAME)],
            s2.componentSchemas[NamePolicy.canonical(MIRROR_SCHEMA_NAME)],
        )
    }

    @Test
    fun freezeDerivationIsOrderInsensitiveAcrossDeclarations() {
        // 声明序无关：同一路由集合换序（跨包 + 包内两档）派生结果逐条相同（含包身份）。
        val routesA = listOf(CharaRouteDeclaration("CFLAG", listOf(7, 3)), CharaRouteDeclaration("CSTR", listOf(1)))
        val routesB = listOf(CharaRouteDeclaration("CSTR", listOf(1)), CharaRouteDeclaration("CFLAG", listOf(3, 7)))
        val s1 = registry()
            .addPackage(ModuleDescriptor("mod.a", charaRoutes = routesA))
            .addPackage(ModuleDescriptor("mod.b", charaRoutes = listOf(CharaRouteDeclaration("ABL", listOf(4)))))
            .freeze()
        val s2 = registry()
            .addPackage(ModuleDescriptor("mod.b", charaRoutes = listOf(CharaRouteDeclaration("ABL", listOf(4)))))
            .addPackage(ModuleDescriptor("mod.a", charaRoutes = routesB))
            .freeze()
        assertEquals(domainShape(s1.charaFieldRoutes), domainShape(s2.charaFieldRoutes))
        assertEquals(
            s1.charaFieldRoutes.routes.map { it.packageId }.distinct().sorted(),
            listOf("mod.a", "mod.b"),
        )
        assertEquals(s1.charaFieldRoutes.mirrorSchema, s2.charaFieldRoutes.mirrorSchema)
    }

    // ------------------------------------------------------------------
    // 判据 5：零变化口径 —— 无路由 ⇒ 路由域空、镜像不派生
    // ------------------------------------------------------------------

    @Test
    fun noRoutesMeansEmptyRouteDomainAndNoMirror() {
        val snap = registry()
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.plain",
                    componentSchemas = listOf(ComponentSchema("mod.plain", "Stats", listOf(ComponentFieldSpec("hp")))),
                ),
            )
            .freeze()
        assertTrue(snap.charaFieldRoutes.isEmpty())
        assertNull(snap.charaFieldRoutes.mirrorSchema)
        assertTrue(snap.charaFieldRoutes.routes.isEmpty())
        assertNull(snap.componentSchema(MIRROR_SCHEMA_NAME), "镜像不派生：componentSchemas 不含 __native_chara__")
        assertEquals(setOf("STATS"), snap.componentSchemas.keys)
    }

    // ------------------------------------------------------------------
    // 保留名与镜像字段撞名（fail-closed；v1 无「用户优先」语义，RECON 需组长接线 3）
    // ------------------------------------------------------------------

    @Test
    fun userDeclaredMirrorSchemaNameIsRejectedWhenRoutesExist() {
        val c = freezeExpectConflict(
            ModuleDescriptor(
                packageId = "mod.a",
                componentSchemas = listOf(ComponentSchema("mod.a", "__native_chara__")),
                charaRoutes = listOf(CharaRouteDeclaration("CFLAG", listOf(0))),
            ),
            key = NamePolicy.canonical(MIRROR_SCHEMA_NAME),
        )
        assertTrue(c.reason.contains("保留名"), c.reason)
    }

    @Test
    fun mirrorFieldNameCollidingWithDeclaredFieldIsRejected() {
        val c = freezeExpectConflict(
            ModuleDescriptor(
                packageId = "mod.a",
                componentSchemas = listOf(
                    ComponentSchema("mod.a", "Stats", listOf(ComponentFieldSpec("CFLAG:3"))),
                ),
                charaRoutes = listOf(CharaRouteDeclaration("CFLAG", listOf(3))),
            ),
            key = "CFLAG:3",
        )
        assertTrue(c.reason.contains("撞名"), c.reason)
    }

    // ------------------------------------------------------------------
    // 整包拒绝口径：路由冲突 = 无部分注册，重放 freeze 同样红
    // ------------------------------------------------------------------

    @Test
    fun routeConflictRejectsWholePackageWithoutPartialSnapshot() {
        val builder = registry()
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.a",
                    componentSchemas = listOf(ComponentSchema("mod.a", "Stats")),
                    charaRoutes = listOf(CharaRouteDeclaration("RELATION")),
                ),
            )
        val e = assertFailsWith<DeclarationConflictException> { builder.freeze() }
        assertEquals(1, e.conflicts.count { it.domain == DeclarationDomain.CHARA_ROUTE })
        // 冻结未发生：重放同样冲突（声明事务无部分注册，成功才产出快照）。
        val e2 = assertFailsWith<DeclarationConflictException> { builder.freeze() }
        assertEquals(e.conflicts, e2.conflicts)
    }

    // ------------------------------------------------------------------
    // wire 载体：component-schemas 段判别 tag（B0 缺省 / B1 路由；段数 18 不变）
    // ------------------------------------------------------------------

    private fun encodeLines(d: ModuleDescriptor): List<String> =
        String(ManifestCodec.encode(d), Charsets.UTF_8).removeSuffix("\n").split("\n")

    private fun toBytes(lines: List<String>): ByteArray = (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)

    private fun sectionSlice(lines: List<String>, name: String): List<String> {
        val start = lines.indexOf("@$name")
        var end = start
        while (lines[end] != "/") end++
        return lines.subList(start + 1, end)
    }

    @Test
    fun routeDescriptorRidesComponentSchemasSectionWithB1Subtype() {
        val d = ModuleDescriptor(
            packageId = "mod.wire",
            componentSchemas = listOf(ComponentSchema("mod.wire", "Stats")),
            charaRoutes = listOf(
                CharaRouteDeclaration("CFLAG", listOf(3, 1)),
                CharaRouteDeclaration("CSTR"),
            ),
        )
        val lines = encodeLines(d)
        val body = sectionSlice(lines, "component-schemas")
        // 计数 = schema 数 + 路由数（1 + 2）；裸 schema 不落 tag（缺省形），路由条目带 B1。
        assertEquals("I3", body.first())
        assertEquals(2, body.count { it == "B1" }, "两条路由合并进同一计数，B1 行各一条")
        val back = ManifestCodec.decode(toBytes(lines))
        assertEquals(d.charaRoutes, back.charaRoutes)
        assertEquals(d.componentSchemas, back.componentSchemas)
        // write → read → write 逐字节相同（确定性口径）。
        assertTrue(ManifestCodec.encode(d).contentEquals(ManifestCodec.encode(back)))
    }

    @Test
    fun routelessDescriptorEmitsNoDiscriminantTagAndRoundTripsByteIdentical() {
        // 无路由 = 缺省形：component-schemas 段（以及全文）不出现 B1 行 —— 旧档字节逐位一致。
        for (d in listOf(
            ModuleDescriptor(packageId = "mod.empty"),
            ModuleDescriptor(
                packageId = "mod.plain",
                componentSchemas = listOf(ComponentSchema("mod.plain", "Stats", listOf(ComponentFieldSpec("hp")))),
            ),
        )) {
            val bytes = ManifestCodec.encode(d)
            val text = String(bytes, Charsets.UTF_8)
            assertTrue(!text.contains("B1"), "无路由描述符不得产出判别 tag 行")
            assertTrue(bytes.contentEquals(ManifestCodec.encode(ManifestCodec.decode(bytes))), "往返逐字节不变")
        }
    }

    @Test
    fun explicitB0TaggedSchemaEntriesAreAccepted() {
        // 缺省 tag 的显式形：读侧接受条目首的 B0 行（写侧不产 —— 旧档字节兼容口径）。
        val d = ModuleDescriptor(
            packageId = "mod.tag",
            componentSchemas = listOf(ComponentSchema("mod.tag", "Stats")),
        )
        val lines = encodeLines(d).toMutableList()
        val countIdx = lines.indexOf("@component-schemas") + 1
        lines.add(countIdx + 1, "B0")
        val back = ManifestCodec.decode(toBytes(lines))
        assertEquals(d.componentSchemas, back.componentSchemas)
        assertTrue(back.charaRoutes.isEmpty())
    }

    @Test
    fun unknownDiscriminantTagInComponentSchemasIsRejected() {
        val lines = encodeLines(
            ModuleDescriptor(
                packageId = "mod.bad",
                componentSchemas = listOf(ComponentSchema("mod.bad", "Stats")),
            ),
        ).toMutableList()
        val countIdx = lines.indexOf("@component-schemas") + 1
        lines[countIdx + 1] = "B9"
        val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(lines)) }
        assertTrue(ex.message!!.contains("component-schemas 判别 tag"), ex.message)
    }
}
